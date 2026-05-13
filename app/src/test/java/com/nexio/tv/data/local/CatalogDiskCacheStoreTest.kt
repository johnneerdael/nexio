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
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogDiskCacheStoreTest {
    private val gson = Gson()

    @Test
    fun `catalog disk cache write sanitizes raw premium poster urls`() {
        val prefs = InMemorySharedPreferences()
        val filesDir = tempDir("catalog-write-sanitize")
        val store = CatalogDiskCacheStore(context = mockContext(prefs, filesDir))

        store.write(
            cacheKey = "catalog-key",
            row = sampleRow(
                poster = "https://api.top-posters.com/secret/imdb/poster/tt123.jpg",
                posterProviderTag = "top_posters"
            ),
            catalogVersionHash = "version"
        )

        val raw = File(filesDir, "catalog-disk-cache-v1/entries.json").readText(Charsets.UTF_8)
        assertFalse(raw.contains("api.top-posters.com"))
        assertFalse(raw.contains("secret"))
        assertFalse(raw.contains("top_posters"))
        assertEquals(emptyMap<String, Any>(), prefs.getAll())
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

    @Test
    fun `catalog disk cache writes to file store and not shared preferences`() {
        val prefs = InMemorySharedPreferences()
        val filesDir = tempDir("catalog-file-store")
        val context = mockContext(prefs, filesDir)
        val store = CatalogDiskCacheStore(context = context)

        store.write(
            cacheKey = "catalog-key",
            row = sampleRow(poster = null, posterProviderTag = null),
            catalogVersionHash = "version"
        )

        assertEquals(emptyMap<String, Any>(), prefs.getAll())
        assertEquals("Popular", store.read("catalog-key")?.catalogRow?.catalogName)
        assertTrue(File(filesDir, "catalog-disk-cache-v1/entries.json").exists())
    }

    @Test
    fun `catalog disk cache migrates legacy shared preferences and clears them`() {
        val prefs = InMemorySharedPreferences()
        val filesDir = tempDir("catalog-legacy")
        val context = mockContext(prefs, filesDir)
        val payload = JsonObject().apply {
            add("catalogRow", gson.toJsonTree(sampleRow(poster = null, posterProviderTag = null)))
            addProperty("catalogVersionHash", "version")
            addProperty("updatedAtMs", 100L)
        }
        prefs.edit().putString("catalog::catalog-key", gson.toJson(payload)).commit()

        val store = CatalogDiskCacheStore(context = context)

        assertEquals("Popular", store.read("catalog-key")?.catalogRow?.catalogName)
        assertEquals(emptyMap<String, Any>(), prefs.getAll())
    }

    @Test
    fun `catalog disk cache migration does not overwrite existing file entry`() {
        val prefs = InMemorySharedPreferences()
        val filesDir = tempDir("catalog-newer-file")
        val context = mockContext(prefs, filesDir)
        val stalePayload = JsonObject().apply {
            add("catalogRow", gson.toJsonTree(sampleRow(poster = null, posterProviderTag = null, catalogName = "Stale")))
            addProperty("catalogVersionHash", "stale")
            addProperty("updatedAtMs", 100L)
        }
        prefs.edit().putString("catalog::catalog-key", gson.toJson(stalePayload)).commit()
        FileBackedJsonObjectStore(File(filesDir, "catalog-disk-cache-v1/entries.json")).put(
            "catalog::catalog-key",
            JsonObject().apply {
                add("catalogRow", gson.toJsonTree(sampleRow(poster = null, posterProviderTag = null, catalogName = "Fresh")))
                addProperty("catalogVersionHash", "fresh")
                addProperty("updatedAtMs", 200L)
            }
        )
        FileBackedJsonObjectStore.resetSharedStateForTest(File(filesDir, "catalog-disk-cache-v1/entries.json"))

        val store = CatalogDiskCacheStore(context = context)

        assertEquals("Fresh", store.read("catalog-key")?.catalogRow?.catalogName)
        assertEquals(emptyMap<String, Any>(), prefs.getAll())
    }

    @Test
    fun `catalog disk cache remove deletes file backed entry`() {
        val prefs = InMemorySharedPreferences()
        val filesDir = tempDir("catalog-remove")
        val store = CatalogDiskCacheStore(context = mockContext(prefs, filesDir))
        store.write(
            cacheKey = "catalog-key",
            row = sampleRow(poster = null, posterProviderTag = null),
            catalogVersionHash = "version"
        )

        store.remove("catalog-key")

        assertNull(store.read("catalog-key"))
        assertFalse(File(filesDir, "catalog-disk-cache-v1/entries.json").readText(Charsets.UTF_8).contains("catalog::catalog-key"))
    }

    private fun sampleRow(
        poster: String?,
        posterProviderTag: String?,
        catalogName: String = "Popular"
    ): CatalogRow {
        return CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://example.com/manifest.json",
            catalogId = "popular",
            catalogName = catalogName,
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

    private fun mockContext(
        prefs: InMemorySharedPreferences,
        filesDir: File = tempDir("catalog-cache")
    ): Context {
        return mockk {
            every { getSharedPreferences("catalog_disk_cache_v1", Context.MODE_PRIVATE) } returns prefs
            every { this@mockk.filesDir } returns filesDir
        }
    }

    private fun tempDir(prefix: String): File {
        return Files.createTempDirectory(prefix).toFile()
    }
}
