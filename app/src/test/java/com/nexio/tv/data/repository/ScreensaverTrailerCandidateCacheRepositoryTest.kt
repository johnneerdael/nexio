package com.nexio.tv.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.media.MediaClipStore
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.data.remote.api.TmdbVideoResult
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TrailerDisplayState
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ScreensaverTrailerCandidateCacheRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `valid 48 hour refresh gate skips TMDB video fetches`() = runTest {
        val provider = FakeScreensaverTrailerTmdbProvider()
        val mediaClipStore = MediaClipStore(context, prefsName = "valid-gate-${System.nanoTime()}", clock = { 10_000L })
        val repository = ScreensaverTrailerCandidateCacheRepository(
            context = context,
            trailerTmdbProvider = provider,
            mediaClipStore = mediaClipStore,
            clock = { 10_000L },
            stateFileName = "valid-gate-${System.nanoTime()}.json",
            testOnlyConstructor = true
        )
        val item = resolvedItem(tmdbId = "550", type = ContentType.MOVIE)

        repository.testOnlyMarkFresh(profileId = 1, refreshedAtMs = 10_000L - 1_000L)
        val result = repository.ensureFreshTmdbTrendingTrailerCandidates(
            profileId = 1,
            items = listOf(item)
        )

        assertEquals(ScreensaverTrailerCandidateCacheStatus.HIT, result.status)
        assertEquals(0, provider.movieRequests)
        assertEquals(0, provider.tvRequests)
    }

    @Test
    fun `expired gate fetches movie and tv videos and stores youtube ids only`() = runTest {
        val provider = FakeScreensaverTrailerTmdbProvider(
            movieVideos = listOf(tmdbVideo(id = "m1", key = "movieTrailer")),
            tvVideos = listOf(tmdbVideo(id = "t1", key = "tvTrailer"))
        )
        val mediaClipStore = MediaClipStore(context, prefsName = "expired-gate-${System.nanoTime()}", clock = { 200_000L })
        val repository = ScreensaverTrailerCandidateCacheRepository(
            context = context,
            trailerTmdbProvider = provider,
            mediaClipStore = mediaClipStore,
            clock = { 200_000L },
            stateFileName = "expired-gate-${System.nanoTime()}.json",
            testOnlyConstructor = true
        )

        val result = repository.ensureFreshTmdbTrendingTrailerCandidates(
            profileId = 7,
            items = listOf(
                resolvedItem(tmdbId = "550", type = ContentType.MOVIE),
                resolvedItem(tmdbId = "1399", type = ContentType.SERIES)
            )
        )

        assertEquals(ScreensaverTrailerCandidateCacheStatus.REFRESHED, result.status)
        assertEquals(1, provider.movieRequests)
        assertEquals(1, provider.tvRequests)
        assertEquals(listOf("movieTrailer", "tvTrailer"), result.youtubeIds)
        assertTrue(result.playbackRefs.all { it is TrailerPlaybackRef.YouTubeId })
        assertNull(result.extractedVideoUrl)
        assertNull(result.extractedAudioUrl)
    }

    @Test
    fun `refresh failure keeps stale gate result without playback extraction`() = runTest {
        val provider = FakeScreensaverTrailerTmdbProvider(throwOnFetch = true)
        val mediaClipStore = MediaClipStore(context, prefsName = "failure-gate-${System.nanoTime()}", clock = { 500_000L })
        val repository = ScreensaverTrailerCandidateCacheRepository(
            context = context,
            trailerTmdbProvider = provider,
            mediaClipStore = mediaClipStore,
            clock = { 500_000L },
            stateFileName = "failure-gate-${System.nanoTime()}.json",
            testOnlyConstructor = true
        )
        repository.testOnlyMarkFresh(profileId = 3, refreshedAtMs = 1L)

        val result = repository.ensureFreshTmdbTrendingTrailerCandidates(
            profileId = 3,
            items = listOf(resolvedItem(tmdbId = "550", type = ContentType.MOVIE)),
            ttlMs = 1L
        )

        assertEquals(ScreensaverTrailerCandidateCacheStatus.STALE_FALLBACK, result.status)
        assertEquals(1, provider.movieRequests)
        assertTrue(result.youtubeIds.isEmpty())
        assertNull(result.extractedVideoUrl)
    }

    @Test
    fun `legacy obfuscated state file reads typed profile state without refetching`() = runTest {
        val stateFileName = "legacy-obfuscated-gate-${System.nanoTime()}.json"
        File(context.filesDir, stateFileName).writeText(
            """{"a":{"1":{"a":99000,"b":40,"c":499}}}"""
        )
        val provider = FakeScreensaverTrailerTmdbProvider()
        val mediaClipStore = MediaClipStore(
            context,
            prefsName = "legacy-obfuscated-gate-${System.nanoTime()}",
            clock = { 100_000L }
        )
        val repository = ScreensaverTrailerCandidateCacheRepository(
            context = context,
            trailerTmdbProvider = provider,
            mediaClipStore = mediaClipStore,
            clock = { 100_000L },
            stateFileName = stateFileName,
            testOnlyConstructor = true
        )

        val result = repository.ensureFreshTmdbTrendingTrailerCandidates(
            profileId = 1,
            items = listOf(resolvedItem(tmdbId = "550", type = ContentType.MOVIE))
        )

        assertEquals(ScreensaverTrailerCandidateCacheStatus.HIT, result.status)
        assertEquals(99_000L, result.refreshedAtMs)
        assertEquals(0, provider.movieRequests)
        assertEquals(0, provider.tvRequests)
    }

    private fun resolvedItem(tmdbId: String, type: ContentType): ResolvedDisplayItem =
        ResolvedDisplayItem(
            itemKey = "${type.name.lowercase()}:$tmdbId",
            contentId = "tmdb:$tmdbId",
            parentId = "",
            itemType = type,
            mediaKind = if (type == ContentType.MOVIE) MetadataMediaKind.MOVIE else MetadataMediaKind.SERIES,
            canonicalProvider = "tmdb",
            canonicalId = tmdbId,
            imdbId = null,
            stableIds = ProviderIds(tmdb = tmdbId),
            display = ResolvedDisplayFields(
                title = "Title $tmdbId",
                originalTitle = null,
                year = 2024,
                releaseDate = "2024-01-01",
                overview = null,
                genres = emptyList(),
                runtimeText = null
            ),
            artwork = ArtworkBundle(),
            rating = null,
            trailer = TrailerDisplayState(),
            hydrationState = HydrationState.CANONICAL_READY,
            sourceTrace = emptyList(),
            updatedAtMs = 1L
        )

    private fun tmdbVideo(id: String, key: String): TmdbVideoResult =
        TmdbVideoResult(
            id = id,
            key = key,
            name = "Trailer",
            site = "YouTube",
            type = "Trailer",
            official = true,
            iso6391 = "en",
            iso31661 = "US"
        )
}

private class FakeScreensaverTrailerTmdbProvider(
    private val movieVideos: List<TmdbVideoResult> = emptyList(),
    private val tvVideos: List<TmdbVideoResult> = emptyList(),
    private val throwOnFetch: Boolean = false
) : com.nexio.tv.data.integration.trailer.TrailerTmdbVideoProvider {
    var movieRequests: Int = 0
        private set
    var tvRequests: Int = 0
        private set

    override suspend fun getTmdbApiKey(): String? = "tmdb-key"

    override suspend fun fetchMovieVideos(
        tmdbId: Int,
        preferredLanguage: String,
        apiKey: String
    ): List<TmdbVideoResult> {
        movieRequests += 1
        if (throwOnFetch) error("movie fetch failed")
        return movieVideos
    }

    override suspend fun fetchTvVideos(
        tmdbId: Int,
        preferredLanguage: String,
        apiKey: String
    ): List<TmdbVideoResult> {
        tvRequests += 1
        if (throwOnFetch) error("tv fetch failed")
        return tvVideos
    }
}
