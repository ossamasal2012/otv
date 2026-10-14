package com.yallagoal.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * قواعد حجب الإعلانات لقنوات iframe — منطق نقي بلا أي اعتماد على Android (يُختبر على JVM عادية،
 * انظر AdBlockRulesTest). الربط الفعلي بـ WebView موجود في IframeAdBlocker.
 *
 * <h3>فلسفة القواعد: الدقة قبل الشمول</h3>
 * أي قاعدة واسعة تخاطر بكسر تشغيل قناة كانت تعمل، والمطلوب صراحةً ألا يُحجب أي طلب فيديو أو
 * صوت أو قائمة تشغيل أو مقطع أو مفتاح بث. لذلك:
 * <ol>
 *   <li>الحجب بالنطاق فقط: نطاقات شبكات إعلانية معروفة (الإعلانات المنبثقة/التحويلية والبانرات
 *       ومنصات الإعلان)، تُطابَق مع نطاقاتها الفرعية. لا قواعد مسارات عامة مثل «/ads/».</li>
 *   <li>استثناءات صارمة تُفحص قبل أي حجب: نطاقات التشغيل الضرورية (يوتيوب، CDN المشغّلات...)،
 *       نطاق صفحة القناة نفسه (first-party)، وأي طلب ينتهي مساره بامتداد وسائط
 *       (m3u8/ts/mp4/mpd/m4s/aac/key/vtt...) مهما كان نطاقه.</li>
 *   <li>جلسات يوتيوب (عبر مسار /yt-embed في الـ Worker) لا يُحجب فيها شيء إطلاقاً: يوتيوب يتعامل
 *       مع حجب طلبات إعلاناته بطرق قد تكسر التشغيل، ولا نوافذ منبثقة فيه أصلاً.</li>
 * </ol>
 *
 * <h3>حدّ صريح</h3>
 * نطاقات النوافذ المنبثقة تتبدّل باستمرار (قائمة EasyList الخاصة بها وحدها بالآلاف وتتغيّر أسبوعياً)،
 * فلا تصلح قائمة ثابتة داخل التطبيق لملاحقتها. لذلك المنبثقات والتحويلات تُمنَع هيكلياً في
 * IframeAdBlocker (ابتلاع window.open/target=_blank، وإلغاء تنقّل الإطار الرئيسي)، وهذه القائمة
 * تغطي فقط سكربتات وموارد شبكات الإعلانات المعروفة.
 */
final class AdBlockRules {

    private AdBlockRules() {}

    /** نطاقات شبكات إعلانية معروفة (النطاق نفسه وكل نطاقاته الفرعية). مراجَعة مقابل EasyList. */
    private static final Set<String> AD_DOMAINS = new HashSet<>(Arrays.asList(
            // --- جوجل وكبرى منصات المزايدة الإعلانية ---
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "googletagservices.com", "adservice.google.com", "2mdn.net",
            "adnxs.com", "adsrvr.org", "advertising.com", "adform.net", "criteo.com", "criteo.net",
            "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com", "indexww.com",
            "smartadserver.com", "amazon-adsystem.com", "moatads.com", "adsafeprotected.com",
            "yieldmo.com", "sharethrough.com", "teads.tv", "media.net", "bidswitch.net",
            "contextweb.com", "lijit.com", "sovrn.com", "33across.com", "adroll.com",
            "rtbhouse.com", "undertone.com", "adtech.de", "admixer.net",
            // --- توصيات المحتوى/الإعلانات الأصلية ---
            "taboola.com", "outbrain.com", "revcontent.com", "mgid.com", "adskeeper.com",
            "infolinks.com",
            // --- شبكات النوافذ المنبثقة والتحويل وإعلانات البانر الشائعة في صفحات البث ---
            "popads.net", "popcash.net", "popmyads.com", "propellerads.com", "propellerclick.com",
            "onclickads.net", "clickadu.com", "exoclick.com", "exosrv.com", "exdynsrv.com",
            "juicyads.com", "trafficjunky.net", "trafficfactory.biz", "traffichaus.com",
            "adcash.com", "a-ads.com", "ad-maven.com", "adsterra.com", "highperformanceformat.com",
            "effectivegatecpm.com", "profitablegatecpm.com", "hilltopads.net", "monetag.com",
            "bidvertiser.com", "ero-advertising.com", "realsrv.com", "tsyndicate.com",
            "bannerconnect.net", "adsco.re", "adspyglass.com", "yllix.com", "zeropark.com",
            "richads.com", "ad.gt", "adtng.com", "adx.ws", "adsupply.com", "revenuehits.com",
            "cpmstar.com", "adk2x.com"
    ));

    /** نطاقات ضرورية للتشغيل/الواجهة لا تُحجب أبداً حتى لو تقاطعت يوماً مع قاعدة أخرى. */
    private static final Set<String> NEVER_BLOCK_DOMAINS = new HashSet<>(Arrays.asList(
            "youtube.com", "youtube-nocookie.com", "youtu.be", "ytimg.com", "googlevideo.com",
            "gstatic.com", "googleapis.com", "ggpht.com",
            "cloudflare.com", "cloudflarestream.com", "jsdelivr.net", "unpkg.com",
            "jwplatform.com", "jwpcdn.com", "vimeo.com", "vimeocdn.com", "dailymotion.com", "dmcdn.net",
            "akamaized.net", "akamaihd.net", "fastly.net", "b-cdn.net",
            "github.com", "githubusercontent.com", "workers.dev", "imgur.com"
    ));

    /** امتدادات وسائط/بث: أي طلب ينتهي مساره بها لا يُحجب مهما كان نطاقه. */
    private static final String[] MEDIA_EXTENSIONS = {
            ".m3u8", ".m3u", ".ts", ".m4s", ".mp4", ".m4v", ".m4a", ".mp3", ".aac", ".ac3", ".eac3",
            ".opus", ".ogg", ".oga", ".webm", ".mkv", ".flv", ".mov", ".avi", ".wav", ".mpd", ".key",
            ".vtt", ".srt", ".cmfv", ".cmfa", ".cmft", ".mts", ".m2ts"
    };

    // ---------------------------------------------------------------------------------------
    //  واجهة الاستخدام
    // ---------------------------------------------------------------------------------------

    /**
     * هل يُحجب طلب مورد فرعي (سكربت/صورة/إطار/XHR...) داخل جلسة iframe؟
     *
     * @param url         رابط الطلب
     * @param sessionHost نطاق صفحة القناة (iframe) — طلبات نفس الموقع لا تُحجب أبداً؛ يجوز null.
     */
    static boolean shouldBlockRequest(String url, String sessionHost) {
        final Parsed p = parse(url);
        if (p == null || !p.isHttp || p.host.isEmpty()) return false;

        if (matchesSuffix(NEVER_BLOCK_DOMAINS, p.host)) return false;
        if (sessionHost != null && sameSite(p.host, sessionHost)) return false;
        if (looksLikeMedia(p.path)) return false;

        return isAdHost(p.host) || isKnownAdPath(p.host, p.path);
    }

    /**
     * هل وجهة تنقّل (إطار فرعي) هي نطاق إعلاني معروف؟ تُستخدم لإلغاء تحويلات الإطار إلى صفحات
     * الإعلانات. نفس الاستثناءات الصارمة (بما فيها نطاق القناة نفسه) لكن بدون استثناء الوسائط:
     * التنقّل إلى صفحة وليس تحميل مقطع.
     */
    static boolean isAdNavigation(String url, String sessionHost) {
        final Parsed p = parse(url);
        if (p == null || !p.isHttp || p.host.isEmpty()) return false;
        if (matchesSuffix(NEVER_BLOCK_DOMAINS, p.host)) return false;
        if (sessionHost != null && sameSite(p.host, sessionHost)) return false;
        return isAdHost(p.host) || isKnownAdPath(p.host, p.path);
    }

    /**
     * جلسات لا يُطبَّق فيها حجب الشبكة إطلاقاً: يوتيوب مباشرة، أو صفحة الـ Worker الوسيطة
     * (/yt-embed) التي تضمّن يوتيوب. (ابتلاع النوافذ المنبثقة وإلغاء اختطاف الصفحة يبقيان فعّالين.)
     */
    static boolean isSkippedSession(String url) {
        final Parsed p = parse(url);
        if (p == null || !p.isHttp) return false;
        if (p.host.equals("youtu.be") || p.host.endsWith(".youtu.be")) return true;
        if (p.host.equals("youtube.com") || p.host.endsWith(".youtube.com")) return true;
        if (p.host.equals("youtube-nocookie.com") || p.host.endsWith(".youtube-nocookie.com")) return true;
        return p.path.equals("/yt-embed") || p.path.startsWith("/yt-embed/");
    }

    /** نطاق الرابط بحروف صغيرة، أو null إن لم يكن رابط http(s) صالحاً. */
    static String hostOf(String url) {
        final Parsed p = parse(url);
        return (p != null && p.isHttp && !p.host.isEmpty()) ? p.host : null;
    }

    static boolean isAdHost(String host) {
        return host != null && matchesSuffix(AD_DOMAINS, host);
    }

    // ---------------------------------------------------------------------------------------
    //  تفاصيل داخلية
    // ---------------------------------------------------------------------------------------

    /**
     * قاعدتا مسار عاليتا الدقة على نطاق ليس إعلانياً بالكامل: بكسل/سكربتات الإعلانات داخل
     * google.com نفسه (AdSense/Ad Manager). لا قواعد مسارات أخرى عمداً.
     */
    private static boolean isKnownAdPath(String host, String path) {
        if (host.equals("google.com") || host.endsWith(".google.com")) {
            return path.startsWith("/pagead/") || path.startsWith("/adsense/");
        }
        return false;
    }

    /** يطابق host أو أي أب له (بحدود النقطة) مع مجموعة نطاقات. لا يصل إلى مستوى TLD وحده. */
    private static boolean matchesSuffix(Set<String> domains, String host) {
        String h = host;
        while (true) {
            if (domains.contains(h)) return true;
            final int dot = h.indexOf('.');
            if (dot < 0 || dot == h.length() - 1) return false;
            h = h.substring(dot + 1);
        }
    }

    /** نفس الموقع تقريباً: تطابق تام أو أحدهما نطاق فرعي من الآخر (بحدود النقطة). */
    private static boolean sameSite(String a, String b) {
        if (a.equals(b)) return true;
        return a.endsWith("." + b) || b.endsWith("." + a);
    }

    static boolean looksLikeMedia(String path) {
        if (path == null) return false;
        final String lower = path.toLowerCase(Locale.US);
        for (String ext : MEDIA_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static final class Parsed {
        final boolean isHttp;
        final String host;
        final String path;

        Parsed(boolean isHttp, String host, String path) {
            this.isHttp = isHttp;
            this.host = host;
            this.path = path;
        }
    }

    /**
     * محلّل روابط يدوي متسامح (لا يرمي استثناءً أبداً): java.net.URI يرفض أي رابط فيه حرف غير
     * مهرَّب (مسافة، |، ...) وهي شائعة جداً في روابط الإعلانات، و android.net.Uri غير متاح في
     * اختبارات JVM. نحتاج فقط: المخطط، النطاق (بدون userinfo/port/نقطة نهائية)، والمسار.
     */
    private static Parsed parse(String url) {
        if (url == null) return null;
        final int schemeEnd = url.indexOf("://");
        if (schemeEnd <= 0 || schemeEnd > 10) return null;

        final String scheme = url.substring(0, schemeEnd).toLowerCase(Locale.US);
        final boolean isHttp = scheme.equals("http") || scheme.equals("https");

        final String rest = url.substring(schemeEnd + 3);
        int authorityEnd = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            final char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i;
                break;
            }
        }

        String authority = rest.substring(0, authorityEnd);
        final int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);

        String host;
        if (authority.startsWith("[")) { // IPv6
            final int close = authority.indexOf(']');
            host = close > 0 ? authority.substring(1, close) : authority;
        } else {
            final int colon = authority.indexOf(':');
            host = colon >= 0 ? authority.substring(0, colon) : authority;
        }
        host = host.toLowerCase(Locale.US);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);

        String path = "/";
        if (authorityEnd < rest.length() && rest.charAt(authorityEnd) == '/') {
            int pathEnd = rest.length();
            for (int i = authorityEnd; i < rest.length(); i++) {
                final char c = rest.charAt(i);
                if (c == '?' || c == '#') {
                    pathEnd = i;
                    break;
                }
            }
            path = rest.substring(authorityEnd, pathEnd);
        }
        return new Parsed(isHttp, host, path);
    }
}
