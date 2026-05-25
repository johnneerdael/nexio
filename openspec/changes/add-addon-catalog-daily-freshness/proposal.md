# Add addon catalog daily freshness

## Why

The HAR from `HTTPToolkit_2026-05-25_17-03.har` shows repeated identical addon catalog GETs during one Home session, including 7-8 requests per exact Top Streaming and Torrentio catalog URL. These requests carry app-level cache bypass headers because `NetworkModule.disableDiskCacheForGetRequests()` deliberately prevents OkHttp from owning freshness for app-managed integration data.

That transport policy is defensible when the app decides to go to network, but addon catalog rows currently have no freshness gate in the repository refresh path. `HomeCatalogRefreshCoordinator.refreshSerially()` calls `CatalogRepositoryImpl.refreshCatalogToDisk()` for each enabled catalog, and `refreshCatalogToDisk()` always performs a network refresh before writing `CatalogDiskCacheStore`.

Addon catalog rails are discovery snapshots, not account progress or playback streams. They should remain stable for daily Home usage and should not refetch the same provider URL repeatedly within one day.

## What changes

- Add a repository-owned 24-hour fresh TTL for addon catalog rows persisted in `CatalogDiskCacheStore`.
- Make `CatalogRepositoryImpl.refreshCatalogToDisk()` return a fresh cached row without calling `AddonCatalogIntegrationProvider.getCatalog()`.
- When a cached row is stale, keep the current network refresh and disk write behavior.
- If a stale network refresh fails and a cached row exists, return the stale cached row so Home rails continue rendering.
- Keep `NetworkModule.disableDiskCacheForGetRequests()` unchanged for addon catalog traffic; when the app intentionally refreshes, app-level cache policy still owns freshness instead of OkHttp.

## What does not change

- No Home-specific catalog freshness logic in `HomeCatalogRefreshCoordinator`.
- No change to first-paint publish ordering or provider metadata hydration ordering.
- No migration of addon catalog DTO calls from `IntegrationCallSpec` to `IntegrationSpec` in this first fix.
- No broad removal of cache-bypass headers from addon, Kitsu, Trakt, MDBList, Simkl, or benchmark clients.

