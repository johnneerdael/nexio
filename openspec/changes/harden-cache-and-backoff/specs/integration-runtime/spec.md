## MODIFIED Requirements

### Requirement: Stream open() failures register backoff

`DefaultIntegrationRuntime.openInternal` catch branch MUST invoke `noteSyntheticNetworkFailure(provider, scope, retryAfterMs = null, reason = exception.message)` before recording the FAILED audit phase.

#### Scenario: open() catch branch engages backoff manager

- **WHEN** `spec.open()` throws an exception
- **THEN** `backoffManager.isBlocked(spec.provider, spec.scope)` is `true` for the configured backoff window

### Requirement: HttpError and NetworkError branches fall back to stale cache

`DefaultIntegrationRuntime.executeProviderLoad` HttpError (429, 5xx) and NetworkError branches MUST attempt `cacheStore.readStale(spec)` BEFORE returning Missing. On hit, return `IntegrationFetchResult.Stale(value)` and emit `runtime.cache_decision { decision = "STALE_HIT" }`.

#### Scenario: 429 with stale cache returns Stale not Missing

- **GIVEN** an expired-fresh-but-still-stale cache entry exists
- **WHEN** the provider returns HTTP 429
- **THEN** the result is `IntegrationFetchResult.Stale(cachedValue)`
- **AND** a `runtime.cache_decision` STALE_HIT event fires

### Requirement: Cache writes are atomic against concurrent reads

`LocalIntegrationCacheStore.write` MUST write blob bytes to `${blobPath}.tmp` then atomically rename to `blobPath`. The rename + DAO row upsert MUST execute inside a single Room `@Transaction`.

#### Scenario: Reader during concurrent write sees one value, never partial

- **GIVEN** an existing cached entry for spec X
- **WHEN** writer A invokes `write(specX, valueA)` concurrently with reader B's `readFresh(specX)`
- **THEN** reader B receives EITHER the previous value OR the new value, never a corrupt decode or null

### Requirement: IntegrationBackoffManager schedule is exponential with jitter and clears on success

The block window MUST grow as `min(baseMs × 2^consecutiveFailures, capMs) ± jitter`. Successful loads MUST invoke `backoffManager.clear(provider, scope)` to reset the counter.

#### Scenario: Backoff doubles per consecutive failure up to cap

- **GIVEN** baseMs = 2_000, capMs = 60_000
- **WHEN** the same (provider, scope) fails 3 times in a row
- **THEN** the third block window is approximately 8_000ms (= 2_000 × 2^2)

#### Scenario: Successful load clears backoff counter

- **GIVEN** a (provider, scope) has 3 consecutive failures
- **WHEN** the next load succeeds
- **THEN** `backoffManager.isBlocked(provider, scope)` returns `false`
- **AND** the next failure starts the counter back at 1

## REMOVED Requirements

### Requirement: TraceCacheDecision exposes INVALIDATED and EVICTED values

**Reason:** No production code path emits these decisions.

**Migration:** No callers exist; safe to delete.

### Requirement: MetadataCacheKeys.localized helper exists

**Reason:** Zero production callers.

**Migration:** Verified zero callers via grep. Safe to delete.

## ADDED Requirements

### Requirement: Single-flight is exercised by a regression test

A coroutine race test MUST exist that races two concurrent `executeCacheFirst` invocations on the same `cacheKey` and asserts the loader runs exactly once. A second test MUST cover the type-collision case: same `cacheKey` with different `T` types MUST not produce a `ClassCastException`.

#### Scenario: Two concurrent CacheFirst calls join via single-flight

- **WHEN** two coroutines invoke `runtime.get(specA)` concurrently with the same `cacheKey`
- **THEN** the spec's `load { ... }` lambda is invoked exactly once
- **AND** both callers receive the same `IntegrationFetchResult` value
