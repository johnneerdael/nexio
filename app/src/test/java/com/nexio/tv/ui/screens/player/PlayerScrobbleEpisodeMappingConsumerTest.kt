package com.nexio.tv.ui.screens.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-inspection test (D.8 fix-up) that pins the consumer wiring of
 * [PlayerRuntimeController.buildScrobbleItem]. The warmup populates
 * `currentTraktEpisodeMapping` and `currentTraktEpisodeMappingKey`, but the
 * mapping is only useful if `buildScrobbleItem` actually consults it when
 * constructing the [com.nexio.tv.data.repository.TrackingScrobbleItem.Episode].
 *
 * Without these assertions, anime / absolute-numbered shows would silently
 * regress to posting the raw `currentSeason` / `currentEpisode` to Trakt the
 * moment a future refactor stops reading the warmed mapping.
 */
class PlayerScrobbleEpisodeMappingConsumerTest {

    @Test
    fun buildScrobbleItem_consults_warmed_mapping_for_effective_season_episode() {
        val src = File(
            "app/src/main/java/com/nexio/tv/ui/screens/player/" +
                "PlayerRuntimeControllerPlaybackEvents.kt"
        ).readText()
        val fnStart = src.indexOf(
            "internal fun PlayerRuntimeController.buildScrobbleItem"
        )
        assertTrue("buildScrobbleItem function must exist", fnStart >= 0)
        val fnBody = src.substring(fnStart)
            .substringBefore("\ninternal fun PlayerRuntimeController.")

        assertTrue(
            "buildScrobbleItem must consult currentTraktEpisodeMappingKey: $fnBody",
            fnBody.contains("currentTraktEpisodeMappingKey")
        )
        assertTrue(
            "buildScrobbleItem must consult currentTraktEpisodeMapping: $fnBody",
            fnBody.contains("currentTraktEpisodeMapping")
        )
        assertTrue(
            "buildScrobbleItem must use the shared currentEpisodeMappingCacheKey() helper: $fnBody",
            fnBody.contains("currentEpisodeMappingCacheKey()")
        )
        assertTrue(
            "buildScrobbleItem must derive an effectiveSeason from the mapping: $fnBody",
            fnBody.contains("effectiveSeason")
        )
        assertTrue(
            "buildScrobbleItem must derive an effectiveEpisode from the mapping: $fnBody",
            fnBody.contains("effectiveEpisode")
        )
        // The mapping carries (season, episode) on EpisodeMappingEntry — confirm
        // the consumer pulls those fields from the mapped entry, not just the
        // raw currentSeason/currentEpisode.
        assertTrue(
            "buildScrobbleItem must read mappedEpisode?.season: $fnBody",
            fnBody.contains("mappedEpisode?.season")
        )
        assertTrue(
            "buildScrobbleItem must read mappedEpisode?.episode: $fnBody",
            fnBody.contains("mappedEpisode?.episode")
        )
        // The Episode payload must use the effective values, not the raw ones.
        assertTrue(
            "Episode item must pass season = effectiveSeason: $fnBody",
            fnBody.contains("season = effectiveSeason")
        )
        assertTrue(
            "Episode item must pass number = effectiveEpisode: $fnBody",
            fnBody.contains("number = effectiveEpisode")
        )
    }
}
