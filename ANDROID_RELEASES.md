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

The Android app's **Settings → App → Check for updates** entry reads this release tag and finds the attached APK. **Download APK** downloads it into the app cache and opens Android's package installer; the first install may require enabling **Allow Luno to install unknown apps**. Users can also download it directly from the release page.

## Important Signing Rule

Every future release must use the same keystore and key alias. Never generate a replacement key for an existing application ID unless you intentionally want Android to treat it as a different app.

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
reads public latest-release metadata, compares normalized versions and selects
the first APK asset. [MainShell](app/src/main/java/com/luno/mobile/ui/shell/MainShell.kt)
invokes [ApkInstaller](app/src/main/java/com/luno/mobile/data/update/ApkInstaller.kt)
on IO, handles errors and routes unknown-source permission to Android settings.
The installer requires an initial HTTPS `github.com` URL and bounded bytes,
stages in cache, then opens Android's package installer with a temporary read
grant. Default OkHttp redirects are enabled; the app does not independently
verify APK signature, checksum or package before opening the installer.
[Manifest](app/src/main/AndroidManifest.xml) and
[FileProvider paths](app/src/main/res/xml/file_paths.xml) own privileged permission
and cache exposure. Do not describe the initial-host check as redirect validation.

[GitHubReleaseServiceTest](app/src/test/java/com/luno/mobile/data/update/GitHubReleaseServiceTest.kt)
covers metadata/version/HTTP outcomes; no installer test exists. Real permission,
installer launch and update-over-existing-install require device evidence and
the same signing key.
