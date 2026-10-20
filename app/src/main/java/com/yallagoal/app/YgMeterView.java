package com.yallagoal.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** عمود قياس رأسي (سطوع/صوت) — مسار شفاف + تعبئة متدرّجة من الأسفل بقدر القيمة (0..1). */
final class YgMeterView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float value = 0.5f;

    YgMeterView(Context c) {
        super(c);
        trackPaint.setColor(0x38FFFFFF);
    }

    void setValue(float v) {
        value = v < 0f ? 0f : (v > 1f ? 1f : v);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fillPaint.setShader(new LinearGradient(0f, h, 0f, 0f,
                YgUi.COBALT, YgUi.COBALT_3, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = w / 2f;
        rect.set(0f, 0f, w, h);
        canvas.drawRoundRect(rect, r, r, trackPaint);
        float fh = h * value;
        if (fh > 0f) {
            rect.set(0f, h - fh, w, h);
            canvas.drawRoundRect(rect, r, r, fillPaint);
        }
    }
}
