package com.h33ted.legacylyrion;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.os.Build;
import android.os.Handler;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stock-music-app style controls for whichever Lyrion player the app is controlling:
 * the ongoing notification (art, title, prev/play/next; expanded view on 4.1+),
 * lock-screen controls via RemoteControlClient, and headset/Bluetooth media buttons.
 * Lives inside SlimProtoService, which already holds the foreground notification.
 */
final class MediaNotifier {
    static final String ACTION_PREV = "com.h33ted.legacylyrion.PREV";
    static final String ACTION_TOGGLE = "com.h33ted.legacylyrion.TOGGLE";
    static final String ACTION_NEXT = "com.h33ted.legacylyrion.NEXT";
    static final String ACTION_PAUSE = "com.h33ted.legacylyrion.PAUSE";

    private final Service svc;
    private final int notifId;
    private final Handler h = new Handler();
    private final ExecutorService artExec = Executors.newSingleThreadExecutor();
    private final AudioManager am;
    private final ComponentName buttonReceiver;
    private RemoteControlClient rcc;
    private boolean running, inFlight, playing, hasFocus;
    private String title = "LegacyLyrion", artist = "Ready", album = "", artUrl;
    private long durationMs;
    private boolean wasPlaying;
    private long lastClaim;
    private Bitmap art, notifArt; // art: lock screen (up to 400 px); notifArt: small copy for the shade

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            poll();
            if (running) h.postDelayed(this, playing ? 3000 : 6000);
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focus = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                hasFocus = false;
                // another app started playing: pause like a stock player would (only for this phone's player)
                if (isLocal() && playing) command(ACTION_PAUSE);
            }
        }
    };

    MediaNotifier(Service s, int id) {
        svc = s;
        notifId = id;
        am = (AudioManager) s.getSystemService(Context.AUDIO_SERVICE);
        buttonReceiver = new ComponentName(s, MediaButtonReceiver.class);
    }

    Notification initial() {
        return build();
    }

    void start() {
        running = true;
        try {
            Intent mb = new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(buttonReceiver);
            rcc = new RemoteControlClient(PendingIntent.getBroadcast(svc, 0, mb, 0));
            rcc.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY
                    | RemoteControlClient.FLAG_KEY_MEDIA_PAUSE | RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE
                    | RemoteControlClient.FLAG_KEY_MEDIA_NEXT | RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS);
        } catch (Throwable ignored) {}
        claimLockScreen();
        h.post(tick);
    }

    /**
     * The lock screen shows the RemoteControlClient at the top of the media-button stack.
     * Sony's Walkman (and some other players) keep pushing a client-less proxy entry on top,
     * which makes the lock screen say "No tracks available". Re-registering moves us back up.
     */
    private void claimLockScreen() {
        lastClaim = System.currentTimeMillis();
        try {
            am.registerMediaButtonEventReceiver(buttonReceiver);
            if (rcc != null) am.registerRemoteControlClient(rcc);
        } catch (Throwable ignored) {}
    }

    void stop() {
        running = false;
        h.removeCallbacks(tick);
        try {
            if (rcc != null) am.unregisterRemoteControlClient(rcc);
            am.unregisterMediaButtonEventReceiver(buttonReceiver);
            am.abandonAudioFocus(focus);
        } catch (Throwable ignored) {}
        artExec.shutdownNow();
    }

    private boolean isLocal() {
        return SlimProtoService.localMac(svc).equalsIgnoreCase(Prefs.player(svc));
    }

    /** Handles notification buttons and media keys. */
    void command(String action) {
        JSONArray cmd;
        if (ACTION_PREV.equals(action)) cmd = Rpc.cmd("playlist", "index", "-1");
        else if (ACTION_NEXT.equals(action)) cmd = Rpc.cmd("playlist", "index", "+1");
        else if (ACTION_PAUSE.equals(action)) cmd = Rpc.cmd("pause", "1");
        else if (ACTION_TOGGLE.equals(action)) {
            playing = !playing;
            cmd = playing ? Rpc.cmd("play") : Rpc.cmd("pause", "1");
            publish();
        } else return;
        Rpc.call(Prefs.hostPort(svc), Prefs.player(svc), cmd, new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                h.removeCallbacks(tick);
                h.postDelayed(tick, 500);
            }
        });
    }

    private void poll() {
        if (inFlight) return;
        inFlight = true;
        final String hp = Prefs.hostPort(svc);
        final String pid = Prefs.player(svc);
        Rpc.call(hp, pid, Rpc.cmd("status", "-", "1", "tags:alcK"), new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                inFlight = false;
                if (r == null) return;
                JSONArray loop = r.optJSONArray("playlist_loop");
                JSONObject t = loop == null ? null : loop.optJSONObject(0);
                playing = "play".equals(r.optString("mode"));
                durationMs = (long) (r.optDouble("duration", 0) * 1000);
                if (playing && (!wasPlaying || System.currentTimeMillis() - lastClaim > 30000)) claimLockScreen();
                wasPlaying = playing;
                if (t == null) {
                    title = "LegacyLyrion";
                    artist = r.optString("player_name", "Nothing playing");
                    album = "";
                } else {
                    title = t.optString("title", "");
                    artist = t.optString("artist", "");
                    album = t.optString("album", "");
                    String icon = Item.str(t, "artwork_url");
                    if (icon == null) icon = Ui.localCover(Item.str(t, "coverid"));
                    if (icon == null) {
                        try {
                            icon = "/music/current/cover_300x300.jpg?player=" + URLEncoder.encode(pid, "UTF-8")
                                    + "&id=" + t.optString("id");
                        } catch (Exception ignored) {}
                    }
                    final String url = Ui.art(hp, icon, 400);
                    if (url != null && !url.equals(artUrl)) {
                        artUrl = url;
                        artExec.execute(new Runnable() {
                            @Override
                            public void run() {
                                final Bitmap b = ImageLoader.fetchBlocking(url);
                                final Bitmap small = b == null ? null : Bitmap.createScaledBitmap(b, 192, 192, true);
                                h.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (url.equals(artUrl)) {
                                            art = b;
                                            notifArt = small;
                                            publish();
                                        }
                                    }
                                });
                            }
                        });
                    }
                }
                if (playing && isLocal() && !hasFocus) {
                    hasFocus = am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                            == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                }
                publish();
            }
        });
    }

    private final android.util.SparseArray<Bitmap> glyphs = new android.util.SparseArray<Bitmap>();

    /** White transport icon from the SVGs in res/raw, rendered at 32dp in device pixels (notification shades are dark on 4.x). */
    private Bitmap glyph(int raw) {
        Bitmap b = glyphs.get(raw);
        if (b == null) {
            b = SvgIcon.get(svc, raw).render(Ui.dp(svc, 32), 0xFFFFFFFF);
            glyphs.put(raw, b);
        }
        return b;
    }

    private PendingIntent action(String a, int req) {
        Intent i = new Intent(svc, SlimProtoService.class).setAction(a);
        return PendingIntent.getService(svc, req, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private RemoteViews views(int layout) {
        RemoteViews v = new RemoteViews(svc.getPackageName(), layout);
        v.setTextViewText(R.id.nTitle, title);
        v.setTextViewText(R.id.nArtist, artist);
        if (layout == R.layout.notif_big) v.setTextViewText(R.id.nAlbum, album);
        if (notifArt != null) v.setImageViewBitmap(R.id.nArt, notifArt);
        else v.setImageViewResource(R.id.nArt, R.drawable.ic_launcher);
        v.setImageViewBitmap(R.id.nPrev, glyph(R.raw.ic_prev));
        v.setImageViewBitmap(R.id.nPlay, glyph(playing ? R.raw.ic_pause : R.raw.ic_play));
        v.setImageViewBitmap(R.id.nNext, glyph(R.raw.ic_next));
        v.setOnClickPendingIntent(R.id.nPrev, action(ACTION_PREV, 1));
        v.setOnClickPendingIntent(R.id.nPlay, action(ACTION_TOGGLE, 2));
        v.setOnClickPendingIntent(R.id.nNext, action(ACTION_NEXT, 3));
        return v;
    }

    @SuppressWarnings("deprecation")
    private Notification build() {
        Notification.Builder b = new Notification.Builder(svc)
                .setSmallIcon(R.drawable.ic_stat_legacylyrion)
                .setContent(views(R.layout.notif_small))
                .setOngoing(true)
                .setWhen(0)
                .setContentIntent(PendingIntent.getActivity(svc, 0,
                        new Intent(svc, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0));
        if (Build.VERSION.SDK_INT >= 16) b.setPriority(Notification.PRIORITY_HIGH);
        Notification n = b.getNotification();
        if (Build.VERSION.SDK_INT >= 16) n.bigContentView = views(R.layout.notif_big);
        return n;
    }

    @SuppressWarnings("deprecation")
    private void publish() {
        if (!running) return;
        NotificationManager nm = (NotificationManager) svc.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(notifId, build());
        if (rcc != null) {
            try {
                rcc.setPlaybackState(playing ? RemoteControlClient.PLAYSTATE_PLAYING : RemoteControlClient.PLAYSTATE_PAUSED);
                RemoteControlClient.MetadataEditor ed = rcc.editMetadata(true);
                ed.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, title);
                ed.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, artist);
                ed.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, album);
                ed.putString(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST, artist);
                if (durationMs > 0) ed.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, durationMs);
                if (art != null) ed.putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK,
                        art.copy(art.getConfig() != null ? art.getConfig() : Bitmap.Config.ARGB_8888, false));
                ed.apply();
            } catch (Throwable ignored) {}
        }
    }
}
