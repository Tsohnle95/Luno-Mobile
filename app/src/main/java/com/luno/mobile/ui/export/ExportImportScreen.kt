package com.luno.mobile.ui.export

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.luno.mobile.LunoApp
import com.luno.mobile.data.export.FavoritesMetadataBackup
import com.luno.mobile.data.export.FavoritesMetadataBackupCodec
import com.luno.mobile.data.export.ImportPreview
import com.luno.mobile.data.export.LibraryManifest
import com.luno.mobile.ui.theme.AccentGreen
import com.luno.mobile.ui.theme.Dimens
import com.luno.mobile.ui.theme.PrimaryText
import com.luno.mobile.ui.theme.SecondaryText
import com.luno.mobile.ui.theme.SurfaceDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExportImportScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as LunoApp
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<LibraryManifest?>(null) }
    var pendingFavoritesExport by remember { mutableStateOf<FavoritesMetadataBackup?>(null) }
    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    var pendingFavoritesImportJson by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var favoritesPreview by remember { mutableStateOf<FavoritesMetadataBackup?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val manifest = pendingExport
        if (uri == null || manifest == null) {
            pendingExport = null
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true
            try {
                val json = app.libraryTransferRepository.encode(manifest)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Could not open the selected file")
                }
                status = "Exported ${manifest.tracks.size} track reference(s)."
            } catch (error: Exception) {
                status = "Export failed: ${error.message ?: "Could not write the file"}"
            } finally {
                busy = false
                pendingExport = null
            }
        }
    }

    val favoritesExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val backup = pendingFavoritesExport
        if (uri == null || backup == null) {
            pendingFavoritesExport = null
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true
            try {
                val json = app.libraryTransferRepository.encodeFavoritesMetadataBackup(backup)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Could not open the selected file")
                }
                status = "Saved a favorites backup with ${backup.favorites.size} song(s). Keep it outside Luno, such as in Downloads and your computer backup."
            } catch (error: Exception) {
                status = "Favorites backup failed: ${error.message ?: "Could not write the file"}"
            } finally {
                busy = false
                pendingFavoritesExport = null
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val json = withContext(Dispatchers.IO) { readBounded(context, uri) }
                pendingImportJson = json
                preview = app.libraryTransferRepository.previewImport(json)
                status = null
            } catch (error: Exception) {
                status = "Import preview failed: ${error.message ?: "Invalid manifest"}"
                pendingImportJson = null
                preview = null
            } finally {
                busy = false
            }
        }
    }

    val favoritesImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val json = withContext(Dispatchers.IO) { readBounded(context, uri) }
                val backup = FavoritesMetadataBackupCodec.decodeAndValidate(json)
                pendingFavoritesImportJson = json
                favoritesPreview = backup
                status = null
            } catch (error: Exception) {
                status = "Favorites backup could not be opened: ${error.message ?: "Invalid backup"}"
                pendingFavoritesImportJson = null
                favoritesPreview = null
            } finally {
                busy = false
            }
        }
    }

    fun requestFullExport() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                pendingExport = app.libraryTransferRepository.buildFullLibraryManifest()
                exportLauncher.launch("luno-library.json")
            } catch (error: Exception) {
                status = "Export failed: ${error.message ?: "Could not build manifest"}"
                busy = false
            }
        }
    }

    fun requestFavoritesExport() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                pendingFavoritesExport = app.libraryTransferRepository.buildFavoritesMetadataBackup()
                favoritesExportLauncher.launch("luno-favorites-backup.json")
            } catch (error: Exception) {
                status = "Favorites backup failed: ${error.message ?: "Could not prepare backup"}"
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.paddingLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.paddingMedium)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = PrimaryText
                )
            }
            Text(
                text = "Export / Import",
                style = MaterialTheme.typography.headlineMedium,
                color = PrimaryText,
                modifier = Modifier.padding(start = Dimens.paddingSmall)
            )
        }
        Text(
            text = "Move playlists and song references between Luno installs. " +
                "The full library transfer never contains audio, local paths, playback history, or secrets.",
            color = SecondaryText,
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            onClick = ::requestFavoritesExport,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Download, contentDescription = null)
            Text("Back up favorite songs", modifier = Modifier.padding(start = Dimens.paddingSmall))
        }
        OutlinedButton(
            onClick = { if (!busy) favoritesImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.UploadFile, contentDescription = null, tint = AccentGreen)
            Text("Restore favorite songs", modifier = Modifier.padding(start = Dimens.paddingSmall), color = PrimaryText)
        }
        Text(
            text = "Favorites backups contain song metadata and private match hashes only. They contain no audio or local file paths. Save the file outside the app so it remains available after uninstalling Luno.",
            color = SecondaryText,
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = ::requestFullExport,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Download, contentDescription = null)
            Text("Export full library", modifier = Modifier.padding(start = Dimens.paddingSmall))
        }
        OutlinedButton(
            onClick = { if (!busy) importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.UploadFile, contentDescription = null, tint = AccentGreen)
            Text("Import JSON", modifier = Modifier.padding(start = Dimens.paddingSmall), color = PrimaryText)
        }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = AccentGreen,
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(end = Dimens.paddingSmall)
                )
                Text("Working…", color = SecondaryText)
            }
        }
        status?.let {
            Text(text = it, color = AccentGreen, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(Dimens.paddingLarge))
        Text(
            text = "Imports are additive only. Existing playlists are merged by name and existing audio is never deleted. Missing YouTube references are queued through Downloads; metadata-only matches require your confirmation.",
            color = SecondaryText,
            style = MaterialTheme.typography.bodySmall
        )
    }

    val currentPreview = preview
    if (currentPreview != null) {
        val ambiguous = currentPreview.ambiguousTracks.size
        AlertDialog(
            onDismissRequest = {
                preview = null
                pendingImportJson = null
            },
            containerColor = SurfaceDark,
            titleContentColor = PrimaryText,
            textContentColor = SecondaryText,
            title = { Text("Review import") },
            text = {
                Column {
                    Text("${currentPreview.manifest.tracks.size} track reference(s)")
                    Text("${currentPreview.matchedCount} match existing audio")
                    Text("${currentPreview.missingTracks.size} missing and may be queued")
                    Text("${currentPreview.newPlaylistNames.size} new playlist(s), ${currentPreview.existingPlaylistNames.size} merged")
                    if (ambiguous > 0) {
                        Text(
                            "$ambiguous metadata-only match(es) may be a different recording. Confirm to continue.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = Dimens.paddingSmall)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val json = pendingImportJson ?: return@TextButton
                        scope.launch {
                            busy = true
                            try {
                                val result = app.libraryTransferRepository.importManifest(
                                    json,
                                    confirmAmbiguous = true
                                )
                                status = "Import complete: ${result.matchedCount} matched, " +
                                    "${result.queuedDownloads} download(s) queued, " +
                                    "${result.unresolvedTracks.size} unresolved."
                                Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                                preview = null
                                pendingImportJson = null
                            } catch (error: Exception) {
                                status = "Import failed: ${error.message ?: "Could not import"}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("Import", color = AccentGreen) }
            },
            dismissButton = {
                TextButton(onClick = {
                    preview = null
                    pendingImportJson = null
                }) { Text("Cancel", color = SecondaryText) }
            }
        )
    }

    val currentFavoritesPreview = favoritesPreview
    if (currentFavoritesPreview != null) {
        AlertDialog(
            onDismissRequest = {
                favoritesPreview = null
                pendingFavoritesImportJson = null
            },
            containerColor = SurfaceDark,
            titleContentColor = PrimaryText,
            textContentColor = SecondaryText,
            title = { Text("Restore favorites") },
            text = {
                Text(
                    "This backup has ${currentFavoritesPreview.favorites.size} favorite song(s). " +
                        "Luno will mark unique matches in your current library as favorites. " +
                        "Unmatched or ambiguous songs will be left unchanged."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val json = pendingFavoritesImportJson ?: return@TextButton
                        scope.launch {
                            busy = true
                            try {
                                val result = app.libraryTransferRepository
                                    .restoreFavoritesMetadataBackup(json)
                                status = "Favorites restored: ${result.restoredCount} matched, " +
                                    "${result.ambiguousCount} ambiguous, ${result.missingCount} not found."
                                Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                                favoritesPreview = null
                                pendingFavoritesImportJson = null
                            } catch (error: Exception) {
                                status = "Favorites restore failed: ${error.message ?: "Could not restore backup"}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("Restore", color = AccentGreen) }
            },
            dismissButton = {
                TextButton(onClick = {
                    favoritesPreview = null
                    pendingFavoritesImportJson = null
                }) { Text("Cancel", color = SecondaryText) }
            }
        )
    }
}

private suspend fun readBounded(context: android.content.Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        val maxBytes = 10 * 1024 * 1024
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) error("Manifest is too large")
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        } ?: error("Could not open the selected file")
        bytes.toString(Charsets.UTF_8)
    }
