package com.nexio.tv.core.tvdb

import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.domain.model.TvdbSettings
import com.nexio.tv.domain.model.TvdbValidationStatus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvdbProviderFallbackTest {

    @Test
    fun `disabled TVDB returns not configured fallback decision`() = runTest {
        val fallback = TvdbProviderFallback(
            settingsDataStore = settingsStore(
                TvdbSettings(
                    enabled = false,
                    apiKey = "tvdb-key",
                    validationStatus = TvdbValidationStatus.VALID
                )
            )
        )

        val result = fallback.decide()

        assertEquals(
            TvdbProviderDecision.UseFallback(TvdbProviderFallback.REASON_NOT_CONFIGURED),
            result
        )
    }

    @Test
    fun `valid configured TVDB returns use TVDB decision`() = runTest {
        val fallback = TvdbProviderFallback(
            settingsDataStore = settingsStore(
                TvdbSettings(
                    enabled = true,
                    apiKey = "tvdb-key",
                    validationStatus = TvdbValidationStatus.VALID
                )
            )
        )

        val result = fallback.decide()

        assertTrue(result is TvdbProviderDecision.UseTvdb)
    }

    @Test
    fun `invalid active TVDB returns invalid credentials fallback decision`() = runTest {
        val fallback = TvdbProviderFallback(
            settingsDataStore = settingsStore(
                TvdbSettings(
                    enabled = true,
                    apiKey = "tvdb-key",
                    validationStatus = TvdbValidationStatus.INVALID
                )
            )
        )

        val result = fallback.decide()

        assertEquals(
            TvdbProviderDecision.UseFallback(TvdbProviderFallback.REASON_INVALID_CREDENTIALS),
            result
        )
    }

    private fun settingsStore(settings: TvdbSettings): TvdbSettingsDataStore {
        return mockk(relaxed = true) {
            every { this@mockk.settings } returns flowOf(settings)
        }
    }
}
