package com.luno.mobile.ui.home

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luno.mobile.LunoApp
import com.luno.mobile.data.db.dao.PlaylistWithTracks
import com.luno.mobile.data.db.entity.DownloadJob
import com.luno.mobile.data.db.entity.DownloadState
import com.luno.mobile.data.db.entity.Playlist
import com.luno.mobile.data.db.entity.SystemPlaylists
import com.luno.mobile.data.db.entity.Track
import com.luno.mobile.playback.MediaTrack
import com.luno.mobile.playback.MusicController
import com.luno.mobile.playback.PlaybackSource
import com.luno.mobile.ui.components.BulkSelectionToolbar
import com.luno.mobile.ui.components.ArtworkCollage
import com.luno.mobile.ui.components.MiniPlayerOverlayHeight
import com.luno.mobile.ui.components.RecommendationArtworkImage
import com.luno.mobile.ui.components.TrackActionsSheet
import com.luno.mobile.ui.theme.AccentGreen
import com.luno.mobile.ui.theme.Dimens
import com.luno.mobile.ui.theme.PrimaryBackground
import com.luno.mobile.ui.theme.PrimaryText
import com.luno.mobile.ui.theme.SecondaryText
import com.luno.mobile.ui.theme.SurfaceDark
import com.luno.mobile.ui.theme.SurfaceElevated
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private data class HomeContentSnapshot(
    val tracks: List<Track>,
    val playlists: List<PlaylistWithTracks>,
    val downloads: List<DownloadJob>,
    val recentlyPlayed: List<MediaTrack>,
    val hasCurrentTrack: Boolean
)

private data class HomeScrollAnchor(
    val key: Any,
    val offset: Int
)

/** Catalogue identity must not change when mutable playback metadata changes. */
internal fun homeCatalogueKey(tracks: List<Track>): List<String> =
    tracks.asSequence().map { it.uri }.sorted().toList()

/** Returns the newest completed downloads that still exist in the library. */
internal fun recentlyDownloadedTracks(
    downloads: List<DownloadJob>,
    tracksByUri: Map<String, Track>,
    limit: Int = 50
): List<Track> = downloads
    .asSequence()
    .filter { it.state == DownloadState.COMPLETED && it.localUri.isNotBlank() }
    .sortedWith(
        compareByDescending<DownloadJob> {
            it.completedAt.takeIf { time -> time > 0L } ?: it.addedAt
        }.thenByDescending { it.id }
    )
    .mapNotNull { tracksByUri[it.localUri] }
    .distinctBy { it.uri }
    .take(limit.coerceAtLeast(0))
    .toList()

/** Returns the six most recently played real playlists, newest first. */
internal fun recentlyPlayedPlaylists(
    playlists: List<PlaylistWithTracks>,
    playbackHistory: List<MediaTrack> = emptyList(),
    limit: Int = 6
): List<PlaylistWithTracks> {
    val playlistsById = playlists.associateBy { it.playlist.id }
    val persistedRecency = playlists
        .asSequence()
        .filter { it.playlist.lastPlayedAt > 0L }
        .sortedWith(
            compareByDescending<PlaylistWithTracks> { it.playlist.lastPlayedAt }
                .thenByDescending { it.playlist.id }
        )
    val restoredHistory = playbackHistory
        .asSequence()
        .mapNotNull { it.playbackSource?.playlistId }
        .filter { it > 0L }
        .distinct()
        .mapNotNull(playlistsById::get)
        .filter { it.playlist.lastPlayedAt == 0L }

    return (persistedRecency + restoredHistory)
        .distinctBy { it.playlist.id }
        .take(limit.coerceAtLeast(0))
        .toList()
}

/**
 * Home greeting, a persisted recent-playlist grid, and the existing local
 * library carousels. Settings is opened from the profile icon beside the greeting.
 */
@Composable
fun HomeScreen(
    musicController: MusicController,
    onPlay: (List<MediaTrack>, Int, Boolean) -> Unit = { _, _, _ -> },
    onOpenPlaylist: (Long) -> Unit = {},
    onOpenMadeForYou: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenRecents: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as LunoApp
    val currentTrack by musicController.currentTrack.collectAsState()
    val recentlyPlayed by musicController.recentlyPlayed.collectAsState()
    // Rendered from the app-warmed LibraryData flows — switching back to
    // Home shows the full carousels in the same frame as the transition.
    val libraryData = app.libraryData
    val allTracks by libraryData.tracks.collectAsState()
    val playlistsWithTracks by libraryData.playlists.collectAsState()
    val downloads by libraryData.downloads.collectAsState()
    val libraryLoaded by libraryData.loaded.collectAsState()

    // All-or-nothing first render (same as the other tabs): normally the
    // library data is already warm from startup, so this only shows on the
    // very first app frames.
    if (!libraryLoaded) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = AccentGreen)
        }
        return
    }

    val greeting = getGreeting()

    // Hoisted scroll states: keeping the parent state at screen level avoids
    // losing the vertical anchor when Home recomposes after a Room emission.
    val homeListState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }

    // Hoisted carousel scroll states: remembered at screen level so each
    // carousel keeps its exact scroll position even when its section
    // swaps content (e.g. EmptyStateCard <-> carousel while the library
    // changes) — with per-item remembers the state could reset or jump.
    val recentlyPlayedListState = rememberLazyListState()
    val recentlyDownloadedListState = rememberLazyListState()
    val madeForYouListState = rememberLazyListState()
    val favoritesListState = rememberLazyListState()
    val popularTracksListState = rememberLazyListState()
    val popularPlaylistsListState = rememberLazyListState()

    // Room emits a new list when a play count changes. Applying that update
    // during a fling can change section membership and move the content under
    // the user's finger. Keep one render snapshot until every Home list is
    // idle, then apply the newest library/history data in one update.
    val latestContent = rememberUpdatedState(
        HomeContentSnapshot(
            tracks = allTracks,
            playlists = playlistsWithTracks,
            downloads = downloads,
            recentlyPlayed = recentlyPlayed,
            hasCurrentTrack = currentTrack != null
        )
    )
    var renderedContent by remember { mutableStateOf(latestContent.value) }
    var anchorToRestore by remember { mutableStateOf<HomeScrollAnchor?>(null) }
    fun isHomeScrolling(): Boolean =
        homeListState.isScrollInProgress ||
            recentlyPlayedListState.isScrollInProgress ||
            recentlyDownloadedListState.isScrollInProgress ||
            madeForYouListState.isScrollInProgress ||
            favoritesListState.isScrollInProgress ||
            popularTracksListState.isScrollInProgress ||
            popularPlaylistsListState.isScrollInProgress

    // Room can emit while a fling is settling. Apply the newest snapshot only
    // while idle, and preserve the first visible keyed item if a section's
    // membership or height changed. This prevents a data refresh from moving
    // the content under the user's finger or appearing to reverse direction.
    fun applyLatestContent() {
        if (isHomeScrolling()) return
        val nextContent = latestContent.value
        if (renderedContent == nextContent) return
        anchorToRestore = homeListState.layoutInfo.visibleItemsInfo
            .firstOrNull()
            ?.let { HomeScrollAnchor(key = it.key, offset = it.offset) }
        renderedContent = nextContent
    }

    LaunchedEffect(allTracks, playlistsWithTracks, downloads, recentlyPlayed, currentTrack != null) {
        applyLatestContent()
    }
    LaunchedEffect(
        homeListState,
        recentlyPlayedListState,
        recentlyDownloadedListState,
        madeForYouListState,
        favoritesListState,
        popularTracksListState,
        popularPlaylistsListState
    ) {
        snapshotFlow { isHomeScrolling() }
            .distinctUntilChanged()
            .collect { isScrolling ->
                if (!isScrolling) {
                    // Wait for the idle layout to be committed before taking
                    // an anchor. A fling can report idle one frame before its
                    // final layout is visible to the parent composition.
                    withFrameNanos { }
                    applyLatestContent()
                }
            }
    }

    LaunchedEffect(renderedContent) {
        val anchor = anchorToRestore ?: return@LaunchedEffect
        withFrameNanos { }
        val updatedItem = homeListState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == anchor.key }
        anchorToRestore = null
        // A new gesture may begin during the frame wait. Never issue a
        // corrective scroll once the user has taken control again.
        if (isHomeScrolling()) return@LaunchedEffect
        if (updatedItem != null) {
            val correction = updatedItem.offset - anchor.offset
            if (correction != 0) {
                homeListState.scrollBy(correction.toFloat())
            }
        }
    }

    val homeTracks = renderedContent.tracks
    val homePlaylists = renderedContent.playlists
    val homeDownloads = renderedContent.downloads
    val homeRecentlyPlayed = renderedContent.recentlyPlayed
    val homeHasCurrentTrack = renderedContent.hasCurrentTrack

    // Key the random sample only to catalogue membership. A play-count or
    // artwork update refreshes the card data without changing its order.
    val catalogueUris = remember(homeTracks) { homeCatalogueKey(homeTracks) }
    val madeForYouUris = remember(catalogueUris) {
        catalogueUris.shuffled().take(50)
    }
    val tracksByUri = remember(homeTracks) { homeTracks.associateBy { it.uri } }
    val recentlyDownloaded = remember(homeDownloads, tracksByUri) {
        recentlyDownloadedTracks(homeDownloads, tracksByUri)
    }
    val recentlyDownloadedMedia = remember(recentlyDownloaded) {
        recentlyDownloaded.map {
            MediaTrack(
                uri = it.uri,
                title = it.title,
                artist = it.artist,
                album = it.album,
                durationMs = it.durationMs,
                artworkUri = it.albumArtUri(),
                playbackSource = PlaybackSource("Home", "Recently downloaded")
            )
        }
    }
    val madeForYou = remember(madeForYouUris, homeTracks) {
        madeForYouUris.mapNotNull { tracksByUri[it] }
    }
    val madeForYouMedia = remember(madeForYou) {
        madeForYou.map {
            MediaTrack(
                uri = it.uri,
                title = it.title,
                artist = it.artist,
                album = it.album,
                durationMs = it.durationMs,
                artworkUri = it.albumArtUri(),
                playbackSource = PlaybackSource("Home", "Made for you")
            )
        }
    }
    val popularTracks = remember(homeTracks) {
        homeTracks
            .filter { it.playCount > 0 }
            .sortedWith(
                compareByDescending<Track> { it.playCount }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.uri }
            )
            .take(20)
    }
    val recentPlaylists = remember(homePlaylists, homeRecentlyPlayed) {
        recentlyPlayedPlaylists(homePlaylists, homeRecentlyPlayed)
    }
    val favoriteTracks = remember(homeTracks) {
        homeTracks
            .filter { it.isFavorite }
            .sortedWith(
                compareByDescending<Track> { it.addedAt }
                    .thenBy { it.uri }
            )
            .take(20)
    }
    val yourShows = remember(homePlaylists) {
        homePlaylists
            .sortedWith(
                compareByDescending<PlaylistWithTracks> { it.playlist.lastPlayedAt }
                    .thenByDescending { it.playlist.createdAt }
                    .thenBy { it.playlist.id }
            )
            .take(20)
    }
    val quickAccessPlaylists = remember(favoriteTracks, recentPlaylists, yourShows) {
        buildList {
            add(
                PlaylistWithTracks(
                    playlist = Playlist(
                        id = SystemPlaylists.FAVORITES_ID,
                        name = "Favorites"
                    ),
                    tracks = favoriteTracks
                )
            )
            addAll(
                (recentPlaylists + yourShows)
                    .filterNot { it.playlist.id == SystemPlaylists.FAVORITES_ID }
                    .distinctBy { it.playlist.id }
                    .take(5)
            )
        }
    }
    val favoriteTracksMedia = remember(favoriteTracks) {
        favoriteTracks.map {
            MediaTrack(
                uri = it.uri,
                title = it.title,
                artist = it.artist,
                album = it.album,
                durationMs = it.durationMs,
                artworkUri = it.albumArtUri(),
                playbackSource = PlaybackSource("Home", "Favorites")
            )
        }
    }
    val popularTracksMedia = remember(popularTracks) {
        popularTracks.map {
            MediaTrack(
                uri = it.uri,
                title = it.title,
                artist = it.artist,
                album = it.album,
                durationMs = it.durationMs,
                artworkUri = it.albumArtUri(),
                playbackSource = PlaybackSource("Home", "Most popular songs")
            )
        }
    }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }
    var actionsTrack by remember { mutableStateOf<Track?>(null) }
    val selectedTracks = homeTracks.filter { it.uri in selectedKeys }
    val selectedPlaylists = homePlaylists.filter {
        "p${it.playlist.id}" in selectedKeys
    }
    val selectableKeys = remember(
        homeRecentlyPlayed,
        recentlyDownloaded,
        madeForYou,
        favoriteTracks,
        popularTracks,
        yourShows,
        recentPlaylists
    ) {
        buildSet {
            addAll(homeRecentlyPlayed.map { it.uri })
            addAll(recentlyDownloaded.map { it.uri })
            addAll(madeForYou.map { it.uri })
            addAll(favoriteTracks.map { it.uri })
            addAll(popularTracks.map { it.uri })
            addAll(yourShows.map { "p${it.playlist.id}" })
        }
    }
    val scope = rememberCoroutineScope()

    fun toggleSelection(key: String) {
        val next = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
        selectedKeys = next
        selectionMode = next.isNotEmpty()
    }

    fun beginSelection(key: String) {
        selectionMode = true
        selectedKeys = selectedKeys + key
    }

    fun exitSelection() {
        selectionMode = false
        selectedKeys = emptySet()
    }

    var selectedCategory by rememberSaveable { mutableStateOf("Music") }

    // Edge-to-edge column; each section supplies its own horizontal padding
    // so carousels clip visibly at the screen edges.  Vertical rhythm is
    // standardized: 16dp above the greeting, then every section is broken
    // by a 24dp header gap with an 8dp header-to-content gap.
    LazyColumn(
        state = homeListState,
        modifier = Modifier.fillMaxSize().background(PrimaryBackground),
        // The shell overlays the mini-player on Home so its appearance never
        // changes the viewport while this list is being dragged. Keep a
        // permanent end inset so the last carousel remains reachable under
        // either shell state.
        contentPadding = PaddingValues(
            top = Dimens.paddingLarge,
            bottom = Dimens.paddingXLarge + MiniPlayerOverlayHeight
        )
    ) {
        // Greeting header
        item(key = "greeting", contentType = "hero") {
            HomeHero(
                greeting = greeting,
                onOpenDownloads = onOpenDownloads,
                onOpenRecents = onOpenRecents,
                onOpenSettings = onOpenSettings
            )
        }

        item(key = "home-categories", contentType = "category-filters") {
            HomeCategoryFilters(
                selectedCategory = selectedCategory,
                onCategorySelected = { selectedCategory = it },
                modifier = Modifier.padding(top = Dimens.paddingXLarge)
            )
        }

        if (selectedCategory != "Music") {
            item(key = "unsupported-category", contentType = "empty-state") {
                EmptyStateCard(
                    title = if (selectedCategory == "Podcasts & Shows") {
                        "No podcasts or shows in your library yet"
                    } else {
                        "No audiobooks in your library yet"
                    },
                    subtitle = "Luno currently plays music from your local library.",
                    modifier = Modifier.padding(
                        horizontal = Dimens.paddingLarge,
                        vertical = Dimens.paddingXLarge
                    )
                )
            }
        } else {
            if (quickAccessPlaylists.isNotEmpty()) {
                item(key = "recent-playlists", contentType = "recent-playlist-grid") {
                    RecentPlaylistGrid(
                        playlists = quickAccessPlaylists,
                        selected = { playlist ->
                            if (!selectionMode) {
                                null
                            } else if (playlist.playlist.id == SystemPlaylists.FAVORITES_ID) {
                                favoriteTracks.isNotEmpty() && favoriteTracks.all { it.uri in selectedKeys }
                            } else {
                                "p${playlist.playlist.id}" in selectedKeys
                            }
                        },
                        onClick = { playlist ->
                            if (selectionMode && playlist.playlist.id == SystemPlaylists.FAVORITES_ID) {
                                val favoriteUris = favoriteTracks.map { it.uri }.toSet()
                                if (favoriteUris.isNotEmpty()) {
                                    selectedKeys = if (favoriteUris.all { it in selectedKeys }) {
                                        selectedKeys - favoriteUris
                                    } else {
                                        selectedKeys + favoriteUris
                                    }
                                    selectionMode = selectedKeys.isNotEmpty()
                                }
                            } else if (selectionMode) {
                                toggleSelection("p${playlist.playlist.id}")
                            } else {
                                onOpenPlaylist(playlist.playlist.id)
                            }
                        },
                        onLongClick = { playlist ->
                            if (playlist.playlist.id == SystemPlaylists.FAVORITES_ID) {
                                if (favoriteTracks.isNotEmpty()) {
                                    selectionMode = true
                                    selectedKeys = selectedKeys + favoriteTracks.map { it.uri }
                                }
                            } else {
                                beginSelection("p${playlist.playlist.id}")
                            }
                        },
                        modifier = Modifier.padding(top = Dimens.paddingXLarge)
                    )
                }
            }

            item(key = "jump-back-in-header", contentType = "section-header") {
                SectionHeader(title = "Jump Back In")
            }
            if (homeRecentlyPlayed.isEmpty() && !homeHasCurrentTrack) {
                item(key = "jump-back-in-empty", contentType = "empty-state") {
                    EmptyStateCard(
                        title = "No tracks yet",
                        subtitle = "Play music from your library and it will show up here",
                        modifier = Modifier.padding(horizontal = Dimens.paddingLarge)
                    )
                }
            } else {
                item(key = "jump-back-in-carousel", contentType = "track-carousel") {
                    val history = homeRecentlyPlayed
                        .distinctBy { it.uri }
                        .take(20)
                        .map { it.copy(playbackSource = PlaybackSource("Home", "Jump Back In")) }
                    LazyRow(
                        state = recentlyPlayedListState,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.paddingMedium),
                        contentPadding = PaddingValues(horizontal = Dimens.paddingLarge)
                    ) {
                        items(
                            history,
                            key = { it.uri },
                            contentType = { "track-card" }
                        ) { track ->
                            TrackCard(
                                title = track.title,
                                artist = track.artist,
                                artworkUri = track.artworkUri,
                                selected = if (selectionMode) track.uri in selectedKeys else null,
                                onClick = {
                                    if (selectionMode) toggleSelection(track.uri) else {
                                        val index = history.indexOfFirst { it.uri == track.uri }
                                        onPlay(history, index.coerceAtLeast(0), false)
                                    }
                                },
                                onLongClick = { beginSelection(track.uri) }
                            )
                        }
                    }
                }
            }

            item(key = "your-shows-header", contentType = "section-header") {
                SectionHeader(title = "Your Shows")
            }
            if (yourShows.isEmpty()) {
                item(key = "your-shows-empty", contentType = "empty-state") {
                    EmptyStateCard(
                        title = "No playlists yet",
                        subtitle = "Your playlists will appear here",
                        modifier = Modifier.padding(horizontal = Dimens.paddingLarge)
                    )
                }
            } else {
                item(key = "your-shows-carousel", contentType = "playlist-carousel") {
                    LazyRow(
                        state = popularPlaylistsListState,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.paddingMedium),
                        contentPadding = PaddingValues(horizontal = Dimens.paddingLarge)
                    ) {
                        items(
                            yourShows,
                            key = { it.playlist.id },
                            contentType = { "playlist-card" }
                        ) { playlist ->
                            HomePlaylistCard(
                                name = playlist.playlist.name,
                                tracks = playlist.tracks,
                                subtitle = "${playlist.tracks.size} songs",
                                selected = if (selectionMode) {
                                    "p${playlist.playlist.id}" in selectedKeys
                                } else {
                                    null
                                },
                                onClick = {
                                    val key = "p${playlist.playlist.id}"
                                    if (selectionMode) toggleSelection(key)
                                    else onOpenPlaylist(playlist.playlist.id)
                                },
                                onLongClick = { beginSelection("p${playlist.playlist.id}") }
                            )
                        }
                    }
                }
            }

            if (selectionMode) {
                item(key = "selection-toolbar", contentType = "toolbar") {
                    BulkSelectionToolbar(
                        selectedTracks = selectedTracks,
                        selectedPlaylists = selectedPlaylists,
                        allSelected = selectableKeys.isNotEmpty() && selectableKeys.all { it in selectedKeys },
                        onSelectAll = { selectAll ->
                            if (selectAll) {
                                selectionMode = true
                                selectedKeys = selectableKeys
                            } else {
                                exitSelection()
                            }
                        },
                        onDismiss = ::exitSelection,
                        onAddToPlaylist = { playlist, trackUris ->
                            scope.launch {
                                app.playlistRepository.addTracksToPlaylist(playlist.id, trackUris)
                                Toast.makeText(context, "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
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
                        onRemoveTracks = { uris ->
                            scope.launch { uris.forEach { app.libraryRepository.deleteTrack(it) } }
                            exitSelection()
                        },
                        onDeletePlaylists = { ids ->
                            scope.launch { ids.forEach { app.playlistRepository.deletePlaylist(it) } }
                            exitSelection()
                        },
                        modifier = Modifier.padding(horizontal = Dimens.paddingLarge)
                    )
                }
            }

            // Made for you — a random mix from the entire catalogue.
            item(key = "made-header", contentType = "section-header") {
                SectionHeader(
                    title = "Made for you",
                    supportingText = "A fresh mix from your library",
                    onClick = if (homeTracks.isEmpty()) null else {
                        {
                            app.setMadeForYouTracks(madeForYou)
                            onOpenMadeForYou()
                        }
                    }
                )
            }
            if (homeTracks.isEmpty()) {
                item(key = "made-empty", contentType = "empty-state") {
                    EmptyStateCard(
                        title = "Nothing here yet",
                        subtitle = "Songs from your library will appear here",
                        modifier = Modifier.padding(horizontal = Dimens.paddingLarge)
                    )
                }
            } else {
                item(key = "made-carousel", contentType = "track-carousel") {
                    LazyRow(
                        state = madeForYouListState,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.paddingMedium),
                        contentPadding = PaddingValues(horizontal = Dimens.paddingLarge)
                    ) {
                        items(
                            madeForYou,
                            key = { it.uri },
                            contentType = { "track-card" }
                        ) { track ->
                            TrackCard(
                                title = track.title,
                                artist = track.artist,
                                artworkUri = track.albumArtUri(),
                                selected = if (selectionMode) track.uri in selectedKeys else null,
                                onClick = {
                                    if (selectionMode) toggleSelection(track.uri) else {
                                        val index = madeForYou.indexOfFirst { it.uri == track.uri }
                                        onPlay(madeForYouMedia, index.coerceAtLeast(0), false)
                                    }
                                },
                                onLongClick = { beginSelection(track.uri) }
                            )
                        }
                    }
                }
            }

            // Favorites — songs explicitly marked by the user, kept ahead of the
            // popularity section while retaining the same edge-clipped carousel.
            item(key = "favorites-header", contentType = "section-header") {
                SectionHeader(
                    title = "Favorites",
                    supportingText = "Songs you marked as favorites"
                )
            }
            if (favoriteTracks.isNotEmpty()) {
                item(key = "favorites-carousel", contentType = "track-carousel") {
                    LazyRow(
                        state = favoritesListState,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.paddingMedium),
                        contentPadding = PaddingValues(horizontal = Dimens.paddingLarge)
                    ) {
                        items(
                            favoriteTracks,
                            key = { it.uri },
                            contentType = { "track-card" }
                        ) { track ->
                            TrackCard(
                                title = track.title,
                                artist = track.artist,
                                artworkUri = track.albumArtUri(),
                                selected = if (selectionMode) track.uri in selectedKeys else null,
                                onClick = {
                                    if (selectionMode) {
                                        toggleSelection(track.uri)
                                    } else {
                                        val index = favoriteTracks.indexOfFirst { it.uri == track.uri }
                                        onPlay(favoriteTracksMedia, index.coerceAtLeast(0), false)
                                    }
                                },
                                onLongClick = { beginSelection(track.uri) },
                                onMenuClick = { actionsTrack = track }
                            )
                        }
                    }
                }
            }

            // Most popular — local play counts are persisted in Room.
            item(key = "popular-header", contentType = "section-header") {
                SectionHeader(
                    title = "Most popular",
                    supportingText = "Based on your local play counts"
                )
            }
            if (popularTracks.isEmpty()) {
                item(key = "popular-empty", contentType = "empty-state") {
                    EmptyStateCard(
                        title = "Nothing popular yet",
                        subtitle = "Play songs to build your local favorites",
                        modifier = Modifier.padding(horizontal = Dimens.paddingLarge)
                    )
                }
            }
            if (popularTracks.isNotEmpty()) {
                item(key = "popular-songs-header", contentType = "section-header") {
                    SectionHeader(title = "Popular songs", topPadding = 0.dp)
                }
                item(key = "popular-songs-carousel", contentType = "track-carousel") {
                    LazyRow(
                        state = popularTracksListState,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.paddingMedium),
                        contentPadding = PaddingValues(horizontal = Dimens.paddingLarge)
                    ) {
                        items(
                            popularTracks,
                            key = { it.uri },
                            contentType = { "track-card" }
                        ) { track ->
                            TrackCard(
                                title = track.title,
                                artist = track.artist,
                                artworkUri = track.albumArtUri(),
                                selected = if (selectionMode) track.uri in selectedKeys else null,
                                onClick = {
                                    if (selectionMode) toggleSelection(track.uri) else {
                                        val index = popularTracks.indexOfFirst { it.uri == track.uri }
                                        onPlay(popularTracksMedia, index.coerceAtLeast(0), false)
                                    }
                                },
                                onLongClick = { beginSelection(track.uri) }
                            )
                        }
                    }
                }
            }
            // Recently downloaded is the final Home category: completed downloads
            // in completion order, capped at the latest 50 songs.
            item(key = "downloaded-header", contentType = "section-header") {
                SectionHeader(
                    title = "Recently downloaded",
                    supportingText = "Your latest 50 downloads"
                )
            }
            if (recentlyDownloaded.isEmpty()) {
                item(key = "downloaded-empty", contentType = "empty-state") {
                    EmptyStateCard(
                        title = "No downloads yet",
                        subtitle = "Downloaded songs will appear here",
                        modifier = Modifier.padding(horizontal = Dimens.paddingLarge)
                    )
                }
            } else {
                item(key = "downloaded-carousel", contentType = "track-carousel") {
                    LazyRow(
                        state = recentlyDownloadedListState,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.paddingMedium),
                        contentPadding = PaddingValues(horizontal = Dimens.paddingLarge)
                    ) {
                        items(
                            recentlyDownloaded,
                            key = { it.uri },
                            contentType = { "track-card" }
                        ) { track ->
                            TrackCard(
                                title = track.title,
                                artist = track.artist,
                                artworkUri = track.albumArtUri(),
                                selected = if (selectionMode) track.uri in selectedKeys else null,
                                onClick = {
                                    if (selectionMode) {
                                        toggleSelection(track.uri)
                                    } else {
                                        val index = recentlyDownloaded.indexOfFirst { it.uri == track.uri }
                                        onPlay(recentlyDownloadedMedia, index.coerceAtLeast(0), false)
                                    }
                                },
                                onLongClick = { beginSelection(track.uri) }
                            )
                        }
                    }
                }
            }
        }
    }

    actionsTrack?.let { track ->
        TrackActionsSheet(
            track = track,
            onDismiss = { actionsTrack = null }
        )
    }
}

/**
 * Playlist card used in Home's horizontal artwork rows: square artwork from
 * its first track, with the playlist name and track count below.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HomePlaylistCard(
    name: String,
    tracks: List<Track>,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean?
) {
    Column(
        modifier = Modifier
            .width(148.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box {
            RecommendationArtworkImage(
                artist = tracks.firstOrNull()?.artist ?: name,
                title = tracks.firstOrNull()?.title ?: name,
                artworkUri = tracks.firstOrNull()?.albumArtUri(),
                modifier = Modifier
                    .size(148.dp)
                    .clip(RoundedCornerShape(Dimens.cornerLarge)),
                placeholderIconSize = 40.dp,
                decodeSizePx = 384
            )
            if (selected != null) {
                Icon(
                    imageVector = if (selected) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.RadioButtonUnchecked
                    },
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) AccentGreen else SecondaryText,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Dimens.paddingSmall)
                )
            }
        }
        Spacer(modifier = Modifier.height(Dimens.paddingSmall))
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = PrimaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = SecondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HomeHero(
    greeting: String,
    onOpenDownloads: () -> Unit,
    onOpenRecents: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.paddingLarge),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Good $greeting",
            style = MaterialTheme.typography.titleLarge,
            color = PrimaryText,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onOpenDownloads, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Filled.NotificationsNone,
                contentDescription = "Downloads",
                tint = PrimaryText,
                modifier = Modifier.size(Dimens.iconSize)
            )
        }
        IconButton(onClick = onOpenRecents, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = "Recently played",
                tint = PrimaryText,
                modifier = Modifier.size(Dimens.iconSize)
            )
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Filled.AccountCircle,
                contentDescription = "Profile and settings",
                tint = PrimaryText,
                modifier = Modifier.size(Dimens.iconSize)
            )
        }
    }
}

@Composable
private fun HomeCategoryFilters(
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.paddingLarge),
        horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSmall)
    ) {
        listOf("Music", "Podcasts & Shows", "Audiobooks").forEach { category ->
            val selected = category == selectedCategory
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (selected) SurfaceElevated else SurfaceDark)
                    .clickable { onCategorySelected(category) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = category,
                    style = MaterialTheme.typography.labelLarge,
                    color = PrimaryText,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun RecentPlaylistGrid(
    playlists: List<PlaylistWithTracks>,
    selected: (PlaylistWithTracks) -> Boolean?,
    onClick: (PlaylistWithTracks) -> Unit,
    onLongClick: (PlaylistWithTracks) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.paddingLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.paddingSmall)
    ) {
        playlists.chunked(2).forEach { rowPlaylists ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSmall)) {
                rowPlaylists.forEach { playlist ->
                    RecentPlaylistTile(
                        playlist = playlist,
                        selected = selected(playlist),
                        onClick = { onClick(playlist) },
                        onLongClick = { onLongClick(playlist) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowPlaylists.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun RecentPlaylistTile(
    playlist: PlaylistWithTracks,
    selected: Boolean?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(Dimens.cornerSmall))
            .background(SurfaceDark)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (playlist.playlist.id == SystemPlaylists.FAVORITES_ID) {
            Box(
                modifier = Modifier.size(56.dp).background(
                    Brush.linearGradient(listOf(Color(0xFF4825C5), Color(0xFFC3D6CF)))
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Favorite, contentDescription = null, tint = Color.White)
            }
        } else {
            ArtworkCollage(
                tracks = playlist.tracks,
                modifier = Modifier.size(56.dp),
                placeholderIconSize = 14.dp,
                decodeSizePx = 192
            )
        }
        Text(
            text = if (playlist.playlist.id == SystemPlaylists.FAVORITES_ID) {
                "Liked Songs"
            } else {
                playlist.playlist.name
            },
            style = MaterialTheme.typography.labelLarge,
            color = PrimaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Dimens.paddingSmall)
        )
        if (selected != null) {
            Icon(
                imageVector = if (selected) {
                    Icons.Filled.CheckCircle
                } else {
                    Icons.Filled.RadioButtonUnchecked
                },
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) AccentGreen else SecondaryText,
                modifier = Modifier
                    .size(Dimens.iconSizeSmall)
                    .padding(end = 2.dp)
            )
        }
    }
}

/**
 * Section title with the standardized rhythm used across Home: 24dp above
 * (break between sections), 8dp below (header-to-content gap).
 */
@Composable
fun SectionHeader(
    title: String,
    supportingText: String? = null,
    onClick: (() -> Unit)? = null,
    topPadding: Dp = Dimens.paddingXLarge
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Dimens.paddingLarge,
                end = Dimens.paddingLarge,
                top = topPadding,
                bottom = Dimens.paddingSmall
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick
                        )
                    } else {
                        Modifier
                    }
                )
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = PrimaryText,
                fontWeight = FontWeight.Bold
            )
            supportingText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (onClick != null) {
            Text(
                text = "View all",
                style = MaterialTheme.typography.labelLarge,
                color = AccentGreen,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun TrackCard(
    title: String,
    artist: String,
    artworkUri: String? = null,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    onMenuClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .width(148.dp)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                } else {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    )
                }
            )
    ) {
        Box {
            RecommendationArtworkImage(
                artist = artist,
                title = title,
                artworkUri = artworkUri,
                modifier = Modifier
                    .size(148.dp)
                    .clip(RoundedCornerShape(Dimens.cornerLarge)),
                placeholderIconSize = 40.dp,
                decodeSizePx = 384
            )
            if (selected != null) {
                Icon(
                    imageVector = if (selected) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.RadioButtonUnchecked
                    },
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) AccentGreen else SecondaryText,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Dimens.paddingSmall)
                )
            }
            if (onMenuClick != null) {
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Track options",
                        tint = AccentGreen
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(Dimens.paddingSmall))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = PrimaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = artist,
            style = MaterialTheme.typography.bodySmall,
            color = SecondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun EmptyStateCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.paddingMedium)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = PrimaryText
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = SecondaryText,
            modifier = Modifier.padding(top = Dimens.paddingSmall)
        )
    }
}

private fun getGreeting(): String {
    val hour = SimpleDateFormat("HH", Locale.getDefault()).format(Date()).toIntOrNull() ?: 12
    return when (hour) {
        in 5..11 -> "morning"
        in 12..17 -> "afternoon"
        else -> "evening"
    }
}
