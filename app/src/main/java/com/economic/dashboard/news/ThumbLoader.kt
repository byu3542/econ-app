package com.economic.dashboard.news

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Minimal thumbnail loader (memory-cached, downsampled) so the news list
 * doesn't need a full image library for the occasional RSS enclosure.
 */
object ThumbLoader {

    private const val MAX_DIM_PX = 256
    private val CACHE = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, b: Bitmap): Int = b.byteCount
    }
    // Image requests are independent of the RSS fetch. Four workers keep the
    // first visible cards moving without creating an unbounded request storm.
    private val EXECUTOR = Executors.newFixedThreadPool(4)
    private val WAITERS = ConcurrentHashMap<String, CopyOnWriteArrayList<WeakReference<ImageView>>>()
    private val MAIN = Handler(Looper.getMainLooper())
    private val CLIENT = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Loads url into view; hides the view on failure. Recycler-safe via view tag. */
    @JvmStatic
    fun load(view: ImageView, url: String?) {
        if (url.isNullOrEmpty()) {
            view.tag = null
            view.setImageDrawable(null)
            view.visibility = View.GONE
            return
        }
        view.tag = url
        val cached = CACHE.get(url)
        if (cached != null) {
            view.setImageBitmap(cached)
            view.visibility = View.VISIBLE
            return
        }
        view.visibility = View.GONE

        val newWaiters = CopyOnWriteArrayList<WeakReference<ImageView>>()
        val waiters = WAITERS.putIfAbsent(url, newWaiters)
        val shouldStart = waiters == null
        val requestWaiters = waiters ?: newWaiters
        requestWaiters.add(WeakReference(view))

        // Only the first visible card for a URL starts network work. Other
        // cards wait for the same result instead of duplicating the request.
        if (shouldStart) {
            EXECUTOR.execute {
                val bmp = fetch(url)
                WAITERS.remove(url)
                if (bmp != null) CACHE.put(url, bmp)
                deliver(url, bmp, requestWaiters)
            }
        }
    }

    private fun deliver(url: String, bmp: Bitmap?, waiters: List<WeakReference<ImageView>>) {
        MAIN.post {
            for (ref in waiters) {
                val view = ref.get()
                if (view == null || url != view.tag) continue
                if (bmp != null) {
                    view.setImageBitmap(bmp)
                    view.visibility = View.VISIBLE
                } else {
                    view.setImageDrawable(null)
                    view.visibility = View.GONE
                }
            }
        }
    }

    private fun fetch(url: String): Bitmap? {
        try {
            CLIENT.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val body = r.body ?: return null
                val bytes = body.bytes()
                val opts = BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var sample = 1
                while (opts.outWidth / (sample * 2) >= MAX_DIM_PX
                    && opts.outHeight / (sample * 2) >= MAX_DIM_PX
                ) sample *= 2
                opts.inJustDecodeBounds = false
                opts.inSampleSize = sample
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } catch (e: Exception) {
            return null
        }
    }
}
