# Redesign Library Provider Selector

## Why

Library currently exposes a bespoke two-button split between Unified Watchlist and Provider Library. That does not match the Library design language and makes provider/list selection harder than necessary.

## What Changes

- Replace the primary-tab split with a provider dropdown before the list dropdown.
- Keep Unified Watchlist as the default provider.
- Show authenticated tracker providers and configured debrid providers as provider options.
- Show provider-specific list choices for Trakt, SIMKL, and MDBList.
- Show `N/A` for list selection when Unified or a debrid provider is selected.
- Preserve Unified Watchlist rows as provider-neutral rows hydrated through the existing resolved-display path.
- Add MDBList personal list and static list-management support.
- Treat SIMKL list management as fixed status-bucket membership management.

## Impact

- Affects Library domain models, repository contract, repository implementation, ViewModel, and Compose screen.
- Extends MDBList API/service coverage.
- Adds provider-scoped tests for tracker/debrid list behavior.
- Does not add provider badges, Continue Watching context, or arbitrary SIMKL custom-list CRUD.
