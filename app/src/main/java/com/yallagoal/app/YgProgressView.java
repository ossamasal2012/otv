package com.yallagoal.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * شريط تقدّم التحديث بهوية التطبيق: مسار داكن + تعبئة بتدرّج أزرق ← برتقالي.
 * يدعم وضعين: محدَّد (Determinate) من 0..max، وغير محدَّد (Indeterminate) بشريط لامع يتحرّك —
 * ويُستعمل الثاني فقط عندما يكون الحجم الكلي مجهولاً (لا نسبة مُختلَقة أبداً).
 */
final class YgProgressView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private final LinearGradient unit;
    private int max = 1000;
    private int progress = 0;
    private boolean indeterminate = false;
    private float sweep = 0f;
    private ValueAnimator anim;

    YgProgressView(Context c) {
        super(c);
        trackPaint.setColor(0x2996B2FF);
        unit = new LinearGradient(0f, 0f, 1f, 0f,
                new int[]{YgUi.COBALT_2, YgUi.COBALT, YgUi.EMBER},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
        fillPaint.setShader(unit);
    }

    void setMax(int max) {
        this.max = Math.max(1, max);
        invalidate();
    }

    void setProgress(int progress) {
        this.progress = Math.max(0, Math.min(max, progress));
        invalidate();
    }

    void setIndeterminate(boolean on) {
        if (on == indeterminate) return;
        indeterminate = on;
        if (on) startSweep(); else stopSweep();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) w = YgUi.dp(getContext(), 200);
        setMeasuredDimension(w, YgUi.dp(getContext(), 10));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = h / 2f;
        rect.set(0f, 0f, w, h);
        canvas.drawRoundRect(rect, r, r, trackPaint);

        float left;
        float right;
        if (indeterminate) {
            float seg = w * 0.38f;
            left = -seg + (w + seg) * sweep;
            right = left + seg;
            left = Math.max(0f, left);
            right = Math.min(w, right);
            if (right <= left) return;
        } else {
            left = 0f;
            right = w * (progress / (float) max);
            if (right <= 0f) return;
            right = Math.max(right, h); // أقل عرض = كبسولة كاملة
        }
        matrix.setScale(Math.max(1f, right - left), 1f);
        matrix.postTranslate(left, 0f);
        unit.setLocalMatrix(matrix);
        rect.set(left, 0f, right, h);
        canvas.drawRoundRect(rect, r, r, fillPaint);
    }

    private void startSweep() {
        if (anim != null) return;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1250);
        anim.setInterpolator(new LinearInterpolator());
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                sweep = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    private void stopSweep() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (indeterminate) startSweep();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSweep();
        super.onDetachedFromWindow();
    }
}
