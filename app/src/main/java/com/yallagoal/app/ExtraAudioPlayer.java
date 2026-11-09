package com.yallagoal.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * مشغّل «الصوت الإضافي»: مشغّل ExoPlayer ثانٍ (صوت فقط) يعمل بالتوازي مع المشغّل الرئيسي لقناة/فيلم/حلقة،
 * ويسمح بإضافة رابط صوتي (بثّ مباشر غير منتهٍ، mp3/aac/m4a/ogg/mp4، HLS، قوائم .pls/.m3u) أو ملف من الجهاز.
 *
 * <h3>المزج</h3>
 * مستوى الصوت الأصلي ومستوى الصوت الإضافي مستقلان (0–100%) مع كتم مستقل لكلٍّ منهما، وأوضاع سريعة:
 * الصوتان معاً / الرابط فقط / الأصلي فقط. إن تعذّر الصوت الإضافي (خطأ/إعادة اتصال) يعود صوت القناة
 * الأصلي تلقائياً ولو كان مكتوماً، فلا يبقى المستخدم بلا صوت.
 *
 * <h3>المزامنة (الأهم)</h3>
 * إزاحة {@link #getOffsetMs()} = مقدار تقديم (+) أو ترجيع (−) الصوت الإضافي نسبةً لوضعه الطبيعي مع الصورة.
 * يُنفَّذ بحسب نوع المصدر:
 * <ul>
 *   <li>{@link SyncKind#COUPLED}: فيلم/حلقة + ملف صوتي قابل للسَّعي: الصوت مربوط بزمن الفيديو
 *       (الموضع = موضع الفيديو + الإزاحة) ويتبعه عند التوقف والسَّعي والتخزين المؤقت، مع تصحيح الانحراف.</li>
 *   <li>{@link SyncKind#SEEK_FREE}: مصدر قابل للسَّعي فوق قناة مباشرة (أو HLS مباشر بنافذة): تقديم/ترجيع بسَعي نسبي.</li>
 *   <li>{@link SyncKind#LIVE_DELAY}: بث صوتي مباشر لا يقبل السَّعي: ترجيع/تقديم عبر {@link TimeShiftAudioProcessor}.</li>
 * </ul>
 * كذلك «إيقاف مؤقت للصوت الإضافي فقط» و«العودة للمباشر/التصفير». التشغيل/الإيقاف يتبع الفيديو دائماً.
 *
 * <p>كل الدوال العامة تُستدعى من خيط الواجهة فقط.
 */
@UnstableApi
final class ExtraAudioPlayer {

    static final int MIX_BOTH = 0;
    static final int MIX_EXTRA_ONLY = 1;
    static final int MIX_ORIGINAL_ONLY = 2;
    static final int MIX_CUSTOM = 3;

    enum Status { NONE, LOADING, PLAYING, WAITING, PAUSED, RECONNECTING, ERROR, ENDED }

    enum SyncKind { UNKNOWN, COUPLED, SEEK_FREE, LIVE_DELAY }

    interface Listener {
        /** تغيّر أي شيء يهم الواجهة (حالة/إزاحة/مستويات/مصدر). */
        void onExtraAudioChanged();

        /** رسالة قصيرة تُعرض للمستخدم (Toast). */
        void onExtraAudioMessage(String message);
    }

    /** أقصى إزاحة مسموحة (±120 ثانية). */
    static final long MAX_SHIFT_MS = TimeShiftAudioProcessor.MAX_DELAY_MS;

    private static final String UA = "YallaGoal/1.0 (Android; ExoPlayer)";
    private static final long TICK_MS = 250L;
    private static final long DRIFT_MS = 350L;
    private static final long CORRECTION_COOLDOWN_MS = 1800L;
    private static final long OFFSET_APPLY_DEBOUNCE_MS = 160L;
    private static final long PERSIST_DEBOUNCE_MS = 450L;
    private static final int MAX_RETRIES = 6;

    private final Context app;
    private final PlaybackEngine main;
    private final boolean mainIsLive;
    private final String storeKey;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Listener listener;

    // ---- الإعداد (هو ما يُحفَظ)
    private String sourceUri = "";
    private boolean sourceIsFile = false;
    private String sourceName = "";
    private float origVol = 1f;
    private float extVol = 1f;
    private boolean origMuted = false;
    private boolean extMuted = false;
    private long offsetMs = 0L;
    private boolean saved = false;

    // ---- الحالة الحية
    private boolean userPaused = false;
    private Status status = Status.NONE;
    private String statusMessage = "";
    private SyncKind kind = SyncKind.UNKNOWN;
    private boolean released = false;
    private float mainSpeed = 1f;

    private ExoPlayer player;
    private TimeShiftAudioProcessor shifter;
    private OkHttpClient client;
    private ExecutorService io;

    private int sessionToken = 0;
    private int attempt = 0; // 0 مباشر، 1 تلميح HLS، 2 حلّ قائمة تشغيل، 3 الرابط المحلول
    private String resolvedUrl;
    private boolean resolvedIsHls;
    private int retries = 0;
    private boolean everReady = false;
    private long lastCorrectionAt = 0L;
    private long pendingSeekDelta = 0L;
    private boolean memoryWarned = false;
    /** مصدر منتهٍ بمدة معروفة (ملف) — انتهاؤه ليس انقطاع بثّ فلا نُعيد الاتصال به. */
    private boolean finiteSource = false;

    ExtraAudioPlayer(Context context, PlaybackEngine mainEngine, boolean mainIsLive, String storeKey) {
        this.app = context.getApplicationContext();
        this.main = mainEngine;
        this.mainIsLive = mainIsLive;
        this.storeKey = storeKey;
    }

    void setListener(Listener l) {
        this.listener = l;
    }

    // =====================================================================================
    //  الإعداد من الخارج
    // =====================================================================================

    /** يستعيد إعداداً محفوظاً ويبدأ التشغيل. */
    void restore(ExtraAudioStore.Profile p) {
        if (released || p == null || p.source.isEmpty()) {
            return;
        }
        sourceUri = p.source;
        sourceIsFile = p.isFile();
        sourceName = p.name;
        origVol = p.originalVolumePct / 100f;
        extVol = p.extraVolumePct / 100f;
        origMuted = p.originalMuted;
        extMuted = p.extraMuted;
        offsetMs = clamp(p.offsetMs, -MAX_SHIFT_MS, MAX_SHIFT_MS);
        userPaused = false;
        saved = true;
        startSource();
    }

    /**
     * يضبط رابطاً صوتياً (إضافة أو استبدال).
     *
     * @return null عند النجاح، أو رسالة خطأ عربية جاهزة للعرض.
     */
    String setSourceUrl(String raw) {
        return setSourceUrl(raw, null);
    }

    /** نفس {@link #setSourceUrl(String)} مع اسم عرض اختياري (مثل اسم محطة الراديو). */
    String setSourceUrl(String raw, String displayName) {
        String url = normalizeInputUrl(raw);
        if (url == null) {
            return "الرابط غير صالح. الصق رابطاً يبدأ بـ http:// أو https://";
        }
        if (isYoutubeUrl(url)) {
            return "روابط يوتيوب غير مدعومة. استخدم رابطاً مباشراً لملف صوتي أو بثّ صوتي.";
        }
        final String name = (displayName == null || displayName.trim().isEmpty())
                ? displayNameForUrl(url) : displayName.trim();
        beginNewSource(url, false, name);
        return null;
    }

    /** يضبط ملفاً محلياً (content:// أو file://). */
    void setSourceFile(String uri, String displayName) {
        if (uri == null || uri.isEmpty()) {
            return;
        }
        beginNewSource(uri, true, displayName == null || displayName.isEmpty() ? "ملف صوتي" : displayName);
    }

    private void beginNewSource(String src, boolean isFile, String name) {
        if (released) {
            return;
        }
        final boolean first = !hasSource();
        sourceUri = src;
        sourceIsFile = isFile;
        sourceName = name;
        offsetMs = 0L; // مصدر جديد = محاذاة جديدة
        userPaused = false;
        if (first) {
            origVol = 1f;
            extVol = 1f;
            origMuted = false;
            extMuted = false;
        }
        startSource();
        persistNow();
        notifyChanged();
    }

    /** إعادة تحميل نفس المصدر (يُبقي المستويات والإزاحة). */
    void reload() {
        if (released || !hasSource()) {
            return;
        }
        startSource();
        message("تمت إعادة تحميل الصوت");
    }

    /** حذف الصوت الإضافي والعودة لصوت القناة الأصلي (ويحذف الإعداد المحفوظ). */
    void clear() {
        if (released) {
            return;
        }
        stopPlayerOnly();
        sessionToken++;
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(persistRunnable);
        sourceUri = "";
        sourceName = "";
        sourceIsFile = false;
        offsetMs = 0L;
        origVol = 1f;
        extVol = 1f;
        origMuted = false;
        extMuted = false;
        userPaused = false;
        kind = SyncKind.UNKNOWN;
        status = Status.NONE;
        statusMessage = "";
        saved = false;
        ExtraAudioStore.delete(app, storeKey);
        main.setVolume(1f);
        notifyChanged();
    }

    /** يُستدعى من الواجهة عند مغادرة المشغّل نهائياً. لا يمسح الإعداد المحفوظ. */
    void release() {
        if (released) {
            return;
        }
        persistNow();
        released = true;
        handler.removeCallbacksAndMessages(null);
        stopPlayerOnly();
        try {
            if (io != null) {
                io.shutdownNow();
            }
            if (client != null) {
                client.dispatcher().executorService().shutdown();
                client.connectionPool().evictAll();
            }
        } catch (Throwable ignored) {
            // تنظيف فقط
        }
        listener = null;
    }

    // =====================================================================================
    //  المزج: مستويات وكتم
    // =====================================================================================

    void setOriginalVolumePct(int pct) {
        origVol = ExtraAudioStore.clampPct(pct) / 100f;
        afterMixChange();
    }

    void setExtraVolumePct(int pct) {
        extVol = ExtraAudioStore.clampPct(pct) / 100f;
        afterMixChange();
    }

    void setOriginalMuted(boolean m) {
        origMuted = m;
        afterMixChange();
    }

    void setExtraMuted(boolean m) {
        extMuted = m;
        afterMixChange();
    }

    void setMixMode(int mode) {
        if (mode == MIX_BOTH) {
            origMuted = false;
            extMuted = false;
        } else if (mode == MIX_EXTRA_ONLY) {
            origMuted = true;
            extMuted = false;
        } else if (mode == MIX_ORIGINAL_ONLY) {
            origMuted = false;
            extMuted = true;
        }
        afterMixChange();
    }

    int getMixMode() {
        if (!origMuted && !extMuted) {
            return MIX_BOTH;
        }
        if (origMuted && !extMuted) {
            return MIX_EXTRA_ONLY;
        }
        if (!origMuted) {
            return MIX_ORIGINAL_ONLY;
        }
        return MIX_CUSTOM;
    }

    private void afterMixChange() {
        applyVolumes();
        schedulePersist();
        notifyChanged();
    }

    /** منحنى مستوى إدراكي: ربع القيمة عند 50% بدل النصف، فتكون الشريحة أدقّ في المستويات المنخفضة. */
    private static float taper(float v) {
        float c = v < 0f ? 0f : (v > 1f ? 1f : v);
        return c * c;
    }

    private void applyVolumes() {
        if (released) {
            return;
        }
        if (!hasSource()) {
            main.setVolume(1f);
            return;
        }
        // سلامة: إن تعذّر الصوت الإضافي يعود الأصلي ولو كان مكتوماً.
        final boolean extraFailing = status == Status.ERROR || status == Status.RECONNECTING;
        final boolean origEffectiveMuted = origMuted && !extraFailing;
        main.setVolume(origEffectiveMuted ? 0f : taper(origVol));
        if (player != null) {
            player.setVolume(extMuted ? 0f : taper(extVol));
        }
    }

    // =====================================================================================
    //  المزامنة: ترجيع/تقديم/إيقاف مؤقت
    // =====================================================================================

    /** تقديم (+) أو ترجيع (−) الصوت الإضافي بمقدار delta (ms). */
    void nudge(long deltaMs) {
        setOffsetMs(offsetMs + deltaMs, true);
    }

    /** ضبط الإزاحة المطلقة (ms). */
    void setOffsetMs(long newOffset, boolean userAction) {
        if (released || !hasSource()) {
            return;
        }
        final long lo = minOffset();
        final long hi = maxOffset();
        final long clamped = clamp(newOffset, lo, hi);
        if (clamped != newOffset && userAction) {
            if (newOffset > hi) {
                message(kind == SyncKind.LIVE_DELAY
                        ? "الصوت على أحدث نقطة في البث المباشر ولا يمكن تقديمه أكثر. للتقديم أرجِع الصورة (الفيديو) للخلف."
                        : "وصلت لأقصى تقديم (" + (hi / 1000) + " ث)");
            } else {
                message("وصلت لأقصى ترجيع (" + (-lo / 1000) + " ث)");
            }
        }
        final long applied = clamped - offsetMs;
        if (applied == 0L) {
            return;
        }
        offsetMs = clamped;
        applyOffsetChange(applied);
        schedulePersist();
        notifyChanged();
    }

    /** يصفّر الإزاحة (والعودة للحافة الحية في البث المباشر). */
    void resetOffset() {
        if (released || !hasSource()) {
            return;
        }
        if (kind == SyncKind.SEEK_FREE && player != null && player.isCurrentMediaItemLive()) {
            try {
                player.seekToDefaultPosition();
            } catch (Throwable ignored) {
                // نكمل بالتصفير النسبي
            }
            pendingSeekDelta = 0L;
            if (offsetMs != 0L) {
                offsetMs = 0L;
                schedulePersist();
                notifyChanged();
            }
            return;
        }
        setOffsetMs(0L, true);
    }

    private void applyOffsetChange(long appliedDelta) {
        switch (kind) {
            case LIVE_DELAY:
                if (shifter != null) {
                    shifter.setDelayMs(-offsetMs);
                }
                break;
            case SEEK_FREE:
                pendingSeekDelta += appliedDelta;
                handler.removeCallbacks(applyOffsetRunnable);
                handler.postDelayed(applyOffsetRunnable, OFFSET_APPLY_DEBOUNCE_MS);
                break;
            case COUPLED:
                handler.removeCallbacks(applyOffsetRunnable);
                handler.postDelayed(applyOffsetRunnable, OFFSET_APPLY_DEBOUNCE_MS);
                break;
            default:
                // لم يُعرف النوع بعد: تُطبَّق الإزاحة عند أول جاهزية.
                break;
        }
    }

    private final Runnable applyOffsetRunnable = new Runnable() {
        @Override
        public void run() {
            if (released || player == null) {
                return;
            }
            if (kind == SyncKind.COUPLED) {
                alignCoupled(true);
            } else if (kind == SyncKind.SEEK_FREE) {
                final long d = pendingSeekDelta;
                pendingSeekDelta = 0L;
                if (d != 0L) {
                    seekRelative(d);
                }
            }
        }
    };

    private void seekRelative(long delta) {
        try {
            final long dur = player.getDuration();
            long target = player.getCurrentPosition() + delta;
            if (target < 0L) {
                target = 0L;
            }
            if (dur != C.TIME_UNSET && dur > 0L && target > dur - 300L) {
                target = Math.max(0L, dur - 300L);
            }
            player.seekTo(target);
        } catch (Throwable ignored) {
            // سعي فاشل لا يجوز أن يُسقط التطبيق
        }
    }

    long minOffset() {
        return -MAX_SHIFT_MS;
    }

    long maxOffset() {
        return kind == SyncKind.LIVE_DELAY ? 0L : MAX_SHIFT_MS;
    }

    /** إيقاف/استئناف الصوت الإضافي وحده (الفيديو يستمر). */
    void setUserPaused(boolean paused) {
        if (released || !hasSource() || userPaused == paused) {
            return;
        }
        userPaused = paused;
        tick();
        notifyChanged();
    }

    // =====================================================================================
    //  ربط مع المشغّل الرئيسي
    // =====================================================================================

    /** سرعة الفيديو الرئيسي تغيّرت (للفيديو المربوط فقط). */
    void onMainSpeedChanged(float speed) {
        mainSpeed = speed <= 0f ? 1f : speed;
        if (player != null && kind == SyncKind.COUPLED) {
            try {
                player.setPlaybackParameters(new PlaybackParameters(mainSpeed));
            } catch (Throwable ignored) {
                // غير حرج
            }
        }
    }

    /** تغيّرت حالة التشغيل/الإيقاف بالفيديو: نتفاعل فوراً دون انتظار الدورة التالية. */
    void onMainPlayingChanged() {
        if (!released && player != null) {
            handler.post(this::tick);
        }
    }

    // =====================================================================================
    //  حفظ
    // =====================================================================================

    boolean isSaved() {
        return saved;
    }

    /** تفعيل الحفظ = يُحفَظ الإعداد الحالي ويُستعاد تلقائياً عند العودة لهذا المحتوى، ويتحدّث مع كل تعديل. */
    void setSaved(boolean on) {
        if (released || !hasSource()) {
            return;
        }
        saved = on;
        if (on) {
            persistNow();
        } else {
            ExtraAudioStore.delete(app, storeKey);
        }
        notifyChanged();
    }

    private final Runnable persistRunnable = this::persistNow;

    private void schedulePersist() {
        if (!saved) {
            return;
        }
        handler.removeCallbacks(persistRunnable);
        handler.postDelayed(persistRunnable, PERSIST_DEBOUNCE_MS);
    }

    private void persistNow() {
        if (!saved || !hasSource()) {
            return;
        }
        final ExtraAudioStore.Profile p = new ExtraAudioStore.Profile();
        p.type = sourceIsFile ? ExtraAudioStore.Profile.TYPE_FILE : ExtraAudioStore.Profile.TYPE_URL;
        p.source = sourceUri;
        p.name = sourceName;
        p.originalVolumePct = Math.round(origVol * 100f);
        p.extraVolumePct = Math.round(extVol * 100f);
        p.originalMuted = origMuted;
        p.extraMuted = extMuted;
        p.offsetMs = offsetMs;
        ExtraAudioStore.save(app, storeKey, p);
    }

    // =====================================================================================
    //  قراءة الحالة (للواجهة)
    // =====================================================================================

    boolean hasSource() {
        return !sourceUri.isEmpty();
    }

    String getSourceName() {
        return sourceName;
    }

    boolean isFileSource() {
        return sourceIsFile;
    }

    Status getStatus() {
        return status;
    }

    String getStatusMessage() {
        return statusMessage;
    }

    SyncKind getSyncKind() {
        return kind;
    }

    long getOffsetMs() {
        return offsetMs;
    }

    int getOriginalVolumePct() {
        return Math.round(origVol * 100f);
    }

    int getExtraVolumePct() {
        return Math.round(extVol * 100f);
    }

    boolean isOriginalMuted() {
        return origMuted;
    }

    boolean isExtraMuted() {
        return extMuted;
    }

    boolean isUserPaused() {
        return userPaused;
    }

    /** هل الصوت الإضافي بثّ مباشر (لتبديل نص زر التصفير إلى «للمباشر»). */
    boolean isLiveLike() {
        return kind == SyncKind.LIVE_DELAY
                || (kind == SyncKind.SEEK_FREE && player != null && player.isCurrentMediaItemLive());
    }

    // =====================================================================================
    //  البدء والمحاولات
    // =====================================================================================

    private void startSource() {
        if (released || !hasSource()) {
            return;
        }
        stopPlayerOnly();
        sessionToken++;
        attempt = (!sourceIsFile && looksLikePlaylistUrl(sourceUri)) ? 2 : 0;
        retries = 0;
        everReady = false;
        resolvedUrl = null;
        resolvedIsHls = false;
        kind = SyncKind.UNKNOWN;
        finiteSource = false;
        pendingSeekDelta = 0L;
        lastCorrectionAt = 0L;
        setStatus(Status.LOADING, "جارٍ التحميل…");
        applyVolumes();
        runAttempt();
        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, TICK_MS);
    }

    private void runAttempt() {
        if (released) {
            return;
        }
        if (attempt == 2) {
            resolvePlaylistAsync(sessionToken);
            return;
        }
        final String uri = (attempt == 3 && resolvedUrl != null) ? resolvedUrl : sourceUri;
        final boolean hls = attempt == 1 || (attempt == 3 && resolvedIsHls);
        prepareWith(uri, hls);
    }

    private void prepareWith(String uriStr, boolean hlsHint) {
        try {
            ensurePlayer();
            final MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(uriStr));
            if (hlsHint) {
                b.setMimeType(MimeTypes.APPLICATION_M3U8);
            }
            player.setMediaItem(b.build());
            player.setPlayWhenReady(false);
            player.prepare();
            applyVolumes();
        } catch (Throwable t) {
            fail("تعذّر تشغيل الصوت الإضافي: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        }
    }

    private void ensurePlayer() {
        if (player != null) {
            return;
        }
        final TimeShiftAudioProcessor proc = new TimeShiftAudioProcessor();
        shifter = proc;
        final Context ctx = app;

        final RenderersFactory renderers = (eventHandler, videoListener, audioListener, textOutput, metadataOutput) -> {
            final AudioSink sink = new DefaultAudioSink.Builder(ctx)
                    .setAudioProcessors(new AudioProcessor[]{proc})
                    .build();
            return new Renderer[]{
                    new MediaCodecAudioRenderer(ctx, MediaCodecSelector.DEFAULT, true, eventHandler, audioListener, sink)
            };
        };

        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
        }
        final DataSource.Factory http = new OkHttpDataSource.Factory(client).setUserAgent(UA);
        final DataSource.Factory dataSource = new DefaultDataSource.Factory(ctx, http);
        final DefaultExtractorsFactory extractors = new DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true);
        final LoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(20_000, 60_000, 1_500, 3_000)
                .setBackBuffer(30_000, true)
                .build();

        final ExoPlayer p = new ExoPlayer.Builder(ctx, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSource, extractors))
                .setLoadControl(loadControl)
                .build();
        p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build());
        p.setRepeatMode(Player.REPEAT_MODE_OFF);
        p.addListener(playerListener);
        player = p;
    }

    private void stopPlayerOnly() {
        handler.removeCallbacks(applyOffsetRunnable);
        handler.removeCallbacks(reconnectRunnable);
        final ExoPlayer p = player;
        player = null;
        shifter = null;
        if (p != null) {
            try {
                p.removeListener(playerListener);
                p.release();
            } catch (Throwable ignored) {
                // تنظيف فقط
            }
        }
    }

    // =====================================================================================
    //  أحداث المشغّل الثاني
    // =====================================================================================

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (released || player == null) {
                return;
            }
            if (state == Player.STATE_READY) {
                if (!everReady) {
                    everReady = true;
                    retries = 0;
                    onFirstReady();
                } else if (status == Status.ENDED) {
                    setStatus(Status.WAITING, "");
                }
                tick();
            } else if (state == Player.STATE_ENDED) {
                handleEnded();
            } else if (state == Player.STATE_BUFFERING) {
                tick();
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            handleError(error);
        }
    };

    private void onFirstReady() {
        if (player == null) {
            return;
        }
        final Tracks tracks = player.getCurrentTracks();
        if (!tracks.containsType(C.TRACK_TYPE_AUDIO)) {
            fail("هذا المصدر لا يحتوي على مسار صوتي.");
            return;
        }
        final boolean seekable = player.isCurrentMediaItemSeekable();
        final boolean live = player.isCurrentMediaItemLive();
        final long dur = player.getDuration();
        finiteSource = !live && dur != C.TIME_UNSET && dur > 0L;
        if (!seekable) {
            kind = SyncKind.LIVE_DELAY;
        } else if (!mainIsLive && !live && dur != C.TIME_UNSET && dur > 0L) {
            kind = SyncKind.COUPLED;
        } else {
            kind = SyncKind.SEEK_FREE;
        }

        switch (kind) {
            case LIVE_DELAY:
                offsetMs = clamp(offsetMs, -MAX_SHIFT_MS, 0L);
                if (shifter != null) {
                    shifter.setEnabled(true);
                    shifter.setDelayMs(-offsetMs);
                }
                break;
            case COUPLED:
                offsetMs = clamp(offsetMs, -MAX_SHIFT_MS, MAX_SHIFT_MS);
                try {
                    player.setPlaybackParameters(new PlaybackParameters(mainSpeed));
                } catch (Throwable ignored) {
                    // غير حرج
                }
                break;
            default:
                offsetMs = clamp(offsetMs, -MAX_SHIFT_MS, MAX_SHIFT_MS);
                if (offsetMs != 0L) {
                    seekRelative(offsetMs); // تطبيق إزاحة محفوظة على بداية الجلسة
                }
                break;
        }
        setStatus(Status.WAITING, "");
        applyVolumes();
        if (kind == SyncKind.COUPLED) {
            alignCoupled(true);
        }
        notifyChanged();
    }

    private void handleEnded() {
        if (released || player == null) {
            return;
        }
        if (!finiteSource && (kind == SyncKind.LIVE_DELAY || player.isCurrentMediaItemLive())) {
            // انقطع بثّ مباشر: نعيد الاتصال تلقائياً.
            scheduleReconnect("انقطع البث");
            return;
        }
        if (kind == SyncKind.COUPLED) {
            // الصوت أقصر من الفيديو: يبقى ساكناً حتى يعود الفيديو لنطاقه (tick يتولى ذلك).
            tick();
            return;
        }
        setStatus(Status.ENDED, "انتهى الصوت الإضافي");
        applyVolumes();
    }

    private void handleError(PlaybackException e) {
        if (released || player == null) {
            return;
        }
        final int code = e.errorCode;
        if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            try {
                player.seekToDefaultPosition();
                player.prepare();
            } catch (Throwable t) {
                fail(describeError(code));
            }
            return;
        }
        if (!sourceIsFile) {
            if (isContainerError(code)) {
                if (attempt == 0) {
                    attempt = 1; // ربما هو HLS بلا امتداد واضح
                    runAttempt();
                    return;
                }
                if (attempt == 1) {
                    attempt = 2; // ربما هو قائمة تشغيل نصية
                    runAttempt();
                    return;
                }
            } else if (isNetworkError(code)) {
                scheduleReconnect("انقطع الاتصال");
                return;
            }
        }
        fail(describeError(code));
    }

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (released || player == null) {
                return;
            }
            final String uri = (attempt == 3 && resolvedUrl != null) ? resolvedUrl : sourceUri;
            final boolean hls = attempt == 1 || (attempt == 3 && resolvedIsHls);
            everReady = false;
            kind = SyncKind.UNKNOWN;
            prepareWith(uri, hls);
        }
    };

    private void scheduleReconnect(String reason) {
        if (released) {
            return;
        }
        if (retries >= MAX_RETRIES) {
            fail(reason + " ولم تنجح إعادة الاتصال. اضغط «تحديث» للمحاولة من جديد.");
            return;
        }
        retries++;
        final long delay = Math.min(10_000L, 1000L << (retries - 1));
        setStatus(Status.RECONNECTING, reason + " — إعادة الاتصال (" + retries + "/" + MAX_RETRIES + ")…");
        applyVolumes();
        try {
            if (player != null) {
                player.stop();
            }
        } catch (Throwable ignored) {
            // غير حرج
        }
        handler.removeCallbacks(reconnectRunnable);
        handler.postDelayed(reconnectRunnable, delay);
        notifyChanged();
    }

    private void fail(String msg) {
        if (released) {
            return;
        }
        stopPlayerKeepSource();
        setStatus(Status.ERROR, msg);
        applyVolumes();
        notifyChanged();
    }

    private void stopPlayerKeepSource() {
        handler.removeCallbacks(reconnectRunnable);
        handler.removeCallbacks(applyOffsetRunnable);
        if (player != null) {
            try {
                player.stop();
            } catch (Throwable ignored) {
                // غير حرج
            }
        }
    }

    // =====================================================================================
    //  دورة المتابعة (تشغيل/إيقاف تبعاً للفيديو + محاذاة الصوت المربوط)
    // =====================================================================================

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (released) {
                return;
            }
            tick();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private void tick() {
        final ExoPlayer p = player;
        if (released || p == null || !everReady) {
            return;
        }
        if (status == Status.ERROR || status == Status.ENDED || status == Status.RECONNECTING) {
            return;
        }
        final boolean mainPlaying = main.isPlaying();
        boolean wantPlay = mainPlaying && !userPaused;

        if (kind == SyncKind.COUPLED) {
            final long target = main.getCurrentPosition() + offsetMs;
            final long dur = p.getDuration();
            if (target < 0L) {
                wantPlay = false;
                if (p.getCurrentPosition() > 250L) {
                    p.seekTo(0L);
                }
            } else if (dur != C.TIME_UNSET && dur > 0L && target >= dur) {
                wantPlay = false;
            } else if (wantPlay && p.getPlaybackState() == Player.STATE_READY) {
                driftCorrect(target, false);
            } else if (p.getPlaybackState() == Player.STATE_ENDED) {
                // الصوت كان قد انتهى ثم عاد الفيديو لنطاقه (سَعي للخلف): نعيد الصوت إلى موضعه.
                driftCorrect(target, true);
            }
        }

        if (p.getPlayWhenReady() != wantPlay) {
            p.setPlayWhenReady(wantPlay);
        }

        // حالة العرض
        final Status s;
        String msg = "";
        if (userPaused) {
            s = Status.PAUSED;
        } else if (!mainPlaying) {
            s = Status.WAITING;
        } else if (p.getPlaybackState() == Player.STATE_BUFFERING) {
            s = Status.LOADING;
            msg = "تخزين مؤقت…";
        } else {
            s = Status.PLAYING;
        }
        setStatus(s, msg);

        if (shifter != null && shifter.hasMemoryFailure() && !memoryWarned) {
            memoryWarned = true;
            message("ذاكرة الجهاز لا تكفي لترجيع البث المباشر بالتأخير المطلوب");
        }
    }

    /** محاذاة الصوت المربوط مع موضع الفيديو فوراً (عند تغيّر الإزاحة أو أول جاهزية). */
    private void alignCoupled(boolean force) {
        if (player == null || kind != SyncKind.COUPLED) {
            return;
        }
        final long target = main.getCurrentPosition() + offsetMs;
        final long dur = player.getDuration();
        if (target < 0L || (dur != C.TIME_UNSET && dur > 0L && target >= dur)) {
            return;
        }
        driftCorrect(target, force);
    }

    private void driftCorrect(long target, boolean force) {
        final long now = SystemClock.elapsedRealtime();
        final long drift = player.getCurrentPosition() - target;
        if (force || (Math.abs(drift) > DRIFT_MS && now - lastCorrectionAt > CORRECTION_COOLDOWN_MS)) {
            lastCorrectionAt = now;
            try {
                player.seekTo(Math.max(0L, target));
            } catch (Throwable ignored) {
                // غير حرج
            }
        }
    }

    // =====================================================================================
    //  قوائم التشغيل النصية (.pls/.m3u) — تُحلّ مرة واحدة إلى رابط البث الفعلي
    // =====================================================================================

    private void resolvePlaylistAsync(final int token) {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();
        }
        if (io == null) {
            io = Executors.newSingleThreadExecutor();
        }
        final OkHttpClient c = client;
        final String url = sourceUri;
        try {
            io.execute(() -> {
                String found = null;
                boolean hls = false;
                try {
                    final String[] r = resolvePlaylist(c, url, 0);
                    if (r != null) {
                        found = r[0];
                        hls = "hls".equals(r[1]);
                    }
                } catch (Throwable ignored) {
                    // نتيجة null تكفي
                }
                final String f = found;
                final boolean isHls = hls;
                handler.post(() -> {
                    if (released || token != sessionToken) {
                        return;
                    }
                    if (f == null) {
                        fail("تعذّر قراءة هذا الرابط كصوت أو كقائمة تشغيل. تأكد أنه رابط مباشر لملف أو بثّ صوتي.");
                        return;
                    }
                    resolvedUrl = f;
                    resolvedIsHls = isHls;
                    attempt = 3;
                    runAttempt();
                });
            });
        } catch (Throwable t) {
            fail("تعذّر تحميل قائمة التشغيل.");
        }
    }

    /** يُرجع {الرابط، "hls"|"direct"} أو null. */
    private static String[] resolvePlaylist(OkHttpClient c, String url, int depth) throws IOException {
        final Request req = new Request.Builder().url(url).header("User-Agent", UA).build();
        try (Response resp = c.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code());
            }
            final ResponseBody body = resp.body();
            if (body == null) {
                return null;
            }
            final InputStream in = body.byteStream();
            final byte[] buf = new byte[65536];
            int n = 0;
            int r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) {
                n += r;
            }
            final String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            final String trimmed = text.trim();
            if (trimmed.startsWith("#EXTM3U") && trimmed.contains("#EXT-X-")) {
                return new String[]{url, "hls"};
            }
            String found = null;
            for (String raw : text.split("\\r?\\n")) {
                final String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                final String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("file1=")) {
                    found = line.substring(6).trim();
                    break;
                }
                if (lower.startsWith("http://") || lower.startsWith("https://")) {
                    found = line;
                    break;
                }
            }
            if (found == null || !(found.toLowerCase(Locale.ROOT).startsWith("http"))) {
                return null;
            }
            if (depth < 2 && looksLikePlaylistUrl(found)) {
                return resolvePlaylist(c, found, depth + 1);
            }
            return new String[]{found, "direct"};
        }
    }

    // =====================================================================================
    //  أدوات
    // =====================================================================================

    private void setStatus(Status s, String msg) {
        final String m = msg == null ? "" : msg;
        if (s == status && m.equals(statusMessage)) {
            return;
        }
        final boolean statusChanged = s != status;
        status = s;
        statusMessage = m;
        if (statusChanged) {
            applyVolumes(); // الأصلي يعود تلقائياً عند الخطأ/إعادة الاتصال
        }
        notifyChanged();
    }

    private void notifyChanged() {
        final Listener l = listener;
        if (l != null) {
            l.onExtraAudioChanged();
        }
    }

    private void message(String m) {
        final Listener l = listener;
        if (l != null) {
            l.onExtraAudioMessage(m);
        }
    }

    private static long clamp(long v, long lo, long hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static boolean isContainerError(int code) {
        return code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                || code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
    }

    private static boolean isNetworkError(int code) {
        return code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                || code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                || code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                || code == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                || code == PlaybackException.ERROR_CODE_TIMEOUT;
    }

    private String describeError(int code) {
        switch (code) {
            case PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND:
                return "الملف غير موجود أو تم نقله. اختره من جديد.";
            case PlaybackException.ERROR_CODE_IO_NO_PERMISSION:
                return "لا توجد صلاحية لقراءة هذا الملف. اختره من جديد.";
            case PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED:
                return "هذا الرابط غير مشفّر (HTTP) وغير مسموح على هذا الجهاز.";
            case PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE:
                return "هذا الرابط لا يشير إلى ملف أو بثّ صوتي.";
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                return "رفض الخادم الطلب أو لم يعد الرابط صالحاً.";
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
                return "صيغة هذا الصوت غير مدعومة أو الرابط ليس ملفاً/بثّاً صوتياً.";
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return "هذا الجهاز لا يستطيع فك ترميز هذا الصوت.";
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED:
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED:
                return "تعذّر فتح مخرج الصوت للمصدر الإضافي.";
            default:
                return "تعذّر تشغيل الصوت الإضافي. تأكد من الرابط ثم اضغط «تحديث».";
        }
    }

    static boolean looksLikePlaylistUrl(String url) {
        final String lower = url.toLowerCase(Locale.ROOT);
        final int q = lower.indexOf('?');
        final String path = q >= 0 ? lower.substring(0, q) : lower;
        return path.endsWith(".pls") || path.endsWith(".m3u");
    }

    static boolean isYoutubeUrl(String url) {
        final String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("://youtube.com") || lower.contains("://www.youtube.com")
                || lower.contains("://m.youtube.com") || lower.contains("://youtu.be")
                || lower.contains("://music.youtube.com");
    }

    /** ينظّف مُدخل المستخدم: يُرجع رابط http(s) صالحاً أو null. */
    static String normalizeInputUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String u = raw.trim();
        if (u.isEmpty()) {
            return null;
        }
        for (int i = 0; i < u.length(); i++) {
            if (Character.isWhitespace(u.charAt(i))) {
                return null;
            }
        }
        if (!u.contains("://")) {
            if (!(u.contains(".") || u.contains(":"))) {
                return null;
            }
            u = "http://" + u;
        }
        final String lower = u.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            return null;
        }
        final int hostStart = u.indexOf("://") + 3;
        if (hostStart >= u.length()) {
            return null;
        }
        return u;
    }

    static String displayNameForUrl(String url) {
        try {
            final int s = url.indexOf("://") + 3;
            int e = url.length();
            for (int i = s; i < url.length(); i++) {
                final char ch = url.charAt(i);
                if (ch == '/' || ch == '?' || ch == '#') {
                    e = i;
                    break;
                }
            }
            String host = url.substring(s, e);
            final int at = host.lastIndexOf('@');
            if (at >= 0) {
                host = host.substring(at + 1);
            }
            return host.isEmpty() ? "رابط صوتي" : host;
        } catch (Throwable t) {
            return "رابط صوتي";
        }
    }
}
