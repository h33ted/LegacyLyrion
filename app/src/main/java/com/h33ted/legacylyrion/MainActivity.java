package com.h33ted.legacylyrion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Now Playing: big artwork, title, seek, transport, volume; the queue swaps in over the artwork. */
public class MainActivity extends Activity {

    private static final int M_QUEUE = 1, M_PLAYER = 2, M_FX = 3, M_CLEAR = 4, M_LOG = 5;

    private ImageView cover;
    private ListView queueList;
    private TextView title, artist, elapsedTxt, durationTxt;
    private SeekBar seek, volume;
    private GlyphView btnPlay, btnShuffle, btnRepeat, btnQueue;

    private final Handler h = new Handler();
    private String hp, pid;
    private boolean running, inFlight, playing, seeking, volTouch, queueVisible;
    private double elapsed, duration;
    private int shuffle, repeat, curIndex = -1, trackCount = -1, queueLoadedFor = -2;
    private String lastArt = "";
    private String shownPlayer;
    private final List<Item> queue = new ArrayList<Item>();
    private BaseAdapter queueAdapter;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            poll();
            if (running) h.postDelayed(this, 2500);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (playing && !seeking && duration > 0) {
                elapsed = Math.min(duration, elapsed + 1);
                showTime();
            }
            tintFromArtwork();
            if (running) h.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ Xperia (Walkman) look

    /**
     * In the spirit of Sony's 2012 Walkman player: light title, thin rings around previous/next as
     * well as play, thin white seek bars with a round thumb, and a background tinted from the
     * album art.
     */
    private void walkmanLook(GlyphView prev, GlyphView next) {
        android.graphics.Typeface light = android.graphics.Typeface.create("sans-serif-light",
                android.graphics.Typeface.NORMAL);
        title.setTypeface(light);
        title.setTextSize(28);
        artist.setTypeface(light);
        artist.setTextSize(18);
        artist.setTextColor(Ui.textPrimary(this));

        prev.setRing(true);
        next.setRing(true);
        thinSeekBar(seek);
        thinSeekBar(volume);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xff000000));
        // transparent title bar: the artwork-tinted window background runs up behind it,
        // with a thin line along its bottom edge
        if (getActionBar() != null) {
            final int line = Math.max(1, Ui.dp(this, 1));
            final android.graphics.Paint lp = new android.graphics.Paint();
            lp.setColor(0x40ffffff);
            getActionBar().setBackgroundDrawable(new android.graphics.drawable.Drawable() {
                @Override
                public void draw(android.graphics.Canvas c) {
                    android.graphics.Rect b = getBounds();
                    c.drawRect(b.left, b.bottom - line, b.right, b.bottom, lp);
                }

                @Override
                public void setAlpha(int alpha) {}

                @Override
                public void setColorFilter(android.graphics.ColorFilter cf) {}

                @Override
                public int getOpacity() {
                    return android.graphics.PixelFormat.TRANSLUCENT;
                }
            });
        }
        View content = findViewById(android.R.id.content);
        if (content instanceof android.widget.FrameLayout) {
            ((android.widget.FrameLayout) content).setForeground(null);   // the shadow line under the bar
        }
    }

    /** Walkman-style seek bar: a 2dp line, white where played, with a round white thumb. */
    private void thinSeekBar(SeekBar s) {
        android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
        track.setColor(0x44ffffff);
        android.graphics.drawable.GradientDrawable done = new android.graphics.drawable.GradientDrawable();
        done.setColor(0xffffffff);
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(done,
                android.view.Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{track, clip});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        int p = s.getProgress();
        s.setProgressDrawable(ld);
        int line = Ui.dp(this, 2);
        try {   // ProgressBar keeps its bar height in private fields on Android 4.x
            for (String f : new String[]{"mMinHeight", "mMaxHeight"}) {
                java.lang.reflect.Field fl = android.widget.ProgressBar.class.getDeclaredField(f);
                fl.setAccessible(true);
                fl.setInt(s, line);
            }
        } catch (Throwable ignored) {}
        android.graphics.drawable.GradientDrawable thumb = new android.graphics.drawable.GradientDrawable();
        thumb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        thumb.setColor(0xffffffff);
        int t = Ui.dp(this, 14);
        thumb.setSize(t, t);
        s.setThumb(thumb);
        s.setThumbOffset(t / 2);
        s.setProgress(0);
        s.setProgress(p);
        s.requestLayout();
    }

    private Object tinted;

    /** Background: the album art's average colour, darkened, fading to black (as Walkman does). */
    private void tintFromArtwork() {
        if (!Skin.sony(this)) return;
        android.graphics.drawable.Drawable d = cover.getDrawable();
        if (d == tinted) return;
        tinted = d;
        int top = 0xff101010;
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            android.graphics.Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) {
                try {
                    int c = android.graphics.Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0);
                    int r = ((c >> 16) & 255) * 45 / 100, g = ((c >> 8) & 255) * 45 / 100, bl = (c & 255) * 45 / 100;
                    top = 0xff000000 | (r << 16) | (g << 8) | bl;
                } catch (Throwable ignored) {}
            }
        }
        getWindow().setBackgroundDrawable(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, 0xff000000}));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Skin.decorate(this, "Now playing", null, true);
        cover = (ImageView) findViewById(R.id.cover);
        queueList = (ListView) findViewById(R.id.queueList);
        title = (TextView) findViewById(R.id.title);
        artist = (TextView) findViewById(R.id.artist);
        elapsedTxt = (TextView) findViewById(R.id.elapsed);
        durationTxt = (TextView) findViewById(R.id.duration);
        seek = (SeekBar) findViewById(R.id.seek);
        volume = (SeekBar) findViewById(R.id.volume);
        btnPlay = (GlyphView) findViewById(R.id.btnPlay);
        btnShuffle = (GlyphView) findViewById(R.id.btnShuffle);
        btnRepeat = (GlyphView) findViewById(R.id.btnRepeat);
        btnQueue = (GlyphView) findViewById(R.id.btnQueue);
        GlyphView prev = (GlyphView) findViewById(R.id.btnPrev);
        GlyphView next = (GlyphView) findViewById(R.id.btnNext);

        btnPlay.setGlyph(GlyphView.PLAY);
        btnPlay.setRing(true);
        prev.setGlyph(GlyphView.PREV);
        next.setGlyph(GlyphView.NEXT);
        btnShuffle.setGlyph(GlyphView.SHUFFLE);
        btnRepeat.setGlyph(GlyphView.REPEAT);
        btnQueue.setGlyph(GlyphView.QUEUE);
        ((GlyphView) findViewById(R.id.volIcon)).setGlyph(GlyphView.VOLUME);
        ((GlyphView) findViewById(R.id.volIcon)).setClickable(false);
        if (Skin.sony(this)) walkmanLook(prev, next);
        title.setSelected(true); // marquee

        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playing = !playing;
                btnPlay.setGlyph(playing ? GlyphView.PAUSE : GlyphView.PLAY);
                send(playing ? Rpc.cmd("play") : Rpc.cmd("pause", "1"));
            }
        });
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send(Rpc.cmd("playlist", "index", "-1"));
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send(Rpc.cmd("playlist", "index", "+1"));
            }
        });
        btnShuffle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shuffle = shuffle == 0 ? 1 : 0;
                showModes();
                send(Rpc.cmd("playlist", "shuffle", String.valueOf(shuffle)));
                Toast.makeText(MainActivity.this, shuffle == 0 ? "Shuffle off" : "Shuffle on", Toast.LENGTH_SHORT).show();
            }
        });
        btnRepeat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                repeat = repeat == 0 ? 2 : (repeat == 2 ? 1 : 0); // off -> all -> one -> off
                showModes();
                send(Rpc.cmd("playlist", "repeat", String.valueOf(repeat)));
                Toast.makeText(MainActivity.this, repeat == 0 ? "Repeat off" : repeat == 2 ? "Repeat all" : "Repeat one",
                        Toast.LENGTH_SHORT).show();
            }
        });
        btnQueue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setQueueVisible(!queueVisible);
            }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) elapsedTxt.setText(fmt(p));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                elapsed = s.getProgress();
                send(Rpc.cmd("time", String.valueOf(s.getProgress())));
            }
        });
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {}

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                volTouch = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                volTouch = false;
                send(Rpc.cmd("mixer", "volume", String.valueOf(s.getProgress())));
            }
        });

        queueAdapter = new BaseAdapter() {
            @Override
            public int getCount() { return queue.size(); }

            @Override
            public Object getItem(int i) { return queue.get(i); }

            @Override
            public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View v, ViewGroup parent) {
                if (v == null) v = Ui.makeRow(MainActivity.this);
                Ui.bindRow(v, hp, queue.get(i));
                Ui.Row r = (Ui.Row) v.getTag();
                boolean cur = i == curIndex;
                r.title.setTextColor(cur ? Ui.accent(MainActivity.this) : Ui.textPrimary(MainActivity.this));
                r.title.setTypeface(cur ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                return v;
            }
        };
        queueList.setAdapter(queueAdapter);
        queueList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                send(Rpc.cmd("playlist", "index", String.valueOf(pos)));
            }
        });
        queueList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, final int pos, long id) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(queue.get(pos).title)
                        .setItems(new String[]{"Play", "Remove from queue"}, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                send(which == 0 ? Rpc.cmd("playlist", "index", String.valueOf(pos))
                                        : Rpc.cmd("playlist", "delete", String.valueOf(pos)));
                                queueLoadedFor = -2;
                            }
                        })
                        .show();
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        hp = Prefs.hostPort(this);
        pid = Prefs.player(this);
        running = true;
        queueLoadedFor = -2;
        h.post(poller);
        h.postDelayed(ticker, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        h.removeCallbacks(poller);
        h.removeCallbacks(ticker);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, M_QUEUE, 0, "Queue").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, M_PLAYER, 1, "Choose player");
        menu.add(0, M_FX, 2, "Sound effects");
        menu.add(0, M_CLEAR, 3, "Clear queue");
        menu.add(0, M_LOG, 4, "Player log");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: finish(); return true;
            case M_QUEUE: setQueueVisible(!queueVisible); return true;
            case M_PLAYER: choosePlayer(); return true;
            case M_FX: Nav.soundEffects(this); return true;
            case M_CLEAR:
                send(Rpc.cmd("playlist", "clear"));
                queueLoadedFor = -2;
                return true;
            case M_LOG: showLog(); return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (queueVisible) setQueueVisible(false);
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------

    private void send(JSONArray cmd) {
        Rpc.call(hp, pid, cmd, new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                if (e != null) Toast.makeText(MainActivity.this, "Command failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                h.removeCallbacks(poller);
                h.postDelayed(poller, 400);
            }
        });
    }

    private void setQueueVisible(boolean v) {
        queueVisible = v;
        queueList.setVisibility(v ? View.VISIBLE : View.GONE);
        cover.setVisibility(v ? View.INVISIBLE : View.VISIBLE);
        btnQueue.setActive(true);
        btnQueue.setColor(v ? Ui.accent(this) : Ui.textPrimary(this));
        if (v) {
            queueLoadedFor = -2;
            loadQueue();
        }
    }

    private void showModes() {
        btnShuffle.setActive(shuffle != 0);
        btnShuffle.setColor(shuffle != 0 ? Ui.accent(this) : Ui.textPrimary(this));
        btnRepeat.setActive(repeat != 0);
        btnRepeat.setColor(repeat != 0 ? Ui.accent(this) : Ui.textPrimary(this));
        btnRepeat.setBadge(repeat == 1 ? "1" : null);
    }

    private void showTime() {
        if (!seeking) {
            seek.setMax(Math.max(1, (int) duration));
            seek.setProgress((int) elapsed);
            elapsedTxt.setText(fmt((int) elapsed));
        }
        durationTxt.setText(duration > 0 ? fmt((int) duration) : "");
    }

    private static String fmt(int s) {
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void poll() {
        if (inFlight) return;
        inFlight = true;
        Rpc.call(hp, pid, Rpc.cmd("status", "-", "1", "tags:alcdKN"), new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                inFlight = false;
                if (r == null) {
                    title.setText("Can't reach the player");
                    artist.setText(hp);
                    return;
                }
                String pn = r.optString("player_name", "");
                if (!pn.equals(shownPlayer)) {
                    shownPlayer = pn;
                    Skin.setSubtitle(MainActivity.this, pn);
                }
                playing = "play".equals(r.optString("mode"));
                btnPlay.setGlyph(playing ? GlyphView.PAUSE : GlyphView.PLAY);
                elapsed = r.optDouble("time", 0);
                duration = r.optDouble("duration", 0);
                showTime();
                shuffle = r.optInt("playlist shuffle", 0);
                repeat = r.optInt("playlist repeat", 0);
                showModes();
                if (!volTouch) volume.setProgress(Math.abs(r.optInt("mixer volume", 0)));
                int tracks = r.optInt("playlist_tracks", 0);
                int cur = r.optInt("playlist_cur_index", -1);

                JSONArray loop = r.optJSONArray("playlist_loop");
                JSONObject t = loop == null ? null : loop.optJSONObject(0);
                if (t == null || tracks == 0) {
                    title.setText("Nothing playing");
                    artist.setText("Search for something on the home screen");
                    cover.setImageDrawable(null);
                    lastArt = "";
                } else {
                    String tt = t.optString("title", "");
                    if (!tt.contentEquals(title.getText())) title.setText(tt);
                    String sub = t.optString("artist", "");
                    String alb = t.optString("album", "");
                    if (alb.length() > 0) sub = sub.length() > 0 ? sub + " • " + alb : alb;
                    artist.setText(sub);
                    String icon = Item.str(t, "artwork_url");
                    if (icon == null) icon = Ui.localCover(Item.str(t, "coverid"));
                    if (icon == null) icon = Ui.localCover(Item.str(t, "id"));
                    String url = Ui.art(hp, icon, 600);
                    if (url != null && !url.equals(lastArt)) {
                        lastArt = url;
                        ImageLoader.load(cover, url, false);
                    }
                }
                boolean changed = cur != curIndex || tracks != trackCount;
                curIndex = cur;
                trackCount = tracks;
                if (queueVisible && (changed || queueLoadedFor == -2)) loadQueue();
                else if (changed) queueAdapter.notifyDataSetChanged();
            }
        });
    }

    private void loadQueue() {
        queueLoadedFor = curIndex;
        Rpc.call(hp, pid, Rpc.cmd("status", "0", "500", "tags:alcK"), new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                JSONArray loop = r == null ? null : r.optJSONArray("playlist_loop");
                queue.clear();
                for (int i = 0; loop != null && i < loop.length(); i++) {
                    JSONObject o = loop.optJSONObject(i);
                    if (o == null) continue;
                    String icon = Item.str(o, "artwork_url");
                    if (icon == null) icon = Ui.localCover(Item.str(o, "coverid"));
                    queue.add(Item.local("track", o.optString("id"), o.optString("title", ""),
                            o.optString("artist", ""), icon));
                }
                queueAdapter.notifyDataSetChanged();
                if (curIndex >= 0 && curIndex < queue.size()) queueList.setSelection(Math.max(0, curIndex - 1));
            }
        });
    }

    private void choosePlayer() {
        Rpc.call(hp, "", Rpc.cmd("players", "0", "99"), new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                JSONArray loop = r == null ? null : r.optJSONArray("players_loop");
                if (loop == null || loop.length() == 0) {
                    Toast.makeText(MainActivity.this, "No players found", Toast.LENGTH_SHORT).show();
                    return;
                }
                final List<String> ids = new ArrayList<String>();
                List<String> names = new ArrayList<String>();
                int sel = -1;
                String me = SlimProtoService.localMac(MainActivity.this);
                for (int i = 0; i < loop.length(); i++) {
                    JSONObject o = loop.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("playerid");
                    ids.add(id);
                    names.add(o.optString("name", id) + (me.equalsIgnoreCase(id) ? " (this phone)" : ""));
                    if (id.equalsIgnoreCase(pid)) sel = ids.size() - 1;
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Control player")
                        .setSingleChoiceItems(names.toArray(new String[names.size()]), sel, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                pid = ids.get(which);
                                Prefs.get(MainActivity.this).edit().putString("player_id", pid).commit();
                                lastArt = "";
                                queueLoadedFor = -2;
                                d.dismiss();
                                poll();
                            }
                        })
                        .show();
            }
        });
    }

    private void showLog() {
        final String log = SlimProtoService.LOG.length() == 0 ? "(empty)" : SlimProtoService.LOG.toString();
        TextView tv = new TextView(this);
        tv.setText(log);
        tv.setTextSize(11);
        tv.setPadding(16, 16, 16, 16);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Player log")
                .setView(sv)
                .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("slimproto", log));
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }
}
