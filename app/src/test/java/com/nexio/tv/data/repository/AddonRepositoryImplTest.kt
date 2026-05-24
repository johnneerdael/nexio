package com.nexio.tv.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.sync.AddonSyncService
import com.nexio.tv.core.sync.buildAddonRequestUrl
import com.nexio.tv.core.sync.normalizeAddonInstallUrl
import com.nexio.tv.data.integration.addon.AddonManifestIntegrationProvider
import com.nexio.tv.data.local.AddonPreferences
import com.nexio.tv.data.local.FileBackedJsonObjectStore
import com.nexio.tv.data.mapper.toDomain
import com.nexio.tv.data.remote.dto.AddonManifestDto
import com.nexio.tv.domain.model.AuthState
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AddonRepositoryImplTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val gson = Gson()

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
        assertFalse(prefs.contains("manifests"))

        FileBackedJsonObjectStore.resetSharedStateForTest(manifestCacheFile(context))

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

        FileBackedJsonObjectStore.resetSharedStateForTest(manifestCacheFile(context))
        val persisted = FileBackedJsonObjectStore(manifestCacheFile(context)).get(cleanBaseUrl)
        assertTrue(persisted?.get("isAnime")?.asBoolean == true)
        assertFalse(prefs.contains("manifests"))
        coVerify(exactly = 1) { preferences.updateAddonIsAnime(cleanBaseUrl, true) }
    }

    @Test
    fun `init migrates legacy manifest prefs to file store and clears legacy key`() = runTest {
        val baseUrl = "https://addon.example/manifest.json?x=1"
        val cleanBaseUrl = normalizeAddonInstallUrl(baseUrl)
        val legacyAddon = addonDto(name = "Legacy Addon").toDomain(cleanBaseUrl)
            .copy(parserPreset = AddonParserPreset.TORRENTIO, isAnime = true)
        val provider = mockk<AddonManifestIntegrationProvider>(relaxed = true)
        val addonSyncService = mockk<AddonSyncService>(relaxed = true)
        val authManager = mockk<AuthManager>(relaxed = true)
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)
        prefs.edit()
            .putString("manifests", gson.toJson(mapOf(cleanBaseUrl to legacyAddon)))
            .commit()
        val preferences = mockk<AddonPreferences>(relaxed = true)
        every { preferences.installedAddons } returns flowOf(
            listOf(AddonPreferences.AddonInstallConfig(url = cleanBaseUrl))
        )

        val repository = AddonRepositoryImpl(
            addonManifestIntegrationProvider = provider,
            preferences = preferences,
            addonSyncService = addonSyncService,
            authManager = authManager,
            context = context
        )

        val cached = repository.getCachedInstalledAddons()
        FileBackedJsonObjectStore.resetSharedStateForTest(manifestCacheFile(context))
        val persisted = FileBackedJsonObjectStore(manifestCacheFile(context)).get(cleanBaseUrl)
        assertEquals(1, cached.size)
        assertEquals("Legacy Addon", cached.first().name)
        assertEquals("Legacy Addon", persisted?.get("name")?.asString)
        assertFalse(prefs.contains("manifests"))
    }

    @Test
    fun `legacy manifest migration does not overwrite newer file entries`() = runTest {
        val baseUrl = "https://addon.example/manifest.json?x=1"
        val cleanBaseUrl = normalizeAddonInstallUrl(baseUrl)
        val fileAddon = addonDto(name = "File Addon").toDomain(cleanBaseUrl)
        val legacyAddon = addonDto(name = "Legacy Addon").toDomain(cleanBaseUrl)
        val provider = mockk<AddonManifestIntegrationProvider>(relaxed = true)
        val addonSyncService = mockk<AddonSyncService>(relaxed = true)
        val authManager = mockk<AuthManager>(relaxed = true)
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)
        FileBackedJsonObjectStore(manifestCacheFile(context))
            .put(cleanBaseUrl, gson.toJsonTree(fileAddon).asJsonObject)
        prefs.edit()
            .putString("manifests", gson.toJson(mapOf(cleanBaseUrl to legacyAddon)))
            .commit()
        val preferences = mockk<AddonPreferences>(relaxed = true)
        every { preferences.installedAddons } returns flowOf(
            listOf(AddonPreferences.AddonInstallConfig(url = cleanBaseUrl))
        )

        val repository = AddonRepositoryImpl(
            addonManifestIntegrationProvider = provider,
            preferences = preferences,
            addonSyncService = addonSyncService,
            authManager = authManager,
            context = context
        )

        val cached = repository.getCachedInstalledAddons()
        FileBackedJsonObjectStore.resetSharedStateForTest(manifestCacheFile(context))
        val persisted = FileBackedJsonObjectStore(manifestCacheFile(context)).get(cleanBaseUrl)
        assertEquals(1, cached.size)
        assertEquals("File Addon", cached.first().name)
        assertEquals("File Addon", persisted?.get("name")?.asString)
        assertFalse(prefs.contains("manifests"))
    }

    @Test
    fun `file cache load drops malformed entry and keeps valid entries`() = runTest {
        val badBaseUrl = normalizeAddonInstallUrl("https://bad.example/manifest.json")
        val goodBaseUrl = normalizeAddonInstallUrl("https://good.example/manifest.json")
        val goodAddon = addonDto(name = "Good Addon").toDomain(goodBaseUrl)
        val provider = mockk<AddonManifestIntegrationProvider>(relaxed = true)
        val addonSyncService = mockk<AddonSyncService>(relaxed = true)
        val authManager = mockk<AuthManager>(relaxed = true)
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)
        FileBackedJsonObjectStore(manifestCacheFile(context)).putAll(
            mapOf(
                badBaseUrl to JsonObject().apply { addProperty("id", "bad.addon") },
                goodBaseUrl to gson.toJsonTree(goodAddon).asJsonObject
            )
        )
        FileBackedJsonObjectStore.resetSharedStateForTest(manifestCacheFile(context))
        val preferences = mockk<AddonPreferences>(relaxed = true)
        every { preferences.installedAddons } returns flowOf(
            listOf(
                AddonPreferences.AddonInstallConfig(url = badBaseUrl),
                AddonPreferences.AddonInstallConfig(url = goodBaseUrl)
            )
        )

        val repository = AddonRepositoryImpl(
            addonManifestIntegrationProvider = provider,
            preferences = preferences,
            addonSyncService = addonSyncService,
            authManager = authManager,
            context = context
        )

        val cached = repository.getCachedInstalledAddons()
        assertEquals(1, cached.size)
        assertEquals(goodBaseUrl, cached.first().baseUrl)
        assertEquals("Good Addon", cached.first().name)
    }

    @Test
    fun `legacy migration drops malformed entries and keeps valid entries`() = runTest {
        val badBaseUrl = normalizeAddonInstallUrl("https://bad.example/manifest.json")
        val goodBaseUrl = normalizeAddonInstallUrl("https://good.example/manifest.json")
        val goodAddon = addonDto(name = "Good Legacy Addon").toDomain(goodBaseUrl)
        val legacyJson = JsonObject().apply {
            addProperty(badBaseUrl, "bad")
            add(goodBaseUrl, gson.toJsonTree(goodAddon))
        }
        val provider = mockk<AddonManifestIntegrationProvider>(relaxed = true)
        val addonSyncService = mockk<AddonSyncService>(relaxed = true)
        val authManager = mockk<AuthManager>(relaxed = true)
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)
        prefs.edit().putString("manifests", gson.toJson(legacyJson)).commit()
        val preferences = mockk<AddonPreferences>(relaxed = true)
        every { preferences.installedAddons } returns flowOf(
            listOf(
                AddonPreferences.AddonInstallConfig(url = badBaseUrl),
                AddonPreferences.AddonInstallConfig(url = goodBaseUrl)
            )
        )

        val repository = AddonRepositoryImpl(
            addonManifestIntegrationProvider = provider,
            preferences = preferences,
            addonSyncService = addonSyncService,
            authManager = authManager,
            context = context
        )

        val cached = repository.getCachedInstalledAddons()
        FileBackedJsonObjectStore.resetSharedStateForTest(manifestCacheFile(context))
        val persisted = FileBackedJsonObjectStore(manifestCacheFile(context))
        assertEquals(1, cached.size)
        assertEquals(goodBaseUrl, cached.first().baseUrl)
        assertEquals("Good Legacy Addon", cached.first().name)
        assertEquals(setOf(goodBaseUrl), persisted.keys())
        assertFalse(prefs.contains("manifests"))
    }

    @Test
    fun `removeAddon removes manifest from file cache`() = runTest {
        val baseUrl = "https://addon.example/manifest.json?x=1"
        val cleanBaseUrl = normalizeAddonInstallUrl(baseUrl)
        val manifestUrl = buildAddonRequestUrl(cleanBaseUrl, "manifest.json")
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
        } returns NetworkResult.Success(addonDto())

        val repository = AddonRepositoryImpl(
            addonManifestIntegrationProvider = provider,
            preferences = preferences,
            addonSyncService = addonSyncService,
            authManager = authManager,
            context = context
        )

        repository.fetchAddon(baseUrl)
        repository.removeAddon(baseUrl)

        FileBackedJsonObjectStore.resetSharedStateForTest(manifestCacheFile(context))
        assertEquals(emptySet<String>(), FileBackedJsonObjectStore(manifestCacheFile(context)).keys())
        coVerify(exactly = 1) { preferences.removeAddon(cleanBaseUrl) }
    }

    @Test
    fun `getInstalledAddons emits cached manifests without refreshing them`() = runTest {
        val baseUrl = "https://cached.example/config/manifest.json"
        val cleanBaseUrl = normalizeAddonInstallUrl(baseUrl)
        val cachedAddon = addonDto(name = "Cached Addon").toDomain(cleanBaseUrl)
            .copy(parserPreset = AddonParserPreset.NEXIO_TORII, isAnime = true)
        val provider = mockk<AddonManifestIntegrationProvider>(relaxed = true)
        val addonSyncService = mockk<AddonSyncService>(relaxed = true)
        val authManager = mockk<AuthManager>(relaxed = true)
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)
        FileBackedJsonObjectStore(manifestCacheFile(context))
            .put(cleanBaseUrl, gson.toJsonTree(cachedAddon).asJsonObject)
        FileBackedJsonObjectStore.resetSharedStateForTest(manifestCacheFile(context))
        val preferences = mockk<AddonPreferences>(relaxed = true)
        every { preferences.installedAddons } returns flowOf(
            listOf(
                AddonPreferences.AddonInstallConfig(
                    url = cleanBaseUrl,
                    parserPreset = AddonParserPreset.NEXIO_TORII,
                    isAnime = true
                )
            )
        )

        val repository = AddonRepositoryImpl(
            addonManifestIntegrationProvider = provider,
            preferences = preferences,
            addonSyncService = addonSyncService,
            authManager = authManager,
            context = context
        )

        val installed = repository.getInstalledAddons().first()

        assertEquals(1, installed.size)
        assertEquals(cleanBaseUrl, installed.single().baseUrl)
        assertEquals("Cached Addon", installed.single().name)
        assertEquals(AddonParserPreset.NEXIO_TORII, installed.single().parserPreset)
        assertTrue(installed.single().isAnime)
        coVerify(exactly = 0) { provider.getManifest(any(), any()) }
    }

    @Test
    fun `getInstalledAddons fetches only manifests missing from cache`() = runTest {
        val cachedUrl = normalizeAddonInstallUrl("https://cached.example/config/manifest.json")
        val missingUrl = normalizeAddonInstallUrl("https://missing.example/config/manifest.json")
        val missingManifestUrl = buildAddonRequestUrl(missingUrl, "manifest.json")
        val cachedAddon = addonDto(name = "Cached Addon").toDomain(cachedUrl)
            .copy(parserPreset = AddonParserPreset.NEXIO_TORII)
        val missingDto = addonDto(name = "Fetched Missing Addon")
        val provider = mockk<AddonManifestIntegrationProvider>()
        val addonSyncService = mockk<AddonSyncService>(relaxed = true)
        val authManager = mockk<AuthManager>(relaxed = true)
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)
        FileBackedJsonObjectStore(manifestCacheFile(context))
            .put(cachedUrl, gson.toJsonTree(cachedAddon).asJsonObject)
        FileBackedJsonObjectStore.resetSharedStateForTest(manifestCacheFile(context))
        val preferences = mockk<AddonPreferences>(relaxed = true)
        every { preferences.installedAddons } returns flowOf(
            listOf(
                AddonPreferences.AddonInstallConfig(
                    url = cachedUrl,
                    parserPreset = AddonParserPreset.NEXIO_TORII
                ),
                AddonPreferences.AddonInstallConfig(
                    url = missingUrl,
                    parserPreset = AddonParserPreset.NEXIO_NAGARE,
                    isAnime = true
                )
            )
        )
        coEvery {
            provider.getManifest(addonId = missingUrl, manifestUrl = missingManifestUrl)
        } returns NetworkResult.Success(missingDto)

        val repository = AddonRepositoryImpl(
            addonManifestIntegrationProvider = provider,
            preferences = preferences,
            addonSyncService = addonSyncService,
            authManager = authManager,
            context = context
        )

        val emissions = repository.getInstalledAddons().take(2).toList()

        assertEquals(
            listOf(listOf("Cached Addon"), listOf("Cached Addon", "Fetched Missing Addon")),
            emissions.map { list -> list.map { it.name } }
        )
        assertEquals(AddonParserPreset.NEXIO_NAGARE, emissions.last().single { it.baseUrl == missingUrl }.parserPreset)
        assertTrue(emissions.last().single { it.baseUrl == missingUrl }.isAnime)
        coVerify(exactly = 0) {
            provider.getManifest(addonId = cachedUrl, manifestUrl = buildAddonRequestUrl(cachedUrl, "manifest.json"))
        }
        coVerify(exactly = 1) {
            provider.getManifest(addonId = missingUrl, manifestUrl = missingManifestUrl)
        }
    }

    @Test
    fun `getInstalledAddons repeated collectors do not refresh cached manifests`() = runTest {
        val baseUrl = normalizeAddonInstallUrl("https://cached.example/config/manifest.json")
        val cachedAddon = addonDto(name = "Cached Addon").toDomain(baseUrl)
        val provider = mockk<AddonManifestIntegrationProvider>(relaxed = true)
        val addonSyncService = mockk<AddonSyncService>(relaxed = true)
        val authManager = mockk<AuthManager>(relaxed = true)
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)
        FileBackedJsonObjectStore(manifestCacheFile(context))
            .put(baseUrl, gson.toJsonTree(cachedAddon).asJsonObject)
        FileBackedJsonObjectStore.resetSharedStateForTest(manifestCacheFile(context))
        val preferences = mockk<AddonPreferences>(relaxed = true)
        every { preferences.installedAddons } returns flowOf(
            listOf(AddonPreferences.AddonInstallConfig(url = baseUrl))
        )

        val repository = AddonRepositoryImpl(
            addonManifestIntegrationProvider = provider,
            preferences = preferences,
            addonSyncService = addonSyncService,
            authManager = authManager,
            context = context
        )

        val first = repository.getInstalledAddons().first()
        val second = repository.getInstalledAddons().first()

        assertEquals(listOf("Cached Addon"), first.map { it.name })
        assertEquals(listOf("Cached Addon"), second.map { it.name })
        coVerify(exactly = 0) { provider.getManifest(any(), any()) }
    }

    private fun mockContext(): Context {
        return mockContext(InMemorySharedPreferences())
    }

    private fun mockContext(preferences: InMemorySharedPreferences): Context {
        val filesDir = tmp.newFolder("files")
        val context = mockk<Context>()
        every { context.getSharedPreferences("addon_manifest_cache", Context.MODE_PRIVATE) } returns preferences
        every { context.filesDir } returns filesDir
        return context
    }

    private fun manifestCacheFile(context: Context): File =
        File(context.filesDir, "addon-manifest-cache-v1/entries.json")

    private fun addonDto(name: String = "Community Addon"): AddonManifestDto =
        AddonManifestDto(
            id = "community.addon",
            name = name,
            version = "1.0.0",
            types = listOf("movie")
        )
}
