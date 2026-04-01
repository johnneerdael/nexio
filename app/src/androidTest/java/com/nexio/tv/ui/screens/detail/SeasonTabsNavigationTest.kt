package com.nexio.tv.ui.screens.detail

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.Video
import com.nexio.tv.ui.theme.NexioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeasonTabsNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun auto_targeted_season_enters_the_cta_episode() {
        composeRule.setContent {
            NexioTheme {
                SeasonNavigationHarness(
                    totalSeasons = 50,
                    initialSelectedSeason = 50,
                    initialTargetEpisode = 5
                )
            }
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithText("Season 50").assertIsDisplayed()

        composeRule.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Selected season: 50").assertIsDisplayed()
        composeRule.onNodeWithText("Focused episode: S50E5").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun manual_override_enters_the_selected_season_instead_of_the_cta_season() {
        composeRule.setContent {
            NexioTheme {
                SeasonNavigationHarness(
                    totalSeasons = 50,
                    initialSelectedSeason = 50,
                    initialTargetEpisode = 5
                )
            }
        }

        composeRule.waitForIdle()

        composeRule.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionLeft)
        }
        composeRule.waitForIdle()

        composeRule.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Selected season: 49").assertIsDisplayed()
        composeRule.onNodeWithText("Focused episode: S49E1").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun stored_per_season_focus_still_wins() {
        composeRule.setContent {
            NexioTheme {
                SeasonNavigationHarness(
                    totalSeasons = 50,
                    initialSelectedSeason = 50,
                    initialTargetEpisode = 5,
                    seededLastFocusedEpisodeIdBySeason = mapOf(50 to "show:50:6")
                )
            }
        }

        composeRule.waitForIdle()

        composeRule.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Selected season: 50").assertIsDisplayed()
        composeRule.onNodeWithText("Focused episode: S50E6").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun short_show_keeps_existing_target_episode_behavior() {
        composeRule.setContent {
            NexioTheme {
                SeasonNavigationHarness(
                    totalSeasons = 2,
                    initialSelectedSeason = 2,
                    initialTargetEpisode = 3
                )
            }
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithText("Season 2").assertIsDisplayed()

        composeRule.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Selected season: 2").assertIsDisplayed()
        composeRule.onNodeWithText("Focused episode: S2E3").assertIsDisplayed()
    }
}

@Composable
private fun SeasonNavigationHarness(
    totalSeasons: Int,
    initialSelectedSeason: Int,
    initialTargetEpisode: Int,
    seededLastFocusedEpisodeIdBySeason: Map<Int, String> = emptyMap()
) {
    var selectedSeason by rememberSaveable { mutableIntStateOf(initialSelectedSeason) }
    var manualSeasonOverrideActive by rememberSaveable { mutableStateOf(false) }
    var focusedEpisodeLabel by rememberSaveable { mutableStateOf("none") }
    var seasonEntryRestoreEpisodeId by rememberSaveable { mutableStateOf<String?>(null) }
    var seasonEntryRestoreToken by rememberSaveable { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    val heroFocusRequester = remember { FocusRequester() }
    val selectedTabFocusRequester = remember { FocusRequester() }
    val lastFocusedEpisodeIdBySeason = remember {
        mutableStateMapOf<Int, String>().apply {
            putAll(seededLastFocusedEpisodeIdBySeason)
        }
    }
    val episodeFocusRequestersBySeason = remember { mutableMapOf<Int, MutableMap<String, FocusRequester>>() }

    val seasons = remember(totalSeasons) { (1..totalSeasons).toList() }
    val allEpisodes = remember(totalSeasons) {
        buildList {
            for (season in 1..totalSeasons) {
                for (episode in 1..6) {
                    add(
                        Video(
                            id = "show:$season:$episode",
                            title = "Episode $episode",
                            released = "2026-01-${episode.toString().padStart(2, '0')}",
                            thumbnail = null,
                            season = season,
                            episode = episode,
                            overview = "Overview $season-$episode",
                            runtime = 42
                        )
                    )
                }
            }
        }
    }
    val meta = remember(totalSeasons, allEpisodes) {
        Meta(
            id = "show",
            type = ContentType.SERIES,
            rawType = "series",
            name = "Test show",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            videos = allEpisodes,
            country = null,
            awards = null,
            language = null,
            links = emptyList()
        )
    }
    val episodesForSeason = remember(selectedSeason, allEpisodes) {
        allEpisodes.filter { it.season == selectedSeason }
    }
    val seasonEpisodeFocusRequesters = remember(selectedSeason, episodesForSeason) {
        val byEpisodeId = episodeFocusRequestersBySeason.getOrPut(selectedSeason) { mutableMapOf() }
        episodesForSeason.forEach { episode ->
            byEpisodeId.getOrPut(episode.id) { FocusRequester() }
        }
        byEpisodeId.keys.retainAll(episodesForSeason.map { it.id }.toSet())
        byEpisodeId
    }
    val ctaNextToWatch = remember(initialSelectedSeason, initialTargetEpisode) {
        SeriesNextToWatchCandidate(
            watchProgress = null,
            isResume = false,
            nextVideoId = "show:$initialSelectedSeason:$initialTargetEpisode",
            nextSeason = initialSelectedSeason,
            nextEpisode = initialTargetEpisode
        )
    }
    val seasonEntryEpisodeId = remember(
        meta,
        selectedSeason,
        manualSeasonOverrideActive,
        ctaNextToWatch,
        lastFocusedEpisodeIdBySeason[selectedSeason]
    ) {
        resolveSeasonEntryEpisodeId(
            meta = meta,
            selectedSeason = selectedSeason,
            nextToWatch = ctaNextToWatch,
            lastFocusedEpisodeIdBySeason = lastFocusedEpisodeIdBySeason,
            manualSeasonOverride = manualSeasonOverrideActive
        )
    }
    val seasonDownFocusRequester = remember(
        selectedSeason,
        episodesForSeason,
        seasonEpisodeFocusRequesters,
        seasonEntryEpisodeId
    ) {
        seasonEntryEpisodeId?.let { seasonEpisodeFocusRequesters[it] }
            ?: episodesForSeason.firstOrNull()?.id?.let { seasonEpisodeFocusRequesters[it] }
    }

    LaunchedEffect(Unit) {
        heroFocusRequester.requestFocusAfterFrames()
    }

    Column {
        Button(
            onClick = {},
            modifier = androidx.compose.ui.Modifier
                .focusRequester(heroFocusRequester)
                .focusProperties { down = selectedTabFocusRequester }
        ) {
            Text("Hero")
        }

        Text("Selected season: $selectedSeason")
        Text("Focused episode: $focusedEpisodeLabel")

        SeasonTabs(
            seasons = seasons,
            selectedSeason = selectedSeason,
            onSeasonSelected = {
                selectedSeason = it
                manualSeasonOverrideActive = true
            },
            onSeasonShortPress = {
                selectedSeason = it
                manualSeasonOverrideActive = true
            },
            selectedTabFocusRequester = selectedTabFocusRequester,
            downFocusRequester = seasonDownFocusRequester,
            onSelectedSeasonDown = seasonEntryEpisodeId?.let { episodeId ->
                {
                    seasonEntryRestoreEpisodeId = episodeId
                    coroutineScope.launch {
                        delay(32)
                        seasonEntryRestoreToken += 1
                    }
                }
            }
        )

        EpisodesRow(
            episodes = episodesForSeason,
            onEpisodeClick = {},
            onToggleEpisodeWatched = {},
            selectedSeason = selectedSeason,
            upFocusRequester = selectedTabFocusRequester,
            episodeFocusRequesters = seasonEpisodeFocusRequesters,
            restoreEpisodeId = seasonEntryRestoreEpisodeId,
            restoreFocusToken = seasonEntryRestoreToken,
            onRestoreFocusHandled = {
                seasonEntryRestoreEpisodeId = null
            },
            onEpisodeFocused = { episodeId ->
                lastFocusedEpisodeIdBySeason[selectedSeason] = episodeId
                val episode = episodesForSeason.firstOrNull { it.id == episodeId }
                focusedEpisodeLabel = if (episode?.season != null && episode.episode != null) {
                    "S${episode.season}E${episode.episode}"
                } else {
                    "none"
                }
            },
            scrollToEpisodeId = if (lastFocusedEpisodeIdBySeason[selectedSeason] == null) {
                seasonEntryEpisodeId
            } else {
                null
            }
        )
    }
}
