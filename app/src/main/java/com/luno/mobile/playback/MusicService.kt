package com.luno.mobile.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.luno.mobile.MainActivity
import com.luno.mobile.LunoApp
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors
import java.util.UUID
import java.util.concurrent.TimeUnit

class MusicService : MediaSessionService() {

    companion object {
        const val ACTION_OPEN_PLAYER = "com.luno.mobile.action.OPEN_PLAYER"
        private const val PLAYBACK_PREFS = "playback_restore"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_REPEAT = "repeat"
        private const val KEY_URI = "uri"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_ALBUM = "album"
        private const val KEY_DURATION_MS = "duration_ms"
        private const val KEY_ARTWORK_URI = "artwork_uri"
        private const val KEY_SOURCE_CATEGORY = "source_category"
        private const val KEY_SOURCE_NAME = "source_name"
        private const val KEY_SOURCE_PLAYLIST_ID = "source_playlist_id"
        private const val KEY_POSITION_MS = "position_ms"
        private const val KEY_PLAY_WHEN_READY = "play_when_ready"
    }

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private val queueExecutor = Executors.newSingleThreadExecutor()
    private lateinit var queueStore: PlaybackQueueStore
    private var queueDirty = true
    private var queueRevision = UUID.randomUUID().toString()
    private var lastCursor: PlaybackCursor? = null
    private val checkpointHandler = Handler(Looper.getMainLooper())
    private val periodicCheckpoint = object : Runnable {
        override fun run() {
            persistPlayback()
            checkpointHandler.postDelayed(this, 5_000L)
        }
    }
    private val playbackPersistenceListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) { queueDirty = true }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { queueDirty = true }
        override fun onRepeatModeChanged(repeatMode: Int) { queueDirty = true }
        override fun onEvents(player: Player, events: Player.Events) = persistPlayback()
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        val p = player ?: return
        queueStore = PlaybackQueueStore(this)
        restoreLastPlayback(p)
        p.addListener(playbackPersistenceListener)
        checkpointHandler.postDelayed(periodicCheckpoint, 5_000L)

        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_PLAYER
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, p)
            .setSessionActivity(pendingIntent)
            .setCallback(ArtworkEnrichingCallback())
            .build()
    }

    /**
     * Enriches incoming media items with `artworkData` bytes loaded from
     * their `artworkUri` (cached embedded artwork).  The media-session
     * notification and lock-screen controls render artwork from
     * `artworkData`, so without this enrichment those surfaces would
     * show no artwork even though the in-app UI loads the same file.
     *
     * Only items flagged `METADATA_ENRICH_ARTWORK` by [MusicController]
     * (the play-start item plus a small window around it, and manually
     * queued items) are enriched.  Loading artworkData for **every** item
     * of a large context queue (this library: ~170MB of cached artwork
     * across 4k+ tracks) holds the whole library in memory at once and
     * gets the process LMK/OOM-killed.
     */
    private inner class ArtworkEnrichingCallback : MediaSession.Callback {
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onPlayerCommandRequest(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int
        ): Int {
            if (playerCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM &&
                (application as? LunoApp)
                    ?.recommendationPreviewManager
                    ?.handleSessionNext() == true
            ) {
                return SessionResult.RESULT_ERROR_NOT_SUPPORTED
            }
            return SessionResult.RESULT_SUCCESS
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val output = SettableFuture.create<List<MediaItem>>()
            artworkExecutor.execute {
                try {
                    val enriched = mediaItems.map { item ->
                        val metadata = item.mediaMetadata
                        val artworkUri = metadata.artworkUri
                        val shouldEnrich = metadata.extras
                            ?.getBoolean(MusicController.METADATA_ENRICH_ARTWORK) == true
                        if (shouldEnrich && artworkUri != null && metadata.artworkData == null) {
                            val bytes = try {
                                contentResolver.openInputStream(artworkUri)?.use { it.readBytes() }
                            } catch (_: Exception) {
                                null
                            }
                            if (bytes != null && bytes.isNotEmpty()) {
                                item.buildUpon()
                                    .setMediaMetadata(
                                        metadata.buildUpon()
                                            .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                            .build()
                                    )
                                    .build()
                            } else {
                                item
                            }
                        } else {
                            item
                        }
                    }
                    output.set(enriched)
                } catch (e: Exception) {
                    output.setException(e)
                }
            }
            return output
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        persistPlayback()
        if (p == null || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        checkpointHandler.removeCallbacks(periodicCheckpoint)
        persistPlayback()
        // Release session BEFORE player (Media3 contract: session holds player reference).
        // Fields are nulled immediately so cleanup is idempotent.
        mediaSession?.run {
            release()
            mediaSession = null
        }
        player?.run {
            removeListener(playbackPersistenceListener)
            release()
            player = null
        }
        artworkExecutor.shutdown()
        queueExecutor.shutdown()
        super.onDestroy()
    }

    private fun persistPlayback() {
        val p = player ?: return
        val previewState = (application as? LunoApp)?.recommendationPreviewManager?.state?.value
        val temporary = previewState?.discoverMode == true || previewState?.active == true ||
            (0 until p.mediaItemCount).any {
                p.getMediaItemAt(it).mediaMetadata.extras?.getBoolean(MusicController.METADATA_TRANSIENT) == true
            }
        if (temporary) {
            // Discover fences the physical queue before a preview plays. Never
            // replace durable recovery with that one-item/temporary projection.
            lastCursor?.takeIf { it.playWhenReady }?.copy(playWhenReady = false)?.let { cursor ->
                lastCursor = cursor
                enqueuePersistence { queueStore.checkpoint(cursor) }
            }
            queueDirty = true
            return
        }
        val cursor = PlaybackCursor(
            revision = if (queueDirty) UUID.randomUUID().toString() else queueRevision,
            index = p.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = p.currentPosition.coerceAtLeast(0L),
            playWhenReady = p.playWhenReady && p.playbackState != Player.STATE_ENDED
        )
        if (queueDirty) {
            val timeline = p.currentTimeline
            val order = ArrayList<Int>(p.mediaItemCount)
            var index = timeline.getFirstWindowIndex(true)
            while (index != C.INDEX_UNSET && order.size < p.mediaItemCount) {
                order += index
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
            }
            val snapshot = PlaybackQueueSnapshot(
                (0 until p.mediaItemCount).map { p.getMediaItemAt(it) },
                order, p.shuffleModeEnabled, p.repeatMode, cursor
            )
            queueDirty = false
            queueRevision = cursor.revision
            enqueuePersistence { queueStore.save(snapshot) }
        } else if (cursor != lastCursor) {
            enqueuePersistence { queueStore.checkpoint(cursor) }
        }
        lastCursor = cursor
    }

    private fun enqueuePersistence(block: () -> Unit) {
        queueExecutor.execute {
            try {
                block()
            } catch (_: Exception) {
                Log.w("MusicService", "Could not save playback recovery state")
                checkpointHandler.post { queueDirty = true }
            }
        }
    }

    // A test seam for deterministic disk completion; production never blocks the UI.
    internal fun awaitPlaybackPersistence() {
        if (queueExecutor.isShutdown) {
            check(queueExecutor.awaitTermination(10, TimeUnit.SECONDS))
        } else {
            queueExecutor.submit {}.get(10, TimeUnit.SECONDS)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun restoreLastPlayback(currentPlayer: ExoPlayer) {
        queueStore.read()?.let { snapshot ->
            queueRevision = snapshot.cursor.revision
            lastCursor = snapshot.cursor
            currentPlayer.shuffleModeEnabled = snapshot.shuffle
            currentPlayer.repeatMode = snapshot.repeat
            if (snapshot.items.isNotEmpty()) {
                currentPlayer.setMediaItems(snapshot.items, snapshot.cursor.index, snapshot.cursor.positionMs)
                currentPlayer.setShuffleOrder(DefaultShuffleOrder(snapshot.shuffleOrder.toIntArray(), 0L))
                currentPlayer.prepare()
                currentPlayer.playWhenReady = snapshot.cursor.playWhenReady
            }
            return
        }
        val preferences = getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE)
        currentPlayer.shuffleModeEnabled = preferences.getBoolean(KEY_SHUFFLE, false)
        currentPlayer.repeatMode = preferences.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF)
            .takeIf { it in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL } ?: Player.REPEAT_MODE_OFF
        val uri = preferences.getString(KEY_URI, null)?.takeIf { it.isNotBlank() } ?: return
        val track = MediaTrack(
            uri = uri,
            title = preferences.getString(KEY_TITLE, null).orEmpty().ifBlank { "Unknown" },
            artist = preferences.getString(KEY_ARTIST, null).orEmpty().ifBlank { "Unknown" },
            album = preferences.getString(KEY_ALBUM, null).orEmpty(),
            durationMs = preferences.getLong(KEY_DURATION_MS, 0L),
            artworkUri = preferences.getString(KEY_ARTWORK_URI, null),
            playbackSource = preferences.getString(KEY_SOURCE_CATEGORY, null)
                ?.takeIf { it.isNotBlank() }
                ?.let { category ->
                    PlaybackSource(
                        category = category,
                        name = preferences.getString(KEY_SOURCE_NAME, null),
                        playlistId = preferences.getLong(KEY_SOURCE_PLAYLIST_ID, 0L)
                            .takeIf { it != 0L }
                    )
                }
        )
        val extras = Bundle().apply {
            putLong(MusicController.METADATA_DURATION_MS, track.durationMs)
            putBoolean(MusicController.METADATA_ENRICH_ARTWORK, true)
            track.playbackSource?.let { source ->
                putString(MusicController.METADATA_SOURCE_CATEGORY, source.category)
                source.name?.let { putString(MusicController.METADATA_SOURCE_NAME, it) }
                source.playlistId?.let { putLong(MusicController.METADATA_PLAYLIST_ID, it) }
            }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setExtras(extras)
            .apply { track.artworkUri?.let { setArtworkUri(android.net.Uri.parse(it)) } }
            .build()
        currentPlayer.setMediaItem(
            MediaItem.Builder()
                .setMediaId(track.uri)
                .setUri(track.uri)
                .setMediaMetadata(metadata)
                .build()
        )
        currentPlayer.prepare()
        currentPlayer.seekTo(preferences.getLong(KEY_POSITION_MS, 0L).coerceAtLeast(0L))
        currentPlayer.playWhenReady = preferences.getBoolean(KEY_PLAY_WHEN_READY, false)
    }
}
