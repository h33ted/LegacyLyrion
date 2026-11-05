package com.h33ted.legacylyrion.retro;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Home: tabs along the top like the phone's music library (Library, Spotify, YouTube Music,
 * Radio, More), the chosen menu below, and a now-playing strip at the bottom.
 */
public class HomeActivity extends RetroActivity {

    private static final class Tab {
        final String id, label;
        final JSONArray cmd;

        Tab(String id, String label, JSONArray cmd) {
            this.id = id;
            this.label = label;
            this.cmd = cmd;
        }
    }

    private final List<Tab> tabs = new ArrayList<Tab>();
    private final List<TextView> tabViews = new ArrayList<TextView>();
    private LinearLayout tabBar;
    private JiveList list;
    private TextView status, npTitle, npSub;
    private ImageView npArt;
    private String shownTab;
    private final Handler h = new Handler();
    private boolean resumed;
    private String npArtUrl;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Prefs.setupDone(this)) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        SlimProtoService.sync(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        tabBar = new LinearLayout(this);
        root.addView(tabBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, dp(46) * Look.screenScale(this) / 100));

        FrameLayout body = new FrameLayout(this);
        status = text(16, false, 0xffdddddd);
        status.setGravity(Gravity.CENTER);
        status.setSingleLine(false);
        status.setPadding(dp(16), dp(16), dp(16), dp(16));
        list = new JiveList(this, status);
        body.addView(list.list);
        body.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, 0, 1f));
        root.addView(nowPlayingStrip());
        setContentView(root);

        tabs.add(new Tab("library", "Library", null));
        tabs.add(new Tab("radio", "Radio", Rpc.cmd("radios", "0", "200", "menu:radio")));
        tabs.add(new Tab("more", "More", null));
        buildTabs();
        findSources();
        Updater.checkDaily(this);
    }

    /** Spotify and YouTube Music tabs appear when the server has those plugins. */
    private void findSources() {
        Rpc.call(this, "", Rpc.cmd("apps", "0", "100"), new Rpc.Callback() {
            public void done(JSONObject r, Exception e) {
                JSONArray loop = r == null ? null : r.optJSONArray("appss_loop");
                boolean spotty = false, yt = false;
                for (int i = 0; loop != null && i < loop.length(); i++) {
                    JSONObject o = loop.optJSONObject(i);
                    String c = o == null ? "" : o.optString("cmd");
                    if ("spotty".equals(c)) spotty = true;
                    if ("youtubemusic".equals(c)) yt = true;
                }
                int at = 1;
                if (spotty) tabs.add(at++, new Tab("spotify", "Spotify", Rpc.cmd("spotty", "items", "0", "200", "menu:spotty")));
                if (yt) tabs.add(at, new Tab("youtube", "YouTube", Rpc.cmd("youtubemusic", "items", "0", "200", "menu:youtubemusic")));
                buildTabs();
            }
        });
    }

    private void buildTabs() {
        tabBar.removeAllViews();
        tabViews.clear();
        String want = shownTab != null ? shownTab : Prefs.tab(this);
        Tab pick = tabs.get(0);
        for (final Tab t : tabs) {
            TextView v = text(14, false, 0xffffffff);
            v.setText(t.label);
            v.setEllipsize(null);
            android.content.res.ColorStateList tc = Look.tabText(this);
            if (tc != null) {
                v.setTextColor(tc);
                v.setShadowLayer(0, 0, 0, 0);
            }
            android.graphics.drawable.Drawable tb = Look.tab(this);
            android.graphics.Rect tp = new android.graphics.Rect();
            tb.getPadding(tp);
            // shrink the label until it fits its share of the screen, inside the tab's own padding
            float room = Compat.screenW(this) / (float) tabs.size() - tp.left - tp.right - dp(2);
            float px = v.getTextSize();
            while (px > 6 && v.getPaint().measureText(t.label) > room) {
                px -= 1;
                v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px);
            }
            v.setGravity(Gravity.CENTER);
            v.setBackgroundDrawable(tb);
            v.setFocusable(true);
            v.setClickable(true);
            v.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    show(t);
                }
            });
            tabBar.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.FILL_PARENT, 1f));
            tabViews.add(v);
            if (t.id.equals(want)) pick = t;
        }
        if (!pick.id.equals(shownTab)) show(pick);
        else mark();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (!tabs.isEmpty()) buildTabs();   // re-fit the labels to the new width
    }

    private void mark() {
        for (int i = 0; i < tabs.size(); i++) {
            boolean on = tabs.get(i).id.equals(shownTab);
            tabViews.get(i).setSelected(on);
            if (Look.tabText(this) == null) tabViews.get(i).setTextColor(on ? 0xffffffff : 0xffb0b0b0);
        }
    }

    private void show(Tab t) {
        shownTab = t.id;
        Prefs.setTab(this, t.id);
        mark();
        if ("library".equals(t.id)) {
            list.loadNode("myMusic");
        } else if (t.cmd != null) {
            list.load(t.cmd);
        } else {
            list.items.clear();
            list.base = null;
            String[][] more = {
                    {"Favorites", "favorites", "items", "0", "200", "menu:favorites"},
                    {"Apps", "myapps", "items", "0", "200", "menu:myapps"},
            };
            for (String[] m : more) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("text", m[0]);
                    JSONObject go = new JSONObject();
                    JSONArray c = new JSONArray();
                    JSONObject params = new JSONObject();
                    for (int i = 1; i < m.length; i++) {
                        String tok = m[i];
                        int colon = tok.indexOf(':');
                        if (colon > 0) params.put(tok.substring(0, colon), tok.substring(colon + 1));
                        else if (!tok.matches("\\d+")) c.put(tok);
                    }
                    go.put("cmd", c);
                    go.put("params", params);
                    JSONObject acts = new JSONObject();
                    acts.put("go", go);
                    o.put("actions", acts);
                    list.items.add(Item.fromJive(o));
                } catch (Exception ignored) {}
            }
            list.notifyDataSetChanged();
            status.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------ now playing strip

    private View nowPlayingStrip() {
        LinearLayout s = new LinearLayout(this);
        s.setGravity(Gravity.CENTER_VERTICAL);
        s.setPadding(dp(6), dp(4), dp(6), dp(4));
        s.setBackgroundDrawable(Look.titleBar(this));
        int a = dp(40) * Look.screenScale(this) / 100;
        npArt = new ImageView(this);
        npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(a, a);
        ap.rightMargin = dp(8);
        s.addView(npArt, ap);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        npTitle = text(15, true, 0xffffffff);
        npSub = text(12, false, 0xffcccccc);
        col.addView(npTitle);
        col.addView(npSub);
        s.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        s.setClickable(true);
        s.setFocusable(true);
        s.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, NowPlayingActivity.class));
            }
        });
        npTitle.setText("Now playing");
        return s;
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            if (!resumed) return;
            Rpc.call(HomeActivity.this, Rpc.cmd("status", "-", "1", "tags:alcK"), new Rpc.Callback() {
                public void done(JSONObject r, Exception e) {
                    if (r == null) {
                        npTitle.setText("Not connected");
                        npSub.setText(Prefs.hostPort(HomeActivity.this));
                        return;
                    }
                    JSONArray loop = r.optJSONArray("playlist_loop");
                    JSONObject t = loop == null ? null : loop.optJSONObject(0);
                    String pn = r.optString("player_name", "");
                    if (t == null) {
                        npTitle.setText("Nothing playing");
                        npSub.setText(pn);
                        npArt.setImageDrawable(Look.noArt(HomeActivity.this));
                        npArtUrl = null;
                        return;
                    }
                    npTitle.setText(("play".equals(r.optString("mode")) ? "▶ " : "") + t.optString("title"));
                    String sub = t.optString("artist", "");
                    npSub.setText(sub.length() > 0 ? sub + "  ·  " + pn : pn);
                    String icon = Item.str(t, "artwork_url");
                    if (icon == null) icon = Images.trackCover(Item.str(t, "coverid"));
                    String url = Images.url(HomeActivity.this, icon, dp(48));
                    if (url == null) npArt.setImageDrawable(Look.noArt(HomeActivity.this));
                    else if (!url.equals(npArtUrl)) Images.load(npArt, url, 0);
                    npArtUrl = url;
                }
            });
            h.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        h.removeCallbacks(poll);
        h.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        h.removeCallbacks(poll);
    }

    @Override
    void reload() {
        list.reload();
        h.removeCallbacks(poll);
        if (resumed) h.post(poll);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.getBooleanExtra("reload", false)) {
            tabs.clear();
            recreateTabs();
        }
    }

    private void recreateTabs() {
        tabs.add(new Tab("library", "Library", null));
        tabs.add(new Tab("radio", "Radio", Rpc.cmd("radios", "0", "200", "menu:radio")));
        tabs.add(new Tab("more", "More", null));
        shownTab = null;
        buildTabs();
        findSources();
    }
}
