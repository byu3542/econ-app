package com.economic.dashboard.analyst

import android.content.Context
import com.economic.dashboard.database.YieldDatabase
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.ui.Benchmarks
import com.economic.dashboard.ui.EconomicViewModel
import com.economic.dashboard.ui.MetricPopupRegistry
import com.economic.dashboard.utils.AxisDateFormat
import java.util.Locale

/**
 * 2026-10-12 analyst cards: the one place a chat card (chart or table) and the
 * render_chart / render_table tool_result get their numbers, so the card, the
 * prose and the metric sheet quote the same value.
 *
 * A series with a metric sheet ([MetricPopupRegistry.popupIdForSeries]) draws
 * `spec.history(vm)` and grades with `spec.grade` — the sheet's quantity, not
 * the raw print (CPI and Core PCE are index levels in the registry but YoY %
 * on their sheets; WAGES is $/hr but its sheet is real wage growth).
 *
 * Two exceptions, both decided in the Phase 2 plan:
 * - Treasury sheets read the ~30-date Treasury.gov snapshot, too short for a
 *   chat window, so the line comes from the registry's own Room rows (same %
 *   units) and only the pill comes from `spec.grade`.
 * - GDP's grade is a trailing 4-quarter average; the pill says so.
 *
 * Series with no sheet draw registry values with no pill.
 *
 * Reads only what the app already holds (Room + the ViewModel's LiveData);
 * never fetches. Safe off the main thread (LiveData.getValue is).
 */
object ChatSeriesData {

    class Card(
        @JvmField val seriesId: String,
        @JvmField val label: String,
        /** Oldest-first, windowed to the requested months, ISO dates. */
        @JvmField val points: List<EconomicDataPoint>,
        @JvmField val valueFmt: String,
        /** Units word appended where the formatted value doesn't carry one. */
        @JvmField val unitNote: String,
        @JvmField val grade: Benchmarks.Result?,
        /** Shown after the pill word, e.g. "4Q avg". Null for most series. */
        @JvmField val gradeNote: String?,
        /** Sheet to open, or null when the series has none. */
        @JvmField val popupId: String?,
        @JvmField val months: Int
    ) {
        fun format(v: Double): String = String.format(Locale.US, valueFmt, v)

        fun latestText(): String = points.lastOrNull()?.let { format(it.value) } ?: "—"

        /** "Aug 2024–Aug 2026", from the points actually drawn. */
        fun windowText(): String {
            if (points.isEmpty()) return ""
            val a = AxisDateFormat.axisLabel(points.first().date, "MMM yyyy")
            val b = AxisDateFormat.axisLabel(points.last().date, "MMM yyyy")
            return if (a == b) a else "$a–$b"
        }

        fun pillText(): String? = grade?.let { g -> gradeNote?.let { "${g.word} · $it" } ?: g.word }

        /** One line for a tool_result and a long-press context block:
         *  `Core PCE (YoY) 3.01% YoY · CAUTION · Aug 2024–Aug 2026`. */
        fun summaryLine(): String {
            val sb = StringBuilder(label).append(' ').append(latestText())
            when {
                unitNote.startsWith("% ") -> sb.append(unitNote.substring(1))
                unitNote.isNotEmpty() && unitNote != "%" -> sb.append(' ').append(unitNote)
            }
            pillText()?.let { sb.append(" · ").append(it) }
            val w = windowText()
            if (w.isNotEmpty()) sb.append(" · ").append(w)
            return sb.toString()
        }
    }

    /** Sheets whose own history is too short for a chat window (see above). */
    private val SNAPSHOT_SHEETS = setOf("yield10y", "yield2y", "yield3m", "yield1m", "yield30y")

    /** Card labels where the sheet's quantity differs from the registry label. */
    private val SHEET_LABELS = mapOf(
        "cpi" to "CPI inflation (YoY)",
        "corepce" to "Core PCE (YoY)",
        "wages" to "Real wage growth"
    )

    @JvmStatic
    fun resolve(ctx: Context, vm: EconomicViewModel?, seriesId: String, months: Int): Card {
        val entry = EconomicSeriesRegistry.byId(seriesId)
        val registryLabel = entry?.chartLabel ?: seriesId
        val popupId = MetricPopupRegistry.popupIdForSeries(seriesId)
        val spec = popupId?.let { MetricPopupRegistry.byId(it) }

        if (spec != null && vm != null) {
            if (popupId in SNAPSHOT_SHEETS) {
                val pts = window(registryPoints(ctx, seriesId), months)
                return Card(seriesId, registryLabel, pts, spec.valueFmt, "%",
                    if (pts.isEmpty()) null else spec.grade(pts), null, popupId, months)
            }
            val full = spec.history(vm).map { it.copy(date = iso(it.date)) }
            if (full.size >= 2) {
                val unit = when {
                    spec.quantityNote == "year-over-year change" -> "% YoY"
                    spec.valueFmt.contains("%%") -> "%"
                    else -> entry?.unit ?: ""
                }
                return Card(seriesId, SHEET_LABELS[popupId] ?: registryLabel,
                    window(full, months), spec.valueFmt, unit, spec.grade(full),
                    if (popupId == "gdp") "4Q avg" else null, popupId, months)
            }
        }
        // No sheet, or the sheet's data isn't loaded yet: the raw print with
        // its registry label and no pill — never an index under a YoY label.
        val unit = entry?.unit ?: ""
        val fmt = if (unit == "%") "%.2f%%" else "%,.2f"
        return Card(seriesId, registryLabel, window(registryPoints(ctx, seriesId), months),
            fmt, unit, null, null, popupId, months)
    }

    private fun window(rows: List<EconomicDataPoint>, months: Int): List<EconomicDataPoint> =
        EconomicViewModel.filterByMonths(rows, months)

    /** Room rows first, the ViewModel's in-memory points when Room is cold —
     *  the same two sources, same order, get_series reads (AI Law 13). */
    private fun registryPoints(ctx: Context, seriesId: String): List<EconomicDataPoint> {
        val rows = YieldDatabase.getInstance(ctx.applicationContext)
            .economicHistoryDao().getSeriesSync(seriesId)
        val pts = if (rows.size >= 2)
            rows.map { EconomicDataPoint("", "", seriesId, it.date, it.value, "") }
        else
            AnalystToolExecutor.getLiveSeriesPoints(seriesId) ?: emptyList()
        return pts.map { it.copy(date = iso(it.date)) }.sortedBy { it.date }
    }

    /** "yyyy-MM" → "yyyy-MM-01" so window math and axis labels parse it. */
    @JvmStatic
    fun iso(d: String): String = if (d.length == 7) "$d-01" else d
}
