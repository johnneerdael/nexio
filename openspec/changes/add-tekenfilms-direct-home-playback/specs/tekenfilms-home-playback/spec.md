## ADDED Requirements

### Requirement: Tekenfilms Home rail is an exact add-on exception

Nexio SHALL apply Tekenfilms-specific Home behavior only when the catalog item originates from the
installed add-on whose normalized base URL is `https://tekenfilms.nexioapp.org`, manifest id is
`org.nexio.tekenfilms`, catalog id is `tekenfilms_nl`, type is `movie`, and item id starts with
`tekenfilms:`.

#### Scenario: Exact Tekenfilms add-on matches

- **GIVEN** an installed add-on has normalized base URL `https://tekenfilms.nexioapp.org`
- **AND** its manifest id is `org.nexio.tekenfilms`
- **AND** a Home catalog row uses catalog id `tekenfilms_nl` and type `movie`
- **AND** a row item id starts with `tekenfilms:`
- **WHEN** Home evaluates Tekenfilms-specific behavior
- **THEN** the item is eligible for the Tekenfilms Home exception

#### Scenario: Other add-ons do not match

- **GIVEN** an installed add-on differs by base URL, manifest id, catalog id, type, or item id
  prefix
- **WHEN** Home evaluates Tekenfilms-specific behavior
- **THEN** the item is not eligible for the Tekenfilms Home exception
- **AND** the item keeps the generic add-on Home behavior

### Requirement: Tekenfilms Modern Home rail displays every catalog item

The Modern Home Tekenfilms catalog rail SHALL expose every item returned by the add-on catalog
response to the horizontal rail and SHALL NOT apply the generic large-row display truncation to
that rail.

#### Scenario: Tekenfilms row exceeds generic display cap

- **GIVEN** the Tekenfilms catalog response contains more than the generic Modern Home display cap
- **WHEN** Modern Home builds display rows
- **THEN** the Tekenfilms row contains every returned catalog item
- **AND** no generic "take 25" truncation is applied to that row

#### Scenario: Non-Tekenfilms add-on row exceeds generic display cap

- **GIVEN** a non-Tekenfilms add-on catalog response contains more than the generic Modern Home
  display cap
- **WHEN** Modern Home builds display rows
- **THEN** the non-Tekenfilms row keeps the existing generic truncation behavior

### Requirement: Tekenfilms Home items remain first-paint only

Tekenfilms Home catalog items SHALL render from add-on first-paint catalog metadata and SHALL NOT
enter Home/detail metadata hydration or resolved-overlay refresh pipelines.

#### Scenario: Tekenfilms item is scheduled for Home hydration

- **GIVEN** a Tekenfilms catalog item is present in Modern Home
- **WHEN** Home evaluates metadata hydration candidates
- **THEN** the Tekenfilms item is excluded from metadata hydration
- **AND** no detail metadata request is made for that item

#### Scenario: Non-Tekenfilms item is scheduled for Home hydration

- **GIVEN** a non-Tekenfilms add-on catalog item is present in Modern Home
- **WHEN** Home evaluates metadata hydration candidates
- **THEN** the item keeps the existing metadata hydration behavior

### Requirement: Tekenfilms Home clicks launch scoped direct playback

Clicking a Tekenfilms item in Modern Home SHALL bypass detail navigation, fetch streams only from
the Tekenfilms add-on for the clicked item id, and launch playback directly with the add-on
provided stream URL.

#### Scenario: Tekenfilms item click returns a stream URL

- **GIVEN** a Tekenfilms Modern Home item with id `tekenfilms:101-echte-dalmatiers-1996`
- **AND** the Tekenfilms add-on stream endpoint returns a player-ready URL
- **WHEN** the user clicks the item
- **THEN** Nexio fetches streams only from `https://tekenfilms.nexioapp.org`
- **AND** Nexio launches `Player` directly with the returned URL
- **AND** Nexio does not navigate to `Detail`
- **AND** Nexio does not search streams across all installed add-ons

#### Scenario: Tekenfilms stream lookup fails

- **GIVEN** a Tekenfilms Modern Home item is clicked
- **AND** the Tekenfilms stream endpoint returns no player-ready stream
- **WHEN** direct playback is attempted
- **THEN** Nexio stays out of detail hydration
- **AND** Nexio reports a recoverable playback-launch failure through the existing Home error
  surface or log path

#### Scenario: Non-Tekenfilms item click

- **GIVEN** a non-Tekenfilms Modern Home catalog item
- **WHEN** the user clicks the item
- **THEN** Nexio uses the existing detail navigation behavior
- **AND** Nexio does not use the Tekenfilms direct playback path
