package com.luno.mobile.data.update

import android.content.SharedPreferences
import org.json.JSONObject

enum class UpdatePhase { PERMISSION, DOWNLOADING, VERIFYING, READY, STAGING, CONFIRM, INSTALLING, FAILED }

data class UpdateTransaction(
    val release: GitHubRelease,
    val installedCode: Long,
    val phase: UpdatePhase,
    val workId: String? = null,
    val sessionId: Int? = null,
    val confirmationUri: String? = null,
    val confirmationLaunched: Boolean = false,
    val autoInstall: Boolean = true,
    val message: String? = null
)

internal fun GitHubRelease.toJson(): JSONObject = JSONObject()
    .put("tag", tagName).put("version", version).put("name", name)
    .put("notes", notes).put("url", releaseUrl).put("apk", apkUrl)
    .put("size", apkSize).put("digest", apkDigest)

internal fun releaseFromJson(json: JSONObject) = GitHubRelease(
    tagName = json.getString("tag"), version = json.getString("version"),
    name = json.getString("name"), notes = json.optString("notes"),
    releaseUrl = json.getString("url"), apkUrl = json.nullableString("apk"),
    apkSize = json.optLong("size"), apkDigest = json.nullableString("digest")
)

private fun JSONObject.nullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

/** Small update receipts are separate from Room music metadata and excluded from backup. */
internal class UpdateTransactionStore(private val preferences: SharedPreferences) {
    fun load(): UpdateTransaction? = runCatching {
        val json = JSONObject(preferences.getString("transaction", null) ?: return null)
        UpdateTransaction(
            release = releaseFromJson(json.getJSONObject("release")),
            installedCode = json.getLong("installedCode"),
            phase = UpdatePhase.valueOf(json.getString("phase")),
            workId = json.nullableString("workId"),
            sessionId = if (json.has("sessionId") && !json.isNull("sessionId")) json.getInt("sessionId") else null,
            confirmationUri = json.nullableString("confirmationUri"),
            confirmationLaunched = json.optBoolean("confirmationLaunched"),
            autoInstall = json.optBoolean("autoInstall", true),
            message = json.nullableString("message")
        )
    }.getOrNull()

    fun save(transaction: UpdateTransaction?) {
        val json = transaction?.let {
            JSONObject().put("release", it.release.toJson()).put("installedCode", it.installedCode)
                .put("phase", it.phase.name).put("workId", it.workId).put("sessionId", it.sessionId)
                .put("confirmationUri", it.confirmationUri).put("confirmationLaunched", it.confirmationLaunched)
                .put("autoInstall", it.autoInstall).put("message", it.message).toString()
        }
        // The write must land before handing ownership to WorkManager or Android's installer.
        preferences.edit().putString("transaction", json).commit()
    }

    fun reconcile(version: String, code: Long, wasUpdated: Boolean): String? {
        val transaction = load()
        val previousCode = if (preferences.contains("seenCode")) preferences.getLong("seenCode", 0L) else null
        val updated = isCompletedUpdate(transaction, version, code) ||
            (previousCode != null && code > previousCode) || (previousCode == null && wasUpdated)
        if (updated) {
            preferences.edit().remove("transaction").putString("receipt", version)
                .putLong("seenCode", code).commit()
        } else {
            preferences.edit().putLong("seenCode", code).commit()
        }
        return receipt()
    }

    fun receipt(): String? = preferences.getString("receipt", null)
    fun acknowledge() { preferences.edit().remove("receipt").commit() }
}

internal fun isCompletedUpdate(transaction: UpdateTransaction?, version: String, code: Long): Boolean =
    transaction != null && code > transaction.installedCode &&
        (GitHubReleaseService.compareVersions(version, transaction.release.version) ?: -1) >= 0
