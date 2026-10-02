package com.luno.mobile.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.luno.mobile.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppUpdateState(
    val transaction: UpdateTransaction? = null,
    val checking: Boolean = false,
    val checked: ReleaseCheckResult? = null,
    val bytes: Long = 0L,
    val total: Long = 0L,
    val preparationProgress: Float = 0f,
    val receipt: String? = null
)

/** Owns one update across screens, permission activities, worker execution and APK replacement. */
class AppUpdateManager(private val context: Context, private val scope: CoroutineScope) {
    private val store = UpdateTransactionStore(context.getSharedPreferences("app_updates", Context.MODE_PRIVATE))
    private val workManager = WorkManager.getInstance(context)
    private val installer = context.packageManager.packageInstaller
    private val mutableState = MutableStateFlow(AppUpdateState(transaction = store.load()))
    val state = mutableState.asStateFlow()
    private var checkJob: Job? = null

    init {
        reconcileInstalled()
        recoverDownload()
        // A killed staging coroutine cannot complete its session; keep the verified download for retry.
        state.value.transaction?.takeIf {
            it.phase == UpdatePhase.STAGING || (it.phase == UpdatePhase.INSTALLING &&
                it.sessionId?.let(installer::getSessionInfo)?.isSealed == false)
        }?.let { transaction ->
            transaction.sessionId?.let { runCatching { installer.abandonSession(it) } }
            save(transaction.copy(phase = UpdatePhase.READY, sessionId = null, autoInstall = false,
                message = "Installation preparation was interrupted. Tap Install update to continue."))
        }
        scope.launch(Dispatchers.Main.immediate) {
            workManager.getWorkInfosForUniqueWorkFlow(AppUpdateWorker.UNIQUE_WORK).collect { work ->
                val transaction = state.value.transaction ?: return@collect
                if (transaction.phase !in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)) return@collect
                val info = work.firstOrNull { it.id.toString() == transaction.workId } ?: return@collect
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> save(transaction.copy(phase = UpdatePhase.READY))
                    WorkInfo.State.FAILED -> save(transaction.copy(phase = UpdatePhase.FAILED,
                        message = info.outputData.getString(AppUpdateWorker.ERROR) ?: "Download failed. Please retry."))
                    WorkInfo.State.CANCELLED -> save(transaction.copy(phase = UpdatePhase.FAILED,
                        message = "The update download was cancelled. You can retry."))
                    else -> {
                        val phase = if (info.progress.getBoolean(AppUpdateWorker.VERIFYING, false)) UpdatePhase.VERIFYING else UpdatePhase.DOWNLOADING
                        if (phase != transaction.phase) save(transaction.copy(phase = phase))
                        val bytes = info.progress.getLong(AppUpdateWorker.BYTES, state.value.bytes)
                        val total = info.progress.getLong(AppUpdateWorker.TOTAL, state.value.total)
                        mutableState.value = state.value.copy(bytes = bytes, total = total)
                    }
                }
            }
        }
    }

    fun check() {
        if (state.value.transaction != null || state.value.checking) return
        checkJob = scope.launch(Dispatchers.Main.immediate) {
            mutableState.value = state.value.copy(checking = true, checked = null)
            val result = GitHubReleaseService().checkForUpdate(BuildConfig.VERSION_NAME)
            mutableState.value = state.value.copy(checking = false, checked = result)
        }
    }

    fun begin(release: GitHubRelease) {
        if (state.value.transaction != null || release.apkUrl == null) return
        checkJob?.cancel()
        mutableState.value = state.value.copy(checking = false, bytes = 0L, total = release.apkSize)
        save(UpdateTransaction(release, installedCode(), UpdatePhase.PERMISSION))
        continueAfterPermission()
    }

    /** Idempotent: only the permission phase may enqueue a worker or retry installation. */
    fun continueAfterPermission() {
        val transaction = state.value.transaction ?: return
        if (transaction.phase != UpdatePhase.PERMISSION || !canInstall()) return
        if (downloadExists(transaction)) save(transaction.copy(phase = UpdatePhase.READY)) else enqueue(transaction)
    }

    fun onForeground() {
        reconcileInstalled()
        continueAfterPermission()
        val transaction = state.value.transaction ?: return
        if (transaction.phase in setOf(UpdatePhase.INSTALLING, UpdatePhase.CONFIRM) &&
            transaction.sessionId?.let(installer::getSessionInfo) == null) {
            save(transaction.copy(phase = UpdatePhase.FAILED, sessionId = null, confirmationUri = null,
                message = "Android did not complete the update. Tap Retry installation to continue."))
        }
    }

    private fun recoverDownload() {
        val transaction = state.value.transaction ?: return
        if (transaction.phase !in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)) return
        scope.launch(Dispatchers.Main.immediate) {
            val workId = transaction.workId?.let(java.util.UUID::fromString)
            val info = withContext(Dispatchers.IO) { workId?.let { workManager.getWorkInfoById(it).get() } }
            if (info == null && state.value.transaction == transaction) enqueue(transaction)
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    private fun enqueue(transaction: UpdateTransaction) {
        val request = OneTimeWorkRequestBuilder<AppUpdateWorker>().setInputData(workDataOf(
            AppUpdateWorker.RELEASE to transaction.release.copy(notes = "", name = "", releaseUrl = "").toJson().toString()
        )).build()
        save(transaction.copy(phase = UpdatePhase.DOWNLOADING, workId = request.id.toString(),
            sessionId = null, confirmationUri = null, message = null, autoInstall = true))
        mutableState.value = state.value.copy(bytes = 0L, total = transaction.release.apkSize)
        workManager.enqueueUniqueWork(AppUpdateWorker.UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun retry() {
        val transaction = state.value.transaction ?: return
        if (transaction.phase != UpdatePhase.FAILED && transaction.phase != UpdatePhase.READY) return
        transaction.sessionId?.let { runCatching { installer.abandonSession(it) } }
        save(transaction.copy(phase = UpdatePhase.PERMISSION, autoInstall = true, message = null,
            sessionId = null, confirmationUri = null, confirmationLaunched = false))
        continueAfterPermission()
    }

    fun installReady() {
        val transaction = state.value.transaction ?: return
        if (transaction.phase != UpdatePhase.READY) return
        if (!canInstall()) {
            save(transaction.copy(phase = UpdatePhase.PERMISSION))
            return
        }
        if (!downloadExists(transaction)) {
            enqueue(transaction)
            return
        }
        save(transaction.copy(phase = UpdatePhase.STAGING, message = null, autoInstall = false))
        mutableState.value = state.value.copy(preparationProgress = 0f)
        scope.launch(Dispatchers.Main.immediate) {
            var sessionId: Int? = null
            try {
                val file = AppUpdateWorker.updateFile(context, requireNotNull(transaction.workId))
                withContext(Dispatchers.IO) {
                    try { ApkInstaller.verify(context, file, transaction.release) }
                    catch (e: Exception) { file.delete(); throw e }
                }
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(file.length())
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                sessionId = installer.createSession(params)
                save(requireNotNull(state.value.transaction).copy(sessionId = sessionId))
                installer.openSession(sessionId).use { session ->
                    withContext(Dispatchers.IO) {
                        session.openWrite("base.apk", 0L, file.length()).use { output ->
                            file.inputStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                var written = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    written += count
                                    val progress = written.toFloat() / file.length()
                                    withContext(Dispatchers.Main.immediate) {
                                        mutableState.value = state.value.copy(preparationProgress = progress)
                                    }
                                }
                            }
                            session.fsync(output)
                        }
                    }
                    save(requireNotNull(state.value.transaction).copy(phase = UpdatePhase.INSTALLING))
                    val callback = Intent(context, UpdateInstallReceiver::class.java)
                        .setAction("com.luno.mobile.UPDATE_INSTALL_RESULT")
                    val pendingIntent = PendingIntent.getBroadcast(context, sessionId, callback,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(pendingIntent.intentSender)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessionId?.let { runCatching { installer.abandonSession(it) } }
                state.value.transaction?.let {
                    save(it.copy(phase = UpdatePhase.FAILED, sessionId = null,
                        message = e.message ?: "Could not prepare the update. Retry installation."))
                }
            }
        }
    }

    fun installResult(sessionId: Int, status: Int, confirmation: Intent?, message: String?) {
        val transaction = state.value.transaction ?: return
        if (!acceptInstallResult(transaction, sessionId)) return
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirmation == null) {
                    save(transaction.copy(phase = UpdatePhase.FAILED, message = "Android did not provide an installation prompt. Retry installation."))
                } else {
                    save(transaction.copy(phase = UpdatePhase.CONFIRM,
                        confirmationUri = confirmation.toUri(Intent.URI_INTENT_SCHEME), confirmationLaunched = false))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> reconcileInstalled()
            else -> {
                runCatching { installer.abandonSession(sessionId) }
                val detail = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    "Installation was cancelled. Your downloaded update is ready to retry."
                } else "Android could not install the update${message?.take(250)?.let { ": $it" }.orEmpty()}. Your library has not been changed."
                save(transaction.copy(phase = UpdatePhase.FAILED, sessionId = null, confirmationUri = null, message = detail))
            }
        }
    }

    fun confirmationIntent(): Intent? {
        val transaction = state.value.transaction ?: return null
        if (transaction.phase !in setOf(UpdatePhase.CONFIRM, UpdatePhase.INSTALLING)) return null
        val intent = transaction.confirmationUri?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
            ?: return null
        save(transaction.copy(phase = UpdatePhase.INSTALLING, confirmationLaunched = true))
        return intent
    }

    fun confirmationFailed() {
        state.value.transaction?.let { save(it.copy(phase = UpdatePhase.FAILED,
            message = "Android's confirmation screen could not open. Retry installation.")) }
    }

    fun permissionFailed() {
        state.value.transaction?.let { save(it.copy(phase = UpdatePhase.FAILED,
            message = "Could not open Android's permission screen. Allow installs for Luno in Android Settings, then retry.")) }
    }

    fun cancel() {
        val transaction = state.value.transaction ?: return
        if (transaction.phase in setOf(UpdatePhase.STAGING, UpdatePhase.CONFIRM, UpdatePhase.INSTALLING)) return
        // Clear the owner before cancellation so late worker output cannot resurrect the transaction.
        save(null)
        transaction.sessionId?.let { runCatching { installer.abandonSession(it) } }
        transaction.workId?.let { workManager.cancelWorkById(java.util.UUID.fromString(it)) }
        deleteDownload(transaction)
        mutableState.value = state.value.copy(bytes = 0L, total = 0L)
    }

    fun acknowledgeReceipt() {
        store.acknowledge()
        mutableState.value = state.value.copy(receipt = null)
    }

    private fun downloadExists(transaction: UpdateTransaction): Boolean =
        transaction.workId?.let { AppUpdateWorker.updateFile(context, it).isFile } == true

    private fun deleteDownload(transaction: UpdateTransaction) {
        transaction.workId?.let { AppUpdateWorker.updateFile(context, it).delete() }
    }

    private fun save(transaction: UpdateTransaction?) {
        store.save(transaction)
        mutableState.value = state.value.copy(transaction = transaction)
    }

    @Suppress("DEPRECATION")
    private fun installedCode(): Long = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    @Suppress("DEPRECATION")
    private fun reconcileInstalled() {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val before = state.value.transaction
        val receipt = store.reconcile(installed.versionName.orEmpty(), installed.longVersionCode,
            installed.lastUpdateTime > installed.firstInstallTime)
        val transaction = store.load()
        if (before != null && transaction == null) deleteDownload(before)
        mutableState.value = state.value.copy(transaction = transaction, receipt = receipt)
    }
}

internal fun acceptInstallResult(transaction: UpdateTransaction, sessionId: Int): Boolean =
    transaction.sessionId == sessionId && transaction.phase in setOf(
        UpdatePhase.STAGING, UpdatePhase.CONFIRM, UpdatePhase.INSTALLING
    )
