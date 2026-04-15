package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.domain.model.ContentType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelTvdbProviderRoutingTest {

    @Test
    fun `continue watching tvdb success does not call tmdb`() = runTest {
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val request = TvMetadataRequest(
            contentId = "tt0944947",
            fallbackContentId = "tt0944947:2:5",
            contentType = ContentType.SERIES
        )
        coEvery { tvMetadataRouter.fetchEnrichment(request) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = TvMetadataEnrichment(
                seriesTvdbId = 121361,
                localizedTitle = "Game of Thrones"
            )
        )

        val decision = tvMetadataRouter.fetchEnrichment(request)

        assertEquals(TvProvider.TVDB, decision.provider)
        assertEquals("Game of Thrones", decision.value?.localizedTitle)
        assertTrue(
            HomeViewModel::class.java.declaredConstructors.any { constructor ->
                constructor.parameterTypes.contains(TvMetadataRouter::class.java)
            }
        )
        coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), any(), any()) }
    }
}
