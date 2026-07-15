package com.economic.dashboard.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Downscales and base64-encodes images for the Anthropic vision API. */
public class ImageUtils {

    private static final int MAX_DIMENSION = 1024;
    private static final int JPEG_QUALITY = 80;

    /** Result pair: base64 data + the media type it was encoded as. */
    public static class EncodedImage {
        public final String base64;
        public final String mediaType;
        public final Bitmap preview;
        EncodedImage(String base64, String mediaType, Bitmap preview) {
            this.base64 = base64; this.mediaType = mediaType; this.preview = preview;
        }
    }

    /**
     * Loads a content Uri, downscales it to fit 1024px, re-encodes as JPEG,
     * and returns the base64 payload. Never call on the main thread.
     *
     * @return null if the Uri can't be read or decoded.
     */
    public static EncodedImage encode(Context ctx, Uri uri) {
        try {
            // Bounds pass to pick a sample size without loading full pixels
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / sample > MAX_DIMENSION * 2) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) return null;

            // Final exact downscale
            int w = bmp.getWidth(), h = bmp.getHeight();
            int big = Math.max(w, h);
            if (big > MAX_DIMENSION) {
                float scale = (float) MAX_DIMENSION / big;
                bmp = Bitmap.createScaledBitmap(bmp,
                        Math.round(w * scale), Math.round(h * scale), true);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            String b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            return new EncodedImage(b64, "image/jpeg", bmp);
        } catch (Exception e) {
            return null;
        }
    }
}
