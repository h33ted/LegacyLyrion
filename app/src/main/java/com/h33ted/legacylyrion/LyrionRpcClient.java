package com.h33ted.legacylyrion;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class LyrionRpcClient {

    public static JSONObject sendCommand(String serverHostPort, String playerId, JSONArray commandArray) throws Exception {
        URL url = new URL("http://" + serverHostPort + "/jsonrpc.js");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(4500);
        conn.setReadTimeout(6000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);

        JSONObject payload = new JSONObject();
        payload.put("id", 1);
        payload.put("method", "slim.request");

        JSONArray params = new JSONArray();
        params.put(playerId == null ? "" : playerId);
        params.put(commandArray);
        payload.put("params", params);

        byte[] body = payload.toString().getBytes("UTF-8");
        OutputStream os = conn.getOutputStream();
        os.write(body);
        os.flush();
        os.close();

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        conn.disconnect();

        return new JSONObject(sb.toString()).optJSONObject("result");
    }

    public static Bitmap fetchCoverArt(String serverHostPort, String trackId, String artworkUrl) {
        try {
            String fullUrl;
            if (artworkUrl != null && artworkUrl.startsWith("http")) {
                fullUrl = artworkUrl;
            } else if (trackId != null && trackId.length() > 0 && !"0".equals(trackId)) {
                fullUrl = "http://" + serverHostPort + "/music/" + trackId + "/cover_300x300.jpg";
            } else {
                return null;
            }
            URL url = new URL(fullUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);
            InputStream is = conn.getInputStream();
            Bitmap bmp = BitmapFactory.decodeStream(is);
            is.close();
            conn.disconnect();
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }
}
