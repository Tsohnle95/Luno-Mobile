package com.luno.mobile.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class AppDatabaseMigrationTest {
    @Test
    fun migration10To11_preservesPlaylistMembershipAndFavorites(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseFile = context.getDatabasePath(TEST_DATABASE)
        databaseFile.parentFile?.mkdirs()
        context.deleteDatabase(TEST_DATABASE)

        createVersion10Database(databaseFile)

        val upgraded = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            TEST_DATABASE
        ).addMigrations(AppDatabase.MIGRATION_10_11).build()
        try {
            upgraded.openHelper.writableDatabase

            val playlist = upgraded.playlistDao().getPlaylist(7L)
            assertThat(playlist?.playCount).isEqualTo(3)
            assertThat(playlist?.lastPlayedAt).isEqualTo(0L)

            val savedTrack = upgraded.trackDao().getTrack(FAVORITE_URI)
            assertThat(savedTrack?.isFavorite).isTrue()
            val membership = upgraded.playlistDao().getPlaylistWithTracks(7L)
            assertThat(membership?.tracks?.map { it.uri }).containsExactly(FAVORITE_URI)
            assertThat(membership?.let { upgraded.playlistDao().trackCount(it.playlist.id) })
                .isEqualTo(1)
        } finally {
            upgraded.close()
            context.deleteDatabase(TEST_DATABASE)
        }
    }

    private fun createVersion10Database(databaseFile: java.io.File) {
        val schema = requireNotNull(javaClass.classLoader?.getResourceAsStream(SCHEMA_V10)) {
            "Room v10 schema snapshot is missing from unit test resources"
        }.bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }

        val legacy = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val tableName = entity.getString("tableName")
                legacy.execSQL(
                    entity.getString("createSql").replace("\${TABLE_NAME}", tableName)
                )
            }
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val indices = entity.getJSONArray("indices")
                for (index in 0 until indices.length()) {
                    val sql = indices.getJSONObject(index).getString("createSql")
                    legacy.execSQL(sql.replace("\${TABLE_NAME}", entity.getString("tableName")))
                }
            }
            legacy.execSQL(
                """INSERT INTO tracks (uri, title, artist, album, durationMs, albumArtPath, playCount, isFavorite, addedAt)
                    VALUES ('$FAVORITE_URI', 'Saved song', 'Artist', '', 120000, NULL, 4, 1, 10)"""
            )
            legacy.execSQL(
                """INSERT INTO playlists (id, name, description, playlistUrl, playCount, createdAt)
                    VALUES (7, 'Saved playlist', '', '', 3, 11)"""
            )
            legacy.execSQL(
                """INSERT INTO playlist_tracks (playlistId, trackUri, sortOrder)
                    VALUES (7, '$FAVORITE_URI', 2)"""
            )
            legacy.execSQL("PRAGMA user_version = 10")
        } finally {
            legacy.close()
        }
    }

    private companion object {
        const val TEST_DATABASE = "playlist-last-played-migration"
        const val SCHEMA_V10 = "com.luno.mobile.data.db.AppDatabase/10.json"
        const val FAVORITE_URI = "content://migration/favorite"
    }
}
