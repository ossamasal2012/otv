package com.yallagoal.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * نظام تحديث OTA — تحديث كامل فقط: أي إصدار جديد (يُكتشف عبر رفع versionCode في
 * app/build.gradle، والذي يُقارَن مباشرة برقم الإصدار الحقيقي المثبَّت على الجهاز عبر
 * PackageManager) يعني تنزيل وتثبيت ملف APK كامل جديد. لا يوجد أي مسار "تحديث ذكي"/محتوى فقط
 * بأي شكل — أُزيل بالكامل عمداً (راجع تقرير حذف نظام التحديث الذكي المرفق لتفاصيل السبب
 * والقرار)، فلا وجود لأي مجلد override، ولا أي ملف يُستبدَل بمعزل عن الـAPK نفسه.
 *
 * التنزيل الفعلي لا يحدث هنا إطلاقاً — بل بمعرفة UpdateDownloadService (خدمة Foreground حقيقية
 * تستمر بالعمل حتى لو أُغلق التطبيق كلياً، وتُظهر تقدّمها بشريط الإشعارات). هذا الملف مسؤول فقط
 * عن: فحص version.json، عرض حوارات "تحديث متوفر"/"تعذر التحديث"، بدء الخدمة، ومتابعة تقدّمها
 * اللحظي عبر UpdateProgressBus بينما التطبيق بالمقدمة.
 */
public class UpdateManager {

    // ⚠️ مهم جداً: غيّر السطر التالي إلى "اسم_المستخدم/اسم_المستودع" الخاص بك بالضبط
    private static final String GITHUB_REPO = "ossamasal2012/otv";

    private static final String VERSION_URL =
            "https://github.com/" + GITHUB_REPO + "/releases/latest/download/version.json";

    private static final String UPDATE_FILE_NAME = "update.apk";
    private static final String UPDATE_TEMP_FILE_NAME = "update.apk.part";

    // ==================== تفضيلات وعلامات مشتركة مع UpdateDownloadService وMainActivity ====================

    static final String PREFS_NAME = "yg_update_prefs";
    static final String PREF_READY_APK_VERSION_CODE = "ready_apk_version_code";
    static final String PREF_READY_APK_SHA256 = "ready_apk_sha256";

    private Activity activity;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // بيانات الإصدار المُكتشف، محفوظة لإتاحة "إعادة المحاولة" دون الحاجة لإعادة جلب version.json
    private String pendingVersionName = "";
    private String pendingChangelog = "";
    private String pendingDownloadUrl = "";
    private String pendingSha256 = "";
    private int pendingVersionCode = 0;
    private boolean pendingForce = true;

    private volatile boolean isDownloading = false;

    // عناصر حوار التقدّم
    private AlertDialog progressDialog;
    private TextView progressStatusText;
    private TextView progressPercentText;
    private TextView progressSizeText;
    private ProgressBar progressBar;

    private final UpdateProgressBus.Listener progressListener = new UpdateProgressBus.Listener() {
        @Override
        public void onProgress(long downloadedBytes, long totalBytes, String statusText) {
            updateProgressUI(downloadedBytes, totalBytes,
                    (statusText != null && !statusText.isEmpty()) ? statusText : "جاري تحميل التحديث...");
        }

        @Override
        public void onCompleted(boolean success, String message, boolean readyToInstall) {
            isDownloading = false;
            UpdateProgressBus.setListener(null);
            mainHandler.post(() -> {
                dismissProgressDialog();
                if (!success) {
                    showErrorDialog(message);
                    return;
                }
                // تحديث كامل دائماً الآن: النجاح يعني APK جاهز ومُتحقَّق منه على القرص، فوراً
                // للتثبيت (readyToInstall صحيح دوماً بهذا المسار — راجع UpdateDownloadService).
                installApk(activity, getDestFile());
            });
        }
    };

    public void checkForUpdate(Activity activity) {
        this.activity = activity;
        cleanupStaleTempFile();

        new Thread(() -> {
            try {
                URL url = new URL(VERSION_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setInstanceFollowRedirects(true);

                InputStream is = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                is.close();

                JSONObject json = new JSONObject(bos.toString("UTF-8"));
                int remoteVersionCode = json.getInt("versionCode");
                String remoteVersionName = json.optString("versionName", "");
                String changelog = json.optString("changelog", "");
                String downloadUrl = json.getString("download_url");
                // sha256 اختياري: إصدارات قديمة من version.json قد لا تحتويه بعد، ونتعامل مع غيابه بأمان
                String sha256 = json.optString("sha256", "");
                boolean force = json.optBoolean("force_update", true);

                int localVersionCode = getLocalVersionCode();

                // منع الـ Downgrade: لا تحديث إطلاقاً إن لم يكن الإصدار البعيد أحدث فعلياً من
                // النسخة الحقيقية المثبَّتة على الجهاز (PackageManager) — مقارنة مباشرة وموثوقة
                // تماماً بما أن التحديث الوحيد الممكن الآن هو تثبيت APK فعلي، الذي يغيّر هذا
                // الرقم نفسه فور نجاحه، فلا وجود لأي حالة قد تُظهر نفس التحديث مرتين.
                if (remoteVersionCode > localVersionCode) {
                    pendingVersionName = remoteVersionName;
                    pendingChangelog = changelog;
                    pendingDownloadUrl = downloadUrl;
                    pendingSha256 = sha256;
                    pendingVersionCode = remoteVersionCode;
                    pendingForce = force;
                    showDialogOnUiThread();
                }
            } catch (Exception e) {
                // فشل صامت في الفحص الدوري: لا إنترنت، أو المستودع غير عام بعد، أو ما فيه إصدار منشور بعد.
                // هذا لا يجب أن يؤثر على عمل التطبيق الأساسي إطلاقاً.
            }
        }).start();
    }

    private int getLocalVersionCode() {
        return getLocalVersionCode(activity);
    }

    private static int getLocalVersionCode(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isActivityUsable() {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    // ==================== حوار "تحديث متوفر" الأولي ====================

    private void showDialogOnUiThread() {
        if (!isActivityUsable()) return;
        activity.runOnUiThread(this::showUpdateDialog);
    }

    private void showUpdateDialog() {
        if (!isActivityUsable()) return;

        String message = (pendingChangelog != null && !pendingChangelog.isEmpty())
                ? pendingChangelog
                : "يتوفر إصدار جديد من التطبيق"
                        + (pendingVersionName.isEmpty() ? "" : " (" + pendingVersionName + ")") + ".";

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("تحديث متوفر")
                .setMessage(message)
                .setCancelable(!pendingForce)
                .setPositiveButton("تحديث الآن", (dialog, which) -> beginDownloadFlow());

        if (!pendingForce) {
            builder.setNegativeButton("لاحقاً", (dialog, which) -> dialog.dismiss());
        }

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);

        if (pendingForce) {
            // يمنع إغلاق الرسالة بزر الرجوع طالما التحديث إلزامي
            dialog.setOnKeyListener((d, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK);
        }

        dialog.show();
    }

    // ==================== حوار التقدّم ====================

    private int dp(int value) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    /**
     * يبني تخطيط حوار التقدّم برمجياً بالكامل (بدون أي ملف XML خارجي في res/layout) —
     * هذا مقصود: ملف Java واحد ذاتي الاكتفاء لا يعتمد على وجود ملف موارد منفصل، حتى لا
     * يفشل البناء لو نُسي إضافة ملف جديد بالمسار الصحيح عند دمج التعديلات يدوياً بمشروع قائم.
     */
    private View buildProgressView() {
        int hPad = dp(24);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(hPad, dp(8), hPad, dp(8));

        progressStatusText = new TextView(activity);
        progressStatusText.setText("جاري تحضير التنزيل...");
        progressStatusText.setTextColor(Color.parseColor("#F1F5F9"));
        progressStatusText.setTextSize(14);
        progressStatusText.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(progressStatusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000); // دقة 0.1% لحركة أنعم بصرياً، محسوبة من downloadedBytes/totalBytes الفعليين
        progressBar.setProgress(0);
        progressBar.setIndeterminate(false);
        progressBar.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#10B981")));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#1E293B")));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(10));
        barParams.topMargin = dp(16);
        root.addView(progressBar, barParams);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(10);

        progressPercentText = new TextView(activity);
        progressPercentText.setText("");
        progressPercentText.setTextColor(Color.parseColor("#10B981"));
        progressPercentText.setTypeface(Typeface.DEFAULT_BOLD);
        progressPercentText.setTextSize(13);
        progressPercentText.setGravity(Gravity.START);
        row.addView(progressPercentText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        progressSizeText = new TextView(activity);
        progressSizeText.setText("");
        progressSizeText.setTextColor(Color.parseColor("#94A3B8"));
        progressSizeText.setTextSize(12);
        progressSizeText.setGravity(Gravity.END);
        row.addView(progressSizeText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(row, rowParams);

        return root;
    }

    private void buildProgressDialogIfNeeded() {
        if (!isActivityUsable()) return;

        View view = buildProgressView();

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("جاري تحديث التطبيق")
                .setView(view)
                .setCancelable(false);

        progressDialog = builder.create();
        progressDialog.setCanceledOnTouchOutside(false);
        // التنزيل عملية إلزامية بمجرد بدئها لتفادي ملفات نصف مكتملة وتشابك محاولات متعددة —
        // زر "إعادة المحاولة" في حوار الخطأ هو الطريق الوحيد للتكرار، ولا زر رجوع هنا. كما أن
        // التنزيل نفسه يستمر بخدمة Foreground بالخلفية حتى لو أغلق المستخدم هذا الحوار بالكامل
        // (بالخروج من التطبيق)، فلا خوف من فقدان التقدّم.
        progressDialog.setOnKeyListener((d, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK);
    }

    private void showProgressDialog() {
        if (!isActivityUsable()) return;
        buildProgressDialogIfNeeded();
        if (progressDialog != null) {
            progressDialog.show();
        }
        updateProgressUI(0, -1, "جاري الاتصال بالسيرفر...");
    }

    private void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignored) {
            }
        }
        progressDialog = null;
    }

    /**
     * يحدّث واجهة التقدّم بأرقام حقيقية فقط. totalBytes <= 0 تعني أن الحجم الكلي غير معروف بعد،
     * وفي هذه الحالة لا تُخترع أي نسبة مئوية: يتحول الشريط لوضع Indeterminate (تقدّم غير محدد
     * بصرياً، وهذا صادق وليس تمثيلاً وهمياً) وتُعرض فقط كمية البيانات المُنزَّلة فعلياً.
     */
    private void updateProgressUI(long downloadedBytes, long totalBytes, String statusText) {
        mainHandler.post(() -> {
            if (progressStatusText == null) return;
            progressStatusText.setText(statusText);

            if (totalBytes > 0) {
                int permille = (int) Math.min(1000, (downloadedBytes * 1000L) / totalBytes);
                int percent = (int) Math.min(100, (downloadedBytes * 100L) / totalBytes);
                progressBar.setIndeterminate(false);
                progressBar.setProgress(permille);
                progressPercentText.setText(String.format(Locale.US, "%d%%", percent));
                progressSizeText.setText(String.format(Locale.US, "%s / %s",
                        formatMegabytes(downloadedBytes), formatMegabytes(totalBytes)));
            } else {
                progressBar.setIndeterminate(true);
                progressPercentText.setText("");
                progressSizeText.setText(downloadedBytes > 0 ? formatMegabytes(downloadedBytes) : "");
            }
        });
    }

    private String formatMegabytes(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(Locale.US, "%.0f MB", mb);
    }

    // ==================== بدء التنزيل (عبر UpdateDownloadService) ====================

    private void beginDownloadFlow() {
        if (isDownloading || !isActivityUsable()) return;
        isDownloading = true;
        showProgressDialog();

        UpdateProgressBus.setListener(progressListener);

        Intent serviceIntent = new Intent(activity, UpdateDownloadService.class);
        serviceIntent.putExtra(UpdateDownloadService.EXTRA_VERSION_CODE, pendingVersionCode);
        serviceIntent.putExtra(UpdateDownloadService.EXTRA_VERSION_NAME, pendingVersionName);
        serviceIntent.putExtra(UpdateDownloadService.EXTRA_DOWNLOAD_URL, pendingDownloadUrl);
        serviceIntent.putExtra(UpdateDownloadService.EXTRA_SHA256, pendingSha256);

        try {
            ContextCompat.startForegroundService(activity, serviceIntent);
        } catch (Exception e) {
            isDownloading = false;
            UpdateProgressBus.setListener(null);
            dismissProgressDialog();
            showErrorDialog("تعذر بدء التحديث. حاول مرة أخرى.");
        }
    }

    private File getDestFile() {
        return new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), UPDATE_FILE_NAME);
    }

    private File getTempFile() {
        return new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), UPDATE_TEMP_FILE_NAME);
    }

    private void cleanupStaleTempFile() {
        try {
            if (activity == null) return;
            File temp = getTempFile();
            if (temp.exists()) temp.delete();
        } catch (Exception ignored) {
        }
    }

    // ==================== معالجة الفشل + إعادة المحاولة ====================

    private void showErrorDialog(String message) {
        if (!isActivityUsable()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("تعذر إكمال التحديث")
                .setMessage(message)
                .setCancelable(!pendingForce)
                .setPositiveButton("إعادة المحاولة", (dialog, which) -> beginDownloadFlow());

        if (!pendingForce) {
            builder.setNegativeButton("لاحقاً", (dialog, which) -> dialog.dismiss());
        }

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);

        if (pendingForce) {
            dialog.setOnKeyListener((d, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK);
        }

        dialog.show();
    }

    // ==================== التثبيت (تُستدعى من التدفّق الحيّ، ومن MainActivity عند العودة للتطبيق) ====================

    static void installApk(Activity activity, File file) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (file == null || !file.exists()) {
            Toast.makeText(activity, "ملف التحديث غير موجود. حاول مرة أخرى.", Toast.LENGTH_LONG).show();
            return;
        }

        // بعض الأجهزة تمنع التثبيت بصمت لو صلاحية "تثبيت تطبيقات غير معروفة" غير مفعّلة
        // لتطبيقنا تحديداً. بدل ما يفشل التحديث بدون أي رسالة، نتحقق أولاً ونوجّه المستخدم
        // مباشرة لشاشة الإعدادات الصحيحة لتفعيلها. الملف يبقى محفوظاً ومُتحقَّقاً على القرص،
        // وعند العودة للتطبيق سيُثبَّت تلقائياً (راجع checkAndInstallIfReady بالأسفل).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(activity,
                    "يرجى تفعيل \"السماح من هذا المصدر\" لإكمال التحديث، ثم عد للتطبيق",
                    Toast.LENGTH_LONG).show();
            Intent settingsIntent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(settingsIntent);
            } catch (Exception ignored) {
                // بعض الأجهزة القديمة جداً لا تدعم هذه الشاشة تحديداً
            }
            return;
        }

        try {
            Uri apkUri = FileProvider.getUriForFile(
                    activity, activity.getPackageName() + ".fileprovider", file);

            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(installIntent);
        } catch (Exception e) {
            // فشل تثبيت واحد يجب ألا يكسر التطبيق الأساسي إطلاقاً — نعرض رسالة واضحة بدل الانهيار
            Toast.makeText(activity, "تعذر بدء عملية التثبيت. حاول مرة أخرى.", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * تُستدعى من MainActivity.onResume(): إذا كانت خدمة UpdateDownloadService قد أنهت تنزيل
     * تحديث كامل بنجاح وتحقّقت منه بينما كان التطبيق بالخلفية أو مغلقاً تماماً، تُثبِّته تلقائياً
     * فور عودة المستخدم — بلا حاجة لأي ضغطة إضافية، تماماً كما طُلب.
     */
    static void checkAndInstallIfReady(Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            int readyVersionCode = prefs.getInt(PREF_READY_APK_VERSION_CODE, 0);
            if (readyVersionCode <= 0) return;

            int localVersionCode = getLocalVersionCode(activity);
            if (readyVersionCode <= localVersionCode) {
                // تم التثبيت فعلاً (أو تحديث الجهاز بطريقة أخرى) — العلامة القديمة لم تعد ذات معنى
                prefs.edit().remove(PREF_READY_APK_VERSION_CODE).remove(PREF_READY_APK_SHA256).apply();
                return;
            }

            File apkFile = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), UPDATE_FILE_NAME);
            if (!apkFile.exists()) return;

            String expectedSha256 = prefs.getString(PREF_READY_APK_SHA256, "");
            if (expectedSha256 != null && !expectedSha256.isEmpty()) {
                String actual = computeSha256Quietly(apkFile);
                if (actual == null || !actual.equalsIgnoreCase(expectedSha256.trim())) return;
            }

            installApk(activity, apkFile);
        } catch (Exception ignored) {
        }
    }

    private static String computeSha256Quietly(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
