## ADDED Requirements

### Requirement: Account-Owned Rail Key Scoping Invariant

For any rail whose content depends on the active profile or a specific provider account/credential ("account-owned rails"), the system SHALL enforce that the key cannot collide across profiles or accounts. At least one of the following SHALL hold for every account-owned rail key: (a) the `HomeRailKey` string includes the account scope (for example `trakt_user_list_{accountHash}_{listIdHash}`); or (b) the store namespace that holds the key (`HomeRailOrderStore`, `SyntheticHomeCatalogStore`) is scoped to the active profile id and the relevant per-family credential hash. It is forbidden for two different profiles or two different provider accounts to share the same account-owned rail key in the same store namespace.

#### Scenario: Account-owned key collision across profiles is impossible
- **GIVEN** profile 1 has a Trakt user list rail with `listIdHash = L1` under credential `C1`
- **AND** profile 2 has a Trakt user list rail with the same `listIdHash = L1` under credential `C2`
- **WHEN** both profiles' rails are stored in `HomeRailOrderState` and `SyntheticHomeCatalogStore`
- **THEN** the two rails do not collide — either because their `HomeRailKey` strings differ (account scope encoded in the key) or because the store namespace differs (profile/credential scoped store) — and switching the active profile does not show profile 1's rails for profile 2

#### Scenario: Re-authentication with a different provider account does not reuse previous account's rails
- **GIVEN** the active profile has Trakt account `C1` with a list rail key `K`
- **WHEN** the user re-authenticates Trakt as a different account `C2`
- **THEN** `K` is not reused for any rail belonging to `C2`
- **AND** Modern Home does not display `C1`'s rails after the re-authentication

### Requirement: Authoritative Effective Rail Order

The system SHALL compute Modern Home rail order from a single authoritative model — `EffectiveHomeRailOrder` — derived from `HomeRailOrderState` (saved global order plus disabled keys) and the live `HomeRailDefinition` list. Persisted synthetic group order from `SyntheticHomeCatalogStore` SHALL NOT contribute to rail ordering.

#### Scenario: Saved global order wins for known enabled keys
- **GIVEN** `HomeRailOrderState.orderedKeys` is `[trakt_popular_movies, tmdb_popular_movies, simkl_tv_trending_today]`
- **AND** all three keys appear in live definitions and are enabled
- **WHEN** Modern Home computes `EffectiveHomeRailOrder`
- **THEN** `visibleKeys` equals `[trakt_popular_movies, tmdb_popular_movies, simkl_tv_trending_today]` in that exact order

#### Scenario: New live keys are appended by family then intra-family rank
- **GIVEN** `HomeRailOrderState.orderedKeys` is `[tmdb_popular_movies]`
- **AND** live definitions also include `simkl_tv_trending_today` (family rank 1, intra rank 0) and `tmdb_top_rated_movies` (family rank 3, intra rank 1)
- **WHEN** Modern Home computes `EffectiveHomeRailOrder`
- **THEN** `visibleKeys` equals `[tmdb_popular_movies, simkl_tv_trending_today, tmdb_top_rated_movies]`
- **AND** `newlyDiscoveredKeys` equals `[simkl_tv_trending_today, tmdb_top_rated_movies]`

#### Scenario: Disabled keys are excluded
- **GIVEN** `HomeRailOrderState.orderedKeys` includes `tmdb_popular_movies`
- **AND** `HomeRailOrderState.disabledKeys` contains `tmdb_popular_movies`
- **WHEN** Modern Home computes `EffectiveHomeRailOrder`
- **THEN** `visibleKeys` does not contain `tmdb_popular_movies`
- **AND** `disabledKeys` contains `tmdb_popular_movies`

#### Scenario: Disabled-by-provider-flag keys are excluded
- **GIVEN** `HomeRailOrderState.orderedKeys` includes `kitsu_trending_anime`
- **AND** the live definition for `kitsu_trending_anime` has `enabled = false`
- **WHEN** Modern Home computes `EffectiveHomeRailOrder`
- **THEN** `visibleKeys` does not contain `kitsu_trending_anime`

#### Scenario: Enable precedence requires both authorities
- **GIVEN** the live definition for `K` has `enabled = true`
- **AND** `HomeRailOrderState.disabledKeys` contains `K`
- **WHEN** Modern Home computes `EffectiveHomeRailOrder`
- **THEN** `visibleKeys` does not contain `K`
- **AND** calling `HomeRailOrderStore.setEnabled(K, true, ...)` removes `K` from `disabledKeys` but does not flip the provider flag

#### Scenario: setEnabled does not override the provider enabled flag
- **GIVEN** the live definition for `K` has `enabled = false`
- **AND** `HomeRailOrderState.disabledKeys` does not contain `K`
- **WHEN** the user calls `HomeRailOrderStore.setEnabled(K, true, ANDROID_ORDER_SCREEN)`
- **THEN** `K` remains hidden because the provider flag still says `enabled = false`
- **AND** the diagnostics event records the visibility outcome rather than treating `setEnabled(true)` as a guarantee of visibility

#### Scenario: Unknown saved keys are kept on the persisted list but not visible
- **GIVEN** `HomeRailOrderState.orderedKeys` includes `cinemeta_movie_top`
- **AND** no live definition exists for that key
- **WHEN** Modern Home computes `EffectiveHomeRailOrder`
- **THEN** `unknownSavedKeys` contains the key
- **AND** `visibleKeys` does not contain the key
- **AND** the persisted `HomeRailOrderState.orderedKeys` is not modified

#### Scenario: Persisted synthetic order does not influence rail order
- **GIVEN** `SyntheticHomeCatalogStore` persists groups in order `[simkl_tv_trending_today, tmdb_popular_movies]`
- **AND** `HomeRailOrderState.orderedKeys` is `[tmdb_popular_movies, simkl_tv_trending_today]`
- **AND** both keys are enabled in live definitions
- **WHEN** Modern Home computes `EffectiveHomeRailOrder`
- **THEN** `visibleKeys` equals `[tmdb_popular_movies, simkl_tv_trending_today]`
- **AND** the diagnostics event `home.rail_order_reconciled` records `ignoredOrderSources` containing `persistedSyntheticOrder`

### Requirement: Reactive Rail Order Mutations

`HomeRailOrderStore` SHALL expose `state` as a `StateFlow<HomeRailOrderState>` collected by Modern Home and SHALL expose `effectiveOrder(liveDefinitions)` as a `StateFlow<EffectiveHomeRailOrder>` that recomputes whenever **either** `state` or `liveDefinitions` emits a new value (using `combine` semantics). Mutations from the catalog order screen, provider settings screens, and account sync, **and** provider-flag changes that surface only through `liveDefinitions[i].enabled`, SHALL emit a new `EffectiveHomeRailOrder` immediately and SHALL NOT require an app restart or a forced network refetch.

#### Scenario: Effective order recomputes on liveDefinitions change alone
- **GIVEN** `HomeRailOrderState` has not changed
- **WHEN** `liveDefinitions` emits a new list in which a previously-`enabled = true` definition becomes `enabled = false`
- **THEN** `effectiveOrder` emits a new `EffectiveHomeRailOrder`
- **AND** the affected key is no longer in `visibleKeys`

#### Scenario: Catalog order screen reorder applies immediately
- **GIVEN** Modern Home is rendered with rails in order `[A, B, C]`
- **WHEN** the user reorders to `[C, A, B]` on the Android catalog order screen
- **THEN** `HomeRailOrderStore.updateOrder([C, A, B], ANDROID_ORDER_SCREEN)` is called
- **AND** Modern Home rerenders rails as `[C, A, B]` without an app restart
- **AND** no catalog refetch is triggered solely by the reorder

#### Scenario: Catalog enable/disable applies immediately
- **GIVEN** Modern Home is rendering rail key `K`
- **WHEN** the user disables `K` from any settings surface
- **THEN** `EffectiveHomeRailOrder.visibleKeys` no longer contains `K`
- **AND** Modern Home removes the row without restart
- **AND** any persisted synthetic content for `K` remains stored but is not displayed

#### Scenario: Re-enable shows cached content immediately when available
- **GIVEN** rail key `K` is currently disabled
- **AND** `SyntheticHomeCatalogStore` has cached content for `K`
- **WHEN** the user re-enables `K`
- **THEN** `EffectiveHomeRailOrder.visibleKeys` contains `K` at its saved or default position
- **AND** Modern Home renders the rail using the cached content as fallback
- **AND** no fetch is required to display the row

### Requirement: updateOrder Preserves Unknown Saved Keys

When a caller invokes `HomeRailOrderStore.updateOrder(input, source)` with a list that omits keys currently in `HomeRailOrderState.orderedKeys` whose live definitions are not currently known, the store SHALL preserve those omitted-and-unknown keys by appending them to the end of the new persisted `orderedKeys`. The store SHALL NOT preserve omitted keys whose live definitions are currently known (those are treated as an explicit removal). `setEnabled` and `reorderProviderKeys` are not subject to this rule because they operate on specific keys; `DEBUG_RESET` paths are exempt by design.

#### Scenario: Reorder while an addon is temporarily unavailable does not lose its saved position
- **GIVEN** `HomeRailOrderState.orderedKeys` is `[A, cinemeta_offline_catalog, B]`
- **AND** live definitions currently contain only `[A, B]` (the addon is offline)
- **WHEN** the catalog order screen calls `updateOrder([B, A], ANDROID_ORDER_SCREEN)`
- **THEN** persisted `HomeRailOrderState.orderedKeys` becomes `[B, A, cinemeta_offline_catalog]`
- **AND** when the addon comes back online and its definition appears in `liveDefinitions`, its rail rejoins Modern Home at the preserved position

#### Scenario: Reorder explicitly removing a known key does remove it
- **GIVEN** `HomeRailOrderState.orderedKeys` is `[A, B, C]`
- **AND** live definitions currently contain `[A, B, C]`
- **WHEN** the catalog order screen calls `updateOrder([A, C], ANDROID_ORDER_SCREEN)`
- **THEN** persisted `HomeRailOrderState.orderedKeys` becomes `[A, C]`
- **AND** `B` is treated as an explicit removal because its live definition is currently known

### Requirement: Provider-Settings Write-Through With Precise Splice

When a provider settings screen (Trakt, SIMKL, MDBList, TMDB, or Kitsu) mutates the provider's `catalogOrder`, the system SHALL call `HomeRailOrderStore.reorderProviderKeys(family, providerOrder, PROVIDER_SETTINGS_SCREEN)`. The splice algorithm SHALL:

1. Identify existing positions in `HomeRailOrderState.orderedKeys` occupied by keys whose live definition has the given `family` (in existing order: `existingFamilyPositions`).
2. Build `desiredFamilyKeys` as `providerOrder` followed by any existing family keys not present in `providerOrder` (preserving their existing relative order — the "stable tail").
3. If `existingFamilyPositions` is non-empty, fill those positions in order with `desiredFamilyKeys[0..n-1]` and append any leftover `desiredFamilyKeys` entries contiguously immediately after the last existing family position; non-family keys keep their absolute positions.
4. If `existingFamilyPositions` is empty, insert `desiredFamilyKeys` as a contiguous block at the index of the first key whose live definition has `familyRank` greater than the family's `familyRank` (or at the end if none).
5. Preserve the relative order of all non-family keys.

#### Scenario: Provider reorder preserves cross-provider positions
- **GIVEN** `HomeRailOrderState.orderedKeys` is `[trakt_popular_movies, tmdb_popular_movies, simkl_tv_trending_today, tmdb_top_rated_movies]`
- **WHEN** the TMDB settings screen sets TMDB `catalogOrder` to `[tmdb_top_rated_movies, tmdb_popular_movies]`
- **THEN** `HomeRailOrderState.orderedKeys` becomes `[trakt_popular_movies, tmdb_top_rated_movies, simkl_tv_trending_today, tmdb_popular_movies]`
- **AND** Modern Home rerenders without restart

#### Scenario: Provider reorder does not move other providers
- **GIVEN** `HomeRailOrderState.orderedKeys` is `[simkl_tv_trending_today, simkl_movie_trending_week, trakt_popular_movies, simkl_anime_trending_week]`
- **WHEN** the SIMKL settings screen sets SIMKL `catalogOrder` to `[simkl_anime_trending_week, simkl_movie_trending_week, simkl_tv_trending_today]`
- **THEN** `HomeRailOrderState.orderedKeys` becomes `[simkl_anime_trending_week, simkl_movie_trending_week, trakt_popular_movies, simkl_tv_trending_today]`
- **AND** the position of `trakt_popular_movies` relative to non-SIMKL keys is preserved

#### Scenario: Provider reorder introduces a new family key
- **GIVEN** `HomeRailOrderState.orderedKeys` is `[trakt_A, tmdb_A, simkl_B]`
- **AND** TMDB live definitions include `tmdb_A`, `tmdb_B`, and `tmdb_C`
- **WHEN** the TMDB settings screen sets TMDB `catalogOrder` to `[tmdb_C, tmdb_B, tmdb_A]`
- **THEN** `HomeRailOrderState.orderedKeys` becomes `[trakt_A, tmdb_C, simkl_B, tmdb_B, tmdb_A]`
- **AND** the new family keys are appended contiguously after the family's last existing position

#### Scenario: Provider reorder omits an existing family key (stable tail)
- **GIVEN** `HomeRailOrderState.orderedKeys` is `[tmdb_A, simkl_X, tmdb_B, trakt_Y, tmdb_C]`
- **WHEN** the TMDB settings screen sets TMDB `catalogOrder` to `[tmdb_C, tmdb_A]`
- **THEN** `HomeRailOrderState.orderedKeys` becomes `[tmdb_C, simkl_X, tmdb_A, trakt_Y, tmdb_B]`
- **AND** `tmdb_B` is preserved in its existing relative position within the family slot sequence
- **AND** the family key not mentioned by the caller is not dropped

#### Scenario: Provider has no current slot — block inserted at family rank
- **GIVEN** `HomeRailOrderState.orderedKeys` is `[trakt_A, simkl_B]`
- **AND** Kitsu has `familyRank` greater than Trakt's and SIMKL's
- **WHEN** the Kitsu settings screen sets Kitsu `catalogOrder` to `[kitsu_trending_anime, kitsu_popular_anime]`
- **THEN** `HomeRailOrderState.orderedKeys` becomes `[trakt_A, simkl_B, kitsu_trending_anime, kitsu_popular_anime]`
- **AND** the Kitsu block is inserted contiguously at the index after the last key with smaller family rank

### Requirement: Pipeline Materializes Content By Effective Order Key

`HomeViewModelCatalogPipeline.updateCatalogRowsPipeline` SHALL materialize Modern Home rows by iterating `EffectiveHomeRailOrder.visibleKeys` in order and selecting content per key with the priority: live group → persisted synthetic group → loading placeholder when the live definition's publish policy permits. The pipeline SHALL NOT concatenate persisted synthetic groups before live groups, SHALL NOT drop live groups when persisted groups have duplicate keys, and SHALL NOT derive a default order from synthetic group iteration order.

#### Scenario: Live group preferred over persisted synthetic content
- **GIVEN** `EffectiveHomeRailOrder.visibleKeys` contains `K` first
- **AND** a live group exists for `K` with content `LiveContent`
- **AND** a persisted synthetic group exists for `K` with older content `OldContent`
- **WHEN** the pipeline materializes rows
- **THEN** the row for `K` uses `LiveContent`
- **AND** the row appears at index 0

#### Scenario: Persisted synthetic content used as fallback
- **GIVEN** `EffectiveHomeRailOrder.visibleKeys` contains `K`
- **AND** no live group exists for `K`
- **AND** a persisted synthetic group exists for `K` with content `CachedContent`
- **WHEN** the pipeline materializes rows
- **THEN** the row for `K` uses `CachedContent`
- **AND** the diagnostics event `home.persisted_synthetic_used_as_content_only` records the key in debug builds

#### Scenario: Loading placeholder when policy permits
- **GIVEN** `EffectiveHomeRailOrder.visibleKeys` contains `K`
- **AND** no live group and no persisted synthetic group exist for `K`
- **AND** the live definition for `K` has `publishPolicy = PUBLISH_ON_FIRST_PAINT`
- **WHEN** the pipeline materializes rows
- **THEN** a loading placeholder row is published for `K`

#### Scenario: Duplicate live group is not dropped
- **GIVEN** a live group exists for `K`
- **AND** a persisted synthetic group also exists for `K`
- **WHEN** the pipeline materializes rows
- **THEN** the live group is the source of content for `K`
- **AND** the live group is not removed from the result on the basis of the persisted duplicate

### Requirement: One-Shot Migration Prefers Live Default Order Over Stale Synthetic

On first launch after the upgrade, the system SHALL seed `HomeRailOrderState.orderedKeys` per profile in this priority:

1. If `HomeRailOrderState.orderedKeys` is non-empty, do nothing.
2. Else if legacy `LayoutPreferenceDataStore.homeCatalogOrderKeys` is non-empty, seed from legacy and set `lastMutationSource = MIGRATION`.
3. Else if live `HomeRailDefinition` list is available, seed from live definitions sorted by `(defaultSortKey.familyRank, defaultSortKey.intraFamilyRank)` and set `lastMutationSource = MIGRATION`.
4. Else (live definitions not yet available on this read), temporarily seed from `SyntheticHomeCatalogStore` iteration order and set `lastMutationSource = MIGRATION_SYNTHETIC_FALLBACK`. The system SHALL overwrite this temporary state with the live default order the first time live definitions become non-empty in the same process, and SHALL set `lastMutationSource = MIGRATION` at that point.

`HomeRailOrderState.disabledKeys` SHALL be seeded from legacy `disabledHomeCatalogKeys` whenever `disabledKeys` is empty and the legacy value is non-empty, independent of which orderedKeys branch ran. The migration SHALL run at most once per profile per process for the orderedKeys branches 2 and 3; the synthetic-fallback overwrite is permitted only when the previously written `lastMutationSource` is `MIGRATION_SYNTHETIC_FALLBACK`.

#### Scenario: First launch with legacy global order seeds state from legacy
- **GIVEN** `HomeRailOrderState.orderedKeys` is empty
- **AND** legacy `homeCatalogOrderKeys` is `[A, B, C]`
- **WHEN** `HomeRailOrderStore` initializes for the active profile
- **THEN** `HomeRailOrderState.orderedKeys` becomes `[A, B, C]`
- **AND** `HomeRailOrderState.lastMutationSource` is `MIGRATION`

#### Scenario: First launch with no legacy and live definitions available seeds from live default order
- **GIVEN** `HomeRailOrderState.orderedKeys` is empty
- **AND** legacy `homeCatalogOrderKeys` is empty
- **AND** live definitions are non-empty and sorted by family-rank then intra-family-rank as `[trakt_popular_movies, simkl_tv_trending_today, tmdb_popular_movies]`
- **WHEN** `HomeRailOrderStore` initializes for the active profile
- **THEN** `HomeRailOrderState.orderedKeys` becomes `[trakt_popular_movies, simkl_tv_trending_today, tmdb_popular_movies]`
- **AND** `HomeRailOrderState.lastMutationSource` is `MIGRATION`
- **AND** persisted synthetic group iteration order is not consulted

#### Scenario: First launch with no legacy and no live definitions temporarily seeds from synthetic
- **GIVEN** `HomeRailOrderState.orderedKeys` is empty
- **AND** legacy `homeCatalogOrderKeys` is empty
- **AND** live definitions are not yet available on this read
- **AND** persisted synthetic group iteration order is `[simkl_tv_trending_today, tmdb_popular_movies]`
- **WHEN** `HomeRailOrderStore` initializes for the active profile
- **THEN** `HomeRailOrderState.orderedKeys` becomes `[simkl_tv_trending_today, tmdb_popular_movies]`
- **AND** `HomeRailOrderState.lastMutationSource` is `MIGRATION_SYNTHETIC_FALLBACK`

#### Scenario: Synthetic-fallback state is overwritten when live definitions arrive
- **GIVEN** `HomeRailOrderState.lastMutationSource` is `MIGRATION_SYNTHETIC_FALLBACK`
- **AND** the previously seeded `orderedKeys` is `[simkl_tv_trending_today, tmdb_popular_movies]`
- **WHEN** live definitions become non-empty for the first time and the live default order is `[trakt_popular_movies, simkl_tv_trending_today, tmdb_popular_movies]`
- **THEN** `HomeRailOrderState.orderedKeys` is overwritten to `[trakt_popular_movies, simkl_tv_trending_today, tmdb_popular_movies]`
- **AND** `HomeRailOrderState.lastMutationSource` becomes `MIGRATION`

#### Scenario: Synthetic-fallback overwrite does not clobber a user mutation
- **GIVEN** `HomeRailOrderState.lastMutationSource` is `MIGRATION_SYNTHETIC_FALLBACK`
- **WHEN** the user reorders rails via the catalog order screen, setting `lastMutationSource = ANDROID_ORDER_SCREEN`
- **AND** live definitions subsequently arrive
- **THEN** `HomeRailOrderState.orderedKeys` is not overwritten
- **AND** `HomeRailOrderState.lastMutationSource` remains `ANDROID_ORDER_SCREEN`

#### Scenario: Disabled legacy keys carry over
- **GIVEN** legacy `disabledHomeCatalogKeys` is `[K]`
- **AND** `HomeRailOrderState.disabledKeys` is empty
- **WHEN** `HomeRailOrderStore` initializes for the active profile
- **THEN** `HomeRailOrderState.disabledKeys` contains `K`

#### Scenario: Migration is idempotent
- **GIVEN** `HomeRailOrderState.orderedKeys` is non-empty
- **AND** `HomeRailOrderState.lastMutationSource` is not `MIGRATION_SYNTHETIC_FALLBACK`
- **WHEN** `HomeRailOrderStore` initializes again
- **THEN** the existing state is not overwritten

### Requirement: SyntheticHomeCatalogStore Is Content-Only

`SyntheticHomeCatalogStore` SHALL expose its content to the Modern Home pipeline as a key-indexed map (`Map<HomeRailKey, SyntheticGroup>`). The pipeline SHALL NOT depend on the store's iteration order. The internal disk shape MAY remain ordered for backward compatibility but SHALL NOT be read by the pipeline as an ordering source.

#### Scenario: Pipeline consumes by-key reader
- **GIVEN** persisted synthetic groups exist for keys `[A, B]` and the iteration order on disk is `[B, A]`
- **WHEN** the pipeline reads persisted synthetic content
- **THEN** it accesses content by key lookup
- **AND** the iteration order on disk does not influence the order of rows on Modern Home

### Requirement: Rail Order Diagnostics

The system SHALL emit a `home.rail_order_reconciled` diagnostics event whenever `EffectiveHomeRailOrder` is computed. The event payload SHALL include the saved global order, per-provider orders observed, persisted synthetic order (informational only), live definition order, the resulting effective order, the disabled key set, the newly discovered keys, the ignored order sources, and the mutation reason.

#### Scenario: Reconciliation event includes all order sources and effective result
- **GIVEN** Modern Home recomposes after a TMDB settings screen reorder
- **WHEN** the reconciler runs
- **THEN** a `home.rail_order_reconciled` event is emitted
- **AND** the payload contains `savedGlobalOrder`, `providerOrders`, `persistedSyntheticOrder`, `liveDefinitionOrder`, `effectiveOrder`, `disabledKeys`, `newlyDiscoveredKeys`, `ignoredOrderSources`, and `reason`

#### Scenario: Debug-only diagnostics events fire on the corresponding mutations
- **GIVEN** the build is a debug build
- **WHEN** the user disables a rail
- **THEN** a `home.rail_enabled_changed` event records the key and `enabled = false`
- **AND** a `home.rail_hidden_due_to_disabled` event records the key
