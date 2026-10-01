package com.luno.mobile.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class MusicFolderRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val treeUri = Uri.parse(
        "content://com.android.externalstorage.documents/tree/6661-6334%3Aapp_music_folder"
    )

    @Test
    fun saveTreeUriPersistsReadAndWriteGrantsForSelectedTree() {
        val flags = mutableListOf<Int>()
        val repository = MusicFolderRepository(
            context,
            MusicFolderRepository.TreePermissionPersister { uri, flag ->
                assertThat(uri).isEqualTo(treeUri)
                flags += flag
            }
        )

        val writable = repository.saveTreeUri(treeUri)

        assertThat(flags).containsExactly(
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ).inOrder()
        assertThat(repository.loadTreeUri()).isEqualTo(treeUri.toString())
        // The test persister records the calls; the platform permission list
        // remains empty in Robolectric, so production preflight stays strict.
        assertThat(writable).isFalse()
    }

    @Test
    fun missingWriteGrantKeepsAudioAndOffersFolderReselection() {
        val repository = MusicFolderRepository(
            context,
            MusicFolderRepository.TreePermissionPersister { _, _ -> }
        )
        repository.saveTreeUri(treeUri)
        val audio = File(context.cacheDir, "saf-preflight-audio.m4a")
            .apply { writeBytes(byteArrayOf(3, 1, 4, 1, 5)) }

        val failure = runCatching {
            repository.copyFileToSelectedFolder(
                sourceFile = audio,
                displayName = "song.m4a",
                mimeType = "audio/mp4"
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure!!.message).contains("Choose the Music folder again")
        assertThat(repository.destinationFailureNotice(failure))
            .contains("Choose the Music folder again in Settings")
        assertThat(audio.readBytes()).isEqualTo(byteArrayOf(3, 1, 4, 1, 5))
        audio.delete()
    }

    @Test
    fun securityFailureNoticeDoesNotExposeProviderUri() {
        val repository = MusicFolderRepository(context)
        val failure = SecurityException(
            "Permission Denial writing content://private/tree/path"
        )

        val notice = repository.destinationFailureNotice(failure)

        assertThat(notice).contains("write access")
        assertThat(notice).contains("Choose the Music folder again")
        assertThat(notice).doesNotContain("content://")
        assertThat(notice).doesNotContain("private/tree")
    }
}
