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

The Android app's **Settings → App updates** entry shows the installed version,
checks the latest stable GitHub release and finds its attached APK. **Download &
install** shows progress, verifies the APK, then opens Android's package installer.
The first install may require enabling **Allow Luno to install unknown apps**;
returning from that screen continues with the already downloaded APK. Android
still asks the user to confirm the update. Older app versions expose the check
under **Settings → App → Check for updates**. Users can also download directly
from the release page.

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
[MainShell](app/src/main/java/com/luno/mobile/ui/shell/MainShell.kt) opens
[AppUpdateDialog](app/src/main/java/com/luno/mobile/ui/update/AppUpdateDialog.kt),
which owns check/download progress, cancellation, errors and install permission
continuation. Dialog work survives navigation behind it, not process death;
verified private cache can be reused after another check without downloading again.
[ApkInstaller](app/src/main/java/com/luno/mobile/data/update/ApkInstaller.kt)
requires an initial HTTPS `github.com` URL under this repository's release
download path, bounds bytes and follows only HTTPS redirects to GitHub's allowed
release hosts. Before publishing the cache file, it checks size and SHA-256 when
provided by release metadata, and compares Android-parsed package ID, signing
certificates, versionName and versionCode against the installed app and release.
It rejects a different key, another app, mismatched tag or non-increasing version
code; Android's installer performs its own final signature/install validation.
Opening the installer grants temporary read access to the cache file.
[Manifest](app/src/main/AndroidManifest.xml) and
[FileProvider paths](app/src/main/res/xml/file_paths.xml) own privileged permission
and cache exposure. Do not describe the initial-host check as redirect validation.

[GitHubReleaseServiceTest](app/src/test/java/com/luno/mobile/data/update/GitHubReleaseServiceTest.kt)
covers metadata/version/HTTP outcomes and asset selection.
[ApkInstallerTest](app/src/test/java/com/luno/mobile/data/update/ApkInstallerTest.kt)
covers package/version/certificate rejection and initial URL restrictions using
metadata values. These tests do not prove Android archive parsing, real permission,
installer launch or an update over an existing installation; those need device
evidence with the same signing key.
