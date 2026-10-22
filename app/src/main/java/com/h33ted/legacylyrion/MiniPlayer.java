package com.h33ted.legacylyrion;

import android.app.Activity;
import android.os.Handler;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

/** The bar at the bottom of Home and Browse: what's playing, play/pause, next. Tap opens Now Playing. */
final class MiniPlayer {
    private static MiniPlayer active;

    private final Activity act;
    private final View root;
    private final ImageView art;
    private final TextView title, artist;
    private final GlyphView playPause, next;
    private final Handler h = new Handler();
    private boolean playing, inFlight, running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            poll();
            if (running) h.postDelayed(this, 3000);
        }
    };

    MiniPlayer(final Activity a) {
        act = a;
        root = a.findViewById(R.id.miniPlayer);
        art = (ImageView) a.findViewById(R.id.miniArt);
        title = (TextView) a.findViewById(R.id.miniTitle);
        artist = (TextView) a.findViewById(R.id.miniArtist);
        playPause = (GlyphView) a.findViewById(R.id.miniPlayPause);
        next = (GlyphView) a.findViewById(R.id.miniNext);
        playPause.setGlyph(GlyphView.PLAY);
        next.setGlyph(GlyphView.NEXT);
        Ui.placeholder(art, false);
        Ui.selectable(root);
        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Nav.nowPlaying(a);
            }
        });
        playPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playing = !playing;
                updateButton();
                Nav.exec(a, playing ? Rpc.cmd("play") : Rpc.cmd("pause", "1"), null, null);
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Nav.exec(a, Rpc.cmd("playlist", "index", "+1"), null, null);
            }
        });
    }

    void start() {
        active = this;
        running = true;
        h.removeCallbacks(tick);
        h.post(tick);
    }

    void stop() {
        if (active == this) active = null;
        running = false;
        h.removeCallbacks(tick);
    }

    static void refreshActive() {
        final MiniPlayer m = active;
        if (m == null) return;
        m.h.removeCallbacks(m.tick);
        m.h.postDelayed(m.tick, 700);
    }

    private void updateButton() {
        playPause.setGlyph(playing ? GlyphView.PAUSE : GlyphView.PLAY);
    }

    private void poll() {
        if (inFlight) return;
        inFlight = true;
        final String hp = Prefs.hostPort(act);
        final String pid = Prefs.player(act);
        Rpc.call(hp, pid, Rpc.cmd("status", "-", "1", "tags:alcK"), new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                inFlight = false;
                JSONArray loop = r == null ? null : r.optJSONArray("playlist_loop");
                JSONObject t = loop == null ? null : loop.optJSONObject(0);
                if (t == null) {
                    root.setVisibility(View.GONE);
                    return;
                }
                root.setVisibility(View.VISIBLE);
                title.setText(t.optString("title", ""));
                String ar = t.optString("artist", "");
                if (ar.length() == 0) ar = t.optString("album", "");
                artist.setText(ar);
                playing = "play".equals(r.optString("mode"));
                updateButton();
                String icon = Item.str(t, "artwork_url");
                if (icon == null) icon = Ui.localCover(Item.str(t, "coverid"));
                if (icon == null) {
                    try {
                        icon = "/music/current/cover_150x150.jpg?player=" + URLEncoder.encode(pid, "UTF-8")
                                + "&id=" + t.optString("id");
                    } catch (Exception ignored) {}
                }
                String url = Ui.art(hp, icon, 150);
                if (url != null && !url.equals(art.getTag())) ImageLoader.load(art, url, false);
            }
        });
    }
}
