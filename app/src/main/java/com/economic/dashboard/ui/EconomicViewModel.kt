package com.economic.dashboard.ui

import android.app.Application
import android.content.Context

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

import com.economic.dashboard.alerts.AlertValues
import com.economic.dashboard.alerts.ThresholdAlertEvaluator
import com.economic.dashboard.api.ApiConfig
import com.economic.dashboard.api.EconomicRepository
import com.economic.dashboard.cache.DashboardDataCache
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.utils.SettingsManager

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Note on accessor shape: every `getXxxData()` below stays a FUNCTION rather than
 * becoming a Kotlin property. Around 100 already-converted Kotlin call sites
 * write `vm.getCpiData()` with parens (plus MainActivity.java, still Java), and
 * Kotlin's synthetic-property sugar does not apply to a Kotlin-declared
 * `getX()` function — turning these into `val`s would break every one of them.
 * This is the mirror image of the `NewsRepository.getCachedItems()` case in
 * batch 8, where the sole Kotlin caller used property syntax and the getter
 * therefore had to become a property. The call sites decide, not the idiom.
 */
class EconomicViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = EconomicRepository()

    private val treasuryData = MutableLiveData<List<EconomicDataPoint>>()
    private val gdpData = MutableLiveData<List<EconomicDataPoint>>()
    private val employmentData = MutableLiveData<List<EconomicDataPoint>>()
    private val cpiData = MutableLiveData<List<EconomicDataPoint>>()
    private val wageData = MutableLiveData<List<EconomicDataPoint>>()
    private val fedFundsData = MutableLiveData<List<EconomicDataPoint>>()
    // Review finding 8 (R10/KD2): the hero headline reads this small daily
    // series (latest close + day-over-day delta); the monthly fedFundsData
    // above stays the popup's 5Y history. Display-only — alert evaluation
    // keeps the monthly series the popup shows (KTD7).
    private val fedFundsDailyData = MutableLiveData<List<EconomicDataPoint>>()
    private val ismPmiData = MutableLiveData<List<EconomicDataPoint>>()
    private val pceData = MutableLiveData<List<EconomicDataPoint>>()
    private val housingData = MutableLiveData<List<EconomicDataPoint>>()
    private val mbsMortgageData = MutableLiveData<List<EconomicDataPoint>>()

    // Stock Market Indices
    private val sp500Data = MutableLiveData<List<EconomicDataPoint>>()
    private val nasdaqData = MutableLiveData<List<EconomicDataPoint>>()
    private val vixData = MutableLiveData<List<EconomicDataPoint>>()

    // Bond Market Spreads
    private val baaSpreadData = MutableLiveData<List<EconomicDataPoint>>()
    private val hySpreadData = MutableLiveData<List<EconomicDataPoint>>()
    private val moveData = MutableLiveData<List<EconomicDataPoint>>()

    private val latestQuarterGdp = MutableLiveData<String>()
    private val latestQuarterLabel = MutableLiveData<String>()

    private var currentTenYearList: List<EconomicDataPoint>? = null
    private var currentTwoYearList: List<EconomicDataPoint>? = null
    private var currentThreeMonthList: List<EconomicDataPoint>? = null

    private val calculatedSpreadData = MutableLiveData<List<EconomicDataPoint>>()
    private val calculatedSpread3MData = MutableLiveData<List<EconomicDataPoint>>()

    private val isLoading = MutableLiveData(false)
    private val errorMsg = MutableLiveData<String>()
    /** "as of …" label shown while cached (stale) values are on screen; null once fresh. */
    private val cacheAsOf = MutableLiveData<String?>()
    /**
     * One-shot summary posted when a refresh settles with at least one failed
     * series, so a dead network is reported instead of looking like a no-op.
     * Consumed (set back to null) by the observer that shows it.
     */
    private val refreshFailure = MutableLiveData<String?>()

    // Per-series failure tracking so one dead API can't blank a screen.
    private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val failedSeries = MutableLiveData<Set<String>>()

    private val pendingFetches = AtomicInteger(0)
    private val lock = Any()

    fun getTreasuryData(): LiveData<List<EconomicDataPoint>> = treasuryData
    fun getGdpData(): LiveData<List<EconomicDataPoint>> = gdpData
    fun getEmploymentData(): LiveData<List<EconomicDataPoint>> = employmentData
    fun getCpiData(): LiveData<List<EconomicDataPoint>> = cpiData
    fun getWageData(): LiveData<List<EconomicDataPoint>> = wageData
    fun getFedFundsData(): LiveData<List<EconomicDataPoint>> = fedFundsData
    fun getFedFundsDailyData(): LiveData<List<EconomicDataPoint>> = fedFundsDailyData
    fun getIsmPmiData(): LiveData<List<EconomicDataPoint>> = ismPmiData
    fun getPceData(): LiveData<List<EconomicDataPoint>> = pceData
    fun getHousingData(): LiveData<List<EconomicDataPoint>> = housingData
    fun getMbsMortgageData(): LiveData<List<EconomicDataPoint>> = mbsMortgageData
    fun getSp500Data(): LiveData<List<EconomicDataPoint>> = sp500Data
    fun getNasdaqData(): LiveData<List<EconomicDataPoint>> = nasdaqData
    fun getVixData(): LiveData<List<EconomicDataPoint>> = vixData
    fun getBaaSpreadData(): LiveData<List<EconomicDataPoint>> = baaSpreadData
    fun getHySpreadData(): LiveData<List<EconomicDataPoint>> = hySpreadData
    fun getMoveData(): LiveData<List<EconomicDataPoint>> = moveData
    fun getCalculatedSpreadData(): LiveData<List<EconomicDataPoint>> = calculatedSpreadData
    fun getCalculatedSpread3MData(): LiveData<List<EconomicDataPoint>> = calculatedSpread3MData
    fun getLatestQuarterGdp(): LiveData<String> = latestQuarterGdp
    fun getLatestQuarterLabel(): LiveData<String> = latestQuarterLabel
    fun getIsLoading(): LiveData<Boolean> = isLoading
    fun getErrorMsg(): LiveData<String> = errorMsg
    fun getCacheAsOf(): LiveData<String?> = cacheAsOf
    fun getRefreshFailure(): LiveData<String?> = refreshFailure

    /** Clears the one-shot failure message once it has been shown. */
    fun consumeRefreshFailure() {
        if (refreshFailure.value != null) refreshFailure.value = null
    }

    fun getFailedSeries(): LiveData<Set<String>> = failedSeries

    /** True if a cached snapshot exists — used to skip the skeleton on warm launch. */
    fun hasCache(): Boolean {
        return DashboardDataCache.hasAny(getApplication<Application>())
    }

    private fun markFailed(key: String) {
        // Same already-changed guard as markRecovered: an outage re-failing the
        // same series shouldn't re-fire every retry-chip observer per refresh.
        if (failed.add(key)) failedSeries.postValue(HashSet(failed))
    }

    private fun markRecovered(key: String) {
        if (failed.remove(key)) failedSeries.postValue(HashSet(failed))
    }

    /**
     * Re-fetch a single series after a failure, without disturbing the other
     * cards. Keeps the pending counter balanced so the refresh spinner behaves.
     */
    fun retrySeries(key: String) {
        isLoading.postValue(true)
        pendingFetches.incrementAndGet()
        when (key) {
            CACHE_TREASURY -> fetchTreasury()
            CACHE_FED_FUNDS -> fetchFedFunds()
            CACHE_FED_FUNDS_DAILY -> fetchFedFundsDaily()
            CACHE_EMPLOYMENT -> fetchEmployment()
            CACHE_CPI -> fetchCPI()
            CACHE_GDP -> fetchGDP()
            CACHE_MBS -> fetchMbsMortgage()
            CACHE_VIX -> fetchVix()
            KEY_WAGES -> fetchWages()
            KEY_PCE -> fetchPCE()
            KEY_HOUSING -> fetchHousing()
            KEY_SP500 -> fetchSp500()
            KEY_NASDAQ -> fetchNasdaq()
            KEY_BAA -> fetchBaaSpread()
            KEY_HY -> fetchHySpread()
            else -> pendingFetches.decrementAndGet() // unknown key — undo
        }
    }

    /**
     * Posts the last cached values immediately so the dashboard paints in
     * <400ms on a warm launch, then sets the "as of …" label. Only touches
     * LiveData that has a cached snapshot; a first-ever launch is a no-op.
     */
    private fun primeFromCache() {
        val ctx: Context = getApplication<Application>()
        if (!DashboardDataCache.hasAny(ctx)) return
        postCached(treasuryData, CACHE_TREASURY)
        postCached(fedFundsData, CACHE_FED_FUNDS)
        postCached(fedFundsDailyData, CACHE_FED_FUNDS_DAILY)
        postCached(employmentData, CACHE_EMPLOYMENT)
        postCached(cpiData, CACHE_CPI)
        postCached(gdpData, CACHE_GDP)
        postCached(mbsMortgageData, CACHE_MBS)
        // Inflation's "Real Wage Growth" card needs wages AND CPI together. CPI
        // was already primed here but wages was not, so on any launch where the
        // live BLS wage call didn't land the card sat blank next to two
        // populated neighbours.
        postCached(wageData, KEY_WAGES)
        // Stocks is a Markets child tab, but its data is also consumed by the
        // Econ Analyst. Prime these series here so opening the analyst after a
        // lifecycle change does not lose the values that Stocks already showed.
        postCached(sp500Data, KEY_SP500)
        postCached(nasdaqData, KEY_NASDAQ)
        postCached(vixData, CACHE_VIX)
        val stamp = cacheStamp()
        if (stamp != null) cacheAsOf.postValue("Showing cached data · as of $stamp")
    }

    /** Formatted time of the last successful cache write, or null if never written. */
    private fun cacheStamp(): String? {
        val ts = DashboardDataCache.lastUpdated(getApplication<Application>())
        if (ts <= 0) return null
        return SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(ts))
    }

    /**
     * Persistent banner text for a refresh that did not fully land. The
     * timestamp is the last *successful* write, so on a total failure it still
     * points at the data actually on screen.
     */
    private fun staleLabel(failedCount: Int): String {
        val head = if (failedCount == 1) "1 series didn't refresh"
        else "$failedCount series didn't refresh"
        val stamp = cacheStamp()
        return if (stamp == null) head else "$head · showing data as of $stamp"
    }

    /** Transient message for the same condition, shown on every tab. */
    private fun failureMessage(failedCount: Int): String =
        if (failedCount == 1) "Couldn't refresh 1 series — check your connection"
        else "Couldn't refresh $failedCount series — check your connection"

    private fun postCached(live: MutableLiveData<List<EconomicDataPoint>>, key: String) {
        val cached = DashboardDataCache.load(getApplication<Application>(), key)
        // Skip the repost when the snapshot equals what's already displayed
        // (structural equality — EconomicDataPoint is a data class): a manual
        // refresh otherwise rebuilds every chart for unchanged data. Cold
        // launch is unaffected (live.value is null there).
        if (cached != null && !cached.isEmpty() && live.value != cached) live.postValue(cached)
    }

    /** Posts fresh data to LiveData and writes it back to the snapshot cache. */
    private fun deliver(
        live: MutableLiveData<List<EconomicDataPoint>>, key: String,
        data: List<EconomicDataPoint>
    ) {
        live.postValue(data)
        DashboardDataCache.save(getApplication<Application>(), key, data)
        markRecovered(key)
    }

    /**
     * True once [fetchAllData] has run at least once for this ViewModel
     * instance. The ViewModel survives an Activity recreate (theme toggle,
     * rotation) via the normal ViewModelStore mechanism, so its LiveData
     * already holds the current values across that recreate — callers use
     * this to skip a redundant [fetchAllData] (see [MainActivity.onCreate]),
     * which would otherwise re-run [primeFromCache]'s synchronous
     * SharedPreferences reads and re-`postValue` the same data, forcing every
     * observing chart/card to rebuild for nothing right as the new Activity
     * is standing up — the main contributor to the multi-second freeze a
     * theme switch used to cause.
     */
    private var hasFetchedOnce = false
    fun hasFetchedOnce(): Boolean = hasFetchedOnce

    fun fetchAllData() {
        hasFetchedOnce = true
        // Cache-first: paint the last known values before the network returns.
        primeFromCache()
        isLoading.postValue(true)
        synchronized(lock) {
            currentTenYearList = null; currentTwoYearList = null; currentThreeMonthList = null
        }
        // Build the task list first so the pending count is always derived from
        // the actual number of fetches — no hardcoded magic number to maintain.
        val fetchTasks: List<() -> Unit> = listOf(
            ::fetchTreasury, ::fetchGDP, ::fetchEmployment, ::fetchCPI, ::fetchWages,
            ::fetchFedFunds, ::fetchFedFundsDaily, ::fetchTenYearHistory,
            ::fetchTwoYearHistory, ::fetchThreeMonthHistory,
            ::fetchPCE, ::fetchHousing, ::fetchMbsMortgage,
            ::fetchSp500, ::fetchNasdaq, ::fetchVix,
            ::fetchBaaSpread, ::fetchHySpread)
        pendingFetches.set(fetchTasks.size)
        for (task in fetchTasks) task()
    }

    private fun checkAllDone() {
        if (pendingFetches.decrementAndGet() <= 0) {
            isLoading.postValue(false)
            // Report the outcome, not just the end of the spinner. fetchAllData()
            // primes every card from the snapshot cache BEFORE touching the
            // network, so a refresh in which every series failed repaints the
            // same numbers and is otherwise indistinguishable from one that
            // succeeded. Only drop the "showing cached data" label when the
            // data behind it was actually replaced.
            val failedCount = failed.size
            if (failedCount == 0) {
                cacheAsOf.postValue(null)
            } else {
                cacheAsOf.postValue(staleLabel(failedCount))
                refreshFailure.postValue(failureMessage(failedCount))
            }
            // TICKET-24: evaluate the user's threshold alert rules against the
            // freshly fetched values (keys that failed are simply absent).
            evaluateAlertRules()
        }
    }

    /** TICKET-24: latest value per series key → threshold rule evaluation.
     *  The key → value contract lives in [AlertValues.build] (pure, JVM-tested)
     *  so every popup alertSeriesKey is guaranteed a value in popup-displayed
     *  units; this method only supplies the LiveData snapshots. */
    private fun evaluateAlertRules() {
        val latest = AlertValues.build(
            fedFunds = fedFundsData.value,
            gdp = gdpData.value,
            employment = employmentData.value,
            cpi = cpiData.value,
            vix = vixData.value,
            mbsMortgage = mbsMortgageData.value,
            wages = wageData.value,
            pce = pceData.value,
            housing = housingData.value,
            sp500 = sp500Data.value,
            nasdaq = nasdaqData.value,
            baaSpread = baaSpreadData.value,
            hySpread = hySpreadData.value,
            treasury = treasuryData.value,
            spread2y = calculatedSpreadData.value)
        ThresholdAlertEvaluator.evaluate(getApplication<Application>(), latest)
    }

    fun fetchFedFunds() {
        // Monthly, 60 points (5Y) — was "24"/daily (≈24 business days, ~1 month),
        // which made every popup timeframe button (3M/6M/1Y/5Y) show the exact
        // same handful of points since the whole series already fit in the
        // shortest window. Matches the treasury-maturity histories' convention.
        repository.fetchFredData(ApiConfig.FRED_FED_FUNDS, "Federal Funds Effective Rate", "60", "m", "Interest Rates",
            object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
                override fun onSuccess(data: List<EconomicDataPoint>) { deliver(fedFundsData, CACHE_FED_FUNDS, data); checkAllDone() }
                override fun onError(error: String) { errorMsg.postValue("Fed Funds: $error"); markFailed(CACHE_FED_FUNDS); checkAllDone() }
            })
    }

    /** Review finding 8 (R10): small native-cadence (daily) fetch for the hero
     *  headline — `frequency = null` omits the param so FRED serves daily DFF.
     *  ~30 points ≈ one month of closes, enough for latest + day-over-day. */
    fun fetchFedFundsDaily() {
        repository.fetchFredData(ApiConfig.FRED_FED_FUNDS, "Federal Funds Effective Rate", "30", null, "Interest Rates",
            object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
                override fun onSuccess(data: List<EconomicDataPoint>) { deliver(fedFundsDailyData, CACHE_FED_FUNDS_DAILY, data); checkAllDone() }
                override fun onError(error: String) { errorMsg.postValue("Fed Funds daily: $error"); markFailed(CACHE_FED_FUNDS_DAILY); checkAllDone() }
            })
    }

    fun fetchTenYearHistory() {
        repository.fetchFredData(ApiConfig.FRED_10Y_MATURITY, "10-Year Maturity", "36", "m", "Interest Rates",
            object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
                override fun onSuccess(data: List<EconomicDataPoint>) {
                    synchronized(lock) { currentTenYearList = data; combineForSpread(); combineFor3MSpread() }
                    checkAllDone()
                }
                override fun onError(error: String) { errorMsg.postValue("10Y: $error"); checkAllDone() }
            })
    }

    fun fetchTwoYearHistory() {
        repository.fetchFredData(ApiConfig.FRED_2Y_MATURITY, "2-Year Maturity", "36", "m", "Interest Rates",
            object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
                override fun onSuccess(data: List<EconomicDataPoint>) {
                    synchronized(lock) { currentTwoYearList = data; combineForSpread() }
                    checkAllDone()
                }
                override fun onError(error: String) { errorMsg.postValue("2Y: $error"); checkAllDone() }
            })
    }

    fun fetchThreeMonthHistory() {
        repository.fetchFredData(ApiConfig.FRED_3M_MATURITY, "3-Month Maturity", "36", "m", "Interest Rates",
            object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
                override fun onSuccess(data: List<EconomicDataPoint>) {
                    synchronized(lock) { currentThreeMonthList = data; combineFor3MSpread() }
                    checkAllDone()
                }
                override fun onError(error: String) { errorMsg.postValue("3M: $error"); checkAllDone() }
            })
    }

    fun fetchPCE() {
        repository.fetchPCEData(object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
            override fun onSuccess(data: List<EconomicDataPoint>) { pceData.postValue(data); markRecovered(KEY_PCE); checkAllDone() }
            override fun onError(error: String) { errorMsg.postValue("PCE: $error"); markFailed(KEY_PCE); checkAllDone() }
        })
    }

    fun fetchHousing() {
        repository.fetchHousingData(object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
            override fun onSuccess(data: List<EconomicDataPoint>) { housingData.postValue(data); markRecovered(KEY_HOUSING); checkAllDone() }
            override fun onError(error: String) { errorMsg.postValue("Housing: $error"); markFailed(KEY_HOUSING); checkAllDone() }
        })
    }

    fun fetchMbsMortgage() {
        repository.fetchMbsMortgageData(object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
            override fun onSuccess(data: List<EconomicDataPoint>) { deliver(mbsMortgageData, CACHE_MBS, data); checkAllDone() }
            override fun onError(error: String) { errorMsg.postValue("MBS: $error"); markFailed(CACHE_MBS); checkAllDone() }
        })
    }

    private fun combineForSpread() {
        val tens = currentTenYearList ?: return
        val twos = currentTwoYearList ?: return
        val results = ArrayList<EconomicDataPoint>()
        for (t in tens)
            for (w in twos)
                if (t.date == w.date) {
                    results.add(EconomicDataPoint("Calculated", "Treasury", "10Y-2Y Spread", t.date, t.value - w.value, "%")); break
                }
        results.sortBy { it.date }
        calculatedSpreadData.postValue(results)
    }

    private fun combineFor3MSpread() {
        val tens = currentTenYearList ?: return
        val threes = currentThreeMonthList ?: return
        val results = ArrayList<EconomicDataPoint>()
        for (t in tens)
            for (w in threes)
                if (t.date == w.date) {
                    results.add(EconomicDataPoint("Calculated", "Treasury", "10Y-3M Spread", t.date, t.value - w.value, "%")); break
                }
        results.sortBy { it.date }
        calculatedSpread3MData.postValue(results)
    }

    fun fetchTreasury() {
        repository.fetchTreasuryRates(object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
            override fun onSuccess(data: List<EconomicDataPoint>) { deliver(treasuryData, CACHE_TREASURY, data); checkAllDone() }
            override fun onError(error: String) { errorMsg.postValue("Treasury: $error"); markFailed(CACHE_TREASURY); checkAllDone() }
        })
    }

    fun fetchGDP() {
        repository.fetchGDPData(object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
            override fun onSuccess(data: List<EconomicDataPoint>) { deliver(gdpData, CACHE_GDP, data); updateLatestQuarter(data); checkAllDone() }
            override fun onError(error: String) { errorMsg.postValue("GDP: $error"); markFailed(CACHE_GDP); checkAllDone() }
        })
    }

    fun fetchEmployment() {
        repository.fetchEmploymentData(object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
            override fun onSuccess(data: List<EconomicDataPoint>) { deliver(employmentData, CACHE_EMPLOYMENT, data); checkAllDone() }
            override fun onError(error: String) { errorMsg.postValue("Employment: $error"); markFailed(CACHE_EMPLOYMENT); checkAllDone() }
        })
    }

    fun fetchCPI() {
        repository.fetchCPIData(object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
            override fun onSuccess(data: List<EconomicDataPoint>) { deliver(cpiData, CACHE_CPI, data); checkAllDone() }
            override fun onError(error: String) { errorMsg.postValue("CPI: $error"); markFailed(CACHE_CPI); checkAllDone() }
        })
    }

    fun fetchWages() {
        repository.fetchWageData(object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
            override fun onSuccess(data: List<EconomicDataPoint>) { deliver(wageData, KEY_WAGES, data); checkAllDone() }
            override fun onError(error: String) { errorMsg.postValue("Wages: $error"); markFailed(KEY_WAGES); checkAllDone() }
        })
    }

    fun fetchSp500() {
        // SP500 on FRED is a weekly series — "w" is the correct frequency
        repository.fetchFredData(ApiConfig.FRED_SP500, "S&P 500 Index", ApiConfig.STOCK_INDICES_LIMIT, "w", "Stocks",
            object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
                override fun onSuccess(data: List<EconomicDataPoint>) { deliver(sp500Data, KEY_SP500, data); checkAllDone() }
                override fun onError(error: String) { errorMsg.postValue("S&P 500: $error"); markFailed(KEY_SP500); checkAllDone() }
            })
    }

    fun fetchNasdaq() {
        // NASDAQCOM on FRED is a monthly series — "m" is the correct frequency
        repository.fetchFredData(ApiConfig.FRED_NASDAQ, "Nasdaq Composite Index", ApiConfig.STOCK_INDICES_LIMIT, "m", "Stocks",
            object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
                override fun onSuccess(data: List<EconomicDataPoint>) { deliver(nasdaqData, KEY_NASDAQ, data); checkAllDone() }
                override fun onError(error: String) { errorMsg.postValue("Nasdaq: $error"); markFailed(KEY_NASDAQ); checkAllDone() }
            })
    }

    fun fetchVix() {
        // VIXCLS on FRED is daily — "d" is correct
        repository.fetchFredData(ApiConfig.FRED_VIX, "VIX Volatility Index", ApiConfig.STOCK_INDICES_LIMIT, "d", "Volatility",
            object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
                override fun onSuccess(data: List<EconomicDataPoint>) { deliver(vixData, CACHE_VIX, data); checkAllDone() }
                override fun onError(error: String) { errorMsg.postValue("VIX: $error"); markFailed(CACHE_VIX); checkAllDone() }
            })
    }

    fun fetchBaaSpread() {
        repository.fetchFredData(ApiConfig.FRED_BAA_SPREAD, "BAA Corporate Spread", ApiConfig.BOND_SPREADS_LIMIT, "d", "Credit Spreads",
            object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
                override fun onSuccess(data: List<EconomicDataPoint>) { baaSpreadData.postValue(data); markRecovered(KEY_BAA); checkAllDone() }
                override fun onError(error: String) { errorMsg.postValue("BAA: $error"); markFailed(KEY_BAA); checkAllDone() }
            })
    }

    fun fetchHySpread() {
        repository.fetchFredData(ApiConfig.FRED_HY_SPREAD, "High Yield Spread", ApiConfig.BOND_SPREADS_LIMIT, "d", "Credit Spreads",
            object : EconomicRepository.DataCallback<List<EconomicDataPoint>> {
                override fun onSuccess(data: List<EconomicDataPoint>) { hySpreadData.postValue(data); markRecovered(KEY_HY); checkAllDone() }
                override fun onError(error: String) { errorMsg.postValue("HY: $error"); markFailed(KEY_HY); checkAllDone() }
            })
    }
    // fetchMove() removed — MOVE Index (bond market volatility) is not available via the FRED API.
    // moveData LiveData is retained in case a future data source is wired in.

    private fun updateLatestQuarter(data: List<EconomicDataPoint>) {
        val rows = filterBySeries(data, "Gross domestic product")
        if (rows.isEmpty()) return
        val latest = rows[rows.size - 1]
        latestQuarterGdp.postValue(String.format(Locale.US, "%.2f%%", latest.value))
        latestQuarterLabel.postValue(formatQuarterLabel(latest.date))
    }

    private fun formatQuarterLabel(date: String?): String {
        if (date == null || date.length < 7) return ""
        val year = date.substring(0, 4)
        val month = date.substring(5, 7)
        val q = when (month) {
            "01" -> "Q1"
            "04" -> "Q2"
            "07" -> "Q3"
            "10" -> "Q4"
            else -> "Q?"
        }
        return "$q $year"
    }

    companion object {
        // ── Series keys — used for the snapshot cache (DashboardDataCache),
        //    per-series failure tracking, and single-series retry. Public so the
        //    dashboard can map a failed key back to its card. ─────────────────────
        //    `const val` (not @JvmField): MainActivity.java still reads
        //    EconomicViewModel.CACHE_FED_FUNDS, and const emits a real static field.
        const val CACHE_TREASURY = "treasury"
        const val CACHE_FED_FUNDS = "fed_funds"
        // Review finding 8: hero's daily series, cached/retried independently
        // of the monthly popup history above.
        const val CACHE_FED_FUNDS_DAILY = "fed_funds_daily"
        const val CACHE_EMPLOYMENT = "employment"
        const val CACHE_CPI = "cpi"
        const val CACHE_GDP = "gdp"
        const val CACHE_MBS = "mbs_mortgage"
        const val CACHE_VIX = "vix"
        // TICKET-18: additional leaf-screen series get per-key failure tracking +
        // single-series retry too (these aren't snapshot-cached, only tracked so a
        // leaf card can show the "tap to retry" chip when its own fetch fails).
        const val KEY_WAGES = "wages"
        const val KEY_PCE = "pce"
        const val KEY_HOUSING = "housing"
        const val KEY_SP500 = "sp500"
        const val KEY_NASDAQ = "nasdaq"
        const val KEY_BAA = "baa_spread"
        const val KEY_HY = "hy_spread"
        const val KEY_LABOR = "labor_force"
        // TICKET-24: alert-only key for the computed 10Y-3M spread (not a fetched
        // series — derived from the Treasury maturity histories).
        const val ALERT_SPREAD_3M = "spread_10y3m"
        // P6 TICKET-35: alert-only keys for the computed 10Y-2Y spread and the
        // Housing mortgage-10Y spread.
        const val ALERT_SPREAD_2Y = "spread_10y2y"
        const val ALERT_MORTGAGE_SPREAD = "mortgage_spread"
        // Yield-row popups (2026-09-03): alert keys for the Yields table's other
        // maturities. CACHE_TREASURY stays the 10Y key so existing 10Y rules keep
        // evaluating; a 2Y rule saved from the 2Y popup must never grade 10Y.
        const val ALERT_YIELD_1M = "yield_1m"
        const val ALERT_YIELD_3M = "yield_3m"
        const val ALERT_YIELD_2Y = "yield_2y"
        const val ALERT_YIELD_30Y = "yield_30y"

        // Latest-value/YoY helpers formerly here moved to alerts/AlertValues.kt
        // (review fix): the alert key → value contract is now pure and JVM-tested.

        @JvmStatic
        fun getLatest(data: List<EconomicDataPoint>?, series: String): EconomicDataPoint? {
            if (data == null) return null
            var latest: EconomicDataPoint? = null
            for (p in data) {
                val current = latest
                if (p.series == series && (current == null || p.date.compareTo(current.date) > 0))
                    latest = p
            }
            return latest
        }

        @JvmStatic
        fun filterBySeries(data: List<EconomicDataPoint>?, series: String): List<EconomicDataPoint> {
            val result = ArrayList<EconomicDataPoint>()
            if (data != null) for (p in data) if (p.series == series) result.add(p)
            result.sortBy { it.date }
            return result
        }

        /**
         * Returns the tail of a date-sorted (yyyy-MM-dd) series that falls within the
         * given number of months of its most recent point. This standardizes the
         * x-axis window across every chart in the app. Falls back to the full list
         * when the window would leave fewer than two points, so sparse series
         * (e.g. quarterly GDP) stay drawable, and when a date fails to parse.
         *
         * Takes and returns a NON-NULL list, unlike the Java. Every caller passes
         * the result of [filterBySeries], which never returns null, and the
         * ~10 already-converted fragments assign the result straight back into a
         * non-null `List` — a nullable return would break all of them. The Java's
         * `if (sortedRows == null) return sortedRows` branch was therefore
         * unreachable and is dropped, along with the now-dead `date == null`
         * guards (EconomicDataPoint.date is non-null since batch 3).
         */
        @JvmStatic
        fun filterByMonths(sortedRows: List<EconomicDataPoint>, months: Int): List<EconomicDataPoint> {
            if (sortedRows.size < 2 || months <= 0) return sortedRows
            val latest = sortedRows[sortedRows.size - 1].date
            if (latest.length < 10) return sortedRows
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val d = sdf.parse(latest) ?: return sortedRows
                val cal = Calendar.getInstance()
                cal.time = d
                cal.add(Calendar.MONTH, -months)
                val cutoff = sdf.format(cal.time)
                val result = ArrayList<EconomicDataPoint>()
                for (p in sortedRows)
                    if (p.date.compareTo(cutoff) >= 0) result.add(p)
                if (result.size >= 2) result else sortedRows
            } catch (e: ParseException) {
                sortedRows
            }
        }

        /** Per-chart timeframe window (TICKET-11): honors that chart's own range. */
        @JvmStatic
        fun filterByTimeframe(ctx: Context, sortedRows: List<EconomicDataPoint>, chartId: String): List<EconomicDataPoint> {
            return filterByMonths(sortedRows, SettingsManager.getChartTimeframeMonths(ctx, chartId))
        }

        @JvmStatic
        fun calculatePercentile(data: List<EconomicDataPoint>?, series: String, currentValue: Double): Int {
            val rows = filterBySeries(data, series)
            if (rows.size < 5) return -1
            var count = 0
            for (p in rows) if (p.value <= currentValue) count++
            return Math.round((count * 100.0) / rows.size).toInt()
        }

        @JvmStatic
        fun formatPercentile(pct: Int): String {
            if (pct < 0) return ""
            val s = if (pct == 11 || pct == 12 || pct == 13) "th"
            else if (pct % 10 == 1) "st"
            else if (pct % 10 == 2) "nd"
            else if (pct % 10 == 3) "rd"
            else "th"
            return "$pct$s pct."
        }
    }
}
