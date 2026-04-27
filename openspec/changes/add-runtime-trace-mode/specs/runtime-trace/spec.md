## ADDED Requirements

### Requirement: Trace mode is off by default and incurs no measurable runtime cost
Production builds with `TraceMode.OFF` SHALL resolve the active runtime sink to `NoopRuntimeTraceSink` and emit zero events.

#### Scenario: Default mode resolves to Noop
- **GIVEN** the app starts with no trace settings persisted
- **WHEN** `TraceSessionManager.activeSink()` is queried
- **THEN** the returned sink is `NoopRuntimeTraceSink`
- **AND** zero `TraceEventEnvelope`s are written to disk

#### Scenario: Toggling OFF stops emission
- **GIVEN** a trace session is active under `SAFE_METADATA_RUNTIME`
- **WHEN** `TraceSessionManager.stop()` is invoked
- **THEN** `activeSink()` returns `NoopRuntimeTraceSink`
- **AND** the trace JSONL file is closed and remains untouched by subsequent runtime calls

### Requirement: HTTP body capture is gated to internal/debug builds
The trace UI SHALL refuse to select `TraceMode.INCLUDE_HTTP_BODIES_INTERNAL_ONLY` on release builds, and the interceptor SHALL refuse to capture bodies when the build is not internal.

#### Scenario: Release build rejects body-capture mode selection
- **GIVEN** `BuildConfig.DEBUG` is false and the build is not flagged internal
- **WHEN** the user attempts to select `INCLUDE_HTTP_BODIES_INTERNAL_ONLY`
- **THEN** the selection is rejected
- **AND** the previously selected mode remains active

#### Scenario: Internal build allows body capture under size cap
- **GIVEN** internal build with mode `INCLUDE_HTTP_BODIES_INTERNAL_ONLY`
- **WHEN** an HTTP response of textual content type within the 64 KB cap is observed
- **THEN** a `trace.body_sample` event is emitted with the redacted body sample

### Requirement: Every HTTP event correlates to a runtime operation
Every emitted `http.request` / `http.response` envelope SHALL include the `runtimeOperationId` of the originating `IntegrationRuntime` operation. HTTP traffic without a `RuntimeTraceContext` SHALL emit `policy.unscoped_network_call`.

#### Scenario: HTTP request carries runtimeOperationId
- **GIVEN** an `IntegrationRuntime` operation in flight with `runtimeOperationId = "op_42"`
- **WHEN** the OkHttp client issues the underlying network request
- **THEN** the emitted `http.request` envelope's payload contains `"runtimeOperationId": "op_42"`

#### Scenario: Unscoped HTTP request emits policy violation
- **GIVEN** an OkHttp client makes a request whose `Request.tag(RuntimeTraceContext::class.java)` is null
- **WHEN** the interceptor inspects the request
- **THEN** a `policy.unscoped_network_call` envelope is emitted
- **AND** in internal builds, an exception is thrown so the source is debuggable

### Requirement: Trace bundle redaction is verifiable
Every exported `nexio-trace-{ts}.zip` SHALL include a `redaction-manifest.json` listing the redaction keys applied, and SHALL contain no raw `Authorization` headers, raw OAuth tokens, or raw API keys.

#### Scenario: Bundle excludes raw Authorization values
- **GIVEN** a session captured an HTTP request with header `Authorization: Bearer SECRET`
- **WHEN** the bundle is exported
- **THEN** scanning the bundle bytes for `SECRET` returns no hits
- **AND** the corresponding `http.request` envelope shows `"Authorization": "<redacted>"`

#### Scenario: Bundle excludes API keys from URL queries
- **GIVEN** a captured request URL `…?api_key=ABC123&language=en`
- **WHEN** the bundle is exported
- **THEN** the URL stored in the envelope is `…?api_key=<redacted>&language=en`
- **AND** scanning the bundle bytes for `ABC123` returns no hits

### Requirement: Validator produces PASS / PASS_WITH_WARNINGS / FAIL
`RuntimeTraceValidator` SHALL evaluate every rule in `TraceValidationRules.ALL` against the captured event sequence and emit a `TraceVerdict`.

#### Scenario: Clean session passes
- **GIVEN** a session containing only spec-compliant events
- **WHEN** the validator runs
- **THEN** the verdict is `PASS` and no failures are reported

#### Scenario: Synthetic violation fails the verdict
- **GIVEN** a session containing a `metadata.first_paint` event with `routerExecuted=true`
- **WHEN** the validator runs
- **THEN** the verdict is `FAIL`
- **AND** the failure references the rule id `PreviewMustNotRouteOrNetwork`

### Requirement: Profile and credential identifiers are hashed before emission
Trace events SHALL identify profiles and credentials via HMAC-SHA256(salt, value) truncated to 12 hex characters; raw `profileId` integer values and raw `credentialHash` source bytes SHALL NOT appear in any envelope.

#### Scenario: Profile-bound event carries profileHash, not profileId
- **GIVEN** a runtime operation under `Profile(profileId = 2)`
- **WHEN** the operation_start envelope is emitted
- **THEN** the payload contains `profileHash` (12 hex chars derived via HMAC with the per-session salt)
- **AND** the payload does not contain the integer `2` as a profile identifier
