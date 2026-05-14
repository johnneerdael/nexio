# Typed Cache Stores Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `HydratedHomeOverlayStore` and `MediaClipStore` resident `JsonObject` cache state with typed v2 file-backed stores while preserving current behavior and migrations.

**Architecture:** Add two focused typed stores, one for hydrated overlay aliases/overlays and one for media clip records. Each store owns streaming `JsonReader`/`JsonWriter` IO, atomic temp-file replacement, in-memory typed maps, v1 file migration, and legacy SharedPreferences migration; existing public store APIs stay unchanged.

**Tech Stack:** Kotlin, Android `Context.filesDir`, Gson `JsonReader`/`JsonWriter`, JUnit/Robolectric, coroutines test, adb, heaptrail.

---

## File Structure

- Create `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayTypedStore.kt`
  - Owns `files/hydrated-home-overlay-v2/entries.json`.
  - Keeps resident state as `LinkedHashMap<String, String>` aliases and `LinkedHashMap<String, HydratedHomeOverlay>` overlays.
  - Migrates from `files/hydrated-home-overlay-v1/entries.json` and legacy SharedPreferences-compatible entries supplied by `HydratedHomeOverlayStore`.

- Modify `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt`
  - Replace `FileBackedJsonObjectStore` usage with `HydratedHomeOverlayTypedStore`.
  - Keep public behavior, stale tracking, key shapes, validation, and trace behavior.

- Test `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayTypedStoreTest.kt`
  - Covers v2 round trip, malformed entry skipping, v1 migration, and no-overwrite behavior.

- Modify `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStorePersistenceTest.kt`
  - Update disk assertions to v2 shape and add compatibility assertions for v1 migration.

- Create `app/src/main/java/com/nexio/tv/core/media/MediaClipTypedStore.kt`
  - Owns `files/media-clip-store-v2/entries.json`.
  - Keeps resident state as `LinkedHashMap<String, StoredMediaClipRecord>`.
  - Migrates from `files/media-clip-store-v1/entries.json` and legacy SharedPreferences-compatible entries supplied by `MediaClipStore`.

- Modify `app/src/main/java/com/nexio/tv/core/media/MediaClipStore.kt`
  - Replace `FileBackedJsonObjectStore` usage with `MediaClipTypedStore`.
  - Move `StoredMediaClipRecord` out of `private` visibility so the typed store can use it in the same package.

- Test `app/src/test/java/com/nexio/tv/core/media/MediaClipTypedStoreTest.kt`
  - Covers v2 round trip, malformed entry skipping, v1 migration, and no-overwrite behavior.

- Modify `app/src/test/java/com/nexio/tv/core/media/MediaClipStoreTest.kt`
  - Update persistence assertions to v2 shape and keep all behavior tests passing.

Do not modify `DurableArtworkDecisionCache`, artwork decision models, thumbnail routing, `CatalogDiskCacheStore`, addon manifests, or metadata cache in this plan.

---

### Task 1: Add Failing Hydrated Overlay Typed Store Tests

**Files:**
- Create: `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayTypedStoreTest.kt`
- Read-only reference: `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt`

- [ ] **Step 1: Write the failing test file**

Create `HydratedHomeOverlayTypedStoreTest.kt` with these tests:

```kotlin
package com.nexio.tv.data.local

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HydratedHomeOverlayTypedStoreTest {
    private val gson = Gson()

    @Test
    fun `v2 round trip stores aliases as strings and overlays as typed values`() {
        val dir = Files.createTempDirectory("overlay-typed-v2").toFile()
        val store = HydratedHomeOverlayTypedStore(File(dir, "hydrated-home-overlay-v2/entries.json"), gson)
        val overlay = overlay(title = "Fight Club")

        assertTrue(store.upsert(overlay, setOf("alias::en::policy:1::movie:tmdb:550")))
        HydratedHomeOverlayTypedStore.resetSharedStateForTest(File(dir, "hydrated-home-overlay-v2/entries.json"))
        val reloaded = HydratedHomeOverlayTypedStore(File(dir, "hydrated-home-overlay-v2/entries.json"), gson)

        assertEquals(overlay.overlayKey, reloaded.aliasOverlayKey("alias::en::policy:1::movie:tmdb:550"))
        assertEquals("Fight Club", reloaded.overlay(overlay.overlayKey)?.fields?.title)
        val raw = File(dir, "hydrated-home-overlay-v2/entries.json").readText()
        assertTrue(raw.contains("\"aliases\""))
        assertTrue(raw.contains("\"overlays\""))
        assertFalse(raw.contains("\"overlayKey\":{\""))
    }

    @Test
    fun `malformed v2 entries are skipped without dropping valid entries`() {
        val dir = Files.createTempDirectory("overlay-typed-malformed").toFile()
        val file = File(dir, "hydrated-home-overlay-v2/entries.json")
        file.parentFile.mkdirs()
        file.writeText(
            """
            {
              "schemaVersion": 2,
              "aliases": {
                "alias::en::policy:1::movie:tmdb:550": "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
                "alias::bad": { "not": "a string" }
              },
              "overlays": {
                "canonical:TMDB:550:type:MOVIE:lang:en:policy:1": {
                  "schemaVersion": 1,
                  "value": ${gson.toJson(overlay(title = "Fight Club"))}
                },
                "canonical:bad": { "schemaVersion": 1, "value": "bad" }
              }
            }
            """.trimIndent()
        )

        val store = HydratedHomeOverlayTypedStore(file, gson)

        assertEquals("canonical:TMDB:550:type:MOVIE:lang:en:policy:1", store.aliasOverlayKey("alias::en::policy:1::movie:tmdb:550"))
        assertEquals("Fight Club", store.overlay("canonical:TMDB:550:type:MOVIE:lang:en:policy:1")?.fields?.title)
        assertNull(store.aliasOverlayKey("alias::bad"))
        assertNull(store.overlay("canonical:bad"))
    }

    @Test
    fun `v1 file migration preserves valid aliases and overlays`() {
        val dir = Files.createTempDirectory("overlay-typed-v1").toFile()
        val v1File = File(dir, "hydrated-home-overlay-v1/entries.json")
        val v2File = File(dir, "hydrated-home-overlay-v2/entries.json")
        val overlay = overlay(title = "Legacy")
        FileBackedJsonObjectStore(v1File).putAll(
            mapOf(
                "overlay::${overlay.overlayKey}" to JsonObject().apply {
                    addProperty("schemaVersion", 1)
                    add("value", gson.toJsonTree(overlay))
                },
                "alias::en::policy:1::movie:tmdb:550" to JsonObject().apply {
                    addProperty("overlayKey", overlay.overlayKey)
                },
                "alias::bad" to JsonObject()
            )
        )
        FileBackedJsonObjectStore.resetSharedStateForTest(v1File)

        val store = HydratedHomeOverlayTypedStore(v2File, gson)
        assertTrue(store.migrateFromV1File(v1File))

        assertEquals(overlay.overlayKey, store.aliasOverlayKey("alias::en::policy:1::movie:tmdb:550"))
        assertEquals("Legacy", store.overlay(overlay.overlayKey)?.fields?.title)
        assertTrue(v2File.exists())
    }

    @Test
    fun `v1 migration does not overwrite existing v2 entries`() {
        val dir = Files.createTempDirectory("overlay-typed-no-overwrite").toFile()
        val v1File = File(dir, "hydrated-home-overlay-v1/entries.json")
        val v2File = File(dir, "hydrated-home-overlay-v2/entries.json")
        val current = overlay(title = "Current")
        val stale = overlay(title = "Stale")
        val store = HydratedHomeOverlayTypedStore(v2File, gson)
        assertTrue(store.upsert(current, setOf("alias::en::policy:1::movie:tmdb:550")))
        FileBackedJsonObjectStore(v1File).putAll(
            mapOf(
                "overlay::${stale.overlayKey}" to JsonObject().apply {
                    addProperty("schemaVersion", 1)
                    add("value", gson.toJsonTree(stale))
                },
                "alias::en::policy:1::movie:tmdb:550" to JsonObject().apply {
                    addProperty("overlayKey", stale.overlayKey)
                }
            )
        )
        FileBackedJsonObjectStore.resetSharedStateForTest(v1File)

        assertTrue(store.migrateFromV1File(v1File))

        assertEquals(current.overlayKey, store.aliasOverlayKey("alias::en::policy:1::movie:tmdb:550"))
        assertEquals("Current", store.overlay(current.overlayKey)?.fields?.title)
    }

    private fun overlay(title: String): HydratedHomeOverlay {
        val fields = HomeDisplayMetadata(title = title, poster = "rpdb://550.jpg")
        return HydratedHomeOverlay(
            overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
            itemKey = "movie:tmdb:550",
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            imdbId = "tt0137523",
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
            stableIdsSnapshot = ProviderIds(tmdb = "550", imdb = "tt0137523"),
            settingsSignature = "settings"
        )
    }
}
```

- [ ] **Step 2: Run the new test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.HydratedHomeOverlayTypedStoreTest
```

Expected: compilation fails with `Unresolved reference: HydratedHomeOverlayTypedStore`.

- [ ] **Step 3: Commit the failing tests**

Use explicit paths:

```bash
git add app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayTypedStoreTest.kt
git commit -m "test: cover typed hydrated overlay store"
```

---

### Task 2: Implement HydratedHomeOverlayTypedStore

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayTypedStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayTypedStoreTest.kt`

- [ ] **Step 1: Create the typed store implementation**

Create `HydratedHomeOverlayTypedStore.kt`:

```kotlin
package com.nexio.tv.data.local

import com.google.gson.Gson
import com.google.gson.JsonIOException
import com.google.gson.JsonParseException
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderIds
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

internal class HydratedHomeOverlayTypedStore(
    private val file: File,
    private val gson: Gson
) {
    private data class SharedState(
        val lock: Any = Any(),
        var loaded: Boolean = false,
        val aliases: LinkedHashMap<String, String> = linkedMapOf(),
        val overlays: LinkedHashMap<String, HydratedHomeOverlay> = linkedMapOf()
    )

    private data class Snapshot(
        val aliases: LinkedHashMap<String, String>,
        val overlays: LinkedHashMap<String, HydratedHomeOverlay>
    )

    private val state = stateFor(file)

    fun aliasOverlayKey(aliasKey: String): String? = synchronized(state.lock) {
        ensureLoadedLocked()
        state.aliases[aliasKey.trim()]
    }

    fun overlay(overlayKey: String): HydratedHomeOverlay? = synchronized(state.lock) {
        ensureLoadedLocked()
        state.overlays[overlayKey.trim()]?.normalizeDefaults()
    }

    fun aliasKeys(): Set<String> = synchronized(state.lock) {
        ensureLoadedLocked()
        state.aliases.keys.toSet()
    }

    fun upsert(overlay: HydratedHomeOverlay, aliasKeys: Set<String>): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        val snapshot = snapshotLocked()
        val cleanOverlayKey = overlay.overlayKey.trim().takeIf { it.isNotEmpty() } ?: return false
        snapshot.overlays[cleanOverlayKey] = overlay.normalizeDefaults()
        for (aliasKey in aliasKeys) {
            val cleanAliasKey = aliasKey.trim().takeIf { it.isNotEmpty() } ?: continue
            snapshot.aliases[cleanAliasKey] = cleanOverlayKey
        }
        writeAndSwapLocked(snapshot)
    }

    fun removeAliases(aliasKeys: Collection<String>): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        val clean = aliasKeys.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        if (clean.isEmpty()) return true
        if (clean.none(state.aliases::containsKey)) return true
        val snapshot = snapshotLocked()
        for (aliasKey in clean) snapshot.aliases.remove(aliasKey)
        writeAndSwapLocked(snapshot)
    }

    fun clearAll(): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        if (state.aliases.isEmpty() && state.overlays.isEmpty()) return true
        writeAndSwapLocked(Snapshot(linkedMapOf(), linkedMapOf()))
    }

    fun migrateFromV1File(v1File: File): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        if (!v1File.isFile) return true
        val snapshot = snapshotLocked()
        var changed = false
        val v1Store = FileBackedJsonObjectStore(v1File)
        for ((key, value) in v1Store.entries()) {
            when {
                key.startsWith(OVERLAY_PREFIX) && key !in snapshot.overlays -> {
                    val schemaVersion = value.get("schemaVersion")?.asInt ?: continue
                    if (schemaVersion != OVERLAY_VALUE_SCHEMA_VERSION) continue
                    val overlay = runCatching {
                        gson.fromJson(value.get("value"), HydratedHomeOverlay::class.java)
                    }.getOrNull()?.normalizeDefaults() ?: continue
                    snapshot.overlays[key.removePrefix(OVERLAY_PREFIX)] = overlay
                    changed = true
                }
                key.startsWith(ALIAS_PREFIX) && key !in snapshot.aliases -> {
                    val overlayKey = value.get("overlayKey")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    snapshot.aliases[key] = overlayKey
                    changed = true
                }
            }
        }
        if (!changed) true else writeAndSwapLocked(snapshot)
    }

    fun migrateLegacyEntries(entries: Map<String, String>): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        if (entries.isEmpty()) return true
        val snapshot = snapshotLocked()
        var changed = false
        for ((key, raw) in entries) {
            when {
                key.startsWith(OVERLAY_PREFIX) && key.removePrefix(OVERLAY_PREFIX) !in snapshot.overlays -> {
                    val root = runCatching { gson.fromJson(raw, com.google.gson.JsonObject::class.java) }.getOrNull() ?: continue
                    val schemaVersion = root.get("schemaVersion")?.asInt ?: continue
                    if (schemaVersion != OVERLAY_VALUE_SCHEMA_VERSION) continue
                    val overlay = runCatching {
                        gson.fromJson(root.get("value"), HydratedHomeOverlay::class.java)
                    }.getOrNull()?.normalizeDefaults() ?: continue
                    snapshot.overlays[key.removePrefix(OVERLAY_PREFIX)] = overlay
                    changed = true
                }
                key.startsWith(ALIAS_PREFIX) && key !in snapshot.aliases -> {
                    val overlayKey = raw.trim().takeIf { it.isNotEmpty() } ?: continue
                    snapshot.aliases[key] = overlayKey
                    changed = true
                }
            }
        }
        if (!changed) true else writeAndSwapLocked(snapshot)
    }

    private fun ensureLoadedLocked() {
        if (state.loaded) return
        state.aliases.clear()
        state.overlays.clear()
        if (file.isFile) {
            runCatching { readFileLocked() }
                .onFailure {
                    state.aliases.clear()
                    state.overlays.clear()
                }
        }
        state.loaded = true
    }

    private fun readFileLocked() {
        FileInputStream(file).use { fis ->
            BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                JsonReader(br).use { reader ->
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) return
                    reader.beginObject()
                    var schemaVersion = 0
                    val aliases = linkedMapOf<String, String>()
                    val overlays = linkedMapOf<String, HydratedHomeOverlay>()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "schemaVersion" -> schemaVersion = reader.nextInt()
                            "aliases" -> readAliases(reader, aliases)
                            "overlays" -> readOverlays(reader, overlays)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if (schemaVersion != STORE_SCHEMA_VERSION) return
                    state.aliases.putAll(aliases)
                    state.overlays.putAll(overlays)
                }
            }
        }
    }

    private fun readAliases(reader: JsonReader, aliases: LinkedHashMap<String, String>) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return
        }
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (reader.peek() == JsonToken.STRING) {
                val value = reader.nextString().trim()
                if (key.isNotBlank() && value.isNotBlank()) aliases[key] = value
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun readOverlays(reader: JsonReader, overlays: LinkedHashMap<String, HydratedHomeOverlay>) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return
        }
        reader.beginObject()
        while (reader.hasNext()) {
            val overlayKey = reader.nextName()
            val overlay = readOverlayRecord(reader)
            if (overlay != null && overlayKey.isNotBlank()) overlays[overlayKey] = overlay
        }
        reader.endObject()
    }

    private fun readOverlayRecord(reader: JsonReader): HydratedHomeOverlay? {
        return runCatching {
            var schemaVersion = 0
            var overlay: HydratedHomeOverlay? = null
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                return null
            }
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "schemaVersion" -> schemaVersion = reader.nextInt()
                    "value" -> overlay = gson.fromJson(reader, HydratedHomeOverlay::class.java)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            overlay?.takeIf { schemaVersion == OVERLAY_VALUE_SCHEMA_VERSION }?.normalizeDefaults()
        }.getOrNull()
    }

    private fun writeAndSwapLocked(snapshot: Snapshot): Boolean {
        return if (writeLocked(snapshot)) {
            state.aliases.clear()
            state.aliases.putAll(snapshot.aliases)
            state.overlays.clear()
            state.overlays.putAll(snapshot.overlays)
            state.loaded = true
            true
        } else {
            false
        }
    }

    private fun writeLocked(snapshot: Snapshot): Boolean {
        var tempFile: File? = null
        return try {
            file.parentFile?.mkdirs()
            tempFile = tempFile()
            FileOutputStream(tempFile).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                    JsonWriter(bw).use { writer ->
                        writer.beginObject()
                        writer.name("schemaVersion").value(STORE_SCHEMA_VERSION)
                        writer.name("aliases")
                        writer.beginObject()
                        for ((key, value) in snapshot.aliases) writer.name(key).value(value)
                        writer.endObject()
                        writer.name("overlays")
                        writer.beginObject()
                        for ((key, overlay) in snapshot.overlays) {
                            writer.name(key)
                            writer.beginObject()
                            writer.name("schemaVersion").value(OVERLAY_VALUE_SCHEMA_VERSION)
                            writer.name("value")
                            gson.toJson(overlay, HydratedHomeOverlay::class.java, writer)
                            writer.endObject()
                        }
                        writer.endObject()
                        writer.endObject()
                    }
                }
            }
            moveReplacing(tempFile, file)
            true
        } catch (_: IOException) {
            tempFile?.delete()
            false
        } catch (_: JsonIOException) {
            tempFile?.delete()
            false
        } catch (_: SecurityException) {
            tempFile?.delete()
            false
        }
    }

    private fun snapshotLocked(): Snapshot =
        Snapshot(LinkedHashMap(state.aliases), LinkedHashMap(state.overlays))

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun tempFile(): File =
        File.createTempFile("${file.name}.", ".tmp", file.parentFile ?: File("."))

    @Suppress("SENSELESS_COMPARISON")
    private fun HydratedHomeOverlay.normalizeDefaults(): HydratedHomeOverlay {
        val needsSnapshot = stableIdsSnapshot == null
        val needsSignature = settingsSignature == null
        if (!needsSnapshot && !needsSignature) return this
        return copy(
            stableIdsSnapshot = if (needsSnapshot) ProviderIds() else stableIdsSnapshot,
            settingsSignature = if (needsSignature) "" else settingsSignature
        )
    }

    companion object {
        private const val STORE_SCHEMA_VERSION = 2
        private const val OVERLAY_VALUE_SCHEMA_VERSION = 1
        private const val OVERLAY_PREFIX = "overlay::"
        private const val ALIAS_PREFIX = "alias::"
        private val states = ConcurrentHashMap<String, SharedState>()

        private fun stateFor(file: File): SharedState =
            states.getOrPut(stateKey(file)) { SharedState() }

        private fun stateKey(file: File): String = try {
            file.canonicalPath
        } catch (_: IOException) {
            file.absolutePath
        }

        internal fun resetSharedStateForTest(file: File) {
            states.remove(stateKey(file))
        }
    }
}
```

- [ ] **Step 2: Run the typed overlay store tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.HydratedHomeOverlayTypedStoreTest
```

Expected: tests pass or fail only for direct API mismatches in the new typed store. Fix mismatches by changing the typed store, not the test intent.

- [ ] **Step 3: Commit the implementation**

Use explicit paths:

```bash
git add app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayTypedStore.kt app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayTypedStoreTest.kt
git commit -m "feat: add typed hydrated overlay store"
```

---

### Task 3: Wire HydratedHomeOverlayStore to the Typed Store

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStorePersistenceTest.kt`

- [ ] **Step 1: Update tests to expect v2 overlay storage**

In `HydratedHomeOverlayStorePersistenceTest`, replace helper paths that point at `hydrated-home-overlay-v1/entries.json` with v2 equivalents:

```kotlin
private fun entriesFile(filesDir: File): File =
    File(filesDir, "hydrated-home-overlay-v2/entries.json")
```

Update raw disk assertions from the v1 object-entry shape to the v2 shape:

```kotlin
val disk = diskEntries(filesDir)
val aliases = disk.getAsJsonObject("aliases")
val overlays = disk.getAsJsonObject("overlays")
val overlayEntry = overlays.getAsJsonObject(value.overlayKey)

assertEquals(value.overlayKey, aliases.get("alias::en::policy:1::movie:imdb:tt0137523").asString)
assertEquals(1, overlayEntry.get("schemaVersion").asInt)
assertEquals("Fight Club", overlayEntry.getAsJsonObject("value").getAsJsonObject("fields").get("title").asString)
```

Keep existing behavior assertions unchanged: `readForItemKeys`, `readByCanonicalIdentity`, `markStaleIfWeakerIds`, `markStaleAll`, `removeAliases`, and `clearAll` should still return the same results.

- [ ] **Step 2: Run the overlay persistence tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.HydratedHomeOverlayStorePersistenceTest
```

Expected: failures show production still writes v1 `FileBackedJsonObjectStore` data.

- [ ] **Step 3: Replace the store field in HydratedHomeOverlayStore**

In `HydratedHomeOverlayStore.kt`, replace the `entryStore` lazy property:

```kotlin
private val entryStore by lazy {
    HydratedHomeOverlayTypedStore(
        file = File(context.filesDir, "hydrated-home-overlay-v2/entries.json"),
        gson = gson
    ).also { store ->
        migrateV1FileIfNeeded(store)
        migrateLegacyPrefsIfNeeded(store)
    }
}
```

Add a v1 file helper:

```kotlin
private fun v1EntriesFile(): File =
    File(context.filesDir, "hydrated-home-overlay-v1/entries.json")
```

Add v1 migration:

```kotlin
private fun migrateV1FileIfNeeded(store: HydratedHomeOverlayTypedStore) {
    val v1File = v1EntriesFile()
    if (!v1File.isFile) return
    if (!store.migrateFromV1File(v1File)) return
    v1File.delete()
}
```

Replace `migrateLegacyPrefsIfNeeded(store: FileBackedJsonObjectStore)` with:

```kotlin
private fun migrateLegacyPrefsIfNeeded(store: HydratedHomeOverlayTypedStore) {
    val legacy = prefs()
    val values = legacy.all
    if (values.isEmpty()) return

    val entries = linkedMapOf<String, String>()
    val legacyKeysToClear = linkedSetOf<String>()
    for ((key, value) in values) {
        val raw = value as? String ?: continue
        when {
            key.startsWith(OVERLAY_PREFIX) -> {
                entries[key] = raw
                legacyKeysToClear += key
            }
            key.startsWith(ALIAS_PREFIX) -> {
                entries[key] = raw
                legacyKeysToClear += key
            }
        }
    }
    if (legacyKeysToClear.isEmpty()) return
    if (!store.migrateLegacyEntries(entries)) return

    val editor = legacy.edit()
    for (key in legacyKeysToClear) editor.remove(key)
    editor.commit()
}
```

- [ ] **Step 4: Replace direct `FileBackedJsonObjectStore` calls**

Change `upsert` to call:

```kotlin
val aliasKeys = normalizedAliases.map { itemKey ->
    aliasPrefsKey(
        itemKey = itemKey,
        languageTag = overlay.languageTag,
        policyVersion = overlay.policyVersion
    )
}.toSet()
val stored = withContext(Dispatchers.IO) {
    entryStore.upsert(overlay, aliasKeys)
}
```

Change `removeAliases` to call `entryStore.removeAliases(aliasKeys)`.

Change `clearAll` to call `entryStore.clearAll()`.

Change `markStaleAll` to call:

```kotlin
entryStore.aliasKeys()
    .asSequence()
    .mapNotNull { extractItemKeyFromAliasPrefsKey(it) }
    .toSet()
```

Change `readOverlayByKey` to call:

```kotlin
val overlay = entryStore.overlay(overlayKey) ?: return null
```

Delete the local `schemaVersion` and `gson.fromJson(root.get("value"), HydratedHomeOverlay::class.java)` block from `readOverlayByKey`, because the typed store returns `HydratedHomeOverlay`.

Change `readAliasOverlayKey(aliasKey)` to:

```kotlin
private fun readAliasOverlayKey(aliasKey: String): String? =
    entryStore.aliasOverlayKey(aliasKey)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
```

Change `readOverlayForItemKey` to scan `entryStore.aliasKeys()`.

- [ ] **Step 5: Run overlay tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.nexio.tv.data.local.HydratedHomeOverlayTypedStoreTest \
  --tests com.nexio.tv.data.local.HydratedHomeOverlayStorePersistenceTest \
  --tests com.nexio.tv.data.local.HydratedHomeOverlayStoreTest \
  --tests com.nexio.tv.data.local.HydratedHomeOverlayStoreInvalidationTest
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit overlay wiring**

Use explicit paths:

```bash
git add app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayTypedStore.kt app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStorePersistenceTest.kt app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayTypedStoreTest.kt
git commit -m "feat: use typed hydrated overlay cache"
```

---

### Task 4: Add Failing MediaClip Typed Store Tests

**Files:**
- Create: `app/src/test/java/com/nexio/tv/core/media/MediaClipTypedStoreTest.kt`
- Read-only reference: `app/src/main/java/com/nexio/tv/core/media/MediaClipStore.kt`

- [ ] **Step 1: Write the failing test file**

Create `MediaClipTypedStoreTest.kt`:

```kotlin
package com.nexio.tv.core.media

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.data.local.FileBackedJsonObjectStore
import java.io.File
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

        assertEquals("abc123", reloaded.records().single().externalVideoId)
        val raw = file.readText()
        assertTrue(raw.contains("\"schemaVersion\":2"))
        assertTrue(raw.contains("\"records\""))
        assertFalse(raw.contains("\"media-clip:one\":{\"members\""))
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

        assertEquals(listOf("valid"), store.records().map { it.externalVideoId })
        assertNull(store.record("media-clip:bad"))
    }

    @Test
    fun `v1 migration preserves valid records`() {
        val dir = Files.createTempDirectory("media-clip-typed-v1").toFile()
        val v1File = File(dir, "media-clip-store-v1/entries.json")
        val v2File = File(dir, "media-clip-store-v2/entries.json")
        FileBackedJsonObjectStore(v1File).putAll(
            mapOf(
                "media-clip:legacy" to gson.toJsonTree(record(key = "media-clip:legacy", externalVideoId = "legacy")).asJsonObject,
                "media-clip:bad" to JsonObject().apply { addProperty("key", "media-clip:bad") }
            )
        )
        FileBackedJsonObjectStore.resetSharedStateForTest(v1File)

        val store = MediaClipTypedStore(v2File, gson)
        assertTrue(store.migrateFromV1File(v1File))

        assertEquals("legacy", store.record("media-clip:legacy")?.externalVideoId)
        assertNull(store.record("media-clip:bad"))
        assertTrue(v2File.exists())
    }

    @Test
    fun `v1 migration does not overwrite existing v2 record`() {
        val dir = Files.createTempDirectory("media-clip-typed-no-overwrite").toFile()
        val v1File = File(dir, "media-clip-store-v1/entries.json")
        val v2File = File(dir, "media-clip-store-v2/entries.json")
        val store = MediaClipTypedStore(v2File, gson)
        assertTrue(store.putAll(listOf(record(key = "media-clip:same", externalVideoId = "file"))))
        FileBackedJsonObjectStore(v1File).put(
            "media-clip:same",
            gson.toJsonTree(record(key = "media-clip:same", externalVideoId = "legacy")).asJsonObject
        )
        FileBackedJsonObjectStore.resetSharedStateForTest(v1File)

        assertTrue(store.migrateFromV1File(v1File))

        assertEquals("file", store.record("media-clip:same")?.externalVideoId)
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
```

- [ ] **Step 2: Run the new test and verify it fails**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.core.media.MediaClipTypedStoreTest -x :app:generateRuntimeEventAuditSample -x :app:generateIntegrationRuntimeAudit
```

Expected: compilation fails with `Unresolved reference: MediaClipTypedStore` and `Unresolved reference: StoredMediaClipRecord` if the record is still private.

- [ ] **Step 3: Commit the failing tests**

Use explicit paths:

```bash
git add app/src/test/java/com/nexio/tv/core/media/MediaClipTypedStoreTest.kt
git commit -m "test: cover typed media clip store"
```

---

### Task 5: Implement MediaClipTypedStore

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/media/MediaClipTypedStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/media/MediaClipStore.kt`
- Test: `app/src/test/java/com/nexio/tv/core/media/MediaClipTypedStoreTest.kt`

- [ ] **Step 1: Move StoredMediaClipRecord to package visibility**

In `MediaClipStore.kt`, move `StoredMediaClipRecord` out of the `MediaClipStore` class and place it after `StoredMediaClip`. Use this exact declaration:

```kotlin
internal data class StoredMediaClipRecord(
    val key: String,
    val clipId: String,
    val contentId: String,
    val itemType: String?,
    val tmdbId: String?,
    val tvdbId: String?,
    val imdbId: String?,
    val kitsuId: String?,
    val provider: String,
    val source: String,
    val scopeKind: String,
    val season: Int?,
    val episode: Int?,
    val clipType: String,
    val title: String?,
    val language: String?,
    val site: String,
    val externalVideoId: String?,
    val playbackKind: String?,
    val youtubeId: String?,
    val providerUrlHash: String?,
    val redactedUrl: String?,
    val confidence: String,
    val fetchedAtMs: Long,
    val expiresAtMs: Long,
    val staleUntilMs: Long,
    val sourceTrace: List<String>
)
```

Remove the old private nested `StoredMediaClipRecord` declaration from the bottom of `MediaClipStore`.

- [ ] **Step 2: Create the typed media clip store**

Create `MediaClipTypedStore.kt`:

```kotlin
package com.nexio.tv.core.media

import com.google.gson.Gson
import com.google.gson.JsonIOException
import com.google.gson.JsonParseException
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.nexio.tv.data.local.FileBackedJsonObjectStore
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

internal class MediaClipTypedStore(
    private val file: File,
    private val gson: Gson
) {
    private data class SharedState(
        val lock: Any = Any(),
        var loaded: Boolean = false,
        val records: LinkedHashMap<String, StoredMediaClipRecord> = linkedMapOf()
    )

    private val state = stateFor(file)

    fun record(key: String): StoredMediaClipRecord? = synchronized(state.lock) {
        ensureLoadedLocked()
        state.records[key.trim()]
    }

    fun records(): List<StoredMediaClipRecord> = synchronized(state.lock) {
        ensureLoadedLocked()
        state.records.values.toList()
    }

    fun putAll(records: Collection<StoredMediaClipRecord>): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        val candidate = LinkedHashMap(state.records)
        var changed = false
        for (record in records) {
            val cleanKey = record.key.trim().takeIf { it.startsWith(KEY_PREFIX) } ?: continue
            candidate[cleanKey] = record.copy(key = cleanKey)
            changed = true
        }
        if (!changed) true else writeAndSwapLocked(candidate)
    }

    fun migrateFromV1File(v1File: File): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        if (!v1File.isFile) return true
        val candidate = LinkedHashMap(state.records)
        var changed = false
        val v1Store = FileBackedJsonObjectStore(v1File)
        for ((key, raw) in v1Store.entries()) {
            if (!key.startsWith(KEY_PREFIX) || key in candidate) continue
            val record = runCatching { gson.fromJson(raw, StoredMediaClipRecord::class.java) }.getOrNull()
                ?.takeIf { it.isValidForKey(key) }
                ?: continue
            candidate[key] = record.copy(key = key)
            changed = true
        }
        if (!changed) true else writeAndSwapLocked(candidate)
    }

    fun migrateLegacyEntries(entries: Map<String, String>): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        if (entries.isEmpty()) return true
        val candidate = LinkedHashMap(state.records)
        var changed = false
        for ((key, raw) in entries) {
            if (!key.startsWith(KEY_PREFIX) || key in candidate) continue
            val record = runCatching { gson.fromJson(raw, StoredMediaClipRecord::class.java) }.getOrNull()
                ?.takeIf { it.isValidForKey(key) }
                ?: continue
            candidate[key] = record.copy(key = key)
            changed = true
        }
        if (!changed) true else writeAndSwapLocked(candidate)
    }

    private fun ensureLoadedLocked() {
        if (state.loaded) return
        state.records.clear()
        if (file.isFile) {
            runCatching { readFileLocked() }
                .onFailure { state.records.clear() }
        }
        state.loaded = true
    }

    private fun readFileLocked() {
        FileInputStream(file).use { fis ->
            BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                JsonReader(br).use { reader ->
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) return
                    reader.beginObject()
                    var schemaVersion = 0
                    val loaded = linkedMapOf<String, StoredMediaClipRecord>()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "schemaVersion" -> schemaVersion = reader.nextInt()
                            "records" -> readRecords(reader, loaded)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if (schemaVersion != STORE_SCHEMA_VERSION) return
                    state.records.putAll(loaded)
                }
            }
        }
    }

    private fun readRecords(reader: JsonReader, out: LinkedHashMap<String, StoredMediaClipRecord>) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return
        }
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            val record = runCatching {
                gson.fromJson<StoredMediaClipRecord>(reader, StoredMediaClipRecord::class.java)
            }.getOrNull()
            if (record != null && record.isValidForKey(key)) {
                out[key] = record.copy(key = key)
            }
        }
        reader.endObject()
    }

    private fun writeAndSwapLocked(candidate: LinkedHashMap<String, StoredMediaClipRecord>): Boolean {
        return if (writeLocked(candidate)) {
            state.records.clear()
            state.records.putAll(candidate)
            state.loaded = true
            true
        } else {
            false
        }
    }

    private fun writeLocked(candidate: Map<String, StoredMediaClipRecord>): Boolean {
        var tempFile: File? = null
        return try {
            file.parentFile?.mkdirs()
            tempFile = tempFile()
            FileOutputStream(tempFile).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                    JsonWriter(bw).use { writer ->
                        writer.beginObject()
                        writer.name("schemaVersion").value(STORE_SCHEMA_VERSION)
                        writer.name("records")
                        writer.beginObject()
                        for ((key, record) in candidate) {
                            writer.name(key)
                            gson.toJson(record, StoredMediaClipRecord::class.java, writer)
                        }
                        writer.endObject()
                        writer.endObject()
                    }
                }
            }
            moveReplacing(tempFile, file)
            true
        } catch (_: IOException) {
            tempFile?.delete()
            false
        } catch (_: JsonIOException) {
            tempFile?.delete()
            false
        } catch (_: SecurityException) {
            tempFile?.delete()
            false
        }
    }

    private fun StoredMediaClipRecord.isValidForKey(expectedKey: String): Boolean =
        key.trim() == expectedKey &&
            key.startsWith(KEY_PREFIX) &&
            clipId.isNotBlank() &&
            contentId.isNotBlank() &&
            provider.isNotBlank() &&
            source.isNotBlank() &&
            scopeKind.isNotBlank() &&
            clipType.isNotBlank() &&
            site.isNotBlank() &&
            confidence.isNotBlank() &&
            expiresAtMs > 0L &&
            staleUntilMs >= expiresAtMs

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun tempFile(): File =
        File.createTempFile("${file.name}.", ".tmp", file.parentFile ?: File("."))

    companion object {
        private const val STORE_SCHEMA_VERSION = 2
        private const val KEY_PREFIX = "media-clip:"
        private val states = ConcurrentHashMap<String, SharedState>()

        private fun stateFor(file: File): SharedState =
            states.getOrPut(stateKey(file)) { SharedState() }

        private fun stateKey(file: File): String = try {
            file.canonicalPath
        } catch (_: IOException) {
            file.absolutePath
        }

        internal fun resetSharedStateForTest(file: File) {
            states.remove(stateKey(file))
        }
    }
}
```

- [ ] **Step 3: Run the typed media clip store tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.core.media.MediaClipTypedStoreTest -x :app:generateRuntimeEventAuditSample -x :app:generateIntegrationRuntimeAudit
```

Expected: tests pass after resolving imports and package visibility.

- [ ] **Step 4: Commit the typed media clip store**

Use explicit paths:

```bash
git add app/src/main/java/com/nexio/tv/core/media/MediaClipStore.kt app/src/main/java/com/nexio/tv/core/media/MediaClipTypedStore.kt app/src/test/java/com/nexio/tv/core/media/MediaClipTypedStoreTest.kt
git commit -m "feat: add typed media clip store"
```

---

### Task 6: Wire MediaClipStore to the Typed Store

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/media/MediaClipStore.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/media/MediaClipStoreTest.kt`

- [ ] **Step 1: Update MediaClipStoreTest v2 path helpers**

In `MediaClipStoreTest`, add:

```kotlin
private fun v2EntriesFile(): File =
    File(context.filesDir, "${fileNamespaceName()}/entries.json")

private fun fileNamespaceName(): String =
    prefsName.takeUnless { it == "media_clip_store_v1" } ?: "media-clip-store-v2"
```

For tests using the production namespace, assert:

```kotlin
val defaultFile = File(context.filesDir, "media-clip-store-v2/entries.json")
assertTrue(defaultFile.exists())
```

Update direct persisted-record assertions to read the v2 JSON:

```kotlin
val root = Gson().fromJson(v2EntriesFile().readText(), JsonObject::class.java)
val records = root.getAsJsonObject("records")
assertEquals("legacy", records.getAsJsonObject("media-clip:legacy").get("externalVideoId").asString)
```

- [ ] **Step 2: Run MediaClipStore tests and verify failure**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.media.MediaClipStoreTest.*' -x :app:generateRuntimeEventAuditSample -x :app:generateIntegrationRuntimeAudit
```

Expected: failures show production still writes v1 paths.

- [ ] **Step 3: Replace entryStore in MediaClipStore**

In `MediaClipStore.kt`, replace:

```kotlin
private val entryStore by lazy {
    FileBackedJsonObjectStore(
        file = File(context.filesDir, "${fileNamespace()}/entries.json")
    ).also(::migrateLegacyPrefsIfNeeded)
}
```

with:

```kotlin
private val entryStore by lazy {
    MediaClipTypedStore(
        file = File(context.filesDir, "${fileNamespace()}/entries.json"),
        gson = gson
    ).also { store ->
        migrateV1FileIfNeeded(store)
        migrateLegacyPrefsIfNeeded(store)
    }
}
```

Change `fileNamespace()` to return the v2 namespace:

```kotlin
private fun fileNamespace(): String =
    mutablePrefsName
        ?.takeUnless { it == DEFAULT_PREFS_NAME }
        ?: DEFAULT_FILE_NAMESPACE
```

Change `DEFAULT_FILE_NAMESPACE` to:

```kotlin
const val DEFAULT_FILE_NAMESPACE = "media-clip-store-v2"
```

Add:

```kotlin
private fun v1EntriesFile(): File =
    File(
        context.filesDir,
        "${mutablePrefsName?.takeUnless { it == DEFAULT_PREFS_NAME } ?: "media-clip-store-v1"}/entries.json"
    )
```

- [ ] **Step 4: Replace writes and reads**

In `storeCandidates`, remove the `JsonObject` write map and call:

```kotlin
if (!entryStore.putAll(records)) return 0
```

In `getCandidates`, replace the `entryStore.entries()` sequence with:

```kotlin
return entryStore.records()
    .asSequence()
    .mapNotNull { record ->
        record.toStoredMediaClipIfMatching(
            identity = identity,
            scope = scope,
            clipTypes = clipTypes,
            normalizedLanguage = normalizedLanguage,
            nowMs = now,
            includeStale = includeStale
        )
    }
    .sortedWith(
        compareBy<StoredMediaClip> { if (it.cacheDecision == CacheDecision.HIT) 0 else 1 }
            .thenBy { it.clipType.ordinal }
            .thenBy { it.confidence.ordinal }
    )
    .toList()
```

Delete `decodeRecord(raw: JsonObject)` if it is no longer used.

- [ ] **Step 5: Replace migration methods**

Add:

```kotlin
private fun migrateV1FileIfNeeded(store: MediaClipTypedStore) {
    val v1File = v1EntriesFile()
    if (!v1File.isFile) return
    if (!store.migrateFromV1File(v1File)) return
    v1File.delete()
}
```

Replace `migrateLegacyPrefsIfNeeded(store: FileBackedJsonObjectStore)` with:

```kotlin
private fun migrateLegacyPrefsIfNeeded(store: MediaClipTypedStore) {
    val legacy = prefs()
    val legacyKeys = legacy.all.keys.filter { key -> key.startsWith(KEY_PREFIX) }
    if (legacyKeys.isEmpty()) return

    val entries = linkedMapOf<String, String>()
    for (key in legacyKeys) {
        val raw = legacy.getString(key, null)?.takeIf { it.isNotBlank() } ?: continue
        entries[key] = raw
    }
    if (!store.migrateLegacyEntries(entries)) return

    val editor = legacy.edit()
    for (key in legacyKeys) editor.remove(key)
    editor.commit()
}
```

- [ ] **Step 6: Run media clip tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests com.nexio.tv.core.media.MediaClipTypedStoreTest \
  --tests 'com.nexio.tv.core.media.MediaClipStoreTest.*' \
  -x :app:generateRuntimeEventAuditSample \
  -x :app:generateIntegrationRuntimeAudit
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit media clip wiring**

Use explicit paths:

```bash
git add app/src/main/java/com/nexio/tv/core/media/MediaClipStore.kt app/src/main/java/com/nexio/tv/core/media/MediaClipTypedStore.kt app/src/test/java/com/nexio/tv/core/media/MediaClipStoreTest.kt app/src/test/java/com/nexio/tv/core/media/MediaClipTypedStoreTest.kt
git commit -m "feat: use typed media clip cache"
```

---

### Task 7: Full Targeted Regression Suite

**Files:**
- Read: all files changed in Tasks 1-6.

- [ ] **Step 1: Run all typed cache tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.nexio.tv.data.local.HydratedHomeOverlayTypedStoreTest \
  --tests com.nexio.tv.data.local.HydratedHomeOverlayStorePersistenceTest \
  --tests com.nexio.tv.data.local.HydratedHomeOverlayStoreTest \
  --tests com.nexio.tv.data.local.HydratedHomeOverlayStoreInvalidationTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run all media clip tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests com.nexio.tv.core.media.MediaClipTypedStoreTest \
  --tests 'com.nexio.tv.core.media.MediaClipStoreTest.*' \
  -x :app:generateRuntimeEventAuditSample \
  -x :app:generateIntegrationRuntimeAudit
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run OpenSpec validation**

Run:

```bash
openspec validate typed-cache-stores --strict
```

Expected:

```text
Change 'typed-cache-stores' is valid
```

- [ ] **Step 4: Commit any test-only fixes**

If tests required fixes, stage only touched typed-cache files:

```bash
git add app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayTypedStore.kt app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStorePersistenceTest.kt app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayTypedStoreTest.kt app/src/main/java/com/nexio/tv/core/media/MediaClipStore.kt app/src/main/java/com/nexio/tv/core/media/MediaClipTypedStore.kt app/src/test/java/com/nexio/tv/core/media/MediaClipStoreTest.kt app/src/test/java/com/nexio/tv/core/media/MediaClipTypedStoreTest.kt
git commit -m "test: stabilize typed cache store migration"
```

If no files changed after the test run, do not commit.

---

### Task 8: Device Storage and Heap Verification

**Files:**
- No code files.

- [ ] **Step 1: Build debug APK**

Run:

```bash
./gradlew :app:assembleUniversalDebug
```

Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/universal/debug/app-universal-debug.apk` exists.

- [ ] **Step 2: Install on rooted device**

Run:

```bash
adb -s 192.168.50.98:5555 install -r app/build/outputs/apk/universal/debug/app-universal-debug.apk
```

Expected:

```text
Success
```

- [ ] **Step 3: Launch app and select profile**

Run the smoke sequence required by `AGENTS.md`:

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 5
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 45
adb -s 192.168.50.98:5555 logcat -d -t 800 | grep -E "FATAL|AndroidRuntime|ANR|ClassCast|NoSuchMethod|OutOfMemory" | tail -20
```

Expected: no crash lines from the final grep command.

- [ ] **Step 4: Verify v2 files and old v1 files**

Run:

```bash
adb -s 192.168.50.98:5555 shell run-as com.nexiodebug.tv ls -lh files/hydrated-home-overlay-v2 files/media-clip-store-v2
adb -s 192.168.50.98:5555 shell run-as com.nexiodebug.tv wc -c files/hydrated-home-overlay-v2/entries.json files/media-clip-store-v2/entries.json
adb -s 192.168.50.98:5555 shell run-as com.nexiodebug.tv ls -lh files/hydrated-home-overlay-v1 files/media-clip-store-v1
```

Expected:

- v2 `entries.json` files exist.
- v1 directories are absent, empty, or no longer contain growing active cache files.
- The app-private SharedPreferences XMLs for these stores remain small.

- [ ] **Step 5: Capture heap after home load**

Run:

```bash
adb -s 192.168.50.98:5555 shell rm -f /data/local/tmp/nexio-typed-cache-after.hprof
adb -s 192.168.50.98:5555 shell am dumpheap com.nexiodebug.tv /data/local/tmp/nexio-typed-cache-after.hprof
adb -s 192.168.50.98:5555 pull /data/local/tmp/nexio-typed-cache-after.hprof /tmp/nexio-typed-cache-after.hprof
ls -lh /tmp/nexio-typed-cache-after.hprof
```

Expected: pulled heap file is non-empty.

- [ ] **Step 6: Analyze heap for the targeted retention**

Run:

```bash
heaptrail -i /tmp/nexio-typed-cache-after.hprof --leak-suspects --exclude-soft-weak --preview-bytes 200 --top 15
heaptrail -i /tmp/nexio-typed-cache-after.hprof --find-referrers com.nexio.tv.data.local.FileBackedJsonObjectStore\$SharedState --hops 3 --top 40 --retained-size --exclude-soft-weak
heaptrail -i /tmp/nexio-typed-cache-after.hprof --find-referrers com.google.gson.JsonObject --hops 3 --top 60 --retained-size --exclude-soft-weak
```

Expected:

- `FileBackedJsonObjectStore$SharedState` no longer retains hydrated overlay or media clip data.
- Remaining `JsonObject` retention, if present, belongs to other caches outside this plan.

- [ ] **Step 7: Record verification result in final handoff**

Summarize:

- Test commands and pass/fail status.
- Device v2 file sizes.
- Whether v1 files were removed/empty.
- Heaptrail result for `FileBackedJsonObjectStore$SharedState`.

Commit nothing for device verification unless a code or test fix was required.

---

## Self-Review Checklist

- Spec coverage:
  - Typed resident overlay aliases and overlays: Tasks 1-3.
  - Typed resident media clip records: Tasks 4-6.
  - V1 file migration: Tasks 1, 2, 4, 5.
  - Legacy SharedPreferences migration: Tasks 3 and 6.
  - Existing v2 entries win over older sources: Tasks 1, 2, 4, 5.
  - Device and heap verification: Task 8.

- Scope check:
  - No thumbnail or `DurableArtworkDecisionCache` change is included.
  - No catalog/addon/metadata cache rewrite is included.

- Worktree safety:
  - All commit commands use explicit paths.
  - No step uses `git add -A`, `git add .`, `git commit -a`, `git stash`, or destructive checkout/reset commands.
