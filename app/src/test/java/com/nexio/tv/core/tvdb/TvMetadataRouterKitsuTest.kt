package com.nexio.tv.core.tvdb

import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.anime.KitsuMetadataService
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.TvdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TvMetadataRouterKitsuTest {
    @Test
    fun `anime id uses kitsu before tvdb and tmdb`() = runTest {
        val kitsu = mockk<KitsuMetadataService>()
        val tvdbIdentity = mockk<TvdbIdentityService>(relaxed = true)
        val tvdbMetadata = mockk<TvdbMetadataService>(relaxed = true)
        val tmdb = mockk<TmdbService>(relaxed = true)
        val tmdbMetadata = mockk<TmdbMetadataService>(relaxed = true)
        val router = router(kitsu, tvdbIdentity, tvdbMetadata, tmdb, tmdbMetadata)

        coEvery { kitsu.fetchEnrichment("mal:5114", ContentMediaKind.SERIES) } returns TvMetadataEnrichment(
            seriesTvdbId = null,
            localizedTitle = "Fullmetal Alchemist: Brotherhood"
        )

        val decision = router.fetchEnrichment(
            TvMetadataRequest(
                contentId = "mal:5114",
                contentType = ContentType.SERIES,
                language = "en-US"
            )
        )

        assertEquals(TvProvider.KITSU, decision.provider)
        assertEquals(TvMetadataDecisionReason.KITSU_SUCCESS, decision.reason)
        assertEquals("Fullmetal Alchemist: Brotherhood", decision.value?.localizedTitle)
        coVerify(exactly = 0) { tvdbIdentity.resolveSeriesByRemoteId(any(), any()) }
        coVerify(exactly = 0) { tmdb.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbMetadata.fetchEnrichment(any(), any(), any()) }
    }

    @Test
    fun `mapped tmdb tvdb and imdb anime ids use kitsu`() = runTest {
        val kitsu = mockk<KitsuMetadataService>()
        val router = router(kitsu)
        coEvery { kitsu.fetchEnrichment("tmdb:31911", ContentMediaKind.SERIES) } returns TvMetadataEnrichment(localizedTitle = "FMA:B", seriesTvdbId = null)
        coEvery { kitsu.fetchEnrichment("tvdb:85249", ContentMediaKind.SERIES) } returns TvMetadataEnrichment(localizedTitle = "FMA:B", seriesTvdbId = null)
        coEvery { kitsu.fetchEnrichment("tt1355642", ContentMediaKind.SERIES) } returns TvMetadataEnrichment(localizedTitle = "FMA:B", seriesTvdbId = null)

        assertEquals(TvProvider.KITSU, router.fetchEnrichment(TvMetadataRequest("tmdb:31911", contentType = ContentType.SERIES)).provider)
        assertEquals(TvProvider.KITSU, router.fetchEnrichment(TvMetadataRequest("tvdb:85249", contentType = ContentType.SERIES)).provider)
        assertEquals(TvProvider.KITSU, router.fetchEnrichment(TvMetadataRequest("tt1355642", contentType = ContentType.SERIES)).provider)
    }

    @Test
    fun `anime id uses kitsu for season episodes`() = runTest {
        val kitsu = mockk<KitsuMetadataService>()
        val tvdbIdentity = mockk<TvdbIdentityService>(relaxed = true)
        val tvdbMetadata = mockk<TvdbMetadataService>(relaxed = true)
        val router = router(kitsu, tvdbIdentity, tvdbMetadata)
        coEvery { kitsu.fetchSeasonEpisodes("kitsu:1", ContentMediaKind.SERIES, 1) } returns listOf(
            TvSeasonEpisode(
                episodeNumber = 1,
                airDate = "1998-04-03",
                metadata = TvEpisodeMetadata(
                    seasonNumber = 1,
                    episodeNumber = 1,
                    title = "Asteroid Blues"
                )
            )
        )

        val decision = router.fetchSeasonEpisodes(
            contentId = "kitsu:1",
            fallbackContentId = null,
            seasonNumber = 1,
            language = "en-US"
        )

        assertEquals(TvProvider.KITSU, decision.provider)
        assertEquals("Asteroid Blues", decision.value?.first()?.metadata?.title)
        coVerify(exactly = 0) { tvdbIdentity.resolveSeriesByRemoteId(any(), any()) }
        coVerify(exactly = 0) { tvdbMetadata.fetchSeasonEpisodes(any(), any(), any()) }
    }

    @Test
    fun `non anime id keeps tvdb route`() = runTest {
        val kitsu = mockk<KitsuMetadataService>()
        val tvdbIdentity = mockk<TvdbIdentityService>()
        val tvdbMetadata = mockk<TvdbMetadataService>()
        val identity = TvdbSeriesIdentity(tvdbId = 121361)
        val router = router(kitsu, tvdbIdentity, tvdbMetadata)

        coEvery { kitsu.fetchEnrichment("tt0944947", ContentMediaKind.SERIES) } returns null
        coEvery { tvdbIdentity.resolveSeriesByRemoteId("tt0944947", TvdbRemoteIdSource.IMDB) } returns identity
        coEvery { tvdbMetadata.fetchSeriesEnrichment(identity, "en-US") } returns TvMetadataEnrichment(
            seriesTvdbId = 121361,
            localizedTitle = "Game of Thrones"
        )

        val decision = router.fetchEnrichment(
            TvMetadataRequest("tt0944947", contentType = ContentType.SERIES, language = "en-US")
        )

        assertEquals(TvProvider.TVDB, decision.provider)
        coVerify(exactly = 1) { kitsu.fetchEnrichment("tt0944947", ContentMediaKind.SERIES) }
    }

    private fun router(
        kitsuMetadataService: KitsuMetadataService,
        tvdbIdentityService: TvdbIdentityService = mockk(relaxed = true),
        tvdbMetadataService: TvdbMetadataService = mockk(relaxed = true),
        tmdbService: TmdbService = mockk(relaxed = true),
        tmdbMetadataService: TmdbMetadataService = mockk(relaxed = true)
    ): TvMetadataRouter {
        val tvdbSettings = mockk<TvdbSettingsDataStore>()
        val tmdbSettings = mockk<TmdbSettingsDataStore>()
        every { tvdbSettings.settings } returns flowOf(TvdbSettings())
        every { tmdbSettings.settings } returns flowOf(TmdbSettings())
        return TvMetadataRouter(
            tvdbSettingsDataStore = tvdbSettings,
            tmdbSettingsDataStore = tmdbSettings,
            tvdbIdentityService = tvdbIdentityService,
            tvdbMetadataService = tvdbMetadataService,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService,
            credentialHealth = mockk(relaxed = true) { coEvery { canCallTvdb() } returns true },
            diagnosticsRecorder = mockk(relaxUnitFun = true),
            kitsuMetadataService = kitsuMetadataService
        )
    }
}
