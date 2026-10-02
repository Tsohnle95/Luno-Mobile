package com.luno.mobile.data.update

import android.content.Context
import android.content.Intent
import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.SettableFuture
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppUpdateManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var scope: CoroutineScope
    private lateinit var worker: ListenableWorker
    private val release = GitHubRelease("v1.0.5", "1.0.5", "Luno", "", "https://github.com/release", "https://github.com/apk")
    private val store get() = UpdateTransactionStore(context.getSharedPreferences("app_updates", Context.MODE_PRIVATE))

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().clear().commit()
        val installed = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
        installed.versionName = "1.0.4"
        installed.setLongVersionCode(5L)
        installed.firstInstallTime = 100L
        installed.lastUpdateTime = 100L
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    object : ListenableWorker(appContext, workerParameters) {
                        override fun startWork() = SettableFuture.create<Result>()
                    }.also { worker = it }
            }).build())
    }

    @After
    fun tearDown() {
        scope.cancel()
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
        Dispatchers.resetMain()
    }

    @Test
    fun permissionReturn_enqueuesOnceAndReopeningDoesNotEnqueueAgain() {
        val manager = AppUpdateManager(context, scope)
        manager.begin(release)
        assertThat(manager.state.value.transaction?.phase).isEqualTo(UpdatePhase.PERMISSION)
        assertThat(manager.state.value.transaction?.workId).isNull()
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        manager.continueAfterPermission()
        val id = manager.state.value.transaction?.workId
        assertThat(id).isNotNull()
        manager.continueAfterPermission()
        manager.onForeground()
        assertThat(manager.state.value.transaction?.workId).isEqualTo(id)
        assertThat(store.load()?.workId).isEqualTo(id)
        manager.begin(release)
        assertThat(manager.state.value.transaction?.workId).isEqualTo(id)
    }

    @Test
    fun permissionAndVerifiedDownload_surviveManagerReplacement() {
        val workId = UUID.randomUUID().toString()
        AppUpdateWorker.updateFile(context, workId).writeText("verified download placeholder")
        store.save(UpdateTransaction(release, 5L, UpdatePhase.PERMISSION, workId = workId))
        val manager = AppUpdateManager(context, scope)
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        manager.onForeground()
        assertThat(manager.state.value.transaction?.phase).isEqualTo(UpdatePhase.READY)
        assertThat(manager.state.value.transaction?.workId).isEqualTo(workId)
        manager.onForeground()
        assertThat(manager.state.value.transaction?.phase).isEqualTo(UpdatePhase.READY)
        manager.cancel()
        assertThat(AppUpdateWorker.updateFile(context, workId).exists()).isFalse()
    }

    @Test
    fun matchingNativeResult_isTrackedAndCancelledInstallationKeepsDownload() {
        val workId = UUID.randomUUID().toString()
        val file = AppUpdateWorker.updateFile(context, workId).apply { writeText("placeholder") }
        store.save(UpdateTransaction(release, 5L, UpdatePhase.INSTALLING, workId, sessionId = 42))
        val manager = AppUpdateManager(context, scope)
        manager.installResult(41, PackageInstaller.STATUS_FAILURE, null, "old callback")
        assertThat(manager.state.value.transaction?.phase).isEqualTo(UpdatePhase.INSTALLING)
        val confirmation = Intent("android.content.pm.action.CONFIRM_INSTALL")
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, 42)
        manager.installResult(42, PackageInstaller.STATUS_PENDING_USER_ACTION, confirmation, null)
        assertThat(manager.state.value.transaction?.phase).isEqualTo(UpdatePhase.CONFIRM)
        assertThat(manager.confirmationIntent()?.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)).isEqualTo(42)
        assertThat(store.load()?.confirmationLaunched).isTrue()
        manager.installResult(42, PackageInstaller.STATUS_FAILURE_ABORTED, null, null)
        assertThat(manager.state.value.transaction?.phase).isEqualTo(UpdatePhase.FAILED)
        assertThat(manager.state.value.receipt).isNull()
        assertThat(file.exists()).isTrue()
        manager.cancel()
    }

    @Test
    fun nativeSuccess_doesNotClaimSuccessUntilInstalledVersionActuallyChanges() {
        store.save(UpdateTransaction(release, 5L, UpdatePhase.INSTALLING, sessionId = 42))
        val manager = AppUpdateManager(context, scope)
        manager.installResult(42, PackageInstaller.STATUS_SUCCESS, null, null)
        assertThat(manager.state.value.receipt).isNull()
        val installed = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
        installed.versionName = "1.0.5"
        installed.setLongVersionCode(6L)
        installed.lastUpdateTime = 200L
        manager.onForeground()
        assertThat(manager.state.value.receipt).isEqualTo("1.0.5")
        assertThat(manager.state.value.transaction).isNull()
        manager.acknowledgeReceipt()
        manager.onForeground()
        assertThat(manager.state.value.receipt).isNull()
    }

    @Test
    fun workerByteProgressAndVerification_areRestoredAfterManagerReplacement() = runBlocking {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        val manager = AppUpdateManager(context, scope)
        manager.begin(release)
        val workId = manager.state.value.transaction?.workId
        worker.setProgressAsync(workDataOf(AppUpdateWorker.BYTES to 512L, AppUpdateWorker.TOTAL to 1024L)).get(5, TimeUnit.SECONDS)
        withTimeout(5_000) { manager.state.first { it.bytes == 512L } }
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val restored = AppUpdateManager(context, scope)
        withTimeout(5_000) { restored.state.first { it.bytes == 512L } }
        assertThat(restored.state.value.total).isEqualTo(1024L)
        assertThat(restored.state.value.transaction?.workId).isEqualTo(workId)
        worker.setProgressAsync(workDataOf(AppUpdateWorker.VERIFYING to true)).get(5, TimeUnit.SECONDS)
        withTimeout(5_000) { restored.state.first { it.transaction?.phase == UpdatePhase.VERIFYING } }
        Unit
    }

    @Test
    fun interruptedCommit_keepsDownloadAndAbandonsUncommittedSession() {
        val installer = context.packageManager.packageInstaller
        val session = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        store.save(UpdateTransaction(release, 5L, UpdatePhase.INSTALLING, sessionId = session))
        val manager = AppUpdateManager(context, scope)
        assertThat(manager.state.value.transaction?.phase).isEqualTo(UpdatePhase.READY)
        assertThat(manager.state.value.transaction?.autoInstall).isFalse()
        assertThat(installer.getSessionInfo(session)).isNull()
    }

    @Test
    fun savedTransactionWithoutEnqueuedWorker_isRecovered() = runBlocking {
        val missingWork = UUID.randomUUID().toString()
        store.save(UpdateTransaction(release, 5L, UpdatePhase.DOWNLOADING, workId = missingWork))
        val manager = AppUpdateManager(context, scope)
        val recovered = withTimeout(5_000) { manager.state.first { it.transaction?.workId != missingWork } }
        assertThat(recovered.transaction?.phase).isEqualTo(UpdatePhase.DOWNLOADING)
        assertThat(recovered.transaction?.workId).isNotNull()
    }

    @Test
    fun fullInstaller_keepsNativeProgressAndCompletionAndGrantsReadAccess() {
        val uri = Uri.parse("content://com.luno.mobile.fileprovider/update/update.apk")
        val intent = ApkInstaller.fullInstallerIntent(uri)
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data).isEqualTo(uri)
        assertThat(intent.type).isEqualTo("application/vnd.android.package-archive")
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isEqualTo(0)
        assertThat(intent.hasExtra(Intent.EXTRA_RETURN_RESULT)).isFalse()
        assertThat(intent.hasExtra(PackageInstaller.EXTRA_SESSION_ID)).isFalse()
    }

    @Test
    fun fullInstaller_launchesOnceAndCancellationKeepsVerifiedDownload() {
        val workId = UUID.randomUUID().toString()
        val file = AppUpdateWorker.updateFile(context, workId).apply { writeText("verified placeholder") }
        store.save(UpdateTransaction(release, 5L, UpdatePhase.READY, workId))
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        val uri = Uri.parse("content://com.luno.mobile.fileprovider/update/update.apk")
        var preparations = 0
        val manager = AppUpdateManager(context, scope) { _, _ ->
            preparations++
            ApkInstaller.fullInstallerIntent(uri)
        }
        manager.installReady()
        manager.installReady()
        assertThat(preparations).isEqualTo(1)
        assertThat(store.load()?.phase).isEqualTo(UpdatePhase.CONFIRM)
        val intent = manager.confirmationIntent()!!
        assertThat(intent.data).isEqualTo(uri)
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(manager.confirmationIntent()).isNull()
        manager.onForeground()
        assertThat(manager.state.value.transaction?.phase).isEqualTo(UpdatePhase.INSTALLING)
        val restored = AppUpdateManager(context, scope)
        restored.onForeground()
        assertThat(restored.state.value.transaction?.phase).isEqualTo(UpdatePhase.INSTALLING)
        assertThat(restored.confirmationIntent()).isNull()
        restored.onInstallerReturned()
        assertThat(restored.state.value.transaction?.phase).isEqualTo(UpdatePhase.READY)
        assertThat(restored.state.value.transaction?.autoInstall).isFalse()
        assertThat(restored.state.value.receipt).isNull()
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun fullInstaller_returnAfterReplacementConfirmsActualVersion() {
        store.save(UpdateTransaction(release, 5L, UpdatePhase.INSTALLING))
        val manager = AppUpdateManager(context, scope)
        val installed = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
        installed.versionName = "1.0.5"
        installed.setLongVersionCode(6L)
        manager.onInstallerReturned()
        assertThat(manager.state.value.transaction).isNull()
        assertThat(manager.state.value.receipt).isEqualTo("1.0.5")
    }

    @Test
    fun fullInstaller_preparationFailureAllowsRetryWithoutClaimingSuccess() {
        val workId = UUID.randomUUID().toString()
        AppUpdateWorker.updateFile(context, workId).writeText("placeholder")
        store.save(UpdateTransaction(release, 5L, UpdatePhase.READY, workId))
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        val manager = AppUpdateManager(context, scope) { _, _ -> error("Cannot open APK") }
        manager.installReady()
        assertThat(manager.state.value.transaction?.phase).isEqualTo(UpdatePhase.FAILED)
        assertThat(manager.state.value.transaction?.message).isEqualTo("Cannot open APK")
        assertThat(manager.state.value.receipt).isNull()
    }

    @Test
    fun replacementNotification_requiresPermissionAndAnActualUpdate() {
        val manager = AppUpdateManager(context, scope)
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        context.getSystemService(NotificationManager::class.java).cancelAll()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.onPackageReplaced()
        assertThat(notifications.allNotifications).isEmpty()
        val installed = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
        installed.versionName = "1.0.5"
        installed.setLongVersionCode(6L)
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.onPackageReplaced()
        assertThat(notifications.allNotifications).isEmpty()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.onPackageReplaced()
        val notification = notifications.allNotifications.single()
        assertThat(notification.extras.getString(Notification.EXTRA_TITLE)).isEqualTo("Luno 1.0.5 installed")
        assertThat(notification.actions.single().title.toString()).isEqualTo("Open Luno")
        assertThat(shadowOf(notification.contentIntent).savedIntent.component?.className).isEqualTo("com.luno.mobile.MainActivity")
        manager.acknowledgeReceipt()
        manager.onPackageReplaced()
        assertThat(notifications.allNotifications).isEmpty()
    }
}
