package com.yallagoal.app;

import java.util.EnumMap;
import java.util.Map;

/**
 * مُقرِّر التعافي من أخطاء التشغيل — منطق صرف بلا أي اعتماد على Android أو Media3، ولهذا
 * يمكن اختباره على JVM عادية (انظر PlaybackRecoveryPolicyTest تحت app/src/test).
 *
 * <h3>لماذا هذا الكلاس موجود؟</h3>
 * ExoPlayer يُعيد محاولة تحميل أي طلب فاشل بنفسه (3 محاولات، و6 للبث التدريجي المباشر، بتأخير
 * لا يتجاوز 5 ثوانٍ — DefaultLoadErrorHandlingPolicy)، وبعدها يرمي خطأً نهائياً ويتوقف عند
 * STATE_IDLE. قبل هذا الكلاس كان التطبيق يعرض لحظتها رسالة عامة ويطلب من المستخدم الضغط على
 * «إعادة المحاولة» يدوياً — وهذا بالضبط ما كان يظهر كأن «القناة تتوقف ثم تعمل حين نخرج منها
 * وندخلها من جديد». معظم هذه الأخطاء (قائمة تشغيل لم تعد تتجدد، رمز مميَّز انتهت صلاحيته،
 * سيرفر يحتاج عدة ثوانٍ ليبدأ القناة، انقطاع شبكة لحظي، تأخّر عن حافة البث المباشر...) يُعالَج
 * تلقائياً بإعادة تهيئة المشغّل من الرابط الأصلي بعد مهلة قصيرة — وهو ما يفعله هذا المُقرِّر،
 * لكن بحدود صارمة (لا حلقات لانهائية) وبحسب نوع الخطأ لا بوصفة واحدة لكل شيء.
 *
 * <h3>ما الذي يقرّره؟</h3>
 * يستقبل تصنيفاً للفشل (Failure) ويُرجع قراراً (Decision): أعد المحاولة بعد X ميلي ثانية،
 * أو جدّد الرابط ثم أعد، أو عُد لحافة البث المباشر، أو انتظر عودة الشبكة، أو استسلم برسالة
 * واضحة تذكر السبب الحقيقي ورمز HTTP إن وُجد. الأخطاء الدائمة (فك ترميز، DRM، صيغة غير مدعومة،
 * اتصال HTTP محظور) لا تُعاد أبداً.
 *
 * <h3>الحدود</h3>
 * لكل نوع خطأ ميزانية محاولات خاصة به + سقف إجمالي (MAX_TOTAL_ATTEMPTS). وإن استمر التشغيل سليماً
 * {@link #HEALTHY_RESET_MS} على الأقل قبل الفشل التالي، فالفشل التالي «حادثة جديدة» وتُصفَّر
 * العدّادات. الكلاس غير آمن للخيوط المتعددة: يُستدعى من خيط واجهة المستخدم فقط.
 */
final class PlaybackRecoveryPolicy {

    /** تصنيف الفشل كما يراه المُقرِّر (المحرّك هو من يحوّل أخطاء Media3 إلى هذه الأنواع). */
    enum Kind {
        /** تأخّر المشغّل عن نافذة البث المباشر (انقطاع/إيقاف طويل). */
        BEHIND_LIVE_WINDOW,
        /** قائمة HLS لم تعد تتجدد (المصدر توقف أو تعلّق). */
        PLAYLIST_STUCK,
        /** رقم التسلسل في قائمة HLS رجع للخلف (المصدر أُعيد تشغيله). */
        PLAYLIST_RESET,
        /** HTTP 401 / 403. */
        HTTP_AUTH,
        /** HTTP 404 / 410. */
        HTTP_NOT_FOUND,
        /** HTTP 429. */
        HTTP_RATE_LIMIT,
        /** HTTP 5xx (وأيضاً 408 و425). */
        HTTP_SERVER,
        /** أي رمز HTTP آخر >= 400. */
        HTTP_OTHER,
        NETWORK_FAILED,
        NETWORK_TIMEOUT,
        /** استجابة غير صالحة: HTML بدل بث، قائمة تالفة، نوع محتوى خاطئ. */
        BAD_RESPONSE,
        /** خطأ عام غير محدد يُرجَّح أنه عابر. */
        UNKNOWN_TRANSIENT,
        // ---- أخطاء دائمة: لا تُعاد المحاولة أبداً ----
        UNSUPPORTED_FORMAT,
        CLEARTEXT_BLOCKED,
        DECODER,
        DRM,
        OTHER_FATAL
    }

    /** وصف فشل واحد: النوع + رمز خطأ المشغّل + رمز HTTP (أو -1). */
    static final class Failure {
        final Kind kind;
        final int errorCode;
        final int httpStatus;

        Failure(Kind kind, int errorCode, int httpStatus) {
            this.kind = kind;
            this.errorCode = errorCode;
            this.httpStatus = httpStatus;
        }
    }

    enum Action {
        /** أعد تهيئة المشغّل بنفس الرابط. */
        RETRY_SAME_URL,
        /** جدّد الرابط إن أمكن (واجهة API) ثم أعد التهيئة؛ دون مُجدِّد = كالسابق. */
        REFRESH_URL_AND_RETRY,
        /** seekToDefaultPosition ثم prepare — العلاج الرسمي لـ BehindLiveWindow. */
        SEEK_TO_LIVE_EDGE,
        /** لا اتصال بالشبكة: انتظر ثم اسأل المُقرِّر مجدداً (لا يستهلك المحاولات). */
        WAIT_FOR_NETWORK,
        /** أظهر الخطأ النهائي للمستخدم. */
        GIVE_UP
    }

    static final class Decision {
        final Action action;
        /** تأخير قبل تنفيذ الإجراء (ميلي ثانية). */
        final long delayMs;
        /** رقم المحاولة الحالية (1..max) أو 0 إن لم يكن ينطبق. */
        final int attempt;
        final int maxAttempts;
        /** نص حالة قصير يُعرض فوق دائرة التحميل أثناء التعافي. */
        final String statusMessage;
        /** رسالة نهائية للمستخدم عند GIVE_UP (بدون السطر التقني). */
        final String finalMessage;

        private Decision(Action action, long delayMs, int attempt, int maxAttempts,
                         String statusMessage, String finalMessage) {
            this.action = action;
            this.delayMs = delayMs;
            this.attempt = attempt;
            this.maxAttempts = maxAttempts;
            this.statusMessage = statusMessage;
            this.finalMessage = finalMessage;
        }

        static Decision retry(Action action, long delayMs, int attempt, int max, String status) {
            return new Decision(action, delayMs, attempt, max, status, null);
        }

        static Decision giveUp(String finalMessage) {
            return new Decision(Action.GIVE_UP, 0L, 0, 0, null, finalMessage);
        }
    }

    // ---------------------------------------------------------------------------------------
    //  الثوابت (كلها مبرَّرة في README/التقرير؛ لا أرقام عشوائية)
    // ---------------------------------------------------------------------------------------

    /** تأخيرات إعادة المحاولة التصاعدية (ميلي ثانية) — المحاولة n تأخذ العنصر n-1 (الأخير يتكرر). */
    private static final long[] BACKOFF_MS = {1_500L, 3_000L, 6_000L, 10_000L, 15_000L};

    /** سقف إجمالي لمحاولات التعافي في حادثة واحدة مهما تنوّعت الأخطاء. */
    static final int MAX_TOTAL_ATTEMPTS = 8;

    /**
     * سقف زمني للحادثة الواحدة (من أول فشل فيها): بعده لا محاولات جديدة حتى لو بقي من الميزانية
     * شيء. ضروري لأن دورة تحميل واحدة داخل ExoPlayer على سيرفر لا يردّ قد تستغرق وحدها ~60 ثانية
     * (مهلة اتصال 15 ث × 4 محاولات)، فبدون هذا السقف قد ينتظر المستخدم دقائق قبل أن يرى السبب.
     * لا تُحتسب فيه فترة انتظار الشبكة (انقطاع الإنترنت ليس خطأً في المصدر).
     */
    static final long MAX_INCIDENT_MS = 150_000L;

    /** إن بقي التشغيل سليماً هذه المدة قبل الفشل التالي فهو حادثة جديدة (تُصفَّر العدّادات). */
    static final long HEALTHY_RESET_MS = 30_000L;

    /** أقصى مدة انتظار لعودة الشبكة قبل الاستسلام بدل الدوران للأبد. */
    static final long MAX_OFFLINE_WAIT_MS = 180_000L;

    /** فاصل فحص عودة الشبكة. */
    static final long OFFLINE_POLL_MS = 2_000L;

    /** مهلة قصيرة بعد عودة الشبكة قبل إعادة الاتصال (لتستقر الشبكة). */
    private static final long NETWORK_SETTLE_MS = 1_000L;

    private final Map<Kind, Integer> usedPerKind = new EnumMap<>(Kind.class);
    private int totalAttempts = 0;
    private long offlineSinceMs = -1L;
    private long incidentStartMs = -1L;

    /** يُصفِّر كل العدّادات — يُستدعى عند بدء تشغيل جديد أو ضغطة «إعادة المحاولة» اليدوية. */
    void reset() {
        usedPerKind.clear();
        totalAttempts = 0;
        offlineSinceMs = -1L;
        incidentStartMs = -1L;
    }

    /** عدد محاولات التعافي الآلية المستهلكة حالياً (للاختبار والتشخيص). */
    int getTotalAttempts() {
        return totalAttempts;
    }

    /**
     * @param failure  تصنيف الفشل
     * @param nowMs    ساعة رتيبة (SystemClock.elapsedRealtime) — تُمرَّر من الخارج ليبقى الكلاس نقياً
     * @param playedMs مدة التشغيل السليم المتواصل التي سبقت هذا الفشل (0 إن لم يُشغَّل شيء)
     * @param online   هل الجهاز متصل بالشبكة وقت القرار
     */
    Decision onFailure(Failure failure, long nowMs, long playedMs, boolean online) {
        if (playedMs >= HEALTHY_RESET_MS) {
            reset();
        }

        final Kind kind = failure.kind;
        final int budget = budgetFor(kind);
        if (budget <= 0) {
            return Decision.giveUp(finalMessageFor(failure));
        }

        // ---- لا شبكة: لا نحرق المحاولات؛ ننتظر عودتها (مع سقف زمني) ----
        if (isNetworkSensitive(kind)) {
            if (!online) {
                if (offlineSinceMs < 0) offlineSinceMs = nowMs;
                if (nowMs - offlineSinceMs > MAX_OFFLINE_WAIT_MS) {
                    return Decision.giveUp(MSG_OFFLINE);
                }
                return Decision.retry(Action.WAIT_FOR_NETWORK, OFFLINE_POLL_MS, 0, 0,
                        "لا يوجد اتصال بالإنترنت — بانتظار عودة الشبكة…");
            }
            if (offlineSinceMs >= 0) {
                // عادت الشبكة بعد انتظار: نستأنف دون استهلاك محاولة (مع مهلة استقرار قصيرة)،
                // ويبدأ العدّ الزمني للحادثة من جديد (فترة الانقطاع ليست خطأً في المصدر).
                offlineSinceMs = -1L;
                incidentStartMs = nowMs;
                return Decision.retry(actionFor(kind), NETWORK_SETTLE_MS, 0, 0,
                        "عادت الشبكة — جارٍ إعادة الاتصال…");
            }
        }

        if (incidentStartMs < 0) incidentStartMs = nowMs;

        final int used = valueOf(usedPerKind.get(kind));
        if (used >= budget || totalAttempts >= MAX_TOTAL_ATTEMPTS
                || nowMs - incidentStartMs > MAX_INCIDENT_MS) {
            return Decision.giveUp(finalMessageFor(failure));
        }

        final int attempt = used + 1;
        usedPerKind.put(kind, attempt);
        totalAttempts++;

        return Decision.retry(actionFor(kind), delayFor(kind, attempt), attempt, budget,
                statusFor(kind, attempt, budget));
    }

    // ---------------------------------------------------------------------------------------
    //  جداول القرار
    // ---------------------------------------------------------------------------------------

    static Kind kindForHttpStatus(int status) {
        if (status == 401 || status == 403) return Kind.HTTP_AUTH;
        if (status == 404 || status == 410) return Kind.HTTP_NOT_FOUND;
        if (status == 429) return Kind.HTTP_RATE_LIMIT;
        if (status == 408 || status == 425 || (status >= 500 && status <= 599)) return Kind.HTTP_SERVER;
        return Kind.HTTP_OTHER;
    }

    /** عدد محاولات التعافي الآلية المسموحة لكل نوع (0 = خطأ دائم، لا إعادة). */
    static int budgetFor(Kind kind) {
        switch (kind) {
            case BEHIND_LIVE_WINDOW:  return 3;
            case PLAYLIST_STUCK:      return 4;
            case PLAYLIST_RESET:      return 4;
            case HTTP_AUTH:           return 3;
            case HTTP_NOT_FOUND:      return 3;
            case HTTP_RATE_LIMIT:     return 4;
            case HTTP_SERVER:         return 5;
            case HTTP_OTHER:          return 1;
            // فشل اتصال سريع (رفض/DNS...): محاولات رخيصة. أما انتهاء المهلة فدورة واحدة منه تستغرق
            // وحدها ~60 ث داخل ExoPlayer، فتكفي محاولة آلية واحدة للتفريق بين عطل عابر (تبديل شبكة)
            // وسيرفر ميّت — ثم نُظهر السبب بدل أن ينتظر المستخدم دقائق.
            case NETWORK_FAILED:      return 4;
            case NETWORK_TIMEOUT:     return 1;
            case BAD_RESPONSE:        return 2;
            case UNKNOWN_TRANSIENT:   return 2;
            default:                  return 0;
        }
    }

    private static boolean isNetworkSensitive(Kind kind) {
        return kind == Kind.NETWORK_FAILED || kind == Kind.NETWORK_TIMEOUT
                || kind == Kind.HTTP_SERVER || kind == Kind.UNKNOWN_TRANSIENT
                || kind == Kind.PLAYLIST_STUCK;
    }

    /**
     * الأخطاء التي يُرجَّح أنها ناتجة عن انتهاء صلاحية الرابط/الرمز المميَّز أو توقف المصدر
     * تُجدَّد روابطها (إن وُجد مُجدِّد موثوق)؛ أما أخطاء السيرفر المشغول/الشبكة فلا نضغط فيها
     * على واجهة التجديد بلا داعٍ.
     */
    static Action actionFor(Kind kind) {
        switch (kind) {
            case BEHIND_LIVE_WINDOW:
                return Action.SEEK_TO_LIVE_EDGE;
            case PLAYLIST_STUCK:
            case PLAYLIST_RESET:
            case HTTP_AUTH:
            case HTTP_NOT_FOUND:
            case BAD_RESPONSE:
                return Action.REFRESH_URL_AND_RETRY;
            default:
                return Action.RETRY_SAME_URL;
        }
    }

    static long delayFor(Kind kind, int attempt) {
        if (kind == Kind.BEHIND_LIVE_WINDOW && attempt == 1) {
            return 0L; // العودة لحافة البث فورية في أول مرة (العلاج الرسمي لهذا الخطأ)
        }
        int idx = Math.min(attempt - 1, BACKOFF_MS.length - 1);
        long d = BACKOFF_MS[Math.max(0, idx)];
        if (kind == Kind.HTTP_RATE_LIMIT) d *= 2; // السيرفر يطلب التمهّل
        return d;
    }

    // ---------------------------------------------------------------------------------------
    //  الرسائل (عربية، تذكر السبب الحقيقي بدل رسالة عامة واحدة لكل الحالات)
    // ---------------------------------------------------------------------------------------

    private static final String MSG_OFFLINE =
            "لا يوجد اتصال بالإنترنت. تحقق من الشبكة ثم أعد المحاولة.";

    private static String statusFor(Kind kind, int attempt, int max) {
        String progress = " (" + attempt + "/" + max + ")";
        switch (kind) {
            case BEHIND_LIVE_WINDOW:
                return "جارٍ العودة إلى البث المباشر…";
            case PLAYLIST_STUCK:
            case PLAYLIST_RESET:
                return "توقّف البث من المصدر — جارٍ إعادة الاتصال" + progress + "…";
            case HTTP_AUTH:
                return "رفض السيرفر الطلب — جارٍ إعادة المحاولة" + progress + "…";
            case HTTP_NOT_FOUND:
                return "القناة غير متاحة حالياً — جارٍ إعادة المحاولة" + progress + "…";
            case HTTP_RATE_LIMIT:
                return "السيرفر مشغول — ستُعاد المحاولة تلقائياً" + progress + "…";
            case HTTP_SERVER:
                return "السيرفر يواجه خللاً مؤقتاً — جارٍ إعادة المحاولة" + progress + "…";
            case NETWORK_FAILED:
            case NETWORK_TIMEOUT:
                return "ضعف في الاتصال بالمصدر — جارٍ إعادة المحاولة" + progress + "…";
            case BAD_RESPONSE:
                return "استجابة غير صالحة من السيرفر — جارٍ إعادة المحاولة" + progress + "…";
            default:
                return "جارٍ إعادة المحاولة" + progress + "…";
        }
    }

    /** الرسالة النهائية للمستخدم (بدون السطر التقني؛ المحرّك يُلحقه). */
    static String finalMessageFor(Failure f) {
        switch (f.kind) {
            case HTTP_AUTH:
                return "رفض السيرفر الاتصال (HTTP " + f.httpStatus + "). قد يكون الاشتراك منتهياً أو تم بلوغ "
                        + "الحد الأقصى للأجهزة المتصلة. أغلق القناة على أي جهاز آخر ثم أعد المحاولة.";
            case HTTP_NOT_FOUND:
                return "هذه القناة غير متوفرة حالياً على السيرفر (HTTP " + f.httpStatus
                        + "). قد تكون متوقفة أو تغيّر رابطها.";
            case HTTP_RATE_LIMIT:
                return "السيرفر يرفض الطلبات الكثيرة مؤقتاً (HTTP 429). انتظر قليلاً ثم أعد المحاولة.";
            case HTTP_SERVER:
                return "السيرفر يواجه خللاً مؤقتاً (HTTP " + f.httpStatus
                        + "). حاول لاحقاً أو اختر سيرفراً آخر للقناة.";
            case HTTP_OTHER:
                return "رفض السيرفر الطلب (HTTP " + f.httpStatus + ").";
            case NETWORK_FAILED:
            case NETWORK_TIMEOUT:
                return "تعذر الاتصال بمصدر البث. تأكد من الإنترنت، أو أن سيرفر القناة متوقف حالياً.";
            case PLAYLIST_STUCK:
                return "توقّف البث من مصدره ولم يعد يتجدد. قد تكون القناة متوقفة حالياً.";
            case PLAYLIST_RESET:
                return "أُعيد تشغيل البث من مصدره ولم نتمكن من الاتصال به مجدداً.";
            case BEHIND_LIVE_WINDOW:
                return "تعذر اللحاق بالبث المباشر بعد انقطاع طويل. أعد المحاولة.";
            case BAD_RESPONSE:
                return "استجاب السيرفر بمحتوى غير صالح كبث. قد يكون الرابط منتهي الصلاحية أو القناة معطّلة، "
                        + "أو أن صيغة الملف غير مدعومة في المشغل الداخلي (جرّب مشغلاً خارجياً من الإعدادات).";
            case UNSUPPORTED_FORMAT:
                return "صيغة هذا المحتوى غير مدعومة في المشغل الداخلي. جرّب مشغلاً خارجياً من الإعدادات.";
            case CLEARTEXT_BLOCKED:
                return "تم حظر اتصال غير مشفّر (HTTP) لهذا المصدر.";
            case DECODER:
                return "تعذر فك تشفير هذا الفيديو على هذا الجهاز. جرّب مشغلاً خارجياً من الإعدادات.";
            case DRM:
                return "هذا المحتوى محمي بنظام حماية غير مدعوم على هذا الجهاز.";
            default:
                return "تعذر تشغيل هذا المحتوى.";
        }
    }

    private static int valueOf(Integer v) {
        return v == null ? 0 : v;
    }
}
