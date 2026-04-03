## MODIFIED Requirements

### Requirement: Debrid settings can start manual provider benchmarks
The Debrid integration settings surface SHALL expose manual benchmark actions for connected
Real-Debrid and Premiumize providers and SHALL present the latest completed benchmark results with
re-openable detailed result views.

#### Scenario: Settings exposes both transport-comparison and configuration benchmarks
- **WHEN** a supported provider row is connected on the Debrid settings screen
- **THEN** the user can start the existing transport-comparison benchmark
- **AND** the user can start a second configuration benchmark dedicated to optimized transport
  profile comparison

#### Scenario: Only one Debrid benchmark can run at a time
- **WHEN** any Debrid benchmark is already running
- **THEN** the UI blocks starting the other benchmark type
- **AND** the active benchmark remains cancellable

## ADDED Requirements

### Requirement: Configuration benchmark runs a fixed optimized transport matrix
The system SHALL provide a Debrid configuration benchmark that runs a fixed matrix of optimized
transport profiles against one resolved provider file and URL.

#### Scenario: Session reuses one candidate for every matrix profile
- **WHEN** a configuration benchmark session starts successfully
- **THEN** the runtime resolves one provider candidate file and direct URL for that session
- **AND** every runnable profile in the matrix uses that same candidate

#### Scenario: URL expiry fails clearly instead of switching files
- **WHEN** the resolved provider URL expires or becomes unusable mid-session
- **THEN** the session reports a clear failure for the affected run or session
- **AND** the runtime does not silently re-resolve a different provider file to continue the matrix

#### Scenario: Session covers the approved nine-profile matrix
- **WHEN** a configuration benchmark session runs
- **THEN** it evaluates 2, 3, and 4 parallel downloads at 8 MB, 16 MB, and 24 MB chunk sizes
- **AND** the resulting session stores an outcome for each of the nine profiles

### Requirement: Configuration benchmark reports success, failure, and unsupported profile states
Each configuration benchmark profile SHALL end in a user-visible success, failure, or unsupported
state.

#### Scenario: Memory-unsafe profiles are marked unsupported without execution
- **WHEN** a matrix profile exceeds the benchmark's safe memory budget for the current device
- **THEN** that profile is marked `Unsupported`
- **AND** the runtime skips network execution for that profile

#### Scenario: Successful profiles store their sustained average throughput
- **WHEN** a matrix profile completes successfully
- **THEN** the stored result includes its 30-second sustained average throughput
- **AND** successful profiles can be ranked by that value

### Requirement: Configuration benchmark ranks successful profiles by average throughput
The configuration benchmark SHALL identify the best profile using average throughput among
successful runs only.

#### Scenario: Best profile ignores failed and unsupported rows
- **WHEN** the session contains a mix of successful, failed, and unsupported profile results
- **THEN** the best profile is selected from successful rows only
- **AND** failed or unsupported rows do not outrank successful rows

#### Scenario: Session with no successful profiles has no best profile
- **WHEN** every profile in the session ends failed or unsupported
- **THEN** the persisted result contains no best profile
- **AND** the UI presents a no-success summary instead of a best-profile banner

### Requirement: Configuration benchmark results are grouped for TV readability
Successful configuration benchmark sessions SHALL present grouped results by chunk size with compact
parallelism subrows.

#### Scenario: Completion UI shows grouped profile results and best summary
- **WHEN** a configuration benchmark session completes
- **THEN** the UI shows a best-profile summary at the top
- **AND** the UI groups results into 8 MB, 16 MB, and 24 MB sections
- **AND** each section shows 2x, 3x, and 4x profile rows with success, failure, or unsupported
  labels
