## 1. Provider Model And Repository Contract

- [ ] Add Library provider selection/option/snapshot models.
- [ ] Expose available provider options from LibraryRepository.
- [ ] Expose provider-scoped Library snapshots.

## 2. Provider Services

- [ ] Extend MDBListLibraryService with `/lists/user`, list item reads, and static list CRUD.
- [ ] Ensure SIMKL emits fixed status buckets as provider lists.
- [ ] Add EasyDebrid provider visibility and provider-specific debrid filtering.

## 3. Library ViewModel

- [ ] Replace primary-tab state with selected provider state.
- [ ] Derive selected list/type/sort from the provider snapshot.
- [ ] Route refresh and list-management actions by selected provider.

## 4. Library UI

- [ ] Remove Unified Watchlist / Provider Library button row.
- [ ] Add Provider dropdown before List.
- [ ] Render `N/A` list control for Unified and debrid providers.
- [ ] Keep Library grid, actions, and empty states in the existing design language.

## 5. Verification

- [ ] Add focused unit tests for provider options, list behavior, management capability, and UI contract.
- [ ] Run OpenSpec validation.
- [ ] Run focused Gradle tests.
- [ ] Smoke test Library after selecting a profile.
