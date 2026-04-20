@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nexio.tv.ui.screens.detail

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun trailer_playback_keeps_hero_actions_and_metadata_visible() {
        composeRule.setContent {
            HeroSectionTrailerVisibilityHarness()
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithText("Play").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add to library").assertIsDisplayed()
        composeRule.onNodeWithText("Director: Sam Raimi").assertIsDisplayed()
        composeRule.onNodeWithText("Two colleagues become stranded on a deserted island and must survive.").assertIsDisplayed()
    }

    @Test
    fun switching_to_a_new_detail_resets_scroll_to_the_hero() {
        composeRule.setContent {
            DetailMetaChangeScrollResetHarness()
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithText("Below item 8 for first").assertIsDisplayed()

        composeRule.onNodeWithTag("switch_meta").performClick()
        composeRule.waitForIdle()

        val indexText = composeRule.onNodeWithTag("scroll_state").fetchSemanticsNode().config
            .getOrElse(SemanticsProperties.Text) { emptyList() }
            .joinToString(separator = "") { it.text }

        assertTrue("Expected reset to index 0, was '$indexText'", indexText.contains("index=0"))
        composeRule.onNodeWithText("Hero for second").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun moving_between_hero_actions_does_not_correct_small_parent_scroll_offsets() {
        composeRule.setContent {
            HeroSectionScrollCorrectionHarness()
        }

        composeRule.waitForIdle()

        val initialState = composeRule.scrollStateText()
        assertTrue("Expected initial offset=4, was '$initialState'", initialState.contains("offset=4"))

        composeRule.onRoot().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeRule.waitForIdle()

        val afterRight = composeRule.scrollStateText()
        assertTrue(
            "Horizontal hero focus should not reset parent scroll offset, was '$afterRight'",
            afterRight.contains("offset=4")
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun hero_play_focus_survives_late_focus_thief_after_initial_landing() {
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                HeroSectionLateThiefHarness()
            }

            // Let the initial focus land on Play.
            repeat(30) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
            }
            composeRule.onNodeWithText("Play").assertIsFocused()

            // Let the thief effect run (it requests focus ~8 frames after composition).
            repeat(30) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
            }

            // Guard should have re-stolen focus back within the 1.5 s window.
            repeat(90) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
            }
            composeRule.onNodeWithText("Play").assertIsFocused()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun delayed_hero_focus_request_waits_until_target_is_composed() {
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                DelayedFocusRequestHarness()
            }

            repeat(10) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
            }

            composeRule.onNodeWithText("Delayed hero").assertIsFocused()
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

private fun ComposeContentTestRule.scrollStateText(): String {
    return onNodeWithTag("scroll_state").fetchSemanticsNode().config
        .getOrElse(SemanticsProperties.Text) { emptyList() }
        .joinToString(separator = "") { it.text }
}

@Composable
private fun DetailMetaChangeScrollResetHarness() {
    var metaId by remember { mutableStateOf("first") }

    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = { metaId = "second" },
            modifier = Modifier.testTag("switch_meta")
        ) {
            Text("Switch meta")
        }

        key(metaId) {
            val listState = rememberResettableLazyListState(
                resetKey = metaId,
                firstVisibleItemIndex = if (metaId == "first") 8 else 0,
                prefetchStrategy = LazyListPrefetchStrategy(nestedPrefetchItemCount = 2)
            )
            val scrollStateLabel by remember(listState) {
                derivedStateOf {
                    "index=${listState.firstVisibleItemIndex}, offset=${listState.firstVisibleItemScrollOffset}"
                }
            }

            Text(
                text = scrollStateLabel,
                modifier = Modifier.testTag("scroll_state")
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState
            ) {
                item {
                    Text("Hero for $metaId")
                }

                items((1..12).toList()) { index ->
                    Text(text = "Below item $index for $metaId")
                }
            }
        }
    }
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
                trailerAvailable = false,
                playButtonFocusRequester = playButtonFocusRequester,
                restorePlayFocusToken = 1,
                onPlayFocusRestored = {
                    coroutineScope.launch { listState.restoreHeroScrollAfterFocus() }
                }
            )
        }

        items((1..12).toList()) { index ->
            Text(text = "Below item $index")
        }
    }
}

@Composable
private fun HeroSectionTrailerVisibilityHarness() {
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
        trailerAvailable = true,
        onTrailerClick = {},
        isTrailerPlaying = true
    )
}

@Composable
private fun HeroSectionScrollCorrectionHarness() {
    val playButtonFocusRequester = remember { FocusRequester() }
    val listState = rememberResettableLazyListState(
        resetKey = "hero_scroll",
        firstVisibleItemIndex = 0,
        firstVisibleItemScrollOffset = 4,
        prefetchStrategy = LazyListPrefetchStrategy(nestedPrefetchItemCount = 2)
    )
    val scrollStateLabel by remember(listState) {
        derivedStateOf {
            "index=${listState.firstVisibleItemIndex}, offset=${listState.firstVisibleItemScrollOffset}"
        }
    }

    LaunchedEffect(playButtonFocusRequester) {
        playButtonFocusRequester.requestFocusAfterFrames(frames = 0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = scrollStateLabel,
            modifier = Modifier.testTag("scroll_state")
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState
        ) {
            item {
                HeroContentSection(
                    meta = heroTestMeta(title = "Mike and Nick and Nick and Alice", director = "BenDavid Grabinski"),
                    nextEpisode = null,
                    nextToWatch = null,
                    onPlayClick = {},
                    isInLibrary = false,
                    onToggleLibrary = {},
                    onLibraryLongPress = {},
                    trailerAvailable = false,
                    playButtonFocusRequester = playButtonFocusRequester,
                    restorePlayFocusToken = 0
                )
            }

            items((1..12).toList()) { index ->
                Text(text = "Below item $index")
            }
        }
    }
}

@Composable
private fun HeroSectionLateThiefHarness() {
    val playButtonFocusRequester = remember { FocusRequester() }
    val thiefFocusRequester = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var playIsFocused by remember { mutableStateOf(false) }
    var playEverFocused by remember { mutableStateOf(false) }
    var userHasNavigatedFromPlay by remember { mutableStateOf(false) }

    // Simulated focus thief — mimics a lazy-list section doing LaunchedEffect(Unit) { requestFocus() }.
    LaunchedEffect(thiefFocusRequester) {
        repeat(8) { withFrameNanos { } }
        runCatching { thiefFocusRequester.requestFocus() }
    }

    // Mirror of the MetaDetailsScreen guard loop.
    LaunchedEffect(playEverFocused, userHasNavigatedFromPlay) {
        if (!playEverFocused) return@LaunchedEffect
        repeat(90) {
            if (
                !shouldReclaimPlayFocus(
                    playEverFocused = playEverFocused,
                    playIsFocused = playIsFocused,
                    userHasNavigatedFromPlay = userHasNavigatedFromPlay,
                    hasPendingRestoreTarget = false,
                    isTrailerPlaying = false,
                    isTrailerLoading = false
                )
            ) {
                if (userHasNavigatedFromPlay) return@LaunchedEffect
                kotlinx.coroutines.delay(16)
                return@repeat
            }
            runCatching { playButtonFocusRequester.requestFocus() }
            kotlinx.coroutines.delay(32)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState
    ) {
        item {
            HeroContentSection(
                meta = heroTestMeta(title = "Late Thief", director = "Sam Raimi"),
                nextEpisode = null,
                nextToWatch = null,
                onPlayClick = {},
                isInLibrary = false,
                onToggleLibrary = {},
                onLibraryLongPress = {},
                trailerAvailable = false,
                playButtonFocusRequester = playButtonFocusRequester,
                requestInitialFocus = true,
                onHeroActionFocused = { action ->
                    if (action != HeroAction.PLAY) {
                        userHasNavigatedFromPlay = true
                    }
                },
                onPlayFocusChanged = { focused ->
                    playIsFocused = focused
                    if (focused) playEverFocused = true
                }
            )
        }

        item {
            Button(
                onClick = {},
                modifier = Modifier.focusRequester(thiefFocusRequester)
            ) {
                Text("Thief")
            }
        }
    }
}

@Composable
private fun DelayedFocusRequestHarness() {
    val heroFocusRequester = remember { FocusRequester() }
    val fallbackFocusRequester = remember { FocusRequester() }
    var heroVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        heroFocusRequester.requestFocusAfterFrames(frames = 0)
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        fallbackFocusRequester.requestFocus()
        repeat(6) {
            withFrameNanos { }
        }
        heroVisible = true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (heroVisible) {
            Button(
                onClick = {},
                modifier = Modifier.focusRequester(heroFocusRequester)
            ) {
                Text("Delayed hero")
            }
        }

        Button(
            onClick = {},
            modifier = Modifier.focusRequester(fallbackFocusRequester)
        ) {
            Text("Fallback")
        }
    }
}

private fun heroTestMeta(
    title: String,
    director: String
): Meta {
    return Meta(
        id = title.lowercase().replace(" ", "_"),
        type = ContentType.MOVIE,
        rawType = "movie",
        name = title,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = "Two gangsters and the woman they love try to survive the most dangerous night of their lives.",
        releaseInfo = "2026-01-30",
        imdbRating = 6.6f,
        genres = listOf("Comedy", "Science Fiction", "Crime"),
        runtime = "107",
        director = listOf(director),
        writer = emptyList(),
        cast = emptyList(),
        videos = emptyList(),
        country = "United States of America",
        awards = null,
        language = "en",
        links = emptyList(),
        ageRating = "R"
    )
}
