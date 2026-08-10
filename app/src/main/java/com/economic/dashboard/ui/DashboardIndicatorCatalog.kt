package com.economic.dashboard.ui

import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.utils.SeriesMath

import java.util.Locale

/**
 * Catalog of indicators the Overview's hero + 6 KPI slots can be customized to
 * track (design branch 2a — "Customizable dashboard: pick any indicator for 7
 * slots"). Each entry is a stable [id] (persisted via
 * [com.economic.dashboard.utils.SettingsManager]), a display [label], and the
 * category it's grouped under in the indicator picker.
 *
 * Deliberately scoped to series the app already fetches (see
 * [com.economic.dashboard.ui.EconomicViewModel]) rather than the full design
 * mock's catalog — CLAIMS/PAYROLLS/SENTIMENT aren't wired to a data source
 * today, so WAGES/HOUSING/NASDAQ stand in as the "Inflation & Labor" /
 * "Housing & Markets" picks instead.
 */
object DashboardIndicatorCatalog {

    const val GROUP_GROWTH = "GROWTH & RATES"
    const val GROUP_INFLATION = "INFLATION & LABOR"
    const val GROUP_HOUSING = "HOUSING"
    const val GROUP_MARKETS = "MARKETS"

    val GROUP_ORDER = listOf(GROUP_GROWTH, GROUP_INFLATION, GROUP_HOUSING, GROUP_MARKETS)

    class IndicatorMeta(@JvmField val id: String, @JvmField val label: String, @JvmField val group: String)

    val ALL: List<IndicatorMeta> = listOf(
        IndicatorMeta("fedfunds", "FED FUNDS RATE", GROUP_GROWTH),
        IndicatorMeta("gdp", "GDP GROWTH", GROUP_GROWTH),
        IndicatorMeta("yield10y", "10Y TREASURY YIELD", GROUP_GROWTH),
        IndicatorMeta("spread", "10Y-3M SPREAD", GROUP_GROWTH),
        IndicatorMeta("cpi", "CPI-U YOY", GROUP_INFLATION),
        IndicatorMeta("corepce", "CORE PCE INDEX", GROUP_INFLATION),
        IndicatorMeta("unemployment", "UNEMPLOYMENT", GROUP_INFLATION),
        IndicatorMeta("wages", "AVG HOURLY EARNINGS", GROUP_INFLATION),
        IndicatorMeta("mortgage", "30 YEAR MORTGAGE", GROUP_HOUSING),
        IndicatorMeta("housing", "HOUSING STARTS", GROUP_HOUSING),
        IndicatorMeta("vix", "VIX INDEX", GROUP_MARKETS),
        IndicatorMeta("sp500", "S&P 500", GROUP_MARKETS),
        IndicatorMeta("nasdaq", "NASDAQ COMPOSITE", GROUP_MARKETS)
    )

    @JvmStatic
    fun byId(id: String?): IndicatorMeta? = ALL.firstOrNull { it.id == id }

    @JvmStatic
    fun label(id: String?): String = byId(id)?.label ?: "—"

    /** What a slot/hero card needs to paint itself for one indicator. */
    class SlotDisplay(
        @JvmField val value: String,
        @JvmField val date: String,
        @JvmField val delta: Double?,
        @JvmField val deltaFmt: String,
        @JvmField val goodWhenDown: Boolean,
        @JvmField val spark: List<Float>,
        @JvmField val tier: Benchmarks.Result?
    )

    /** Resolves an indicator id against a live snapshot of every series the ViewModel holds. */
    @JvmStatic
    fun resolve(id: String, vm: EconomicViewModel): SlotDisplay? {
        return when (id) {
            "fedfunds" -> fedFunds(vm)
            "gdp" -> gdp(vm)
            "yield10y" -> yield10y(vm)
            "spread" -> spread(vm)
            "cpi" -> cpiYoy(vm)
            "corepce" -> corePce(vm)
            "unemployment" -> unemployment(vm)
            "wages" -> simple(vm.getWageData().value, "Average Hourly Earnings - Private", "$%.2f", "%.2f", false, 12, null)
            "mortgage" -> mortgage(vm)
            "housing" -> housing(vm)
            "vix" -> vixIndicator(vm)
            "sp500" -> stock(vm.getSp500Data().value, "S&P 500 Index", 30, 3500.0, 4000.0)
            "nasdaq" -> stock(vm.getNasdaqData().value, "Nasdaq Composite Index", 12, 10000.0, 12000.0)
            else -> null
        }
    }

    // ── Per-indicator resolvers ─────────────────────────────────────────────

    private fun fedFunds(vm: EconomicViewModel): SlotDisplay? {
        val rows = EconomicViewModel.filterBySeries(vm.getFedFundsData().value, "Federal Funds Effective Rate")
        if (rows.isEmpty()) return null
        val current = rows[rows.size - 1].value
        val delta = if (rows.size >= 2) current - rows[rows.size - 2].value else null
        return SlotDisplay(
            String.format(Locale.US, "%.2f%%", current), rows[rows.size - 1].date, delta, "%.2f", true,
            tail(rows, 30), Benchmarks.rateBadge(current, 2.0, 4.0, 5.5)
        )
    }

    private fun yield10y(vm: EconomicViewModel): SlotDisplay? {
        val rows = EconomicViewModel.filterBySeries(vm.getTreasuryData().value, "10 Year")
        if (rows.isEmpty()) return null
        val current = rows[rows.size - 1].value
        val delta = if (rows.size >= 2) current - rows[rows.size - 2].value else null
        return SlotDisplay(
            String.format(Locale.US, "%.2f%%", current), rows[rows.size - 1].date, delta, "%.2f", true,
            // Same cutoffs HousingFragment's "10Y" swappable badge already uses (TICKET-03).
            tail(rows, 30), Benchmarks.rateBadge(current, 3.0, 4.0, 5.0)
        )
    }

    private fun corePce(vm: EconomicViewModel): SlotDisplay? {
        val rows = EconomicViewModel.filterBySeries(vm.getPceData().value, "Core PCE Price Index")
        if (rows.size < 13) return null
        val yoyRows = SeriesMath.yoyPercentSeries(rows)
        if (yoyRows.isEmpty()) return null
        val latest = yoyRows[yoyRows.size - 1].value
        val delta = if (yoyRows.size >= 2) latest - yoyRows[yoyRows.size - 2].value else null
        return SlotDisplay(
            String.format(Locale.US, "%.1f%%", latest), yoyRows[yoyRows.size - 1].date, delta, "%.2f", true,
            yoyRows.subList(Math.max(0, yoyRows.size - 12), yoyRows.size).map { it.value.toFloat() },
            Benchmarks.pceYoY(latest)
        )
    }

    private fun gdp(vm: EconomicViewModel): SlotDisplay? {
        val rows = EconomicViewModel.filterBySeries(vm.getGdpData().value, "Gross domestic product")
        if (rows.isEmpty()) return null
        val avg = SeriesMath.trailingAverage(rows, 4)
        val delta = if (rows.size >= 2) rows[rows.size - 1].value - rows[rows.size - 2].value else null
        return SlotDisplay(
            String.format(Locale.US, "%.1f%%", avg), rows[rows.size - 1].date, delta, "%.1f", false,
            tail(rows, 8), Benchmarks.gdpGrowth(avg)
        )
    }

    private fun cpiYoy(vm: EconomicViewModel): SlotDisplay? {
        val rows = EconomicViewModel.filterBySeries(vm.getCpiData().value, "CPI-U All Items")
        if (rows.size < 13) return null
        val yoyRows = SeriesMath.yoyPercentSeries(rows)
        if (yoyRows.isEmpty()) return null
        val latest = yoyRows[yoyRows.size - 1].value
        val delta = if (yoyRows.size >= 2) latest - yoyRows[yoyRows.size - 2].value else null
        return SlotDisplay(
            String.format(Locale.US, "%.1f%%", latest), yoyRows[yoyRows.size - 1].date, delta, "%.2f", true,
            yoyRows.subList(Math.max(0, yoyRows.size - 12), yoyRows.size).map { it.value.toFloat() },
            Benchmarks.cpiYoY(latest)
        )
    }

    private fun spread(vm: EconomicViewModel): SlotDisplay? {
        val data = vm.getTreasuryData().value
        val tens = EconomicViewModel.filterBySeries(data, "10 Year")
        val threes = EconomicViewModel.filterBySeries(data, "3 Month")
        val rows = SeriesMath.tenYearMinusThreeMonthSpread(tens, threes)
        if (rows.isEmpty()) return null
        val latest = rows[rows.size - 1].value
        val delta = if (rows.size >= 2) latest - rows[rows.size - 2].value else null
        val spark = rows.subList(Math.max(0, rows.size - 30), rows.size).map { it.value.toFloat() }
        return SlotDisplay(
            String.format(Locale.US, "%.2f%%", latest), rows[rows.size - 1].date, delta, "%.2f", false,
            spark, Benchmarks.spread3m10y(latest)
        )
    }

    private fun unemployment(vm: EconomicViewModel): SlotDisplay? {
        val rows = EconomicViewModel.filterBySeries(vm.getEmploymentData().value, "Unemployment Rate")
        if (rows.isEmpty()) return null
        val current = rows[rows.size - 1].value
        val riseFromLow = SeriesMath.riseFromTrailingLow(rows, 12)
        val delta = if (rows.size >= 2) current - rows[rows.size - 2].value else null
        return SlotDisplay(
            String.format(Locale.US, "%.1f%%", current), rows[rows.size - 1].date, delta, "%.1f", true,
            tail(rows, 12), Benchmarks.unemployment(current, riseFromLow)
        )
    }

    private fun mortgage(vm: EconomicViewModel): SlotDisplay? {
        val rows = EconomicViewModel.filterBySeries(vm.getMbsMortgageData().value, "30-Yr Mortgage Rate")
        if (rows.isEmpty()) return null
        val current = rows[rows.size - 1].value
        val delta = if (rows.size >= 2) current - rows[rows.size - 2].value else null
        return SlotDisplay(
            String.format(Locale.US, "%.2f%%", current), rows[rows.size - 1].date, delta, "%.2f", true,
            tail(rows, 12), Benchmarks.mortgage30(current)
        )
    }

    private fun housing(vm: EconomicViewModel): SlotDisplay? {
        val rows = EconomicViewModel.filterBySeries(vm.getHousingData().value, "Housing Starts")
        if (rows.isEmpty()) return null
        val current = rows[rows.size - 1].value
        val delta = if (rows.size >= 2) current - rows[rows.size - 2].value else null
        return SlotDisplay(
            String.format(Locale.US, "%.0fK", current), rows[rows.size - 1].date, delta, "%.0f", false,
            tail(rows, 12), Benchmarks.housingStarts(current)
        )
    }

    private fun vixIndicator(vm: EconomicViewModel): SlotDisplay? {
        val rows = EconomicViewModel.filterBySeries(vm.getVixData().value, "VIX Volatility Index")
        if (rows.isEmpty()) return null
        val current = rows[rows.size - 1].value
        val delta = if (rows.size >= 2) current - rows[rows.size - 2].value else null
        return SlotDisplay(
            String.format(Locale.US, "%.1f pt", current), rows[rows.size - 1].date, delta, "%.1f", true,
            tail(rows, 30), Benchmarks.vix(current)
        )
    }

    private fun stock(
        data: List<EconomicDataPoint>?, series: String, sparkN: Int, weakBelow: Double, fairBelow: Double
    ): SlotDisplay? {
        val rows = EconomicViewModel.filterBySeries(data, series)
        if (rows.isEmpty()) return null
        val current = rows[rows.size - 1].value
        val delta = if (rows.size >= 2) current - rows[rows.size - 2].value else null
        return SlotDisplay(
            String.format(Locale.US, "%,.0f", current), rows[rows.size - 1].date, delta, "%,.0f", false,
            // Same cutoffs StocksFragment's sp500/nasdaq badges already use (TICKET-03).
            tail(rows, sparkN), Benchmarks.stockLevel(current, weakBelow, fairBelow)
        )
    }

    private fun simple(
        data: List<EconomicDataPoint>?, series: String, fmt: String, deltaFmt: String,
        goodWhenDown: Boolean, sparkN: Int, tier: Benchmarks.Result?
    ): SlotDisplay? {
        val rows = EconomicViewModel.filterBySeries(data, series)
        if (rows.isEmpty()) return null
        val latest = rows[rows.size - 1]
        val delta = if (rows.size >= 2) latest.value - rows[rows.size - 2].value else null
        return SlotDisplay(
            String.format(Locale.US, fmt, latest.value), latest.date, delta, deltaFmt, goodWhenDown,
            tail(rows, sparkN), tier
        )
    }

    private fun tail(rows: List<EconomicDataPoint>, n: Int): List<Float> {
        val out = ArrayList<Float>()
        for (i in Math.max(0, rows.size - n) until rows.size) out.add(rows[i].value.toFloat())
        return out
    }
}
