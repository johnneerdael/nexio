## MODIFIED Requirements
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
- **AND** steady-state runtime refactors SHALL NOT change the captured transport bytes

### Requirement: Diagnostics Export

The system SHALL export a diagnostics bundle for validation sessions.

#### Scenario: Export validation diagnostics
- **WHEN** a developer requests validation export
- **THEN** the system exports structured logs, optional binary dumps, sample metadata, and relevant
  route/config diagnostics for that validation session
- **AND** the exported runtime diagnostics can distinguish transport success from late-stream
  steady-state output degradation

### Requirement: Runtime Continuity Validation

The system SHALL validate sink continuity and steady-state audio-output progress independently from
transport integrity.

#### Scenario: Report late-stream steady-state degradation separately from transport success
- **WHEN** a validation run completes with transport passing but steady-state output showing repeated
  zero-write recovery cycles or underrun
- **THEN** the diagnostics SHALL preserve `transportVerdict=PASS`
- **AND** the runtime diagnostics SHALL report the steady-state degradation separately
- **AND** the exported artifacts SHALL support comparing runtime continuity before and after native
  steady-state output refactors
