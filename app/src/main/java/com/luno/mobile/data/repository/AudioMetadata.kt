package com.luno.mobile.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

internal fun missingTitle(value: String) = value.trim().lowercase() in
    setOf("", "unknown", "unknown track", "unknown song", "<unknown>")

internal fun missingArtist(value: String) = value.trim().lowercase() in
    setOf("", "unknown", "unknown artist", "<unknown>")

data class AudioMetadata(val title: String = "", val artist: String = "", val durationMs: Long = 0)

internal fun audioFileName(uri: Uri): String = uri.lastPathSegment.orEmpty().substringAfterLast('/')

internal fun filenameMetadata(name: String): AudioMetadata {
    val title = name.substringBeforeLast('.', name).trim()
    val parts = title.split(" - ", limit = 2)
    return if (parts.size == 2 && parts.all { it.isNotBlank() }) {
        AudioMetadata(title = parts[1].trim(), artist = parts[0].trim())
    } else AudioMetadata(title = title)
}

internal fun readAudioMetadata(context: Context, uri: Uri): AudioMetadata {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        AudioMetadata(
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0) ?: 0
        )
    } finally {
        runCatching { retriever.release() }
    }
}

data class MetadataRepairResult(
    val updated: Int,
    val remainingTitles: Int,
    val remainingArtists: Int,
    val sidecarsSkipped: Int,
    val backupPath: String,
    val updatedTracks: List<com.luno.mobile.data.db.entity.Track> = emptyList()
)
