package com.h33ted.legacylyrion;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Self-update. Two sources:
 *  - SERVER (default): the LegacyLyrion Updates plugin on the user's own Lyrion server, over
 *    its plain-HTTP port (legacylyrion/update/android.json). Works on every Android version,
 *    because it needs no TLS: phones before Android 9/10 mostly can't reach GitHub any more
 *    (old certificate stores and TLS stacks).
 *  - GITHUB (advanced, Settings > Updates from): the latest release of {@link #REPO} directly.
 * An update is offered when the published versionCode is higher than this build's; the
 * download is checked against the SHA-256 the plugin publishes before it is installed.
 * Checked once a day from Home, or on demand from the menu.
 */
final class Updater {
    static final String REPO = "h33ted/LegacyLyrion";
    static final String DEFAULT_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    static final String CHANNEL = "android";
    static final String SOURCE_SERVER = "server", SOURCE_GITHUB = "github";
    private static final long DAY = 24L * 60 * 60 * 1000;

    private Updater() {}

    static String source(Context c) {
        return Prefs.get(c).getString("update_source", SOURCE_SERVER);
    }

    static void checkDaily(Activity a) {
        long last = Prefs.get(a).getLong("update_checked", 0);
        if (System.currentTimeMillis() - last < DAY) return;
        Prefs.get(a).edit().putLong("update_checked", System.currentTimeMillis()).commit();
        check(a, false);
    }

    /** What a source offers: version name, versionCode (0 = compare names), download, checksum, notes. */
    private static final class Offer {
        String version = "", url, sha256, notes = "";
        int code;
    }

    static void check(final Activity a, final boolean manual) {
        final boolean server = !SOURCE_GITHUB.equals(source(a));
        final String hostPort = Prefs.hostPort(a);
        if (manual) Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show();
        new AsyncTask<Void, Void, Offer>() {
            Exception err;
            boolean noPlugin;

            @Override
            protected Offer doInBackground(Void... v) {
                try {
                    return server ? fromServer(hostPort) : fromGithub(Prefs.get(a).getString("update_url", DEFAULT_URL));
                } catch (NoPlugin e) {
                    noPlugin = true;
                    return null;
                } catch (Exception e) {
                    err = e;
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Offer o) {
                if (a.isFinishing()) return;
                if (noPlugin) {
                    if (manual) explainNoPlugin(a, hostPort);
                    return;
                }
                if (o == null) {
                    if (manual) Toast.makeText(a, "Update check failed: " + reason(err, server), Toast.LENGTH_LONG).show();
                    return;
                }
                boolean newer = o.code > 0 ? o.code > versionCode(a) : compare(o.version, version(a)) > 0;
                if (o.url == null || !newer) {
                    if (manual) Toast.makeText(a, "You're on the latest version (" + version(a) + ")", Toast.LENGTH_SHORT).show();
                    return;
                }
                offer(a, o);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static final class NoPlugin extends Exception {}

    /** legacylyrion/update/android.json from the LegacyLyrion Updates plugin. */
    private static Offer fromServer(String hostPort) throws Exception {
        String url = "http://" + hostPort + "/legacylyrion/update/" + CHANNEL + ".json";
        JSONObject j;
        try {
            j = new JSONObject(httpGet(url, null));
        } catch (HttpError e) {
            if (e.code == 404) throw new NoPlugin();
            throw e;
        }
        Offer o = new Offer();
        if (j.isNull("version")) return o;   // plugin installed, nothing published yet
        o.version = j.optString("version", "");
        o.code = j.optInt("code", 0);
        o.url = j.optString("url", null);
        o.sha256 = j.optString("sha256", null);
        o.notes = j.optString("notes", "");
        return o;
    }

    /** The latest GitHub release with an APK that isn't LegacyLyrion Retro's. */
    private static Offer fromGithub(String url) throws Exception {
        JSONObject rel = new JSONObject(httpGet(url, "application/vnd.github+json"));
        Offer o = new Offer();
        o.version = rel.optString("tag_name", "").replaceFirst("^[vV]", "");
        o.notes = rel.optString("body", "");
        JSONArray assets = rel.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject as = assets.optJSONObject(i);
            String u = as == null ? null : as.optString("browser_download_url", null);
            // LegacyLyrion Retro (Android 1.5 - 2.x) is a separate app: never offer its APK here
            if (u != null && u.toLowerCase().endsWith(".apk") && u.toLowerCase().indexOf("retro") < 0) {
                o.url = u;
                break;
            }
        }
        return o;
    }

    private static void explainNoPlugin(Activity a, String hostPort) {
        new AlertDialog.Builder(a)
                .setTitle("Updates")
                .setMessage("Your Lyrion server (" + hostPort + ") doesn't have the LegacyLyrion Updates plugin, "
                        + "which is where this app gets its updates. Install the plugin on the server, or, on Android 9 "
                        + "and newer, switch Settings > Updates from to GitHub.")
                .setPositiveButton("OK", null)
                .show();
    }

    private static void offer(final Activity a, final Offer o) {
        String msg = "Version " + o.version + " is available (you have " + version(a) + ").";
        String notes = o.notes == null ? "" : o.notes.trim();
        if (notes.length() > 0) msg += "\n\n" + (notes.length() > 600 ? notes.substring(0, 600) + "…" : notes);
        new AlertDialog.Builder(a)
                .setTitle("Update LegacyLyrion")
                .setMessage(msg)
                .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        download(a, o.url, o.sha256);
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    @SuppressWarnings("deprecation")
    private static void download(final Activity a, final String apkUrl, final String sha256) {
        final ProgressDialog pd = new ProgressDialog(a);
        pd.setMessage("Downloading update…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setCancelable(false);
        pd.show();
        new AsyncTask<Void, Integer, File>() {
            Exception err;

            @Override
            protected File doInBackground(Void... v) {
                try {
                    HttpURLConnection c = open(apkUrl, null);
                    int total = c.getContentLength();
                    InputStream in = c.getInputStream();
                    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                    // world-readable so the system package installer can open it (no storage permission needed)
                    FileOutputStream out = a.openFileOutput("update.apk", Context.MODE_WORLD_READABLE);
                    byte[] buf = new byte[16384];
                    int n, done = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                        done += n;
                        if (total > 0) publishProgress(done * 100 / total);
                    }
                    out.close();
                    in.close();
                    c.disconnect();
                    File f = a.getFileStreamPath("update.apk");
                    if (sha256 != null && sha256.length() > 0 && !sha256.equalsIgnoreCase(hex(md.digest()))) {
                        f.delete();
                        throw new Exception("the download is damaged (checksum mismatch), try again");
                    }
                    return f;
                } catch (Exception e) {
                    err = e;
                    return null;
                }
            }

            @Override
            protected void onProgressUpdate(Integer... p) {
                pd.setProgress(p[0]);
            }

            @Override
            protected void onPostExecute(File f) {
                try {
                    pd.dismiss();
                } catch (Exception ignored) {}
                if (f == null) {
                    Toast.makeText(a, "Download failed: " + reason(err, true), Toast.LENGTH_LONG).show();
                    return;
                }
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(Integer.toHexString((x & 0xff) | 0x100).substring(1));
        return sb.toString();
    }

    static int versionCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------

    static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** Compares dotted versions, ignoring a leading "v" and any suffix like "-beta". */
    static int compare(String a, String b) {
        String[] x = a.replaceFirst("^[vV]", "").split("[^0-9]+");
        String[] y = b.replaceFirst("^[vV]", "").split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && x[i].length() > 0 ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && y[i].length() > 0 ? Integer.parseInt(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static String reason(Exception e, boolean server) {
        if (e == null) return "unknown error";
        if (!server && e instanceof javax.net.ssl.SSLException)
            return "Android " + Build.VERSION.RELEASE + " can't connect to GitHub securely; use the Lyrion server in Settings > Updates from";
        if (e instanceof Auth.Required) return "the server needs a login (menu > Server login)";
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static final class HttpError extends Exception {
        final int code;

        HttpError(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private static String httpGet(String url, String accept) throws Exception {
        HttpURLConnection c = open(url, accept);
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        c.disconnect();
        return sb.toString();
    }

    /** Opens a connection, following redirects across hosts (GitHub sends assets to a CDN host). */
    private static HttpURLConnection open(String url, String accept) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            if (accept != null) c.setRequestProperty("Accept", accept);
            Auth.apply(c);   // the Lyrion server's login, if it has one (only ever sent to that server)
            if (c instanceof HttpsURLConnection && Build.VERSION.SDK_INT >= 16 && Build.VERSION.SDK_INT < 20) {
                ((HttpsURLConnection) c).setSSLSocketFactory(Tls12.factory());
            }
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "LegacyLyrion");
            int code = Auth.check(c);
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) throw new HttpError(code);
            return c;
        }
        throw new Exception("too many redirects");
    }

    /** Enables TLS 1.1/1.2 on Android 4.1-4.4, where they exist but are off by default. */
    static final class Tls12 extends SSLSocketFactory {
        private static Tls12 instance;
        private final SSLSocketFactory base;

        private Tls12(SSLSocketFactory base) {
            this.base = base;
        }

        static synchronized SSLSocketFactory factory() throws Exception {
            if (instance == null) {
                SSLContext ctx = SSLContext.getInstance("TLSv1.2");
                ctx.init(null, null, null);
                instance = new Tls12(ctx.getSocketFactory());
            }
            return instance;
        }

        private Socket patch(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ss = (SSLSocket) s;
                List<String> want = new ArrayList<String>();
                List<String> supported = Arrays.asList(ss.getSupportedProtocols());
                for (String p : new String[]{"TLSv1.2", "TLSv1.1", "TLSv1"}) if (supported.contains(p)) want.add(p);
                ss.setEnabledProtocols(want.toArray(new String[want.size()]));
            }
            return s;
        }

        @Override
        public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }

        @Override
        public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws java.io.IOException {
            return patch(base.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws java.io.IOException {
            return patch(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress local, int lport) throws java.io.IOException {
            return patch(base.createSocket(host, port, local, lport));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws java.io.IOException {
            return patch(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress a, int port, InetAddress local, int lport) throws java.io.IOException {
            return patch(base.createSocket(a, port, local, lport));
        }
    }
}
