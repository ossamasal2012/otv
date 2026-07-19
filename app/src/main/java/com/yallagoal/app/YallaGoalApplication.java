package com.yallagoal.app;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

/**
 * نستخدم ProcessLifecycleOwner (من AndroidX) بدل الاعتماد على onPause/onResume الخاصة بنشاط
 * (Activity) واحد، لأن ذلك الأخير يتأثر بدوران الشاشة وتعدد الشاشات الداخلية فيعطي نتائج غير
 * دقيقة. ProcessLifecycleOwner.onStart لا يُستدعى إلا عندما ينتقل التطبيق بأكمله من الخلفية
 * إلى المقدمة فعلياً، و onStop لا يُستدعى إلا عندما لا تبقى أي شاشة منه ظاهرة على الإطلاق —
 * وهذا هو التعريف الدقيق المطلوب لـ"مستخدم نشط الآن".
 */
public class YallaGoalApplication extends Application implements DefaultLifecycleObserver {

    private static final String TAG = "YallaGoalApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        // التطبيق بأكمله بات ظاهراً أمام المستخدم الآن -> "نشط". فتح الاتصال هنا وحده يكفي
        // ليُسجَّل هذا الجهاز أيضاً كـ"مستخدم فريد" تلقائياً على الخادم (راجع UserStatsManager).
        try {
            UserStatsManager.getInstance(this).markActive();
        } catch (Exception e) {
            Log.w(TAG, "تعذر تحديث حالة النشاط (دخول): " + e.getMessage());
        }
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        // لم تعد أي شاشة من التطبيق ظاهرة -> ليس "نشطاً"، لكنه يبقى "مستخدماً" مسجّلاً.
        try {
            UserStatsManager.getInstance(this).markInactive();
        } catch (Exception e) {
            Log.w(TAG, "تعذر تحديث حالة النشاط (خروج): " + e.getMessage());
        }
    }
}
