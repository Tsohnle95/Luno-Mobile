package com.luno.mobile.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.luno.mobile.playback.TemporaryVideoState
import com.luno.mobile.playback.TemporaryVideoStatus
import com.luno.mobile.ui.theme.PrimaryText
import com.luno.mobile.ui.theme.PlayerPurple

internal fun videoSeekTarget(audioMs: Long, videoMs: Long): Long? =
    audioMs.coerceAtLeast(0).takeIf { kotlin.math.abs(it - videoMs) > 400L }

/** Audio MediaSession remains the only audible player and authoritative clock. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun TemporaryVideoSurface(
    state: TemporaryVideoState,
    trackUri: String?,
    audioPositionMs: Long,
    audioPlaying: Boolean,
    modifier: Modifier,
    onRetry: () -> Unit,
    onDecoderError: (String) -> Unit
) {
    if (!state.enabled || trackUri == null || state.trackUri != trackUri) return
    val context = LocalContext.current
    Box(modifier.clipToBounds().background(Color.Black.copy(alpha = 0.45f))) {
        if (state.status == TemporaryVideoStatus.READY && state.file != null) {
            val player = remember(trackUri) {
                ExoPlayer.Builder(context).build().apply {
                    volume = 0f
                    setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, false)
                }
            }
            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { onDecoderError(trackUri) }
                }
                player.addListener(listener)
                onDispose { player.removeListener(listener); player.release() }
            }
            LaunchedEffect(player, state.file) {
                player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(state.file)))
                player.seekTo(audioPositionMs)
                player.prepare()
            }
            LaunchedEffect(player, audioPositionMs, audioPlaying) {
                player.volume = 0f
                videoSeekTarget(audioPositionMs, player.currentPosition)?.let(player::seekTo)
                player.playWhenReady = audioPlaying
            }
            AndroidView(
                factory = { PlayerView(it).apply {
                    useController = false
                    setKeepContentOnPlayerReset(true)
                    // Match the artwork's full-bleed crop inside the existing stage.
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setBackgroundColor(android.graphics.Color.BLACK)
                    clipChildren = true
                    clipToPadding = true
                    contentDescription = "Temporary song video"
                    this.player = player
                } },
                modifier = Modifier.fillMaxSize(),
                update = { it.player = player },
                onRelease = { it.player = null }
            )
        } else {
            Column(Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.status == TemporaryVideoStatus.ERROR) {
                    Text(state.error ?: "Video unavailable", color = PrimaryText)
                    TextButton(onClick = onRetry) { Text("Retry video", color = PrimaryText) }
                } else {
                    VideoLoadingIndicator(state.progress.takeIf {
                        state.status == TemporaryVideoStatus.DOWNLOADING
                    })
                }
            }
        }
    }
}

@Composable
internal fun VideoLoadingIndicator(progress: Float?) {
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (progress == null) {
            CircularProgressIndicator(Modifier.fillMaxSize(), color = PlayerPurple, strokeWidth = 3.dp)
        } else {
            val bounded = progress.coerceIn(0f, 1f)
            CircularProgressIndicator(progress = { bounded }, modifier = Modifier.fillMaxSize(),
                color = PlayerPurple, strokeWidth = 3.dp)
            Text("${(bounded * 100).toInt()}%", color = PrimaryText, fontSize = 12.sp)
        }
    }
}
