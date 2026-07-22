package com.economic.dashboard.ui;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.economic.dashboard.api.ApiConfig;
import com.economic.dashboard.api.EconomicRepository;
import com.economic.dashboard.cache.DashboardDataCache;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.utils.SettingsManager;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class EconomicViewModel extends AndroidViewModel {

    // ── Series keys — used for the snapshot cache (DashboardDataCache),
    //    per-series failure tracking, and single-series retry. Public so the
    //    dashboard can map a failed key back to its card. ─────────────────────
    public static final String CACHE_TREASURY   = "treasury";
    public static final String CACHE_FED_FUNDS  = "fed_funds";
    public static final String CACHE_EMPLOYMENT = "employment";
    public static final String CACHE_CPI        = "cpi";
    public static final String CACHE_GDP        = "gdp";
    public static final String CACHE_MBS        = "mbs_mortgage";
    public static final String CACHE_VIX        = "vix";
    // TICKET-18: additional leaf-screen series get per-key failure tracking +
    // single-series retry too (these aren't snapshot-cached, only tracked so a
    // leaf card can show the "tap to retry" chip when its own fetch fails).
    public static final String KEY_WAGES        = "wages";
    public static final String KEY_PCE          = "pce";
    public static final String KEY_HOUSING      = "housing";
    public static final String KEY_SP500        = "sp500";
    public static final String KEY_NASDAQ       = "nasdaq";
    public static final String KEY_BAA          = "baa_spread";
    public static final String KEY_HY           = "hy_spread";
    // TICKET-24: alert-only key for the computed 10Y-3M spread (not a fetched
    // series — derived from the Treasury maturity histories).
    public static final String ALERT_SPREAD_3M  = "spread_10y3m";

    public EconomicViewModel(@NonNull Application application) {
        super(application);
    }

    private final EconomicRepository repository = new EconomicRepository();

    private final MutableLiveData<List<EconomicDataPoint>> treasuryData    = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> gdpData         = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> employmentData  = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> cpiData         = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> wageData        = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> fedFundsData    = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> ismPmiData      = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> fedFundsHistory = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> pceData         = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> housingData     = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> mbsMortgageData = new MutableLiveData<>();

    // Stock Market Indices
    private final MutableLiveData<List<EconomicDataPoint>> sp500Data  = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> nasdaqData = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> vixData    = new MutableLiveData<>();

    // Bond Market Spreads
    private final MutableLiveData<List<EconomicDataPoint>> baaSpreadData = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> hySpreadData  = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> moveData      = new MutableLiveData<>();

    private final MutableLiveData<String> latestQuarterGdp   = new MutableLiveData<>();
    private final MutableLiveData<String> latestQuarterLabel = new MutableLiveData<>();

    private List<EconomicDataPoint> currentTenYearList    = null;
    private List<EconomicDataPoint> currentTwoYearList    = null;
    private List<EconomicDataPoint> currentThreeMonthList = null;

    private final MutableLiveData<List<EconomicDataPoint>> calculatedSpreadData   = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> calculatedSpread3MData = new MutableLiveData<>();

    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String>  errorMsg  = new MutableLiveData<>();
    private final MutableLiveData<String>  statusMsg = new MutableLiveData<>();
    /** "as of …" label shown while cached (stale) values are on screen; null once fresh. */
    private final MutableLiveData<String>  cacheAsOf = new MutableLiveData<>();

    // ── TICKET-22: "Since you last opened" strip ─────────────────────────────
    // Latest values from the snapshot the app *opened with*, captured before
    // the fresh fetch overwrites the cache. Null on a first-ever launch.
    private Double openedFedFunds, openedCpiYoY, openedVix;
    private final MutableLiveData<String> sinceLastOpen = new MutableLiveData<>();
    /** Session-scoped dismiss flag (VM outlives the fragment view). */
    private boolean sinceLastOpenDismissed = false;

    // Per-series failure tracking so one dead API can't blank a screen.
    private final java.util.Set<String> failed =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final MutableLiveData<java.util.Set<String>> failedSeries = new MutableLiveData<>();

    private final AtomicInteger pendingFetches = new AtomicInteger(0);
    private final Object lock = new Object();

    public LiveData<List<EconomicDataPoint>> getTreasuryData()           { return treasuryData; }
    public LiveData<List<EconomicDataPoint>> getGdpData()                { return gdpData; }
    public LiveData<List<EconomicDataPoint>> getEmploymentData()         { return employmentData; }
    public LiveData<List<EconomicDataPoint>> getCpiData()                { return cpiData; }
    public LiveData<List<EconomicDataPoint>> getWageData()               { return wageData; }
    public LiveData<List<EconomicDataPoint>> getFedFundsData()           { return fedFundsData; }
    public LiveData<List<EconomicDataPoint>> getIsmPmiData()             { return ismPmiData; }
    public LiveData<List<EconomicDataPoint>> getFedFundsHistory()        { return fedFundsHistory; }
    public LiveData<List<EconomicDataPoint>> getPceData()                { return pceData; }
    public LiveData<List<EconomicDataPoint>> getHousingData()            { return housingData; }
    public LiveData<List<EconomicDataPoint>> getMbsMortgageData()        { return mbsMortgageData; }
    public LiveData<List<EconomicDataPoint>> getSp500Data()              { return sp500Data; }
    public LiveData<List<EconomicDataPoint>> getNasdaqData()             { return nasdaqData; }
    public LiveData<List<EconomicDataPoint>> getVixData()                { return vixData; }
    public LiveData<List<EconomicDataPoint>> getBaaSpreadData()          { return baaSpreadData; }
    public LiveData<List<EconomicDataPoint>> getHySpreadData()           { return hySpreadData; }
    public LiveData<List<EconomicDataPoint>> getMoveData()               { return moveData; }
    public LiveData<List<EconomicDataPoint>> getCalculatedSpreadData()   { return calculatedSpreadData; }
    public LiveData<List<EconomicDataPoint>> getCalculatedSpread3MData() { return calculatedSpread3MData; }
    public LiveData<String>                  getLatestQuarterGdp()       { return latestQuarterGdp; }
    public LiveData<String>                  getLatestQuarterLabel()     { return latestQuarterLabel; }
    public LiveData<Boolean>                 getIsLoading()              { return isLoading; }
    public LiveData<String>                  getErrorMsg()               { return errorMsg; }
    public LiveData<String>                  getStatusMsg()              { return statusMsg; }
    public LiveData<String>                  getCacheAsOf()              { return cacheAsOf; }
    public LiveData<String>                  getSinceLastOpen()          { return sinceLastOpen; }
    public boolean isSinceLastOpenDismissed()                            { return sinceLastOpenDismissed; }
    public void dismissSinceLastOpen() {
        sinceLastOpenDismissed = true;
        sinceLastOpen.postValue(null);
    }
    public LiveData<java.util.Set<String>>   getFailedSeries()           { return failedSeries; }

    /** True if a cached snapshot exists — used to skip the skeleton on warm launch. */
    public boolean hasCache() {
        return DashboardDataCache.hasAny(getApplication());
    }

    private void markFailed(String key) {
        failed.add(key);
        failedSeries.postValue(new java.util.HashSet<>(failed));
    }

    private void markRecovered(String key) {
        if (failed.remove(key)) failedSeries.postValue(new java.util.HashSet<>(failed));
    }

    /**
     * Re-fetch a single series after a failure, without disturbing the other
     * cards. Keeps the pending counter balanced so the refresh spinner behaves.
     */
    public void retrySeries(String key) {
        isLoading.postValue(true);
        pendingFetches.incrementAndGet();
        switch (key) {
            case CACHE_TREASURY:   fetchTreasury();   break;
            case CACHE_FED_FUNDS:  fetchFedFunds();   break;
            case CACHE_EMPLOYMENT: fetchEmployment(); break;
            case CACHE_CPI:        fetchCPI();        break;
            case CACHE_GDP:        fetchGDP();        break;
            case CACHE_MBS:        fetchMbsMortgage();break;
            case CACHE_VIX:        fetchVix();        break;
            case KEY_WAGES:        fetchWages();      break;
            case KEY_PCE:          fetchPCE();        break;
            case KEY_HOUSING:      fetchHousing();    break;
            case KEY_SP500:        fetchSp500();      break;
            case KEY_NASDAQ:       fetchNasdaq();     break;
            case KEY_BAA:          fetchBaaSpread();  break;
            case KEY_HY:           fetchHySpread();   break;
            default: pendingFetches.decrementAndGet(); break; // unknown key — undo
        }
    }

    /**
     * Posts the last cached values immediately so the dashboard paints in
     * <400ms on a warm launch, then sets the "as of …" label. Only touches
     * LiveData that has a cached snapshot; a first-ever launch is a no-op.
     */
    private void primeFromCache() {
        Context ctx = getApplication();
        if (!DashboardDataCache.hasAny(ctx)) return;
        postCached(treasuryData,    CACHE_TREASURY);
        postCached(fedFundsData,    CACHE_FED_FUNDS);
        postCached(employmentData,  CACHE_EMPLOYMENT);
        postCached(cpiData,         CACHE_CPI);
        postCached(gdpData,         CACHE_GDP);
        postCached(mbsMortgageData, CACHE_MBS);
        postCached(vixData,         CACHE_VIX);
        long ts = DashboardDataCache.lastUpdated(ctx);
        if (ts > 0) {
            String stamp = new SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new Date(ts));
            cacheAsOf.postValue("Showing cached data · as of " + stamp);
        }
    }

    private void postCached(MutableLiveData<List<EconomicDataPoint>> live, String key) {
        List<EconomicDataPoint> cached = DashboardDataCache.load(getApplication(), key);
        if (cached != null && !cached.isEmpty()) live.postValue(cached);
    }

    /** Posts fresh data to LiveData and writes it back to the snapshot cache. */
    private void deliver(MutableLiveData<List<EconomicDataPoint>> live, String key,
                         List<EconomicDataPoint> data) {
        live.postValue(data);
        DashboardDataCache.save(getApplication(), key, data);
        markRecovered(key);
    }

    public void fetchAllData() {
        // TICKET-22: remember what the app opened with, before deliver()
        // overwrites the snapshot cache with fresh values.
        captureOpenedWithSnapshot();
        // Cache-first: paint the last known values before the network returns.
        primeFromCache();
        isLoading.postValue(true);
        synchronized (lock) {
            currentTenYearList = null; currentTwoYearList = null; currentThreeMonthList = null;
        }
        // Build the task list first so the pending count is always derived from
        // the actual number of fetches — no hardcoded magic number to maintain.
        List<Runnable> fetchTasks = Arrays.asList(
                this::fetchTreasury, this::fetchGDP, this::fetchEmployment, this::fetchCPI, this::fetchWages,
                this::fetchFedFunds, this::fetchFedFundsHistory, this::fetchTenYearHistory,
                this::fetchTwoYearHistory, this::fetchThreeMonthHistory,
                this::fetchPCE, this::fetchHousing, this::fetchMbsMortgage,
                this::fetchSp500, this::fetchNasdaq, this::fetchVix,
                this::fetchBaaSpread, this::fetchHySpread);
        pendingFetches.set(fetchTasks.size());
        for (Runnable task : fetchTasks) task.run();
    }

    private void checkAllDone() {
        if (pendingFetches.decrementAndGet() <= 0) {
            isLoading.postValue(false);
            // Fresh data has landed — drop the "showing cached data" label.
            cacheAsOf.postValue(null);
            // TICKET-22: all fetches settled — diff fresh vs. opened-with values.
            computeSinceLastOpen();
            // TICKET-24: evaluate the user's threshold alert rules against the
            // freshly fetched values (keys that failed are simply absent).
            evaluateAlertRules();
        }
    }

    /** TICKET-24: latest value per series key → threshold rule evaluation. */
    private void evaluateAlertRules() {
        java.util.Map<String, Double> latest = new java.util.HashMap<>();
        putIfPresent(latest, CACHE_FED_FUNDS,  latestOf(fedFundsData.getValue(),    "Federal Funds Effective Rate"));
        putIfPresent(latest, CACHE_GDP,        latestOf(gdpData.getValue(),         "Gross domestic product"));
        putIfPresent(latest, CACHE_EMPLOYMENT, latestOf(employmentData.getValue(),  "Unemployment Rate"));
        putIfPresent(latest, CACHE_CPI,        cpiYoyOf(cpiData.getValue()));   // CPI = YoY %
        putIfPresent(latest, CACHE_VIX,        latestOf(vixData.getValue(),         "VIX Volatility Index"));
        putIfPresent(latest, CACHE_MBS,        latestOf(mbsMortgageData.getValue(), "30-Yr Mortgage Rate"));
        putIfPresent(latest, KEY_WAGES,        latestOf(wageData.getValue(),        "Average Hourly Earnings - Private"));
        putIfPresent(latest, KEY_PCE,          latestOf(pceData.getValue(),         "PCE Price Index"));
        putIfPresent(latest, KEY_HOUSING,      latestOf(housingData.getValue(),     "Housing Starts"));
        putIfPresent(latest, KEY_SP500,        latestOf(sp500Data.getValue(),       "S&P 500 Index"));
        putIfPresent(latest, KEY_NASDAQ,       latestOf(nasdaqData.getValue(),      "Nasdaq Composite Index"));
        putIfPresent(latest, KEY_BAA,          latestOf(baaSpreadData.getValue(),   "BAA Corporate Spread"));
        putIfPresent(latest, KEY_HY,           latestOf(hySpreadData.getValue(),    "High Yield Spread"));
        putIfPresent(latest, ALERT_SPREAD_3M,  latestOf(calculatedSpread3MData.getValue()));
        com.economic.dashboard.alerts.ThresholdAlertEvaluator.evaluate(getApplication(), latest);
    }

    private static void putIfPresent(java.util.Map<String, Double> map, String key, Double v) {
        if (v != null) map.put(key, v);
    }

    // ── TICKET-22 helpers ────────────────────────────────────────────────────

    /** Latest numeric value of a series list, or null if absent/empty. */
    private static Double latestOf(List<EconomicDataPoint> rows) {
        if (rows == null || rows.isEmpty()) return null;
        return rows.get(rows.size() - 1).getValue();
    }

    /** Latest value of one named series inside a (possibly multi-series) list. */
    private static Double latestOf(List<EconomicDataPoint> rows, String seriesName) {
        if (rows == null || rows.isEmpty()) return null;
        return latestOf(filterBySeries(rows, seriesName));
    }

    /** CPI YoY % from a monthly index series (needs ≥13 rows), or null. */
    private static Double yoyOf(List<EconomicDataPoint> rows) {
        if (rows == null || rows.size() < 13) return null;
        double latest = rows.get(rows.size() - 1).getValue();
        double base   = rows.get(rows.size() - 13).getValue();
        if (base == 0) return null;
        return ((latest - base) / base) * 100.0;
    }

    /** CPI YoY % from the CPI-U series within a multi-series CPI list. */
    private static Double cpiYoyOf(List<EconomicDataPoint> rows) {
        if (rows == null || rows.isEmpty()) return null;
        return yoyOf(filterBySeries(rows, "CPI-U All Items"));
    }

    /** Snapshot the headline values the app opened with (warm launch only). */
    private void captureOpenedWithSnapshot() {
        Context ctx = getApplication();
        if (!DashboardDataCache.hasAny(ctx)) return;          // first-ever launch
        if (openedFedFunds != null || openedCpiYoY != null || openedVix != null)
            return;                                            // already captured this session
        openedFedFunds = latestOf(DashboardDataCache.load(ctx, CACHE_FED_FUNDS),
                "Federal Funds Effective Rate");
        openedCpiYoY   = cpiYoyOf(DashboardDataCache.load(ctx, CACHE_CPI));
        openedVix      = latestOf(DashboardDataCache.load(ctx, CACHE_VIX),
                "VIX Volatility Index");
    }

    /**
     * Builds "Since last open: Fed Funds ▲ +0.25pp · CPI • unchanged · VIX ▼ −1.2"
     * from the opened-with snapshot vs. the fresh values now in LiveData.
     * Posts null (strip hidden) on first-ever launch or after dismiss.
     */
    private void computeSinceLastOpen() {
        if (sinceLastOpenDismissed) { sinceLastOpen.postValue(null); return; }
        List<String> parts = new ArrayList<>();
        appendDeltaPart(parts, "Fed Funds", openedFedFunds,
                latestOf(fedFundsData.getValue(), "Federal Funds Effective Rate"), "%.2fpp");
        appendDeltaPart(parts, "CPI",       openedCpiYoY,   cpiYoyOf(cpiData.getValue()), "%.2fpp");
        appendDeltaPart(parts, "VIX",       openedVix,
                latestOf(vixData.getValue(), "VIX Volatility Index"), "%.1f");
        if (parts.isEmpty()) { sinceLastOpen.postValue(null); return; }
        StringBuilder sb = new StringBuilder("Since last open: ");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(parts.get(i));
        }
        sinceLastOpen.postValue(sb.toString());
    }

    private static void appendDeltaPart(List<String> parts, String label,
                                        Double opened, Double fresh, String fmt) {
        if (opened == null || fresh == null) return;
        double delta = fresh - opened;
        if (Math.abs(delta) < 0.005) {
            parts.add(label + " unchanged");
        } else {
            parts.add(label + " " + com.economic.dashboard.utils.DeltaFormatter.format(delta, fmt));
        }
    }

    public void fetchFedFunds() {
        repository.fetchFredData(ApiConfig.FRED_FED_FUNDS, "Federal Funds Effective Rate", "24", null, "Interest Rates",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) { deliver(fedFundsData, CACHE_FED_FUNDS, d); checkAllDone(); }
                    @Override public void onError(String e) { errorMsg.postValue("Fed Funds: "+e); markFailed(CACHE_FED_FUNDS); checkAllDone(); }
                });
    }

    public void fetchFedFundsHistory() {
        repository.fetchFredData(ApiConfig.FRED_FED_FUNDS, "Fed Funds History", "37", "m", "Interest Rates",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) { fedFundsHistory.postValue(d); checkAllDone(); }
                    @Override public void onError(String e) { errorMsg.postValue("Fed History: "+e); checkAllDone(); }
                });
    }

    public void fetchTenYearHistory() {
        repository.fetchFredData(ApiConfig.FRED_10Y_MATURITY, "10-Year Maturity", "36", "m", "Interest Rates",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) {
                        synchronized (lock) { currentTenYearList = d; combineForSpread(); combineFor3MSpread(); }
                        checkAllDone();
                    }
                    @Override public void onError(String e) { errorMsg.postValue("10Y: "+e); checkAllDone(); }
                });
    }

    public void fetchTwoYearHistory() {
        repository.fetchFredData(ApiConfig.FRED_2Y_MATURITY, "2-Year Maturity", "36", "m", "Interest Rates",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) {
                        synchronized (lock) { currentTwoYearList = d; combineForSpread(); }
                        checkAllDone();
                    }
                    @Override public void onError(String e) { errorMsg.postValue("2Y: "+e); checkAllDone(); }
                });
    }

    public void fetchThreeMonthHistory() {
        repository.fetchFredData(ApiConfig.FRED_3M_MATURITY, "3-Month Maturity", "36", "m", "Interest Rates",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) {
                        synchronized (lock) { currentThreeMonthList = d; combineFor3MSpread(); }
                        checkAllDone();
                    }
                    @Override public void onError(String e) { errorMsg.postValue("3M: "+e); checkAllDone(); }
                });
    }

    public void fetchPCE() {
        repository.fetchPCEData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { pceData.postValue(d); markRecovered(KEY_PCE); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("PCE: "+e); markFailed(KEY_PCE); checkAllDone(); }
        });
    }

    public void fetchHousing() {
        repository.fetchHousingData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { housingData.postValue(d); markRecovered(KEY_HOUSING); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("Housing: "+e); markFailed(KEY_HOUSING); checkAllDone(); }
        });
    }

    public void fetchMbsMortgage() {
        repository.fetchMbsMortgageData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { deliver(mbsMortgageData, CACHE_MBS, d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("MBS: "+e); markFailed(CACHE_MBS); checkAllDone(); }
        });
    }

    private void combineForSpread() {
        if (currentTenYearList == null || currentTwoYearList == null) return;
        List<EconomicDataPoint> results = new ArrayList<>();
        for (EconomicDataPoint t : currentTenYearList)
            for (EconomicDataPoint w : currentTwoYearList)
                if (t.getDate().equals(w.getDate())) {
                    results.add(new EconomicDataPoint("Calculated","Treasury","10Y-2Y Spread",t.getDate(),t.getValue()-w.getValue(),"%")); break;
                }
        results.sort((a,b)->a.getDate().compareTo(b.getDate()));
        calculatedSpreadData.postValue(results);
    }

    private void combineFor3MSpread() {
        if (currentTenYearList == null || currentThreeMonthList == null) return;
        List<EconomicDataPoint> results = new ArrayList<>();
        for (EconomicDataPoint t : currentTenYearList)
            for (EconomicDataPoint w : currentThreeMonthList)
                if (t.getDate().equals(w.getDate())) {
                    results.add(new EconomicDataPoint("Calculated","Treasury","10Y-3M Spread",t.getDate(),t.getValue()-w.getValue(),"%")); break;
                }
        results.sort((a,b)->a.getDate().compareTo(b.getDate()));
        calculatedSpread3MData.postValue(results);
    }

    public void fetchTreasury() {
        repository.fetchTreasuryRates(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { deliver(treasuryData, CACHE_TREASURY, d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("Treasury: "+e); markFailed(CACHE_TREASURY); checkAllDone(); }
        });
    }

    public void fetchGDP() {
        repository.fetchGDPData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { deliver(gdpData, CACHE_GDP, d); updateLatestQuarter(d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("GDP: "+e); markFailed(CACHE_GDP); checkAllDone(); }
        });
    }

    public void fetchEmployment() {
        repository.fetchEmploymentData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { deliver(employmentData, CACHE_EMPLOYMENT, d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("Employment: "+e); markFailed(CACHE_EMPLOYMENT); checkAllDone(); }
        });
    }

    public void fetchCPI() {
        repository.fetchCPIData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { deliver(cpiData, CACHE_CPI, d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("CPI: "+e); markFailed(CACHE_CPI); checkAllDone(); }
        });
    }

    public void fetchWages() {
        repository.fetchWageData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { wageData.postValue(d); markRecovered(KEY_WAGES); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("Wages: "+e); markFailed(KEY_WAGES); checkAllDone(); }
        });
    }

    public void fetchSp500() {
        // SP500 on FRED is a weekly series — "w" is the correct frequency
        repository.fetchFredData(ApiConfig.FRED_SP500, "S&P 500 Index", ApiConfig.STOCK_INDICES_LIMIT, "w", "Stocks",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) { sp500Data.postValue(d); markRecovered(KEY_SP500); checkAllDone(); }
                    @Override public void onError(String e) { errorMsg.postValue("S&P 500: "+e); markFailed(KEY_SP500); checkAllDone(); }
                });
    }

    public void fetchNasdaq() {
        // NASDAQCOM on FRED is a monthly series — "m" is the correct frequency
        repository.fetchFredData(ApiConfig.FRED_NASDAQ, "Nasdaq Composite Index", ApiConfig.STOCK_INDICES_LIMIT, "m", "Stocks",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) { nasdaqData.postValue(d); markRecovered(KEY_NASDAQ); checkAllDone(); }
                    @Override public void onError(String e) { errorMsg.postValue("Nasdaq: "+e); markFailed(KEY_NASDAQ); checkAllDone(); }
                });
    }

    public void fetchVix() {
        // VIXCLS on FRED is daily — "d" is correct
        repository.fetchFredData(ApiConfig.FRED_VIX, "VIX Volatility Index", ApiConfig.STOCK_INDICES_LIMIT, "d", "Volatility",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) { deliver(vixData, CACHE_VIX, d); checkAllDone(); }
                    @Override public void onError(String e) { errorMsg.postValue("VIX: "+e); markFailed(CACHE_VIX); checkAllDone(); }
                });
    }

    public void fetchBaaSpread() {
        repository.fetchFredData(ApiConfig.FRED_BAA_SPREAD, "BAA Corporate Spread", ApiConfig.BOND_SPREADS_LIMIT, "d", "Credit Spreads",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) { baaSpreadData.postValue(d); markRecovered(KEY_BAA); checkAllDone(); }
                    @Override public void onError(String e) { errorMsg.postValue("BAA: "+e); markFailed(KEY_BAA); checkAllDone(); }
                });
    }

    public void fetchHySpread() {
        repository.fetchFredData(ApiConfig.FRED_HY_SPREAD, "High Yield Spread", ApiConfig.BOND_SPREADS_LIMIT, "d", "Credit Spreads",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) { hySpreadData.postValue(d); markRecovered(KEY_HY); checkAllDone(); }
                    @Override public void onError(String e) { errorMsg.postValue("HY: "+e); markFailed(KEY_HY); checkAllDone(); }
                });
    }
    // fetchMove() removed — MOVE Index (bond market volatility) is not available via the FRED API.
    // moveData LiveData is retained in case a future data source is wired in.

    public static EconomicDataPoint getLatest(List<EconomicDataPoint> data, String series) {
        if (data == null) return null;
        EconomicDataPoint latest = null;
        for (EconomicDataPoint p : data)
            if (p.getSeries().equals(series) && (latest == null || p.getDate().compareTo(latest.getDate()) > 0))
                latest = p;
        return latest;
    }

    private void updateLatestQuarter(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = filterBySeries(data, "Gross domestic product");
        if (rows.isEmpty()) return;
        EconomicDataPoint latest = rows.get(rows.size()-1);
        latestQuarterGdp.postValue(String.format(Locale.US, "%.2f%%", latest.getValue()));
        latestQuarterLabel.postValue(formatQuarterLabel(latest.getDate()));
    }

    private String formatQuarterLabel(String date) {
        if (date == null || date.length() < 7) return "";
        String year = date.substring(0,4), month = date.substring(5,7), q;
        switch (month) { case "01": q="Q1"; break; case "04": q="Q2"; break; case "07": q="Q3"; break; case "10": q="Q4"; break; default: q="Q?"; break; }
        return q+" "+year;
    }

    public static List<EconomicDataPoint> filterBySeries(List<EconomicDataPoint> data, String series) {
        List<EconomicDataPoint> result = new ArrayList<>();
        if (data != null) for (EconomicDataPoint p : data) if (p.getSeries().equals(series)) result.add(p);
        result.sort((a,b)->a.getDate().compareTo(b.getDate()));
        return result;
    }

    /**
     * Returns the tail of a date-sorted (yyyy-MM-dd) series that falls within the
     * given number of months of its most recent point. This standardizes the
     * x-axis window across every chart in the app. Falls back to the full list
     * when the window would leave fewer than two points, so sparse series
     * (e.g. quarterly GDP) stay drawable, and when a date fails to parse.
     */
    public static List<EconomicDataPoint> filterByMonths(List<EconomicDataPoint> sortedRows, int months) {
        if (sortedRows == null || sortedRows.size() < 2 || months <= 0) return sortedRows;
        String latest = sortedRows.get(sortedRows.size() - 1).getDate();
        if (latest == null || latest.length() < 10) return sortedRows;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date d = sdf.parse(latest);
            if (d == null) return sortedRows;
            Calendar cal = Calendar.getInstance();
            cal.setTime(d);
            cal.add(Calendar.MONTH, -months);
            String cutoff = sdf.format(cal.getTime());
            List<EconomicDataPoint> result = new ArrayList<>();
            for (EconomicDataPoint p : sortedRows)
                if (p.getDate() != null && p.getDate().compareTo(cutoff) >= 0) result.add(p);
            return result.size() >= 2 ? result : sortedRows;
        } catch (ParseException e) {
            return sortedRows;
        }
    }

    /** Windows a date-sorted series using the user's chart-timeframe setting. */
    public static List<EconomicDataPoint> filterByTimeframe(Context ctx, List<EconomicDataPoint> sortedRows) {
        return filterByMonths(sortedRows, SettingsManager.getChartTimeframeMonths(ctx));
    }

    /** Per-chart timeframe window (TICKET-11): honors that chart's own range. */
    public static List<EconomicDataPoint> filterByTimeframe(Context ctx, List<EconomicDataPoint> sortedRows, String chartId) {
        return filterByMonths(sortedRows, SettingsManager.getChartTimeframeMonths(ctx, chartId));
    }

    public static int calculatePercentile(List<EconomicDataPoint> data, String series, double currentValue) {
        List<EconomicDataPoint> rows = filterBySeries(data, series);
        if (rows.size() < 5) return -1;
        int count = 0;
        for (EconomicDataPoint p : rows) if (p.getValue() <= currentValue) count++;
        return (int) Math.round((count * 100.0) / rows.size());
    }

    public static String formatPercentile(int pct) {
        if (pct < 0) return "";
        String s = (pct==11||pct==12||pct==13)?"th":(pct%10==1)?"st":(pct%10==2)?"nd":(pct%10==3)?"rd":"th";
        return pct+s+" pct.";
    }
}
