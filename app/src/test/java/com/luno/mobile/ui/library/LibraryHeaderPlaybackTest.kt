package com.luno.mobile.ui.library

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.luno.mobile.ui.components.TrackSortMode
import com.luno.mobile.ui.theme.LunoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class LibraryHeaderPlaybackTest {
    @get:Rule val compose = createComposeRule()
    @Test fun libraryHasAnIndependentPlayActionBesideItsFilters() {
        compose.setContent { LunoTheme {
            LibraryHeader(query = "", onQueryChange = {}, filter = LibraryFilter.ALBUMS,
                onFilterChange = {}, sortMode = TrackSortMode.entries.first(), onSortChange = {},
                gridView = false, onToggleView = {}, onCreatePlaylist = {}, onOpenSettings = {},
                onSelectAll = {}, selectionToolbar = {})
        } }
        compose.onNodeWithContentDescription("Play library").assertIsDisplayed()
    }
    @Test fun holdingForOneSecondTogglesShuffleWithoutStartingPlayback() {
        val shuffle = mutableStateOf(false)
        var plays = 0
        compose.setContent { LunoTheme {
            LibraryHeader(query = "filtered query", onQueryChange = {}, filter = LibraryFilter.SONGS,
                onFilterChange = {}, sortMode = TrackSortMode.entries.first(), onSortChange = {},
                gridView = false, onToggleView = {}, onCreatePlaylist = {}, onOpenSettings = {},
                onSelectAll = {}, libraryShuffle = shuffle.value, onPlayLibrary = { plays++ },
                onToggleLibraryShuffle = { shuffle.value = !shuffle.value }, selectionToolbar = {})
        } }
        compose.onNodeWithContentDescription("Play library").performTouchInput {
            down(center); advanceEventTime(1100); up()
        }
        compose.runOnIdle { assertThat(plays).isEqualTo(0); assertThat(shuffle.value).isTrue() }
        compose.onNodeWithContentDescription("Shuffle library").performClick()
        compose.runOnIdle { assertThat(plays).isEqualTo(1) }
        compose.onNodeWithContentDescription("Shuffle library").performTouchInput {
            down(center); advanceEventTime(1100); up()
        }
        compose.onNodeWithContentDescription("Play library").assertIsDisplayed()
    }
}
