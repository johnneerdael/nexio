package com.nexio.tv.data.remote.api

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbApiDtoTest {
    @Test
    fun `tmdb details parses appended external ids without nested id`() {
        val json = """
            {
              "id": 550,
              "title": "Fight Club",
              "external_ids": {
                "imdb_id": "tt0137523",
                "tvdb_id": null
              }
            }
        """.trimIndent()

        val details = Moshi.Builder()
            .build()
            .adapter(TmdbDetailsResponse::class.java)
            .fromJson(json)

        assertEquals(550, details?.id)
        assertEquals("tt0137523", details?.externalIds?.imdbId)
    }
}
