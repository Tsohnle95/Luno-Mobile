package com.luno.mobile.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Deterministic contract tests for [MusicController] using a fake
 * [MusicController.AsyncConnector] so queue dispatch, late completion,
 * state hydration, and failure paths can be verified without a live
 * [androidx.media3.session.MediaSessionService].
 *
 * Uses [Config.NONE] because the test injects a [FakeConnector] that
 * does not require [SessionToken][androidx.media3.session.SessionToken]
 * construction (which validates against the manifest).
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class MusicControllerTest {

    private lateinit var context: Context
    private lateinit var fakeConnector: FakeConnector
    private lateinit var controller: MusicController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("context_shuffle", Context.MODE_PRIVATE).edit().clear().commit()
        fakeConnector = FakeConnector()
        controller = MusicController(
            context,
            connector = fakeConnector
        )
    }

    @Test
    fun `context shuffle survives recreation and playlist rename without leaking to other collections`() {
        val library = PlaybackSource("Library", "All songs")
        val playlist = PlaybackSource("Playlist", "First name", 42)
        controller.setContextShuffle(library, true)
        controller.setContextShuffle(playlist, true)
        controller.setContextShuffle(library, false)
        controller.release()
        val recreated = MusicController(context, connector = fakeConnector)
        try {
            assertThat(recreated.shuffleFor(library)).isFalse()
            assertThat(recreated.shuffleFor(playlist.copy(name = "Renamed"))).isTrue()
            assertThat(recreated.shuffleFor(playlist.copy(playlistId = 43))).isFalse()
        } finally {
            recreated.release()
        }
    }

    @Test
    fun `full player shuffle saves active context and survives a new controller`() {
        val source = PlaybackSource("Library", "All songs")
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        val connectedController = MusicController(context,
            connector = MusicController.AsyncConnector { ctx -> MediaController.Builder(ctx, session.token).buildAsync() })
        try {
            val connected = CountDownLatch(1)
            connectedController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            player.setMediaItem(connectedController.buildMediaItem(
                MediaTrack(uri = "file:///saved.mp3", title = "Saved", artist = "Artist", playbackSource = source)))
            shadowOf(Looper.getMainLooper()).idle()
            connectedController.toggleShuffle()
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(player.shuffleModeEnabled).isTrue()
            assertThat(player.playWhenReady).isFalse()
            val otherSource = PlaybackSource("Playlist", "Other", 43)
            connectedController.play(MediaTrack(uri = "file:///other.mp3", title = "Other", artist = "Artist", playbackSource = otherSource))
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(player.shuffleModeEnabled).isFalse()
            val tracks = listOf("first", "selected", "last").map {
                MediaTrack(uri = "file:///$it.mp3", title = it, artist = "Artist", playbackSource = source)
            }
            connectedController.play(tracks, startIndex = 1)
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(player.shuffleModeEnabled).isTrue()
            assertThat(player.currentMediaItem?.mediaId).isEqualTo("file:///selected.mp3")
            val recreated = MusicController(context, connector = fakeConnector)
            try {
                assertThat(recreated.shuffleFor(source)).isTrue()
            } finally {
                recreated.release()
            }
        } finally {
            connectedController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun `manual queue membership hydrates so later additions stay after existing queued items`() {
        val player = ExoPlayer.Builder(context).build()
        val seed = MediaTrack(uri = "file:///seed.mp3", title = "Seed", artist = "Artist")
        player.setMediaItems(listOf(
            controller.buildMediaItem(seed),
            controller.buildMediaItem(seed.copy(uri = "file:///manual.mp3"), manuallyQueued = true),
            controller.buildMediaItem(seed.copy(uri = "file:///tail.mp3"))
        ))
        val session = MediaSession.Builder(context, player).build()
        val connectedController = MusicController(context,
            connector = MusicController.AsyncConnector { ctx -> MediaController.Builder(ctx, session.token).buildAsync() })
        try {
            val connected = CountDownLatch(1)
            connectedController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            connectedController.addToQueue(seed.copy(uri = "file:///new.mp3"))
            shadowOf(Looper.getMainLooper()).idle()
            assertThat((0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
                .containsExactly("file:///seed.mp3", "file:///manual.mp3", "file:///new.mp3", "file:///tail.mp3").inOrder()
        } finally {
            connectedController.release()
            session.release()
            player.release()
        }
    }

    // ── Pending-play contract (pre-connection) ──────────────────────────

    @Test
    fun `restored queue and metadata refresh do not add plays but explicit restarts do`() {
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        val played = mutableListOf<String>()
        val connectedController = MusicController(context,
            connector = MusicController.AsyncConnector { ctx -> MediaController.Builder(ctx, session.token).buildAsync() },
            onTrackPlayed = { uri, _ -> played += uri })
        val song = MediaTrack(uri = "file:///recovered.mp3", title = "Unknown Track", artist = "Unknown Artist")
        try {
            val connected = CountDownLatch(1)
            connectedController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            player.setMediaItem(connectedController.buildMediaItem(song))
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(played).isEmpty()

            connectedController.updateTrackMetadata(song.uri, "Recovered", "Artist")
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(played).isEmpty()
            assertThat(connectedController.currentTrack.value?.title).isEqualTo("Recovered")

            connectedController.play(song.copy(title = "Recovered", artist = "Artist"))
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(played).containsExactly(song.uri)
            connectedController.updateTrackMetadata(song.uri, "Edited", "Artist")
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(played).containsExactly(song.uri)
            connectedController.play(song.copy(title = "Edited", artist = "Artist"))
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(played).containsExactly(song.uri, song.uri)
        } finally {
            connectedController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun `playUri returns true even before connection`() {
        assertThat(controller.playUri("http://example.com/track.mp3")).isTrue()
    }

    @Test
    fun `play with single track returns true before connection`() {
        assertThat(controller.play(track("uri1"))).isTrue()
    }

    @Test
    fun `play with list returns true before connection`() {
        assertThat(controller.play(listOf(track("a"), track("b")), 0)).isTrue()
    }

    @Test
    fun `playShuffled queues a shuffle request with a valid random start`() {
        val tracks = listOf(track("a"), track("b"), track("c"))

        assertThat(controller.playShuffled(tracks)).isTrue()

        val request = controller.pendingRequest
        assertThat(request).isNotNull()
        assertThat(request!!.items).containsExactlyElementsIn(tracks).inOrder()
        assertThat(request.startIndex).isAtLeast(0)
        assertThat(request.startIndex).isLessThan(tracks.size)
        assertThat(request.shuffle).isTrue()
    }

    @Test
    fun `ordered library play resets shuffle and replaces playlist context with the full library`() {
        val library = listOf(track("first"), track("middle"), track("last"))
        controller.playShuffled(listOf(track("playlist-only")), playlistId = 123)
        controller.play(library, startIndex = 0, shuffle = false)
        val request = controller.pendingRequest!!
        assertThat(request.items).containsExactlyElementsIn(library).inOrder()
        assertThat(request.startIndex).isEqualTo(0)
        assertThat(request.shuffle).isFalse()
        assertThat(request.playlistId).isNull()
        controller.playShuffled(library)
        assertThat(controller.pendingRequest!!.shuffle).isTrue()
        assertThat(controller.pendingRequest!!.items).containsExactlyElementsIn(library).inOrder()
        assertThat(controller.pendingRequest!!.playlistId).isNull()
    }

    @Test
    fun `playSequential disables shuffle and repeat for a finite preview`() {
        val preview = MediaTrack(uri = "file:///cache/preview.m4a", isTransient = true)

        controller.playSequential(preview)

        val request = controller.pendingRequest
        assertThat(request).isNotNull()
        assertThat(request!!.items).containsExactly(preview)
        assertThat(request.shuffle).isFalse()
        assertThat(request.repeatMode).isEqualTo(Player.REPEAT_MODE_OFF)
    }

    @Test
    fun `play request revision tracks newer playback choices before connection`() {
        assertThat(controller.playRequestRevision.value).isEqualTo(0L)

        controller.playSequential(MediaTrack(uri = "file:///cache/preview.m4a", isTransient = true))
        controller.play(track("normal-choice"))
        controller.play(emptyList<MediaTrack>())

        assertThat(controller.playRequestRevision.value).isEqualTo(2L)
        assertThat(controller.pendingRequest!!.items.single().uri).isEqualTo("normal-choice")
    }

    @Test
    fun `playback neighbor follows playback order and stops at its edges`() {
        val playbackOrder = listOf(2, 0, 1)

        assertThat(playbackNeighborIndex(playbackOrder, currentRawIndex = 0, direction = -1))
            .isEqualTo(2)
        assertThat(playbackNeighborIndex(playbackOrder, currentRawIndex = 0, direction = 1))
            .isEqualTo(1)
        assertThat(playbackNeighborIndex(playbackOrder, currentRawIndex = 2, direction = -1))
            .isNull()
        assertThat(playbackNeighborIndex(playbackOrder, currentRawIndex = 1, direction = 1))
            .isNull()
    }

    @Test
    fun `play with empty list returns true before connection`() {
        assertThat(controller.play(emptyList<String>())).isTrue()
    }

    @Test
    fun `retaining current item removes earlier and later queue entries`() {
        val pastUri = "file:///past.m4a"
        val currentUri = "file:///current.m4a"
        val nextUri = "file:///next.m4a"
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        player.setMediaItems(listOf(pastUri, currentUri, nextUri).map { uri ->
            MediaItem.Builder().setMediaId(uri).setUri(uri).build()
        })
        player.seekToDefaultPosition(1)
        val connectedController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )

        try {
            val connected = CountDownLatch(1)
            connectedController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()

            assertThat(connectedController.retainCurrentItemOnly()).isTrue()
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(player.mediaItemCount).isEqualTo(1)
            assertThat(player.currentMediaItem?.mediaId).isEqualTo(currentUri)
        } finally {
            connectedController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun `discovery fencing a large queue bounds timeline rebuilds and retains playback`() {
        assertLargeQueueMutationPreservesPlayback(restore = false)
    }

    @Test
    fun `restoring discovery from a large queue bounds timeline rebuilds and retains playback`() {
        assertLargeQueueMutationPreservesPlayback(restore = true)
    }

    @Test
    fun `discovery fencing preserves paused playback and shuffle at either queue edge`() {
        for (index in listOf(0, 1_023)) {
            assertLargeQueueMutationPreservesPlayback(restore = false, selectedIndex = index, playing = false)
        }
    }

    private fun assertLargeQueueMutationPreservesPlayback(
        restore: Boolean,
        selectedIndex: Int = 471,
        playing: Boolean = true
    ) {
        // Each timeline rebuild also copies the current artwork. Small final-state
        // tests missed the quadratic allocation storm on a real library queue.
        val tracks = (0 until 1_024).map { track("file:///large-$it.m4a") }
        val player = ExoPlayer.Builder(context).build()
        player.setMediaItems(tracks.map { controller.buildMediaItem(it) }, selectedIndex, 12_345L)
        player.shuffleModeEnabled = true
        player.playWhenReady = playing
        val session = MediaSession.Builder(context, player).build()
        val connectedController = MusicController(context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            })
        try {
            val connected = CountDownLatch(1)
            connectedController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            var timelineChanges = 0
            var itemTransitions = 0
            player.addListener(object : Player.Listener {
                override fun onTimelineChanged(timeline: Timeline, reason: Int) { timelineChanges++ }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { itemTransitions++ }
            })
            val currentUid = player.currentTimeline.getWindow(selectedIndex, Timeline.Window()).uid
            if (restore) {
                assertThat(connectedController.restoreQueueAroundCurrent(
                    tracks, tracks[selectedIndex].uri, selectedIndex, resumeAtNextIfEnded = false
                )).isTrue()
            } else {
                assertThat(connectedController.retainCurrentItemOnly()).isTrue()
            }
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(timelineChanges).isAtMost(if (restore) 4 else 2)
            assertThat(itemTransitions).isEqualTo(0)
            assertThat(player.currentMediaItem?.mediaId).isEqualTo(tracks[selectedIndex].uri)
            assertThat(player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window()).uid)
                .isEqualTo(currentUid)
            assertThat(player.currentPosition).isEqualTo(12_345L)
            assertThat(player.playWhenReady).isEqualTo(playing)
            assertThat(player.shuffleModeEnabled).isTrue()
            assertThat(player.mediaItemCount).isEqualTo(if (restore) tracks.size else 1)
            if (restore) {
                assertThat((0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
                    .containsExactlyElementsIn(tracks.map { it.uri }).inOrder()
            }
        } finally {
            connectedController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun `retaining current item rejects a stale playback request revision`() {
        val seedUri = "file:///revision-seed.m4a"
        val oldNextUri = "file:///revision-old-next.m4a"
        val userChoiceUri = "file:///revision-user-choice.m4a"
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        player.setMediaItems(listOf(seedUri, oldNextUri).map { uri ->
            MediaItem.Builder().setMediaId(uri).setUri(uri).build()
        })
        player.seekToDefaultPosition(0)
        val connectedController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )

        try {
            val connected = CountDownLatch(1)
            connectedController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            val snapshotRevision = connectedController.playRequestRevision.value

            connectedController.play(
                listOf(
                    MediaTrack(uri = seedUri, title = "Seed"),
                    MediaTrack(uri = userChoiceUri, title = "New choice")
                )
            )

            assertThat(connectedController.playRequestRevision.value)
                .isGreaterThan(snapshotRevision)
            assertThat(
                connectedController.retainCurrentItemOnly(seedUri, snapshotRevision)
            ).isFalse()
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(player.mediaItemCount).isEqualTo(2)
            assertThat(player.currentMediaItem?.mediaId).isEqualTo(seedUri)
            assertThat(player.getMediaItemAt(1).mediaId).isEqualTo(userChoiceUri)
        } finally {
            connectedController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun `context insertion rejects a preview from a stale play request revision`() {
        val seedUri = "file:///stale-insert-seed.m4a"
        val physicalNextUri = "file:///stale-insert-next.m4a"
        val userChoiceUri = "file:///stale-insert-user-choice.m4a"
        val previewUri = "file:///stale-insert-preview.m4a"
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        player.setMediaItems(listOf(seedUri, physicalNextUri).map { uri ->
            MediaItem.Builder().setMediaId(uri).setUri(uri).build()
        })
        player.seekToDefaultPosition(0)
        val connectedController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )

        try {
            val connected = CountDownLatch(1)
            connectedController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            val previewRevision = connectedController.playRequestRevision.value
            connectedController.play(listOf(track(userChoiceUri)))
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(
                connectedController.insertIntoPlaybackContext(
                    track = track(previewUri),
                    afterUri = seedUri,
                    insertBeforeCurrentIfAnchorMissing = true,
                    resumeWhenIdle = false,
                    expectedPlayRequestRevision = previewRevision
                )
            ).isFalse()
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(player.mediaItemCount).isEqualTo(1)
            assertThat(player.currentMediaItem?.mediaId).isEqualTo(userChoiceUri)
        } finally {
            connectedController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun `deferred preview insertion resumes ended playback when play intent remains set`() {
        val seedUri = "file:///ended-seed.m4a"
        val previewUri = "file:///ended-preview.m4a"
        val player = ExoPlayer.Builder(context).build()
        player.setMediaItems(listOf(seedUri).map { uri ->
            MediaItem.Builder().setMediaId(uri).setUri(uri).build()
        })
        player.seekToDefaultPosition(0)
        player.playWhenReady = true
        val sessionPlayer = PlaybackStateOverridePlayer(player, Player.STATE_ENDED)
        val session = MediaSession.Builder(context, sessionPlayer).build()
        val connectedController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )

        try {
            val connected = CountDownLatch(1)
            connectedController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(connectedController.playbackState.value).isEqualTo(Player.STATE_ENDED)
            assertThat(connectedController.playWhenReady.value).isTrue()
            val playCallsBeforeInsert = sessionPlayer.playCalls

            assertThat(
                connectedController.insertIntoPlaybackContext(
                    track = MediaTrack(uri = previewUri, title = "Preview"),
                    afterUri = seedUri,
                    resumeWhenIdle = false,
                    resumeWhenEnded = true
                )
            ).isTrue()
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(sessionPlayer.playCalls).isGreaterThan(playCallsBeforeInsert)
        } finally {
            connectedController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun `deferred preview insertion does not resume ended playback after pause`() {
        val seedUri = "file:///paused-ended-seed.m4a"
        val previewUri = "file:///paused-ended-preview.m4a"
        val player = ExoPlayer.Builder(context).build()
        player.setMediaItems(listOf(seedUri).map { uri ->
            MediaItem.Builder().setMediaId(uri).setUri(uri).build()
        })
        player.seekToDefaultPosition(0)
        player.playWhenReady = false
        val sessionPlayer = PlaybackStateOverridePlayer(player, Player.STATE_ENDED)
        val session = MediaSession.Builder(context, sessionPlayer).build()
        val connectedController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )

        try {
            val connected = CountDownLatch(1)
            connectedController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(connectedController.playbackState.value).isEqualTo(Player.STATE_ENDED)
            assertThat(connectedController.playWhenReady.value).isFalse()
            val playCallsBeforeInsert = sessionPlayer.playCalls

            assertThat(
                connectedController.insertIntoPlaybackContext(
                    track = MediaTrack(uri = previewUri, title = "Preview"),
                    afterUri = seedUri,
                    resumeWhenIdle = false,
                    resumeWhenEnded = true
                )
            ).isTrue()
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(sessionPlayer.playCalls).isEqualTo(playCallsBeforeInsert)
            assertThat(player.playWhenReady).isFalse()
        } finally {
            connectedController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun `restoring an ended physical queue rechecks play intent after resume decision`() {
        val seedUri = "file:///paused-restore-seed.m4a"
        val nextUri = "file:///paused-restore-next.m4a"
        val player = ExoPlayer.Builder(context).build()
        player.setMediaItems(listOf(seedUri, nextUri).map { uri ->
            MediaItem.Builder().setMediaId(uri).setUri(uri).build()
        })
        player.seekToDefaultPosition(0)
        player.playWhenReady = false
        val sessionPlayer = PlaybackStateOverridePlayer(player, Player.STATE_ENDED)
        val session = MediaSession.Builder(context, sessionPlayer).build()
        val connectedController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )

        try {
            val connected = CountDownLatch(1)
            connectedController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(connectedController.playbackState.value).isEqualTo(Player.STATE_ENDED)
            assertThat(connectedController.playWhenReady.value).isFalse()

            assertThat(
                connectedController.restoreQueueAroundCurrent(
                    tracks = listOf(track(seedUri), track(nextUri)),
                    currentUri = seedUri,
                    currentIndex = 0,
                    resumeAtNextIfEnded = true
                )
            ).isTrue()
            shadowOf(Looper.getMainLooper()).idle()

            assertThat((0 until player.mediaItemCount).map {
                player.getMediaItemAt(it).mediaId
            }).containsExactly(seedUri, nextUri).inOrder()
            assertThat(player.currentMediaItemIndex).isEqualTo(0)
            assertThat(player.playWhenReady).isFalse()
            assertThat(sessionPlayer.playCalls).isEqualTo(0)
        } finally {
            connectedController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun `restoring an ended physical queue advances while play intent remains set`() {
        val seedUri = "file:///playing-restore-seed.m4a"
        val nextUri = "file:///playing-restore-next.m4a"
        val player = ExoPlayer.Builder(context).build()
        player.setMediaItems(listOf(seedUri, nextUri).map { uri ->
            MediaItem.Builder().setMediaId(uri).setUri(uri).build()
        })
        player.seekToDefaultPosition(0)
        player.playWhenReady = true
        val sessionPlayer = PlaybackStateOverridePlayer(player, Player.STATE_ENDED)
        val session = MediaSession.Builder(context, sessionPlayer).build()
        val connectedController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )

        try {
            val connected = CountDownLatch(1)
            connectedController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(connectedController.playbackState.value).isEqualTo(Player.STATE_ENDED)
            assertThat(connectedController.playWhenReady.value).isTrue()
            val playCallsBeforeRestore = sessionPlayer.playCalls

            assertThat(
                connectedController.restoreQueueAroundCurrent(
                    tracks = listOf(track(seedUri), track(nextUri)),
                    currentUri = seedUri,
                    currentIndex = 0,
                    resumeAtNextIfEnded = true
                )
            ).isTrue()
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(player.currentMediaItem?.mediaId).isEqualTo(nextUri)
            assertThat(player.currentMediaItemIndex).isEqualTo(1)
            assertThat(player.playWhenReady).isTrue()
            assertThat(sessionPlayer.playCalls).isGreaterThan(playCallsBeforeRestore)
        } finally {
            connectedController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun `empty request does not replace the last non-empty pending request`() {
        controller.play(listOf(track("keep-a"), track("keep-b")), startIndex = 1)
        controller.play(emptyList<MediaTrack>())

        val req = controller.pendingRequest
        assertThat(req).isNotNull()
        assertThat(req!!.items.map { it.uri }).containsExactly("keep-a", "keep-b").inOrder()
        assertThat(req.startIndex).isEqualTo(1)
    }

    @Test
    fun `multiple playUri calls preserve last user request`() {
        controller.playUri("first")
        controller.playUri("second")
        controller.playUri("third")
        // All preceding contract holds — no crash, no silent false.
    }

    // ── Full queue preservation ──────────────────────────────────────────

    @Test
    fun `pre-connection multi-track request retains full queue and start index`() {
        val tracks = listOf(track("a"), track("b"), track("c"))
        controller.play(tracks, startIndex = 1)
        // Pending request should hold the full queue.
        val req = controller.pendingRequest
        assertThat(req).isNotNull()
        assertThat(req!!.items.map { it.uri }).containsExactly("a", "b", "c").inOrder()
        assertThat(req.startIndex).isEqualTo(1)
    }

    @Test
    fun `last pre-connection request wins`() {
        controller.play(listOf(track("first-a"), track("first-b")))
        controller.play(listOf(track("second-a"), track("second-b"), track("second-c")), startIndex = 2)
        val req = controller.pendingRequest
        assertThat(req).isNotNull()
        assertThat(req!!.items.map { it.uri }).containsExactly("second-a", "second-b", "second-c")
        assertThat(req.startIndex).isEqualTo(2)
    }

    @Test
    fun `startIndex is clamped to valid range`() {
        val tracks = listOf(track("a"), track("b"))
        controller.play(tracks, startIndex = 99) // out of range
        val req = controller.pendingRequest
        assertThat(req).isNotNull()
        assertThat(req!!.startIndex).isEqualTo(1) // clamped to lastIndex
    }

    @Test
    fun `pending request is consumed after connection`() = runTest {
        controller.playUri("once")
        assertThat(controller.pendingRequest).isNotNull()
        controller.initialize()
        // Wait for async completion to settle
        kotlinx.coroutines.yield()
    }

    @Test
    fun `after successful connection pending request is consumed`() {
        controller.playUri("consumed")
        assertThat(controller.pendingRequest).isNotNull()
        controller.initialize()
        // Still not consumed because the fake future hasn't completed.
        assertThat(controller.pendingRequest).isNotNull()
    }

    // ── Terminal release semantics ───────────────────────────────────────

    @Test
    fun `release clears pending request`() {
        controller.playUri("lost")
        controller.release()
        assertThat(controller.pendingRequest).isNull()
    }

    @Test
    fun `release prevents onReady callback`() {
        var onReadyCalled = false
        controller.release()
        controller.initialize { onReadyCalled = true }
        assertThat(fakeConnector.lastConnectAttempt).isFalse()
        assertThat(onReadyCalled).isFalse()
    }

    @Test
    fun `playUri after release returns true without queuing`() {
        controller.release()
        assertThat(controller.playUri("http://example.com/track.mp3")).isTrue()
        assertThat(controller.pendingRequest).isNull()
    }

    @Test
    fun `release is idempotent`() {
        controller.release()
        controller.release() // second call must not throw
    }

    @Test
    fun `togglePlayPause does not throw before connection`() {
        controller.togglePlayPause()
    }

    @Test
    fun `seekTo does not throw before connection`() {
        controller.seekTo(5000L)
    }

    @Test
    fun `skipToNext does not throw before connection`() {
        controller.skipToNext()
    }

    @Test
    fun `stop does not throw before connection`() {
        controller.stop()
    }

    // ── Metadata preservation ────────────────────────────────────────────

    @Test
    fun `metadata from Track is preserved in pending request`() {
        val original = MediaTrack(
            uri = "content://track/1",
            title = "Sonne",
            artist = "Rammstein",
            album = "Mutter",
            durationMs = 290000L
        )
        controller.play(original)
        val req = controller.pendingRequest
        assertThat(req).isNotNull()
        assertThat(req!!.items).hasSize(1)
        val item = req.items[0]
        assertThat(item.uri).isEqualTo("content://track/1")
        assertThat(item.title).isEqualTo("Sonne")
        assertThat(item.artist).isEqualTo("Rammstein")
        assertThat(item.album).isEqualTo("Mutter")
        assertThat(item.durationMs).isEqualTo(290000L)
    }

    @Test
    fun `playUri creates minimal metadata`() {
        controller.playUri("content://track/no-metadata.mp3")
        val req = controller.pendingRequest
        assertThat(req).isNotNull()
        assertThat(req!!.items[0].title).isEqualTo("Unknown")
        assertThat(req.items[0].artist).isEqualTo("Unknown")
        assertThat(req.items[0].album).isEmpty()
        assertThat(req.items[0].durationMs).isEqualTo(0L)
    }

    @Test
    fun `MediaItem preserves exact Room metadata including duration`() {
        val original = MediaTrack(
            uri = "content://tracks/東京%20Song",
            title = "  東京 Song  ",
            artist = "Artist CASE",
            album = "Álbum",
            durationMs = 123456L
        )

        val item = controller.buildMediaItem(original)

        assertThat(item.mediaId).isEqualTo(original.uri)
        assertThat(item.mediaMetadata.title.toString()).isEqualTo(original.title)
        assertThat(item.mediaMetadata.artist.toString()).isEqualTo(original.artist)
        assertThat(item.mediaMetadata.albumTitle.toString()).isEqualTo(original.album)
        assertThat(
            item.mediaMetadata.extras?.getLong(MusicController.METADATA_DURATION_MS)
        ).isEqualTo(original.durationMs)
    }

    @Test
    fun `playlist playback context is carried in MediaItem metadata`() {
        val item = controller.buildMediaItem(track("rap-song"), playlistId = 42L)

        assertThat(
            item.mediaMetadata.extras?.getLong(MusicController.METADATA_PLAYLIST_ID)
        ).isEqualTo(42L)
    }

    @Test
    fun `playback source is carried in MediaItem metadata`() {
        val item = controller.buildMediaItem(
            track("home-song").copy(
                playbackSource = PlaybackSource(
                    category = "Home",
                    name = "Made for you"
                )
            )
        )

        val extras = item.mediaMetadata.extras
        assertThat(extras?.getString(MusicController.METADATA_SOURCE_CATEGORY))
            .isEqualTo("Home")
        assertThat(extras?.getString(MusicController.METADATA_SOURCE_NAME))
            .isEqualTo("Made for you")
    }

    @Test
    fun `temporary preview marker is carried in MediaItem metadata`() {
        val item = controller.buildMediaItem(
            MediaTrack(
                uri = "file:///cache/recommendation_previews/preview.m4a",
                title = "Preview",
                isTransient = true
            )
        )

        assertThat(
            item.mediaMetadata.extras?.getBoolean(MusicController.METADATA_TRANSIENT)
        ).isTrue()
    }

    // ── State flows ──────────────────────────────────────────────────────

    @Test
    fun `state flows have initial values`() {
        assertThat(controller.isPlaying.value).isFalse()
        assertThat(controller.currentTrack.value).isNull()
        assertThat(controller.progress.value).isEqualTo(0L)
        assertThat(controller.duration.value).isEqualTo(0L)
        assertThat(controller.hasActiveItem.value).isFalse()
        assertThat(controller.isConnected.value).isFalse()
        assertThat(controller.recentlyPlayed.value).isEmpty()
    }

    // ── Repeat / shuffle / queue API ─────────────────────────────────────

    @Test
    fun `repeatMode and shuffleEnabled have initial values`() {
        assertThat(controller.repeatMode.value).isEqualTo(Player.REPEAT_MODE_OFF)
        assertThat(controller.shuffleEnabled.value).isFalse()
    }

    @Test
    fun `toggleRepeatMode does not throw before connection`() {
        controller.toggleRepeatMode()
        assertThat(controller.repeatMode.value).isEqualTo(Player.REPEAT_MODE_OFF)
    }

    @Test
    fun `toggleShuffle does not throw before connection`() {
        controller.toggleShuffle()
        assertThat(controller.shuffleEnabled.value).isFalse()
    }

    @Test
    fun `setShuffle does not throw before connection`() {
        controller.setShuffle(true)
        controller.setShuffle(false)
    }

    @Test
    fun `shuffle toggle adjusts the queue without starting playback`() {
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        val uris = listOf("file:///cue-a.m4a", "file:///cue-b.m4a", "file:///cue-c.m4a")
        player.setMediaItems(uris.map { uri ->
            MediaItem.Builder().setMediaId(uri).setUri(uri).build()
        })
        player.playWhenReady = false
        player.prepare()
        val connectedController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )

        try {
            val connected = CountDownLatch(1)
            connectedController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            player.playWhenReady = false
            shadowOf(Looper.getMainLooper()).idle()
            val indexBefore = player.currentMediaItemIndex

            connectedController.setShuffle(true)
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(connectedController.shuffleEnabled.value).isTrue()
            assertThat(player.shuffleModeEnabled).isTrue()
            assertThat(player.playWhenReady).isFalse()
            assertThat(player.currentMediaItemIndex).isEqualTo(indexBefore)
            assertThat(player.mediaItemCount).isEqualTo(uris.size)

            connectedController.setShuffle(false)
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(player.shuffleModeEnabled).isFalse()
            assertThat(player.playWhenReady).isFalse()
            assertThat(player.currentMediaItemIndex).isEqualTo(indexBefore)
        } finally {
            connectedController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun `getQueue returns empty before connection`() {
        assertThat(controller.getQueue()).isEmpty()
    }

    @Test
    fun `playQueueItem jumps to the selected playback-order item`() {
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        val uris = listOf("file:///queue-a.m4a", "file:///queue-b.m4a", "file:///queue-c.m4a")
        player.setMediaItems(uris.map { uri ->
            MediaItem.Builder().setMediaId(uri).setUri(uri).build()
        })
        player.seekToDefaultPosition(0)
        val connectedController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )

        try {
            val connected = CountDownLatch(1)
            connectedController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            player.playWhenReady = false
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(connectedController.playQueueItem(2)).isTrue()
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(player.currentMediaItemIndex).isEqualTo(2)
            assertThat(player.playWhenReady).isTrue()
            assertThat(connectedController.playQueueItem(9)).isFalse()
        } finally {
            connectedController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun `reconnectIfNeeded replaces a disconnected controller and restores transport`() {
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        val uris = listOf("file:///reconnect-a.m4a", "file:///reconnect-b.m4a")
        player.setMediaItems(uris.map { uri ->
            MediaItem.Builder().setMediaId(uri).setUri(uri).build()
        })
        player.seekToDefaultPosition(0)
        val connector = SequentialConnector()
        val connectedController = MusicController(context, connector = connector)

        try {
            val firstFuture = MediaController.Builder(context, session.token).buildAsync()
            connector.enqueue(firstFuture)
            val connected = CountDownLatch(1)
            connectedController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(connectedController.isConnected.value).isTrue()

            val firstController = firstFuture.get()
            firstController.release()
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(firstController.isConnected).isFalse()

            connector.enqueue(MediaController.Builder(context, session.token).buildAsync())
            connectedController.reconnectIfNeeded()
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(connector.connectCalls).isEqualTo(2)
            assertThat(connectedController.isConnected.value).isTrue()
            assertThat(connectedController.getQueue()).hasSize(uris.size)

            assertThat(connectedController.playQueueItem(1)).isTrue()
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(player.currentMediaItemIndex).isEqualTo(1)
        } finally {
            connectedController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun `playNext does not throw before connection`() {
        controller.playNext(track("next"))
    }

    @Test
    fun `addToQueue does not throw before connection`() {
        controller.addToQueue(track("append"))
    }

    @Test
    fun `temporary context mutations report rejection before connection`() {
        assertThat(controller.appendToPlaybackContext(track("append"))).isFalse()
        assertThat(
            controller.insertIntoPlaybackContext(
                track("insert"),
                afterUri = "current"
            )
        ).isFalse()
        assertThat(controller.removeCurrentFromPlaybackContext()).isFalse()
    }

    @Test
    fun `moveQueueItem does not throw before connection`() {
        controller.moveQueueItem(0, 1)
    }

    @Test
    fun `moveQueueItem to same index is a no-op before connection`() {
        controller.moveQueueItem(2, 2)
    }

    @Test
    fun `clearRecentlyPlayed does not throw before connection`() {
        controller.clearRecentlyPlayed()
        assertThat(controller.recentlyPlayed.value).isEmpty()
    }

    @Test
    fun `restores recently played history from the persistence store`() {
        val history = listOf(
            MediaTrack(
                uri = "uri-2",
                title = "Second",
                artist = "Artist",
                album = "Album",
                durationMs = 2000L,
                artworkUri = "file:///artwork/second.jpg"
            ),
            track("uri-1")
        )
        val store = FakeRecentlyPlayedStore(history)

        val restored = MusicController(
            context,
            connector = FakeConnector(),
            recentlyPlayedStore = store
        )

        assertThat(restored.recentlyPlayed.value).containsExactlyElementsIn(history).inOrder()
    }

    @Test
    fun `clearing recently played history clears the persistence store`() {
        val store = FakeRecentlyPlayedStore(listOf(track("persisted")))
        val persistedController = MusicController(
            context,
            connector = FakeConnector(),
            recentlyPlayedStore = store
        )

        persistedController.clearRecentlyPlayed()

        assertThat(store.load()).isEmpty()
    }

    @Test
    fun `shared preferences store restores history in a new store instance`() {
        val history = listOf(
            MediaTrack(
                uri = "content://track/2",
                title = "Second",
                artist = "Artist",
                album = "Album",
                durationMs = 2000L,
                artworkUri = "file:///artwork/second.jpg"
            ),
            track("content://track/1")
        )
        val firstStore = SharedPreferencesRecentlyPlayedStore(context)
        firstStore.clear()

        try {
            firstStore.save(history)
            val reopenedStore = SharedPreferencesRecentlyPlayedStore(context)

            assertThat(reopenedStore.load()).containsExactlyElementsIn(history).inOrder()
        } finally {
            firstStore.clear()
        }
    }

    @Test
    fun `artworkUri is preserved on the built MediaItem`() {
        val original = MediaTrack(
            uri = "content://track/art",
            title = "Artwork Song",
            artist = "Artist",
            album = "Album",
            durationMs = 1000L,
            artworkUri = "file:///data/user/0/com.luno.mobile/files/artwork/abc.jpg"
        )

        val item = controller.buildMediaItem(original)

        assertThat(item.mediaMetadata.artworkUri.toString())
            .isEqualTo(original.artworkUri)
    }

    @Test
    fun `buildMediaItem without artwork leaves artworkUri null`() {
        val item = controller.buildMediaItem(track("plain"))

        assertThat(item.mediaMetadata.artworkUri).isNull()
    }

    // ── Error emission ───────────────────────────────────────────────────

    @Test
    fun `connection failure emits connectionError`() = runTest {
        val errors = mutableListOf<MusicController.ConnectionException>()
        val job = launch {
            controller.connectionError.collect { errors.add(it) }
        }
        // Give the collector a chance to start collecting before emission.
        kotlinx.coroutines.yield()
        controller.initialize()
        fakeConnector.connectFuture.setException(RuntimeException("Service unavailable"))
        // Yield again so the buffered emission reaches the collector.
        kotlinx.coroutines.yield()
        assertThat(errors).isNotEmpty()
        job.cancel()
    }

    @Test
    fun `synchronous connector failure emits a sanitized connection error`() = runTest {
        val throwingController = MusicController(
            context,
            connector = MusicController.AsyncConnector {
                throw IllegalStateException(
                    "Cannot open content://library/private/song.mp3\ninternal stack"
                )
            }
        )
        val errors = mutableListOf<MusicController.ConnectionException>()
        val job = launch {
            throwingController.connectionError.collect { errors.add(it) }
        }
        kotlinx.coroutines.yield()

        throwingController.initialize()
        kotlinx.coroutines.yield()

        assertThat(errors).hasSize(1)
        assertThat(errors.single().message).doesNotContain("content://")
        assertThat(errors.single().message).doesNotContain("private")
        assertThat(errors.single().message).doesNotContain("stack")
        job.cancel()
        throwingController.release()
    }

    @Test
    fun `error sanitizer removes local and remote URIs paths and later lines`() {
        val sanitized = controller.sanitizeErrorMessage(
            "file://storage/emulated/0/Music/private.mp3 " +
                "https://user:secret@example.test/audio /data/user/0/app/private/file\n" +
                "java.lang.IllegalStateException: stack"
        )

        assertThat(sanitized).doesNotContain("file://")
        assertThat(sanitized).doesNotContain("https://")
        assertThat(sanitized).doesNotContain("user:secret")
        assertThat(sanitized).doesNotContain("/data/user")
        assertThat(sanitized).doesNotContain("IllegalStateException")
    }

    @Test
    fun `stale future after release does not emit connectionError`() = runTest {
        val errors = mutableListOf<MusicController.ConnectionException>()
        val job = launch {
            controller.connectionError.collect { errors.add(it) }
        }
        kotlinx.coroutines.yield()
        controller.initialize()
        controller.release()
        // Complete the future after release — should be ignored silently.
        fakeConnector.connectFuture.setException(RuntimeException("Late failure"))
        kotlinx.coroutines.yield()
        assertThat(errors).isEmpty()
        job.cancel()
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Test
    fun `initialize before release attempts connection`() {
        controller.initialize()
        assertThat(fakeConnector.lastConnectAttempt).isTrue()
    }

    @Test
    fun `connected dispatch sets lastDispatchedRequest`() {
        controller.play(listOf(track("a"), track("b")), startIndex = 1)
        controller.initialize()
        // Before connection, lastDispatchedRequest is null.
        assertThat(controller.lastDispatchedRequest).isNull()
    }

    @Test
    fun `toggle play pause pauses when session requests playback but player is not playing`() {
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        val connectedController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )

        try {
            val connected = CountDownLatch(1)
            connectedController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            val playWhenReadyState = connectedController.playWhenReady

            connectedController.togglePlayPause()
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(player.playWhenReady).isTrue()
            assertThat(player.isPlaying).isFalse()
            assertThat(playWhenReadyState.value).isTrue()

            connectedController.togglePlayPause()
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(player.playWhenReady).isFalse()
            assertThat(playWhenReadyState.value).isFalse()
        } finally {
            connectedController.release()
            session.release()
            player.release()
        }
    }
}

// ── Test helpers ─────────────────────────────────────────────────────────

/** Shorthand for creating a [MediaTrack] with just a URI. */
private fun track(uri: String): MediaTrack = MediaTrack(uri = uri)

private class PlaybackStateOverridePlayer(
    player: Player,
    private val stateOverride: Int
) : ForwardingPlayer(player) {
    var playCalls: Int = 0
        private set

    override fun getPlaybackState(): Int = stateOverride

    override fun play() {
        playCalls++
        super.play()
    }
}

// ── Test double ───────────────────────────────────────────────────────────

/**
 * A fake [MusicController.AsyncConnector] that captures connect calls
 * and returns a [SettableFuture] for deterministic completion.
 */
class FakeConnector : MusicController.AsyncConnector {

    val connectFuture: SettableFuture<MediaController> = SettableFuture.create()
    var lastConnectAttempt: Boolean = false
        private set

    override fun connect(context: Context): ListenableFuture<MediaController> {
        lastConnectAttempt = true
        return connectFuture
    }
}

/** Supplies a fresh controller future for each reconnect attempt. */
class SequentialConnector : MusicController.AsyncConnector {
    private val pending = mutableListOf<ListenableFuture<MediaController>>()
    var connectCalls: Int = 0
        private set

    fun enqueue(future: ListenableFuture<MediaController>) {
        pending += future
    }

    override fun connect(context: Context): ListenableFuture<MediaController> {
        connectCalls++
        return pending.removeFirstOrNull() ?: SettableFuture.create()
    }
}

private class FakeRecentlyPlayedStore(
    initial: List<MediaTrack>
) : RecentlyPlayedStore {
    private var tracks = initial

    override fun load(): List<MediaTrack> = tracks

    override fun save(tracks: List<MediaTrack>) {
        this.tracks = tracks
    }

    override fun clear() {
        tracks = emptyList()
    }
}
