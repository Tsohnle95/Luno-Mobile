package com.luno.mobile.ui.player

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class FullPlayerRecommendationStatusTest {

    @Test
    fun transportIconsUseSessionPlayIntent() {
        val fullPlayer = File("src/main/java/com/luno/mobile/ui/player/FullPlayerScreen.kt")
            .readText()
        val miniPlayer = File("src/main/java/com/luno/mobile/ui/components/MiniPlayer.kt")
            .readText()

        assertThat(fullPlayer)
            .contains("musicController.playWhenReady.collectAsState()")
        assertThat(fullPlayer).contains("imageVector = if (playWhenReady)")
        assertThat(miniPlayer)
            .contains("musicController.playWhenReady.collectAsState()")
        assertThat(miniPlayer)
            .contains("if (playWhenReady) Icons.Default.Pause else Icons.Default.PlayArrow")
    }

    @Test
    fun readyRecommendationsKeepTheirArtistAndSeparateReadyIndicator() {
        val source = File("src/main/java/com/luno/mobile/ui/player/FullPlayerScreen.kt")
            .readText()
        val start = source.indexOf("val statusText = when")
        val end = source.indexOf("val statusIsError", start)
        assertThat(start).isAtLeast(0)
        assertThat(end).isGreaterThan(start)

        val statusBlock = source.substring(start, end)
        assertThat(statusBlock).doesNotContain("Ready in temporary queue")
        val readyBranch = Regex(
            """ready\s*->\s*(.+?)(?=\n\s*else\s*->)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(statusBlock)
        if (readyBranch != null) {
            assertThat(readyBranch.groupValues[1]).contains("recommendation.artist")
        }
        assertThat(statusBlock).contains("recommendation.artist")
        assertThat(source).contains("ready && !permanent -> Icon(")
        assertThat(source).contains("contentDescription = \"Ready to play\"")
    }
}
