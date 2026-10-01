# Luno Library Export v1

This is a metadata-only transfer format named `luno.library.export` with
`manifest_version` equal to `1`.

Owns v1 compatibility/semantic guarantees and Mobile transfer orchestration.
[schema.json](schema.json) owns machine-readable wire shape; fixtures and codec
tests are executable evidence. Does not own audio storage, worker lifecycle,
playback or task execution.

## Guarantees

- Local paths and audio bytes are never included.
- Secrets, queue state, favorites, and playback history are never included.
- Unknown fields are rejected.
- Track references are bounded identifiers, not filesystem paths.
- A track without a provider identity must explicitly set
  `ambiguity_confirmation_required` to `true`.
- Imports are additive; consumers must not delete existing library content.

[schema.json](schema.json) describes the wire shape. Consumers must also enforce the
semantic rules documented here and in their implementation tests, including
the virtual `All Music` playlist restriction and reference uniqueness.

Desktop and Mobile consumers must accept [valid.json](valid.json) and reject
[invalid-unknown-field.json](invalid-unknown-field.json). Fixtures are intentionally
duplicated with Luno Desktop; this repository cannot alone prove current Desktop
parity. Compare both consumers/fixtures when changing the contract.

## Mobile path and state

[LibraryManifest](../../../app/src/main/java/com/luno/mobile/data/export/LibraryManifest.kt)
defines values; [LibraryManifestCodec](../../../app/src/main/java/com/luno/mobile/data/export/LibraryManifestCodec.kt)
enforces field/type/ref bounds, supported provider identities, safe playlist
names, reference uniqueness, virtual All Music restrictions and explicit
ambiguity for metadata-only tracks. Encoding validates through decoding.

[LibraryTransferRepository](../../../app/src/main/java/com/luno/mobile/data/repository/LibraryTransferRepository.kt)
owns `buildFullLibraryManifest`, selected-track/playlist exports, `previewImport`
and `importManifest`. Export snapshots metadata and ordered membership; completed
download provenance supplies provider identity rather than local playable paths.
Favorites/play counts are local library state and are not transfer fields.
`sourceByTrackUri` derives identity from completed-job thumbnail/source URLs,
not `DownloadJob.videoId`. Prepared-preview promotion and unprepared enqueue
carry different provenance fields; validate both save branches when changing
identity, sync deduplication or transfer export. Do not assume the newer job
video ID automatically participates in transfer matching.

[ExportImportScreen](../../../app/src/main/java/com/luno/mobile/ui/export/ExportImportScreen.kt)
reads a bounded SAF document, validates/previews matching and requests ambiguity
confirmation. Provider-bearing tracks match by provider identity; metadata-only
tracks use normalized artist/title. Existing playlist names match normalized
names. Matched membership application is additive/transactional. Missing-track
resolution and download scheduling are a separate phase, so whole import is
not one atomic transaction. Missing non-YouTube providers remain unresolved;
confirmed metadata-only tracks use search. Resolved tracks enqueue through
[downloads](../../../docs/downloads.md), then ordinary worker finalization adds
memberships. [Library/storage](../../../docs/library-storage.md) owns Room shape.

## Changes and evidence

Identity/format changes require tracing Desktop + schema/fixtures → model/codec
→ matching and preview/confirmation → download provenance/enqueue → worker
membership publication → library UI. Preserve unknown-field rejection; do not
silently make a versioned format permissive. Version changes need explicit
consumer compatibility and recovery validation, not just fixture edits.

[LibraryManifestCodecTest](../../../app/src/test/java/com/luno/mobile/data/export/LibraryManifestCodecTest.kt)
covers shared fixtures, roundtrip, unknown fields, unsafe names, invalid
refs/version and ambiguity.
[LibraryTransferRepositoryTest](../../../app/src/test/java/com/luno/mobile/data/repository/LibraryTransferRepositoryTest.kt)
currently covers full/selected export, not additive import/matching/queued-download
recovery. Those paths need targeted evidence when changed; no broad compatibility
claim follows from existing export tests alone.
