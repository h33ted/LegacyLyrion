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
- **Native look on each skin.** Uses the maker's own theme where it has one: HTC Sense 5 (dark by default, light optional, with HTC's action bar, text fields and section bars), LG Optimus UI, Sony Xperia (the carbon background, Sony's section headers and the accent colour of the phone's chosen theme, with a Now Playing screen in the style of the Walkman player), and Samsung and other skins through Android's device theme. Plain Holo light and dark can be picked on any phone.
- **First-run setup** finds your Lyrion server on the Wi-Fi automatically, or takes an address by hand, then checks which music plugins the server has: with none it starts in your library, with one it starts there, and with both Spotty and YouTube Music it asks which should be the main source.
- **Updates itself** from this repository's GitHub releases (Android 4.1 and newer; see below).

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

The app checks this repository's latest release once a day (and from *Check for updates* in the menu). A release whose tag (for example `v1.1.0`) is newer than the installed version, with an `.apk` attached, is offered for download and install.

GitHub requires TLS 1.2. Android 4.1 to 4.3 have it but leave it switched off, so the updater turns it on. **Android 4.0 cannot reach GitHub at all**; update those phones by downloading the APK from another device or a computer.

## Building

The project is a plain Gradle project (Android Gradle Plugin 4.2.2, Gradle 6.9, JDK 8, SDK 28, no dependencies).

```
gradle assembleDebug
```

Releases are built by GitHub Actions: pushing a tag like `v1.1.0` builds an APK signed with the release key (stored as repository secrets) and publishes it as a release. See `.github/workflows/release.yml`.

## How it works

- **Browsing and control** use Lyrion's JSON-RPC API (`/jsonrpc.js`). Spotify and YouTube Music browsing go through the plugins' Squeezebox-controller (Jive) menus, the same ones a Squeezebox Touch uses, so both share one code path.
- **Playback on the phone** is a small SlimProto client (TCP port 3483) that streams MP3 from Lyrion into Android's `MediaPlayer`.
- **Skins:** most makers restyle Android's `Theme.DeviceDefault`, so the app just uses it. HTC Sense and LG keep their looks in their own resource packages (`com.htc`, `com.lge.internal`); the app detects and loads those at runtime, and reaches HTC's widget classes by reflection, so one APK runs everywhere.
- **Icons** are SVG files in `res/raw`, drawn by a small built-in SVG path renderer (Android 4.x has no vector drawables), so they stay sharp at every screen density.

## Credits

See [NOTICE](NOTICE). Spotify is a trademark of Spotify AB; YouTube and YouTube Music are trademarks of Google LLC. This project is not affiliated with Spotify, Google, Lyrion or Logitech. Spotify and YouTube Music playback depend on third-party plugins (Spotty, YouTube Music for LMS) that describe themselves as unofficial and may stop working when those services change; use them at your own risk and within the services' terms.
