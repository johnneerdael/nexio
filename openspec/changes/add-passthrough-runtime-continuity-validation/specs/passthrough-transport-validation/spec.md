## ADDED Requirements
### Requirement: Runtime Continuity Validation

The system SHALL validate sink continuity and steady-state audio-output progress independently from
transport integrity and player-state runtime quality during a passthrough validation session.

#### Scenario: Detect choppy audio without Media3 rebuffering
- **WHEN** playback remains in `READY` and avoids repeated `BUFFERING`
- **AND** the sink shows repeated zero-write cycles, playback-head stalls, restart churn, or stuck
  output remainders
- **THEN** the validator SHALL report a runtime continuity degradation or failure for that same run
- **AND** the transport verdict SHALL remain independent

#### Scenario: Runtime continuity remains additive to existing runtime validation
- **WHEN** runtime continuity validation is enabled
- **THEN** the existing Media3 listener and playback-stats runtime layer continues to run
- **AND** the new continuity layer adds to that runtime evidence instead of replacing it

### Requirement: Continuous Sink Health Collection

The system SHALL continuously record sink/output health metrics at the custom sink/native output
boundary while validation playback is expected to be active.

#### Scenario: Capture steady-state output progress metrics
- **WHEN** a validation session enters active playback
- **THEN** the system records write attempts, requested bytes, written bytes, zero-write count,
  partial-write count, and output queue depth
- **AND** the system records playback-head advance samples, underrun count, restart count, and
  stuck-remainder duration

#### Scenario: Report sink continuity failure codes
- **WHEN** sink/output progress exceeds configured continuity thresholds
- **THEN** the system reports machine-readable failure codes including:
  - `AUDIO_WRITE_ZERO_STREAK`
  - `PLAYBACK_HEAD_STALLED`
  - `POSITION_STALLED_WHILE_PLAYING`
  - `AUDIO_TIMESTAMP_DISCONTINUITY`
  - `PARTIAL_WRITE_REMAINDER_STUCK`
  - `AUDIO_UNDERRUN_REPEATED`
  - `OUTPUT_RESTART_CHURN`

### Requirement: Route Stability Validation

The system SHALL validate route stability after playback reaches a stable passthrough start.

#### Scenario: Detect route instability after stable start
- **WHEN** playback reaches an initial stable passthrough route
- **AND** the routed encoding, sample rate, channel mask, or output route changes unexpectedly after
  that point
- **THEN** the system reports route-stability degradation or failure
- **AND** the system reports machine-readable failure codes including:
  - `ROUTE_REOPEN_AFTER_START`
  - `ROUTE_RECLASSIFIED_AFTER_START`

### Requirement: Playback-Head Health Validation

The system SHALL validate continuous forward audio progress using playback-head health sampling.

#### Scenario: Detect playback-head stall during expected playback
- **WHEN** `isPlaying=true`
- **AND** playback-head progress fails to advance by the configured minimum amount for longer than
  the configured stall threshold
- **THEN** the system reports `PLAYBACK_HEAD_STALLED`
- **AND** the system reports `POSITION_STALLED_WHILE_PLAYING` in the runtime summary
- **AND** the exported diagnostics include the longest observed playback-head stall duration

### Requirement: Explicit Runtime Scoring Windows

The system SHALL score continuity and route-stability failures against explicit steady-state timing
windows.

#### Scenario: Score continuity only after stable start
- **WHEN** a validation run has not yet reached its configured stable-start point
- **THEN** startup activity before that point SHALL NOT be scored as steady-state continuity failure
- **AND** the configured stable-start timing SHALL be exported with the runtime diagnostics

#### Scenario: Exclude late end-of-run noise by default
- **WHEN** a late underrun, restart, or similar disruption occurs after an otherwise healthy run
- **AND** the event falls inside the configured late-noise exclusion window near the end of playback
- **THEN** the raw event SHALL still be exported
- **BUT** the event SHALL be excluded from default steady-state runtime failure scoring

### Requirement: Operator Runtime Observations

The system SHALL accept structured operator runtime observations for AVR lock quality and audible
audio quality during a validation session.

#### Scenario: Record operator observation from ADB
- **WHEN** a developer submits a structured operator runtime observation through the debug command
  path
- **THEN** the validator stores AVR lock quality, audio quality, and any provided note in the
  active validation session

#### Scenario: Operator observation can downgrade runtime verdict
- **WHEN** the operator reports weak AVR lock or choppy/dropout audio
- **THEN** the validator SHALL preserve transport truth
- **AND** the runtime scoring SHALL be allowed to degrade through failure codes including:
  - `WEAK_AVR_LOCK_REPORTED`
  - `CHOPPY_AUDIO_REPORTED`

## MODIFIED Requirements
### Requirement: Runtime Playback Quality Validation

The system SHALL validate runtime playback quality independently from transport integrity during a
passthrough validation session.

#### Scenario: Runtime validation uses layered runtime truths
- **WHEN** runtime playback validation is enabled for a passthrough validation session
- **THEN** the system scores runtime quality from:
  - player-state runtime signals
  - sink continuity signals
  - route stability signals
  - operator observations
- **AND** the final `runtimeVerdict` SHALL be derived from those layered runtime truths

### Requirement: Runtime Verdicts and Failure Codes

The system SHALL report machine-readable runtime verdicts and runtime failure codes independently
from transport failure codes.

#### Scenario: Runtime verdict includes sub-verdicts
- **WHEN** a diagnostics bundle is exported for a validation run with runtime collection enabled
- **THEN** the runtime diagnostics include sub-verdicts for player-state quality, sink continuity,
  route stability, and operator observations
- **AND** the system rolls those sub-verdicts into the exported `runtimeVerdict`

#### Scenario: Runtime diagnostics export the shared scoring config
- **WHEN** a diagnostics bundle is exported for a validation run with runtime continuity collection
  enabled
- **THEN** the exported runtime diagnostics include the centralized runtime threshold and scoring-
  window configuration used for that run

#### Scenario: Runtime pass requires healthy layered runtime truths
- **WHEN** a validation run avoids startup/control-plane failure
- **BUT** sink continuity, route stability, or operator observation indicates degraded playback
- **THEN** the system SHALL NOT report `RUNTIME_PASS`

### Requirement: Diagnostics Export

The system SHALL export a diagnostics bundle for validation sessions.

#### Scenario: Export includes continuity and operator reports
- **WHEN** a diagnostics bundle is exported for a validation run with runtime continuity collection
  enabled
- **THEN** the bundle includes `sink-health.json`, `route-health.json`,
  `playback-head-health.json`, and `operator-observation.json`
- **AND** `runtime-summary.json` includes the configured thresholds and continuity/route/operator
  metrics used for runtime verdict calculation

### Requirement: Debug Control Surfaces

The system SHALL expose both in-app debug controls and an ADB-triggerable command path for
validation control.

#### Scenario: Control operator runtime observations from ADB
- **WHEN** a developer uses the ADB debug command path during an active validation session
- **THEN** the command surface supports structured operator runtime observations for AVR lock
  quality, audio quality, and an optional note
