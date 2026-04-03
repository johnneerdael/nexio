## ADDED Requirements

### Requirement: Local probe authenticates with Real-Debrid from environment configuration
The local transport probe SHALL load Real-Debrid authentication from environment-backed
configuration instead of relying on pasted final URLs only.

#### Scenario: Probe reads token from environment file
- **WHEN** the operator runs the probe with a valid `.env` file
- **THEN** the probe authenticates using the configured Real-Debrid token
- **AND** subsequent candidate-resolution and unrestrict requests use that token

### Requirement: Local probe can resolve a large Real-Debrid candidate automatically or by override
The local transport probe SHALL support both Nexio-like automatic large-file selection and explicit
item overrides for reproducible reruns.

#### Scenario: Automatic resolution chooses a deterministic large playable candidate
- **WHEN** the operator runs the probe without a specific item override
- **THEN** the probe filters to playable video files with known size
- **AND** chooses the largest candidate by size
- **AND** breaks size ties by the most recently listed candidate
- **AND** the resulting session records the selected file metadata and resolved host

#### Scenario: Operator can override the candidate identity
- **WHEN** the operator supplies `--download-id`, `--torrent-id`, or debug-only `--direct-url`
- **THEN** the probe resolves that exact target instead of auto-selecting another file
- **AND** the session records that the run used an explicit override

### Requirement: Local probe models parallel range workers and in-order consumer delivery
The probe SHALL measure both worker-side range activity and assembled consumer-side progress.

#### Scenario: Probe distinguishes head-of-line blocking from worker completion ahead of the consumer
- **WHEN** a later range finishes before the next required leading range
- **THEN** the probe records that the consumer is blocked on the missing leading range
- **AND** completed ahead-of-consumer ranges remain visible in the telemetry

#### Scenario: Probe records per-worker request lifecycle
- **WHEN** parallel workers run against the resolved direct URL
- **THEN** the probe records range assignment, request open, first byte, bytes received, retries,
  completion, and failure events per worker

### Requirement: Local probe emits structured artifacts for offline analysis
The probe SHALL write machine-readable session artifacts to a run directory.

#### Scenario: Probe writes session, worker, consumer, and summary outputs
- **WHEN** a probe run finishes
- **THEN** the output directory contains structured session metadata
- **AND** worker-side and consumer-side event logs are persisted
- **AND** the run includes a summary explaining the most likely stall mode when possible

### Requirement: Local probe optionally integrates packet capture
Packet capture SHALL be optional and SHALL not be required for a successful userspace-only run.

#### Scenario: Probe starts and stops packet capture when enabled
- **WHEN** the operator enables packet capture for a run
- **THEN** the probe starts a packet capture process before transfer begins
- **AND** stops it at the end of the run
- **AND** records the capture artifact path in the session output

#### Scenario: Probe cleans up packet capture on cancellation or failure
- **WHEN** a packet-capture-enabled run is canceled or fails early
- **THEN** the probe still stops the packet capture process
- **AND** records whether a partial capture artifact was produced

#### Scenario: Probe still runs when packet capture is disabled
- **WHEN** the operator runs the probe without packet capture enabled
- **THEN** the userspace transport investigation still completes normally
- **AND** the output directory omits packet-capture artifacts
