package com.luno.mobile.ui.shell

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.luno.mobile.ui.theme.LunoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], qualifiers = "w900dp-h900dp")
class DrawerRotationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun closedDrawerDoesNotExposeSettingsOverContentAfterRotation() {
        val landscape = mutableStateOf(false)
        compose.setContent {
            LunoTheme {
                Box(Modifier.requiredSize(if (landscape.value) 800.dp else 360.dp,
                    if (landscape.value) 360.dp else 800.dp)) {
                    LunoNavigationDrawer(rememberDrawerState(DrawerValue.Closed), drawerContent = {
                        Text("Settings", Modifier.testTag("settings"))
                        Text("Library", Modifier.testTag("library-folder"))
                    }) { Text("Home") }
                }
            }
        }
        compose.onNodeWithTag("library-folder").assertIsNotDisplayed()
        compose.runOnIdle { landscape.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("library-folder").assertIsNotDisplayed()
        compose.onNodeWithTag("settings").assertIsNotDisplayed()
        compose.onNodeWithTag("navigation-drawer").assertIsNotDisplayed()
    }
    @Test fun drawerWidthDoesNotChangeItsAnchorsWhenThePhoneRotates() {
        val landscape = mutableStateOf(false)
        compose.setContent {
            LunoTheme {
                Box(Modifier.requiredSize(if (landscape.value) 800.dp else 360.dp,
                    if (landscape.value) 360.dp else 800.dp)) {
                    LunoNavigationDrawer(rememberDrawerState(DrawerValue.Open), drawerContent = {
                        Text("Library")
                    }) { Text("Home") }
                }
            }
        }
        val portraitWidth = compose.onNodeWithTag("navigation-drawer").fetchSemanticsNode().size.width
        compose.runOnIdle { landscape.value = true }
        compose.waitForIdle()
        val landscapeWidth = compose.onNodeWithTag("navigation-drawer").fetchSemanticsNode().size.width
        com.google.common.truth.Truth.assertThat(landscapeWidth).isEqualTo(portraitWidth)
    }

}
