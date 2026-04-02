## MODIFIED Requirements

### Requirement: Debrid settings can start manual provider benchmarks
The Debrid integration settings surface SHALL expose a manual benchmark action for connected
Real-Debrid and Premiumize providers and SHALL present the latest completed benchmark result with a
re-openable detailed result view.

#### Scenario: Completed provider benchmark can be reopened from settings
- **WHEN** the Debrid settings screen is opened after a prior successful provider benchmark
- **THEN** the corresponding provider section surfaces an affordance to view the latest benchmark
  result
- **AND** selecting that affordance opens the stored detailed benchmark result without rerunning the
  benchmark

### Requirement: Benchmark captures sustained direct-link performance
The benchmark runtime SHALL measure startup, sustained transfer, and seek behavior for both the raw
provider path and the Nexio optimized transport path by reading bytes into a discard sink.

#### Scenario: Benchmark session measures both direct and optimized transports
- **WHEN** a provider benchmark starts successfully
- **THEN** the benchmark session measures a `Direct` transport profile and a `Nexio Optimized`
  transport profile against the same resolved provider file
- **AND** the resulting session stores both transport profiles as one latest provider result

#### Scenario: Benchmark session captures seek latency distributions
- **WHEN** a provider benchmark completes successfully
- **THEN** the result includes seek latency summaries for each transport profile
- **AND** those summaries include `p50`, `p95`, and `p99` seek TTFB values plus seek failure rate

## ADDED Requirements

### Requirement: Benchmark results compare direct and optimized transports side-by-side
Successful provider benchmarks SHALL present direct and optimized transport results side-by-side in
the completion UI.

#### Scenario: Completion modal opens with direct and optimized columns
- **WHEN** a provider benchmark completes successfully
- **THEN** the UI opens a benchmark results modal automatically
- **AND** the modal shows separate `Direct` and `Nexio Optimized` result columns immediately
- **AND** both columns reference the same benchmark session and resolved provider file

#### Scenario: Optimized result shows the transport config snapshot used for measurement
- **WHEN** the benchmark results modal shows the optimized transport profile
- **THEN** the optimized result includes the parallel transport configuration used during that
  benchmark session
- **AND** the user can see which connection-count and chunk-size settings produced the result

### Requirement: Benchmark session persists startup, sustained, and seek summaries per transport
The system SHALL persist latest-result benchmark sessions per provider with separate startup,
sustained, and seek summaries for direct and optimized transports.

#### Scenario: Stored benchmark result includes sustained percentile and stability metrics
- **WHEN** a provider benchmark completes successfully
- **THEN** the latest stored provider result includes sustained throughput summaries for each
  transport profile
- **AND** those summaries include average throughput, `p10` throughput, peak throughput, variance
  or standard deviation, and stability signals such as stall count or read-gap tracking

#### Scenario: Stored benchmark result includes shared candidate metadata
- **WHEN** a provider benchmark completes successfully
- **THEN** the latest stored provider result includes benchmark-session metadata shared by both
  transports
- **AND** that metadata includes the provider, measured-at time, resolved host, and file identity

### Requirement: Benchmark uses the current Nexio optimized transport configuration
The optimized benchmark transport SHALL measure the current Nexio parallel-download path using the
active transport configuration at benchmark start.

#### Scenario: Optimized benchmark snapshots parallel transport settings at session start
- **WHEN** a provider benchmark starts
- **THEN** the runtime snapshots the current Nexio optimized transport settings before measurement
- **AND** the optimized benchmark profile uses that frozen snapshot for the full benchmark session

#### Scenario: Optimized benchmark stays isolated from playback cache behavior
- **WHEN** the optimized benchmark transport runs
- **THEN** it uses a benchmark-only transport harness based on Nexio’s optimized range-fetch path
- **AND** it does not reuse playback cache, warm-ahead, or prior benchmark bootstrap state

### Requirement: Active provider benchmarks do not count as idle time
The app SHALL treat an active provider benchmark as non-idle activity.

#### Scenario: Screensaver does not become eligible during an active benchmark
- **WHEN** a provider benchmark is running
- **THEN** the idle screensaver is not eligible to appear
- **AND** benchmark runtime does not advance the app toward idle presentation

#### Scenario: Idle timer resets when benchmark state changes
- **WHEN** a provider benchmark starts or ends
- **THEN** the app registers an idle interaction boundary for that transition
- **AND** the user is not immediately treated as idle because of time spent benchmarking
