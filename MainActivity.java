package com.yallagoal.app;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.FrameLayout;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "YallaGoalProxy";

    // ─── Fullscreen state ───────────────────────────────────────────────────────
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private FrameLayout customViewContainer;

    // ═══════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        super.onCreate(savedInstanceState);
        applyFullscreen();
        setupWebView();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFullscreen();
    }

    @Override
    public void onResume() {
        super.onResume();
        applyFullscreen();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  FULLSCREEN
    // ═══════════════════════════════════════════════════════════════════════════

    private void applyFullscreen() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
        } else {
            //noinspection deprecation
            w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  WEBVIEW SETUP
    // ═══════════════════════════════════════════════════════════════════════════

    private void setupWebView() {
        try {
            WebView wv = getBridge().getWebView();
            WebSettings s = wv.getSettings();

            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setAllowFileAccess(true);
            s.setAllowContentAccess(true);
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            s.setBuiltInZoomControls(false);
            s.setDisplayZoomControls(false);
            s.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.6367.82 Mobile Safari/537.36"
            );

            wv.setLayerType(View.LAYER_TYPE_HARDWARE, null);

            // Full-screen video support
            wv.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onShowCustomView(View view, CustomViewCallback callback) {
                    if (customView != null) { onHideCustomView(); return; }
                    customView = view;
                    customViewCallback = callback;
                    ViewGroup decorView = (ViewGroup) getWindow().getDecorView();
                    customViewContainer = new FrameLayout(MainActivity.this);
                    customViewContainer.setBackgroundColor(Color.BLACK);
                    customViewContainer.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    decorView.addView(customViewContainer, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    getBridge().getWebView().setVisibility(View.GONE);
                    applyFullscreen();
                }

                @Override
                public void onHideCustomView() {
                    if (customView == null) return;
                    ViewGroup decorView = (ViewGroup) getWindow().getDecorView();
                    customViewContainer.removeView(customView);
                    decorView.removeView(customViewContainer);
                    customView = null;
                    customViewContainer = null;
                    customViewCallback.onCustomViewHidden();
                    getBridge().getWebView().setVisibility(View.VISIBLE);
                    applyFullscreen();
                }
            });

            if (Build.VERSION.SDK_INT >= 21) {
                android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                cm.setAcceptCookie(true);
                cm.setAcceptThirdPartyCookies(wv, true);
            }

            getBridge().setWebViewClient(new UltraProxyClient(getBridge()));
            Log.i(TAG, "══════ يلا گول ULTRA PROXY ENGINE v5.0 نشط ══════");

        } catch (Exception e) {
            Log.e(TAG, "setupWebView error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ████████████████████████████████████████████████████████████████████████
    //  ██                                                                    ██
    //  ██          U L T R A   P R O X Y   C L I E N T   v 5 . 0           ██
    //  ██       أقوى وأذكى بروكسي في تاريخ تطبيقات الأندرويد               ██
    //  ██                                                                    ██
    //  ████████████████████████████████████████████████████████████████████████
    // ═══════════════════════════════════════════════════════════════════════════

    class UltraProxyClient extends BridgeWebViewClient {

        // ── Constants ─────────────────────────────────────────────────────────
        private static final int TIMEOUT_CONNECT_MS      = 12_000;
        private static final int TIMEOUT_READ_MS         = 20_000;
        private static final int TIMEOUT_STREAM_MS       = 30_000;
        private static final int MAX_REDIRECTS           = 12;
        private static final int MAX_RETRY_ATTEMPTS      = 3;
        private static final long CACHE_ENTRY_TTL_MS     = 8_000;    // 8 s for volatile data
        private static final long CACHE_STATIC_TTL_MS   = 180_000;  // 3 min for static assets
        private static final int  CACHE_MAX_BODY_BYTES   = 512_000;  // 512 KB
        private static final int  CACHE_MAX_ENTRIES      = 120;
        private static final int  BUFFER_SIZE            = 32_768;   // 32 KB streaming buffer

        // ── Intelligent Cache ──────────────────────────────────────────────────
        private final LinkedHashMap<String, CacheEntry> responseCache =
            new LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> e) {
                    return size() > CACHE_MAX_ENTRIES;
                }
            };

        // ── Connection Stats (Adaptive Intelligence) ───────────────────────────
        private final ConcurrentHashMap<String, HostStats> hostStatsMap = new ConcurrentHashMap<>();

        // ── Blocked URL fingerprints (auto-learned) ────────────────────────────
        private final Set<String> autoBlockedPatterns =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

        // ── Domain → Cookie jar (session persistence) ─────────────────────────
        private final ConcurrentHashMap<String, String> cookieJar = new ConcurrentHashMap<>();

        // ── User-Agent rotation pool ───────────────────────────────────────────
        private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.119 Mobile Safari/537.36 SamsungBrowser/23.0",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.3 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (Linux; Android 14; OnePlus 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2.1 Safari/605.1.15",
        };
        private final AtomicInteger uaIndex = new AtomicInteger(0);

        // ── Counters ───────────────────────────────────────────────────────────
        private final AtomicLong totalRequests    = new AtomicLong(0);
        private final AtomicLong proxiedRequests  = new AtomicLong(0);
        private final AtomicLong cacheHits        = new AtomicLong(0);
        private final AtomicLong bypassedRequests = new AtomicLong(0);

        // ── MIME type registry ─────────────────────────────────────────────────
        private static final Map<String, String> MIME_MAP;
        static {
            Map<String, String> m = new HashMap<>();
            // Video
            m.put("m3u8",  "application/vnd.apple.mpegurl");
            m.put("ts",    "video/mp2t");
            m.put("mp4",   "video/mp4");
            m.put("webm",  "video/webm");
            m.put("mkv",   "video/x-matroska");
            m.put("avi",   "video/x-msvideo");
            m.put("mov",   "video/quicktime");
            m.put("flv",   "video/x-flv");
            m.put("3gp",   "video/3gpp");
            // Audio
            m.put("mp3",   "audio/mpeg");
            m.put("aac",   "audio/aac");
            m.put("ogg",   "audio/ogg");
            m.put("opus",  "audio/opus");
            m.put("wav",   "audio/wav");
            m.put("flac",  "audio/flac");
            m.put("m4a",   "audio/mp4");
            m.put("weba",  "audio/webm");
            // Manifests & playlists
            m.put("m3u",   "audio/x-mpegurl");
            m.put("mpd",   "application/dash+xml");
            m.put("f4m",   "application/f4m+xml");
            m.put("xml",   "application/xml");
            // Web assets
            m.put("js",    "application/javascript");
            m.put("json",  "application/json");
            m.put("css",   "text/css");
            m.put("html",  "text/html");
            m.put("htm",   "text/html");
            m.put("txt",   "text/plain");
            m.put("svg",   "image/svg+xml");
            m.put("png",   "image/png");
            m.put("jpg",   "image/jpeg");
            m.put("jpeg",  "image/jpeg");
            m.put("gif",   "image/gif");
            m.put("webp",  "image/webp");
            m.put("ico",   "image/x-icon");
            m.put("woff",  "font/woff");
            m.put("woff2", "font/woff2");
            m.put("ttf",   "font/ttf");
            // Streams
            m.put("asx",   "video/x-ms-asf");
            m.put("pls",   "audio/x-scpls");
            MIME_MAP = Collections.unmodifiableMap(m);
        }

        // ── Passthrough domains (never proxy these) ────────────────────────────
        private static final Set<String> PASSTHROUGH_DOMAINS = new HashSet<>(Arrays.asList(
            "localhost", "capacitor", "127.0.0.1",
            "fonts.googleapis.com", "fonts.gstatic.com",
            "cdn.jsdelivr.net", "cdnjs.cloudflare.com",
            "unpkg.com", "ajax.googleapis.com"
        ));

        // ── Pattern matchers ───────────────────────────────────────────────────
        private static final Pattern CHARSET_PATTERN =
            Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);
        private static final Pattern REDIRECT_PATTERN =
            Pattern.compile("<meta[^>]+http-equiv=[\"']?refresh[\"']?[^>]+content=[\"']\\d+;\\s*url=([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern STREAM_URL_PATTERN =
            Pattern.compile("(https?://[^\\s\"'<>]+\\.(?:m3u8|mpd|ts|mp4|webm)(?:[?#][^\\s\"'<>]*)?)",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern GZIP_PATTERN =
            Pattern.compile("gzip", Pattern.CASE_INSENSITIVE);
        private static final Pattern DEFLATE_PATTERN =
            Pattern.compile("deflate", Pattern.CASE_INSENSITIVE);

        // ══════════════════════════════════════════════════════════════════════
        //  INNER CLASSES
        // ══════════════════════════════════════════════════════════════════════

        /** Cache entry with TTL, ETag, and conditional-GET support */
        static class CacheEntry {
            final byte[]              body;
            final String              mimeType;
            final String              charset;
            final Map<String, String> headers;
            final long                createdAt;
            final long                ttl;
            final String              etag;
            final String              lastModified;
            final int                 status;

            CacheEntry(byte[] body, String mimeType, String charset,
                       Map<String, String> headers, long ttl,
                       String etag, String lastModified, int status) {
                this.body         = body;
                this.mimeType     = mimeType;
                this.charset      = charset;
                this.headers      = headers;
                this.createdAt    = System.currentTimeMillis();
                this.ttl          = ttl;
                this.etag         = etag;
                this.lastModified = lastModified;
                this.status       = status;
            }

            boolean isExpired() { return (System.currentTimeMillis() - createdAt) > ttl; }
        }

        /** Per-host adaptive statistics for intelligent routing */
        static class HostStats {
            final AtomicLong successCount  = new AtomicLong(0);
            final AtomicLong failureCount  = new AtomicLong(0);
            final AtomicLong totalLatency  = new AtomicLong(0);
            volatile int     bestTimeout   = TIMEOUT_CONNECT_MS; // self-adjusting

            void recordSuccess(long latencyMs) {
                successCount.incrementAndGet();
                totalLatency.addAndGet(latencyMs);
                // Shrink timeout for fast hosts, floor at 4 s
                long avg = totalLatency.get() / successCount.get();
                bestTimeout = (int) Math.max(4_000, Math.min(avg * 3, TIMEOUT_CONNECT_MS));
            }

            void recordFailure() {
                failureCount.incrementAndGet();
            }

            double reliability() {
                long total = successCount.get() + failureCount.get();
                return total == 0 ? 1.0 : (double) successCount.get() / total;
            }

            long avgLatency() {
                long c = successCount.get();
                return c == 0 ? 0 : totalLatency.get() / c;
            }
        }

        /** Rich result from a proxied request */
        static class ProxyResult {
            final InputStream         stream;
            final String              mimeType;
            final String              charset;
            final int                 status;
            final Map<String, String> headers;
            final boolean             fromCache;

            ProxyResult(InputStream stream, String mimeType, String charset,
                        int status, Map<String, String> headers, boolean fromCache) {
                this.stream    = stream;
                this.mimeType  = mimeType;
                this.charset   = charset;
                this.status    = status;
                this.headers   = headers;
                this.fromCache = fromCache;
            }
        }

        /** Classified request type for routing decisions */
        enum RequestClass {
            BYPASS,           // let WebView handle natively
            PREFLIGHT,        // OPTIONS → instant 204
            STATIC_ASSET,     // CSS/JS/font/image → cache long
            API_CALL,         // JSON/XHR → cache short, retry
            MEDIA_STREAM,     // HLS/DASH/audio streams → streaming mode
            WEB_PAGE,         // HTML navigation → bypass
            UNKNOWN           // proxy with defaults
        }

        // ══════════════════════════════════════════════════════════════════════

        UltraProxyClient(com.getcapacitor.Bridge b) {
            super(b);
        }

        // ══════════════════════════════════════════════════════════════════════
        //  MAIN INTERCEPT ENTRY POINT
        // ══════════════════════════════════════════════════════════════════════

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView wv, WebResourceRequest req) {
            String url    = req.getUrl().toString();
            String method = req.getMethod() != null ? req.getMethod().toUpperCase(Locale.ROOT) : "GET";
            Map<String, String> reqHeaders = req.getRequestHeaders();
            long reqStart = System.currentTimeMillis();
            totalRequests.incrementAndGet();

            try {
                // ── 1. PREFLIGHT: instant 204 response ────────────────────────
                if ("OPTIONS".equals(method)) {
                    return buildPreflightResponse();
                }

                // ── 2. Classify the request ───────────────────────────────────
                RequestClass cls = classifyRequest(url, method, reqHeaders);

                // ── 3. Hard bypass cases ──────────────────────────────────────
                if (cls == RequestClass.BYPASS || cls == RequestClass.WEB_PAGE) {
                    bypassedRequests.incrementAndGet();
                    return super.shouldInterceptRequest(wv, req);
                }

                // ── 4. Check auto-blocked patterns ────────────────────────────
                if (isAutoBlocked(url)) {
                    return buildErrorResponse(403, "Blocked by adaptive filter");
                }

                // ── 5. Check cache before any network call ────────────────────
                String cacheKey = buildCacheKey(url, method, reqHeaders);
                synchronized (responseCache) {
                    CacheEntry cached = responseCache.get(cacheKey);
                    if (cached != null && !cached.isExpired()) {
                        cacheHits.incrementAndGet();
                        Log.d(TAG, "CACHE HIT → " + url);
                        return buildCachedResponse(cached);
                    }
                    if (cached != null) { responseCache.remove(cacheKey); }
                }

                // ── 6. Media streams bypass (let exoplayer / hls.js handle) ───
                if (cls == RequestClass.MEDIA_STREAM && isNativeStreamable(url, reqHeaders)) {
                    bypassedRequests.incrementAndGet();
                    return super.shouldInterceptRequest(wv, req);
                }

                // ── 7. Execute proxied request with retry intelligence ─────────
                proxiedRequests.incrementAndGet();
                ProxyResult result = executeWithRetry(url, method, reqHeaders, cls, reqStart);

                if (result == null) {
                    // All retries failed → fall back to native
                    return super.shouldInterceptRequest(wv, req);
                }

                // ── 8. Cache the result if appropriate ────────────────────────
                if (isCacheable(method, result.status, result.mimeType, cls)) {
                    cacheResult(cacheKey, result, cls);
                }

                return buildWebResourceResponse(result);

            } catch (Exception e) {
                Log.e(TAG, "Proxy intercept error for: " + url, e);
                return super.shouldInterceptRequest(wv, req);
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        //  REQUEST CLASSIFICATION ENGINE
        // ══════════════════════════════════════════════════════════════════════

        private RequestClass classifyRequest(String url, String method,
                                             Map<String, String> headers) {
            String lower = url.toLowerCase(Locale.ROOT);
            String accept = headers != null ? headers.getOrDefault("Accept", "") : "";
            String origin = headers != null ? headers.getOrDefault("Origin", "") : "";
            String referer = headers != null ? headers.getOrDefault("Referer", "") : "";
            String xReq = headers != null
                ? headers.getOrDefault("X-Requested-With", "") : "";

            // ── Must bypass: non-HTTP URLs ─────────────────────────────────────
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return RequestClass.BYPASS;
            }

            // ── Must bypass: local / internal ─────────────────────────────────
            for (String pd : PASSTHROUGH_DOMAINS) {
                if (lower.contains(pd)) return RequestClass.BYPASS;
            }

            // ── Must bypass: capacitor scheme ─────────────────────────────────
            if (lower.contains("capacitor://")) return RequestClass.BYPASS;

            // ── HTML navigation from iframe or 3rd-party ──────────────────────
            boolean isFromApp = origin.contains("localhost") || origin.contains("capacitor://")
                || referer.contains("localhost") || referer.contains("capacitor://");
            if (!isFromApp && accept.contains("text/html") && !"XMLHttpRequest".equalsIgnoreCase(xReq)) {
                return RequestClass.WEB_PAGE;
            }

            // ── HLS / DASH / raw stream ────────────────────────────────────────
            if (lower.contains(".m3u8") || lower.contains(".mpd") ||
                (lower.contains(".ts") && lower.contains("segment")) ||
                lower.contains("manifest") || lower.contains("playlist")) {
                return RequestClass.MEDIA_STREAM;
            }

            // ── Radio / pure audio streams ─────────────────────────────────────
            if (isRadioStream(lower)) {
                return RequestClass.BYPASS; // hand off to native audio stack
            }

            // ── Static assets ─────────────────────────────────────────────────
            String ext = extractExtension(lower);
            Set<String> staticExts = new HashSet<>(Arrays.asList(
                "js", "css", "woff", "woff2", "ttf", "otf",
                "png", "jpg", "jpeg", "gif", "webp", "ico", "svg"
            ));
            if (staticExts.contains(ext)) return RequestClass.STATIC_ASSET;

            // ── API / XHR ─────────────────────────────────────────────────────
            if (accept.contains("application/json") || accept.contains("text/plain")
                || "XMLHttpRequest".equalsIgnoreCase(xReq)
                || lower.contains("/api/") || lower.contains("json")) {
                return RequestClass.API_CALL;
            }

            // ── Media files ───────────────────────────────────────────────────
            Set<String> mediaExts = new HashSet<>(Arrays.asList(
                "mp4", "webm", "mp3", "aac", "ogg", "opus", "wav", "flac", "m4a"
            ));
            if (mediaExts.contains(ext)) return RequestClass.MEDIA_STREAM;

            return RequestClass.UNKNOWN;
        }

        // ══════════════════════════════════════════════════════════════════════
        //  RETRY + ADAPTIVE ROUTING ENGINE
        // ══════════════════════════════════════════════════════════════════════

        private ProxyResult executeWithRetry(String url, String method,
                                             Map<String, String> reqHeaders,
                                             RequestClass cls, long reqStart) {
            String host = extractHost(url);
            HostStats stats = hostStatsMap.computeIfAbsent(host, k -> new HostStats());
            int connectTimeout = stats.bestTimeout;

            // Build a list of UA candidates — rotate if host has had failures
            List<String> uaCandidates = buildUACandidates(host, stats);

            Exception lastEx = null;
            for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
                String ua = uaCandidates.get(attempt % uaCandidates.size());
                long attemptStart = System.currentTimeMillis();

                try {
                    ProxyResult result = executeSingleRequest(
                        url, method, reqHeaders, cls, ua,
                        connectTimeout + (attempt * 3_000), // timeout escalation
                        attempt
                    );

                    if (result != null) {
                        long latency = System.currentTimeMillis() - attemptStart;
                        stats.recordSuccess(latency);

                        if (attempt > 0) {
                            Log.i(TAG, "✓ Success on attempt " + (attempt + 1)
                                + " for " + host + " (" + latency + "ms)");
                        }
                        return result;
                    }

                } catch (Exception e) {
                    lastEx = e;
                    stats.recordFailure();
                    Log.w(TAG, "Attempt " + (attempt + 1) + " failed for "
                        + host + ": " + e.getMessage());

                    // Exponential back-off: 50ms, 200ms, 500ms
                    try {
                        Thread.sleep((long)(50 * Math.pow(4, attempt)));
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            // All retries failed
            if (lastEx != null) {
                Log.e(TAG, "All retries exhausted for " + url, lastEx);
            }

            // If host is chronically unreliable, add to auto-blocked
            if (stats.reliability() < 0.1 && stats.failureCount.get() > 5) {
                autoBlockedPatterns.add(host);
                Log.w(TAG, "Auto-blocking unreliable host: " + host);
            }

            return null;
        }

        // ══════════════════════════════════════════════════════════════════════
        //  CORE HTTP EXECUTION
        // ══════════════════════════════════════════════════════════════════════

        private ProxyResult executeSingleRequest(String url, String method,
                                                 Map<String, String> reqHeaders,
                                                 RequestClass cls, String ua,
                                                 int connectTimeout, int attempt)
            throws IOException {

            URL targetUrl      = new URL(url);
            String host        = extractHost(url);
            int redirectCount  = 0;
            HttpURLConnection conn = null;

            // ── Redirect-following loop ────────────────────────────────────────
            while (redirectCount <= MAX_REDIRECTS) {
                conn = (HttpURLConnection) targetUrl.openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(connectTimeout);
                conn.setReadTimeout(cls == RequestClass.MEDIA_STREAM
                    ? TIMEOUT_STREAM_MS : TIMEOUT_READ_MS);
                conn.setInstanceFollowRedirects(false); // manual control
                conn.setUseCaches(false);

                // ── Apply request headers intelligently ───────────────────────
                applyRequestHeaders(conn, reqHeaders, url, ua, cls, attempt);

                // ── Restore session cookies ───────────────────────────────────
                String storedCookies = cookieJar.get(host);
                if (storedCookies != null) {
                    conn.setRequestProperty("Cookie", storedCookies);
                }

                conn.connect();
                int status = conn.getResponseCode();
                if (status < 0) status = 200;

                // ── Harvest and store cookies ─────────────────────────────────
                harvestCookies(conn, host);

                // ── Handle redirects ──────────────────────────────────────────
                if (status >= 300 && status < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) break;
                    location = resolveRedirect(targetUrl.toString(), location);
                    targetUrl = new URL(location);
                    host = extractHost(location);
                    redirectCount++;
                    conn.disconnect();
                    Log.d(TAG, "Redirect [" + redirectCount + "] → " + location);
                    continue;
                }

                // ── Determine MIME + charset ───────────────────────────────────
                String contentType   = normalizeContentType(conn.getContentType(), url);
                String mimeType      = extractMime(contentType);
                String charset       = extractCharset(contentType, conn, url);

                // ── Decompress if needed ───────────────────────────────────────
                String encoding      = conn.getHeaderField("Content-Encoding");
                InputStream rawStream = status >= 400
                    ? conn.getErrorStream() : conn.getInputStream();
                if (rawStream == null) rawStream = new ByteArrayInputStream(new byte[0]);
                InputStream decoded   = decodeStream(rawStream, encoding);

                // ── Collect response headers for pass-through ──────────────────
                Map<String, String> respHeaders = collectResponseHeaders(conn, status);

                // ── Stream or buffer based on content type ─────────────────────
                if (shouldStream(mimeType, cls)) {
                    // True streaming: don't buffer
                    Log.d(TAG, "STREAM → " + url);
                    final HttpURLConnection finalConn = conn;
                    return new ProxyResult(decoded, mimeType, charset,
                        status, respHeaders, false);
                }

                // ── Buffer for cache + processing ──────────────────────────────
                byte[] body = readWithLimit(decoded, CACHE_MAX_BODY_BYTES);
                conn.disconnect();

                // ── Post-process body (e.g., rewrite M3U8 URLs) ────────────────
                if (mimeType.contains("mpegurl") || mimeType.contains("x-mpegurl")) {
                    body = rewriteM3U8(body, targetUrl.toString());
                }

                Log.d(TAG, "PROXY [" + status + "] " + mimeType
                    + " " + body.length + "B → " + url);

                return new ProxyResult(
                    new ByteArrayInputStream(body), mimeType, charset,
                    status, respHeaders, false
                );
            }

            if (conn != null) conn.disconnect();
            return null;
        }

        // ══════════════════════════════════════════════════════════════════════
        //  HEADER INTELLIGENCE ENGINE
        // ══════════════════════════════════════════════════════════════════════

        private void applyRequestHeaders(HttpURLConnection conn,
                                         Map<String, String> reqHeaders,
                                         String url, String ua,
                                         RequestClass cls, int attempt) {
            String host = extractHost(url);
            String origin = "https://" + host;
            String referer = extractBaseUrl(url) + "/";

            // ── Selectively forward original headers ───────────────────────────
            if (reqHeaders != null) {
                Set<String> skipHeaders = new HashSet<>(Arrays.asList(
                    "host", "origin", "referer", "user-agent",
                    "accept-encoding", "connection", "pragma",
                    "cache-control", "if-none-match", "if-modified-since"
                ));
                for (Map.Entry<String, String> h : reqHeaders.entrySet()) {
                    if (!skipHeaders.contains(h.getKey().toLowerCase(Locale.ROOT))) {
                        conn.setRequestProperty(h.getKey(), h.getValue());
                    }
                }
            }

            // ── Core browser identity headers ─────────────────────────────────
            conn.setRequestProperty("User-Agent", ua);
            conn.setRequestProperty("Origin", origin);
            conn.setRequestProperty("Referer", referer);
            conn.setRequestProperty("Host", host);

            // ── Accept-Encoding: always request compressed content ─────────────
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate, br");

            // ── Connection management ─────────────────────────────────────────
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setRequestProperty("Keep-Alive", "timeout=30, max=100");

            // ── Class-specific headers ────────────────────────────────────────
            switch (cls) {
                case MEDIA_STREAM:
                    conn.setRequestProperty("Accept",
                        "application/vnd.apple.mpegurl,application/x-mpegURL,video/mp4,*/*;q=0.9");
                    if (!hasRangeHeader(reqHeaders)) {
                        conn.setRequestProperty("Range", "bytes=0-");
                    }
                    break;
                case API_CALL:
                    conn.setRequestProperty("Accept",
                        "application/json,text/plain,*/*;q=0.8");
                    conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                    break;
                case STATIC_ASSET:
                    conn.setRequestProperty("Accept",
                        "text/css,*/*;q=0.1");
                    break;
                default:
                    conn.setRequestProperty("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                    break;
            }

            // ── Security/anti-bot headers ─────────────────────────────────────
            conn.setRequestProperty("Sec-Fetch-Site", "cross-site");
            conn.setRequestProperty("Sec-Fetch-Mode", "cors");
            conn.setRequestProperty("Sec-Fetch-Dest",
                cls == RequestClass.MEDIA_STREAM ? "video" : "empty");
            conn.setRequestProperty("Sec-CH-UA-Mobile", "?1");
            conn.setRequestProperty("Sec-CH-UA-Platform", "\"Android\"");
            conn.setRequestProperty("DNT", "1");
            conn.setRequestProperty("Upgrade-Insecure-Requests", "1");

            // ── On retry attempts, add anti-cache busters ──────────────────────
            if (attempt > 0) {
                conn.setRequestProperty("Pragma", "no-cache");
                conn.setRequestProperty("Cache-Control", "no-cache, no-store");
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        //  M3U8 REWRITER (fixes relative URLs in HLS playlists)
        // ══════════════════════════════════════════════════════════════════════

        private byte[] rewriteM3U8(byte[] body, String baseUrl) {
            try {
                String content = new String(body, StandardCharsets.UTF_8);
                StringBuilder out = new StringBuilder(content.length() + 512);
                URL base = new URL(baseUrl);
                String basePath = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1);

                for (String line : content.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        out.append(line).append('\n');
                    } else {
                        // Resolve relative segment URLs
                        String resolved = resolveRedirect(baseUrl, trimmed);
                        out.append(resolved).append('\n');
                    }
                }
                return out.toString().getBytes(StandardCharsets.UTF_8);
            } catch (Exception e) {
                return body; // return original if rewriting fails
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        //  CONTENT DECODING ENGINE
        // ══════════════════════════════════════════════════════════════════════

        private InputStream decodeStream(InputStream raw, String encoding) {
            if (encoding == null) return raw;
            try {
                if (GZIP_PATTERN.matcher(encoding).find()) {
                    return new GZIPInputStream(raw, BUFFER_SIZE);
                }
                if (DEFLATE_PATTERN.matcher(encoding).find()) {
                    return new InflaterInputStream(raw);
                }
            } catch (IOException e) {
                Log.w(TAG, "Stream decode failed, returning raw: " + e.getMessage());
            }
            return raw;
        }

        // ══════════════════════════════════════════════════════════════════════
        //  CHARSET DETECTION ENGINE
        // ══════════════════════════════════════════════════════════════════════

        private String extractCharset(String contentType, HttpURLConnection conn, String url) {
            // 1. From Content-Type header
            if (contentType != null) {
                Matcher m = CHARSET_PATTERN.matcher(contentType);
                if (m.find()) {
                    String cs = m.group(1).trim();
                    if (Charset.isSupported(cs)) return cs;
                }
            }
            // 2. From Content-Language header
            String lang = conn.getHeaderField("Content-Language");
            if (lang != null && (lang.contains("ar") || lang.contains("fa") || lang.contains("ur"))) {
                return "UTF-8"; // Arabic / Farsi / Urdu → always UTF-8
            }
            // 3. URL-based heuristic (Arabic domains)
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.contains(".ar") || lower.contains("arabic") || lower.contains("arab")) {
                return "UTF-8";
            }
            // 4. Default
            return "utf-8";
        }

        // ══════════════════════════════════════════════════════════════════════
        //  MIME TYPE INTELLIGENCE
        // ══════════════════════════════════════════════════════════════════════

        private String normalizeContentType(String serverType, String url) {
            String urlMime = detectMimeFromUrl(url);

            // Server said octet-stream → trust URL extension instead
            if (serverType == null || serverType.isEmpty()
                || serverType.contains("octet-stream")
                || serverType.contains("binary")) {
                return urlMime;
            }

            // For known media types, prefer URL-based detection (more reliable)
            Set<String> mediaMimes = new HashSet<>(Arrays.asList(
                "video/", "audio/", "application/vnd.apple", "application/dash"
            ));
            for (String mm : mediaMimes) {
                if (urlMime.startsWith(mm)) return urlMime;
            }

            return serverType;
        }

        private String extractMime(String contentType) {
            if (contentType == null) return "application/octet-stream";
            return contentType.contains(";")
                ? contentType.split(";")[0].trim()
                : contentType.trim();
        }

        private String detectMimeFromUrl(String url) {
            String lower = url.toLowerCase(Locale.ROOT).split("[?#]")[0];
            int dot = lower.lastIndexOf('.');
            if (dot >= 0) {
                String ext = lower.substring(dot + 1);
                String mime = MIME_MAP.get(ext);
                if (mime != null) return mime;
            }
            // Path-based detection for extensionless streams
            if (lower.contains("stream") || lower.contains("live"))
                return "application/vnd.apple.mpegurl";
            if (lower.contains(".json") || lower.contains("/json"))
                return "application/json";
            return "application/octet-stream";
        }

        private String extractExtension(String url) {
            String clean = url.split("[?#]")[0];
            int dot = clean.lastIndexOf('.');
            if (dot >= 0 && dot < clean.length() - 1) {
                return clean.substring(dot + 1).toLowerCase(Locale.ROOT);
            }
            return "";
        }

        // ══════════════════════════════════════════════════════════════════════
        //  COOKIE MANAGEMENT ENGINE
        // ══════════════════════════════════════════════════════════════════════

        private void harvestCookies(HttpURLConnection conn, String host) {
            try {
                Map<String, List<String>> headerFields = conn.getHeaderFields();
                List<String> setCookies = headerFields.get("Set-Cookie");
                if (setCookies == null) setCookies = headerFields.get("set-cookie");
                if (setCookies == null || setCookies.isEmpty()) return;

                StringBuilder cookieBuilder = new StringBuilder();
                String existing = cookieJar.get(host);
                if (existing != null) cookieBuilder.append(existing).append("; ");

                for (String cookie : setCookies) {
                    if (cookie == null) continue;
                    // Extract only name=value, skip attributes
                    String nameValue = cookie.split(";")[0].trim();
                    if (!nameValue.isEmpty()) {
                        cookieBuilder.append(nameValue).append("; ");
                    }
                }

                String result = cookieBuilder.toString().trim();
                if (result.endsWith(";")) result = result.substring(0, result.length() - 1);
                if (!result.isEmpty()) {
                    cookieJar.put(host, result);
                }
            } catch (Exception e) {
                // Non-critical
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        //  RESPONSE HEADER COLLECTION
        // ══════════════════════════════════════════════════════════════════════

        private Map<String, String> collectResponseHeaders(HttpURLConnection conn, int status) {
            Map<String, String> headers = new HashMap<>();

            // ── CORS headers (always injected) ─────────────────────────────────
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Allow-Methods",
                "GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS");
            headers.put("Access-Control-Allow-Headers",
                "Content-Type, Authorization, Range, Accept, Origin, "
                + "X-Requested-With, Referer, Accept-Encoding, Accept-Language");
            headers.put("Access-Control-Expose-Headers",
                "Content-Length, Content-Range, Content-Type, Accept-Ranges, ETag, "
                + "Last-Modified, X-Total-Count, X-Page-Count");
            headers.put("Access-Control-Allow-Credentials", "true");
            headers.put("Access-Control-Max-Age", "86400");

            // ── Stream support ────────────────────────────────────────────────
            headers.put("Accept-Ranges", "bytes");
            headers.put("Cross-Origin-Resource-Policy", "cross-origin");
            headers.put("Cross-Origin-Embedder-Policy", "unsafe-none");
            headers.put("Cross-Origin-Opener-Policy", "unsafe-none");
            headers.put("X-Frame-Options", "ALLOWALL");

            // ── Passthrough useful headers ─────────────────────────────────────
            String[] passthrough = {
                "Content-Length", "Content-Range", "Content-Encoding",
                "ETag", "Last-Modified", "Cache-Control", "Expires",
                "X-RateLimit-Limit", "X-RateLimit-Remaining"
            };
            for (String h : passthrough) {
                String val = conn.getHeaderField(h);
                if (val != null) headers.put(h, val);
            }

            // ── Custom proxy identification ────────────────────────────────────
            headers.put("X-Proxy", "YallaGoal-UltraProxy/5.0");
            headers.put("X-Proxy-Status", String.valueOf(status));
            headers.put("Timing-Allow-Origin", "*");

            return headers;
        }

        // ══════════════════════════════════════════════════════════════════════
        //  CACHING ENGINE
        // ══════════════════════════════════════════════════════════════════════

        private String buildCacheKey(String url, String method,
                                     Map<String, String> headers) {
            String range = headers != null ? headers.getOrDefault("Range", "") : "";
            // Normalize URL for cache key (strip tracking params)
            String clean = url.replaceAll("[?&](?:_|cb|t|ts|rand|random|cachebust)=[^&]*", "");
            return method + ":" + clean + ":r=" + range;
        }

        private boolean isCacheable(String method, int status, String mime, RequestClass cls) {
            if (!"GET".equals(method)) return false;
            if (status != 200 && status != 206) return false;
            if (cls == RequestClass.API_CALL) return true;
            if (cls == RequestClass.STATIC_ASSET) return true;
            // Don't cache live streams
            if (mime.contains("mpegurl") || mime.contains("x-mpegURL")) return false;
            if (mime.contains("mp2t")) return false;
            return true;
        }

        private void cacheResult(String key, ProxyResult result, RequestClass cls) {
            if (!(result.stream instanceof ByteArrayInputStream)) return;
            ByteArrayInputStream bais = (ByteArrayInputStream) result.stream;
            byte[] body;
            try {
                bais.reset();
                body = readWithLimit(bais, CACHE_MAX_BODY_BYTES);
                bais.reset();
            } catch (IOException e) { return; }

            long ttl = cls == RequestClass.STATIC_ASSET
                ? CACHE_STATIC_TTL_MS : CACHE_ENTRY_TTL_MS;
            String etag = result.headers.getOrDefault("ETag", null);
            String lastMod = result.headers.getOrDefault("Last-Modified", null);

            synchronized (responseCache) {
                responseCache.put(key, new CacheEntry(
                    body, result.mimeType, result.charset,
                    result.headers, ttl, etag, lastMod, result.status
                ));
            }
        }

        private WebResourceResponse buildCachedResponse(CacheEntry entry) {
            Map<String, String> headers = new HashMap<>(entry.headers);
            headers.put("X-Proxy-Cache", "HIT");
            headers.put("Age", String.valueOf(
                (System.currentTimeMillis() - entry.createdAt) / 1000));
            return new WebResourceResponse(
                entry.mimeType, entry.charset, entry.status,
                statusReason(entry.status), headers,
                new ByteArrayInputStream(entry.body)
            );
        }

        // ══════════════════════════════════════════════════════════════════════
        //  RESPONSE BUILDERS
        // ══════════════════════════════════════════════════════════════════════

        private WebResourceResponse buildWebResourceResponse(ProxyResult result) {
            Map<String, String> headers = new HashMap<>(result.headers);
            headers.put("X-Proxy-Cache", result.fromCache ? "HIT" : "MISS");
            return new WebResourceResponse(
                result.mimeType, result.charset,
                result.status, statusReason(result.status),
                headers, result.stream
            );
        }

        private WebResourceResponse buildPreflightResponse() {
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Access-Control-Allow-Methods",
                "GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS");
            h.put("Access-Control-Allow-Headers", "*");
            h.put("Access-Control-Max-Age", "86400");
            h.put("Content-Length", "0");
            return new WebResourceResponse(
                "text/plain", "utf-8", 204, "No Content",
                h, new ByteArrayInputStream(new byte[0])
            );
        }

        private WebResourceResponse buildErrorResponse(int status, String message) {
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            h.put("X-Proxy-Error", message);
            byte[] body = message.getBytes(StandardCharsets.UTF_8);
            return new WebResourceResponse(
                "text/plain", "utf-8", status, statusReason(status),
                h, new ByteArrayInputStream(body)
            );
        }

        // ══════════════════════════════════════════════════════════════════════
        //  UTILITY HELPERS
        // ══════════════════════════════════════════════════════════════════════

        private List<String> buildUACandidates(String host, HostStats stats) {
            List<String> list = new ArrayList<>();
            // Primary: random UA based on host hash for consistency
            int idx = Math.abs(host.hashCode()) % USER_AGENTS.length;
            list.add(USER_AGENTS[idx]);
            // Fallbacks: other UAs in rotation
            for (int i = 1; i < USER_AGENTS.length; i++) {
                list.add(USER_AGENTS[(idx + i) % USER_AGENTS.length]);
            }
            return list;
        }

        private boolean isAutoBlocked(String url) {
            String host = extractHost(url);
            return autoBlockedPatterns.contains(host);
        }

        private boolean isNativeStreamable(String url, Map<String, String> headers) {
            // If the request has a Range header for streaming, let native handle it
            // unless it's coming from our app (to avoid HLS.js interception conflicts)
            return false; // We handle all media to ensure CORS
        }

        private boolean shouldStream(String mimeType, RequestClass cls) {
            // True streaming: push bytes directly without buffering
            return mimeType.contains("mp2t")
                || mimeType.contains("mpegurl")
                || mimeType.contains("dash");
        }

        private boolean isRadioStream(String lower) {
            return (lower.contains("icecast") || lower.contains("shoutcast")
                    || lower.contains("radio") || lower.contains("streamlive")
                    || lower.contains(":8000/") || lower.contains(":8002/")
                    || lower.contains(":8080/stream") || lower.contains("/stream.mp3")
                    || lower.contains("/live.mp3"))
                   && !lower.contains(".m3u8") && !lower.contains(".ts");
        }

        private boolean hasRangeHeader(Map<String, String> headers) {
            if (headers == null) return false;
            return headers.containsKey("Range") || headers.containsKey("range");
        }

        private String extractHost(String url) {
            try {
                URL u = new URL(url);
                return u.getHost();
            } catch (Exception e) {
                return url;
            }
        }

        private String extractBaseUrl(String url) {
            try {
                URL u = new URL(url);
                return u.getProtocol() + "://" + u.getHost();
            } catch (Exception e) {
                return "";
            }
        }

        private String resolveRedirect(String base, String location) {
            try {
                if (location.startsWith("http://") || location.startsWith("https://")) {
                    return location;
                }
                URL baseUrl = new URL(base);
                return new URL(baseUrl, location).toString();
            } catch (MalformedURLException e) {
                return location;
            }
        }

        private byte[] readWithLimit(InputStream in, int maxBytes) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[BUFFER_SIZE];
            int total = 0, n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) {
                    // Exceeded limit — return what we have
                    baos.write(buf, 0, n);
                    break;
                }
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }

        private String statusReason(int status) {
            switch (status) {
                case 200: return "OK";
                case 204: return "No Content";
                case 206: return "Partial Content";
                case 301: return "Moved Permanently";
                case 302: return "Found";
                case 304: return "Not Modified";
                case 400: return "Bad Request";
                case 401: return "Unauthorized";
                case 403: return "Forbidden";
                case 404: return "Not Found";
                case 429: return "Too Many Requests";
                case 500: return "Internal Server Error";
                case 502: return "Bad Gateway";
                case 503: return "Service Unavailable";
                default:  return "Unknown";
            }
        }

        private WebResourceResponse buildFullCorsHeaders(InputStream is, String mimeType,
                                                         String charset, int status,
                                                         Map<String, String> extra) {
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS");
            h.put("Access-Control-Allow-Headers", "*");
            h.put("Access-Control-Expose-Headers", "*");
            h.put("Access-Control-Allow-Credentials", "true");
            h.put("Accept-Ranges", "bytes");
            h.put("X-Frame-Options", "ALLOWALL");
            if (extra != null) h.putAll(extra);
            if (is == null) is = new ByteArrayInputStream(new byte[0]);
            return new WebResourceResponse(mimeType, charset, status,
                statusReason(status), h, is);
        }
    }
}
