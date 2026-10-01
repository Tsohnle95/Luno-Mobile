package com.luno.mobile.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.luno.mobile.data.db.AppDatabase
import com.luno.mobile.data.db.entity.Playlist
import com.luno.mobile.data.db.entity.PlaylistTrack
import com.luno.mobile.data.db.entity.Track
import com.google.common.truth.Truth.assertThat
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class LibraryTransferRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: LibraryTransferRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = LibraryTransferRepository(
            database,
            DownloadRepository(database.downloadJobDao(), context, database.playlistDao())
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun fullExportPreservesPlaylistRelationOrderAndUnassignedTracks() = runBlocking<Unit> {
        val first = Track(uri = "file:///first.mp3", title = "First", artist = "Artist")
        val second = Track(uri = "file:///second.mp3", title = "Second", artist = "Artist")
        val unassigned = Track(uri = "file:///unassigned.mp3", title = "Loose", artist = "Artist")
        database.trackDao().insertTracks(listOf(first, second, unassigned))
        val playlistId = database.playlistDao().insertPlaylist(Playlist(name = "Favorites"))
        database.playlistDao().addTrackToPlaylist(PlaylistTrack(playlistId, second.uri, 10))
        database.playlistDao().addTrackToPlaylist(PlaylistTrack(playlistId, first.uri, 2))

        val manifest = repository.buildFullLibraryManifest()
        val playlist = manifest.playlists.single()
        val titleByRef = manifest.tracks.associateBy { it.ref }.mapValues { it.value.title }

        assertThat(playlist.trackRefs.map { titleByRef[it] }).containsExactly("First", "Second").inOrder()
        assertThat(manifest.unassignedTrackRefs.map { titleByRef[it] }).containsExactly("Loose")
    }

    @Test
    fun selectedExportDoesNotExposeLocalUri() = runBlocking<Unit> {
        val track = Track(uri = "content://private/path/song.mp3", title = "Song", artist = "Artist")
        database.trackDao().insertTrack(track)

        val json = repository.encode(repository.buildSelectedTracksManifest(listOf(track.uri)))

        assertThat(json).doesNotContain(track.uri)
        assertThat(json).contains("selected_tracks")
    }

    @Test
    fun favoritesBackupRestoresUniqueMetadataMatchWithoutExposingUri() = runBlocking<Unit> {
        val original = Track(
            uri = "content://private/original/song.mp3",
            title = "Favorite Song",
            artist = "Favorite Artist",
            album = "Favorite Album",
            durationMs = 212_000L,
            isFavorite = true
        )
        database.trackDao().insertTrack(original)

        val json = repository.encodeFavoritesMetadataBackup(
            repository.buildFavoritesMetadataBackup()
        )
        assertThat(json).doesNotContain(original.uri)
        assertThat(json).contains("Favorite Song")

        database.trackDao().deleteTrack(original.uri)
        val rescanned = original.copy(
            uri = "content://media/external/audio/media/42",
            isFavorite = false
        )
        database.trackDao().insertTrack(rescanned)

        val result = repository.restoreFavoritesMetadataBackup(json)

        assertThat(result.restoredCount).isEqualTo(1)
        assertThat(result.ambiguousCount).isEqualTo(0)
        assertThat(result.missingCount).isEqualTo(0)
        assertThat(database.trackDao().getTrack(rescanned.uri)!!.isFavorite).isTrue()
    }

    @Test
    fun favoritesBackupLeavesAmbiguousMetadataMatchesUntouched() = runBlocking<Unit> {
        val original = Track(
            uri = "content://private/original/song.mp3",
            title = "Same Song",
            artist = "Same Artist",
            album = "Same Album",
            durationMs = 180_000L,
            isFavorite = true
        )
        database.trackDao().insertTrack(original)
        val json = repository.encodeFavoritesMetadataBackup(
            repository.buildFavoritesMetadataBackup()
        )
        database.trackDao().deleteTrack(original.uri)
        val possibleMatches = listOf(
            original.copy(uri = "content://media/one", isFavorite = false),
            original.copy(uri = "content://media/two", isFavorite = false)
        )
        database.trackDao().insertTracks(possibleMatches)

        val result = repository.restoreFavoritesMetadataBackup(json)

        assertThat(result.restoredCount).isEqualTo(0)
        assertThat(result.ambiguousCount).isEqualTo(1)
        assertThat(possibleMatches.map { database.trackDao().getTrack(it.uri)!!.isFavorite })
            .containsExactly(false, false)
    }
}
