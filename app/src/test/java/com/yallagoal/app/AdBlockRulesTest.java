package com.yallagoal.app;

/**
 * اختبار قواعد حجب الإعلانات (AdBlockRules) على JVM عادية — بلا JUnit ولا Android:
 *
 *   javac -d /tmp/ygt app/src/main/java/com/yallagoal/app/AdBlockRules.java \
 *         app/src/test/java/com/yallagoal/app/AdBlockRulesTest.java
 *   java -cp /tmp/ygt com.yallagoal.app.AdBlockRulesTest
 */
public final class AdBlockRulesTest {

    private static int checks = 0;

    private static void check(boolean cond, String what) {
        checks++;
        if (!cond) {
            System.err.println("FAIL: " + what);
            System.exit(1);
        }
    }

    private static void blocked(String url, String session) {
        check(AdBlockRules.shouldBlockRequest(url, session), "should BLOCK  " + url);
    }

    private static void allowed(String url, String session) {
        check(!AdBlockRules.shouldBlockRequest(url, session), "should ALLOW  " + url);
    }

    public static void main(String[] args) {
        final String S = "player.example-tv.com";

        // ---- يُحجب: موارد شبكات إعلانية معروفة (بنطاقاتها الفرعية) ----
        blocked("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", S);
        blocked("https://securepubads.g.doubleclick.net/tag/js/gpt.js", S);
        blocked("https://a.popads.net/pop.js", S);
        blocked("http://s.adsterra.com/x?y=1", S);
        blocked("https://x.exoclick.com:8443/a.php", S);
        blocked("https://www.highperformanceformat.com/abc/invoke.js", S);
        blocked("https://www.google.com/pagead/1p-user-list/123?x=1", S);
        blocked("https://adservice.google.com/adsid/integrator.js", S);
        // أشكال رابط غير اعتيادية يجب ألا تُفلت من الحجب ولا ترمي استثناءً
        blocked("https://USER:pw@ADS.POPCASH.NET./x", S);
        blocked("https://a.popads.net/path with space|pipe.js", S);
        blocked("https://popads.net", S);

        // ---- لا يُحجب: موارد البث مهما كان نطاقها ----
        allowed("https://pagead2.googlesyndication.com/video/seg1.ts", S);
        allowed("https://a.popads.net/live/index.m3u8?token=abc", S);
        allowed("https://cdn.exoclick.com/stream/chunk0001.m4s", S);
        allowed("https://x.adsterra.com/movie.MP4", S);
        allowed("https://x.adsterra.com/enc.key", S);
        allowed("https://x.adsterra.com/manifest.mpd?x=1", S);

        // ---- لا يُحجب: نطاقات ضرورية، نفس موقع القناة، ونطاقات غير معروفة ----
        allowed("https://www.youtube.com/s/player/base.js", S);
        allowed("https://i.ytimg.com/vi/abc/hqdefault.jpg", S);
        allowed("https://rr3---sn.googlevideo.com/videoplayback?x=1", S);
        allowed("https://raw.githubusercontent.com/user/repo/main/channels.json", S);
        allowed("https://cdnjs.cloudflare.com/ajax/libs/hls.js/1.5.0/hls.min.js", S);
        allowed("https://example.com/app.js", S);
        allowed("https://www.google.com/search?q=ads", S);
        allowed("https://popads.net/x.js", "popads.net");            // first-party
        allowed("https://sub.popads.net/x.js", "popads.net");        // first-party (فرعي)

        // ---- لا مطابقات خاطئة بالتشابه النصي ----
        allowed("https://notdoubleclick.net/x.js", S);
        allowed("https://doubleclick.net.evil-site.com/x.js", S);
        allowed("https://adsterra.com.cn.example.org/x.js", S);
        allowed("https://mydoubleclick.net/x.js", S);

        // ---- مخططات/قيم غير http(s) أو تالفة: لا حجب ولا استثناء ----
        allowed("file:///android_asset/index.html", S);
        allowed("data:text/html,<h1>x</h1>", S);
        allowed("blob:https://example.com/uuid", S);
        allowed("javascript:void(0)", S);
        allowed("", S);
        allowed(null, S);
        allowed("https://", S);

        // ---- بلا نطاق جلسة معروف ----
        blocked("https://a.popads.net/pop.js", null);
        allowed("https://example.com/a.js", null);

        // ---- التنقّل ----
        check(AdBlockRules.isAdNavigation("https://adsterra.com/landing", S), "nav to ad network is ad navigation");
        check(AdBlockRules.isAdNavigation("https://x.propellerads.com/r?id=1", S), "nav to propellerads");
        check(!AdBlockRules.isAdNavigation("https://example.org/page", S), "nav to unknown site is NOT blocked by list");
        check(!AdBlockRules.isAdNavigation("https://www.youtube.com/watch?v=x", S), "nav to youtube is not blocked");
        check(!AdBlockRules.isAdNavigation("https://adsterra.com/x", "adsterra.com"), "same-site nav not blocked");
        // بخلاف الموارد، التنقّل إلى صفحة (حتى بامتداد مثل .mp4 مضلِّل) لا يُستثنى بالامتداد
        check(AdBlockRules.isAdNavigation("https://adsterra.com/redirect.mp4", S), "ad navigation ignores media extension trick");

        // ---- جلسات يوتيوب ----
        check(AdBlockRules.isSkippedSession("https://yallagoal-active-users.ossamasal2012.workers.dev/yt-embed?v=abc123XYZ_-"), "yt-embed worker page skipped");
        check(AdBlockRules.isSkippedSession("https://www.youtube.com/embed/abc"), "youtube skipped");
        check(AdBlockRules.isSkippedSession("https://www.youtube-nocookie.com/embed/abc"), "youtube-nocookie skipped");
        check(AdBlockRules.isSkippedSession("https://youtu.be/abc"), "youtu.be skipped");
        check(!AdBlockRules.isSkippedSession("https://example.com/yt-embedded"), "similar path not skipped");
        check(!AdBlockRules.isSkippedSession("https://notyoutube.com/x"), "lookalike host not skipped");
        check(!AdBlockRules.isSkippedSession("https://player.example-tv.com/embed/1"), "normal channel not skipped");

        // ---- hostOf ----
        check("sub.example.com".equals(AdBlockRules.hostOf("HTTPS://Sub.Example.COM:8080/p?q#f")), "hostOf normalizes");
        check("example.com".equals(AdBlockRules.hostOf("http://u:p@example.com./x")), "hostOf strips userinfo and trailing dot");
        check(AdBlockRules.hostOf("file:///x") == null, "hostOf non-http is null");
        check(AdBlockRules.hostOf("nonsense") == null, "hostOf garbage is null");

        System.out.println("OK — " + checks + " checks passed");
    }
}
