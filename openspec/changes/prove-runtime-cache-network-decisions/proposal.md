## Why

On-device debugging needs a measurable answer to one question: when cached metadata is still fresh, did Nexio avoid provider network calls?

The current runtime already emits `runtime.cache_decision`, `http.request`, and `http.response` events, but the operational proof is incomplete:

- Logcat hides critical cache-proof fields such as `runtimeOperationId`, `networkSuppressed`, `ttlMs`, and `staleWindowMs`.
- File trace sessions can drop `DefaultIntegrationRuntime` events in production because runtime contexts can be stamped with `traceSessionId = "noop"` when the active sink is the composite production sink.
- The validator has a fresh-cache/no-network rule, but the exported summary does not make cache-proof results easy to read per operation/cache key.
- Coil image requests are outside `IntegrationRuntime`, so provider metadata proof and image proof must be reported separately.

## What Changes

### MODIFIED

- `RuntimeTraceSink` exposes the active trace session id so composite sinks can propagate it to `DefaultIntegrationRuntime`.
- `DefaultIntegrationRuntime` uses `traceSink.activeTraceSessionId()` for runtime contexts instead of casting only to `FileRuntimeTraceSink`.
- `LogcatRuntimeTraceSink` prints cache-proof fields for `runtime.cache_decision`: `runtimeOperationId`, `apiShapeId`, `reason`, `networkSuppressed`, `ttlMs`, and `staleWindowMs`.
- `RuntimeTraceValidator` and `TraceSummaryGenerator` surface per-operation cache proof entries.

### ADDED

- Validation for stale-cache events where `networkSuppressed = true`: no `http.request` may appear for the same `runtimeOperationId`.
- A local trace analysis script that reads `trace-events.jsonl` and reports provider metadata network proof by operation/cache key.
- Debugging documentation for proving provider metadata cache hits vs network calls on rooted/profileable devices.

## Impact

- Affected specs: `integration-runtime`.
- Affected runtime path: trace emission only. Provider fetch, cache, router, hydration, and field resolution behavior are not changed.
- Logcat becomes sufficient for quick inspection; file traces become sufficient for audit-grade proof.
- Image cache proof remains separate from provider metadata proof because Coil image fetches are not `IntegrationRuntime` operations.
