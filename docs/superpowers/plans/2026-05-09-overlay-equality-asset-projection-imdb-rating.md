# Overlay Equality, Asset Projection, And IMDB Rating Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop premium posters from flickering on every overlay re-stamp, restore TVDB logo/backdrop visibility on the hero panel, and surface IMDB ratings on TVDB-canonical SERIES rows by routing every home request through the existing bulk IMDB ratings integration.

**Architecture:** Three independent fixes at three layers — (B) `HydratedHomeOverlay` content-aware dedup so timestamp-only deltas don't invalidate the catalog signature, (A) invert `ArtworkLegacyProjection` to prefer the self-healing `nexio-artwork://decision/...` URI over the read-only `nexio-artwork://asset/...` URI, (C) wire `TitleRatingOverrideRepository` into `MetadataRouterFacade.applyRatingResolverSelection` so rail rows pick up `CUSTOM_IMDB` candidates from the bulk-IMDB API.

**Tech Stack:** Kotlin, Android, Hilt, Compose, JUnit 4, MockK, Gradle armv7 debug unit tests, ADB-installed armv7 release for device verification.

---

## Evidence Summary

Captured on Ugoos AM6 (192.168.50.98:5555) after fresh launch on commit `8b86bdb...` (Tasks 1+3+4+5+6+7 + version-flow debounce):

- App is responsive (debounce restored interactivity), but premium RPDB posters still flicker briefly then revert.
- 152 `home.hydration_applied` events in 35s for ~25 unique items — `tmdb:1198994` re-applied 6 times in 20s with **identical `displayHash`**, `cacheDecision=HIT_OR_LOCAL`, `networkExecuted=false`.
- 84 concurrent GCs in 35s, individual GCs freeing 22-35MB at a time, heap rising to 113MB.
- Persisted overlay XML carries `nexio-artwork://asset/...:TVDB:logo:urlHash:...` strings, but only ONE `artwork.orphan_asset_ref_rehydrate_skipped` event fires (rail cards have no logo slot in portrait mode; only hero panel asks for the logo URI).
- All 60 TVDB SERIES overlays persist `imdbRating: null, ratingSource: null` — `RATING fieldTrace: provider=TVDB, sourceRole=PRIMARY, rejectedCandidates=[]`.

## Root Causes (with file:line)

**B. Timestamp-driven equality cycles.** `HydratedHomeOverlay` (data class) has `updatedAtMs`/`staleAtMs`/`expiresAtMs` in its primary constructor (`app/src/main/java/com/nexio/tv/domain/model/HydratedHomeOverlay.kt:39-41`). The dedup at `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:507` (`if (current[overlay.itemKey] == overlay)`) NEVER short-circuits because each rebuild stamps a fresh `nowMs` (`HomeHydrationCoordinator.kt:217, 237-239`). The same pattern poisons `shouldPublishHydratedHomeOverlays` (line 456-459) and `buildCatalogComputationSignature` (around line 2545). Result: every cache-hit re-hydration mutates the in-memory map, invalidates the catalog signature, fires `scheduleUpdateCatalogRows()`, emits a fresh `CatalogRow` list, Coil sees a new `AsyncImagePainter` model, and cross-fades — perceived as the poster flickering.

**A. Asset URI promoted before bytes are materialized.** `ArtworkLegacyProjection.kt:5-8` emits `nexio-artwork://asset/<assetKey>` in preference to `nexio-artwork://decision/<decisionKey>` whenever `assetKey != null`. `MetadataArtworkDecisionResolver.kt:84-122` constructs the assetKey by hashing the candidate URL (line 105-125) but never calls `getOrFetch(decision)` to materialize bytes. The asset URI lands in the persisted overlay pointing at bytes nobody ever wrote. `NexioArtworkFetcher` for the asset path only calls `getExistingFile(assetKey)` (and `getOrRehydrateAsset(assetKey)` after the prior plan's Task 4) — but `getOrRehydrateAsset` requires an asset record store entry, and that entry is only written by `persistAssetRecordBestEffort` inside `fetchInternal`. Bytes that were never `getOrFetch`-ed are permanently dangling.

**C. Home pipeline never calls the bulk-IMDB ratings repository.** `TitleRatingOverrideRepository.titleRatingCandidates(preview, stableIdBundle, providerIds)` (`app/src/main/java/com/nexio/tv/data/repository/TitleRatingOverrideRepository.kt:22-52`) returns `List<RatingCandidate>` tagged `SourceRole.CUSTOM_IMDB` from the existing bulk IMDB integration (`api.nexioapp.org/v1/ratings/bulk`). It's used by `DetailRatingDisplayRepository` for the detail screen but NOT by `MetadataRouterFacade.applyRatingResolverSelection` (commit `4bbde86c4`). RatingResolver's precedence is `CUSTOM_IMDB → MDBLIST → OMDB → PRIMARY_PROVIDER → PREVIEW_FALLBACK`, so once we feed CUSTOM_IMDB candidates the resolver will pick them over an empty TVDB primary.

## File Map

- Modify `app/src/main/java/com/nexio/tv/domain/model/HydratedHomeOverlay.kt`
  - Add `fun HydratedHomeOverlay.contentEquals(other: HydratedHomeOverlay): Boolean` that ignores timestamp fields.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
  - Replace `current[overlay.itemKey] == overlay` with `existing.contentEquals(overlay)` at line 507.
  - Replace `current != next` in `shouldPublishHydratedHomeOverlays` with content-only equality.
  - In `buildCatalogComputationSignature` (~line 2545), hash overlays by their content key instead of value-equals on the data class.
- Modify `app/src/main/java/com/nexio/tv/core/artwork/ArtworkLegacyProjection.kt`
  - Invert: emit decision URI by default; only emit asset URI when explicitly requested with proof of bytes-on-disk.
- Modify `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
  - Inject `TitleRatingOverrideRepository`.
  - In `applyRatingResolverSelection`, suspend-call `titleRatingCandidates(...)` and add the returned list to the resolver candidates.
- Modify `app/src/main/java/com/nexio/tv/core/di/MetadataRouterModule.kt` (or the equivalent Hilt module that constructs `MetadataRouterFacade`) if the repository isn't already provided.
- Modify tests:
  - `app/src/test/java/com/nexio/tv/domain/model/HydratedHomeOverlayTest.kt` (or new `HydratedHomeOverlayContentEqualsTest.kt`)
  - `app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactiveHydrationPipelineTest.kt`
  - `app/src/test/java/com/nexio/tv/core/artwork/ArtworkLegacyProjectionTest.kt`
  - `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeRatingTest.kt` (or extend nearest neighbor)

## Execution Packets

```text
Packet B — Overlay equality stops timestamp-driven flicker (P0):
  Task 1   contentEquals helper on HydratedHomeOverlay
  Task 2   apply seam + publish gate use contentEquals
  Task 3   buildCatalogComputationSignature uses content hash

Packet A — Asset URI projection prefers decision (P0):
  Task 4   ArtworkLegacyProjection emits decision URI; asset URI only with bytes-on-disk proof

Packet C — IMDB ratings reach home pipeline (P1):
  Task 5   Inject TitleRatingOverrideRepository into MetadataRouterFacade
  Task 6   applyRatingResolverSelection suspends to titleRatingCandidates(...)

Packet D — Device verification:
  Task 7   Build, install, capture, verify, refresh sanitized summary
```

Recommended order: B → A → C → D. B is the highest-impact single fix because it stops the recomposition cycle that's both the popping AND a chunk of the GC churn. A and C are independent of B and of each other.

---

## Task 1: `contentEquals` Helper

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/HydratedHomeOverlay.kt`
- Test: `app/src/test/java/com/nexio/tv/domain/model/HydratedHomeOverlayTest.kt`

- [ ] **Step 1: Read the existing data class**

```bash
sed -n '20,55p' app/src/main/java/com/nexio/tv/domain/model/HydratedHomeOverlay.kt
```

Expected: `data class HydratedHomeOverlay(...)` with `overlayKey`, `itemKey`, `canonicalProvider`, `canonicalId`, `imdbId`, `contentType`, `languageTag`, `policyVersion` (default), `fields`, `fieldTrace`, `displayHash` (defaulted via `fields.hydratedHomeDisplayHash()`), `state`, `updatedAtMs`, `staleAtMs`, `expiresAtMs`. The timestamp trio at the bottom is what equality currently includes.

- [ ] **Step 2: Write the failing test**

Add to `HydratedHomeOverlayTest.kt`:

```kotlin
@Test
fun `contentEquals returns true when only timestamps differ`() {
    val fields = HomeDisplayMetadata(title = "Same")
    val a = HydratedHomeOverlay(
        overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
        itemKey = "movie:tmdb:550",
        canonicalProvider = ProviderId.TMDB,
        canonicalId = "550",
        imdbId = "tt0137523",
        contentType = ContentType.MOVIE,
        languageTag = "en",
        policyVersion = 1,
        fields = fields,
        fieldTrace = emptyList(),
        updatedAtMs = 1_000L,
        staleAtMs = 2_000L,
        expiresAtMs = 3_000L,
        state = HomeItemHydrationState.CANONICAL_READY
    )
    val b = a.copy(updatedAtMs = 5_000L, staleAtMs = 6_000L, expiresAtMs = 7_000L)

    assertNotEquals(a, b, "data-class equality should fail when timestamps differ")
    assertTrue(a.contentEquals(b), "contentEquals must ignore timestamp churn")
}

@Test
fun `contentEquals returns false when displayHash differs`() {
    val a = HydratedHomeOverlay(
        overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
        itemKey = "movie:tmdb:550",
        canonicalProvider = ProviderId.TMDB,
        canonicalId = "550",
        imdbId = "tt0137523",
        contentType = ContentType.MOVIE,
        languageTag = "en",
        policyVersion = 1,
        fields = HomeDisplayMetadata(title = "Old"),
        fieldTrace = emptyList(),
        updatedAtMs = 1_000L,
        staleAtMs = 2_000L,
        expiresAtMs = 3_000L,
        state = HomeItemHydrationState.CANONICAL_READY
    )
    val b = a.copy(fields = HomeDisplayMetadata(title = "New"), displayHash = HomeDisplayMetadata(title = "New").hydratedHomeDisplayHash())

    assertFalse(a.contentEquals(b))
}
```

If `assertNotEquals` is unavailable, use `assertFalse(a == b)` instead.

- [ ] **Step 3: Run red**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.domain.model.HydratedHomeOverlayTest.contentEquals*'
```

Expected: fail (method doesn't exist).

- [ ] **Step 4: Add the helper**

In `HydratedHomeOverlay.kt`, after the `data class HydratedHomeOverlay` declaration but before the `hydratedHomeOverlayKey` extension, add:

```kotlin
/**
 * True when two overlays describe the same content. Ignores [updatedAtMs],
 * [staleAtMs], and [expiresAtMs] which are stamped fresh on every rebuild even
 * when nothing else has changed. Use this — not data-class equality — for the
 * apply-seam dedup, the publish gate, and the catalog-signature hash. Otherwise
 * every cache-hit hydration tick mutates the overlay map, fires
 * scheduleUpdateCatalogRows(), and triggers a full Compose recomposition.
 */
fun HydratedHomeOverlay.contentEquals(other: HydratedHomeOverlay): Boolean =
    overlayKey == other.overlayKey &&
        itemKey == other.itemKey &&
        canonicalProvider == other.canonicalProvider &&
        canonicalId == other.canonicalId &&
        imdbId == other.imdbId &&
        contentType == other.contentType &&
        languageTag == other.languageTag &&
        policyVersion == other.policyVersion &&
        fields == other.fields &&
        fieldTrace == other.fieldTrace &&
        displayHash == other.displayHash &&
        state == other.state
```

- [ ] **Step 5: Run green**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.domain.model.HydratedHomeOverlayTest
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/HydratedHomeOverlay.kt app/src/test/java/com/nexio/tv/domain/model/HydratedHomeOverlayTest.kt
git commit -m "$(cat <<'EOF'
feat: add HydratedHomeOverlay.contentEquals that ignores timestamp churn

Data-class equality on HydratedHomeOverlay includes updatedAtMs,
staleAtMs, and expiresAtMs which the hydration coordinator stamps
fresh on every rebuild — even cache-hit ones with identical
displayHash and fields. The downstream consumers (apply-seam dedup,
publish gate, catalog-signature hash) need a content-only equality
to short-circuit cycles correctly. This commit only adds the helper;
the call sites are migrated in the next task.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 2: Apply Seam And Publish Gate Use `contentEquals`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactiveHydrationPipelineTest.kt`

- [ ] **Step 1: Read both call sites**

```bash
sed -n '500,520p' app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
sed -n '454,463p' app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
```

Expected:
- Line 507: `if (current[overlay.itemKey] == overlay) current` inside `applyHydratedHomeOverlayFromCoordinator`.
- Line 456-458: `internal fun shouldPublishHydratedHomeOverlays(current: Map<String, HydratedHomeOverlay>, next: Map<String, HydratedHomeOverlay>): Boolean = current != next`.

- [ ] **Step 2: Write a failing test**

Add to `HomeReactiveHydrationPipelineTest.kt`:

```kotlin
@Test
fun `unchanged overlay map ignoring timestamps does not request a republish`() {
    val baseOverlay = HydratedHomeOverlayFixtures.tmdbMovie(
        canonicalId = "550",
        title = "Fight Club",
        updatedAtMs = 1_000L,
        staleAtMs = 2_000L,
        expiresAtMs = 3_000L
    )
    val current = mapOf("movie:tmdb:550" to baseOverlay)
    val nextSameContent = mapOf(
        "movie:tmdb:550" to baseOverlay.copy(
            updatedAtMs = 5_000L,
            staleAtMs = 6_000L,
            expiresAtMs = 7_000L
        )
    )

    assertFalse(
        shouldPublishHydratedHomeOverlays(current, nextSameContent),
        "publish gate must short-circuit on content-equal maps with timestamp-only differences"
    )
}
```

If `HydratedHomeOverlayFixtures.tmdbMovie` doesn't exist, locate the closest fixture in the file and adapt — match the pattern used by existing tests (probably `RailPreviewFirstPaintFixtures` or in-test helpers).

- [ ] **Step 3: Run red**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.ui.screens.home.HomeReactiveHydrationPipelineTest.unchanged overlay map ignoring timestamps does not request a republish'
```

Expected: fail (publish gate uses `!=` which fails on timestamp-only diff).

- [ ] **Step 4: Migrate the apply-seam dedup**

In `HomeViewModelCatalogPipeline.kt:506-513`, change:

```kotlin
hydratedHomeOverlaysByItemKey.update { current ->
    if (current[overlay.itemKey] == overlay) {
        current
    } else {
        changed = true
        current + (overlay.itemKey to overlay)
    }
}
```

to:

```kotlin
hydratedHomeOverlaysByItemKey.update { current ->
    val existing = current[overlay.itemKey]
    if (existing != null && existing.contentEquals(overlay)) {
        current
    } else {
        changed = true
        current + (overlay.itemKey to overlay)
    }
}
```

Add the import: `import com.nexio.tv.domain.model.contentEquals`.

- [ ] **Step 5: Migrate the publish gate**

Change `shouldPublishHydratedHomeOverlays` body (line 456-458) to:

```kotlin
internal fun shouldPublishHydratedHomeOverlays(
    current: Map<String, HydratedHomeOverlay>,
    next: Map<String, HydratedHomeOverlay>
): Boolean {
    if (current.size != next.size) return true
    if (current.keys != next.keys) return true
    return current.any { (key, overlay) ->
        val nextOverlay = next[key] ?: return true
        !overlay.contentEquals(nextOverlay)
    }
}
```

- [ ] **Step 6: Run green**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeReactiveHydrationPipelineTest
```

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactiveHydrationPipelineTest.kt
git commit -m "$(cat <<'EOF'
fix: dedup hydrated home overlays on content, not timestamps

The apply seam at applyHydratedHomeOverlayFromCoordinator and the
publish gate shouldPublishHydratedHomeOverlays both used data-class
equality, which includes updatedAtMs/staleAtMs/expiresAtMs.
Re-hydration ticks stamp fresh nowMs even when displayHash and fields
are identical, so neither check ever short-circuited:
  movie:tmdb:1198994 hydrated 6 times in 20s with identical displayHash
Each tick mutated the overlay map, invalidated the catalog signature,
emitted a fresh CatalogRow list, and Coil cross-faded between identical
poster URLs — perceived as the premium poster flickering away. Switching
to contentEquals collapses these noisy ticks into no-ops.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 3: Catalog Signature Uses Content Hash

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`

- [ ] **Step 1: Locate `buildCatalogComputationSignature`**

```bash
grep -n "fun buildCatalogComputationSignature\|computationSignature" app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt | head
```

Expected: a function around line 2545 that builds a signature from `hydratedHomeOverlaysByItemKey.value` (and other inputs). Read the body.

- [ ] **Step 2: Identify how overlays are folded into the signature**

If the signature uses `overlays.hashCode()` or `overlays.toString()`, the timestamp fields leak in via `HydratedHomeOverlay`'s data-class equality/`hashCode()`. We need to fold each overlay's `displayHash` (already content-derived) into the signature instead.

- [ ] **Step 3: Patch the fold**

Replace the overlay portion of the signature builder with an iteration that accumulates each overlay's `displayHash`:

```kotlin
private fun buildCatalogComputationSignature(
    rows: List<CatalogRow>,
    overlays: Map<String, HydratedHomeOverlay>,
    // ... other params unchanged ...
): String {
    val overlayContentDigest = overlays
        .toSortedMap()
        .entries
        .joinToString(separator = "|") { (key, overlay) -> "$key=${overlay.displayHash}" }
    // ... combine overlayContentDigest with the other inputs as before ...
}
```

If the signature uses a different shape (e.g., a `data class` of inputs that gets `.hashCode()`-ed), thread `overlayContentDigest: String` through that data class and remove the raw `overlays: Map<String, HydratedHomeOverlay>` from it. Keep `overlays` as a parameter for the actual apply pass; just don't include it in the signature itself.

- [ ] **Step 4: Re-run the home-pipeline tests**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.ui.screens.home.*' 2>&1 | tail -10
```

Confirm no NEW failures (pre-existing `assertSame` failure stays).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
git commit -m "$(cat <<'EOF'
fix: hash hydrated overlays by displayHash in catalog signature

The catalog computation signature included the raw overlay map, so
timestamp-only churn on HydratedHomeOverlay's data class made every
re-hydration tick produce a fresh signature even with identical
content. The signature now folds each overlay's displayHash (which
is already content-derived) so cache-hit re-hydrations are properly
deduped end-to-end.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 4: `ArtworkLegacyProjection` Prefers Decision URI

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkLegacyProjection.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkLegacyProjectionTest.kt`

- [ ] **Step 1: Read the current projection**

```bash
sed -n '1,30p' app/src/main/java/com/nexio/tv/core/artwork/ArtworkLegacyProjection.kt
```

Expected: `fun ArtworkDisplayRef?.toLegacyArtworkString(): String?` (or similar) that maps a `RuntimeAsset(decisionKey, assetKey)` to `nexio-artwork://asset/<assetKey>` when `assetKey != null`, otherwise to `nexio-artwork://decision/<decisionKey>`.

- [ ] **Step 2: Write a failing test**

Add to `ArtworkLegacyProjectionTest.kt`:

```kotlin
@Test
fun `RuntimeAsset projects to decision URI by default`() {
    val ref = ArtworkDisplayRefFixtures.runtimeAsset(
        decisionKey = "artwork-decision:poster:canonical:tvdb:355567:provider:RPDB:premium:true:settings:abc:credential:def:imageLang:en:policy:1",
        assetKey = "artwork-asset:RPDB:poster:tvdb:355567:settings:abc:credential:def:imageLang:en:policy:1"
    )

    val result = ref.toLegacyArtworkString()

    assertNotNull(result)
    assertTrue(
        "RuntimeAsset must project to decision URI for self-healing fetcher; was: $result",
        result!!.startsWith("nexio-artwork://decision/")
    )
    assertFalse(result.startsWith("nexio-artwork://asset/"))
}
```

If `ArtworkDisplayRefFixtures.runtimeAsset` doesn't exist, construct the `ArtworkDisplayRef` directly using the actual constructor visible in the source.

- [ ] **Step 3: Run red**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.core.artwork.ArtworkLegacyProjectionTest.RuntimeAsset projects to decision URI by default'
```

Expected: fail.

- [ ] **Step 4: Invert the projection**

Change `ArtworkLegacyProjection.kt` so `RuntimeAsset` always emits the decision URI:

```kotlin
internal fun ArtworkDisplayRef.RuntimeAsset.toLegacyArtworkString(): String =
    "nexio-artwork://decision/${decisionKey.value}"
```

(If the original branched on `assetKey != null`, drop the asset-URI branch entirely. The decision URI self-heals via `NexioArtworkFetcher`'s decision-path → `ArtworkAssetRepository.getOrFetchDecision` → `fetchInternal` → `persistAssetRecordBestEffort`.)

If other call sites depend on receiving an asset URI (e.g., a hot path that knows bytes are present and wants to skip the decision lookup), preserve a SEPARATE method `toAssetArtworkString()` for those callers and leave `toLegacyArtworkString()` as decision-only. Search for asset-URI consumers:

```bash
grep -rn "nexio-artwork://asset" app/src/main/java/com/nexio/tv/ | grep -v test | head
```

- [ ] **Step 5: Run green and the broader artwork test suite**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkLegacyProjectionTest
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.core.artwork.*'
```

Expected: pass. If any test asserts a specific URI shape that includes `asset/`, update the test fixture to match the new contract — those tests were pinning the bug, not behavior.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkLegacyProjection.kt app/src/test/java/com/nexio/tv/core/artwork/ArtworkLegacyProjectionTest.kt
git commit -m "$(cat <<'EOF'
fix: project RuntimeAsset to decision URI for self-healing fetcher

Previously the projection emitted nexio-artwork://asset/<assetKey>
whenever the assetKey was non-null. But MetadataArtworkDecisionResolver
constructs assetKey by hashing the URL without ever materializing the
bytes, so the asset URI lands in persisted overlays pointing at bytes
nobody ever wrote. NexioArtworkFetcher's asset path is read-only —
the bytes never appear unless something later reaches the decision
URI directly.

Switching to decision-URI projection lets the fetcher's decision
path (getOrFetchDecision → fetchInternal → persistAssetRecordBestEffort)
materialize the bytes on first paint, and keeps the existing asset-
record store entry creation invariant intact. Hero-panel logos and
any future rail logo slot will now render instead of silently failing
with artwork.orphan_asset_ref_rehydrate_skipped.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 5: Inject `TitleRatingOverrideRepository` Into `MetadataRouterFacade`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
- Modify: Hilt module that constructs `MetadataRouterFacade` (find with grep — likely `app/src/main/java/com/nexio/tv/core/di/MetadataRouterModule.kt` or equivalent)

- [ ] **Step 1: Read the current constructor**

```bash
sed -n '1,80p' app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt | head -80
```

Find the `class MetadataRouterFacade @Inject constructor(...)` declaration. Note the existing constructor params.

- [ ] **Step 2: Add the injected dependency**

Add `private val titleRatingOverrideRepository: TitleRatingOverrideRepository` to the constructor parameter list. Add the import:

```kotlin
import com.nexio.tv.data.repository.TitleRatingOverrideRepository
```

If `MetadataRouterFacade` is constructed by a `@Provides`-style Hilt module rather than `@Inject` constructor injection, also update the module's provider function. Search for it:

```bash
grep -rn "MetadataRouterFacade(" app/src/main/java/com/nexio/tv/core/di/ | head
```

- [ ] **Step 3: Verify the Hilt graph compiles**

```bash
./gradlew :app:hiltJavaCompileArmv7Debug 2>&1 | tail -10
```

If `TitleRatingOverrideRepository` requires a binding that isn't present, add the binding (it's already `@Inject constructor(...)` `@Singleton` — no extra module needed).

- [ ] **Step 4: Commit (just the wiring, no behavior change yet)**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt app/src/main/java/com/nexio/tv/core/di/
git commit -m "$(cat <<'EOF'
feat: inject TitleRatingOverrideRepository into MetadataRouterFacade

Wiring-only change in preparation for routing CUSTOM_IMDB rating
candidates from the bulk-IMDB integration into the home pipeline's
RatingResolver invocation. The repository is unused after this commit;
the consumer change lands in the next task.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 6: `applyRatingResolverSelection` Pulls CUSTOM_IMDB Candidates

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeRatingTest.kt` (or the nearest test class for facade rating coverage)

- [ ] **Step 1: Read the current `applyRatingResolverSelection`**

```bash
grep -n "fun applyRatingResolverSelection" app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt
sed -n '<lineFromGrep>,$+30p' app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt
```

Expected: a private function that takes `document`, `preview`, `primary`; builds a `RatingCandidate` list; calls `RatingResolver.resolveTitleRating(...)`; returns `document.copy(rating = ..., sourceProviders = ..., sourceRoles = ...)`.

- [ ] **Step 2: Read the existing repo's API shape**

```bash
sed -n '22,52p' app/src/main/java/com/nexio/tv/data/repository/TitleRatingOverrideRepository.kt
```

The method we want is `suspend fun titleRatingCandidates(preview: MetaPreview, stableIdBundle: StableIdBundle? = null, providerIds: ProviderIds = ProviderIds()): List<RatingCandidate>`.

- [ ] **Step 3: Make `applyRatingResolverSelection` suspend and call the repo**

Change the signature from `private fun applyRatingResolverSelection(...)` to `private suspend fun applyRatingResolverSelection(...)`. Add the call inside `buildList { ... }`:

```kotlin
private suspend fun applyRatingResolverSelection(
    document: ResolvedMetadataDocument,
    preview: MetadataCandidate?,
    primary: MetadataCandidate?,
    rawPreview: MetaPreview,
    stableIdBundle: StableIdBundle?,
    providerIds: ProviderIds
): ResolvedMetadataDocument {
    val candidates = buildList {
        addAll(titleRatingOverrideRepository.titleRatingCandidates(rawPreview, stableIdBundle, providerIds))
        primary?.fields?.get(ResolvedField.RATING)?.value?.toRatingCandidate(
            sourceRole = com.nexio.tv.core.metadata.router.resolver.SourceRole.PRIMARY_PROVIDER,
            sourceProvider = primary.provider.name
        )?.let(::add)
        preview?.fields?.get(ResolvedField.RATING)?.value?.toRatingCandidate(
            sourceRole = com.nexio.tv.core.metadata.router.resolver.SourceRole.PREVIEW_FALLBACK,
            sourceProvider = preview.provider.name
        )?.let(::add)
    }
    if (candidates.isEmpty()) return document
    val resolution = RatingResolver.resolveTitleRating(candidates) ?: return document
    return document.copy(
        rating = resolution.value,
        sourceProviders = document.sourceProviders + (ResolvedField.RATING to resolution.sourceProvider),
        sourceRoles = document.sourceRoles + (ResolvedField.RATING to com.nexio.tv.core.metadata.router.SourceRole.PRIMARY)
    )
}
```

Note: `addAll(titleRatingCandidates(...))` is a SUSPEND call — the function is now `suspend`. Update the caller. Find the existing call site:

```bash
grep -n "applyRatingResolverSelection(" app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt
```

`resolveRequest` is already a suspend function, so passing through is straightforward. Pass the new params: the `MetaPreview` (from `request.sourceContext.addonMetadata` via the existing pipeline OR construct from `previewCandidate`), the `StableIdBundle?` (already in scope as `runResult.stableIdBundle` or similar — check), and the `ProviderIds` (from `request.sourceContext.previewStableIds` or equivalent).

If the rawPreview/stableIdBundle/providerIds aren't in scope at the call site, the simplest path is to pass them through from `resolveRequest`'s local variables. Look for the closest `MetaPreview`-shaped value in scope at the call site.

- [ ] **Step 4: Add a test**

In the rating-focused test class, add:

```kotlin
@Test
fun `applyRatingResolverSelection picks CUSTOM_IMDB candidate when bulk repo returns one`() = runTest {
    val titleRatingRepo = mockk<TitleRatingOverrideRepository>()
    coEvery {
        titleRatingRepo.titleRatingCandidates(any(), any(), any())
    } returns listOf(
        RatingCandidate(
            value = 8.4,
            sourceRole = com.nexio.tv.core.metadata.router.resolver.SourceRole.CUSTOM_IMDB,
            sourceProvider = "IMDB",
            confidence = Confidence.HIGH,
            scope = RatingScope.TITLE
        )
    )

    val facade = TestMetadataRouterFacade.builder()
        .withTvdbPrimary(rating = null)
        .withTitleRatingOverrideRepository(titleRatingRepo)
        .build()

    val result = facade.resolveRequest(/* trakt-canonical SERIES request with imdb tt1190634 */)

    assertEquals(8.4, result.displayMetadata.imdbRating)
    assertEquals(TitleRatingSource.IMDB, result.displayMetadata.ratingSource)
}
```

Adapt fixtures to the actual `TestMetadataRouterFacade.builder()` API. If that builder doesn't accept `withTitleRatingOverrideRepository`, extend it.

- [ ] **Step 5: Run the test**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterFacadeRatingTest
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeRatingTest.kt
git commit -m "$(cat <<'EOF'
fix: pull CUSTOM_IMDB rating candidates from bulk-IMDB repo on home path

applyRatingResolverSelection now adds the
TitleRatingOverrideRepository.titleRatingCandidates(...) result to its
candidate set before invoking RatingResolver.resolveTitleRating.
RatingResolver's existing precedence (CUSTOM_IMDB → MDBLIST → OMDB →
PRIMARY_PROVIDER → PREVIEW_FALLBACK) means a successful bulk-IMDB
lookup wins over an empty TVDB primary or a TMDB rail-preview rating.

Closes the gap on TVDB-canonical SERIES rows where home overlays
persisted imdbRating=null/ratingSource=null because neither the
preview nor the canonical primary carried a usable rating value.
The detail screen has used this same repository for some time; this
commit just brings the home pipeline in line with that contract.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 7: Device Verification

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
mkdir -p tmp/crash-investigation-2026-05-09/repro4
adb -s $DEV logcat -d -v threadtime > tmp/crash-investigation-2026-05-09/repro4/logcat-postfix.txt
adb -s $DEV shell su -c 'cp /data/data/com.nexio.tv/shared_prefs/hydrated_home_overlay_v1.xml /sdcard/overlay-postfix.xml'
adb -s $DEV pull /sdcard/overlay-postfix.xml tmp/crash-investigation-2026-05-09/repro4/overlay-postfix.xml
adb -s $DEV shell su -c 'cp /data/data/com.nexio.tv/shared_prefs/continue_watching_snapshot.xml /sdcard/cw-postfix.xml'
adb -s $DEV pull /sdcard/cw-postfix.xml tmp/crash-investigation-2026-05-09/repro4/cw-postfix.xml
```

- [ ] **Step 4: Run the broader sanity check**

```bash
python3 << 'EOF'
import xml.etree.ElementTree as ET, html, json, re
from collections import Counter

overlay = 'tmp/crash-investigation-2026-05-09/repro4/overlay-postfix.xml'
logcat = 'tmp/crash-investigation-2026-05-09/repro4/logcat-postfix.txt'

t = ET.parse(overlay)
strings = [(c.attrib['name'], c.text or '') for c in t.getroot() if c.tag == 'string']
tvdb_series = [(n, json.loads(html.unescape(v))['value']) for n, v in strings
               if n.startswith('overlay::canonical:TVDB:') and 'type:SERIES' in n]

ratings_non_null = sum(1 for _, v in tvdb_series if v.get('fields', {}).get('imdbRating') is not None)
sources = Counter(v.get('fields', {}).get('ratingSource') for _, v in tvdb_series)
poster_decision_refs = sum(1 for _, v in tvdb_series
                          if (v.get('fields', {}).get('poster') or '').startswith('nexio-artwork://decision/'))
poster_asset_refs = sum(1 for _, v in tvdb_series
                       if (v.get('fields', {}).get('poster') or '').startswith('nexio-artwork://asset/'))
logo_decision_refs = sum(1 for _, v in tvdb_series
                        if (v.get('fields', {}).get('logo') or '').startswith('nexio-artwork://decision/'))
logo_asset_refs = sum(1 for _, v in tvdb_series
                     if (v.get('fields', {}).get('logo') or '').startswith('nexio-artwork://asset/'))

print(f'TVDB SERIES overlays: {len(tvdb_series)}')
print(f'  imdbRating non-null: {ratings_non_null} (target: > 0 — Task 6)')
print(f'  ratingSource distribution: {dict(sources)}')
print(f'  poster decision refs: {poster_decision_refs} (target: > 0 — Task 4)')
print(f'  poster asset refs:    {poster_asset_refs} (target: 0 — Task 4)')
print(f'  logo decision refs:   {logo_decision_refs} (target: > 0 — Task 4)')
print(f'  logo asset refs:      {logo_asset_refs} (target: 0 — Task 4)')

with open(logcat) as f:
    log = f.read()

def count(p): return len(re.findall(p, log))
events = {
    'hydration_applied':            count(r'home\.hydration_applied'),
    'fallback_materialized':        count(r'artwork\.fallback_materialized'),
    'orphan_rehydrate_skipped':     count(r'artwork\.orphan_asset_ref_rehydrate_skipped'),
    'long_monitor_decision_flush':  count(r'Long monitor.*ArtworkDecisionCacheFlush'),
    'background_concurrent_gc':     count(r'Background concurrent copying GC'),
    'fatal_exception':              count(r'FATAL EXCEPTION'),
}
print('\nRuntime trace counts:')
for k, n in events.items():
    print(f'  {k}: {n}')

# Find the same-itemKey re-apply storms
print('\nTop re-applied itemKeys (should all be 1-2 after Task 2):')
import subprocess
subprocess.run(['grep', '-oE', 'home\.hydration_applied.*itemKey=[^ ]+', logcat], capture_output=True, text=True).stdout.splitlines()
EOF
```

Expected after Tasks 1-6:
- `imdbRating non-null > 0` for TVDB SERIES (was 0)
- `ratingSource` includes `IMDB`
- `poster asset refs` = 0, `poster decision refs` > 0 (Task 4)
- `logo asset refs` = 0, `logo decision refs` > 0 (Task 4)
- `hydration_applied` count significantly lower than 152 (Tasks 1-3 should collapse cache-hit re-applies)
- `background_concurrent_gc` significantly lower than 84
- `fallback_materialized` = 0
- `fatal_exception` = 0

- [ ] **Step 5: Refresh sanitized summary**

```bash
python3 tools/reporting/summarize_artwork_state.py \
  --overlay tmp/crash-investigation-2026-05-09/repro4/overlay-postfix.xml \
  --snapshot tmp/crash-investigation-2026-05-09/repro4/cw-postfix.xml \
  --logcat tmp/crash-investigation-2026-05-09/repro4/logcat-postfix.txt \
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

- [ ] **Step 7: Commit**

```bash
git add review-dossier/2026-05-08-tv-artwork-cw-postfix-summary.json
git commit -m "$(cat <<'EOF'
docs: refresh sanitized summary after overlay equality + projection + rating fixes

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Required Verification Commands

Run before claiming complete:

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.domain.model.HydratedHomeOverlayTest
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeReactiveHydrationPipelineTest
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkLegacyProjectionTest
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterFacadeRatingTest
./gradlew :app:assembleArmv7Release
```

Then run the device verification in Task 7.

## Acceptance Criteria

- `HydratedHomeOverlay.contentEquals(other)` returns true for two overlays differing only in `updatedAtMs`/`staleAtMs`/`expiresAtMs` and same content otherwise.
- The apply seam at `applyHydratedHomeOverlayFromCoordinator` and the publish gate `shouldPublishHydratedHomeOverlays` use `contentEquals` and short-circuit on timestamp-only diffs.
- `buildCatalogComputationSignature` (or the surrounding signature builder) folds overlay content via `displayHash`, not via raw map equality.
- `ArtworkLegacyProjection` projects every `RuntimeAsset` to `nexio-artwork://decision/<key>` regardless of whether `assetKey` is present.
- Persisted overlay XML for TVDB-canonical SERIES rows has zero `nexio-artwork://asset/...` refs in `fields.poster`/`fields.backdrop`/`fields.logo`; all are `nexio-artwork://decision/...`.
- `MetadataRouterFacade.applyRatingResolverSelection` calls `TitleRatingOverrideRepository.titleRatingCandidates(...)` and includes the result in the resolver's candidate list.
- TVDB-canonical SERIES rows where the bulk-IMDB API has a rating persist `imdbRating != null`, `ratingSource = TitleRatingSource.IMDB`.
- Device verification shows: zero `fatal_exception`, zero `Long monitor decision flush`, zero `fallback_materialized`, `hydration_applied` count < 30 in 35s (was 152), GC count < 30 (was 84), poster/backdrop/logo refs render visibly on Trakt-trending and TMDB-popular rails.
- Premium RPDB posters do not flicker after rendering.

## Commit Plan

```text
feat: add HydratedHomeOverlay.contentEquals that ignores timestamp churn
fix: dedup hydrated home overlays on content, not timestamps
fix: hash hydrated overlays by displayHash in catalog signature
fix: project RuntimeAsset to decision URI for self-healing fetcher
feat: inject TitleRatingOverrideRepository into MetadataRouterFacade
fix: pull CUSTOM_IMDB rating candidates from bulk-IMDB repo on home path
docs: refresh sanitized summary after overlay equality + projection + rating fixes
```

Do not stage:

```text
tmp/crash-investigation-2026-05-09/**
app/src/main/assets/openrouter_reasoning_models.json
media
```
