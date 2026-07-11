package com.yallagoal.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

public class WebAppInterface {

    private final Context context;
    private final CastManager castManager;

    public WebAppInterface(Context context, CastManager castManager) {
        this.context = context;
        this.castManager = castManager;
    }

    /**
     * تستدعى من JavaScript داخل الصفحة (window.AndroidPlayer.playExternal(url, title, packageName))
     * لتشغيل رابط بث مباشر بمشغل فيديو خارجي محدد (VLC) بدل تشغيله داخل الصفحة.
     * packageName هو حزمة التطبيق المختار من الإعدادات؛ لو فارغ أو غير مثبت، يعرض قائمة اختيار عامة.
     *
     * مهم: لا نضيف Intent.FLAG_ACTIVITY_NEW_TASK هنا عمداً، حتى يبقى المشغل الخارجي
     * على نفس مكدس المهام (task) الخاص بتطبيقنا — هذا يخلي زر الرجوع بالمشغل
     * الخارجي يرجع مباشرة لتطبيق يلا گول تلقائياً، بدون أي كود إضافي.
     */
    @JavascriptInterface
    public void playExternal(String url, String title, String packageName) {
        if (url == null || url.isEmpty() || !(context instanceof Activity)) return;

        Activity activity = (Activity) context;
        activity.runOnUiThread(() -> {
            Uri uri = Uri.parse(url);

            if (packageName != null && !packageName.isEmpty()) {
                Intent targetedIntent = new Intent(Intent.ACTION_VIEW);
                targetedIntent.setPackage(packageName);
                targetedIntent.setDataAndType(uri, "video/*");
                if (title != null && !title.isEmpty()) {
                    targetedIntent.putExtra("title", title);
                }
                try {
                    context.startActivity(targetedIntent);
                    return;
                } catch (ActivityNotFoundException ignored) {
                    // التطبيق المختار غير مثبت، ننتقل لقائمة اختيار عامة
                }
            }

            Intent genericIntent = new Intent(Intent.ACTION_VIEW);
            genericIntent.setDataAndType(uri, "video/*");
            if (title != null && !title.isEmpty()) {
                genericIntent.putExtra("title", title);
            }

            try {
                context.startActivity(Intent.createChooser(genericIntent, "افتح باستخدام"));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(context,
                        "لا يوجد مشغل فيديو مثبت على جهازك. ثبّت VLC من الإعدادات.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * تستدعى من شاشة الإعدادات لمعرفة هل تطبيق معيّن (VLC) مثبت على الجهاز،
     * لإظهار زر "تثبيت" فقط عند الحاجة.
     */
    @JavascriptInterface
    public boolean isPackageInstalled(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
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

    /**
     * تستدعى من زر "بث إلى التلفاز" بجانب كل سيرفر — تفتح قائمة أجهزة العرض المتوفرة
     * على نفس الشبكة (Android TV، Chromecast، تلفزيونات LG المعتمدة من گوگل...) وتبدأ
     * تشغيل الرابط عليها مباشرة بعد الاتصال.
     */
    @JavascriptInterface
    public void castToTv(String url, String title) {
        if (!(context instanceof Activity)) return;
        Activity activity = (Activity) context;
        activity.runOnUiThread(() -> {
            if (castManager == null) {
                Toast.makeText(context, "البث للتلفاز غير مدعوم على هذا الجهاز", Toast.LENGTH_SHORT).show();
                return;
            }
            castManager.castToTv(url, title);
        });
    }
}
