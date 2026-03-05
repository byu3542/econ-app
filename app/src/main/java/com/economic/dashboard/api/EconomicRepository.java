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
                        try {
                            errorDetail = " - " + response.errorBody().string();
                        } catch (Exception ignored) {}
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

                if (!response.isSuccessful() || response.body() == null) {
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
                        if (!text.isEmpty()) {
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

    // ============================================================
    // BEA - GDP DATA
    // ============================================================
    public void fetchGDPData(DataCallback<List<EconomicDataPoint>> callback) {
        new Thread(() -> {
            try {
                int currentYear = Calendar.getInstance().get(Calendar.YEAR);
                int startYear = currentYear - 5;

                StringBuilder yearList = new StringBuilder();
                for (int y = startYear; y <= currentYear; y++) {
                    if (y > startYear) yearList.append(",");
                    yearList.append(y);
                }

                Map<String, String> params = new HashMap<>();
                params.put("UserID", ApiConfig.BEA_API_KEY);
                params.put("method", "GetData");
                params.put("datasetname", ApiConfig.BEA_DATASET);
                params.put("TableName", ApiConfig.BEA_TABLE);
                params.put("Frequency", ApiConfig.BEA_FREQUENCY);
                params.put("Year", yearList.toString());
                params.put("ResultFormat", "JSON");

                Call<BeaResponse> call = RetrofitClient.getBeaService().getBeaData(params);
                retrofit2.Response<BeaResponse> response = call.execute();

                if (!response.isSuccessful() || response.body() == null) {
                    callback.onError("BEA HTTP error: " + response.code());
                    return;
                }

                BeaResponse beaResponse = response.body();
                List<EconomicDataPoint> results = new ArrayList<>();

                if (beaResponse.BEAAPI != null &&
                    beaResponse.BEAAPI.Results != null &&
                    beaResponse.BEAAPI.Results.Data != null) {

                    for (BeaResponse.BeaDataPoint item : beaResponse.BEAAPI.Results.Data) {
                        if (isValidValue(item.DataValue)) {
                            try {
                                String cleanValue = item.DataValue.replace(",", "");
                                results.add(new EconomicDataPoint(
                                        "BEA", "GDP",
                                        item.getDescription(),
                                        item.TimePeriod,
                                        Double.parseDouble(cleanValue), "%"
                                ));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }

                callback.onSuccess(results);
            } catch (Exception e) {
                Log.e(TAG, "BEA fetch error", e);
                callback.onError("BEA fetch failed: " + e.getMessage());
            }
        }).start();
    }

    // ============================================================
    // BLS - Employment, CPI, Wages
    // ============================================================
    public void fetchBlsData(List<String> seriesIds, String category,
                              Map<String, String> seriesNames, Map<String, String> units,
                              DataCallback<List<EconomicDataPoint>> callback) {
        new Thread(() -> {
            try {
                int currentYear = Calendar.getInstance().get(Calendar.YEAR);
                int startYear = currentYear - 2;

                Map<String, Object> body = new HashMap<>();
                body.put("seriesid", seriesIds);
                body.put("startyear", String.valueOf(startYear));
                body.put("endyear", String.valueOf(currentYear));
                body.put("registrationkey", ApiConfig.BLS_API_KEY);

                Call<BlsResponse> call = RetrofitClient.getBlsService().getBlsData(body);
                retrofit2.Response<BlsResponse> response = call.execute();

                if (!response.isSuccessful() || response.body() == null) {
                    callback.onError("BLS HTTP error: " + response.code());
                    return;
                }

                BlsResponse blsResponse = response.body();
                List<EconomicDataPoint> results = new ArrayList<>();

                if ("REQUEST_SUCCEEDED".equals(blsResponse.status) &&
                    blsResponse.Results != null &&
                    blsResponse.Results.series != null) {

                    for (BlsResponse.BlsSeries series : blsResponse.Results.series) {
                        String sName = seriesNames.getOrDefault(series.seriesID, series.seriesID);
                        String unit  = units != null ? units.getOrDefault(series.seriesID, "") : "";

                        if (series.data != null) {
                            for (BlsResponse.BlsDataPoint point : series.data) {
                                if (isValidValue(point.value)) {
                                    String date = formatBlsDate(point.year, point.period);
                                    try {
                                        results.add(new EconomicDataPoint(
                                                "BLS", category, sName,
                                                date, Double.parseDouble(point.value), unit
                                        ));
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                        }
                    }
                }

                results.sort((a, b) -> a.getDate().compareTo(b.getDate()));
                callback.onSuccess(results);

            } catch (Exception e) {
                Log.e(TAG, "BLS fetch error", e);
                callback.onError("BLS fetch failed: " + e.getMessage());
            }
        }).start();
    }

    public void fetchEmploymentData(DataCallback<List<EconomicDataPoint>> callback) {
        Map<String, String> names = new HashMap<>();
        names.put(ApiConfig.BLS_UNEMPLOYMENT_RATE,   "Unemployment Rate");
        names.put(ApiConfig.BLS_EMPLOYMENT_LEVEL,    "Employment Level");
        names.put(ApiConfig.BLS_LABOR_PARTICIPATION, "Labor Force Participation Rate");

        Map<String, String> units = new HashMap<>();
        units.put(ApiConfig.BLS_UNEMPLOYMENT_RATE,   "%");
        units.put(ApiConfig.BLS_EMPLOYMENT_LEVEL,    "Thousands");
        units.put(ApiConfig.BLS_LABOR_PARTICIPATION, "%");

        fetchBlsData(
                Arrays.asList(ApiConfig.BLS_UNEMPLOYMENT_RATE,
                              ApiConfig.BLS_EMPLOYMENT_LEVEL,
                              ApiConfig.BLS_LABOR_PARTICIPATION),
                "Employment", names, units, callback
        );
    }

    public void fetchCPIData(DataCallback<List<EconomicDataPoint>> callback) {
        Map<String, String> names = new HashMap<>();
        names.put(ApiConfig.BLS_CPI_U, "CPI-U All Items");
        names.put(ApiConfig.BLS_CPI_W, "CPI-W All Items");

        Map<String, String> units = new HashMap<>();
        units.put(ApiConfig.BLS_CPI_U, "Index");
        units.put(ApiConfig.BLS_CPI_W, "Index");

        fetchBlsData(
                Arrays.asList(ApiConfig.BLS_CPI_U, ApiConfig.BLS_CPI_W),
                "CPI", names, units, callback
        );
    }

    public void fetchWageData(DataCallback<List<EconomicDataPoint>> callback) {
        Map<String, String> names = new HashMap<>();
        names.put(ApiConfig.BLS_HOURLY_EARNINGS, "Average Hourly Earnings - Private");
        names.put(ApiConfig.BLS_WEEKLY_EARNINGS, "Average Weekly Earnings - Private");

        Map<String, String> units = new HashMap<>();
        units.put(ApiConfig.BLS_HOURLY_EARNINGS, "$");
        units.put(ApiConfig.BLS_WEEKLY_EARNINGS, "$");

        fetchBlsData(
                Arrays.asList(ApiConfig.BLS_HOURLY_EARNINGS, ApiConfig.BLS_WEEKLY_EARNINGS),
                "Wages", names, units, callback
        );
    }

    private boolean isValidValue(String val) {
        return val != null && !val.isEmpty() && !val.equals("-") && !val.equals(".");
    }

    private String formatBlsDate(String year, String period) {
        if (period.startsWith("M")) {
            String month = period.substring(1);
            return year + "-" + month;
        }
        return year + "-" + period;
    }
}
