package com.luno.mobile.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.luno.mobile.data.db.AppDatabase
import com.luno.mobile.data.db.entity.DownloadState
import com.luno.mobile.data.db.entity.Playlist
import com.luno.mobile.data.discovery.LastfmTrack
import com.luno.mobile.data.repository.DownloadRepository
import com.luno.mobile.data.repository.PlaylistRepository
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class RecommendationPreviewManagerTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var downloadRepository: DownloadRepository
    private lateinit var appScope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        downloadRepository = DownloadRepository(
            downloadJobDao = database.downloadJobDao(),
            context = context,
            playlistDao = database.playlistDao(),
            trackDao = database.trackDao(),
            database = database
        )
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        database.close()
        File(context.cacheDir, DownloadRepository.PREVIEW_DOWNLOAD_DIR).deleteRecursively()
    }

    @Test
    fun recommendationKey_isStableAcrossCaseAndOuterWhitespace() {
        val first = LastfmTrack("  Artist Name ", " Song Title ", 0.9)
        val second = LastfmTrack("artist name", "song title", 0.8)

        assertThat(RecommendationPreviewManager.recommendationKey(first))
            .isEqualTo(RecommendationPreviewManager.recommendationKey(second))
    }

    @Test
    fun recommendationKey_collapsesPunctuationAccentsAndUnicodeSpacing() {
        val first = LastfmTrack("Taco", "Puttin' on the Ritz", 0.9)
        val duplicate = LastfmTrack("  tacó ", "Puttin\u00a0on the Ritz", 0.8)

        assertThat(RecommendationPreviewManager.recommendationKey(first))
            .isEqualTo(RecommendationPreviewManager.recommendationKey(duplicate))
    }

    @Test
    fun extensionForMimeType_mapsPlayableAudioFormats() {
        assertThat(RecommendationPreviewManager.extensionForMimeType("audio/mp4; codecs=mp4a"))
            .isEqualTo("m4a")
        assertThat(RecommendationPreviewManager.extensionForMimeType("audio/webm; codecs=opus"))
            .isEqualTo("opus")
        assertThat(RecommendationPreviewManager.extensionForMimeType("audio/mpeg"))
            .isEqualTo("mp3")
    }

    @Test
    fun prefetchThreshold_startsAtExactlySeventyFivePercent() {
        assertThat(
            RecommendationPreviewManager.hasReachedPrefetchThreshold(
                progressMs = 749,
                durationMs = 1_000
            )
        ).isFalse()
        assertThat(
            RecommendationPreviewManager.hasReachedPrefetchThreshold(
                progressMs = 750,
                durationMs = 1_000
            )
        ).isTrue()
        assertThat(
            RecommendationPreviewManager.hasReachedPrefetchThreshold(
                progressMs = 1_000,
                durationMs = 0
            )
        ).isFalse()
    }

    @Test
    fun nextFivePreviews_prepareImmediatelyBeforeTheRollingWindowGate() {
        assertThat(RecommendationPreviewManager.shouldWaitForNextPreview(0)).isFalse()
        assertThat(RecommendationPreviewManager.shouldWaitForNextPreview(1)).isFalse()
        assertThat(
            RecommendationPreviewManager.shouldWaitForNextPreview(
                RecommendationPreviewManager.IMMEDIATE_PREVIEW_AHEAD_COUNT
            )
        ).isFalse()
        assertThat(
            RecommendationPreviewManager.shouldWaitForNextPreview(
                RecommendationPreviewManager.IMMEDIATE_PREVIEW_AHEAD_COUNT + 1
            )
        ).isTrue()
    }

    @Test
    fun nonDiscoverPreview_preparesSelectedAndFirstFiveRecommendations() {
        val recommendations = listOf(
            LastfmTrack("Artist 1", "Song 1", 0.9),
            LastfmTrack("Artist 2", "Song 2", 0.8),
            LastfmTrack("Artist 3", "Song 3", 0.7)
        )

        assertThat(
            RecommendationPreviewManager.recommendationsForSession(
                recommendations = recommendations,
                startIndex = 1,
                discoverMode = false
            )
        ).containsExactly(recommendations[1], recommendations[2]).inOrder()
    }

    @Test
    fun discoverPreview_queuesTheSelectedRecommendationAndItsFollowingItems() {
        val recommendations = listOf(
            LastfmTrack("Artist 1", "Song 1", 0.9),
            LastfmTrack("Artist 2", "Song 2", 0.8),
            LastfmTrack("Artist 3", "Song 3", 0.7)
        )

        assertThat(
            RecommendationPreviewManager.recommendationsForSession(
                recommendations = recommendations,
                startIndex = 1,
                discoverMode = true
            )
        ).containsExactly(recommendations[1], recommendations[2]).inOrder()
    }

    @Test
    fun nonDiscoverPreview_keepsTheSelectedRecommendationAheadOfTheFirstFive() {
        val recommendations = (0..11).map { index ->
            LastfmTrack("Artist $index", "Song $index", 0.9 - index / 10.0)
        }

        val prepared = RecommendationPreviewManager.recommendationsForSession(
            recommendations = recommendations,
            startIndex = 6,
            discoverMode = false
        )

        assertThat(prepared).hasSize(6)
        assertThat(prepared.first()).isEqualTo(recommendations[6])
        assertThat(prepared.drop(1))
            .containsExactlyElementsIn(recommendations.subList(7, 12))
    }

    @Test
    fun restartingFromAnotherRecommendation_retainsAllPreparedRecommendationsForBackNavigation() {
        val recommendations = (0..6).map { index ->
            LastfmTrack("Artist $index", "Song $index", 0.9 - index / 10.0)
        }
        val preparedKeys = recommendations
            .take(5)
            .map(RecommendationPreviewManager::recommendationKey)
            .toSet()

        val retainedKeys = RecommendationPreviewManager.retainPreparedRecommendationKeys(
            preparedKeys = preparedKeys,
            recommendations = recommendations
        )

        assertThat(retainedKeys).containsExactlyElementsIn(
            recommendations.take(5).map(RecommendationPreviewManager::recommendationKey)
        )
    }

    @Test
    fun restartedPreview_withTransientCurrentTrack_isNotTreatedAsPlaybackIntervention() {
        val seed = MediaTrack(uri = "file:///library/seed.m4a", isTransient = false)
        val playingPreview = MediaTrack(
            uri = "file:///cache/recommendation_previews/preview.m4a",
            isTransient = true
        )
        val otherPhysical = MediaTrack(uri = "file:///library/other.m4a", isTransient = false)

        assertThat(
            RecommendationPreviewManager.isNormalPlaybackSupersedingPreview(
                newerPlayRequest = false,
                currentTrack = playingPreview,
                initialNormalTrackUri = seed.uri,
                deferredInitialPlayback = false,
                observedPreviewTrack = false,
                previewPlaybackStarted = false
            )
        ).isFalse()
        assertThat(
            RecommendationPreviewManager.isNormalPlaybackSupersedingPreview(
                newerPlayRequest = false,
                currentTrack = seed,
                initialNormalTrackUri = seed.uri,
                deferredInitialPlayback = false,
                observedPreviewTrack = false,
                previewPlaybackStarted = false
            )
        ).isFalse()
        assertThat(
            RecommendationPreviewManager.isNormalPlaybackSupersedingPreview(
                newerPlayRequest = false,
                currentTrack = otherPhysical,
                initialNormalTrackUri = seed.uri,
                deferredInitialPlayback = false,
                observedPreviewTrack = false,
                previewPlaybackStarted = false
            )
        ).isTrue()
        assertThat(
            RecommendationPreviewManager.isNormalPlaybackSupersedingPreview(
                newerPlayRequest = true,
                currentTrack = seed,
                initialNormalTrackUri = seed.uri,
                deferredInitialPlayback = false,
                observedPreviewTrack = false,
                previewPlaybackStarted = false
            )
        ).isTrue()
    }

    @Test
    fun discoverHandoff_defersOnlyForTheCurrentNormalSeed() {
        val normal = MediaTrack(uri = "seed", isTransient = false)
        val transient = normal.copy(isTransient = true)

        assertThat(
            RecommendationPreviewManager.shouldDeferDiscoverHandoff(
                requested = true,
                currentTrack = normal,
                seedUri = "seed"
            )
        ).isTrue()
        assertThat(
            RecommendationPreviewManager.shouldDeferDiscoverHandoff(
                requested = true,
                currentTrack = transient,
                seedUri = "seed"
            )
        ).isFalse()
        assertThat(
            RecommendationPreviewManager.shouldDeferDiscoverHandoff(
                requested = true,
                currentTrack = normal,
                seedUri = "other"
            )
        ).isFalse()
    }

    @Test
    fun nextDuringDiscoverHandoff_isInterceptedBeforeTheFirstPreviewStarts() {
        val normal = MediaTrack(uri = "seed")

        assertThat(
            RecommendationPreviewManager.isDiscoverHandoffPending(
                discoverMode = true,
                currentTrack = normal,
                deferredInitialPlayback = true,
                initialNormalTrackUri = "seed",
                discoverRequestPending = false,
                discoverStartedUri = null
            )
        ).isTrue()
        assertThat(
            RecommendationPreviewManager.isDiscoverHandoffPending(
                discoverMode = true,
                currentTrack = normal,
                deferredInitialPlayback = false,
                initialNormalTrackUri = null,
                discoverRequestPending = true,
                discoverStartedUri = "seed"
            )
        ).isTrue()
        assertThat(
            RecommendationPreviewManager.isDiscoverHandoffPending(
                discoverMode = true,
                currentTrack = normal.copy(isTransient = true),
                deferredInitialPlayback = true,
                initialNormalTrackUri = "seed",
                discoverRequestPending = false,
                discoverStartedUri = null
            )
        ).isFalse()

        assertThat(
            RecommendationPreviewManager.shouldHandleDiscoverNext(
                discoverMode = true,
                handoffPending = true,
                nextRecommendationAvailable = false,
                extensionPending = false
            )
        ).isTrue()
        assertThat(
            RecommendationPreviewManager.shouldHandleDiscoverNext(
                discoverMode = false,
                handoffPending = true,
                nextRecommendationAvailable = true,
                extensionPending = true
            )
        ).isFalse()
    }

    @Test
    fun nextDuringDiscoverPageLoad_staysInsideRecommendations() {
        assertThat(
            RecommendationPreviewManager.shouldHandleDiscoverNext(
                discoverMode = true,
                handoffPending = false,
                nextRecommendationAvailable = false,
                extensionPending = true
            )
        ).isTrue()
        assertThat(RecommendationPreviewManager.nextDiscoverRecommendationIndex(0, 3))
            .isEqualTo(1)
        assertThat(RecommendationPreviewManager.nextDiscoverRecommendationIndex(2, 3))
            .isNull()
    }

    @Test
    fun recommendationWindow_keepsFiveItemsAheadOfCurrentRecommendation() {
        assertThat(
            RecommendationPreviewManager.minimumCurrentIndexForRecommendation(6)
        ).isEqualTo(1)
        assertThat(
            RecommendationPreviewManager.minimumCurrentIndexForRecommendation(10)
        ).isEqualTo(5)
        assertThat(
            RecommendationPreviewManager.minimumCurrentIndexForRecommendation(3)
        ).isEqualTo(0)
    }

    @Test
    fun emptyPlaybackContext_afterObservedPreview_keepsPreparationAlive() {
        assertThat(
            RecommendationPreviewManager.shouldContinueAfterPlaybackGap(
                observedPreviousTrack = true,
                currentTrack = null
            )
        ).isTrue()
        assertThat(
            RecommendationPreviewManager.shouldContinueAfterPlaybackGap(
                observedPreviousTrack = false,
                currentTrack = null
            )
        ).isFalse()
        assertThat(
            RecommendationPreviewManager.shouldContinueAfterPlaybackGap(
                observedPreviousTrack = true,
                currentTrack = MediaTrack(uri = "preview")
            )
        ).isFalse()
    }


    @Test
    fun discoverMode_toggleIsProcessState() {
        val manager = RecommendationPreviewManager(
            context = context,
            appScope = appScope,
            downloadRepository = downloadRepository,
            playlistRepository = PlaylistRepository(
                database.playlistDao(),
                database.trackDao()
            )
        )

        manager.setDiscoverMode(true)
        assertThat(manager.state.value.discoverMode).isTrue()

        manager.setDiscoverMode(false)
        assertThat(manager.state.value.discoverMode).isFalse()
    }

    @Test
    fun continuationAfterCurrent_keepsOnlyLaterNonPreviewQueueItems() {
        val queue = listOf(
            MediaTrack(uri = "current"),
            MediaTrack(uri = "next"),
            MediaTrack(uri = "temporary", isTransient = true),
            MediaTrack(uri = "later")
        )

        assertThat(
            RecommendationPreviewManager.continuationAfterCurrent(queue, "current")
                ?.map { it.uri }
        ).containsExactly("next", "later").inOrder()
    }

    @Test
    fun unpreparedSave_isQueuedOnceAndBecomesPermanentOnlyAfterCompletion() = runBlocking {
        val playlistId = database.playlistDao().insertPlaylist(Playlist(name = "Saved previews"))
        val recommendation = LastfmTrack("Artist", "Song", 0.9)
        var previewResolverCalls = 0
        var durableResolverCalls = 0
        val manager = RecommendationPreviewManager(
            context = context,
            appScope = appScope,
            downloadRepository = downloadRepository,
            playlistRepository = PlaylistRepository(
                database.playlistDao(),
                database.trackDao()
            ),
            resolver = {
                previewResolverCalls++
                Result.failure(AssertionError("Preview resolver must not handle durable saves"))
            },
            durableResolver = {
                durableResolverCalls++
                Result.success(
                    ResolvedRecommendation(
                        sourceUrl = "https://example.com/audio.m4a",
                        thumbnailUrl = "https://example.com/art.jpg",
                        mimeType = "audio/mp4"
                    )
                )
            }
        )
        val key = RecommendationPreviewManager.recommendationKey(recommendation)

        val firstSave = manager.saveToPlaylist(recommendation, playlistId, null)
        val duplicateSave = manager.saveToPlaylist(recommendation, playlistId, null)

        assertThat(firstSave.getOrThrow()).isInstanceOf(RecommendationSaveOutcome.Queued::class.java)
        assertThat(duplicateSave.isFailure).isTrue()
        assertThat(previewResolverCalls).isEqualTo(0)
        assertThat(durableResolverCalls).isEqualTo(1)
        assertThat(manager.state.value.queuedKeys).containsExactly(key)
        assertThat(manager.state.value.permanentKeys).doesNotContain(key)

        val jobId = (firstSave.getOrThrow() as RecommendationSaveOutcome.Queued).jobId
        database.downloadJobDao().markCompleted(
            jobId,
            DownloadState.COMPLETED,
            "file:///durable/song.m4a",
            System.currentTimeMillis()
        )
        awaitPreviewState(manager) { key in it.permanentKeys }

        assertThat(manager.state.value.queuedKeys).doesNotContain(key)
        assertThat(manager.state.value.saveFailures).doesNotContainKey(key)
    }

    @Test
    fun discoverIsolationRemovesPastPhysicalItemsBeforeRepeatAllCanWrap() = runBlocking {
        val pastUri = "file:///past-physical.m4a"
        val seedUri = "file:///repeat-all-seed.m4a"
        val physicalNextUri = "file:///repeat-all-next.m4a"
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        player.setMediaItems(listOf(pastUri, seedUri, physicalNextUri).map { uri ->
            MediaItem.Builder().setMediaId(uri).setUri(uri).build()
        })
        player.seekToDefaultPosition(1)
        player.repeatMode = Player.REPEAT_MODE_ALL
        shadowOf(Looper.getMainLooper()).idle()
        val musicController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )
        val manager = RecommendationPreviewManager(
            context = context,
            appScope = appScope,
            downloadRepository = downloadRepository,
            playlistRepository = PlaylistRepository(
                database.playlistDao(),
                database.trackDao()
            ),
            resolver = {
                delay(30_000)
                Result.failure(IllegalStateException("Hold preview resolution"))
            }
        )

        try {
            val connected = CountDownLatch(1)
            musicController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(musicController.currentTrack.value?.uri).isEqualTo(seedUri)
            manager.attach(musicController)
            manager.setDiscoverMode(
                enabled = true,
                recommendations = listOf(LastfmTrack("Repeat Artist", "Ready Later", 0.9))
            )
            awaitPreviewState(manager) { it.active }
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(player.mediaItemCount).isEqualTo(1)
            assertThat(player.currentMediaItem?.mediaId).isEqualTo(seedUri)
            assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ALL)

            manager.setDiscoverMode(enabled = false)
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(player.mediaItemCount).isEqualTo(3)
            assertThat((0 until player.mediaItemCount).map {
                player.getMediaItemAt(it).mediaId
            }).containsExactly(pastUri, seedUri, physicalNextUri).inOrder()
            assertThat(player.currentMediaItemIndex).isEqualTo(1)
            assertThat(player.playWhenReady).isFalse()
            assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ALL)
            manager.detach(musicController)
        } finally {
            musicController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun deferredQueueRestoreDoesNotAppendOverANewerPlayRequest() = runBlocking {
        val seedUri = "file:///restore-race-seed.m4a"
        val physicalNextUri = "file:///restore-race-next.m4a"
        val userChoiceUri = "file:///restore-race-user-choice.m4a"
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        player.setMediaItems(listOf(seedUri, physicalNextUri).map { uri ->
            MediaItem.Builder().setMediaId(uri).setUri(uri).build()
        })
        player.seekToDefaultPosition(0)
        shadowOf(Looper.getMainLooper()).idle()
        val musicController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )
        val fetchStarted = CompletableDeferred<Unit>()
        val fetchResult = CompletableDeferred<Result<List<LastfmTrack>>>()
        val manager = RecommendationPreviewManager(
            context = context,
            appScope = appScope,
            downloadRepository = downloadRepository,
            playlistRepository = PlaylistRepository(
                database.playlistDao(),
                database.trackDao()
            ),
            discoverRecommendations = {
                fetchStarted.complete(Unit)
                fetchResult.await()
            }
        )

        try {
            val connected = CountDownLatch(1)
            musicController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            manager.attach(musicController)
            manager.setDiscoverMode(enabled = true)
            withTimeout(5_000) { fetchStarted.await() }
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(player.mediaItemCount).isEqualTo(1)

            withContext(Dispatchers.Default) {
                manager.setDiscoverMode(enabled = false)
            }
            musicController.play(
                listOf(MediaTrack(uri = userChoiceUri, title = "New Choice", artist = "User"))
            )
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(musicController.playRequestRevision.value).isGreaterThan(0L)
            assertThat(player.mediaItemCount).isEqualTo(1)
            assertThat(player.currentMediaItem?.mediaId).isEqualTo(userChoiceUri)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            manager.detach(musicController)
            musicController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun discoverHandoff_holdsPhysicalQueueUntilFirstPreviewIsReady() {
        val seedUri = "file:///seed.m4a"
        val physicalNextUri = "file:///physical-next.m4a"
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        player.setMediaItems(
            listOf(seedUri, physicalNextUri).map { uri ->
                MediaItem.Builder().setMediaId(uri).setUri(uri).build()
            }
        )
        player.seekToDefaultPosition(0)
        shadowOf(Looper.getMainLooper()).idle()
        val musicController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )

        try {
            val connected = CountDownLatch(1)
            musicController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            val manager = RecommendationPreviewManager(
                context = context,
                appScope = appScope,
                downloadRepository = downloadRepository,
                playlistRepository = PlaylistRepository(
                    database.playlistDao(),
                    database.trackDao()
                ),
                resolver = {
                    delay(30_000)
                    Result.failure(IllegalStateException("Keep the first preview pending"))
                }
            )
            manager.attach(musicController)

            manager.setDiscoverMode(
                enabled = true,
                recommendations = listOf(LastfmTrack("Recommended Artist", "Recommended", 0.9))
            )
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(player.currentMediaItem?.mediaId).isEqualTo(seedUri)
            assertThat(player.mediaItemCount).isEqualTo(1)

            manager.setDiscoverMode(enabled = false)
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(player.mediaItemCount).isEqualTo(2)
            manager.detach(musicController)
        } finally {
            musicController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun discoverHandoff_advancesQueuedPreviewWhenSeedEndsDuringResolution() = runBlocking {
        val seedUri = "file:///handoff-race-seed.m4a"
        val physicalNextUri = "file:///handoff-race-next.m4a"
        val previewUri = "file:///handoff-race-preview.m4a"
        val recommendation = LastfmTrack("Race Artist", "Race Preview", 0.9)
        val player = ExoPlayer.Builder(context).build()
        player.setMediaItems(listOf(seedUri, physicalNextUri).map { uri ->
            MediaItem.Builder().setMediaId(uri).setUri(uri).build()
        })
        player.seekToDefaultPosition(0)
        player.playWhenReady = true
        val sessionPlayer = ControlledPlaybackStatePlayer(player, Player.STATE_READY)
        val session = MediaSession.Builder(context, sessionPlayer).build()
        val musicController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )
        val raceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val previewDownloadReturned = CompletableDeferred<Unit>()
        val manager = RecommendationPreviewManager(
            context = context,
            appScope = raceScope,
            downloadRepository = downloadRepository,
            playlistRepository = PlaylistRepository(
                database.playlistDao(),
                database.trackDao()
            ),
            resolver = {
                Result.success(
                    ResolvedRecommendation(
                        sourceUrl = "https://example.com/race-preview.m4a",
                        thumbnailUrl = "",
                        mimeType = "audio/mp4"
                    )
                )
            },
            previewDownloader = { _, _, _, target ->
                target.parentFile?.mkdirs()
                target.writeBytes(byteArrayOf(0))
                withContext(Dispatchers.Default) {
                    // Queue the player-end event before the manager's Main-thread
                    // insertion while its background continuation still sees READY.
                    Handler(Looper.getMainLooper()).post {
                        sessionPlayer.transitionTo(Player.STATE_ENDED)
                    }
                    previewDownloadReturned.complete(Unit)
                    Result.success(
                        MediaTrack(
                            uri = previewUri,
                            title = recommendation.title,
                            artist = recommendation.artist,
                            isTransient = true
                        )
                    )
                }
            }
        )

        try {
            val connected = CountDownLatch(1)
            musicController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(musicController.currentTrack.value?.uri).isEqualTo(seedUri)
            assertThat(musicController.playWhenReady.value).isTrue()
            manager.attach(musicController)
            manager.setDiscoverMode(enabled = true, recommendations = listOf(recommendation))

            withTimeout(5_000) { previewDownloadReturned.await() }
            withTimeout(5_000) {
                while (musicController.currentTrack.value?.uri != previewUri) {
                    shadowOf(Looper.getMainLooper()).idle()
                    kotlinx.coroutines.yield()
                    delay(10)
                }
            }

            assertThat(musicController.currentTrack.value?.uri).isEqualTo(previewUri)
            assertThat(musicController.playWhenReady.value).isTrue()
            assertThat(player.currentMediaItem?.mediaId).isEqualTo(previewUri)
        } finally {
            manager.detach(musicController)
            raceScope.cancel()
            musicController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun discoverOffRestoresEndedPausedQueueWithoutAdvancing() = runBlocking {
        val seedUri = "file:///paused-discover-seed.m4a"
        val physicalNextUri = "file:///paused-discover-next.m4a"
        val player = ExoPlayer.Builder(context).build()
        player.setMediaItems(listOf(seedUri, physicalNextUri).map { uri ->
            MediaItem.Builder().setMediaId(uri).setUri(uri).build()
        })
        player.seekToDefaultPosition(0)
        player.playWhenReady = true
        val sessionPlayer = ControlledPlaybackStatePlayer(player, Player.STATE_READY)
        val session = MediaSession.Builder(context, sessionPlayer).build()
        val musicController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )
        val resolverStarted = CompletableDeferred<Unit>()
        val pendingResolver = CompletableDeferred<Result<ResolvedRecommendation>>()
        val manager = RecommendationPreviewManager(
            context = context,
            appScope = appScope,
            downloadRepository = downloadRepository,
            playlistRepository = PlaylistRepository(
                database.playlistDao(),
                database.trackDao()
            ),
            resolver = {
                resolverStarted.complete(Unit)
                pendingResolver.await()
            }
        )

        try {
            val connected = CountDownLatch(1)
            musicController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            manager.attach(musicController)
            manager.setDiscoverMode(
                enabled = true,
                recommendations = listOf(LastfmTrack("Paused Artist", "Paused Preview", 0.9))
            )
            withTimeout(5_000) { resolverStarted.await() }
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(player.mediaItemCount).isEqualTo(1)

            player.playWhenReady = false
            shadowOf(Looper.getMainLooper()).idle()
            sessionPlayer.transitionTo(Player.STATE_ENDED)
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(musicController.playbackState.value).isEqualTo(Player.STATE_ENDED)
            assertThat(musicController.playWhenReady.value).isFalse()

            manager.setDiscoverMode(enabled = false)
            shadowOf(Looper.getMainLooper()).idle()

            assertThat((0 until player.mediaItemCount).map {
                player.getMediaItemAt(it).mediaId
            }).containsExactly(seedUri, physicalNextUri).inOrder()
            assertThat(player.currentMediaItem?.mediaId).isEqualTo(seedUri)
            assertThat(player.currentMediaItemIndex).isEqualTo(0)
            assertThat(player.playWhenReady).isFalse()
        } finally {
            manager.detach(musicController)
            musicController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun discoverPreviewInsertionDoesNotOverrideAPlayRequestDuringDownload() = runBlocking {
        val seedUri = "file:///interrupted-discover-seed.m4a"
        val physicalNextUri = "file:///interrupted-discover-next.m4a"
        val userChoiceUri = "file:///interrupted-discover-user-choice.m4a"
        val previewUri = "file:///interrupted-discover-preview.m4a"
        val recommendation = LastfmTrack("Intervention Artist", "Stale Preview", 0.9)
        val player = ExoPlayer.Builder(context).build()
        player.setMediaItems(listOf(seedUri, physicalNextUri).map { uri ->
            MediaItem.Builder().setMediaId(uri).setUri(uri).build()
        })
        player.seekToDefaultPosition(0)
        val session = MediaSession.Builder(context, player).build()
        val musicController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )
        val raceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val downloadStarted = CompletableDeferred<Unit>()
        val releaseDownload = CompletableDeferred<Unit>()
        val manager = RecommendationPreviewManager(
            context = context,
            appScope = raceScope,
            downloadRepository = downloadRepository,
            playlistRepository = PlaylistRepository(
                database.playlistDao(),
                database.trackDao()
            ),
            resolver = {
                Result.success(
                    ResolvedRecommendation(
                        sourceUrl = "https://example.com/stale-preview.m4a",
                        thumbnailUrl = "",
                        mimeType = "audio/mp4"
                    )
                )
            },
            previewDownloader = { _, _, _, target ->
                target.parentFile?.mkdirs()
                target.writeBytes(byteArrayOf(0))
                downloadStarted.complete(Unit)
                releaseDownload.await()
                Result.success(
                    MediaTrack(
                        uri = previewUri,
                        title = recommendation.title,
                        artist = recommendation.artist,
                        isTransient = true
                    )
                )
            }
        )

        try {
            val connected = CountDownLatch(1)
            musicController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            manager.attach(musicController)
            manager.setDiscoverMode(enabled = true, recommendations = listOf(recommendation))
            withTimeout(5_000) { downloadStarted.await() }

            musicController.play(listOf(MediaTrack(uri = userChoiceUri, title = "User choice")))
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(musicController.currentTrack.value?.uri).isEqualTo(userChoiceUri)
            assertThat(player.mediaItemCount).isEqualTo(1)

            releaseDownload.complete(Unit)
            awaitPreviewState(manager) { !it.active }
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(player.mediaItemCount).isEqualTo(1)
            assertThat(player.currentMediaItem?.mediaId).isEqualTo(userChoiceUri)
        } finally {
            releaseDownload.complete(Unit)
            manager.detach(musicController)
            raceScope.cancel()
            musicController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun discoverHandoff_failedFirstPreviewRestoresPhysicalQueue() = runBlocking {
        val seedUri = "file:///seed-failure.m4a"
        val physicalNextUri = "file:///physical-next-failure.m4a"
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        player.setMediaItems(
            listOf(seedUri, physicalNextUri).map { uri ->
                MediaItem.Builder().setMediaId(uri).setUri(uri).build()
            }
        )
        player.seekToDefaultPosition(0)
        shadowOf(Looper.getMainLooper()).idle()
        val musicController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )
        val recommendation = LastfmTrack("Unavailable Artist", "Unavailable", 0.9)
        val recommendationKey = RecommendationPreviewManager.recommendationKey(recommendation)

        try {
            val connected = CountDownLatch(1)
            musicController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(musicController.currentTrack.value?.uri).isEqualTo(seedUri)
            val manager = RecommendationPreviewManager(
                context = context,
                appScope = appScope,
                downloadRepository = downloadRepository,
                playlistRepository = PlaylistRepository(
                    database.playlistDao(),
                    database.trackDao()
                ),
                resolver = {
                    delay(100)
                    Result.failure(IllegalStateException("No preview source"))
                }
            )
            manager.attach(musicController)
            manager.setDiscoverMode(true, recommendations = listOf(recommendation))

            awaitPreviewState(manager) { it.active }
            awaitPreviewState(manager) { recommendationKey in it.failures }
            awaitPreviewState(manager) { !it.active }
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(player.mediaItemCount).isEqualTo(2)
            assertThat(player.currentMediaItem?.mediaId).isEqualTo(seedUri)

            manager.detach(musicController)
        } finally {
            musicController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun discoverHandoff_emptyRecommendationFetchRestoresPhysicalQueue() = runBlocking {
        val seedUri = "file:///seed-empty.m4a"
        val physicalNextUri = "file:///physical-next-empty.m4a"
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        player.setMediaItems(
            listOf(seedUri, physicalNextUri).map { uri ->
                MediaItem.Builder().setMediaId(uri).setUri(uri).build()
            }
        )
        player.seekToDefaultPosition(0)
        shadowOf(Looper.getMainLooper()).idle()
        val musicController = MusicController(
            context,
            connector = MusicController.AsyncConnector { ctx ->
                MediaController.Builder(ctx, session.token).buildAsync()
            }
        )
        val fetchStarted = CompletableDeferred<Unit>()
        val fetchResult = CompletableDeferred<Result<List<LastfmTrack>>>()
        val manager = RecommendationPreviewManager(
            context = context,
            appScope = appScope,
            downloadRepository = downloadRepository,
            playlistRepository = PlaylistRepository(
                database.playlistDao(),
                database.trackDao()
            ),
            discoverRecommendations = {
                fetchStarted.complete(Unit)
                fetchResult.await()
            }
        )

        try {
            val connected = CountDownLatch(1)
            musicController.initialize { connected.countDown() }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue()
            manager.attach(musicController)
            manager.setDiscoverMode(enabled = true)
            withTimeout(5_000) { fetchStarted.await() }
            val isolatedRevision = musicController.queueRevision.value

            fetchResult.complete(Result.success(emptyList()))
            withTimeout(5_000) {
                musicController.queueRevision.first { it > isolatedRevision }
            }
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(player.mediaItemCount).isEqualTo(2)
            assertThat(player.currentMediaItem?.mediaId).isEqualTo(seedUri)

            manager.detach(musicController)
        } finally {
            musicController.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun previewResolveTimeout_clearsSpinnerAndShowsRetryableFailure() = runBlocking {
        val recommendation = LastfmTrack("Slow Artist", "Slow Song", 0.9)
        val controller = MusicController(context)
        val manager = RecommendationPreviewManager(
            context = context,
            appScope = appScope,
            downloadRepository = downloadRepository,
            playlistRepository = PlaylistRepository(
                database.playlistDao(),
                database.trackDao()
            ),
            resolver = {
                delay(1_000)
                Result.success(
                    ResolvedRecommendation(
                        sourceUrl = "https://example.com/audio.m4a",
                        thumbnailUrl = "",
                        mimeType = "audio/mp4"
                    )
                )
            },
            resolveTimeoutMs = 25
        )
        manager.attach(controller)

        manager.startPreview(
            seedUri = "file:///seed.m4a",
            recommendations = listOf(recommendation),
            startIndex = 0,
            artworkByKey = emptyMap()
        )

        val key = RecommendationPreviewManager.recommendationKey(recommendation)
        val failedState = awaitPreviewState(manager) { key in it.failures }
        assertThat(failedState.resolvingKeys).doesNotContain(key)
        assertThat(failedState.preparingKeys).doesNotContain(key)
        assertThat(failedState.failures.getValue(key)).contains("timed out")
        awaitPreviewState(manager) { !it.active }
        controller.release()
        Unit
    }

    @Test
    fun previewDownloadTimeout_clearsSpinnerAndShowsRetryableFailure() = runBlocking {
        val recommendation = LastfmTrack("Slow Artist", "Slow Download", 0.9)
        val controller = MusicController(context)
        val manager = RecommendationPreviewManager(
            context = context,
            appScope = appScope,
            downloadRepository = downloadRepository,
            playlistRepository = PlaylistRepository(
                database.playlistDao(),
                database.trackDao()
            ),
            resolver = {
                Result.success(
                    ResolvedRecommendation(
                        sourceUrl = "https://example.com/audio.m4a",
                        thumbnailUrl = "",
                        mimeType = "audio/mp4"
                    )
                )
            },
            previewDownloader = { _, _, _, _ ->
                delay(1_000)
                Result.failure(AssertionError("Timed-out download must not complete"))
            },
            downloadTimeoutMs = 25
        )
        manager.attach(controller)

        manager.startPreview(
            seedUri = "file:///seed.m4a",
            recommendations = listOf(recommendation),
            startIndex = 0,
            artworkByKey = emptyMap()
        )

        val key = RecommendationPreviewManager.recommendationKey(recommendation)
        val failedState = awaitPreviewState(manager) { key in it.failures }
        assertThat(failedState.resolvingKeys).doesNotContain(key)
        assertThat(failedState.preparingKeys).doesNotContain(key)
        assertThat(failedState.failures.getValue(key)).contains("download timed out")
        awaitPreviewState(manager) { !it.active }
        controller.release()
        Unit
    }

    /**
     * Wait off the test thread so the Robolectric main looper is not the
     * collector's dispatcher. CI runners can schedule preview work much later
     * than a local machine, so keep a generous bound and report the last state.
     */
    private suspend fun awaitPreviewState(
        manager: RecommendationPreviewManager,
        timeoutMs: Long = 15_000,
        predicate: (RecommendationPreviewState) -> Boolean
    ): RecommendationPreviewState = withContext(Dispatchers.Default) {
        try {
            withTimeout(timeoutMs) { manager.state.first(predicate) }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError(
                "Timed out after ${timeoutMs}ms waiting for preview state; " +
                    "last=${manager.state.value}",
                timeout
            )
        }
    }
}

private class ControlledPlaybackStatePlayer(
    player: Player,
    initialPlaybackState: Int
) : ForwardingPlayer(player) {
    private val listeners = CopyOnWriteArraySet<Player.Listener>()

    @Volatile
    private var reportedPlaybackState = initialPlaybackState

    override fun getPlaybackState(): Int = reportedPlaybackState

    override fun addListener(listener: Player.Listener) {
        listeners += listener
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners -= listener
        super.removeListener(listener)
    }

    fun transitionTo(state: Int) {
        reportedPlaybackState = state
        listeners.forEach { it.onPlaybackStateChanged(state) }
    }

    override fun prepare() = Unit

    override fun play() = Unit
}
