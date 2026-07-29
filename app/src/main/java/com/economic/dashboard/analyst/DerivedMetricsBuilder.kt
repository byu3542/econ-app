package com.economic.dashboard.analyst

import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.ui.EconomicViewModel

import java.util.Locale

/**
 * Computes cross-metric derived readings (spreads, real rates) from the live
 * ViewModel data and formats them as a compact block for the AI Analyst
 * system prompt. Derived numbers unlock better analysis than raw levels —
 * e.g. the real 10Y yield says more about policy tightness than the nominal.
 *
 * Safe to call from the main thread (reads LiveData values only).
 *
 * Note on call style: `EconomicViewModel` and `LiveData` are still Java, so
 * their `getX()` accessors keep the explicit call form here. `.value` on an
 * `EconomicDataPoint` is a Kotlin property (data class) and must use property
 * syntax — the two look alike on the same line and are not interchangeable.
 */
object DerivedMetricsBuilder {

    /** @return multi-line block, or "" if not enough data has loaded yet. */
    @JvmStatic
    fun build(vm: EconomicViewModel): String {
        val sb = StringBuilder()

        val treasury: List<EconomicDataPoint>? = vm.getTreasuryData().getValue()
        val tenY: EconomicDataPoint? = EconomicViewModel.getLatest(treasury, "10 Year")
        val twoY: EconomicDataPoint? = EconomicViewModel.getLatest(treasury, "2 Year")
        val threeM: EconomicDataPoint? = EconomicViewModel.getLatest(treasury, "3 Month")

        // CPI YoY (for real-rate math)
        var cpiYoyTmp: Double? = null
        val cpiList: List<EconomicDataPoint>? = vm.getCpiData().getValue()
        val cpiRows: List<EconomicDataPoint>? =
            if (cpiList != null) EconomicViewModel.filterBySeries(cpiList, "CPI-U All Items") else null
        if (cpiRows != null && cpiRows.size >= 13) {
            val base = cpiRows[cpiRows.size - 13].value
            if (Math.abs(base) > 1e-9)
                cpiYoyTmp = ((cpiRows[cpiRows.size - 1].value - base) / base) * 100.0
        }
        val cpiYoy = cpiYoyTmp

        if (tenY != null && cpiYoy != null)
            sb.append(String.format(Locale.US,
                "Real 10Y Yield (10Y minus CPI YoY): %+.2f%%\n", tenY.value - cpiYoy))

        val fedFunds: EconomicDataPoint? = EconomicViewModel.getLatest(
            vm.getFedFundsData().getValue(), "Federal Funds Effective Rate")
        if (fedFunds != null && cpiYoy != null)
            sb.append(String.format(Locale.US,
                "Real Policy Rate (Fed Funds minus CPI YoY): %+.2f%%\n", fedFunds.value - cpiYoy))

        if (twoY != null && threeM != null)
            sb.append(String.format(Locale.US,
                "2Y-3M Spread (near-term Fed expectations): %+.2f%%\n",
                twoY.value - threeM.value))

        // Mortgage spread over the 10Y — a stress gauge for housing credit.
        val mbsList: List<EconomicDataPoint>? = vm.getMbsMortgageData().getValue()
        val mortgage: EconomicDataPoint? =
            if (mbsList != null) EconomicViewModel.getLatest(mbsList, "30-Yr Mortgage Rate") else null
        if (mortgage != null && tenY != null)
            sb.append(String.format(Locale.US,
                "Mortgage Spread (30Y mortgage minus 10Y Treasury): %+.2f%% (historical norm ~1.7%%)\n",
                mortgage.value - tenY.value))

        // Credit-risk appetite: HY minus BAA.
        val baaList: List<EconomicDataPoint>? = vm.getBaaSpreadData().getValue()
        val hyList: List<EconomicDataPoint>? = vm.getHySpreadData().getValue()
        val baa: EconomicDataPoint? =
            if (baaList != null && !baaList.isEmpty()) baaList[baaList.size - 1] else null
        val hy: EconomicDataPoint? =
            if (hyList != null && !hyList.isEmpty()) hyList[hyList.size - 1] else null
        if (baa != null && hy != null)
            sb.append(String.format(Locale.US,
                "HY-minus-BAA Gap (junk-vs-investment-grade risk premium): %+.2f%%\n",
                hy.value - baa.value))

        if (sb.length == 0) return ""
        return "\nDERIVED CROSS-METRIC READINGS (computed from the snapshot above)\n" + sb
    }
}
