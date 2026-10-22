package com.h33ted.legacylyrion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.audiofx.AudioEffect;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/** What happens when an item is tapped or long-pressed, shared by Home and Browse. */
final class Nav {
    private Nav() {}

    static void exec(final Activity a, JSONArray cmd, final String toast, final Runnable then) {
        if (cmd == null) {
            Toast.makeText(a, "Not available for this item", Toast.LENGTH_SHORT).show();
            return;
        }
        Rpc.call(Prefs.hostPort(a), Prefs.player(a), cmd, new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                if (e != null) {
                    Toast.makeText(a, "Command failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (toast != null) Toast.makeText(a, toast, Toast.LENGTH_SHORT).show();
                if (then != null) then.run();
                MiniPlayer.refreshActive();
            }
        });
    }

    static JSONArray localCmd(String verb, Item it) {
        return Rpc.cmd("playlistcontrol", "cmd:" + verb, it.localKind + "_id:" + it.localId);
    }

    /** Tap on a song: plays it in context (rest of album / search list follows), like Spotify. */
    static void playTrack(final Activity a, Item it, JSONObject base) {
        if (it.isLocal()) {
            if (it.parentAlbumId != null && it.position >= 0) {
                final String idx = String.valueOf(it.position);
                exec(a, Rpc.cmd("playlistcontrol", "cmd:load", "album_id:" + it.parentAlbumId), "Playing " + it.title,
                        new Runnable() {
                            @Override
                            public void run() {
                                exec(a, Rpc.cmd("playlist", "index", idx), null, null);
                            }
                        });
            } else {
                exec(a, localCmd("load", it), "Playing " + it.title, null);
            }
            return;
        }
        JSONArray cmd = Jive.command(it, base, "playControl", 0);
        if (cmd == null) {
            exec(a, Jive.command(it, base, "play", 0), "Playing " + it.title, null);
            return;
        }
        final String toast = "Playing " + it.title;
        Rpc.call(Prefs.hostPort(a), Prefs.player(a), cmd, new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                if (e != null) {
                    Toast.makeText(a, "Command failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    return;
                }
                // With an empty queue Lyrion just plays. When something is already playing it replies
                // with a menu (Add to End / Play Next / Play this Song / Play all songs) instead.
                // Pick "Play all songs" from this track, like Spotify; fall back to "Play this Song".
                JSONArray choice = menuChoice(r, "item_playall");
                if (choice == null) choice = menuChoice(r, "item_play");
                if (choice != null) exec(a, choice, toast, null);
                else {
                    Toast.makeText(a, toast, Toast.LENGTH_SHORT).show();
                    MiniPlayer.refreshActive();
                }
            }
        });
    }

    /** The go-command of the entry with this Jive style in a context-menu reply, as CLI tokens. */
    private static JSONArray menuChoice(JSONObject r, String style) {
        JSONArray loop = r == null ? null : r.optJSONArray("item_loop");
        for (int i = 0; loop != null && i < loop.length(); i++) {
            JSONObject o = loop.optJSONObject(i);
            if (o == null || !style.equals(o.optString("style"))) continue;
            JSONObject acts = o.optJSONObject("actions");
            JSONObject go = acts == null ? null : acts.optJSONObject("go");
            JSONArray c = go == null ? null : go.optJSONArray("cmd");
            if (c == null) return null;
            JSONArray out = new JSONArray();
            for (int k = 0; k < c.length(); k++) out.put(c.optString(k));
            JSONObject p = go.optJSONObject("params");
            if (p != null) {
                java.util.Iterator<?> keys = p.keys();
                while (keys.hasNext()) {
                    String key = String.valueOf(keys.next());
                    out.put(key + ":" + p.optString(key));
                }
            }
            return out;
        }
        return null;
    }

    /** Long-press: Play now / Play next / Add to queue. */
    static void itemMenu(final Activity a, final Item it, final JSONObject base) {
        if (!it.isTrack() && !it.isPlayableContainer()) return;
        new AlertDialog.Builder(a)
                .setTitle(it.title)
                .setItems(new String[]{"Play now", "Play next", "Add to queue"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String[] local = {"load", "insert", "add"};
                        String[] jive = {"play", "add-hold", "add"};
                        String[] msg = {"Playing ", "Playing next: ", "Added: "};
                        JSONArray cmd = it.isLocal() ? localCmd(local[which], it)
                                : Jive.command(it, base, jive[which], 0);
                        exec(a, cmd, msg[which] + it.title, null);
                    }
                })
                .show();
    }

    /** Tap on anything that is not a song. */
    static void open(final Activity a, final Item it, final JSONObject base) {
        if (it.isTrack()) {
            playTrack(a, it, base);
            return;
        }
        if (it.isLocal()) {
            Intent i = new Intent(a, BrowseActivity.class);
            i.putExtra("title", it.title);
            i.putExtra("subtitle", it.subtitle);
            i.putExtra("icon", it.icon);
            i.putExtra("circle", it.isArtist());
            i.putExtra("local_kind", it.localKind);
            i.putExtra("local_id", it.localId);
            a.startActivity(i);
            return;
        }
        String nw = it.nextWindow();
        if ("parent".equals(nw) || "grandparent".equals(nw) || "refresh".equals(nw)
                || "refreshOrigin".equals(nw) || "nowPlaying".equals(nw)) {
            exec(a, Jive.command(it, base, "go", 0), it.title, null);
            return;
        }
        if ("search".equals(it.type) || "text".equals(it.type)) {
            askInput(a, it, base);
            return;
        }
        JSONArray go = Jive.command(it, base, "go", 200);
        if (go == null) {
            exec(a, Jive.command(it, base, "play", 0), "Playing " + it.title, null);
            return;
        }
        JSONArray play = it.isPlayableContainer() ? Jive.command(it, base, "play", 0) : null;
        JSONArray add = it.isPlayableContainer() ? Jive.command(it, base, "add", 0) : null;
        browse(a, it.title, it.niceSubtitle(), it.icon, go, play, add, it.isArtist(), false);
    }

    static void browse(Activity a, String title, String subtitle, String icon, JSONArray cmd,
                       JSONArray play, JSONArray add, boolean circle, boolean tracksOnly) {
        Intent i = new Intent(a, BrowseActivity.class);
        i.putExtra("title", title);
        i.putExtra("subtitle", subtitle);
        i.putExtra("icon", icon);
        i.putExtra("circle", circle);
        i.putExtra("tracks_only", tracksOnly);
        i.putExtra("cmd", cmd.toString());
        if (play != null) i.putExtra("play_cmd", play.toString());
        if (add != null) i.putExtra("add_cmd", add.toString());
        a.startActivity(i);
    }

    static void browseLocal(Activity a, String title, String kind, String id) {
        Intent i = new Intent(a, BrowseActivity.class);
        i.putExtra("title", title);
        i.putExtra("local_kind", kind);
        i.putExtra("local_id", id);
        a.startActivity(i);
    }

    private static void askInput(final Activity a, final Item it, final JSONObject base) {
        final EditText e = Skin.editText(a);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(a)
                .setTitle(it.title)
                .setView(e)
                .setPositiveButton("Search", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String q = e.getText().toString().trim();
                        if (q.length() == 0) return;
                        JSONArray cmd = Jive.withInput(Jive.command(it, base, "go", 200), q);
                        if (cmd != null) browse(a, q, it.title, null, cmd, null, null, false, false);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    static void nowPlaying(Activity a) {
        a.startActivity(new Intent(a, MainActivity.class));
    }

    static void library(Activity a) {
        Intent i = new Intent(a, LibraryActivity.class);
        i.putExtra("server_host_port", Prefs.hostPort(a));
        i.putExtra("player_id", Prefs.player(a));
        a.startActivity(i);
    }

    static void soundEffects(Activity a) {
        Intent i = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
        i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, SlimProtoService.audioSessionId);
        i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, a.getPackageName());
        try {
            if (a.getPackageManager().resolveActivity(i, 0) != null) {
                a.startActivityForResult(i, 0);
                return;
            }
            a.startActivity(new Intent(android.provider.Settings.ACTION_SOUND_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(a, "No sound effects panel found", Toast.LENGTH_SHORT).show();
        }
    }
}
