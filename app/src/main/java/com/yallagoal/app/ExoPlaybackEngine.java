package com.yallagoal.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.ViewGroup;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * محرّك التشغيل الافتراضي للمشغّل الداخلي — AndroidX Media3 (ExoPlayer) بإصدار 1.4.1،
 * مع OkHttp كطبقة شبكة (نفس نمط الاستخدام المعتمد أصلاً بباقي التطبيق)، ودعم HLS يأتي
 * تلقائياً بمجرد وجود اعتماديّة media3-exoplayer-hls على مسار البناء (media3 يكتشفها
 * ويستخدمها داخلياً دون أي كود إضافي هنا).
 *
 * لا تُستخدم عناصر تحكّم Media3 الجاهزة (PlayerView.useController) إطلاقاً؛ واجهة التحكم
 * بالكامل (زر تشغيل/إيقاف، شريط تقدّم، تقديم/ترجيع 10 ثوانٍ، وضع ملء الشاشة، قائمة الدقات...)
 * مبنية خصيصاً داخل InternalPlayerActivity لضمان تصميم واحد موحّد بغضّ النظر عن المحرّك المُختار.
 *
 * اختيار الدقة يدوياً يعتمد على واجهة Media3 القياسية لتجاوز اختيار المسار (TrackSelectionOverride
 * فوق TrackSelectionParameters) — تماماً كما تفعل أي واجهة "اختيار جودة" قياسية مبنية على
 * ExoPlayer؛ لا حيلة أو تقريب هنا، فالمحرّك نفسه يُقيَّد فعلياً على الدقة المُختارة.
 *
 * ==== التعافي الآلي من أخطاء التشغيل (طبقتان) ====
 * الطبقة 1 (داخل ExoPlayer، دون تغيير): إعادة محاولة الطلب الفاشل نفسه (3 محاولات، و6 للبث
 * التدريجي المباشر) — مناسبة لانقطاع طلب واحد.
 * الطبقة 2 (هنا): حين يرمي ExoPlayer خطأً نهائياً كان المشغّل يتوقف عند STATE_IDLE وتظهر رسالة
 * عامة بانتظار ضغطة المستخدم. الآن يُصنَّف الخطأ (classify) ويُسأل PlaybackRecoveryPolicy: أعد
 * التهيئة من الرابط الأصلي بعد مهلة تصاعدية؟ جدّد الرابط أولاً (إن وُجد UrlRefresher)؟ عُد
 * لحافة البث المباشر (BehindLiveWindow — العلاج الرسمي: seekToDefaultPosition + prepare)؟ انتظر
 * عودة الشبكة؟ أم استسلم برسالة تذكر السبب ورمز HTTP؟ كل ذلك بميزانية محاولات صارمة.
 * كل عملية متأخرة (مؤقّت/تجديد رابط) مربوطة برمز جلسة (sessionToken) يتغيّر مع كل playUrl/release،
 * فلا يؤثر أي رد قديم على قناة جديدة أبداً.
 */
@UnstableApi
final class ExoPlaybackEngine implements PlaybackEngine {

    private static final String TAG = "ExoEngine";

    private ExoPlayer player;
    private PlayerView playerView;
    private Listener listener;
    private volatile String selectedQualityId = "auto";

    // ---- التعافي الآلي (يعمل كله على خيط الواجهة الرئيسي) ----
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PlaybackRecoveryPolicy recoveryPolicy = new PlaybackRecoveryPolicy();
    private Context appContext;
    private OkHttpClient okHttpClient;
    private UrlRefresher urlRefresher;
    private ExecutorService refreshExecutor;
    private String currentUrl;
    /** يزداد عند كل playUrl/release: أي مؤقّت أو رد تجديد قديم يتجاهل نفسه إن اختلف رمزه. */
    private int sessionToken = 0;
    private Runnable pendingRecovery;
    /**
     * لحظة أول وصول للمشغّل إلى STATE_READY منذ آخر تهيئة (أو -1). تُستخدم لمعرفة هل عاش البث
     * ≥ 30 ث بعد أن نجح فعلاً قبل الفشل الحالي — وهذا يُحتسب حتى لو تخلله تقطّع (rebuffer)، فجلسة
     * مشاهدة طويلة بها تقطّعات عابرة لا تستنفد ميزانية التعافي بسبب حوادث قديمة متباعدة.
     */
    private long readySinceMs = -1L;

    @Override
    public void initialize(Context context) {
        appContext = context.getApplicationContext();

        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();

        DataSource.Factory dataSourceFactory = new OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent("YallaGoal/1.0 (Android; ExoPlayer)");

        DefaultMediaSourceFactory mediaSourceFactory =
                new DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory);

        player = new ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (listener == null || player == null) return;
                if (state == Player.STATE_READY) {
                    if (readySinceMs < 0) readySinceMs = SystemClock.elapsedRealtime();
                    listener.onBuffering(false);
                    long d = player.getDuration();
                    listener.onReady(d == C.TIME_UNSET ? 0L : d);
                } else if (state == Player.STATE_BUFFERING) {
                    listener.onBuffering(true);
                } else if (state == Player.STATE_ENDED) {
                    listener.onEnded();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (listener != null) listener.onPlayingStateChanged(isPlaying);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                handlePlayerError(error);
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                if (listener != null) listener.onTracksChanged();
            }
        });
    }

    @Override
    public void attachTo(ViewGroup container) {
        playerView = new PlayerView(container.getContext());
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setPlayer(player);
        container.removeAllViews();
        container.addView(playerView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void setUrlRefresher(UrlRefresher refresher) {
        this.urlRefresher = refresher;
    }

    @Override
    public void playUrl(String url, long startPositionMs) {
        if (player == null) return;

        // تشغيل جديد (أو «إعادة المحاولة» اليدوية) = حادثة جديدة بميزانية كاملة، وأي تعافٍ أو
        // تجديد رابط أو انتظار شبكة قديم يُبطَل فوراً.
        sessionToken++;
        cancelPendingRecovery();
        recoveryPolicy.reset();
        readySinceMs = -1L;
        currentUrl = url;

        selectedQualityId = "auto";
        // فيديو/قناة جديدة تبدأ دائماً باختيار تلقائي — أي تثبيت دقة يدوي سابق كان يخص المحتوى
        // السابق فقط ولا معنى لبقائه هنا.
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters().buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .build());

        MediaItem item = MediaItem.fromUri(url);
        if (startPositionMs > 0) {
            player.setMediaItem(item, startPositionMs);
        } else {
            player.setMediaItem(item);
        }
        player.prepare();
        player.setPlayWhenReady(true);
    }

    @Override
    public void play() {
        if (player != null) player.play();
    }

    @Override
    public void pause() {
        if (player != null) player.pause();
    }

    @Override
    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    @Override
    public long getCurrentPosition() {
        return player != null ? Math.max(0L, player.getCurrentPosition()) : 0L;
    }

    @Override
    public long getDuration() {
        if (player == null) return 0L;
        long d = player.getDuration();
        return d == C.TIME_UNSET ? 0L : d;
    }

    @Override
    public void seekTo(long positionMs) {
        if (player != null) player.seekTo(Math.max(0L, positionMs));
    }

    @Override
    public void setResizeMode(ResizeMode mode) {
        if (playerView == null || mode == null) return;
        switch (mode) {
            case FILL:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                break;
            case ZOOM:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                break;
            case FIT:
            default:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                break;
        }
    }

    @Override
    public List<QualityOption> getAvailableQualities() {
        List<QualityOption> result = new ArrayList<>();
        if (player == null) return result;

        List<Integer> heights = new ArrayList<>();
        Tracks tracks = player.getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format format = group.getTrackFormat(i);
                int height = format.height;
                if (height == Format.NO_VALUE || height <= 0) continue;
                if (!heights.contains(height)) heights.add(height);
            }
        }

        Collections.sort(heights, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return b - a; // تنازلياً: الأعلى دقة أولاً
            }
        });

        for (Integer h : heights) {
            result.add(new QualityOption(String.valueOf(h), h + "p"));
        }
        return result;
    }

    @Override
    public String getSelectedQualityId() {
        return selectedQualityId;
    }

    @Override
    public void selectQuality(String qualityId) {
        if (player == null) return;

        if (qualityId == null || "auto".equals(qualityId)) {
            player.setTrackSelectionParameters(
                    player.getTrackSelectionParameters().buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                            .build());
            selectedQualityId = "auto";
            return;
        }

        int targetHeight;
        try {
            targetHeight = Integer.parseInt(qualityId);
        } catch (NumberFormatException e) {
            return;
        }

        Tracks tracks = player.getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format format = group.getTrackFormat(i);
                if (format.height == targetHeight) {
                    TrackGroup trackGroup = group.getMediaTrackGroup();
                    player.setTrackSelectionParameters(
                            player.getTrackSelectionParameters().buildUpon()
                                    .setOverrideForType(new TrackSelectionOverride(trackGroup, i))
                                    .build());
                    selectedQualityId = qualityId;
                    return;
                }
            }
        }
    }

    @Override
    public void release() {
        // أبطل أولاً كل ما قد يُنفَّذ لاحقاً (مؤقّتات تعافٍ، رد تجديد رابط، استطلاع شبكة).
        sessionToken++;
        cancelPendingRecovery();
        if (refreshExecutor != null) {
            refreshExecutor.shutdownNow();
            refreshExecutor = null;
        }

        if (playerView != null) {
            playerView.setPlayer(null);
            playerView = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }

        // عميل OkHttp خاص بهذا المحرّك: بدون هذا يبقى مجمّع اتصالاته (keep-alive) ومنفّذ طلباته
        // حيَّين بعد مغادرة القناة، فيستمر اتصال السيرفر مفتوحاً دقائق (يضرّ بحسابات Xtream
        // المحدودة الاتصالات المتزامنة عند فتح القناة التالية).
        if (okHttpClient != null) {
            try {
                okHttpClient.dispatcher().executorService().shutdown();
                okHttpClient.connectionPool().evictAll();
            } catch (Exception ignored) {
                // لا شيء نفعله: التحرير هنا بأفضل جهد فقط
            }
            okHttpClient = null;
        }

        listener = null;
        urlRefresher = null;
    }

    // ==================== التعافي الآلي من الأخطاء ====================

    private void handlePlayerError(PlaybackException error) {
        if (listener == null || player == null) return;

        final long now = SystemClock.elapsedRealtime();
        final long played = readySinceMs >= 0 ? now - readySinceMs : 0L;
        readySinceMs = -1L;

        final PlaybackRecoveryPolicy.Failure failure = classify(error);
        final PlaybackRecoveryPolicy.Decision decision =
                recoveryPolicy.onFailure(failure, now, played, isOnline());
        applyDecision(decision, failure);
    }

    private void applyDecision(PlaybackRecoveryPolicy.Decision decision,
                               PlaybackRecoveryPolicy.Failure failure) {
        if (listener == null || player == null) return;

        // سجل تشخيصي بلا أي رابط أو توكن: النوع + رمز المشغّل + رمز HTTP فقط (يظهر في نسخة debug).
        Log.d(TAG, "playback failure kind=" + failure.kind + " code=" + failure.errorCode
                + " http=" + failure.httpStatus + " -> " + decision.action
                + " attempt=" + decision.attempt + "/" + decision.maxAttempts
                + " delayMs=" + decision.delayMs);

        switch (decision.action) {
            case GIVE_UP:
                listener.onError(withTechnicalDetail(decision.finalMessage, failure));
                break;
            case WAIT_FOR_NETWORK:
                listener.onRecovering(decision.statusMessage);
                scheduleNetworkPoll(failure, decision.delayMs);
                break;
            default:
                listener.onRecovering(decision.statusMessage);
                scheduleRecovery(decision.action, decision.delayMs);
                break;
        }
    }

    private void scheduleRecovery(final PlaybackRecoveryPolicy.Action action, long delayMs) {
        final int token = sessionToken;
        cancelPendingRecovery();
        pendingRecovery = new Runnable() {
            @Override
            public void run() {
                pendingRecovery = null;
                if (token != sessionToken || player == null) return;
                executeRecovery(action, token);
            }
        };
        mainHandler.postDelayed(pendingRecovery, Math.max(0L, delayMs));
    }

    /** انتظار عودة الشبكة: يسأل المُقرِّر دورياً دون استهلاك محاولات حتى تعود أو ينتهي السقف. */
    private void scheduleNetworkPoll(final PlaybackRecoveryPolicy.Failure failure, long delayMs) {
        final int token = sessionToken;
        cancelPendingRecovery();
        pendingRecovery = new Runnable() {
            @Override
            public void run() {
                pendingRecovery = null;
                if (token != sessionToken || player == null) return;
                applyDecision(recoveryPolicy.onFailure(failure, SystemClock.elapsedRealtime(), 0L, isOnline()),
                        failure);
            }
        };
        mainHandler.postDelayed(pendingRecovery, Math.max(0L, delayMs));
    }

    private void cancelPendingRecovery() {
        if (pendingRecovery != null) {
            mainHandler.removeCallbacks(pendingRecovery);
            pendingRecovery = null;
        }
    }

    private void executeRecovery(PlaybackRecoveryPolicy.Action action, int token) {
        // أي نجاح سابق لا يُحتسب لصالح الحادثة القادمة: العدّ يبدأ من أول READY بعد هذه التهيئة.
        readySinceMs = -1L;

        if (action == PlaybackRecoveryPolicy.Action.SEEK_TO_LIVE_EDGE) {
            // العلاج الرسمي لـ BehindLiveWindowException (توثيق Media3): قفزة لحافة البث ثم تهيئة.
            player.seekToDefaultPosition();
            player.prepare();
            player.setPlayWhenReady(true);
            return;
        }

        final long resumePos = resumePositionForRetry();
        if (action == PlaybackRecoveryPolicy.Action.REFRESH_URL_AND_RETRY && urlRefresher != null) {
            refreshUrlThenPrepare(token, resumePos);
        } else {
            reprepare(currentUrl, resumePos);
        }
    }

    /** فيديو بمدة معروفة: يستأنف من حيث وصل. بث مباشر: دائماً من الحافة (0). */
    private long resumePositionForRetry() {
        if (player == null) return 0L;
        final long duration = player.getDuration();
        final boolean isVod = duration != C.TIME_UNSET && duration > 0 && !player.isCurrentMediaItemLive();
        return isVod ? Math.max(0L, player.getCurrentPosition()) : 0L;
    }

    private void refreshUrlThenPrepare(final int token, final long resumePos) {
        final UrlRefresher refresher = urlRefresher;
        if (refreshExecutor == null) {
            refreshExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "yg-url-refresh");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        refreshExecutor.execute(new Runnable() {
            @Override
            public void run() {
                String fresh = null;
                try {
                    fresh = refresher.fetchFreshUrl();
                } catch (Exception e) {
                    // فشل التجديد لا يمنع المحاولة بالرابط الحالي (قد يكون كافياً)؛ بلا تسجيل للرابط.
                    Log.d(TAG, "url refresh failed: " + e.getClass().getSimpleName());
                }
                final String result = fresh;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != sessionToken || player == null) return;
                        reprepare(result != null && !result.isEmpty() ? result : currentUrl, resumePos);
                    }
                });
            }
        });
    }

    /** إعادة تهيئة سريعة بنفس المشغّل (دون المساس باختيار الدقة اليدوي ضمن نفس الحادثة). */
    private void reprepare(String url, long resumePos) {
        if (player == null || url == null || url.isEmpty()) return;
        currentUrl = url;
        MediaItem item = MediaItem.fromUri(url);
        if (resumePos > 0) {
            player.setMediaItem(item, resumePos);
        } else {
            player.setMediaItem(item);
        }
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true; // لا نعرف ⇒ نفترض متصلاً (لا نُعلّق المستخدم بلا سبب)
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * يحوّل خطأ Media3 إلى تصنيف يفهمه PlaybackRecoveryPolicy. نبحث في سلسلة الأسباب (cause) عن
     * الاستثناءات المحدِّدة: رمز HTTP الفعلي، وتعلّق/إعادة ضبط قائمة HLS (كلاهما يظهر بخلاف ذلك
     * كخطأ عام ERROR_CODE_IO_UNSPECIFIED).
     */
    private static PlaybackRecoveryPolicy.Failure classify(PlaybackException error) {
        final int code = error.errorCode;
        int httpStatus = -1;
        boolean stuck = false;
        boolean reset = false;

        Throwable t = error;
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof HttpDataSource.InvalidResponseCodeException) {
                httpStatus = ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
            } else if (t instanceof HlsPlaylistTracker.PlaylistStuckException) {
                stuck = true;
            } else if (t instanceof HlsPlaylistTracker.PlaylistResetException) {
                reset = true;
            }
        }

        final PlaybackRecoveryPolicy.Kind kind;
        if (stuck) {
            kind = PlaybackRecoveryPolicy.Kind.PLAYLIST_STUCK;
        } else if (reset) {
            kind = PlaybackRecoveryPolicy.Kind.PLAYLIST_RESET;
        } else if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            kind = PlaybackRecoveryPolicy.Kind.BEHIND_LIVE_WINDOW;
        } else if (httpStatus > 0) {
            kind = PlaybackRecoveryPolicy.kindForHttpStatus(httpStatus);
        } else {
            kind = kindForErrorCode(code);
        }
        return new PlaybackRecoveryPolicy.Failure(kind, code, httpStatus);
    }

    private static PlaybackRecoveryPolicy.Kind kindForErrorCode(int code) {
        switch (code) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
                return PlaybackRecoveryPolicy.Kind.NETWORK_FAILED;
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
            case PlaybackException.ERROR_CODE_TIMEOUT:
                return PlaybackRecoveryPolicy.Kind.NETWORK_TIMEOUT;
            case PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
            // 3003 يظهر أيضاً حين يردّ السيرفر بصفحة خطأ HTML/JSON بدل البث (لا نملك نوع المحتوى
            // لنفرّق بينها وبين صيغة غير مدعومة فعلاً) ⇒ محاولتان فقط ثم رسالة تذكر الاحتمالين.
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
                return PlaybackRecoveryPolicy.Kind.BAD_RESPONSE;
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
                return PlaybackRecoveryPolicy.Kind.UNSUPPORTED_FORMAT;
            case PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED:
                return PlaybackRecoveryPolicy.Kind.CLEARTEXT_BLOCKED;
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return PlaybackRecoveryPolicy.Kind.DECODER;
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED:
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS: // بلا رمز مُستخرَج (نادر)
            case PlaybackException.ERROR_CODE_UNSPECIFIED:
            case PlaybackException.ERROR_CODE_REMOTE_ERROR:
            case PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK:
            case PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED:
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED:
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED:
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED:
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED:
                return PlaybackRecoveryPolicy.Kind.UNKNOWN_TRANSIENT;
            default:
                if (code >= 6000 && code < 7000) return PlaybackRecoveryPolicy.Kind.DRM;
                return PlaybackRecoveryPolicy.Kind.OTHER_FATAL;
        }
    }

    /** يُلحق سطراً تقنياً قصيراً (بلا أي رابط أو توكن) ليسهل تشخيص الحالة من لقطة شاشة المستخدم. */
    private static String withTechnicalDetail(String message, PlaybackRecoveryPolicy.Failure failure) {
        StringBuilder sb = new StringBuilder(message != null ? message : "تعذر تشغيل هذا المحتوى.");
        sb.append("\n(رمز الخطأ: ").append(failure.errorCode);
        if (failure.httpStatus > 0) sb.append(" · HTTP ").append(failure.httpStatus);
        sb.append(")");
        return sb.toString();
    }
}
