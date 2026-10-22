package com.h33ted.legacylyrion;

import android.os.AsyncTask;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fire-and-callback JSON-RPC helper; the callback runs on the UI thread. */
final class Rpc {
    interface Callback {
        void done(JSONObject result, Exception error);
    }

    private Rpc() {}

    static JSONArray cmd(String... tokens) {
        JSONArray a = new JSONArray();
        for (String t : tokens) a.put(t);
        return a;
    }

    static void call(final String hostPort, final String player, final JSONArray cmd, final Callback cb) {
        new AsyncTask<Void, Void, JSONObject>() {
            Exception err;

            @Override
            protected JSONObject doInBackground(Void... v) {
                try {
                    JSONObject r = LyrionRpcClient.sendCommand(hostPort, player, cmd);
                    if (r == null) err = new Exception("empty reply");
                    return r;
                } catch (Exception e) {
                    err = e;
                    return null;
                }
            }

            @Override
            protected void onPostExecute(JSONObject r) {
                if (cb != null) cb.done(r, err);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
}
