# Reactive Home Hydration Overlays Design

Date: 2026-05-02
Status: Approved design for implementation planning

## Problem

Modern Home has a proven first-paint contract and several existing hydration paths, but the bridge from hydration completion back into the rendered home item is inconsistent.

Current code already contains partial mechanisms:

- Addon serial refresh can publish raw first-paint rows and later republish hydrated rows through `HomeCatalogRefreshCoordinator.refreshSerially`.
- Focused and hero enrichment can patch `catalogsMap` and call `scheduleUpdateCatalogRows`.
- Visible hydration resolves stable ID bundles and writes `HomeDisplayMetadata` to `MetadataDiskCacheStore`.

The gap is that visible/background hydration can produce cache data without a guaranteed observed item-level repaint, especially for synthetic API rails rebuilt from provider snapshots. Home can therefore keep showing first-paint preview fields even after metadata, stable IDs, and ratings have arrived.

## Decision

Add a durable, reactive home display overlay layer.

The overlay layer must update Modern Home cards after canonical metadata arrives without blocking first paint, reloading full rows, changing row membership, or adding provider-specific render paths.

The selected approach is:

```text
Persisted HydratedHomeOverlayStore
+ immediate in-memory overlay publish
+ one HomeHydrationCoordinator
+ existing MetadataRouter / stable ID / provider runtime / FieldResolver path
```

No in-memory-only stage is allowed. The implementation must include the durable overlay architecture from the start.

## Non-Goals

- Do not block first paint on metadata, stable ID resolution, ratings, or provider network.
- Do not create a second metadata router, FieldResolver, rating resolver, or provider-specific home renderer.
- Do not store canonical provider metadata inside home rail previews.
- Do not use Trakt or Simkl IDs as tracking targets for scrobble. Scrobble payloads should use title, year, and stable IDs such as IMDb, TMDB, TVDB, or Kitsu.
- Do not reload or rebuild whole rows for metadata-only updates.
- Do not let hydration change row order, rail membership, or focused item identity.

## Architecture

The home lifecycle becomes:

```text
Rail/addon/API payload
    -> FirstPaintPreview / MetaPreview / RailItemPreview
    -> Modern Home renders preview immediately
    -> HomeHydrationCoordinator receives visible/focused/adjacent work
    -> MetadataRouter selects authority
    -> StableIdBundleResolver prepares canonical ID and IMDb sidecar
    -> existing provider runtime / provider plan fetches canonical data
    -> FieldResolver produces selected display fields and traces
    -> HydratedHomeOverlayStore.upsert writes durable overlay
    -> in-memory overlay state publishes same overlay immediately
    -> updateCatalogRowsPipeline composes previews with overlays
    -> affected cards repaint in place
```

`HomeHydrationCoordinator` owns home scheduling and result application only. It must not decide primary authority or call provider-specific endpoints directly.

## Data Model

Add a durable overlay record:

```kotlin
data class HydratedHomeOverlay(
    val overlayKey: String,
    val itemKey: String,
    val canonicalProvider: ProviderId,
    val canonicalId: String,
    val imdbId: String?,
    val contentType: ContentType,
    val languageTag: String,
    val policyVersion: Int,
    val fields: HomeDisplayMetadata,
    val fieldTrace: List<FieldTrace>,
    val displayHash: String,
    val updatedAtMs: Long,
    val staleAtMs: Long,
    val expiresAtMs: Long
)
```

Keying:

```text
overlayKey = canonical:{provider}:{id}:type:{type}:lang:{language}:policy:{version}
itemKey    = current home item key, such as tmdb:550, tt0137523, kitsu:12, or a rail source key
```

The overlay is canonical and language/policy scoped. Item-key aliases map current home cards to the canonical overlay so the same resolved display can update the same title across multiple rails without duplicating metadata per rail.

The store contract should support batched observation and cache-first reads:

```kotlin
interface HydratedHomeOverlayStore {
    fun observeForItemKeys(
        itemKeys: Set<String>,
        languageTag: String,
        policyVersion: Int
    ): Flow<Map<String, HydratedHomeOverlay>>

    suspend fun readByCanonicalIdentity(
        canonicalProvider: ProviderId,
        canonicalId: String,
        contentType: ContentType,
        languageTag: String,
        policyVersion: Int
    ): HydratedHomeOverlay?

    suspend fun upsert(
        overlay: HydratedHomeOverlay,
        aliases: Set<String>
    )
}
```

Default overlay freshness:

```text
staleAt = updatedAt + 24h
expiresAt = updatedAt + 7d
```

Stable ID facts remain cache-first and longer lived than overlays. Overlay expiry must not delete canonical provider metadata.

## Home State Composition

Modern Home should render composed display state:

```text
first-paint preview
+ hydrated home overlay
+ profile overlays
+ artwork and rating decisions
= rendered home card
```

In the current home pipeline, this means:

- Keep `catalogsMap`, synthetic snapshots, and first-paint rows as membership and preview-order state.
- Add an observed overlay map in `HomeViewModel`, keyed by current item keys.
- Apply overlays inside the same `updateCatalogRowsPipeline` path before publishing `_uiState.catalogRows`.
- When a hydration result arrives, write it to `HydratedHomeOverlayStore` and push it into in-memory overlay state, then schedule a row-safe recomposition.

Field selection is fallback based:

```text
title    = hydrated primary title -> preview title
poster   = hydrated/artwork poster -> preview poster -> placeholder
overview = hydrated primary overview -> preview overview
rating   = rating resolver / IMDb overlay -> preview rating
```

Overlay application must preserve preview ownership semantics. `RAIL_PREVIEW` and `ADDON_PREVIEW` are display fallback roles, not final field owners after canonical data succeeds.

## HomeHydrationCoordinator

Add a single coordinator for Modern Home hydration work.

Responsibilities:

- Accept visible, focused, adjacent, and hero item candidates from `HomeViewModel`.
- Prioritize work as:

```text
P0 focused item
P1 visible items
P2 adjacent +/- 2 items
P3 hero candidates
```

- Start only after first paint has been published.
- Resolve stable ID bundles cache-first.
- Hydrate canonical display through the existing router/facade/runtime path.
- Apply rating enrichment using the stable IMDb sidecar when available.
- Build `HydratedHomeOverlay` from FieldResolver-selected display fields.
- Write the overlay and aliases to durable storage.
- Publish the same overlay into in-memory state immediately.
- Drop late results when profile, language, generation, or session no longer matches.

The coordinator must not:

- decide movie vs TV vs anime authority
- call TMDB, TVDB, Kitsu, Trakt, Simkl, or IMDb APIs directly
- hydrate entire rows
- mutate rail membership or row order
- bypass existing runtime/cache policy

The current visible hydration behavior should change from:

```text
hydrate visible item -> write HomeDisplayMetadata to disk cache -> no guaranteed observed repaint
```

to:

```text
hydrate visible item
    -> FieldResolver/rating result
    -> HydratedHomeOverlayStore.upsert
    -> in-memory overlay map update
    -> schedule item-level safe home recomposition
```

## Failure Behavior

- If identity is unresolved, keep preview visible and mark the item as `FAILED_USING_PREVIEW`.
- If provider or ratings network fails, keep preview visible and negative-cache only where existing policy allows it.
- If a cache hit exists, apply the overlay without network.
- If profile, language, or generation changes while hydration is running, ignore the late result and emit a trace event.
- If the overlay display hash has not changed, skip UI publication.

## Observability

Add home-specific trace events:

```text
home.first_paint_applied
home.hydration_started
home.hydration_overlay_written
home.hydration_applied
home.hydration_ignored
home.hydration_failed_using_preview
```

Each event should include enough fields to validate device behavior:

```text
railId
itemKey
firstPaintSource
canonicalProvider
canonicalId
imdbId
trigger
priority
workClass
changedFields
displayHashBefore
displayHashAfter
rowOrderChanged
focusChanged
networkExecuted
cacheDecision
ignoreReason
```

Execution reports should add scenarios that prove before/after home updates:

```text
addon_first_paint_then_hydrated_home_update
trakt_rail_first_paint_then_tvdb_update
tmdb_movie_rail_first_paint_then_tmdb_update
tmdb_tv_rail_first_paint_then_tvdb_update
kitsu_rail_first_paint_then_kitsu_update
simkl_rail_first_paint_then_tmdb_update
hydration_failure_keeps_preview
cache_hit_updates_home_without_network
focused_item_hydrates_before_offscreen_items
hydration_result_ignored_after_profile_switch
```

## Testing

Required tests:

- First paint does not call MetadataRouter, ProviderPlanRunner, rating APIs, or metadata runtime.
- Visible hydration writes a hydrated overlay and updates the current home card.
- Focused hydration uses the same coordinator/store path as visible hydration.
- Hero hydration uses the same coordinator/store path or a documented wrapper feeding it.
- TMDB movie rail first paints from TMDB payload, resolves IMDb sidecar, and applies rating overlay.
- TMDB TV rail first paints from TMDB payload and applies TVDB canonical overlay.
- Kitsu rail first paints and hydrates Kitsu without TVDB/TMDB fallback for explicit anime IDs.
- Trakt rail first paints title/year and later applies TVDB/TMDB overlay.
- Cache-hit overlay updates home without network.
- Network failure keeps preview visible.
- Profile, language, or generation mismatch ignores late overlay.
- Row order and focus key remain stable after overlay application.
- Overlay display hash avoids redundant republish.

## Acceptance Criteria

- API and addon rails show preview immediately.
- Hydration arrival visibly repaints existing cards with canonical fields.
- IMDb-backed ratings can update TMDB rail cards after stable ID sidecar resolution.
- Kitsu/anime rows do not route through TVDB or TMDB fallback when an explicit anime-only ID is present.
- Cache hits apply home updates without network.
- Network results apply home updates without full row reload.
- Offscreen items are not aggressively hydrated.
- Home updates do not cause focus loss or row reordering.
- No provider-specific renderers, hydration schedulers, or field merge rules are introduced.

## OpenSpec Follow-Up

Implementation planning should create an OpenSpec change with a verb-led ID and deltas for the relevant home/metadata capabilities. The change should include strict scenarios for first paint, overlay write, overlay application, cache hit, failure fallback, and profile-switch ignore behavior.
