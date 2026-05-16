package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class ArtworkCandidatePersistenceTest {
    @Test
    fun `premium remote-url candidate registers url with remote source store`() {
        // Regression: Fanart candidates are emitted as RemoteUrl + PREMIUM. If
        // toPersistedCandidate skips registration for PREMIUM sourceRole, the
        // renderer cannot resolve the assetKey -> URL at paint time and falls
        // back to the rejected primary (TVDB) — observed on-device on 2026-05-16.
        // FileBackedArtworkRemoteSourceStore.put() already filters
        // credential-bearing URLs via isPremiumProviderRawUrl(), so registering
        // PREMIUM RemoteUrl is safe.
        val url = "https://assets.fanart.tv/fanart/the-boys-2019-5bcc305c1e41f.jpg"
        val sensitive = SensitiveArtworkUrl.of(url)
        val hash = ArtworkCacheKeys.normalizedUrlHash(url)
        val candidate = ArtworkCandidate(
            ownerKey = ArtworkOwnerKey.CanonicalContent("tvdb:355567"),
            canonicalContentId = "tvdb:355567",
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
            imageType = ArtworkType.BACKDROP,
            sourceRole = ArtworkSourceRole.PREMIUM,
            source = ArtworkSource.RemoteUrl.of(rawUrl = sensitive, normalizedUrlHash = hash),
            priority = 15,
            requiresRuntimeFetch = true,
            imageLanguage = "en"
        )
        val store = mockk<ArtworkRemoteSourceStore>(relaxed = true)

        candidate.toPersistedCandidate(policyVersion = 1, remoteSourceStore = store)

        verify(exactly = 1) { store.put(hash, sensitive) }
    }
}
