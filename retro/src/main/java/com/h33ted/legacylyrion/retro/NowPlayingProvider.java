package com.h33ted.legacylyrion.retro;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * The phone player's current track as a one-row table, in MediaStore's column names. Samsung's
 * Galaxy S lock screen shows the music it is told about by querying the "mediauri" of the
 * player's broadcast (see {@link LockScreen}); pointing that at this provider puts the Lyrion
 * track on the lock screen.
 */
public class NowPlayingProvider extends ContentProvider {
    static final String AUTHORITY = "com.h33ted.legacylyrion.retro.nowplaying";

    static volatile String title = "", artist = "", album = "";
    static volatile long duration;
    static volatile int seq;

    /** A new URI per track, so the lock screen doesn't keep showing the previous one. */
    static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/track/" + seq);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String order) {
        String[] cols = projection != null ? projection
                : new String[]{"_id", "title", "artist", "album", "album_id", "duration"};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            String k = cols[i];
            if ("title".equals(k) || "_display_name".equals(k)) row[i] = title;
            else if ("artist".equals(k)) row[i] = artist;
            else if ("album".equals(k)) row[i] = album;
            else if ("duration".equals(k)) row[i] = duration;
            else if ("_id".equals(k)) row[i] = seq;
            // Must be a valid number: the lock screen opens content://media/external/audio/albumart/<id>.
            // -1 makes MediaStore throw IllegalStateException, uncaught in the system process (a
            // system restart). Our artwork's reserved id (LockScreen.setArtwork), or 0: a
            // well-formed album that doesn't exist, which ends in the FileNotFoundException the
            // lock screen handles by showing its own disc.
            else if ("album_id".equals(k)) row[i] = LockScreen.albumId;
            else if ("mime_type".equals(k)) row[i] = "audio/mpeg";
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "audio/mpeg";
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
