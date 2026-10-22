package com.luno.mobile.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Covers the destination's first frame even when system Back bypasses navigation callbacks. */
@Composable
internal fun DestinationTransitionCover(
    entryId: String?, requested: Boolean, onDestinationChanged: () -> Unit
) {
    var coveredEntry by remember { mutableStateOf(entryId) }
    val changed = entryId != coveredEntry
    SideEffect {
        if (changed) {
            onDestinationChanged()
            coveredEntry = entryId
        }
    }
    TransitionMask(visible = requested || changed)
}
