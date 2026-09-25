package com.yallagoal.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ============================================================================================
 *  خزنة الأصول (AssetVault) — تجعل ملف واجهة التطبيق (index.html) غير مقروء داخل الـ APK.
 * ============================================================================================
 *
 *  المشكلة: الـ APK هو في الحقيقة ملف ZIP؛ أي شخص يغيّر امتداده إلى .zip ويفتحه سيجد
 *  assets/index.html كاملاً بنص واضح (كل منطق التطبيق ومصادر بياناته).
 *
 *  الحل: أثناء البناء الرسمي (GitHub Actions) يُصغَّر index.html ثم يُشفَّر بـ AES-256-GCM إلى
 *  assets/yg.dat ويُحذف الأصلي من الحزمة. داخل الـ APK لا يبقى إلا بايتات عشوائية الشكل.
 *  عند التشغيل يُفكّ التشفير في الذاكرة فقط (لا يُكتب أي ملف مقروء على القرص) ثم تُحمَّل
 *  الصفحة عبر loadDataWithBaseURL.
 *
 *  مفتاح التشفير لا يُخزَّن جاهزاً في أي مكان؛ يُشتقّ وقت التشغيل من:
 *    1) شهادة توقيع التطبيق نفسه (SHA-256 لشهادة الـ keystore الرسمي)، و
 *    2) ثابت سري (pepper) مُجزَّأ ومُموَّه بين ثلاث مصفوفات أدناه.
 *  فإذا أعاد أي شخص توقيع الـ APK بمفتاحه الخاص (نسخة مقلَّدة/معدَّلة) فسيتغيّر المفتاح
 *  المشتق ويفشل فك التشفير فلا تعمل الواجهة إطلاقاً.
 *
 *  ⚠️ لا تُعدِّل المصفوفات K1/K2/K3 يدوياً: سكربت البناء (scripts/protect-assets.js) يقرؤها
 *  من هذا الملف نفسه ليشتق نفس المفتاح — أي تعديل غير متزامن يعني فشل فك التشفير.
 *
 *  ملاحظة صريحة: لا توجد حماية مطلقة لأي تطبيق يعمل على جهاز المستخدم؛ هذه الطبقة تغلق
 *  "فك الضغط وقراءة الملفات" وتُفشل النسخ المعاد توقيعها، وترفع كلفة أي هندسة عكسية
 *  (ينبغي أن تُقرن بـ R8/ProGuard المفعَّل بالإصدار الرسمي). راجع README_SECURITY.md.
 */
final class AssetVault {

    /** اسم الملف المشفّر داخل assets/ — يُنشئه سكربت البناء فقط في البناء الرسمي. */
    static final String ASSET_NAME = "yg.dat";

    private static final byte[] MAGIC = {'Y', 'G', '1', 0};
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] KDF_LABEL = "yg-asset-v1".getBytes(StandardCharsets.UTF_8);

    // YG-PEPPER-BEGIN
    private static final int[] K1 = {
            0xd7, 0x2d, 0x33, 0xbc, 0x19, 0xa3, 0xa0, 0x8a,
            0x3a, 0x83, 0xad, 0xf8, 0xe7, 0x2b, 0x4c, 0x42,
            0xc9, 0x16, 0x9e, 0x93, 0x0f, 0x9c, 0x61, 0x24,
            0x61, 0xa6, 0xfe, 0xa5, 0x3b, 0xdb, 0x4c, 0xdd
    };

    private static final int[] K2 = {
            0x7c, 0x9d, 0xfd, 0xa4, 0x2d, 0xa3, 0xdb, 0x0d,
            0x63, 0x76, 0xeb, 0xe6, 0x46, 0xba, 0x4c, 0xf2,
            0x34, 0x8f, 0x1b, 0xd9, 0xc3, 0x91, 0x5e, 0x8f,
            0x74, 0x2c, 0xfb, 0x6f, 0xdc, 0xd5, 0x04, 0xfb
    };

    private static final int[] K3 = {
            0xc2, 0xad, 0xb7, 0x88, 0x95, 0x34, 0x99, 0x92,
            0x21, 0x5e, 0x11, 0xdf, 0x87, 0xaf, 0x80, 0xea,
            0xf3, 0xfb, 0x10, 0xe5, 0x96, 0x87, 0x80, 0xa3,
            0x49, 0xa2, 0xd5, 0x04, 0x88, 0xdf, 0xb1, 0xf0
    };
    // YG-PEPPER-END

    private AssetVault() {}

    /** هل توجد نسخة مشفّرة من الواجهة داخل هذه الحزمة؟ (false في بناء التطوير المحلي). */
    static boolean exists(Context ctx) {
        try (InputStream in = ctx.getAssets().open(ASSET_NAME)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * يفكّ تشفير الواجهة ويعيدها نصاً (في الذاكرة فقط).
     * يرمي استثناءً إن فشل الفك: توقيع مختلف (نسخة مُعاد توقيعها)، ملف تالف، أو تلاعب.
     */
    static String decryptHtml(Context ctx) throws GeneralSecurityException, IOException {
        byte[] blob;
        try (InputStream in = ctx.getAssets().open(ASSET_NAME)) {
            blob = readAll(in);
        }
        byte[] plain = decryptBlob(blob, signerDigest(ctx));
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** قلب التشفير (بلا أي اعتماد على أندرويد) — مُفصول ليسهل اختباره. */
    static byte[] decryptBlob(byte[] blob, byte[] certDigest) throws GeneralSecurityException {
        final int header = MAGIC.length + IV_LEN;
        if (blob == null || blob.length < header + (TAG_BITS / 8) + 1) {
            throw new GeneralSecurityException("bad-blob");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (blob[i] != MAGIC[i]) throw new GeneralSecurityException("bad-magic");
        }
        byte[] key = deriveKey(certDigest);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, blob, MAGIC.length, IV_LEN));
        cipher.updateAAD(MAGIC);
        return cipher.doFinal(blob, header, blob.length - header);
    }

    /** المفتاح = HMAC-SHA256( pepper , SHA256(شهادة التوقيع) || "yg-asset-v1" ). */
    private static byte[] deriveKey(byte[] certDigest) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(pepper(), "HmacSHA256"));
        mac.update(certDigest);
        return mac.doFinal(KDF_LABEL);
    }

    /** يُعيد تركيب الثابت السري: pepper[i] = K1[i] ^ K2[(i*7+3)&31] ^ K3[i]. */
    private static byte[] pepper() {
        byte[] p = new byte[32];
        for (int i = 0; i < 32; i++) {
            p[i] = (byte) (K1[i] ^ K2[(i * 7 + 3) & 31] ^ K3[i]);
        }
        return p;
    }

    /** SHA-256 لشهادة التوقيع الفعلية المثبَّت بها هذا التطبيق على الجهاز. */
    @SuppressWarnings("deprecation")
    static byte[] signerDigest(Context ctx) throws GeneralSecurityException {
        PackageManager pm = ctx.getPackageManager();
        String pkg = ctx.getPackageName();
        byte[] cert = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo si = pi.signingInfo;
                if (si != null) {
                    Signature[] signers = si.getApkContentsSigners();
                    if (signers != null && signers.length > 0) cert = signers[0].toByteArray();
                }
            }
            if (cert == null) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                if (pi.signatures != null && pi.signatures.length > 0) {
                    cert = pi.signatures[0].toByteArray();
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new GeneralSecurityException("no-package");
        }
        if (cert == null) throw new GeneralSecurityException("no-signer");
        return MessageDigest.getInstance("SHA-256").digest(cert);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
