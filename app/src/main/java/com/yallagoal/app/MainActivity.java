package com.yallagoal.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewCompat;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.firebase.messaging.FirebaseMessaging;

public class MainActivity extends AppCompatActivity {

    private WebView webView;

    // for HTML5 <video> fullscreen (player fullscreen button)
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private FrameLayout fullscreenContainer;
    private int originalOrientation;

    private static final String LOCAL_URL = "file:///android_asset/index.html";

    // الرابط الفعلي المُحمَّل بالـ WebView — دائماً LOCAL_URL الآن (لا يوجد أي مصدر بديل بعد
    // حذف نظام التحديث الذكي بالكامل؛ التطبيق يُحدَّث فقط عبر تثبيت APK كامل جديد). المتغيّر
    // مُبقًى (بدل استخدام LOCAL_URL مباشرة) فقط لمقارنته بـonPageFinished بالأسفل.
    private String loadedUrl = LOCAL_URL;

    // true فقط بين استدعاء loadDataWithBaseURL (واجهة مشفّرة مفكوكة بالذاكرة) وأول onPageFinished
    // بعده — شبكة أمان تضمن حقن رمز الجسر حتى لو أبلغ WebView عن رابط غير متوقع لهذه الصفحة.
    private boolean awaitingVaultPage = false;

    // شبكة أمان لـ localStorage: صفحة محمّلة من الذاكرة (loadDataWithBaseURL) قد ترفض بعض إصدارات
    // WebView الوصول لـ localStorage؛ هذا السكربت يفحص ذلك أول ما تبدأ الصفحة، وإن فشل يستبدلها
    // بتخزين مؤقت بالذاكرة كي لا يتعطّل التطبيق بالكامل (الحالة الطبيعية: لا يفعل أي شيء).
    private static final String STORAGE_GUARD =
            "<script>(function(){try{var k='__yg_t__';localStorage.setItem(k,'1');localStorage.removeItem(k);}"
            + "catch(e){try{var m={};Object.defineProperty(window,'localStorage',{configurable:true,value:{"
            + "getItem:function(k){return Object.prototype.hasOwnProperty.call(m,k)?m[k]:null},"
            + "setItem:function(k,v){m[k]=String(v)},removeItem:function(k){delete m[k]},clear:function(){m={}},"
            + "key:function(i){return Object.keys(m)[i]||null},"
            + "get length(){return Object.keys(m).length}}});}catch(e2){}}})();</script>";

    // تظهر بدل التطبيق إذا وُجدت واجهة مشفّرة داخل الحزمة وتعذّر فكّها (نسخة مُعاد توقيعها/معدّلة).
    private static final String TAMPER_HTML =
            "<!DOCTYPE html><html lang='ar' dir='rtl'><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
            + "background:#0b1220;color:#e2e8f0;font-family:sans-serif;text-align:center;padding:24px;box-sizing:border-box}"
            + "h1{font-size:20px;margin:0 0 12px;color:#f87171}p{font-size:15px;line-height:1.9;margin:0;color:#94a3b8}"
            + "</style></head><body><div><h1>نسخة غير رسمية من التطبيق</h1>"
            + "<p>تعذّر التحقق من سلامة هذه النسخة (قد تكون معدَّلة أو غير مكتملة).<br>"
            + "يرجى حذفها وتثبيت النسخة الرسمية من المصدر الأصلي.</p></div></body></html>";

    // يدفع عدد المستخدمين/النشطين مباشرةً للواجهة (WebView) فور تغيّرهما، بدون أي polling.
    private UserStatsManager.StatsListener statsListener;

    // تُحدَّث من الصفحة (JS) عبر WebAppInterface.notifyIframeState() كلما فُتح/أُغلق مشغل
    // iframe بملء الشاشة — تجعل زر الرجوع يخرج من الـ iframe فقط بدل إغلاق التطبيق بالكامل.
    private volatile boolean isIframeOpen = false;

    public void setIframeOpen(boolean open) {
        isIframeOpen = open;
    }

    // تُحدَّث من الصفحة (JS) عبر WebAppInterface.notifyNavDepth() كلما تغيّر عمق مكدس التنقّل
    // الداخلي بالتطبيق (مثلاً: التنقّل داخل سيرفرات Xtream — قائمة السيرفرات، ثم قنوات/أفلام/
    // مسلسلات السيرفر، ثم التصنيفات، ثم القائمة، ثم مواسم/حلقات المسلسل...). عندما تكون true
    // فهذا يعني أن هناك خطوة سابقة يمكن الرجوع لها داخل الصفحة نفسها، فيتولى زر الرجوع
    // بالجهاز إرجاع خطوة واحدة داخل التطبيق بدل إغلاقه بالكامل مباشرة.
    private volatile boolean jsCanGoBack = false;

    public void setJsCanGoBack(boolean canGoBack) {
        jsCanGoBack = canGoBack;
    }

    // ذاكرة تخزين الصور المؤقتة الأصلية (شعارات قنوات/بوسترات أفلام ومسلسلات Xtream) — تُنشأ
    // مرة واحدة وتُستخدم طوال عمر النشاط عبر shouldInterceptRequest بالأسفل. راجع التوثيق
    // الكامل داخل ImageCacheInterceptor.java لسبب اعتمادها على الاعتراض الأصلي بدل JavaScript.
    private ImageCacheInterceptor imageCacheInterceptor;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        // Edge-to-edge, fullscreen, app covers the whole screen
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);
        hideSystemBars();

        fullscreenContainer = findViewById(R.id.fullscreen_container);
        webView = findViewById(R.id.webview);

        WebView.setWebContentsDebuggingEnabled(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        // Allow local asset loading (file:///android_asset/...) — لازم لأنك تستخدم LOCAL_URL
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        // Allow (or restrict) file URL access from file URLs — امنح الحد الأدنى اللازم
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            settings.setAllowFileAccessFromFileURLs(true);
            // نترك universal access معطّل لأمان أفضل (لا تسمح لملفات محلية بطلبات شبكية عابرة للمصدر)
            settings.setAllowUniversalAccessFromFileURLs(false);
        }

        settings.setSaveFormData(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setUserAgentString(settings.getUserAgentString() + " YallaGoalApp/1.0");
        WebViewCompat.startSafeBrowsing(this, null);

        CookieManager.getInstance().setAcceptCookie(true);
        // setAcceptThirdPartyCookies requires API 21+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }

        final String bridgeToken = UUID.randomUUID().toString();

        imageCacheInterceptor = new ImageCacheInterceptor(getApplicationContext());

        webView.setWebViewClient(new WebViewClient() {
            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    if (imageCacheInterceptor != null && imageCacheInterceptor.shouldHandle(request)) {
                        WebResourceResponse cached = imageCacheInterceptor.intercept(request);
                        if (cached != null) return cached;
                        // null يعني: تعذر الجلب من هنا لأي سبب — نُسقط لسلوك WebView الافتراضي
                        // بالأسفل بدل حجب الصورة نهائياً.
                    }
                } catch (Exception ignored) {
                    // أي عطل غير متوقع بطبقة الكاش لا يجب أن يكسر تحميل أي مورد إطلاقاً.
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                boolean isMainPage = loadedUrl.equals(url)
                        || (awaitingVaultPage && url != null && url.startsWith("data:"));
                if (!isMainPage) return;
                awaitingVaultPage = false;

                // حقن رمز المصادقة (ضروري لعمل جسر AndroidPlayer) وتشغيل التهيئة الأساسية.
                String script = "(function(){ try {"
                        + "Object.defineProperty(window, '__YG_TOKEN__', { value: '" + bridgeToken + "', writable: false, configurable: false });"
                        + "checkAuth();"
                        + "if (window.initUserStats) { initUserStats(); }"
                        + "return 'OK';"
                        + "} catch (e) { return 'ERR:' + (e && e.message ? e.message : String(e)); } })();";

                view.evaluateJavascript(script, result -> { /* لا حاجة لأي إجراء إضافي بالنتيجة */ });
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    onHideCustomView();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                originalOrientation = getRequestedOrientation();

                fullscreenContainer.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenContainer.setVisibility(View.VISIBLE);
                webView.setVisibility(View.GONE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                hideSystemBars();
            }

            @Override
            public void onHideCustomView() {
                exitFullscreenVideo();
            }

            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                // بعض قنوات iframe تعرض شاشة سوداء لأن مصدرها يشغّل الفيديو عبر EME/DRM
                // (Widevine) ويطلب إذن "الوسائط المحمية" — رفضه دائماً (كما كان سابقاً) يمنع
                // فك تشفير الصورة نهائياً فتظهر شاشة سوداء رغم أن الصوت أو واجهة المشغّل قد
                // تعمل. نمنح هذا الإذن تحديداً فقط: لا يكشف أي بيانات حساسة ولا علاقة له
                // بالكاميرا أو المايكروفون، ونواصل رفض أي طلب آخر (كاميرا/مايكروفون/MIDI) كما
                // كان تماماً.
                runOnUiThread(() -> {
                    for (String resource : request.getResources()) {
                        if (android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(resource)) {
                            request.grant(new String[]{android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
                            return;
                        }
                    }
                    request.deny();
                });
            }
        });

        CastManager castManager;
        try {
            castManager = new CastManager(this);
        } catch (Throwable e) {
            castManager = null;
        }

        webView.addJavascriptInterface(new WebAppInterface(this, castManager, bridgeToken), "AndroidPlayer");

        if (!loadUi()) {
            // نسخة معدَّلة/مُعاد توقيعها: نعرض رسالة فقط ولا نُكمل أي تهيئة (إشعارات/إحصائيات/تحديث).
            return;
        }

        requestNotificationPermissionIfNeeded();

        FirebaseMessaging.getInstance().subscribeToTopic("all_users");

        new UpdateManager().checkForUpdate(this);

        startLiveStatsUpdates();
    }

    /**
     * يحمّل واجهة التطبيق.
     *  - البناء الرسمي: الواجهة مشفّرة داخل الحزمة (assets/yg.dat) وتُفكّ بالذاكرة فقط، فلا يوجد
     *    index.html مقروء داخل الـ APK (راجع AssetVault.java و README_SECURITY.md).
     *  - بناء التطوير المحلي (لا يوجد ملف مشفّر): تُحمَّل index.html العادية كما كان دائماً.
     *
     * @return false إذا كانت الحزمة تحوي واجهة مشفّرة تعذّر فكّها (توقيع مختلف أو تلاعب) — عندها
     *         تُعرض رسالة "نسخة غير رسمية" ويُوقَف باقي التهيئة.
     */
    private boolean loadUi() {
        if (!AssetVault.exists(this)) {
            webView.loadUrl(loadedUrl);
            return true;
        }
        try {
            String html = injectAfterHead(AssetVault.decryptHtml(this), STORAGE_GUARD);
            awaitingVaultPage = true;
            // base = history = LOCAL_URL ⇒ نفس المصدر (file://) ونفس تخزين الصفحة السابق تماماً.
            webView.loadDataWithBaseURL(LOCAL_URL, html, "text/html", "UTF-8", LOCAL_URL);
            return true;
        } catch (Exception e) {
            awaitingVaultPage = false;
            webView.loadDataWithBaseURL(null, TAMPER_HTML, "text/html", "UTF-8", null);
            return false;
        }
    }

    /** يدرج snippet مباشرةً بعد وسم {@code <head>} (وليس قبل DOCTYPE كي لا يدخل المتصفح وضع quirks). */
    private static String injectAfterHead(String html, String snippet) {
        Matcher m = Pattern.compile("<head[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) {
            return html.substring(0, m.end()) + snippet + html.substring(m.end());
        }
        return html;
    }

    /**
     * يشترك بعدّاد المستخدمين/النشطين الحيّ ويدفع كل تحديث فوراً إلى صفحة الويب عبر
     * window.__ygOnStatsUpdate(total, active) — بدون أي استعلام دوري (polling) من الواجهة.
     */
    private void startLiveStatsUpdates() {
        try {
            statsListener = (total, active) -> runOnUiThread(() -> {
                if (webView == null) return;
                String js = "window.__ygOnStatsUpdate && window.__ygOnStatsUpdate("
                        + total + "," + active + ");";
                webView.evaluateJavascript(js, null);
            });
            UserStatsManager.getInstance(this).addListener(statsListener);
        } catch (Exception ignored) {
            // أي خلل هنا لا يجب أن يؤثر على تشغيل التطبيق أو القنوات إطلاقاً.
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        // ملاحظة مهمة: استخدام Build.VERSION_CODES.TIRAMISU يتطلب compileSdkVersion >= 33.
        // إذا كان مشروعك يستخدم compileSdk < 33 فبدّل الشرط إلى (Build.VERSION.SDK_INT >= 33)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                exitFullscreenVideo();
                return true;
            }
            if (isIframeOpen) {
                // نطلب من الصفحة نفسها إغلاق الـ iframe والرجوع للقائمة الرئيسية — لا نغلق
                // النشاط (Activity) إطلاقاً هنا. isIframeOpen سيتحدّث تلقائياً لـ false بمجرد
                // أن تستدعي الصفحة notifyIframeState(false, ...) من داخل closeIframeAndReturnToList().
                webView.evaluateJavascript(
                        "if (window.closeIframeAndReturnToList) { closeIframeAndReturnToList(); }", null);
                return true;
            }
            if (jsCanGoBack) {
                // نطلب من الصفحة الرجوع خطوة واحدة داخل مكدس التنقّل الخاص بها (مثلاً: من قائمة
                // حلقات موسم إلى قائمة المواسم، أو من قائمة قنوات سيرفر Xtream إلى تصنيفاته...).
                // jsCanGoBack سيتحدّث تلقائياً بعدها عبر notifyNavDepth() لو وصلنا للجذر.
                webView.evaluateJavascript(
                        "if (window.__ygHandleAppBack) { window.__ygHandleAppBack(); }", null);
                return true;
            }
            if (webView.canGoBack()) {
                webView.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void exitFullscreenVideo() {
        if (customView == null) return;
        webView.setVisibility(View.VISIBLE);
        fullscreenContainer.setVisibility(View.GONE);
        fullscreenContainer.removeView(customView);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customView = null;
        setRequestedOrientation(originalOrientation);
        hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // يغطي كل مسارات الدخول للتطبيق (أيقونة عادية، لمس الإشعار، أو العودة من الخلفية) بمكان
        // واحد فقط: إن كانت خدمة UpdateDownloadService قد أنهت تنزيل تحديث كامل وتحقّقت منه
        // بنجاح، يُثبَّت تلقائياً الآن دون أي ضغطة إضافية من المستخدم.
        UpdateManager.checkAndInstallIfReady(this);
    }

    @Override
    protected void onDestroy() {
        if (statsListener != null) {
            try {
                UserStatsManager.getInstance(this).removeListener(statsListener);
            } catch (Exception ignored) {}
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
