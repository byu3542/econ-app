package com.economic.dashboard.api;

import android.content.Context;
import android.util.Log;

import com.economic.dashboard.database.EconomicHistoryDao;
import com.economic.dashboard.database.YieldDatabase;
import com.economic.dashboard.models.BeaResponse;
import com.economic.dashboard.models.BlsResponse;
import com.economic.dashboard.models.EconomicHistoryEntry;
import com.economic.dashboard.models.FredResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import retrofit2.Call;

/**
 * Fetches and caches 2 years of historical economic data in the economic_history
 * Room table so the AI Analyst can reference trends even on first launch.
 *
 * Data fetched (all ~24 months / 8 quarters):
 *   FRED weekly  → 10-Year Treasury (DGS10), 2-Year Treasury (DGS2),
 *                  3-Month T-Bill (DGS3MO), 30-Yr Mortgage Rate (MORTGAGE30US)
 *   BLS monthly  → Unemployment Rate (LNS14000000)
 *   BEA quarterly→ GDP Growth, Real GDP (T10101)
 *
 * Refresh policy: if the most recent cached row is < STALE_DAYS old, skip the fetch.
 * This prevents hammering APIs on every app open or every AI Analyst panel open.
 */
public class HistoricalDataRepository {

    private static final String TAG = "HistoricalDataRepo";

    // ── Staleness threshold ────────────────────────────────────────────────────
    /** Refresh cached history at most once every 7 days. */
    private static final long STALE_DAYS       = 7;
    private static final long STALE_THRESHOLD_MS = STALE_DAYS * 24 * 60 * 60 * 1000L;

    // ── Series IDs (used as primary keys in economic_history) ──────────────────
    public static final String SERIES_10Y_TREASURY  = "DGS10";
    public static final String SERIES_2Y_TREASURY   = "DGS2";
    public static final String SERIES_3M_TREASURY   = "DGS3MO";
    public static final String SERIES_MORTGAGE30    = "MORTGAGE30US";
    public static final String SERIES_UNEMPLOYMENT  = "LNS14000000";
    public static final String SERIES_GDP_GROWTH    = "GDP_BEA_T10101";

    /** Number of weekly FRED records ≈ 2 years (104 weeks). */
    private static final String FRED_2Y_WEEKLY_LIMIT = "104";

    // ──────────────────────────────────────────────────────────────────────────

    public interface RefreshCallback {
        /** Called on a background thread when all series have been attempted. */
        void onComplete(boolean anySucceeded);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Check cache staleness and fetch from APIs if needed.
     * Runs entirely on a background thread; safe to call from the main thread.
     *
     * @param context  Used to obtain the Room database instance.
     * @param callback Notified (on a background thread) when the refresh is done.
     *                 Pass null if you don't need a completion signal.
     */
    public static void refreshIfStale(Context context, RefreshCallback callback) {
        new Thread(() -> {
            try {
                EconomicHistoryDao dao = YieldDatabase.getInstance(context).economicHistoryDao();
                long lastCache = dao.getLastCacheTimeSync();
                long ageMs = System.currentTimeMillis() - lastCache;

                if (lastCache > 0 && ageMs < STALE_THRESHOLD_MS) {
                    Log.d(TAG, "Cache is fresh (" + (ageMs / 3600_000) + "h old). Skipping refresh.");
                    if (callback != null) callback.onComplete(false);
                    return;
                }

                Log.d(TAG, lastCache == 0
                        ? "Cache is empty — fetching 2-year history."
                        : "Cache is stale (" + (ageMs / 86400_000) + "d old) — refreshing.");

                fetchAll(dao, callback);

            } catch (Exception e) {
                Log.e(TAG, "refreshIfStale error", e);
                if (callback != null) callback.onComplete(false);
            }
        }).start();
    }

    /**
     * Force a full refresh regardless of cache age.
     * Clears existing data before fetching.
     */
    public static void forceRefresh(Context context, RefreshCallback callback) {
        new Thread(() -> {
            try {
                EconomicHistoryDao dao = YieldDatabase.getInstance(context).economicHistoryDao();
                dao.clearAll();
                fetchAll(dao, callback);
            } catch (Exception e) {
                Log.e(TAG, "forceRefresh error", e);
                if (callback != null) callback.onComplete(false);
            }
        }).start();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal — orchestrate all fetches
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Fetch all 6 series in parallel (each in its own thread).
     * The callback fires after ALL series have returned (success or error).
     */
    private static void fetchAll(EconomicHistoryDao dao, RefreshCallback callback) {
        // We track 6 series; AtomicInteger counts completions.
        final int TOTAL = 6;
        AtomicInteger remaining    = new AtomicInteger(TOTAL);
        AtomicInteger successCount = new AtomicInteger(0);

        Runnable onOneDone = () -> {
            if (remaining.decrementAndGet() == 0) {
                Log.d(TAG, "All series fetched. Succeeded: " + successCount.get() + "/" + TOTAL);
                if (callback != null) callback.onComplete(successCount.get() > 0);
            }
        };

        long now = System.currentTimeMillis();

        // ── FRED: weekly 2-year data ───────────────────────────────────────────
        fetchFredSeries(dao, SERIES_10Y_TREASURY,  "10-Year Treasury Yield",      "Treasury",  now, successCount, onOneDone);
        fetchFredSeries(dao, SERIES_2Y_TREASURY,   "2-Year Treasury Yield",       "Treasury",  now, successCount, onOneDone);
        fetchFredSeries(dao, SERIES_3M_TREASURY,   "3-Month T-Bill Yield",        "Treasury",  now, successCount, onOneDone);
        fetchFredSeries(dao, SERIES_MORTGAGE30,    "30-Year Fixed Mortgage Rate", "Housing",   now, successCount, onOneDone);

        // ── BLS: monthly unemployment (2 years) ───────────────────────────────
        fetchBlsUnemployment(dao, now, successCount, onOneDone);

        // ── BEA: quarterly GDP growth (8 quarters ≈ 2 years) ─────────────────
        fetchBeaGdp(dao, now, successCount, onOneDone);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FRED fetcher (generic weekly series)
    // ──────────────────────────────────────────────────────────────────────────

    private static void fetchFredSeries(
            EconomicHistoryDao dao,
            String seriesId,
            String seriesName,
            String category,
            long now,
            AtomicInteger successCount,
            Runnable onDone) {

        new Thread(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("series_id",  seriesId);
                params.put("api_key",    ApiConfig.FRED_API_KEY);
                params.put("file_type",  "json");
                params.put("sort_order", "desc");
                params.put("limit",      FRED_2Y_WEEKLY_LIMIT);
                params.put("frequency",  "w");  // weekly aggregation

                Call<FredResponse> call = RetrofitClient.getFredService().getFredData(params);
                retrofit2.Response<FredResponse> response = call.execute();

                if (!response.isSuccessful() || response.body() == null) {
                    Log.w(TAG, "FRED " + seriesId + " HTTP " + response.code());
                    onDone.run();
                    return;
                }

                List<EconomicHistoryEntry> entries = new ArrayList<>();
                if (response.body().observations != null) {
                    for (FredResponse.FredObservation obs : response.body().observations) {
                        if (isValidValue(obs.value)) {
                            try {
                                entries.add(new EconomicHistoryEntry(
                                        seriesId, seriesName, category,
                                        obs.date,
                                        Double.parseDouble(obs.value),
                                        "%", now
                                ));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }

                if (!entries.isEmpty()) {
                    dao.insertAll(entries);
                    successCount.incrementAndGet();
                    Log.d(TAG, "Cached " + entries.size() + " rows for " + seriesId);
                } else {
                    Log.w(TAG, "FRED " + seriesId + " returned 0 valid observations");
                }
            } catch (Exception e) {
                Log.e(TAG, "FRED fetch error for " + seriesId, e);
            } finally {
                onDone.run();
            }
        }).start();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BLS fetcher — Unemployment Rate (LNS14000000)
    // ──────────────────────────────────────────────────────────────────────────

    private static void fetchBlsUnemployment(
            EconomicHistoryDao dao,
            long now,
            AtomicInteger successCount,
            Runnable onDone) {

        new Thread(() -> {
            try {
                int currentYear = Calendar.getInstance().get(Calendar.YEAR);
                int startYear   = currentYear - 2;  // 2 years back

                Map<String, Object> body = new HashMap<>();
                body.put("seriesid",        Arrays.asList(SERIES_UNEMPLOYMENT));
                body.put("startyear",       String.valueOf(startYear));
                body.put("endyear",         String.valueOf(currentYear));
                body.put("registrationkey", ApiConfig.BLS_API_KEY);

                Call<BlsResponse> call = RetrofitClient.getBlsService().getBlsData(body);
                retrofit2.Response<BlsResponse> response = call.execute();

                if (!response.isSuccessful() || response.body() == null) {
                    Log.w(TAG, "BLS unemployment HTTP " + response.code());
                    onDone.run();
                    return;
                }

                List<EconomicHistoryEntry> entries = new ArrayList<>();
                BlsResponse blsResp = response.body();

                if (blsResp.Results != null && blsResp.Results.series != null) {
                    for (BlsResponse.BlsSeries series : blsResp.Results.series) {
                        if (!SERIES_UNEMPLOYMENT.equals(series.seriesID)) continue;
                        if (series.data == null) continue;

                        for (BlsResponse.BlsDataPoint dp : series.data) {
                            // BLS periods: "M01"–"M12" are monthly; skip annual "M13"
                            if (dp.period == null || !dp.period.startsWith("M")) continue;
                            if ("M13".equals(dp.period)) continue;
                            if (!isValidValue(dp.value)) continue;

                            try {
                                int month = Integer.parseInt(dp.period.substring(1));
                                String date = dp.year + "-"
                                        + String.format(Locale.US, "%02d", month)
                                        + "-01";
                                entries.add(new EconomicHistoryEntry(
                                        SERIES_UNEMPLOYMENT,
                                        "Unemployment Rate",
                                        "Employment",
                                        date,
                                        Double.parseDouble(dp.value),
                                        "%", now
                                ));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }

                if (!entries.isEmpty()) {
                    dao.insertAll(entries);
                    successCount.incrementAndGet();
                    Log.d(TAG, "Cached " + entries.size() + " rows for unemployment");
                } else {
                    Log.w(TAG, "BLS unemployment returned 0 valid observations");
                }
            } catch (Exception e) {
                Log.e(TAG, "BLS unemployment fetch error", e);
            } finally {
                onDone.run();
            }
        }).start();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BEA fetcher — GDP Growth (T10101, quarterly)
    // ──────────────────────────────────────────────────────────────────────────

    private static void fetchBeaGdp(
            EconomicHistoryDao dao,
            long now,
            AtomicInteger successCount,
            Runnable onDone) {

        new Thread(() -> {
            try {
                int currentYear = Calendar.getInstance().get(Calendar.YEAR);
                // Build comma-separated year list for the past 3 years
                // (extra year ensures we capture full 8 quarters near year boundaries)
                StringBuilder years = new StringBuilder();
                for (int y = currentYear - 2; y <= currentYear; y++) {
                    if (years.length() > 0) years.append(",");
                    years.append(y);
                }

                Map<String, String> params = new HashMap<>();
                params.put("UserID",       ApiConfig.BEA_API_KEY);
                params.put("method",       "GetData");
                params.put("DatasetName",  "NIPA");
                params.put("TableName",    "T10101");
                params.put("Frequency",    "Q");
                params.put("Year",         years.toString());
                params.put("ResultFormat", "JSON");

                Call<BeaResponse> call = RetrofitClient.getBeaService().getBeaData(params);
                retrofit2.Response<BeaResponse> response = call.execute();

                if (!response.isSuccessful() || response.body() == null) {
                    Log.w(TAG, "BEA GDP HTTP " + response.code());
                    onDone.run();
                    return;
                }

                BeaResponse beaResp = response.body();
                List<EconomicHistoryEntry> entries = new ArrayList<>();

                if (beaResp.BEAAPI != null && beaResp.BEAAPI.Results != null
                        && beaResp.BEAAPI.Results.Data != null) {

                    for (BeaResponse.BeaDataPoint dp : beaResp.BEAAPI.Results.Data) {
                        if (dp.TimePeriod == null || !isValidValue(dp.DataValue)) continue;

                        // Only keep "Gross domestic product" line (LineDescription)
                        String desc = dp.getDescription();
                        if (desc == null || !desc.toLowerCase(Locale.US)
                                .contains("gross domestic product")) continue;

                        try {
                            double value = Double.parseDouble(dp.DataValue.replace(",", ""));
                            String date  = beaTimePeriodToDate(dp.TimePeriod);
                            if (date == null) continue;

                            entries.add(new EconomicHistoryEntry(
                                    SERIES_GDP_GROWTH,
                                    "GDP Growth (QoQ Annualized)",
                                    "GDP",
                                    date,
                                    value,
                                    "%", now
                            ));
                        } catch (NumberFormatException ignored) {}
                    }
                }

                if (!entries.isEmpty()) {
                    dao.insertAll(entries);
                    successCount.incrementAndGet();
                    Log.d(TAG, "Cached " + entries.size() + " rows for GDP");
                } else {
                    Log.w(TAG, "BEA GDP returned 0 valid observations");
                }
            } catch (Exception e) {
                Log.e(TAG, "BEA GDP fetch error", e);
            } finally {
                onDone.run();
            }
        }).start();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Convert BEA period "2023Q1" → "2023-01-01". Returns null on bad input. */
    private static String beaTimePeriodToDate(String timePeriod) {
        if (timePeriod == null || !timePeriod.contains("Q")) return null;
        String[] parts = timePeriod.split("Q");
        if (parts.length != 2) return null;
        int quarter;
        try { quarter = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { return null; }
        String month;
        switch (quarter) {
            case 1: month = "01"; break;
            case 2: month = "04"; break;
            case 3: month = "07"; break;
            case 4: month = "10"; break;
            default: return null;
        }
        return parts[0] + "-" + month + "-01";
    }

    private static boolean isValidValue(String value) {
        return value != null && !value.isEmpty()
                && !value.equals(".") && !value.equalsIgnoreCase("ND");
    }
}
