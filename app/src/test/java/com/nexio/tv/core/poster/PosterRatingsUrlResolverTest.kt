package com.nexio.tv.core.poster

import com.nexio.tv.core.artwork.ArtworkCacheKeys
import com.nexio.tv.core.artwork.ArtworkDecision
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.EpisodeArtworkContext
import com.nexio.tv.core.artwork.InMemoryArtworkDecisionCache
import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.core.image.PosterIntegrationRequest
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterRatingsProvider
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TopPostersEntitlementSnapshot
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PosterRatingsUrlResolverTest {

    private val resolver = PosterRatingsUrlResolver(
        settingsDataStore = mockk<PosterRatingsSettingsDataStore>(),
        artworkDecisionCache = InMemoryArtworkDecisionCache()
    )

    @Test
    fun `top posters selected poster returns internal artwork ref without provider domain`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolver = resolver(cache)

        val resolved = resolver.resolvePosterArtworkString(
            settings = topPostersSettings(),
            providerIds = ProviderIds(tmdb = "550"),
            mediaKind = MetadataMediaKind.MOVIE,
            ownerKey = ArtworkOwnerKey.CanonicalContent("tmdb:movie-550")
        )

        assertInternalArtworkRef(resolved)
        assertNoRawPremiumUrl(resolved)
        assertFalse(resolved.orEmpty().contains("api.top-posters.com"))
        assertFalse(resolved.orEmpty().contains("ratingposterdb.com"))
        val decision = cache.get(decisionKeyFromRef(resolved!!))
        assertEquals("TOP_POSTERS", decision?.selectedCandidate?.provider?.key)
        assertEquals("tmdb", decision?.selectedCandidate?.providerTemplate?.idType)
        assertEquals("movie-550", decision?.selectedCandidate?.providerTemplate?.mediaId)
        assertFalse(decision?.credentialHash.orEmpty().contains("key"))
        assertEquals(64, decision?.credentialHash?.length)
    }

    @Test
    fun `rpdb selected poster returns internal artwork ref without provider domain`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolver = resolver(cache)

        val resolved = resolver.resolvePosterArtworkString(
            settings = rpdbSettings(),
            providerIds = ProviderIds(imdb = "tt15940132"),
            mediaKind = MetadataMediaKind.MOVIE,
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt15940132")
        )

        assertInternalArtworkRef(resolved)
        assertNoRawPremiumUrl(resolved)
        assertFalse(resolved.orEmpty().contains("api.top-posters.com"))
        assertFalse(resolved.orEmpty().contains("ratingposterdb.com"))
        val decision = cache.get(decisionKeyFromRef(resolved!!))
        assertEquals("RPDB", decision?.selectedCandidate?.provider?.key)
        assertEquals("imdb", decision?.selectedCandidate?.providerTemplate?.idType)
        assertEquals("tt15940132", decision?.selectedCandidate?.providerTemplate?.mediaId)
        assertFalse(decision?.credentialHash.orEmpty().contains("key"))
        assertEquals(64, decision?.credentialHash?.length)
    }

    @Test
    fun `default poster selection returns fallback primary and not premium provider ref`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolver = resolver(cache)
        val fallbackUrl = "https://image.tmdb.org/t/p/w500/poster.jpg"

        val resolved = resolver.resolvePosterArtworkString(
            settings = ArtworkProviderSettings(
                rpdbApiKey = "rpdb-key",
                topPostersApiKey = "top-key",
                selection = ArtworkProviderSelectionSettings(
                    posterProvider = ArtworkProviderChoiceKey.DEFAULT
                )
            ),
            providerIds = ProviderIds(imdb = "tt15940132"),
            mediaKind = MetadataMediaKind.MOVIE,
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt15940132"),
            fallbackPosterUrl = fallbackUrl
        )

        assertInternalArtworkRef(resolved)
        val decision = cache.get(decisionKeyFromRef(resolved!!))
        assertEquals("TMDB", decision?.selectedCandidate?.provider?.key)
        assertNull(decision?.selectedCandidate?.providerTemplate)
    }

    @Test
    fun `top posters selected with kitsu anime id uses top posters provider template`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolver = resolver(cache)

        val resolved = resolver.resolvePosterArtworkString(
            settings = topPostersSettings(),
            providerIds = ProviderIds(kitsu = "7442"),
            mediaKind = MetadataMediaKind.ANIME,
            ownerKey = ArtworkOwnerKey.CanonicalContent("kitsu:7442")
        )

        assertInternalArtworkRef(resolved)
        val decision = cache.get(decisionKeyFromRef(resolved!!))
        assertEquals("TOP_POSTERS", decision?.selectedCandidate?.provider?.key)
        assertEquals("kitsu", decision?.selectedCandidate?.providerTemplate?.idType)
        assertEquals("7442", decision?.selectedCandidate?.providerTemplate?.mediaId)
    }

    @Test
    fun `active rpdb poster projection uses shared artwork ref`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolved = resolver(cache).resolvePosterArtworkString(
            originalPosterUrl = null,
            contentId = "tt15940132",
            contentType = ContentType.MOVIE,
            activeProvider = PosterRatingsUrlResolver.ActiveProvider(
                provider = PosterRatingsProvider.RPDB,
                apiKey = "rpdb-key"
            )
        )

        assertInternalArtworkRef(resolved)
        assertNoRawPremiumUrl(resolved)
        val decision = cache.get(decisionKeyFromRef(resolved!!))
        assertEquals("RPDB", decision?.selectedCandidate?.provider?.key)
        assertEquals("imdb", decision?.selectedCandidate?.providerTemplate?.idType)
        assertEquals("tt15940132", decision?.selectedCandidate?.providerTemplate?.mediaId)
    }

    @Test
    fun `active top posters poster projection uses shared artwork ref from tmdb id`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolved = resolver(cache).resolvePosterArtworkString(
            originalPosterUrl = "",
            contentId = "tmdb:123",
            contentType = ContentType.SERIES,
            activeProvider = PosterRatingsUrlResolver.ActiveProvider(
                provider = PosterRatingsProvider.TOP_POSTERS,
                apiKey = "top-key"
            )
        )

        assertInternalArtworkRef(resolved)
        assertNoRawPremiumUrl(resolved)
        val decision = cache.get(decisionKeyFromRef(resolved!!))
        assertEquals("TOP_POSTERS", decision?.selectedCandidate?.provider?.key)
        assertEquals("tmdb", decision?.selectedCandidate?.providerTemplate?.idType)
        assertEquals("series-123", decision?.selectedCandidate?.providerTemplate?.mediaId)
    }

    @Test
    fun `active top posters poster projection uses shared artwork ref from tvdb id`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolved = resolver(cache).resolvePosterArtworkString(
            originalPosterUrl = null,
            contentId = "tvdb:121361",
            contentType = ContentType.SERIES,
            activeProvider = PosterRatingsUrlResolver.ActiveProvider(
                provider = PosterRatingsProvider.TOP_POSTERS,
                apiKey = "top-key"
            )
        )

        assertInternalArtworkRef(resolved)
        assertNoRawPremiumUrl(resolved)
        val decision = cache.get(decisionKeyFromRef(resolved!!))
        assertEquals("TOP_POSTERS", decision?.selectedCandidate?.provider?.key)
        assertEquals("tvdb", decision?.selectedCandidate?.providerTemplate?.idType)
        assertEquals("121361", decision?.selectedCandidate?.providerTemplate?.mediaId)
    }

    @Test
    fun `legacy poster url resolver does not emit premium provider model`() {
        val resolved = resolver.resolvePosterUrl(
            originalPosterUrl = "https://tvdb.example/poster.jpg",
            contentId = "tvdb:121361",
            contentType = ContentType.SERIES,
            activeProvider = PosterRatingsUrlResolver.ActiveProvider(
                provider = PosterRatingsProvider.RPDB,
                apiKey = "rpdb-key"
            )
        )

        assertEquals("https://tvdb.example/poster.jpg", resolved)
        assertNoRawPremiumUrl(resolved)
    }

    @Test
    fun `legacy poster artwork string preserves internal ref without active provider`() {
        val ref = "nexio-artwork://asset/provider-poster-key"

        val resolved = resolver.resolvePosterArtworkString(
            originalPosterUrl = ref,
            contentId = "tvdb:121361",
            contentType = ContentType.SERIES,
            activeProvider = null
        )

        assertEquals(ref, resolved)
    }

    @Test
    fun `meta apply preserves internal ref without active provider`() {
        val ref = "nexio-artwork://decision/provider-decision-key"
        val meta = meta(id = "tmdb:550", poster = ref)

        val resolved = resolver.apply(meta, activeProvider = null)

        assertEquals(ref, resolved.poster)
        assertNull(resolved.posterProviderTag)
    }

    @Test
    fun `top posters episode thumbnail projects selected provider template as asset URI`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolver = resolver(cache)

        val resolved = resolver.resolveEpisodeThumbnailArtworkRef(
            settings = topPostersThumbnailSettings(),
            providerIds = ProviderIds(tvdb = "1399"),
            mediaKind = MetadataMediaKind.SERIES,
            ownerKey = ArtworkOwnerKey.CanonicalContent("tvdb:1399:S1E1"),
            episodeContext = EpisodeArtworkContext(season = 1, episode = 1),
            fallbackThumbnailUrl = "https://image.tmdb.org/t/p/w500/fallback.jpg",
            primaryProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB)
        )

        val runtimeRef = resolved as ArtworkDisplayRef.RuntimeAsset
        val decision = cache.get(runtimeRef.decisionKey)
        val expectedAssetKey = ArtworkCacheKeys.assetKeyForProviderTemplate(
            decision!!.selectedCandidate.providerTemplate!!
        )
        assertEquals(expectedAssetKey, runtimeRef.assetKey)
        assertTrue(runtimeRef.displayHints.embedsRatingOverlay)
        assertEquals("nexio-artwork://asset/${expectedAssetKey.value}", resolved.toLegacyArtworkString())
    }

    @Test
    fun `primary episode thumbnail fallback projects selected remote URL as asset URI`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolver = resolver(cache)
        val fallbackUrl = "https://image.tmdb.org/t/p/w500/still.jpg?utm_source=newsletter"

        val resolved = resolver.resolveEpisodeThumbnailArtworkRef(
            settings = ArtworkProviderSettings(
                topPostersApiKey = "top-key",
                selection = ArtworkProviderSelectionSettings(
                    thumbnailProvider = ArtworkProviderChoiceKey.DEFAULT
                )
            ),
            providerIds = ProviderIds(tvdb = "1399"),
            mediaKind = MetadataMediaKind.SERIES,
            ownerKey = ArtworkOwnerKey.CanonicalContent("tvdb:1399:S1E1"),
            episodeContext = EpisodeArtworkContext(season = 1, episode = 1),
            fallbackThumbnailUrl = fallbackUrl,
            primaryProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB)
        )

        val runtimeRef = resolved as ArtworkDisplayRef.RuntimeAsset
        val expectedAssetKey = ArtworkCacheKeys.assetKeyForRemoteUrl(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
            imageType = ArtworkType.THUMBNAIL,
            normalizedUrlHash = ArtworkCacheKeys.normalizedUrlHash(fallbackUrl),
            variant = null,
            policyVersion = 1
        )
        assertEquals(expectedAssetKey, runtimeRef.assetKey)
        assertFalse(runtimeRef.displayHints.embedsRatingOverlay)
        assertEquals("nexio-artwork://asset/${expectedAssetKey.value}", resolved.toLegacyArtworkString())
    }

    @Test
    fun `unsupported top posters episode thumbnail without primary fallback returns null`() {
        val resolved = resolver(InMemoryArtworkDecisionCache()).resolveEpisodeThumbnailArtworkRef(
            settings = ArtworkProviderSettings(
                topPostersApiKey = "top-key",
                selection = ArtworkProviderSelectionSettings(
                    thumbnailProvider = ArtworkProviderChoiceKey.TOP_POSTERS
                ),
                topPostersEntitlement = null
            ),
            providerIds = ProviderIds(tvdb = "1399"),
            mediaKind = MetadataMediaKind.SERIES,
            ownerKey = ArtworkOwnerKey.CanonicalContent("tvdb:1399:S1E1"),
            episodeContext = EpisodeArtworkContext(season = 1, episode = 1),
            fallbackThumbnailUrl = null,
            primaryProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB)
        )

        assertNull(resolved)
    }

    @Test
    fun `invalid top posters episode thumbnail context falls back to primary with rejection trace`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolved = resolver(cache).resolveEpisodeThumbnailArtworkRef(
            settings = topPostersThumbnailSettings(),
            providerIds = ProviderIds(tvdb = "1399"),
            mediaKind = MetadataMediaKind.SERIES,
            ownerKey = ArtworkOwnerKey.CanonicalContent("tvdb:1399:S0E1"),
            episodeContext = EpisodeArtworkContext(season = 0, episode = 1),
            fallbackThumbnailUrl = "https://image.tmdb.org/t/p/w500/fallback.jpg",
            primaryProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB)
        )

        val runtimeRef = resolved as ArtworkDisplayRef.RuntimeAsset
        val decision = cache.get(runtimeRef.decisionKey)
        assertEquals("TMDB", decision?.selectedCandidate?.provider?.key)
        assertEquals(
            listOf("topposters_invalid_episode_context"),
            decision?.rejectedCandidates?.map { it.reason }
        )
        assertFalse(runtimeRef.displayHints.embedsRatingOverlay)
    }

    @Test
    fun `unsupported top posters episode thumbnail id falls back to primary with rejection trace`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolved = resolver(cache).resolveEpisodeThumbnailArtworkRef(
            settings = topPostersThumbnailSettings(),
            providerIds = ProviderIds(tmdb = "bad-id"),
            mediaKind = MetadataMediaKind.SERIES,
            ownerKey = ArtworkOwnerKey.CanonicalContent("tmdb:bad-id:S1E1"),
            episodeContext = EpisodeArtworkContext(season = 1, episode = 1),
            fallbackThumbnailUrl = "https://image.tmdb.org/t/p/w500/fallback.jpg",
            primaryProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB)
        )

        val runtimeRef = resolved as ArtworkDisplayRef.RuntimeAsset
        val decision = cache.get(runtimeRef.decisionKey)
        assertEquals("TMDB", decision?.selectedCandidate?.provider?.key)
        assertEquals(
            listOf("missing_supported_provider_id"),
            decision?.rejectedCandidates?.map { it.reason }
        )
        assertFalse(runtimeRef.displayHints.embedsRatingOverlay)
    }

    @Test
    fun `premium key clear falls back to primary artwork`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolver = resolver(cache)
        val fallbackUrl = "https://image.tmdb.org/t/p/w500/poster.jpg"

        val resolved = resolver.resolvePosterArtworkString(
            settings = ArtworkProviderSettings(
                rpdbApiKey = "",
                selection = ArtworkProviderSelectionSettings(
                    posterProvider = ArtworkProviderChoiceKey.RPDB
                )
            ),
            providerIds = ProviderIds(imdb = "tt15940132"),
            mediaKind = MetadataMediaKind.MOVIE,
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt15940132"),
            fallbackPosterUrl = fallbackUrl
        )

        assertInternalArtworkRef(resolved)
        assertNoRawPremiumUrl(resolved)
        val decision = cache.get(decisionKeyFromRef(resolved!!))
        assertEquals("TMDB", decision?.selectedCandidate?.provider?.key)
        assertNull(decision?.selectedCandidate?.providerTemplate)
    }

    @Test
    fun `meta preview premium projection returns shared artwork ref not raw provider url`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolver = resolver(cache)
        val preview = preview(id = "tt15940132", poster = "https://image.tmdb.org/t/p/w500/poster.jpg").copy(
            firstPaintStableIds = ProviderIds(imdb = "tt15940132")
        )

        val resolved = resolver.applyArtworkRef(preview, rpdbSettings())

        assertInternalArtworkRef(resolved.poster)
        assertNoRawPremiumUrl(resolved.poster)
        assertEquals("rpdb", resolved.posterProviderTag)
        val decision = cache.get(decisionKeyFromRef(resolved.poster!!))
        assertEquals("RPDB", decision?.selectedCandidate?.provider?.key)
        assertEquals(ArtworkOwnerKey.CanonicalContent("imdb:tt15940132"), decision?.ownerKey)
    }

    @Test
    fun `legacy meta preview apply emits shared artwork ref not integration poster model`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolver = resolver(cache)
        val preview = preview(id = "tt15940132", poster = "https://image.tmdb.org/t/p/w500/poster.jpg")

        val resolved = resolver.apply(
            preview,
            PosterRatingsUrlResolver.ActiveProvider(
                provider = PosterRatingsProvider.RPDB,
                apiKey = "rpdb-key"
            )
        )

        assertInternalArtworkRef(resolved.poster)
        assertNoRawPremiumUrl(resolved.poster)
        assertEquals("rpdb", resolved.posterProviderTag)
        val decision = cache.get(decisionKeyFromRef(resolved.poster!!))
        assertEquals("RPDB", decision?.selectedCandidate?.provider?.key)
    }

    @Test
    fun `legacy meta apply emits shared artwork ref not integration poster model`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolver = resolver(cache)
        val meta = meta(id = "tmdb:550", poster = "https://image.tmdb.org/t/p/w500/poster.jpg")

        val resolved = resolver.apply(
            meta,
            PosterRatingsUrlResolver.ActiveProvider(
                provider = PosterRatingsProvider.TOP_POSTERS,
                apiKey = "top-key"
            )
        )

        assertInternalArtworkRef(resolved.poster)
        assertNoRawPremiumUrl(resolved.poster)
        assertEquals("top_posters", resolved.posterProviderTag)
        val decision = cache.get(decisionKeyFromRef(resolved.poster!!))
        assertEquals("TOP_POSTERS", decision?.selectedCandidate?.provider?.key)
        assertEquals(ArtworkOwnerKey.CanonicalContent("tmdb:movie-550"), decision?.ownerKey)
    }

    @Test
    fun `meta preview fallback poster is not tagged premium`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolver = resolver(cache)
        val fallbackUrl = "https://image.tmdb.org/t/p/w500/poster.jpg"
        val preview = preview(id = "addon-item-1", poster = fallbackUrl)

        val resolved = resolver.applyArtworkRef(
            preview,
            ArtworkProviderSettings(
                rpdbApiKey = "",
                selection = ArtworkProviderSelectionSettings(
                    posterProvider = ArtworkProviderChoiceKey.RPDB
                )
            )
        )

        assertInternalArtworkRef(resolved.poster)
        assertEquals(null, resolved.posterProviderTag)
        val decision = cache.get(decisionKeyFromRef(resolved.poster!!))
        assertEquals("TMDB", decision?.selectedCandidate?.provider?.key)
        assertEquals(ArtworkOwnerKey.PreviewItem::class, decision?.ownerKey!!::class)
    }

    @Test
    fun `meta preview without stable provider ids uses preview owner key`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolver = resolver(cache)
        val preview = preview(id = "tt15940132", poster = "https://image.tmdb.org/t/p/w500/poster.jpg")

        val resolved = resolver.applyArtworkRef(preview, rpdbSettings())

        val decision = cache.get(decisionKeyFromRef(resolved.poster!!))
        assertEquals(ArtworkOwnerKey.PreviewItem::class, decision?.ownerKey!!::class)
    }

    @Test
    fun `meta preview internal artwork ref is idempotent and does not write decision`() {
        val cache = CountingArtworkDecisionCache()
        val resolver = resolver(cache)
        val preview = preview(
            id = "tt15940132",
            poster = "nexio-artwork://decision/existing"
        ).copy(posterProviderTag = "rpdb")

        val resolved = resolver.applyArtworkRef(preview, rpdbSettings())

        assertEquals(preview, resolved)
        assertEquals(0, cache.putCount)
    }

    @Test
    fun `meta preview placeholder ref is idempotent and does not write decision`() {
        val cache = CountingArtworkDecisionCache()
        val resolver = resolver(cache)
        val preview = preview(
            id = "source-item",
            poster = "nexio-placeholder://poster/source-item"
        )

        val resolved = resolver.applyArtworkRef(preview, rpdbSettings())

        assertEquals(preview, resolved)
        assertEquals(0, cache.putCount)
    }

    @Test
    fun `meta preview legacy premium integration poster is not persisted as fallback`() {
        val cache = CountingArtworkDecisionCache()
        val resolver = resolver(cache)
        val legacyPremiumPoster = PosterIntegrationRequest(
            provider = IntegrationProvider.RPDB,
            cacheKey = "legacy-rpdb",
            apiKey = "secret",
            path = "imdb/poster-default/tt15940132.jpg"
        ).toModel()
        val preview = preview(id = "source-item", poster = legacyPremiumPoster)

        val resolved = resolver.applyArtworkRef(preview, ArtworkProviderSettings())

        assertNull(resolved.poster)
        assertNull(resolved.posterProviderTag)
        assertEquals(0, cache.putCount)
    }

    @Test
    fun `meta preview raw premium provider url is not persisted as fallback`() {
        val cache = CountingArtworkDecisionCache()
        val resolver = resolver(cache)
        val preview = preview(
            id = "source-item",
            poster = "https://api.ratingposterdb.com/secret/imdb/poster-default/tt15940132.jpg"
        )

        val resolved = resolver.applyArtworkRef(preview, ArtworkProviderSettings())

        assertNull(resolved.poster)
        assertNull(resolved.posterProviderTag)
        assertEquals(0, cache.putCount)
    }

    @Test
    fun `meta preview raw top posters provider url is not persisted as fallback`() {
        val cache = CountingArtworkDecisionCache()
        val resolver = resolver(cache)
        val preview = preview(
            id = "source-item",
            poster = "https://api.top-posters.com/secret/imdb/poster/tt15940132.jpg"
        )

        val resolved = resolver.applyArtworkRef(preview, ArtworkProviderSettings())

        assertNull(resolved.poster)
        assertNull(resolved.posterProviderTag)
        assertEquals(0, cache.putCount)
    }

    @Test
    fun `blank stable ids do not suppress derived preview id`() {
        val cache = InMemoryArtworkDecisionCache()
        val resolver = resolver(cache)
        val preview = preview(
            id = "tt15940132",
            poster = "https://image.tmdb.org/t/p/w500/poster.jpg"
        ).copy(firstPaintStableIds = ProviderIds(imdb = " "))

        val resolved = resolver.applyArtworkRef(preview, rpdbSettings())

        val decision = cache.get(decisionKeyFromRef(resolved.poster!!))
        assertEquals("RPDB", decision?.selectedCandidate?.provider?.key)
        assertEquals("imdb", decision?.selectedCandidate?.providerTemplate?.idType)
        assertEquals("tt15940132", decision?.selectedCandidate?.providerTemplate?.mediaId)
    }

    private fun resolver(cache: ArtworkDecisionCache): PosterRatingsUrlResolver =
        PosterRatingsUrlResolver(
            settingsDataStore = mockk<PosterRatingsSettingsDataStore>(),
            artworkDecisionCache = cache
        )

    private fun topPostersSettings(): ArtworkProviderSettings =
        ArtworkProviderSettings(
            topPostersApiKey = "top-key",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.TOP_POSTERS
            )
        )

    private fun rpdbSettings(): ArtworkProviderSettings =
        ArtworkProviderSettings(
            rpdbApiKey = "rpdb-key",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB
            )
        )

    private fun topPostersThumbnailSettings(): ArtworkProviderSettings =
        ArtworkProviderSettings(
            topPostersApiKey = "top-key",
            selection = ArtworkProviderSelectionSettings(
                thumbnailProvider = ArtworkProviderChoiceKey.TOP_POSTERS
            ),
            topPostersEntitlement = TopPostersEntitlementSnapshot(
                valid = true,
                isActive = true,
                tier = 1,
                tierName = "Premium",
                episodeThumbnails = true,
                verifiedAtMs = 1_000L,
                expiresAtMs = System.currentTimeMillis() + 86_400_000L
            )
        )

    private fun assertInternalArtworkRef(value: String?) {
        assertNotNull(value)
        assertTrue(value!!.startsWith("nexio-artwork://"))
    }

    private fun assertNoRawPremiumUrl(value: String?) {
        val text = value.orEmpty()
        assertFalse(text.startsWith("https://api.ratingposterdb.com"))
        assertFalse(text.startsWith("https://api.top-posters.com"))
        assertFalse(text.startsWith("integration-poster://"))
    }

    private fun decisionKeyFromRef(value: String): ArtworkDecisionKey =
        ArtworkDecisionKey(value.substringAfter("nexio-artwork://decision/"))

    private fun preview(id: String, poster: String?): MetaPreview =
        MetaPreview(
            id = id,
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Item $id",
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            firstPaintSource = FirstPaintSource.ADDON_META_PREVIEW
        )

    private fun meta(id: String, poster: String?): Meta =
        Meta(
            id = id,
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Item $id",
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            videos = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList()
        )

    private class CountingArtworkDecisionCache : ArtworkDecisionCache {
        private val delegate = InMemoryArtworkDecisionCache()
        var putCount: Int = 0
            private set

        override fun get(key: ArtworkDecisionKey): ArtworkDecision? =
            delegate.get(key)

        override fun put(decision: ArtworkDecision) {
            putCount += 1
            delegate.put(decision)
        }

        override fun remove(key: ArtworkDecisionKey) {
            delegate.remove(key)
        }

        override fun linkPreviewToCanonical(previewKey: ArtworkDecisionKey, canonicalKey: ArtworkDecisionKey) {
            delegate.linkPreviewToCanonical(previewKey, canonicalKey)
        }

        override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? =
            delegate.getCanonicalForPreview(previewKey)

        override fun invalidateBySettingsHash(settingsHash: String) {
            delegate.invalidateBySettingsHash(settingsHash)
        }

        override fun invalidateByCredentialHash(credentialHash: String) {
            delegate.invalidateByCredentialHash(credentialHash)
        }

        override fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>) {
            delegate.invalidateArtworkPolicy(settingsHashes, credentialHashes)
        }

        override fun invalidatePremiumArtworkPolicy() {
            delegate.invalidatePremiumArtworkPolicy()
        }
    }
}
