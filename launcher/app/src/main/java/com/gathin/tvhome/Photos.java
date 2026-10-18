package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;

/** Decoding photos small enough for a 1 GB TV: never bigger than the screen, 16-bit colour. */
final class Photos {

    private Photos() {}

    static int[] screen(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return new int[]{Math.max(640, dm.widthPixels), Math.max(360, dm.heightPixels)};
    }

    /** Decodes downscaled to roughly the screen size, or null if the bytes are not an image. */
    static Bitmap decode(byte[] data, int maxW, int maxH) {
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, probe);
            if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;
            int sample = 1;
            while (probe.outWidth / (sample * 2) >= maxW && probe.outHeight / (sample * 2) >= maxH) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;  // half the memory, fine for photos
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Throwable t) {
            return null;   // OutOfMemoryError included: a photo is never worth crashing for
        }
    }
}
