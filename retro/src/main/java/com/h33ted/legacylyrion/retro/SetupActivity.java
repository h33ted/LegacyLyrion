package com.h33ted.legacylyrion.retro;

import android.content.Context;
import android.content.Intent;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds Lyrion servers on the Wi-Fi the way Squeezebox hardware does (UDP 3483, 'e' request
 * asking for IPAD/NAME/JSON/VERS), or takes an address typed by hand.
 */
public class SetupActivity extends RetroActivity {
    private LinearLayout found;
    private ProgressBar spinner;
    private TextView status;
    private EditText host, port;
    private int scanGen;

    static final class Server {
        String name, ip, port, version;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int p = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        TextView head = text(21, true, 0xffffffff);
        head.setSingleLine(false);
        head.setText("Find your Lyrion server");
        root.addView(head);
        TextView expl = text(14, false, 0xffdddddd);
        expl.setSingleLine(false);
        expl.setPadding(0, dp(4), 0, dp(8));
        expl.setText("Looking on this Wi-Fi network. Tap your server, or type its address below.");
        root.addView(expl);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        row.addView(spinner);
        status = text(14, false, 0xffdddddd);
        status.setSingleLine(false);
        status.setPadding(dp(8), 0, 0, 0);
        row.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button again = new Button(this);
        again.setText("Search");
        again.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                scan();
            }
        });
        row.addView(again);
        root.addView(row);

        found = new LinearLayout(this);
        found.setOrientation(LinearLayout.VERTICAL);
        found.setPadding(0, dp(6), 0, dp(6));
        root.addView(found);

        TextView manual = text(17, true, 0xffffffff);
        manual.setText("Enter it yourself");
        manual.setPadding(0, dp(10), 0, 0);
        root.addView(manual);
        LinearLayout mrow = new LinearLayout(this);
        host = new EditText(this);
        host.setHint("e.g. 192.168.1.20");
        host.setSingleLine(true);
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (Prefs.setupDone(this)) host.setText(Prefs.host(this));
        mrow.addView(host, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        port = new EditText(this);
        port.setHint("9000");
        port.setSingleLine(true);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(Prefs.port(this));
        mrow.addView(port, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(mrow);
        Button connect = new Button(this);
        connect.setText("Connect");
        connect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String h = host.getText().toString().trim(), pt = port.getText().toString().trim();
                if (h.length() == 0) {
                    host.setError("Enter the server's address");
                    return;
                }
                test(h, pt.length() == 0 ? "9000" : pt);
            }
        });
        root.addView(connect);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        scan();
    }

    private void scan() {
        final int gen = ++scanGen;
        found.removeAllViews();
        spinner.setVisibility(View.VISIBLE);
        status.setText("Searching…");
        final InetAddress bcast = broadcastAddress();
        new AsyncTask<Void, Server, Integer>() {
            @Override
            protected Integer doInBackground(Void... v) {
                List<String> seen = new ArrayList<String>();
                DatagramSocket s = null;
                try {
                    s = new DatagramSocket();
                    s.setBroadcast(true);
                    s.setSoTimeout(700);
                    byte[] req = request();
                    long end = System.currentTimeMillis() + 4000;
                    int sends = 0;
                    while (System.currentTimeMillis() < end && gen == scanGen) {
                        if (sends < 3) {
                            s.send(new DatagramPacket(req, req.length, InetAddress.getByName("255.255.255.255"), 3483));
                            if (bcast != null) s.send(new DatagramPacket(req, req.length, bcast, 3483));
                            sends++;
                        }
                        byte[] buf = new byte[1024];
                        DatagramPacket pk = new DatagramPacket(buf, buf.length);
                        try {
                            s.receive(pk);
                        } catch (SocketTimeoutException te) {
                            continue;
                        }
                        Server sv = parse(pk);
                        if (sv != null && !seen.contains(sv.ip + ":" + sv.port)) {
                            seen.add(sv.ip + ":" + sv.port);
                            publishProgress(sv);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (s != null) s.close();
                }
                return seen.size();
            }

            @Override
            protected void onProgressUpdate(Server... l) {
                if (gen == scanGen) addServer(l[0]);
            }

            @Override
            protected void onPostExecute(Integer n) {
                if (gen != scanGen) return;
                spinner.setVisibility(View.GONE);
                status.setText(n == 0 ? "No server answered. Check you're on the same Wi-Fi, or type the address."
                        : n == 1 ? "Found 1 server" : "Found " + n + " servers");
            }
        }.execute();
    }

    private static byte[] request() {
        String[] tags = {"IPAD", "NAME", "JSON", "VERS"};
        byte[] b = new byte[1 + tags.length * 5];
        b[0] = 'e';
        int o = 1;
        for (String t : tags) {
            for (int i = 0; i < 4; i++) b[o++] = (byte) t.charAt(i);
            b[o++] = 0;
        }
        return b;
    }

    private static Server parse(DatagramPacket pk) {
        byte[] d = pk.getData();
        int n = pk.getLength();
        if (n < 1 || d[0] != 'E') return null;
        Server sv = new Server();
        sv.ip = pk.getAddress().getHostAddress();
        sv.port = "9000";
        int o = 1;
        while (o + 5 <= n) {
            String tag = new String(d, o, 4);
            int len = d[o + 4] & 0xFF;
            o += 5;
            if (o + len > n) break;
            String val = new String(d, o, len);
            o += len;
            if ("NAME".equals(tag)) sv.name = val;
            else if ("JSON".equals(tag)) sv.port = val;
            else if ("IPAD".equals(tag) && val.length() > 0) sv.ip = val;
            else if ("VERS".equals(tag)) sv.version = val;
        }
        if (sv.name == null) sv.name = "Lyrion Music Server";
        return sv;
    }

    private InetAddress broadcastAddress() {
        try {
            WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            DhcpInfo di = wm == null ? null : wm.getDhcpInfo();
            if (di == null || di.ipAddress == 0) return null;
            int b = (di.ipAddress & di.netmask) | ~di.netmask;
            byte[] q = new byte[4];
            for (int k = 0; k < 4; k++) q[k] = (byte) ((b >> (k * 8)) & 0xFF);
            return InetAddress.getByAddress(q);
        } catch (Exception e) {
            return null;
        }
    }

    private void addServer(final Server sv) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setFocusable(true);
        row.setClickable(true);
        TextView name = text(18, true, 0xffffffff);
        name.setText(sv.name);
        TextView addr = text(13, false, 0xffcccccc);
        addr.setText(sv.ip + ":" + sv.port + (sv.version != null ? "  ·  " + sv.version : ""));
        row.addView(name);
        row.addView(addr);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                test(sv.ip, sv.port);
            }
        });
        found.addView(row);
    }

    private void test(final String h, final String pt) {
        spinner.setVisibility(View.VISIBLE);
        status.setText("Connecting to " + h + ":" + pt + "…");
        Rpc.call(this, h + ":" + pt, "", Rpc.cmd("serverstatus", "0", "0"), new Rpc.Callback() {
            public void done(JSONObject r, Exception e) {
                spinner.setVisibility(View.GONE);
                if (e instanceof Auth.Required) {
                    status.setText(h + ":" + pt + " is password protected");
                    Auth.prompt(SetupActivity.this, h + ":" + pt, (Auth.Required) e, new Runnable() {
                        public void run() {
                            test(h, pt);
                        }
                    });
                    return;
                }
                if (r == null) {
                    status.setText("No Lyrion server at " + h + ":" + pt
                            + (e != null && e.getMessage() != null ? " (" + e.getMessage() + ")" : ""));
                    return;
                }
                Prefs.get(SetupActivity.this).edit().putString("lms_host", h).putString("lms_port", pt).commit();
                SlimProtoService.sync(SetupActivity.this);
                startActivity(new Intent(SetupActivity.this, HomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("reload", true));
                finish();
            }
        });
    }
}
