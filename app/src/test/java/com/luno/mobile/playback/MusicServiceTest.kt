package com.luno.mobile.playback

import android.app.Application
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class MusicServiceTest {
    @Before
    fun clearRecovery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        android.util.AtomicFile(java.io.File(context.filesDir, "playback_queue.json")).delete()
        context.getSharedPreferences("playback_queue_cursor", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `legacy single song recovery still upgrades when queue snapshot is missing or corrupt`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("playback_restore", Context.MODE_PRIVATE).edit()
            .putString("uri", "file:///legacy.mp3").putString("title", "Legacy")
            .putLong("position_ms", 3210L).putBoolean("play_when_ready", false)
            .putBoolean("shuffle", true).commit()
        java.io.File(context.filesDir, "playback_queue.json").writeText("{broken")
        val service = Robolectric.buildService(MusicService::class.java).create()
        try {
            val field = MusicService::class.java.getDeclaredField("player").apply { isAccessible = true }
            val player = field.get(service.get()) as ExoPlayer
            assertThat(player.currentMediaItem?.mediaId).isEqualTo("file:///legacy.mp3")
            assertThat(player.currentPosition).isEqualTo(3210L)
            assertThat(player.shuffleModeEnabled).isTrue()
        } finally {
            service.destroy()
            service.get().awaitPlaybackPersistence()
        }
        assertThat(PlaybackQueueStore(context).read()!!.items).hasSize(1)
    }

    @Test
    fun `queue edits and an explicitly cleared queue replace previous recovery`() {
        val field = MusicService::class.java.getDeclaredField("player").apply { isAccessible = true }
        val first = Robolectric.buildService(MusicService::class.java).create()
        val second = try {
            val player = field.get(first.get()) as ExoPlayer
            player.setMediaItems(listOf("a", "b", "c").map {
                MediaItem.Builder().setMediaId(it).setUri("file:///$it.mp3").build()
            })
            shadowOf(Looper.getMainLooper()).idle()
            player.removeMediaItem(1)
            player.addMediaItem(MediaItem.Builder().setMediaId("added").setUri("file:///added.mp3").build())
            shadowOf(Looper.getMainLooper()).idle()
            first.destroy()
            first.get().awaitPlaybackPersistence()
            Robolectric.buildService(MusicService::class.java).create()
        } catch (error: Throwable) {
            first.destroy()
            throw error
        }
        val restored = field.get(second.get()) as ExoPlayer
        assertThat((0 until restored.mediaItemCount).map { restored.getMediaItemAt(it).mediaId })
            .containsExactly("a", "c", "added").inOrder()
        restored.clearMediaItems()
        shadowOf(Looper.getMainLooper()).idle()
        second.destroy()
        second.get().awaitPlaybackPersistence()
        val third = Robolectric.buildService(MusicService::class.java).create()
        try {
            assertThat((field.get(third.get()) as ExoPlayer).mediaItemCount).isEqualTo(0)
        } finally {
            third.destroy()
            third.get().awaitPlaybackPersistence()
        }
    }

    @Test
    fun `temporary preview never overwrites saved physical queue`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val field = MusicService::class.java.getDeclaredField("player").apply { isAccessible = true }
        val first = Robolectric.buildService(MusicService::class.java).create()
        val items = listOf("physical-a", "physical-b").map {
            MediaItem.Builder().setMediaId(it).setUri("file:///$it.mp3").build()
        }
        try {
            val player = field.get(first.get()) as ExoPlayer
            player.setMediaItems(items)
            shadowOf(Looper.getMainLooper()).idle()
            first.get().awaitPlaybackPersistence()
            val preview = MusicController(context).buildMediaItem(
                MediaTrack(uri = "file:///preview.mp3", title = "Preview", artist = "Artist", isTransient = true))
            player.setMediaItem(preview)
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            first.destroy()
            first.get().awaitPlaybackPersistence()
        }
        val second = Robolectric.buildService(MusicService::class.java).create()
        try {
            val player = field.get(second.get()) as ExoPlayer
            assertThat((0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
                .containsExactly("physical-a", "physical-b").inOrder()
            assertThat(player.playWhenReady).isFalse()
        } finally {
            second.destroy()
            second.get().awaitPlaybackPersistence()
        }
    }

    @Test
    fun `full queue including duplicates and shuffled traversal survives recreation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("playback_restore", Context.MODE_PRIVATE).edit().clear().commit()
        val field = MusicService::class.java.getDeclaredField("player").apply { isAccessible = true }
        val first = Robolectric.buildService(MusicService::class.java).create()
        val order = intArrayOf(2, 0, 3, 1)
        try {
            val player = field.get(first.get()) as ExoPlayer
            player.setMediaItems(listOf("a", "b", "a", "c").map {
                MediaItem.Builder().setMediaId(it).setUri("file:///$it.mp3").build()
            }, 2, 12345L)
            player.setShuffleOrder(DefaultShuffleOrder(order, 7L))
            player.shuffleModeEnabled = true
            player.repeatMode = Player.REPEAT_MODE_ALL
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            first.destroy()
            first.get().awaitPlaybackPersistence()
        }
        val second = Robolectric.buildService(MusicService::class.java).create()
        try {
            val restored = field.get(second.get()) as ExoPlayer
            assertThat((0 until restored.mediaItemCount).map { restored.getMediaItemAt(it).mediaId })
                .containsExactly("a", "b", "a", "c").inOrder()
            assertThat(restored.currentMediaItemIndex).isEqualTo(2)
            assertThat(restored.currentPosition).isEqualTo(12345L)
            assertThat(restored.shuffleModeEnabled).isTrue()
            assertThat(restored.repeatMode).isEqualTo(Player.REPEAT_MODE_ALL)
            val timeline = restored.currentTimeline
            var index = timeline.getFirstWindowIndex(true)
            val actual = mutableListOf<Int>()
            while (index != androidx.media3.common.C.INDEX_UNSET && actual.size < 4) {
                actual += index
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
            }
            assertThat(actual).containsExactlyElementsIn(order.toList()).inOrder()
        } finally {
            second.destroy()
            second.get().awaitPlaybackPersistence()
        }
    }

    @Test
    fun `shuffle and repeat survive service recreation even without a loaded item`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("playback_restore", Context.MODE_PRIVATE).edit().clear().commit()
        val first = Robolectric.buildService(MusicService::class.java).create()
        val field = MusicService::class.java.getDeclaredField("player").apply { isAccessible = true }
        try {
            val player = field.get(first.get()) as ExoPlayer
            player.shuffleModeEnabled = true
            player.repeatMode = Player.REPEAT_MODE_ALL
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            first.destroy()
            first.get().awaitPlaybackPersistence()
        }
        val second = Robolectric.buildService(MusicService::class.java).create()
        try {
            val restored = field.get(second.get()) as ExoPlayer
            assertThat(restored.shuffleModeEnabled).isTrue()
            assertThat(restored.repeatMode).isEqualTo(Player.REPEAT_MODE_ALL)
            assertThat(restored.playWhenReady).isFalse()
        } finally {
            second.destroy()
            second.get().awaitPlaybackPersistence()
        }
    }
}
