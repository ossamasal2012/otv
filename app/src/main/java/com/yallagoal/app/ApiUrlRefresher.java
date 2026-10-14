package com.yallagoal.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * يجدّد رابط البث لقنوات «الرابط المتجدد» (واجهة API ترجع JSON فيه stream_url بتوكن مؤقت) —
 * نفس عقد دالة resolveStreamUrl في index.html بالضبط:
 *
 *   - HTTP غير ناجح                       ⇒ فشل.
 *   - {"ok": false, ...}                  ⇒ فشل (انتهت صلاحية القناة من المصدر).
 *   - غياب stream_url أو ليس http(s)       ⇒ فشل.
 *   - غير ذلك                              ⇒ يُرجع stream_url.
 *
 * لماذا يوجد؟ الواجهة (JS) كانت تجلب الرابط الحقيقي مرة واحدة عند الضغط على القناة ثم تسلّمه
 * للمشغّل الأصلي؛ وبعد انتهاء صلاحية التوكن (يعرف JS مدة صلاحيته expires_in_seconds لكنه كان
 * يهملها) يفشل تحميل القائمة أو المقطع التالي والمشغّل لا يملك طريقة لجلب رابط جديد، فيتوقف البث.
 * بهذا الكلاس يستطيع المحرّك (عند خطأ يشي بانتهاء الصلاحية فقط — انظر PlaybackRecoveryPolicy)
 * أن يجلب رابطاً جديداً من المصدر نفسه ثم يعيد التشغيل بدل الاستسلام.
 *
 * يُستدعى دائماً من خيط خلفي (يحجب على الشبكة). لا يُسجِّل أي رابط أو توكن في السجلات.
 */
final class ApiUrlRefresher implements PlaybackEngine.UrlRefresher {

    // عميل مشترك خفيف: مهلات قصيرة (نفس 8 ثوانٍ في JS) وبدون callTimeout طويل.
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build();

    /** حدّ أقصى لحجم الاستجابة المقبولة (JSON صغير؛ أي شيء أكبر ليس واجهتنا). */
    private static final long MAX_BODY_BYTES = 256L * 1024L;

    private final String apiUrl;

    ApiUrlRefresher(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    @Override
    public String fetchFreshUrl() throws IOException {
        if (apiUrl == null || !(apiUrl.startsWith("https://") || apiUrl.startsWith("http://"))) {
            throw new IOException("bad refresh url");
        }

        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("refresh api http " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("refresh api empty body");

            long declared = body.contentLength();
            if (declared > MAX_BODY_BYTES) throw new IOException("refresh api body too large");

            String text = body.string();
            if (text.length() > MAX_BODY_BYTES) throw new IOException("refresh api body too large");

            return parseStreamUrl(text);
        }
    }

    /** منفصلة وبـ package-private ليسهل اختبارها (المنطق مطابق لـ resolveStreamUrl في JS). */
    static String parseStreamUrl(String json) throws IOException {
        try {
            JSONObject data = new JSONObject(json);
            if (data.has("ok") && !data.isNull("ok") && !data.optBoolean("ok", true)) {
                throw new IOException("refresh api reports ok=false");
            }
            String streamUrl = data.optString("stream_url", "");
            if (streamUrl.isEmpty() || "null".equals(streamUrl)) {
                throw new IOException("refresh api has no stream_url");
            }
            if (!(streamUrl.startsWith("https://") || streamUrl.startsWith("http://"))) {
                throw new IOException("refresh api stream_url is not http(s)");
            }
            return streamUrl;
        } catch (JSONException e) {
            throw new IOException("refresh api returned invalid json");
        }
    }
}
