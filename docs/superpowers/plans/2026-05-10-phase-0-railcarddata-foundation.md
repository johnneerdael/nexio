# Phase 0 — RailCardData Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Define a typed `RailCardData` interface, migrate `GridContentCard` to consume it, and provide a `MetaPreview.toRailCardData()` legacy adapter so all 9 existing call sites keep working with no behavior change. Type-strict slot contract is enforced at the card primitive boundary even before per-surface projections land.

**Architecture:** New interface in `app/src/main/java/com/nexio/tv/ui/components/RailCardData.kt`. Existing typed projections (`ModernHomeRowItem`, `DetailRailItem`) implement it. `GridContentCard`'s parameter type flips from `MetaPreview` to `RailCardData`. Legacy callers wrap with `.toRailCardData()` until their per-surface migration (Phases 1A-G) replaces the wrapper with a typed projection.

**Tech Stack:** Kotlin · Compose · `ArtworkDisplayRef` (existing typed slot type) · JUnit4

**Spec source:** `docs/superpowers/specs/2026-05-10-home-metapreview-elimination-design.md` Section "Phase 0".

---

## File Structure

### New files

| File | Responsibility |
|---|---|
| `app/src/main/java/com/nexio/tv/ui/components/RailCardData.kt` | Interface defining the minimum typed contract `GridContentCard` consumes (`id`, `name`, `posterRef`, `posterProviderTag`); also hosts the `MetaPreview.toRailCardData()` legacy adapter |
| `app/src/test/java/com/nexio/tv/ui/components/RailCardDataTest.kt` | Unit tests for the interface contract + adapter mapping |

### Modified files

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/ui/components/GridContentCard.kt` | Parameter `item: MetaPreview` → `item: RailCardData`. Body reads `item.posterRef`, `item.id`, `item.name`, `item.posterProviderTag` (no `MetaPreview` reference). |
| `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRowItem.kt` | Add `posterProviderTag: String?` field; implement `RailCardData` (override `id` from `contentId`, `name` from `title`). |
| `app/src/main/java/com/nexio/tv/ui/screens/detail/DetailRailItem.kt` | Add `posterProviderTag: String?` field; implement `RailCardData` (override `id` from `contentId`, `name` from `title`). |
| `app/src/main/java/com/nexio/tv/ui/screens/AndroidTvFeedBrowserScreen.kt` | At call site (line ~139): `GridContentCard(item = item.toRailCardData(), ...)`. |
| `app/src/main/java/com/nexio/tv/ui/screens/CatalogSeeAllScreen.kt` | At call site (line ~197): same wrapper change. |
| `app/src/main/java/com/nexio/tv/ui/screens/organization/OrganizationDetailScreen.kt` | At call site (line ~129): same wrapper change. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt` | At call site (line ~343): same wrapper change. |
| `app/src/main/java/com/nexio/tv/ui/screens/library/LibraryScreen.kt` | At call site (line ~344): same wrapper change. |
| `app/src/main/java/com/nexio/tv/ui/screens/cast/CastDetailScreen.kt` | At call site (line ~350): same wrapper change. |
| `app/src/main/java/com/nexio/tv/ui/screens/search/SearchDiscoverSection.kt` | At call site (line ~500): same wrapper change. |
| `app/src/main/java/com/nexio/tv/ui/screens/detail/MoreLikeThisSection.kt` | At call site (line ~90): change from `GridContentCard(item = item.source, ...)` to `GridContentCard(item = item, ...)` (DetailRailItem now implements RailCardData directly). |
| `app/src/main/java/com/nexio/tv/ui/screens/detail/CollectionSection.kt` | At call site (line ~90): same as MoreLikeThisSection. |

---

## Task 1: Define `RailCardData` interface (write failing test first)

**Files:**
- Create: `app/src/test/java/com/nexio/tv/ui/components/RailCardDataTest.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/components/RailCardData.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/ui/components/RailCardDataTest.kt`:

```kotlin
package com.nexio.tv.ui.components

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RailCardDataTest {

    private fun makeRef(value: String) = ArtworkDisplayRef.LegacyString(
        value = value,
        imageType = ArtworkType.POSTER,
        trace = ArtworkTrace.empty()
    )

    private val sample = object : RailCardData {
        override val id: String = "tt12345"
        override val name: String? = "Some Title"
        override val posterRef: ArtworkDisplayRef? = makeRef("https://example.com/poster.jpg")
        override val posterProviderTag: String? = "tmdb"
    }

    @Test
    fun `interface exposes id`() {
        assertEquals("tt12345", sample.id)
    }

    @Test
    fun `interface exposes name as nullable`() {
        assertEquals("Some Title", sample.name)
    }

    @Test
    fun `interface exposes posterRef as nullable ArtworkDisplayRef`() {
        val ref = sample.posterRef
        assertEquals(ArtworkType.POSTER, ref?.imageType)
    }

    @Test
    fun `interface exposes posterProviderTag as nullable`() {
        assertEquals("tmdb", sample.posterProviderTag)
    }

    @Test
    fun `null fields are permitted`() {
        val empty = object : RailCardData {
            override val id: String = "x"
            override val name: String? = null
            override val posterRef: ArtworkDisplayRef? = null
            override val posterProviderTag: String? = null
        }
        assertNull(empty.name)
        assertNull(empty.posterRef)
        assertNull(empty.posterProviderTag)
    }
}
```

- [ ] **Step 2: Run test to verify it fails (compile error)**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.components.RailCardDataTest"`

Expected: BUILD FAILED with `Unresolved reference: RailCardData`.

- [ ] **Step 3: Create the interface**

Create `app/src/main/java/com/nexio/tv/ui/components/RailCardData.kt`:

```kotlin
package com.nexio.tv.ui.components

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.MetaPreview

/**
 * Minimum typed contract that [GridContentCard] consumes. Each surface that
 * renders rail cards provides a typed projection that implements this
 * interface — `ModernHomeRowItem` (home rails / SeeAll / GridHome),
 * `DetailRailItem` (MoreLikeThis / Collection), and per-surface projections
 * to be added by Phases 1A-G of the spec at
 * `docs/superpowers/specs/2026-05-10-home-metapreview-elimination-design.md`.
 *
 * The legacy adapter [toRailCardData] wraps a [MetaPreview] for surfaces that
 * have not migrated to a typed projection yet — it preserves the existing
 * "typed slot first, legacy String second" image-resolution chain inside the
 * single [posterRef] field, so [GridContentCard] only needs the one read path.
 *
 * Per CLAUDE.md hard rule #1, surfaces consume `ResolvedDisplayItem` (or an
 * approved per-surface projection like `ModernHomeRowItem`), never raw
 * `MetaPreview`. Typed `posterRef` is strict POSTER type — no cross-type
 * fallback to backdrop or logo.
 */
@Immutable
interface RailCardData {
    /** Stable content identifier — used for cache keys and focus tracking. */
    val id: String

    /** Display name. May be null when upstream metadata is missing; card
     *  renders an empty label in that case. */
    val name: String?

    /** Typed POSTER artwork slot. Strict POSTER type per rule #1 — never a
     *  backdrop or logo masquerading as a poster. May be null when no
     *  artwork is available; card renders the placeholder in that case. */
    val posterRef: ArtworkDisplayRef?

    /** Optional provider tag for disk-cache differentiation (e.g. distinguishes
     *  TVDB poster from TMDB poster for the same content id). May be null;
     *  card omits the provider component from the disk cache key. */
    val posterProviderTag: String?
}

/**
 * Legacy adapter for surfaces still passing `MetaPreview` directly. Wraps the
 * meta as a `RailCardData` view so [GridContentCard] keeps working without
 * forcing every caller to migrate to a typed projection in the same change.
 *
 * Composes the existing `artwork?.poster ?: legacy String` chain into a single
 * [posterRef] — when the typed `artwork.poster` is set, it wins; otherwise the
 * legacy String becomes a [ArtworkDisplayRef.LegacyString] of POSTER type.
 *
 * This adapter is intended to be removed once every caller has its own typed
 * projection (Phases 1A-G complete).
 */
fun MetaPreview.toRailCardData(): RailCardData = MetaPreviewRailCardAdapter(this)

private class MetaPreviewRailCardAdapter(private val meta: MetaPreview) : RailCardData {
    override val id: String get() = meta.id
    override val name: String get() = meta.name
    override val posterRef: ArtworkDisplayRef? = computePosterRef(meta)
    override val posterProviderTag: String? get() = meta.posterProviderTag
}

private fun computePosterRef(meta: MetaPreview): ArtworkDisplayRef? {
    val typed = meta.artwork?.poster
    if (typed != null) return typed
    val legacy = meta.poster?.takeIf { it.isNotBlank() }
        ?: meta.displayPoster?.takeIf { it.isNotBlank() }
        ?: return null
    return ArtworkDisplayRef.LegacyString(
        value = legacy,
        imageType = ArtworkType.POSTER,
        trace = ArtworkTrace.empty()
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.components.RailCardDataTest"`

Expected: 5 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/components/RailCardData.kt \
        app/src/test/java/com/nexio/tv/ui/components/RailCardDataTest.kt
git commit -m "feat(ui): RailCardData interface + MetaPreview legacy adapter

Phase 0 of the home-MetaPreview-elimination spec (commit c6d885e81).
Defines the minimum typed contract GridContentCard consumes (id, name,
posterRef, posterProviderTag). Existing typed projections will implement
this in subsequent tasks; legacy callers will use the toRailCardData()
adapter until Phases 1A-G migrate them to per-surface projections.

The adapter composes the existing 'typed artwork.poster first, legacy
String second' chain into a single posterRef field so GridContentCard
only needs one read path."
```

---

## Task 2: Add adapter test for `MetaPreview.toRailCardData()`

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/components/RailCardDataTest.kt`

The previous task only tested the interface contract abstractly. This task adds tests for the adapter's mapping correctness.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/java/com/nexio/tv/ui/components/RailCardDataTest.kt` (inside the existing class):

```kotlin
@Test
fun `adapter maps id and name from MetaPreview`() {
    val meta = makeMeta(id = "tt99", name = "Title", poster = "https://x/p.jpg")
    val card = meta.toRailCardData()
    assertEquals("tt99", card.id)
    assertEquals("Title", card.name)
}

@Test
fun `adapter prefers typed artwork poster over legacy String`() {
    val typedRef = makeRef("typed-url")
    val artwork = com.nexio.tv.core.artwork.ArtworkBundle(poster = typedRef)
    val meta = makeMeta(
        id = "tt1",
        name = "x",
        poster = "legacy-url",
        artwork = artwork
    )
    val card = meta.toRailCardData()
    val ref = card.posterRef as ArtworkDisplayRef.LegacyString
    assertEquals("typed-url", ref.value)
}

@Test
fun `adapter falls back to legacy poster String when typed artwork is null`() {
    val meta = makeMeta(id = "tt2", name = "x", poster = "legacy-url", artwork = null)
    val card = meta.toRailCardData()
    val ref = card.posterRef as ArtworkDisplayRef.LegacyString
    assertEquals("legacy-url", ref.value)
    assertEquals(ArtworkType.POSTER, ref.imageType)
}

@Test
fun `adapter returns null posterRef when both typed and legacy are null or blank`() {
    val meta = makeMeta(id = "tt3", name = "x", poster = null, artwork = null)
    val card = meta.toRailCardData()
    assertNull(card.posterRef)
}

@Test
fun `adapter returns null posterRef when legacy String is blank`() {
    val meta = makeMeta(id = "tt3b", name = "x", poster = "   ", artwork = null)
    val card = meta.toRailCardData()
    assertNull(card.posterRef)
}

@Test
fun `adapter passes through posterProviderTag`() {
    val meta = makeMeta(id = "tt4", name = "x", poster = "u", posterProviderTag = "tmdb")
    val card = meta.toRailCardData()
    assertEquals("tmdb", card.posterProviderTag)
}

private fun makeMeta(
    id: String,
    name: String,
    poster: String? = null,
    artwork: com.nexio.tv.core.artwork.ArtworkBundle? = null,
    posterProviderTag: String? = null
): com.nexio.tv.domain.model.MetaPreview = com.nexio.tv.domain.model.MetaPreview(
    id = id,
    type = com.nexio.tv.domain.model.ContentType.MOVIE,
    name = name,
    poster = poster,
    posterShape = com.nexio.tv.domain.model.PosterShape.POSTER,
    background = null,
    logo = null,
    description = null,
    releaseInfo = null,
    imdbRating = null,
    genres = emptyList(),
    artwork = artwork,
    posterProviderTag = posterProviderTag
)
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.components.RailCardDataTest"`

Expected: 11 tests, 0 failures (the 5 from Task 1 + 6 new). If any test fails to compile because `MetaPreview` requires fields not in `makeMeta`, add the missing required parameters with default-ish values (e.g. `firstPaintSource = FirstPaintSource.ADDON_META_PREVIEW`); inspect `app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt` to see which constructor params are required.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/components/RailCardDataTest.kt
git commit -m "test(ui): RailCardData adapter mapping tests

Covers id/name passthrough, typed-artwork-wins-over-legacy-String,
legacy-String fallback, blank/null handling, posterProviderTag
passthrough."
```

---

## Task 3: `ModernHomeRowItem` implements `RailCardData`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRowItem.kt`

`ModernHomeRowItem` is the home-rail typed projection. After this task it implements `RailCardData` so `GridContentCard` accepts it directly. Adds a derived `posterProviderTag` that comes from the typed `posterRef`'s `RuntimeAsset.selectedProvider` when present.

- [ ] **Step 1: Read the current file**

Read `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRowItem.kt` end-to-end (40 lines).

Note: the existing data class has fields `itemKey`, `contentId`, `parentId`, `title: String?`, `year`, `posterRef`, `backdropRef`, `logoRef`, `thumbnailRef`, `rating`, `hydrationState`. `RailCardData` requires `id`, `name`, `posterRef`, `posterProviderTag`.

- [ ] **Step 2: Add a unit test for the new conformance**

Create `app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomeRowItemRailCardDataTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.ui.components.RailCardData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernHomeRowItemRailCardDataTest {

    @Test
    fun `ModernHomeRowItem is a RailCardData`() {
        val item = ModernHomeRowItem(
            itemKey = "movie:tt1",
            contentId = "tt1",
            parentId = "tt1",
            title = "X",
            year = 2024,
            posterRef = null,
            backdropRef = null,
            logoRef = null,
            thumbnailRef = null,
            rating = null,
            hydrationState = HydrationState.PREVIEW_ONLY,
            posterProviderTag = null
        )
        assertTrue(item is RailCardData)
    }

    @Test
    fun `RailCardData id maps to contentId`() {
        val item = sampleItem(contentId = "tt42")
        val card: RailCardData = item
        assertEquals("tt42", card.id)
    }

    @Test
    fun `RailCardData name maps to title`() {
        val item = sampleItem(title = "Hello")
        val card: RailCardData = item
        assertEquals("Hello", card.name)
    }

    @Test
    fun `RailCardData posterProviderTag is derived from posterRef RuntimeAsset selectedProvider`() {
        val ref = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = com.nexio.tv.core.artwork.ArtworkDecisionKey(
                ownerKey = com.nexio.tv.core.artwork.ArtworkOwnerKey("tt1"),
                imageType = ArtworkType.POSTER,
                imageLanguage = "en"
            ),
            assetKey = null,
            imageType = ArtworkType.POSTER,
            selectedProvider = ArtworkProviderId.TMDB,
            sourceRole = ArtworkSourceRole.PRIMARY,
            trace = ArtworkTrace.empty()
        )
        val item = sampleItem(posterRef = ref)
        val card: RailCardData = item
        assertEquals("tmdb", card.posterProviderTag)
    }

    @Test
    fun `RailCardData posterProviderTag is null when posterRef is LegacyString`() {
        val ref = ArtworkDisplayRef.LegacyString(
            value = "x",
            imageType = ArtworkType.POSTER,
            trace = ArtworkTrace.empty()
        )
        val item = sampleItem(posterRef = ref)
        val card: RailCardData = item
        assertNull(card.posterProviderTag)
    }

    private fun sampleItem(
        contentId: String = "tt1",
        title: String? = "x",
        posterRef: ArtworkDisplayRef? = null
    ) = ModernHomeRowItem(
        itemKey = "movie:$contentId",
        contentId = contentId,
        parentId = contentId,
        title = title,
        year = null,
        posterRef = posterRef,
        backdropRef = null,
        logoRef = null,
        thumbnailRef = null,
        rating = null,
        hydrationState = HydrationState.PREVIEW_ONLY,
        posterProviderTag = posterRef?.deriveProviderTag()
    )
}
```

If the `ArtworkDecisionKey`/`ArtworkOwnerKey` constructors do not match what compiles in your branch, simplify the test to use `ArtworkDisplayRef.LegacyString` only and skip the RuntimeAsset case — the LegacyString case is the production-critical one for the legacy adapter; the RuntimeAsset provider derivation can be verified manually against `ArtworkProviderId.TMDB.name.lowercase() == "tmdb"`.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.home.ModernHomeRowItemRailCardDataTest"`

Expected: BUILD FAILED — `ModernHomeRowItem does not implement RailCardData` and `Unresolved reference: posterProviderTag` (the constructor doesn't have that field yet) and `Unresolved reference: deriveProviderTag`.

- [ ] **Step 4: Add the field, conformance, and provider-tag derivation helper**

Replace `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRowItem.kt` with:

```kotlin
package com.nexio.tv.ui.screens.home

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.ui.components.RailCardData

@Immutable
data class ModernHomeRowItem(
    val itemKey: String,
    val contentId: String,
    val parentId: String,
    val title: String?,
    val year: Int?,
    val posterRef: ArtworkDisplayRef?,
    val backdropRef: ArtworkDisplayRef?,
    val logoRef: ArtworkDisplayRef?,
    val thumbnailRef: ArtworkDisplayRef?,
    val rating: TitleRating?,
    val hydrationState: HydrationState,
    override val posterProviderTag: String?
) : RailCardData {
    override val id: String get() = contentId
    override val name: String? get() = title
    // posterRef satisfies the override directly via the data-class property.

    companion object {
        fun from(resolved: ResolvedDisplayItem): ModernHomeRowItem =
            ModernHomeRowItem(
                itemKey = resolved.itemKey,
                contentId = resolved.contentId,
                parentId = resolved.parentId,
                title = resolved.display.title,
                year = resolved.display.year,
                posterRef = resolved.artwork.poster,
                backdropRef = resolved.artwork.backdrop,
                logoRef = resolved.artwork.logo,
                thumbnailRef = resolved.artwork.thumbnail,
                rating = resolved.rating,
                hydrationState = resolved.hydrationState,
                posterProviderTag = resolved.artwork.poster.deriveProviderTag()
            )
    }
}

internal fun ArtworkDisplayRef?.deriveProviderTag(): String? = when (this) {
    is ArtworkDisplayRef.RuntimeAsset -> selectedProvider?.name?.lowercase()
    is ArtworkDisplayRef.LegacyString, is ArtworkDisplayRef.Placeholder, null -> null
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.home.ModernHomeRowItemRailCardDataTest"`

Expected: 5 tests, 0 failures.

- [ ] **Step 6: Compile the rest of the project to catch downstream construction sites**

Run: `./gradlew :app:compileUniversalDebugKotlin`

Expected: BUILD FAILED if any caller constructs `ModernHomeRowItem` with the old field set (missing `posterProviderTag`). If so, fix each construction site to pass `posterProviderTag = posterRef.deriveProviderTag()` (or `null` if the call site has no posterRef yet). Likely call sites: `ResolvedDisplayProjectionCache.projectItem` body, any test fixture builders.

After fixes: `./gradlew :app:compileUniversalDebugKotlin` BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRowItem.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomeRowItemRailCardDataTest.kt
# stage any modified call sites caught by the compile pass
git status
git add -A
git commit -m "feat(home): ModernHomeRowItem implements RailCardData

Phase 0 of the home-MetaPreview-elimination spec (commit c6d885e81).
ModernHomeRowItem.from() now derives posterProviderTag from the typed
posterRef's RuntimeAsset.selectedProvider so the disk-cache key
differentiation behavior (formerly via MetaPreview.posterProviderTag)
survives the migration."
```

---

## Task 4: `DetailRailItem` implements `RailCardData`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/DetailRailItem.kt`

`DetailRailItem` is the detail-rail typed projection (Plan B Surface 5 Task 24, commit `d777c65b3`). After this task it implements `RailCardData`.

- [ ] **Step 1: Read the current file**

Read `app/src/main/java/com/nexio/tv/ui/screens/detail/DetailRailItem.kt` end-to-end. Note that the type embeds `source: MetaPreview` and currently exposes `itemKey`, `contentId`, `title: String`, `year`, `posterRef`, `backdropRef`, `logoRef`, `rating`.

- [ ] **Step 2: Write the failing conformance test**

Create `app/src/test/java/com/nexio/tv/ui/screens/detail/DetailRailItemRailCardDataTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.ui.components.RailCardData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailRailItemRailCardDataTest {

    @Test
    fun `DetailRailItem is a RailCardData`() {
        val item = DetailRailItem.fromMetaPreview(makeMeta(id = "tt1", name = "X"))
        assertTrue(item is RailCardData)
    }

    @Test
    fun `RailCardData id maps to contentId`() {
        val item = DetailRailItem.fromMetaPreview(makeMeta(id = "tt42", name = "x"))
        val card: RailCardData = item
        assertEquals("tt42", card.id)
    }

    @Test
    fun `RailCardData name maps to title`() {
        val item = DetailRailItem.fromMetaPreview(makeMeta(id = "tt1", name = "Hello"))
        val card: RailCardData = item
        assertEquals("Hello", card.name)
    }

    @Test
    fun `RailCardData posterProviderTag passes through from source`() {
        val item = DetailRailItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", posterProviderTag = "tvdb")
        )
        val card: RailCardData = item
        assertEquals("tvdb", card.posterProviderTag)
    }

    private fun makeMeta(
        id: String,
        name: String,
        poster: String? = "https://x/p.jpg",
        posterProviderTag: String? = null
    ) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = name,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        posterProviderTag = posterProviderTag
    )
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.detail.DetailRailItemRailCardDataTest"`

Expected: BUILD FAILED — `DetailRailItem does not implement RailCardData`, and the constructor does not have a `posterProviderTag` field.

- [ ] **Step 4: Update `DetailRailItem` to implement the interface**

Edit `app/src/main/java/com/nexio/tv/ui/screens/detail/DetailRailItem.kt` so that:
1. The class declares `: RailCardData`.
2. Adds `override val posterProviderTag: String?` as a constructor parameter.
3. Adds `override val id: String get() = contentId` and `override val name: String? get() = title`.
4. The `fromMetaPreview` factory passes `posterProviderTag = meta.posterProviderTag`.

Final shape (showing the relevant parts; preserve the existing KDoc, imports, and other companion factories):

```kotlin
@Immutable
internal data class DetailRailItem(
    val itemKey: String,
    val contentId: String,
    val title: String,
    val year: Int?,
    override val posterRef: ArtworkDisplayRef?,
    val backdropRef: ArtworkDisplayRef?,
    val logoRef: ArtworkDisplayRef?,
    val rating: TitleRating?,
    val source: MetaPreview,
    override val posterProviderTag: String?
) : com.nexio.tv.ui.components.RailCardData {
    override val id: String get() = contentId
    override val name: String? get() = title

    companion object {
        fun fromMetaPreview(meta: MetaPreview): DetailRailItem = DetailRailItem(
            itemKey = homeDisplayItemKey(meta.apiType, meta.id),
            contentId = meta.id,
            title = meta.name,
            year = meta.releaseInfo?.split("-")?.firstOrNull()?.toIntOrNull(),
            posterRef = meta.poster.toLegacyRailRefOrNull(ArtworkType.POSTER),
            backdropRef = meta.background.toLegacyRailRefOrNull(ArtworkType.BACKDROP),
            logoRef = meta.logo.toLegacyRailRefOrNull(ArtworkType.LOGO),
            rating = meta.imdbRating?.let { value ->
                TitleRating(value = value.toDouble(), source = meta.ratingSource ?: TitleRatingSource.IMDB)
            },
            source = meta,
            posterProviderTag = meta.posterProviderTag
        )
    }
}
```

(Visibility note: the existing class is `internal data class` per commit `d777c65b3`; the `RailCardData` interface is `public`. Kotlin will accept an `internal` class that implements a `public` interface as long as the class itself isn't exposed beyond the module — it isn't here.)

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.detail.DetailRailItemRailCardDataTest"`

Expected: 4 tests, 0 failures.

- [ ] **Step 6: Compile to catch downstream call sites**

Run: `./gradlew :app:compileUniversalDebugKotlin`

Expected: BUILD SUCCESSFUL. The only `DetailRailItem` constructor caller is `fromMetaPreview`, which Step 4 already updated. If a test fixture constructs `DetailRailItem` directly, it'll fail — fix it by passing `posterProviderTag = null` (or matching the test's intent).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/DetailRailItem.kt \
        app/src/test/java/com/nexio/tv/ui/screens/detail/DetailRailItemRailCardDataTest.kt
git add -A
git commit -m "feat(detail): DetailRailItem implements RailCardData

Phase 0 of the home-MetaPreview-elimination spec (commit c6d885e81).
DetailRailItem.fromMetaPreview now passes posterProviderTag through from
the source MetaPreview so disk-cache differentiation (e.g. TVDB vs TMDB
poster for the same content_id) is preserved when the migration drops
the .source bridge in a later phase."
```

---

## Task 5: Migrate `GridContentCard` signature + all 9 callers (atomic)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/components/GridContentCard.kt`
- Modify: 9 caller files (listed in File Structure section)

This is the load-bearing task. `GridContentCard`'s parameter type changes from `MetaPreview` to `RailCardData` — all 9 callers must update in the same commit because the type change is breaking.

Two kinds of caller migrations:
- **Pass-through callers (7)** — currently pass `item: MetaPreview`; after the change, pass `item.toRailCardData()`.
- **Already-typed callers (2)** — `MoreLikeThisSection` and `CollectionSection` pass `item.source` (where `item: DetailRailItem`); after the change, they pass `item` directly because `DetailRailItem` is now a `RailCardData`.

- [ ] **Step 1: Flip `GridContentCard` signature**

Edit `app/src/main/java/com/nexio/tv/ui/components/GridContentCard.kt`:

1. Change the import line `import com.nexio.tv.domain.model.MetaPreview` → remove (no longer needed in this file).
2. Change the parameter type at line 58: `item: MetaPreview` → `item: RailCardData`.
3. Update the body's reads:
   - Line 149: `val displayPoster = item.displayPoster` → DELETE (no fallback chain in interface; `item.posterRef` is the single source).
   - Line 150-151:
     ```kotlin
     val coilModel = item.artwork?.poster.toCoilModelOrNull()
         ?: displayPoster.toLegacyArtworkCoilModelOrNull("${item.id}:poster", ArtworkType.POSTER)
     ```
     → REPLACE with:
     ```kotlin
     val coilModel = item.posterRef.toCoilModelOrNull()
     ```
     (When the typed `posterRef` is a `LegacyString`, `toCoilModelOrNull()` already calls `toLegacyArtworkCoilModelOrNull(...)` internally — see `app/src/main/java/com/nexio/tv/core/artwork/ArtworkUiModels.kt:5-20`. So the chain collapses without behavior change.)
   - Line 152 `remember(coilModel, requestWidthPx, requestHeightPx, item.id, item.posterProviderTag)` — keep, the keys still resolve via the interface.
   - Line 167 `contentDescription = item.name` — keep (interface exposes `name: String?`; `AsyncImage`'s `contentDescription` accepts null).
   - Line 196 `text = item.name` — change to `text = item.name.orEmpty()` (Text needs non-null; the interface allows null for cases where upstream metadata is absent).

4. Remove the import for `toLegacyArtworkCoilModelOrNull` if no longer used in this file: search for other call sites in the file and only delete if zero remain.

Final relevant section of the file (lines ~148-205, post-edit):

```kotlin
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(cardShape)
            ) {
                val context = LocalContext.current
                val coilModel = item.posterRef.toCoilModelOrNull()
                val imageModel = remember(coilModel, requestWidthPx, requestHeightPx, item.id, item.posterProviderTag) {
                    val modelKey = coilModel?.toString()
                    ImageRequest.Builder(context)
                        .data(coilModel)
                        .crossfade(imageCrossfade)
                        .size(width = requestWidthPx, height = requestHeightPx)
                        .memoryCacheKey("${modelKey}_${requestWidthPx}x${requestHeightPx}")
                        .diskCacheKey(ArtworkImageCacheKeys.poster(item.id, item.posterProviderTag, modelKey))
                        .build()
                }
                if (coilModel == null) {
                    MonochromePosterPlaceholder()
                } else {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                if (isWatched) {
                    // ... unchanged
                }
            }
        }

        if (showLabel) {
            Text(
                text = item.name.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = NexioColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(posterCardStyle.width)
                    .padding(top = 8.dp, start = 2.dp, end = 2.dp)
            )
        }
    }
}
```

- [ ] **Step 2: Update each pass-through caller (7 sites) to wrap MetaPreview**

For each of the 7 files below, change `GridContentCard(item = X, ...)` to `GridContentCard(item = X.toRailCardData(), ...)`. Add the import `import com.nexio.tv.ui.components.toRailCardData` to each file.

**`app/src/main/java/com/nexio/tv/ui/screens/AndroidTvFeedBrowserScreen.kt`** — line ~139:
- Find: `GridContentCard(`
- The next line should be `item = <some MetaPreview expression>,`. Replace `<expr>` with `<expr>.toRailCardData()`.
- Add import.

**`app/src/main/java/com/nexio/tv/ui/screens/CatalogSeeAllScreen.kt`** — line ~197:
- Same pattern.

**`app/src/main/java/com/nexio/tv/ui/screens/organization/OrganizationDetailScreen.kt`** — line ~129:
- Same pattern.

**`app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt`** — line ~343:
- Same pattern.

**`app/src/main/java/com/nexio/tv/ui/screens/library/LibraryScreen.kt`** — line ~344:
- Same pattern.

**`app/src/main/java/com/nexio/tv/ui/screens/cast/CastDetailScreen.kt`** — line ~350:
- Same pattern.

**`app/src/main/java/com/nexio/tv/ui/screens/search/SearchDiscoverSection.kt`** — line ~500:
- Same pattern.

- [ ] **Step 3: Update the 2 already-typed callers to pass the typed projection directly**

**`app/src/main/java/com/nexio/tv/ui/screens/detail/MoreLikeThisSection.kt`** — line ~90:
- Find: `GridContentCard(item = item.source, ...)`.
- Replace: `GridContentCard(item = item, ...)` (because `DetailRailItem` is now a `RailCardData`).
- Also remove the `// TODO(Plan B): remove .source bridge ...` comment that the previous Phase added; it's done now.

**`app/src/main/java/com/nexio/tv/ui/screens/detail/CollectionSection.kt`** — line ~90:
- Same pattern as MoreLikeThisSection.

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileUniversalDebugKotlin`

Expected: BUILD SUCCESSFUL. If failures appear, they'll be either:
- A caller missed in Step 2 — search `GridContentCard(item =` and add the wrapper or typed projection.
- A test calling `GridContentCard(item = someMetaPreview, ...)` — wrap with `.toRailCardData()`.

- [ ] **Step 5: Run all touched test classes**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.components.RailCardDataTest" --tests "com.nexio.tv.ui.screens.home.ModernHomeRowItemRailCardDataTest" --tests "com.nexio.tv.ui.screens.detail.DetailRailItemRailCardDataTest" --tests "com.nexio.tv.ui.screens.detail.MoreLikeThisSection*" --tests "com.nexio.tv.ui.screens.detail.CollectionSection*"`

Expected: all PASS.

- [ ] **Step 6: Build the APK**

Run: `./gradlew :app:assembleUniversalDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Smoke test on device**

```bash
adb -s 192.168.50.98:5555 install -r app/build/outputs/apk/universal/debug/app-universal-debug.apk
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
```

Wait ~30 seconds, then:

```bash
adb -s 192.168.50.98:5555 logcat -d -t 600 | grep -E "FATAL|AndroidRuntime|ANR|ClassCast|NoSuchMethod" | tail -10
```

Expected: empty output.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/components/GridContentCard.kt \
        app/src/main/java/com/nexio/tv/ui/screens/AndroidTvFeedBrowserScreen.kt \
        app/src/main/java/com/nexio/tv/ui/screens/CatalogSeeAllScreen.kt \
        app/src/main/java/com/nexio/tv/ui/screens/organization/OrganizationDetailScreen.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt \
        app/src/main/java/com/nexio/tv/ui/screens/library/LibraryScreen.kt \
        app/src/main/java/com/nexio/tv/ui/screens/cast/CastDetailScreen.kt \
        app/src/main/java/com/nexio/tv/ui/screens/search/SearchDiscoverSection.kt \
        app/src/main/java/com/nexio/tv/ui/screens/detail/MoreLikeThisSection.kt \
        app/src/main/java/com/nexio/tv/ui/screens/detail/CollectionSection.kt
git commit -m "refactor(ui): GridContentCard accepts RailCardData

Phase 0 of the home-MetaPreview-elimination spec (commit c6d885e81).
GridContentCard's item parameter flips from MetaPreview to RailCardData.
The 7 pass-through callers wrap their MetaPreview via the toRailCardData()
adapter; the 2 detail-rails callers (MoreLikeThis, Collection) pass their
DetailRailItem directly now that it implements RailCardData.

The card's body collapses the 'typed artwork.poster first, legacy String
second' fallback chain into a single posterRef.toCoilModelOrNull() read —
ArtworkDisplayRef.LegacyString.toCoilModelOrNull() already routes through
the legacy string path, so behavior is unchanged for legacy callers.

Type-strict slot contract per CLAUDE.md rule #1 is now enforced at the
card primitive boundary. Per-surface migrations in Phases 1A-G replace
the toRailCardData() wrappers with typed projections."
```

---

## Self-review

**1. Spec coverage:**

The spec's Phase 0 section enumerates: define `RailCardData` interface, migrate `GridContentCard`, provide `MetaPreview.toRailCardData()` adapter, existing typed projections implement it.

| Spec requirement | Task |
|---|---|
| `RailCardData` interface defined with `posterRef` typed strict | Task 1 |
| `MetaPreview.toRailCardData()` legacy adapter | Task 1 |
| Adapter mapping unit tests | Task 2 |
| `ModernHomeRowItem` implements `RailCardData` | Task 3 |
| `DetailRailItem` implements `RailCardData` | Task 4 |
| `GridContentCard` parameter type → `RailCardData` | Task 5 |
| All 9 callers updated atomically | Task 5 |
| No behavior change at any call site | Task 5 (Steps 4-7) |

All Phase 0 spec requirements have a task. The spec's broader phases (1A-G, 2A-D, 3, 4) are deferred to their own future plans, per the spec's "13 sub-projects" decomposition.

**2. Placeholder scan:** none — every task has exact file paths, exact code, exact commands, and exact expected output. The "if any test fails to compile because MetaPreview requires fields not in makeMeta" note in Task 2 Step 2 is a fallback instruction with concrete remediation, not a placeholder.

**3. Type consistency:**
- `RailCardData.id: String` is consistent across Tasks 1, 3, 4, 5.
- `RailCardData.name: String?` is consistent (Task 5 Step 1 line 196 handles null via `.orEmpty()`).
- `RailCardData.posterRef: ArtworkDisplayRef?` is the same `ArtworkDisplayRef` from `com.nexio.tv.core.artwork` used by every existing typed projection.
- `RailCardData.posterProviderTag: String?` — added to `ModernHomeRowItem` (Task 3), `DetailRailItem` (Task 4), and read in Task 5 line 158-159. The constructor signature in Task 3 includes it as the last parameter; Task 3's existing-call-site fix instruction handles fixture migration.
- `MetaPreview.toRailCardData()` defined in Task 1, called in Task 5 Step 2.
- `ArtworkDisplayRef?.deriveProviderTag()` defined in Task 3 Step 4, used in Task 3 Step 4's `ModernHomeRowItem.from()`.

No type drift detected.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-10-phase-0-railcarddata-foundation.md`. Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent per task, two-stage review between tasks, fast iteration.
2. **Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints for review.

Which approach?
