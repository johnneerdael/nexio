package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraktEpisodeMappingTest {

    // ------------------------------------------------------------------
    // remapEpisodeByTitleOrIndex — forward direction (addon → trakt)
    // ------------------------------------------------------------------

    @Test
    fun remap_returns_trakt_entry_when_title_uniquely_matches() {
        val addonEpisodes = listOf(
            EpisodeMappingEntry(season = 0, episode = 1, title = "Pilot"),
            EpisodeMappingEntry(season = 0, episode = 2, title = "The Cat in the Box"),
            EpisodeMappingEntry(season = 0, episode = 3, title = "Gray Matter")
        )
        val traktEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1, title = "Pilot"),
            EpisodeMappingEntry(season = 1, episode = 2, title = "The Cat in the Box"),
            EpisodeMappingEntry(season = 2, episode = 1, title = "Gray Matter")
        )

        val result = remapEpisodeByTitleOrIndex(
            requestedSeason = 0,
            requestedEpisode = 2,
            requestedVideoId = null,
            requestedTitle = "The Cat in the Box",
            addonEpisodes = addonEpisodes,
            traktEpisodes = traktEpisodes
        )

        assertEquals(1, result?.season)
        assertEquals(2, result?.episode)
    }

    @Test
    fun remap_falls_back_to_index_when_title_has_multiple_matches() {
        val addonEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1, title = "Episode 1"),
            EpisodeMappingEntry(season = 1, episode = 2, title = "Episode 2")
        )
        val traktEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1, title = "Episode 1"),
            EpisodeMappingEntry(season = 1, episode = 2, title = "Episode 1") // duplicate title
        )

        // "Episode 1" matches twice in target → title match rejected → falls back to index 0
        val result = remapEpisodeByTitleOrIndex(
            requestedSeason = 1,
            requestedEpisode = 1,
            requestedVideoId = null,
            requestedTitle = "Episode 1",
            addonEpisodes = addonEpisodes,
            traktEpisodes = traktEpisodes
        )

        assertEquals(1, result?.season)
        assertEquals(1, result?.episode)
    }

    @Test
    fun remap_falls_back_to_index_when_title_is_generic_episode_number() {
        val addonEpisodes = listOf(
            EpisodeMappingEntry(season = 0, episode = 1, title = "Episode 1"),
            EpisodeMappingEntry(season = 0, episode = 2, title = "Episode 2")
        )
        val traktEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1, title = "Pilot"),
            EpisodeMappingEntry(season = 1, episode = 2, title = "The Next Chapter")
        )

        // "Episode 1" is filtered by isUsefulEpisodeTitle → index fallback
        val result = remapEpisodeByTitleOrIndex(
            requestedSeason = 0,
            requestedEpisode = 1,
            requestedVideoId = null,
            requestedTitle = null,
            addonEpisodes = addonEpisodes,
            traktEpisodes = traktEpisodes
        )

        assertEquals(1, result?.season)
        assertEquals(1, result?.episode)
    }

    @Test
    fun remap_uses_videoId_to_locate_source_episode() {
        val addonEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1, title = "Pilot", videoId = "vid-001"),
            EpisodeMappingEntry(season = 1, episode = 2, title = "Second", videoId = "vid-002")
        )
        val traktEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1, title = "Pilot"),
            EpisodeMappingEntry(season = 1, episode = 2, title = "Second")
        )

        val result = remapEpisodeByTitleOrIndex(
            requestedSeason = 0,
            requestedEpisode = 0,
            requestedVideoId = "vid-002",
            requestedTitle = null,
            addonEpisodes = addonEpisodes,
            traktEpisodes = traktEpisodes
        )

        assertEquals(1, result?.season)
        assertEquals(2, result?.episode)
    }

    @Test
    fun remap_returns_null_when_source_episode_not_found() {
        val addonEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1, title = "Pilot")
        )
        val traktEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1, title = "Pilot")
        )

        val result = remapEpisodeByTitleOrIndex(
            requestedSeason = 99,
            requestedEpisode = 99,
            requestedVideoId = null,
            requestedTitle = "Unknown Episode",
            addonEpisodes = addonEpisodes,
            traktEpisodes = traktEpisodes
        )

        assertNull(result)
    }

    @Test
    fun remap_returns_null_when_source_list_is_empty() {
        val result = remapEpisodeByTitleOrIndex(
            requestedSeason = 1,
            requestedEpisode = 1,
            requestedVideoId = null,
            requestedTitle = null,
            addonEpisodes = emptyList(),
            traktEpisodes = listOf(EpisodeMappingEntry(season = 1, episode = 1))
        )
        assertNull(result)
    }

    @Test
    fun remap_returns_null_when_target_list_is_empty() {
        val result = remapEpisodeByTitleOrIndex(
            requestedSeason = 1,
            requestedEpisode = 1,
            requestedVideoId = null,
            requestedTitle = null,
            addonEpisodes = listOf(EpisodeMappingEntry(season = 1, episode = 1)),
            traktEpisodes = emptyList()
        )
        assertNull(result)
    }

    @Test
    fun remap_returns_null_when_index_out_of_target_bounds() {
        val addonEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1),
            EpisodeMappingEntry(season = 1, episode = 2)
        )
        val traktEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1) // only one entry
        )

        val result = remapEpisodeByTitleOrIndex(
            requestedSeason = 1,
            requestedEpisode = 2,
            requestedVideoId = null,
            requestedTitle = null,
            addonEpisodes = addonEpisodes,
            traktEpisodes = traktEpisodes
        )

        assertNull(result)
    }

    // ------------------------------------------------------------------
    // reverseRemapEpisodeByTitleOrIndex — reverse direction (trakt → addon)
    // ------------------------------------------------------------------

    @Test
    fun reverse_remap_returns_addon_entry_for_trakt_coordinates() {
        val addonEpisodes = listOf(
            EpisodeMappingEntry(season = 0, episode = 1, title = "Pilot"),
            EpisodeMappingEntry(season = 0, episode = 2, title = "Second")
        )
        val traktEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1, title = "Pilot"),
            EpisodeMappingEntry(season = 1, episode = 2, title = "Second")
        )

        val result = reverseRemapEpisodeByTitleOrIndex(
            requestedSeason = 1,
            requestedEpisode = 2,
            requestedVideoId = null,
            requestedTitle = null,
            addonEpisodes = addonEpisodes,
            traktEpisodes = traktEpisodes
        )

        assertEquals(0, result?.season)
        assertEquals(2, result?.episode)
    }

    @Test
    fun reverse_remap_returns_null_when_trakt_episode_not_found() {
        val addonEpisodes = listOf(EpisodeMappingEntry(season = 0, episode = 1))
        val traktEpisodes = listOf(EpisodeMappingEntry(season = 1, episode = 1))

        val result = reverseRemapEpisodeByTitleOrIndex(
            requestedSeason = 9,
            requestedEpisode = 9,
            requestedVideoId = null,
            requestedTitle = null,
            addonEpisodes = addonEpisodes,
            traktEpisodes = traktEpisodes
        )

        assertNull(result)
    }

    // ------------------------------------------------------------------
    // Title normalisation edge cases (exercised via remap path)
    // ------------------------------------------------------------------

    @Test
    fun remap_matches_despite_punctuation_and_case_differences() {
        val addonEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1, title = "Hello, World!")
        )
        val traktEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1, title = "hello world")
        )

        val result = remapEpisodeByTitleOrIndex(
            requestedSeason = 1,
            requestedEpisode = 1,
            requestedVideoId = null,
            requestedTitle = "Hello, World!",
            addonEpisodes = addonEpisodes,
            traktEpisodes = traktEpisodes
        )

        assertEquals(1, result?.season)
        assertEquals(1, result?.episode)
    }

    @Test
    fun remap_skips_title_match_for_ep_pattern() {
        val addonEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1, title = "ep 1"),
            EpisodeMappingEntry(season = 1, episode = 2, title = "ep 2")
        )
        val traktEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1, title = "Pilot"),
            EpisodeMappingEntry(season = 1, episode = 2, title = "Second")
        )

        // "ep 1" filtered → index fallback → orderedSource[0] → orderedTarget[0]
        val result = remapEpisodeByTitleOrIndex(
            requestedSeason = 1,
            requestedEpisode = 1,
            requestedVideoId = null,
            requestedTitle = null,
            addonEpisodes = addonEpisodes,
            traktEpisodes = traktEpisodes
        )

        assertEquals(1, result?.season)
        assertEquals(1, result?.episode)
    }
}
