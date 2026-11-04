# BetterPod

A click-wheel iPod for Android. Your phone's music, YouTube Music, Google Drive videos and retro
games, all driven by a spinning wheel. Built in Kotlin + Jetpack Compose for a Samsung Galaxy
S20 FE; it works on any Android 11+ phone.

## Install (just want to use it)

1. Go to this repo's **Releases** page and download the latest `BetterPod-vX.Y.apk` on your phone.
2. Open it. When Android asks, allow installs from that app (Chrome / Files / Drive), then **Install**.
3. If Play Protect warns "unknown app": **More details → Install anyway**. That shows for any app
   not from the Play Store.
4. Allow music access when BetterPod opens.

**Google Drive videos** need the owner to add your Gmail as a test user first. Ask them.

## Features

| Menu | What it does |
|---|---|
| Now Playing | Album art, progress, scrub mode, volume on the wheel |
| Search | Songs, artists, albums on the phone (keyboard pops up) |
| Playlists | Every music folder is a playlist. Play All / Shuffle, and **Edit Playlist** hides songs without deleting them |
| Songs / Artists / Albums | Classic iPod browsing |
| YouTube Music | Search, Liked Songs, your playlists, Home mixes; plays in the background with lock-screen controls |
| Videos | Google Drive videos (My Drive, shared drives, shared with me), search, offline downloads (phone or SD card) |
| Games | Breaker, Snake, Stack + Hextris, Tetris, 2048, Pac-Man, Floppy Bird. Wheel or Game Boy pad |
| Settings | Shuffle, repeat, wheel clicks, download location |

## Controls

- **Spin the wheel**: scroll. On Now Playing: volume (or seek in scrub mode).
- **Center**: select. On Now Playing: play / pause.
- **Tap the Now Playing screen**: scrub mode (spin to move through the song).
- **MENU** (top): back. **⏮ / ⏭** (left/right): previous / next. **⏯** (bottom): play/pause.
- **Touch**: tap a row to open it, swipe the screen to scroll.
- **Games**: wheel edges = D-pad, center = A, spin = steer. Swipe sideways to exit (from the
  screen edge in web games). WHEEL/PAD switches to the Game Boy pad.

## Getting music on the phone

Plug the phone into a PC and copy music into the **Music** folder. Each sub-folder becomes a playlist.

## YouTube Music notes

- Sign in from **YouTube Music → Sign in to Google**. If Google refuses ("browser may not be
  secure"), search and playback still work without an account.
- **Skip ads** is on by default and can be turned off in the YouTube Music menu. Skipping ads is
  against YouTube's Terms of Service; use it at your own risk.
- It runs the real music.youtube.com page hidden inside the app, so a YouTube website change can
  occasionally break search or next/previous until `assets/ytmusic.js` is updated.

## Build it yourself

1. Install **Android Studio** and open this folder (File → Open).
2. Let Gradle sync, plug in your phone (USB debugging on), press **Run ▶**.
3. Make a shareable APK: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.

**Google Drive** won't sign in on your own build until you create your own Google Cloud project:
enable the **Google Drive API**, set up the **OAuth consent screen** (add yourself as a test user),
and create an **Android OAuth client** with package `com.corpsave.betterpod` and your debug
SHA-1:

```
keytool -list -v -keystore "%USERPROFILE%\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
```

## Code map

| File | Job |
|---|---|
| `MainActivity.kt` | Screen, menus, Now Playing, wheel wiring |
| `ClickWheel.kt` | The touch wheel (spin, tap zones, haptic clicks) |
| `Library.kt` | Reads songs from the phone; folder playlists |
| `PlaybackService.kt` | Background player for local music (Media3) |
| `YtMusic.kt`, `YtMusicService.kt`, `assets/ytmusic.js` | YouTube Music bridge |
| `Drive.kt`, `Downloads.kt`, `DownloadService.kt`, `VideoActivity.kt` | Google Drive videos + offline |
| `Games.kt`, `WebGames.kt`, `assets/games/` | Built-in and bundled games |

Third-party game credits and licenses: see [THIRD_PARTY.md](THIRD_PARTY.md).
