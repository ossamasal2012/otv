package com.yallagoal.app;

import android.content.Context;
import android.net.Uri;
import android.view.ViewGroup;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

/**
 * محرّك التشغيل البديل للمشغّل الداخلي — LibVLC for Android بإصدار 3.7.0، لتغطية صيغ/بروتوكولات
 * قد لا يتعامل معها ExoPlayer بنفس الكفاءة على بعض الأجهزة أو بعض سيرفرات Xtream.
 *
 * نفس واجهة PlaybackEngine بالضبط، لذا واجهة InternalPlayerActivity الموحّدة تعمل معه دون
 * أي فرق عن محرّك ExoPlayer.
 */
final class VlcPlaybackEngine implements PlaybackEngine {

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private VLCVideoLayout videoLayout;
    private Listener listener;

    // LibVLC لا يضمن معرفة مدة الفيديو فوراً عند بدء التشغيل؛ نحتفظ بأي طلب Seek وصل قبل أن
    // تُعرف المدة فعلياً (عبر LengthChanged) ونطبّقه بأول لحظة ممكنة بدل تجاهله أو فشله بصمت.
    private long pendingSeekMs = -1L;
    private boolean readyNotified = false;

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

        Media media = new Media(libVLC, Uri.parse(url));
        media.setHWDecoderEnabled(true, false);
        mediaPlayer.setMedia(media);
        media.release();
        mediaPlayer.play();
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
