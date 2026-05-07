package com.nexio.tv.core.stream

import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AioStrictStreamParserNexioToriiTest {

    private val name = """
        1080p · BluRay · HEVC
        ⚡ RD · 🎙 JPN+ENG · 5.1 · 📝 ENG
        ⛩ Torii
    """.trimIndent()

    private val description = """
        📄 [SubsPlease] One Piece - 1100 [1080p][AAC].mkv
        💾 1.45 GiB · 👥 152 · 📅 6h
        📡 Nyaa.si · SubsPlease · Crunchyroll
        🎬 ONE PIECE · 1999 · TV · 1100ep
        📺 S1E1100 · "Romance Dawn"
        🎯 HIGH (152) · year+title+ep
        🆔 anilist:21 · mal:21 · kitsu:12 · imdb:tt0388629
    """.trimIndent()

    private val stream = Stream(
        name = name,
        title = null,
        description = description,
        url = "https://x",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        sources = null,
        addonName = "Nexio Torii",
        addonLogo = null,
        addonParserPreset = AddonParserPreset.NEXIO_TORII
    )

    @Test fun `extracts filename from description`() {
        val info = AioStrictStreamParser.parse(stream)
        assertEquals("[SubsPlease] One Piece - 1100 [1080p][AAC].mkv", info.filename)
    }

    @Test fun `populates matchInfo from match line`() {
        val info = AioStrictStreamParser.parse(stream)
        assertNotNull(info.matchInfo)
        assertEquals("HIGH", info.matchInfo?.confidence)
        assertEquals(152, info.matchInfo?.score)
    }

    @Test fun `populates episodeTitle from episode line`() {
        val info = AioStrictStreamParser.parse(stream)
        assertEquals("Romance Dawn", info.episodeTitle)
    }

    @Test fun `populates crossIds`() {
        val info = AioStrictStreamParser.parse(stream)
        assertEquals("21", info.crossIds["anilist"])
        assertEquals("21", info.crossIds["mal"])
        assertEquals("12", info.crossIds["kitsu"])
        assertEquals("tt0388629", info.crossIds["imdb"])
    }

    @Test fun `returns null matchInfo for non-Nexio presets`() {
        val genericStream = stream.copy(addonParserPreset = AddonParserPreset.GENERIC)
        val info = AioStrictStreamParser.parse(genericStream)
        assertEquals(null, info.matchInfo)
        assertEquals(null, info.episodeTitle)
        assertTrue(info.crossIds.isEmpty())
    }
}
