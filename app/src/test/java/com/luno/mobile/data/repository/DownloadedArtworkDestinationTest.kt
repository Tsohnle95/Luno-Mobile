package com.luno.mobile.data.repository

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
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
import com.luno.mobile.playback.DownloadWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Regression coverage for the reported camera-roll pollution: playlist
 * downloads kept copying their cached thumbnail JPEG into the selected SAF
 * tree. Only playable audio may cross that boundary.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class DownloadedArtworkDestinationTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: DownloadRepository
    private val treeUri = Uri.parse("content://${FakeMusicFolderProvider.AUTHORITY}/tree/music")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FakeMusicFolderProvider.reset()
        Robolectric.setupContentProvider(
            FakeMusicFolderProvider::class.java,
            FakeMusicFolderProvider.AUTHORITY
        )
        val musicFolder = MusicFolderRepository(
            context,
            permissionPersister = { _, _ -> },
            permissionChecker = { true }
        )
        musicFolder.saveTreeUri(treeUri)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = DownloadRepository(
            downloadJobDao = database.downloadJobDao(),
            context = context,
            playlistDao = database.playlistDao(),
            trackDao = database.trackDao(),
            database = database,
            musicFolderRepository = musicFolder
        )
    }

    @After
    fun tearDown() {
        database.close()
        File(context.filesDir, DownloadWorker.KEY_DOWNLOAD_DIR).deleteRecursively()
        File(context.cacheDir, DownloadRepository.PREVIEW_DOWNLOAD_DIR).deleteRecursively()
        context.getSharedPreferences("music_folder", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun promotePreview_writesOnlyAudioToSelectedFolder(): Unit = runBlocking {
        val playlistId = database.playlistDao().insertPlaylist(Playlist(name = "Saved"))
        val preview = File(
            context.cacheDir,
            "${DownloadRepository.PREVIEW_DOWNLOAD_DIR}/preview.m4a"
        ).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val artwork = validArtwork("preview-cover.png")

        val track = repository.promotePreview(
            previewFile = preview,
            sourceUrl = "https://media.example/song",
            title = "Song",
            artist = "Artist",
            playlistId = playlistId,
            artworkPath = artwork.absolutePath
        )

        val names = FakeMusicFolderProvider.documentNames()
        assertThat(names.filter { it.endsWith(".jpg", ignoreCase = true) }).isEmpty()
        assertThat(names.filter { it.endsWith(".m4a", ignoreCase = true) }).hasSize(1)
        assertThat(track.albumArtPath).isEqualTo(artwork.absolutePath)
    }

    @Test
    fun syncAppSongsToMusicFolder_writesOnlyAudioAndKeepsPrivateArtwork(): Unit = runBlocking {
        val playlistId = database.playlistDao().insertPlaylist(Playlist(name = "Downloaded"))
        val downloadRoot = File(context.filesDir, DownloadWorker.KEY_DOWNLOAD_DIR).apply {
            mkdirs()
        }
        val source = File(downloadRoot, "Artist - Song.m4a").apply {
            writeBytes(byteArrayOf(5, 6, 7, 8))
        }
        val artwork = validArtwork("downloaded-cover.png")
        val track = Track(
            uri = source.toURI().toString(),
            title = "Song",
            artist = "Artist",
            albumArtPath = artwork.absolutePath
        )
        database.trackDao().insertTrack(track)
        database.playlistDao().addTrackToPlaylist(
            PlaylistTrack(playlistId, track.uri, 0)
        )
        database.downloadJobDao().insertDownload(
            DownloadJob(
                sourceUrl = "https://media.example/song",
                title = "Song",
                state = DownloadState.COMPLETED,
                localUri = track.uri,
                videoId = "video-1"
            )
        )

        val result = repository.syncAppSongsToMusicFolder()

        assertThat(result.synced).isEqualTo(1)
        val names = FakeMusicFolderProvider.documentNames()
        assertThat(names.filter { it.endsWith(".jpg", ignoreCase = true) }).isEmpty()
        assertThat(names.filter { it.endsWith(".m4a", ignoreCase = true) }).hasSize(1)
        val synced = database.trackDao().getAllTracksOnce().single()
        assertThat(synced.albumArtPath).isEqualTo(artwork.absolutePath)
        assertThat(database.playlistDao().getPlaylistWithTracks(playlistId)!!.tracks)
            .hasSize(1)
    }

    private fun validArtwork(name: String): File {
        val file = File(context.filesDir, "artwork/$name").apply {
            parentFile?.mkdirs()
        }
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()
        return file
    }

    class FakeMusicFolderProvider : ContentProvider() {

        private data class FakeDocument(
            val id: String,
            val parentId: String?,
            val name: String,
            val mimeType: String,
            val file: File
        )

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val id = documentIdOf(uri)
            val isChildren = uri.pathSegments.orEmpty().lastOrNull() == "children"
            if (isChildren) {
                val rows = documents.values
                    .filter { it.parentId == id }
                    .map { rowOf(it) }
                return cursor(projection, rows)
            }
            val document = documents[id] ?: return cursor(projection, emptyList())
            return cursor(projection, listOf(rowOf(document)))
        }

        override fun getType(uri: Uri): String =
            documents[documentIdOf(uri)]?.mimeType ?: "application/octet-stream"

        @Suppress("DEPRECATION")
        override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
            return when (method) {
                "android:createDocument" -> {
                    val parentUri = extras?.getParcelable("uri") as? Uri ?: return null
                    val name = extras.getString("_display_name") ?: return null
                    val mimeType = extras.getString("mime_type") ?: "application/octet-stream"
                    val id = "doc-${nextId++}"
                    val file = File(requireNotNull(context).cacheDir, "saf-$id").apply {
                        writeBytes(ByteArray(0))
                    }
                    documents[id] = FakeDocument(
                        id = id,
                        parentId = documentIdOf(parentUri),
                        name = name,
                        mimeType = mimeType,
                        file = file
                    )
                    android.os.Bundle().apply {
                        putParcelable(
                            "uri",
                            DocumentsContract.buildDocumentUri(AUTHORITY, id)
                        )
                    }
                }
                "android:deleteDocument" -> {
                    val documentUri = extras?.getParcelable("uri") as? Uri
                    val document = documentUri?.let { documents.remove(documentIdOf(it)) }
                    document?.file?.delete()
                    android.os.Bundle().apply { putBoolean("result", document != null) }
                }
                else -> super.call(method, arg, extras)
            }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val document = documents[documentIdOf(uri)]
                ?: throw FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(
                document.file,
                ParcelFileDescriptor.parseMode(mode)
            )
        }

        private fun documentIdOf(uri: Uri): String {
            val segments = uri.pathSegments.orEmpty()
            return if (segments.size >= 4 && segments[0] == "tree") {
                segments[3]
            } else {
                segments.getOrNull(1).orEmpty()
            }
        }

        private fun rowOf(document: FakeDocument): Map<String, Any?> = mapOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to document.id,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to document.name,
            DocumentsContract.Document.COLUMN_MIME_TYPE to document.mimeType,
            DocumentsContract.Document.COLUMN_SIZE to document.file.length()
        )

        private fun cursor(
            projection: Array<out String>?,
            rows: List<Map<String, Any?>>
        ): Cursor {
            val columns = projection ?: arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )
            val cursor = MatrixCursor(columns)
            rows.forEach { row -> cursor.addRow(columns.map { row[it] }) }
            return cursor
        }

        companion object {
            const val AUTHORITY = "com.luno.test.musicfolder.documents"
            private const val ROOT_ID = "music"
            private const val DIR = DocumentsContract.Document.MIME_TYPE_DIR

            private val documents = linkedMapOf<String, FakeDocument>()
            private var nextId = 1

            fun reset() {
                documents.clear()
                documents[ROOT_ID] = FakeDocument(
                    id = ROOT_ID,
                    parentId = null,
                    name = "Music",
                    mimeType = DIR,
                    file = File("/")
                )
                nextId = 1
            }

            fun documentNames(): List<String> =
                documents.values.filter { it.id != ROOT_ID }.map { it.name }
        }
    }
}
