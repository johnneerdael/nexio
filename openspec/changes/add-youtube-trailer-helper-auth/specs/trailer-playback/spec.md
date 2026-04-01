## ADDED Requirements

### Requirement: Trailer availability must be internal-playback-only
Trailer affordances MUST only be shown when Nexio can produce an internally playable trailer result.

#### Scenario: Signed-out public trailer is internally playable
- **GIVEN** the user is not signed into `YouTube Trailer Login`
- **AND** the current internal trailer resolver returns a playable internal trailer
- **WHEN** Nexio evaluates trailer availability for detail or home surfaces
- **THEN** Nexio shows the relevant trailer affordance
- **AND** trailer playback stays in-app

#### Scenario: Signed-out trailer only resolves externally
- **GIVEN** the user is not signed into `YouTube Trailer Login`
- **AND** the current internal trailer resolver cannot produce a playable internal trailer
- **WHEN** the only remaining outcome would be an external trailer URL
- **THEN** Nexio does not show any trailer affordance for that item

#### Scenario: Signed-in helper trailer is internally playable
- **GIVEN** the user is signed into `YouTube Trailer Login`
- **AND** the bundled helper resolves direct playable trailer media URLs for the YouTube-backed trailer
- **WHEN** Nexio evaluates trailer availability for detail or home surfaces
- **THEN** Nexio shows the relevant trailer affordance
- **AND** playback stays in-app using the helper-produced media URLs

#### Scenario: Signed-in helper fails to resolve playback
- **GIVEN** the user is signed into `YouTube Trailer Login`
- **AND** the trailer source is YouTube-backed
- **AND** the bundled helper is unavailable, times out, or returns no playable trailer
- **WHEN** Nexio evaluates trailer availability
- **THEN** Nexio does not show any trailer affordance for that item

### Requirement: Signed-out users must remain on the current internal resolver path
Bundled helper resolution MUST NOT run unless the user has an active YouTube trailer login session.

#### Scenario: Signed-out availability check
- **GIVEN** the user has no active `YouTube Trailer Login` session
- **WHEN** Nexio resolves trailer availability
- **THEN** Nexio uses only the current internal trailer resolver path
- **AND** Nexio does not invoke the bundled helper

### Requirement: Signed-in users must use a device-code YouTube auth session
Nexio MUST provide a same-device device-code auth flow which yields refresh/access token state suitable for bundled helper execution.

#### Scenario: Integration sign-in establishes trailer auth
- **GIVEN** the user opens `Settings > Integration > YouTube Trailer Login`
- **WHEN** the user completes the QR / device-code YouTube sign-in flow successfully
- **THEN** Nexio marks YouTube trailer auth as available
- **AND** Nexio persists the refresh/access token state in app-controlled storage for helper use

#### Scenario: Helper execution uses app-owned auth token
- **GIVEN** the user has an active `YouTube Trailer Login` session
- **WHEN** Nexio invokes the bundled helper for a YouTube-backed trailer
- **THEN** Nexio provides the helper with an authorization header derived from the stored session

### Requirement: Signed-in YouTube trailers must use helper resolution exclusively
When YouTube trailer auth is available, Nexio MUST route YouTube-backed trailers through the bundled helper rather than the legacy internal YouTube resolver.

#### Scenario: Signed-in YouTube trailer uses helper path
- **GIVEN** the user has an active `YouTube Trailer Login` session
- **AND** the trailer source is YouTube-backed
- **WHEN** Nexio resolves playback for that trailer
- **THEN** Nexio invokes the bundled helper
- **AND** Nexio does not use the legacy internal YouTube resolver for that trailer

#### Scenario: Signed-in non-YouTube trailer stays on existing internal path
- **GIVEN** the user has an active `YouTube Trailer Login` session
- **AND** the trailer source is not YouTube-backed
- **WHEN** Nexio resolves playback for that trailer
- **THEN** Nexio uses the existing internal trailer resolver path
- **AND** Nexio does not require the bundled helper for that trailer

### Requirement: Home poster long-press dialog must surface playable trailer action
The shared home poster long-press dialog MUST surface `Play Trailer` only when trailer availability is positive.

#### Scenario: Home poster has playable trailer
- **GIVEN** a poster in Classic, Grid, or Modern home has a trailer that Nexio can play internally
- **WHEN** the user opens the poster long-press dialog
- **THEN** the dialog includes a `Play Trailer` action

#### Scenario: Home poster has no playable trailer
- **GIVEN** a poster in Classic, Grid, or Modern home does not have a trailer that Nexio can play internally
- **WHEN** the user opens the poster long-press dialog
- **THEN** the dialog does not include a `Play Trailer` action
