## 1. Toggle & Startup Gating
- [x] 1.1 Add `diskFirstHomeStartupEnabled` setting and wire into Home startup pipeline.
- [x] 1.2 Add startup phase state (`startupWindowOpenUntilMs`, `isStartupDeferredRefreshAllowed`) and 20s gating logic.
- [x] 1.3 Keep legacy baseline behavior path intact when toggle is OFF.

## 2. Catalog Disk Cache
- [x] 2.1 Add `CatalogDiskCacheStore` for persisted catalog rows + version hash metadata.
- [x] 2.2 Extend `CatalogRepository` with cached-first and refresh-to-disk APIs.
- [x] 2.3 Integrate repository flow so startup uses disk rows first, defers network refresh to Phase B.

## 3. Metadata Disk Cache + Locale Invalidation
- [x] 3.1 Add `MetadataDiskCacheStore` keyed by `type:id` + `languageTag` (+ epoch/version metadata).
- [x] 3.2 Update `MetaRepositoryImpl` and `TmdbMetadataService` for disk-first read and write-through behavior.
- [x] 3.3 Add locale-change invalidation path (epoch bump/mark stale) via `AppLocaleResolver` observation.

## 4. Serialized Refresh Coordinator
- [x] 4.1 Add `HomeCatalogRefreshCoordinator` single-worker pipeline for catalog -> metadata -> image.
- [x] 4.2 Implement row diffing (added/retained/removed) and hydration queueing for new/changed items only.
- [x] 4.3 Gate UI row publish until hydration completion, then atomically publish.

## 5. Cleanup & Concurrency Budgets
- [x] 5.1 Add reverse index and bounded cleanup for unreferenced metadata/images.
- [x] 5.2 Add startup/normal concurrency caps (startup 1-2, normal 4) and serialized metadata hydration.

## 6. Telemetry & Validation
- [x] 6.1 Add milestone logs and counters behind startup telemetry toggle.
- [x] 6.2 Add/extend unit tests for cache keying, diffing, invalidation, and publish gating.
- [x] 6.3 Execute profiling checklist: startup network silence window, frame-time/CPU comparison, locale rehydrate behavior.
