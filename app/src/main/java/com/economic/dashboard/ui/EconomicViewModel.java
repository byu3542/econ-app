package com.economic.dashboard.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.economic.dashboard.api.ApiConfig;
import com.economic.dashboard.api.EconomicRepository;
import com.economic.dashboard.models.EconomicDataPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class EconomicViewModel extends ViewModel {

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

    public void fetchAllData() {
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
        if (pendingFetches.decrementAndGet() <= 0) isLoading.postValue(false);
    }

    public void fetchFedFunds() {
        repository.fetchFredData(ApiConfig.FRED_FED_FUNDS, "Federal Funds Effective Rate", "24", null, "Interest Rates",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) { fedFundsData.postValue(d); checkAllDone(); }
                    @Override public void onError(String e) { errorMsg.postValue("Fed Funds: "+e); checkAllDone(); }
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
            @Override public void onSuccess(List<EconomicDataPoint> d) { pceData.postValue(d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("PCE: "+e); checkAllDone(); }
        });
    }

    public void fetchHousing() {
        repository.fetchHousingData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { housingData.postValue(d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("Housing: "+e); checkAllDone(); }
        });
    }

    public void fetchMbsMortgage() {
        repository.fetchMbsMortgageData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { mbsMortgageData.postValue(d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("MBS: "+e); checkAllDone(); }
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
            @Override public void onSuccess(List<EconomicDataPoint> d) { treasuryData.postValue(d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("Treasury: "+e); checkAllDone(); }
        });
    }

    public void fetchGDP() {
        repository.fetchGDPData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { gdpData.postValue(d); updateLatestQuarter(d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("GDP: "+e); checkAllDone(); }
        });
    }

    public void fetchEmployment() {
        repository.fetchEmploymentData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { employmentData.postValue(d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("Employment: "+e); checkAllDone(); }
        });
    }

    public void fetchCPI() {
        repository.fetchCPIData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { cpiData.postValue(d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("CPI: "+e); checkAllDone(); }
        });
    }

    public void fetchWages() {
        repository.fetchWageData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { wageData.postValue(d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("Wages: "+e); checkAllDone(); }
        });
    }

    public void fetchSp500() {
        // SP500 on FRED is a weekly series — "w" is the correct frequency
        repository.fetchFredData(ApiConfig.FRED_SP500, "S&P 500 Index", ApiConfig.STOCK_INDICES_LIMIT, "w", "Stocks",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) { sp500Data.postValue(d); checkAllDone(); }
                    @Override public void onError(String e) { errorMsg.postValue("S&P 500: "+e); checkAllDone(); }
                });
    }

    public void fetchNasdaq() {
        // NASDAQCOM on FRED is a monthly series — "m" is the correct frequency
        repository.fetchFredData(ApiConfig.FRED_NASDAQ, "Nasdaq Composite Index", ApiConfig.STOCK_INDICES_LIMIT, "m", "Stocks",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) { nasdaqData.postValue(d); checkAllDone(); }
                    @Override public void onError(String e) { errorMsg.postValue("Nasdaq: "+e); checkAllDone(); }
                });
    }

    public void fetchVix() {
        // VIXCLS on FRED is daily — "d" is correct
        repository.fetchFredData(ApiConfig.FRED_VIX, "VIX Volatility Index", ApiConfig.STOCK_INDICES_LIMIT, "d", "Volatility",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) { vixData.postValue(d); checkAllDone(); }
                    @Override public void onError(String e) { errorMsg.postValue("VIX: "+e); checkAllDone(); }
                });
    }

    public void fetchBaaSpread() {
        repository.fetchFredData(ApiConfig.FRED_BAA_SPREAD, "BAA Corporate Spread", ApiConfig.BOND_SPREADS_LIMIT, "d", "Credit Spreads",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) { baaSpreadData.postValue(d); checkAllDone(); }
                    @Override public void onError(String e) { errorMsg.postValue("BAA: "+e); checkAllDone(); }
                });
    }

    public void fetchHySpread() {
        repository.fetchFredData(ApiConfig.FRED_HY_SPREAD, "High Yield Spread", ApiConfig.BOND_SPREADS_LIMIT, "d", "Credit Spreads",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) { hySpreadData.postValue(d); checkAllDone(); }
                    @Override public void onError(String e) { errorMsg.postValue("HY: "+e); checkAllDone(); }
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

    public static int calculatePercentile(List<EconomicDataPoint> data, String series, double currentValue) {
        List<EconomicDataPoint> rows = filterBySeries(data, series);
        if (rows.size() < 5) return -1;
        int count = 0;
        for (EconomicDataPoint p : rows) if (p.getValue() <= currentValu