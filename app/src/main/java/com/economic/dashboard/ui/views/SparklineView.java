package com.economic.dashboard.ui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal sparkline: draws a single gold polyline across the view bounds.
 * Call {@link #setValues(List)} with chronological values (oldest first).
 */
public class SparklineView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final List<Float> values = new ArrayList<>();

    public SparklineView(Context context) { this(context, null); }

    public SparklineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(Color.parseColor("#C9A84C"));
    }

    public void setValues(List<Float> newValues) {
        values.clear();
        if (newValues != null) values.addAll(newValues);
        setVisibility(values.size() >= 2 ? VISIBLE : GONE);
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = values.size();
        if (n < 2) return;

        float pad = dp(2f);
        float w = getWidth()  - 2 * pad;
        float h = getHeight() - 2 * pad;
        if (w <= 0 || h <= 0) return;

        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : values) { min = Math.min(min, v); max = Math.max(max, v); }
        float range = max - min;
        if (range < 1e-6f) { min -= 0.5f; range = 1f; }

        path.reset();
        for (int i = 0; i < n; i++) {
            float x = pad + (w * i) / (n - 1);
            float y = pad + h - ((values.get(i) - min) / range) * h;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, paint);
    }
}
