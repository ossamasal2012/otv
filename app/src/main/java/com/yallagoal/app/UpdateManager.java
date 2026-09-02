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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * نظام تحديث OTA بوضعين قابلين للاختيار من الإعدادات:
 *
 *  - "ذكي" (المُفضَّل والافتراضي): يقتصر التنزيل على ملفات واجهة الويب (index.html وما شابهها
 *    مستقبلاً) التي تغيّرت فعلياً منذ آخر إصدار — حجم تنزيل أصغر بكثير. متاح فقط عندما يُصرِّح
 *    الإصدار البعيد نفسه (عبر version.json) أن هذا الإصدار "ويب فقط" (لا تغييرات بكود
 *    Java/Kotlin المُترجَم، الذي لا يمكن تحديثه إطلاقاً إلا عبر تثبيت APK كامل — قيد تقني لا
 *    يمكن تجاوزه أبداً)؛ إن لم يكن متاحاً، يتراجع تلقائياً وبصمت لوضع التحديث الكامل.
 *  - "كامل": تثبيت APK جديد بالكامل (الآلية الأصلية، دون تغيير بمنطقها).
 *
 * التنزيل الفعلي (لأي من الوضعين) لم يعد يحدث هنا إطلاقاً — بل بمعرفة UpdateDownloadService
 * (خدمة Foreground حقيقية تستمر بالعمل حتى لو أُغلق التطبيق كلياً، وتُظهر تقدّمها بشريط
 * الإشعارات). هذا الملف الآن مسؤول فقط عن: فحص version.json، عرض حوارات "تحديث متوفر"/
 * "تعذر التحديث"، بدء الخدمة، ومتابعة تقدّمها اللحظي عبر UpdateProgressBus بينما التطبيق
 * بالمقدمة (حوار التقدّم البرمجي نفسه بلا أي تغيير بشكله).
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
    static final String PREF_UPDATE_MODE = "update_mode";
    static final String PREF_SMART_VERSION_CODE = "smart_update_version_code";
    // نسخة الـ"تحديث الذكي" السابقة المحفوظة كـ backup حقيقي قبل تفعيل نسخة جديدة (راجع
    // UpdateDownloadService.markSmartUpdateBackup وUpdateManager.rollBackToLastGoodVersion) —
    // تتيح Rollback حقيقي لآخر نسخة كانت تعمل فعلاً، بدل القفز مباشرة للأصل المرفق بالحزمة.
    static final String PREF_SMART_VERSION_CODE_BACKUP = "smart_update_version_code_backup";
    static final String PREF_READY_APK_VERSION_CODE = "ready_apk_version_code";
    static final String PREF_READY_APK_SHA256 = "ready_apk_sha256";

    static final String MODE_SMART = "smart";
    static final String MODE_FULL = "full";

    /** التفضيل الحالي المحفوظ ("smart" أو "full") — الافتراضي "smart" كما طلب المستخدم. */
    static String getUpdatePreference(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_UPDATE_MODE, MODE_SMART);
    }

    /** تُستدعى من WebAppInterface عند تغيير المستخدم لطريقة التحديث بشاشة الإعدادات (JS). */
    static void setUpdatePreference(Context context, String mode) {
        String normalized = MODE_FULL.equals(mode) ? MODE_FULL : MODE_SMART;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_UPDATE_MODE, normalized)
                .apply();
    }

    private Activity activity;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // بيانات الإصدار المُكتشف، محفوظة لإتاحة "إعادة المحاولة" دون الحاجة لإعادة جلب version.json
    private String pendingVersionName = "";
    private String pendingChangelog = "";
    private String pendingDownloadUrl = "";
    private String pendingSha256 = "";
    private int pendingVersionCode = 0;
    private boolean pendingForce = true;
    private boolean pendingWebOnlyUpdate = false;
    private String pendingAssetsManifestJson = "";

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
                if (readyToInstall) {
                    installApk(activity, getDestFile());
                } else {
                    showSmartUpdateSuccessDialog(message);
                }
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

                // حقلان اختياريان جديدان لدعم "التحديث الذكي" — غيابهما (أي إصدار version.json
                // قديم لم يُحدَّث بعد لدعم هذه الميزة) يعني ببساطة عدم توفّر الوضع الذكي لهذا
                // الإصدار تحديداً، فيتراجع الوضع تلقائياً وبصمت لتحديث كامل — توافق كامل للخلف.
                boolean webOnly = json.optBoolean("web_only_update", false);
                JSONArray manifestArr = json.optJSONArray("assets_manifest");
                String manifestJson = manifestArr != null ? manifestArr.toString() : "";

                int localVersionCode = getLocalVersionCode();

                // ===== إصلاح مشكلة "ظهور نفس التحديث مرة ثانية بعد نجاحه" من جذورها =====
                // localVersionCode (من PackageManager) هو رقم الـAPK الحقيقي المثبَّت، ولا يتغيّر
                // أبداً بتحديث ذكي (محتوى فقط) — قيد Android نفسه، وليس نقصاً بالتنفيذ. فلو قارنّا
                // remoteVersionCode بـlocalVersionCode وحدها، تبقى المقارنة "أحدث" صحيحة إلى الأبد
                // بعد أي تحديث ذكي ناجح — حتى لو كان المحتوى الفعلي على الجهاز مطابقاً تماماً لما
                // يعلنه السيرفر — وهذا كان بالضبط السبب البرمجي المباشر للمشكلة.
                //
                // الحل: نحسب effectiveInstalledVersionCode الذي يضيف آخر تحديث ذكي تم تفعيله
                // *وتحقّقت ملفاته الآن فعلياً* من نسخة الـAPK الخام. لا نثق بعلامة SharedPreferences
                // (PREF_SMART_VERSION_CODE) بمجرّدها أبداً — راجع getVerifiedSmartVersionCode: تتحقق
                // من SHA-256 لكل ملف فعلي على القرص مقابل هذا المانيفست البعيد نفسه قبل الوثوق بها.
                int effectiveInstalledVersionCode = localVersionCode;
                if (webOnly && !manifestJson.isEmpty()) {
                    int verifiedSmartVersion = getVerifiedSmartVersionCode(manifestJson, remoteVersionCode);
                    if (verifiedSmartVersion > effectiveInstalledVersionCode) {
                        effectiveInstalledVersionCode = verifiedSmartVersion;
                    }
                }

                // نفس شرط منع الـ Downgrade الأصلي، لكن مقارَناً الآن بالنسخة الفعّالة الحقيقية
                // (الأعلى بين نسخة الـAPK ونسخة المحتوى الذكي المُتحقَّق منها فعلياً) بدل نسخة
                // الـAPK الخام فقط.
                if (remoteVersionCode > effectiveInstalledVersionCode) {
                    pendingVersionName = remoteVersionName;
                    pendingChangelog = changelog;
                    pendingDownloadUrl = downloadUrl;
                    pendingSha256 = sha256;
                    pendingVersionCode = remoteVersionCode;
                    pendingForce = force;
                    pendingWebOnlyUpdate = webOnly;
                    pendingAssetsManifestJson = manifestJson;
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

    /**
     * يتحقق مما إذا كانت آخر نسخة "تحديث ذكي" مُسجَّلة فعلياً (PREF_SMART_VERSION_CODE) تطابق
     * تماماً نسخة المانيفست البعيد الحالي (remoteVersionCode) *و* أن ملفاتها الموجودة الآن على
     * القرص (web_override/) تطابق SHA-256 كل ملف بهذا المانيفست بالضبط — بدل الوثوق بعلامة
     * SharedPreferences مجرّدة (بالضبط كما طُلب: مصدر الحقيقة هو الملفات الفعلية + الهاش، وليس
     * Flag بسيط). يُعيد رقم النسخة نفسه (> 0) فقط لو نجح كل تحقق بالكامل؛ وإلا يُعيد 0.
     *
     * ملاحظة مهمة: فشل التحقق هنا لا يمسح PREF_SMART_VERSION_CODE تلقائياً — فقط يجعلنا لا نثق
     * بها *لهذه المقارنة تحديداً*. الحذف الفعلي لملفات override معطوبة مسؤولية MainActivity عند
     * فشل تحميل الصفحة فعلياً بالـWebView (رولباك حقيقي)، حفاظاً على مبدأ "لا تُحذف نسخة قد تكون
     * سليمة فعلاً بسبب فحص متشائم واحد هنا" (مثلاً أثناء انقطاع I/O عابر).
     */
    private int getVerifiedSmartVersionCode(String manifestJson, int remoteVersionCode) {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            int smartVersion = prefs.getInt(PREF_SMART_VERSION_CODE, 0);
            // نتحقق فقط عندما تساوي النسخة الذكية المسجَّلة نسخة المانيفست البعيد الحالي بالضبط:
            // فقط عندها نملك هاشات موثوقة (من هذا المانيفست نفسه) لمقارنة الملفات الفعلية بها.
            if (smartVersion <= 0 || smartVersion != remoteVersionCode) return 0;

            File overrideDir = new File(activity.getFilesDir(), "web_override");
            if (!overrideDir.isDirectory()) return 0;

            JSONArray arr = new JSONArray(manifestJson);
            if (arr.length() == 0) return 0;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String path = obj.optString("path", "");
                String expectedSha256 = obj.optString("sha256", "");
                if (path.isEmpty() || expectedSha256.isEmpty() || path.contains("..")) return 0;

                File localFile = new File(overrideDir, path);
                if (!localFile.isFile()) return 0;

                String actual = computeSha256Quietly(localFile);
                if (actual == null || !actual.equalsIgnoreCase(expectedSha256.trim())) return 0;
            }

            return smartVersion; // كل ملف تحقق بنجاح — يمكن الوثوق بهذه النسخة كمثبَّتة فعلياً
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

        // إشارة صغيرة لتوضيح أي وضع تحديث يعمل حالياً — شفافية للمستخدم بلا إغراقه بالتفاصيل
        boolean useSmart = MODE_SMART.equals(getUpdatePreference(activity)) && isSmartModeAvailable();
        TextView modeHint = new TextView(activity);
        modeHint.setText(useSmart ? "تحديث ذكي — حجم تنزيل أصغر" : "تحديث كامل");
        modeHint.setTextColor(Color.parseColor("#64748B"));
        modeHint.setTextSize(11);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(14);
        root.addView(modeHint, hintParams);

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

    private boolean isSmartModeAvailable() {
        return pendingWebOnlyUpdate && pendingAssetsManifestJson != null && !pendingAssetsManifestJson.isEmpty();
    }

    private void beginDownloadFlow() {
        if (isDownloading || !isActivityUsable()) return;
        isDownloading = true;
        showProgressDialog();

        boolean useSmartMode = MODE_SMART.equals(getUpdatePreference(activity)) && isSmartModeAvailable();

        UpdateProgressBus.setListener(progressListener);

        Intent serviceIntent = new Intent(activity, UpdateDownloadService.class);
        serviceIntent.putExtra(UpdateDownloadService.EXTRA_VERSION_CODE, pendingVersionCode);
        serviceIntent.putExtra(UpdateDownloadService.EXTRA_VERSION_NAME, pendingVersionName);

        if (useSmartMode) {
            serviceIntent.putExtra(UpdateDownloadService.EXTRA_MODE, UpdateDownloadService.MODE_SMART);
            serviceIntent.putExtra(UpdateDownloadService.EXTRA_MANIFEST_JSON, pendingAssetsManifestJson);
        } else {
            serviceIntent.putExtra(UpdateDownloadService.EXTRA_MODE, UpdateDownloadService.MODE_FULL);
            serviceIntent.putExtra(UpdateDownloadService.EXTRA_DOWNLOAD_URL, pendingDownloadUrl);
            serviceIntent.putExtra(UpdateDownloadService.EXTRA_SHA256, pendingSha256);
        }

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

    private void showSmartUpdateSuccessDialog(String message) {
        if (!isActivityUsable()) return;
        new AlertDialog.Builder(activity)
                .setTitle("تم التحديث")
                .setMessage((message != null ? message : "تم تحديث محتوى التطبيق.") + "\n\nسيتم تطبيق التحديث الآن.")
                .setCancelable(true)
                .setPositiveButton("حسناً", (dialog, which) -> {
                    dialog.dismiss();
                    if (activity instanceof MainActivity) {
                        ((MainActivity) activity).reloadWebViewForSmartUpdate();
                    }
                })
                .show();
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

    /**
     * يُستدعى من MainActivity قبل تحميل الصفحة الرئيسية: هل توجد نسخة override محلية صالحة
     * (من تحديث ذكي سابق) لملف index.html يجب استخدامها بدل الأصل المرفق بحزمة التطبيق؟
     * يتضمّن فحص "تقادم" ذاتي: لو صار الإصدار المثبَّت فعلياً (تحديث كامل جديد، أو حتى إعادة
     * تثبيت يدوي) أحدث من (أو يساوي) آخر تحديث ذكي مُسجَّل، تُحذف نسخة الـ override القديمة
     * تلقائياً ويُعاد استخدام الأصل المرفق مباشرة (الذي بات مضموناً أحدث أو مساوياً لها).
     */
    static File resolveWebOverrideIndexFile(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            int smartVersion = prefs.getInt(PREF_SMART_VERSION_CODE, 0);
            if (smartVersion <= 0) return null;

            int realVersionCode = getLocalVersionCode(context);
            File overrideDir = new File(context.getFilesDir(), "web_override");

            if (realVersionCode >= smartVersion) {
                deleteRecursivelyStatic(overrideDir);
                prefs.edit().remove(PREF_SMART_VERSION_CODE).apply();
                return null;
            }

            File indexFile = new File(overrideDir, "index.html");
            return indexFile.exists() ? indexFile : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * تُستدعى من MainActivity عند فشل "فحص السلامة" لصفحة override (تحديث ذكي سابق) — تمسح
     * نسخة الـoverride كاملة وعلامتها فوراً، فيعود التطبيق للأصل المرفق بالحزمة بأمان تام
     * بأي تشغيل قادم (أو فوراً، إذ يُعيد MainActivity تحميل الصفحة مباشرة بعد هذا الاستدعاء).
     */
    public static void clearWebOverride(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            File overrideDir = new File(context.getFilesDir(), "web_override");
            deleteRecursivelyStatic(overrideDir);
            prefs.edit().remove(PREF_SMART_VERSION_CODE).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * Rollback حقيقي (وليس مجرد Fallback): تُستدعى من MainActivity عند فشل "فحص السلامة" لصفحة
     * override بعد تفعيل تحديث ذكي جديد مباشرة. تحاول أولاً استرجاع آخر نسخة محتوى كانت مُفعَّلة
     * فعلياً *قبل* هذا التحديث الفاشل (web_override_backup — يحتفظ بها UpdateDownloadService
     * تلقائياً قبل كل تفعيل جديد، راجع markSmartUpdateBackup هناك)، بدل القفز مباشرة للأصل
     * المرفق بالحزمة. فقط لو لم توجد نسخة backup أصلاً (أو تعذّر استرجاعها بأمان) يُستخدم الأصل
     * المرفق كملاذ أخير — بالضبط الفرق المطلوب بين "Rollback حقيقي" و"Fallback" فقط.
     *
     * @return true لو استُرجعت نسخة backup فعلية وأصبحت هي الـactive الجديدة، أو false لو تم
     *         اللجوء مباشرة للأصل المرفق بالحزمة (وعندها مُسحت كل علامات التحديث الذكي).
     */
    public static boolean rollBackToLastGoodVersion(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            File overrideDir = new File(context.getFilesDir(), "web_override");
            File backupDir = new File(context.getFilesDir(), "web_override_backup");

            // النسخة الجديدة الفاشلة يجب ألا تبقى active بأي حال — تُحذف أولاً دائماً.
            deleteRecursivelyStatic(overrideDir);

            int backupVersion = prefs.getInt(PREF_SMART_VERSION_CODE_BACKUP, 0);
            if (backupDir.isDirectory() && backupVersion > 0 && backupDir.renameTo(overrideDir)) {
                prefs.edit()
                        .putInt(PREF_SMART_VERSION_CODE, backupVersion)
                        .remove(PREF_SMART_VERSION_CODE_BACKUP)
                        .apply();
                return true;
            }

            // لا توجد نسخة backup موثوقة (غير موجودة، أو بلا رقم إصدار مسجَّل، أو تعذّر نقلها) —
            // لا نثق بأي بقايا محتملة، ونعود للأصل المرفق بالحزمة كملاذ أخير آمن.
            deleteRecursivelyStatic(overrideDir);
            deleteRecursivelyStatic(backupDir);
            prefs.edit().remove(PREF_SMART_VERSION_CODE).remove(PREF_SMART_VERSION_CODE_BACKUP).apply();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteRecursivelyStatic(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) deleteRecursivelyStatic(child);
                else child.delete();
            }
        }
        dir.delete();
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
