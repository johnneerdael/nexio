# Modern Home Rail Order Authority — Design

Date: 2026-05-05
Status: Draft (brainstorming complete; pending user review)
Related: `review-dossier/android-modern-home-catalog-rail-order-rca.md`
OpenSpec changes:
- `openspec/changes/make-modern-home-rail-order-authoritative-and-reactive/`
- `openspec/changes/extend-account-sync-with-tmdb-kitsu-catalogs/` (depends on the foundation change)

## Problem

Modern Home rail order is currently a function of three partial authorities:

- Global home order (`LayoutPreferenceDataStore.homeCatalogOrderKeys`).
- Provider-specific order (Trakt, SIMKL, MDBList, TMDB, Kitsu `catalogOrder`).
- Persisted synthetic group order (`SyntheticHomeCatalogStore`).

`HomeViewModelCatalogPipeline.updateCatalogRowsPipeline` builds persisted synthetic groups first, concatenates live groups, drops live duplicates, and derives default ordering from the resulting synthetic group order. As a result, stale persisted synthetic order can mask current live provider order. Order/enabled changes can also fail to apply until restart or a full refresh. Account sync only models `home`, `trakt`, `simkl`, and `mdblist` catalog sections — TMDB and Kitsu catalog enable/order are not synchronizable.

The RCA classifies this as a single-source-of-truth problem.

## Goal

Modern Home rail order is authoritative, reactive, and migration-safe:

- A single authoritative model — `EffectiveHomeRailOrder` — determines row order.
- Persisted synthetic state is content cache only and never determines order.
- Order/enabled mutations from the catalog order screen, provider settings screens, and account sync apply immediately to Modern Home with no app restart and no forced network refetch.
- Existing users see no rail-order regression on the first launch after the upgrade.
- Account sync covers TMDB and Kitsu catalog settings on equal footing with Trakt, SIMKL, and MDBList.

## Non-goals

- No changes to `MetadataRouter` or per-item rail hydration.
- No change to item order *inside* a provider rail payload.
- No forced network refetch when rows are reordered or hidden.
- No changes to addon sync semantics.

## Scope split

Two dependent OpenSpec changes:

1. **`make-modern-home-rail-order-authoritative-and-reactive`** (foundation). Introduces the authoritative model, store, reconciler, pipeline rewrite, migration shim, synth-store semantics change, provider-settings write-through, and diagnostics. Closes the RCA bug. Lands first.
2. **`extend-account-sync-with-tmdb-kitsu-catalogs`** (sync extension, depends on 1). Adds `catalogs.tmdb` and `catalogs.kitsu` sections to account sync, with apply paths that write to provider stores and write-through to `HomeRailOrderStore`. Closes the sync gap.

## Architecture

```text
catalog order screen     provider settings (T/S/M/T/K)     account sync
        \                          |                           /
         \                         v                          /
          \-------- HomeRailOrderStore.updateOrder / setEnabled / reorderProviderKeys
                              |
                              v
                    HomeRailOrderState  (per-profile, persisted)
                              |
                              v
              HomeRailOrderReconciler.reconcile(saved, disabled, liveDefinitions)
                              |
                              v
                     EffectiveHomeRailOrder  (StateFlow)
                              |
                              v
                  updateCatalogRowsPipeline  -- materializes content per key
                              |
                              v
                          Modern Home rows
```

Persisted synthetic groups are read by key *after* `EffectiveHomeRailOrder` is computed and only as a content fallback when no live group exists for a visible key.

## Core model

Package: `com.nexio.tv.ui.screens.home.order`

```kotlin
@JvmInline value class HomeRailKey(val value: String)

enum class RailFamily { TRAKT, SIMKL, MDBLIST, TMDB, KITSU, ADDON }

enum class RailSource { PROVIDER_PUBLIC, PROVIDER_USER, ADDON_CATALOG }

enum class RailPublishPolicy {
    PUBLISH_ALWAYS,
    PUBLISH_WHEN_NON_EMPTY,
    PUBLISH_ON_FIRST_PAINT,
}

data class HomeRailDefinition(
    val key: HomeRailKey,
    val family: RailFamily,
    val source: RailSource,
    val title: String,
    val enabled: Boolean,
    val defaultSortKey: DefaultSortKey,
    val publishPolicy: RailPublishPolicy,
)

data class DefaultSortKey(
    val familyRank: Int,        // fixed family order: TRAKT=0, SIMKL=1, MDBLIST=2, TMDB=3, KITSU=4, ADDON=5
    val intraFamilyRank: Int,   // index within the provider's catalogOrder (or addon order)
)

enum class RailOrderMutationSource {
    ANDROID_ORDER_SCREEN,
    PROVIDER_SETTINGS_SCREEN,
    ACCOUNT_SYNC,
    DEFAULT_BOOTSTRAP,
    MIGRATION,
    MIGRATION_SYNTHETIC_FALLBACK,  // temporary state until liveDefinitions arrive on first read
    DEBUG_RESET,
}

data class HomeRailOrderState(
    val orderedKeys: List<HomeRailKey>,
    val disabledKeys: Set<HomeRailKey>,
    val version: Long,
    val updatedAtMs: Long,
    val lastMutationSource: RailOrderMutationSource,
)

data class EffectiveHomeRailOrder(
    val visibleKeys: List<HomeRailKey>,
    val disabledKeys: Set<HomeRailKey>,
    val unknownSavedKeys: List<HomeRailKey>,
    val newlyDiscoveredKeys: List<HomeRailKey>,
    val prunedKeys: List<HomeRailKey>,
    val sourceTrace: RailOrderTrace,
)
```

`HomeRailKey` is a value class wrapper around the existing string key. **No key-format change** is introduced; existing strings such as `tmdb:popular:movies` or `addon:{addonIdHash}:catalog:{type}:{id}` are accepted as-is.

`RailOwner` is intentionally not modeled in the type system. Instead, the system enforces an explicit scoping invariant:

> **Scoping invariant.** For any rail whose content depends on the active profile or on a specific provider account/credential ("account-owned rails" — Trakt user lists, SIMKL user lists, MDBList personal lists, etc.), at least one of the following SHALL hold:
> 1. The `HomeRailKey` includes the account scope in its string form (e.g., `trakt:user-list:{accountHash}:{listIdHash}`), so two different accounts cannot collide on the same key, **or**
> 2. The store namespace that holds the key is profile-and-account-scoped (i.e., `HomeRailOrderStore` and `SyntheticHomeCatalogStore` are read/written under a key set scoped to the active profile id and the relevant credential hash for that family), so two different profiles or accounts cannot collide on the same store entry.
>
> It is forbidden for two different profiles or two different provider accounts to share the same account-owned rail key in the same store namespace. Any code path that allocates an account-owned rail key SHALL verify (in tests) that switching the active profile or re-authenticating the provider does not reuse the previous account's key against the same store.

The current `LayoutPreferenceDataStore.profileFlow { ... }` pattern already satisfies condition 2 for the per-profile dimension. Per-provider-credential scoping is satisfied today either by encoding `accountHash` / `credentialHash` into the key string (Trakt user lists, MDBList personal lists) or by the provider store being implicitly account-scoped (single-account integrations). Public/global rails (`tmdb:popular:movies`, `simkl:discovery:movies:trending:today`) are not account-owned and have no scoping requirement beyond profile.

**Tests required**:

- `account_owned_keys_either_include_account_scope_or_live_in_account_scoped_store`
- `profile2_account_owned_keys_do_not_collide_with_profile1`
- `profile_switch_does_not_reuse_account_rail_order_from_previous_profile`
- `provider_re_authentication_with_a_different_account_does_not_reuse_previous_account_rails_in_HomeRailOrderState`

## Reconciler

```kotlin
fun reconcile(
    savedGlobalOrder: List<HomeRailKey>,
    disabledKeys: Set<HomeRailKey>,
    liveDefinitions: List<HomeRailDefinition>,
): EffectiveHomeRailOrder {
    val liveKeys = liveDefinitions.map { it.key }.toSet()
    val enabledLive = liveDefinitions.filter { it.enabled && it.key !in disabledKeys }
    val enabledKeys = enabledLive.map { it.key }.toSet()

    val savedKnownEnabled = savedGlobalOrder.filter { it in enabledKeys }
    val missingEnabled = enabledLive
        .filter { it.key !in savedKnownEnabled }
        .sortedWith(compareBy({ it.defaultSortKey.familyRank }, { it.defaultSortKey.intraFamilyRank }))
        .map { it.key }

    return EffectiveHomeRailOrder(
        visibleKeys = savedKnownEnabled + missingEnabled,
        disabledKeys = disabledKeys,
        unknownSavedKeys = savedGlobalOrder.filter { it !in liveKeys },
        newlyDiscoveredKeys = missingEnabled,
        prunedKeys = savedGlobalOrder.filter { it !in enabledKeys && it in liveKeys },
        sourceTrace = RailOrderTrace(savedGlobalOrder, liveDefinitions, disabledKeys),
    )
}
```

Rules:

- Saved global order wins for known enabled keys.
- Disabled keys are removed immediately (whether disabled via `disabledKeys` or via provider `enabled = false`).
- New enabled keys not in saved order are appended in family-rank then intra-family-rank order.
- Unknown saved keys are kept on the persisted list (in case the provider/addon comes back) but are not visible.
- Persisted synthetic group order is **never** an input.

## Pipeline rewrite

`HomeViewModelCatalogPipeline.updateCatalogRowsPipeline` is changed to:

```kotlin
val liveDefinitions = catalogPlan.railDefinitions()
val state = homeRailOrderStore.state.value     // already collected as StateFlow
val effective = reconciler.reconcile(
    savedGlobalOrder = state.orderedKeys,
    disabledKeys = state.disabledKeys,
    liveDefinitions = liveDefinitions,
)

val liveByKey       = liveSyntheticGroups.associateBy { it.orderKey.toRailKey() }
val persistedByKey  = persistedSyntheticGroups.associateBy { it.orderKey.toRailKey() }

val finalGroups = effective.visibleKeys.mapNotNull { key ->
    liveByKey[key]
        ?: persistedByKey[key]?.withFreshDefinition(liveDefinitions.byKey(key))
        ?: buildLoadingGroupIfPolicyAllows(key, liveDefinitions.byKey(key))
}
```

The forbidden pattern is removed:

```kotlin
// REMOVED:
val syntheticGroups = persistedGroups + liveGroups.filterNot { dup }
val defaultOrderKeys = syntheticGroups.map { it.orderKey }
```

## Stores and persistence

`HomeRailOrderStore` (new):

- Backed by `LayoutPreferenceDataStore` (per-profile, same `profileFlow` pattern).
- Persists `HomeRailOrderState` as JSON (Gson, matching existing patterns). Existing `homeCatalogOrderKeys` and `disabledHomeCatalogKeys` keys are retained for the migration window and seeded into `HomeRailOrderState` on first read.
- Public surface:
  - `state: StateFlow<HomeRailOrderState>`
  - `effectiveOrder(liveDefinitions: Flow<List<HomeRailDefinition>>): StateFlow<EffectiveHomeRailOrder>`
  - `suspend fun updateOrder(orderedKeys: List<HomeRailKey>, source: RailOrderMutationSource)`
  - `suspend fun setEnabled(key: HomeRailKey, enabled: Boolean, source: RailOrderMutationSource)`
  - `suspend fun reorderProviderKeys(family: RailFamily, providerOrder: List<HomeRailKey>, source: RailOrderMutationSource)`

**Reactive composition.** `effectiveOrder` SHALL recompute when *either* `state` or `liveDefinitions` emits, not only on `state` changes. The implementation uses `combine`:

```kotlin
fun effectiveOrder(
    liveDefinitions: Flow<List<HomeRailDefinition>>,
): StateFlow<EffectiveHomeRailOrder> = combine(state, liveDefinitions) { s, defs ->
    reconciler.reconcile(s.orderedKeys, s.disabledKeys, defs)
}.stateIn(scope, SharingStarted.Eagerly, EffectiveHomeRailOrder.Empty)
```

The pipeline collects this flow rather than reading `state.value` snapshots. A provider enabling/disabling a rail (which surfaces as a change in `liveDefinitions[i].enabled` rather than `HomeRailOrderState`) MUST therefore propagate to Modern Home immediately. A test asserts this explicitly: `effective_order_recomputes_on_live_definitions_change_alone`.

**Preserving unknown saved keys on `updateOrder`.** When the catalog order screen writes a new ordered list, the input contains only currently-visible keys. The store SHALL preserve any keys in `HomeRailOrderState.orderedKeys` that are absent from the input *and* absent from the current live definitions ("unknown saved keys" — typically temporarily-unavailable addons or a provider whose catalog plan has not yet loaded). The implementation appends those keys to the end of the new order before persisting:

```kotlin
suspend fun updateOrder(input: List<HomeRailKey>, source: RailOrderMutationSource) {
    val current = state.value.orderedKeys
    val live = lastKnownLiveDefinitionKeys()    // best-effort snapshot for unknown detection
    val unknownInCurrent = current.filter { it !in live && it !in input }
    val merged = input + unknownInCurrent
    persist(state.value.copy(
        orderedKeys = merged,
        version = state.value.version + 1,
        updatedAtMs = clock.now().toEpochMilli(),
        lastMutationSource = source,
    ))
}
```

This rule does NOT apply to `setEnabled` or `reorderProviderKeys`, which already operate on specific keys. It also does not protect against an explicit `DEBUG_RESET` call which is permitted to discard everything.

A test asserts: `update_order_preserves_keys_currently_unknown_to_live_definitions`. Without this rule, an addon that is briefly absent at the moment the user reorders rails would lose its saved position permanently.

`reorderProviderKeys` semantics: a precise splice algorithm that replaces the family's slice of `orderedKeys` while preserving relative positions of all non-family keys. The naive "remove all family keys then insert at first family slot" rule fails for several real cases (new family keys, missing family keys, interleaved family, no existing family slot), so the algorithm is specified explicitly below.

**Inputs.** `orderedKeys` (current global), `family`, `providerOrder` (incoming list, the family's keys in desired order; possibly contains keys not yet in `orderedKeys` and possibly missing keys currently in `orderedKeys`), `liveDefinitions` (used to determine current family-rank for the no-existing-slot case).

**Algorithm.**

```text
1. existingFamilyPositions := positions in orderedKeys whose key has family == family,
   in their existing index order.
2. existingFamilyKeys := keys at those positions, in existing order.
3. desiredFamilyKeys := concat(
       providerOrder,                                     // user-requested order, including new keys
       existingFamilyKeys not in providerOrder            // keys not mentioned by the caller, kept in
                                                          // their existing relative order (stable tail)
   )
   // De-duplicate while preserving first-occurrence order.
4. If existingFamilyPositions is non-empty:
       a. Walk orderedKeys in order; emit the i-th non-family key unchanged into result.
       b. At each existingFamilyPosition[i], emit desiredFamilyKeys[i] if present, else nothing.
       c. After the walk, if desiredFamilyKeys has more entries than existingFamilyPositions,
          insert the remaining entries at the result index immediately after the last
          existingFamilyPosition (i.e., contiguous with the family's tail position).
   Else (existingFamilyPositions is empty — family has no current slot in orderedKeys):
       a. Compute insertionIndex = the index in orderedKeys where the family would land
          if the list were sorted by (familyRank, current position). Concretely: the index
          of the first key whose live-definition familyRank > family's familyRank.
          If no such key, insertionIndex = orderedKeys.size.
       b. Insert desiredFamilyKeys as a contiguous block at insertionIndex.
5. Return result. Non-family keys retain their relative order in all branches.
```

**Worked examples.**

```text
# Simple replace (matches earlier worked example)
current:        [trakt:popular, tmdb:popular, simkl:trending, tmdb:top-rated]
providerOrder:  [tmdb:top-rated, tmdb:popular]                  (TMDB)
result:         [trakt:popular, tmdb:top-rated, simkl:trending, tmdb:popular]

# providerOrder introduces a new family key
current:        [trakt:A, tmdb:A, simkl:B]
providerOrder:  [tmdb:C, tmdb:B, tmdb:A]                        (TMDB)
desiredFamilyKeys: [tmdb:C, tmdb:B, tmdb:A]   // existing tmdb:A appears in providerOrder
existingFamilyPositions: [1]
result:         [trakt:A, tmdb:C, simkl:B, tmdb:B, tmdb:A]
                                  ^---^---- new keys appended after the family's last existing slot

# providerOrder omits an existing family key
current:        [tmdb:A, simkl:X, tmdb:B, trakt:Y, tmdb:C]
providerOrder:  [tmdb:C, tmdb:A]                                (TMDB)
existingFamilyKeys: [tmdb:A, tmdb:B, tmdb:C]
desiredFamilyKeys: [tmdb:C, tmdb:A, tmdb:B]   // tmdb:B kept (stable tail)
existingFamilyPositions: [0, 2, 4]
result:         [tmdb:C, simkl:X, tmdb:A, trakt:Y, tmdb:B]

# Family has no current slot
current:        [trakt:A, simkl:B]
providerOrder:  [kitsu:trending, kitsu:popular]                 (KITSU; familyRank 4)
existingFamilyPositions: []
insertionIndex: 2 (KITSU rank 4 > SIMKL rank 1 and TRAKT rank 0; nothing has rank > 4)
result:         [trakt:A, simkl:B, kitsu:trending, kitsu:popular]
```

The "stable tail" rule (existing family keys not mentioned by the caller are kept in their existing relative order, after `providerOrder`) means a partial provider reorder cannot accidentally drop family keys, even when the caller has stale knowledge of which keys exist.

`SyntheticHomeCatalogStore` (modified):

- Reader exposes `syntheticGroupByKey: Map<HomeRailKey, SyntheticGroup>` to the pipeline.
- The store may keep its current ordered shape on disk for backward compatibility; the order is never read by the pipeline after the first-launch migration shim runs.
- Writes still happen as today (groups arrive from refresh).

## Migration

One-shot, per-profile, idempotent. Runs the first time `HomeRailOrderStore.state` is read after the upgrade.

```text
if HomeRailOrderState.orderedKeys is non-empty:
    no-op (already migrated)

else if legacy homeCatalogOrderKeys is non-empty:
    seed orderedKeys from legacy homeCatalogOrderKeys
    set lastMutationSource = MIGRATION

else if liveDefinitions are available:
    seed orderedKeys from liveDefinitions sorted by (defaultSortKey.familyRank, defaultSortKey.intraFamilyRank)
    set lastMutationSource = MIGRATION

else:
    // liveDefinitions not yet available on first read — temporary fallback so the user does
    // not see a blank home, immediately superseded once liveDefinitions arrive.
    seed orderedKeys from current persisted synthetic group order in SyntheticHomeCatalogStore
    set lastMutationSource = MIGRATION_SYNTHETIC_FALLBACK

disabledKeys is seeded from legacy disabledHomeCatalogKeys when present (independent of the orderedKeys path).
```

When `lastMutationSource = MIGRATION_SYNTHETIC_FALLBACK`, `HomeRailOrderStore` SHALL re-run the migration step the first time `liveDefinitions` becomes non-empty in the same process: it overwrites `orderedKeys` from the live default order and sets `lastMutationSource = MIGRATION`. This is the only state in which migration is allowed to overwrite previously-written `orderedKeys`, and it does so only when the previously-written value is itself a `MIGRATION_SYNTHETIC_FALLBACK` value.

**Why this priority instead of synthetic-first.** A migration that seeded directly from persisted synthetic order would faithfully preserve the user's current visible order, but would also faithfully preserve the exact stale-synthetic state that caused the RCA bug — turning the bug into the new authoritative state on first launch. Seeding from current live definitions (current provider `catalogOrder`, family rank) honors the user's most recent provider settings, which is the correct authority. The synthetic-fallback branch exists only to avoid a blank home in the rare case where live definitions are not yet computed when the store is first read, and it is replaced by the live-default order as soon as live definitions arrive.

**User-facing consequence.** A user whose visible order today is being driven by stale synthetic order will see the order change on first post-upgrade launch — to the order their current provider settings actually request. This is intentional: the change is the bugfix.

After migration, `HomeRailOrderState` is the authoritative store. Legacy keys remain readable for one release cycle for rollback safety; subsequent writes target the new state only.

## Provider-settings write-through

For each of Trakt, SIMKL, MDBList, TMDB, Kitsu, when the settings screen mutates `catalogOrder`:

1. Write the provider's existing `catalogOrder` preference (unchanged).
2. Compute the keys belonging to that family from current live definitions filtered by the new `catalogOrder`.
3. Call `HomeRailOrderStore.reorderProviderKeys(family, computedKeys, RailOrderMutationSource.PROVIDER_SETTINGS_SCREEN)`.

Enable/disable flips:

1. Write the provider's existing enabled flag (unchanged).
2. Call `HomeRailOrderStore.setEnabled(key, enabled, PROVIDER_SETTINGS_SCREEN)` for each affected key (or rely solely on the provider enabled flag flowing through `liveDefinitions.enabled`; both paths are reconciler inputs).

The catalog order screen continues to write the global order, but routes through `HomeRailOrderStore.updateOrder(..., ANDROID_ORDER_SCREEN)` rather than directly setting `homeCatalogOrderKeys`.

## Account sync extension (Change 2)

Sync apply paths must distinguish "field absent in this payload" from "field present and intentionally empty". An empty default like `emptyList()` collapses these two cases, which would let a partial sync silently disable rails or overwrite an existing order with empty. **All new and modified sync fields involved in catalog ordering use nullable types**, with `null` meaning "not present, do not change" and a non-null value (including an empty list) meaning "present, apply as-is".

```kotlin
// Existing sections in CatalogSyncSettings become nullable so the same presence
// semantics apply to all provider sections, not only the new ones. New sections
// are added with the same nullable shape.

@Serializable data class CatalogSyncSettings(
    val home: HomeCatalogSyncSettings? = null,
    val trakt: TraktCatalogSyncSettings? = null,
    val simkl: SimklCatalogSyncSettings? = null,
    val mdblist: MDBListCatalogSyncSettings? = null,
    val tmdb: TmdbCatalogSyncSettings? = null,     // NEW
    val kitsu: KitsuCatalogSyncSettings? = null,   // NEW
)

@Serializable data class TmdbCatalogSyncSettings(
    val catalogEnabledSet: List<String>? = null,
    val catalogOrder: List<String>? = null,
)

@Serializable data class KitsuCatalogSyncSettings(
    val catalogEnabledSet: List<String>? = null,
    val catalogOrder: List<String>? = null,
)
```

The change to nullability for the existing Home/Trakt/SIMKL/MDBList sections is a coordinated patch that ships in the same commit as the TMDB/Kitsu additions; otherwise the partial-sync invariant is unenforceable. Inside the apply paths, every nullable field SHALL be guarded:

```text
if section is null:    do not modify the corresponding target state
if section is non-null:
    for each inner nullable field:
        if null: do not modify
        if non-null (including empty): apply
```

Field naming follows the existing `SimklCatalogSyncSettings` / `MDBListCatalogSyncSettings` convention. Backward compatibility on the wire is preserved because Kotlinx Serialization writes `null` fields as JSON null (or omits them with the appropriate configuration); older clients that emit empty lists for these fields will continue to be accepted and treated as "present, intentionally empty" — that is the safe interpretation under the new rule because emitting `[]` from an older client is itself an explicit emission.

Apply paths in `AccountConfigSyncContract` and `AccountSettingsSyncService`:

- For each provider section present, write the provider's existing preference store (`TmdbCatalogSettingsDataStore`, `KitsuCatalogSettingsDataStore`) **and** call `HomeRailOrderStore.reorderProviderKeys(family, ..., ACCOUNT_SYNC)`, with `lastMutationSource = ACCOUNT_SYNC`.
- If `catalogs.home.homeCatalogOrderKeys` is present, call `HomeRailOrderStore.updateOrder(keys, ACCOUNT_SYNC)`. Otherwise leave the home order untouched (do not erase it from a partial sync).
- `ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION` bumps from 8 to 9; `AccountConfigSyncPayload.schemaVersion` default bumps to 9 to match. Older versions remain accepted; missing TMDB/Kitsu fields are treated as absent.

## Diagnostics

Always-on:

- `home.rail_order_reconciled` — emits saved global, provider orders, persisted synthetic order (informational), live definition order, effective order, disabled keys, newly discovered keys, ignored sources, mutation reason.

Debug/verbose only:

- `home.rail_order_mutation`
- `home.rail_enabled_changed`
- `home.rail_hidden_due_to_disabled`
- `home.rail_added_from_missing_default`
- `home.persisted_synthetic_used_as_content_only`

## Tests

Ordering (reconciler unit tests):

- `persisted_synthetic_order_does_not_override_live_order`
- `global_home_order_applies_over_persisted_synthetic_order`
- `provider_order_change_updates_effective_home_order_immediately`
- `provider_order_write_through_preserves_cross_provider_positions`
- `disabled_rail_hidden_even_if_persisted_synthetic_exists`
- `enabled_rail_appears_immediately_with_cached_content`
- `missing_saved_keys_are_pruned_when_provider_disabled`
- `unknown_saved_keys_kept_on_persisted_list_but_not_visible`
- `new_live_keys_are_appended_by_family_then_intra_family_rank`
- `late_loaded_rail_inserts_at_effective_position_not_end`

Pipeline (`updateCatalogRowsPipeline` integration tests):

- `pipeline_uses_effective_order_not_synthetic_order`
- `duplicate_live_group_not_dropped_due_to_persisted_group`
- `persisted_synthetic_group_used_only_as_content_fallback`
- `live_definition_with_loading_policy_publishes_loading_placeholder`

Migration:

- `first_launch_with_legacy_global_order_seeds_state_from_legacy`
- `first_launch_with_no_legacy_and_live_definitions_seeds_from_live_default_order`
- `first_launch_with_no_legacy_and_no_live_definitions_temporarily_seeds_from_synthetic_then_overwrites_when_live_arrives`
- `migration_synthetic_fallback_is_overwritten_on_first_live_definitions_emission`
- `migration_synthetic_fallback_is_not_overwritten_after_a_user_mutation`
- `subsequent_launch_does_not_re_run_migration`
- `disabled_legacy_keys_carry_into_disabledKeys`

UI/reactivity:

- `catalog_order_screen_reorder_updates_home_without_restart`
- `catalog_enable_disable_updates_home_without_restart`
- `home_order_flow_emits_on_order_mutation`
- `home_order_change_preserves_row_content_and_focus_where_possible`

Sync (Change 2):

- `account_sync_home_order_updates_modern_home_immediately`
- `account_sync_provider_order_write_through_updates_home_order`
- `account_sync_tmdb_catalog_settings_applied`
- `account_sync_kitsu_catalog_settings_applied`
- `partial_sync_without_home_order_does_not_revert_to_stale_synthetic_order`
- `account_sync_contract_version_9_round_trip`

## Risks and mitigations

- **Risk:** users whose visible order is currently driven by stale synthetic order see their home reshuffle on first post-upgrade boot.
  - **Mitigation:** this is intentional — the reshuffle is to the order their current provider settings actually request, which is the bugfix. The migration prefers the legacy global order when present (explicit user intent) and only falls back to synthetic order temporarily when live definitions are not yet available on first read; in that case it is overwritten by the live default order as soon as live definitions arrive (`MIGRATION_SYNTHETIC_FALLBACK` → `MIGRATION` transition).
- **Risk:** legacy `homeCatalogOrderKeys` and `disabledHomeCatalogKeys` keep being written by an old code path during the migration window.
  - **Mitigation:** all writes route through `HomeRailOrderStore`; legacy DataStore keys become read-only during the migration window and are removed in the next release.
- **Risk:** `reorderProviderKeys` produces an unexpected splice when a family's keys are interleaved with non-family keys in unusual orders.
  - **Mitigation:** unit-tested splice that preserves all non-family relative positions; worked example covered by `provider_order_write_through_preserves_cross_provider_positions`.
- **Risk:** contract-version 9 rollout against older clients.
  - **Mitigation:** server retains acceptance of prior versions; new TMDB/Kitsu fields are additive with empty defaults; older clients ignore them.

## Out-of-scope follow-ups

- Long-term collapse of provider-specific `catalogOrder` into a single global order (Option A in the brainstorm). Foundation enables it; a separate change would deprecate provider-specific order as a write surface.
- Diagnostics surface in the in-app debug screen (current change emits trace events; UI surfacing is separate).
- Web UI for editing TMDB/Kitsu catalog settings is out of scope here; this change makes the Android side ready to receive them once web exposes them.
