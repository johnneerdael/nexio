## ADDED Requirements

### Requirement: Warm process resumes can skip startup splash
When the Nexio process is still alive and critical bootstrap state is already ready, foreground re-entry SHALL not replay the startup splash.

#### Scenario: Warm launcher return skips splash
- **GIVEN** the Nexio process is still alive
- **AND** critical startup state is already ready
- **WHEN** the user returns from Home or relaunches the app from the launcher
- **THEN** Nexio skips the startup splash
- **AND** it resumes directly into the app UI

#### Scenario: Warm activity recreation skips splash when state is ready
- **GIVEN** the Nexio process is still alive
- **AND** the activity is recreated
- **AND** critical startup state is already ready
- **WHEN** the recreated activity enters foreground
- **THEN** Nexio skips the startup splash

### Requirement: Warm resumes keep splash only when critical state is not ready
Warm process resumes SHALL keep the startup splash only when required bootstrap state is not yet ready.

#### Scenario: Warm resume keeps splash while critical state is missing
- **GIVEN** the Nexio process is still alive
- **AND** critical startup state is not yet ready
- **WHEN** the app re-enters foreground
- **THEN** Nexio may show the startup splash
- **AND** it uses that path only until the required state becomes ready

### Requirement: Startup-only deferred work does not replay on warm resumes
Startup-only deferred work SHALL run once per process cold start and SHALL not replay on warm process resumes.

#### Scenario: Warm foreground return skips deferred startup work
- **GIVEN** startup-only deferred work already completed during the current process lifetime
- **WHEN** the app re-enters foreground
- **THEN** Nexio does not rerun that deferred startup work

### Requirement: Launch observability distinguishes cold and warm behavior
The system SHALL emit logs that distinguish cold boots from warm resumes and explain why splash was shown or skipped.

#### Scenario: Launch logging records disposition
- **WHEN** Nexio enters foreground through a launch or resume path
- **THEN** logs identify whether the event was a cold process start, warm resume with splash skipped, or warm resume with splash kept because readiness was incomplete
