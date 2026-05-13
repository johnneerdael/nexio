package com.nexio.tv.notices.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexio.tv.notices.model.RemoteNoticeDisplay
import com.nexio.tv.ui.theme.NexioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteNoticeDialogTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun renders_markdown_notice_and_closes() {
        composeRule.setContent {
            NexioTheme {
                var visible by remember { mutableStateOf(true) }
                if (visible) {
                    RemoteNoticeDialog(
                        notice = RemoteNoticeDisplay(
                            id = "notice-1",
                            title = "Important notice",
                            markdown = "# Heading\n\nBody copy",
                            markdownUrl = "https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/notice-1.md",
                            publishedAt = "2026-05-12T12:01:00Z"
                        ),
                        onDismiss = { visible = false }
                    )
                }
            }
        }

        composeRule.onNodeWithText("Important notice").assertIsDisplayed()
        composeRule.onNodeWithText("Body copy").assertIsDisplayed()
        composeRule.onNodeWithText("Close").assertIsFocused()
        composeRule.onNodeWithText("Close").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Important notice").assertCountEquals(0)
    }
}
