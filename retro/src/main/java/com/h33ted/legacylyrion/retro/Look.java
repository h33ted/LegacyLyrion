package com.h33ted.legacylyrion.retro;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;

/**
 * Where the player's look comes from, best first:
 *  1. the phone's own music player, borrowed at run time:
 *     SONY    com.sonyericsson.music ("WALKMAN" on the X8/W8, "Music player" on the X10 mini):
 *             round keys, corner keys, seek bar, background;
 *     SAMSUNG com.android.music as Samsung ships it (Galaxy S TouchWiz 3, Galaxy S2 TouchWiz 4): its keys, key bar, title
 *             strip, seek bar and disc (TouchWiz's framework supplies the list screens' title bars);
 *     MEDIASCAPE  Sony Ericsson's Mediascape (X10 on 1.6): its ring keys, controller bar,
 *             wave background and CD case;
 *     AOSP    the stock com.android.music (Galaxy 5 and most 1.5 - 2.x phones): album frame and
 *             the framework's media keys;
 *  2. the vendor framework (android:drawable/semc_*, android:style/SEMCTheme on Sony Ericsson);
 *  3. our own drawings of the Walkman shapes, for every other phone.
 * Nothing is copied into this app: resources are read from what the phone already has.
 */
final class Look {
    static final String SONY_MUSIC = "com.sonyericsson.music", MUSIC = "com.android.music",
            MEDIASCAPE_PKG = "com.sonyericsson.android.mediascape";
    static final int PLAIN = 0, SONY = 1, SAMSUNG = 2, AOSP = 3, MEDIASCAPE = 4;

    private static Resources player;
    private static String playerPkg;
    private static int profile = -1;

    private Look() {}

    private static Resources res(Context c, String pkg) {
        try {
            return c.createPackageContext(pkg, 0).getResources();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Which phone player we borrow from (decided once). */
    static int profile(Context c) {
        if (profile >= 0) return profile;
        profile = PLAIN;
        Resources r = res(c, SONY_MUSIC);
        if (id(r, "music_playview_play_selector", SONY_MUSIC) != 0) {
            profile = SONY;
            player = r;
            playerPkg = SONY_MUSIC;
            return profile;
        }
        r = res(c, MEDIASCAPE_PKG);   // X10 on 1.6: Mediascape is the music player
        if (id(r, "ms_player_play_icn", MEDIASCAPE_PKG) != 0) {
            profile = MEDIASCAPE;
            player = r;
            playerPkg = MEDIASCAPE_PKG;
            return profile;
        }
        r = res(c, MUSIC);
        if (id(r, "music_play_control_play_btn_selector", MUSIC) != 0) profile = SAMSUNG;
        else if (id(r, "albumart_mp_unknown", MUSIC) != 0) profile = AOSP;
        if (profile != PLAIN) {
            player = r;
            playerPkg = MUSIC;
        }
        return profile;
    }

    static boolean hasWalkman(Context c) { return profile(c) == SONY; }

    /**
     * The accent colour of the phone's colour theme on 2011 Xperias (UXP semc_theme_accent_color,
     * red / blue / green... as picked in Settings > Display > Theme), or 0.
     */
    static int accent(Context c) {
        try {
            int i = c.getResources().getIdentifier("semc_theme_accent_color", "color", UXP);
            return i == 0 ? 0 : c.getResources().getColor(i);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The 2011 WALKMAN (Xperia arc / ray / neo, 2.3): button row under the cover, no corner keys. */
    static boolean walkman2011(Context c) {
        return hasWalkman(c) && sony(c, "music_playview_musiclibrary_btn_selector") != null;
    }

    /** A size from the phone's music player (its own layout values), or the fallback. */
    static int playerDimen(Context c, String name, int fallbackPx) {
        profile(c);
        try {
            int i = player == null ? 0 : player.getIdentifier(name, "dimen", playerPkg);
            return i == 0 ? fallbackPx : player.getDimensionPixelSize(i);
        } catch (Throwable t) {
            return fallbackPx;
        }
    }

    /** Walkman layout (round keys, corners) for Sony phones and for phones with nothing to borrow. */
    static boolean walkmanLayout(Context c) { return profile(c) == SONY || profile(c) == PLAIN; }

    static boolean isSony(Context c) {
        return id(c.getResources(), "semc_bg", "android") != 0 || id(c.getResources(), "semc_bg", UXP) != 0
                || id(c.getResources(), "semc_background", "android") != 0 || hasWalkman(c) || profile(c) == MEDIASCAPE;
    }

    static String describe(Context c) {
        switch (profile(c)) {
            case SONY: return "your phone's Sony music player";
            case SAMSUNG: return "your phone's Samsung music player";
            case MEDIASCAPE: return "your phone's Mediascape";
            case AOSP: return "your phone's Android music player";
            default: return isSony(c) ? "your phone's Sony Ericsson theme" : "built-in drawings";
        }
    }

    private static int id(Resources r, String name, String pkg) {
        if (r == null) return 0;
        try {
            return r.getIdentifier(name, "drawable", pkg);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** A drawable from the phone's own music player, or null. */
    static Drawable sony(Context c, String name) {
        profile(c);
        int i = id(player, name, playerPkg);
        if (i == 0) return null;
        try {
            return player.getDrawable(i);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Same, only when that player is the given profile. */
    static Drawable from(Context c, int prof, String name) {
        return profile(c) == prof ? sony(c, name) : null;
    }

    /** Sony's 2011 resource package (Xperia arc / ray / neo...), loaded into every app there. */
    static final String UXP = "com.sonyericsson.uxp";

    /**
     * A drawable from the phone's framework, or null: android:drawable/name, then Sony's UXP
     * package, where the 2011 Xperias keep their semc_* look.
     */
    static Drawable framework(Context c, String name) {
        Resources r = c.getResources();
        int i = id(r, name, "android");
        if (i == 0) i = id(r, name, UXP);
        if (i == 0) return null;
        try {
            return r.getDrawable(i);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Drawable pressable(Drawable normal, Drawable pressed) {
        if (normal == null) return null;
        if (pressed == null) return normal;
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{android.R.attr.state_focused}, pressed);
        s.addState(new int[]{android.R.attr.state_selected}, pressed);
        s.addState(new int[0], normal);
        return s;
    }

    // ------------------------------------------------------------------ theme

    /**
     * Sony Ericsson's own theme when the phone has it (lists, dialogs, buttons, menus look like
     * the phone's apps); the plain black theme otherwise. Call before super.onCreate.
     */
    static void theme(Activity a) {
        Resources r = a.getResources();
        int t = 0;
        try {
            t = r.getIdentifier("SEMCTheme.NoTitleBar", "style", "android");   // 2010 Xperias (1.6, 2.1)
            if (t == 0) t = r.getIdentifier("SemcTheme.NoTitleBar", "style", "android");   // X10 (1.6)
            if (t == 0) t = r.getIdentifier("SEMCTheme.NoTitleBar", "style", UXP);   // 2011 Xperias (2.3)
        } catch (Throwable ignored) {}
        if (t != 0) a.setTheme(t);
        else a.setTheme(android.R.style.Theme_NoTitleBar);
    }

    /** The screen background: Walkman picture, Sony wallpaper-style background, or our own. */
    static Drawable background(Context c) {
        Drawable d = from(c, SONY, "music_default_background");
        if (d == null) d = from(c, MEDIASCAPE, "ms_bg_background_icn");
        if (d == null) d = framework(c, "semc_bg");
        if (d == null) d = framework(c, "semc_background");   // X10 (1.6)
        if (d == null && profile(c) != PLAIN) d = new android.graphics.drawable.ColorDrawable(0xff000000);
        if (d == null) d = new Backdrop();
        return d;
    }

    // ------------------------------------------------------------------ player keys

    static final int PLAY = 0, PAUSE = 1, PREV = 2, NEXT = 3;
    private static final String[] KEY = {"play", "pause", "previous", "next"};
    private static final String[] SAMSUNG_KEY = {"play", "pause", "prev", "next"};
    private static final String[] MS_KEY = {"play", "stop", "previous", "next"};   // Mediascape's pause is "stop"
    private static final String[] SAMSUNG_KEY3 = {"play", "pause", "rew", "ff"};
    private static final int[] AOSP_KEY = {android.R.drawable.ic_media_play, android.R.drawable.ic_media_pause,
            android.R.drawable.ic_media_previous, android.R.drawable.ic_media_next};

    /** Background behind each key in the bar layout (stock Android's MediaButton style). */
    static Drawable keyBg(Context c) {
        if (profile(c) == AOSP) return framework(c, "media_button_background");
        return from(c, SAMSUNG, "music_play_control_selector");
    }

    /** The bar holding the keys in the bar layout. */
    static Drawable keyBar(Context c) {
        Drawable d = from(c, SAMSUNG, "music_play_controller_bg");
        if (d != null) return d;
        return new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff6a6a6a, 0xff3a3a3a});
    }

    /** Frame around the artwork in the bar layout (stock Android's album border). */
    static Drawable artFrame(Context c) {
        return from(c, AOSP, "album_border_large");
    }

    static Drawable transport(Context c, int which) {
        Drawable d = from(c, SONY, "music_playview_" + KEY[which] + "_selector");
        if (d == null) d = from(c, SAMSUNG, "music_play_control_" + SAMSUNG_KEY[which] + "_btn_selector");   // TouchWiz 4
        if (d == null) d = from(c, SAMSUNG, "music_play_control_" + SAMSUNG_KEY3[which] + "_btn_selector");  // TouchWiz 3
        if (d == null && profile(c) == MEDIASCAPE) {
            String n = "ms_player_" + MS_KEY[which];
            d = pressable(sony(c, n + "_icn"), sony(c, n + "_highlighted_icn"));
        }
        if (d == null && profile(c) == AOSP) d = c.getResources().getDrawable(AOSP_KEY[which]);
        if (d != null) return d;
        int size = Compat.dp(c, which <= PAUSE ? 88 : 64) * screenScale(c) / 100;
        return pressable(new RoundKey(which, size, false), new RoundKey(which, size, true));
    }

    /** Corner keys: top-left goes to the library, top-right to the play queue. */
    static Drawable cornerBg(Context c, boolean left) {
        Drawable d = from(c, SONY, left ? "music_corner_btn_topleft_defalut" : "music_corner_btn_topright_defalut");
        if (d != null) return d;
        String n = left ? "semc_button_corner_up_left" : "semc_button_corner_up_right";
        d = pressable(framework(c, n), framework(c, n + "_pressed"));
        if (d != null) return d;
        int size = Compat.dp(c, 64) * screenScale(c) / 100;
        return pressable(new Corner(left, size, false), new Corner(left, size, true));
    }

    static Drawable cornerIcon(Context c, boolean left) {
        Drawable d = from(c, SONY, left ? "music_playview_musiclibrary" : "music_playview_playqueue");
        if (d != null) return d;
        return new Glyph(left ? Glyph.LIBRARY : Glyph.QUEUE, Compat.dp(c, 30) * screenScale(c) / 100);
    }

    /** The ∞ symbol, when the player keeps it apart from the key (WALKMAN on the X8/W8), or null. */
    static Drawable infinityIcon(Context c) {
        Drawable d = from(c, SONY, "music_infinite");
        if (d == null) d = from(c, SONY, "music_playview_infinite");   // 2011
        return d;
    }

    /** The infinity key under the title ("more about this"). */
    static Drawable infinity(Context c) {
        Drawable d = from(c, SONY, "music_infinite_button");
        if (d == null) d = from(c, SONY, "music_playview_infinite_btn_bg_selector");   // 2011
        if (d == null) d = framework(c, "semc_infinite_button");
        return d;
    }

    static Drawable seekTrack(Context c) {
        Drawable d = from(c, SONY, "seekbar_style");
        if (d == null) d = from(c, SAMSUNG, "music_play_tw3_horizontal_seekbar");
        if (d != null) return d;
        Drawable bg = framework(c, "semc_progress_bar_bg"), fg = framework(c, "semc_progress_bar_indicator");
        if (bg != null && fg != null) {
            android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(new Drawable[]{bg,
                    new android.graphics.drawable.ClipDrawable(fg, android.view.Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL)});
            l.setId(0, android.R.id.background);
            l.setId(1, android.R.id.progress);
            return l;
        }
        return null;   // keep the theme's own
    }

    static Drawable seekThumb(Context c) {
        Drawable d = from(c, SONY, "music_playview_progress_dot_selector");   // 2011
        if (d == null) d = from(c, SONY, "music_playview_progress_dot");
        if (d == null) d = from(c, SAMSUNG, "music_play_tw3_progressive_point");
        if (d != null) return d;
        if (profile(c) == AOSP || profile(c) == MEDIASCAPE) return null;   // the framework's own thumb
        return new Dot(Compat.dp(c, 18));
    }

    /** Placeholder while there's no artwork: the Walkman CD, or our own disc. */
    static Drawable noArt(Context c) {
        Drawable d = from(c, SONY, "musicplayer_default_album_big");
        if (d == null) d = from(c, SAMSUNG, "music_play_disc_default_01");   // TouchWiz 3
        if (d == null) d = from(c, SAMSUNG, "music_player_thumb_01");        // TouchWiz 4
        if (d == null) d = from(c, AOSP, "albumart_mp_unknown");
        if (d == null) d = from(c, MEDIASCAPE, "ms_default_album_art_for_player_icn");
        return d != null ? d : new Disc();
    }

    /** Tabs on the home screen. */
    static Drawable tab(Context c) {
        Drawable d = from(c, SONY, "general_tab");
        if (d != null) return d;

        d = pressable(framework(c, "semc_tab_inactive_bg"), framework(c, "semc_tab_active_bg"));
        if (d != null) return d;
        d = framework(c, "tab_indicator");   // the phone's own TabWidget look (TouchWiz, Sony 2011, stock...)
        if (d != null) {
            frameworkTabs = true;
            return d;
        }
        return pressable(new TabBg(false), new TabBg(true));
    }

    private static boolean frameworkTabs;

    /**
     * Label colours for the tabs: the framework's own (tab_indicator_text: dark on the light
     * tabs of the X10, white on TouchWiz...) when its tab graphics are used, else null (white).
     */
    static android.content.res.ColorStateList tabText(Context c) {
        tab(c);
        if (!frameworkTabs) return null;
        try {
            int i = c.getResources().getIdentifier("tab_indicator_text", "color", "android");
            return i == 0 ? null : c.getResources().getColorStateList(i);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Rows under the Walkman background: the Walkman's own highlight when it has one. */
    static Drawable listSelector(Context c) {
        Drawable d = from(c, SONY, "music_list_selector_background");
        if (d == null) d = from(c, SAMSUNG, "music_play_list_selector");
        return d;
    }

    /**
     * Title strips: Sony's title bar, TouchWiz's blue activity title bar, or the platform's own,
     * else a tab-style bar of our own.
     */
    static Drawable titleBar(Context c) {
        String[] names = {"semc_title_bar", "semc_activity_title_bar", "activity_title_bar", "title_bar_portrait", "title_bar"};
        for (String n : names) {
            Drawable d = framework(c, n);
            if (d != null) return d;
        }
        return new TabBg(false);
    }

    /** Text sizes follow the screen: 100 on a 320-pixel-wide 160 dpi phone. */
    static int screenScale(Context c) {
        int w = Math.min(Compat.screenW(c), Compat.screenH(c));
        int s = (int) (w * 100 / (320 * Compat.density(c)));
        return Math.max(70, Math.min(140, s));
    }

    // ------------------------------------------------------------------ our own drawings

    private abstract static class Shape extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        public void setAlpha(int a) {}

        @Override
        public void setColorFilter(ColorFilter cf) {}

        @Override
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Dark glass background with a soft glow, like the Walkman wallpaper but quieter. */
    static final class Backdrop extends Shape {
        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            p.setShader(new LinearGradient(0, b.top, 0, b.bottom, 0xff15181d, 0xff050506, Shader.TileMode.CLAMP));
            cv.drawRect(b, p);
            float r = Math.max(b.width(), b.height()) * 0.7f;
            p.setShader(new RadialGradient(b.left + b.width() * 0.75f, b.top + b.height() * 0.42f, r,
                    0x3a3a7bd5, 0x003a7bd5, Shader.TileMode.CLAMP));
            cv.drawRect(b, p);
            p.setShader(null);
        }

        @Override
        public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /** Round transport key: glass disc, thin white ring, white symbol. */
    static final class RoundKey extends Shape {
        final int which, size;
        final boolean down;

        RoundKey(int which, int size, boolean down) {
            this.which = which;
            this.size = size;
            this.down = down;
        }

        @Override
        public int getIntrinsicWidth() { return size; }

        @Override
        public int getIntrinsicHeight() { return size; }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY(), r = Math.min(b.width(), b.height()) / 2f - 2;
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, cy - r, 0, cy + r, down ? 0x66ffffff : 0x40ffffff,
                    down ? 0x33ffffff : 0x0affffff, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, r, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, r / 14f));
            p.setColor(0xd0ffffff);
            cv.drawCircle(cx, cy, r - p.getStrokeWidth() / 2, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xffffffff);
            float g = r * (which <= PAUSE ? 0.42f : 0.36f);
            Path path = new Path();
            if (which == PLAY) {
                path.moveTo(cx - g * 0.55f, cy - g);
                path.lineTo(cx + g * 0.95f, cy);
                path.lineTo(cx - g * 0.55f, cy + g);
                path.close();
                cv.drawPath(path, p);
            } else if (which == PAUSE) {
                float w = g * 0.42f;
                cv.drawRect(cx - g * 0.75f, cy - g, cx - g * 0.75f + w, cy + g, p);
                cv.drawRect(cx + g * 0.75f - w, cy - g, cx + g * 0.75f, cy + g, p);
            } else {
                int dir = which == NEXT ? 1 : -1;
                for (int k = 0; k < 2; k++) {
                    float x0 = cx + dir * (k == 0 ? -g * 0.95f : 0);
                    path.reset();
                    path.moveTo(x0, cy - g * 0.8f);
                    path.lineTo(x0 + dir * g * 0.95f, cy);
                    path.lineTo(x0, cy + g * 0.8f);
                    path.close();
                    cv.drawPath(path, p);
                }
                float bx = cx + dir * g * 0.95f;
                cv.drawRect(Math.min(bx, bx + dir * g * 0.22f), cy - g * 0.8f, Math.max(bx, bx + dir * g * 0.22f), cy + g * 0.8f, p);
            }
        }
    }

    /** Quarter-circle corner key, as on Sony's 2010 phones. */
    static final class Corner extends Shape {
        final boolean left, down;
        final int size;

        Corner(boolean left, int size, boolean down) {
            this.left = left;
            this.size = size;
            this.down = down;
        }

        @Override
        public int getIntrinsicWidth() { return size; }

        @Override
        public int getIntrinsicHeight() { return size; }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            float r = Math.min(b.width(), b.height());
            float cx = left ? b.left : b.right, cy = b.top;
            p.setStyle(Paint.Style.FILL);
            p.setColor(down ? 0x55ffffff : 0x26ffffff);
            cv.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, r / 40f));
            p.setColor(0x80ffffff);
            cv.drawCircle(cx, cy, r - p.getStrokeWidth(), p);
        }
    }

    /** Library (note in a folder) and play queue (lines and a note) symbols for the corners. */
    static final class Glyph extends Shape {
        static final int LIBRARY = 0, QUEUE = 1;
        final int kind, size;

        Glyph(int kind, int size) {
            this.kind = kind;
            this.size = size;
        }

        @Override
        public int getIntrinsicWidth() { return size; }

        @Override
        public int getIntrinsicHeight() { return size; }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()), x = b.left, y = b.top;
            p.setColor(0xffffffff);
            p.setStyle(Paint.Style.FILL);
            if (kind == LIBRARY) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s / 14f);
                cv.drawRoundRect(new RectF(x + s * 0.12f, y + s * 0.18f, x + s * 0.88f, y + s * 0.84f), s / 10f, s / 10f, p);
                p.setStyle(Paint.Style.FILL);
                note(cv, x + s * 0.5f, y + s * 0.5f, s * 0.42f);
            } else {
                float h = s / 12f;
                for (int i = 0; i < 3; i++) cv.drawRect(x + s * 0.08f, y + s * (0.2f + i * 0.2f), x + s * 0.5f, y + s * (0.2f + i * 0.2f) + h, p);
                note(cv, x + s * 0.72f, y + s * 0.5f, s * 0.5f);
            }
        }

        private void note(Canvas cv, float cx, float cy, float h) {
            float r = h * 0.2f;
            cv.drawCircle(cx - r * 0.6f, cy + h * 0.32f, r, p);
            cv.drawRect(cx + r * 0.25f, cy - h * 0.5f, cx + r * 0.25f + h * 0.1f, cy + h * 0.32f, p);
            cv.drawRect(cx + r * 0.25f, cy - h * 0.5f, cx + r * 0.25f + h * 0.38f, cy - h * 0.38f, p);
        }
    }

    static final class Dot extends Shape {
        final int size;

        Dot(int size) { this.size = size; }

        @Override
        public int getIntrinsicWidth() { return size; }

        @Override
        public int getIntrinsicHeight() { return size; }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            float r = Math.min(b.width(), b.height()) / 2f;
            p.setColor(0x55ffffff);
            cv.drawCircle(b.exactCenterX(), b.exactCenterY(), r, p);
            p.setColor(0xffffffff);
            cv.drawCircle(b.exactCenterX(), b.exactCenterY(), r * 0.45f, p);
        }
    }

    /** A plain disc for "no artwork". */
    static final class Disc extends Shape {
        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY(), r = Math.min(b.width(), b.height()) * 0.46f;
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, 0xffe8ecf2, 0xff9aa3ad, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, r, p);
            p.setShader(null);
            p.setColor(0xff6d7680);
            cv.drawCircle(cx, cy, r * 0.32f, p);
            p.setColor(0xffd6dbe1);
            cv.drawCircle(cx, cy, r * 0.22f, p);
            p.setColor(0xff30353b);
            cv.drawCircle(cx, cy, r * 0.06f, p);
        }

        @Override
        public int getIntrinsicWidth() { return 200; }

        @Override
        public int getIntrinsicHeight() { return 200; }
    }

    /** Tab background: dark bar, lighter with a bright top line when chosen. */
    static final class TabBg extends Shape {
        final boolean on;

        TabBg(boolean on) { this.on = on; }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            p.setShader(new LinearGradient(0, b.top, 0, b.bottom, on ? 0xff4a5866 : 0xff23282e,
                    on ? 0xff2a3440 : 0xff111417, Shader.TileMode.CLAMP));
            cv.drawRect(b, p);
            p.setShader(null);
            p.setColor(on ? 0xff6fb3ff : 0xff000000);
            cv.drawRect(b.left, on ? b.bottom - Math.max(2, b.height() / 16) : b.bottom - 1, b.right, b.bottom, p);
            p.setColor(0x33ffffff);
            cv.drawRect(b.right - 1, b.top, b.right, b.bottom, p);
        }
    }
}
