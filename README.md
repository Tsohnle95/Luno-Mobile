# Luno Mobile

Luno Mobile is the Android edition of Luno, built with Kotlin, Jetpack Compose,
Room, Media3, WorkManager, and NewPipeExtractor.

## Development

Clone with the pinned NewPipeExtractor submodule:

```bash
git clone --recurse-submodules https://github.com/Tsohnle95/Luno-Mobile.git
cd Luno-Mobile
python3 scripts/verify.py
```

The project runs Gradle with Java 17 and needs a Java 11 compiler toolchain for
the included extractor build. It uses the Android SDK configured by Gradle and
keeps the application identity `com.luno.mobile`. The NewPipeExtractor submodule is
pinned to v0.26.4; do not replace it with an unpinned or local-only checkout.

See [development setup and verification](docs/android-development.md) for SDK
setup, focused checks and device coverage, and [Android releases](ANDROID_RELEASES.md)
for signing/publishing. Coding agents start at [AGENTS.md](AGENTS.md).

## Release

Release builds use the environment variables below. Signing material must stay
outside the repository:

```text
ANDROID_RELEASE_KEYSTORE_PATH
ANDROID_RELEASE_STORE_PASSWORD
ANDROID_RELEASE_KEY_ALIAS
ANDROID_RELEASE_KEY_PASSWORD
```

The GitHub release workflow supplies the keystore from the base64-encoded
`ANDROID_RELEASE_KEYSTORE_BASE64` secret and publishes `Luno-Mobile-v*.apk`.

## Transfer compatibility

The versioned, path-free library transfer fixtures live in
`contracts/library-export/v1/` and are intentionally duplicated with Luno
Desktop. Desktop and Mobile must remain compatible with the v1 manifest.

Desktop development: https://github.com/Tsohnle95/Luno-Desktop

The legacy Python/Tkinter desktop is frozen under the
`legacy-python-desktop-final` archive tag in Luno Desktop.
