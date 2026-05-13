package com.nexio.tv.data.local

import android.content.Context
import com.google.gson.JsonObject
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.hydratedHomeOverlayKey
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HydratedHomeOverlayStoreTest {
    @Test
    fun `upsert persists canonical overlay and aliases multiple item keys`() = runTest {
        val prefs = InMemorySharedPreferences()
        val filesDir = tempDir("overlay-store-test")
        val store = HydratedHomeOverlayStore(mockContext(prefs, filesDir))
        val overlay = overlay(itemKey = "movie:tmdb:550")

        store.upsert(overlay, aliases = setOf("movie:tmdb:550", "movie:imdb:tt0137523"))

        FileBackedJsonObjectStore.resetSharedStateForTest(entriesFile(filesDir))
        val recreatedStore = HydratedHomeOverlayStore(mockContext(prefs, filesDir))
        assertEquals(
            "Fight Club",
            recreatedStore.readByCanonicalIdentity(
                canonicalProvider = ProviderId.TMDB,
                canonicalId = "550",
                contentType = ContentType.MOVIE,
                languageTag = "en",
                policyVersion = 1
            )?.fields?.title
        )
        assertEquals(
            setOf("movie:tmdb:550", "movie:imdb:tt0137523"),
            recreatedStore.readForItemKeys(
                itemKeys = setOf("movie:tmdb:550", "movie:imdb:tt0137523"),
                languageTag = "en",
                policyVersion = 1
            ).keys
        )
    }

    @Test
    fun `observeForItemKeys resolves aliases to overlays`() = runTest {
        val store = HydratedHomeOverlayStore(mockContext(InMemorySharedPreferences()))
        val overlay = overlay(itemKey = "movie:tmdb:550")
        val observedFlow = store.observeForItemKeys(
            itemKeys = setOf("movie:imdb:tt0137523"),
            languageTag = "en",
            policyVersion = 1
        )

        assertEquals(emptyMap<String, HydratedHomeOverlay>(), observedFlow.first())
        val nextObserved = async { observedFlow.first { it.isNotEmpty() } }
        runCurrent()
        store.upsert(overlay, aliases = setOf("movie:imdb:tt0137523"))
        advanceTimeBy(50L)
        runCurrent()

        assertEquals("Fight Club", nextObserved.await().getValue("movie:imdb:tt0137523").fields.title)
    }

    @Test
    fun `expired overlays are not returned`() = runTest {
        val store = HydratedHomeOverlayStore(mockContext(InMemorySharedPreferences()))
        val expired = overlay(
            itemKey = "movie:tmdb:550",
            updatedAtMs = 1_000L,
            staleAtMs = 2_000L,
            expiresAtMs = 3_000L
        )

        store.upsert(expired, aliases = setOf("movie:tmdb:550"))

        assertNull(
            store.readByCanonicalIdentity(
                canonicalProvider = ProviderId.TMDB,
                canonicalId = "550",
                contentType = ContentType.MOVIE,
                languageTag = "en",
                policyVersion = 1,
                nowMs = 4_000L
            )
        )
        assertEquals(
            emptyMap<String, HydratedHomeOverlay>(),
            store.readForItemKeys(
                itemKeys = setOf("movie:tmdb:550"),
                languageTag = "en",
                policyVersion = 1,
                nowMs = 4_000L
            )
        )
    }

    @Test
    fun `removeAliases stops item key observation without deleting canonical overlay`() = runTest {
        val store = HydratedHomeOverlayStore(mockContext(InMemorySharedPreferences()))
        val overlay = overlay(itemKey = "movie:tmdb:550")
        store.upsert(overlay, aliases = setOf("movie:tmdb:550", "movie:imdb:tt0137523"))

        store.removeAliases(
            itemKeys = setOf("movie:imdb:tt0137523"),
            languageTag = "en",
            policyVersion = 1
        )

        val observed = store.observeForItemKeys(
            itemKeys = setOf("movie:imdb:tt0137523"),
            languageTag = "en",
            policyVersion = 1
        ).first()

        assertEquals(emptyMap<String, HydratedHomeOverlay>(), observed)
        assertEquals(
            "Fight Club",
            store.readByCanonicalIdentity(
                canonicalProvider = ProviderId.TMDB,
                canonicalId = "550",
                contentType = ContentType.MOVIE,
                languageTag = "en",
                policyVersion = 1
            )?.fields?.title
        )
    }

    @Test
    fun `clearAll removes persisted overlays and aliases`() = runTest {
        val store = HydratedHomeOverlayStore(mockContext(InMemorySharedPreferences()))
        val overlay = overlay(itemKey = "movie:tmdb:550")
        store.upsert(overlay, aliases = setOf("movie:tmdb:550", "movie:imdb:tt0137523"))

        store.clearAll()

        assertNull(
            store.readByCanonicalIdentity(
                canonicalProvider = ProviderId.TMDB,
                canonicalId = "550",
                contentType = ContentType.MOVIE,
                languageTag = "en",
                policyVersion = 1
            )
        )
        assertEquals(
            emptyMap<String, HydratedHomeOverlay>(),
            store.readForItemKeys(
                itemKeys = setOf("movie:tmdb:550", "movie:imdb:tt0137523"),
                languageTag = "en",
                policyVersion = 1
            )
        )
    }

    @Test
    fun `aliases are scoped by language and policy`() = runTest {
        val store = HydratedHomeOverlayStore(mockContext(InMemorySharedPreferences()))
        store.upsert(
            overlay = overlay(itemKey = "movie:tmdb:550", languageTag = "en", policyVersion = 1),
            aliases = setOf("movie:imdb:tt0137523")
        )

        assertEquals(
            emptyMap<String, HydratedHomeOverlay>(),
            store.readForItemKeys(
                itemKeys = setOf("movie:imdb:tt0137523"),
                languageTag = "nl",
                policyVersion = 1
            )
        )
        assertEquals(
            emptyMap<String, HydratedHomeOverlay>(),
            store.readForItemKeys(
                itemKeys = setOf("movie:imdb:tt0137523"),
                languageTag = "en",
                policyVersion = 2
            )
        )
    }

    @Test
    fun `corrupted persisted display hash is ignored`() = runTest {
        val prefs = InMemorySharedPreferences()
        val filesDir = tempDir("overlay-store-corrupt-hash")
        val store = HydratedHomeOverlayStore(mockContext(prefs, filesDir))
        val overlay = overlay(itemKey = "movie:tmdb:550")
        store.upsert(overlay, aliases = setOf("movie:tmdb:550"))

        mutateOverlayEntry(filesDir, overlay.overlayKey) { root ->
            root.getAsJsonObject("value").addProperty("displayHash", "corrupted-display-hash")
        }

        assertNull(
            store.readByCanonicalIdentity(
                canonicalProvider = ProviderId.TMDB,
                canonicalId = "550",
                contentType = ContentType.MOVIE,
                languageTag = "en",
                policyVersion = 1
            )
        )
        assertEquals(
            emptyMap<String, HydratedHomeOverlay>(),
            store.readForItemKeys(
                itemKeys = setOf("movie:tmdb:550"),
                languageTag = "en",
                policyVersion = 1
            )
        )
    }

    @Test
    fun `persisted overlay with mismatched canonical identity is ignored`() = runTest {
        val prefs = InMemorySharedPreferences()
        val filesDir = tempDir("overlay-store-mismatched-canonical")
        val store = HydratedHomeOverlayStore(mockContext(prefs, filesDir))
        val overlay = overlay(itemKey = "movie:tmdb:550")
        store.upsert(overlay, aliases = setOf("movie:tmdb:550"))

        mutateOverlayEntry(filesDir, overlay.overlayKey) { root ->
            root.getAsJsonObject("value").addProperty("canonicalId", "551")
        }

        assertNull(
            store.readByCanonicalIdentity(
                canonicalProvider = ProviderId.TMDB,
                canonicalId = "550",
                contentType = ContentType.MOVIE,
                languageTag = "en",
                policyVersion = 1
            )
        )
    }

    @Test
    fun `persisted overlay with mismatched canonical identity is ignored for aliases`() = runTest {
        val prefs = InMemorySharedPreferences()
        val filesDir = tempDir("overlay-store-mismatched-alias")
        val store = HydratedHomeOverlayStore(mockContext(prefs, filesDir))
        val overlay = overlay(itemKey = "movie:tmdb:550")
        store.upsert(overlay, aliases = setOf("movie:tmdb:550"))

        mutateOverlayEntry(filesDir, overlay.overlayKey) { root ->
            root.getAsJsonObject("value").addProperty("canonicalId", "551")
        }

        assertEquals(
            emptyMap<String, HydratedHomeOverlay>(),
            store.readForItemKeys(
                itemKeys = setOf("movie:tmdb:550"),
                languageTag = "en",
                policyVersion = 1
            )
        )
    }

    @Test
    fun `persisted overlay with mismatched language scope is ignored for aliases`() = runTest {
        val prefs = InMemorySharedPreferences()
        val filesDir = tempDir("overlay-store-mismatched-language")
        val store = HydratedHomeOverlayStore(mockContext(prefs, filesDir))
        val overlay = overlay(itemKey = "movie:tmdb:550")
        store.upsert(overlay, aliases = setOf("movie:tmdb:550"))

        mutateOverlayEntry(filesDir, overlay.overlayKey) { root ->
            root.getAsJsonObject("value").addProperty("languageTag", "nl")
        }

        assertEquals(
            emptyMap<String, HydratedHomeOverlay>(),
            store.readForItemKeys(
                itemKeys = setOf("movie:tmdb:550"),
                languageTag = "en",
                policyVersion = 1
            )
        )
    }

    private fun overlay(
        itemKey: String,
        languageTag: String = "en",
        policyVersion: Int = 1,
        updatedAtMs: Long = 10_000L,
        staleAtMs: Long = Long.MAX_VALUE,
        expiresAtMs: Long = Long.MAX_VALUE
    ): HydratedHomeOverlay {
        val fields = HomeDisplayMetadata(
            title = "Fight Club",
            poster = "poster.jpg",
            imdbRating = 8.8f
        )
        return HydratedHomeOverlay(
            overlayKey = hydratedHomeOverlayKey(
                canonicalProvider = ProviderId.TMDB,
                canonicalId = "550",
                contentType = ContentType.MOVIE,
                languageTag = languageTag,
                policyVersion = policyVersion
            ),
            itemKey = itemKey,
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            imdbId = "tt0137523",
            contentType = ContentType.MOVIE,
            languageTag = languageTag,
            policyVersion = policyVersion,
            fields = fields,
            fieldTrace = listOf(
                HydratedHomeFieldTrace(
                    field = "TITLE",
                    selectedProvider = "TMDB",
                    sourceRole = "PRIMARY"
                )
            ),
            displayHash = fields.hydratedHomeDisplayHash(),
            updatedAtMs = updatedAtMs,
            staleAtMs = staleAtMs,
            expiresAtMs = expiresAtMs
        )
    }

    private fun mockContext(
        prefs: InMemorySharedPreferences,
        filesDir: File = tempDir("overlay-store-test")
    ): Context {
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return context
    }

    private fun mutateOverlayEntry(
        filesDir: File,
        overlayKey: String,
        mutate: (JsonObject) -> Unit
    ) {
        val store = FileBackedJsonObjectStore(File(filesDir, "hydrated-home-overlay-v1/entries.json"))
        val entries = store.entries().toMutableMap()
        val root = entries.getValue("overlay::$overlayKey")
        mutate(root)
        assertTrue(store.replaceAll(entries))
    }

    private fun entriesFile(filesDir: File): File =
        File(filesDir, "hydrated-home-overlay-v1/entries.json")

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()
}
