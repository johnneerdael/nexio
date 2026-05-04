package com.nexio.tv.core.player

import com.nexio.tv.data.local.PlayerPreference
import com.nexio.tv.ui.screens.stream.AutoPlayStreamAlternative
import com.nexio.tv.ui.screens.stream.StreamPlaybackInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeProfilingDiagnosticTest {
    @Test
    fun `probe plan uses resolved CDN primary with capped fallback fanout`() {
        val playbackInfo = playbackInfo(
            url = "https://addon.example/stream/primary.mkv",
            streamKey = "primary",
            addonBaseUrl = "https://addon.example",
            fallbacks = listOf(
                fallback("f1", "https://addon.example/stream/f1.mkv"),
                fallback("f2", "https://addon.example/stream/f2.mkv"),
                fallback("f3", "https://addon.example/stream/f3.mkv")
            )
        )

        val plan = ProbeProfilingDiagnostic.buildPlanForTesting(
            playbackInfo = playbackInfo,
            fallbackCount = 2
        )

        assertEquals(
            listOf(
                "primary_resolve_cdn",
                "fallback_0_resolve_cdn",
                "fallback_1_resolve_cdn"
            ),
            plan.map { it.label }
        )
        assertEquals(true, plan[0].routeThroughResolver)
        assertEquals("addon.example", plan[0].addonHost)
        assertEquals("addon.example", plan[1].addonHost)
        assertEquals("f2", plan[2].streamKey)
    }

    private fun fallback(streamKey: String, url: String?): AutoPlayStreamAlternative =
        AutoPlayStreamAlternative(
            streamKey = streamKey,
            url = url,
            streamName = streamKey,
            headers = mapOf("x-test" to streamKey),
            addonBaseUrl = "https://addon.example"
        )

    private fun playbackInfo(
        url: String,
        streamKey: String,
        addonBaseUrl: String,
        fallbacks: List<AutoPlayStreamAlternative>
    ): StreamPlaybackInfo =
        StreamPlaybackInfo(
            url = url,
            title = "Title",
            streamName = "Stream",
            playerBackend = PlayerPreference.INTERNAL,
            year = "2026",
            isExternal = false,
            isTorrent = false,
            infoHash = null,
            ytId = null,
            headers = mapOf("x-test" to "primary"),
            contentId = "1",
            contentType = "movie",
            contentName = "Title",
            originalLanguage = null,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "movie:1",
            season = null,
            episode = null,
            episodeTitle = null,
            bingeGroup = null,
            rememberedAudioLanguage = null,
            rememberedAudioName = null,
            streamKey = streamKey,
            addonBaseUrl = addonBaseUrl,
            autoPlayFallbackCandidates = fallbacks
        )
}
