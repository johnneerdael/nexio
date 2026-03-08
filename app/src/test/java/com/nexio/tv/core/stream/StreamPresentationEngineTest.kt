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
        parserPreset: AddonParserPreset = AddonParserPreset.GENERIC
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
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                videoHash = null,
                videoSize = if (filename.startsWith("Shelter")) 11L * 1024L * 1024L * 1024L else (2.7 * 1024 * 1024 * 1024).toLong(),
                filename = filename
            ),
            addonName = "Test Addon",
            addonLogo = null,
            addonParserPreset = parserPreset
        )
    }
}
