package com.h33ted.legacylyrion.retro;

import org.json.JSONObject;

/** One entry of a Jive menu (library, Spotty, YouTube Music, radio...). */
final class Item {
    String title = "";
    String subtitle = "";
    String icon;
    String type;
    JSONObject raw;

    static Item fromJive(JSONObject o) {
        Item it = new Item();
        it.raw = o;
        String text = o.optString("text", "");
        int nl = text.indexOf('\n');
        it.title = nl >= 0 ? text.substring(0, nl) : text;
        it.subtitle = nl >= 0 ? stripSeconds(text.substring(nl + 1).replace('\n', ' ')) : "";
        it.icon = str(o, "icon");
        if (it.icon == null) it.icon = str(o, "icon-id");
        if (it.icon == null) it.icon = str(o.optJSONObject("presetParams"), "icon");
        it.type = str(o, "type");
        return it;
    }

    static String str(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return null;
        String s = o.optString(k, null);
        return (s == null || s.length() == 0) ? null : s;
    }

    private JSONObject go() {
        JSONObject a = raw == null ? null : raw.optJSONObject("actions");
        return a == null ? null : a.optJSONObject("go");
    }

    boolean isTrack() {
        if (raw == null) return false;
        if (raw.has("playControlParams") || "audio".equals(type)) return true;
        JSONObject pp = raw.optJSONObject("presetParams");
        return pp != null && "audio".equals(pp.optString("favorites_type")) && go() == null;
    }

    boolean isArtist() { return subtitle.startsWith("Followers"); }

    /** Albums, playlists, mixes: things with Play / Add. */
    boolean isPlayableContainer() {
        if ("playlist".equals(type)) return true;
        JSONObject a = raw == null ? null : raw.optJSONObject("actions");
        return a != null && a.has("play") && !isTrack();
    }

    /** YouTube Music lines end in the length in seconds ("Daft Punk - Homework - 430"). */
    static String stripSeconds(String sub) {
        int k = sub.lastIndexOf(" - ");
        if (k < 0 || k + 3 >= sub.length() || sub.length() - k - 3 > 5) return sub;
        for (int i = k + 3; i < sub.length(); i++) if (!Character.isDigit(sub.charAt(i))) return sub;
        return sub.substring(0, k);
    }

    String niceSubtitle() { return subtitle.startsWith("Followers") ? "Artist" : subtitle; }

    String nextWindow() {
        JSONObject g = go();
        return g == null ? null : str(g, "nextWindow");
    }
}
