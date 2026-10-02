package com.luno.mobile.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.coroutineContext

sealed interface ApkInstallResult {
    data object InstallerOpened : ApkInstallResult
    data object UnknownSourcesPermissionRequired : ApkInstallResult
    data class Failure(val message: String) : ApkInstallResult
}

internal data class ApkIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signers: Set<String>
)

internal fun validateUpdateIdentity(installed: ApkIdentity, update: ApkIdentity, expectedVersion: String) {
    if (update.packageName != installed.packageName) throw IOException("The update belongs to a different app")
    if (update.signers.isEmpty() || update.signers != installed.signers) {
        throw IOException("The update's signing key does not match this installation. Your library has not been changed.")
    }
    if (update.versionCode <= installed.versionCode) throw IOException("This APK is not newer than the installed app")
    if (GitHubReleaseService.compareVersions(update.versionName, expectedVersion) != 0) {
        throw IOException("The APK version does not match the GitHub release")
    }
}

/** Downloads and verifies an APK independently from permission and installer prompts. */
object ApkInstaller {
    private const val MAX_APK_BYTES = 200L * 1024L * 1024L
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun download(
        context: Context,
        release: GitHubRelease,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "luno-update.apk")
        if (target.isFile && runCatching { verify(context, target, release) }.isSuccess) {
            onProgress(target.length(), target.length())
            return@withContext target
        }
        val url = release.apkUrl?.toHttpUrlOrNull() ?: throw IOException("This release has no downloadable APK")
        requireReleaseAssetUrl(url)
        val temporary = File.createTempFile("luno-update-", ".part", context.cacheDir)
        try {
            assetResponse(url).use { response ->
                if (!response.isSuccessful) throw IOException("GitHub returned HTTP ${response.code}. Try again.")
                val body = response.body ?: throw IOException("GitHub returned an empty APK")
                val total = release.apkSize.takeIf { it > 0L } ?: body.contentLength()
                if (total > MAX_APK_BYTES || body.contentLength() > MAX_APK_BYTES) throw IOException("The APK is unexpectedly large")
                temporary.outputStream().buffered().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            if (downloaded > MAX_APK_BYTES) throw IOException("The APK is unexpectedly large")
                            output.write(buffer, 0, count)
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            verify(context, temporary, release)
            if (!temporary.renameTo(target)) temporary.copyTo(target, overwrite = true)
            target
        } finally {
            temporary.delete()
        }
    }

    fun openInstaller(context: Context, file: File): ApkInstallResult {
        if (!file.isFile) return ApkInstallResult.Failure("The downloaded update is missing. Download it again.")
        if (!context.packageManager.canRequestPackageInstalls()) return ApkInstallResult.UnknownSourcesPermissionRequired
        return try {
            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            ApkInstallResult.InstallerOpened
        } catch (_: Exception) {
            ApkInstallResult.Failure("Android could not open the update installer. Try again.")
        }
    }

    internal fun requireReleaseAssetUrl(url: HttpUrl) {
        val prefix = "/${GitHubReleaseService.DEFAULT_OWNER}/${GitHubReleaseService.DEFAULT_REPOSITORY}/releases/download/"
        val trusted = url.host == "github.com" && url.encodedPath.startsWith(prefix)
        if (url.scheme != "https" || !trusted) throw IOException("The APK link does not belong to Luno's GitHub releases")
    }

    private fun assetResponse(initialUrl: HttpUrl): Response {
        var url = initialUrl
        repeat(6) {
            val request = Request.Builder().url(url)
                .header("User-Agent", "Luno (Android)")
                .header("Accept", "application/octet-stream")
                .build()
            val response = client.newCall(request).execute()
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val destination = response.header("Location")?.let(url::resolve)
            response.close()
            if (destination == null || destination.scheme != "https" || destination.host !in setOf(
                    "github.com", "api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"
                )) throw IOException("GitHub returned an untrusted download redirect")
            url = destination
        }
        throw IOException("GitHub returned too many download redirects")
    }

    @Suppress("DEPRECATION")
    private fun verify(context: Context, file: File, release: GitHubRelease) {
        if (file.length() <= 0L || file.length() > MAX_APK_BYTES) throw IOException("The downloaded APK is incomplete")
        if (release.apkSize > 0L && file.length() != release.apkSize) throw IOException("The downloaded APK is incomplete")
        release.apkDigest?.let { digest ->
            val expected = digest.removePrefix("sha256:")
            if (!expected.matches(Regex("[0-9a-fA-F]{64}")) || !sha256(file).equals(expected, ignoreCase = true)) {
                throw IOException("The downloaded APK does not match GitHub's checksum")
            }
        }
        val manager = context.packageManager
        val archive = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw IOException("The download is not a valid signed Android APK")
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        validateUpdateIdentity(installed.identity(), archive.identity(), release.version)
    }

    private fun PackageInfo.identity() = ApkIdentity(
        packageName = packageName,
        versionName = versionName.orEmpty(),
        versionCode = longVersionCode,
        signers = signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
