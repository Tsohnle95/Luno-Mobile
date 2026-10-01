# Luno Mobile architecture overview

Owns runtime boundaries, dependency direction, application composition and
cross-domain navigation. Domain documents below own their state, contracts and
invariants. This is not an execution manual: see [AGENTS.md](AGENTS.md) and
[agent execution](docs/agent-execution.md). Source/config/schema and tests take
precedence over stale prose.

## Product and compatibility

Luno Mobile is an offline-first Kotlin/Jetpack Compose music player using
Media3 for playback, Room for durable library state, and WorkManager for
background work. The Android namespace and application ID are both
`com.luno.mobile`. SDK levels and app version live in
[app/build.gradle.kts](app/build.gradle.kts); dependency versions in the
[catalog](gradle/libs.versions.toml). Preserve Room schema history and explicit
migrations; [AppDatabase](app/src/main/java/com/luno/mobile/data/db/AppDatabase.kt)
and [schemas](app/schemas/com.luno.mobile.data.db.AppDatabase/) are authoritative.

The language-neutral library transfer contract is v1. Its fixtures are kept in
`contracts/library-export/v1/` and are intentionally duplicated with Luno
Desktop. See the [v1 owner](contracts/library-export/v1/README.md) for wire shape,
semantic guarantees, Mobile orchestration and compatibility change paths.

## Canonical owners

| Durable truth | Owner | Authority/evidence |
| --- | --- | --- |
| Agent behavior / temporary recovery | [Execution](docs/agent-execution.md), [tasks](.agent/tasks/README.md) | Root/scoped policy; current task and Git |
| Metadata, SAF, playlists, artwork | [Library/storage](docs/library-storage.md) | Room entities/DAOs/migrations/schemas; repository tests |
| Session, queue/history, discovery/previews | [Playback/discovery](docs/playback-discovery.md) | Media3 adapters, service and preview tests |
| Job lifecycle, extraction, sync, promotion | [Downloads](docs/downloads.md) | Room jobs, WorkManager, repository/range tests |
| Desktop transfer | [Transfer v1](contracts/library-export/v1/README.md) | Schema, fixtures, codec/repository tests |
| Toolchain, verification, debugging | [Development](docs/android-development.md) | Wrapper, catalog, Gradle config, workflows |
| Signing, publishing, installed updates | [Releases](ANDROID_RELEASES.md) | Release workflow, updater, manifest/installer |

README remains public-facing. Tool-specific policy routes to root AGENTS. No
scoped files are warranted today: this is one app with shared conventions;
the vendor composite build stays externally owned. Split owners only when
independent state/contracts/lifecycle and recurring routing value warrant it.

## Architecture and data flow

`LunoApp` owns application-scoped repositories, eagerly observed `LibraryData`,
import/artwork managers, recommendation previews and its process-lifetime
`SupervisorJob` scope. It initializes Room, NewPipe and WorkManager configuration,
starts download reconciliation/repair, and captures uncaught crashes locally.
`MainActivity` creates/attaches/releases `MusicController`; `MusicService`
independently owns ExoPlayer/MediaSession and their release/restore lifecycle.
WorkManager's default startup initializer is disabled in favor of `LunoApp`'s
configuration. No separate service process is declared in the
[manifest](app/src/main/AndroidManifest.xml); lifecycle boundaries still matter.

Compose screens under `app/src/main/java/com/luno/mobile/ui/` render state and delegate
mutations to repositories. The data layer under `app/src/main/java/com/luno/mobile/data/`
owns Room persistence, SAF/library access, downloads, discovery, artwork, and
transfer import/export. `playback/` also contains download/sync workers and
network extraction; directories are not independent architectural domains.
UI delegates mutations through repositories/controllers; repositories use
DAOs and platform/network adapters; Room emits state via `LibraryData` to UI.
Workers and preview promotion converge on repository/Room publication. The
extractor is an upstream composite build, not another shipped application.

Composition entry points:
[LunoApp](app/src/main/java/com/luno/mobile/LunoApp.kt),
[MainActivity](app/src/main/java/com/luno/mobile/MainActivity.kt),
[MainShell](app/src/main/java/com/luno/mobile/ui/shell/MainShell.kt),
[LunoNavHost](app/src/main/java/com/luno/mobile/ui/navigation/NavGraph.kt).

## Cross-boundary change paths

Use these routes to find all affected surfaces; details/invariants stay at each
owner. Transport contracts are Kotlin values/MediaItem extras, Room entities,
WorkManager input IDs/tags, Android intents/content URIs and transfer JSON.

| Change | Follow the producers, state and consumers | Owner/test route |
| --- | --- | --- |
| Next while Discover is pending | App controls → preview Next router → controller; external media controls → service callback → same router; preparation/page state → queue continuation | [Playback/discovery](docs/playback-discovery.md), preview and controller tests plus device session checks |
| Save a recommendation | Last.fm identity → WebSearch resolution → preview asset or queued job → DownloadRepository → SAF/private audio + Room Track/membership/completion → LibraryData/UI | [Playback/discovery](docs/playback-discovery.md), [downloads](docs/downloads.md), [library](docs/library-storage.md); save/promotion/destination tests |
| Stuck/cancelled download or playlist sync | Downloads UI → repository → WorkManager producer/consumer → worker → Room status → startup/periodic reconciliation → LibraryData | [Downloads](docs/downloads.md); cancellation must account for sync producers |
| Metadata/history change | Room Track → MediaTrack/MediaItem extras → session → controller listener → recents preferences + repository popularity | [Library](docs/library-storage.md), [playback](docs/playback-discovery.md); preserve transient/source markers |
| New persisted field | Entity/DAO → explicit migration + exported schema → repositories/jobs → projections/UI; assess transfer inclusion intentionally | [Library](docs/library-storage.md), [transfer](contracts/library-export/v1/README.md); migration/device coverage required |
| Transfer identity or format | Desktop/schema/fixtures → codec/model → Room matching/preview → download provenance/enqueue → worker membership publication → UI | [Transfer](contracts/library-export/v1/README.md); codec/export tests and import recovery gap |

App-process imports/previews survive navigation, not process death. Durable
download execution belongs to WorkManager; startup reconciliation bridges its
state back to Room. Service restore and recents use private preferences. Keep
these distinct recovery owners when changing lifetimes.

Desktop development lives at
https://github.com/Tsohnle95/Luno-Desktop. The archived Python/Tkinter
generation is not an Android dependency.
