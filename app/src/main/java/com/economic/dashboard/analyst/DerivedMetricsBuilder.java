package com.economic.dashboard.analyst;

import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.ui.EconomicViewModel;

import java.util.List;
import java.util.Locale;

/**
 * Computes cross-metric derived readings (spreads, real rates) from the live
 * ViewModel data and formats them as a compact block for the AI Analyst
 * system prompt. Derived numbers unlock better analysis than raw levels —
 * e.g. the real 10Y yield says more about policy tightness than the nominal.
 *
 * Safe to call from the main thread (reads LiveData values only).
 */
public class DerivedMetricsBuilder {

    /** @return multi-line block, or "" if not enough data has loaded yet. */
    public static String build(EconomicViewModel vm) {
        StringBuilder sb = new StringBuilder();

        List<EconomicDataPoint> treasury = vm.getTreasuryData().getValue();
        EconomicDataPoint tenY   = EconomicViewModel.getLatest(treasury, "10 Year");
        EconomicDataPoint twoY   = EconomicViewModel.getLatest(treasury, "2 Year");
        EconomicDataPoint threeM = EconomicViewModel.getLatest(treasury, "3 Month");

        // CPI YoY (for real-rate math)
        Double cpiYoy = null;
        List<EconomicDataPoint> cpiList = vm.getCpiData().getValue();
        List<EconomicDataPoint> cpiRows = cpiList != null
                ? EconomicViewModel.filterBySeries(cpiList, "CPI-U All Items") : null;
        if (cpiRows != null && cpiRows.size() >= 13) {
            double base = cpiRows.get(cpiRows.size() - 13).getValue();
            if (Math.abs(base) > 1e-9)
                cpiYoy = ((cpiRows.get(cpiRows.size() - 1).getValue() - base) / base) * 100.0;
        }

        if (tenY != null && cpiYoy != null)
            sb.append(String.format(Locale.US,
                    "Real 10Y Yield (10Y minus CPI YoY): %+.2f%%\n", tenY.getValue() - cpiYoy));

        EconomicDataPoint fedFunds = EconomicViewModel.getLatest(
                vm.getFedFundsData().getValue(), "Federal Funds Effective Rate");
        if (fedFunds != null && cpiYoy != null)
            sb.append(String.format(Locale.US,
                    "Real Policy Rate (Fed Funds minus CPI YoY): %+.2f%%\n", fedFunds.getValue() - cpiYoy));

        if (twoY != null && threeM != null)
            sb.append(String.format(Locale.US,
                    "2Y-3M Spread (near-term Fed expectations): %+.2f%%\n",
                    twoY.getValue() - threeM.getValue()));

        // Mortgage spread over the 10Y — a stress gauge for housing credit.
        List<EconomicDataPoint> mbsList = vm.getMbsMortgageData().getValue();
        EconomicDataPoint mortgage = mbsList != null
                ? EconomicViewModel.getLatest(mbsList, "30-Yr Mortgage Rate") : null;
        if (mortgage != null && tenY != null)
            sb.append(String.format(Locale.US,
                    "Mortgage Spread (30Y mortgage minus 10Y Treasury): %+.2f%% (historical norm ~1.7%%)\n",
                    mortgage.getValue() - tenY.getValue()));

        // Credit-risk appetite: HY minus BAA.
        List<EconomicDataPoint> baaList = vm.getBaaSpreadData().getValue();
        List<EconomicDataPoint> hyList  = vm.getHySpreadData().getValue();
        EconomicDataPoint baa = baaList != null && !baaList.isEmpty() ? baaList.get(baaList.size()-1) : null;
        EconomicDataPoint hy  = hyList  != null && !hyList.isEmpty()  ? hyList.get(hyList.size()-1)   : null;
        if (baa != null && hy != null)
            sb.append(String.format(Locale.US,
                    "HY-minus-BAA Gap (junk-vs-investment-grade risk premium): %+.2f%%\n",
                    hy.getValue() - baa.getValue()));

        if (sb.length() == 0) return "";
        return "\nDERIVED CROSS-METRIC READINGS (computed from the snapshot above)\n" + sb;
    }
}
