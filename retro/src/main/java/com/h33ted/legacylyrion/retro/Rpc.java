package com.h33ted.legacylyrion.retro;

import android.content.Context;
import android.os.AsyncTask;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Lyrion JSON-RPC (slim.request); the callback runs on the UI thread. */
final class Rpc {
    interface Callback {
        void done(JSONObject result, Exception error);
    }

    static {
        // Android 1.x - 2.1 reuse dead keep-alive sockets and fail every other request
        System.setProperty("http.keepAlive", "false");
    }

    private Rpc() {}

    static JSONArray cmd(String... tokens) {
        JSONArray a = new JSONArray();
        for (String t : tokens) a.put(t);
        return a;
    }

    static JSONObject send(Context c, String hostPort, String player, JSONArray cmd) throws Exception {
        URL url = new URL("http://" + hostPort + "/jsonrpc.js");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Connection", "close");
            conn.setDoOutput(true);
            Auth.apply(c, conn);
            JSONObject payload = new JSONObject();
            payload.put("id", 1);
            payload.put("method", "slim.request");
            JSONArray params = new JSONArray();
            params.put(player == null ? "" : player);
            params.put(cmd);
            payload.put("params", params);
            byte[] body = payload.toString().getBytes("UTF-8");
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.close();
            int code = Auth.check(c, conn);
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONObject r = new JSONObject(new String(bo.toByteArray(), "UTF-8")).optJSONObject("result");
            if (r == null) r = new JSONObject();
            return r;
        } finally {
            conn.disconnect();
        }
    }

    static void call(Context c, JSONArray cmd, Callback cb) {
        call(c, Prefs.player(c), cmd, cb);
    }

    static void call(final Context c, final String player, final JSONArray cmd, final Callback cb) {
        call(c, Prefs.hostPort(c), player, cmd, cb);
    }

    static void call(final Context c, final String hostPort, final String player, final JSONArray cmd, final Callback cb) {
        final Context app = c.getApplicationContext();
        new AsyncTask<Void, Void, JSONObject>() {
            Exception err;

            @Override
            protected JSONObject doInBackground(Void... v) {
                try {
                    return send(app, hostPort, player, cmd);
                } catch (Exception e) {
                    err = e;
                    return null;
                }
            }

            @Override
            protected void onPostExecute(JSONObject r) {
                if (cb != null) cb.done(r, err);
                if (err instanceof Auth.Required) Auth.onRequired((Auth.Required) err);
            }
        }.execute();
    }
}
