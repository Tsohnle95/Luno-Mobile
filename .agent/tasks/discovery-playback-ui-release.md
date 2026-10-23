# Luno Discovery playback, UI sync, and release

Status: IN_PROGRESS
Mode: PROJECT

## Goal and acceptance

- Fix the three reported Android issues: Discovery Mode continues into recommendations instead of the physical app playlist at song end; the in-app play/pause control reflects and controls actual playback; finished Discovery songs retain their artist name while queue/download status is shown separately.
- Preserve ordinary physical-playlist continuation, MediaSession/notification controls, download/save state, and library metadata.
- Prove each issue with focused regression coverage that fails before its fix and passes after.
- After canonical release verification and direct Android playback validation, publish the next version through the repository's tag-triggered GitHub Release workflow, with a signed APK downloadable from Releases.

## Context and verified facts

- Repo: `/Users/tdawg/code-repositories/Luno-Mobile`, remote `Tsohnle95/Luno-Mobile`, branch `main`, HEAD `416ce79`.
- Latest published baseline is `v1.0.8` / code `9`; `app/build.gradle.kts` now targets `1.0.9` / code `10`. GitHub's public latest release and origin tags were rechecked; `v1.0.9` does not exist yet.
- Canonical owners: `docs/playback-discovery.md`, `docs/android-development.md`, `ANDROID_RELEASES.md`; source router points playback/session/discovery behavior to `MusicController`, `MusicService`, `RecommendationPreviewManager`, and Discovery repository/UI.
- The release workflow runs on `v*`, builds/verifies a signed release APK, and auto-publishes it. It requires the configured GitHub signing secrets; never inspect or print them.
- Preserve pre-existing work: `.agent/tasks/update-handoff.md` is an active unrelated task, and `vendor/NewPipeExtractor` is dirty. Do not edit/reset/stage/commit either.
- Local gate toolchains Temurin 17 and 11 were checksum-verified and extracted under Hermes scratch; Android SDK is `~/Library/Android/sdk`. `gh` is absent, but a read-only `git push --dry-run` succeeded using the macOS keychain helper.
- On the last phone connection, `SM-G781W` (Android API 33) had Luno `1.0.8-dev` / code 9 installed and retained its library data after the signed debug update. The final 1.0.9-dev build is not yet installed; current `adb devices -l` reports no device.

## Plan and review

| Phase | Status | Validation/result | Checkpoint |
| --- | --- | --- | --- |
| Investigate current behavior and write red tests for each reported symptom | done | MediaSession test reproduced `isPlaying=false` with play intent true and showed toggle failed to pause; Discovery integration test reproduced the physical successor remaining queued before preview readiness; artist source contract identified the literal replacement | RED tests recorded |
| Implement minimal fixes and focused regressions | done | Focused playback/UI suites pass, including repeat-all isolation/restoration, request-revision-guarded preview insertion, late Discover natural-end handoff, and paused/playing ended-queue restoration | review findings resolved; regression suites pass |
| Review cumulative diff, update durable owner docs, run canonical release gate | done | `python3 scripts/verify.py`, `python3 scripts/verify.py --release` (`GITHUB_REF_NAME=v1.0.9`), and `:app:assembleDebugAndroidTest` pass. Independent review found no security or logic errors; the two restore-path test suggestions were added and the focused suite passes. Local release APK is unsigned | code review/gates passed |
| Exercise playback/UI on Android, bump version, publish and verify release APK | in progress | Earlier screenshot confirmed pause icon/intent and artist plus readiness cue. Instrumentation launch was rejected due signer mismatch; current `adb devices -l` shows no device, so final 1.0.9-dev device smoke remains unverified | no commit/tag/release |

Review user-reported behavior against producers, state ownership, and consumers. Distinguish automatic song-ended transitions from explicit Next, and distinguish MediaSession state from Compose UI projection. Keep artist metadata separate from temporary-queue status. Update this task as evidence changes; do not alter the active update-handoff task or vendor checkout.

## Continuation

- Last verified state: focused playback/UI suites, full debug verification, release verification, and debug instrumentation APK build pass. Local release artifact metadata is `com.luno.mobile`, versionCode `10`, versionName `1.0.9`; it is unsigned. GitHub latest remains `v1.0.8`; `origin/main` is `416ce79`; push dry-run succeeded through macOS Keychain.
- Risks: `gh` is unavailable; ADB currently reports no device, so the final update and playback smoke test are unverified. Instrumentation launch previously hit a signer mismatch. GitHub Actions must sign and verify the release APK. No commit, tag, or release exists yet. Preserve the active update-handoff task and dirty NewPipeExtractor submodule.
- Genuine remaining work: final diff review, direct Android acceptance, and the requested release publication.