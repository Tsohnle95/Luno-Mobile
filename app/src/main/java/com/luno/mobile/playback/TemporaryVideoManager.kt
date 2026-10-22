package com.luno.mobile.playback

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal const val VIDEO_PREFETCH_DEPTH = 5
internal const val VIDEO_CACHE_BYTES = 384L * 1024 * 1024

internal data class TemporaryVideoSource(val videoId: String, val url: String, val height: Int)
internal fun interface TemporaryVideoResolver {
    suspend fun resolve(track: MediaTrack): List<TemporaryVideoSource>
    suspend fun resolveUpgrade(track: MediaTrack, videoId: String): List<TemporaryVideoSource> = emptyList()
}
internal fun interface TemporaryVideoDownloader {
    suspend fun download(source: TemporaryVideoSource, target: File, progress: (Float?) -> Unit): File
}

enum class TemporaryVideoStatus { OFF, RESOLVING, DOWNLOADING, READY, ERROR }
data class TemporaryVideoState(
    val enabled: Boolean = false,
    val trackUri: String? = null,
    val status: TemporaryVideoStatus = TemporaryVideoStatus.OFF,
    val file: File? = null,
    val progress: Float? = null,
    val error: String? = null,
    val cachedUpcoming: Int = 0,
    val height: Int = 0,
    val upgrading: Boolean = false,
    internal val generation: Long = 0
)

internal fun temporaryVideoPlan(current: MediaTrack?, following: List<MediaTrack>): List<MediaTrack> =
    if (current == null) emptyList() else (listOf(current) + following)
        .distinctBy { it.uri }.take(VIDEO_PREFETCH_DEPTH + 1)

/** Disposable video staging never mutates the audio queue, Room or durable download jobs. */
class TemporaryVideoManager internal constructor(
    private val root: File,
    private val scope: CoroutineScope,
    private val resolver: TemporaryVideoResolver,
    private val downloader: TemporaryVideoDownloader,
    initiallyEnabled: Boolean = false,
    private val persistEnabled: (Boolean) -> Unit = {},
    private val maximumCacheBytes: Long = VIDEO_CACHE_BYTES,
    private val stageTimeoutMs: Long = 120_000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: (String) -> Unit = {},
    private val upgradeTimeoutMs: Long = 15 * 60_000L
) {
    private val mutableState = MutableStateFlow(TemporaryVideoState(enabled = initiallyEnabled))
    val state: StateFlow<TemporaryVideoState> = mutableState.asStateFlow()
    private val generation = AtomicLong()
    private var plan: List<MediaTrack> = emptyList()
    private var worker: Job? = null
    private var binding: Job? = null
    private var owner: MusicController? = null
    private var initialized = false
    private val quickSources = java.util.concurrent.ConcurrentHashMap<String, TemporaryVideoSource>()
    private val hdSources = java.util.concurrent.ConcurrentHashMap<String, TemporaryVideoSource>()
    private val invalidated = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun attach(controller: MusicController, previews: RecommendationPreviewManager? = null) {
        if (owner === controller) return
        binding?.cancel()
        owner = controller
        // MediaController queue access belongs to its main application looper.
        binding = scope.launch(Dispatchers.Main.immediate) {
            combine(controller.currentTrack, controller.queueRevision,
                previews?.state ?: MutableStateFlow(RecommendationPreviewState())) { current, _, preview ->
                current?.let { temporaryVideoPlan(it,
                    if (preview.discoverMode) previews?.preparedVideoSuccessors().orEmpty()
                    else controller.getQueueAfterCurrent().orEmpty()) }
            }.collect { next ->
                // A replacement Activity/controller starts empty while reconnecting.
                // Keep its in-flight cache work alive across that gap.
                if (next != null) updatePlan(next)
            }
        }
    }

    fun detach(controller: MusicController) {
        if (owner !== controller) return
        binding?.cancel()
        owner = null
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled == state.value.enabled) return
        persistEnabled(enabled)
        mutableState.value = state.value.copy(enabled = enabled)
        restart()
    }

    internal fun updatePlan(next: List<MediaTrack>) {
        val bounded = next.distinctBy { it.uri }.take(VIDEO_PREFETCH_DEPTH + 1)
        if (plan == bounded) return
        plan = bounded
        restart()
    }

    fun retry() = restart()

    fun reportDecoderError(trackUri: String) {
        if (state.value.trackUri != trackUri || !state.value.enabled) return
        val token = generation.incrementAndGet()
        worker?.cancel()
        diagnostics("Decoder failed at ${state.value.height}p")
        val quick = cacheFile(trackUri)
        if (state.value.file?.name?.endsWith("-hd.mp4") == true && quick.isFile) {
            hdSources.remove(trackUri)
            cacheFile(trackUri, hd = true).delete()
            mutableState.value = state.value.copy(file = quick, height = quickSources[trackUri]?.height ?: 0,
                upgrading = false, generation = token, error = null)
            return
        }
        invalidated += trackUri
        mutableState.value = state.value.copy(status = TemporaryVideoStatus.ERROR, file = null,
            progress = null, generation = token, error = "Video could not be decoded. Audio is still available; retry video.")
        // Decoder errors invalidate this artifact, not the durable song.

    }

    private fun cacheFile(uri: String, hd: Boolean = false): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(root, if (hd) "$hash-hd.mp4" else "$hash.mp4")
    }

    private fun restart() {
        val token = generation.incrementAndGet()
        val previous = worker
        previous?.cancel()
        val snapshot = plan.toList()
        val enabled = state.value.enabled
        mutableState.value = TemporaryVideoState(enabled, snapshot.firstOrNull()?.uri,
            if (enabled && snapshot.isNotEmpty()) TemporaryVideoStatus.RESOLVING else TemporaryVideoStatus.OFF, generation = token)
        worker = scope.launch {
            // Join cancellation before pruning/publishing paths another worker owned.
            previous?.cancelAndJoin()
            if (!enabled || snapshot.isEmpty()) return@launch
            withContext(ioDispatcher) {
                root.mkdirs()
                if (!initialized) {
                    root.listFiles()?.forEach { it.delete() }
                    initialized = true
                }
                val keep = snapshot.flatMap { listOf(cacheFile(it.uri).name, cacheFile(it.uri, hd = true).name) }.toSet()
                root.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
                snapshot.forEachIndexed { index, track ->
                    currentCoroutineContext().ensureActive()
                    val quickFile = cacheFile(track.uri)
                    val hdFile = cacheFile(track.uri, hd = true)
                    if (invalidated.remove(track.uri)) {
                        quickFile.delete(); hdFile.delete()
                        quickSources.remove(track.uri); hdSources.remove(track.uri)
                    }
                    val file = if (hdFile.isFile && hdSources.containsKey(track.uri)) hdFile else quickFile
                    try {
                        if (!file.isFile || file.length() == 0L) {
                            withTimeout(stageTimeoutMs) {
                                if (index == 0) publish(token) { it.copy(status = TemporaryVideoStatus.RESOLVING) }
                                val sources = resolver.resolve(track)
                                currentCoroutineContext().ensureActive()
                                check(sources.isNotEmpty()) { "No matching playable video was found" }
                                var failure: Exception? = null
                                for (source in sources.take(9)) {
                                    currentCoroutineContext().ensureActive()
                                    try {
                                        downloader.download(source, file) { value ->
                                            if (index == 0) publish(token) {
                                                it.copy(status = TemporaryVideoStatus.DOWNLOADING, progress = value)
                                            }
                                        }
                                        diagnostics("Quick ready ${source.height}p, ${file.length()} bytes")
                                        quickSources[track.uri] = source
                                        failure = null
                                        break
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (error: Exception) { failure = error }
                                }
                                failure?.let { throw it }
                                check(file.isFile && file.length() > 0) { "Video download produced no playable file" }
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        val total = root.listFiles()?.sumOf { it.length() } ?: 0L
                        if (total > maximumCacheBytes) {
                            if (index > 0) { file.delete(); return@forEachIndexed }
                            root.listFiles()?.filter { it != file }?.forEach { it.delete() }
                            check(file.length() <= maximumCacheBytes) { "Video exceeds temporary cache limit" }
                        }
                        if (index == 0) publish(token) {
                            it.copy(status = TemporaryVideoStatus.READY, file = file, progress = 1f, error = null,
                                height = (if (file == hdFile) hdSources[track.uri] else quickSources[track.uri])?.height ?: 0)
                        } else publish(token) { it.copy(cachedUpcoming = it.cachedUpcoming + 1) }
                        if (index == 0 && file != hdFile) {
                            quickSources[track.uri]?.let { upgradeCurrent(track, it, token) }
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        file.delete()
                        if (index == 0) publish(token) {
                            it.copy(status = TemporaryVideoStatus.ERROR, file = null, progress = null,
                                error = "Video preparation timed out. Retry video; audio is unaffected.")
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        file.delete()
                        if (index == 0) publish(token) {
                            it.copy(status = TemporaryVideoStatus.ERROR, file = null, progress = null,
                                error = error.message ?: "Video unavailable; audio remains available")
                        }
                        // A speculative successor failure must never replace current readiness.
                    }
                }
            }
        }
    }

    private suspend fun upgradeCurrent(track: MediaTrack, quick: TemporaryVideoSource, token: Long) {
        if (quick.height >= 1080) return
        val target = cacheFile(track.uri, hd = true)
        publish(token) { it.copy(upgrading = true) }
        try {
            withTimeout(upgradeTimeoutMs) {
                val upgrades = resolver.resolveUpgrade(track, quick.videoId)
                    .filter { it.videoId == quick.videoId && it.height > quick.height && it.height <= 1080 }
                    .sortedByDescending { it.height }.distinctBy { it.url }.take(3)
                diagnostics("HD choices above ${quick.height}p: ${upgrades.map { it.height }}")
                for (source in upgrades) {
                    currentCoroutineContext().ensureActive()
                    try {
                        downloader.download(source, target) {}
                        currentCoroutineContext().ensureActive()
                        check(target.isFile && target.length() in 1..maximumCacheBytes) { "HD video exceeds temporary cache limit" }
                        // Current quality has priority over speculative successors and its old quick artifact.
                        root.listFiles()?.filter { it != target }?.sortedByDescending { it.length() }?.forEach {
                            if ((root.listFiles()?.sumOf { file -> file.length() } ?: 0) > maximumCacheBytes) it.delete()
                        }
                        diagnostics("HD ready ${source.height}p, ${target.length()} bytes")
                        hdSources[track.uri] = source
                        publish(token) { it.copy(file = target, height = source.height, upgrading = false) }
                        return@withTimeout
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        diagnostics("HD ${source.height}p failed: ${error.javaClass.simpleName}")
                        target.delete()
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            diagnostics("HD staging timed out")
            target.delete()
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            diagnostics("HD resolution failed: ${error.javaClass.simpleName}")
            target.delete()
        }
        finally { publish(token) { it.copy(upgrading = false) } }
    }

    private fun publish(token: Long, change: (TemporaryVideoState) -> TemporaryVideoState) {
        mutableState.update { current ->
            if (current.generation == token && current.enabled) change(current) else current
        }
    }
}
