package com.economic.dashboard.api;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import java.util.concurrent.TimeUnit;

public class RetrofitClient {

    private static volatile Retrofit blsRetrofit;
    private static volatile Retrofit beaRetrofit;
    private static volatile Retrofit fredRetrofit;

    /** Shared client — reuses the connection pool across all Retrofit instances and direct OkHttp calls. */
    private static volatile OkHttpClient sharedClient;

    public static synchronized OkHttpClient buildClient() {
        if (sharedClient == null) {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
            sharedClient = new OkHttpClient.Builder()
                    .addInterceptor(logging)
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build();
        }
        return sharedClient;
    }

    public static synchronized EconomicApiService getBlsService() {
        if (blsRetrofit == null) {
            blsRetrofit = new Retrofit.Builder()
                    .baseUrl(ApiConfig.BLS_BASE_URL_V2)
                    .client(buildClient())
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return blsRetrofit.create(EconomicApiService.class);
    }

    public static synchronized EconomicApiService getBeaService() {
        if (beaRetrofit == null) {
            beaRetrofit = new Retrofit.Builder()
                    .baseUrl(ApiConfig.BEA_BASE_URL)
                    .client(buildClient())
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return beaRetrofit.create(EconomicApiService.class);
    }

    public static synchronized EconomicApiService getFredService() {
        if (fredRetrofit == null) {
            fredRetrofit = new Retrofit.Builder()
                    .baseUrl(ApiConfig.FRED_BASE_URL)
                    .client(buildClient())
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return fredRetrofit.create(EconomicApiService.class);
    }
}
