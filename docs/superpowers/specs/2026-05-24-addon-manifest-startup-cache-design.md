# Addon Manifest Startup Cache Design

Date: 2026-05-24

## Problem

The startup HAR captured after profile selection shows a manifest refresh storm for installed Stremio addons:

- `torii.nexioapp.org/.../manifest.json`: 21 repeated `308` responses
- `nagare.nexioapp.org/.../manifest.json`: 21 repeated `308` responses
- `jackettio.nexioapp.org/.../manifest.json`: 21 repeated `308` responses
- StremThru, FilmWhisper, Comet, Comet for the Weebs, Meteor, and a StremThru mirror each returned one successful `200` manifest response

The rooted runtime backoff database recorded `Too many follow-up requests: 21` for the same Torii, Nagare, and Jackettio addon scopes. HAR inspection shows each `308` redirects to the same URL, so one logical manifest request expands into OkHttp's redirect-follow limit before failing.

`AddonRepositoryImpl.getInstalledAddons()` currently emits cached manifests, then performs a fresh parallel manifest fetch for every valid installed addon config on every collection. That means normal profile/home startup can revalidate addon manifests without a cache TTL or explicit refresh request.

## Goals

- Keep installed addons usable from the persisted manifest cache during startup/home load.
- Avoid boot/profile-selection network refreshes for addon manifests that already have usable cached entries.
- Fetch manifests for installed addon configs that have no usable cached manifest, so missing-cache addons can become visible.
- Prevent one redirect-looping manifest endpoint from producing 21 network requests in the HAR.
- Preserve explicit network refresh behavior for add/manual refresh flows.
- Keep the implementation narrow because `getInstalledAddons()` has many consumers.

## Non-Goals

- Do not redesign addon sync, addon installation, or stream/meta/catalog request routing.
- Do not remove runtime backoff for addon failures.
- Do not change the Stremio configured-addon URL format.
- Do not make startup depend on all addon manifests being freshly revalidated.

## Current Evidence

Source findings:

- `AddonRepositoryImpl.getInstalledAddons()` is a cold Flow. It emits cached manifests, then calls `fetchAddon(...)` for every valid config.
- `AddonRepositoryImpl.fetchAddon()` builds `manifest.json` via `buildAddonRequestUrl(...)` and calls `AddonManifestIntegrationProvider.getManifest(...)`.
- `AddonManifestIntegrationProvider.getManifest(...)` routes through `IntegrationRuntime` and `AddonApi.getManifest(@Url)`.
- The shared OkHttp client follows redirects.
- `DefaultIntegrationRuntime` maps thrown network exceptions to synthetic failures and records them through `IntegrationBackoffManager`.

Graph findings:

- `AddonRepositoryImpl.getInstalledAddons()` has 105 callers and changing its broad semantics is high risk.
- Direct graph-linked tests for `getInstalledAddons()` are missing.
- Relevant existing test files are `AddonRepositoryImplTest`, `AddonManifestIntegrationProviderTest`, and `AddonSyncCodecTest`.

## Recommended Approach

Use a cache-first startup policy with missing-manifest fetches and a manifest redirect-loop guard.

`getInstalledAddons()` should continue to return a Flow of installed domain addons, but ordinary collection should no longer re-fetch cached addon manifests. The default collection path should:

1. Normalize installed configs.
2. Emit cached manifest entries immediately when present.
3. Identify configs missing from `manifestCache`.
4. Fetch only those missing manifests.
5. Emit an updated list only if missing manifests were fetched successfully.

Explicit addon actions keep network behavior:

- `fetchAddon(baseUrl)` remains the explicit network fetch entry point.
- Addon installation and manual refresh flows can force a manifest fetch and persist the result.
- Future background maintenance can add a TTL policy, but that is outside this first fix.

## API Boundary

Keep the public `AddonRepository.getInstalledAddons()` shape stable. Avoid adding a public refresh-mode parameter to all consumers.

Internally split `AddonRepositoryImpl` into:

- a cached read path that projects `AddonPreferences.AddonInstallConfig` plus `manifestCache` into `Addon`;
- a missing-manifest fetch path used by ordinary `getInstalledAddons()` collection;
- an explicit refresh path used by `fetchAddon(...)` and addon-management actions.

This keeps startup/home behavior quiet without forcing all callers to reason about refresh policy.

## Redirect Handling

Manifest requests should not follow same-URL redirect loops until OkHttp's limit.

The implementation should add a narrow manifest-request guard, preferably in the manifest integration/provider layer rather than global OkHttp configuration. Acceptable designs:

- use a manifest-specific OkHttp/Retrofit path with `followRedirects(false)` and map `3xx` to an HTTP error; or
- add manifest-only logic that detects a same-URL redirect location and returns a clean error before another follow-up.

The behavior should preserve normal success responses. Runtime backoff may still record the failure, but the reason should be one clean manifest failure rather than `Too many follow-up requests: 21`.

## Data Flow

Startup/home collection:

1. `HomeViewModel.observeInstalledAddonsPipeline()` collects `addonRepository.getInstalledAddons()`.
2. `getInstalledAddons()` emits cached installed addons from disk-backed manifest cache.
3. Cached configs are not revalidated during this collection.
4. Missing-cache configs are fetched once through the manifest provider.
5. Successful missing manifests are persisted and emitted.
6. Failed missing manifests are omitted from the emitted domain list without blocking cached addons.

Explicit network refresh:

1. Addon-management or manual refresh calls `fetchAddon(...)`.
2. The manifest provider performs a network fetch.
3. Success persists the manifest cache.
4. Failure is surfaced and may be recorded by integration runtime/backoff.

## Failure Handling

- Cached manifest exists and ordinary collection is used: no network call, no warning, no backoff mutation.
- Missing manifest fetch succeeds: persist and emit it.
- Missing manifest fetch fails: keep cached addons visible and omit only the missing failed addon.
- Redirect-loop manifest URL: produce one clean failure and avoid 21 follow-up requests.
- Existing active backoff: respect runtime policy and fall back to cache when available.

## Testing

Add or update focused tests:

- `AddonRepositoryImplTest`: cached installed manifests do not call `AddonManifestIntegrationProvider` during `getInstalledAddons()`.
- `AddonRepositoryImplTest`: `getInstalledAddons()` fetches only configs missing from cache.
- `AddonRepositoryImplTest`: multiple collectors do not cause network work for cached configs.
- `AddonSyncCodecTest`: configured addon manifest URLs still normalize/build as expected.
- `AddonManifestIntegrationProviderTest` or a transport-level test: same-URL `308` manifest redirect maps to one clean failure and does not produce a redirect-follow storm.

Verification should include the existing narrow addon repository/provider tests. Full app compile may remain noisy if unrelated working-tree changes are present; any such blocker must be reported separately from this fix.

## Risks

- Some callers may have implicitly relied on `getInstalledAddons().first()` to force manifest revalidation. The design intentionally removes that hidden side effect for cached manifests. Explicit refresh callers keep the network path.
- If a cached manifest is stale, startup will use stale addon metadata until an explicit refresh or future maintenance path updates it. This is acceptable for startup performance and avoids boot storms.
- Manifest redirect handling must stay manifest-scoped so stream, catalog, meta, and playback redirect behavior is not changed.

## Decisions

- The first fix will not introduce a TTL. A later background maintenance task can add a bounded TTL refresh after home is usable.
- Manual refresh UI behavior can reuse `fetchAddon(...)`; no new UI is required for this fix.
