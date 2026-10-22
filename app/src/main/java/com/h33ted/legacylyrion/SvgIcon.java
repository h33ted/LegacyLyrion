package com.h33ted.legacylyrion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.SparseArray;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal SVG icon support for Android 4.x, which has no VectorDrawable. Reads the
 * {@code <path d="...">} elements of an SVG in res/raw (M L H V C S Q T Z, absolute and
 * relative; no arcs) into a Path in viewBox units.
 *
 * Drawing rule that keeps icons sharp: never scale the Canvas. With hardware acceleration,
 * Android 4.x rasterises a path once at its own coordinates and then stretches that texture,
 * which is what made the old 24-unit icons blurry. {@link #fit} instead transforms the path
 * itself into device pixels before it is drawn.
 */
final class SvgIcon {
    private static final SparseArray<SvgIcon> CACHE = new SparseArray<SvgIcon>();
    private static final Pattern D = Pattern.compile("\\sd=\"([^\"]+)\"");
    private static final Pattern VIEWBOX = Pattern.compile("viewBox=\"\\s*([-\\d.]+)[\\s,]+([-\\d.]+)[\\s,]+([-\\d.]+)[\\s,]+([-\\d.]+)");
    private static final Pattern TOKEN = Pattern.compile("[MmLlHhVvCcSsQqTtZz]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    final Path path;
    final RectF viewBox;

    private SvgIcon(Path p, RectF vb) {
        path = p;
        viewBox = vb;
    }

    static synchronized SvgIcon get(Context c, int rawRes) {
        SvgIcon s = CACHE.get(rawRes);
        if (s == null) {
            s = load(c, rawRes);
            CACHE.put(rawRes, s);
        }
        return s;
    }

    /** The icon's path scaled to fill {@code size}x{@code size} px (minus padding), centred at cx,cy. */
    Path fit(float cx, float cy, float size) {
        float s = size / Math.max(viewBox.width(), viewBox.height());
        Matrix m = new Matrix();
        m.setTranslate(-viewBox.centerX(), -viewBox.centerY());
        m.postScale(s, s);
        m.postTranslate(cx, cy);
        Path out = new Path();
        path.transform(m, out);
        return out;
    }

    /** Renders to a bitmap at an exact pixel size (for RemoteViews, which need bitmaps). */
    Bitmap render(int px, int color) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        new Canvas(b).drawPath(fit(px / 2f, px / 2f, px), p);
        return b;
    }

    // ------------------------------------------------------------------ parsing

    private static SvgIcon load(Context c, int rawRes) {
        String svg = "";
        try {
            InputStream in = c.getResources().openRawResource(rawRes);
            byte[] buf = new byte[in.available() + 1];
            int n = 0, r;
            while ((r = in.read(buf, n, buf.length - n)) > 0) {
                n += r;
                if (n == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
            }
            in.close();
            svg = new String(buf, 0, n, "UTF-8");
        } catch (Exception ignored) {}
        RectF vb = new RectF(0, 0, 24, 24);
        Matcher v = VIEWBOX.matcher(svg);
        if (v.find()) {
            float x = Float.parseFloat(v.group(1)), y = Float.parseFloat(v.group(2));
            vb.set(x, y, x + Float.parseFloat(v.group(3)), y + Float.parseFloat(v.group(4)));
        }
        Path all = new Path();
        Matcher d = D.matcher(" " + svg);
        while (d.find()) all.addPath(parse(d.group(1)));
        return new SvgIcon(all, vb);
    }

    static Path parse(String d) {
        List<String> t = new ArrayList<String>();
        Matcher m = TOKEN.matcher(d);
        while (m.find()) t.add(m.group());
        Path p = new Path();
        float x = 0, y = 0, sx = 0, sy = 0; // current point, subpath start
        float cx = 0, cy = 0;              // last control point (for S/T)
        char cmd = 'M', prev = ' ';
        int i = 0;
        while (i < t.size()) {
            String tok = t.get(i);
            if (Character.isLetter(tok.charAt(0))) {
                cmd = tok.charAt(0);
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    p.close();
                    x = sx;
                    y = sy;
                    prev = cmd;
                    continue;
                }
            }
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M':
                    x = ox + f(t, i++);
                    y = oy + f(t, i++);
                    p.moveTo(x, y);
                    sx = x;
                    sy = y;
                    cmd = rel ? 'l' : 'L'; // further pairs are line-tos
                    break;
                case 'L':
                    x = ox + f(t, i++);
                    y = oy + f(t, i++);
                    p.lineTo(x, y);
                    break;
                case 'H':
                    x = ox + f(t, i++);
                    p.lineTo(x, y);
                    break;
                case 'V':
                    y = oy + f(t, i++);
                    p.lineTo(x, y);
                    break;
                case 'C': {
                    float x1 = ox + f(t, i++), y1 = oy + f(t, i++);
                    cx = ox + f(t, i++);
                    cy = oy + f(t, i++);
                    x = ox + f(t, i++);
                    y = oy + f(t, i++);
                    p.cubicTo(x1, y1, cx, cy, x, y);
                    break;
                }
                case 'S': {
                    boolean after = "CcSs".indexOf(prev) >= 0;
                    float x1 = after ? 2 * x - cx : x, y1 = after ? 2 * y - cy : y;
                    cx = ox + f(t, i++);
                    cy = oy + f(t, i++);
                    x = ox + f(t, i++);
                    y = oy + f(t, i++);
                    p.cubicTo(x1, y1, cx, cy, x, y);
                    break;
                }
                case 'Q':
                    cx = ox + f(t, i++);
                    cy = oy + f(t, i++);
                    x = ox + f(t, i++);
                    y = oy + f(t, i++);
                    p.quadTo(cx, cy, x, y);
                    break;
                case 'T': {
                    boolean after = "QqTt".indexOf(prev) >= 0;
                    cx = after ? 2 * x - cx : x;
                    cy = after ? 2 * y - cy : y;
                    x = ox + f(t, i++);
                    y = oy + f(t, i++);
                    p.quadTo(cx, cy, x, y);
                    break;
                }
                default:
                    i++; // unsupported command (e.g. arcs): skip a token rather than loop forever
            }
            prev = cmd;
        }
        return p;
    }

    private static float f(List<String> t, int i) {
        return i < t.size() ? Float.parseFloat(t.get(i)) : 0f;
    }
}
