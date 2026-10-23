package com.luno.mobile.data.repository

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.luno.mobile.data.db.AppDatabase
import com.luno.mobile.data.db.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class MetadataRecoveryTest {
    private lateinit var db: AppDatabase
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        Robolectric.setupContentProvider(Provider::class.java, "metadata.test")
    }
    @After fun close() = db.close()
    private fun uri(name: String) = Uri.parse("content://metadata.test/document/" + Uri.encode("root/$name"))
    private fun repository(timeoutMs: Long = 2_000, probe: (Uri) -> AudioMetadata) = LibraryRepository(
        context, db.trackDao(), db.playlistDao(),
        LibraryRepository.UriPermissionPersister { },
        downloadJobDao = db.downloadJobDao(), metadataProbe = probe, metadataTimeoutMs = timeoutMs
    )

    @Test fun stalledImportRetainsFilenameInsteadOfPublishingUnknownPlaceholders() = runBlocking<Unit> {
        val result = repository(timeoutMs = 20) { Thread.sleep(200); AudioMetadata("Late", "Late") }
            .importAudioUri(uri("Artist - Real Song.MP3"))
        assertThat(result.getOrThrow().title).isEqualTo("Real Song")
        assertThat(result.getOrThrow().artist).isEqualTo("Artist")
    }

    @Test fun sidecarWithAudioExtensionAndAudioMimeIsNotImported() = runBlocking<Unit> {
        val result = repository { error("Must not probe sidecar") }
            .importAudioUri(uri("._Artist - Song.mp3"))
        assertThat(result.isFailure).isTrue()
        assertThat(db.trackDao().trackCount()).isEqualTo(0)
    }

    @Test fun filenameRecoveryPreservesIdentityFavoritesHistoryArtworkAndMembershipAndIsIdempotent() = runBlocking<Unit> {
        val before = Track(uri("Artist - Real Song.mp3").toString(), "Unknown Track", "Unknown Artist",
            album = "Album", albumArtPath = "/old/art", isFavorite = true, playCount = 8, addedAt = 42)
        db.trackDao().insertTrack(before)
        val playlist = db.playlistDao().insertPlaylist(Playlist(name = "Original playlist"))
        db.playlistDao().addTrackToPlaylist(PlaylistTrack(playlist, before.uri, 17))
        val memberships = db.playlistDao().getAllPlaylistTracksOnce()
        val repo = repository { throw FileNotFoundException() }
        val result = repo.repairMissingMetadata()
        assertThat(result.updated).isEqualTo(1)
        assertThat(db.trackDao().getTrack(before.uri)).isEqualTo(before.copy(title = "Real Song", artist = "Artist"))
        assertThat(db.playlistDao().getAllPlaylistTracksOnce()).isEqualTo(memberships)
        val backup = org.json.JSONObject(File(result.backupPath).readText())
        assertThat(backup.getJSONArray("tracks").getJSONObject(0).getString("title")).isEqualTo("Unknown Track")
        assertThat(backup.getJSONArray("memberships").length()).isEqualTo(1)
        assertThat(repo.repairMissingMetadata().updated).isEqualTo(0)
    }

    @Test fun exactDownloadProvenanceWinsButValidUserTitleIsNeverReplaced() = runBlocking<Unit> {
        val before = Track(uri("Filename Artist - Filename Title.mp3").toString(), "My title", "Unknown Artist", durationMs = 10)
        db.trackDao().insertTrack(before)
        db.downloadJobDao().insertDownload(DownloadJob(sourceUrl = "https://youtube.com/watch?v=source", title = "Download title",
            artist = "Saved artist", state = DownloadState.COMPLETED, localUri = before.uri))
        val result = repository { AudioMetadata("Embedded title", "Embedded artist", 999) }.repairMissingMetadata()
        assertThat(result.updated).isEqualTo(1)
        assertThat(db.trackDao().getTrack(before.uri)).isEqualTo(before.copy(artist = "Saved artist"))
    }

    @Test fun userEditsDuringProbeWinAndConcurrentFavoritesArePreserved() = runBlocking<Unit> {
        val before = Track(uri("Artist - Song.mp3").toString(), "Unknown Track", "Unknown Artist")
        db.trackDao().insertTrack(before)
        repository {
            runBlocking { db.trackDao().updateTrack(before.copy(title = "User title", isFavorite = true, playCount = 3)) }
            AudioMetadata("Embedded title", "Embedded artist", 500)
        }.repairMissingMetadata()
        val after = db.trackDao().getTrack(before.uri)!!
        assertThat(after.title).isEqualTo("User title")
        assertThat(after.artist).isEqualTo("Embedded artist")
        assertThat(after.isFavorite).isTrue()
        assertThat(after.playCount).isEqualTo(3)
    }

    @Test fun unresolvedOpaqueUrisAndExistingSidecarsAreLeftUntouched() = runBlocking<Unit> {
        val sidecar = Track(uri("._Artist - Song.mp3").toString(), "Unknown Track", "Unknown Artist")
        val opaque = Track("content://metadata.test/123", "Unknown Track", "Unknown Artist")
        db.trackDao().insertTracks(listOf(sidecar, opaque))
        val result = repository { throw FileNotFoundException() }.repairMissingMetadata()
        assertThat(result.updated).isEqualTo(0)
        assertThat(result.sidecarsSkipped).isEqualTo(1)
        assertThat(result.remainingTitles).isEqualTo(1)
        assertThat(db.trackDao().getAllTracksOnce()).containsExactly(sidecar, opaque)
    }

    @Test fun artworkSweepCannotRevertRepairedNamesOrFavorites() = runBlocking<Unit> {
        val before = Track(uri("Artist - Song.mp3").toString(), "Unknown Track", "Unknown Artist")
        db.trackDao().insertTrack(before)
        val repo = repository { AudioMetadata() }
        repo.fetchMissingArtwork(extract = {
            runBlocking { db.trackDao().updateTrack(before.copy(title = "Recovered", artist = "Recovered artist", isFavorite = true)) }
            "/new/art"
        })
        assertThat(db.trackDao().getTrack(before.uri)).isEqualTo(
            before.copy(title = "Recovered", artist = "Recovered artist", isFavorite = true, albumArtPath = "/new/art"))
    }

    @Test fun embeddedTagsRestoreAnUnseparatedFilenameWithoutGuessingItsArtist() = runBlocking<Unit> {
        val before = Track(uri("no separator.mp3").toString(), "Unknown Track", "Unknown Artist")
        db.trackDao().insertTrack(before)
        repository { AudioMetadata("Tagged title", "Tagged artist", 1000) }.repairMissingMetadata()
        assertThat(db.trackDao().getTrack(before.uri)).isEqualTo(
            before.copy(title = "Tagged title", artist = "Tagged artist", durationMs = 1000))
    }

    @Test fun failedBackupPreventsAnyLibraryChanges() = runBlocking<Unit> {
        val before = Track(uri("Artist - Song.mp3").toString(), "Unknown Track", "Unknown Artist")
        db.trackDao().insertTrack(before)
        val root = java.nio.file.Files.createTempDirectory("repair-backup-test").toFile()
        File(root, "metadata-repairs").writeText("Cannot create a backup directory here")
        val wrapped = object : android.content.ContextWrapper(context) {
            override fun getFilesDir() = root
        }
        var probed = false
        val repo = LibraryRepository(wrapped, db.trackDao(), db.playlistDao(),
            metadataProbe = { probed = true; AudioMetadata("Title", "Artist") })
        assertThat(runCatching { repo.repairMissingMetadata() }.isFailure).isTrue()
        assertThat(probed).isFalse()
        assertThat(db.trackDao().getTrack(before.uri)).isEqualTo(before)
    }

    class Provider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "audio/mpeg"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            return MatrixCursor(columns).apply { addRow(columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> audioFileName(uri)
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> "audio/mpeg"
                    else -> null
                }
            }.toTypedArray()) }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = throw FileNotFoundException()
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }
}
