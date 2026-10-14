package com.yallagoal.app;

import com.yallagoal.app.PlaybackRecoveryPolicy.Action;
import com.yallagoal.app.PlaybackRecoveryPolicy.Decision;
import com.yallagoal.app.PlaybackRecoveryPolicy.Failure;
import com.yallagoal.app.PlaybackRecoveryPolicy.Kind;

/**
 * اختبار منطق التعافي (PlaybackRecoveryPolicy) على JVM عادية — بلا JUnit ولا Android حتى يعمل
 * كما هو في أي بيئة:
 *
 *   javac -d /tmp/ygt app/src/main/java/com/yallagoal/app/PlaybackRecoveryPolicy.java \
 *         app/src/test/java/com/yallagoal/app/PlaybackRecoveryPolicyTest.java
 *   java -cp /tmp/ygt com.yallagoal.app.PlaybackRecoveryPolicyTest
 *
 * يخرج برمز 1 عند أول فشل. الساعة مُحاكاة (لا انتظار حقيقي).
 */
public final class PlaybackRecoveryPolicyTest {

    private static int checks = 0;

    private static void check(boolean cond, String what) {
        checks++;
        if (!cond) {
            System.err.println("FAIL: " + what);
            System.exit(1);
        }
    }

    private static Failure f(Kind k, int http) {
        return new Failure(k, 0, http);
    }

    public static void main(String[] args) {
        testHttpMapping();
        testPermanentErrorsNeverRetry();
        testBackoffAndBudgetForServerErrors();
        testAuthErrorGivesUpFastWithHttpCodeInMessage();
        testBehindLiveWindowIsImmediateThenBacksOff();
        testStuckPlaylistRefreshesUrl();
        testHealthyPlaybackResetsIncident();
        testTotalCapAcrossMixedKinds();
        testOfflineWaitDoesNotBurnBudgetAndResumes();
        testOfflineWaitHasCeiling();
        testRefreshOnlyForExpiryLikeErrors();
        testManualResetRestoresBudget();
        testTimeoutGetsSingleAutomaticRetry();
        testIncidentTimeCapStopsRetriesEvenWithBudgetLeft();
        testIncidentClockRestartsAfterOfflineWait();
        System.out.println("OK — " + checks + " checks passed");
    }

    static void testHttpMapping() {
        check(PlaybackRecoveryPolicy.kindForHttpStatus(401) == Kind.HTTP_AUTH, "401 -> AUTH");
        check(PlaybackRecoveryPolicy.kindForHttpStatus(403) == Kind.HTTP_AUTH, "403 -> AUTH");
        check(PlaybackRecoveryPolicy.kindForHttpStatus(404) == Kind.HTTP_NOT_FOUND, "404 -> NOT_FOUND");
        check(PlaybackRecoveryPolicy.kindForHttpStatus(410) == Kind.HTTP_NOT_FOUND, "410 -> NOT_FOUND");
        check(PlaybackRecoveryPolicy.kindForHttpStatus(429) == Kind.HTTP_RATE_LIMIT, "429 -> RATE_LIMIT");
        check(PlaybackRecoveryPolicy.kindForHttpStatus(500) == Kind.HTTP_SERVER, "500 -> SERVER");
        check(PlaybackRecoveryPolicy.kindForHttpStatus(503) == Kind.HTTP_SERVER, "503 -> SERVER");
        check(PlaybackRecoveryPolicy.kindForHttpStatus(522) == Kind.HTTP_SERVER, "522 (Cloudflare) -> SERVER");
        check(PlaybackRecoveryPolicy.kindForHttpStatus(408) == Kind.HTTP_SERVER, "408 -> SERVER");
        check(PlaybackRecoveryPolicy.kindForHttpStatus(400) == Kind.HTTP_OTHER, "400 -> OTHER");
    }

    static void testPermanentErrorsNeverRetry() {
        Kind[] permanent = {Kind.UNSUPPORTED_FORMAT, Kind.CLEARTEXT_BLOCKED, Kind.DECODER, Kind.DRM, Kind.OTHER_FATAL};
        for (Kind k : permanent) {
            PlaybackRecoveryPolicy p = new PlaybackRecoveryPolicy();
            Decision d = p.onFailure(f(k, -1), 0, 0, true);
            check(d.action == Action.GIVE_UP, k + " must give up immediately");
            check(d.finalMessage != null && !d.finalMessage.isEmpty(), k + " has a final message");
            check(p.getTotalAttempts() == 0, k + " consumes no attempts");
        }
    }

    static void testBackoffAndBudgetForServerErrors() {
        PlaybackRecoveryPolicy p = new PlaybackRecoveryPolicy();
        long[] expectedDelays = {1_500, 3_000, 6_000, 10_000, 15_000};
        for (int i = 0; i < 5; i++) {
            Decision d = p.onFailure(f(Kind.HTTP_SERVER, 503), 1000L * i, 0, true);
            check(d.action == Action.RETRY_SAME_URL, "503 retry #" + (i + 1) + " is RETRY_SAME_URL");
            check(d.delayMs == expectedDelays[i], "503 backoff #" + (i + 1) + " = " + expectedDelays[i] + " got " + d.delayMs);
            check(d.attempt == i + 1 && d.maxAttempts == 5, "attempt counter " + (i + 1) + "/5");
            check(d.statusMessage.contains("(" + (i + 1) + "/5)"), "status shows progress");
        }
        Decision last = p.onFailure(f(Kind.HTTP_SERVER, 503), 10_000, 0, true);
        check(last.action == Action.GIVE_UP, "6th 503 gives up (bounded, no infinite loop)");
        check(last.finalMessage.contains("503"), "final message mentions the HTTP code");
    }

    static void testAuthErrorGivesUpFastWithHttpCodeInMessage() {
        PlaybackRecoveryPolicy p = new PlaybackRecoveryPolicy();
        for (int i = 0; i < 3; i++) {
            Decision d = p.onFailure(f(Kind.HTTP_AUTH, 403), 0, 0, true);
            check(d.action == Action.REFRESH_URL_AND_RETRY, "403 refreshes the URL before retrying");
        }
        Decision d = p.onFailure(f(Kind.HTTP_AUTH, 403), 0, 0, true);
        check(d.action == Action.GIVE_UP, "403 gives up after 3 recoveries");
        check(d.finalMessage.contains("403"), "message has the status code");
        check(d.finalMessage.contains("أجهزة") || d.finalMessage.contains("للأجهزة"), "message explains the connection-limit possibility");
    }

    static void testBehindLiveWindowIsImmediateThenBacksOff() {
        PlaybackRecoveryPolicy p = new PlaybackRecoveryPolicy();
        Decision d1 = p.onFailure(f(Kind.BEHIND_LIVE_WINDOW, -1), 0, 0, true);
        check(d1.action == Action.SEEK_TO_LIVE_EDGE && d1.delayMs == 0, "first BehindLiveWindow: immediate live-edge seek");
        Decision d2 = p.onFailure(f(Kind.BEHIND_LIVE_WINDOW, -1), 0, 0, true);
        check(d2.action == Action.SEEK_TO_LIVE_EDGE && d2.delayMs == 3_000, "second: backs off (attempt 2 -> 3000ms)");
        p.onFailure(f(Kind.BEHIND_LIVE_WINDOW, -1), 0, 0, true);
        check(p.onFailure(f(Kind.BEHIND_LIVE_WINDOW, -1), 0, 0, true).action == Action.GIVE_UP, "4th gives up");
    }

    static void testStuckPlaylistRefreshesUrl() {
        PlaybackRecoveryPolicy p = new PlaybackRecoveryPolicy();
        Decision d = p.onFailure(f(Kind.PLAYLIST_STUCK, -1), 0, 0, true);
        check(d.action == Action.REFRESH_URL_AND_RETRY, "stuck playlist -> refresh + retry");
        Decision r = p.onFailure(f(Kind.PLAYLIST_RESET, -1), 0, 0, true);
        check(r.action == Action.REFRESH_URL_AND_RETRY, "reset playlist -> refresh + retry");
    }

    static void testHealthyPlaybackResetsIncident() {
        PlaybackRecoveryPolicy p = new PlaybackRecoveryPolicy();
        for (int i = 0; i < 5; i++) p.onFailure(f(Kind.HTTP_SERVER, 503), 0, 0, true);
        check(p.onFailure(f(Kind.HTTP_SERVER, 503), 0, 0, true).action == Action.GIVE_UP, "budget exhausted");
        // بعد بث سليم 31 ثانية: حادثة جديدة بميزانية كاملة
        Decision d = p.onFailure(f(Kind.HTTP_SERVER, 503), 100_000, 31_000, true);
        check(d.action == Action.RETRY_SAME_URL && d.attempt == 1, "healthy >=30s starts a fresh incident");
        // لكن 29 ثانية لا تكفي
        PlaybackRecoveryPolicy q = new PlaybackRecoveryPolicy();
        for (int i = 0; i < 5; i++) q.onFailure(f(Kind.HTTP_SERVER, 503), 0, 0, true);
        check(q.onFailure(f(Kind.HTTP_SERVER, 503), 0, 29_000, true).action == Action.GIVE_UP, "29s is not enough to reset");
    }

    static void testTotalCapAcrossMixedKinds() {
        PlaybackRecoveryPolicy p = new PlaybackRecoveryPolicy();
        Kind[] seq = {Kind.HTTP_SERVER, Kind.NETWORK_FAILED, Kind.HTTP_NOT_FOUND, Kind.PLAYLIST_STUCK,
                Kind.HTTP_SERVER, Kind.NETWORK_TIMEOUT, Kind.HTTP_AUTH, Kind.PLAYLIST_RESET};
        for (Kind k : seq) {
            check(p.onFailure(f(k, 500), 0, 0, true).action != Action.GIVE_UP, "within cap: " + k);
        }
        check(p.getTotalAttempts() == PlaybackRecoveryPolicy.MAX_TOTAL_ATTEMPTS, "8 attempts consumed");
        check(p.onFailure(f(Kind.BAD_RESPONSE, -1), 0, 0, true).action == Action.GIVE_UP,
                "9th attempt of any kind is refused by the global cap");
    }

    static void testOfflineWaitDoesNotBurnBudgetAndResumes() {
        PlaybackRecoveryPolicy p = new PlaybackRecoveryPolicy();
        Failure net = f(Kind.NETWORK_FAILED, -1);
        long t = 0;
        Decision d = p.onFailure(net, t, 0, false);
        check(d.action == Action.WAIT_FOR_NETWORK && d.delayMs == PlaybackRecoveryPolicy.OFFLINE_POLL_MS, "offline -> wait");
        for (int i = 0; i < 20; i++) {
            t += 2000;
            d = p.onFailure(net, t, 0, false);
            check(d.action == Action.WAIT_FOR_NETWORK, "still waiting");
        }
        check(p.getTotalAttempts() == 0, "waiting for the network consumed no attempts");
        t += 2000;
        d = p.onFailure(net, t, 0, true);
        check(d.action == Action.RETRY_SAME_URL && d.attempt == 0, "network back -> resume without consuming an attempt");
        check(p.getTotalAttempts() == 0, "still zero attempts after resume");
    }

    static void testOfflineWaitHasCeiling() {
        PlaybackRecoveryPolicy p = new PlaybackRecoveryPolicy();
        Failure net = f(Kind.NETWORK_TIMEOUT, -1);
        p.onFailure(net, 0, 0, false);
        Decision d = p.onFailure(net, PlaybackRecoveryPolicy.MAX_OFFLINE_WAIT_MS + 1, 0, false);
        check(d.action == Action.GIVE_UP, "offline wait is capped");
        check(d.finalMessage.contains("الإنترنت"), "offline message");
    }

    static void testRefreshOnlyForExpiryLikeErrors() {
        check(PlaybackRecoveryPolicy.actionFor(Kind.HTTP_SERVER) == Action.RETRY_SAME_URL, "5xx does not hit the refresh API");
        check(PlaybackRecoveryPolicy.actionFor(Kind.NETWORK_FAILED) == Action.RETRY_SAME_URL, "network does not hit the refresh API");
        check(PlaybackRecoveryPolicy.actionFor(Kind.HTTP_RATE_LIMIT) == Action.RETRY_SAME_URL, "429 does not hit the refresh API");
        check(PlaybackRecoveryPolicy.actionFor(Kind.HTTP_NOT_FOUND) == Action.REFRESH_URL_AND_RETRY, "404 refreshes");
        check(PlaybackRecoveryPolicy.actionFor(Kind.BAD_RESPONSE) == Action.REFRESH_URL_AND_RETRY, "bad response refreshes");
    }

    static void testManualResetRestoresBudget() {
        PlaybackRecoveryPolicy p = new PlaybackRecoveryPolicy();
        for (int i = 0; i < 3; i++) p.onFailure(f(Kind.HTTP_AUTH, 403), 0, 0, true);
        check(p.onFailure(f(Kind.HTTP_AUTH, 403), 0, 0, true).action == Action.GIVE_UP, "exhausted");
        p.reset();
        check(p.onFailure(f(Kind.HTTP_AUTH, 403), 0, 0, true).attempt == 1, "reset() restores the full budget (manual retry)");
    }

    static void testTimeoutGetsSingleAutomaticRetry() {
        PlaybackRecoveryPolicy p = new PlaybackRecoveryPolicy();
        Decision d1 = p.onFailure(f(Kind.NETWORK_TIMEOUT, -1), 0, 0, true);
        check(d1.action == Action.RETRY_SAME_URL && d1.attempt == 1 && d1.maxAttempts == 1, "first timeout: one automatic retry");
        check(p.onFailure(f(Kind.NETWORK_TIMEOUT, -1), 70_000, 0, true).action == Action.GIVE_UP,
                "second timeout gives up (a dead host must not keep the user waiting for minutes)");
        // اتصال سريع الفشل (مرفوض/DNS) يبقى أرخص: 4 محاولات
        PlaybackRecoveryPolicy q = new PlaybackRecoveryPolicy();
        for (int i = 0; i < 4; i++) check(q.onFailure(f(Kind.NETWORK_FAILED, -1), i * 1000L, 0, true).action != Action.GIVE_UP, "fast failure retry " + (i + 1));
        check(q.onFailure(f(Kind.NETWORK_FAILED, -1), 5000, 0, true).action == Action.GIVE_UP, "fifth fast failure gives up");
    }

    static void testIncidentTimeCapStopsRetriesEvenWithBudgetLeft() {
        PlaybackRecoveryPolicy p = new PlaybackRecoveryPolicy();
        check(p.onFailure(f(Kind.HTTP_SERVER, 503), 0, 0, true).action == Action.RETRY_SAME_URL, "t=0 retry");
        check(p.onFailure(f(Kind.HTTP_SERVER, 503), 60_000, 0, true).action == Action.RETRY_SAME_URL, "t=60s retry");
        Decision d = p.onFailure(f(Kind.HTTP_SERVER, 503), PlaybackRecoveryPolicy.MAX_INCIDENT_MS + 1, 0, true);
        check(d.action == Action.GIVE_UP, "past the incident cap: give up although 2 retries of the 5 remain");
        check(d.finalMessage.contains("503"), "cap message still states the real reason");
    }

    static void testIncidentClockRestartsAfterOfflineWait() {
        PlaybackRecoveryPolicy p = new PlaybackRecoveryPolicy();
        Failure net = f(Kind.NETWORK_FAILED, -1);
        check(p.onFailure(net, 0, 0, true).action == Action.RETRY_SAME_URL, "first retry starts the incident clock");
        long t = 100_000;
        check(p.onFailure(net, t, 0, false).action == Action.WAIT_FOR_NETWORK, "goes offline");
        t += 120_000; // دقيقتان بلا شبكة
        check(p.onFailure(net, t, 0, false).action == Action.WAIT_FOR_NETWORK, "still offline");
        t += 2_000;
        check(p.onFailure(net, t, 0, true).action == Action.RETRY_SAME_URL, "network back: resumes");
        check(p.onFailure(net, t + 5_000, 0, true).action == Action.RETRY_SAME_URL,
                "offline time is not counted against the incident cap (5s after reconnect still retries)");
    }
}
