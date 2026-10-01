package com.luno.mobile.data.export

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Strict codec for the mobile-only favorites backup; desktop transfer v1 is unchanged. */
object FavoritesMetadataBackupCodec {
    const val MAX_INPUT_CHARS = 5 * 1024 * 1024
    private const val MAX_FAVORITES = 50_000
    private const val MAX_STRING_LENGTH = 2_000
    private val identityPattern = Regex("^[a-f0-9]{64}$")
    private val sourceIdPattern = Regex("^[A-Za-z0-9._~:-]{1,256}$")
    private val controlPattern = Regex("[\\u0000-\\u001f\\u007f]")

    fun encode(backup: FavoritesMetadataBackup): String {
        val json = toJson(backup).toString()
        return toJson(decodeAndValidate(json)).toString(2)
    }

    fun decodeAndValidate(input: String): FavoritesMetadataBackup {
        if (input.length > MAX_INPUT_CHARS) {
            throw ManifestFormatException("Favorites backup is too large")
        }
        val root = try {
            JSONObject(input)
        } catch (error: Exception) {
            throw ManifestFormatException("Favorites backup is not valid JSON: ${error.message ?: "parse error"}")
        }
        requireExactKeys(root, setOf("format", "version", "created_at", "favorites"), "backup")
        if (root.string("format", "format") != FAVORITES_BACKUP_FORMAT) {
            throw ManifestFormatException("Unsupported favorites backup format")
        }
        if (root.long("version", "version") != FAVORITES_BACKUP_VERSION.toLong()) {
            throw ManifestFormatException("Unsupported favorites backup version")
        }
        val createdAt = root.string("created_at", "created_at")
        if (!createdAt.endsWith("Z") || runCatching { Instant.parse(createdAt) }.isFailure) {
            throw ManifestFormatException("created_at must be an ISO-8601 UTC timestamp")
        }

        val array = root.array("favorites", "favorites")
        if (array.length() > MAX_FAVORITES) {
            throw ManifestFormatException("Too many favorites")
        }
        val identities = mutableSetOf<String>()
        val entries = buildList {
            for (index in 0 until array.length()) {
                val item = array.get(index) as? JSONObject
                    ?: throw ManifestFormatException("favorites[$index] must be an object")
                val field = "favorites[$index]"
                requireExactKeys(
                    item,
                    setOf("identity_sha256", "title", "artist", "album", "duration_ms", "source"),
                    field
                )
                val identity = item.string("identity_sha256", "$field.identity_sha256")
                if (!identityPattern.matches(identity) || !identities.add(identity)) {
                    throw ManifestFormatException("Invalid or duplicate $field.identity_sha256")
                }
                val title = item.string("title", "$field.title")
                val artist = item.string("artist", "$field.artist")
                val album = item.string("album", "$field.album")
                if (title.isBlank()) throw ManifestFormatException("$field.title cannot be blank")
                listOf(title, artist, album).forEachIndexed { textIndex, value ->
                    if (value.length > MAX_STRING_LENGTH || controlPattern.containsMatchIn(value)) {
                        throw ManifestFormatException("Invalid text in $field (${textIndex + 1})")
                    }
                }
                val durationMs = item.long("duration_ms", "$field.duration_ms")
                if (durationMs !in 0L..86_400_000L) {
                    throw ManifestFormatException("Invalid $field.duration_ms")
                }
                val source = when (val raw = item.get("source")) {
                    JSONObject.NULL -> null
                    is JSONObject -> {
                        requireExactKeys(raw, setOf("provider", "id"), "$field.source")
                        val provider = raw.string("provider", "$field.source.provider")
                        val id = raw.string("id", "$field.source.id")
                        if (provider != "youtube" || !sourceIdPattern.matches(id)) {
                            throw ManifestFormatException("Invalid $field.source")
                        }
                        ManifestSource(provider, id)
                    }
                    else -> throw ManifestFormatException("$field.source must be an object or null")
                }
                add(FavoriteMetadataEntry(identity, title, artist, album, durationMs, source))
            }
        }
        return FavoritesMetadataBackup(createdAt, entries)
    }

    private fun toJson(backup: FavoritesMetadataBackup): JSONObject = JSONObject().apply {
        put("format", FAVORITES_BACKUP_FORMAT)
        put("version", FAVORITES_BACKUP_VERSION)
        put("created_at", backup.createdAt)
        put("favorites", JSONArray().apply {
            backup.favorites.forEach { favorite ->
                put(JSONObject().apply {
                    put("identity_sha256", favorite.identitySha256)
                    put("title", favorite.title)
                    put("artist", favorite.artist)
                    put("album", favorite.album)
                    put("duration_ms", favorite.durationMs)
                    put("source", favorite.source?.let { source ->
                        JSONObject().put("provider", source.provider).put("id", source.id)
                    } ?: JSONObject.NULL)
                })
            }
        })
    }

    private fun requireExactKeys(value: JSONObject, expected: Set<String>, field: String) {
        val actual = value.keys().asSequence().toSet()
        if (actual != expected) {
            throw ManifestFormatException("Unexpected fields in $field")
        }
    }

    private fun JSONObject.string(key: String, field: String): String =
        get(key) as? String ?: throw ManifestFormatException("$field must be a string")

    private fun JSONObject.long(key: String, field: String): Long {
        val number = get(key) as? Number
            ?: throw ManifestFormatException("$field must be an integer")
        val value = number.toLong()
        if (number.toDouble() != value.toDouble()) {
            throw ManifestFormatException("$field must be an integer")
        }
        return value
    }

    private fun JSONObject.array(key: String, field: String): JSONArray =
        get(key) as? JSONArray ?: throw ManifestFormatException("$field must be an array")
}
