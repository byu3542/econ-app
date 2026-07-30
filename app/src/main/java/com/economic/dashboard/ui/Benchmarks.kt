package com.economic.dashboard.ui

import com.economic.dashboard.ui.BenchmarkStyle.Tier

/**
 * One grading function per metric -- the single source of truth for what word + [Tier]
 * a value earns. Both the Overview (Dashboard) and the owning tab call the SAME function, so a
 * badge can never diverge by screen (TICKET-03).
 *
 * **Controlled vocabulary (TICKET-04).** The healthy band is ALWAYS `"HEALTHY"` (this
 * kills the ~9 synonyms green used to carry). The recession family is unified to a single
 * `"RECESSION"` (was RECESSION SIGNAL / RECESSION TERRITORY / RECESSION SIGNAL (SAHM RULE)).
 * The retired `WARNING`/`DANGER` spread scale is gone. Every other band carries a
 * single, metric-appropriate word with no cross-metric synonyms. Colours come only from
 * [BenchmarkStyle]; this class never touches colour.
 */
object Benchmarks {

    /**
     * Grading result: the badge word and its tier.
     *
     * `@JvmField` on `word` is load-bearing: `BenchmarksTest.java` asserts on
     * `Benchmarks.mortgage30(2.5).word` — a RAW FIELD read from a same-package
     * Java test. A plain Kotlin `val` emits `getWord()` and no field, and the
     * test stops compiling (migration lesson 9).
     *
     * `tier` is read only from Kotlin (`BenchmarkStyle`, `DashboardFragment`),
     * where a plain `val` would work. It carries `@JvmField` for symmetry with
     * `word`, not because anything requires it.
     */
    class Result(@JvmField val word: String, @JvmField val tier: Tier)

    private fun r(w: String, t: Tier): Result = Result(w, t)

    // ── Labor ────────────────────────────────────────────────────────────────

    /** Unemployment rate + rise-from-12mo-low. Sahm-style rise and the >7% level both read
     *  RECESSION so Overview and the Employment tab agree exactly. */
    @JvmStatic
    fun unemployment(rate: Double, riseFromLow: Double): Result {
        if (rate > 7.0 || riseFromLow >= 0.5) return r("RECESSION", Tier.ALERT)
        if (rate > 5.5) return r("ELEVATED", Tier.ELEVATED)
        if (riseFromLow >= 0.3) return r("CAUTION", Tier.WATCH)
        return r("HEALTHY", Tier.GOOD)
    }

    /** Labor-force participation rate (%). */
    @JvmStatic
    fun laborForce(rate: Double): Result {
        if (rate >= 63.3) return r("HEALTHY", Tier.GOOD)
        if (rate >= 62.0) return r("DECLINING", Tier.ELEVATED)
        return r("CRITICAL", Tier.ALERT)
    }

    // ── Inflation ────────────────────────────────────────────────────────────

    /** CPI YoY (%). Deflation risk is a mild concern (WATCH), not a "good" blue tier. */
    @JvmStatic
    fun cpiYoY(yoy: Double): Result {
        if (yoy < 1.5) return r("DEFLATION RISK", Tier.WATCH)
        if (yoy <= 2.5) return r("HEALTHY", Tier.GOOD)
        if (yoy <= 3.5) return r("CAUTION", Tier.WATCH)
        if (yoy <= 6.0) return r("ELEVATED", Tier.ELEVATED)
        return r("CRITICAL", Tier.ALERT)
    }

    /** PCE YoY (%). Aligned to the CPI wording + bands so the two inflation gauges match. */
    @JvmStatic
    fun pceYoY(yoy: Double): Result = cpiYoY(yoy)

    /** Real wage growth = wage YoY minus CPI YoY (percentage points). */
    @JvmStatic
    fun realWageGrowth(spread: Double): Result {
        if (spread < -2.0) return r("CRITICAL", Tier.ALERT)
        if (spread < 0.0) return r("LOSING GROUND", Tier.ELEVATED)
        if (spread <= 1.0) return r("BARELY AHEAD", Tier.WATCH)
        return r("HEALTHY", Tier.GOOD)
    }

    // ── Rates / curve ────────────────────────────────────────────────────────

    /** 3M/10Y treasury spread (pp). Shared by Overview + Spreads. RECOVERING (watch) and
     *  FLATTENING (elevated) are now distinct tiers so no two adjacent bands share a colour
     *  (TICKET-06). Extreme steepness is the sole purple = IMBALANCE case. */
    @JvmStatic
    fun spread3m10y(spread: Double): Result {
        if (spread >= 3.50) return r("STEEP", Tier.IMBALANCE)
        if (spread >= 1.00) return r("HEALTHY", Tier.GOOD)
        if (spread >= 0.00) return r("RECOVERING", Tier.WATCH)
        if (spread > -0.50) return r("FLATTENING", Tier.ELEVATED)
        if (spread > -1.50) return r("INVERTED", Tier.ALERT)
        return r("DEEP INVERSION", Tier.ALERT_DEEP)
    }

    /** 10Y/2Y treasury spread (pp). Its own (narrower) bands; replaces the retired
     *  HEALTHY/CAUTION/WARNING/DANGER scale -- WARNING/DANGER no longer exist anywhere. */
    @JvmStatic
    fun spread10y2y(spread: Double): Result {
        if (spread >= 0.50) return r("HEALTHY", Tier.GOOD)
        if (spread >= 0.00) return r("FLATTENING", Tier.WATCH)
        if (spread > -0.50) return r("INVERTING", Tier.ELEVATED)
        return r("INVERTED", Tier.ALERT)
    }

    /*
     * Mortgage affordability reference case.
     *
     * A rate by itself is not an affordability benchmark: the same rate can be
     * manageable with a low home price and unaffordable with a high one. This
     * reference case follows the structure used by HUD/NAR affordability
     * measures: median income divided by the income required to carry a median-
     * priced home. The inputs are intentionally visible and easy to refresh.
     *
     * Median household income: Census CPS ASEC 2024, $83,730.
     * Median existing-home price: NAR June 2026 snapshot, $440,600.
     * Housing cost cap: 30% of gross income (HUD/Freddie Mac guidance).
     * Down payment: 20%; term: 30 years; taxes + insurance allowance: 1.5%
     * of home value annually. The allowance is a national approximation; local
     * property taxes, insurance, HOA dues, debt, and credit are not modeled.
     */
    private const val MORTGAGE_MEDIAN_INCOME = 83730.0
    private const val MORTGAGE_MEDIAN_HOME_PRICE = 440600.0
    private const val MORTGAGE_DOWN_PAYMENT = 0.20
    private const val MORTGAGE_HOUSING_COST_RATIO = 0.30
    private const val MORTGAGE_TAX_INSURANCE_RATE = 0.015
    private const val MORTGAGE_TERM_MONTHS = 360

    /** 30Y mortgage affordability badge for the Overview. */
    @JvmStatic
    fun mortgage30(rate: Double): Result {
        val index = mortgageAffordabilityIndex(rate, MORTGAGE_MEDIAN_INCOME,
            MORTGAGE_MEDIAN_HOME_PRICE)
        if (index >= 1.30) return r("HEALTHY", Tier.GOOD)
        if (index >= 1.00) return r("MODERATE", Tier.WATCH)
        if (index >= 0.80) return r("ELEVATED", Tier.ELEVATED)
        return r("CRITICAL", Tier.ALERT)
    }

    /**
     * HUD/NAR-style affordability index: median income divided by the income
     * needed for the reference home at the supplied 30-year fixed rate.
     *
     * Was package-private in Java and is called directly by the same-package
     * `BenchmarksTest`. Kotlin has no package-private, and `internal` would
     * name-mangle the JVM signature and break that call — so this is public
     * + `@JvmStatic` (migration lesson 5).
     */
    @JvmStatic
    fun mortgageAffordabilityIndex(
        rate: Double, medianIncome: Double, medianHomePrice: Double
    ): Double {
        if (rate < 0 || medianIncome <= 0 || medianHomePrice <= 0) return 0.0

        val monthlyRate = rate / 100.0 / 12.0
        val loan = medianHomePrice * (1.0 - MORTGAGE_DOWN_PAYMENT)
        val principalAndInterest: Double
        if (monthlyRate == 0.0) {
            principalAndInterest = loan / MORTGAGE_TERM_MONTHS
        } else {
            val factor = Math.pow(1.0 + monthlyRate, MORTGAGE_TERM_MONTHS.toDouble())
            principalAndInterest = loan * (monthlyRate * factor) / (factor - 1.0)
        }

        val taxesAndInsurance = medianHomePrice * MORTGAGE_TAX_INSURANCE_RATE / 12.0
        val monthlyHousingCost = principalAndInterest + taxesAndInsurance
        val requiredIncome = monthlyHousingCost * 12.0 / MORTGAGE_HOUSING_COST_RATIO
        return medianIncome / requiredIncome
    }

    /**
     * A LOW/MODERATE/HIGH/VERY-HIGH rate badge (Housing mortgage + 10Y badges).
     *
     * The first parameter was named `val` in the Java. `val` is a Kotlin hard
     * keyword, so it is renamed to `v` here (same in [mortgageSpread] and
     * [bondSpread]) — a forced rename, not a stylistic one. Every call site in
     * the app passes arguments positionally, so no caller is affected.
     */
    @JvmStatic
    fun rateBadge(v: Double, goodBelow: Double, moderateBelow: Double, highBelow: Double): Result {
        if (v < goodBelow) return r("HEALTHY", Tier.GOOD)
        if (v < moderateBelow) return r("MODERATE", Tier.ELEVATED)
        if (v < highBelow) return r("HIGH", Tier.ALERT)
        return r("VERY HIGH", Tier.ALERT_DEEP)
    }

    /** Mortgage-to-10Y spread badge (Housing). Narrow + normal are both fine (GOOD). */
    @JvmStatic
    fun mortgageSpread(v: Double): Result {
        if (v < 2.2) return r("HEALTHY", Tier.GOOD)
        if (v < 2.8) return r("WIDE", Tier.ELEVATED)
        return r("VERY WIDE", Tier.ALERT)
    }

    /** Credit spread (bonds). `goodBelow`/`elevatedBelow` are the two cutoffs;
     *  tight + normal collapse onto GOOD (blue dropped). */
    @JvmStatic
    fun bondSpread(v: Double, goodBelow: Double, elevatedBelow: Double): Result {
        if (v < goodBelow) return r("HEALTHY", Tier.GOOD)
        if (v < elevatedBelow) return r("ELEVATED", Tier.ELEVATED)
        return r("DISTRESS", Tier.ALERT)
    }

    // ── Growth / markets ─────────────────────────────────────────────────────

    /** GDP growth, rolling 4-quarter average (%). Overheating is the purple = IMBALANCE case. */
    @JvmStatic
    fun gdpGrowth(avg: Double): Result {
        if (avg < 0) return r("RECESSION", Tier.ALERT)
        if (avg <= 1.0) return r("STAGNATION", Tier.ELEVATED)
        if (avg <= 2.0) return r("BELOW POTENTIAL", Tier.WATCH)
        if (avg <= 4.0) return r("HEALTHY", Tier.GOOD)
        return r("OVERHEATING", Tier.IMBALANCE)
    }

    /** Latest single-quarter GDP print (%). */
    @JvmStatic
    fun gdpQuarter(latest: Double): Result {
        if (latest < 0) return r("CONTRACTION", Tier.ALERT)
        if (latest < 2.0) return r("SLOWING", Tier.WATCH)
        return r("HEALTHY", Tier.GOOD)
    }

    /** Equity index level, graded against two cutoffs. STRONG/VERY-STRONG collapse onto GOOD. */
    @JvmStatic
    fun stockLevel(level: Double, weakBelow: Double, fairBelow: Double): Result {
        if (level < weakBelow) return r("WEAK", Tier.ALERT)
        if (level < fairBelow) return r("FAIR", Tier.ELEVATED)
        return r("HEALTHY", Tier.GOOD)
    }

    /** VIX level. Calm markets (LOW + NORMAL) collapse onto GOOD. */
    @JvmStatic
    fun vix(level: Double): Result {
        if (level < 20) return r("HEALTHY", Tier.GOOD)
        if (level < 30) return r("ELEVATED", Tier.ELEVATED)
        return r("CRITICAL", Tier.ALERT)
    }

    /** Housing starts (thousands SAAR). STRONG/VERY-STRONG collapse onto GOOD. */
    @JvmStatic
    fun housingStarts(latest: Double): Result {
        if (latest < 900) return r("WEAK", Tier.ALERT)
        if (latest < 1200) return r("BELOW AVERAGE", Tier.ELEVATED)
        return r("HEALTHY", Tier.GOOD)
    }

    /** Existing home sales (thousands SAAR). STRONG/VERY-STRONG collapse onto GOOD. */
    @JvmStatic
    fun existingHomeSales(latest: Double): Result {
        if (latest < 3000) return r("WEAK", Tier.ALERT)
        if (latest < 4000) return r("BELOW AVERAGE", Tier.ELEVATED)
        return r("HEALTHY", Tier.GOOD)
    }
}
