package com.economic.dashboard.analyst

import java.util.LinkedHashMap

/**
 * Single source of truth for every economic series the Econ Analyst can
 * reference. Before this existed, the same 17 ids were hand-maintained in
 * three separate places (AnalystToolExecutor.SERIES_IDS/LIVE_SERIES,
 * ChatAdapter.SERIES_LABELS, and a literal id list inside
 * AiAnalystBottomSheet.buildSystemPrompt()) that happened to agree but had
 * nothing enforcing it at compile time (TICKET-F / review F6).
 *
 * Each id carries two independent labels, not one: `chartLabel` is the
 * UI caption shown on chat sparklines (ChatAdapter), and `toolHint` is
 * the short description fed into the get_series tool schema and echoed back
 * in tool results (AnalystToolExecutor). They read differently by design
 * ("S&P 500" vs. "S&P 500 index") and both are preserved verbatim from
 * pre-refactor source — this is a structural change, not a wording change.
 */
object EconomicSeriesRegistry {

    enum class Source { ROOM_CACHED, LIVE }

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
        @JvmField val source: Source
    )

    private val ENTRIES = arrayOf(
        // Room-cached (matches HistoricalDataRepository).
        Entry("DGS10",          "10-Year Treasury Yield",       "10Y Treasury",                      Source.ROOM_CACHED),
        Entry("DGS2",           "2-Year Treasury Yield",        "2Y Treasury",                        Source.ROOM_CACHED),
        Entry("DGS3MO",         "3-Month T-Bill Yield",         "3M T-Bill",                          Source.ROOM_CACHED),
        Entry("MORTGAGE30US",   "30-Year Mortgage Rate",        "30Y mortgage rate",                  Source.ROOM_CACHED),
        Entry("LNS14000000",    "Unemployment Rate",            "unemployment rate",                  Source.ROOM_CACHED),
        Entry("GDP_BEA_T10101", "GDP Growth (QoQ annualized)",  "GDP growth QoQ annualized",          Source.ROOM_CACHED),

        // Live ViewModel-backed (AI Law 3: matches every long-press trigger surface).
        Entry("CPI",            "CPI-U Index (All Items)", "CPI-U All Items index",             Source.LIVE),
        Entry("PCE",            "PCE Price Index",         "PCE Price Index",                    Source.LIVE),
        Entry("CORE_PCE",       "Core PCE Price Index",    "Core PCE Price Index",               Source.LIVE),
        Entry("SP500",          "S&P 500",                 "S&P 500 index",                      Source.LIVE),
        Entry("NASDAQ",         "Nasdaq Composite",        "Nasdaq Composite index",             Source.LIVE),
        Entry("VIX",            "VIX Volatility Index",    "VIX volatility index",               Source.LIVE),
        Entry("WAGES",          "Avg Hourly Earnings",     "Average Hourly Earnings (private)",  Source.LIVE),
        Entry("HOUSING_STARTS", "Housing Starts",          "Housing Starts",                     Source.LIVE),
        Entry("HOME_SALES",     "Existing Home Sales",     "Existing Home Sales",                Source.LIVE),
        Entry("BAA_SPREAD",     "BAA Corporate Spread",    "BAA Corporate Bond Spread",          Source.LIVE),
        Entry("HY_SPREAD",      "High Yield Spread",       "High Yield Bond Spread",             Source.LIVE)
    )

    /** All entries, in declaration order (room-cached first, then live). */
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
}
