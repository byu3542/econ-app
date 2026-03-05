package com.economic.dashboard.api;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import java.util.concurrent.TimeUnit;

public class RetrofitClient {

    private static Retrofit blsRetrofit;
    private static Retrofit beaRetrofit;
    private static Retrofit fredRetrofit;

    public static OkHttpClient buildClient() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);
        return new OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public static EconomicApiService getBlsService() {
        if (blsRetrofit == null) {
            blsRetrofit = new Retrofit.Builder()
                    .baseUrl(ApiConfig.BLS_BASE_URL_V2)
                    .client(buildClient())
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return blsRetrofit.create(EconomicApiService.class);
    }

    public static EconomicApiService getBeaService() {
        if (beaRetrofit == null) {
            beaRetrofit = new Retrofit.Builder()
                    .baseUrl(ApiConfig.BEA_BASE_URL)
                    .client(buildClient())
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return beaRetrofit.create(EconomicApiService.class);
    }

    public static EconomicApiService getFredService() {
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
