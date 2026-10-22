package com.luno.mobile.playback

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class Mp4VideoDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build(),
    private val maximumBytes: Long = VIDEO_CACHE_BYTES,
    private val rangeDownloader: ParallelRangeDownloader = ParallelRangeDownloader(client, maximumSizeBytes = maximumBytes)
) : TemporaryVideoDownloader {
    override suspend fun download(source: TemporaryVideoSource, target: File, progress: (Float?) -> Unit): File =
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val staged = File(target.parentFile, ".${target.name}-${UUID.randomUUID()}.range-stage")
            val context = currentCoroutineContext()
            try {
                val result = rangeDownloader.download(request(source), staged, isStopped = { !context.isActive }) { done, total ->
                    progress((done.toFloat() / total).coerceAtMost(0.99f))
                }
                if (result is ParallelRangeDownloader.Outcome.Completed) {
                    context.ensureActive()
                    publish(staged, target)
                    progress(1f)
                    target
                } else downloadSingle(source, target, progress)
            } finally { staged.delete() }
        }

    private fun request(source: TemporaryVideoSource) = Request.Builder().url(source.url)
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0.0.0 Mobile Safari/537.36")
        .header("Referer", "https://www.youtube.com/").build()

    private fun publish(partial: File, target: File) {
        check(partial.length() in 12..maximumBytes) { "Video exceeds temporary download limit" }
        val header = partial.inputStream().use { input -> ByteArray(12).also { input.read(it) } }
        check(String(header, 4, 4, Charsets.US_ASCII) == "ftyp") { "Server did not return an MP4 video" }
        check(partial.renameTo(target)) { "Could not publish temporary video" }
    }

    private suspend fun downloadSingle(source: TemporaryVideoSource, target: File, progress: (Float?) -> Unit): File =
        suspendCancellableCoroutine { continuation ->
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, ".${target.name}-${UUID.randomUUID()}.part")
            val call = client.newCall(request(source))
            continuation.invokeOnCancellation { call.cancel(); partial.delete() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    partial.delete()
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            check(response.isSuccessful) { "Video server returned HTTP ${response.code}" }
                            val body = response.body ?: error("Empty video response")
                            val total = body.contentLength()
                            check(total <= maximumBytes) { "Video exceeds temporary download limit" }
                            progress(null)
                            var written = 0L
                            body.byteStream().use { input ->
                                partial.outputStream().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        if (!continuation.isActive) throw IOException("Video download cancelled")
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        written += count
                                        check(written <= maximumBytes) { "Video exceeds temporary download limit" }
                                        output.write(buffer, 0, count)
                                        progress(if (total > 0) (written.toFloat() / total).coerceAtMost(0.99f) else null)
                                    }
                                }
                            }
                            check(written >= 12 && (total < 0 || total == written)) { "Incomplete video download" }
                            check(continuation.isActive) { "Video download cancelled" }
                            publish(partial, target)
                            progress(1f)
                            if (continuation.isActive) continuation.resume(target)
                        }
                    } catch (error: Exception) {
                        partial.delete()
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
}
