package com.yallagoal.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * حوارات التطبيق بهوية "ليلة المباراة": بطاقة داكنة بحدّ أزرق باهت، شارة أيقونة متوهّجة،
 * عنوان بخط Lalezar ونص Readex Pro، وأزرار بتدرّج الكوبالت. تحلّ محل AlertDialog الرمادي الافتراضي.
 *
 * مبنية برمجياً بالكامل (لا res/layout) على نفس مبدأ بقية واجهات التطبيق الأصلية.
 */
final class YgDialog {
    private YgDialog() {}

    /** نبرة الشارة: تحدد لون الأيقونة والتوهّج. */
    static final int TONE_BRAND = 0;
    static final int TONE_BAD = 1;

    /** بطاقة الحوار الفارغة (تُملأ بالمحتوى) داخل نافذة شفافة تحجب الخلفية بتعتيم عميق. */
    static Dialog create(Activity activity, LinearLayout card, boolean cancelable) {
        Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout wrap = new FrameLayout(activity);
        int side = YgUi.dp(activity, 20);
        wrap.setPadding(side, side, side, side);
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int cardW = Math.min(YgUi.dp(activity, 360), screenW - side * 2);
        wrap.addView(card, new FrameLayout.LayoutParams(cardW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        d.setContentView(wrap);

        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.78f);
        }
        d.setCancelable(cancelable);
        d.setCanceledOnTouchOutside(false);
        return d;
    }

    /** بطاقة عمودية بهيئة الحوار. */
    static LinearLayout card(Activity a) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(YgUi.dp(a, 22), YgUi.dp(a, 26), YgUi.dp(a, 22), YgUi.dp(a, 20));
        card.setBackground(YgUi.panel(a, YgUi.dp(a, 28)));
        return card;
    }

    /** شارة أيقونة مربّعة مستديرة متوهّجة. */
    static View badge(Activity a, String icon, int tone) {
        boolean bad = tone == TONE_BAD;
        FrameLayout box = new FrameLayout(a);
        GradientDrawable bg;
        if (bad) {
            bg = YgUi.rect(0x24FF5A6E, YgUi.dp(a, 22));
            bg.setStroke(Math.max(1, YgUi.dp(a, 1)), 0x59FF5A6E);
        } else {
            bg = YgUi.primary(YgUi.dp(a, 22), false);
        }
        box.setBackground(bg);
        box.addView(new YgIconView(a, icon, bad ? YgUi.BAD : Color.WHITE),
                new FrameLayout.LayoutParams(YgUi.dp(a, 32), YgUi.dp(a, 32), Gravity.CENTER));
        return box;
    }

    static TextView title(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(24);
        t.setTypeface(YgUi.display(a));
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView message(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(YgUi.MIST);
        t.setTextSize(13);
        t.setTypeface(YgUi.text(a));
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0f, 1.45f);
        return t;
    }

    /** شارة صغيرة برتقالية (مثل رقم الإصدار). */
    static TextView chip(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(0xFF2A1200);
        t.setTextSize(12);
        t.setTypeface(YgUi.display(a));
        t.setGravity(Gravity.CENTER);
        t.setPadding(YgUi.dp(a, 12), YgUi.dp(a, 2), YgUi.dp(a, 12), YgUi.dp(a, 2));
        t.setBackground(YgUi.rect(YgUi.EMBER_2, YgUi.dp(a, 12)));
        t.setIncludeFontPadding(false);
        return t;
    }

    /** زر رئيسي بتدرّج الكوبالت مع أيقونة اختيارية. */
    static View primaryButton(Activity a, String text, String icon, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(YgUi.primary(YgUi.dp(a, 16), false));
        b.setPadding(YgUi.dp(a, 20), 0, YgUi.dp(a, 20), 0);
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14.5f);
        t.setTypeface(YgUi.bold(a));
        t.setIncludeFontPadding(false);
        b.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (icon != null) {
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(YgUi.dp(a, 19), YgUi.dp(a, 19));
            ip.leftMargin = YgUi.dp(a, 8);
            b.addView(new YgIconView(a, icon, Color.WHITE), ip);
        }
        b.setOnClickListener(l);
        YgUi.pressable(b);
        return b;
    }

    /** زر ثانوي زجاجي. */
    static View ghostButton(Activity a, String text, View.OnClickListener l) {
        TextView b = new TextView(a);
        b.setText(text);
        b.setTextColor(YgUi.CHALK);
        b.setTextSize(13.5f);
        b.setTypeface(YgUi.bold(a));
        b.setGravity(Gravity.CENTER);
        b.setBackground(YgUi.glass(a, YgUi.dp(a, 16), false));
        b.setIncludeFontPadding(false);
        b.setOnClickListener(l);
        YgUi.pressable(b);
        return b;
    }

    /**
     * حوار تنبيه جاهز: شارة + عنوان + (شارة إصدار اختيارية) + رسالة قابلة للتمرير + زر رئيسي + زر ثانوي اختياري.
     * الضغط على أي زر يغلق الحوار أولاً ثم ينفّذ الإجراء (نفس سلوك AlertDialog القديم).
     */
    static Dialog alert(final Activity a, String icon, int tone, String titleText, String chipText,
                        String messageText, String positiveText, String positiveIcon, final Runnable onPositive,
                        String negativeText, boolean cancelable) {
        LinearLayout card = card(a);

        card.addView(badge(a, icon, tone), new LinearLayout.LayoutParams(YgUi.dp(a, 66), YgUi.dp(a, 66)));

        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = YgUi.dp(a, 16);
        card.addView(title(a, titleText), tp);

        if (chipText != null && !chipText.isEmpty()) {
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cp.topMargin = YgUi.dp(a, 10);
            card.addView(chip(a, chipText), cp);
        }

        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.addView(message(a, messageText), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mp.topMargin = YgUi.dp(a, 12);
        mp.bottomMargin = YgUi.dp(a, 20);
        // ارتفاع أقصى: نص التغييرات الطويل يتمرّر بدل أن يتجاوز الشاشة
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.34f);
        card.addView(new MaxHeightFrame(a, sv, maxH), mp);

        final Dialog[] holder = new Dialog[1];

        View pos = primaryButton(a, positiveText, positiveIcon, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) holder[0].dismiss();
                if (onPositive != null) onPositive.run();
            }
        });
        card.addView(pos, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, YgUi.dp(a, 52)));

        if (negativeText != null && !negativeText.isEmpty()) {
            View neg = ghostButton(a, negativeText, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (holder[0] != null) holder[0].dismiss();
                }
            });
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, YgUi.dp(a, 48));
            np.topMargin = YgUi.dp(a, 10);
            card.addView(neg, np);
        }

        Dialog d = create(a, card, cancelable);
        holder[0] = d;
        return d;
    }

    /** حاوية تحدّ أقصى لارتفاع ابنها (ScrollView) دون إجباره على ملء المساحة. */
    private static final class MaxHeightFrame extends FrameLayout {
        private final int maxHeightPx;

        MaxHeightFrame(Activity a, View child, int maxHeightPx) {
            super(a);
            this.maxHeightPx = maxHeightPx;
            addView(child, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int hMode = MeasureSpec.getMode(heightMeasureSpec);
            int hSize = MeasureSpec.getSize(heightMeasureSpec);
            int capped = hMode == MeasureSpec.UNSPECIFIED ? maxHeightPx : Math.min(hSize, maxHeightPx);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST));
        }
    }

}
