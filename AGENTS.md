# AGENTS.md - Luno Mobile Operating Rules

## Start here

Read `project_brain.md` before changing the Android application. Source and
tests are authoritative when the brain document is stale. Inspect only the
files needed for the current task and preserve unrelated user changes.

## Mobile invariants

- Preserve namespace and application ID `com.luno.mobile`.
- Preserve Room schema history and explicit migrations.
- Preserve Media3 playback/session behavior, SAF/library behavior, downloads,
  WorkManager jobs, discovery, artwork, and transfer import/export semantics.
- Keep NewPipeExtractor reproducible through the pinned `vendor/NewPipeExtractor`
  submodule at v0.26.4. Do not rely on an untracked local checkout.
- Keep the v1 transfer fixtures in `contracts/library-export/v1/` compatible
  with Luno Desktop.
- Keep signing material and `local.properties` out of Git.

## Verification

With Java 17 and a configured Android SDK, run:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

For release verification, run `verifyReleaseVersion testDebugUnitTest
lintDebug assembleRelease` and validate a signed artifact with `apksigner`.

Do not treat a timeout, skipped required check, or truncated command as a pass.
