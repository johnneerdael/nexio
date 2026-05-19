package com.nexio.tv.core.artwork

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkRemoteSourceStoreTest {
    private val temp = TemporaryFolder().also { it.create() }

    @Test
    fun `debounced puts update memory without synchronously flushing file`() {
        val file = temp.newFile("remote-sources.json").also { it.delete() }
        val store = FileBackedArtworkRemoteSourceStore(
            file = file,
            gson = Gson(),
            writeDebounceMs = 60_000L
        )

        store.put("hash-a", SensitiveArtworkUrl.of("https://image.example/a.jpg"))
        store.put("hash-b", SensitiveArtworkUrl.of("https://image.example/b.jpg"))

        assertFalse(file.exists())
        assertEquals("https://image.example/a.jpg", store.get("hash-a")?.value)
        assertEquals("https://image.example/b.jpg", store.get("hash-b")?.value)

        store.flushPendingWritesForTest()
        val restarted = FileBackedArtworkRemoteSourceStore(file, Gson())

        assertEquals("https://image.example/a.jpg", restarted.get("hash-a")?.value)
        assertEquals("https://image.example/b.jpg", restarted.get("hash-b")?.value)
    }

    @Test
    fun `premium provider raw urls are removed without synchronous debounced flush`() {
        val file = temp.newFile("remote-sources-premium.json").also { it.delete() }
        val store = FileBackedArtworkRemoteSourceStore(
            file = file,
            gson = Gson(),
            writeDebounceMs = 60_000L
        )

        store.put("hash-a", SensitiveArtworkUrl.of("https://image.example/a.jpg"))
        store.flushPendingWritesForTest()

        store.put("hash-a", SensitiveArtworkUrl.of("https://api.ratingposterdb.com/poster.jpg"))

        assertFalse("debounced removal should not synchronously rewrite the file", file.readText().isEmpty())
        assertNull(store.get("hash-a"))

        store.flushPendingWritesForTest()
        val restarted = FileBackedArtworkRemoteSourceStore(file, Gson())

        assertNull(restarted.get("hash-a"))
    }
}
