package com.yallagoal.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * شريط تقدّم مخصّص بهوية ليلا گول: مسار رفيع، شريط تخزين مؤقت (buffer) أفتح، وتقدّم بتدرّج
 * أزرق ينتهي برتقالياً عند المقبض الأبيض المتوهّج. يتجه دائماً من اليسار لليمين (مثل كل مشغلات الفيديو).
 *
 * الوضعان الخاصان:
 *  - setSeekable(false): لا يستجيب للمس ولا يُظهر مقبضاً (بث مباشر بلا نافذة قابلة للسَّعي).
 *  - setLiveFull(true): يرسم المسار كاملاً بتدرّج هادئ للدلالة على «بث حي» دون تقدّم مُلفَّق.
 */
final class YgSeekView extends View {

    interface Listener {
        void onSeekStart();

        void onSeekChange(float fraction);

        void onSeekEnd(float fraction);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bufferPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix shaderMatrix = new Matrix();
    private final LinearGradient unitGradient;

    private final float trackH;
    private final float thumbR;
    private final float padX;

    private float progress = 0f;
    private float buffered = 0f;
    private boolean dragging = false;
    private boolean seekable = true;
    private boolean liveFull = false;
    private Listener listener;

    YgSeekView(Context c) {
        super(c);
        trackH = YgUi.dp(c, 4.5f);
        thumbR = YgUi.dp(c, 8f);
        padX = YgUi.dp(c, 10f);

        trackPaint.setColor(0x38FFFFFF);
        bufferPaint.setColor(0x59FFFFFF);
        glowPaint.setColor(YgUi.withAlpha(YgUi.COBALT, 0x66));
        thumbPaint.setColor(0xFFFFFFFF);

        unitGradient = new LinearGradient(0f, 0f, 1f, 0f,
                new int[]{YgUi.COBALT_2, YgUi.COBALT, YgUi.EMBER},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        progressPaint.setShader(unitGradient);
    }

    void setListener(Listener l) {
        this.listener = l;
    }

    void setProgress(float fraction) {
        if (dragging) return;
        this.progress = clamp(fraction);
        invalidate();
    }

    void setBuffered(float fraction) {
        this.buffered = clamp(fraction);
        invalidate();
    }

    void setSeekable(boolean seekable) {
        this.seekable = seekable;
        invalidate();
    }

    void setLiveFull(boolean liveFull) {
        this.liveFull = liveFull;
        invalidate();
    }

    boolean isDragging() {
        return dragging;
    }

    private static float clamp(float f) {
        return f < 0f ? 0f : (f > 1f ? 1f : f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) w = YgUi.dp(getContext(), 200);
        setMeasuredDimension(w, YgUi.dp(getContext(), 38));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float cy = getHeight() / 2f;
        float left = padX;
        float right = w - padX;
        float len = right - left;
        if (len <= 0) return;
        float th = dragging ? trackH * 1.45f : trackH;
        float r = th / 2f;

        // المسار الخلفي
        rect.set(left, cy - r, right, cy + r);
        canvas.drawRoundRect(rect, r, r, trackPaint);

        if (liveFull) {
            shaderMatrix.setScale(len, 1f);
            shaderMatrix.postTranslate(left, 0f);
            unitGradient.setLocalMatrix(shaderMatrix);
            progressPaint.setAlpha(110);
            canvas.drawRoundRect(rect, r, r, progressPaint);
            progressPaint.setAlpha(255);
            return;
        }

        // المخزَّن مؤقتاً
        if (buffered > 0f) {
            rect.set(left, cy - r, left + len * buffered, cy + r);
            canvas.drawRoundRect(rect, r, r, bufferPaint);
        }

        // التقدّم
        float px = left + len * progress;
        if (progress > 0f) {
            float pw = Math.max(px - left, 1f);
            shaderMatrix.setScale(pw, 1f);
            shaderMatrix.postTranslate(left, 0f);
            unitGradient.setLocalMatrix(shaderMatrix);
            rect.set(left, cy - r, px, cy + r);
            canvas.drawRoundRect(rect, r, r, progressPaint);
        }

        // المقبض
        if (seekable) {
            float tr = dragging ? thumbR * 1.35f : thumbR;
            canvas.drawCircle(px, cy, tr + YgUi.dp(getContext(), dragging ? 7f : 5f), glowPaint);
            canvas.drawCircle(px, cy, tr, thumbPaint);
        }
    }

    private float fractionFor(float x) {
        float left = padX;
        float len = getWidth() - padX * 2f;
        if (len <= 0f) return 0f;
        return clamp((x - left) / len);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!seekable || liveFull) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                progress = fractionFor(e.getX());
                if (listener != null) {
                    listener.onSeekStart();
                    listener.onSeekChange(progress);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    progress = fractionFor(e.getX());
                    if (listener != null) listener.onSeekChange(progress);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) progress = fractionFor(e.getX());
                    if (listener != null) listener.onSeekEnd(progress);
                    invalidate();
                }
                return true;
            default:
                return true;
        }
    }
}
