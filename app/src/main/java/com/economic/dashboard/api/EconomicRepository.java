package com.economic.dashboard.api;

import android.util.Log;
import android.util.Xml;

import com.economic.dashboard.models.BeaResponse;
import com.economic.dashboard.models.BlsResponse;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.models.FredResponse;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import retrofit2.Call;

public class EconomicRepository {

    private static final String TAG = "EconomicRepository";

    public interface DataCallback<T> {
        void onSuccess(T data);
        void onError(String error);
    }

    // ============================================================
    // FRED - Generic Fetcher
    // ============================================================
    public void fetchFredData(String seriesId, String seriesName, DataCallback<List<EconomicDataPoint>> callback) {
        fetchFredData(seriesId, seriesName, "5", callback);
    }

    public void fetchFredData(String seriesId, String seriesName, String limit, DataCallback<List<EconomicDataPoint>> callback) {
        fetchFredData(seriesId, seriesName, limit, null, callback);
    }

    public void fetchFredData(String seriesId, String seriesName, String limit, String frequency, DataCallback<List<EconomicDataPoint>> callback) {
        new Thread(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("series_id", seriesId);
                params.put("api_key", ApiConfig.FRED_API_KEY);
                params.put("file_type", "json");
                params.put("sort_order", "desc");
                params.put("limit", limit);
                if (frequency != null) {
                    params.put("frequency", frequency);
                }

                Call<FredResponse> call = RetrofitClient.getFredService().getFredData(params);
                retrofit2.Response<FredResponse> response = call.execute();

                if (!response.isSuccessful() || response.body() == null) {
                    String errorDetail = "";
                    if (response.errorBody() != null) {
                        try { errorDetail = " - " + response.errorBody().string(); } catch (Exception ignored) {}
                    }
                    callback.onError("FRED HTTP error: " + response.code() + errorDetail);
                    return;
                }

                List<EconomicDataPoint> results = new ArrayList<>();
                FredResponse fredResponse = response.body();
                if (fredResponse.observations != null) {
                    for (FredResponse.FredObservation obs : fredResponse.observations) {
                        try {
                            if (isValidValue(obs.value)) {
                                results.add(new EconomicDataPoint(
                                        "FRED", "Interest Rates", seriesName,
                                        obs.date, Double.parseDouble(obs.value), "%"
                                ));
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }

                if (results.isEmpty()) {
                    callback.onError("No valid data found for " + seriesName);
                } else {
                    callback.onSuccess(results);
                }
            } catch (Exception e) {
                Log.e(TAG, "FRED fetch error", e);
                callback.onError("FRED fetch failed: " + e.getMessage());
            }
        }).start();
    }

    // ============================================================
    // PCE INFLATION — FRED
    // ============================================================
    public void fetchPCEData(DataCallback<List<EconomicDataPoint>> callback) {
        new Thread(() -> {
            try {
                // Fetch PCE (all items) — 120 months for percentile context
                List<EconomicDataPoint> pceAll   = fetchFredSync(ApiConfig.FRED_PCE,      "PCE Price Index",       ApiConfig.FRED_PCE_LIMIT, "m");
                List<EconomicDataPoint> pceCore  = fetchFredSync(ApiConfig.FRED_CORE_PCE, "Core PCE Price Index",  ApiConfig.FRED_PCE_LIMIT, "m");

                List<EconomicDataPoint> combined = new ArrayList<>();
                combined.addAll(pceAll);
                combined.addAll(pceCore);

                if (combined.isEmpty()) {
                    callback.onError("No PCE data returned");
                } else {
                    combined.sort((a, b) -> a.getDate().compareTo(b.getDate()));
                    callback.onSuccess(combined);
                }
            } catch (Exception e) {
                Log.e(TAG, "PCE fetch error", e);
                callback.onError("PCE fetch failed: " + e.getMessage());
            }
        }).start();
    }

    // ============================================================
    // HOUSING DATA — FRED
    // ============================================================
    public void fetchHousingData(DataCallback<List<EconomicDataPoint>> callback) {
        new Thread(() -> {
            try {
                List<EconomicDataPoint> starts = fetchFredSync(
                        ApiConfig.FRED_HOUSING_STARTS, "Housing Starts",
                        ApiConfig.FRED_HOUSING_LIMIT, "m");
                List<EconomicDataPoint> sales = fetchFredSync(
                        ApiConfig.FRED_EXISTING_HOME_SALES, "Existing Home Sales",
                        ApiConfig.FRED_HOUSING_LIMIT, "m");

                List<EconomicDataPoint> combined = new ArrayList<>();
                combined.addAll(starts);
                combined.addAll(sales);

                if (combined.isEmpty()) {
                    callback.onError("No housing data returned");
                } else {
                    combined.sort((a, b) -> a.getDate().compareTo(b.getDate()));
                    callback.onSuccess(combined);
                }
            } catch (Exception e) {
                Log.e(TAG, "Housing fetch error", e);
                callback.onError("Housing fetch failed: " + e.getMessage());
            }
        }).start();
    }

    /** Synchronous FRED helper for use inside compound fetch methods */
    private List<EconomicDataPoint> fetchFredSync(String seriesId, String seriesName,
                                                   String limit, String frequency) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("series_id", seriesId);
        params.put("api_key", ApiConfig.FRED_API_KEY);
        params.put("file_type", "json");
        params.put("sort_order", "desc");
        params.put("limit", limit);
        if (frequency != null) params.put("frequency", frequency);

        Call<FredResponse> call = RetrofitClient.getFredService().getFredData(params);
        retrofit2.Response<FredResponse> response = call.execute();

        List<EconomicDataPoint> results = new ArrayList<>();
        if (response.isSuccessful() && response.body() != null && response.body().observations != null) {
            for (FredResponse.FredObservation obs : response.body().observations) {
                try {
                    if (isValidValue(obs.value)) {
                        results.add(new EconomicDataPoint(
                                "FRED", "Inflation", seriesName,
                                obs.date, Double.parseDouble(obs.value), "Index"
                        ));
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return results;
    }

    // ============================================================
    // MBS & MORTGAGE RATES — FRED
    // ============================================================
    public void fetchMbsMortgageData(DataCallback<List<EconomicDataPoint>> callback) {
        new Thread(() -> {
            try {
                List<EconomicDataPoint> bankMbs = fetchFredSync(
                        ApiConfig.FRED_BANK_MBS, "Bank MBS Holdings",
                        ApiConfig.FRED_MBS_LIMIT, "w");
                List<EconomicDataPoint> fedMbs = fetchFredSync(
                        ApiConfig.FRED_FED_MBS, "Fed MBS Holdings",
                        ApiConfig.FRED_MBS_LIMIT, "w");
                List<EconomicDataPoint> mortgage = fetchFredSync(
                        ApiConfig.FRED_MORTGAGE30, "30-Yr Mortgage Rate",
                        ApiConfig.FRED_MBS_LIMIT, "w");

                List<EconomicDataPoint> combined = new ArrayList<>();
                combined.addAll(bankMbs);
                combined.addAll(fedMbs);
                combined.addAll(mortgage);

                if (combined.isEmpty()) {
                    callback.onError("No MBS/mortgage data returned");
                } else {
                    combined.sort((a, b) -> a.getDate().compareTo(b.getDate()));
                    callback.onSuccess(combined);
                }
            } catch (Exception e) {
                Log.e(TAG, "MBS/mortgage fetch error", e);
                callback.onError("MBS/mortgage fetch failed: " + e.getMessage());
            }
        }).start();
    }

    // ============================================================
    // GDP — BEA NIPA TABLE T10101
    // ============================================================
    public void fetchGDPData(DataCallback<List<EconomicDataPoint>> callback) {
        new Thread(() -> {
            try {
                int currentYear = Calendar.getInstance().get(Calendar.YEAR);
                StringBuilder yearsBuilder = new StringBuilder();
                for (int y = currentYear - 10; y <= currentYear; y++) {
                    if (yearsBuilder.length() > 0) yearsBuilder.append(",");
                    yearsBuilder.append(y);
                }

                Map<String, String> params = new HashMap<>();
                params.put("UserID",      ApiConfig.BEA_API_KEY);
                params.put("method",      "GetData");
                params.put("DatasetName", ApiConfig.BEA_DATASET);
                params.put("TableName",   ApiConfig.BEA_TABLE);
                params.put("Frequency",   ApiConfig.BEA_FREQUENCY);
                params.put("Year",        yearsBuilder.toString());
                params.put("ResultFormat","JSON");

                Call<BeaResponse> call = RetrofitClient.getBeaService().getBeaData(params);
                retrofit2.Response<BeaResponse> response = call.execute();

                if (!response.isSuccessful() || response.body() == null) {
                    callback.onError("BEA HTTP error: " + response.code());
                    return;
                }

                BeaResponse beaResp = response.body();
                List<EconomicDataPoint> results = new ArrayList<>();

                if (beaResp.BEAAPI != null && beaResp.BEAAPI.Results != null
                        && beaResp.BEAAPI.Results.Data != null) {
                    for (BeaResponse.BeaDataPoint dp : beaResp.BEAAPI.Results.Data) {
                        if (isValidValue(dp.DataValue) && dp.TimePeriod != null) {
                            try {
                                double value = Double.parseDouble(dp.DataValue.replace(",", ""));
                                String date = beaTimePeriodToDate(dp.TimePeriod);
                                results.add(new EconomicDataPoint(
                                        "BEA", "GDP", dp.getDescription(),
                                        date, value, "%"
                                ));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }

                if (results.isEmpty()) {
                    callback.onError("No GDP data returned");
                } else {
                    results.sort((a, b) -> a.getDate().compareTo(b.getDate()));
                    callback.onSuccess(results);
                }
            } catch (Exception e) {
                Log.e(TAG, "BEA fetch error", e);
                callback.onError("GDP fetch failed: " + e.getMessage());
            }
        }).start();
    }

    /** Converts BEA period strings like "2023Q1" → "2023-01-01". */
    private String beaTimePeriodToDate(String timePeriod) {
        if (timePeriod != null && timePeriod.contains("Q")) {
            String[] parts = timePeriod.split("Q");
            if (parts.length == 2) {
                String year = parts[0];
                int quarter = Integer.parseInt(parts[1]);
                String month;
                switch (quarter) {
                    case 1:  month = "01"; break;
                    case 2:  month = "04"; break;
                    case 3:  month = "07"; break;
                    default: month = "10"; break;
                }
                return year + "-" + month + "-01";
            }
        }
        return timePeriod;
    }

    // ============================================================
    // EMPLOYMENT — BLS (Unemployment, Participation, Level)
    // ============================================================
    public void fetchEmploymentData(DataCallback<List<EconomicDataPoint>> callback) {
        fetchBlsData(
                Arrays.asList(ApiConfig.BLS_UNEMPLOYMENT_RATE,
                              ApiConfig.BLS_EMPLOYMENT_LEVEL,
                              ApiConfig.BLS_LABOR_PARTICIPATION),
                "Employment",
                new String[]{"Unemployment Rate", "Employment Level", "Labor Force Participation Rate"},
                new String[]{"%", "Thousands", "%"},
                callback);
    }

    // ============================================================
    // CPI — BLS (CPI-U and CPI-W)
    // ============================================================
    public void fetchCPIData(DataCallback<List<EconomicDataPoint>> callback) {
        fetchBlsData(
                Arrays.asList(ApiConfig.BLS_CPI_U, ApiConfig.BLS_CPI_W),
                "Inflation",
                new String[]{"CPI-U All Items", "CPI-W All Items"},
                new String[]{"Index", "Index"},
                callback);
    }

    // ============================================================
    // WAGES — BLS (Hourly and Weekly Earnings)
    // ============================================================
    public void fetchWageData(DataCallback<List<EconomicDataPoint>> callback) {
        fetchBlsData(
                Arrays.asList(ApiConfig.BLS_HOURLY_EARNINGS, ApiConfig.BLS_WEEKLY_EARNINGS),
                "Employment",
                new String[]{"Average Hourly Earnings - Private", "Average Weekly Earnings - Private"},
                new String[]{"$/hr", "$/wk"},
                callback);
    }

    /**
     * Shared BLS fetch helper. Sends a POST request for the given series IDs,
     * maps each to a human-readable name, and returns chronologically sorted results.
     */
    private void fetchBlsData(List<String> seriesIds, String category,
                               String[] seriesNames, String[] units,
                               DataCallback<List<EconomicDataPoint>> callback) {
        new Thread(() -> {
            try {
                int currentYear = Calendar.getInstance().get(Calendar.YEAR);
                int startYear   = currentYear - Integer.parseInt(ApiConfig.BLS_HISTORY_MONTHS);

                Map<String, Object> body = new HashMap<>();
                body.put("seriesid",       seriesIds);
                body.put("startyear",      String.valueOf(startYear));
                body.put("endyear",        String.valueOf(currentYear));
                body.put("registrationkey", ApiConfig.BLS_API_KEY);

                Call<BlsResponse> call = RetrofitClient.getBlsService().getBlsData(body);
                retrofit2.Response<BlsResponse> response = call.execute();

                if (!response.isSuccessful() || response.body() == null) {
                    String errorDetail = "";
                    if (response.errorBody() != null) {
                        try { errorDetail = " - " + response.errorBody().string(); } catch (Exception ignored) {}
                    }
                    callback.onError("BLS HTTP error: " + response.code() + errorDetail);
                    return;
                }

                BlsResponse blsResp = response.body();
                List<EconomicDataPoint> results = new ArrayList<>();

                if (blsResp.Results != null && blsResp.Results.series != null) {
                    for (BlsResponse.BlsSeries series : blsResp.Results.series) {
                        // Map series ID → human name + unit
                        String seriesName = series.seriesID;
                        String unit = "";
                        for (int i = 0; i < seriesIds.size(); i++) {
                            if (seriesIds.get(i).equals(series.seriesID)) {
                                seriesName = seriesNames[i];
                                unit = units[i];
                                break;
                            }
                        }

                        if (series.data != null) {
                            for (BlsResponse.BlsDataPoint dp : series.data) {
                                try {
                                    if (dp.period != null && dp.period.startsWith("M")
                                            && isValidValue(dp.value)) {
                                        int month = Integer.parseInt(dp.period.substring(1));
                                        String date = dp.year + "-"
                                                + String.format(Locale.US, "%02d", month)
                                                + "-01";
                                        results.add(new EconomicDataPoint(
                                                "BLS", category, seriesName,
                                                date, Double.parseDouble(dp.value), unit));
                                    }
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                }

                if (results.isEmpty()) {
                    callback.onError("No BLS data returned for: " + seriesIds);
                } else {
                    results.sort((a, b) -> a.getDate().compareTo(b.getDate()));
                    callback.onSuccess(results);
                }
            } catch (Exception e) {
                Log.e(TAG, "BLS fetch error", e);
                callback.onError("BLS fetch failed: " + e.getMessage());
            }
        }).start();
    }

    // ============================================================
    // TREASURY YIELD CURVE
    // ============================================================
    public void fetchTreasuryRates(DataCallback<List<EconomicDataPoint>> callback) {
        new Thread(() -> {
            try {
                int currentYear = Calendar.getInstance().get(Calendar.YEAR);
                String url = ApiConfig.TREASURY_BASE_URL +
                        "xml?data=daily_treasury_yield_curve&field_tdr_date_value=" + currentYear;

                OkHttpClient client = RetrofitClient.buildClient();
                Request request = new Request.Builder().url(url).build();
                Response response = client.newCall(request).execute();

                if (response.isSuccessful() == false || response.body() == null) {
                    callback.onError("Treasury HTTP error: " + response.code());
                    return;
                }

                String xmlContent = response.body().string();
                List<EconomicDataPoint> results = parseTreasuryXml(xmlContent);
                callback.onSuccess(results);

            } catch (Exception e) {
                Log.e(TAG, "Treasury fetch error", e);
                callback.onError("Treasury fetch failed: " + e.getMessage());
            }
        }).start();
    }

    private List<EconomicDataPoint> parseTreasuryXml(String xmlContent) throws Exception {
        List<EconomicDataPoint> results = new ArrayList<>();

        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new StringReader(xmlContent));

        String currentDate = null;
        Map<String, String> currentRates = new HashMap<>();
        boolean inProperties = false;
        String currentElement = null;

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
                case XmlPullParser.START_TAG:
                    String name = parser.getName();
                    currentElement = name;
                    if (name.equals("properties")) {
                        inProperties = true;
                        currentDate = null;
                        currentRates.clear();
                    }
                    break;

                case XmlPullParser.TEXT:
                    if (inProperties && currentElement != null) {
                        String text = parser.getText().trim();
                        if (text.isEmpty() == false) {
                            if (currentElement.equals("NEW_DATE")) {
                                currentDate = formatTreasuryDate(text);
                            }
                            String[] fields = ApiConfig.TREASURY_FIELDS;
                            for (String field : fields) {
                                if (currentElement.equals(field)) {
                                    currentRates.put(field, text);
                                }
                            }
                        }
                    }
                    break;

                case XmlPullParser.END_TAG:
                    if (parser.getName().equals("properties") && inProperties) {
                        if (currentDate != null) {
                            for (int i = 0; i < ApiConfig.TREASURY_FIELDS.length; i++) {
                                String val = currentRates.get(ApiConfig.TREASURY_FIELDS[i]);
                                if (val != null && isValidValue(val)) {
                                    try {
                                        results.add(new EconomicDataPoint(
                                                "Treasury", "Yield Curve",
                                                ApiConfig.TREASURY_MATURITIES[i],
                                                currentDate, Double.parseDouble(val), "%"
                                        ));
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                        }
                        inProperties = false;
                    }
                    currentElement = null;
                    break;
            }
            eventType = parser.next();
        }

        results.sort((a, b) -> b.getDate().compareTo(a.getDate()));
        if (results.size() > ApiConfig.TREASURY_DAYS_BACK * 8) {
            results = results.subList(0, ApiConfig.TREASURY_DAYS_BACK * 8);
        }
        return results;
    }

    private String formatTreasuryDate(String rawDate) {
        if (rawDate == null) return "";
        if (rawDate.contains("T")) return rawDate.substring(0, 10);
        return rawDate.length() >= 10 ? rawDate.substring(0, 10) : rawDate;
    }

    private boolean isValidValue(String value) {
        return value != null && !value.isEmpty() && !value.equals(".") && !value.equalsIgnoreCase("ND");
    }
}
