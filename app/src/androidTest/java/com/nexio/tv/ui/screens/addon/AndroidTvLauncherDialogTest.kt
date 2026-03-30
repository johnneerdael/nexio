package com.nexio.tv.ui.screens.addon

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexio.tv.core.recommendations.AndroidTvFeedOption
import com.nexio.tv.ui.theme.NexioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidTvLauncherDialogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun first_select_press_turns_on_launcher_feeds() {
        composeRule.setContent {
            NexioTheme {
                var enabled by remember { mutableStateOf(false) }
                var selectedFeedKeys by remember { mutableStateOf(setOf<String>()) }
                AndroidTvLauncherDialog(
                    enabled = enabled,
                    selectedFeedKeys = selectedFeedKeys,
                    feedOptions = listOf(sampleFeedOption()),
                    onDismiss = {},
                    onEnabledChange = { enabled = it },
                    onToggleFeed = { key ->
                        selectedFeedKeys = if (key in selectedFeedKeys) {
                            selectedFeedKeys - key
                        } else {
                            selectedFeedKeys + key
                        }
                    }
                )
            }
        }

        composeRule.onNodeWithText("Launcher publishing is off").assertIsDisplayed()
        composeRule.onNodeWithText("Turn on").assertIsDisplayed()

        composeRule.onAllNodes(isRoot())[1].performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Launcher publishing is on").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun enabled_dialog_can_select_first_feed_with_center_press() {
        composeRule.setContent {
            NexioTheme {
                var selectedFeedKeys by remember { mutableStateOf(setOf<String>()) }
                AndroidTvLauncherDialog(
                    enabled = true,
                    selectedFeedKeys = selectedFeedKeys,
                    feedOptions = listOf(sampleFeedOption()),
                    onDismiss = {},
                    onEnabledChange = {},
                    onToggleFeed = { key ->
                        selectedFeedKeys = if (key in selectedFeedKeys) {
                            selectedFeedKeys - key
                        } else {
                            selectedFeedKeys + key
                        }
                    }
                )
            }
        }

        composeRule.onNodeWithText("Not selected").assertIsDisplayed()

        composeRule.onAllNodes(isRoot())[1].performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Selected").assertIsDisplayed()
    }

    private fun sampleFeedOption() = AndroidTvFeedOption(
        key = "sample_feed",
        title = "Sample Feed",
        subtitle = "Sample subtitle",
        sourceLabel = "Addon",
        typeLabel = "Series"
    )
}
