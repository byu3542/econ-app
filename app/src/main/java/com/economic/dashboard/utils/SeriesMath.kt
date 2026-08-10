package com.economic.dashboard.utils

import com.economic.dashboard.models.EconomicDataPoint

/**
 * Shared derived-series math. Extracted so the Overview slot ([com.economic.dashboard.ui.DashboardIndicatorCatalog])
 * and a metric's popup ([com.economic.dashboard.ui.MetricPopupController]) compute a derived
 * series (YoY %, curve spread) exactly once, from exactly one formula — the same principle
 * [com.economic.dashboard.ui.Benchmarks] applies to grading (TICKET-03).
 */
object SeriesMath {

    /**
     * 12-month-lookback YoY % change, point for point, over a date-sorted series.
     * Each output point carries the *current* point's date/source/category, with
     * `value` replaced by the YoY % and `unit` set to "%".
     */
    @JvmStatic
    fun yoyPercentSeries(rows: List<EconomicDataPoint>): List<EconomicDataPoint> {
        val out = ArrayList<EconomicDataPoint>()
        for (i in 12 until rows.size) {
            val base = rows[i - 12].value
            if (Math.abs(base) > 1e-9) {
                val yoy = ((rows[i].value - base) / base) * 100.0
                val p = rows[i]
                out.add(EconomicDataPoint(p.source, p.category, p.series, p.date, yoy, "%"))
            }
        }
        return out
    }

    /**
     * 10Y minus 3M treasury spread, matched by date. `tens`/`threes` need not be
     * pre-aligned — only dates present in both series produce an output point.
     */
    @JvmStatic
    fun tenYearMinusThreeMonthSpread(
        tens: List<EconomicDataPoint>, threes: List<EconomicDataPoint>
    ): List<EconomicDataPoint> {
        val threeByDate = HashMap<String, Double>()
        for (p in threes) threeByDate[p.date] = p.value
        val out = ArrayList<EconomicDataPoint>()
        for (p in tens) {
            val s = threeByDate[p.date]
            if (s != null) out.add(EconomicDataPoint(p.source, p.category, "10Y-3M Spread", p.date, p.value - s, "%"))
        }
        return out
    }

    /** Average of the trailing [n] rows (or fewer, if the series is shorter). 0.0 for an empty list. */
    @JvmStatic
    fun trailingAverage(rows: List<EconomicDataPoint>, n: Int): Double {
        if (rows.isEmpty()) return 0.0
        var sum = 0.0
        var count = 0
        for (i in Math.max(0, rows.size - n) until rows.size) { sum += rows[i].value; count++ }
        return if (count > 0) sum / count else 0.0
    }

    /** Current value minus the lowest value in the trailing [n] rows (Sahm-rule-style "rise from low"). */
    @JvmStatic
    fun riseFromTrailingLow(rows: List<EconomicDataPoint>, n: Int): Double {
        if (rows.isEmpty()) return 0.0
        var low = Double.MAX_VALUE
        for (i in Math.max(0, rows.size - n) until rows.size) low = Math.min(low, rows[i].value)
        return rows[rows.size - 1].value - low
    }

    /**
     * Real wage growth spread, point for point: wage YoY % minus CPI YoY %, matched by
     * date. Both [wageRows] and [cpiRows] are raw (non-YoY) date-sorted series — this
     * runs [yoyPercentSeries] on each internally.
     */
    @JvmStatic
    fun realWageGrowthSpread(
        wageRows: List<EconomicDataPoint>, cpiRows: List<EconomicDataPoint>
    ): List<EconomicDataPoint> {
        val wageYoy = yoyPercentSeries(wageRows)
        val cpiYoy = yoyPercentSeries(cpiRows)
        val cpiByDate = HashMap<String, Double>()
        for (p in cpiYoy) cpiByDate[p.date] = p.value
        val out = ArrayList<EconomicDataPoint>()
        for (p in wageYoy) {
            val c = cpiByDate[p.date]
            if (c != null) out.add(EconomicDataPoint(p.source, p.category, "Real Wage Growth", p.date, p.value - c, "pp"))
        }
        return out
    }
}
