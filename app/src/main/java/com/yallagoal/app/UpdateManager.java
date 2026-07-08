package com.yallagoal.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateManager {

    // ⚠️ مهم جداً: غيّر السطر التالي إلى "اسم_المستخدم/اسم_المستودع" الخاص بك بالضبط
    // مثال: إذا رابط مستودعك هو github.com/ossamasal2012/yalla-goal-app
    // فالقيمة الصحيحة هي "ossamasal2012/yalla-goal-app"
    private static final String GITHUB_REPO = "ossamasal2012/YOUR_REPO_NAME";

    private static final String VERSION_URL =
            "https://github.com/" + GITHUB_REPO + "/releases/latest/download/version.json";

    private Activity activity;
    private long downloadId = -1;

    public void checkForUpdate(Activity activity) {
        this.activity = activity;

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
                boolean force = json.optBoolean("force_update", true);

                int localVersionCode = getLocalVersionCode();

                if (remoteVersionCode > localVersionCode) {
                    showDialogOnUiThread(remoteVersionName, changelog, downloadUrl, force);
                }
            } catch (Exception e) {
                // فشل صامت: لا إنترنت، أو المستودع غير عام بعد، أو ما فيه إصدار منشور بعد
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

    private void showDialogOnUiThread(String versionName, String changelog, String downloadUrl, boolean force) {
        if (activity.isFinishing()) return;
        activity.runOnUiThread(() -> showUpdateDialog(versionName, changelog, downloadUrl, force));
    }

    private void showUpdateDialog(String versionName, String changelog, String downloadUrl, boolean force) {
        String message = (changelog != null && !changelog.isEmpty())
                ? changelog
                : "يتوفر إصدار جديد من التطبيق" + (versionName.isEmpty() ? "" : " (" + versionName + ")") + ".";

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("تحديث متوفر")
                .setMessage(message)
                .setCancelable(!force)
                .setPositiveButton("تحديث الآن", (dialog, which) -> startDownload(downloadUrl));

        if (!force) {
            builder.setNegativeButton("لاحقاً", (dialog, which) -> dialog.dismiss());
        }

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);

        if (force) {
            // يمنع إغلاق الرسالة بزر الرجوع طالما التحديث إلزامي
            dialog.setOnKeyListener((d, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK);
        }

        dialog.show();
    }

    private void startDownload(String downloadUrl) {
        Toast.makeText(activity, "جاري تحميل التحديث...", Toast.LENGTH_SHORT).show();

        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setTitle("تحديث يلا گول");
        request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "update.apk");
        request.setMimeType("application/vnd.android.package-archive");

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    installApk();
                    try {
                        ctx.unregisterReceiver(this);
                    } catch (Exception ignored) {
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_EXPORTED);

        downloadId = dm.enqueue(request);
    }

    private void installApk() {
        File file = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk");
        if (!file.exists()) return;

        Uri apkUri = FileProvider.getUriForFile(
                activity, activity.getPackageName() + ".fileprovider", file);

        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(installIntent);
    }
}
