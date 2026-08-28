package com.yallagoal.app;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;
import android.util.LruCache;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ذاكرة تخزين مؤقت حقيقية متعددة المستويات للصور (شعارات القنوات/بوسترات الأفلام والمسلسلات)
 * تعمل على مستوى Android الأصلي عبر WebViewClient.shouldInterceptRequest بدل JavaScript —
 * وهذا مقصود تماماً:
 *
 * الصفحة مُحمَّلة من file:///android_asset/index.html، وأي محاولة لجلب بايتات الصور عبر
 * fetch()/XHR من JavaScript (لتخزينها بـ IndexedDB مثلاً) تصطدم بقيود CORS لأن سيرفرات
 * Xtream لا ترسل ترويسات Access-Control-Allow-Origin عادة (وليست مصممة لذلك أصلاً). أما
 * الاعتراض هنا فيحدث على مستوى الشبكة الأصلي قبل وصول الطلب لمحرك الويب، فلا علاقة له
 * بقيود CORS إطلاقاً — تماماً كما تعمل بقية علامات <img> اليوم دون أي مشكلة، بدون أي
 * تعديل على HTML/JS نفسه.
 *
 * المسار الكامل:
 *
 *   WebView → [1] Memory LRU (فوري، بلا I/O) → [2] Disk Cache (streaming، بلا Network) →
 *   [3] Network (streaming حقيقي عبر OkHttp، مع تكرار طلبات مُوحَّد Single-flight) → Disk + Memory
 *
 * كل مسار I/O هنا (قرص أو شبكة) يُبنى على Streaming حقيقي: لا يُقرأ أي محتوى صورة بالكامل إلى
 * byte[] لمجرد تمريره لـ WebView — انظر TeeInputStream بالأسفل. الاستثناء الوحيد هو صورة
 * موجودة فعلاً بذاكرة LRU (وهي أصلاً بايتات جاهزة بالذاكرة، فلا تكلفة I/O إضافية إطلاقاً).
 *
 * أي عطل في أي خطوة هنا (قراءة/كتابة قرص، اتصال شبكي، نوع محتوى غير صالح) يُعاد منه بأمان
 * لسلوك WebView الافتراضي (إرجاع null من intercept) بدل كسر تحميل الصورة إطلاقاً، ولا تُخزَّن
 * أي استجابة فاشلة (404/403/500/مهلة/رابط معطوب/محتوى فارغ أو غير صالح) بالكاش بشكل دائم.
 */
final class ImageCacheInterceptor {

    private static final String TAG = "ImageCache";
    private static final String CACHE_DIR_NAME = "yg_image_cache";

    // Disk cache: حدّ أقصى معقول («عشرات إلى مئات MB») مع تقليص تدريجي (LRU) عند التجاوز —
    // لا يُحذف كل الكاش دفعة واحدة أبداً، فقط أقدم العناصر استخداماً حتى نصل لهدف التقليص.
    private static final long MAX_DISK_BYTES = 200L * 1024 * 1024;
    private static final long TRIM_TARGET_BYTES = 150L * 1024 * 1024;

    // لا نُدرج بذاكرة LRU أي صورة أكبر من هذا الحجم — صور ضخمة استثنائية تبقى بالقرص فقط،
    // فلا تستهلك وحدها حصة كبيرة غير متناسبة من ذاكرة التطبيق المحدودة أصلاً.
    private static final int MAX_MEMORY_ENTRY_BYTES = 3 * 1024 * 1024;

    // أعلى/أدنى حجم منطقي لذاكرة LRU الإجمالية بغضّ النظر عن ذاكرة الجهاز — راجع
    // calculateMemoryCacheBytes(): تُشتق من ثُمن الذاكرة المتاحة للتطبيق ثم تُقيَّد بهذين الحدّين.
    private static final int MIN_MEMORY_CACHE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_MEMORY_CACHE_BYTES = 40 * 1024 * 1024;

    // أقصى مدة ينتظرها طلب مُكرَّر (Follower) نتيجة طلب أصلي (Leader) قيد التنفيذ لنفس الصورة،
    // قبل أن يتراجع بأمان لسلوك WebView الافتراضي بدل الانتظار إلى الأبد.
    private static final long DEDUP_WAIT_TIMEOUT_SECONDS = 20L;

    private final File cacheDir;
    private final OkHttpClient httpClient;
    private final LruCache<String, MemoryEntry> memoryCache;

    // فهرس Disk Cache بالذاكرة (مفتاح ← حجم/آخر استخدام) لتفادي فحص كامل لمجلد الكاش عند كل
    // عملية تقليص (Trim) — يُبنى مرة واحدة كسولاً (Lazy) بأول استخدام فعلي بدل داخل الـ constructor
    // (الذي يُستدعى من Thread الواجهة الرئيسي عند إنشاء النشاط) حتى لا يُحمَّل UI Thread بفحص قرص.
    private final ConcurrentHashMap<String, DiskIndexEntry> diskIndex = new ConcurrentHashMap<>();
    private final AtomicLong currentDiskBytes = new AtomicLong(0L);
    private final Object diskIndexLock = new Object();
    private volatile boolean diskIndexLoaded = false;

    private final AtomicBoolean trimInProgress = new AtomicBoolean(false);

    // منع تحميل نفس الصورة عدة مرات بالتوازي (Single-flight): أول طلب لصورة غير مخزّنة يصبح
    // "القائد" وينفّذ الجلب الفعلي، وأي طلب آخر لنفس الصورة أثناء ذلك ينتظر نتيجته بدل بدء
    // اتصال شبكي جديد خاص به — راجع fetchWithDeduplication().
    private final ConcurrentHashMap<String, InFlight> inFlightRequests = new ConcurrentHashMap<>();

    ImageCacheInterceptor(Context context) {
        cacheDir = new File(context.getCacheDir(), CACHE_DIR_NAME);
        if (!cacheDir.exists()) {
            boolean ignored = cacheDir.mkdirs();
        }

        memoryCache = new LruCache<String, MemoryEntry>(calculateMemoryCacheBytes(context)) {
            @Override
            protected int sizeOf(String key, MemoryEntry value) {
                return value.bytes.length;
            }
        };

        // Client واحد مشترك يُعاد استخدامه لكل الصور طوال عمر النشاط — Connection Pool وKeep-Alive
        // وHTTP/2 (تلقائي عبر ALPN عند دعم السيرفر له) كلها فعّالة هنا بفضل مشاركة نفس الـ Client
        // بدل إنشاء واحد جديد لكل صورة. maxRequestsPerHost أعلى من افتراضي OkHttp (5) لأن غالبية
        // الصور تأتي من مضيف Xtream نفسه، فطلب متزامن أوسع لنفس المضيف يُسرّع القوائم الكبيرة.
        ConnectionPool connectionPool = new ConnectionPool(12, 5, TimeUnit.MINUTES);
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequestsPerHost(10);
        dispatcher.setMaxRequests(24);

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS) // سقف زمني إجمالي: طلب عالق لا يبقى بلا نهاية
                .connectionPool(connectionPool)
                .dispatcher(dispatcher)
                .retryOnConnectionFailure(true)
                .build();
    }

    private static int calculateMemoryCacheBytes(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            int memoryClassMb = am != null ? am.getMemoryClass() : 64;
            int bytes = (memoryClassMb * 1024 * 1024) / 8; // نمط معياري بأندرويد: ثُمن ذاكرة التطبيق المتاحة
            return Math.max(MIN_MEMORY_CACHE_BYTES, Math.min(MAX_MEMORY_CACHE_BYTES, bytes));
        } catch (Exception e) {
            return 12 * 1024 * 1024;
        }
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
                || path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".bmp")
                || path.endsWith(".svg");
    }

    /**
     * يُستدعى من Thread الخاص بـ WebView (وليس UI Thread حسب توثيق shouldInterceptRequest
     * نفسه) — آمن تماماً لعمل قراءة/كتابة قرص أو اتصال شبكي هنا مباشرة. يعود فوراً تقريباً عند
     * نجاح Memory/Disk Hit، ويعود بسرعة عند Cache Miss أيضاً (فور استلام ترويسات الشبكة فقط،
     * وليس بعد اكتمال تنزيل الصورة كاملة) — لا شيء هنا يحجب WebView أكثر من اللازم.
     */
    WebResourceResponse intercept(WebResourceRequest request) {
        try {
            final String url = request.getUrl().toString();
            final String key = hashKey(url);

            MemoryEntry mem = memoryCache.get(key);
            if (mem != null) {
                log("MEMORY HIT", url);
                return buildResponse(mem.mime, new ByteArrayInputStream(mem.bytes), mem.bytes.length);
            }

            ensureDiskIndexLoaded();

            WebResourceResponse diskResponse = tryServeFromDisk(key, url);
            if (diskResponse != null) return diskResponse;

            return fetchWithDeduplication(url, key, request.getRequestHeaders());
        } catch (Exception e) {
            // أي عطل غير متوقع بأي مرحلة هنا لا يجب أن يكسر تحميل الصورة إطلاقاً — نُسقط بأمان
            // لسلوك WebView الافتراضي بدلاً من ذلك.
            return null;
        }
    }

    // ==================== المستوى الثاني: Disk Cache ====================

    private void ensureDiskIndexLoaded() {
        if (diskIndexLoaded) return;
        synchronized (diskIndexLock) {
            if (diskIndexLoaded) return;
            File[] files = cacheDir.listFiles();
            if (files != null) {
                long total = 0L;
                for (File f : files) {
                    String name = f.getName();
                    if (name.contains(".tmp")) {
                        // بقايا كتابة سابقة لم تكتمل (مثلاً إغلاق التطبيق فجأة أثناء تنزيل صورة) — تُحذف بأمان
                        boolean ignored = f.delete();
                        continue;
                    }
                    if (name.endsWith(".bin")) {
                        String key = name.substring(0, name.length() - 4);
                        long size = f.length();
                        diskIndex.put(key, new DiskIndexEntry(size, f.lastModified()));
                        total += size;
                    }
                }
                currentDiskBytes.set(total);
            }
            diskIndexLoaded = true;
        }
    }

    /**
     * يخدم الصورة من القرص عبر Streaming حقيقي (FileInputStream يُمرَّر مباشرة لـ WebView، وليس
     * byte[] كامل بالذاكرة)، ويُرقّيها بالتوازي لذاكرة LRU أثناء قراءة WebView نفسها لها (نفس آلية
     * TeeInputStream المستخدمة لجلب الشبكة بالأسفل) — بلا أي حجب أو قراءة إضافية مضاعفة للملف.
     */
    private WebResourceResponse tryServeFromDisk(final String key, String url) {
        File binFile = new File(cacheDir, key + ".bin");
        if (!binFile.exists()) return null;
        long size = binFile.length();
        if (size <= 0) return null;

        File metaFile = new File(cacheDir, key + ".meta");
        DiskMeta meta = metaFile.exists() ? DiskMeta.readQuietly(metaFile) : null;
        final String mime = meta != null ? meta.mime : "image/*";

        try {
            InputStream fileStream = new FileInputStream(binFile);
            touchDiskEntry(key, binFile);
            log("DISK HIT", url);

            InputStream servedStream = fileStream;
            if (size <= MAX_MEMORY_ENTRY_BYTES) {
                servedStream = new TeeInputStream(fileStream, null, (int) size, new TeeFinishListener() {
                    @Override
                    public void onFinished(boolean diskSuccess, byte[] memoryBytesOrNull) {
                        if (memoryBytesOrNull != null && memoryBytesOrNull.length > 0) {
                            memoryCache.put(key, new MemoryEntry(memoryBytesOrNull, mime));
                        }
                    }
                });
            }
            return buildResponse(mime, servedStream, size);
        } catch (IOException e) {
            return null; // أي عطل بقراءة الكاش لا يمنع تحميل الصورة — نكمل بأمان لمسار الشبكة بالأعلى
        }
    }

    private void touchDiskEntry(String key, File file) {
        DiskIndexEntry entry = diskIndex.get(key);
        if (entry != null) {
            entry.lastAccessMs = System.currentTimeMillis();
        } else {
            registerDiskEntry(key, file.length());
        }
        boolean ignored = file.setLastModified(System.currentTimeMillis()); // ترتيب LRU يعتمد أيضاً على mtime الملف نفسه
    }

    private void registerDiskEntry(String key, long sizeBytes) {
        DiskIndexEntry previous = diskIndex.put(key, new DiskIndexEntry(sizeBytes, System.currentTimeMillis()));
        long delta = sizeBytes - (previous != null ? previous.sizeBytes : 0L);
        currentDiskBytes.addAndGet(delta);
    }

    private void maybeTrimCacheAsync() {
        if (currentDiskBytes.get() <= MAX_DISK_BYTES) return;
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
        }, "yg-image-cache-trim").start();
    }

    private void trimCacheIfNeeded() {
        if (currentDiskBytes.get() <= TRIM_TARGET_BYTES) return;

        List<Map.Entry<String, DiskIndexEntry>> entries = new ArrayList<>(diskIndex.entrySet());
        // الأقدم استخداماً أولاً — مقارنة يدوية بسيطة بدل Comparator.comparingLong (وهي واجهة
        // Java 8 default method على java.util.Comparator نفسه) لتبقى متوافقة تماماً مع منصّة
        // أندرويد المستهدفة هنا (minSdk 23) بدون الاعتماد على تفعيل Core Library Desugaring
        // غير المُفعَّل حالياً بالمشروع.
        Collections.sort(entries, new Comparator<Map.Entry<String, DiskIndexEntry>>() {
            @Override
            public int compare(Map.Entry<String, DiskIndexEntry> a, Map.Entry<String, DiskIndexEntry> b) {
                long diff = a.getValue().lastAccessMs - b.getValue().lastAccessMs;
                if (diff < 0) return -1;
                if (diff > 0) return 1;
                return 0;
            }
        });

        for (Map.Entry<String, DiskIndexEntry> entry : entries) {
            if (currentDiskBytes.get() <= TRIM_TARGET_BYTES) break;
            String key = entry.getKey();
            DiskIndexEntry indexEntry = entry.getValue();
            File bin = new File(cacheDir, key + ".bin");
            File meta = new File(cacheDir, key + ".meta");
            boolean ignored1 = bin.delete();
            boolean ignored2 = meta.delete();
            if (diskIndex.remove(key, indexEntry)) {
                currentDiskBytes.addAndGet(-indexEntry.sizeBytes);
            }
            log("CACHE EVICTION", key);
        }
    }

    // ==================== المستوى الثالث: Network (مع منع التكرار) ====================

    private WebResourceResponse fetchWithDeduplication(String url, String key, Map<String, String> originalHeaders) {
        InFlight created = new InFlight();
        InFlight existing = inFlightRequests.putIfAbsent(key, created);

        if (existing == null) {
            // نحن "القائد" لهذه الصورة — ننفّذ الجلب الفعلي، ونُشعر أي طلبات أخرى وصلت خلفنا
            // لنفس الصورة (عبر completeTicket بالأسفل) بمجرد اكتمال الكتابة بالكاش فعلياً، وليس
            // فور استلام ترويسات الشبكة فقط (وإلا قد يجدها المتابعون غير مكتملة بعد).
            log("NETWORK MISS (leader)", url);
            return fetchAndStream(url, key, created, originalHeaders);
        }

        // لسنا القائد — طلب لنفس الصورة قيد التنفيذ فعلاً؛ ننتظره بدل بدء اتصال شبكي مستقل جديد.
        log("REQUEST DEDUPLICATED (waiting on in-flight request)", url);
        boolean completed;
        try {
            completed = existing.latch.await(DEDUP_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (!completed || !existing.success) return null;

        // القائد انتهى بنجاح — النتيجة الآن بذاكرة أو قرص الكاش، نخدمها بنفس المسار العادي تماماً.
        MemoryEntry mem = memoryCache.get(key);
        if (mem != null) {
            return buildResponse(mem.mime, new ByteArrayInputStream(mem.bytes), mem.bytes.length);
        }
        return tryServeFromDisk(key, url);
    }

    private void completeTicket(String key, InFlight ticket, boolean success) {
        ticket.success = success;
        ticket.latch.countDown();
        inFlightRequests.remove(key, ticket);
    }

    /**
     * يجلب الصورة من الشبكة ويُرجع استجابة Streaming فوراً بمجرد استلام الترويسات (وليس بعد
     * اكتمال التنزيل الكامل) — عبر TeeInputStream الذي يُمرِّر كل جزء يُقرأ لـ WebView مباشرة
     * أثناء كتابته بالتوازي للقرص (وذاكرة LRU إن كان الحجم ضمن الحد). لا يُستخدم
     * response.body().bytes() إطلاقاً في أي مكان هنا.
     */
    private WebResourceResponse fetchAndStream(final String url, final String key, final InFlight ticket,
                                                Map<String, String> originalHeaders) {
        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");

        // نُمرِّر الترويسات التي قد يحتاجها مصدر الصورة فعلياً (توكن مصادقة/Referer/كوكيز خاصة
        // بجلسة المستخدم على سيرفر Xtream) كما وصلت أصلاً من WebView لهذا الطلب بالذات، بدل
        // حذفها بصمت — لكن دون اختراع أي ترويسات إضافية عشوائية لا داعي لها.
        forwardHeaderIfPresent(reqBuilder, originalHeaders, "Referer");
        forwardHeaderIfPresent(reqBuilder, originalHeaders, "Origin");
        forwardHeaderIfPresent(reqBuilder, originalHeaders, "Cookie");
        forwardHeaderIfPresent(reqBuilder, originalHeaders, "Authorization");
        forwardHeaderIfPresent(reqBuilder, originalHeaders, "User-Agent");

        Request req = reqBuilder.build();

        final Response response;
        try {
            response = httpClient.newCall(req).execute();
        } catch (Exception e) {
            log("IMAGE LOAD FAILED (network: " + e.getClass().getSimpleName() + ")", url);
            completeTicket(key, ticket, false);
            return null;
        }

        if (!response.isSuccessful()) {
            log("IMAGE LOAD FAILED (HTTP " + response.code() + ")", url);
            closeQuietly(response);
            completeTicket(key, ticket, false);
            return null;
        }

        ResponseBody body = response.body();
        if (body == null) {
            log("IMAGE LOAD FAILED (empty body)", url);
            closeQuietly(response);
            completeTicket(key, ticket, false);
            return null;
        }

        final String mime = resolveMimeType(response.header("Content-Type"), url);
        if (mime == null) {
            log("IMAGE LOAD FAILED (invalid content-type — likely an error page, not an image)", url);
            closeQuietly(response);
            completeTicket(key, ticket, false);
            return null;
        }

        long contentLength = body.contentLength();
        if (contentLength == 0) {
            log("IMAGE LOAD FAILED (empty response)", url);
            closeQuietly(response);
            completeTicket(key, ticket, false);
            return null;
        }

        String cacheControlHeader = response.header("Cache-Control");
        boolean noStore = cacheControlHeader != null
                && cacheControlHeader.toLowerCase(Locale.US).contains("no-store");

        File tmpFile = null;
        OutputStream diskOut = null;
        if (!noStore) {
            File candidate = new File(cacheDir, key + ".tmp" + System.nanoTime());
            try {
                diskOut = new FileOutputStream(candidate);
                tmpFile = candidate;
            } catch (IOException e) {
                // تعذر الكتابة بالقرص (مساحة ممتلئة مثلاً) — نكمل تمرير الصورة لـ WebView بدون تخزينها بالكاش
                diskOut = null;
                tmpFile = null;
            }
        }

        int memCap = noStore ? 0 : (contentLength > 0 && contentLength <= MAX_MEMORY_ENTRY_BYTES
                ? (int) contentLength
                : MAX_MEMORY_ENTRY_BYTES);

        final File finalTmpFile = tmpFile;
        final DiskMeta meta = DiskMeta.fromResponse(mime, response);
        final Response finalResponse = response;

        TeeInputStream tee = new TeeInputStream(body.byteStream(), diskOut, memCap, new TeeFinishListener() {
            @Override
            public void onFinished(boolean diskSuccess, byte[] memoryBytesOrNull) {
                try {
                    if (diskSuccess && finalTmpFile != null) {
                        finalizeDiskWrite(key, finalTmpFile, meta);
                        log("CACHE WRITE", url);
                    } else if (finalTmpFile != null) {
                        boolean ignored = finalTmpFile.delete();
                    }
                    if (memoryBytesOrNull != null && memoryBytesOrNull.length > 0) {
                        memoryCache.put(key, new MemoryEntry(memoryBytesOrNull, mime));
                    }
                } finally {
                    closeQuietly(finalResponse);
                    completeTicket(key, ticket, true);
                }
            }
        });

        return buildResponse(mime, tee, contentLength);
    }

    /**
     * ينسخ ترويسة واحدة محدَّدة بالاسم من ترويسات الطلب الأصلي (كما وصلت من WebView) لطلب
     * الشبكة الجديد، فقط إن كانت موجودة فعلاً وغير فارغة. يبحث أولاً بالاسم كما هو، ثم بتجاهل
     * حالة الأحرف احتياطاً (بعض المصادر قد تُرجع أسماء الترويسات بحالة أحرف مختلفة قليلاً).
     */
    private void forwardHeaderIfPresent(Request.Builder builder, Map<String, String> headers, String name) {
        if (headers == null) return;
        String value = headers.get(name);
        if (value == null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                    value = entry.getValue();
                    break;
                }
            }
        }
        if (value != null && !value.isEmpty()) {
            builder.header(name, value);
        }
    }

    private void finalizeDiskWrite(String key, File tmpFile, DiskMeta meta) {
        File binFile = new File(cacheDir, key + ".bin");
        File metaFile = new File(cacheDir, key + ".meta");
        try {
            if (binFile.exists()) {
                boolean ignored = binFile.delete();
            }
            boolean moved = tmpFile.renameTo(binFile);
            if (!moved) {
                boolean ignored = tmpFile.delete();
                return;
            }
            meta.writeQuietly(metaFile);
            registerDiskEntry(key, binFile.length());
            maybeTrimCacheAsync();
        } catch (Exception e) {
            boolean ignored = tmpFile.delete();
        }
    }

    // ==================== أدوات مشتركة ====================

    private WebResourceResponse buildResponse(String mime, InputStream stream, long contentLength) {
        Map<String, String> headers = new HashMap<>();
        if (contentLength > 0) {
            headers.put("Content-Length", String.valueOf(contentLength));
        }
        headers.put("Cache-Control", "public, max-age=604800");
        return new WebResourceResponse(mime, null, 200, "OK", headers, stream);
    }

    /**
     * يحدد MIME الفعلي بدل افتراض image/jpeg دائماً: يقرأ Content-Type أولاً (مع تجاهل صفحات
     * الخطأ النصية التي قد يُعيدها مصدر معطوب بترويسة 200 مُضلِّلة)، ثم يستدل من امتداد الرابط
     * عند غياب Content-Type أو عموميته، ثم يثق أخيراً أنها صورة فعلاً (بما أن shouldHandle مرّرت
     * الطلب أصلاً إما بامتداد معروف أو بترويسة Accept التي يضعها WebView لعناصر <img> تحديداً).
     * يُرجع null فقط عند دليل واضح على أن هذا ليس محتوى صورة إطلاقاً (لا نخزّنه ولا نخدمه كصورة).
     */
    private String resolveMimeType(String contentTypeHeader, String url) {
        String mime = contentTypeHeader;
        if (mime != null) {
            int semi = mime.indexOf(';');
            if (semi >= 0) mime = mime.substring(0, semi);
            mime = mime.trim().toLowerCase(Locale.US);
        }

        if (mime != null && (mime.startsWith("text/") || mime.equals("application/json") || mime.contains("xml"))) {
            return null; // على الأغلب صفحة خطأ من المصدر (HTML/JSON/XML)، وليست صورة فعلية
        }

        if (mime != null && mime.startsWith("image/")) {
            return mime;
        }

        String guessed = guessMimeFromUrl(url);
        if (guessed != null) return guessed;

        return "image/*";
    }

    private String guessMimeFromUrl(String url) {
        String lower = url.toLowerCase(Locale.US);
        int q = lower.indexOf('?');
        String path = q >= 0 ? lower.substring(0, q) : lower;
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".gif")) return "image/gif";
        if (path.endsWith(".bmp")) return "image/bmp";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return null;
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

    private static void closeQuietly(Response response) {
        try {
            if (response != null) response.close();
        } catch (Exception ignored) {
        }
    }

    private void log(String tag, String url) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, tag + ": " + url);
        }
    }

    // ==================== أنواع مساعدة داخلية ====================

    private static final class MemoryEntry {
        final byte[] bytes;
        final String mime;

        MemoryEntry(byte[] bytes, String mime) {
            this.bytes = bytes;
            this.mime = mime;
        }
    }

    private static final class DiskIndexEntry {
        volatile long sizeBytes;
        volatile long lastAccessMs;

        DiskIndexEntry(long sizeBytes, long lastAccessMs) {
            this.sizeBytes = sizeBytes;
            this.lastAccessMs = lastAccessMs;
        }
    }

    private static final class InFlight {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean success = false;
    }

    /** بيانات وصفية صغيرة بجانب كل صورة مخزَّنة بالقرص — تنسيق نصّي بسيط بدل JSON لتفادي أي اعتمادية جديدة. */
    private static final class DiskMeta {
        final String mime;
        final String etag;
        final String lastModified;

        DiskMeta(String mime, String etag, String lastModified) {
            this.mime = mime;
            this.etag = etag;
            this.lastModified = lastModified;
        }

        static DiskMeta fromResponse(String mime, Response response) {
            return new DiskMeta(mime, response.header("ETag"), response.header("Last-Modified"));
        }

        void writeQuietly(File file) {
            try (OutputStream out = new FileOutputStream(file)) {
                StringBuilder sb = new StringBuilder();
                sb.append(mime != null ? mime : "image/*").append('\n');
                sb.append(etag != null ? etag : "").append('\n');
                sb.append(lastModified != null ? lastModified : "").append('\n');
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // فشل حفظ البيانات الوصفية ليس خطأً حرجاً — الصورة نفسها محفوظة بالكاش أصلاً بالملف الآخر
            }
        }

        static DiskMeta readQuietly(File file) {
            try (InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[512];
                int n = in.read(buf);
                String content = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
                String[] lines = content.split("\n", -1);
                String mime = (lines.length > 0 && !lines[0].isEmpty()) ? lines[0] : "image/*";
                String etag = lines.length > 1 ? lines[1] : "";
                String lastModified = lines.length > 2 ? lines[2] : "";
                return new DiskMeta(mime, etag.isEmpty() ? null : etag, lastModified.isEmpty() ? null : lastModified);
            } catch (IOException e) {
                return null;
            }
        }
    }

    private interface TeeFinishListener {
        /**
         * يُستدعى مرة واحدة فقط عند إغلاق التدفّق (نجاحاً كان أو فشلاً).
         * diskSuccess: هل كُتبت البيانات كاملة وبنجاح لملف القرص المؤقّت (false إن لم يكن هناك
         * وجهة قرص أصلاً، أو فشلت الكتابة، أو فشلت القراءة من المصدر نفسه).
         * memoryBytesOrNull: البايتات الكاملة الجاهزة لذاكرة LRU إن نجحت القراءة بالكامل ولم
         * يتجاوز الحجم الحد الأقصى المسموح للذاكرة، وإلا null.
         */
        void onFinished(boolean diskSuccess, byte[] memoryBytesOrNull);
    }

    /**
     * تُمرِّر كل بايت يُقرأ من مصدر واحد (شبكة أو قرص) مباشرة للمستهلك (WebView) أثناء كتابته
     * بالتوازي لوجهة اختيارية على القرص (عند الجلب من الشبكة فقط؛ null عند الترقية من قرص
     * لذاكرة LRU فلا حاجة لإعادة كتابته) ولذاكرة مؤقّتة محدودة الحجم (Cap) لترقيتها لاحقاً لـ
     * Memory Cache — كل ذلك أثناء القراءة نفسها (Streaming حقيقي): لا يُخزَّن أي محتوى وسيط
     * لكامل الصورة قبل تمريرها لـ WebView، وتبدأ WebView باستلام البايتات فور وصولها لا بعد
     * اكتمال التنزيل كاملاً.
     *
     * فشل كتابة القرص تحديداً (مثلاً مساحة ممتلئة) لا يُوقف تمرير البايتات لـ WebView إطلاقاً؛
     * فقط يُلغي تخزين هذه الصورة بالكاش لهذه المرة. أما فشل القراءة من المصدر نفسه فيُرفع
     * كاستثناء طبيعي لأعلى (كما يحدث بأي تحميل صورة عادي يفشل شبكياً منتصف الطريق).
     */
    private final class TeeInputStream extends InputStream {
        private final InputStream source;
        private final OutputStream diskSink;
        private final ByteArrayOutputStream memorySink;
        private final int memoryCapBytes;
        private final TeeFinishListener onFinished;
        private boolean memoryCapExceeded;
        private boolean diskWriteFailed;
        private boolean sourceErrored;
        private boolean closed;

        TeeInputStream(InputStream source, OutputStream diskSink, int memoryCapBytes, TeeFinishListener onFinished) {
            this.source = source;
            this.diskSink = diskSink;
            this.memoryCapBytes = memoryCapBytes;
            this.memorySink = memoryCapBytes > 0
                    ? new ByteArrayOutputStream(Math.min(memoryCapBytes, 64 * 1024))
                    : null;
            this.onFinished = onFinished;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int n = read(single, 0, 1);
            return n == -1 ? -1 : (single[0] & 0xFF);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n;
            try {
                n = source.read(buffer, offset, length);
            } catch (IOException e) {
                sourceErrored = true;
                throw e;
            }
            if (n > 0) writeThrough(buffer, offset, n);
            return n;
        }

        private void writeThrough(byte[] buffer, int offset, int length) {
            if (diskSink != null && !diskWriteFailed) {
                try {
                    diskSink.write(buffer, offset, length);
                } catch (IOException e) {
                    diskWriteFailed = true; // مشكلة كتابة القرص فقط — لا تُوقف تمرير الصورة لـ WebView إطلاقاً
                }
            }
            if (memorySink != null && !memoryCapExceeded) {
                if (memorySink.size() + length > memoryCapBytes) {
                    memoryCapExceeded = true; // أكبر من الحد المسموح للذاكرة — نكتفي بالقرص فقط لهذه الصورة
                } else {
                    memorySink.write(buffer, offset, length);
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                source.close();
            } finally {
                if (diskSink != null) {
                    try {
                        diskSink.close();
                    } catch (IOException ignored) {
                    }
                }
                boolean diskSuccess = diskSink != null && !diskWriteFailed && !sourceErrored;
                boolean memorySuccess = memorySink != null && !memoryCapExceeded && !sourceErrored;
                byte[] memoryBytes = memorySuccess ? memorySink.toByteArray() : null;
                if (onFinished != null) onFinished.onFinished(diskSuccess, memoryBytes);
            }
        }
    }
}
