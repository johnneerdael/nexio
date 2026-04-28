package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ProviderPlanRole
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolverType
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbRecommendationMetadataAdapterTest {

    private val samplePreview = MetaPreview(
        id = "tmdb:1",
        type = ContentType.MOVIE,
        name = "Similar Movie",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    @Test
    fun `supports declares MOVIE_RECOMMENDATIONS and TV_RECOMMENDATIONS only`() {
        val adapter = TmdbRecommendationMetadataAdapter(mockk(relaxed = true))
        assertTrue(adapter.supports(step(TmdbApiShapes.MOVIE_RECOMMENDATIONS)))
        assertTrue(adapter.supports(step(TmdbApiShapes.TV_RECOMMENDATIONS)))
        assertEquals(false, adapter.supports(step(TmdbApiShapes.MOVIE_CORE)))
        assertEquals(false, adapter.supports(step(TmdbApiShapes.TV_CORE)))
        assertEquals(false, adapter.supports(step(TmdbApiShapes.MOVIE_REVIEWS)))
        assertEquals(false, adapter.supports(step(TmdbApiShapes.MOVIE_VIDEOS)))
    }

    @Test
    fun `MOVIE_RECOMMENDATIONS produces RECOMMENDATIONS candidate from repository`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>()
        coEvery { repo.fetchMoreLikeThis("550", ContentType.MOVIE) } returns listOf(samplePreview)
        val adapter = TmdbRecommendationMetadataAdapter(repo)

        val result = adapter.execute(
            route = movieRoute(),
            step = step(TmdbApiShapes.MOVIE_RECOMMENDATIONS)
        )

        assertNotNull(result.candidate)
        val candidate = result.candidate!!
        assertEquals(MetadataPrimaryProvider.TMDB, candidate.provider)
        assertEquals(ResolverType.RECOMMENDATIONS, candidate.resolverType)
        val recs = candidate.fields[ResolvedField.RECOMMENDATIONS]?.value
        assertEquals(listOf(samplePreview), recs)
    }

    @Test
    fun `TV_RECOMMENDATIONS routes to SERIES content type`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>()
        coEvery { repo.fetchMoreLikeThis("1399", ContentType.SERIES) } returns listOf(samplePreview)
        val adapter = TmdbRecommendationMetadataAdapter(repo)

        val result = adapter.execute(
            route = tvRoute(),
            step = step(TmdbApiShapes.TV_RECOMMENDATIONS)
        )

        assertNotNull(result.candidate?.fields?.get(ResolvedField.RECOMMENDATIONS))
    }

    @Test
    fun `missing tmdb id short circuits to empty candidate`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>(relaxed = true)
        val adapter = TmdbRecommendationMetadataAdapter(repo)

        val result = adapter.execute(
            route = movieRoute(targetIds = emptyMap()),
            step = step(TmdbApiShapes.MOVIE_RECOMMENDATIONS)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
    }

    @Test
    fun `empty recommendations list yields empty candidate`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>()
        coEvery { repo.fetchMoreLikeThis("550", ContentType.MOVIE) } returns emptyList()
        val adapter = TmdbRecommendationMetadataAdapter(repo)

        val result = adapter.execute(
            route = movieRoute(),
            step = step(TmdbApiShapes.MOVIE_RECOMMENDATIONS)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
        assertNull(result.candidate?.fields?.get(ResolvedField.RECOMMENDATIONS))
    }

    @Test
    fun `unsupported shape yields empty candidate`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>(relaxed = true)
        val adapter = TmdbRecommendationMetadataAdapter(repo)

        val result = adapter.execute(
            route = movieRoute(),
            step = step(TmdbApiShapes.MOVIE_VIDEOS)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
    }

    private fun step(shape: String) = ProviderPlanStep(
        apiShapeId = shape,
        provider = MetadataPrimaryProvider.TMDB,
        role = ProviderPlanRole.SECONDARY,
        required = false
    )

    private fun movieRoute(
        targetIds: Map<MetadataPrimaryProvider, String> = mapOf(MetadataPrimaryProvider.TMDB to "550")
    ) = MetadataRoute(
        provider = MetadataPrimaryProvider.TMDB,
        parentId = "tmdb:550",
        mediaKind = MetadataMediaKind.MOVIE,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        sourceContext = MetadataSourceContext(),
        language = null,
        targetIds = targetIds,
        trace = emptyList()
    )

    private fun tvRoute(
        targetIds: Map<MetadataPrimaryProvider, String> = mapOf(MetadataPrimaryProvider.TMDB to "1399")
    ) = MetadataRoute(
        provider = MetadataPrimaryProvider.TMDB,
        parentId = "tmdb:1399",
        mediaKind = MetadataMediaKind.SERIES,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        sourceContext = MetadataSourceContext(),
        language = null,
        targetIds = targetIds,
        trace = emptyList()
    )
}
