package com.nexio.tv.data.trailer

import com.nexio.tv.data.remote.api.TmdbVideoResult
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerSupportTest {

    @Test
    fun `selectPreferredCombinedTrailerUrl prefers manifest for playback compatibility`() {
        val selected = selectPreferredCombinedTrailerUrl(
            manifestUrl = "https://example.com/trailer/master.m3u8",
            progressiveUrl = "https://example.com/trailer/video.webm"
        )

        assertEquals("https://example.com/trailer/master.m3u8", selected)
    }

    @Test
    fun `sortTrailerCandidatesForPlayback prefers mp4 over higher spec webm`() {
        val sorted = sortTrailerCandidatesForPlayback(
            listOf(
                StreamCandidate(
                    client = "android_vr",
                    priority = 0,
                    url = "https://example.com/trailer/video.webm",
                    score = 1080_060_000.0,
                    hasN = false,
                    itag = "303",
                    height = 1080,
                    fps = 60,
                    ext = "webm"
                ),
                StreamCandidate(
                    client = "ios",
                    priority = 1,
                    url = "https://example.com/trailer/video.mp4",
                    score = 720_030_000.0,
                    hasN = false,
                    itag = "22",
                    height = 720,
                    fps = 30,
                    ext = "mp4"
                )
            )
        )

        assertEquals("https://example.com/trailer/video.mp4", sorted.first().url)
    }

    @Test
    fun `selectPreferredTrailerPlaybackSource prefers combined trailer source over split adaptive playback`() {
        val selected = selectPreferredTrailerPlaybackSource(
            combinedUrl = "https://example.com/trailer/master.m3u8",
            adaptiveVideoUrl = "https://example.com/trailer/video.mp4",
            adaptiveAudioUrl = "https://example.com/trailer/audio.m4a"
        )

        assertEquals("https://example.com/trailer/master.m3u8", selected?.videoUrl)
        assertNull(selected?.audioUrl)
    }

    @Test
    fun `selectPreferredTrailerPlaybackSource falls back to adaptive split playback when combined source is absent`() {
        val selected = selectPreferredTrailerPlaybackSource(
            combinedUrl = null,
            adaptiveVideoUrl = "https://example.com/trailer/video.mp4",
            adaptiveAudioUrl = "https://example.com/trailer/audio.m4a"
        )

        assertEquals("https://example.com/trailer/video.mp4", selected?.videoUrl)
        assertEquals("https://example.com/trailer/audio.m4a", selected?.audioUrl)
    }

    @Test
    fun `rankTmdbVideoCandidates prefers official trailers before teasers and smaller videos`() {
        val ranked = rankTmdbVideoCandidates(
            listOf(
                tmdbVideo(
                    key = "teaser12345a",
                    type = "Teaser",
                    official = true,
                    size = 2160,
                    publishedAt = "2024-01-01T00:00:00Z"
                ),
                tmdbVideo(
                    key = "smalltrailer",
                    type = "Trailer",
                    official = true,
                    size = 720,
                    publishedAt = "2024-03-01T00:00:00Z"
                ),
                tmdbVideo(
                    key = "besttrailer1",
                    type = "Trailer",
                    official = true,
                    size = 1080,
                    publishedAt = "2024-04-01T00:00:00Z"
                ),
                tmdbVideo(
                    key = "unofficial11",
                    type = "Trailer",
                    official = false,
                    size = 2160,
                    publishedAt = "2024-05-01T00:00:00Z"
                )
            )
        )

        assertEquals("besttrailer1", ranked[0].key)
        assertEquals("smalltrailer", ranked[1].key)
        assertEquals("unofficial11", ranked[2].key)
        assertEquals("teaser12345a", ranked[3].key)
    }

    @Test
    fun `selectStreailerTrailerCandidate prefers explicit trailer streams and ignores recaps`() {
        val candidate = selectStreailerTrailerCandidate(
            listOf(
                streailerStream(
                    name = "📝 Recap Season 1",
                    ytId = "recapvideo1",
                    bingeGroup = "recap"
                ),
                streailerStream(
                    name = "🎬 Trailer",
                    ytId = "trailerabc1",
                    bingeGroup = "trailer"
                )
            )
        )

        assertEquals("trailerabc1", candidate?.youtubeId)
        assertNull(candidate?.externalUrl)
    }

    @Test
    fun `selectStreailerTrailerCandidate falls back to youtube external links when present`() {
        val candidate = selectStreailerTrailerCandidate(
            listOf(
                streailerStream(
                    name = "🔗 🎬 Trailer",
                    externalUrl = "https://www.youtube.com/watch?v=extvideo123",
                    bingeGroup = "trailer"
                )
            )
        )

        assertEquals("https://www.youtube.com/watch?v=extvideo123", candidate?.externalUrl)
    }

    @Test
    fun `extractYouTubeVideoId supports direct ids short urls and watch urls`() {
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10")
        )
    }

    private fun tmdbVideo(
        key: String,
        type: String,
        official: Boolean,
        size: Int,
        publishedAt: String
    ): TmdbVideoResult {
        return TmdbVideoResult(
            key = key,
            site = "YouTube",
            type = type,
            official = official,
            size = size,
            publishedAt = publishedAt
        )
    }

    private fun streailerStream(
        name: String,
        ytId: String? = null,
        externalUrl: String? = null,
        bingeGroup: String? = null
    ): Stream {
        return Stream(
            name = name,
            title = name,
            description = null,
            url = null,
            ytId = ytId,
            infoHash = null,
            fileIdx = null,
            externalUrl = externalUrl,
            behaviorHints = StreamBehaviorHints(
                notWebReady = true,
                bingeGroup = bingeGroup,
                countryWhitelist = null,
                proxyHeaders = null
            ),
            addonName = "Streailer",
            addonLogo = null,
            addonParserPreset = AddonParserPreset.GENERIC
        )
    }
}
