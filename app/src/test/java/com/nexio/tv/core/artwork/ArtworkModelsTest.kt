package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ArtworkModelsTest {
    @Test
    fun `sensitive artwork url does not leak through toString`() {
        val source = ArtworkSource.RemoteUrl.of(
            rawUrl = SensitiveArtworkUrl.of("https://image.tmdb.org/t/p/w500/secret.jpg?api_key=abc"),
            normalizedUrlHash = "hash123"
        )

        val rendered = source.toString()

        assertFalse(rendered.contains("secret.jpg"))
        assertFalse(rendered.contains("api_key=abc"))
        assertEquals(
            "RemoteUrl(redactedUrlForTrace=https://image.tmdb.org/t/p/w500/<redacted>, normalizedUrlHash=hash123)",
            rendered
        )
    }

    @Test
    fun `remote urls with identical values are equal`() {
        val first = ArtworkSource.RemoteUrl.of(
            rawUrl = SensitiveArtworkUrl.of("https://image.tmdb.org/t/p/w500/secret.jpg?api_key=abc"),
            normalizedUrlHash = "hash123"
        )
        val second = ArtworkSource.RemoteUrl.of(
            rawUrl = SensitiveArtworkUrl.of("https://image.tmdb.org/t/p/w500/secret.jpg?api_key=abc"),
            normalizedUrlHash = "hash123"
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `decision key rejects uri breaking characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtworkDecisionKey("bad/key")
        }
    }

    @Test
    fun `asset key rejects uri breaking characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtworkAssetKey("bad?key")
        }
    }

    @Test
    fun `runtime provider identity reuses IntegrationProvider`() {
        val provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS)

        assertEquals(IntegrationProvider.TOP_POSTERS, provider.providerId)
        assertEquals("TOP_POSTERS", provider.key)
    }
}
