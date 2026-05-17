## ADDED Requirements

### Requirement: Embedded MKV Text Track Harvest

The subtitle translation system SHALL support background harvesting of selected embedded Matroska text subtitle tracks for ahead-of-playback translation.

#### Scenario: Eligible embedded MKV SubRip track

- **WHEN** playback uses a Matroska stream
- **AND** auto-translate is enabled
- **AND** an internal `subrip` subtitle track is selected
- **THEN** the system starts a background harvest session for that selected track
- **AND** the harvest session extracts timed source cue records independently of renderer lookahead

#### Scenario: Unsupported subtitle source

- **WHEN** the selected subtitle source is not an internal Matroska text track supported by the current phase
- **THEN** the background harvester is not started
- **AND** playback continues using the existing subtitle translation behavior
- **AND** the unsupported reason is logged

#### Scenario: Track changes during playback

- **WHEN** the selected subtitle track changes during playback
- **THEN** the active harvest session is cancelled
- **AND** a new timeline key is created for the newly selected track when eligible
- **AND** translations from the previous track are not used for the new track

### Requirement: Translated Subtitle Timeline Replacement

The subtitle translation system SHALL render original subtitles until translated replacements are available in a session-scoped translated timeline.

#### Scenario: Timeline hit

- **WHEN** the renderer is about to display a source cue
- **AND** the translated timeline contains a matching translated cue for the same stream, track, target language, settings signature, timing, and source text identity
- **THEN** the renderer displays the translated cue instead of the original cue

#### Scenario: Timeline miss

- **WHEN** the renderer is about to display a source cue
- **AND** the translated timeline does not contain a matching translated cue
- **THEN** the renderer displays the original source cue unchanged
- **AND** the source cue is registered for translation backfill when it is eligible

#### Scenario: Translation or harvest failure

- **WHEN** harvesting, translation, or timeline lookup fails
- **THEN** playback displays original subtitles unchanged
- **AND** the failure reason is logged
- **AND** the system does not suppress, blank, or block subtitle display because translation is late

### Requirement: Ahead Translation Diagnostics

The subtitle translation system SHALL expose diagnostics that distinguish harvested timeline translation from renderer-driven micro-batch translation.

#### Scenario: Harvest progress reported

- **WHEN** an embedded MKV harvest session is active
- **THEN** diagnostics include selected track identity, codec, language, harvested cue count, and latest harvested time range

#### Scenario: Translation progress reported

- **WHEN** harvested cue translation is active
- **THEN** diagnostics include translated cue count, pending cue count, failed cue count, and current ready range

#### Scenario: Renderer replacement reported

- **WHEN** subtitles are rendered during an eligible harvest session
- **THEN** diagnostics include translated timeline hit and miss counts
- **AND** fallback reasons are logged when original subtitles are rendered for eligible cues
