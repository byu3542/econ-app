package com.economic.dashboard.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    @Volatile private var blsRetrofit: Retrofit? = null
    @Volatile private var beaRetrofit: Retrofit? = null
    @Volatile private var fredRetrofit: Retrofit? = null

    /** Shared client — reuses the connection pool across all Retrofit instances and direct OkHttp calls. */
    @Volatile private var sharedClient: OkHttpClient? = null

    @JvmStatic
    @Synchronized
    fun buildClient(): OkHttpClient {
        var client = sharedClient
        if (client == null) {
            val logging = HttpLoggingInterceptor()
            logging.setLevel(HttpLoggingInterceptor.Level.BASIC)
            client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
            sharedClient = client
        }
        return client
    }

    @JvmStatic
    @Synchronized
    fun getBlsService(): EconomicApiService {
        var retrofit = blsRetrofit
        if (retrofit == null) {
            retrofit = Retrofit.Builder()
                .baseUrl(ApiConfig.BLS_BASE_URL_V2)
                .client(buildClient())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            blsRetrofit = retrofit
        }
        return retrofit.create(EconomicApiService::class.java)
    }

    @JvmStatic
    @Synchronized
    fun getBeaService(): EconomicApiService {
        var retrofit = beaRetrofit
        if (retrofit == null) {
            retrofit = Retrofit.Builder()
                .baseUrl(ApiConfig.BEA_BASE_URL)
                .client(buildClient())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            beaRetrofit = retrofit
        }
        return retrofit.create(EconomicApiService::class.java)
    }

    @JvmStatic
    @Synchronized
    fun getFredService(): EconomicApiService {
        var retrofit = fredRetrofit
        if (retrofit == null) {
            retrofit = Retrofit.Builder()
                .baseUrl(ApiConfig.FRED_BASE_URL)
                .client(buildClient())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            fredRetrofit = retrofit
        }
        return retrofit.create(EconomicApiService::class.java)
    }
}
