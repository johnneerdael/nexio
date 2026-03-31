## ADDED Requirements

### Requirement: Modern home consumes prepared presentation state

The modern home experience SHALL build carousel rows, row lookups, and warm-start presentation
state outside the composable hot path and feed the prepared structure into UI rendering.

#### Scenario: Warm-start home presentation before full build

- **GIVEN** modern home has more catalog rows than are needed for first paint
- **WHEN** the home ViewModel prepares modern home presentation state
- **THEN** it publishes a reduced warm-start presentation first
- **AND** it replaces that warm-start snapshot with the full presentation after background building

#### Scenario: Focus restore resolves rows from prepared lookups

- **GIVEN** modern home has saved row and item focus state
- **WHEN** the screen restores focus
- **THEN** it resolves the target row from prepared row-key lookups instead of rescanning raw
  catalog rows inside the composable

### Requirement: Stream-selection directional repeat input is throttled

The stream selection screen and player source side panel SHALL throttle repeated directional D-pad
input so rapid repeats do not overwhelm focus/filter updates.

#### Scenario: Repeated left or right input is throttled

- **GIVEN** the user holds a directional D-pad key on stream selection filters
- **WHEN** repeat events arrive faster than the configured throttle window
- **THEN** excess repeat events are consumed
- **AND** filter changes advance at the throttled cadence

#### Scenario: Repeated up or down input is throttled in source panels

- **GIVEN** the user holds a directional D-pad key in the player source side panel
- **WHEN** repeat events arrive faster than the configured throttle window
- **THEN** excess repeat events are consumed before focus traversal or filter mutation

### Requirement: Playback retries transient failures once

Playback SHALL retry recoverable transient failures once before surfacing a terminal error.

#### Scenario: Generic transient playback failure retries once

- **GIVEN** playback hits a recoverable transient IO or decoder failure
- **WHEN** the player error callback runs for the current stream
- **THEN** the runtime reinitializes the current stream once from the current position
- **AND** it does not loop indefinitely on repeated failures

#### Scenario: Audio-track switch forces segment refetch

- **GIVEN** playback is active and the user selects a different audio track
- **WHEN** the runtime applies the new audio track override
- **THEN** it seeks back one millisecond from the current position
- **AND** playback is forced to refetch the current segment with the new track selection

### Requirement: App and playback clients allow invalid certificates

Network and playback HTTP clients SHALL accept invalid or self-signed certificates for addon,
metadata, and playback traffic.

#### Scenario: App HTTP client trusts invalid certificates

- **GIVEN** an addon or metadata endpoint presents a self-signed or otherwise invalid certificate
- **WHEN** Nexio performs an HTTP request through the shared app client
- **THEN** the request proceeds without TLS validation failure

#### Scenario: Playback HTTP client trusts invalid certificates

- **GIVEN** a playback stream presents a self-signed or otherwise invalid certificate
- **WHEN** the media source factory creates its OkHttp client
- **THEN** playback requests proceed without TLS validation failure

### Requirement: Localized metadata respects locale-sensitive language and date rules

TMDB localization and user-facing air-date formatting SHALL respect exact language-region matches
and locale-aware date patterns.

#### Scenario: TMDB language normalization preserves region intent

- **GIVEN** Nexio resolves a TMDB language tag such as `pt-br` or `es-419`
- **WHEN** it normalizes that language for TMDB requests and localized image selection
- **THEN** region codes are uppercased
- **AND** `es-419` maps to `es-MX`
- **AND** exact language-region matches sort ahead of language-only fallbacks

#### Scenario: Continue watching air dates use locale-aware ordering

- **GIVEN** continue watching or episode cards display an episode air date
- **WHEN** the current locale uses day-first ordering
- **THEN** the rendered label uses the locale-aware day/month ordering instead of a hardcoded
  month-first pattern

### Requirement: Home and stream surfaces reduce redundant overdraw

Modern home, sidebar, and stream rendering SHALL avoid duplicate background paints and expensive
full-surface blur/offscreen composition when the same visual result can be achieved with bounded
single-pass drawing.

#### Scenario: Root surfaces avoid duplicate background layers

- **GIVEN** the app theme or window already provides the base background
- **WHEN** home, catalog, or detail-adjacent poster surfaces render
- **THEN** they avoid adding redundant root background layers
- **AND** poster/grid surfaces rely on image placeholder, fallback, or bounded gradients instead of
  separate opaque container fills underneath the same content

#### Scenario: Sidebar and stream overlays avoid expensive full-surface effects

- **GIVEN** the modern sidebar or stream screen overlay is visible
- **WHEN** Nexio renders its overlay gradients and chrome
- **THEN** it does not require sidebar haze blur or forced offscreen compositing for those layers
- **AND** any remaining overlay gradients stay bounded to the regions needed for readability

### Requirement: Home watched badges stay live for movies and series

Home poster watched badges SHALL update from live watched-state observers without per-item memoized
staleness, and they SHALL support series posters as well as movies.

#### Scenario: Movie watched badge updates without refocus

- **GIVEN** a movie poster is visible on home
- **WHEN** its watched state changes
- **THEN** the poster badge updates without requiring the user to refocus the card

#### Scenario: Series watched badge uses watched-show state

- **GIVEN** a series poster is visible on home
- **WHEN** Trakt watched-show state indicates the series is completed
- **THEN** the home poster badge renders as watched
