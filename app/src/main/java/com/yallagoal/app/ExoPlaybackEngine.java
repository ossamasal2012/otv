package com.yallagoal.app;

import android.content.Context;
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
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
 */
@UnstableApi
final class ExoPlaybackEngine implements PlaybackEngine {

    private ExoPlayer player;
    private PlayerView playerView;
    private Listener listener;
    private volatile String selectedQualityId = "auto";

    @Override
    public void initialize(Context context) {
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
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
                if (listener != null) listener.onError(mapErrorMessage(error));
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
    public void playUrl(String url, long startPositionMs) {
        if (player == null) return;
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
        if (playerView != null) {
            playerView.setPlayer(null);
            playerView = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        listener = null;
    }

    private String mapErrorMessage(PlaybackException error) {
        int code = error.errorCode;
        if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            return "تعذر الاتصال بمصدر البث. تحقق من اتصال الإنترنت.";
        }
        if (code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                || code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
            return "تعذر الوصول لهذا المحتوى من السيرفر.";
        }
        if (code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || code == PlaybackException.ERROR_CODE_DECODING_FAILED) {
            return "تعذر فك تشفير هذا الفيديو على هذا الجهاز.";
        }
        return "تعذر تشغيل هذا المحتوى.";
    }
}
