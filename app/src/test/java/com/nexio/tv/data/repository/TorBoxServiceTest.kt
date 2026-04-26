package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.debrid.TorBoxIntegrationProvider
import com.nexio.tv.data.local.TorBoxSettings
import com.nexio.tv.data.local.TorBoxSettingsDataStore
import com.nexio.tv.data.remote.dto.debrid.TorBoxEnvelopeDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxUserDto
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

class TorBoxServiceTest {

    @Test
    fun `validate and save api key persists connected account from provider`() = runTest {
        val provider = mockk<TorBoxIntegrationProvider>()
        val settingsDataStore = mockk<TorBoxSettingsDataStore>()
        coJustRun { settingsDataStore.setApiKey("torbox-key") }
        coEvery { provider.fetchAccountInfo("torbox-key") } returns IntegrationCallResult.Success(
            TorBoxEnvelopeDto(
                success = true,
                data = TorBoxUserDto(
                    email = "user@torbox.local",
                    plan = "Pro"
                )
            )
        )

        val service = TorBoxService(provider, settingsDataStore)

        val result = service.validateAndSaveApiKey("  torbox-key  ")

        assertTrue(result.isSuccess)
        assertEquals("user@torbox.local", result.getOrThrow().email)
        assertEquals("Pro", result.getOrThrow().plan)
        assertTrue(service.accountState.value.isConnected)
        coVerify(exactly = 1) { settingsDataStore.setApiKey("torbox-key") }
    }

    @Test
    fun `validate and save api key preserves contact failure message from provider`() = runTest {
        val provider = mockk<TorBoxIntegrationProvider>()
        val settingsDataStore = mockk<TorBoxSettingsDataStore>(relaxed = true)
        coEvery { provider.fetchAccountInfo("torbox-key") } returns IntegrationCallResult.NetworkError(
            IllegalStateException("gateway down")
        )

        val service = TorBoxService(provider, settingsDataStore)

        val result = service.validateAndSaveApiKey("torbox-key")

        assertTrue(result.isFailure)
        assertEquals("gateway down", result.exceptionOrNull()?.message)
    }

    @Test
    fun `validate and save api key resets stale connected state on failed validation`() = runTest {
        val provider = mockk<TorBoxIntegrationProvider>()
        val settingsDataStore = mockk<TorBoxSettingsDataStore>(relaxed = true)
        coJustRun { settingsDataStore.setApiKey("working-key") }
        coEvery { provider.fetchAccountInfo("working-key") } returns IntegrationCallResult.Success(
            TorBoxEnvelopeDto(
                success = true,
                data = TorBoxUserDto(
                    email = "user@torbox.local",
                    plan = "Pro"
                )
            )
        )
        coEvery { provider.fetchAccountInfo("bad-key") } returns IntegrationCallResult.NetworkError(
            IllegalStateException("gateway down")
        )

        val service = TorBoxService(provider, settingsDataStore)

        val success = service.validateAndSaveApiKey("working-key")

        assertTrue(success.isSuccess)
        assertTrue(service.accountState.value.isConnected)

        val failure = service.validateAndSaveApiKey("bad-key")

        assertTrue(failure.isFailure)
        assertEquals("gateway down", failure.exceptionOrNull()?.message)
        assertFalse(service.accountState.value.isConnected)
        assertEquals("bad-key", service.accountState.value.apiKey)
        assertEquals("gateway down", service.accountState.value.errorMessage)
    }

    @Test
    fun `refresh account state marks stored api key disconnected when provider fetch fails`() = runTest {
        val provider = mockk<TorBoxIntegrationProvider>()
        val settingsDataStore = mockk<TorBoxSettingsDataStore>()
        every { settingsDataStore.settings } returns flowOf(TorBoxSettings(apiKey = "saved-key"))
        coEvery { provider.fetchAccountInfo("saved-key") } returns IntegrationCallResult.HttpError(
            401
        )

        val service = TorBoxService(provider, settingsDataStore)

        service.refreshAccountState()

        assertFalse(service.accountState.value.isConnected)
        assertEquals("saved-key", service.accountState.value.apiKey)
        assertEquals("TorBox authentication failed", service.accountState.value.errorMessage)
    }

    @Test
    fun `refresh account state marks stored api key disconnected when account success flag is not true`() = runTest {
        val provider = mockk<TorBoxIntegrationProvider>()
        val settingsDataStore = mockk<TorBoxSettingsDataStore>()
        every { settingsDataStore.settings } returns flowOf(TorBoxSettings(apiKey = "saved-key"))
        coEvery { provider.fetchAccountInfo("saved-key") } returns IntegrationCallResult.Success(
            TorBoxEnvelopeDto(
                success = false,
                error = "invalid token"
            )
        )

        val service = TorBoxService(provider, settingsDataStore)

        service.refreshAccountState()

        assertFalse(service.accountState.value.isConnected)
        assertEquals("saved-key", service.accountState.value.apiKey)
        assertEquals("TorBox authentication failed", service.accountState.value.errorMessage)
    }
}
