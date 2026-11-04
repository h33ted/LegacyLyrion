package com.h33ted.legacylyrion;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP Basic authentication for Lyrion servers behind a password (Lyrion's own "Password
 * protection", or a reverse proxy in front of it).
 *
 * The login is stored per server (host:port) in the app's private preferences and sent as
 * "Authorization: Basic base64(user:password)" with every request to that server only: JSON-RPC,
 * artwork, and the phone player's audio stream. A server that wants a login answers 401 with
 * "WWW-Authenticate: Basic realm=...", which {@link #check} turns into {@link Required}; the app
 * then asks for the username and password ({@link #onRequired}).
 */
final class Auth {

    /** Thrown when the server asked for a Basic login (none stored, or a wrong one). */
    static final class Required extends IOException {
        final String hostPort;
        final String realm;
        final boolean hadLogin;

        Required(String hostPort, String realm, boolean hadLogin) {
            super(hadLogin ? "wrong username or password" : "this server needs a username and password");
            this.hostPort = hostPort;
            this.realm = realm;
            this.hadLogin = hadLogin;
        }
    }

    private static final String FILE = Prefs.FILE;
    private static final Map<String, String> HEADERS = new HashMap<String, String>();
    private static boolean loaded;
    private static WeakReference<Activity> current = new WeakReference<Activity>(null);
    private static boolean asking;
    private static String declinedFor;

    private Auth() {}

    // ------------------------------------------------------------------ storage

    private static String key(String hostPort) {
        return "auth." + norm(hostPort);
    }

    private static String norm(String hostPort) {
        return hostPort == null ? "" : hostPort.trim().toLowerCase(java.util.Locale.US);
    }

    /** Loads stored logins once and starts tracking the visible screen (for the login prompt). */
    static synchronized void init(Context c) {
        if (loaded) return;
        loaded = true;
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            if (e.getKey().startsWith("auth.") && e.getKey().endsWith(".token")) {
                String hp = e.getKey().substring(5, e.getKey().length() - 6);
                HEADERS.put(hp, "Basic " + e.getValue());
            }
        }
        Context app = c.getApplicationContext();
        if (app instanceof Application) {
            // UI size: the system resets the density on rotation; put it back and redraw
            ((Application) app).registerComponentCallbacks(new android.content.ComponentCallbacks() {
                @Override
                public void onConfigurationChanged(android.content.res.Configuration cfg) {
                    final Activity a = current.get();
                    if (a == null || Skin.scale(a) == 100) return;
                    a.getWindow().getDecorView().post(new Runnable() {
                        @Override
                        public void run() {
                            Skin.applyScale(a);
                            a.recreate();
                        }
                    });
                }

                @Override
                public void onLowMemory() {}
            });
            ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(Activity a) {
                    current = new WeakReference<Activity>(a);
                }

                @Override
                public void onActivityPaused(Activity a) {
                    if (current.get() == a) current = new WeakReference<Activity>(null);
                }

                @Override
                public void onActivityCreated(Activity a, Bundle b) {}

                @Override
                public void onActivityStarted(Activity a) {}

                @Override
                public void onActivityStopped(Activity a) {}

                @Override
                public void onActivitySaveInstanceState(Activity a, Bundle b) {}

                @Override
                public void onActivityDestroyed(Activity a) {}
            });
        }
    }

    static String user(Context c, String hostPort) {
        return Prefs.get(c).getString(key(hostPort) + ".user", "");
    }

    static boolean has(String hostPort) {
        synchronized (Auth.class) {
            return HEADERS.containsKey(norm(hostPort));
        }
    }

    /** Stores a login for a server; an empty username removes it. */
    static void save(Context c, String hostPort, String user, String password) {
        SharedPreferences.Editor e = Prefs.get(c).edit();
        String k = key(hostPort);
        synchronized (Auth.class) {
            if (user == null || user.length() == 0) {
                e.remove(k + ".user").remove(k + ".token");
                HEADERS.remove(norm(hostPort));
            } else {
                String token;
                try {
                    token = Base64.encodeToString((user + ":" + password).getBytes("UTF-8"), Base64.NO_WRAP);
                } catch (java.io.UnsupportedEncodingException x) {
                    throw new RuntimeException(x);
                }
                e.putString(k + ".user", user).putString(k + ".token", token);
                HEADERS.put(norm(hostPort), "Basic " + token);
            }
        }
        e.commit();
        declinedFor = null;
    }

    /** The Authorization header value for a server, or null. */
    static String header(String hostPort) {
        synchronized (Auth.class) {
            return HEADERS.get(norm(hostPort));
        }
    }

    private static String hostPort(URL u) {
        int port = u.getPort() == -1 ? u.getDefaultPort() : u.getPort();
        return u.getHost() + ":" + port;
    }

    // ------------------------------------------------------------------ HTTP

    /** Adds the stored login, if any, for the server this connection goes to. */
    static void apply(HttpURLConnection c) {
        String h = header(hostPort(c.getURL()));
        if (h != null) c.setRequestProperty("Authorization", h);
    }

    /**
     * Throws {@link Required} if the server answered 401 with a Basic challenge. Call after the
     * request was sent; returns the response code otherwise.
     */
    static int check(HttpURLConnection c) throws IOException {
        int code;
        try {
            code = c.getResponseCode();
        } catch (IOException e) {
            // Android 4.x throws instead of returning 401 when it can't parse the challenge
            String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.US);
            if (!m.contains("authentication")) throw e;
            code = HttpURLConnection.HTTP_UNAUTHORIZED;
        }
        if (code != HttpURLConnection.HTTP_UNAUTHORIZED) return code;
        String challenge = null;
        Map<String, List<String>> fields = c.getHeaderFields();
        if (fields != null) {
            for (Map.Entry<String, List<String>> f : fields.entrySet()) {
                if (f.getKey() != null && "www-authenticate".equalsIgnoreCase(f.getKey()) && !f.getValue().isEmpty()) {
                    challenge = f.getValue().get(0);
                }
            }
        }
        String hp = hostPort(c.getURL());
        if (challenge == null || challenge.trim().toLowerCase(java.util.Locale.US).startsWith("basic")) {
            String realm = null;
            if (challenge != null) {
                int i = challenge.toLowerCase(java.util.Locale.US).indexOf("realm=");
                if (i >= 0) realm = challenge.substring(i + 6).replace("\"", "").trim();
            }
            throw new Required(hp, realm, has(hp));
        }
        throw new IOException("HTTP 401 (unsupported login type: " + challenge + ")");
    }

    // ------------------------------------------------------------------ prompt

    /**
     * Called (on the UI thread) when any request to the configured server needed a login: asks
     * once on the visible screen and reloads that screen after saving. "Cancel" stops asking
     * until the app is restarted or a login is entered from the menu.
     */
    static void onRequired(Required r) {
        Activity a = current.get();
        if (a == null || a.isFinishing() || asking) return;
        if (!norm(r.hostPort).equals(norm(Prefs.hostPort(a)))) return;   // setup screen handles its own
        if (norm(r.hostPort).equals(declinedFor)) return;
        final Activity screen = a;
        prompt(a, r.hostPort, r, new Runnable() {
            @Override
            public void run() {
                screen.recreate();
            }
        });
    }

    /** Username/password dialog for a server; runs {@code saved} after the login is stored. */
    static void prompt(final Activity a, final String hostPort, Required why, final Runnable saved) {
        if (asking) return;
        asking = true;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 20);
        box.setPadding(p, Ui.dp(a, 8), p, 0);
        TextView msg = Ui.text(a, 14, false, false);
        String where = why != null && why.realm != null && why.realm.length() > 0 ? " (" + why.realm + ")" : "";
        msg.setText(why == null
                ? "Username and password for " + hostPort + ", if it is password protected. They are kept on this phone only."
                : why.hadLogin
                ? "The username or password for " + hostPort + where + " was not accepted."
                : hostPort + where + " is password protected. Enter its username and password; they are kept on this phone only.");
        final EditText user = Skin.editText(a);
        user.setHint("Username");
        user.setSingleLine(true);
        user.setText(user(a, hostPort));
        final EditText pass = Skin.editText(a);
        pass.setHint("Password");
        pass.setSingleLine(true);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(msg);
        box.addView(user);
        box.addView(pass);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("Server login")
                .setView(box)
                .setPositiveButton("Log in", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        save(a, hostPort, user.getText().toString().trim(), pass.getText().toString());
                        if (saved != null) saved.run();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        declinedFor = norm(hostPort);
                    }
                });
        if (has(hostPort)) {
            b.setNeutralButton("Remove login", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    save(a, hostPort, "", "");
                    if (saved != null) saved.run();
                }
            });
        }
        AlertDialog dlg = b.create();
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                asking = false;
            }
        });
        dlg.show();
    }
}
