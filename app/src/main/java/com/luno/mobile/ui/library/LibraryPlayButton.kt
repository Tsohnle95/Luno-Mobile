package com.luno.mobile.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.luno.mobile.ui.theme.PlayerPurple
import com.luno.mobile.ui.theme.PrimaryText

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryPlayButton(enabled: Boolean, shuffle: Boolean, onPlay: () -> Unit, onToggleShuffle: () -> Unit) {
    val configuration = LocalViewConfiguration.current
    val haptic = LocalHapticFeedback.current
    CompositionLocalProvider(LocalViewConfiguration provides object : ViewConfiguration by configuration {
        override val longPressTimeoutMillis: Long = 1000L
    }) {
        Box(Modifier.size(44.dp).clip(CircleShape)
            .background(PlayerPurple.copy(alpha = if (enabled) 1f else 0.35f))
            .semantics {
                contentDescription = if (shuffle) "Shuffle library" else "Play library"
                stateDescription = if (shuffle) "Shuffle on" else "Shuffle off"
            }
            .combinedClickable(enabled = enabled, onClick = onPlay,
                onLongClickLabel = "Toggle library shuffle", onLongClick = {
                    onToggleShuffle()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, null, tint = PrimaryText, modifier = Modifier.size(28.dp))
            if (shuffle) Icon(Icons.Filled.Shuffle, null, tint = PrimaryText,
                modifier = Modifier.align(Alignment.BottomEnd).size(15.dp)
                    .background(PlayerPurple, CircleShape))
        }
    }
}
