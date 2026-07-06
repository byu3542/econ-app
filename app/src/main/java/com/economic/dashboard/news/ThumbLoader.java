package com.economic.dashboard.news;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

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
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = new OkHttpClient();

    private ThumbLoader() {}

    /** Loads url into view; hides the view on failure. Recycler-safe via view tag. */
    public static void load(ImageView view, String url) {
        if (url == null || url.isEmpty()) { view.setVisibility(View.GONE); return; }
        view.setTag(url);
        Bitmap cached = CACHE.get(url);
        if (cached != null) { view.setImageBitmap(cached); view.setVisibility(View.VISIBLE); return; }
        view.setVisibility(View.GONE);
        EXECUTOR.execute(() -> {
            Bitmap bmp = fetch(url);
            if (bmp == null) return;
            CACHE.put(url, bmp);
            MAIN.post(() -> {
                if (url.equals(view.getTag())) {
                    view.setImageBitmap(bmp);
                    view.setVisibility(View.VISIBLE);
                }
            });
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
