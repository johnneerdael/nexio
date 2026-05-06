package com.nexio.tv.data.integration.posters

import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.InMemoryArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.PosterApiShapes
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouteTrace
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ProviderPlanRole
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.PosterRatingsSettings
import com.nexio.tv.domain.model.toArtworkProviderSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumPosterMetadataProviderAdapterStableIdTest {

    @Test
    fun `top posters candidate ref uses provider native target id instead of route parent id`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val adapter = TopPostersMetadataProviderAdapter(
            posterResolver = resolver(
                PosterRatingsSettings(
                    topPostersEnabled = true,
                    topPostersApiKey = "top-key"
                ),
                cache
            )
        )
        val route = route(
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.MOVIE,
            parentId = "catalog-row-item-42",
            targetIds = mapOf(
                MetadataPrimaryProvider.TMDB to "tmdb:550",
                MetadataPrimaryProvider.IMDB to "tt0137523"
            )
        )

        val result = adapter.execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.TOP_POSTERS, PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE)
        )

        val value = result.candidate?.fields?.get(ResolvedField.POSTER)?.value as? String
        assertInternalArtworkRef(value)
        val template = cache.get(decisionKeyFromRef(value!!))?.selectedCandidate?.providerTemplate
        assertEquals(ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS), template?.provider)
        assertEquals("tmdb", template?.idType)
        assertEquals("movie-550", template?.mediaId)
        assertFalse(template?.mediaId.orEmpty().contains(route.parentId))
    }

    @Test
    fun `top posters decision persists non premium source poster as primary fallback candidate`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val adapter = TopPostersMetadataProviderAdapter(
            posterResolver = resolver(
                PosterRatingsSettings(
                    topPostersEnabled = true,
                    topPostersApiKey = "top-key"
                ),
                cache
            )
        )
        val route = route(
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.MOVIE,
            parentId = "catalog-row-item-42",
            targetIds = mapOf(
                MetadataPrimaryProvider.TMDB to "tmdb:550",
                MetadataPrimaryProvider.IMDB to "tt0137523"
            ),
            sourceMetadata = HomeDisplayMetadata(
                poster = "https://image.tmdb.org/t/p/w500/fallback.jpg"
            )
        )

        val result = adapter.execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.TOP_POSTERS, PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE)
        )

        val value = result.candidate?.fields?.get(ResolvedField.POSTER)?.value as? String
        assertInternalArtworkRef(value)
        val decision = cache.get(decisionKeyFromRef(value!!))
        val rejected = decision!!.rejectedCandidates.single { it.sourceRole == ArtworkSourceRole.PRIMARY }
        assertEquals(ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB), rejected.provider)
        assertEquals("premium_artwork_provider_precedence", rejected.reason)
        assertNotNull(rejected.sourceHash)
        assertTrue(rejected.redactedSourceForTrace!!.contains("image.tmdb.org"))
        assertNull(rejected.providerTemplate)
    }

    @Test
    fun `rpdb candidate ref uses stable tmdb target id instead of route parent id`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val adapter = RpdbMetadataProviderAdapter(
            posterResolver = resolver(
                PosterRatingsSettings(
                    rpdbEnabled = true,
                    rpdbApiKey = "rpdb-key"
                ),
                cache
            )
        )
        val route = route(
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.MOVIE,
            parentId = "catalog-row-item-99",
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:550")
        )

        val result = adapter.execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.RPDB, PosterApiShapes.RPDB_POSTER_TEMPLATE)
        )

        val value = result.candidate?.fields?.get(ResolvedField.POSTER)?.value as? String
        assertInternalArtworkRef(value)
        val template = cache.get(decisionKeyFromRef(value!!))?.selectedCandidate?.providerTemplate
        assertEquals(ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB), template?.provider)
        assertEquals("tmdb", template?.idType)
        assertEquals("movie-550", template?.mediaId)
        assertFalse(template?.mediaId.orEmpty().contains(route.parentId))
    }

    @Test
    fun `rpdb decision persists non premium source poster as primary fallback candidate`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val adapter = RpdbMetadataProviderAdapter(
            posterResolver = resolver(
                PosterRatingsSettings(
                    rpdbEnabled = true,
                    rpdbApiKey = "rpdb-key"
                ),
                cache
            )
        )
        val route = route(
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.MOVIE,
            parentId = "catalog-row-item-99",
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:550"),
            sourceMetadata = HomeDisplayMetadata(
                poster = "https://image.tmdb.org/t/p/w500/fallback.jpg"
            )
        )

        val result = adapter.execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.RPDB, PosterApiShapes.RPDB_POSTER_TEMPLATE)
        )

        val value = result.candidate?.fields?.get(ResolvedField.POSTER)?.value as? String
        assertInternalArtworkRef(value)
        val decision = cache.get(decisionKeyFromRef(value!!))
        val rejected = decision!!.rejectedCandidates.single { it.sourceRole == ArtworkSourceRole.PRIMARY }
        assertEquals(ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB), rejected.provider)
        assertEquals("premium_artwork_provider_precedence", rejected.reason)
        assertNotNull(rejected.sourceHash)
        assertTrue(rejected.redactedSourceForTrace!!.contains("image.tmdb.org"))
        assertNull(rejected.providerTemplate)
    }

    @Test
    fun `rpdb adapter does not persist raw premium provider url as fallback candidate`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val adapter = RpdbMetadataProviderAdapter(
            posterResolver = resolver(
                PosterRatingsSettings(
                    rpdbEnabled = true,
                    rpdbApiKey = "rpdb-key"
                ),
                cache
            )
        )
        val route = route(
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.MOVIE,
            parentId = "catalog-row-item-99",
            targetIds = mapOf(
                MetadataPrimaryProvider.TMDB to "tmdb:550",
                MetadataPrimaryProvider.IMDB to "tt0137523"
            ),
            sourceMetadata = HomeDisplayMetadata(
                poster = "https://api.ratingposterdb.com/rpdb-key/imdb/poster-default/tt0137523.jpg"
            )
        )

        val result = adapter.execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.RPDB, PosterApiShapes.RPDB_POSTER_TEMPLATE)
        )

        val value = result.candidate?.fields?.get(ResolvedField.POSTER)?.value as? String
        assertInternalArtworkRef(value)
        val decision = cache.get(decisionKeyFromRef(value!!))
        assertTrue(decision!!.rejectedCandidates.none { it.sourceRole == ArtworkSourceRole.PRIMARY })
    }

    @Test
    fun `kitsu only anime route produces top posters ref but not rpdb ref`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val route = route(
            provider = MetadataPrimaryProvider.KITSU,
            mediaKind = MetadataMediaKind.ANIME,
            parentId = "kitsu:7442",
            targetIds = mapOf(MetadataPrimaryProvider.KITSU to "kitsu:7442")
        )

        val rpdbResult = RpdbMetadataProviderAdapter(
            posterResolver = resolver(
                PosterRatingsSettings(
                    rpdbEnabled = true,
                    rpdbApiKey = "rpdb-key"
                )
            )
        ).execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.RPDB, PosterApiShapes.RPDB_POSTER_TEMPLATE)
        )
        val topPostersResult = TopPostersMetadataProviderAdapter(
            posterResolver = resolver(
                PosterRatingsSettings(
                    topPostersEnabled = true,
                    topPostersApiKey = "top-key"
                ),
                cache
            )
        ).execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.TOP_POSTERS, PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE)
        )

        val value = topPostersResult.candidate?.fields?.get(ResolvedField.POSTER)?.value as? String
        assertNull(rpdbResult.candidate?.fields?.get(ResolvedField.POSTER))
        assertInternalArtworkRef(value)
        val template = cache.get(decisionKeyFromRef(value!!))?.selectedCandidate?.providerTemplate
        assertEquals(ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS), template?.provider)
        assertEquals("kitsu", template?.idType)
        assertEquals("7442", template?.mediaId)
    }

    @Test
    fun `top posters adapter emits internal ref for kitsu only anime when selected`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val route = route(
            provider = MetadataPrimaryProvider.KITSU,
            mediaKind = MetadataMediaKind.ANIME,
            parentId = "kitsu:7442",
            targetIds = mapOf(MetadataPrimaryProvider.KITSU to "kitsu:7442")
        )

        val result = TopPostersMetadataProviderAdapter(
            posterResolver = resolver(
                PosterRatingsSettings(
                    topPostersEnabled = true,
                    topPostersApiKey = "top-key",
                ),
                cache
            )
        ).execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.TOP_POSTERS, PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE)
        )

        val value = result.candidate?.fields?.get(ResolvedField.POSTER)?.value as? String
        assertInternalArtworkRef(value)
        val decision = cache.get(decisionKeyFromRef(value!!))
        assertEquals(ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS), decision?.selectedCandidate?.provider)
        assertEquals("kitsu", decision?.selectedCandidate?.providerTemplate?.idType)
        assertEquals("7442", decision?.selectedCandidate?.providerTemplate?.mediaId)
    }

    @Test
    fun `rpdb adapter emits no poster for kitsu only anime`() = runTest {
        val route = route(
            provider = MetadataPrimaryProvider.KITSU,
            mediaKind = MetadataMediaKind.ANIME,
            parentId = "kitsu:7442",
            targetIds = mapOf(MetadataPrimaryProvider.KITSU to "kitsu:7442")
        )

        val result = RpdbMetadataProviderAdapter(
            posterResolver = resolver(
                PosterRatingsSettings(
                    rpdbEnabled = true,
                    rpdbApiKey = "rpdb-key",
                )
            )
        ).execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.RPDB, PosterApiShapes.RPDB_POSTER_TEMPLATE)
        )

        assertNull(result.candidate?.fields?.get(ResolvedField.POSTER))
    }

    @Test
    fun `selected poster provider determines which configured adapter emits candidate`() = runTest {
        val settings = ArtworkProviderSettings(
            rpdbApiKey = "rpdb-key",
            topPostersApiKey = "top-key",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.TOP_POSTERS
            )
        )
        val route = route(
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.MOVIE,
            parentId = "tmdb:550",
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:550")
        )

        val rpdbResult = RpdbMetadataProviderAdapter(
            posterResolver = resolver(settings)
        ).execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.RPDB, PosterApiShapes.RPDB_POSTER_TEMPLATE)
        )
        val topPostersResult = TopPostersMetadataProviderAdapter(
            posterResolver = resolver(settings)
        ).execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.TOP_POSTERS, PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE)
        )

        assertNull(rpdbResult.candidate?.fields?.get(ResolvedField.POSTER))
        val topPostersValue = topPostersResult.candidate?.fields?.get(ResolvedField.POSTER)?.value as? String
        assertInternalArtworkRef(topPostersValue)
    }

    private fun resolver(
        settings: PosterRatingsSettings,
        cache: ArtworkDecisionCache = InMemoryArtworkDecisionCache()
    ): PosterRatingsUrlResolver =
        resolver(settings.toArtworkProviderSettings(), cache)

    private fun resolver(
        settings: ArtworkProviderSettings,
        cache: ArtworkDecisionCache = InMemoryArtworkDecisionCache()
    ): PosterRatingsUrlResolver {
        val dataStore = mockk<PosterRatingsSettingsDataStore>()
        every { dataStore.settings } returns flowOf(settings)
        return PosterRatingsUrlResolver(dataStore, cache)
    }

    private fun route(
        provider: MetadataPrimaryProvider,
        mediaKind: MetadataMediaKind,
        parentId: String,
        targetIds: Map<MetadataPrimaryProvider, String>,
        sourceMetadata: HomeDisplayMetadata? = null
    ): MetadataRoute = MetadataRoute(
        provider = provider,
        parentId = parentId,
        mediaKind = mediaKind,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        sourceContext = MetadataSourceContext(addonMetadata = sourceMetadata),
        targetIds = targetIds,
        trace = listOf(
            MetadataRouteTrace(
                reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                detail = "test route"
            )
        )
    )

    private fun posterStep(
        provider: MetadataPrimaryProvider,
        apiShapeId: String
    ): ProviderPlanStep = ProviderPlanStep(
        apiShapeId = apiShapeId,
        provider = provider,
        role = ProviderPlanRole.ARTWORK,
        required = false
    )

    private fun assertInternalArtworkRef(value: String?) {
        assertNotNull(value)
        assertTrue(value!!.startsWith("nexio-artwork://"))
        assertFalse(value.contains("api.top-posters.com"))
        assertFalse(value.contains("ratingposterdb.com"))
    }

    private fun decisionKeyFromRef(value: String): ArtworkDecisionKey =
        ArtworkDecisionKey(value.substringAfter("nexio-artwork://decision/"))
}
