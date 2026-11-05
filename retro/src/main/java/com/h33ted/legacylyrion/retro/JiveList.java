package com.h33ted.legacylyrion.retro;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A Jive menu shown as a list (library, Spotify, YouTube Music, radio...): loading, rows with
 * artwork, and what tapping or long-pressing an entry does, the way Squeezebox controllers do it.
 */
final class JiveList extends BaseAdapter {
    final RetroActivity a;
    final ListView list;
    final List<Item> items = new ArrayList<Item>();
    JSONObject base;
    private JSONArray cmd;
    private int gen;
    private final TextView status;
    private final int art;

    JiveList(RetroActivity a, TextView status) {
        this.a = a;
        this.status = status;
        this.art = a.dp(44) * Look.screenScale(a) / 100;
        list = new ListView(a);
        list.setCacheColorHint(0);
        list.setFastScrollEnabled(true);
        Drawable sel = Look.listSelector(a);
        if (sel != null) list.setSelector(sel);
        list.setAdapter(this);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < items.size()) open(items.get(pos));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= items.size()) return false;
                return itemMenu(items.get(pos));
            }
        });
    }

    /** The server's Jive home menu, fetched once: library entries live under node "myMusic". */
    private static JSONArray homeMenu;
    private String node;

    /** Shows the entries of one node of the home menu (e.g. "myMusic"), in the server's order. */
    void loadNode(final String n) {
        node = n;
        cmd = null;
        final int my = ++gen;
        items.clear();
        notifyDataSetChanged();
        if (homeMenu != null) {
            showNode(n);
            return;
        }
        showStatus("Loading…");
        Rpc.call(a, Rpc.cmd("menu", "0", "1000", "direct:1"), new Rpc.Callback() {
            public void done(JSONObject r, Exception e) {
                if (my != gen) return;
                JSONArray loop = r == null ? null : r.optJSONArray("item_loop");
                if (loop == null) {
                    showStatus("Couldn't load" + (e != null && e.getMessage() != null ? ": " + e.getMessage() : ""));
                    return;
                }
                homeMenu = loop;
                showNode(n);
            }
        });
    }

    private void showNode(String n) {
        List<JSONObject> picked = new ArrayList<JSONObject>();
        for (int i = 0; i < homeMenu.length(); i++) {
            JSONObject o = homeMenu.optJSONObject(i);
            if (o != null && n.equals(o.optString("node"))) picked.add(o);
        }
        java.util.Collections.sort(picked, new java.util.Comparator<JSONObject>() {
            public int compare(JSONObject x, JSONObject y) {
                double d = x.optDouble("weight", 100) - y.optDouble("weight", 100);
                return d < 0 ? -1 : d > 0 ? 1 : 0;
            }
        });
        base = null;
        items.clear();
        for (JSONObject o : picked) {
            Item it = Item.fromJive(o);
            if (it.title.length() > 0) items.add(it);
        }
        notifyDataSetChanged();
        showStatus(items.isEmpty() ? "Empty" : null);
    }

    void load(JSONArray c) {
        node = null;
        cmd = c;
        final int my = ++gen;
        items.clear();
        notifyDataSetChanged();
        showStatus("Loading…");
        if (c == null) {
            showStatus("Nothing here");
            return;
        }
        Rpc.call(a, c, new Rpc.Callback() {
            public void done(JSONObject r, Exception e) {
                if (my != gen) return;
                if (r == null) {
                    showStatus("Couldn't load" + (e != null && e.getMessage() != null ? ": " + e.getMessage() : ""));
                    return;
                }
                base = r.optJSONObject("base");
                JSONArray loop = r.optJSONArray("item_loop");
                for (int i = 0; loop != null && i < loop.length(); i++) {
                    JSONObject o = loop.optJSONObject(i);
                    if (o == null) continue;
                    Item it = Item.fromJive(o);
                    if (it.title.length() > 0) items.add(it);
                }
                notifyDataSetChanged();
                showStatus(items.isEmpty() ? "Empty" : null);
            }
        });
    }

    void reload() {
        if (node != null) {
            homeMenu = null;
            loadNode(node);
        } else {
            load(cmd);
        }
    }

    private void showStatus(String s) {
        if (status == null) return;
        status.setVisibility(s == null ? View.GONE : View.VISIBLE);
        if (s != null) status.setText(s);
    }

    // ------------------------------------------------------------------ rows

    public int getCount() { return items.size(); }

    public Object getItem(int i) { return items.get(i); }

    public long getItemId(int i) { return i; }

    public View getView(int i, View v, ViewGroup parent) {
        if (v == null) v = makeRow();
        Item it = items.get(i);
        Object[] h = (Object[]) v.getTag();
        ImageView iv = (ImageView) h[0];
        ((TextView) h[1]).setText(it.title);
        TextView sub = (TextView) h[2];
        String s = it.niceSubtitle();
        sub.setText(s);
        sub.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
        String url = Images.url(a, it.icon, art);
        iv.setVisibility(url == null ? View.GONE : View.VISIBLE);
        if (url != null) Images.load(iv, url, 0);
        return v;
    }

    private View makeRow() {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = a.dp(8);
        row.setPadding(pad, pad / 2, pad, pad / 2);
        row.setMinimumHeight(a.dp(52) * Look.screenScale(a) / 100);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(art, art);
        ip.rightMargin = pad;
        row.addView(iv, ip);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = a.text(18, false, 0xffffffff);
        TextView s = a.text(13, false, 0xffbdbdbd);
        col.addView(t);
        col.addView(s);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setTag(new Object[]{iv, t, s});
        return row;
    }

    // ------------------------------------------------------------------ actions

    static void exec(final RetroActivity a, JSONArray cmd, final String toast) {
        if (cmd == null) {
            a.toast("Not available for this item");
            return;
        }
        Rpc.call(a, cmd, new Rpc.Callback() {
            public void done(JSONObject r, Exception e) {
                if (e != null) a.toast("Failed: " + e.getMessage());
                else if (toast != null) a.toast(toast);
            }
        });
    }

    JSONArray playCmd(Item it) { return it.isTrack() ? null : Jive.command(it, base, "play", 0); }

    void open(Item it) {
        if (it.raw != null && it.raw.optInt("isANode", 0) == 1) {
            Intent i = new Intent(a, BrowseActivity.class);
            i.putExtra("title", it.title);
            i.putExtra("node", Item.str(it.raw, "id"));
            a.startActivity(i);
            return;
        }
        if (it.isTrack()) {
            playTrack(it);
            return;
        }
        String nw = it.nextWindow();
        if ("parent".equals(nw) || "grandparent".equals(nw) || "refresh".equals(nw)
                || "refreshOrigin".equals(nw) || "nowPlaying".equals(nw)) {
            exec(a, Jive.command(it, base, "go", 0), it.title);
            return;
        }
        if ("search".equals(it.type) || "text".equals(it.type)) {
            askInput(it);
            return;
        }
        JSONArray go = Jive.command(it, base, "go", 200);
        if (go == null) {
            exec(a, Jive.command(it, base, "play", 0), "Playing " + it.title);
            return;
        }
        browse(a, it.title, it.niceSubtitle(), go, playCmd(it), it.isTrack() ? null : Jive.command(it, base, "add", 0));
    }

    static void browse(RetroActivity a, String title, String sub, JSONArray go, JSONArray play, JSONArray add) {
        Intent i = new Intent(a, BrowseActivity.class);
        i.putExtra("title", title);
        i.putExtra("subtitle", sub);
        i.putExtra("cmd", go.toString());
        if (play != null) i.putExtra("play", play.toString());
        if (add != null) i.putExtra("add", add.toString());
        a.startActivity(i);
    }

    /** Tap on a song: play it in its context (the rest of the album or list follows). */
    void playTrack(final Item it) {
        JSONArray c = Jive.command(it, base, "playControl", 0);
        if (c == null) {
            exec(a, Jive.command(it, base, "play", 0), "Playing " + it.title);
            return;
        }
        Rpc.call(a, c, new Rpc.Callback() {
            public void done(JSONObject r, Exception e) {
                if (e != null) {
                    a.toast("Failed: " + e.getMessage());
                    return;
                }
                // with music already queued Lyrion answers with a choice; take "play all from here"
                JSONArray choice = menuChoice(r, "item_playall");
                if (choice == null) choice = menuChoice(r, "item_play");
                if (choice != null) exec(a, choice, "Playing " + it.title);
                else a.toast("Playing " + it.title);
            }
        });
    }

    private static JSONArray menuChoice(JSONObject r, String style) {
        JSONArray loop = r == null ? null : r.optJSONArray("item_loop");
        for (int i = 0; loop != null && i < loop.length(); i++) {
            JSONObject o = loop.optJSONObject(i);
            if (o == null || !style.equals(o.optString("style"))) continue;
            JSONObject acts = o.optJSONObject("actions");
            JSONObject go = acts == null ? null : acts.optJSONObject("go");
            JSONArray c = go == null ? null : go.optJSONArray("cmd");
            if (c == null) return null;
            JSONArray out = new JSONArray();
            for (int k = 0; k < c.length(); k++) out.put(c.optString(k));
            JSONObject p = go.optJSONObject("params");
            if (p != null) {
                java.util.Iterator<?> keys = p.keys();
                while (keys.hasNext()) {
                    String key = String.valueOf(keys.next());
                    out.put(key + ":" + p.optString(key));
                }
            }
            return out;
        }
        return null;
    }

    /** Long-press: Play now / Play next / Add to queue. */
    boolean itemMenu(final Item it) {
        final JSONArray play = Jive.command(it, base, "play", 0);
        if (play == null) return false;
        new AlertDialog.Builder(a)
                .setTitle(it.title)
                .setItems(new String[]{"Play now", "Play next", "Add to queue"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String[] act = {"play", "add-hold", "add"};
                        String[] msg = {"Playing ", "Playing next: ", "Added: "};
                        exec(a, which == 0 ? play : Jive.command(it, base, act[which], 0), msg[which] + it.title);
                    }
                })
                .show();
        return true;
    }

    private void askInput(final Item it) {
        final EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(a)
                .setTitle(it.title)
                .setView(e)
                .setPositiveButton("Search", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String q = e.getText().toString().trim();
                        if (q.length() == 0) return;
                        JSONArray c = Jive.withInput(Jive.command(it, base, "go", 200), q);
                        if (c != null) browse(a, q, it.title, c, null, null);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
