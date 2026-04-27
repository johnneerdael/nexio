# Tasks

## 1. Foundation (Phase 1)

- [ ] OpenSpec scaffold validates `--strict`.
- [ ] `TraceMode` enum + `TraceModeProvider` interface.
- [ ] `TraceSession` + `TraceEventEnvelope<T>`.
- [ ] `RuntimeTraceSink` interface + `NoopRuntimeTraceSink` object.
- [ ] `TraceHash` HMAC-SHA256 helper (12-char truncation).
- [ ] `TraceRedactor` for URLs, headers, JSON bodies.
- [ ] `JsonlTraceWriter` (append-only, size-capped, drop-priority).
- [ ] `FileRuntimeTraceSink` wires writer + redactor + priority-by-eventType.
- [ ] `TraceSettingsDataStore` persists `TraceMode`.
- [ ] `TraceSessionManager` lifecycle (start/stop/clear; OFF → Noop).

## 2. Runtime hooks (Phase 2)

- [ ] `RuntimeTraceContext` + `RuntimeTraceContextElement` (CoroutineContext element).
- [ ] Emit `runtime.operation_start` / `_finish` / `_failed` from `DefaultIntegrationRuntime`.
- [ ] Emit `runtime.cache_decision` alongside existing `IntegrationAuditPhase` cache events; add `TraceCacheDecision` enum.
- [ ] `UnscopedNetworkPolicyGuard` emits `policy.unscoped_network_call` and throws in internal builds.

## 3. Network instrumentation (Phase 3)

- [ ] `RuntimeTraceInterceptor` emits sanitized `http.request` / `http.response`.
- [ ] HTTP body sampling under `INCLUDE_HTTP_BODIES_INTERNAL_ONLY` only; release builds reject body capture.
- [ ] `RuntimeTraceEventListener` emits `http.timing` events for connect/secure-connect/response phases.
- [ ] Hilt module wires interceptor + listener factory into every `@Named` `OkHttpClient`.
- [ ] `TracedTransport` helper for non-OkHttp call sites.

## 4. Metadata events (Phase 4)

- [ ] `metadata.first_paint` from `MetadataRequestNormalizer`.
- [ ] `metadata.route_decision` from `MetadataRouter`.
- [ ] `metadata.identity_resolution` from identity resolvers.
- [ ] `metadata.provider_plan` from `ProviderPlanRunner`.
- [ ] `metadata.localization_plan` from `LocalizationResolver`.
- [ ] `metadata.resolver_schedule` from `ResolverOrchestrator`.
- [ ] `metadata.field_selected` from `FieldResolver`.
- [ ] `profile.boundary_check` from `ProfileBoundaryEnforcer`.
- [ ] `continue_watching.snapshot_write` / `_read` from `ContinueWatchingSnapshotService`.

## 5. Validator + Export (Phase 5)

- [ ] `TraceValidationReport` types (`TraceVerdict`, `TraceValidationFailure`, `TraceValidationWarning`).
- [ ] `TraceValidationRules` — 14 fail rules from spec §11.
- [ ] `RuntimeTraceValidator` runs rules, builds report with counters.
- [ ] `TraceSummaryGenerator` emits `trace-summary.json` + `trace-summary.md`.
- [ ] `TraceBundleExporter` ZIP with all required entries + `redaction-manifest.json`.
- [ ] `:app:generateTraceValidatorAudit` Gradle task wraps the golden test.

## 6. Settings + Live UI (Phase 6)

- [ ] `RuntimeTraceSettingsViewModel` (state + commands).
- [ ] `RuntimeTraceSettingsScreen` detail Compose (mode picker, start/stop/clear/export, status row).
- [ ] Surface `troubleshooting_runtime_trace` row inside `PlaybackSettingsSections.kt`'s existing Troubleshooting subsection (next to the data-collection items).
- [ ] `RuntimeTraceLiveStatusViewModel` + `RuntimeTraceLiveStatusScreen` backed by `FileRuntimeTraceSink`'s ring buffer of recent events.
- [ ] Manual QA playbook at `docs/qa/runtime-trace-playbook.md` covering the six flows from spec §15.

## 7. Sign-off (Phase 7)

- [ ] `TraceBundleGoldenTest` end-to-end.
- [ ] Run all trace + audit gradle tasks; confirm PASS / 0 violations / clean worktree.
- [ ] Validate OpenSpec `--strict`; commit + push.
