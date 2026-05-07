package com.nexio.tv.core.metadata.router.resolver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.media.ClipSite
import com.nexio.tv.core.media.Confidence
import com.nexio.tv.core.media.ContentIdentity
import com.nexio.tv.core.media.MediaClipCandidate
import com.nexio.tv.core.media.MediaClipPlaybackRef
import com.nexio.tv.core.media.MediaClipScope
import com.nexio.tv.core.media.MediaClipSource
import com.nexio.tv.core.media.MediaClipStore
import com.nexio.tv.core.media.MediaClipType
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TrailerResolverMediaClipStoreTest {
    private lateinit var context: Context
    private lateinit var store: MediaClipStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefsName = "trailer_resolver_media_clip_test_${System.nanoTime()}"
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        store = MediaClipStore(context, prefsName = prefsName, clock = { 1_000_000L })
    }

    @Test
    fun `trailer resolver uses cached media clip for screensaver item with stable ids and no trailer ids`() {
        val stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523")
        val identity = ContentIdentity(
            contentId = "tmdb:550",
            itemType = "movie",
            stableIds = stableIds
        )
        store.storeCandidates(
            listOf(
                MediaClipCandidate(
                    clipId = "tmdb:movie:550:cached",
                    contentId = identity,
                    provider = "TMDB",
                    source = MediaClipSource.PROVIDER,
                    scope = MediaClipScope.Title(identity),
                    clipType = MediaClipType.TRAILER,
                    title = "Cached Trailer",
                    language = "en",
                    site = ClipSite.YOUTUBE,
                    externalVideoId = "cached",
                    playbackRef = MediaClipPlaybackRef.YouTubeId("cached"),
                    confidence = Confidence.HIGH,
                    sourceTrace = listOf("tmdb.movie.videos"),
                    fetchedAtMs = 1_000_000L
                )
            )
        )
        val resolver = TrailerResolver(
            traceEvents = TraceMetadataEvents(RecordingTraceSink(), sessionId = { "s1" }),
            mediaClipStore = store
        )

        val resolution = resolver.resolveTrailer(
            TrailerResolveRequest(
                itemKey = "movie:tmdb:550",
                title = "Fight Club",
                year = "1999",
                stableIds = stableIds,
                fallbackYtIds = emptyList(),
                surface = TrailerSurface.SCREENSAVER,
                type = "movie",
                contentId = "tmdb:550"
            )
        )

        assertTrue(resolution.availability.available)
        assertEquals("media_clip_cache_hit", resolution.availability.reason)
        assertEquals(TrailerPlaybackRef.YouTubeId("cached"), resolution.selected)
    }
}
