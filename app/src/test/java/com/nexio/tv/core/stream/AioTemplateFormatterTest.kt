package com.nexio.tv.core.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AioTemplateFormatterTest {

    @Test
    fun `template engine supports nested replace join conditionals and line removal`() {
        val formatter = AioTemplateFormatter(
            nameTemplate = "{stream.resolution::exists[\"{stream.resolution::replace('2160p','4K')}\"||\"Unknown\"]}",
            descriptionTemplate = "{stream.audioTags::exists[\"{stream.audioTags::join(' | ')}\"||\"Stereo\"]}\n" +
                "{stream.message::~Download[\"{tools.removeLine}\"||\"\"]}"
        )
        val parseValue = AioParseValue(
            stream = AioStreamTemplateModel(
                resolution = "2160p",
                audioTags = listOf("Atmos", "TrueHD"),
                message = "Download required"
            )
        )

        val result = formatter.format(parseValue)

        assertEquals("4K", result.name)
        assertEquals("Atmos | TrueHD", result.description)
    }

    @Test
    fun `template engine supports comparator chaining and truncation`() {
        val formatter = AioTemplateFormatter(
            nameTemplate = "{stream.title::exists[\"{stream.title::title::truncate(12)}\"||\"?\"]}" +
                "{stream.seadexBest::istrue[\" 🏆\"||\"\"]}" +
                "{stream.seadex::istrue::and::stream.seadexBest::isfalse[\" 🥈\"||\"\"]}",
            descriptionTemplate = "{stream.duration::>0[\"{stream.duration::time}\"||\"Unknown\"]}"
        )
        val parseValue = AioParseValue(
            stream = AioStreamTemplateModel(
                title = "movie title",
                seadex = true,
                seadexBest = false,
                duration = 9_120_000L
            )
        )

        val result = formatter.format(parseValue)

        assertEquals("Movie Title 🥈", result.name)
        assertEquals("2h:32m:0s", result.description)
    }

    @Test
    fun `built in universal template renders richer aio style multiline output`() {
        val formatter = AioTemplateFormatter(
            nameTemplate = AioBuiltInFormatters.UNIVERSAL.nameTemplate,
            descriptionTemplate = AioBuiltInFormatters.UNIVERSAL.descriptionTemplate,
            badgeRowTemplate = AioBuiltInFormatters.UNIVERSAL.badgeRowTemplate
        )
        val parseValue = AioParseValue(
            stream = AioStreamTemplateModel(
                title = "Movie Title",
                year = "2023",
                resolution = "2160p",
                quality = "BluRay",
                visualTags = listOf("DV"),
                audioTags = listOf("Atmos", "TrueHD"),
                audioChannels = listOf("7.1"),
                duration = null,
                size = 10L * 1024L * 1024L * 1024L,
                type = "debrid",
                uLanguages = listOf("English", "Italian"),
                uLanguageEmojis = listOf("🇬🇧", "🇮🇹"),
                releaseGroup = "GROUP",
                filename = "Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.NF.mkv"
            ),
            service = AioServiceTemplateModel(
                id = "RD",
                shortName = "RD",
                name = "Real-Debrid",
                cached = true
            ),
            addon = AioAddonTemplateModel(name = "Torrentio")
        )

        val result = formatter.format(parseValue)
        val titleLine = result.name
        val detailLine = result.description

        assertTrue(titleLine.contains("[[icon:4k]]"))
        assertTrue(titleLine.contains(" Movie Title (2023)"))
        assertFalse(titleLine.startsWith("⭐"))
        assertEquals(
            listOf(
                "💾 10.74 GB",
                "[[icon:netflix]] Netflix • [[icon:realdebrid]] Real-Debrid",
                "[[text:7:10]]Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.NF.mkv"
            ),
            detailLine.lines()
        )
        assertTrue(result.badgeRow.contains("[[icon:dovi:1.75]]"))
        assertTrue(result.badgeRow.contains("[[icon:atmos:1.75]]"))
        assertTrue(result.badgeRow.contains("[[icon:truehd:1.40]]"))
    }

    @Test
    fun `built in universal template hides year for series and compacts episode formatting`() {
        val formatter = AioTemplateFormatter(
            nameTemplate = AioBuiltInFormatters.UNIVERSAL.nameTemplate,
            descriptionTemplate = AioBuiltInFormatters.UNIVERSAL.descriptionTemplate
        )
        val parseValue = AioParseValue(
            stream = AioStreamTemplateModel(
                title = "Last Week Tonight With John Oliver",
                year = "2026",
                resolution = "1080p",
                seasonEpisode = listOf("S13", "E07"),
                quality = "WEB-DL",
                audioTags = listOf("DD+"),
                size = 2_970_000_000L,
                filename = "Last.Week.Tonight.With.John.Oliver.S13E07.1080p.HMAX.WEB-DL.DDP5.1.mkv"
            ),
            metadata = AioMetadataTemplateModel(queryType = "series")
        )

        val result = formatter.format(parseValue)

        assertEquals("[[icon:fullhd]] Last Week Tonight With John Ol… (S13E07)", result.name)
        assertFalse(result.name.contains("(2026)"))
    }
}
