package com.h33ted.legacylyrion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Transport and toolbar buttons, drawn from the SVGs in res/raw in the theme's text colour.
 * Paths are fitted in device pixels (see {@link SvgIcon}), so they stay sharp on every density
 * and with hardware acceleration.
 */
public class GlyphView extends View {

    public static final int PLAY = 0, PAUSE = 1, PREV = 2, NEXT = 3, SHUFFLE = 4, REPEAT = 5,
            QUEUE = 6, PLUS = 7, VOLUME = 8, SEARCH = 9, MORE = 10;

    private static final int[] RAW = {R.raw.ic_play, R.raw.ic_pause, R.raw.ic_prev, R.raw.ic_next,
            R.raw.ic_shuffle, R.raw.ic_repeat, R.raw.ic_queue, R.raw.ic_add, R.raw.ic_volume,
            R.raw.ic_search, R.raw.ic_more};

    private int glyph = PLAY;
    private boolean ring = false;
    private boolean active = true;
    private boolean repeatOne = false;
    private int color;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Path fitted;
    private int fittedFor = -1;

    public GlyphView(Context c) {
        super(c);
        init();
    }

    public GlyphView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        setClickable(true);
        color = Ui.textPrimary(getContext()); // follow the skin, light or dark
        stroke.setStyle(Paint.Style.STROKE);
    }

    public void setGlyph(int g) {
        if (g != glyph) {
            glyph = g;
            fitted = null;
            invalidate();
        }
    }

    public void setRing(boolean r) {
        ring = r;
        fitted = null;
        invalidate();
    }

    public void setActive(boolean a) {
        active = a;
        invalidate();
    }

    public void setColor(int c) {
        color = c;
        invalidate();
    }

    /** "1" switches the repeat icon to repeat-one; null back to repeat. */
    public void setBadge(String b) {
        boolean one = b != null;
        if (one != repeatOne) {
            repeatOne = one;
            fitted = null;
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        fitted = null;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float size = Math.min(w, h);
        int alpha = active ? 255 : 110;
        if (isPressed()) alpha = Math.min(alpha, 150);
        fill.setColor(color);
        fill.setAlpha(alpha);
        stroke.setColor(color);
        stroke.setAlpha(alpha);

        if (ring) {
            stroke.setStrokeWidth(Math.max(1f, size * 0.035f));
            c.drawCircle(w / 2f, h / 2f, size / 2f - size * 0.03f, stroke);
        }
        int raw = glyph == REPEAT && repeatOne ? R.raw.ic_repeat_one : RAW[Math.max(0, Math.min(glyph, RAW.length - 1))];
        if (fitted == null || fittedFor != raw) {
            fitted = SvgIcon.get(getContext(), raw).fit(w / 2f, h / 2f, size * (ring ? 0.56f : 0.78f));
            fittedFor = raw;
        }
        c.drawPath(fitted, fill);
    }
}
