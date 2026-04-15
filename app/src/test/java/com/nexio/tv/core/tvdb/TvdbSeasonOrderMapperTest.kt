package com.nexio.tv.core.tvdb

import com.nexio.tv.data.remote.api.TvdbSeasonTypeRecord
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.domain.model.TvdbEpisodeOrder
import com.nexio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TvdbSeasonOrderMapper]: META-03 TVDB default season-type preservation.
 *
 * Contract:
 * - Canonical season/episode numbers (from Trakt/TMDB aired order) stay unchanged on Video.
 * - TVDB default season-type metadata is preserved alongside canonical keys.
 * - Specials placement data is preserved but does not alter canonical numbering.
 */
class TvdbSeasonOrderMapperTest {

    private val mapper = TvdbSeasonOrderMapper()

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

    private fun testSeriesRecord(
        defaultSeasonType: Int? = 1,
        seasonTypes: List<TvdbSeasonTypeRecord> = listOf(
            TvdbSeasonTypeRecord(id = 1, name = "Aired Order", type = "official"),
            TvdbSeasonTypeRecord(id = 2, name = "DVD Order", type = "dvd"),
            TvdbSeasonTypeRecord(id = 3, name = "Absolute Order", type = "absolute")
        )
    ) = TvdbSeriesExtendedRecord(
        id = 12345,
        name = "Test Series",
        defaultSeasonType = defaultSeasonType,
        seasonTypes = seasonTypes
    )

    // --- Tests ---

    @Test
    fun `preserves default season type and available season types`() {
        // Given: A TVDB series with defaultSeasonType=1 and available season types.
        val series = testSeriesRecord()

        // When: Building series order context.
        val context = mapper.buildSeriesOrderContext(series)

        // Then: Context preserves season type metadata.
        assertNotNull("Context must not be null when defaultSeasonType is set", context)
        assertEquals(1, context!!.defaultSeasonTypeId)
        assertEquals("Aired Order", context.defaultSeasonTypeName)
        assertEquals("official", context.defaultSeasonTypeSlug)
        assertEquals(3, context.availableSeasonTypes.size)
        assertEquals("dvd", context.availableSeasonTypes[1].type)

        // And: A canonical video retains its season/episode when mapper applies order.
        val video = canonicalVideo(season = 1, episode = 5)
        val tvdbEpisode = tvdbEpisodeMetadata(seasonNumber = 2, episodeNumber = 3)
        val episodeOrder = mapper.mapEpisodeOrder(tvdbEpisode)
        val metadata = TvEpisodeMetadata(tvdbEpisodeOrder = episodeOrder)
        val mapped = mapper.applyEpisodeOrder(video, metadata)

        assertEquals(1, mapped.season)
        assertEquals(5, mapped.episode)
        assertNotNull(mapped.tvdbEpisodeOrder)
        assertEquals(2, mapped.tvdbEpisodeOrder!!.defaultSeason)
        assertEquals(3, mapped.tvdbEpisodeOrder!!.defaultEpisode)
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
        val episodeOrder = mapper.mapEpisodeOrder(tvdbDefaultOrderEpisode)
        val metadata = TvEpisodeMetadata(tvdbEpisodeOrder = episodeOrder)
        val mapped = mapper.applyEpisodeOrder(video, metadata)

        // Then: Canonical keys are preserved.
        assertEquals(1, mapped.season)
        assertEquals(5, mapped.episode)

        // And: TVDB order is stored separately.
        assertNotNull(mapped.tvdbEpisodeOrder)
        assertEquals(2, mapped.tvdbEpisodeOrder!!.defaultSeason)
        assertEquals(3, mapped.tvdbEpisodeOrder!!.defaultEpisode)
    }

    @Test
    fun `specials placement is preserved but not applied to canonical keys`() {
        // Given: A TVDB special with airsBeforeSeason=1, airsBeforeEpisode=3.
        val specialVideo = canonicalVideo(season = 0, episode = 2)
        val tvdbSpecialEpisode = tvdbEpisodeMetadata(
            seasonNumber = 0,
            episodeNumber = 2,
            title = "Special: Behind the Scenes",
            airsBeforeSeason = 1,
            airsBeforeEpisode = 3
        )

        // When: The mapper processes the special.
        val episodeOrder = mapper.mapEpisodeOrder(tvdbSpecialEpisode)

        // Then: Placement hints are preserved.
        assertNotNull(episodeOrder)
        assertEquals(1, episodeOrder!!.airsBeforeSeason)
        assertEquals(3, episodeOrder.airsBeforeEpisode)
        assertTrue(
            "alternateOrderPreservedButNotApplied should be true for specials placement",
            episodeOrder.alternateOrderPreservedButNotApplied
        )

        // And: Canonical keys remain unchanged after apply.
        val metadata = TvEpisodeMetadata(tvdbEpisodeOrder = episodeOrder)
        val mapped = mapper.applyEpisodeOrder(specialVideo, metadata)
        assertEquals(0, mapped.season)
        assertEquals(2, mapped.episode)
    }

    @Test
    fun `buildSeriesOrderContext returns null when both defaultSeasonType and seasonTypes are empty`() {
        val series = testSeriesRecord(defaultSeasonType = null, seasonTypes = emptyList())
        val context = mapper.buildSeriesOrderContext(series)
        assertNull("Context must be null when no season-type data exists", context)
    }

    @Test
    fun `buildSeriesOrderContext returns context when only seasonTypes present`() {
        val series = testSeriesRecord(
            defaultSeasonType = null,
            seasonTypes = listOf(TvdbSeasonTypeRecord(id = 1, name = "Aired Order", type = "official"))
        )
        val context = mapper.buildSeriesOrderContext(series)
        assertNotNull("Context must not be null when seasonTypes is non-empty", context)
        assertNull(context!!.defaultSeasonTypeId)
        assertEquals(1, context.availableSeasonTypes.size)
    }

    @Test
    fun `mapEpisodeOrder returns null when all fields are null`() {
        val episode = TvEpisodeMetadata()
        val order = mapper.mapEpisodeOrder(episode)
        assertNull("Episode order must be null when all order fields are null", order)
    }

    @Test
    fun `slug derivation lowercases and replaces spaces with dashes`() {
        val series = testSeriesRecord(
            defaultSeasonType = 1,
            seasonTypes = listOf(
                TvdbSeasonTypeRecord(id = 1, name = "Aired Order", type = "Official Order")
            )
        )
        val context = mapper.buildSeriesOrderContext(series)
        assertEquals("official-order", context!!.defaultSeasonTypeSlug)
    }
}
