package com.nexio.tv.data.integration.metadata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.media.ContentIdentity
import com.nexio.tv.core.media.MediaClipScope
import com.nexio.tv.core.media.MediaClipStore
import com.nexio.tv.core.media.MediaClipType
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ProviderPlanRole
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.tvdb.TvdbTrailerLookupResult
import com.nexio.tv.core.tvdb.TvdbTrailerResolver
import com.nexio.tv.domain.model.ProviderIds
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TvdbTrailerMetadataAdapterTest {
    @Test
    fun `TV_TRAILERS stores TVDB title trailer candidate in media clip store`() = runTest {
        val resolver = mockk<TvdbTrailerResolver>()
        coEvery {
            resolver.resolveTitleTrailer(
                contentId = "tvdb:81189",
                type = "tv",
                title = null,
                year = null
            )
        } returns TvdbTrailerLookupResult.ResolvedYouTube(
            youtubeUrl = "https://www.youtube.com/watch?v=abc123",
            videoId = "abc123"
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = MediaClipStore(
            context = context,
            prefsName = "tvdb_trailer_adapter_media_clip_${System.nanoTime()}",
            clock = { 1_000_000L }
        )
        val adapter = TvdbTrailerMetadataAdapter(resolver, mediaClipStore = store)

        val result = adapter.execute(
            route = tvRoute(),
            step = step()
        )

        assertEquals(
            listOf("https://www.youtube.com/watch?v=abc123"),
            result.candidate?.fields?.get(ResolvedField.TRAILERS)?.value
        )
        val identity = ContentIdentity(
            contentId = "tvdb:81189",
            itemType = "series",
            stableIds = ProviderIds(tvdb = "81189")
        )
        val clips = store.getCandidates(
            identity = identity,
            scope = MediaClipScope.Title(identity),
            clipTypes = setOf(MediaClipType.TRAILER),
            language = "en"
        )
        assertEquals(1, clips.size)
        assertEquals("TVDB", clips.single().provider)
        assertEquals("abc123", clips.single().externalVideoId)
    }

    @Test
    fun `TV_TRAILERS does not store title trailer candidate for movie routes`() = runTest {
        val resolver = mockk<TvdbTrailerResolver>(relaxed = true)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = MediaClipStore(
            context = context,
            prefsName = "tvdb_trailer_adapter_movie_media_clip_${System.nanoTime()}",
            clock = { 1_000_000L }
        )
        val adapter = TvdbTrailerMetadataAdapter(resolver, mediaClipStore = store)

        val result = adapter.execute(
            route = tvRoute(mediaKind = MetadataMediaKind.MOVIE),
            step = step()
        )

        assertTrue(result.candidate?.fields.isNullOrEmpty())
        val identity = ContentIdentity(
            contentId = "tvdb:81189",
            itemType = "series",
            stableIds = ProviderIds(tvdb = "81189")
        )
        val clips = store.getCandidates(
            identity = identity,
            scope = MediaClipScope.Title(identity),
            clipTypes = setOf(MediaClipType.TRAILER),
            language = "en"
        )
        assertTrue(clips.isEmpty())
    }

    private fun step() = ProviderPlanStep(
        apiShapeId = TvdbApiShapes.TV_TRAILERS,
        provider = MetadataPrimaryProvider.TVDB,
        role = ProviderPlanRole.MEDIA,
        required = false
    )

    private fun tvRoute(
        mediaKind: MetadataMediaKind = MetadataMediaKind.SERIES
    ) = MetadataRoute(
        provider = MetadataPrimaryProvider.TVDB,
        parentId = "tvdb:81189",
        mediaKind = mediaKind,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        sourceContext = MetadataSourceContext(itemType = "series"),
        language = "en",
        targetIds = mapOf(MetadataPrimaryProvider.TVDB to "81189"),
        trace = emptyList()
    )
}
