package com.economic.dashboard.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Downscales and base64-encodes images for the Anthropic vision API. */
object ImageUtils {

    private const val MAX_DIMENSION = 1024
    private const val JPEG_QUALITY = 80

    /** Result pair: base64 data + the media type it was encoded as. */
    class EncodedImage(
        @JvmField val base64: String,
        @JvmField val mediaType: String,
        @JvmField val preview: Bitmap
    )

    /**
     * Loads a content Uri, downscales it to fit 1024px, re-encodes as JPEG,
     * and returns the base64 payload. Never call on the main thread.
     *
     * @return null if the Uri can't be read or decoded.
     */
    @JvmStatic
    fun encode(ctx: Context, uri: Uri): EncodedImage? {
        return try {
            // Bounds pass to pick a sample size without loading full pixels
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            } ?: return null

            var sample = 1
            val largest = Math.max(bounds.outWidth, bounds.outHeight)
            while (largest / sample > MAX_DIMENSION * 2) sample *= 2

            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            var bmp: Bitmap = ctx.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            } ?: return null

            // Final exact downscale
            val w = bmp.width
            val h = bmp.height
            val big = Math.max(w, h)
            if (big > MAX_DIMENSION) {
                val scale = MAX_DIMENSION.toFloat() / big
                bmp = Bitmap.createScaledBitmap(
                    bmp, Math.round(w * scale), Math.round(h * scale), true)
            }

            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            EncodedImage(b64, "image/jpeg", bmp)
        } catch (e: Exception) {
            null
        }
    }
}
