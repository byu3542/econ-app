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
    // @JvmSuppressWildcards is required here: kotlin.collections.Map is declared
    // `out V`, so Map<String, Any> compiles to a JVM signature of
    // Map<String, ?>. Retrofit's reflection-based body serialization rejects
    // wildcard type arguments outright ("Parameter type must not include a type
    // variable or wildcard"), so every BLS call (Employment, CPI, Wages) failed
    // before a request was ever sent. Suppressing the wildcard restores the
    // concrete Map<String, Object> signature Retrofit expects.
    @POST("timeseries/data/")
    fun getBlsData(@Body body: Map<String, @JvmSuppressWildcards Any>): Call<BlsResponse>

    // BEA - GET request
    @GET("data")
    fun getBeaData(@QueryMap params: Map<String, String>): Call<BeaResponse>

    // FRED - GET request
    @GET("series/observations")
    fun getFredData(@QueryMap params: Map<String, String>): Call<FredResponse>
}
