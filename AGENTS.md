# Luno Mobile agent entry point

Offline-first Kotlin/Compose Android app. Room owns library metadata; Media3
owns playback in a MediaSessionService; WorkManager executes downloads/sync.
The pinned NewPipeExtractor composite build is an external dependency.

## Read progressively

This file → applicable scoped `AGENTS.md` → task-relevant canonical docs →
specific source symbols/tests. Expand only when evidence requires it. Do not
preload all docs or ingest whole large files, vendor, generated or build trees.
There are currently no scoped instructions; discover any added ones along paths
you touch. Tool-specific instruction files must route here and add only deltas.

Read [project_brain.md](project_brain.md) before changing the Android app. It
owns the architecture overview; source/config/schema and tests are authoritative
when prose is stale.

## Setup and commands

Use Java 17 to run Gradle, plus Java 11 for the pinned extractor compiler, and a
configured Android SDK. Initialize the tracked submodule with
`git submodule update --init --recursive`; use the Gradle wrapper, never system
Gradle or an untracked extractor checkout. See [development operations](docs/android-development.md).

- Canonical debug gate: `python3 scripts/verify.py` (docs, unit tests, lint, APK).
- Docs only: `python3 scripts/verify.py --docs-only`.
- Focused test: `./gradlew :app:testDebugUnitTest --tests 'com.luno.mobile.playback.MusicControllerTest'`.
- Development: `./build-mobile.command` builds, or installs/launches with adb.
- Direct debug checks: `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- Release gate: `python3 scripts/verify.py --release`, then signed `apksigner`
  verification per [ANDROID_RELEASES.md](ANDROID_RELEASES.md).

## Module and symptom router

Implementation paths below are relative to `app/src/main/java/com/luno/mobile/`.
Tests are in the matching `app/src/test/java/com/luno/mobile/` package; linked
owners give exact test paths and platform coverage limits.

| Area / symptom | Implementation and responsibility | Canonical owner / tests |
| --- | --- | --- |
| Startup, stale screens, navigation | `LunoApp`, `MainActivity`, `ui/shell/MainShell.kt`, `ui/navigation/NavGraph.kt`, `data/repository/LibraryData.kt`; composition and projections | [Overview](project_brain.md), [library/storage](docs/library-storage.md); `HomeScreenTest`, `ArtworkFetchManagerTest` |
| Missing imports, grants, favorites, ordering, artwork | `data/repository/LibraryRepository.kt`, `PlaylistRepository.kt`, `MusicFolderRepository.kt`, `data/db/`, `data/artwork/`; metadata and SAF | [Library/storage](docs/library-storage.md); `LibraryRepositoryTest`, `PlaylistRepositoryTest`, `MusicFolderRepositoryTest` |
| Next, queue, recents, notification, preview failure | `playback/MusicController.kt`, `MusicService.kt`, `RecommendationPreviewManager.kt`, `data/discovery/`, `data/repository/DiscoveryRepository.kt`; playback and temporary discovery | [Playback/discovery](docs/playback-discovery.md); `MusicControllerTest`, `RecommendationPreviewManagerTest`, `DiscoveryRepositoryTest` |
| Stuck progress, cancellation, destination, playlist sync | `data/repository/DownloadRepository.kt`, `playback/DownloadWorker.kt`, `PlaylistSyncWorker.kt`, `ParallelRangeDownloader.kt`; jobs and execution | [Downloads](docs/downloads.md); `DownloadRepositoryTest`, `ParallelRangeDownloaderTest` |
| Desktop transfer rejected, missing membership | `data/export/`, `data/repository/LibraryTransferRepository.kt`, `ui/export/`; strict format and additive import | [Transfer v1](contracts/library-export/v1/README.md); `LibraryManifestCodecTest`, `LibraryTransferRepositoryTest` |
| Build, release, sideload update/install | Gradle configs, `.github/workflows/`, `data/update/`, manifest/FileProvider | [Development](docs/android-development.md), [releases](ANDROID_RELEASES.md); `GitHubReleaseServiceTest` |

## Execution and safety

[docs/agent-execution.md](docs/agent-execution.md) owns the full protocol:
PATCH stays local; FEATURE uses an in-context plan; PROJECT records/reviews a
temporary [.agent/tasks/](.agent/tasks/README.md) file; BATCH triages and classifies
each independent unit. Escalate/replan on false assumptions, expanding contracts,
repeated failed repairs, or unclear correctness. Optional delegation requires
coordinating shared-state changes and integrated validation.

- Inspect Git status before editing. Preserve pre-existing changes and submodule
  dirt. Never reset, clean, stash, discard, or commit unrelated work.
- Preserve `com.luno.mobile`, explicit Room migrations/schema history, Media3
  sessions, SAF/library semantics, downloads/WorkManager, discovery/artwork and
  v1 Desktop compatibility.
- Keep NewPipeExtractor pinned to v0.26.4 through the tracked submodule; its
  catalog coordinate is substituted by `settings.gradle.kts`.
- Never read/print secret values for discovery. Keep signing material and
  `local.properties` out of Git. Destructive actions and publish/deploy require
  task authorization; routine reversible work does not.
- Prefer self-explanatory code. Comments preserve invariants, races,
  compatibility, trust assumptions and rationale, not obvious statements.

Done means acceptance met, focused checks and canonical gate passed under the
supported runtime, cumulative diff reviewed, changed durable truth updated at
its owner, and required direct platform checks performed or reported unverified.
Timeouts, skipped checks and truncated output are never passes. Commit only
authorized, independently sound agent-owned checkpoints with related docs.
Git owns completed history; delete completed task memory. README stays public
facing and must not become a competing agent manual.
