package com.luno.mobile.playback

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Test

class ParallelRangeDownloaderTest {

    @Test
    fun downloadsAndAssemblesValidatedRanges() = withServer { server, root ->
        val content = ByteArray(23) { (it * 13).toByte() }
        val requestedRanges = CopyOnWriteArrayList<String>()
        server.dispatcher = rangeDispatcher(content) { request ->
            request.getHeader("Range")?.also(requestedRanges::add)
        }

        val target = File(root, "ranged.tmp")
        val result = downloader().download(
            request = request(server),
            targetFile = target,
            isStopped = { false },
            onProgress = { _, _ -> }
        )

        assertThat(result).isInstanceOf(ParallelRangeDownloader.Outcome.Completed::class.java)
        assertThat(target.readBytes()).isEqualTo(content)
        assertThat(requestedRanges).containsExactly(
            "bytes=0-3",
            "bytes=4-7",
            "bytes=8-11",
            "bytes=12-15",
            "bytes=16-19",
            "bytes=20-22"
        )
    }

    @Test
    fun ignoredRangeLeavesTargetCleanForSingleStreamFallback() = withServer { server, root ->
        val content = ByteArray(32) { it.toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(content))
        }

        val target = File(root, "fallback.mp3")
        val result = downloader().download(
            request(server), target, { false }, { _, _ -> }
        )
        assertThat(result).isEqualTo(ParallelRangeDownloader.Outcome.Unsupported)
        assertThat(target.exists()).isFalse()
        assertThat(root.listFiles().orEmpty()).isEmpty()
        assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=0-3")

        // This is the worker's existing un-ranged fallback request. The
        // range probe must not leave bytes behind that could be appended or
        // mistaken for a complete file.
        client.newCall(request(server)).execute().use { response ->
            target.writeBytes(response.body!!.bytes())
        }
        assertThat(server.takeRequest().getHeader("Range")).isNull()
        assertThat(target.readBytes()).isEqualTo(content)
    }

    @Test
    fun malformedLaterRangeDiscardsAllPartialChunks() = withServer { server, root ->
        val content = ByteArray(20) { (it + 40).toByte() }
        server.dispatcher = rangeDispatcher(content) { request ->
            request.getHeader("Range")
        }.let { validDispatcher ->
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val requestedRange = request.getHeader("Range")
                    if (requestedRange == "bytes=0-3") return validDispatcher.dispatch(request)
                    val response = validDispatcher.dispatch(request)
                    return response.setHeader("Content-Range", "bytes 0-3/${content.size}")
                }
            }
        }

        val target = File(root, "malformed.tmp")
        val result = downloader().download(
            request(server), target, { false }, { _, _ -> }
        )

        assertThat(result).isEqualTo(ParallelRangeDownloader.Outcome.Unsupported)
        assertThat(target.exists()).isFalse()
        assertThat(root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun cancellationRemovesPartialChunksAndTemporaryDirectory() = withServer { server, root ->
        val content = ByteArray(24) { it.toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 0-3/${content.size}")
                .addHeader("Content-Type", "audio/mpeg")
                .setHeadersDelay(5, TimeUnit.SECONDS)
                .setBody(Buffer().write(content, 0, 4))
        }

        val target = File(root, "cancelled.tmp")
        coroutineScope {
            val task = async {
                downloader().download(request(server), target, { false }, { _, _ -> })
            }
            delay(100)
            task.cancelAndJoin()
        }

        assertThat(target.exists()).isFalse()
        assertThat(root.listFiles().orEmpty()).isEmpty()
    }

    private fun downloader() = ParallelRangeDownloader(
        client = client,
        chunkSizeBytes = 4L,
        minimumParallelSizeBytes = 8L,
        maximumParallelRequests = 2,
        isEligibleHost = { true }
    )

    private fun request(server: MockWebServer): Request = Request.Builder()
        .url(server.url("/audio"))
        .build()

    private fun rangeDispatcher(
        content: ByteArray,
        onRequest: (RecordedRequest) -> Unit = {}
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            onRequest(request)
            val range = request.getHeader("Range")
                ?.let { RANGE.matchEntire(it) }
                ?: return MockResponse().setResponseCode(200).setBody(Buffer().write(content))
            val start = range.groupValues[1].toInt()
            val end = minOf(range.groupValues[2].toInt(), content.lastIndex)
            if (start > end || start >= content.size) return MockResponse().setResponseCode(416)
            val body = Buffer().write(content, start, end - start + 1)
            return MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes $start-$end/${content.size}")
                .addHeader("Content-Type", "audio/mpeg")
                .setBody(body)
        }
    }

    private fun withServer(block: suspend (MockWebServer, File) -> Unit) = runBlocking {
        val root = kotlin.io.path.createTempDirectory("luno-range-test").toFile()
        val server = MockWebServer()
        server.start()
        try {
            block(server, root)
        } finally {
            server.shutdown()
            root.deleteRecursively()
        }
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        private val RANGE = Regex("bytes=([0-9]+)-([0-9]+)")
    }
}
