# Lane A — IntegrationRuntime Control Plane

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Phase:** 5
- **Owner task:** Task 25
- **Files inspected:** 12 (`DefaultIntegrationRuntime.kt`, `IntegrationScope.kt`, `IntegrationCallSpec.kt`, `IntegrationStreamSpec.kt`, `IntegrationSpec.kt`, `IntegrationCacheStore.kt`, `LocalIntegrationCacheStore.kt`, `IntegrationBackoffManager.kt`, `ProviderRequestGate.kt`, `IntegrationSingleFlight.kt`, `IntegrationPolicyRegistry.kt`, `IntegrationCachePolicy.kt`, `IntegrationHeaderPolicies.kt`)

## What changed (per diff map)

The `core/integration` package is a 40-file new addition: `IntegrationRuntime` interface plus `DefaultIntegrationRuntime` orchestrate the fetch/call/open pipeline, with single-flight, provider semaphore, backoff DAO, and audit sink injected. `ProfileBoundaryEnforcer.validateRequest` is now called from each spec's `init` block to fail-fast at construction time. The runtime is wired to `RuntimeTraceSink` (Noop or File) and emits `runtime.operation_start/finish/failed` and `runtime.cache_decision` envelopes through `RuntimeTraceContextElement` so HTTP interceptors can correlate.

## Contract verdicts

| Contract | Verdict | Evidence |
|---|---|---|
| Spec construction calls `validateRequest` | ✅ | `IntegrationSpec.kt:24`, `IntegrationCallSpec.kt:17`, `IntegrationStreamSpec.kt:17` |
| `runtime.cache_decision` emitted at each policy branch | ✅ | `DefaultIntegrationRuntime.kt:176` (Disabled), `:184` (ObserveOnly), `:192` (Mutation), `:435/485/501/516/530/547/560` (CacheFirst) |
| 429/5xx registers backoff | ⚠️ | `DefaultIntegrationRuntime.kt:284-291` (callInternal) and `:617-624` (executeProviderLoad) cover HTTP errors; `openInternal` (`:385-388`) catches network exceptions without calling `noteSyntheticNetworkFailure` — see F-A-01 |
| Single-flight joins concurrent ops | ⚠️ | `IntegrationSingleFlight.kt:19-50` correct; only invoked in `executeCacheFirst` (`DefaultIntegrationRuntime.kt:482`). No coverage for `call`, `open`, `executeWithoutCache`, `executeMutation`, or `executeProviderLoad` outside CacheFirst — see F-A-02 + F-TM-02 |
| Lane concurrency via `requestGate.withPermit` | ✅ | `DefaultIntegrationRuntime.kt:263` (callInternal), `:379` (openInternal), `:592` (executeProviderLoad) |
| Audit sink invoked at each phase | ✅ | `record(...)` invoked at REQUEST_RECEIVED, PLAYBACK_BLOCKED, BACKOFF_BLOCKED, LANE_QUEUED, NETWORK_START, NETWORK_END, FAILED, MISSING, FRESH_CACHE_HIT, STALE_CACHE_HIT, CACHE_WRITE — see `DefaultIntegrationRuntime.kt:169,254,258,262,264,278,283-313,381-388,399,420,434,452,466,481,484,498,500,529,546,559,583,587,591,593,607,613,626,637,690-787` |

## Findings

### F-A-01: Stream open() failures do not register backoff

- **Severity:** P1
- **Evidence:** `app/src/main/java/com/nexio/tv/core/integration/DefaultIntegrationRuntime.kt:385-389`
- **Violated contract:** 429 / 5xx (and synthetic network failure) registers backoff
- **User-visible impact:** When a streaming endpoint (e.g. SSE / chunked transport) keeps failing, the provider is never gated by `IntegrationBackoffManager`, so subsequent open attempts retry immediately and may also bypass the rate-limit recovery applied to peer `get` / `call` operations on the same provider+scope. This produces request hammering on a degraded upstream.
- **Required fix:** In the `catch` branch of `openInternal` invoke `noteSyntheticNetworkFailure(provider = spec.provider, scope = spec.scope, retryAfterMs = null, reason = exception.message)` before recording `FAILED`, mirroring `executeProviderLoad` and `callInternal`.
- **Test or report that should catch it:** New unit test in `DefaultIntegrationRuntimeStreamBackoffTest` asserting `backoffManager.isBlocked` becomes true after a stream open exception; runtime audit report should include a `BACKOFF_REGISTERED` row tied to a stream phase.

### F-A-02: Single-flight only applied to CacheFirst path

- **Severity:** P2
- **Evidence:** `app/src/main/java/com/nexio/tv/core/integration/DefaultIntegrationRuntime.kt:482` (sole caller of `singleFlight.run`); `executeWithoutCache` (`:393`), `executeMutation` (`:414`), `callInternal` (`:246`), `openInternal` (`:362`) and `executeProviderLoad` (`:577`) lack any coalescing.
- **Violated contract:** Single-flight join across concurrent identical operations.
- **User-visible impact:** Concurrent identical `IntegrationCallSpec` / `IntegrationStreamSpec` / `IntegrationSpec(Disabled|ObserveOnly|Mutation)` invocations with the same operation key issue duplicated upstream requests. For ObserveOnly metadata refresh and stream open this is the documented contract; for mutations it can produce double-write outcomes (e.g., duplicate Trakt scrobble POSTs on rapid retry).
- **Required fix:** Either (a) extend `IntegrationSingleFlight` to key on `operationKey + scope.storageKey` for non-cache paths (with explicit opt-out for mutations that must duplicate), or (b) document the contract narrowing in `IntegrationRuntime` KDoc and the audit report so reviewers don't expect global coalescing. Recommend (a) for `Disabled`/`ObserveOnly` and explicit opt-in for `Mutation`/`call`/`open`.
- **Test or report that should catch it:** Companion to F-TM-02 — add a parameterised single-flight regression test exercising each policy branch + `call` + `open`.

### F-A-03: `runtime.cache_decision` events suppressed when sink is Noop

- **Severity:** Nit
- **Evidence:** `app/src/main/java/com/nexio/tv/core/integration/DefaultIntegrationRuntime.kt:66,95` short-circuit emit when `traceSink === NoopRuntimeTraceSink`.
- **Violated contract:** Cache-decision branches emit `runtime.cache_decision`.
- **User-visible impact:** In production builds without trace mode active, cache-decision events are never produced. Downstream tooling (validators, audit reports, CI gates) that asserts on these events must always run with a non-noop sink installed; otherwise a regression that silently flips a branch will not surface in audit-only runs.
- **Required fix:** Document the always-noop fast-path in KDoc on `emitTrace`/`emitCacheDecision` and in the runtime audit, or have audit-only runs install a memory-backed `RuntimeTraceSink` so cache decisions are observable.
- **Test or report that should catch it:** `IntegrationRuntimeAuditArtifactTest` should be parameterised to also run with `traceSink = NoopRuntimeTraceSink` and assert the documented behaviour.

### F-A-04: Unused `policy` parameter in `executeObserveOnly`

- **Severity:** Nit
- **Evidence:** `app/src/main/java/com/nexio/tv/core/integration/DefaultIntegrationRuntime.kt:405-412` accepts `policy: IntegrationCachePolicy.ObserveOnly` and never references it; the method just delegates to `executeWithoutCache`.
- **Violated contract:** None directly; design integrity / dead-code hygiene.
- **User-visible impact:** None today, but the unused parameter implies an `ObserveOnly.reason` was intended to be threaded into the audit/trace payload — the actual cache_decision emit at `:184` does not include `reason`. Future maintainers may either drop the parameter (losing the original intent) or reintroduce the gap unawares.
- **Required fix:** Either add `extra = mapOf("observeReason" to policy.reason)` to the `OBSERVE_ONLY` cache-decision emit at `:184` and propagate `policy.reason` into the audit record, or remove the unused parameter from `executeObserveOnly` and the call site.
- **Test or report that should catch it:** Lint/dead-code rule (Detekt `UnusedParameter`) plus an audit assertion that `ObserveOnly` events carry the reason string.

### F-TM-02: No single-flight regression test (re-stated from test matrix)

- **Severity:** P1
- **Evidence:** `review-dossier/08-test-matrix.md:29,96`. No `SingleFlight*Test` exists under `app/src/test/java/com/nexio/tv/core/integration/`; production `IntegrationSingleFlight` is correct on inspection but uncovered.
- **Violated contract:** Single-flight joins concurrent operations for the same key.
- **User-visible impact:** A regression that drops the `mutex.withLock` block or the deferred completion would not be caught — concurrent fetches on the same cache key would both hit the network, doubling load on rate-limited providers (TMDB, TVDB) and potentially breaching their lane permit allocation.
- **Required fix:** Add a coroutine test that races two `getInternal` calls with the same `cacheKey`, asserts the underlying loader is invoked once, and asserts both callers receive the same `IntegrationFetchResult`.
- **Test or report that should catch it:** New `IntegrationSingleFlightTest` (unit) plus a runtime-level `DefaultIntegrationRuntimeSingleFlightTest` exercising `executeCacheFirst` end-to-end.

## Outcome

CHANGES_REQUESTED — Lane A is broadly correct, but blocking items F-A-01 (stream backoff gap) and F-TM-02 (single-flight regression coverage) plus follow-up F-A-02 (single-flight scope) must be addressed before approval. F-A-03 / F-A-04 are nit-tier hygiene items.
