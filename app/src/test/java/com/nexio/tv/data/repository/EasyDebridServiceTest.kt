package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.debrid.EasyDebridIntegrationProvider
import com.nexio.tv.data.local.EasyDebridSettings
import com.nexio.tv.data.local.EasyDebridSettingsDataStore
import com.nexio.tv.data.remote.dto.debrid.EasyDebridUserDto
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyDebridServiceTest {

    @Test
    fun `validate and save api key persists connected account from provider`() = runTest {
        val provider = mockk<EasyDebridIntegrationProvider>()
        val settingsDataStore = mockk<EasyDebridSettingsDataStore>()
        coJustRun { settingsDataStore.setApiKey("easy-key") }
        coEvery { provider.fetchAccountInfo("easy-key") } returns IntegrationCallResult.Success(
            EasyDebridUserDto(
                id = "user-123",
                paidUntil = "2026-12-31"
            )
        )

        val service = EasyDebridService(provider, settingsDataStore)

        val result = service.validateAndSaveApiKey("  easy-key  ")

        assertTrue(result.isSuccess)
        assertEquals("user-123", result.getOrThrow().userId)
        assertEquals("2026-12-31", result.getOrThrow().paidUntil)
        assertTrue(service.accountState.value.isConnected)
        coVerify(exactly = 1) { settingsDataStore.setApiKey("easy-key") }
    }

    @Test
    fun `validate and save api key preserves contact failure message from provider`() = runTest {
        val provider = mockk<EasyDebridIntegrationProvider>()
        val settingsDataStore = mockk<EasyDebridSettingsDataStore>(relaxed = true)
        coEvery { provider.fetchAccountInfo("easy-key") } returns IntegrationCallResult.NetworkError(
            IllegalStateException("gateway down")
        )

        val service = EasyDebridService(provider, settingsDataStore)

        val result = service.validateAndSaveApiKey("easy-key")

        assertTrue(result.isFailure)
        assertEquals("gateway down", result.exceptionOrNull()?.message)
    }

    @Test
    fun `validate and save api key resets stale connected state on provider failure`() = runTest {
        val provider = mockk<EasyDebridIntegrationProvider>()
        val settingsDataStore = mockk<EasyDebridSettingsDataStore>()
        coJustRun { settingsDataStore.setApiKey(any()) }
        coEvery { provider.fetchAccountInfo("good-key") } returns IntegrationCallResult.Success(
            EasyDebridUserDto(
                id = "user-123",
                paidUntil = "2026-12-31"
            )
        )
        coEvery { provider.fetchAccountInfo("bad-key") } returns IntegrationCallResult.NetworkError(
            IllegalStateException("gateway down")
        )

        val service = EasyDebridService(provider, settingsDataStore)

        service.validateAndSaveApiKey("good-key")
        val result = service.validateAndSaveApiKey("bad-key")

        assertTrue(result.isFailure)
        assertEquals("bad-key", service.accountState.value.apiKey)
        assertFalse(service.accountState.value.isConnected)
        assertEquals("gateway down", service.accountState.value.errorMessage)
        assertNull(service.accountState.value.userId)
    }

    @Test
    fun `refresh account state marks stored api key disconnected when provider fetch fails`() = runTest {
        val provider = mockk<EasyDebridIntegrationProvider>()
        val settingsDataStore = mockk<EasyDebridSettingsDataStore>()
        every { settingsDataStore.settings } returns flowOf(EasyDebridSettings(apiKey = "saved-key"))
        coEvery { provider.fetchAccountInfo("saved-key") } returns IntegrationCallResult.HttpError(401)

        val service = EasyDebridService(provider, settingsDataStore)

        service.refreshAccountState()

        assertFalse(service.accountState.value.isConnected)
        assertEquals("saved-key", service.accountState.value.apiKey)
        assertEquals("EasyDebrid authentication failed", service.accountState.value.errorMessage)
    }
}
