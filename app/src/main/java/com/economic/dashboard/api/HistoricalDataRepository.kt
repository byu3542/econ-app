package com.economic.dashboard.api

import android.content.Context
import android.util.Log
import com.economic.dashboard.database.EconomicHistoryDao
import com.economic.dashboard.database.YieldDatabase
import com.economic.dashboard.models.BeaResponse
import com.economic.dashboard.models.BlsResponse
import com.economic.dashboard.models.EconomicHistoryEntry
import com.economic.dashboard.models.FredResponse
import com.economic.dashboard.utils.AppExecutors
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

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
object HistoricalDataRepository {

    private const val TAG = "HistoricalDataRepo"

    // ── Staleness threshold ────────────────────────────────────────────────────
    /** Refresh cached history at most once every 7 days. */
    private const val STALE_DAYS = 7L
    private const val STALE_THRESHOLD_MS = STALE_DAYS * 24 * 60 * 60 * 1000L

    // ── Series IDs (used as primary keys in economic_history) ──────────────────
    @JvmField val SERIES_10Y_TREASURY = "DGS10"
    @JvmField val SERIES_2Y_TREASURY = "DGS2"
    @JvmField val SERIES_3M_TREASURY = "DGS3MO"
    @JvmField val SERIES_MORTGAGE30 = "MORTGAGE30US"
    @JvmField val SERIES_UNEMPLOYMENT = "LNS14000000"
    @JvmField val SERIES_GDP_GROWTH = "GDP_BEA_T10101"

    /** Number of weekly FRED records ≈ 2 years (104 weeks). */
    private const val FRED_2Y_WEEKLY_LIMIT = "104"

    // ──────────────────────────────────────────────────────────────────────────

    fun interface RefreshCallback {
        /** Called on a background thread when all series have been attempted. */
        fun onComplete(anySucceeded: Boolean)
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
    @JvmStatic
    fun refreshIfStale(context: Context, callback: RefreshCallback?) {
        AppExecutors.networkIO().execute {
            try {
                val dao = YieldDatabase.getInstance(context).economicHistoryDao()
                val lastCache = dao.getLastCacheTimeSync()
                val ageMs = System.currentTimeMillis() - lastCache

                if (lastCache > 0 && ageMs < STALE_THRESHOLD_MS) {
                    Log.d(TAG, "Cache is fresh (" + (ageMs / 3_600_000) + "h old). Skipping refresh.")
                    callback?.onComplete(false)
                    return@execute
                }

                Log.d(
                    TAG, if (lastCache == 0L)
                        "Cache is empty — fetching 2-year history."
                    else
                        "Cache is stale (" + (ageMs / 86_400_000) + "d old) — refreshing."
                )

                fetchAll(dao, callback)
            } catch (e: Exception) {
                Log.e(TAG, "refreshIfStale error", e)
                callback?.onComplete(false)
            }
        }
    }

    /**
     * Force a full refresh regardless of cache age.
     * Clears existing data before fetching.
     */
    @JvmStatic
    fun forceRefresh(context: Context, callback: RefreshCallback?) {
        AppExecutors.networkIO().execute {
            try {
                val dao = YieldDatabase.getInstance(context).economicHistoryDao()
                dao.clearAll()
                fetchAll(dao, callback)
            } catch (e: Exception) {
                Log.e(TAG, "forceRefresh error", e)
                callback?.onComplete(false)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal — orchestrate all fetches
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Fetch all 6 series in parallel (each in its own thread).
     * The callback fires after ALL series have returned (success or error).
     */
    private fun fetchAll(dao: EconomicHistoryDao, callback: RefreshCallback?) {
        // We track 6 series; AtomicInteger counts completions.
        val total = 6
        val remaining = AtomicInteger(total)
        val successCount = AtomicInteger(0)

        val onOneDone = Runnable {
            if (remaining.decrementAndGet() == 0) {
                Log.d(TAG, "All series fetched. Succeeded: " + successCount.get() + "/" + total)
                callback?.onComplete(successCount.get() > 0)
            }
        }

        val now = System.currentTimeMillis()

        // ── FRED: weekly 2-year data ───────────────────────────────────────────
        fetchFredSeries(dao, SERIES_10Y_TREASURY, "10-Year Treasury Yield", "Treasury", now, successCount, onOneDone)
        fetchFredSeries(dao, SERIES_2Y_TREASURY, "2-Year Treasury Yield", "Treasury", now, successCount, onOneDone)
        fetchFredSeries(dao, SERIES_3M_TREASURY, "3-Month T-Bill Yield", "Treasury", now, successCount, onOneDone)
        fetchFredSeries(dao, SERIES_MORTGAGE30, "30-Year Fixed Mortgage Rate", "Housing", now, successCount, onOneDone)

        // ── BLS: monthly unemployment (2 years) ───────────────────────────────
        fetchBlsUnemployment(dao, now, successCount, onOneDone)

        // ── BEA: quarterly GDP growth (8 quarters ≈ 2 years) ─────────────────
        fetchBeaGdp(dao, now, successCount, onOneDone)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FRED fetcher (generic weekly series)
    // ──────────────────────────────────────────────────────────────────────────

    private fun fetchFredSeries(
        dao: EconomicHistoryDao,
        seriesId: String,
        seriesName: String,
        category: String,
        now: Long,
        successCount: AtomicInteger,
        onDone: Runnable
    ) {
        AppExecutors.networkIO().execute {
            try {
                val params = HashMap<String, String>()
                params["series_id"] = seriesId
                params["api_key"] = ApiConfig.FRED_API_KEY
                params["file_type"] = "json"
                params["sort_order"] = "desc"
                params["limit"] = FRED_2Y_WEEKLY_LIMIT
                params["frequency"] = "w" // weekly aggregation

                val call = RetrofitClient.getFredService().getFredData(params)
                val response = call.execute()
                val body = response.body()

                if (!response.isSuccessful || body == null) {
                    Log.w(TAG, "FRED $seriesId HTTP " + response.code())
                    onDone.run()
                    return@execute
                }

                val entries = ArrayList<EconomicHistoryEntry>()
                val observations = body.observations
                if (observations != null) {
                    for (obs in observations) {
                        val value = obs.value
                        val date = obs.date
                        if (date != null && value != null && isValidValue(value)) {
                            try {
                                entries.add(
                                    EconomicHistoryEntry(
                                        seriesId, seriesName, category,
                                        date,
                                        value.toDouble(),
                                        "%", now
                                    )
                                )
                            } catch (ignored: NumberFormatException) {
                            }
                        }
                    }
                }

                if (entries.isNotEmpty()) {
                    dao.insertAll(entries)
                    successCount.incrementAndGet()
                    Log.d(TAG, "Cached " + entries.size + " rows for " + seriesId)
                } else {
                    Log.w(TAG, "FRED $seriesId returned 0 valid observations")
                }
            } catch (e: Exception) {
                Log.e(TAG, "FRED fetch error for $seriesId", e)
            } finally {
                onDone.run()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BLS fetcher — Unemployment Rate (LNS14000000)
    // ──────────────────────────────────────────────────────────────────────────

    private fun fetchBlsUnemployment(
        dao: EconomicHistoryDao,
        now: Long,
        successCount: AtomicInteger,
        onDone: Runnable
    ) {
        AppExecutors.networkIO().execute {
            try {
                val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                val startYear = currentYear - 2 // 2 years back

                val requestBody = HashMap<String, Any>()
                requestBody["seriesid"] = listOf(SERIES_UNEMPLOYMENT)
                requestBody["startyear"] = startYear.toString()
                requestBody["endyear"] = currentYear.toString()
                requestBody["registrationkey"] = ApiConfig.BLS_API_KEY

                val call = RetrofitClient.getBlsService().getBlsData(requestBody)
                val response = call.execute()
                val blsResp = response.body()

                if (!response.isSuccessful || blsResp == null) {
                    Log.w(TAG, "BLS unemployment HTTP " + response.code())
                    onDone.run()
                    return@execute
                }

                val entries = ArrayList<EconomicHistoryEntry>()
                val seriesList = blsResp.Results?.series

                if (seriesList != null) {
                    for (series in seriesList) {
                        if (SERIES_UNEMPLOYMENT != series.seriesID) continue
                        val dataList = series.data ?: continue

                        for (dp in dataList) {
                            // BLS periods: "M01"–"M12" are monthly; skip annual "M13"
                            val period = dp.period
                            val value = dp.value
                            if (period == null || !period.startsWith("M")) continue
                            if ("M13" == period) continue
                            if (value == null || !isValidValue(value)) continue

                            try {
                                val month = period.substring(1).toInt()
                                val date = dp.year + "-" +
                                    String.format(Locale.US, "%02d", month) +
                                    "-01"
                                entries.add(
                                    EconomicHistoryEntry(
                                        SERIES_UNEMPLOYMENT,
                                        "Unemployment Rate",
                                        "Employment",
                                        date,
                                        value.toDouble(),
                                        "%", now
                                    )
                                )
                            } catch (ignored: NumberFormatException) {
                            }
                        }
                    }
                }

                if (entries.isNotEmpty()) {
                    dao.insertAll(entries)
                    successCount.incrementAndGet()
                    Log.d(TAG, "Cached " + entries.size + " rows for unemployment")
                } else {
                    Log.w(TAG, "BLS unemployment returned 0 valid observations")
                }
            } catch (e: Exception) {
                Log.e(TAG, "BLS unemployment fetch error", e)
            } finally {
                onDone.run()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BEA fetcher — GDP Growth (T10101, quarterly)
    // ──────────────────────────────────────────────────────────────────────────

    private fun fetchBeaGdp(
        dao: EconomicHistoryDao,
        now: Long,
        successCount: AtomicInteger,
        onDone: Runnable
    ) {
        AppExecutors.networkIO().execute {
            try {
                val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                // Build comma-separated year list for the past 3 years
                // (extra year ensures we capture full 8 quarters near year boundaries)
                val years = StringBuilder()
                for (y in currentYear - 2..currentYear) {
                    if (years.isNotEmpty()) years.append(",")
                    years.append(y)
                }

                val params = HashMap<String, String>()
                params["UserID"] = ApiConfig.BEA_API_KEY
                params["method"] = "GetData"
                params["DatasetName"] = "NIPA"
                params["TableName"] = "T10101"
                params["Frequency"] = "Q"
                params["Year"] = years.toString()
                params["ResultFormat"] = "JSON"

                val call = RetrofitClient.getBeaService().getBeaData(params)
                val response = call.execute()
                val beaResp = response.body()

                if (!response.isSuccessful || beaResp == null) {
                    Log.w(TAG, "BEA GDP HTTP " + response.code())
                    onDone.run()
                    return@execute
                }

                val entries = ArrayList<EconomicHistoryEntry>()
                val dataPoints = beaResp.BEAAPI?.Results?.Data

                if (dataPoints != null) {
                    for (dp in dataPoints) {
                        val timePeriod = dp.TimePeriod
                        val dataValue = dp.DataValue
                        if (timePeriod == null || dataValue == null || !isValidValue(dataValue)) continue

                        // Only keep "Gross domestic product" line (LineDescription)
                        val desc = dp.getDescription()
                        if (desc == null || !desc.lowercase(Locale.US)
                                .contains("gross domestic product")
                        ) continue

                        try {
                            val value = dataValue.replace(",", "").toDouble()
                            val date = beaTimePeriodToDate(timePeriod) ?: continue

                            entries.add(
                                EconomicHistoryEntry(
                                    SERIES_GDP_GROWTH,
                                    "GDP Growth (QoQ Annualized)",
                                    "GDP",
                                    date,
                                    value,
                                    "%", now
                                )
                            )
                        } catch (ignored: NumberFormatException) {
                        }
                    }
                }

                if (entries.isNotEmpty()) {
                    dao.insertAll(entries)
                    successCount.incrementAndGet()
                    Log.d(TAG, "Cached " + entries.size + " rows for GDP")
                } else {
                    Log.w(TAG, "BEA GDP returned 0 valid observations")
                }
            } catch (e: Exception) {
                Log.e(TAG, "BEA GDP fetch error", e)
            } finally {
                onDone.run()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Convert BEA period "2023Q1" → "2023-01-01". Returns null on bad input. */
    private fun beaTimePeriodToDate(timePeriod: String?): String? {
        if (timePeriod == null || !timePeriod.contains("Q")) return null
        val parts = timePeriod.split("Q")
        if (parts.size != 2) return null
        val quarter = try {
            parts[1].toInt()
        } catch (e: NumberFormatException) {
            return null
        }
        val month = when (quarter) {
            1 -> "01"
            2 -> "04"
            3 -> "07"
            4 -> "10"
            else -> return null
        }
        return parts[0] + "-" + month + "-01"
    }

    private fun isValidValue(value: String?): Boolean =
        value != null && value.isNotEmpty() && value != "." && !value.equals("ND", ignoreCase = true)
}
