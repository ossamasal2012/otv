package com.yallagoal.app;

/**
 * ناقل حالة بسيط داخل نفس العملية (Process) بين UpdateDownloadService (المُنفِّذ الفعلي
 * الوحيد للتنزيل، سواء كان التطبيق بالمقدمة أو الخلفية) وأي واجهة مستخدم مهتمة بمتابعة
 * التقدّم لحظياً أثناء بقاء التطبيق مفتوحاً (حالياً: حوار تقدّم UpdateManager).
 *
 * لا حاجة هنا لأي آلية IPC (LocalBroadcastManager أو Binder/Messenger): الخدمة والنشاط
 * يعملان دائماً بنفس العملية (لا android:process منفصل بأي منهما)، فحقل ساكن (static) بسيط
 * آمن وكافٍ تماماً. عند تسجيل مستمع جديد (مثلاً المستخدم فتح التطبيق أثناء تنزيل جارٍ
 * بالخلفية) يُزامَن فوراً بآخر حالة معروفة بدل انتظار تحديث تالٍ من الخدمة.
 */
final class UpdateProgressBus {

    interface Listener {
        void onProgress(long downloadedBytes, long totalBytes, String statusText);

        void onCompleted(boolean success, String message, boolean readyToInstall);
    }

    private static volatile Listener listener;

    private static volatile long lastDownloaded = 0L;
    private static volatile long lastTotal = -1L;
    private static volatile String lastStatus = "";

    private static volatile boolean hasCompletedEvent = false;
    private static volatile boolean lastCompletedSuccess = false;
    private static volatile String lastCompletedMessage = "";
    private static volatile boolean lastReadyToInstall = false;

    private UpdateProgressBus() {
    }

    static void setListener(Listener l) {
        listener = l;
        if (l == null) return;
        l.onProgress(lastDownloaded, lastTotal, lastStatus);
        if (hasCompletedEvent) {
            l.onCompleted(lastCompletedSuccess, lastCompletedMessage, lastReadyToInstall);
        }
    }

    /** يُستدعى ببدء تنزيل جديد لمسح أي حالة "اكتمال" عالقة من محاولة سابقة. */
    static void resetForNewDownload() {
        hasCompletedEvent = false;
        lastDownloaded = 0L;
        lastTotal = -1L;
        lastStatus = "";
    }

    static void publishProgress(long downloadedBytes, long totalBytes, String statusText) {
        lastDownloaded = downloadedBytes;
        lastTotal = totalBytes;
        lastStatus = statusText != null ? statusText : "";
        Listener l = listener;
        if (l != null) l.onProgress(downloadedBytes, totalBytes, lastStatus);
    }

    static void publishCompleted(boolean success, String message, boolean readyToInstall) {
        hasCompletedEvent = true;
        lastCompletedSuccess = success;
        lastCompletedMessage = message != null ? message : "";
        lastReadyToInstall = readyToInstall;
        Listener l = listener;
        if (l != null) l.onCompleted(success, lastCompletedMessage, readyToInstall);
    }
}
