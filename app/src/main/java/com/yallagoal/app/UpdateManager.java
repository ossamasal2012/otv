package com.yallagoal.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * نظام تحديث OTA حقيقي ومتكامل.
 *
 * يعتمد على نفس آلية version.json المستضافة على أحدث إصدار على GitHub (لم تتغير)، لكنه يستبدل
 * التنزيل القديم عبر DownloadManager (الذي لا يوفر تقدماً دقيقاً بالبايت ولا تحقق سلامة) بتنزيل
 * مباشر عبر OkHttp (نفس مكتبة الشبكة المستخدمة أصلاً في UserStatsManager) مع:
 *   - قراءة التيار (Stream) يدوياً بايتاً بايت لحساب downloadedBytes/totalBytes حقيقيين.
 *   - حساب SHA-256 أثناء التنزيل نفسه (بدون قراءة إضافية للملف) والتحقق منه مقابل القيمة الموثوقة
 *     الموجودة في version.json (حقل "sha256" الاختياري).
 *   - منع التثبيت نهائياً إن فشل أي تحقق (الحجم، الهاش، رقم الإصدار).
 *   - حوار تقدّم مبني برمجياً بالكامل (بدون أي ملف Layout خارجي) يعمل على UI Thread فقط بينما
 *     التنزيل يعمل على Thread منفصل تماماً.
 */
public class UpdateManager {

    // ⚠️ مهم جداً: غيّر السطر التالي إلى "اسم_المستخدم/اسم_المستودع" الخاص بك بالضبط
    private static final String GITHUB_REPO = "ossamasal2012/otv";

    private static final String VERSION_URL =
            "https://github.com/" + GITHUB_REPO + "/releases/latest/download/version.json";

    private static final String UPDATE_FILE_NAME = "update.apk";
    private static final String UPDATE_TEMP_FILE_NAME = "update.apk.part";

    private Activity activity;

    // عميل HTTP واحد يُعاد استخدامه لكل من فحص version.json وتنزيل ملف التحديث.
    // readTimeout هنا هو مهلة بين كل حزمة بيانات وأخرى (وليس مهلة إجمالية للتنزيل) — لذلك
    // تنزيل كبير وبطيء لكنه "حي" ومستمر لن يُقطع، بينما اتصال متجمد فعلاً سيُكتشف ويُصنَّف كخطأ.
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // بيانات الإصدار المُكتشف، محفوظة لإتاحة "إعادة المحاولة" دون الحاجة لإعادة جلب version.json
    private String pendingVersionName = "";
    private String pendingChangelog = "";
    private String pendingDownloadUrl = "";
    private String pendingSha256 = "";
    private int pendingVersionCode = 0;
    private boolean pendingForce = true;

    private volatile boolean isDownloading = false;
    private volatile boolean downloadCancelled = false;

    // عناصر حوار التقدّم
    private AlertDialog progressDialog;
    private TextView progressStatusText;
    private TextView progressPercentText;
    private TextView progressSizeText;
    private ProgressBar progressBar;

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

                // نفس شرط منع الـ Downgrade الأصلي: لا تحديث إطلاقاً إن لم يكن الإصدار البعيد أحدث فعلياً
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
        try {
            PackageInfo pInfo = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
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
        // زر "إعادة المحاولة" في حوار الخطأ هو الطريق الوحيد للتكرار، ولا زر رجوع هنا.
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
     * يحدّث واجهة التقدّم بأرقام حقيقية فقط. totalBytes = -1 تعني أن Content-Length غير متوفر،
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
                // Content-Length غير متوفر: لا نسبة مئوية وهمية، فقط مؤشر تقدّم غير محدد + الحجم المُنزَّل
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

    // ==================== بدء التنزيل ====================

    private void beginDownloadFlow() {
        if (isDownloading) return;
        showProgressDialog();
        startDownload();
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

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true; // لا نمنع المحاولة إن تعذّر الاستعلام عن حالة الشبكة نفسها
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    private void startDownload() {
        isDownloading = true;
        downloadCancelled = false;

        new Thread(() -> {
            try {
                if (!isNetworkAvailable()) {
                    finishWithError("لا يوجد اتصال بالإنترنت. تحقق من الشبكة وحاول مجدداً.");
                    return;
                }

                // مسار سريع: إن كان لدينا بالفعل ملفاً محملاً ومُتحقَّقاً مسبقاً من محاولة سابقة
                // (مثلاً المستخدم رجع من إعدادات "السماح من هذا المصدر" بدل الشبكة) لا داعي لإعادة
                // التنزيل بالكامل من جديد — فقط أعد التحقق منه ثم ثبّته مباشرة.
                File existingVerified = getDestFile();
                if (existingVerified.exists() && !pendingSha256.isEmpty()
                        && verifyExistingFile(existingVerified)) {
                    updateProgressUI(existingVerified.length(), existingVerified.length(),
                            "تم العثور على تحديث محمَّل مسبقاً، جاري التحقق...");
                    proceedToInstall(existingVerified);
                    return;
                }

                downloadAndVerify();
            } catch (Exception e) {
                finishWithError(classifyError(e));
            }
        }).start();
    }

    private boolean verifyExistingFile(File file) {
        try {
            String actualHash = computeSha256(file);
            return actualHash != null && actualHash.equalsIgnoreCase(pendingSha256.trim());
        } catch (Exception e) {
            return false;
        }
    }

    private String computeSha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return bytesToHex(digest.digest());
    }

    private void downloadAndVerify() throws IOException, NoSuchAlgorithmException {
        File tempFile = getTempFile();
        File destFile = getDestFile();

        if (tempFile.exists()) tempFile.delete();

        String cacheBustedUrl = pendingDownloadUrl
                + (pendingDownloadUrl.contains("?") ? "&" : "?")
                + "cb=" + System.currentTimeMillis();

        Request request = new Request.Builder().url(cacheBustedUrl).build();

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long downloadedBytes = 0;
        long totalBytes = -1;

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                finishWithError("فشل الاتصال بالسيرفر (HTTP " + response.code() + "). حاول مرة أخرى.");
                return;
            }

            ResponseBody body = response.body();
            if (body == null) {
                finishWithError("استجابة فارغة من السيرفر. حاول مرة أخرى.");
                return;
            }

            totalBytes = body.contentLength(); // -1 إن كان Content-Length غير متوفر من السيرفر

            try (InputStream in = body.byteStream();
                 OutputStream out = new FileOutputStream(tempFile)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                long lastUiUpdateMs = 0;
                final long fTotalBytes = totalBytes;

                while ((bytesRead = in.read(buffer)) != -1) {
                    if (downloadCancelled) {
                        finishWithError("تم إلغاء التنزيل.");
                        return;
                    }

                    out.write(buffer, 0, bytesRead);
                    digest.update(buffer, 0, bytesRead);
                    downloadedBytes += bytesRead;

                    long now = System.currentTimeMillis();
                    boolean isComplete = fTotalBytes > 0 && downloadedBytes >= fTotalBytes;
                    if (now - lastUiUpdateMs > 150 || isComplete) {
                        lastUiUpdateMs = now;
                        final long fDownloaded = downloadedBytes;
                        updateProgressUI(fDownloaded, fTotalBytes, "جاري تحميل التحديث...");
                    }
                }
                out.flush();
            }
        } catch (SocketTimeoutException e) {
            finishWithError("انتهت مهلة الاتصال بالسيرفر (اتصال ضعيف أو متقطع). حاول مرة أخرى.");
            return;
        } catch (UnknownHostException e) {
            finishWithError("تعذر الوصول للسيرفر. تحقق من اتصال الإنترنت.");
            return;
        } catch (IOException e) {
            finishWithError(classifyError(e));
            return;
        }

        // ===== التحقق من اكتمال الملف (لا يُعتبر وصول 100% وحده دليلاً كافياً) =====
        if (downloadedBytes <= 0 || tempFile.length() <= 0) {
            deleteQuietly(tempFile);
            finishWithError("فشل تنزيل ملف التحديث (ملف فارغ). حاول مرة أخرى.");
            return;
        }
        if (totalBytes > 0 && tempFile.length() != totalBytes) {
            deleteQuietly(tempFile);
            finishWithError("الملف المُنزَّل غير مكتمل (الحجم لا يطابق الحجم المتوقع). حاول مرة أخرى.");
            return;
        }

        updateProgressUI(downloadedBytes, totalBytes, "جاري التحقق من سلامة الملف...");

        // ===== التحقق من سلامة الملف عبر SHA-256 (إن توفرت قيمة موثوقة من version.json) =====
        if (pendingSha256 != null && !pendingSha256.trim().isEmpty()) {
            String actualHash = bytesToHex(digest.digest());
            if (!actualHash.equalsIgnoreCase(pendingSha256.trim())) {
                // عزل/حذف الملف التالف فوراً — يُمنع تثبيته نهائياً
                deleteQuietly(tempFile);
                finishWithError("فشل التحقق من سلامة ملف التحديث. تم حذف الملف التالف تلقائياً.");
                return;
            }
        }

        // ===== إعادة التحقق من رقم الإصدار مباشرة قبل التثبيت (حماية إضافية ضد الـ Downgrade) =====
        int localVersionCode = getLocalVersionCode();
        if (pendingVersionCode <= localVersionCode) {
            deleteQuietly(tempFile);
            finishWithError("تم إلغاء التثبيت: الإصدار المُنزَّل ليس أحدث من الإصدار الحالي فعلياً.");
            return;
        }

        // جميع الفحوصات نجحت: انقل الملف المؤقت إلى مساره النهائي فقط الآن
        deleteQuietly(destFile);
        boolean moved = tempFile.renameTo(destFile);
        if (!moved) {
            deleteQuietly(tempFile);
            finishWithError("تعذر حفظ ملف التحديث على الجهاز. تحقق من مساحة التخزين المتاحة.");
            return;
        }

        proceedToInstall(destFile);
    }

    private void proceedToInstall(File apkFile) {
        isDownloading = false;
        mainHandler.post(() -> {
            dismissProgressDialog();
            installApk(apkFile);
        });
    }

    private void deleteQuietly(File file) {
        try {
            if (file != null && file.exists()) file.delete();
        } catch (Exception ignored) {
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    /**
     * يصنّف الاستثناءات الشائعة أثناء التنزيل إلى رسائل عربية واضحة ومفهومة للمستخدم،
     * بدل عرض تفاصيل تقنية خام (وفق جميع سيناريوهات الأعطال المطلوب التعامل معها باحترافية).
     */
    private String classifyError(Exception e) {
        if (e instanceof UnknownHostException) {
            return "تعذر الوصول للسيرفر. تحقق من اتصال الإنترنت.";
        }
        if (e instanceof SocketTimeoutException) {
            return "انتهت مهلة الاتصال (اتصال ضعيف أو متقطع). حاول مرة أخرى.";
        }
        if (e instanceof NoSuchAlgorithmException) {
            return "تعذر التحقق من سلامة الملف على هذا الجهاز.";
        }
        String msg = e.getMessage();
        return "تعذر إكمال التحديث" + (msg != null && !msg.isEmpty() ? " (" + msg + ")" : "") + ". حاول مرة أخرى.";
    }

    // ==================== معالجة الفشل + إعادة المحاولة ====================

    private void finishWithError(String message) {
        isDownloading = false;
        mainHandler.post(() -> {
            dismissProgressDialog();
            showErrorDialog(message);
        });
    }

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

    // ==================== التثبيت ====================

    private void installApk(File file) {
        if (!isActivityUsable()) return;
        if (!file.exists()) {
            showErrorDialog("ملف التحديث غير موجود. حاول مرة أخرى.");
            return;
        }

        // بعض الأجهزة تمنع التثبيت بصمت لو صلاحية "تثبيت تطبيقات غير معروفة" غير مفعّلة
        // لتطبيقنا تحديداً. بدل ما يفشل التحديث بدون أي رسالة، نتحقق أولاً ونوجّه المستخدم
        // مباشرة لشاشة الإعدادات الصحيحة لتفعيلها. الملف يبقى محفوظاً ومُتحقَّقاً على القرص،
        // وعند العودة والضغط على "تحديث الآن" مجدداً سيُستخدم مباشرة دون إعادة تنزيل (راجع startDownload).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(activity,
                    "يرجى تفعيل \"السماح من هذا المصدر\" لإكمال التحديث، ثم اضغط تحديث الآن مرة أخرى",
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
            // فشل تثبيت واحد يجب ألا يكسر التطبيق الأساسي إطلاقاً — نعرض خطأ واضحاً بدل الانهيار
            showErrorDialog("تعذر بدء عملية التثبيت. حاول مرة أخرى.");
        }
    }
}
