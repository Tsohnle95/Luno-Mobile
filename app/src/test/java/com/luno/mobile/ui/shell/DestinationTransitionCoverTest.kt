package com.luno.mobile.ui.shell

import android.app.Application
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.google.common.truth.Truth.assertThat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.luno.mobile.ui.theme.LunoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class DestinationTransitionCoverTest {
    @get:Rule val compose = createComposeRule()
    @Test fun poppingTheRealNavStackShowsTheCoverWithoutAManualNavigationCallback() {
        lateinit var nav: NavHostController
        var requested by mutableStateOf(false)
        compose.setContent { LunoTheme {
            nav = rememberNavController()
            val entry by nav.currentBackStackEntryAsState()
            Box(Modifier.fillMaxSize()) {
                NavHost(nav, startDestination = "home", enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None }, popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None }) {
                    composable("home") { Text("Home") }
                    composable("other") { Text("Other") }
                }
                DestinationTransitionCover(entry?.id, requested) { requested = true }
            }
        } }
        compose.runOnIdle { nav.navigate("other") }
        compose.runOnIdle { requested = false }
        compose.waitForIdle()
        compose.onNodeWithTag("page-transition-mask").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { nav.popBackStack() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("page-transition-mask").assertIsDisplayed()
    }
    @Test fun opaqueCoverBlocksTouchesFromActivatingHiddenScreenActions() {
        var hiddenClicks = 0
        compose.setContent { LunoTheme {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clickable { hiddenClicks++ }) { Text("Hidden screen") }
                DestinationTransitionCover("same", true) {}
            }
        } }
        compose.onRoot().performTouchInput { click(center) }
        compose.runOnIdle { assertThat(hiddenClicks).isEqualTo(0) }
    }
}
