package com.h33ted.legacylyrion.retro;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Tells the system and other apps what the phone player is playing, the way the phones' own
 * music players do, so lock screens and widgets that follow those players follow this one too:
 *  - Samsung (Galaxy S, TouchWiz 3): the lock screen listens for
 *    com.android.music.musicservicecommand.mediainfo {mediauri, playing, isStop}, reads title /
 *    artist / album from the mediauri, asks for it again with ...checkplaystatus when it appears,
 *    and its keys send ...togglepause / ...next / ...previous (and ff / rew);
 *  - stock Android players' com.android.music.metachanged / playstatechanged (scrobblers,
 *    widgets and lock screens of other skins).
 */
final class LockScreen {
    private static final String CMD = "com.android.music.musicservicecommand";
    static final String MEDIAINFO = CMD + ".mediainfo";

    private LockScreen() {}

    // ------------------------------------------------------------------ artwork

    /**
     * Album ids reserved for the artwork, far above any real MediaStore album. Two of them,
     * alternating per track: the Samsung lock screen only reloads the picture when the album
     * id changes.
     */
    private static final long ART_ALBUM = 1946157056L;
    private static boolean[] registered = new boolean[2];
    private static int artSlot;

    /** The album id the lock screen should use: one with our artwork, or 0 for its own disc. */
    static volatile long albumId;

    /**
     * Puts the track's artwork where the lock screen can read it: a file on the SD card (in a
     * folder with .nomedia, so it stays out of the Gallery), registered in MediaStore's album_art
     * table under a reserved album id. Call off the main thread. Null clears it.
     */
    static void setArtwork(Context c, Bitmap art) {
        if (art == null || !Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            albumId = 0;
            return;
        }
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "Android/data/" + c.getPackageName() + "/art");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                albumId = 0;
                return;
            }
            File nomedia = new File(dir, ".nomedia");
            if (!nomedia.exists()) nomedia.createNewFile();
            int slot = artSlot = 1 - artSlot;
            File out = new File(dir, "art" + slot + ".jpg"), tmp = new File(dir, "art" + slot + ".tmp");
            FileOutputStream os = new FileOutputStream(tmp);
            art.compress(Bitmap.CompressFormat.JPEG, 90, os);
            os.close();
            if (!tmp.renameTo(out)) {
                albumId = 0;
                return;
            }
            long id = ART_ALBUM + slot;
            if (!registered[slot]) {
                ContentValues v = new ContentValues();
                v.put("album_id", id);
                v.put("_data", out.getAbsolutePath());
                try {
                    c.getContentResolver().insert(Uri.parse("content://media/external/audio/albumart"), v);
                } catch (Exception alreadyThere) {
                    // a row from an earlier run points at the same file
                }
                registered[slot] = true;
            }
            albumId = id;
        } catch (Exception e) {
            albumId = 0;
        }
    }

    /** Announce the current state; call on every play / pause / stop and track change. */
    static void announce(Context c, boolean playing, boolean stopped) {
        try {
            Intent i = new Intent(MEDIAINFO);
            i.putExtra("mediauri", NowPlayingProvider.uri());
            i.putExtra("playing", playing);
            i.putExtra("isStop", stopped);
            c.sendBroadcast(i);

            for (String a : new String[]{"com.android.music.metachanged", "com.android.music.playstatechanged"}) {
                Intent m = new Intent(a);
                m.putExtra("id", (long) NowPlayingProvider.seq);
                m.putExtra("track", NowPlayingProvider.title);
                m.putExtra("artist", NowPlayingProvider.artist);
                m.putExtra("album", NowPlayingProvider.album);
                m.putExtra("playing", playing);
                m.putExtra("duration", NowPlayingProvider.duration);
                c.sendBroadcast(m);
            }
        } catch (Exception ignored) {}
    }

    interface Commands {
        void togglePause();

        void next();

        void previous();

        void announceNow();
    }

    /** The lock screen's keys (and its "who is playing?" question). */
    static BroadcastReceiver register(Context c, final Commands cmd) {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                String a = i.getAction();
                if (a == null) return;
                if (a.equals(CMD + ".togglepause")) cmd.togglePause();
                else if (a.equals(CMD + ".next") || a.equals(CMD + ".ff.up")) cmd.next();
                else if (a.equals(CMD + ".previous") || a.equals(CMD + ".rew.up")) cmd.previous();
                else if (a.equals(CMD + ".checkplaystatus")) cmd.announceNow();
            }
        };
        IntentFilter f = new IntentFilter();
        for (String s : new String[]{".togglepause", ".next", ".previous", ".ff.up", ".rew.up", ".checkplaystatus"}) {
            f.addAction(CMD + s);
        }
        c.registerReceiver(r, f);
        return r;
    }
}
