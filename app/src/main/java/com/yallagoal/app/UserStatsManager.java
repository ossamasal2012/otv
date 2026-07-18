package com.yallagoal.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ============================================================================================
 *  نظام دقيق لحساب "عدد المستخدمين" (تثبيتات فريدة) و"المستخدمين النشطين الآن" (حاضرون فعلياً).
 * ============================================================================================
 *
 * تصميم هجين مقصود، كل جزء بالأداة الأنسب له، وكلاهما مجاني بالكامل بدون أي بطاقة دفع:
 *
 *  • "إجمالي المستخدمين" (نادر التغيّر — يزيد فقط عند جهاز جديد فعلياً) → Firebase Realtime
 *    Database. هذا الرقم لا سقف له إطلاقاً (يقدر يوصل لملايين)، لأنه مجرد قراءة/كتابة عرضية
 *    لعدد صحيح واحد، ولا يتطلب إبقاء اتصال دائم مفتوح.
 *
 *  • "المستخدمون النشطون الآن" (يتغيّر باستمرار طالما التطبيق مفتوح) → Cloudflare Worker +
 *    Workers KV، عبر "نبضات" HTTP دورية قصيرة بدل اتصال دائم. هذا يتفادى بالكامل سقف
 *    "الاتصالات المتزامنة" (100 فقط) المفروض على خطة Firebase المجانية، ولا يحتاج أي بطاقة
 *    دفع أيضاً (خطة Cloudflare المجانية بدون بطاقة إطلاقاً).
 *
 * كل استدعاءات هذا الصنف آمنة تماماً (Fail-safe): أي خلل بالشبكة أو بإعداد الخدمتين لا يُسقط
 * التطبيق أبداً ولا يؤثر على تشغيل القنوات — أقصى ما يحدث هو عدم تحديث الأرقام مؤقتاً.
 */
public final class UserStatsManager {

    /** يُستدعى مع آخر قيم معروفة عند كل تغيّر: إجمالي المستخدمين، والمستخدمون النشطون الآن. */
    public interface StatsListener {
        void onStatsChanged(long totalUsers, long activeUsers);
    }

    private static final String TAG = "UserStatsManager";

    private static final String PREFS_NAME = "yg_device_identity";
    private static final String PREF_FALLBACK_ID = "fallback_device_id";
    private static final String PREF_KNOWN_REGISTERED = "known_registered_v1";

    private static final String NODE_INSTALLS = "installs";
    private static final String NODE_STATS = "stats";
    private static final String FIELD_TOTAL_INSTALLS = "totalInstalls";

    // قيمة ANDROID_ID المعطوبة المعروفة على بعض الأجهزة/المحاكيات القديمة جداً — نستبعدها.
    private static final String KNOWN_BROKEN_ANDROID_ID = "9774d56d682e549c";

    // ============================================================================
    // ⚠️ مهم جداً: عدّل هذين السطرين بعد نشر الـ Cloudflare Worker (راجع
    // README_USER_STATS.md لخطوات النشر الكاملة). قبل التعديل، ميزة "نشط الآن"
    // تبقى معطّلة بهدوء (لا تُسبب أي خطأ)، بينما "إجمالي المستخدمين" يعمل طبيعياً.
    // ============================================================================
    private static final String ACTIVE_STATS_BASE_URL = "https://yallagoal-active-users.ossamasal2012.workers.dev";
    private static final String ACTIVE_STATS_SHARED_SECRET = "CehBJ9onRc16htCkWyMBnCMbM5vFqQ2zHfn8qtWlUW0";

    private static final long HEARTBEAT_INTERVAL_MS = 30_000L; // كل 30 ثانية طالما التطبيق مفتوح
    private static final int HTTP_TIMEOUT_MS = 8_000;

    private static volatile UserStatsManager instance;

    private final Context appContext;
    private final SharedPreferences identityPrefs;
    private final String deviceId;

    @Nullable private final DatabaseReference installRef;
    @Nullable private final DatabaseReference totalUsersRef;

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private volatile boolean heartbeatLoopRunning = false;

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!heartbeatLoopRunning) return;
            sendHeartbeatOnce();
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    private final CopyOnWriteArraySet<StatsListener> listeners = new CopyOnWriteArraySet<>();
    private volatile boolean totalUsersListenerAttached = false;

    private volatile long cachedTotalUsers = 0L;
    private volatile long cachedActiveUsers = 0L;

    private UserStatsManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.identityPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.deviceId = resolveDeviceId(appContext, identityPrefs);

        DatabaseReference root = null;
        try {
            root = FirebaseDatabase.getInstance().getReference();
        } catch (Exception e) {
            // يحدث فقط إذا لم يتم تفعيل/ربط Realtime Database بعد بمشروع فايربيس.
            // راجع README_USER_STATS.md لخطوات التفعيل. التطبيق يستمر بالعمل طبيعياً بدون هذه الميزة.
            Log.w(TAG, "Realtime Database غير مهيأة بعد: " + e.getMessage());
        }

        if (root != null) {
            installRef = root.child(NODE_INSTALLS).child(deviceId);
            totalUsersRef = root.child(NODE_STATS).child(FIELD_TOTAL_INSTALLS);
        } else {
            installRef = null;
            totalUsersRef = null;
        }
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
     * إعادة ضبط مصنع). نُمرّره عبر SHA-256 قبل استخدامه كمعرّف فلا نخزّن أي قيمة خام حساسة.
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

    // ============================ تسجيل "مستخدم" (تثبيت فريد) ============================
    // (Firebase Realtime Database — بدون أي سقف على عدد "المستخدمين" الإجمالي)

    /**
     * تُستدعى مرة عند كل بدء تشغيل للتطبيق (Application.onCreate). آمنة تماماً للاستدعاء
     * المتكرر: تستخدم علامة محلية سريعة لتفادي طلب شبكي غير ضروري بعد أول تسجيل ناجح، لكن
     * حتى لو فُقدت هذه العلامة المحلية (مثلاً "مسح بيانات التطبيق" دون حذفه)، فالـ Transaction
     * الذرّية على الخادم تبقى المصدر الوحيد للحقيقة ولا يمكن أن تُسبب عدّاً مضاعفاً أبداً.
     */
    public void registerOrTouch() {
        if (installRef == null || totalUsersRef == null) return;

        if (identityPrefs.getBoolean(PREF_KNOWN_REGISTERED, false)) {
            touchLastSeen();
            return;
        }

        final Map<String, Object> newInstallData = buildInstallPayload(true);

        installRef.runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData currentData) {
                if (currentData.getValue() == null) {
                    currentData.setValue(newInstallData);
                    return Transaction.success(currentData);
                }
                // الجهاز مسجّل مسبقاً على الخادم (تحديث تطبيق، أو علامة محلية فُقدت) — لا نلمس
                // تاريخ أول تثبيت إطلاقاً، ولا نزيد عداد الإجمالي.
                return Transaction.abort();
            }

            @Override
            public void onComplete(@Nullable DatabaseError error, boolean committed,
                                    @Nullable DataSnapshot snapshot) {
                if (error != null) {
                    Log.w(TAG, "تعذر تسجيل الجهاز: " + error.getMessage());
                    return; // لا نضبط العلامة المحلية؛ سنعيد المحاولة بالمرة القادمة.
                }

                identityPrefs.edit().putBoolean(PREF_KNOWN_REGISTERED, true).apply();

                if (committed) {
                    // هذا الجهاز يُرى للمرة الأولى فعلياً على الخادم بأكمله -> مستخدم جديد بالكامل.
                    incrementTotalUsersExactlyOnce();
                } else {
                    // كان مسجلاً مسبقاً (تحديث/حذف وإعادة تثبيت/فتح متكرر) -> لا زيادة بالعدّاد إطلاقاً.
                    touchLastSeen();
                }
            }
        });
    }

    private void incrementTotalUsersExactlyOnce() {
        if (totalUsersRef == null) return;
        totalUsersRef.runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData currentData) {
                Long current = currentData.getValue(Long.class);
                currentData.setValue((current == null ? 0L : current) + 1L);
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(@Nullable DatabaseError error, boolean committed,
                                    @Nullable DataSnapshot snapshot) {
                if (error != null) {
                    Log.w(TAG, "تعذر تحديث إجمالي المستخدمين: " + error.getMessage());
                }
            }
        });
    }

    private void touchLastSeen() {
        if (installRef == null) return;
        installRef.updateChildren(buildInstallPayload(false));
    }

    private Map<String, Object> buildInstallPayload(boolean isFirstSeen) {
        Map<String, Object> data = new HashMap<>();
        if (isFirstSeen) {
            data.put("firstInstallAt", ServerValue.TIMESTAMP);
        }
        data.put("lastSeenAt", ServerValue.TIMESTAMP);
        data.put("appVersionName", getAppVersionName());
        data.put("appVersionCode", getAppVersionCode());
        data.put("platform", "android");
        return data;
    }

    private String getAppVersionName() {
        try {
            PackageInfo info = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0);
            return info.versionName != null ? info.versionName : "unknown";
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    private long getAppVersionCode() {
        try {
            PackageInfo info = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0L;
        }
    }

    // ======================== الحضور (المستخدم النشط الآن) ========================
    // (Cloudflare Worker عبر HTTP — بدون اتصال دائم، وبدون أي سقف اتصالات متزامنة)

    /**
     * التطبيق بأكمله أصبح بالمقدمة فعلياً أمام المستخدم. تُستدعى من ProcessLifecycleOwner.onStart
     * على مستوى التطبيق كله (وليس نشاطاً منفرداً)، فلا تتأثر بدوران الشاشة أو التنقل الداخلي.
     * تبدأ حلقة "نبضات" HTTP دورية (كل 30 ثانية) تبقي هذا الجهاز محتسباً ضمن "نشط الآن"، وتتوقف
     * تلقائياً بمجرد استدعاء markInactive().
     */
    public void markActive() {
        touchLastSeen();

        if (heartbeatLoopRunning) return;
        heartbeatLoopRunning = true;
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        heartbeatHandler.post(heartbeatRunnable);
    }

    /**
     * التطبيق بأكمله انتقل للخلفية (لا شاشة منه ظاهرة). نوقف حلقة النبضات فوراً، ونرسل إشعار
     * "خروج" أخير (best-effort) ليختفي هذا الجهاز من عدّاد "نشط الآن" فوراً بدل انتظار انتهاء
     * صلاحية آخر نبضة على خادم Cloudflare (وهي خط أمان احتياطي فقط للحالات غير الطبيعية مثل
     * تعطّل التطبيق أو فقد الشبكة المفاجئ، حيث يختفي الجهاز تلقائياً من العدّاد خلال ثوانٍ معدودة
     * دون الحاجة لأي إشعار صريح منه).
     */
    public void markInactive() {
        heartbeatLoopRunning = false;
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        sendLeaveOnce();
    }

    private void sendHeartbeatOnce() {
        networkExecutor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("deviceId", deviceId);
                JSONObject response = postJson("/heartbeat", body);
                applyActiveCountFromResponse(response);
            } catch (Exception e) {
                Log.w(TAG, "تعذر إرسال نبضة النشاط: " + e.getMessage());
            }
        });
    }

    private void sendLeaveOnce() {
        networkExecutor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("deviceId", deviceId);
                JSONObject response = postJson("/leave", body);
                applyActiveCountFromResponse(response);
            } catch (Exception e) {
                Log.w(TAG, "تعذر إرسال إشعار الخروج: " + e.getMessage());
            }
        });
    }

    private void applyActiveCountFromResponse(@Nullable JSONObject response) {
        if (response == null || !response.has("active")) return;
        cachedActiveUsers = response.optLong("active", cachedActiveUsers);
        notifyListeners();
    }

    /** ينفّذ طلب POST بصيغة JSON على خادم Cloudflare Worker. يعمل دوماً على خيط بالخلفية. */
    @Nullable
    private JSONObject postJson(String path, JSONObject body) {
        if (ACTIVE_STATS_BASE_URL.contains("REPLACE_ME")) {
            // Worker لم يُنشر/يُهيّأ بعد بهذا المشروع — لا نحاول الاتصال، ولا نُسبب أي خطأ مرئي.
            return null;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ACTIVE_STATS_BASE_URL + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-YG-Secret", ACTIVE_STATS_SHARED_SECRET);
            conn.setDoOutput(true);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }

            if (code < 200 || code >= 300) {
                Log.w(TAG, "خادم إحصائيات النشاط أعاد الحالة " + code + ": " + sb);
                return null;
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            Log.w(TAG, "خطأ اتصال بخادم إحصائيات النشاط: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
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
        ensureTotalUsersListenerAttached();
        listener.onStatsChanged(cachedTotalUsers, cachedActiveUsers);
    }

    public void removeListener(StatsListener listener) {
        listeners.remove(listener);
    }

    private void ensureTotalUsersListenerAttached() {
        if (totalUsersListenerAttached) return;
        if (totalUsersRef == null) return;
        totalUsersListenerAttached = true;

        totalUsersRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Long value = snapshot.getValue(Long.class);
                cachedTotalUsers = value == null ? 0L : value;
                notifyListeners();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "تعذر متابعة إجمالي المستخدمين: " + error.getMessage());
            }
        });
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
