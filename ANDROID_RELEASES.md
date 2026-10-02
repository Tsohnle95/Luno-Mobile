# Android Releases

Owns signing, publication and the installed update/installer trust boundary.
[Development operations](docs/android-development.md) owns setup/debug verification;
build configuration and workflow are the technical authority for release settings.

Android downloads are published to the repository's GitHub Releases page:

`https://github.com/Tsohnle95/Luno-Mobile/releases/latest`

## Local Testing

Keep using the existing debug workflow:

```bash
./build-mobile.command
```

The underlying direct Gradle workflow remains:

```bash
./gradlew installDebug
```

The root helper builds and installs/launches when `adb` sees a connected device; without a device it builds the APK only. The debug variant does not use the release keystore or release secrets.

To iterate on a phone that already has a release installed, use the
[local phone development installer](docs/android-development.md#fast-phone-iteration-over-an-installed-release).
It re-signs the debug APK with your existing release key so `adb install -r`
preserves the library. Passwords stay in the private Terminal session. These
debug artifacts are local testing builds and must not be published as releases.

## Create The Signing Key

Generate the key once and keep the keystore and passwords backed up securely. Losing this key prevents future APKs from updating an installed app.

```bash
keytool -genkeypair -v \
  -keystore luno-release.jks \
  -alias luno \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Do not commit the keystore. The repository ignores `*.jks`, `*.keystore`, and `*.p12` files.

## Configure GitHub Secrets

In the repository, open **Settings → Secrets and variables → Actions → New repository secret** and add:

| Secret | Value |
|---|---|
| `ANDROID_RELEASE_KEYSTORE_BASE64` | Base64 contents of `luno-release.jks` |
| `ANDROID_RELEASE_STORE_PASSWORD` | Keystore password |
| `ANDROID_RELEASE_KEY_ALIAS` | `luno` or the alias chosen above |
| `ANDROID_RELEASE_KEY_PASSWORD` | Key password |

On macOS, copy the encoded keystore with:

```bash
base64 < luno-release.jks | pbcopy
```

## Publish A Release

1. Update `versionName` and increment `versionCode` in `app/build.gradle.kts`.
2. Run `python3 scripts/verify.py`; `installDebug` installs and is not validation.
   For release checks run `python3 scripts/verify.py --release` with signing
   configured, then verify the signed artifact as below.
3. Commit and push the version change.
4. Create and push a matching tag, for example:

```bash
git tag v0.1.0
git push origin v0.1.0
```

5. GitHub Actions runs `python3 scripts/verify.py --release` (documentation,
   `verifyReleaseVersion`, `testDebugUnitTest`, `lintDebug`, `assembleRelease`).
6. The workflow verifies that the tag matches `versionName` and verifies the APK signature.
7. A GitHub Release is created automatically with `Luno-Mobile-v0.1.0.apk` attached.

The Android app's **Home profile → Settings → App → Check for updates** entry shows
the installed version and checks the latest stable GitHub release. **Download &
install** requests Android's install permission first when needed. Enable **Allow
from this source** and return to Luno; the download starts automatically. The
screen shows percent/bytes, verification and installation preparation separately.
Closing the screen keeps the WorkManager download running; a status banner lets
you reopen it. **Cancel update** explicitly cancels the download. Android asks
for confirmation and keeps its full installation screen visible through security
checks and installation. Luno closes during replacement; tap Android's **Open**
button when installation finishes. A completion notification also offers **Open
Luno** when notifications are allowed. Luno confirms **Updated successfully**
using the actual installed version; that receipt stays until acknowledged.
Returning after cancellation keeps the verified APK ready to retry. Users can
also download directly from the release page.

The updater performing an upgrade is the version already installed. Updating
from 1.0.5/1.0.6 still uses their confirmation-only session handoff. Version 1.0.7
uses the full Android installer for subsequent updates and adds a package
replacement completion notification. Fresh installations do not display an
update success receipt.

The repository and release assets must be public for the anonymous in-app
updater. Do not embed a GitHub credential in the APK. A private repository returns
an access failure instead of update metadata. To test an update from a phone,
leave the preceding signed release installed, publish a newer version with the
same key and a higher versionCode, then run the phone's update check.

## Important Signing Rule

Every future release must use the same keystore and key alias. Never generate a replacement key for an existing application ID unless you intentionally want Android to treat it as a different app.

## Keep The Signing Identity Safe

The keystore contains the private signing key; a password or shared secret by
itself cannot recreate that key. A password can unlock a matching keystore if
you still have the keystore file. Do not send signing passwords or keystore
contents in chat.

Keep the release keystore in an encrypted password manager or encrypted vault,
with a second encrypted copy on offline storage in a separate location. Keep
the alias and both passwords in the password manager, and record the signing
certificate SHA-256 fingerprint so a restored copy can be checked. Keep the
keystore outside the repository; the GitHub Actions secrets above hold the CI
copy. Never store a plain keystore, password, or base64 copy in Git or an
unencrypted note.

The Android debug key is separate. Android/Gradle creates
`~/.android/debug.keystore` automatically, and a computer reset can create a
different debug certificate. A debug APK signed by the new key cannot update
an app signed by the old debug key. Backing up that debug keystore preserves
debug-over-debug installs, but published builds should use the one stable
release keystore described above. If the old debug keystore is gone, recover
it from a backup or export app data before a one-time uninstall; a secret
string alone does not resolve `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

## Release verification and update boundary

The [release workflow](.github/workflows/android-release.yml) decodes the base64
secret to a temporary keystore; Gradle consumes the four signing environment
variables listed in [app/build.gradle.kts](app/build.gradle.kts). Do not inspect
or print their values. Without all signing variables, a local release build may
be unsigned. `verifyReleaseVersion` only checks tag/version when `GITHUB_REF_NAME`
exists; a successful local build alone does not prove tagged/signing correctness.
Use matching release versions, increment versionCode, and verify a signed APK:

```bash
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose app/build/outputs/apk/release/app-release.apk
```

[GitHubReleaseService](app/src/main/java/com/luno/mobile/data/update/GitHubReleaseService.kt)
reads public latest-release metadata, compares normalized versions and prefers
the APK named for that release (falling back to the first APK asset).
[LunoApp](app/src/main/java/com/luno/mobile/LunoApp.kt) owns
[AppUpdateManager](app/src/main/java/com/luno/mobile/data/update/AppUpdateManager.kt).
[MainShell](app/src/main/java/com/luno/mobile/ui/shell/MainShell.kt) renders its
[AppUpdateHost](app/src/main/java/com/luno/mobile/ui/update/AppUpdateDialog.kt)
and status banner; the dialog delegates mutations. A private preferences
[UpdateTransactionStore](app/src/main/java/com/luno/mobile/data/update/UpdateTransactionStore.kt)
persists release identity, baseline installed code, worker/session IDs,
confirmation handoff and the acknowledged receipt. It is excluded from the
database-only backup rules and never changes Room metadata. One unique
[AppUpdateWorker](app/src/main/java/com/luno/mobile/data/update/AppUpdateWorker.kt)
owns download/verification and durable WorkManager byte progress. Per-worker
cache files keep obsolete downloads separate; a missing cache can be downloaded
again. Permission continuation is idempotent and only enqueues from its pending
phase. Startup repairs the gap between saving a transaction and enqueueing work.

Android's full APK installer owns native confirmation, security checks, progress
and its final Done/Open screen. The resumed Compose host launches an ACTION_VIEW
APK intent with a FileProvider content URI/read grant through an activity-result
launcher. Do not set EXTRA_RETURN_RESULT: Android must retain its completion UI.
Do not replace this with an app-owned session confirmation: confirming that
session finishes immediately and returns to Luno while installation continues.
Persist and claim the handoff once before launch. Returning without an installed
version change restores Ready with the cached APK and disables automatic retry.
Interrupted preparation also restores Ready. Legacy session fields and the
internal [UpdateInstallReceiver](app/src/main/java/com/luno/mobile/data/update/UpdateInstallReceiver.kt)
remain to tolerate callbacks from an upgrade initiated by 1.0.5/1.0.6; stale
session/worker results cannot change a newer transaction.

Success is reconciled against PackageManager's actual installed version/code.
[UpdateReplacedReceiver](app/src/main/java/com/luno/mobile/data/update/UpdateReplacedReceiver.kt)
handles the protected MY_PACKAGE_REPLACED broadcast after process replacement;
[UpdateCompletionNotifier](app/src/main/java/com/luno/mobile/data/update/UpdateCompletionNotifier.kt)
posts a one-time completion notification with an explicit Open Luno activity
PendingIntent when notification permission is already granted. Do not force a
background activity launch. First launch from older updaters uses installation
timestamps to bootstrap the receipt; acknowledgement and seen version prevent
repetition on subsequent launches.
[ApkInstaller](app/src/main/java/com/luno/mobile/data/update/ApkInstaller.kt)
requires an initial HTTPS `github.com` URL under this repository's release
download path, bounds bytes and follows only HTTPS redirects to GitHub's allowed
release hosts. Before publishing the cache file, it checks size and SHA-256 when
provided by release metadata, and compares Android-parsed package ID, signing
certificates, versionName and versionCode against the installed app and release.
It rejects a different key, another app, mismatched tag or non-increasing version
code; Android's installer performs its own final signature/install validation.
The APK is verified again before granting Android read access through FileProvider.
[Manifest](app/src/main/AndroidManifest.xml) and
[FileProvider paths](app/src/main/res/xml/file_paths.xml) own privileged permission,
receiver registration and APK cache exposure. Do not describe the initial-host
check as redirect validation.

[GitHubReleaseServiceTest](app/src/test/java/com/luno/mobile/data/update/GitHubReleaseServiceTest.kt)
covers metadata/version/HTTP outcomes and asset selection.
[ApkInstallerTest](app/src/test/java/com/luno/mobile/data/update/ApkInstallerTest.kt)
covers package/version/certificate rejection and initial URL restrictions.
[AppUpdateManagerTest](app/src/test/java/com/luno/mobile/data/update/AppUpdateManagerTest.kt)
and [UpdateTransactionStoreTest](app/src/test/java/com/luno/mobile/data/update/UpdateTransactionStoreTest.kt)
cover permission return without duplicate enqueue, cached retry/recovery, native
legacy session result filtering, full installer intent flags, single-launch
handoffs, cancelled-install recovery, notification permission and one-time
installed-version receipts. These checks use Robolectric and controlled workers; they do not prove
Android archive parsing, real permission, native installer launch or a signed
update over an existing installation. Those require device evidence with the
same signing key.
