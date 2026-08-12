package com.economic.dashboard.alerts

import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.ui.EconomicViewModel
import com.economic.dashboard.ui.MetricPopupRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the popup → alert contract (review fixes R1–R4):
 *  - every alertSeriesKey the metric popup registry publishes is populated by
 *    AlertValues.build when its series has data (no dead keys — the 10Y bug class);
 *  - Core PCE and wage alerts evaluate the derived quantity the popup displays
 *    (YoY %, real-wage spread), never the raw level (the wrong-units bug class);
 *  - absent/short series omit their key instead of throwing or defaulting to 0.
 */
class AlertValuesTest {

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun row(series: String, date: String, v: Double) =
        EconomicDataPoint("Test", "Test", series, date, v, "%")

    /** 13 monthly rows (exactly enough for one YoY point): base at [start], latest = base*(1+pct/100). */
    private fun yoySeries(series: String, base: Double, pct: Double): List<EconomicDataPoint> {
        val out = ArrayList<EconomicDataPoint>()
        for (m in 0..12) {
            val date = String.format("%04d-%02d-01", 2025 + m / 12, m % 12 + 1)
            // Linear ramp from base to base*(1+pct/100) so only the endpoints matter.
            val v = base + (base * pct / 100.0) * (m / 12.0)
            out.add(row(series, date, v))
        }
        return out
    }

    private fun pair(series: String, latest: Double) = listOf(
        row(series, "2026-06-01", latest - 0.1), row(series, "2026-07-01", latest))

    /** Every input populated richly enough that every key should appear. */
    private fun fullBuild(): Map<String, Double> = AlertValues.build(
        fedFunds = pair("Federal Funds Effective Rate", 4.33),
        gdp = pair("Gross domestic product", 2.4),
        employment = pair("Unemployment Rate", 4.1) + pair("Labor Force Participation Rate", 62.6),
        cpi = yoySeries("CPI-U All Items", 100.0, 3.0),
        vix = pair("VIX Volatility Index", 17.5),
        mbsMortgage = pair("30-Yr Mortgage Rate", 6.8),
        wages = yoySeries("Average Hourly Earnings - Private", 30.0, 2.5),
        pce = yoySeries("Core PCE Price Index", 120.0, 2.8),
        housing = pair("Housing Starts", 1350.0),
        sp500 = pair("S&P 500 Index", 5900.0),
        nasdaq = pair("Nasdaq Composite Index", 19000.0),
        baaSpread = pair("BAA Corporate Spread", 1.9),
        hySpread = pair("High Yield Spread", 3.1),
        treasury = pair("10 Year", 4.6) + pair("3 Month", 5.1),
        spread2y = pair("10Y-2Y Spread", 0.45))

    // ── the dead-key class (R1, R3, R4) ─────────────────────────────────────

    @Test
    fun everyRegistryAlertKeyIsEvaluated() {
        val built = fullBuild()
        for ((id, spec) in MetricPopupRegistry.SPECS) {
            assertTrue(
                "Popup '$id' publishes alertSeriesKey '${spec.alertSeriesKey}' but " +
                    "AlertValues.build never populates it — an alert saved from that " +
                    "popup would never fire. Add the key to AlertValues.build.",
                built.containsKey(spec.alertSeriesKey))
        }
    }

    @Test
    fun treasuryKeyCarriesLatestTenYearValue() { // AE1
        assertEquals(4.6, fullBuild()[EconomicViewModel.CACHE_TREASURY]!!, 1e-9)
    }

    // ── the wrong-units class (R2) ──────────────────────────────────────────

    @Test
    fun corePceEvaluatesYoyPercentNotIndexLevel() { // AE2
        val v = fullBuild()[EconomicViewModel.KEY_PCE]!!
        assertEquals(2.8, v, 1e-6)          // the YoY % the popup plots
        assertTrue(v < 10.0)                // emphatically not the ~123 index level
    }

    @Test
    fun wagesEvaluateRealGrowthSpreadNotNominalDollars() { // AE3
        val v = fullBuild()[EconomicViewModel.KEY_WAGES]!!
        assertEquals(2.5 - 3.0, v, 1e-6)    // wage YoY − CPI YoY, in pp
        assertTrue(v < 1.0)                 // not the ~$30 nominal level
    }

    @Test
    fun cpiEvaluatesYoyPercent() {
        assertEquals(3.0, fullBuild()[EconomicViewModel.CACHE_CPI]!!, 1e-6)
    }

    @Test
    fun spread3mMirrorsPopupFormula() {
        // The "spread" popup computes 10Y-3M from the treasury list by date.
        assertEquals(4.6 - 5.1, fullBuild()[EconomicViewModel.ALERT_SPREAD_3M]!!, 1e-9)
    }

    // ── absent/short series stay absent (Postel: no zeros, no throws) ───────

    @Test
    fun allNullInputsYieldEmptyMap() {
        val built = AlertValues.build(
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null)
        assertTrue(built.isEmpty())
    }

    @Test
    fun shortSeriesOmitYoyKeys() {
        val built = AlertValues.build(
            fedFunds = null, gdp = null, employment = null,
            cpi = yoySeries("CPI-U All Items", 100.0, 3.0).take(12),
            vix = null, mbsMortgage = null,
            wages = yoySeries("Average Hourly Earnings - Private", 30.0, 2.5).take(12),
            pce = yoySeries("Core PCE Price Index", 120.0, 2.8).take(12),
            housing = null, sp500 = null, nasdaq = null,
            baaSpread = null, hySpread = null, treasury = null, spread2y = null)
        assertFalse(built.containsKey(EconomicViewModel.KEY_PCE))
        assertFalse(built.containsKey(EconomicViewModel.KEY_WAGES))
        assertFalse(built.containsKey(EconomicViewModel.CACHE_CPI))
    }
}
