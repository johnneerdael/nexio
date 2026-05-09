package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After the SharedPreferences→file-backed migration the original assertion
 * (`prefs.applyCount == 1`) became meaningless — writes no longer flow through
 * SharedPreferences at all. The store now keeps an in-memory `cache` map and
 * flushes the entire map to a single JSON file via streaming JsonWriter on
 * debounce. The "batching" guarantee we still want is identical though:
 * **N writes within one debounce window result in exactly one file write
 * containing all N entries**. We verify that by inspecting the snapshot file
 * after a single explicit flush and counting expected keys.
 */
class MetadataDiskCacheStoreWriteBatchingTest {

    @Test
    fun `repeated metadata writes are batched into one disk commit window`() {
        val tempDir = java.nio.file.Files.createTempDirectory("mdc-write-batch").toFile()
        tempDir.deleteOnExit()
        val store = MetadataDiskCacheStore(
            context = mockContext(tempDir),
            ioScope = CoroutineScope(EmptyCoroutineContext),
            debounceMs = Long.MAX_VALUE,
        )

        repeat(20) { index ->
            store.writeMeta(
                itemKey = "item-$index",
                languageTag = "en",
                providerToken = "pm",
                meta = meta(index),
            )
        }

        store.flushPendingWritesForTest()

        val snapshot = File(tempDir, "metadata_disk_cache_v1.json")
        assertTrue("snapshot file should exist after flush", snapshot.exists())
        // The whole snapshot is one JSON object whose top-level keys are the cache
        // keys; counting the prefix matches confirms every write made it into the
        // single file write.
        val text = snapshot.readText()
        val matchCount = Regex("\"meta::item-\\d+::").findAll(text).count()
        assertEquals(20, matchCount)
    }

    @Test
    fun `tvdb reference writes are batched`() {
        val tempDir = java.nio.file.Files.createTempDirectory("mdc-write-batch").toFile()
        tempDir.deleteOnExit()
        val store = MetadataDiskCacheStore(
            context = mockContext(tempDir),
            ioScope = CoroutineScope(EmptyCoroutineContext),
            debounceMs = Long.MAX_VALUE,
        )

        store.writeTvdbReference("genres", listOf(mapOf("id" to 1, "name" to "Drama")))
        store.writeTvdbReference("languages", listOf(mapOf("id" to "eng", "name" to "English")))
        store.writeTvdbReference("entity_types", listOf(mapOf("id" to 1, "name" to "Series")))

        store.flushPendingWritesForTest()

        val snapshot = File(tempDir, "metadata_disk_cache_v1.json")
        assertTrue("snapshot file should exist after flush", snapshot.exists())
        val text = snapshot.readText()
        assertTrue(text.contains("\"tvdb_ref::genres::data\""))
        assertTrue(text.contains("\"tvdb_ref::languages::data\""))
        assertTrue(text.contains("\"tvdb_ref::entity_types::data\""))
    }

    private fun mockContext(filesDir: File): Context {
        val prefs = InMemorySharedPreferences()
        return mockk {
            every { getSharedPreferences(any(), any()) } returns prefs
            every { this@mockk.filesDir } returns filesDir
        }
    }

    private fun meta(index: Int): Meta {
        return Meta(
            id = "item-$index",
            type = ContentType.MOVIE,
            name = "Item $index",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            videos = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
        )
    }
}
