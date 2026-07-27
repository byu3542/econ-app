package com.economic.dashboard.analyst;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for every economic series the Econ Analyst can
 * reference. Before this existed, the same 17 ids were hand-maintained in
 * three separate places (AnalystToolExecutor.SERIES_IDS/LIVE_SERIES,
 * ChatAdapter.SERIES_LABELS, and a literal id list inside
 * AiAnalystBottomSheet.buildSystemPrompt()) that happened to agree but had
 * nothing enforcing it at compile time (TICKET-F / review F6).
 *
 * Each id carries two independent labels, not one: {@code chartLabel} is the
 * UI caption shown on chat sparklines (ChatAdapter), and {@code toolHint} is
 * the short description fed into the get_series tool schema and echoed back
 * in tool results (AnalystToolExecutor). They read differently by design
 * ("S&P 500" vs. "S&P 500 index") and both are preserved verbatim from
 * pre-refactor source — this is a structural change, not a wording change.
 */
public final class EconomicSeriesRegistry {

    private EconomicSeriesRegistry() {}

    public enum Source { ROOM_CACHED, LIVE }

    public static final class Entry {
        public final String id;
        public final String chartLabel;
        public final String toolHint;
        public final Source source;

        Entry(String id, String chartLabel, String toolHint, Source source) {
            this.id = id;
            this.chartLabel = chartLabel;
            this.toolHint = toolHint;
            this.source = source;
        }
    }

    private static final Entry[] ENTRIES = {
        // Room-cached (matches HistoricalDataRepository).
        new Entry("DGS10",          "10-Year Treasury Yield",       "10Y Treasury",                      Source.ROOM_CACHED),
        new Entry("DGS2",           "2-Year Treasury Yield",        "2Y Treasury",                        Source.ROOM_CACHED),
        new Entry("DGS3MO",         "3-Month T-Bill Yield",         "3M T-Bill",                          Source.ROOM_CACHED),
        new Entry("MORTGAGE30US",   "30-Year Mortgage Rate",        "30Y mortgage rate",                  Source.ROOM_CACHED),
        new Entry("LNS14000000",    "Unemployment Rate",            "unemployment rate",                  Source.ROOM_CACHED),
        new Entry("GDP_BEA_T10101", "GDP Growth (QoQ annualized)",  "GDP growth QoQ annualized",          Source.ROOM_CACHED),

        // Live ViewModel-backed (AI Law 3: matches every long-press trigger surface).
        new Entry("CPI",            "CPI-U Index (All Items)", "CPI-U All Items index",             Source.LIVE),
        new Entry("PCE",            "PCE Price Index",         "PCE Price Index",                    Source.LIVE),
        new Entry("CORE_PCE",       "Core PCE Price Index",    "Core PCE Price Index",               Source.LIVE),
        new Entry("SP500",          "S&P 500",                 "S&P 500 index",                      Source.LIVE),
        new Entry("NASDAQ",         "Nasdaq Composite",        "Nasdaq Composite index",             Source.LIVE),
        new Entry("VIX",            "VIX Volatility Index",    "VIX volatility index",               Source.LIVE),
        new Entry("WAGES",          "Avg Hourly Earnings",     "Average Hourly Earnings (private)",  Source.LIVE),
        new Entry("HOUSING_STARTS", "Housing Starts",          "Housing Starts",                     Source.LIVE),
        new Entry("HOME_SALES",     "Existing Home Sales",     "Existing Home Sales",                Source.LIVE),
        new Entry("BAA_SPREAD",     "BAA Corporate Spread",    "BAA Corporate Bond Spread",          Source.LIVE),
        new Entry("HY_SPREAD",      "High Yield Spread",       "High Yield Bond Spread",             Source.LIVE),
    };

    /** All entries, in declaration order (room-cached first, then live). */
    public static Entry[] entries() { return ENTRIES; }

    /** Every id, in registry order — feeds the INLINE CHARTS sentence. */
    public static List<String> allIds() {
        List<String> ids = new ArrayList<>();
        for (Entry e : ENTRIES) ids.add(e.id);
        return ids;
    }

    /** Room-cached ids only — feeds AnalystToolExecutor.SERIES_IDS. */
    public static String[] roomCachedIds() {
        List<String> ids = new ArrayList<>();
        for (Entry e : ENTRIES) if (e.source == Source.ROOM_CACHED) ids.add(e.id);
        return ids.toArray(new String[0]);
    }

    /** id -> toolHint for LIVE entries only — feeds AnalystToolExecutor.LIVE_SERIES. */
    public static Map<String, String> liveToolHints() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Entry e : ENTRIES) if (e.source == Source.LIVE) m.put(e.id, e.toolHint);
        return m;
    }

    /** id -> chartLabel for every id — feeds ChatAdapter.SERIES_LABELS. */
    public static Map<String, String> chartLabels() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Entry e : ENTRIES) m.put(e.id, e.chartLabel);
        return m;
    }
}
