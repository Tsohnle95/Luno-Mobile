package com.luno.mobile.data.update

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

class AppUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            val release = releaseFromJson(JSONObject(inputData.getString(RELEASE) ?: error("Missing update release")))
            var lastProgress = 0L
            ApkInstaller.download(
                context = applicationContext,
                release = release,
                target = updateFile(applicationContext, id.toString()),
                onVerifying = { setProgressAsync(workDataOf(VERIFYING to true)) },
                onProgress = { bytes, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgress >= 250L || bytes == total) {
                        lastProgress = now
                        setProgressAsync(workDataOf(BYTES to bytes, TOTAL to total))
                    }
                }
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(workDataOf(ERROR to (e.message ?: "The download failed. Check your connection and retry.")))
        }
    }

    companion object {
        const val UNIQUE_WORK = "luno-app-update"
        const val RELEASE = "release"
        const val BYTES = "bytes"
        const val TOTAL = "total"
        const val VERIFYING = "verifying"
        const val ERROR = "error"
        internal fun updateFile(context: Context, workId: String): File = File(context.cacheDir, "luno-update-$workId.apk")
    }
}
