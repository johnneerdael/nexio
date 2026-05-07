package com.nexio.tv.core.stream

import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AioStrictStreamParserNexioNagareTest {

    private val name = """
        1080p · HLS
        🌊 Gojo · pahe · 📝 SUB
        Direct stream
    """.trimIndent()

    private val description = """
        📄 [Gojo] One Piece - S1E1 [1080p HLS].m3u8
        📡 Gojo · animetsu.live · server: pahe (mega-cloud.top)
        🎬 ONE PIECE · 1999 · TV · 1100ep
        📺 S1E1 · "Romance Dawn"
        📝 ENG, JPN
        🌐 Direct stream · no debrid required
        🎯 HIGH (200) · +200 anilist_id_exact (21)
        🆔 anilist:21 · mal:21 · kitsu:12 · imdb:tt0388629
    """.trimIndent()

    private val stream = Stream(
        name = name,
        title = null,
        description = description,
        url = "https://cdn.example.com/x.m3u8",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        sources = null,
        addonName = "Nexio Nagare",
        addonLogo = null,
        addonParserPreset = AddonParserPreset.NEXIO_NAGARE
    )

    @Test fun `parses synthetic filename via PTT`() {
        val info = AioStrictStreamParser.parse(stream)
        assertEquals("[Gojo] One Piece - S1E1 [1080p HLS].m3u8", info.filename)
        assertEquals("1080p", info.resolution)
    }

    @Test fun `populates matchInfo + episodeTitle + crossIds`() {
        val info = AioStrictStreamParser.parse(stream)
        assertNotNull(info.matchInfo)
        assertEquals("HIGH", info.matchInfo?.confidence)
        assertEquals(200, info.matchInfo?.score)
        assertEquals("Romance Dawn", info.episodeTitle)
        assertEquals("21", info.crossIds["anilist"])
    }
}
