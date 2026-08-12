package com.economic.dashboard.news

import android.content.Context
import android.util.Log

import com.economic.dashboard.utils.SettingsManager

import java.util.Collections
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class NewsRepository private constructor() {

    private var cachedItemsList: MutableList<NewsItem>? = null
    private var cacheTimestamp: Long = 0

    /** Notified after every successful fetch so the UI can refresh the News
        unread badge (TICKET-17). Runs on the fetch's background thread — the
        listener is responsible for hopping to the main thread. */
    @Volatile private var onFetchComplete: Runnable? = null

    fun setOnFetchCompleteListener(r: Runnable) {
        this.onFetchComplete = r
    }

    /** True if the user has this feed category enabled (default: all on). */
    private fun categoryEnabled(categoryKey: String): Boolean {
        val ctx = appContext ?: return true // init not called — fail open
        return when (categoryKey) {
            NewsConstants.CAT_GOV -> SettingsManager.getBool(ctx, SettingsManager.KEY_NEWS_GOV, true)
            NewsConstants.CAT_MEDIA -> SettingsManager.getBool(ctx, SettingsManager.KEY_NEWS_MEDIA, true)
            NewsConstants.CAT_INTL -> SettingsManager.getBool(ctx, SettingsManager.KEY_NEWS_INTL, true)
            NewsConstants.CAT_RESEARCH -> SettingsManager.getBool(ctx, SettingsManager.KEY_NEWS_RESEARCH, true)
            else -> true
        }
    }

    /** Drops the in-memory cache so the next fetch re-reads the feed settings. */
    @Synchronized
    fun invalidateCache() {
        cachedItemsList = null
        cacheTimestamp = 0
    }

    /**
     * Fetches enabled RSS feeds in parallel, deduplicates, sorts, and caps the list.
     * Must be called from a background thread.
     *
     * @param forceRefresh ignore cache even if still fresh
     * @return merged, deduplicated list of up to 150 news items, newest first
     */
    @Synchronized
    fun fetchAllFeeds(forceRefresh: Boolean): List<NewsItem> {
        val now = System.currentTimeMillis()
        val cached = cachedItemsList
        if (!forceRefresh && cached != null
            && (now - cacheTimestamp) < CACHE_DURATION_MS
        ) {
            return cached
        }

        // Fresh executor per fetch so a stale pool never lingers
        val executor = Executors.newFixedThreadPool(8)

        val futures = ArrayList<Future<List<NewsItem>>>()
        for (feed in NewsConstants.RSS_FEEDS) {
            if (!categoryEnabled(feed.categoryKey)) continue // user disabled this category
            futures.add(executor.submit(Callable { RssParser().parse(feed.url, feed.name) }))
        }

        val all = ArrayList<NewsItem>()
        // Bound the whole feed refresh, not each future independently. With
        // more feeds than worker threads, per-future timeouts can otherwise
        // stack up across several batches and make the News screen appear to
        // hang during launch.
        val fetchDeadline = System.currentTimeMillis() + 12000L
        for (future in futures) {
            try {
                val remaining = fetchDeadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    future.cancel(true)
                    continue
                }
                val result = future.get(remaining, TimeUnit.MILLISECONDS)
                if (result != null) all.addAll(result)
            } catch (e: TimeoutException) {
                Log.w(TAG, "Feed timed out, skipping")
                future.cancel(true)
            } catch (e: Exception) {
                Log.w(TAG, "Feed future failed, skipping: " + e.message)
            }
        }

        executor.shutdownNow()

        // Sort newest first before dedup so we always keep the freshest copy
        all.sortByDescending { it.pubDateMillis }

        // ── URL deduplication (first occurrence = newest) ───────────────────────
        val urlDeduped = LinkedHashMap<String, NewsItem>()
        for (item in all) {
            val u = item.url
            if (!u.isNullOrEmpty()) {
                urlDeduped.putIfAbsent(u, item)
            }
        }
        val uniqueItems = ArrayList(urlDeduped.values)

        // ── Title deduplication (normalised 40-char prefix, first = newest) ─────
        val seenTitles = HashSet<String>()
        val titleDeduped = ArrayList<NewsItem>()
        for (item in uniqueItems) {
            val t = item.title ?: continue
            val cleaned = t.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
            val titleKey = cleaned.substring(0, minOf(40, cleaned.length))
            if (seenTitles.add(titleKey)) {
                titleDeduped.add(item)
            }
        }

        // Cap and cache
        val capped = if (titleDeduped.size > MAX_ITEMS)
            titleDeduped.subList(0, MAX_ITEMS)
        else
            titleDeduped

        val newCache = ArrayList(capped)
        cachedItemsList = newCache
        cacheTimestamp = System.currentTimeMillis()
        val cb = onFetchComplete
        cb?.run() // let the UI recompute the unread badge
        return newCache
    }

    /** Newest item's publish time (epoch millis), or 0 if the cache is cold. */
    @Synchronized
    fun getNewestPubDate(): Long {
        val items = cachedItemsList
        if (items.isNullOrEmpty()) return 0L
        var newest = 0L
        for (item in items)
            if (item.pubDateMillis > newest) newest = item.pubDateMillis
        return newest
    }

    /** How many cached items are newer than {@code sinceMillis} (for the badge). */
    @Synchronized
    fun getUnreadCount(sinceMillis: Long): Int {
        val items = cachedItemsList ?: return 0
        return items.count { it.pubDateMillis > sinceMillis }
    }

    /**
     * Unmodifiable snapshot of the last-fetched list.
     * Safe to call from any thread. Returns empty list if cache is cold.
     */
    val cachedItems: List<NewsItem>
        get() = synchronized(this) {
            val items = cachedItemsList
            if (items != null) Collections.unmodifiableList(items) else Collections.emptyList()
        }

    /** Returns the epoch-millis timestamp of the last successful fetch, or 0 if never fetched. */
    @Synchronized
    fun getLastFetchTimestamp(): Long = cacheTimestamp

    /**
     * Triggers a background fetch only if the cache is stale or cold.
     * Fire-and-forget: no callback, no LiveData updates.
     * Safe to call from the main thread.
     */
    fun fetchAllFeedsIfStale() {
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (cachedItemsList != null && (now - cacheTimestamp) < CACHE_DURATION_MS) return
        }
        // shutdown() lets the already-submitted task finish, then ends the worker
        // thread instead of leaking it (one per call, from every Activity recreate).
        val executor = Executors.newSingleThreadExecutor()
        executor.execute { fetchAllFeeds(false) }
        executor.shutdown()
    }

    companion object {
        private const val TAG = "NewsRepository"
        private const val CACHE_DURATION_MS = 15 * 60 * 1000 // 15 minutes
        private const val MAX_ITEMS = 150

        @Volatile private var instance: NewsRepository? = null

        /** Application context for reading the news-category settings.
            Set once in EconomicDashboardApplication.onCreate(). */
        @Volatile private var appContext: Context? = null

        @JvmStatic
        fun init(context: Context) {
            appContext = context.applicationContext
        }

        @JvmStatic
        @Synchronized
        fun getInstance(): NewsRepository {
            var i = instance
            if (i == null) {
                i = NewsRepository()
                instance = i
            }
            return i
        }
    }
}
