# Catalog Inventory Repository Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract `HomeViewModel._fullCatalogRows: MutableStateFlow<List<CatalogRow>>` into a `@Singleton CatalogInventoryRepository` keyed by `addonId_apiType_catalogId`, so consumers read in their native shape (single rail by key, set, snapshot map) instead of the full-inventory list.

**Architecture:** New `@Singleton` repository owns a `LinkedHashMap<String, CatalogRow>` inventory. `HomeViewModelCatalogPipeline` keeps computing the inventory (push model) and calls `inventoryRepository.publish(rows)`. Each of the 5 internal pipeline reads + 1 external `CatalogSeeAllScreen` consumer migrates to the repository's typed accessors. Migration is staged: dual-write first, cut over reads, delete old fields last — each stage independently compilable + testable.

**Tech Stack:** Kotlin, Hilt DI (`@Singleton @Inject constructor()`), `kotlinx.coroutines.flow` (`MutableStateFlow`, `distinctUntilChanged`), JUnit 4 + MockK for unit tests.

**Spec:** `docs/superpowers/specs/2026-05-10-catalog-inventory-repository-design.md`

**Plan B sequencing:** Insert before Plan B Task 17 (Continue Watching). Do NOT touch the resolved-display layer or any of the Plan B per-surface projection types.

---

## File Structure

### New files

| Path | Responsibility |
|---|---|
| `app/src/main/java/com/nexio/tv/data/repository/CatalogInventoryRepository.kt` | `@Singleton` repository. Owns `MutableStateFlow<Map<String, CatalogRow>>` inventory. Exposes `inventory: StateFlow`, `snapshot()`, `observeRail(key)`, `isEmpty()`, `activeItemKeys()`, `publish(rows)`, `clear()`. |
| `app/src/test/java/com/nexio/tv/data/repository/CatalogInventoryRepositoryTest.kt` | 12 unit tests covering publish/snapshot round-trip, ordering, key collision, blank-component skip, isEmpty lifecycle, observeRail filtering + null on missing/removed, clear, activeItemKeys aggregation, concurrent publish/snapshot non-tearing. |

### Modified files

| Path | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt` | Inject `CatalogInventoryRepository` constructor param. Add `fun observeCatalogRail(key: String): Flow<CatalogRow?>` façade. Delete `_fullCatalogRows` and `fullCatalogRows` fields (Task 6, last stage). |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt` | 5 site replacements: writer (line 3126) → `publish`, two resets (281, 2201) → `clear`, three reads (1477, 1623, 2792) → repo accessors. |
| `app/src/main/java/com/nexio/tv/ui/screens/CatalogSeeAllScreen.kt` | Replace `viewModel.fullCatalogRows.collectAsState() + .find { ... }` with `viewModel.observeCatalogRail(catalogKey).collectAsState(initial = null)`. |
| `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTest.kt`<br>`app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogStartupReadinessTest.kt`<br>`app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelFocusHydrationTest.kt`<br>`app/src/test/java/com/nexio/tv/core/search/AndroidTvLocalSearchCorpusTest.kt`<br>`app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt` | Migrate any reference to `_fullCatalogRows` / `fullCatalogRows` to use `CatalogInventoryRepository` mocks/instances. Verify per-file in Task 6. |

---

## Task 1: Create `CatalogInventoryRepository` with full unit-test coverage

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/CatalogInventoryRepository.kt`
- Create: `app/src/test/java/com/nexio/tv/data/repository/CatalogInventoryRepositoryTest.kt`

- [ ] **Step 1: Write the failing test file with all 12 tests**

```kotlin
// app/src/test/java/com/nexio/tv/data/repository/CatalogInventoryRepositoryTest.kt
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.MetaPreview
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogInventoryRepositoryTest {

    private fun row(addonId: String = "a", apiType: String = "movie", catalogId: String = "c", items: List<MetaPreview> = emptyList()): CatalogRow =
        CatalogRow(addonId = addonId, apiType = apiType, catalogId = catalogId, items = items)

    private fun preview(id: String, apiType: String = "movie"): MetaPreview =
        MetaPreview(id = id, apiType = apiType)

    @Test
    fun `publish then snapshot returns map keyed by addonId_apiType_catalogId`() {
        val repo = CatalogInventoryRepository()
        val r1 = row(addonId = "addonX", apiType = "movie", catalogId = "popular")
        repo.publish(listOf(r1))
        assertSame(r1, repo.snapshot()["addonX_movie_popular"])
    }

    @Test
    fun `publish preserves insertion order via LinkedHashMap`() {
        val repo = CatalogInventoryRepository()
        val r1 = row(addonId = "z", catalogId = "1")
        val r2 = row(addonId = "a", catalogId = "2")
        val r3 = row(addonId = "m", catalogId = "3")
        repo.publish(listOf(r1, r2, r3))
        assertEquals(listOf("z_movie_1", "a_movie_2", "m_movie_3"), repo.snapshot().keys.toList())
    }

    @Test
    fun `publish overwrites prior entry for same triple key`() {
        val repo = CatalogInventoryRepository()
        val r1 = row(addonId = "a", catalogId = "c", items = listOf(preview("x")))
        val r2 = row(addonId = "a", catalogId = "c", items = listOf(preview("y")))
        repo.publish(listOf(r1, r2))
        assertSame(r2, repo.snapshot()["a_movie_c"])
        assertEquals(1, repo.snapshot().size)
    }

    @Test
    fun `publish skips rows with blank addonId apiType or catalogId`() {
        val repo = CatalogInventoryRepository()
        val good = row(addonId = "ok", apiType = "movie", catalogId = "c")
        val blankAddon = row(addonId = "", apiType = "movie", catalogId = "c")
        val blankApi = row(addonId = "ok", apiType = "", catalogId = "c")
        val blankCatalog = row(addonId = "ok", apiType = "movie", catalogId = "")
        repo.publish(listOf(good, blankAddon, blankApi, blankCatalog))
        assertEquals(setOf("ok_movie_c"), repo.snapshot().keys)
    }

    @Test
    fun `isEmpty true on init and after clear`() {
        val repo = CatalogInventoryRepository()
        assertTrue(repo.isEmpty())
        repo.publish(listOf(row()))
        repo.clear()
        assertTrue(repo.isEmpty())
    }

    @Test
    fun `isEmpty false after non-empty publish`() {
        val repo = CatalogInventoryRepository()
        repo.publish(listOf(row()))
        assertFalse(repo.isEmpty())
    }

    @Test
    fun `observeRail emits initial null then rail when published`() = runTest {
        val repo = CatalogInventoryRepository()
        val emissions = mutableListOf<CatalogRow?>()
        val job = kotlinx.coroutines.launch {
            repo.observeRail("a_movie_c").collect { emissions += it }
        }
        kotlinx.coroutines.yield()
        repo.publish(listOf(row(addonId = "a", catalogId = "c")))
        kotlinx.coroutines.yield()
        job.cancel()
        assertNull(emissions.first())
        assertNotNull(emissions.last())
    }

    @Test
    fun `observeRail filters distinct — same content yields one emission`() = runTest {
        val repo = CatalogInventoryRepository()
        val r1 = row(addonId = "a", catalogId = "c")
        val emissions = mutableListOf<CatalogRow?>()
        val job = kotlinx.coroutines.launch {
            repo.observeRail("a_movie_c").collect { emissions += it }
        }
        kotlinx.coroutines.yield()
        repo.publish(listOf(r1))
        kotlinx.coroutines.yield()
        repo.publish(listOf(r1))  // same content, same reference
        kotlinx.coroutines.yield()
        job.cancel()
        // initial null + one rail emission = 2 total; the second publish does not emit
        assertEquals(2, emissions.size)
    }

    @Test
    fun `observeRail returns null when rail removed from publish`() = runTest {
        val repo = CatalogInventoryRepository()
        val r1 = row(addonId = "a", catalogId = "c")
        repo.publish(listOf(r1))
        val emissions = mutableListOf<CatalogRow?>()
        val job = kotlinx.coroutines.launch {
            repo.observeRail("a_movie_c").collect { emissions += it }
        }
        kotlinx.coroutines.yield()
        repo.publish(emptyList())  // rail disappears
        kotlinx.coroutines.yield()
        job.cancel()
        assertEquals(r1, emissions.first())
        assertNull(emissions.last())
    }

    @Test
    fun `clear empties the inventory`() {
        val repo = CatalogInventoryRepository()
        repo.publish(listOf(row()))
        repo.clear()
        assertTrue(repo.snapshot().isEmpty())
    }

    @Test
    fun `activeItemKeys aggregates apiType colon id across all rails`() {
        val repo = CatalogInventoryRepository()
        val r1 = row(addonId = "a", apiType = "movie", catalogId = "c1", items = listOf(preview("x", "movie"), preview("y", "movie")))
        val r2 = row(addonId = "b", apiType = "series", catalogId = "c2", items = listOf(preview("z", "series")))
        repo.publish(listOf(r1, r2))
        assertEquals(setOf("movie:x", "movie:y", "series:z"), repo.activeItemKeys())
    }

    @Test
    fun `concurrent publish and snapshot does not tear`() = runTest {
        val repo = CatalogInventoryRepository()
        val rowsA = (1..50).map { row(catalogId = "a$it") }
        val rowsB = (1..50).map { row(catalogId = "b$it") }
        coroutineScope {
            val publish = async {
                repeat(20) {
                    repo.publish(if (it % 2 == 0) rowsA else rowsB)
                }
            }
            val read = async {
                repeat(20) {
                    val snap = repo.snapshot()
                    // every snapshot must be coherent: all keys belong to one batch
                    val first = snap.keys.firstOrNull()?.split("_")?.last()?.first()
                    if (first != null) {
                        assertTrue(snap.keys.all { it.split("_").last().first() == first })
                    }
                }
            }
            publish.await(); read.await()
        }
    }
}
```

- [ ] **Step 2: Run the failing test to verify it fails**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.repository.CatalogInventoryRepositoryTest"
```
Expected: BUILD FAILED — `Unresolved reference 'CatalogInventoryRepository'`.

- [ ] **Step 3: Write minimal repository**

```kotlin
// app/src/main/java/com/nexio/tv/data/repository/CatalogInventoryRepository.kt
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.CatalogRow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Owns the catalog inventory — every rail across every addon, Trakt synthetic
 * group, MDBList, Simkl, TMDB, Kitsu — keyed by `addonId_apiType_catalogId`.
 *
 * Replaces `HomeViewModel._fullCatalogRows: MutableStateFlow<List<CatalogRow>>`,
 * which retained 17-28 MiB on `HomeViewModel`'s dominator subtree under
 * sustained Modern Home use. Internal pipeline consumers read in their native
 * shape (snapshot map, set of item keys, emptiness flag); `CatalogSeeAllScreen`
 * observes a single rail by key instead of the full inventory list.
 *
 * Push model: `HomeViewModelCatalogPipeline.applyHomeSnapshotToUiPipeline`
 * keeps building the inventory and calls [publish] each emission. Repo does
 * not subscribe to upstream sources itself.
 *
 * Spec: docs/superpowers/specs/2026-05-10-catalog-inventory-repository-design.md
 */
@Singleton
class CatalogInventoryRepository @Inject constructor() {

    private val _inventory = MutableStateFlow<Map<String, CatalogRow>>(emptyMap())
    val inventory: StateFlow<Map<String, CatalogRow>> = _inventory.asStateFlow()

    /** Synchronous read for `HomeViewModelCatalogPipeline` internal use. */
    fun snapshot(): Map<String, CatalogRow> = _inventory.value

    /** Single-rail observation for `CatalogSeeAllScreen`. Filtered + distinct
     *  so only rail-content changes propagate to the consumer. */
    fun observeRail(key: String): Flow<CatalogRow?> =
        _inventory.map { it[key] }.distinctUntilChanged()

    /** Used in place of `_fullCatalogRows.value.isEmpty()` for the
     *  `rawFirstPaintBatchActive` flag in `runSerializedPostStartupRefreshPipeline`. */
    fun isEmpty(): Boolean = _inventory.value.isEmpty()

    /**
     * Aggregates `"${apiType}:${id}"` strings across every item in every rail.
     * Replaces the inventory portion of the inline build at
     * `HomeViewModelCatalogPipeline.kt:1623`. The call site unions this with
     * `catalogsMap.values.flatMap { ... }.map { ... }.toSet()` to get the
     * full active-keys set.
     */
    fun activeItemKeys(): Set<String> {
        val current = _inventory.value
        val out = HashSet<String>()
        for ((_, row) in current) {
            for (i in row.items.indices) {
                val item = row.items[i]
                out += "${item.apiType}:${item.id}"
            }
        }
        return out
    }

    /**
     * Replace the inventory atomically. Built as `LinkedHashMap` so insertion
     * order is preserved (matches the prior `List<CatalogRow>` ordering used
     * by upstream). Rows with any blank component of the triple key are
     * skipped defensively — pipeline shouldn't produce these but the gate
     * prevents silent corruption.
     *
     * `@Synchronized` atomicizes build + StateFlow assignment from the writer
     * side; `_inventory.value`'s volatile semantics guarantee readers never
     * see a torn map.
     */
    @Synchronized
    fun publish(rows: List<CatalogRow>) {
        val next = LinkedHashMap<String, CatalogRow>(rows.size)
        // Indexed-for: avoids Iterable iterator allocation (CLAUDE.md rule #4).
        for (i in rows.indices) {
            val row = rows[i]
            if (row.addonId.isBlank() || row.apiType.isBlank() || row.catalogId.isBlank()) continue
            next["${row.addonId}_${row.apiType}_${row.catalogId}"] = row
        }
        _inventory.value = next
    }

    /** Reset path for profile switch / sign-out. */
    fun clear() {
        _inventory.value = emptyMap()
    }
}
```

- [ ] **Step 4: Run the tests — all 12 should pass**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.repository.CatalogInventoryRepositoryTest"
```
Expected: BUILD SUCCESSFUL with 12 tests passed.

If a test fails, read the failure carefully. The most likely sources of failure: ordering test (use `LinkedHashMap`, not `HashMap`); concurrent test (verify `@Synchronized`); `observeRail` distinct test (the second `publish(listOf(r1))` produces a fresh `LinkedHashMap` reference but the rail's `CatalogRow` reference is the same — `distinctUntilChanged` on the rail value works because `r1 === r1`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/CatalogInventoryRepository.kt \
        app/src/test/java/com/nexio/tv/data/repository/CatalogInventoryRepositoryTest.kt
git commit -m "feat(repo): introduce CatalogInventoryRepository

Owns the catalog inventory (addonId_apiType_catalogId-keyed Map) that
HomeViewModel._fullCatalogRows: StateFlow<List<CatalogRow>> currently
holds. New repo exposes typed accessors: snapshot(), observeRail(key),
isEmpty(), activeItemKeys(), publish(rows), clear(). Push model — the
pipeline keeps computing the inventory and calls publish() each emission.

12-case unit test covers publish/snapshot, ordering, key collision,
blank-component skip, isEmpty lifecycle, observeRail filtering and null
on missing/removed, clear, activeItemKeys aggregation, concurrent
publish/snapshot non-tearing.

Spec: docs/superpowers/specs/2026-05-10-catalog-inventory-repository-design.md"
```

---

## Task 2: Inject `CatalogInventoryRepository` into `HomeViewModel` + add façade method

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`

This task wires the new repository through Hilt. We do NOT yet remove the old `_fullCatalogRows` field — that happens in Task 6 after all consumers have migrated. The two flows run in parallel during Tasks 3-5.

- [ ] **Step 1: Add the constructor parameter + import**

Open `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`. Find the existing import block of `data.repository.*` types. Add this import alongside the others (alphabetical order):

```kotlin
import com.nexio.tv.data.repository.CatalogInventoryRepository
```

Find the `HomeViewModel` constructor parameter list — it's a long `@Inject constructor(...)` with many dependencies. Add `private val catalogInventoryRepository: CatalogInventoryRepository` to the parameter list (place near other `data.repository.*` injections — `MetadataDisplayRepository` or similar — for locality). Mind the trailing comma on the previous parameter.

- [ ] **Step 2: Add the façade method on `HomeViewModel`**

Locate the existing `_fullCatalogRows`/`fullCatalogRows` block (around line 200). Right after the `val displayCatalogRows: StateFlow<List<CatalogRow>>` declaration, add:

```kotlin
/**
 * Single-rail observation for `CatalogSeeAllScreen`. Delegates to
 * [CatalogInventoryRepository.observeRail] so the SeeAll screen does NOT
 * subscribe to the full inventory StateFlow — recomposes only when the
 * specific rail's content changes.
 *
 * Spec: docs/superpowers/specs/2026-05-10-catalog-inventory-repository-design.md
 */
fun observeCatalogRail(key: String): kotlinx.coroutines.flow.Flow<com.nexio.tv.domain.model.CatalogRow?> =
    catalogInventoryRepository.observeRail(key)
```

(If `Flow` and `CatalogRow` are already imported in this file, use the unqualified names. The fully-qualified spelling above is safe regardless.)

- [ ] **Step 3: Build to verify Hilt graph + compilation**

```bash
./gradlew :app:assembleUniversalDebug
```
Expected: BUILD SUCCESSFUL. If Hilt complains about an unsatisfied dependency, verify `CatalogInventoryRepository` has `@Singleton` + `@Inject constructor()` from Task 1. The empty constructor is sufficient — Hilt will provide it without a separate module.

- [ ] **Step 4: Run home test suite to verify no regression**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.home.*"
```
Expected: same outcome as before this task (the 5 known pre-existing failures from baseline `026025bdf` remain; no new failures). If any test now fails to compile because it constructs `HomeViewModel` directly, add a `CatalogInventoryRepository()` arg to the constructor call. Defer migrating mock-based tests — they go in Task 6.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt
git commit -m "feat(home-vm): inject CatalogInventoryRepository + add observeCatalogRail facade

Wire the new repository through Hilt. _fullCatalogRows / fullCatalogRows
fields are NOT yet removed — Tasks 3-5 migrate consumers; Task 6 deletes
the old fields. Both flows run in parallel during the migration.

The observeCatalogRail(key) facade preserves CatalogSeeAllScreen's
existing 'inject HomeViewModel via hiltViewModel()' wiring and avoids
introducing a new ViewModel class for the migration."
```

---

## Task 3: Pipeline writes to BOTH old field + new repository (dual-write)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:3126`

This is the single writer site. The change is a one-line addition: keep the old assignment AND add `catalogInventoryRepository.publish(...)`. After this task the new repo is populated correctly; reads still go to the old field.

- [ ] **Step 1: Add the dual-write at the writer site**

Open `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`. Locate line 3126:

```kotlin
_fullCatalogRows.value = composedSnapshot.fullRows
```

Replace with:

```kotlin
// Plan-C migration (spec 2026-05-10-catalog-inventory-repository-design):
// Dual-write while consumers migrate. _fullCatalogRows stays in place
// until Task 6 deletes it; reads switch to inventoryRepository in Task 4.
_fullCatalogRows.value = composedSnapshot.fullRows
catalogInventoryRepository.publish(composedSnapshot.fullRows)
```

This file is a top-level extension on `HomeViewModel`, so `catalogInventoryRepository` is reachable via the receiver — no new parameter needed.

- [ ] **Step 2: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Sanity-check with home tests**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.home.*"
```
Expected: same outcome as Task 2 — no new failures.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
git commit -m "feat(home-pipeline): dual-write inventory to CatalogInventoryRepository

applyHomeSnapshotToUiPipeline writes composedSnapshot.fullRows to BOTH
_fullCatalogRows (legacy) and catalogInventoryRepository.publish (new).
Tasks 4-5 migrate read sites; Task 6 deletes the legacy field. During
this stage both flows are coherent because they're written in the same
critical section."
```

---

## Task 4: Migrate the four pipeline read sites + two reset sites to the repository

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`

After this task, the only remaining external consumer of `_fullCatalogRows` / `fullCatalogRows` is `CatalogSeeAllScreen`. The dual-write from Task 3 stays — reads now resolve to the repo.

- [ ] **Step 1: Migrate the `rawFirstPaintBatchActive` read at line 1477**

Locate line 1477 inside the `runSerializedPostStartupRefreshPipeline` body:

```kotlin
var rawFirstPaintBatchActive = _fullCatalogRows.value.isEmpty()
```

Replace with:

```kotlin
var rawFirstPaintBatchActive = catalogInventoryRepository.isEmpty()
```

- [ ] **Step 2: Migrate the `activeCatalogItemKeys` build at line 1623**

Locate the inline build:

```kotlin
val activeCatalogItemKeys = (_fullCatalogRows.value.asSequence() + catalogsMap.values.asSequence())
    .flatMap { row -> row.items.asSequence() }
    .map { item -> "${item.apiType}:${item.id}" }
    .toSet()
```

Replace with:

```kotlin
// Inventory contribution from the repository; union with the live
// catalogsMap (which holds rails not yet committed to the inventory
// snapshot at this point in the refresh pipeline).
val activeCatalogItemKeys = catalogInventoryRepository.activeItemKeys() +
    catalogsMap.values.asSequence()
        .flatMap { row -> row.items.asSequence() }
        .map { item -> "${item.apiType}:${item.id}" }
        .toSet()
```

(`Set<String> + Set<String>` returns a new `Set` — semantics preserved.)

- [ ] **Step 3: Migrate the `cachedFullRows` read inside `withContext` at line ~2792**

Locate the snapshot-at-use-site read inside `updateCatalogRowsPipeline`'s `withContext(Dispatchers.Default)` block. The KDoc above it explains the use-site read is intentional. Replace:

```kotlin
val cachedFullRows = _fullCatalogRows.value
```

with:

```kotlin
// Snapshot.values is a view backed by the underlying LinkedHashMap;
// .toList() materializes it once into the list shape that
// mergeCachedRowsWithLiveRows expects. The snapshot-at-use-site
// semantics described in the KDoc above are preserved.
val cachedFullRows = catalogInventoryRepository.snapshot().values.toList()
```

- [ ] **Step 4: Migrate the two reset sites (lines 281 and ~2201)**

Locate line 281 (cold-reset path):

```kotlin
_fullCatalogRows.value = emptyList()
```

Replace with:

```kotlin
_fullCatalogRows.value = emptyList()  // legacy, removed in Task 6
catalogInventoryRepository.clear()
```

Locate line ~2201 (`catalogsMap.clear()` neighbourhood):

```kotlin
_fullCatalogRows.value = emptyList()
```

Replace with the same dual-clear pattern:

```kotlin
_fullCatalogRows.value = emptyList()  // legacy, removed in Task 6
catalogInventoryRepository.clear()
```

(After Task 6 the legacy lines disappear; for now keeping both keeps the dual-flow coherent.)

- [ ] **Step 5: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run the home test suite**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.home.*"
```
Expected: same outcome as Task 2 — pre-existing failures only. The dual-write from Task 3 keeps `_fullCatalogRows.value` consistent with the repo, so any test that observes it still sees the same content.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
git commit -m "feat(home-pipeline): migrate four pipeline reads to CatalogInventoryRepository

- rawFirstPaintBatchActive (line 1477): inventoryRepository.isEmpty()
- activeCatalogItemKeys (line 1623): inventoryRepository.activeItemKeys() unioned with the live catalogsMap contribution
- cachedFullRows (line ~2792): inventoryRepository.snapshot().values.toList(); KDoc'd snapshot-at-use-site semantics preserved
- two reset sites (281, ~2201): dual-clear (legacy + repo)

After this task the only external consumer of _fullCatalogRows is
CatalogSeeAllScreen — that migrates in Task 5; the legacy field is
deleted in Task 6."
```

---

## Task 5: Migrate `CatalogSeeAllScreen` to `observeCatalogRail`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/CatalogSeeAllScreen.kt`

- [ ] **Step 1: Replace the `fullCatalogRows.find { ... }` block**

Open `app/src/main/java/com/nexio/tv/ui/screens/CatalogSeeAllScreen.kt`. Find lines 68-81 (the block that collects `fullCatalogRows` and does `.find`):

```kotlin
val fullCatalogRows by viewModel.fullCatalogRows.collectAsState()
// ... up to where catalogRow is computed:
val catalogKey = "${addonId}_${type}_${catalogId}"
val catalogRow = fullCatalogRows.find {
    "${it.addonId}_${it.apiType}_${it.catalogId}" == catalogKey
}
```

Replace with:

```kotlin
// Catalog key matches CatalogInventoryRepository's keying convention
// (addonId_apiType_catalogId). observeCatalogRail returns the rail
// when present, null when absent, distinct-until-rail-content-changes —
// SeeAll no longer recomposes on every full-inventory emission.
val catalogKey = "${addonId}_${type}_${catalogId}"
val catalogRow by viewModel.observeCatalogRail(catalogKey).collectAsState(initial = null)
```

If the original code declared `val fullCatalogRows by ...` and used it elsewhere on the screen, search the file for any other reference to `fullCatalogRows` and remove them. Most likely there are none — the variable was only used for the `.find`.

- [ ] **Step 2: Add the import for `collectAsState` if not already present**

Check the top of `CatalogSeeAllScreen.kt` imports. If `androidx.compose.runtime.collectAsState` is already imported (it should be — used elsewhere in the same file), no change needed. The `Flow.collectAsState` extension is the same one used by the other `viewModel.X.collectAsState()` calls.

- [ ] **Step 3: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Build full APK to verify the screen still links**

```bash
./gradlew :app:assembleUniversalDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/CatalogSeeAllScreen.kt
git commit -m "feat(see-all): observe single rail via CatalogInventoryRepository

Replaces fullCatalogRows.collectAsState() + .find { ... } with
viewModel.observeCatalogRail(catalogKey).collectAsState(initial = null).
SeeAll now recomposes only when the visible rail's content changes,
not on every Modern Home pipeline tick.

After this task no production code reads HomeViewModel.fullCatalogRows;
Task 6 deletes the legacy field."
```

---

## Task 6: Delete `_fullCatalogRows` / `fullCatalogRows` + migrate test mocks

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Modify: any test file under `app/src/test/` that references `_fullCatalogRows` or `fullCatalogRows`

This is the final cutover. Tests must be migrated FIRST (otherwise they fail to compile after the field is deleted).

- [ ] **Step 1: Find every test reference**

```bash
grep -rln "_fullCatalogRows\|\.fullCatalogRows" app/src/test --include="*.kt"
```

For each match, open the file and assess: is it asserting against the StateFlow's value, mocking the property, or calling a constructor of `HomeViewModel` that requires the field? Migration patterns:

- **Test reads `viewModel.fullCatalogRows.value` to assert state**: replace with `viewModel.<some test seam>.snapshot()` if the test was checking the inventory contents — but these reads are usually about the COMPUTED inventory after a pipeline tick. Alternative: inject a real `CatalogInventoryRepository` instance and assert against `repo.snapshot()`.
- **Test mocks the field via MockK**: replace with a real `CatalogInventoryRepository()` instance passed to the SUT.
- **Test constructs `HomeViewModel` directly**: add a `CatalogInventoryRepository()` arg to the constructor call (the repo's @Inject constructor takes no args).

For each test file matched, apply the appropriate migration. Recompile after each file edit.

- [ ] **Step 2: Verify tests compile**

```bash
./gradlew :app:compileUniversalDebugUnitTestKotlin
```
Expected: BUILD SUCCESSFUL. If still failing, finish migrating any remaining test references.

- [ ] **Step 3: Run home + repository tests to verify behavior**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.ui.screens.home.*" \
  --tests "com.nexio.tv.data.repository.CatalogInventoryRepositoryTest"
```
Expected: same pre-existing failures from baseline `026025bdf` (5 known); no new failures. If a test fails because it was asserting a property that's now sourced from the repo, fix the test to read from the repo.

- [ ] **Step 4: Delete the legacy fields from `HomeViewModel`**

Open `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`. Find the two-line block at line 200-201:

```kotlin
internal val _fullCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
val fullCatalogRows: StateFlow<List<CatalogRow>> = _fullCatalogRows.asStateFlow()
```

Delete both lines. Leave the surrounding comment block (about Plan B small Task 26 / SlotTable retention) intact — it documents the OTHER fields (`_displayCatalogRows`, `_displayHeroItems`).

- [ ] **Step 5: Delete the legacy writes from `HomeViewModelCatalogPipeline`**

Open `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`. Remove every remaining reference to `_fullCatalogRows`:

- Line 281 area: delete the `_fullCatalogRows.value = emptyList()  // legacy, removed in Task 6` line; keep the `catalogInventoryRepository.clear()` line.
- Line ~2201 area: same.
- Line 3126 area: delete the `_fullCatalogRows.value = composedSnapshot.fullRows` line; keep the `catalogInventoryRepository.publish(...)` line. Also remove the "Plan-C migration / Dual-write while consumers migrate" comment block — we're past that stage.

Verify with grep:

```bash
grep -n "_fullCatalogRows\|fullCatalogRows" app/src/main
```
Expected: empty output.

- [ ] **Step 6: Compile and re-run tests**

```bash
./gradlew :app:assembleUniversalDebug
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.home.*" --tests "com.nexio.tv.data.repository.CatalogInventoryRepositoryTest"
```
Expected: BUILD SUCCESSFUL on both. Same pre-existing test failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt \
        app/src/test
git commit -m "refactor(home-vm): delete _fullCatalogRows; CatalogInventoryRepository is sole source

Final cutover. After Tasks 3-5 migrated all readers (4 internal pipeline
sites + CatalogSeeAllScreen) and Task 6 Step 1 migrated all test mocks,
the legacy MutableStateFlow<List<CatalogRow>> on HomeViewModel is no
longer reached. Delete the field and its dual-write call sites.

HomeViewModel's dominator subtree no longer carries the inventory's
17-28 MiB at peak; CatalogInventoryRepository owns it as a separate
@Singleton dominator."
```

---

## Task 7: Build, install, on-device acceptance

**Files:** none modified. This is verification only.

- [ ] **Step 1: Full build**

```bash
./gradlew :app:assembleUniversalDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Install + restart**

```bash
adb -s 192.168.50.98:5555 install -r app/build/outputs/apk/universal/debug/app-universal-debug.apk
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
sleep 1
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 3
PID=$(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv)
echo "Live PID: $PID"
```

- [ ] **Step 3: SeeAll smoke**

Manually on device: open Settings → Home → tap a "See all" link on any rail (Trakt Trending / TMDB Popular / an addon catalog). Verify:
- Grid renders with the same item set as before this change.
- Scrolling near the bottom triggers load-more (the existing `LaunchedEffect(catalogRow?.items?.size)` is unchanged).
- Tapping an item navigates to detail.
- Backing out and re-entering SeeAll for the same rail reuses the cached state.

- [ ] **Step 4: Modern Home cold-start parity**

Force-stop the app and re-launch. Watch Modern Home boot:
- Rails appear in the same order as before.
- No orphaned empty rails.
- Hero, Continue Watching (if applicable), and screensaver candidates populate normally.

- [ ] **Step 5: Profile-switch parity**

If multiple profiles are configured: switch profile via Settings → Profiles. Verify the inventory clears and repopulates from the new profile's pipeline. The `clear()` paths from Task 4 are exercised here.

- [ ] **Step 6: Heap-dump perf gate**

```bash
PID=$(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv)
adb -s 192.168.50.98:5555 shell rm -f /data/local/tmp/heap-inventory-repo.hprof
adb -s 192.168.50.98:5555 shell am dumpheap "$PID" /data/local/tmp/heap-inventory-repo.hprof
sleep 30  # let the dump complete; the file grows incrementally
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap-inventory-repo.hprof /tmp/heap-inventory-repo.hprof
heaptrail -i /tmp/heap-inventory-repo.hprof --leak-suspects --retained-size --exclude-soft-weak --preview-bytes 16384 -t 10 | head -40
```

Expected:
- No suspect references `HomeViewModel._fullCatalogRows`-shaped path (the field is gone).
- A new dominator may appear: `CatalogInventoryRepository` retaining the inventory map (~17-28 MiB during peak Modern Home use). This is moved memory, not new memory — `HomeViewModel`'s retained subtree shrinks by a corresponding amount.
- The `filterDisabledHomeCatalogRows` fix from commit `da4706f0f` keeps the GC pattern at 27-42 MB / 0.6-0.7s with sub-millisecond concurrent pauses. No frame skips.

- [ ] **Step 7: SeeAll recomposition test (optional)**

While SeeAll is on screen for one rail, force a Modern Home refresh in the background (e.g. trigger a network refresh, or wait for the periodic refresh tick). SeeAll should NOT recompose unless its specific rail's content changed. Compare `Choreographer.Skipped` / observable jank before vs after — should be unchanged or improved.

- [ ] **Step 8: Push**

```bash
git push origin main
```

- [ ] **Step 9: Update Plan B notes**

Append a short entry to the top of `docs/superpowers/plans/2026-05-09-resolved-display-ui-consumption-migration.md`'s status section noting that the Catalog Inventory Repository (this plan) shipped between Surface 3 and Surface 4. Resume Plan B Task 17 (Continue Watching) afterwards.

```bash
# Manually edit the plan's "Session status" header section to add:
#   - 2026-05-10: CatalogInventoryRepository extracted from HomeViewModel
#     (commits: see this plan). Surface 4 (CW) starts on top of this shape.
```

Commit:

```bash
git add docs/superpowers/plans/2026-05-09-resolved-display-ui-consumption-migration.md
git commit -m "docs(plan-b): note CatalogInventoryRepository landed between Surface 3 and 4"
git push origin main
```

---

## Self-Review

**Spec coverage** — every requirement in `2026-05-10-catalog-inventory-repository-design.md` maps to a task:
- §Components / `CatalogInventoryRepository` → Task 1
- §Components / `HomeViewModel` façade + injection → Task 2
- §Components / 5 pipeline site replacements → Tasks 3 (writer) + 4 (reads + resets)
- §Components / `CatalogSeeAllScreen` → Task 5
- §Components / "Delete the legacy fields" → Task 6
- §Testing / 12 unit tests → Task 1 Step 1
- §Testing / integration tests migration → Task 6 Step 1
- §Testing / on-device gates → Task 7
- §Sequencing → header + Task 7 Step 9

**Placeholder scan** — none. Every step has either exact code, an exact command + expected output, or a concrete file path + line range.

**Type consistency** — `CatalogInventoryRepository` exposes `inventory: StateFlow<Map<String, CatalogRow>>`, `snapshot(): Map<String, CatalogRow>`, `observeRail(key: String): Flow<CatalogRow?>`, `isEmpty(): Boolean`, `activeItemKeys(): Set<String>`, `publish(rows: List<CatalogRow>)`, `clear()`. The façade method on `HomeViewModel` is `observeCatalogRail(key: String): Flow<CatalogRow?>` matching the underlying repo signature. Pipeline migration calls match the exact method names.

**Open considerations not blocking the plan** (carry as follow-ups):
- A `CatalogInventoryMemo` that interns the LinkedHashMap reference when content is unchanged across publishes. Spec §Reference stability called this out as deferred.
- `CatalogSeeAllViewModel` (dedicated VM for SeeAll) if the screen grows independent state. For now, the façade keeps `hiltViewModel<HomeViewModel>()` working.
