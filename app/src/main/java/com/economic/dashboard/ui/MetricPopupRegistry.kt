package com.economic.dashboard.ui

import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.ui.BenchmarkStyle.Tier
import com.economic.dashboard.utils.SeriesMath

/**
 * One [MetricPopupSpec] per catalog indicator id — everything
 * [MetricPopupController] needs to render that indicator's popup (mockup 1c)
 * generically: no hand-typed dialog XML, no per-indicator Kotlin binding code.
 *
 * For catalog-backed indicators `title` delegates to [DashboardIndicatorCatalog.label]
 * so a popup's title can never drift from its Overview slot label; leaf-screen-only
 * specs (`hy`, `baa`, `labor`, the `yield1m`…`yield30y` tenors) carry a literal
 * title because no Overview slot exists to drift from. `grade` always calls an existing
 * [Benchmarks] function with its already-established cutoffs (TICKET-03) —
 * never a new cutoff invented here.
 */
object MetricPopupRegistry {

    class BenchmarkBand(
        @JvmField val label: String,
        @JvmField val rangeText: String,
        @JvmField val tier: Tier
    )

    class MetricPopupSpec(
        @JvmField val id: String,
        @JvmField val title: String,
        /** The "＋ Add alert" row's target series key (EconomicViewModel CACHE_/KEY_ constant). */
        @JvmField val alertSeriesKey: String,
        @JvmField val valueFmt: String,
        @JvmField val deltaFmt: String,
        @JvmField val goodWhenDown: Boolean,
        /** Timeframe-selector persistence key. Deliberately "popup_<id>", never
         *  a tab's own chart id (several tabs reuse one chart id across more
         *  than one chart — reusing it here would silently couple this popup's
         *  timeframe to whatever that tab's chart is set to). */
        @JvmField val chartId: String,
        /** Oldest-first history of exactly the series this popup grades/plots. */
        @JvmField val history: (EconomicViewModel) -> List<EconomicDataPoint>,
        @JvmField val grade: (List<EconomicDataPoint>) -> Benchmarks.Result?,
        @JvmField val bands: List<BenchmarkBand>,
        /**
         * P9 TICKET-47: what this popup actually grades, when that differs from
         * the headline print of the underlying series — a year-over-year change,
         * or a spread. Shown in the add-alert dialog's context caption so a
         * threshold is typed against the right quantity. Null where the popup
         * plots the series as published (the majority).
         */
        @JvmField val quantityNote: String? = null
    )

    val SPECS: Map<String, MetricPopupSpec> = mapOf(
        "cpi" to MetricPopupSpec(
            id = "cpi",
            title = DashboardIndicatorCatalog.label("cpi"),
            alertSeriesKey = EconomicViewModel.CACHE_CPI,
            valueFmt = "%.2f%%",
            deltaFmt = "%.2f",
            goodWhenDown = true,
            chartId = "popup_cpi",
            history = { vm ->
                SeriesMath.yoyPercentSeries(
                    EconomicViewModel.filterBySeries(vm.getCpiData().value, "CPI-U All Items"))
            },
            grade = { rows -> rows.lastOrNull()?.let { Benchmarks.cpiYoY(it.value) } },
            bands = listOf(
                BenchmarkBand("DEFLATION RISK", "Below 1.5%", Tier.WATCH),
                BenchmarkBand("HEALTHY", "1.5% – 2.5%", Tier.GOOD),
                BenchmarkBand("CAUTION", "2.5% – 3.5%", Tier.WATCH),
                BenchmarkBand("ELEVATED", "3.5% – 6.0%", Tier.ELEVATED),
                BenchmarkBand("CRITICAL", "6.0%+", Tier.ALERT)
            ),
            quantityNote = "year-over-year change"
        ),
        "housing" to MetricPopupSpec(
            id = "housing",
            title = DashboardIndicatorCatalog.label("housing"),
            alertSeriesKey = EconomicViewModel.KEY_HOUSING,
            valueFmt = "%.0fK",
            deltaFmt = "%.0f",
            goodWhenDown = false,
            chartId = "popup_housing",
            history = { vm ->
                EconomicViewModel.filterBySeries(vm.getHousingData().value, "Housing Starts")
            },
            grade = { rows -> rows.lastOrNull()?.let { Benchmarks.housingStarts(it.value) } },
            bands = listOf(
                BenchmarkBand("WEAK", "Below 900K", Tier.ALERT),
                BenchmarkBand("BELOW AVERAGE", "900K – 1,200K", Tier.ELEVATED),
                BenchmarkBand("HEALTHY", "1,200K or higher", Tier.GOOD)
            )
        ),
        "gdp" to MetricPopupSpec(
            id = "gdp",
            title = DashboardIndicatorCatalog.label("gdp"),
            alertSeriesKey = EconomicViewModel.CACHE_GDP,
            valueFmt = "%.2f%%",
            deltaFmt = "%.2f",
            goodWhenDown = false,
            chartId = "popup_gdp",
            history = { vm ->
                EconomicViewModel.filterBySeries(vm.getGdpData().value, "Gross domestic product")
            },
            // Graded on the trailing 4-quarter average (same input Benchmarks.gdpGrowth
            // always uses — TICKET-03), not the latest raw print.
            grade = { rows ->
                if (rows.isEmpty()) null else Benchmarks.gdpGrowth(SeriesMath.trailingAverage(rows, 4))
            },
            bands = listOf(
                BenchmarkBand("RECESSION", "Below 0%", Tier.ALERT),
                BenchmarkBand("STAGNATION", "0% – 1%", Tier.ELEVATED),
                BenchmarkBand("BELOW POTENTIAL", "1% – 2%", Tier.WATCH),
                BenchmarkBand("HEALTHY", "2% – 4%", Tier.GOOD),
                BenchmarkBand("OVERHEATING", "4%+", Tier.IMBALANCE)
            )
        ),
        "spread" to MetricPopupSpec(
            id = "spread",
            title = DashboardIndicatorCatalog.label("spread"),
            alertSeriesKey = EconomicViewModel.ALERT_SPREAD_3M,
            valueFmt = "%.2f%%",
            deltaFmt = "%.2f",
            goodWhenDown = false,
            chartId = "popup_spread",
            history = { vm ->
                val data = vm.getTreasuryData().value
                SeriesMath.tenYearMinusThreeMonthSpread(
                    EconomicViewModel.filterBySeries(data, "10 Year"),
                    EconomicViewModel.filterBySeries(data, "3 Month"))
            },
            grade = { rows -> rows.lastOrNull()?.let { Benchmarks.spread3m10y(it.value) } },
            bands = listOf(
                BenchmarkBand("STEEP", "3.50% or higher", Tier.IMBALANCE),
                BenchmarkBand("HEALTHY", "1.00% – 3.50%", Tier.GOOD),
                BenchmarkBand("RECOVERING", "0.00% – 1.00%", Tier.WATCH),
                BenchmarkBand("FLATTENING", "-0.50% – 0.00%", Tier.ELEVATED),
                BenchmarkBand("INVERTED", "-1.50% – -0.50%", Tier.ALERT),
                BenchmarkBand("DEEP INVERSION", "Below -1.50%", Tier.ALERT_DEEP)
            ),
            quantityNote = "10Y minus 3M"
        ),
        "unemployment" to MetricPopupSpec(
            id = "unemployment",
            title = DashboardIndicatorCatalog.label("unemployment"),
            alertSeriesKey = EconomicViewModel.CACHE_EMPLOYMENT,
            valueFmt = "%.2f%%",
            deltaFmt = "%.2f",
            goodWhenDown = true,
            chartId = "popup_unemployment",
            history = { vm ->
                EconomicViewModel.filterBySeries(vm.getEmploymentData().value, "Unemployment Rate")
            },
            // Two-input grading (rate + rise from 12mo low — Sahm-rule-style),
            // same inputs Benchmarks.unemployment always uses (TICKET-03).
            grade = { rows ->
                if (rows.isEmpty()) null else Benchmarks.unemployment(
                    rows.last().value, SeriesMath.riseFromTrailingLow(rows, 12))
            },
            bands = listOf(
                BenchmarkBand("HEALTHY", "Rate ≤5.5% and rise <0.3pp from 12-mo low", Tier.GOOD),
                BenchmarkBand("CAUTION", "Rise 0.3–0.5pp from 12-mo low", Tier.WATCH),
                BenchmarkBand("ELEVATED", "Rate above 5.5%", Tier.ELEVATED),
                BenchmarkBand("RECESSION", "Rate above 7.0%, or rise 0.5pp+ from 12-mo low", Tier.ALERT)
            )
        ),
        "mortgage" to MetricPopupSpec(
            id = "mortgage",
            title = DashboardIndicatorCatalog.label("mortgage"),
            alertSeriesKey = EconomicViewModel.CACHE_MBS,
            valueFmt = "%.2f%%",
            deltaFmt = "%.2f",
            goodWhenDown = true,
            chartId = "popup_mortgage",
            history = { vm ->
                EconomicViewModel.filterBySeries(vm.getMbsMortgageData().value, "30-Yr Mortgage Rate")
            },
            grade = { rows -> rows.lastOrNull()?.let { Benchmarks.mortgage30(it.value) } },
            bands = listOf(
                BenchmarkBand("HEALTHY", "Affordability index 1.30 or higher", Tier.GOOD),
                BenchmarkBand("MODERATE", "Affordability index 1.00 to 1.29", Tier.WATCH),
                BenchmarkBand("ELEVATED", "Affordability index 0.80 to 0.99", Tier.ELEVATED),
                BenchmarkBand("CRITICAL", "Affordability index below 0.80", Tier.ALERT)
            )
        ),
        "vix" to MetricPopupSpec(
            id = "vix",
            title = DashboardIndicatorCatalog.label("vix"),
            alertSeriesKey = EconomicViewModel.CACHE_VIX,
            valueFmt = "%.1f pt",
            deltaFmt = "%.1f",
            goodWhenDown = true,
            chartId = "popup_vix",
            history = { vm ->
                EconomicViewModel.filterBySeries(vm.getVixData().value, "VIX Volatility Index")
            },
            grade = { rows -> rows.lastOrNull()?.let { Benchmarks.vix(it.value) } },
            bands = listOf(
                BenchmarkBand("HEALTHY", "Below 20 points", Tier.GOOD),
                BenchmarkBand("ELEVATED", "20 – 30 points", Tier.ELEVATED),
                BenchmarkBand("CRITICAL", "30 points or higher", Tier.ALERT)
            )
        ),
        "fedfunds" to MetricPopupSpec(
            id = "fedfunds",
            title = DashboardIndicatorCatalog.label("fedfunds"),
            alertSeriesKey = EconomicViewModel.CACHE_FED_FUNDS,
            valueFmt = "%.2f%%",
            deltaFmt = "%.2f",
            goodWhenDown = true,
            chartId = "popup_fedfunds",
            history = { vm ->
                EconomicViewModel.filterBySeries(vm.getFedFundsData().value, "Federal Funds Effective Rate")
            },
            // No existing in-app precedent for the effective federal funds rate (unlike the
            // other 12 indicators, which all reuse an already-established Benchmarks call).
            // Historical-context bands: <2.0% accommodative, 2.0-4.0% normalizing,
            // 4.0-5.5% restrictive, 5.5%+ highly restrictive.
            grade = { rows -> rows.lastOrNull()?.let { Benchmarks.rateBadge(it.value, 2.0, 4.0, 5.5) } },
            bands = listOf(
                BenchmarkBand("HEALTHY", "Below 2.0%", Tier.GOOD),
                BenchmarkBand("MODERATE", "2.0% – 4.0%", Tier.ELEVATED),
                BenchmarkBand("HIGH", "4.0% – 5.5%", Tier.ALERT),
                BenchmarkBand("VERY HIGH", "5.5% or higher", Tier.ALERT_DEEP)
            )
        ),
        "yield10y" to MetricPopupSpec(
            id = "yield10y",
            title = DashboardIndicatorCatalog.label("yield10y"),
            alertSeriesKey = EconomicViewModel.CACHE_TREASURY,
            valueFmt = "%.2f%%",
            deltaFmt = "%.2f",
            goodWhenDown = true,
            chartId = "popup_yield10y",
            history = { vm ->
                EconomicViewModel.filterBySeries(vm.getTreasuryData().value, "10 Year")
            },
            // Same cutoffs HousingFragment's "10Y" swappable badge already uses (TICKET-03).
            grade = { rows -> rows.lastOrNull()?.let { Benchmarks.rateBadge(it.value, 3.0, 4.0, 5.0) } },
            bands = listOf(
                BenchmarkBand("HEALTHY", "Below 3.0%", Tier.GOOD),
                BenchmarkBand("MODERATE", "3.0% – 4.0%", Tier.ELEVATED),
                BenchmarkBand("HIGH", "4.0% – 5.0%", Tier.ALERT),
                BenchmarkBand("VERY HIGH", "5.0% or higher", Tier.ALERT_DEEP)
            )
        ),
        // Yield-row popups (2026-09-03): the Yields table's other four maturities,
        // same generic sheet as yield10y. Titles are literals (like "hy"/"baa") —
        // these are not Overview catalog indicators. Cutoffs are borrowed, never
        // new: bills (1M, 3M) track the policy rate, so they take the fedfunds
        // bands; 2Y and 30Y take the 10Y bands. History depth matches yield10y
        // (the ~30-date Treasury.gov snapshot every treasury spec reads).
        "yield1m" to treasuryTenor("yield1m", "1M TREASURY YIELD", "1 Month",
            EconomicViewModel.ALERT_YIELD_1M, shortEnd = true),
        "yield3m" to treasuryTenor("yield3m", "3M TREASURY YIELD", "3 Month",
            EconomicViewModel.ALERT_YIELD_3M, shortEnd = true),
        "yield2y" to treasuryTenor("yield2y", "2Y TREASURY YIELD", "2 Year",
            EconomicViewModel.ALERT_YIELD_2Y, shortEnd = false),
        "yield30y" to treasuryTenor("yield30y", "30Y TREASURY YIELD", "30 Year",
            EconomicViewModel.ALERT_YIELD_30Y, shortEnd = false),
        "corepce" to MetricPopupSpec(
            id = "corepce",
            title = DashboardIndicatorCatalog.label("corepce"),
            alertSeriesKey = EconomicViewModel.KEY_PCE,
            valueFmt = "%.2f%%",
            deltaFmt = "%.2f",
            goodWhenDown = true,
            chartId = "popup_corepce",
            history = { vm ->
                SeriesMath.yoyPercentSeries(
                    EconomicViewModel.filterBySeries(vm.getPceData().value, "Core PCE Price Index"))
            },
            // pceYoY is an alias of cpiYoY (Benchmarks.kt) — same bands, same wording,
            // so the Fed's preferred gauge and headline CPI read consistently.
            grade = { rows -> rows.lastOrNull()?.let { Benchmarks.pceYoY(it.value) } },
            bands = listOf(
                BenchmarkBand("DEFLATION RISK", "Below 1.5%", Tier.WATCH),
                BenchmarkBand("HEALTHY", "1.5% – 2.5%", Tier.GOOD),
                BenchmarkBand("CAUTION", "2.5% – 3.5%", Tier.WATCH),
                BenchmarkBand("ELEVATED", "3.5% – 6.0%", Tier.ELEVATED),
                BenchmarkBand("CRITICAL", "6.0%+", Tier.ALERT)
            ),
            quantityNote = "year-over-year change"
        ),
        "sp500" to MetricPopupSpec(
            id = "sp500",
            title = DashboardIndicatorCatalog.label("sp500"),
            alertSeriesKey = EconomicViewModel.KEY_SP500,
            valueFmt = "%,.0f",
            deltaFmt = "%,.0f",
            goodWhenDown = false,
            chartId = "popup_sp500",
            history = { vm ->
                EconomicViewModel.filterBySeries(vm.getSp500Data().value, "S&P 500 Index")
            },
            // Same cutoffs StocksFragment's sp500 badge already uses (TICKET-03). Level-based,
            // not drawdown-based, to stay consistent with that existing badge.
            grade = { rows -> rows.lastOrNull()?.let { Benchmarks.stockLevel(it.value, 3500.0, 4000.0) } },
            bands = listOf(
                BenchmarkBand("WEAK", "Below 3,500", Tier.ALERT),
                BenchmarkBand("FAIR", "3,500 – 4,000", Tier.ELEVATED),
                BenchmarkBand("HEALTHY", "4,000 or higher", Tier.GOOD)
            )
        ),
        "nasdaq" to MetricPopupSpec(
            id = "nasdaq",
            title = DashboardIndicatorCatalog.label("nasdaq"),
            alertSeriesKey = EconomicViewModel.KEY_NASDAQ,
            valueFmt = "%,.0f",
            deltaFmt = "%,.0f",
            goodWhenDown = false,
            chartId = "popup_nasdaq",
            history = { vm ->
                EconomicViewModel.filterBySeries(vm.getNasdaqData().value, "Nasdaq Composite Index")
            },
            // Same cutoffs StocksFragment's nasdaq badge already uses (TICKET-03).
            grade = { rows -> rows.lastOrNull()?.let { Benchmarks.stockLevel(it.value, 10000.0, 12000.0) } },
            bands = listOf(
                BenchmarkBand("WEAK", "Below 10,000", Tier.ALERT),
                BenchmarkBand("FAIR", "10,000 – 12,000", Tier.ELEVATED),
                BenchmarkBand("HEALTHY", "12,000 or higher", Tier.GOOD)
            )
        ),
        "wages" to MetricPopupSpec(
            id = "wages",
            // Deliberately NOT DashboardIndicatorCatalog.label("wages") ("AVG HOURLY
            // EARNINGS", the nominal level shown on the Overview slot) — this popup
            // grades and plots the derived REAL WAGE GROWTH spread (wage YoY minus
            // CPI YoY), a different metric under the same catalog id. Matches the
            // title the old dialog_wages_status.xml always used.
            title = "REAL WAGE GROWTH",
            alertSeriesKey = EconomicViewModel.KEY_WAGES,
            valueFmt = "%.2f%%",
            deltaFmt = "%.2f",
            goodWhenDown = false,
            chartId = "popup_wages",
            history = { vm ->
                SeriesMath.realWageGrowthSpread(
                    EconomicViewModel.filterBySeries(vm.getWageData().value, "Average Hourly Earnings - Private"),
                    EconomicViewModel.filterBySeries(vm.getCpiData().value, "CPI-U All Items"))
            },
            grade = { rows -> rows.lastOrNull()?.let { Benchmarks.realWageGrowth(it.value) } },
            bands = listOf(
                BenchmarkBand("CRITICAL", "Below -2.0pp spread", Tier.ALERT),
                BenchmarkBand("LOSING GROUND", "-2.0pp to 0.0pp spread", Tier.ELEVATED),
                BenchmarkBand("BARELY AHEAD", "0.0pp to +1.0pp spread", Tier.WATCH),
                BenchmarkBand("HEALTHY", "+1.0pp or more spread", Tier.GOOD)
            ),
            quantityNote = "wage growth minus CPI"
        ),
        // P6 TICKET-35: the ids below are not DashboardIndicatorCatalog slots —
        // they exist so the leaf screens' secondary cards popup too. Literal titles
        // (wages precedent); every grade call reuses the exact cutoffs its screen
        // badge already applies (Benchmarks — TICKET-03), never a new cutoff.
        "baa" to MetricPopupSpec(
            id = "baa",
            title = "BAA CORPORATE SPREAD",
            alertSeriesKey = EconomicViewModel.KEY_BAA,
            valueFmt = "%.2f%%",
            deltaFmt = "%.2f",
            goodWhenDown = true,
            chartId = "popup_baa",
            history = { vm ->
                EconomicViewModel.filterBySeries(vm.getBaaSpreadData().value, "BAA Corporate Spread")
            },
            // Same cutoffs BondsFragment's BAA badge already uses.
            grade = { rows -> rows.lastOrNull()?.let { Benchmarks.bondSpread(it.value, 2.5, 3.5) } },
            bands = listOf(
                BenchmarkBand("HEALTHY", "Below 2.5%", Tier.GOOD),
                BenchmarkBand("ELEVATED", "2.5% – 3.5%", Tier.ELEVATED),
                BenchmarkBand("DISTRESS", "3.5% or higher", Tier.ALERT)
            )
        ),
        "hy" to MetricPopupSpec(
            id = "hy",
            title = "HIGH YIELD SPREAD",
            alertSeriesKey = EconomicViewModel.KEY_HY,
            valueFmt = "%.2f%%",
            deltaFmt = "%.2f",
            goodWhenDown = true,
            chartId = "popup_hy",
            history = { vm ->
                EconomicViewModel.filterBySeries(vm.getHySpreadData().value, "High Yield Spread")
            },
            // Same cutoffs BondsFragment's HY badge already uses.
            grade = { rows -> rows.lastOrNull()?.let { Benchmarks.bondSpread(it.value, 5.0, 8.0) } },
            bands = listOf(
                BenchmarkBand("HEALTHY", "Below 5.0%", Tier.GOOD),
                BenchmarkBand("ELEVATED", "5.0% – 8.0%", Tier.ELEVATED),
                BenchmarkBand("DISTRESS", "8.0% or higher", Tier.ALERT)
            )
        ),
        "spread2y" to MetricPopupSpec(
            id = "spread2y",
            title = "10Y-2Y SPREAD",
            alertSeriesKey = EconomicViewModel.ALERT_SPREAD_2Y,
            valueFmt = "%.2f%%",
            deltaFmt = "%.2f",
            goodWhenDown = false,
            chartId = "popup_spread2y",
            history = { vm -> vm.getCalculatedSpreadData().value ?: emptyList() },
            // Same grader SpreadsFragment's 10Y-2Y badge already uses.
            grade = { rows -> rows.lastOrNull()?.let { Benchmarks.spread10y2y(it.value) } },
            bands = listOf(
                BenchmarkBand("HEALTHY", "0.50% or higher", Tier.GOOD),
                BenchmarkBand("FLATTENING", "0.00% – 0.50%", Tier.WATCH),
                BenchmarkBand("INVERTING", "-0.50% – 0.00%", Tier.ELEVATED),
                BenchmarkBand("INVERTED", "Below -0.50%", Tier.ALERT)
            ),
            quantityNote = "10Y minus 2Y"
        ),
        "mortgagespread" to MetricPopupSpec(
            id = "mortgagespread",
            title = "MORTGAGE - 10Y SPREAD",
            alertSeriesKey = EconomicViewModel.ALERT_MORTGAGE_SPREAD,
            valueFmt = "%.2f%%",
            deltaFmt = "%.2f",
            goodWhenDown = true,
            chartId = "popup_mortgagespread",
            history = { vm ->
                SeriesMath.mortgageMinusTenYearSpread(
                    EconomicViewModel.filterBySeries(vm.getMbsMortgageData().value, "30-Yr Mortgage Rate"),
                    EconomicViewModel.filterBySeries(vm.getTreasuryData().value, "10 Year"))
            },
            // Same grader HousingFragment's spread badge already uses.
            grade = { rows -> rows.lastOrNull()?.let { Benchmarks.mortgageSpread(it.value) } },
            bands = listOf(
                BenchmarkBand("HEALTHY", "Below 2.2%", Tier.GOOD),
                BenchmarkBand("WIDE", "2.2% – 2.8%", Tier.ELEVATED),
                BenchmarkBand("VERY WIDE", "2.8% or higher", Tier.ALERT)
            ),
            quantityNote = "30-yr mortgage minus 10Y"
        ),
        "labor" to MetricPopupSpec(
            id = "labor",
            title = "LABOR FORCE PARTICIPATION",
            alertSeriesKey = EconomicViewModel.KEY_LABOR,
            valueFmt = "%.1f%%",
            deltaFmt = "%.1f",
            goodWhenDown = false,
            chartId = "popup_labor",
            history = { vm ->
                EconomicViewModel.filterBySeries(vm.getEmploymentData().value, "Labor Force Participation Rate")
            },
            // Same cutoffs EmploymentFragment's labor badge already uses.
            grade = { rows -> rows.lastOrNull()?.let { Benchmarks.laborForce(it.value) } },
            bands = listOf(
                BenchmarkBand("HEALTHY", "63.3% or higher", Tier.GOOD),
                BenchmarkBand("DECLINING", "62.0% – 63.3%", Tier.ELEVATED),
                BenchmarkBand("CRITICAL", "Below 62.0%", Tier.ALERT)
            )
        )
    )

    /**
     * One Treasury maturity, plotted as published from the daily curve snapshot.
     * [shortEnd] picks the borrowed grader: the fedfunds cutoffs (2.0/4.0/5.5)
     * for bills, the 10Y cutoffs (3.0/4.0/5.0) for the long end — see the
     * "fedfunds" and "yield10y" specs above for the precedent of each. A
     * function, not a val: SPECS is initialised first, so a band-list val
     * declared below it would still be null when SPECS reads it.
     */
    private fun treasuryTenor(
        id: String, title: String, series: String, alertKey: String, shortEnd: Boolean
    ): MetricPopupSpec = MetricPopupSpec(
        id = id,
        title = title,
        alertSeriesKey = alertKey,
        valueFmt = "%.2f%%",
        deltaFmt = "%.2f",
        goodWhenDown = true,
        chartId = "popup_$id",
        history = { vm -> EconomicViewModel.filterBySeries(vm.getTreasuryData().value, series) },
        grade = { rows ->
            rows.lastOrNull()?.let {
                if (shortEnd) Benchmarks.rateBadge(it.value, 2.0, 4.0, 5.5)
                else Benchmarks.rateBadge(it.value, 3.0, 4.0, 5.0)
            }
        },
        bands = if (shortEnd) listOf(
            BenchmarkBand("HEALTHY", "Below 2.0%", Tier.GOOD),
            BenchmarkBand("MODERATE", "2.0% – 4.0%", Tier.ELEVATED),
            BenchmarkBand("HIGH", "4.0% – 5.5%", Tier.ALERT),
            BenchmarkBand("VERY HIGH", "5.5% or higher", Tier.ALERT_DEEP)
        ) else listOf(
            BenchmarkBand("HEALTHY", "Below 3.0%", Tier.GOOD),
            BenchmarkBand("MODERATE", "3.0% – 4.0%", Tier.ELEVATED),
            BenchmarkBand("HIGH", "4.0% – 5.0%", Tier.ALERT),
            BenchmarkBand("VERY HIGH", "5.0% or higher", Tier.ALERT_DEEP)
        )
    )

    @JvmStatic
    fun byId(id: String): MetricPopupSpec? = SPECS[id]
}
