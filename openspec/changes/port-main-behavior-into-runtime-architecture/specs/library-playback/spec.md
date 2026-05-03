## ADDED Requirements

### Requirement: Continue Watching launch context preserves source and identity data

Continue Watching playback and manual-selection routes MUST preserve the source addon context, content id, video id, content type, content name, season, episode, runtime, original language, resume state, and stable IDs available from the item.

#### Scenario: Continue Watching resume uses originating addon context

- **GIVEN** a Continue Watching item was created from an addon stream
- **AND** the item has an originating addon base URL
- **WHEN** the user resumes playback from Modern Home
- **THEN** the stream route carries the addon base URL
- **AND** the stream lookup prefers the originating addon before cross-addon fallback
- **AND** player navigation preserves the same addon base URL for playback records.

#### Scenario: Continue Watching Survivor S05E10 enters autoplay with episode context

- **GIVEN** a Continue Watching item for Survivor season 5 episode 10
- **WHEN** the user resumes playback
- **THEN** the stream request context contains content type `series`
- **AND** the request contains season `5`
- **AND** the request contains episode `10`
- **AND** the stable ID bundle contains the canonical TVDB series id when resolvable
- **AND** deterministic autoplay logs the stream candidate count and every rejection reason before reporting no eligible links.

### Requirement: Continue Watching unaired gating uses localized TVDB timing

Next-up and Continue Watching visibility MUST use localized TVDB timing when exact provider timing is available.

#### Scenario: Unaired next-up item is hidden until local air time

- **GIVEN** TVDB supplies an episode air instant for a next-up episode
- **WHEN** the device-local time is before that instant
- **THEN** the item is not rendered as playable in Continue Watching
- **AND** the next refresh is scheduled for the soonest known air time
- **AND** the trace includes an exact air-time diagnostic.

### Requirement: Deterministic autoplay ports keep shared selector path

Autoplay safety fixes from `main` MUST land in the existing stream presentation, parsing, scoring, and playback preflight path.

#### Scenario: Title mismatch is rejected before scoring

- **GIVEN** a stream candidate filename parses to a different title than the requested content
- **WHEN** deterministic autoplay evaluates candidates
- **THEN** the candidate is rejected before benchmark-aware scoring
- **AND** the rejection is logged with the requested title, parsed title, and candidate key
- **AND** manual stream selection still shows the candidate unless a separate manual filter is active.

#### Scenario: Diacritic-equivalent title is accepted

- **GIVEN** requested title `Le Samouraï`
- **AND** a parsed candidate title `Le Samourai`
- **WHEN** deterministic title matching runs
- **THEN** the candidate is accepted as title-compatible.
