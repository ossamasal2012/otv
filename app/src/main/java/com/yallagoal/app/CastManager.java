package com.yallagoal.app;

import android.app.Activity;
import android.widget.Toast;

import androidx.mediarouter.app.MediaRouteChooserDialog;
import androidx.mediarouter.media.MediaRouteSelector;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

/**
 * يدير عملية "البث إلى التلفاز" عبر بروتوكول Google Cast — يعمل بكفاءة تامة مع:
 *  - أجهزة Android TV / Google TV (دعم Google Cast مدمج بنظام التشغيل نفسه دائماً).
 *  - أجهزة Chromecast التقليدية المتصلة بأي تلفاز.
 *  - أغلب تلفزيونات LG الحديثة (من ٢٠١٩ تقريباً وما بعدها) المعتمدة كـ"Google Cast built-in"،
 *    وكذلك تلفزيونات سامسونج/سوني وغيرها المعتمدة بنفس المعيار.
 *  ملاحظة: تلفزيونات LG القديمة جداً غير المعتمدة من گوگل لهذا المعيار لن تظهر بقائمة الأجهزة؛
 *  الحل بهذي الحالة هو توصيل جهاز Chromecast تقليدي بمنفذ HDMI بالتلفاز نفسه.
 */
public class CastManager {

    private final Activity activity;
    private final CastContext castContext;

    private String pendingUrl;
    private String pendingTitle;

    public CastManager(Activity activity) {
        this.activity = activity;
        this.castContext = CastContext.getSharedInstance(activity);
        this.castContext.getSessionManager().addSessionManagerListener(sessionListener, CastSession.class);
    }

    public void castToTv(String url, String title) {
        if (url == null || url.isEmpty()) return;

        CastSession session = castContext.getSessionManager().getCurrentCastSession();
        if (session != null && session.isConnected()) {
            loadMedia(session, url, title);
            return;
        }

        pendingUrl = url;
        pendingTitle = title;

        activity.runOnUiThread(() -> {
            try {
                MediaRouteSelector selector = castContext.getMergedSelector();
                if (selector == null) {
                    Toast.makeText(activity, "البث للتلفاز غير مدعوم على هذا الجهاز", Toast.LENGTH_SHORT).show();
                    return;
                }
                MediaRouteChooserDialog dialog = new MediaRouteChooserDialog(activity);
                dialog.setRouteSelector(selector);
                dialog.show();
            } catch (Throwable e) {
                Toast.makeText(activity, "تعذر فتح قائمة أجهزة البث", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadMedia(CastSession session, String url, String title) {
        RemoteMediaClient remoteMediaClient = session.getRemoteMediaClient();
        if (remoteMediaClient == null) return;

        MediaMetadata metadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC);
        metadata.putString(MediaMetadata.KEY_TITLE, (title != null && !title.isEmpty()) ? title : "يلا گول");

        MediaInfo mediaInfo = new MediaInfo.Builder(url)
                .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
                .setContentType("application/x-mpegURL")
                .setMetadata(metadata)
                .build();

        MediaLoadRequestData loadRequestData = new MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .build();

        remoteMediaClient.load(loadRequestData);
        activity.runOnUiThread(() ->
                Toast.makeText(activity, "جاري البث إلى التلفاز...", Toast.LENGTH_SHORT).show());
    }

    private final SessionManagerListener<CastSession> sessionListener = new SessionManagerListener<CastSession>() {
        @Override
        public void onSessionStarted(CastSession session, String sessionId) {
            deliverPendingIfAny(session);
        }

        @Override
        public void onSessionResumed(CastSession session, boolean wasSuspended) {
            deliverPendingIfAny(session);
        }

        @Override
        public void onSessionStartFailed(CastSession session, int error) {
            Toast.makeText(activity, "فشل الاتصال بجهاز العرض", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onSessionResumeFailed(CastSession session, int error) { }

        @Override
        public void onSessionEnded(CastSession session, int error) { }

        @Override
        public void onSessionSuspended(CastSession session, int reason) { }

        @Override
        public void onSessionEnding(CastSession session) { }

        @Override
        public void onSessionResuming(CastSession session, String sessionId) { }

        @Override
        public void onSessionStarting(CastSession session) { }

        private void deliverPendingIfAny(CastSession session) {
            if (pendingUrl != null) {
                loadMedia(session, pendingUrl, pendingTitle);
                pendingUrl = null;
                pendingTitle = null;
            }
        }
    };
}
