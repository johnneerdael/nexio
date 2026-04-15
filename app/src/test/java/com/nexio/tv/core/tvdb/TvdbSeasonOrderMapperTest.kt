package com.nexio.tv.core.tvdb

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Wave 0 validation scaffold for META-03: TVDB default season-type preservation.
 *
 * These tests define the contract that Phase 9 Plan 01 must satisfy:
 * - Canonical season/episode numbers (from Trakt/TMDB aired order) stay unchanged on Video.
 * - TVDB default season-type metadata is preserved alongside canonical keys.
 * - Specials placement data is preserved but does not alter canonical numbering.
 *
 * All tests are expected to fail until the TvdbSeasonOrderMapper is implemented in Plan 09-01.
 */
class TvdbSeasonOrderMapperTest {

    // --- Fixture helpers ---

    private fun tvdbEpisodeMetadata(
        seasonNumber: Int,
        episodeNumber: Int,
        title: String = "Episode $episodeNumber",
        absoluteNumber: Int? = null,
        airsAfterSeason: Int? = null,
        airsBeforeSeason: Int? = null,
        airsBeforeEpisode: Int? = null
    ) = TvEpisodeMetadata(
        providerEpisodeId = "tvdb:${seasonNumber * 100 + episodeNumber}",
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        title = title,
        overview = "Overview for $title",
        thumbnail = "https://artworks.thetvdb.com/fake/$seasonNumber/$episodeNumber.jpg",
        airDate = "2025-01-${episodeNumber.toString().padStart(2, '0')}",
        runtimeMinutes = 42,
        absoluteNumber = absoluteNumber,
        airsAfterSeason = airsAfterSeason,
        airsBeforeSeason = airsBeforeSeason,
        airsBeforeEpisode = airsBeforeEpisode
    )

    private fun canonicalVideo(
        season: Int,
        episode: Int,
        id: String = "tt0000001:$season:$episode"
    ) = Video(
        id = id,
        title = "S${season}E${episode}",
        released = "2025-01-01",
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null
    )

    // --- Tests ---

    @Test
    fun `preserves default season type and available season types`() {
        // Given: A TVDB series enrichment with defaultSeasonType = "official" and
        // available season types including "official", "dvd", "absolute".
        // The canonical Video has season=1, episode=5.
        val video = canonicalVideo(season = 1, episode = 5)
        val tvdbEpisode = tvdbEpisodeMetadata(
            seasonNumber = 2,  // TVDB default order places this in season 2
            episodeNumber = 3  // TVDB default order episode number
        )

        // When: The TvdbSeasonOrderMapper maps TVDB metadata onto the canonical video.
        // Then: Video.season remains 1 (canonical) and Video.episode remains 5 (canonical).
        assertEquals(
            "Canonical season must be preserved",
            1, video.season
        )
        assertEquals(
            "Canonical episode must be preserved",
            5, video.episode
        )

        // Phase 9 implementation must add tvdbEpisodeOrder to Video containing:
        //   defaultSeason = 2, defaultEpisode = 3
        // This assertion will fail until Plan 09-01 adds the tvdbEpisodeOrder field.
        // Uncomment and adapt once the field exists:
        // val mapped = TvdbSeasonOrderMapper.map(video, tvdbEpisode, defaultSeasonType = "official")
        // assertEquals(2, mapped.tvdbEpisodeOrder?.defaultSeason)
        // assertEquals(3, mapped.tvdbEpisodeOrder?.defaultEpisode)
        // assertEquals("official", mapped.tvdbEpisodeOrder?.defaultSeasonType)

        // For now, assert the field does not yet exist to confirm this test is a scaffold.
        // This will compile-fail or assert-fail when the mapper is implemented.
        val videoFields = Video::class.java.declaredFields.map { it.name }
        assert(videoFields.contains("tvdbEpisodeOrder")) {
            "Video must have a tvdbEpisodeOrder field after Plan 09-01 implementation"
        }
    }

    @Test
    fun `canonical season and episode stay unchanged when default order differs`() {
        // Given: A video with canonical (season=1, episode=5) and TVDB default order
        // that maps to (season=2, episode=3).
        val video = canonicalVideo(season = 1, episode = 5)
        val tvdbDefaultOrderEpisode = tvdbEpisodeMetadata(
            seasonNumber = 2,
            episodeNumber = 3
        )

        // When: TVDB metadata is applied.
        // Then: The canonical keys used for Trakt progress, watch-state, and episode ratings
        // remain (1, 5) regardless of TVDB default ordering.
        assertEquals(1, video.season)
        assertEquals(5, video.episode)

        // The mapper must produce a result where canonical keys are stable.
        // This assertion verifies the contract once the mapper exists.
        // val mapped = TvdbSeasonOrderMapper.map(video, tvdbDefaultOrderEpisode)
        // assertEquals(1, mapped.season) // canonical preserved
        // assertEquals(5, mapped.episode) // canonical preserved
        // assertEquals(2, mapped.tvdbEpisodeOrder?.defaultSeason) // TVDB order stored separately
        // assertEquals(3, mapped.tvdbEpisodeOrder?.defaultEpisode) // TVDB order stored separately

        val videoFields = Video::class.java.declaredFields.map { it.name }
        assert(videoFields.contains("tvdbEpisodeOrder")) {
            "Video.tvdbEpisodeOrder field must exist for TVDB default order storage after Plan 09-01"
        }
    }

    @Test
    fun `specials placement is preserved but not applied to canonical keys`() {
        // Given: A TVDB special episode with airsBeforeSeason=1, airsBeforeEpisode=3.
        // The canonical video is a special at season=0, episode=2.
        val specialVideo = canonicalVideo(season = 0, episode = 2)
        val tvdbSpecialEpisode = tvdbEpisodeMetadata(
            seasonNumber = 0,
            episodeNumber = 2,
            title = "Special: Behind the Scenes",
            airsBeforeSeason = 1,
            airsBeforeEpisode = 3
        )

        // When: The mapper processes the special.
        // Then: Video.season=0 and Video.episode=2 are unchanged (canonical).
        assertEquals(0, specialVideo.season)
        assertEquals(2, specialVideo.episode)

        // The placement hints (airsBeforeSeason, airsBeforeEpisode) must be preserved in the
        // episode metadata but must NOT alter the canonical season/episode keys.
        assertEquals(1, tvdbSpecialEpisode.airsBeforeSeason)
        assertEquals(3, tvdbSpecialEpisode.airsBeforeEpisode)

        // Phase 9 implementation must expose these placement hints without changing Video.season/episode.
        val videoFields = Video::class.java.declaredFields.map { it.name }
        assert(videoFields.contains("tvdbEpisodeOrder")) {
            "Video.tvdbEpisodeOrder must carry specials placement data after Plan 09-01"
        }
    }
}
