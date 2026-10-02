package com.luno.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luno.mobile.data.db.entity.Playlist
import com.luno.mobile.data.db.entity.Track
import com.luno.mobile.data.db.entity.SystemPlaylists
import com.luno.mobile.ui.theme.AppBackgroundGreen
import com.luno.mobile.ui.theme.AccentGreen
import com.luno.mobile.ui.theme.Dimens
import com.luno.mobile.ui.theme.PrimaryText
import com.luno.mobile.ui.theme.SecondaryText

/**
 * Library record sleeve: upright artwork, playlist name/count/duration and
 * existing options. The virtual Favorites playlist has its own green heart tile.
 *
 * [onSync] / [onUrlChanged] / [onDelete] control which items appear in the
 * 3-dot menu; passing only [onClick] yields a plain tappable card.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun PlaylistCard(
    playlist: Playlist,
    tracks: List<Track>,
    onClick: () -> Unit,
    onSync: (() -> Unit)? = null,
    onStopSync: (() -> Unit)? = null,
    onUrlChanged: ((String) -> Unit)? = null,
    onClearPlaylist: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    showOptions: Boolean = true
) {
    var showMenu by remember { mutableStateOf(false) }
    val favorites = playlist.id == SystemPlaylists.FAVORITES_ID
    val duration = remember(tracks) { tracks.sumOf { it.durationMs } }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .then(if (favorites) Modifier.background(Brush.horizontalGradient(
                    listOf(AppBackgroundGreen, AppBackgroundGreen.copy(alpha = 0.4f))
                )).border(1.dp, AccentGreen.copy(alpha = 0.1f), RoundedCornerShape(12.dp)) else Modifier)
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
                .padding(horizontal = if (favorites) 12.dp else 0.dp, vertical = 12.dp),
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

            if (favorites) {
                Box(Modifier.size(60.dp).clip(RoundedCornerShape(8.dp))
                    .background(AccentGreen.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Favorite, null, tint = AccentGreen, modifier = Modifier.size(30.dp))
                }
            } else {
                ArtworkCollage(
                    tracks = tracks,
                    modifier = Modifier.size(88.dp),
                    placeholderIconSize = 20.dp,
                    decodeSizePx = 384,
                    shape = RoundedCornerShape(8.dp)
                )
            }

            Spacer(modifier = Modifier.width(18.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
            ) {
                Text(
                    text = playlist.name,
                    style = if (favorites) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = PrimaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text("${tracks.size} songs · ${formatPlaylistDuration(duration)}",
                    style = MaterialTheme.typography.bodySmall, color = SecondaryText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            if (showOptions) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Playlist options",
                            tint = SecondaryText,
                            modifier = Modifier.size(Dimens.iconSize)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if (onSync != null && playlist.playlistUrl.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text("Sync playlist", color = PrimaryText) },
                                onClick = {
                                    showMenu = false
                                    onSync()
                                }
                            )
                        }
                        if (onStopSync != null) {
                            DropdownMenuItem(
                                text = { Text("Stop sync", color = PrimaryText) },
                                onClick = {
                                    showMenu = false
                                    onStopSync()
                                }
                            )
                        }
                        if (onUrlChanged != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (playlist.playlistUrl.isNotBlank()) "Edit YouTube URL" else "Set YouTube URL",
                                        color = PrimaryText
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onUrlChanged(playlist.playlistUrl)
                                }
                            )
                        }
                        if (onClearPlaylist != null && tracks.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Clear playlist", color = PrimaryText) },
                                onClick = {
                                    showMenu = false
                                    onClearPlaylist()
                                }
                            )
                        }
                        if (onExport != null) {
                            DropdownMenuItem(
                                text = { Text("Export playlist", color = PrimaryText) },
                                onClick = {
                                    showMenu = false
                                    onExport()
                                }
                            )
                        }
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text("Delete playlist", color = PrimaryText) },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
        }
        if (!favorites) HorizontalDivider(color = Color.White.copy(alpha = 0.07f), thickness = 0.5.dp)

    }
}
