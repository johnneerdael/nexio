# On-Device Trace Mode — Design Synthesis

**Review SHA:** `774a540f8`
**Date:** 2026-04-29
**Primary lane:** I — On-Device Trace Mode; cross-cuts: A (OkHttp wiring), B (metadata events), D (cache decision events), E (localization plan), F (boundary check events), G (CW snapshot events), H (scrobble rejection events)

---

## What it does

The on-device trace mode is a diagnostic subsystem that captures a structured event log of the integration runtime's behavior — HTTP calls, cache decisions, metadata routing decisions, field ownership selections, profile boundary checks, scrobble rejections, and localization plans — into an append-only JSONL file on the device's internal storage. The intent is to produce a self-contained bundle (ZIP) that a user or support engineer can export and use to diagnose provider issues, cache behavior, or cross-profile bugs without requiring a debugger or logcat.

The mode is gated by `TraceMode` enum: `OFF`, `SAFE_METADATA_RUNTIME`, `INCLUDE_HTTP_SUMMARY`, and `INCLUDE_HTTP_BODIES_INTERNAL_ONLY`. Each mode controls three boolean flags (`includesRuntime`, `includesHttpSummary`, `includesHttpBodies`). HTTP body capture is doubly gated: the mode must be `INCLUDE_HTTP_BODIES_INTERNAL_ONLY` AND the build must be an internal (debug) build — never in release. The mode is stored in `TraceSettingsDataStore` (DataStore Preferences) and persists across restarts.

Redaction is applied before any data reaches the JSONL sink. `TraceRedactor` strips 10 HTTP headers (including `simkl-api-key`, `trakt-api-key`, `simkl-client-id`, `x-tvdb-apikey`, `authorization`, `cookie`), 14 JSON body keys (including `client_secret`, `access_token`, `refresh_token`, `code`, `client_id`), and auth-sensitive URL query parameters. Redaction is applied by `RuntimeTraceInterceptor` at the OkHttp network layer and by `TraceMetadataEvents` helpers at the application layer. The exported bundle includes a `redaction-manifest.json` documenting what was redacted — though this manifest is currently out of sync with the actual `TraceRedactor` sets (finding F2-I-02).

---

## Component map

| Component | File | Role |
|---|---|---|
| `RuntimeTraceSink` / `NoopRuntimeTraceSink` | `core/trace/RuntimeTraceSink.kt` | Emit target interface; `NoopRuntimeTraceSink` is the default when no session is active |
| `FileRuntimeTraceSink` | `core/trace/FileRuntimeTraceSink.kt` | Session-scoped JSONL writer; 50 MB cap; 100-event ring buffer for live status; BLOCKER-priority events exempt from cap |
| `JsonlTraceWriter` | `core/trace/JsonlTraceWriter.kt` | Appends JSONL lines to the trace file; backed by `PrintWriter` (silently swallows `IOException` — finding F2-I-10) |
| `TraceMode` enum + `TraceModeProvider` | `core/trace/TraceMode.kt` | Gating: `OFF` / `SAFE_METADATA_RUNTIME` / `INCLUDE_HTTP_SUMMARY` / `INCLUDE_HTTP_BODIES_INTERNAL_ONLY`; 3 boolean flags per variant |
| `DataStoreTraceModeProvider` | `core/trace/DataStoreTraceModeProvider.kt` | Reads current mode from `TraceSettingsDataStore`; mode changes take effect on next request |
| `TraceEventEnvelope<T>` | `core/trace/TraceEventEnvelope.kt` | Generic payload wrapper: `schemaVersion`, `traceSessionId`, `sequence`, `wallClockMs`, `elapsedRealtimeMs`, `threadName`, `eventType`, `payload` |
| `TraceMetadataEvents` | `core/trace/TraceMetadataEvents.kt` | 9 emit helpers: `emitFirstPaint`, `emitIdentityResolution`, `emitProviderPlan`, `emitResolverSchedule`, `emitNormalizerWarning`, `emitFieldSelected`, `emitRouteDecision`, `emitScrobbleRejected`, `emitLocalizationPlan` |
| `FirstPaintTracer` | `core/trace/FirstPaintTracer.kt` | Static singleton; bridges `MetaPreview.toFirstPaintHomeDisplayMetadata()` calls to `emitFirstPaint` without DI plumbing into domain types |
| `RuntimeTraceInterceptor` | `core/trace/RuntimeTraceInterceptor.kt` | OkHttp **network** interceptor; emits `http.request`, `http.response`, `http.error`, `trace.body_sample` (gated on mode + `isInternalBuild`) |
| `RuntimeTraceContextRequestTaggingInterceptor` | `core/trace/RuntimeTraceContextRequestTaggingInterceptor.kt` | OkHttp **application** interceptor; bridges coroutine-scoped `RuntimeTraceContextElement` thread-local onto OkHttp request tags so the network interceptor can read `RuntimeTraceContext` |
| `RuntimeTraceContext` / `RuntimeTraceContextElement` | `core/trace/RuntimeTraceContext.kt` / `RuntimeTraceContextElement.kt` | Per-coroutine trace context carrying `sessionId`, `operationId`, `providerName`, `traceMode` |
| `TraceRedactor` | `core/trace/TraceRedactor.kt` | Redacts URL params, HTTP headers, JSON body keys before sink emission; expanded in F-I-01 to cover provider auth headers |
| `TraceCacheDecision` enum | `core/trace/TraceCacheDecision.kt` | `HIT`, `MISS_THEN_NETWORK`, `STALE_HIT`, `EXPIRED_MISS`, `BYPASS_DISABLED`, `OBSERVE_ONLY`, `WRITE` — all 7 emitted from `DefaultIntegrationRuntime` |
| `TraceSessionManager` + `TraceSession` | `core/trace/TraceSessionManager.kt` | Creates/destroys sessions; writes `TraceSession` (carrying `gitSha: String?` — always `null` in production; finding F2-I-01) |
| `TraceBundleExporter` | `core/trace/TraceBundleExporter.kt` | Zips 7 artifacts: `trace-events.jsonl`, `trace-summary.json`, `trace-summary.md`, `trace-validation-report.json`, `redaction-manifest.json`, `device-info.json`, `app-build-info.json` |
| `TraceSummaryGenerator` | `core/trace/TraceSummaryGenerator.kt` | Generates human-readable summary from captured events |
| `RuntimeTraceValidator` | `core/trace/RuntimeTraceValidator.kt` | Runs 16 structural invariant rules on a captured session |
| `TraceValidationRules` (16 rules) | `core/trace/TraceValidationRules.kt` | Rule definitions covering HTTP invariants, cache decisions, metadata routing, profile boundary, and localization ordering |
| `UnscopedNetworkPolicyGuard` | `core/trace/UnscopedNetworkPolicyGuard.kt` | Emits `policy.unscoped_network_call` when `IntegrationNetworkPermitInterceptor` detects a permit-less in-scope call (telemetry only in `AUDIT_ONLY` mode) |
| `RuntimeTraceSettingsViewModel` + `RuntimeTraceSettingsScreen` | `ui/screens/settings/RuntimeTraceSettings*.kt` | Settings UI: mode picker + session start/stop; nav not yet wired; entry point not gated on `BuildConfig.DEBUG` (finding F2-I-07) |
| `RuntimeTraceLiveStatusViewModel` + `RuntimeTraceLiveStatusScreen` | `ui/screens/settings/RuntimeTraceLiveStatus*.kt` | 1 Hz ring-buffer poll for live status display |
| `RuntimeTraceModule` (Hilt) | `core/di/RuntimeTraceModule.kt` | Provides `RuntimeTraceSink`, `RuntimeTraceInterceptor`, `RuntimeTraceContextRequestTaggingInterceptor`, `TraceSessionManager`, `TraceBundleExporter` |

---

## Contracts trace mode must satisfy

- Every emit method has at least one production caller (verified: all 9 `TraceMetadataEvents` helpers have callers).
- Every validator rule has an event source (verified for 14 of 16 rules; `metadata.normalizer_warning` and `playback.scrobble_rejected` have no validator rule — F2-I-08, F2-I-09).
- HTTP bodies absent unless mode is `INCLUDE_HTTP_BODIES_INTERNAL_ONLY` AND `BuildConfig.DEBUG` is true — pinned by `RuntimeTraceInterceptorBodyGatingTest` (5 tests; `SAFE_METADATA_RUNTIME` coverage absent — F2-I-04).
- Auth tokens redacted: `TraceRedactor` covers 10 headers + 14 JSON body keys + URL params; parity with live sets not pinned by a test (F2-I-02).
- Bundle ZIP exports all 7 required artifacts with no raw auth tokens — pinned by `TraceBundleGoldenTest` and `TraceBundleExporterTest`.
- `gitSha` + worktree state recorded in bundle metadata — NOT satisfied in production: `gitSha = null` always (F2-I-01).
- `RuntimeTraceValidatorRealEmissionTest` is included in `generateTraceValidatorAudit` task filter — verified; wildcard pattern `com.nexio.tv.core.trace.*Validator*Test` matches.
- `LocalizationPlanPrecedesProviderSteps` rule fires a FAIL when `metadata.provider_plan` arrives before `metadata.localization_plan` for TVDB/TMDB/KITSU routes — pinned by `RuntimeTraceValidatorLocalizationPlanRuleTest` (isolation tests); NOT validated end-to-end against real TVDB episode-path emissions (F2-E-03, F2-I-11).
- Every fresh `OkHttpClient.Builder()` in `NetworkModule` wires both `RuntimeTraceContextRequestTaggingInterceptor` (application) and `RuntimeTraceInterceptor` (network) — pinned by `DerivedOkHttpClientTraceWiringTest` and `YouTubeTrailerClientTraceInterceptorTest`. Four fresh builds confirmed.
- Trace settings UI requires no special ADB or developer-mode unlock — VIOLATED: entry is unconditional in `PlaybackSettingsSections.kt:635-642`; no `BuildConfig.DEBUG` gate (F2-I-07).
- `SecondaryDoesNotOverwritePrimary` validator rule correctly identifies secondary-wins-on-protected-field scenarios — VIOLATED: rule fires on any rejected-candidates scenario regardless of who won (F2-I-06).
- `EXPIRED_MISS` and `WRITE` `TraceCacheDecision` values are consumed by at least one validator rule — NOT satisfied (F2-I-05).

---

## Current implementation gaps

### I-01 → F2-I-02: `redactionManifest()` out of sync with `TraceRedactor`

`TraceRedactor` gained 4 new headers and 2 new JSON body keys in F-I-01. `TraceBundleExporter.redactionManifest()` was not updated. The exported `redaction-manifest.json` misrepresents what is actually redacted. A `TraceRedactorManifestParityTest` would catch future drift automatically.

**Status:** Not addressed in any cluster; remains open.

### I-02-nit: `RuntimeTraceModule.kt:100-101` KDoc references stale method name

Comment says `"MetaPreview.toHomeDisplayMetadata()"` but actual wiring is `"toFirstPaintHomeDisplayMetadata()"`. The wiring itself is correct (F-I-02 is closed); only the comment is stale.

### I-03 → F2-I-01: `gitSha` always `null` in production trace bundles

`RuntimeTraceModule.provideTraceBuildInfo()` hardcodes `gitSha = null`. No `buildConfigField` captures the commit SHA at build time. Support engineers receiving a trace bundle cannot determine which build produced it.

**Status:** Not addressed in any cluster; remains open.

### I-04 → F2-I-04: `SAFE_METADATA_RUNTIME` mode not covered in body-gating tests

`RuntimeTraceInterceptorBodyGatingTest` has 5 tests but neither `SAFE_METADATA_RUNTIME + internal` nor `SAFE_METADATA_RUNTIME + non-internal` is asserted. The mode short-circuits at `!mode.includesHttpSummary` so no body is possible, but this is not pinned.

**Status:** Not addressed in any cluster; remains open.

### I-05 → F2-I-05: `EXPIRED_MISS` and `WRITE` `TraceCacheDecision` values have no validator rule

`DefaultIntegrationRuntime` emits both values, but `RuntimeTraceValidator` counts only `HIT`, `MISS_THEN_NETWORK`, and `STALE_HIT`. No rule asserts "an `EXPIRED_MISS` must be followed by `http.request` for the same operation." A silent bug that causes `EXPIRED_MISS` to skip the network call would go undetected.

**Status:** Not addressed in any cluster; remains open.

### I-06 → F2-I-07: Trace settings UI accessible to retail users

`PlaybackSettingsSections.kt:635-642` renders the trace entry unconditionally. A retail Fire TV / Android TV user can navigate to Settings → Playback → Logging → Runtime Trace (once navigation is wired) and start capturing HTTP URLs and headers. Body capture is correctly gated in the interceptor, but URL and header metadata is sensitive.

**Status:** Not addressed in any cluster; remains open.

### I-07 → F2-I-06: `SecondaryDoesNotOverwritePrimary` rule has inverted semantics

The rule fires when `rejectedCandidates` is non-empty for protected fields, regardless of whether the winner was primary or secondary. Primary-wins-with-competition produces a false-positive FAIL. The correct condition is `sourceRole == "SECONDARY"` (or equivalent) combined with the non-empty rejected candidates check.

**Status:** Not addressed in any cluster; remains open.

### I-08 → F2-I-08: `metadata.normalizer_warning` event has no validator rule

`emitNormalizerWarning` was added in F-B-07 and is wired in `MetadataRequestNormalizer`. No `TraceValidationRule` asserts structural invariants about these events. A payload key rename would go undetected.

**Status:** Not addressed in any cluster; remains open.

### I-09 → F2-I-10: `JsonlTraceWriter.append()` silently swallows `IOException`

`PrintWriter` catches `IOException` internally and sets an error flag without propagating it. `droppedCount()` tracks byte-cap drops only. Storage-full events produce no increment to any counter and no UI indication. On a low-storage device, events are silently lost.

**Status:** Not addressed in any cluster; remains open.

### I-10 → F2-I-09: `playback.scrobble_rejected` event has no validator rule

Both `TraktScrobbleService` and `SimklScrobbleService` call `emitScrobbleRejected`. No `TraceValidationRule` asserts the event carries non-null `envelopeProfileId` and `activeProfileId`. The event name also misrepresents the actual behavior (the write proceeds — F2-H-01). Until enforcement is fixed, the event name should be `"playback.scrobble_boundary_mismatch"`.

**Status:** Not addressed in any cluster; remains open.

### I-11 / E-03 → F2-E-03: `LocalizationPlanPrecedesProviderSteps` rule has no real-emission end-to-end coverage for TVDB episode path

`RuntimeTraceValidatorRealEmissionTest` drives only `kitsu:7442`. The TVDB `SERIES_EPISODES_LANGUAGE` branch that emits the second `emitLocalizationPlan` (with real `perEpisodeFallbacksAttempted`) and per-episode `emitFieldSelected` is never driven. If `emitLocalizationPlan` payload key `"provider"` were renamed, the isolation tests would still pass.

**Status:** Not addressed in any cluster; remains open.

---

## Recommended next iteration

1. **Fix `gitSha` capture and `redactionManifest()` parity (F2-I-01, F2-I-02).** These are one-line build config changes with self-evident value. Add a `buildConfigField("String", "GIT_SHA", ...)` in `app/build.gradle.kts` and add `TraceRedactorManifestParityTest`. Both changes are cheap and high-value for support/security reviewers.

2. **Fix `JsonlTraceWriter` `IOException` handling (F2-I-10).** Replace `PrintWriter` with `BufferedWriter(FileWriter(...))`. Wrap `out.write(line)` in try-catch; increment a separate `ioErrors` counter on catch. Expose `ioErrorCount()` in `RuntimeTraceLiveStatusUiState`. Without this, low-storage devices silently lose trace events with no UI signal.

3. **Gate trace settings UI on `BuildConfig.IS_DEBUG_BUILD` (F2-I-07).** This is a privacy/security fix for retail devices. Either gate the entire entry, or restrict the mode picker to `OFF` and `SAFE_METADATA_RUNTIME` for non-debug builds. This should land before the trace settings navigation is wired (currently `TODO: nav to RuntimeTraceSettingsScreen`).

4. **Fix `SecondaryDoesNotOverwritePrimary` validator rule semantics (F2-I-06).** Add `sourceRole != "PRIMARY"` (or equivalent) to the filter condition. Add tests asserting: primary-wins-with-competition → PASS; secondary-wins-on-protected-field → FAIL. Without this fix, running the validator on a real production trace session produces false-positive FAILs for every multi-candidate primary-wins scenario.

5. **Add validator rules for `EXPIRED_MISS`, `WRITE`, `metadata.normalizer_warning`, and `playback.scrobble_rejected` (F2-I-05, F2-I-08, F2-I-09).** The `generateTraceValidatorAudit` gate currently validates only 14 of the 16+ distinct event types that the trace subsystem emits. Each uncovered event type is a schema-drift vector. Minimal rules: `ExpiredMissPrecedesNetworkRequest`, `NormalizerWarningHasContentId`, `ScrobbleRejectedHasProfileIds`. Also extend `TraceValidationReport` counters for `EXPIRED_MISS` and `WRITE`.
