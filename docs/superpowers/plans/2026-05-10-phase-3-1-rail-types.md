# Phase 3.1 — `RailItemKey` + `Rail` Structural Types

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Define `RailItemKey` and `Rail` typed structural shapes alongside the existing `CatalogRow`. Foundation only — no production wiring, no consumer or producer migrations. Subsequent sub-projects (3.2-3.6) use these types.

**Architecture:** `Rail` mirrors `CatalogRow`'s structure (addon/catalog metadata, pagination, loading state) but replaces `items: List<MetaPreview>` with `items: List<RailItemKey>`. The authority-owned-item-data model: rail carries structure + ordered keys; item content lives in `ResolvedDisplaySurfaceRepository`. `RailItemKey(apiType: String, contentId: String)` derives the authority lookup key via `homeDisplayItemKey(apiType, contentId)`.

**Tech Stack:** Kotlin · Compose `@Immutable` · existing `homeDisplayItemKey` helper at `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt:216` · JUnit4.

**Spec source:** `docs/superpowers/specs/2026-05-10-phase-3-catalog-pipeline-restructure-design.md` — sub-project 3.1.

---

## File Structure

### New files

| File | Responsibility |
|---|---|
| `app/src/main/java/com/nexio/tv/domain/model/RailItemKey.kt` | Minimal opaque key (`apiType`, `contentId`) that derives authority lookup via `homeDisplayItemKey()`. |
| `app/src/main/java/com/nexio/tv/domain/model/Rail.kt` | Structure-only rail: addon/catalog metadata + ordered `List<RailItemKey>`. Same shape as `CatalogRow` minus item content. |
| `app/src/test/java/com/nexio/tv/domain/model/RailItemKeyTest.kt` | Type equality, key derivation, data-class semantics. |
| `app/src/test/java/com/nexio/tv/domain/model/RailTest.kt` | Type equality, derived properties (apiType from ContentType + rawType), data-class semantics. |

### Untouched

- `CatalogRow.kt` stays unmodified. Phase 3.2-3.5 wire `Rail` into consumers. Phase 3.6 flips the producer. Phase 3.9 retires `CatalogRow` if/when no longer needed.

---

## Task 1: Define `RailItemKey`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/domain/model/RailItemKey.kt`
- Create: `app/src/test/java/com/nexio/tv/domain/model/RailItemKeyTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/domain/model/RailItemKeyTest.kt`:

```kotlin
package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RailItemKeyTest {

    @Test
    fun `key derives from apiType and contentId via homeDisplayItemKey`() {
        val key = RailItemKey(apiType = "movie", contentId = "tt0111161")
        assertEquals(homeDisplayItemKey("movie", "tt0111161"), key.key)
    }

    @Test
    fun `equality uses apiType + contentId`() {
        val a = RailItemKey(apiType = "movie", contentId = "tt1")
        val b = RailItemKey(apiType = "movie", contentId = "tt1")
        val c = RailItemKey(apiType = "series", contentId = "tt1")
        val d = RailItemKey(apiType = "movie", contentId = "tt2")
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(a, d)
    }

    @Test
    fun `hashCode is stable across instances with equal fields`() {
        val a = RailItemKey(apiType = "movie", contentId = "tt1")
        val b = RailItemKey(apiType = "movie", contentId = "tt1")
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy preserves field semantics`() {
        val original = RailItemKey(apiType = "movie", contentId = "tt1")
        val updated = original.copy(contentId = "tt2")
        assertEquals("movie", updated.apiType)
        assertEquals("tt2", updated.contentId)
        assertEquals(homeDisplayItemKey("movie", "tt2"), updated.key)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.domain.model.RailItemKeyTest" 2>&1 | tail -10
```

Expected: BUILD FAILED — `Unresolved reference: RailItemKey`.

- [ ] **Step 3: Create the type**

Create `app/src/main/java/com/nexio/tv/domain/model/RailItemKey.kt`:

```kotlin
package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Opaque key for looking up a resolved item in
 * [com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository]. Carries
 * just enough to derive the authority lookup key via [homeDisplayItemKey]
 * — no item content. The authority owns item content; rails carry only
 * structure + ordered keys.
 *
 * Plan B Phase 3.1 of the home-MetaPreview-elimination spec
 * (`docs/superpowers/specs/2026-05-10-phase-3-catalog-pipeline-restructure-design.md`).
 */
@Immutable
data class RailItemKey(
    val apiType: String,
    val contentId: String
) {
    /** Authority lookup key. Equivalent to `homeDisplayItemKey(apiType, contentId)`. */
    val key: String get() = homeDisplayItemKey(apiType, contentId)
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.domain.model.RailItemKeyTest" 2>&1 | tail -10
```

Expected: 4 tests, 0 failures.

- [ ] **Step 5: Commit (Task 1)**

EXPLICITLY stage. DO NOT use `git add -A` (CLAUDE.md hard rule #7, commit `90e8ccb27`). Working tree has uncommitted other-workstream files — DO NOT touch them.

```bash
git add app/src/main/java/com/nexio/tv/domain/model/RailItemKey.kt \
        app/src/test/java/com/nexio/tv/domain/model/RailItemKeyTest.kt
git status -sb  # verify only the 2 intended files staged
git diff --cached --stat
git commit -m "feat(domain): RailItemKey — opaque authority lookup key

Phase 3.1 of the home-MetaPreview-elimination spec (commit fa05a1fe5).
Defines the minimal opaque key (apiType + contentId) that derives the
authority lookup via homeDisplayItemKey. Foundation type — no production
wiring yet. Used by sub-projects 3.2-3.6 to flip consumers and producer
to authority-owned item data.

The architecture goal: rails carry structure + ordered keys; item
content lives in ResolvedDisplaySurfaceRepository. RailItemKey is the
bridge between the two."
```

---

## Task 2: Define `Rail`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/domain/model/Rail.kt`
- Create: `app/src/test/java/com/nexio/tv/domain/model/RailTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/domain/model/RailTest.kt`:

```kotlin
package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RailTest {

    @Test
    fun `apiType derives from ContentType and rawType`() {
        val rail = sampleRail(type = ContentType.MOVIE, rawType = "movie")
        assertEquals(ContentType.MOVIE.toApiString("movie"), rail.apiType)
    }

    @Test
    fun `apiType uses rawType passthrough when ContentType yields it`() {
        // Mirrors CatalogRow's apiType getter (type.toApiString(rawType)).
        val rail = sampleRail(type = ContentType.SERIES, rawType = "tv")
        assertEquals(ContentType.SERIES.toApiString("tv"), rail.apiType)
    }

    @Test
    fun `equality uses all structural fields`() {
        val a = sampleRail(catalogId = "cat1")
        val b = sampleRail(catalogId = "cat1")
        val c = sampleRail(catalogId = "cat2")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `items is List of RailItemKey`() {
        val keys = listOf(
            RailItemKey(apiType = "movie", contentId = "tt1"),
            RailItemKey(apiType = "movie", contentId = "tt2")
        )
        val rail = sampleRail(items = keys)
        assertEquals(2, rail.items.size)
        assertEquals(keys, rail.items)
    }

    @Test
    fun `default flags mirror CatalogRow defaults`() {
        val rail = sampleRail()
        assertEquals(false, rail.isLoading)
        assertEquals(true, rail.hasMore)
        assertEquals(0, rail.currentPage)
        assertEquals(false, rail.supportsSkip)
        assertEquals(100, rail.skipStep)
    }

    private fun sampleRail(
        addonId: String = "addon1",
        addonName: String = "Addon",
        addonBaseUrl: String = "https://addon.example.com",
        catalogId: String = "cat1",
        catalogName: String = "Trending",
        type: ContentType = ContentType.MOVIE,
        rawType: String = type.toApiString(),
        items: List<RailItemKey> = emptyList()
    ): Rail = Rail(
        addonId = addonId,
        addonName = addonName,
        addonBaseUrl = addonBaseUrl,
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        rawType = rawType,
        items = items
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.domain.model.RailTest" 2>&1 | tail -10
```

Expected: BUILD FAILED — `Unresolved reference: Rail`.

- [ ] **Step 3: Create the type**

Create `app/src/main/java/com/nexio/tv/domain/model/Rail.kt`:

```kotlin
package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Structure-only rail: addon/catalog metadata, ordered list of opaque
 * [RailItemKey]s, pagination + loading state. Mirrors [CatalogRow]'s
 * structural fields exactly, but the `items` field carries only KEYS
 * — item content lives in
 * [com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository] and
 * is looked up by [RailItemKey.key].
 *
 * Plan B Phase 3.1 of the home-MetaPreview-elimination spec
 * (`docs/superpowers/specs/2026-05-10-phase-3-catalog-pipeline-restructure-design.md`).
 * Foundation type — no production wiring yet. Sub-projects 3.2-3.6 wire
 * this into consumers and producer; sub-project 3.9 retires the legacy
 * [CatalogRow] if/when no consumer still needs it.
 */
@Immutable
data class Rail(
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val items: List<RailItemKey>,
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 0,
    val supportsSkip: Boolean = false,
    val skipStep: Int = 100
) {
    val apiType: String
        get() = type.toApiString(rawType)
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.domain.model.RailTest" 2>&1 | tail -10
```

Expected: 5 tests, 0 failures.

- [ ] **Step 5: Compile production**

```bash
./gradlew :app:compileUniversalDebugKotlin 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit (Task 2)**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/Rail.kt \
        app/src/test/java/com/nexio/tv/domain/model/RailTest.kt
git status -sb
git diff --cached --stat
git commit -m "feat(domain): Rail — structure-only typed rail

Phase 3.1 of the home-MetaPreview-elimination spec (commit fa05a1fe5).
Mirrors CatalogRow's structural fields (addon/catalog metadata,
pagination, loading state) but the items field carries only typed
RailItemKey instances — item content lives in
ResolvedDisplaySurfaceRepository and is looked up by key.

Foundation type — no production wiring yet. Sub-projects 3.2-3.5
wire Rail into consumers (resolved projections, screensaver bulk
publication); sub-project 3.6 flips the producer pipeline to emit
Rail directly from the catalog pipeline; sub-project 3.9 retires
the legacy CatalogRow if/when no consumer still needs it."
```

---

## Self-review

**1. Spec coverage:**

Phase 3.1 spec text: "New type `RailItemKey(apiType: String, contentId: String)` — looks up in authority via `homeDisplayItemKey(apiType, contentId)`. New type `Rail` (or rename existing `CatalogRow` carefully) carrying ONLY structure: `catalogId`, `addonId`, `apiType`, `title`, `items: List<RailItemKey>`. Unit tests for type equality, key derivation. No production wiring changes yet; just the types exist."

Tasks 1 + 2 implement exactly that. Chose the "new type alongside" approach over "rename CatalogRow" — less disruptive, lets the migration go consumer-by-consumer in 3.2-3.5 before flipping the producer in 3.6.

**Note on field naming:** The spec sketch said `title` but `CatalogRow`'s actual field is `catalogName` (the rail's display title). `Rail` mirrors that as `catalogName` — matches the existing pattern.

**2. Placeholder scan:** None. Each step has exact code, exact commands, exact expected output.

**3. Type consistency:**
- `homeDisplayItemKey(contentType: String, contentId: String): String` — defined at `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt:216`. Used in `RailItemKey.key` getter.
- `ContentType.toApiString(rawType: String): String` — used in `Rail.apiType` getter, mirroring `CatalogRow.apiType`.
- `@Immutable` from `androidx.compose.runtime.Immutable` — same pattern as `CatalogRow`.
- All field names match `CatalogRow`'s structural subset.

No type drift.

**4. Risk:** LOWEST possible. New types, no production wiring, no behavior change. Unit-test-only validation.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-10-phase-3-1-rail-types.md`. Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent per task, two-stage review.
2. **Inline Execution** — execute tasks in this session.

Which approach?
