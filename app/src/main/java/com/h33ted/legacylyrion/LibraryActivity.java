package com.h33ted.legacylyrion;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Stack;

public class LibraryActivity extends Activity {

    private String serverHostPort;
    private String playerId;

    private TextView txtBreadcrumb;
    private EditText editSearch;
    private ListView listLibrary;
    private Button tabArtists, tabAlbums, tabGenres, tabPlaylists, tabRadios;

    private final List<LibNode> nodes = new ArrayList<LibNode>();
    private ArrayAdapter<LibNode> adapter;
    private final Stack<NavState> history = new Stack<NavState>();

    // Filter context for Play All / Add All buttons
    private String currentContextFilter = null;
    private int tabDefaultColor;
    private Button tabApps;
    private boolean appsMode = false; // e.g., "artist_id:12" or "album_id:45"

    private static class LibNode {
        String type; // "artist", "album", "genre", "playlist", "track", "radio"
        String id;
        String title;
        String subtitle;
        String filterTag; // e.g. "album_id:55" or "track_id:199"
        JSONObject raw;   // original item for Apps browsing

        LibNode(String type, String id, String title, String subtitle, String filterTag) {
            this.type = type;
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.filterTag = filterTag;
        }

        @Override
        public String toString() {
            if (subtitle != null && subtitle.length() > 0) {
                return title + "  —  " + subtitle;
            }
            return title;
        }
    }

    private static class NavState {
        String title;
        String contextFilter;
        List<LibNode> items;

        NavState(String title, String contextFilter, List<LibNode> items) {
            this.title = title;
            this.contextFilter = contextFilter;
            this.items = new ArrayList<LibNode>(items);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);

        serverHostPort = getIntent().getStringExtra("server_host_port");
        playerId = getIntent().getStringExtra("player_id");

        ActionBar ab = getActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setTitle("Lyrion Library");
            ab.setSubtitle("Target: " + playerId);
        }

        txtBreadcrumb = (TextView) findViewById(R.id.txtBreadcrumb);
        txtBreadcrumb.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                String log = SlimProtoService.LOG.length() == 0 ? "(empty)" : SlimProtoService.LOG.toString();
                TextView tv = new TextView(LibraryActivity.this);
                tv.setText(log);
                tv.setTextSize(11);
                tv.setPadding(16, 16, 16, 16);
                tv.setTextIsSelectable(true);
                ScrollView sv = new ScrollView(LibraryActivity.this);
                sv.addView(tv);
                new AlertDialog.Builder(LibraryActivity.this).setTitle("Log").setView(sv)
                        .setPositiveButton("Close", null).show();
                return true;
            }
        });
        editSearch = (EditText) findViewById(R.id.editSearch);
        if (getIntent().getBooleanExtra("focus_search", false)) {
            editSearch.requestFocus();
            getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        listLibrary = (ListView) findViewById(R.id.listLibrary);

        tabArtists = (Button) findViewById(R.id.tabArtists);
        tabAlbums = (Button) findViewById(R.id.tabAlbums);
        tabGenres = (Button) findViewById(R.id.tabGenres);
        tabPlaylists = (Button) findViewById(R.id.tabPlaylists);
        tabRadios = (Button) findViewById(R.id.tabRadios);
        tabApps = (Button) findViewById(R.id.tabApps);
        tabDefaultColor = tabAlbums.getTextColors().getDefaultColor();
        Button[] allTabs = new Button[]{tabArtists, tabAlbums, tabGenres, tabPlaylists, tabRadios, tabApps};
        for (Button tb : allTabs) tb.setTextSize(11);
        tabApps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                highlightTab(tabApps);
                history.clear();
                appsMode = true;
                loadApps();
            }
        });

        adapter = new ArrayAdapter<LibNode>(this, android.R.layout.simple_list_item_1, nodes);
        listLibrary.setAdapter(adapter);

        tabArtists.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                highlightTab(tabArtists);
                history.clear();
                appsMode = false;
                loadArtists(null, "All Artists");
            }
        });

        tabAlbums.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                highlightTab(tabAlbums);
                history.clear();
                appsMode = false;
                loadAlbums(null, "All Albums");
            }
        });

        tabGenres.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                highlightTab(tabGenres);
                history.clear();
                appsMode = false;
                loadGenres();
            }
        });

        tabPlaylists.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                highlightTab(tabPlaylists);
                history.clear();
                appsMode = false;
                loadPlaylists();
            }
        });

        tabRadios.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                highlightTab(tabRadios);
                history.clear();
                appsMode = false;
                loadFavoritesOrRadio();
            }
        });

        findViewById(R.id.btnSearch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String q = editSearch.getText().toString().trim();
                if (q.length() > 0) {
                    if (appsMode) {
                        searchInApp(q);
                    } else {
                        pushHistory();
                        searchTracks(q);
                    }
                }
            }
        });

        findViewById(R.id.btnBackLevel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navigateUp();
            }
        });

        findViewById(R.id.btnPlayContext).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                executeContextPlaylistControl("load");
            }
        });

        findViewById(R.id.btnAddContext).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                executeContextPlaylistControl("add");
            }
        });

        listLibrary.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                LibNode node = nodes.get(position);
                if (appsMode) {
                    onAppNodeClick(node, view);
                    return;
                }
                if ("genre".equals(node.type)) {
                    pushHistory();
                    loadArtists("genre_id:" + node.id, node.title);
                } else if ("artist".equals(node.type)) {
                    pushHistory();
                    loadAlbums("artist_id:" + node.id, node.title);
                } else if ("album".equals(node.type)) {
                    pushHistory();
                    loadTracks("album_id:" + node.id, node.title);
                } else if ("playlist".equals(node.type)) {
                    pushHistory();
                    loadPlaylistTracks(node.id, node.title);
                } else if ("track".equals(node.type) || "radio".equals(node.type)) {
                    showHoloTrackActionMenu(view, node, position);
                }
            }
        });

        listLibrary.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                LibNode node = nodes.get(position);
                if (appsMode) {
                    showAppActionMenu(view, node);
                    return true;
                }
                showHoloTrackActionMenu(view, node, position);
                return true;
            }
        });

        loadArtists(null, "All Artists");
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (!history.isEmpty()) {
            navigateUp();
        } else {
            super.onBackPressed();
        }
    }

    private void highlightTab(Button active) {
        Button[] all = new Button[]{tabArtists, tabAlbums, tabGenres, tabPlaylists, tabRadios, tabApps};
        for (Button b : all) {
            b.setTextColor(b == active ? Ui.accent(this) : tabDefaultColor);
        }
    }

    private void pushHistory() {
        if (!nodes.isEmpty()) {
            history.push(new NavState(txtBreadcrumb.getText().toString(), currentContextFilter, nodes));
        }
    }

    private void navigateUp() {
        if (!history.isEmpty()) {
            NavState prev = history.pop();
            txtBreadcrumb.setText(prev.title);
            currentContextFilter = prev.contextFilter;
            nodes.clear();
            nodes.addAll(prev.items);
            adapter.notifyDataSetChanged();
        } else {
            finish();
        }
    }

    private void showHoloTrackActionMenu(View anchor, final LibNode node, final int position) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "Play Now");
        popup.getMenu().add(0, 2, 1, "Play Next (Insert)");
        popup.getMenu().add(0, 3, 2, "Add to End of Queue");
        if ("track".equals(node.type) && currentContextFilter != null) {
            popup.getMenu().add(0, 4, 3, "Play Entire Album from Here");
        }

        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
                if (id == 1) {
                    sendPlaylistControl("load", node.filterTag, -1);
                } else if (id == 2) {
                    sendPlaylistControl("insert", node.filterTag, -1);
                } else if (id == 3) {
                    sendPlaylistControl("add", node.filterTag, -1);
                } else if (id == 4) {
                    sendPlaylistControl("load", currentContextFilter, position);
                }
                return true;
            }
        });
        popup.show();
    }

    private void executeContextPlaylistControl(String cmdType) {
        if (appsMode) {
            Toast.makeText(this, "Long-press an item to play it", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentContextFilter != null && currentContextFilter.length() > 0) {
            sendPlaylistControl(cmdType, currentContextFilter, -1);
        } else if (!nodes.isEmpty() && "track".equals(nodes.get(0).type)) {
            // Queue all visible search tracks
            queueVisibleTracksDirectly(cmdType);
        } else {
            Toast.makeText(this, "Long-press any artist, album, or song to queue it", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendPlaylistControl(final String cmdType, final String filterParam, final int jumpIndex) {
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {
                try {
                    if (filterParam.startsWith("favorites_url:")) {
                        String url = filterParam.substring("favorites_url:".length());
                        JSONArray cmd = new JSONArray();
                        cmd.put("playlist");
                        cmd.put("load".equals(cmdType) ? "play" : cmdType);
                        cmd.put(url);
                        LyrionRpcClient.sendCommand(serverHostPort, playerId, cmd);
                    } else {
                        JSONArray cmd = new JSONArray();
                        cmd.put("playlistcontrol");
                        cmd.put("cmd:" + cmdType);
                        cmd.put(filterParam);
                        LyrionRpcClient.sendCommand(serverHostPort, playerId, cmd);

                        if (jumpIndex > 0) {
                            JSONArray jump = new JSONArray();
                            jump.put("playlist");
                            jump.put("index");
                            jump.put(String.valueOf(jumpIndex));
                            LyrionRpcClient.sendCommand(serverHostPort, playerId, jump);
                        }
                    }
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                if (!ok) {
                    Toast.makeText(LibraryActivity.this, "Command failed", Toast.LENGTH_SHORT).show();
                    return;
                }
                if ("load".equals(cmdType)) {
                    finish(); // Return to Now Playing screen on immediate play
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void queueVisibleTracksDirectly(final String cmdType) {
        final List<String> trackFilters = new ArrayList<String>();
        for (LibNode n : nodes) {
            if ("track".equals(n.type)) trackFilters.add(n.filterTag);
        }
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    for (int i = 0; i < trackFilters.size(); i++) {
                        JSONArray cmd = new JSONArray();
                        cmd.put("playlistcontrol");
                        cmd.put("cmd:" + (i == 0 && "load".equals(cmdType) ? "load" : "add"));
                        cmd.put(trackFilters.get(i));
                        LyrionRpcClient.sendCommand(serverHostPort, playerId, cmd);
                    }
                } catch (Exception ignored) {}
                return null;
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                if ("load".equals(cmdType)) finish();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void loadGenres() {
        currentContextFilter = null;
        txtBreadcrumb.setText("Loading Genres...");
        new AsyncTask<Void, Void, JSONObject>() {
            @Override
            protected JSONObject doInBackground(Void... params) {
                try {
                    JSONArray cmd = new JSONArray();
                    cmd.put("genres");
                    cmd.put("0");
                    cmd.put("300");
                    return LyrionRpcClient.sendCommand(serverHostPort, "", cmd);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(JSONObject res) {
                nodes.clear();
                if (res == null) {
                    txtBreadcrumb.setText("Cannot reach Lyrion at " + serverHostPort);
                }
                if (res != null) {
                    JSONArray loop = res.optJSONArray("genres_loop");
                    if (loop != null) {
                        for (int i = 0; i < loop.length(); i++) {
                            JSONObject o = loop.optJSONObject(i);
                            if (o != null) {
                                String id = o.optString("id");
                                nodes.add(new LibNode("genre", id,
                                        o.optString("genre", "Genre"), "", "genre_id:" + id));
                            }
                        }
                    }
                    txtBreadcrumb.setText("Genres (" + nodes.size() + ")");
                }
                adapter.notifyDataSetChanged();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void loadArtists(final String filter, final String label) {
        currentContextFilter = filter;
        txtBreadcrumb.setText("Loading Artists...");
        new AsyncTask<Void, Void, JSONObject>() {
            @Override
            protected JSONObject doInBackground(Void... params) {
                try {
                    JSONArray cmd = new JSONArray();
                    cmd.put("artists");
                    cmd.put("0");
                    cmd.put("1000");
                    if (filter != null) cmd.put(filter);
                    return LyrionRpcClient.sendCommand(serverHostPort, "", cmd);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(JSONObject res) {
                nodes.clear();
                if (res == null) {
                    txtBreadcrumb.setText("Cannot reach Lyrion at " + serverHostPort);
                }
                if (res != null) {
                    JSONArray loop = res.optJSONArray("artists_loop");
                    if (loop != null) {
                        for (int i = 0; i < loop.length(); i++) {
                            JSONObject o = loop.optJSONObject(i);
                            if (o != null) {
                                String id = o.optString("id");
                                nodes.add(new LibNode("artist", id,
                                        o.optString("artist", "Artist"), "", "artist_id:" + id));
                            }
                        }
                    }
                    txtBreadcrumb.setText(label + " (" + nodes.size() + ")");
                } else {
                    txtBreadcrumb.setText("Cannot reach Lyrion at " + serverHostPort);
                }
                adapter.notifyDataSetChanged();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void loadAlbums(final String filter, final String label) {
        currentContextFilter = filter;
        txtBreadcrumb.setText("Loading Albums...");
        new AsyncTask<Void, Void, JSONObject>() {
            @Override
            protected JSONObject doInBackground(Void... params) {
                try {
                    JSONArray cmd = new JSONArray();
                    cmd.put("albums");
                    cmd.put("0");
                    cmd.put("1000");
                    cmd.put("tags:ajly");
                    if (filter != null) cmd.put(filter);
                    return LyrionRpcClient.sendCommand(serverHostPort, "", cmd);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(JSONObject res) {
                nodes.clear();
                if (res == null) {
                    txtBreadcrumb.setText("Cannot reach Lyrion at " + serverHostPort);
                }
                if (res != null) {
                    JSONArray loop = res.optJSONArray("albums_loop");
                    if (loop != null) {
                        for (int i = 0; i < loop.length(); i++) {
                            JSONObject o = loop.optJSONObject(i);
                            if (o != null) {
                                String id = o.optString("id");
                                String year = o.optString("year", "");
                                String artist = o.optString("artist", "");
                                String sub = (!"0".equals(year) && year.length() > 0)
                                        ? artist + " (" + year + ")" : artist;
                                nodes.add(new LibNode("album", id,
                                        o.optString("album", "Album"), sub, "album_id:" + id));
                            }
                        }
                    }
                    txtBreadcrumb.setText(label + " (" + nodes.size() + " albums)");
                }
                adapter.notifyDataSetChanged();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void loadTracks(final String filter, final String label) {
        currentContextFilter = filter;
        txtBreadcrumb.setText("Loading Tracks...");
        new AsyncTask<Void, Void, JSONObject>() {
            @Override
            protected JSONObject doInBackground(Void... params) {
                try {
                    JSONArray cmd = new JSONArray();
                    cmd.put("titles");
                    cmd.put("0");
                    cmd.put("500");
                    cmd.put(filter);
                    cmd.put("tags:aAltd");
                    cmd.put("sort:tracknum");
                    return LyrionRpcClient.sendCommand(serverHostPort, "", cmd);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(JSONObject res) {
                nodes.clear();
                if (res == null) {
                    txtBreadcrumb.setText("Cannot reach Lyrion at " + serverHostPort);
                }
                if (res != null) {
                    JSONArray loop = res.optJSONArray("titles_loop");
                    if (loop != null) {
                        for (int i = 0; i < loop.length(); i++) {
                            JSONObject o = loop.optJSONObject(i);
                            if (o != null) {
                                String id = o.optString("id");
                                String trackNum = o.optString("tracknum", "");
                                String prefix = trackNum.length() > 0 ? trackNum + ". " : "";
                                nodes.add(new LibNode("track", id,
                                        prefix + o.optString("title", "Track"),
                                        o.optString("artist", ""),
                                        "track_id:" + id));
                            }
                        }
                    }
                    txtBreadcrumb.setText(label + " (" + nodes.size() + " tracks)");
                }
                adapter.notifyDataSetChanged();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void loadPlaylists() {
        currentContextFilter = null;
        txtBreadcrumb.setText("Loading Playlists...");
        new AsyncTask<Void, Void, JSONObject>() {
            @Override
            protected JSONObject doInBackground(Void... params) {
                try {
                    JSONArray cmd = new JSONArray();
                    cmd.put("playlists");
                    cmd.put("0");
                    cmd.put("300");
                    return LyrionRpcClient.sendCommand(serverHostPort, "", cmd);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(JSONObject res) {
                nodes.clear();
                if (res == null) {
                    txtBreadcrumb.setText("Cannot reach Lyrion at " + serverHostPort);
                }
                if (res != null) {
                    JSONArray loop = res.optJSONArray("playlists_loop");
                    if (loop != null) {
                        for (int i = 0; i < loop.length(); i++) {
                            JSONObject o = loop.optJSONObject(i);
                            if (o != null) {
                                String id = o.optString("id");
                                nodes.add(new LibNode("playlist", id,
                                        o.optString("playlist", "Playlist"),
                                        "Saved Playlist", "playlist_id:" + id));
                            }
                        }
                    }
                    txtBreadcrumb.setText("Playlists (" + nodes.size() + ")");
                }
                adapter.notifyDataSetChanged();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void loadPlaylistTracks(final String playlistId, final String label) {
        currentContextFilter = "playlist_id:" + playlistId;
        txtBreadcrumb.setText("Loading Playlist...");
        new AsyncTask<Void, Void, JSONObject>() {
            @Override
            protected JSONObject doInBackground(Void... params) {
                try {
                    JSONArray cmd = new JSONArray();
                    cmd.put("playlists");
                    cmd.put("tracks");
                    cmd.put("0");
                    cmd.put("500");
                    cmd.put("playlist_id:" + playlistId);
                    cmd.put("tags:aAlt");
                    return LyrionRpcClient.sendCommand(serverHostPort, "", cmd);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(JSONObject res) {
                nodes.clear();
                if (res == null) {
                    txtBreadcrumb.setText("Cannot reach Lyrion at " + serverHostPort);
                }
                if (res != null) {
                    JSONArray loop = res.optJSONArray("playlisttracks_loop");
                    if (loop != null) {
                        for (int i = 0; i < loop.length(); i++) {
                            JSONObject o = loop.optJSONObject(i);
                            if (o != null) {
                                String id = o.optString("id");
                                nodes.add(new LibNode("track", id,
                                        o.optString("title", "Track"),
                                        o.optString("artist", ""),
                                        "track_id:" + id));
                            }
                        }
                    }
                    txtBreadcrumb.setText(label + " (" + nodes.size() + " tracks)");
                }
                adapter.notifyDataSetChanged();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void loadFavoritesOrRadio() {
        currentContextFilter = null;
        txtBreadcrumb.setText("Loading Favorites & Radio...");
        new AsyncTask<Void, Void, JSONObject>() {
            @Override
            protected JSONObject doInBackground(Void... params) {
                try {
                    JSONArray cmd = new JSONArray();
                    cmd.put("favorites");
                    cmd.put("items");
                    cmd.put("0");
                    cmd.put("200");
                    cmd.put("want_url:1");
                    return LyrionRpcClient.sendCommand(serverHostPort, "", cmd);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(JSONObject res) {
                nodes.clear();
                if (res == null) {
                    txtBreadcrumb.setText("Cannot reach Lyrion at " + serverHostPort);
                }
                if (res != null) {
                    JSONArray loop = res.optJSONArray("loop_loop");
                    if (loop != null) {
                        for (int i = 0; i < loop.length(); i++) {
                            JSONObject o = loop.optJSONObject(i);
                            if (o != null) {
                                String url = o.optString("url", "");
                                String name = o.optString("name", o.optString("title", "Station"));
                                if (url.length() > 0) {
                                    nodes.add(new LibNode("radio", o.optString("id"),
                                            name, "Stream", "favorites_url:" + url));
                                }
                            }
                        }
                    }
                    txtBreadcrumb.setText("Favorites & Radio (" + nodes.size() + ")");
                }
                adapter.notifyDataSetChanged();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    // ---------------- Apps (Lyrion plugins: radio, podcasts, streaming services) ----------------

    private void loadApps() {
        currentContextFilter = null;
        txtBreadcrumb.setText("Loading Apps...");
        try {
            JSONObject params = new JSONObject();
            params.put("menu", "1");
            runBrowse(new String[]{"myapps", "items"}, params, "Apps");
        } catch (Exception ignored) {}
    }

    private String[] cmdTokens(JSONArray arr) {
        if (arr == null) return new String[0];
        String[] out = new String[arr.length()];
        for (int i = 0; i < out.length; i++) out[i] = arr.optString(i);
        return out;
    }

    private boolean isPlayCommand(String[] cmd) {
        for (String t : cmd) {
            if ("playlist".equals(t)) return true;
        }
        return false;
    }

    private JSONObject substitute(JSONObject params, String term) {
        JSONObject out = new JSONObject();
        if (params == null) return out;
        try {
            Iterator<String> it = params.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = params.optString(k);
                if (term != null) v = v.replace("__TAGGEDINPUT__", term);
                out.put(k, v);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private JSONArray buildCmd(String[] tokens, JSONObject params, boolean paged) {
        JSONArray cmd = new JSONArray();
        for (String t : tokens) cmd.put(t);
        if (paged) {
            cmd.put("0");
            cmd.put("200");
        }
        if (params != null) {
            Iterator<String> it = params.keys();
            while (it.hasNext()) {
                String k = it.next();
                cmd.put(k + ":" + params.optString(k));
            }
        }
        return cmd;
    }

    private void runBrowse(final String[] tokens, final JSONObject params, final String label) {
        txtBreadcrumb.setText("Loading " + label + "...");
        new AsyncTask<Void, Void, JSONObject>() {
            @Override
            protected JSONObject doInBackground(Void... p) {
                try {
                    return LyrionRpcClient.sendCommand(serverHostPort, playerId,
                            buildCmd(tokens, params, true));
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(JSONObject res) {
                nodes.clear();
                if (res == null) {
                    txtBreadcrumb.setText("Cannot reach Lyrion at " + serverHostPort);
                } else {
                    JSONObject baseObj = res.optJSONObject("base");
                    String shown = res.toString();
                    if (shown.length() > 700) shown = shown.substring(0, 700);
                    SlimProtoService.log("browse " + java.util.Arrays.toString(tokens) + " " + params
                            + " -> " + shown);
                    JSONArray loop = res.optJSONArray("item_loop");
                    if (loop != null) {
                        for (int i = 0; i < loop.length(); i++) {
                            JSONObject o = loop.optJSONObject(i);
                            if (o == null) continue;
                            String text = o.optString("text", "");
                            if (text.length() == 0) continue;
                            String title = text;
                            String sub = "";
                            int nl = text.indexOf('\n');
                            if (nl > 0) {
                                title = text.substring(0, nl);
                                sub = text.substring(nl + 1).replace('\n', ' ');
                            }
                            LibNode n = new LibNode("app", String.valueOf(i), title, sub, "");
                            n.raw = resolveItem(o, baseObj);
                            nodes.add(n);
                        }
                    }
                    txtBreadcrumb.setText(label + " (" + nodes.size() + ")");
                    if (nodes.isEmpty()) {
                        txtBreadcrumb.setText(label + " (empty or unsupported)");
                    }
                }
                adapter.notifyDataSetChanged();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void copyInto(JSONObject dst, JSONObject src) {
        if (src == null) return;
        try {
            Iterator<String> it = src.keys();
            while (it.hasNext()) {
                String k = it.next();
                dst.put(k, src.opt(k));
            }
        } catch (Exception ignored) {}
    }

    /**
     * Jive menus define actions once in "base" and put per-item values in item.params
     * (named by action.itemsParams). Merge them into a self-contained item.
     */
    private JSONObject resolveItem(JSONObject item, JSONObject base) {
        try {
            JSONObject out = new JSONObject(item.toString());
            String type = item.optString("type", "");
            String style = item.optString("style", "");
            JSONObject eff = new JSONObject();
            if (!"text".equals(type) && !"itemNoAction".equals(style)) {
                JSONObject own = item.optJSONObject("actions");
                JSONObject baseActs = base == null ? null : base.optJSONObject("actions");
                String[] names = new String[]{"go", "play", "add", "add-hold"};
                for (String name : names) {
                    JSONObject act = own != null ? own.optJSONObject(name) : null;
                    if (act == null && baseActs != null) act = baseActs.optJSONObject(name);
                    if (act == null || act.optJSONArray("cmd") == null) continue;
                    JSONObject r = new JSONObject();
                    r.put("cmd", act.optJSONArray("cmd"));
                    JSONObject params = new JSONObject();
                    copyInto(params, act.optJSONObject("params"));
                    String ip = act.optString("itemsParams", "");
                    if (ip.length() > 0) copyInto(params, item.optJSONObject(ip));
                    r.put("params", params);
                    eff.put(name, r);
                }
            }
            out.put("actions", eff);
            return out;
        } catch (Exception e) {
            return item;
        }
    }

    private JSONObject actionOf(LibNode node, String name) {
        if (node.raw == null) return null;
        JSONObject actions = node.raw.optJSONObject("actions");
        return actions == null ? null : actions.optJSONObject(name);
    }

    private void onAppNodeClick(final LibNode node, View anchor) {
        final JSONObject go = actionOf(node, "go");
        if (go == null) {
            if (actionOf(node, "play") != null) showAppActionMenu(anchor, node);
            return;
        }
        final String[] cmd = cmdTokens(go.optJSONArray("cmd"));
        if (isPlayCommand(cmd)) {
            runAppCommand(go, null, true);
            return;
        }
        if (node.raw.optJSONObject("input") != null) {
            promptForSearch(node.title, new SearchCallback() {
                @Override
                public void onTerm(String term) {
                    pushHistory();
                    runBrowse(cmd, substitute(go.optJSONObject("params"), term), node.title + ": " + term);
                }
            });
            return;
        }
        pushHistory();
        runBrowse(cmd, substitute(go.optJSONObject("params"), null), node.title);
    }

    private interface SearchCallback {
        void onTerm(String term);
    }

    private void promptForSearch(String title, final SearchCallback cb) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Search", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String term = input.getText().toString().trim();
                        if (term.length() > 0) cb.onTerm(term);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** FIND button inside Apps: runs the first search entry of the open app with the typed text. */
    private void searchInApp(final String q) {
        for (final LibNode n : nodes) {
            if (n.raw != null && n.raw.optJSONObject("input") != null) {
                final JSONObject go = actionOf(n, "go");
                if (go == null) continue;
                pushHistory();
                runBrowse(cmdTokens(go.optJSONArray("cmd")), substitute(go.optJSONObject("params"), q),
                        n.title + ": " + q);
                return;
            }
        }
        Toast.makeText(this, "Open an app with a search entry first", Toast.LENGTH_SHORT).show();
    }

    private void showAppActionMenu(View anchor, final LibNode node) {
        JSONObject play = actionOf(node, "play");
        JSONObject add = actionOf(node, "add");
        JSONObject insert = actionOf(node, "add-hold");
        JSONObject go = actionOf(node, "go");
        if (play == null && go != null && isPlayCommand(cmdTokens(go.optJSONArray("cmd")))) play = go;
        if (play == null && add == null && insert == null) {
            Toast.makeText(this, "Nothing to play here", Toast.LENGTH_SHORT).show();
            return;
        }
        final JSONObject fPlay = play, fAdd = add, fInsert = insert;
        PopupMenu popup = new PopupMenu(this, anchor);
        if (fPlay != null) popup.getMenu().add(0, 1, 0, "Play Now");
        if (fInsert != null) popup.getMenu().add(0, 2, 1, "Play Next (Insert)");
        if (fAdd != null) popup.getMenu().add(0, 3, 2, "Add to End of Queue");
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
                if (id == 1) runAppCommand(fPlay, null, true);
                else if (id == 2) runAppCommand(fInsert, null, false);
                else if (id == 3) runAppCommand(fAdd, null, false);
                return true;
            }
        });
        popup.show();
    }

    private void runAppCommand(final JSONObject action, final String term, final boolean finishOnOk) {
        final JSONArray cmd = buildCmd(cmdTokens(action.optJSONArray("cmd")),
                substitute(action.optJSONObject("params"), term), false);
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... p) {
                try {
                    LyrionRpcClient.sendCommand(serverHostPort, playerId, cmd);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                if (!ok) {
                    Toast.makeText(LibraryActivity.this, "Command failed", Toast.LENGTH_SHORT).show();
                } else if (finishOnOk) {
                    finish();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void searchTracks(final String query) {
        currentContextFilter = null;
        txtBreadcrumb.setText("Searching '" + query + "'...");
        new AsyncTask<Void, Void, JSONObject>() {
            @Override
            protected JSONObject doInBackground(Void... params) {
                try {
                    JSONArray cmd = new JSONArray();
                    cmd.put("titles");
                    cmd.put("0");
                    cmd.put("250");
                    cmd.put("search:" + query);
                    cmd.put("tags:aAlt");
                    return LyrionRpcClient.sendCommand(serverHostPort, "", cmd);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(JSONObject res) {
                nodes.clear();
                if (res == null) {
                    txtBreadcrumb.setText("Cannot reach Lyrion at " + serverHostPort);
                }
                if (res != null) {
                    JSONArray loop = res.optJSONArray("titles_loop");
                    if (loop != null) {
                        for (int i = 0; i < loop.length(); i++) {
                            JSONObject o = loop.optJSONObject(i);
                            if (o != null) {
                                String id = o.optString("id");
                                nodes.add(new LibNode("track", id,
                                        o.optString("title", "Track"),
                                        o.optString("artist", "") + " • " + o.optString("album", ""),
                                        "track_id:" + id));
                            }
                        }
                    }
                    txtBreadcrumb.setText("Search '" + query + "' (" + nodes.size() + " tracks)");
                }
                adapter.notifyDataSetChanged();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
}
