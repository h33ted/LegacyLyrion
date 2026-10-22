package com.h33ted.legacylyrion;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny async image loader with a memory cache; the view's tag guards against recycled rows. */
final class ImageLoader {
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) Math.min(Runtime.getRuntime().maxMemory() / 8, 16 * 1024 * 1024)) {
                @Override
                protected int sizeOf(String k, Bitmap b) {
                    return b.getRowBytes() * b.getHeight();
                }
            };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ImageLoader() {}

    static void load(final ImageView iv, final String url, final boolean circle) {
        iv.setTag(url);
        iv.setImageDrawable(null);
        if (url == null) return;
        final String key = circle ? url + "#c" : url;
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = fetch(url);
                if (bmp == null) bmp = fetch(url); // the server's image proxy can be slow the first time
                if (bmp != null && circle) bmp = circle(bmp);
                if (bmp != null) CACHE.put(key, bmp);
                final Bitmap fb = bmp;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fb != null && url.equals(iv.getTag())) iv.setImageBitmap(fb);
                    }
                });
            }
        });
    }

    /** Synchronous fetch for background threads (notification artwork). */
    static Bitmap fetchBlocking(String url) {
        Bitmap b = url == null ? null : CACHE.get(url);
        if (b == null && url != null) {
            b = fetch(url);
            if (b != null) CACHE.put(url, b);
        }
        return b;
    }

    private static Bitmap fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Bitmap circle(Bitmap src) {
        int s = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        BitmapShader sh = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setTranslate(-(src.getWidth() - s) / 2f, -(src.getHeight() - s) / 2f);
        sh.setLocalMatrix(m);
        p.setShader(sh);
        cv.drawCircle(s / 2f, s / 2f, s / 2f, p);
        return out;
    }
}
