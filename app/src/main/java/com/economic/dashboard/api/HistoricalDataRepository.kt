package com.economic.dashboard.api

import android.content.Context
import android.util.Log
import com.economic.dashboard.analyst.EconomicSeriesRegistry
import com.economic.dashboard.analyst.EconomicSeriesRegistry.ApiSource
import com.economic.dashboard.analyst.EconomicSeriesRegistry.Entry
import com.economic.dashboard.analyst.EconomicSeriesRegistry.Tier
import com.economic.dashboard.database.EconomicHistoryDao
import com.economic.dashboard.database.YieldDatabase
import com.economic.dashboard.models.EconomicHistoryEntry
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fills the economic_history Room table so the AI Analyst can reference trends
 * even on first launch.
 *
 * This used to be six hardcoded fetch calls behind one global staleness check.
 * It is now registry-driven: EconomicSeriesRegistry is the fetch catalog, and
 * every series it declares (FRED, BLS, BEA, plus the Treasury.gov curve) lands
 * in the same table under the registry's `id`.
 *
 * Coverage:
 *   FRED     → 18 series (rates, market indices, spreads, PCE, housing, MBS)
 *   BLS      → 7 series, retrieved in ONE batched POST (the v2 API accepts many
 *              `seriesid` values per request; one request per series would be
 *              seven times the traffic for identical data)
 *   BEA      → GDP growth, NIPA T10101, quarterly
 *   Treasury → 8 constant maturities, mirror-written by TreasuryYieldRepository
 *              from the XML it already fetches. Deliberately NOT fetched here —
 *              see EconomicSeriesRegistry's Treasury block.
 *
 * Refresh policy: per series, not global. Each series is refetched only when
 * the newest `cachedAtMillis` for its own seriesId is older than its
 * [Tier] allows (daily ≈ 1d, monthly ≈ 7d, quarterly ≈ 30d), so a market
 * series can refresh without dragging quarterly GDP along with it.
 * [forceRefresh] ignores tiers entirely.
 *
 * Concurrency: history fetches run on a dedicated 4-thread pool rather than
 * AppExecutors.networkIO(). That pool is also bounded at 4, but it is shared
 * with the dashboard's own fetches — queueing ~20 catalog requests on it would
 * stall the visible UI for seconds on a cold start.
 */
object HistoricalDataRepository {

    private const val TAG = "HistoricalDataRepo"

    // ── Series IDs (used as primary keys in economic_history) ──────────────────
    // Retained as constants because HistoricalContextBuilder references them by
    // name. They are the registry ids for the same six series.
    @JvmField val SERIES_10Y_TREASURY = "DGS10"
    @JvmField val SERIES_2Y_TREASURY = "DGS2"
    @JvmField val SERIES_3M_TREASURY = "DGS3MO"
    @JvmField val SERIES_MORTGAGE30 = "MORTGAGE30US"
    @JvmField val SERIES_UNEMPLOYMENT = "LNS14000000"
    @JvmField val SERIES_GDP_GROWTH = "GDP_BEA_T10101"

    /**
     * Bounded pool for catalog fetches. Capped at 4 so a full cold-start refresh
     * never bursts ~20 simultaneous requests at FRED.
     */
    private val historyIO: ExecutorService = Executors.newFixedThreadPool(4) { r ->
        val t = Thread(r, "history-io")
        t.isDaemon = true
        t
    }

    // ──────────────────────────────────────────────────────────────────────────

    fun interface RefreshCallback {
        /** Called on a background thread when all series have been attempted. */
        fun onComplete(anySucceeded: Boolean)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Pure, Android-free staleness test — exposed so unit tests can cover the
     * tier cadence without a database or the Android framework.
     *
     * @param lastCacheMillis newest cachedAtMillis for this series (0 = never cached)
     */
    @JvmStatic
    fun isSeriesStale(tier: Tier, lastCacheMillis: Long, nowMillis: Long): Boolean =
        tier.isStale(lastCacheMillis, nowMillis)

    /**
     * Refresh only the series whose own cached rows have aged past their tier.
     * Runs entirely on a background thread; safe to call from the main thread.
     *
     * @param context  Used to obtain the Room database instance.
     * @param callback Notified (on a background thread) when the refresh is done.
     *                 Pass null if you don't need a completion signal.
     */
    @JvmStatic
    fun refreshIfStale(context: Context, callback: RefreshCallback?) {
        historyIO.execute {
            try {
                val dao = YieldDatabase.getInstance(context).economicHistoryDao()
                val now = System.currentTimeMillis()

                val stale = ArrayList<Entry>()
                for (e in EconomicSeriesRegistry.fetchableEntries()) {
                    val last = dao.getLastCacheTimeForSeries(e.id)
                    if (isSeriesStale(e.tier, last, now)) stale.add(e)
                }

                if (stale.isEmpty()) {
                    Log.d(TAG, "All " + EconomicSeriesRegistry.fetchableEntries().size +
                        " catalog series are fresh. Skipping refresh.")
                    callback?.onComplete(false)
                    return@execute
                }

                Log.d(TAG, "Refreshing " + stale.size + " stale series of " +
                    EconomicSeriesRegistry.fetchableEntries().size + " catalogued.")
                fetchEntries(dao, stale, now, callback)
            } catch (e: Exception) {
                Log.e(TAG, "refreshIfStale error", e)
                callback?.onComplete(false)
            }
        }
    }

    /**
     * Force a full refresh regardless of cache age.
     *
     * Clears each series it is about to refetch, rather than the whole table:
     * a blanket clearAll() would also wipe the Treasury curve rows, which this
     * class does not fetch and so would not restore.
     */
    @JvmStatic
    fun forceRefresh(context: Context, callback: RefreshCallback?) {
        historyIO.execute {
            try {
                val dao = YieldDatabase.getInstance(context).economicHistoryDao()
                val all = EconomicSeriesRegistry.fetchableEntries()
                for (e in all) {
                    try {
                        dao.deleteSeries(e.id)
                    } catch (ex: Exception) {
                        Log.w(TAG, "Could not clear series " + e.id, ex)
                    }
                }
                fetchEntries(dao, all, System.currentTimeMillis(), callback)
            } catch (e: Exception) {
                Log.e(TAG, "forceRefresh error", e)
                callback?.onComplete(false)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal — orchestrate the catalog
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Dispatch one task per FRED series, ONE task for all BLS series combined,
     * and one per BEA series. The callback fires exactly once, after every task
     * has finished (success or error).
     */
    private fun fetchEntries(
        dao: EconomicHistoryDao,
        entries: List<Entry>,
        now: Long,
        callback: RefreshCallback?
    ) {
        val fred = ArrayList<Entry>()
        val bls = ArrayList<Entry>()
        val bea = ArrayList<Entry>()
        for (e in entries) {
            when (e.apiSource) {
                ApiSource.FRED -> fred.add(e)
                ApiSource.BLS -> bls.add(e)
                ApiSource.BEA -> bea.add(e)
                // Mirror-written by TreasuryYieldRepository; never fetched here.
                ApiSource.TREASURY -> {}
            }
        }

        val total = fred.size + (if (bls.isEmpty()) 0 else 1) + bea.size
        if (total == 0) {
            Log.d(TAG, "Nothing to fetch.")
            callback?.onComplete(false)
            return
        }

        val remaining = AtomicInteger(total)
        val successCount = AtomicInteger(0)

        val onOneDone = Runnable {
            if (remaining.decrementAndGet() == 0) {
                Log.d(TAG, "Catalog refresh finished. Succeeded: " +
                    successCount.get() + "/" + total + " requests")
                callback?.onComplete(successCount.get() > 0)
            }
        }

        for (e in fred) historyIO.execute {
            try {
                fetchFredSeries(dao, e, now, successCount)
            } catch (ex: Exception) {
                Log.e(TAG, "FRED task error for " + e.id, ex)
            } finally {
                onOneDone.run()
            }
        }

        if (bls.isNotEmpty()) historyIO.execute {
            try {
                fetchBlsBatch(dao, bls, now, successCount)
            } catch (ex: Exception) {
                Log.e(TAG, "BLS batch task error", ex)
            } finally {
                onOneDone.run()
            }
        }

        for (e in bea) historyIO.execute {
            try {
                fetchBeaSeries(dao, e, now, successCount)
            } catch (ex: Exception) {
                Log.e(TAG, "BEA task error for " + e.id, ex)
            } finally {
                onOneDone.run()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FRED fetcher — one catalog entry
    // ──────────────────────────────────────────────────────────────────────────

    private fun fetchFredSeries(
        dao: EconomicHistoryDao,
        entry: Entry,
        now: Long,
        successCount: AtomicInteger
    ) {
        val params = HashMap<String, String>()
        params["series_id"] = entry.remoteId
        params["api_key"] = ApiConfig.FRED_API_KEY
        params["file_type"] = "json"
        params["sort_order"] = "desc"
        if (entry.limit.isNotEmpty()) params["limit"] = entry.limit
        val freq = entry.frequency
        if (freq != null) params["frequency"] = freq

        val call = RetrofitClient.getFredService().getFredData(params)
        val response = call.execute()
        val body = response.body()

        if (!response.isSuccessful || body == null) {
            Log.w(TAG, "FRED " + entry.id + " HTTP " + response.code())
            return
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
                                entry.id, entry.chartLabel, entry.category,
                                date, value.toDouble(), entry.unit, now
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
            Log.d(TAG, "Cached " + entries.size + " rows for " + entry.id)
        } else {
            Log.w(TAG, "FRED " + entry.id + " returned 0 valid observations")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BLS fetcher — every BLS catalog entry in a single POST
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * The BLS v2 API accepts multiple `seriesid` values in one request, so all
     * catalogued BLS series share a single call. The response is split back out
     * by matching each returned seriesID to its catalog entry's remoteId.
     */
    private fun fetchBlsBatch(
        dao: EconomicHistoryDao,
        blsEntries: List<Entry>,
        now: Long,
        successCount: AtomicInteger
    ) {
        val byRemoteId = HashMap<String, Entry>()
        val ids = ArrayList<String>()
        for (e in blsEntries) {
            byRemoteId[e.remoteId] = e
            ids.add(e.remoteId)
        }

        val requestBody = buildBlsRequestBody(ids)

        val call = RetrofitClient.getBlsService().getBlsData(requestBody)
        val response = call.execute()
        val blsResp = response.body()

        if (!response.isSuccessful || blsResp == null) {
            Log.w(TAG, "BLS batch (" + ids.size + " series) HTTP " + response.code())
            return
        }

        val rows = ArrayList<EconomicHistoryEntry>()
        val seriesList = blsResp.Results?.series
        var seriesSeen = 0

        if (seriesList != null) {
            for (series in seriesList) {
                val entry = byRemoteId[series.seriesID] ?: continue
                val dataList = series.data ?: continue
                seriesSeen++

                for (dp in dataList) {
                    // BLS periods: "M01"–"M12" are monthly; skip annual "M13".
                    val period = dp.period
                    val value = dp.value
                    if (period == null || !period.startsWith("M")) continue
                    if ("M13" == period) continue
                    if (value == null || !isValidValue(value)) continue

                    try {
                        val date = blsPeriodToDate(dp.year, period)
                        rows.add(
                            EconomicHistoryEntry(
                                entry.id, entry.chartLabel, entry.category,
                                date, value.toDouble(), entry.unit, now
                            )
                        )
                    } catch (ignored: NumberFormatException) {
                    }
                }
            }
        }

        if (rows.isNotEmpty()) {
            dao.insertAll(rows)
            successCount.incrementAndGet()
            Log.d(TAG, "Cached " + rows.size + " rows across " + seriesSeen +
                "/" + ids.size + " BLS series in one request")
        } else {
            Log.w(TAG, "BLS batch returned 0 valid observations for " + ids.size + " series")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BEA fetcher — GDP Growth (T10101, quarterly)
    // ──────────────────────────────────────────────────────────────────────────

    private fun fetchBeaSeries(
        dao: EconomicHistoryDao,
        entry: Entry,
        now: Long,
        successCount: AtomicInteger
    ) {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        // Past 3 years (extra year ensures we capture full 8 quarters near year boundaries)
        val years = buildYearsCsv(currentYear - 2, currentYear)

        val params = HashMap<String, String>()
        params["UserID"] = ApiConfig.BEA_API_KEY
        params["method"] = "GetData"
        params["DatasetName"] = ApiConfig.BEA_DATASET
        params["TableName"] = entry.remoteId
        params["Frequency"] = entry.frequency ?: ApiConfig.BEA_FREQUENCY
        params["Year"] = years
        params["ResultFormat"] = "JSON"

        val call = RetrofitClient.getBeaService().getBeaData(params)
        val response = call.execute()
        val beaResp = response.body()

        if (!response.isSuccessful || beaResp == null) {
            Log.w(TAG, "BEA " + entry.id + " HTTP " + response.code())
            return
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
                            entry.id, entry.chartLabel, entry.category,
                            date, value, entry.unit, now
                        )
                    )
                } catch (ignored: NumberFormatException) {
                }
            }
        }

        if (entries.isNotEmpty()) {
            dao.insertAll(entries)
            successCount.incrementAndGet()
            Log.d(TAG, "Cached " + entries.size + " rows for " + entry.id)
        } else {
            Log.w(TAG, "BEA " + entry.id + " returned 0 valid observations")
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
}
