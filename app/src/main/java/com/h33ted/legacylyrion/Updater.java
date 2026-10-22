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
 * Self-update from GitHub releases. Looks at the latest release of {@link #REPO}; if its tag
 * (e.g. "v1.1.0") is newer than this build's versionName and it has an .apk asset, offers to
 * download and install it. Checked once a day from Home, or on demand from the menu.
 *
 * Old Android caveats: 4.1-4.3 support TLS 1.2 but leave it off, so it is switched on here.
 * 4.0 has no TLS 1.2 at all and cannot reach GitHub; the update URL can be pointed at a
 * plain-HTTP mirror instead (Prefs key "update_url", same JSON shape as the GitHub API).
 */
final class Updater {
    static final String REPO = "h33ted/LegacyLyrion";
    static final String DEFAULT_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long DAY = 24L * 60 * 60 * 1000;

    private Updater() {}

    static void checkDaily(Activity a) {
        long last = Prefs.get(a).getLong("update_checked", 0);
        if (System.currentTimeMillis() - last < DAY) return;
        Prefs.get(a).edit().putLong("update_checked", System.currentTimeMillis()).commit();
        check(a, false);
    }

    static void check(final Activity a, final boolean manual) {
        final String url = Prefs.get(a).getString("update_url", DEFAULT_URL);
        if (manual) Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show();
        new AsyncTask<Void, Void, JSONObject>() {
            Exception err;

            @Override
            protected JSONObject doInBackground(Void... v) {
                try {
                    return new JSONObject(httpGet(url));
                } catch (Exception e) {
                    err = e;
                    return null;
                }
            }

            @Override
            protected void onPostExecute(JSONObject rel) {
                if (a.isFinishing()) return;
                if (rel == null) {
                    if (manual) Toast.makeText(a, "Update check failed: " + reason(err), Toast.LENGTH_LONG).show();
                    return;
                }
                String tag = rel.optString("tag_name", "");
                String apk = null;
                JSONArray assets = rel.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject as = assets.optJSONObject(i);
                    String u = as == null ? null : as.optString("browser_download_url", null);
                    if (u != null && u.toLowerCase().endsWith(".apk")) {
                        apk = u;
                        break;
                    }
                }
                String mine = version(a);
                if (apk == null || compare(tag, mine) <= 0) {
                    if (manual) Toast.makeText(a, "You're on the latest version (" + mine + ")", Toast.LENGTH_SHORT).show();
                    return;
                }
                offer(a, tag, rel.optString("body", ""), apk);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static void offer(final Activity a, String tag, String notes, final String apkUrl) {
        String msg = "Version " + tag.replaceFirst("^[vV]", "") + " is available (you have " + version(a) + ").";
        if (notes.length() > 0) msg += "\n\n" + (notes.length() > 600 ? notes.substring(0, 600) + "…" : notes);
        new AlertDialog.Builder(a)
                .setTitle("Update LegacyLyrion")
                .setMessage(msg)
                .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        download(a, apkUrl);
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    @SuppressWarnings("deprecation")
    private static void download(final Activity a, final String apkUrl) {
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
                    HttpURLConnection c = open(apkUrl);
                    int total = c.getContentLength();
                    InputStream in = c.getInputStream();
                    // world-readable so the system package installer can open it (no storage permission needed)
                    FileOutputStream out = a.openFileOutput("update.apk", Context.MODE_WORLD_READABLE);
                    byte[] buf = new byte[16384];
                    int n, done = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) publishProgress(done * 100 / total);
                    }
                    out.close();
                    in.close();
                    c.disconnect();
                    return a.getFileStreamPath("update.apk");
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
                    Toast.makeText(a, "Download failed: " + reason(err), Toast.LENGTH_LONG).show();
                    return;
                }
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
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

    private static String reason(Exception e) {
        if (e == null) return "unknown error";
        if (Build.VERSION.SDK_INT < 16 && e instanceof javax.net.ssl.SSLException)
            return "Android " + Build.VERSION.RELEASE + " can't connect to GitHub securely";
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = open(url);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        c.disconnect();
        return sb.toString();
    }

    /** Opens a connection, following redirects across hosts (GitHub sends assets to a CDN host). */
    private static HttpURLConnection open(String url) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            if (c instanceof HttpsURLConnection && Build.VERSION.SDK_INT >= 16 && Build.VERSION.SDK_INT < 20) {
                ((HttpsURLConnection) c).setSSLSocketFactory(Tls12.factory());
            }
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "LegacyLyrion");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) throw new Exception("HTTP " + code);
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
