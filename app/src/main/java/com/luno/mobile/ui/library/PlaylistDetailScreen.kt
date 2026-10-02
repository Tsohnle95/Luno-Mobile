package com.luno.mobile.ui.library

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luno.mobile.LunoApp
import com.luno.mobile.data.db.dao.PlaylistWithTracks
import com.luno.mobile.data.db.entity.SystemPlaylists
import com.luno.mobile.data.db.entity.Track
import com.luno.mobile.playback.MediaTrack
import com.luno.mobile.playback.MusicController
import com.luno.mobile.playback.PlaybackSource
import com.luno.mobile.ui.components.BulkSelectionToolbar
import com.luno.mobile.ui.components.RecommendationArtworkImage
import com.luno.mobile.ui.components.SortChip
import com.luno.mobile.ui.components.TrackActionsSheet
import com.luno.mobile.ui.components.TrackSortMode
import com.luno.mobile.ui.components.sortedByMode
import com.luno.mobile.ui.components.sortedByPlaylistMembership
import com.luno.mobile.ui.theme.AccentGreen
import com.luno.mobile.ui.theme.Dimens
import com.luno.mobile.ui.theme.PrimaryText
import com.luno.mobile.ui.theme.SecondaryText
import com.luno.mobile.ui.theme.SurfaceDark
import com.luno.mobile.ui.theme.SurfaceElevated
import kotlinx.coroutines.launch

/**
 * Shared playlist detail screen with cover art, Luno attribution, duration,
 * playlist controls and ordered tracks. Tapping a track starts the playlist
 * at that position; long-press opens track actions.
 */
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    musicController: MusicController,
    onBack: () -> Unit,
    onExportPlaylist: (Long) -> Unit = {},
    onExportTracks: (List<String>) -> Unit = {},
    virtualName: String? = null,
    virtualDescription: String = "",
    virtualTracks: List<Track>? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as LunoApp
    val scope = rememberCoroutineScope()

    // Keep playlist membership order separate from the Recently added sort.
    // The saved order remains the default; the explicit Recently added option
    // sorts by each track's library-added timestamp.
    var sortMode by rememberSaveable { mutableStateOf(TrackSortMode.PLAYLIST_ORDER) }

    // In-playlist search filters both the track list and its play context.
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var favoritesFirst by rememberSaveable { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf(setOf<String>()) }
    var actionsTrack by remember { mutableStateOf<Track?>(null) }

    // Reads from the app-warmed LibraryData playlists flow: the detail
    // screen is only reachable via a playlist card, so the list is already
    // loaded and the header + tracks render in the same frame as the
    // transition (membership changes flow in live too).
    val playlists by app.libraryData.playlists.collectAsState()
    val playlistWithTracks = playlists.firstOrNull { it.playlist.id == playlistId }
    val membershipOrder by remember(playlistId, virtualTracks) {
        if (virtualTracks == null && playlistId > 0L) {
            app.database.playlistDao().observePlaylistTracks(playlistId)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }.collectAsState(initial = emptyList())
    val shuffleEnabled by musicController.shuffleEnabled.collectAsState()

    // All-or-nothing first render: hold the screen behind one static
    // placeholder instead of showing the top bar and then the header and
    // track list popping in afterwards.
    if (virtualTracks == null && playlistWithTracks == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = AccentGreen)
        }
        return
    }
    val playlist = playlistWithTracks?.playlist
    val tracks = virtualTracks ?: playlistWithTracks?.tracks.orEmpty()
    // Virtual playlists (for example Made for you) do not own persisted
    // playlist-view counts. Only a real playlist detail view supplies a
    // playlist playback context.
    val playbackPlaylistId = playlist?.id?.takeIf { virtualTracks == null }
    val playlistName = when {
        virtualName != null -> virtualName
        playlist?.name?.equals("Unsorted", ignoreCase = true) == true -> {
            "Unsorted - songs yet to find a home"
        }
        else -> playlist?.name.orEmpty()
    }
    val playlistDescription = virtualDescription.ifBlank { playlist?.description.orEmpty() }
    val playbackSource = if (virtualTracks != null) {
        PlaybackSource(
            category = if (playlistId == SystemPlaylists.FAVORITES_ID) "Library" else "Home",
            name = playlistName
        )
    } else {
        PlaybackSource(
            category = "Playlist",
            name = playlistName,
            playlistId = playbackPlaylistId
        )
    }

    // In-playlist search: filter the membership by title/artist/album
    // (case-insensitive), then sort the filtered set — the list rows AND
    // the play context walk the same filtered order.
    val filteredTracks = remember(tracks, query) {
        if (query.isBlank()) tracks
        else tracks.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true) ||
                it.album.contains(query, ignoreCase = true)
        }
    }
    val sortedTracks = remember(filteredTracks, sortMode, membershipOrder) {
        if (sortMode == TrackSortMode.PLAYLIST_ORDER) {
            filteredTracks.sortedByPlaylistMembership(membershipOrder.map { it.trackUri })
        } else {
            filteredTracks.sortedByMode(sortMode)
        }
    }
    val displayedTracks = remember(sortedTracks, favoritesFirst) {
        if (favoritesFirst) sortedTracks.sortedByDescending { it.isFavorite } else sortedTracks
    }
    val selectedTracks = tracks.filter { it.uri in selectedUris }

    fun toggleSelection(uri: String) {
        val next = if (uri in selectedUris) selectedUris - uri else selectedUris + uri
        selectedUris = next
        selectionMode = next.isNotEmpty()
    }

    fun beginSelection(uri: String) {
        selectionMode = true
        selectedUris = selectedUris + uri
    }

    fun exitSelection() {
        selectionMode = false
        selectedUris = emptySet()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Dimens.paddingXLarge)
    ) {
        if (selectionMode) {
            item {
                BulkSelectionToolbar(
                    selectedTracks = selectedTracks,
                    selectedPlaylists = emptyList(),
                    allSelected = tracks.isNotEmpty() && selectedUris.size == tracks.size,
                    onSelectAll = { selectAll ->
                        if (selectAll) {
                            selectionMode = true
                            selectedUris = tracks.map { it.uri }.toSet()
                        } else {
                            exitSelection()
                        }
                    },
                    onDismiss = ::exitSelection,
                    onAddToPlaylist = { target, trackUris ->
                        scope.launch {
                            app.playlistRepository.addTracksToPlaylist(target.id, trackUris)
                            Toast.makeText(context, "Added to ${target.name}", Toast.LENGTH_SHORT).show()
                        }
                        exitSelection()
                    },
                    onCreatePlaylist = { name, description, trackUris ->
                        scope.launch {
                            app.playlistRepository.createPlaylist(name, description).onSuccess { playlist ->
                                app.playlistRepository.addTracksToPlaylist(playlist.id, trackUris)
                                Toast.makeText(context, "Created ${playlist.name}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        exitSelection()
                    },
                    onExportTracks = { uris ->
                        onExportTracks(uris)
                        exitSelection()
                    },
                    onRemoveTracks = { uris ->
                        scope.launch {
                            uris.forEach { app.playlistRepository.removeTrackFromPlaylist(playlistId, it) }
                        }
                        exitSelection()
                    },
                    modifier = Modifier.padding(horizontal = Dimens.paddingLarge)
                )
            }
        }

        item(key = "playlist-cover", contentType = "playlist-cover") {
            PlaylistArtworkHero(
                name = playlistName,
                description = playlistDescription,
                tracks = tracks,
                onBack = onBack,
                onExport = if (virtualTracks == null && playlistId > 0L) {
                    { onExportPlaylist(playlistId) }
                } else {
                    null
                }
            )
        }

        if (tracks.isEmpty()) {
            item {
                PlaylistHeader(
                    trackCount = 0,
                    totalDurationMs = 0L,
                    onPlayAll = null
                )
            }
            item {
                Text(
                    text = "No tracks in this playlist yet. Long-press a song and choose \"Add to playlist\".",
                    color = SecondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(Dimens.paddingLarge)
                )
            }
        } else {
            item {
                PlaylistHeader(
                    trackCount = tracks.size,
                    totalDurationMs = tracks.sumOf { it.durationMs },
                    onPlayAll = {
                        musicController.play(
                            displayedTracks.map { it.toMediaTrack(playbackSource) },
                            0,
                            playbackPlaylistId
                        )
                    },
                    onShuffleAll = {
                        musicController.playShuffled(
                            displayedTracks.map { it.toMediaTrack(playbackSource) },
                            playbackPlaylistId
                        )
                    },
                    onSearch = { showSearch = !showSearch },
                    searchVisible = showSearch,
                    sortMode = sortMode,
                    onSortModeChange = { sortMode = it },
                    shuffleActive = shuffleEnabled,
                    favoritesFirst = favoritesFirst,
                    onFavoritesFirstChange = { favoritesFirst = it }
                )
            }

            if (showSearch) {
                item {
                    Column {
                        Spacer(modifier = Modifier.height(Dimens.paddingMedium))
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimens.paddingLarge),
                            placeholder = {
                                Text(text = "Search in this playlist", color = SecondaryText)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = "Search",
                                    tint = SecondaryText
                                )
                            },
                            trailingIcon = {
                                if (query.isNotBlank()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(
                                            imageVector = Icons.Filled.Clear,
                                            contentDescription = "Clear search",
                                            tint = SecondaryText
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = PrimaryText,
                                unfocusedTextColor = PrimaryText,
                                cursorColor = AccentGreen,
                                focusedBorderColor = AccentGreen,
                                unfocusedBorderColor = SurfaceElevated,
                                focusedContainerColor = SurfaceDark,
                                unfocusedContainerColor = SurfaceDark
                            )
                        )
                        Spacer(modifier = Modifier.height(Dimens.paddingMedium))
                    }
                }
            }

            if (filteredTracks.isEmpty()) {
                item {
                    Text(
                        text = "No songs match \"$query\" in this playlist.",
                        color = SecondaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(Dimens.paddingLarge)
                    )
                }
            } else {
                items(
                    displayedTracks,
                    key = { it.uri },
                    contentType = { "playlist-track" }
                ) { track ->
                    PlaylistTrackRow(
                        track = track,
                        selected = if (selectionMode) track.uri in selectedUris else null,
                        onClick = {
                            if (selectionMode) {
                                toggleSelection(track.uri)
                            } else {
                                val index = displayedTracks.indexOfFirst { it.uri == track.uri }
                                musicController.play(
                                    displayedTracks.map { it.toMediaTrack(playbackSource) },
                                    index.coerceAtLeast(0),
                                    playbackPlaylistId
                                )
                            }
                        },
                        onLongPress = { beginSelection(track.uri) },
                        onMenuClick = { actionsTrack = track },
                        showFavoriteIcon = favoritesFirst || playlistId == SystemPlaylists.FAVORITES_ID
                    )
                }
            }
        }
    }

    actionsTrack?.let { track ->
        TrackActionsSheet(
            track = track,
            onDismiss = { actionsTrack = null },
            onDeleted = { actionsTrack = null },
            onRemoveFromPlaylist = if (virtualTracks == null) {
                {
                    scope.launch {
                        app.playlistRepository.removeTrackFromPlaylist(playlistId, track.uri)
                        Toast.makeText(context, "Removed from playlist", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                null
            }
        )
    }
}

@Composable
private fun PlaylistArtworkHero(
    name: String,
    description: String,
    tracks: List<Track>,
    onBack: () -> Unit,
    onExport: (() -> Unit)?
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.12f)
    ) {
        val cover = tracks.firstOrNull()
        RecommendationArtworkImage(
            artist = cover?.artist ?: name,
            title = cover?.title ?: name,
            artworkUri = cover?.albumArtUri(),
            modifier = Modifier.fillMaxSize(),
            shape = RectangleShape,
            placeholderIconSize = 56.dp,
            decodeSizePx = 1024
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.24f),
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.88f)
                    )
                )
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = Dimens.paddingSmall, vertical = Dimens.paddingSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.38f))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(Dimens.iconSize)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (onExport != null) {
                IconButton(
                    onClick = onExport,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.38f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.UploadFile,
                        contentDescription = "Export playlist",
                        tint = Color.White,
                        modifier = Modifier.size(Dimens.iconSize)
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = Dimens.paddingLarge, vertical = Dimens.paddingMedium)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LunoAttribution(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 5.dp, height = 17.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AccentGreen)
        )
        Spacer(modifier = Modifier.width(Dimens.paddingSmall))
        Text(
            text = "Luno",
            style = MaterialTheme.typography.titleSmall,
            color = PrimaryText,
            fontWeight = FontWeight.Bold
        )
    }
}

internal fun formatPlaylistDuration(durationMs: Long): String {
    val totalMinutes = durationMs.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "$hours hr $minutes min" else "$minutes min"
}

@Composable
private fun PlaylistHeader(
    trackCount: Int,
    totalDurationMs: Long,
    onPlayAll: (() -> Unit)?,
    onShuffleAll: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    searchVisible: Boolean = false,
    sortMode: TrackSortMode = TrackSortMode.PLAYLIST_ORDER,
    onSortModeChange: ((TrackSortMode) -> Unit)? = null,
    shuffleActive: Boolean = false,
    favoritesFirst: Boolean = false,
    onFavoritesFirstChange: ((Boolean) -> Unit)? = null
) {
    Column(modifier = Modifier.padding(horizontal = Dimens.paddingLarge)) {
        Spacer(modifier = Modifier.height(Dimens.paddingMedium))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LunoAttribution(modifier = Modifier.weight(1f))
            Text(
                text = "$trackCount songs · ${formatPlaylistDuration(totalDurationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = SecondaryText
            )
        }
        Spacer(modifier = Modifier.height(Dimens.paddingSmall))

        if (onPlayAll != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onSearch != null) {
                    IconButton(onClick = onSearch) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = if (searchVisible) {
                                "Hide playlist search"
                            } else {
                                "Search playlist"
                            },
                            tint = PrimaryText,
                            modifier = Modifier.size(Dimens.iconSize)
                        )
                    }
                }
                if (onShuffleAll != null) {
                    IconButton(onClick = onShuffleAll) {
                        Icon(
                            imageVector = Icons.Filled.Shuffle,
                            contentDescription = "Shuffle playlist",
                            tint = if (shuffleActive) AccentGreen else SecondaryText,
                            modifier = Modifier.size(Dimens.iconSize)
                        )
                    }
                }

                if (onFavoritesFirstChange != null) {
                    IconButton(onClick = { onFavoritesFirstChange(!favoritesFirst) }) {
                        Icon(
                            imageVector = if (favoritesFirst) {
                                Icons.Filled.Favorite
                            } else {
                                Icons.Filled.FavoriteBorder
                            },
                            contentDescription = if (favoritesFirst) {
                                "Show favorites first"
                            } else {
                                "Sort favorites first"
                            },
                            tint = if (favoritesFirst) AccentGreen else SecondaryText,
                            modifier = Modifier.size(Dimens.iconSize)
                        )
                    }
                }

                if (onSortModeChange != null) {
                    SortChip(
                        mode = sortMode,
                        onModeChange = onSortModeChange,
                        compact = true,
                        availableModes = TrackSortMode.PLAYLIST_MODES
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onPlayAll,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(AccentGreen)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play playlist",
                        tint = Color.Black,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistTrackRow(
    track: Track,
    selected: Boolean?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onMenuClick: () -> Unit,
    showFavoriteIcon: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress
            )
            .padding(horizontal = Dimens.paddingLarge, vertical = Dimens.paddingSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected != null) {
            Icon(
                imageVector = if (selected) {
                    Icons.Filled.CheckCircle
                } else {
                    Icons.Filled.RadioButtonUnchecked
                },
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) AccentGreen else SecondaryText,
                modifier = Modifier.size(Dimens.iconSize)
            )
            Spacer(modifier = Modifier.width(Dimens.paddingSmall))
        }
        RecommendationArtworkImage(
            artist = track.artist,
            title = track.title,
            artworkUri = track.albumArtUri(),
            modifier = Modifier
                .size(Dimens.albumArtSmall)
                .clip(RoundedCornerShape(Dimens.cornerSmall)),
            placeholderIconSize = 20.dp,
            decodeSizePx = 192
        )
        Spacer(modifier = Modifier.width(Dimens.paddingMedium))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                color = PrimaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = SecondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showFavoriteIcon && track.isFavorite) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "Favorite",
                tint = AccentGreen,
                modifier = Modifier
                    .size(Dimens.iconSize)
                    .offset(x = 8.dp)
            )
        }
        IconButton(onClick = onMenuClick) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Track options",
                tint = AccentGreen,
                modifier = Modifier.size(Dimens.iconSize)
            )
        }
    }
}

private fun Track.toMediaTrack(source: PlaybackSource? = null): MediaTrack = MediaTrack(
    uri = uri,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    artworkUri = albumArtUri(),
    playbackSource = source
)
