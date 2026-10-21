package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Calendar;

/**
 * What sits behind the home screen.
 *
 * A folder of pictures, one of which is shown for the day: put as many as you like in the folder
 * named by wallpaper_dir in config.xml and the TV works through them, a different one each day,
 * in a fixed order so the same date always gives the same picture. Add more any time; the TV
 * notices within a minute and never needs rebuilding.
 *
 * With no folder, or no pictures in it, the background is a colour that follows the time of day,
 * so the screen is never flat black.
 *
 * The decode is deliberately cheap - downscaled to the screen, 16-bit - because this is a 1 GB TV
 * and a full-size photo held behind the launcher is memory the apps need more. Only one picture is
 * ever in memory, and it is kept until the day or the folder changes.
 */
final class Sky {

    private static final String TAG = "TvHome";
    private static final String[] KINDS = {".jpg", ".jpeg", ".png", ".webp", ".jfif", ".bmp"};

    /** top, middle, bottom of the background gradient, for each band of the day. */
    private static final int[][] BANDS = {
            {0xFF07091C, 0xFF0D1030, 0xFF161A42},   // 0  night
            {0xFF0A1330, 0xFF14204A, 0xFF1E2A5C},   // 5  morning
            {0xFF0B1636, 0xFF152354, 0xFF203066},   // 11 midday
            {0xFF1A0E34, 0xFF2E1348, 0xFF461B4E},   // 17 evening
            {0xFF07091C, 0xFF0D1030, 0xFF161A42},   // 22 night again
    };
    private static final int[] STARTS = {0, 5, 11, 17, 22};

    /** The accent: the pink the tiles and the line of the day pick up. */
    static final int ACCENT = 0xFFFF2E63;
    /** The warm gold kept for festival days. */
    static final int GOLD = 0xFFE9C46A;

    private static Bitmap cached;
    private static String cachedKey;

    private Sky() {}

    /** The picture of the day if there is one, otherwise the colour of the hour. */
    static Drawable background(Context context) {
        Bitmap photo = today(context);
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
     * Darkest at the top and bottom, where the bento row and the app names are, and lightest
     * across the middle so the picture still shows. Without this a bright photo swallows the text.
     */
    private static GradientDrawable scrim() {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xD6000000, 0x66000000, 0x7A000000, 0xE0000000});
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

    /** Today's picture, decoded small, or null when the folder has nothing we can read. */
    private static Bitmap today(Context context) {
        String dir = context.getString(R.string.wallpaper_dir).trim();
        if (dir.isEmpty()) return null;
        File[] pictures = list(new File(dir));
        if (pictures.length == 0) return null;

        // Sorted by name, so the order is stable, and keyed to the date, so it holds all day.
        Calendar now = Calendar.getInstance();
        int index = Math.abs(now.get(Calendar.YEAR) * 366 + now.get(Calendar.DAY_OF_YEAR))
                % pictures.length;
        File pick = pictures[index];
        String key = pick.getAbsolutePath() + "@" + pick.lastModified() + "#" + pictures.length;
        if (cached != null && !cached.isRecycled() && key.equals(cachedKey)) return cached;

        try {
            int[] screen = Photos.screen(context);
            Bitmap decoded = Photos.decode(readAll(pick), screen[0], screen[1]);
            if (decoded == null) {
                Log.w(TAG, "could not decode wallpaper " + pick.getName());
                return null;
            }
            cached = decoded;
            cachedKey = key;
            Log.i(TAG, "wallpaper " + (index + 1) + " of " + pictures.length + ": " + pick.getName());
            return cached;
        } catch (Throwable t) {
            Log.w(TAG, "wallpaper failed: " + pick, t);
            return null;
        }
    }

    /** Readable picture files in the folder, by name. A single file at that path also works. */
    private static File[] list(File dir) {
        if (dir.isFile()) return new File[]{dir};
        File[] all = dir.listFiles();
        if (all == null) return new File[0];
        java.util.List<File> keep = new java.util.ArrayList<>();
        for (File f : all) {
            if (!f.isFile() || !f.canRead()) continue;
            String name = f.getName().toLowerCase();
            for (String kind : KINDS) {
                if (name.endsWith(kind)) {
                    keep.add(f);
                    break;
                }
            }
        }
        File[] out = keep.toArray(new File[0]);
        Arrays.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /** Capped: a picture larger than this is a mistake, not a picture. */
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
