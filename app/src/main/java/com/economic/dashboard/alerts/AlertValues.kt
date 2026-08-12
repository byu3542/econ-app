package com.economic.dashboard.alerts

import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.ui.EconomicViewModel
import com.economic.dashboard.utils.SeriesMath

/**
 * Builds the key → latest-value map that [ThresholdAlertEvaluator] compares
 * user alert rules against. Pure and framework-free so the contract is
 * JVM-testable (AlertValuesTest): every `alertSeriesKey` the metric popup
 * registry publishes must come out of [build], in the same units the popup
 * displays (its `history` lambda) — an alert saved from a popup that never
 * evaluates, or evaluates a different quantity than shown, is worse than no
 * alert. Each entry below names the popup transform it mirrors; change them
 * together or the popup and the alert silently disagree.
 *
 * Inputs are the raw LiveData `.value` lists (nullable, possibly multi-series);
 * absent/short series simply omit their key — [ThresholdAlertEvaluator] treats
 * missing keys as "no data this pass", never as zero.
 */
object AlertValues {

    @JvmStatic
    fun build(
        fedFunds: List<EconomicDataPoint>?,
        gdp: List<EconomicDataPoint>?,
        employment: List<EconomicDataPoint>?,
        cpi: List<EconomicDataPoint>?,
        vix: List<EconomicDataPoint>?,
        mbsMortgage: List<EconomicDataPoint>?,
        wages: List<EconomicDataPoint>?,
        pce: List<EconomicDataPoint>?,
        housing: List<EconomicDataPoint>?,
        sp500: List<EconomicDataPoint>?,
        nasdaq: List<EconomicDataPoint>?,
        baaSpread: List<EconomicDataPoint>?,
        hySpread: List<EconomicDataPoint>?,
        treasury: List<EconomicDataPoint>?,
        spread2y: List<EconomicDataPoint>?
    ): Map<String, Double> {
        val latest = HashMap<String, Double>()
        putIfPresent(latest, EconomicViewModel.CACHE_FED_FUNDS, latestOf(fedFunds, "Federal Funds Effective Rate"))
        putIfPresent(latest, EconomicViewModel.CACHE_GDP, latestOf(gdp, "Gross domestic product"))
        putIfPresent(latest, EconomicViewModel.CACHE_EMPLOYMENT, latestOf(employment, "Unemployment Rate"))
        putIfPresent(latest, EconomicViewModel.KEY_LABOR, latestOf(employment, "Labor Force Participation Rate"))
        // CPI popup plots yoyPercentSeries of CPI-U — evaluate the same YoY %.
        putIfPresent(latest, EconomicViewModel.CACHE_CPI,
            lastValue(SeriesMath.yoyPercentSeries(filter(cpi, "CPI-U All Items"))))
        putIfPresent(latest, EconomicViewModel.CACHE_VIX, latestOf(vix, "VIX Volatility Index"))
        putIfPresent(latest, EconomicViewModel.CACHE_MBS, latestOf(mbsMortgage, "30-Yr Mortgage Rate"))
        // Wages popup plots the REAL WAGE GROWTH spread (wage YoY − CPI YoY, pp),
        // not nominal $/hour — evaluate the same spread (review fix, R2/AE3).
        putIfPresent(latest, EconomicViewModel.KEY_WAGES,
            lastValue(SeriesMath.realWageGrowthSpread(
                filter(wages, "Average Hourly Earnings - Private"),
                filter(cpi, "CPI-U All Items"))))
        // Core PCE popup plots yoyPercentSeries of the Core PCE index, not the
        // raw index level (~120+) — evaluate the same YoY % (review fix, R2/AE2).
        putIfPresent(latest, EconomicViewModel.KEY_PCE,
            lastValue(SeriesMath.yoyPercentSeries(filter(pce, "Core PCE Price Index"))))
        putIfPresent(latest, EconomicViewModel.KEY_HOUSING, latestOf(housing, "Housing Starts"))
        putIfPresent(latest, EconomicViewModel.KEY_SP500, latestOf(sp500, "S&P 500 Index"))
        putIfPresent(latest, EconomicViewModel.KEY_NASDAQ, latestOf(nasdaq, "Nasdaq Composite Index"))
        putIfPresent(latest, EconomicViewModel.KEY_BAA, latestOf(baaSpread, "BAA Corporate Spread"))
        putIfPresent(latest, EconomicViewModel.KEY_HY, latestOf(hySpread, "High Yield Spread"))
        // The "spread" popup computes 10Y-3M from the daily treasury list — same
        // formula here so the alert tracks the number the popup shows.
        putIfPresent(latest, EconomicViewModel.ALERT_SPREAD_3M,
            lastValue(SeriesMath.tenYearMinusThreeMonthSpread(
                filter(treasury, "10 Year"), filter(treasury, "3 Month"))))
        putIfPresent(latest, EconomicViewModel.CACHE_TREASURY, latestOf(treasury, "10 Year"))
        putIfPresent(latest, EconomicViewModel.ALERT_SPREAD_2Y, lastValue(spread2y))
        putIfPresent(latest, EconomicViewModel.ALERT_MORTGAGE_SPREAD,
            lastValue(SeriesMath.mortgageMinusTenYearSpread(
                filter(mbsMortgage, "30-Yr Mortgage Rate"), filter(treasury, "10 Year"))))
        return latest
    }

    private fun filter(rows: List<EconomicDataPoint>?, series: String): List<EconomicDataPoint> =
        EconomicViewModel.filterBySeries(rows, series)

    private fun lastValue(rows: List<EconomicDataPoint>?): Double? =
        if (rows == null || rows.isEmpty()) null else rows[rows.size - 1].value

    private fun latestOf(rows: List<EconomicDataPoint>?, seriesName: String): Double? =
        if (rows == null || rows.isEmpty()) null else lastValue(filter(rows, seriesName))

    private fun putIfPresent(map: MutableMap<String, Double>, key: String, v: Double?) {
        if (v != null) map[key] = v
    }
}
