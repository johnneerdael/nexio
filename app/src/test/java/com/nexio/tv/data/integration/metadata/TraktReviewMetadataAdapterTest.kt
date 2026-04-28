package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.integration.TraktApiShapes
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.PaginationCursor
import com.nexio.tv.core.metadata.router.ProviderPlanRole
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolverType
import com.nexio.tv.data.repository.ReviewsRepository
import com.nexio.tv.data.repository.TraktReviewPage
import com.nexio.tv.domain.model.MetaReview
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktReviewMetadataAdapterTest {

    private val sampleReview = MetaReview(
        id = "trakt-1",
        author = "Bob",
        content = "Worth a watch."
    )

    @Test
    fun `supports declares MOVIE_COMMENTS and SHOW_COMMENTS only`() {
        val adapter = TraktReviewMetadataAdapter(mockk(relaxed = true))
        assertTrue(adapter.supports(step(TraktApiShapes.MOVIE_COMMENTS)))
        assertTrue(adapter.supports(step(TraktApiShapes.SHOW_COMMENTS)))
        assertEquals(false, adapter.supports(step(TraktApiShapes.TRENDING_MOVIES)))
        assertEquals(false, adapter.supports(step(TraktApiShapes.POPULAR_SHOWS)))
        assertEquals(false, adapter.supports(step(TmdbApiShapes.MOVIE_REVIEWS)))
    }

    @Test
    fun `MOVIE_COMMENTS produces REVIEWS candidate from repository`() = runTest {
        val repo = mockk<ReviewsRepository>()
        coEvery {
            repo.fetchTraktReviewPage(pathId = "tt0133093", isShow = false, page = 1, limit = 20)
        } returns TraktReviewPage(reviews = listOf(sampleReview), hasMore = false)
        val adapter = TraktReviewMetadataAdapter(repo)

        val result = adapter.execute(
            route = movieRoute(),
            step = step(TraktApiShapes.MOVIE_COMMENTS)
        )

        assertNotNull(result.candidate)
        val candidate = result.candidate!!
        assertEquals(MetadataPrimaryProvider.TRAKT, candidate.provider)
        assertEquals(ResolverType.REVIEWS, candidate.resolverType)
        val reviews = candidate.fields[ResolvedField.REVIEWS]?.value
        assertEquals(listOf(sampleReview), reviews)
    }

    @Test
    fun `SHOW_COMMENTS routes with isShow=true`() = runTest {
        val repo = mockk<ReviewsRepository>()
        coEvery {
            repo.fetchTraktReviewPage(pathId = "tt0944947", isShow = true, page = 1, limit = 20)
        } returns TraktReviewPage(reviews = listOf(sampleReview), hasMore = false)
        val adapter = TraktReviewMetadataAdapter(repo)

        val result = adapter.execute(
            route = showRoute(),
            step = step(TraktApiShapes.SHOW_COMMENTS)
        )

        assertNotNull(result.candidate?.fields?.get(ResolvedField.REVIEWS))
    }

    @Test
    fun `imdb prefixed target id is stripped before dispatch`() = runTest {
        val repo = mockk<ReviewsRepository>()
        coEvery {
            repo.fetchTraktReviewPage(pathId = "tt0133093", isShow = false, page = 1, limit = 20)
        } returns TraktReviewPage(reviews = listOf(sampleReview), hasMore = false)
        val adapter = TraktReviewMetadataAdapter(repo)

        val result = adapter.execute(
            route = movieRoute(
                targetIds = mapOf(MetadataPrimaryProvider.IMDB to "imdb:tt0133093")
            ),
            step = step(TraktApiShapes.MOVIE_COMMENTS)
        )

        assertEquals(listOf(sampleReview), result.candidate?.fields?.get(ResolvedField.REVIEWS)?.value)
    }

    @Test
    fun `missing imdb id short circuits to empty candidate`() = runTest {
        val repo = mockk<ReviewsRepository>(relaxed = true)
        val adapter = TraktReviewMetadataAdapter(repo)

        val result = adapter.execute(
            route = movieRoute(targetIds = emptyMap()),
            step = step(TraktApiShapes.MOVIE_COMMENTS)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
    }

    @Test
    fun `empty review list yields empty candidate`() = runTest {
        val repo = mockk<ReviewsRepository>()
        coEvery {
            repo.fetchTraktReviewPage(pathId = "tt0133093", isShow = false, page = 1, limit = 20)
        } returns TraktReviewPage(reviews = emptyList(), hasMore = false)
        val adapter = TraktReviewMetadataAdapter(repo)

        val result = adapter.execute(
            route = movieRoute(),
            step = step(TraktApiShapes.MOVIE_COMMENTS)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
        assertNull(result.candidate?.fields?.get(ResolvedField.REVIEWS))
    }

    @Test
    fun `null page response yields empty candidate`() = runTest {
        val repo = mockk<ReviewsRepository>()
        coEvery {
            repo.fetchTraktReviewPage(pathId = "tt0133093", isShow = false, page = 1, limit = 20)
        } returns null
        val adapter = TraktReviewMetadataAdapter(repo)

        val result = adapter.execute(
            route = movieRoute(),
            step = step(TraktApiShapes.MOVIE_COMMENTS)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
    }

    @Test
    fun `pagination cursor on route threads page and limit through to repository`() = runTest {
        val repo = mockk<ReviewsRepository>()
        coEvery {
            repo.fetchTraktReviewPage(pathId = "tt0133093", isShow = false, page = 3, limit = 5)
        } returns TraktReviewPage(reviews = listOf(sampleReview), hasMore = true)
        val adapter = TraktReviewMetadataAdapter(repo)

        val result = adapter.execute(
            route = movieRoute().copy(pagination = PaginationCursor(page = 3, limit = 5)),
            step = step(TraktApiShapes.MOVIE_COMMENTS)
        )

        assertEquals(listOf(sampleReview), result.candidate?.fields?.get(ResolvedField.REVIEWS)?.value)
    }

    @Test
    fun `missing pagination cursor falls back to page=1 limit=20`() = runTest {
        val repo = mockk<ReviewsRepository>()
        coEvery {
            repo.fetchTraktReviewPage(pathId = "tt0133093", isShow = false, page = 1, limit = 20)
        } returns TraktReviewPage(reviews = listOf(sampleReview), hasMore = false)
        val adapter = TraktReviewMetadataAdapter(repo)

        // No `.copy(pagination = ...)` — exercises the default branch.
        val result = adapter.execute(
            route = movieRoute(),
            step = step(TraktApiShapes.MOVIE_COMMENTS)
        )

        assertEquals(listOf(sampleReview), result.candidate?.fields?.get(ResolvedField.REVIEWS)?.value)
    }

    @Test
    fun `unsupported shape yields empty candidate`() = runTest {
        val repo = mockk<ReviewsRepository>(relaxed = true)
        val adapter = TraktReviewMetadataAdapter(repo)

        val result = adapter.execute(
            route = movieRoute(),
            step = step(TraktApiShapes.TRENDING_MOVIES)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
    }

    private fun step(shape: String) = ProviderPlanStep(
        apiShapeId = shape,
        provider = MetadataPrimaryProvider.TRAKT,
        role = ProviderPlanRole.SECONDARY,
        required = false
    )

    private fun movieRoute(
        targetIds: Map<MetadataPrimaryProvider, String> = mapOf(MetadataPrimaryProvider.IMDB to "tt0133093")
    ) = MetadataRoute(
        provider = MetadataPrimaryProvider.TRAKT,
        parentId = "imdb:tt0133093",
        mediaKind = MetadataMediaKind.MOVIE,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        sourceContext = MetadataSourceContext(),
        language = null,
        targetIds = targetIds,
        trace = emptyList()
    )

    private fun showRoute(
        targetIds: Map<MetadataPrimaryProvider, String> = mapOf(MetadataPrimaryProvider.IMDB to "tt0944947")
    ) = MetadataRoute(
        provider = MetadataPrimaryProvider.TRAKT,
        parentId = "imdb:tt0944947",
        mediaKind = MetadataMediaKind.SERIES,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        sourceContext = MetadataSourceContext(),
        language = null,
        targetIds = targetIds,
        trace = emptyList()
    )
}
