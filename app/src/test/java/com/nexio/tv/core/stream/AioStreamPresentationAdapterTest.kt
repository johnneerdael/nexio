package com.nexio.tv.core.stream

import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Test

class AioStreamPresentationAdapterTest {

    @Test
    fun `universal template renders movie stream through uniform formatting path`() {
        val stream = Stream(
            name = "⚡ RD",
            title = null,
            description = "Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.mkv",
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
                videoSize = 62_500_000_000L,
                filename = "Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.mkv"
            ),
            addonName = "Torrentio",
            addonLogo = null,
            addonParserPreset = AddonParserPreset.GENERIC
        )

        val item = StreamPresentationEngine.organize(
            streams = listOf(stream),
            availableAddons = listOf(stream.addonName),
            selectedAddonFilter = null,
            flags = StreamFeatureFlags(
                uniformFormattingEnabled = true,
                groupAcrossAddonsEnabled = false
            ),
            requestContext = StreamRequestContext(contentType = "movie")
        ).items.single()

        assertEquals("⭐⭐⭐⭐⭐ 4K UHD - Movie Title (2023)", item.title)
        assertEquals(
            listOf(
                "🎥 Blu-ray  • 🔊 Dolby Atmos Dolby TrueHD 7.1 • ⏱️ Unknown"
            ),
            item.detailLines
        )
    }
}
