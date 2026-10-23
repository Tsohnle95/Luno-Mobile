# Playback, session and discovery previews

Metadata replacement and initial session queue restoration update player
projections without incrementing library play counts. Explicit playback starts,
including a restart of the same song, still count. The Media3 controller tracks
the explicit start separately from playlist-change events, which also occur when
recovered metadata replaces the open player's item. This prevents metadata
repair/editing from becoming a listening event or resetting its progress.

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
recovery of the full persistent Playing Next queue, current index/position,
play intent and shuffle/repeat modes. [PlaybackQueueStore](../app/src/main/java/com/luno/mobile/playback/PlaybackQueueStore.kt)
writes versioned atomic queue snapshots in app-private files on a serialized
worker. Original order, duplicate occurrences, exact shuffled traversal, source
metadata and manual queue membership survive restart; artwork bytes and transient
preview items are not serialized. Lightweight cursor preferences are tied to a
queue generation, so an interrupted update cannot apply an index to a different
queue. Queue edits/mode changes save a new snapshot; seeks, pauses and task
removal checkpoint position, with five-second checkpoints during playback.
Abrupt process termination can lose the last five seconds plus pending disk work.
An explicitly empty queue stays empty. Corrupt/unsupported snapshots fall back to
the legacy single-item preferences; unreadable track URIs remain subject to the
existing playback error path. Snapshots over 32 MiB are rejected, retaining the
previous recovery point.

Active Discover/preview sessions preserve the last persistent physical queue
instead of saving their temporary projections; recovery resumes that saved queue
paused. Discover preparation itself remains process-local. Restore installs items
and shuffle order before attaching the session/controller, avoiding new play
counts. Release session before player, then shut down artwork and persistence
executors. This service
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
must remain distinct. Session teardown preserves per-item failures: a timed-out
or failed sole preview must remain a visible retryable outcome instead of being
erased by the cleanup state reset.

When Discover is enabled on a normal track, capture the physical queue and keep
only the current item in Media3 until a recommendation is prepared. This prevents
automatic queue advancement from escaping into the physical playlist during a
slow resolve/download. With Repeat All enabled, the seed may repeat while the
recommendation is unavailable, but the repeat cannot wrap to an earlier physical
track; a prepared recommendation becomes its next item. If the current item ends
first, the first ready preview resumes from the ended state; a stop/pause must
not be mistaken for natural end. If Discover is disabled or previews fail before
one plays, restore the saved physical queue around the still-playing seed without
restarting it. A newer explicit play request supersedes that saved queue snapshot.

`MusicController.isPlaying` describes actual playback for progress/preview
animation. Its separate `playWhenReady` flow mirrors MediaSession play intent and
owns transport-button icons/toggling, so controls stay aligned while buffering or
when playback is controlled from the notification. Shuffle is a mode/queue
control only: toggling it must never start playback from idle or jump the
current item; audio starts only from an explicit play or track tap.
Library and playlist shuffle choices live in private `context_shuffle`
preferences exposed by `MusicController.contextShuffle`, rather than screen
state. Real playlists are keyed by ID (so renaming preserves the choice), and
virtual collections by source category/name. A collection's control updates its
saved preference and the session only when that collection is active. Full-player
shuffle toggles also update the active persistent source's preference. Starting
that collection, including tapping a specific song, applies its saved choice;
tapping a song retains that song as the start item. Temporary preview mode
commands do not overwrite collection preferences.

Playing Next rows are interactive: tapping one calls `playQueueItem` with its
playback-order index, which seeks to the matching raw item and starts it.
When the app returns to the foreground, `MainActivity.onStart` calls
`reconnectIfNeeded`; a disconnected MediaController silently ignores transport
commands, so reconnecting and re-hydrating the session queue is required before
play/next/previous can work again.

App controls call `skipToNext`; external MediaSession Next is intercepted by
the service callback and routed through `handleSessionNext`. Both converge on
`routeNextWithinPreview`: pending Discover handoff/recommendation/page owns Next
before the physical library queue fallback. A visible button-only fix misses
notification/session controls. Saving prepared assets calls `promotePreview`;
unprepared saves resolve/enqueue a durable job. UI becomes permanent only after
actual durable completion. See [cross-boundary routes](../project_brain.md).

## Player presentation

Portrait FullPlayer uses a full-width artwork hero occupying 65% of its available
viewport, fading into the neutral page. Title/artist sit below it, with Queue,
Favorite and a horizontal three-dot options menu to their right. A white seek bar
precedes repeat, previous, purple Play/Pause, next and shuffle controls. No app
footer or top back arrow appears; Android Back returns to the prior screen.
Metadata, seek and transport controls sit near the bottom of the initial viewport,
with a 16dp bottom inset; extra vertical space falls between artwork and metadata.
Discover mode
and recommendations appear only after scrolling; Discover mode uses an explicit
on/off switch, with no downward arrow or scroll-hint text. Session intent and
preview Next routing remain the owners of control state and advancement.

Both mini-player placements share a circled-plus action between Favorite and
Play/Pause. It opens the existing playlist picker for a snapshot of the tapped
library track URI. Membership writes run in `LunoApp.appScope` after dismissal;
repository outcomes supply added/already-present feedback. Transient or unindexed
tracks do not expose an enabled membership action.

## Evidence and direct checks

- [MusicControllerTest](../app/src/test/java/com/luno/mobile/playback/MusicControllerTest.kt):
  pending requests/generations/release, metadata, context/queue seams, errors,
  history restoration, independent collection shuffle persistence/rename and full-player synchronization, session play-intent toggling, and retaining the current
  item while a deferred context is prepared.
- [MusicServiceTest](../app/src/test/java/com/luno/mobile/playback/MusicServiceTest.kt):
  service recreation restores full queues with duplicate items, exact shuffle
  traversal, position/modes, edits/clearing, preview isolation and legacy fallback.
- [PlaybackQueueStoreTest](../app/src/test/java/com/luno/mobile/playback/PlaybackQueueStoreTest.kt):
  metadata/manual membership, generation-bound cursor updates, corrupt data,
  unsupported versions and invalid shuffle permutations.
- [RecommendationPreviewManagerTest](../app/src/test/java/com/luno/mobile/playback/RecommendationPreviewManagerTest.kt):
  handoff/session Next/page load, windows, prepared retention/back navigation,
  save deduplication/completion, timeout spinner clearing and failure retention
  through session cleanup, Discover queue fencing
  under Repeat All and full queue restoration around the still-playing seed, and
  resuming a deferred preview after natural end without overriding a newer play.
- [FullPlayerRecommendationStatusTest](../app/src/test/java/com/luno/mobile/ui/player/FullPlayerRecommendationStatusTest.kt):
  transport icons use session play intent; ready recommendations keep the artist
  line and expose a separate Ready to play icon.
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

## Temporary song videos

`LunoApp.temporaryVideoManager` owns the process-lifetime, opt-in video plan.
The song options menu toggles video mode; the existing artwork stage displays a
muted native Media3 video using the same full-bleed crop, dimensions and fade.
Audio remains owned by MusicService, and its position and actual playing state
control the visual decoder. Leaving the screen releases only the visual player.

The desktop-inspired plan stages the current track and at most five distinct
successors, including already-prepared Discover assets. NewPipe resolves up to
three title/artist/duration-matched candidates, preferring a quick MP4 at 480p or
below. Once the current quick video is ready, the manager stages a higher-quality
MP4 from that exact YouTube source, preferring 1080p then 720p, before prefetching
successors. HD preparation has a separate bounded fifteen-minute deadline. Transfers reuse
the validated parallel byte-range downloader on eligible YouTube CDN hosts,
falling back to a single stream for unsupported servers. The quick video remains
ready throughout; failed HD preparation
leaves it playing, and a decoder failure falls back to its quick artifact when
still cached. Generation checks fence late upgrades after skips or disabling.
The visual player retains its last frame while switching local files and seeks
to the authoritative audio clock without taking audio focus.
Downloads publish atomically into cache/song-videos after size/header checks;
individual files and the planned cache are capped at 384 MiB. Smaller MP4
variants of the same source are retried before changing song candidates (at most
three variants per candidate). These
files never enter Room or durable library downloads. Disabling cancels work;
generation checks reject stale completions, and timeouts/decoder failures expose
retry without interrupting audio. A fresh manager clears previous-session bytes
when video is enabled; resolved source identities persist separately.

`TemporaryVideoManagerTest`, `TemporaryVideoDownloaderTest` and
`TemporaryVideoPolicyTest` cover bounded planning, cancellation, fallback, timeout,
cache invalidation, HTTP publication and audio-clock drift. `PlayerOrientationTest`
and `DrawerRotationTest` render Compose regressions for landscape scrolling and controls
and stable closed-drawer anchors. The drawer uses a fixed width across rotation;
the shell applies horizontal system insets, and the full player disables drawer
swipes while retaining the phone's system Back navigation.

In landscape, the single-song artwork/video occupies the full initial app
viewport without a bottom fade. The same outer player list scrolls to song
metadata, seek/transport controls, Discover mode and recommendations below it;
there is no separate scrolling controls pane. Entering landscape resets a saved
portrait scroll offset to the artwork at the top. Portrait retains its fading art
and initially visible controls.

The general Library play control builds a complete library context in the
selected sort order, independently of search, filters or playlist membership.
A normal tap starts index zero with shuffle explicitly disabled. Holding for one
second toggles a saved Library shuffle preference without starting playback;
a subsequent tap starts a random item with Media3 shuffle enabled for the entire
library. Playlist screens continue to install their own scoped contexts.
