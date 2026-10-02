package com.luno.mobile.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luno.mobile.data.db.entity.Track
import com.luno.mobile.ui.components.ArtworkImage
import com.luno.mobile.ui.components.TrackSortMode
import com.luno.mobile.ui.theme.AccentGreen
import com.luno.mobile.ui.theme.AppBackgroundGreen
import com.luno.mobile.ui.theme.PrimaryBackground
import com.luno.mobile.ui.theme.PrimaryText
import com.luno.mobile.ui.theme.SecondaryText
import com.luno.mobile.ui.theme.SurfaceDark

/** The shelf's green atmosphere stays behind the list as the artwork header scrolls away. */
internal fun Modifier.libraryShelfBackground(): Modifier = drawWithCache {
    val upperGlow = Brush.radialGradient(
        colors = listOf(AppBackgroundGreen, AppBackgroundGreen.copy(alpha = 0.45f), Color.Transparent),
        center = Offset(size.width * 0.65f, size.height * 0.12f),
        radius = size.maxDimension * 0.85f
    )
    val lowerGlow = Brush.radialGradient(
        colors = listOf(AppBackgroundGreen.copy(alpha = 0.5f), Color.Transparent),
        center = Offset(size.width * -0.1f, size.height * 0.82f),
        radius = size.minDimension * 0.85f
    )
    onDrawBehind {
        drawRect(PrimaryBackground)
        drawRect(upperGlow)
        drawRect(lowerGlow)
    }
}

@Composable
internal fun LibraryHeader(
    tracks: List<Track>,
    playlistCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    playlistView: Boolean,
    onViewChange: (Boolean) -> Unit,
    sortMode: TrackSortMode,
    onSortChange: (TrackSortMode) -> Unit,
    canPlay: Boolean,
    shuffleEnabled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit
) {
    // Artwork is stable while searching, sorting or switching views; it is decoration,
    // never a new projection or a source of playback/metadata state.
    val heroTracks = remember(tracks) {
        tracks.asSequence().filter { !it.albumArtPath.isNullOrBlank() }
            .distinctBy { it.albumArtPath }.take(6).toList()
    }
    Box(Modifier.fillMaxWidth().clipToBounds()) {
        if (heroTracks.isNotEmpty()) {
            Column(
                Modifier.matchParentSize()
                    .clearAndSetSemantics { }
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithCache {
                        val fade = Brush.verticalGradient(
                            colorStops = arrayOf(0f to Color.Black, 0.45f to Color.Black, 1f to Color.Transparent)
                        )
                        onDrawWithContent {
                            drawContent()
                            // Mask the artwork into the actual body gradient instead of
                            // fading to an opaque colour that leaves a seam at the header.
                            drawRect(fade, blendMode = BlendMode.DstIn)
                        }
                    }
            ) {
                repeat(2) { row ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(3) { col ->
                            val track = heroTracks[(row * 3 + col) % heroTracks.size]
                            ArtworkImage(
                                artworkUri = track.albumArtUri(),
                                shape = RectangleShape,
                                decodeSizePx = 384,
                                modifier = Modifier.weight(1f).aspectRatio(1f)
                                    .graphicsLayer { alpha = 0.48f }
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            listOf(PrimaryBackground.copy(alpha = 0.5f), AppBackgroundGreen.copy(alpha = 0.65f), Color.Transparent)
        )))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your Library", style = MaterialTheme.typography.headlineLarge, color = PrimaryText)
                    Spacer(Modifier.height(8.dp))
                    Text("${tracks.size} songs · $playlistCount playlists",
                        style = MaterialTheme.typography.bodySmall, color = SecondaryText)
                }
                IconButton(
                    onClick = onPlay,
                    enabled = canPlay,
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                        .background(if (canPlay) AccentGreen else SurfaceDark)
                ) {
                    Icon(Icons.Filled.PlayArrow, "Play library",
                        tint = if (canPlay) Color.Black else SecondaryText, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = onShuffle, enabled = canPlay, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Shuffle, "Shuffle library",
                        tint = if (shuffleEnabled && canPlay) AccentGreen else SecondaryText,
                        modifier = Modifier.size(22.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LibraryViewTabs(playlistView, onViewChange, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                LibrarySortMenu(sortMode, onSortChange, Modifier.weight(1f))
            }
            LibrarySearch(query, onQueryChange)
        }
    }
}

@Composable
private fun LibraryViewTabs(playlistView: Boolean, onViewChange: (Boolean) -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        listOf("Songs" to false, "Playlists" to true).forEach { (label, isPlaylist) ->
            val active = playlistView == isPlaylist
            Column(
                Modifier.semantics { selected = active }
                    .clickable(onClick = { onViewChange(isPlaylist) }),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.height(45.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (active) PrimaryText else SecondaryText)
                }
                Box(Modifier.width(if (isPlaylist) 54.dp else 38.dp).height(3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(if (active) AccentGreen else Color.Transparent))
            }
        }
    }
}

@Composable
private fun LibrarySortMenu(mode: TrackSortMode, onChange: (TrackSortMode) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp))
            .clickable { expanded = true }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(Icons.Filled.Sort, "Sort: ${mode.label}", tint = SecondaryText, modifier = Modifier.size(18.dp))
            Text(mode.label, style = MaterialTheme.typography.labelSmall, color = SecondaryText,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = SecondaryText, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            TrackSortMode.LIBRARY_MODES.forEach { entry ->
                DropdownMenuItem(text = { Text(entry.label, color = if (entry == mode) AccentGreen else PrimaryText) },
                    onClick = { expanded = false; onChange(entry) })
            }
        }
    }
}

@Composable
private fun LibrarySearch(query: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().height(48.dp).clip(shape)
            .background(SurfaceDark.copy(alpha = 0.86f))
            .border(1.dp, if (focused) AccentGreen else Color.White.copy(alpha = 0.07f), shape)
            .onFocusChanged { focused = it.isFocused },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = PrimaryText),
        cursorBrush = SolidColor(AccentGreen),
        singleLine = true,
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = SecondaryText, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Search your library", style = MaterialTheme.typography.bodyMedium,
                        color = SecondaryText, maxLines = 1)
                    inner()
                }
                if (query.isNotEmpty()) IconButton(onClick = { onChange("") }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Clear, "Clear search", tint = SecondaryText, modifier = Modifier.size(18.dp))
                }
            }
        }
    )
}
