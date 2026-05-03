package com.nexio.tv.core.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrationPolicyRegistryTest {
    @Test
    fun `default policies cover every external provider and stay serial by default`() {
        val registry = defaultIntegrationPolicyRegistry()

        val providers = setOf(
            IntegrationProvider.TRAKT,
            IntegrationProvider.SIMKL,
            IntegrationProvider.TMDB,
            IntegrationProvider.TVDB,
            IntegrationProvider.KITSU,
            IntegrationProvider.MDBLIST,
            IntegrationProvider.OMDB,
            IntegrationProvider.CUSTOM_IMDB,
            IntegrationProvider.THEINTRODB,
            IntegrationProvider.ANISKIP,
            IntegrationProvider.ANIMESKIP,
            IntegrationProvider.ARM,
            IntegrationProvider.RPDB,
            IntegrationProvider.TOP_POSTERS,
            IntegrationProvider.REAL_DEBRID,
            IntegrationProvider.PREMIUMIZE,
            IntegrationProvider.TORBOX,
            IntegrationProvider.EASY_DEBRID,
            IntegrationProvider.SHADOW_COLLECTOR,
            IntegrationProvider.GITHUB,
            IntegrationProvider.OPEN_SUBTITLES,
            IntegrationProvider.SUBTITLE_SOURCE_DOWNLOAD,
            IntegrationProvider.SUBTITLE_TRANSLATION
        )

        providers.forEach { provider ->
            val policy = registry.policyFor(provider)
            assertEquals(provider.name, 1, policy.maxConcurrentNetworkStarts)
        }

        assertTrue(registry.policyFor(IntegrationProvider.TRAKT).allowDuringPlayback)
        assertTrue(registry.policyFor(IntegrationProvider.SIMKL).allowDuringPlayback)
        assertFalse(registry.policyFor(IntegrationProvider.TMDB).allowDuringPlayback)
        assertFalse(registry.policyFor(IntegrationProvider.RPDB).allowDuringPlayback)
    }
}
