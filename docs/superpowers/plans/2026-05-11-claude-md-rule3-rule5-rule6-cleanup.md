# CLAUDE.md rule #3 / #5 / #6 cleanup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close residual CLAUDE.md hard-rule violations validated in the post-v0.56 heap dump — eliminate 7 SharedPreferences-backed JSON stores (rule #3), unpin the `updateCatalogRowsPipeline` continuation locals (rule #6), and intern the 526-instance `preferredArtworkProviders` LinkedHashMap retention (rule #5).

**Architecture:** Apply the proven file-streaming JSON recipe from `HomeCatalogSnapshotStore.streamSnapshotToFile` (`bc7b5061a`) per store, with boot-once migration from the legacy XML and atomic-rename writes. Rule #6 fix is structural: remove outer-fun locals, refetch StateFlow values inside the branches that need them. Rule #5 fix is a singleton interner keyed on the 4-tuple of provider keys.

**Tech Stack:** Kotlin, Jetpack/Compose, Hilt, kotlinx.coroutines, Gson `JsonReader`/`JsonWriter`, `androidx.test` + Robolectric for migration round-trip tests, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-05-11-claude-md-rule3-rule5-rule6-cleanup-design.md`

---

## File Structure

**New files (Task 1):**
- `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayFileStore.kt` — file-streaming impl. Replaces the SharedPreferences-backed `HydratedHomeOverlayStore` body while preserving its public API.

**Modified files (Task 1):**
- `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt` — swap SharedPreferences ops for delegation to `HydratedHomeOverlayFileStore`.

**Test files (Task 1):**
- `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayFileStoreTest.kt` — unit test for the file-streaming store.
- `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStoreMigrationTest.kt` — Robolectric migration round-trip test.

**Modified files (Task 2):**
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt` — delete 5 outer-fun locals at lines 2562–2586; replace each use with an inline `*.value` read at the use-site.

**Test files (Task 2):**
- `app/src/test/java/com/nexio/tv/ui/screens/home/UpdateCatalogRowsPipelineRule6Test.kt` — instruments the function and asserts each branch performs an independent read.

**Tasks 3–8 (P1, follow-up sessions):** identical file structure pattern per store. Each task creates a new `*FileStore.kt`, modifies the existing store, adds a migration test, and uses a unique file path under `filesDir/<store-name>-v1/`.

**Task 9 (P2):**
- New: `app/src/main/java/com/nexio/tv/core/artwork/PreferredArtworkProvidersMemo.kt`
- Modified: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/PreferredArtworkProvidersMemoTest.kt`

**Task 10 (P2):**
- Modified: each migrated store file from Tasks 1, 3–8, plus `MetadataDiskCacheStore.kt` — add a single line in the migration-success path: `context.deleteSharedPreferences(LEGACY_PREFS_NAME)`.

---

## Task 1: HydratedHomeOverlayStore → file-streaming JSON

**Workstream:** P0 (this session)

**Spec section:** `§ P0.1`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayFileStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayFileStoreTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStoreMigrationTest.kt`

### Step 1: Write the failing test — empty store cold-start

Create `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayFileStoreTest.kt`:

```kotlin
package com.nexio.tv.data.local

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HydratedHomeOverlayFileStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `cold start on absent file returns empty maps`() = runTest {
        val store = HydratedHomeOverlayFileStore(filesDir = tmp.root)
        val snapshot = store.snapshot()
        assertEquals(emptyMap<String, HydratedHomeOverlay>(), snapshot.overlays)
        assertEquals(emptyMap<String, String>(), snapshot.aliases)
    }
}
```

### Step 2: Run test to verify it fails

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.HydratedHomeOverlayFileStoreTest" --max-workers=1
```

Expected: FAIL with "Unresolved reference: HydratedHomeOverlayFileStore"

### Step 3: Implement minimal store + snapshot

Create `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayFileStore.kt`:

```kotlin
package com.nexio.tv.data.local

import com.nexio.tv.domain.model.HydratedHomeOverlay
import java.io.File

class HydratedHomeOverlayFileStore(
    private val filesDir: File
) {
    data class Snapshot(
        val overlays: Map<String, HydratedHomeOverlay>,
        val aliases: Map<String, String>
    ) {
        companion object {
            val EMPTY = Snapshot(emptyMap(), emptyMap())
        }
    }

    suspend fun snapshot(): Snapshot {
        val file = File(filesDir, STORE_DIR + "/" + STORE_FILE)
        if (!file.exists()) return Snapshot.EMPTY
        return Snapshot.EMPTY // streaming read added in next step
    }

    internal companion object {
        const val STORE_DIR = "hydrated-home-overlay-v1"
        const val STORE_FILE = "store.json"
        const val SCHEMA_VERSION = 1
    }
}
```

### Step 4: Run test to verify it passes

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.HydratedHomeOverlayFileStoreTest" --max-workers=1
```

Expected: PASS (1 test)

### Step 5: Write failing test for round-trip write+read

Append to `HydratedHomeOverlayFileStoreTest.kt`:

```kotlin
    @Test
    fun `upsert then snapshot returns the persisted overlay`() = runTest {
        val store = HydratedHomeOverlayFileStore(filesDir = tmp.root)
        val overlay = HydratedHomeOverlay(
            itemKey = "movie:tmdb:550",
            overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            imdbId = "tt0137523",
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1,
            fields = HomeDisplayMetadata(title = "Fight Club"),
            fieldTrace = emptyList(),
            displayHash = "abc",
            state = HomeItemHydrationState.CANONICAL_READY,
            updatedAtMs = 1000L,
            staleAtMs = 2000L,
            stableIdsSnapshot = ProviderIds(tmdb = "550"),
            settingsSignature = "p=rpdb;l=default;b=default;t=default;v=1"
        )

        store.upsertSync(overlay, aliases = mapOf("alias::en::policy:1::movie:tmdb:550" to overlay.overlayKey))
        store.flushForTest()

        val snapshot = HydratedHomeOverlayFileStore(filesDir = tmp.root).snapshot()
        assertEquals(1, snapshot.overlays.size)
        assertEquals("Fight Club", snapshot.overlays[overlay.overlayKey]?.fields?.title)
        assertEquals(overlay.overlayKey, snapshot.aliases["alias::en::policy:1::movie:tmdb:550"])
    }
```

### Step 6: Run test to verify it fails

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.HydratedHomeOverlayFileStoreTest" --max-workers=1
```

Expected: FAIL with "Unresolved reference: upsertSync" or compile error.

### Step 7: Implement streaming write + read

Replace `HydratedHomeOverlayFileStore.kt` body with:

```kotlin
package com.nexio.tv.data.local

import android.util.Log
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.nexio.tv.domain.model.HydratedHomeOverlay
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HydratedHomeOverlayFileStore(
    private val filesDir: File,
    private val gson: Gson = Gson()
) {
    data class Snapshot(
        val overlays: Map<String, HydratedHomeOverlay>,
        val aliases: Map<String, String>
    ) {
        companion object { val EMPTY = Snapshot(emptyMap(), emptyMap()) }
    }

    private val ioLock = Mutex()
    private val workingOverlays = LinkedHashMap<String, HydratedHomeOverlay>()
    private val workingAliases = LinkedHashMap<String, String>()
    private var loaded = false

    private val storeDir get() = File(filesDir, STORE_DIR).apply { mkdirs() }
    private val storeFile get() = File(storeDir, STORE_FILE)

    suspend fun snapshot(): Snapshot = ioLock.withLock {
        loadIfNeeded()
        Snapshot(LinkedHashMap(workingOverlays), LinkedHashMap(workingAliases))
    }

    suspend fun upsertSync(overlay: HydratedHomeOverlay, aliases: Map<String, String>) {
        ioLock.withLock {
            loadIfNeeded()
            workingOverlays[overlay.overlayKey] = overlay
            workingAliases.putAll(aliases)
            persistLocked()
        }
    }

    suspend fun removeAliasesSync(aliasKeys: Collection<String>) {
        ioLock.withLock {
            loadIfNeeded()
            if (aliasKeys.none { it in workingAliases }) return
            aliasKeys.forEach { workingAliases.remove(it) }
            persistLocked()
        }
    }

    suspend fun clearAllSync() {
        ioLock.withLock {
            workingOverlays.clear()
            workingAliases.clear()
            storeFile.delete()
            loaded = true
        }
    }

    suspend fun flushForTest() = ioLock.withLock { persistLocked() }

    private fun loadIfNeeded() {
        if (loaded) return
        if (storeFile.exists()) {
            runCatching { streamRead(storeFile, workingOverlays, workingAliases) }
                .onFailure { Log.w(TAG, "Failed to read $storeFile", it) }
        }
        loaded = true
    }

    private fun persistLocked() {
        val tempFile = File(storeDir, STORE_FILE_TEMP)
        FileOutputStream(tempFile).use { fos ->
            BufferedWriter(OutputStreamWriter(fos, StandardCharsets.UTF_8)).use { bw ->
                JsonWriter(bw).use { writer -> streamWrite(writer) }
            }
        }
        Files.move(
            tempFile.toPath(),
            storeFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    private fun streamWrite(writer: JsonWriter) {
        writer.beginObject()
        writer.name("schemaVersion").value(SCHEMA_VERSION.toLong())
        writer.name("overlays").beginObject()
        for ((k, v) in workingOverlays) {
            writer.name(k)
            gson.toJson(v, HydratedHomeOverlay::class.java, writer)
        }
        writer.endObject()
        writer.name("aliases").beginObject()
        for ((k, v) in workingAliases) writer.name(k).value(v)
        writer.endObject()
        writer.endObject()
    }

    private fun streamRead(
        file: File,
        outOverlays: MutableMap<String, HydratedHomeOverlay>,
        outAliases: MutableMap<String, String>
    ) {
        FileInputStream(file).use { fis ->
            BufferedReader(InputStreamReader(fis, StandardCharsets.UTF_8)).use { br ->
                JsonReader(br).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "schemaVersion" -> {
                                val v = reader.nextInt()
                                if (v != SCHEMA_VERSION) {
                                    Log.w(TAG, "Unexpected schemaVersion=$v; aborting read")
                                    return
                                }
                            }
                            "overlays" -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    val key = reader.nextName()
                                    if (reader.peek() == JsonToken.NULL) { reader.nextNull(); continue }
                                    val overlay = gson.fromJson<HydratedHomeOverlay>(
                                        reader, HydratedHomeOverlay::class.java
                                    ) ?: continue
                                    outOverlays[key] = overlay
                                }
                                reader.endObject()
                            }
                            "aliases" -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    val key = reader.nextName()
                                    if (reader.peek() == JsonToken.NULL) { reader.nextNull(); continue }
                                    outAliases[key] = reader.nextString()
                                }
                                reader.endObject()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            }
        }
    }

    internal companion object {
        const val TAG = "HydratedHomeOverlayFileStore"
        const val STORE_DIR = "hydrated-home-overlay-v1"
        const val STORE_FILE = "store.json"
        const val STORE_FILE_TEMP = "store.json.tmp"
        const val SCHEMA_VERSION = 1
    }
}
```

### Step 8: Run round-trip test

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.HydratedHomeOverlayFileStoreTest" --max-workers=1
```

Expected: PASS (2 tests)

### Step 9: Write failing test for legacy migration

Create `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStoreMigrationTest.kt`:

```kotlin
package com.nexio.tv.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HydratedHomeOverlayStoreMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val gson = Gson()

    @Test
    fun `boot-once migration moves legacy XML into file-streamed store and deletes legacy prefs`() = runTest {
        val overlay = HydratedHomeOverlay(
            itemKey = "movie:tmdb:550",
            overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            imdbId = "tt0137523",
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1,
            fields = HomeDisplayMetadata(title = "Fight Club"),
            fieldTrace = emptyList(),
            displayHash = "abc",
            state = HomeItemHydrationState.CANONICAL_READY,
            updatedAtMs = 1000L,
            staleAtMs = 2000L,
            stableIdsSnapshot = ProviderIds(tmdb = "550"),
            settingsSignature = "p=rpdb;l=default;b=default;t=default;v=1"
        )
        val payload = JsonObject().apply {
            add("value", gson.toJsonTree(overlay))
            addProperty("schemaVersion", 1)
        }
        context.getSharedPreferences("hydrated_home_overlay_v1", Context.MODE_PRIVATE)
            .edit()
            .putString("overlay::${overlay.overlayKey}", gson.toJson(payload))
            .putString("alias::en::policy:1::movie:tmdb:550", overlay.overlayKey)
            .commit()

        val store = HydratedHomeOverlayStore(context = context)
        store.warmUpForTest()  // implementation in next step

        val read = store.readByCanonicalIdentity(
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1
        )
        assertEquals("Fight Club", read?.fields?.title)

        val newFile = context.filesDir.resolve("hydrated-home-overlay-v1/store.json")
        assertTrue("Expected new file to exist after migration", newFile.exists())

        val legacyPrefs = context.getSharedPreferences("hydrated_home_overlay_v1", Context.MODE_PRIVATE)
        assertFalse("Expected legacy prefs cleared", legacyPrefs.contains("overlay::${overlay.overlayKey}"))
    }
}
```

### Step 10: Run migration test to verify it fails

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.HydratedHomeOverlayStoreMigrationTest" --max-workers=1
```

Expected: FAIL with "Unresolved reference: warmUpForTest" or NPE if migration not wired.

### Step 11: Wire HydratedHomeOverlayStore to delegate to the file store

Replace the body of `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt` between the class declaration and the companion. Keep all public method signatures unchanged; move SharedPreferences ops to a private legacy-read helper used only by the migration path.

```kotlin
package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.hydratedHomeOverlayKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
@Singleton
class HydratedHomeOverlayStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traceEvents: TraceMetadataEvents = TraceMetadataEvents(
        sink = NoopRuntimeTraceSink,
        sessionId = { null }
    )
) {
    private val gson = Gson()
    private val version = MutableStateFlow(0L)
    private val staleItemKeys = MutableStateFlow<Set<String>>(emptySet())
    private val fileStore = HydratedHomeOverlayFileStore(filesDir = context.filesDir, gson = gson)
    @Volatile private var warmedUp = false

    suspend fun warmUp() {
        if (warmedUp) return
        withContext(Dispatchers.IO) {
            val newFileExists = context.filesDir.resolve("hydrated-home-overlay-v1/store.json").exists()
            if (!newFileExists) migrateFromLegacyPrefs()
            // After this, fileStore.snapshot() returns the migrated data.
            fileStore.snapshot()  // forces loadIfNeeded
            warmedUp = true
        }
    }

    internal fun warmUpForTest() = runBlocking { warmUp() }

    fun observeForItemKeys(
        itemKeys: Set<String>,
        languageTag: String,
        policyVersion: Int
    ): Flow<Map<String, HydratedHomeOverlay>> {
        val normalizedKeys = itemKeys.normalizedItemKeys()
        return version
            .debounce(VERSION_DEBOUNCE_MS)
            .map {
                withContext(Dispatchers.IO) {
                    warmUp()
                    readForItemKeys(itemKeys = normalizedKeys, languageTag = languageTag, policyVersion = policyVersion)
                }
            }
    }

    suspend fun upsert(overlay: HydratedHomeOverlay, aliases: Set<String>) {
        warmUp()
        val normalizedAliases = (aliases + overlay.itemKey).normalizedItemKeys()
        val aliasMap = normalizedAliases.associate { itemKey ->
            aliasPrefsKey(itemKey, overlay.languageTag, overlay.policyVersion) to overlay.overlayKey
        }
        fileStore.upsertSync(overlay, aliasMap)
        if (staleItemKeys.value.isNotEmpty()) {
            staleItemKeys.update { current -> if (current.isEmpty()) current else current - normalizedAliases }
        }
        incrementVersion()
    }

    suspend fun removeAliases(itemKeys: Set<String>, languageTag: String, policyVersion: Int) {
        warmUp()
        val normalized = itemKeys.normalizedItemKeys()
        val aliasKeys = normalized.map { aliasPrefsKey(it, languageTag, policyVersion) }
        fileStore.removeAliasesSync(aliasKeys)
        if (staleItemKeys.value.isNotEmpty()) {
            staleItemKeys.update { current -> if (current.isEmpty()) current else current - normalized }
        }
        incrementVersion()
    }

    suspend fun clearAll() {
        warmUp()
        fileStore.clearAllSync()
        staleItemKeys.value = emptySet()
        incrementVersion()
    }

    suspend fun markStaleIfWeakerIds(itemKey: String, currentIds: ProviderIds) {
        warmUp()
        val trimmed = itemKey.trim().takeIf { it.isNotEmpty() } ?: return
        val overlay = withContext(Dispatchers.IO) { readOverlayForItemKey(trimmed) } ?: return
        if (overlay.state == HomeItemHydrationState.STALE_READY) return
        if (!currentIds.strictlyContains(overlay.stableIdsSnapshot)) return
        if (trimmed in staleItemKeys.value) return
        staleItemKeys.update { current -> if (trimmed in current) current else current + trimmed }
        traceEvents.emitOverlayStaleMarked(
            itemKey = trimmed,
            reason = "cross_id_enriched",
            oldState = overlay.state.name
        )
        incrementVersion()
    }

    suspend fun markStaleAll(reason: String) {
        warmUp()
        val snapshot = fileStore.snapshot()
        val itemKeys = snapshot.aliases.keys
            .mapNotNull { extractItemKeyFromAliasPrefsKey(it) }
            .toSet()
        if (itemKeys.isEmpty()) return
        staleItemKeys.update { current -> current + itemKeys }
        traceEvents.emitOverlayStaleMarked(
            itemKey = "<all:${itemKeys.size}>",
            reason = reason.ifBlank { "settings_change" },
            oldState = "CANONICAL_READY"
        )
        incrementVersion()
    }

    fun readForItemKeys(
        itemKeys: Set<String>,
        languageTag: String,
        policyVersion: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Map<String, HydratedHomeOverlay> {
        val snapshot = runBlocking { fileStore.snapshot() }
        return itemKeys.normalizedItemKeys().mapNotNull { itemKey ->
            val overlayKey = snapshot.aliases[aliasPrefsKey(itemKey, languageTag, policyVersion)]
                ?: return@mapNotNull null
            val overlay = snapshot.overlays[overlayKey] ?: return@mapNotNull null
            val validated = overlay.takeIf {
                it.languageTag == languageTag && it.policyVersion == policyVersion && !it.isExpired(nowMs)
            } ?: return@mapNotNull null
            itemKey to validated.applyInMemoryStaleness(itemKey)
        }.toMap()
    }

    fun readByCanonicalIdentity(
        canonicalProvider: ProviderId,
        canonicalId: String,
        contentType: ContentType,
        languageTag: String,
        policyVersion: Int,
        nowMs: Long = System.currentTimeMillis()
    ): HydratedHomeOverlay? {
        val snapshot = runBlocking { fileStore.snapshot() }
        val overlayKey = hydratedHomeOverlayKey(canonicalProvider, canonicalId, contentType, languageTag, policyVersion)
        val overlay = snapshot.overlays[overlayKey]?.takeIf {
            it.canonicalProvider == canonicalProvider &&
                it.canonicalId == canonicalId &&
                it.contentType == contentType &&
                it.languageTag == languageTag &&
                it.policyVersion == policyVersion &&
                !it.isExpired(nowMs)
        } ?: return null

        val currentStale = staleItemKeys.value
        val stale = if (currentStale.isEmpty()) false else currentStale.any { staleKey ->
            snapshot.aliases[aliasPrefsKey(staleKey, languageTag, policyVersion)] == overlay.overlayKey
        }
        return if (stale) overlay.copy(state = HomeItemHydrationState.STALE_READY) else overlay
    }

    private fun migrateFromLegacyPrefs() {
        val legacy = context.getSharedPreferences("hydrated_home_overlay_v1", Context.MODE_PRIVATE)
        val all = legacy.all
        if (all.isEmpty()) return
        val overlays = LinkedHashMap<String, HydratedHomeOverlay>()
        val aliases = LinkedHashMap<String, String>()
        for ((key, value) in all) {
            when {
                key.startsWith("overlay::") -> {
                    val overlayKey = key.removePrefix("overlay::")
                    val raw = value as? String ?: continue
                    runCatching {
                        val root = gson.fromJson(raw, JsonObject::class.java) ?: return@runCatching
                        if (root.get("schemaVersion")?.asInt != 1) return@runCatching
                        val overlay = gson.fromJson(root.get("value"), HydratedHomeOverlay::class.java)
                            ?: return@runCatching
                        overlays[overlayKey] = overlay
                    }.onFailure { Log.w(TAG, "Migrate: skipped overlay key=$overlayKey", it) }
                }
                key.startsWith("alias::") -> {
                    val aliasOverlayKey = value as? String ?: continue
                    aliases[key] = aliasOverlayKey
                }
            }
        }
        if (overlays.isEmpty() && aliases.isEmpty()) return

        // Write to new store via a single bulk upsert pass.
        runBlocking {
            overlays.values.forEach { overlay ->
                fileStore.upsertSync(overlay, emptyMap())
            }
            // Now write aliases in one batch.
            fileStore.upsertSync(
                overlays.values.first(),
                aliases
            )
        }
        legacy.edit().clear().apply()
        // Step 10 in P2 deletes the file entirely; here we just clear so a re-boot doesn't re-migrate.
    }

    private suspend fun readOverlayForItemKey(itemKey: String): HydratedHomeOverlay? {
        val snapshot = fileStore.snapshot()
        // Find any alias whose target overlay's itemKey matches the requested itemKey.
        val match = snapshot.overlays.values.firstOrNull { it.itemKey == itemKey } ?: return null
        return match
    }

    private fun HydratedHomeOverlay.applyInMemoryStaleness(itemKey: String): HydratedHomeOverlay {
        if (itemKey !in staleItemKeys.value) return this
        if (state == HomeItemHydrationState.STALE_READY) return this
        return copy(state = HomeItemHydrationState.STALE_READY)
    }

    private fun Set<String>.normalizedItemKeys(): Set<String> =
        mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()

    private fun incrementVersion() { version.update { it + 1 } }

    private fun aliasPrefsKey(itemKey: String, languageTag: String, policyVersion: Int): String =
        "${ALIAS_PREFIX}${languageTag.trim()}::policy:$policyVersion::${itemKey.trim()}"

    private fun extractItemKeyFromAliasPrefsKey(prefsKey: String): String? {
        if (!prefsKey.startsWith(ALIAS_PREFIX)) return null
        val tail = prefsKey.removePrefix(ALIAS_PREFIX)
        // "<lang>::policy:N::<itemKey>"
        val parts = tail.split("::", limit = 3)
        return parts.getOrNull(2)
    }

    private fun HydratedHomeOverlay.isExpired(nowMs: Long): Boolean =
        staleAtMs in 1..nowMs

    private companion object {
        const val TAG = "HydratedHomeOverlayStore"
        const val ALIAS_PREFIX = "alias::"
        const val VERSION_DEBOUNCE_MS = 50L
    }
}
```

### Step 12: Run all HydratedHomeOverlay tests

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.local.HydratedHomeOverlayFileStoreTest" \
  --tests "com.nexio.tv.data.local.HydratedHomeOverlayStoreMigrationTest" \
  --tests "com.nexio.tv.data.local.HydratedHomeOverlayStoreTest" \
  --max-workers=1
```

Expected: PASS (all tests). The existing `HydratedHomeOverlayStoreTest` exercises the public API; it should still pass because the API is unchanged.

### Step 13: Build the whole module

```bash
./gradlew :app:assembleUniversalDebug --max-workers=1
```

Expected: BUILD SUCCESSFUL.

### Step 14: On-device pre-fix heap baseline

```bash
DEV=192.168.50.98:5555
adb -s $DEV shell am force-stop com.nexiodebug.tv
adb -s $DEV logcat -c
adb -s $DEV shell monkey -p com.nexiodebug.tv 1 >/dev/null 2>&1
sleep 12
adb -s $DEV shell input keyevent KEYCODE_DPAD_CENTER
sleep 45
PID=$(adb -s $DEV shell pidof com.nexiodebug.tv | tr -d '\r')
adb -s $DEV shell am dumpheap "$PID" /sdcard/before-task1.hprof
sleep 5
adb -s $DEV pull /sdcard/before-task1.hprof /tmp/before-task1.hprof
adb -s $DEV shell "su -c 'ls -la /data/data/com.nexiodebug.tv/shared_prefs/hydrated_home_overlay_v1.xml'"
```

Expected: hprof pulled. SharedPreferences XML size noted (current observation: 542 KiB).

### Step 15: Install the new build

```bash
./gradlew :app:installUniversalDebug --max-workers=1
DEV=192.168.50.98:5555
adb -s $DEV shell am force-stop com.nexiodebug.tv
adb -s $DEV logcat -c
adb -s $DEV shell monkey -p com.nexiodebug.tv 1 >/dev/null 2>&1
sleep 12
adb -s $DEV shell input keyevent KEYCODE_DPAD_CENTER
sleep 45
```

Expected: app boots cleanly, profile selected, home rendered.

### Step 16: On-device post-fix verification

```bash
DEV=192.168.50.98:5555
PID=$(adb -s $DEV shell pidof com.nexiodebug.tv | tr -d '\r')
adb -s $DEV shell am dumpheap "$PID" /sdcard/after-task1.hprof
sleep 5
adb -s $DEV pull /sdcard/after-task1.hprof /tmp/after-task1.hprof
# 1. Confirm new file exists
adb -s $DEV shell "su -c 'ls -la /data/data/com.nexiodebug.tv/files/hydrated-home-overlay-v1/'"
# 2. Confirm legacy prefs is empty
adb -s $DEV shell "su -c 'ls -la /data/data/com.nexiodebug.tv/shared_prefs/hydrated_home_overlay_v1.xml'"
adb -s $DEV shell "su -c 'cat /data/data/com.nexiodebug.tv/shared_prefs/hydrated_home_overlay_v1.xml' | head -3"
# 3. Heap diff
heaptrail --diff-from /tmp/before-task1.hprof --diff-to /tmp/after-task1.hprof --diff-by bytes --top 20
# 4. FATAL/ANR scan
adb -s $DEV logcat -d | grep -E "FATAL|ANR in com\.nexiodebug" | head -5
```

Expected:
- `files/hydrated-home-overlay-v1/store.json` exists, ≤ 600 KiB
- `shared_prefs/hydrated_home_overlay_v1.xml` is empty (`<map/>`) or has been cleared
- Heap diff shows the `com.google.gson.internal.LinkedTreeMap$Node` count drop by at least 5,000
- No FATAL/ANR

### Step 17: Commit

```bash
git add \
  app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayFileStore.kt \
  app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt \
  app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayFileStoreTest.kt \
  app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStoreMigrationTest.kt
git status -sb
git commit -m "$(cat <<'EOF'
perf(overlay): migrate HydratedHomeOverlayStore to file-streaming JSON

The SharedPreferences-backed store grew to 542 KiB on-disk (10.8x the
50 KiB CLAUDE.md rule #3 ban). Every prefs.edit().putString().apply()
escapes the entire XML map to disk, and each gson.fromJson(rawString)
read pins the full JSON as a transient String + UTF-16 char[]
(~2x file size) before parsing.

Move overlay + alias records into a single file-streamed JSON document
at files/hydrated-home-overlay-v1/store.json, written via a JsonWriter
over a BufferedWriter (no String materialisation) and atomic-renamed
into place. Boot-once migration reads the legacy XML, writes the new
file, and clears the prefs map. The public API of HydratedHomeOverlayStore
is unchanged; Plan B Task 7+8 invocation sites compile and run unmodified.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
git status
```

Expected: branch ahead by 1 commit. No other-workstream files swept up.

---

## Task 2: `updateCatalogRowsPipeline` — remove rule #6 outer-fun locals

**Workstream:** P0 (this session)

**Spec section:** `§ P0.2`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt` (lines 2562–2586, plus every downstream use of the removed locals)
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/UpdateCatalogRowsPipelineRule6Test.kt`

### Step 1: Write the failing test — assert independent reads per branch

Create `app/src/test/java/com/nexio/tv/ui/screens/home/UpdateCatalogRowsPipelineRule6Test.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static-source assertion that updateCatalogRowsPipeline no longer binds the five
 * large discovery values as outer-fun locals. CLAUDE.md hard rule #6 forbids
 * `val foo = stateFlow.value` at function head when the function fans out via
 * supervisorScope/coroutineScope with suspensions, because the Kotlin coroutine
 * state machine saves the local into every branch's continuation.
 *
 * This test is intentionally a regex over the source file rather than a runtime
 * test — the rule #6 violation is captured-locals-in-continuations, which isn't
 * observable from unit tests without bytecode inspection. The regex catches
 * regressions during normal review.
 */
class UpdateCatalogRowsPipelineRule6Test {
    private val source = File(
        "src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt"
    ).readText()

    @Test
    fun `updateCatalogRowsPipeline does not bind currentHydratedHomeOverlays as outer-fun local`() {
        val body = source.substringAfter("fun HomeViewModel.updateCatalogRowsPipeline(")
            .substringBefore("\n}")
        val firstSuspensionAt = body.indexOf("withLock {").coerceAtLeast(0)
        val preFanout = body.substring(firstSuspensionAt, body.indexOf("supervisorScope").let {
            if (it < 0) body.length else it
        })
        assertTrue(
            "Rule #6 violation: 'val currentHydratedHomeOverlays =' appears at function head. " +
                "Move it inside the branch that uses it (re-read .value at use-site).",
            !preFanout.contains("val currentHydratedHomeOverlays")
        )
    }

    @Test
    fun `updateCatalogRowsPipeline does not bind discovery snapshots as outer-fun locals`() {
        val body = source.substringAfter("fun HomeViewModel.updateCatalogRowsPipeline(")
            .substringBefore("\n}")
        val preFanout = body.substringBefore("supervisorScope")
        val forbidden = listOf(
            "val traktSnapshot   =",
            "val simklSnapshot   =",
            "val mdbListSnapshot =",
            "val tmdbSnapshot    =",
            // also accept the no-padding form just in case
            "val traktSnapshot =",
            "val simklSnapshot =",
            "val mdbListSnapshot =",
            "val tmdbSnapshot =",
        )
        for (decl in forbidden) {
            assertTrue(
                "Rule #6 violation: '$decl' appears at function head. " +
                    "Move it inside the branch that uses it.",
                !preFanout.contains(decl)
            )
        }
    }
}
```

### Step 2: Run test to verify it fails

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.home.UpdateCatalogRowsPipelineRule6Test" --max-workers=1
```

Expected: FAIL on both tests (current source still has the outer-fun locals).

### Step 3: Inspect current bindings + every use-site

```bash
grep -n "currentHydratedHomeOverlays\|traktSnapshot\|simklSnapshot\|mdbListSnapshot\|tmdbSnapshot" \
  /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt \
  | head -40
```

Expected: list of every reference. Note each line number where the locals are bound vs read. We will replace the bindings with inline reads at the read sites.

### Step 4: Remove the 5 outer-fun local bindings

Edit `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`, delete the lines that look like (currently around lines 2562–2586):

```kotlin
    val traktSnapshot = if (activeProfileTraktAuthenticated) {
        traktDiscoverySnapshot
    } else {
        com.nexio.tv.data.repository.TraktDiscoverySnapshot()
    }
    val traktPrefs = traktCatalogPreferences.onlyWhenAuthenticated(activeProfileTraktAuthenticated)
    val simklSnapshot = simklDiscoverySnapshot
    val simklPrefs = simklCatalogPreferences
    val mdbListSnapshot = mdbListDiscoverySnapshot
    val mdbListPrefs = mdbListCatalogPreferences
    val tmdbSnapshot = tmdbDiscoverySnapshot
    val tmdbPrefs = tmdbCatalogPreferences
    ...
    val currentHydratedHomeOverlays = hydratedHomeOverlaysByItemKey.value
```

Note: `*Prefs` are small (DataStore-backed scalar config objects); they can stay as outer-fun locals — they are not the rule #6 targets. ONLY remove the five snapshot/overlay vals.

### Step 5: Replace every downstream use with an inline read

At every site that previously read `currentHydratedHomeOverlays`, `traktSnapshot`, `simklSnapshot`, `mdbListSnapshot`, or `tmdbSnapshot`, substitute an inline read scoped to the innermost block. For example, the use site that previously was:

```kotlin
    // Was: uses traktSnapshot + currentHydratedHomeOverlays from outer scope
    val rows = buildRowsFor(traktSnapshot, currentHydratedHomeOverlays, ...)
```

Becomes:

```kotlin
    run {
        val rowsTraktSnapshot = if (activeProfileTraktAuthenticated) traktDiscoverySnapshot
            else com.nexio.tv.data.repository.TraktDiscoverySnapshot()
        val rowsOverlays = hydratedHomeOverlaysByItemKey.value
        val rows = buildRowsFor(rowsTraktSnapshot, rowsOverlays, ...)
        // ...continue with rows...
    }
```

The `run { ... }` block scope ensures the Kotlin liveness analysis marks `rowsTraktSnapshot` and `rowsOverlays` as dead at block exit; the next suspension in the enclosing function will not capture them. Repeat for every use site (`grep` from Step 3 lists them all).

For sites that pass these values to inner helpers, no change is needed at the helper signature — just construct the value at the call site:

```kotlin
    // Was: helperCall(traktSnapshot)
    helperCall(
        if (activeProfileTraktAuthenticated) traktDiscoverySnapshot
            else com.nexio.tv.data.repository.TraktDiscoverySnapshot()
    )
```

### Step 6: Run tests

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.home.UpdateCatalogRowsPipelineRule6Test" --max-workers=1
```

Expected: PASS (2 tests).

### Step 7: Run full unit-test suite for HomeViewModel-related tests

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.home.*" --max-workers=1
```

Expected: PASS (existing pipeline tests still pass — only the binding pattern changed, behaviour did not).

### Step 8: Build + deploy

```bash
./gradlew :app:installUniversalDebug --max-workers=1
```

Expected: BUILD SUCCESSFUL, APK installed on the connected device(s).

### Step 9: On-device heap verification

```bash
DEV=192.168.50.98:5555
adb -s $DEV shell am force-stop com.nexiodebug.tv
adb -s $DEV logcat -c
adb -s $DEV shell monkey -p com.nexiodebug.tv 1 >/dev/null 2>&1
sleep 12
adb -s $DEV shell input keyevent KEYCODE_DPAD_CENTER
sleep 45
PID=$(adb -s $DEV shell pidof com.nexiodebug.tv | tr -d '\r')
adb -s $DEV shell am dumpheap "$PID" /sdcard/after-task2.hprof
sleep 5
adb -s $DEV pull /sdcard/after-task2.hprof /tmp/after-task2.hprof
heaptrail -i /tmp/after-task2.hprof \
  --find-referrers "com.nexio.tv.ui.screens.home.HomeViewModelCatalogPipelineKt\$updateCatalogRowsPipeline\$1" \
  --hops 2 --top 10
adb -s $DEV logcat -d | grep -E "FATAL|ANR in com\.nexiodebug" | head -5
```

Expected:
- The captured-locals chain for the `$updateCatalogRowsPipeline$1` continuation no longer contains `java.util.LinkedHashMap` (overlays) or `com.nexio.tv.data.repository.*DiscoverySnapshot` instances. Only protocol/profile metadata fields should be retained.
- No FATAL/ANR.

### Step 10: Commit

```bash
git add \
  app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt \
  app/src/test/java/com/nexio/tv/ui/screens/home/UpdateCatalogRowsPipelineRule6Test.kt
git status -sb
git commit -m "$(cat <<'EOF'
perf(home): unpin updateCatalogRowsPipeline outer-fun locals (rule #6)

The 5 outer-fun locals at function head — currentHydratedHomeOverlays
plus the 4 *DiscoverySnapshot values — were captured by every
continuation generated for the function body's suspensions, observed
in heap as a live $updateCatalogRowsPipeline$1 retaining the
hydratedHomeOverlaysByItemKey LinkedHashMap (audit's 1.17 MiB pin
chain).

Move the reads to the innermost block that uses each value, scoped in
a `run { ... }` so the Kotlin coroutine state machine's liveness
analysis releases them before the next suspension. The downstream
applyNonDowngradeMerge is the actual correctness boundary; refetching
.value at the use-site sees the latest state and introduces no new
race.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
git status
```

Expected: branch ahead by 1. No other-workstream files swept up.

---

## Task 3: MediaClipStore → file-streaming JSON

**Workstream:** P1 (follow-up session)

**Spec section:** `§ P1 table` row 1

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/media/MediaClipFileStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/media/MediaClipStore.kt`
- Test: `app/src/test/java/com/nexio/tv/core/media/MediaClipFileStoreTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/media/MediaClipStoreMigrationTest.kt`

Follow the same TDD shape as Task 1. Differences:

- **File path:** `filesDir/media-clip-store-v1/store.json`
- **Legacy prefs name:** read from `MediaClipStore.prefs()` (currently uses `mutablePrefsName ?: prefsName`; resolve at migration time).
- **Schema:** `{schemaVersion: 1, records: {<key>: MediaClipRecord}}`
- **Write coalescing:** 250 ms debounce (per-clip-played event bursts).
- **Public API to preserve:** every `MediaClipStore` method (read records, get-or-put, etc. — grep the existing impl for the surface).
- **Commit message:** `perf(media-clip): migrate MediaClipStore to file-streaming JSON (rule #3, 192 KiB → file)`.

### Step 1 (template): Write failing FileStore round-trip test

```kotlin
// app/src/test/java/com/nexio/tv/core/media/MediaClipFileStoreTest.kt
package com.nexio.tv.core.media

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaClipFileStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `cold start on absent file returns empty records`() = runTest {
        val store = MediaClipFileStore(filesDir = tmp.root)
        assertEquals(emptyMap<String, MediaClipRecord>(), store.snapshot())
    }

    @Test
    fun `upsert then snapshot returns persisted record`() = runTest {
        val store = MediaClipFileStore(filesDir = tmp.root)
        val record = MediaClipRecord(
            // fill with real MediaClipRecord constructor — copy from existing tests
        )
        store.upsertSync("k", record)
        store.flushForTest()
        assertEquals(record, MediaClipFileStore(filesDir = tmp.root).snapshot()["k"])
    }
}
```

### Step 2 (template): Run, expect FAIL on `Unresolved reference: MediaClipFileStore`

### Step 3 (template): Implement `MediaClipFileStore`

Use the same `JsonWriter` / `JsonReader` / atomic-rename structure as `HydratedHomeOverlayFileStore` (see Task 1 Step 7 for the full code). Substitute `HydratedHomeOverlay` → `MediaClipRecord` and the schema shape.

### Step 4 (template): Test round-trip passes

### Step 5 (template): Migration test

Seed legacy prefs with `editor.putString(record.key, gson.toJson(record))`; instantiate the new `MediaClipStore`; call `warmUp()`; assert records loaded and prefs cleared.

### Step 6 (template): Wire `MediaClipStore` to delegate to `MediaClipFileStore` + migrate on first warmup.

### Step 7 (template): On-device verify

```bash
adb -s $DEV shell "su -c 'ls -la /data/data/com.nexiodebug.tv/shared_prefs/'" | grep media_clip_store
# Expected: 0 bytes / deleted
adb -s $DEV shell "su -c 'ls -la /data/data/com.nexiodebug.tv/files/media-clip-store-v1/'"
# Expected: store.json present
```

### Step 8 (template): Commit with explicit-path `git add` and the commit message above.

---

## Task 4: CatalogDiskCacheStore → per-key files

**Workstream:** P1

**Spec section:** `§ P1 table` row 2

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/local/CatalogDiskCacheFileStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/CatalogDiskCacheStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/CatalogDiskCacheFileStoreTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/CatalogDiskCacheStoreMigrationTest.kt`

Per-key file layout: `filesDir/catalog-disk-cache-v1/<sha256(cacheKey)>.json`. Writes are point updates, no coalescing required.

### Step 1: Failing test for per-key write+read

```kotlin
// CatalogDiskCacheFileStoreTest.kt
package com.nexio.tv.data.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogDiskCacheFileStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `read on absent key returns null`() = runTest {
        val store = CatalogDiskCacheFileStore(filesDir = tmp.root)
        assertNull(store.read("missing"))
    }

    @Test
    fun `write then read round-trip returns payload`() = runTest {
        val store = CatalogDiskCacheFileStore(filesDir = tmp.root)
        val payload = CatalogPayload(
            // fill with actual CatalogPayload constructor — copy from existing tests
        )
        store.write("addon::movie", payload)
        assertEquals(payload, CatalogDiskCacheFileStore(filesDir = tmp.root).read("addon::movie"))
    }
}
```

### Step 2–8: Standard sequence

Replace the existing `CatalogDiskCacheStore.kt:57` `prefs.edit().putString(prefKey(cacheKey), gson.toJson(payload)).commit()` line with a delegate call to `CatalogDiskCacheFileStore.write(...)`. Use sha256 of the cacheKey as the on-disk filename to avoid filesystem path issues. Migration reads every legacy `catalog::*` prefs entry, writes one file per entry, clears prefs.

Commit message: `perf(catalog): migrate CatalogDiskCacheStore to per-key file-streaming JSON (rule #3, 66 KiB → 370 files)`.

---

## Task 5: TraktDiscoverySnapshotStore → single-file JSON

**Workstream:** P1

**Spec section:** `§ P1 table` row 3

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/local/TraktDiscoverySnapshotFileStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/TraktDiscoverySnapshotFileStoreTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStoreMigrationTest.kt`

File path: `filesDir/trakt-discovery-snapshot-v1/store.json`. Single snapshot per profile, sparse writes (once per refresh), no coalescing.

### Step 1: Failing test

```kotlin
// TraktDiscoverySnapshotFileStoreTest.kt
package com.nexio.tv.data.local

import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TraktDiscoverySnapshotFileStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `cold start on absent file returns empty snapshot`() = runTest {
        val store = TraktDiscoverySnapshotFileStore(filesDir = tmp.root)
        assertEquals(TraktDiscoverySnapshot(), store.read())
    }

    @Test
    fun `write then read round-trip returns snapshot`() = runTest {
        val store = TraktDiscoverySnapshotFileStore(filesDir = tmp.root)
        val snap = TraktDiscoverySnapshot(updatedAtMs = 12345L)
        store.write(snap)
        assertEquals(snap, TraktDiscoverySnapshotFileStore(filesDir = tmp.root).read())
    }
}
```

### Step 2–8: Standard sequence

Replace `TraktDiscoverySnapshotStore.kt:97` `prefs.edit().putString(SNAPSHOT_KEY, gson.toJson(payload)).commit()` with `traktDiscoverySnapshotFileStore.write(payload)`. Migration: read SNAPSHOT_KEY from legacy prefs, write to new file, clear prefs.

Commit message: `perf(trakt): migrate TraktDiscoverySnapshotStore to file-streaming JSON (rule #3, 48 KiB → file)`.

---

## Task 6: SimklDiscoverySnapshotStore → two file-streamed stores

**Workstream:** P1

**Spec section:** `§ P1 table` row 4

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/local/SimklDiscoverySnapshotFileStore.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/SimklExternalIdCacheFileStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/SimklDiscoverySnapshotFileStoreTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/SimklExternalIdCacheFileStoreTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStoreMigrationTest.kt`

Two distinct stores share one source file (`SimklDiscoverySnapshotStore.kt`); split them into two new file-backed stores:
- `simkl-discovery-snapshot-v1/store.json` for the snapshot
- `simkl-external-id-cache-v1/store.json` for the id cache

### Step 1: Failing tests for both stores

```kotlin
// SimklDiscoverySnapshotFileStoreTest.kt
@Test
fun `snapshot round-trip`() = runTest {
    val store = SimklDiscoverySnapshotFileStore(filesDir = tmp.root)
    val snap = SimklDiscoverySnapshot(updatedAtMs = 1L)
    store.write(snap)
    assertEquals(snap, SimklDiscoverySnapshotFileStore(filesDir = tmp.root).read())
}

// SimklExternalIdCacheFileStoreTest.kt
@Test
fun `external id cache round-trip`() = runTest {
    val store = SimklExternalIdCacheFileStore(filesDir = tmp.root)
    val cache = mapOf("tmdb:550" to "simkl:42")
    store.write(cache)
    assertEquals(cache, SimklExternalIdCacheFileStore(filesDir = tmp.root).read())
}
```

### Step 2–8: Standard sequence

Replace `SimklDiscoverySnapshotStore.kt:101` and `:134` with delegate calls. Migration reads both prefs keys, writes both files, clears prefs.

Commit message: `perf(simkl): migrate Simkl snapshot + external-id cache to file-streaming JSON (rule #3)`.

---

## Task 7: TvdbIdentityCacheStore → per-key files

**Workstream:** P1

**Spec section:** `§ P1 table` row 5

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/local/TvdbIdentityCacheFileStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/TvdbIdentityCacheStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/TvdbIdentityCacheFileStoreTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/TvdbIdentityCacheStoreMigrationTest.kt`

Per-key files: `filesDir/tvdb-identity-cache-v1/<idTypeAndValue>.json`. Same shape as Task 4.

### Step 1: Failing tests

```kotlin
// TvdbIdentityCacheFileStoreTest.kt
@Test
fun `write then read round-trip`() = runTest {
    val store = TvdbIdentityCacheFileStore(filesDir = tmp.root)
    val record = TvdbIdentityRecord(
        // fill with actual constructor
    )
    store.write("imdb:tt0137523", record)
    assertEquals(record, TvdbIdentityCacheFileStore(filesDir = tmp.root).read("imdb:tt0137523"))
}
```

### Step 2–8: Standard sequence

Replace `TvdbIdentityCacheStore.kt:52` `prefs.edit().putString(key, gson.toJson(payload)).apply()` with delegate. Migration reads every prefs entry into per-key files.

Commit message: `perf(tvdb): migrate TvdbIdentityCacheStore to per-key file-streaming JSON (rule #3)`.

---

## Task 8: AddonRepositoryImpl manifest cache → file-streaming JSON

**Workstream:** P1

**Spec section:** `§ P1 table` row 6

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/AddonManifestCacheFileStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/AddonRepositoryImpl.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/AddonManifestCacheFileStoreTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/AddonRepositoryImplManifestMigrationTest.kt`

Single-file: `filesDir/addon-manifest-cache-v1/manifests.json`. Schema `{schemaVersion: 1, manifests: {<addonId>: Manifest}}`. Sparse writes on install/uninstall, no coalescing.

### Step 1: Failing test

```kotlin
@Test
fun `manifest cache round-trip`() = runTest {
    val store = AddonManifestCacheFileStore(filesDir = tmp.root)
    val manifests = mapOf("torrentio.strem.fun" to Manifest(/* ... */))
    store.write(manifests)
    assertEquals(manifests, AddonManifestCacheFileStore(filesDir = tmp.root).read())
}
```

### Step 2–8: Standard sequence

Replace `AddonRepositoryImpl.kt:125` `prefs.edit().putString(MANIFEST_CACHE_KEY, gson.toJson(manifestCache.toMap())).apply()` with `addonManifestCacheFileStore.write(manifestCache.toMap())`. Migration reads MANIFEST_CACHE_KEY, writes file, clears prefs.

Commit message: `perf(addon): migrate AddonRepositoryImpl manifest cache to file-streaming JSON (rule #3, 35 KiB growing)`.

---

## Task 9: PreferredArtworkProvidersMemo (rule #5)

**Workstream:** P2 (follow-up session)

**Spec section:** `§ P2.S4.1`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/PreferredArtworkProvidersMemo.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/PreferredArtworkProvidersMemoTest.kt`

### Step 1: Write failing test for memo identity

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import org.junit.Assert.assertSame
import org.junit.Test

class PreferredArtworkProvidersMemoTest {
    private val rpdb = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB)
    private val addon = ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON)

    @Test
    fun `intern returns reference-equal map for same content`() {
        val memo = PreferredArtworkProvidersMemo()
        val first = memo.intern(poster = rpdb, backdrop = addon, logo = addon, thumbnail = addon)
        val second = memo.intern(poster = rpdb, backdrop = addon, logo = addon, thumbnail = addon)
        assertSame(first, second)
    }

    @Test
    fun `intern returns distinct map for different content`() {
        val memo = PreferredArtworkProvidersMemo()
        val a = memo.intern(poster = rpdb, backdrop = addon, logo = addon, thumbnail = addon)
        val b = memo.intern(poster = addon, backdrop = addon, logo = addon, thumbnail = addon)
        assert(a !== b)
    }
}
```

### Step 2: Run, expect FAIL on `Unresolved reference: PreferredArtworkProvidersMemo`

### Step 3: Implement the memo

```kotlin
// app/src/main/java/com/nexio/tv/core/artwork/PreferredArtworkProvidersMemo.kt
package com.nexio.tv.core.artwork

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferredArtworkProvidersMemo @Inject constructor() {
    private val cache = ConcurrentHashMap<PreferredKey, Map<ArtworkType, ArtworkProviderId>>()

    private data class PreferredKey(
        val poster: String,
        val backdrop: String,
        val logo: String,
        val thumbnail: String
    )

    fun intern(
        poster: ArtworkProviderId,
        backdrop: ArtworkProviderId,
        logo: ArtworkProviderId,
        thumbnail: ArtworkProviderId
    ): Map<ArtworkType, ArtworkProviderId> =
        cache.getOrPut(PreferredKey(poster.key, backdrop.key, logo.key, thumbnail.key)) {
            mapOf(
                ArtworkType.POSTER to poster,
                ArtworkType.BACKDROP to backdrop,
                ArtworkType.LOGO to logo,
                ArtworkType.THUMBNAIL to thumbnail
            )
        }
}
```

### Step 4: Run test, expect PASS

### Step 5: Wire memo into HomeResolvedDisplayMapper

In `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`, change the `toResolvedDisplayItems` and `toResolvedDisplayItemsEnriched` signatures to accept a `PreferredArtworkProvidersMemo` parameter (defaulted to a private fresh instance for back-compat with tests). Replace the inline `mapOf(...)` at lines 248–261:

```kotlin
// Before:
val preferred = mapOf(
    ArtworkType.POSTER to resolver.resolve(POSTER, type, isAnime, stableIds, currentSettings),
    ArtworkType.BACKDROP to resolver.resolve(BACKDROP, type, isAnime, stableIds, currentSettings),
    ArtworkType.LOGO to resolver.resolve(LOGO, type, isAnime, stableIds, currentSettings),
    ArtworkType.THUMBNAIL to resolver.resolve(THUMBNAIL, type, isAnime, stableIds, currentSettings)
)

// After:
val preferred = preferredArtworkProvidersMemo.intern(
    poster = resolver.resolve(POSTER, type, isAnime, stableIds, currentSettings),
    backdrop = resolver.resolve(BACKDROP, type, isAnime, stableIds, currentSettings),
    logo = resolver.resolve(LOGO, type, isAnime, stableIds, currentSettings),
    thumbnail = resolver.resolve(THUMBNAIL, type, isAnime, stableIds, currentSettings)
)
```

Plumb the memo through the call sites in `HomeViewModelCatalogPipeline.kt` (where the mapper is invoked).

### Step 6: Run mapper tests + integration test

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.core.artwork.PreferredArtworkProvidersMemoTest" \
  --tests "com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapper*" \
  --max-workers=1
```

Expected: PASS.

### Step 7: On-device heap verification

```bash
DEV=192.168.50.98:5555
PID=$(adb -s $DEV shell pidof com.nexiodebug.tv | tr -d '\r')
adb -s $DEV shell am dumpheap "$PID" /sdcard/after-task9.hprof
adb -s $DEV pull /sdcard/after-task9.hprof /tmp/after-task9.hprof
heaptrail -i /tmp/after-task9.hprof --find-referrers com.nexio.tv.domain.model.ResolvedDisplayItem --hops 1 --top 8 | grep preferredArtworkProviders
heaptrail -i /tmp/after-task9.hprof -t 25 | grep LinkedHashMap
```

Expected: `preferredArtworkProviders` direct-referrers count drops to single digits (was 526). Total `LinkedHashMap` instance count drops by ~500.

### Step 8: Commit

```bash
git add \
  app/src/main/java/com/nexio/tv/core/artwork/PreferredArtworkProvidersMemo.kt \
  app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt \
  app/src/test/java/com/nexio/tv/core/artwork/PreferredArtworkProvidersMemoTest.kt
git commit -m "$(cat <<'EOF'
perf(artwork): intern preferredArtworkProviders map (CLAUDE.md rule #5)

Each ResolvedDisplayItem allocated its own 4-entry LinkedHashMap for
preferredArtworkProviders (526 instances retained in the post-v0.56
heap, one per ResolvedDisplayItem). Most items share the same provider
tuple (e.g., non-anime movies all map to {POSTER: RPDB, BACKDROP: ADDON,
LOGO: ADDON, THUMBNAIL: ADDON}).

Add PreferredArtworkProvidersMemo singleton interning content-equal
4-tuples to reference-equal Map instances; the steady-state cache size
is < 100 entries (4 providers x ArtworkProviderChoiceKey states x
isAnime x content-type). Compose stability skip benefits because === on
state.copy(preferredArtworkProviders = ref) now holds.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: LegacyPrefsCleanupPass — delete migrated XMLs

**Workstream:** P2

**Spec section:** `§ P2.S4.2`

**Files:**
- Modify: every migrated store's `warmUp`/migration path (Tasks 1, 3–8) + `MetadataDiskCacheStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/LegacyPrefsCleanupTest.kt`

### Step 1: Write failing test

```kotlin
// LegacyPrefsCleanupTest.kt
package com.nexio.tv.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LegacyPrefsCleanupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `migration deletes shared_prefs XML after successful migration`() = runTest {
        // Seed legacy
        context.getSharedPreferences("metadata_disk_cache_v1", Context.MODE_PRIVATE)
            .edit().putString("k", "v").commit()
        val legacyXml = File(context.applicationInfo.dataDir, "shared_prefs/metadata_disk_cache_v1.xml")
        assert(legacyXml.exists())

        // Instantiate the store — its boot-once migration should clean up.
        MetadataDiskCacheStore(context = context).warmUpForTest()

        assertFalse("Legacy XML should be deleted after migration", legacyXml.exists())
    }
}
```

### Step 2: Run test, expect FAIL

### Step 3: Add cleanup line to each migrated store

In each migrated store's migration tail (`migrateFromLegacyPrefs` for Task 1; equivalent in Tasks 3–8 and `MetadataDiskCacheStore`), append:

```kotlin
private fun migrateFromLegacyPrefs() {
    val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    val all = legacy.all
    if (all.isEmpty()) {
        // Already migrated previously; ensure the XML file is gone.
        context.deleteSharedPreferences(LEGACY_PREFS_NAME)
        return
    }
    // ... read + write new file ...
    legacy.edit().clear().apply()
    context.deleteSharedPreferences(LEGACY_PREFS_NAME)
}
```

Add to: `HydratedHomeOverlayStore` (Task 1), `MediaClipStore` (Task 3), `CatalogDiskCacheStore` (Task 4), `TraktDiscoverySnapshotStore` (Task 5), `SimklDiscoverySnapshotStore` (Task 6), `TvdbIdentityCacheStore` (Task 7), `AddonRepositoryImpl` (Task 8), and `MetadataDiskCacheStore` (already-migrated, gets the cleanup for the 217 KiB orphan).

### Step 4: Run cleanup test, expect PASS

### Step 5: On-device verify

```bash
adb -s $DEV shell "su -c 'ls -la /data/data/com.nexiodebug.tv/shared_prefs/'"
```

Expected: none of `hydrated_home_overlay_v1.xml`, `metadata_disk_cache_v1.xml`, `media_clip_store_v1.xml`, `catalog_disk_cache_v1.xml`, `trakt_discovery_snapshot.xml`, `simkl_discovery_snapshot_v1.xml`, `tvdb_identity_cache_v1.xml`, `addon_manifest_cache.xml` present after a fresh boot.

### Step 6: Commit

```bash
git add \
  app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt \
  app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt \
  app/src/main/java/com/nexio/tv/core/media/MediaClipStore.kt \
  app/src/main/java/com/nexio/tv/data/local/CatalogDiskCacheStore.kt \
  app/src/main/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStore.kt \
  app/src/main/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStore.kt \
  app/src/main/java/com/nexio/tv/data/local/TvdbIdentityCacheStore.kt \
  app/src/main/java/com/nexio/tv/data/repository/AddonRepositoryImpl.kt \
  app/src/test/java/com/nexio/tv/data/local/LegacyPrefsCleanupTest.kt
git commit -m "chore(prefs): delete legacy SharedPreferences XML after successful migration

Each migrated store now calls context.deleteSharedPreferences(LEGACY_NAME)
at the tail of its migration path. Reclaims the 217 KiB orphaned
metadata_disk_cache_v1.xml file plus the cleared XML stubs left behind
by Tasks 1, 3-8.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Final verification (after Task 10)

```bash
# 1. No more putString-of-gson.toJson in production code
grep -rn "putString.*gson\.toJson\|gson\.toJson.*putString" app/src/main/java
# Expected: zero matches

# 2. shared_prefs directory is small
adb -s $DEV shell "su -c 'du -h /data/data/com.nexiodebug.tv/shared_prefs/'"
# Expected: < 100 KiB total

# 3. All migrated files present in filesDir
adb -s $DEV shell "su -c 'ls /data/data/com.nexiodebug.tv/files/' | grep -E 'v1$'"
# Expected: 7 directories present
```

---

## Sequencing summary

| Task | Workstream | Session |
|---|---|---|
| 1. HydratedHomeOverlayStore | P0 | this |
| 2. updateCatalogRowsPipeline rule #6 | P0 | this |
| 3. MediaClipStore | P1 | follow-up |
| 4. CatalogDiskCacheStore | P1 | follow-up |
| 5. TraktDiscoverySnapshotStore | P1 | follow-up |
| 6. SimklDiscoverySnapshotStore | P1 | follow-up |
| 7. TvdbIdentityCacheStore | P1 | follow-up |
| 8. AddonRepositoryImpl manifest | P1 | follow-up |
| 9. PreferredArtworkProvidersMemo | P2 | follow-up |
| 10. LegacyPrefsCleanupPass | P2 | follow-up |