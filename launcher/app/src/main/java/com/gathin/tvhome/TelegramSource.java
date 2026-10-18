package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Private family board over a Telegram bot: notes and photos sent to the bot from any phone,
 * from anywhere, appear on the screensaver.
 *
 * Why a bot and not a public topic: the bot token is a secret, and only the chat IDs listed in
 * telegram_allowed_chats are accepted, so someone who stumbles on the bot cannot put anything on
 * the TV. With no allowed chats configured, nothing is accepted at all.
 *
 * Polls once a minute, and only while the screensaver is showing. Updates are never confirmed back
 * to Telegram, so it keeps a rolling window of what was sent in the last 24 hours and this class
 * needs no stored state. Photos are downloaded once, downscaled to the screen and cached in memory.
 */
final class TelegramSource {
    private static final long POLL_MS = 60_000;
    private static final long RETRY_MS = 15_000;
    private static final long KEEP_MS = 12L * 60 * 60 * 1000;  // show a note or photo this long
    private static final int MAX_PHOTOS = 3;                   // keep memory small on a 1 GB TV
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    /** A photo on the board. */
    static final class Shot {
        final Bitmap bitmap;
        final String caption;   // may be empty
        final long sentAt;      // when the phone sent it
        final long firstSeenAt; // when this TV first downloaded it

        Shot(Bitmap bitmap, String caption, long sentAt, long firstSeenAt) {
            this.bitmap = bitmap;
            this.caption = caption;
            this.sentAt = sentAt;
            this.firstSeenAt = firstSeenAt;
        }
    }

    /** Text notes, newest first. Replaced as a whole, never mutated. */
    static volatile List<String> notes = Collections.emptyList();
    /** Photos, newest first. Replaced as a whole, never mutated. */
    static volatile List<Shot> shots = Collections.emptyList();

    private static final Map<String, Shot> cache = new HashMap<>();  // file_id -> downloaded photo
    private static volatile long lastAttempt = 0;
    private static volatile boolean busy = false;

    private TelegramSource() {}

    static void refreshIfStale(Context context) {
        final String token = context.getString(R.string.telegram_bot_token).trim();
        if (token.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (busy || now - lastAttempt < POLL_MS) return;
        busy = true;
        lastAttempt = now;

        final Set<String> allowed = allowedChats(context);
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        final int maxW = Math.max(640, dm.widthPixels);
        final int maxH = Math.max(360, dm.heightPixels);
        new Thread(() -> {
            try {
                poll(token, allowed, maxW, maxH);
            } catch (Exception e) {
                lastAttempt = System.currentTimeMillis() - POLL_MS + RETRY_MS;  // offline: retry soon
            } finally {
                busy = false;
            }
        }, "tg-poll").start();
    }

    private static Set<String> allowedChats(Context c) {
        Set<String> out = new HashSet<>();
        for (String part : c.getString(R.string.telegram_allowed_chats).split("[,\\s]+")) {
            part = part.trim();
            if (!part.isEmpty()) out.add(part);
        }
        return out;
    }

    private static void poll(String token, Set<String> allowed, int maxW, int maxH) throws Exception {
        if (allowed.isEmpty()) {           // nothing may be shown until chat IDs are configured
            notes = Collections.emptyList();
            shots = Collections.emptyList();
            return;
        }
        String body = get("https://api.telegram.org/bot" + token
                + "/getUpdates?timeout=0&allowed_updates=%5B%22message%22%5D");
        JSONObject root = new JSONObject(body);
        if (!root.optBoolean("ok")) throw new IllegalStateException("telegram: " + root.optString("description"));
        JSONArray updates = root.optJSONArray("result");
        if (updates == null) return;

        long now = System.currentTimeMillis();
        List<String> freshNotes = new ArrayList<>();
        List<Shot> freshShots = new ArrayList<>();
        Set<String> stillWanted = new HashSet<>();

        for (int i = updates.length() - 1; i >= 0; i--) {   // newest first
            JSONObject msg = updates.optJSONObject(i) == null
                    ? null : updates.optJSONObject(i).optJSONObject("message");
            if (msg == null) continue;
            JSONObject chat = msg.optJSONObject("chat");
            if (chat == null || !allowed.contains(String.valueOf(chat.opt("id")))) continue;
            long sentAt = msg.optLong("date") * 1000L;
            if (sentAt <= 0 || now - sentAt > KEEP_MS) continue;

            String who = "";
            JSONObject from = msg.optJSONObject("from");
            if (from != null) who = from.optString("first_name", "").trim();

            JSONArray photo = msg.optJSONArray("photo");
            if (photo != null && photo.length() > 0) {
                String fileId = pickSize(photo, maxW);
                if (fileId == null) continue;
                stillWanted.add(fileId);
                String caption = msg.optString("caption", "").trim();
                if (!who.isEmpty()) caption = caption.isEmpty() ? who : who + "  ·  " + caption;
                Shot shot;
                synchronized (cache) {
                    shot = cache.get(fileId);
                }
                if (shot == null && freshShots.size() < MAX_PHOTOS) {
                    Bitmap bmp = download(token, fileId, maxW, maxH);
                    if (bmp != null) {
                        shot = new Shot(bmp, caption, sentAt, System.currentTimeMillis());
                        synchronized (cache) {
                            cache.put(fileId, shot);
                        }
                    }
                }
                if (shot != null && freshShots.size() < MAX_PHOTOS) freshShots.add(shot);
                continue;
            }

            String text = msg.optString("text", "").trim();
            if (text.isEmpty()) continue;
            if (text.length() > 160) text = text.substring(0, 157) + "...";
            freshNotes.add(who.isEmpty() ? text : who + ": " + text);
            if (freshNotes.size() >= 6) continue;
        }

        synchronized (cache) {               // drop photos that have aged out of the window
            cache.keySet().retainAll(stillWanted);
        }
        notes = Collections.unmodifiableList(freshNotes);
        shots = Collections.unmodifiableList(freshShots);
    }

    /** Largest offered size that is still no wider than the screen, else the smallest one. */
    private static String pickSize(JSONArray sizes, int maxW) {
        String best = null;
        int bestW = -1;
        String smallest = null;
        int smallestW = Integer.MAX_VALUE;
        for (int i = 0; i < sizes.length(); i++) {
            JSONObject s = sizes.optJSONObject(i);
            if (s == null) continue;
            int w = s.optInt("width", 0);
            String id = s.optString("file_id", "");
            if (id.isEmpty()) continue;
            if (w < smallestW) { smallestW = w; smallest = id; }
            if (w <= maxW && w > bestW) { bestW = w; best = id; }
        }
        return best != null ? best : smallest;
    }

    private static Bitmap download(String token, String fileId, int maxW, int maxH) throws Exception {
        JSONObject meta = new JSONObject(get("https://api.telegram.org/bot" + token
                + "/getFile?file_id=" + fileId));
        if (!meta.optBoolean("ok")) return null;
        JSONObject result = meta.optJSONObject("result");
        String path = result == null ? "" : result.optString("file_path", "");
        if (path.isEmpty()) return null;
        byte[] data = getBytes("https://api.telegram.org/file/bot" + token + "/" + path);
        if (data.length == 0) return null;

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
        opts.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory, fine for photos
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    // ---- http ----

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "tv-vibehack-screensaver");
        if (conn.getResponseCode() != 200) {
            throw new java.io.IOException("HTTP " + conn.getResponseCode());
        }
        return conn;
    }

    private static byte[] getBytes(String url) throws Exception {
        HttpURLConnection conn = open(url);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0 && buf.size() < MAX_BYTES) buf.write(chunk, 0, n);
            return buf.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private static String get(String url) throws Exception {
        return new String(getBytes(url), "UTF-8");
    }
}
