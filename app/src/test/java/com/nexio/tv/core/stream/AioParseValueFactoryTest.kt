package com.nexio.tv.core.stream

import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AioParseValueFactoryTest {

    @Test
    fun `movie stream exposes aio parse contract fields`() {
        val stream = stream(
            filename = "Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.mkv",
            name = "⚡ RD",
            description = "Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.mkv",
            bingeGroup = "show:movie-title",
            videoHash = "abc123hash"
        )
        val parsed = AioStrictStreamParser.parse(stream)

        val parseValue = AioParseValueFactory.from(stream, parsed, StreamRequestContext(contentType = "movie"))

        assertEquals("Movie Title", parseValue.stream.title)
        assertEquals("2023", parseValue.stream.year)
        assertEquals("2160p", parseValue.stream.resolution)
        assertEquals("BluRay", parseValue.stream.quality)
        assertEquals(listOf("DV"), parseValue.stream.visualTags)
        assertEquals(listOf("Atmos", "TrueHD"), parseValue.stream.audioTags)
        assertEquals(listOf("7.1"), parseValue.stream.audioChannels)
        assertEquals("abc123hash", parseValue.stream.videoHash)
        assertEquals("show:movie-title", parseValue.stream.bingeGroup)
        assertEquals(10L * 1024L * 1024L * 1024L, parseValue.stream.size)
        assertEquals("RD", parseValue.service.id)
        assertEquals(true, parseValue.service.cached)
        assertTrue(parseValue.debug.json?.contains("\"videoHash\":\"abc123hash\"") == true)
        assertTrue(parseValue.debug.json?.contains("\"bingeGroup\":\"show:movie-title\"") == true)
        assertTrue(parseValue.debug.json?.contains("\"size\":10737418240") == true)
    }

    @Test
    fun `episode stream exposes season episode formatting fields`() {
        val stream = stream(
            filename = "Shrinking.S03E06.Dereks.Dont.Die.1080p.ATVP.WEB-DL.DDP5.1.Atmos.ENG.ITA.H264-TheShrink.mkv"
        )
        val parsed = AioStrictStreamParser.parse(stream)

        val parseValue = AioParseValueFactory.from(stream, parsed, StreamRequestContext(contentType = "series", season = 3, episode = 6))

        assertEquals(listOf(3), parseValue.stream.seasons)
        assertEquals(listOf(6), parseValue.stream.episodes)
        assertEquals("S03", parseValue.stream.formattedSeasons)
        assertEquals("E06", parseValue.stream.formattedEpisodes)
        assertEquals(listOf("S03", "E06"), parseValue.stream.seasonEpisode)
    }

    @Test
    fun `debrid streams resolve debrid type and service names`() {
        val stream = stream(
            filename = "Show.S01E02.1080p.WEB-DL.x265.Group.mkv",
            name = "[PM+] Torrentio"
        )
        val parsed = AioStrictStreamParser.parse(stream)

        val parseValue = AioParseValueFactory.from(stream, parsed)

        assertEquals("debrid", parseValue.stream.type)
        assertEquals("PM", parseValue.service.id)
        assertEquals("Premiumize", parseValue.service.name)
        assertEquals(true, parseValue.service.cached)
    }

    @Test
    fun `parse value fallback trusts wrapped provider id over stream text`() {
        val stream = stream(
            filename = "Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv",
            name = "TorBox",
            description = """
                📄 Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv
                🔍 TorBox
            """.trimIndent(),
            wrappedProviderId = "PM"
        )
        val parsed = AioStrictStreamParser.parse(stream).copy(serviceId = null)

        val parseValue = AioParseValueFactory.from(stream, parsed)

        assertEquals("PM", parseValue.service.id)
        assertEquals("Premiumize", parseValue.service.name)
        assertEquals("debrid", parseValue.stream.type)
    }

    @Test
    fun `parse value exposes richer stream metadata fields`() {
        val stream = stream(
            filename = "Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv",
            description = """
                📁 Movie Title 2023 MULTi
                📄 Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv
                💾 62.5 GB / 💾 70.0 GB 👤 42 📅 3d ⚙️ Torrentio
                12.5 Mbps • 2h 32m 0s
            """.trimIndent()
        )
        val parsed = AioStrictStreamParser.parse(stream)

        val parseValue = AioParseValueFactory.from(stream, parsed)

        assertEquals("Movie Title 2023 MULTi", parseValue.stream.folderName)
        assertEquals(70L * 1024L * 1024L * 1024L, parseValue.stream.folderSize)
        assertEquals(12_500_000L, parseValue.stream.bitrate)
        assertEquals(42, parseValue.stream.seeders)
        assertEquals("3d", parseValue.stream.age)
        assertEquals(72, parseValue.stream.ageHours)
        assertEquals("Torrentio", parseValue.stream.indexer)
        assertEquals(9_120_000L, parseValue.stream.duration)
    }

    @Test
    fun `parse value falls back to request runtime when stream text has no duration`() {
        val stream = stream(
            filename = "Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv",
            description = "Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv"
        )
        val parsed = AioStrictStreamParser.parse(stream)

        val parseValue = AioParseValueFactory.from(
            stream,
            parsed,
            StreamRequestContext(contentType = "movie", runtimeMinutes = 152)
        )

        assertEquals(9_120_000L, parseValue.stream.duration)
        assertEquals(152, parseValue.metadata.runtime)
        assertEquals(152, parseValue.metadata.episodeRuntime)
    }

    @Test
    fun `edge case stream leaves optional fields empty when unavailable`() {
        val stream = Stream(
            name = "Unknown link",
            title = null,
            description = "Just a stream",
            url = "https://example.com/video",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                videoHash = null,
                videoSize = null,
                filename = null
            ),
            addonName = "Test Addon",
            addonLogo = null,
            addonParserPreset = AddonParserPreset.GENERIC
        )
        val parsed = AioStrictStreamParser.parse(stream)

        val parseValue = AioParseValueFactory.from(stream, parsed)

        assertNull(parseValue.stream.resolution)
        assertNull(parseValue.stream.quality)
        assertEquals("Just A Stream", parseValue.stream.title)
        assertEquals("http", parseValue.stream.type)
        assertTrue(parseValue.stream.rseMatched.isEmpty())
    }

    private fun stream(
        filename: String,
        name: String? = null,
        description: String? = filename,
        bingeGroup: String? = null,
        videoHash: String? = null,
        wrappedProviderId: String? = null
    ): Stream {
        return Stream(
            name = name,
            title = null,
            description = description,
            url = "https://example.com/video.mkv",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = bingeGroup,
                countryWhitelist = null,
                proxyHeaders = null,
                videoHash = videoHash,
                videoSize = 10L * 1024L * 1024L * 1024L,
                filename = filename
            ),
            addonName = "Test Addon",
            addonLogo = null,
            addonParserPreset = AddonParserPreset.GENERIC,
            wrappedProviderId = wrappedProviderId
        )
    }
}
