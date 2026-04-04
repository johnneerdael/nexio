## ADDED Requirements

### Requirement: NEXIO provides a no-addon universal-streamer default
When the user has no installed addons, the app SHALL switch from addon-based play routing to an installed-official-app handoff mode.

#### Scenario: Zero installed addons enables universal-streamer mode
- **WHEN** the user has zero installed addons
- **THEN** detail-page play actions use the universal-streamer path instead of addon-based stream playback

#### Scenario: Any installed addon disables universal-streamer mode completely
- **WHEN** the user has one or more installed addons
- **THEN** the universal-streamer mode is disabled
- **AND** play actions return to the normal addon-based flow

### Requirement: NEXIO can hand off no-addon play requests to installed official apps
In universal-streamer mode, detail-page play actions SHALL attempt to resolve supported installed official streaming apps that expose a usable in-app search entry point and hand off the selected title into one of them.

#### Scenario: Non-searchable installed apps are excluded
- **WHEN** a supported official streaming app is installed
- **AND** it does not expose a usable package-scoped in-app search entry point
- **THEN** NEXIO excludes that app from the chooser instead of falling back to a generic app-home launch

#### Scenario: Multiple installed apps show a chooser
- **WHEN** universal-streamer mode resolves more than one supported installed official app
- **THEN** the app shows a chooser instead of auto-picking one

#### Scenario: No installed official app shows setup guidance
- **WHEN** universal-streamer mode finds no supported installed official app
- **THEN** the app shows a guidance dialog telling the user to install a supported app or enable addons

### Requirement: Android TV searchable integration for NEXIO is tracked as a future phase
The no-addon official-app handoff behavior SHALL remain separate from future Android TV searchable integration work for NEXIO itself.

#### Scenario: Searchable phase separation stays explicit
- **WHEN** this capability is implemented
- **THEN** the current change only covers provider handoff through installed app in-app search
- **AND** Android TV system search exposure for NEXIO itself remains a future documented phase
