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
import com.nexio.tv.domain.model.MetaReview
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbReviewMetadataAdapterTest {

    private val sampleReview = MetaReview(
        id = "r1",
        author = "Alice",
        content = "Great movie."
    )

    @Test
    fun `supports declares MOVIE_REVIEWS and TV_REVIEWS only`() {
        val adapter = TmdbReviewMetadataAdapter(mockk(relaxed = true))
        assertTrue(adapter.supports(step(TmdbApiShapes.MOVIE_REVIEWS)))
        assertTrue(adapter.supports(step(TmdbApiShapes.TV_REVIEWS)))
        assertEquals(false, adapter.supports(step(TmdbApiShapes.MOVIE_CORE)))
        assertEquals(false, adapter.supports(step(TmdbApiShapes.TV_CORE)))
        assertEquals(false, adapter.supports(step(TmdbApiShapes.MOVIE_VIDEOS)))
    }

    @Test
    fun `MOVIE_REVIEWS produces REVIEWS candidate from repository`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>()
        coEvery { repo.fetchReviews("550", ContentType.MOVIE) } returns listOf(sampleReview)
        val adapter = TmdbReviewMetadataAdapter(repo)

        val result = adapter.execute(
            route = movieRoute(),
            step = step(TmdbApiShapes.MOVIE_REVIEWS)
        )

        assertNotNull(result.candidate)
        val candidate = result.candidate!!
        assertEquals(MetadataPrimaryProvider.TMDB, candidate.provider)
        assertEquals(ResolverType.REVIEWS, candidate.resolverType)
        val reviews = candidate.fields[ResolvedField.REVIEWS]?.value
        assertEquals(listOf(sampleReview), reviews)
    }

    @Test
    fun `TV_REVIEWS routes to SERIES content type`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>()
        coEvery { repo.fetchReviews("1399", ContentType.SERIES) } returns listOf(sampleReview)
        val adapter = TmdbReviewMetadataAdapter(repo)

        val result = adapter.execute(
            route = tvRoute(),
            step = step(TmdbApiShapes.TV_REVIEWS)
        )

        assertNotNull(result.candidate?.fields?.get(ResolvedField.REVIEWS))
    }

    @Test
    fun `missing tmdb id short circuits to empty candidate`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>(relaxed = true)
        val adapter = TmdbReviewMetadataAdapter(repo)

        val result = adapter.execute(
            route = movieRoute(targetIds = emptyMap()),
            step = step(TmdbApiShapes.MOVIE_REVIEWS)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
    }

    @Test
    fun `empty review list yields empty candidate`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>()
        coEvery { repo.fetchReviews("550", ContentType.MOVIE) } returns emptyList()
        val adapter = TmdbReviewMetadataAdapter(repo)

        val result = adapter.execute(
            route = movieRoute(),
            step = step(TmdbApiShapes.MOVIE_REVIEWS)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
        assertNull(result.candidate?.fields?.get(ResolvedField.REVIEWS))
    }

    @Test
    fun `unsupported shape yields empty candidate`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>(relaxed = true)
        val adapter = TmdbReviewMetadataAdapter(repo)

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
