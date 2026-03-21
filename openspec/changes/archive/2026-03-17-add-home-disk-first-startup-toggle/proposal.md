# Change: Add Toggleable Disk-First Home Startup Refresh Mode

## Why
Home startup currently performs aggressive foreground/discovery/catalog refresh work during early app resume, which creates avoidable startup jank and nondeterministic first-render behavior. We need a safe migration path that preserves current baseline behavior behind a fallback toggle in case regressions or leaks are detected.

## What Changes
- Add a feature toggle for a new disk-first startup mode (with current behavior preserved as fallback).
- Introduce startup phase gating so heavy network/meta/image refresh work is deferred for a 20s startup window when the toggle is enabled.
- Add persistent disk caches for catalog rows and metadata (language-aware), plus controlled invalidation.
- Add a serialized refresh coordinator for post-startup catalog -> metadata -> image hydration.
- Enforce publish gating: updated rows are shown only after hydration is complete.
- Add telemetry milestones/counters under existing startup perf logging toggle to compare baseline vs disk-first mode.

## Impact
- Affected specs: `home-startup-refresh` (new capability)
- Affected code:
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelIntegrationRefreshPipeline.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt`
  - `app/src/main/java/com/nexio/tv/data/repository/MetaRepositoryImpl.kt`
  - `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
  - `app/src/main/java/com/nexio/tv/data/local/*` (new disk cache stores)
  - `app/src/main/java/com/nexio/tv/core/locale/AppLocaleResolver.kt` integration points

## Rollout & Safety
- Default toggle state: **OFF** (retain current baseline behavior).
- Toggle target: debug/logging settings (with optional remote/account config override later).
- Rollback: disable toggle to immediately revert to current startup behavior without code rollback.

## Open Questions
- Should the toggle be debug-only initially, or exposed in production settings for fast field rollback?
- Should manual user refresh bypass the 20s gate when disk-first mode is enabled?
