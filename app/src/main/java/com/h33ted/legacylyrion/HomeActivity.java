package com.h33ted.legacylyrion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Launcher screen. Spotify first: a search box, then Spotty's "Made for you" mixes, your
 * playlists and the rest of Spotty's menu. Typing searches Spotify and shows grouped results.
 * With "Local library only" switched on in Settings, the same screen searches and shows the
 * music stored on the Lyrion server instead.
 */
public class HomeActivity extends Activity {

    private static final int MENU_SETTINGS = 1, MENU_NOW = 2, MENU_LIBRARY = 3, MENU_FX = 4, MENU_FIND = 5, MENU_UPDATE = 6;

    private EditText searchBox;
    private LinearLayout content;
    private ScrollView scroll;
    private ProgressBar progress;
    private MiniPlayer mini;
    private final Handler handler = new Handler();

    private int generation = 0;
    private int pending = 0;
    private String hp, pid;
    private boolean localOnly;

    // Spotty menu, fetched once per server/player
    private JSONObject spottyTop;
    private Item searchItem;
    private JSONObject searchBase;

    private final Runnable searchRunnable = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);
        if (!Prefs.setupDone(this)) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_home);
        Skin.decorate(this, "LegacyLyrion", null, false);
        searchBox = Skin.swap((EditText) findViewById(R.id.searchBox));
        content = (LinearLayout) findViewById(R.id.content);
        scroll = (ScrollView) findViewById(R.id.scroll);
        progress = (ProgressBar) findViewById(R.id.progress);
        mini = new MiniPlayer(this);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                handler.removeCallbacks(searchRunnable);
                handler.postDelayed(searchRunnable, 500);
            }
        });
        searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    handler.removeCallbacks(searchRunnable);
                    hideKeyboard();
                    refresh();
                    return true;
                }
                return false;
            }
        });

        loadPrefs();
        startSlimProto();
        refresh();
        Updater.checkDaily(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mini == null) return;
        loadPrefs();
        mini.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mini != null) mini.stop();
    }

    @Override
    public void onBackPressed() {
        if (searchBox.getText().length() > 0) {
            searchBox.setText("");
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (mini == null) return false;
        menu.add(0, MENU_NOW, 0, "Now playing")
                .setIcon(new SvgDrawable(this, R.raw.ic_now_playing,
                        Ui.themeColor(this, android.R.attr.actionMenuTextColor, 0xFFFFFFFF), 32))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, MENU_LIBRARY, 1, "Library browser");
        menu.add(0, MENU_FX, 2, "Sound effects");
        menu.add(0, MENU_SETTINGS, 3, "Settings").setIcon(android.R.drawable.ic_menu_preferences);
        menu.add(0, MENU_FIND, 4, "Find server");
        menu.add(0, MENU_UPDATE, 5, "Check for updates");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_SETTINGS: showSettings(); return true;
            case MENU_NOW: Nav.nowPlaying(this); return true;
            case MENU_LIBRARY: Nav.library(this); return true;
            case MENU_FX: Nav.soundEffects(this); return true;
            case MENU_FIND: startActivity(new Intent(this, SetupActivity.class)); return true;
            case MENU_UPDATE: Updater.check(this, true); return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------ plumbing

    private void loadPrefs() {
        hp = Prefs.hostPort(this);
        pid = Prefs.player(this);
        localOnly = Prefs.localOnly(this);
        searchBox.setHint(localOnly ? "Search your library" : "What do you want to listen to?");
        Skin.setSubtitle(this, localOnly ? "Local library" : "Spotify");
    }

    private void startSlimProto() {
        if (!Prefs.phonePlayer(this)) {
            stopService(new Intent(this, SlimProtoService.class));
            return;
        }
        int port = 9000;
        try {
            port = Integer.parseInt(Prefs.port(this));
        } catch (Exception ignored) {}
        Intent svc = new Intent(this, SlimProtoService.class);
        svc.putExtra("server_ip", Prefs.host(this));
        svc.putExtra("server_http_port", port);
        startService(svc);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
    }

    private void refresh() {
        String q = searchBox.getText().toString().trim();
        generation++;
        pending = 0;
        content.removeAllViews();
        scroll.scrollTo(0, 0);
        progress.setVisibility(View.GONE);
        if (q.length() == 0) {
            if (localOnly) localHome(generation);
            else spottyHome(generation);
        } else {
            if (localOnly) localSearch(generation, q);
            else spottySearch(generation, q);
        }
    }

    private Ui.Section addSection(String title, View.OnClickListener seeAll, boolean shelf) {
        Ui.Section s = Ui.section(this, title, seeAll, shelf);
        content.addView(s.root);
        return s;
    }

    private void showMessage(String msg) {
        content.addView(Ui.message(this, msg));
    }

    private void begin() {
        pending++;
        progress.setVisibility(View.VISIBLE);
    }

    /** Returns false if the screen moved on (new search) and the result must be dropped. */
    private boolean end(int gen) {
        if (gen != generation) return false;
        if (--pending <= 0) progress.setVisibility(View.GONE);
        return true;
    }

    private void call(final int gen, JSONArray cmd, final Rpc.Callback cb) {
        begin();
        Rpc.call(hp, pid, cmd, new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                if (!end(gen)) return;
                cb.done(r, e);
            }
        });
    }

    private void addCard(Ui.Section s, final Item it, final JSONObject base, boolean circle) {
        View card = Ui.card(this, hp, it, circle);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Nav.open(HomeActivity.this, it, base);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Nav.itemMenu(HomeActivity.this, it, base);
                return true;
            }
        });
        s.body.addView(card);
    }

    private void addRow(Ui.Section s, final Item it, final JSONObject base) {
        View row = Ui.makeRow(this);
        Ui.selectable(row);
        Ui.bindRow(row, hp, it);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Nav.open(HomeActivity.this, it, base);
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Nav.itemMenu(HomeActivity.this, it, base);
                return true;
            }
        });
        s.body.addView(row);
    }

    private void fillTopResult(Ui.Section s, final Item it, final JSONObject base) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 16);
        box.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        Ui.selectable(box);
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        boolean circle = it.isArtist();
        Ui.placeholder(iv, circle);
        box.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 92)));
        ImageLoader.load(iv, Ui.art(hp, it.icon, 300), circle);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 16), 0, 0, 0);
        TextView name = Ui.text(this, 24, true, true);
        name.setText(it.title);
        TextView kind = Ui.text(this, 14, false, false);
        kind.setText(it.niceSubtitle().length() > 0 ? it.niceSubtitle() : "Artist");
        col.addView(name);
        col.addView(kind);
        box.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Nav.open(HomeActivity.this, it, base);
            }
        });
        s.body.addView(box);
        s.show();
    }

    private static List<Item> items(JSONObject r) {
        List<Item> out = new ArrayList<Item>();
        JSONArray loop = r == null ? null : r.optJSONArray("item_loop");
        if (loop == null) return out;
        for (int i = 0; i < loop.length(); i++) {
            JSONObject o = loop.optJSONObject(i);
            if (o != null) {
                Item it = Item.fromJive(o);
                if (it.title.length() > 0) out.add(it);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Spotify (Spotty)

    private interface Ready {
        void ready(boolean ok);
    }

    private void ensureSpotty(final int gen, final Ready r) {
        if (spottyTop != null) {
            r.ready(true);
            return;
        }
        call(gen, Rpc.cmd("spotty", "items", "0", "100", "menu:spotty"), new Rpc.Callback() {
            @Override
            public void done(JSONObject res, Exception e) {
                if (res != null && res.optJSONArray("item_loop") != null) spottyTop = res;
                r.ready(spottyTop != null);
            }
        });
    }

    private Item findTop(String title) {
        for (Item it : items(spottyTop)) if (it.title.equalsIgnoreCase(title)) return it;
        return null;
    }

    private int spottyRetries = 0;

    /**
     * On first run the phone's own player may not be registered with Lyrion yet, and Spotty
     * rejects unknown players. Wait for it a few times before showing an error.
     */
    private boolean retrySpotty(final int gen) {
        if (spottyRetries >= 5) return false;
        spottyRetries++;
        showMessage("Connecting to Lyrion\u2026");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen == generation) refresh();
            }
        }, 2500);
        return true;
    }

    private void spottyUnavailable() {
        showMessage("Couldn't load Spotty from " + hp + ".\n\nCheck the server and player in Settings, "
                + "make sure the Spotty plugin is installed, or switch on \"Local library only\".");
    }

    private void spottyHome(final int gen) {
        ensureSpotty(gen, new Ready() {
            @Override
            public void ready(boolean ok) {
                if (gen != generation) return;
                if (!ok) {
                    if (!retrySpotty(gen)) spottyUnavailable();
                    return;
                }
                spottyRetries = 0;
                final JSONObject topBase = spottyTop.optJSONObject("base");
                Item home = findTop("Home");
                Item playlists = findTop("Playlists");
                if (home != null) shelf(gen, addSection("Made for you", seeAll(home, topBase), true), home, topBase, 30, false);
                if (playlists != null) shelf(gen, addSection("Your playlists", seeAll(playlists, topBase), true), playlists, topBase, 20, false);
                Ui.Section browse = addSection("Browse", null, false);
                for (Item it : items(spottyTop)) {
                    if (it.title.equalsIgnoreCase("Home") || it.title.equalsIgnoreCase("Search")) continue;
                    addRow(browse, it, topBase);
                }
            }
        });
    }

    private View.OnClickListener seeAll(final Item folder, final JSONObject base) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Nav.open(HomeActivity.this, folder, base);
            }
        };
    }

    /** Fills a horizontal shelf with the first {@code count} children of a Spotty folder. */
    private void shelf(final int gen, final Ui.Section s, Item folder, JSONObject base, int count, final boolean circle) {
        JSONArray cmd = Jive.command(folder, base, "go", count);
        if (cmd == null) {
            s.hide();
            return;
        }
        call(gen, cmd, new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                List<Item> list = items(r);
                JSONObject b = r == null ? null : r.optJSONObject("base");
                for (Item it : list) addCard(s, it, b, circle || it.isArtist());
                if (list.isEmpty()) s.hide();
            }
        });
    }

    private void ensureSearch(final int gen, final Ready r) {
        if (searchItem != null) {
            r.ready(true);
            return;
        }
        ensureSpotty(gen, new Ready() {
            @Override
            public void ready(boolean ok) {
                final Item search = ok ? findTop("Search") : null;
                JSONArray cmd = search == null ? null : Jive.command(search, spottyTop.optJSONObject("base"), "go", 20);
                if (cmd == null) {
                    r.ready(false);
                    return;
                }
                call(gen, cmd, new Rpc.Callback() {
                    @Override
                    public void done(JSONObject res, Exception e) {
                        for (Item it : items(res)) {
                            if ("search".equals(it.type)) {
                                searchItem = it;
                                searchBase = res.optJSONObject("base");
                                break;
                            }
                        }
                        r.ready(searchItem != null);
                    }
                });
            }
        });
    }

    private void spottySearch(final int gen, final String q) {
        final Ui.Section top = addSection("Top result", null, false);
        final Ui.Section songs = addSection("Songs", null, false);
        final Ui.Section artists = addSection("Artists", null, true);
        final Ui.Section albums = addSection("Albums", null, true);
        final Ui.Section playlists = addSection("Playlists", null, true);
        top.hide();
        songs.hide();
        artists.hide();
        albums.hide();
        playlists.hide();

        ensureSearch(gen, new Ready() {
            @Override
            public void ready(boolean ok) {
                if (gen != generation) return;
                final JSONArray cmd;
                if (ok) {
                    cmd = Jive.withInput(Jive.command(searchItem, searchBase, "go", 60), q);
                } else if (spottyTop != null) {
                    cmd = Rpc.cmd("spotty", "items", "0", "60", "menu:spotty", "item_id:1.0", "search:" + q);
                } else {
                    if (!retrySpotty(gen)) spottyUnavailable();
                    return;
                }
                call(gen, cmd, new Rpc.Callback() {
                    @Override
                    public void done(JSONObject r, Exception e) {
                        if (r == null) {
                            showMessage("Search failed" + (e != null ? ": " + e.getMessage() : ""));
                            return;
                        }
                        final JSONObject base = r.optJSONObject("base");
                        List<Item> all = items(r);
                        int nTracks = 0;
                        Item fArtists = null, fAlbums = null, fPlaylists = null;
                        for (Item it : all) {
                            if (it.isTrack()) {
                                if (nTracks < 5) addRow(songs, it, base);
                                nTracks++;
                            } else if (it.title.equalsIgnoreCase("Artists")) fArtists = it;
                            else if (it.title.equalsIgnoreCase("Albums")) fAlbums = it;
                            else if (it.title.equalsIgnoreCase("Playlists")) fPlaylists = it;
                        }
                        if (nTracks > 0) {
                            songs.show();
                            if (nTracks > 5) addSeeAll(songs, "See all songs", cmd, true);
                        }
                        if (fArtists != null) results(gen, artists, top, fArtists, base, true);
                        if (fAlbums != null) results(gen, albums, null, fAlbums, base, false);
                        if (fPlaylists != null) results(gen, playlists, null, fPlaylists, base, false);
                        if (nTracks == 0 && fArtists == null && fAlbums == null && fPlaylists == null) {
                            showMessage("No results for “" + q + "”");
                        }
                    }
                });
            }
        });
    }

    private void addSeeAll(Ui.Section s, String label, final JSONArray cmd, final boolean tracksOnly) {
        final String title = searchBox.getText().toString().trim();
        TextView more = Ui.text(this, 14, false, true);
        more.setText(label);
        more.setTextColor(Ui.accent(this));
        more.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        Ui.selectable(more);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JSONArray full = new JSONArray();
                for (int i = 0; i < cmd.length(); i++) full.put(i == 3 ? "200" : cmd.optString(i));
                Nav.browse(HomeActivity.this, title, "Songs", null, full, null, null, false, tracksOnly);
            }
        });
        s.body.addView(more);
    }

    /** Loads one result folder (Artists/Albums/Playlists) into a shelf; the first artist also becomes the top result. */
    private void results(final int gen, final Ui.Section s, final Ui.Section top, final Item folder,
                         final JSONObject base, final boolean circle) {
        JSONArray cmd = Jive.command(folder, base, "go", 15);
        if (cmd == null) return;
        call(gen, cmd, new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                List<Item> list = items(r);
                if (list.isEmpty()) return;
                JSONObject b = r.optJSONObject("base");
                for (Item it : list) addCard(s, it, b, circle);
                s.show();
                if (top != null) fillTopResult(top, list.get(0), b);
                s.setSeeAll(seeAll(folder, base));
            }
        });
    }

    // ------------------------------------------------------------------ local library

    private void localHome(int gen) {
        Ui.Section recent = addSection("Recently added", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Nav.browseLocal(HomeActivity.this, "Recently added", "albums_sort", "new");
            }
        }, true);
        Ui.Section random = addSection("Rediscover", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Nav.browseLocal(HomeActivity.this, "Rediscover", "albums_sort", "random");
            }
        }, true);
        albumShelf(gen, recent, Rpc.cmd("albums", "0", "20", "sort:new", "tags:laj"));
        albumShelf(gen, random, Rpc.cmd("albums", "0", "20", "sort:random", "tags:laj"));

        Ui.Section lib = addSection("Your library", null, false);
        String[] names = {"Artists", "Albums", "Genres", "Playlists"};
        for (String n : names) {
            TextView t = Ui.text(this, 17, true, false);
            t.setText(n);
            t.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
            Ui.selectable(t);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Nav.library(HomeActivity.this);
                }
            });
            lib.body.addView(t);
        }
    }

    private void albumShelf(int gen, final Ui.Section s, JSONArray cmd) {
        call(gen, cmd, new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                List<Item> list = localAlbums(r);
                for (Item it : list) addCard(s, it, null, false);
                if (list.isEmpty()) s.hide();
            }
        });
    }

    static List<Item> localAlbums(JSONObject r) {
        List<Item> out = new ArrayList<Item>();
        JSONArray loop = r == null ? null : r.optJSONArray("albums_loop");
        if (loop == null) return out;
        for (int i = 0; i < loop.length(); i++) {
            JSONObject o = loop.optJSONObject(i);
            if (o == null) continue;
            String sub = o.optString("artist", "");
            if (sub.length() == 0 && o.has("year") && !"0".equals(o.optString("year"))) sub = o.optString("year");
            out.add(Item.local("album", o.optString("id"), o.optString("album", ""), sub,
                    Ui.localCover(Item.str(o, "artwork_track_id"))));
        }
        return out;
    }

    static List<Item> localTracks(JSONObject r) {
        List<Item> out = new ArrayList<Item>();
        JSONArray loop = r == null ? null : r.optJSONArray("titles_loop");
        if (loop == null) return out;
        for (int i = 0; i < loop.length(); i++) {
            JSONObject o = loop.optJSONObject(i);
            if (o == null) continue;
            String sub = o.optString("artist", "");
            String album = o.optString("album", "");
            if (album.length() > 0) sub = sub.length() > 0 ? sub + " • " + album : album;
            String cover = Item.str(o, "coverid");
            out.add(Item.local("track", o.optString("id"), o.optString("title", ""), sub,
                    Ui.localCover(cover != null ? cover : o.optString("id"))));
        }
        return out;
    }

    static List<Item> localArtists(JSONObject r) {
        List<Item> out = new ArrayList<Item>();
        JSONArray loop = r == null ? null : r.optJSONArray("artists_loop");
        if (loop == null) return out;
        for (int i = 0; i < loop.length(); i++) {
            JSONObject o = loop.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id");
            // artist photos exist only with the Music & Artist Information plugin; otherwise a grey circle
            out.add(Item.local("artist", id, o.optString("artist", ""), "Artist",
                    "/imageproxy/mai/artist/" + id + "/image.png"));
        }
        return out;
    }

    private void localSearch(int gen, final String q) {
        final Ui.Section top = addSection("Top result", null, false);
        final Ui.Section songs = addSection("Songs", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Nav.browseLocal(HomeActivity.this, q, "search_tracks", q);
            }
        }, false);
        final Ui.Section artists = addSection("Artists", null, true);
        final Ui.Section albums = addSection("Albums", null, true);
        top.hide();
        songs.hide();
        artists.hide();
        albums.hide();
        final int[] found = {0, 0};

        call(gen, Rpc.cmd("artists", "0", "15", "search:" + q), new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                List<Item> list = localArtists(r);
                for (Item it : list) addCard(artists, it, null, true);
                if (!list.isEmpty()) {
                    artists.show();
                    fillTopResult(top, list.get(0), null);
                }
                localDone(found, list.size(), q);
            }
        });
        call(gen, Rpc.cmd("titles", "0", "5", "search:" + q, "tags:alc"), new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                List<Item> list = localTracks(r);
                for (Item it : list) addRow(songs, it, null);
                if (!list.isEmpty()) songs.show();
                localDone(found, list.size(), q);
            }
        });
        call(gen, Rpc.cmd("albums", "0", "15", "search:" + q, "tags:laj"), new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                List<Item> list = localAlbums(r);
                for (Item it : list) addCard(albums, it, null, false);
                if (!list.isEmpty()) albums.show();
                localDone(found, list.size(), q);
            }
        });
    }

    private void localDone(int[] found, int n, String q) {
        found[0]++;
        found[1] += n;
        if (found[0] == 3 && found[1] == 0) showMessage("Nothing in your library matches “" + q + "”");
    }

    // ------------------------------------------------------------------ settings

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);

        final EditText host = Skin.editText(this);
        host.setHint("Server IP address");
        host.setSingleLine(true);
        host.setText(Prefs.host(this));
        final EditText port = Skin.editText(this);
        port.setHint("Port (9000)");
        port.setSingleLine(true);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(Prefs.port(this));
        TextView pl = Ui.text(this, 14, false, false);
        pl.setText("Control player");
        pl.setPadding(0, Ui.dp(this, 12), 0, 0);
        final Spinner players = new Spinner(this);
        final List<String> ids = new ArrayList<String>();
        final ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item,
                new ArrayList<String>());
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        players.setAdapter(ad);
        final CheckBox phone = new CheckBox(this);
        phone.setText("Use this phone as a player");
        phone.setChecked(Prefs.phonePlayer(this));
        final CheckBox local = new CheckBox(this);
        local.setText("Local library only (hide Spotify)");
        local.setChecked(Prefs.localOnly(this));

        box.addView(host);
        box.addView(port);
        box.addView(pl);
        box.addView(players);
        TextView lookLabel = Ui.text(this, 14, false, false);
        lookLabel.setText("Look");
        lookLabel.setPadding(0, Ui.dp(this, 12), 0, 0);
        final Spinner look = new Spinner(this);
        // Maker's skin first (default), then plain Holo on every phone.
        final boolean htc = Skin.senseAvailable(this);
        final boolean lge = Skin.lgAvailable(this);
        final String[] lookKeys = htc ? new String[]{"auto", "light", "holo_light", "holo_dark"}
                : lge ? new String[]{"auto", "holo_light", "holo_dark"}
                : new String[]{"auto", "light", "holo_light", "holo_dark"};
        ArrayAdapter<String> lookAd = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item,
                htc ? new String[]{"Sense dark", "Sense light", "Holo light", "Holo dark"}
                        : lge ? new String[]{"LG White", "Holo light", "Holo dark"}
                        : new String[]{"Phone default", "Phone light", "Holo light", "Holo dark"});
        lookAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        look.setAdapter(lookAd);
        final String oldLook = Skin.look(this);
        for (int i = 0; i < lookKeys.length; i++) if (lookKeys[i].equals(oldLook)) look.setSelection(i);

        box.addView(phone);
        box.addView(local);
        box.addView(lookLabel);
        box.addView(look);

        ids.add(SlimProtoService.localMac(HomeActivity.this));
        ad.add("This phone (" + SlimProtoService.deviceName() + ")");
        Rpc.call(hp, "", Rpc.cmd("players", "0", "99"), new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                JSONArray loop = r == null ? null : r.optJSONArray("players_loop");
                if (loop == null) return;
                for (int i = 0; i < loop.length(); i++) {
                    JSONObject o = loop.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("playerid");
                    if (SlimProtoService.localMac(HomeActivity.this).equalsIgnoreCase(id)) continue;
                    ids.add(id);
                    ad.add(o.optString("name", id));
                }
                ad.notifyDataSetChanged();
                for (int i = 0; i < ids.size(); i++) if (ids.get(i).equalsIgnoreCase(pid)) players.setSelection(i);
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int sel = players.getSelectedItemPosition();
                        Prefs.get(HomeActivity.this).edit()
                                .putString("lms_host", host.getText().toString().trim())
                                .putString("lms_port", port.getText().toString().trim())
                                .putString("player_id", sel >= 0 && sel < ids.size() ? ids.get(sel) : pid)
                                .putBoolean("local_only", local.isChecked())
                                .putBoolean("phone_player", phone.isChecked())
                                .putString("look", lookKeys[Math.max(0, look.getSelectedItemPosition())])
                                .commit();
                        if (!oldLook.equals(lookKeys[Math.max(0, look.getSelectedItemPosition())])) {
                            recreate();
                            return;
                        }
                        spottyTop = null;
                        searchItem = null;
                        searchBase = null;
                        loadPrefs();
                        startSlimProto();
                        refresh();
                        mini.start();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
