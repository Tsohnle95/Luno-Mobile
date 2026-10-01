package com.luno.mobile.data.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class FavoritesMetadataBackupCodecTest {
    private fun backup() = FavoritesMetadataBackup(
        createdAt = "2026-10-01T00:00:00Z",
        favorites = listOf(
            FavoriteMetadataEntry(
                identitySha256 = "a".repeat(64),
                title = "Song",
                artist = "Artist",
                album = "Album",
                durationMs = 210_000L,
                source = ManifestSource("youtube", "abc12345678")
            )
        )
    )

    @Test
    fun roundTripPreservesSafeFavoriteMetadataAndSource() {
        val encoded = FavoritesMetadataBackupCodec.encode(backup())

        assertThat(FavoritesMetadataBackupCodec.decodeAndValidate(encoded)).isEqualTo(backup())
        assertThat(encoded).contains("luno.mobile.favorites")
        assertThat(encoded).doesNotContain("content://")
    }

    @Test
    fun rejectsUnknownFieldsAndInvalidIdentity() {
        val encoded = FavoritesMetadataBackupCodec.encode(backup())
        val withUnknownField = encoded.replace("\"format\"", "\"extra\":true,\"format\"")
        val invalidIdentity = encoded.replace("a".repeat(64), "not-a-hash")

        assertThat(runCatching {
            FavoritesMetadataBackupCodec.decodeAndValidate(withUnknownField)
        }.isFailure).isTrue()
        assertThat(runCatching {
            FavoritesMetadataBackupCodec.decodeAndValidate(invalidIdentity)
        }.isFailure).isTrue()
    }
}
