package com.h33ted.legacylyrion;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Album / playlist / artist / "see all" page: big artwork, title, Play and Queue buttons,
 * then the list. Works for Spotty (Jive commands passed in) and for the local library.
 */
public class BrowseActivity extends Activity {

    private final List<Item> items = new ArrayList<Item>();
    private JSONObject base;
    private String hp, localKind, localId;
    private boolean tracksOnly;
    private MiniPlayer mini;
    private ProgressBar progress;
    private TextView status;
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browse);
        hp = Prefs.hostPort(this);
        mini = new MiniPlayer(this);

        String title = getIntent().getStringExtra("title");
        String subtitle = getIntent().getStringExtra("subtitle");
        String icon = getIntent().getStringExtra("icon");
        boolean circle = getIntent().getBooleanExtra("circle", false);
        tracksOnly = getIntent().getBooleanExtra("tracks_only", false);
        localKind = getIntent().getStringExtra("local_kind");
        localId = getIntent().getStringExtra("local_id");
        Skin.decorate(this, title, null, true);

        final JSONArray play, add;
        if (localKind != null && ("album".equals(localKind) || "artist".equals(localKind))) {
            Item self = Item.local(localKind, localId, title, subtitle, icon);
            play = Nav.localCmd("load", self);
            add = Nav.localCmd("add", self);
        } else {
            play = parse(getIntent().getStringExtra("play_cmd"));
            add = parse(getIntent().getStringExtra("add_cmd"));
        }

        ListView list = (ListView) findViewById(R.id.list);
        list.addHeaderView(header(title, subtitle, icon, circle, play, add), null, false);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() { return items.size(); }

            @Override
            public Object getItem(int i) { return items.get(i); }

            @Override
            public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View v, ViewGroup parent) {
                if (v == null) v = Ui.makeRow(BrowseActivity.this);
                Ui.bindRow(v, hp, items.get(i));
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                Object o = parent.getItemAtPosition(pos);
                if (o instanceof Item) Nav.open(BrowseActivity.this, (Item) o, base);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                Object o = parent.getItemAtPosition(pos);
                if (!(o instanceof Item)) return false;
                Nav.itemMenu(BrowseActivity.this, (Item) o, base);
                return true;
            }
        });

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mini.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mini.stop();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static JSONArray parse(String s) {
        if (s == null) return null;
        try {
            return new JSONArray(s);
        } catch (Exception e) {
            return null;
        }
    }

    private View header(String title, String subtitle, String icon, boolean circle,
                        final JSONArray play, final JSONArray add) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setGravity(Gravity.CENTER_HORIZONTAL);
        h.setPadding(Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 8));

        String url = Ui.art(hp, icon, 300);
        if (url != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Ui.placeholder(iv, circle);
            int s = Ui.dp(this, 200);
            h.addView(iv, new LinearLayout.LayoutParams(s, s));
            ImageLoader.load(iv, url, circle);
        }
        TextView t = Ui.text(this, 22, true, true);
        t.setSingleLine(false);
        t.setMaxLines(2);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(0, Ui.dp(this, 14), 0, 0);
        t.setText(title);
        h.addView(t);
        if (subtitle != null && subtitle.length() > 0) {
            TextView st = Ui.text(this, 14, false, false);
            st.setGravity(Gravity.CENTER_HORIZONTAL);
            st.setText(subtitle);
            h.addView(st);
        }
        if (play != null || add != null) {
            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setGravity(Gravity.CENTER_HORIZONTAL);
            btns.setPadding(0, Ui.dp(this, 12), 0, 0);
            // plain framework buttons, so they wear the OEM skin's button style
            if (play != null) {
                Button b = new Button(this);
                b.setText("Play");
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Nav.exec(BrowseActivity.this, play, "Playing " + getTitleText(), null);
                    }
                });
                btns.addView(b, new LinearLayout.LayoutParams(Ui.dp(this, 130), LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            if (add != null) {
                Button b = new Button(this);
                b.setText("Add to queue");
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Nav.exec(BrowseActivity.this, add, "Added to queue", null);
                    }
                });
                btns.addView(b, new LinearLayout.LayoutParams(Ui.dp(this, 130), LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            h.addView(btns);
        }
        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setPadding(0, Ui.dp(this, 12), 0, 0);
        h.addView(progress, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 52)));
        status = Ui.text(this, 14, false, false);
        status.setSingleLine(false);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setVisibility(View.GONE);
        h.addView(status);
        return h;
    }

    private String getTitleText() {
        return getIntent().getStringExtra("title");
    }

    private void load() {
        JSONArray cmd;
        if (localKind == null) {
            cmd = parse(getIntent().getStringExtra("cmd"));
        } else if ("album".equals(localKind)) {
            cmd = Rpc.cmd("titles", "0", "500", "album_id:" + localId, "sort:tracknum", "tags:altc");
        } else if ("artist".equals(localKind)) {
            cmd = Rpc.cmd("albums", "0", "300", "artist_id:" + localId, "sort:yearalbum", "tags:lajy");
        } else if ("search_tracks".equals(localKind)) {
            cmd = Rpc.cmd("titles", "0", "200", "search:" + localId, "tags:alc");
        } else if ("albums_sort".equals(localKind)) {
            cmd = Rpc.cmd("albums", "0", "200", "sort:" + localId, "tags:laj");
        } else {
            cmd = null;
        }
        if (cmd == null) {
            done("Nothing to show");
            return;
        }
        Rpc.call(hp, Prefs.player(this), cmd, new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                if (r == null) {
                    BrowseActivity.this.done("Couldn't load" + (e != null ? ": " + e.getMessage() : ""));
                    return;
                }
                items.clear();
                if (localKind == null) {
                    base = r.optJSONObject("base");
                    JSONArray loop = r.optJSONArray("item_loop");
                    for (int i = 0; loop != null && i < loop.length(); i++) {
                        JSONObject o = loop.optJSONObject(i);
                        if (o == null) continue;
                        Item it = Item.fromJive(o);
                        if (it.title.length() == 0 || (tracksOnly && !it.isTrack())) continue;
                        items.add(it);
                    }
                } else if (r.has("titles_loop")) {
                    List<Item> tracks = HomeActivity.localTracks(r);
                    boolean album = "album".equals(localKind);
                    for (int i = 0; i < tracks.size(); i++) {
                        Item it = tracks.get(i);
                        if (album) {
                            it.parentAlbumId = localId;
                            it.position = i;
                            it.title = (i + 1) + ". " + it.title;
                        }
                        items.add(it);
                    }
                } else {
                    items.addAll(HomeActivity.localAlbums(r));
                }
                adapter.notifyDataSetChanged();
                BrowseActivity.this.done(items.isEmpty() ? "Empty" : null);
            }
        });
    }

    private void done(String msg) {
        progress.setVisibility(View.GONE);
        status.setVisibility(msg == null ? View.GONE : View.VISIBLE);
        if (msg != null) status.setText(msg);
    }
}
