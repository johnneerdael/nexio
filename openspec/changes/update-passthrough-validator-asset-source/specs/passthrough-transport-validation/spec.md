## MODIFIED Requirements

### Requirement: Debug Transport Validation Mode

The system SHALL provide a debug-only passthrough transport validation mode for golden validation
assets sourced from the configured validator asset set.

#### Scenario: Validation mode is available in debug surfaces
- **WHEN** the app is running in a debug-capable build
- **THEN** the developer can enable passthrough transport validation from the app's debug surface
- **AND** the same validation mode can be controlled through an ADB-triggerable debug command path

### Requirement: Bundled Golden Sample Playback

The system SHALL launch validation playback from the configured golden validation asset set only.

#### Scenario: Launch selected validation sample
- **WHEN** a developer selects a validation sample from the validation controls
- **THEN** the app launches playback for that sample through the validation flow
- **AND** the validation session is associated with the selected golden reference metadata

#### Scenario: Download validation files before playback
- **WHEN** the selected validation sample requires hosted files that are not already cached locally
- **THEN** the validator downloads the required source and reference files before starting playback
- **AND** the validator stores them in app-specific local storage

### Requirement: Diagnostics Export

The system SHALL export a diagnostics bundle for validation sessions.

#### Scenario: Export identifies the golden reference set
- **WHEN** a diagnostics bundle is exported
- **THEN** the bundle includes the manifest version and asset checksum information for the selected
  golden reference set used by that validation session
- **AND** the bundle includes the validator asset source URL and cache-state metadata for the local
  files used by that session

## ADDED Requirements

### Requirement: Hosted Validator Asset Source

The system SHALL support a hosted validator asset source for debug validation media and reference
files.

#### Scenario: Load validation manifest from hosted source
- **WHEN** the validator catalog is initialized in a debug-capable build
- **THEN** the app loads `transport_validation_manifest.json` from the configured hosted validator
  asset source
- **AND** the available validation samples are derived from that manifest

#### Scenario: Reuse cached validator files
- **WHEN** a required validator file already exists in local validator storage
- **AND** its checksum matches the manifest entry
- **THEN** the validator reuses the local file instead of downloading it again

#### Scenario: Reject stale cached validator files
- **WHEN** a required validator file exists locally but its checksum does not match the manifest
- **THEN** the validator invalidates the stale local copy
- **AND** downloads a fresh copy before validation continues

### Requirement: Lightweight Debug APK Packaging

The system SHALL keep large validator media/reference files out of the debug APK.

#### Scenario: Debug APK excludes large validator sample assets
- **WHEN** a debug APK is assembled
- **THEN** large validator media/reference files are not packaged into the APK
- **AND** validation sample access depends on the hosted asset source and local cache instead
