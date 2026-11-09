package com.yallagoal.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * دليل محطات «الراديو»: يقرأ نفس ملف radio.json الذي يعرضه مجلد الراديو في التطبيق، ليختار المستخدم
 * منه محطة كصوت إضافي (تعليق/راديو) بنقرتين بدل لصق الرابط.
 *
 * <p>الصيغة مطابقة لـ channels.json: مصفوفة عناصر {n: الاسم, s: [{n, u, t}]}. نخزّن آخر نسخة ناجحة
 * محلياً فتبقى القائمة متاحة بلا إنترنت، ونُحدّثها في الخلفية عند كل فتح.
 */
final class RadioDirectory {

    static final String URL =
            "https://raw.githubusercontent.com/ossamasal2012/yalla-data/refs/heads/main/radio.json";
    private static final String PREFS = "yg_radio_dir";
    private static final String KEY_JSON = "json";

    /** محطة واحدة (سيرفر واحد). */
    static final class Station {
        final String name;
        final String url;

        Station(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    interface Callback {
        /** على خيط الواجهة. fromCache=true حين تعذّر الإنترنت وعُرضت النسخة المحفوظة. */
        void onLoaded(List<Station> stations, boolean fromCache);

        void onFailed(String message);
    }

    private static volatile List<Station> memory;

    private RadioDirectory() {
    }

    static void load(final Context context, final Callback cb) {
        final Context app = context.getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());
        if (memory != null && !memory.isEmpty()) {
            cb.onLoaded(memory, false);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Station> result = null;
                boolean cached = false;
                try {
                    final String json = download();
                    result = parse(json);
                    if (!result.isEmpty()) {
                        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_JSON, json).apply();
                    }
                } catch (Throwable ignored) {
                    // ننتقل للنسخة المحفوظة
                }
                if (result == null || result.isEmpty()) {
                    try {
                        final SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                        final String json = sp.getString(KEY_JSON, null);
                        if (json != null) {
                            result = parse(json);
                            cached = true;
                        }
                    } catch (Throwable ignored) {
                        // لا شيء
                    }
                }
                final List<Station> out = result;
                final boolean fromCache = cached;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (out == null || out.isEmpty()) {
                            cb.onFailed("تعذّر تحميل محطات الراديو. تأكد من الإنترنت ثم أعد المحاولة.");
                        } else {
                            memory = out;
                            cb.onLoaded(out, fromCache);
                        }
                    }
                });
            }
        }, "yg-radio-dir").start();
    }

    private static String download() throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "YallaGoal/1.0 (Android)");
        try {
            if (c.getResponseCode() != 200) {
                throw new java.io.IOException("HTTP " + c.getResponseCode());
            }
            final InputStream in = c.getInputStream();
            final ByteArrayOutputStream bo = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                bo.write(buf, 0, r);
                if (bo.size() > 4 * 1024 * 1024) {
                    throw new java.io.IOException("too large");
                }
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    static List<Station> parse(String json) throws Exception {
        final List<Station> list = new ArrayList<>();
        final JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            final String name = o.optString("n", "").trim();
            final JSONArray servers = o.optJSONArray("s");
            if (name.isEmpty() || servers == null) {
                continue;
            }
            final List<Station> mine = new ArrayList<>();
            for (int k = 0; k < servers.length(); k++) {
                final JSONObject sv = servers.optJSONObject(k);
                if (sv == null) {
                    continue;
                }
                final String u = sv.optString("u", "").trim();
                if (u.isEmpty() || "i".equals(sv.optString("t", "v"))) {
                    continue; // صفحات iframe ليست صوتاً قابلاً للتشغيل هنا
                }
                mine.add(new Station(name, u));
            }
            for (int k = 0; k < mine.size(); k++) {
                final Station st = mine.get(k);
                list.add(mine.size() > 1 ? new Station(st.name + " (" + (k + 1) + ")", st.url) : st);
            }
        }
        return list;
    }
}
