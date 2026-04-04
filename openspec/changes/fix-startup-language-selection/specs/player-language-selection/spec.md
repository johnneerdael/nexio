## ADDED Requirements

### Requirement: Startup audio selection honors preferred language over default track ordering
When initial playback starts, Nexio SHALL prefer the configured startup audio language even when the container default track points at a different language.

#### Scenario: Preferred English audio beats non-English default track
- **GIVEN** the preferred audio language is English
- **AND** the source contains multiple audio tracks
- **AND** the default selected track is not English
- **WHEN** playback starts
- **THEN** Nexio selects the English audio track if one exists

### Requirement: Original language can be used as the startup audio preference
Nexio SHALL support an Original language audio preference mode for startup selection.

#### Scenario: Original language preference resolves from content metadata
- **GIVEN** the preferred audio language mode is Original language
- **AND** the content original language is available
- **WHEN** playback starts
- **THEN** Nexio prefers the audio track that matches that original language

### Requirement: Startup subtitle selection prefers downloaded primary-language subtitles over embedded secondary-language subtitles
When choosing subtitles at startup, Nexio SHALL prefer addon/downloaded subtitles in the primary subtitle language before embedded subtitles that only match the secondary subtitle language.

#### Scenario: Downloaded Dutch subtitle beats embedded English subtitle
- **GIVEN** the primary subtitle language is Dutch
- **AND** the secondary subtitle language is English
- **AND** embedded subtitles contain English but not Dutch
- **AND** addon subtitles contain Dutch
- **WHEN** playback starts
- **THEN** Nexio selects the downloaded Dutch subtitle

### Requirement: Gemini startup fallback can translate secondary-language subtitles to the primary language
When no subtitle in the primary language exists at startup, Nexio SHALL select the best available secondary-language subtitle and SHALL auto-enable Gemini translation to the primary subtitle language when Gemini is configured and enabled.

#### Scenario: Secondary subtitle is auto-translated at startup
- **GIVEN** the primary subtitle language is unavailable in both embedded and downloaded subtitles
- **AND** a subtitle in the secondary subtitle language is available
- **AND** Gemini is configured and enabled
- **WHEN** playback starts
- **THEN** Nexio selects the secondary-language subtitle
- **AND** Nexio auto-enables Gemini translation targeting the primary subtitle language

### Requirement: Gemini startup translation failure keeps the fallback subtitle active
If Gemini startup translation fails, Nexio SHALL keep the chosen fallback subtitle active instead of leaving the user without subtitles.

#### Scenario: Failed startup translation preserves secondary-language subtitle
- **GIVEN** Nexio selected a fallback subtitle in the secondary language at startup
- **AND** Gemini startup translation fails
- **WHEN** the failure is handled
- **THEN** the fallback subtitle remains selected
