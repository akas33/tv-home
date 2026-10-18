package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.service.dreams.DreamService;
import android.view.View;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Ambient screensaver: slow drifting colour on pure black, a large thin clock that wanders around
 * the screen (so no pixel stays lit, which protects the panel), and an optional "brief" of short
 * lines pushed from your computer with tools/push-brief.py (weather, calendar, a note, anything).
 *
 * Light on purpose: three cached radial gradients, ~20 fps, no network, no background work.
 */
public class AmbientDream extends DreamService {

    private AmbientView view;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        view = new AmbientView(this);
        setContentView(view);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        view.start();
    }

    @Override
    public void onDreamingStopped() {
        view.stop();
        super.onDreamingStopped();
    }

    static final class AmbientView extends View {
        private static final long FRAME_MS = 50;          // ~20 fps
        private static final long BRIEF_RELOAD_MS = 60_000;
        private static final long BRIEF_CYCLE_MS = 9_000; // one line: fade in, hold, fade out
        private static final long BRIEF_FADE_MS = 1_200;

        private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint clockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ampmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint briefPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm", Locale.getDefault());
        private final SimpleDateFormat ampmFmt = new SimpleDateFormat("a", Locale.getDefault());
        private final DateFormat dateFmt = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault());
        private final Date date = new Date();

        private final Shader[] shaders = new Shader[3];
        private final float[] phase = {0f, 2.1f, 4.2f};
        private final float[] speed = {0.031f, 0.023f, 0.041f};
        private int paletteBucket = -1;
        private float nightDim = 1f;

        private final List<String> brief = new ArrayList<>();
        private int briefIdx = 0;
        private long briefLoadedAt = 0;
        private long briefStart = 0;

        private float lastDim = 1f;
        private long t0;
        private boolean running;
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                invalidate();
                postDelayed(this, lastDim <= 0f ? 1000 : FRAME_MS); // fully dimmed: barely wake up
            }
        };

        AmbientView(Context c) {
            super(c);
            Typeface face;
            try {
                face = getResources().getFont(R.font.outfit);
            } catch (Exception e) {
                face = Typeface.create("sans-serif-thin", Typeface.NORMAL);
            }
            clockPaint.setTypeface(face);
            clockPaint.setColor(Color.argb(235, 255, 255, 255));
            ampmPaint.setTypeface(face);
            ampmPaint.setColor(Color.argb(150, 255, 255, 255));
            datePaint.setTypeface(face);
            datePaint.setColor(Color.argb(170, 255, 255, 255));
            briefPaint.setTypeface(face);
            briefPaint.setColor(Color.WHITE);
            setBackgroundColor(Color.BLACK);
        }

        void start() {
            running = true;
            t0 = System.currentTimeMillis();
            briefStart = t0;
            briefLoadedAt = 0;
            removeCallbacks(tick);
            post(tick);
        }

        void stop() {
            running = false;
            removeCallbacks(tick);
        }

        @Override
        protected void onDetachedFromWindow() {
            stop();
            super.onDetachedFromWindow();
        }

        // ---- palette: warm in the morning, cool by day, rose at dusk, dim indigo at night ----

        private void ensurePalette(Calendar cal, int w, int h) {
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int bucket = hour < 5 ? 3 : hour < 11 ? 0 : hour < 17 ? 1 : hour < 21 ? 2 : 3;
            if (bucket == paletteBucket && shaders[0] != null) return;
            paletteBucket = bucket;
            int[][] pal = {
                    {0xFFE8A04A, 0xFFE0705A, 0xFF8A5AD0},  // morning: amber, coral, violet
                    {0xFF2FA8C7, 0xFF3F6FE0, 0xFF37C9A5},  // day: teal, blue, mint
                    {0xFFE0507F, 0xFFF08A3C, 0xFF6A4BC8},  // evening: rose, orange, purple
                    {0xFF2A2E8F, 0xFF4A2A82, 0xFF1D5A8A}   // night: deep indigo
            };
            nightDim = bucket == 3 ? 0.55f : 1f;
            float r = Math.max(w, h) * 0.46f;
            for (int i = 0; i < 3; i++) {
                int c = pal[bucket][i];
                int centre = Color.argb((int) (150 * nightDim), Color.red(c), Color.green(c), Color.blue(c));
                int edge = Color.argb(0, Color.red(c), Color.green(c), Color.blue(c));
                shaders[i] = new RadialGradient(0, 0, r, centre, edge, Shader.TileMode.CLAMP);
            }
        }

        // ---- brief: short lines pushed to <app files dir>/brief.txt ----

        private void maybeReloadBrief(long now) {
            BriefFetcher.refreshIfStale(getContext());
            if (now - briefLoadedAt < BRIEF_RELOAD_MS && !(brief.isEmpty() && now - briefLoadedAt > 15_000)) return;
            briefLoadedAt = now;
            List<String> fresh = new ArrayList<>();
            File f = new File(getContext().getExternalFilesDir(null), "brief.txt");
            if (f.isFile() && f.length() < 16 * 1024) {
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    String line;
                    while ((line = br.readLine()) != null && fresh.size() < 12) {
                        line = line.trim();
                        if (!line.isEmpty()) fresh.add(line);
                    }
                } catch (Exception ignored) {
                    // unreadable file: just show no brief
                }
            }
            fresh.addAll(BriefFetcher.lines); // weather + headlines the TV fetched itself
            brief.clear();
            brief.addAll(fresh);
            if (briefIdx >= brief.size()) briefIdx = 0;
        }

        private String greeting(int hour) {
            if (hour < 5) return getContext().getString(R.string.good_night);
            if (hour < 12) return getContext().getString(R.string.good_morning);
            if (hour < 17) return getContext().getString(R.string.good_afternoon);
            if (hour < 21) return getContext().getString(R.string.good_evening);
            return getContext().getString(R.string.good_night);
        }

        /** 1 = full brightness, 0 = black. Bedtime window comes from config.xml (dim_*). */
        private float dimFactor(Calendar cal) {
            int startH = getResources().getInteger(R.integer.dim_start_hour);
            int endH = getResources().getInteger(R.integer.dim_end_hour);
            float ramp = Math.max(1, getResources().getInteger(R.integer.dim_ramp_minutes));
            if (startH < 0 || startH > 23 || endH < 0 || endH > 23) return 1f;
            float m = cal.get(Calendar.HOUR_OF_DAY) * 60f + cal.get(Calendar.MINUTE)
                    + cal.get(Calendar.SECOND) / 60f;
            float start = startH * 60f;
            float windowLen = ((endH - startH) * 60f + 1440f) % 1440f;
            if (windowLen == 0f) return 1f;
            float elapsed = (m - start + 1440f) % 1440f;
            if (elapsed >= windowLen) return 1f;               // outside bedtime window
            if (elapsed < ramp) return 1f - elapsed / ramp;    // fading out
            return 0f;                                         // fully black
        }

        @Override
        protected void onDraw(Canvas canvas) {
            drawScene(canvas);
            if (lastDim < 1f) canvas.drawColor(Color.argb((int) (255 * (1f - lastDim)), 0, 0, 0));
        }

        private void drawScene(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            long now = System.currentTimeMillis();
            float sec = (now - t0) / 1000f;
            Calendar cal = Calendar.getInstance();
            lastDim = dimFactor(cal);
            if (lastDim <= 0f) return;                         // black; onDraw paints it
            ensurePalette(cal, w, h);
            maybeReloadBrief(now);

            canvas.drawColor(Color.BLACK);

            // drifting colour blobs
            for (int i = 0; i < 3; i++) {
                float cx = w * (0.5f + 0.38f * (float) Math.sin(sec * speed[i] + phase[i]));
                float cy = h * (0.5f + 0.34f * (float) Math.cos(sec * speed[i] * 0.8f + phase[i] * 1.3f));
                blobPaint.setShader(shaders[i]);
                canvas.save();
                canvas.translate(cx, cy);
                canvas.drawRect(-w, -h, w, h, blobPaint);
                canvas.restore();
            }

            // clock that wanders (burn-in protection)
            date.setTime(now);
            String time = timeFmt.format(date);
            String ampm = ampmFmt.format(date).toUpperCase(Locale.getDefault());
            clockPaint.setTextSize(h * 0.24f);
            ampmPaint.setTextSize(h * 0.05f);
            datePaint.setTextSize(h * 0.045f);
            float timeW = clockPaint.measureText(time);
            float ampmW = ampmPaint.measureText(ampm);
            float blockW = timeW + ampmW + h * 0.03f;
            float marginX = w * 0.06f;
            float rangeX = Math.max(0f, w - blockW - 2 * marginX);
            float x = marginX + rangeX * (0.5f + 0.5f * (float) Math.sin(sec * 0.021f));
            float top = h * 0.14f;
            float rangeY = h * 0.34f;
            float y = top + h * 0.24f + rangeY * (0.5f + 0.5f * (float) Math.sin(sec * 0.015f + 1.0f));
            canvas.drawText(time, x, y, clockPaint);
            canvas.drawText(ampm, x + timeW + h * 0.03f, y - h * 0.13f, ampmPaint);
            String dateLine = greeting(cal.get(Calendar.HOUR_OF_DAY)) + "  ·  " + dateFmt.format(date);
            float dx = Math.min(x + h * 0.01f, w - marginX - datePaint.measureText(dateLine));
            canvas.drawText(dateLine, Math.max(marginX, dx), y + h * 0.075f, datePaint);

            // rotating brief line, fading in and out
            if (!brief.isEmpty()) {
                long into = now - briefStart;
                if (into >= BRIEF_CYCLE_MS) {
                    briefStart = now;
                    into = 0;
                    briefIdx = (briefIdx + 1) % brief.size();
                }
                float a = 1f;
                if (into < BRIEF_FADE_MS) a = into / (float) BRIEF_FADE_MS;
                else if (into > BRIEF_CYCLE_MS - BRIEF_FADE_MS)
                    a = (BRIEF_CYCLE_MS - into) / (float) BRIEF_FADE_MS;
                briefPaint.setAlpha((int) (210 * Math.max(0f, Math.min(1f, a)) * (nightDim > 0.9f ? 1f : 0.8f)));
                briefPaint.setTextSize(h * 0.052f);
                String line = brief.get(briefIdx);
                // shrink long lines to fit inside the screen width
                float maxW = w - 2 * marginX;
                float lw = briefPaint.measureText(line);
                if (lw > maxW) briefPaint.setTextSize(briefPaint.getTextSize() * maxW / lw);
                float bx = Math.min(x + h * 0.01f, w - marginX - briefPaint.measureText(line));
                canvas.drawText(line, Math.max(marginX, bx), y + h * 0.16f, briefPaint);
            }
        }
    }
}
