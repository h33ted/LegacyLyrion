package com.h33ted.legacylyrion.retro;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The play queue: tap to jump, long-press to remove; the playing song is marked. */
public class QueueActivity extends RetroActivity {
    private final List<JSONObject> songs = new ArrayList<JSONObject>();
    private int current = -1;
    private BaseAdapter adapter;
    private ListView list;
    private TextView head;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        View bar = titleBar("Play queue", "");
        root.addView(bar);
        head = (TextView) ((LinearLayout) bar).getChildAt(0);
        list = new ListView(this);
        list.setCacheColorHint(0);
        list.setFastScrollEnabled(true);
        android.graphics.drawable.Drawable sel = Look.listSelector(this);
        if (sel != null) list.setSelector(sel);
        final int art = dp(40) * Look.screenScale(this) / 100;
        adapter = new BaseAdapter() {
            public int getCount() { return songs.size(); }

            public Object getItem(int i) { return songs.get(i); }

            public long getItemId(int i) { return i; }

            public View getView(int i, View v, ViewGroup parent) {
                if (v == null) {
                    LinearLayout row = new LinearLayout(QueueActivity.this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(8), dp(4), dp(8), dp(4));
                    ImageView iv = new ImageView(QueueActivity.this);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(art, art);
                    ip.rightMargin = dp(8);
                    row.addView(iv, ip);
                    LinearLayout col = new LinearLayout(QueueActivity.this);
                    col.setOrientation(LinearLayout.VERTICAL);
                    TextView t = text(17, false, 0xffffffff), s = text(13, false, 0xffbdbdbd);
                    col.addView(t);
                    col.addView(s);
                    row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    row.setTag(new Object[]{iv, t, s});
                    v = row;
                }
                JSONObject o = songs.get(i);
                Object[] hld = (Object[]) v.getTag();
                TextView t = (TextView) hld[1];
                t.setText((i == current ? "▶ " : "") + o.optString("title"));
                t.setTypeface(null, i == current ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                ((TextView) hld[2]).setText(o.optString("artist"));
                String icon = Item.str(o, "artwork_url");
                if (icon == null) icon = Images.trackCover(Item.str(o, "coverid"));
                Images.load((ImageView) hld[0], Images.url(QueueActivity.this, icon, art), 0);
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Rpc.call(QueueActivity.this, Rpc.cmd("playlist", "index", String.valueOf(pos)), new Rpc.Callback() {
                    public void done(JSONObject r, Exception e) {
                        load();
                    }
                });
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, final int pos, long id) {
                new AlertDialog.Builder(QueueActivity.this)
                        .setTitle(songs.get(pos).optString("title"))
                        .setItems(new String[]{"Play", "Remove from queue", "Clear queue"}, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                org.json.JSONArray c = w == 0 ? Rpc.cmd("playlist", "index", String.valueOf(pos))
                                        : w == 1 ? Rpc.cmd("playlist", "delete", String.valueOf(pos)) : Rpc.cmd("playlist", "clear");
                                Rpc.call(QueueActivity.this, c, new Rpc.Callback() {
                                    public void done(JSONObject r, Exception e) {
                                        load();
                                    }
                                });
                            }
                        }).show();
                return true;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, 0, 1f));
        setContentView(root);
        load();
    }

    private void load() {
        Rpc.call(this, Rpc.cmd("status", "0", "500", "tags:alcK"), new Rpc.Callback() {
            public void done(JSONObject r, Exception e) {
                songs.clear();
                JSONArray loop = r == null ? null : r.optJSONArray("playlist_loop");
                for (int i = 0; loop != null && i < loop.length(); i++) {
                    JSONObject o = loop.optJSONObject(i);
                    if (o != null) songs.add(o);
                }
                current = r == null ? -1 : r.optInt("playlist_cur_index", -1);
                head.setText(r == null ? "Can't reach the player" : songs.isEmpty() ? "The queue is empty"
                        : "Play queue (" + songs.size() + ")");
                adapter.notifyDataSetChanged();
                if (current >= 0 && current < songs.size()) list.setSelection(Math.max(0, current - 1));
            }
        });
    }

    @Override
    void reload() { load(); }
}
