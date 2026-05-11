# Artwork Routing & Invalidation Design

**Status:** Spec
**Date:** 2026-05-11
**Subsystem:** Home pipeline — artwork resolution + overlay lifecycle

## Goal

Make user-chosen artwork provider settings the authoritative source of truth on Modern Home, with stable rendering (no popping), correct invalidation when settings or stable IDs change, and a forward-compatible model for adding fanart.tv (default for movies/series, not anime) without code changes at every consumer.

## Problem statement

Investigation surfaced four bugs in the current pipeline plus an architectural gap that blocks the future fanart.tv migration:

**Bug A — popping (same-rank tie-breaker is "incoming wins").** `HomeRailProjectionReducer.pickHigherRanked` uses strict `>` for rank comparison. When existing `RESOLVED("rpdb://…")` and incoming `RESOLVED("addon://stock.jpg")` collide, the tie goes to incoming. Transient RPDB API failures during re-hydration flip the surface; the next successful hydration flips it back. Result: visible poster popping.

**Bug B — stale overlay (no invalidation when IDs strengthen).** `HydratedHomeOverlay` only stores `imdbId: String?`. No snapshot of other provider IDs. No `delete`/`markStale` API on `HydratedHomeOverlayStore`. No invalidation when `CatalogItemCrossIdEnricher` populates imdb after first hydration. Overlay built with imdb=null stays cached forever; the RPDB candidate is never re-evaluated.

**Bug C — content-equality gate suppresses legitimate upserts.** `HomeHydrationCoordinator` skips upsert when `displayHash` matches the existing overlay (line 132–141). Combined with no delete API, this means even if cross-id enrichment adds imdb but the hydration's display fields haven't materially changed, the overlay never refreshes.

**Bug D — settings changes don't invalidate overlays.** `ArtworkProviderSelectionSettings.posterProvider = RPDB` is consulted at hydration time and baked into `fields.poster`. Switching RPDB → DEFAULT in settings has no effect on already-cached overlays.

**Architectural gap — DEFAULT semantics.** Today `ArtworkProviderChoiceKey.DEFAULT` means "no premium provider; use PRIMARY (TMDB/etc.)". The future model needs `DEFAULT` to resolve to different providers per (artworkType, contentType) — fanart.tv for movies/series posters, addon for anime — with the explicit RPDB/TOP_POSTERS settings choice overriding the default.

## Non-goals

- Fanart.tv `ArtworkProvider` integration itself (separate spec; this design only ensures the resolver can consume it when added).
- Per-content-type override UI ("force addon for movies, RPDB for series"). The user's settings model is per-artwork-type only.
- Episode thumbnail provider expansion beyond TOP_POSTERS gating.
- Migrating existing UI screens away from inline image URLs (the model still produces URLs at the slot level).

## Architecture

### System layers

```
┌─────────────────────────────────────────────────────────────────────┐
│ Settings (DataStore — small scalars)                                │
│   ArtworkProviderSelectionSettings {                                │
│     posterProvider | logoProvider | backdropProvider |              │
│     thumbnailProvider                                               │
│       each ∈ { DEFAULT, RPDB, TOP_POSTERS, FANART_TV (future) }     │
│   }                                                                  │
└────────────────────────┬────────────────────────────────────────────┘
                         │ flows
                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│ ArtworkProviderResolver (NEW — pure function)                       │
│   resolve(artworkType, contentType, isAnime, availableIds, settings)│
│     1. Explicit premium choice → returned if capable                │
│     2. Else contentTypeDefaults[(artworkType, isAnime)] table        │
└────────────────────────┬────────────────────────────────────────────┘
                         │ called by
                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│ ArtworkRouter (existing, lightly modified)                          │
│   Replaces inline selectedProviderFor logic with resolver call.     │
└─────────────────────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│ HomeHydrationCoordinator (existing, NEW: stamps provenance)         │
│   Stamps stableIdsSnapshot + settingsSignature onto each overlay.   │
└────────────────────────┬────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│ HydratedHomeOverlayStore (existing, NEW invalidation API)           │
│   • upsert(overlay, aliases)                                        │
│   • markStaleIfWeakerIds(itemKey, currentIds)     ← NEW             │
│   • markStaleAll(reason)                          ← NEW             │
└─────────────────────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│ ResolvedDisplaySurfaceRepository (existing, NEW tie-breaker)        │
│   applyNonDowngradeMerge: tie → settings-preferred provider wins.   │
└─────────────────────────────────────────────────────────────────────┘
```

### Trigger points

| Event | Component | Action |
|---|---|---|
| `CatalogItemCrossIdEnricher` writes new IDs for an itemKey | enricher → `overlayStore.markStaleIfWeakerIds(itemKey, currentIds)` | overlay's `state = STALE_READY` in memory |
| User changes any `ArtworkProviderSelectionSettings` field | new `ArtworkSettingsInvalidator` (app-scoped Flow observer) → `overlayStore.markStaleAll("settings_change")` | every overlay's `state = STALE_READY` in memory |
| Item becomes visible / focused | existing `HomeHydrationCoordinator` visibility path | re-fires hydration; content-equality gate bypassed when `state == STALE_READY` |

### Tie-breaker (Bug A fix)

`ResolvedDisplayItem` gains a `preferredArtworkProviders: Map<ArtworkType, ArtworkProviderId>` field, computed once per item at projection time by `HomeResolvedDisplayMapper` via the resolver.

`ResolvedDisplaySurfaceRepository.applyNonDowngradeMerge` consults this map when an artwork slot's rank ties with the existing slot's rank. Rule:

```
For each artwork slot (poster, backdrop, logo, thumbnail):
  preferred = incoming.preferredArtworkProviders[slotType]
  inMatches = incoming.slot.provider == preferred.name
  exMatches = existing.slot.provider == preferred.name

  if incoming.rank > existing.rank → incoming wins
  if incoming.rank < existing.rank → existing wins
  if rank tie:
      if  inMatches && !exMatches → incoming wins   (upgrade)
      if !inMatches &&  exMatches → existing wins   (REJECT REGRESSION)
      if  inMatches &&  exMatches → incoming wins   (both preferred)
      if !inMatches && !exMatches → existing wins   (no churn for irrelevant deltas)

For text slots (title, overview, genres, releaseInfo, runtime, rating):
  unchanged — strict-> rank, tie → incoming wins
```

The reducer itself stays a pure rank-picker. Settings only touch the merge boundary inside the repository.

## Data model changes

### `HydratedHomeOverlay` (existing — extend)

```kotlin
data class HydratedHomeOverlay(
    // unchanged fields
    val overlayKey: String,
    val itemKey: String,
    val canonicalProvider: ProviderId,
    val canonicalId: String,
    val imdbId: String?,
    val contentType: ContentType,
    val languageTag: String,
    val policyVersion: Int,
    val fields: HomeDisplayMetadata,
    val fieldTrace: List<HydratedHomeFieldTrace>,
    val displayHash: String,
    val updatedAtMs: Long,
    val staleAtMs: Long,
    val expiresAtMs: Long,
    val state: HomeItemHydrationState,

    // NEW
    val stableIdsSnapshot: ProviderIds = ProviderIds(),
    val settingsSignature: String = ""
)
```

### `ResolvedDisplayItem` (existing — extend)

```kotlin
data class ResolvedDisplayItem(
    // unchanged
    val slots: ResolvedDisplayFieldSlots? = null,
    // NEW
    val preferredArtworkProviders: Map<ArtworkType, ArtworkProviderId> = emptyMap()
)
```

### `ProviderIds.strictlyContains` (NEW helper)

```kotlin
fun ProviderIds.strictlyContains(other: ProviderIds): Boolean {
    val gainedImdb  = imdb  != null && other.imdb  == null
    val gainedTmdb  = tmdb  != null && other.tmdb  == null
    val gainedTvdb  = tvdb  != null && other.tvdb  == null
    val gainedKitsu = kitsu != null && other.kitsu == null
    val gainedTrakt = trakt != null && other.trakt == null
    val gainedSimkl = simkl != null && other.simkl == null
    // True iff (a) at least one ID is new AND (b) no ID was lost.
    if (!(gainedImdb || gainedTmdb || gainedTvdb || gainedKitsu || gainedTrakt || gainedSimkl)) return false
    val lostImdb  = imdb  == null && other.imdb  != null
    val lostTmdb  = tmdb  == null && other.tmdb  != null
    val lostTvdb  = tvdb  == null && other.tvdb  != null
    val lostKitsu = kitsu == null && other.kitsu != null
    val lostTrakt = trakt == null && other.trakt != null
    val lostSimkl = simkl == null && other.simkl != null
    return !(lostImdb || lostTmdb || lostTvdb || lostKitsu || lostTrakt || lostSimkl)
}
```

### Persistence — schema migration

`HydratedHomeOverlayStore` already uses streaming JSON read/write. The on-disk schema bumps to v2:

- v1 records load with `stableIdsSnapshot = ProviderIds()` (empty) and `settingsSignature = ""` (defaults).
- On the next `markStaleIfWeakerIds` evaluation (first cross-id enricher emission for any of these items), the empty snapshot is strictly contained by any non-empty current IDs → overlay marks stale → re-hydrate on next visibility. **Self-healing migration; no explicit upgrade code.**
- Same migration story for `settingsSignature`: every v1 overlay has `""` which never matches the current signature → first settings observer emission triggers `markStaleAll`.

Stale state (`state = STALE_READY`) is **not persisted**. It's set only in memory; cold-start loads overlays as `CANONICAL_READY` (or whatever was persisted), then the invalidators re-fire and re-mark.

## Resolver implementation

```kotlin
@Singleton
class ArtworkProviderResolver @Inject constructor(
    private val capabilityResolver: ArtworkProviderCapabilityResolver
) {
    fun resolve(
        artworkType: ArtworkType,
        contentType: ContentType,
        isAnime: Boolean,
        availableIds: ProviderIds,
        settings: ArtworkProviderSettings
    ): ArtworkProviderId {
        val explicit = settings.selection.providerFor(artworkType.toSettingsKey())
        if (explicit != ArtworkProviderChoiceKey.DEFAULT) {
            val provider = explicit.toRuntimeProviderId()
            val capable = capabilityResolver.evaluate(
                provider = provider,
                imageType = artworkType,
                ids = availableIds,
                mediaKind = contentType.toMetadataMediaKind(),
                settings = settings
            )
            if (capable.supported) return provider
            // Premium chosen but inputs not ready — fall through to default.
            // Next strengthening of IDs triggers markStaleIfWeakerIds → re-hydrate.
        }
        return contentTypeDefaults.resolve(artworkType, isAnime)
    }
}

private object contentTypeDefaults {
    private val addonProvider =
        ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON)
    // When fanart.tv lands: add ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART)
    // and switch the non-anime branch below to it. Also bump DEFAULTS_TABLE_VERSION.

    fun resolve(artworkType: ArtworkType, isAnime: Boolean): ArtworkProviderId =
        when (artworkType) {
            ArtworkType.POSTER, ArtworkType.BACKDROP, ArtworkType.LOGO ->
                if (isAnime) addonProvider else addonProvider
                //  ↑ when fanart.tv lands: else fanartProvider
            ArtworkType.THUMBNAIL -> addonProvider
        }
}
```

**Two new helpers required (small, can live with their domain types):**

- `ArtworkProviderChoiceKey.toRuntimeProviderId(): ArtworkProviderId` — maps `RPDB → ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB)`, `TOP_POSTERS → ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS)`. `DEFAULT` should never be passed here (the resolver guards with `!= DEFAULT` first); if called with `DEFAULT`, throw `IllegalArgumentException` ("resolver bug — DEFAULT was not coerced upstream").
- `ContentType.toMetadataMediaKind(): MetadataMediaKind` — already exists as `private fun` in three sites (`HomeResolvedDisplayMapper.kt:395`, `MetadataRequestNormalizer.kt:27`, `PosterRatingsUrlResolver.kt:796`). Promote to a single `internal fun` in `domain/model/ContentType.kt` so the resolver, the existing call sites, and the mapper all share one source. (Targeted cleanup; does not expand scope.)

`IntegrationProvider` already has cases for `RPDB`, `TOP_POSTERS`, and `ADDON`. Adding `FANART` is the future provider-integration spec's job.

**Anime detection** — `isAnime: Boolean` is derived once at the call site by the mapper. Source heuristic:
```kotlin
val isAnime = item.apiType.equals("anime", ignoreCase = true) ||
              item.firstPaintRailSource == RailSource.KITSU
```
The resolver does not concern itself with detection; it consumes the boolean.

## Settings observer (NEW)

```kotlin
@Singleton
class ArtworkSettingsInvalidator @Inject constructor(
    private val settingsSource: ArtworkProviderSettingsSource,  // existing interface
    private val overlayStore: HydratedHomeOverlayStore,
    @AppScope private val appScope: CoroutineScope
) {
    fun start() {
        appScope.launch {
            var lastSignature: String? = null
            settingsSource.settings  // Flow<ArtworkProviderSettings>
                .map { it.toSettingsSignature() }
                .distinctUntilChanged()
                .collect { signature ->
                    if (lastSignature != null && lastSignature != signature) {
                        overlayStore.markStaleAll(reason = "settings_change")
                    }
                    lastSignature = signature
                }
        }
    }
}

fun ArtworkProviderSettings.toSettingsSignature(): String =
    "p=${selection.posterProvider.value};" +
    "l=${selection.logoProvider.value};" +
    "b=${selection.backdropProvider.value};" +
    "t=${selection.thumbnailProvider.value};" +
    "v=$DEFAULTS_TABLE_VERSION"

private const val DEFAULTS_TABLE_VERSION = 1
```

`ArtworkSettingsInvalidator.start()` is wired into the app's existing startup sequence (alongside the other singleton invalidators). The `v=$N` suffix is the fanart.tv migration hook — bump the constant when the defaults table changes, every overlay's persisted signature is now stale, mass invalidation triggers on first emission.

## Bootstrapping sequence

```
App start
  └─ DataStore loads ArtworkProviderSettings
  └─ ArtworkSettingsInvalidator.start() — begins observing settings flow
  └─ HydratedHomeOverlayStore loads from disk
       └─ v1 records: empty stableIdsSnapshot + empty settingsSignature
       └─ v2 records: full provenance
  └─ Home pipeline starts; CatalogItemCrossIdEnricher runs as part of producer
       └─ Pushes markStaleIfWeakerIds(itemKey, currentIds) for every item whose
          current IDs strictly contain its overlay's stableIdsSnapshot
       └─ v1-loaded overlays all have empty snapshot → all mark stale
       └─ Settings observer's first emission: persisted signatures = "" ≠ current
          → markStaleAll fires once
  └─ Visibility/focus events drive HomeHydrationCoordinator
     → content-equality gate bypassed for STALE_READY overlays
     → re-hydration runs with current IDs + settings
     → router consults resolver, picks correct provider
     → surface merge accepts (preferred-provider tie-break)
```

## Telemetry

| Event | Fields | Trigger |
|---|---|---|
| `artwork.resolver.decision` | itemKeyHash, artworkType, contentType, isAnime, explicit (user choice), fellThroughTo (effective provider), chosenProvider, capabilitySupported | every `ArtworkProviderResolver.resolve` call |
| `overlay.stale_marked` | itemKey (hashed), reason (`settings_change` / `cross_id_enriched` / `signature_mismatch_cold_start`), oldState | every `markStale*` invocation that actually transitions an overlay |
| `overlay.rehydration_triggered` | itemKey (hashed), source (`visibility` / `focus` / `adjacent`), priorState | every coordinator re-fire of a stale overlay |
| `surface.merge.tie_break_rejected_regression` | itemKey (hashed), slotType, existingProvider, incomingProvider, preferredProvider | every artwork tie-break that keeps existing because incoming's provider isn't preferred |

The last event is the **popping watchdog**. Non-zero count on Modern Home soak ≥ 5 minutes = the tie-breaker fix is actively rejecting regressions.

## On-device acceptance gates

### Gate 1 — Settings change is observable on Modern Home

1. Cold-start, soak 60s on Modern Home with Trakt Trending Movies visible (RPDB posters loaded).
2. Navigate to Integration settings → change Poster provider RPDB → Default.
3. Return to Modern Home. Wait up to 10s (visibility re-hydration window).
4. Expected: posters refresh to addon stock (or fanart.tv when integrated).
5. Logcat: `overlay.stale_marked reason=settings_change` fires N times (one per affected overlay).

### Gate 2 — Stale-overlay self-healing (Bug B fix)

1. Fresh install. Cold start.
2. Trakt Trending Movies row loads — first hydration runs before cross-id enricher resolves imdb. Overlays land with `imdbId = null`, `stableIdsSnapshot.imdb = null`.
3. Cross-id enricher resolves imdb in background; calls `markStaleIfWeakerIds`.
4. Logcat: `overlay.stale_marked reason=cross_id_enriched` fires.
5. Within 30s: posters upgrade addon stock → RPDB without user input.

### Gate 3 — Popping does not recur (Bug A fix)

1. Cold-start, soak 5 minutes on Modern Home with multiple rails visible.
2. Periodic producer emissions trigger re-hydration of various items.
3. Logcat: `surface.merge.tie_break_rejected_regression` fires non-zero (proves the tie-breaker is being exercised by transient regressions and rejecting them).
4. Visual: posters do NOT flicker between RPDB and addon during the soak.

### Gate 4 — Heap stability

1. Capture heap dumps before and after the settings-change scenario via heaptrail.
2. `ResolvedDisplayItem` count steady (~700–1400 typical).
3. `HydratedHomeOverlay` count steady (no leak from invalidation cycles).
4. No new top-N class introduced.
5. GC interval > 5 s steady state.

## Component boundaries

| File / class | Responsibility | Touched? |
|---|---|---|
| `domain/model/HydratedHomeOverlay.kt` | Overlay shape + persistence schema | **Extend** — add `stableIdsSnapshot`, `settingsSignature` |
| `domain/model/ProviderIds.kt` | Provider ID bundle | **Extend** — add `strictlyContains` |
| `domain/model/ResolvedDisplayItem.kt` (or `ResolvedDisplaySurfaceModels.kt`) | Surface item shape | **Extend** — add `preferredArtworkProviders` |
| `core/artwork/ArtworkProviderResolver.kt` | DEFAULT semantics + per-(artworkType, contentType) routing | **Create** |
| `core/artwork/ArtworkRouter.kt` | Candidate selection | **Modify** — delegate `selectedProviderFor` to resolver |
| `data/local/HydratedHomeOverlayStore.kt` | Overlay persistence + in-memory cache | **Extend** — add `markStaleIfWeakerIds`, `markStaleAll`; persist + read v2 fields |
| `ui/screens/home/HomeHydrationCoordinator.kt` | Hydration orchestration | **Modify** — stamp provenance onto built overlays; bypass content-equality gate when existing is STALE_READY |
| `ui/screens/home/HomeResolvedDisplayMapper.kt` | MetaPreview → ResolvedDisplayItem projection | **Modify** — compute `preferredArtworkProviders` per item via resolver |
| `data/repository/ResolvedDisplaySurfaceRepository.kt` | Surface merge boundary | **Modify** — `applyNonDowngradeMerge` consults `incoming.preferredArtworkProviders` for tie-breaking artwork slots |
| `data/mapper/CatalogItemCrossIdEnricher.kt` | ID strengthening | **Modify** — inject `HydratedHomeOverlayStore`; push `markStaleIfWeakerIds` when new IDs land |
| `data/invalidation/ArtworkSettingsInvalidator.kt` | Settings change observer | **Create** |
| `core/di/IntegrationRuntimeModule.kt` | DI wiring | **Modify** — bind new components; start the invalidator in the app bootstrap path |

## Test design (informs the plan's TDD steps)

| Unit / integration test | Validates |
|---|---|
| `ArtworkProviderResolverTest` | DEFAULT fall-through to content-type table; explicit RPDB returned when capable; explicit RPDB falls through when capability denied; anime + DEFAULT → addon; non-anime + DEFAULT → addon (today) / fanart (when constant flips) |
| `ProviderIdsStrictlyContainsTest` | gained-only case → true; lost-any case → false; identical → false; empty vs non-empty → false (no IDs gained) |
| `HydratedHomeOverlayStoreInvalidationTest` | `markStaleIfWeakerIds` marks only when current strictly contains snapshot; `markStaleAll` transitions every overlay's state; in-memory only (cold-start re-load returns persisted state) |
| `ResolvedDisplaySurfaceRepositoryTieBreakerTest` | Same-rank artwork slot: incoming with preferred wins; incoming without preferred when existing has preferred → existing wins; both preferred → incoming wins; neither preferred → existing wins; text slot ties remain incoming-wins |
| `ArtworkSettingsInvalidatorTest` | First emission does NOT trigger invalidation; second emission with same signature does NOT trigger; second emission with different signature triggers `markStaleAll("settings_change")` |
| `HomeResolvedDisplayMapperPreferredProvidersTest` | Maps each item's 4 artwork types to the resolver's output; anime detection sources documented; memoization preserves reference identity when settings + item content unchanged |
| `HydratedHomeOverlayStorePersistenceTest` | v1 records load with empty snapshot + empty signature defaults; v2 records round-trip both fields; downgrade (v2 → v1) safe (extra fields ignored by old reader) |
| On-device acceptance gates 1–4 (above) | End-to-end behavior |

## Open questions (resolved during brainstorming)

| Question | Decision |
|---|---|
| DEFAULT semantics | Sentinel + pure resolver maps `(artworkType, contentType)` → effective provider |
| Settings-change invalidation strategy | Lazy mark-stale; re-hydrate on next visibility/focus |
| Stable-IDs-change detection | Push from `CatalogItemCrossIdEnricher` (it knows when IDs strengthen); store's `markStaleIfWeakerIds` does the comparison |
| Same-rank tie-breaker | Settings-preferred provider wins ties at the merge boundary; reducer stays pure |
| Resolver default table location | Hardcoded in `contentTypeDefaults` private object; bumping `DEFAULTS_TABLE_VERSION` is the migration hook for fanart.tv |
| Stale state persistence | Not persisted; cold-start loads overlays as CANONICAL_READY, invalidators re-fire if conditions still apply |
| Anime detection | Caller-side (mapper): `item.apiType == "anime"` OR `item.firstPaintRailSource == RailSource.KITSU` |
| Per-content-type settings UI | Out of scope; settings remain per-artwork-type only |
