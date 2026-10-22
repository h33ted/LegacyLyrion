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
        ActionBar ab = a.getActionBar();
        if (ab != null) {
            ab.setTitle(title);
            ab.setSubtitle(subtitle);
            ab.setDisplayHomeAsUpEnabled(up);
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

    /** Sense section bar (dark strip with caps label), or null off HTC. */
    static ViewGroup separator(Context c, String label) {
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
