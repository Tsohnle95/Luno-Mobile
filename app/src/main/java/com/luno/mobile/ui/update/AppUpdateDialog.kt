package com.luno.mobile.ui.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luno.mobile.BuildConfig
import com.luno.mobile.data.update.AppUpdateManager
import com.luno.mobile.data.update.AppUpdateState
import com.luno.mobile.data.update.ReleaseCheckResult
import com.luno.mobile.data.update.UpdatePhase
import com.luno.mobile.ui.theme.AccentGreen
import com.luno.mobile.ui.theme.PrimaryText
import com.luno.mobile.ui.theme.SecondaryText
import com.luno.mobile.ui.theme.SurfaceDark
import java.util.Locale

/** Always composed by the shell; resumes permissions and native confirmation only in foreground. */
@Composable
fun AppUpdateHost(manager: AppUpdateManager, isOpen: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by manager.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle, manager) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_RESUME) manager.onForeground()
        }
        lifecycle.addObserver(observer)
        manager.onForeground()
        onDispose { lifecycle.removeObserver(observer) }
    }
    val transaction = state.transaction
    LaunchedEffect(transaction?.phase, transaction?.autoInstall, transaction?.confirmationLaunched, isOpen, resumed) {
        if (!isOpen || !resumed) return@LaunchedEffect
        if (transaction?.phase == UpdatePhase.READY && transaction.autoInstall) manager.installReady()
        if (transaction?.phase == UpdatePhase.CONFIRM && !transaction.confirmationLaunched) {
            manager.confirmationIntent()?.let { intent ->
                try { context.startActivity(intent) } catch (_: Exception) { manager.confirmationFailed() }
            }
        }
    }
    if (isOpen) {
        AppUpdateDialog(manager, onDismiss)
    } else if (state.receipt != null) {
        AlertDialog(
            onDismissRequest = manager::acknowledgeReceipt,
            containerColor = SurfaceDark,
            title = { Text("Updated successfully", color = PrimaryText) },
            text = { Text("Luno ${state.receipt} is installed. Your library and favorites are still here.", color = SecondaryText) },
            confirmButton = { TextButton(onClick = manager::acknowledgeReceipt) { Text("Done", color = AccentGreen) } }
        )
    }
}

@Composable
fun AppUpdateBanner(manager: AppUpdateManager, onOpen: () -> Unit) {
    val state by manager.state.collectAsStateWithLifecycle()
    if (state.transaction == null) return
    Surface(color = SurfaceDark, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("App update · ${stageLabel(state)}", color = PrimaryText, style = MaterialTheme.typography.bodySmall)
            Text("View", color = AccentGreen, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AppUpdateDialog(manager: AppUpdateManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by manager.state.collectAsStateWithLifecycle()
    val transaction = state.transaction
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        manager.continueAfterPermission()
    }
    fun permission() {
        try {
            permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")))
        } catch (_: Exception) { manager.permissionFailed() }
    }
    LaunchedEffect(Unit) { manager.check() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        titleContentColor = PrimaryText,
        textContentColor = SecondaryText,
        title = { Text(if (state.receipt != null) "Updated successfully" else "App updates") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Installed: Luno ${BuildConfig.VERSION_NAME}", color = PrimaryText)
                Spacer(Modifier.height(16.dp))
                if (state.receipt != null) {
                    Text("Luno ${state.receipt} is installed. Your library and favorites are still here.")
                } else if (transaction != null) {
                    Text("Updating to ${transaction.release.version}", color = PrimaryText)
                    Spacer(Modifier.height(12.dp))
                    Text("1 Allow installs  →  2 Download  →  3 Install", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    Text(stageLabel(state), color = PrimaryText)
                    Spacer(Modifier.height(12.dp))
                    when (transaction.phase) {
                        UpdatePhase.PERMISSION -> Text("Android needs permission once. Tap Allow installs, turn on Allow from this source, then return to Luno. The update will continue automatically.")
                        UpdatePhase.DOWNLOADING -> {
                            if (state.total > 0L) {
                                LinearProgressIndicator(progress = { (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = AccentGreen)
                            } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AccentGreen)
                            Spacer(Modifier.height(8.dp))
                            Text("${megabytes(state.bytes)} MB${if (state.total > 0L) " of ${megabytes(state.total)} MB" else " downloaded"}")
                            Spacer(Modifier.height(8.dp))
                            Text("You can close this screen. The download will continue.", style = MaterialTheme.typography.bodySmall)
                        }
                        UpdatePhase.VERIFYING -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AccentGreen)
                            Spacer(Modifier.height(8.dp))
                            Text("Checking the download, app version and signing key before installation.")
                        }
                        UpdatePhase.READY -> Text(transaction.message ?: "Download verified. Ready to open Android's update screen.")
                        UpdatePhase.STAGING -> {
                            LinearProgressIndicator(progress = { state.preparationProgress }, modifier = Modifier.fillMaxWidth(), color = AccentGreen)
                            Spacer(Modifier.height(8.dp))
                            Text("Preparing the verified APK for Android…")
                        }
                        UpdatePhase.CONFIRM, UpdatePhase.INSTALLING -> Text("Confirm Update in Android's screen. Android shows installation progress. Luno will confirm the installed version when you reopen it.")
                        UpdatePhase.FAILED -> Text(transaction.message ?: "The update did not finish. Please retry.")
                    }
                } else if (state.checking) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AccentGreen)
                    Spacer(Modifier.height(12.dp))
                    Text("Checking the latest GitHub release…")
                } else when (val result = state.checked) {
                    is ReleaseCheckResult.UpdateAvailable -> {
                        Text("Luno ${result.release.version} is available", color = PrimaryText)
                        Spacer(Modifier.height(12.dp))
                        Text(result.release.notes.ifBlank { "A new release is ready to download." }, modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()))
                        Spacer(Modifier.height(12.dp))
                        Text("Your library and favorites stay on this phone.", style = MaterialTheme.typography.bodySmall)
                        if (result.release.apkUrl == null) Text("The release APK is not available yet. Check again shortly.")
                    }
                    is ReleaseCheckResult.UpToDate -> Text("You're up to date. No newer release is available.")
                    is ReleaseCheckResult.Failure -> Text(result.message)
                    null -> Text("Check GitHub for the latest release.")
                }
            }
        },
        confirmButton = {
            when {
                state.receipt != null -> Button(onClick = { manager.acknowledgeReceipt(); onDismiss() }) { Text("Done") }
                transaction?.phase == UpdatePhase.PERMISSION -> Button(onClick = ::permission) { Text("Allow installs") }
                transaction?.phase in setOf(UpdatePhase.READY, UpdatePhase.FAILED) -> Button(onClick = manager::retry) {
                    Text(if (transaction?.workId != null && transaction.phase == UpdatePhase.FAILED) "Retry update" else "Install update")
                }
                transaction?.phase in setOf(UpdatePhase.CONFIRM, UpdatePhase.INSTALLING) && transaction?.confirmationUri != null -> TextButton(onClick = {
                    manager.confirmationIntent()?.let { intent ->
                        try { context.startActivity(intent) } catch (_: Exception) { manager.confirmationFailed() }
                    }
                }) { Text("Open Android installer", color = AccentGreen) }
                transaction != null -> TextButton(onClick = onDismiss) { Text("Keep using Luno", color = AccentGreen) }
                state.checked is ReleaseCheckResult.UpdateAvailable -> {
                    val release = (state.checked as ReleaseCheckResult.UpdateAvailable).release
                    Button(enabled = release.apkUrl != null, onClick = {
                        manager.begin(release)
                        if (manager.state.value.transaction?.phase == UpdatePhase.PERMISSION) permission()
                    }) { Text("Download & install") }
                }
                !state.checking -> TextButton(onClick = manager::check) { Text("Check again", color = AccentGreen) }
            }
        },
        dismissButton = {
            Row {
                if (transaction != null && transaction.phase !in setOf(UpdatePhase.STAGING, UpdatePhase.CONFIRM, UpdatePhase.INSTALLING)) {
                    TextButton(onClick = manager::cancel) { Text("Cancel update", color = SecondaryText) }
                }
                TextButton(onClick = onDismiss) { Text("Close", color = SecondaryText) }
            }
        }
    )
}

private fun megabytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f", bytes / (1024.0 * 1024.0))
private fun stageLabel(state: AppUpdateState): String = when (state.transaction?.phase) {
    UpdatePhase.PERMISSION -> "Permission needed"
    UpdatePhase.DOWNLOADING -> if (state.total > 0L) "Downloading · ${(state.bytes * 100L / state.total).coerceIn(0, 100)}%" else "Downloading"
    UpdatePhase.VERIFYING -> "Verifying download"
    UpdatePhase.READY -> "Ready to install"
    UpdatePhase.STAGING -> "Preparing installation"
    UpdatePhase.CONFIRM -> "Confirm in Android"
    UpdatePhase.INSTALLING -> "Waiting for Android to finish"
    UpdatePhase.FAILED -> "Update needs attention"
    null -> "App updates"
}
