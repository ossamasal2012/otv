package com.yallagoal.app;

import android.Manifest;
import android.annotation.SuppressLint;
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

import java.util.UUID;

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
                if (LOCAL_URL.equals(url)) {
                    view.evaluateJavascript(
                            "Object.defineProperty(window, '__YG_TOKEN__', { value: '" + bridgeToken + "', writable: false, configurable: false }); checkAuth(); if (window.initUserStats) { initUserStats(); }", null);
                }
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
                runOnUiThread(request::deny);
            }
        });

        CastManager castManager;
        try {
            castManager = new CastManager(this);
        } catch (Throwable e) {
            castManager = null;
        }

        webView.addJavascriptInterface(new WebAppInterface(this, castManager, bridgeToken), "AndroidPlayer");

        webView.loadUrl(LOCAL_URL);

        requestNotificationPermissionIfNeeded();

        FirebaseMessaging.getInstance().subscribeToTopic("all_users");

        new UpdateManager().checkForUpdate(this);

        startLiveStatsUpdates();
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
