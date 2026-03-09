package com.nexio.tv.core.stream

import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPresentationEngineTest {

    @Test
    fun `shrinking filename maps to clean aio style title and details`() {
        val stream = stream(
            filename = "Shrinking.S03E06.Dereks.Dont.Die.1080p.ATVP.WEB-DL.DDP5.1.Atmos.ENG.ITA.H264-TheShrink.mkv",
            name = "⚡ PM"
        )

        val item = organize(stream)

        assertEquals("Shrinking 🎬 S03 - E06 ⚡️ FHD", item.title)
        assertEquals(
            listOf(
                "🖥 WEB-DL | 🎞️ AVC",
                "🎧 Atmos | DD+ | 🔊 5.1",
                "📦 2.7 GB | EN • IT",
                "Shrinking.S03E06.Dereks.Dont.Die.1080p.ATVP.WEB-DL.DDP5.1.Atmos.ENG.ITA.H264-TheShrink.mkv"
            ),
            item.detailLines
        )
    }

    @Test
    fun `shelter filename maps to clean movie title and languages`() {
        val stream = stream(
            filename = "Shelter.2026.MULTi.VFQ.2160p.HDR.WEB-DL.H265-Slay3R.mkv",
            name = "⚡ RD"
        )

        val item = organize(stream)

        assertEquals("Shelter 🎬 2026 ⚡️ 4K", item.title)
        assertEquals(
            listOf(
                "🖥 WEB-DL | 🎞️ HEVC | HDR",
                "📦 11 GB | MULTI • FR",
                "Shelter.2026.MULTi.VFQ.2160p.HDR.WEB-DL.H265-Slay3R.mkv"
            ),
            item.detailLines
        )
    }

    @Test
    fun `uniform formatting removes garbage subtitle line`() {
        val stream = stream(
            filename = "Shrinking.S03E06.Dereks.Dont.Die.1080p.ATVP.WEB-DL.DDP5.1.Atmos.ENG.ITA.H264-TheShrink.mkv",
            name = "⚡ PM",
            description = "PM • DL.DDP5.1.Atmos.ENG.ITA.H264-TheShrink"
        )

        val item = organize(stream)

        assertEquals(null, item.subtitle)
        assertTrue(item.detailLines.none { it.contains("PM • DL") })
    }

    @Test
    fun `torrentio PM plus marker is recognized as cached`() {
        val item = organize(
            stream(
                filename = "Show.S01E02.1080p.WEB-DL.x265.Group.mkv",
                name = "[PM+] Torrentio"
            )
        )

        assertEquals(true, item.parsed.isCached)
    }

    @Test
    fun `download marker overrides PM plus and remains uncached`() {
        val item = organize(
            stream(
                filename = "Show.S01E02.1080p.WEB-DL.x265.Group.mkv",
                name = "[PM+] Torrentio",
                description = "[PM download] TorrentsDB"
            )
        )

        assertEquals(false, item.parsed.isCached)
    }

    @Test
    fun `diagnostics count episode mismatch drops`() {
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(filename = "Show.S01E03.1080p.WEB-DL.x265.mkv")
            ),
            availableAddons = listOf("Test Addon"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(filterEpisodeMismatchStreamsEnabled = true),
            requestContext = StreamRequestContext(
                contentType = "series",
                season = 1,
                episode = 2
            )
        )

        assertEquals(1, result.diagnostics.inputCount)
        assertEquals(1, result.diagnostics.droppedEpisodeMismatchCount)
        assertEquals(0, result.diagnostics.finalPresentedCount)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `diagnostics count movie year mismatch drops`() {
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(filename = "Some.Movie.2023.1080p.BluRay.x264.mkv")
            ),
            availableAddons = listOf("Test Addon"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(filterMovieYearMismatchStreamsEnabled = true),
            requestContext = StreamRequestContext(
                contentType = "movie",
                year = "2024"
            )
        )

        assertEquals(1, result.diagnostics.inputCount)
        assertEquals(1, result.diagnostics.droppedMovieYearMismatchCount)
        assertEquals(0, result.diagnostics.finalPresentedCount)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `diagnostics count web dl dolby vision drops`() {
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(filename = "Some.Movie.2024.2160p.WEB-DL.DV.HEVC.mkv")
            ),
            availableAddons = listOf("Test Addon"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(filterWebDolbyVisionStreamsEnabled = true),
            requestContext = StreamRequestContext(contentType = "movie")
        )

        assertEquals(1, result.diagnostics.inputCount)
        assertEquals(1, result.diagnostics.droppedWebDolbyVisionCount)
        assertEquals(0, result.diagnostics.finalPresentedCount)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `diagnostics count grouped dedupe drops`() {
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(
                    filename = "Show.S01E02.1080p.WEB-DL.x265.Group.mkv",
                    addonName = "Addon A",
                    infoHash = "abc123"
                ),
                stream(
                    filename = "Show.S01E02.1080p.WEB-DL.x265.Group.mkv",
                    addonName = "Addon B",
                    infoHash = "abc123"
                )
            ),
            availableAddons = listOf("Addon A", "Addon B"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(
                groupAcrossAddonsEnabled = true,
                deduplicateGroupedStreamsEnabled = true
            ),
            requestContext = StreamRequestContext(contentType = "series", season = 1, episode = 2)
        )

        assertEquals(2, result.diagnostics.inputCount)
        assertEquals(1, result.diagnostics.droppedDeduplicateCount)
        assertEquals(1, result.diagnostics.finalPresentedCount)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `dedupe keeps cached over uncached when duplicate cluster contains both`() {
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(
                    filename = "Show.S01E02.1080p.WEB-DL.x265.Group.mkv",
                    addonName = "Addon A",
                    infoHash = "abc123",
                    name = "⚡ RD"
                ),
                stream(
                    filename = "Show.S01E02.1080p.WEB-DL.x265.Group.mkv",
                    addonName = "Addon B",
                    infoHash = "abc123",
                    name = "download RD"
                )
            ),
            availableAddons = listOf("Addon A", "Addon B"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(
                groupAcrossAddonsEnabled = true,
                deduplicateGroupedStreamsEnabled = true
            ),
            requestContext = StreamRequestContext(contentType = "series", season = 1, episode = 2)
        )

        assertEquals(1, result.items.size)
        assertEquals(true, result.items.single().parsed.isCached)
        assertEquals(1, result.diagnostics.dedupeMixedCachedUncachedClusterCount)
        assertEquals(0, result.diagnostics.dedupeCachedDroppedForUncachedClusterCount)
    }

    @Test
    fun `dedupe keeps distinct cached releases from same service`() {
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(
                    filename = "Show.S01E02.1080p.WEB-DL.x265.GroupA.mkv",
                    addonName = "Addon A",
                    name = "⚡ RD"
                ),
                stream(
                    filename = "Show.S01E02.1080p.WEB-DL.x265.GroupB.mkv",
                    addonName = "Addon B",
                    name = "⚡ RD"
                )
            ),
            availableAddons = listOf("Addon A", "Addon B"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(
                groupAcrossAddonsEnabled = true,
                deduplicateGroupedStreamsEnabled = true
            ),
            requestContext = StreamRequestContext(contentType = "series", season = 1, episode = 2)
        )

        assertEquals(2, result.items.size)
        assertEquals(0, result.diagnostics.droppedDeduplicateCount)
    }

    @Test
    fun `diagnostics does not change visible stream ordering`() {
        val streams = listOf(
            stream(filename = "Show.S01E02.720p.WEB-DL.x264.mkv", addonName = "Addon A"),
            stream(filename = "Show.S01E02.1080p.WEB-DL.x265.mkv", addonName = "Addon B")
        )

        val result = StreamPresentationEngine.organize(
            streams = streams,
            availableAddons = listOf("Addon A", "Addon B"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(groupAcrossAddonsEnabled = true),
            requestContext = StreamRequestContext(contentType = "series", season = 1, episode = 2)
        )

        assertEquals(2, result.items.size)
        assertEquals(0, result.diagnostics.droppedEpisodeMismatchCount)
        assertEquals(0, result.diagnostics.droppedMovieYearMismatchCount)
        assertEquals(0, result.diagnostics.droppedWebDolbyVisionCount)
        assertEquals(0, result.diagnostics.droppedDeduplicateCount)
        assertEquals(0, result.diagnostics.droppedAddonFilterCount)
        assertEquals(2, result.diagnostics.finalPresentedCount)
    }

    @Test
    fun `grouped sorting orders by resolution first then size`() {
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(
                    filename = "Movie.2026.1080p.WEB-DL.x265.Small.mkv",
                    addonName = "Addon A",
                    videoSizeBytes = 4L * 1024L * 1024L * 1024L
                ),
                stream(
                    filename = "Movie.2026.2160p.WEB-DL.x265.Small.mkv",
                    addonName = "Addon B",
                    videoSizeBytes = 10L * 1024L * 1024L * 1024L
                ),
                stream(
                    filename = "Movie.2026.1080p.WEB-DL.x265.Large.mkv",
                    addonName = "Addon C",
                    videoSizeBytes = 8L * 1024L * 1024L * 1024L
                ),
                stream(
                    filename = "Movie.2026.2160p.WEB-DL.x265.Large.mkv",
                    addonName = "Addon D",
                    videoSizeBytes = 20L * 1024L * 1024L * 1024L
                )
            ),
            availableAddons = listOf("Addon A", "Addon B", "Addon C", "Addon D"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(groupAcrossAddonsEnabled = true),
            requestContext = StreamRequestContext(contentType = "movie")
        )

        assertEquals(
            listOf("2160p", "2160p", "1080p", "1080p"),
            result.items.map { it.parsed.resolution }
        )
        assertEquals(
            listOf(20L, 10L, 8L, 4L),
            result.items.map { (it.parsed.sizeBytes ?: 0L) / (1024L * 1024L * 1024L) }
        )
    }

    @Test
    fun `grouped sorting orders cached then unknown then uncached before resolution and size`() {
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(
                    filename = "Movie.2026.720p.WEB-DL.x265.CachedLow.mkv",
                    addonName = "Addon A",
                    name = "⚡ RD",
                    videoSizeBytes = 2L * 1024L * 1024L * 1024L
                ),
                stream(
                    filename = "Movie.2026.2160p.WEB-DL.x265.UnknownHigh.mkv",
                    addonName = "Addon B",
                    name = "No cache marker",
                    videoSizeBytes = 20L * 1024L * 1024L * 1024L
                ),
                stream(
                    filename = "Movie.2026.2160p.WEB-DL.x265.UncachedHigh.mkv",
                    addonName = "Addon C",
                    name = "download RD",
                    videoSizeBytes = 25L * 1024L * 1024L * 1024L
                ),
                stream(
                    filename = "Movie.2026.1080p.WEB-DL.x265.CachedMid.mkv",
                    addonName = "Addon D",
                    name = "⚡ PM",
                    videoSizeBytes = 8L * 1024L * 1024L * 1024L
                )
            ),
            availableAddons = listOf("Addon A", "Addon B", "Addon C", "Addon D"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(groupAcrossAddonsEnabled = true),
            requestContext = StreamRequestContext(contentType = "movie")
        )

        assertEquals(
            listOf(true, true, null, false),
            result.items.map { it.parsed.isCached }
        )
        assertEquals(
            listOf("1080p", "720p", "2160p", "2160p"),
            result.items.map { it.parsed.resolution }
        )
    }

    private fun organize(stream: Stream) = StreamPresentationEngine.organize(
        streams = listOf(stream),
        availableAddons = listOf(stream.addonName),
        selectedAddonFilter = null,
        flags = StreamFeatureFlags(
            uniformFormattingEnabled = true,
            groupAcrossAddonsEnabled = false
        )
    ).items.single()

    private fun stream(
        filename: String,
        name: String? = null,
        description: String? = filename,
        parserPreset: AddonParserPreset = AddonParserPreset.GENERIC,
        addonName: String = "Test Addon",
        infoHash: String? = null,
        videoSizeBytes: Long? = null
    ): Stream {
        return Stream(
            name = name,
            title = null,
            description = description,
            url = "https://example.com/video.mkv",
            ytId = null,
            infoHash = infoHash,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                videoHash = null,
                videoSize = videoSizeBytes
                    ?: if (filename.startsWith("Shelter")) 11L * 1024L * 1024L * 1024L else (2.7 * 1024 * 1024 * 1024).toLong(),
                filename = filename
            ),
            addonName = addonName,
            addonLogo = null,
            addonParserPreset = parserPreset
        )
    }
}
