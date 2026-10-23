package com.luno.mobile.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class PlaybackQueueStoreTest {
    private lateinit var context: Context
    private lateinit var store: PlaybackQueueStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        android.util.AtomicFile(File(context.filesDir, "playback_queue.json")).delete()
        context.getSharedPreferences("playback_queue_cursor", Context.MODE_PRIVATE).edit().clear().commit()
        store = PlaybackQueueStore(context)
    }

    private fun snapshot(): PlaybackQueueSnapshot {
        val controller = MusicController(context)
        val item = controller.buildMediaItem(MediaTrack(
            uri = "content://music/track", title = "Song", artist = "Artist", album = "Album",
            durationMs = 120000L, artworkUri = "file:///art.jpg",
            playbackSource = PlaybackSource("Playlist", "Named", 42)), manuallyQueued = true)
        controller.release()
        return PlaybackQueueSnapshot(listOf(item, item), listOf(1, 0), true, 2,
            PlaybackCursor("generation", 1, 123L, false))
    }

    @Test
    fun `metadata and manual membership round trip while cursor updates preserve queue`() {
        store.save(snapshot())
        store.checkpoint(PlaybackCursor("generation", 0, 9876L, true))
        val restored = requireNotNull(PlaybackQueueStore(context).read())
        assertThat(restored.items).hasSize(2)
        assertThat(restored.shuffleOrder).containsExactly(1, 0).inOrder()
        assertThat(restored.cursor).isEqualTo(PlaybackCursor("generation", 0, 9876L, true))
        val metadata = restored.items[0].mediaMetadata
        assertThat(metadata.title.toString()).isEqualTo("Song")
        assertThat(metadata.artworkUri.toString()).isEqualTo("file:///art.jpg")
        assertThat(metadata.artworkData).isNull()
        assertThat(metadata.extras!!.getLong(MusicController.METADATA_PLAYLIST_ID)).isEqualTo(42L)
        assertThat(metadata.extras!!.getBoolean(MusicController.METADATA_MANUALLY_QUEUED)).isTrue()
    }

    @Test
    fun `stale or out of bounds cursor cannot select an item from another queue generation`() {
        val original = snapshot()
        store.save(original)
        store.checkpoint(PlaybackCursor("older-generation", 0, 555L, true))
        assertThat(store.read()!!.cursor).isEqualTo(original.cursor)
        store.checkpoint(PlaybackCursor("generation", 99, 555L, true))
        assertThat(store.read()!!.cursor).isEqualTo(original.cursor)
    }

    @Test
    fun `corruption unsupported versions and invalid permutations fail safely`() {
        val file = File(context.filesDir, "playback_queue.json")
        file.writeText("{broken")
        assertThat(store.read()).isNull()
        store.save(snapshot())
        val valid = file.readText()
        file.writeText(valid.replace("\"version\":1", "\"version\":99"))
        assertThat(store.read()).isNull()
        file.writeText(valid.replace("\"order\":[1,0]", "\"order\":[0,0]"))
        assertThat(store.read()).isNull()
    }
}
