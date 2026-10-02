package com.luno.mobile.ui.home

import com.google.common.truth.Truth.assertThat
import com.luno.mobile.data.db.dao.PlaylistWithTracks
import com.luno.mobile.data.db.entity.DownloadJob
import com.luno.mobile.data.db.entity.DownloadState
import com.luno.mobile.data.db.entity.Playlist
import com.luno.mobile.data.db.entity.Track
import com.luno.mobile.playback.MediaTrack
import com.luno.mobile.playback.PlaybackSource
import com.luno.mobile.ui.components.formatPlaylistDuration
import org.junit.Test

class HomeScreenTest {
    @Test
    fun recentlyPlayedPlaylists_returnsSixNewestPersistedPlaylistsOnly() {
        val playlists = (1L..8L).map { id ->
            PlaylistWithTracks(
                playlist = Playlist(
                    id = id,
                    name = "Playlist $id",
                    lastPlayedAt = if (id == 8L) 0L else id * 100L
                ),
                tracks = emptyList()
            )
        }

        val result = recentlyPlayedPlaylists(playlists)

        assertThat(result.map { it.playlist.id })
            .containsExactly(7L, 6L, 5L, 4L, 3L, 2L)
            .inOrder()
    }

    @Test
    fun recentlyPlayedPlaylists_breaksEqualTimestampsByNewestPlaylistId() {
        val playlists = listOf(2L, 5L, 3L).map { id ->
            PlaylistWithTracks(
                playlist = Playlist(id = id, name = "Playlist $id", lastPlayedAt = 100L),
                tracks = emptyList()
            )
        }

        assertThat(recentlyPlayedPlaylists(playlists, limit = 2).map { it.playlist.id })
            .containsExactly(5L, 3L)
            .inOrder()
    }

    @Test
    fun recentlyPlayedPlaylists_respectsNonPositiveLimits() {
        val playlist = PlaylistWithTracks(
            playlist = Playlist(id = 1L, name = "Playlist", lastPlayedAt = 1L),
            tracks = emptyList()
        )

        assertThat(recentlyPlayedPlaylists(listOf(playlist), limit = 0)).isEmpty()
    }

    @Test
    fun recentlyPlayedPlaylists_restoresLegacyOrderFromSavedTrackHistory() {
        val playlists = (1L..4L).map { id ->
            PlaylistWithTracks(
                playlist = Playlist(id = id, name = "Playlist $id"),
                tracks = emptyList()
            )
        }
        val history = listOf(3L, 1L, 3L, -2L).mapIndexed { index, playlistId ->
            MediaTrack(
                uri = "track://$index",
                title = "Track $index",
                artist = "Artist",
                playbackSource = PlaybackSource("Playlist", playlistId = playlistId)
            )
        }

        assertThat(recentlyPlayedPlaylists(playlists, history).map { it.playlist.id })
            .containsExactly(3L, 1L)
            .inOrder()
    }

    @Test
    fun recentlyPlayedPlaylists_persistedTimestampsPrecedeLegacyHistory() {
        val playlists = listOf(
            PlaylistWithTracks(
                playlist = Playlist(id = 1L, name = "Old", lastPlayedAt = 0L),
                tracks = emptyList()
            ),
            PlaylistWithTracks(
                playlist = Playlist(id = 2L, name = "New", lastPlayedAt = 500L),
                tracks = emptyList()
            )
        )
        val history = listOf(
            MediaTrack(
                uri = "track://old",
                title = "Old track",
                artist = "Artist",
                playbackSource = PlaybackSource("Playlist", playlistId = 1L)
            )
        )

        assertThat(recentlyPlayedPlaylists(playlists, history).map { it.playlist.id })
            .containsExactly(2L, 1L)
            .inOrder()
    }

    @Test
    fun formatPlaylistDuration_displaysHoursAndRemainingMinutes() {
        assertThat(formatPlaylistDuration(3_725_000L)).isEqualTo("1 hr 2 min")
        assertThat(formatPlaylistDuration(125_000L)).isEqualTo("2 min")
        assertThat(formatPlaylistDuration(-1L)).isEqualTo("0 min")
    }

    @Test
    fun catalogueKeyIgnoresMutableTrackMetadata() {
        val before = listOf(
            Track(uri = "track://one", title = "One"),
            Track(uri = "track://two", title = "Two")
        )
        val after = before.map {
            it.copy(
                title = "Updated ${it.title}",
                playCount = it.playCount + 1,
                albumArtPath = "/art/${it.uri.substringAfterLast('/')}.jpg"
            )
        }

        assertThat(homeCatalogueKey(after))
            .containsExactlyElementsIn(homeCatalogueKey(before))
            .inOrder()
    }

    @Test
    fun catalogueKeyChangesWhenLibraryMembershipChanges() {
        val before = listOf(Track(uri = "track://one", title = "One"))
        val after = before + Track(uri = "track://two", title = "Two")

        assertThat(homeCatalogueKey(after))
            .containsExactly("track://one", "track://two")
            .inOrder()
    }

    @Test
    fun catalogueKeyIgnoresRoomOrderingChanges() {
        val firstOrder = listOf(
            Track(uri = "track://two", title = "Two"),
            Track(uri = "track://one", title = "One")
        )
        val secondOrder = firstOrder.asReversed()

        assertThat(homeCatalogueKey(firstOrder))
            .containsExactlyElementsIn(homeCatalogueKey(secondOrder))
            .inOrder()
    }

    @Test
    fun recentlyDownloadedTracks_returnsNewestCompletedLibraryTracksAndCapsAt50() {
        val tracks = (1..51).map { index ->
            Track(uri = "track://$index", title = "Track $index")
        }
        val jobs = tracks.mapIndexed { index, track ->
            DownloadJob(
                id = (index + 1).toLong(),
                sourceUrl = "https://example.com/${index + 1}",
                title = track.title,
                state = DownloadState.COMPLETED,
                localUri = track.uri,
                addedAt = index.toLong(),
                completedAt = index.toLong()
            )
        }

        val result = recentlyDownloadedTracks(
            downloads = jobs,
            tracksByUri = tracks.associateBy { it.uri }
        )

        assertThat(result).hasSize(50)
        assertThat(result.first().uri).isEqualTo("track://51")
        assertThat(result.last().uri).isEqualTo("track://2")
        assertThat(result).doesNotContain(tracks.first())
    }

    @Test
    fun recentlyDownloadedTracks_ignoresIncompleteAndMissingLibraryJobs() {
        val track = Track(uri = "track://completed", title = "Completed")
        val jobs = listOf(
            DownloadJob(
                id = 1,
                sourceUrl = "completed",
                title = "Completed",
                state = DownloadState.COMPLETED,
                localUri = track.uri,
                completedAt = 3
            ),
            DownloadJob(
                id = 2,
                sourceUrl = "queued",
                title = "Queued",
                state = DownloadState.QUEUED,
                localUri = "track://queued",
                completedAt = 4
            ),
            DownloadJob(
                id = 3,
                sourceUrl = "missing",
                title = "Missing",
                state = DownloadState.COMPLETED,
                localUri = "track://missing",
                completedAt = 5
            )
        )

        assertThat(recentlyDownloadedTracks(jobs, mapOf(track.uri to track)))
            .containsExactly(track)
    }
}
