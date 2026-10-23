package com.luno.mobile.playback

import com.luno.mobile.data.db.dao.DownloadJobDao
import com.luno.mobile.data.db.dao.PlaylistDao
import com.luno.mobile.data.db.dao.TrackDao
import com.luno.mobile.data.db.entity.DownloadJob
import com.luno.mobile.data.db.entity.DownloadState
import com.luno.mobile.data.db.entity.PlaylistTrack
import com.luno.mobile.data.db.entity.Track
import com.luno.mobile.data.repository.normalizeForDuplicate
import kotlin.math.abs

/** New downloads required after library and in-flight jobs are reconciled. */
internal data class PlaylistSyncPlan(
    val videosToDownload: List<PlaylistVideo>,
    val reusedTracks: Int,
    val alreadyQueued: Int,
    val mergedDuplicates: Int
)

/**
 * Resolves playlist entries against durable provenance and legacy metadata
 * before any network work. Durable video identity wins; old rows without it
 * match by normalized artist/title, or by exact title plus duration when the
 * old artist was never recovered. Title-only matching is never used broadly:
 * it could join unrelated songs.
 */
internal class PlaylistSyncPlanner(
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val jobDao: DownloadJobDao
) {
    suspend fun plan(playlistId: Long, videos: List<PlaylistVideo>): PlaylistSyncPlan {
        // Repair first so a previously merged row can satisfy this run.
        val mergedDuplicates = repairDuplicateDownloads(playlistId)
        val tracks = trackDao.getAllTracksOnce()
        val completedByVideoId = completedJobsByVideoId()
        var reused = 0
        var alreadyQueued = 0
        val toDownload = mutableListOf<PlaylistVideo>()

        // Playlists can repeat a video; one durable download covers every slot.
        for (video in videos.filter { it.videoId.isNotBlank() }.distinctBy { it.videoId }) {
            val track = findExistingTrack(video, tracks, completedByVideoId)
            if (track != null) {
                addMembership(playlistId, track.uri)
                reused++
                continue
            }
            if (hasActiveDownload(video.videoId)) {
                alreadyQueued++
                continue
            }
            toDownload += video
        }

        return PlaylistSyncPlan(
            videosToDownload = toDownload,
            reusedTracks = reused,
            alreadyQueued = alreadyQueued,
            mergedDuplicates = mergedDuplicates
        )
    }

    /** Re-check used after slow extraction; reads the current library. */
    internal suspend fun findExistingTrack(video: PlaylistVideo): Track? =
        findExistingTrack(video, trackDao.getAllTracksOnce(), completedJobsByVideoId())

    internal suspend fun hasActiveDownload(videoId: String): Boolean {
        val job = jobDao.latestByVideoId(videoId)
        if (job != null && job.state.isActive()) return true
        // Legacy rows may have no videoId column value but keep the original
        // video identity in their source URL.
        return jobDao.countByVideoQuery(videoId) > 0
    }

    internal suspend fun addMembership(playlistId: Long, trackUri: String) {
        if (playlistDao.isTrackInPlaylist(playlistId, trackUri)) return
        playlistDao.addTrackToPlaylist(
            PlaylistTrack(
                playlistId = playlistId,
                trackUri = trackUri,
                sortOrder = playlistDao.maxSortOrder(playlistId) + 1
            )
        )
    }

    private suspend fun findExistingTrack(
        video: PlaylistVideo,
        tracks: List<Track>,
        completedByVideoId: Map<String, DownloadJob>
    ): Track? {
        tracks.firstOrNull { it.videoId == video.videoId }?.let { return it }

        val completed = completedByVideoId[video.videoId]
        val completedUri = completed?.localUri?.takeIf { it.isNotBlank() }
        if (completedUri != null) {
            tracks.firstOrNull { it.uri == completedUri }?.let { track ->
                trackDao.setVideoIdIfBlank(track.uri, video.videoId)
                return track
            }
        }

        return findLegacyTrack(video, tracks)
    }

    /**
     * Metadata fallback for rows created before Track stored videoId. Rows
     * already carrying a different videoId are a different video and are not
     * candidates.
     */
    private suspend fun findLegacyTrack(video: PlaylistVideo, tracks: List<Track>): Track? {
        val videoCombined = combinedKey(video.artist, video.title)
        val split = video.title.split(" - ", limit = 2)
        val videoSplit = if (split.size == 2) combinedKey(split[0], split[1]) else ""
        val videoTitle = titleKey(video.title)
        // Extractor durations are seconds; Room stores milliseconds.
        val videoDurationMs = video.duration * 1000L

        return tracks.firstOrNull { track ->
            if (track.videoId.isNotBlank() && track.videoId != video.videoId) {
                return@firstOrNull false
            }
            val trackCombined = combinedKey(track.artist, track.title)
            if ((videoCombined.isNotEmpty() && videoCombined == trackCombined) ||
                (videoSplit.isNotEmpty() && videoSplit == trackCombined)
            ) {
                return@firstOrNull durationsCompatible(videoDurationMs, track.durationMs)
            }
            // Some legacy imports lost the artist entirely. Exact normalized
            // title plus duration is strong enough only in that case.
            isUnknownArtist(track.artist) &&
                videoDurationMs > 0L && track.durationMs > 0L &&
                videoTitle.isNotEmpty() && videoTitle == titleKey(track.title) &&
                abs(videoDurationMs - track.durationMs) <= METADATA_DURATION_TOLERANCE_MS
        }?.also { track ->
            if (track.videoId.isBlank()) {
                trackDao.setVideoIdIfBlank(track.uri, video.videoId)
            }
        }
    }

    /**
     * Collapses rows created by repeated syncs. Proven rows (stored videoId or
     * a completed job) merge by video identity. A legacy row with no
     * provenance merges into the single proven row with the same normalized
     * artist/title, or exact title/artist group; ambiguous versions are left
     * alone.
     */
    internal suspend fun repairDuplicateDownloads(playlistId: Long): Int {
        val tracks = playlistDao.getPlaylistWithTracks(playlistId)?.tracks.orEmpty()
        if (tracks.size < 2) return 0

        val completedJobs = jobDao.getCompletedDownloadsOnce()
            .filter { it.videoId.isNotBlank() }
        val videoIdByUri = completedJobs
            .groupBy { it.localUri }
            .mapValues { (_, jobs) -> jobs.first().videoId }
        val allMemberships = playlistDao.getAllPlaylistTracksOnce()
        fun provenance(track: Track): String =
            track.videoId.ifBlank { videoIdByUri[track.uri].orEmpty() }

        val proven = tracks.filter { provenance(it).isNotBlank() }
        val unproven = tracks.filter { provenance(it).isBlank() }
        var merged = 0
        val representativeByVideoId = mutableMapOf<String, String>()

        for ((videoId, group) in proven.groupBy(::provenance).filterValues { it.size > 1 }) {
            val canonical = group.minWithOrNull(
                compareBy<Track>({ it.addedAt }, { it.uri })
            ) ?: continue
            representativeByVideoId[videoId] = canonical.uri
            trackDao.setVideoIdIfBlank(canonical.uri, videoId)
            merged += mergeInto(
                canonicalUri = canonical.uri,
                duplicates = group,
                completedJobs = completedJobs,
                allMemberships = allMemberships
            )
        }

        fun representative(track: Track): String =
            representativeByVideoId[provenance(track)] ?: track.uri

        val provenByUri = proven.associateBy { it.uri }
        val provenByCombined = proven
            .groupBy { combinedKey(it.artist, it.title) }
            .filterKeys { it.isNotEmpty() }
            .mapValues { (_, group) -> group.map(::representative).distinct() }
        val provenByTitle = proven
            .groupBy { titleKey(it.title) }
            .filterKeys { it.isNotEmpty() }
            .mapValues { (_, group) -> group.map(::representative).distinct() }

        for (track in unproven) {
            val combinedCandidates = provenByCombined[combinedKey(track.artist, track.title)]
                ?.mapNotNull(provenByUri::get)
                ?.filter { durationsCompatible(track.durationMs, it.durationMs) }
            val titleCandidates = if (isUnknownArtist(track.artist)) {
                provenByTitle[titleKey(track.title)]
                    ?.mapNotNull(provenByUri::get)
                    ?.filter {
                        track.durationMs > 0L && it.durationMs > 0L &&
                            abs(track.durationMs - it.durationMs) <=
                            METADATA_DURATION_TOLERANCE_MS
                    }
            } else {
                null
            }
            val targetUri = combinedCandidates?.singleOrNull()?.uri
                ?: titleCandidates?.singleOrNull()?.uri
                ?: continue
            merged += mergeInto(
                canonicalUri = targetUri,
                duplicates = listOf(track),
                completedJobs = completedJobs,
                allMemberships = allMemberships
            )
        }

        // Legacy syncs can leave multiple rows without provenance. Merge only
        // groups whose exact title and artist match; a normalized match alone
        // could collapse distinct versions (for example a live recording).
        val remainingUnproven = playlistDao.getPlaylistWithTracks(playlistId)
            ?.tracks
            .orEmpty()
            .filter { provenance(it).isBlank() }
        for (group in remainingUnproven
            .groupBy { it.title.trim() to it.artist.trim() }
            .values
            .filter { it.size > 1 }
        ) {
            val canonical = group.minWithOrNull(
                compareBy<Track>({ it.addedAt }, { it.uri })
            ) ?: continue
            merged += mergeInto(
                canonicalUri = canonical.uri,
                duplicates = group,
                completedJobs = completedJobs,
                allMemberships = allMemberships
            )
        }
        return merged
    }

    private suspend fun mergeInto(
        canonicalUri: String,
        duplicates: List<Track>,
        completedJobs: List<DownloadJob>,
        allMemberships: List<PlaylistTrack>
    ): Int {
        val canonical = trackDao.getTrack(canonicalUri) ?: return 0
        var canonicalFavorite = canonical.isFavorite
        var canonicalArtwork = canonical.albumArtPath
        var merged = 0

        for (duplicate in duplicates) {
            if (duplicate.uri == canonicalUri) continue

            if (duplicate.isFavorite && !canonicalFavorite) {
                trackDao.setFavorite(canonicalUri, true)
                canonicalFavorite = true
            }
            if (canonicalArtwork.isNullOrBlank() &&
                !duplicate.albumArtPath.isNullOrBlank()
            ) {
                trackDao.setArtwork(canonicalUri, duplicate.albumArtPath)
                canonicalArtwork = duplicate.albumArtPath
            }
            if (duplicate.playCount > 0) {
                trackDao.addPlayCount(canonicalUri, duplicate.playCount)
            }

            // Preserve membership everywhere the duplicate appeared before the
            // cascading Track delete removes its junction rows.
            allMemberships
                .filter { it.trackUri == duplicate.uri }
                .forEach { membership ->
                    playlistDao.addTrackToPlaylist(
                        PlaylistTrack(
                            playlistId = membership.playlistId,
                            trackUri = canonicalUri,
                            sortOrder = membership.sortOrder
                        )
                    )
                }
            completedJobs
                .filter { it.localUri == duplicate.uri }
                .forEach { job -> jobDao.updateDownload(job.copy(localUri = canonicalUri)) }
            trackDao.deleteTrack(duplicate.uri)
            merged++
        }
        return merged
    }

    private suspend fun completedJobsByVideoId(): Map<String, DownloadJob> =
        jobDao.getCompletedDownloadsOnce()
            .filter { it.videoId.isNotBlank() }
            .groupBy { it.videoId }
            .mapValues { (_, jobs) -> jobs.maxByOrNull { it.addedAt }!! }

    private fun combinedKey(artist: String, title: String): String =
        normalizeForDuplicate("$artist$title")

    private fun titleKey(title: String): String = normalizeForDuplicate(title)

    private fun durationsCompatible(first: Long, second: Long): Boolean =
        first <= 0L || second <= 0L || abs(first - second) <= METADATA_DURATION_TOLERANCE_MS

    private fun isUnknownArtist(artist: String): Boolean =
        artist.isBlank() || artist.equals("Unknown Artist", ignoreCase = true)

    private companion object {
        const val METADATA_DURATION_TOLERANCE_MS = 3_000L
    }
}

private fun DownloadState.isActive(): Boolean =
    this == DownloadState.QUEUED || this == DownloadState.DOWNLOADING
