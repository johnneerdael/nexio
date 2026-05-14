package com.nexio.tv.core.media

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.nexio.tv.data.local.FileBackedJsonObjectStore
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClipTypedStoreTest {
    private val gson = Gson()

    @Test
    fun `v2 round trip stores typed records`() {
        val dir = Files.createTempDirectory("media-clip-typed-v2").toFile()
        val file = File(dir, "media-clip-store-v2/entries.json")
        val store = MediaClipTypedStore(file, gson)
        val record = record(key = "media-clip:one", externalVideoId = "abc123")

        assertTrue(store.putAll(listOf(record)))
        MediaClipTypedStore.resetSharedStateForTest(file)
        val reloaded = MediaClipTypedStore(file, gson)

        assertEquals(record, reloaded.records().single())
        val disk = diskEntries(file)
        assertEquals(2, disk.get("schemaVersion").asInt)
        assertTrue(disk.has("records"))
        assertFalse(disk.has("media-clip:one"))
        assertTrue(disk.getAsJsonObject("records").has("media-clip:one"))
    }

    @Test
    fun `malformed v2 records are skipped without dropping valid records`() {
        val dir = Files.createTempDirectory("media-clip-typed-malformed").toFile()
        val file = File(dir, "media-clip-store-v2/entries.json")
        file.parentFile.mkdirs()
        file.writeText(
            """
            {
              "schemaVersion": 2,
              "records": {
                "media-clip:valid": ${gson.toJson(record(key = "media-clip:valid", externalVideoId = "valid"))},
                "media-clip:bad": { "key": "media-clip:bad" }
              }
            }
            """.trimIndent()
        )

        val store = MediaClipTypedStore(file, gson)
        val valid = record(key = "media-clip:valid", externalVideoId = "valid")

        assertEquals(listOf(valid), store.records())
        assertNull(store.record("media-clip:bad"))
    }

    @Test
    fun `v1 migration preserves valid records`() {
        val dir = Files.createTempDirectory("media-clip-typed-v1").toFile()
        val v1File = File(dir, "media-clip-store-v1/entries.json")
        val v2File = File(dir, "media-clip-store-v2/entries.json")
        val legacy = record(key = "media-clip:legacy", externalVideoId = "legacy")
        FileBackedJsonObjectStore(v1File).putAll(
            mapOf(
                "media-clip:legacy" to gson.toJsonTree(legacy).asJsonObject,
                "media-clip:bad" to JsonObject().apply { addProperty("key", "media-clip:bad") }
            )
        )
        FileBackedJsonObjectStore.resetSharedStateForTest(v1File)

        val store = MediaClipTypedStore(v2File, gson)
        assertTrue(store.migrateFromV1File(v1File))
        MediaClipTypedStore.resetSharedStateForTest(v2File)
        val reloaded = MediaClipTypedStore(v2File, gson)

        assertEquals(legacy, reloaded.record("media-clip:legacy"))
        assertNull(reloaded.record("media-clip:bad"))
        assertTrue(v2File.exists())
    }

    @Test
    fun `v1 migration does not overwrite existing v2 record`() {
        val dir = Files.createTempDirectory("media-clip-typed-no-overwrite").toFile()
        val v1File = File(dir, "media-clip-store-v1/entries.json")
        val v2File = File(dir, "media-clip-store-v2/entries.json")
        val store = MediaClipTypedStore(v2File, gson)
        val current = record(key = "media-clip:same", externalVideoId = "file")
        assertTrue(store.putAll(listOf(current)))
        FileBackedJsonObjectStore(v1File).put(
            "media-clip:same",
            gson.toJsonTree(record(key = "media-clip:same", externalVideoId = "legacy")).asJsonObject
        )
        FileBackedJsonObjectStore.resetSharedStateForTest(v1File)

        assertTrue(store.migrateFromV1File(v1File))
        MediaClipTypedStore.resetSharedStateForTest(v2File)
        val reloaded = MediaClipTypedStore(v2File, gson)

        assertEquals(current, reloaded.record("media-clip:same"))
    }

    private fun diskEntries(file: File): JsonObject {
        FileInputStream(file).use { fis ->
            BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                JsonReader(br).use { reader ->
                    return gson.fromJson(reader, JsonObject::class.java)
                }
            }
        }
    }

    private fun record(key: String, externalVideoId: String): StoredMediaClipRecord =
        StoredMediaClipRecord(
            key = key,
            clipId = "tmdb:movie:550:$externalVideoId",
            contentId = "tmdb:550",
            itemType = "movie",
            tmdbId = "550",
            tvdbId = null,
            imdbId = "tt0137523",
            kitsuId = null,
            provider = "TMDB",
            source = MediaClipSource.PROVIDER.name,
            scopeKind = "title",
            season = null,
            episode = null,
            clipType = MediaClipType.TRAILER.name,
            title = "Official Trailer",
            language = "en",
            site = ClipSite.YOUTUBE.name,
            externalVideoId = externalVideoId,
            playbackKind = "youtube",
            youtubeId = externalVideoId,
            providerUrlHash = null,
            redactedUrl = null,
            confidence = Confidence.HIGH.name,
            fetchedAtMs = 1_000L,
            expiresAtMs = 2_000L,
            staleUntilMs = 3_000L,
            sourceTrace = listOf("tmdb.movie.videos")
        )
}
