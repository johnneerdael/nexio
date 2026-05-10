package com.nexio.tv.data.repository

import com.google.gson.GsonBuilder
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.domain.model.DisplaySourceRank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueWatchingMetadataSnapshotTypeAdapterTest {

    private val gson = GsonBuilder()
        .registerTypeAdapter(
            ContinueWatchingMetadataSnapshot::class.java,
            ContinueWatchingMetadataSnapshotTypeAdapter()
        )
        .create()

    @Test
    fun `v2 JSON with clickTimeSlots deserializes directly`() {
        val v2Json = """
            {
              "routingVersion": 1,
              "parentId": "tt0944947",
              "primaryProvider": "TMDB",
              "decisionReason": "PROVIDER_NATIVE_DIRECT",
              "clickTimeSlots": {
                "title": { "value": "Game of Thrones", "rank": "FIRST_PAINT", "updatedAtMs": 0, "trace": [] },
                "originalTitle": { "value": null, "rank": "EMPTY", "updatedAtMs": 0, "trace": [] },
                "overview": { "value": null, "rank": "EMPTY", "updatedAtMs": 0, "trace": [] },
                "genres": { "value": null, "rank": "EMPTY", "updatedAtMs": 0, "trace": [] },
                "releaseInfo": { "value": null, "rank": "EMPTY", "updatedAtMs": 0, "trace": [] },
                "runtime": { "value": null, "rank": "EMPTY", "updatedAtMs": 0, "trace": [] },
                "rating": { "value": null, "rank": "EMPTY", "updatedAtMs": 0, "trace": [] },
                "poster": { "value": null, "rank": "EMPTY", "updatedAtMs": 0, "trace": [] },
                "backdrop": { "value": null, "rank": "EMPTY", "updatedAtMs": 0, "trace": [] },
                "logo": { "value": null, "rank": "EMPTY", "updatedAtMs": 0, "trace": [] },
                "thumbnail": { "value": null, "rank": "EMPTY", "updatedAtMs": 0, "trace": [] },
                "posterProviderTag": { "value": null, "rank": "EMPTY", "updatedAtMs": 0, "trace": [] }
              }
            }
        """.trimIndent()

        val snapshot = gson.fromJson(v2Json, ContinueWatchingMetadataSnapshot::class.java)

        assertNotNull(snapshot)
        assertEquals(1, snapshot.routingVersion)
        assertEquals("tt0944947", snapshot.parentId)
        assertEquals(MetadataPrimaryProvider.TMDB, snapshot.primaryProvider)
        assertEquals(MetadataDecisionReason.PROVIDER_NATIVE_DIRECT, snapshot.decisionReason)
        assertEquals("Game of Thrones", snapshot.clickTimeSlots.title.value)
        assertEquals(DisplaySourceRank.FIRST_PAINT, snapshot.clickTimeSlots.title.rank)
    }

    @Test
    fun `v1 JSON with clickTimeDisplayMetadata projects to slots at FIRST_PAINT rank`() {
        val v1Json = """
            {
              "routingVersion": 1,
              "parentId": "tt0944947",
              "primaryProvider": "TMDB",
              "decisionReason": "PROVIDER_NATIVE_DIRECT",
              "clickTimeDisplayMetadata": {
                "title": "Game of Thrones",
                "description": "An epic fantasy series",
                "imdbRating": 9.2,
                "ratingSource": "IMDB",
                "genres": ["Drama", "Fantasy"]
              }
            }
        """.trimIndent()

        val snapshot = gson.fromJson(v1Json, ContinueWatchingMetadataSnapshot::class.java)

        assertNotNull(snapshot)
        assertEquals("Game of Thrones", snapshot.clickTimeSlots.title.value)
        assertEquals(DisplaySourceRank.FIRST_PAINT, snapshot.clickTimeSlots.title.rank)
        assertEquals("An epic fantasy series", snapshot.clickTimeSlots.overview.value)
        assertEquals(listOf("Drama", "Fantasy"), snapshot.clickTimeSlots.genres.value)
    }

    @Test
    fun `JSON missing both click-time field shapes yields empty slots`() {
        val sparseJson = """
            {
              "routingVersion": 1,
              "parentId": "tt0944947",
              "primaryProvider": "TMDB",
              "decisionReason": "PROVIDER_NATIVE_DIRECT"
            }
        """.trimIndent()

        val snapshot = gson.fromJson(sparseJson, ContinueWatchingMetadataSnapshot::class.java)

        assertNotNull(snapshot)
        assertNull(snapshot.clickTimeSlots.title.value)
        assertEquals(DisplaySourceRank.EMPTY, snapshot.clickTimeSlots.title.rank)
    }
}
