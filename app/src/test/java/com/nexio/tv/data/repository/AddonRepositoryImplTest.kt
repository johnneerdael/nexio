package com.nexio.tv.data.repository

import android.content.Context
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.sync.AddonSyncService
import com.nexio.tv.core.sync.buildAddonRequestUrl
import com.nexio.tv.core.sync.normalizeAddonInstallUrl
import com.nexio.tv.data.integration.addon.AddonManifestIntegrationProvider
import com.nexio.tv.data.local.AddonPreferences
import com.nexio.tv.data.remote.dto.AddonManifestDto
import com.nexio.tv.domain.model.AuthState
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonRepositoryImplTest {
    @Test
    fun `fetchAddon delegates to manifest provider with canonical base URL and manifest URL`() = runTest {
        val baseUrl = "https://addon.example/manifest.json?x=1"
        val cleanBaseUrl = normalizeAddonInstallUrl(baseUrl)
        val manifestUrl = buildAddonRequestUrl(cleanBaseUrl, "manifest.json")
        val expectedManifest = AddonManifestDto(
            id = "community.addon",
            name = "Community Addon",
            version = "1.0.0",
            types = listOf("movie")
        )
        val provider = mockk<AddonManifestIntegrationProvider>()
        val preferences = mockk<AddonPreferences>(relaxed = true)
        val addonSyncService = mockk<AddonSyncService>(relaxed = true)
        val authManager = mockk<AuthManager>(relaxed = true)
        val context = mockContext()

        every { preferences.installedAddons } returns flowOf(emptyList())
        every { authManager.hasSyncSession } returns false
        coEvery {
            provider.getManifest(
                addonId = cleanBaseUrl,
                manifestUrl = manifestUrl
            )
        } returns NetworkResult.Success(expectedManifest)

        val repository = AddonRepositoryImpl(
            addonManifestIntegrationProvider = provider,
            preferences = preferences,
            addonSyncService = addonSyncService,
            authManager = authManager,
            context = context
        )
        val result = repository.fetchAddon(baseUrl)
        assertTrue(result is NetworkResult.Success<*>)

        coVerify(exactly = 1) {
            provider.getManifest(
                addonId = cleanBaseUrl,
                manifestUrl = manifestUrl
            )
        }
    }

    @Test
    fun `successful fetch caches manifest and makes it visible through getCachedInstalledAddons on fresh repository`() = runTest {
        val baseUrl = "https://addon.example/manifest.json?x=1"
        val cleanBaseUrl = normalizeAddonInstallUrl(baseUrl)
        val manifestUrl = buildAddonRequestUrl(cleanBaseUrl, "manifest.json")
        val addonDto = AddonManifestDto(
            id = "community.addon",
            name = "Community Addon",
            version = "1.0.0",
            types = listOf("movie")
        )
        val provider = mockk<AddonManifestIntegrationProvider>()
        val addonSyncService = mockk<AddonSyncService>(relaxed = true)
        val authManager = mockk<AuthManager>(relaxed = true)
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)
        val installedConfig = AddonPreferences.AddonInstallConfig(
            url = cleanBaseUrl,
            parserPreset = AddonParserPreset.STREMTHRU
        )
        val preferences = mockk<AddonPreferences>(relaxed = true)
        every { preferences.installedAddons } returns flowOf(listOf(installedConfig))
        every { authManager.hasSyncSession } returns false
        coEvery {
            provider.getManifest(
                addonId = cleanBaseUrl,
                manifestUrl = manifestUrl
            )
        } returns NetworkResult.Success(addonDto)

        val firstRepository = AddonRepositoryImpl(
            addonManifestIntegrationProvider = provider,
            preferences = preferences,
            addonSyncService = addonSyncService,
            authManager = authManager,
            context = context
        )
        val fetched = firstRepository.fetchAddon(baseUrl)
        val cachedAfterFetch = firstRepository.getCachedInstalledAddons()
        assertTrue(fetched is NetworkResult.Success<*>)
        assertEquals(1, cachedAfterFetch.size)
        assertEquals(cleanBaseUrl, cachedAfterFetch.first().baseUrl)
        assertEquals(AddonParserPreset.STREMTHRU, cachedAfterFetch.first().parserPreset)

        val secondRepository = AddonRepositoryImpl(
            addonManifestIntegrationProvider = provider,
            preferences = preferences,
            addonSyncService = addonSyncService,
            authManager = authManager,
            context = context
        )
        val cachedFromNewInstance = secondRepository.getCachedInstalledAddons()
        assertEquals(1, cachedFromNewInstance.size)
        assertEquals(cleanBaseUrl, cachedFromNewInstance.first().baseUrl)
        assertEquals(AddonParserPreset.STREMTHRU, cachedFromNewInstance.first().parserPreset)

        coVerify(exactly = 1) {
            provider.getManifest(
                addonId = cleanBaseUrl,
                manifestUrl = manifestUrl
            )
        }
    }

    @Test
    fun `getCachedInstalledAddons layers installed isAnime onto cached manifest`() = runTest {
        val baseUrl = "https://addon.example/manifest.json?x=1"
        val cleanBaseUrl = normalizeAddonInstallUrl(baseUrl)
        val manifestUrl = buildAddonRequestUrl(cleanBaseUrl, "manifest.json")
        val addonDto = AddonManifestDto(
            id = "community.addon",
            name = "Community Addon",
            version = "1.0.0",
            types = listOf("series")
        )
        val provider = mockk<AddonManifestIntegrationProvider>()
        val addonSyncService = mockk<AddonSyncService>(relaxed = true)
        val authManager = mockk<AuthManager>(relaxed = true)
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)
        val installedConfig = AddonPreferences.AddonInstallConfig(
            url = cleanBaseUrl,
            parserPreset = AddonParserPreset.STREMTHRU,
            isAnime = true
        )
        val preferences = mockk<AddonPreferences>(relaxed = true)
        every { preferences.installedAddons } returns flowOf(listOf(installedConfig))
        every { authManager.hasSyncSession } returns false
        coEvery {
            provider.getManifest(
                addonId = cleanBaseUrl,
                manifestUrl = manifestUrl
            )
        } returns NetworkResult.Success(addonDto)

        val repository = AddonRepositoryImpl(
            addonManifestIntegrationProvider = provider,
            preferences = preferences,
            addonSyncService = addonSyncService,
            authManager = authManager,
            context = context
        )

        repository.fetchAddon(baseUrl)
        val cached = repository.getCachedInstalledAddons()

        assertEquals(1, cached.size)
        assertEquals(AddonParserPreset.STREMTHRU, cached.first().parserPreset)
        assertTrue(cached.first().isAnime)
    }

    @Test
    fun `updateAddonIsAnime updates cached manifest and persists it to disk`() = runTest {
        val baseUrl = "https://addon.example/manifest.json?x=1"
        val cleanBaseUrl = normalizeAddonInstallUrl(baseUrl)
        val manifestUrl = buildAddonRequestUrl(cleanBaseUrl, "manifest.json")
        val addonDto = AddonManifestDto(
            id = "community.addon",
            name = "Community Addon",
            version = "1.0.0",
            types = listOf("series")
        )
        val provider = mockk<AddonManifestIntegrationProvider>()
        val addonSyncService = mockk<AddonSyncService>(relaxed = true)
        val authManager = mockk<AuthManager>(relaxed = true)
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)
        val preferences = mockk<AddonPreferences>(relaxed = true)

        every { preferences.installedAddons } returns flowOf(
            listOf(AddonPreferences.AddonInstallConfig(url = cleanBaseUrl))
        )
        every { authManager.authState } returns MutableStateFlow(AuthState.SignedOut)
        every { authManager.currentSessionUserId } returns null
        coEvery {
            provider.getManifest(
                addonId = cleanBaseUrl,
                manifestUrl = manifestUrl
            )
        } returns NetworkResult.Success(addonDto)

        val repository = AddonRepositoryImpl(
            addonManifestIntegrationProvider = provider,
            preferences = preferences,
            addonSyncService = addonSyncService,
            authManager = authManager,
            context = context
        )

        repository.fetchAddon(baseUrl)
        repository.updateAddonIsAnime(baseUrl, isAnime = true)

        val persisted = prefs.getString("manifests", null)
        assertTrue(persisted?.contains("\"isAnime\":true") == true)
        coVerify(exactly = 1) { preferences.updateAddonIsAnime(cleanBaseUrl, true) }
    }

    private fun mockContext(): Context {
        return mockContext(InMemorySharedPreferences())
    }

    private fun mockContext(preferences: InMemorySharedPreferences): Context {
        val context = mockk<Context>()
        every { context.getSharedPreferences("addon_manifest_cache", Context.MODE_PRIVATE) } returns preferences
        return context
    }
}
