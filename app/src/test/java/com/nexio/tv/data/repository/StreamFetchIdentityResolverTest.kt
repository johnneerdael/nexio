package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamFetchIdentityResolverTest {
    private val resolver = StreamFetchIdentityResolver()

    @Test
    fun `default stremio non anime tvdb series prefers show imdb episode stream id`() = runTest {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "393268",
            providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
        )

        val result = resolver.resolveForEpisode(
            identity,
            identity.providerIds,
            2,
            1,
            StreamSourceContext(MetadataMediaKind.SERIES, "tvdb:393268:2:1")
        )

        assertEquals("tt9794044:2:1", result?.videoId)
        assertEquals(StreamIdScheme.IMDB_EPISODE, result?.idScheme)
    }

    @Test
    fun `tvdb series stream identity does not require imdb`() = runTest {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "393268",
            providerIds = ProviderIds(tvdb = "393268")
        )

        val result = resolver.resolveForEpisode(
            identity,
            identity.providerIds,
            2,
            1,
            StreamSourceContext(MetadataMediaKind.SERIES, "tvdb:393268:2:1"),
            episodeOrderProvider = TvEpisodeOrderProvider.TVDB_DEFAULT
        )

        assertEquals("tvdb:393268:2:1", result?.videoId)
        assertEquals(StreamIdScheme.TVDB_EPISODE, result?.idScheme)
    }

    @Test
    fun `tmdb default series with tmdb canonical id and tvdb sidecar does not use tvdb episode stream id`() = runTest {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "71446",
            providerIds = ProviderIds(tmdb = "71446", tvdb = "81189")
        )

        val result = resolver.resolveForEpisode(
            identity,
            identity.providerIds,
            2,
            1,
            StreamSourceContext(MetadataMediaKind.SERIES, "tmdb:71446:2:1"),
            episodeOrderProvider = TvEpisodeOrderProvider.TMDB_DEFAULT
        )

        assertNull(result)
    }

    @Test
    fun `tvdb override series with tmdb canonical id and tvdb sidecar uses tvdb episode stream id`() = runTest {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "71446",
            providerIds = ProviderIds(tmdb = "71446", tvdb = "81189")
        )

        val result = resolver.resolveForEpisode(
            identity,
            identity.providerIds,
            2,
            1,
            StreamSourceContext(MetadataMediaKind.SERIES, "tmdb:71446:2:1"),
            episodeOrderProvider = TvEpisodeOrderProvider.TVDB_DEFAULT
        )

        assertEquals("tvdb:81189", result?.contentId)
        assertEquals("tvdb:81189:2:1", result?.videoId)
        assertEquals(StreamIdScheme.TVDB_EPISODE, result?.idScheme)
    }

    @Test
    fun `default stremio movie uses imdb movie stream id`() = runTest {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            providerIds = ProviderIds(tmdb = "550", imdb = "tt0137523")
        )

        val result = resolver.resolveForMovie(
            identity,
            identity.providerIds,
            StreamSourceContext(MetadataMediaKind.MOVIE, "tmdb:550")
        )

        assertEquals("tt0137523", result?.contentId)
        assertEquals("tt0137523", result?.videoId)
        assertEquals(StreamIdScheme.IMDB_MOVIE, result?.idScheme)
    }

    @Test
    fun `stream identity is unresolved when imdb id is not strict title id`() = runTest {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            providerIds = ProviderIds(tmdb = "550", imdb = "0137523")
        )

        val result = resolver.resolveForMovie(
            identity,
            identity.providerIds,
            StreamSourceContext(MetadataMediaKind.MOVIE, "tmdb:550")
        )

        assertNull(result)
    }
}
