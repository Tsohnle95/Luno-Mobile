package com.luno.mobile.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Both orientations render the same controls and state; only placement changes. */
@Composable
internal fun ResponsivePlayerStage(
    viewportHeight: Dp,
    landscape: Boolean,
    artwork: @Composable (Modifier) -> Unit,
    controls: @Composable () -> Unit
) {
    if (landscape) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(viewportHeight).testTag("landscape-player-artwork")) { artwork(Modifier.fillMaxSize()) }
            Box(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 16.dp)) { controls() }
        }
    } else {
        Column(Modifier.fillMaxWidth().heightIn(min = viewportHeight).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.fillMaxWidth().height(viewportHeight * 0.65f)) { artwork(Modifier.fillMaxSize()) }
            controls()
        }
    }
}
