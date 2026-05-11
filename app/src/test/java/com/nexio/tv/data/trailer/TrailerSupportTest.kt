package com.nexio.tv.data.trailer

import com.nexio.tv.data.remote.api.TmdbVideoResult
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerSupportTest {

    @Test
    fun `extractDefaultYouTubeAudioLanguageCode reads default audio track language`() {
        val playerResponse = mapOf(
            "captions" to mapOf(
                "playerCaptionsTracklistRenderer" to mapOf(
                    "captionTracks" to listOf(
                        mapOf("languageCode" to "hi"),
                        mapOf("languageCode" to "en-US")
                    ),
                    "audioTracks" to listOf(
                        mapOf("captionTrackIndices" to listOf(0))
                    ),
                    "defaultAudioTrackIndex" to 0
                )
            )
        )

        val languageCode = extractDefaultYouTubeAudioLanguageCode(playerResponse)

        assertEquals("hi", languageCode)
    }

    @Test
    fun `extractDefaultYouTubeAudioLanguageCode falls back to single caption track`() {
        val playerResponse = mapOf(
            "captions" to mapOf(
                "playerCaptionsTracklistRenderer" to mapOf(
                    "captionTracks" to listOf(
                        mapOf("languageCode" to "en-GB")
                    )
                )
            )
        )

        val languageCode = extractDefaultYouTubeAudioLanguageCode(playerResponse)

        assertEquals("en-GB", languageCode)
    }

    @Test
    fun `isEnglishYouTubeLanguageCode only accepts english variants`() {
        assertTrue(isEnglishYouTubeLanguageCode("en"))
        assertTrue(isEnglishYouTubeLanguageCode("en-US"))
        assertTrue(isEnglishYouTubeLanguageCode("en_GB"))
        assertTrue(!isEnglishYouTubeLanguageCode("hi"))
        assertTrue(!isEnglishYouTubeLanguageCode("fr"))
        assertTrue(!isEnglishYouTubeLanguageCode(null))
    }

    @Test
    fun `isYouTubeTrailerLanguageAcceptable allows english regardless of original language`() {
        assertTrue(isYouTubeTrailerLanguageAcceptable("en", null))
        assertTrue(isYouTubeTrailerLanguageAcceptable("en-US", "ko"))
    }

    @Test
    fun `isYouTubeTrailerLanguageAcceptable allows trailers matching original language`() {
        assertTrue(isYouTubeTrailerLanguageAcceptable("ko", "ko"))
        assertTrue(isYouTubeTrailerLanguageAcceptable("ko-KR", "ko"))
        assertTrue(isYouTubeTrailerLanguageAcceptable("ko", "KO-KR"))
        assertTrue(isYouTubeTrailerLanguageAcceptable("ja_JP", "ja"))
    }

    @Test
    fun `isYouTubeTrailerLanguageAcceptable rejects mismatched non-english trailers`() {
        assertTrue(!isYouTubeTrailerLanguageAcceptable("ko", "ja"))
        assertTrue(!isYouTubeTrailerLanguageAcceptable("ko", null))
        assertTrue(!isYouTubeTrailerLanguageAcceptable("ko", ""))
    }

    @Test
    fun `iOS profile headers omit web origin and referer`() {
        val headers = buildYouTubeRequestHeaders(
            profile = YouTubeRequestProfile.IOS,
            userAgent = "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)"
        )
        assertEquals(false, headers.containsKey("origin"))
        assertEquals(false, headers.containsKey("referer"))
        assertEquals(false, headers.containsKey("accept-language"))
        assertEquals(
            "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)",
            headers["user-agent"]
        )
        assertEquals("2", headers["x-goog-api-format-version"])
    }

    @Test
    fun `Android profile headers omit web origin and referer`() {
        val headers = buildYouTubeRequestHeaders(
            profile = YouTubeRequestProfile.ANDROID,
            userAgent = "com.google.android.youtube/21.03.36 (Linux; U; Android 15; US) gzip"
        )
        assertEquals(false, headers.containsKey("origin"))
        assertEquals(false, headers.containsKey("referer"))
        assertEquals("2", headers["x-goog-api-format-version"])
    }

    @Test
    fun `Web profile headers preserve existing web fingerprint`() {
        val headers = buildYouTubeRequestHeaders(profile = YouTubeRequestProfile.WEB)
        assertEquals("https://www.youtube.com", headers["origin"])
        assertEquals("https://www.youtube.com/", headers["referer"])
        assertEquals("en-US,en;q=0.9", headers["accept-language"])
        assertEquals(false, headers.containsKey("x-goog-api-format-version"))
    }

    @Test
    fun `Profile headers include cookie when provided`() {
        val headers = buildYouTubeRequestHeaders(
            profile = YouTubeRequestProfile.IOS,
            userAgent = "ios-ua",
            cookieHeader = "SID=abc; HSID=def"
        )
        assertEquals("SID=abc; HSID=def", headers["Cookie"])
    }

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
                    client = "android",
                    priority = 1,
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
                    priority = 0,
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
    fun `rankTmdbVideoCandidates excludes recaps from normal trailer playback selection`() {
        val ranked = rankTmdbVideoCandidates(
            listOf(
                tmdbVideo(
                    key = "recap1234567",
                    type = "Recap",
                    official = true,
                    size = 1080,
                    publishedAt = "2024-04-01T00:00:00Z"
                ),
                tmdbVideo(
                    key = "trailer12345",
                    type = "Trailer",
                    official = true,
                    size = 720,
                    publishedAt = "2024-03-01T00:00:00Z"
                )
            )
        )

        assertEquals(1, ranked.size)
        assertEquals("trailer12345", ranked.single().key)
    }

    @Test
    fun `rankTmdbVideoCandidates excludes non english trailers`() {
        val ranked = rankTmdbVideoCandidates(
            listOf(
                tmdbVideo(
                    key = "hinditr1111",
                    type = "Trailer",
                    official = true,
                    size = 1080,
                    publishedAt = "2024-04-02T00:00:00Z",
                    iso6391 = "hi"
                ),
                tmdbVideo(
                    key = "english1111a",
                    type = "Trailer",
                    official = true,
                    size = 720,
                    publishedAt = "2024-04-01T00:00:00Z",
                    iso6391 = "en"
                )
            )
        )

        assertEquals(1, ranked.size)
        assertEquals("english1111a", ranked.single().key)
    }

    @Test
    fun `rankTmdbRecapCandidates prefers official recaps over featurettes`() {
        val ranked = rankTmdbRecapCandidates(
            listOf(
                tmdbVideo(
                    key = "featurette11",
                    type = "Featurette",
                    official = true,
                    size = 1080,
                    publishedAt = "2024-03-01T00:00:00Z"
                ),
                tmdbVideo(
                    key = "recap1234567",
                    type = "Recap",
                    official = true,
                    size = 720,
                    publishedAt = "2024-04-01T00:00:00Z"
                )
            )
        )

        assertEquals(1, ranked.size)
        assertEquals("recap1234567", ranked.single().key)
    }

    @Test
    fun `rankTmdbRecapCandidates excludes non english recaps`() {
        val ranked = rankTmdbRecapCandidates(
            listOf(
                tmdbVideo(
                    key = "hindirecap1a",
                    type = "Recap",
                    official = true,
                    size = 1080,
                    publishedAt = "2024-04-02T00:00:00Z",
                    iso6391 = "hi"
                ),
                tmdbVideo(
                    key = "englishrcp1",
                    type = "Recap",
                    official = true,
                    size = 720,
                    publishedAt = "2024-04-01T00:00:00Z",
                    iso6391 = "en"
                )
            )
        )

        assertEquals(1, ranked.size)
        assertEquals("englishrcp1", ranked.single().key)
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
    fun `selectStreailerTrailerCandidate prefers teaser and excludes generic non trailer clips`() {
        val candidate = selectStreailerTrailerCandidate(
            listOf(
                streailerStream(
                    name = "Behind the scenes",
                    ytId = "clipvideo01"
                ),
                streailerStream(
                    name = "Official Teaser",
                    ytId = "teaservid01",
                    bingeGroup = "teaser"
                )
            )
        )

        assertEquals("teaservid01", candidate?.youtubeId)
    }

    @Test
    fun `selectStreailerTrailerCandidate returns null when only generic clips are present`() {
        val candidate = selectStreailerTrailerCandidate(
            listOf(
                streailerStream(
                    name = "Behind the scenes",
                    ytId = "clipvideo01"
                )
            )
        )

        assertNull(candidate)
    }

    @Test
    fun `selectStreailerRecapCandidate only returns recap streams`() {
        val candidate = selectStreailerRecapCandidate(
            listOf(
                streailerStream(
                    name = "🎬 Trailer",
                    ytId = "trailerabc1",
                    bingeGroup = "trailer"
                ),
                streailerStream(
                    name = "📝 Season Recap",
                    ytId = "recapvideo1",
                    bingeGroup = "recap"
                )
            )
        )

        assertEquals("recapvideo1", candidate?.youtubeId)
    }

    @Test
    fun `selectSeasonStreailerTrailerCandidate only returns matching season trailer or teaser`() {
        val candidate = selectSeasonStreailerTrailerCandidate(
            streams = listOf(
                streailerStream(
                    name = "Season 2 Trailer",
                    ytId = "season2trr1",
                    bingeGroup = "trailer"
                ),
                streailerStream(
                    name = "Season 1 Teaser",
                    ytId = "season1tsr1",
                    bingeGroup = "teaser"
                )
            ),
            seasonNumber = 1
        )

        assertEquals("season1tsr1", candidate?.youtubeId)
    }

    @Test
    fun `selectSeasonStreailerRecapCandidate ignores recap for other seasons`() {
        val candidate = selectSeasonStreailerRecapCandidate(
            streams = listOf(
                streailerStream(
                    name = "Season 2 Recap",
                    ytId = "season2rcp1",
                    bingeGroup = "recap"
                )
            ),
            seasonNumber = 1
        )

        assertNull(candidate)
    }

    @Test
    fun `orderedSeasonRecapCandidates uses tmdb recap before streailer recap`() {
        val ordered = orderedSeasonRecapCandidates(
            tmdbResults = listOf(
                tmdbVideo(
                    key = "tmdbrecap01",
                    type = "Recap",
                    official = true,
                    size = 1080,
                    publishedAt = "2024-05-01T00:00:00Z"
                )
            ),
            streailerRecap = StreailerTrailerCandidate(youtubeId = "streailer01")
        )

        assertEquals(2, ordered.size)
        assertTrue(ordered.first() is SeasonMediaCandidate.TmdbYouTube)
        assertEquals("tmdbrecap01", (ordered.first() as SeasonMediaCandidate.TmdbYouTube).youtubeId)
        assertTrue(ordered.last() is SeasonMediaCandidate.Streailer)
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
        publishedAt: String,
        iso6391: String = "en"
    ): TmdbVideoResult {
        return TmdbVideoResult(
            iso6391 = iso6391,
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
