# Change: Add Persisted Home Display Metadata Hydration

## Why
Modern Home currently persists raw catalog and continue-watching snapshots, then relies on in-memory and focus-time enrichment to fill in display metadata like logos, descriptions, genres, release info, runtime, and IMDb rating. That produces inconsistent rails: some feeds look rich immediately, others stay sparse until focused, and Continue Watching often never reaches parity. We want Home to render from disk with display-ready metadata as soon as cached feeds load.

## What Changes
- Add a persisted Home display-metadata hydration pipeline that enriches catalog and Continue Watching items before they are published into Home disk snapshots.
- Introduce a shared Home display metadata model so normal catalogs and Continue Watching use the same enriched metadata source for hero and card display.
- Persist enriched metadata in language-aware disk-backed Home snapshots so cold startup restores consistent metadata without waiting for focus-time repair.
- Update refresh flow so changed/new items are hydrated before snapshot publish, with bounded fallback behavior if enrichment is unavailable.
- Add reference tracking and bounded cleanup so metadata for items that drop out of Home feeds is evicted from disk and does not accumulate indefinitely.
- Keep existing focus-time enrichment only as a fallback/repair path, not the primary source of Home display metadata.

## Impact
- Affected specs: `home-display-metadata-cache` (new capability)
- Related pending change: `add-home-disk-first-startup-toggle`
- Affected code:
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`
  - `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
  - `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt`
  - `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt`
  - `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/MetaRepositoryImpl.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt`

## Rollout & Safety
- Persist only Home-display-critical metadata in this phase: title, logo, description, genres, release info/year, runtime, rating, and preferred artwork references.
- Reuse existing locale epoch invalidation so language changes force rehydration of persisted display metadata.
- Apply bounded cleanup on publish and on epoch/version changes to prevent unbounded storage growth when feeds rotate.

## Open Questions
- Should Home display metadata live only inside the persisted Home snapshots, or also in a dedicated reverse-indexed disk store keyed by `type:id` for cross-snapshot reuse?
- What bounded fallback is acceptable when a row refresh completes but one or more items cannot be enriched in time: publish with previous persisted metadata, or publish with raw source metadata?
