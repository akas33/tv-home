package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * The focus ring: one crisp outline in the accent colour with a soft halo outside it. Floats over
 * the grid and is moved between tiles with spring animations.
 *
 * It used to be a filled translucent slab drawn ON TOP of the focused tile, which washed the app's
 * artwork out and looked like a sheet of plastic lying over the screen. A ring marks the same tile
 * without covering any of it.
 */
public class GlassView extends View {

    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float radius;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2f * density);
        rim.setColor(Sky.ACCENT);
        halo.setStyle(Paint.Style.STROKE);
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;   // room for the halo
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        // Halo: three widening, fading strokes in the accent colour.
        int rgb = Sky.ACCENT & 0x00FFFFFF;
        for (int i = 1; i <= 3; i++) {
            halo.setStrokeWidth(i * 2f * density);
            halo.setColor((0x3A / i) << 24 | rgb);
            float o = i * density;
            canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o,
                    radius + o, radius + o, halo);
        }

        canvas.drawRoundRect(rect, radius, radius, rim);
    }
}
