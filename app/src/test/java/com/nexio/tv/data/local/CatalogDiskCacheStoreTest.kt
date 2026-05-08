package com.nexio.tv.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogDiskCacheStoreTest {
    private val gson = Gson()

    @Test
    fun `catalog disk cache write sanitizes raw premium poster urls`() {
        val prefs = InMemorySharedPreferences()
        val store = CatalogDiskCacheStore(context = mockContext(prefs))

        store.write(
            cacheKey = "catalog-key",
            row = sampleRow(
                poster = "https://api.top-posters.com/secret/imdb/poster/tt123.jpg",
                posterProviderTag = "top_posters"
            ),
            catalogVersionHash = "version"
        )

        val raw = prefs.getAll().values.single() as String
        assertFalse(raw.contains("api.top-posters.com"))
        assertFalse(raw.contains("secret"))
        assertFalse(raw.contains("top_posters"))
    }

    @Test
    fun `catalog disk cache read sanitizes legacy raw premium poster urls`() {
        val prefs = InMemorySharedPreferences()
        val store = CatalogDiskCacheStore(context = mockContext(prefs))
        val payload = JsonObject().apply {
            add(
                "catalogRow",
                gson.toJsonTree(
                    sampleRow(
                        poster = "https://api.ratingposterdb.com/secret/imdb/poster-default/tt123.jpg",
                        posterProviderTag = "rpdb"
                    )
                )
            )
            addProperty("catalogVersionHash", "version")
            addProperty("updatedAtMs", 100L)
        }
        prefs.edit()
            .putString("catalog::catalog-key", gson.toJson(payload))
            .commit()

        val item = store.read("catalog-key")?.catalogRow?.items?.single()

        assertNull(item?.poster)
        assertNull(item?.posterProviderTag)
    }

    private fun sampleRow(
        poster: String?,
        posterProviderTag: String?
    ): CatalogRow {
        return CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://example.com/manifest.json",
            catalogId = "popular",
            catalogName = "Popular",
            type = ContentType.MOVIE,
            items = listOf(
                MetaPreview(
                    id = "tt123",
                    type = ContentType.MOVIE,
                    rawType = "movie",
                    name = "Sample",
                    poster = poster,
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = "2026",
                    imdbRating = null,
                    genres = emptyList(),
                    posterProviderTag = posterProviderTag
                )
            )
        )
    }

    private fun mockContext(prefs: InMemorySharedPreferences): Context {
        return mockk {
            every { getSharedPreferences("catalog_disk_cache_v1", Context.MODE_PRIVATE) } returns prefs
        }
    }
}
