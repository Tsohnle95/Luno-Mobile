package com.luno.mobile.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.luno.mobile.data.db.entity.Track
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY addedAt DESC")
    fun getAllTracks(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE uri = :uri")
    suspend fun getTrack(uri: String): Track?

    @Query("SELECT * FROM tracks WHERE videoId = :videoId LIMIT 1")
    suspend fun getTrackByVideoId(videoId: String): Track?

    /** Backfill provenance without overwriting a known identity. */
    @Query("UPDATE tracks SET videoId = :videoId WHERE uri = :uri AND videoId = ''")
    suspend fun setVideoIdIfBlank(uri: String, videoId: String)

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' ORDER BY title ASC")
    fun searchTracks(query: String): Flow<List<Track>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: Track)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<Track>)

    @Update
    suspend fun updateTrack(track: Track)

    @Query("UPDATE tracks SET albumArtPath = :path WHERE uri = :uri")
    suspend fun setArtwork(uri: String, path: String?)

    // Compare each field independently: a user edit during a slow scan wins.
    // Column updates also preserve concurrent favorites, history and artwork.
    @Query("""UPDATE tracks SET
        title = CASE WHEN title = :oldTitle AND :title != '' THEN :title ELSE title END,
        artist = CASE WHEN artist = :oldArtist AND :artist != '' THEN :artist ELSE artist END,
        durationMs = CASE WHEN durationMs = 0 AND :durationMs > 0 THEN :durationMs ELSE durationMs END
        WHERE uri = :uri AND (
            (title = :oldTitle AND :title != '' AND title != :title) OR
            (artist = :oldArtist AND :artist != '' AND artist != :artist) OR
            (durationMs = 0 AND :durationMs > 0))""")
    suspend fun repairMetadata(
        uri: String, oldTitle: String, oldArtist: String,
        title: String, artist: String, durationMs: Long
    ): Int

    @Query("DELETE FROM tracks WHERE uri = :uri")
    suspend fun deleteTrack(uri: String)

    @Query("UPDATE tracks SET playCount = playCount + 1 WHERE uri = :uri")
    suspend fun incrementPlayCount(uri: String)

    @Query("UPDATE tracks SET playCount = playCount + :count WHERE uri = :uri")
    suspend fun addPlayCount(uri: String, count: Int)

    @Query("UPDATE tracks SET isFavorite = :isFavorite WHERE uri = :uri")
    suspend fun setFavorite(uri: String, isFavorite: Boolean)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun trackCount(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM tracks WHERE uri = :uri)")
    suspend fun exists(uri: String): Boolean

    @Query("SELECT * FROM tracks")
    suspend fun getAllTracksOnce(): List<com.luno.mobile.data.db.entity.Track>

    @Query("SELECT * FROM tracks WHERE uri IN (:uris)")
    suspend fun getTracksByUris(uris: List<String>): List<Track>
}
