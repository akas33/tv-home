package com.gathin.tvhome;

import android.graphics.drawable.GradientDrawable;

import java.util.Calendar;

/**
 * The home screen's background colour, which follows the time of day instead of sitting on flat
 * black. The palette is peacock - deep indigo through teal, warm gold at dusk - so the screen has
 * some depth and the gold accents on the greeting and the line of the day have something to sit on.
 *
 * Kept separate from the screensaver's own palette on purpose: the screensaver is looked at for
 * minutes at a time and can afford to be dark, while the home screen is glanced at and needs to
 * carry white text and app artwork over it.
 */
final class Sky {

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

    private Sky() {}

    /** A top-to-bottom gradient for the hour we are in. */
    static GradientDrawable background() {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, colours());
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
}
