package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.service.dreams.DreamService;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The idle screen: a slow sky over the city rather than a dashboard. A deep vertical gradient with
 * two soft glows drifting through it, shifting through dawn, day, dusk and night. The time is the
 * one loud element; the weather sits opposite it and never moves away; a headline or a family note
 * runs quietly along the bottom, with the feed's picture when there is one.
 *
 * A photo someone has just sent takes the whole screen for a few minutes, because that is the
 * point of sending it.
 *
 * Built for a 1 GB TV: cached shaders, ~20 fps, no blur, no video, no background work.
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
        private static final long BAND_CYCLE_MS = 13_000; // one headline or note
        private static final long BAND_FADE_MS = 1_400;
        private static final long CONTENT_POLL_MS = 5_000;
        private static final long PHOTO_SLOT_MS = 20_000;
        private static final String PREFS = "ambient";
        private static final String KEY_BYPASS = "dim_bypass_until";

        /** top, bottom, glow A, glow B - dawn, morning, day, dusk, night. */
        private static final int[][] SKY = {
                {0xFF10163A, 0xFF2E1C3E, 0xFFE8734A, 0xFF7B4BA8},
                {0xFF0E2A4A, 0xFF17425F, 0xFFF2A65A, 0xFF4FB8D9},
                {0xFF0B3B5C, 0xFF115A7F, 0xFF3FC9E0, 0xFF8FD9C0},
                {0xFF2A1238, 0xFF4A1B3D, 0xFFF0663C, 0xFF9B4BC8},
                {0xFF05080F, 0xFF0C1226, 0xFF24407A, 0xFF3A2458},
        };

        private final Paint sky = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint vignette = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint time = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint meridiem = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint date = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint temp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sky2 = new Paint(Paint.ANTI_ALIAS_FLAG);   // condition + range
        private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hindu = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint photo = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint mat = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF dst = new RectF();

        private final SimpleDateFormat hhmm = new SimpleDateFormat("h:mm", Locale.getDefault());
        private final SimpleDateFormat ampm = new SimpleDateFormat("a", Locale.getDefault());
        private final SimpleDateFormat longDate =
                new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault());
        private final Date clock = new Date();

        private Shader skyShader, glowA, glowB, vignetteShader;
        private int skyBucket = -1, skyW, skyH;
        private Shader timeShader;
        private float timeShaderSize = -1f;
        private int timeShaderBucket = -1;

        /** One thing in the bottom band: a family note, or a headline with its picture. */
        private static final class BandItem {
            final String text;
            final Bitmap image;
            BandItem(String text, Bitmap image) { this.text = text; this.image = image; }
        }

        private final List<BandItem> items = new ArrayList<>();
        private int bandIdx = 0;
        private long bandStart = 0, lastPoll = 0;

        private float lastDim = 1f;
        private long t0;
        private boolean running;
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                invalidate();
                postDelayed(this, lastDim <= 0f ? 1000 : FRAME_MS);
            }
        };

        AmbientView(Context c) {
            super(c);
            Typeface base, display;
            try {
                base = getResources().getFont(R.font.outfit);
            } catch (Exception e) {
                base = Typeface.create("sans-serif-light", Typeface.NORMAL);
            }
            try {
                // Space Grotesk for the numerals: its figures have real character at this size,
                // which is what makes the clock worth looking at
                display = getResources().getFont(R.font.space_grotesk);
            } catch (Exception e) {
                display = base;
            }
            time.setTypeface(weight(display, 300));
            time.setColor(0xFFF2F6FA);
            meridiem.setTypeface(weight(base, 400));
            meridiem.setColor(0x8CFFFFFF);
            date.setTypeface(weight(base, 300));
            date.setColor(0xB8FFFFFF);
            temp.setTypeface(weight(display, 400));
            temp.setColor(0xFFF2F6FA);
            temp.setTextAlign(Paint.Align.RIGHT);
            sky2.setTypeface(weight(base, 400));
            sky2.setColor(0xB8FFFFFF);
            sky2.setTextAlign(Paint.Align.RIGHT);
            band.setTypeface(weight(base, 500));
            hindu.setTypeface(weight(base, 400));
            hindu.setColor(0x8CFFFFFF);
            band.setColor(0xFFFFFFFF);
            mat.setColor(0x4D000000);
            setBackgroundColor(Color.BLACK);
        }

        private Typeface weight(Typeface base, int w) {
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    return Typeface.create(base, w, false);
                } catch (Exception ignored) {
                    // fall through to the face as loaded
                }
            }
            return base;
        }

        void start() {
            running = true;
            t0 = System.currentTimeMillis();
            bandStart = t0;
            lastPoll = 0;
            removeCallbacks(tick);
            post(tick);
        }

        /**
         * Leaving while the screen had dimmed means somebody is still up, so dimming is suspended
         * for dim_bypass_minutes rather than going black again every few minutes.
         */
        void stop() {
            running = false;
            removeCallbacks(tick);
            if (lastDim >= 1f) return;
            int minutes = getResources().getInteger(R.integer.dim_bypass_minutes);
            if (minutes <= 0) return;
            try {
                prefs().edit().putLong(KEY_BYPASS,
                        System.currentTimeMillis() + minutes * 60_000L).apply();
            } catch (Exception ignored) {
                // no preferences: dimming simply stays on
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            stop();
            super.onDetachedFromWindow();
        }

        private SharedPreferences prefs() {
            return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }

        // ---- sky ----

        private int bucketFor(int hour) {
            if (hour < 5) return 4;
            if (hour < 8) return 0;
            if (hour < 12) return 1;
            if (hour < 17) return 2;
            if (hour < 20) return 3;
            return 4;
        }

        private void ensureSky(int bucket, int w, int h) {
            if (bucket == skyBucket && w == skyW && h == skyH && skyShader != null) return;
            skyBucket = bucket;
            skyW = w;
            skyH = h;
            int[] p = SKY[bucket];
            skyShader = new LinearGradient(0, 0, 0, h, p[0], p[1], Shader.TileMode.CLAMP);
            // the glows carry the colour; generous radius so the falloff stays soft on a big panel
            glowA = new RadialGradient(0, 0, Math.max(w, h) * 0.55f,
                    withAlpha(p[2], 0x7A), withAlpha(p[2], 0), Shader.TileMode.CLAMP);
            glowB = new RadialGradient(0, 0, Math.max(w, h) * 0.50f,
                    withAlpha(p[3], 0x66), withAlpha(p[3], 0), Shader.TileMode.CLAMP);
            vignetteShader = new RadialGradient(w * 0.5f, h * 0.45f, Math.max(w, h) * 0.75f,
                    0x00000000, 0x80000000, Shader.TileMode.CLAMP);
        }

        private static int withAlpha(int color, int alpha) {
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        }

        /** 1 = full brightness, 0 = black. Bedtime window comes from config.xml (dim_*). */
        private float dimFactor(Calendar cal) {
            try {
                if (System.currentTimeMillis() < prefs().getLong(KEY_BYPASS, 0L)) return 1f;
            } catch (Exception ignored) {
                // no preferences: carry on and dim normally
            }
            int startH = getResources().getInteger(R.integer.dim_start_hour);
            int endH = getResources().getInteger(R.integer.dim_end_hour);
            float ramp = Math.max(1, getResources().getInteger(R.integer.dim_ramp_minutes));
            if (startH < 0 || startH > 23 || endH < 0 || endH > 23) return 1f;
            float m = cal.get(Calendar.HOUR_OF_DAY) * 60f + cal.get(Calendar.MINUTE)
                    + cal.get(Calendar.SECOND) / 60f;
            float windowLen = ((endH - startH) * 60f + 1440f) % 1440f;
            if (windowLen == 0f) return 1f;
            float elapsed = (m - startH * 60f + 1440f) % 1440f;
            if (elapsed >= windowLen) return 1f;
            if (elapsed < ramp) return 1f - elapsed / ramp;
            return 0f;
        }

        // ---- content ----

        private void pollContent(long now) {
            BriefFetcher.refreshIfStale(getContext());
            BoardService.ensureRunning(getContext());
            if (now - lastPoll < CONTENT_POLL_MS) return;
            lastPoll = now;
            List<BandItem> fresh = new ArrayList<>();
            for (String note : Board.lines()) fresh.add(new BandItem(note, null));
            for (BriefFetcher.Headline h : BriefFetcher.headlines) {
                fresh.add(new BandItem(h.title, h.image));
            }
            items.clear();
            items.addAll(fresh);
            if (bandIdx >= items.size()) bandIdx = 0;
        }

        private String greeting(int hour) {
            if (hour < 5) return getContext().getString(R.string.good_night);
            if (hour < 12) return getContext().getString(R.string.good_morning);
            if (hour < 17) return getContext().getString(R.string.good_afternoon);
            if (hour < 21) return getContext().getString(R.string.good_evening);
            return getContext().getString(R.string.good_night);
        }

        private Board.Item pickPhoto(long now) {
            List<Board.Item> list = Board.photos();
            if (list.isEmpty()) return null;
            Board.Item newest = list.get(0);
            long featureMs = Math.max(0,
                    getResources().getInteger(R.integer.photo_feature_minutes)) * 60_000L;
            if (now - newest.receivedAt < featureMs) return newest;
            long slot = now / PHOTO_SLOT_MS;
            if (slot % 2 == 0) return null;
            return list.get((int) ((slot / 2) % list.size()));
        }

        // ---- drawing ----

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            long now = System.currentTimeMillis();
            Calendar cal = Calendar.getInstance();
            lastDim = dimFactor(cal);
            if (lastDim <= 0f) {
                canvas.drawColor(Color.BLACK);
                return;
            }
            float sec = (now - t0) / 1000f;
            clock.setTime(now);
            ensureSky(bucketFor(cal.get(Calendar.HOUR_OF_DAY)), w, h);
            pollContent(now);

            drawSky(canvas, sec, w, h);

            // the whole composition breathes a little, which keeps a static panel safe without
            // scattering the elements around the screen
            canvas.save();
            canvas.translate(w * 0.010f * (float) Math.sin(sec * 0.013),
                             h * 0.012f * (float) Math.cos(sec * 0.011));

            Board.Item shot = pickPhoto(now);
            if (shot != null && shot.isPhoto()) {
                drawPhoto(canvas, shot, w, h);
                drawCornerClock(canvas, w, h);
            } else {
                drawClock(canvas, cal, w, h);
                drawWeather(canvas, w, h);
                drawBand(canvas, now, w, h);
            }
            canvas.restore();

            if (lastDim < 1f) {
                canvas.drawColor(Color.argb((int) (255 * (1f - lastDim)), 0, 0, 0));
            }
        }

        private void drawSky(Canvas canvas, float sec, int w, int h) {
            sky.setShader(skyShader);
            canvas.drawRect(0, 0, w, h, sky);

            glow.setShader(glowA);
            canvas.save();
            canvas.translate(w * (0.22f + 0.16f * (float) Math.sin(sec * 0.021)),
                             h * (0.78f + 0.12f * (float) Math.cos(sec * 0.017)));
            canvas.drawRect(-w, -h, w, h, glow);
            canvas.restore();

            glow.setShader(glowB);
            canvas.save();
            canvas.translate(w * (0.80f + 0.14f * (float) Math.cos(sec * 0.015)),
                             h * (0.24f + 0.14f * (float) Math.sin(sec * 0.019)));
            canvas.drawRect(-w, -h, w, h, glow);
            canvas.restore();

            vignette.setShader(vignetteShader);
            canvas.drawRect(0, 0, w, h, vignette);
        }

        /**
         * The one loud element. Oversized hairline numerals filled with a vertical gradient that
         * picks up the hour's own sky colour, so the time belongs to the scene rather than sitting
         * on top of it as flat white text.
         */
        private void drawClock(Canvas canvas, Calendar cal, int w, int h) {
            float margin = w * 0.075f;
            float size = h * 0.34f;
            float baseline = h * 0.345f;
            String t = hhmm.format(clock);

            if (timeShader == null || timeShaderSize != size || timeShaderBucket != skyBucket) {
                timeShaderSize = size;
                timeShaderBucket = skyBucket;
                int tint = blend(SKY[skyBucket][2], 0xFFFFFFFF, 0.55f);
                timeShader = new LinearGradient(0, baseline - size * 0.78f, 0, baseline,
                        0xFFFFFFFF, tint, Shader.TileMode.CLAMP);
            }
            time.setTextSize(size);
            time.setShader(timeShader);
            time.setShadowLayer(h * 0.05f, 0, h * 0.004f, 0x40000000);
            canvas.drawText(t, margin, baseline, time);
            time.setShader(null);
            time.clearShadowLayer();

            meridiem.setTextSize(h * 0.05f);
            date.setTextSize(h * 0.042f);
            canvas.drawText(ampm.format(clock).toLowerCase(Locale.getDefault()),
                    margin + time.measureText(t) + w * 0.014f, baseline, meridiem);
            canvas.drawText(greeting(cal.get(Calendar.HOUR_OF_DAY)) + ". " + longDate.format(clock),
                    margin + h * 0.006f, baseline + h * 0.072f, date);
            try {
                hindu.setTextSize(h * 0.036f);
                canvas.drawText(Panchang.at(cal).line(), margin + h * 0.006f,
                        baseline + h * 0.122f, hindu);
            } catch (Exception ignored) {
                // the western date alone is fine
            }
        }

        /** Mixes two colours, amount 0 keeps a, amount 1 gives b. */
        private static int blend(int a, int b, float amount) {
            float keep = 1f - amount;
            return Color.argb(255,
                    (int) (Color.red(a) * keep + Color.red(b) * amount),
                    (int) (Color.green(a) * keep + Color.green(b) * amount),
                    (int) (Color.blue(a) * keep + Color.blue(b) * amount));
        }

        /** Always on screen, opposite the time: now, what it is doing, and today's range. */
        private void drawWeather(Canvas canvas, int w, int h) {
            BriefFetcher.Weather wx = BriefFetcher.weather;
            if (wx == null) return;
            float right = w - w * 0.075f;
            float baseline = h * 0.345f;      // shares the clock's baseline, so the two blocks lock together
            temp.setTextSize(h * 0.14f);
            sky2.setTextSize(h * 0.042f);
            canvas.drawText(wx.temp + "°", right, baseline, temp);
            String line = wx.condition + (wx.place.isEmpty() ? "" : " in " + wx.place);
            canvas.drawText(line, right, baseline + h * 0.052f, sky2);
            sky2.setTextSize(h * 0.036f);
            String range = wx.low + "° to " + wx.high + "°";
            if (wx.rainChance >= 0) range += "    " + wx.rainChance + "% rain";
            canvas.drawText(range, right, baseline + h * 0.100f, sky2);
        }

        /** A note or a headline, low and quiet, with the feed's picture when there is one. */
        private void drawBand(Canvas canvas, long now, int w, int h) {
            if (items.isEmpty()) return;
            long into = now - bandStart;
            if (into >= BAND_CYCLE_MS) {
                bandStart = now;
                into = 0;
                bandIdx = (bandIdx + 1) % items.size();
            }
            float a = 1f;
            if (into < BAND_FADE_MS) a = into / (float) BAND_FADE_MS;
            else if (into > BAND_CYCLE_MS - BAND_FADE_MS) {
                a = (BAND_CYCLE_MS - into) / (float) BAND_FADE_MS;
            }
            a = Math.max(0f, Math.min(1f, a));
            BandItem item = items.get(bandIdx);

            float margin = w * 0.075f;
            float centre = h * 0.835f;
            float textLeft = margin;

            if (item.image != null && !item.image.isRecycled()) {
                float size = h * 0.17f;
                dst.set(margin, centre - size / 2f, margin + size * 1.35f, centre + size / 2f);
                canvas.save();
                clip.reset();
                clip.addRoundRect(dst, h * 0.016f, h * 0.016f, Path.Direction.CW);
                canvas.clipPath(clip);
                photo.setAlpha((int) (255 * a));
                canvas.drawBitmap(item.image, null, dst, photo);
                canvas.restore();
                textLeft = dst.right + w * 0.022f;
            }

            band.setAlpha((int) (255 * a));
            band.setTextSize(h * 0.050f);
            float maxWidth = (w - margin) - textLeft;
            List<String> lines = wrap(item.text, band, maxWidth, 2);
            float lineH = h * 0.064f;
            float y = centre - (lines.size() - 1) * lineH / 2f + h * 0.014f;
            for (String line : lines) {
                canvas.drawText(line, textLeft, y, band);
                y += lineH;
            }
        }

        /** Greedy word wrap, capped at maxLines with an ellipsis on the last one. */
        private static List<String> wrap(String text, Paint paint, float maxWidth, int maxLines) {
            List<String> out = new ArrayList<>();
            String[] words = text.split("\\s+");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (paint.measureText(candidate) <= maxWidth || line.length() == 0) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                    if (out.size() == maxLines) break;
                }
            }
            if (out.size() < maxLines && line.length() > 0) out.add(line.toString());
            if (out.size() == maxLines && !out.isEmpty()) {
                String last = out.get(out.size() - 1);
                while (paint.measureText(last + "…") > maxWidth && last.length() > 1) {
                    last = last.substring(0, last.length() - 1);
                }
                boolean truncated = paint.measureText(text) > maxWidth * maxLines;
                if (truncated) out.set(out.size() - 1, last.trim() + "…");
            }
            return out;
        }

        private void drawPhoto(Canvas canvas, Board.Item shot, int w, int h) {
            float margin = w * 0.07f;
            float captionH = h * 0.12f;
            float availW = w - 2 * margin;
            float availH = h - 2 * margin - captionH;
            int bw = shot.photo.getWidth(), bh = shot.photo.getHeight();
            if (bw <= 0 || bh <= 0) return;
            float scale = Math.min(availW / bw, availH / bh);
            float dw = bw * scale, dh = bh * scale;
            float left = (w - dw) / 2f;
            float top = margin + (availH - dh) / 2f;
            dst.set(left, top, left + dw, top + dh);

            float r = Math.min(w, h) * 0.022f;
            float pad = Math.min(w, h) * 0.012f;
            canvas.save();
            clip.reset();
            clip.addRoundRect(dst.left - pad, dst.top - pad, dst.right + pad, dst.bottom + pad,
                    r + pad, r + pad, Path.Direction.CW);
            canvas.drawPath(clip, mat);
            canvas.restore();

            canvas.save();
            clip.reset();
            clip.addRoundRect(dst, r, r, Path.Direction.CW);
            canvas.clipPath(clip);
            photo.setAlpha(255);
            canvas.drawBitmap(shot.photo, null, dst, photo);
            canvas.restore();

            String caption = shot.line();
            if (!caption.isEmpty()) {
                band.setAlpha(230);
                band.setTextSize(h * 0.045f);
                band.setTextAlign(Paint.Align.CENTER);
                float cw = band.measureText(caption);
                float maxW = w - 2 * margin;
                if (cw > maxW) band.setTextSize(band.getTextSize() * maxW / cw);
                canvas.drawText(caption, w / 2f, dst.bottom + h * 0.075f, band);
                band.setTextAlign(Paint.Align.LEFT);
            }
        }

        private void drawCornerClock(Canvas canvas, int w, int h) {
            time.setTextSize(h * 0.075f);
            date.setTextSize(h * 0.030f);
            float x = w * 0.05f, y = h * 0.11f;
            canvas.drawText(hhmm.format(clock) + " "
                    + ampm.format(clock).toLowerCase(Locale.getDefault()), x, y, time);
            canvas.drawText(longDate.format(clock), x, y + h * 0.042f, date);
        }
    }
}
