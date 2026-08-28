package com.yallagoal.app;

import android.content.Context;
import android.net.Uri;
import android.view.ViewGroup;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * محرّك التشغيل البديل للمشغّل الداخلي — LibVLC for Android بإصدار 3.7.0، لتغطية صيغ/بروتوكولات
 * قد لا يتعامل معها ExoPlayer بنفس الكفاءة على بعض الأجهزة أو بعض سيرفرات Xtream.
 *
 * نفس واجهة PlaybackEngine بالضبط، لذا واجهة InternalPlayerActivity الموحّدة تعمل معه دون
 * أي فرق عن محرّك ExoPlayer.
 *
 * ملاحظة عن اختيار الدقة هنا تحديداً: مسارات الفيديو المتعددة (getVideoTracks) في LibVLC تمثّل
 * مسارات ES منفصلة فعلياً بالملف/البث (زوايا كاميرا متعددة، أو قوائم غير-تكيّفية تحتوي أكثر من
 * مسار فيديو)، وليست بالضرورة نفس مفهوم "الدقات البديلة" لبثّ HLS تكيّفي — فمعظم بثوث HLS
 * التكيّفية تُدار داخلياً بواسطة LibVLC ضمن مسار واحد فقط دون كشف الدقات البديلة كمسارات منفصلة
 * عبر هذه الواجهة العامة. لذلك القائمة هنا صادقة تماماً مع قدرة المحرّك الفعلية: "تلقائي" متاح
 * دائماً وفعّال دائماً، وأي مسارات إضافية حقيقية يكتشفها LibVLC تُعرض وتعمل فعلياً عند اختيارها؛
 * لا نُظهر خيارات وهمية لا تُغيّر شيئاً فعلياً عند تفعيلها.
 */
final class VlcPlaybackEngine implements PlaybackEngine {

    // نسبة تكبير ثابتة ومعقولة لوضع "تكبير" (Zoom) — تُطبَّق على حاوية العرض نفسها (Android View)
    // بدل الاعتماد على معرفة الأبعاد الأصلية للفيديو من LibVLC مباشرة (واجهة أعمق وأقل استقراراً
    // عبر الإصدارات)، فتقصّ الأشرطة السوداء بأمان تام دون أي اعتماد على تفاصيل داخلية للمحرّك.
    private static final float ZOOM_SCALE_FACTOR = 1.35f;

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private VLCVideoLayout videoLayout;
    private Listener listener;

    // LibVLC لا يضمن معرفة مدة الفيديو فوراً عند بدء التشغيل؛ نحتفظ بأي طلب Seek وصل قبل أن
    // تُعرف المدة فعلياً (عبر LengthChanged) ونطبّقه بأول لحظة ممكنة بدل تجاهله أو فشله بصمت.
    private long pendingSeekMs = -1L;
    private boolean readyNotified = false;

    private ResizeMode currentResizeMode = ResizeMode.FIT;
    private volatile String selectedQualityId = "auto";
    private int autoVideoTrackId = -1;
    private boolean autoVideoTrackCaptured = false;

    @Override
    public void initialize(Context context) {
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=2000");
        options.add("--http-reconnect");

        libVLC = new LibVLC(context, options);
        mediaPlayer = new MediaPlayer(libVLC);

        mediaPlayer.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event event) {
                if (listener == null) return;
                switch (event.type) {
                    case MediaPlayer.Event.Opening:
                        listener.onBuffering(true);
                        break;
                    case MediaPlayer.Event.Buffering:
                        listener.onBuffering(event.getBuffering() < 100f);
                        break;
                    case MediaPlayer.Event.Playing:
                        listener.onBuffering(false);
                        listener.onPlayingStateChanged(true);
                        notifyReadyIfPossible();
                        applyPendingSeekIfPossible();
                        captureAutoVideoTrackIfPossible();
                        listener.onTracksChanged();
                        break;
                    case MediaPlayer.Event.Paused:
                        listener.onPlayingStateChanged(false);
                        break;
                    case MediaPlayer.Event.EndReached:
                        listener.onEnded();
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        listener.onError("تعذر تشغيل هذا المحتوى.");
                        break;
                    case MediaPlayer.Event.LengthChanged:
                        notifyReadyIfPossible();
                        applyPendingSeekIfPossible();
                        listener.onTracksChanged();
                        break;
                    default:
                        break;
                }
            }
        });
    }

    private void notifyReadyIfPossible() {
        if (readyNotified || mediaPlayer == null || listener == null) return;
        long len = mediaPlayer.getLength();
        if (len > 0) {
            readyNotified = true;
            listener.onReady(len);
        }
    }

    private void applyPendingSeekIfPossible() {
        if (pendingSeekMs >= 0 && mediaPlayer != null && mediaPlayer.getLength() > 0) {
            mediaPlayer.setTime(pendingSeekMs);
            pendingSeekMs = -1L;
        }
    }

    /** يحفظ أي مسار فيديو اختاره LibVLC تلقائياً بمجرد معرفته — هذا ما يُستعاد عند اختيار "تلقائي" لاحقاً. */
    private void captureAutoVideoTrackIfPossible() {
        if (autoVideoTrackCaptured || mediaPlayer == null) return;
        int current = mediaPlayer.getVideoTrack();
        if (current >= 0) {
            autoVideoTrackId = current;
            autoVideoTrackCaptured = true;
        }
    }

    @Override
    public void attachTo(ViewGroup container) {
        videoLayout = new VLCVideoLayout(container.getContext());
        container.removeAllViews();
        container.addView(videoLayout, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mediaPlayer.attachViews(videoLayout, null, false, false);
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void playUrl(String url, long startPositionMs) {
        if (libVLC == null || mediaPlayer == null) return;
        readyNotified = false;
        pendingSeekMs = startPositionMs > 0 ? startPositionMs : -1L;

        // فيديو/قناة جديدة تبدأ دائماً باختيار دقة تلقائي، وتُنسى أي معلومات مسار "تلقائي" خاصة
        // بالمحتوى السابق فقط.
        selectedQualityId = "auto";
        autoVideoTrackCaptured = false;
        autoVideoTrackId = -1;

        Media media = new Media(libVLC, Uri.parse(url));
        media.setHWDecoderEnabled(true, false);
        mediaPlayer.setMedia(media);
        media.release();
        mediaPlayer.play();

        applyResizeMode();
    }

    @Override
    public void play() {
        if (mediaPlayer != null) mediaPlayer.play();
    }

    @Override
    public void pause() {
        if (mediaPlayer != null) mediaPlayer.pause();
    }

    @Override
    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    @Override
    public long getCurrentPosition() {
        return mediaPlayer != null ? Math.max(0L, mediaPlayer.getTime()) : 0L;
    }

    @Override
    public long getDuration() {
        return mediaPlayer != null ? Math.max(0L, mediaPlayer.getLength()) : 0L;
    }

    @Override
    public void seekTo(long positionMs) {
        if (mediaPlayer == null) return;
        long safePosition = Math.max(0L, positionMs);
        if (mediaPlayer.getLength() > 0) {
            mediaPlayer.setTime(safePosition);
        } else {
            pendingSeekMs = safePosition;
        }
    }

    @Override
    public void setResizeMode(ResizeMode mode) {
        if (mode == null) return;
        currentResizeMode = mode;
        applyResizeMode();
    }

    private void applyResizeMode() {
        if (mediaPlayer == null || videoLayout == null) return;
        try {
            switch (currentResizeMode) {
                case FILL: {
                    videoLayout.setScaleX(1f);
                    videoLayout.setScaleY(1f);
                    int w = videoLayout.getWidth();
                    int h = videoLayout.getHeight();
                    if (w > 0 && h > 0) {
                        mediaPlayer.setAspectRatio(w + ":" + h);
                    }
                    mediaPlayer.setScale(0f);
                    break;
                }
                case ZOOM:
                    mediaPlayer.setAspectRatio(null);
                    mediaPlayer.setScale(0f);
                    videoLayout.setScaleX(ZOOM_SCALE_FACTOR);
                    videoLayout.setScaleY(ZOOM_SCALE_FACTOR);
                    break;
                case FIT:
                default:
                    videoLayout.setScaleX(1f);
                    videoLayout.setScaleY(1f);
                    mediaPlayer.setAspectRatio(null);
                    mediaPlayer.setScale(0f);
                    break;
            }
        } catch (Exception ignored) {
            // أي خلل غير متوقع بضبط نسبة العرض لا يجب أن يوقف التشغيل نفسه إطلاقاً
        }
    }

    @Override
    public List<QualityOption> getAvailableQualities() {
        List<QualityOption> result = new ArrayList<>();
        if (mediaPlayer == null) return result;
        captureAutoVideoTrackIfPossible();

        MediaPlayer.TrackDescription[] tracks;
        try {
            tracks = mediaPlayer.getVideoTracks();
        } catch (Exception e) {
            return result;
        }
        if (tracks == null) return result;

        for (MediaPlayer.TrackDescription t : tracks) {
            if (t == null || t.id < 0) continue;
            if (autoVideoTrackCaptured && t.id == autoVideoTrackId) continue; // هذا هو نفسه "تلقائي"، لا نكرره كخيار منفصل
            String label = (t.name != null && !t.name.trim().isEmpty()) ? t.name.trim() : ("مسار " + t.id);
            result.add(new QualityOption(String.valueOf(t.id), label));
        }
        return result;
    }

    @Override
    public String getSelectedQualityId() {
        return selectedQualityId;
    }

    @Override
    public void selectQuality(String qualityId) {
        if (mediaPlayer == null) return;

        if (qualityId == null || "auto".equals(qualityId)) {
            captureAutoVideoTrackIfPossible();
            if (autoVideoTrackCaptured) {
                try {
                    mediaPlayer.setVideoTrack(autoVideoTrackId);
                } catch (Exception ignored) {
                }
            }
            selectedQualityId = "auto";
            return;
        }

        try {
            int id = Integer.parseInt(qualityId);
            mediaPlayer.setVideoTrack(id);
            selectedQualityId = qualityId;
        } catch (NumberFormatException ignored) {
            // معرّف غير صالح — نتجاهله بأمان بدل رمي استثناء يوقف التشغيل
        }
    }

    @Override
    public void release() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {
            }
            mediaPlayer.setEventListener(null);
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (libVLC != null) {
            libVLC.release();
            libVLC = null;
        }
        videoLayout = null;
        listener = null;
    }
}
