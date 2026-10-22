package com.h33ted.legacylyrion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Turns a Jive item + its list's "base" into a CLI command, the way Squeezebox controllers do. */
final class Jive {
    static final String INPUT = "__TAGGEDINPUT__";

    private Jive() {}

    static JSONArray command(Item it, JSONObject base, String action, int count) {
        if (it == null || it.raw == null) return null;
        JSONObject act = null;
        boolean fromBase = false;
        JSONObject ia = it.raw.optJSONObject("actions");
        if (ia != null) act = ia.optJSONObject(action);
        if (act == null && base != null) {
            JSONObject ba = base.optJSONObject("actions");
            if (ba != null) {
                act = ba.optJSONObject(action);
                fromBase = act != null;
            }
        }
        if (act == null) return null;
        JSONArray cmd = act.optJSONArray("cmd");
        if (cmd == null || cmd.length() == 0) return null;

        Map<String, String> kv = new LinkedHashMap<String, String>();
        putAll(kv, act.optJSONObject("params"));
        if (fromBase) {
            String ip = Item.str(act, "itemsParams");
            if (ip != null) {
                JSONObject extra = it.raw.optJSONObject(ip);
                if (extra == null) return null; // controllers skip the action in this case
                putAll(kv, extra);
            }
        }

        JSONArray out = new JSONArray();
        for (int i = 0; i < cmd.length(); i++) out.put(cmd.optString(i));
        String idx = kv.remove("_index");
        String qty = kv.remove("_quantity");
        if ("items".equals(cmd.optString(cmd.length() - 1))) {
            out.put(idx != null ? idx : "0");
            out.put(count > 0 ? String.valueOf(count) : (qty != null ? qty : "100"));
        }
        kv.remove("isContextMenu");
        for (Map.Entry<String, String> e : kv.entrySet()) out.put(e.getKey() + ":" + e.getValue());
        return out;
    }

    /** Replaces __TAGGEDINPUT__ in every token with the user's text. */
    static JSONArray withInput(JSONArray cmd, String text) {
        if (cmd == null) return null;
        JSONArray out = new JSONArray();
        for (int i = 0; i < cmd.length(); i++) out.put(cmd.optString(i).replace(INPUT, text));
        return out;
    }

    private static void putAll(Map<String, String> kv, JSONObject o) {
        if (o == null) return;
        Iterator<?> keys = o.keys();
        while (keys.hasNext()) {
            String k = String.valueOf(keys.next());
            if (!o.isNull(k)) kv.put(k, o.optString(k));
        }
    }
}
