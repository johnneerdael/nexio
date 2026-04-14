package com.nexio.tv.ui.screens.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nexio.tv.ui.theme.NexioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModernHomeLoadingPlaceholdersTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun loading_catalog_row_renders_non_clickable_placeholders() {
        composeRule.setContent {
            NexioTheme {
                val bringIntoViewSpec = LocalBringIntoViewSpec.current
                val uiCaches = remember { ModernHomeUiCaches() }
                ModernRowSection(
                    row = HeroCarouselRow(
                        key = "simkl_tv_trending_today",
                        title = "SIMKL Trending TV (Today)",
                        globalRowIndex = 0,
                        items = emptyList(),
                        isLoading = true
                    ),
                    isActiveRow = false,
                    isVerticalRowsScrolling = false,
                    rowTitleBottom = 8.dp,
                    defaultBringIntoViewSpec = bringIntoViewSpec,
                    initialScrollIndex = 0,
                    uiCaches = uiCaches,
                    pendingRowFocusIndex = null,
                    pendingRowFocusNonce = 0,
                    onPendingRowFocusCleared = {},
                    onRowItemFocused = { _, _, _ -> error("Loading placeholders should not focus items") },
                    useLandscapePosters = false,
                    showLabels = true,
                    posterCardCornerRadius = 8.dp,
                    effectiveExpandEnabled = false,
                    expandedCatalogFocusKeyForRow = null,
                    focusedPosterBackdropTrailerPlaybackTarget = FocusedPosterTrailerPlaybackTarget.HERO_MEDIA,
                    trailerPlaybackUnlockedFocusKey = null,
                    focusedPosterBackdropTrailerMuted = true,
                    trailerPreviewUrls = emptyMap(),
                    trailerPreviewAudioUrls = emptyMap(),
                    trailerPreviewExternalUrls = emptyMap(),
                    modernCatalogCardWidth = 126.dp,
                    modernCatalogCardHeight = 189.dp,
                    continueWatchingCardWidth = 220.dp,
                    continueWatchingCardHeight = 124.dp,
                    onContinueWatchingClick = {},
                    onContinueWatchingOptions = {},
                    isCatalogItemWatched = { false },
                    onCatalogItemLongPress = { _, _ -> },
                    onItemFocus = {},
                    onPreloadAdjacentItem = {},
                    onCatalogSelectionFocused = {},
                    onNavigateToDetail = { _, _, _ -> },
                    onLoadMoreCatalog = { _, _, _ -> },
                    canPromoteHeroTrailerToFullscreen = false,
                    fullscreenTrailerActive = false,
                    onPromoteHeroTrailerToFullscreen = {},
                    onBackdropInteraction = {}
                )
            }
        }

        composeRule.onNodeWithText("SIMKL Trending TV (Today)").assertIsDisplayed()
        composeRule.onAllNodesWithTag(MODERN_LOADING_PLACEHOLDER_TEST_TAG)
            .assertCountEquals(modernLoadingPlaceholderCount())
            .apply {
                fetchSemanticsNodes().forEachIndexed { index, _ ->
                    get(index).assertHasNoClickAction()
                }
            }
    }
}
