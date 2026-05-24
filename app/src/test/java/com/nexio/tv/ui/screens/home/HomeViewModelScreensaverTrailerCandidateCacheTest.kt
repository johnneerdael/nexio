package com.nexio.tv.ui.screens.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelScreensaverTrailerCandidateCacheTest {
    @Test
    fun `screensaver surface publish uses metadata candidate cache not broad trailer warmer`() {
        val source = File("src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt").readText()
        val publishBody = source.substringAfter("internal fun HomeViewModel.publishTmdbTrendingScreensaverSurface")
            .substringBefore("private fun com.nexio.tv.domain.model.ResolvedDisplayItem.hasScreensaverTrailerResolutionPath")

        assertTrue(publishBody.contains("screensaverTrailerCandidateCacheRepository.ensureFreshTmdbTrendingTrailerCandidates"))
        assertFalse(publishBody.contains("refreshScreensaverTrailerCachePipeline"))
        assertFalse(publishBody.contains("warmScreensaverTrailerCache"))
        assertFalse(publishBody.contains("metadataRouterFacade.fetchTrailer"))
    }
}
