package com.yallagoal.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * يحفظ نقطة التوقف الأخيرة لكل فيديو (فيلم/حلقة) على حدة، ليُستأنف منها تلقائياً عند فتح
 * نفس المحتوى مرة أخرى — للفيديوهات فقط (movie/episode)، ولا يُستخدم إطلاقاً مع البث
 * المباشر (القنوات) لأنه لا يملك نقطة توقف ذات معنى أصلاً.
 *
 * المفتاح (key) يُبنى من طرف الاستدعاء (المسؤول عن كونه ثابتاً ومميّزاً لكل فيلم/حلقة على
 * حدة، بمعزل عن السيرفر) — راجع InternalPlayerActivity لكيفية بنائه بالضبط.
 */
final class PlaybackPositionStore {

    private static final String PREFS_NAME = "yg_playback_positions";

    // لا معنى لحفظ نقطة استئناف قبل هذه المدة (بداية الفيديو فعلياً)
    private static final long MIN_SAVE_POSITION_MS = 5_000L;

    // لو بقي أقل من هذه المدة على نهاية الفيديو، نعتبره "شُوهد بالكامل" ونمسح نقطة الاستئناف
    // بدل حفظها قرب النهاية تماماً (حتى لا يُعاد فتحه في المرة القادمة عند آخر ثانية تقريباً)
    private static final long NEAR_END_THRESHOLD_MS = 15_000L;

    private PlaybackPositionStore() {
    }

    static void savePosition(Context context, String key, long positionMs, long durationMs) {
        if (context == null || key == null || key.isEmpty()) return;
        try {
            SharedPreferences.Editor editor =
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();

            boolean tooEarly = positionMs < MIN_SAVE_POSITION_MS;
            boolean nearEnd = durationMs > 0 && positionMs >= (durationMs - NEAR_END_THRESHOLD_MS);

            if (tooEarly || nearEnd) {
                editor.remove(key).apply();
            } else {
                editor.putLong(key, positionMs).apply();
            }
        } catch (Exception ignored) {
            // فشل حفظ نقطة الاستئناف ليس خطأً حرجاً — لا يجب أن يؤثر على التشغيل نفسه إطلاقاً
        }
    }

    static long getPosition(Context context, String key) {
        if (context == null || key == null || key.isEmpty()) return 0L;
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(key, 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    static void clearPosition(Context context, String key) {
        if (context == null || key == null || key.isEmpty()) return;
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(key).apply();
        } catch (Exception ignored) {
        }
    }
}
