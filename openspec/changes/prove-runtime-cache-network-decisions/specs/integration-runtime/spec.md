## ADDED Requirements

### Requirement: Runtime trace sessions use the active trace session id

When a runtime trace session is active, every event emitted by `DefaultIntegrationRuntime` MUST carry the active session id, including `runtime.operation_start`, `runtime.cache_decision`, `runtime.operation_finish`, `http.request`, and `http.response`.

#### Scenario: Active file trace records runtime cache decisions

- **GIVEN** a `TraceSessionManager` has started a trace session
- **AND** the runtime sink is the production composite sink
- **WHEN** a `CacheFirst` runtime operation reads from a fresh cache entry
- **THEN** the session `trace-events.jsonl` contains a `runtime.cache_decision` event
- **AND** the event `traceSessionId` equals the active session id
- **AND** the event payload `decision` is `HIT`
- **AND** the event payload `networkSuppressed` is `true`

### Requirement: Cache decision logcat exposes network-proof fields

`Nexio.IntRuntime` logcat lines for `runtime.cache_decision` MUST include enough fields to prove cache behavior without reading the JSONL bundle.

#### Scenario: Fresh cache hit logcat proves provider network suppression

- **WHEN** a `runtime.cache_decision` event has `decision = "HIT"`
- **THEN** the logcat line includes `runtimeOperationId`
- **AND** the logcat line includes `apiShapeId`
- **AND** the logcat line includes `cacheKey`
- **AND** the logcat line includes `reason=fresh-cache-hit`
- **AND** the logcat line includes `networkSuppressed=true`
- **AND** the logcat line includes `ttlMs`
- **AND** the logcat line includes `staleWindowMs`

### Requirement: Fresh and suppressed stale cache hits cannot emit HTTP requests

For a single `runtimeOperationId`, a cache decision that suppresses network MUST NOT be followed by any `http.request`.

#### Scenario: Validator fails when fresh cache hit still performs HTTP

- **GIVEN** a `runtime.cache_decision` event with `decision = "HIT"`, `networkSuppressed = true`, and `runtimeOperationId = "op_1"`
- **WHEN** the same trace contains an `http.request` event with `runtimeOperationId = "op_1"`
- **THEN** `RuntimeTraceValidator` returns `FAIL`
- **AND** one failure uses rule id `FreshCacheHitSuppressesNetwork`

#### Scenario: Validator fails when suppressed stale cache hit still performs HTTP

- **GIVEN** a `runtime.cache_decision` event with `decision = "STALE_HIT"`, `networkSuppressed = true`, and `runtimeOperationId = "op_2"`
- **WHEN** the same trace contains an `http.request` event with `runtimeOperationId = "op_2"`
- **THEN** `RuntimeTraceValidator` returns `FAIL`
- **AND** one failure uses rule id `SuppressedStaleCacheHitSuppressesNetwork`

### Requirement: Trace summary reports cache proof per operation

The exported trace summary MUST group runtime operations by `runtimeOperationId` and report provider, api shape, operation key, cache key, cache decision, network-suppressed flag, and whether an HTTP request occurred.

#### Scenario: Kitsu second-open trace has zero provider metadata network calls for fresh entries

- **GIVEN** a second-open trace for `kitsu:12`
- **WHEN** Kitsu metadata entries are still inside their TTL
- **THEN** each Kitsu metadata operation summary shows `decision = "HIT"`
- **AND** each summary shows `networkSuppressed = true`
- **AND** each summary shows `httpRequestCount = 0`
- **AND** no Kitsu metadata operation summary shows `decision = "MISS_THEN_NETWORK"`

### Requirement: Provider metadata cache proof and image cache proof are reported separately

`IntegrationRuntime` cache proof MUST be scoped to provider metadata, identity, rail, and integration operations. Coil image fetches MUST NOT be represented as provider metadata cache hits unless they are routed through an integration poster fetcher.

#### Scenario: Image load network does not falsify provider metadata cache proof

- **GIVEN** Kitsu metadata runtime operations are all fresh cache hits
- **AND** a Coil image request occurs for a poster URL
- **WHEN** cache proof is summarized
- **THEN** provider metadata proof still reports zero provider metadata network calls
- **AND** the report separately states that image cache proof requires Coil instrumentation
