package com.nexio.tv.core.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MediaClipStoreTest {
    private lateinit var context: Context
    private lateinit var prefsName: String
    private var nowMs: Long = 1_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefsName = "media_clip_store_test_${System.nanoTime()}"
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `media clip store persists youtube id after restart`() {
        val identity = tmdbMovieIdentity()
        val firstStore = MediaClipStore(context, prefsName = prefsName, clock = { nowMs })

        firstStore.storeCandidates(
            listOf(
                MediaClipCandidate(
                    clipId = "tmdb:movie:550:abc123",
                    contentId = identity,
                    provider = "TMDB",
                    source = MediaClipSource.PROVIDER,
                    scope = MediaClipScope.Title(identity),
                    clipType = MediaClipType.TRAILER,
                    title = "Official Trailer",
                    language = "en",
                    site = ClipSite.YOUTUBE,
                    externalVideoId = "abc123",
                    playbackRef = MediaClipPlaybackRef.YouTubeId("abc123"),
                    confidence = Confidence.HIGH,
                    sourceTrace = listOf("tmdb.movie.videos"),
                    fetchedAtMs = nowMs
                )
            )
        )

        val restartedStore = MediaClipStore(context, prefsName = prefsName, clock = { nowMs + 10L })
        val clips = restartedStore.getCandidates(
            identity = identity,
            scope = MediaClipScope.Title(identity),
            clipTypes = setOf(MediaClipType.TRAILER),
            language = "en"
        )

        assertEquals(1, clips.size)
        assertEquals("abc123", clips.single().externalVideoId)
        assertEquals(MediaClipPlaybackRef.YouTubeId("abc123"), clips.single().playbackRef)
    }

    @Test
    fun `media clip store does not store expired playback url as durable candidate`() {
        val identity = tmdbMovieIdentity()
        val store = MediaClipStore(context, prefsName = prefsName, clock = { nowMs })

        store.storeCandidates(
            listOf(
                MediaClipCandidate(
                    clipId = "tmdb:movie:550:resolved",
                    contentId = identity,
                    provider = "TMDB",
                    source = MediaClipSource.PROVIDER,
                    scope = MediaClipScope.Title(identity),
                    clipType = MediaClipType.TRAILER,
                    title = "Resolved Stream",
                    language = "en",
                    site = ClipSite.PROVIDER,
                    externalVideoId = null,
                    playbackRef = MediaClipPlaybackRef.ResolvedPlaybackUri(
                        uri = "https://video.example.test/expired.m3u8",
                        expiresAtMs = nowMs + 5_000L
                    ),
                    confidence = Confidence.MEDIUM,
                    sourceTrace = listOf("youtube.playback.resolve"),
                    fetchedAtMs = nowMs
                )
            )
        )

        val clips = store.getCandidates(
            identity = identity,
            scope = MediaClipScope.Title(identity),
            clipTypes = setOf(MediaClipType.TRAILER),
            language = "en"
        )

        assertEquals(1, clips.size)
        assertNull(clips.single().playbackRef)
        assertNull(clips.single().providerUrlHash)
        assertNull(clips.single().redactedUrl)
    }

    @Test
    fun `media clip store stale candidate used when provider unavailable`() {
        val identity = tmdbMovieIdentity()
        val store = MediaClipStore(context, prefsName = prefsName, clock = { nowMs })
        store.storeCandidates(
            candidates = listOf(
                MediaClipCandidate(
                    clipId = "tmdb:movie:550:stale",
                    contentId = identity,
                    provider = "TMDB",
                    source = MediaClipSource.PROVIDER,
                    scope = MediaClipScope.Title(identity),
                    clipType = MediaClipType.TRAILER,
                    title = null,
                    language = "en",
                    site = ClipSite.YOUTUBE,
                    externalVideoId = "stale",
                    playbackRef = MediaClipPlaybackRef.YouTubeId("stale"),
                    confidence = Confidence.MEDIUM,
                    sourceTrace = listOf("tmdb.movie.videos"),
                    fetchedAtMs = nowMs
                )
            ),
            freshTtlMs = 100L,
            staleTtlMs = 1_000L
        )

        nowMs += 500L

        val clips = store.getCandidates(
            identity = identity,
            scope = MediaClipScope.Title(identity),
            clipTypes = setOf(MediaClipType.TRAILER),
            language = "en",
            includeStale = true
        )

        assertEquals("stale", clips.single().externalVideoId)
        assertEquals(CacheDecision.STALE_HIT, clips.single().cacheDecision)
    }

    private fun tmdbMovieIdentity(): ContentIdentity =
        ContentIdentity(
            contentId = "tmdb:550",
            itemType = "movie",
            stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523")
        )
}
