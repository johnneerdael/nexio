# Tasks

## 1. Foundation (Phase 1)

- [x] OpenSpec scaffold validates `--strict`.
- [x] `TraceMode` enum + `TraceModeProvider` interface.
- [x] `TraceSession` + `TraceEventEnvelope<T>`.
- [x] `RuntimeTraceSink` interface + `NoopRuntimeTraceSink` object.
- [x] `TraceHash` HMAC-SHA256 helper (12-char truncation).
- [x] `TraceRedactor` for URLs, headers, JSON bodies.
- [x] `JsonlTraceWriter` (append-only, size-capped, drop-priority).
- [x] `FileRuntimeTraceSink` wires writer + redactor + priority-by-eventType.
- [x] `TraceSettingsDataStore` persists `TraceMode`.
- [x] `TraceSessionManager` lifecycle (start/stop/clear; OFF → Noop).

## 2. Runtime hooks (Phase 2)

- [x] `RuntimeTraceContext` + `RuntimeTraceContextElement` (CoroutineContext element).
- [x] Emit `runtime.operation_start` / `_finish` / `_failed` from `DefaultIntegrationRuntime`.
- [x] Emit `runtime.cache_decision` alongside existing `IntegrationAuditPhase` cache events; add `TraceCacheDecision` enum.
- [x] `UnscopedNetworkPolicyGuard` emits `policy.unscoped_network_call` and throws in internal builds.

## 3. Network instrumentation (Phase 3)

- [x] `RuntimeTraceInterceptor` emits sanitized `http.request` / `http.response`.
- [x] HTTP body sampling under `INCLUDE_HTTP_BODIES_INTERNAL_ONLY` only; release builds reject body capture.
- [x] `RuntimeTraceEventListener` emits `http.timing` events for connect/secure-connect/response phases.
- [x] Hilt module wires interceptor + listener factory into every `@Named` `OkHttpClient`.
- [x] `TracedTransport` helper for non-OkHttp call sites.

## 4. Metadata events (Phase 4)

- [ ] `metadata.first_paint` from `MetadataRequestNormalizer`. _(DEFERRED — no addon-preview UI path exists yet; helper added but not wired.)_
- [x] `metadata.route_decision` from `MetadataRouter`.
- [x] `metadata.identity_resolution` from identity resolvers.
- [x] `metadata.provider_plan` from `ProviderPlanRunner`.
- [ ] `metadata.localization_plan` from `LocalizationResolver`. _(DEFERRED — TVDB/Kitsu provider-adapter orchestration site not identified.)_
- [x] `metadata.resolver_schedule` from `ResolverOrchestrator`.
- [x] `metadata.field_selected` from `FieldResolver`.
- [x] `profile.boundary_check` from `ProfileBoundaryEnforcer`.
- [x] `continue_watching.snapshot_write` / `_read` from `ContinueWatchingSnapshotService`.

## 5. Validator + Export (Phase 5)

- [x] `TraceValidationReport` types (`TraceVerdict`, `TraceValidationFailure`, `TraceValidationWarning`).
- [x] `TraceValidationRules` — 14 fail rules from spec §11.
- [x] `RuntimeTraceValidator` runs rules, builds report with counters.
- [x] `TraceSummaryGenerator` emits `trace-summary.json` + `trace-summary.md`.
- [x] `TraceBundleExporter` ZIP with all required entries + `redaction-manifest.json`.
- [x] `:app:generateTraceValidatorAudit` Gradle task wraps the golden test.

## 6. Settings + Live UI (Phase 6)

- [x] `RuntimeTraceSettingsViewModel` (state + commands).
- [x] `RuntimeTraceSettingsScreen` detail Compose (mode picker, start/stop/clear/export, status row).
- [x] Surface `troubleshooting_runtime_trace` row inside `PlaybackSettingsSections.kt`'s existing Troubleshooting subsection (next to the data-collection items).
- [x] `RuntimeTraceLiveStatusViewModel` + `RuntimeTraceLiveStatusScreen` backed by `FileRuntimeTraceSink`'s ring buffer of recent events.
- [ ] Navigation route from Troubleshooting row to detail screen, and from detail screen to Live status. _(DEFERRED — wire-up TODOs remain in `PlaybackSettingsScreen.kt` and `RuntimeTraceSettingsScreen.kt`; Clear/Export buttons also pending.)_
- [x] Manual QA playbook at `docs/qa/runtime-trace-playbook.md` covering the six flows from spec §15.

## 7. Sign-off (Phase 7)

- [x] `TraceBundleGoldenTest` end-to-end.
- [x] Run all trace + audit gradle tasks; confirm PASS / 0 violations / clean worktree.
- [x] Validate OpenSpec `--strict`; commit + push.
