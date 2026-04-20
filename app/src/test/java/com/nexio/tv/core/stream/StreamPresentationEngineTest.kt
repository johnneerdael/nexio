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
            name = "⚡ PM",
            addonName = "Torrentio",
            videoSizeBytes = 10L * 1024L * 1024L * 1024L
        )

        val item = organize(stream)

        assertEquals("[[icon:fullhd]] Shrinking (S03E06)", item.title)
        assertEquals(
            listOf(
                "💾 10.74 GB",
                "[[icon:appletv]] Apple TV+ • [[icon:premiumize]] Premiumize",
                "[[text:7:10]]Shrinking.S03E06.Dereks.Dont.Die.1080p.ATVP.WEB-DL.DDP5.1.Atmos.ENG.ITA.H264-TheShrink.mkv"
            ),
            item.detailLines
        )
        assertEquals("[[icon:atmos:1.40]] [[icon:ddp:1.40]]", item.badgeRow)
        assertEquals(true, item.suppressAutomaticBadgeRow)
    }

    @Test
    fun `shelter filename maps to clean movie title and languages`() {
        val stream = stream(
            filename = "Shelter.2026.MULTi.VFQ.2160p.HDR.WEB-DL.H265-Slay3R.mkv",
            name = "⚡ RD",
            addonName = "Torrentio",
            videoSizeBytes = 10L * 1024L * 1024L * 1024L
        )

        val item = organize(stream)

        assertEquals("[[icon:4k]] Shelter (2026)", item.title)
        assertEquals(
            listOf(
                "💾 10.74 GB",
                "[[icon:realdebrid]] Real-Debrid",
                "[[text:7:10]]Shelter.2026.MULTi.VFQ.2160p.HDR.WEB-DL.H265-Slay3R.mkv"
            ),
            item.detailLines
        )
        assertEquals("[[icon:hdr10:1.40]]", item.badgeRow)
        assertEquals(true, item.suppressAutomaticBadgeRow)
    }

    @Test
    fun `universal template renders full aio style movie card details`() {
        val stream = stream(
            filename = "Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.NF.mkv",
            name = "⚡ RD",
            addonName = "Torrentio",
            videoSizeBytes = 10L * 1024L * 1024L * 1024L
        )

        val item = organize(stream)
        val detailOutput = item.detailLines.joinToString("\n")
        val badgeRow = item.badgeRow.orEmpty()

        assertEquals("[[icon:4k]] Movie Title (2023)", item.title)
        assertTrue(detailOutput.contains("[[icon:netflix]] Netflix"))
        assertTrue(detailOutput.contains("[[icon:realdebrid]] Real-Debrid"))
        assertTrue(badgeRow.contains("[[icon:atmos:1.40]]"))
        assertTrue(badgeRow.contains("[[icon:truehd:1.40]]"))
        assertTrue(badgeRow.contains("[[icon:dovi:1.40]]"))
        assertEquals(true, item.suppressAutomaticBadgeRow)
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
    fun `uniform formatting uses selected built in template when provided`() {
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(
                    filename = "Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.mkv",
                    name = "⚡ RD"
                )
            ),
            availableAddons = listOf("Test Addon"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(
                uniformFormattingEnabled = true,
                groupAcrossAddonsEnabled = false,
                uniformFormattingTemplate = AioFormatterSelection(selectedTemplateId = "prism")
            ),
            requestContext = StreamRequestContext(contentType = "movie")
        )

        val item = result.items.single()
        assertEquals("🔥4K UHD", item.title)
        assertEquals(listOf("🎬 Movie Title (2023)"), item.detailLines)
    }

    @Test
    fun `direct addon stream remains playable when it has no metadata filename`() {
        val stream = stream(
            filename = "",
            name = "NebulaStreams 4K | 4khdhub",
            description = """
                4K | WEB
                📺 HDR • BluRay
                🎞️ HEVC • 10-bit
                🎧 DD+
                📦 16.83GB
                🌐 Hindi + English
                🔍 4khdhub
                📁 The Shawshank Redemption (1994)
            """.trimIndent(),
            addonName = "NebulaStreams",
            url = "https://cryptoinsights.site/direct/path%2Fwith%2Fencoded?token=a%2Fb%3D&expires=1776650158",
            notWebReady = true,
            videoSizeBytes = null
        )

        val result = StreamPresentationEngine.organize(
            streams = listOf(stream),
            availableAddons = listOf(stream.addonName),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(
                uniformFormattingEnabled = true,
                groupAcrossAddonsEnabled = true,
                deduplicateGroupedStreamsEnabled = true
            ),
            requestContext = StreamRequestContext(
                contentType = "movie",
                title = "The Shawshank Redemption",
                year = "1994"
            )
        )

        val item = result.items.single()
        assertEquals(stream.url, item.stream.getStreamUrl())
        assertEquals(true, item.parsed.hasUsablePlaybackTarget)
        assertEquals(StreamTransportKind.HTTP, item.parsed.transportKind)
    }

    @Test
    fun `direct addon stream bypasses metadata mismatch filters`() {
        val stream = stream(
            filename = "",
            name = "NebulaStreams 4K | 4khdhub",
            description = "Movie.From.Addon.Catalog.2026.2160p",
            addonName = "NebulaStreams",
            url = "https://cryptoinsights.site/direct/movie-2026.mkv",
            notWebReady = true,
            videoSizeBytes = null
        )

        val result = StreamPresentationEngine.organize(
            streams = listOf(stream),
            availableAddons = listOf(stream.addonName),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(
                uniformFormattingEnabled = true,
                groupAcrossAddonsEnabled = true,
                deduplicateGroupedStreamsEnabled = true,
                filterMovieYearMismatchStreamsEnabled = true
            ),
            requestContext = StreamRequestContext(
                contentType = "movie",
                title = "Different Metadata Title",
                year = "1994"
            )
        )

        val item = result.items.single()
        assertEquals(stream.url, item.stream.getStreamUrl())
    }

    @Test
    fun `uniform formatting uses custom synced template when selected`() {
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(
                    filename = "Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.mkv",
                    name = "⚡ RD"
                )
            ),
            availableAddons = listOf("Test Addon"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(
                uniformFormattingEnabled = true,
                groupAcrossAddonsEnabled = false,
                uniformFormattingTemplate = AioFormatterSelection(
                    selectedTemplateId = "custom",
                    customTemplate = AioCustomTemplateSelection(
                        label = "Compact",
                        nameTemplate = "{stream.title::upper}",
                        descriptionTemplate = "{stream.year::exists[\"{stream.year}\"||\"?\"]}"
                    )
                )
            ),
            requestContext = StreamRequestContext(contentType = "movie")
        )

        val item = result.items.single()
        assertEquals("MOVIE TITLE", item.title)
        assertEquals(listOf("2023"), item.detailLines)
    }

    @Test
    fun `custom uniform formatting renders optional badge row template and detects chip token`() {
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(
                    filename = "Movie.Title.2023.2160p.BluRay.HEVC-GROUP.mkv",
                    name = "⚡ RD"
                )
            ),
            availableAddons = listOf("Test Addon"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(
                uniformFormattingEnabled = true,
                groupAcrossAddonsEnabled = false,
                uniformFormattingTemplate = AioFormatterSelection(
                    selectedTemplateId = "custom",
                    customTemplate = AioCustomTemplateSelection(
                        label = "Badge row",
                        nameTemplate = "{stream.title}",
                        descriptionTemplate = "{stream.year}",
                        badgeRowTemplate = "{service.cached::istrue[\"[[chip:cached]]\"||\"\"]}"
                    )
                )
            ),
            requestContext = StreamRequestContext(contentType = "movie")
        )

        val item = result.items.single()
        assertEquals("[[chip:cached]]", item.badgeRow)
        assertEquals(true, item.hasFormatterChipTokens)
        assertEquals(true, item.suppressAutomaticBadgeRow)
    }

    @Test
    fun `custom uniform formatting suppresses automatic badge row when badge row has only icons`() {
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(
                    filename = "Movie.Title.2023.2160p.HDR.BluRay.HEVC-GROUP.mkv",
                    name = "⚡ RD"
                )
            ),
            availableAddons = listOf("Test Addon"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(
                uniformFormattingEnabled = true,
                groupAcrossAddonsEnabled = false,
                uniformFormattingTemplate = AioFormatterSelection(
                    selectedTemplateId = "custom",
                    customTemplate = AioCustomTemplateSelection(
                        label = "Icon badge row",
                        nameTemplate = "{stream.title}",
                        descriptionTemplate = "{stream.year}",
                        badgeRowTemplate = "{stream.visualTags::exists[\"{stream.visualTags::join(' ')::replace('HDR','[[icon:hdr10:1.25]]')}\"||\"\"]}"
                    )
                )
            ),
            requestContext = StreamRequestContext(contentType = "movie")
        )

        val item = result.items.single()
        assertEquals("[[icon:hdr10:1.25]]", item.badgeRow)
        assertEquals(false, item.hasFormatterChipTokens)
        assertEquals(true, item.suppressAutomaticBadgeRow)
    }

    @Test
    fun `custom uniform formatting detects inline chip token and leaves empty badge row blank`() {
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(
                    filename = "Movie.Title.2023.2160p.BluRay.HEVC-GROUP.mkv",
                    name = "⚡ RD"
                )
            ),
            availableAddons = listOf("Test Addon"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(
                uniformFormattingEnabled = true,
                groupAcrossAddonsEnabled = false,
                uniformFormattingTemplate = AioFormatterSelection(
                    selectedTemplateId = "custom",
                    customTemplate = AioCustomTemplateSelection(
                        label = "Inline badge",
                        nameTemplate = "{service.cached::istrue[\"[[chip:cached]] \"||\"\"]}{stream.title}",
                        descriptionTemplate = "{stream.year}"
                    )
                )
            ),
            requestContext = StreamRequestContext(contentType = "movie")
        )

        val item = result.items.single()
        assertEquals("[[chip:cached]] Movie Title", item.title)
        assertEquals(null, item.badgeRow)
        assertEquals(true, item.hasFormatterChipTokens)
        assertEquals(true, item.suppressAutomaticBadgeRow)
    }

    @Test
    fun `uniform formatting falls back to metadata runtime when parser duration is missing`() {
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(
                    filename = "Movie.Title.2023.2160p.BluRay.HEVC-GROUP.mkv",
                    description = "Movie.Title.2023.2160p.BluRay.HEVC-GROUP.mkv"
                )
            ),
            availableAddons = listOf("Test Addon"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(
                uniformFormattingEnabled = true,
                groupAcrossAddonsEnabled = false,
                uniformFormattingTemplate = AioFormatterSelection(
                    selectedTemplateId = "custom",
                    customTemplate = AioCustomTemplateSelection(
                        label = "Runtime fallback",
                        nameTemplate = "{stream.title}",
                        descriptionTemplate = "{stream.duration::time}"
                    )
                )
            ),
            requestContext = StreamRequestContext(
                contentType = "movie",
                runtimeMinutes = 152
            )
        )

        assertEquals(listOf("2h:32m:0s"), result.items.single().detailLines)
    }

    @Test
    fun `uniform formatting keeps parser runtime over metadata fallback`() {
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(
                    filename = "Movie.Title.2023.2160p.BluRay.HEVC-GROUP.mkv",
                    description = "Movie.Title.2023.2160p.BluRay.HEVC-GROUP.mkv\n12.5 Mbps • 2h 10m 0s"
                )
            ),
            availableAddons = listOf("Test Addon"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(
                uniformFormattingEnabled = true,
                groupAcrossAddonsEnabled = false,
                uniformFormattingTemplate = AioFormatterSelection(
                    selectedTemplateId = "custom",
                    customTemplate = AioCustomTemplateSelection(
                        label = "Runtime priority",
                        nameTemplate = "{stream.title}",
                        descriptionTemplate = "{stream.duration::time}"
                    )
                )
            ),
            requestContext = StreamRequestContext(
                contentType = "movie",
                runtimeMinutes = 152
            )
        )

        assertEquals(listOf("2h:10m:0s"), result.items.single().detailLines)
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
                    name = "⚡ RD",
                    videoSizeBytes = 2_000_000_000L
                ),
                stream(
                    filename = "Show.S01E02.1080p.WEB-DL.x265.GroupB.mkv",
                    addonName = "Addon B",
                    name = "⚡ RD",
                    videoSizeBytes = 3_000_000_000L
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
    fun `dedupe keeps one cached duplicate per service`() {
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(
                    filename = "Show.S01E02.1080p.WEB-DL.x265.Group.mkv",
                    addonName = "Addon A",
                    name = "⚡ RD",
                    description = "⚡ RD"
                ),
                stream(
                    filename = "Show.S01E02.1080p.WEB-DL.x265.Group.mkv",
                    addonName = "Addon B",
                    name = "⚡ Real-Debrid",
                    description = "⚡ Real-Debrid"
                ),
                stream(
                    filename = "Show.S01E02.1080p.WEB-DL.x265.Group.mkv",
                    addonName = "Addon C",
                    name = "⚡ PM",
                    description = "⚡ PM"
                )
            ),
            availableAddons = listOf("Addon A", "Addon B", "Addon C"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(
                groupAcrossAddonsEnabled = true,
                deduplicateGroupedStreamsEnabled = true
            ),
            requestContext = StreamRequestContext(contentType = "series", season = 1, episode = 2)
        )

        assertEquals(2, result.items.size)
        assertEquals(listOf("PM", "RD"), result.items.mapNotNull { it.parsed.serviceId }.sorted())
    }

    @Test
    fun `dedupe clusters exact byte size duplicates and keeps one cached result per service`() {
        val sharedSizeBytes = 12_345_678_901L
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(
                    filename = "First.Release.2026.1080p.WEB-DL.x265.GroupA.mkv",
                    addonName = "Addon A",
                    name = "⚡ RD",
                    description = "⚡ RD",
                    videoSizeBytes = sharedSizeBytes
                ),
                stream(
                    filename = "Second.Release.2026.720p.HDTV.x264.GroupB.mkv",
                    addonName = "Addon B",
                    name = "⚡ Real-Debrid",
                    description = "⚡ Real-Debrid",
                    videoSizeBytes = sharedSizeBytes
                ),
                stream(
                    filename = "Third.Release.2026.2160p.BluRay.HEVC.GroupC.mkv",
                    addonName = "Addon C",
                    name = "⚡ PM",
                    description = "⚡ PM",
                    videoSizeBytes = sharedSizeBytes
                )
            ),
            availableAddons = listOf("Addon A", "Addon B", "Addon C"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(
                groupAcrossAddonsEnabled = true,
                deduplicateGroupedStreamsEnabled = true
            ),
            requestContext = StreamRequestContext(contentType = "movie")
        )

        assertEquals(2, result.items.size)
        assertEquals(1, result.diagnostics.droppedDeduplicateCount)
        assertEquals(listOf("PM", "RD"), result.items.mapNotNull { it.parsed.serviceId }.sorted())
    }

    @Test
    fun `dedupe keeps wrapped cached duplicates per service when info hash and file index match`() {
        val sharedHash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(
                    filename = "Show.S01E02.1080p.WEB-DL.x265.Group.mkv",
                    addonName = "Addon A",
                    name = "⚡ Real-Debrid",
                    description = "⚡ Real-Debrid",
                    infoHash = sharedHash,
                    fileIdx = 0,
                    wrappedProviderId = "RD"
                ),
                stream(
                    filename = "Show.S01E02.1080p.WEB-DL.x265.Group.mkv",
                    addonName = "Addon A",
                    name = "⚡ Premiumize",
                    description = "⚡ Premiumize",
                    infoHash = sharedHash,
                    fileIdx = 0,
                    wrappedProviderId = "PM"
                )
            ),
            availableAddons = listOf("Addon A"),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(
                groupAcrossAddonsEnabled = true,
                deduplicateGroupedStreamsEnabled = true
            ),
            requestContext = StreamRequestContext(contentType = "series", season = 1, episode = 2)
        )

        assertEquals(2, result.items.size)
        assertEquals(listOf("PM", "RD"), result.items.mapNotNull { it.parsed.serviceId }.sorted())
    }

    @Test
    fun `dedupe keeps uncached fallback when cached duplicate is only available on another service`() {
        val result = StreamPresentationEngine.organize(
            streams = listOf(
                stream(
                    filename = "Show.S01E02.1080p.WEB-DL.x265.Group.mkv",
                    addonName = "Addon A",
                    name = "⚡ RD",
                    description = "⚡ RD"
                ),
                stream(
                    filename = "Show.S01E02.1080p.WEB-DL.x265.Group.mkv",
                    addonName = "Addon B",
                    name = "download PM",
                    description = "download PM"
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
        assertEquals(
            listOf(StreamTransportKind.CACHED, StreamTransportKind.UNCACHED),
            result.items.map { it.parsed.transportKind }.sortedBy { it.name }
        )
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
        fileIdx: Int? = null,
        wrappedProviderId: String? = null,
        videoSizeBytes: Long? = null,
        url: String = "https://example.com/video.mkv",
        notWebReady: Boolean? = null
    ): Stream {
        return Stream(
            name = name,
            title = null,
            description = description,
            url = url,
            ytId = null,
            infoHash = infoHash,
            fileIdx = fileIdx,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = notWebReady,
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
            addonParserPreset = parserPreset,
            wrappedProviderId = wrappedProviderId
        )
    }
}
