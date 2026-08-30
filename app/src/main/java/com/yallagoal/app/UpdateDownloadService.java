package com.yallagoal.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * خدمة Foreground واحدة هي المُنفِّذ الفعلي الوحيد لتنزيل التحديثات (كاملة أو ذكية) — سواء
 * كان التطبيق بالمقدمة أو الخلفية أو مغلقاً تماماً من قائمة التطبيقات الحديثة. هذا هو جوهر
 * تحقيق "التحميل يستمر حتى خارج التطبيق": خدمة Foreground حقيقية (وليست Thread عادي داخل
 * نشاط، الذي يُقتل فوراً تقريباً مع تدمير النشاط) تبقى تعمل بمعرفة نظام أندرويد نفسه طالما
 * إشعارها الدائم ظاهر. فقط "إيقاف قسري" صريح من إعدادات النظام لأي تطبيق يوقفها — وهذا قيد
 * أندرويد نفسه على كل التطبيقات ولا يمكن لأي تطبيق تجاوزه.
 *
 * UpdateManager (حوار التقدّم أثناء بقاء التطبيق مفتوحاً) لا يُنزّل شيئاً بنفسه بعد الآن —
 * فقط يبدأ هذه الخدمة ثم يستمع لتقدّمها اللحظي عبر UpdateProgressBus، فتبقى هذه الخدمة
 * "مصدر الحقيقة" الوحيد بلا أي احتمال لازدواج تنزيل أو تعارض حالة بين مسارين مختلفين.
 *
 * وضعا التحديث المدعومان (راجع UpdateManager للتفاصيل الكاملة عن متى يُختار كل منهما):
 *  MODE_FULL  — يُنزّل ملف APK كاملاً ويتحقق منه عبر SHA-256 (نفس آلية UpdateManager الأصلية
 *               تماماً، منقولة هنا بلا تغيير بالمنطق).
 *  MODE_SMART — يقارن كل ملف بقائمة assets_manifest (من version.json) بمحتواه الحالي فعلياً
 *               (نسخة مُحمَّلة سابقاً إن وُجدت، وإلا الأصل المرفق بحزمة التطبيق نفسها)، وينزّل
 *               فقط الملفات التي تغيّر هاشها SHA-256 فعلاً، فيبقى حجم التنزيل أصغر بكثير عندما
 *               يقتصر التغيير على واجهة الويب (index.html) دون أي تعديل بكود Java/Kotlin نفسه
 *               (الذي لا يمكن تحديثه إطلاقاً إلا عبر تثبيت APK كامل — قيد تقني لا يمكن تجاوزه).
 */
public class UpdateDownloadService extends Service {

    static final String EXTRA_MODE = "mode";
    static final String MODE_FULL = "full";
    static final String MODE_SMART = "smart";

    static final String EXTRA_DOWNLOAD_URL = "download_url";
    static final String EXTRA_SHA256 = "sha256";
    static final String EXTRA_VERSION_CODE = "version_code";
    static final String EXTRA_VERSION_NAME = "version_name";
    static final String EXTRA_MANIFEST_JSON = "manifest_json";

    static final String EXTRA_TRIGGER_INSTALL = "trigger_install";

    private static final String CHANNEL_ID = "yg_updates";
    private static final int NOTIF_ID = 8801;

    private static final String UPDATE_FILE_NAME = "update.apk";
    private static final String UPDATE_TEMP_FILE_NAME = "update.apk.part";

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    private volatile boolean cancelled = false;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildProgressNotification("جاري التحضير...", 0, 0, true));

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final String mode = intent.getStringExtra(EXTRA_MODE);
        cancelled = false;
        UpdateProgressBus.resetForNewDownload();

        new Thread(() -> {
            try {
                if (MODE_SMART.equals(mode)) {
                    runSmartUpdate(intent);
                } else {
                    runFullUpdate(intent);
                }
            } catch (Exception e) {
                finishWithError("تعذر إكمال التحديث. حاول مرة أخرى.");
            } finally {
                stopForeground(false);
                stopSelf();
            }
        }, "yg-update-download").start();

        // لا إعادة تشغيل تلقائية بمعرفة النظام لو قُتلت الخدمة قسراً: بيانات الطلب (الروابط/
        // الهاش) لن تكون متوفرة لإعادة تشغيل تلقائي بلا Intent، والمستخدم يعيد المحاولة بسهولة
        // من داخل التطبيق بدل محاولة صامتة قد تفشل دون أي رسالة واضحة.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        cancelled = true;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== قناة الإشعارات ====================

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "تحديثات التطبيق", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("تقدّم تنزيل تحديثات التطبيق");
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
    }

    // ==================== بناء الإشعارات ====================

    private PendingIntent buildOpenAppPendingIntent(boolean triggerInstall) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (triggerInstall) intent.putExtra(EXTRA_TRIGGER_INSTALL, true);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(this, triggerInstall ? 2 : 1, intent, flags);
    }

    private Notification buildProgressNotification(String status, long downloaded, long total, boolean indeterminate) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(this, R.color.notification_color))
                .setContentTitle("جاري تحديث يلا گول")
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(buildOpenAppPendingIntent(false))
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (indeterminate || total <= 0) {
            builder.setProgress(0, 0, true);
            builder.setContentText(status);
        } else {
            int percent = (int) Math.min(100, (downloaded * 100L) / total);
            builder.setProgress(100, percent, false);
            builder.setContentText(status + " (" + percent + "%)");
        }
        return builder.build();
    }

    private Notification buildCompletionNotification(boolean success, String message, boolean readyToInstall) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(this, R.color.notification_color))
                .setContentTitle(success ? "التحديث جاهز" : "تعذر إكمال التحديث")
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(buildOpenAppPendingIntent(readyToInstall));

        if (readyToInstall) {
            builder.addAction(R.drawable.ic_notification, "تثبيت الآن", buildOpenAppPendingIntent(true));
        }
        return builder.build();
    }

    private void updateNotification(Notification n) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, n);
        } catch (SecurityException ignored) {
            // صلاحية الإشعارات غير ممنوحة — التنزيل يكمل عادياً بصمت، فقط لن يظهر إشعار للمستخدم
        }
    }

    private void publishProgress(String status, long downloaded, long total) {
        UpdateProgressBus.publishProgress(downloaded, total, status);
        updateNotification(buildProgressNotification(status, downloaded, total, total <= 0));
    }

    private void finishSuccess(String message, boolean readyToInstall) {
        UpdateProgressBus.publishCompleted(true, message, readyToInstall);
        updateNotification(buildCompletionNotification(true, message, readyToInstall));
    }

    private void finishWithError(String message) {
        UpdateProgressBus.publishCompleted(false, message, false);
        updateNotification(buildCompletionNotification(false, message, false));
    }

    // ==================== وضع: تحديث كامل (APK) ====================

    private void runFullUpdate(Intent intent) {
        String downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL);
        String sha256 = intent.getStringExtra(EXTRA_SHA256);
        int versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0);
        String versionName = intent.getStringExtra(EXTRA_VERSION_NAME);
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            finishWithError("رابط التحديث غير صالح.");
            return;
        }

        File tempFile = getApkTempFile();
        File destFile = getApkDestFile();

        // مسار سريع: ملف مُنزَّل ومُتحقَّق مسبقاً من محاولة سابقة (مثلاً المستخدم رجع من إعدادات
        // "السماح من هذا المصدر" بدل الشبكة) — لا داعي لإعادة تنزيله من جديد.
        if (destFile.exists() && sha256 != null && !sha256.trim().isEmpty()
                && verifyFileHash(destFile, sha256)) {
            markApkReadyToInstall(versionCode, sha256);
            finishSuccess("التحديث" + versionSuffix(versionName) + " جاهز للتثبيت. اضغط للتثبيت الآن.", true);
            return;
        }

        if (tempFile.exists()) deleteQuietly(tempFile);

        String cacheBustedUrl = downloadUrl + (downloadUrl.contains("?") ? "&" : "?") + "cb=" + System.currentTimeMillis();
        Request request = new Request.Builder().url(cacheBustedUrl).build();

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            finishWithError("تعذر التحقق من سلامة الملف على هذا الجهاز.");
            return;
        }

        long downloadedBytes = 0L;
        long totalBytes = -1L;

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
            totalBytes = body.contentLength();
            final long fTotal = totalBytes;

            try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int read;
                long lastUiUpdate = 0L;
                while ((read = in.read(buffer)) != -1) {
                    if (cancelled) {
                        deleteQuietly(tempFile);
                        finishWithError("تم إلغاء التنزيل.");
                        return;
                    }
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    downloadedBytes += read;

                    long now = System.currentTimeMillis();
                    boolean complete = fTotal > 0 && downloadedBytes >= fTotal;
                    if (now - lastUiUpdate > 400 || complete) {
                        lastUiUpdate = now;
                        publishProgress("جاري تحميل التحديث...", downloadedBytes, fTotal);
                    }
                }
                out.flush();
            }
        } catch (IOException e) {
            finishWithError(classifyIoError(e));
            return;
        }

        if (downloadedBytes <= 0 || tempFile.length() <= 0) {
            deleteQuietly(tempFile);
            finishWithError("فشل تنزيل ملف التحديث (ملف فارغ). حاول مرة أخرى.");
            return;
        }
        if (totalBytes > 0 && tempFile.length() != totalBytes) {
            deleteQuietly(tempFile);
            finishWithError("الملف المُنزَّل غير مكتمل. حاول مرة أخرى.");
            return;
        }

        if (sha256 != null && !sha256.trim().isEmpty()) {
            String actualHash = bytesToHex(digest.digest());
            if (!actualHash.equalsIgnoreCase(sha256.trim())) {
                deleteQuietly(tempFile);
                finishWithError("فشل التحقق من سلامة ملف التحديث. تم حذف الملف التالف تلقائياً.");
                return;
            }
        }

        int localVersionCode = getLocalVersionCode();
        if (versionCode <= localVersionCode) {
            deleteQuietly(tempFile);
            finishWithError("تم إلغاء التثبيت: الإصدار المُنزَّل ليس أحدث من الإصدار الحالي فعلياً.");
            return;
        }

        deleteQuietly(destFile);
        boolean moved = tempFile.renameTo(destFile);
        if (!moved) {
            deleteQuietly(tempFile);
            finishWithError("تعذر حفظ ملف التحديث على الجهاز. تحقق من مساحة التخزين المتاحة.");
            return;
        }

        markApkReadyToInstall(versionCode, sha256);
        finishSuccess("التحديث" + versionSuffix(versionName) + " جاهز للتثبيت. اضغط للتثبيت الآن.", true);
    }

    // ==================== وضع: تحديث ذكي (ملفات الويب فقط) ====================

    private void runSmartUpdate(Intent intent) {
        int versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0);
        String manifestJson = intent.getStringExtra(EXTRA_MANIFEST_JSON);
        if (manifestJson == null || manifestJson.trim().isEmpty()) {
            finishWithError("لا تتوفر بيانات التحديث الذكي لهذا الإصدار.");
            return;
        }

        List<AssetEntry> entries;
        try {
            entries = parseManifest(manifestJson);
        } catch (Exception e) {
            finishWithError("تعذرت قراءة بيانات التحديث الذكي.");
            return;
        }
        if (entries.isEmpty()) {
            markSmartUpdateApplied(versionCode);
            finishSuccess("محتوى التطبيق محدَّث بالفعل.", false);
            return;
        }

        List<AssetEntry> needed = new ArrayList<>();
        for (AssetEntry entry : entries) {
            String currentHash = computeCurrentAssetHash(entry.path);
            if (currentHash == null || !currentHash.equalsIgnoreCase(entry.sha256)) {
                needed.add(entry);
            }
        }

        if (needed.isEmpty()) {
            markSmartUpdateApplied(versionCode);
            finishSuccess("محتوى التطبيق محدَّث بالفعل.", false);
            return;
        }

        long totalBytes = 0L;
        for (AssetEntry e : needed) totalBytes += Math.max(0, e.size);

        File tmpDir = getWebOverrideTmpDir();
        deleteRecursively(tmpDir);
        if (!tmpDir.exists()) tmpDir.mkdirs();

        long downloadedSoFar = 0L;
        final long fTotalBytes = totalBytes;

        for (AssetEntry entry : needed) {
            if (cancelled) {
                deleteRecursively(tmpDir);
                finishWithError("تم إلغاء التنزيل.");
                return;
            }

            File outFile = resolveSafeChildFile(tmpDir, entry.path);
            if (outFile == null) {
                continue; // مسار غير آمن (لن يحدث فعلياً من مصدر موثوق، لكن نتجاهله دفاعياً بدل الفشل الكامل
            }
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            Request request = new Request.Builder().url(entry.url).build();

            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                finishWithError("تعذر التحقق من سلامة الملفات على هذا الجهاز.");
                return;
            }

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    deleteRecursively(tmpDir);
                    finishWithError("فشل تنزيل ملف (" + entry.path + "). حاول مرة أخرى، أو بدّل لوضع التحديث الكامل.");
                    return;
                }
                ResponseBody body = response.body();
                if (body == null) {
                    deleteRecursively(tmpDir);
                    finishWithError("استجابة فارغة أثناء تنزيل (" + entry.path + ").");
                    return;
                }

                try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    long lastUiUpdate = 0L;
                    while ((read = in.read(buffer)) != -1) {
                        if (cancelled) {
                            deleteRecursively(tmpDir);
                            finishWithError("تم إلغاء التنزيل.");
                            return;
                        }
                        out.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        downloadedSoFar += read;

                        long now = System.currentTimeMillis();
                        if (now - lastUiUpdate > 400) {
                            lastUiUpdate = now;
                            publishProgress("جاري تحميل تحديث ذكي...", downloadedSoFar, fTotalBytes);
                        }
                    }
                    out.flush();
                }
            } catch (IOException e) {
                deleteRecursively(tmpDir);
                finishWithError(classifyIoError(e));
                return;
            }

            String actualHash = bytesToHex(digest.digest());
            if (!actualHash.equalsIgnoreCase(entry.sha256)) {
                deleteRecursively(tmpDir);
                finishWithError("فشل التحقق من سلامة ملف (" + entry.path + "). حاول مرة أخرى.");
                return;
            }
        }

        // كل الملفات نزلت وتحقّقت بنجاح — الآن فقط نستبدل النسخة الفعّالة دفعة واحدة، فلا تبقى
        // أبداً حالة وسيطة ناقصة (بعض الملفات جديدة وبعضها قديمة) يمكن أن تُخدَّم لـ WebView.
        File activeDir = getWebOverrideDir();
        deleteRecursively(activeDir);
        boolean moved = tmpDir.renameTo(activeDir);
        if (!moved) {
            deleteRecursively(tmpDir);
            finishWithError("تعذر حفظ ملفات التحديث على الجهاز.");
            return;
        }

        markSmartUpdateApplied(versionCode);
        finishSuccess("تم تحديث محتوى التطبيق بنجاح.", false);
    }

    private String computeCurrentAssetHash(String path) {
        // أولاً: نسخة override محلية من تحديث ذكي سابق (إن وُجدت) هي المصدر الفعّال حالياً.
        File overrideFile = resolveSafeChildFile(getWebOverrideDir(), path);
        if (overrideFile != null && overrideFile.exists()) {
            return computeFileSha256Quietly(overrideFile);
        }
        // وإلا: الأصل المرفق داخل حزمة التطبيق نفسها (assets/).
        try (InputStream in = getAssets().open(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            return bytesToHex(digest.digest());
        } catch (Exception e) {
            return null; // غير موجود أصلاً بالحزمة (ملف جديد كلياً يُضيفه هذا التحديث) — اختلاف حقيقي يستوجب التنزيل
        }
    }

    /** يحل مساراً فرعياً آمناً داخل مجلد أساس معيّن، متجاهلاً أي محاولة صعود (..) بأمان تام. */
    private File resolveSafeChildFile(File baseDir, String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return null;
        String cleaned = relativePath.replace('\\', '/');
        if (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        String[] segments = cleaned.split("/");
        File current = baseDir;
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) continue;
            current = new File(current, segment);
        }
        try {
            String basePath = baseDir.getCanonicalPath();
            String resolvedPath = current.getCanonicalPath();
            if (!resolvedPath.equals(basePath) && !resolvedPath.startsWith(basePath + File.separator)) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return current;
    }

    private List<AssetEntry> parseManifest(String json) throws Exception {
        List<AssetEntry> result = new ArrayList<>();
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            String path = obj.optString("path", "");
            String url = obj.optString("url", "");
            String sha256 = obj.optString("sha256", "");
            long size = obj.optLong("size", -1);
            if (!path.isEmpty() && !url.isEmpty() && !sha256.isEmpty()) {
                result.add(new AssetEntry(path, url, sha256, size));
            }
        }
        return result;
    }

    private static final class AssetEntry {
        final String path;
        final String url;
        final String sha256;
        final long size;

        AssetEntry(String path, String url, String sha256, long size) {
            this.path = path;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    // ==================== مجلدات وملفات ====================

    File getWebOverrideDir() {
        return new File(getFilesDir(), "web_override");
    }

    private File getWebOverrideTmpDir() {
        return new File(getFilesDir(), "web_override_tmp");
    }

    private File getApkDestFile() {
        return new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), UPDATE_FILE_NAME);
    }

    private File getApkTempFile() {
        return new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), UPDATE_TEMP_FILE_NAME);
    }

    // ==================== علامات SharedPreferences ====================

    private void markApkReadyToInstall(int versionCode, String sha256) {
        getSharedPreferences(UpdateManager.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(UpdateManager.PREF_READY_APK_VERSION_CODE, versionCode)
                .putString(UpdateManager.PREF_READY_APK_SHA256, sha256 != null ? sha256 : "")
                .apply();
    }

    private void markSmartUpdateApplied(int versionCode) {
        getSharedPreferences(UpdateManager.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(UpdateManager.PREF_SMART_VERSION_CODE, versionCode)
                .apply();
    }

    // ==================== أدوات مساعدة ====================

    private int getLocalVersionCode() {
        try {
            android.content.pm.PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pInfo.getLongVersionCode();
            }
            return pInfo.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean verifyFileHash(File file, String expectedSha256) {
        String actual = computeFileSha256Quietly(file);
        return actual != null && expectedSha256 != null && actual.equalsIgnoreCase(expectedSha256.trim());
    }

    private String computeFileSha256Quietly(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            return bytesToHex(digest.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteQuietly(File file) {
        try {
            if (file != null && file.exists()) file.delete();
        } catch (Exception ignored) {
        }
    }

    private void deleteRecursively(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) deleteRecursively(child);
                else deleteQuietly(child);
            }
        }
        deleteQuietly(dir);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private String classifyIoError(IOException e) {
        if (e instanceof UnknownHostException) {
            return "تعذر الوصول للسيرفر. تحقق من اتصال الإنترنت.";
        }
        if (e instanceof SocketTimeoutException) {
            return "انتهت مهلة الاتصال (اتصال ضعيف أو متقطع). حاول مرة أخرى.";
        }
        String msg = e.getMessage();
        return "تعذر إكمال التحديث" + (msg != null && !msg.isEmpty() ? " (" + msg + ")" : "") + ". حاول مرة أخرى.";
    }

    private String versionSuffix(String versionName) {
        return (versionName != null && !versionName.isEmpty()) ? " (" + versionName + ")" : "";
    }
}
