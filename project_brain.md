# Luno Mobile Project Brain

This document is the operational map for the standalone Luno Mobile Android
repository. Source and tests are authoritative if this document becomes stale.

## Product and compatibility

Luno Mobile is an offline-first Kotlin/Jetpack Compose music player using
Media3 for playback, Room for durable library state, and WorkManager for
background work. The Android namespace and application ID are both
`com.luno.mobile`; min SDK is 29 and target/compile SDK is 35. Preserve the
Room schema history and explicit migrations when changing persistence.

The language-neutral library transfer contract is v1. Its fixtures are kept in
`contracts/library-export/v1/` and are intentionally duplicated with Luno
Desktop. The manifest is path-free and supports additive matching/import;
exports must not contain local paths, audio bytes, secrets, queue state, or
history. Keep the Mobile codec behavior compatible with Desktop.

## Build and release constraints

- Use the repository Gradle wrapper from the repository root.
- Use Java 17; Android CI provisions it explicitly.
- NewPipeExtractor is a pinned Git submodule at
  `vendor/NewPipeExtractor`, currently tag `v0.26.4`. A fresh clone must use
  `git clone --recurse-submodules` or `git submodule update --init --recursive`.
- Do not rely on `local.properties`, a checked-in keystore, an absolute SDK
  path, or an untracked vendor checkout.
- Debug verification: `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and
  `./gradlew assembleDebug`.
- Release verification: `./gradlew verifyReleaseVersion testDebugUnitTest
  lintDebug assembleRelease`, followed by `apksigner verify`.
- Signing is supplied only through CI environment variables. Never commit
  signing material or print secret values.

Required release variables are `ANDROID_RELEASE_KEYSTORE_PATH`,
`ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS`, and
`ANDROID_RELEASE_KEY_PASSWORD`. GitHub Actions decodes the
`ANDROID_RELEASE_KEYSTORE_BASE64` secret into a temporary keystore.

## Architecture and data flow

`LunoApp` owns application-scoped repositories and playback services. Compose
screens under `app/src/main/java/com/luno/mobile/ui/` render state and delegate
mutations to repositories. The data layer under `app/src/main/java/com/luno/mobile/data/`
owns Room persistence, SAF/library access, downloads, discovery, artwork, and
transfer import/export. Media3 playback is under `playback/` and is exposed to
the UI through `MusicController` and the media service.

The main flows are:

1. The user grants storage access through SAF. `LibraryRepository` scans the
   selected roots, normalizes metadata, and upserts tracks/playlists in Room.
2. Library and playlist screens observe Room flows. Track actions route through
   repositories; duplicate detection and metadata-only removal never delete
   audio unexpectedly.
3. `MusicController` maps Room tracks to Media3 media items, owns queue and
   recent-history policy, and maintains notification/session behavior through
   the media service.
4. Downloads stage temporary files, validate media, then promote atomically
   into the app library/download area. WorkManager owns durable background work
   and retry state.
5. Discovery uses the user’s Last.fm key and NewPipeExtractor-backed search;
   recommendation previews remain temporary until explicitly saved.
6. Transfer export snapshots logical tracks and playlist memberships. Import
   validates the v1 manifest, matches against the current library, and applies
   additive changes with ambiguity reporting.

## Repository map

```text
├── app/
│   ├── src/main/java/com/luno/mobile/
│   │   ├── data/             # Room, SAF/library, downloads, discovery, artwork, export
│   │   ├── playback/         # Media3 controller/service, previews, search
│   │   ├── ui/               # Compose shell, navigation, library, player, discovery
│   │   └── MainActivity.kt
│   ├── src/main/res/         # Android resources
│   ├── src/test/             # JVM/Robolectric/unit tests
│   ├── src/androidTest/      # device tests
│   └── schemas/              # tracked Room schema snapshots v1–v9
├── contracts/library-export/v1/  # shared transfer schema and fixtures
├── vendor/NewPipeExtractor/      # pinned submodule, v0.26.4
├── gradle/                       # wrapper and version catalog
├── .github/workflows/android-release.yml
├── build-mobile.command
├── ANDROID_RELEASES.md
├── settings.gradle.kts
└── project_brain.md
```

## Change discipline

Keep changes scoped to Mobile behavior or to paths required by the standalone
repository root. Do not change the application ID, SDK policy, transfer
format, NewPipeExtractor version, Room migrations, release signing contract,
or playback semantics as part of repository separation. Update this document
when architecture, data flow, compatibility constraints, or the file registry
changes.

Desktop development lives at
https://github.com/Tsohnle95/Luno-Desktop. The archived Python/Tkinter
generation is not an Android dependency.
