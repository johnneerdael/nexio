# Cross-Client Home Catalog Rails — Design

Date: 2026-05-12
Status: Draft (brainstorming complete; pending user review)

Related:
- `docs/superpowers/plans/2026-05-12-supabase-contract-v10-timestamps.md`
- `docs/superpowers/specs/2026-05-05-modern-home-rail-order-authority-design.md`
- `docs/superpowers/plans/2026-04-20-tmdb-primary-search-and-stock-catalogs.md`
- `docs/superpowers/specs/2026-05-10-catalog-inventory-repository-design.md`

## Problem

Android now has stock TMDB and Kitsu catalog rails, but `nexio-web` does not expose those rails in the catalog management view. More broadly, Modern Home catalog display is split across multiple partial authorities:

- Legacy home order and hidden keys: `catalogs.home.homeCatalogOrderKeys` and `catalogs.home.disabledHomeCatalogKeys`.
- Provider catalog settings: Trakt, SIMKL, MDBList, TMDB, and Kitsu enabled/order fields.
- Addon catalog inventory from installed addon manifests.
- Android's `HomeRailOrderStore` and reconciler.
- Web's `CatalogInventory` view and portal settings sanitizer.

That split makes it hard to guarantee that Android and web show and manage the same Modern Home catalog rails. The desired outcome is stronger than "web can edit similar settings": both clients must operate on the same ordered displayed rail list, so a change on either side is reflected on the other side.

## User-Approved Decisions

- Use a new explicit `catalogs.home.rails[]` model as the shared cross-client source of truth for displayed Modern Home catalog rails.
- Removing a rail from Modern Home hides it from Modern Home only. It does not disable the provider catalog or remove provider configuration.
- Manage catalogs with a **Visible List + Add Catalog** UI on both Android and web.
- Align conflict behavior with the Supabase contract v10 timestamp architecture. Do not introduce a custom catalog conflict system.
- Store `catalogs.home.rails[]` in the existing profile settings blob v10 surface, not a new table/sync surface.
- Migrate existing users by preserving their current visible Modern Home exactly. Hidden, disabled, and never-added candidates move to Add Catalog.

## Goals

- Android and web render the same Modern Home catalog rail order from one shared model.
- Android and web management actions write the same profile settings field.
- Web includes stock TMDB and Kitsu catalog candidates alongside addon, Trakt, SIMKL, and MDBList candidates.
- Users with many hidden or disabled candidate rails manage a short visible list, while Add Catalog exposes the full candidate inventory.
- Existing users see no surprise Modern Home layout reset during migration.
- Cross-client writes are protected by the v10 profile-settings stale-base contract.

## Non-Goals

- No new Supabase table or independent catalog sync surface.
- No operation-log conflict model for rail edits.
- No removal of legacy fields in this change. `homeCatalogOrderKeys`, `disabledHomeCatalogKeys`, and provider catalog fields remain for compatibility and staged migration.
- No change to item order inside a catalog rail.
- No change to TMDB/Kitsu discovery APIs or metadata routing.

## Architecture

`catalogs.home.rails[]` becomes the only cross-client display authority for Modern Home catalog rails. Android and web both render home catalog rail membership/order from this array. Array position is display order.

```text
catalog inventory sources
  ├─ addon manifest catalogs
  ├─ Trakt built-ins + selected lists
  ├─ SIMKL built-ins
  ├─ MDBList selected/personal/top lists
  ├─ TMDB stock catalogs
  └─ Kitsu stock catalogs
            │
            v
     candidate inventory
            │
            ├─ Modern Home display: catalogs.home.rails[] ∩ inventory
            └─ Add Catalog: inventory - catalogs.home.rails[]

Android Manage Catalogs ─┐
                         ├─ v10 profile settings blob ─ catalogs.home.rails[]
nexio-web Manage Catalogs ┘
```

Provider catalog settings still exist, but their responsibility is provider configuration and candidate availability. They no longer decide final Modern Home membership. For example, `catalogs.tmdb.catalogEnabledSet` can still express TMDB catalog availability/options, but whether `tmdb_trending_movies` appears on Modern Home is decided by `catalogs.home.rails[]`.

## Data Model

Add a versioned home rail model to the existing profile settings blob:

```ts
type HomeCatalogRailFamily =
  | 'addon'
  | 'trakt'
  | 'simkl'
  | 'mdblist'
  | 'tmdb'
  | 'kitsu'

type HomeCatalogRailSource =
  | 'addon_catalog'
  | 'provider_catalog'
  | 'provider_list'

type HomeCatalogRail = {
  key: string
  family: HomeCatalogRailFamily
  source: HomeCatalogRailSource
  title: string
  enabled: true
  addedAtMs?: number
}

type HomeCatalogSyncSettings = {
  railsVersion: 1
  rails: HomeCatalogRail[]

  // Legacy compatibility during migration.
  heroCatalogKeys: string[]
  homeCatalogOrderKeys: string[]
  disabledHomeCatalogKeys: string[]
}
```

For this feature, membership is binary:

- Present in `rails[]` means displayed on Modern Home, assuming the candidate is still available.
- Absent from `rails[]` means not displayed and eligible for Add Catalog.
- `enabled` remains in the model for future compatibility, but the initial implementation writes `true` and treats absent as hidden. The management UI only shows displayed rails.

### Key Rules

- `key` must match the existing stable rail key used by Android/web candidate inventory:
  - TMDB: `tmdb_trending_movies`, `tmdb_popular_series`, etc.
  - Kitsu: `kitsu_trending_anime`, etc.
  - Trakt/SIMKL/MDBList: existing provider/list keys.
  - Addons: existing addon catalog key format.
- Duplicates sanitize with first entry wins.
- Unknown keys stay in the blob but do not render until matching inventory returns.
- Missing title is filled from inventory at read time. Persisted `title` is a display fallback, not the identity authority.

## Management UI

Both clients converge on **Visible List + Add Catalog**.

### Visible List

The main management view shows only `catalogs.home.rails[]`, in order. It supports:

- Reorder visible rails.
- Remove a rail from Modern Home.
- Save/sync changes through the profile settings blob.
- Show unavailable rails if a persisted rail key no longer exists in the current inventory, with a remove action.

This keeps the primary management screen small for users with many disabled or unused candidate rails.

### Add Catalog

Add Catalog opens a searchable modal/sheet derived from:

```text
all candidate inventory keys - keys already present in catalogs.home.rails[]
```

It includes addon catalogs and stock/provider candidates:

- TMDB stock catalogs.
- Kitsu stock catalogs.
- Trakt built-ins and selected lists.
- SIMKL built-ins.
- MDBList selected/personal/top lists.
- Installed addon catalogs.

Adding a catalog appends it to `rails[]` by default. A future enhancement may insert after the currently focused rail, but append is the baseline.

### Provider Settings Relationship

Provider settings screens can continue to manage provider-specific options. They are not the primary display membership UI.

If a provider setting makes a rail unavailable while that rail is present in `rails[]`, Modern Home skips the rail and management shows it as unavailable/removable. The client must not silently replace it with another rail.

## Sync Contract

`catalogs.home.rails[]` lives inside the existing profile settings blob and uses the v10 profile-settings watermark from `docs/superpowers/plans/2026-05-12-supabase-contract-v10-timestamps.md`.

The model is profile-scoped. Each profile owns its own `catalogs.home.rails[]`; edits for one profile must not mutate another profile's home layout. During rollout, any client surface that still treats profile 1/default profile catalog settings as account-level settings must bridge those reads/writes into the profile settings blob before treating `rails[]` as authoritative. The end state is that all profile home rail edits route through the v10 profile-settings surface.

Write behavior:

1. Client reads profile settings with `updated_at_ms`.
2. Client edits `catalogs.home.rails[]`.
3. Client pushes with `p_base_updated_at_ms`.
4. Supabase rejects stale writes with `reason='stale_base'`.
5. Client pulls the latest blob.
6. Client rebases simple non-conflicting changes and retries, or reports the latest order when reorder conflicts cannot be safely rebased.

No custom catalog conflict model is introduced.

## Migration

Existing users are migrated by preserving their current visible Modern Home exactly.

Migration input:

- Legacy `homeCatalogOrderKeys`.
- Legacy `disabledHomeCatalogKeys`.
- Provider enabled/order settings.
- Addon manifest catalog inventory.
- TMDB/Kitsu catalog preferences.
- Android effective rail order definitions where available.

Migration output:

- `catalogs.home.rails[]` contains only the rails currently visible on Modern Home, in current visible order.
- Hidden, disabled, unknown, unavailable, and never-added candidates are excluded from `rails[]`.
- Excluded candidates are still available through Add Catalog when present in inventory.
- Legacy fields remain populated for compatibility during rollout.

If a client receives a profile settings blob without `rails[]`, it derives `rails[]` with this migration rule before rendering the management UI. The derived value should be persisted on the next profile settings write.

## Client Responsibilities

### Android

- Teach profile settings sync models to decode/encode `catalogs.home.rails[]`.
- Adapt `HomeRailOrderStore`/Modern Home projection so `rails[]` is the preferred source when present.
- Preserve legacy compatibility for profiles without `rails[]`.
- Add Catalog inventory includes addon, Trakt, SIMKL, MDBList, TMDB, and Kitsu candidates.
- Catalog management writes `rails[]` for add/remove/reorder actions.

### nexio-web

- Extend portal types/defaults/sanitizers/profile blob mapping with `catalogs.home.rails[]`.
- Add TMDB and Kitsu catalog sections/candidates to web inventory.
- Update `CatalogInventory` to show visible rails only and add an Add Catalog modal/sheet.
- Ensure account/profile management writes profile settings through the v10 profile-settings stale-base path.
- Preserve legacy fields until Android and web no longer need compatibility writes.

## Error Handling

| Scenario | Behavior |
|---|---|
| Unknown key in `rails[]` | Keep in blob; skip rendering on Modern Home; show unavailable/removable in management if useful. |
| Duplicate key in `rails[]` | First wins; later duplicates dropped during sanitize. |
| Candidate unavailable due to provider/auth/addon removal | Skip on Modern Home; show unavailable/removable in management; keep key until user removes or inventory returns. |
| Add Catalog candidate already displayed | Exclude from Add Catalog. |
| Stale v10 profile settings write | Pull latest, rebase simple add/remove when possible, retry; otherwise surface latest order. |
| Legacy-only blob | Derive `rails[]` from current visible home order and persist on next write. |
| Web lacks TMDB/Kitsu provider section in older blob | Use defaults from shared portal defaults and expose candidates according to current stock catalog defaults. |

## Testing Strategy

### Shared Fixtures

Create Android/web parity fixtures for the same profile settings blob:

- Legacy-only blob with addon + provider rails.
- Blob with `rails[]` containing TMDB and Kitsu stock rails.
- Blob with duplicates and unknown keys.
- Blob with unavailable provider/addon candidates.

Expected output is the same ordered displayed key list on Android and web.

### Android Tests

- `rails[]` wins over legacy `homeCatalogOrderKeys` when present.
- Legacy migration preserves effective visible Modern Home order.
- Removing a rail deletes it from `rails[]` only and does not disable provider settings.
- Add Catalog inventory excludes displayed rails and includes TMDB/Kitsu candidates.
- Unknown/unavailable rails do not render on Modern Home.
- v10 stale profile-settings write triggers pull/rebase/retry or conflict surfacing.

### Web Tests

- Portal types/defaults include `catalogs.home.rails[]`, `catalogs.tmdb`, and `catalogs.kitsu`.
- Profile settings blob decode/encode round-trips `rails[]`.
- Catalog inventory includes stock TMDB and Kitsu candidates.
- Visible manager shows only `rails[]`.
- Add Catalog excludes currently displayed rails.
- Legacy migration fixture matches Android expected displayed order.
- Stale-base rejection from v10 profile settings does not silently clobber newer Android changes.

## Acceptance Criteria

- A rail added/reordered/removed on Android appears with the same Modern Home catalog view/order on web after sync.
- A rail added/reordered/removed on web appears with the same Modern Home catalog view/order on Android after sync.
- TMDB and Kitsu stock catalog candidates are visible and addable in `nexio-web`.
- Existing users keep their current visible Modern Home order after migration.
- Hidden or disabled rails do not clutter the main management view; they are available through Add Catalog when inventory supports them.
- Stale cross-client writes are handled by the v10 profile-settings watermark path.
