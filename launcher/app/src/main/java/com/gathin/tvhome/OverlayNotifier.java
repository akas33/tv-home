package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A small banner over whatever is playing, so a new note or photo is noticed while someone is
 * watching Netflix. Drawn as a system overlay, which needs the "draw over other apps" permission;
 * Android TV has no screen to grant it, so the install script grants it over ADB:
 *
 *   appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow
 *
 * Without that permission addView throws, which is caught here: the board still works, the banner
 * just doesn't appear. The banner never takes focus or touch, so it cannot steal remote presses.
 */
final class OverlayNotifier {
    private static final long SHOW_MS = 7_000;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private View shown;
    private final Runnable hide = this::remove;

    OverlayNotifier(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Shows a banner for this item. Safe to call from any thread. */
    void announce(Board.Item item) {
        if (item == null) return;
        main.post(() -> {
            try {
                remove();
                WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return;
                View banner = build(item);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        android.graphics.PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.END;
                int m = dp(28);
                lp.x = m;
                lp.y = m;
                wm.addView(banner, lp);
                shown = banner;
                banner.setAlpha(0f);
                banner.animate().alpha(1f).setDuration(260).start();
                main.postDelayed(hide, SHOW_MS);
            } catch (Exception ignored) {
                // no overlay permission, or the window manager refused: skip the banner
                shown = null;
            }
        });
    }

    private void remove() {
        main.removeCallbacks(hide);
        View v = shown;
        shown = null;
        if (v == null) return;
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.removeViewImmediate(v);
        } catch (Exception ignored) {
            // already gone
        }
    }

    private View build(Board.Item item) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(232, 18, 18, 20));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.argb(60, 255, 255, 255));
        row.setBackground(bg);
        int pad = dp(14);
        row.setPadding(pad, pad, pad + dp(4), pad);

        if (item.isPhoto()) {
            ImageView thumb = new ImageView(context);
            thumb.setImageBitmap(item.photo);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(72), dp(72));
            tp.setMarginEnd(dp(14));
            thumb.setLayoutParams(tp);
            row.addView(thumb);
        }

        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText(item.isPhoto()
                ? context.getString(R.string.banner_photo)
                : context.getString(R.string.banner_note));
        title.setTextColor(Color.argb(150, 255, 255, 255));
        title.setTextSize(13f);
        title.setAllCaps(true);
        title.setLetterSpacing(0.12f);
        col.addView(title);

        TextView body = new TextView(context);
        String line = item.line();
        body.setText(line.isEmpty() ? context.getString(R.string.banner_photo) : line);
        body.setTextColor(Color.WHITE);
        body.setTextSize(19f);
        body.setMaxLines(2);
        body.setMaxWidth(dp(360));
        try {
            body.setTypeface(context.getResources().getFont(R.font.outfit));
        } catch (Exception e) {
            body.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        }
        col.addView(body);

        row.addView(col);
        return row;
    }

    private int dp(float v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
