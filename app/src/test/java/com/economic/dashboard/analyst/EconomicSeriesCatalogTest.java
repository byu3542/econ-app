package com.economic.dashboard.analyst;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.economic.dashboard.api.ApiConfig;
import com.economic.dashboard.api.HistoricalDataRepository;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JVM unit tests for the economic_history fetch catalog.
 *
 * Covers the three things that silently rot as series are added:
 *   1. tier-based staleness decisions
 *   2. catalog completeness — every ApiConfig series id is catalogued exactly once
 *   3. unit correctness per series (the pre-expansion code hardcoded "%" for
 *      every FRED series, which is wrong for index levels and dollar amounts)
 *
 * Everything under test is pure: no Android framework, no database, no network.
 * Run with: ./gradlew test
 */
public class EconomicSeriesCatalogTest {

    private static final long HOUR = 60L * 60 * 1000;
    private static final long DAY = 24 * HOUR;
    private static final long NOW = 1_785_000_000_000L;

    // ── 1. Tier-based staleness ───────────────────────────────────────────────

    @Test
    public void neverCached_isStaleAtEveryTier() {
        for (EconomicSeriesRegistry.Tier tier : EconomicSeriesRegistry.Tier.values()) {
            assertTrue(tier + " should be stale when never cached",
                    HistoricalDataRepository.isSeriesStale(tier, 0, NOW));
            assertTrue(tier + " should be stale on a negative timestamp",
                    HistoricalDataRepository.isSeriesStale(tier, -1, NOW));
        }
    }

    @Test
    public void dailyTier_refreshesAfterOneDay() {
        EconomicSeriesRegistry.Tier daily = EconomicSeriesRegistry.Tier.DAILY;
        assertFalse(HistoricalDataRepository.isSeriesStale(daily, NOW - 1 * HOUR, NOW));
        assertFalse(HistoricalDataRepository.isSeriesStale(daily, NOW - DAY + 1000, NOW));
        assertTrue(HistoricalDataRepository.isSeriesStale(daily, NOW - DAY, NOW));
        assertTrue(HistoricalDataRepository.isSeriesStale(daily, NOW - 3 * DAY, NOW));
    }

    @Test
    public void monthlyTier_refreshesAfterSevenDays() {
        EconomicSeriesRegistry.Tier monthly = EconomicSeriesRegistry.Tier.MONTHLY;
        // A daily-tier series would already be stale here; a monthly one is not.
        assertFalse(HistoricalDataRepository.isSeriesStale(monthly, NOW - 2 * DAY, NOW));
        assertFalse(HistoricalDataRepository.isSeriesStale(monthly, NOW - 7 * DAY + 1000, NOW));
        assertTrue(HistoricalDataRepository.isSeriesStale(monthly, NOW - 7 * DAY, NOW));
    }

    @Test
    public void quarterlyTier_refreshesAfterThirtyDays() {
        EconomicSeriesRegistry.Tier quarterly = EconomicSeriesRegistry.Tier.QUARTERLY;
        assertFalse(HistoricalDataRepository.isSeriesStale(quarterly, NOW - 10 * DAY, NOW));
        assertFalse(HistoricalDataRepository.isSeriesStale(quarterly, NOW - 30 * DAY + 1000, NOW));
        assertTrue(HistoricalDataRepository.isSeriesStale(quarterly, NOW - 30 * DAY, NOW));
    }

    @Test
    public void tiersAreOrderedDailyThroughQuarterly() {
        assertTrue(EconomicSeriesRegistry.Tier.DAILY.maxAgeMillis
                < EconomicSeriesRegistry.Tier.MONTHLY.maxAgeMillis);
        assertTrue(EconomicSeriesRegistry.Tier.MONTHLY.maxAgeMillis
                < EconomicSeriesRegistry.Tier.QUARTERLY.maxAgeMillis);
    }

    /** A series stale at the daily cadence must not be considered stale monthly. */
    @Test
    public void tiersDecideIndependently() {
        long threeDaysOld = NOW - 3 * DAY;
        assertTrue(HistoricalDataRepository.isSeriesStale(
                EconomicSeriesRegistry.Tier.DAILY, threeDaysOld, NOW));
        assertFalse(HistoricalDataRepository.isSeriesStale(
                EconomicSeriesRegistry.Tier.MONTHLY, threeDaysOld, NOW));
        assertFalse(HistoricalDataRepository.isSeriesStale(
                EconomicSeriesRegistry.Tier.QUARTERLY, threeDaysOld, NOW));
    }

    // ── 2. Catalog completeness ───────────────────────────────────────────────

    /** Every FRED/BLS/BEA series id declared in ApiConfig, referenced by constant. */
    private static List<String> apiConfigSeriesIds() {
        return Arrays.asList(
                ApiConfig.FRED_FED_FUNDS,
                ApiConfig.FRED_ISM_PMI,
                ApiConfig.FRED_10Y_MATURITY,
                ApiConfig.FRED_2Y_MATURITY,
                ApiConfig.FRED_3M_MATURITY,
                ApiConfig.FRED_SP500,
                ApiConfig.FRED_WILSHIRE5000,
                ApiConfig.FRED_NASDAQ,
                ApiConfig.FRED_VIX,
                ApiConfig.FRED_PCE,
                ApiConfig.FRED_CORE_PCE,
                ApiConfig.FRED_BAA_SPREAD,
                ApiConfig.FRED_HY_SPREAD,
                ApiConfig.FRED_HOUSING_STARTS,
                ApiConfig.FRED_EXISTING_HOME_SALES,
                ApiConfig.FRED_BANK_MBS,
                ApiConfig.FRED_FED_MBS,
                ApiConfig.FRED_MORTGAGE30,
                ApiConfig.BLS_UNEMPLOYMENT_RATE,
                ApiConfig.BLS_EMPLOYMENT_LEVEL,
                ApiConfig.BLS_LABOR_PARTICIPATION,
                ApiConfig.BLS_CPI_U,
                ApiConfig.BLS_CPI_W,
                ApiConfig.BLS_HOURLY_EARNINGS,
                ApiConfig.BLS_WEEKLY_EARNINGS,
                ApiConfig.BEA_TABLE);
    }

    private static Map<String, Integer> remoteIdCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (EconomicSeriesRegistry.Entry e : EconomicSeriesRegistry.entries()) {
            Integer prev = counts.get(e.remoteId);
            counts.put(e.remoteId, prev == null ? 1 : prev + 1);
        }
        return counts;
    }

    @Test
    public void everyApiConfigSeriesIsCataloguedExactlyOnce() {
        Map<String, Integer> counts = remoteIdCounts();
        for (String remoteId : apiConfigSeriesIds()) {
            Integer n = counts.get(remoteId);
            assertNotNull("ApiConfig series " + remoteId + " is missing from the catalog", n);
            assertEquals("ApiConfig series " + remoteId + " is catalogued more than once",
                    1, (int) n);
        }
    }

    @Test
    public void everyTreasuryMaturityIsCataloguedExactlyOnce() {
        Map<String, Integer> counts = remoteIdCounts();
        assertEquals("TREASURY_MATURITIES and TREASURY_FIELDS must stay parallel",
                ApiConfig.TREASURY_MATURITIES.length, ApiConfig.TREASURY_FIELDS.length);
        for (int i = 0; i < ApiConfig.TREASURY_FIELDS.length; i++) {
            String field = ApiConfig.TREASURY_FIELDS[i];
            Integer n = counts.get(field);
            assertNotNull("Treasury field " + field + " is missing from the catalog", n);
            assertEquals("Treasury field " + field + " is catalogued more than once", 1, (int) n);

            // The maturity label is what TreasuryYieldRepository actually looks up.
            EconomicSeriesRegistry.Entry e =
                    EconomicSeriesRegistry.treasuryEntryForMaturity(ApiConfig.TREASURY_MATURITIES[i]);
            assertNotNull("No catalog entry for maturity " + ApiConfig.TREASURY_MATURITIES[i], e);
            assertEquals(field, e.remoteId);
            assertEquals(EconomicSeriesRegistry.ApiSource.TREASURY, e.apiSource);
        }
    }

    /**
     * Reflection guard: catches an ApiConfig series constant added without a
     * matching catalog entry (and without anyone updating the explicit list
     * above). Non-series FRED_/BLS_ constants — keys, base URLs, limits — are
     * excluded by name.
     */
    @Test
    public void noFredOrBlsConstantEscapesTheCatalog() throws Exception {
        Set<String> notSeriesIds = new HashSet<>(Arrays.asList(
                "FRED_API_KEY", "FRED_BASE_URL", "BLS_API_KEY", "BLS_BASE_URL_V2",
                "FRED_MBS_LIMIT", "FRED_HOUSING_LIMIT", "FRED_PCE_LIMIT",
                "FRED_CACHE_DAILY_LIMIT", "FRED_CACHE_MONTHLY_LIMIT",
                "BLS_HISTORY_YEARS"));

        Map<String, Integer> counts = remoteIdCounts();
        List<String> missing = new ArrayList<>();
        for (Field f : ApiConfig.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() != String.class) continue;
            String name = f.getName();
            if (!name.startsWith("FRED_") && !name.startsWith("BLS_")) continue;
            if (notSeriesIds.contains(name)) continue;

            f.setAccessible(true);
            String value = (String) f.get(null);
            if (!counts.containsKey(value)) missing.add(name + " (" + value + ")");
        }
        assertTrue("ApiConfig series constants missing from EconomicSeriesRegistry: " + missing,
                missing.isEmpty());
    }

    @Test
    public void catalogIdsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (EconomicSeriesRegistry.Entry e : EconomicSeriesRegistry.entries()) {
            assertTrue("Duplicate catalog id: " + e.id, seen.add(e.id));
        }
    }

    /**
     * The pre-expansion ids are load-bearing: AnalystToolExecutor's tool enum,
     * ChatAdapter.SERIES_LABELS, the chat [CHART:...] tags, and existing rows in
     * economic_history all key off them. Renaming one is a regression.
     */
    @Test
    public void preExistingIdsArePreserved() {
        List<String> legacy = Arrays.asList(
                "DGS10", "DGS2", "DGS3MO", "MORTGAGE30US", "LNS14000000", "GDP_BEA_T10101",
                "CPI", "PCE", "CORE_PCE", "SP500", "NASDAQ", "VIX", "WAGES",
                "HOUSING_STARTS", "HOME_SALES", "BAA_SPREAD", "HY_SPREAD");
        for (String id : legacy) {
            assertNotNull("Registry id " + id + " disappeared", EconomicSeriesRegistry.byId(id));
        }
        // HistoricalContextBuilder reads the table through these constants.
        assertNotNull(EconomicSeriesRegistry.byId(HistoricalDataRepository.SERIES_10Y_TREASURY));
        assertNotNull(EconomicSeriesRegistry.byId(HistoricalDataRepository.SERIES_2Y_TREASURY));
        assertNotNull(EconomicSeriesRegistry.byId(HistoricalDataRepository.SERIES_3M_TREASURY));
        assertNotNull(EconomicSeriesRegistry.byId(HistoricalDataRepository.SERIES_MORTGAGE30));
        assertNotNull(EconomicSeriesRegistry.byId(HistoricalDataRepository.SERIES_UNEMPLOYMENT));
        assertNotNull(EconomicSeriesRegistry.byId(HistoricalDataRepository.SERIES_GDP_GROWTH));
    }

    /** Treasury curve rows are mirror-written, so they must never be fetched here. */
    @Test
    public void fetchableEntriesExcludeTreasury() {
        int treasury = 0;
        for (EconomicSeriesRegistry.Entry e : EconomicSeriesRegistry.entries()) {
            if (e.apiSource == EconomicSeriesRegistry.ApiSource.TREASURY) treasury++;
        }
        assertEquals(8, treasury);
        for (EconomicSeriesRegistry.Entry e : EconomicSeriesRegistry.fetchableEntries()) {
            assertFalse("Treasury entry " + e.id + " must not be fetched over HTTP",
                    e.apiSource == EconomicSeriesRegistry.ApiSource.TREASURY);
        }
        assertEquals(EconomicSeriesRegistry.entries().length - treasury,
                EconomicSeriesRegistry.fetchableEntries().size());
    }

    // ── 3. Unit correctness ───────────────────────────────────────────────────

    /**
     * The pre-expansion fetcher stored "%" for every FRED series. That is wrong
     * for index levels, unit counts, and dollar amounts, and the AI quotes the
     * unit verbatim — "S&P 500 at 5432.10%" is the failure this pins down.
     */
    @Test
    public void unitsAreCorrectPerSeries() {
        Map<String, String> expected = new HashMap<>();
        // Index levels — NOT percentages.
        expected.put("SP500", "Index");
        expected.put("NASDAQ", "Index");
        expected.put("WILL5000INDX", "Index");
        expected.put("VIX", "Index");
        expected.put("PCE", "Index");
        expected.put("CORE_PCE", "Index");
        expected.put("CPI", "Index");
        expected.put("CUSR0000SA0", "Index");
        expected.put("NAPM", "Index");
        // Counts.
        expected.put("HOUSING_STARTS", "Thousands");
        expected.put("HOME_SALES", "Units");
        expected.put("LNS12000000", "Thousands");
        // Dollars.
        expected.put("TMBACBW027SBOG", "$M");
        expected.put("WSHOMCB", "$M");
        expected.put("WAGES", "$/hr");
        expected.put("CES0500000011", "$/wk");
        // Genuine percentages.
        expected.put("DGS10", "%");
        expected.put("DGS2", "%");
        expected.put("DGS3MO", "%");
        expected.put("DFF", "%");
        expected.put("MORTGAGE30US", "%");
        expected.put("BAA_SPREAD", "%");
        expected.put("HY_SPREAD", "%");
        expected.put("LNS14000000", "%");
        expected.put("LNS11300000", "%");
        expected.put("GDP_BEA_T10101", "%");
        expected.put("UST_1M", "%");
        expected.put("UST_30Y", "%");

        for (Map.Entry<String, String> want : expected.entrySet()) {
            EconomicSeriesRegistry.Entry e = EconomicSeriesRegistry.byId(want.getKey());
            assertNotNull("Missing catalog entry: " + want.getKey(), e);
            assertEquals("Wrong unit for " + want.getKey(), want.getValue(), e.unit);
        }
    }

    @Test
    public void everyEntryHasUsableMetadata() {
        Set<String> allowedCategories = new HashSet<>(Arrays.asList(
                "Treasury", "Employment", "Housing", "GDP", "Inflation",
                "Markets", "Credit", "Policy"));
        for (EconomicSeriesRegistry.Entry e : EconomicSeriesRegistry.entries()) {
            assertFalse("Blank id", e.id.trim().isEmpty());
            assertFalse("Blank chartLabel for " + e.id, e.chartLabel.trim().isEmpty());
            assertFalse("Blank toolHint for " + e.id, e.toolHint.trim().isEmpty());
            assertFalse("Blank remoteId for " + e.id, e.remoteId.trim().isEmpty());
            assertFalse("Blank unit for " + e.id, e.unit.trim().isEmpty());
            assertTrue("Unexpected category '" + e.category + "' for " + e.id,
                    allowedCategories.contains(e.category));
            assertNotNull("Null tier for " + e.id, e.tier);
            // FRED is the only provider that takes a record-count limit.
            if (e.apiSource == EconomicSeriesRegistry.ApiSource.FRED) {
                assertFalse("FRED entry " + e.id + " needs a limit", e.limit.trim().isEmpty());
                assertNotNull("FRED entry " + e.id + " needs a frequency", e.frequency);
            }
        }
    }

    /** Chart captions and tool hints must exist for every id the model can name. */
    @Test
    public void everyIdHasChartLabelAndAppearsInAllIds() {
        Map<String, String> labels = EconomicSeriesRegistry.chartLabels();
        List<String> allIds = EconomicSeriesRegistry.allIds();
        assertEquals(EconomicSeriesRegistry.entries().length, allIds.size());
        assertEquals(EconomicSeriesRegistry.entries().length, labels.size());
        for (String id : allIds) {
            assertNotNull("No chartLabel for " + id, labels.get(id));
        }
        // Every catalogued series is Room-backed since the cache expansion.
        assertEquals(EconomicSeriesRegistry.entries().length,
                EconomicSeriesRegistry.roomCachedIds().length);
    }
}
