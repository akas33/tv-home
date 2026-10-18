package com.gathin.tvhome;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The family board: notes and photos, wherever they came from (the TV's own upload page, email,
 * or a Telegram bot). One shared store so the screensaver, the home screen and the pop-up banner
 * all show the same things.
 *
 * Items are kept for 12 hours and capped, so memory stays small on a 1 GB TV. Every method is
 * synchronised; sources add from background threads while the UI reads on the main thread.
 */
final class Board {
    private static final long KEEP_MS = 12L * 60 * 60 * 1000;
    private static final int MAX_PHOTOS = 3;
    private static final int MAX_NOTES = 8;

    /** One thing on the board. A photo item has a bitmap; a note item does not. */
    static final class Item {
        final String id;          // stable per source, used to avoid showing duplicates
        final String who;         // sender name, may be empty
        final String text;        // note body, or a photo's caption, may be empty
        final Bitmap photo;       // null for a note
        final long receivedAt;    // when THIS TV got it

        Item(String id, String who, String text, Bitmap photo, long receivedAt) {
            this.id = id;
            this.who = who == null ? "" : who.trim();
            this.text = text == null ? "" : text.trim();
            this.photo = photo;
            this.receivedAt = receivedAt;
        }

        boolean isPhoto() {
            return photo != null && !photo.isRecycled();
        }

        /** "Asha: back by 7" for a note, "Asha" or "Asha · at the park" for a photo. */
        String line() {
            if (who.isEmpty()) return text;
            if (text.isEmpty()) return who;
            return who + (isPhoto() ? "  ·  " : ": ") + text;
        }
    }

    /** Newest arrival, for the pop-up banner. Null until something arrives. */
    private static Item newest;
    private static final LinkedHashMap<String, Item> items = new LinkedHashMap<>();

    private Board() {}

    /** Adds an item unless its id is already on the board. Returns true if it is new. */
    static synchronized boolean add(Item item) {
        if (item == null || item.id == null || item.id.isEmpty()) return false;
        if (items.containsKey(item.id)) return false;
        items.put(item.id, item);
        newest = item;
        prune();
        return true;
    }

    static synchronized boolean has(String id) {
        return id != null && items.containsKey(id);
    }

    /** Photos, newest first. */
    static synchronized List<Item> photos() {
        prune();
        List<Item> out = new ArrayList<>();
        for (Item it : items.values()) if (it.isPhoto()) out.add(0, it);
        return out;
    }

    /** Note and caption lines, newest first, for the screensaver's rotating text. */
    static synchronized List<String> lines() {
        prune();
        List<String> out = new ArrayList<>();
        for (Item it : items.values()) {
            String l = it.line();
            if (!l.isEmpty() && !it.isPhoto()) out.add(0, l);
        }
        return out;
    }

    static synchronized Item newest() {
        prune();
        return newest != null && items.containsKey(newest.id) ? newest : null;
    }

    /** Drops anything older than 12 hours, then trims to the caps, oldest first. */
    private static void prune() {
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        int photos = 0, notes = 0;
        // walk newest-first so the caps keep the most recent
        List<Item> all = new ArrayList<>(items.values());
        for (int i = all.size() - 1; i >= 0; i--) {
            Item it = all.get(i);
            boolean drop = it.receivedAt < cutoff;
            if (!drop) {
                if (it.isPhoto()) drop = ++photos > MAX_PHOTOS;
                else drop = ++notes > MAX_NOTES;
            }
            if (drop) items.remove(it.id);
        }
        if (newest != null && !items.containsKey(newest.id)) newest = null;
        for (Iterator<Map.Entry<String, Item>> i = items.entrySet().iterator(); i.hasNext(); ) {
            if (i.next().getValue() == null) i.remove();
        }
    }
}
