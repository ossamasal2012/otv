# =============================================================================================
#  قواعد R8 / ProGuard للإصدار الرسمي (release) — تُفعَّل من app/build.gradle
# =============================================================================================
#  الغرض الأمني: R8 يُعيد تسمية كل كلاسات ودوال ومتغيّرات التطبيق إلى رموز بلا معنى (a, b, c…)
#  ويحذف كل كود غير مستخدم؛ فإذا فتح أحدهم الـ APK بأدوات مثل jadx/apktool لن يجد أسماء
#  UserStatsManager أو WebAppInterface أو أي منطق مقروء — بل شيفرة مبعثرة يصعب فهمها.
#
#  ما لا يجوز أن يُعاد تسميته (لأنه يُستدعى "بالاسم" من خارج كود Java) مذكور بالأسفل فقط.
#  مكتبات Google/AndroidX/OkHttp/Media3/Firebase تأتي بقواعد خاصة بها تُطبَّق تلقائياً.
# =============================================================================================

# ---- 1) جسر JavaScript ⇄ Java -----------------------------------------------------------------
# صفحة الواجهة تستدعي window.AndroidPlayer.<method>() بالاسم عبر الـ reflection؛ لو أُعيدت تسمية هذه
# الدوال أو حُذفت (لأنها لا تُستدعى من Java) لتوقّف التطبيق كلياً.
-keepclassmembers class com.yallagoal.app.WebAppInterface {
    @android.webkit.JavascriptInterface <methods>;
    public *;
}
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ---- 2) مزوّد خيارات Cast ---------------------------------------------------------------------
# يُشار إليه بنص داخل AndroidManifest.xml (OPTIONS_PROVIDER_CLASS_NAME) ويُنشأ بالـ reflection.
-keep public class com.yallagoal.app.CastOptionsProvider { *; }

# ---- 3) مزيد من التشويش -----------------------------------------------------------------------
# نقل كل الكلاسات المُشوَّشة إلى حزمة جذرية واحدة (يخفي هيكل الحزم com.yallagoal.app.*).
# ملاحظة: لا نُبقي أسماء الملفات ولا أرقام الأسطر (SourceFile/LineNumberTable) عمداً — تكشف بنية الكود.
-repackageclasses ''
-allowaccessmodification

# ---- 4) حذف رسائل السجل من الإصدار الرسمي -----------------------------------------------------
# رسائل Log.* تحوي نصوصاً داخلية تشرح عمل النظام؛ تُحذف مع نصوصها من الـ APK.
# (تعمل فقط مع proguard-android-optimize.txt المستخدم في build.gradle)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# ---- 5) تحذيرات معروفة عن مكتبات اختيارية غير مستخدمة (آمن تجاهلها) ------------------------------
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn okhttp3.internal.platform.**
