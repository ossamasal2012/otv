package com.yallagoal.app;

import android.view.ViewGroup;

/**
 * طبقة تجريد مشتركة بين محرّكي التشغيل الداخليّين (ExoPlayer عبر AndroidX Media3، وLibVLC).
 * تسمح لـ InternalPlayerActivity وواجهته المخصّصة (شريط تقدّم/تقديم-ترجيع 10 ثوانٍ/تشغيل-إيقاف)
 * بالعمل بنفس الشكل تماماً بغضّ النظر عن أي محرّك اختاره المستخدم من الإعدادات.
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

    /** بالميلي ثانية؛ صفر أو قيمة سالبة إن لم تكن معروفة (مثل البث المباشر بلا مدة ثابتة). */
    long getDuration();

    void seekTo(long positionMs);

    /** يحرر كل موارد المحرّك (ذاكرة/سطح عرض/اتصالات) — يجب استدعاؤها دائماً عند الإغلاق. */
    void release();

    interface Listener {
        /** أول مرة تصبح فيها مدة الفيديو معروفة وجاهزاً فعلياً للتشغيل. */
        void onReady(long durationMs);

        void onBuffering(boolean isBuffering);

        /** رسالة مفهومة جاهزة للعرض مباشرة للمستخدم، وليست نص استثناء تقني خام. */
        void onError(String userMessage);

        void onEnded();

        void onPlayingStateChanged(boolean isPlaying);
    }
}
