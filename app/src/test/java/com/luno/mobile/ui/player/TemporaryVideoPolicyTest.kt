package com.luno.mobile.ui.player

import com.google.common.truth.Truth.assertThat
import com.luno.mobile.playback.MediaTrack
import com.luno.mobile.playback.WebSearchResult
import com.luno.mobile.playback.rankVideoCandidates
import org.junit.Test

class TemporaryVideoPolicyTest {
    @Test fun videoFollowsAudioSeekAndOnlyCorrectsMaterialClockDrift() {
        assertThat(videoSeekTarget(1000, 1200)).isNull()
        assertThat(videoSeekTarget(1000, 1500)).isEqualTo(1000L)
        assertThat(videoSeekTarget(30_000, 1000)).isEqualTo(30_000L)
        assertThat(videoSeekTarget(-5, 500)).isEqualTo(0L)
    }

    @Test fun oversizedPreferredVideoCanFallBackToSmallerStreamsOfTheSameSong() {
        fun stream(height: Int) = org.schabi.newpipe.extractor.stream.VideoStream.Builder()
            .setId("$height").setContent("https://video.test/$height", true)
            .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.MPEG_4)
            .setIsVideoOnly(false).setResolution("${height}p").build()
        val choices = com.luno.mobile.playback.videoStreamChoices("song", listOf(
            stream(1080), stream(360), stream(144), stream(240)))
        assertThat(choices.map { it.height }).containsExactly(360, 240, 144).inOrder()
        assertThat(choices.map { it.videoId }.toSet()).containsExactly("song")
    }

    @Test fun upgradePrefers1080Then720AndNeverDownloadsBeyond1080OrAnotherFormat() {
        fun stream(height: Int, format: org.schabi.newpipe.extractor.MediaFormat = org.schabi.newpipe.extractor.MediaFormat.MPEG_4) =
            org.schabi.newpipe.extractor.stream.VideoStream.Builder().setId("$height-$format")
                .setContent("https://video.test/$height-$format", true).setMediaFormat(format)
                .setIsVideoOnly(true).setResolution("${height}p").build()
        val choices = com.luno.mobile.playback.videoUpgradeChoices("same-song", listOf(
            stream(360), stream(2160), stream(720), stream(1080), stream(1080, org.schabi.newpipe.extractor.MediaFormat.WEBM)))
        assertThat(choices.map { it.height }).containsExactly(1080, 720).inOrder()
        assertThat(choices.map { it.videoId }.toSet()).containsExactly("same-song")
    }

    @Test fun sourceRelevanceNormalizesAccentsAndRejectsDifferentDurationAndUnrelatedTitles() {
        val track = MediaTrack("one", "De\u0301ja\u0300 Vu - Album Name", "Beyonce\u0301", durationMs = 180_000)
        fun item(id: String, title: String, artist: String, duration: Long) =
            WebSearchResult(title, artist, duration, "", id, "YouTube")
        val exact = item("exact", "Beyoncé - Déjà Vu (Official Video)", "Beyoncé", 182)
        val wrongDuration = item("live", "Déjà Vu live concert", "Beyoncé", 1200)
        val unrelated = item("unrelated", "Other Song", "Beyoncé", 180)
        assertThat(rankVideoCandidates(track, listOf(unrelated, wrongDuration, exact, exact))).containsExactly(exact)
    }
}
