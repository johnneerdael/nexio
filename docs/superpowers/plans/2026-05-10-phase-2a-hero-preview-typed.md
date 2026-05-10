# Phase 2A — Hero-preview side channel reads from typed `ContinueWatchingResolvedDisplayItem`

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `buildContinueWatchingItem` in `ModernHomeModels.kt` currently aliases `val item = resolved.toContinueWatchingItem()` and reads display fields (`displayMetadata.displayPoster` / `displayBackdrop` / `displayLogo` / `title`) from the legacy chain to build the focused-CW Modern hero preview. Migrate these reads to consume the typed `resolved.posterRef` / `backdropRef` / `logoRef` / `title` directly. Eliminates the rule #1 typed-slot violation flagged in commit `c2c4db253` (Plan B Surface 4 follow-up).

**Architecture:** Single file change. Replace `displayMetadata.displayPoster` with `resolved.posterRef.toLegacyArtworkString()`, `displayBackdrop` with `resolved.backdropRef.toLegacyArtworkString()`, `displayLogo` with `resolved.logoRef.toLegacyArtworkString()`, and the title with `resolved.title`. Other `displayMetadata` fields (description, releaseInfo, imdbRating, ratingSource, tomatoesRating, genres) stay on the legacy chain — they're not yet on the typed projection and migrating them is Phase 3 scope. The `val item = resolved.toContinueWatchingItem()` alias remains because the function still reads CW-specific source fields (episode info, name fallbacks).

**Tech Stack:** Kotlin · Compose · `ArtworkDisplayRef.toLegacyArtworkString()` (existing helper at `app/src/main/java/com/nexio/tv/core/artwork/ArtworkLegacyProjection.kt:22`) · JUnit4

**Spec source:** `docs/superpowers/specs/2026-05-10-home-metapreview-elimination-design.md` Phase 2A.

---

## File Structure

### Modified files

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt` | In `buildContinueWatchingItem(resolved, …)`: extract `val resolvedPoster = resolved.posterRef.toLegacyArtworkString()`, `val resolvedBackdrop = resolved.backdropRef.toLegacyArtworkString()`, `val resolvedLogo = resolved.logoRef.toLegacyArtworkString()`, `val resolvedTitle = resolved.title` once at the top. Replace each downstream `displayMetadata.displayPoster` / `displayBackdrop` / `displayLogo` / `displayMetadata.title` read with the new locals. Update the TODO comment block to describe what was migrated and what remains (description/genres/etc. as Phase 3 scope). |

### No new types, no new tests

This task is a pure read-site swap inside one function. The contract `ContinueWatchingResolvedDisplayItem.posterRef` / `backdropRef` / `logoRef` is already typed strict (rule #1) and tested via `ContinueWatchingResolvedDisplayItemTest`. No additional unit tests needed — the existing CW migration tests cover the projection. On-device smoke verifies the rendered preview still resolves correctly.

---

## Task 1: Migrate display-field reads in `buildContinueWatchingItem`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`

- [ ] **Step 1: Read the current function**

Open `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt` and read the entire `buildContinueWatchingItem` function (starts around line 380, runs to ~540). Note:
- The `val item = resolved.toContinueWatchingItem()` alias.
- The `val displayMetadata = item.displayMetadata()` line.
- The 20 read sites for `displayMetadata.displayPoster` / `displayBackdrop` / `displayLogo` / `title` across the InProgress branch, NextUp branch, and the bottom `imageUrl` computation.
- The TODO comment block at lines ~383-392.

- [ ] **Step 2: Add typed locals at the top of the function**

After the existing `val item = resolved.toContinueWatchingItem()` line and BEFORE `val displayMetadata = item.displayMetadata()`, insert:

```kotlin
// Phase 2A migration: poster/backdrop/logo/title now read from the typed
// projection (rule #1) instead of the legacy displayMetadata chain. Other
// fields (description, releaseInfo, imdbRating, tomatoesRating, genres)
// stay on displayMetadata for now — those typed equivalents land in Phase 3
// (catalog pipeline restructure).
val resolvedPoster: String? = resolved.posterRef.toLegacyArtworkString()
val resolvedBackdrop: String? = resolved.backdropRef.toLegacyArtworkString()
val resolvedLogo: String? = resolved.logoRef.toLegacyArtworkString()
val resolvedTitle: String? = resolved.title
```

Add the import `import com.nexio.tv.core.artwork.toLegacyArtworkString` if not already present.

- [ ] **Step 3: Migrate each read site in the `HeroPreview` for InProgress**

In the `is ContinueWatchingItem.InProgress -> { HeroPreview(...) }` block (~line 395-432):

- `title = displayMetadata.title ?: item.progress.name` → `title = resolvedTitle ?: item.progress.name`
- `logo = displayMetadata.displayLogo` → `logo = resolvedLogo`
- `poster = displayMetadata.displayPoster` → `poster = resolvedPoster`
- `backdrop = displayMetadata.displayBackdrop` → `backdrop = resolvedBackdrop`
- Inside `imageUrl = if (useLandscapePosters) { firstNonBlank(displayMetadata.displayBackdrop, displayMetadata.displayPoster) } else { displayMetadata.displayPoster }` → `imageUrl = if (useLandscapePosters) { firstNonBlank(resolvedBackdrop, resolvedPoster) } else { resolvedPoster }`

Leave `description`, `yearText`, `imdbText`, `tomatoesText`, `genres` unchanged — those still read from `displayMetadata` (Phase 3 will migrate them).

- [ ] **Step 4: Migrate each read site in the `HeroPreview` for NextUp**

In the `is ContinueWatchingItem.NextUp -> { HeroPreview(...) }` block (~line 433-470):

- `title = displayMetadata.title ?: item.info.name` → `title = resolvedTitle ?: item.info.name`
- `logo = displayMetadata.displayLogo` → `logo = resolvedLogo`
- `poster = displayMetadata.displayPoster` → `poster = resolvedPoster`
- `backdrop = displayMetadata.displayBackdrop` → `backdrop = resolvedBackdrop`
- Inside `imageUrl = if (useLandscapePosters) { firstNonBlank(displayMetadata.displayBackdrop, displayMetadata.displayPoster, item.info.thumbnail) } else { firstNonBlank(displayMetadata.displayPoster, item.info.thumbnail) }` → swap each `displayMetadata.displayBackdrop` for `resolvedBackdrop` and each `displayMetadata.displayPoster` for `resolvedPoster`. Keep `item.info.thumbnail` (NextUp-specific episode still).

Same exception list as Step 3: description / yearText / imdbText / tomatoesText / genres stay on `displayMetadata`.

- [ ] **Step 5: Migrate the bottom `imageUrl` computation**

After the `heroPreview` block, the function continues with a separate `val imageUrl = when (item) { ... }` block (~line 472-510). Each `displayMetadata.displayPoster` / `displayBackdrop` read in that block also swaps to `resolvedPoster` / `resolvedBackdrop`. Walk every match in the function body via grep:

```bash
grep -n "displayMetadata\\.displayPoster\\|displayMetadata\\.displayBackdrop\\|displayMetadata\\.displayLogo" app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt
```

After your edits, this grep should return zero hits. (`displayMetadata.title` may still appear if there's a non-hero use; keep `displayMetadata.title` for non-hero reads if present, but in `buildContinueWatchingItem` specifically the title path also flips.)

- [ ] **Step 6: Update the TODO comment block**

The existing comment block at the top of `buildContinueWatchingItem` (lines ~383-392) said:

```kotlin
// The resolved projection drives the rendered CW row card via
// ContinueWatchingCard (typed posterRef/backdropRef/logoRef). This function
// builds the Modern carousel's HERO PREVIEW (the focused-trailer big-preview
// image at the top of Modern Home), which still derives display fields from
// the legacy displayMetadata chain. The card layer is rule #1 compliant; the
// hero-preview layer is not yet.
//
// TODO(Plan B Surface 4 follow-up): migrate hero-preview poster/backdrop/logo
// reads to resolved.posterRef/backdropRef/logoRef.toLegacyArtworkString() so
// the focused-CW preview also enforces typed display authority.
```

Replace it with:

```kotlin
// The resolved projection drives both the rendered CW row card AND the
// Modern carousel's HERO PREVIEW (this function). poster/backdrop/logo/title
// read from the typed `resolved.posterRef` / `backdropRef` / `logoRef` /
// `title` slots — rule #1 compliant. Other display fields (description,
// releaseInfo, imdbRating, tomatoesRating, genres) still come from the
// legacy `displayMetadata` chain on the embedded ContinueWatchingItem;
// migrating those is Phase 3 (catalog pipeline restructure) of
// docs/superpowers/specs/2026-05-10-home-metapreview-elimination-design.md.
```

- [ ] **Step 7: Compile + verify grep is clean**

```bash
./gradlew :app:compileUniversalDebugKotlin 2>&1 | tail -8
```

Expected: BUILD SUCCESSFUL.

```bash
grep -n "displayMetadata\\.displayPoster\\|displayMetadata\\.displayBackdrop\\|displayMetadata\\.displayLogo" app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt
```

Expected: empty output.

```bash
grep -c "resolvedPoster\\|resolvedBackdrop\\|resolvedLogo\\|resolvedTitle" app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt
```

Expected: a count > 0 (the new locals are used).

- [ ] **Step 8: Run targeted tests**

```bash
./gradlew :app:testUniversalDebugUnitTest \
    --tests "com.nexio.tv.ui.screens.home.ModernHomeModelsTest" \
    --tests "com.nexio.tv.ui.screens.home.ModernHomePresentationTest" \
    --tests "com.nexio.tv.ui.components.ContinueWatchingResolvedDisplayItemTest" 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL. Pre-existing failures (OpenSubtitles, rating-source, overlay-applier) are out of scope — DO NOT fix.

If `ModernHomeModelsTest` was constructing test fixtures that built `ContinueWatchingResolvedDisplayItem` with specific posterRef values and then asserting `displayMetadata.displayPoster` reaches the rendered output, the test may now fail because the read source changed. Fix by updating the test's assertion to read `resolved.posterRef.toLegacyArtworkString()` instead — same shape, just sourced from the projection. Do not weaken or skip the assertion.

- [ ] **Step 9: Build APK**

```bash
./gradlew :app:assembleUniversalDebug 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Smoke test on device**

```bash
adb -s 192.168.50.98:5555 install -r app/build/outputs/apk/universal/debug/app-universal-debug.apk
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 30
adb -s 192.168.50.98:5555 logcat -d -t 600 | grep -E "FATAL|AndroidRuntime|ANR|ClassCast|NoSuchMethod" | tail -10
```

Expected: empty output. The user can manually verify on-device that focusing a CW item still shows the correct poster/backdrop/logo in the Modern hero preview.

- [ ] **Step 11: Commit**

EXPLICITLY stage the 1 file (DO NOT use `git add -A`). If a test file was updated in Step 8, also stage it explicitly:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt
# If a test file required updating in Step 8:
# git add app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomeModelsTest.kt
git status -sb  # verify only the intended files staged; in-flight other-workstream files untouched
git diff --cached --stat
git commit -m "feat(home): hero-preview reads from typed ContinueWatchingResolvedDisplayItem

Phase 2A of the home-MetaPreview-elimination spec (commit c6d885e81).
buildContinueWatchingItem's HERO PREVIEW now reads poster/backdrop/logo
/title from the typed resolved.posterRef / backdropRef / logoRef / title
slots instead of the legacy displayMetadata.displayPoster / displayBackdrop
/ displayLogo / title chain. Closes the rule #1 follow-up TODO from
commit c2c4db253 — the focused-CW preview now enforces typed display
authority just like the CW row card itself.

Other display fields (description, releaseInfo, imdbRating, tomatoesRating,
genres) still come from the legacy displayMetadata chain on the embedded
ContinueWatchingItem — migrating those is Phase 3 (catalog pipeline
restructure) scope.

The resolved.toContinueWatchingItem() alias remains because the function
still reads CW-specific source fields (episode info, name fallbacks)
that aren't on the projection."
```

---

## Self-review

**1. Spec coverage:**

Phase 2A spec section: "Migrate to read from `resolved.posterRef` / `backdropRef` / `logoRef.toLegacyArtworkString()`. Other fields (description, releaseInfo, imdbRating, tomatoesRating, genres) stay on legacy chain — those are Phase 3 scope."

Task 1 covers exactly that. Steps 3 + 4 + 5 walk every poster/backdrop/logo/title read site in the function. Steps 7 + 8 verify with grep + targeted tests. Out-of-scope fields (description/releaseInfo/imdbRating/tomatoesRating/genres) are explicitly preserved at each step.

**2. Placeholder scan:** None. Each step has exact match patterns and exact replacements. The "if a test file required updating" branch in Step 8 is a known-deterministic remediation, not a placeholder.

**3. Type consistency:**
- `resolved.posterRef: ArtworkDisplayRef?` — strict POSTER per `ContinueWatchingResolvedDisplayItem` (`fromInProgress` and `fromInProgressLegacy` both wrap with POSTER type).
- `resolved.backdropRef: ArtworkDisplayRef?` — strict BACKDROP.
- `resolved.logoRef: ArtworkDisplayRef?` — strict LOGO.
- `resolved.title: String?` — exposed on the sealed parent.
- `ArtworkDisplayRef?.toLegacyArtworkString(): String?` — the existing extension at `ArtworkLegacyProjection.kt:22`.

No type drift detected.

**4. Risk:**

LOW. Single-function read swap. The typed slots feeding `resolved.posterRef`/etc. are populated by the same upstream as `displayMetadata.displayPoster` (the resolved authority's poster slot). Behavior should be identical for typed-resolved items; for legacy-fallback items (`fromInProgressLegacy`), the wrapped `LegacyString` value is the same string as `displayMetadata.displayPoster` would yield. On-device smoke verifies.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-10-phase-2a-hero-preview-typed.md`. Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent for the task, two-stage review, fast iteration.
2. **Inline Execution** — execute in this session using executing-plans.

Which approach?
