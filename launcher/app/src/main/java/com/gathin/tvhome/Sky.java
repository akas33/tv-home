package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Calendar;

/**
 * What sits behind the home screen.
 *
 * A picture if there is one: drop a photo at the path in config.xml (wallpaper_path, by default
 * /sdcard/tvhome/wallpaper.jpg) and it becomes the background, under a scrim dark enough that
 * white text stays readable over anything. Put a different picture there and the TV picks it up
 * the next time the home screen is opened - nothing to rebuild.
 *
 * Otherwise a colour that follows the time of day, in peacock - deep indigo through teal, warm at
 * dusk - so the screen is never flat black.
 *
 * The decode is deliberately cheap: downscaled to the screen and 16-bit, because this is a 1 GB TV
 * and a full-size JPEG held behind the launcher is memory that the apps need more.
 */
final class Sky {

    private static final String TAG = "TvHome";

    /** top, middle, bottom of the background gradient, for each band of the day. */
    private static final int[][] BANDS = {
            {0xFF05070F, 0xFF0A1024, 0xFF131A33},   // 0  night
            {0xFF0A1A2E, 0xFF10304A, 0xFF17434F},   // 5  morning
            {0xFF07202F, 0xFF0D3845, 0xFF114A4E},   // 11 midday
            {0xFF1A0F2E, 0xFF34173A, 0xFF4A2338},   // 17 evening
            {0xFF05070F, 0xFF0A1024, 0xFF131A33},   // 22 night again
    };
    private static final int[] STARTS = {0, 5, 11, 17, 22};

    /** The gold used for the greeting on a festival day and for the line of the day. */
    static final int GOLD = 0xFFE9C46A;

    /** Decoded once and kept: re-reading the card on every resume is slow and pointless. */
    private static Bitmap cached;
    private static String cachedPath;
    private static long cachedStamp;

    private Sky() {}

    /** The picture if there is one, otherwise the colour of the hour. */
    static Drawable background(Context context) {
        Bitmap photo = wallpaper(context);
        if (photo == null) return gradient();
        BitmapDrawable picture = new BitmapDrawable(context.getResources(), photo);
        picture.setGravity(android.view.Gravity.FILL);
        return new LayerDrawable(new Drawable[]{picture, scrim()});
    }

    /** A top-to-bottom gradient for the hour we are in. */
    static GradientDrawable gradient() {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, colours());
        g.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return g;
    }

    /**
     * Darkened enough for white text, and darkest at the very top and bottom where the clock and
     * the app names sit. A bright photo would otherwise swallow both.
     */
    private static GradientDrawable scrim() {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xD9000000, 0x8C000000, 0xD9000000});
        g.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return g;
    }

    private static int[] colours() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int band = 0;
        for (int i = 0; i < STARTS.length; i++) {
            if (hour >= STARTS[i]) band = i;
        }
        return BANDS[band].clone();
    }

    /** The wallpaper file, decoded small, or null when there is not one we can read. */
    private static Bitmap wallpaper(Context context) {
        String path = context.getString(R.string.wallpaper_path).trim();
        if (path.isEmpty()) return null;
        File file = new File(path);
        if (!file.isFile() || !file.canRead()) return null;

        // Same file, same timestamp: hand back what we already decoded.
        if (cached != null && !cached.isRecycled()
                && path.equals(cachedPath) && file.lastModified() == cachedStamp) {
            return cached;
        }
        try {
            int[] screen = Photos.screen(context);
            Bitmap decoded = Photos.decode(readAll(file), screen[0], screen[1]);
            if (decoded == null) return null;
            cached = decoded;
            cachedPath = path;
            cachedStamp = file.lastModified();
            return cached;
        } catch (Throwable t) {
            Log.w(TAG, "wallpaper failed: " + path, t);
            return null;
        }
    }

    /** Capped: a wallpaper larger than this is a mistake, not a wallpaper. */
    private static byte[] readAll(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0 && buf.size() < 12 * 1024 * 1024) buf.write(chunk, 0, n);
            return buf.toByteArray();
        }
    }
}
