package com.nexio.tv.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HydratedHomeOverlayStorePersistenceTest {

    private fun newStore(
        prefs: InMemorySharedPreferences = InMemorySharedPreferences(),
        filesDir: File = tempDir("overlay-store")
    ): Pair<HydratedHomeOverlayStore, InMemorySharedPreferences> {
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return HydratedHomeOverlayStore(context) to prefs
    }

    private fun overlay(
        stableIdsSnapshot: ProviderIds,
        settingsSignature: String
    ): HydratedHomeOverlay {
        val fields = HomeDisplayMetadata(title = "Fight Club", poster = "rpdb://550.jpg")
        return HydratedHomeOverlay(
            overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
            itemKey = "movie:tmdb:550",
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            imdbId = stableIdsSnapshot.imdb,
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1,
            fields = fields,
            fieldTrace = emptyList(),
            displayHash = fields.hydratedHomeDisplayHash(),
            updatedAtMs = 1L,
            staleAtMs = Long.MAX_VALUE,
            expiresAtMs = Long.MAX_VALUE,
            state = HomeItemHydrationState.CANONICAL_READY,
            stableIdsSnapshot = stableIdsSnapshot,
            settingsSignature = settingsSignature
        )
    }

    @Test
    fun `v2 round-trip preserves stableIdsSnapshot and settingsSignature`() = runTest {
        val filesDir = tempDir("overlay-store-round-trip")
        val (store, prefs) = newStore(filesDir = filesDir)
        val v2 = overlay(
            stableIdsSnapshot = ProviderIds(tmdb = "550", imdb = "tt0137523"),
            settingsSignature = "p=rpdb;l=default;b=default;t=default;v=1"
        )
        store.upsert(v2, aliases = setOf("movie:tmdb:550"))
        val disk = diskEntries(filesDir)
        val overlayEntry = disk.getAsJsonObject("overlay::${v2.overlayKey}")
        val aliasEntry = disk.getAsJsonObject("alias::en::policy:1::movie:tmdb:550")

        assertEquals(1, overlayEntry.get("schemaVersion").asInt)
        assertEquals("tt0137523", overlayEntry.getAsJsonObject("value").getAsJsonObject("stableIdsSnapshot").get("imdb").asString)
        assertEquals(v2.overlayKey, aliasEntry.get("overlayKey").asString)
        FileBackedJsonObjectStore.resetSharedStateForTest(entriesFile(filesDir))

        // Reinstantiate the store over the same prefs — equivalent to a cold-start re-read.
        val ctx2 = mockk<Context>()
        every { ctx2.filesDir } returns filesDir
        every { ctx2.getSharedPreferences(any(), any()) } returns prefs
        val reloaded = HydratedHomeOverlayStore(ctx2)

        val out = reloaded.readByCanonicalIdentity(
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1
        )!!

        assertEquals(ProviderIds(tmdb = "550", imdb = "tt0137523"), out.stableIdsSnapshot)
        assertEquals("p=rpdb;l=default;b=default;t=default;v=1", out.settingsSignature)
        assertEquals("Fight Club", out.fields.title)
    }

    @Test
    fun `v1 record without new fields loads with empty defaults`() = runTest {
        val prefs = InMemorySharedPreferences()
        // Hand-craft a v1-shape JSON payload: serialize a current overlay, then strip the
        // two new keys to simulate the on-disk shape that existed before Task 6 added
        // stableIdsSnapshot / settingsSignature to the model. schemaVersion stays at 1 —
        // the store's stored schema version did not bump; only the value's field set
        // expanded, and Gson defaults missing fields on the data class side.
        val gson = Gson()
        val v1Style = overlay(
            stableIdsSnapshot = ProviderIds(tmdb = "550"),
            settingsSignature = "p=default;l=default;b=default;t=default;v=1"
        )
        val valueTree = gson.toJsonTree(v1Style).asJsonObject
        valueTree.remove("stableIdsSnapshot")
        valueTree.remove("settingsSignature")
        val payload = JsonObject().apply {
            add("value", valueTree)
            addProperty("schemaVersion", 1)
        }
        prefs.edit()
            .putString("overlay::${v1Style.overlayKey}", gson.toJson(payload))
            .putString(
                "alias::en::policy:1::movie:tmdb:550",
                v1Style.overlayKey
            )
            .apply()

        val (store, _) = newStore(prefs)
        val out = store.readByCanonicalIdentity(
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1
        )!!

        assertEquals(ProviderIds(), out.stableIdsSnapshot)
        assertEquals("", out.settingsSignature)
        // Rest of the overlay still loads correctly.
        assertEquals("Fight Club", out.fields.title)
    }

    @Test
    fun `upsert writes overlays to file store and not shared preferences`() = runTest {
        val prefs = InMemorySharedPreferences()
        val filesDir = tempDir("overlay-file-store")
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val store = HydratedHomeOverlayStore(context)
        val value = overlay(
            stableIdsSnapshot = ProviderIds(tmdb = "550", imdb = "tt0137523"),
            settingsSignature = "p=rpdb;l=default;b=default;t=default;v=1"
        )

        store.upsert(value, aliases = setOf("movie:tmdb:550", "movie:imdb:tt0137523"))

        assertEquals(emptyMap<String, Any>(), prefs.getAll())
        val disk = diskEntries(filesDir)
        assertTrue(disk.has("overlay::${value.overlayKey}"))
        assertEquals(value.overlayKey, disk.getAsJsonObject("alias::en::policy:1::movie:imdb:tt0137523").get("overlayKey").asString)
        FileBackedJsonObjectStore.resetSharedStateForTest(entriesFile(filesDir))
        val reloadedContext = mockk<Context>()
        every { reloadedContext.filesDir } returns filesDir
        every { reloadedContext.getSharedPreferences(any(), any()) } returns prefs
        val reloaded = HydratedHomeOverlayStore(reloadedContext)
        assertEquals(
            "Fight Club",
            reloaded.readForItemKeys(
                itemKeys = setOf("movie:imdb:tt0137523"),
                languageTag = "en",
                policyVersion = 1
            ).getValue("movie:imdb:tt0137523").fields.title
        )
        assertTrue(File(filesDir, "hydrated-home-overlay-v1/entries.json").exists())
    }

    @Test
    fun `legacy shared preferences overlays migrate then clear legacy prefs`() = runTest {
        val prefs = InMemorySharedPreferences()
        val filesDir = tempDir("overlay-legacy")
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val legacy = overlay(
            stableIdsSnapshot = ProviderIds(tmdb = "550", imdb = "tt0137523"),
            settingsSignature = "p=rpdb;l=default;b=default;t=default;v=1"
        )
        val payload = JsonObject().apply {
            add("value", Gson().toJsonTree(legacy))
            addProperty("schemaVersion", 1)
        }
        prefs.edit()
            .putString("overlay::${legacy.overlayKey}", Gson().toJson(payload))
            .putString("alias::en::policy:1::movie:tmdb:550", legacy.overlayKey)
            .commit()

        val store = HydratedHomeOverlayStore(context)
        val out = store.readForItemKeys(
            itemKeys = setOf("movie:tmdb:550"),
            languageTag = "en",
            policyVersion = 1
        ).getValue("movie:tmdb:550")

        assertEquals("Fight Club", out.fields.title)
        assertEquals(emptyMap<String, Any>(), prefs.getAll())
        val disk = diskEntries(filesDir)
        assertEquals("Fight Club", disk.getAsJsonObject("overlay::${legacy.overlayKey}")
            .getAsJsonObject("value")
            .getAsJsonObject("fields")
            .get("title")
            .asString)
        assertEquals(legacy.overlayKey, disk.getAsJsonObject("alias::en::policy:1::movie:tmdb:550").get("overlayKey").asString)
    }

    @Test
    fun `legacy migration does not overwrite existing file entries`() = runTest {
        val prefs = InMemorySharedPreferences()
        val filesDir = tempDir("overlay-legacy-existing")
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val current = overlay(
            stableIdsSnapshot = ProviderIds(tmdb = "550", imdb = "tt0137523"),
            settingsSignature = "p=current;l=default;b=default;t=default;v=1"
        )
        HydratedHomeOverlayStore(context).upsert(current, aliases = setOf("movie:tmdb:550"))
        val stale = current.withTitle("Legacy Cut")
        val payload = JsonObject().apply {
            add("value", Gson().toJsonTree(stale))
            addProperty("schemaVersion", 1)
        }
        prefs.edit()
            .putString("overlay::${stale.overlayKey}", Gson().toJson(payload))
            .putString("alias::en::policy:1::movie:tmdb:550", stale.overlayKey)
            .commit()

        FileBackedJsonObjectStore.resetSharedStateForTest(entriesFile(filesDir))
        val reloaded = HydratedHomeOverlayStore(context)
        val out = reloaded.readForItemKeys(
            itemKeys = setOf("movie:tmdb:550"),
            languageTag = "en",
            policyVersion = 1
        ).getValue("movie:tmdb:550")

        assertEquals("Fight Club", out.fields.title)
        assertEquals(emptyMap<String, Any>(), prefs.getAll())
        assertEquals("Fight Club", diskEntries(filesDir)
            .getAsJsonObject("overlay::${current.overlayKey}")
            .getAsJsonObject("value")
            .getAsJsonObject("fields")
            .get("title")
            .asString)
    }

    @Test
    fun `clearAll removes only overlay store entries`() = runTest {
        val prefs = InMemorySharedPreferences()
        val filesDir = tempDir("overlay-clear-preserve")
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val store = HydratedHomeOverlayStore(context)
        val value = overlay(
            stableIdsSnapshot = ProviderIds(tmdb = "550", imdb = "tt0137523"),
            settingsSignature = "p=rpdb;l=default;b=default;t=default;v=1"
        )
        store.upsert(value, aliases = setOf("movie:tmdb:550"))
        assertTrue(FileBackedJsonObjectStore(entriesFile(filesDir)).put(
            "unrelated::entry",
            JsonObject().apply { addProperty("name", "keep") }
        ))

        store.clearAll()

        val disk = diskEntries(filesDir)
        assertEquals(setOf("unrelated::entry"), disk.keySet())
        assertEquals("keep", disk.getAsJsonObject("unrelated::entry").get("name").asString)
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    private fun entriesFile(filesDir: File): File =
        File(filesDir, "hydrated-home-overlay-v1/entries.json")

    private fun diskEntries(filesDir: File): JsonObject {
        val file = entriesFile(filesDir)
        assertTrue(file.exists())
        FileInputStream(file).use { fis ->
            BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                JsonReader(br).use { reader ->
                    return Gson().fromJson(reader, JsonObject::class.java)
                }
            }
        }
    }

    private fun HydratedHomeOverlay.withTitle(title: String): HydratedHomeOverlay {
        val updatedFields = fields.copy(title = title)
        return copy(
            fields = updatedFields,
            displayHash = updatedFields.hydratedHomeDisplayHash()
        )
    }
}
