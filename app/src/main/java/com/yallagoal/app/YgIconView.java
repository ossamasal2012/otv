package com.yallagoal.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * أيقونة متجهية بلا أي ملف موارد: تُرسَم مباشرة بـCanvas من مسارات {@link YgIcons}
 * (لوحة 24×24، خط 2 وحدة بأطراف مستديرة — نفس أيقونات واجهة الويب).
 */
final class YgIconView extends View {
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Path path = new Path();
    private String name = "";
    private boolean filled = false;
    private float strokeUnits = 2f;

    YgIconView(Context c) {
        super(c);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        setColor(0xFFFFFFFF);
    }

    YgIconView(Context c, String icon, int color) {
        this(c);
        setColor(color);
        setIcon(icon);
    }

    void setIcon(String icon) {
        if (icon == null || icon.equals(name)) return;
        name = icon;
        path = parse(YgIcons.data(icon));
        invalidate();
    }

    void setColor(int color) {
        strokePaint.setColor(color);
        fillPaint.setColor(color);
        invalidate();
    }

    /** يملأ الشكل (للتشغيل/الإيقاف المؤقت) إضافةً إلى الحدّ المستدير. */
    void setFilled(boolean filled) {
        this.filled = filled;
        invalidate();
    }

    void setStrokeUnits(float units) {
        this.strokeUnits = units;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        int h = getHeight() - getPaddingTop() - getPaddingBottom();
        float side = Math.min(w, h);
        if (side <= 0) return;
        float s = side / 24f;
        canvas.save();
        canvas.translate(getPaddingLeft() + (w - side) / 2f, getPaddingTop() + (h - side) / 2f);
        canvas.scale(s, s);
        strokePaint.setStrokeWidth(strokeUnits);
        if (filled) canvas.drawPath(path, fillPaint);
        canvas.drawPath(path, strokePaint);
        canvas.restore();
    }

    /** يحوّل نص المسار (M/L/C/Z مطلقة مفصولة بمسافات) إلى Path. */
    static Path parse(String d) {
        Path p = new Path();
        if (d == null || d.isEmpty()) return p;
        String[] t = d.split(" ");
        int i = 0;
        try {
            while (i < t.length) {
                String op = t[i++];
                if (op.isEmpty()) continue;
                switch (op.charAt(0)) {
                    case 'M':
                        p.moveTo(f(t[i]), f(t[i + 1]));
                        i += 2;
                        break;
                    case 'L':
                        p.lineTo(f(t[i]), f(t[i + 1]));
                        i += 2;
                        break;
                    case 'C':
                        p.cubicTo(f(t[i]), f(t[i + 1]), f(t[i + 2]), f(t[i + 3]), f(t[i + 4]), f(t[i + 5]));
                        i += 6;
                        break;
                    case 'Z':
                        p.close();
                        break;
                    default:
                        break;
                }
            }
        } catch (RuntimeException ignored) {
            // مسار تالف: نعرض ما رُسم حتى الآن بدل إسقاط الواجهة
        }
        return p;
    }

    private static float f(String s) {
        return Float.parseFloat(s);
    }
}
