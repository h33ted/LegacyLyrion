package com.h33ted.legacylyrion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** A Drawable for an SVG in res/raw (e.g. action-bar icons); re-fitted in pixels whenever its bounds change. */
final class SvgDrawable extends Drawable {
    private final SvgIcon icon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int intrinsic;
    private Path fitted;

    SvgDrawable(Context c, int rawRes, int color, int sizeDp) {
        icon = SvgIcon.get(c, rawRes);
        paint.setColor(color);
        intrinsic = Ui.dp(c, sizeDp);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        fitted = icon.fit(b.exactCenterX(), b.exactCenterY(), Math.min(b.width(), b.height()));
    }

    @Override
    public void draw(Canvas c) {
        if (fitted == null) onBoundsChange(getBounds());
        c.drawPath(fitted, paint);
    }

    @Override
    public int getIntrinsicWidth() { return intrinsic; }

    @Override
    public int getIntrinsicHeight() { return intrinsic; }

    @Override
    public void setAlpha(int a) {
        paint.setAlpha(a);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
