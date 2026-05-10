# Phase 1F + 1G — `CatalogSeeAllScreen` + `GridHomeContent` consume `ModernHomeRowItem` directly

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `.toRailCardData()` wrapper at `CatalogSeeAllScreen` and `GridHomeContent` with a direct `ModernHomeRowItem` projection. Add a `ModernHomeRowItem.fromMetaPreview(meta)` factory (mirroring `DetailRailItem.fromMetaPreview` from commit `d777c65b3`) so home-rail items can be projected from `MetaPreview` at the screen boundary without going through the legacy adapter.

**Architecture:** Both screens currently render rail items by wrapping a `MetaPreview` with the legacy adapter. After this work, each item is projected via the existing `ModernHomeRowItem` typed shape — same shape used by Modern Home rails (already on the resolved authority). The factory wraps poster/backdrop/logo URL strings as `ArtworkDisplayRef.LegacyString` of the typed kind so the rule #1 strict-typed-slot contract holds at the screen boundary.

**Tech Stack:** Kotlin · Compose · `ArtworkDisplayRef` (existing typed slot type) · `homeDisplayItemKey` helper · JUnit4

**Spec source:** `docs/superpowers/specs/2026-05-10-home-metapreview-elimination-design.md` Phase 1F + 1G.

---

## File Structure

### Modified files

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRowItem.kt` | Add `fromMetaPreview(meta: MetaPreview)` companion factory wrapping legacy String refs as `ArtworkDisplayRef.LegacyString`. |
| `app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomeRowItemTest.kt` | Create or extend with tests for the new factory: id/title mapping, year extraction, posterRef LegacyString wrapping, posterProviderTag passthrough. |
| `app/src/main/java/com/nexio/tv/ui/screens/CatalogSeeAllScreen.kt` | Replace `item.toRailCardData()` with `ModernHomeRowItem.fromMetaPreview(item)`. Drop the `import com.nexio.tv.ui.components.toRailCardData` if no other reference. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt` | Replace `effectiveItem.toRailCardData()` with `ModernHomeRowItem.fromMetaPreview(effectiveItem)`. Drop the `toRailCardData` import if unused. |

### Out of scope

- The other 5 `.toRailCardData()` callers (search, library, organization, cast, feed) — those are Phases 1A-E with their own typed projections (different from `ModernHomeRowItem`).
- `CatalogInventoryRepository.observeRail` signature — keep emitting `CatalogRow?` (unchanged); projection happens at the screen.
- `overlayResolvedDisplay` helper used by `GridHomeContent` — preserved as-is, just projected via the factory at the call site.

---

## Task 1: Add `ModernHomeRowItem.fromMetaPreview(meta)` factory

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRowItem.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomeRowItemFromMetaPreviewTest.kt` (new)

- [ ] **Step 1: Read the current file**

Read `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRowItem.kt` end-to-end. Note the existing `from(resolved: ResolvedDisplayItem)` factory and the `ArtworkDisplayRef?.deriveProviderTag()` helper.

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomeRowItemFromMetaPreviewTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.homeDisplayItemKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModernHomeRowItemFromMetaPreviewTest {

    @Test
    fun `fromMetaPreview maps id and title from MetaPreview`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt99", name = "Title")
        )
        assertEquals("tt99", item.contentId)
        assertEquals("tt99", item.parentId)
        assertEquals("Title", item.title)
        assertEquals(homeDisplayItemKey(ContentType.MOVIE.toApiString(), "tt99"), item.itemKey)
    }

    @Test
    fun `fromMetaPreview wraps poster URL as LegacyString of POSTER type`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", poster = "https://x/p.jpg")
        )
        val ref = item.posterRef as ArtworkDisplayRef.LegacyString
        assertEquals("https://x/p.jpg", ref.value)
        assertEquals(ArtworkType.POSTER, ref.imageType)
    }

    @Test
    fun `fromMetaPreview wraps background URL as LegacyString of BACKDROP type`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", background = "https://x/b.jpg")
        )
        val ref = item.backdropRef as ArtworkDisplayRef.LegacyString
        assertEquals("https://x/b.jpg", ref.value)
        assertEquals(ArtworkType.BACKDROP, ref.imageType)
    }

    @Test
    fun `fromMetaPreview wraps logo URL as LegacyString of LOGO type`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", logo = "https://x/l.jpg")
        )
        val ref = item.logoRef as ArtworkDisplayRef.LegacyString
        assertEquals("https://x/l.jpg", ref.value)
        assertEquals(ArtworkType.LOGO, ref.imageType)
    }

    @Test
    fun `fromMetaPreview returns null poster ref when poster is null`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", poster = null)
        )
        assertNull(item.posterRef)
    }

    @Test
    fun `fromMetaPreview returns null poster ref when poster is blank`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", poster = "  ")
        )
        assertNull(item.posterRef)
    }

    @Test
    fun `fromMetaPreview extracts year from releaseInfo`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", releaseInfo = "2024-03-15")
        )
        assertEquals(2024, item.year)
    }

    @Test
    fun `fromMetaPreview returns null year when releaseInfo is null`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", releaseInfo = null)
        )
        assertNull(item.year)
    }

    @Test
    fun `fromMetaPreview passes posterProviderTag through`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", posterProviderTag = "tmdb")
        )
        assertEquals("tmdb", item.posterProviderTag)
    }

    @Test
    fun `fromMetaPreview sets hydrationState to PREVIEW_ONLY`() {
        val item = ModernHomeRowItem.fromMetaPreview(makeMeta(id = "tt1", name = "x"))
        assertEquals(HydrationState.PREVIEW_ONLY, item.hydrationState)
    }

    private fun makeMeta(
        id: String,
        name: String,
        poster: String? = null,
        background: String? = null,
        logo: String? = null,
        releaseInfo: String? = null,
        posterProviderTag: String? = null
    ) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = name,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = background,
        logo = logo,
        description = null,
        releaseInfo = releaseInfo,
        imdbRating = null,
        genres = emptyList(),
        posterProviderTag = posterProviderTag
    )
}
```

If `MetaPreview` constructor needs additional required params, add only what the compiler complains about. The same fixture pattern is used in `RailCardDataTest.kt` and `DetailRailItemRailCardDataTest.kt` (commits `a1b60b517` and `de16841c4`); copy from there if needed.

- [ ] **Step 3: Run test to verify it fails**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.home.ModernHomeRowItemFromMetaPreviewTest" 2>&1 | tail -10
```

Expected: BUILD FAILED with `Unresolved reference: fromMetaPreview`.

- [ ] **Step 4: Add the factory**

Edit `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRowItem.kt`. Add an import for `ArtworkType`, `ArtworkTrace`, `MetaPreview`, and `homeDisplayItemKey` if they're not already imported (some may be).

Inside the `companion object` (alongside the existing `from(resolved)`), append:

```kotlin
/**
 * Best-effort projection from a legacy [MetaPreview] when the resolved
 * authority's `ResolvedDisplayItem` is not available at the call site.
 * Wraps poster/background/logo URL strings as
 * [ArtworkDisplayRef.LegacyString] of the typed [ArtworkType] so the
 * surface boundary's rule #1 strict-typed-slot contract holds.
 *
 * Mirrors [com.nexio.tv.ui.screens.detail.DetailRailItem.fromMetaPreview]
 * (commit `d777c65b3`). Callers that already have a `ResolvedDisplayItem`
 * should use [from] instead.
 */
fun fromMetaPreview(meta: MetaPreview): ModernHomeRowItem = ModernHomeRowItem(
    itemKey = homeDisplayItemKey(meta.apiType, meta.id),
    contentId = meta.id,
    parentId = meta.id,
    title = meta.name,
    year = meta.releaseInfo?.split("-")?.firstOrNull()?.toIntOrNull(),
    posterRef = meta.poster.toLegacyHomeRailRefOrNull(ArtworkType.POSTER),
    backdropRef = meta.background.toLegacyHomeRailRefOrNull(ArtworkType.BACKDROP),
    logoRef = meta.logo.toLegacyHomeRailRefOrNull(ArtworkType.LOGO),
    thumbnailRef = null,
    rating = null,
    hydrationState = HydrationState.PREVIEW_ONLY,
    posterProviderTag = meta.posterProviderTag
)
```

After the `companion object` block (top-level in the file), add the helper:

```kotlin
private fun String?.toLegacyHomeRailRefOrNull(type: ArtworkType): ArtworkDisplayRef? {
    val v = this?.takeIf { it.isNotBlank() } ?: return null
    return ArtworkDisplayRef.LegacyString(value = v, imageType = type, trace = ArtworkTrace.empty())
}
```

(Naming: `toLegacyHomeRailRefOrNull` rather than just `toLegacyRefOrNull` to avoid collision with `DetailRailItem`'s file-private `String?.toLegacyRailRefOrNull` extension. The two are semantically equivalent but keeping them per-file private avoids compile clashes if both files end up in the same module-level namespace at some point.)

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.home.ModernHomeRowItemFromMetaPreviewTest" 2>&1 | tail -10
```

Expected: 10 tests, 0 failures.

- [ ] **Step 6: Compile production**

```bash
./gradlew :app:compileUniversalDebugKotlin 2>&1 | tail -8
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRowItem.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomeRowItemFromMetaPreviewTest.kt
git commit -m "feat(home): ModernHomeRowItem.fromMetaPreview factory

Phases 1F+1G of the home-MetaPreview-elimination spec (commit c6d885e81).
Mirrors DetailRailItem.fromMetaPreview (commit d777c65b3) — wraps a
legacy MetaPreview's poster/background/logo URL strings as
ArtworkDisplayRef.LegacyString of the typed ArtworkType so the typed
slot contract holds at the surface boundary even when the resolved
authority's ResolvedDisplayItem is not available.

Used by Phase 1F (CatalogSeeAllScreen) and Phase 1G (GridHomeContent)
in the next task to drop the .toRailCardData() wrapper landed in
Phase 0 Task 5 (commit 90a6195eb)."
```

---

## Task 2: Migrate `CatalogSeeAllScreen` + `GridHomeContent` call sites

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/CatalogSeeAllScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt`

- [ ] **Step 1: Update `CatalogSeeAllScreen`**

Edit `app/src/main/java/com/nexio/tv/ui/screens/CatalogSeeAllScreen.kt`:

1. At the `GridContentCard(item = item.toRailCardData(), ...)` site (line ~198), change to:
   ```kotlin
   GridContentCard(
       item = ModernHomeRowItem.fromMetaPreview(item),
       ...
   )
   ```

2. Remove the import `import com.nexio.tv.ui.components.toRailCardData` if no other reference in this file (grep the file for `toRailCardData` to confirm).

3. Add the import `import com.nexio.tv.ui.screens.home.ModernHomeRowItem`.

- [ ] **Step 2: Update `GridHomeContent`**

Edit `app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt`:

1. At the `GridContentCard(item = effectiveItem.toRailCardData(), ...)` site (line ~345), change to:
   ```kotlin
   GridContentCard(
       item = ModernHomeRowItem.fromMetaPreview(effectiveItem),
       ...
   )
   ```

2. Remove the import `import com.nexio.tv.ui.components.toRailCardData` if no other reference.

3. `ModernHomeRowItem` is in the same package (`com.nexio.tv.ui.screens.home`), so no import needed.

- [ ] **Step 3: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin 2>&1 | tail -8
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run smoke tests for the affected screens**

```bash
./gradlew :app:testUniversalDebugUnitTest \
    --tests "com.nexio.tv.ui.screens.home.ModernHomeRowItemFromMetaPreviewTest" \
    --tests "com.nexio.tv.ui.screens.CatalogSeeAll*" \
    --tests "com.nexio.tv.ui.screens.home.GridHome*" 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL. Pre-existing failures (OpenSubtitles, rating-source, overlay-applier) are out of scope.

- [ ] **Step 5: Build APK**

```bash
./gradlew :app:assembleUniversalDebug 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Smoke test on device**

```bash
adb -s 192.168.50.98:5555 install -r app/build/outputs/apk/universal/debug/app-universal-debug.apk
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 30
adb -s 192.168.50.98:5555 logcat -d -t 600 | grep -E "FATAL|AndroidRuntime|ANR|ClassCast|NoSuchMethod" | tail -10
```

Expected: empty.

- [ ] **Step 7: Commit**

EXPLICITLY stage the 2 files (DO NOT use `git add -A`):

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/CatalogSeeAllScreen.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt
git status -sb  # verify only these 2 files staged; in-flight other-workstream files untouched
git diff --cached --stat
git commit -m "feat(home): SeeAll + GridHome consume ModernHomeRowItem directly

Phases 1F+1G of the home-MetaPreview-elimination spec (commit c6d885e81).
CatalogSeeAllScreen and GridHomeContent now project rail items via
ModernHomeRowItem.fromMetaPreview() (commit from previous task) instead
of the toRailCardData() legacy adapter introduced in Phase 0 Task 5
(commit 90a6195eb).

Type-strict slot contract per CLAUDE.md rule #1 is now enforced at the
ModernHomeRowItem layer for these two screens — same shape Modern Home
rails use. Two of the seven legacy adapter call sites are now removed;
five remain (search, library, organization, cast, feed) — Phases 1A-E
cover those with their own surface-specific typed projections."
```

---

## Self-review

**1. Spec coverage:**

Phase 1F: `CatalogSeeAllScreen → reuse ModernHomeRowItem` — Task 2 Step 1.
Phase 1G: `GridHomeContent → reuse ModernHomeRowItem` — Task 2 Step 2.
The factory needed to support both is added in Task 1.

**2. Placeholder scan:** None. Each step has exact code, exact commands, exact expected output. The only deferred decision is "if `MetaPreview` constructor needs additional required params, add only what the compiler complains about" — that's a known-deterministic remediation, not a placeholder.

**3. Type consistency:**
- `ModernHomeRowItem.fromMetaPreview(meta: MetaPreview): ModernHomeRowItem` — defined in Task 1 Step 4, used in Task 2 Steps 1 + 2.
- `String?.toLegacyHomeRailRefOrNull(type: ArtworkType): ArtworkDisplayRef?` — defined in Task 1 Step 4, only used inside the factory.
- All field names on `ModernHomeRowItem` match what's already on the data class (added in Phase 0 Task 3).
- `homeDisplayItemKey(apiType: String, id: String)` — same signature used by `DetailRailItem.fromMetaPreview`.

No type drift detected.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-10-phase-1fg-modernhomerow-direct.md`. Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent per task, two-stage review, fast iteration.
2. **Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
