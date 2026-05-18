## ADDED Requirements

### Requirement: MP4 Text Sample Table Harvest

The subtitle translation system SHALL support background harvesting of selected embedded MP4 text subtitle tracks from MP4 text sample table metadata rather than renderer lookahead or playback-style extractor reads.

#### Scenario: Eligible embedded MP4 text track

- **WHEN** playback uses an MP4, M4V, or MOV stream
- **AND** auto-translate is enabled
- **AND** a supported internal text subtitle track is selected
- **THEN** the system starts a background sample-table harvest session for that selected track
- **AND** the harvest session extracts timed source cue records independently of renderer lookahead
- **AND** the harvest session does not require playback to advance before future cue records can be discovered

#### Scenario: Sample table discovery

- **WHEN** the MP4 container metadata is readable
- **THEN** the harvest path obtains text sample entries for the selected supported text track
- **AND** each entry includes cue time, sample byte offset, sample byte size, sample flags, format, language, and selected text ordinal
- **AND** audio and video sample table entries are not exposed to the app harvester

#### Scenario: Direct sample reads

- **WHEN** the selected MP4 text sample table is available
- **THEN** the harvester reads subtitle sample bytes by sample offset and size
- **AND** nearby sample byte ranges are coalesced when doing so reduces HTTP range opens without delaying cue publication materially
- **AND** decoded cue records are inserted into the session-scoped translated subtitle timeline

#### Scenario: Fallback when sample table harvest is unavailable

- **WHEN** sample table harvest cannot be used for the selected MP4 text track
- **THEN** the system may use the existing MP4 extractor-loop harvester as a fallback
- **AND** playback continues rendering original subtitles until translated timeline replacements exist
- **AND** the fallback reason is logged

### Requirement: MP4 Ahead Harvest Proof Diagnostics

The subtitle translation system SHALL expose diagnostics that prove whether MP4 harvesting is building a large ahead timeline or only advancing through a bounded extractor loop.

#### Scenario: Sample table harvest progress reported

- **WHEN** an MP4 sample-table harvest session is active
- **THEN** diagnostics include selected track identity, sample table count, read range count, coalesced range count, harvested cue count, latest harvested cue time, translated cue count, and completion status

#### Scenario: Extractor-loop fallback progress reported

- **WHEN** the MP4 extractor-loop harvester is active
- **THEN** diagnostics include extractor read count, input reopen count, seek count, cumulative input open time, harvested cue count, latest harvested cue time, and latest seek position

#### Scenario: Device proof of ahead timeline

- **WHEN** auto-translate is enabled during live MP4 playback on the rooted verification device
- **THEN** diagnostics can demonstrate whether the translated source cue count rises to a large/full ahead set without waiting for playback to reach each cue
- **AND** diagnostics can distinguish translation backlog from container harvesting backlog
