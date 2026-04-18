package com.nexio.tv.ui.screens.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexio.tv.domain.model.MetaCastMember
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PauseOverlayRenderingTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun hidden_pause_overlay_does_not_emit_artwork_or_cast_nodes() {
        composeRule.setContent {
            PauseOverlay(
                visible = false,
                onClose = {},
                title = "Monarch: Legacy of Monsters",
                episodeTitle = "Separate Ways",
                season = 1,
                episode = 10,
                year = "2024",
                type = "series",
                description = "Titan X makes landfall.",
                backdropUrl = null,
                logoUrl = null,
                cast = listOf(
                    MetaCastMember(
                        name = "Anna Sawai",
                        character = "Cate Randa",
                        photo = null
                    )
                ),
                modifier = Modifier
            )
        }

        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag("pause_overlay_root").assertCountEquals(0)
        composeRule.onAllNodesWithTag("pause_overlay_backdrop_fallback").assertCountEquals(0)
        composeRule.onAllNodesWithTag("pause_overlay_title_fallback").assertCountEquals(0)
        composeRule.onAllNodesWithTag("pause_overlay_cast_photo").assertCountEquals(0)
    }

    @Test
    fun visible_pause_overlay_emits_fallback_artwork_and_cast_photo_slots() {
        composeRule.setContent {
            PauseOverlay(
                visible = true,
                onClose = {},
                title = "Monarch: Legacy of Monsters",
                episodeTitle = "Separate Ways",
                season = 1,
                episode = 10,
                year = "2024",
                type = "series",
                description = "Titan X makes landfall.",
                backdropUrl = null,
                logoUrl = null,
                cast = listOf(
                    MetaCastMember(
                        name = "Anna Sawai",
                        character = "Cate Randa",
                        photo = null
                    ),
                    MetaCastMember(
                        name = "Kiersey Clemons",
                        character = "May",
                        photo = null
                    )
                ),
                modifier = Modifier
            )
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pause_overlay_root").assertIsDisplayed()
        composeRule.onNodeWithTag("pause_overlay_backdrop_fallback").assertIsDisplayed()
        composeRule.onNodeWithTag("pause_overlay_title_fallback").assertIsDisplayed()
        composeRule.onNodeWithText("Anna Sawai").assertIsDisplayed()
        composeRule.onNodeWithText("Cate Randa").assertIsDisplayed()
        composeRule.onNodeWithText("Kiersey Clemons").assertIsDisplayed()
        composeRule.onNodeWithText("May").assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodesWithTag("pause_overlay_cast_photo").fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithTag("pause_overlay_cast_name").fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithTag("pause_overlay_cast_role").fetchSemanticsNodes().size)
    }
}
