package com.luno.mobile.ui.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LibrarySearchTest {

    @Test
    fun trailingSpaceStillMatchesTheSong() {
        assertThat(matchesSearchQuery("Swimming ", "Swimming")).isTrue()
    }

    @Test
    fun leadingSpaceStillMatchesTheSong() {
        assertThat(matchesSearchQuery("  Swimming", "Swimming")).isTrue()
    }

    @Test
    fun whitespaceOnlyMatchesEverything() {
        assertThat(matchesSearchQuery("   ", "Swimming")).isTrue()
    }

    @Test
    fun surroundingWhitespaceStillMatchesArtistOrAlbum() {
        assertThat(matchesSearchQuery(" mac miller ", "Swimming", "Mac Miller", "Swimming"))
            .isTrue()
    }

    @Test
    fun aDifferentTitleDoesNotMatch() {
        assertThat(matchesSearchQuery("Swimming ", "Circles")).isFalse()
    }

    @Test
    fun matchingIsStillCaseInsensitive() {
        assertThat(matchesSearchQuery(" swimming ", "SWIMMING")).isTrue()
    }
}
