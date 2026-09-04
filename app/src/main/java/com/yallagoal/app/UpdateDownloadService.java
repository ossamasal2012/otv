package com.yallagoal.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * خدمة Foreground واحدة هي المُنفِّذ الفعلي الوحيد لتنزيل تحديثات APK — سواء كان التطبيق
 * بالمقدمة أو الخلفية أو مغلقاً تماماً من قائمة التطبيقات الحديثة. هذا هو جوهر تحقيق "التحميل
 * يستمر حتى خارج التطبيق": خدمة Foreground حقيقية (وليست Thread عادي داخل نشاط، الذي يُقتل
 * فوراً تقريباً مع تدمير النشاط) تبقى تعمل بمعرفة نظام أندرويد نفسه طالما إشعارها الدائم ظاهر.
 * فقط "إيقاف قسري" صريح من إعدادات النظام لأي تطبيق يوقفها — وهذا قيد أندرويد نفسه على كل
 * التطبيقات ولا يمكن لأي تطبيق تجاوزه.
 *
 * UpdateManager (حوار التقدّم أثناء بقاء التطبيق مفتوحاً) لا يُنزّل شيئاً بنفسه — فقط يبدأ هذه
 * الخدمة ثم يستمع لتقدّمها اللحظي عبر UpdateProgressBus، فتبقى هذه الخدمة "مصدر الحقيقة"
 * الوحيد بلا أي احتمال لازدواج تنزيل أو تعارض حالة بين مسارين مختلفين.
 *
 * يُنزَّل ملف APK كاملاً دائماً ويُتحقَّق منه عبر SHA-256 قبل السماح بتثبيته — لا يوجد أي مسار
 * "تحديث ذكي"/محتوى فقط بأي شكل (أُزيل بالكامل عمداً، راجع UpdateManager للتفاصيل).
 */
public class UpdateDownloadService extends Service {

    static final String EXTRA_DOWNLOAD_URL = "download_url";
    static final String EXTRA_SHA256 = "sha256";
    static final String EXTRA_VERSION_CODE = "version_code";
    static final String EXTRA_VERSION_NAME = "version_name";

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

    // ==================== حماية من التزامن (Concurrent Update Protection) ====================
    // حارس ثابت (على مستوى العملية كاملة، وليس على مستوى نسخة UpdateManager التي تُعاد إنشاؤها
    // بكل onCreate بـMainActivity) يمنع تشغيل عمليتي تنزيل بنفس الوقت. بدون هذا، لو أُعيد إنشاء
    // MainActivity (مثلاً: النظام أعاد تشغيل العملية بالخلفية ثم عاد المستخدم) بينما تنزيل سابق
    // ما زال يعمل بالخلفية عبر هذه الخدمة، قد يُستدعى startForegroundService مرة ثانية فيُشغِّل
    // Thread ثانٍ يكتب لنفس ملف update.apk.part بالتوازي مع الأول — تلف ملف محقق.
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildProgressNotification("جاري التحضير...", 0, 0, true));

        if (intent == null) {
            // لا نوقف الخدمة هنا لو كان هناك تنزيل حقيقي يعمل بالفعل (RUNNING=true) — هذا
            // الاستدعاء بلا Intent صالح لا علاقة له بذلك التنزيل ويجب ألا يقطعه.
            if (!RUNNING.get()) stopSelf();
            return START_NOT_STICKY;
        }

        if (!RUNNING.compareAndSet(false, true)) {
            // ==================== حماية من التزامن ====================
            // هناك بالفعل تنزيل يعمل حالياً بمعرفة هذه الخدمة — نتجاهل هذا الطلب المكرر بالكامل:
            // لا Thread ثانٍ، ولا إعادة ضبط لـUpdateProgressBus (كي لا نفقد تقدّم العملية الأصلية
            // الحقيقية)، ولا أي استدعاء لـstopSelf/stopForeground (كي لا نقطع العملية الأصلية
            // بالخطأ). التقدّم الحقيقي يستمر ويُبلَّغ عادي عبر UpdateProgressBus، وواجهة المستخدم
            // (لو كانت مفتوحة) تبقى متزامنة معه بلا أي أثر لهذا الطلب الزائد.
            return START_NOT_STICKY;
        }

        cancelled = false;
        UpdateProgressBus.resetForNewDownload();

        new Thread(() -> {
            try {
                runFullUpdate(intent);
            } catch (Exception e) {
                finishWithError("تعذر إكمال التحديث. حاول مرة أخرى.");
            } finally {
                RUNNING.set(false);
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
        // إعادة ضبط دفاعية: لو انتهت الخدمة بأي مسار غير متوقَّع دون أن يصل Thread التنزيل إلى
        // كتلة finally الخاصة به (نادر)، لا نريد أن يبقى الحارس RUNNING=true للأبد ويمنع أي
        // محاولة تحديث لاحقة بمعزل تام عن هذه الخدمة تحديداً.
        RUNNING.set(false);
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

    // ==================== تحديث APK كامل ====================

    private void runFullUpdate(Intent intent) {
        String downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL);
        String sha256 = intent.getStringExtra(EXTRA_SHA256);
        int versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0);
        String versionName = intent.getStringExtra(EXTRA_VERSION_NAME);
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            finishWithError("رابط التحديث غير صالح.");
            return;
        }
        // أمان: نرفض رابط تحميل APK إن لم يكن HTTPS، بغض النظر عمّا يعلنه السيرفر. هذا يخص قناة
        // التحديث فقط ولا يمسّ android:usesCleartextTraffic العام للتطبيق (يبقى يعمل بشكل طبيعي
        // لمصادر IPTV/HLS الحالية التي قد تحتاج HTTP).
        if (!downloadUrl.startsWith("https://")) {
            finishWithError("تم رفض رابط التحديث لأسباب أمنية (يجب أن يكون HTTPS).");
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
            deleteQuietly(tempFile);
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

    // ==================== مجلدات وملفات ====================

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
