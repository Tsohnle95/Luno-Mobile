# Library, persistence and storage

Owns durable library metadata, ordered playlists, SAF import/destination grants
and artwork caches. Does not own [job execution](downloads.md),
[playback/history](playback-discovery.md) or [transfer wire semantics](../contracts/library-export/v1/README.md).

## Entry points and state

- [LibraryRepository](../app/src/main/java/com/luno/mobile/data/repository/LibraryRepository.kt):
  `importAudioUri`, `importLibraryTree`, `updateTrackMetadata`, `setFavorite`,
  `recordPlayback`, `fetchMissingArtwork`, `upgradeArtwork`.
- [PlaylistRepository](../app/src/main/java/com/luno/mobile/data/repository/PlaylistRepository.kt):
  ordered membership mutation; [PlaylistDao](../app/src/main/java/com/luno/mobile/data/db/dao/PlaylistDao.kt)
  owns queries including `observePlaylistTracks`.
- [MusicFolderRepository](../app/src/main/java/com/luno/mobile/data/repository/MusicFolderRepository.kt):
  selected tree URI preferences, `saveTreeUri`, actual persisted read/write
  grants, `copyFileToSelectedFolder` and destination fallback notice.
- [MusicFolderImportManager](../app/src/main/java/com/luno/mobile/ui/shell/MusicFolderImportManager.kt)
  and [ArtworkFetchManager](../app/src/main/java/com/luno/mobile/ui/shell/ArtworkFetchManager.kt)
  run in `LunoApp.appScope`; navigation does not cancel them, process death does.
- [LibraryData](../app/src/main/java/com/luno/mobile/data/repository/LibraryData.kt)
  eagerly projects Room flows into app-lifetime StateFlows for Compose. This is
  an observable cache, not a competing persistence owner.

[AppDatabase](../app/src/main/java/com/luno/mobile/data/db/AppDatabase.kt),
[entities](../app/src/main/java/com/luno/mobile/data/db/entity/) and
[schema history](../app/schemas/com.luno.mobile.data.db.AppDatabase/) own the
database shape/version; retain explicit migrations and every historic snapshot.
Tracks are keyed by playable URI; membership uses playlist ID + track URI with
cascading foreign keys. `Track.videoId` is durable YouTube provenance used by
playlist sync, so a completed download remains recognizable after job-history
cleanup. REPLACE track inserts differ from IGNORE membership inserts; inspect
effects on relationships when changing insertion behavior.
Playlist membership adds are duplicate-aware: `PlaylistRepository` returns
`ADDED`/`ALREADY_PRESENT` for one song and an added/already-present summary for
batches, while the DAO still inserts with IGNORE for atomic concurrency safety.
UI success text must come from that outcome, never from the tap itself, and
`PlaylistPickerSheet` marks playlists that already contain the selected song.
Sheet mutations that must outlive dismissal (playlist/favorite/delete actions)
run in `LunoApp.appScope`; a `rememberCoroutineScope` tied to a sheet is
cancelled as soon as the sheet leaves composition.

Favorites live on `Track.isFavorite`. Favorites is a reserved virtual projection,
not an ordinary persisted membership owner. All Music is also virtual.
The mini-player heart resolves the current playable URI against the Room library
projection and calls `LibraryRepository.toggleFavorite`, which flips the stored
flag and returns the new value; writes run in the application scope so leaving
the mini player cannot cancel them. Do not derive the new value from a
composition snapshot, which can be stale after reconnects. Both the
Home overlay and other screens share this state through `MainShell`.
Real playlists store `lastPlayedAt` beside their aggregate `playCount`. The
timestamp advances from the library playback callback when a non-transient
track with that playlist's `PlaybackSource` becomes current; opening a playlist
does not count as listening. Home orders these timestamps newest first, followed
by legacy playlist IDs from saved recent track history when their timestamps
are still zero. Its six quick shortcuts contain Favorite Songs and up to five real
playlists, filling unused recent slots from the library. Jump Back In uses recent
tracks; Your Shows currently projects library playlists, not podcast episodes.
Existing rows migrate with
`lastPlayedAt = 0`; older playlists absent from saved track history cannot be
ordered from the aggregate play count.
Use `sortOrder`/ordered membership queries; a Room relation does not promise
order. [PlaylistDetailScreen](../app/src/main/java/com/luno/mobile/ui/library/PlaylistDetailScreen.kt)
uses `sortedByPlaylistMembership` in
[SortChip](../app/src/main/java/com/luno/mobile/ui/components/SortChip.kt) for its
default order; explicit Recently added uses the track's library-added time.
[SortChipTest](../app/src/test/java/com/luno/mobile/ui/components/SortChipTest.kt)
exercises sort labels/modes and ordering helpers, but its separation case uses
identical membership/recent order. Changes need a discriminating case or direct
check with different orders. Metadata edits preserve unrelated
track fields. Repository track/playlist removal is metadata-only and must not
unexpectedly delete source audio. Download destination publication is owned by
[downloads](downloads.md).

## Library presentation

[LibraryScreen](../app/src/main/java/com/luno/mobile/ui/library/LibraryScreen.kt)
implements **01 / The familiar library** from the
[disposable 25-direction study](../design/library-25-disposable.html).
[LibraryHeader](../app/src/main/java/com/luno/mobile/ui/library/LibraryPresentation.kt)
uses a compact Luno settings badge, title, search and create actions, Playlists / Songs /
Albums tabs, sort menu, playlist list/grid toggle and selection menu.
Search opens a neutral filled field and preserves the query across filter changes.
The initial Playlists tab lists virtual Favorites and playlists. Songs shows the
track collection; Albums is a future-use placeholder and does not change metadata.
Filter and playlist layout state are saveable;
changing the filter clears selection only when the filter actually changes.
Grid and list layouts reuse the same playlist callbacks and metadata. Song rows
remain a list. Selection applies to the current tab, while virtual Favorites stays excluded
from destructive bulk actions.

The shell uses a flat charcoal `PrimaryBackground` across every route, including
beneath the transparent status bar and during route transitions. Library has no
corner glow; the mini-player uses a flat neutral surface. Playlist detail and
the full player fade their artwork into the neutral page background. Library
owns its header; the shell omits the Luno wordmark here.
Search and sort use existing projections and shared `TrackSortMode` modes. Library
and in-playlist search trim surrounding whitespace before matching, so a trailing
space (for example "Swimming ") does not hide a valid song; a whitespace-only
query matches everything. Favorites and Unsorted keep their reserved positions
above other playlists independently of sorting.

[PlaylistCard](../app/src/main/java/com/luno/mobile/ui/components/PlaylistCard.kt)
uses compact 54dp square artwork, a single-line playlist title and type/song-count
metadata. Grid entries enlarge the same artwork above their title. Favorites uses
a flat purple heart tile, with no destructive options or separate decorative card.
Existing selection, sync/cancellation, URL editing, clear/delete and export callbacks stay
owned by the screen/repositories. Song playback uses the sorted, filtered song
context so Next/Previous follows the visible song order.

Playlist detail uses a viewport-relative tall cover with a bottom fade, no app
wordmark or top back arrow, a compact purple Play control, and a horizontal-dot
sort menu. Search, shuffle, favorites-first and sort semantics remain unchanged.
Changing sort mode or favorites-first returns the list to its top, so switching
from playlist order to Recently added reveals the newest rows instead of
retaining the old scroll index. Android Back owns return navigation.

## SAF flow, failures and recovery

Picker → import manager → persisted root grant + saved tree URI → bounded
provider traversal/metadata extraction → DAO inserts/membership → LibraryData.
Root grants cover descendants; do not request persistable grants for child tree
URIs. Identity deduplication uses provider authority + document ID across URI
forms. Descendant URIs must retain the original tree root. Provider fallbacks,
self-child filtering, depth limits and bounded blocking work defend against
bad listings/hangs. Unlistable folders count as errors. The manager's stall
watchdog resolves visible progress/warns while work can continue; do not treat
that as WorkManager persistence or guaranteed completion.

Audio import rejects macOS `._` sidecars even when their extension/MIME claims
audio. Filename identity is retained before the bounded native metadata probe;
artwork extraction has a separate deadline and cannot erase recovered names.
An unreadable tag or timeout therefore falls back to the original filename,
rather than publishing an unnecessary Unknown Track placeholder.

**Settings → Library → Repair missing song details** retries incomplete records
through [LibraryRepository](../app/src/main/java/com/luno/mobile/data/repository/LibraryRepository.kt).
It uses exact completed-download URI/document identity, embedded tags, then
filename metadata. It never guesses from remote search or overwrites valid
fields. Before mutation it writes a synced private JSON snapshot under
`filesDir/metadata-repairs`, retaining original URI, metadata, favorite/history
fields and ordered playlist memberships. Failure to save the snapshot aborts
repair. Conditional column updates preserve user edits made during the scan and
leave other track fields and relationships untouched. Artwork writers also
update only the artwork column, so stale scans cannot revert repaired names.
The application-owned [MetadataRepairManager](../app/src/main/java/com/luno/mobile/ui/shell/MetadataRepairManager.kt)
keeps progress across navigation and reports unresolved titles/artists and
skipped sidecars. Existing sidecar rows and all source audio remain untouched;
retry is safe after interruption. Process death stops the scan; it is not a
durable WorkManager job. [MetadataRecoveryTest](../app/src/test/java/com/luno/mobile/data/repository/MetadataRecoveryTest.kt)
covers timeout fallback, sidecar rejection, exact provenance, field preservation,
backup failure, concurrent edits, stale artwork and repeat runs.

Folder copies need actual persisted **read and write** grants. Revocation,
provider failures and collisions require inspecting `reuseExisting` versus
`overwriteExisting`; these are distinct retry/promotion behaviors. Failed copy
must retain usable private downloaded audio and report the actual destination
with reselection advice. Avoid broad storage permissions or destructive cleanup
to repair missing grants. Reselect the tree and retry through normal owners.

## Artwork

[ArtworkStorage](../app/src/main/java/com/luno/mobile/data/artwork/ArtworkStorage.kt)
stores recoverable images in private `filesDir/artwork`; `Track.albumArtPath`
references them. Artwork never belongs in the selected SAF media tree: download,
preview promotion and folder sync copy playable audio only, because that tree may
be a camera roll or other user media folder. Missing-art repair tries embedded
art, completed-download thumbnails, then remote cover search via
[ArtworkFetchService](../app/src/main/java/com/luno/mobile/data/artwork/ArtworkFetchService.kt).
The Settings quality scan tries larger YouTube thumbnails and cover search;
upgrade replaces a cached image only when decoded pixel area improves.
Rendering is in `ui/components/ArtworkImage.kt`, `ArtworkCollage.kt` and
`RecommendationArtwork.kt`; rendering state must not become persistence truth.

## Behavioral evidence

- [LibraryRepositoryTest](../app/src/test/java/com/luno/mobile/data/repository/LibraryRepositoryTest.kt),
  [MusicFolderRepositoryTest](../app/src/test/java/com/luno/mobile/data/repository/MusicFolderRepositoryTest.kt),
  [PlaylistRepositoryTest](../app/src/test/java/com/luno/mobile/data/repository/PlaylistRepositoryTest.kt),
  [PlaylistAddMessageTest](../app/src/test/java/com/luno/mobile/ui/components/PlaylistAddMessageTest.kt),
  [LibrarySearchTest](../app/src/test/java/com/luno/mobile/ui/library/LibrarySearchTest.kt),
  [DuplicateFinderTest](../app/src/test/java/com/luno/mobile/data/repository/DuplicateFinderTest.kt).
- [TrackDaoTest](../app/src/test/java/com/luno/mobile/data/db/TrackDaoTest.kt),
  [PlaylistDaoTest](../app/src/test/java/com/luno/mobile/data/db/PlaylistDaoTest.kt).
  [AppDatabaseTest](../app/src/test/java/com/luno/mobile/data/db/AppDatabaseTest.kt)
  is abstract in-memory setup, **not migration validation**. No dedicated
  migration test exists; persistence changes need actual upgrade evidence.
- [LibraryRepositoryArtworkFetchTest](../app/src/test/java/com/luno/mobile/data/repository/LibraryRepositoryArtworkFetchTest.kt),
  [ArtworkFetchServiceTest](../app/src/test/java/com/luno/mobile/data/artwork/ArtworkFetchServiceTest.kt),
  [ArtworkCollageTest](../app/src/test/java/com/luno/mobile/ui/components/ArtworkCollageTest.kt),
  [ArtworkFetchManagerTest](../app/src/test/java/com/luno/mobile/ui/shell/ArtworkFetchManagerTest.kt).
  These cover missing-art fetching/rendering/manager behavior; no dedicated
  `upgradeArtwork` pixel-quality regression exists. Quality replacement changes
  need focused evidence that lower-quality candidates retain the old cache.
- [LibrarySmokeTest](../app/src/androidTest/java/com/luno/mobile/storage/LibrarySmokeTest.kt)
  uses a fake permission persister and test FileProvider. Real picker/providers,
  durable grants across restart, removable storage and UI appearance require
  [direct device checks](android-development.md).

Home and Library share the purple L profile/settings button. The launcher uses
the provided purple L artwork in adaptive and density-specific Android assets.
The Library filter row also exposes library-wide play: normal mode starts the
first track in the selected sort order; a one-second hold toggles shuffle for
subsequent taps. Shuffle state is visibly distinct — an accent ring and a
green shuffle badge on the play button — and the hold briefly shows a
green "Shuffle enabled" or red "Shuffle off" label above it for one second.
The circular background is clipped, not the whole button box, so the badge and
feedback remain visible. This action ignores search and filter projections and
sends the complete library queue; playlist playback retains its own context.

Navigation's destination-driven cover observes back-stack entry identity, so
system Back, restored tabs and explicit navigation all cover the incoming
screen's first frame. The cover renders above Home's mini-player overlay and
restarts its hold/readiness window for each new destination. Its opaque and
fading layer consumes pointer input so hidden screen actions cannot activate.
