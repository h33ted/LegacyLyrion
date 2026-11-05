package com.h33ted.legacylyrion.retro;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Now playing. Two layouts:
 *  - Walkman (Sony phones, and phones with no player to borrow from): corner keys for the
 *    library and the queue, artist and title on top, artwork in the middle, three round keys
 *    and a thin seek bar at the bottom, over the Walkman background;
 *  - Bar (Samsung and stock Android players): title on top, framed artwork, seek bar with
 *    times, and the phone player's own keys in a bar along the bottom.
 */
public class NowPlayingActivity extends RetroActivity {
    private static final int M_SHUFFLE = 200, M_REPEAT = 201, M_QUEUE = 202;
    private static final int ID_TOP = 1, ID_KEYS = 2, ID_SEEK = 3, ID_TIME = 4, ID_INF = 5, ID_ART = 6;

    private TextView line1, line2, line3, time, total;
    private ImageView art, playKey;
    private SeekBar seek;
    private boolean playing, dragging, resumed, inFlight;
    private double elapsed, duration;
    private int shuffle, repeat;
    private String artUrl = "";
    private long polledAt;
    private final Handler h = new Handler();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean land = Compat.screenW(this) > Compat.screenH(this);
        setContentView(Look.profile(this) == Look.MEDIASCAPE ? mediascape()
                : !Look.walkmanLayout(this) ? bar()
                : land ? walkmanLand() : Look.walkman2011(this) ? walkman2011() : walkman());
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && duration > 0) showTime(duration * p / 1000.0);
            }

            public void onStartTrackingTouch(SeekBar s) { dragging = true; }

            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (duration <= 0) return;
                elapsed = duration * s.getProgress() / 1000.0;
                send(Rpc.cmd("time", String.valueOf((int) elapsed)));
            }
        });
    }

    // ------------------------------------------------------------------ layouts

    private int scaled(float v) { return dp(v) * Look.screenScale(this) / 100; }

    private ImageView key(final int which) {
        ImageView v = new ImageView(this);
        Drawable d = Look.transport(this, which);
        if (Look.profile(this) == Look.MEDIASCAPE) {
            v.setImageDrawable(d);
        } else if (Look.walkmanLayout(this)) {
            v.setBackgroundDrawable(d);
        } else {
            v.setImageDrawable(d);
            v.setScaleType(ImageView.ScaleType.CENTER);
            Drawable bg = Look.keyBg(this);
            if (bg != null) v.setBackgroundDrawable(bg);
        }
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                if (which == Look.PREV) send(Rpc.cmd("playlist", "index", "-1"));
                else if (which == Look.NEXT) send(Rpc.cmd("playlist", "index", "+1"));
                else togglePlay();
            }
        });
        return v;
    }

    /** Corner keys: library (top left), play queue (top right). Returns their size. */
    private int addCorners(RelativeLayout root) {
        int size = 0;
        for (int i = 0; i < 2; i++) {
            final boolean left = i == 0;
            Drawable bg = Look.cornerBg(this, left);
            int cs = Math.max(bg.getIntrinsicWidth(), scaled(56));
            size = cs;
            ImageView c = new ImageView(this);
            c.setBackgroundDrawable(bg);
            c.setImageDrawable(Look.cornerIcon(this, left));
            c.setScaleType(ImageView.ScaleType.CENTER);
            int pad = cs / 4;
            c.setPadding(left ? 0 : pad, 0, left ? pad : 0, pad);
            c.setClickable(true);
            c.setFocusable(true);
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (left) startActivity(new Intent(NowPlayingActivity.this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    else startActivity(new Intent(NowPlayingActivity.this, QueueActivity.class));
                }
            });
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(cs, cs);
            lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            lp.addRule(left ? RelativeLayout.ALIGN_PARENT_LEFT : RelativeLayout.ALIGN_PARENT_RIGHT);
            root.addView(c, lp);
        }

        return size;
    }

    private RelativeLayout roundKeys() {
        RelativeLayout keys = new RelativeLayout(this);
        keys.setId(ID_KEYS);
        ImageView prev = key(Look.PREV), next = key(Look.NEXT);
        playKey = key(Look.PLAY);
        RelativeLayout.LayoutParams kp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kp.addRule(RelativeLayout.CENTER_IN_PARENT);
        keys.addView(playKey, kp);
        kp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        kp.addRule(RelativeLayout.CENTER_VERTICAL);
        keys.addView(prev, kp);
        kp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        kp.addRule(RelativeLayout.CENTER_VERTICAL);
        keys.addView(next, kp);
        return keys;
    }

    private int keysWidth() {
        return Look.transport(this, Look.PREV).getIntrinsicWidth() + Look.transport(this, Look.PLAY).getIntrinsicWidth()
                + Look.transport(this, Look.NEXT).getIntrinsicWidth();
    }

    private ImageView infinityKey() {
        Drawable inf = Look.infinity(this);
        if (inf == null) return null;
        ImageView iv = new ImageView(this);
        iv.setId(ID_INF);
        Drawable icon = Look.infinityIcon(this);
        if (icon != null) {   // key and symbol are separate drawables on the W8 / X8
            iv.setBackgroundDrawable(inf);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.CENTER);
        } else {
            iv.setImageDrawable(inf);
        }
        iv.setClickable(true);
        iv.setFocusable(true);
        iv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                moreDialog();
            }
        });
        return iv;
    }

    /**
     * Landscape Walkman: artwork as tall as the screen allows on the left, and a column on the
     * right with artist and title, the infinity key, the three round keys and the seek bar.
     */
    private View walkmanLand() {
        RelativeLayout root = new RelativeLayout(this);
        int w = Compat.screenW(this), h = Compat.screenH(this);
        boolean y2011 = Look.walkman2011(this);
        int corner = y2011 ? 0 : Math.max(Look.cornerBg(this, true).getIntrinsicWidth(), scaled(56));
        int m = scaled(8);
        int kw = keysWidth() + scaled(16);
        int s = Math.max(scaled(60), Math.min(h - corner * 2 / 3 - m, w - kw - 3 * m));

        // a plain row, art | column (RelativeLayout's RIGHT_OF mis-measures on 1.x)
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row, new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));

        LinearLayout artBox = new LinearLayout(this);
        artBox.setGravity(Gravity.CENTER);
        artBox.setPadding(m, corner * 2 / 3, 0, m);
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        art.setImageDrawable(Look.noArt(this));
        artBox.addView(art, new LinearLayout.LayoutParams(s, s));
        row.addView(artBox, new LinearLayout.LayoutParams(s + m, ViewGroup.LayoutParams.FILL_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(m, 0, m, 0);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.FILL_PARENT, 1f));

        line1 = text(18, false, 0xffffffff);
        line2 = text(18, false, 0xffffffff);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = corner * 2 / 3;   // clear of the queue corner
        lp.topMargin = scaled(4);
        col.addView(line1, lp);
        lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = corner * 2 / 3;
        col.addView(line2, lp);
        ImageView inf = y2011 ? null : infinityKey();
        if (inf != null) {
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ip.topMargin = scaled(4);
            ip.gravity = Gravity.CENTER_HORIZONTAL;
            col.addView(inf, ip);
        }
        if (y2011) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.topMargin = scaled(6);
            col.addView(buttonRow2011(), bp);
        }
        col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(Math.min(kw, w - s - 3 * m), ViewGroup.LayoutParams.WRAP_CONTENT);
        kp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(roundKeys(), kp);
        time = text(14, false, 0xffffffff);
        time.setGravity(Gravity.RIGHT);
        total = time;
        col.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        seek = new SeekBar(this);
        styleSeek();
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, scaled(25));
        sp.bottomMargin = scaled(4);
        col.addView(seek, sp);

        if (!y2011) addCorners(root);   // on top of the row
        return root;
    }

    /** 2011 WALKMAN: library, ∞ and queue as a row of buttons (no corner keys). */
    private View buttonRow2011() {
        // a plain row with stretchy gaps (a RelativeLayout here mis-measures inside a column on 2.x)
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int m = Look.playerDimen(this, "player_button_margin", dp(6));
        row.setPadding(m, 0, m, 0);
        String[] bg = {"music_playview_musiclibrary_btn_selector", null, "music_playview_playqueue_btn_selector"};
        for (int i = 0; i < 3; i++) {
            final int which = i;
            View v;
            if (i == 1) {
                v = infinityKey();
                if (v == null) v = new View(this);
                v.setId(0);
            } else {
                ImageView b = new ImageView(this);
                b.setBackgroundDrawable(Look.sony(this, bg[i]));
                b.setClickable(true);
                b.setFocusable(true);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View x) {
                        if (which == 0) startActivity(new Intent(NowPlayingActivity.this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                        else startActivity(new Intent(NowPlayingActivity.this, QueueActivity.class));
                    }
                });
                v = b;
            }
            if (i > 0) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return row;
    }

    /**
     * Portrait 2011 WALKMAN (Xperia arc / ray / neo...), as the phone's own player draws it: artist,
     * then the title in the theme's accent colour; the cover, with the three keys over its lower
     * edge; the time and seek bar; library / ∞ / queue along the bottom; and the background
     * tinted from the artwork. Sizes come from the player's own layout values.
     */
    private View walkman2011() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int w = Math.min(Compat.screenW(this), Compat.screenH(this));
        int h = Math.max(Compat.screenW(this), Compat.screenH(this));
        int pad = Look.playerDimen(this, "medium_padding", dp(10));
        int accent = Look.accent(this);

        line1 = text(17, false, 0xffffffff);
        line2 = text(24, false, accent != 0 ? accent : 0xffffffff);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = lp.rightMargin = pad;
        lp.topMargin = scaled(8);
        root.addView(line1, lp);
        lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = lp.rightMargin = pad;
        root.addView(line2, lp);

        // cover size: the player's own, limited so everything fits under the status bar
        View row = buttonRow2011();
        int rowH = 0;
        for (Drawable d : new Drawable[]{Look.sony(this, "music_playview_musiclibrary_btn_selector"), Look.infinity(this),
                Look.sony(this, "music_playview_playqueue_btn_selector")}) {
            if (d != null) rowH = Math.max(rowH, d.getIntrinsicHeight());
        }
        int keysH = Look.transport(this, Look.PLAY).getIntrinsicHeight();
        int fixed = dp(25) + scaled(70) + keysH * 55 / 100 + scaled(20) + scaled(32) + rowH + scaled(24);
        int s = Math.min(Math.min(Look.playerDimen(this, "album_art_size", dp(210)), w - 2 * pad), h - fixed);
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        art.setImageDrawable(Look.noArt(this));
        lp = new LinearLayout.LayoutParams(s, s);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = scaled(12);
        root.addView(art, lp);

        // the three keys, pulled up over the cover's lower edge (drawn after it, so on top)
        RelativeLayout keys = roundKeys();
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(Math.min(w, Look.playerDimen(this, "player_controls_view_width", dp(240))),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        kp.gravity = Gravity.CENTER_HORIZONTAL;
        kp.topMargin = -keysH * 45 / 100;
        root.addView(keys, kp);

        time = text(14, false, 0xffffffff);
        time.setGravity(Gravity.RIGHT);
        total = time;
        lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = pad;
        root.addView(time, lp);
        seek = new SeekBar(this);
        styleSeek();
        lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = lp.rightMargin = pad;
        root.addView(seek, lp);

        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = scaled(6);
        root.addView(row, lp);
        tintArt = true;
        return root;
    }

    // ------------------------------------------------------------------ 2011: artwork-tinted background

    private boolean tintArt;
    private Drawable tintedFor;

    /** Dark background with a glow in the cover's colour, behind the cover and the keys. */
    private void tintFromArtwork() {
        if (!tintArt || art == null) return;
        Drawable d = art.getDrawable();
        if (d == tintedFor) return;
        tintedFor = d;
        int c = 0xff202020;
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            android.graphics.Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            if (b != null) {
                try {
                    c = android.graphics.Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0);
                } catch (Throwable ignored) {}
            }
        }
        int r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
        int mx = Math.max(r, Math.max(g, bl));
        if (mx > 0 && mx < 160) {   // keep dark covers from giving a muddy, invisible glow
            r = r * 160 / mx;
            g = g * 160 / mx;
            bl = bl * 160 / mx;
        }
        getWindow().setBackgroundDrawable(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{shade(r, g, bl, 12), shade(r, g, bl, 30), shade(r, g, bl, 55), shade(r, g, bl, 8), 0xff000000}));
    }

    private static int shade(int r, int g, int b, int pct) {
        return 0xff000000 | ((r * pct / 100) << 16) | ((g * pct / 100) << 8) | (b * pct / 100);
    }

    private View walkman() {
        RelativeLayout root = new RelativeLayout(this);
        int w = Math.min(Compat.screenW(this), Compat.screenH(this));

        addCorners(root);

        // artist / title, between the corners
        LinearLayout top = new LinearLayout(this);
        top.setId(ID_TOP);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        top.setPadding(0, scaled(6), 0, 0);
        line1 = text(20, false, 0xffffffff);
        line2 = text(20, false, 0xffffffff);
        line1.setGravity(Gravity.CENTER_HORIZONTAL);
        line2.setGravity(Gravity.CENTER_HORIZONTAL);
        top.addView(line1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        top.addView(line2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        RelativeLayout.LayoutParams tp = new RelativeLayout.LayoutParams(w * 6 / 10, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        tp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        root.addView(top, tp);

        // the infinity key: shuffle, repeat, player
        ImageView iv = infinityKey();
        int below = ID_TOP;
        if (iv != null) {
            RelativeLayout.LayoutParams ip = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ip.addRule(RelativeLayout.BELOW, ID_TOP);
            ip.addRule(RelativeLayout.CENTER_HORIZONTAL);
            ip.topMargin = scaled(4);
            root.addView(iv, ip);
            below = ID_INF;
        }

        // seek bar at the very bottom, elapsed time above its right end
        seek = new SeekBar(this);
        seek.setId(ID_SEEK);
        styleSeek();
        RelativeLayout.LayoutParams sp = new RelativeLayout.LayoutParams(w * 92 / 100, scaled(25));
        sp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        sp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        sp.bottomMargin = scaled(4);
        root.addView(seek, sp);

        time = text(14, false, 0xffffffff);
        time.setId(ID_TIME);
        time.setGravity(Gravity.RIGHT);
        total = time;
        RelativeLayout.LayoutParams tm = new RelativeLayout.LayoutParams(w * 92 / 100, ViewGroup.LayoutParams.WRAP_CONTENT);
        tm.addRule(RelativeLayout.ABOVE, ID_SEEK);
        tm.addRule(RelativeLayout.CENTER_HORIZONTAL);
        root.addView(time, tm);

        // three round keys
        RelativeLayout keys = roundKeys();
        RelativeLayout.LayoutParams kl = new RelativeLayout.LayoutParams(w * 3 / 4, ViewGroup.LayoutParams.WRAP_CONTENT);
        kl.addRule(RelativeLayout.ABOVE, ID_TIME);
        kl.addRule(RelativeLayout.CENTER_HORIZONTAL);
        root.addView(keys, kl);

        // artwork fills what is left in the middle
        art = new ImageView(this);
        art.setId(ID_ART);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        art.setImageDrawable(Look.noArt(this));
        RelativeLayout.LayoutParams ap = new RelativeLayout.LayoutParams(w * 62 / 100, ViewGroup.LayoutParams.FILL_PARENT);
        ap.addRule(RelativeLayout.BELOW, below);
        ap.addRule(RelativeLayout.ABOVE, ID_KEYS);
        ap.addRule(RelativeLayout.CENTER_HORIZONTAL);
        ap.topMargin = scaled(6);
        ap.bottomMargin = scaled(6);
        root.addView(art, ap);
        return root;
    }

    /**
     * Mediascape (X10 on 1.6): artist, title and album centred at the top over the wave
     * background, the cover in the middle, and the controller along the bottom: the ring keys,
     * the track number and time, and the seek bar, on Mediascape's dark gradient bar.
     */
    private View mediascape() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        line1 = text(16, false, 0xffffffff);
        line2 = text(23, false, 0xffffffff);
        line3 = text(17, false, 0xffdddddd);
        for (TextView t : new TextView[]{line1, line2, line3}) {
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = lp.rightMargin = dp(10);
            if (t == line1) lp.topMargin = dp(8);
            root.addView(t, lp);
        }
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        art.setImageDrawable(Look.noArt(this));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, 0, 1f);
        ap.setMargins(dp(24), dp(10), dp(24), dp(10));
        root.addView(art, ap);

        // the controller, on Mediascape's gradient bar (which fades in from above)
        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.VERTICAL);
        ctl.setGravity(Gravity.CENTER_HORIZONTAL);
        Drawable bar = Look.sony(this, "ms_player_contoroller_background_icn");
        if (bar != null) ctl.setBackgroundDrawable(bar);
        LinearLayout keys = new LinearLayout(this);
        keys.setGravity(Gravity.CENTER);
        ImageView prev = key(Look.PREV), next = key(Look.NEXT);
        playKey = key(Look.PLAY);
        for (ImageView k : new ImageView[]{prev, playKey, next}) {
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            kp.leftMargin = kp.rightMargin = dp(6);
            keys.addView(k, kp);
        }
        ctl.addView(keys);
        LinearLayout times = new LinearLayout(this);
        times.setPadding(dp(10), 0, dp(10), 0);
        time = text(11, false, 0xffffffff);
        total = text(11, false, 0xffffffff);
        total.setGravity(Gravity.RIGHT);
        times.addView(time, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(total, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ctl.addView(times, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        seek = new SeekBar(this);
        styleSeek();
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(dp(10), 0, dp(10), dp(8));
        ctl.addView(seek, sp);
        root.addView(ctl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private View bar() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(10), dp(6), dp(10), dp(6));
        Drawable hb = Look.from(this, Look.SAMSUNG, "music_play_title_bg");
        if (hb != null) head.setBackgroundDrawable(hb);
        line2 = text(19, true, 0xffffffff);
        line1 = text(14, false, 0xffcccccc);
        head.addView(line2);
        head.addView(line1);
        root.addView(head);

        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Drawable frame = Look.artFrame(this);
        if (frame != null) art.setBackgroundDrawable(frame);
        art.setPadding(dp(4), dp(4), dp(4), dp(4));
        art.setImageDrawable(Look.noArt(this));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, 0, 1f);
        ap.setMargins(dp(16), dp(10), dp(16), dp(10));
        root.addView(art, ap);

        LinearLayout prog = new LinearLayout(this);
        prog.setGravity(Gravity.CENTER_VERTICAL);
        prog.setPadding(dp(8), 0, dp(8), dp(4));
        time = text(13, false, 0xffffffff);
        total = text(13, false, 0xffffffff);
        seek = new SeekBar(this);
        styleSeek();
        prog.addView(time);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sp.leftMargin = sp.rightMargin = dp(6);
        prog.addView(seek, sp);
        prog.addView(total);
        root.addView(prog);

        LinearLayout keys = new LinearLayout(this);
        keys.setGravity(Gravity.CENTER);
        keys.setBackgroundDrawable(Look.keyBar(this));
        keys.setPadding(0, dp(4), 0, dp(4));
        ImageView prev = key(Look.PREV), next = key(Look.NEXT);
        playKey = key(Look.PLAY);
        int kw = Math.min(Compat.screenW(this), Compat.screenH(this)) / 4;
        keys.addView(prev, new LinearLayout.LayoutParams(kw, ViewGroup.LayoutParams.WRAP_CONTENT));
        keys.addView(playKey, new LinearLayout.LayoutParams(kw, ViewGroup.LayoutParams.WRAP_CONTENT));
        keys.addView(next, new LinearLayout.LayoutParams(kw, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(keys);
        return root;
    }

    private void styleSeek() {
        Drawable track = Look.seekTrack(this);
        int accent = Look.walkman2011(this) ? Look.accent(this) : 0;
        if (track instanceof android.graphics.drawable.LayerDrawable && accent != 0) {   // 2011: progress in the theme colour
            Drawable p = ((android.graphics.drawable.LayerDrawable) track).findDrawableByLayerId(android.R.id.progress);
            if (p != null) p.setColorFilter(new android.graphics.PorterDuffColorFilter(accent, android.graphics.PorterDuff.Mode.SRC_ATOP));
        }
        if (track != null) seek.setProgressDrawable(track);
        Drawable thumb = Look.seekThumb(this);
        if (thumb != null) {
            seek.setThumb(thumb);
            seek.setThumbOffset(thumb.getIntrinsicWidth() / 2);
        }
        seek.setPadding(dp(8), 0, dp(8), 0);
    }

    // ------------------------------------------------------------------ status

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!resumed) return;
            tintFromArtwork();
            if (System.currentTimeMillis() - polledAt > 3000) poll();
            else if (playing && duration > 0 && !dragging) {
                elapsed = Math.min(duration, elapsed + 1);
                showTime(elapsed);
            }
            h.postDelayed(this, 1000);
        }
    };

    private void poll() {
        if (inFlight) return;
        inFlight = true;
        polledAt = System.currentTimeMillis();
        Rpc.call(this, Rpc.cmd("status", "-", "1", "tags:alcK"), new Rpc.Callback() {
            public void done(JSONObject r, Exception e) {
                inFlight = false;
                if (r == null) {
                    line1.setText("Can't reach the player");
                    line2.setText(e != null && e.getMessage() != null ? e.getMessage() : Prefs.hostPort(NowPlayingActivity.this));
                    return;
                }
                playing = "play".equals(r.optString("mode"));
                showPlaying();
                elapsed = r.optDouble("time", 0);
                duration = r.optDouble("duration", 0);
                shuffle = r.optInt("playlist shuffle", 0);
                repeat = r.optInt("playlist repeat", 0);
                if (!dragging) showTime(elapsed);
                JSONArray loop = r.optJSONArray("playlist_loop");
                JSONObject t = loop == null ? null : loop.optJSONObject(0);
                if (t == null) {
                    line1.setText(r.optString("player_name", ""));
                    line2.setText("Nothing playing");
                    if (artUrl.length() > 0) art.setImageDrawable(Look.noArt(NowPlayingActivity.this));
                    artUrl = "";
                    return;
                }
                String artist = t.optString("artist", ""), album = t.optString("album", ""), title = t.optString("title", "");
                if (line3 != null) {
                    line1.setText(artist);
                    line3.setText(album);
                } else if (Look.walkmanLayout(NowPlayingActivity.this)) {
                    line1.setText(artist.length() > 0 ? artist : album);
                } else {
                    line1.setText(album.length() > 0 && artist.length() > 0 ? artist + " · " + album : artist + album);
                }
                line2.setText(title);
                String icon = Item.str(t, "artwork_url");
                if (icon == null) icon = Images.trackCover(Item.str(t, "coverid"));
                int size = Math.min(Compat.screenW(NowPlayingActivity.this), Compat.screenH(NowPlayingActivity.this)) * 7 / 10;
                String url = Images.url(NowPlayingActivity.this, icon, size);
                if (url == null) {
                    art.setImageDrawable(Look.noArt(NowPlayingActivity.this));
                    artUrl = "";
                } else if (!url.equals(artUrl)) {
                    artUrl = url;
                    Images.load(art, url, 0);
                    if (art.getDrawable() == null) art.setImageDrawable(Look.noArt(NowPlayingActivity.this));
                }
            }
        });
    }

    private void showPlaying() {
        Drawable d = Look.transport(this, playing ? Look.PAUSE : Look.PLAY);
        if (Look.walkmanLayout(this) && Look.profile(this) != Look.MEDIASCAPE) playKey.setBackgroundDrawable(d);
        else playKey.setImageDrawable(d);
    }

    private void showTime(double at) {
        if (duration > 0) {
            if (!dragging || at != elapsed) seek.setProgress((int) (at * 1000 / duration));
            if (total == time) time.setText(clock(at));
            else {
                time.setText(clock(at));
                total.setText(clock(duration));
            }
        } else {
            seek.setProgress(0);
            time.setText(at > 0 ? clock(at) : "");
            if (total != time) total.setText("");
        }
    }

    private static String clock(double s) {
        int t = (int) s, m = t / 60, sec = t % 60;
        if (m >= 60) return (m / 60) + ":" + (m % 60 < 10 ? "0" : "") + (m % 60) + ":" + (sec < 10 ? "0" : "") + sec;
        return (m < 10 ? "0" : "") + m + ":" + (sec < 10 ? "0" : "") + sec;
    }

    private void togglePlay() {
        playing = !playing;
        showPlaying();
        send(playing ? Rpc.cmd("play") : Rpc.cmd("pause", "1"));
    }

    private void send(JSONArray cmd) {
        Rpc.call(this, cmd, new Rpc.Callback() {
            public void done(JSONObject r, Exception e) {
                if (e != null) toast("Failed: " + e.getMessage());
                polledAt = 0;   // refresh on the next tick
            }
        });
    }

    private void moreDialog() {
        String[] opts = {"Shuffle: " + (shuffle == 0 ? "off" : shuffle == 1 ? "songs" : "albums"),
                "Repeat: " + (repeat == 0 ? "off" : repeat == 1 ? "this song" : "all"), "Play queue", "Choose player"};
        new AlertDialog.Builder(this).setItems(opts, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                if (which == 0) cycleShuffle();
                else if (which == 1) cycleRepeat();
                else if (which == 2) startActivity(new Intent(NowPlayingActivity.this, QueueActivity.class));
                else choosePlayer();
            }
        }).show();
    }

    private void cycleShuffle() {
        shuffle = (shuffle + 1) % 3;
        send(Rpc.cmd("playlist", "shuffle", String.valueOf(shuffle)));
        toast(shuffle == 0 ? "Shuffle off" : shuffle == 1 ? "Shuffle songs" : "Shuffle albums");
    }

    private void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        send(Rpc.cmd("playlist", "repeat", String.valueOf(repeat)));
        toast(repeat == 0 ? "Repeat off" : repeat == 1 ? "Repeat this song" : "Repeat all");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, M_QUEUE, 0, "Play queue").setIcon(android.R.drawable.ic_menu_agenda);
        m.add(0, M_SHUFFLE, 0, "Shuffle").setIcon(android.R.drawable.ic_menu_rotate);
        m.add(0, M_REPEAT, 0, "Repeat").setIcon(android.R.drawable.ic_menu_revert);
        return super.onCreateOptionsMenu(m);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case M_QUEUE:
                startActivity(new Intent(this, QueueActivity.class));
                return true;
            case M_SHUFFLE:
                cycleShuffle();
                return true;
            case M_REPEAT:
                cycleRepeat();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyDown(int code, android.view.KeyEvent ev) {
        // D-pad / trackball: left and right skip, centre plays or pauses
        if (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER && !hasFocusedKey()) {
            togglePlay();
            return true;
        }
        return super.onKeyDown(code, ev);
    }

    private boolean hasFocusedKey() {
        View f = getCurrentFocus();
        return f != null && f.isClickable();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        polledAt = 0;
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        h.removeCallbacks(tick);
    }

    @Override
    void reload() { polledAt = 0; }
}
