## ADDED Requirements

### Requirement: Main behavior ports must use shared IntegrationRuntime operations

Every provider-facing behavior ported from `main` MUST execute through the existing IntegrationRuntime operation shape for that provider, with operation key, endpoint shape id, cache policy, header policy, trace context, and runtime cache decision.

#### Scenario: Ported provider metadata call remains runtime-covered

- **GIVEN** a main-only fix requires a TVDB, TMDB, Kitsu, Trakt, Simkl, IMDb, or addon provider request
- **WHEN** the fix is implemented on `codex/integration-runtime-phase-a`
- **THEN** the request is executed through an IntegrationRuntime-backed provider
- **AND** the runtime emits `runtime.operation_start`
- **AND** the runtime emits `runtime.cache_decision`
- **AND** the runtime emits `runtime.operation_finish`
- **AND** no screen or repository performs a raw provider HTTP call outside the registered runtime operation.

### Requirement: Main parity work starts from a classified commit ledger

The port MUST begin with a ledger of `HEAD..main` commits so behavior is ported intentionally rather than through a mechanical merge.

#### Scenario: Main-only commit is classified before implementation

- **GIVEN** a commit exists in `HEAD..main`
- **WHEN** the parity ledger is generated
- **THEN** the commit has a domain
- **AND** the commit has one of `PORT`, `ALREADY_COVERED`, `OBSOLETE`, or `REDESIGN_FOR_SHARED_ARCHITECTURE`
- **AND** ported commits name the target shared component where the behavior will land.

### Requirement: Runtime cache behavior remains measurable after ports

Ported behavior MUST preserve cache-vs-network proof for provider metadata and identity operations.

#### Scenario: Second-open provider metadata path uses fresh cache

- **GIVEN** a provider metadata detail screen was opened once
- **AND** all required provider metadata entries remain inside TTL
- **WHEN** the same detail screen is opened again
- **THEN** runtime cache decisions for the same provider metadata operations are `HIT`
- **AND** `networkSuppressed` is `true`
- **AND** no `http.request` is emitted for the same runtime operation ids.
