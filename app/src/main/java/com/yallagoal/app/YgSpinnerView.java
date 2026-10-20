package com.yallagoal.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** دائرة تحميل بهوية التطبيق: قوس متدرّج (أزرق ← برتقالي) يدور فوق حلقة باهتة. */
final class YgSpinnerView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private ValueAnimator anim;
    private float angle;

    YgSpinnerView(Context c) {
        super(c);
        track.setStyle(Paint.Style.STROKE);
        track.setColor(0x24FFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float stroke = Math.max(2f, Math.min(w, h) * 0.075f);
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);
        float inset = stroke / 2f;
        oval.set(inset, inset, w - inset, h - inset);
        arc.setShader(new SweepGradient(w / 2f, h / 2f,
                new int[]{YgUi.withAlpha(YgUi.COBALT_2, 0), YgUi.COBALT_2, YgUi.EMBER},
                new float[]{0f, 0.4f, 0.75f}));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawArc(oval, 0, 360, false, track);
        canvas.save();
        canvas.rotate(angle, getWidth() / 2f, getHeight() / 2f);
        canvas.drawArc(oval, 0, 270, false, arc);
        canvas.restore();
    }

    private void startSpin() {
        if (anim != null) return;
        anim = ValueAnimator.ofFloat(0f, 360f);
        anim.setDuration(950);
        anim.setInterpolator(new LinearInterpolator());
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                angle = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    private void stopSpin() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) startSpin();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSpin();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && isAttachedToWindow()) startSpin(); else stopSpin();
    }
}
