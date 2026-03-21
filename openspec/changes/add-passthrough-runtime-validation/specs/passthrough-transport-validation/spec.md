## ADDED Requirements
### Requirement: Runtime Playback Quality Validation

The system SHALL validate runtime playback quality independently from transport integrity during a
bundled passthrough validation session.

#### Scenario: Transport passes while runtime fails
- **WHEN** a validation run produces correct packed transport bursts and correct `AudioTrack.write`
  bursts
- **AND** playback still fails to start promptly, stalls, or becomes unstable
- **THEN** the validation result SHALL preserve the transport verdict
- **AND** the system SHALL report an independent runtime verdict for that same run

#### Scenario: Runtime validation stays scoped to bundled validation sessions
- **WHEN** a bundled passthrough validation run starts
- **THEN** runtime playback-quality collection begins for that validation session
- **AND** the collection ends with that validation session's teardown or export

#### Scenario: Runtime collection does not change transport capture behavior
- **WHEN** runtime playback validation is enabled
- **THEN** transport burst capture and transport comparison continue to operate exactly as before
- **AND** runtime collection remains additive to the existing transport validation flow

### Requirement: Runtime Signal Collection

The system SHALL collect runtime playback signals from Media3 during a validation session.

#### Scenario: Collect player and analytics runtime signals
- **WHEN** runtime playback validation is enabled for a bundled validation session
- **THEN** the system collects `Player.Listener` playback state changes, `isPlaying` changes, and
  player errors
- **AND** the system collects `AnalyticsListener` runtime signals including dropped video frames and
  audio underruns when available
- **AND** the system collects `PlaybackStatsListener` playback-duration and buffering statistics for
  that session

### Requirement: Runtime Verdicts and Failure Codes

The system SHALL report machine-readable runtime verdicts and runtime failure codes independently
from transport failure codes.

#### Scenario: Report runtime verdict for degraded playback
- **WHEN** a validation session exceeds configured startup or stability thresholds
- **THEN** the system reports a runtime verdict of `RUNTIME_DEGRADED` or `RUNTIME_FAIL`
- **AND** the system reports one or more runtime failure codes such as:
  - `STARTUP_TIMEOUT`
  - `READY_BUFFERING_OSCILLATION`
  - `AUDIO_UNDERRUN`
  - `DROPPED_VIDEO_FRAMES_HIGH`
  - `POSITION_STALLED`
  - `ROUTE_REPATCH_AFTER_START`
  - `PLAYER_ERROR`

#### Scenario: Report stalled playback position with an objective rule
- **WHEN** playback position fails to advance by at least a configured minimum delta while
  `isPlaying=true` over a configured observation window
- **THEN** the system reports `POSITION_STALLED`
- **AND** the configured window and minimum delta are included in the exported runtime summary

#### Scenario: Preserve unknown runtime outcome
- **WHEN** transport validation completes but runtime observation is unavailable or incomplete
- **THEN** the system reports `RUNTIME_UNKNOWN`
- **AND** the transport verdict remains usable on its own

## MODIFIED Requirements
### Requirement: Diagnostics Export

The system SHALL export a diagnostics bundle for validation sessions.

#### Scenario: Export validation diagnostics
- **WHEN** a developer requests validation export
- **THEN** the system exports structured logs, optional binary dumps, sample metadata, relevant
  route/config diagnostics, and runtime playback diagnostics for that validation session

#### Scenario: Export includes route/config snapshot
- **WHEN** a diagnostics bundle is exported
- **THEN** the bundle includes selected sample metadata, routed device, encoding, sample rate,
  channel mask, direct playback support result, `AudioTrack` state, and per-burst comparison
  results

#### Scenario: Export identifies the golden reference set
- **WHEN** a diagnostics bundle is exported
- **THEN** the bundle includes the manifest version and asset checksum information for the bundled
  golden reference set used by that validation session

#### Scenario: Export includes runtime playback reports
- **WHEN** a diagnostics bundle is exported for a validation run with runtime collection enabled
- **THEN** the bundle includes `runtime-summary.json`, `playback-stats.json`,
  `player-events.json`, and `analytics-events.json`
- **AND** the summary identifies both `transportVerdict` and `runtimeVerdict`
- **AND** the summary includes the configured runtime thresholds used for verdict calculation

### Requirement: Debug Control Surfaces

The system SHALL expose both in-app debug controls and an ADB-triggerable command path for
validation control.

#### Scenario: Control validation from the debug UI
- **WHEN** a developer opens the in-app debug settings screen
- **THEN** the UI provides validation enablement, sample selection, capture mode selection, binary
  dump enablement, diagnostics export, and runtime validation settings including startup timeout or
  observation-window controls

#### Scenario: Control validation from ADB
- **WHEN** a developer uses the ADB debug command path
- **THEN** the command surface supports enable/disable, sample selection, start/stop validation,
  export, clearing the previous session, and runtime validation settings such as startup timeout or
  observation-window controls
