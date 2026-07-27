package com.economic.dashboard.news;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Minimal thumbnail loader (memory-cached, downsampled) so the news list
 * doesn't need a full image library for the occasional RSS enclosure.
 */
public final class ThumbLoader {

    private static final int MAX_DIM_PX = 256;
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 16)) {
                @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
            };
    // Image requests are independent of the RSS fetch. Four workers keep the
    // first visible cards moving without creating an unbounded request storm.
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);
    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<WeakReference<ImageView>>> WAITERS =
            new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private ThumbLoader() {}

    /** Loads url into view; hides the view on failure. Recycler-safe via view tag. */
    public static void load(ImageView view, String url) {
        if (url == null || url.isEmpty()) {
            view.setTag(null);
            view.setImageDrawable(null);
            view.setVisibility(View.GONE);
            return;
        }
        view.setTag(url);
        Bitmap cached = CACHE.get(url);
        if (cached != null) { view.setImageBitmap(cached); view.setVisibility(View.VISIBLE); return; }
        view.setVisibility(View.GONE);

        CopyOnWriteArrayList<WeakReference<ImageView>> newWaiters =
                new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<WeakReference<ImageView>> waiters = WAITERS.putIfAbsent(url, newWaiters);
        boolean shouldStart = waiters == null;
        if (shouldStart) waiters = newWaiters;
        final CopyOnWriteArrayList<WeakReference<ImageView>> requestWaiters = waiters;
        requestWaiters.add(new WeakReference<>(view));

        // Only the first visible card for a URL starts network work. Other
        // cards wait for the same result instead of duplicating the request.
        if (shouldStart) {
            EXECUTOR.execute(() -> {
                Bitmap bmp = fetch(url);
                WAITERS.remove(url);
                if (bmp != null) CACHE.put(url, bmp);
                deliver(url, bmp, requestWaiters);
            });
        }
    }

    private static void deliver(String url, Bitmap bmp,
                                List<WeakReference<ImageView>> waiters) {
        MAIN.post(() -> {
            for (WeakReference<ImageView> ref : waiters) {
                ImageView view = ref.get();
                if (view == null || !url.equals(view.getTag())) continue;
                if (bmp != null) {
                    view.setImageBitmap(bmp);
                    view.setVisibility(View.VISIBLE);
                } else {
                    view.setImageDrawable(null);
                    view.setVisibility(View.GONE);
                }
            }
        });
    }

    private static Bitmap fetch(String url) {
        try (Response r = CLIENT.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) return null;
            byte[] bytes = r.body().bytes();
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= MAX_DIM_PX
                    && opts.outHeight / (sample * 2) >= MAX_DIM_PX) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (Exception e) {
            return null;
        }
    }
}
