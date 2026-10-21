package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * The focus highlight: a pane of dark glass behind the tile you are on, with a soft sheen down it
 * and a hairline rim.
 *
 * It is drawn BEHIND the tile, not over it, which is the whole point. A filled pane drawn on top
 * washed the app's artwork out - white over Netflix, and the pink ring that replaced it read as a
 * box drawn round the app rather than the app being lit.
 */
public class GlassView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final float density;
    private float radius;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * density);
        rim.setColor(0x59FFFFFF);   // a plain hairline, not the accent: a coloured ring read as a box
        halo.setStyle(Paint.Style.STROKE);
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Dark glass: lighter at the top, almost black at the bottom.
        fill.setShader(new LinearGradient(0, 0, 0, h, 0x4DFFFFFF, 0x1A000000, Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, 0, 0, h * 0.45f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;   // room for the halo
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        for (int i = 1; i <= 3; i++) {
            halo.setStrokeWidth(i * 2f * density);
            halo.setColor((0x26 / i) << 24 | 0xFFFFFF);
            float o = i * density;
            canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o,
                    radius + o, radius + o, halo);
        }

        canvas.drawRoundRect(rect, radius, radius, fill);

        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.45f, sheen);
        canvas.restore();

        canvas.drawRoundRect(rect, radius, radius, rim);
    }
}
