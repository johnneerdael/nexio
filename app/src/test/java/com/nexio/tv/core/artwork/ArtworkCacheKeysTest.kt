package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ArtworkCacheKeysTest {
    @Test
    fun `decision key includes owner image language settings credential and policy`() {
        val key = ArtworkCacheKeys.decisionKey(
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            imageType = ArtworkType.POSTER,
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
            premiumEnabled = true,
            settingsHash = "settingsabc",
            credentialHash = "credentialdef",
            policyVersion = 1
        )

        assertEquals(
            "artwork-decision:poster:canonical:imdb:tt0137523:provider:TOP_POSTERS:premium:true:settings:settingsabc:credential:credentialdef:imageLang:en:policy:1",
            key.value
        )
    }

    @Test
    fun `asset key for remote url uses normalized hash instead of raw url`() {
        val key = ArtworkCacheKeys.assetKeyForRemoteUrl(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
            imageType = ArtworkType.POSTER,
            normalizedUrlHash = "hashabc",
            variant = "w500",
            policyVersion = 1
        )

        assertEquals(
            "artwork-asset:TMDB:poster:urlHash:hashabc:variant:w500:imageLang:en:policy:1",
            key.value
        )
        assertFalse(key.value.contains("https://"))
    }

    @Test
    fun `asset key for remote url includes stable variant when variant is null`() {
        val key = ArtworkCacheKeys.assetKeyForRemoteUrl(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
            imageType = ArtworkType.POSTER,
            normalizedUrlHash = "hashabc",
            variant = null,
            policyVersion = 1
        )

        assertEquals(
            "artwork-asset:TMDB:poster:urlHash:hashabc:variant:none:imageLang:en:policy:1",
            key.value
        )
        assertFalse(key.value.contains("imageLang:fr"))
    }

    @Test
    fun `normalized url hash strips known tracking params but preserves width path`() {
        val hashA = ArtworkCacheKeys.normalizedUrlHash(
            " HTTPS://Image.TMDB.org/t/p/w500/abc.jpg?utm_source=x&v=1 "
        )
        val hashB = ArtworkCacheKeys.normalizedUrlHash(
            "https://image.tmdb.org/t/p/w500/abc.jpg?v=1"
        )

        assertEquals(hashB, hashA)
    }

    @Test
    fun `normalized url preserves existing percent encoded path and query values`() {
        val rawUrl = "https://Example.com/a%2Fb.jpg?x=a%2Fb&utm_source=t"
        val normalizedUrl = "https://example.com/a%2Fb.jpg?x=a%2Fb"

        assertEquals(normalizedUrl, ArtworkCacheKeys.normalizeUrl(rawUrl))
        assertEquals(
            ArtworkCacheKeys.normalizedUrlHash(normalizedUrl),
            ArtworkCacheKeys.normalizedUrlHash(rawUrl)
        )
    }

    @Test
    fun `cache keys never contain raw remote url or credential material`() {
        val key = ArtworkCacheKeys.assetKeyForProviderTemplate(
            PersistedProviderTemplate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                imageType = ArtworkType.POSTER,
                idType = "imdb",
                mediaId = "tt0137523",
                providerPathHash = "pathhash",
                settingsHash = "settingshash",
                credentialHash = "credentialhash",
                policyVersion = 1
            )
        )

        assertFalse(key.value.contains("https://"))
        assertFalse(key.value.contains("api_key"))
        assertFalse(key.value.contains("raw"))
        assertEquals(
            "artwork-asset:RPDB:poster:imdb:tt0137523:settings:settingshash:credential:credentialhash:imageLang:en:policy:1",
            key.value
        )
    }

    @Test
    fun `provider template asset key always uses english image language`() {
        val key = ArtworkCacheKeys.assetKeyForProviderTemplate(
            PersistedProviderTemplate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                imageType = ArtworkType.POSTER,
                idType = "imdb",
                mediaId = "tt0137523",
                providerPathHash = "pathhash",
                settingsHash = "settingshash",
                credentialHash = "credentialhash",
                imageLanguage = "fr",
                policyVersion = 1
            )
        )

        assertFalse(key.value.contains("imageLang:fr"))
        assertEquals(
            "artwork-asset:RPDB:poster:imdb:tt0137523:settings:settingshash:credential:credentialhash:imageLang:en:policy:1",
            key.value
        )
    }
}
