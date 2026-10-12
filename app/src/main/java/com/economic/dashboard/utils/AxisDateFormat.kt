package com.economic.dashboard.utils

import android.content.Context
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * TICKET-57 — one date grammar for everything a chart or card shows.
 *
 * Data dates arrive as ISO `yyyy-MM-dd` strings and used to be reshaped by
 * eight per-fragment label builders into five different looks
 * (`2026-04-01`, `05/29/26`, `Aug 24`, `04-2024`, `08/24`). Two forms remain:
 *
 * - **Axis labels** ([axisLabel], [attach]): `MMM yyyy` ("Apr 2026") for
 *   monthly and quarterly series and for any window longer than a year;
 *   `MMM d` ("May 29") for daily series inside a one-year window, where the
 *   year is implied and the day is what changes.
 * - **Full dates** ([full]): `MMM d, yyyy` ("Apr 1, 2026") for the Overview
 *   card date line and the Yields table.
 *
 * The cadence comes from [ChartCadence] by the chart's timeframe id, the
 * window from [SettingsManager.getChartTimeframeMonths], so the label and
 * the data it describes are chosen from the same facts. ISO never reaches
 * the screen; an unparseable string falls back to itself rather than to "".
 */
object AxisDateFormat {

    private const val ISO = "yyyy-MM-dd"
    private const val AXIS_MONTH = "MMM yyyy"
    private const val AXIS_DAY = "MMM d"
    private const val FULL = "MMM d, yyyy"

    /** Pattern for an axis given the series cadence and the visible window. */
    @JvmStatic
    fun axisPattern(cadence: ChartCadence.Cadence, windowMonths: Int): String =
        if (cadence == ChartCadence.Cadence.DAILY && windowMonths <= 12) AXIS_DAY else AXIS_MONTH

    /** Pattern for the chart with this timeframe id, as currently windowed. */
    @JvmStatic
    fun axisPattern(ctx: Context, chartId: String): String =
        axisPattern(ChartCadence.of(chartId), SettingsManager.getChartTimeframeMonths(ctx, chartId))

    /** One axis label from an ISO date. */
    @JvmStatic
    fun axisLabel(isoDate: String?, pattern: String): String = reformat(isoDate, pattern)

    /** "Apr 1, 2026" from an ISO date; the raw string if it does not parse. */
    @JvmStatic
    fun full(isoDate: String?): String = reformat(isoDate, FULL)

    /** Axis labels for [isoDates] on the chart with this timeframe id. */
    @JvmStatic
    fun axisLabels(ctx: Context, chartId: String, isoDates: List<String>): List<String> {
        val pattern = axisPattern(ctx, chartId)
        val out = SimpleDateFormat(pattern, Locale.US)
        val parser = SimpleDateFormat(ISO, Locale.US)
        return isoDates.map { reformat(it, parser, out) }
    }

    /**
     * Installs an index → label formatter on [chart]'s x-axis for entries
     * whose x is the position in [isoDates]. Label density is left to the
     * axis's own `labelCount`; the formatter never blanks positions itself.
     */
    @JvmStatic
    fun attach(chart: LineChart?, ctx: Context, chartId: String, isoDates: List<String>) {
        attachLabels(chart, axisLabels(ctx, chartId, isoDates))
    }

    /** 2026-10-12: same, for a window the caller holds rather than the
     *  persisted one (a sheet opened from a chat card). */
    @JvmStatic
    fun attach(chart: LineChart?, chartId: String, windowMonths: Int, isoDates: List<String>) {
        val pattern = axisPattern(ChartCadence.of(chartId), windowMonths)
        attachLabels(chart, isoDates.map { axisLabel(it, pattern) })
    }

    /** Chat chart card x labels: `Aug '24`. */
    const val COMPACT = "MMM ''yy"

    /** Index → label formatter for entries whose x is the position in [labels]. */
    @JvmStatic
    fun attachLabels(chart: LineChart?, labels: List<String>) {
        if (chart == null) return
        chart.xAxis.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = Math.round(value)
                return if (idx >= 0 && idx < labels.size) labels[idx] else ""
            }
        })
    }

    private fun reformat(isoDate: String?, pattern: String): String =
        reformat(isoDate, SimpleDateFormat(ISO, Locale.US), SimpleDateFormat(pattern, Locale.US))

    private fun reformat(isoDate: String?, parser: SimpleDateFormat, out: SimpleDateFormat): String {
        if (isoDate == null) return ""
        if (isoDate.length < 10) return isoDate
        return try {
            val d = parser.parse(isoDate.substring(0, 10)) ?: return isoDate
            out.format(d)
        } catch (e: ParseException) {
            isoDate
        }
    }
}
