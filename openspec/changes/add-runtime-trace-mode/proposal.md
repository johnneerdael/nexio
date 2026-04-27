# Change: Add Runtime + Metadata Trace Mode

## Why

CI proves invariants in unit tests, but on-device debugging — especially profile-boundary, cache, and metadata-routing issues that only reproduce on real Android TV hardware — has no structured signal beyond logcat. We need a developer-toggleable trace harness that captures the same events our audit fixtures assert on, in a sanitized exportable bundle.

## What Changes

- Add a `Settings → Playback → Troubleshooting → Runtime & Metadata Trace` entry with a 4-state mode toggle (`OFF`, `SAFE_METADATA_RUNTIME`, `INCLUDE_HTTP_SUMMARY`, `INCLUDE_HTTP_BODIES_INTERNAL_ONLY`) and session controls (start/stop/clear/export/show live).
- Introduce `RuntimeTraceSink` (`Noop` + `File` impls), `TraceSession`, `TraceEventEnvelope<T>`, `JsonlTraceWriter`, `TraceRedactor`, and `RuntimeTraceContext` propagated via `Request.tag()` and `CoroutineContext.Element`.
- Add an OkHttp `Interceptor` + `EventListener` that emit sanitized `http.request` / `http.response` events correlated by `runtimeOperationId`. Detect unscoped HTTP traffic (no runtime context) and emit `policy.unscoped_network_call` violations.
- Instrument the metadata layer (`MetadataRequestNormalizer`, `MetadataRouter`, `IdentityRouter`, `ProviderPlanRunner`, `LocalizationResolver`, `ResolverOrchestrator`, `FieldResolver`), the runtime layer (`DefaultIntegrationRuntime`), and the profile/CW layer (`ProfileBoundaryEnforcer`, `ContinueWatchingSnapshotService`) with the 13 event types in the spec.
- Add a `RuntimeTraceValidator` that produces a `PASS`/`PASS_WITH_WARNINGS`/`FAIL` report and a `TraceBundleExporter` that assembles a sanitized ZIP.

## Impact

- Engineering and QA can capture an on-device session and prove preview/no-network, route-input rules, cache-hit network suppression, profile boundaries, identity resolution, localization fallback, and FieldResolver ownership without rebuilding the app.
- Production cost is ~1 nullable check per runtime/HTTP event when mode is `OFF` (the `NoopRuntimeTraceSink` is a no-op).
- All trace JSONL is written to app-private storage; bundle export uses `FileProvider` for sharing.

## Out Of Scope

- Sending traces off-device automatically.
- Persistent always-on tracing across app restarts (sessions are explicit start/stop).
- Replacing existing `IntegrationAuditSink` — that stays as the in-memory CI audit; the trace sink targets on-disk on-device support bundles.
- Performance profiling beyond timing fields already captured (no Perfetto/Tracing wiring).
