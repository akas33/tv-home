package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.content.SharedPreferences;
import android.util.Log;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);
    }

    static final class Entry {
        final String label;
        final ComponentName component;    // null for an input tile
        final String pkg;                 // "input:<name>" for an input tile, so the dock can order it
        final Drawable banner;
        final Drawable icon;
        final String inputId;             // set only for an input tile
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.inputId = null;
        }

        /** A tile that switches the TV to an input rather than opening an app. */
        Entry(String label, String inputId, Drawable icon) {
            this.label = label;
            this.component = null;
            this.pkg = DOCK_INPUT_PREFIX + label;
            this.banner = null;
            this.icon = icon;
            this.inputId = inputId;
        }
    }

    /** Put "input:Jio" in dock_apps to pin an input beside the apps. */
    static final String DOCK_INPUT_PREFIX = "input:";
    private static final String USAGE_PREFS = "tile_usage";
    private static final String TAG = "TvHome";

    static final float FOCUS_SCALE = 1.10f;

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (res/values/config.xml, dock_apps). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
    }

    private SharedPreferences usage() {
        return ctx.getSharedPreferences(USAGE_PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Any dock_apps entry written as "input:Name" becomes a tile that switches straight to that
     * input, so a set-top box is one press rather than three through the Inputs menu.
     */
    private void addInputTiles(List<Entry> fresh) {
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) {
            Log.w(TAG, "no TvInputManager, cannot pin inputs");
            return;
        }
        for (String fav : favourites) {
            if (!fav.startsWith(DOCK_INPUT_PREFIX)) continue;
            String wanted = fav.substring(DOCK_INPUT_PREFIX.length()).trim();
            TvInputInfo hit = findInput(tim, wanted);
            if (hit == null) {
                Log.w(TAG, "no input matched \"" + wanted + "\"");
                continue;
            }
            // the tile carries the name from config, not the TV's wordier one
            fresh.add(new Entry(wanted, hit.getId(), ctx.getDrawable(R.drawable.ic_input)));
        }
    }

    /**
     * An exact name wins; failing that, one that merely contains it. TVs name a port whatever the
     * owner typed into settings - "Jio setup" for a box you would call Jio - so asking for an exact
     * match means retyping config to match the TV instead of the other way round.
     */
    private TvInputInfo findInput(TvInputManager tim, String wanted) {
        String want = normalise(wanted);
        TvInputInfo loose = null;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (!info.isPassthroughInput()) continue;
            String name = normalise(TvInputs.label(ctx, info));
            Log.i(TAG, "input \"" + name + "\" (" + info.getId() + ") vs wanted \"" + want + "\"");
            if (name.equals(want)) return info;
            if (loose == null && (name.contains(want) || want.contains(name))) loose = info;
        }
        return loose;
    }

    private static String normalise(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon));
        }
        addInputTiles(fresh);
        final SharedPreferences used = usage();
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {                       // the dock keeps the order you chose
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            // everything else: most recently opened first, then apps never opened, alphabetically
            long la = used.getLong(a.pkg, 0L), lb = used.getLong(b.pkg, 0L);
            if (la != lb) return Long.compare(lb, la);
            return a.label.compareToIgnoreCase(b.label);
        });
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        h.name.setAlpha(0f);
        h.card.setScaleX(1f);
        h.card.setScaleY(1f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            if (e.inputId != null) return true;
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
    }

    private void launch(Entry e) {
        usage().edit().putLong(e.pkg, System.currentTimeMillis()).apply();
        if (e.inputId != null) {
            try {
                ctx.startActivity(new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(TvContract.buildChannelUriForPassthroughInput(e.inputId),
                                "vnd.android.cursor.item/channel")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ex) {
                Toast.makeText(ctx, "Can't switch to " + e.label, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, "Can't open " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
