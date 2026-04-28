## Why

The architecture audit (`review-dossier/09-known-gaps.md`) identified 11 cluster-B findings against the runtime control plane:

- **F-A-01 (P1):** `DefaultIntegrationRuntime.openInternal` catch branch records FAILED without invoking `noteSyntheticNetworkFailure` — stream errors don't engage backoff.
- **F-D-01 (P1):** HttpError branch of `executeProviderLoad` returns Missing instead of falling back to `cacheStore.readStale(spec)` — UI flickers on rate-limit.
- **F-D-02 (P1):** Cache write is non-atomic (`file.writeBytes` then `cacheDao.upsertCacheEntry` separately) — concurrent reader can see partial bytes.
- **F-TM-02 (P1):** No `SingleFlight*Test` exists — regressions go uncaught.
- **F-A-02 (P2):** Single-flight only on CacheFirst path; mutations / streams / direct calls duplicate.
- **F-D-03 (P2):** `TraceCacheDecision.INVALIDATED` and `EVICTED` are dead enum values.
- **F-D-04 (P2):** `MetadataCacheKeys.localized` is unused.
- **F-D-06 (P2):** `IntegrationBackoffManager` has fixed waits — no exponential, no jitter, no clear-on-success.
- **F-A-03/A-04/D-05 (Nits):** cache_decision events suppressed when sink is Noop; unused `policy` param; single-flight key map can leak typed casts.

This change closes all 11.

## What Changes

### MODIFIED

- `DefaultIntegrationRuntime.openInternal` invokes `noteSyntheticNetworkFailure` in its catch branch (F-A-01).
- `executeProviderLoad` HttpError + NetworkError branches attempt `cacheStore.readStale(spec)` before returning Missing; on hit emit `STALE_HIT` cache decision and `IntegrationFetchResult.Stale(...)` (F-D-01).
- `LocalIntegrationCacheStore.write` writes blob to `${blobPath}.tmp` then atomically renames; rename + DAO upsert wrapped in Room `@Transaction`. `readFresh`/`readStale` tolerate `FileNotFoundException` and short reads (F-D-02).
- `IntegrationSingleFlight` keys use `TypedSingleFlightKey<T>(cacheKey, mimeType)` (F-D-05).
- `IntegrationCallSpec` gains `coalesceConcurrent: Boolean = false` opt-in (F-A-02).
- `IntegrationBackoffManager` schedule grows exponentially with jitter; `consecutiveFailures` counter; `clear(provider, scope)` invoked at success (F-D-06).
- `executeObserveOnly` unused `policy` parameter removed (F-A-04).
- `emitCacheDecision` Noop-sink short-circuits removed (F-A-03).

### REMOVED

- `TraceCacheDecision.INVALIDATED` and `EVICTED` (F-D-03 — option b: no production emission site).
- `MetadataCacheKeys.localized(...)` (F-D-04 — option b: zero callers).

### ADDED

- `SingleFlight*Test` regression suite (F-TM-02 + F-D-05).
- `IntegrationBackoffManagerExponentialTest` (F-D-06).

## Impact

- Affected specs: `integration-runtime` (or whichever capability owns runtime/cache/backoff).
- Affected code: 4 production files + 1-2 enum/helper deletions.
- No new dependencies.
- Behavior changes: stream-open errors register backoff; stale-on-429 eliminates UI flicker; atomic writes eliminate corrupt-decode race; backoff with exponential+jitter spreads retry storms; clear-on-success unblocks providers after one-off failures.
