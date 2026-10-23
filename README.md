# Luno Mobile

<p align="center">
  <a href="https://github.com/Tsohnle95/Luno-Mobile/releases/latest">
    <img src="docs/assets/releases/luno-mobile-banner.png" alt="Luno Mobile — Your music. Your library." />
  </a>
</p>

<p align="center">
  <a href="https://github.com/Tsohnle95/Luno-Mobile/releases/latest">
    <img src="docs/assets/releases/download-android.png" alt="Download Luno Mobile for Android" width="320" height="64" />
  </a>
  <br />
  <sub>Universal APK · Android 10 or later</sub>
</p>

Luno Mobile brings local listening, playlists, and music discovery together in
one offline-first Android music player.

## Made for your music

- **Keep your collection close.** Browse music on your device, save favorites,
  and organize tracks into playlists.
- **Pick up where you left off.** Your queue, current track, playback position,
  and repeat mode are restored when you reopen the app.
- **Find something new.** Explore music with previews, then save the tracks you
  want to keep.

## Download

Get the latest universal APK from the [Luno Mobile release page](https://github.com/Tsohnle95/Luno-Mobile/releases/latest).
It supports Android 10 and later. Updating an existing installation keeps your
library; see the [install and update guide](ANDROID_RELEASES.md) for details.

## Build from source

Luno Mobile is built with Kotlin and Jetpack Compose. Room stores library data,
Media3 handles playback, and WorkManager runs background downloads and sync.

Clone the repository with its pinned NewPipeExtractor submodule, then run the
project verification gate:

```bash
git clone --recurse-submodules https://github.com/Tsohnle95/Luno-Mobile.git
cd Luno-Mobile
python3 scripts/verify.py
```

The build needs Java 17, a Java 11 compiler toolchain for NewPipeExtractor, and
the Android SDK. See [development setup and verification](docs/android-development.md)
for setup and device coverage.

## Works with Luno Desktop

Mobile and Desktop share the path-free library transfer v1 format. See the
[transfer contract](contracts/library-export/v1/README.md) and
[Luno Desktop](https://github.com/Tsohnle95/Luno-Desktop).
