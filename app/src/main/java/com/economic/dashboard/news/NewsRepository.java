package com.economic.dashboard.news;

import android.content.Context;
import android.util.Log;

import com.economic.dashboard.utils.SettingsManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class NewsRepository {

    private static final String TAG              = "NewsRepository";
    private static final int    CACHE_DURATION_MS = 15 * 60 * 1000; // 15 minutes
    private static final int    MAX_ITEMS         = 150;

    private static NewsRepository instance;

    /** Application context for reading the news-category settings.
        Set once in EconomicDashboardApplication.onCreate(). */
    private static Context appContext;

    private List<NewsItem> cachedItems    = null;
    private long           cacheTimestamp = 0;

    /** Notified after every successful fetch so the UI can refresh the News
        unread badge (TICKET-17). Runs on the fetch's background thread — the
        listener is responsible for hopping to the main thread. */
    private volatile Runnable onFetchComplete = null;

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public void setOnFetchCompleteListener(Runnable r) {
        this.onFetchComplete = r;
    }

    public static synchronized NewsRepository getInstance() {
        if (instance == null) instance = new NewsRepository();
        return instance;
    }

    private NewsRepository() {}

    /** True if the user has this feed category enabled (default: all on). */
    private static boolean categoryEnabled(String categoryKey) {
        if (appContext == null) return true; // init not called — fail open
        switch (categoryKey) {
            case NewsConstants.CAT_GOV:
                return SettingsManager.getBool(appContext, SettingsManager.KEY_NEWS_GOV, true);
            case NewsConstants.CAT_MEDIA:
                return SettingsManager.getBool(appContext, SettingsManager.KEY_NEWS_MEDIA, true);
            case NewsConstants.CAT_INTL:
                return SettingsManager.getBool(appContext, SettingsManager.KEY_NEWS_INTL, true);
            case NewsConstants.CAT_RESEARCH:
                return SettingsManager.getBool(appContext, SettingsManager.KEY_NEWS_RESEARCH, true);
            default:
                return true;
        }
    }

    /** Drops the in-memory cache so the next fetch re-reads the feed settings. */
    public synchronized void invalidateCache() {
        cachedItems = null;
        cacheTimestamp = 0;
    }

    /**
     * Fetches enabled RSS feeds in parallel, deduplicates, sorts, and caps the list.
     * Must be called from a background thread.
     *
     * @param forceRefresh ignore cache even if still fresh
     * @return merged, deduplicated list of up to 150 news items, newest first
     */
    public synchronized List<NewsItem> fetchAllFeeds(boolean forceRefresh) {
        long now = System.currentTimeMillis();
        if (!forceRefresh && cachedItems != null
                && (now - cacheTimestamp) < CACHE_DURATION_MS) {
            return cachedItems;
        }

        // Fresh executor per fetch so a stale pool never lingers
        ExecutorService executor = Executors.newFixedThreadPool(8);

        List<Future<List<NewsItem>>> futures = new ArrayList<>();
        for (String[] feed : NewsConstants.RSS_FEEDS) {
            if (!categoryEnabled(feed[2])) continue; // user disabled this category
            final String url  = feed[0];
            final String name = feed[1];
            futures.add(executor.submit(() -> new RssParser().parse(url, name)));
        }

        List<NewsItem> all = new ArrayList<>();
        for (Future<List<NewsItem>> future : futures) {
            try {
                List<NewsItem> result = future.get(12000, TimeUnit.MILLISECONDS);
                if (result != null) all.addAll(result);
            } catch (TimeoutException e) {
                Log.w(TAG, "Feed timed out, skipping");
            } catch (Exception e) {
                Log.w(TAG, "Feed future failed, skipping: " + e.getMessage());
            }
        }

        executor.shutdown();

        // Sort newest first before dedup so we always keep the freshest copy
        Collections.sort(all, (a, b) -> Long.compare(b.pubDateMillis, a.pubDateMillis));

        // ── URL deduplication (first occurrence = newest) ───────────────────────
        Map<String, NewsItem> urlDeduped = new LinkedHashMap<>();
        for (NewsItem item : all) {
            if (item.url != null && !item.url.isEmpty()) {
                urlDeduped.putIfAbsent(item.url, item);
            }
        }
        List<NewsItem> uniqueItems = new ArrayList<>(urlDeduped.values());

        // ── Title deduplication (normalised 40-char prefix, first = newest) ─────
        Set<String> seenTitles = new HashSet<>();
        List<NewsItem> titleDeduped = new ArrayList<>();
        for (NewsItem item : uniqueItems) {
            if (item.title == null) continue;
            String cleaned  = item.title.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
            String titleKey = cleaned.substring(0, Math.min(40, cleaned.length()));
            if (seenTitles.add(titleKey)) {
                titleDeduped.add(item);
            }
        }

        // Cap and cache
        List<NewsItem> capped = titleDeduped.size() > MAX_ITEMS
                ? titleDeduped.subList(0, MAX_ITEMS)
                : titleDeduped;

        cachedItems    = new ArrayList<>(capped);
        cacheTimestamp = System.currentTimeMillis();
        Runnable cb = onFetchComplete;
        if (cb != null) cb.run(); // let the UI recompute the unread badge
        return cachedItems;
    }

    /** Newest item's publish time (epoch millis), or 0 if the cache is cold. */
    public synchronized long getNewestPubDate() {
        if (cachedItems == null || cachedItems.isEmpty()) return 0L;
        long newest = 0L;
        for (NewsItem item : cachedItems)
            if (item.pubDateMillis > newest) newest = item.pubDateMillis;
        return newest;
    }

    /** How many cached items are newer than {@code sinceMillis} (for the badge). */
    public synchronized int getUnreadCount(long sinceMillis) {
        if (cachedItems == null) return 0;
        int n = 0;
        for (NewsItem item : cachedItems)
            if (item.pubDateMillis > sinceMillis) n++;
        return n;
    }

    /**
     * Returns an unmodifiable snapshot of the last-fetched list.
     * Safe to call from any thread. Returns empty list if cache is cold.
     */
    public synchronized List<NewsItem> getCachedItems() {
        return cachedItems != null
                ? Collections.unmodifiableList(cachedItems)
                : Collections.emptyList();
    }

    /** Returns the epoch-millis timestamp of the last successful fetch, or 0 if never fetched. */
    public synchronized long getLastFetchTimestamp() {
        return cacheTimestamp;
    }

    /**
     * Triggers a background fetch only if the cache is stale or cold.
     * Fire-and-forget: no callback, no LiveData updates.
     * Safe to call from the main thread.
     */
    public void fetchAllFeedsIfStale() {
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (cachedItems != null && (now - cacheTimestamp) < CACHE_DURATION_MS) return;
        }
        Executors.newSingleThreadExecutor().execute(() -> fetchAllFeeds(false));
    }
}
