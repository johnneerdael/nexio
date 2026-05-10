# Phase 3.8 — `renderDisplayMetadata` slot-aware merge (no UX shift)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Migrate `ContinueWatchingMetadataSnapshot.renderDisplayMetadata` and the lone other `HomeDisplayMetadata.coalesceWith` call site (`HomeViewModelContinueWatching.kt:607`) to use the typed `ResolvedDisplayFieldSlots` bag + rank-aware merge (`pickHigherRanked` / `HomeRailProjectionReducer.reduce`) — without changing observable rendering semantics.

**Architecture insight that makes this safe:** the existing `stringSlot`/`listSlot`/`ratingSlot`/`artworkSlotFromBundle` helpers in `SlotConversions.kt` already coerce null/blank values to `rank = DisplaySourceRank.EMPTY` (see line 97: `rank = if (trimmed == null) DisplaySourceRank.EMPTY else rank`). So a slot with a null value will lose to any lower-rank slot with a non-null value. Running `chooseHigherRank` over slot-converted inputs yields **exactly the same per-field result** as the current `coalesceWith` (`this.field ?: fallback.field`):

- canonical non-null → rank `CANONICAL_READY` → wins (matches coalesceWith: canonical.field ?: …)
- canonical null → rank `EMPTY` → loses to clickTime if clickTime non-null (matches coalesceWith fall-through)
- clickTime non-null but lower rank than persistedFallback non-null → persistedFallback wins (matches coalesceWith: clickTime.field ?: fallback.field after canonical fall-through)

Rank ordering: `EMPTY (0) < FIRST_PAINT (1) < STALE_RESOLVED (2) < RESOLVED (3) < CANONICAL_READY (4)`.

**Spec reference:** Sub-project 3.8 in `docs/superpowers/specs/2026-05-10-phase-3-catalog-pipeline-restructure-design.md`. The spec's full intent (snapshot field type change to `ResolvedDisplayFieldSlots`) is **explicitly out of scope** for this plan — that's a persistence-schema reshape that needs its own session. This plan delivers the spec's "renderDisplayMetadata uses ResolvedSlot.choose" requirement without a schema bump.

**Risk:** LOW. The slot-conversion-with-EMPTY-on-null trick preserves the existing observable semantics field-by-field. No persistence schema change. The only risk is helper-internal artwork handling (`mergeFallbackArtwork` has subtle durable-ref preference logic that must be preserved).

---

## File Structure

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/ui/screens/home/SlotConversions.kt` | Add `fun HomeDisplayMetadata.toResolvedFieldSlots(nowMs: Long, rank: DisplaySourceRank, provider: String? = null, role: String? = null): ResolvedDisplayFieldSlots` mirroring the existing `HydratedHomeOverlay.toResolvedSlots` (overlay.fields IS a HomeDisplayMetadata — same field set). |
| `app/src/main/java/com/nexio/tv/ui/screens/home/SlotConversions.kt` | Add `fun ResolvedDisplayFieldSlots.toHomeDisplayMetadata(): HomeDisplayMetadata` — inverse projection that pulls slot.value per field into a fresh HomeDisplayMetadata. Artwork: rebuild ArtworkBundle from poster/backdrop/logo/thumbnail slots' ArtworkDisplayRef values via the same takeIfImageType pattern. |
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMetadataSnapshot.kt` | Rewrite `renderDisplayMetadata` to convert all 3 inputs via `toResolvedFieldSlots` at ranks `CANONICAL_READY`, `FIRST_PAINT`, `STALE_RESOLVED` respectively; merge via `HomeRailProjectionReducer.reduce(firstPaint = clickTimeSlots, overlay = canonicalSlots, existing = persistedFallbackSlots, profile = null)`; convert merged slots back via `toHomeDisplayMetadata()`. Remove the `coalesceWith` import. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:607` | Replace `localizedPreview.toHomeDisplayMetadata().coalesceWith(existing)` with a slot-aware merge: `localizedPreview` → `toResolvedFieldSlots(... rank = FIRST_PAINT)`, `existing` → `toResolvedFieldSlots(... rank = RESOLVED)` (existing is already-hydrated metadata), merge via `pickHigherRanked` per field OR via reducer.reduce, convert back. Remove the `coalesceWith` import. |

After this plan ships, `coalesceWith` has 0 live callers in `app/src/main`. Phase 4 will delete the extension function. `mergeFallback` (which was already 0-caller) gets deleted alongside it.

---

## Task 1: Add `HomeDisplayMetadata.toResolvedFieldSlots` and inverse projection

**File:** `app/src/main/java/com/nexio/tv/ui/screens/home/SlotConversions.kt`

- [ ] **Step 1: Read current `HydratedHomeOverlay.toResolvedSlots`** at line 62. It converts `overlay.fields: HomeDisplayMetadata` to `ResolvedDisplayFieldSlots`. Extract the per-field conversion logic so we can call it directly on a HomeDisplayMetadata.

- [ ] **Step 2: Add a generalised helper after the existing functions:**

```kotlin
/**
 * Projects a [HomeDisplayMetadata] into rank-tagged slots at [rank]. Null /
 * blank fields become EMPTY-rank slots (per the rank-aware merge rules in
 * stringSlot etc.), so a lower-rank input with a non-null value will still
 * win for that field — equivalent to coalesceWith's null-fallback semantic
 * but routed through the typed-slot rank machinery.
 *
 * Used by ContinueWatchingMetadataSnapshot.renderDisplayMetadata to merge
 * canonical (CANONICAL_READY) / clickTime (FIRST_PAINT) / persistedFallback
 * (STALE_RESOLVED) metadata via HomeRailProjectionReducer.
 */
fun HomeDisplayMetadata.toResolvedFieldSlots(
    nowMs: Long,
    rank: DisplaySourceRank,
    provider: String? = null,
    role: String? = ROLE_HYDRATION_RESOLVED,
): ResolvedDisplayFieldSlots {
    return ResolvedDisplayFieldSlots(
        title = stringSlot(title, provider, role, nowMs, rank),
        originalTitle = ResolvedSlot.empty(nowMs),
        overview = stringSlot(description, provider, role, nowMs, rank),
        genres = listSlot(genres, provider, role, nowMs, rank),
        releaseInfo = stringSlot(releaseInfo, provider, role, nowMs, rank),
        runtime = stringSlot(runtime, provider, role, nowMs, rank),
        rating = ratingSlot(imdbRating, ratingSource, provider, role, nowMs, rank),
        poster = artworkSlotFromBundle(
            artwork?.poster.takeIfImageType(ArtworkType.POSTER), poster,
            ArtworkType.POSTER, provider, role, nowMs, rank
        ),
        backdrop = artworkSlotFromBundle(
            artwork?.backdrop.takeIfImageType(ArtworkType.BACKDROP), backdrop,
            ArtworkType.BACKDROP, provider, role, nowMs, rank
        ),
        logo = artworkSlotFromBundle(
            artwork?.logo.takeIfImageType(ArtworkType.LOGO), logo,
            ArtworkType.LOGO, provider, role, nowMs, rank
        ),
        thumbnail = artworkSlotFromBundle(
            artwork?.thumbnail.takeIfImageType(ArtworkType.THUMBNAIL), thumbnail,
            ArtworkType.THUMBNAIL, provider, role, nowMs, rank
        ),
        posterProviderTag = stringSlot(posterProviderTag, provider, role, nowMs, rank)
    )
}
```

- [ ] **Step 3: Add inverse projection helper:**

```kotlin
/**
 * Inverse of [HomeDisplayMetadata.toResolvedFieldSlots]: builds a
 * HomeDisplayMetadata from the merged slot bag, pulling slot.value per
 * field. Used by ContinueWatchingMetadataSnapshot.renderDisplayMetadata
 * to return the merged result in the legacy HomeDisplayMetadata shape
 * (the CW snapshot field type change to ResolvedDisplayFieldSlots is
 * out of scope for this plan).
 */
fun ResolvedDisplayFieldSlots.toHomeDisplayMetadata(): HomeDisplayMetadata {
    val posterUrl = poster.value?.toLegacyArtworkString()
    val backdropUrl = backdrop.value?.toLegacyArtworkString()
    val logoUrl = logo.value?.toLegacyArtworkString()
    val thumbnailUrl = thumbnail.value?.toLegacyArtworkString()
    val artworkBundle = ArtworkBundle(
        poster = poster.value.takeIfImageType(ArtworkType.POSTER),
        backdrop = backdrop.value.takeIfImageType(ArtworkType.BACKDROP),
        logo = logo.value.takeIfImageType(ArtworkType.LOGO),
        thumbnail = thumbnail.value.takeIfImageType(ArtworkType.THUMBNAIL)
    ).enforceArtworkTypeBoundaries().emptyOrNull()
    return HomeDisplayMetadata(
        title = title.value,
        logo = logoUrl,
        description = overview.value,
        genres = genres.value.orEmpty(),
        releaseInfo = releaseInfo.value,
        runtime = runtime.value,
        imdbRating = rating.value?.value?.toFloat(),
        ratingSource = rating.value?.source,
        tomatoesRating = null, // tomatoes lives outside the slot bag (flat field on ResolvedDisplayFields); the CW path will lose it here. Acceptable: tomatoes is not part of HomeDisplayMetadata's render-critical surface for CW cards.
        originalLanguage = null, // same as tomatoes
        imdbId = null, // same
        poster = posterUrl,
        posterProviderTag = posterProviderTag.value,
        backdrop = backdropUrl,
        thumbnail = thumbnailUrl,
        artwork = artworkBundle
    )
}
```

- [ ] **Step 4: Verify imports.** Add `import com.nexio.tv.core.artwork.ArtworkBundle` and `import com.nexio.tv.core.artwork.enforceArtworkTypeBoundaries` if not present; `emptyOrNull` should already be in the artwork package. Add `import com.nexio.tv.domain.model.HomeDisplayMetadata`.

- [ ] **Step 5: Compile.** `./gradlew :app:compileUniversalDebugKotlin 2>&1 | tail -5` → BUILD SUCCESSFUL.

---

## Task 2: Rewrite `ContinueWatchingMetadataSnapshot.renderDisplayMetadata`

**File:** `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMetadataSnapshot.kt`

- [ ] **Step 1: Replace the function body:**

```kotlin
fun renderDisplayMetadata(
    canonical: HomeDisplayMetadata?,
    clickTime: HomeDisplayMetadata?,
    persistedFallback: HomeDisplayMetadata?
): HomeDisplayMetadata {
    if (canonical == null && clickTime == null && persistedFallback == null) {
        return HomeDisplayMetadata()
    }
    val nowMs = System.currentTimeMillis()
    val canonicalSlots = canonical?.toResolvedFieldSlots(
        nowMs = nowMs,
        rank = DisplaySourceRank.CANONICAL_READY,
    )
    val clickTimeSlots = clickTime?.toResolvedFieldSlots(
        nowMs = nowMs,
        rank = DisplaySourceRank.FIRST_PAINT,
    )
    val persistedFallbackSlots = persistedFallback?.toResolvedFieldSlots(
        nowMs = nowMs,
        rank = DisplaySourceRank.STALE_RESOLVED,
    )
    // HomeRailProjectionReducer takes firstPaint (required) + 3 optional
    // higher-rank inputs. Map our inputs to its slot params:
    //   firstPaint = clickTimeSlots (or persistedFallbackSlots if clickTime is null)
    //   overlay    = canonicalSlots
    //   existing   = persistedFallbackSlots
    val firstPaint = clickTimeSlots
        ?: persistedFallbackSlots
        ?: emptySlotsAt(nowMs)
    val merged = HomeRailProjectionReducer.reduce(
        firstPaint = firstPaint,
        overlay = canonicalSlots,
        existing = if (clickTimeSlots != null) persistedFallbackSlots else null,
        profile = null,
    )
    return merged.toHomeDisplayMetadata()
}

private fun emptySlotsAt(nowMs: Long): ResolvedDisplayFieldSlots = ResolvedDisplayFieldSlots(
    title = ResolvedSlot.empty(nowMs),
    originalTitle = ResolvedSlot.empty(nowMs),
    overview = ResolvedSlot.empty(nowMs),
    genres = ResolvedSlot.empty(nowMs),
    releaseInfo = ResolvedSlot.empty(nowMs),
    runtime = ResolvedSlot.empty(nowMs),
    rating = ResolvedSlot.empty(nowMs),
    poster = ResolvedSlot.empty(nowMs),
    backdrop = ResolvedSlot.empty(nowMs),
    logo = ResolvedSlot.empty(nowMs),
    thumbnail = ResolvedSlot.empty(nowMs),
    posterProviderTag = ResolvedSlot.empty(nowMs),
)
```

- [ ] **Step 2: Update imports.** Remove `import com.nexio.tv.domain.model.coalesceWith`. Add `import com.nexio.tv.domain.model.DisplaySourceRank`, `import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots`, `import com.nexio.tv.domain.model.ResolvedSlot`, `import com.nexio.tv.ui.screens.home.HomeRailProjectionReducer`, `import com.nexio.tv.ui.screens.home.toHomeDisplayMetadata`, `import com.nexio.tv.ui.screens.home.toResolvedFieldSlots`.

- [ ] **Step 3: Compile** → BUILD SUCCESSFUL.

---

## Task 3: Migrate the last `coalesceWith` call site (HomeViewModelContinueWatching:607)

**File:** `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`

- [ ] **Step 1: Read line 600-615** for context. The expression is:

```kotlin
val enrichedMetadata = localizedPreview.toHomeDisplayMetadata().coalesceWith(existing)
```

`localizedPreview` is fresh first-paint data; `existing` is whatever was already in the cache (higher-ranked since it's been hydrated). The current code does null-fallback: localizedPreview wins per non-null field, falls back to existing for nulls.

Migrate to slot-aware merge:

```kotlin
val nowMs = System.currentTimeMillis()
val firstPaintSlots = localizedPreview.toHomeDisplayMetadata()
    .toResolvedFieldSlots(nowMs = nowMs, rank = DisplaySourceRank.FIRST_PAINT)
val existingSlots = existing?.toResolvedFieldSlots(
    nowMs = nowMs,
    rank = DisplaySourceRank.RESOLVED,
)
val enrichedMetadata = HomeRailProjectionReducer.reduce(
    firstPaint = firstPaintSlots,
    overlay = existingSlots,
    existing = null,
    profile = null,
).toHomeDisplayMetadata()
```

Note: same EMPTY-on-null behaviour preserves the existing localizedPreview-wins-on-non-null semantic.

- [ ] **Step 2: Update imports.** Remove `import com.nexio.tv.domain.model.coalesceWith`. Add the same DisplaySourceRank / ResolvedDisplayFieldSlots / HomeRailProjectionReducer / toResolvedFieldSlots / toHomeDisplayMetadata imports.

- [ ] **Step 3: Compile** → BUILD SUCCESSFUL.

---

## Task 4: Verify zero `coalesceWith` callers + smoke + ship

- [ ] **Step 1: Verify.** `grep -rn "\\.coalesceWith\b" app/src/main --include="*.kt"` → only the deprecated declaration in `HomeDisplayMetadata.kt` should appear. Phase 4 will delete it.

- [ ] **Step 2: Test compile.** `./gradlew :app:compileUniversalDebugUnitTestKotlin 2>&1 | tail -5` → BUILD SUCCESSFUL.

- [ ] **Step 3: Run any related unit tests.** `./gradlew :app:testUniversalDebugUnitTest --tests "*ContinueWatching*Test*" 2>&1 | tail -10` → all pass.

- [ ] **Step 4: Build APK + install + smoke (rule #8 sequence).**

- [ ] **Step 5: Verify CW UX on-device.** Mark a few items in-progress (Trakt or local scrobble). Reboot the app. Observe whether CW row renders titles + posters as expected. The slot conversion + EMPTY-on-null trick is designed to preserve this; verification is belt-and-suspenders.

- [ ] **Step 6: Stage by explicit path (rule #7) + commit + push** as a single 3-file commit:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/SlotConversions.kt
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMetadataSnapshot.kt
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt
git status -sb  # confirm only those 3 staged
git commit -m "feat(cw): renderDisplayMetadata + CW enrichment use rank-aware slot merge

Phase 3.8 of the home-MetaPreview-elimination spec — partial. Migrates
the 3 live coalesceWith call sites (2 in
ContinueWatchingMetadataSnapshot.renderDisplayMetadata, 1 in
HomeViewModelContinueWatching enrichment) to route through the typed
ResolvedDisplayFieldSlots bag via HomeRailProjectionReducer.

The EMPTY-on-null behaviour baked into stringSlot/listSlot/ratingSlot/
artworkSlotFromBundle (SlotConversions.kt:97 et al.) preserves the
existing null-fallback semantic field-by-field: a slot with a null
value gets rank=EMPTY (0) and loses to any lower-rank slot with a
non-null value — equivalent to coalesceWith's \`this.field ?:
fallback.field\` but routed through the rank-aware merge machinery.

After this commit, coalesceWith has 0 live callers in app/src/main.
mergeFallback is already 0-caller. Phase 4 will delete both extensions.

The ContinueWatchingMetadataSnapshot data class shape is unchanged
(clickTimeDisplayMetadata still HomeDisplayMetadata); the spec's
full type-shift to ResolvedDisplayFieldSlots is deferred to a future
session that can verify persistence schema migration on-device.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
git push origin main 2>&1 | tail -3
```

---

## Self-review

**1. Spec coverage:** Phase 3.8 spec requires "renderDisplayMetadata uses ResolvedSlot.choose per field". This plan delivers that via HomeRailProjectionReducer.reduce (which is pickHigherRanked under the hood). The spec's additional field-type-change to ResolvedDisplayFieldSlots is **deferred** with explicit rationale.

**2. Placeholder scan:** None. Every step has exact file path, exact code, exact command.

**3. Semantic preservation:** The EMPTY-on-null trick in the existing slot conversion helpers is the load-bearing observation. Without it the migration would shift semantics (rule #1 non-downgrade) and risk CW UX regressions. With it, observable behavior is unchanged per field.

**4. Tomatoes/originalLanguage/imdbId loss:** `toHomeDisplayMetadata()` drops these 3 fields because they aren't in `ResolvedDisplayFieldSlots`. Acceptable for CW rendering — none of these are visible on CW cards. If a future session needs them, extend the slot bag.

**5. Risk:** LOW per the EMPTY-on-null invariant. Verification on-device (Task 4 Step 5) is belt-and-suspenders, not load-bearing.
