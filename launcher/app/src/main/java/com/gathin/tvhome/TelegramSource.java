package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

/**
 * Optional third way onto the board: a private Telegram bot, for family who already use Telegram.
 * The bot token is a secret and only the chat IDs in telegram_allowed_chats are accepted, so
 * someone who stumbles on the bot cannot put anything on the TV. Empty token means off.
 *
 * Updates are never confirmed back to Telegram, so each poll sees whatever was sent in the last
 * 24 hours and this class needs no stored state; Board drops the duplicates.
 */
final class TelegramSource {
    private static final long KEEP_MS = 12L * 60 * 60 * 1000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private TelegramSource() {}

    static boolean configured(Context c) {
        return !c.getString(R.string.telegram_bot_token).trim().isEmpty()
                && !allowedChats(c).isEmpty();
    }

    private static Set<String> allowedChats(Context c) {
        Set<String> out = new HashSet<>();
        for (String part : c.getString(R.string.telegram_allowed_chats).split("[,;\\s]+")) {
            part = part.trim();
            if (!part.isEmpty()) out.add(part);
        }
        return out;
    }

    /** Checks the bot and adds anything new to the board. Call from a background thread. */
    static void poll(Context c) throws Exception {
        if (!configured(c)) return;
        String token = c.getString(R.string.telegram_bot_token).trim();
        Set<String> allowed = allowedChats(c);
        int[] screen = Photos.screen(c);

        String body = get("https://api.telegram.org/bot" + token
                + "/getUpdates?timeout=0&allowed_updates=%5B%22message%22%5D");
        JSONObject root = new JSONObject(body);
        if (!root.optBoolean("ok")) {
            throw new IllegalStateException("telegram: " + root.optString("description"));
        }
        JSONArray updates = root.optJSONArray("result");
        if (updates == null) return;
        long cutoff = System.currentTimeMillis() - KEEP_MS;

        for (int i = updates.length() - 1; i >= 0; i--) {     // newest first
            JSONObject update = updates.optJSONObject(i);
            if (update == null) continue;
            JSONObject msg = update.optJSONObject("message");
            if (msg == null) continue;
            JSONObject chat = msg.optJSONObject("chat");
            if (chat == null || !allowed.contains(String.valueOf(chat.opt("id")))) continue;
            long sentAt = msg.optLong("date") * 1000L;
            if (sentAt <= 0 || sentAt < cutoff) continue;

            String who = "";
            JSONObject from = msg.optJSONObject("from");
            if (from != null) who = from.optString("first_name", "").trim();

            JSONArray photo = msg.optJSONArray("photo");
            if (photo != null && photo.length() > 0) {
                String fileId = pickSize(photo, screen[0]);
                if (fileId == null) continue;
                String id = "tg:" + fileId;
                if (Board.has(id)) continue;
                Bitmap bmp = download(token, fileId, screen[0], screen[1]);
                if (bmp == null) continue;
                Board.add(new Board.Item(id, who, msg.optString("caption", ""), bmp,
                        System.currentTimeMillis()));
                continue;
            }

            String text = msg.optString("text", "").trim();
            if (text.isEmpty()) continue;
            if (text.length() > 160) text = text.substring(0, 157) + "...";
            Board.add(new Board.Item("tg:" + update.optLong("update_id"), who, text, null,
                    System.currentTimeMillis()));
        }
    }

    /** Largest offered size still no wider than the screen, else the smallest one. */
    private static String pickSize(JSONArray sizes, int maxW) {
        String best = null, smallest = null;
        int bestW = -1, smallestW = Integer.MAX_VALUE;
        for (int i = 0; i < sizes.length(); i++) {
            JSONObject s = sizes.optJSONObject(i);
            if (s == null) continue;
            String id = s.optString("file_id", "");
            if (id.isEmpty()) continue;
            int w = s.optInt("width", 0);
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
        return Photos.decode(getBytes("https://api.telegram.org/file/bot" + token + "/" + path),
                maxW, maxH);
    }

    // ---- http ----

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "tv-vibehack-board");
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
