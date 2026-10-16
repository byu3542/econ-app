package com.economic.dashboard.analyst

import com.economic.dashboard.api.ApiConfig
import java.util.LinkedHashMap

/**
 * Single source of truth for every economic series the economii Analyst can
 * reference. Before this existed, the same 17 ids were hand-maintained in
 * three separate places (AnalystToolExecutor.SERIES_IDS/LIVE_SERIES,
 * ChatAdapter.SERIES_LABELS, and a literal id list inside
 * AiAnalystBottomSheet.buildSystemPrompt()) that happened to agree but had
 * nothing enforcing it at compile time (TICKET-F / review F6).
 *
 * As of the 2026-07-30 historical-cache expansion this is also the **fetch
 * catalog**: each Entry carries everything HistoricalDataRepository needs to
 * pull the series and store it in the economic_history Room table, so adding a
 * series is a one-line change here rather than a new hardcoded fetch call.
 *
 * Each id carries two independent labels, not one: `chartLabel` is the
 * UI caption shown on chat sparklines (ChatAdapter), and `toolHint` is
 * the short description fed into the get_series tool schema and echoed back
 * in tool results (AnalystToolExecutor). They read differently by design
 * ("S&P 500" vs. "S&P 500 index") and both are preserved verbatim from
 * pre-refactor source — this is a structural change, not a wording change.
 *
 * `id` is load-bearing and must never change: AnalystToolExecutor's tool enum,
 * ChatAdapter.SERIES_LABELS, the render_chart tool enum, and the seriesId
 * column in economic_history all key off these exact strings. Several series
 * are therefore registered under an app-level alias (CPI, SP500, VIX, WAGES,
 * PCE, CORE_PCE, HOUSING_STARTS, HOME_SALES, BAA_SPREAD, HY_SPREAD) rather
 * than their provider id — the provider id lives in `remoteId`.
 */
object EconomicSeriesRegistry {

    /**
     * Where a series is read from at query time. Every catalogued series is now
     * ROOM_CACHED; LIVE is retained because AnalystToolExecutor still falls back
     * to the ViewModel-backed provider when the Room cache has not been filled
     * yet (first launch), and re-adding a live-only series must stay a one-line
     * change.
     */
    enum class Source { ROOM_CACHED, LIVE }

    /** Which provider the fetch layer talks to for this series. */
    enum class ApiSource { FRED, BLS, BEA, TREASURY }

    /**
     * Refresh cadence. Staleness is evaluated per series against the newest
     * `cachedAtMillis` for that seriesId, so a daily market series can refresh
     * without dragging quarterly GDP along with it.
     */
    enum class Tier(@JvmField val maxAgeMillis: Long) {
        /** Market/rate series that move every session. */
        DAILY(24L * 60 * 60 * 1000),
        /** Monthly prints — CPI, PCE, employment, housing, PMI, mortgage. */
        MONTHLY(7L * 24 * 60 * 60 * 1000),
        /** Quarterly releases — BEA GDP. */
        QUARTERLY(30L * 24 * 60 * 60 * 1000);

        /** Pure, Android-free staleness test. `lastCacheMillis` of 0 means "never cached". */
        fun isStale(lastCacheMillis: Long, nowMillis: Long): Boolean {
            if (lastCacheMillis <= 0) return true
            return (nowMillis - lastCacheMillis) >= maxAgeMillis
        }
    }

    /**
     * Constructor is public rather than package-private (its Java shape):
     * Kotlin does not let an enclosing class reach a nested class's private
     * constructor the way Java does, so the ENTRIES initializer below needs it
     * visible. `internal` is not an option — it name-mangles (migration
     * lesson 5).
     */
    class Entry(
        @JvmField val id: String,
        @JvmField val chartLabel: String,
        @JvmField val toolHint: String,
        @JvmField val source: Source,
        @JvmField val apiSource: ApiSource,
        /** Provider's own series id — always an ApiConfig constant, never a literal. */
        @JvmField val remoteId: String,
        /** Unit label stored on every row: "%", "Index", "$/hr", "Thousands", "$M", … */
        @JvmField val unit: String,
        /** "Treasury", "Employment", "Housing", "GDP", "Inflation", "Markets", "Credit", "Policy". */
        @JvmField val category: String,
        /** FRED aggregation param ("d"/"w"/"m"/"q"), BEA frequency, or null when N/A. */
        @JvmField val frequency: String?,
        /** Record count for providers that take one; empty when the provider uses a date range. */
        @JvmField val limit: String,
        @JvmField val tier: Tier
    )

    private val ENTRIES = arrayOf(
        // ── FRED, daily ───────────────────────────────────────────────────────
        Entry("DGS10", "10-Year Treasury Yield", "10Y Treasury", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_10Y_MATURITY, "%", "Treasury",
            "d", ApiConfig.FRED_CACHE_DAILY_LIMIT, Tier.DAILY),
        Entry("DGS2", "2-Year Treasury Yield", "2Y Treasury", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_2Y_MATURITY, "%", "Treasury",
            "d", ApiConfig.FRED_CACHE_DAILY_LIMIT, Tier.DAILY),
        Entry("DGS3MO", "3-Month T-Bill Yield", "3M T-Bill", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_3M_MATURITY, "%", "Treasury",
            "d", ApiConfig.FRED_CACHE_DAILY_LIMIT, Tier.DAILY),
        Entry("DFF", "Fed Funds Rate (Effective)", "effective fed funds rate", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_FED_FUNDS, "%", "Policy",
            "d", ApiConfig.FRED_CACHE_DAILY_LIMIT, Tier.DAILY),
        Entry("SP500", "S&P 500", "S&P 500 index", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_SP500, "Index", "Markets",
            "d", ApiConfig.FRED_CACHE_DAILY_LIMIT, Tier.DAILY),
        Entry("NASDAQ", "Nasdaq Composite", "Nasdaq Composite index", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_NASDAQ, "Index", "Markets",
            "d", ApiConfig.FRED_CACHE_DAILY_LIMIT, Tier.DAILY),
        Entry("WILL5000INDX", "Wilshire 5000 Index", "Wilshire 5000 total market index", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_WILSHIRE5000, "Index", "Markets",
            "d", ApiConfig.FRED_CACHE_DAILY_LIMIT, Tier.DAILY),
        Entry("VIX", "VIX Volatility Index", "VIX volatility index", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_VIX, "Index", "Markets",
            "d", ApiConfig.FRED_CACHE_DAILY_LIMIT, Tier.DAILY),
        Entry("BAA_SPREAD", "BAA Corporate Spread", "BAA Corporate Bond Spread", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_BAA_SPREAD, "%", "Credit",
            "d", ApiConfig.FRED_CACHE_DAILY_LIMIT, Tier.DAILY),
        Entry("HY_SPREAD", "High Yield Spread", "High Yield Bond Spread", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_HY_SPREAD, "%", "Credit",
            "d", ApiConfig.FRED_CACHE_DAILY_LIMIT, Tier.DAILY),

        // ── FRED, weekly ──────────────────────────────────────────────────────
        Entry("MORTGAGE30US", "30-Year Mortgage Rate", "30Y mortgage rate", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_MORTGAGE30, "%", "Housing",
            "w", ApiConfig.FRED_MBS_LIMIT, Tier.MONTHLY),
        // Millions of USD, per the FRED series definitions — NOT percentages.
        Entry("TMBACBW027SBOG", "Bank MBS Holdings", "MBS held by commercial banks", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_BANK_MBS, "\$M", "Credit",
            "w", ApiConfig.FRED_MBS_LIMIT, Tier.MONTHLY),
        Entry("WSHOMCB", "Fed MBS Holdings", "MBS held by the Federal Reserve", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_FED_MBS, "\$M", "Credit",
            "w", ApiConfig.FRED_MBS_LIMIT, Tier.MONTHLY),

        // ── FRED, monthly ─────────────────────────────────────────────────────
        Entry("PCE", "PCE Price Index", "PCE Price Index", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_PCE, "Index", "Inflation",
            "m", ApiConfig.FRED_PCE_LIMIT, Tier.MONTHLY),
        Entry("CORE_PCE", "Core PCE Price Index", "Core PCE Price Index", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_CORE_PCE, "Index", "Inflation",
            "m", ApiConfig.FRED_PCE_LIMIT, Tier.MONTHLY),
        Entry("HOUSING_STARTS", "Housing Starts", "Housing Starts", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_HOUSING_STARTS, "Thousands", "Housing",
            "m", ApiConfig.FRED_HOUSING_LIMIT, Tier.MONTHLY),
        // EXHOSLUSM495S is reported as a raw annualized unit count (~4,000,000),
        // not thousands like HOUST — keeping the units distinct matters because
        // the AI quotes them verbatim.
        Entry("HOME_SALES", "Existing Home Sales", "Existing Home Sales", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_EXISTING_HOME_SALES, "Units", "Housing",
            "m", ApiConfig.FRED_HOUSING_LIMIT, Tier.MONTHLY),
        // NAPM is a discontinued FRED series (ISM withdrew redistribution rights;
        // observations end around 2016). Catalogued because ApiConfig still
        // declares it, but expect an empty/stale fetch and a "0 valid
        // observations" warning rather than current PMI prints.
        Entry("NAPM", "ISM Manufacturing PMI", "ISM manufacturing PMI", Source.ROOM_CACHED,
            ApiSource.FRED, ApiConfig.FRED_ISM_PMI, "Index", "GDP",
            "m", ApiConfig.FRED_CACHE_MONTHLY_LIMIT, Tier.MONTHLY),

        // ── BLS, monthly (fetched in ONE batched POST — see HistoricalDataRepository) ──
        Entry("LNS14000000", "Unemployment Rate", "unemployment rate", Source.ROOM_CACHED,
            ApiSource.BLS, ApiConfig.BLS_UNEMPLOYMENT_RATE, "%", "Employment",
            null, "", Tier.MONTHLY),
        Entry("LNS12000000", "Employment Level", "employment level (household survey)", Source.ROOM_CACHED,
            ApiSource.BLS, ApiConfig.BLS_EMPLOYMENT_LEVEL, "Thousands", "Employment",
            null, "", Tier.MONTHLY),
        Entry("LNS11300000", "Labor Force Participation", "labor force participation rate", Source.ROOM_CACHED,
            ApiSource.BLS, ApiConfig.BLS_LABOR_PARTICIPATION, "%", "Employment",
            null, "", Tier.MONTHLY),
        Entry("CPI", "CPI-U Index (All Items)", "CPI-U All Items index", Source.ROOM_CACHED,
            ApiSource.BLS, ApiConfig.BLS_CPI_U, "Index", "Inflation",
            null, "", Tier.MONTHLY),
        // ApiConfig calls CUSR0000SA0 "BLS_CPI_W" and EconomicRepository labels it
        // "CPI-W All Items", but the id is seasonally-adjusted CPI-**U** (true
        // CPI-W is CWUR0000SA0). Labelled accurately here rather than propagating
        // the misnomer into AI output; the dashboard's own label is untouched.
        Entry("CUSR0000SA0", "CPI-U Index (Seasonally Adj.)", "CPI-U All Items, seasonally adjusted", Source.ROOM_CACHED,
            ApiSource.BLS, ApiConfig.BLS_CPI_W, "Index", "Inflation",
            null, "", Tier.MONTHLY),
        Entry("WAGES", "Avg Hourly Earnings", "Average Hourly Earnings (private)", Source.ROOM_CACHED,
            ApiSource.BLS, ApiConfig.BLS_HOURLY_EARNINGS, "\$/hr", "Employment",
            null, "", Tier.MONTHLY),
        Entry("CES0500000011", "Avg Weekly Earnings", "Average Weekly Earnings (private)", Source.ROOM_CACHED,
            ApiSource.BLS, ApiConfig.BLS_WEEKLY_EARNINGS, "\$/wk", "Employment",
            null, "", Tier.MONTHLY),

        // ── BEA, quarterly ────────────────────────────────────────────────────
        Entry("GDP_BEA_T10101", "GDP Growth (QoQ annualized)", "GDP growth QoQ annualized", Source.ROOM_CACHED,
            ApiSource.BEA, ApiConfig.BEA_TABLE, "%", "GDP",
            ApiConfig.BEA_FREQUENCY, "", Tier.QUARTERLY),

        // ── Treasury.gov daily yield curve ────────────────────────────────────
        // INTENTIONAL DUPLICATION: these eight maturities are already persisted
        // in the treasury_yields table. They are mirrored into economic_history
        // so the Analyst can query every series through one uniform path
        // (get_series / render_chart), instead of the curve being the one dataset
        // it cannot reach. There is NO extra network call — TreasuryYieldRepository
        // writes both tables from the single XML response it already fetches, so
        // HistoricalDataRepository deliberately skips ApiSource.TREASURY entries.
        // Note UST_10Y and DGS10 are the same economic quantity from different
        // publishers; the toolHints say so, so the model can tell them apart.
        Entry("UST_1M", "1-Month Treasury Yield", "1M Treasury (Treasury.gov curve)", Source.ROOM_CACHED,
            ApiSource.TREASURY, ApiConfig.TREASURY_FIELDS[0], "%", "Treasury", "d", "", Tier.DAILY),
        Entry("UST_3M", "3-Month Treasury Yield", "3M Treasury (Treasury.gov curve)", Source.ROOM_CACHED,
            ApiSource.TREASURY, ApiConfig.TREASURY_FIELDS[1], "%", "Treasury", "d", "", Tier.DAILY),
        Entry("UST_6M", "6-Month Treasury Yield", "6M Treasury (Treasury.gov curve)", Source.ROOM_CACHED,
            ApiSource.TREASURY, ApiConfig.TREASURY_FIELDS[2], "%", "Treasury", "d", "", Tier.DAILY),
        Entry("UST_1Y", "1-Year Treasury Yield", "1Y Treasury (Treasury.gov curve)", Source.ROOM_CACHED,
            ApiSource.TREASURY, ApiConfig.TREASURY_FIELDS[3], "%", "Treasury", "d", "", Tier.DAILY),
        Entry("UST_2Y", "2-Year Treasury Yield", "2Y Treasury (Treasury.gov curve)", Source.ROOM_CACHED,
            ApiSource.TREASURY, ApiConfig.TREASURY_FIELDS[4], "%", "Treasury", "d", "", Tier.DAILY),
        Entry("UST_5Y", "5-Year Treasury Yield", "5Y Treasury (Treasury.gov curve)", Source.ROOM_CACHED,
            ApiSource.TREASURY, ApiConfig.TREASURY_FIELDS[5], "%", "Treasury", "d", "", Tier.DAILY),
        Entry("UST_10Y", "10-Year Treasury Yield (curve)", "10Y Treasury (Treasury.gov curve)", Source.ROOM_CACHED,
            ApiSource.TREASURY, ApiConfig.TREASURY_FIELDS[6], "%", "Treasury", "d", "", Tier.DAILY),
        Entry("UST_30Y", "30-Year Treasury Yield", "30Y Treasury (Treasury.gov curve)", Source.ROOM_CACHED,
            ApiSource.TREASURY, ApiConfig.TREASURY_FIELDS[7], "%", "Treasury", "d", "", Tier.DAILY)
    )

    /** All entries, in declaration order (FRED, BLS, BEA, then Treasury). */
    @JvmStatic
    fun entries(): Array<Entry> = ENTRIES

    /** Every id, in registry order — feeds the INLINE CHARTS sentence. */
    @JvmStatic
    fun allIds(): List<String> {
        val ids = ArrayList<String>()
        for (e in ENTRIES) ids.add(e.id)
        return ids
    }

    /** Room-cached ids only — feeds AnalystToolExecutor.SERIES_IDS. */
    @JvmStatic
    fun roomCachedIds(): Array<String> {
        val ids = ArrayList<String>()
        for (e in ENTRIES) if (e.source == Source.ROOM_CACHED) ids.add(e.id)
        return ids.toTypedArray()
    }

    /** id -> toolHint for LIVE entries only — feeds AnalystToolExecutor.LIVE_SERIES. */
    @JvmStatic
    fun liveToolHints(): Map<String, String> {
        val m: MutableMap<String, String> = LinkedHashMap()
        for (e in ENTRIES) if (e.source == Source.LIVE) m[e.id] = e.toolHint
        return m
    }

    /** id -> chartLabel for every id — feeds ChatAdapter.SERIES_LABELS. */
    @JvmStatic
    fun chartLabels(): Map<String, String> {
        val m: MutableMap<String, String> = LinkedHashMap()
        for (e in ENTRIES) m[e.id] = e.chartLabel
        return m
    }

    /** Lookup by registry id, or null if unknown. */
    @JvmStatic
    fun byId(id: String): Entry? {
        for (e in ENTRIES) if (e.id == id) return e
        return null
    }

    /**
     * Entries HistoricalDataRepository fetches over the network. Excludes
     * ApiSource.TREASURY, which TreasuryYieldRepository mirror-writes from the
     * yield-curve XML it already downloads.
     */
    @JvmStatic
    fun fetchableEntries(): List<Entry> {
        val out = ArrayList<Entry>()
        for (e in ENTRIES) if (e.apiSource != ApiSource.TREASURY) out.add(e)
        return out
    }

    /**
     * Maps a Treasury maturity label as it appears in EconomicDataPoint.series
     * ("1 Month", "3 Month", …) to its catalog entry, so TreasuryYieldRepository
     * can mirror parsed curve points into economic_history without a second
     * hardcoded id list.
     */
    @JvmStatic
    fun treasuryEntryForMaturity(maturityLabel: String): Entry? {
        val maturities = ApiConfig.TREASURY_MATURITIES
        for (i in maturities.indices) {
            if (maturities[i] != maturityLabel) continue
            val field = ApiConfig.TREASURY_FIELDS[i]
            for (e in ENTRIES) if (e.apiSource == ApiSource.TREASURY && e.remoteId == field) return e
            return null
        }
        return null
    }
}
