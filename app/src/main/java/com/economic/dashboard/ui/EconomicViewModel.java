package com.economic.dashboard.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.economic.dashboard.api.ApiConfig;
import com.economic.dashboard.api.EconomicRepository;
import com.economic.dashboard.models.EconomicDataPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class EconomicViewModel extends ViewModel {

    private final EconomicRepository repository = new EconomicRepository();

    // LiveData for each data set
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
    private final MutableLiveData<String> latestQuarterGdp   = new MutableLiveData<>();
    private final MutableLiveData<String> latestQuarterLabel = new MutableLiveData<>();

    // Spread calculation pieces
    private List<EconomicDataPoint> currentTenYearList    = null;
    private List<EconomicDataPoint> currentTwoYearList    = null;
    private List<EconomicDataPoint> currentThreeMonthList = null;

    private final MutableLiveData<List<EconomicDataPoint>> calculatedSpreadData   = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> calculatedSpread3MData = new MutableLiveData<>();

    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String>  errorMsg  = new MutableLiveData<>();
    private final MutableLiveData<String>  statusMsg = new MutableLiveData<>();

    private int pendingFetches = 0;
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
    public LiveData<List<EconomicDataPoint>> getCalculatedSpreadData()   { return calculatedSpreadData; }
    public LiveData<List<EconomicDataPoint>> getCalculatedSpread3MData() { return calculatedSpread3MData; }
    public LiveData<String>                  getLatestQuarterGdp()       { return latestQuarterGdp; }
    public LiveData<String>                  getLatestQuarterLabel()     { return latestQuarterLabel; }
    public LiveData<Boolean>                 getIsLoading()              { return isLoading; }
    public LiveData<String>                  getErrorMsg()               { return errorMsg; }
    public LiveData<String>                  getStatusMsg()              { return statusMsg; }

    // ============================================================
    // FETCH ALL Ã¢â‚¬â€ 12 parallel fetches
    // ============================================================
    public void fetchAllData() {
        isLoading.postValue(true);
        statusMsg.postValue("Fetching all economic data...");
        synchronized (lock) {
            pendingFetches = 13;
            currentTenYearList    = null;
            currentTwoYearList    = null;
            currentThreeMonthList = null;
        }
        fetchTreasury();
        fetchGDP();
        fetchEmployment();
        fetchCPI();
        fetchWages();
        fetchFedFunds();
        fetchFedFundsHistory();
        fetchTenYearHistory();
        fetchTwoYearHistory();
        fetchThreeMonthHistory();
        fetchPCE();
        fetchHousing();
        fetchMbsMortgage();
    }

    private void checkAllDone() {
        synchronized (lock) {
            pendingFetches--;
            if (pendingFetches <= 0) {
                isLoading.postValue(false);
                statusMsg.postValue("All data refreshed!");
            }
        }
    }

    // ============================================================
    // INDIVIDUAL FETCHERS
    // ============================================================
    public void fetchFedFunds() {
        repository.fetchFredData(ApiConfig.FRED_FED_FUNDS, "Federal Funds Effective Rate", "24",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) { fedFundsData.postValue(d); checkAllDone(); }
                    @Override public void onError(String e) { errorMsg.postValue("Fed Funds: " + e); checkAllDone(); }
                });
    }

    public void fetchFedFundsHistory() {
        repository.fetchFredData(ApiConfig.FRED_FED_FUNDS, "Fed Funds History", "37", "m",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) { fedFundsHistory.postValue(d); checkAllDone(); }
                    @Override public void onError(String e) { errorMsg.postValue("Fed History: " + e); checkAllDone(); }
                });
    }

    public void fetchTenYearHistory() {
        repository.fetchFredData(ApiConfig.FRED_10Y_MATURITY, "10-Year Maturity", "36", "m",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) {
                        synchronized (lock) { currentTenYearList = d; combineForSpread(); combineFor3MSpread(); }
                        checkAllDone();
                    }
                    @Override public void onError(String e) { errorMsg.postValue("10Y Fetch: " + e); checkAllDone(); }
                });
    }

    public void fetchTwoYearHistory() {
        repository.fetchFredData(ApiConfig.FRED_2Y_MATURITY, "2-Year Maturity", "36", "m",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) {
                        synchronized (lock) { currentTwoYearList = d; combineForSpread(); }
                        checkAllDone();
                    }
                    @Override public void onError(String e) { errorMsg.postValue("2Y Fetch: " + e); checkAllDone(); }
                });
    }

    public void fetchThreeMonthHistory() {
        repository.fetchFredData(ApiConfig.FRED_3M_MATURITY, "3-Month Maturity", "36", "m",
                new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
                    @Override public void onSuccess(List<EconomicDataPoint> d) {
                        synchronized (lock) { currentThreeMonthList = d; combineFor3MSpread(); }
                        checkAllDone();
                    }
                    @Override public void onError(String e) { errorMsg.postValue("3M Fetch: " + e); checkAllDone(); }
                });
    }

    public void fetchPCE() {
        repository.fetchPCEData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { pceData.postValue(d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("PCE: " + e); checkAllDone(); }
        });
    }

    public void fetchHousing() {
        repository.fetchHousingData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { housingData.postValue(d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("Housing: " + e); checkAllDone(); }
        });
    }

    public void fetchMbsMortgage() {
        repository.fetchMbsMortgageData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { mbsMortgageData.postValue(d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("MBS/Mortgage: " + e); checkAllDone(); }
        });
    }

    private void combineForSpread() {
        if (currentTenYearList == null || currentTwoYearList == null) return;
        List<EconomicDataPoint> spreadResults = new ArrayList<>();
        for (EconomicDataPoint t : currentTenYearList) {
            for (EconomicDataPoint w : currentTwoYearList) {
                if (t.getDate().equals(w.getDate())) {
                    spreadResults.add(new EconomicDataPoint("Calculated", "Treasury", "10Y-2Y Spread",
                            t.getDate(), t.getValue() - w.getValue(), "%"));
                    break;
                }
            }
        }
        spreadResults.sort((a, b) -> a.getDate().compareTo(b.getDate()));
        calculatedSpreadData.postValue(spreadResults);
    }

    private void combineFor3MSpread() {
        if (currentTenYearList == null || currentThreeMonthList == null) return;
        List<EconomicDataPoint> spreadResults = new ArrayList<>();
        for (EconomicDataPoint t : currentTenYearList) {
            for (EconomicDataPoint w : currentThreeMonthList) {
                if (t.getDate().equals(w.getDate())) {
                    spreadResults.add(new EconomicDataPoint("Calculated", "Treasury", "10Y-3M Spread",
                            t.getDate(), t.getValue() - w.getValue(), "%"));
                    break;
                }
            }
        }
        spreadResults.sort((a, b) -> a.getDate().compareTo(b.getDate()));
        calculatedSpread3MData.postValue(spreadResults);
    }

    public void fetchTreasury() {
        repository.fetchTreasuryRates(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { treasuryData.postValue(d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("Treasury: " + e); checkAllDone(); }
        });
    }

    public void fetchGDP() {
        repository.fetchGDPData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { gdpData.postValue(d); updateLatestQuarter(d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("GDP: " + e); checkAllDone(); }
        });
    }

    public void fetchEmployment() {
        repository.fetchEmploymentData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { employmentData.postValue(d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("Employment: " + e); checkAllDone(); }
        });
    }

    public void fetchCPI() {
        repository.fetchCPIData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { cpiData.postValue(d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("CPI: " + e); checkAllDone(); }
        });
    }

    public void fetchWages() {
        repository.fetchWageData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> d) { wageData.postValue(d); checkAllDone(); }
            @Override public void onError(String e) { errorMsg.postValue("Wages: " + e); checkAllDone(); }
        });
    }

    // ============================================================
    // UTILITIES
    // ============================================================
    public static EconomicDataPoint getLatest(List<EconomicDataPoint> data, String series) {
        if (data == null) return null;
        EconomicDataPoint latest = null;
        for (EconomicDataPoint point : data) {
            if (point.getSeries().equals(series)) {
                if (latest == null || point.getDate().compareTo(latest.getDate()) > 0) latest = point;
            }
        }
        return latest;
    }

    private void updateLatestQuarter(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = filterBySeries(data, "Gross domestic product");
        if (rows.isEmpty()) return;
        EconomicDataPoint latest = rows.get(rows.size() - 1);
        latestQuarterGdp.postValue(String.format(Locale.US, "%.2f%%", latest.getValue()));
        latestQuarterLabel.postValue(formatQuarterLabel(latest.getDate()));
    }

    private String formatQuarterLabel(String date) {
        if (date == null || date.length() < 7) return "";
        String year = date.substring(0, 4);
        String month = date.substring(5, 7);
        String quarter;
        switch (month) {
            case "01": quarter = "Q1"; break;
            case "04": quarter = "Q2"; break;
            case "07": quarter = "Q3"; break;
            case "10": quarter = "Q4"; break;
            default: quarter = "Q?"; break;
        }
        return quarter + " " + year;
    }
    public static List<EconomicDataPoint> filterBySeries(List<EconomicDataPoint> data, String series) {
        List<EconomicDataPoint> result = new ArrayList<>();
        if (data != null) {
            for (EconomicDataPoint p : data) {
                if (p.getSeries().equals(series)) result.add(p);
            }
        }
        result.sort((a, b) -> a.getDate().compareTo(b.getDate()));
        return result;
    }

    /**
     * Returns the percentile rank (0-100) of currentValue within historical data for the given series.
     * e.g., 18 = this reading is higher than 18% of historical readings (bottom 18th percentile).
     * Returns -1 if insufficient data.
     */
    public static int calculatePercentile(List<EconomicDataPoint> data, String series, double currentValue) {
        List<EconomicDataPoint> rows = filterBySeries(data, series);
        if (rows.size() < 5) return -1;
        int countAtOrBelow = 0;
        for (EconomicDataPoint p : rows) {
            if (p.getValue() <= currentValue) countAtOrBelow++;
        }
        return (int) Math.round((countAtOrBelow * 100.0) / rows.size());
    }

    /** Formats a percentile into a compact label like "18th pct." */
    public static String formatPercentile(int pct) {
        if (pct < 0) return "";
        String suffix = (pct == 11 || pct == 12 || pct == 13) ? "th"
                : (pct % 10 == 1) ? "st"
                : (pct % 10 == 2) ? "nd"
                : (pct % 10 == 3) ? "rd" : "th";
        return pct + suffix + " pct.";
    }
}
