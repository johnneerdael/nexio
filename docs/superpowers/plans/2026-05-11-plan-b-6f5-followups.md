# Plan B 6f.5 Follow-Ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close two follow-up cleanups the 6f.5 reviewers flagged: drop obsolete `HomeReactiveHydrationPipelineTest` tests that exercise the no-op `composeHydratedHomeOverlaySnapshot`, and stop the producer path from double-publishing to `HOME_SURFACE_KEY`.

**Architecture:** Two tightly-scoped commits in two files. Task 1 deletes test code that no longer matches production behavior (Phase 3.6.5 turned the compose helper into a pass-through; three tests still assert that helper applies overlays). Task 2 adds a default-true `publishResolvedSurface: Boolean` parameter to `applyHomeResolvedRowsToUiPipeline`; the producer entry passes `false` so the producer's own enriched publish at `:3082` is the single authoritative HOME_SURFACE_KEY writer per producer emission.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit 4 (test side), Android TV.

**Pre-flight context — what 6f.5 shipped:**
- Snapshot legacy fields dropped (`d2e787e8d`), reader/writer simplified.
- Apply pipeline split into a cold-start path (filter+reconstruct) and a producer path (`applyHomeProducerEmissionToUiPipeline`, `6b06f8ec2`) that bypasses the rails-roundtrip.
- Both apply paths funnel through a private shared core `applyHomeResolvedRowsToUiPipeline` that composes, sets `_internalCatalogRows`, publishes the typed surface, updates grid items, and kicks off downstream enrichment.
- Tag `plan-b-6f5-complete` at commit `cba299e3d`, pushed.

**Reviewer-flagged residuals:**
1. `HomeReactiveHydrationPipelineTest` runs 7 tests; 4 pass, 3 fail. The 3 failing tests (`overlay snapshot composition updates visible card and hero without row reorder`, `... respects disabled hero field groups`, `... applies enabled hero field groups`) assert overlay application by `composeHydratedHomeOverlaySnapshot`. Phase 3.6.5 turned that function into a no-op pass-through (`@Suppress("UNUSED_PARAMETER") overlaysByItemKey`); the typed authority (`ResolvedDisplaySurfaceRepository`) owns overlay application now. The tests have no path to pass.
2. Producer emission at `HomeViewModelCatalogPipeline.kt:3076-3091` calls `applyHomeProducerEmissionToUiPipeline(...)` → shared core publishes to `HOME_SURFACE_KEY` via `toResolvedDisplayItems(...)` (non-enriched) at `:3282-3286`, then the producer immediately overwrites with `toResolvedDisplayItemsEnriched(...)` at `:3088-3091`. The enriched publish wins; the first call is N `ResolvedDisplayItem` allocations + one `publishResolvedItems` invocation discarded per producer emission. With producer emissions every 7-22s and ~1,400 home items, that's a steady allocation tax for no observable effect.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactiveHydrationPipelineTest.kt` | Delete the 3 obsolete `composeHydratedHomeOverlaySnapshot` overlay-application tests. Keep the 4 surviving tests (overlay observation keys, two republish-gate tests, hero-identity pass-through) and the `preview`/`row`/`overlay` helper functions used by them. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt` | Add `publishResolvedSurface: Boolean = true` to `applyHomeResolvedRowsToUiPipeline`. Gate the HOME_SURFACE_KEY publish block (lines ~3277-3286) on the parameter. `applyHomeProducerEmissionToUiPipeline` calls the core with `publishResolvedSurface = false`; cold-start `applyHomeSnapshotToUiPipeline` keeps the default (true). |

---

## Task 1: Delete obsolete `composeHydratedHomeOverlaySnapshot` tests

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactiveHydrationPipelineTest.kt`

The three failing tests (at lines 22-47, 113-168, 170-209) all build a `MetaPreview` + an overlay that supplies different content, call `composeHydratedHomeOverlaySnapshot`, and assert the OVERLAY content wins. Since Phase 3.6.5, the function returns its inputs unchanged — those assertions can never pass.

The other four tests (overlay observation keys at 49-61, two republish-gate tests at 63-91, hero-identity pass-through at 93-111) are unaffected — they test orthogonal helpers (`hydratedHomeOverlayItemKeysForRows`, `shouldPublishHydratedHomeOverlays`) or already assert the no-op behavior (`assertSame(heroItems, composed.heroItems)`).

- [ ] **Step 1: Verify the failure mode**

Run:
```bash
cd /Users/jneerdael/Scripts/nexio
./gradlew :app:testUniversalDebugUnitTest --tests "*HomeReactiveHydrationPipelineTest*" --max-workers=1 2>&1 | grep -E "FAILED|PASSED|Tests:" | head -10
```

Expected output (matching today's broken state):
```
HomeReactiveHydrationPipelineTest > overlay snapshot composition applies enabled hero field groups FAILED
HomeReactiveHydrationPipelineTest > overlay snapshot composition updates visible card and hero without row reorder FAILED
HomeReactiveHydrationPipelineTest > overlay snapshot composition respects disabled hero field groups FAILED
```

- [ ] **Step 2: Delete test 1 (`updates visible card and hero without row reorder`)**

Edit `app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactiveHydrationPipelineTest.kt`. Delete the entire block from line 21 (`@Test` annotation) through line 47 (closing `}` of the test function) inclusive. The deleted block is:

```kotlin
    @Test
    fun `overlay snapshot composition updates visible card and hero without row reorder`() {
        val first = preview("tmdb:550", "Preview")
        val second = preview("tmdb:551", "Second")
        val displayRow = row(listOf(first, second))
        val fullRow = row(listOf(first, second, preview("tmdb:552", "Third")))
        val overlay = overlay(
            itemKey = "movie:tmdb:550",
            fields = HomeDisplayMetadata(title = "Canonical", poster = "poster.jpg")
        )

        val composed = composeHydratedHomeOverlaySnapshot(
            displayRows = listOf(displayRow),
            fullRows = listOf(fullRow),
            heroItems = listOf(first, second),
            overlaysByItemKey = mapOf("movie:tmdb:550" to overlay)
        )

        assertEquals(listOf("tmdb:550", "tmdb:551"), composed.displayRows.single().items.map { it.id })
        assertEquals(listOf("tmdb:550", "tmdb:551", "tmdb:552"), composed.fullRows.single().items.map { it.id })
        assertEquals(listOf("tmdb:550", "tmdb:551"), composed.heroItems.map { it.id })
        assertEquals("Canonical", composed.displayRows.single().items.first().name)
        assertEquals("poster.jpg", composed.displayRows.single().items.first().poster)
        assertEquals("Canonical", composed.fullRows.single().items.first().name)
        assertEquals("Canonical", composed.heroItems.first().name)
        assertEquals("Second", composed.displayRows.single().items.last().name)
    }
```

Preserve the blank line separating tests (so the file stays readable).

- [ ] **Step 3: Delete test 6 (`respects disabled hero field groups`)**

Delete the entire block from the `@Test` line (was line 113) through the closing `}` of the function (was line 168). The deleted block starts with:

```kotlin
    @Test
    fun `overlay snapshot composition respects disabled hero field groups`() {
        val item = preview("tmdb:550", "Preview").copy(
```

and ends with:

```kotlin
        assertEquals(null, hero.poster)
    }
```

- [ ] **Step 4: Delete test 7 (`applies enabled hero field groups`)**

Delete the entire block from the `@Test` line (was line 170) through the closing `}` of the function (was line 209). The deleted block starts with:

```kotlin
    @Test
    fun `overlay snapshot composition applies enabled hero field groups`() {
        val item = preview("tmdb:550", "Preview").copy(
```

and ends with:

```kotlin
        assertEquals("2024", hero.releaseInfo)
    }
```

- [ ] **Step 5: Check for now-unused imports**

After deletions, four tests remain plus the helpers. Verify which imports are still needed:

```bash
grep -E "^import" app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactiveHydrationPipelineTest.kt
```

Likely candidates for removal (verify each by grepping the post-delete file):

```bash
for sym in TmdbSettings TitleRatingSource assertFalse assertTrue; do
  echo "$sym: $(grep -c "\\b$sym\\b" app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactiveHydrationPipelineTest.kt) hits"
done
```

If `TitleRatingSource` shows 0 hits in the post-delete file, remove `import com.nexio.tv.domain.model.TitleRatingSource`. Apply the same logic to `TmdbSettings`. `assertFalse`/`assertTrue` are used by `unchanged overlay map does not request a republish` (line 71-72) so they stay.

`TmdbSettings` is referenced by the surviving `snapshot composition preserves hero identity when overlay display is unchanged` test? Inspect:

```bash
grep -n "TmdbSettings" app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactiveHydrationPipelineTest.kt
```

If the only remaining `TmdbSettings` reference is in deleted tests' bodies (which by Step 4 are gone), remove the import. Same check for `TitleRatingSource`.

- [ ] **Step 6: Run the suite to confirm clean**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*HomeReactiveHydrationPipelineTest*" --max-workers=1 2>&1 | grep -E "FAILED|PASSED|tests completed|Tests:" | head -10
```

Expected: zero `FAILED` lines, 4 tests passing (no explicit `PASSED` lines from Gradle — confirm via `tests completed, 0 failed`):

```
> Task :app:testUniversalDebugUnitTest
BUILD SUCCESSFUL
```

If a surviving test fails, the file edit accidentally damaged a kept test — re-inspect.

- [ ] **Step 7: Stage by explicit path and commit**

```bash
cd /Users/jneerdael/Scripts/nexio
for i in 1 2 3 4 5 6 7 8 9 10; do
  if [ ! -f .git/index.lock ]; then
    if git add app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactiveHydrationPipelineTest.kt; then
      echo "staged on attempt $i"; break
    fi
  fi
  sleep 2
done
git status -sb | head -3
for i in 1 2 3 4 5 6 7 8 9 10; do
  if [ ! -f .git/index.lock ]; then
    if git commit -m "$(cat <<'EOF'
test(home/hydration): drop composeHydratedHomeOverlaySnapshot tests

Three tests in HomeReactiveHydrationPipelineTest asserted that
composeHydratedHomeOverlaySnapshot applies overlay content onto rows +
hero items. Phase 3.6.5 (commit 63aa16286) turned that function into a
no-op pass-through — the typed authority (ResolvedDisplaySurface
Repository) owns overlay application now. The tests have no path to
pass and have been failing since Phase 3.6.5 shipped.

Deleted tests:
- "overlay snapshot composition updates visible card and hero without
  row reorder"
- "overlay snapshot composition respects disabled hero field groups"
- "overlay snapshot composition applies enabled hero field groups"

Surviving tests in this file (overlay observation key dedupe, two
republish-gate tests, hero-identity pass-through) still validate
behavior that exists.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"; then
      echo "committed on attempt $i"; break
    fi
  fi
  sleep 2
done
git status -sb | head -3
```

Verify `HomeReactiveHydrationPipelineTest.kt` is the only staged file. Other agents' working-tree changes (trailer subtitle work etc.) must remain untouched. Run `git diff --cached --stat` if you want a final pre-commit check.

---

## Task 2: Eliminate producer-path double-publish to `HOME_SURFACE_KEY`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`

Today the producer emission path does (in order):
1. `applyHomeProducerEmissionToUiPipeline(displayRows, fullRows, heroItems)` (line ~3076)
2. … which calls `applyHomeResolvedRowsToUiPipeline(...)` (shared core, line ~3254)
3. … which at lines ~3277-3286 builds `toResolvedDisplayItems(composedSnapshot.displayRows)` (NON-enriched) and `publishResolvedItems(...)` to `HOME_SURFACE_KEY`.
4. Control returns to the producer; lines ~3081-3091 build `toResolvedDisplayItemsEnriched(rows = _internalCatalogRows.value, overlaysByItemKey, idMappingStore)` (ENRICHED with cross-provider IDs) and call `publishResolvedItems(...)` to `HOME_SURFACE_KEY` again.

Step 4 overwrites step 3 (publishResolvedItems replaces by `(surfaceKey, profileId)`). Step 3 is wasted work.

Fix: add a `publishResolvedSurface: Boolean = true` parameter to the shared core. Cold-start callers take the default; producer caller passes `false`.

- [ ] **Step 1: Read the current core function**

```bash
sed -n '3254,3320p' app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
```

Confirm the function signature and the HOME_SURFACE_KEY publish block (lines 3271-3286).

- [ ] **Step 2: Modify the core's signature + gate the publish**

Edit `applyHomeResolvedRowsToUiPipeline`. Replace the signature block:

```kotlin
private fun HomeViewModel.applyHomeResolvedRowsToUiPipeline(
    displayRows: List<com.nexio.tv.domain.model.CatalogRow>,
    fullRows: List<com.nexio.tv.domain.model.CatalogRow>,
    heroItems: List<com.nexio.tv.domain.model.MetaPreview>
) {
```

with:

```kotlin
private fun HomeViewModel.applyHomeResolvedRowsToUiPipeline(
    displayRows: List<com.nexio.tv.domain.model.CatalogRow>,
    fullRows: List<com.nexio.tv.domain.model.CatalogRow>,
    heroItems: List<com.nexio.tv.domain.model.MetaPreview>,
    publishResolvedSurface: Boolean = true
) {
```

Then replace the publish block:

```kotlin
    // Restore the typed resolved-display authority from the snapshot. Without this,
    // cold-start snapshot restore would leave HOME_SURFACE_KEY empty until the next
    // updateCatalogRowsPipeline emission, so RPDB premium artwork and other
    // overlay-derived projections would only appear after the producer flips. By
    // publishing here we make snapshot-restore the first-paint authority on cold
    // start (Plan B Task 6c).
    val resolvedItemsForSnapshotSurface = HomeResolvedDisplayMapper.toResolvedDisplayItems(
        rows = composedSnapshot.displayRows,
        overlaysByItemKey = hydratedHomeOverlaysByItemKey.value,
        resolveTrailer = null
    )
    resolvedDisplaySurfaceRepository.publishResolvedItems(
        surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
        profileSession = profileManager.activeProfileSession.value,
        items = resolvedItemsForSnapshotSurface
    )
```

with:

```kotlin
    // Restore the typed resolved-display authority from the snapshot. Without this,
    // cold-start snapshot restore would leave HOME_SURFACE_KEY empty until the next
    // updateCatalogRowsPipeline emission, so RPDB premium artwork and other
    // overlay-derived projections would only appear after the producer flips. By
    // publishing here we make snapshot-restore the first-paint authority on cold
    // start (Plan B Task 6c).
    //
    // Producer emission path passes publishResolvedSurface = false. It immediately
    // overwrites HOME_SURFACE_KEY with the cross-provider-enriched mapper output
    // (HomeResolvedDisplayMapper.toResolvedDisplayItemsEnriched at the call site
    // in updateCatalogRowsPipeline), so the non-enriched publish here is wasted
    // work — N ResolvedDisplayItem allocations + a publishResolvedItems call
    // discarded within microseconds. Gating it lets the producer be the single
    // authoritative writer per producer emission.
    if (publishResolvedSurface) {
        val resolvedItemsForSnapshotSurface = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = composedSnapshot.displayRows,
            overlaysByItemKey = hydratedHomeOverlaysByItemKey.value,
            resolveTrailer = null
        )
        resolvedDisplaySurfaceRepository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileManager.activeProfileSession.value,
            items = resolvedItemsForSnapshotSurface
        )
    }
```

(All other code in the function stays as it is. Indentation matches the surrounding 4-space style; the `if` adds one nesting level.)

- [ ] **Step 3: Update `applyHomeProducerEmissionToUiPipeline` to pass `false`**

Locate via:

```bash
grep -n "fun HomeViewModel.applyHomeProducerEmissionToUiPipeline" app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
```

Replace the producer entry function body. The current shape is:

```kotlin
internal fun HomeViewModel.applyHomeProducerEmissionToUiPipeline(
    displayRows: List<com.nexio.tv.domain.model.CatalogRow>,
    fullRows: List<com.nexio.tv.domain.model.CatalogRow>,
    heroItems: List<com.nexio.tv.domain.model.MetaPreview>
) {
    applyHomeResolvedRowsToUiPipeline(
        displayRows = displayRows,
        fullRows = fullRows,
        heroItems = heroItems
    )
}
```

Replace with:

```kotlin
internal fun HomeViewModel.applyHomeProducerEmissionToUiPipeline(
    displayRows: List<com.nexio.tv.domain.model.CatalogRow>,
    fullRows: List<com.nexio.tv.domain.model.CatalogRow>,
    heroItems: List<com.nexio.tv.domain.model.MetaPreview>
) {
    // Plan B Task 6f.5 follow-up — producer path skips the core's HOME_SURFACE_KEY
    // publish. The producer's own publishResolvedItems call (immediately after
    // this returns, with toResolvedDisplayItemsEnriched and cross-provider ID
    // enrichment) overwrites the surface for the same (HOME_SURFACE_KEY,
    // profileId). The core's non-enriched publish would be discarded within
    // microseconds — gate it off here.
    applyHomeResolvedRowsToUiPipeline(
        displayRows = displayRows,
        fullRows = fullRows,
        heroItems = heroItems,
        publishResolvedSurface = false
    )
}
```

- [ ] **Step 4: Verify cold-start `applyHomeSnapshotToUiPipeline` is unchanged**

```bash
sed -n '3321,3380p' app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt | grep -n "applyHomeResolvedRowsToUiPipeline"
```

Expected: one line that does `applyHomeResolvedRowsToUiPipeline(displayRows = ..., fullRows = ..., heroItems = ...)` with no fourth argument — relies on the default `publishResolvedSurface = true`. If the cold-start call accidentally got a `publishResolvedSurface =` argument, revert that.

- [ ] **Step 5: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: On-device sanity check**

Install + cold-start + soak. Confirm rails still render (the producer's enriched publish must be the sole authoritative HOME_SURFACE_KEY writer for producer emissions to still work).

```bash
./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 10
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 60
adb -s 192.168.50.98:5555 logcat -d -t 5000 | grep -E "FATAL|ANR in com\.nexiodebug|Restored merged home snapshot|Persisted snapshot applied" | head -10
adb -s 192.168.50.98:5555 shell screencap -p /sdcard/post-followup-t2.png
adb -s 192.168.50.98:5555 pull /sdcard/post-followup-t2.png /tmp/post-followup-t2.png
```

Expected from the logcat grep:
- A `Persisted snapshot applied` or `Restored merged home snapshot` line with `rails=N hero=M` where N > 0 and M > 0.
- No `FATAL`, no `ANR in com.nexiodebug`.

Inspect `/tmp/post-followup-t2.png` visually — rails populated (the same Trakt Trending / TMDB Trending rails the user saw post-T11.5).

If rails are MISSING (typed surface decay returned), the producer's enriched publish is failing for some reason — re-enable the core's publish by removing Step 3's `publishResolvedSurface = false` while you investigate. Don't ship a broken producer.

- [ ] **Step 7: Capture a heap dump for allocation churn confirmation**

```bash
PID=$(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv)
adb -s 192.168.50.98:5555 shell am dumpheap "$PID" /data/local/tmp/heap-followup-t2.hprof
sleep 8
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap-followup-t2.hprof /tmp/heap-followup-t2.hprof
echo "Soaking 30s..."
sleep 30
adb -s 192.168.50.98:5555 shell am dumpheap "$PID" /data/local/tmp/heap-followup-t2-30.hprof
sleep 8
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap-followup-t2-30.hprof /tmp/heap-followup-t2-30.hprof
heaptrail --diff-from /tmp/heap-followup-t2.hprof --diff-to /tmp/heap-followup-t2-30.hprof --diff-by count --top 20 2>&1 | head -30
```

Expected: `com.nexio.tv.domain.model.ResolvedDisplayItem` delta drops relative to the pre-followup baseline (one fewer allocation pass per producer emission). The pre-followup baseline (the post-T11.5 heap pair, /tmp/heap-churn-t0.hprof → /tmp/heap-churn-t60.hprof) showed `ResolvedDisplayItem +1811 / 60s`. After this followup expect ~ half the allocation rate during steady-state churn (a single enriched mapper pass per emission instead of one non-enriched + one enriched). Cold-start hydration growth (the first ~60s after profile select while catalogs load) is unaffected because that growth is driven by *new* catalog content arriving, not by re-publishing the same content.

If the delta is essentially unchanged, the producer's emission cadence dominates and the wasted-publish savings are below the heap dump's resolution — that's acceptable, the fix is correct architecturally.

- [ ] **Step 8: Stage by explicit path and commit**

```bash
for i in 1 2 3 4 5 6 7 8 9 10; do
  if [ ! -f .git/index.lock ]; then
    if git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt; then
      echo "staged on attempt $i"; break
    fi
  fi
  sleep 2
done
git diff --cached --stat
for i in 1 2 3 4 5 6 7 8 9 10; do
  if [ ! -f .git/index.lock ]; then
    if git commit -m "$(cat <<'EOF'
perf(home/snapshot): producer path skips redundant HOME_SURFACE_KEY publish

Plan B 6f.5 follow-up. The producer-path emission was calling the shared
apply core (which publishes the typed surface via toResolvedDisplayItems,
non-enriched) and then immediately overwriting that surface with
toResolvedDisplayItemsEnriched (cross-provider ID enrichment from
idMappingStore). The non-enriched publish was discarded within
microseconds — N ResolvedDisplayItem allocations + one publishResolved
Items call wasted per producer emission.

- applyHomeResolvedRowsToUiPipeline takes a new
  publishResolvedSurface: Boolean = true parameter that gates the
  HOME_SURFACE_KEY publish block.
- applyHomeProducerEmissionToUiPipeline passes false (its caller does
  the enriched publish that wins anyway).
- Cold-start applyHomeSnapshotToUiPipeline keeps the default (true) —
  there is no follow-up enriched publish on the cold-start path, so
  the core must publish the snapshot to the typed authority itself.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"; then
      echo "committed on attempt $i"; break
    fi
  fi
  sleep 2
done
git status -sb | head -3
```

Verify only `HomeViewModelCatalogPipeline.kt` is in the commit.

---

## Self-Review

**Spec coverage:** Two items called out as 6f.5 follow-ups; one task each. ✓

**Placeholder scan:** No "TBD" / "appropriate" / "Similar to" / "fix it" patterns. Each step has either complete code blocks or exact commands with expected output. ✓

**Type consistency:**
- `publishResolvedSurface: Boolean` parameter name used consistently in Task 2 Steps 2, 3, 4.
- Function name `applyHomeResolvedRowsToUiPipeline` (shared core) consistent throughout.
- Function name `applyHomeProducerEmissionToUiPipeline` (producer entry) consistent.
- File paths verified against the post-6f.5 tree at commit `cba299e3d`.

**Verification gates:**
- Task 1 Step 6 confirms the 3 deleted tests are gone and 4 remaining tests pass.
- Task 2 Step 6 confirms rails still render on-device.
- Task 2 Step 7 measures allocation impact via heap diff.

**Open follow-ups intentionally NOT covered here** (not in scope):
- `AnimeIdMappingService` heap retention (~45 MiB, pre-existing, separate Anime ID streaming work tracked in `project_anr_fix_anime_id_map_2026_05_11.md`).
- Producer transient `Snapshot.rails` dedupe by `row.catalogId` (the persist log shows `rails=6` for an 82-catalog profile — possibly correct, possibly under-emitting; orthogonal to either follow-up here).
