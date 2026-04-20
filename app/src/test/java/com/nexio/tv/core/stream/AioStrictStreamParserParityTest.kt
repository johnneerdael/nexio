package com.nexio.tv.core.stream

import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AioStrictStreamParserParityTest {

    @Test
    fun `stream parser merges folder and filename metadata with stream extras`() {
        val stream = stream(
            filename = "Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv",
            description = """
                📁 Movie Title 2023 MULTi
                📄 Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv
                💾 62.5 GB / 💾 70.0 GB 👤 42 📅 3d ⚙️ Torrentio
            """.trimIndent()
        )

        val parsed = AioStrictStreamParser.parse(stream)

        assertEquals("Movie Title 2023 MULTi", parsed.folderName)
        assertEquals("Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv", parsed.filename)
        assertTrue(parsed.languages.contains("Multi"))
        assertEquals(67_108_864_000L, parsed.sizeBytes)
        assertEquals(75_161_927_680L, parsed.folderSizeBytes)
        assertEquals(42, parsed.seeders)
        assertEquals("3d", parsed.age)
        assertEquals(72, parsed.ageHours)
        assertEquals("Torrentio", parsed.indexer)
    }

    @Test
    fun `stream parser extracts bitrate and duration from description`() {
        val stream = stream(
            filename = "Show.S01E02.1080p.WEB-DL.H264-GROUP.mkv",
            description = "📄 Show.S01E02.1080p.WEB-DL.H264-GROUP.mkv\n12.5 Mbps • 2h 32m 0s"
        )

        val parsed = AioStrictStreamParser.parse(stream)

        assertEquals(12_500_000L, parsed.bitrate)
        assertEquals(9_120_000L, parsed.durationMs)
    }

    @Test
    fun `stream parser extracts binary size units from description`() {
        val stream = stream(
            filename = "Movie.Title.2023.2160p.WEB-DL.H264-GROUP.mkv",
            description = """
                📄 Movie.Title.2023.2160p.WEB-DL.H264-GROUP.mkv
                💾 72 GiB
            """.trimIndent()
        )

        val parsed = AioStrictStreamParser.parse(stream.copy(behaviorHints = stream.behaviorHints?.copy(filename = null)))

        assertEquals(72L * 1024L * 1024L * 1024L, parsed.sizeBytes)
    }

    @Test
    fun `stream parser cleans filename candidates like upstream aio`() {
        val stream = stream(
            filename = "",
            description = """
                Torrentio: 🎬 Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv
                💾 62.5 GB
            """.trimIndent()
        )

        val parsed = AioStrictStreamParser.parse(stream.copy(behaviorHints = stream.behaviorHints?.copy(filename = null)))

        assertEquals("Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv", parsed.filename)
        assertEquals("Movie Title", parsed.title)
    }

    @Test
    fun `stream parser defaults matched debrid service to uncached when no cache marker exists`() {
        val stream = stream(
            filename = "Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv",
            description = "Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv",
            name = "[RD] Torrentio"
        )

        val parsed = AioStrictStreamParser.parse(stream)

        assertEquals("RD", parsed.serviceId)
        assertEquals(false, parsed.isCached)
        assertEquals(StreamTransportKind.UNCACHED, parsed.transportKind)
    }

    @Test
    fun `stream parser treats direct real debrid download urls as cached debrid streams`() {
        val stream = stream(
            filename = "Avatar.Fire.and.Ash.2025.MULTi.VF2.2160p.HDR.DV.WEB-DL.Dolby.Atmos.7.1.H265-Slay3R.mkv",
            description = "38.9 GB 2160p",
            name = "Debrid",
            url = "https://45-4.download.real-debrid.com/d/RCFFKFRWG3THS/video.mkv"
        )

        val parsed = AioStrictStreamParser.parse(stream)

        assertEquals("RD", parsed.serviceId)
        assertEquals(true, parsed.isCached)
        assertEquals(StreamTransportKind.CACHED, parsed.transportKind)
    }

    @Test
    fun `stream parser trusts wrapped provider id over debrid text matches`() {
        val stream = stream(
            filename = "Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv",
            description = """
                📄 Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv
                🔍 TorBox
            """.trimIndent(),
            name = "TorBox",
            wrappedProviderId = "RD"
        )

        val parsed = AioStrictStreamParser.parse(stream)

        assertEquals("RD", parsed.serviceId)
        assertEquals(false, parsed.isCached)
        assertEquals(StreamTransportKind.UNCACHED, parsed.transportKind)
    }

    @Test
    fun `stream parser uses aio style release group extraction`() {
        val parsed = AioStrictFileParser.parse("Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP[rarbg].mkv")

        assertEquals("GROUP", parsed.releaseGroup)
    }

    @Test
    fun `stream parser keeps season pack from richer folder parse`() {
        val stream = stream(
            filename = "Show.1080p.WEB-DL.H264-GROUP.mkv",
            description = "📁 Show.S03.1080p.WEB-DL.MULTi\n📄 Show.1080p.WEB-DL.H264-GROUP.mkv"
        )

        val parsed = AioStrictStreamParser.parse(stream)

        assertEquals(listOf(3), parsed.seasons)
        assertTrue(parsed.episodes.isEmpty())
        assertEquals(true, parsed.seasonPack)
        assertEquals(listOf("Multi"), parsed.languages)
    }

    @Test
    fun `stremthru parser uses package size indexer and private marker`() {
        val stream = stream(
            filename = "",
            description = """
                📁 Movie Title 2023
                📄 Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv
                📦 70.0 GB
                🔍 DMM-Cast
            """.trimIndent(),
            name = "🔑 ⚡ RD",
            preset = AddonParserPreset.STREMTHRU
        )

        val parsed = AioStrictStreamParser.parse(stream.copy(behaviorHints = stream.behaviorHints?.copy(filename = null)))

        assertEquals(75_161_927_680L, parsed.folderSizeBytes)
        assertEquals("DMM-Cast", parsed.indexer)
        assertEquals(true, parsed.isPrivate)
    }

    @Test
    fun `torrentio parser promotes multi subs languages to subtitles and keeps folder from first line`() {
        val stream = stream(
            filename = "Show.S01E02.1080p.WEB-DL.H264-GROUP.mkv",
            description = """
                Show Season 1
                Show.S01E02.1080p.WEB-DL.H264-GROUP.mkv
                Multi Subs EN FR IT
            """.trimIndent(),
            name = "⚡ RD",
            preset = AddonParserPreset.TORRENTIO
        )

        val parsed = AioStrictStreamParser.parse(stream)

        assertEquals("Show Season 1", parsed.folderName)
        assertTrue(parsed.languages.isEmpty())
        assertEquals(listOf("English", "French", "Italian"), parsed.subtitles)
    }

    @Test
    fun `webstreamr parser normalizes resolution and extracts warning message`() {
        val stream = stream(
            filename = "",
            description = """
                War.Machine.2026.REPACK.MULTI.WEB.H264
                ⚠️ slow source
                🔗 StreamHub
            """.trimIndent(),
            name = "WebStreamr 1040p",
            preset = AddonParserPreset.WEBSTREAMR
        )

        val parsed = AioStrictStreamParser.parse(stream.copy(behaviorHints = stream.behaviorHints?.copy(filename = null)))

        assertEquals("1080p", parsed.resolution)
        assertEquals("slow source", parsed.message)
        assertEquals("StreamHub", parsed.indexer)
    }

    private fun stream(
        filename: String,
        description: String,
        name: String? = null,
        preset: AddonParserPreset = AddonParserPreset.GENERIC,
        wrappedProviderId: String? = null,
        url: String = "https://example.com/video.mkv"
    ): Stream {
        return Stream(
            name = name,
            title = null,
            description = description,
            url = url,
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
                filename = filename
            ),
            addonName = "Test Addon",
            addonLogo = null,
            addonParserPreset = preset,
            wrappedProviderId = wrappedProviderId
        )
    }
}
