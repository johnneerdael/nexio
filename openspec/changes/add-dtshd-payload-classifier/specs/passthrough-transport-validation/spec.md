## MODIFIED Requirements

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

#### Scenario: Classify wrapped DTS-HD payloads
- **WHEN** the selected bundled sample is a DTS-HD MA or DTS:X sample and the burst remains on the
  DTS-HD type-IV transport path
- **THEN** the validator classifies the wrapped payload as HD, likely-core-only, or unknown
- **AND** that payload classification supplements the burst-by-burst golden comparison result
- **AND** the classifier does not replace golden burst comparison as the primary source of truth

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

#### Scenario: Export includes DTS payload classifier metadata
- **WHEN** a diagnostics bundle is exported for a DTS-HD MA or DTS:X validation session
- **THEN** the per-burst export data includes the DTS type-IV subtype, wrapper metadata, and
  payload classification result
- **AND** the export can distinguish wrapped HD payload, wrapped likely-core-only payload, and
  wrapped unknown payload outcomes

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

#### Scenario: DTS wrapped payload collapse is surfaced explicitly
- **WHEN** a DTS-HD MA or DTS:X burst stays on the type-IV transport path but the validator
  classifies the wrapped payload as likely-core-only
- **THEN** the diagnostics surface that classifier result explicitly
- **AND** the result is distinguishable from a transport-wrapper disappearance or generic
  full-burst mismatch
