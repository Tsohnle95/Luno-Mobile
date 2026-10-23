package com.luno.mobile.playback

import android.content.Context
import android.os.Bundle
import android.util.AtomicFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class PlaybackCursor(
    val revision: String,
    val index: Int,
    val positionMs: Long,
    val playWhenReady: Boolean
)

internal data class PlaybackQueueSnapshot(
    val items: List<MediaItem>,
    val shuffleOrder: List<Int>,
    val shuffle: Boolean,
    val repeat: Int,
    val cursor: PlaybackCursor
)

/** Called on the service's single persistence worker, except startup reads. */
internal class PlaybackQueueStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "playback_queue.json"))
    private val preferences = context.getSharedPreferences("playback_queue_cursor", Context.MODE_PRIVATE)

    fun save(snapshot: PlaybackQueueSnapshot) {
        val root = JSONObject().put("version", 1)
            .put("items", JSONArray(snapshot.items.map(::encodeItem)))
            .put("order", JSONArray(snapshot.shuffleOrder))
            .put("shuffle", snapshot.shuffle).put("repeat", snapshot.repeat)
            .put("cursor", encodeCursor(snapshot.cursor))
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 32 * 1024 * 1024)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        checkpoint(snapshot.cursor)
    }

    fun checkpoint(cursor: PlaybackCursor) {
        // One value ties the cursor to a complete queue generation. A crash
        // between the atomic queue write and this update uses its initial cursor.
        preferences.edit().putString("cursor", encodeCursor(cursor).toString()).apply()
    }

    fun read(): PlaybackQueueSnapshot? = try {
        val bytes = file.openRead().use { input ->
            require(input.channel.size() <= 32 * 1024 * 1024)
            input.readBytes()
        }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("version") == 1)
        val itemsJson = root.getJSONArray("items")
        val items = (0 until itemsJson.length()).map { decodeItem(itemsJson.getJSONObject(it)) }
        val orderJson = root.getJSONArray("order")
        val order = (0 until orderJson.length()).map { orderJson.getInt(it) }
        require(order.sorted() == items.indices.toList())
        val initialCursor = decodeCursor(root.getJSONObject("cursor"))
        val latestCursor = runCatching {
            preferences.getString("cursor", null)?.let { decodeCursor(JSONObject(it)) }
        }.getOrNull()
        val cursor = latestCursor?.takeIf {
            it.revision == initialCursor.revision &&
                ((items.isEmpty() && it.index == 0) || it.index in items.indices)
        } ?: initialCursor
        require((items.isEmpty() && cursor.index == 0) || cursor.index in items.indices)
        val repeat = root.getInt("repeat")
        require(repeat in 0..2)
        PlaybackQueueSnapshot(items, order, root.getBoolean("shuffle"), repeat, cursor)
    } catch (_: Exception) {
        null
    }

    private fun encodeCursor(cursor: PlaybackCursor) = JSONObject()
        .put("revision", cursor.revision).put("index", cursor.index)
        .put("position", cursor.positionMs).put("play", cursor.playWhenReady)

    private fun decodeCursor(json: JSONObject) = PlaybackCursor(
        json.getString("revision"), json.getInt("index"),
        json.getLong("position").also { require(it >= 0) }, json.getBoolean("play")
    )

    private fun encodeItem(item: MediaItem): JSONObject {
        val metadata = item.mediaMetadata
        val extras = metadata.extras
        return JSONObject().put("id", item.mediaId)
            .put("uri", requireNotNull(item.localConfiguration).uri.toString())
            .put("title", metadata.title?.toString().orEmpty())
            .put("artist", metadata.artist?.toString().orEmpty())
            .put("album", metadata.albumTitle?.toString().orEmpty())
            .put("artwork", metadata.artworkUri?.toString())
            .put("duration", extras?.getLong(MusicController.METADATA_DURATION_MS) ?: 0L)
            .put("category", extras?.getString(MusicController.METADATA_SOURCE_CATEGORY))
            .put("name", extras?.getString(MusicController.METADATA_SOURCE_NAME))
            .put("playlist", extras?.getLong(MusicController.METADATA_PLAYLIST_ID) ?: 0L)
            .put("manual", extras?.getBoolean(MusicController.METADATA_MANUALLY_QUEUED) == true)
    }

    private fun decodeItem(json: JSONObject): MediaItem {
        val uri = json.getString("uri").also { require(it.isNotBlank()) }
        val extras = Bundle().apply {
            putLong(MusicController.METADATA_DURATION_MS, json.getLong("duration"))
            json.optString("category").takeIf { it.isNotBlank() }?.let {
                putString(MusicController.METADATA_SOURCE_CATEGORY, it)
            }
            json.optString("name").takeIf { it.isNotBlank() }?.let {
                putString(MusicController.METADATA_SOURCE_NAME, it)
            }
            json.getLong("playlist").takeIf { it != 0L }?.let {
                putLong(MusicController.METADATA_PLAYLIST_ID, it)
            }
            putBoolean(MusicController.METADATA_MANUALLY_QUEUED, json.optBoolean("manual"))
        }
        return MediaItem.Builder().setMediaId(json.getString("id")).setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(json.getString("title")).setArtist(json.getString("artist"))
                .setAlbumTitle(json.getString("album"))
                .apply { json.optString("artwork").takeIf { it.isNotBlank() }?.let {
                    setArtworkUri(android.net.Uri.parse(it))
                } }
                .setExtras(extras).build())
            .build()
    }
}
