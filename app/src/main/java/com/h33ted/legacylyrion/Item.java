package com.h33ted.legacylyrion;

import org.json.JSONObject;

/** One browsable entry: either a Jive item from a plugin (Spotty) or a local library object. */
final class Item {
    String title = "";
    String subtitle = "";
    String icon;
    String type;
    String id;
    JSONObject raw;

    // local library entries
    String localKind; // track, album, artist
    String localId;
    int position = -1; // index within a local album, for "play from here"
    String parentAlbumId;

    static Item fromJive(JSONObject o) {
        Item it = new Item();
        it.raw = o;
        String text = o.optString("text", "");
        int nl = text.indexOf('\n');
        it.title = nl >= 0 ? text.substring(0, nl) : text;
        it.subtitle = nl >= 0 ? text.substring(nl + 1).replace('\n', ' ') : "";
        it.subtitle = stripSeconds(it.subtitle);
        it.icon = str(o, "icon");
        if (it.icon == null) it.icon = str(o, "icon-id");
        if (it.icon == null) it.icon = str(o.optJSONObject("presetParams"), "icon");
        it.type = str(o, "type");
        it.id = str(o.optJSONObject("params"), "item_id");
        if (it.id == null) it.id = str(goAction(o) == null ? null : goAction(o).optJSONObject("params"), "item_id");
        return it;
    }

    static Item local(String kind, String id, String title, String subtitle, String icon) {
        Item it = new Item();
        it.localKind = kind;
        it.localId = id;
        it.title = title == null ? "" : title;
        it.subtitle = subtitle == null ? "" : subtitle;
        it.icon = icon;
        return it;
    }

    static String str(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return null;
        String s = o.optString(k, null);
        return (s == null || s.length() == 0) ? null : s;
    }

    private static JSONObject goAction(JSONObject o) {
        JSONObject a = o.optJSONObject("actions");
        return a == null ? null : a.optJSONObject("go");
    }

    boolean isLocal() { return localKind != null; }

    boolean isTrack() {
        if (isLocal()) return "track".equals(localKind);
        if (raw.has("playControlParams") || "audio".equals(type)) return true;
        JSONObject pp = raw.optJSONObject("presetParams");
        return pp != null && "audio".equals(pp.optString("favorites_type")) && goAction(raw) == null;
    }

    boolean isArtist() {
        if (isLocal()) return "artist".equals(localKind);
        return subtitle.startsWith("Followers");
    }

    /** Albums, playlists, mixes: things with a Play button. */
    boolean isPlayableContainer() {
        if (isLocal()) return "album".equals(localKind) || "artist".equals(localKind);
        return "playlist".equals(type);
    }

    /** YouTube Music lines end in the length in seconds ("Daft Punk - Homework - 430"); drop it. */
    static String stripSeconds(String sub) {
        int k = sub.lastIndexOf(" - ");
        if (k < 0 || k + 3 >= sub.length() || sub.length() - k - 3 > 5) return sub;
        for (int i = k + 3; i < sub.length(); i++) if (!Character.isDigit(sub.charAt(i))) return sub;
        return sub.substring(0, k);
    }

    String niceSubtitle() {
        if (subtitle.startsWith("Followers")) return "Artist";
        return subtitle;
    }

    /** Jive "nextWindow" of the go action: parent/refresh mean "do it and stay here". */
    String nextWindow() {
        JSONObject go = raw == null ? null : goAction(raw);
        return go == null ? null : str(go, "nextWindow");
    }
}
