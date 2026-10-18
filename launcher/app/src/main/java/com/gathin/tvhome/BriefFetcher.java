package com.gathin.tvhome;

import android.content.Context;
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
 * Fetches the screensaver's brief by itself, so no computer has to stay on: today's weather from
 * Open-Meteo (free, no account or key) and a few headlines from an RSS feed. Runs only while the
 * screensaver is showing, at most once every 30 minutes, on a short-lived background thread.
 * Both sources are optional and configured in res/values/config.xml; leave them blank to skip.
 */
final class BriefFetcher {
    private static final long STALE_MS = 30L * 60 * 1000;
    private static final long RETRY_MS = 5L * 60 * 1000;
    private static final int MAX_HEADLINES = 5;

    /** Latest fetched lines (replaced as a whole, never mutated). */
    static volatile List<String> lines = Collections.emptyList();

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
            try {
                List<String> out = new ArrayList<>();
                try { weather(app, out); } catch (Exception ignored) { }
                try { news(app, out); } catch (Exception ignored) { }
                if (!out.isEmpty()) {
                    lines = Collections.unmodifiableList(out);
                } else {
                    lastAttempt = System.currentTimeMillis() - STALE_MS + RETRY_MS; // offline: retry soon
                }
            } finally {
                busy = false;
            }
        }, "brief-fetch").start();
    }

    // ---- weather ----

    private static void weather(Context c, List<String> out) throws Exception {
        String lat = c.getString(R.string.weather_lat).trim();
        String lon = c.getString(R.string.weather_lon).trim();
        if (lat.isEmpty() || lon.isEmpty()) return;
        String label = c.getString(R.string.weather_label).trim();
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code"
                + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&forecast_days=1&timezone=auto";
        JSONObject j = new JSONObject(get(url));
        JSONObject cur = j.getJSONObject("current");
        JSONObject day = j.getJSONObject("daily");
        long now = Math.round(cur.getDouble("temperature_2m"));
        String sky = describe(cur.getInt("weather_code"));
        long hi = Math.round(first(day, "temperature_2m_max"));
        long lo = Math.round(first(day, "temperature_2m_min"));
        String head = (label.isEmpty() ? "" : label + "  ·  ") + now + "° now, " + sky;
        out.add(head);
        String line = "Today " + lo + "°–" + hi + "°";
        double rain = first(day, "precipitation_probability_max");
        if (!Double.isNaN(rain)) line += "  ·  " + Math.round(rain) + "% chance of rain";
        out.add(line);
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
        if (code == 0) return "clear";
        if (code <= 3) return "partly cloudy";
        if (code == 45 || code == 48) return "foggy";
        if (code >= 51 && code <= 57) return "drizzle";
        if (code >= 61 && code <= 67) return "rain";
        if (code >= 71 && code <= 77) return "snow";
        if (code >= 80 && code <= 82) return "showers";
        if (code >= 95) return "thunderstorms";
        return "cloudy";
    }

    // ---- headlines ----

    private static void news(Context c, List<String> out) throws Exception {
        String feed = c.getString(R.string.news_feed_url).trim();
        if (feed.isEmpty()) return;
        HttpURLConnection conn = open(feed);
        try (InputStream in = conn.getInputStream()) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            boolean inItem = false;
            int added = 0;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT && added < MAX_HEADLINES;
                 ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    if ("item".equals(n) || "entry".equals(n)) inItem = true;
                    else if (inItem && "title".equals(n)) {
                        String t = p.nextText().trim();
                        if (!t.isEmpty()) {
                            out.add(t);
                            added++;
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG
                        && ("item".equals(p.getName()) || "entry".equals(p.getName()))) {
                    inItem = false;
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    // ---- http ----

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "tv-vibehack-screensaver");
        if (conn.getResponseCode() != 200) throw new java.io.IOException("HTTP " + conn.getResponseCode());
        return conn;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = open(url);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0 && buf.size() < 256 * 1024) buf.write(chunk, 0, n);
            return buf.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }
}
