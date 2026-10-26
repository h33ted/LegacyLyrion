package com.h33ted.legacylyrion;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.URLEncoder;

/**
 * View builders. Every colour comes from the current theme (Theme.DeviceDefault), so the app
 * picks up whatever the OEM skin defines: Sony, Samsung, LG, HTC Sense, stock Holo, light or dark.
 * The only fixed colour is a translucent grey for empty artwork, which reads on any background.
 */
final class Ui {
    static final int PLACEHOLDER = 0x33888888;

    private Ui() {}

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static int themeColor(Context c, int attr, int fallback) {
        TypedArray a = c.getTheme().obtainStyledAttributes(new int[]{attr});
        try {
            return a.getColor(0, fallback);
        } finally {
            a.recycle();
        }
    }

    static int textPrimary(Context c) { return themeColor(c, android.R.attr.textColorPrimary, Color.GRAY); }

    static int textSecondary(Context c) { return themeColor(c, android.R.attr.textColorSecondary, Color.GRAY); }

    /** The skin's accent: link colour is the one attribute every OEM theme of this era sets. */
    static int accent(Context c) {
        if (Skin.sony(c)) {
            int a = Skin.sonyAccent(c);
            // the stock value is white; only a real theme colour counts as an accent
            if (a != 0 && (a & 0xffffff) != 0xffffff) return a;
        }
        return themeColor(c, android.R.attr.textColorLink, textPrimary(c));
    }

    static void selectable(View v) {
        TypedValue tv = new TypedValue();
        if (v.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            // setBackgroundResource() wipes padding on pre-4.4 framework, so put it back
            int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
            v.setBackgroundResource(tv.resourceId);
            v.setPadding(l, t, r, b);
        }
        v.setClickable(true);
    }

    @SuppressWarnings("deprecation")
    static void placeholder(ImageView iv, boolean circle) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(PLACEHOLDER);
        if (circle) g.setShape(GradientDrawable.OVAL);
        else g.setCornerRadius(dp(iv.getContext(), 3));
        iv.setBackgroundDrawable(g);
    }

    /** Absolute, resized artwork URL for a Jive icon, local cover path or remote image. */
    static String art(String hostPort, String icon, int size) {
        if (icon == null || icon.length() == 0) return null;
        String base = "http://" + hostPort;
        String sz = size + "x" + size;
        if (icon.startsWith("http://") || icon.startsWith("https://")) {
            if (icon.startsWith(base)) return icon;
            try { // let the server fetch and shrink remote art; old phones choke on 640px JPEGs
                return base + "/imageproxy/" + URLEncoder.encode(icon, "UTF-8") + "/image_" + sz + "_p.png";
            } catch (Exception e) {
                return icon;
            }
        }
        String path = icon.startsWith("/") ? icon : "/" + icon;
        if (path.startsWith("/imageproxy/") && path.endsWith("/image.png")) {
            path = path.substring(0, path.length() - "image.png".length()) + "image_" + sz + "_p.png";
        } else if (path.startsWith("/music/")) {
            path = path.replaceAll("cover_\\d+x\\d+", "cover_" + sz);
        }
        return base + path;
    }

    static String localCover(String trackOrCoverId) {
        if (trackOrCoverId == null || trackOrCoverId.length() == 0 || "0".equals(trackOrCoverId)) return null;
        return "/music/" + trackOrCoverId + "/cover_150x150.jpg";
    }

    static TextView text(Context c, float sp, boolean primary, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(primary ? textPrimary(c) : textSecondary(c));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    // ---------- list rows ----------

    static final class Row {
        ImageView art;
        TextView title, sub;
    }

    static View makeRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 16), dp(c, 7), dp(c, 16), dp(c, 7));
        row.setMinimumHeight(dp(c, 64));
        Row h = new Row();
        h.art = new ImageView(c);
        h.art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        row.addView(h.art, new LinearLayout.LayoutParams(dp(c, 50), dp(c, 50)));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(c, 14), 0, 0, 0);
        h.title = text(c, 16, true, false);
        h.sub = text(c, 13, false, false);
        col.addView(h.title);
        col.addView(h.sub);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setTag(h);
        return row;
    }

    static void bindRow(View row, String hostPort, Item it) {
        Row h = (Row) row.getTag();
        h.title.setText(it.title);
        String sub = it.niceSubtitle();
        h.sub.setText(sub);
        h.sub.setVisibility(sub.length() == 0 ? View.GONE : View.VISIBLE);
        boolean circle = it.isArtist();
        placeholder(h.art, circle);
        ImageLoader.load(h.art, art(hostPort, it.icon, 150), circle);
    }

    // ---------- cards and shelves ----------

    static View card(Context c, String hostPort, Item it, boolean circle) {
        int w = dp(c, 132);
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(c, 6), dp(c, 4), dp(c, 6), dp(c, 4));
        if (circle) v.setGravity(Gravity.CENTER_HORIZONTAL);
        selectable(v);
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        placeholder(iv, circle);
        v.addView(iv, new LinearLayout.LayoutParams(w, w));
        ImageLoader.load(iv, art(hostPort, it.icon, 300), circle);
        TextView t = text(c, 14, true, true);
        t.setText(it.title);
        t.setPadding(0, dp(c, 6), 0, 0);
        if (circle) t.setGravity(Gravity.CENTER_HORIZONTAL);
        v.addView(t, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        String subText = it.niceSubtitle();
        if (subText.length() > 0) {
            TextView s = text(c, 12, false, false);
            s.setSingleLine(false);
            s.setMaxLines(2);
            s.setText(subText);
            if (circle) s.setGravity(Gravity.CENTER_HORIZONTAL);
            v.addView(s, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return v;
    }

    /** A section: bold heading (+ optional "See all") above a body that is a list or a shelf. */
    static final class Section {
        LinearLayout root;
        LinearLayout body;
        ViewGroup head;
        boolean hasSeeAll;

        void setSeeAll(View.OnClickListener l) {
            if (hasSeeAll || l == null) return;
            hasSeeAll = true;
            Context c = root.getContext();
            TextView more = text(c, 13, false, true);
            more.setText("See all");
            more.setTextColor(accent(c));
            more.setPadding(dp(c, 8), dp(c, 6), dp(c, 12), dp(c, 6));
            selectable(more);
            more.setOnClickListener(l);
            if (head instanceof android.widget.FrameLayout) {
                head.addView(more, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
                more.setGravity(Gravity.CENTER_VERTICAL);
            } else {
                head.addView(more);
            }
        }

        void hide() { root.setVisibility(View.GONE); }

        void show() { root.setVisibility(View.VISIBLE); }
    }

    static Section section(Context c, String title, View.OnClickListener seeAll, boolean shelf) {
        Section s = new Section();
        s.root = new LinearLayout(c);
        s.root.setOrientation(LinearLayout.VERTICAL);
        ViewGroup sep = Skin.separator(c, title);
        if (sep != null) {
            // Sense: HTC's own section bar; it lays out only its own text, so "See all" sits on top of it
            android.widget.FrameLayout wrap = new android.widget.FrameLayout(c);
            wrap.addView(sep, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            s.head = wrap;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(c, 6);
            s.root.addView(wrap, lp);
        } else {
            LinearLayout head = new LinearLayout(c);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(dp(c, 16), dp(c, 18), dp(c, 8), dp(c, 6));
            TextView h = text(c, 20, true, true);
            h.setText(title);
            head.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            s.head = head;
            s.root.addView(head);
        }
        s.setSeeAll(seeAll);
        s.body = new LinearLayout(c);
        if (shelf) {
            s.body.setOrientation(LinearLayout.HORIZONTAL);
            s.body.setPadding(dp(c, 10), 0, dp(c, 10), 0);
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(s.body);
            s.root.addView(hs);
        } else {
            s.body.setOrientation(LinearLayout.VERTICAL);
            s.root.addView(s.body);
        }
        return s;
    }

    static TextView message(Context c, String msg) {
        TextView t = text(c, 15, false, false);
        t.setSingleLine(false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 24), dp(c, 48), dp(c, 24), dp(c, 24));
        t.setText(msg);
        return t;
    }
}
