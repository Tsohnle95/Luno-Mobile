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
