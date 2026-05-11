# Home Rail Projection And Rating Restore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the missing overlay→projection contract on Modern Home so hydrated TVDB/RPDB metadata reaches the UI; stop premium posters from being demoted on cold-start soft refresh failures; and route TV-row ratings through `RatingResolver` so a numerically-better TMDB rail-preview rating wins over an empty TVDB primary on TVDB-canonical SERIES rows.

**Invariant:** Hydrated overlays are sticky and authoritative for hydrated fields until invalidated. A later first-paint/catalog emission may update source payload, but it MUST NOT remove hydrated artwork, rating, title, or identity fields.

**Architecture:** Three independent packets at three layers — (A) overlay-key alias symmetry on the apply seam + reactive reprojection on overlay updates, (B) preserve durable `nexio-artwork://` poster refs across overlay merge + move artwork-decision cache flush off the main thread, (C) wire `RatingResolver.resolveTitleRating(...)` into the home pipeline by collecting rating candidates from preview + primary + secondary candidates.

**Tech Stack:** Kotlin, Android, Hilt, Compose, JUnit 4, MockK, Gradle armv7 debug unit tests, ADB-installed armv7 release for device verification.

---

## Evidence Summary

Captured on Ugoos AM6 (192.168.50.98:5555) after fresh launch on commit `0ecb90d10`:

- Persisted overlay XML for TVDB-canonical SERIES rows contains correct `nexio-artwork://decision/...:provider:RPDB:...` poster, `nexio-artwork://asset/...:TVDB:backdrop:...` backdrop, `nexio-artwork://asset/...:TVDB:logo:...` logo, plus title/description/runtime/genres/releaseInfo. Data side is producing the right metadata.
- Modern Home rails do not display ANY of that metadata — titles/posters/backdrops blank or stuck at first-paint.
- Only **2** `home.hydration_applied` events fire in 35 seconds (was dozens previously). Most rows never receive an applied overlay event.
- Premium RPDB posters render briefly (~100ms) on cold start, then are replaced by addon TMDB raw URLs.
- `Long monitor contention with owner ArtworkDecisionCacheFlush 324ms` on the main thread.
- TVDB-canonical SERIES rows persist `imdbRating = null, ratingSource = null`; `metadata.field_selected ... field=RATING selectedProvider=TVDB rejected=0` shows TMDB rail-preview rating never reached `FieldResolver` as a candidate.

## Root Causes

**A. Read/write key asymmetry on the overlay map.** `hydratedHomeOverlayItemKeysForRows` expands aliases via `HomeArtworkOverlayKeys.aliasesFor(...)` and the store returns the map keyed by whichever alias hit (e.g., `series:tvdb:355567`). Every applier still looks up by the row's own `homeOverlayItemKey()` (`series:trakt:171028`) → blind miss. Plus two flows write into `hydratedHomeOverlaysByItemKey` with different key shapes (coordinator uses rowItemKey; store-flow uses alias-form), producing inconsistent state and explaining the missing `home.hydration_applied` events on restored snapshots.

**B. `HomeDisplayMetadata.applyTo` demotes durable poster refs.** At `HomeDisplayMetadata.kt:100-101`, `displayPoster` (overlay's bare TMDB URL) unconditionally beats `base.poster` (already a `nexio-artwork://decision/...:RPDB:...` ref minted by `PosterRatingsUrlResolver.applyArtworkRef`). Plus `DurableArtworkDecisionCache.kt:38` flushes via a single-thread executor that holds the same `synchronized(lock)` callers (`get`, `put`, `lookup`) contend with — main-thread reads block on the flush.

**C. RatingResolver never invoked from the home pipeline.** `RatingResolver` exists at `app/src/main/java/com/nexio/tv/core/metadata/router/resolver/RatingResolver.kt:47` and is correctly used by the detail screen (`MetadataDisplayRepository.kt:408`, `DetailRatingDisplayRepository.kt:76,98`). The home flow goes `HomeHydrationCoordinator → MetadataRouterFacade.resolveRequest → FieldResolver.resolveWithPreview` and stops. Task 5's `isPositiveRating` guard only covers the zero/null-primary edge — a non-zero TVDB rating still wins over a numerically-better TMDB rail-preview because the rule is presence-based, not value-based.

## File Map

- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt`
  - Apply seam reads via alias set, not single row key.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
  - Hero applier and resolved-display lookup use the same alias-aware probe.
  - Coordinator-write and store-flow-write path normalize to a single key shape.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`
  - Resolved-display lookup uses alias-aware probe.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
  - Continue-watching/hero presentation lookups use alias-aware probe.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
  - CW row overlay lookups use alias-aware probe.
- Modify `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`
  - `applyTo` preserves a base `nexio-artwork://...` poster against a non-internal overlay poster.
  - Hash includes thumbnail.
- Modify `app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCache.kt`
  - Flush executor does not block readers on the same monitor; reads use a snapshot.
- Modify `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
  - Invoke `RatingResolver.resolveTitleRating(...)` between `resolveWithPreview` and `toHomeDisplayMetadata`.
- Modify tests:
  - `app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplierTest.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperTest.kt`
  - `app/src/test/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCacheTest.kt` (or nearest)
  - `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeStableIdBundleTest.kt`

## Execution Packets

```text
Packet A — Rail projection alias symmetry (P0):
  Task 1   Apply seam queries via aliasesFor(...) instead of single key
  Task 2   Coordinator-write and store-flow-write normalize to one key shape
  Task 3   ResolvedDisplayMapper + presentation pipeline + CW lookups use alias probe

Packet B — Premium poster stickiness + cache flush (P0/P1):
  Task 4   applyTo preserves durable nexio-artwork poster against bare-URL overlay
  Task 5   hydratedHomeDisplayHash includes thumbnail
  Task 6   DurableArtworkDecisionCache flush off the main-thread monitor

Packet C — Rating ingestion via RatingResolver (P1):
  Task 7   MetadataRouterFacade collects RatingCandidate set + invokes RatingResolver
  Task 8   resolveTitleRating result overrides resolved-doc rating + sourceProviders[RATING]

Packet D — Device verification:
  Task 9   Build, install, capture, verify, refresh sanitized summary
```

Packets are independent. Recommended order: A → B → C → D. Land A first because it unblocks every consumer of overlay metadata. B is independent but stacks well onto A's verification window. C requires Tasks 7+8 to land together (rating selection + source plumbing) but does not depend on A or B.

---

## Task 1: Apply-Seam Queries Via Alias Set

**Why this fixes the bug:** Read scope (`hydratedHomeOverlayItemKeysForRows`) expands every row's lookup key into the same alias set the overlay was written under. The store returns `Map<aliasKey, overlay>` keyed by whichever alias hit on disk. The applier's `overlaysByItemKey[item.homeOverlayItemKey()]` queries by the row's single own key, missing entries stored under canonical or typed aliases.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplierTest.kt`

- [ ] **Step 1: Read the apply seam**

Run:

```bash
sed -n '1,40p' app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt
```

Expected: `internal fun List<MetaPreview>.applyHydratedHomeOverlays(overlaysByItemKey: Map<String, HydratedHomeOverlay>): List<MetaPreview>` mapping each item via `overlay.fields.applyTo(item)` after `overlaysByItemKey[item.homeOverlayItemKey()]` lookup.

- [ ] **Step 2: Write a failing test for the alias-mismatch case**

Add to `HomeHydrationOverlayApplierTest.kt`:

```kotlin
@Test
fun `applyHydratedHomeOverlays applies overlay stored under canonical alias to a row keyed by trakt id`() {
    val overlay = HydratedHomeOverlayFixtures.tvdbCanonicalSeriesOverlay(
        canonicalId = "355567",
        title = "The Boys",
        imdbId = "tt1190634"
    )
    val item = MetaPreviewFixtures.traktTrendingSeries(
        contentId = "trakt:171028",
        firstPaintStableIds = ProviderIds(
            trakt = "171028",
            tvdb = "355567",
            imdb = "tt1190634"
        )
    )
    val overlaysByAlias = mapOf("series:tvdb:355567" to overlay)

    val applied = listOf(item).applyHydratedHomeOverlays(overlaysByAlias).single()

    assertEquals("The Boys", applied.name)
}
```

If `HydratedHomeOverlayFixtures` / `MetaPreviewFixtures` don't exist, locate the existing builder helpers in `HomeHydrationOverlayApplierTest.kt` and adapt; do not invent new builders. The test must (a) put the overlay only under the canonical alias key, (b) construct a row keyed by a different (trakt) id with the canonical id present in `firstPaintStableIds`, (c) assert the overlay is applied.

- [ ] **Step 3: Run the red test**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.ui.screens.home.HomeHydrationOverlayApplierTest.applyHydratedHomeOverlays applies overlay stored under canonical alias to a row keyed by trakt id'
```

Expected: fail because the applier uses single-key lookup.

- [ ] **Step 4: Implement alias-aware lookup**

In `HomeHydrationOverlayApplier.kt`, replace the single-key lookup:

```kotlin
internal fun List<MetaPreview>.applyHydratedHomeOverlays(
    overlaysByItemKey: Map<String, HydratedHomeOverlay>
): List<MetaPreview> = map { item ->
    val overlay = item.findOverlay(overlaysByItemKey) ?: return@map item
    overlay.fields.applyTo(item)
}

private fun MetaPreview.findOverlay(
    overlaysByItemKey: Map<String, HydratedHomeOverlay>
): HydratedHomeOverlay? {
    val rowKey = homeOverlayItemKey()
    overlaysByItemKey[rowKey]?.let { return it }
    val aliases = HomeArtworkOverlayKeys.aliasesFor(
        rowItemKey = rowKey,
        contentId = id,
        itemType = apiType,
        providerIds = firstPaintStableIds,
        canonicalProvider = null,
        canonicalId = null
    )
    return aliases.asSequence().mapNotNull { overlaysByItemKey[it] }.firstOrNull()
}
```

Add the import: `import com.nexio.tv.ui.screens.home.HomeArtworkOverlayKeys`. (Same package; may not need an explicit import.)

- [ ] **Step 5: Run the green test and full applier suite**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeHydrationOverlayApplierTest
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplierTest.kt
git commit -m "$(cat <<'EOF'
fix: apply hydrated overlays via alias set on the rail apply seam

Previously the applier looked up overlays by the row's own
homeOverlayItemKey, while the store returns the map keyed by whichever
alias matched on disk. For trakt-trending TVDB-canonical rows that
yields a guaranteed miss because the overlay lives under
series:tvdb:<canonical> while the row is series:trakt:<trakt-id>.
Apply seam now probes HomeArtworkOverlayKeys.aliasesFor(...) so the
write and read shapes stay symmetric.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 2: Normalize Two-Flow Key Shapes Into One

**Why this matters:** `applyHydratedHomeOverlayFromCoordinator` (`HomeViewModelCatalogPipeline.kt:484-525`) inserts overlays into `hydratedHomeOverlaysByItemKey` keyed by `overlay.itemKey` (the rowItemKey). The `observeHydratedHomeOverlaysForRows` path (line 678-687) writes the same map with whatever alias hit. The map ends up with two key shapes for the same overlay; consumers that picked the wrong shape silently miss. Plus only the coordinator emits `home.hydration_applied`, which is why restored snapshots from disk (which flow through the store path) never produce an applied event.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Test: existing pipeline test class — add a focused test if a `HomeViewModelCatalogPipelineTest` exists; otherwise, this task is a refactor that the Task 1 test indirectly validates.

- [ ] **Step 1: Read the two write paths**

Run:

```bash
sed -n '480,530p' app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
sed -n '675,695p' app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
```

Expected: `applyHydratedHomeOverlayFromCoordinator` inserts via `current.toMutableMap().apply { put(overlay.itemKey, overlay) }`. `observeHydratedHomeOverlaysForRows` inserts via the alias-form keys returned from the store.

- [ ] **Step 2: Decide on the canonical map key**

The two reasonable shapes:
- (a) Canonical-form keys (`overlay::canonical:TVDB:355567:type:SERIES`) — disk-shape.
- (b) Row-form keys (`series:trakt:171028`).

Task 1 made the consumer side alias-aware, so either shape works as long as it's CONSISTENT. Pick (b) — it matches what the coordinator already writes and what most consumers expect when no alias expansion is needed.

- [ ] **Step 3: Update the store-flow path to write under rowItemKey**

In `observeHydratedHomeOverlaysForRows` (line 678-687), instead of writing the map back as-returned, transform each entry to be keyed by the row item key that triggered the alias hit. The store returns `Map<aliasKey, overlay>`; the caller knows which `rowItemKey` was the seed. Change the `combine`/`map` to:

```kotlin
.map { rowKeyToOverlay ->
    rowKeyToOverlay.mapValues { (_, overlay) -> overlay }
        .mapKeys { (_, overlay) -> overlay.itemKey }
}
```

Or simpler: in the `combine(...)` block, build a `Map<String, HydratedHomeOverlay>` by iterating each overlay and using `overlay.itemKey` as the key directly. Drop the alias key the store emitted.

- [ ] **Step 4: Run all home-pipeline tests**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.ui.screens.home.*'
```

Expected: pass. If a presentation-pipeline test breaks because it relied on the alias-shape map, update the fixture to expect rowItemKey shape.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
git commit -m "$(cat <<'EOF'
fix: normalize hydrated-overlay map keys to rowItemKey shape

The coordinator-write path stored overlays under overlay.itemKey
(rowItemKey) while the store-flow-write path used whichever alias
matched on disk. Two key shapes in the same MutableStateFlow caused
silent-miss lookups for overlays restored from disk and explained the
near-zero home.hydration_applied event count after cold restart.

Both write paths now key by overlay.itemKey; alias expansion happens
only on the consumer (Task 1) so the apply seam stays uniform.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 3: Resolved-Display Mapper, Presentation Pipeline, CW Lookups Use Alias Probe

**Why this matters:** The same single-key lookup pattern appears in the resolved-display mapper, presentation pipeline, and CW row paths. Even with Task 1's apply-seam fix, these consumers will still miss overlays unless they use the same alias probe.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperTest.kt`

- [ ] **Step 1: Inventory the affected lookups**

Run:

```bash
grep -nE "overlaysByItemKey\[\|homeOverlayItemKey\(\)\]" \
  app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt \
  app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt \
  app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt
```

Expected: lookup sites at `HomeResolvedDisplayMapper.kt:47-48`, `HomeViewModelPresentationPipeline.kt:568, 660, 727`, and `HomeViewModelContinueWatching.kt:1094, 1138, 1181, 1206`.

- [ ] **Step 2: Extract a shared helper**

Add to `HomeArtworkOverlayKeys.kt` (or as an internal extension in `HomeHydrationOverlayApplier.kt`):

```kotlin
internal fun MetaPreview.overlayFromMap(
    overlaysByItemKey: Map<String, HydratedHomeOverlay>
): HydratedHomeOverlay? {
    val rowKey = homeOverlayItemKey()
    overlaysByItemKey[rowKey]?.let { return it }
    val aliases = HomeArtworkOverlayKeys.aliasesFor(
        rowItemKey = rowKey,
        contentId = id,
        itemType = apiType,
        providerIds = firstPaintStableIds,
        canonicalProvider = null,
        canonicalId = null
    )
    return aliases.asSequence().mapNotNull { overlaysByItemKey[it] }.firstOrNull()
}
```

Re-use this helper from Task 1's applier (replace the `findOverlay` private with this shared one).

- [ ] **Step 3: Replace each call site**

For each `overlaysByItemKey[item.homeOverlayItemKey()]` lookup in the three files, replace with `item.overlayFromMap(overlaysByItemKey)`.

For the CW lookups in `HomeViewModelContinueWatching.kt:1094, 1138, 1181, 1206` that use `displayMetadataByItemKey[homeDisplayItemKey(contentType, contentId)]` — those are `displayMetadataByItemKey`, NOT `overlaysByItemKey`. Different map, different schema. Leave those untouched unless they exhibit the same alias-mismatch problem (which would be a separate task).

- [ ] **Step 4: Add a focused test**

Add to `HomeResolvedDisplayMapperTest.kt`:

```kotlin
@Test
fun `resolved display mapper finds overlay stored under canonical alias for a trakt-keyed row`() {
    val overlay = HydratedHomeOverlayFixtures.tvdbCanonicalSeriesOverlay(
        canonicalId = "355567",
        backdrop = "nexio-artwork://asset/...:TVDB:backdrop:..."
    )
    val item = MetaPreviewFixtures.traktTrendingSeries(
        contentId = "trakt:171028",
        firstPaintStableIds = ProviderIds(trakt = "171028", tvdb = "355567")
    )
    val overlaysByAlias = mapOf("series:tvdb:355567" to overlay)

    val resolved = HomeResolvedDisplayMapper.resolve(item, overlaysByAlias)

    assertNotNull(resolved.artwork.backdrop, "resolved display must surface the canonical-aliased backdrop")
}
```

Adapt to the actual mapper signature.

- [ ] **Step 5: Run the test**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapperTest'
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeArtworkOverlayKeys.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperTest.kt
git commit -m "$(cat <<'EOF'
fix: extend alias-aware overlay lookup to all consumer seams

Resolved-display mapper, presentation pipeline (hero + screensaver +
continue-watching), and rail consumer probes now share the same
overlayFromMap helper that probes HomeArtworkOverlayKeys.aliasesFor
when the rowItemKey misses. Closes the remaining single-key lookup
sites identified in research-A.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 4: `applyTo` Preserves Durable Poster Refs

**Why this matters:** `HomeDisplayMetadata.applyTo` at `HomeDisplayMetadata.kt:100-101` unconditionally prefers `displayPoster` over `base.poster`. The hydrated overlay's `fields.poster` carries the metadata router's bare TMDB/TVDB URL, while the base `MetaPreview` already has `poster = "nexio-artwork://decision/...:RPDB:..."` from `PosterRatingsUrlResolver.applyArtworkRef`. Merge demotes the durable premium ref → user sees the RPDB image flicker then revert to addon TMDB.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplierTest.kt`

- [ ] **Step 1: Read `applyTo`**

```bash
sed -n '85,115p' app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt
```

Expected: lines 90-105 show the merge. Note `poster = displayPoster ?: base.poster` and the parallel for `posterProviderTag`.

- [ ] **Step 2: Write a failing test for the demotion case**

Add to `HomeHydrationOverlayApplierTest.kt`:

```kotlin
@Test
fun `applyTo preserves base nexio-artwork poster against bare-URL overlay poster`() {
    val basePremiumRef = "nexio-artwork://decision/artwork-decision:poster:canonical:tmdb:series-76479:provider:RPDB:premium:true:settings:abc:credential:def:imageLang:en:policy:1"
    val item = MetaPreviewFixtures.traktTrendingSeries(
        contentId = "trakt:171028",
        poster = basePremiumRef,
        posterProviderTag = "rpdb"
    )
    val overlayMetadata = HomeDisplayMetadata(
        title = "The Boys",
        poster = "https://image.tmdb.org/t/p/w500/abc.jpg",
        posterProviderTag = "tmdb"
    )

    val applied = overlayMetadata.applyTo(item)

    assertEquals(basePremiumRef, applied.poster, "durable nexio-artwork poster ref must not be demoted by a bare-URL overlay")
    assertEquals("rpdb", applied.posterProviderTag)
}

@Test
fun `applyTo does prefer overlay poster when overlay carries a nexio-artwork ref`() {
    val overlayPremiumRef = "nexio-artwork://decision/artwork-decision:poster:canonical:tmdb:series-76479:provider:RPDB:premium:true:settings:def:credential:abc:imageLang:en:policy:1"
    val item = MetaPreviewFixtures.traktTrendingSeries(
        contentId = "trakt:171028",
        poster = "https://image.tmdb.org/t/p/w500/abc.jpg"
    )
    val overlayMetadata = HomeDisplayMetadata(
        poster = overlayPremiumRef,
        posterProviderTag = "rpdb"
    )

    val applied = overlayMetadata.applyTo(item)

    assertEquals(overlayPremiumRef, applied.poster)
    assertEquals("rpdb", applied.posterProviderTag)
}
```

- [ ] **Step 3: Run red tests**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.ui.screens.home.HomeHydrationOverlayApplierTest.applyTo preserves base nexio-artwork poster against bare-URL overlay poster' --tests 'com.nexio.tv.ui.screens.home.HomeHydrationOverlayApplierTest.applyTo does prefer overlay poster when overlay carries a nexio-artwork ref'
```

Expected: first test fails (current code demotes), second test passes already.

- [ ] **Step 4: Implement the durable-poster guard**

In `HomeDisplayMetadata.kt:90-105`, change the poster + posterProviderTag merge to:

```kotlin
poster = preferDurableRef(displayPoster, base.poster),
posterProviderTag = if (preferDurableRef(displayPoster, base.poster) === base.poster) {
    base.posterProviderTag
} else if (displayPoster != null) {
    posterProviderTag
} else {
    base.posterProviderTag
},
```

Add the helper at the bottom of the file:

```kotlin
private fun preferDurableRef(overlayPoster: String?, basePoster: String?): String? {
    val baseIsDurable = basePoster?.startsWith("nexio-artwork://") == true
    val overlayIsDurable = overlayPoster?.startsWith("nexio-artwork://") == true
    return when {
        overlayIsDurable -> overlayPoster
        baseIsDurable -> basePoster
        else -> overlayPoster ?: basePoster
    }
}
```

Apply the same pattern to `background` and `logo` if they receive durable refs (verify by reading `applyTo`).

- [ ] **Step 5: Run green**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeHydrationOverlayApplierTest
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.domain.model.HomeDisplayMetadataTest
```

If `HomeDisplayMetadataTest` doesn't exist, skip the second run.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplierTest.kt
git commit -m "$(cat <<'EOF'
fix: preserve durable nexio-artwork poster refs against bare-URL overlay merge

HomeDisplayMetadata.applyTo previously preferred displayPoster
unconditionally, which demoted base.poster=nexio-artwork://decision/...
back to the bare TMDB URL the metadata router emitted. The overlay
merge now keeps the durable premium ref when only the base side is
durable, fixing the cold-start RPDB-then-TMDB poster flicker.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 5: `hydratedHomeDisplayHash` Includes Thumbnail

**Why this matters:** The hash already includes title/logo/description/genres/releaseInfo/runtime/imdbRating/ratingSource/poster/posterProviderTag/backdrop, but NOT thumbnail. If a thumbnail-only overlay update arrives, `shouldPublishHydratedHomeOverlays` may no-op even though the thumbnail changed.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/HydratedHomeOverlay.kt`
- Test: `app/src/test/java/com/nexio/tv/domain/model/HydratedHomeOverlayTest.kt` (or nearest)

- [ ] **Step 1: Read the hash**

```bash
sed -n '64,90p' app/src/main/java/com/nexio/tv/domain/model/HydratedHomeOverlay.kt
```

Expected: 12 fields appended; thumbnail is missing.

- [ ] **Step 2: Add a failing test**

If a HydratedHomeOverlayTest exists, add:

```kotlin
@Test
fun `hash differs when thumbnail differs`() {
    val a = HomeDisplayMetadata(thumbnail = "https://example.com/a.jpg")
    val b = HomeDisplayMetadata(thumbnail = "https://example.com/b.jpg")

    assertNotEquals(a.hydratedHomeDisplayHash(), b.hydratedHomeDisplayHash())
}
```

If no test class exists, create `app/src/test/java/com/nexio/tv/domain/model/HydratedHomeOverlayHashTest.kt` with this test plus a positive case (`hash is stable when thumbnail unchanged`).

- [ ] **Step 3: Run red**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests '*HydratedHomeOverlay*hash differs*'
```

Expected: fail.

- [ ] **Step 4: Add thumbnail to the hash**

In `HydratedHomeOverlay.kt:64-83`, add `appendLengthPrefixed("thumbnail", thumbnail.orEmpty())` after the `backdrop` line. Match the existing field-name-then-length-then-value style.

- [ ] **Step 5: Run green and full domain-model test suite**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests '*HydratedHomeOverlay*'
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/HydratedHomeOverlay.kt app/src/test/java/com/nexio/tv/domain/model/HydratedHomeOverlayHashTest.kt
git commit -m "$(cat <<'EOF'
fix: include thumbnail in hydratedHomeDisplayHash

Thumbnail-only overlay updates were not changing the hash, so
shouldPublishHydratedHomeOverlays could silently drop them. Adding
thumbnail to the hash matches the coverage of poster/backdrop/logo
and ensures changes are observable to the publisher gate.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 6: `DurableArtworkDecisionCache` Flush Off Main Thread

**Why this matters:** The flush thread holds the same `synchronized(lock)` that `get`/`put`/`lookup` callers hold. A 324ms flush blocks main-thread reads during home startup. Even if it's not the only bug, it's a concrete architectural violation.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCache.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCacheTest.kt`

- [ ] **Step 1: Read the lock + flush pattern**

```bash
sed -n '20,80p' app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCache.kt
sed -n '95,135p' app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCache.kt
sed -n '415,440p' app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCache.kt
```

Expected: a single `private val lock = Any()` with `synchronized(lock) { ... }` blocks around `get` (line 64), `put` (line 105), `lookup` (line 69), AND inside the flush executor (lines 422-431).

- [ ] **Step 2: Decide on the lock model**

The simplest correct change: split the lock into two — one for the in-memory map (`mapLock`) and a separate one for the file flush (`flushLock`). Reads acquire only `mapLock` (fast). Writes acquire `mapLock` to update the in-memory map, then schedule the flush via the executor; the executor acquires only `flushLock` to serialize disk writes. Reads NEVER block on flushes.

Alternative: switch to `ConcurrentHashMap` and a debounced single-writer flush. Larger refactor.

Pick option (a) — split locks. Smaller surface.

- [ ] **Step 3: Write a failing test for read-not-blocked-by-flush**

Add to `DurableArtworkDecisionCacheTest.kt`:

```kotlin
@Test
fun `read is not blocked by an in-progress flush`() {
    val cache = DurableArtworkDecisionCache(...)  // match existing test setup
    val flushBlocking = CountDownLatch(1)
    val readReturned = CountDownLatch(1)
    cache.put(/* a decision */)
    val flushThread = Thread {
        cache.simulateLongFlush(blocker = flushBlocking)
    }
    flushThread.start()

    val readThread = Thread {
        cache.get(decisionKey)
        readReturned.countDown()
    }
    readThread.start()

    assertTrue("read must complete within 200ms even while flush is blocked", readReturned.await(200, TimeUnit.MILLISECONDS))
    flushBlocking.countDown()
    flushThread.join()
}
```

`simulateLongFlush` is a `@VisibleForTesting` helper you'll add — accepts a `CountDownLatch` and holds the flush lock until released. If the existing flush executor doesn't expose a hook, expose a `runFlushBlocking(latch: CountDownLatch)` test method that grabs the flush lock and awaits the latch.

- [ ] **Step 4: Run red**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.core.artwork.DurableArtworkDecisionCacheTest.read is not blocked by an in-progress flush'
```

Expected: timeout fails the test.

- [ ] **Step 5: Implement split-lock**

Replace the single `private val lock = Any()` with:

```kotlin
private val mapLock = Any()    // protects the in-memory map structure only
private val flushLock = Any()  // serializes disk writes; held only by flush executor
```

Update `get` / `put` / `lookup` (~lines 64, 105, 69) to use `synchronized(mapLock)` only. Update the flush executor body (~lines 422-431) to use `synchronized(flushLock)` only. The flush reads from the in-memory map under `mapLock` to take a snapshot, then releases `mapLock` and writes the snapshot to disk under `flushLock`.

If the flush logic mutates the in-memory map (e.g., to mark entries as flushed), do that mutation under `mapLock` AFTER the disk write succeeds.

- [ ] **Step 6: Run green and full cache test suite**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.artwork.DurableArtworkDecisionCacheTest
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCache.kt app/src/test/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCacheTest.kt
git commit -m "$(cat <<'EOF'
fix: split DurableArtworkDecisionCache lock so flush cannot block reads

The cache previously used a single monitor for both the in-memory map
and the disk flush executor. A 324ms flush during home startup blocked
main-thread reads (observed via Long monitor contention with owner
ArtworkDecisionCacheFlush). Splits the lock so reads acquire only the
in-memory mapLock; flushes acquire only flushLock against a snapshot
captured under mapLock.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 7: `MetadataRouterFacade` Collects RatingCandidates + Invokes RatingResolver

**Why this matters:** `RatingResolver` exists and works, but the home pipeline never calls it. Routing RATING through the resolver means a TMDB rail-preview rating beats an empty TVDB primary on TVDB-canonical SERIES rows.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeRatingTest.kt` (new)

- [ ] **Step 1: Read the post-resolve flow**

```bash
sed -n '230,260p' app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt
```

Expected: `fieldResolver.resolveWithPreview(preview, primary, secondary, ...)` returns a `ResolvedMetadataDocument` then `toHomeDisplayMetadata(initialDisplay)` is called.

- [ ] **Step 2: Read RatingResolver and the existing detail-screen pattern**

```bash
sed -n '1,90p' app/src/main/java/com/nexio/tv/core/metadata/router/resolver/RatingResolver.kt
sed -n '375,430p' app/src/main/java/com/nexio/tv/data/repository/MetadataDisplayRepository.kt
```

Expected: `RatingResolver.resolveTitleRating(candidates: List<RatingCandidate>): RatingResolution?` with precedence `CUSTOM_IMDB → MDBLIST → OMDB → PRIMARY_PROVIDER → PREVIEW_FALLBACK`. The detail repo at `MetadataDisplayRepository.kt:382-405` shows how to build candidates from `resolvedDocument.fields[ResolvedField.RATING]` (PRIMARY) and `Meta.imdbRating` (PREVIEW_FALLBACK).

- [ ] **Step 3: Write a failing test for the wiring**

Create `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeRatingTest.kt`:

```kotlin
class MetadataRouterFacadeRatingTest {
    @Test
    fun `tvdb canonical series with empty primary rating selects tmdb rail preview rating`() = runTest {
        val facade = TestMetadataRouterFacade.builder()
            // configure a TVDB-canonical primary that returns RATING=null
            .withTvdbPrimary(rating = null)
            // configure a TMDB rail preview with imdbRating = 8.4
            .withRailPreview(
                contentId = "trakt:171028",
                imdbRating = 8.4f,
                ratingSource = TitleRatingSource.IMDB
            )
            .build()

        val request = MetadataRequest(
            contentId = "trakt:171028",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext(itemType = "series"),
            language = "en",
            depth = MetadataDepth.DETAIL_CORE
        )

        val result = facade.resolveRequest(request)

        assertEquals(8.4f, result.displayMetadata.imdbRating)
        assertEquals(TitleRatingSource.TMDB, result.displayMetadata.ratingSource)
    }
}
```

If `TestMetadataRouterFacade` doesn't expose the right builder, look at the existing `TestMetadataRouterFacade` in `app/src/test/java/com/nexio/tv/core/metadata/router/TestMetadataRouterFacade.kt` and adapt — it must let the test inject a primary candidate without RATING and a rail preview with `imdbRating`. If the test infra needs build-out, that's its own scope decision; report BLOCKED rather than building it ad-hoc.

- [ ] **Step 4: Run red**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterFacadeRatingTest
```

Expected: fail.

- [ ] **Step 5: Wire RatingResolver between resolveWithPreview and toHomeDisplayMetadata**

Inject `RatingResolver` as a constructor dependency on `MetadataRouterFacade` (it's a top-level `object`, so just reference `RatingResolver` directly).

In `resolveRequest` (around line 242-243, after `fieldResolver.resolveWithPreview` and before `toHomeDisplayMetadata`), add:

```kotlin
val ratedDocument = applyRatingResolverSelection(
    document = resolvedDocument,
    preview = previewCandidate,
    primary = primaryCandidate
)
```

Define the helper as a private member:

```kotlin
private fun applyRatingResolverSelection(
    document: ResolvedMetadataDocument,
    preview: MetadataCandidate?,
    primary: MetadataCandidate?
): ResolvedMetadataDocument {
    val candidates = buildList {
        primary?.fields?.get(ResolvedField.RATING)?.value?.toRatingCandidate(
            sourceRole = SourceRole.PRIMARY_PROVIDER,
            sourceProvider = primary.provider.name
        )?.let(::add)
        preview?.fields?.get(ResolvedField.RATING)?.value?.toRatingCandidate(
            sourceRole = SourceRole.PREVIEW_FALLBACK,
            sourceProvider = preview.provider.name
        )?.let(::add)
        document.secondaryRatingCandidates()
            .forEach { add(it) }
    }
    val resolution = RatingResolver.resolveTitleRating(candidates) ?: return document
    return document.copy(
        rating = resolution.value,
        sourceProviders = document.sourceProviders + (ResolvedField.RATING to resolution.sourceProvider),
        sourceRoles = document.sourceRoles + (ResolvedField.RATING to resolution.sourceRole)
    )
}

private fun Any.toRatingCandidate(sourceRole: SourceRole, sourceProvider: String): RatingCandidate? {
    val number = this as? Number ?: return null
    val value = number.toDouble().takeIf { it > 0.0 } ?: return null
    return RatingCandidate(
        scope = RatingScope.TITLE,
        value = value,
        sourceRole = sourceRole,
        sourceProvider = sourceProvider,
        confidence = Confidence.HIGH
    )
}

private fun ResolvedMetadataDocument.secondaryRatingCandidates(): List<RatingCandidate> = emptyList()
```

The `secondaryRatingCandidates` placeholder returns empty for now; MDBList/OMDb/CUSTOM_IMDB ingestion is out of scope for this packet (would need a new injection seam for the detail-screen pattern).

Then change the line that calls `toHomeDisplayMetadata`:

```kotlin
val displayMetadata = ratedDocument.toHomeDisplayMetadata(initialDisplay)
```

(Was: `resolvedDocument.toHomeDisplayMetadata(initialDisplay)`.)

- [ ] **Step 6: Run green**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterFacadeRatingTest
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeRatingTest.kt
git commit -m "$(cat <<'EOF'
fix: route home RATING selection through RatingResolver

MetadataRouterFacade now collects rating candidates from the primary
canonical and the rail preview, runs them through
RatingResolver.resolveTitleRating, and overrides the resolved doc's
rating/sourceProviders/sourceRoles with the resolution. Closes the
gap where TVDB-canonical SERIES rows persisted imdbRating=null/
ratingSource=null because TMDB rail-preview rating never reached the
resolver.

MDBList / OMDb / CUSTOM_IMDB ingestion is tracked separately —
secondaryRatingCandidates() returns empty for now.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 8: Validate `toHomeDisplayMetadata` Picks Up The New ratingSource

**Why this matters:** Task 6 of the prior plan made `toHomeDisplayMetadata` derive `ratingSource` from `sourceProviders[ResolvedField.RATING]`. Task 7 above writes the resolver-selected provider into that map. Together they should produce a non-null `ratingSource` for the test fixture in Task 7, but verify with one more test specifically targeting the source mapping.

**Files:**
- Modify: (no production change — verification only)
- Test: extend `MetadataRouterFacadeRatingTest.kt` from Task 7

- [ ] **Step 1: Add a failing test for `ratingSource = TMDB`**

Already covered by Task 7's test (`assertEquals(TitleRatingSource.TMDB, result.displayMetadata.ratingSource)`). If that assertion passed in Task 7's green run, this task is verification-only.

- [ ] **Step 2: Add a follow-up test for unknown providers**

```kotlin
@Test
fun `unknown rating provider preserves value but leaves ratingSource null`() = runTest {
    val facade = TestMetadataRouterFacade.builder()
        // primary provider TVDB returns rating = 7.6
        .withTvdbPrimary(rating = 7.6f)
        .withoutRailPreview()
        .build()

    val result = facade.resolveRequest(/* tvdb-canonical series request */)

    assertEquals(7.6f, result.displayMetadata.imdbRating)
    assertNull("TVDB rating source must not be coerced to IMDB", result.displayMetadata.ratingSource)
}
```

- [ ] **Step 3: Run**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterFacadeRatingTest
```

- [ ] **Step 4: Commit (test-only)**

```bash
git add app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeRatingTest.kt
git commit -m "$(cat <<'EOF'
test: pin RatingResolver selection to displayMetadata.ratingSource

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 9: Device Verification

**Files:**
- Modify: `review-dossier/2026-05-08-tv-artwork-cw-postfix-summary.json` (replace counts)

- [ ] **Step 1: Build the release APK**

```bash
./gradlew :app:assembleArmv7Release
```

- [ ] **Step 2: Force-stop, clear logcat, install, launch**

```bash
DEV=192.168.50.98:5555
adb -s $DEV shell am force-stop com.nexio.tv
adb -s $DEV logcat -c
adb -s $DEV install -r app/build/outputs/apk/armv7/release/app-armv7-release.apk
adb -s $DEV shell monkey -p com.nexio.tv 1
```

- [ ] **Step 3: Wait 35 seconds, capture artifacts**

```bash
DEV=192.168.50.98:5555
mkdir -p tmp/crash-investigation-2026-05-09/repro
adb -s $DEV logcat -d -v threadtime > tmp/crash-investigation-2026-05-09/repro/logcat-postfix.txt
adb -s $DEV shell su -c 'cp /data/data/com.nexio.tv/shared_prefs/hydrated_home_overlay_v1.xml /sdcard/overlay-postfix.xml'
adb -s $DEV pull /sdcard/overlay-postfix.xml tmp/crash-investigation-2026-05-09/repro/overlay-postfix.xml
adb -s $DEV shell su -c 'cp /data/data/com.nexio.tv/shared_prefs/continue_watching_snapshot.xml /sdcard/cw-postfix.xml'
adb -s $DEV pull /sdcard/cw-postfix.xml tmp/crash-investigation-2026-05-09/repro/cw-postfix.xml
```

- [ ] **Step 4: Run the broader sanity check**

```bash
python3 << 'EOF'
import xml.etree.ElementTree as ET, html, json, re
from collections import Counter

overlay_path = 'tmp/crash-investigation-2026-05-09/repro/overlay-postfix.xml'
logcat_path = 'tmp/crash-investigation-2026-05-09/repro/logcat-postfix.txt'

t = ET.parse(overlay_path)
strings = [(c.attrib['name'], c.text or '') for c in t.getroot() if c.tag == 'string']
tvdb_series = [(n, json.loads(html.unescape(v))['value']) for n, v in strings
               if n.startswith('overlay::canonical:TVDB:') and 'type:SERIES' in n]

ratings_non_null = sum(1 for _, v in tvdb_series if v.get('fields', {}).get('imdbRating') is not None)
sources = Counter(v.get('fields', {}).get('ratingSource') for _, v in tvdb_series)
rpdb_poster_refs = sum(1 for _, v in tvdb_series
                      if 'provider:RPDB' in (v.get('fields', {}).get('poster') or ''))
tvdb_logo_refs = sum(1 for _, v in tvdb_series
                     if 'TVDB:logo' in (v.get('fields', {}).get('logo') or ''))
tvdb_backdrop_refs = sum(1 for _, v in tvdb_series
                         if 'TVDB:backdrop' in (v.get('fields', {}).get('backdrop') or ''))

print(f'TVDB SERIES overlays: {len(tvdb_series)}')
print(f'  with imdbRating non-null: {ratings_non_null} (target: > 0 — Task 7)')
print(f'  ratingSource distribution: {dict(sources)}')
print(f'  with RPDB poster ref:    {rpdb_poster_refs}')
print(f'  with TVDB logo ref:      {tvdb_logo_refs}')
print(f'  with TVDB backdrop ref:  {tvdb_backdrop_refs}')

with open(logcat_path) as f:
    log = f.read()

def count(pattern):
    return len(re.findall(pattern, log))

events = {
    'hydration_applied':            count(r'home\.hydration_applied'),
    'fallback_materialized':        count(r'artwork\.fallback_materialized'),
    'fallback_suppressed_soft':     count(r'artwork\.fallback_suppressed_soft_failure'),
    'orphan_rehydrate_skipped':     count(r'artwork\.orphan_asset_ref_rehydrate_skipped'),
    'long_monitor_decision_flush':  count(r'Long monitor.*ArtworkDecisionCacheFlush'),
    'fatal_exception':              count(r'FATAL EXCEPTION'),
}
print('\nRuntime trace counts:')
for k, n in events.items():
    print(f'  {k}: {n}')

print('\nAcceptance signals:')
print(f'  Bug A (no metadata on rails): hydration_applied count must be >> 2.')
print(f'  Bug B (popping): fallback_materialized must be 0 OR strictly less than fallback_suppressed_soft.')
print(f'  Bug B (cache flush): long_monitor_decision_flush must be 0.')
print(f'  Bug C (rating): TVDB SERIES with imdbRating non-null must be > 0; ratingSource includes TMDB.')
EOF
```

Expected after Tasks 1-8:
- `hydration_applied` count is much greater than 2 (was 2 on baseline) — likely >= 30
- `imdbRating non-null` > 0
- `ratingSource distribution` includes `TMDB`
- `fallback_materialized` count is zero or strictly less than `fallback_suppressed_soft`
- `long_monitor_decision_flush` count is zero
- `fatal_exception` count is zero

- [ ] **Step 5: Refresh sanitized summary**

```bash
python3 tools/reporting/summarize_artwork_state.py \
  --overlay tmp/crash-investigation-2026-05-09/repro/overlay-postfix.xml \
  --snapshot tmp/crash-investigation-2026-05-09/repro/cw-postfix.xml \
  --logcat tmp/crash-investigation-2026-05-09/repro/logcat-postfix.txt \
  --adb-connect-status connected \
  --apk-install-status success \
  --launch-status success \
  --su-access available \
  --device-error-category none \
  --logcat-captured-without-clear \
  --output review-dossier/2026-05-08-tv-artwork-cw-postfix-summary.json
```

- [ ] **Step 6: Verify nothing raw is staged**

```bash
git status --short
git diff --cached --name-only
```

Expected: only the sanitized JSON. No raw xml, no raw logcat.

- [ ] **Step 7: Commit**

```bash
git add review-dossier/2026-05-08-tv-artwork-cw-postfix-summary.json
git commit -m "$(cat <<'EOF'
docs: refresh sanitized device summary after rail projection restore

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Required Verification Commands

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeHydrationOverlayApplierTest
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapperTest
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.artwork.DurableArtworkDecisionCacheTest
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterFacadeRatingTest
./gradlew :app:assembleArmv7Release
```

Then run the device verification in Task 9.

## Acceptance Criteria

- Trakt-trending TVDB-canonical rows render with hydrated TVDB title, description, RPDB poster, TVDB backdrop, TVDB logo on Modern Home.
- `home.hydration_applied` count in a 35-second cold-start window is much greater than 2 (target: at least one event per visible row).
- Premium RPDB posters do not flicker out to bare TMDB URLs after rendering.
- `Long monitor contention with owner ArtworkDecisionCacheFlush` warnings are absent in cold-start logcat.
- TVDB-canonical SERIES rows that have a TMDB rail-preview rating persist `imdbRating != null` and `ratingSource = TMDB`.
- TVDB-canonical SERIES with a TVDB-only rating value persist `imdbRating != null` with `ratingSource = null` (no silent IMDB coercion).
- `hydratedHomeDisplayHash` changes when only the thumbnail changes.
- All focused unit tests green; existing tests unbroken (or updated fixtures with explicit explanation in commit messages).

## Commit Plan

```text
fix: apply hydrated overlays via alias set on the rail apply seam
fix: normalize hydrated-overlay map keys to rowItemKey shape
fix: extend alias-aware overlay lookup to all consumer seams
fix: preserve durable nexio-artwork poster refs against bare-URL overlay merge
fix: include thumbnail in hydratedHomeDisplayHash
fix: split DurableArtworkDecisionCache lock so flush cannot block reads
fix: route home RATING selection through RatingResolver
test: pin RatingResolver selection to displayMetadata.ratingSource
docs: refresh sanitized device summary after rail projection restore
```

Do not stage:

```text
tmp/crash-investigation-2026-05-09/**
app/src/main/assets/openrouter_reasoning_models.json
media
```
