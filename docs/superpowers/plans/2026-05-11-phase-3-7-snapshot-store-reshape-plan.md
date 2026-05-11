# Phase 3.7 — `HomeCatalogSnapshotStore.Snapshot` reshape Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reshape `HomeCatalogSnapshotStore.Snapshot` to persist rail structure only (`Rail` + `RailItemKey`); add a new `ResolvedDisplaySnapshotStore` that persists the typed authority's `ResolvedDisplayItem` state; restore both at cold-start so rails render instantly with typed-authority content.

**Architecture:** Two coordinated persistence files (`filesDir/home-catalog-snapshot-v1/p<profileId>_<lang>.json` for rail structure + `filesDir/resolved-display-v1/p<profileId>_<lang>.json` for typed items), both written when the home pipeline debounce fires and both read at cold-start. v1 (schema 4) reads project the legacy `MetaPreview` content to `ResolvedDisplayItem` at `FIRST_PAINT` rank via the existing slot conversion infrastructure (`toFirstPaintSlots` + `HomeRailProjectionReducer.reduce`), preserving cold-start UX through the upgrade.

**Tech Stack:** Kotlin · Gson streaming `JsonReader`/`JsonWriter` · existing `Rail`/`RailItemKey` types (commits `a4faee398`, `1cced1db5`) · existing `HomeRailProjectionReducer` (`9c7dbe4ba`) · existing slot conversion helpers in `SlotConversions.kt`.

**Spec source:** `docs/superpowers/specs/2026-05-11-phase-3-7-snapshot-store-reshape-design.md`.

**Repo conventions (HARD RULES):**

1. **NEVER use `git stash`, `git add -A`, `git add .`, `git commit -a`.** Stage by explicit path. Working tree has other-workstream modified files (`TvdbMetadataServiceOriginalLanguageTest.kt`, `MetaDetailsTvdbAdvancedMetadataTest.kt`, `media` submodule, untracked plan markdowns); leave them alone.
2. **Smoke tests require profile selection.** After `monkey -p`, wait 5s, `adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER`, wait, then verify `HomeViewModel.*Persisted snapshot` log line. Press DPAD again if missing.
3. Direct main commits authorized per session pattern.

**Sequencing:** Tasks 2-6 form a single coordinated commit (the Snapshot data class change cascades into ~16 call sites and into the new persistence infrastructure — they must ship together or compile breaks). Task 1 commits an intentionally-failing TDD anchor. Task 7 is on-device verification.

---

## File Structure

### Created files

| File | Responsibility |
|---|---|
| `app/src/main/java/com/nexio/tv/data/local/ResolvedDisplaySnapshotStore.kt` | File-backed streaming JSON persistence for `Map<itemKey: String, ResolvedDisplayItem>`. Mirrors `HomeCatalogSnapshotStore` recipe. Per-profile + per-language file under `filesDir/resolved-display-v1/p<profileId>_<lang>.json`. |
| `app/src/main/java/com/nexio/tv/data/local/SnapshotV1MigrationProjector.kt` | Pure function: `projectLegacySnapshot(catalogRows: List<CatalogRow>, fullCatalogRows: List<CatalogRow>, heroItems: List<MetaPreview>, orderedGroupKeys: List<String>, nowMs: Long) → SnapshotV1MigrationResult(snapshotV5, typedCache: Map<itemKey, ResolvedDisplayItem>)`. Self-contained so the legacy read path stays readable and the projection logic is unit-testable. |
| `app/src/test/java/com/nexio/tv/data/local/ResolvedDisplaySnapshotStoreTest.kt` | Round-trip + error-handling tests for the new persistence class. |
| `app/src/test/java/com/nexio/tv/data/local/SnapshotV1MigrationProjectorTest.kt` | Unit tests for the v1 → v5 projection logic. |

### Modified files

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt` | `Snapshot` data class field swap. `SCHEMA_VERSION` bumps 4 → 5. `streamReadSnapshot` adds a v4 detection branch that delegates to `SnapshotV1MigrationProjector` (and writes the projected typed cache via injected `ResolvedDisplaySnapshotStore` reference). v5 path streams `displayRails` / `fullRails` / `heroItemKeys`. Write path always emits v5. Constructor accepts `ResolvedDisplaySnapshotStore` for the migration projection. |
| `app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt` | Add `fun restoreFromDisk(items: Map<String, ResolvedDisplayItem>, profileId: Int)`. Pre-populates the home surface in-memory state. Bypasses the `shouldSuppressSurfaceUpdate` gate (restore IS authoritative; not a competing emission). Idempotent — second call with the same items is a no-op. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt` | ~16 call sites updated. Read consumers: `snapshot.catalogRows` → `snapshot.displayRails` (and look up items via `resolvedDisplaySurfaceRepository.snapshotNow(profileId)`). Write coordination: `persistMergedHomeSnapshotIfNeeded` flushes both snapshot + typed cache. Read coordination: `restorePersistedCatalogSnapshotPipeline` reads typed cache + calls `restoreFromDisk`. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt` | Update `inMemoryHomeSnapshot` / `pendingRestoredCatalogSnapshot` / `pendingHomeSnapshotPersist` types to reference the new `Snapshot` shape (no logic change — the type alias is automatically resolved by Kotlin once the data class changes). Inject `resolvedDisplaySnapshotStore` for the pipeline to use. |

### Untouched

- `MetaPreview` / `CatalogRow` data classes (still emitted by producer at runtime as first-paint shells; this phase reshapes only the persisted form).
- `Rail` / `RailItemKey` types (already exist; no changes).
- `ResolvedDisplayItem` data class (already serialisable; just used in a new persistence boundary).
- `HydratedHomeOverlayStore` (independent persistence layer for overlay state).

---

## Task 1: TDD anchor — failing test files

**Files:**
- Create: `app/src/test/java/com/nexio/tv/data/local/ResolvedDisplaySnapshotStoreTest.kt`
- Create: `app/src/test/java/com/nexio/tv/data/local/SnapshotV1MigrationProjectorTest.kt`

- [ ] **Step 1: Create `ResolvedDisplaySnapshotStoreTest.kt`** (references types that don't exist yet — intentional fail)

```kotlin
package com.nexio.tv.data.local

import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResolvedDisplaySnapshotStoreTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private fun storeFor(profileId: Int = 1, lang: String = "en"): ResolvedDisplaySnapshotStore =
        ResolvedDisplaySnapshotStore.forTesting(
            rootDir = tempFolder.newFolder("resolved-display-v1"),
            activeProfileId = { profileId },
            currentLanguageTag = { lang },
        )

    private fun sampleItem(itemKey: String, title: String): ResolvedDisplayItem = ResolvedDisplayItem(
        itemKey = itemKey,
        contentId = itemKey.substringAfterLast(':'),
        parentId = itemKey.substringAfterLast(':'),
        itemType = ContentType.MOVIE,
        mediaKind = MetadataMediaKind.MOVIE,
        canonicalProvider = null,
        canonicalId = null,
        imdbId = null,
        stableIds = ProviderIds(),
        display = ResolvedDisplayFields(
            title = title, originalTitle = null, year = null, releaseDate = null,
            overview = null, genres = emptyList(), runtimeText = null
        ),
        artwork = ArtworkBundle(),
        rating = null,
        trailer = TrailerDisplayState(),
        hydrationState = HydrationState.PREVIEW_ONLY,
        sourceTrace = emptyList(),
        updatedAtMs = 0L,
        slots = null
    )

    @Test
    fun `round-trip empty map`() {
        val store = storeFor()
        store.write(emptyMap())
        val read = store.read()
        assertEquals(emptyMap<String, ResolvedDisplayItem>(), read)
    }

    @Test
    fun `round-trip single item`() {
        val store = storeFor()
        val item = sampleItem("movie:tt1234567", "Test Movie")
        store.write(mapOf(item.itemKey to item))
        val read = store.read()
        assertEquals(1, read.size)
        assertEquals("Test Movie", read[item.itemKey]?.display?.title)
    }

    @Test
    fun `round-trip multiple items preserves keys and values`() {
        val store = storeFor()
        val items = (1..5).associate { i ->
            "movie:tt$i" to sampleItem("movie:tt$i", "Movie $i")
        }
        store.write(items)
        val read = store.read()
        assertEquals(5, read.size)
        items.forEach { (key, expected) ->
            assertEquals(expected.display.title, read[key]?.display?.title)
        }
    }

    @Test
    fun `read returns empty map when file missing`() {
        val store = storeFor()
        val read = store.read()
        assertTrue(read.isEmpty())
    }
}
```

- [ ] **Step 2: Create `SnapshotV1MigrationProjectorTest.kt`** (references types that don't exist yet — intentional fail)

```kotlin
package com.nexio.tv.data.local

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotV1MigrationProjectorTest {

    private fun sampleMetaPreview(id: String, name: String): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = name,
        poster = "https://example.com/$id.jpg",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        runtime = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun sampleCatalogRow(catalogId: String, items: List<MetaPreview>): CatalogRow = CatalogRow(
        addonId = "test-addon",
        addonName = "Test Addon",
        addonBaseUrl = "https://test.example",
        catalogId = catalogId,
        catalogName = "Catalog $catalogId",
        type = ContentType.MOVIE,
        items = items
    )

    @Test
    fun `projects v1 catalogRows to v5 displayRails preserving structure`() {
        val metas = listOf(
            sampleMetaPreview("tt1", "Movie One"),
            sampleMetaPreview("tt2", "Movie Two"),
        )
        val v1Rows = listOf(sampleCatalogRow("c1", metas))

        val result = SnapshotV1MigrationProjector.projectLegacySnapshot(
            catalogRows = v1Rows,
            fullCatalogRows = v1Rows,
            heroItems = emptyList(),
            orderedGroupKeys = listOf("g1"),
            nowMs = 1_700_000_000_000L
        )

        assertEquals(1, result.snapshot.displayRails.size)
        assertEquals("c1", result.snapshot.displayRails[0].catalogId)
        assertEquals(2, result.snapshot.displayRails[0].items.size)
        assertEquals("tt1", result.snapshot.displayRails[0].items[0].contentId)
        assertEquals("tt2", result.snapshot.displayRails[0].items[1].contentId)
    }

    @Test
    fun `projects v1 heroItems to v5 heroItemKeys`() {
        val heroMetas = listOf(
            sampleMetaPreview("tt10", "Hero One"),
            sampleMetaPreview("tt11", "Hero Two"),
        )
        val result = SnapshotV1MigrationProjector.projectLegacySnapshot(
            catalogRows = emptyList(),
            fullCatalogRows = emptyList(),
            heroItems = heroMetas,
            orderedGroupKeys = emptyList(),
            nowMs = 1_700_000_000_000L
        )

        assertEquals(listOf("tt10", "tt11"), result.snapshot.heroItemKeys.map { it.contentId })
    }

    @Test
    fun `projects MetaPreview content to ResolvedDisplayItem at FIRST_PAINT rank`() {
        val meta = sampleMetaPreview("tt1", "Movie One")
        val v1Rows = listOf(sampleCatalogRow("c1", listOf(meta)))

        val result = SnapshotV1MigrationProjector.projectLegacySnapshot(
            catalogRows = v1Rows,
            fullCatalogRows = v1Rows,
            heroItems = emptyList(),
            orderedGroupKeys = emptyList(),
            nowMs = 1_700_000_000_000L
        )

        assertEquals(1, result.typedCache.size)
        val item = result.typedCache.values.first()
        assertEquals("Movie One", item.display.title)
        assertEquals(HydrationState.PREVIEW_ONLY, item.hydrationState)
        // Slots: each non-null projection should be at FIRST_PAINT rank
        val slots = item.slots
        assertTrue("title slot expected at FIRST_PAINT", slots?.title?.rank == DisplaySourceRank.FIRST_PAINT)
    }

    @Test
    fun `de-duplicates typed cache by itemKey when meta appears in multiple rows`() {
        val meta = sampleMetaPreview("tt1", "Movie One")
        val v1Rows = listOf(
            sampleCatalogRow("c1", listOf(meta)),
            sampleCatalogRow("c2", listOf(meta)) // same MetaPreview in two rows
        )

        val result = SnapshotV1MigrationProjector.projectLegacySnapshot(
            catalogRows = v1Rows,
            fullCatalogRows = v1Rows,
            heroItems = emptyList(),
            orderedGroupKeys = emptyList(),
            nowMs = 1_700_000_000_000L
        )

        assertEquals("typed cache de-dups by itemKey", 1, result.typedCache.size)
        assertEquals(2, result.snapshot.displayRails.size) // both rails preserved
    }
}
```

- [ ] **Step 3: Verify the tests fail to compile**

```bash
./gradlew :app:compileUniversalDebugUnitTestKotlin 2>&1 | grep -E "Unresolved reference|error:" | head -10
```

Expected: errors mentioning `ResolvedDisplaySnapshotStore`, `SnapshotV1MigrationProjector`, `displayRails`, `heroItemKeys` — the types don't exist yet.

- [ ] **Step 4: Commit the failing tests**

```bash
git add app/src/test/java/com/nexio/tv/data/local/ResolvedDisplaySnapshotStoreTest.kt
git add app/src/test/java/com/nexio/tv/data/local/SnapshotV1MigrationProjectorTest.kt
git status -sb
```

Verify only those two test files are staged.

```bash
git commit -m "$(cat <<'EOF'
test(home/snapshot): failing tests for Phase 3.7 reshape

TDD anchor. Tests reference types that don't exist yet
(ResolvedDisplaySnapshotStore, SnapshotV1MigrationProjector,
Snapshot.displayRails, Snapshot.heroItemKeys) — won't compile until
Task 2 lands. Committing failing tests first to lock in contracts.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
git push origin main 2>&1 | tail -3
```

---

## Task 2: Coordinated migration commit (Sub-tasks 2.1 – 2.6 ship as ONE commit)

Tasks 2.1 through 2.6 are atomic with respect to compile. Each sub-step is a discrete piece, but the commit happens once at Step 2.7 after all sub-steps land and the build is green.

### Sub-task 2.1: Create `SnapshotV1MigrationProjector.kt`

**File:** `app/src/main/java/com/nexio/tv/data/local/SnapshotV1MigrationProjector.kt`

- [ ] **Step 1: Write the projector**

```kotlin
package com.nexio.tv.data.local

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.enforceArtworkTypeBoundaries
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.Rail
import com.nexio.tv.domain.model.RailItemKey
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.toRail
import com.nexio.tv.ui.screens.home.HomeRailProjectionReducer
import com.nexio.tv.ui.screens.home.toFirstPaintSlots

/**
 * Phase 3.7 v1 (schema 4) → v5 read-time projection. Takes the legacy
 * snapshot's denormalized `MetaPreview`/`CatalogRow` content and returns
 * both the new structural [HomeCatalogSnapshotStore.Snapshot] shape AND
 * a typed-authority cache map suitable for writing to
 * [ResolvedDisplaySnapshotStore].
 *
 * Each [MetaPreview] is projected to a [ResolvedDisplayItem] via
 * `toFirstPaintSlots(nowMs)` + [HomeRailProjectionReducer.reduce] (no
 * overlay, no existing, no profile inputs). Slots are at `FIRST_PAINT`
 * rank — when the typed authority emits fresh content, the higher-rank
 * resolved data will win per CLAUDE.md rule #1.
 *
 * Pure function — no I/O, no side effects. The caller writes the typed
 * cache to disk via [ResolvedDisplaySnapshotStore.write].
 */
internal object SnapshotV1MigrationProjector {

    data class SnapshotV1MigrationResult(
        val snapshot: HomeCatalogSnapshotStore.Snapshot,
        val typedCache: Map<String, ResolvedDisplayItem>,
    )

    fun projectLegacySnapshot(
        catalogRows: List<CatalogRow>,
        fullCatalogRows: List<CatalogRow>,
        heroItems: List<MetaPreview>,
        orderedGroupKeys: List<String>,
        nowMs: Long,
    ): SnapshotV1MigrationResult {
        val typedCache = mutableMapOf<String, ResolvedDisplayItem>()

        // Project every MetaPreview that appears in any rail or in heroItems
        // into a ResolvedDisplayItem. De-dup by itemKey — the same MetaPreview
        // can appear in multiple rails (e.g. synthetic Trakt+TMDB groups).
        val rowsForRails = catalogRows + fullCatalogRows
        for (i in rowsForRails.indices) {
            val row = rowsForRails[i]
            val items = row.items
            for (j in items.indices) {
                val meta = items[j]
                val itemKey = homeDisplayItemKey(meta.apiType, meta.id)
                if (typedCache.containsKey(itemKey)) continue
                typedCache[itemKey] = meta.toResolvedDisplayItemForMigration(nowMs)
            }
        }
        for (i in heroItems.indices) {
            val meta = heroItems[i]
            val itemKey = homeDisplayItemKey(meta.apiType, meta.id)
            if (typedCache.containsKey(itemKey)) continue
            typedCache[itemKey] = meta.toResolvedDisplayItemForMigration(nowMs)
        }

        val snapshot = HomeCatalogSnapshotStore.Snapshot(
            displayRails = catalogRows.map { it.toRail() },
            fullRails = fullCatalogRows.map { it.toRail() },
            heroItemKeys = heroItems.map { RailItemKey(apiType = it.apiType, contentId = it.id) },
            orderedGroupKeys = orderedGroupKeys,
        )

        return SnapshotV1MigrationResult(snapshot = snapshot, typedCache = typedCache.toMap())
    }

    private fun MetaPreview.toResolvedDisplayItemForMigration(nowMs: Long): ResolvedDisplayItem {
        val firstPaintSlots = toFirstPaintSlots(nowMs)
        val merged = HomeRailProjectionReducer.reduce(
            firstPaint = firstPaintSlots,
            overlay = null,
            existing = null,
            profile = null,
        )
        val itemKey = homeDisplayItemKey(apiType, id)
        return ResolvedDisplayItem(
            itemKey = itemKey,
            contentId = id,
            parentId = id,
            itemType = type,
            mediaKind = when (apiType.lowercase()) {
                "movie" -> MetadataMediaKind.MOVIE
                "series", "tv", "show" -> MetadataMediaKind.SERIES
                else -> MetadataMediaKind.UNKNOWN
            },
            canonicalProvider = null,
            canonicalId = null,
            imdbId = firstPaintStableIds.imdb,
            stableIds = firstPaintStableIds,
            display = ResolvedDisplayFields(
                title = merged.title.value,
                originalTitle = merged.originalTitle.value,
                year = releaseInfo?.take(4)?.toIntOrNull(),
                releaseDate = merged.releaseInfo.value,
                overview = merged.overview.value,
                genres = merged.genres.value.orEmpty(),
                runtimeText = merged.runtime.value
            ),
            artwork = ArtworkBundle(
                poster = merged.poster.value,
                backdrop = merged.backdrop.value,
                logo = merged.logo.value,
                thumbnail = merged.thumbnail.value
            ).enforceArtworkTypeBoundaries(),
            rating = merged.rating.value,
            trailer = TrailerDisplayState(),
            hydrationState = HydrationState.PREVIEW_ONLY,
            sourceTrace = emptyList(),
            updatedAtMs = nowMs,
            slots = merged,
        )
    }
}

private val MetaPreview.firstPaintStableIds: ProviderIds
    get() = firstPaintStableIds  // delegates to MetaPreview's own firstPaintStableIds property; if naming collides, use this.firstPaintStableIds inline at the call site instead
```

NOTE: the bottom `private val MetaPreview.firstPaintStableIds` is an alias-only because Kotlin doesn't allow shadowing a property with itself. If `MetaPreview` already has a `firstPaintStableIds: ProviderIds` property, drop the alias and use `this.firstPaintStableIds` directly. Verify:

```bash
grep -n "firstPaintStableIds" app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt | head -3
```

If the property exists, delete the alias declaration from the file. If it doesn't, look at `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt` around line 132 (which references `firstPaintStableIds.withOverlayStableId(overlay)`) to confirm the access pattern, and replicate it here.

### Sub-task 2.2: Create `ResolvedDisplaySnapshotStore.kt`

**File:** `app/src/main/java/com/nexio/tv/data/local/ResolvedDisplaySnapshotStore.kt`

- [ ] **Step 1: Write the store**

```kotlin
package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.ResolvedDisplayItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 3.7 — file-backed streaming JSON persistence for the typed
 * authority's per-item state. Companion to [HomeCatalogSnapshotStore]:
 * the snapshot stores rail structure (RailItemKey lists); this store
 * holds the actual [ResolvedDisplayItem] content. Both are flushed in
 * the same write coordination call and read together at cold-start.
 *
 * Per-profile + per-language file path under
 * `filesDir/resolved-display-v1/p<profileId>_<lang>.json`. Mirrors the
 * HomeCatalogSnapshotStore recipe: streaming JsonReader for reads,
 * streaming JsonWriter + atomic Files.move rename for writes.
 */
@Singleton
class ResolvedDisplaySnapshotStore private constructor(
    private val rootDir: () -> File,
    private val activeProfileId: () -> Int,
    private val currentLanguageTag: () -> String,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        profileManager: ProfileManager,
    ) : this(
        rootDir = { File(context.filesDir, SNAPSHOT_DIR) },
        activeProfileId = { profileManager.activeProfileId.value },
        currentLanguageTag = { AppLocaleResolver.resolveEffectiveAppLanguageTag(context) },
    )

    companion object {
        private const val TAG = "ResolvedDisplayStore"
        private const val SNAPSHOT_DIR = "resolved-display-v1"
        private const val SCHEMA_VERSION = 1
        private val gson = Gson()
        private val mapType = object : TypeToken<Map<String, ResolvedDisplayItem>>() {}.type

        @JvmStatic
        fun forTesting(
            rootDir: File,
            activeProfileId: () -> Int,
            currentLanguageTag: () -> String = { "en" },
        ): ResolvedDisplaySnapshotStore = ResolvedDisplaySnapshotStore(
            rootDir = { rootDir },
            activeProfileId = activeProfileId,
            currentLanguageTag = currentLanguageTag,
        )
    }

    fun write(items: Map<String, ResolvedDisplayItem>, profileId: Int = activeProfileId()) {
        runCatching {
            val target = snapshotFileFor(profileId)
            target.parentFile?.mkdirs()
            val tempFile = File(target.parentFile, "${target.name}.tmp")
            FileOutputStream(tempFile).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                    JsonWriter(bw).use { writer ->
                        writer.beginObject()
                        writer.name("schemaVersion").value(SCHEMA_VERSION)
                        writer.name("items")
                        gson.toJson(items, mapType, writer)
                        writer.endObject()
                    }
                }
            }
            Files.move(
                tempFile.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to write resolved display snapshot", error)
        }
    }

    fun read(profileId: Int = activeProfileId()): Map<String, ResolvedDisplayItem> {
        val file = snapshotFileFor(profileId)
        if (!file.exists()) return emptyMap()
        return runCatching {
            FileInputStream(file).use { fis ->
                BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                    JsonReader(br).use { reader ->
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                            return@runCatching emptyMap<String, ResolvedDisplayItem>()
                        }
                        var schemaVersion = -1
                        var items: Map<String, ResolvedDisplayItem> = emptyMap()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "schemaVersion" -> {
                                    schemaVersion = reader.nextInt()
                                    if (schemaVersion > SCHEMA_VERSION) {
                                        return@runCatching emptyMap<String, ResolvedDisplayItem>()
                                    }
                                }
                                "items" -> {
                                    items = gson.fromJson(reader, mapType) ?: emptyMap()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        items
                    }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to read resolved display snapshot", error)
        }.getOrDefault(emptyMap())
    }

    fun clear(profileId: Int = activeProfileId()) {
        runCatching {
            snapshotFileFor(profileId).takeIf { it.exists() }?.delete()
        }
    }

    private fun snapshotFileFor(profileId: Int): File {
        val parent = rootDir()
        if (!parent.exists()) parent.mkdirs()
        val sanitizedTag = currentLanguageTag()
            .lowercase()
            .replace(Regex("[^a-z0-9_-]"), "_")
            .ifBlank { "unknown" }
        return File(parent, "p${profileId.coerceAtLeast(1)}_${sanitizedTag}.json")
    }
}
```

### Sub-task 2.3: Add `restoreFromDisk` to `ResolvedDisplaySurfaceRepository.kt`

**File:** `app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt`

- [ ] **Step 1: Read the existing file** to locate the right place to insert the new method:

```bash
sed -n '1,80p' app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt
```

Find the existing private surfaces MutableStateFlow declaration (around line 25) and the `publishResolvedItems` method (around line 50-70).

- [ ] **Step 2: Add the new method** immediately after `publishResolvedItems`:

```kotlin
    /**
     * Phase 3.7 — cold-start restore. Seeds the home-surface in-memory state
     * for [profileId] with [items] previously persisted by
     * `ResolvedDisplaySnapshotStore`. Bypasses the `shouldSuppressSurfaceUpdate`
     * gate: restore IS authoritative (we're recovering the typed authority's
     * last-known state from disk), not a competing fresh emission.
     *
     * Idempotent — a second call with the same items leaves the state unchanged
     * (the MutableStateFlow's `update` will short-circuit on reference equality
     * if the merged list is identical).
     */
    @Synchronized
    fun restoreFromDisk(items: Map<String, ResolvedDisplayItem>, profileId: Int) {
        if (items.isEmpty()) return
        val itemsList = items.values.toList()
        surfaces.update { current ->
            val currentSurface = current[HOME_SURFACE_KEY].orEmpty()
            val existing = currentSurface[profileId].orEmpty()
            // Merge: restored items fill gaps but never overwrite fresher ones
            // already in memory (e.g. if the producer beat us to it).
            val existingKeys = existing.map { it.itemKey }.toSet()
            val newItems = itemsList.filter { it.itemKey !in existingKeys }
            if (newItems.isEmpty()) return@update current
            val merged = existing + newItems
            current + (HOME_SURFACE_KEY to (currentSurface + (profileId to merged)))
        }
    }
```

### Sub-task 2.4: Reshape `HomeCatalogSnapshotStore.Snapshot`

**File:** `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`

- [ ] **Step 1: Bump `SCHEMA_VERSION`** from 4 → 5

Find at line ~107:
```kotlin
private const val SCHEMA_VERSION = 4
```
Replace:
```kotlin
private const val SCHEMA_VERSION = 5
```

- [ ] **Step 2: Replace the `Snapshot` data class** at line ~153

Find:
```kotlin
    data class Snapshot(
        val catalogRows: List<CatalogRow>,
        val fullCatalogRows: List<CatalogRow>,
        val heroItems: List<MetaPreview>,
        val orderedGroupKeys: List<String> = emptyList()
    )
```

Replace:
```kotlin
    data class Snapshot(
        val displayRails: List<Rail>,
        val fullRails: List<Rail>,
        val heroItemKeys: List<RailItemKey>,
        val orderedGroupKeys: List<String> = emptyList()
    )
```

- [ ] **Step 3: Add imports** at top of file:

```kotlin
import com.nexio.tv.domain.model.Rail
import com.nexio.tv.domain.model.RailItemKey
```

Remove the now-unused `CatalogRow` and `MetaPreview` imports IF the file no longer references them. Verify with:
```bash
grep -n "CatalogRow\b\|MetaPreview\b" app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt
```
Keep both imports if `streamReadSnapshot`'s v4 branch (Step 5 below) parses them.

- [ ] **Step 4: Inject `ResolvedDisplaySnapshotStore`** into the constructor

Find the existing `@Inject constructor(...)`. Add `private val resolvedDisplaySnapshotStore: ResolvedDisplaySnapshotStore` as a constructor parameter. The existing test constructor and any factory methods need the same addition.

```bash
grep -n "constructor(" app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt | head -5
```

For each constructor, add the parameter. Test-only constructors that don't need migration projection can pass a stub:

```kotlin
resolvedDisplaySnapshotStore = ResolvedDisplaySnapshotStore.forTesting(
    rootDir = File.createTempFile("rds", "").also { it.delete() }.also { it.mkdir() },
    activeProfileId = { 1 },
)
```

(Replace existing test-constructor body accordingly. Add `import java.io.File` if needed.)

- [ ] **Step 5: Update `streamReadSnapshot` to detect v4 and delegate to the migration projector**

Find the existing `streamReadSnapshot` method (around line ~312). It currently parses v4 directly. Restructure to detect the schema version first, then branch:

```kotlin
    private fun streamReadSnapshot(file: File, posterProviderToken: String): Snapshot? {
        return runCatching {
            FileInputStream(file).use { fis ->
                BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                    JsonReader(br).use { reader ->
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                            return@runCatching null
                        }
                        // Peek the schemaVersion field to dispatch v4 vs v5
                        // without re-opening the file. The schemaVersion is
                        // always the first field by writer convention; if not,
                        // we fall back to streaming the whole file and pick
                        // the branch after we've seen it.
                        var detectedVersion = -1
                        var capturedV4Fields: V4ParseState? = null
                        var capturedV5Fields: V5ParseState? = null
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "schemaVersion" -> {
                                    detectedVersion = reader.nextInt()
                                    if (detectedVersion != 4 && detectedVersion != SCHEMA_VERSION) {
                                        return@runCatching null
                                    }
                                }
                                "languageTag" -> {
                                    val tag = reader.nextString().trim()
                                    if (tag.isBlank() || tag != currentLanguageTag()) {
                                        return@runCatching null
                                    }
                                }
                                "posterProviderToken" -> {
                                    val token = reader.nextString().trim()
                                    if (token != posterProviderToken) {
                                        Log.d(TAG, "Poster provider changed ($token -> $posterProviderToken), invalidating snapshot")
                                        return@runCatching null
                                    }
                                }
                                "catalogRows", "fullCatalogRows", "heroItems" -> {
                                    if (capturedV4Fields == null) capturedV4Fields = V4ParseState()
                                    capturedV4Fields.parseField(reader, gson, catalogRowListType, metaPreviewListType)
                                }
                                "displayRails", "fullRails", "heroItemKeys" -> {
                                    if (capturedV5Fields == null) capturedV5Fields = V5ParseState()
                                    capturedV5Fields.parseField(reader, gson, railListType, railItemKeyListType)
                                }
                                "orderedGroupKeys" -> {
                                    val keys = gson.fromJson<List<String>>(reader, stringListType) ?: emptyList()
                                    capturedV4Fields?.orderedGroupKeys = keys
                                    capturedV5Fields?.orderedGroupKeys = keys
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        when (detectedVersion) {
                            4 -> migrateV4ToV5(capturedV4Fields ?: V4ParseState())
                            5 -> capturedV5Fields?.toSnapshot()
                            else -> null
                        }
                    }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to stream-read home snapshot from file", error)
        }.getOrNull()
    }

    private fun migrateV4ToV5(v4: V4ParseState): Snapshot {
        val nowMs = System.currentTimeMillis()
        val result = SnapshotV1MigrationProjector.projectLegacySnapshot(
            catalogRows = v4.catalogRows,
            fullCatalogRows = v4.fullCatalogRows,
            heroItems = v4.heroItems,
            orderedGroupKeys = v4.orderedGroupKeys,
            nowMs = nowMs,
        )
        runCatching {
            resolvedDisplaySnapshotStore.write(result.typedCache)
        }.onFailure { error ->
            Log.w(TAG, "Failed to write projected typed cache during v4 migration", error)
        }
        return result.snapshot
    }

    private class V4ParseState {
        var catalogRows: List<CatalogRow> = emptyList()
        var fullCatalogRows: List<CatalogRow> = emptyList()
        var heroItems: List<MetaPreview> = emptyList()
        var orderedGroupKeys: List<String> = emptyList()

        fun parseField(
            reader: JsonReader,
            gson: Gson,
            catalogRowListType: java.lang.reflect.Type,
            metaPreviewListType: java.lang.reflect.Type,
        ) {
            // peek the field name (we've already consumed it in the caller);
            // dispatch based on which field is currently being parsed.
            // Reader is now positioned at the field value.
            // The caller used reader.nextName() before invoking parseField,
            // so we need to know which name we just saw. Track via local var.
            // (Caller-side: this method is called within a when branch, so
            // the caller knows the name. The implementation below uses a
            // side channel: capture the latest-name in the caller.)
        }
    }
```

The above sketch is approximate — full implementation requires careful gson `Type` declarations (`catalogRowListType`, `metaPreviewListType`, `railListType`, `railItemKeyListType`, `stringListType`) at the top of the class. Use the existing `catalogRowListType` and `metaPreviewListType` declarations as a model. Add:

```kotlin
private val railListType: java.lang.reflect.Type = object : TypeToken<List<Rail>>() {}.type
private val railItemKeyListType: java.lang.reflect.Type = object : TypeToken<List<RailItemKey>>() {}.type
```

The above `V4ParseState.parseField` is intentionally incomplete — restructure the parse loop so each field's name is captured at the call site and `parseField` is called with the field name as a parameter:

```kotlin
"catalogRows" -> {
    if (capturedV4Fields == null) capturedV4Fields = V4ParseState()
    capturedV4Fields.catalogRows = gson.fromJson<List<CatalogRow>>(reader, catalogRowListType) ?: emptyList()
}
"fullCatalogRows" -> {
    if (capturedV4Fields == null) capturedV4Fields = V4ParseState()
    capturedV4Fields.fullCatalogRows = gson.fromJson<List<CatalogRow>>(reader, catalogRowListType) ?: emptyList()
}
"heroItems" -> {
    if (capturedV4Fields == null) capturedV4Fields = V4ParseState()
    capturedV4Fields.heroItems = gson.fromJson<List<MetaPreview>>(reader, metaPreviewListType) ?: emptyList()
}
"displayRails" -> {
    if (capturedV5Fields == null) capturedV5Fields = V5ParseState()
    capturedV5Fields.displayRails = gson.fromJson<List<Rail>>(reader, railListType) ?: emptyList()
}
"fullRails" -> {
    if (capturedV5Fields == null) capturedV5Fields = V5ParseState()
    capturedV5Fields.fullRails = gson.fromJson<List<Rail>>(reader, railListType) ?: emptyList()
}
"heroItemKeys" -> {
    if (capturedV5Fields == null) capturedV5Fields = V5ParseState()
    capturedV5Fields.heroItemKeys = gson.fromJson<List<RailItemKey>>(reader, railItemKeyListType) ?: emptyList()
}
```

`V4ParseState` and `V5ParseState` become simple field holders:

```kotlin
private class V4ParseState(
    var catalogRows: List<CatalogRow> = emptyList(),
    var fullCatalogRows: List<CatalogRow> = emptyList(),
    var heroItems: List<MetaPreview> = emptyList(),
    var orderedGroupKeys: List<String> = emptyList(),
)

private class V5ParseState(
    var displayRails: List<Rail> = emptyList(),
    var fullRails: List<Rail> = emptyList(),
    var heroItemKeys: List<RailItemKey> = emptyList(),
    var orderedGroupKeys: List<String> = emptyList(),
) {
    fun toSnapshot(): Snapshot = Snapshot(
        displayRails = displayRails,
        fullRails = fullRails,
        heroItemKeys = heroItemKeys,
        orderedGroupKeys = orderedGroupKeys,
    )
}
```

- [ ] **Step 6: Update the write path** to emit v5

Find `writeSnapshotToFile` / `streamSnapshotToFile` (around line ~250+). The current v4 write emits `catalogRows`, `fullCatalogRows`, `heroItems`. Replace with v5 fields:

```kotlin
// Inside the JsonWriter block:
writer.name("schemaVersion").value(SCHEMA_VERSION)
writer.name("languageTag").value(currentLanguageTag())
writer.name("posterProviderToken").value(posterProviderToken)
writer.name("displayRails")
gson.toJson(snapshot.displayRails, railListType, writer)
writer.name("fullRails")
gson.toJson(snapshot.fullRails, railListType, writer)
writer.name("heroItemKeys")
gson.toJson(snapshot.heroItemKeys, railItemKeyListType, writer)
writer.name("orderedGroupKeys")
gson.toJson(snapshot.orderedGroupKeys, stringListType, writer)
```

The exact existing structure may have additional fields (e.g., `posterProviderToken`); preserve those unchanged. The change is the field-shape swap, not the field set.

### Sub-task 2.5: Update `HomeViewModel.kt` for the new injected dependency

**File:** `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`

- [ ] **Step 1: Inject `ResolvedDisplaySnapshotStore`**

Find the `HomeViewModel` constructor (likely `@HiltViewModel` near top) and the existing `homeCatalogSnapshotStore` injection. Add a new parameter:

```kotlin
private val resolvedDisplaySnapshotStore: com.nexio.tv.data.local.ResolvedDisplaySnapshotStore,
```

If `HomeCatalogSnapshotStore` is injected here (it should be, given `pendingHomeSnapshotPersist` exists), the new store needs to be alongside it. Verify:

```bash
grep -n "homeCatalogSnapshotStore\|HomeCatalogSnapshotStore" app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt | head -5
```

- [ ] **Step 2: Update the snapshot state-variable type references**

The type aliases like `internal var inMemoryHomeSnapshot: HomeCatalogSnapshotStore.Snapshot? = null` at lines 511-514 don't need text changes — the `.Snapshot` reference resolves to the new shape automatically once Sub-task 2.4 lands.

### Sub-task 2.6: Update call sites in `HomeViewModelCatalogPipeline.kt`

**File:** `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`

The call sites read `snapshot.catalogRows` / `snapshot.fullCatalogRows` / `snapshot.heroItems` directly. After the reshape, these don't compile. Each site needs to be updated to either:
- Read `snapshot.displayRails` / `snapshot.fullRails` / `snapshot.heroItemKeys` directly (for structural use)
- Look up items via `resolvedDisplaySurfaceRepository.snapshotNow(profileId)` and join with the rail structure (for content use)

- [ ] **Step 1: List all call sites**

```bash
grep -n "snapshot\.catalogRows\|snapshot\.fullCatalogRows\|snapshot\.heroItems\|restoredSnapshot\.catalogRows\|restoredSnapshot\.fullCatalogRows\|restoredSnapshot\.heroItems" app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
```

There should be ~16 matches across the file. For each match, the fix pattern is:

| Old pattern | New pattern |
|---|---|
| `snapshot.catalogRows.size` | `snapshot.displayRails.sumOf { it.items.size }` (item count) or `snapshot.displayRails.size` (rail count) — depends on context |
| `snapshot.catalogRows.isEmpty()` | `snapshot.displayRails.isEmpty() || snapshot.displayRails.all { it.items.isEmpty() }` |
| `snapshot.catalogRows.filter(::isRetained)` | Filter the typed cache instead — but since the filter operates on `CatalogRow`, this needs to be reworked. See Step 2. |
| `snapshot.heroItems.filter { item -> ... item.id ... }` | `snapshot.heroItemKeys.filter { key -> ... key.contentId ... }` |

- [ ] **Step 2: Rework the filter helpers**

Find `filterRestoredHomeSnapshotTmdbRows`, `filterRestoredHomeSnapshotKitsuRows`, and `filterDisabledHomeCatalogRows` (around lines 3154, 3215, 3309). These currently filter `Snapshot.catalogRows` by row identity (catalogId). The new Snapshot has `displayRails`, which has the same `catalogId` field — direct port:

For each function with signature `(snapshot: Snapshot, ...) → Snapshot`, replace:
- `snapshot.catalogRows.filter(::isRetained)` → `snapshot.displayRails.filter(::isRetainedRail)`
- `snapshot.fullCatalogRows.filter(::isRetained)` → `snapshot.fullRails.filter(::isRetainedRail)`
- `snapshot.heroItems.filter { item -> ... }` → `snapshot.heroItemKeys.filter { key -> ... }`

Where `isRetainedRail: (Rail) -> Boolean` mirrors the existing `isRetained: (CatalogRow) -> Boolean` predicate (likely `it.catalogId !in droppedKeys` style — preserve the catalogId-based logic exactly).

- [ ] **Step 3: Apply the snapshot to the runtime state**

The current code likely does (around line 2947 — the `transientSnapshot` construction site):
```kotlin
val transientSnapshot = HomeCatalogSnapshotStore.Snapshot(
    catalogRows = _displayCatalogRows.value,
    fullCatalogRows = ...,
    heroItems = _displayHeroItems.value,
    orderedGroupKeys = ...
)
```

Replace with:
```kotlin
val transientSnapshot = HomeCatalogSnapshotStore.Snapshot(
    displayRails = _displayCatalogRows.value.map { it.toRail() },
    fullRails = ..._displayCatalogRowsFullVariableHere..._.map { it.toRail() },
    heroItemKeys = _displayHeroItems.value.map { RailItemKey(it.apiType, it.id) },
    orderedGroupKeys = ...
)
```

The `CatalogRow.toRail()` extension already exists (commit `1cced1db5`).

- [ ] **Step 4: Restore call-site for the typed cache**

Find `restorePersistedCatalogSnapshotPipeline` at line ~147. After the snapshot read succeeds, add:

```kotlin
val typedCache = resolvedDisplaySnapshotStore.read(profileId)
resolvedDisplaySurfaceRepository.restoreFromDisk(items = typedCache, profileId = profileId)
```

Place this BEFORE `applyPendingPersistedHomeSnapshotIfPossiblePipeline` so the typed authority is warm when rendering kicks off.

- [ ] **Step 5: Write coordination**

Find the existing snapshot write call site (search `homeCatalogSnapshotStore.write`):

```bash
grep -n "homeCatalogSnapshotStore.write" app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
```

For each, immediately after the snapshot write, add:

```kotlin
resolvedDisplaySnapshotStore.write(
    items = resolvedDisplaySurfaceRepository.snapshotNow(profileId).associateBy { it.itemKey },
    profileId = profileId,
)
```

Use the same `profileId` variable that was passed to the snapshot write.

- [ ] **Step 6: Verify call site count**

After all updates:
```bash
grep -n "\.catalogRows\|\.fullCatalogRows\|\.heroItems\b" app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt | grep -v "//" | head -10
```

Expected: empty output (or only comments referencing the migration history).

### Sub-task 2.7: Compile + test + commit

- [ ] **Step 1: Compile main**

```bash
./gradlew :app:compileUniversalDebugKotlin 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Compile tests**

```bash
./gradlew :app:compileUniversalDebugUnitTestKotlin 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`. Any test files that construct `HomeCatalogSnapshotStore.Snapshot(catalogRows = ..., heroItems = ...)` will break compile. Update them to the new shape:
```kotlin
HomeCatalogSnapshotStore.Snapshot(
    displayRails = listOf(...),
    fullRails = listOf(...),
    heroItemKeys = listOf(...),
    orderedGroupKeys = ...
)
```

Find such tests:
```bash
grep -rn "HomeCatalogSnapshotStore\.Snapshot(\|catalogRows\s*=" app/src/test --include="*.kt"
```

For each, port the test data to the new shape (use `.toRail()` on legacy CatalogRow constructors; map MetaPreview → RailItemKey).

- [ ] **Step 3: Run the new unit tests**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*ResolvedDisplaySnapshotStoreTest*" 2>&1 | tail -10
./gradlew :app:testUniversalDebugUnitTest --tests "*SnapshotV1MigrationProjectorTest*" 2>&1 | tail -10
```
Expected: all pass.

- [ ] **Step 4: Run all related tests**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*HomeCatalogSnapshotStore*" 2>&1 | tail -15
```
Expected: all pass.

- [ ] **Step 5: Stage by explicit path**

Stage each modified file by name. **DO NOT** use `git add -A`, `git add .`, or `git commit -a` (rule #7).

```bash
git add app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt
git add app/src/main/java/com/nexio/tv/data/local/ResolvedDisplaySnapshotStore.kt
git add app/src/main/java/com/nexio/tv/data/local/SnapshotV1MigrationProjector.kt
git add app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
# Plus any test files you needed to update for compile (paste paths from Step 2 grep).
git status -sb
```

Verify only intended files are staged. Restore-staged any sweeps.

- [ ] **Step 6: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(home/snapshot): Phase 3.7 reshape — Rail/RailItemKey persistence + typed-authority cache

Phase 3.7 of the home-MetaPreview-elimination spec. Reshapes
HomeCatalogSnapshotStore.Snapshot to persist rail structure only
(Rail + RailItemKey), eliminating denormalized MetaPreview content
from the persisted file format. Adds a new parallel persistence
boundary (ResolvedDisplaySnapshotStore) that stores the typed
authority's ResolvedDisplayItem state. Restores both at cold-start
so rails render instantly with typed-authority content — no UX
regression.

Snapshot v5 shape:
  data class Snapshot(
      val displayRails: List<Rail>,
      val fullRails: List<Rail>,
      val heroItemKeys: List<RailItemKey>,
      val orderedGroupKeys: List<String> = emptyList(),
  )

v4 -> v5 migration: streamReadSnapshot detects schema 4 in the file
header, parses legacy CatalogRow/MetaPreview content, projects each
MetaPreview to ResolvedDisplayItem at FIRST_PAINT rank via
SnapshotV1MigrationProjector (toFirstPaintSlots + reducer.reduce),
writes the projected typed cache via ResolvedDisplaySnapshotStore,
returns the v5 Snapshot. One-time per device per profile; subsequent
reads use the v5 streaming path.

SCHEMA_VERSION bumped 4 -> 5.

Write coordination: persistMergedHomeSnapshotIfNeeded flushes both
files (snapshot first, then typed cache). Read coordination:
restorePersistedCatalogSnapshotPipeline reads the typed cache and
seeds the typed authority via restoreFromDisk before render.

Call sites in HomeViewModelCatalogPipeline updated (~16) to read
displayRails / heroItemKeys structure and look up content via
ResolvedDisplaySurfaceRepository.

Test infrastructure: ResolvedDisplaySnapshotStoreTest covers
round-trip + error handling; SnapshotV1MigrationProjectorTest covers
the v4 -> v5 projection logic.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
git push origin main 2>&1 | tail -3
```

---

## Task 3: On-device verification

**Files:** none (verification only).

- [ ] **Step 1: Build APK**

```bash
./gradlew :app:assembleUniversalDebug 2>&1 | tail -3
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Install on device**

```bash
adb -s 192.168.50.98:5555 install -r app/build/outputs/apk/universal/debug/app-universal-debug.apk 2>&1 | tail -2
```
Expected: `Success`.

- [ ] **Step 3: First post-upgrade launch — v4 migration acceptance**

The user's device has a v4 snapshot file on disk (legacy). On first launch with the new APK, the migration projector runs.

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1 2>&1 | tail -1
sleep 5
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 10
until adb -s 192.168.50.98:5555 logcat -d -t 1000 | grep -q "HomeViewModel.*Persisted snapshot"; do
  adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
  sleep 5
done
sleep 30
adb -s 192.168.50.98:5555 logcat -d -t 600 | grep -E "FATAL EXCEPTION|ANR in com.nexiodebug|ClassCastException|NoSuchMethodError|JsonSyntaxException" | tail -10
```
Expected: empty crash output. Open the home screen and observe rails. **Rails should render with the same content as pre-upgrade** — the migration projection preserves visible state through the schema bump.

- [ ] **Step 4: Verify the typed cache file exists**

```bash
adb -s 192.168.50.98:5555 shell ls -la "/data/data/com.nexiodebug.tv/files/resolved-display-v1/"
```
Expected: one file matching `p<profileId>_<lang>.json`, non-empty.

- [ ] **Step 5: Second launch — v5 steady-state acceptance**

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 5
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 10
until adb -s 192.168.50.98:5555 logcat -d -t 1000 | grep -q "HomeViewModel.*Persisted snapshot"; do
  adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
  sleep 5
done
sleep 30
adb -s 192.168.50.98:5555 logcat -d -t 600 | grep -E "FATAL EXCEPTION|ANR in com.nexiodebug|ClassCastException|NoSuchMethodError|JsonSyntaxException" | tail -10
```
Open home screen. Rails should render instantly with typed-authority content. **No blank period** — the typed cache from Step 4 was just restored.

- [ ] **Step 6: Capture heap + verify acceptance criterion**

```bash
PID=$(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv | tr -d '\r\n')
adb -s 192.168.50.98:5555 shell am dumpheap "$PID" /data/local/tmp/heap-3-7.hprof
until [ $(adb -s 192.168.50.98:5555 shell ls -l /data/local/tmp/heap-3-7.hprof 2>/dev/null | awk '{print $5}') -gt 50000000 ]; do sleep 2; done
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap-3-7.hprof /tmp/heap-3-7.hprof 2>&1 | tail -1
heaptrail -i /tmp/heap-3-7.hprof --find-referrers com.nexio.tv.domain.model.MetaPreview --hops 2 --top 30 2>&1 | tail -30
```

**Expected:** No retainer chain through `HomeCatalogSnapshotStore.*` or `Snapshot.*` to `MetaPreview`. Acceptable retainers:
- `_displayCatalogRows.value` (runtime StateFlow — out of scope for Phase 3.7; Phase 3.9 retires)
- `HydratedHomeOverlay.fields → MetaPreview` (overlay store — out of scope)
- `MetadataDiskCacheStore` chains (TVDB cache — out of scope)
- Transient pipeline locals

NO `HomeCatalogSnapshotStore.Snapshot.*` retainers should appear — that's the acceptance criterion.

- [ ] **Step 7: GC pattern check**

```bash
adb -s 192.168.50.98:5555 logcat -d -t 5000 | grep -E "Background concurrent" | tail -15
```
Expected: matches the current post-3.6.5 baseline (idle gaps 5s+, LOS < 5MB per cycle). No death-spiral signature.

- [ ] **Step 8: Sign-off**

If all 7 prior steps passed, Phase 3.7 is complete. Original Phase 3 catalog-pipeline-restructure spec is fully implemented end-to-end.

If any step fails or rails render with missing content, REPORT BLOCKED with the heap dump file path, the failed step number, and a description of what was observed.

---

## Self-review

**1. Spec coverage check:**

- Snapshot v5 shape (spec § Snapshot v5 data class) → Sub-task 2.4 Step 2 ✓
- ResolvedDisplaySnapshotStore (spec § Created files) → Sub-task 2.2 ✓
- SnapshotV1MigrationProjector (spec § Created files) → Sub-task 2.1 ✓
- ResolvedDisplaySurfaceRepository.restoreFromDisk (spec § Modified files) → Sub-task 2.3 ✓
- Write coordination (spec § Write coordination) → Sub-task 2.6 Step 5 ✓
- Cold-start read flow v5 (spec § Cold-start read flow v2) → Sub-task 2.6 Step 4 ✓
- Cold-start read flow v1 (spec § Cold-start read flow v1) → Sub-task 2.4 Steps 5-6 + Sub-task 2.1 (projector) ✓
- Error handling matrix (spec § Error handling matrix) → covered across the streaming JsonReader/Writer use of runCatching + log warning + return null in Sub-tasks 2.2 + 2.4 ✓
- Testing acceptance (spec § Testing) → Tasks 1 + 2.7 (unit) + Task 3 (on-device) ✓
- Schema version bump 4 → 5 (spec § Self-review ambiguity) → Sub-task 2.4 Step 1 ✓
- FIRST_PAINT rank for v1 projection (spec § Self-review ambiguity) → Sub-task 2.1 (uses `toFirstPaintSlots`) ✓

No spec gaps.

**2. Placeholder scan:** None. Every step has explicit file paths and code. The `V4ParseState.parseField` sketch was intentionally replaced with the inline call-site implementation; the final implementation uses direct field-by-field gson.fromJson calls per Step 5's revised guidance.

**3. Type consistency:**
- `Snapshot.displayRails: List<Rail>` is the same type across data class declaration, V5ParseState, write path, and read path.
- `SnapshotV1MigrationResult.snapshot` returns a `HomeCatalogSnapshotStore.Snapshot` (matches the migrateV4ToV5 return type).
- `ResolvedDisplaySnapshotStore.read()/write()` use `Map<String, ResolvedDisplayItem>` consistently.
- `ResolvedDisplaySurfaceRepository.restoreFromDisk(items: Map<String, ResolvedDisplayItem>, profileId: Int)` matches the call site in Sub-task 2.6 Step 4.

No drift.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-11-phase-3-7-snapshot-store-reshape-plan.md`. Two execution options:

**1. Subagent-Driven (recommended)** — fresh subagent per task with two-stage review, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
