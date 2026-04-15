package com.nexio.tv.core.tvdb

import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tmdb.TmdbEpisodeEnrichment
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.data.remote.api.TmdbEpisode
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.TvdbSettings
import com.nexio.tv.domain.model.TvdbValidationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvMetadataRouterTest {

    @Test
    fun `does not call tmdb when tvdb succeeds`() = runTest {
        val tvdbIdentityService = mockk<TvdbIdentityService>()
        val tvdbMetadataService = mockk<TvdbMetadataService>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val router = tvMetadataRouter(
            tvdbIdentityService = tvdbIdentityService,
            tvdbMetadataService = tvdbMetadataService,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService
        )
        val identity = TvdbSeriesIdentity(tvdbId = 121361)
        val tvdbEnrichment = TvMetadataEnrichment(
            seriesTvdbId = 121361,
            localizedTitle = "Game of Thrones"
        )

        coEvery {
            tvdbIdentityService.resolveSeriesByRemoteId("tt0944947", TvdbRemoteIdSource.IMDB)
        } returns identity
        coEvery { tvdbMetadataService.fetchSeriesEnrichment(identity, "en-US") } returns tvdbEnrichment

        val decision = router.fetchEnrichment(
            TvMetadataRequest(
                contentId = "tt0944947",
                contentType = ContentType.SERIES,
                language = "en-US"
            )
        )

        assertEquals(TvProvider.TVDB, decision.provider)
        assertEquals(TvMetadataDecisionReason.TVDB_SUCCESS, decision.reason)
        assertEquals(121361, decision.value?.seriesTvdbId)
        assertTrue(decision.diagnostics.any { it.reason == TvMetadataDecisionReason.TMDB_TV_SKIPPED })
        coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), any(), any()) }
    }

    @Test
    fun `falls back to tmdb when tvdb inactive`() = runTest {
        val tmdbService = mockk<TmdbService>()
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        val router = tvMetadataRouter(
            settings = TvdbSettings(enabled = false),
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService
        )

        coEvery { tmdbService.ensureTmdbId("tt0944947", "series") } returns "1399"
        coEvery {
            tmdbMetadataService.fetchEnrichment("1399", ContentType.SERIES, "en-US")
        } returns tmdbEnrichment(title = "Fallback title")

        val decision = router.fetchEnrichment(
            TvMetadataRequest(
                contentId = "tt0944947",
                contentType = ContentType.SERIES,
                language = "en-US"
            )
        )

        assertEquals(TvProvider.TMDB, decision.provider)
        assertEquals("Fallback title", decision.value?.localizedTitle)
        assertNull(decision.value?.seriesTvdbId)
        assertTrue(decision.diagnostics.any { it.reason == TvMetadataDecisionReason.TVDB_INACTIVE })
        assertTrue(decision.diagnostics.any { it.reason == TvMetadataDecisionReason.TVDB_FALLBACK_TMDB })
        coVerify(exactly = 1) { tmdbService.ensureTmdbId("tt0944947", "series") }
        coVerify(exactly = 1) { tmdbMetadataService.fetchEnrichment("1399", ContentType.SERIES, "en-US") }
    }

    @Test
    fun `does not call tmdb fallback when tvdb inactive and tmdb disabled`() = runTest {
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val router = tvMetadataRouter(
            settings = TvdbSettings(enabled = false),
            tmdbSettings = TmdbSettings(enabled = false, apiKey = "tmdb-key"),
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService
        )

        val decision = router.fetchEnrichment(
            TvMetadataRequest(
                contentId = "tt0944947",
                contentType = ContentType.SERIES,
                language = "en-US"
            )
        )

        assertEquals(TvProvider.TMDB, decision.provider)
        assertEquals(TvMetadataDecisionReason.TVDB_INACTIVE, decision.reason)
        assertNull(decision.value)
        assertTrue(decision.diagnostics.any { it.reason == TvMetadataDecisionReason.TVDB_INACTIVE })
        assertTrue(decision.diagnostics.any { it.reason == TvMetadataDecisionReason.TVDB_FALLBACK_TMDB })
        coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), any(), any()) }
    }

    @Test
    fun `does not call tmdb fallback when tvdb record missing and tmdb disabled`() = runTest {
        val tvdbIdentityService = mockk<TvdbIdentityService>()
        val tvdbMetadataService = mockk<TvdbMetadataService>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val identity = TvdbSeriesIdentity(tvdbId = 121361)
        val router = tvMetadataRouter(
            tvdbIdentityService = tvdbIdentityService,
            tvdbMetadataService = tvdbMetadataService,
            tmdbSettings = TmdbSettings(enabled = false, apiKey = "tmdb-key"),
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService
        )

        coEvery {
            tvdbIdentityService.resolveSeriesByRemoteId("tt0944947", TvdbRemoteIdSource.IMDB)
        } returns identity
        coEvery { tvdbMetadataService.fetchSeriesEnrichment(identity, "en-US") } returns null

        val decision = router.fetchEnrichment(
            TvMetadataRequest(
                contentId = "tt0944947",
                contentType = ContentType.SERIES,
                language = "en-US"
            )
        )

        assertEquals(TvProvider.TMDB, decision.provider)
        assertEquals(TvMetadataDecisionReason.TVDB_RECORD_MISSING, decision.reason)
        assertNull(decision.value)
        assertTrue(decision.diagnostics.any { it.reason == TvMetadataDecisionReason.TVDB_RECORD_MISSING })
        assertTrue(decision.diagnostics.any { it.reason == TvMetadataDecisionReason.TVDB_FALLBACK_TMDB })
        coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), any(), any()) }
    }

    @Test
    fun `does not call tmdb episode or season fallback when tmdb disabled`() = runTest {
        val tvdbIdentityService = mockk<TvdbIdentityService>()
        val tvdbMetadataService = mockk<TvdbMetadataService>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val identity = TvdbSeriesIdentity(tvdbId = 121361)
        val router = tvMetadataRouter(
            tvdbIdentityService = tvdbIdentityService,
            tvdbMetadataService = tvdbMetadataService,
            tmdbSettings = TmdbSettings(enabled = false, apiKey = "tmdb-key"),
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService
        )

        coEvery {
            tvdbIdentityService.resolveSeriesByRemoteId("tt0944947", TvdbRemoteIdSource.IMDB)
        } returns identity
        coEvery {
            tvdbMetadataService.fetchEpisodeEnrichment(identity, listOf(1), "en-US")
        } returns emptyMap()
        coEvery { tvdbMetadataService.fetchSeasonEpisodes(identity, 1, "en-US") } returns emptyList()

        val episodeDecision = router.fetchEpisodeEnrichment(
            TvMetadataRequest(
                contentId = "tt0944947",
                contentType = ContentType.SERIES,
                language = "en-US",
                seasonNumbers = listOf(1)
            )
        )
        val seasonDecision = router.fetchSeasonEpisodes(
            contentId = "tt0944947",
            fallbackContentId = null,
            seasonNumber = 1,
            language = "en-US"
        )

        assertEquals(TvProvider.TMDB, episodeDecision.provider)
        assertEquals(TvMetadataDecisionReason.TVDB_RECORD_MISSING, episodeDecision.reason)
        assertEquals(emptyMap<Pair<Int, Int>, TvEpisodeMetadata>(), episodeDecision.value)
        assertEquals(TvProvider.TMDB, seasonDecision.provider)
        assertEquals(TvMetadataDecisionReason.TVDB_RECORD_MISSING, seasonDecision.reason)
        assertEquals(emptyList<TvSeasonEpisode>(), seasonDecision.value)
        coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEpisodeEnrichment(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchSeasonEpisodes(any(), any(), any()) }
    }

    @Test
    fun `movie adapter returns tmdb provider with null seriesTvdbId`() = runTest {
        val tmdbService = mockk<TmdbService>()
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        val tvdbIdentityService = mockk<TvdbIdentityService>(relaxed = true)
        val tvdbMetadataService = mockk<TvdbMetadataService>(relaxed = true)
        val router = tvMetadataRouter(
            tvdbIdentityService = tvdbIdentityService,
            tvdbMetadataService = tvdbMetadataService,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService
        )

        coEvery { tmdbService.ensureTmdbId("tt0137523", "movie") } returns "550"
        coEvery {
            tmdbMetadataService.fetchEnrichment("550", ContentType.MOVIE, "en-US")
        } returns tmdbEnrichment(title = "Fight Club")

        val decision = router.fetchEnrichment(
            TvMetadataRequest(
                contentId = "tt0137523",
                contentType = ContentType.MOVIE,
                language = "en-US"
            )
        )

        assertEquals(TvProvider.TMDB, decision.provider)
        assertEquals("Fight Club", decision.value?.localizedTitle)
        assertNull(decision.value?.seriesTvdbId)
        coVerify(exactly = 0) { tvdbIdentityService.resolveSeriesByRemoteId(any(), any()) }
        coVerify(exactly = 0) { tvdbIdentityService.resolveSeriesByTvdbId(any()) }
        coVerify(exactly = 0) { tvdbMetadataService.fetchSeriesEnrichment(any(), any()) }
    }

    @Test
    fun `TVDB fallback adapter returns null seriesTvdbId when identity missing`() = runTest {
        val tvdbIdentityService = mockk<TvdbIdentityService>()
        val tmdbService = mockk<TmdbService>()
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        val router = tvMetadataRouter(
            tvdbIdentityService = tvdbIdentityService,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService
        )

        coEvery {
            tvdbIdentityService.resolveSeriesByRemoteId("tt0944947", TvdbRemoteIdSource.IMDB)
        } returns null
        coEvery { tmdbService.ensureTmdbId("tmdb:1399", "series") } returns "1399"
        coEvery {
            tmdbMetadataService.fetchEnrichment("1399", ContentType.SERIES, "en-US")
        } returns tmdbEnrichment(title = "Fallback title")

        val decision = router.fetchEnrichment(
            TvMetadataRequest(
                contentId = "tt0944947",
                fallbackContentId = "tmdb:1399",
                contentType = ContentType.SERIES,
                language = "en-US"
            )
        )

        assertEquals(TvProvider.TMDB, decision.provider)
        assertNull(decision.value?.seriesTvdbId)
        assertTrue(decision.diagnostics.any { it.reason == TvMetadataDecisionReason.TVDB_IDENTITY_MISSING })
        assertTrue(decision.diagnostics.any { it.reason == TvMetadataDecisionReason.TVDB_FALLBACK_TMDB })
    }

    @Test
    fun `episode and season fallback adapters keep seriesTvdbId out of TMDB data`() = runTest {
        val tvdbIdentityService = mockk<TvdbIdentityService>()
        val tmdbService = mockk<TmdbService>()
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        val router = tvMetadataRouter(
            settings = TvdbSettings(enabled = false),
            tvdbIdentityService = tvdbIdentityService,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService
        )

        coEvery { tmdbService.ensureTmdbId("tt0944947", "series") } returns "1399"
        coEvery {
            tmdbMetadataService.fetchEpisodeEnrichment("1399", listOf(1), "en-US")
        } returns mapOf(1 to 1 to tmdbEpisodeEnrichment())
        coEvery {
            tmdbMetadataService.fetchSeasonEpisodes(1399, 1, "en-US")
        } returns listOf(tmdbEpisode())

        val episodeDecision = router.fetchEpisodeEnrichment(
            TvMetadataRequest(
                contentId = "tt0944947",
                contentType = ContentType.SERIES,
                language = "en-US",
                seasonNumbers = listOf(1)
            )
        )
        val seasonDecision = router.fetchSeasonEpisodes(
            contentId = "tt0944947",
            fallbackContentId = null,
            seasonNumber = 1,
            language = "en-US"
        )

        assertEquals("tmdb:1001", episodeDecision.value?.get(1 to 1)?.providerEpisodeId)
        assertEquals("tmdb:1001", seasonDecision.value?.single()?.metadata?.providerEpisodeId)
        coVerify(exactly = 0) { tvdbIdentityService.resolveSeriesByRemoteId(any(), any()) }
        coVerify(exactly = 0) { tvdbIdentityService.resolveSeriesByTvdbId(any()) }
    }

    private fun tvMetadataRouter(
        settings: TvdbSettings = TvdbSettings(
            enabled = true,
            apiKey = "tvdb-key",
            validationStatus = TvdbValidationStatus.VALID
        ),
        tmdbSettings: TmdbSettings = TmdbSettings(enabled = true, apiKey = "tmdb-key"),
        tvdbIdentityService: TvdbIdentityService = mockk(relaxed = true),
        tvdbMetadataService: TvdbMetadataService = mockk(relaxed = true),
        tmdbService: TmdbService = mockk(relaxed = true),
        tmdbMetadataService: TmdbMetadataService = mockk(relaxed = true)
    ): TvMetadataRouter {
        val settingsDataStore = mockk<TvdbSettingsDataStore>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        every { settingsDataStore.settings } returns flowOf(settings)
        every { tmdbSettingsDataStore.settings } returns flowOf(tmdbSettings)

        return TvMetadataRouter(
            tvdbSettingsDataStore = settingsDataStore,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            tvdbIdentityService = tvdbIdentityService,
            tvdbMetadataService = tvdbMetadataService,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService
        )
    }

    private fun tmdbEnrichment(title: String): TmdbEnrichment = TmdbEnrichment(
        localizedTitle = title,
        description = "Fallback description",
        genres = listOf("Drama"),
        backdrop = "https://image.tmdb.org/t/p/w1280/backdrop.jpg",
        logo = "https://image.tmdb.org/t/p/w500/logo.png",
        poster = "https://image.tmdb.org/t/p/w500/poster.jpg",
        directorMembers = emptyList(),
        writerMembers = emptyList(),
        castMembers = emptyList(),
        releaseInfo = "2020-01-01",
        rating = 8.2,
        runtimeMinutes = 45,
        director = emptyList(),
        writer = emptyList(),
        productionCompanies = emptyList(),
        networks = emptyList(),
        ageRating = "TV-MA",
        countries = listOf("United States"),
        language = "en",
        collectionId = null,
        collectionName = null
    )

    private fun tmdbEpisodeEnrichment(): TmdbEpisodeEnrichment = TmdbEpisodeEnrichment(
        tmdbEpisodeId = 1001,
        voteAverage = 8.4,
        title = "Winter Is Coming",
        overview = "The first episode.",
        thumbnail = "https://image.tmdb.org/t/p/w500/still.jpg",
        airDate = "2011-04-17",
        runtimeMinutes = 62
    )

    private fun tmdbEpisode(): TmdbEpisode = TmdbEpisode(
        id = 1001,
        episodeNumber = 1,
        name = "Winter Is Coming",
        overview = "The first episode.",
        stillPath = "/still.jpg",
        airDate = "2011-04-17",
        runtime = 62,
        voteAverage = 8.4
    )
}
