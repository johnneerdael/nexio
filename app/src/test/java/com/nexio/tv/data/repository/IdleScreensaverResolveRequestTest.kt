package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.core.metadata.router.ResolverSchedule
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IdleScreensaverResolveRequestTest {

    private val samplePreview = MetaPreview(
        id = "tvdb:355567",
        type = ContentType.SERIES,
        name = "Sample",
        poster = "preview-poster",
        posterShape = PosterShape.POSTER,
        background = "preview-bg",
        logo = "preview-logo",
        description = "A drama series",
        releaseInfo = "2019",
        imdbRating = 8.4f,
        genres = listOf("Drama"),
        runtime = "60m"
    )

    @Test
    fun `fetchIdleScreensaverMeta routes via resolveRequest with preview toHomeDisplayMetadata as addonMetadata`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val captured = slot<MetadataRequest>()
        coEvery { facade.resolveRequest(capture(captured)) } returns successResult()

        fetchIdleScreensaverMeta(samplePreview, facade)

        coVerify(exactly = 1) { facade.resolveRequest(any()) }
        val ctx = captured.captured.sourceContext
        assertNotNull(ctx.addonMetadata)
        assertEquals(samplePreview.name, ctx.addonMetadata!!.title)
        assertEquals(samplePreview.poster, ctx.addonMetadata!!.poster)
        assertEquals(samplePreview.background, ctx.addonMetadata!!.backdrop)
        assertEquals(samplePreview.logo, ctx.addonMetadata!!.logo)
        assertEquals(listOf("Drama"), ctx.addonMetadata!!.genres)
    }

    @Test
    fun `fetchIdleScreensaverMeta uses MetadataDepth DETAIL_CORE for canonical hydration`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val captured = slot<MetadataRequest>()
        coEvery { facade.resolveRequest(capture(captured)) } returns successResult()

        fetchIdleScreensaverMeta(samplePreview, facade)

        assertEquals(MetadataDepth.DETAIL_CORE, captured.captured.depth)
    }

    @Test
    fun `fetchIdleScreensaverMeta returns null when route is null`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        coEvery { facade.resolveRequest(any()) } returns successResult().copy(route = null)

        val result = fetchIdleScreensaverMeta(samplePreview, facade)

        assertNull(result)
    }

    @Test
    fun `fetchIdleScreensaverMeta returns Meta built from displayMetadata`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        coEvery { facade.resolveRequest(any()) } returns successResult()

        val result = fetchIdleScreensaverMeta(samplePreview, facade)

        assertNotNull(result)
        assertEquals("Sample", result!!.name)
        assertEquals("tvdb-poster", result.poster)
        assertEquals("tvdb-backdrop", result.background)
    }

    @Test
    fun `fetchIdleScreensaverMeta canonical title from displayMetadata overrides input preview title`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val canonicalResult = successResult().copy(
            resolvedDocument = successResult().resolvedDocument.copy(title = "Canonical Title"),
            displayMetadata = successResult().displayMetadata.copy(title = "Canonical Title")
        )
        coEvery { facade.resolveRequest(any()) } returns canonicalResult

        val result = fetchIdleScreensaverMeta(samplePreview, facade)

        assertNotNull(result)
        // samplePreview.name = "Sample"; canonical displayMetadata.title = "Canonical Title"
        assertEquals("Canonical Title", result!!.name)
    }

    @Test
    fun `fetchIdleScreensaverMeta returns null when resolveRequest throws non-cancellation exception`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        coEvery { facade.resolveRequest(any()) } throws RuntimeException("network error")

        val result = fetchIdleScreensaverMeta(samplePreview, facade)

        assertNull(result)
    }

    private fun successResult() = MetadataResolutionResult(
        route = MetadataRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = "tvdb:355567",
            mediaKind = MetadataMediaKind.SERIES,
            reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
            sourceContext = MetadataSourceContext(),
            targetIds = mapOf(MetadataPrimaryProvider.TVDB to "tvdb:355567"),
            trace = emptyList()
        ),
        plan = null,
        resolverSchedule = ResolverSchedule(MetadataDepth.PREVIEW, emptyList(), emptyList()),
        resolvedDocument = ResolvedMetadataDocument(
            canonicalId = "tvdb:355567",
            title = "Sample",
            overview = "A drama series",
            poster = "tvdb-poster",
            backdrop = "tvdb-backdrop",
            logo = "tvdb-logo",
            rating = 8.4f,
            runtimeMinutes = 60,
            fieldOwners = emptyMap(),
            ignoredOverwrites = emptyList()
        ),
        displayMetadata = HomeDisplayMetadata(
            title = "Sample",
            poster = "tvdb-poster",
            backdrop = "tvdb-backdrop",
            logo = "tvdb-logo",
            genres = listOf("Drama"),
            releaseInfo = "2019",
            runtime = "60m",
            imdbRating = 8.4f
        ),
        trace = emptyList()
    )
}
