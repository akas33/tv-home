package com.gathin.tvhome;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * The Hindu calendar date, worked out on the TV with no network and no data files: tithi (lunar
 * day), paksha (waxing or waning) and nakshatra (the moon's star).
 *
 * The tithi is the moon's elongation from the sun divided into thirty parts; the nakshatra is the
 * moon's sidereal longitude divided into twenty-seven. Sun and moon positions come from the
 * abridged theory in Meeus, with the Lahiri ayanamsa for the sidereal frame.
 *
 * This is an approximation. The longitude terms are truncated and the ayanamsa is linear, so near
 * the moment a tithi turns over it can disagree with a published panchang by a few hours. It was
 * checked against known new moons (18 January and 17 February 2026), both of which come out as
 * Amavasya, and the tithi advances by exactly one each day as it must.
 */
final class Panchang {

    static final String[] TITHI = {
            "Pratipada", "Dwitiya", "Tritiya", "Chaturthi", "Panchami", "Shashthi", "Saptami",
            "Ashtami", "Navami", "Dashami", "Ekadashi", "Dwadashi", "Trayodashi", "Chaturdashi",
            "Purnima",
            "Pratipada", "Dwitiya", "Tritiya", "Chaturthi", "Panchami", "Shashthi", "Saptami",
            "Ashtami", "Navami", "Dashami", "Ekadashi", "Dwadashi", "Trayodashi", "Chaturdashi",
            "Amavasya"
    };

    static final String[] NAKSHATRA = {
            "Ashwini", "Bharani", "Krittika", "Rohini", "Mrigashira", "Ardra", "Punarvasu",
            "Pushya", "Ashlesha", "Magha", "Purva Phalguni", "Uttara Phalguni", "Hasta", "Chitra",
            "Swati", "Vishakha", "Anuradha", "Jyeshtha", "Mula", "Purva Ashadha", "Uttara Ashadha",
            "Shravana", "Dhanishta", "Shatabhisha", "Purva Bhadrapada", "Uttara Bhadrapada", "Revati"
    };

    final String tithi, nakshatra, paksha;
    final int tithiIndex;        // 1..30
    /** Today's festival, or null. Set by festivalOn(). */
    String festival;

    private Panchang(int tithiIndex, String tithi, String nakshatra, String paksha) {
        this.tithiIndex = tithiIndex;
        this.tithi = tithi;
        this.nakshatra = nakshatra;
        this.paksha = paksha;
    }

    /** "Shukla Navami" and the like, for the moment given. */
    static Panchang at(Calendar local) {
        double julian = julianDay(local);
        double sun = sunLongitude(julian);
        double moon = moonLongitude(julian);

        double elongation = norm(moon - sun);
        int t = (int) (elongation / 12.0);                  // 0..29
        double sidereal = norm(moon - ayanamsa(julian));
        int n = (int) (sidereal / (360.0 / 27.0));          // 0..26

        return new Panchang(t + 1, TITHI[t], NAKSHATRA[n], t < 15 ? "Shukla" : "Krishna");
    }

    /** Reads naturally on screen: "Shukla Navami, Shravana". */
    String line() {
        return paksha + " " + tithi + ", " + nakshatra;
    }

    // ---- the Hindu month, and festivals ----

    static final String[] MONTHS = {
            "Chaitra", "Vaishakha", "Jyeshtha", "Ashadha", "Shravana", "Bhadrapada", "Ashwina",
            "Kartika", "Margashirsha", "Pausha", "Magha", "Phalguna"
    };

    /** name, purnimanta month, tithi (1..30). North Indian reckoning. */
    private static final String[][] FESTIVALS = {
            {"Maha Shivaratri", "Phalguna", "29"},
            {"Holi", "Phalguna", "15"},
            {"Ram Navami", "Chaitra", "9"},
            {"Hanuman Jayanti", "Chaitra", "15"},
            {"Guru Purnima", "Ashadha", "15"},
            {"Raksha Bandhan", "Shravana", "15"},
            {"Janmashtami", "Bhadrapada", "23"},
            {"Ganesh Chaturthi", "Bhadrapada", "4"},
            {"Navratri begins", "Ashwina", "1"},
            {"Dussehra", "Ashwina", "10"},
            {"Sharad Purnima", "Ashwina", "15"},
            {"Karva Chauth", "Kartika", "19"},
            {"Diwali", "Kartika", "30"},
            {"Govardhan Puja", "Kartika", "1"},
            {"Bhai Dooj", "Kartika", "2"},
    };

    /**
     * North India keeps purnimanta months, which end at the full moon: the same as the amanta
     * month during the waxing fortnight, and one ahead during the waning one. The amanta month is
     * named from the sidereal sign the sun occupies at the new moon the month began at.
     */
    private static int monthIndex(double julian) {
        double nm = previousNewMoon(julian);
        int amanta = ((int) (norm(sunLongitude(nm) - ayanamsa(nm)) / 30.0) + 1) % 12;
        double elongation = norm(moonLongitude(julian) - sunLongitude(julian));
        boolean waxing = elongation < 180.0;
        return waxing ? amanta : (amanta + 1) % 12;
    }

    /** The elongation falls as we step back and wraps past zero at the new moon. */
    private static double previousNewMoon(double julian) {
        double j = julian;
        double previous = norm(moonLongitude(j) - sunLongitude(j));
        for (int i = 0; i < 300; i++) {
            j -= 0.125;
            double current = norm(moonLongitude(j) - sunLongitude(j));
            if (current > previous) return j + 0.125;
            previous = current;
        }
        return julian;
    }

    /**
     * The festival on this date, or null. A tithi can start and end inside one day, so the day is
     * sampled at several hours rather than once.
     */
    static String festivalOn(Calendar day) {
        Calendar probe = (Calendar) day.clone();
        for (int hour : new int[]{6, 10, 14, 18, 21}) {
            probe.set(Calendar.HOUR_OF_DAY, hour);
            probe.set(Calendar.MINUTE, 0);
            probe.set(Calendar.SECOND, 0);
            double julian = julianDay(probe);
            int t = (int) (norm(moonLongitude(julian) - sunLongitude(julian)) / 12.0) + 1;
            String month = MONTHS[monthIndex(julian)];
            for (String[] f : FESTIVALS) {
                if (month.equals(f[1]) && t == Integer.parseInt(f[2])) return f[0];
            }
        }
        return null;
    }

    /** "Diwali today" or "Dussehra in 5 days", looking a fortnight ahead. Null if nothing is near. */
    static String upcoming(Calendar from) {
        Calendar day = (Calendar) from.clone();
        for (int ahead = 0; ahead <= 14; ahead++) {
            String f = festivalOn(day);
            if (f != null) {
                if (ahead == 0) return f + " today";
                if (ahead == 1) return f + " tomorrow";
                return f + " in " + ahead + " days";
            }
            day.add(Calendar.DAY_OF_MONTH, 1);
        }
        return null;
    }

    // ---- astronomy ----

    private static double julianDay(Calendar local) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(local.getTimeInMillis());
        int y = utc.get(Calendar.YEAR);
        int m = utc.get(Calendar.MONTH) + 1;
        int d = utc.get(Calendar.DAY_OF_MONTH);
        double hour = utc.get(Calendar.HOUR_OF_DAY)
                + utc.get(Calendar.MINUTE) / 60.0
                + utc.get(Calendar.SECOND) / 3600.0;
        if (m <= 2) {
            y -= 1;
            m += 12;
        }
        int a = y / 100;
        int b = 2 - a + a / 4;
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1))
                + d + b - 1524.5 + hour / 24.0;
    }

    private static double centuries(double julian) {
        return (julian - 2451545.0) / 36525.0;
    }

    private static double sunLongitude(double julian) {
        double t = centuries(julian);
        double l0 = norm(280.46646 + 36000.76983 * t + 0.0003032 * t * t);
        double m = Math.toRadians(norm(357.52911 + 35999.05029 * t - 0.0001537 * t * t));
        double c = (1.914602 - 0.004817 * t) * Math.sin(m)
                + (0.019993 - 0.000101 * t) * Math.sin(2 * m)
                + 0.000289 * Math.sin(3 * m);
        return norm(l0 + c);
    }

    private static double moonLongitude(double julian) {
        double t = centuries(julian);
        double lp = norm(218.3164477 + 481267.88123421 * t);
        double d = Math.toRadians(norm(297.8501921 + 445267.1114034 * t));
        double ms = Math.toRadians(norm(357.5291092 + 35999.0502909 * t));
        double mp = Math.toRadians(norm(134.9633964 + 477198.8675055 * t));
        double f = Math.toRadians(norm(93.272095 + 483202.0175233 * t));
        return norm(lp
                + 6.288774 * Math.sin(mp)
                + 1.274027 * Math.sin(2 * d - mp)
                + 0.658314 * Math.sin(2 * d)
                + 0.213618 * Math.sin(2 * mp)
                - 0.185116 * Math.sin(ms)
                - 0.114332 * Math.sin(2 * f)
                + 0.058793 * Math.sin(2 * d - 2 * mp)
                + 0.057066 * Math.sin(2 * d - ms - mp)
                + 0.053322 * Math.sin(2 * d + mp)
                + 0.045758 * Math.sin(2 * d - ms));
    }

    /** Lahiri ayanamsa, linear approximation: good to about a minute of arc over this decade. */
    private static double ayanamsa(double julian) {
        return 23.85 + (julian - 2451545.0) / 365.25 * 0.013972;
    }

    private static double norm(double degrees) {
        double r = degrees % 360.0;
        return r < 0 ? r + 360.0 : r;
    }
}
