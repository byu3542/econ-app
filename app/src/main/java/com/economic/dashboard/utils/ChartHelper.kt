package com.economic.dashboard.utils

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

import androidx.core.content.ContextCompat

import com.economic.dashboard.R

import android.widget.TextView

import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.DefaultAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.utils.MPPointF

object ChartHelper {

    /**
     * Styles a LineChart with the app theme:
     * transparent background, theme grid lines and axis text, LINE-form
     * legend, and disabled description overlay. Grid visibility and Y-axis
     * decimal precision come from user settings (SettingsManager).
     */
    @JvmStatic
    fun styleLineChart(chart: LineChart, description: String, xTitle: String, yTitle: String) {
        val ctx: Context = chart.context
        val gridColor = ContextCompat.getColor(ctx, R.color.chart_grid)
        val axisLine = ContextCompat.getColor(ctx, R.color.chart_axis_line)
        val axisText = ContextCompat.getColor(ctx, R.color.chart_axis_text)
        val legendText = ContextCompat.getColor(ctx, R.color.chart_legend_text)

        // User preferences
        val showGrid = SettingsManager.gridlinesEnabled(ctx)
        val decimals = SettingsManager.getChartDecimals(ctx)

        // Disable description label (title is in the card header)
        val desc = Description()
        desc.setEnabled(false)
        chart.setDescription(desc)

        // Background & Borders — transparent so card background shows through
        chart.setBackgroundColor(Color.TRANSPARENT)
        chart.setDrawGridBackground(false)
        chart.setDrawBorders(false)

        // Interaction
        chart.setTouchEnabled(true)
        chart.setDragEnabled(true)
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.setDoubleTapToZoomEnabled(true)

        // X Axis — IOS-07: no vertical gridlines at all (Swift Charts doesn't
        // draw them); fewer labels (TICKET-07)
        val xAxis = chart.xAxis
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM)
        xAxis.setDrawGridLines(false)
        xAxis.setTextColor(axisText)
        xAxis.setTextSize(10f)
        xAxis.setGranularity(1f)
        xAxis.setLabelRotationAngle(-45f)
        xAxis.setAvoidFirstLastClipping(true)
        xAxis.setLabelCount(5, false)
        xAxis.setAxisLineColor(axisLine)
        xAxis.setDrawAxisLine(false)

        // Y Axis (Left) — IOS-07: dashed hairlines instead of solid thin
        // lines, no redundant axis line (TICKET-07)
        val leftAxis = chart.axisLeft
        leftAxis.setDrawGridLines(showGrid)
        leftAxis.setGridColor(gridColor)
        leftAxis.setGridLineWidth(0.4f)
        leftAxis.enableGridDashedLine(6f, 4f, 0f)
        leftAxis.setTextColor(axisText)
        leftAxis.setTextSize(10f)
        leftAxis.setDrawZeroLine(true)
        leftAxis.setZeroLineColor(axisLine)
        leftAxis.setDrawAxisLine(false)
        leftAxis.setLabelCount(5, false)
        leftAxis.setXOffset(10f) // Space for labels
        leftAxis.setValueFormatter(DefaultAxisValueFormatter(decimals))

        // Disable Right Axis
        chart.axisRight.setEnabled(false)

        // Legend
        val legend = chart.legend
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM)
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER)
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL)
        legend.setDrawInside(false)
        legend.setTextSize(11f)
        legend.setTextColor(legendText)
        legend.setForm(Legend.LegendForm.LINE)
        legend.setYOffset(5f)

        // Loading state
        chart.setNoDataText("Awaiting Economic Data...")
        chart.setNoDataTextColor(axisText)

        // Padding to prevent labels from being cut off
        chart.setExtraBottomOffset(30f)
        chart.setExtraLeftOffset(10f)
        chart.setExtraTopOffset(20f)
        chart.setExtraRightOffset(20f)
    }

    /**
     * TICKET-07 — de-clutter pass for the navy-surface charts that carry their
     * own local `styleChart`. Call once right after that method: it thins
     * gridlines, drops the redundant axis lines, and trims the label count so
     * the plot reads cleanly at a glance. Colour choices are self-contained
     * (low-alpha white on navy) so no resource lookups are needed.
     */
    @JvmStatic
    fun declutterDark(chart: LineChart?) {
        if (chart == null) return
        val faintGrid = Color.argb(0x0D, 0xFF, 0xFF, 0xFF) // ~5% white
        chart.description.setEnabled(false)

        // IOS-07: no vertical gridlines — Swift Charts doesn't draw them.
        val x = chart.xAxis
        x.setDrawGridLines(false)
        x.setDrawAxisLine(false)
        x.setLabelCount(5, false)
        x.setAvoidFirstLastClipping(true)

        // IOS-07: horizontal gridlines become dashed hairlines only.
        val y = chart.axisLeft
        y.setDrawGridLines(true)
        y.setGridColor(faintGrid)
        y.setGridLineWidth(0.4f)
        y.enableGridDashedLine(6f, 4f, 0f)
        y.setDrawAxisLine(false)
        y.setLabelCount(5, false)

        chart.axisRight.setEnabled(false)
        chart.setExtraRightOffset(28f) // room for right-end line labels (TICKET-08)
    }

    // ── TICKET-08: direct line labeling on multi-series charts ──────────────

    /**
     * Turns off the detached legend so multi-series charts can rely on labels
     * drawn at each line's right end instead (recognition over recall).
     */
    @JvmStatic
    fun useDirectLabels(chart: LineChart?) {
        if (chart == null) return
        chart.legend.setEnabled(false)
        chart.setExtraRightOffset(40f) // reserve space for the end labels
    }

    /**
     * Draws `label` once, at the right-most point of `ds`, in the
     * line's own colour — so each series is identifiable without cross-checking
     * a legend. Values on all other points stay hidden.
     */
    @JvmStatic
    fun labelLineEnd(ds: LineDataSet?, label: String) {
        if (ds == null || ds.entryCount == 0) return
        val lastX = ds.getEntryForIndex(ds.entryCount - 1).x
        ds.setDrawValues(true)
        ds.setValueTextColor(ds.color)
        ds.setValueTextSize(9f)
        ds.setValueFormatter(object : ValueFormatter() {
            override fun getPointLabel(entry: Entry?): String {
                return if (entry != null && entry.x == lastX) label else ""
            }
        })
    }

    // ── IOS-07: Swift-Charts-style primary series ───────────────────────────

    /**
     * The real-data line look: 2dp, no cubic smoothing (smoothing a real
     * series misrepresents it), no per-point circles/values, gradient fill
     * from ~18% of the line's own color fading to transparent. Intended for
     * a chart's PRIMARY series only — benchmark/reference lines (LimitLine)
     * are a separate, deliberately thinner/plain style and untouched by this.
     * MPAndroidChart v3.1.0 has no public API for round line caps, so that
     * part of the look isn't applied here.
     */
    @JvmStatic
    fun styleLineSeries(ds: LineDataSet?, color: Int) {
        if (ds == null) return
        ds.color = color
        ds.setLineWidth(2f)
        ds.setMode(LineDataSet.Mode.LINEAR)
        ds.setDrawCircles(false)
        ds.setDrawValues(false)
        ds.setDrawFilled(true)
        val top = (color and 0x00FFFFFF.toInt()) or 0x2E000000.toInt()    // ~18% alpha
        val bottom = color and 0x00FFFFFF.toInt()                        // fully transparent
        ds.setFillDrawable(
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(top, bottom)
            )
        )
    }

    // ── TICKET-23: chart crosshair / value scrubbing ────────────────────────

    /**
     * Enables drag-to-scrub read-out on a line chart. Dragging highlights the
     * nearest point and shows a [ChartMarker] bubble with the date and the
     * unit-formatted value at that point. One-line opt-in per chart.
     *
     * The marker is cleared when the gesture ends. Charts that also use
     * `AskAnalyst.attachChartExplain` get that clear for free (its gesture
     * listener calls `highlightValue(null)` on gesture end); this method
     * deliberately does NOT install its own gesture listener so it never
     * clobbers the "Explain this chart" long-press.
     */
    @JvmStatic
    fun attachCrosshair(chart: LineChart?) {
        if (chart == null) return
        val marker = ChartMarker(chart.context, chart)
        marker.setChartView(chart)
        chart.setMarker(marker)
        chart.setDrawMarkers(true)
        chart.setHighlightPerDragEnabled(true)
        chart.setHighlightPerTapEnabled(true)

        // IOS-07: a light haptic tick each time the scrub crosses onto a new
        // data point, not on every pixel of drag.
        var lastTickedX = Float.NaN
        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e == null) return
                if (lastTickedX.isNaN() || e.x != lastTickedX) {
                    lastTickedX = e.x
                    HapticUtil.scrubTick(chart) // IOS-09
                }
            }
            override fun onNothingSelected() {
                lastTickedX = Float.NaN
            }
        })
    }

    /**
     * Read-out bubble drawn at the touched point. Reuses the chart's own axis
     * value formatters so the date and unit match what's already on the axes;
     * falls back to [NumberFormatUtil] when a chart has no left-axis
     * formatter set.
     */
    class ChartMarker(ctx: Context, private val chart: LineChart?) : MarkerView(ctx, R.layout.view_chart_marker) {
        private val tvValue: TextView = findViewById(R.id.tvMarkerValue)
        private val tvDate: TextView = findViewById(R.id.tvMarkerDate)

        override fun refreshContent(e: Entry?, highlight: Highlight?) {
            if (e != null) {
                val yf: ValueFormatter? = chart?.axisLeft?.valueFormatter
                tvValue.text = if (yf != null) yf.getFormattedValue(e.y) else NumberFormatUtil.number(e.y.toDouble(), 2)
                val xf: ValueFormatter? = chart?.xAxis?.valueFormatter
                tvDate.text = if (xf != null) xf.getFormattedValue(e.x) else ""
            }
            super.refreshContent(e, highlight)
        }

        override fun getOffset(): MPPointF {
            return MPPointF(-(width / 2f), -height.toFloat() - 8)
        }
    }
}
