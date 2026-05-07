# Android Modern Home Catalog Rail Order RCA

Date: 2026-05-04

Scope: Android modern home, Android catalog order screen, and account/web catalog sync. This is root cause analysis only; no code fixes were applied.

## Summary

The modern home rail order is controlled by more than one ordering source:

- Global home order: `LayoutPreferenceDataStore.homeCatalogOrderKeys`.
- Provider-specific order: Trakt, SIMKL, MDBList, TMDB, and Kitsu `catalogOrder` preferences.
- Cached synthetic rail order: persisted `SyntheticHomeCatalogStore` groups restored at startup and during refresh.

The observed "not always reflected" behavior is consistent with the app sometimes using the intended global order, and other times falling back to provider-block defaults or stale persisted synthetic group order.

## Root Cause

Modern home has no single authoritative rail-order model. The catalog order UI can display a merged list of all rail families, but the home pipeline composes rows from a mix of global order keys, provider-specific order keys, live rows, and persisted synthetic rows.

The primary failure mode is in `HomeViewModelCatalogPipeline.updateCatalogRowsPipeline`:

1. It builds persisted synthetic groups first.
2. It builds live synthetic groups from the current catalog plan.
3. It drops live synthetic groups whose keys already exist in persisted groups.
4. It builds default ordering from the resulting synthetic group order.

Relevant code:

- `currentPreferencePersistedTmdbSyntheticGroups` and Kitsu groups are loaded from persisted state at `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:2340`.
- Persisted synthetic groups are concatenated before live groups at `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:2400`.
- Live groups with duplicate order keys are discarded at `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:2402`.
- `defaultOrderKeys` are then derived from `syntheticGroups.map { it.orderKey }` at `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:2445`.
- Global saved order is applied only after that default list is built at `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:2451`.

When `homeCatalogOrderKeys` is empty, stale, missing some keys, or not updated by the source that changed the rail order, the persisted synthetic group order can become the practical source of truth. Because duplicate live groups are removed, a current provider order can be hidden behind older persisted rows.

## Data Flow

Android catalog order screen:

- `CatalogOrderViewModel` builds one visible list from addons, Trakt, SIMKL, MDBList, Kitsu, and TMDB at `app/src/main/java/com/nexio/tv/ui/screens/addon/CatalogOrderViewModel.kt:257`.
- It applies saved global order first, then appends missing default entries at `app/src/main/java/com/nexio/tv/ui/screens/addon/CatalogOrderViewModel.kt:265`.
- Reordering from this screen writes only `LayoutPreferenceDataStore.setHomeCatalogOrderKeys(...)`, not provider-specific `catalogOrder`, at `app/src/main/java/com/nexio/tv/ui/screens/addon/CatalogOrderViewModel.kt:117`.
- Enabling/disabling TMDB and Kitsu does use their provider stores at `app/src/main/java/com/nexio/tv/ui/screens/addon/CatalogOrderViewModel.kt:83`, but reordering does not.

Modern home planning:

- `buildConfiguredCatalogPlan` builds expected and publishable keys from provider preferences at `app/src/main/java/com/nexio/tv/ui/screens/home/CatalogPlan.kt:138`.
- Provider families are concatenated in fixed family order: Trakt, SIMKL, MDBList, TMDB, Kitsu, addons at `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt:320`.
- SIMKL/TMDB/Kitsu intra-provider order is preserved by their `catalogOrder` helpers, for example SIMKL at `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt:762`, TMDB at `app/src/main/java/com/nexio/tv/data/local/TmdbCatalogSettingsDataStore.kt:50`, and Kitsu at `app/src/main/java/com/nexio/tv/data/local/KitsuCatalogSettingsDataStore.kt:44`.
- Cross-provider ordering depends on `homeCatalogOrderKeys`. Provider-specific order alone cannot move a TMDB rail above a SIMKL rail, for example.

Web/account sync:

- The account sync payload has `catalogs.home`, `catalogs.trakt`, `catalogs.simkl`, and `catalogs.mdblist`, but no `catalogs.tmdb` or `catalogs.kitsu` section at `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt:150`.
- Pull apply writes home, Trakt, SIMKL, and MDBList catalog settings at `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt:336`, but there is no matching TMDB/Kitsu catalog preference apply path.
- `AccountSettingsSyncService` has the same shape: it applies home order plus Trakt/SIMKL catalog preferences at `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt:800`.

This means web-driven configuration can only affect Android modern home order if the web payload updates `catalogs.home.homeCatalogOrderKeys`, or if it updates provider-specific order for providers that Android syncs. TMDB and Kitsu catalog enable/order settings are local-only from this sync model.

## Reproduction Conditions

The issue should reproduce under either of these conditions:

1. Cached synthetic rows exist for a provider, then provider-specific `catalogOrder` changes while `homeCatalogOrderKeys` is empty or does not include those keys. Modern home can continue ordering from persisted synthetic groups because persisted groups are preferred over live duplicate groups.
2. A web/account payload changes provider-specific catalog order but does not provide a complete `catalogs.home.homeCatalogOrderKeys` list. Android modern home can only preserve intra-provider order for synced provider stores, and fixed provider-family ordering remains.
3. A web/account payload changes TMDB or Kitsu catalog settings. The current account sync schema has no TMDB/Kitsu catalog preference sections, so Android will not receive those provider-specific catalog order/enabled changes through this path.

## Why It Is Intermittent

The behavior depends on runtime state:

- Fresh profile with no persisted synthetic groups: live catalog plan order is more likely to show.
- Warm profile with persisted synthetic groups: old group order can win.
- Android catalog order screen reorder: writes global order and is more likely to affect cross-provider modern home ordering.
- Provider settings screen or partial web sync: may write only provider-specific order, which modern home uses only within its fixed family order and can be masked by persisted groups.
- Missing content: publishable keys exclude empty rails, so missing/late source data can temporarily collapse the intended order.

## Existing Coverage

Existing tests cover pieces of the intended behavior:

- `CatalogPlanTest` verifies provider-level configured order and disabled-key filtering.
- `HomeCatalogStartupReadinessTest` verifies expected/publishable order behavior for several provider combinations.

Verification run on 2026-05-04:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.ui.screens.home.CatalogPlanTest --tests com.nexio.tv.ui.screens.home.HomeCatalogStartupReadinessTest
```

Result: passed (`BUILD SUCCESSFUL`).

The uncovered gap is the merge path where persisted synthetic groups already contain the same keys as current live synthetic groups, but in a different order. There is also no sync contract coverage for TMDB/Kitsu catalog preference propagation because the account sync schema does not model those sections.

## Hypothesis

The most likely root cause is stale or incomplete order authority, not a Compose rendering problem. Modern home renders the order it is given; the issue happens before rendering when the row list is composed from persisted synthetic groups, live groups, provider orders, and optional global order.

Confidence: high for the stale persisted synthetic group masking path; high for the TMDB/Kitsu sync schema gap; medium for the exact user-facing scenario without device logs, because the report does not yet specify which provider rails were reordered and whether the change came from Android settings or nexio-web.

## Evidence Needed From A Device

Collect these values when the issue is visible:

- `homeCatalogOrderKeys`
- provider `catalogOrder` and enabled sets for the affected provider
- `SyntheticHomeCatalogStore` group order for Trakt/SIMKL/MDBList/TMDB/Kitsu
- modern home `Catalog order reconciliation saved=... default=... effective=...` log
- `HomeCatalogSnapshotStore.Snapshot.orderedGroupKeys`

Expected confirming signal: `homeCatalogOrderKeys` is empty/stale or missing affected keys, while `SyntheticHomeCatalogStore` contains the affected keys in the order shown on modern home rather than the newly configured provider order.

## Non-Fix Recommendations

These are investigation follow-ups, not implemented fixes:

- Add a failing unit test around `updateCatalogRowsPipeline` or an extracted ordering helper showing persisted synthetic groups in old order and live groups in new order.
- Add sync contract tests asserting whether TMDB/Kitsu catalog settings are intentionally local-only or should be represented in account sync.
- Add temporary diagnostics at the catalog-order boundary to log global order, provider order, persisted synthetic group order, and effective home order in one place.
