package com.luno.mobile.ui.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.luno.mobile.BuildConfig
import com.luno.mobile.data.update.ApkInstallResult
import com.luno.mobile.data.update.ApkInstaller
import com.luno.mobile.data.update.GitHubRelease
import com.luno.mobile.data.update.GitHubReleaseService
import com.luno.mobile.data.update.ReleaseCheckResult
import com.luno.mobile.ui.theme.AccentGreen
import com.luno.mobile.ui.theme.PrimaryText
import com.luno.mobile.ui.theme.SecondaryText
import com.luno.mobile.ui.theme.SurfaceDark
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private sealed interface UpdateState {
    data object Checking : UpdateState
    data class Checked(val result: ReleaseCheckResult) : UpdateState
    data class Downloading(val percent: Int?) : UpdateState
    data class Ready(val release: GitHubRelease, val file: File, val message: String? = null) : UpdateState
}

@Composable
fun AppUpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val service = remember { GitHubReleaseService() }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Checking) }
    var operation by remember { mutableStateOf<Job?>(null) }

    fun check() {
        operation?.cancel()
        state = UpdateState.Checking
        operation = scope.launch {
            state = UpdateState.Checked(service.checkForUpdate(BuildConfig.VERSION_NAME))
        }
    }

    fun install(ready: UpdateState.Ready): Boolean {
        return when (val result = ApkInstaller.openInstaller(context, ready.file)) {
            ApkInstallResult.InstallerOpened -> {
                state = ready.copy(message = "Confirm Update in Android's installer. Your library and favorites stay on this phone.")
                false
            }
            ApkInstallResult.UnknownSourcesPermissionRequired -> {
                state = ready.copy(message = "Allow Luno to install apps, then return here. The APK is already downloaded.")
                true
            }
            is ApkInstallResult.Failure -> {
                state = ready.copy(message = result.message)
                false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        (state as? UpdateState.Ready)?.let(::install)
    }

    fun requestInstall(ready: UpdateState.Ready) {
        if (install(ready)) {
            permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            })
        }
    }

    fun download(release: GitHubRelease) {
        state = UpdateState.Downloading(null)
        operation = scope.launch {
            try {
                val file = ApkInstaller.download(context, release) { bytes, total ->
                    state = UpdateState.Downloading(if (total > 0L) ((bytes * 100L) / total).toInt().coerceIn(0, 100) else null)
                }
                val ready = UpdateState.Ready(release, file)
                state = ready
                requestInstall(ready)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = UpdateState.Checked(ReleaseCheckResult.Failure(e.message ?: "Could not download the update. Try again."))
            }
        }
    }

    LaunchedEffect(Unit) { check() }
    AlertDialog(
        onDismissRequest = { operation?.cancel(); onDismiss() },
        containerColor = SurfaceDark,
        titleContentColor = PrimaryText,
        textContentColor = SecondaryText,
        title = { Text("App updates") },
        text = {
            Column {
                Text("Installed: Luno ${BuildConfig.VERSION_NAME}", color = PrimaryText)
                Spacer(Modifier.height(16.dp))
                when (val current = state) {
                    UpdateState.Checking -> {
                        CircularProgressIndicator(color = AccentGreen)
                        Spacer(Modifier.height(12.dp))
                        Text("Checking the latest GitHub release…")
                    }
                    is UpdateState.Downloading -> {
                        Text("Downloading update${current.percent?.let { " · $it%" }.orEmpty()}")
                        Spacer(Modifier.height(12.dp))
                        if (current.percent == null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AccentGreen)
                        } else {
                            LinearProgressIndicator(progress = { current.percent / 100f }, modifier = Modifier.fillMaxWidth(), color = AccentGreen)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("The app and signing key are checked before installation.", style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateState.Ready -> {
                        Text("Luno ${current.release.version} is ready to install", color = PrimaryText)
                        Spacer(Modifier.height(12.dp))
                        Text(current.message ?: "The downloaded APK has been verified.")
                    }
                    is UpdateState.Checked -> when (val result = current.result) {
                        is ReleaseCheckResult.UpToDate -> {
                            Text("Latest release: ${result.latestVersion}")
                            Spacer(Modifier.height(12.dp))
                            Text("No newer update is available.")
                        }
                        is ReleaseCheckResult.Failure -> Text(result.message)
                        is ReleaseCheckResult.UpdateAvailable -> {
                            Text("Luno ${result.release.version} is available", color = PrimaryText)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                result.release.notes.ifBlank { "A new release is ready to download." },
                                modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                            )
                            if (result.release.apkUrl == null) {
                                Spacer(Modifier.height(12.dp))
                                Text("This release does not have an APK attached yet.")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (val current = state) {
                is UpdateState.Ready -> Button(onClick = { requestInstall(current) }) { Text("Install update") }
                is UpdateState.Checked -> when (val result = current.result) {
                    is ReleaseCheckResult.UpdateAvailable -> Button(
                        enabled = result.release.apkUrl != null,
                        onClick = { download(result.release) }
                    ) { Text("Download & install") }
                    else -> TextButton(onClick = ::check) { Text("Check again", color = AccentGreen) }
                }
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = { operation?.cancel(); onDismiss() }) {
                Text(if (state is UpdateState.Downloading || state is UpdateState.Checking) "Cancel" else "Close", color = SecondaryText)
            }
        }
    )
}
