package com.economic.dashboard.utils

import androidx.annotation.StringRes
import com.economic.dashboard.R

/**
 * TICKET-56 part B — how often each charted series prints, and therefore the
 * shortest timeframe window that still shows a shape. GDP is quarterly, so a
 * 3M window is two points joined by one straight segment; six monthly points
 * is the least that reads as a line.
 *
 * Keyed by the timeframe persistence `chartId` (tab chart ids and the
 * `popup_<id>` ids from [com.economic.dashboard.ui.MetricPopupRegistry]),
 * which is the one name every consumer of a window already has:
 * [TimeframeSelector.attach], [SettingsManager.getChartTimeframeMonths] and,
 * through it, `EconomicViewModel.filterByTimeframe`. Unknown ids are treated
 * as daily/weekly and get no floor beyond the 3M segment.
 */
object ChartCadence {

    enum class Cadence(val minMonths: Int, @StringRes val floorReasonRes: Int) {
        DAILY(3, 0),
        MONTHLY(6, R.string.tf_floor_monthly),
        QUARTERLY(24, R.string.tf_floor_quarterly)
    }

    private val QUARTERLY_IDS = setOf("gdp", "popup_gdp")

    private val MONTHLY_IDS = setOf(
        // tabs (one selector drives every chart on the tab)
        "employment", "inflation", "housing",
        // metric sheet
        "popup_cpi", "popup_corepce", "popup_wages",
        "popup_unemployment", "popup_labor",
        "popup_housing", "popup_mortgage"
    )

    @JvmStatic
    fun of(chartId: String): Cadence = when (chartId) {
        in QUARTERLY_IDS -> Cadence.QUARTERLY
        in MONTHLY_IDS -> Cadence.MONTHLY
        else -> Cadence.DAILY
    }

    /** Smallest month window the series should be drawn at. */
    @JvmStatic
    fun minMonths(chartId: String): Int = of(chartId).minMonths
}
