package com.nexio.tv.core.tvdb

import com.nexio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvMetadataModelsTest {

    @Test
    fun `diagnostic reasons expose stable provider decision event names`() {
        assertEquals("tmdb_tv_skipped", TvMetadataDecisionReason.TMDB_TV_SKIPPED.eventName)
        assertEquals("tvdb_fallback_tmdb", TvMetadataDecisionReason.TVDB_FALLBACK_TMDB.eventName)
        assertEquals("poster_ratings_override", TvMetadataDecisionReason.POSTER_RATINGS_OVERRIDE.eventName)
    }

    @Test
    fun `enrichment accepts nullable TVDB series id for non TVDB fallback data`() {
        val enrichment = TvMetadataEnrichment(
            seriesTvdbId = null,
            localizedTitle = "Fallback title",
            description = "Fallback description",
            genres = listOf("Drama"),
            backdrop = "https://image.example/backdrop.jpg",
            logo = "https://image.example/logo.png",
            poster = "https://image.example/poster.jpg",
            releaseInfo = "2024",
            rating = 8.4,
            runtimeMinutes = 45,
            ageRating = "TV-14",
            countries = listOf("United States"),
            language = "en",
            airsDays = mapOf("monday" to true),
            airsTime = "21:00",
            averageRuntimeMinutes = 46,
            originalCountry = "usa",
            originalLanguage = "eng",
            status = "Continuing",
            aliases = listOf("Fallback alias"),
            contentRatings = listOf("TV-14"),
            remoteIds = mapOf("imdb" to setOf("tt1234567"))
        )

        assertNull(enrichment.seriesTvdbId)
        assertEquals("21:00", enrichment.airsTime)
        assertEquals(setOf("tt1234567"), enrichment.remoteIds["imdb"])
    }

    @Test
    fun `episode metadata retains TVDB episode placement and finale fields without changing Video`() {
        val episode = TvEpisodeMetadata(
            providerEpisodeId = "tvdb:987",
            seasonNumber = 0,
            episodeNumber = 3,
            title = "A Special",
            overview = "Special placement metadata",
            thumbnail = "https://image.example/episode.jpg",
            airDate = "2024-10-10",
            runtimeMinutes = 51,
            absoluteNumber = 12,
            airsAfterSeason = 1,
            airsBeforeSeason = 2,
            airsBeforeEpisode = 1,
            linkedMovieTvdbId = 4444,
            finaleType = "series"
        )

        assertEquals(12, episode.absoluteNumber)
        assertEquals(2, episode.airsBeforeSeason)
        assertEquals(1, episode.airsBeforeEpisode)
        assertEquals(4444, episode.linkedMovieTvdbId)
        assertEquals("series", episode.finaleType)
    }

    @Test
    fun `metadata request and decision carry provider neutral routing inputs and diagnostics`() {
        val request = TvMetadataRequest(
            contentId = "series:game-of-thrones",
            fallbackContentId = "tmdb:1399",
            contentType = ContentType.SERIES,
            language = "en-US",
            seasonNumbers = listOf(1, 2)
        )
        val diagnostic = TvMetadataDiagnosticEvent(
            reason = TvMetadataDecisionReason.TMDB_TV_SKIPPED,
            contentId = request.contentId,
            provider = TvProvider.TVDB,
            fallbackProvider = TvProvider.TMDB,
            detail = "TVDB success path"
        )

        val decision = TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = request,
            diagnostics = listOf(diagnostic)
        )

        assertEquals(ContentType.SERIES, request.contentType)
        assertEquals(listOf(1, 2), request.seasonNumbers)
        assertEquals(TvProvider.TVDB, decision.provider)
        assertTrue(decision.diagnostics.single().detail?.contains("success") == true)
    }
}
