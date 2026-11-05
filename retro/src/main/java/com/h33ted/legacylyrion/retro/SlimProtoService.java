package com.h33ted.legacylyrion.retro;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone as a Lyrion player: SlimProto over TCP 3483 to the server, audio through the
 * platform MediaPlayer (the server sends MP3, which every Android version plays). Also handles
 * media keys for whichever player is chosen, and pauses the phone's music for calls.
 */
public class SlimProtoService extends Service {
    static final String ACTION_TOGGLE = "com.h33ted.legacylyrion.retro.TOGGLE", ACTION_NEXT = "com.h33ted.legacylyrion.retro.NEXT",
            ACTION_PREV = "com.h33ted.legacylyrion.retro.PREV", ACTION_PAUSE = "com.h33ted.legacylyrion.retro.PAUSE";
    private static final int SLIMPROTO_PORT = 3483;
    private static final int NOTIF_ID = 3483;
    private static String sMac;

    static final StringBuffer LOG = new StringBuffer();

    static void log(String m) {
        String t = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        LOG.append(t).append(' ').append(m).append('\n');
        if (LOG.length() > 8000) LOG.delete(0, LOG.length() - 6000);
    }

    private volatile boolean startedSent;
    private volatile int generation;
    private volatile String serverIp = "";
    private volatile int serverHttpPort = 9000;
    private volatile Socket socket;
    private volatile DataOutputStream outStream;
    private Thread protoThread;
    private volatile MediaPlayer mediaPlayer;
    private volatile float currentGain = 1.0f;
    private Handler mainHandler;
    private final ExecutorService statExec = Executors.newSingleThreadExecutor();
    private WifiManager.WifiLock wifiLock;
    private String stateText = "Starting", trackText = "";
    private boolean pausedForCall;
    private android.content.BroadcastReceiver lockKeys;
    private boolean lockPlaying, lockStopped = true;

    /** Starts or stops the phone player to match the settings. */
    static void sync(Context c) {
        Intent i = new Intent(c, SlimProtoService.class);
        if (Prefs.phonePlayer(c) && Prefs.setupDone(c)) c.startService(i);
        else c.stopService(i);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler();
        try {
            WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "LegacyLyrionRetro");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) {}
        try {
            ((TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE)).listen(calls, PhoneStateListener.LISTEN_CALL_STATE);
        } catch (Exception ignored) {}
        Compat.foreground(this, NOTIF_ID, notification(), true);
        lockKeys = LockScreen.register(this, new LockScreen.Commands() {
            public void togglePause() {
                Rpc.call(SlimProtoService.this, localMac(SlimProtoService.this), Rpc.cmd("pause"), null);
            }

            public void next() {
                Rpc.call(SlimProtoService.this, localMac(SlimProtoService.this), Rpc.cmd("playlist", "index", "+1"), null);
            }

            public void previous() {
                Rpc.call(SlimProtoService.this, localMac(SlimProtoService.this), Rpc.cmd("playlist", "index", "-1"), null);
            }

            public void announceNow() {
                if (!lockStopped) LockScreen.announce(SlimProtoService.this, lockPlaying, false);
            }
        });
    }

    /** onStart works on every version; 2.0+ calls it from onStartCommand. */
    @Override
    public void onStart(Intent intent, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (a != null && a.startsWith("com.h33ted.legacylyrion.retro.")) {
            mediaKey(a);
            if (!Prefs.phonePlayer(this)) stopSelf();
            return;
        }
        if (!Prefs.phonePlayer(this) || !Prefs.setupDone(this)) {
            stopSelf();
            return;
        }
        String host = Prefs.host(this);
        int port;
        try {
            port = Integer.parseInt(Prefs.port(this));
        } catch (Exception e) {
            port = 9000;
        }
        boolean changed = !host.equals(serverIp) || port != serverHttpPort;
        serverIp = host;
        serverHttpPort = port;
        if (changed || protoThread == null || !protoThread.isAlive()) startLoop();
    }

    @Override
    public void onDestroy() {
        generation++;
        closeSocket();
        stopAndReleaseMediaPlayer();
        statExec.shutdownNow();
        try {
            if (lockKeys != null) unregisterReceiver(lockKeys);
        } catch (Exception ignored) {}
        LockScreen.announce(this, false, true);
        try {
            ((TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE)).listen(calls, PhoneStateListener.LISTEN_NONE);
        } catch (Exception ignored) {}
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {}
        Compat.foreground(this, NOTIF_ID, null, false);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ media keys, calls

    private void mediaKey(String a) {
        JSONArray cmd;
        if (ACTION_NEXT.equals(a)) cmd = Rpc.cmd("playlist", "index", "+1");
        else if (ACTION_PREV.equals(a)) cmd = Rpc.cmd("playlist", "index", "-1");
        else if (ACTION_PAUSE.equals(a)) cmd = Rpc.cmd("pause", "1");
        else cmd = Rpc.cmd("pause");
        Rpc.call(this, cmd, null);
    }

    private final PhoneStateListener calls = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String number) {
            MediaPlayer mp = mediaPlayer;
            boolean local = false;
            try {
                local = mp != null && mp.isPlaying();
            } catch (Exception ignored) {}
            if (state != TelephonyManager.CALL_STATE_IDLE && local) {
                pausedForCall = true;
                Rpc.call(SlimProtoService.this, localMac(SlimProtoService.this), Rpc.cmd("pause", "1"), null);
            } else if (state == TelephonyManager.CALL_STATE_IDLE && pausedForCall) {
                pausedForCall = false;
                Rpc.call(SlimProtoService.this, localMac(SlimProtoService.this), Rpc.cmd("pause", "0"), null);
            }
        }
    };

    // ------------------------------------------------------------------ notification

    private android.app.Notification notification() {
        PendingIntent tap = PendingIntent.getActivity(this, 0,
                new Intent(this, NowPlayingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0);
        String title = trackText.length() > 0 ? trackText : "LegacyLyrion";
        return Compat.notification(this, getResources().getIdentifier("ic_stat", "drawable", getPackageName()), title, stateText + " \u00B7 " + deviceName(), tap);
    }

    private void state(final String s) {
        log(s);
        mainHandler.post(new Runnable() {
            public void run() {
                stateText = s;
                if ("Playing".equals(s) || "Buffering…".equals(s)) fetchTitle();
                if ("Playing".equals(s)) lockState(true, false);
                else if ("Paused".equals(s)) lockState(false, false);
                else if ("Ready to play".equals(s) || "Stream error".equals(s)) lockState(false, true);
                refreshNotification();
            }
        });
    }

    /** Lock screen and widgets: announce only real changes. */
    private void lockState(boolean playing, boolean stopped) {
        if (playing == lockPlaying && stopped == lockStopped) return;
        lockPlaying = playing;
        lockStopped = stopped;
        LockScreen.announce(this, playing, stopped);
    }

    private void refreshNotification() {
        try {
            ((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_ID, notification());
        } catch (Exception ignored) {}
    }

    private void fetchTitle() {
        Rpc.call(this, localMac(this), Rpc.cmd("status", "-", "1", "tags:alcK"), new Rpc.Callback() {
            public void done(JSONObject r, Exception e) {
                JSONArray loop = r == null ? null : r.optJSONArray("playlist_loop");
                JSONObject t = loop == null ? null : loop.optJSONObject(0);
                if (t == null) return;
                String a = t.optString("artist", ""), title = t.optString("title", ""), al = t.optString("album", "");
                trackText = title + (a.length() > 0 ? " - " + a : "");
                refreshNotification();
                if (!title.equals(NowPlayingProvider.title) || !a.equals(NowPlayingProvider.artist)) {
                    NowPlayingProvider.title = title;
                    NowPlayingProvider.artist = a;
                    NowPlayingProvider.album = al;
                    NowPlayingProvider.duration = (long) (r.optDouble("duration", 0) * 1000);
                    NowPlayingProvider.seq++;
                    if (!lockStopped) LockScreen.announce(SlimProtoService.this, lockPlaying, false);
                    String icon = Item.str(t, "artwork_url");
                    if (icon == null) icon = Images.trackCover(Item.str(t, "coverid"));
                    final String url = Images.url(SlimProtoService.this, icon, 300);
                    final int mySeq = NowPlayingProvider.seq;
                    new Thread(new Runnable() {   // then the artwork, off the main thread (not the STAT thread)
                        public void run() {
                            LockScreen.setArtwork(SlimProtoService.this, url == null ? null : Images.fetch(SlimProtoService.this, url, 300));
                            mainHandler.post(new Runnable() {
                                public void run() {
                                    if (mySeq == NowPlayingProvider.seq && !lockStopped) {
                                        NowPlayingProvider.seq++;   // a new mediauri, so the lock screen re-reads it
                                        LockScreen.announce(SlimProtoService.this, lockPlaying, false);
                                    }
                                }
                            });
                        }
                    }, "LockArt").start();
                }
            }
        });
    }

    // ------------------------------------------------------------------ identity

    /**
     * Per-phone player ID from ANDROID_ID and the model, so a reinstall keeps the same player in
     * Lyrion; the locally-administered bit keeps it clear of real hardware.
     */
    static synchronized String localMac(Context c) {
        if (sMac != null) return sMac;
        SharedPreferences p = Prefs.get(c);
        String m = p.getString("player_mac", null);
        if (m == null) {
            String aid = null;
            try {
                aid = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
            } catch (Exception ignored) {}
            String seed = aid + "|" + Build.MODEL + "|" + Build.DEVICE + "|retro";
            if (aid == null || "9774d56d682e549c".equals(aid)) seed += "|" + System.currentTimeMillis();
            byte[] h;
            try {
                h = java.security.MessageDigest.getInstance("MD5").digest(seed.getBytes("UTF-8"));
            } catch (Exception e) {
                h = new byte[16];
                new java.util.Random().nextBytes(h);
            }
            h[0] = (byte) ((h[0] & 0xFC) | 0x02);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0) sb.append(':');
                String x = Integer.toHexString(h[i] & 0xFF);
                sb.append(x.length() == 1 ? "0" + x : x);
            }
            m = sb.toString();
            p.edit().putString("player_mac", m).commit();
        }
        sMac = m;
        return m;
    }

    private static byte[] macBytes(String mac) {
        String[] parts = mac.split(":");
        byte[] b = new byte[6];
        for (int i = 0; i < 6; i++) b[i] = (byte) Integer.parseInt(parts[i], 16);
        return b;
    }

    /** Player name: "Android - <model>", e.g. "Android - U20i". */
    static String deviceName() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        return "Android - " + (model.length() == 0 ? "Unknown" : model);
    }

    static String firmware() {
        return "Android " + Build.VERSION.RELEASE + " (API " + Compat.sdk() + ")";
    }

    private static String newString(byte[] b, int off, int len) {
        try {
            return new String(b, off, len, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return new String(b, off, len);
        }
    }

    private static String capSafe(String v) {
        return v.replace(',', ' ').replace('=', ' ');
    }

    private void startLoop() {
        final int gen = ++generation;
        closeSocket();
        Thread old = protoThread;
        if (old != null) old.interrupt();
        protoThread = new Thread(new Runnable() {
            public void run() {
                runProtocolLoop(gen);
            }
        }, "SlimProto");
        protoThread.start();
    }

    private void runProtocolLoop(int gen) {
        while (gen == generation) {
            Socket s = null;
            try {
                state("Connecting to " + serverIp);
                s = new Socket();
                socket = s;
                s.connect(new InetSocketAddress(serverIp, SLIMPROTO_PORT), 4500);
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);

                DataInputStream in = new DataInputStream(s.getInputStream());
                outStream = new DataOutputStream(s.getOutputStream());

                sendHeloPacket();
                state("Ready to play");

                while (gen == generation) {
                    // Server -> client frame: [2-byte length][4-byte opcode][data]; length includes opcode
                    int len = in.readUnsignedShort();
                    byte[] opBytes = new byte[4];
                    in.readFully(opBytes);
                    int dataLen = len - 4;
                    if (dataLen < 0) throw new IOException("bad frame length");
                    byte[] data = new byte[dataLen];
                    if (dataLen > 0) in.readFully(data);
                    handleServerFrame(new String(opBytes, "US-ASCII"), data);
                }
            } catch (Exception e) {
                log("connection error: " + e);
                if (gen == generation) state("Reconnecting to " + serverIp);
            } finally {
                try {
                    if (s != null) s.close();
                } catch (Exception ignored) {}
                if (gen == generation) outStream = null;
            }
            if (gen != generation) break;
            try {
                Thread.sleep(3500);
            } catch (InterruptedException ie) {
                break;
            }
        }
    }

    private void sendHeloPacket() throws IOException {
        String caps = "Model=squeezelite,ModelName=" + capSafe(deviceName()) + ",Firmware=" + capSafe(firmware())
                + ",AccuratePlayPoints=1,HasDigitalOut=1,MaxSampleRate=48000,mp3";
        byte[] capBytes = caps.getBytes("UTF-8");

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(body);
        d.writeByte(12);
        d.writeByte(Math.min(Compat.sdk(), 255));
        d.write(macBytes(localMac(this)));
        d.write(new byte[16]);
        d.writeShort(0x8000);
        d.writeLong(0L);
        d.writeByte('E');
        d.writeByte('N');
        d.write(capBytes);
        d.flush();
        writeClientFrame("HELO", body.toByteArray());
    }

    private void sendStatPacket(String eventCode, int serverTimestamp) {
        if (outStream == null) return;
        try {
            int elapsedMs = 0;
            MediaPlayer mp = mediaPlayer;
            if (mp != null) {
                try {
                    if (mp.isPlaying()) elapsedMs = mp.getCurrentPosition();
                } catch (Exception ignored) {}
            }

            ByteArrayOutputStream body = new ByteArrayOutputStream(53);
            DataOutputStream d = new DataOutputStream(body);
            d.write(eventCode.getBytes("US-ASCII"));
            d.writeByte(0);
            d.writeByte(0);
            d.writeByte(0);
            d.writeInt(4194304);
            d.writeInt(2097152);
            d.writeLong(1024000L);
            d.writeShort(65535);
            d.writeInt((int) (System.currentTimeMillis() & 0xFFFFFFFFL));
            d.writeInt(2097152);
            d.writeInt(1048576);
            d.writeInt(elapsedMs / 1000);
            d.writeShort(0);
            d.writeInt(elapsedMs);
            d.writeInt(serverTimestamp);
            d.writeShort(0);
            d.flush();
            writeClientFrame("STAT", body.toByteArray());
        } catch (Exception ignored) {}
    }

    private synchronized void writeClientFrame(String tag, byte[] payload) throws IOException {
        DataOutputStream o = outStream;
        if (o == null) return;
        o.write(tag.getBytes("US-ASCII"));
        o.writeInt(payload.length);
        o.write(payload);
        o.flush();
    }

    private void handleServerFrame(String cmd, byte[] payload) {
        if (!("strm".equals(cmd) && payload.length > 0 && payload[0] == 't')) {
            log("RX " + cmd + " len=" + payload.length);
        }
        if ("strm".equals(cmd) && payload.length >= 24) {
            handleStrmCommand(payload);
        } else if ("audg".equals(cmd) && payload.length >= 8) {
            handleAudgCommand(payload);
        } else if ("setd".equals(cmd) && payload.length >= 1 && payload[0] == 0) {
            // Server asks for the player name: reply with SETD id 0 + name
            if (payload.length == 1) {
                try {
                    byte[] name = deviceName().getBytes("UTF-8");
                    byte[] out = new byte[name.length + 2];
                    out[0] = 0;
                    System.arraycopy(name, 0, out, 1, name.length);
                    out[out.length - 1] = 0;
                    writeClientFrame("SETD", out);
                } catch (Exception ignored) {}
            }
        }
    }

    private void handleAudgCommand(byte[] p) {
        float vol;
        if (p.length >= 14) {
            // new_left: 16.16 fixed point, 65536 = full scale
            long newLeft = ((long) (p[10] & 0xFF) << 24) | ((p[11] & 0xFF) << 16)
                    | ((p[12] & 0xFF) << 8) | (p[13] & 0xFF);
            vol = newLeft / 65536.0f;
        } else {
            int oldLeft = ((p[0] & 0xFF) << 24) | ((p[1] & 0xFF) << 16)
                    | ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
            vol = oldLeft / 128.0f;
        }
        currentGain = Math.min(1.0f, Math.max(0.0f, vol));
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                MediaPlayer mp = mediaPlayer;
                if (mp != null) {
                    try {
                        mp.setVolume(currentGain, currentGain);
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private void handleStrmCommand(byte[] payload) {
        final char subCmd = (char) payload[0];
        final char autostart = (char) payload[1];
        final int serverPort = ((payload[18] & 0xFF) << 8) | (payload[19] & 0xFF);
        final int replayGainOrTimestamp = ((payload[14] & 0xFF) << 24)
                | ((payload[15] & 0xFF) << 16)
                | ((payload[16] & 0xFF) << 8)
                | (payload[17] & 0xFF);
        final String ipFromServer;
        if (payload[20] == 0 && payload[21] == 0 && payload[22] == 0 && payload[23] == 0) {
            ipFromServer = null;
        } else {
            ipFromServer = (payload[20] & 0xFF) + "." + (payload[21] & 0xFF) + "."
                    + (payload[22] & 0xFF) + "." + (payload[23] & 0xFF);
        }

        String httpHeader = "";
        if (payload.length > 24) {
            httpHeader = newString(payload, 24, payload.length - 24);
        }
        final String requestString = httpHeader;
        if (subCmd != 't') {
            String r = requestString.replace("\r", " ").replace("\n", " ");
            if (r.length() > 160) r = r.substring(0, 160);
            log("strm " + subCmd + " auto=" + autostart + " fmt=" + (char) payload[2]
                    + " port=" + serverPort + " ip=" + ipFromServer + " req=" + r);
        }

        if (subCmd == 't') {
            sendBackgroundStat("STMt", replayGainOrTimestamp);
            return;
        }

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                MediaPlayer mp = mediaPlayer;
                switch (subCmd) {
                    case 's':
                        startStreamFromHttpHeader(requestString, ipFromServer, serverPort,
                                autostart == '1' || autostart == '3');
                        break;
                    case 'p':
                        if (mp != null) {
                            try {
                                if (mp.isPlaying()) mp.pause();
                            } catch (Exception ignored) {}
                            sendBackgroundStat("STMp", 0);
                            state("Paused");
                        }
                        break;
                    case 'u':
                        if (mp != null) {
                            try {
                                mp.start();
                            } catch (Exception ignored) {}
                            sendBackgroundStat("STMr", 0);
                            if (!startedSent) {
                                startedSent = true;
                                sendBackgroundStat("STMs", 0);
                            }
                            state("Playing");
                        }
                        break;
                    case 'q':
                    case 'f':
                        stopAndReleaseMediaPlayer();
                        sendBackgroundStat("STMf", 0);
                        break;
                    default:
                        sendBackgroundStat("STMt", 0);
                        break;
                }
            }
        });
    }

    private void startStreamFromHttpHeader(String httpHeader, String ipFromServer, int port,
                                           final boolean autoStart) {
        String path = "/stream.mp3?player=" + localMac(this);
        if (httpHeader != null && httpHeader.startsWith("GET ")) {
            int endIdx = httpHeader.indexOf(" HTTP/");
            if (endIdx > 4) {
                path = httpHeader.substring(4, endIdx).trim();
            }
        }
        int targetPort = (port > 0) ? port : serverHttpPort;
        String host = (ipFromServer != null) ? ipFromServer : serverIp;
        final String streamUrl = "http://" + host + ":" + targetPort + path;

        stopAndReleaseMediaPlayer();
        startedSent = false;
        log("stream URL " + streamUrl);
        sendBackgroundStat("STMc", 0);
        state("Buffering…");

        final boolean[] prepared = new boolean[1];
        try {
            MediaPlayer mp = new MediaPlayer();
            mediaPlayer = mp;
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mp.setVolume(currentGain, currentGain);
            String login = Auth.header(this, host + ":" + targetPort);
            if (login != null && Compat.sdk() >= 14) {   // the stream needs the login too (4.0+ can send it)
                java.util.Map<String, String> headers = new java.util.HashMap<String, String>();
                headers.put("Authorization", login);
                MediaPlayer.class.getMethod("setDataSource", Context.class, android.net.Uri.class, java.util.Map.class)
                        .invoke(mp, this, android.net.Uri.parse(streamUrl), headers);
            } else {
                mp.setDataSource(streamUrl);
            }

            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    prepared[0] = true;
                    log("prepared, autostart=" + autoStart);
                    sendBackgroundStat("STMh", 0);
                    sendBackgroundStat("STMl", 0);
                    if (autoStart) {
                        m.start();
                        startedSent = true;
                        sendBackgroundStat("STMs", 0);
                        state("Playing");
                    } else {
                        state("Buffered");
                    }
                }
            });

            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    log("playback completed");
                    state("Ready to play");
                    sendBackgroundStat("STMd", 0);
                    sendBackgroundStat("STMu", 0);
                }
            });

            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    log("MediaPlayer error what=" + what + " extra=" + extra);
                    sendBackgroundStat("STMn", 0);
                    state("Can't play this stream (" + what + ")");
                    return true;
                }
            });

            mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer m, int what, int extra) {
                    log("MediaPlayer info " + what + "/" + extra);
                    return false;
                }
            });
            final MediaPlayer watched = mp;
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mediaPlayer == watched && !prepared[0]) log("still not prepared after 12 s");
                }
            }, 12000);
            mp.prepareAsync();
        } catch (Exception e) {
            log("stream setup failed: " + e);
            sendBackgroundStat("STMn", 0);
            state("Stream error");
        }
    }

    /** Network writes must stay off the main thread; a single executor keeps them ordered. */
    private void sendBackgroundStat(final String code, final int ts) {
        try {
            statExec.execute(new Runnable() {
                @Override
                public void run() {
                    sendStatPacket(code, ts);
                }
            });
        } catch (Exception ignored) {}
    }

    private void stopAndReleaseMediaPlayer() {
        MediaPlayer mp = mediaPlayer;
        mediaPlayer = null;
        if (mp != null) {
            try {
                if (mp.isPlaying()) mp.stop();
            } catch (Exception ignored) {}
            try {
                mp.release();
            } catch (Exception ignored) {}
        }
    }

    private void closeSocket() {
        try {
            Socket s = socket;
            if (s != null) s.close();
        } catch (Exception ignored) {}
        socket = null;
        outStream = null;
    }
}
