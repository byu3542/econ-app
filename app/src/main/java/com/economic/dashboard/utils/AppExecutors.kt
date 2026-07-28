package com.economic.dashboard.utils

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Global executor pools for the whole app.
 *
 * Replaces the previous pattern of spawning a raw `new Thread(...)` per
 * network call, which created unbounded concurrency (18+ threads during a full
 * dashboard refresh). Network work is bounded by a fixed pool; database work is
 * serialized on a single thread so Room writes never race each other.
 *
 * A Kotlin `object` is itself a thread-safe lazy singleton (guaranteed by the
 * JVM's class-init locking), so this replaces the old manual
 * double-checked-locking `getInstance()` — callers now call these functions
 * directly (`AppExecutors.networkIO()`), same as batch 12's planned cleanup
 * would have done for every other true singleton in this codebase.
 */
object AppExecutors {

    private val networkIOExecutor: ExecutorService = Executors.newFixedThreadPool(4)
    private val diskIOExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainThreadHandler = Handler(Looper.getMainLooper())

    /** Bounded pool for network I/O (API fetches). */
    @JvmStatic
    fun networkIO(): ExecutorService = networkIOExecutor

    /** Single thread for Room / disk work — serializes DB writes. */
    @JvmStatic
    fun diskIO(): ExecutorService = diskIOExecutor

    /** Post work back to the Android main thread. */
    @JvmStatic
    fun mainThread(r: Runnable) {
        mainThreadHandler.post(r)
    }
}
