# Playback, session and discovery previews

Owns Media3 session/control contracts, queue/recent-history policy and temporary
discovery lifecycle. Does not own [Room library](library-storage.md), permanent
[download publication](downloads.md), or release installation.

## Lifetimes, contracts and state

[MainActivity](../app/src/main/java/com/luno/mobile/MainActivity.kt) creates,
initializes and releases the Activity's
[MusicController](../app/src/main/java/com/luno/mobile/playback/MusicController.kt).
It attaches/detaches the application-owned preview manager. The controller
adapts session state to UI StateFlows and handles `PlaybackRequest`, pending
connection generations, queue/context order, shuffle/repeat and safe errors.
Before connection the last nonempty user request wins; stale connections after
release must not activate playback.

[MusicService](../app/src/main/java/com/luno/mobile/playback/MusicService.kt)
owns ExoPlayer/MediaSession, audio focus/noisy handling, notifications and private
preference restoration of the last persistent item/position/playWhenReady.
Release session before player, then shut down artwork executor. This service
has a lifecycle independent of the Activity; its exported surface and permissions
live in the [manifest](../app/src/main/AndroidManifest.xml). Inspect session
callback authorization/command handling when changing external control behavior.

`MediaTrack`, `PlaybackRequest`, [PlaybackSource](../app/src/main/java/com/luno/mobile/playback/PlaybackSource.kt)
and controller `METADATA_*` extras carry identity, playlist/source, duration,
transient status and selective artwork flags across the MediaItem boundary.
Transient previews must neither enter recents nor trigger Room popularity
recording. For a non-transient media-item transition, `MainActivity` passes the
playlist ID from that source to `LibraryRepository.recordPlayback`; the library
repository persists playlist play count and last-played time in Room. A queue
without a real playlist ID does not update playlist recency.
[RecentlyPlayedStore](../app/src/main/java/com/luno/mobile/playback/RecentlyPlayedStore.kt)
persists URI-deduplicated, most-recent-first history in preferences independently
of Room. Notification artwork enrichment is deliberately selective: embedding
artwork bytes into an entire large library queue can cause memory termination.

## Discovery and continuation

[FullPlayerScreen](../app/src/main/java/com/luno/mobile/ui/player/FullPlayerScreen.kt)
produces recommendation and save-to-playlist actions through
[PlaylistPickerSheet](../app/src/main/java/com/luno/mobile/ui/components/PlaylistPickerSheet.kt).
[MainShell](../app/src/main/java/com/luno/mobile/ui/shell/MainShell.kt) routes
app player/MiniPlayer Next controls. Start at these producers for UI changes,
then trace the state owners below and the external session command path.

[DiscoveryRepository](../app/src/main/java/com/luno/mobile/data/repository/DiscoveryRepository.kt)
owns key state and recommendation filtering against normalized library artist/
title identities. [LastfmService](../app/src/main/java/com/luno/mobile/data/discovery/LastfmService.kt)
owns requests, similar-track → artist-top-track fallback, normalization and
nonempty response cache keyed by artist/title/limit. Key changes clear cache.
[LastfmKeyStore](../app/src/main/java/com/luno/mobile/data/discovery/LastfmKeyStore.kt)
tries encrypted preferences but falls back to private plain preferences on
initialization failure; encryption is not unconditional. Never log/export keys.

[RecommendationPreviewManager](../app/src/main/java/com/luno/mobile/playback/RecommendationPreviewManager.kt)
owns process-local preview preparation, sliding prefetch window, handoff,
continuation pages, save deduplication and cancellation/generation defenses.
Its app scope survives navigation, not process death. Startup deletes temporary
preview cache; prepared assets are not library tracks until explicitly saved.
Resolution uses [download/extraction adapters](downloads.md) and bounded waits;
waiting/resolving/preparing/ready/failure and saving/queued/permanent states
must remain distinct.

App controls call `skipToNext`; external MediaSession Next is intercepted by
the service callback and routed through `handleSessionNext`. Both converge on
`routeNextWithinPreview`: pending Discover handoff/recommendation/page owns Next
before the physical library queue fallback. A visible button-only fix misses
notification/session controls. Saving prepared assets calls `promotePreview`;
unprepared saves resolve/enqueue a durable job. UI becomes permanent only after
actual durable completion. See [cross-boundary routes](../project_brain.md).

## Evidence and direct checks

- [MusicControllerTest](../app/src/test/java/com/luno/mobile/playback/MusicControllerTest.kt):
  pending requests/generations/release, metadata, context/queue seams, errors,
  history restoration.
- [RecommendationPreviewManagerTest](../app/src/test/java/com/luno/mobile/playback/RecommendationPreviewManagerTest.kt):
  handoff/session Next/page load, windows, prepared retention/back navigation,
  save deduplication/completion, timeout spinner clearing.
- [DiscoveryRepositoryTest](../app/src/test/java/com/luno/mobile/data/repository/DiscoveryRepositoryTest.kt),
  [LastfmServiceTest](../app/src/test/java/com/luno/mobile/data/discovery/LastfmServiceTest.kt),
  [RecommendationArtworkServiceTest](../app/src/test/java/com/luno/mobile/data/discovery/RecommendationArtworkServiceTest.kt).
- [NotificationPermissionPolicyTest](../app/src/test/java/com/luno/mobile/playback/NotificationPermissionPolicyTest.kt),
  [MusicControllerInstrumentedTest](../app/src/androidTest/java/com/luno/mobile/playback/MusicControllerInstrumentedTest.kt):
  real session/playback, notification behavior, Activity recreation and pending
  queue/start-index installation. Not run by the JVM gate or CI.

For session/UI changes directly check app and external Next, notification
permission, background playback, Activity recreation/process restart and
preview/save failure recovery as relevant. Live Last.fm/YouTube behavior and
Android Keystore fallback require integration/device evidence; mocks do not
establish provider availability. See [operations](android-development.md).
