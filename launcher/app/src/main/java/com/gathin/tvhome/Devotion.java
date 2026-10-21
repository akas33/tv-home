package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.Calendar;

/**
 * The line of the day on the home screen, and the festival greeting.
 *
 * Both come out of res/values/config.xml so they can be changed without touching Java: the lines
 * live in daily_quotes, the family's name in family_name. Nothing here touches the network - the
 * line is picked from the date, so the same line shows all day and a different one tomorrow.
 */
final class Devotion {

    private Devotion() {}

    /** Today's line, or null when the list in config.xml is empty. */
    static String quote(Context context) {
        String[] lines = context.getResources().getStringArray(R.array.daily_quotes);
        if (lines.length == 0) return null;
        int day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        return lines[Math.abs(day) % lines.length].trim();
    }

    /**
     * The greeting: on a festival day "Happy Dussehra, Manju and family", otherwise the ordinary
     * time-of-day greeting passed in. The festival wording comes from strings.xml so it can be
     * translated; the family's name is skipped when config.xml leaves family_name blank.
     */
    static String greeting(Context context, String timeOfDay) {
        String festival = null;
        try {
            festival = Panchang.festivalOn(Calendar.getInstance());
        } catch (Exception ignored) {
            // the greeting still works without it
        }
        if (festival == null) return timeOfDay;

        String family = context.getString(R.string.family_name).trim();
        return family.isEmpty()
                ? context.getString(R.string.festival_greeting, festival)
                : context.getString(R.string.festival_greeting_family, festival, family);
    }

    /** True when there is a song configured for the line of the day. */
    static boolean hasSong(Context context) {
        return !context.getString(R.string.quote_video_url).trim().isEmpty();
    }

    /**
     * Plays the configured song. The TV's YouTube app is asked by name first, because leaving the
     * package open on a TV with a browser installed can land a video in a browser instead.
     */
    static boolean playSong(Context context) {
        String url = context.getString(R.string.quote_video_url).trim();
        if (url.isEmpty()) return false;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        for (String pkg : new String[]{"com.google.android.youtube.tv", "com.google.android.youtube", null}) {
            intent.setPackage(pkg);
            try {
                context.startActivity(intent);
                return true;
            } catch (Exception ignored) {
                // not installed, or no activity for it: try the next
            }
        }
        return false;
    }
}
