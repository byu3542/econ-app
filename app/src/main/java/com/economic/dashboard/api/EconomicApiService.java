package com.economic.dashboard.api;

import com.economic.dashboard.models.BeaResponse;
import com.economic.dashboard.models.BlsResponse;
import com.economic.dashboard.models.FredResponse;

import java.util.Map;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.QueryMap;

public interface EconomicApiService {

    // BLS - POST request with series IDs
    @POST("timeseries/data/")
    Call<BlsResponse> getBlsData(@Body Map<String, Object> body);

    // BEA - GET request
    @GET("data")
    Call<BeaResponse> getBeaData(@QueryMap Map<String, String> params);

    // FRED - GET request
    @GET("series/observations")
    Call<FredResponse> getFredData(@QueryMap Map<String, String> params);
}
