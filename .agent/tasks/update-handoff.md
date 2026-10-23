# Native update completion handoff

Status: ACTIVE
Mode: PROJECT

## Acceptance and preserved behavior

- Keep permission-first continuation, durable WorkManager download/verification, cached retries and actual-version success receipts.
- Keep Android's full installation UI alive through Play Protect, package replacement and its Done/Open completion screen; avoid confirmation-only sessions that return to a waiting Luno process.
- Clearly describe the installation stage, prevent duplicate installer launches and recover native cancellation without redownloading.
- Record actual replacement and expose a completion notification with a direct Open Luno action where notification permission is already granted.
- Preserve package/signing identity, music metadata/favorites, playback/SAF, and all Home/settings changes in 1.0.6. No new privileged permissions or background activity launch tricks.

## Verified facts and replan

- Phone SM_G781W, Android API 33, installed 1.0.6/code7, firstInstallTime still 18:23:27.
- Native log: original updater foregrounded again after confirmation/security popup, then PackageManager killed Luno on installPackageLI at 23:45:21; applied its session at 23:45:23; user reopened at 23:45:34. Data retained.
- Earlier implementation incorrectly assumed direct PackageInstaller confirmation preserves Android's full progress/completion UI. Android source distinguishes confirming an app-owned session from installing a supplied APK through InstallInstalling.
- Replan: Android owns the full APK installation activity via ACTION_VIEW/FileProvider. Luno owns download/trust checks and persistent receipt. Do not use EXTRA_RETURN_RESULT to skip Android's completion screen. Observe activity return only as incomplete/cancelled when actual version remains unchanged; protected MY_PACKAGE_REPLACED records real completion and notification.
- Preserve the old internal session receiver/fields to tolerate pending handoffs from installed 1.0.6 while upgrading to the repair.

## Plan

1. Replace app-owned session staging/commit with verified full installer intent; single claim and activity-result recovery.
2. Busy installation copy/indicator; full system progress and Done/Open; replacement completion notification and durable receipt.
3. Trust, launch flags, cancellation, recreation, completion/notification checks; canonical gate and owner docs.
4. Publish signed 1.0.7; verify signature and directly exercise the full system installer on the connected phone while preserving data.

## Continuation

- Full APK intent/read grant and single-launch activity-result handoff implemented. Protected replacement receiver and permission-aware completion notification added. Legacy pending sessions remain supported.
- Updated release/architecture owners. Focused updater checks passed; canonical gate passed (279 tests, zero failures/errors/skips; docs, lint and debug APK). Cumulative diff reviewed; unrelated extractor dirt preserved.
- Publishing signed 1.0.7 next, then full native installer device check.

- Pre-install device UI: Favorites contains 14 songs / 4 hr 41 min; top rows Black and White, Never Enough (Re:um Remix), Re:um - Oh Lordy. Keep these for after-install comparison.

- Committed/pushed b667832 on main and tagged v1.0.7. Signed release gate succeeded: https://github.com/Tsohnle95/Luno-Mobile/actions/runs/36972292530. Anonymous latest release confirmed v1.0.7.
- Published APK /private/tmp/Luno-Mobile-v1.0.7.apk verified: com.luno.mobile, code8/name1.0.7; size54754179; SHA256955bd2a5548ff7047e06750f53fee3a2880f6b04d2eed5b8b1c05fd86a1c5c6c; stable signing certificate SHA2567f9e99531f58c4c11fdb44b021ba256db2e7418aa49eee275c9c4780a5e945f6.
- Direct native installation test remains pending: phone disconnected before publication and adb shows no devices. Async reconnect/unlock request sent; no reply yet. Do not claim final native screen or post-install favorites verified. When reconnected, use the trusted signed APK through the full Android installer (not adb install as UX proof), observe Done/Open, verify actual version/firstInstallTime and 14 favorites retained. Do not uninstall. Delete this task only once completed or explicitly cancelled.
