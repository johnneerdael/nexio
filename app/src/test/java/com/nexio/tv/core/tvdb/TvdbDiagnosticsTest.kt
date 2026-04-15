package com.nexio.tv.core.tvdb

import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.domain.model.TvdbSettings
import com.nexio.tv.domain.model.TvdbValidationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TvdbDiagnosticsTest {

    @Test
    fun `fallback diagnostic stores sanitized reason without credential material`() = runTest {
        val dataStore = mockk<TvdbSettingsDataStore>(relaxed = true) {
            every { settings } returns flowOf(TvdbSettings())
        }
        val fallback = TvdbProviderFallback(settingsDataStore = dataStore)

        coEvery {
            dataStore.saveValidationFailure(any(), any())
        } returns Unit

        fallback.recordFallback("401 for key tvdb-key with pin subscriber-pin token tvdb-token")

        coVerify(exactly = 1) {
            dataStore.saveValidationFailure(
                status = TvdbValidationStatus.FALLBACK_ACTIVE,
                lastFailure = TvdbProviderFallback.REASON_AUTH_UNAVAILABLE
            )
        }
    }
}
