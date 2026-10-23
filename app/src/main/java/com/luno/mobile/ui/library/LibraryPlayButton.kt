package com.luno.mobile.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luno.mobile.ui.theme.AccentGreen
import com.luno.mobile.ui.theme.AccentRed
import com.luno.mobile.ui.theme.PlayerPurple
import com.luno.mobile.ui.theme.PrimaryBackground
import com.luno.mobile.ui.theme.PrimaryText
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryPlayButton(enabled: Boolean, shuffle: Boolean, onPlay: () -> Unit, onToggleShuffle: () -> Unit) {
    val configuration = LocalViewConfiguration.current
    val haptic = LocalHapticFeedback.current
    var feedbackEnabled by remember { mutableStateOf<Boolean?>(null) }
    var feedbackVisible by remember { mutableStateOf(false) }
    var feedbackToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(feedbackToken) {
        if (!feedbackVisible) return@LaunchedEffect
        delay(SHUFFLE_FEEDBACK_MILLIS)
        feedbackVisible = false
    }

    CompositionLocalProvider(LocalViewConfiguration provides object : ViewConfiguration by configuration {
        override val longPressTimeoutMillis: Long = 1000L
    }) {
        // The outer box must not be clipped: the shuffle badge and feedback
        // label intentionally draw outside the circular button.
        Box(Modifier.size(44.dp)) {
            Box(
                Modifier.matchParentSize()
                    .clip(CircleShape)
                    .background(PlayerPurple.copy(alpha = if (enabled) 1f else 0.35f))
                    .then(
                        if (shuffle) {
                            Modifier.border(2.dp, AccentGreen, CircleShape)
                        } else {
                            Modifier
                        }
                    )
                    .semantics {
                        contentDescription = if (shuffle) "Shuffle library" else "Play library"
                        stateDescription = if (shuffle) "Shuffle on" else "Shuffle off"
                    }
                    .combinedClickable(
                        enabled = enabled,
                        onClick = onPlay,
                        onLongClickLabel = "Toggle library shuffle",
                        onLongClick = {
                            val next = !shuffle
                            onToggleShuffle()
                            feedbackEnabled = next
                            feedbackVisible = true
                            feedbackToken++
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    null,
                    tint = PrimaryText,
                    modifier = Modifier.size(28.dp)
                )
            }

            if (shuffle) {
                Box(
                    Modifier.align(Alignment.BottomEnd)
                        .size(18.dp)
                        .background(AccentGreen, CircleShape)
                        .border(2.dp, PrimaryBackground, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Shuffle,
                        null,
                        tint = PrimaryBackground,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = feedbackVisible,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-34).dp)
                    .wrapContentWidth(
                        align = Alignment.CenterHorizontally,
                        unbounded = true
                    ),
                enter = fadeIn(tween(100)) + slideInVertically(tween(120)) { it / 2 },
                exit = fadeOut(tween(160))
            ) {
                Text(
                    text = if (feedbackEnabled == true) "Shuffle enabled" else "Shuffle off",
                    color = if (feedbackEnabled == true) AccentGreen else AccentRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(
                            PrimaryBackground.copy(alpha = 0.92f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

private const val SHUFFLE_FEEDBACK_MILLIS = 1000L
