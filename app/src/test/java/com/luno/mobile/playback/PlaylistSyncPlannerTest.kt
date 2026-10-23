package com.luno.mobile.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.luno.mobile.data.db.AppDatabase
import com.luno.mobile.data.db.entity.DownloadJob
import com.luno.mobile.data.db.entity.DownloadState
import com.luno.mobile.data.db.entity.Playlist
import com.luno.mobile.data.db.entity.PlaylistTrack
import com.luno.mobile.data.db.entity.Track
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class PlaylistSyncPlannerTest {

    private lateinit var database: AppDatabase
    private lateinit var planner: PlaylistSyncPlanner

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        planner = PlaylistSyncPlanner(
            trackDao = database.trackDao(),
            playlistDao = database.playlistDao(),
            jobDao = database.downloadJobDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun plan_reusesTrackStoredByVideoIdWithoutQueueingAgain(): Unit = runBlocking {
        val playlistId = newPlaylist("Dubstep")
        val track = Track(
            uri = "file:///music/song.m4a",
            title = "Song",
            artist = "Artist",
            videoId = VIDEO_ID
        )
        database.trackDao().insertTrack(track)

        val plan = planner.plan(playlistId, listOf(video(VIDEO_ID)))

        assertThat(plan.videosToDownload).isEmpty()
        assertThat(plan.reusedTracks).isEqualTo(1)
        assertThat(memberships(playlistId)).containsExactly(track.uri)
    }

    @Test
    fun plan_reusesCompletedJobTrackAndBackfillsVideoId(): Unit = runBlocking {
        val playlistId = newPlaylist("Dubstep")
        val track = Track(uri = "file:///music/downloaded.m4a", title = "Downloaded")
        database.trackDao().insertTrack(track)
        database.downloadJobDao().insertDownload(
            DownloadJob(
                sourceUrl = "https://media.example/audio",
                title = "Downloaded",
                state = DownloadState.COMPLETED,
                localUri = track.uri,
                videoId = VIDEO_ID
            )
        )

        val plan = planner.plan(playlistId, listOf(video(VIDEO_ID)))

        assertThat(plan.videosToDownload).isEmpty()
        assertThat(plan.reusedTracks).isEqualTo(1)
        assertThat(database.trackDao().getTrack(track.uri)!!.videoId).isEqualTo(VIDEO_ID)
        assertThat(memberships(playlistId)).containsExactly(track.uri)
    }

    @Test
    fun plan_skipsVideoWithActiveDownload() = runBlocking {
        val playlistId = newPlaylist("Dubstep")
        database.downloadJobDao().insertDownload(
            DownloadJob(
                sourceUrl = "https://media.example/audio",
                title = "Downloading",
                state = DownloadState.QUEUED,
                videoId = VIDEO_ID
            )
        )

        val plan = planner.plan(playlistId, listOf(video(VIDEO_ID)))

        assertThat(plan.videosToDownload).isEmpty()
        assertThat(plan.alreadyQueued).isEqualTo(1)
        assertThat(memberships(playlistId)).isEmpty()
    }

    @Test
    fun plan_requestsOneDownloadForRepeatedPlaylistEntries() = runBlocking {
        val playlistId = newPlaylist("Dubstep")

        val plan = planner.plan(
            playlistId,
            listOf(video("video-a"), video("video-a"), video("video-b"))
        )

        assertThat(plan.videosToDownload.map { it.videoId })
            .containsExactly("video-a", "video-b")
            .inOrder()
    }

    @Test
    fun plan_reusesLegacyTrackByNormalizedArtistAndTitle(): Unit = runBlocking {
        val playlistId = newPlaylist("Dubstep")
        val legacy = Track(
            uri = "file:///music/legacy-same.m4a",
            title = "Song (Official Video)",
            artist = "Uploader",
            durationMs = 180_000L
        )
        database.trackDao().insertTrack(legacy)

        val plan = planner.plan(
            playlistId,
            listOf(video(VIDEO_ID, title = "Song (Official Video)", artist = "Uploader"))
        )

        assertThat(plan.videosToDownload).isEmpty()
        assertThat(plan.reusedTracks).isEqualTo(1)
        assertThat(memberships(playlistId)).containsExactly(legacy.uri)
        assertThat(database.trackDao().getTrack(legacy.uri)!!.videoId).isEqualTo(VIDEO_ID)
    }

    @Test
    fun plan_reusesLegacyTrackByTitleAndDurationWhenArtistWasLost(): Unit = runBlocking {
        val playlistId = newPlaylist("Dubstep")
        val legacy = Track(
            uri = "file:///music/legacy-unknown.m4a",
            title = "- Radioactive [DUBSTEP REMIX]",
            artist = "Unknown Artist",
            durationMs = 188_000L
        )
        database.trackDao().insertTrack(legacy)

        val plan = planner.plan(
            playlistId,
            listOf(
                video(
                    VIDEO_ID,
                    title = "Radioactive [DUBSTEP REMIX]",
                    artist = "PrimeElectr0",
                    duration = 188L
                )
            )
        )

        assertThat(plan.videosToDownload).isEmpty()
        assertThat(memberships(playlistId)).containsExactly(legacy.uri)
    }

    @Test
    fun plan_doesNotReuseSameTitleWithDifferentDuration(): Unit = runBlocking {
        val playlistId = newPlaylist("Dubstep")
        database.trackDao().insertTrack(
            Track(
                uri = "file:///music/wrong-duration.m4a",
                title = "Song",
                artist = "Unknown Artist",
                durationMs = 100_000L
            )
        )

        val plan = planner.plan(
            playlistId,
            listOf(video(VIDEO_ID, title = "Song", duration = 180L))
        )

        assertThat(plan.videosToDownload).hasSize(1)
        assertThat(memberships(playlistId)).isEmpty()
    }

    @Test
    fun plan_doesNotJoinUnrelatedSongsByContainingTitle() = runBlocking {
        val playlistId = newPlaylist("Dubstep")
        database.trackDao().insertTrack(
            Track(uri = "file:///music/mix.m4a", title = "Dubstep Mix", artist = "DJ")
        )

        val plan = planner.plan(
            playlistId,
            listOf(video(VIDEO_ID, title = "Dubstep"))
        )

        assertThat(plan.videosToDownload).hasSize(1)
        assertThat(memberships(playlistId)).isEmpty()
    }

    @Test
    fun repairDuplicateDownloads_mergesSameVideoAndPreservesStateAndMemberships() = runBlocking {
        val dubstep = newPlaylist("Dubstep")
        val favourites = newPlaylist("Favourites")
        val canonical = Track(
            uri = "file:///music/old.m4a",
            title = "Song",
            artist = "Artist",
            addedAt = 10L
        )
        val duplicate = Track(
            uri = "file:///music/new.m4a",
            title = "Song",
            artist = "Artist",
            albumArtPath = "/artwork/cover.jpg",
            playCount = 4,
            isFavorite = true,
            addedAt = 20L
        )
        database.trackDao().insertTracks(listOf(canonical, duplicate))
        database.playlistDao().addTrackToPlaylist(PlaylistTrack(dubstep, canonical.uri, 0))
        database.playlistDao().addTrackToPlaylist(PlaylistTrack(dubstep, duplicate.uri, 1))
        database.playlistDao().addTrackToPlaylist(PlaylistTrack(favourites, duplicate.uri, 0))
        database.downloadJobDao().insertDownload(
            DownloadJob(
                sourceUrl = "https://media.example/audio-old",
                title = "Song",
                state = DownloadState.COMPLETED,
                localUri = canonical.uri,
                videoId = VIDEO_ID
            )
        )
        val completed = database.downloadJobDao().insertDownload(
            DownloadJob(
                sourceUrl = "https://media.example/audio-new",
                title = "Song",
                state = DownloadState.COMPLETED,
                localUri = duplicate.uri,
                videoId = VIDEO_ID
            )
        )

        val merged = planner.repairDuplicateDownloads(dubstep)

        assertThat(merged).isEqualTo(1)
        assertThat(database.trackDao().getAllTracksOnce().map { it.uri })
            .containsExactly(canonical.uri)
        val saved = database.trackDao().getTrack(canonical.uri)!!
        assertThat(saved.videoId).isEqualTo(VIDEO_ID)
        assertThat(saved.isFavorite).isTrue()
        assertThat(saved.playCount).isEqualTo(4)
        assertThat(saved.albumArtPath).isEqualTo("/artwork/cover.jpg")
        assertThat(memberships(dubstep)).containsExactly(canonical.uri)
        assertThat(memberships(favourites)).containsExactly(canonical.uri)
        assertThat(database.downloadJobDao().getDownload(completed)!!.localUri)
            .isEqualTo(canonical.uri)
    }

    @Test
    fun repairDuplicateDownloads_mergesLegacyRowIntoProvenRowByNormalizedMetadata(): Unit =
        runBlocking {
            val playlistId = newPlaylist("Dubstep")
            val legacy = Track(
                uri = "file:///music/legacy.m4a",
                title = "Sweep (Nxou Remix) [Free Download]",
                artist = "Blue Foundation",
                addedAt = 10L
            )
            val proven = Track(
                uri = "file:///music/proven.m4a",
                title = "Sweep (Nxou Remix) [Free Download]",
                artist = "Blue Foundation",
                addedAt = 20L
            )
            database.trackDao().insertTracks(listOf(legacy, proven))
            database.playlistDao().addTrackToPlaylist(PlaylistTrack(playlistId, legacy.uri, 0))
            database.playlistDao().addTrackToPlaylist(PlaylistTrack(playlistId, proven.uri, 1))
            database.downloadJobDao().insertDownload(
                DownloadJob(
                    sourceUrl = "https://media.example/audio",
                    title = proven.title,
                    state = DownloadState.COMPLETED,
                    localUri = proven.uri,
                    videoId = VIDEO_ID
                )
            )

            val merged = planner.repairDuplicateDownloads(playlistId)

            assertThat(merged).isEqualTo(1)
            assertThat(database.trackDao().getAllTracksOnce().map { it.uri })
                .containsExactly(proven.uri)
            assertThat(memberships(playlistId)).containsExactly(proven.uri)
        }

    @Test
    fun repairDuplicateDownloads_mergesLegacyUnknownArtistByTitleAndDuration(): Unit =
        runBlocking {
            val playlistId = newPlaylist("Dubstep")
            val legacy = Track(
                uri = "file:///music/legacy-unknown.m4a",
                title = "- Radioactive [DUBSTEP REMIX]",
                artist = "Unknown Artist",
                durationMs = 188_000L,
                addedAt = 10L
            )
            val proven = Track(
                uri = "file:///music/proven-known.m4a",
                title = "Radioactive [DUBSTEP REMIX]",
                artist = "PrimeElectr0",
                durationMs = 188_000L,
                addedAt = 20L
            )
            database.trackDao().insertTracks(listOf(legacy, proven))
            database.playlistDao().addTrackToPlaylist(PlaylistTrack(playlistId, legacy.uri, 0))
            database.playlistDao().addTrackToPlaylist(PlaylistTrack(playlistId, proven.uri, 1))
            database.downloadJobDao().insertDownload(
                DownloadJob(
                    sourceUrl = "https://media.example/audio",
                    title = proven.title,
                    state = DownloadState.COMPLETED,
                    localUri = proven.uri,
                    videoId = VIDEO_ID
                )
            )

            val merged = planner.repairDuplicateDownloads(playlistId)

            assertThat(merged).isEqualTo(1)
            assertThat(database.trackDao().getAllTracksOnce().map { it.uri })
                .containsExactly(proven.uri)
            assertThat(memberships(playlistId)).containsExactly(proven.uri)
        }

    @Test
    fun repairDuplicateDownloads_mergesExactUnprovenDuplicates(): Unit = runBlocking {
        val playlistId = newPlaylist("Dubstep")
        val oldest = Track(
            uri = "file:///music/one.m4a",
            title = "Cracks",
            artist = "UKF Dubstep",
            addedAt = 10L
        )
        val newer = Track(
            uri = "file:///music/two.m4a",
            title = "Cracks",
            artist = "UKF Dubstep",
            addedAt = 20L
        )
        database.trackDao().insertTracks(listOf(oldest, newer))
        database.playlistDao().addTrackToPlaylist(PlaylistTrack(playlistId, oldest.uri, 0))
        database.playlistDao().addTrackToPlaylist(PlaylistTrack(playlistId, newer.uri, 1))

        val merged = planner.repairDuplicateDownloads(playlistId)

        assertThat(merged).isEqualTo(1)
        assertThat(database.trackDao().getAllTracksOnce().map { it.uri })
            .containsExactly(oldest.uri)
        assertThat(memberships(playlistId)).containsExactly(oldest.uri)
    }

    @Test
    fun repairDuplicateDownloads_leavesAmbiguousUnprovenVersionsAlone(): Unit = runBlocking {
        val playlistId = newPlaylist("Dubstep")
        val first = Track(uri = "file:///music/one.m4a", title = "Same", artist = "Artist")
        val second = Track(
            uri = "file:///music/two.m4a",
            title = "Same (Live)",
            artist = "Artist"
        )
        database.trackDao().insertTracks(listOf(first, second))
        database.playlistDao().addTrackToPlaylist(PlaylistTrack(playlistId, first.uri, 0))
        database.playlistDao().addTrackToPlaylist(PlaylistTrack(playlistId, second.uri, 1))

        val merged = planner.repairDuplicateDownloads(playlistId)

        assertThat(merged).isEqualTo(0)
        assertThat(database.trackDao().getAllTracksOnce()).hasSize(2)
        assertThat(memberships(playlistId)).containsExactly(first.uri, second.uri)
    }

    private suspend fun newPlaylist(name: String): Long =
        database.playlistDao().insertPlaylist(Playlist(name = name))

    private suspend fun memberships(playlistId: Long): List<String> =
        database.playlistDao().getPlaylistWithTracks(playlistId)!!.tracks.map { it.uri }

    private fun video(
        videoId: String,
        title: String = "Song",
        artist: String = "Artist",
        duration: Long = 180L
    ) = PlaylistVideo(
        videoId = videoId,
        title = title,
        artist = artist,
        duration = duration
    )

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
    }
}
