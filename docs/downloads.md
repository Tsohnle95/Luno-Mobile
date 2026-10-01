# Downloads, extraction and playlist sync

Owns durable download job orchestration, source resolution, worker/retry/cancel
lifecycles and preview promotion. [Library/storage](library-storage.md) owns
metadata/SAF grants; [playback/discovery](playback-discovery.md) owns temporary
preview state; [transfer](../contracts/library-export/v1/README.md) owns wire semantics.

## Owners and contracts

[DownloadRepository](../app/src/main/java/com/luno/mobile/data/repository/DownloadRepository.kt)
owns `enqueueDownload`, retry/cancel/history, Unsorted repair, preview promotion,
playlist-sync enqueue, destination copying and `reconcileActiveDownloads`.
Room [DownloadJob](../app/src/main/java/com/luno/mobile/data/db/entity/DownloadJob.kt)
and [DownloadJobDao](../app/src/main/java/com/luno/mobile/data/db/dao/DownloadJobDao.kt)
own observable durable status/history/provenance/destination; WorkManager owns
execution. Per-job work IDs, tags, connected-network constraints and exponential
backoff connect them. [DownloadsScreen](../app/src/main/java/com/luno/mobile/ui/downloads/DownloadsScreen.kt)
consumes job states. For a new durable state, trace the entity/default/migration,
DAO hardcoded active-state queries (`getActiveDownloadsOnce`, playlist queries
and video-identity queries), repository/worker/reconciliation transitions and
UI state branches together; changing the enum alone is insufficient.
Capture thumbnail and chosen playlists at enqueue and
preserve them through retry. Missing destination maps to Unsorted, including
legacy membership repair and finalization.

[DownloadWorker](../app/src/main/java/com/luno/mobile/playback/DownloadWorker.kt)
validates HTTP(S) input, upgrades HTTP to HTTPS, uses foreground execution,
throttles Room progress, transfers media, derives duration/artwork and attempts
SAF copy. Track + memberships + completed job publish in a Room transaction.
Successful SAF copy becomes the playable URI; redundant private audio is
removed after commit. Failed SAF copy retains usable private audio and completes
with actual-destination notice; provider failure is not a network retry.
Artwork is best effort. Ordinary streaming writes directly to private output;
duration extraction failure may yield zero and still complete. Do not claim
full media validation or atomic filesystem/database publication.

Cancellation cleans output and marks Room cancellation in `NonCancellable`.
Network IO retries are bounded by worker attempt policy. `LunoApp` reconciles at
startup and periodically: enqueued/blocked/running remain active; cancelled,
failed or missing work cannot leave permanent progress. SUCCEEDED with an active
Room row is failed finalization, because success must already commit completion
with the Track. Follow Room → worker → reconciliation → LibraryData/UI together.

## Sync producers and promotion

[PlaylistSyncWorker](../app/src/main/java/com/luno/mobile/playback/PlaylistSyncWorker.kt)
enumerates YouTube playlist entries, reuses library tracks, adds membership,
deduplicates jobs using persisted video identity plus legacy URL matching, then
enqueues independent download workers tagged by playlist. Stop All/per-playlist
cancel must stop producers **and** consumers; cancelling one consumer alone can
allow the producer to enqueue more. Provenance changes also affect
[transfer export](../contracts/library-export/v1/README.md).

`promotePreview` canonicalizes a source under the private preview-cache root,
requires a still-existing destination playlist, copies to a unique durable
private path and transactionally inserts track/membership/completed history.
Failed database publication rolls back durable output/created SAF documents.
Retain path containment and unique output names; prepared cache is untrusted
input to permanent publication. Destination collision/reuse semantics live in
[MusicFolderRepository](../app/src/main/java/com/luno/mobile/data/repository/MusicFolderRepository.kt).

## Extraction and ranges

[WebSearchService](../app/src/main/java/com/luno/mobile/playback/WebSearchService.kt)
owns YouTube search/playlist extraction and audio-resolution fallbacks:
NewPipe → Invidious → InnerTube. Preview resolution limits fallback attempts;
durable resolution permits broader fallback. Inspect error/cancellation
behavior when changing modes.
[NewPipeDownloader](../app/src/main/java/com/luno/mobile/playback/NewPipeDownloader.kt)
is initialized by LunoApp; the tracked extractor commit is the dependency
authority, substituted by [settings](../settings.gradle.kts). Upgrade catalog
intent and gitlink/tag together, retain core-library desugaring and validate
search, playlist extraction, preview, download and transfer provenance. Never
edit/vendor an untracked checkout as a permanent repair.

[ParallelRangeDownloader](../app/src/main/java/com/luno/mobile/playback/ParallelRangeDownloader.kt)
only ranges eligible `googlevideo.com` hosts. It bounds concurrency, verifies
206/redirect host/Content-Range/length/consistent total, assembles ordered chunks
and verifies final bytes. Rejection/cancellation cleans chunks/target and allows
ordinary-stream fallback; cancellation cancels blocked calls. Keep validation
at this transport boundary rather than trusting a CDN response by hostname.

## Evidence and gaps

[DownloadRepositoryTest](../app/src/test/java/com/luno/mobile/data/repository/DownloadRepositoryTest.kt)
covers orphan reconciliation, URL rejection, destination/thumbnail durability,
Unsorted repair, unique preview paths and promotion rollback.
[ParallelRangeDownloaderTest](../app/src/test/java/com/luno/mobile/playback/ParallelRangeDownloaderTest.kt)
covers assembly, unsupported/malformed ranges, clean fallback and cancellation.
[DownloadJobDaoTest](../app/src/test/java/com/luno/mobile/data/db/DownloadJobDaoTest.kt)
is DAO evidence. There are no dedicated DownloadWorker/PlaylistSyncWorker tests
or live extractor checks. Worker foreground/retry/process-death behavior,
real destination grants, external providers and producer cancellation require
targeted integration/device checks in [operations](android-development.md).
