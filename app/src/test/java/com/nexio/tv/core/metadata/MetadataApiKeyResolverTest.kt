package com.nexio.tv.core.metadata

import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.TvdbSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataApiKeyResolverTest {
    @Test
    fun `tmdb custom key overrides builtin key`() = runTest {
        val tmdbStore = mockk<TmdbSettingsDataStore>()
        val tvdbStore = mockk<TvdbSettingsDataStore>()
        every { tmdbStore.settings } returns flowOf(TmdbSettings(apiKey = " custom-tmdb "))
        every { tvdbStore.settings } returns flowOf(TvdbSettings())

        val resolver = MetadataApiKeyResolver(
            tmdbSettingsDataStore = tmdbStore,
            tvdbSettingsDataStore = tvdbStore,
            builtInTmdbKey = { "builtin-tmdb" },
            builtInTvdbKey = { "builtin-tvdb" }
        )

        val credential = resolver.tmdbCredential()

        assertEquals("custom-tmdb", credential.apiKey)
        assertEquals(MetadataCredentialSource.CUSTOM, credential.source)
    }

    @Test
    fun `tmdb falls back to builtin key when custom key is blank`() = runTest {
        val tmdbStore = mockk<TmdbSettingsDataStore>()
        val tvdbStore = mockk<TvdbSettingsDataStore>()
        every { tmdbStore.settings } returns flowOf(TmdbSettings(apiKey = " "))
        every { tvdbStore.settings } returns flowOf(TvdbSettings())

        val resolver = MetadataApiKeyResolver(
            tmdbSettingsDataStore = tmdbStore,
            tvdbSettingsDataStore = tvdbStore,
            builtInTmdbKey = { "builtin-tmdb" },
            builtInTvdbKey = { "builtin-tvdb" }
        )

        val credential = resolver.tmdbCredential()

        assertEquals("builtin-tmdb", credential.apiKey)
        assertEquals(MetadataCredentialSource.BUILT_IN, credential.source)
    }

    @Test
    fun `tvdb custom key overrides builtin key and keeps pin`() = runTest {
        val tmdbStore = mockk<TmdbSettingsDataStore>()
        val tvdbStore = mockk<TvdbSettingsDataStore>()
        every { tmdbStore.settings } returns flowOf(TmdbSettings())
        every {
            tvdbStore.settings
        } returns flowOf(TvdbSettings(apiKey = " custom-tvdb ", subscriberPin = " 1234 "))

        val resolver = MetadataApiKeyResolver(
            tmdbSettingsDataStore = tmdbStore,
            tvdbSettingsDataStore = tvdbStore,
            builtInTmdbKey = { "builtin-tmdb" },
            builtInTvdbKey = { "builtin-tvdb" }
        )

        val credential = resolver.tvdbCredential()

        assertEquals("custom-tvdb", credential.apiKey)
        assertEquals("1234", credential.pin)
        assertEquals(MetadataCredentialSource.CUSTOM, credential.source)
    }

    @Test
    fun `missing credentials are explicit`() = runTest {
        val tmdbStore = mockk<TmdbSettingsDataStore>()
        val tvdbStore = mockk<TvdbSettingsDataStore>()
        every { tmdbStore.settings } returns flowOf(TmdbSettings(apiKey = ""))
        every { tvdbStore.settings } returns flowOf(TvdbSettings(apiKey = ""))

        val resolver = MetadataApiKeyResolver(
            tmdbSettingsDataStore = tmdbStore,
            tvdbSettingsDataStore = tvdbStore,
            builtInTmdbKey = { "" },
            builtInTvdbKey = { "" }
        )

        assertTrue(resolver.tmdbCredential().missing)
        assertTrue(resolver.tvdbCredential().missing)
    }
}
