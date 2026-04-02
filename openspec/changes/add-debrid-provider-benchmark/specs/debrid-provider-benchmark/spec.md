## ADDED Requirements

### Requirement: Debrid settings can start manual provider benchmarks
The Debrid integration settings surface SHALL expose a manual benchmark action for connected
Real-Debrid and Premiumize providers.

#### Scenario: Connected provider entry exposes benchmark action
- **WHEN** the user opens the Debrid integration settings screen and Real-Debrid or Premiumize is connected
- **THEN** the corresponding provider entry surfaces a manual benchmark action on the Debrid settings screen
- **AND** the action starts provider-specific benchmark collection without navigating away from the screen

#### Scenario: Disconnected provider entry does not start a benchmark
- **WHEN** the provider is not connected
- **THEN** the provider entry does not allow benchmark execution
- **AND** the UI keeps the existing provider connection affordance instead

### Requirement: Manual benchmark uses a real provider library item
The benchmark runtime SHALL resolve a real playable provider library item before measuring
provider performance.

#### Scenario: Benchmark resolves a playable Real-Debrid or Premiumize item
- **WHEN** the user starts a benchmark for a connected provider with at least one playable library item
- **THEN** the runtime resolves a direct playback candidate from that provider's library integration path
- **AND** the benchmark streams bytes from that resolved direct link

#### Scenario: Benchmark fails when no playable provider item exists
- **WHEN** the user starts a benchmark for a connected provider that has no suitable playable library item
- **THEN** the benchmark does not start the measurement phase
- **AND** the runtime reports a `no_playable_library_item` failure reason

### Requirement: Benchmark captures sustained direct-link performance
The benchmark runtime SHALL measure startup and sustained direct-link performance by reading bytes
into a discard sink.

#### Scenario: Benchmark completes after both minimum thresholds are satisfied
- **WHEN** a benchmark run reaches at least `500 MB` transferred and at least `120s` elapsed
- **THEN** the runtime marks the run as completed
- **AND** the result includes startup and sustained-throughput summary metrics

#### Scenario: Benchmark continues if bytes threshold is met before sustained window
- **WHEN** the benchmark has transferred at least `500 MB` but less than `120s` has elapsed
- **THEN** the runtime continues measuring
- **AND** it does not mark the run completed until the sustained window is also satisfied

#### Scenario: Benchmark can be cancelled or time out before completion
- **WHEN** the user cancels the benchmark or the safety timeout is reached before successful completion
- **THEN** the runtime terminates the run with an explicit non-success termination reason
- **AND** the run is not stored as a completed benchmark result

### Requirement: Only one benchmark runs at a time
The benchmark subsystem SHALL enforce a single active benchmark across providers.

#### Scenario: Second provider benchmark is blocked during an active run
- **WHEN** a benchmark is already active for one provider
- **THEN** the system does not start another provider benchmark concurrently
- **AND** the UI keeps the inactive provider rows in a non-running state

#### Scenario: Active benchmark stops when the app leaves the foreground
- **WHEN** the app moves to the background during an active benchmark
- **THEN** the runtime cancels the active benchmark
- **AND** the user is not left with a silently running background measurement

### Requirement: Latest benchmark result is stored locally per provider
The system SHALL persist only the latest local benchmark result for each supported provider.

#### Scenario: Completed provider result overwrites the previous provider result
- **WHEN** a completed benchmark finishes for Real-Debrid or Premiumize
- **THEN** the runtime stores that provider's latest result locally
- **AND** any previous latest result for the same provider is replaced

#### Scenario: Provider benchmark controls surface the latest stored result
- **WHEN** the Debrid settings screen is opened after a prior successful benchmark
- **THEN** the corresponding provider benchmark control surfaces the latest stored benchmark summary
- **AND** the summary is available without rerunning the benchmark

### Requirement: Benchmark transport stays isolated from playback optimizations
The phase-1 benchmark transport SHALL not reuse playback caches or warm-ahead behavior.

#### Scenario: Benchmark uses dedicated transport behavior
- **WHEN** a benchmark run is started
- **THEN** the benchmark transport reads the provider stream through a dedicated measurement path
- **AND** it does not rely on playback cache, warm-ahead, or stream-link reuse behavior for the measurement
