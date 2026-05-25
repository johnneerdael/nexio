# Addon Catalog Daily Freshness Design

## Context

`HTTPToolkit_2026-05-25_17-03.har` showed repeated identical addon catalog GETs during a single Home session, especially Top Streaming and Torrentio catalog URLs fetched 7-8 times. The requests carry `Cache-Control: no-cache` and `Pragma: no-cache` because `NetworkModule.disableDiskCacheForGetRequests()` intentionally prevents OkHttp disk cache from owning freshness for app-managed integration data.

The problematic behavior is not the transport header by itself. The addon catalog refresh path has no app-level freshness gate:

- `HomeCatalogRefreshCoordinator.refreshSerially()` iterates enabled addon catalogs.
- `CatalogRepositoryImpl.refreshCatalogToDisk()` always fetches network and writes `CatalogDiskCacheStore`.
- `AddonCatalogIntegrationProvider.getCatalog()` routes through `IntegrationRuntime.call(...)`, which provides runtime gating/tracing but no cache policy.

Addon catalog rails are discovery snapshots rather than account progress or playback streams, so a daily freshness window is appropriate.

## Approved Direction

Implement repository-owned 24-hour freshness for addon catalog rows.

`CatalogRepositoryImpl.refreshCatalogToDisk()` should read the existing `CatalogDiskCacheStore` row before network. If the row exists and is less than 24 hours old, return it without calling `AddonCatalogIntegrationProvider.getCatalog()` and without rewriting the row only to update the timestamp.

If the cache row is missing or at least 24 hours old, keep the current network fetch, mapping, enrichment, memory cache update, and disk write behavior. If that stale refresh fails and a cached row exists, return the stale cached row so Home remains renderable.

Keep `NetworkModule.disableDiskCacheForGetRequests()` unchanged for this fix. When the app decides a catalog is stale, the network request should still bypass OkHttp disk cache so the repository policy remains the freshness authority.

## Rejected Alternatives

Runtime `CacheFirst` for addon catalog DTOs is architecturally attractive, but it is broader than the first fix. The current durable cache stores mapped `CatalogRow` objects with existing cache keys, provider cache tokens, and downstream Home behavior. Moving DTO fetches into `IntegrationSpec` should be considered later after the repository behavior is locked by tests.

Coordinator-level freshness gating is too high in the stack. It would hide catalog freshness inside Home orchestration and make other repository callers easier to misuse.

Removing no-cache headers globally is unsafe. The original helper exists because app caches, not OkHttp, should own freshness for these integrations.

## Test Plan

Add focused tests that prove:

- fresh cached rows skip `AddonCatalogIntegrationProvider.getCatalog()`
- stale cached rows call the provider and rewrite disk on success
- stale cached rows are returned when network refresh fails
- cache-key isolation still includes addon base URL, addon id, type, catalog id, skip, extra args, and provider cache token
- Home first-paint publish and provider hydration ordering stay unchanged when the repository returns a cached row

Run the focused repository and coordinator tests, then validate the OpenSpec change with:

```bash
openspec validate add-addon-catalog-daily-freshness --strict
```

