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
cascading foreign keys. REPLACE track inserts differ from IGNORE membership
inserts; inspect effects on relationships when changing insertion behavior.

Favorites live on `Track.isFavorite`. Favorites is a reserved virtual projection,
not an ordinary persisted membership owner. All Music is also virtual.
The mini-player heart resolves the current playable URI against the Room library
projection and delegates changes to `LibraryRepository.setFavorite`; temporary
recommendations without a saved library row cannot be favorited there. Both the
Home overlay and other screens share this state through `MainShell`.
Real playlists store `lastPlayedAt` beside their aggregate `playCount`. The
timestamp advances from the library playback callback when a non-transient
track with that playlist's `PlaybackSource` becomes current; opening a playlist
does not count as listening. Home orders these timestamps newest first, followed
by legacy playlist IDs from saved recent track history when their timestamps
are still zero. Its six quick shortcuts contain Liked Songs and up to five real
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
uses the selected record-shelf direction from [the design gallery](../design/README.md).
[LibraryHeader](../app/src/main/java/com/luno/mobile/ui/library/LibraryPresentation.kt)
adds a full-width, faded wall of up to six distinct cached covers above the green
body gradient. Library owns its header; the shell omits the Luno wordmark here.
The covers use equal square tiles independently of the foreground height; rows
repeat to cover the measured hero. The hero includes the playlist/song section
heading and fades out immediately before the first content row, keeping artwork
behind the complete header without shifting the controls or playlist positions.
The decoration is stable across search/view/sort changes, excludes
accessibility semantics and has no metadata or playback ownership. Cover images
remain upright. The initial view is Playlists; direct Songs/Playlists tabs retain
saveable view state and clear selection only when the view actually changes.
Search has the underline treatment from concept 05; search and sort use the
existing projections and shared `TrackSortMode` modes. Header counts include
the virtual Favorites playlist, consistently with the list.

[PlaylistCard](../app/src/main/java/com/luno/mobile/ui/components/PlaylistCard.kt)
renders larger flat artwork and actual song-count/total-duration metadata. The
virtual Favorites row has a green heart tile, the same geometry/type/divider as
other rows, and no destructive actions. Existing
selection, sync/cancellation, URL editing, clear/delete and export callbacks stay
owned by the screen/repositories. Header playback uses the sorted, filtered song
context and is disabled when that context is empty.

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

Folder copies need actual persisted **read and write** grants. Revocation,
provider failures and collisions require inspecting `reuseExisting` versus
`overwriteExisting`; these are distinct retry/promotion behaviors. Failed copy
must retain usable private downloaded audio and report the actual destination
with reselection advice. Avoid broad storage permissions or destructive cleanup
to repair missing grants. Reselect the tree and retry through normal owners.

## Artwork

[ArtworkStorage](../app/src/main/java/com/luno/mobile/data/artwork/ArtworkStorage.kt)
stores recoverable images in private `filesDir/artwork`; `Track.albumArtPath`
references them. Missing-art repair tries embedded art, completed-download
thumbnails, then remote cover search via
[ArtworkFetchService](../app/src/main/java/com/luno/mobile/data/artwork/ArtworkFetchService.kt).
The Settings quality scan tries larger YouTube thumbnails and cover search;
upgrade replaces a cached image only when decoded pixel area improves.
Rendering is in `ui/components/ArtworkImage.kt`, `ArtworkCollage.kt` and
`RecommendationArtwork.kt`; rendering state must not become persistence truth.

## Behavioral evidence

- [LibraryRepositoryTest](../app/src/test/java/com/luno/mobile/data/repository/LibraryRepositoryTest.kt),
  [MusicFolderRepositoryTest](../app/src/test/java/com/luno/mobile/data/repository/MusicFolderRepositoryTest.kt),
  [PlaylistRepositoryTest](../app/src/test/java/com/luno/mobile/data/repository/PlaylistRepositoryTest.kt),
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
