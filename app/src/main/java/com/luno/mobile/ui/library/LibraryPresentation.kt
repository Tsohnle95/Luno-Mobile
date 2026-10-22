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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luno.mobile.ui.components.TrackSortMode
import com.luno.mobile.ui.theme.AccentGreen
import com.luno.mobile.ui.theme.PrimaryBackground
import com.luno.mobile.ui.theme.PrimaryText
import com.luno.mobile.ui.theme.SecondaryText
import com.luno.mobile.ui.theme.SurfaceDark

internal enum class LibraryFilter(val label: String) { PLAYLISTS("Playlists"), SONGS("Songs"), ALBUMS("Albums") }

@Composable
internal fun LibraryHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: LibraryFilter,
    onFilterChange: (LibraryFilter) -> Unit,
    sortMode: TrackSortMode,
    onSortChange: (TrackSortMode) -> Unit,
    gridView: Boolean,
    onToggleView: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectAll: () -> Unit,
    libraryShuffle: Boolean = false,
    libraryCanPlay: Boolean = true,
    onPlayLibrary: () -> Unit = {},
    onToggleLibraryShuffle: () -> Unit = {},
    selectionToolbar: @Composable () -> Unit
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var batchMenu by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searching) { if (searching) searchFocus.requestFocus() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.luno.mobile.ui.components.LunoProfileButton(onOpenSettings)
            Text("Your Library", modifier = Modifier.weight(1f).padding(start = 10.dp),
                style = MaterialTheme.typography.headlineSmall, fontSize = 26.sp,
                letterSpacing = (-1).sp, fontWeight = FontWeight.Bold, color = PrimaryText,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { searching = !searching }) {
                Icon(Icons.Filled.Search, "Search library", tint = PrimaryText)
            }
            IconButton(onClick = onCreatePlaylist) {
                Icon(Icons.Filled.Add, "Create playlist", tint = PrimaryText)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            LibraryFilter.entries.forEach { entry ->
                val active = filter == entry
                Box(
                    Modifier.clip(RoundedCornerShape(24.dp))
                        .background(if (active) PrimaryText else SurfaceDark)
                        .semantics { selected = active }
                        .clickable { onFilterChange(entry) }
                        .padding(horizontal = 17.dp, vertical = 11.dp)
                ) {
                    Text(entry.label, fontSize = 12.sp,
                        color = if (active) PrimaryBackground else PrimaryText)
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                LibraryPlayButton(libraryCanPlay, libraryShuffle, onPlayLibrary, onToggleLibraryShuffle)
            }
        }
        if (searching || query.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            LibrarySearch(query, onQueryChange, Modifier.focusRequester(searchFocus))
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            LibrarySortMenu(sortMode, onSortChange, Modifier.weight(1f))
            IconButton(onClick = onToggleView, enabled = filter == LibraryFilter.PLAYLISTS) {
                Icon(if (gridView) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                    if (gridView) "Show list" else "Show grid",
                    tint = if (filter != LibraryFilter.PLAYLISTS) SecondaryText.copy(alpha = 0.35f) else SecondaryText,
                    modifier = Modifier.size(21.dp))
            }
            Box {
                IconButton(onClick = { batchMenu = true }, enabled = filter != LibraryFilter.ALBUMS) {
                    Icon(Icons.Filled.MoreVert, "Library actions", tint = SecondaryText, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(batchMenu, onDismissRequest = { batchMenu = false }) {
                    DropdownMenuItem(text = { Text("Select all") }, onClick = {
                        batchMenu = false
                        onSelectAll()
                    })
                }
            }
        }
        selectionToolbar()
    }
}

@Composable
private fun LibrarySortMenu(mode: TrackSortMode, onChange: (TrackSortMode) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.height(48.dp).clip(RoundedCornerShape(8.dp))
            .clickable { expanded = true }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(Icons.AutoMirrored.Filled.Sort, "Sort: ${mode.label}", tint = SecondaryText, modifier = Modifier.size(17.dp))
            Text(mode.label, style = MaterialTheme.typography.labelSmall, color = SecondaryText,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun LibrarySearch(query: String, onChange: (String) -> Unit, modifier: Modifier) {
    BasicTextField(
        value = query,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(5.dp)).background(SurfaceDark),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = PrimaryText),
        cursorBrush = SolidColor(AccentGreen),
        singleLine = true,
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = SecondaryText, modifier = Modifier.size(18.dp))
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
