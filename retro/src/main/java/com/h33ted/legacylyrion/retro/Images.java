package com.h33ted.legacylyrion.retro;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Artwork for small, slow phones: the server shrinks it (Lyrion's image resizer), the phone
 * keeps a few dozen in memory, two downloads at a time. The view's tag guards recycled rows.
 */
final class Images {
    private static final int MAX = 48;
    private static final Map<String, Bitmap> CACHE = new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
            return size() > MAX;
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static Handler main;

    private Images() {}

    /** Full URL for a Jive icon or artwork_url at the given size (pixels). */
    static String url(Context c, String icon, int size) {
        if (icon == null || icon.length() == 0) return null;
        String base = "http://" + Prefs.hostPort(c);
        String sz = size + "x" + size;
        if (icon.startsWith("http://") || icon.startsWith("https://")) {
            if (icon.startsWith(base)) return icon;
            try { // the server fetches and shrinks remote art: 1.x phones can't do HTTPS to most CDNs
                return base + "/imageproxy/" + URLEncoder.encode(icon, "UTF-8") + "/image_" + sz + "_p.png";
            } catch (Exception e) {
                return icon;
            }
        }
        String path = icon.startsWith("/") ? icon : "/" + icon;
        if (path.startsWith("/imageproxy/") && path.endsWith("/image.png")) {
            path = path.substring(0, path.length() - "image.png".length()) + "image_" + sz + "_p.png";
        } else if (path.startsWith("/music/")) {
            if (path.matches(".*cover_\\d+x\\d+.*")) path = path.replaceAll("cover_\\d+x\\d+(_[a-zA-Z])?", "cover_" + sz + "_p");
            else if (path.matches("/music/[^/]+/cover(\\.\\w+)?")) path = path.replaceAll("/cover(\\.\\w+)?$", "/cover_" + sz + "_p.png");
        } else if (path.startsWith("/html/images/") || path.startsWith("/plugins/")) {
            int dot = path.lastIndexOf('.');
            if (dot > 0 && path.indexOf("_" + sz) < 0) path = path.substring(0, dot) + "_" + sz + "_p" + path.substring(dot);
        }
        return base + path;
    }

    static String trackCover(String id) {
        if (id == null || id.length() == 0 || "0".equals(id)) return null;
        return "/music/" + id + "/cover.png";
    }

    static void load(final ImageView iv, final String url, final int fallback) {
        if (main == null) main = new Handler();
        iv.setTag(url);
        Bitmap cached;
        synchronized (CACHE) {
            cached = url == null ? null : CACHE.get(url);
        }
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        if (fallback != 0) iv.setImageResource(fallback);
        else iv.setImageDrawable(null);
        if (url == null) return;
        final Context app = iv.getContext().getApplicationContext();
        POOL.execute(new Runnable() {
            public void run() {
                final Bitmap b = fetch(app, url, 0);
                main.post(new Runnable() {
                    public void run() {
                        if (b != null && url.equals(iv.getTag())) iv.setImageBitmap(b);
                    }
                });
            }
        });
    }

    /** Blocking fetch, scaled down to at most maxSide pixels (0 = as served). */
    static Bitmap fetch(Context c, String url, int maxSide) {
        synchronized (CACHE) {
            Bitmap b = CACHE.get(url);
            if (b != null) return b;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);
            Auth.apply(c, conn);
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            byte[] data = bo.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            if (maxSide > 0) {
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int s = 1;
                while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
            }
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b != null) {
                synchronized (CACHE) {
                    CACHE.put(url, b);
                }
            }
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
