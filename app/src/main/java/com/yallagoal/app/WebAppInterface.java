package com.yallagoal.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

public class WebAppInterface {

    private final Context context;

    public WebAppInterface(Context context) {
        this.context = context;
    }

    /**
     * تستدعى من JavaScript داخل الصفحة (window.AndroidPlayer.playExternal(url, title))
     * لتشغيل رابط بث مباشر بمشغل فيديو خارجي بدل تشغيله داخل الصفحة.
     *
     * مهم: لا نضيف Intent.FLAG_ACTIVITY_NEW_TASK هنا عمداً، حتى يبقى المشغل الخارجي
     * على نفس مكدس المهام (task) الخاص بتطبيقنا — هذا يخلي زر الرجوع بالمشغل
     * الخارجي يرجع مباشرة لتطبيق يلا گول تلقائياً، بدون أي كود إضافي.
     */
    @JavascriptInterface
    public void playExternal(String url, String title) {
        if (url == null || url.isEmpty() || !(context instanceof Activity)) return;

        Activity activity = (Activity) context;
        activity.runOnUiThread(() -> {
            Uri uri = Uri.parse(url);

            // نفضّل VLC لأنه من أقوى المشغلات بالتعامل مع بث HLS/الشبكة، ويتجاوز مشاكل
            // الترميز والهيدرز والبث المباشر غير المستقر بسهولة أكبر من أغلب المشغلات الأخرى.
            Intent vlcIntent = new Intent(Intent.ACTION_VIEW);
            vlcIntent.setPackage("org.videolan.vlc");
            vlcIntent.setDataAndType(uri, "video/*");
            if (title != null && !title.isEmpty()) {
                vlcIntent.putExtra("title", title);
            }

            try {
                context.startActivity(vlcIntent);
                return;
            } catch (ActivityNotFoundException ignored) {
                // VLC غير مثبت، نكمل للخيار التالي
            }

            // إذا VLC غير موجود، نعرض على المستخدم أي مشغل فيديو آخر مثبت بجهازه
            Intent genericIntent = new Intent(Intent.ACTION_VIEW);
            genericIntent.setDataAndType(uri, "video/*");
            if (title != null && !title.isEmpty()) {
                genericIntent.putExtra("title", title);
            }

            try {
                context.startActivity(Intent.createChooser(genericIntent, "افتح باستخدام"));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(context,
                        "لا يوجد مشغل فيديو مثبت على جهازك. ثبّت تطبيق VLC للحصول على أفضل تجربة تشغيل.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * تستدعى من زر "تثبيت" بشاشة الإعدادات لفتح صفحة تطبيق على متجر Google Play.
     */
    @JavascriptInterface
    public void openPlayStore(String packageName) {
        if (packageName == null || packageName.isEmpty() || !(context instanceof Activity)) return;

        Activity activity = (Activity) context;
        activity.runOnUiThread(() -> {
            try {
                context.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + packageName)));
            } catch (ActivityNotFoundException e) {
                try {
                    context.startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=" + packageName)));
                } catch (ActivityNotFoundException e2) {
                    Toast.makeText(context, "تعذر فتح متجر التطبيقات.", Toast.LENGTH_LONG).show();
                }
            }
        });
    }
}
