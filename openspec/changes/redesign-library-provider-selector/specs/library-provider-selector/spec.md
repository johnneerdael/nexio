# Library Provider Selector Specification

## ADDED Requirements

### Requirement: Provider-First Library Selector

Library SHALL expose one provider selector before the list selector and SHALL NOT expose separate Unified Watchlist and Provider Library primary buttons.

#### Scenario: Library opens
- **WHEN** the Library screen is opened
- **THEN** the selected provider is `Unified`
- **AND** the selector order is `Provider`, `List`, `Type`, `Sort`

#### Scenario: old primary buttons are absent
- **WHEN** the Library controls are rendered
- **THEN** no `Unified Watchlist` / `Provider Library` button row is present

### Requirement: Provider Availability

Library SHALL always show `Unified`, show tracker providers only when authenticated, and show debrid providers only when configured.

#### Scenario: all providers configured
- **GIVEN** Trakt, SIMKL, MDBList, Real-Debrid, Premiumize, TorBox, and EasyDebrid are available
- **WHEN** provider options are derived
- **THEN** the options are `Unified`, `Trakt`, `SIMKL`, `MDBList`, `Real-Debrid`, `Premiumize`, `TorBox`, and `EasyDebrid`

#### Scenario: selected provider disappears
- **GIVEN** `MDBList` is selected
- **WHEN** MDBList authentication is removed
- **THEN** the selected provider falls back to `Unified`

### Requirement: Provider List Behavior

Library SHALL show provider-specific lists for tracker providers and `N/A` for Unified and debrid providers.

#### Scenario: Unified selected
- **WHEN** `Unified` is selected
- **THEN** the list selector displays `N/A`
- **AND** it is non-actionable

#### Scenario: SIMKL selected
- **WHEN** `SIMKL` is selected
- **THEN** list options include `Plan to Watch`, `Watching`, `Completed`, `On Hold`, and `Dropped`

#### Scenario: MDBList selected
- **WHEN** `MDBList` is selected
- **THEN** list options include `Watchlist`
- **AND** all personal MDBList lists returned by `/lists/user`

#### Scenario: debrid selected
- **WHEN** `TorBox` is selected
- **THEN** the list selector displays `N/A`
- **AND** only TorBox library items are shown

### Requirement: List Management Capabilities

Library SHALL expose list management only where provider APIs support it.

#### Scenario: MDBList static list
- **GIVEN** an owned static MDBList list is selected
- **WHEN** list management is opened
- **THEN** create, rename/privacy update, delete, and item add/remove actions are available

#### Scenario: MDBList dynamic list
- **GIVEN** a dynamic or external MDBList list is selected
- **WHEN** list management is opened
- **THEN** mutation controls are not available for that list

#### Scenario: SIMKL status buckets
- **WHEN** SIMKL list management is used
- **THEN** item moves use fixed status buckets
- **AND** arbitrary custom SIMKL list CRUD is not exposed

### Requirement: Unified Watchlist Presentation

Unified Watchlist SHALL keep provider-neutral cards and existing resolved-display hydration.

#### Scenario: Unified row present in multiple providers
- **GIVEN** the same movie exists in multiple tracker sources
- **WHEN** Unified renders the row
- **THEN** one card is shown
- **AND** provider membership is not rendered as a card badge
