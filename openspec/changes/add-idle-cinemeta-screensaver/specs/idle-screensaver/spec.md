## ADDED Requirements

### Requirement: Global Idle Screensaver Trigger
The system SHALL activate a full-screen idle screensaver overlay after 300 seconds of no remote-control input while the user is on a browsing/navigation surface.

#### Scenario: Idle timeout activates screensaver
- **WHEN** the app is on a non-playback browsing screen and no remote input is received for 300 seconds
- **THEN** the screensaver overlay is shown above the current UI
- **AND** the underlying screen remains mounted so existing state and loading work can continue underneath

#### Scenario: Input resets the idle timer
- **WHEN** any remote input is received before the idle timeout expires
- **THEN** the idle countdown is reset from 300 seconds

#### Scenario: Screensaver is suppressed on incompatible routes
- **WHEN** the active route is playback or another explicitly excluded flow
- **THEN** the idle screensaver does not activate

### Requirement: Cold-Boot Cinemeta Candidate Refresh
The system SHALL refresh the screensaver candidate pool on every cold boot from the stock Cinemeta `Popular - Movie` and `Popular - Series` catalogs, regardless of whether those rows are currently visible on Home.

#### Scenario: Cold boot fetches top Cinemeta candidates
- **WHEN** the app performs a cold boot
- **THEN** it fetches the top 5 items from stock Cinemeta `Popular - Movie`
- **AND** it fetches the top 5 items from stock Cinemeta `Popular - Series`
- **AND** it prepares a combined candidate pool for the idle screensaver session

#### Scenario: Hidden Home rows still contribute to screensaver content
- **WHEN** either stock Cinemeta popular row is hidden from the Home layout
- **THEN** the screensaver candidate refresh still uses that row as a source

#### Scenario: Missing source rows degrade gracefully
- **WHEN** one or both stock Cinemeta popular catalogs fail or return no usable items
- **THEN** the screensaver uses the available successful candidates
- **AND** it does not show the screensaver if no usable candidates are available

### Requirement: Randomized Idle Rotation
The system SHALL rotate the prepared screensaver candidates in randomized order during the session.

#### Scenario: Rotation uses randomized ordering
- **WHEN** a screensaver session starts
- **THEN** the prepared candidate pool is shuffled for that session
- **AND** the screensaver advances through that randomized order instead of a fixed movie-first or series-first sequence

#### Scenario: Slide transition cadence
- **WHEN** the screensaver is active
- **THEN** each slide remains visible for approximately 15 seconds
- **AND** transitions between slides use a cross-fade animation

### Requirement: Netflix-Style Screensaver Presentation
The system SHALL render each screensaver slide with layered artwork, metadata, and panel-safe motion optimized for TV hardware.

#### Scenario: Slide renders with layered presentation
- **WHEN** a screensaver slide is displayed
- **THEN** the background artwork fills the screen
- **AND** a readability gradient/vignette is rendered above it
- **AND** the title logo is shown when available, otherwise a title text fallback is shown
- **AND** the slide can display available metadata such as year and rating

#### Scenario: Motion uses transform-based animation
- **WHEN** a screensaver slide is active
- **THEN** any motion effect is implemented using transform/alpha animation rather than layout-size animation
- **AND** the effect provides subtle slow-motion movement suitable for burn-in mitigation

#### Scenario: Next artwork is prefetched
- **WHEN** one slide is currently visible
- **THEN** the next slide artwork is prefetched before the transition starts
- **AND** transitions do not show white flashes or loading placeholders

### Requirement: Instant Dismiss And Deep Link Action
The system SHALL dismiss immediately on user input, and `OK/Select` SHALL deep-link into the selected title.

#### Scenario: Non-select input dismisses screensaver
- **WHEN** the screensaver is active and the user presses any non-select remote key
- **THEN** the screensaver is dismissed immediately
- **AND** the app returns to the prior UI state

#### Scenario: Select opens the highlighted title
- **WHEN** the screensaver is active and the user presses `OK/Select`
- **THEN** the screensaver is dismissed
- **AND** the app navigates to the selected title's detail screen
