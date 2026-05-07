package com.nexio.tv.core.stream

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AioStrictStreamParserCrossRepoFixtureTest {

    private val fixtureDir = File("docs/superpowers/specs/2026-05-07-fixtures")

    private fun loadFixture(name: String): JsonObject? {
        val f = File(fixtureDir, name)
        if (!f.exists()) return null
        return JsonParser.parseReader(f.reader()).asJsonObject
    }

    private fun streamFor(preset: AddonParserPreset, name: String, description: String) = Stream(
        name = name, title = null, description = description,
        url = "https://x", ytId = null, infoHash = null,
        fileIdx = null, externalUrl = null,
        behaviorHints = null, sources = null,
        addonName = "Nexio Cross-repo Test", addonLogo = null,
        addonParserPreset = preset
    )

    @Test fun `parses nagare fixture into expected ParsedStreamInfo`() {
        val node = loadFixture("nagare-gojo-id-exact.json") ?: return
        val emitted = node.getAsJsonObject("emitted")
        val expected = node.getAsJsonObject("parsed")
        val info = AioStrictStreamParser.parse(streamFor(
            AddonParserPreset.NEXIO_NAGARE,
            emitted.get("name").asString,
            emitted.get("description").asString
        ))
        assertEquals(expected.get("filename").asString, info.filename)
        assertEquals(expected.get("resolution").asString, info.resolution)
        val expectedMatch = expected.getAsJsonObject("matchInfo")
        assertEquals(expectedMatch.get("confidence").asString, info.matchInfo?.confidence)
        assertEquals(expectedMatch.get("score").asInt, info.matchInfo?.score)
        assertEquals(expected.get("episodeTitle").asString, info.episodeTitle)
        val expectedIds = expected.getAsJsonObject("crossIds")
        for (key in expectedIds.keySet()) {
            assertEquals(expectedIds.get(key).asString, info.crossIds[key])
        }
    }

    @Test fun `parses torii fixture into expected ParsedStreamInfo`() {
        val node = loadFixture("torii-cached-realdebrid.json") ?: return
        // Torii fixture stores emittedPatterns (substring matches) since the formatter
        // produces a 📅 ageHours field that depends on the current time. For Kotlin
        // round-trip we synthesize a representative emitted pair below — same shape as
        // what Torii's formatter would produce given a 6-hour-old pubDate.
        val expected = node.getAsJsonObject("parsed")
        val name = "1080p · BluRay · HEVC · DV HDR10\n⚡ RD · 🎙 JPN+ENG · 5.1 · 📝 ENG\n⛩ Torii"
        val description = """
            📄 One Piece - 1100.mkv
            💾 1.4 GiB · 👥 152 · 📅 6h
            📡 Nyaa.si · SubsPlease · Crunchyroll
            🎬 ONE PIECE · 1999 · TV · 1100ep
            📺 S1E1100 · "Romance Dawn"
            🎯 HIGH (152) · title=100 · year_match
            🆔 anilist:21 · mal:21 · kitsu:12 · anidb:69 · imdb:tt0388629
        """.trimIndent()
        val info = AioStrictStreamParser.parse(streamFor(AddonParserPreset.NEXIO_TORII, name, description))
        assertEquals(expected.get("filename").asString, info.filename)
        // Torii fixture's selectedFile name has no resolution token — the resolution
        // lives on the first line of the name. Skip resolution assertion here; covered
        // by AioStrictStreamParserNexioToriiTest separately.
        assertEquals(expected.get("serviceId").asString, info.serviceId)
        assertEquals(expected.get("isCached").asBoolean, info.isCached)
        val expectedMatch = expected.getAsJsonObject("matchInfo")
        assertEquals(expectedMatch.get("confidence").asString, info.matchInfo?.confidence)
        assertEquals(expectedMatch.get("score").asInt, info.matchInfo?.score)
        assertEquals(expected.get("episodeTitle").asString, info.episodeTitle)
    }
}
