package com.economic.dashboard.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.economic.dashboard.api.ApiConfig;
import com.economic.dashboard.api.EconomicRepository;
import com.economic.dashboard.models.EconomicDataPoint;

import java.util.ArrayList;
import java.util.List;

public class EconomicViewModel extends ViewModel {

    private final EconomicRepository repository = new EconomicRepository();

    // LiveData for each data set
    private final MutableLiveData<List<EconomicDataPoint>> treasuryData    = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> gdpData         = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> employmentData  = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> cpiData         = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> wageData        = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> fedFundsData   = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> ismPmiData      = new MutableLiveData<>();
    private final MutableLiveData<List<EconomicDataPoint>> fedFundsHistory = new MutableLiveData<>();
    
    // Spread calculation pieces
    private List<EconomicDataPoint> currentTenYearList = null;
    private List<EconomicDataPoint> currentTwoYearList = null;
    private final MutableLiveData<List<EconomicDataPoint>> calculatedSpreadData = new MutableLiveData<>();

    private final MutableLiveData<Boolean>  isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String>   errorMsg  = new MutableLiveData<>();
    private final MutableLiveData<String>   statusMsg = new MutableLiveData<>();

    // Counters for "fetch all" tracking
    private int pendingFetches = 0;
    private final Object lock = new Object();

    public LiveData<List<EconomicDataPoint>> getTreasuryData()   { return treasuryData; }
    public LiveData<List<EconomicDataPoint>> getGdpData()        { return gdpData; }
    public LiveData<List<EconomicDataPoint>> getEmploymentData() { return employmentData; }
    public LiveData<List<EconomicDataPoint>> getCpiData()        { return cpiData; }
    public LiveData<List<EconomicDataPoint>> getWageData()       { return wageData; }
    public LiveData<List<EconomicDataPoint>> getFedFundsData()   { return fedFundsData; }
    public LiveData<List<EconomicDataPoint>> getIsmPmiData()      { return ismPmiData; }
    public LiveData<List<EconomicDataPoint>> getFedFundsHistory() { return fedFundsHistory; }
    public LiveData<List<EconomicDataPoint>> getCalculatedSpreadData() { return calculatedSpreadData; }
    
    public LiveData<Boolean>                 getIsLoading()      { return isLoading; }
    public LiveData<String>                  getErrorMsg()       { return errorMsg; }
    public LiveData<String>                  getStatusMsg()      { return statusMsg; }

    // ============================================================
    // FETCH ALL
    // ============================================================
    public void fetchAllData() {
        isLoading.postValue(true);
        statusMsg.postValue("Fetching all economic data...");
        synchronized (lock) { 
            pendingFetches = 9; 
            currentTenYearList = null;
            currentTwoYearList = null;
        }

        fetchTreasury();
        fetchGDP();
        fetchEmployment();
        fetchCPI();
        fetchWages();
        fetchFedFunds();
        fetchFedFundsHistory();
        
        // Fetch components for manual spread calculation
        fetchTenYearHistory();
        fetchTwoYearHistory();
    }

    private void checkAllDone() {
        synchronized (lock) {
            pendingFetches--;
            if (pendingFetches <= 0) {
                isLoading.postValue(false);
                statusMsg.postValue("✅ All data refreshed!");
            }
        }
    }

    // ============================================================
    // INDIVIDUAL FETCHERS
    // ============================================================
    public void fetchFedFunds() {
        repository.fetchFredData(ApiConfig.FRED_FED_FUNDS, "Federal Funds Effective Rate", new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> data) {
                fedFundsData.postValue(data);
                checkAllDone();
            }
            @Override public void onError(String error) {
                errorMsg.postValue("Fed Funds: " + error);
                checkAllDone();
            }
        });
    }

    public void fetchFedFundsHistory() {
        // Fetch 37 months to show 36 changes
        repository.fetchFredData(ApiConfig.FRED_FED_FUNDS, "Fed Funds History", "37", "m", new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> data) {
                fedFundsHistory.postValue(data);
                checkAllDone();
            }
            @Override public void onError(String error) {
                errorMsg.postValue("Fed History: " + error);
                checkAllDone();
            }
        });
    }

    public void fetchTenYearHistory() {
        repository.fetchFredData(ApiConfig.FRED_10Y_MATURITY, "10-Year Maturity", "36", "m", new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> data) {
                synchronized (lock) {
                    currentTenYearList = data;
                    combineForSpread();
                }
                checkAllDone();
            }
            @Override public void onError(String error) {
                errorMsg.postValue("10Y Fetch: " + error);
                checkAllDone();
            }
        });
    }

    public void fetchTwoYearHistory() {
        repository.fetchFredData(ApiConfig.FRED_2Y_MATURITY, "2-Year Maturity", "36", "m", new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> data) {
                synchronized (lock) {
                    currentTwoYearList = data;
                    combineForSpread();
                }
                checkAllDone();
            }
            @Override public void onError(String error) {
                errorMsg.postValue("2Y Fetch: " + error);
                checkAllDone();
            }
        });
    }

    private void combineForSpread() {
        if (currentTenYearList == null || currentTwoYearList == null) return;

        List<EconomicDataPoint> spreadResults = new ArrayList<>();
        // Match by date
        for (EconomicDataPoint t : currentTenYearList) {
            for (EconomicDataPoint w : currentTwoYearList) {
                if (t.getDate().equals(w.getDate())) {
                    spreadResults.add(new EconomicDataPoint(
                            "Calculated", "Treasury", "10Y-2Y Spread",
                            t.getDate(), t.getValue() - w.getValue(), "%"
                    ));
                    break;
                }
            }
        }
        
        // Sort ascending for chart (oldest to newest)
        spreadResults.sort((a, b) -> a.getDate().compareTo(b.getDate()));
        calculatedSpreadData.postValue(spreadResults);
    }

    public void fetchTreasury() {
        repository.fetchTreasuryRates(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> data) {
                treasuryData.postValue(data);
                checkAllDone();
            }
            @Override public void onError(String error) {
                errorMsg.postValue("Treasury: " + error);
                checkAllDone();
            }
        });
    }

    public void fetchGDP() {
        repository.fetchGDPData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> data) {
                gdpData.postValue(data);
                checkAllDone();
            }
            @Override public void onError(String error) {
                errorMsg.postValue("GDP: " + error);
                checkAllDone();
            }
        });
    }

    public void fetchEmployment() {
        repository.fetchEmploymentData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> data) {
                employmentData.postValue(data);
                checkAllDone();
            }
            @Override public void onError(String error) {
                errorMsg.postValue("Employment: " + error);
                checkAllDone();
            }
        });
    }

    public void fetchCPI() {
        repository.fetchCPIData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> data) {
                cpiData.postValue(data);
                checkAllDone();
            }
            @Override public void onError(String error) {
                errorMsg.postValue("CPI: " + error);
                checkAllDone();
            }
        });
    }

    public void fetchWages() {
        repository.fetchWageData(new EconomicRepository.DataCallback<List<EconomicDataPoint>>() {
            @Override public void onSuccess(List<EconomicDataPoint> data) {
                wageData.postValue(data);
                checkAllDone();
            }
            @Override public void onError(String error) {
                errorMsg.postValue("Wages: " + error);
                checkAllDone();
            }
        });
    }

    // ============================================================
    // UTILITY: get latest value for a series
    // ============================================================
    public static EconomicDataPoint getLatest(List<EconomicDataPoint> data, String series) {
        if (data == null) return null;
        EconomicDataPoint latest = null;
        for (EconomicDataPoint point : data) {
            if (point.getSeries().equals(series)) {
                if (latest == null || point.getDate().compareTo(latest.getDate()) > 0) {
                    latest = point;
                }
            }
        }
        return latest;
    }

    public static List<EconomicDataPoint> filterBySeries(List<EconomicDataPoint> data, String series) {
        List<EconomicDataPoint> result = new ArrayList<>();
        if (data == null) result = new ArrayList<>();
        else {
            for (EconomicDataPoint p : data) {
                if (p.getSeries().equals(series)) result.add(p);
            }
        }
        result.sort((a, b) -> a.getDate().compareTo(b.getDate()));
        return result;
    }
}
