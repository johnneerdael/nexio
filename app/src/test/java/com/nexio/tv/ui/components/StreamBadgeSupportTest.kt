package com.nexio.tv.ui.components

import com.nexio.tv.core.stream.AioStrictStreamParser
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamBadgeSupportTest {

    @Test
    fun `cached streams show cached badge instead of torrent`() {
        val stream = stream(
            name = "⚡ RD",
            url = "https://real-debrid.example/direct.mkv",
            infoHash = "0123456789ABCDEF0123456789ABCDEF01234567",
            description = "Cached direct stream"
        )

        assertEquals(
            listOf(StreamBadgeKind.CACHED),
            streamBadgeKinds(stream, AioStrictStreamParser.parse(stream))
        )
    }

    @Test
    fun `service wrapped cached stream hides torrent badge even when info hash is retained`() {
        val stream = stream(
            name = "⚡ Real-Debrid",
            url = "https://rd.example/stream",
            infoHash = "89ABCDEF0123456789ABCDEF0123456789ABCDEF",
            description = "RD+ cached\nWrapped via Real-Debrid"
        )

        assertEquals(
            listOf(StreamBadgeKind.CACHED),
            streamBadgeKinds(stream, AioStrictStreamParser.parse(stream))
        )
    }

    @Test
    fun `plain p2p stream still shows torrent badge`() {
        val stream = stream(
            name = "Torrent Candidate",
            url = null,
            infoHash = "FEDCBA9876543210FEDCBA9876543210FEDCBA98",
            description = "Show.S01E02.1080p.WEB-DL"
        )

        assertEquals(
            listOf(StreamBadgeKind.TORRENT),
            streamBadgeKinds(stream, AioStrictStreamParser.parse(stream))
        )
    }

    @Test
    fun `external and youtube badges still surface from raw stream type`() {
        val external = stream(
            name = "External",
            url = null,
            externalUrl = "https://example.com"
        )
        val youtube = stream(
            name = "YouTube",
            url = null,
            ytId = "abc123"
        )

        assertEquals(
            listOf(StreamBadgeKind.EXTERNAL),
            streamBadgeKinds(external, AioStrictStreamParser.parse(external))
        )
        assertEquals(
            listOf(StreamBadgeKind.YOUTUBE),
            streamBadgeKinds(youtube, AioStrictStreamParser.parse(youtube))
        )
    }

    private fun stream(
        name: String,
        url: String? = null,
        infoHash: String? = null,
        description: String? = null,
        externalUrl: String? = null,
        ytId: String? = null
    ): Stream {
        return Stream(
            name = name,
            title = null,
            description = description,
            url = url,
            ytId = ytId,
            infoHash = infoHash,
            fileIdx = null,
            externalUrl = externalUrl,
            behaviorHints = null,
            sources = null,
            addonName = "Test Addon",
            addonLogo = null,
            addonParserPreset = AddonParserPreset.GENERIC
        )
    }
}
