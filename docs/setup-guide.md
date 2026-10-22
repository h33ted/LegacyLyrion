# Turn an old Android phone into a Spotify player with Lyrion and LegacyLyrion

This guide sets up three things:

1. **Lyrion Music Server** on a Windows or Mac computer. It's the free, open-source successor to Logitech Media Server / Squeezebox Server.
2. **Spotty**, the plugin that brings Spotify into Lyrion.
3. **LegacyLyrion** on any Android 4.0+ phone, which becomes both a Spotify-style remote and a player in its own right.

**What you need**

- A Windows 10/11 PC or a Mac that stays on while you listen, on the same Wi-Fi as the phone.
- A Spotify **Premium** account (Spotty doesn't work with free accounts).
- An Android phone running 4.0 Ice Cream Sandwich or newer.

---

## 1. Install Lyrion Music Server

Downloads are on **https://lyrion.org** (*Getting started*). Version 9 is current.

### Windows

1. Download **Windows 64-bit** (`LyrionMusicServer-9.x.x-win64.exe`).
2. Run it and click through the installer. If you had an old 32-bit Logitech Media Server, the installer removes it.
3. Lyrion runs as a Windows service and starts with Windows. There's no tray icon; that's normal.
4. When Windows Firewall asks, **allow access on private networks**. The phone needs TCP 9000 (web and control) and TCP/UDP 3483 (players and discovery).

### macOS

1. Download **Apple macOS** (`LyrionMusicServer-9.x.x.dmg`).
2. Open the disk image and drag **Lyrion Music Server** into *Applications*.
3. Start it from *Applications*. A menu-bar item appears with *Start* / *Stop*.
4. If macOS asks whether Lyrion may accept incoming network connections, allow it.

### First run (both)

1. Open **http://localhost:9000** in a browser on that computer.
2. The setup wizard asks for your music and playlist folders. Point it at local music if you have any, or skip it if you only want Spotify.
3. You can skip the *mysqueezebox.com* account step; it isn't needed.

Write down the computer's IP address (Windows: `ipconfig` in a terminal, "IPv4 Address"; Mac: *System Settings → Wi-Fi → Details*). You only need it if the app can't find the server on its own.

---

## 2. Add Spotify with the Spotty plugin

1. In the Lyrion web page, go to **Settings → Manage Plugins**.
2. Find **Spotty** in the list of third-party plugins, tick it, click **Apply**, and let Lyrion restart.
3. Go to **Settings → Advanced** and pick **Spotty** from the drop-down at the top.
4. Authorise your Spotify account:
   - Open the Spotify app on your phone or computer, on the same network, and start playing anything.
   - Open the list of devices ("Connect to a device") and choose the device Spotty offers. It's named after your Lyrion server.
   - The Spotty settings page picks up the account by itself after a few seconds.
   - **If no device shows up** (this happens on Windows in particular), use the web/browser authorisation option on the same Spotty settings page instead.
5. Check that it works: in the Lyrion web page, *My Apps → Spotty* should show your playlists.

Heads-up: Spotty is an unofficial plugin, and Spotify changes its API now and then, which sometimes breaks the sign-in step until the plugin is updated. If authorisation fails, update Spotty under *Manage Plugins* and check the Spotty thread on **forums.lyrion.org**.

---

## 3. Make sure Lyrion can stream MP3

The phone player asks Lyrion for MP3, the one format every Android 4.x phone plays reliably, and Lyrion converts Spotify and your files on the fly. The Windows and Mac versions include the converter, so normally there's nothing to do.

If the phone shows the song but stays silent, go to **Settings → Advanced → File Types** and make sure the conversions that end in **MP3** aren't disabled. (On Linux servers, install the `lame` package.)

---

## 4. Install LegacyLyrion on the phone

1. On the phone, open the **latest release** page: **https://github.com/h33ted/LegacyLyrion/releases/latest**, and download `LegacyLyrion-x.y.z.apk`.
   - Android 4.0 can't open GitHub any more (it doesn't support modern encryption). Download the APK on a computer and copy it over USB, or use `adb install`.
2. Allow **Settings → Security → Unknown sources**, then open the downloaded file and install it.
3. Start **LegacyLyrion**. The first screen searches your Wi-Fi for Lyrion; tap your server. If nothing is found, type the computer's IP address and port `9000`.
4. That's it. The home screen shows your Spotify mixes and playlists, and the search box searches Spotify.

### Using it

- **Tap a song** to play it; the rest of the playlist or album follows. **Long-press** for *Play next* or *Add to queue*.
- The phone appears in Lyrion as a player called **"Android - \<model\>"** and plays through its own speaker or headphones. Turn this off in *Settings* (⋮ menu → *Settings* → *Use this phone as a player*) if you only want a remote.
- **⋮ → Settings → Control player** switches between this phone and any other Lyrion player (a Squeezebox, a Raspberry Pi with piCorePlayer, another phone…).
- **⋮ → Settings → Local library only** hides Spotify and shows the music stored on the server.
- **⋮ → Settings → Look** picks the phone maker's theme (HTC Sense, LG and so on) or plain Holo light/dark.
- The notification, lock screen and headset buttons all control playback, and the phone's own equaliser / sound effects work (⋮ → *Sound effects*).
- The app checks GitHub for updates once a day on Android 4.1+.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| App doesn't find the server | Same Wi-Fi? Allow Lyrion through the firewall (private networks), or type the IP by hand. Guest networks often block devices from seeing each other. |
| "Couldn't load Spotty" | Check *My Apps → Spotty* in the Lyrion web page. If it's empty, re-authorise in *Settings → Advanced → Spotty*. |
| Song shows but no sound on the phone | *Settings → Advanced → File Types*: allow conversion to MP3. Check the phone's media volume. |
| Music jumps between two phones | Each install has its own player ID; make sure both phones run LegacyLyrion 1.0.0 or newer. |
| Updates fail on Android 4.0 | Expected; GitHub needs TLS 1.2. Install new versions by hand. |

Source, issues and releases: **https://github.com/h33ted/LegacyLyrion**
