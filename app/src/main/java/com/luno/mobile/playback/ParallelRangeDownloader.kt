package com.luno.mobile.playback

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Bounded parallel byte-range transfer for signed YouTube CDN media URLs.
 * Any server that ignores Range or returns inconsistent range metadata is
 * treated as unsupported so the caller can use its ordinary single stream.
 */
internal class ParallelRangeDownloader(
    private val client: OkHttpClient,
    private val chunkSizeBytes: Long = DEFAULT_CHUNK_SIZE_BYTES,
    private val minimumParallelSizeBytes: Long = DEFAULT_MINIMUM_SIZE_BYTES,
    private val maximumParallelRequests: Int = DEFAULT_PARALLEL_REQUESTS,
    private val isEligibleHost: (String) -> Boolean = ::isYouTubeCdnHost
) {
    sealed interface Outcome {
        data class Completed(
            val file: File,
            val contentType: String,
            val totalBytes: Long
        ) : Outcome

        data object Unsupported : Outcome
    }

    private data class ByteRange(val index: Int, val start: Long, val end: Long)

    private data class RangeChunk(
        val file: File,
        val start: Long,
        val end: Long,
        val totalBytes: Long,
        val contentType: String
    ) {
        val length: Long get() = end - start + 1L
    }

    init {
        require(chunkSizeBytes > 0L)
        require(minimumParallelSizeBytes > 0L)
        require(maximumParallelRequests > 0)
    }

    suspend fun download(
        request: Request,
        targetFile: File,
        isStopped: () -> Boolean,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Outcome {
        if (!isEligibleHost(request.url.host) || targetFile.exists()) return Outcome.Unsupported

        val parent = targetFile.absoluteFile.parentFile ?: return Outcome.Unsupported
        val workDir = File(parent, ".${targetFile.name}.ranges-${UUID.randomUUID()}")
        if (!workDir.mkdirs()) return Outcome.Unsupported
        var promoted = false

        try {
            val firstEnd = chunkSizeBytes - 1L
            val first = fetchRange(
                request = request,
                range = ByteRange(0, 0L, firstEnd),
                expectedTotalBytes = null,
                partFile = File(workDir, "part-0"),
                isStopped = isStopped
            ) ?: return Outcome.Unsupported

            val totalBytes = first.totalBytes
            if (totalBytes < minimumParallelSizeBytes || totalBytes > MAX_SUPPORTED_SIZE_BYTES) {
                return Outcome.Unsupported
            }
            if (first.end != minOf(firstEnd, totalBytes - 1L)) return Outcome.Unsupported

            val completedBytes = AtomicLong(first.length)
            onProgress(completedBytes.get(), totalBytes)

            val chunkCount = ((totalBytes - 1L) / chunkSizeBytes + 1L).toInt()
            val ranges = (1 until chunkCount).map { index ->
                val start = index.toLong() * chunkSizeBytes
                ByteRange(index, start, minOf(start + chunkSizeBytes - 1L, totalBytes - 1L))
            }
            val semaphore = Semaphore(maximumParallelRequests)
            val progressLock = Mutex()
            var lastProgressAt = System.nanoTime()
            val progressThreshold = maxOf(64L * 1024L, totalBytes / 200L)

            kotlinx.coroutines.coroutineScope {
                ranges.map { range ->
                    async {
                        semaphore.withPermit {
                            val part = File(workDir, "part-${range.index}")
                            val chunk = fetchRange(
                                request = request,
                                range = range,
                                expectedTotalBytes = totalBytes,
                                partFile = part,
                                isStopped = isStopped
                            ) ?: throw IOException("The server returned an invalid byte range")

                            val downloaded = completedBytes.addAndGet(chunk.length)
                            val now = System.nanoTime()
                            if (now - lastProgressAt >= PROGRESS_INTERVAL_NANOS ||
                                downloaded >= totalBytes ||
                                downloaded % progressThreshold < chunk.length
                            ) {
                                progressLock.withLock {
                                    val lockedNow = System.nanoTime()
                                    if (lockedNow - lastProgressAt >= PROGRESS_INTERVAL_NANOS ||
                                        completedBytes.get() >= totalBytes
                                    ) {
                                        lastProgressAt = lockedNow
                                        onProgress(completedBytes.get(), totalBytes)
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }

            if (isStopped()) throw IOException("Download stopped")
            if (completedBytes.get() != totalBytes) throw IOException("Incomplete ranged download")

            val assembledFile = File(workDir, "assembled")
            FileOutputStream(assembledFile).buffered().use { output ->
                repeat(chunkCount) { index ->
                    File(workDir, "part-$index").inputStream().buffered().use { input ->
                        input.copyTo(output, COPY_BUFFER_SIZE)
                    }
                }
            }
            if (assembledFile.length() != totalBytes) throw IOException("Assembled length mismatch")
            if (!assembledFile.renameTo(targetFile)) throw IOException("Could not stage ranged download")
            promoted = true
            onProgress(totalBytes, totalBytes)
            return Outcome.Completed(targetFile, first.contentType, totalBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            // The caller retries using one ordinary stream. This method never
            // leaves a partial target file behind on a rejected range transfer.
            return Outcome.Unsupported
        } finally {
            workDir.deleteRecursively()
            if (!promoted) targetFile.delete()
        }
    }

    private suspend fun fetchRange(
        request: Request,
        range: ByteRange,
        expectedTotalBytes: Long?,
        partFile: File,
        isStopped: () -> Boolean
    ): RangeChunk? {
        if (isStopped()) throw IOException("Download stopped")
        val rangeRequest = request.newBuilder()
            .header("Range", "bytes=${range.start}-${range.end}")
            .build()
        return executeWithCancellation(rangeRequest, isStopped) { response ->
            if (response.code != 206 || !isEligibleHost(response.request.url.host)) return@executeWithCancellation null
            val contentRange = parseContentRange(response.header("Content-Range")) ?: return@executeWithCancellation null
            val (responseStart, responseEnd, totalBytes) = contentRange
            if (responseStart != range.start || responseEnd != range.end || totalBytes <= responseEnd) {
                return@executeWithCancellation null
            }
            if (expectedTotalBytes != null && totalBytes != expectedTotalBytes) {
                return@executeWithCancellation null
            }
            val expectedLength = responseEnd - responseStart + 1L
            val declaredLength = response.header("Content-Length")?.toLongOrNull()
            if (declaredLength != null && declaredLength != expectedLength) {
                return@executeWithCancellation null
            }

            val body = response.body ?: return@executeWithCancellation null
            val contentLength = body.contentLength()
            if (contentLength >= 0L && contentLength != expectedLength) {
                return@executeWithCancellation null
            }

            var bytesCopied = 0L
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            body.byteStream().use { input ->
                FileOutputStream(partFile).buffered().use { output ->
                    while (true) {
                        if (isStopped()) throw IOException("Download stopped")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytesCopied += read
                        if (bytesCopied > expectedLength) return@executeWithCancellation null
                    }
                }
            }
            if (bytesCopied != expectedLength) {
                partFile.delete()
                return@executeWithCancellation null
            }
            RangeChunk(
                file = partFile,
                start = responseStart,
                end = responseEnd,
                totalBytes = totalBytes,
                contentType = body.contentType()?.toString()
                    ?: response.header("Content-Type").orEmpty()
            )
        }
    }

    private suspend fun <T> executeWithCancellation(
        request: Request,
        isStopped: () -> Boolean,
        consume: (Response) -> T
    ): T {
        val call = client.newCall(request)
        val parentContext = currentCoroutineContext()
        val monitor = CoroutineScope(parentContext + Dispatchers.Default).launch {
            while (true) {
                if (isStopped()) {
                    call.cancel()
                    break
                }
                delay(CANCEL_POLL_INTERVAL_MS)
            }
        }
        val completionHandle = parentContext[kotlinx.coroutines.Job]
            ?.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
        return try {
            withContext(Dispatchers.IO) {
                call.execute().use(consume)
            }
        } finally {
            completionHandle?.dispose()
            monitor.cancel()
        }
    }

    private fun parseContentRange(value: String?): Triple<Long, Long, Long>? {
        val match = CONTENT_RANGE.matchEntire(value.orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return if (start >= 0L && end >= start && total > end) Triple(start, end, total) else null
    }

    companion object {
        private const val DEFAULT_CHUNK_SIZE_BYTES = 1L * 1024L * 1024L
        private const val DEFAULT_MINIMUM_SIZE_BYTES = 4L * 1024L * 1024L
        private const val DEFAULT_PARALLEL_REQUESTS = 4
        private const val MAX_SUPPORTED_SIZE_BYTES = 2L * 1024L * 1024L * 1024L
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val CANCEL_POLL_INTERVAL_MS = 150L
        private const val PROGRESS_INTERVAL_NANOS = 250_000_000L
        private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")

        private fun isYouTubeCdnHost(host: String): Boolean =
            host == "googlevideo.com" || host.endsWith(".googlevideo.com")
    }
}
