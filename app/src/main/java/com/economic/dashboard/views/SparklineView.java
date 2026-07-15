package com.economic.dashboard.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/**
 * Lightweight inline sparkline for AI Analyst chat bubbles — renders a
 * [CHART:SERIES:WINDOW] tag as a small trend line without the weight of a
 * full MPAndroidChart instance per recycled row.
 */
public class SparklineView extends View {

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private float[] values;
    private int lineColor = 0xFFB8860B; // gold ink default; overridable

    public SparklineView(Context c) { super(c); init(); }
    public SparklineView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(getResources().getDisplayMetrics().density * 2f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setLineColor(int color) { lineColor = color; invalidate(); }

    public void setData(List<Double> data) {
        if (data == null || data.size() < 2) { values = null; invalidate(); return; }
        values = new float[data.size()];
        for (int i = 0; i < data.size(); i++) values[i] = (float) (double) data.get(i);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (values == null || values.length < 2) return;

        float w = getWidth(), h = getHeight();
        float padY = h * 0.12f;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : values) { if (v < min) min = v; if (v > max) max = v; }
        float range = max - min;
        if (range < 1e-6f) range = 1f;

        linePaint.setColor(lineColor);
        fillPaint.setShader(new LinearGradient(0, 0, 0, h,
                (lineColor & 0x00FFFFFF) | 0x33000000, 0x00000000, Shader.TileMode.CLAMP));

        linePath.reset(); fillPath.reset();
        for (int i = 0; i < values.length; i++) {
            float x = w * i / (values.length - 1);
            float y = padY + (h - 2 * padY) * (1f - (values[i] - min) / range);
            if (i == 0) { linePath.moveTo(x, y); fillPath.moveTo(x, h); fillPath.lineTo(x, y); }
            else        { linePath.lineTo(x, y); fillPath.lineTo(x, y); }
        }
        fillPath.lineTo(w, h);
        fillPath.close();

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);
    }
}
