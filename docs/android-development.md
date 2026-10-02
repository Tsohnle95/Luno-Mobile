# Android development and recovery

Owns setup, verification, platform coverage and debugging procedures.
[ANDROID_RELEASES.md](../ANDROID_RELEASES.md) owns signing/publishing/update
installation; [execution](agent-execution.md) owns task process. Build files and
workflows own actual toolchain configuration, not a duplicated version table.

## Setup and dependency authority

Use Java 17 to run Gradle (`JAVA_HOME` and Java on PATH should resolve consistently)
and a Java 11 compiler toolchain required by the pinned
[extractor build](../vendor/NewPipeExtractor/build.gradle.kts). Install both JDKs;
if Gradle cannot auto-discover 11, set `JAVA11_HOME` to its JDK home when running
the canonical gate. The wrapper passes it and setup-java's versioned JDK
variables to Gradle's `org.gradle.java.installations.fromEnv`. For direct Gradle
commands use `-Porg.gradle.java.installations.paths=/path/to/jdk11,/path/to/jdk17`.
See [Gradle toolchain discovery](https://docs.gradle.org/current/userguide/toolchains.html#sec:custom_loc).
Upstream extractor Checkstyle requires Java 21 and is a dependency of upstream
tests; this app gate does not run those tests or claim their coverage.
Use an Android SDK configured through `ANDROID_HOME` or untracked `local.properties`, and
Python 3.9+ for the standard-library verification wrapper. Use
[gradlew](../gradlew); its distribution is pinned in
[wrapper properties](../gradle/wrapper/gradle-wrapper.properties). SDK/application
settings live in [app Gradle](../app/build.gradle.kts); plugins/libraries in the
[version catalog](../gradle/libs.versions.toml). There are no dependency lockfiles
or dependency-verification metadata; the wrapper/catalog/gitlink supply current
pins, not a claim of fully locked transitive artifacts. Initial resolution needs
network access to configured repositories; later offline runs need populated caches.

Clone with `--recurse-submodules`, or run:

```bash
git submodule update --init --recursive
./gradlew --version
python3 scripts/verify.py
```

The tracked NewPipeExtractor gitlink is authoritative. The catalog version names
upstream intent; [settings](../settings.gradle.kts) substitutes the coordinate
with the vendor composite build. Preserve dirty vendor files and do not update
the pin during setup. If dirty/pin-mismatched, inspect and resolve explicitly
rather than resetting. SDK provisioning in CI includes the platform/build-tools
packages listed in the workflows. AGP currently suppresses its newer compile
SDK warning via [gradle.properties](../gradle.properties); preserve this known
compatibility choice until a separately validated toolchain upgrade.

## Verification and development

From any working directory, `python3 /path/to/repo/scripts/verify.py` uses the
repository root. The default canonical debug gate runs docs checks then
`./gradlew --no-daemon --console=plain testDebugUnitTest lintDebug assembleDebug`.
`--release` substitutes `verifyReleaseVersion testDebugUnitTest lintDebug assembleRelease`;
release signature verification remains mandatory and separate. `--docs-only`
needs no JDK/SDK/network. The full gate rejects a Java major inconsistent with
the source target and bounds Gradle at 20 minutes; the wrapper uses POSIX process
groups on Linux/macOS, matching CI and this local environment. Timeout terminates the process
group and returns failure. It does not install/launch an app or require a device.

For focused checks use the actual test class, for example:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.luno.mobile.data.repository.DownloadRepositoryTest'
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

[build-mobile.command](../build-mobile.command) builds a debug APK without adb,
or installs/launches on a connected device. `./gradlew installDebug` installs
without being a test gate. Reports live under `app/build/reports/tests/` and
`app/build/reports/`; APK outputs under `app/build/outputs/apk/`.

The checker validates local Markdown paths/fragments, linked Kotlin declarations,
source-line link avoidance, task template availability, schema-history/migration
surface, extractor HEAD versus gitlink (and tag when locally available), and CI
Java/SDK/build-tools/gate alignment. It ignores
external links, generated/vendor documentation and secret/local files. This
detects surface drift, not semantic truth, migration correctness or Desktop parity.

## CI and platform coverage

[Android verification](../.github/workflows/android-verify.yml) runs the canonical
debug gate on push/PR with recursive submodules, Temurin Java 11 + 17 and Android SDK.
[Release workflow](../.github/workflows/android-release.yml) runs the release gate
on version tags, verifies a signed APK and publishes it. Linux CI exercises
build/JVM/Robolectric/lint; it has no emulator/device/live-provider or macOS job.
Local macOS builds do not establish continuous macOS coverage.

Device tests are separate, with a connected supported device/emulator:

```bash
./gradlew connectedDebugAndroidTest
```

They cover session/playback/notification/recreation and test-provider imports.
They do not prove real SAF grants/provider restart behavior. For affected
acceptance, directly exercise appearance/navigation; app/external Next during
Discover preparation; background notification/recreation; picker import and
read/write destination grants across restart/revocation; cancel/retry/process
death with downloads and sync; live extraction/Last.fm; and sideload update
permission/installer as relevant. Report device/API/provider and observations.
No dedicated migration, worker/sync, import-recovery or APK installer suite
currently supplies those assurances; owners document the precise gaps.

## Debugging and recovery

- Build failures: inspect Java/SDK/wrapper version, submodule HEAD/gitlink and
  dependency resolution before changing app code. Never read signing values or
  `local.properties` contents for cartography. Retry sandbox/network failures
  with required permission; do not label incomplete resolution a passing gate.
- Crash: `LunoApp` appends uncaught failures to private `filesDir/crash_log.txt`,
  then invokes the previous handler. The file has no in-app Settings entry.
  Device `adb logcat` can corroborate; avoid logging API keys or user-private data.
- Stuck downloads: trace Room job ID/work ID → WorkManager terminal state →
  `reconcileActiveDownloads`; restart/periodic reconciliation is the repair path.
  Do not clear app data or delete usable private audio as a generic repair.
- SAF: inspect actual persisted grants, reselect the tree, and reproduce the
  provider error. A saved URI string alone does not grant access.
- Preview: distinguish process-local preparation from a durable job; restart
  clears temporary assets. Follow generations/cancellation before forcing state.
- Interrupted coding: resume through [.agent/tasks/README.md](../.agent/tasks/README.md),
  current source/tests and Git diff/checkpoints. Permanent docs are current truth,
  not an investigation log.
