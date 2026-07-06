package com.economic.dashboard.utils;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Global executor pools for the whole app.
 *
 * Replaces the previous pattern of spawning a raw {@code new Thread(...)} per
 * network call, which created unbounded concurrency (18+ threads during a full
 * dashboard refresh). Network work is bounded by a fixed pool; database work is
 * serialized on a single thread so Room writes never race each other.
 */
public class AppExecutors {

    private static volatile AppExecutors instance;

    private final ExecutorService networkIO;
    private final ExecutorService diskIO;
    private final Handler mainThread;

    private AppExecutors() {
        networkIO = Executors.newFixedThreadPool(4);
        diskIO = Executors.newSingleThreadExecutor();
        mainThread = new Handler(Looper.getMainLooper());
    }

    public static AppExecutors getInstance() {
        if (instance == null) {
            synchronized (AppExecutors.class) {
                if (instance == null) instance = new AppExecutors();
            }
        }
        return instance;
    }

    /** Bounded pool for network I/O (API fetches). */
    public ExecutorService networkIO() { return networkIO; }

    /** Single thread for Room / disk work — serializes DB writes. */
    public ExecutorService diskIO() { return diskIO; }

    /** Post work back to the Android main thread. */
    public void mai