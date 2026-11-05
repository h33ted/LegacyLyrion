package com.h33ted.legacylyrion.retro;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings: server, chosen player, whether the phone itself plays. */
final class Prefs {
    static final String FILE = "lyrion";

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String host(Context c) { return get(c).getString("lms_host", ""); }

    static String port(Context c) { return get(c).getString("lms_port", "9000"); }

    static String hostPort(Context c) { return host(c) + ":" + port(c); }

    static boolean setupDone(Context c) { return host(c).length() > 0; }

    /** Whether this phone registers itself as a Lyrion player (SlimProto). */
    static boolean phonePlayer(Context c) { return get(c).getBoolean("phone_player", true); }

    static String player(Context c) {
        String id = get(c).getString("player_id", null);
        if (id == null || id.length() == 0) return SlimProtoService.localMac(c);
        return id;
    }

    static boolean playerIsPhone(Context c) {
        return SlimProtoService.localMac(c).equalsIgnoreCase(player(c));
    }

    static void setPlayer(Context c, String id) { get(c).edit().putString("player_id", id).commit(); }

    /** Last home tab, so the app reopens where it was. */
    static String tab(Context c) { return get(c).getString("tab", null); }

    static void setTab(Context c, String t) { get(c).edit().putString("tab", t).commit(); }
}
