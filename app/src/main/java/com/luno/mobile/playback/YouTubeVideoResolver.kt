package com.luno.mobile.playback

import android.content.Context
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.MediaFormat

internal fun videoWords(value: String): Set<String> = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}+"), "").lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
    .filter { it.isNotBlank() }.toSet()

internal fun videoSearchTitle(track: MediaTrack): String = track.title.substringBefore(" - ")
    .replace(Regex("\\[[^]]*]"), "").trim()

/** Prefer strict artist/title matches, allowing the desktop's bounded title-overlap fallback. */
internal fun rankVideoCandidates(track: MediaTrack, candidates: List<WebSearchResult>): List<WebSearchResult> {
    val title = videoWords(videoSearchTitle(track))
    if (title.isEmpty()) return emptyList()
    val artist = videoWords(track.artist).minus(setOf("unknown", "artist", "topic"))
    val floor = minOf(title.size, maxOf(2, (title.size + 1) / 2))
    return candidates.distinctBy { it.videoId }.filter { candidate ->
        val titleOverlap = videoWords(candidate.title).intersect(title).size
        val durationMs = candidate.duration * 1000
        titleOverlap >= floor && (track.durationMs <= 0 || durationMs <= 0 ||
            kotlin.math.abs(durationMs - track.durationMs) <= maxOf(15_000L, track.durationMs / 5))
    }.sortedWith(compareByDescending<WebSearchResult> {
        val words = videoWords(it.title + " " + it.artist)
        title.all { word -> word in words } && (artist.isEmpty() || artist.any { word -> word in words })
    }.thenByDescending { videoWords(it.title).intersect(title).size }).take(3)
}

internal class YouTubeVideoResolver(context: Context) : TemporaryVideoResolver {
    private val mapping = context.getSharedPreferences("temporary_video_sources", Context.MODE_PRIVATE)

    override suspend fun resolve(track: MediaTrack): List<TemporaryVideoSource> = withContext(Dispatchers.IO) {
        val known = mapping.getString(track.uri, null)
        if (known != null) {
            runCatching { sourcesFor(known) }.getOrNull()?.let { return@withContext it }
        }
        val query = "${track.artist} - ${videoSearchTitle(track)}"
        val results = when (val result = WebSearchService.searchYouTube(query, limit = 12)) {
            is ExtractionResult.Success -> result.data
            is ExtractionResult.Error -> error(result.message)
        }
        val matches = rankVideoCandidates(track, results)
        val sources = mutableListOf<TemporaryVideoSource>()
        for (candidate in matches) {
            currentCoroutineContext().ensureActive()
            runCatching { sourcesFor(candidate.videoId) }.getOrNull()?.let { sources += it }
        }
        check(sources.isNotEmpty()) { "No matching MP4 video is available for this song" }
        // This is source identity only. MP4 bytes remain disposable app cache.
        mapping.edit().putString(track.uri, sources.first().videoId).apply()
        sources
    }

    override suspend fun resolveUpgrade(track: MediaTrack, videoId: String): List<TemporaryVideoSource> =
        withContext(Dispatchers.IO) {
            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            extractor.fetchPage()
            videoUpgradeChoices(videoId, extractor.videoStreams + extractor.videoOnlyStreams)
        }

    private fun sourcesFor(id: String): List<TemporaryVideoSource> {
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$id")
        extractor.fetchPage()
        return videoStreamChoices(id, extractor.videoStreams + extractor.videoOnlyStreams)
    }
}

/** Keep lower-resolution alternatives for the same song when a large stream is rejected. */
internal fun videoStreamChoices(
    id: String,
    streams: List<org.schabi.newpipe.extractor.stream.VideoStream>
): List<TemporaryVideoSource> {
    fun height(stream: org.schabi.newpipe.extractor.stream.VideoStream): Int =
        Regex("\\d+").find(stream.getResolution())?.value?.toIntOrNull() ?: Int.MAX_VALUE
    val compatible = streams.filter {
        it.format == MediaFormat.MPEG_4 && !it.content.isNullOrBlank() && it.isUrl && height(it) <= 1080
    }.distinctBy { it.content }
    val quick = compatible.filter { height(it) <= 480 }
    val selected = quick.filter { !it.isVideoOnly() }.maxByOrNull(::height)
        ?: quick.maxByOrNull(::height)
        ?: compatible.minByOrNull(::height)
        ?: error("This source has no compatible MP4 video")
    return (listOf(selected) + compatible.filter { height(it) <= height(selected) }
        .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> { height(it) }
            .thenByDescending { it.isVideoOnly() }))
        .distinctBy { it.content }.take(3)
        .map { TemporaryVideoSource(id, it.content, height(it)) }
}

internal fun videoUpgradeChoices(
    id: String,
    streams: List<org.schabi.newpipe.extractor.stream.VideoStream>
): List<TemporaryVideoSource> = streams.filter {
    it.format == MediaFormat.MPEG_4 && !it.content.isNullOrBlank() && it.isUrl &&
        (it.codec.isNullOrBlank() || it.codec.startsWith("avc", ignoreCase = true))
}.sortedBy { it.getResolution().contains("60") }.mapNotNull {
    val height = Regex("\\d+").find(it.getResolution())?.value?.toIntOrNull() ?: return@mapNotNull null
    if (height !in 481..1080) null else TemporaryVideoSource(id, it.content, height)
}.distinctBy { it.url }.sortedByDescending { it.height }.distinctBy { it.height }.take(3)
