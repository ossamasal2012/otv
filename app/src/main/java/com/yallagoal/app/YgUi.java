package com.yallagoal.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;

/**
 * نظام التصميم الأصلي ليلا گول ("ليلة المباراة") — نفس ألوان وطباعة واجهة الويب تماماً،
 * ليبدو التطبيق كله (الصفحة + المشغّل + الحوارات) كتلة بصرية واحدة.
 *
 * كل شيء هنا برمجي بلا أي ملف layout/drawable من res/، والخطوط تُحمَّل من assets/fonts
 * (Readex Pro للنصوص، Lalezar للعناوين والأرقام) مع رجوع آمن للخط الافتراضي إن فُقد الملف.
 */
final class YgUi {
    private YgUi() {}

    // ---------- الألوان (مطابقة لمتغيرات CSS في index.html) ----------
    static final int INK_0 = 0xFF050A1B;
    static final int INK_1 = 0xFF0A1230;
    static final int INK_2 = 0xFF0F1A40;
    static final int INK_3 = 0xFF162554;

    static final int CHALK = 0xFFF3F6FF;
    static final int MIST = 0xFFB0BEE8;
    static final int FOG = 0xFF7686B8;

    static final int COBALT = 0xFF2F6BFF;
    static final int COBALT_2 = 0xFF5B8DFF;
    static final int COBALT_3 = 0xFF8DB1FF;
    static final int COBALT_LO = 0xFF1B49C9;
    static final int EMBER = 0xFFFF8A1F;
    static final int EMBER_2 = 0xFFFFB056;

    static final int LIVE = 0xFFFF3B55;
    static final int OK = 0xFF2FD89B;
    static final int BAD = 0xFFFF5A6E;
    static final int GOLD = 0xFFFFC94D;

    static final int GLASS_FILL = 0x26FFFFFF;
    static final int GLASS_STROKE = 0x3DFFFFFF;
    static final int LINE = 0x2E96B2FF;

    // ---------- أبعاد ----------
    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // ---------- الخطوط ----------
    private static Typeface sText;
    private static Typeface sBold;
    private static Typeface sDisplay;
    private static boolean sLoaded;

    private static synchronized void ensureFonts(Context c) {
        if (sLoaded) return;
        sLoaded = true;
        AssetManager am = c.getApplicationContext().getAssets();
        sText = load(am, "fonts/ReadexPro-Medium.ttf", Typeface.DEFAULT);
        sBold = load(am, "fonts/ReadexPro-Bold.ttf", Typeface.DEFAULT_BOLD);
        sDisplay = load(am, "fonts/Lalezar-Regular.ttf", Typeface.DEFAULT_BOLD);
    }

    private static Typeface load(AssetManager am, String path, Typeface fallback) {
        try {
            return Typeface.createFromAsset(am, path);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** النص العادي (Readex Pro Medium). */
    static Typeface text(Context c) {
        ensureFonts(c);
        return sText;
    }

    /** النص الغامق (Readex Pro Bold). */
    static Typeface bold(Context c) {
        ensureFonts(c);
        return sBold;
    }

    /** خط العناوين والأرقام الرياضي (Lalezar). */
    static Typeface display(Context c) {
        ensureFonts(c);
        return sDisplay;
    }

    // ---------- الأشكال ----------
    static GradientDrawable rect(int color, float radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(color);
        gd.setCornerRadius(radiusPx);
        return gd;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        return gd;
    }

    /** زجاج: تعبئة بيضاء شفافة + حدّ رفيع — لأزرار المشغّل فوق الفيديو. */
    static GradientDrawable glass(Context c, float radiusPx, boolean circle) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(circle ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        gd.setColor(GLASS_FILL);
        gd.setStroke(Math.max(1, dp(c, 1)), GLASS_STROKE);
        if (!circle) gd.setCornerRadius(radiusPx);
        return gd;
    }

    /** لوحة داكنة (للبطاقات والأوراق): تدرّج رأسي خفيف + حدّ أزرق باهت. */
    static GradientDrawable panel(Context c, float radiusPx) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFA0E1634, 0xFA070D24});
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(radiusPx);
        gd.setStroke(Math.max(1, dp(c, 1)), LINE);
        return gd;
    }

    /** تدرّج الكوبالت المميّز للأزرار الرئيسية. */
    static GradientDrawable primary(float radiusPx, boolean circle) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{COBALT_2, COBALT, COBALT_LO});
        gd.setShape(circle ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        if (!circle) gd.setCornerRadius(radiusPx);
        return gd;
    }

    // ---------- تأثير الضغط (يتقلّص قليلاً ثم يعود) دون أن يمنع النقر ----------
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    static void pressable(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
