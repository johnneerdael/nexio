package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.debrid.PremiumizeIntegrationProvider
import com.nexio.tv.data.local.PremiumizeSettings
import com.nexio.tv.data.local.PremiumizeSettingsDataStore
import com.nexio.tv.data.remote.dto.debrid.PremiumizeAccountInfoDto
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumizeServiceTest {

    @Test
    fun `validate and save api key persists connected account from provider`() = runTest {
        val provider = mockk<PremiumizeIntegrationProvider>()
        val settingsDataStore = mockk<PremiumizeSettingsDataStore>()
        coJustRun { settingsDataStore.setApiKey("premium-key") }
        coEvery { provider.fetchAccountInfo("premium-key") } returns IntegrationCallResult.Success(
            PremiumizeAccountInfoDto(
                status = "success",
                customerId = 42,
                premiumUntil = 1_717_171_717L
            )
        )

        val service = PremiumizeService(provider, settingsDataStore)

        val result = service.validateAndSaveApiKey("  premium-key  ")

        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrThrow().customerId)
        assertTrue(service.accountState.value.isConnected)
        coVerify(exactly = 1) { settingsDataStore.setApiKey("premium-key") }
    }

    @Test
    fun `validate and save api key preserves contact failure message from provider`() = runTest {
        val provider = mockk<PremiumizeIntegrationProvider>()
        val settingsDataStore = mockk<PremiumizeSettingsDataStore>(relaxed = true)
        coEvery { provider.fetchAccountInfo("premium-key") } returns IntegrationCallResult.NetworkError(
            IllegalStateException("gateway down")
        )

        val service = PremiumizeService(provider, settingsDataStore)

        val result = service.validateAndSaveApiKey("premium-key")

        assertTrue(result.isFailure)
        assertEquals("gateway down", result.exceptionOrNull()?.message)
    }

    @Test
    fun `refresh account state marks stored api key disconnected when provider fetch fails`() = runTest {
        val provider = mockk<PremiumizeIntegrationProvider>()
        val settingsDataStore = mockk<PremiumizeSettingsDataStore>()
        every { settingsDataStore.settings } returns flowOf(PremiumizeSettings(apiKey = "saved-key"))
        coEvery { provider.fetchAccountInfo("saved-key") } returns IntegrationCallResult.HttpError(401)

        val service = PremiumizeService(provider, settingsDataStore)

        service.refreshAccountState()

        assertFalse(service.accountState.value.isConnected)
        assertEquals("saved-key", service.accountState.value.apiKey)
        assertEquals("Premiumize authentication failed", service.accountState.value.errorMessage)
    }
}
