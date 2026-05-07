package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderSettingsSource
import com.nexio.tv.core.artwork.ArtworkRemoteSourceStore
import com.nexio.tv.core.artwork.ArtworkRouter
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.InMemoryArtworkDecisionCache
import com.nexio.tv.core.artwork.SensitiveArtworkUrl
import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataLocalizationFallbackRole
import com.nexio.tv.core.metadata.router.MetadataLocalizationPayloadTrace
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ProviderPlanRole
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.data.remote.api.TvdbArtworkRecord
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.data.remote.api.TvdbTranslationRecord
import com.nexio.tv.domain.model.ArtworkProviderSettings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TvdbMetadataProviderAdapterArtworkTest {
    @Test
    fun `series extended artwork type 23 survives as tvdb logo runtime asset`() = runTest {
        val provider = mockk<TvdbIntegrationProvider>()
        coEvery {
            provider.fetchSeriesExtendedCached(tvdbId = 81189, localizationPolicyVersion = any())
        } returns TvdbSeriesExtendedRecord(
            id = 81189,
            image = "https://art.tvdb.com/fallback-poster.jpg",
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
        coEvery {
            provider.fetchSeriesTranslationWithTrace(any(), any(), any(), any())
        } returns LocalizedPayloadFetch(
            value = TvdbTranslationRecord(name = "English Title", overview = "English overview"),
            trace = MetadataLocalizationPayloadTrace(
                provider = MetadataPrimaryProvider.TVDB,
                apiShapeId = TvdbApiShapes.SERIES_TRANSLATION,
                language = "eng",
                fallbackRole = MetadataLocalizationFallbackRole.LANGUAGE_FALLBACK,
                cacheKey = "test",
                cacheDecision = "HIT",
                executedNetwork = false,
                policyVersion = LocalizationPolicy.CURRENT_VERSION
            )
        )
        val cache = InMemoryArtworkDecisionCache()
        val remoteSourceStore = RecordingRemoteSourceStore()
        val adapter = TvdbMetadataProviderAdapter(
            integrationProvider = provider,
            traceEvents = TraceMetadataEvents(NoopRuntimeTraceSink) { null },
            artworkCandidateMapper = TvdbArtworkCandidateMapper(),
            artworkDecisionResolver = resolver(cache, remoteSourceStore)
        )

        val result = adapter.execute(
            route = MetadataRoute(
                provider = MetadataPrimaryProvider.TVDB,
                parentId = "tvdb:81189",
                mediaKind = MetadataMediaKind.SERIES,
                reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                sourceContext = MetadataSourceContext(),
                language = "eng",
                targetIds = mapOf(MetadataPrimaryProvider.TVDB to "tvdb:81189"),
                trace = emptyList()
            ),
            step = ProviderPlanStep(
                apiShapeId = TvdbApiShapes.SERIES_EXTENDED,
                provider = MetadataPrimaryProvider.TVDB,
                role = ProviderPlanRole.PRIMARY_CORE,
                required = true
            )
        )

        val fields = result.candidate?.fields.orEmpty()
        val logoRef = fields.getValue(ResolvedField.LOGO).value as ArtworkDisplayRef.RuntimeAsset
        assertEquals(SourceRole.ARTWORK, fields.getValue(ResolvedField.LOGO).sourceRole)
        assertEquals(ArtworkType.LOGO, logoRef.imageType)
        assertEquals(ArtworkSourceRole.PRIMARY, logoRef.sourceRole)
        assertEquals("TVDB", logoRef.trace.selectedProvider)
        assertEquals("PRIMARY", logoRef.trace.sourceRole)
        assertNotNull(cache.get(logoRef.decisionKey))

        assertEquals(ArtworkType.POSTER, (fields.getValue(ResolvedField.POSTER).value as ArtworkDisplayRef.RuntimeAsset).imageType)
        assertEquals(ArtworkType.BACKDROP, (fields.getValue(ResolvedField.BACKDROP).value as ArtworkDisplayRef.RuntimeAsset).imageType)
    }

    private fun resolver(
        cache: ArtworkDecisionCache,
        remoteSourceStore: ArtworkRemoteSourceStore
    ): MetadataArtworkDecisionResolver =
        MetadataArtworkDecisionResolver(
            artworkRouter = ArtworkRouter(remoteSourceStore = remoteSourceStore),
            artworkDecisionCache = cache,
            remoteSourceStore = remoteSourceStore,
            settingsSource = FakeArtworkProviderSettingsSource()
        )

    private class FakeArtworkProviderSettingsSource : ArtworkProviderSettingsSource {
        override val settings: Flow<ArtworkProviderSettings> =
            MutableStateFlow(ArtworkProviderSettings())
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
