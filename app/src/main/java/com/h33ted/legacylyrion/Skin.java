package com.h33ted.legacylyrion;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * Makes the app look native on the phone's own skin.
 *
 * Sony, Samsung TouchWiz and LG restyle Theme.DeviceDefault, so the manifest theme already
 * follows them. HTC Sense 5 leaves DeviceDefault as Holo and keeps Sense in the "com.htc"
 * resource package (style HtcDeviceDefault, drawable common_app_bkg) plus widgets in
 * HTCCommonctrl.jar on the boot classpath (ActionBarExt, ActionBarText, HtcEditText,
 * HtcListItemSeparator). Those classes exist only on HTC ROMs, so they are reached by
 * reflection and everything falls back to stock widgets elsewhere.
 * (Approach from h33ted/htc-revive.)
 *
 * Sony Xperia (2011-2013 ROMs) keeps its UXP look in SemcGenericUxpRes.apk, resource package
 * "com.sonyericsson.uxp", loaded into every app. From it the app takes the theme accent colour
 * (semc_theme_accent_color, which follows the colour theme picked in the phone's settings) and
 * the dark carbon background (semc_bg); Now Playing then follows Sony's Walkman player.
 * Nothing of Sony's is shipped in the APK: it is all read from the phone at runtime.
 */
final class Skin {
    static String status = "Phone default";
    private static Boolean sense;
    private static final WeakHashMap<Activity, Object> TITLES = new WeakHashMap<Activity, Object>();

    private Skin() {}

    /**
     * Look setting: "auto" (the maker's skin; Sense dark on HTC, LG White on LG, DeviceDefault
     * elsewhere), "light" (Sense light on HTC, DeviceDefault light elsewhere), or plain
     * "holo_light" / "holo_dark" on any phone. Older "dark" values count as "auto".
     */
    static String look(Context c) {
        return Prefs.get(c).getString("look", "auto");
    }

    private static boolean holo(Context c) {
        return look(c).startsWith("holo_");
    }

    /** True on HTC Sense ROMs (theme and widgets present), whatever look is picked. */
    static boolean senseAvailable(Context c) {
        if (sense == null) {
            sense = id(c, "HtcDeviceDefault", "style") != 0 && cls("com.htc.widget.ActionBarExt") != null;
        }
        return sense;
    }

    /** True when the Sense look is in use (HTC ROM and no plain Holo picked). */
    static boolean sense(Context c) {
        return senseAvailable(c) && !holo(c);
    }

    /** Sense light was picked; on HTC the default ("auto") is Sense dark. */
    private static boolean senseLight(Context c) {
        return "light".equals(look(c));
    }

    /** Before super.onCreate(). */
    static void apply(Activity a) {
        String look = look(a);
        if ("holo_light".equals(look)) {
            a.setTheme(android.R.style.Theme_Holo_Light);
            status = "Holo light";
            return;
        }
        if ("holo_dark".equals(look)) {
            a.setTheme(android.R.style.Theme_Holo);
            status = "Holo dark";
            return;
        }
        if (sense(a)) {
            a.setTheme(id(a, "HtcDeviceDefault", "style"));
            if (senseLight(a)) a.getTheme().applyStyle(R.style.SenseLightText, true);
            status = senseLight(a) ? "HTC Sense light" : "HTC Sense dark";
            return;
        }
        // LG: Optimus UI keeps its themes in lge-res.apk (package "com.lge.internal").
        // Theme.LGE.Default is what LG's own apps use; White/Black are aliases a theme pack may fill.
        if (lg(a)) {
            a.setTheme(id(a, "Theme.LGE.Default", "style", LG_PKG));
            status = "LG White";
            return;
        }
        if (sony(a)) {
            status = "Xperia";   // DeviceDefault already is Sony's theme; decorate() adds the rest
            return;
        }
        if ("light".equals(look)) {
            a.setTheme(android.R.style.Theme_DeviceDefault_Light_DarkActionBar);
            status = "Phone default (light)";
        } else {
            status = "Phone default";
        }
    }

    /** After setContentView(): window background and title bar in the skin's own style. */
    static void decorate(final Activity a, String title, String subtitle, boolean up) {
        if (sense(a)) {
            int bkg = id(a, senseLight(a) ? "common_app_bkg" : "common_app_bkg_dark", "drawable");
            if (bkg != 0) a.getWindow().setBackgroundDrawableResource(bkg);
            try {
                Object ext = cls("com.htc.widget.ActionBarExt")
                        .getConstructor(Context.class, ActionBar.class).newInstance(a, a.getActionBar());
                Object container = ext.getClass().getMethod("getCustomContainer").invoke(ext);
                View text = (View) cls("com.htc.widget.ActionBarText").getConstructor(Context.class).newInstance(a);
                call(text, "setPrimaryText", title);
                if (subtitle != null) call(text, "setSecondaryText", subtitle);
                container.getClass().getMethod("addCenterView", View.class).invoke(container, text);
                if (up) {
                    container.getClass().getMethod("setBackUpEnabled", boolean.class).invoke(container, true);
                    container.getClass().getMethod("setBackUpOnClickListener", View.OnClickListener.class)
                            .invoke(container, new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    a.finish();
                                }
                            });
                }
                TITLES.put(a, text);
                return;
            } catch (Throwable ignored) {
                // fall through to the stock action bar
            }
        }
        if (sony(a)) {
            int bg = id(a, "semc_bg", "drawable", SONY_PKG);
            if (bg != 0) {
                try {
                    a.getWindow().setBackgroundDrawableResource(bg);
                } catch (Throwable ignored) {}
            }
        }
        ActionBar ab = a.getActionBar();
        if (ab != null) {
            if (sony(a) && title != null) {   // bold title, as in Sony's own apps
                android.text.SpannableString t = new android.text.SpannableString(title);
                t.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, t.length(), 0);
                ab.setTitle(t);
            } else {
                ab.setTitle(title);
            }
            ab.setSubtitle(subtitle);
            ab.setDisplayHomeAsUpEnabled(up);
            if (sony(a)) {
                // Sony's own apps show no app icon in the title bar (Sense hides it too). With a
                // back arrow the home area has to stay, so the icon becomes an empty drawable.
                if (up) ab.setIcon(new android.graphics.drawable.ColorDrawable(0));
                else ab.setDisplayShowHomeEnabled(false);
            }
        }
    }

    static void setSubtitle(Activity a, String subtitle) {
        Object t = TITLES.get(a);
        if (t != null) {
            call(t, "setSecondaryText", subtitle == null ? "" : subtitle);
            return;
        }
        if (a.getActionBar() != null) a.getActionBar().setSubtitle(subtitle);
    }

    /** The skin's own section bar: Sense's on HTC, Sony's list header on Xperia; null elsewhere. */
    static ViewGroup separator(Context c, String label) {
        if (sony(c)) {
            // Sony's theme points the standard list-separator style at its own header
            // (SEMCWidget.TextView.ListSeparator), so a plain TextView with that style is native.
            android.widget.TextView t = new android.widget.TextView(c, null, android.R.attr.listSeparatorTextViewStyle);
            t.setText(label);
            android.widget.FrameLayout f = new android.widget.FrameLayout(c);
            f.addView(t, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return f;
        }
        if (!sense(c)) return null;
        try {
            Class<?> k = cls("com.htc.widget.HtcListItemSeparator");
            ViewGroup sep = null;
            if (!senseLight(c)) {
                // dark Sense: the separator has its own dark mode (HtcListItemSeparator.MODE_DARK_STYLE)
                try {
                    int mode = k.getField("MODE_DARK_STYLE").getInt(null);
                    sep = (ViewGroup) k.getConstructor(Context.class, int.class).newInstance(c, mode);
                } catch (Throwable ignored) {}
            }
            if (sep == null) sep = (ViewGroup) k.getConstructor(Context.class).newInstance(c);
            sep.getClass().getMethod("setText", int.class, CharSequence.class).invoke(sep, 0, label);
            return sep;
        } catch (Throwable t) {
            return null;
        }
    }

    /** HtcEditText on Sense, a plain EditText elsewhere. */
    static EditText editText(Context c) {
        if (sense(c)) {
            try {
                return (EditText) cls("com.htc.widget.HtcEditText").getConstructor(Context.class).newInstance(c);
            } catch (Throwable ignored) {}
        }
        return new EditText(c);
    }

    /** Replaces an inflated EditText with the skin's own, keeping id, layout and the usual settings. */
    static EditText swap(EditText old) {
        if (!sense(old.getContext())) return old;
        EditText e = editText(old.getContext());
        if (e.getClass() == EditText.class) return old;
        e.setId(old.getId());
        e.setHint(old.getHint());
        e.setInputType(old.getInputType());
        e.setImeOptions(old.getImeOptions());
        e.setSingleLine(true);
        android.graphics.drawable.Drawable[] d = old.getCompoundDrawables();
        e.setCompoundDrawablesWithIntrinsicBounds(d[0], d[1], d[2], d[3]);
        ViewGroup parent = (ViewGroup) old.getParent();
        int idx = parent.indexOfChild(old);
        ViewGroup.LayoutParams lp = old.getLayoutParams();
        parent.removeViewAt(idx);
        parent.addView(e, idx, lp);
        return e;
    }

    // ------------------------------------------------------------------

    private static final String SONY_PKG = "com.sonyericsson.uxp";
    private static Boolean sonyRes;

    /** True on Sony Xperia ROMs with the UXP resources, whatever look is picked. */
    static boolean sonyAvailable(Context c) {
        if (sonyRes == null) {
            sonyRes = !senseAvailable(c) && id(c, "semc_theme_accent_color", "color", SONY_PKG) != 0;
        }
        return sonyRes;
    }

    /** True when the Xperia look is in use (Sony ROM, the default look). */
    static boolean sony(Context c) {
        return sonyAvailable(c) && !holo(c) && !"light".equals(look(c));
    }

    /** The accent of the colour theme picked in the phone's Sony settings, or 0 if unavailable. */
    static int sonyAccent(Context c) {
        int id = id(c, "semc_theme_accent_color", "color", SONY_PKG);
        if (id == 0) return 0;
        try {
            return c.getResources().getColor(id);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static final String LG_PKG = "com.lge.internal";
    private static Boolean lg;

    /** True on LG Optimus UI ROMs, whatever look is picked. */
    static boolean lgAvailable(Context c) {
        if (lg == null) lg = !senseAvailable(c) && id(c, "Theme.LGE.Default", "style", LG_PKG) != 0;
        return lg;
    }

    /** True when the LG look is in use. */
    static boolean lg(Context c) {
        return lgAvailable(c) && !holo(c);
    }

    private static int id(Context c, String name, String type) {
        return id(c, name, type, "com.htc");
    }

    private static int id(Context c, String name, String type, String pkg) {
        try {
            Resources r = c.getResources();
            return r.getIdentifier(name, type, pkg);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Class<?> cls(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void call(Object o, String method, String arg) {
        try {
            Method m = o.getClass().getMethod(method, String.class);
            m.invoke(o, arg);
        } catch (Throwable ignored) {}
    }

}
