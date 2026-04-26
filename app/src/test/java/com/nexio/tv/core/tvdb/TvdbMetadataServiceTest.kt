package com.nexio.tv.core.tvdb

import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.remote.api.TvdbAirsDays
import com.nexio.tv.data.remote.api.TvdbAlias
import com.nexio.tv.data.remote.api.TvdbApi
import com.nexio.tv.data.remote.api.TvdbArtworkRecord
import com.nexio.tv.data.remote.api.TvdbCharacterRecord
import com.nexio.tv.data.remote.api.TvdbCompanyExtendedRecord
import com.nexio.tv.data.remote.api.TvdbCompanyRecord
import com.nexio.tv.data.remote.api.TvdbContentRating
import com.nexio.tv.data.remote.api.TvdbEpisodeRecord
import com.nexio.tv.data.remote.api.TvdbGenreRecord
import com.nexio.tv.data.remote.api.TvdbRemoteId
import com.nexio.tv.data.remote.api.TvdbSeriesEpisodesData
import com.nexio.tv.data.remote.api.TvdbSeriesEpisodesResponse
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedResponse
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.data.remote.api.TvdbStatusRecord
import com.nexio.tv.data.remote.api.TvdbTranslationRecord
import com.nexio.tv.data.remote.api.TvdbTranslationResponse
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.PosterRatingsProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import retrofit2.http.GET

class TvdbMetadataServiceTest {

    @Test
    fun `tvdb api exposes extended series episodes and translation endpoints`() {
        val extended = TvdbApi::class.java.methods.first { it.name == "getSeriesExtended" }
        val episodes = TvdbApi::class.java.methods.first { it.name == "getSeriesEpisodes" }
        val seriesTranslation = TvdbApi::class.java.methods.first { it.name == "getSeriesTranslation" }
        val translatedEpisodes = TvdbApi::class.java.methods.first { it.name == "getSeriesEpisodesTranslated" }
        val episodeTranslation = TvdbApi::class.java.methods.first { it.name == "getEpisodeTranslation" }

        assertEquals("series/{id}/extended", extended.getAnnotation(GET::class.java)?.value)
        assertEquals("series/{id}/episodes/{seasonType}", episodes.getAnnotation(GET::class.java)?.value)
        assertEquals("series/{id}/translations/{language}", seriesTranslation.getAnnotation(GET::class.java)?.value)
        assertEquals("series/{id}/episodes/{seasonType}/{language}", translatedEpisodes.getAnnotation(GET::class.java)?.value)
        assertEquals("episodes/{id}/translations/{language}", episodeTranslation.getAnnotation(GET::class.java)?.value)
    }

    @Test
    fun `series extended dto carries metadata fields used by TVDB mapper`() {
        val record = TvdbSeriesExtendedRecord(
            id = 121361,
            name = "Game of Thrones",
            image = "https://art.example/fallback-poster.jpg",
            airsDays = TvdbAirsDays(sunday = true),
            airsTime = "21:00",
            aliases = listOf(TvdbAlias(name = "GoT")),
            artworks = listOf(TvdbArtworkRecord(image = "https://art.example/poster.jpg", type = 2, score = 91.5)),
            averageRuntime = 57,
            contentRatings = listOf(TvdbContentRating(name = "TV-MA", country = "usa")),
            country = "usa",
            episodes = listOf(TvdbEpisodeRecord(id = 1001, seasonNumber = 1, number = 1, name = "Winter Is Coming")),
            firstAired = "2011-04-17",
            genres = listOf(TvdbGenreRecord(name = "Drama")),
            originalCountry = "usa",
            originalLanguage = "eng",
            originalNetwork = TvdbCompanyRecord(name = "HBO"),
            overview = "Nine noble families fight for control.",
            latestNetwork = TvdbCompanyRecord(name = "HBO"),
            remoteIds = listOf(TvdbRemoteId(id = "tt0944947", sourceName = "imdb")),
            score = 8.4,
            status = TvdbStatusRecord(name = "Ended")
        )

        assertEquals("21:00", record.airsTime)
        assertEquals("Drama", record.genres.orEmpty().single().name)
        assertEquals("tt0944947", record.remoteIds.orEmpty().single().id)
        assertEquals("Winter Is Coming", record.episodes.orEmpty().single().name)
    }

    @Test
    fun `episode dto carries placement and linked movie fields`() {
        val response = TvdbSeriesEpisodesResponse(
            status = "success",
            data = TvdbSeriesEpisodesData(
                episodes = listOf(
                    TvdbEpisodeRecord(
                        absoluteNumber = 1,
                        aired = "2011-04-17",
                        airsAfterSeason = 0,
                        airsBeforeEpisode = 1,
                        airsBeforeSeason = 2,
                        finaleType = "series",
                        id = 1001,
                        image = "https://art.example/episode.jpg",
                        linkedMovie = 4444,
                        name = "Winter Is Coming",
                        number = 1,
                        overview = "The first episode.",
                        runtime = 62,
                        seasonNumber = 1
                    )
                )
            )
        )

        val episode = requireNotNull(response.data?.episodes?.single())
        assertNotNull(episode.id)
        assertEquals(1, episode.absoluteNumber)
        assertEquals(4444, episode.linkedMovie)
        assertEquals("series", episode.finaleType)
    }

    @Test
    fun `poster ratings override replaces only poster`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val authService = mockk<TvdbAuthService>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val cacheStore = mockk<MetadataDiskCacheStore>()
        val activeProvider = PosterRatingsUrlResolver.ActiveProvider(
            provider = PosterRatingsProvider.TOP_POSTERS,
            apiKey = "top-key"
        )
        val identity = TvdbSeriesIdentity(
            tvdbId = 121361,
            remoteIds = mapOf(TvdbRemoteIdSource.IMDB to setOf("tt0944947"))
        )
        val service = TvdbMetadataService(tvdbApi, authService, posterResolver, cacheStore, TvdbSeasonOrderMapper(), TvdbAdvancedMetadataMapper(), mockk(relaxed = true) { coEvery { resolveAlias(any(), any()) } returns null }, mockk(relaxed = true) { coEvery { canCallTvdb() } returns true }, mockk(relaxUnitFun = true))

        coEvery { authService.bearerToken() } returns "Bearer tvdb-token"
        coEvery { posterResolver.getActiveProvider() } returns activeProvider
        every {
            cacheStore.readTvdbEnrichment(121361, "series_extended", "eng", "TOP_POSTERS:${"top-key".hashCode()}")
        } returns null
        every { cacheStore.writeTvdbEnrichment(any(), any(), any(), any(), any()) } just Runs
        every {
            posterResolver.resolvePosterUrl(
                originalPosterUrl = "https://art.example/poster.jpg",
                contentId = "tvdb:121361",
                contentType = ContentType.SERIES,
                activeProvider = activeProvider
            )
        } returns "https://api.top-streaming.stream/top-key/tvdb/poster/121361.jpg"
        coEvery {
            tvdbApi.getSeriesExtended("Bearer tvdb-token", 121361, null, false)
        } returns Response.success(TvdbSeriesExtendedResponse(data = fullSeriesRecord()))

        val enrichment = service.fetchSeriesEnrichment(identity, language = "en-US")

        assertNotNull(enrichment)
        assertEquals(121361, enrichment?.seriesTvdbId)
        assertEquals("Game of Thrones", enrichment?.localizedTitle)
        assertEquals("https://api.top-streaming.stream/top-key/tvdb/poster/121361.jpg", enrichment?.poster)
        assertEquals("https://art.example/backdrop.jpg", enrichment?.backdrop)
        assertEquals("https://art.example/logo.png", enrichment?.logo)
        assertEquals(setOf("tt0944947"), enrichment?.remoteIds?.get("imdb"))
        assertEquals("21:00", enrichment?.airsTime)
        assertEquals(57, enrichment?.averageRuntimeMinutes)
        assertEquals("Ended", enrichment?.status)
        coVerify(exactly = 1) { tvdbApi.getSeriesExtended("Bearer tvdb-token", 121361, null, false) }
    }

    @Test
    fun `fetch series enrichment requests full extended record so advanced fields hydrate`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val service = tvdbService(tvdbApi)
        val identity = TvdbSeriesIdentity(tvdbId = 121361)

        coEvery {
            tvdbApi.getSeriesExtended("Bearer tvdb-token", 121361, null, false)
        } returns Response.success(TvdbSeriesExtendedResponse(data = fullSeriesRecord()))

        val enrichment = service.fetchSeriesEnrichment(identity, language = "en-US")

        assertNotNull(enrichment)
        assertEquals("Pedro Pascal", enrichment?.castMembers?.firstOrNull()?.name)
        assertEquals("HBO", enrichment?.networks?.firstOrNull()?.name)
        assertEquals("Bighead Littlehead", enrichment?.productionCompanies?.firstOrNull()?.name)
        coVerify(exactly = 1) { tvdbApi.getSeriesExtended("Bearer tvdb-token", 121361, null, false) }
    }

    @Test
    fun `series enrichment does not expose tvdb score as rating`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val service = tvdbService(tvdbApi)
        val identity = TvdbSeriesIdentity(tvdbId = 121361)

        coEvery {
            tvdbApi.getSeriesExtended("Bearer tvdb-token", 121361, null, false)
        } returns Response.success(
            TvdbSeriesExtendedResponse(data = fullSeriesRecord().copy(score = 14244.2))
        )

        val enrichment = service.fetchSeriesEnrichment(identity, language = "en-US")

        assertNull(enrichment?.rating)
    }

    @Test
    fun `series enrichment overlays tvdb translated overview for requested app language`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val service = tvdbService(tvdbApi)
        val identity = TvdbSeriesIdentity(tvdbId = 121361)

        coEvery {
            tvdbApi.getSeriesExtended("Bearer tvdb-token", 121361, null, false)
        } returns Response.success(
            TvdbSeriesExtendedResponse(
                data = fullSeriesRecord().copy(
                    name = "Game of Thrones",
                    overview = "English TVDB overview"
                )
            )
        )
        coEvery {
            tvdbApi.getSeriesTranslation("Bearer tvdb-token", 121361, "nld")
        } returns Response.success(
            TvdbTranslationResponse(
                data = TvdbTranslationRecord(
                    language = "nld",
                    name = "Dutch title from translation endpoint",
                    overview = "Nederlandse TVDB beschrijving"
                )
            )
        )

        val enrichment = service.fetchSeriesEnrichment(identity, language = "nl")

        assertNotNull(enrichment)
        assertEquals("Game of Thrones", enrichment?.localizedTitle)
        assertEquals("Nederlandse TVDB beschrijving", enrichment?.description)
        coVerify(exactly = 1) { tvdbApi.getSeriesTranslation("Bearer tvdb-token", 121361, "nld") }
    }

    @Test
    fun `series enrichment keeps base overview when tvdb translation is missing`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val service = tvdbService(tvdbApi)
        val identity = TvdbSeriesIdentity(tvdbId = 121361)

        coEvery {
            tvdbApi.getSeriesExtended("Bearer tvdb-token", 121361, null, false)
        } returns Response.success(
            TvdbSeriesExtendedResponse(
                data = fullSeriesRecord().copy(
                    name = "Game of Thrones",
                    overview = "English TVDB overview"
                )
            )
        )
        coEvery {
            tvdbApi.getSeriesTranslation("Bearer tvdb-token", 121361, "nld")
        } returns Response.error(
            404,
            "{}".toResponseBody("application/json".toMediaType())
        )

        val enrichment = service.fetchSeriesEnrichment(identity, language = "nl")

        assertNotNull(enrichment)
        assertEquals("Game of Thrones", enrichment?.localizedTitle)
        assertEquals("English TVDB overview", enrichment?.description)
    }

    @Test
    fun `series mapping preserves timing source fields`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val service = tvdbService(tvdbApi)
        val identity = TvdbSeriesIdentity(tvdbId = 121361)

        coEvery {
            tvdbApi.getSeriesExtended("Bearer tvdb-token", 121361, null, false)
        } returns Response.success(TvdbSeriesExtendedResponse(data = fullSeriesRecord()))

        val enrichment = service.fetchSeriesEnrichment(identity, language = "en-US")

        assertNotNull(enrichment)
        assertEquals("HBO", enrichment?.originalNetwork)
        assertEquals("HBO", enrichment?.latestNetwork)
        assertEquals("HBO", enrichment?.platformName)
    }

    @Test
    fun `fetch episode enrichment maps TVDB episodes by season and episode`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val service = tvdbService(tvdbApi)
        val identity = TvdbSeriesIdentity(tvdbId = 121361)

        coEvery {
            tvdbApi.getSeriesEpisodes("Bearer tvdb-token", 121361, "default", 0, 1, null, null)
        } returns Response.success(TvdbSeriesEpisodesResponse(data = TvdbSeriesEpisodesData(episodes = listOf(episodeRecord()))))

        val episodes = service.fetchEpisodeEnrichment(identity, seasonNumbers = listOf(1), language = "en-US")

        val episode = episodes[1 to 1]
        assertNotNull(episode)
        assertEquals("tvdb:1001", episode?.providerEpisodeId)
        assertEquals("Winter Is Coming", episode?.title)
        assertEquals("https://art.example/episode.jpg", episode?.thumbnail)
        assertEquals(62, episode?.runtimeMinutes)
        assertEquals(1, episode?.absoluteNumber)
        assertEquals(4444, episode?.linkedMovieTvdbId)
    }

    @Test
    fun `fetch episode enrichment uses translated title and overview from season endpoint`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val service = tvdbService(tvdbApi)
        val identity = TvdbSeriesIdentity(tvdbId = 121361)

        coEvery {
            tvdbApi.getSeriesEpisodes("Bearer tvdb-token", 121361, "default", 0, 1, null, null)
        } returns Response.success(
            TvdbSeriesEpisodesResponse(
                data = TvdbSeriesEpisodesData(
                    episodes = listOf(
                        episodeRecord().copy(
                            id = 3254641,
                            name = "Winter Is Coming",
                            overview = "English episode overview",
                            runtime = 62
                        )
                    )
                )
            )
        )
        coEvery {
            tvdbApi.getSeriesEpisodesTranslated("Bearer tvdb-token", 121361, "default", "nld", 0, 1, null, null)
        } returns Response.success(
            TvdbSeriesEpisodesResponse(
                data = TvdbSeriesEpisodesData(
                    episodes = listOf(
                        episodeRecord().copy(
                            id = 3254641,
                            name = "Dutch title from translation endpoint",
                            overview = "Nederlandse afleveringstekst",
                            runtime = 99
                        )
                    )
                )
            )
        )

        val episodes = service.fetchEpisodeEnrichment(identity, seasonNumbers = listOf(1), language = "nl")

        val episode = episodes[1 to 1]
        assertNotNull(episode)
        assertEquals("Dutch title from translation endpoint", episode?.title)
        assertEquals("Nederlandse afleveringstekst", episode?.overview)
        assertEquals(62, episode?.runtimeMinutes)
        coVerify(exactly = 1) {
            tvdbApi.getSeriesEpisodesTranslated("Bearer tvdb-token", 121361, "default", "nld", 0, 1, null, null)
        }
    }

    @Test
    fun `fetch episode enrichment falls back to per episode translation record`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val service = tvdbService(tvdbApi)
        val identity = TvdbSeriesIdentity(tvdbId = 121361)

        coEvery {
            tvdbApi.getSeriesEpisodes("Bearer tvdb-token", 121361, "default", 0, 1, null, null)
        } returns Response.success(
            TvdbSeriesEpisodesResponse(
                data = TvdbSeriesEpisodesData(
                    episodes = listOf(
                        episodeRecord().copy(
                            id = 3254641,
                            name = "Winter Is Coming",
                            overview = "English episode overview"
                        )
                    )
                )
            )
        )
        coEvery {
            tvdbApi.getSeriesEpisodesTranslated("Bearer tvdb-token", 121361, "default", "nld", 0, 1, null, null)
        } returns Response.success(TvdbSeriesEpisodesResponse(data = TvdbSeriesEpisodesData(episodes = emptyList())))
        coEvery {
            tvdbApi.getEpisodeTranslation("Bearer tvdb-token", 3254641, "nld")
        } returns Response.success(
            TvdbTranslationResponse(
                data = TvdbTranslationRecord(
                    name = "Dutch title from episode translation endpoint",
                    overview = "Nederlandse afleveringstekst"
                )
            )
        )

        val episodes = service.fetchEpisodeEnrichment(identity, seasonNumbers = listOf(1), language = "nl")

        val episode = episodes[1 to 1]
        assertNotNull(episode)
        assertEquals("Dutch title from episode translation endpoint", episode?.title)
        assertEquals("Nederlandse afleveringstekst", episode?.overview)
        coVerify(exactly = 1) {
            tvdbApi.getEpisodeTranslation("Bearer tvdb-token", 3254641, "nld")
        }
    }

    @Test
    fun `fetch episode enrichment uses translated title from per-episode endpoint`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val service = tvdbService(tvdbApi)
        val identity = TvdbSeriesIdentity(tvdbId = 121361)

        coEvery {
            tvdbApi.getSeriesEpisodes("Bearer tvdb-token", 121361, "default", 0, 1, null, null)
        } returns Response.success(
            TvdbSeriesEpisodesResponse(
                data = TvdbSeriesEpisodesData(
                    episodes = listOf(
                        episodeRecord().copy(
                            id = 3254641,
                            name = "עולם רדיואקטיבי",
                            overview = "Hebrew base overview"
                        )
                    )
                )
            )
        )
        coEvery {
            tvdbApi.getSeriesEpisodesTranslated("Bearer tvdb-token", 121361, "default", "nld", 0, 1, null, null)
        } returns Response.success(TvdbSeriesEpisodesResponse(data = TvdbSeriesEpisodesData(episodes = emptyList())))
        coEvery {
            tvdbApi.getEpisodeTranslation("Bearer tvdb-token", 3254641, "nld")
        } returns Response.success(
            TvdbTranslationResponse(
                data = TvdbTranslationRecord(
                    name = "Tegenaanval",
                    overview = "Tamar duikt onder",
                    language = "nld"
                )
            )
        )

        val episodes = service.fetchEpisodeEnrichment(identity, seasonNumbers = listOf(1), language = "nl")

        val episode = episodes[1 to 1]
        assertNotNull(episode)
        assertEquals("Tegenaanval", episode?.title)
        assertEquals("Tamar duikt onder", episode?.overview)
    }

    @Test
    fun `fetch season episodes reads cache before TVDB network`() = runTest {
        val tvdbApi = mockk<TvdbApi>(relaxed = true)
        val authService = mockk<TvdbAuthService>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val cacheStore = mockk<MetadataDiskCacheStore>()
        val service = TvdbMetadataService(tvdbApi, authService, posterResolver, cacheStore, TvdbSeasonOrderMapper(), TvdbAdvancedMetadataMapper(), mockk(relaxed = true) { coEvery { resolveAlias(any(), any()) } returns null }, mockk(relaxed = true) { coEvery { canCallTvdb() } returns true }, mockk(relaxUnitFun = true))
        val cached = listOf(
            TvEpisodeMetadata(
                providerEpisodeId = "tvdb:1001",
                seasonNumber = 1,
                episodeNumber = 1,
                title = "Cached Pilot",
                airDate = "2011-04-17"
            )
        )

        every {
            cacheStore.readTvdbSeasonEpisodes(121361, "default", 1, "eng")
        } returns cached

        val episodes = service.fetchSeasonEpisodes(TvdbSeriesIdentity(tvdbId = 121361), 1, "en-US")

        assertEquals("Cached Pilot", episodes.single().metadata.title)
        coVerify(exactly = 0) { authService.bearerToken() }
        coVerify(exactly = 0) { tvdbApi.getSeriesEpisodes(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `does not cache thrown season episode request`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val authService = mockk<TvdbAuthService>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val cacheStore = mockk<MetadataDiskCacheStore>()
        val service = TvdbMetadataService(tvdbApi, authService, posterResolver, cacheStore, TvdbSeasonOrderMapper(), TvdbAdvancedMetadataMapper(), mockk(relaxed = true) { coEvery { resolveAlias(any(), any()) } returns null }, mockk(relaxed = true) { coEvery { canCallTvdb() } returns true }, mockk(relaxUnitFun = true))

        every { cacheStore.readTvdbSeasonEpisodes(121361, "default", 1, "eng") } returns null
        every { cacheStore.writeTvdbSeasonEpisodes(any(), any(), any(), any(), any()) } just Runs
        coEvery { authService.bearerToken() } returns "Bearer tvdb-token"
        coEvery {
            tvdbApi.getSeriesEpisodes("Bearer tvdb-token", 121361, "default", 0, 1, null, null)
        } throws RuntimeException("remote down")

        val episodes = service.fetchSeasonEpisodes(TvdbSeriesIdentity(tvdbId = 121361), 1, "en-US")

        assertEquals(emptyList<TvSeasonEpisode>(), episodes)
        verify(exactly = 0) { cacheStore.writeTvdbSeasonEpisodes(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `does not cache non success season episode response`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val authService = mockk<TvdbAuthService>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val cacheStore = mockk<MetadataDiskCacheStore>()
        val service = TvdbMetadataService(tvdbApi, authService, posterResolver, cacheStore, TvdbSeasonOrderMapper(), TvdbAdvancedMetadataMapper(), mockk(relaxed = true) { coEvery { resolveAlias(any(), any()) } returns null }, mockk(relaxed = true) { coEvery { canCallTvdb() } returns true }, mockk(relaxUnitFun = true))

        every { cacheStore.readTvdbSeasonEpisodes(121361, "default", 1, "eng") } returns null
        every { cacheStore.writeTvdbSeasonEpisodes(any(), any(), any(), any(), any()) } just Runs
        coEvery { authService.bearerToken() } returns "Bearer tvdb-token"
        coEvery {
            tvdbApi.getSeriesEpisodes("Bearer tvdb-token", 121361, "default", 0, 1, null, null)
        } returns Response.error(
            500,
            "{}".toResponseBody("application/json".toMediaType())
        )

        val episodes = service.fetchSeasonEpisodes(TvdbSeriesIdentity(tvdbId = 121361), 1, "en-US")

        assertEquals(emptyList<TvSeasonEpisode>(), episodes)
        verify(exactly = 0) { cacheStore.writeTvdbSeasonEpisodes(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `caches successful empty season episode response`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val authService = mockk<TvdbAuthService>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val cacheStore = mockk<MetadataDiskCacheStore>()
        val service = TvdbMetadataService(tvdbApi, authService, posterResolver, cacheStore, TvdbSeasonOrderMapper(), TvdbAdvancedMetadataMapper(), mockk(relaxed = true) { coEvery { resolveAlias(any(), any()) } returns null }, mockk(relaxed = true) { coEvery { canCallTvdb() } returns true }, mockk(relaxUnitFun = true))

        every { cacheStore.readTvdbSeasonEpisodes(121361, "default", 1, "eng") } returns null
        every { cacheStore.writeTvdbSeasonEpisodes(121361, "default", 1, "eng", emptyList()) } just Runs
        coEvery { authService.bearerToken() } returns "Bearer tvdb-token"
        coEvery {
            tvdbApi.getSeriesEpisodes("Bearer tvdb-token", 121361, "default", 0, 1, null, null)
        } returns Response.success(TvdbSeriesEpisodesResponse(data = TvdbSeriesEpisodesData(episodes = emptyList())))

        val episodes = service.fetchSeasonEpisodes(TvdbSeriesIdentity(tvdbId = 121361), 1, "en-US")

        assertEquals(emptyList<TvSeasonEpisode>(), episodes)
        verify(exactly = 1) { cacheStore.writeTvdbSeasonEpisodes(121361, "default", 1, "eng", emptyList()) }
    }

    private fun tvdbService(tvdbApi: TvdbApi): TvdbMetadataService {
        val authService = mockk<TvdbAuthService>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val cacheStore = mockk<MetadataDiskCacheStore>()

        coEvery { authService.bearerToken() } returns "Bearer tvdb-token"
        coEvery { posterResolver.getActiveProvider() } returns null
        every { cacheStore.readTvdbEnrichment(any(), any(), any(), any()) } returns null
        every { cacheStore.writeTvdbEnrichment(any(), any(), any(), any(), any()) } just Runs
        every { cacheStore.readTvdbSeasonEpisodes(any(), any(), any(), any()) } returns null
        every { cacheStore.writeTvdbSeasonEpisodes(any(), any(), any(), any(), any()) } just Runs
        every { posterResolver.resolvePosterUrl(any(), any(), any(), null) } answers { firstArg() }

        val mergeAliasStore = mockk<com.nexio.tv.data.local.TvdbMergeAliasStore>(relaxed = true)
        coEvery { mergeAliasStore.resolveAlias(any(), any()) } returns null
        val credentialHealth = mockk<TvdbCredentialHealth>(relaxed = true)
        coEvery { credentialHealth.canCallTvdb() } returns true
        val diagnosticsRecorder = mockk<TvdbDiagnosticsRecorder>(relaxUnitFun = true)

        return TvdbMetadataService(tvdbApi, authService, posterResolver, cacheStore, TvdbSeasonOrderMapper(), TvdbAdvancedMetadataMapper(), mergeAliasStore, credentialHealth, diagnosticsRecorder)
    }

    private fun fullSeriesRecord(): TvdbSeriesExtendedRecord = TvdbSeriesExtendedRecord(
        id = 121361,
        name = "Game of Thrones",
        image = "https://art.example/fallback-poster.jpg",
        airsDays = TvdbAirsDays(sunday = true),
        airsTime = "21:00",
        aliases = listOf(TvdbAlias(name = "GoT")),
        artworks = listOf(
            TvdbArtworkRecord(image = "https://art.example/backdrop.jpg", type = 3, score = 90.0),
            TvdbArtworkRecord(image = "https://art.example/logo.png", type = 23, score = 80.0),
            TvdbArtworkRecord(image = "https://art.example/poster-low.jpg", type = 2, score = 10.0),
            TvdbArtworkRecord(image = "https://art.example/poster.jpg", type = 2, score = 95.0)
        ),
        averageRuntime = 57,
        contentRatings = listOf(TvdbContentRating(name = "TV-MA", country = "usa")),
        country = "usa",
        episodes = listOf(episodeRecord()),
        firstAired = "2011-04-17",
        genres = listOf(TvdbGenreRecord(name = "Drama")),
        originalCountry = "usa",
        originalLanguage = "eng",
        originalNetwork = TvdbCompanyRecord(name = "HBO"),
        overview = "Nine noble families fight for control.",
        latestNetwork = TvdbCompanyRecord(name = "HBO"),
        remoteIds = listOf(TvdbRemoteId(id = "tt0944947", sourceName = "imdb")),
        score = 8.4,
        status = TvdbStatusRecord(name = "Ended"),
        characters = listOf(
            TvdbCharacterRecord(personName = "Pedro Pascal", name = "Joel Miller", peopleType = "Actor", sort = 1)
        ),
        companies = listOf(
            TvdbCompanyExtendedRecord(name = "Bighead Littlehead", primaryCompanyType = 3)
        )
    )

    private fun episodeRecord(): TvdbEpisodeRecord = TvdbEpisodeRecord(
        absoluteNumber = 1,
        aired = "2011-04-17",
        airsAfterSeason = 0,
        airsBeforeEpisode = 1,
        airsBeforeSeason = 2,
        finaleType = "series",
        id = 1001,
        image = "https://art.example/episode.jpg",
        linkedMovie = 4444,
        name = "Winter Is Coming",
        number = 1,
        overview = "The first episode.",
        runtime = 62,
        seasonNumber = 1
    )
}
