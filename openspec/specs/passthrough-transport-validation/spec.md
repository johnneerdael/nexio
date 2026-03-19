# passthrough-transport-validation Specification

## Purpose
TBD - created by archiving change add-passthrough-transport-validation. Update Purpose after archive.
## Requirements
### Requirement: Debug Transport Validation Mode

The system SHALL provide a debug-only passthrough transport validation mode for bundled golden
validation assets.

#### Scenario: Validation mode is available in debug surfaces
- **WHEN** the app is running in a debug-capable build
- **THEN** the developer can enable passthrough transport validation from the app's debug surface
- **AND** the same validation mode can be controlled through an ADB-triggerable debug command path

### Requirement: Bundled Golden Sample Playback

The system SHALL launch validation playback from bundled golden validation samples only.

#### Scenario: Launch selected validation sample
- **WHEN** a developer selects a bundled validation sample from the validation controls
- **THEN** the app launches playback for that bundled sample through the validation flow
- **AND** the validation session is associated with the selected golden reference metadata

### Requirement: Burst-by-Burst Golden Comparison

The system SHALL compare live transport bursts against the selected golden reference on a
burst-by-burst basis.

#### Scenario: Compare live burst to aligned reference burst
- **WHEN** a validation session emits live transport bursts
- **THEN** the system compares live burst `N` with golden reference burst `N`
- **AND** the comparison uses the same logical `Pa/Pb/Pc/Pd` byte-order interpretation on both
  sides
- **AND** the comparison result includes an explicit pass/fail status and failure code

#### Scenario: Prevent false prefix-only comparison
- **WHEN** live validation data is captured
- **THEN** the validator SHALL NOT compare arbitrary leading stream bytes in place of aligned burst
  records
- **AND** the system SHALL report alignment failures explicitly

### Requirement: Transport Boundary Capture

The system SHALL capture validation data at the key passthrough transport boundaries without
mutating the transport path.

#### Scenario: Capture at validation boundaries
- **WHEN** validation mode is enabled for a bundled sample
- **THEN** the system captures transport data at pre-packer input, packed burst output, and
  pre-`AudioTrack.write` boundaries
- **AND** the capture remains observational only
- **AND** the captured chain can be used to prove whether bytes changed between packer output and
  the `AudioTrack.write` boundary

### Requirement: Codec-Specific Validation Rules

The system SHALL apply codec-specific validation rules rather than a single generic pass/fail rule
set.

#### Scenario: Validate TrueHD-specific burst rules
- **WHEN** the selected bundled sample is a TrueHD sample
- **THEN** the validator checks MAT-specific burst shape, `Pc & 0x7F == 0x16`, and MAT-style `Pd`
  expectations

#### Scenario: Validate DTS-HD without silent core fallback
- **WHEN** the selected bundled sample is a DTS-HD MA or DTS:X sample
- **THEN** the validator checks the expected HD transport wrapper details
- **AND** the validator can report `DTSHD_CORE_ONLY_FALLBACK`

#### Scenario: Validate E-AC-3 JOC preservation
- **WHEN** the selected bundled sample is an E-AC-3 Atmos/JOC sample
- **THEN** the validator checks `Pc & 0x7F == 0x15`
- **AND** the validator checks aggregated payload length expectations
- **AND** the validator verifies payload preservation across burst aggregation

### Requirement: Diagnostics Export

The system SHALL export a diagnostics bundle for validation sessions.

#### Scenario: Export validation diagnostics
- **WHEN** a developer requests validation export
- **THEN** the system exports structured logs, optional binary dumps, sample metadata, and relevant
  route/config diagnostics for that validation session

#### Scenario: Export includes route/config snapshot
- **WHEN** a diagnostics bundle is exported
- **THEN** the bundle includes selected sample metadata, routed device, encoding, sample rate,
  channel mask, direct playback support result, `AudioTrack` state, and per-burst comparison
  results

#### Scenario: Export identifies the golden reference set
- **WHEN** a diagnostics bundle is exported
- **THEN** the bundle includes the manifest version and asset checksum information for the bundled
  golden reference set used by that validation session

### Requirement: Explicit Failure Codes

The system SHALL report canonical machine-readable failure codes for initial validation failures.

#### Scenario: Canonical failure codes are available to diagnostics
- **WHEN** a validation burst comparison fails
- **THEN** the system can report canonical failure codes including:
  - `PREAMBLE_MISMATCH`
  - `BURST_ALIGNMENT_FAILED`
  - `PACKER_TO_AUDIOTRACK_MUTATION`
  - `TRUEHD_MAT_INVALID`
  - `DTSHD_CORE_ONLY_FALLBACK`
  - `EAC3_AGGREGATION_MISMATCH`

### Requirement: Debug Control Surfaces

The system SHALL expose both in-app debug controls and an ADB-triggerable command path for
validation control.

#### Scenario: Control validation from the debug UI
- **WHEN** a developer opens the in-app debug settings screen
- **THEN** the UI provides validation enablement, sample selection, capture mode selection, binary
  dump enablement, and diagnostics export

#### Scenario: Control validation from ADB
- **WHEN** a developer uses the ADB debug command path
- **THEN** the command surface supports enable/disable, sample selection, start/stop validation,
  export, and clearing the previous session

### Requirement: Codec-Specific Collection Documentation

The system SHALL provide documentation for running and collecting validation diagnostics for each
supported bundled codec sample.

#### Scenario: Follow documented codec workflow
- **WHEN** a developer needs to validate a specific bundled codec sample
- **THEN** the repository contains documentation describing the required ADB collection steps for
  that codec workflow

