package com.yallagoal.app;

import android.view.ViewGroup;

import java.util.List;

/**
 * طبقة تجريد مشتركة بين محرّكي التشغيل الداخليّين (ExoPlayer عبر AndroidX Media3، وLibVLC).
 * تسمح لـ InternalPlayerActivity وواجهته المخصّصة (شريط تقدّم/تقديم-ترجيع 10 ثوانٍ/تشغيل-إيقاف/
 * وضع ملء الشاشة/قائمة الدقات) بالعمل بنفس الشكل تماماً بغضّ النظر عن أي محرّك اختاره المستخدم
 * من الإعدادات.
 */
interface PlaybackEngine {

    /** يُستدعى مرة واحدة قبل أي استخدام آخر. */
    void initialize(android.content.Context context);

    /** يُلحق واجهة عرض الفيديو الخاصة بهذا المحرّك داخل الحاوية المُعطاة. */
    void attachTo(ViewGroup container);

    void setListener(Listener listener);

    /** يبدأ تشغيل الرابط المُعطى، ويقفز مباشرة لموضع الاستئناف إن كان أكبر من صفر. */
    void playUrl(String url, long startPositionMs);

    void play();

    void pause();

    boolean isPlaying();

    /** بالميلي ثانية؛ صفر إن لم تكن معروفة بعد. */
    long getCurrentPosition();

    /** بالميلي ثانية؛ صفر أو قيمة سالبة إن لم تكن معروفة (مثل البث المباشر بلا مدة ثابتة أو نافذة DVR). */
    long getDuration();

    void seekTo(long positionMs);

    /** يحرر كل موارد المحرّك (ذاكرة/سطح عرض/اتصالات) — يجب استدعاؤها دائماً عند الإغلاق. */
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

        /** رسالة مفهومة جاهزة للعرض مباشرة للمستخدم، وليست نص استثناء تقني خام. */
        void onError(String userMessage);

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
