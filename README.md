<p align="center"><img src="art/legacylyrion_512.png" width="160" alt="LegacyLyrion icon"></p>

# LegacyLyrion

A [Lyrion Music Server](https://lyrion.org) (formerly Logitech Media Server / Squeezebox Server) client for **old Android phones, 4.0 Ice Cream Sandwich and newer**. It is a Spotify-first remote that also plays YouTube Music and your own library, and at the same time it turns the phone itself into a Squeezebox player.

Built for giving phones from the 2011 to 2014 era a second life as music players, and made to look at home on each maker's own skin.

## Features

- **Spotify first.** Search and browse Spotify through the [Spotty](https://github.com/michaelherger/Spotty-Plugin) plugin: results grouped into top result, songs, artists, albums and playlists; your Daily Mixes and playlists on the home screen. Tap a song to play it with the rest of the list queued after it.
- **YouTube Music.** Pick *YouTube Music* as the music source (menu > *Music source*) to browse and search it through the [YouTube Music for LMS](https://github.com/schmij97/lms-ytmusic) plugin: Featured, New Releases and Charts as shelves on the home screen, Moods & Genres and Podcasts, and search grouped into songs, artists, albums and playlists.
- **Local library.** The third music source shows only the music stored on your Lyrion server, with the same search and home screen.
- **The phone is a player.** Built-in SlimProto player: Lyrion sees the phone as a Squeezebox named `Android - <model>` (for example `Android - LT26i`), and it plays through the phone's speaker or headphones. Each phone gets its own player ID, so several phones can play at once.
- **Control any player.** Pick any Lyrion player (Squeezebox, piCorePlayer, squeezelite, another phone) and control it.
- **Stock-player behaviour.** Media notification with artwork and previous / play / next, lock-screen controls, headset and Bluetooth buttons, pause when headphones are unplugged, and the phone's own sound effects (equaliser, Walkman / ClearAudio+ and similar) applied to playback.
- **Native look on each skin.** Uses the maker's own theme where it has one: HTC Sense 5 (dark by default, light optional, with HTC's action bar, text fields and section bars), LG Optimus UI, Sony Xperia (the carbon background, Sony's section headers and the accent colour of the phone's chosen theme, with a Now Playing screen in the style of the Walkman player), and Samsung and other skins through Android's device theme. Plain Holo light and dark can be picked on any phone, and Material light and dark on Android 5.0 and newer.
- **UI size.** *Settings > Size* draws the whole app smaller or larger (60 to 125%), like changing the phone's DPI for this app only; handy on 240x320 flip phones.
- **Password-protected servers.** If the server (Lyrion's own password protection, or a proxy in front of it) asks for a login, the app asks for a username and password once and sends them with every request to that server (HTTP Basic). They are stored on the phone only; ⋮ > *Server login* changes or removes them.
- **First-run setup** finds your Lyrion server on the Wi-Fi automatically, or takes an address by hand, then checks which music plugins the server has: with none it starts in your library, with one it starts there, and with both Spotty and YouTube Music it asks which should be the main source.
- **Updates itself** through your Lyrion server (LegacyLyrion Updates plugin), on every Android version; see below.

## Requirements

- Android 4.0 or newer (tested on Sony Xperia S / 4.1.2, HTC One M7 / 4.2.2, LG G2 / 4.4.2).
- Lyrion Music Server 8 or 9 on your network. Linux (a Raspberry Pi, NAS or home server) or a Mac is the recommended host. Windows works too, but on a PC with more than one network adapter (Wi-Fi and Ethernet together, a VPN, or the virtual adapters VirtualBox, Hyper-V, WSL or Docker add) Lyrion for Windows can end up listening on the wrong one, and phones then can't find it, can't connect, or connect but stay silent. See the setup guide's troubleshooting section for the fix.
- For Spotify: the Spotty plugin and a Spotify Premium account.
- For YouTube Music: the YouTube Music for LMS plugin (no account needed), which needs Python 3 and its yt-dlp downloader on the server.
- For playback on the phone: Lyrion must be able to convert to MP3 (the phone player asks for MP3 only, because that's what every Android 4.x phone decodes reliably). The Windows and macOS builds of Lyrion include the converter; on Linux, install `lame`.

See **[docs/setup-guide.md](docs/setup-guide.md)** for a step-by-step install of Lyrion, Spotty and the app.

## Install

Download the APK from the [latest release](../../releases/latest) on the phone, allow *Unknown sources* in Settings > Security, and open it.

If you sideloaded a test build before 1.0.0, uninstall it first: release builds are signed with a different key.

## Updates

Both apps update themselves through your own Lyrion server, using the LegacyLyrion Updates plugin installed on it. The phone asks the server over its normal plain-HTTP port (`/legacylyrion/update/android.json` for LegacyLyrion, `/legacylyrion/update/retro.json` for Retro), so it works on every Android version. Phones before Android 9 or 10 mostly can't reach GitHub any more, because their TLS and certificates are too old. The plugin mirrors the GitHub releases, or serves builds you push to it yourself.

Updates are checked once a day and from *Check for updates* in the menu. A build is offered when its versionCode is higher than the installed one, and the download is checked against the SHA-256 the server publishes before the installer opens it. Without the plugin on the server, *Check for updates* says so.

LegacyLyrion can also check GitHub directly instead (*Settings > Updates from > GitHub*), for advanced users on Android 9 and newer. Retro only uses the server.

## LegacyLyrion Retro (Android 1.5 to 2.x)

A separate, smaller app for the phones before Ice Cream Sandwich: Sony Ericsson Xperia X10 mini and mini pro, X8, W8, Samsung Galaxy, Galaxy 5 and Galaxy S, and anything else from Android 1.5 Cupcake to 2.3 Gingerbread. It installs next to LegacyLyrion (package `com.h33ted.legacylyrion.retro`) and does the same core job:

- **Library, Spotify, YouTube Music and Radio tabs**, plus Favorites and Apps under *More*. The Spotify and YouTube Music tabs appear when the server has those plugins; the library is the server's own *My Music* menu.
- **The phone is a player** (`Android - <model>`, for example `Android - U20i`), or a remote for any other Lyrion player (menu > *Players*). Headset buttons, pause when headphones are unplugged, pause for phone calls, and a status bar notification.
- **Now Playing in the phone's own player style.** Nothing is copied into the app; it borrows from what the phone already has, at run time:
  - Sony Ericsson 2010 phones with WALKMAN / Music (X10 mini, X8, W8): its round keys, corner keys, infinity key, seek bar and background, with the Sony Ericsson theme on every screen;
  - Sony Ericsson 2011 phones (Xperia arc, ray, neo...): the 2011 WALKMAN layout, with the keys over the cover, library / infinity / queue along the bottom, the title in the phone's theme colour and the background tinted from the artwork;
  - the Xperia X10 on Android 1.6: Mediascape's ring keys, controller bar, wave background and CD case;
  - Samsung Galaxy S and S2: the TouchWiz music player's keys, seek bar and title strip, and TouchWiz's own title bars and tabs;
  - phones with the stock Android music player (Galaxy 5 and most others): its album frame and the system media keys;
  - anything else: the Walkman layout, drawn by the app.
- **Lock screen on the Galaxy S and S2.** Samsung's lock screen shows title, artist and artwork and its play / pause / next / previous keys work, the same as for Samsung's own player. For the artwork, the app keeps the current cover in `Android/data/com.h33ted.legacylyrion.retro/art` on the SD card (out of the Gallery) and registers it with the phone's media database, which is the only place that lock screen reads covers from. Other apps and widgets that follow the stock player's `com.android.music.metachanged` broadcasts see the track too.
- Volume keys change the phone's volume when it plays, and the chosen player's volume otherwise. Works with the trackball / D-pad.

Download `LegacyLyrionRetro-<version>.apk` from the [latest release](../../releases/latest); every release carries both apps with the same version number. Tested on the Xperia X10 (Android 1.6), X10 mini pro (1.6), W8 (2.1), arc S (2.3.4), Samsung Galaxy 5 (2.1), Galaxy S (2.2) and Galaxy S2 (2.3.5). On Android versions before 4.0 the phone can't pass a server login on to the audio stream, so a password-protected server can be browsed and controlled, but the phone itself plays only if the stream is reachable without a login.

## Building

The project is a plain Gradle project (Android Gradle Plugin 4.2.2, Gradle 6.9, JDK 8, SDK 28, no dependencies).

```
gradle assembleDebug
```

This builds both apps (`app/` and `retro/`). The Retro build needs the classic `aapt` from build-tools 28.0.3 as well: Android 1.5 to 2.1 can only read resource tables with UTF-16 strings, which the newer aapt2 never writes, so the build rebuilds Retro's small resource table with `aapt` (see `retro/build.gradle`).

Releases are built by GitHub Actions: pushing a tag like `v1.3.1` builds the APKs signed with the release key (stored as repository secrets) and publishes them as a release. See `.github/workflows/release.yml`. The same build makes LegacyLyrion Retro and attaches it to the same release.

## How it works

- **Browsing and control** use Lyrion's JSON-RPC API (`/jsonrpc.js`). Spotify and YouTube Music browsing go through the plugins' Squeezebox-controller (Jive) menus, the same ones a Squeezebox Touch uses, so both share one code path.
- **Playback on the phone** is a small SlimProto client (TCP port 3483) that streams MP3 from Lyrion into Android's `MediaPlayer`.
- **Skins:** most makers restyle Android's `Theme.DeviceDefault`, so the app just uses it. HTC Sense and LG keep their looks in their own resource packages (`com.htc`, `com.lge.internal`); the app detects and loads those at runtime, and reaches HTC's widget classes by reflection, so one APK runs everywhere.
- **Icons** are SVG files in `res/raw`, drawn by a small built-in SVG path renderer (Android 4.x has no vector drawables), so they stay sharp at every screen density.

## Credits

See [NOTICE](NOTICE). Spotify is a trademark of Spotify AB; YouTube and YouTube Music are trademarks of Google LLC. This project is not affiliated with Spotify, Google, Lyrion or Logitech. Spotify and YouTube Music playback depend on third-party plugins (Spotty, YouTube Music for LMS) that describe themselves as unofficial and may stop working when those services change; use them at your own risk and within the services' terms.
