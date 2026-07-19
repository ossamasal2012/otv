package com.yallagoal.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class WebAppInterface {

    private final Context context;
    private final CastManager castManager;
    private static final String PREFS_NAME = "yg_secure_auth";
    private static final String PREF_AUTHENTICATED = "authenticated";
    private static final String PREF_FAILED_ATTEMPTS = "failed_attempts";
    private static final String PREF_LOCK_UNTIL = "lock_until";
    private static final String PREF_LOCKOUT_LEVEL = "lockout_level";
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long MINUTE_MS = 60 * 1000L;
    private static final long HOUR_MS = 60 * MINUTE_MS;
    private static final String PIN_SHA256 = "5c66dbe863f48e55859f06cbb8becb9dd433af344ed1b5f04608dc50359f56ae";

    private final String bridgeToken;
    private final SharedPreferences authPrefs;

    public WebAppInterface(Context context, CastManager castManager, String bridgeToken) {
        this.context = context;
        this.castManager = castManager;
        this.bridgeToken = bridgeToken;
        this.authPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * كل استدعاء من الصفحة لازم يمرر نفس الرمز اللي زرعناه بصفحتنا الموثوقة فقط بعد تحميلها
     * (window.__YG_TOKEN__). أي محتوى iframe خارجي (قنوات من مواقع أخرى) لا يقدر يقرأ هذا
     * المتغير من نافذة الصفحة الرئيسية بسبب قيود المتصفح بين النطاقات المختلفة، فحتى لو حاول
     * ينادي دوال Android مباشرة، الاستدعاء يُرفض هنا بصمت.
     */
    private boolean isTokenValid(String token) {
        return bridgeToken != null && MessageDigest.isEqual(
                bridgeToken.getBytes(StandardCharsets.UTF_8),
                (token == null ? "" : token).getBytes(StandardCharsets.UTF_8));
    }

    private boolean isUnlocked() {
        return authPrefs.getBoolean(PREF_AUTHENTICATED, false);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    @JavascriptInterface
    public boolean isAuthenticated(String token) {
        return isTokenValid(token) && isUnlocked();
    }

    private static long lockoutDurationMs(int lockoutLevel) {
        if (lockoutLevel <= 1) return 5 * MINUTE_MS;
        if (lockoutLevel == 2) return 30 * MINUTE_MS;
        if (lockoutLevel == 3) return 2 * HOUR_MS;
        if (lockoutLevel == 4) return 12 * HOUR_MS;
        return 24 * HOUR_MS;
    }

    @JavascriptInterface
    public long getAuthLockRemainingMs(String token) {
        if (!isTokenValid(token)) return 0L;
        long remainingMs = authPrefs.getLong(PREF_LOCK_UNTIL, 0L) - System.currentTimeMillis();
        return Math.max(remainingMs, 0L);
    }

    @JavascriptInterface
    public boolean unlockApp(String token, String pin) {
        if (!isTokenValid(token) || pin == null) return false;

        long now = System.currentTimeMillis();
        long lockUntil = authPrefs.getLong(PREF_LOCK_UNTIL, 0L);
        if (lockUntil > now) return false;

        boolean ok = MessageDigest.isEqual(
                PIN_SHA256.getBytes(StandardCharsets.UTF_8),
                sha256(pin.trim()).getBytes(StandardCharsets.UTF_8));

        if (ok) {
            authPrefs.edit()
                    .putBoolean(PREF_AUTHENTICATED, true)
                    .remove(PREF_FAILED_ATTEMPTS)
                    .remove(PREF_LOCK_UNTIL)
                    .apply();
            return true;
        }

        int attempts = authPrefs.getInt(PREF_FAILED_ATTEMPTS, 0) + 1;
        SharedPreferences.Editor editor = authPrefs.edit().putInt(PREF_FAILED_ATTEMPTS, attempts);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            int lockoutLevel = authPrefs.getInt(PREF_LOCKOUT_LEVEL, 0) + 1;
            editor.putLong(PREF_LOCK_UNTIL, now + lockoutDurationMs(lockoutLevel))
                    .putInt(PREF_LOCKOUT_LEVEL, lockoutLevel)
                    .putInt(PREF_FAILED_ATTEMPTS, 0);
        }
        editor.apply();
        return false;
    }

    /**
     * تستدعى من JavaScript داخل الصفحة (window.AndroidPlayer.playExternal(token, url, title, packageName))
     * لتشغيل رابط بث مباشر بمشغل فيديو خارجي محدد (GTV Player) بدل تشغيله داخل الصفحة.
     * packageName هو حزمة التطبيق المختار من الإعدادات؛ لو فارغ أو غير مثبت، يعرض قائمة اختيار عامة.
     *
     * مهم: لا نضيف Intent.FLAG_ACTIVITY_NEW_TASK هنا عمداً، حتى يبقى المشغل الخارجي
     * على نفس مكدس المهام (task) الخاص بتطبيقنا — هذا يخلي زر الرجوع بالمشغل
     * الخارجي يرجع مباشرة لتطبيق يلا گول تلقائياً، بدون أي كود إضافي.
     */
    @JavascriptInterface
    public void playExternal(String token, String url, String title, String packageName) {
        if (!isTokenValid(token) || !isUnlocked()) return;
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
                        "لا يوجد مشغل فيديو مثبت على جهازك. ثبّت GTV Player من الإعدادات.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * تستدعى من شاشة الإعدادات لمعرفة هل تطبيق معيّن (GTV Player) مثبت على الجهاز،
     * لإظهار زر "تثبيت" فقط عند الحاجة.
     */
    @JavascriptInterface
    public boolean isPackageInstalled(String token, String packageName) {
        if (!isTokenValid(token) || !isUnlocked()) return false;
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
    public void openPlayStore(String token, String packageName) {
        if (!isTokenValid(token) || !isUnlocked()) return;
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
     * تستدعى من الصفحة عند تحميلها لأخذ قراءة فورية لآخر أرقام معروفة (إجمالي المستخدمين
     * والمستخدمين النشطين الآن) قبل وصول أي تحديث حيّ لاحق عبر window.__ygOnStatsUpdate.
     * لا تتطلب فتح القفل (PIN) لأنها بيانات إجمالية عامة غير حساسة لأي مستخدم بعينه.
     */
    @JavascriptInterface
    public String getSnapshotStats(String token) {
        if (!isTokenValid(token)) return "{\"total\":0,\"active\":0}";
        UserStatsManager manager = UserStatsManager.getInstance(context);
        long total = manager.getCachedTotalUsers();
        long active = manager.getCachedActiveUsers();
        return "{\"total\":" + total + ",\"active\":" + active + "}";
    }

    /**
     * تستدعى من الصفحة كل مرة يُفتح أو يُغلق فيها مشغل iframe بملء الشاشة (القنوات النادرة من
     * نوع "i"). هذا يسمح لزر الرجوع بأندرويد أن يخرج من الـ iframe فقط بدل إغلاق التطبيق كاملاً.
     */
    @JavascriptInterface
    public void notifyIframeState(boolean isOpen, String token) {
        if (!isTokenValid(token)) return;
        if (context instanceof MainActivity) {
            ((MainActivity) context).setIframeOpen(isOpen);
        }
    }

    /**
     * تستدعى من زر "بث إلى التلفاز" بجانب كل سيرفر — تفتح قائمة أجهزة العرض المتوفرة
     * على نفس الشبكة (Android TV، Chromecast، تلفزيونات LG المعتمدة من گوگل...) وتبدأ
     * تشغيل الرابط عليها مباشرة بعد الاتصال.
     */
    @JavascriptInterface
    public void castToTv(String token, String url, String title) {
        if (!isTokenValid(token) || !isUnlocked()) return;
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
