package com.luno.mobile.ui.components

import com.google.common.truth.Truth.assertThat
import com.luno.mobile.data.repository.PlaylistAddSummary
import org.junit.Test

class PlaylistAddMessageTest {

    @Test
    fun alreadyPresentSingleSongIsReportedExplicitly() {
        assertThat(
            playlistAddMessage("Reggae", PlaylistAddSummary(added = 0, alreadyPresent = 1))
        ).isEqualTo("Already in Reggae")
    }

    @Test
    fun mixedBatchReportsAddedAndAlreadyThere() {
        assertThat(
            playlistAddMessage("Reggae", PlaylistAddSummary(added = 2, alreadyPresent = 3))
        ).isEqualTo("Added 2 to Reggae; 3 already there")
    }

    @Test
    fun newlyCreatedPlaylistReportsRealSongCount() {
        assertThat(
            playlistCreatedMessage("Reggae", PlaylistAddSummary(added = 2, alreadyPresent = 0))
        ).isEqualTo("Created Reggae with 2 songs")
        assertThat(
            playlistCreatedMessage("Reggae", PlaylistAddSummary(added = 0, alreadyPresent = 2))
        ).isEqualTo("Created Reggae; 2 already there")
    }
}
