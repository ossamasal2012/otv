package com.yallagoal.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * تخزين «إعداد الصوت الإضافي» لكل قناة/فيلم/حلقة على الجهاز (SharedPreferences خاصة بالتطبيق).
 *
 * <p>المفتاح: هوية المحتوى (لا الرابط الخام): للأفلام والحلقات resumeKey الثابت؛ للقنوات المباشرة اسم
 * القناة (فيبقى الإعداد عند تبديل السيرفر)؛ وإلا رابط التحديث الثابت أو الرابط بعد حذف مُعاملات
 * التوكن المتغيّرة. المفتاح الفعلي في التخزين هو SHA-256 (لا نكتب روابط فيها بيانات دخول بشكل صريح).
 *
 * <p>المحتوى المحفوظ: المصدر (رابط أو ملف)، مستويا الصوتين، كتم كلٍّ منهما، وإزاحة المزامنة.
 */
final class ExtraAudioStore {

    private static final String PREFS = "yg_extra_audio";
    private static final int MAX_ENTRIES = 200;

    /** مُعاملات تتغيّر عادةً بين جلسة وأخرى فلا تدخل في هوية القناة. */
    private static final String[] VOLATILE_PARAMS = {
            "token", "tokens", "tk", "tkn", "expires", "expire", "expiry", "exp", "e", "sig", "signature",
            "sign", "hash", "md5", "hmac", "hdnts", "hdnea", "hdntl", "st", "ts", "time", "timestamp", "t",
            "nonce", "auth", "authorization", "wmsauthsign", "validfrom", "validto", "valid", "verify",
            "session", "sid", "rand", "random", "r", "_", "zt", "key", "sessionid", "uid", "ip"
    };

    private ExtraAudioStore() {
    }

    /** نموذج الإعداد المحفوظ. */
    static final class Profile {
        static final String TYPE_URL = "u";
        static final String TYPE_FILE = "f";

        String type = TYPE_URL;
        String source = "";
        String name = "";
        int originalVolumePct = 100;
        int extraVolumePct = 100;
        boolean originalMuted = false;
        boolean extraMuted = false;
        long offsetMs = 0L;
        long updatedAt = 0L;

        boolean isFile() {
            return TYPE_FILE.equals(type);
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("t", type);
            o.put("s", source);
            o.put("n", name);
            o.put("ov", originalVolumePct);
            o.put("xv", extraVolumePct);
            o.put("om", originalMuted);
            o.put("xm", extraMuted);
            o.put("off", offsetMs);
            o.put("ts", updatedAt);
            return o;
        }

        static Profile fromJson(String json) {
            try {
                JSONObject o = new JSONObject(json);
                Profile p = new Profile();
                p.type = TYPE_FILE.equals(o.optString("t")) ? TYPE_FILE : TYPE_URL;
                p.source = o.optString("s", "");
                p.name = o.optString("n", "");
                p.originalVolumePct = clampPct(o.optInt("ov", 100));
                p.extraVolumePct = clampPct(o.optInt("xv", 100));
                p.originalMuted = o.optBoolean("om", false);
                p.extraMuted = o.optBoolean("xm", false);
                p.offsetMs = o.optLong("off", 0L);
                p.updatedAt = o.optLong("ts", 0L);
                return p.source.isEmpty() ? null : p;
            } catch (Exception e) {
                return null;
            }
        }
    }

    static int clampPct(int v) {
        return v < 0 ? 0 : (v > 100 ? 100 : v);
    }

    // ------------------------------------------------------------------ هوية المحتوى

    /**
     * يشتق مفتاح هوية المحتوى.
     *
     * @param title      عنوان القناة/الفيلم كما يصل للمشغّل
     * @param isLive     هل هو بث مباشر
     * @param resumeKey  مفتاح استئناف ثابت (للأفلام والحلقات) أو فارغ
     * @param refreshUrl رابط تحديث ثابت (القنوات ذات التوكن المتجدد) أو فارغ
     * @param url        الرابط الحالي للمحتوى
     */
    static String keyFor(String title, boolean isLive, String resumeKey, String refreshUrl, String url) {
        if (resumeKey != null && !resumeKey.trim().isEmpty()) {
            return "k:" + resumeKey.trim();
        }
        String t = normalizeTitle(title);
        if (isLive && !t.isEmpty() && !t.equals(normalizeTitle("يلا گول"))) {
            return "t:" + t;
        }
        if (refreshUrl != null && !refreshUrl.trim().isEmpty()) {
            return "r:" + normalizeUrl(refreshUrl);
        }
        return "u:" + normalizeUrl(url == null ? "" : url);
    }

    static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** يحذف الجزء #fragment ومُعاملات التوكن المتغيّرة ويرتّب ما تبقّى من المُعاملات. */
    static String normalizeUrl(String url) {
        String u = url == null ? "" : url.trim();
        int hash = u.indexOf('#');
        if (hash >= 0) {
            u = u.substring(0, hash);
        }
        int q = u.indexOf('?');
        if (q < 0) {
            return u;
        }
        String base = u.substring(0, q);
        String query = u.substring(q + 1);
        List<String> keep = new ArrayList<>();
        for (String part : query.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String name = (eq >= 0 ? part.substring(0, eq) : part).toLowerCase(Locale.ROOT);
            boolean volatileParam = false;
            for (String v : VOLATILE_PARAMS) {
                if (v.equals(name)) {
                    volatileParam = true;
                    break;
                }
            }
            if (!volatileParam) {
                keep.add(part);
            }
        }
        if (keep.isEmpty()) {
            return base;
        }
        Collections.sort(keep);
        StringBuilder sb = new StringBuilder(base).append('?');
        for (int i = 0; i < keep.size(); i++) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append(keep.get(i));
        }
        return sb.toString();
    }

    private static String prefKey(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("p");
            for (int i = 0; i < 16; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", d[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "p" + Integer.toHexString(key.hashCode());
        }
    }

    // ------------------------------------------------------------------ التخزين

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** يحمّل الإعداد المحفوظ لهذا المفتاح أو null. */
    static Profile load(Context c, String key) {
        try {
            String json = prefs(c).getString(prefKey(key), null);
            return json == null ? null : Profile.fromJson(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** يحفظ/يحدّث الإعداد (ويُقلّم الأقدم إن تجاوز العدد الحد). */
    static void save(Context c, String key, Profile p) {
        try {
            p.updatedAt = System.currentTimeMillis();
            SharedPreferences sp = prefs(c);
            SharedPreferences.Editor ed = sp.edit();
            ed.putString(prefKey(key), p.toJson().toString());
            Map<String, ?> all = sp.getAll();
            if (all.size() >= MAX_ENTRIES) {
                prune(all, ed, MAX_ENTRIES - 20);
            }
            ed.apply();
        } catch (Exception ignored) {
            // التخزين ميزة مساعدة؛ فشله لا يجوز أن يؤثر على التشغيل.
        }
    }

    static void delete(Context c, String key) {
        try {
            prefs(c).edit().remove(prefKey(key)).apply();
        } catch (Exception ignored) {
        }
    }

    private static void prune(Map<String, ?> all, SharedPreferences.Editor ed, int keepCount) {
        final List<Object[]> items = new ArrayList<>();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            long ts = 0L;
            try {
                ts = new JSONObject(String.valueOf(e.getValue())).optLong("ts", 0L);
            } catch (Exception ignored) {
            }
            items.add(new Object[]{e.getKey(), ts});
        }
        Collections.sort(items, (a, b) -> Long.compare((Long) b[1], (Long) a[1]));
        for (int i = keepCount; i < items.size(); i++) {
            ed.remove((String) items.get(i)[0]);
        }
    }
}
