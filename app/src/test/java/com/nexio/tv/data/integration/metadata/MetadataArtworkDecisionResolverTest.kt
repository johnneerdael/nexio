package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.artwork.ArtworkCacheKeys
import com.nexio.tv.core.artwork.ArtworkDecisionPolicy
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkProviderSettingsSource
import com.nexio.tv.core.artwork.ArtworkRemoteSourceStore
import com.nexio.tv.core.artwork.ArtworkRouter
import com.nexio.tv.core.artwork.ArtworkSource
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.InMemoryArtworkDecisionCache
import com.nexio.tv.core.artwork.SensitiveArtworkUrl
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.data.remote.api.TvdbArtworkRecord
import com.nexio.tv.domain.model.ArtworkProviderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataArtworkDecisionResolverTest {
    @Test
    fun `resolves tvdb primary artwork candidates to runtime asset fields and persisted decisions`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val remoteSourceStore = RecordingRemoteSourceStore()
        val resolver = resolver(cache, remoteSourceStore)
        val candidates = TvdbArtworkCandidateMapper().mapSeriesArtwork(
            seriesId = 81189,
            requestedLanguage = "eng",
            artworks = listOf(
                TvdbArtworkRecord(
                    id = 1,
                    type = TvdbArtworkTypes.POSTER,
                    image = "https://art.tvdb.com/poster.jpg",
                    language = "eng"
                ),
                TvdbArtworkRecord(
                    id = 2,
                    type = TvdbArtworkTypes.BACKDROP,
                    image = "https://art.tvdb.com/backdrop.jpg",
                    language = "eng"
                ),
                TvdbArtworkRecord(
                    id = 3,
                    type = TvdbArtworkTypes.CLEAR_LOGO,
                    image = "https://art.tvdb.com/logo.png",
                    language = "eng"
                )
            )
        )

        val fields = resolver.resolveFields(candidates)

        assertEquals(setOf(ResolvedField.POSTER, ResolvedField.BACKDROP, ResolvedField.LOGO), fields.keys)
        val logoRef = fields.getValue(ResolvedField.LOGO).value as ArtworkDisplayRef.RuntimeAsset
        assertEquals(FieldOwner.ARTWORK, fields.getValue(ResolvedField.LOGO).owner)
        assertEquals(SourceRole.ARTWORK, fields.getValue(ResolvedField.LOGO).sourceRole)
        assertEquals(ArtworkType.LOGO, logoRef.imageType)
        assertEquals(ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB), logoRef.selectedProvider)
        assertEquals(ArtworkSourceRole.PRIMARY, logoRef.sourceRole)
        assertEquals("TVDB", logoRef.trace.selectedProvider)
        assertEquals("PRIMARY", logoRef.trace.sourceRole)

        val logoDecision = cache.get(logoRef.decisionKey)
        assertNotNull(logoDecision)
        requireNotNull(logoDecision)
        assertEquals(ArtworkType.LOGO, logoDecision.imageType)
        assertEquals(ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB), logoDecision.selectedCandidate.provider)
        assertEquals(ArtworkSourceRole.PRIMARY, logoDecision.selectedCandidate.sourceRole)
        assertEquals(ArtworkDecisionPolicy.DECISION_TTL_MS, logoDecision.expiresAtMs - logoDecision.createdAtMs)
        assertEquals(ArtworkDecisionPolicy.DECISION_STALE_TTL_MS, logoDecision.staleUntilMs!! - logoDecision.createdAtMs)

        val logoSource = candidates.single { it.imageType == ArtworkType.LOGO }.source as ArtworkSource.RemoteUrl
        assertEquals(
            ArtworkCacheKeys.assetKeyForRemoteUrl(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB),
                imageType = ArtworkType.LOGO,
                normalizedUrlHash = logoSource.normalizedUrlHash,
                variant = null,
                policyVersion = 1
            ),
            logoRef.assetKey
        )
        assertEquals(
            SensitiveArtworkUrl.of("https://art.tvdb.com/logo.png"),
            remoteSourceStore.get(logoSource.normalizedUrlHash)
        )
    }

    @Test
    fun `selects lower priority type 2 artwork over extended image poster fallback`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val remoteSourceStore = RecordingRemoteSourceStore()
        val resolver = resolver(cache, remoteSourceStore)
        val candidates = TvdbArtworkCandidateMapper().mapSeriesArtwork(
            seriesId = 81189,
            requestedLanguage = "eng",
            posterFallbackImage = "https://art.tvdb.com/fallback.jpg",
            artworks = listOf(
                TvdbArtworkRecord(
                    id = 1,
                    type = TvdbArtworkTypes.POSTER,
                    image = "https://art.tvdb.com/type-2-poster.jpg",
                    language = "eng"
                )
            )
        )

        val posterRef = resolver.resolveFields(candidates)
            .getValue(ResolvedField.POSTER)
            .value as ArtworkDisplayRef.RuntimeAsset

        val decision = cache.get(posterRef.decisionKey)
        assertNotNull(decision)
        requireNotNull(decision)
        val type2Hash = ArtworkCacheKeys.normalizedUrlHash("https://art.tvdb.com/type-2-poster.jpg")
        assertEquals(type2Hash, decision.selectedCandidate.sourceHash)
        assertTrue(remoteSourceStore.get(type2Hash) != null)
    }

    private fun resolver(
        cache: ArtworkDecisionCache,
        remoteSourceStore: ArtworkRemoteSourceStore,
        settings: ArtworkProviderSettings = ArtworkProviderSettings()
    ): MetadataArtworkDecisionResolver =
        MetadataArtworkDecisionResolver(
            artworkRouter = ArtworkRouter(remoteSourceStore = remoteSourceStore),
            artworkDecisionCache = cache,
            remoteSourceStore = remoteSourceStore,
            settingsSource = FakeArtworkProviderSettingsSource(settings)
        )

    private class FakeArtworkProviderSettingsSource(
        settings: ArtworkProviderSettings
    ) : ArtworkProviderSettingsSource {
        override val settings: Flow<ArtworkProviderSettings> = MutableStateFlow(settings)
    }

    private class RecordingRemoteSourceStore : ArtworkRemoteSourceStore {
        private val sources = mutableMapOf<String, SensitiveArtworkUrl>()

        override fun put(
            normalizedUrlHash: String,
            source: SensitiveArtworkUrl
        ) {
            sources[normalizedUrlHash] = source
        }

        override fun get(normalizedUrlHash: String): SensitiveArtworkUrl? =
            sources[normalizedUrlHash]
    }
}
