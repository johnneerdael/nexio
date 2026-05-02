package com.nexio.tv.data.remote.dto

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WyzieSubtitleDtoTest {

    private val moshi: Moshi = Moshi.Builder()
        .add(WyzieSourceJsonAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(WyzieSubtitleDto::class.java)
    private val listAdapter = moshi.adapter<List<WyzieSubtitleDto>>(
        com.squareup.moshi.Types.newParameterizedType(List::class.java, WyzieSubtitleDto::class.java)
    )

    @Test
    fun `parses single source string`() {
        val json = """
            {
              "id":"1955024019",
              "url":"https://sub.wyzie.io/c/198e0c4d/id/1955024019?format=srt",
              "format":"srt",
              "encoding":"UTF-8",
              "display":"English",
              "language":"en",
              "media":"The Martian",
              "isHearingImpaired":false,
              "source":"opensubtitles",
              "release":"The.Martian.2015.1080p.WEB-DL",
              "fileName":"the.martian.2015.1080p.web-dl.srt"
            }
        """.trimIndent()

        val dto = adapter.fromJson(json)!!
        assertEquals("1955024019", dto.id)
        assertEquals("opensubtitles", dto.source)
        assertEquals("en", dto.language)
        assertEquals(false, dto.isHearingImpaired)
    }

    @Test
    fun `parses source as JSON array using first element`() {
        val json = """
            {
              "id":"x",
              "url":"https://example/sub.srt",
              "format":"srt",
              "encoding":"UTF-8",
              "display":"English",
              "language":"en",
              "media":"X",
              "isHearingImpaired":false,
              "source":["opensubtitles","subdl"]
            }
        """.trimIndent()

        val dto = adapter.fromJson(json)!!
        assertEquals("opensubtitles", dto.source)
    }

    @Test
    fun `parses null source as null`() {
        val json = """
            {
              "id":"x",
              "url":"https://example/sub.srt",
              "format":"srt",
              "encoding":"UTF-8",
              "display":"English",
              "language":"en",
              "media":"X",
              "isHearingImpaired":false,
              "source":null
            }
        """.trimIndent()

        val dto = adapter.fromJson(json)!!
        assertNull(dto.source)
    }

    @Test
    fun `parses missing source field as null`() {
        val json = """
            {
              "id":"x",
              "url":"https://example/sub.srt",
              "format":"srt",
              "encoding":"UTF-8",
              "display":"English",
              "language":"en",
              "media":"X",
              "isHearingImpaired":false
            }
        """.trimIndent()

        val dto = adapter.fromJson(json)!!
        assertNull(dto.source)
    }

    @Test
    fun `parses missing optional fields without crashing`() {
        val json = """
            {
              "id":"x",
              "url":"https://example/sub.srt",
              "format":"srt",
              "encoding":"UTF-8",
              "display":"English",
              "language":"en",
              "media":"X",
              "isHearingImpaired":true,
              "source":"podnapisi"
            }
        """.trimIndent()

        val dto = adapter.fromJson(json)!!
        assertNull(dto.release)
        assertNull(dto.releases)
        assertNull(dto.fileName)
        assertNull(dto.downloadCount)
        assertNull(dto.origin)
        assertNull(dto.matchedRelease)
        assertNull(dto.matchedFilter)
        assertEquals(true, dto.isHearingImpaired)
    }

    @Test
    fun `parses an empty array as empty list`() {
        val list = listAdapter.fromJson("[]")!!
        assertTrue(list.isEmpty())
    }
}
