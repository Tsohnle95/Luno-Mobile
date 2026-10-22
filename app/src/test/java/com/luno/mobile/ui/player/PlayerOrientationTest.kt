package com.luno.mobile.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.luno.mobile.LunoApp
import com.luno.mobile.playback.MusicController
import com.luno.mobile.ui.theme.LunoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LunoApp::class, sdk = [33], qualifiers = "w800dp-h360dp-land")
class PlayerOrientationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun landscapeShowsArtworkFirstAndRevealsSharedControlsOnScroll() {
        val controller = MusicController(androidx.test.core.app.ApplicationProvider.getApplicationContext<LunoApp>())
        compose.setContent {
            LunoTheme {
                Box(Modifier.requiredSize(800.dp, 360.dp)) { FullPlayerScreen(controller, onBack = {}) }
            }
        }
        compose.onNodeWithTag("landscape-player-artwork").assertWidthIsEqualTo(800.dp).assertHeightIsEqualTo(360.dp)
        compose.onNodeWithContentDescription("Queue").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsNotDisplayed()
        compose.onRoot().performTouchInput { swipeUp() }
        compose.onNodeWithContentDescription("Queue").assertIsDisplayed()
        compose.onNodeWithContentDescription("More options").assertIsDisplayed()
        compose.onNodeWithContentDescription("Repeat").assertIsDisplayed()
        compose.onNodeWithContentDescription("Shuffle").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
    }
}
