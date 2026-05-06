package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.ArtworkProviderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosterRatingsArtworkCredentialResolverTest {
    @Test
    fun `rpdb key resolves only when credential hash matches current settings`() = runTest {
        val settings = MutableStateFlow(ArtworkProviderSettings(rpdbApiKey = "rpdb-key"))
        val resolver = PosterRatingsArtworkCredentialResolver(FakeArtworkProviderSettingsSource(settings))

        val result = resolver.apiKeyFor(
            provider = IntegrationProvider.RPDB,
            credentialHash = ArtworkCredentialHash.hashCredential("rpdb-key")
        )

        assertEquals("rpdb-key", result)
    }

    @Test
    fun `top posters key resolves only when credential hash matches current settings`() = runTest {
        val settings = MutableStateFlow(ArtworkProviderSettings(topPostersApiKey = "top-key"))
        val resolver = PosterRatingsArtworkCredentialResolver(FakeArtworkProviderSettingsSource(settings))

        val result = resolver.apiKeyFor(
            provider = IntegrationProvider.TOP_POSTERS,
            credentialHash = ArtworkCredentialHash.hashCredential("top-key")
        )

        assertEquals("top-key", result)
    }

    @Test
    fun `resolver returns null when current key hash differs from decision hash`() = runTest {
        val settings = MutableStateFlow(ArtworkProviderSettings(rpdbApiKey = "new-key"))
        val resolver = PosterRatingsArtworkCredentialResolver(FakeArtworkProviderSettingsSource(settings))

        val result = resolver.apiKeyFor(
            provider = IntegrationProvider.RPDB,
            credentialHash = ArtworkCredentialHash.hashCredential("old-key")
        )

        assertNull(result)
    }

    private class FakeArtworkProviderSettingsSource(
        override val settings: Flow<ArtworkProviderSettings>
    ) : ArtworkProviderSettingsSource
}
