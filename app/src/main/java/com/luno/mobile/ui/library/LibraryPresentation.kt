package com.luno.mobile.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luno.mobile.data.db.entity.Track
import com.luno.mobile.ui.components.TrackSortMode
import com.luno.mobile.ui.theme.AccentGreen
import com.luno.mobile.ui.theme.PrimaryBackground
import com.luno.mobile.ui.theme.PrimaryText
import com.luno.mobile.ui.theme.SecondaryText
import com.luno.mobile.ui.theme.SurfaceDark

/** Concept 10's green corner light and faint shelves remain fixed behind the list. */
internal fun Modifier.libraryShelfBackground(): Modifier = drawWithCache {
    val glow = Brush.radialGradient(
        colorStops = arrayOf(0f to Color(0xFF19472D), 0.6f to Color.Transparent),
        center = Offset.Zero,
        radius = 1f
    )
    val horizontalRadius = size.width * 0.65f * kotlin.math.sqrt(2f)
    val verticalRadius = size.height * kotlin.math.sqrt(2f)
    val shelfSpacing = 120.dp.toPx()
    onDrawBehind {
        drawRect(PrimaryBackground)
        withTransform({
            translate(size.width * 0.65f, 0f)
            scale(horizontalRadius, verticalRadius, pivot = Offset.Zero)
        }) {
            drawCircle(glow, radius = 1f, center = Offset.Zero)
        }
        var shelfY = shelfSpacing - 1.dp.toPx()
        while (shelfY < size.height) {
            drawLine(Color.White.copy(alpha = 0.016f), Offset(0f, shelfY),
                Offset(size.width, shelfY), strokeWidth = 1.dp.toPx())
            shelfY += shelfSpacing
        }
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
    onShuffle: () -> Unit,
    sectionHeader: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 4.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your Library", style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-1).sp
                    ), color = PrimaryText)
                    Spacer(Modifier.height(9.dp))
                    Text("${tracks.size} songs · $playlistCount playlists",
                        style = MaterialTheme.typography.bodySmall, color = SecondaryText)
                }
                Spacer(Modifier.width(12.dp))
                IconButton(
                    onClick = onPlay,
                    enabled = canPlay,
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .background(if (canPlay) AccentGreen else SurfaceDark)
                ) {
                    Icon(Icons.Filled.PlayArrow, "Play library",
                        tint = if (canPlay) Color.Black else SecondaryText, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onShuffle, enabled = canPlay, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Shuffle, "Shuffle library",
                        tint = if (shuffleEnabled && canPlay) AccentGreen else SecondaryText,
                        modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(30.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LibraryViewTabs(playlistView, onViewChange, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                LibrarySortMenu(sortMode, onSortChange, Modifier.width(124.dp))
            }
            Spacer(Modifier.height(22.dp))
            LibrarySearch(query, onQueryChange)
        }
        Spacer(Modifier.height(8.dp))
        sectionHeader()
    }
}

@Composable
private fun LibraryViewTabs(playlistView: Boolean, onViewChange: (Boolean) -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        listOf("Songs" to false, "Playlists" to true).forEach { (label, isPlaylist) ->
            val active = playlistView == isPlaylist
            Column(
                Modifier.semantics { selected = active }
                    .clickable(onClick = { onViewChange(isPlaylist) }),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.height(45.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge, fontSize = 13.sp,
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
    BasicTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().height(46.dp)
            .drawWithCache {
                onDrawBehind {
                    drawLine(if (focused) AccentGreen else Color.White.copy(alpha = 0.15f),
                        Offset(0f, size.height - 0.5.dp.toPx()),
                        Offset(size.width, size.height - 0.5.dp.toPx()), strokeWidth = 1.dp.toPx())
                }
            }
            .onFocusChanged { focused = it.isFocused },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = PrimaryText),
        cursorBrush = SolidColor(AccentGreen),
        singleLine = true,
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
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
