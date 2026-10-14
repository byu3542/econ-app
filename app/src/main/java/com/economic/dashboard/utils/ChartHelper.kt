package com.economic.dashboard.utils

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

import com.economic.dashboard.R
import com.economic.dashboard.ui.BenchmarkStyle

import android.widget.TextView
import androidx.annotation.ColorInt

import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
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
        // TICKET-57: off until setLineData sees more than one series.
        legend.setEnabled(false)

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
     * The panel-card base style (dark navy until Pantrii port U10; now on the
     * surface card, chrome from [ChartPalette]) the per-tab fragments used to each carry as a
     * private `styleChart` — one copy here so a style change is single-point.
     * The three per-site variants are parameters: `noDataText` (Stocks:
     * "Loading…", Yields: "Loading yield data…"), `noDataTextColor`
     * (Yields only), and `xAxisLineToGrid` (Stocks only, tints the x axis
     * line to the grid color).
     */
    @JvmStatic
    fun stylePanelChart(
        chart: LineChart,
        noDataText: String = "Loading data...",
        noDataTextColor: Int? = null,
        xAxisLineToGrid: Boolean = false
    ) {
        val ctx: Context = chart.context
        val grid = ChartPalette.grid(ctx)
        // Pantrii port U10: transparent, so the chart sits on the surface card.
        chart.setBackgroundColor(Color.TRANSPARENT); chart.setDrawGridBackground(false)
        chart.setDrawBorders(false); chart.setTouchEnabled(true); chart.setDragEnabled(true)
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.description.setEnabled(false)
        chart.setNoDataText(noDataText)
        chart.setNoDataTextColor(noDataTextColor ?: ChartPalette.axisText(ctx))
        chart.setExtraBottomOffset(8f)
        val x = chart.xAxis
        x.setPosition(XAxis.XAxisPosition.BOTTOM); x.setTextColor(ChartPalette.axisText(ctx)); x.setTextSize(9f)
        x.setDrawGridLines(true); x.setGridColor(grid)
        if (xAxisLineToGrid) x.setAxisLineColor(grid)
        x.setLabelRotationAngle(-45f)
        x.setGranularity(1f); x.setLabelCount(6, false); x.setAvoidFirstLastClipping(true)
        val y = chart.axisLeft
        y.setTextColor(ChartPalette.axisText(ctx)); y.setTextSize(10f); y.setDrawGridLines(true); y.setGridColor(grid)
        chart.axisRight.setEnabled(false)
        // TICKET-57: one legend look (line swatch, slate, bottom-centre); off
        // until setLineData sees more than one series.
        val legend = chart.legend
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM)
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER)
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL)
        legend.setDrawInside(false)
        legend.setTextSize(11f)
        legend.setTextColor(ChartPalette.legendText(ctx))
        legend.setForm(Legend.LegendForm.LINE)
        legend.setFormSize(16f)
        legend.setFormLineWidth(2f)
        legend.setYOffset(5f)
        legend.setEnabled(false)
    }

    /**
     * TICKET-57 — the one way to put data on a line chart. Applies the legend
     * rule (a detached legend only when there is more than one series and the
     * chart is not using direct end-of-line labels), optionally animates, and
     * redraws. Replaces the `setData(); animateX(); invalidate()` triplets the
     * fragments carried, and the per-fragment `legend.setEnabled(false)` toggles.
     */
    @JvmStatic
    @JvmOverloads
    fun setLineData(chart: LineChart?, data: LineData, animateMs: Int = 0) {
        if (chart == null) return
        chart.setData(data)
        val direct = chart.getTag(R.id.chart_direct_labels) == true
        chart.legend.setEnabled(data.getDataSetCount() > 1 && !direct)
        if (animateMs > 0) chart.animateX(animateMs)
        chart.invalidate()
    }

    /**
     * Dashed benchmark LimitLine, the body the fragments used to duplicate as
     * a private `addLine`. Explicit setters on MPAndroidChart throughout
     * (migration lesson 12) — the library cannot be built against here, so
     * accessor pairs are never assumed.
     */
    @JvmStatic
    fun addLimitLine(chart: LineChart, value: Float, label: String, @ColorInt color: Int) {
        val l = LimitLine(value, label)
        l.setLineColor(color); l.setLineWidth(1f)
        l.setTextColor(color); l.setTextSize(9f); l.enableDashedLine(8f, 4f, 0f)
        chart.axisLeft.addLimitLine(l)
    }

    /** TICKET-57: benchmark line coloured by its tier (colourblind-aware). */
    @JvmStatic
    fun addLimitLine(chart: LineChart, value: Float, label: String, tier: BenchmarkStyle.Tier) {
        addLimitLine(chart, value, label, ChartPalette.limit(chart.context, tier))
    }

    /** TICKET-57: the neutral "normal range" benchmark line, which has no tier. */
    @JvmStatic
    fun addNormalLimitLine(chart: LineChart, value: Float, label: String) {
        addLimitLine(chart, value, label, ChartPalette.limitNormal(chart.context))
    }

    /**
     * TICKET-07 — de-clutter pass for the navy-surface charts that carry their
     * own local `styleChart`. Call once right after that method: it thins
     * gridlines, drops the redundant axis lines, and trims the label count so
     * the plot reads cleanly at a glance. Colour choices are self-contained
     * (low-alpha white on navy) so no resource lookups are needed.
     */
    @JvmStatic
    fun declutterPanel(chart: LineChart?) {
        if (chart == null) return
        val faintGrid = ChartPalette.grid(chart.context)
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
        chart.setTag(R.id.chart_direct_labels, true) // TICKET-57: setLineData keeps it off
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
        val top = ColorUtils.setAlphaComponent(color, 0x2E)    // ~18% alpha
        val bottom = ColorUtils.setAlphaComponent(color, 0x00) // fully transparent
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
     * 2026-10-12 analyst cards: the metric sheet's chart ([styleLineChart] +
     * [attachCrosshair]) shrunk for a chat bubble. No zoom; 3–4 y ticks; x
     * labels at start, middle and end; horizontal and vertical gridlines in the
     * sheet's own `chart_grid` token. The marker stays up after a drag (the
     * sheet clears it via AskAnalyst's gesture listener, which isn't wired
     * here) and tap-to-highlight is off so a stray tap can't drop it.
     *
     * A drag that turns mostly horizontal claims the gesture from the chat
     * list (`requestDisallowInterceptTouchEvent`); a vertical one leaves it,
     * so the list still scrolls over the card.
     */
    @JvmStatic
    fun styleCompactChart(chart: LineChart, yFormatter: ValueFormatter) {
        styleLineChart(chart, "", "", "")
        attachCrosshair(chart)
        chart.setScaleEnabled(false)
        chart.setPinchZoom(false)
        chart.setDoubleTapToZoomEnabled(false)
        chart.setHighlightPerTapEnabled(false)
        chart.setNoDataText("")
        chart.setExtraBottomOffset(4f)
        chart.setExtraTopOffset(8f)
        chart.setExtraRightOffset(12f)
        chart.setExtraLeftOffset(0f)

        val gridColor = ContextCompat.getColor(chart.context, R.color.chart_grid)
        val x = chart.xAxis
        x.setLabelRotationAngle(0f)
        x.setLabelCount(3, true)
        x.setDrawGridLines(true)
        x.setGridColor(gridColor)
        x.setGridLineWidth(0.4f)
        x.enableGridDashedLine(6f, 4f, 0f)
        x.setAvoidFirstLastClipping(true)
        x.setGranularityEnabled(false)

        val y = chart.axisLeft
        y.setDrawGridLines(true)
        y.setLabelCount(4, false)
        y.setValueFormatter(yFormatter)

        val slop = android.view.ViewConfiguration.get(chart.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        chart.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = Math.abs(ev.x - downX)
                    val dy = Math.abs(ev.y - downY)
                    if (dx > slop && dx > dy) v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false // MPAndroidChart's own listener still runs
        }
    }

    /** Puts the crosshair on the newest point, the card's resting state. */
    @JvmStatic
    fun highlightLatest(chart: LineChart) {
        val ds = chart.data?.getDataSetByIndex(0) ?: return
        if (ds.entryCount == 0) return
        val last = ds.getEntryForIndex(ds.entryCount - 1)
        chart.highlightValue(Highlight(last.x, last.y, 0), false)
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
