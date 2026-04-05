## ADDED Requirements

### Requirement: Completed provider benchmark results can be exported for support analysis
The system SHALL support self-hosted collection of completed provider benchmark results, including
device capability analysis, through authenticated collector APIs.

#### Scenario: Completed provider benchmark result is uploaded to the collector
- **WHEN** a provider benchmark completes successfully in Nexio and benchmark collection is enabled
- **THEN** Nexio uploads the completed benchmark result to the self-hosted collector using the write
  token
- **AND** the uploaded payload includes the benchmark analysis plus detected device capabilities and
  evidence

#### Scenario: Config benchmark result is not uploaded to the collector
- **WHEN** a debrid config benchmark completes
- **THEN** Nexio does not upload that result through the benchmark collector endpoint

#### Scenario: Stored benchmark records can be exported with the read token
- **WHEN** an authenticated support workflow queries the benchmark collector export endpoint
- **THEN** the collector returns stored benchmark records using the read token
- **AND** each record includes the full benchmark result payload plus device capability data needed
  for analysis

### Requirement: Debrid settings separate benchmark collection consent from shadow autoplay consent
The Debrid integration settings surface SHALL expose a dedicated benchmark collection toggle that is
independent from the shadow autoplay data collection toggle.

#### Scenario: Benchmark collection toggle persists independently
- **WHEN** the user enables or disables benchmark collection
- **THEN** the setting is stored independently from shadow autoplay data collection
- **AND** changing one toggle does not implicitly change the other
