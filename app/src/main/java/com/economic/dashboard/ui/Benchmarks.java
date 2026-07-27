package com.economic.dashboard.ui;

import com.economic.dashboard.ui.BenchmarkStyle.Tier;

/**
 * One grading function per metric -- the single source of truth for what word + {@link Tier}
 * a value earns. Both the Overview (Dashboard) and the owning tab call the SAME function, so a
 * badge can never diverge by screen (TICKET-03).
 *
 * <p><b>Controlled vocabulary (TICKET-04).</b> The healthy band is ALWAYS {@code "HEALTHY"} (this
 * kills the ~9 synonyms green used to carry). The recession family is unified to a single
 * {@code "RECESSION"} (was RECESSION SIGNAL / RECESSION TERRITORY / RECESSION SIGNAL (SAHM RULE)).
 * The retired {@code WARNING}/{@code DANGER} spread scale is gone. Every other band carries a
 * single, metric-appropriate word with no cross-metric synonyms. Colours come only from
 * {@link BenchmarkStyle}; this class never touches colour.
 */
public final class Benchmarks {

    private Benchmarks() {}

    /** Grading result: the badge word and its tier. */
    public static final class Result {
        public final String word;
        public final Tier tier;
        public Result(String word, Tier tier) { this.word = word; this.tier = tier; }
    }
    private static Result r(String w, Tier t) { return new Result(w, t); }

    // ── Labor ────────────────────────────────────────────────────────────────

    /** Unemployment rate + rise-from-12mo-low. Sahm-style rise and the >7% level both read
     *  RECESSION so Overview and the Employment tab agree exactly. */
    public static Result unemployment(double rate, double riseFromLow) {
        if (rate > 7.0 || riseFromLow >= 0.5) return r("RECESSION", Tier.ALERT);
        if (rate > 5.5)                        return r("ELEVATED",  Tier.ELEVATED);
        if (riseFromLow >= 0.3)                return r("CAUTION",   Tier.WATCH);
        return r("HEALTHY", Tier.GOOD);
    }

    /** Labor-force participation rate (%). */
    public static Result laborForce(double rate) {
        if (rate >= 63.3) return r("HEALTHY",   Tier.GOOD);
        if (rate >= 62.0) return r("DECLINING", Tier.ELEVATED);
        return r("CRITICAL", Tier.ALERT);
    }

    // ── Inflation ────────────────────────────────────────────────────────────

    /** CPI YoY (%). Deflation risk is a mild concern (WATCH), not a "good" blue tier. */
    public static Result cpiYoY(double yoy) {
        if (yoy < 1.5)  return r("DEFLATION RISK", Tier.WATCH);
        if (yoy <= 2.5) return r("HEALTHY",        Tier.GOOD);
        if (yoy <= 3.5) return r("CAUTION",        Tier.WATCH);
        if (yoy <= 6.0) return r("ELEVATED",       Tier.ELEVATED);
        return r("CRITICAL", Tier.ALERT);
    }

    /** PCE YoY (%). Aligned to the CPI wording + bands so the two inflation gauges match. */
    public static Result pceYoY(double yoy) { return cpiYoY(yoy); }

    /** Real wage growth = wage YoY minus CPI YoY (percentage points). */
    public static Result realWageGrowth(double spread) {
        if (spread < -2.0) return r("CRITICAL",      Tier.ALERT);
        if (spread < 0.0)  return r("LOSING GROUND", Tier.ELEVATED);
        if (spread <= 1.0) return r("BARELY AHEAD",  Tier.WATCH);
        return r("HEALTHY", Tier.GOOD);
    }

    // ── Rates / curve ────────────────────────────────────────────────────────

    /** 3M/10Y treasury spread (pp). Shared by Overview + Spreads. RECOVERING (watch) and
     *  FLATTENING (elevated) are now distinct tiers so no two adjacent bands share a colour
     *  (TICKET-06). Extreme steepness is the sole purple = IMBALANCE case. */
    public static Result spread3m10y(double spread) {
        if (spread >= 3.50) return r("STEEP",          Tier.IMBALANCE);
        if (spread >= 1.00) return r("HEALTHY",        Tier.GOOD);
        if (spread >= 0.00) return r("RECOVERING",     Tier.WATCH);
        if (spread > -0.50) return r("FLATTENING",     Tier.ELEVATED);
        if (spread > -1.50) return r("INVERTED",       Tier.ALERT);
        return r("DEEP INVERSION", Tier.ALERT_DEEP);
    }

    /** 10Y/2Y treasury spread (pp). Its own (narrower) bands; replaces the retired
     *  HEALTHY/CAUTION/WARNING/DANGER scale -- WARNING/DANGER no longer exist anywhere. */
    public static Result spread10y2y(double spread) {
        if (spread >= 0.50) return r("HEALTHY",   Tier.GOOD);
        if (spread >= 0.00) return r("FLATTENING", Tier.WATCH);
        if (spread > -0.50) return r("INVERTING",  Tier.ELEVATED);
        return r("INVERTED", Tier.ALERT);
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
    private static final double MORTGAGE_MEDIAN_INCOME = 83730.0;
    private static final double MORTGAGE_MEDIAN_HOME_PRICE = 440600.0;
    private static final double MORTGAGE_DOWN_PAYMENT = 0.20;
    private static final double MORTGAGE_HOUSING_COST_RATIO = 0.30;
    private static final double MORTGAGE_TAX_INSURANCE_RATE = 0.015;
    private static final int MORTGAGE_TERM_MONTHS = 360;

    /** 30Y mortgage affordability badge for the Overview. */
    public static Result mortgage30(double rate) {
        double index = mortgageAffordabilityIndex(rate, MORTGAGE_MEDIAN_INCOME,
                MORTGAGE_MEDIAN_HOME_PRICE);
        if (index >= 1.30) return r("HEALTHY",  Tier.GOOD);
        if (index >= 1.00) return r("MODERATE", Tier.WATCH);
        if (index >= 0.80) return r("ELEVATED", Tier.ELEVATED);
        return r("CRITICAL", Tier.ALERT);
    }

    /** HUD/NAR-style affordability index: median income divided by the income
     * needed for the reference home at the supplied 30-year fixed rate. */
    static double mortgageAffordabilityIndex(double rate, double medianIncome,
                                             double medianHomePrice) {
        if (rate < 0 || medianIncome <= 0 || medianHomePrice <= 0) return 0.0;

        double monthlyRate = rate / 100.0 / 12.0;
        double loan = medianHomePrice * (1.0 - MORTGAGE_DOWN_PAYMENT);
        double principalAndInterest;
        if (monthlyRate == 0.0) {
            principalAndInterest = loan / MORTGAGE_TERM_MONTHS;
        } else {
            double factor = Math.pow(1.0 + monthlyRate, MORTGAGE_TERM_MONTHS);
            principalAndInterest = loan * (monthlyRate * factor) / (factor - 1.0);
        }

        double taxesAndInsurance = medianHomePrice * MORTGAGE_TAX_INSURANCE_RATE / 12.0;
        double monthlyHousingCost = principalAndInterest + taxesAndInsurance;
        double requiredIncome = monthlyHousingCost * 12.0 / MORTGAGE_HOUSING_COST_RATIO;
        return medianIncome / requiredIncome;
    }

    /** A LOW/MODERATE/HIGH/VERY-HIGH rate badge (Housing mortgage + 10Y badges). */
    public static Result rateBadge(double val, double goodBelow, double moderateBelow, double highBelow) {
        if (val < goodBelow)     return r("HEALTHY",   Tier.GOOD);
        if (val < moderateBelow) return r("MODERATE",  Tier.ELEVATED);
        if (val < highBelow)     return r("HIGH",      Tier.ALERT);
        return r("VERY HIGH", Tier.ALERT_DEEP);
    }

    /** Mortgage-to-10Y spread badge (Housing). Narrow + normal are both fine (GOOD). */
    public static Result mortgageSpread(double val) {
        if (val < 2.2) return r("HEALTHY",   Tier.GOOD);
        if (val < 2.8) return r("WIDE",      Tier.ELEVATED);
        return r("VERY WIDE", Tier.ALERT);
    }

    /** Credit spread (bonds). {@code goodBelow}/{@code elevatedBelow} are the two cutoffs;
     *  tight + normal collapse onto GOOD (blue dropped). */
    public static Result bondSpread(double val, double goodBelow, double elevatedBelow) {
        if (val < goodBelow)     return r("HEALTHY",  Tier.GOOD);
        if (val < elevatedBelow) return r("ELEVATED", Tier.ELEVATED);
        return r("DISTRESS", Tier.ALERT);
    }

    // ── Growth / markets ─────────────────────────────────────────────────────

    /** GDP growth, rolling 4-quarter average (%). Overheating is the purple = IMBALANCE case. */
    public static Result gdpGrowth(double avg) {
        if (avg < 0)   return r("RECESSION",       Tier.ALERT);
        if (avg <= 1.0) return r("STAGNATION",      Tier.ELEVATED);
        if (avg <= 2.0) return r("BELOW POTENTIAL", Tier.WATCH);
        if (avg <= 4.0) return r("HEALTHY",         Tier.GOOD);
        return r("OVERHEATING", Tier.IMBALANCE);
    }

    /** Latest single-quarter GDP print (%). */
    public static Result gdpQuarter(double latest) {
        if (latest < 0)   return r("CONTRACTION", Tier.ALERT);
        if (latest < 2.0) return r("SLOWING",     Tier.WATCH);
        return r("HEALTHY", Tier.GOOD);
    }

    /** Equity index level, graded against two cutoffs. STRONG/VERY-STRONG collapse onto GOOD. */
    public static Result stockLevel(double level, double weakBelow, double fairBelow) {
        if (level < weakBelow) return r("WEAK", Tier.ALERT);
        if (level < fairBelow) return r("FAIR", Tier.ELEVATED);
        return r("HEALTHY", Tier.GOOD);
    }

    /** VIX level. Calm markets (LOW + NORMAL) collapse onto GOOD. */
    public static Result vix(double level) {
        if (level < 20) return r("HEALTHY",  Tier.GOOD);
        if (level < 30) return r("ELEVATED", Tier.ELEVATED);
        return r("CRITICAL", Tier.ALERT);
    }

    /** Housing starts (thousands SAAR). STRONG/VERY-STRONG collapse onto GOOD. */
    public static Result housingStarts(double latest) {
        if (latest < 900)  return r("WEAK",          Tier.ALERT);
        if (latest < 1200) return r("BELOW AVERAGE", Tier.ELEVATED);
        return r("HEALTHY", Tier.GOOD);
    }

    /** Existing home sales (thousands SAAR). STRONG/VERY-STRONG collapse onto GOOD. */
    public static Result existingHomeSales(double latest) {
        if (latest < 3000) return r("WEAK",          Tier.ALERT);
        if (latest < 4000) return r("BELOW AVERAGE", Tier.ELEVATED);
        return r("HEALTHY", Tier.GOOD);
    }
}
