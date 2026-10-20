package com.yallagoal.app;

import android.view.ViewGroup;

import java.util.List;

/**
 * طبقة تجريد لمحرّك التشغيل الداخلي (ExoPlayer عبر AndroidX Media3).
 * تسمح لـ InternalPlayerActivity وواجهته المخصّصة (شريط تقدّم/تقديم-ترجيع 10 ثوانٍ/تشغيل-إيقاف/
 * وضع ملء الشاشة/قائمة الدقات) بالعمل خلف واجهة موحّدة بمعزل عن تفاصيل تطبيق المحرّك الفعلي.
 */
interface PlaybackEngine {

    /** يُستدعى مرة واحدة قبل أي استخدام آخر. */
    void initialize(android.content.Context context);

    /** يُلحق واجهة عرض الفيديو الخاصة بهذا المحرّك داخل الحاوية المُعطاة. */
    void attachTo(ViewGroup container);

    void setListener(Listener listener);

    /**
     * يبدأ تشغيل الرابط المُعطى، ويقفز مباشرة لموضع الاستئناف إن كان أكبر من صفر.
     * استدعاؤه يُلغي أي تعافٍ آلي قيد الانتظار ويُصفّر عدّادات المحاولات (تشغيل جديد = حادثة جديدة).
     */
    void playUrl(String url, long startPositionMs);

    /**
     * مصدر موثوق لتجديد رابط البث عند انتهاء صلاحيته (مثل واجهة API التي تُرجع stream_url بتوكن
     * مؤقت). اختياري: بدونه يُعاد استخدام الرابط الأصلي نفسه عند التعافي (وهو كافٍ لروابط Xtream
     * لأن الرابط الأصلي نفسه يُصدر توكناً جديداً عند كل طلب). يُستدعى دائماً من خيط خلفي.
     */
    interface UrlRefresher {
        String fetchFreshUrl() throws java.io.IOException;
    }

    void setUrlRefresher(UrlRefresher refresher);

    void play();

    void pause();

    boolean isPlaying();

    /** بالميلي ثانية؛ صفر إن لم تكن معروفة بعد. */
    long getCurrentPosition();

    /** بالميلي ثانية؛ صفر أو قيمة سالبة إن لم تكن معروفة (مثل البث المباشر بلا مدة ثابتة أو نافذة DVR). */
    long getDuration();

    /** نهاية ما خُزِّن مؤقتاً (Buffer) بالميلي ثانية؛ صفر إن لم يكن معروفاً. يُستخدم لرسم شريط التخزين بالواجهة. */
    long getBufferedPosition();

    /** سرعة التشغيل (1.0 = عادية، 2.0 = ضعف السرعة). تُطبَّق فوراً وتبقى عبر إعادة المحاولة. */
    void setPlaybackSpeed(float speed);

    void seekTo(long positionMs);

    /**
     * يحرر كل موارد المحرّك (ذاكرة/سطح عرض/اتصالات/مؤقتات التعافي) — يجب استدعاؤها دائماً عند الإغلاق.
     * آمنة للاستدعاء أكثر من مرة، وتُستدعى فوراً عند مغادرة القناة (لا تُنتظر onDestroy) حتى لا يبقى
     * اتصال البث القديم مفتوحاً أثناء فتح القناة التالية.
     */
    void release();

    /**
     * أوضاع ملء الشاشة الثلاثة:
     * FIT  — احتواء الفيديو كاملاً داخل الشاشة مع الحفاظ على نسبة الأبعاد (قد تظهر أشرطة سوداء).
     * FILL — تمديد الفيديو ليملأ الشاشة تماماً بدون أشرطة سوداء (قد يُشوّه الصورة قليلاً إن اختلفت النسبة).
     * ZOOM — تكبير الفيديو ليملأ الشاشة مع الحفاظ على نسبة الأبعاد (يقصّ الزائد بدل تشويهه).
     */
    enum ResizeMode { FIT, FILL, ZOOM }

    /** يُطبَّق فوراً على العرض الحالي؛ يُستدعى في أي وقت بعد attachTo(). */
    void setResizeMode(ResizeMode mode);

    /**
     * خيار دقة واحد متاح للفيديو/القناة الحالية.
     * id: يُستخدم لاحقاً مع selectQuality() فقط (ليس بالضرورة نصاً قابلاً للعرض مباشرة).
     * label: نص جاهز للعرض بالواجهة كما هو (مثل "1080p").
     */
    final class QualityOption {
        final String id;
        final String label;

        QualityOption(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    /**
     * كل الدقات المكتشفة حالياً لهذا الفيديو/البث، الأعلى أولاً — لا تتضمن "تلقائي" إطلاقاً
     * (هذا خيار واجهة يُضيفه المستدعي بنفسه دائماً كأول عنصر ثابت). قد تكون القائمة فارغة إذا
     * لم تُكتشف أي دقات بعد (المعلومات لم تصل من الشبكة بعد)، أو كان المصدر بدقة واحدة فقط لا
     * يوفّرها المحرّك كخيارات منفصلة قابلة للاختيار — هذا سلوك متوقّع وليس خطأً.
     */
    List<QualityOption> getAvailableQualities();

    /** "auto" إذا لم يُفعَّل أي اختيار يدوي حالياً، وإلا id مطابق لأحد عناصر getAvailableQualities(). */
    String getSelectedQualityId();

    /** "auto" يُعيد الاختيار التلقائي/التكيّفي، وأي id آخر (من getAvailableQualities فقط) يثبّت تلك الدقة تحديداً. */
    void selectQuality(String qualityId);

    interface Listener {
        /** أول مرة تصبح فيها مدة الفيديو معروفة وجاهزاً فعلياً للتشغيل. */
        void onReady(long durationMs);

        void onBuffering(boolean isBuffering);

        /**
         * خطأ نهائي بعد استنفاد محاولات التعافي الآلية (أو خطأ دائم لا تفيد فيه الإعادة): رسالة
         * مفهومة جاهزة للعرض مباشرة للمستخدم، وليست نص استثناء تقني خام.
         */
        void onError(String userMessage);

        /**
         * المحرّك يحاول التعافي تلقائياً من فشل مؤقت (قبل أي رسالة خطأ). نص حالة قصير يُعرض
         * فوق دائرة التحميل حتى لا ينتظر المستخدم بصمت؛ يُمسح تلقائياً عند أول onReady.
         */
        void onRecovering(String statusMessage);

        void onEnded();

        void onPlayingStateChanged(boolean isPlaying);

        /**
         * يُستدعى كل مرة تتوفر أو تتغيّر فيها معلومات مسارات الفيديو (الدقات المتاحة) — الوقت
         * المناسب لإعادة قراءة getAvailableQualities() إن كانت قائمة إعدادات الدقة مفتوحة حالياً
         * بالواجهة، لتحديثها فوراً دون حاجة لإغلاقها وإعادة فتحها.
         */
        void onTracksChanged();
    }
}
