package com.nexio.tv.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimklDiscoveryServiceTest {

    @Test
    fun `extract media object unwraps nested tv and anime payloads`() {
        val tvDto = JsonParser.parseString(
            """
            {
              "show": {
                "title": "Nested Show",
                "ids": { "simkl_id": 123 }
              }
            }
            """.trimIndent()
        ).asJsonObject
        val animeDto = JsonParser.parseString(
            """
            {
              "anime": {
                "title": "Nested Anime",
                "ids": { "simkl_id": 456 }
              }
            }
            """.trimIndent()
        ).asJsonObject

        assertEquals("Nested Show", extractSimklDiscoveryMediaObject(tvDto, "tv").get("title").asString)
        assertEquals("Nested Anime", extractSimklDiscoveryMediaObject(animeDto, "anime").get("title").asString)
    }

    @Test
    fun `preferred external content id prefers imdb then tmdb`() {
        val imdbIds = JsonObject().apply {
            addProperty("imdb", "tt1234567")
            addProperty("tmdb", "99")
        }
        val tmdbIds = JsonObject().apply {
            addProperty("tmdb", 101)
        }
        val emptyIds = JsonObject().apply {
            addProperty("simkl_id", 42)
        }

        assertEquals("tt1234567", preferredSimklExternalContentId(imdbIds))
        assertEquals("tmdb:101", preferredSimklExternalContentId(tmdbIds))
        assertNull(preferredSimklExternalContentId(emptyIds))
    }

    @Test
    fun `resolve canonical content id backfills from summary lookup and caches result`() = runTest {
        val ids = JsonObject().apply {
            addProperty("simkl_id", 877196)
        }
        val cache = linkedMapOf<String, String?>()
        var calls = 0

        val first = resolveSimklCanonicalContentId(
            kind = "tv",
            ids = ids,
            externalIdCache = cache,
            fetchExternalContentId = { kind, simklId ->
                calls += 1
                assertEquals("tv", kind)
                assertEquals("877196", simklId)
                "tt7942682"
            }
        )
        val second = resolveSimklCanonicalContentId(
            kind = "tv",
            ids = ids,
            externalIdCache = cache,
            fetchExternalContentId = { _, _ ->
                calls += 1
                "tt-should-not-be-used"
            }
        )

        assertEquals("tt7942682", first)
        assertEquals("tt7942682", second)
        assertEquals(1, calls)
    }

    @Test
    fun `resolve canonical content id falls back to simkl when summary lacks external ids`() = runTest {
        val ids = JsonObject().apply {
            addProperty("simkl_id", 1902682)
        }

        val resolved = resolveSimklCanonicalContentId(
            kind = "anime",
            ids = ids,
            externalIdCache = linkedMapOf(),
            fetchExternalContentId = { _, _ -> null }
        )

        assertEquals("simkl:1902682", resolved)
    }
}
