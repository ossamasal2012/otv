package com.yallagoal.app;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * مانع إعلانات قنوات iframe — يعمل فقط طوال «جلسة iframe» (من فتح قناة من نوع i حتى إغلاقها أو
 * الانتقال لقناة أخرى)، وخارجها لا يفعل شيئاً إطلاقاً: لا يلمس قنوات Xtream ولا المشغّل الأصلي
 * ولا أي قسم آخر في التطبيق، ولا يغيّر أي إعداد في WebView.
 *
 * <h3>لماذا من الجانب الأصلي (Native) وليس بجافاسكربت الصفحة؟</h3>
 * صفحة القناة تعيش في iframe من أصل (origin) مختلف، وسياسة Same-Origin تمنع جافاسكربت الصفحة
 * الأم من رؤية DOM الإطار أو التحكم بسكربتاته. بينما WebView نفسه (الذي يملكه التطبيق) يرى كل
 * طلب شبكي وكل تنقّل وكل نافذة جديدة تصدر من أي إطار، فهنا نتحكم فعلياً.
 *
 * <h3>الطبقات (كلها ضمن الجلسة فقط)</h3>
 * <ol>
 *   <li><b>الشبكة</b> — maybeBlock داخل shouldInterceptRequest: يُرجع ردّاً فارغاً لطلبات نطاقات
 *       الإعلانات المعروفة (AdBlockRules). لا يُحجب أي طلب وسائط/قائمة تشغيل/مقطع/مفتاح.</li>
 *   <li><b>النوافذ المنبثقة</b> — onCreateWindow: أي window.open أو target=_blank من الإطار «يُبتلع»:
 *       نمنح الصفحة نافذة وهمية (WebView مخفي بلا شبكة ولا جافاسكربت) يُدمَّر بعد ثوانٍ، فلا يظهر
 *       شيء للمستخدم، وصفحات القنوات التي تتوقف عن العمل إن رأت المنبثقة محجوبة (null) تكمل
 *       عملها كالمعتاد. هذا لا يعتمد على قائمة نطاقات، لذا يغطي النطاقات المتغيّرة باستمرار.</li>
 *   <li><b>التحويلات</b> — shouldCancelMainFrameNavigation: أثناء الجلسة يكون الإطار الرئيسي هو
 *       صفحة التطبيق نفسها ولا تتنقّل بنفسها أبداً، فأي تنقّل للإطار الرئيسي هو اختطاف من إعلان
 *       (كان يستبدل واجهة التطبيق كلها بصفحة إعلان). وتنقلات الإطار الفرعي إلى نطاقات الإعلانات
 *       المعروفة تُلغى أيضاً (shouldCancelSubframeNavigation).</li>
 * </ol>
 * ويكمّلها في index.html تضييق sandbox الـ iframe (حذف allow-top-navigation-by-user-activation
 * و allow-popups-to-escape-sandbox) فيمنع المتصفح نفسه تنقّل الصفحة العليا حتى قبل وصوله هنا.
 *
 * <h3>حدود لا يمكن تجاوزها (وهي مذكورة صراحةً في التقرير)</h3>
 * إعلان تخدمه شبكة غير معروفة، أو من نفس نطاق مشغّل القناة، أو طبقة (overlay) يرسمها سكربت
 * الصفحة نفسه داخل الإطار: لا يمكن إزالتها من الخارج بسبب Same-Origin ولا تمييزها بأمان عن
 * المحتوى بالرابط وحده. لم نلجأ لإخفاء عناصر عشوائياً (selectors عامة) لأنه يكسر المشغّلات.
 *
 * الأسلوب: الحقول المقروءة من خيوط الشبكة volatile، وكل ما يمسّ واجهة WebView يجري على خيط
 * الواجهة الرئيسي فقط.
 */
final class IframeAdBlocker {

    private static final String TAG = "IframeAdBlocker";

    /** سقف النوافذ الوهمية في الجلسة الواحدة (حماية من الإغراق)، وما زاد تحصل صفحته على null. */
    private static final int MAX_POPUPS_PER_SESSION = 25;
    /** أقصى عدد نوافذ وهمية حيّة في وقت واحد (كل واحدة WebView). */
    private static final int MAX_LIVE_SINKS = 3;
    /** عمر النافذة الوهمية قبل تدميرها. */
    private static final long SINK_LIFETIME_MS = 4_000L;

    // 1×1 شفاف (GIF) — الرد على صور الإعلانات المحجوبة دون إظهار أيقونة صورة مكسورة.
    private static final byte[] TRANSPARENT_GIF = {
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, (byte) 0x80, 0x00, 0x00,
            0x00, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x21, (byte) 0xF9, 0x04, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x44, 0x01, 0x00, 0x3B
    };

    private final WebView webView;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // ---- حالة الجلسة (تُقرأ من خيوط الشبكة) ----
    private volatile boolean active = false;
    private volatile boolean skipNetworkBlocking = false;
    private volatile String sessionHost = null;

    // ---- عدّادات للسجل التشخيصي فقط ----
    private final AtomicInteger blockedRequests = new AtomicInteger();
    private final AtomicInteger blockedRedirects = new AtomicInteger();
    private final AtomicInteger swallowedPopups = new AtomicInteger();

    // ---- خيط الواجهة الرئيسي فقط ----
    private int popupsThisSession = 0;
    private final List<WebView> sinks = new ArrayList<>();
    private final Map<WebView, Runnable> sinkTimers = new HashMap<>();
    private boolean settingsOverridden = false;
    private boolean savedSupportMultipleWindows = false;
    private boolean savedAutoOpenWindows = false;

    IframeAdBlocker(WebView webView) {
        this.webView = webView;
    }

    boolean isActive() {
        return active;
    }

    // ==================== إدارة الجلسة (تُستدعى من جسر JS على أي خيط) ====================

    /**
     * تبدأ جلسة قناة iframe جديدة. تُستدعى قبل إنشاء الـ iframe، وبرابط صفحة القناة إن عُرف (null
     * مقبول: نفعّل الحماية الهيكلية دون استثناء نفس-الموقع). استدعاؤها فوق جلسة قائمة =
     * جلسة جديدة (تبديل قناة): تُصفَّر العدّادات وتُدمَّر نوافذ الجلسة السابقة.
     */
    void startSession(String url) {
        sessionHost = AdBlockRules.hostOf(url);
        skipNetworkBlocking = url != null && AdBlockRules.isSkippedSession(url);
        blockedRequests.set(0);
        blockedRedirects.set(0);
        swallowedPopups.set(0);
        active = true;
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                destroyAllSinks();
                popupsThisSession = 0;
                applySessionSettings();
            }
        });
    }

    /** تنهي الجلسة: يعود WebView تماماً لإعداداته السابقة وتُدمَّر أي نوافذ وهمية. */
    void endSession() {
        final boolean wasActive = active;
        active = false;
        sessionHost = null;
        skipNetworkBlocking = false;
        if (wasActive) {
            Log.d(TAG, "iframe session ended: blockedRequests=" + blockedRequests.get()
                    + " swallowedPopups=" + swallowedPopups.get()
                    + " blockedRedirects=" + blockedRedirects.get());
        }
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                destroyAllSinks();
                popupsThisSession = 0;
                // قد تكون جلسة جديدة بدأت بين الاستدعاءين (تبديل قناة): لا نعيد الإعدادات حينها.
                if (!active) restoreSettings();
            }
        });
    }

    /** يُستدعى من onDestroy للنشاط. */
    void shutdown() {
        active = false;
        sessionHost = null;
        destroyAllSinks();
        settingsOverridden = false;
    }

    // ==================== 1) الشبكة ====================

    /**
     * يُستدعى من shouldInterceptRequest (خيط شبكة خلفي). يُرجع ردّاً فارغاً مناسباً لنوع المورد إن
     * كان الطلب إعلانياً، وإلا null (أي: دع WebView يكمل كالمعتاد).
     */
    WebResourceResponse maybeBlock(WebResourceRequest request) {
        if (!active || skipNetworkBlocking || request == null) return null;
        // التنقل للإطار الرئيسي لا يُعالَج هنا (راجع shouldCancelMainFrameNavigation).
        if (request.isForMainFrame()) return null;

        final String url = request.getUrl() != null ? request.getUrl().toString() : null;
        if (url == null || !AdBlockRules.shouldBlockRequest(url, sessionHost)) return null;

        blockedRequests.incrementAndGet();
        return emptyResponseFor(request);
    }

    private static WebResourceResponse emptyResponseFor(WebResourceRequest request) {
        String path = request.getUrl().getPath();
        path = path == null ? "" : path.toLowerCase(Locale.US);
        final Map<String, String> requestHeaders = request.getRequestHeaders();
        final String accept = requestHeaders != null ? requestHeaders.get("Accept") : null;
        final String acceptLower = accept == null ? "" : accept.toLowerCase(Locale.US);

        final String mime;
        byte[] body = new byte[0];
        String encoding = "UTF-8";

        if (isImagePath(path) || acceptLower.startsWith("image/")) {
            mime = "image/gif";
            body = TRANSPARENT_GIF;
            encoding = null;
        } else if (path.endsWith(".css") || acceptLower.startsWith("text/css")) {
            mime = "text/css";
        } else if (acceptLower.contains("text/html") || path.endsWith(".html") || path.endsWith(".htm")
                || path.endsWith(".php")) {
            mime = "text/html"; // إطار إعلاني محجوب يظهر فارغاً بدل صفحة خطأ
        } else {
            mime = "application/javascript"; // سكربتات وطلبات XHR/fetch: رد فارغ ناجح لا يكسر الصفحة
        }

        final Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse(mime, encoding, 200, "OK", headers, new ByteArrayInputStream(body));
    }

    private static boolean isImagePath(String path) {
        return path.endsWith(".gif") || path.endsWith(".png") || path.endsWith(".jpg")
                || path.endsWith(".jpeg") || path.endsWith(".webp") || path.endsWith(".svg")
                || path.endsWith(".ico") || path.endsWith(".bmp");
    }

    // ==================== 2) التنقّل / التحويلات ====================

    /**
     * أثناء جلسة iframe: أي تنقّل للإطار الرئيسي هو اختطاف (التطبيق لا يتنقّل بنفسه في هذه الفترة
     * لأن الـ iframe يغطي الشاشة كلها). true = ألغِ التنقّل.
     */
    boolean shouldCancelMainFrameNavigation() {
        if (!active) return false;
        blockedRedirects.incrementAndGet();
        return true;
    }

    /** تنقّل داخل إطار فرعي: نلغيه فقط إن كانت وجهته نطاق إعلانات معروف؛ غير ذلك يكمل في الإطار. */
    boolean shouldCancelSubframeNavigation(String url) {
        if (!active || skipNetworkBlocking || url == null) return false;
        if (!AdBlockRules.isAdNavigation(url, sessionHost)) return false;
        blockedRedirects.incrementAndGet();
        return true;
    }

    /** API 23 فقط (لا نملك isForMainFrame): نلغي فقط وجهات نطاقات الإعلانات المعروفة. */
    boolean shouldCancelNavigationLegacy(String url) {
        return shouldCancelSubframeNavigation(url);
    }

    // ==================== 3) النوافذ المنبثقة ====================

    /**
     * يُستدعى من WebChromeClient.onCreateWindow (خيط الواجهة). يرجع true إذا تولّى الأمر (ابتلع
     * النافذة)، وfalse إذا لم يتولَّ (خارج الجلسة، أو تجاوز السقف) فلا تُنشأ أي نافذة.
     */
    boolean onCreateWindow(WebView parent, Message resultMsg) {
        if (!active || resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport)) {
            return false;
        }
        if (popupsThisSession >= MAX_POPUPS_PER_SESSION || sinks.size() >= MAX_LIVE_SINKS) {
            swallowedPopups.incrementAndGet();
            return false; // إغراق بالنوافذ: لا نهدر موارد على كل واحدة؛ الصفحة تحصل على null
        }
        popupsThisSession++;
        swallowedPopups.incrementAndGet();

        final WebView sink = createSink(parent.getContext());
        ((WebView.WebViewTransport) resultMsg.obj).setWebView(sink);
        resultMsg.sendToTarget();

        sinks.add(sink);
        final Runnable timer = new Runnable() {
            @Override
            public void run() {
                destroySink(sink);
            }
        };
        sinkTimers.put(sink, timer);
        uiHandler.postDelayed(timer, SINK_LIFETIME_MS);
        return true;
    }

    /** WebView مخفي بلا شبكة ولا جافاسكربت ولا تنقّل: يستقبل «النافذة» المطلوبة ويُهملها. */
    private WebView createSink(android.content.Context context) {
        final WebView sink = new WebView(context);
        final WebSettings s = sink.getSettings();
        s.setJavaScriptEnabled(false);
        s.setBlockNetworkLoads(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        sink.setWebViewClient(new WebViewClient() {
            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true; // لا تفتح شيئاً ولا تسأل نظام التشغيل أن يفتحه في متصفح
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
        });
        return sink;
    }

    private void destroySink(WebView sink) {
        final Runnable timer = sinkTimers.remove(sink);
        if (timer != null) uiHandler.removeCallbacks(timer);
        if (!sinks.remove(sink)) return; // دُمِّر سابقاً
        try {
            sink.stopLoading();
            sink.destroy();
        } catch (Exception ignored) {
            // أفضل جهد: تنظيف فقط
        }
    }

    private void destroyAllSinks() {
        final List<WebView> copy = new ArrayList<>(sinks);
        for (WebView sink : copy) destroySink(sink);
    }

    // ==================== إعدادات WebView خلال الجلسة فقط ====================

    /**
     * دعم النوافذ المتعددة مطلوب كي يصل window.open إلى onCreateWindow (وإلا يُحمَّل في الإطار
     * نفسه). نفعّله طوال الجلسة فقط ونعيده كما كان بعدها، فخارج قنوات iframe يبقى سلوك WebView
     * (بما فيه target=_blank في واجهة التطبيق) كما كان تماماً.
     */
    private void applySessionSettings() {
        if (!active) return;
        try {
            final WebSettings st = webView.getSettings();
            if (!settingsOverridden) {
                savedSupportMultipleWindows = st.supportMultipleWindows();
                savedAutoOpenWindows = st.getJavaScriptCanOpenWindowsAutomatically();
                settingsOverridden = true;
            }
            st.setSupportMultipleWindows(true);
            st.setJavaScriptCanOpenWindowsAutomatically(true);
        } catch (Exception e) {
            Log.d(TAG, "could not apply session settings: " + e.getClass().getSimpleName());
        }
    }

    private void restoreSettings() {
        if (!settingsOverridden) return;
        try {
            final WebSettings st = webView.getSettings();
            st.setSupportMultipleWindows(savedSupportMultipleWindows);
            st.setJavaScriptCanOpenWindowsAutomatically(savedAutoOpenWindows);
        } catch (Exception e) {
            Log.d(TAG, "could not restore settings: " + e.getClass().getSimpleName());
        }
        settingsOverridden = false;
    }
}
