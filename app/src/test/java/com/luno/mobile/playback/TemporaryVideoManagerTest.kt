package com.luno.mobile.playback

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TemporaryVideoManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val track = MediaTrack("song:0", "Hot Like Sauce", "Pretty Lights", durationMs = 200_000)
    private fun followers() = (1..8).map { track.copy(uri = "song:$it", title = "Song $it") }
    private fun source(track: MediaTrack) = TemporaryVideoSource(track.uri, "https://example.com/video.mp4", 360)
    private val requests = mutableListOf<String>()
    private var downloads = 0
    private fun TestScope.manager(
        resolve: suspend (MediaTrack) -> List<TemporaryVideoSource> = { requests += it.uri; listOf(source(it)) },
        download: suspend (TemporaryVideoSource, File, (Float?) -> Unit) -> File = { _, file, progress ->
            downloads++; progress(0.5f); file.writeBytes(ByteArray(20)); progress(1f); file
        },
        cacheBytes: Long = 1000,
        timeoutMs: Long = 1000
    ) = TemporaryVideoManager(temporary.newFolder(), this, TemporaryVideoResolver(resolve),
        TemporaryVideoDownloader(download), maximumCacheBytes = cacheBytes, stageTimeoutMs = timeoutMs,
        ioDispatcher = StandardTestDispatcher(testScheduler))

    @Test fun currentQuickVideoUpgradesBeforePrefetchWithoutHidingReadyFile() = runTest {
        val hdGate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val root = temporary.newFolder()
        val resolver = object : TemporaryVideoResolver {
            override suspend fun resolve(track: MediaTrack) = listOf(source(track))
            override suspend fun resolveUpgrade(track: MediaTrack, videoId: String) =
                listOf(source(track).copy(height = 1080))
        }
        val manager = TemporaryVideoManager(root, this, resolver, TemporaryVideoDownloader { source, file, _ ->
            order += "${source.videoId}:${source.height}"
            if (source.height == 1080) hdGate.await()
            file.writeBytes(ByteArray(20)); file
        }, maximumCacheBytes = 1000, ioDispatcher = StandardTestDispatcher(testScheduler))
        manager.updatePlan(temporaryVideoPlan(track, followers())); manager.setEnabled(true); runCurrent()
        assertThat(manager.state.value.status).isEqualTo(TemporaryVideoStatus.READY)
        val quickFile = manager.state.value.file!!
        assertThat(quickFile.exists()).isTrue()
        assertThat(order).containsExactly("${track.uri}:360", "${track.uri}:1080").inOrder()
        hdGate.complete(Unit); advanceUntilIdle()
        assertThat(manager.state.value.height).isEqualTo(1080)
        assertThat(manager.state.value.file).isNotEqualTo(quickFile)
        assertThat(manager.state.value.status).isEqualTo(TemporaryVideoStatus.READY)
        assertThat(manager.state.value.cachedUpcoming).isEqualTo(5)
    }

    @Test fun failedHdUpgradeKeepsQuickVideoReadyAndAllowsSuccessorPrefetch() = runTest {
        val resolver = object : TemporaryVideoResolver {
            override suspend fun resolve(track: MediaTrack) = listOf(source(track))
            override suspend fun resolveUpgrade(track: MediaTrack, videoId: String): List<TemporaryVideoSource> = error("HD unavailable")
        }
        val manager = TemporaryVideoManager(temporary.newFolder(), this, resolver,
            TemporaryVideoDownloader { _, file, _ -> file.writeBytes(ByteArray(20)); file },
            ioDispatcher = StandardTestDispatcher(testScheduler))
        manager.updatePlan(temporaryVideoPlan(track, followers())); manager.setEnabled(true); advanceUntilIdle()
        assertThat(manager.state.value.height).isEqualTo(360)
        assertThat(manager.state.value.status).isEqualTo(TemporaryVideoStatus.READY)
        assertThat(manager.state.value.error).isNull()
        assertThat(manager.state.value.cachedUpcoming).isEqualTo(5)
    }

    @Test fun lateUpgradeCannotReplaceANewerCurrentTrackAndDecoderFailureFallsBackToQuick() = runTest {
        val gate = CompletableDeferred<Unit>()
        val old = track
        val latest = track.copy(uri = "latest")
        val resolver = object : TemporaryVideoResolver {
            override suspend fun resolve(track: MediaTrack) = listOf(source(track))
            override suspend fun resolveUpgrade(track: MediaTrack, videoId: String) = listOf(source(track).copy(height = 1080))
        }
        val manager = TemporaryVideoManager(temporary.newFolder(), this, resolver,
            TemporaryVideoDownloader { source, file, _ ->
                if (source.videoId == old.uri && source.height == 1080) withContext(NonCancellable) { gate.await() }
                file.writeBytes(ByteArray(20)); file
            }, ioDispatcher = StandardTestDispatcher(testScheduler))
        manager.updatePlan(listOf(old)); manager.setEnabled(true); runCurrent()
        assertThat(manager.state.value.height).isEqualTo(360)
        manager.updatePlan(listOf(latest)); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertThat(manager.state.value.trackUri).isEqualTo(latest.uri)
        assertThat(manager.state.value.height).isEqualTo(1080)
        assertThat(manager.state.value.file!!.parentFile!!.listFiles()!!.size).isEqualTo(2)
        manager.reportDecoderError(latest.uri)
        assertThat(manager.state.value.status).isEqualTo(TemporaryVideoStatus.READY)
        assertThat(manager.state.value.height).isEqualTo(360)
        assertThat(manager.state.value.file!!.exists()).isTrue()
    }

    @Test fun hdHasALongerBoundedDeadlineAndTimeoutRetainsTheQuickVideo() = runTest {
        var stall = false
        val resolver = object : TemporaryVideoResolver {
            override suspend fun resolve(track: MediaTrack) = listOf(source(track))
            override suspend fun resolveUpgrade(track: MediaTrack, videoId: String) = listOf(source(track).copy(height = 1080))
        }
        val manager = TemporaryVideoManager(temporary.newFolder(), this, resolver,
            TemporaryVideoDownloader { source, file, _ ->
                if (source.height == 1080) { if (stall) awaitCancellation() else delay(5000) }
                file.writeBytes(ByteArray(20)); file
            }, stageTimeoutMs = 1000, upgradeTimeoutMs = 10_000,
            ioDispatcher = StandardTestDispatcher(testScheduler))
        manager.updatePlan(listOf(track)); manager.setEnabled(true)
        advanceTimeBy(2000); runCurrent()
        assertThat(manager.state.value.status).isEqualTo(TemporaryVideoStatus.READY)
        assertThat(manager.state.value.height).isEqualTo(360)
        assertThat(manager.state.value.upgrading).isTrue()
        advanceUntilIdle()
        assertThat(manager.state.value.height).isEqualTo(1080)
        stall = true
        manager.updatePlan(listOf(track.copy(uri = "slow"))); advanceUntilIdle()
        assertThat(manager.state.value.status).isEqualTo(TemporaryVideoStatus.READY)
        assertThat(manager.state.value.height).isEqualTo(360)
        assertThat(manager.state.value.upgrading).isFalse()
        assertThat(manager.state.value.error).isNull()
    }

    @Test fun planUsesAudibleItemAndExactlyFiveUniqueAuthoritativeSuccessors() {
        assertThat(temporaryVideoPlan(track, listOf(track) + followers())).containsExactly(track, *followers().take(5).toTypedArray()).inOrder()
        assertThat(temporaryVideoPlan(null, followers())).isEmpty()
    }

    @Test fun stagesWhilePausedPrefetchesFiveAndAdoptsNextWithoutRedownloading() = runTest {
        val manager = manager()
        manager.updatePlan(temporaryVideoPlan(track, followers()))
        manager.setEnabled(true)
        advanceUntilIdle()
        assertThat(manager.state.value.status).isEqualTo(TemporaryVideoStatus.READY)
        assertThat(manager.state.value.cachedUpcoming).isEqualTo(5)
        assertThat(downloads).isEqualTo(6)
        manager.updatePlan(temporaryVideoPlan(followers()[0], followers().drop(1).take(4)))
        advanceUntilIdle()
        assertThat(manager.state.value.trackUri).isEqualTo("song:1")
        assertThat(manager.state.value.file?.isFile).isTrue()
        assertThat(downloads).isEqualTo(6)
    }

    @Test fun repeatedPlanCoalescesAndSpeculativeFailureCannotReplaceCurrentReadiness() = runTest {
        val manager = manager(resolve = {
            requests += it.uri
            if (it.uri != track.uri) error("successor unavailable")
            listOf(source(it))
        })
        val plan = temporaryVideoPlan(track, followers())
        manager.updatePlan(plan); manager.setEnabled(true); manager.updatePlan(plan)
        advanceUntilIdle()
        assertThat(requests.count { it == track.uri }).isEqualTo(1)
        assertThat(manager.state.value.status).isEqualTo(TemporaryVideoStatus.READY)
        assertThat(manager.state.value.error).isNull()
    }

    @Test fun staleNonCooperativeResolutionCannotPublishForNewAudibleTrack() = runTest {
        val release = CompletableDeferred<Unit>()
        val manager = manager(resolve = {
            if (it.uri == track.uri) withContext(NonCancellable) { release.await() }
            listOf(source(it))
        })
        manager.updatePlan(listOf(track)); manager.setEnabled(true); runCurrent()
        val next = followers()[0]
        manager.updatePlan(listOf(next)); runCurrent()
        assertThat(manager.state.value.trackUri).isEqualTo(next.uri)
        release.complete(Unit); advanceUntilIdle()
        assertThat(manager.state.value.trackUri).isEqualTo(next.uri)
        assertThat(manager.state.value.status).isEqualTo(TemporaryVideoStatus.READY)
        assertThat(downloads).isEqualTo(1)
    }

    @Test fun timeoutSettlesToRetryableErrorInsteadOfAnOrphanedLoadingState() = runTest {
        var stall = true
        val manager = manager(resolve = { if (stall) delay(5000); listOf(source(it)) })
        manager.updatePlan(listOf(track)); manager.setEnabled(true)
        advanceUntilIdle()
        assertThat(manager.state.value.status).isEqualTo(TemporaryVideoStatus.ERROR)
        assertThat(manager.state.value.error).contains("timed out")
        stall = false; manager.retry(); advanceUntilIdle()
        assertThat(manager.state.value.status).isEqualTo(TemporaryVideoStatus.READY)
    }

    @Test fun disablingCancelsPreparationAndLateProgressCannotReenableVideo() = runTest {
        var publish: ((Float?) -> Unit)? = null
        val manager = manager(download = { _, _, progress -> publish = progress; awaitCancellation() })
        manager.updatePlan(listOf(track)); manager.setEnabled(true); runCurrent()
        manager.setEnabled(false); publish?.invoke(0.8f); advanceUntilIdle()
        assertThat(manager.state.value.enabled).isFalse()
        assertThat(manager.state.value.status).isEqualTo(TemporaryVideoStatus.OFF)
        assertThat(manager.state.value.file).isNull()
    }

    @Test fun cacheBoundAndDecoderInvalidationAreIndependentOfAudioQueue() = runTest {
        val manager = manager(cacheBytes = 45)
        manager.updatePlan(temporaryVideoPlan(track, followers())); manager.setEnabled(true); advanceUntilIdle()
        val file = manager.state.value.file!!
        assertThat(file.parentFile!!.listFiles()!!.sumOf { it.length() }).isAtMost(45L)
        manager.reportDecoderError(track.uri)
        assertThat(manager.state.value.status).isEqualTo(TemporaryVideoStatus.ERROR)
        val oldDownloads = downloads
        manager.retry(); advanceUntilIdle()
        assertThat(downloads).isGreaterThan(oldDownloads)
        assertThat(manager.state.value.status).isEqualTo(TemporaryVideoStatus.READY)
        assertThat(manager.state.value.trackUri).isEqualTo(track.uri)
    }

    @Test fun oversizedStreamRetriesSmallerVariantWithoutLosingTheCurrentSong() = runTest {
        val attempted = mutableListOf<Int>()
        val manager = manager(resolve = { listOf(source(it).copy(height = 480), source(it).copy(height = 240)) },
            download = { input, file, _ ->
                attempted += input.height
                if (input.height == 480) error("Video exceeds temporary download limit")
                file.writeText("video"); file
            })
        manager.updatePlan(listOf(track)); manager.setEnabled(true); advanceUntilIdle()
        assertThat(attempted).containsExactly(480, 240).inOrder()
        assertThat(manager.state.value.status).isEqualTo(TemporaryVideoStatus.READY)
        assertThat(manager.state.value.trackUri).isEqualTo(track.uri)
        assertThat(manager.state.value.error).isNull()
    }

    @Test fun sourceFailureFallsThroughToTheNextBoundedCandidate() = runTest {
        val manager = manager(resolve = { listOf(source(it).copy(videoId = "bad"), source(it)) },
            download = { input, file, _ -> if (input.videoId == "bad") error("HTTP 403"); file.writeText("video"); file })
        manager.updatePlan(listOf(track)); manager.setEnabled(true); advanceUntilIdle()
        assertThat(manager.state.value.status).isEqualTo(TemporaryVideoStatus.READY)
    }
}
