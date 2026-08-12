package com.economic.dashboard.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Lightweight inline sparkline for AI Analyst chat bubbles — renders a
 * [CHART:SERIES:WINDOW] tag as a small trend line without the weight of a
 * full MPAndroidChart instance per recycled row.
 *
 * This view is declared in `card_kpi_badge.xml` and `item_chat_message.xml`, and
 * XML inflation resolves the `(Context, AttributeSet)` constructor reflectively.
 * That form is the primary constructor's **full arity**, so inflation would work
 * even without `@JvmOverloads`; the annotation is here to reproduce the Java's
 * second, 1-arg `SparklineView(Context)` constructor.
 *
 * (The usual "custom Views need @JvmOverloads or XML inflation breaks at runtime"
 * warning applies when the *trailing* parameters carry defaults — e.g. the common
 * `defStyleAttr: Int = 0` idiom — which is not the shape here.)
 *
 * One inert delta from the Java: the synthesized 1-arg overload delegates to
 * `View(Context, AttributeSet?)` with null attrs, whereas the Java's
 * `SparklineView(Context)` called `View(Context)` — a different AOSP init path.
 * Nothing constructs this view programmatically today (both call sites use
 * `findViewById`), so it is unreachable, but it would matter if one ever did.
 */
class SparklineView @JvmOverloads constructor(
    c: Context,
    a: AttributeSet? = null
) : View(c, a) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePath = Path()
    private val fillPath = Path()
    private var values: FloatArray? = null
    private var lineColor = 0xFFB8860B.toInt() // gold ink default; overridable
    /** TICKET-H: the KPI-card call site wants the original plain-stroke look
     *  (no gradient fill underneath); the chat bubble call site keeps it. */
    private var fillEnabled = true

    init {
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = resources.displayMetrics.density * 2f
        linePaint.strokeCap = Paint.Cap.ROUND
        fillPaint.style = Paint.Style.FILL
    }

    fun setLineColor(color: Int) { lineColor = color; invalidate() }

    fun setFillEnabled(enabled: Boolean) { fillEnabled = enabled; invalidate() }

    // min/max/range are pure derived state of `values` — computed once per
    // setData rather than on every onDraw (this view lives in recycled rows,
    // which redraw far more often than they rebind).
    private var minVal = 0f
    private var rangeVal = 1f

    fun setData(data: List<Double>?) {
        if (data == null || data.size < 2) { values = null; invalidate(); return }
        val v = FloatArray(data.size)
        for (i in data.indices) v[i] = data[i].toFloat()
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (f in v) { if (f < min) min = f; if (f > max) max = f }
        var range = max - min
        if (range < 1e-6f) range = 1f
        minVal = min
        rangeVal = range
        values = v
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vals = values
        if (vals == null || vals.size < 2) return

        val w = width.toFloat()
        val h = height.toFloat()
        val padY = h * 0.12f
        val min = minVal
        val range = rangeVal

        linePaint.color = lineColor
        if (fillEnabled)
            fillPaint.shader = LinearGradient(0f, 0f, 0f, h,
                (lineColor and 0x00FFFFFF.toInt()) or 0x33000000.toInt(), 0x00000000,
                Shader.TileMode.CLAMP)

        linePath.reset(); fillPath.reset()
        for (i in vals.indices) {
            val x = w * i / (vals.size - 1)
            val y = padY + (h - 2 * padY) * (1f - (vals[i] - min) / range)
            if (i == 0) { linePath.moveTo(x, y); fillPath.moveTo(x, h); fillPath.lineTo(x, y) }
            else { linePath.lineTo(x, y); fillPath.lineTo(x, y) }
        }
        fillPath.lineTo(w, h)
        fillPath.close()

        if (fillEnabled) canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
    }
}
