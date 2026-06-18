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

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private FrameLayout customViewContainer;

    // ═══════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════════
    //  FULLSCREEN
    // ═══════════════════════════════════════════════════════════════════

    private void applyFullscreen() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
        } else {
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

    // ═══════════════════════════════════════════════════════════════════
    //  WEBVIEW SETUP
    // ═══════════════════════════════════════════════════════════════════

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
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                    decorView.addView(customViewContainer, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
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
                android.webkit.CookieManager cm =
                    android.webkit.CookieManager.getInstance();
                cm.setAcceptCookie(true);
                cm.setAcceptThirdPartyCookies(wv, true);
            }

            getBridge().setWebViewClient(new UltraProxyClient(getBridge()));
            Log.i(TAG, "══ يلا گول ULTRA PROXY ENGINE v5.0 ACTIVE ══");
        } catch (Exception e) {
            Log.e(TAG, "setupWebView error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ██████████████████████████████████████████████████████████████████████
    //  ██                                                                  ██
    //  ██        U L T R A   P R O X Y   C L I E N T   v 5 . 0          ██
    //  ██     أقوى وأذكى بروكسي في تاريخ تطبيقات الأندرويد              ██
    //  ██                                                                  ██
    //  ██████████████████████████████████████████████████████████████████████
    // ═══════════════════════════════════════════════════════════════════════════

    class UltraProxyClient extends BridgeWebViewClient {

        // ── Constants ──────────────────────────────────────────────────────────
        private static final int  TIMEOUT_CONNECT_MS    = 12_000;
        private static final int  TIMEOUT_READ_MS       = 20_000;
        private static final int  TIMEOUT_STREAM_MS     = 30_000;
        private static final int  MAX_REDIRECTS         = 12;
        private static final int  MAX_RETRY_ATTEMPTS    = 3;
        private static final long CACHE_VOLATILE_TTL    = 8_000;
        private static final long CACHE_STATIC_TTL      = 180_000;
        private static final int  CACHE_MAX_BODY_BYTES  = 524_288; // 512 KB
        private static final int  CACHE_MAX_ENTRIES     = 120;
        private static final int  BUFFER_SIZE           = 32_768;  // 32 KB

        // ── Intelligent LRU Cache ──────────────────────────────────────────────
        private final LinkedHashMap<String, CacheEntry> responseCache =
            new LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> e) {
                    return size() > CACHE_MAX_ENTRIES;
                }
            };

        // ── Per-host adaptive statistics ───────────────────────────────────────
        private final ConcurrentHashMap<String, HostStats> hostStatsMap =
            new ConcurrentHashMap<>();

        // ── Auto-learned block list ────────────────────────────────────────────
        private final Set<String> autoBlockedHosts =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

        // ── Cookie jar (session persistence across requests) ───────────────────
        private final ConcurrentHashMap<String, String> cookieJar =
            new ConcurrentHashMap<>();

        // ── User-Agent rotation pool ───────────────────────────────────────────
        private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.119 Mobile Safari/537.36 SamsungBrowser/23.0",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.3 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (Linux; Android 14; OnePlus 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2.1 Safari/605.1.15",
        };

        // ── MIME type registry ─────────────────────────────────────────────────
        private static final Map<String, String> MIME_MAP;
        static {
            Map<String, String> m = new HashMap<>();
            m.put("m3u8", "application/vnd.apple.mpegurl");
            m.put("ts",   "video/mp2t");
            m.put("mp4",  "video/mp4");
            m.put("webm", "video/webm");
            m.put("mkv",  "video/x-matroska");
            m.put("avi",  "video/x-msvideo");
            m.put("mov",  "video/quicktime");
            m.put("flv",  "video/x-flv");
            m.put("3gp",  "video/3gpp");
            m.put("mp3",  "audio/mpeg");
            m.put("aac",  "audio/aac");
            m.put("ogg",  "audio/ogg");
            m.put("opus", "audio/opus");
            m.put("wav",  "audio/wav");
            m.put("flac", "audio/flac");
            m.put("m4a",  "audio/mp4");
            m.put("weba", "audio/webm");
            m.put("m3u",  "audio/x-mpegurl");
            m.put("mpd",  "application/dash+xml");
            m.put("f4m",  "application/f4m+xml");
            m.put("xml",  "application/xml");
            m.put("js",   "application/javascript");
            m.put("json", "application/json");
            m.put("css",  "text/css");
            m.put("html", "text/html");
            m.put("htm",  "text/html");
            m.put("txt",  "text/plain");
            m.put("svg",  "image/svg+xml");
            m.put("png",  "image/png");
            m.put("jpg",  "image/jpeg");
            m.put("jpeg", "image/jpeg");
            m.put("gif",  "image/gif");
            m.put("webp", "image/webp");
            m.put("ico",  "image/x-icon");
            m.put("woff", "font/woff");
            m.put("woff2","font/woff2");
            m.put("ttf",  "font/ttf");
            MIME_MAP = Collections.unmodifiableMap(m);
        }

        // ── Domains that should never be proxied ───────────────────────────────
        private static final Set<String> PASSTHROUGH_DOMAINS =
            new HashSet<>(Arrays.asList(
                "localhost", "127.0.0.1", "capacitor",
                "fonts.googleapis.com", "fonts.gstatic.com",
                "cdn.jsdelivr.net", "cdnjs.cloudflare.com",
                "unpkg.com", "ajax.googleapis.com"
            ));

        // ── Regex patterns ─────────────────────────────────────────────────────
        private static final Pattern CHARSET_PATTERN =
            Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);
        private static final Pattern GZIP_PATTERN =
            Pattern.compile("gzip", Pattern.CASE_INSENSITIVE);
        private static final Pattern DEFLATE_PATTERN =
            Pattern.compile("deflate", Pattern.CASE_INSENSITIVE);

        // ── Counters ───────────────────────────────────────────────────────────
        private final AtomicLong totalReqs    = new AtomicLong(0);
        private final AtomicLong proxiedReqs  = new AtomicLong(0);
        private final AtomicLong cacheHits    = new AtomicLong(0);
        private final AtomicLong bypassedReqs = new AtomicLong(0);

        // ══════════════════════════════════════════════════════════════════════
        //  INNER CLASSES
        // ══════════════════════════════════════════════════════════════════════

        static class CacheEntry {
            final byte[]              body;
            final String              mimeType;
            final String              charset;
            final Map<String, String> headers;
            final long                createdAt;
            final long                ttl;
            final int                 status;

            CacheEntry(byte[] body, String mimeType, String charset,
                       Map<String, String> headers, long ttl, int status) {
                this.body      = body;
                this.mimeType  = mimeType;
                this.charset   = charset;
                this.headers   = headers;
                this.createdAt = System.currentTimeMillis();
                this.ttl       = ttl;
                this.status    = status;
            }

            boolean isExpired() {
                return (System.currentTimeMillis() - createdAt) > ttl;
            }
        }

        static class HostStats {
            final AtomicLong successCount = new AtomicLong(0);
            final AtomicLong failureCount = new AtomicLong(0);
            final AtomicLong totalLatency = new AtomicLong(0);
            volatile int bestTimeout = TIMEOUT_CONNECT_MS;

            void recordSuccess(long latencyMs) {
                successCount.incrementAndGet();
                totalLatency.addAndGet(latencyMs);
                long avg = totalLatency.get() / successCount.get();
                bestTimeout = (int) Math.max(4_000, Math.min(avg * 3, TIMEOUT_CONNECT_MS));
            }

            void recordFailure() { failureCount.incrementAndGet(); }

            double reliability() {
                long total = successCount.get() + failureCount.get();
                return total == 0 ? 1.0 : (double) successCount.get() / total;
            }
        }

        enum RequestClass {
            BYPASS, PREFLIGHT, STATIC_ASSET, API_CALL,
            MEDIA_STREAM, WEB_PAGE, UNKNOWN
        }

        static class ProxyResult {
            final InputStream         stream;
            final String              mimeType;
            final String              charset;
            final int                 status;
            final Map<String, String> headers;

            ProxyResult(InputStream stream, String mimeType, String charset,
                        int status, Map<String, String> headers) {
                this.stream   = stream;
                this.mimeType = mimeType;
                this.charset  = charset;
                this.status   = status;
                this.headers  = headers;
            }
        }

        // ══════════════════════════════════════════════════════════════════════

        UltraProxyClient(com.getcapacitor.Bridge b) { super(b); }

        // ══════════════════════════════════════════════════════════════════════
        //  MAIN INTERCEPT ENTRY POINT
        // ══════════════════════════════════════════════════════════════════════

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView wv,
                                                          WebResourceRequest req) {
            String url    = req.getUrl().toString();
            String method = req.getMethod() != null
                ? req.getMethod().toUpperCase(Locale.ROOT) : "GET";
            Map<String, String> rh = req.getRequestHeaders();
            totalReqs.incrementAndGet();

            try {
                // 1. OPTIONS preflight → instant 204
                if ("OPTIONS".equals(method)) return buildPreflight();

                // 2. Classify request
                RequestClass cls = classify(url, method, rh);

                // 3. Hard bypass
                if (cls == RequestClass.BYPASS || cls == RequestClass.WEB_PAGE) {
                    bypassedReqs.incrementAndGet();
                    return super.shouldInterceptRequest(wv, req);
                }

                // 4. Auto-blocked host check
                if (autoBlockedHosts.contains(extractHost(url)))
                    return buildError(403, "Blocked by adaptive filter");

                // 5. Cache lookup
                String cacheKey = buildCacheKey(url, method, rh);
                synchronized (responseCache) {
                    CacheEntry cached = responseCache.get(cacheKey);
                    if (cached != null && !cached.isExpired()) {
                        cacheHits.incrementAndGet();
                        return fromCache(cached);
                    }
                    if (cached != null) responseCache.remove(cacheKey);
                }

                // 6. Execute with retry intelligence
                proxiedReqs.incrementAndGet();
                ProxyResult result = executeWithRetry(url, method, rh, cls);
                if (result == null) return super.shouldInterceptRequest(wv, req);

                // 7. Cache if eligible
                if (isCacheable(method, result.status, result.mimeType, cls))
                    storeCache(cacheKey, result, cls);

                return toResponse(result);

            } catch (Exception e) {
                Log.e(TAG, "Intercept error: " + url, e);
                return super.shouldInterceptRequest(wv, req);
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        //  REQUEST CLASSIFICATION ENGINE
        // ══════════════════════════════════════════════════════════════════════

        private RequestClass classify(String url, String method,
                                      Map<String, String> headers) {
            String lo     = url.toLowerCase(Locale.ROOT);
            String accept = headers != null ? headers.getOrDefault("Accept", "") : "";
            String origin = headers != null ? headers.getOrDefault("Origin", "") : "";
            String ref    = headers != null ? headers.getOrDefault("Referer", "") : "";
            String xrw    = headers != null
                ? headers.getOrDefault("X-Requested-With", "") : "";

            if (!lo.startsWith("http://") && !lo.startsWith("https://"))
                return RequestClass.BYPASS;

            for (String pd : PASSTHROUGH_DOMAINS)
                if (lo.contains(pd)) return RequestClass.BYPASS;

            if (lo.contains("capacitor://")) return RequestClass.BYPASS;

            boolean fromApp = origin.contains("localhost")
                || origin.contains("capacitor://")
                || ref.contains("localhost") || ref.contains("capacitor://");

            if (!fromApp && accept.contains("text/html")
                && !"XMLHttpRequest".equalsIgnoreCase(xrw))
                return RequestClass.WEB_PAGE;

            // HLS / DASH / manifests
            if (lo.contains(".m3u8") || lo.contains(".mpd")
                || (lo.contains(".ts") && lo.contains("seg"))
                || lo.contains("manifest") || lo.contains("playlist"))
                return RequestClass.MEDIA_STREAM;

            // Pure radio/audio streams → bypass to native audio
            if (isRadioStream(lo)) return RequestClass.BYPASS;

            String ext = ext(lo);
            if (isStaticExt(ext)) return RequestClass.STATIC_ASSET;

            if (accept.contains("application/json") || accept.contains("text/plain")
                || "XMLHttpRequest".equalsIgnoreCase(xrw)
                || lo.contains("/api/") || lo.contains("json"))
                return RequestClass.API_CALL;

            if (isMediaExt(ext)) return RequestClass.MEDIA_STREAM;

            return RequestClass.UNKNOWN;
        }

        // ══════════════════════════════════════════════════════════════════════
        //  RETRY + ADAPTIVE ROUTING ENGINE
        // ══════════════════════════════════════════════════════════════════════

        private ProxyResult executeWithRetry(String url, String method,
                                             Map<String, String> rh,
                                             RequestClass cls) {
            String host    = extractHost(url);
            HostStats stats = hostStatsMap.computeIfAbsent(host, k -> new HostStats());
            List<String> uas = buildUACandidates(host);
            Exception lastEx = null;

            for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
                String ua  = uas.get(attempt % uas.size());
                int timeout = stats.bestTimeout + (attempt * 4_000);
                long t0     = System.currentTimeMillis();

                try {
                    ProxyResult r = doRequest(url, method, rh, cls, ua, timeout, attempt);
                    if (r != null) {
                        stats.recordSuccess(System.currentTimeMillis() - t0);
                        if (attempt > 0)
                            Log.i(TAG, "✓ OK attempt " + (attempt + 1) + " → " + host);
                        return r;
                    }
                } catch (Exception e) {
                    lastEx = e;
                    stats.recordFailure();
                    Log.w(TAG, "✗ Attempt " + (attempt + 1) + " failed [" + host + "]: "
                        + e.getMessage());
                    try {
                        Thread.sleep((long)(50 * Math.pow(4, attempt)));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (stats.reliability() < 0.1 && stats.failureCount.get() > 5) {
                autoBlockedHosts.add(host);
                Log.w(TAG, "Auto-blocking chronically unreliable host: " + host);
            }
            if (lastEx != null) Log.e(TAG, "All retries exhausted → " + url, lastEx);
            return null;
        }

        // ══════════════════════════════════════════════════════════════════════
        //  CORE HTTP EXECUTION + MANUAL REDIRECT FOLLOWING
        // ══════════════════════════════════════════════════════════════════════

        private ProxyResult doRequest(String rawUrl, String method,
                                      Map<String, String> rh,
                                      RequestClass cls, String ua,
                                      int connectTimeout, int attempt)
            throws IOException {

            URL target         = new URL(rawUrl);
            String currentHost = extractHost(rawUrl);
            int redirectCount  = 0;

            while (redirectCount <= MAX_REDIRECTS) {
                HttpURLConnection conn =
                    (HttpURLConnection) target.openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(connectTimeout);
                conn.setReadTimeout(
                    cls == RequestClass.MEDIA_STREAM
                        ? TIMEOUT_STREAM_MS : TIMEOUT_READ_MS);
                conn.setInstanceFollowRedirects(false);
                conn.setUseCaches(false);

                // Apply smart headers
                applyHeaders(conn, rh, target.toString(), ua, cls, attempt, currentHost);

                // Restore cookies
                String cookies = cookieJar.get(currentHost);
                if (cookies != null) conn.setRequestProperty("Cookie", cookies);

                conn.connect();
                int status = conn.getResponseCode();
                if (status < 0) status = 200;

                harvestCookies(conn, currentHost);

                // Manual redirect handling
                if (status >= 300 && status < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null || loc.isEmpty()) break;
                    loc = resolveUrl(target.toString(), loc);
                    target      = new URL(loc);
                    currentHost = extractHost(loc);
                    redirectCount++;
                    Log.d(TAG, "→ Redirect [" + redirectCount + "] " + loc);
                    continue;
                }

                // Decode content
                String encoding  = conn.getHeaderField("Content-Encoding");
                String rawCT     = conn.getContentType();
                String mimeType  = smartMime(rawCT, target.toString());
                String charset   = smartCharset(rawCT, conn);

                InputStream raw = status >= 400
                    ? conn.getErrorStream() : conn.getInputStream();
                if (raw == null) raw = new ByteArrayInputStream(new byte[0]);
                InputStream decoded = decodeStream(raw, encoding);

                Map<String, String> respHeaders = buildRespHeaders(conn, status);

                // True streaming for HLS/DASH segments (no buffering)
                if (isLiveStream(mimeType)) {
                    Log.d(TAG, "STREAM ← [" + status + "] " + target);
                    return new ProxyResult(decoded, mimeType, charset,
                        status, respHeaders);
                }

                // Buffer for processing + caching
                byte[] body = readLimited(decoded, CACHE_MAX_BODY_BYTES);
                conn.disconnect();

                // Rewrite M3U8 relative URLs
                if (mimeType.contains("mpegurl") || mimeType.contains("x-mpegURL")) {
                    body = rewriteM3U8(body, target.toString());
                }

                Log.d(TAG, "PROXY ← [" + status + "] " + mimeType
                    + " " + body.length + "B ← " + target);

                return new ProxyResult(
                    new ByteArrayInputStream(body), mimeType, charset,
                    status, respHeaders
                );
            }
            return null;
        }

        // ══════════════════════════════════════════════════════════════════════
        //  HEADER INTELLIGENCE ENGINE
        // ══════════════════════════════════════════════════════════════════════

        private void applyHeaders(HttpURLConnection conn, Map<String, String> rh,
                                   String url, String ua, RequestClass cls,
                                   int attempt, String host) {
            // Forward original headers (selective)
            if (rh != null) {
                Set<String> skip = new HashSet<>(Arrays.asList(
                    "host", "origin", "referer", "user-agent",
                    "accept-encoding", "connection", "pragma",
                    "cache-control", "if-none-match", "if-modified-since"
                ));
                for (Map.Entry<String, String> h : rh.entrySet()) {
                    if (!skip.contains(h.getKey().toLowerCase(Locale.ROOT)))
                        conn.setRequestProperty(h.getKey(), h.getValue());
                }
            }

            // Identity + origin spoofing
            conn.setRequestProperty("User-Agent", ua);
            conn.setRequestProperty("Host", host);
            conn.setRequestProperty("Origin", "https://" + host);
            conn.setRequestProperty("Referer", "https://" + host + "/");

            // Always request compressed content
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate");

            // Connection management
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setRequestProperty("Keep-Alive", "timeout=30, max=100");

            // Class-specific Accept header
            switch (cls) {
                case MEDIA_STREAM:
                    conn.setRequestProperty("Accept",
                        "application/vnd.apple.mpegurl,application/x-mpegURL,video/mp4,*/*;q=0.9");
                    if (!hasRange(rh)) conn.setRequestProperty("Range", "bytes=0-");
                    conn.setRequestProperty("Sec-Fetch-Dest", "video");
                    break;
                case API_CALL:
                    conn.setRequestProperty("Accept",
                        "application/json,text/plain,*/*;q=0.8");
                    conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                    conn.setRequestProperty("Sec-Fetch-Dest", "empty");
                    break;
                case STATIC_ASSET:
                    conn.setRequestProperty("Accept", "text/css,*/*;q=0.1");
                    conn.setRequestProperty("Sec-Fetch-Dest", "style");
                    break;
                default:
                    conn.setRequestProperty("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                    conn.setRequestProperty("Sec-Fetch-Dest", "document");
                    break;
            }

            // Anti-bot / CORS headers
            conn.setRequestProperty("Sec-Fetch-Site", "cross-site");
            conn.setRequestProperty("Sec-Fetch-Mode", "cors");
            conn.setRequestProperty("Sec-CH-UA-Mobile", "?1");
            conn.setRequestProperty("Sec-CH-UA-Platform", "\"Android\"");
            conn.setRequestProperty("DNT", "1");
            conn.setRequestProperty("Upgrade-Insecure-Requests", "1");
            conn.setRequestProperty("Accept-Language", "ar,en-US;q=0.9,en;q=0.8");

            // Retry-specific: bust caches
            if (attempt > 0) {
                conn.setRequestProperty("Pragma", "no-cache");
                conn.setRequestProperty("Cache-Control", "no-cache, no-store");
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        //  M3U8 URL REWRITER (relative → absolute segment URLs)
        // ══════════════════════════════════════════════════════════════════════

        private byte[] rewriteM3U8(byte[] body, String baseUrl) {
            try {
                String content = new String(body, StandardCharsets.UTF_8);
                StringBuilder out = new StringBuilder(content.length() + 1024);
                for (String line : content.split("\n")) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) {
                        out.append(line).append('\n');
                    } else {
                        out.append(resolveUrl(baseUrl, t)).append('\n');
                    }
                }
                return out.toString().getBytes(StandardCharsets.UTF_8);
            } catch (Exception e) {
                return body;
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        //  COOKIE ENGINE
        // ══════════════════════════════════════════════════════════════════════

        private void harvestCookies(HttpURLConnection conn, String host) {
            try {
                Map<String, List<String>> fields = conn.getHeaderFields();
                List<String> setCookies = fields.get("Set-Cookie");
                if (setCookies == null) setCookies = fields.get("set-cookie");
                if (setCookies == null) return;

                StringBuilder sb = new StringBuilder();
                String existing = cookieJar.get(host);
                if (existing != null) sb.append(existing).append("; ");

                for (String c : setCookies) {
                    if (c == null) continue;
                    String nv = c.split(";")[0].trim();
                    if (!nv.isEmpty()) sb.append(nv).append("; ");
                }

                String result = sb.toString().trim();
                if (result.endsWith(";"))
                    result = result.substring(0, result.length() - 1);
                if (!result.isEmpty()) cookieJar.put(host, result);
            } catch (Exception ignored) { }
        }

        // ══════════════════════════════════════════════════════════════════════
        //  RESPONSE HEADER BUILDER
        // ══════════════════════════════════════════════════════════════════════

        private Map<String, String> buildRespHeaders(HttpURLConnection conn, int status) {
            Map<String, String> h = new HashMap<>();

            // Full CORS unlock
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Access-Control-Allow-Methods",
                "GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS");
            h.put("Access-Control-Allow-Headers",
                "Content-Type, Authorization, Range, Accept, "
                + "Origin, X-Requested-With, Referer");
            h.put("Access-Control-Expose-Headers",
                "Content-Length, Content-Range, Content-Type, "
                + "Accept-Ranges, ETag, Last-Modified");
            h.put("Access-Control-Allow-Credentials", "true");
            h.put("Access-Control-Max-Age", "86400");

            // Embedding unlock
            h.put("Accept-Ranges", "bytes");
            h.put("Cross-Origin-Resource-Policy", "cross-origin");
            h.put("Cross-Origin-Embedder-Policy", "unsafe-none");
            h.put("Cross-Origin-Opener-Policy", "unsafe-none");
            h.put("X-Frame-Options", "ALLOWALL");
            h.put("Timing-Allow-Origin", "*");

            // Pass through useful upstream headers
            for (String name : new String[]{
                "Content-Length", "Content-Range", "ETag",
                "Last-Modified", "Cache-Control", "Expires"
            }) {
                String val = conn.getHeaderField(name);
                if (val != null) h.put(name, val);
            }

            h.put("X-Proxy", "YallaGoal-UltraProxy/5.0");
            h.put("X-Proxy-Status", String.valueOf(status));
            return h;
        }

        // ══════════════════════════════════════════════════════════════════════
        //  CACHE ENGINE
        // ══════════════════════════════════════════════════════════════════════

        private String buildCacheKey(String url, String method,
                                     Map<String, String> rh) {
            String range = rh != null ? rh.getOrDefault("Range", "") : "";
            // Strip common tracking params
            String clean = url
                .replaceAll("[?&](?:_|cb|t|ts|rand|random|cachebust|v)=[^&]*", "")
                .replaceAll("[?&]$", "");
            return method + ":" + clean + (range.isEmpty() ? "" : ":r=" + range);
        }

        private boolean isCacheable(String method, int status,
                                    String mime, RequestClass cls) {
            if (!"GET".equals(method)) return false;
            if (status != 200 && status != 206) return false;
            // Never cache live segments
            if (mime.contains("mp2t")) return false;
            if (mime.contains("mpegurl") && cls == RequestClass.MEDIA_STREAM) return false;
            return cls == RequestClass.STATIC_ASSET || cls == RequestClass.API_CALL;
        }

        private void storeCache(String key, ProxyResult r, RequestClass cls) {
            if (!(r.stream instanceof ByteArrayInputStream)) return;
            ByteArrayInputStream bais = (ByteArrayInputStream) r.stream;
            byte[] body;
            try {
                bais.reset();
                body = readLimited(bais, CACHE_MAX_BODY_BYTES);
                bais.reset();
            } catch (IOException e) { return; }
            long ttl = cls == RequestClass.STATIC_ASSET
                ? CACHE_STATIC_TTL : CACHE_VOLATILE_TTL;
            synchronized (responseCache) {
                responseCache.put(key,
                    new CacheEntry(body, r.mimeType, r.charset, r.headers, ttl, r.status));
            }
        }

        private WebResourceResponse fromCache(CacheEntry e) {
            Map<String, String> h = new HashMap<>(e.headers);
            h.put("X-Proxy-Cache", "HIT");
            h.put("Age", String.valueOf(
                (System.currentTimeMillis() - e.createdAt) / 1000));
            return new WebResourceResponse(e.mimeType, e.charset, e.status,
                reason(e.status), h, new ByteArrayInputStream(e.body));
        }

        // ══════════════════════════════════════════════════════════════════════
        //  MIME + CHARSET INTELLIGENCE
        // ══════════════════════════════════════════════════════════════════════

        private String smartMime(String serverCT, String url) {
            String urlMime = mimeFromUrl(url);
            if (serverCT == null || serverCT.isEmpty()
                || serverCT.contains("octet-stream")
                || serverCT.contains("binary")) return urlMime;
            // For known media, trust URL more than server
            if (urlMime.startsWith("video/") || urlMime.startsWith("audio/")
                || urlMime.contains("mpegurl") || urlMime.contains("dash"))
                return urlMime;
            // Strip charset from MIME
            return serverCT.contains(";")
                ? serverCT.split(";")[0].trim() : serverCT.trim();
        }

        private String mimeFromUrl(String url) {
            String lo = url.toLowerCase(Locale.ROOT).split("[?#]")[0];
            int dot = lo.lastIndexOf('.');
            if (dot >= 0) {
                String e = lo.substring(dot + 1);
                String m = MIME_MAP.get(e);
                if (m != null) return m;
            }
            if (lo.contains("stream") || lo.contains("live"))
                return "application/vnd.apple.mpegurl";
            return "application/octet-stream";
        }

        private String smartCharset(String ct, HttpURLConnection conn) {
            if (ct != null) {
                Matcher m = CHARSET_PATTERN.matcher(ct);
                if (m.find()) {
                    String cs = m.group(1).trim();
                    if (Charset.isSupported(cs)) return cs;
                }
            }
            String lang = conn.getHeaderField("Content-Language");
            if (lang != null && (lang.contains("ar") || lang.contains("fa")))
                return "UTF-8";
            return "utf-8";
        }

        // ══════════════════════════════════════════════════════════════════════
        //  STREAM DECOMPRESSION
        // ══════════════════════════════════════════════════════════════════════

        private InputStream decodeStream(InputStream raw, String encoding) {
            if (encoding == null) return raw;
            try {
                if (GZIP_PATTERN.matcher(encoding).find())
                    return new GZIPInputStream(raw, BUFFER_SIZE);
                if (DEFLATE_PATTERN.matcher(encoding).find())
                    return new InflaterInputStream(raw);
            } catch (IOException e) {
                Log.w(TAG, "Decompress failed, returning raw");
            }
            return raw;
        }

        // ══════════════════════════════════════════════════════════════════════
        //  RESPONSE BUILDERS
        // ══════════════════════════════════════════════════════════════════════

        private WebResourceResponse toResponse(ProxyResult r) {
            Map<String, String> h = new HashMap<>(r.headers);
            h.put("X-Proxy-Cache", "MISS");
            return new WebResourceResponse(r.mimeType, r.charset,
                r.status, reason(r.status), h, r.stream);
        }

        private WebResourceResponse buildPreflight() {
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Access-Control-Allow-Methods",
                "GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS");
            h.put("Access-Control-Allow-Headers", "*");
            h.put("Access-Control-Max-Age", "86400");
            h.put("Content-Length", "0");
            return new WebResourceResponse("text/plain", "utf-8", 204,
                "No Content", h, new ByteArrayInputStream(new byte[0]));
        }

        private WebResourceResponse buildError(int status, String msg) {
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            h.put("X-Proxy-Error", msg);
            return new WebResourceResponse("text/plain", "utf-8", status,
                reason(status), h,
                new ByteArrayInputStream(msg.getBytes(StandardCharsets.UTF_8)));
        }

        // ══════════════════════════════════════════════════════════════════════
        //  UTILITY
        // ══════════════════════════════════════════════════════════════════════

        private List<String> buildUACandidates(String host) {
            List<String> list = new ArrayList<>(Arrays.asList(USER_AGENTS));
            // Deterministic primary UA per host
            int start = Math.abs(host.hashCode()) % list.size();
            Collections.rotate(list, -start);
            return list;
        }

        private boolean isRadioStream(String lo) {
            return (lo.contains("icecast") || lo.contains("shoutcast")
                || lo.contains("radio") || lo.contains("streamlive")
                || lo.contains(":8000/") || lo.contains(":8002/")
                || lo.contains("/stream.mp3") || lo.contains("/live.mp3"))
                && !lo.contains(".m3u8") && !lo.contains(".ts");
        }

        private boolean isLiveStream(String mime) {
            return mime.contains("mp2t") || mime.contains("dash+xml");
        }

        private boolean hasRange(Map<String, String> h) {
            if (h == null) return false;
            return h.containsKey("Range") || h.containsKey("range");
        }

        private boolean isStaticExt(String ext) {
            return new HashSet<>(Arrays.asList(
                "js","css","woff","woff2","ttf","otf",
                "png","jpg","jpeg","gif","webp","ico","svg"
            )).contains(ext);
        }

        private boolean isMediaExt(String ext) {
            return new HashSet<>(Arrays.asList(
                "mp4","webm","mp3","aac","ogg","opus","wav","flac","m4a","mkv"
            )).contains(ext);
        }

        private String ext(String url) {
            String clean = url.split("[?#]")[0];
            int dot = clean.lastIndexOf('.');
            return dot >= 0 && dot < clean.length() - 1
                ? clean.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        }

        private String extractHost(String url) {
            try { return new URL(url).getHost(); }
            catch (Exception e) { return url; }
        }

        private String resolveUrl(String base, String relative) {
            try {
                if (relative.startsWith("http://") || relative.startsWith("https://"))
                    return relative;
                return new URL(new URL(base), relative).toString();
            } catch (MalformedURLException e) { return relative; }
        }

        private byte[] readLimited(InputStream in, int max) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[BUFFER_SIZE];
            int total = 0, n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (total >= max) break;
            }
            return out.toByteArray();
        }

        private String reason(int code) {
            switch (code) {
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
    }
}
JAVA_EOF

        echo "==== ✓ UltraProxy v5.0 injected ===="

    - name: Configure AndroidManifest.xml
      run: |
        sed -i '/<\/manifest>/i \    <uses-permission android:name="android.permission.INTERNET" />' \
            android/app/src/main/AndroidManifest.xml
        sed -i '/<\/manifest>/i \    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />' \
            android/app/src/main/AndroidManifest.xml
        sed -i 's/<application/<application android:usesCleartextTraffic="true" android:hardwareAccelerated="true"/' \
            android/app/src/main/AndroidManifest.xml

    - name: Download & Apply App Custom Icon
      run: |
        curl -L -o app_icon.png "https://raw.githubusercontent.com/ossamasal2012/otv/main/icon.png"
        npm install -g @capacitor/assets
        mkdir -p assets
        cp app_icon.png assets/icon.png
        npx capacitor-assets generate --android

    - name: Sync Capacitor Project
      run: npx cap sync android

    - name: Build Android APK (Debug)
      run: |
        cd android
        chmod +x ./gradlew
        ./gradlew assembleDebug --no-daemon --no-build-cache

    - name: Upload APK Artifact
      uses: actions/upload-artifact@v4
      with:
        name: Yalla-Goal-Premium-APK
        path: android/app/build/outputs/apk/debug/app-debug.apk
