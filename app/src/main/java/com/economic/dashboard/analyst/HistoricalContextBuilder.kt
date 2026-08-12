package com.economic.dashboard.analyst

import android.content.Context
import android.util.Log

import com.economic.dashboard.api.HistoricalDataRepository
import com.economic.dashboard.database.EconomicHistoryDao
import com.economic.dashboard.database.YieldDatabase
import com.economic.dashboard.models.EconomicHistoryEntry

import java.util.LinkedHashMap
import java.util.Locale

/**
 * Reads cached economic_history rows from Room and formats a compact
 * historical-trend block for injection into the AI Analyst system prompt.
 *
 * Example output (appended after the live snapshot):
 *
 *   HISTORICAL TRENDS — 2-Year Window
 *   ──────────────────────────────────
 *   TREASURY YIELDS (weekly FRED data)
 *     10Y: Range 3.51–5.02%, 2yr avg 4.32%, Latest 4.25% (2025-05-16) ▼ declining
 *     2Y:  Range 3.75–5.27%, 2yr avg 4.48%, Latest 4.10% (2025-05-16) ▼ declining
 *     3M:  Range 4.28–5.54%, 2yr avg 4.92%, Latest 4.30% (2025-05-16) ▼ declining
 *
 *   MORTGAGE RATES (weekly FRED data)
 *     30-Yr Fixed: Range 6.12–7.79%, 2yr avg 6.95%, Latest 6.85% (2025-05-15) ▼ declining
 *
 *   UNEMPLOYMENT RATE (monthly BLS data)
 *     Range 3.4–4.2%, Latest 4.1% (2025-04-01) ↑ rising
 *
 *   GDP GROWTH — QoQ Annualized (BEA quarterly)
 *     Q2'23 2.1% | Q3'23 4.9% | Q4'23 3.3% | Q1'24 1.6% | Q2'24 3.0% | Q3'24 2.8% | Q4'24 2.3% | Q1'25 -0.3%
 *     2yr avg: 2.5%
 *
 * All queries are synchronous — call this method only from a background thread.
 */
object HistoricalContextBuilder {

    private const val TAG = "HistoricalContextBuilder"

    /**
     * Queries Room synchronously and returns the formatted trend block.
     * Returns an empty string if the cache is empty.
     *
     * Must be called from a background thread.
     */
    @JvmStatic
    fun build(context: Context): String {
        try {
            val dao = YieldDatabase.getInstance(context).economicHistoryDao()
            if (dao.getCount() == 0) return ""

            // Each of these five series feeds both its summary line below AND
            // the FULL SERIES DETAIL section — fetch once, format twice
            // (build() runs on every sheet open; this halves its Room queries).
            val tenY = dao.getSeriesSync(HistoricalDataRepository.SERIES_10Y_TREASURY)
            val twoY = dao.getSeriesSync(HistoricalDataRepository.SERIES_2Y_TREASURY)
            val threeM = dao.getSeriesSync(HistoricalDataRepository.SERIES_3M_TREASURY)
            val mortgage = dao.getSeriesSync(HistoricalDataRepository.SERIES_MORTGAGE30)
            val unemployment = dao.getSeriesSync(HistoricalDataRepository.SERIES_UNEMPLOYMENT)

            val sb = StringBuilder()
            sb.append("\n\nHISTORICAL TRENDS — 2-Year Window\n")
            sb.append("──────────────────────────────────\n")

            // ── Treasury yields ───────────────────────────────────────────────
            sb.append("TREASURY YIELDS (weekly FRED data)\n")
            appendYieldLine(sb, tenY, "10Y")
            appendYieldLine(sb, twoY, "2Y ")
            appendYieldLine(sb, threeM, "3M ")

            // ── Mortgage rates ────────────────────────────────────────────────
            sb.append("\nMORTGAGE RATES (weekly FRED data)\n")
            appendYieldLine(sb, mortgage, "30-Yr Fixed")

            // ── Unemployment rate ─────────────────────────────────────────────
            sb.append("\nUNEMPLOYMENT RATE (monthly BLS data)\n")
            appendUnemploymentBlock(sb, unemployment)

            // ── GDP growth ────────────────────────────────────────────────────
            sb.append("\nGDP GROWTH — QoQ Annualized % (BEA quarterly)\n")
            appendGdpBlock(sb, dao)

            // ── Full series detail (monthly resolution) ──────────────────────
            // Gives the AI the actual time series, not just summary stats, so it
            // can answer questions like "what was the 10Y yield last September?"
            sb.append("\nFULL SERIES DETAIL — month-by-month, 24-month window")
            sb.append(" (weekly series show the last reading of each month)\n")
            appendMonthlySeries(sb, tenY, "10Y Treasury %")
            appendMonthlySeries(sb, twoY, "2Y Treasury %")
            appendMonthlySeries(sb, threeM, "3M T-Bill %")
            appendMonthlySeries(sb, mortgage, "30-Yr Mortgage %")
            appendMonthlySeries(sb, unemployment, "Unemployment %")

            return sb.toString()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to build historical context", e)
            return ""
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Section builders
    // ──────────────────────────────────────────────────────────────────────────

    /** Formats one yield/rate line: label, range, avg, latest, trend arrow. */
    private fun appendYieldLine(
        sb: StringBuilder, rows: List<EconomicHistoryEntry>, label: String
    ) {
        if (rows.isEmpty()) {
            sb.append("  ").append(label).append(": Unavailable\n")
            return
        }

        var min = Double.MAX_VALUE
        // -Double.MAX_VALUE, not Double.MIN_VALUE: the latter is the smallest
        // POSITIVE double (~4.9e-324), so seeding a running max with it reports
        // ~0.00 for any all-negative series instead of the real maximum.
        var max = -Double.MAX_VALUE
        var sum = 0.0
        for (e in rows) {
            if (e.value < min) min = e.value
            if (e.value > max) max = e.value
            sum += e.value
        }
        val avg = sum / rows.size
        val latest = rows[rows.size - 1]
        val trend = trendArrow(rows)

        sb.append(String.format(Locale.US,
            "  %s: Range %.2f–%.2f%%, 2yr avg %.2f%%, Latest %.2f%% (%s) %s\n",
            label, min, max, avg, latest.value, latest.date, trend))
    }

    /** Formats the unemployment block (similar to yield line but no %). */
    private fun appendUnemploymentBlock(sb: StringBuilder, rows: List<EconomicHistoryEntry>) {
        if (rows.isEmpty()) {
            sb.append("  Unavailable\n")
            return
        }

        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE // see appendYieldLine — not Double.MIN_VALUE
        for (e in rows) {
            if (e.value < min) min = e.value
            if (e.value > max) max = e.value
        }
        val latest = rows[rows.size - 1]
        val trend = trendArrow(rows)

        sb.append(String.format(Locale.US,
            "  Range %.1f–%.1f%%, Latest %.1f%% (%s) %s\n",
            min, max, latest.value, latest.date, trend))
    }

    /**
     * Formats the GDP block: shows up to the last 8 quarters inline,
     * followed by the 2-year average.
     */
    private fun appendGdpBlock(sb: StringBuilder, dao: EconomicHistoryDao) {
        val rows: List<EconomicHistoryEntry> =
            dao.getSeriesSync(HistoricalDataRepository.SERIES_GDP_GROWTH)
        if (rows.isEmpty()) {
            sb.append("  Unavailable\n")
            return
        }

        // Show at most the last 8 quarters
        val start = Math.max(0, rows.size - 8)
        var sum = 0.0
        var count = 0
        val qSb = StringBuilder("  ")

        for (i in start until rows.size) {
            val e = rows[i]
            val qLabel = dateToQuarterLabel(e.date)
            qSb.append(String.format(Locale.US, "%s %.1f%%", qLabel, e.value))
            if (i < rows.size - 1) qSb.append(" | ")
            sum += e.value
            count++
        }
        sb.append(qSb).append("\n")

        if (count > 0) {
            sb.append(String.format(Locale.US,
                "  2yr avg: %.2f%%\n", sum / count))
        }
    }

    /**
     * Emits one line per series with the last cached value of each month:
     *   "10Y Treasury %: 2024-07 4.25 | 2024-08 3.92 | ... | 2026-06 4.41"
     * Rows arrive sorted ASC by date, so the last value seen for a month wins.
     */
    private fun appendMonthlySeries(
        sb: StringBuilder, rows: List<EconomicHistoryEntry>, label: String
    ) {
        if (rows.isEmpty()) return

        // LinkedHashMap preserves chronological order; later rows overwrite
        // earlier ones within the same month (= last reading of the month).
        val byMonth: MutableMap<String, Double> = LinkedHashMap()
        for (e in rows) {
            if (e.date.length >= 7) {
                byMonth[e.date.substring(0, 7)] = e.value
            }
        }
        if (byMonth.isEmpty()) return

        sb.append(label).append(": ")
        var first = true
        for (m in byMonth.entries) {
            if (!first) sb.append(" | ")
            sb.append(m.key).append(' ')
                .append(String.format(Locale.US, "%.2f", m.value))
            first = false
        }
        sb.append('\n')
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Compares the last 4 data points to the 4 before them and returns a
     * directional label.  Falls back to "→ stable" for very small moves.
     */
    private fun trendArrow(rows: List<EconomicHistoryEntry>): String {
        if (rows.size < 8) {
            // Not enough history — compare first vs last
            val first = rows[0].value
            val last = rows[rows.size - 1].value
            val diff = last - first
            if (Math.abs(diff) < 0.10) return "→ stable"
            return if (diff > 0) "↑ rising" else "▼ declining"
        }

        // Compare avg of last 4 vs avg of preceding 4
        var recentSum = 0.0
        var priorSum = 0.0
        val n = rows.size
        for (i in n - 4 until n) recentSum += rows[i].value
        for (i in n - 8 until n - 4) priorSum += rows[i].value
        val diff = (recentSum - priorSum) / 4.0

        if (Math.abs(diff) < 0.10) return "→ stable"
        return if (diff > 0) "↑ rising" else "▼ declining"
    }

    /**
     * Convert "YYYY-MM-01" → "Q#'YY" label, e.g. "2024-07-01" → "Q3'24".
     * Falls back to the raw date on parse error.
     *
     * Parameter stays nullable to preserve the documented fallback contract,
     * even though the one call site now passes a non-null Kotlin property.
     */
    private fun dateToQuarterLabel(date: String?): String? {
        if (date == null || date.length < 7) return date
        return try {
            val yearStr = date.substring(0, 4)
            val monthStr = date.substring(5, 7)
            val month = monthStr.toInt()
            val quarter = (month - 1) / 3 + 1
            val yy = yearStr.substring(2)
            "Q" + quarter + "'" + yy
        } catch (e: Exception) {
            date
        }
    }
}
