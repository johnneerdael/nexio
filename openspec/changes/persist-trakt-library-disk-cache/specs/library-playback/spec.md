## ADDED Requirements

### Requirement: Trakt library restores from persisted snapshot state

The Library SHALL restore Trakt watchlist and Trakt personal-list content from a persisted
disk-backed snapshot and SHALL NOT depend on a live Trakt fetch to render the initial view when a
cached snapshot already exists.

#### Scenario: Warm-cache Trakt library opens from disk-backed snapshot

- **GIVEN** Trakt is authenticated
- **AND** a persisted Trakt library snapshot exists on disk
- **WHEN** the Library screen subscribes to Trakt list tabs, items, or membership state
- **THEN** the Library renders from the restored snapshot immediately
- **AND** opening the Library does not require a blocking live Trakt fetch

#### Scenario: First authenticated Trakt library sync blocks until cache exists

- **GIVEN** Trakt is authenticated
- **AND** no persisted Trakt library snapshot exists yet
- **WHEN** the user opens the Library screen
- **THEN** the Library may show the blocking full-screen loading state while the first Trakt sync
  runs
- **AND** once that initial sync succeeds and writes the snapshot, later Library opens restore from
  the persisted snapshot instead of re-entering the blocking loading state

### Requirement: Trakt library refresh publishes through cached snapshot state

The Library SHALL keep the last good Trakt cached snapshot visible during warm-cache refresh and
optimistic list mutations, and SHALL update the persisted snapshot together with the visible Trakt
Library state.

#### Scenario: Warm-cache refresh keeps cached Trakt library visible

- **GIVEN** a persisted Trakt library snapshot already exists
- **WHEN** a Trakt Library refresh runs
- **THEN** the Library keeps rendering the cached Trakt snapshot while refresh is in progress
- **AND** the refreshed Trakt snapshot replaces the visible state only after the renewed snapshot is
  ready and persisted

#### Scenario: Warm-cache refresh failure preserves last good Trakt snapshot

- **GIVEN** a persisted Trakt library snapshot already exists
- **WHEN** a Trakt Library refresh fails
- **THEN** the Library continues rendering the last good cached Trakt snapshot
- **AND** it does not regress to an empty or blocking-loading state

#### Scenario: Optimistic Trakt list mutation rolls back persisted state on failure

- **GIVEN** a persisted Trakt library snapshot already exists
- **WHEN** the user changes Trakt watchlist or personal-list membership
- **THEN** the visible Trakt Library state and persisted Trakt snapshot update optimistically
- **AND** if the network mutation fails, both the visible state and the persisted snapshot roll back
  to the previous confirmed Trakt snapshot

### Requirement: Debrid readable rows use compact single-title presentation

Readable Library rows for debrid service tabs SHALL show one compact filename/title line and SHALL
drop the duplicated secondary title and path/detail lines.

#### Scenario: Debrid row renders compact single title

- **GIVEN** the selected Library tab uses the readable debrid list layout
- **WHEN** a Real-Debrid, Premiumize, or TorBox item is rendered
- **THEN** the row shows only the filename/title text as its visible content
- **AND** it uses the smaller readable-row text treatment instead of the oversized primary title
  treatment
- **AND** the row height is reduced relative to the prior three-line presentation
