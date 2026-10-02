package com.luno.mobile.data.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

data class GitHubRelease(
    val tagName: String,
    val version: String,
    val name: String,
    val notes: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val apkSize: Long = 0L,
    val apkDigest: String? = null
)

sealed interface ReleaseCheckResult {
    data class UpdateAvailable(
        val currentVersion: String,
        val release: GitHubRelease
    ) : ReleaseCheckResult

    data class UpToDate(
        val currentVersion: String,
        val latestVersion: String
    ) : ReleaseCheckResult

    data class Failure(val message: String) : ReleaseCheckResult
}

/** Reads the latest stable public GitHub release used for sideloaded APK updates. */
open class GitHubReleaseService(
    private val owner: String = DEFAULT_OWNER,
    private val repository: String = DEFAULT_REPOSITORY,
    private val baseUrl: String = "https://api.github.com",
    private val client: OkHttpClient = defaultClient()
) {
    companion object {
        const val DEFAULT_OWNER = "Tsohnle95"
        const val DEFAULT_REPOSITORY = "Luno-Mobile"

        private const val TIMEOUT_SECONDS = 8L
        private val VERSION_PATTERN = Regex("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:[-+].*)?$")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        internal fun normalizeVersion(value: String): List<Int>? {
            val match = VERSION_PATTERN.matchEntire(value.trim()) ?: return null
            return (1..3).map { index ->
                match.groupValues[index].ifBlank { "0" }.toIntOrNull() ?: return null
            }
        }

        internal fun compareVersions(left: String, right: String): Int? {
            val leftParts = normalizeVersion(left) ?: return null
            val rightParts = normalizeVersion(right) ?: return null
            return leftParts.zip(rightParts)
                .firstOrNull { (leftPart, rightPart) -> leftPart != rightPart }
                ?.let { (leftPart, rightPart) -> leftPart.compareTo(rightPart) }
                ?: 0
        }
    }

    open suspend fun checkForUpdate(currentVersion: String): ReleaseCheckResult {
        val current = currentVersion.trim()
        if (normalizeVersion(current) == null) {
            return ReleaseCheckResult.Failure("The installed app version is invalid")
        }

        return try {
            val url = "$baseUrl/repos/$owner/$repository/releases/latest".toHttpUrl()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Luno/$current (Android)")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            val responseBody = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw ReleaseCheckException(response.code)
                    }
                    response.body?.string().orEmpty()
                }
            }
            val json = JSONObject(responseBody)
            val tagName = json.optString("tag_name").trim()
            val latestVersion = normalizeVersion(tagName)
                ?: return ReleaseCheckResult.Failure("The latest release has an invalid version tag")
            val comparison = compareVersions(current, latestVersion.joinToString("."))
                ?: return ReleaseCheckResult.Failure("Could not compare app versions")
            val asset = findApkAsset(json, tagName)

            if (comparison >= 0) {
                ReleaseCheckResult.UpToDate(current, latestVersion.joinToString("."))
            } else {
                ReleaseCheckResult.UpdateAvailable(
                    currentVersion = current,
                    release = GitHubRelease(
                        tagName = tagName,
                        version = latestVersion.joinToString("."),
                        name = json.optString("name").ifBlank { tagName },
                        notes = json.optString("body").trim(),
                        releaseUrl = json.optString("html_url").trim(),
                        apkUrl = asset?.optString("browser_download_url")?.takeIf { it.isNotBlank() },
                        apkSize = asset?.optLong("size") ?: 0L,
                        apkDigest = asset?.optString("digest")?.takeIf { it.startsWith("sha256:") }
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReleaseCheckException) {
            when (e.status) {
                404 -> ReleaseCheckResult.Failure("No public GitHub release was found for Luno-Mobile.")
                403, 429 -> ReleaseCheckResult.Failure("GitHub's request limit was reached. Try again later.")
                else -> ReleaseCheckResult.Failure("Could not check GitHub Releases (GitHub returned HTTP ${e.status})")
            }
        } catch (_: Exception) {
            ReleaseCheckResult.Failure("Could not reach GitHub — check your connection")
        }
    }

    private fun findApkAsset(json: JSONObject, tagName: String): JSONObject? {
        val assets = json.optJSONArray("assets") ?: return null
        var fallback: JSONObject? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url").trim()
            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                if (name == "Luno-Mobile-$tagName.apk") return asset
                if (fallback == null) fallback = asset
            }
        }
        return fallback
    }

    private class ReleaseCheckException(val status: Int) : Exception()
}
