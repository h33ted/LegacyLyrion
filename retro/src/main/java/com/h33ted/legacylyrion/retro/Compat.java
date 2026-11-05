package com.h33ted.legacylyrion.retro;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;

/**
 * The few things that differ between Android 1.5 and later, reached by reflection so one APK
 * runs from Cupcake (API 3) upwards without touching a missing method.
 */
final class Compat {
    private Compat() {}

    private static int sdk = -1;

    /** API level; Build.VERSION.SDK_INT itself only exists from 1.6. */
    static int sdk() {
        if (sdk < 0) {
            try {
                sdk = Integer.parseInt(Build.VERSION.SDK);
            } catch (Exception e) {
                sdk = 3;
            }
        }
        return sdk;
    }

    /** Screen density factor (1.0 = 160 dpi); Android 1.5 has no densities and is always 1.0. */
    static float density(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        try {
            return DisplayMetrics.class.getField("density").getFloat(dm);
        } catch (Throwable t) {
            return 1f;
        }
    }

    static int dp(Context c, float v) {
        return (int) (v * density(c) + 0.5f);
    }

    static int screenW(Context c) {
        return c.getResources().getDisplayMetrics().widthPixels;
    }

    static int screenH(Context c) {
        return c.getResources().getDisplayMetrics().heightPixels;
    }

    /** A plain status bar notification: icon, title, text, tap target. */
    @SuppressWarnings("deprecation")
    static Notification notification(Context c, int icon, String title, String text, PendingIntent tap) {
        Notification n;
        if (sdk() >= 11) {
            try {
                Class<?> b = Class.forName("android.app.Notification$Builder");
                Object nb = b.getConstructor(Context.class).newInstance(c);
                b.getMethod("setSmallIcon", int.class).invoke(nb, icon);
                b.getMethod("setContentTitle", CharSequence.class).invoke(nb, title);
                b.getMethod("setContentText", CharSequence.class).invoke(nb, text);
                b.getMethod("setContentIntent", PendingIntent.class).invoke(nb, tap);
                b.getMethod("setOngoing", boolean.class).invoke(nb, true);
                n = (Notification) b.getMethod(sdk() >= 16 ? "build" : "getNotification").invoke(nb);
                return n;
            } catch (Throwable ignored) {}
        }
        n = new Notification();
        n.icon = icon;
        n.when = 0;
        n.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        try {
            Notification.class.getMethod("setLatestEventInfo", Context.class, CharSequence.class,
                    CharSequence.class, PendingIntent.class).invoke(n, c, title, text, tap);
        } catch (Throwable ignored) {}
        return n;
    }

    /** startForeground (2.0+) or setForeground (1.x), so playback is not killed in the background. */
    static void foreground(Service s, int id, Notification n, boolean on) {
        try {
            if (on) {
                Service.class.getMethod("startForeground", int.class, Notification.class).invoke(s, id, n);
            } else {
                Service.class.getMethod("stopForeground", boolean.class).invoke(s, true);
            }
            return;
        } catch (Throwable ignored) {}
        try {
            Service.class.getMethod("setForeground", boolean.class).invoke(s, on);
        } catch (Throwable ignored) {}
        android.app.NotificationManager nm = (android.app.NotificationManager) s.getSystemService(Context.NOTIFICATION_SERVICE);
        if (on) nm.notify(id, n);
        else nm.cancel(id);
    }

    /** Base64 for the Basic login header (android.util.Base64 is API 8). */
    static String base64(byte[] in) {
        final String T = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < in.length; i += 3) {
            int b = (in[i] & 0xff) << 16;
            if (i + 1 < in.length) b |= (in[i + 1] & 0xff) << 8;
            if (i + 2 < in.length) b |= in[i + 2] & 0xff;
            sb.append(T.charAt((b >> 18) & 63)).append(T.charAt((b >> 12) & 63));
            sb.append(i + 1 < in.length ? T.charAt((b >> 6) & 63) : '=');
            sb.append(i + 2 < in.length ? T.charAt(b & 63) : '=');
        }
        return sb.toString();
    }
}
