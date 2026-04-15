package com.nexio.tv.core.tvdb

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvdbProviderFallbackTest {

    @Test
    fun `inactive TVDB leaves TMDB settings and resolves through non TVDB fallback`() = runTest {
        val tvdbProvider = mockk<TvdbSeriesProvider>()
        val tmdbProvider = mockk<TmdbSeriesFallbackProvider>()
        val diagnostics = mockk<TvdbDiagnosticsRecorder>(relaxed = true)
        val router = TvdbProviderFallback(
            tvdbProvider = tvdbProvider,
            tmdbProvider = tmdbProvider,
            diagnosticsRecorder = diagnostics
        )

        coEvery {
            tmdbProvider.resolveSeries("tt0944947")
        } returns TvdbProviderResult.Fallback("tmdb-series", tmdbSettingsChanged = false)

        val result = router.resolveSeries(
            remoteId = "tt0944947",
            settings = TvdbProviderSettings(
                enabled = false,
                validationStatus = TvdbValidationStatus.NOT_CONFIGURED
            )
        )

        assertEquals("tmdb-series", result.seriesId)
        assertTrue(result.tmdbSettingsChanged.not())
        coVerify(exactly = 0) { tvdbProvider.resolveSeries(any()) }
        coVerify(exactly = 1) { tmdbProvider.resolveSeries("tt0944947") }
        coVerify(exactly = 0) { diagnostics.recordFallback(any()) }
    }

    @Test
    fun `active unusable TVDB records sanitized fallback diagnostic before TMDB fallback`() = runTest {
        val tvdbProvider = mockk<TvdbSeriesProvider>()
        val tmdbProvider = mockk<TmdbSeriesFallbackProvider>()
        val diagnostics = mockk<TvdbDiagnosticsRecorder>(relaxed = true)
        val router = TvdbProviderFallback(
            tvdbProvider = tvdbProvider,
            tmdbProvider = tmdbProvider,
            diagnosticsRecorder = diagnostics
        )

        coEvery { tmdbProvider.resolveSeries("tt0944947") } returns
            TvdbProviderResult.Fallback("tmdb-series", tmdbSettingsChanged = false)

        val result = router.resolveSeries(
            remoteId = "tt0944947",
            settings = TvdbProviderSettings(
                enabled = true,
                validationStatus = TvdbValidationStatus.INVALID,
                lastFailure = "401 for key tvdb-key with pin subscriber-pin token tvdb-token"
            )
        )

        assertEquals("tmdb-series", result.seriesId)
        coVerify(exactly = 1) {
            diagnostics.recordFallback(
                match {
                    it.reason == TvdbFallbackReason.INVALID_CREDENTIALS &&
                        it.sanitizedMessage.contains("tvdb-key").not() &&
                        it.sanitizedMessage.contains("subscriber-pin").not() &&
                        it.sanitizedMessage.contains("tvdb-token").not()
                }
            )
        }
    }
}
