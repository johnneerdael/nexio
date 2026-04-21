package com.nexio.tv.data.remote.dto

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubtitlesRestSubtitleDtoTest {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(
        List::class.java, OpenSubtitlesRestSubtitleDto::class.java
    )
    private val adapter = moshi.adapter<List<OpenSubtitlesRestSubtitleDto>>(listType)

    private fun load(name: String): String =
        javaClass.getResourceAsStream("/opensubtitles/$name")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `parses movie imdbid fixture into 100 rows`() {
        val rows = adapter.fromJson(load("search_movie_imdbid.json"))!!
        assertEquals(100, rows.size)
        val first = rows.first()
        assertEquals("imdbid", first.matchedBy)
        assertNotNull(first.idSubtitleFile)
        assertEquals("eng", first.subLanguageID)
        assertEquals("en", first.iso639)
        assertTrue(first.subDownloadLink!!.startsWith("https://dl.opensubtitles.org/"))
    }

    @Test
    fun `parses moviehash fixture and surfaces moviehash MatchedBy`() {
        val rows = adapter.fromJson(load("search_moviehash.json"))!!
        assertTrue(rows.isNotEmpty())
        assertEquals("moviehash", rows.first().matchedBy)
        assertEquals("8e245d9679d31e12", rows.first().movieHash)
        assertEquals("12909756", rows.first().movieByteSize)
    }

    @Test
    fun `parses series episode fixture`() {
        val rows = adapter.fromJson(load("search_series_episode.json"))!!
        assertTrue(rows.isNotEmpty())
        assertEquals("episode", rows.first().movieKind)
    }

    @Test
    fun `parses empty fixture`() {
        val rows = adapter.fromJson(load("search_empty.json"))!!
        assertTrue(rows.isEmpty())
    }
}
