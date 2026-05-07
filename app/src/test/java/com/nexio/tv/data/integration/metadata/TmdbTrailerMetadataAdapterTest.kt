package com.nexio.tv.data.integration.metadata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.core.media.ContentIdentity
import com.nexio.tv.core.media.MediaClipScope
import com.nexio.tv.core.media.MediaClipStore
import com.nexio.tv.core.media.MediaClipType
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ProviderPlanRole
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolverType
import com.nexio.tv.data.integration.trailer.TrailerTmdbProvider
import com.nexio.tv.data.remote.api.TmdbVideoResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TmdbTrailerMetadataAdapterTest {

    private val sampleVideo = TmdbVideoResult(
        iso6391 = "en",
        name = "Trailer",
        key = "abc123",
        site = "YouTube",
        type = "Trailer",
        official = true
    )

    @Test
    fun `supports declares MOVIE_VIDEOS TV_VIDEOS SEASON_VIDEOS only`() {
        val adapter = TmdbTrailerMetadataAdapter(mockk(relaxed = true))
        assertTrue(adapter.supports(step(TmdbApiShapes.MOVIE_VIDEOS)))
        assertTrue(adapter.supports(step(TmdbApiShapes.TV_VIDEOS)))
        assertTrue(adapter.supports(step(TmdbApiShapes.SEASON_VIDEOS)))
        assertEquals(false, adapter.supports(step(TmdbApiShapes.MOVIE_CORE)))
        assertEquals(false, adapter.supports(step(TmdbApiShapes.TV_CORE)))
    }

    @Test
    fun `MOVIE_VIDEOS produces TRAILERS candidate from provider`() = runTest {
        val provider = mockk<TrailerTmdbProvider>()
        coEvery { provider.getTmdbApiKey() } returns "k"
        coEvery { provider.fetchMovieVideos(550, "en-US", "k") } returns listOf(sampleVideo)
        val adapter = TmdbTrailerMetadataAdapter(provider)

        val result = adapter.execute(
            route = movieRoute(),
            step = step(TmdbApiShapes.MOVIE_VIDEOS)
        )

        assertNotNull(result.candidate)
        val candidate = result.candidate!!
        assertEquals(MetadataPrimaryProvider.TMDB, candidate.provider)
        assertEquals(ResolverType.TRAILERS, candidate.resolverType)
        val trailers = candidate.fields[ResolvedField.TRAILERS]?.value
        assertEquals(listOf(sampleVideo), trailers)
    }

    @Test
    fun `MOVIE_VIDEOS stores TMDB trailer candidate in media clip store`() = runTest {
        val provider = mockk<TrailerTmdbProvider>()
        coEvery { provider.getTmdbApiKey() } returns "k"
        coEvery { provider.fetchMovieVideos(550, "en-US", "k") } returns listOf(sampleVideo)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = MediaClipStore(
            context = context,
            prefsName = "tmdb_trailer_adapter_media_clip_${System.nanoTime()}",
            clock = { 1_000_000L }
        )
        val adapter = TmdbTrailerMetadataAdapter(provider, mediaClipStore = store)

        adapter.execute(
            route = movieRoute(),
            step = step(TmdbApiShapes.MOVIE_VIDEOS)
        )

        val identity = ContentIdentity(
            contentId = "tmdb:550",
            itemType = "movie",
            stableIds = com.nexio.tv.domain.model.ProviderIds(tmdb = "550")
        )
        val clips = store.getCandidates(
            identity = identity,
            scope = MediaClipScope.Title(identity),
            clipTypes = setOf(MediaClipType.TRAILER),
            language = "en"
        )
        assertEquals(1, clips.size)
        assertEquals("TMDB", clips.single().provider)
        assertEquals("abc123", clips.single().externalVideoId)
    }

    @Test
    fun `TV_VIDEOS uses route language when present`() = runTest {
        val provider = mockk<TrailerTmdbProvider>()
        coEvery { provider.getTmdbApiKey() } returns "k"
        coEvery { provider.fetchTvVideos(1399, "fr-FR", "k") } returns listOf(sampleVideo)
        val adapter = TmdbTrailerMetadataAdapter(provider)

        val result = adapter.execute(
            route = tvRoute(language = "fr-FR"),
            step = step(TmdbApiShapes.TV_VIDEOS)
        )

        assertNotNull(result.candidate?.fields?.get(ResolvedField.TRAILERS))
    }

    @Test
    fun `SEASON_VIDEOS uses route seasonNumber and emits empty when no season`() = runTest {
        val provider = mockk<TrailerTmdbProvider>()
        coEvery { provider.getTmdbApiKey() } returns "k"
        coEvery { provider.fetchSeasonVideos(1399, 2, "en-US", "k") } returns listOf(sampleVideo)
        val adapter = TmdbTrailerMetadataAdapter(provider)

        val withSeason = adapter.execute(
            route = tvRoute(seasonNumber = 2),
            step = step(TmdbApiShapes.SEASON_VIDEOS)
        )
        assertNotNull(withSeason.candidate?.fields?.get(ResolvedField.TRAILERS))

        val noSeason = adapter.execute(
            route = tvRoute(seasonNumber = null),
            step = step(TmdbApiShapes.SEASON_VIDEOS)
        )
        assertTrue(noSeason.candidate?.fields.isNullOrEmpty())
    }

    @Test
    fun `missing api key short circuits to empty candidate`() = runTest {
        val provider = mockk<TrailerTmdbProvider>()
        coEvery { provider.getTmdbApiKey() } returns null
        val adapter = TmdbTrailerMetadataAdapter(provider)

        val result = adapter.execute(
            route = movieRoute(),
            step = step(TmdbApiShapes.MOVIE_VIDEOS)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
    }

    @Test
    fun `missing tmdb id short circuits to empty candidate`() = runTest {
        val provider = mockk<TrailerTmdbProvider>(relaxed = true)
        val adapter = TmdbTrailerMetadataAdapter(provider)

        val result = adapter.execute(
            route = movieRoute(targetIds = emptyMap()),
            step = step(TmdbApiShapes.MOVIE_VIDEOS)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
    }

    @Test
    fun `empty video list yields empty candidate`() = runTest {
        val provider = mockk<TrailerTmdbProvider>()
        coEvery { provider.getTmdbApiKey() } returns "k"
        coEvery { provider.fetchMovieVideos(550, "en-US", "k") } returns emptyList()
        val adapter = TmdbTrailerMetadataAdapter(provider)

        val result = adapter.execute(
            route = movieRoute(),
            step = step(TmdbApiShapes.MOVIE_VIDEOS)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
        assertNull(result.candidate?.fields?.get(ResolvedField.TRAILERS))
    }

    private fun step(shape: String) = ProviderPlanStep(
        apiShapeId = shape,
        provider = MetadataPrimaryProvider.TMDB,
        role = ProviderPlanRole.MEDIA,
        required = false
    )

    private fun movieRoute(
        targetIds: Map<MetadataPrimaryProvider, String> = mapOf(MetadataPrimaryProvider.TMDB to "550")
    ) = MetadataRoute(
        provider = MetadataPrimaryProvider.TMDB,
        parentId = "tmdb:550",
        mediaKind = MetadataMediaKind.MOVIE,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        sourceContext = MetadataSourceContext(),
        language = null,
        targetIds = targetIds,
        trace = emptyList()
    )

    private fun tvRoute(
        language: String? = null,
        seasonNumber: Int? = null,
        targetIds: Map<MetadataPrimaryProvider, String> = mapOf(MetadataPrimaryProvider.TMDB to "1399")
    ) = MetadataRoute(
        provider = MetadataPrimaryProvider.TMDB,
        parentId = "tmdb:1399",
        mediaKind = MetadataMediaKind.SERIES,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        sourceContext = MetadataSourceContext(),
        language = language,
        seasonNumber = seasonNumber,
        targetIds = targetIds,
        trace = emptyList()
    )
}
