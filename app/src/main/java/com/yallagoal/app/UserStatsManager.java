package com.yallagoal.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * ============================================================================================
 *  نظام دقيق لحساب "عدد المستخدمين" (تثبيتات فريدة) و"المستخدمين النشطين الآن" (حاضرون فعلياً).
 * ============================================================================================
 *
 *  إعادة تصميم كاملة: كل المنطق الآن على خادم واحد (Cloudflare Durable Object بتخزين SQLite
 *  حقيقي)، عبر اتصال WebSocket واحد فقط لكل جهاز. لا يوجد فايربيس هنا إطلاقاً بعد الآن.
 *
 *  لماذا؟ لأن معاملات Realtime Database تعتمد على اتصال حي وتفاعل دقيق مع صلاحيات .read/.write
 *  يصعب ضمانه بثقة تامة، وهذا هو السبب الجذري الأرجح وراء مشكلة "لا يُحتسب إلا مستخدم واحد".
 *  الحل الجذري: خادمنا الخاص الذي نتحكم بمنطقه بالكامل ونفهمه تماماً، بمعالجة متسلسلة صارمة
 *  (Single-threaded) تمنع أي احتمال لتعارض بين جهازين يسجّلان بنفس اللحظة رياضياً.
 *
 *  اتصال الـ WebSocket نفسه يخدم غرضين معاً بكل فتح للتطبيق:
 *   1) "لمسة" تسجّل/تُحدّث هذا الجهاز في جدول التثبيتات على الخادم (يحدد "مستخدم فريد").
 *   2) حضوراً حياً يجعل هذا الجهاز محتسباً ضمن "نشط الآن" طالما الاتصال مفتوح.
 *
 *  كل استدعاءات هذا الصنف آمنة تماماً (Fail-safe): أي خلل بالشبكة أو بإعداد الخادم لا يُسقط
 *  التطبيق أبداً ولا يؤثر على تشغيل القنوات — أقصى ما يحدث هو عدم تحديث الأرقام مؤقتاً.
 */
public final class UserStatsManager {

    /** يُستدعى مع آخر قيم معروفة عند كل تغيّر: إجمالي المستخدمين، والمستخدمون النشطون الآن. */
    public interface StatsListener {
        void onStatsChanged(long totalUsers, long activeUsers);
    }

    private static final String TAG = "UserStatsManager";

    private static final String PREFS_NAME = "yg_device_identity";
    private static final String PREF_FALLBACK_ID = "fallback_device_id";

    // قيمة ANDROID_ID المعطوبة المعروفة على بعض الأجهزة/المحاكيات القديمة جداً — نستبعدها.
    private static final String KNOWN_BROKEN_ANDROID_ID = "9774d56d682e549c";

    // ============================================================================
    // ⚠️ مهم: عدّل هذين السطرين حسب رابط وسر Cloudflare Worker عندك (لم يتغيّرا عن آخر مرة —
    // لا حاجة لإعادة نشر أي binding جديد، نفس الـ Worker والمعرّفات المستخدمة سابقاً).
    // ============================================================================
    private static final String ACTIVE_STATS_BASE_URL = "https://yallagoal-active-users.ossamasal2012.workers.dev";
    private static final String ACTIVE_STATS_SHARED_SECRET = "CehBJ9onRc16htCkWyMBnCMbM5vFqQ2zHfn8qtWlUW0";

    // نبضة تطبيقية خفيفة كل 15 ثانية تبقي الخادم يعرف أن هذا الاتصال لا يزال حياً فعلاً —
    // أساس اكتشاف الانقطاع المفاجئ خلال وقت قصير ومضبوط (راجع alarm() بملف worker.js).
    private static final long PING_INTERVAL_MS = 15_000L;
    private static final long RECONNECT_DELAY_MS = 4_000L;

    private static volatile UserStatsManager instance;

    private final String deviceId;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .build();

    private final Handler wsHandler = new Handler(Looper.getMainLooper());
    private volatile boolean wantsConnection = false;
    @Nullable private volatile WebSocket activeSocket;

    private final Runnable pingRunnable = new Runnable() {
        @Override
        public void run() {
            WebSocket socket = activeSocket;
            if (socket != null) {
                try {
                    socket.send("{\"type\":\"ping\"}");
                } catch (Exception ignored) {}
            }
            if (wantsConnection) {
                wsHandler.postDelayed(this, PING_INTERVAL_MS);
            }
        }
    };

    private final CopyOnWriteArraySet<StatsListener> listeners = new CopyOnWriteArraySet<>();

    private volatile long cachedTotalUsers = 0L;
    private volatile long cachedActiveUsers = 0L;

    private UserStatsManager(Context context) {
        Context appContext = context.getApplicationContext();
        SharedPreferences identityPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.deviceId = resolveDeviceId(appContext, identityPrefs);
    }

    public static UserStatsManager getInstance(Context context) {
        UserStatsManager local = instance;
        if (local == null) {
            synchronized (UserStatsManager.class) {
                local = instance;
                if (local == null) {
                    local = new UserStatsManager(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    // ================================== هوية الجهاز ==================================

    /**
     * معرّف ثابت للجهاز الفعلي (وليس للتثبيت الحالي فقط)، مبني على ANDROID_ID المُوثّق رسمياً
     * ببقائه كما هو عبر تحديثات التطبيق وحتى عبر حذفه وإعادة تثبيته (بنفس توقيع التطبيق ودون
     * إعادة ضبط مصنع). نُمرّره عبر SHA-256 قبل استخدامه كمعرّف فلا نخزّن أي قيمة خام حساسة،
     * والنتيجة hex نظيفة تماماً (أحرف/أرقام فقط) صالحة للاستخدام كوسيطة رابط بأمان.
     */
    private static String resolveDeviceId(Context context, SharedPreferences prefs) {
        String androidId = null;
        try {
            androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception ignored) {
            // بعض الأجهزة النادرة جداً قد تمنع القراءة؛ ننتقل للخط البديل أدناه.
        }

        String rawIdentity;
        if (androidId != null && androidId.trim().length() >= 8
                && !KNOWN_BROKEN_ANDROID_ID.equalsIgnoreCase(androidId.trim())) {
            rawIdentity = "and1:" + androidId.trim();
        } else {
            // خط أمان أخير نادر الاستخدام: معرّف عشوائي محفوظ محلياً. لن ينجو من حذف التطبيق،
            // لكنه يضمن أن الميزة لا تتعطل أبداً حتى على الأجهزة التي لا تدعم ANDROID_ID بشكل طبيعي.
            String fallback = prefs.getString(PREF_FALLBACK_ID, null);
            if (fallback == null) {
                fallback = UUID.randomUUID().toString();
                prefs.edit().putString(PREF_FALLBACK_ID, fallback).apply();
            }
            rawIdentity = "fb1:" + fallback;
        }

        return sha256(rawIdentity + ":com.yallagoal.app");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    // ======================== الاتصال الحيّ (تسجيل + حضور معاً) ========================

    /**
     * التطبيق بأكمله أصبح بالمقدمة فعلياً أمام المستخدم. تُستدعى من ProcessLifecycleOwner.onStart
     * على مستوى التطبيق كله (وليس نشاطاً منفرداً)، فلا تتأثر بدوران الشاشة أو التنقل الداخلي.
     * فتح الاتصال وحده يكفي: الخادم يسجّل/يحدّث هذا الجهاز في جدول التثبيتات تلقائياً بمجرده
     * (راجع touchInstall بملف worker.js)، فلا حاجة لأي طلب تسجيل منفصل إطلاقاً.
     */
    public void markActive() {
        wantsConnection = true;
        connectWebSocketIfNeeded();
    }

    /**
     * التطبيق بأكمله انتقل للخلفية (لا شاشة منه ظاهرة). نغلق الاتصال فوراً وبشكل صريح، فيختفي
     * هذا الجهاز من عدّاد "نشط الآن" على الفور عند كل الأجهزة الأخرى بنفس اللحظة تقريباً.
     */
    public void markInactive() {
        wantsConnection = false;
        wsHandler.removeCallbacksAndMessages(null);
        WebSocket socket = activeSocket;
        activeSocket = null;
        if (socket != null) {
            try {
                socket.close(1000, "app_backgrounded");
            } catch (Exception ignored) {}
        }
    }

    private void connectWebSocketIfNeeded() {
        if (activeSocket != null) return;
        if (ACTIVE_STATS_BASE_URL.contains("REPLACE_ME")) {
            // Worker لم يُنشر/يُهيّأ بعد بهذا المشروع — لا نحاول الاتصال، ولا نُسبب أي خطأ مرئي.
            return;
        }

        String wsUrl;
        try {
            wsUrl = ACTIVE_STATS_BASE_URL.replaceFirst("^https://", "wss://")
                    .replaceFirst("^http://", "ws://") + "/ws?deviceId=" + deviceId;
        } catch (Exception e) {
            return;
        }

        Request request;
        try {
            request = new Request.Builder()
                    .url(wsUrl)
                    .addHeader("X-YG-Secret", ACTIVE_STATS_SHARED_SECRET)
                    .build();
        } catch (Exception e) {
            Log.w(TAG, "رابط خادم الإحصائيات غير صالح: " + e.getMessage());
            return;
        }

        activeSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                wsHandler.removeCallbacks(pingRunnable);
                wsHandler.postDelayed(pingRunnable, PING_INTERVAL_MS);
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                try {
                    JSONObject json = new JSONObject(text);
                    boolean changed = false;
                    if (json.has("total")) {
                        cachedTotalUsers = json.optLong("total", cachedTotalUsers);
                        changed = true;
                    }
                    if (json.has("active")) {
                        cachedActiveUsers = json.optLong("active", cachedActiveUsers);
                        changed = true;
                    }
                    if (changed) notifyListeners();
                } catch (Exception e) {
                    Log.w(TAG, "رسالة غير متوقعة من خادم الإحصائيات: " + e.getMessage());
                }
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                Log.w(TAG, "انقطع اتصال الإحصائيات المباشر: " + t.getMessage());
                if (webSocket == activeSocket) {
                    activeSocket = null;
                }
                scheduleReconnect();
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                if (webSocket == activeSocket) {
                    activeSocket = null;
                }
                // لو انغلق الاتصال من طرف الخادم أو الشبكة (لا نحن من طلب الإغلاق) بينما التطبيق
                // لا يزال بالمقدمة، نعيد الاتصال فوراً تقريباً.
                if (wantsConnection && code != 1000) {
                    scheduleReconnect();
                }
            }
        });
    }

    private void scheduleReconnect() {
        if (!wantsConnection) return;
        wsHandler.postDelayed(() -> {
            if (wantsConnection) connectWebSocketIfNeeded();
        }, RECONNECT_DELAY_MS);
    }

    // ================================ أرقام حيّة (Live) ================================

    public long getCachedTotalUsers() {
        return cachedTotalUsers;
    }

    public long getCachedActiveUsers() {
        return cachedActiveUsers;
    }

    /** يضيف مستمعاً ويرسل له فوراً آخر قيمة معروفة، ثم يستمر بإرسال أي تحديث لاحق فور حدوثه. */
    public void addListener(StatsListener listener) {
        listeners.add(listener);
        listener.onStatsChanged(cachedTotalUsers, cachedActiveUsers);
    }

    public void removeListener(StatsListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (StatsListener listener : listeners) {
            try {
                listener.onStatsChanged(cachedTotalUsers, cachedActiveUsers);
            } catch (Exception e) {
                Log.w(TAG, "خطأ داخل مستمع الإحصائيات: " + e.getMessage());
            }
        }
    }
}
