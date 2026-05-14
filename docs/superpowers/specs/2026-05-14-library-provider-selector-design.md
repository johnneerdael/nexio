# Library Provider Selector Design

Date: 2026-05-14
Owner: nexio
Status: spec - pending implementation plan

## Summary

Replace the current split Library entry controls with one provider-first selector
that keeps Unified Watchlist as the default entry point while exposing provider
specific library views for Trakt, SIMKL, MDBList, and configured debrid
providers.

The Library screen should use the existing Library design language: the same
selector/action rhythm, same grid behavior, and same empty-state style. Unified
rows remain provider-neutral at the UI boundary. Provider membership and source
keys stay in model data for routing and diagnostics, not as card badges.

## Reference Material

- `docs/superpowers/specs/2026-05-13-unified-watchlist-library-design.md`
- `app/src/main/java/com/nexio/tv/ui/screens/library/LibraryScreen.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/library/LibraryViewModel.kt`
- `app/src/main/java/com/nexio/tv/domain/repository/LibraryRepository.kt`
- `app/src/main/java/com/nexio/tv/data/repository/LibraryRepositoryImpl.kt`
- `app/src/main/java/com/nexio/tv/data/repository/SimklLibraryService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/MDBListLibraryService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/MDBListDiscoveryService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt`
- `apiblueprints/simkl.apib`
- `apiblueprints/mdblist.apib`

## Problem

The current Library direction has two top-level buttons, `Unified Watchlist` and
`Provider Library`. That creates a non-standard Library layout and forces users
to choose a mode before choosing the source they actually care about.

The intended design is one extra dropdown before `List`:

```text
Provider | List | Type | Sort
```

Provider decides the data source. List only applies to tracker providers with
real list concepts. Unified and debrid providers set List to `N/A`.

## Goals

1. Keep Unified Watchlist as the default Library entry point.
2. Remove the bespoke Unified Watchlist / Provider Library button split.
3. Add a provider dropdown before the existing list dropdown.
4. Preserve the existing Library visual system and grid behavior.
5. Show Trakt, SIMKL, and MDBList provider options only when authenticated.
6. Show Real-Debrid, Premiumize, TorBox, and EasyDebrid provider options only
   when configured.
7. Support provider-specific list views for Trakt, SIMKL, and MDBList.
8. Support MDBList static list management and SIMKL status-bucket management.
9. Keep debrid provider libraries outside Unified Watchlist.
10. Keep Unified Watchlist cards provider-neutral and hydrated through the same
    path as Modern Home/library rows.

## Non-Goals

- No provider badges on Library cards.
- No separate provider library landing page.
- No Continue Watching, next episode, progress, or playback context in Unified
  Watchlist.
- No SIMKL arbitrary custom list create/rename/delete until the SIMKL API
  exposes stable custom-list write support.
- No debrid provider list management.
- No new metadata or display hydration system.

## User-Facing Behavior

Library opens with `Provider = Unified`.

The selector row becomes:

```text
Provider | List | Type | Sort
```

Available provider options:

- `Unified` is always present.
- `Trakt` appears when Trakt is authenticated.
- `SIMKL` appears when SIMKL is authenticated.
- `MDBList` appears when MDBList is authenticated.
- `Real-Debrid` appears when configured.
- `Premiumize` appears when configured.
- `TorBox` appears when configured.
- `EasyDebrid` appears when configured.

When the selected provider becomes unavailable, the selection falls back to
`Unified`.

The top-right source label mirrors the selected provider. Existing Library card
layout, focus behavior, type filter, sort controls, and empty-state styling
remain the baseline.

## Provider And List Rules

### Unified

`Unified` is the default provider. It shows one canonical row per movie or show
using the Unified Watchlist reducer rules from the earlier design:

- Strong IDs first: TMDb, IMDb, Trakt, SIMKL, TVDB/show IDs where relevant.
- Episodes merge by show identity plus season/episode number, but the UI row is
  show-level.
- Title/year only as a weak fallback, internally marked low confidence.
- Source membership is preserved as model data:
  `presentIn = { Trakt, Simkl, MDBList, Local }`.
- Per-source raw keys are preserved for future mutation routing.
- UI cards remain provider-neutral.

For Unified, `List = N/A` and the list dropdown is disabled/non-actionable.

If no Trakt, SIMKL, or MDBList account is authenticated, the Library view shows
the existing Library empty-state style with copy explaining that Unified
Watchlist requires authenticating Trakt, SIMKL, and/or MDBList.

### Trakt

`Trakt` uses the Trakt provider library.

List dropdown:

- `Watchlist`
- Trakt personal lists

The label should be `Watchlist`, not `Trakt Watchlist`, because provider context
already comes from the Provider selector.

Existing Trakt list management remains in scope.

### SIMKL

`SIMKL` uses SIMKL watchlist/status data.

List dropdown:

- `Plan to Watch`
- `Watching`
- `Completed`
- `On Hold`
- `Dropped`

SIMKL list management means status-bucket membership management:

- Add or move a movie/show to a status with `/sync/add-to-list`.
- Remove a movie/show from SIMKL lists/history with `/sync/history/remove`.
- Use `/sync/all-items/{type}/{status}` for provider list hydration.
- Respect the SIMKL blueprint guidance to check `/sync/activities` first and use
  `date_from` for incremental syncs where possible.

The local SIMKL blueprint marks custom list targets as `IN DEV`; arbitrary SIMKL
custom list create/rename/delete is therefore out of scope for this design.

### MDBList

`MDBList` uses MDBList watchlist and all personal MDBList lists.

List dropdown:

- `Watchlist`
- All authenticated personal MDBList lists from `/lists/user`, not only the
  custom lists selected for catalog discovery.

MDBList static list management is in scope:

- Create static list via `/lists/user/add`.
- Rename and update privacy via `PUT /lists/{listid}`.
- Delete static list via `DELETE /lists/{listid}`.
- Add/remove items via `/lists/{listid}/items/{add|remove}`.

Dynamic and external MDBList lists are readable. Management actions only appear
for mutable static lists owned by the authenticated user.

### Debrid Providers

`Real-Debrid`, `Premiumize`, `TorBox`, and `EasyDebrid` each show that
provider's configured library/cache view.

For debrid providers:

- `List = N/A`.
- Debrid items are not included in Unified Watchlist.
- No list management controls appear.
- The provider view filters to the selected provider instead of showing one
  combined debrid mode.

EasyDebrid must be treated as a first-class provider option when configured.
Current debrid library infrastructure should be audited because it already knows
whether EasyDebrid is connected, but may not yet expose an EasyDebrid library
refresh target or tab equivalent.

## Data Model

Introduce a Library-specific provider selection model instead of overloading
tracking provider state:

```kotlin
enum class LibraryProviderSelection {
    UNIFIED,
    TRAKT,
    SIMKL,
    MDBLIST,
    REAL_DEBRID,
    PREMIUMIZE,
    TORBOX,
    EASY_DEBRID
}
```

The ViewModel derives available provider options from auth/configuration state.
Provider selection is persisted only if existing Library selection persistence
already does that safely; otherwise it can remain session-local. If persisted,
store only the small enum key.

Repository output should become provider scoped:

```kotlin
data class LibraryProviderSnapshot(
    val provider: LibraryProviderSelection,
    val items: List<LibraryEntry>,
    val listTabs: List<LibraryListTab>,
    val selectedListKey: String?,
    val supportsLists: Boolean,
    val supportsListManagement: Boolean,
    val emptyReason: LibraryEmptyReason?
)
```

The exact names may follow local conventions, but the boundary should be
provider-scoped rather than a global `sourceMode`.

Unified Watchlist may project to Library rows at the UI boundary, but it must use
the existing Modern Home/resolved-display hydration path. Do not build an
independent display metadata path for Unified rows.

## OpenSpec Planning

Before implementation, create an OpenSpec change for this capability. The change
should cover the provider selector, provider-scoped list behavior, SIMKL/MDBList
list-management capability boundaries, EasyDebrid provider visibility, and the
Unified authentication empty state.

## UI Architecture

Remove the `LibraryPrimaryTab` style split from the visible UI. The provider
dropdown absorbs that responsibility.

The selector row should use the existing Library controls. There should be no
special Unified layout branch except for the data model differences that require
`List = N/A` and the authentication empty state.

Expected control behavior:

| Provider kind | Provider dropdown | List dropdown | Type | Sort |
| --- | --- | --- | --- | --- |
| Unified | Enabled | `N/A` disabled | Existing behavior | Existing behavior |
| Tracker | Enabled | Provider lists/statuses | Existing behavior | Existing behavior |
| Debrid | Enabled | `N/A` disabled | Existing behavior where meaningful | Existing behavior |

## List Management

List management should be provider-aware.

Trakt continues to use existing Trakt list mutation routes.

SIMKL management should expose actions that map to stable status transitions.
Controls should avoid implying arbitrary list CRUD. For example, moving an item
to `Watching` or removing it from SIMKL is valid; creating a new SIMKL list is
not.

MDBList management should expose static list CRUD and static item add/remove
where the selected list is mutable. Dynamic/external lists remain read-only in
the UI.

Unified management is out of scope for this pass. Removing or adding an item
from Unified would require a source-routing decision and should be designed when
mutation routing is added.

Debrid management is out of scope for this pass.

## Rate Limits And Sync Behavior

Use the existing provider rate-limit infrastructure where available. The
CrossWatch-derived defaults remain a useful baseline:

- SIMKL: `10 GET/sec`, `1 write/sec`
- MDBList: `10 GET/sec`, `1 write/sec`
- Trakt: `3.33 GET/sec`, `1 write/sec`

SIMKL provider list refresh should follow the local blueprint guidance:

- Check activity timestamps first.
- Use `date_from` for incremental `/sync/all-items` reads.
- Avoid full watchlist reads unless needed for first sync or removal
  reconciliation.

MDBList personal list reads should avoid fetching every list's full items
eagerly on Library entry. Fetch the selected list first, and refresh other lists
only when selected or when an existing cache policy already makes that safe.

## Persistence And Memory

No provider library blobs should be added to SharedPreferences or DataStore.
Only small scalar selection state can live there.

If provider library snapshots need disk persistence and may exceed 50 KB, use a
file-backed streaming JSON store with atomic rename, following the project
persistence rule.

Avoid putting large provider item lists into broad Compose-observed state data
classes. Expose hot lists through dedicated `StateFlow`s and collect them at the
UI boundary.

Any suspend iteration over provider item lists must use indexed loops if the
loop body may suspend.

## Testing

Unit coverage:

- Provider availability derives from tracker auth and debrid configuration.
- Selection falls back to `Unified` when the selected provider becomes
  unavailable.
- Unified sets `List = N/A`.
- Debrid providers set `List = N/A`.
- Trakt selected provider shows `Watchlist` and personal lists.
- SIMKL selected provider shows the five status buckets.
- MDBList selected provider shows `Watchlist` and all personal lists.
- MDBList dynamic/external lists are read-only while static owned lists are
  manageable.
- SIMKL management maps to status transitions, not custom list CRUD.
- EasyDebrid appears as a provider option when configured.

UI/smoke coverage:

- Library opens with the provider selector visible before the list selector.
- No `Unified Watchlist` / `Provider Library` button split remains.
- Unified with no tracker auth shows the authentication empty state.
- Provider-specific Library views reuse existing grid/card layout.
- Unified cards do not show provider badges.

Provider integration coverage:

- SIMKL status hydration uses the status keys expected by the API blueprint.
- SIMKL writes use `/sync/add-to-list` for moves/adds and
  `/sync/history/remove` for removals.
- MDBList reads all personal lists from `/lists/user`.
- MDBList static list CRUD endpoints are routed only for mutable static lists.
- Debrid provider selection filters to the chosen provider.

Smoke tests that touch profile-owned Library data must select a profile before
checking Library behavior.

## Acceptance Criteria

1. The Library screen starts on `Provider = Unified`.
2. The old two-button primary tab UI is gone.
3. The selector row is provider-first: `Provider | List | Type | Sort`.
4. Unified and debrid providers show `List = N/A`.
5. Trakt, SIMKL, and MDBList show provider-specific list choices when
   authenticated.
6. Real-Debrid, Premiumize, TorBox, and EasyDebrid show as providers when
   configured.
7. Unified rows remain canonical, show-level for series, provider-neutral, and
   hydrated through the existing display path.
8. SIMKL status-bucket management and MDBList static list management are
   designed into the provider boundary.
9. The implementation does not add large provider item lists to broad observed
   UI state or large JSON blobs to SharedPreferences/DataStore.
