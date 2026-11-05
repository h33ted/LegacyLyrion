package com.h33ted.legacylyrion.retro;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.widget.Toast;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Self-update from the LegacyLyrion Updates plugin on the user's own Lyrion server
 * (legacylyrion/update/retro.json, plain HTTP). Phones on Android 1.5 - 2.x can't reach GitHub
 * at all any more (their TLS and certificates are too old), so the server is the only source.
 * Offered when the published versionCode is higher than this build's; the download is checked
 * against the published SHA-256 before the package installer opens it. Checked once a day from
 * Home, or from the menu.
 */
final class Updater {
    static final String CHANNEL = "retro";
    private static final long DAY = 24L * 60 * 60 * 1000;

    private Updater() {}

    static void checkDaily(Activity a) {
        long last = Prefs.get(a).getLong("update_checked", 0);
        if (System.currentTimeMillis() - last < DAY) return;
        Prefs.get(a).edit().putLong("update_checked", System.currentTimeMillis()).commit();
        check(a, false);
    }

    static void check(final Activity a, final boolean manual) {
        final String hostPort = Prefs.hostPort(a);
        final Context app = a.getApplicationContext();
        if (manual) Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show();
        new AsyncTask<Void, Void, JSONObject>() {
            Exception err;
            int status;

            @Override
            protected JSONObject doInBackground(Void... v) {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL("http://" + hostPort + "/legacylyrion/update/" + CHANNEL + ".json").openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(15000);
                    Auth.apply(app, c);
                    status = Auth.check(app, c);
                    if (status != 200) return null;
                    return new JSONObject(new String(readAll(c.getInputStream()), "UTF-8"));
                } catch (Exception e) {
                    err = e;
                    return null;
                } finally {
                    if (c != null) c.disconnect();
                }
            }

            @Override
            protected void onPostExecute(JSONObject j) {
                if (a.isFinishing()) return;
                if (status == 404) {
                    if (manual) {
                        new AlertDialog.Builder(a)
                                .setTitle("Updates")
                                .setMessage("Your Lyrion server (" + hostPort + ") doesn't have the LegacyLyrion Updates "
                                        + "plugin, which is where this app gets its updates. Install the plugin on the server.")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                    return;
                }
                if (j == null) {
                    if (manual) {
                        String why = err instanceof Auth.Required ? "the server needs a login (menu > Server login)"
                                : err != null && err.getMessage() != null ? err.getMessage() : "HTTP " + status;
                        Toast.makeText(a, "Update check failed: " + why, Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                int code = j.optInt("code", 0);
                String url = j.isNull("version") ? null : j.optString("url", null);
                if (url == null || code <= versionCode(a)) {
                    if (manual) Toast.makeText(a, "You're on the latest version (" + version(a) + ")", Toast.LENGTH_SHORT).show();
                    return;
                }
                offer(a, j.optString("version", ""), j.optString("notes", ""), url, j.optString("sha256", ""));
            }
        }.execute();
    }

    private static void offer(final Activity a, String version, String notes, final String url, final String sha256) {
        String msg = "Version " + version + " is available (you have " + version(a) + ").";
        notes = notes == null ? "" : notes.trim();
        if (notes.length() > 0) msg += "\n\n" + (notes.length() > 600 ? notes.substring(0, 600) + "…" : notes);
        new AlertDialog.Builder(a)
                .setTitle("Update LegacyLyrion Retro")
                .setMessage(msg)
                .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        download(a, url, sha256);
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    private static void download(final Activity a, final String url, final String sha256) {
        final ProgressDialog pd = new ProgressDialog(a);
        pd.setMessage("Downloading update…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setCancelable(false);
        pd.show();
        final Context app = a.getApplicationContext();
        new AsyncTask<Void, Integer, File>() {
            Exception err;

            @Override
            protected File doInBackground(Void... v) {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(30000);
                    Auth.apply(app, c);
                    int status = Auth.check(app, c);
                    if (status != 200) throw new Exception("HTTP " + status);
                    int total = c.getContentLength();
                    InputStream in = c.getInputStream();
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    // world-readable, so the package installer can open it without storage permission
                    FileOutputStream out = a.openFileOutput("update.apk", Context.MODE_WORLD_READABLE);
                    byte[] buf = new byte[8192];
                    int n, done = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                        done += n;
                        if (total > 0) publishProgress(done * 100 / total);
                    }
                    out.close();
                    in.close();
                    File f = a.getFileStreamPath("update.apk");
                    if (sha256 != null && sha256.length() > 0 && !sha256.equalsIgnoreCase(hex(md.digest()))) {
                        f.delete();
                        throw new Exception("the download is damaged (checksum mismatch), try again");
                    }
                    return f;
                } catch (Exception e) {
                    err = e;
                    return null;
                } finally {
                    if (c != null) c.disconnect();
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
                    Toast.makeText(a, "Download failed: " + (err != null && err.getMessage() != null ? err.getMessage() : "error"),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
            }
        }.execute();
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    private static String hex(byte[] b) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < b.length; i++) sb.append(Integer.toHexString((b[i] & 0xff) | 0x100).substring(1));
        return sb.toString();
    }

    static int versionCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
