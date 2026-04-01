package com.nexio.tv.ui.screens.detail

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.Text
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeroSectionFocusStabilityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun moving_focus_horizontally_between_hero_actions_keeps_detail_content_vertically_stable() {
        composeRule.setContent {
            HeroSectionFocusHarness()
        }

        composeRule.waitForIdle()

        val directorLabel = "Director: Sam Raimi"
        composeRule.onNodeWithText(directorLabel).assertIsDisplayed()

        val initialTop = composeRule.onNodeWithText(directorLabel).fetchSemanticsNode().boundsInRoot.top

        composeRule.onRoot().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeRule.waitForIdle()

        val afterFirstRightTop = composeRule.onNodeWithText(directorLabel).fetchSemanticsNode().boundsInRoot.top

        composeRule.onRoot().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeRule.waitForIdle()

        val afterSecondRightTop = composeRule.onNodeWithText(directorLabel).fetchSemanticsNode().boundsInRoot.top

        assertEquals(initialTop, afterFirstRightTop, 0f)
        assertEquals(initialTop, afterSecondRightTop, 0f)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun moving_focus_horizontally_between_hero_actions_keeps_detail_content_stable_during_transition() {
        composeRule.setContent {
            HeroSectionFocusHarness()
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()

            val directorLabel = "Director: Sam Raimi"
            composeRule.onNodeWithText(directorLabel).assertIsDisplayed()

            val positions = mutableListOf<Float>()
            positions += composeRule.directorLineTop(directorLabel)

            composeRule.onRoot().performKeyInput {
                pressKey(Key.DirectionRight)
            }

            repeat(12) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
                positions += composeRule.directorLineTop(directorLabel)
            }

            positions.forEach { top ->
                assertEquals(positions.first(), top, 0f)
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun moving_focus_horizontally_between_hero_actions_keeps_action_row_vertically_stable_during_transition() {
        composeRule.setContent {
            HeroSectionFocusHarness()
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Play").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Add to library").assertIsDisplayed()

            val playTops = mutableListOf<Float>()
            val addToLibraryTops = mutableListOf<Float>()
            val playHeights = mutableListOf<Float>()
            val addToLibraryHeights = mutableListOf<Float>()
            playTops += composeRule.nodeTopByText("Play")
            addToLibraryTops += composeRule.nodeTopByContentDescription("Add to library")
            playHeights += composeRule.nodeHeightByText("Play")
            addToLibraryHeights += composeRule.nodeHeightByContentDescription("Add to library")

            composeRule.onRoot().performKeyInput {
                pressKey(Key.DirectionRight)
            }

            repeat(12) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
                playTops += composeRule.nodeTopByText("Play")
                addToLibraryTops += composeRule.nodeTopByContentDescription("Add to library")
                playHeights += composeRule.nodeHeightByText("Play")
                addToLibraryHeights += composeRule.nodeHeightByContentDescription("Add to library")
            }

            if (playTops.distinct().size > 1 ||
                addToLibraryTops.distinct().size > 1 ||
                playHeights.distinct().size > 1 ||
                addToLibraryHeights.distinct().size > 1
            ) {
                fail(
                    "Play tops=$playTops, add tops=$addToLibraryTops, " +
                        "play heights=$playHeights, add heights=$addToLibraryHeights"
                )
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }
}

private fun ComposeContentTestRule.directorLineTop(label: String): Float {
    return onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
}

private fun ComposeContentTestRule.nodeTopByText(label: String): Float {
    return onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
}

private fun ComposeContentTestRule.nodeTopByContentDescription(contentDescription: String): Float {
    return onNodeWithContentDescription(contentDescription).fetchSemanticsNode().boundsInRoot.top
}

private fun ComposeContentTestRule.nodeHeightByText(label: String): Float {
    return onNodeWithText(label).fetchSemanticsNode().boundsInRoot.height
}

private fun ComposeContentTestRule.nodeHeightByContentDescription(contentDescription: String): Float {
    return onNodeWithContentDescription(contentDescription).fetchSemanticsNode().boundsInRoot.height
}

@Composable
private fun HeroSectionFocusHarness() {
    val playButtonFocusRequester = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState
    ) {
        item {
            HeroContentSection(
                meta = Meta(
                    id = "movie",
                    type = ContentType.MOVIE,
                    rawType = "movie",
                    name = "Send Help",
                    poster = null,
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = "Two colleagues become stranded on a deserted island and must survive.",
                    releaseInfo = "2026-01-30",
                    imdbRating = 7.1f,
                    genres = listOf("Horror", "Thriller", "Comedy"),
                    runtime = "113",
                    director = listOf("Sam Raimi"),
                    writer = emptyList(),
                    cast = emptyList(),
                    videos = emptyList(),
                    country = "United States of America",
                    awards = null,
                    language = "en",
                    links = emptyList(),
                    ageRating = "R"
                ),
                nextEpisode = null,
                nextToWatch = null,
                onPlayClick = {},
                isInLibrary = false,
                onToggleLibrary = {},
                onLibraryLongPress = {},
                isMovieWatched = false,
                isMovieWatchedPending = false,
                onToggleMovieWatched = {},
                trailerAvailable = false,
                playButtonFocusRequester = playButtonFocusRequester,
                restorePlayFocusToken = 1,
                onHeroActionFocused = {
                    coroutineScope.launch { listState.restoreHeroScrollAfterFocus() }
                }
            )
        }

        items((1..12).toList()) { index ->
            Text(text = "Below item $index")
        }
    }
}
