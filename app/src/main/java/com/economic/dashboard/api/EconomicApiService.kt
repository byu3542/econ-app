package com.economic.dashboard.api

import com.economic.dashboard.models.BeaResponse
import com.economic.dashboard.models.BlsResponse
import com.economic.dashboard.models.FredResponse

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.QueryMap

interface EconomicApiService {

    // BLS - POST request with series IDs
    @POST("timeseries/data/")
    fun getBlsData(@Body body: Map<String, Any>): Call<BlsResponse>

    // BEA - GET request
    @GET("data")
    fun getBeaData(@QueryMap params: Map<String, String>): Call<BeaResponse>

    // FRED - GET request
    @GET("series/observations")
    fun getFredData(@QueryMap params: Map<String, String>): Call<FredResponse>
}
