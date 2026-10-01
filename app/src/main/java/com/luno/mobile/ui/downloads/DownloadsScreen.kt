package com.luno.mobile.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luno.mobile.LunoApp
import com.luno.mobile.data.artwork.ArtworkStorage
import com.luno.mobile.data.db.entity.DownloadJob
import com.luno.mobile.data.db.entity.DownloadState
import com.luno.mobile.ui.components.RecommendationArtworkImage
import com.luno.mobile.ui.theme.AccentGreen
import com.luno.mobile.ui.theme.Dimens
import com.luno.mobile.ui.theme.PrimaryText
import com.luno.mobile.ui.theme.SecondaryText
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as LunoApp
    val scope = rememberCoroutineScope()
    // Rendered from the app-warmed LibraryData flows — the queue and sync
    // list are present in the same frame as the transition.
    val libraryData = app.libraryData
    val downloads by libraryData.downloads.collectAsState()
    val tracks by libraryData.tracks.collectAsState()
    val playlistsWithUrls by libraryData.playlistsWithUrls.collectAsState()
    val libraryLoaded by libraryData.loaded.collectAsState()
    val downloadCounts = downloads.filter { it.state == DownloadState.COMPLETED }.groupingBy { job ->
        job.videoId.ifBlank { job.sourceUrl }
    }.eachCount()

    // All-or-nothing first render (same as the other tabs): normally the
    // library data is already warm from startup.
    if (!libraryLoaded) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = AccentGreen)
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.paddingLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.paddingMedium),
        contentPadding = PaddingValues(vertical = Dimens.paddingLarge)
    ) {
        item {
            Text(
                text = "Downloads",
                style = MaterialTheme.typography.headlineLarge,
                color = PrimaryText,
                modifier = Modifier.padding(bottom = Dimens.paddingMedium)
            )
        }

        // Playlist syncs
        if (playlistsWithUrls.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(Dimens.iconSize))
                    Spacer(modifier = Modifier.width(Dimens.paddingSmall))
                    Text(
                        text = "Playlists with URLs",
                        style = MaterialTheme.typography.titleMedium,
                        color = PrimaryText,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            items(
                playlistsWithUrls,
                key = { it.id },
                contentType = { "sync-playlist" }
            ) { playlist ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dimens.paddingSmall),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = PrimaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = playlist.playlistUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = SecondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(Dimens.paddingSmall))
                    Button(
                        onClick = {
                            scope.launch {
                                app.downloadRepository.syncPlaylist(
                                    playlistId = playlist.id,
                                    playlistName = playlist.name,
                                    playlistUrl = playlist.playlistUrl
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(Dimens.iconSizeSmall))
                        Spacer(modifier = Modifier.width(Dimens.paddingSmall))
                        Text("Sync")
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(Dimens.paddingMedium)) }
        }

        // Sync all button
        if (playlistsWithUrls.size >= 2) {
            item {
                Button(
                    onClick = {
                        scope.launch {
                            for (pl in playlistsWithUrls) {
                                app.downloadRepository.syncPlaylist(
                                    playlistId = pl.id,
                                    playlistName = pl.name,
                                    playlistUrl = pl.playlistUrl
                                )
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(modifier = Modifier.width(Dimens.paddingSmall))
                    Text("Sync All Playlists")
                }
            }
            item { Spacer(modifier = Modifier.height(Dimens.paddingMedium)) }
        }

        // Active downloads
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Download Queue (${downloads.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = PrimaryText,
                    modifier = Modifier.weight(1f)
                )
                if (downloads.any { it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING }) {
                    TextButton(onClick = {
                        scope.launch { app.downloadRepository.stopAllActive() }
                    }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = AccentGreen,
                            modifier = Modifier.size(Dimens.iconSizeSmall)
                        )
                        Spacer(modifier = Modifier.width(Dimens.paddingSmall))
                        Text("Stop All", color = AccentGreen)
                    }
                }
            }
        }

        if (downloads.isEmpty()) {
            item {
                Text(
                    text = "No downloads yet. Use Download to find music.",
                    color = SecondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = Dimens.paddingMedium)
                )
            }
        } else {
            items(
                downloads,
                key = { it.id },
                contentType = { "download-job" }
            ) { job ->
                DownloadJobRow(
                    job = job,
                    downloadCount = downloadCounts[job.videoId.ifBlank { job.sourceUrl }] ?: 1,
                    artworkUri = tracks.firstOrNull { it.uri == job.localUri }?.let { track ->
                        track.albumArtPath
                            ?.takeIf(ArtworkStorage::hasUsableArtwork)
                            ?.let { track.albumArtUri() }
                    } ?: job.thumbnailUrl.takeIf { it.isNotBlank() },
                    onCancel = { scope.launch { app.downloadRepository.cancelDownload(job.id) } },
                    onRetry = { scope.launch { app.downloadRepository.retryDownload(job.id) } },
                    onDelete = { scope.launch { app.downloadRepository.deleteDownload(job.id) } }
                )
            }
        }
    }
}

@Composable
private fun DownloadJobRow(
    job: DownloadJob,
    downloadCount: Int,
    artworkUri: String?,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val stateColor = when (job.state) {
        DownloadState.QUEUED -> SecondaryText
        DownloadState.DOWNLOADING -> AccentGreen
        DownloadState.COMPLETED -> AccentGreen
        DownloadState.FAILED -> MaterialTheme.colorScheme.error
        DownloadState.CANCELLED -> SecondaryText
    }

    val stateLabel = when (job.state) {
        DownloadState.QUEUED -> "Queued"
        DownloadState.DOWNLOADING -> "Downloading ${job.progress}%"
        DownloadState.COMPLETED -> "Completed"
        DownloadState.FAILED -> job.errorMessage.ifBlank { "Failed" }
        DownloadState.CANCELLED -> "Cancelled"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.paddingSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RecommendationArtworkImage(
            artist = job.artist,
            title = job.title,
            artworkUri = artworkUri,
            modifier = Modifier
                .size(Dimens.albumArtSmall)
                .clip(RoundedCornerShape(Dimens.cornerSmall)),
            placeholderIconSize = 20.dp,
            decodeSizePx = 192
        )
        Spacer(modifier = Modifier.width(Dimens.paddingSmall))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = job.title,
                style = MaterialTheme.typography.titleSmall,
                color = PrimaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (job.artist.isNotBlank()) {
                Text(
                    text = job.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = stateLabel,
                style = MaterialTheme.typography.labelSmall,
                color = stateColor
            )
            val timestamp = job.completedAt.takeIf { it > 0L } ?: job.addedAt
            Text(
                text = buildString {
                    if (job.state == DownloadState.COMPLETED) {
                        append("Downloaded $downloadCount time")
                        if (downloadCount != 1) append('s')
                        append(" · ")
                    } else {
                        append("Added · ")
                    }
                    append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp)))
                },
                style = MaterialTheme.typography.labelSmall,
                color = SecondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (job.state == DownloadState.COMPLETED) {
                val destinationStatus = job.errorMessage.ifBlank {
                    if (job.localUri.startsWith("content://")) {
                        "Saved to the selected Music folder"
                    } else {
                        "Saved in Luno app storage"
                    }
                }
                Text(
                    text = destinationStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = SecondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        when (job.state) {
            DownloadState.QUEUED, DownloadState.DOWNLOADING -> {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = SecondaryText)
                }
            }
            DownloadState.FAILED -> {
                OutlinedButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
                ) { Text("Retry") }
                Spacer(modifier = Modifier.width(Dimens.paddingSmall))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Close, contentDescription = "Delete", tint = SecondaryText)
                }
            }
            DownloadState.COMPLETED, DownloadState.CANCELLED -> {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = SecondaryText)
                }
            }
        }
    }
}
