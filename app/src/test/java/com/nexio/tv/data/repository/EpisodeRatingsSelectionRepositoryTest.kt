package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.resolver.SourceRole
import com.nexio.tv.core.tmdb.TmdbEpisodeEnrichment
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaLink
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.Video
import com.nexio.tv.ui.screens.detail.EpisodeRating
import com.nexio.tv.ui.screens.detail.EpisodeRatingSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeRatingsSelectionRepositoryTest {

    @Test
    fun `candidate source emits custom imdb tmdb and omdb ratings without selecting winners`() = runTest {
        val customRepository = mockk<CustomImdbEpisodeRatingsRepository>()
        val tmdbService = mockk<TmdbService>()
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        val omdbRepository = mockk<OmdbEpisodeRatingsRepository>()
        coEvery {
            customRepository.getEpisodeRatingsForMeta(any(), any(), any(), any())
        } returns mapOf((1 to 1) to 8.4)
        coEvery { tmdbService.ensureTmdbId("tt27444205", "series") } returns "100"
        coEvery { tmdbMetadataService.fetchEpisodeEnrichment("100", listOf(1)) } returns mapOf(
            (1 to 1) to stubEpisodeEnrichment(7.2),
            (1 to 2) to stubEpisodeEnrichment(6.1)
        )
        coEvery {
            omdbRepository.getEpisodeRatingsForMeta(any(), any(), any(), any())
        } returns mapOf((1 to 1) to 8.1)

        val repository = repository(
            customRepository = customRepository,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService,
            omdbRepository = omdbRepository,
            customActive = true
        )

        val candidates = repository.episodeRatingCandidates(
            meta = stubMeta("tt27444205"),
            fallbackItemId = "",
            fallbackItemType = "series",
            episodesBySeason = mapOf(1 to setOf(1, 2))
        )

        assertEquals(
            listOf(SourceRole.CUSTOM_IMDB, SourceRole.PRIMARY_PROVIDER, SourceRole.PRIMARY_PROVIDER, SourceRole.OMDB),
            candidates.map { it.sourceRole }
        )
        assertEquals(listOf(8.4, 7.2, 6.1, 8.1), candidates.map { it.value })
    }

    @Test
    fun `candidate source backfills from tmdb and omdb when active custom imdb returns empty`() = runTest {
        val customRepository = mockk<CustomImdbEpisodeRatingsRepository>()
        val tmdbService = mockk<TmdbService>()
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        val omdbRepository = mockk<OmdbEpisodeRatingsRepository>()
        coEvery { customRepository.getEpisodeRatingsForMeta(any(), any(), any(), any()) } returns emptyMap()
        coEvery { tmdbService.ensureTmdbId("tmdb:100", "series") } returns "100"
        coEvery { tmdbMetadataService.fetchEpisodeEnrichment("100", listOf(1)) } returns mapOf(
            (1 to 1) to stubEpisodeEnrichment(7.2)
        )
        coEvery {
            omdbRepository.getEpisodeRatingsForMeta(any(), any(), any(), any())
        } returns mapOf((1 to 1) to 8.1)

        val repository = repository(
            customRepository = customRepository,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService,
            omdbRepository = omdbRepository,
            customActive = true
        )

        val result = repository.getEpisodeRatings(
            meta = stubMeta("tmdb:100"),
            fallbackItemId = "tmdb:100",
            fallbackItemType = "series",
            episodesBySeason = mapOf(1 to setOf(1))
        )

        assertEquals(mapOf((1 to 1) to EpisodeRating(8.1, EpisodeRatingSource.OMDB)), result)
        coVerify(exactly = 1) { tmdbService.ensureTmdbId("tmdb:100", "series") }
        coVerify(exactly = 1) { omdbRepository.getEpisodeRatingsForMeta(any(), any(), any(), any()) }
    }

    @Test
    fun `inactive custom imdb emits provider and omdb candidates only`() = runTest {
        val customRepository = mockk<CustomImdbEpisodeRatingsRepository>()
        val tmdbService = mockk<TmdbService>()
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        val omdbRepository = mockk<OmdbEpisodeRatingsRepository>()
        coEvery { tmdbService.ensureTmdbId("tt27444205", "series") } returns "100"
        coEvery { tmdbMetadataService.fetchEpisodeEnrichment("100", listOf(1)) } returns mapOf(
            (1 to 1) to stubEpisodeEnrichment(7.2),
            (1 to 2) to stubEpisodeEnrichment(6.1)
        )
        coEvery {
            omdbRepository.getEpisodeRatingsForMeta(any(), any(), any(), any())
        } returns mapOf(
            (1 to 2) to 8.4,
            (1 to 3) to 8.1
        )

        val repository = repository(
            customRepository = customRepository,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService,
            omdbRepository = omdbRepository,
            customActive = false
        )

        val result = repository.getEpisodeRatings(
            meta = stubMeta("tt27444205"),
            fallbackItemId = "",
            fallbackItemType = "series",
            episodesBySeason = mapOf(1 to setOf(1, 2, 3))
        )

        assertEquals(EpisodeRating(7.2, EpisodeRatingSource.TMDB), result[1 to 1])
        assertEquals(EpisodeRating(8.4, EpisodeRatingSource.OMDB), result[1 to 2])
        assertEquals(EpisodeRating(8.1, EpisodeRatingSource.OMDB), result[1 to 3])
        coVerify(exactly = 0) { customRepository.getEpisodeRatingsForMeta(any(), any(), any(), any()) }
    }

    private fun repository(
        customRepository: CustomImdbEpisodeRatingsRepository,
        tmdbService: TmdbService,
        tmdbMetadataService: TmdbMetadataService,
        omdbRepository: OmdbEpisodeRatingsRepository,
        customActive: Boolean
    ): EpisodeRatingsSelectionRepository =
        EpisodeRatingsSelectionRepository(
            customImdbEpisodeRatingsRepository = customRepository,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService,
            omdbEpisodeRatingsRepository = omdbRepository
        ).also { it.customImdbActiveProvider = { customActive } }

    private fun stubMeta(id: String): Meta {
        return Meta(
            id = id,
            type = ContentType.SERIES,
            rawType = ContentType.SERIES.toApiString(),
            name = "Stub",
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
            writer = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = emptyList<Video>(),
            productionCompanies = emptyList<MetaCompany>(),
            networks = emptyList<MetaCompany>(),
            ageRating = null,
            country = null,
            awards = null,
            language = null,
            links = emptyList<MetaLink>(),
            trailerYtIds = emptyList()
        )
    }

    private fun stubEpisodeEnrichment(voteAverage: Double): TmdbEpisodeEnrichment {
        return TmdbEpisodeEnrichment(
            tmdbEpisodeId = null,
            voteAverage = voteAverage,
            title = null,
            overview = null,
            thumbnail = null,
            airDate = null,
            runtimeMinutes = null
        )
    }
}
