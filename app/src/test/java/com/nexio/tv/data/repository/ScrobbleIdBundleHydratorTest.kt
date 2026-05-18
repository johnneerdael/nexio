package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.CanonicalStableIds
import com.nexio.tv.core.metadata.router.SidecarStableIds
import com.nexio.tv.core.metadata.router.SourceStableIds
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdBundleRequest
import com.nexio.tv.core.metadata.router.StableIdBundleResolver
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrobbleIdBundleHydratorTest {

    private val resolver = mockk<StableIdBundleResolver>()
    private val hydrator = ScrobbleIdBundleHydrator(resolver)

    private fun bundleWith(
        canonical: CanonicalStableIds = CanonicalStableIds(),
        sidecars: SidecarStableIds = SidecarStableIds(),
        observedIds: ProviderIds = ProviderIds(),
        sourceProvider: ProviderId? = null,
        itemType: ContentType = ContentType.SERIES,
    ): StableIdBundle = StableIdBundle(
        itemKey = "k",
        itemType = itemType,
        canonical = canonical,
        sidecars = sidecars,
        source = SourceStableIds(
            sourceProvider = sourceProvider,
            sourceItemId = null,
            railId = null,
            observedIds = observedIds,
        ),
        evidence = emptyList(),
        resolvedAtMs = 0L,
    )

    @Test
    fun `hydrate flattens canonical and sidecar IDs from bundle`() = runTest {
        coEvery { resolver.resolve(any()) } returns bundleWith(
            canonical = CanonicalStableIds(tmdbMovieId = "1396", tvdbSeriesId = "81189"),
            sidecars = SidecarStableIds(imdbId = "tt0903747"),
        )

        val ids = hydrator.hydrate(rawContentId = "tmdb:1396", contentType = "series")

        assertEquals("tt0903747", ids.imdb)
        assertEquals("1396", ids.tmdb)
        assertEquals("81189", ids.tvdb)
    }

    @Test
    fun `hydrate maps movie tmdb canonical id when tv id is also present`() = runTest {
        coEvery { resolver.resolve(any()) } returns bundleWith(
            itemType = ContentType.MOVIE,
            canonical = CanonicalStableIds(tmdbMovieId = "550", tmdbTvId = "71446"),
            sidecars = SidecarStableIds(imdbId = "tt0137523"),
        )

        val ids = hydrator.hydrate(rawContentId = "tmdb:550", contentType = "movie")

        assertEquals("tt0137523", ids.imdb)
        assertEquals("550", ids.tmdb)
    }

    @Test
    fun `hydrate maps series tmdb tv canonical id into provider ids tmdb`() = runTest {
        coEvery { resolver.resolve(any()) } returns bundleWith(
            canonical = CanonicalStableIds(tmdbTvId = "71446", tvdbSeriesId = "81189"),
            sidecars = SidecarStableIds(imdbId = "tt0903747"),
        )

        val ids = hydrator.hydrate(rawContentId = "tvdb:81189", contentType = "series")

        assertEquals("tt0903747", ids.imdb)
        assertEquals("71446", ids.tmdb)
        assertEquals("81189", ids.tvdb)
    }

    @Test
    fun `hydrate keeps tmdb tv canonical and scrobble sidecars for tvdb order override`() = runTest {
        coEvery { resolver.resolve(any()) } returns bundleWith(
            canonical = CanonicalStableIds(tmdbTvId = "71446", tvdbSeriesId = "81189"),
            sidecars = SidecarStableIds(imdbId = "tt0903747"),
            observedIds = ProviderIds(trakt = "1", simkl = "456"),
            sourceProvider = ProviderId.TVDB,
        )

        val ids = hydrator.hydrate(rawContentId = "tvdb:81189", contentType = "series")

        assertEquals("tt0903747", ids.imdb)
        assertEquals("71446", ids.tmdb)
        assertEquals("81189", ids.tvdb)
        assertEquals("1", ids.trakt)
        assertEquals("456", ids.simkl)
    }

    @Test
    fun `hydrate carries anime sidecars through`() = runTest {
        coEvery { resolver.resolve(any()) } returns bundleWith(
            canonical = CanonicalStableIds(kitsuAnimeId = "1"),
            sidecars = SidecarStableIds(
                imdbId = "tt0388629",
                malId = "21",
                anilistId = "21",
                anidbId = "69",
            ),
        )

        val ids = hydrator.hydrate(rawContentId = "kitsu:1", contentType = "series")

        assertEquals("1", ids.kitsu)
        assertEquals("21", ids.mal)
        assertEquals("21", ids.anilist)
        assertEquals("69", ids.anidb)
        assertEquals("tt0388629", ids.imdb)
    }

    @Test
    fun `hydrate preserves observed trakt and simkl from source`() = runTest {
        coEvery { resolver.resolve(any()) } returns bundleWith(
            observedIds = ProviderIds(trakt = "123", simkl = "456"),
        )

        val ids = hydrator.hydrate(rawContentId = "trakt:123", contentType = "movie")

        assertEquals("123", ids.trakt)
        assertEquals("456", ids.simkl)
    }

    @Test
    fun `hydrate falls back to raw imdb id when resolver throws`() = runTest {
        coEvery { resolver.resolve(any()) } throws IllegalStateException("network")

        val ids = hydrator.hydrate(rawContentId = "tt0903747", contentType = "series")

        assertEquals("tt0903747", ids.imdb)
        assertNull(ids.tmdb)
        assertNull(ids.tvdb)
    }

    @Test
    fun `hydrate falls back to parsed tmdb prefix when resolver throws`() = runTest {
        coEvery { resolver.resolve(any()) } throws IllegalStateException("network")

        val ids = hydrator.hydrate(rawContentId = "tmdb:1396", contentType = "movie")

        assertEquals("1396", ids.tmdb)
        assertNull(ids.imdb)
    }

    @Test
    fun `hydrate routes IMDB contentId through IMDB primary provider`() = runTest {
        val captured = slot<StableIdBundleRequest>()
        coEvery { resolver.resolve(capture(captured)) } returns bundleWith()

        hydrator.hydrate(rawContentId = "tt0903747", contentType = "series")

        coVerify { resolver.resolve(any()) }
        assertEquals(ContentType.SERIES, captured.captured.itemType)
        assertEquals("tt0903747", captured.captured.knownIds.imdb)
    }

    @Test
    fun `hydrate maps tv contentType alias to SERIES`() = runTest {
        val captured = slot<StableIdBundleRequest>()
        coEvery { resolver.resolve(capture(captured)) } returns bundleWith()

        hydrator.hydrate(rawContentId = "tmdb:1396", contentType = "tv")

        assertEquals(ContentType.SERIES, captured.captured.itemType)
    }
}
