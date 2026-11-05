package com.h33ted.legacylyrion.retro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Shared by every screen: Sony theme, background, volume keys, the common menu. */
abstract class RetroActivity extends Activity {
    private static WeakReference<Activity> current = new WeakReference<Activity>(null);

    static Activity current() { return current.get(); }

    static final int M_NOW = 1, M_PLAYERS = 2, M_SERVER = 3, M_LOGIN = 4, M_PHONE = 5, M_HOME = 6, M_ABOUT = 7, M_UPDATE = 8;

    @Override
    protected void onCreate(Bundle b) {
        Look.theme(this);
        super.onCreate(b);
        getWindow().setBackgroundDrawable(Look.background(this));
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = new WeakReference<Activity>(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (current.get() == this) current = new WeakReference<Activity>(null);
    }

    /** Called after a login was entered; screens reload their data. */
    void reload() {}

    int dp(float v) { return Compat.dp(this, v); }

    /** Sony-style white text with a soft shadow, readable over picture backgrounds. */
    TextView text(float sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp * Look.screenScale(this) / 100f);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setShadowLayer(2f, 0, 1f, 0xcc000000);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** A thin title strip at the top of list screens. */
    View titleBar(String title, String sub) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));
        bar.setBackgroundDrawable(Look.titleBar(this));
        TextView t = text(18, false, 0xffffffff);
        t.setText(title);
        bar.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = text(13, false, 0xffc8c8c8);
            s.setText(sub);
            bar.addView(s);
        }
        return bar;
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ volume

    /** Volume keys: the phone's own volume when it plays, the Lyrion player's otherwise. */
    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) && !Prefs.playerIsPhone(this)) {
            final boolean up = code == KeyEvent.KEYCODE_VOLUME_UP;
            Rpc.call(this, Rpc.cmd("mixer", "volume", up ? "+5" : "-5"), new Rpc.Callback() {
                public void done(JSONObject r, Exception e) {
                    if (e == null) volumeShown();
                }
            });
            return true;
        }
        return super.onKeyDown(code, ev);
    }

    private Toast volToast;

    private void volumeShown() {
        Rpc.call(this, Rpc.cmd("mixer", "volume", "?"), new Rpc.Callback() {
            public void done(JSONObject r, Exception e) {
                if (r == null) return;
                String v = r.optString("_volume", "");
                if (volToast == null) volToast = Toast.makeText(RetroActivity.this, "", Toast.LENGTH_SHORT);
                volToast.setText("Volume " + v);
                volToast.show();
            }
        });
    }

    // ------------------------------------------------------------------ menu

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        if (!(this instanceof NowPlayingActivity)) m.add(0, M_NOW, 0, "Now playing").setIcon(android.R.drawable.ic_media_play);
        if (!(this instanceof HomeActivity)) m.add(0, M_HOME, 0, "Library").setIcon(android.R.drawable.ic_menu_agenda);
        m.add(0, M_PLAYERS, 0, "Players").setIcon(android.R.drawable.ic_menu_share);
        m.add(0, M_SERVER, 0, "Server").setIcon(android.R.drawable.ic_menu_preferences);
        m.add(0, M_LOGIN, 0, "Server login").setIcon(android.R.drawable.ic_lock_lock);
        m.add(0, M_PHONE, 0, "Phone as player").setIcon(android.R.drawable.ic_menu_call);
        m.add(0, M_UPDATE, 0, "Check for updates").setIcon(android.R.drawable.ic_menu_upload);
        m.add(0, M_ABOUT, 0, "About").setIcon(android.R.drawable.ic_menu_info_details);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case M_NOW:
                startActivity(new Intent(this, NowPlayingActivity.class));
                return true;
            case M_HOME:
                startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                return true;
            case M_PLAYERS:
                choosePlayer();
                return true;
            case M_SERVER:
                startActivity(new Intent(this, SetupActivity.class));
                return true;
            case M_LOGIN:
                Auth.prompt(this, Prefs.hostPort(this), null, new Runnable() {
                    public void run() {
                        reload();
                    }
                });
                return true;
            case M_PHONE:
                phonePlayerSetting();
                return true;
            case M_UPDATE:
                Updater.check(this, true);
                return true;
            case M_ABOUT:
                about();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    void choosePlayer() {
        Rpc.call(this, "", Rpc.cmd("players", "0", "99"), new Rpc.Callback() {
            public void done(JSONObject r, Exception e) {
                JSONArray loop = r == null ? null : r.optJSONArray("players_loop");
                final List<String> ids = new ArrayList<String>();
                List<String> names = new ArrayList<String>();
                String me = SlimProtoService.localMac(RetroActivity.this);
                if (Prefs.phonePlayer(RetroActivity.this)) {
                    ids.add(me);
                    names.add("This phone");
                }
                for (int i = 0; loop != null && i < loop.length(); i++) {
                    JSONObject o = loop.optJSONObject(i);
                    if (o == null || me.equalsIgnoreCase(o.optString("playerid"))) continue;
                    ids.add(o.optString("playerid"));
                    names.add(o.optString("name"));
                }
                if (ids.isEmpty()) {
                    toast(e != null ? "Can't reach the server" : "No players found");
                    return;
                }
                int sel = ids.indexOf(Prefs.player(RetroActivity.this));
                for (int i = 0; i < ids.size(); i++) if (ids.get(i).equalsIgnoreCase(Prefs.player(RetroActivity.this))) sel = i;
                new AlertDialog.Builder(RetroActivity.this)
                        .setTitle("Play on")
                        .setSingleChoiceItems(names.toArray(new String[0]), sel, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                Prefs.setPlayer(RetroActivity.this, ids.get(which));
                                playerChanged();
                            }
                        })
                        .show();
            }
        });
    }

    void playerChanged() {
        reload();
    }

    private void phonePlayerSetting() {
        final boolean on = Prefs.phonePlayer(this);
        new AlertDialog.Builder(this)
                .setTitle("Phone as player")
                .setMessage(on ? "This phone shows up in Lyrion as a player (\"" + SlimProtoService.deviceName()
                        + "\") and can play music itself. Turn that off to use it as a remote only."
                        : "This phone is a remote only. Turn this on to also play music on the phone.")
                .setPositiveButton(on ? "Turn off" : "Turn on", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Prefs.get(RetroActivity.this).edit().putBoolean("phone_player", !on).commit();
                        if (on && Prefs.playerIsPhone(RetroActivity.this)) Prefs.setPlayer(RetroActivity.this, "");
                        SlimProtoService.sync(RetroActivity.this);
                        playerChanged();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void about() {
        String look = Look.describe(this);
        String v = "";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        new AlertDialog.Builder(this)
                .setTitle("LegacyLyrion Retro " + v)
                .setMessage("Lyrion remote and player for Android 1.5 and up.\n\nServer: " + Prefs.hostPort(this)
                        + "\nPlayer: " + (Prefs.playerIsPhone(this) ? "this phone" : Prefs.player(this))
                        + "\nLooks: " + look + "\nAndroid " + android.os.Build.VERSION.RELEASE + " (API " + Compat.sdk() + ")"
                        + "\n\nmade by h33ted")
                .setPositiveButton("OK", null)
                .show();
    }
}
