package com.yallagoal.app;

import android.content.Context;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ذاكرة تخزين مؤقت للصور (شعارات القنوات/بوسترات الأفلام والمسلسلات) تعمل على مستوى Android
 * الأصلي عبر WebViewClient.shouldInterceptRequest بدل JavaScript — وهذا مقصود تماماً:
 *
 * الصفحة مُحمَّلة من file:///android_asset/index.html، وأي محاولة لجلب بايتات الصور عبر
 * fetch()/XHR من JavaScript (لتخزينها بـ IndexedDB مثلاً) تصطدم بقيود CORS لأن سيرفرات
 * Xtream لا ترسل ترويسات Access-Control-Allow-Origin عادة (وليست مصممة لذلك أصلاً). أما
 * الاعتراض هنا فيحدث على مستوى الشبكة الأصلي قبل وصول الطلب لمحرك الويب، فلا علاقة له
 * بقيود CORS إطلاقاً — تماماً كما تعمل بقية علامات <img> اليوم دون أي مشكلة، بدون أي
 * تعديل على HTML/JS نفسه.
 *
 * النتيجة العملية: أول ظهور لأي صورة يُخزَّن خامها على القرص (Cache Dir القابل للتنظيف
 * التلقائي من النظام عند الحاجة لمساحة)، وأي ظهور لاحق لنفس الصورة — بالتمرير للخلف،
 * بالرجوع لقسم سبق فتحه، أو حتى بعد إغلاق التطبيق وإعادة فتحه لاحقاً — يُقرأ من القرص
 * مباشرة بدون أي اتصال شبكي، فيظهر شبه فوري. حتى أول ظهور (Cache Miss) يستفيد من تجميع
 * اتصالات OkHttp لنفس مضيف سيرفر Xtream، فتتسارع الصور المتتالية بنفس الجلسة أيضاً.
 *
 * أي عطل في أي خطوة هنا (قراءة/كتابة قرص، اتصال شبكي) يُعاد منه بأمان لسلوك WebView
 * الافتراضي (إرجاع null من intercept) بدل كسر تحميل الصورة إطلاقاً.
 */
final class ImageCacheInterceptor {

    private static final String CACHE_DIR_NAME = "yg_image_cache";
    private static final long MAX_CACHE_BYTES = 200L * 1024 * 1024; // 200 ميجابايت كحد أقصى
    private static final long TRIM_TARGET_BYTES = 150L * 1024 * 1024; // نقلّص حتى هذا الحد عند التجاوز

    private final File cacheDir;
    private final OkHttpClient httpClient;
    private final AtomicBoolean trimInProgress = new AtomicBoolean(false);

    ImageCacheInterceptor(Context context) {
        cacheDir = new File(context.getCacheDir(), CACHE_DIR_NAME);
        if (!cacheDir.exists()) {
            boolean ignored = cacheDir.mkdirs();
        }
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    /**
     * true فقط لطلبات GET الفرعية (وليست الإطار الرئيسي) التي يُرجَّح جداً أنها صور —
     * إما بامتداد رابط معروف أو بترويسة Accept التي يضعها WebView تلقائياً لعناصر <img>.
     * أي طلب آخر (نداءات Xtream API، واجهة العرض نفسها، إلخ) لا يُلمس إطلاقاً ويمر بسلوكه
     * الطبيعي دون أي تدخل.
     */
    boolean shouldHandle(WebResourceRequest request) {
        if (request.isForMainFrame()) return false;

        String method = request.getMethod();
        if (method != null && !"GET".equalsIgnoreCase(method)) return false;

        String scheme = request.getUrl().getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return false;
        }

        String url = request.getUrl().toString();
        if (looksLikeImageUrl(url)) return true;

        Map<String, String> headers = request.getRequestHeaders();
        String accept = headers != null ? headers.get("Accept") : null;
        return accept != null && accept.contains("image/");
    }

    private boolean looksLikeImageUrl(String url) {
        String lower = url.toLowerCase(Locale.US);
        int q = lower.indexOf('?');
        String path = q >= 0 ? lower.substring(0, q) : lower;
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png")
                || path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".bmp");
    }

    /**
     * يُستدعى من Thread الخاص بـ WebView (وليس UI Thread حسب توثيق shouldInterceptRequest
     * نفسه) — آمن تماماً لعمل قراءة/كتابة قرص أو اتصال شبكي حاجب هنا مباشرة.
     */
    WebResourceResponse intercept(WebResourceRequest request) {
        String url = request.getUrl().toString();
        String key = hashKey(url);
        File bytesFile = new File(cacheDir, key + ".bin");
        File mimeFile = new File(cacheDir, key + ".mime");

        try {
            if (bytesFile.exists() && mimeFile.exists() && bytesFile.length() > 0) {
                String mime = readSmallFile(mimeFile);
                boolean ignored = bytesFile.setLastModified(System.currentTimeMillis()); // ترتيب LRU بسيط
                return new WebResourceResponse(mime, "", new FileInputStream(bytesFile));
            }
        } catch (Exception e) {
            // أي عطل بقراءة الكاش لا يمنع تحميل الصورة إطلاقاً — نكمل لمسار الجلب والتخزين العادي
        }

        return fetchAndCache(url, bytesFile, mimeFile);
    }

    private WebResourceResponse fetchAndCache(String url, File bytesFile, File mimeFile) {
        try {
            Request req = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(req).execute()) {
                if (!response.isSuccessful()) return null; // نترك WebView يتولى الخطأ بسلوكه المعتاد
                ResponseBody body = response.body();
                if (body == null) return null;

                String mime = response.header("Content-Type", "image/*");
                if (mime != null && mime.indexOf(';') >= 0) mime = mime.substring(0, mime.indexOf(';')).trim();
                if (mime == null || mime.isEmpty() || !mime.startsWith("image/")) mime = "image/*";

                byte[] bytes = body.bytes();
                if (bytes.length == 0) return null;

                writeCacheFilesQuietly(bytesFile, mimeFile, bytes, mime);
                maybeTrimCacheAsync();

                return new WebResourceResponse(mime, "", new ByteArrayInputStream(bytes));
            }
        } catch (Exception e) {
            // أي فشل شبكي هنا: نُرجع null فيتولى WebView التحميل العادي — تماماً كسلوك ما قبل
            // هذا التعديل، فلا يمكن لهذه الطبقة أن "تُسوّئ" أي شيء كان يعمل أصلاً.
            return null;
        }
    }

    private void writeCacheFilesQuietly(File bytesFile, File mimeFile, byte[] bytes, String mime) {
        try {
            File tmp = new File(bytesFile.getParentFile(), bytesFile.getName() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            boolean moved = tmp.renameTo(bytesFile);
            if (!moved) {
                boolean ignored = tmp.delete();
                return;
            }
            writeSmallFile(mimeFile, mime);
        } catch (Exception ignored) {
            // فشل الكتابة بالكاش ليس خطأً حرجاً — الصورة نفسها أُرجعت للمستخدم بنجاح أصلاً بالأعلى
        }
    }

    private void maybeTrimCacheAsync() {
        if (!trimInProgress.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    trimCacheIfNeeded();
                } finally {
                    trimInProgress.set(false);
                }
            }
        }).start();
    }

    private void trimCacheIfNeeded() {
        File[] files = cacheDir.listFiles();
        if (files == null) return;

        long total = 0;
        List<File> binFiles = new ArrayList<>();
        for (File f : files) {
            total += f.length();
            if (f.getName().endsWith(".bin")) binFiles.add(f);
        }
        if (total <= MAX_CACHE_BYTES) return;

        // الأقدم استخداماً (lastModified) أولاً — مقارنة يدوية بسيطة بدل Comparator.comparingLong
        // (وهي واجهة Java 8 default method) لتبقى متوافقة تماماً مع minSdk 23 بدون الاعتماد
        // على تفعيل Core Library Desugaring غير المُفعَّل حالياً بالمشروع.
        Collections.sort(binFiles, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long diff = a.lastModified() - b.lastModified();
                if (diff < 0) return -1;
                if (diff > 0) return 1;
                return 0;
            }
        });

        for (int i = 0; i < binFiles.size() && total > TRIM_TARGET_BYTES; i++) {
            File bin = binFiles.get(i);
            String name = bin.getName();
            String base = name.substring(0, name.length() - 4); // إزالة ".bin"
            File mime = new File(cacheDir, base + ".mime");
            total -= bin.length();
            boolean ignored1 = bin.delete();
            boolean ignored2 = mime.delete();
        }
    }

    private String readSmallFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "image/*";
        }
    }

    private void writeSmallFile(File f, String content) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String hashKey(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(url.hashCode());
        }
    }
}
