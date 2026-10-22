package com.h33ted.legacylyrion;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.AudioEffect;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native Lyrion / Squeezebox SlimProto client over TCP port 3483.
 * Registers this device as a player, parses server frames and drives MediaPlayer.
 */
public class SlimProtoService extends Service {

    public static final String ACTION_STATUS_BROADCAST = "com.h33ted.legacylyrion.SLIMPROTO_STATUS";
    /** The MAC every install used to share. Two phones with the same ID made Lyrion bounce the stream between them. */
    static final String LEGACY_SHARED_MAC = "00:04:20:41:4e:44";
    private static String sMac;
    private static final int SLIMPROTO_PORT = 3483;
    private static final int NOTIF_ID = 3483;
    private static final String PREFS = "slimproto";

    public static final StringBuffer LOG = new StringBuffer();
    private volatile boolean startedSent = false;
    public static volatile int audioSessionId = 0;

    public static void log(String m) {
        String t = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        LOG.append(t).append(' ').append(m).append('\n');
        if (LOG.length() > 8000) LOG.delete(0, LOG.length() - 6000);
    }

    private volatile int generation = 0;
    private volatile String serverIp = "";
    private volatile int serverHttpPort = 9000;

    private volatile Socket socket;
    private volatile DataOutputStream outStream;
    private Thread protoThread;

    private volatile MediaPlayer mediaPlayer;
    private volatile float currentGain = 1.0f;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService statExec = Executors.newSingleThreadExecutor();
    private WifiManager.WifiLock wifiLock;
    private MediaNotifier notifier;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "LegacyLyrion");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) {}

        notifier = new MediaNotifier(this, NOTIF_ID);
        startForeground(NOTIF_ID, notifier.initial());
        notifier.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null && intent.getAction().startsWith("com.h33ted.legacylyrion.")) {
            if (notifier != null) notifier.command(intent.getAction());
            return START_STICKY;
        }
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String host;
        int port;
        if (intent != null) {
            host = intent.getStringExtra("server_ip");
            port = intent.getIntExtra("server_http_port", 9000);
            if (host != null && host.length() > 0) {
                p.edit().putString("host", host).putInt("port", port).commit();
            }
        } else {
            // Restarted by the system after being killed
            host = p.getString("host", null);
            port = p.getInt("port", 9000);
        }
        if (host != null && host.length() > 0) {
            boolean changed = !host.equals(serverIp) || port != serverHttpPort;
            serverIp = host;
            serverHttpPort = port;
            if (changed || protoThread == null || !protoThread.isAlive()) {
                startLoop();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (notifier != null) notifier.stop();
        generation++;
        closeSocket();
        stopAndReleaseMediaPlayer();
        statExec.shutdownNow();
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {}
        stopForeground(true);
        super.onDestroy();
    }

    private void startLoop() {
        final int gen = ++generation;
        closeSocket();
        Thread old = protoThread;
        if (old != null) old.interrupt();
        protoThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runProtocolLoop(gen);
            }
        }, "SlimProto-TCP-Thread");
        protoThread.start();
    }

    private void runProtocolLoop(int gen) {
        while (gen == generation) {
            Socket s = null;
            try {
                broadcastState("CONNECTING TO " + serverIp + ":3483...");
                s = new Socket();
                socket = s;
                s.connect(new InetSocketAddress(serverIp, SLIMPROTO_PORT), 4500);
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);

                DataInputStream in = new DataInputStream(s.getInputStream());
                outStream = new DataOutputStream(s.getOutputStream());

                sendHeloPacket();
                broadcastState("SLIMPROTO REGISTERED (" + localMac(this) + ")");

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
                if (gen == generation) broadcastState("SLIMPROTO RECONNECTING...");
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

    /**
     * Per-device player ID. Derived from ANDROID_ID + serial so a reinstall keeps the same
     * player in Lyrion, with the locally-administered bit set so it never collides with real hardware.
     */
    public static synchronized String localMac(Context c) {
        if (sMac != null) return sMac;
        SharedPreferences p = c.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
        String m = p.getString("player_mac", null);
        if (m == null) {
            String aid = null;
            try {
                aid = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
            } catch (Exception ignored) {}
            String seed = aid + "|" + Build.SERIAL + "|" + Build.MODEL;
            if (aid == null || "9774d56d682e549c".equals(aid)) seed += "|" + System.nanoTime(); // known-bad ANDROID_IDs
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
                sb.append(String.format(java.util.Locale.US, "%02x", h[i] & 0xFF));
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

    /** Player name: "Android - <model number>", e.g. "Android - E15i" or "Android - GT-I9100". */
    public static String deviceName() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        return "Android - " + (model.length() == 0 ? "Unknown" : model);
    }

    /** Android version reported as the player firmware, e.g. "Android 4.0.4 (API 15)". */
    public static String firmware() {
        return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
    }

    private static String capSafe(String v) {
        return v.replace(',', ' ').replace('=', ' ');
    }

    private void sendHeloPacket() throws IOException {
        String caps = "Model=squeezelite,ModelName=" + capSafe(deviceName()) + ",Firmware=" + capSafe(firmware())
                + ",AccuratePlayPoints=1,HasDigitalOut=1,MaxSampleRate=48000,mp3";
        byte[] capBytes = caps.getBytes(Charset.forName("UTF-8"));

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(body);
        d.writeByte(12);
        d.writeByte(Math.min(Build.VERSION.SDK_INT, 255));
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
            httpHeader = new String(payload, 24, payload.length - 24, Charset.forName("UTF-8"));
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
                            broadcastState("SLIMPROTO PAUSED");
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
                            broadcastState("SLIMPROTO STREAMING AUDIO");
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
        broadcastState("SLIMPROTO BUFFERING STREAM...");

        final boolean[] prepared = new boolean[1];
        try {
            MediaPlayer mp = new MediaPlayer();
            mediaPlayer = mp;
            openEffectSession(mp);
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mp.setVolume(currentGain, currentGain);
            mp.setDataSource(streamUrl);

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
                        broadcastState("SLIMPROTO STREAMING AUDIO");
                    } else {
                        broadcastState("SLIMPROTO BUFFERED, WAITING");
                    }
                }
            });

            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    log("playback completed");
                    sendBackgroundStat("STMd", 0);
                    sendBackgroundStat("STMu", 0);
                }
            });

            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    log("MediaPlayer error what=" + what + " extra=" + extra);
                    sendBackgroundStat("STMn", 0);
                    broadcastState("SLIMPROTO DECODER ERROR (" + what + ")");
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
            broadcastState("SLIMPROTO STREAM ERROR");
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
            closeEffectSession();
            try {
                if (mp.isPlaying()) mp.stop();
            } catch (Exception ignored) {}
            try {
                mp.release();
            } catch (Exception ignored) {}
        }
    }

    /** Announces the player's audio session so system sound effects (Walkman, EQ) attach to it. */
    private void openEffectSession(MediaPlayer mp) {
        try {
            int sid = mp.getAudioSessionId();
            audioSessionId = sid;
            Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sid);
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
            sendBroadcast(i);
            log("effect session opened: " + sid);
        } catch (Exception e) {
            log("effect session failed: " + e);
        }
    }

    private void closeEffectSession() {
        try {
            int sid = audioSessionId;
            if (sid == 0) return;
            Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sid);
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
            sendBroadcast(i);
            audioSessionId = 0;
        } catch (Exception ignored) {}
    }

    private void closeSocket() {
        try {
            Socket s = socket;
            if (s != null) s.close();
        } catch (Exception ignored) {}
        socket = null;
        outStream = null;
    }

    private void broadcastState(String message) {
        log(message);
        Intent i = new Intent(ACTION_STATUS_BROADCAST);
        i.putExtra("status_text", message);
        sendBroadcast(i);
    }
}
