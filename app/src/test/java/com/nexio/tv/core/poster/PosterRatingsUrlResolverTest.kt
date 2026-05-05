package com.nexio.tv.core.poster

import com.nexio.tv.core.image.PosterIntegrationRequest
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.InMemoryArtworkDecisionCache
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.PosterRatingsProvider
import com.nexio.tv.domain.model.ProviderIds
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
        assertFalse(resolved.orEmpty().contains("api.top-posters.com"))
        assertFalse(resolved.orEmpty().contains("ratingposterdb.com"))
        val decision = cache.get(decisionKeyFromRef(resolved!!))
        assertEquals("TOP_POSTERS", decision?.selectedCandidate?.provider?.key)
        assertEquals("tmdb", decision?.selectedCandidate?.providerTemplate?.idType)
        assertEquals("movie-550", decision?.selectedCandidate?.providerTemplate?.mediaId)
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
        assertFalse(resolved.orEmpty().contains("api.top-posters.com"))
        assertFalse(resolved.orEmpty().contains("ratingposterdb.com"))
        val decision = cache.get(decisionKeyFromRef(resolved!!))
        assertEquals("RPDB", decision?.selectedCandidate?.provider?.key)
        assertEquals("imdb", decision?.selectedCandidate?.providerTemplate?.idType)
        assertEquals("tt15940132", decision?.selectedCandidate?.providerTemplate?.mediaId)
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
    fun `rpdb poster url is built from imdb id when source poster is blank`() {
        val resolved = resolver.resolvePosterUrl(
            originalPosterUrl = null,
            contentId = "tt15940132",
            contentType = ContentType.MOVIE,
            activeProvider = PosterRatingsUrlResolver.ActiveProvider(
                provider = PosterRatingsProvider.RPDB,
                apiKey = "rpdb-key"
            )
        )

        val request = PosterIntegrationRequest.fromModel(resolved!!)
        assertEquals(IntegrationProvider.RPDB, request?.provider)
        assertEquals("rpdb-key", request?.apiKey)
        assertEquals("imdb/poster-default/tt15940132.jpg", request?.path)
    }

    @Test
    fun `top posters url is built from tmdb id when source poster is blank`() {
        val resolved = resolver.resolvePosterUrl(
            originalPosterUrl = "",
            contentId = "tmdb:123",
            contentType = ContentType.SERIES,
            activeProvider = PosterRatingsUrlResolver.ActiveProvider(
                provider = PosterRatingsProvider.TOP_POSTERS,
                apiKey = "top-key"
            )
        )

        val request = PosterIntegrationRequest.fromModel(resolved!!)
        assertEquals(IntegrationProvider.TOP_POSTERS, request?.provider)
        assertEquals("top-key", request?.apiKey)
        assertEquals("tmdb/poster/series-123.jpg", request?.path)
    }

    @Test
    fun `top posters url is built from tvdb id when source poster is blank`() {
        val resolved = resolver.resolvePosterUrl(
            originalPosterUrl = null,
            contentId = "tvdb:121361",
            contentType = ContentType.SERIES,
            activeProvider = PosterRatingsUrlResolver.ActiveProvider(
                provider = PosterRatingsProvider.TOP_POSTERS,
                apiKey = "top-key"
            )
        )

        val request = PosterIntegrationRequest.fromModel(resolved!!)
        assertEquals(IntegrationProvider.TOP_POSTERS, request?.provider)
        assertEquals("top-key", request?.apiKey)
        assertEquals("tvdb/poster/121361.jpg", request?.path)
    }

    @Test
    fun `rpdb poster url is built from tvdb id`() {
        val resolved = resolver.resolvePosterUrl(
            originalPosterUrl = "https://tvdb.example/poster.jpg",
            contentId = "tvdb:121361",
            contentType = ContentType.SERIES,
            activeProvider = PosterRatingsUrlResolver.ActiveProvider(
                provider = PosterRatingsProvider.RPDB,
                apiKey = "rpdb-key"
            )
        )

        val request = PosterIntegrationRequest.fromModel(resolved!!)
        assertEquals(IntegrationProvider.RPDB, request?.provider)
        assertEquals("rpdb-key", request?.apiKey)
        assertEquals("tvdb/poster-default/121361.jpg", request?.path)
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

    private fun assertInternalArtworkRef(value: String?) {
        assertNotNull(value)
        assertTrue(value!!.startsWith("nexio-artwork://"))
    }

    private fun decisionKeyFromRef(value: String): ArtworkDecisionKey =
        ArtworkDecisionKey(value.substringAfter("nexio-artwork://decision/"))
}
