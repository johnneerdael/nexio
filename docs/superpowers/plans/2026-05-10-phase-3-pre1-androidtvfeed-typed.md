# Phase 3.pre1 — AndroidTvFeedBrowserScreen consumes DetailRailItem

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the last `.toRailCardData()` legacy-adapter call site (`AndroidTvFeedBrowserScreen.kt:140`) with `DetailRailItem.fromMetaPreview(item)`, mirroring Phase 1D's CastDetailScreen migration. This is the standalone prerequisite before Phase 3 proper begins.

**Architecture:** AndroidTvFeed renders a remote feed as a grid of `List<MetaPreview>` (confirmed at `AndroidTvFeedBrowserViewModel.kt:21`). Semantically it's "browsing a system-recommended channel" — neither home-rail nor detail-context — but the rendering shape is identical to a detail-context filmography (`onNavigateToDetail(item.id, item.apiType, addonBaseUrl)`). Reusing `DetailRailItem` matches Phase 1D's pragmatic precedent: no new surface-specific projection where the shape already fits.

**Tech Stack:** Kotlin · Compose · existing `DetailRailItem.fromMetaPreview` factory (commit `d777c65b3`).

**Spec source:** `docs/superpowers/specs/2026-05-10-phase-3-catalog-pipeline-restructure-design.md` — sub-project 3.pre1.

---

## File Structure

### Modified files

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/ui/screens/AndroidTvFeedBrowserScreen.kt` | Replace `item.toRailCardData()` with `remember(item) { DetailRailItem.fromMetaPreview(item) }`. Add import for `DetailRailItem`. Drop `toRailCardData` import. |

### Untouched

- `AndroidTvFeedBrowserViewModel.kt` — `uiState.items: List<MetaPreview>` stays. The migration is at the consumer boundary; the VM still emits MetaPreview from the AndroidTv recommendations channel adapter (system-surface concern; out of scope for Phase 3's home-pipeline reshape).

---

## Task 1: Migrate `AndroidTvFeedBrowserScreen` call site

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/AndroidTvFeedBrowserScreen.kt`

- [ ] **Step 1: Read the call site**

Open `app/src/main/java/com/nexio/tv/ui/screens/AndroidTvFeedBrowserScreen.kt`. Find the `GridContentCard` call around line 140 (inside the `else -> { LazyVerticalGrid { itemsIndexed { ... } } }` branch):

```kotlin
itemsIndexed(
    items = uiState.items,
    key = { index, item -> "${item.id}_${item.apiType}_$index" }
) { index, item ->
    GridContentCard(
        item = item.toRailCardData(),
        posterCardStyle = PosterCardDefaults.Style,
        focusRequester = if (index == focusedItemIndex) restoreFocusRequester else null,
        onFocused = { focusedItemIndex = index },
        onClick = {
            onNavigateToDetail(
                item.id,
                item.apiType,
                uiState.addonBaseUrl.orEmpty()
            )
        }
    )
}
```

- [ ] **Step 2: Migrate the call site**

Replace the block above with:

```kotlin
itemsIndexed(
    items = uiState.items,
    key = { index, item -> "${item.id}_${item.apiType}_$index" }
) { index, item ->
    val cardData = remember(item) { DetailRailItem.fromMetaPreview(item) }
    GridContentCard(
        item = cardData,
        posterCardStyle = PosterCardDefaults.Style,
        focusRequester = if (index == focusedItemIndex) restoreFocusRequester else null,
        onFocused = { focusedItemIndex = index },
        onClick = {
            onNavigateToDetail(
                item.id,
                item.apiType,
                uiState.addonBaseUrl.orEmpty()
            )
        }
    )
}
```

The `onClick` callback still reads `item.id` / `item.apiType` directly from the `MetaPreview` — those signatures are unchanged.

- [ ] **Step 3: Update imports**

- **Add**: `import com.nexio.tv.ui.screens.detail.DetailRailItem`
- **Add (if not already present)**: `import androidx.compose.runtime.remember`
- **Remove (if no other reference)**: `import com.nexio.tv.ui.components.toRailCardData`

Verify with grep:

```bash
grep "toRailCardData" app/src/main/java/com/nexio/tv/ui/screens/AndroidTvFeedBrowserScreen.kt
```

Expected: zero matches after edit.

- [ ] **Step 4: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

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

Expected: empty output.

- [ ] **Step 7: Verify the last `.toRailCardData()` caller is gone**

```bash
grep -rn "\\.toRailCardData()" app/src/main --include="*.kt"
```

Expected: zero matches. (If any remain, they are surfaces not enumerated in the Phase 1A-G + 3.pre1 set — flag in the status report, do not fix.)

- [ ] **Step 8: Commit**

EXPLICITLY stage the 1 file. DO NOT use `git add -A` (CLAUDE.md hard rule #7, commit `90e8ccb27`). Working tree currently has uncommitted other-workstream files (`TvdbMetadataServiceOriginalLanguageTest.kt`, `MetaDetailsTvdbAdvancedMetadataTest.kt`, `media` submodule, untracked plan markdowns). DO NOT touch them.

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/AndroidTvFeedBrowserScreen.kt
git status -sb  # verify only the 1 intended file staged
git diff --cached --stat
git commit -m "feat(tv-feed): AndroidTvFeedBrowserScreen consumes DetailRailItem directly

Phase 3.pre1 of the home-MetaPreview-elimination spec (commit fa05a1fe5).
Last .toRailCardData() legacy-adapter call site migrated. AndroidTvFeed
renders a system-recommended channel grid; its render shape matches
Phase 1D's CastDetailScreen (filmography of movies/shows), so reuses
DetailRailItem.fromMetaPreview rather than introducing a new surface-
specific projection.

remember(item) amortizes the per-recomposition projection allocation
(consistent with Phases 1A-D's pattern).

7 of 7 .toRailCardData() call sites now removed:
  1F (SeeAll), 1G (GridHome), 1D (Cast), 1A (Search), 1B (Library),
  1C (Organization), 1E (AndroidTvFeed).

After this commit, the MetaPreview.toRailCardData() legacy adapter in
RailCardData.kt has zero callers in app/src/main. Phase 4 will delete
the adapter alongside the other deprecated helpers.

Sets the stage for Phase 3 proper: catalog pipeline producer flip with
authority-owned item data."
```

- [ ] **Step 9: Verify post-state**

```bash
git show <commit-sha> --stat  # confirm only 1 file in commit
git status  # confirm other-workstream files remain modified/untracked
```

Expected:
- `git show` shows only `AndroidTvFeedBrowserScreen.kt` (~5 line change).
- `git status` still shows `TvdbMetadataServiceOriginalLanguageTest.kt`, `MetaDetailsTvdbAdvancedMetadataTest.kt`, `media` submodule, plan markdowns as modified/untracked.

---

## Self-review

**1. Spec coverage:**

The Phase 3 design spec's sub-project 3.pre1 says: "Phase 1E migration. `AndroidTvFeedBrowserScreen` last `.toRailCardData()` call site. Drops one more legacy-adapter caller before Phase 3 starts. Same shape as Phase 1A-D — small, standalone, no risk."

Task 1 implements exactly that. Step 7 (verify no `.toRailCardData()` callers remain) is the spec's "drops one more legacy-adapter caller" acceptance.

**2. Placeholder scan:** None. Each step has exact file path, exact code, exact command, exact expected output.

**3. Type consistency:**
- `DetailRailItem.fromMetaPreview(meta: MetaPreview): DetailRailItem` — defined in commit `d777c65b3`, used here per the spec's reuse recommendation.
- `RailCardData` interface (commit `e4511e6b8`) — `DetailRailItem` already implements it (commit `de16841c4`).
- `androidx.compose.runtime.remember(key)` — standard Compose API.

No type drift.

**4. Risk:** LOW. Single-file consumer migration; no producer changes; same pattern as 6 prior Phase 1 successes.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-10-phase-3-pre1-androidtvfeed-typed.md`. Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent for the task, two-stage review, fast iteration.
2. **Inline Execution** — execute tasks in this session using executing-plans.

Which approach?
