package com.luno.mobile.data.export

/** A mobile-only portable backup of favorite flags and safe track match keys. */
const val FAVORITES_BACKUP_FORMAT = "luno.mobile.favorites"
const val FAVORITES_BACKUP_VERSION = 1

data class FavoriteMetadataEntry(
    /** SHA-256 of the local URI; the URI itself is never written to the backup. */
    val identitySha256: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val source: ManifestSource?
)

data class FavoritesMetadataBackup(
    val createdAt: String,
    val favorites: List<FavoriteMetadataEntry>
)

data class FavoritesBackupImportResult(
    val restoredCount: Int,
    val ambiguousCount: Int,
    val missingCount: Int
)
