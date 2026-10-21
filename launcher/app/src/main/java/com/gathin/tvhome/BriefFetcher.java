package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the TV fetches for itself while the screensaver is on: today's weather from Open-Meteo
 * (free, no account) and headlines from an RSS feed, with a picture where the feed offers one.
 *
 * Both are optional and configured in res/values/config.xml; leave a value blank to skip it.
 * Runs at most once every 30 minutes, on a short-lived background thread, and nothing at all
 * while the screensaver is not showing.
 */
final class BriefFetcher {
    private static final String TAG = "TvBrief";
    private static final long STALE_MS = 30L * 60 * 1000;
    private static final long RETRY_MS = 5L * 60 * 1000;
    private static final int MAX_HEADLINES = 6;
    private static final int MAX_IMAGES = 3;        // only the first few carry a picture
    private static final int HOURS_SHOWN = 4;       // how far the hourly strip reaches

    /** Today's weather. Null until the first successful fetch. */
    static final class Weather {
        final String place, condition;
        final long temp, high, low;
        final int rainChance;                        // -1 when the forecast omits it

        Weather(String place, String condition, long temp, long high, long low, int rainChance) {
            this.place = place;
            this.condition = condition;
            this.temp = temp;
            this.high = high;
            this.low = low;
            this.rainChance = rainChance;
        }
    }

    /** The next few hours, for the strip under the temperature. */
    static final class Hour {
        final String at;        // "2 PM"
        final long temp;

        Hour(String at, long temp) {
            this.at = at;
            this.temp = temp;
        }
    }

    /** One headline, with its picture once it has been downloaded. */
    static final class Headline {
        final String title;
        final String imageUrl;                       // may be null
        volatile Bitmap image;                       // filled in later, may stay null

        Headline(String title, String imageUrl) {
            this.title = title;
            this.imageUrl = imageUrl;
        }
    }

    /** Today's air quality. Null until the first successful fetch. */
    static final class Air {
        final int aqi;
        final String word;      // "Good", "Moderate", ...
        final int colour;       // for the number on screen

        Air(int aqi) {
            this.aqi = aqi;
            if (aqi <= 50)        { word = "Good";      colour = 0xFF7BD389; }
            else if (aqi <= 100)  { word = "Moderate";  colour = 0xFFE9C46A; }
            else if (aqi <= 150)  { word = "Poor";      colour = 0xFFF4A261; }
            else if (aqi <= 200)  { word = "Unhealthy"; colour = 0xFFE76F51; }
            else if (aqi <= 300)  { word = "Very poor"; colour = 0xFFD64550; }
            else                  { word = "Hazardous"; colour = 0xFFB5179E; }
        }
    }

    static volatile Weather weather;
    static volatile Air air;
    static volatile List<Hour> hours = Collections.emptyList();
    static volatile List<Headline> headlines = Collections.emptyList();

    private static volatile long lastAttempt = 0;
    private static volatile boolean busy = false;

    private BriefFetcher() {}

    static void refreshIfStale(Context context) {
        long now = System.currentTimeMillis();
        if (busy || now - lastAttempt < STALE_MS) return;
        busy = true;
        lastAttempt = now;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            boolean any = false;
            try {
                try {
                    weather = fetchWeather(app);
                    any |= weather != null;
                } catch (Exception e) {
                    Log.w(TAG, "weather failed", e);
                }
                try {
                    air = fetchAir(app);
                    any |= air != null;
                } catch (Exception e) {
                    Log.w(TAG, "air quality failed", e);
                }
                try {
                    List<Headline> fresh = fetchHeadlines(app);
                    if (!fresh.isEmpty()) {
                        headlines = Collections.unmodifiableList(fresh);
                        any = true;
                        fetchImages(app, fresh);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "headlines failed", e);
                }
                Log.i(TAG, "brief refreshed: weather=" + (weather != null)
                        + " headlines=" + headlines.size());
                if (!any) lastAttempt = System.currentTimeMillis() - STALE_MS + RETRY_MS;
            } finally {
                busy = false;
            }
        }, "brief-fetch").start();
    }

    // ---- weather ----

    private static Weather fetchWeather(Context c) throws Exception {
        String lat = c.getString(R.string.weather_lat).trim();
        String lon = c.getString(R.string.weather_lon).trim();
        if (lat.isEmpty() || lon.isEmpty()) return null;
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code"
                + "&hourly=temperature_2m"
                + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&forecast_days=2&timezone=auto";
        JSONObject j = new JSONObject(get(url));
        JSONObject cur = j.getJSONObject("current");
        JSONObject day = j.getJSONObject("daily");
        hours = parseHours(j);
        double rain = first(day, "precipitation_probability_max");
        return new Weather(
                c.getString(R.string.weather_label).trim(),
                describe(cur.getInt("weather_code")),
                Math.round(cur.getDouble("temperature_2m")),
                Math.round(first(day, "temperature_2m_max")),
                Math.round(first(day, "temperature_2m_min")),
                Double.isNaN(rain) ? -1 : (int) Math.round(rain));
    }

    // ---- air quality ----

    /**
     * Open-Meteo's air-quality service: same provider as the weather, also free and with no
     * account, and it takes the same coordinates. US AQI rather than European, because the bands
     * Indian apps and news quote are the US ones.
     */
    private static Air fetchAir(Context c) throws Exception {
        if (!c.getResources().getBoolean(R.bool.show_air_quality)) return null;
        String lat = c.getString(R.string.weather_lat).trim();
        String lon = c.getString(R.string.weather_lon).trim();
        if (lat.isEmpty() || lon.isEmpty()) return null;
        String url = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + lat
                + "&longitude=" + lon + "&current=us_aqi&timezone=auto";
        JSONObject cur = new JSONObject(get(url)).getJSONObject("current");
        if (cur.isNull("us_aqi")) return null;
        return new Air((int) Math.round(cur.getDouble("us_aqi")));
    }

    /**
     * The next few hours. Open-Meteo returns the whole day from midnight, so we skip forward to
     * the hour we are in and take what follows - two days are asked for so an evening does not run
     * out of hours before the strip is full.
     */
    private static List<Hour> parseHours(JSONObject root) {
        List<Hour> out = new ArrayList<>();
        try {
            JSONObject h = root.getJSONObject("hourly");
            JSONArray times = h.getJSONArray("time");
            JSONArray temps = h.getJSONArray("temperature_2m");
            java.util.Calendar now = java.util.Calendar.getInstance();
            String today = String.format(java.util.Locale.US, "%tFT%02d", now, now.get(java.util.Calendar.HOUR_OF_DAY));
            boolean started = false;
            for (int i = 0; i < times.length() && out.size() < HOURS_SHOWN; i++) {
                String stamp = times.getString(i);           // 2026-10-21T14:00
                if (!started) {
                    // compare date+hour only, so the hour we are in is passed over too
                    if (stamp.length() < 13 || stamp.substring(0, 13).compareTo(today) <= 0) continue;
                    started = true;
                }
                if (temps.isNull(i)) continue;
                int hour24 = Integer.parseInt(stamp.substring(11, 13));
                int hour12 = hour24 % 12 == 0 ? 12 : hour24 % 12;
                out.add(new Hour(hour12 + (hour24 < 12 ? " AM" : " PM"),
                        Math.round(temps.getDouble(i))));
            }
        } catch (Exception e) {
            Log.w(TAG, "hourly failed", e);
        }
        return Collections.unmodifiableList(out);
    }

    private static double first(JSONObject o, String key) {
        try {
            JSONArray a = o.getJSONArray(key);
            return a.isNull(0) ? Double.NaN : a.getDouble(0);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 3) return "Partly cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 95) return "Thunderstorms";
        return "Cloudy";
    }

    // ---- headlines ----

    private static List<Headline> fetchHeadlines(Context c) throws Exception {
        List<Headline> out = new ArrayList<>();
        String feed = c.getString(R.string.news_feed_url).trim();
        if (feed.isEmpty()) return out;

        HttpURLConnection conn = open(feed);
        try (InputStream in = conn.getInputStream()) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            boolean inItem = false;
            String title = null, image = null, description = null;
            for (int ev = p.getEventType();
                 ev != XmlPullParser.END_DOCUMENT && out.size() < MAX_HEADLINES;
                 ev = p.next()) {
                String name = p.getName();
                if (name == null) continue;
                String tag = local(name);
                if (ev == XmlPullParser.START_TAG) {
                    if ("item".equals(tag) || "entry".equals(tag)) {
                        inItem = true;
                        title = null; image = null; description = null;
                    } else if (inItem && "title".equals(tag)) {
                        title = p.nextText().trim();
                    } else if (inItem && "description".equals(tag)) {
                        description = p.nextText();
                    } else if (inItem && image == null
                            && ("content".equals(tag) || "thumbnail".equals(tag)
                                || "enclosure".equals(tag) || "image".equals(tag))) {
                        String url = p.getAttributeValue(null, "url");
                        if (url != null && looksLikeImage(url)) image = url;
                    }
                } else if (ev == XmlPullParser.END_TAG
                        && ("item".equals(tag) || "entry".equals(tag))) {
                    inItem = false;
                    if (title != null && !title.isEmpty()) {
                        if (image == null) image = imageFromHtml(description);
                        out.add(new Headline(title, image));
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
        return out;
    }

    /** Strips any namespace prefix, so media:content and content both read as "content". */
    private static String local(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static boolean looksLikeImage(String url) {
        String u = url.toLowerCase();
        return u.startsWith("http") && !u.endsWith(".mp4") && !u.endsWith(".mp3");
    }

    /** Some feeds only hint at a picture inside the description's HTML. */
    private static String imageFromHtml(String html) {
        if (html == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)<img[^>]+src=[\"']([^\"']+)[\"']").matcher(html);
        return m.find() && looksLikeImage(m.group(1)) ? m.group(1) : null;
    }

    private static void fetchImages(Context c, List<Headline> items) {
        int[] screen = Photos.screen(c);
        int maxW = Math.max(320, screen[0] / 4);     // a thumbnail, not a wallpaper
        int done = 0;
        for (Headline h : items) {
            if (done >= MAX_IMAGES) break;
            if (h.imageUrl == null) continue;
            try {
                h.image = Photos.decode(getBytes(h.imageUrl), maxW, maxW);
                if (h.image != null) done++;
            } catch (Exception e) {
                Log.w(TAG, "headline image failed", e);
            }
        }
    }

    // ---- http ----

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "tv-vibehack-screensaver");
        int code = conn.getResponseCode();
        if (code != 200) throw new java.io.IOException("HTTP " + code + " for " + url);
        return conn;
    }

    private static byte[] getBytes(String url) throws Exception {
        HttpURLConnection conn = open(url);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0 && buf.size() < 2 * 1024 * 1024) buf.write(chunk, 0, n);
            return buf.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private static String get(String url) throws Exception {
        return new String(getBytes(url), "UTF-8");
    }
}
