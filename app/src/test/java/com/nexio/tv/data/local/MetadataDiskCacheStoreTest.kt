package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCompanyKind
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataDiskCacheStoreTest {

    @Test
    fun `removeHomeUnreferencedMetaEntries evicts dropped feed metadata`() {
        val store = MetadataDiskCacheStore(
            context = mockContext(InMemorySharedPreferences())
        )

        store.writeMeta("movie:tt1", "en", "native", meta("tt1"))
        store.writeMeta("movie:tt2", "en", "native", meta("tt2"))
        store.replaceHomeFeedReferences("home_catalog_snapshot", setOf("movie:tt1"))

        store.removeHomeUnreferencedMetaEntries()

        assertNotNull(store.readMeta("movie:tt1", "en", "native"))
        assertNull(store.readMeta("movie:tt2", "en", "native"))
    }

    @Test
    fun `removeEntriesFromStaleEpochs evicts metadata after locale epoch change`() {
        val store = MetadataDiskCacheStore(
            context = mockContext(InMemorySharedPreferences())
        )

        store.writeMeta("movie:tt1", "en", "native", meta("tt1"))
        assertNotNull(store.readMeta("movie:tt1", "en", "native"))

        store.bumpLanguageEpoch()
        store.removeEntriesFromStaleEpochs()

        assertNull(store.readMeta("movie:tt1", "en", "native"))
    }

    @Test
    fun `readTmdbEnrichment preserves legacy companies cached before tmdbId and kind fields existed`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)
        val store = MetadataDiskCacheStore(context = context)
        val key = "tmdb::123:MOVIE::en::native"
        prefs.edit().putString(
            key,
            """
            {
              "value": {
                "localizedTitle": "Legacy Movie",
                "description": null,
                "genres": [],
                "backdrop": null,
                "logo": null,
                "poster": null,
                "directorMembers": [],
                "writerMembers": [],
                "castMembers": [],
                "releaseInfo": null,
                "rating": null,
                "runtimeMinutes": null,
                "director": [],
                "writer": [],
                "productionCompanies": [
                  {
                    "name": "Lucasfilm Ltd.",
                    "logo": "https://image.tmdb.org/t/p/w300/logo.png"
                  }
                ],
                "networks": [
                  {
                    "name": "HBO",
                    "logo": "https://image.tmdb.org/t/p/w300/network.png"
                  }
                ],
                "ageRating": null,
                "countries": null,
                "language": null,
                "collectionId": null,
                "collectionName": null
              },
              "languageEpoch": 0,
              "updatedAtMs": 1
            }
            """.trimIndent()
        ).apply()

        val enrichment = store.readTmdbEnrichment("123:MOVIE", "en", "native")

        assertNotNull(enrichment)
        assertEquals(1, enrichment?.productionCompanies?.size)
        assertEquals("Lucasfilm Ltd.", enrichment?.productionCompanies?.single()?.name)
        assertEquals(MetaCompanyKind.COMPANY, enrichment?.productionCompanies?.single()?.kind)
        assertEquals(1, enrichment?.networks?.size)
        assertEquals("HBO", enrichment?.networks?.single()?.name)
        assertEquals(MetaCompanyKind.NETWORK, enrichment?.networks?.single()?.kind)
    }

    @Test
    fun `readTmdbEnrichment ignores cached entries from before current schema version`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)
        val store = MetadataDiskCacheStore(context = context)
        prefs.edit().putString(
            "tmdb::123:MOVIE::en::native",
            """
            {
              "value": {
                "localizedTitle": "Legacy Movie",
                "description": null,
                "genres": [],
                "backdrop": null,
                "logo": null,
                "poster": null,
                "directorMembers": [],
                "writerMembers": [],
                "castMembers": [],
                "productionCompanies": [],
                "networks": [],
                "director": [],
                "writer": [],
                "releaseInfo": null,
                "rating": null,
                "runtimeMinutes": null,
                "ageRating": null,
                "countries": null,
                "language": null,
                "collectionId": null,
                "collectionName": null
              },
              "languageEpoch": 0,
              "updatedAtMs": 1
            }
            """.trimIndent()
        ).apply()

        val enrichment = store.readTmdbEnrichment("123:MOVIE", "en", "native")

        assertNull(enrichment)
    }

    private fun meta(id: String): Meta {
        return Meta(
            id = id,
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Movie $id",
            poster = "poster$id",
            posterShape = PosterShape.POSTER,
            background = "background$id",
            logo = "logo$id",
            description = "description$id",
            releaseInfo = "2024",
            imdbRating = 8.0f,
            genres = listOf("Drama"),
            runtime = "120m",
            director = emptyList(),
            cast = emptyList(),
            videos = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList()
        )
    }

    private fun mockContext(prefs: InMemorySharedPreferences): Context {
        return mockk {
            every { getSharedPreferences("metadata_disk_cache_v1", Context.MODE_PRIVATE) } returns prefs
        }
    }
}
