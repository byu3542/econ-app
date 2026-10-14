package com.economic.dashboard.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Fade
import androidx.transition.TransitionManager
import com.economic.dashboard.R
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.ui.views.SegmentedView
import com.economic.dashboard.utils.ChartHelper
import com.economic.dashboard.utils.ChartPalette
import com.economic.dashboard.utils.AxisDateFormat
import com.economic.dashboard.utils.TimeframeSelector
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Single entry point for every metric badge popup (mockup 1c). Replaces the
 * old per-metric `MetricBottomSheet.show(ctx, R.layout.dialog_xxx_status, ...)`
 * call sites — this always inflates the one generic
 * `sheet_metric_generic_content.xml` and binds it from a [MetricPopupRegistry] spec.
 */
object MetricPopupController {

    /**
     * @param pane   2026-10-12: "graph" or "table" to open the DATA pane on;
     *               null keeps the default (graph).
     * @param months 2026-10-12: window to open at, not persisted (a chat card
     *               opening its own window). Null keeps the remembered one.
     */
    @JvmStatic
    @JvmOverloads
    fun show(ctx: Context, vm: EconomicViewModel, id: String, pane: String? = null, months: Int? = null) {
        val spec = MetricPopupRegistry.byId(id) ?: return

        // History is resolved before the sheet is shown because the add-alert
        // dialog's caption is built from it (P9 TICKET-47): the value it quotes
        // has to be the last point of *this* lambda — the same series, in the
        // same units, that AlertValues.build will evaluate the rule against.
        val fullHistory = spec.history(vm)
        val content = MetricBottomSheet.show(
            ctx, R.layout.sheet_metric_generic_content, spec.alertSeriesKey, spec.title,
            MetricBottomSheet.AlertContext(
                spec.valueFmt, fullHistory.lastOrNull()?.value,
                spec.quantityNote, cadenceOf(fullHistory)))
        if (content !is LinearLayout) return

        content.findViewById<TextView>(R.id.tvPopupTitle)?.text = spec.title

        val gradeResult = spec.grade(fullHistory)
        bindSubtitle(content.findViewById<TextView>(R.id.tvPopupSubtitle), ctx, spec, fullHistory, gradeResult)
        bindBands(content.findViewById<LinearLayout>(R.id.benchmarkBandContainer), ctx, spec, gradeResult)

        // paneStack/dataPaneStack (sheet_metric_generic_content.xml) hold each
        // pair of panes overlaid in one FrameLayout instead of as plain
        // siblings, and the inactive pane is always INVISIBLE rather than
        // GONE. That means paneStack's height is permanently the taller of
        // BENCHMARKS/DATA (and dataPaneStack's the taller of TABLE/GRAPH),
        // fixed from the very first layout pass — so toggling which pane is
        // drawn never changes any container's size and the sheet can't jump.
        // The transition below is purely a crossfade (Fade, not AutoTransition
        // — there's no bounds change left to animate).
        val paneStack: View? = content.findViewById(R.id.paneStack)
        val paneBenchmarks: View? = content.findViewById(R.id.paneBenchmarks)
        val paneData: View? = content.findViewById(R.id.paneData)
        // TICKET-55: both rows are SegmentedViews. DATA is the default-active
        // pane in the XML, so select() without a callback is enough here.
        val switchPrimary: SegmentedView? = content.findViewById(R.id.switchPrimary)
        if (switchPrimary != null) {
            switchPrimary.onSelect = SegmentedView.OnSelect { tag ->
                if (paneStack is android.view.ViewGroup) {
                    TransitionManager.beginDelayedTransition(paneStack, Fade())
                }
                setPaneActive(paneBenchmarks, tag == "benchmarks")
                setPaneActive(paneData, tag == "data")
            }
            switchPrimary.select("data", animate = false)
        }

        val dataPaneStack: View? = content.findViewById(R.id.dataPaneStack)
        val paneTable: View? = content.findViewById(R.id.paneTable)
        val paneGraph: View? = content.findViewById(R.id.paneGraph)
        val switchSecondary: SegmentedView? = content.findViewById(R.id.switchSecondary)
        if (switchSecondary != null) {
            switchSecondary.onSelect = SegmentedView.OnSelect { tag ->
                if (dataPaneStack is android.view.ViewGroup) {
                    TransitionManager.beginDelayedTransition(dataPaneStack, Fade())
                }
                setPaneActive(paneTable, tag == "table")
                setPaneActive(paneGraph, tag == "graph")
            }
            switchSecondary.select("graph", animate = false)
            if (pane == "table") {
                switchSecondary.select("table", animate = false)
                setPaneActive(paneTable, true)
                setPaneActive(paneGraph, false)
            }
        }

        val rv: RecyclerView? = content.findViewById(R.id.rvMetricHistory)
        val adapter = MetricHistoryAdapter(spec.valueFmt, spec.deltaFmt, spec.goodWhenDown)
        rv?.layoutManager = LinearLayoutManager(ctx)
        rv?.adapter = adapter

        val chart: LineChart? = content.findViewById(R.id.chartMetricHistory)
        if (chart != null) {
            // styleLineChart (not declutterDark) — its chart_grid/chart_axis_*
            // colors already flip with the theme; declutterDark hardcodes a
            // translucent-white grid tuned for the always-navy tab-page charts,
            // which disappears on this sheet's light-mode surface.
            ChartHelper.styleLineChart(chart, spec.title, "Date", spec.title)
            ChartHelper.attachCrosshair(chart)
            // Percent series read to 2dp on the Y axis (and so in the scrub
            // bubble, which reuses this formatter) to match the DATA table,
            // overriding the global chart-decimals setting.
            if (spec.valueFmt.contains("%%")) {
                chart.axisLeft.setValueFormatter(object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String =
                        String.format(Locale.US, "%.2f", value)
                })
            }
        }

        val tfSelector: SegmentedView? = content.findViewById(R.id.timeframeSelector)
        // Third row of the DATA pane: subordinate to DATA / BENCHMARKS, same
        // weight as GRAPH / TABLE above it (TICKET-55 step 5).
        tfSelector?.emphasis = SegmentedView.Emphasis.SUBTLE
        TimeframeSelector.attach(tfSelector, spec.chartId, initialMonths = months) { m ->
            // An override window isn't persisted, so read it from the callback
            // rather than from the setting; without one, unchanged.
            val windowed = if (months != null) EconomicViewModel.filterByMonths(fullHistory, m)
                else EconomicViewModel.filterByTimeframe(ctx, fullHistory, spec.chartId)
            adapter.setData(windowed)
            buildChart(chart, windowed, spec, if (months != null) m else null)
        }
    }

    private fun bindSubtitle(
        tv: TextView?, ctx: Context, spec: MetricPopupRegistry.MetricPopupSpec,
        rows: List<EconomicDataPoint>, grade: Benchmarks.Result?
    ) {
        if (tv == null) return
        val latest = rows.lastOrNull()
        val valueStr = if (latest != null) String.format(Locale.US, spec.valueFmt, latest.value) else "—"
        if (grade == null) {
            tv.text = valueStr
            return
        }
        // Pantrii port U11 (KTD10 rule 2): the grade leads with its tier glyph,
        // so the state reads from shape and word. Ink, not the tier colour
        // (signal_watch's pale yellow is illegible as text on surface) and not
        // the accent, which must never sit beside a badge (KTD10 rule 1). The
        // glyph disc in the highlighted band row is the tier-colour cue.
        val graded = "${BenchmarkStyle.tierGlyph(ctx, grade.tier)} ${grade.word}"
        val full = "$valueStr · $graded"
        val span = SpannableString(full)
        val start = full.length - graded.length
        val color = ContextCompat.getColor(ctx, R.color.ink)
        span.setSpan(ForegroundColorSpan(color), start, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(StyleSpan(Typeface.BOLD), start, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tv.text = span
    }

    private fun bindBands(
        container: LinearLayout?, ctx: Context,
        spec: MetricPopupRegistry.MetricPopupSpec, grade: Benchmarks.Result?
    ) {
        if (container == null) return
        container.removeAllViews()
        val inflater = LayoutInflater.from(ctx)
        for ((index, band) in spec.bands.withIndex()) {
            val row = inflater.inflate(R.layout.item_benchmark_band, container, false)
            val glyph: TextView? = row.findViewById(R.id.tvBandGlyph)
            val tvLabel: TextView? = row.findViewById(R.id.tvBandLabel)
            val tvRange: TextView? = row.findViewById(R.id.tvBandRange)
            if (glyph != null) BenchmarkStyle.glyphDisc(glyph, ctx, band.tier)
            tvLabel?.text = band.label
            // Same reasoning as the subtitle: tier color goes on the disc only,
            // never on text (matches BenchmarkStyle.applyBadge's own rule).
            tvLabel?.setTextColor(ContextCompat.getColor(ctx, R.color.muted))
            tvRange?.text = band.rangeText

            if (grade != null && band.tier == grade.tier && band.label == grade.word) {
                val bg = GradientDrawable()
                bg.setCornerRadius(ctx.resources.getDimension(R.dimen.radius_chip))
                val c = BenchmarkStyle.colorFor(ctx, band.tier)
                bg.setColor((c and 0x00FFFFFF) or 0x1F000000) // ~12% alpha — "this is the current band"
                row.background = bg
            }

            container.addView(row)
            if (index < spec.bands.size - 1) {
                val divider = View(ctx)
                divider.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(ctx, 1))
                divider.setBackgroundColor(ContextCompat.getColor(ctx, R.color.line))
                container.addView(divider)
            }
        }
    }

    private fun buildChart(
        chart: LineChart?, rows: List<EconomicDataPoint>,
        spec: MetricPopupRegistry.MetricPopupSpec, windowMonths: Int? = null
    ) {
        if (chart == null) return
        if (rows.isEmpty()) {
            chart.clear()
            return
        }
        val entries = ArrayList<Entry>()
        val dates = ArrayList<String>()
        for (i in rows.indices) {
            entries.add(Entry(i.toFloat(), rows[i].value.toFloat()))
            dates.add(rows[i].date)
        }
        if (windowMonths != null) AxisDateFormat.attach(chart, spec.chartId, windowMonths, dates)
        else AxisDateFormat.attach(chart, chart.context, spec.chartId, dates) // TICKET-57
        val ds = LineDataSet(entries, spec.title)
        ChartHelper.styleLineSeries(ds, ChartPalette.primary(chart.context)) // U10, KTD10 rule 3: never the accent
        ChartHelper.setLineData(chart, LineData(ds))
    }

    /**
     * Shows/hides one pane of an overlaid pair (paneStack or dataPaneStack —
     * see the comment above their SegmentedView wiring). Uses
     * INVISIBLE rather than GONE so the inactive pane still holds its share
     * of the container's fixed height, but that means it stays in the
     * accessibility tree unless told otherwise — NO_HIDE_DESCENDANTS keeps a
     * TalkBack user from landing on a pane they can't see.
     */
    private fun setPaneActive(pane: View?, active: Boolean) {
        if (pane == null) return
        pane.visibility = if (active) View.VISIBLE else View.INVISIBLE
        pane.importantForAccessibility = if (active)
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        else
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun dpToPx(ctx: Context, dp: Int): Int =
        Math.round(dp * ctx.resources.displayMetrics.density)

    /**
     * P9 TICKET-48: how often this series prints, inferred from the spacing of
     * its own most recent observations rather than from a per-metric constant —
     * a constant would be one more thing to keep in sync with the registry, and
     * the dates are already the authority. Feeds the "Notify on any change"
     * caption, which otherwise reads as "constant pings" on a monthly series.
     *
     * Returns null when the series is too short or the spacing is unrecognizable;
     * the caller then says the generic thing rather than guessing a cadence.
     */
    private fun cadenceOf(rows: List<EconomicDataPoint>): String? {
        if (rows.size < 3) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val gaps = ArrayList<Long>()
        var i = rows.size - 1
        while (i > 0 && gaps.size < 5) {
            val older = parseDay(fmt, rows[i - 1].date)
            val newer = parseDay(fmt, rows[i].date)
            if (older != null && newer != null && newer > older) {
                gaps.add((newer - older) / 86_400_000L)
            }
            i--
        }
        if (gaps.isEmpty()) return null
        gaps.sort()
        return when (gaps[gaps.size / 2]) {
            in 0..3 -> "daily"
            in 4..10 -> "weekly"
            in 11..45 -> "monthly"
            in 46..130 -> "quarterly"
            else -> null
        }
    }

    private fun parseDay(fmt: SimpleDateFormat, date: String?): Long? {
        if (date == null || date.length < 10) return null
        return try {
            fmt.parse(date.substring(0, 10))?.time
        } catch (e: ParseException) {
            null
        }
    }
}
