package com.h33ted.legacylyrion;

import android.content.Context;
import android.content.SharedPreferences;

/** One shared settings file for every screen (server, player, music source). */
final class Prefs {
    static final String FILE = "lyrion";
    static final String DEFAULT_HOST = ""; // no default: first-run setup finds or asks for the server

    private Prefs() {}

    static SharedPreferences get(Context c) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (!p.contains("lms_host")) {
            // migrate the old per-activity prefs written by MainActivity.getPreferences()
            SharedPreferences old = c.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
            if (old.contains("lms_host")) {
                p.edit().putString("lms_host", old.getString("lms_host", DEFAULT_HOST))
                        .putString("lms_port", old.getString("lms_port", "9000"))
                        .commit();
            }
        }
        return p;
    }

    static String host(Context c) { return get(c).getString("lms_host", DEFAULT_HOST); }

    static String port(Context c) { return get(c).getString("lms_port", "9000"); }

    static String hostPort(Context c) { return host(c) + ":" + port(c); }

    static String player(Context c) {
        String id = get(c).getString("player_id", null);
        if (id == null || SlimProtoService.LEGACY_SHARED_MAC.equalsIgnoreCase(id)) return SlimProtoService.localMac(c);
        return id;
    }

    /** Whether this phone also registers itself as a Lyrion player (SlimProto). */
    static boolean phonePlayer(Context c) { return get(c).getBoolean("phone_player", true); }

    static boolean setupDone(Context c) { return get(c).getBoolean("setup_done", false) || get(c).contains("lms_host"); }

    /** Where the home screen gets its music: Spotify (Spotty), YouTube Music, or the server's own library. */
    static final String SRC_SPOTIFY = "spotify", SRC_YOUTUBE = "youtube", SRC_LOCAL = "local";

    static String source(Context c) {
        SharedPreferences p = get(c);
        String s = p.getString("source", null);
        if (s != null) return s;
        return p.getBoolean("local_only", false) ? SRC_LOCAL : SRC_SPOTIFY;   // older versions had a checkbox
    }

    static void setSource(Context c, String s) { get(c).edit().putString("source", s).commit(); }

    /** True until a music source has been chosen, by the first-start check or by hand. */
    static boolean sourceUnset(Context c) {
        SharedPreferences p = get(c);
        return !p.contains("source") && !p.contains("local_only");
    }

    static boolean localOnly(Context c) { return SRC_LOCAL.equals(source(c)); }
}
