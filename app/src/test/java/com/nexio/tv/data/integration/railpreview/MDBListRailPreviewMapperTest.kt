package com.nexio.tv.data.integration.railpreview

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.SourcePayloadQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MDBListRailPreviewMapperTest {
    @Test
    fun `mdblist maps appended poster description genres and ratings`() {
        val item = JsonParser.parseString(
            """
            {
              "id": 42,
              "title": "Inception",
              "year": 2010,
              "type": "movie",
              "imdb_id": "tt1375666",
              "tmdb_id": 27205,
              "poster": "https://image.example/inception.jpg",
              "description": "A thief steals corporate secrets through dream-sharing technology.",
              "genres": ["Action", "Science Fiction"],
              "ratings": {"imdb": 8.8}
            }
            """.trimIndent()
        ).asJsonObject

        val preview = MDBListRailPreviewMapper().mapJsonObject(
            railId = "mdblist_top_movies",
            item = item,
            position = 0,
            generatedAtMs = 1_000L
        )!!

        assertEquals(RailSource.BUILT_IN_MDBLIST, preview.railSource)
        assertEquals(ProviderId.MDBLIST, preview.sourceProvider)
        assertEquals("mdblist:list:mdblist_top_movies:item:42", preview.sourceItemId)
        assertEquals(ContentType.MOVIE, preview.itemType)
        assertEquals("tt1375666", preview.stableIds.imdb)
        assertEquals("27205", preview.stableIds.tmdb)
        assertEquals("Inception", preview.display.title)
        assertEquals("https://image.example/inception.jpg", preview.display.posterUrl)
        assertEquals(listOf("Action", "Science Fiction"), preview.display.genres)
        assertEquals(ProviderId.IMDB, preview.display.rating?.provider)
        assertEquals(8.8, preview.display.rating?.value ?: 0.0, 0.01)
        assertEquals(1, preview.ranking?.rank)
        assertEquals(SourcePayloadQuality.RICH_PREVIEW, preview.sourcePayloadQuality)
    }

    @Test
    fun `mdblist maps show series and tv types to series`() {
        listOf("show", "series", "tv").forEachIndexed { index, type ->
            val preview = MDBListRailPreviewMapper().mapJsonObject(
                railId = "mdblist_top_shows",
                item = jsonObject(
                    "id" to (index + 1).toString(),
                    "name" to "Show $index",
                    "type" to type
                ),
                position = index,
                generatedAtMs = 1_000L
            )!!

            assertEquals(ContentType.SERIES, preview.itemType)
        }
    }

    @Test
    fun `mdblist missing id returns null`() {
        val preview = MDBListRailPreviewMapper().mapJsonObject(
            railId = "mdblist_top_movies",
            item = jsonObject("title" to "No ID"),
            position = 0,
            generatedAtMs = 1_000L
        )

        assertNull(preview)
    }

    @Test
    fun `mdblist top level rating maps to mdblist when imdb rating is absent`() {
        val preview = MDBListRailPreviewMapper().mapJsonObject(
            railId = "mdblist_top_movies",
            item = jsonObject(
                "id" to "99",
                "title" to "MDB Rated",
                "type" to "movie",
                "rating" to "7.4"
            ),
            position = 4,
            generatedAtMs = 1_000L
        )!!

        assertEquals(ProviderId.MDBLIST, preview.display.rating?.provider)
        assertEquals(7.4, preview.display.rating?.value ?: 0.0, 0.01)
    }

    private fun jsonObject(vararg values: Pair<String, String>): JsonObject {
        val json = JsonObject()
        values.forEach { (key, value) -> json.addProperty(key, value) }
        return json
    }
}
