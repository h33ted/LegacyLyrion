package com.h33ted.legacylyrion.retro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * HTTP Basic login for password-protected Lyrion servers, kept per server (host:port) on the
 * phone and sent only to that server.
 */
final class Auth {

    /** The server asked for a Basic login (none stored, or a wrong one). */
    static final class Required extends IOException {
        final String hostPort;
        final boolean hadLogin;

        Required(String hostPort, boolean hadLogin) {
            super(hadLogin ? "wrong username or password" : "this server needs a username and password");
            this.hostPort = hostPort;
            this.hadLogin = hadLogin;
        }
    }

    private static boolean asking;
    private static String declinedFor;

    private Auth() {}

    private static String key(String hp) {
        return "auth." + (hp == null ? "" : hp.trim().toLowerCase(Locale.US));
    }

    static String header(Context c, String hostPort) {
        String t = Prefs.get(c).getString(key(hostPort) + ".token", null);
        return t == null ? null : "Basic " + t;
    }

    static String user(Context c, String hostPort) {
        return Prefs.get(c).getString(key(hostPort) + ".user", "");
    }

    static void save(Context c, String hostPort, String user, String pass) {
        SharedPreferences.Editor e = Prefs.get(c).edit();
        String k = key(hostPort);
        if (user == null || user.length() == 0) {
            e.remove(k + ".user").remove(k + ".token");
        } else {
            try {
                e.putString(k + ".user", user).putString(k + ".token", Compat.base64((user + ":" + pass).getBytes("UTF-8")));
            } catch (java.io.UnsupportedEncodingException x) {
                throw new RuntimeException(x);
            }
        }
        e.commit();
        declinedFor = null;
    }

    static String hostPort(URL u) {
        return u.getHost() + ":" + (u.getPort() == -1 ? 80 : u.getPort());
    }

    static void apply(Context c, HttpURLConnection conn) {
        String h = header(c, hostPort(conn.getURL()));
        if (h != null) conn.setRequestProperty("Authorization", h);
    }

    /** Throws {@link Required} on a 401; returns the response code otherwise. */
    static int check(Context c, HttpURLConnection conn) throws IOException {
        int code;
        try {
            code = conn.getResponseCode();
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage()).toLowerCase(Locale.US);
            if (m.indexOf("authentication") < 0 && m.indexOf("401") < 0) throw e;
            code = 401;
        }
        if (code != 401) return code;
        String hp = hostPort(conn.getURL());
        throw new Required(hp, header(c, hp) != null);
    }

    /** A request to the configured server needed a login: ask once on the screen in front. */
    static void onRequired(Required r) {
        Activity a = RetroActivity.current();
        if (a == null || a.isFinishing() || asking) return;
        if (!r.hostPort.equalsIgnoreCase(Prefs.hostPort(a)) || r.hostPort.equalsIgnoreCase(declinedFor)) return;
        final Activity screen = a;
        prompt(a, r.hostPort, r, new Runnable() {
            public void run() {
                if (screen instanceof RetroActivity) ((RetroActivity) screen).reload();
            }
        });
    }

    static void prompt(final Activity a, final String hostPort, Required why, final Runnable saved) {
        if (asking) return;
        asking = true;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Compat.dp(a, 12);
        box.setPadding(p, p / 2, p, 0);
        TextView msg = new TextView(a);
        msg.setText(why == null ? "Username and password for " + hostPort + ", if it is password protected."
                : why.hadLogin ? "The username or password for " + hostPort + " was not accepted."
                : hostPort + " is password protected. They are kept on this phone only.");
        final EditText user = new EditText(a);
        user.setHint("Username");
        user.setSingleLine(true);
        user.setText(user(a, hostPort));
        final EditText pass = new EditText(a);
        pass.setHint("Password");
        pass.setSingleLine(true);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        box.addView(msg);
        box.addView(user);
        box.addView(pass);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("Server login")
                .setView(box)
                .setPositiveButton("Log in", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        asking = false;
                        save(a, hostPort, user.getText().toString().trim(), pass.getText().toString());
                        if (saved != null) saved.run();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        asking = false;
                        declinedFor = hostPort;
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface d) {
                        asking = false;
                    }
                });
        if (header(a, hostPort) != null) {
            b.setNeutralButton("Remove", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    asking = false;
                    save(a, hostPort, "", "");
                    if (saved != null) saved.run();
                }
            });
        }
        b.show();
    }
}
