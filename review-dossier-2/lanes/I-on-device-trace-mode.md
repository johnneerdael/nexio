# Lane I — On-Device Trace Mode

Review SHA: `774a540f8` — Generated 2026-04-29

---

## 1. What changed in this lane on this branch

**Core trace infrastructure (pre-existing, expanded in this branch).** The on-device trace pipeline was delivered in earlier clusters and extended by the current branch. The key subsystems are:

- `RuntimeTraceSink` / `NoopRuntimeTraceSink` — minimal emit-target interface; `NoopRuntimeTraceSink` is the default until a session is started.
- `FileRuntimeTraceSink` — session-scoped JSONL writer backed by `JsonlTraceWriter` with a 50 MB cap, a 100-event ring buffer for live status, and a priority tier (`BLOCKER` / `HIGH` / `MEDIUM` / `LOW` / `VERBOSE`) that exempts `BLOCKER`-priority events from the byte cap.
- `TraceMode` enum — `OFF`, `SAFE_METADATA_RUNTIME`, `INCLUDE_HTTP_SUMMARY`, `INCLUDE_HTTP_BODIES_INTERNAL_ONLY`; each carries three boolean flags (`includesRuntime`, `includesHttpSummary`, `includesHttpBodies`).
- `RuntimeTraceInterceptor` (network-layer OkHttp interceptor) — emits `http.request`, `http.response`, `http.error`, and (gated on mode + `isInternalBuild`) `trace.body_sample`.
- `RuntimeTraceContextRequestTaggingInterceptor` (application-layer OkHttp interceptor) — bridges the coroutine-scoped `RuntimeTraceContextElement` thread-local onto OkHttp request tags so the network interceptor can read the `RuntimeTraceContext`.
- `TraceMetadataEvents` — helper class with nine emit methods: `emitFirstPaint`, `emitIdentityResolution`, `emitProviderPlan`, `emitResolverSchedule`, `emitNormalizerWarning` (F-B-07), `emitFieldSelected`, `emitRouteDecision`, `emitScrobbleRejected`, `emitLocalizationPlan` (F-E-02).
- `TraceEventEnvelope<T>` — generic payload wrapper with `schemaVersion`, `traceSessionId`, `sequence`, wall-clock / elapsed-realtime timestamps, `threadName`, `eventType`, and `payload`.
- `TraceCacheDecision` enum — `HIT`, `MISS_THEN_NETWORK`, `STALE_HIT`, `EXPIRED_MISS`, `BYPASS_DISABLED`, `OBSERVE_ONLY`, `WRITE`.
- `TraceRedactor` — redacts URL query params, HTTP headers, and JSON body keys; expanded by F-I-01 to cover provider-specific auth headers (`simkl-api-key`, `trakt-api-key`, `simkl-client-id`, `x-tvdb-apikey`) and OAuth POST body keys (`code`, `client_id`).
- `TraceSessionManager` — creates / destroys sessions; writes `TraceSession` (carrying `gitSha: String?`) and owns the `FileRuntimeTraceSink` lifecycle.
- `TraceBundleExporter` — zips seven artifacts: `trace-events.jsonl`, `trace-summary.json`, `trace-summary.md`, `trace-validation-report.json`, `redaction-manifest.json`, `device-info.json`, `app-build-info.json`.
- `RuntimeTraceValidator` + `TraceValidationRules` — 16 rules in `TraceValidationRules.ALL` covering HTTP invariants, cache decisions, metadata routing, profile boundary, and localization ordering.
- `FirstPaintTracer` — static singleton wired by `RuntimeTraceModule`; bridges pure-domain `MetaPreview.toFirstPaintHomeDisplayMetadata()` calls to `emitFirstPaint` without requiring DI plumbing into domain types.
- Settings UI — `RuntimeTraceSettingsViewModel` + `RuntimeTraceSettingsScreen` (mode picker + session start/stop), `RuntimeTraceLiveStatusViewModel` + `RuntimeTraceLiveStatusScreen` (1 Hz ring-buffer poll).

**Changes specific to this branch.** F-B-07 added `emitNormalizerWarning` to `TraceMetadataEvents` and wired it in `MetadataRequestNormalizer` for the `ContentType.TV → SERIES` coercion case. F-I-01 expanded `TraceRedactor` with four provider auth headers and two OAuth body keys. F-I-05 (cluster D + deferrals) confirmed derived `OkHttpClient` clients survive via `DerivedOkHttpClientTraceWiringTest` (architecture pin) and `YouTubeTrailerClientTraceInterceptorTest` (direct construction test). F-E-02 added `emitLocalizationPlan` to three provider adapters (TMDB, TVDB, Kitsu). The `generateTraceValidatorAudit` Gradle task was introduced to run `TraceBundleGoldenTest` and all `*Validator*Test` classes. F-I-03 (RealEmissionTest in audit filter) was resolved — the pattern `com.nexio.tv.core.trace.*Validator*Test` matches `RuntimeTraceValidatorRealEmissionTest`.

---

## 2. Architecture surfaces in scope

| Surface | File | Status |
|---|---|---|
| `RuntimeTraceSink` / `NoopRuntimeTraceSink` | `core/trace/RuntimeTraceSink.kt` | active |
| `FileRuntimeTraceSink` | `core/trace/FileRuntimeTraceSink.kt` | active |
| `JsonlTraceWriter` | `core/trace/JsonlTraceWriter.kt` | active |
| `TraceMode` enum + `TraceModeProvider` | `core/trace/TraceMode.kt` | active |
| `TraceEventEnvelope<T>` | `core/trace/TraceEventEnvelope.kt` | active |
| `TraceMetadataEvents` | `core/trace/TraceMetadataEvents.kt` | active — 9 emit methods |
| `FirstPaintTracer` (static singleton) | `core/trace/FirstPaintTracer.kt` | active |
| `RuntimeTraceInterceptor` | `core/trace/RuntimeTraceInterceptor.kt` | active |
| `RuntimeTraceContextRequestTaggingInterceptor` | `core/trace/RuntimeTraceContextRequestTaggingInterceptor.kt` | active |
| `RuntimeTraceContextElement` | `core/trace/RuntimeTraceContextElement.kt` | active |
| `RuntimeTraceContext` | `core/trace/RuntimeTraceContext.kt` | active |
| `TraceRedactor` | `core/trace/TraceRedactor.kt` | active — F-I-01 expanded |
| `TraceCacheDecision` enum | `core/trace/TraceCacheDecision.kt` | active — 7 values; `HIT`, `MISS_THEN_NETWORK`, `STALE_HIT`, `EXPIRED_MISS`, `BYPASS_DISABLED`, `OBSERVE_ONLY`, `WRITE` |
| `TraceSessionManager` + `TraceBuildInfo` | `core/trace/TraceSessionManager.kt` | active |
| `TraceSession` | `core/trace/TraceSession.kt` | active |
| `TraceBundleExporter` | `core/trace/TraceBundleExporter.kt` | active |
| `TraceSummaryGenerator` | `core/trace/TraceSummaryGenerator.kt` | active |
| `RuntimeTraceValidator` | `core/trace/RuntimeTraceValidator.kt` | active |
| `TraceValidationRules` (16 rules) | `core/trace/TraceValidationRules.kt` | active |
| `TraceValidationRule` interface | `core/trace/TraceValidationRule.kt` | active |
| `TraceValidationReport` | `core/trace/TraceValidationReport.kt` | active |
| `UnscopedNetworkPolicyGuard` | `core/trace/UnscopedNetworkPolicyGuard.kt` | active |
| `DataStoreTraceModeProvider` | `core/trace/DataStoreTraceModeProvider.kt` | active |
| `TraceSettingsDataStore` | `data/local/TraceSettingsDataStore.kt` | active |
| `RuntimeTraceSettingsViewModel` + `RuntimeTraceSettingsScreen` | `ui/screens/settings/RuntimeTraceSettings*.kt` | active — nav not yet wired |
| `RuntimeTraceLiveStatusViewModel` + `RuntimeTraceLiveStatusScreen` | `ui/screens/settings/RuntimeTraceLiveStatus*.kt` | active |
| `RuntimeTraceModule` (Hilt) | `core/di/RuntimeTraceModule.kt` | active |
| Validator tests (×5) | `core/trace/RuntimeTraceValidator*Test.kt`, `TraceBundleGoldenTest.kt` | active |
| F-I-05 wiring tests (×2) | `core/network/DerivedOkHttpClientTraceWiringTest.kt`, `YouTubeTrailerClientTraceInterceptorTest.kt` | active |

---

## 3. Contracts this lane must satisfy

1. Every active trace session captures all emitted events into an append-only JSONL file; the file is bounded to 50 MB (non-BLOCKER events dropped when full).
2. HTTP bodies are only captured when mode is `INCLUDE_HTTP_BODIES_INTERNAL_ONLY` **and** the build is an internal (`isInternalBuild = BuildConfig.DEBUG`) build — never in release.
3. All auth-sensitive URL query parameters, HTTP headers, and JSON body keys are redacted before they reach the JSONL sink; the redaction set is at least as broad as the auth surface of all active providers.
4. The trace bundle ZIP exports all seven required artifacts and contains no raw auth tokens.
5. `RuntimeTraceValidator` runs 16 structural invariant rules on a captured session; the audit task `generateTraceValidatorAudit` executes all five validator suite classes and `TraceBundleGoldenTest`.
6. `emitFirstPaint` is called from the canonical Home first-paint boundary (`ModernHomeModels.buildCatalogItem` → `toFirstPaintHomeDisplayMetadata`), not from router pre-flight or other non-presentation paths.
7. Every `emitLocalizationPlan` call precedes the `metadata.provider_plan` event for the same provider in the same session (validated by `LocalizationPlanPrecedesProviderSteps` rule).
8. `RuntimeTraceValidatorRealEmissionTest` is included in the `generateTraceValidatorAudit` Gradle task filter.
9. Every `OkHttpClient.Builder()` fresh construction in `NetworkModule` carries both the tagging interceptor (application) and the trace interceptor (network) — pinned by `DerivedOkHttpClientTraceWiringTest` and `YouTubeTrailerClientTraceInterceptorTest`.
10. The trace settings UI requires no special ADB or developer-mode unlock to reach; access control must be verified against the broader security posture of the app.

---

## 4. Generated reports proving (or not) each contract

| Contract | Generated proof | Verdict |
|---|---|---|
| C-1: Session bounded JSONL | `JsonlTraceWriterTest` — verifies drop when `maxBytes` exceeded (non-BLOCKER), BLOCKER always written | PASS |
| C-2: Bodies only in `INCLUDE_HTTP_BODIES_INTERNAL_ONLY` + internal build | `RuntimeTraceInterceptorBodyGatingTest` — 5 scenarios covering all mode/build combinations | PASS |
| C-3: Auth redaction | `TraceRedactorTest` + `TraceRedactorAuthHeaderParityTest` — covers URL params, Authorization header, F-I-01 provider headers, OAuth JSON keys | PASS (with F-I-06 caveat — see findings) |
| C-4: Bundle ZIP, no raw tokens | `TraceBundleExporterTest` + `TraceBundleGoldenTest` — assert 7 required entries, assert no raw "Bearer SECRET" / "api_key=ABC123" | PASS |
| C-5: Validator audit | `generateTraceValidatorAudit` task — 11 tests across 5 suites + `TraceBundleGoldenTest`; 0 failures per existing `06-localization-audit/` report | PASS |
| C-6: First-paint at correct boundary | `ModernHomeModels.kt:569` calls `toFirstPaintHomeDisplayMetadata()` from `buildCatalogItem`; `HomeFirstPaintMetadataMapper.kt` is the only file defining `toFirstPaintHomeDisplayMetadata` | PASS (see I-02 nuance in findings) |
| C-7: Localization plan precedes provider steps | `LocalizationPlanPrecedesProviderSteps` rule + `RuntimeTraceValidatorLocalizationPlanRuleTest` | PASS (schema-level; E-03 cross-lane: real-emission test does not drive TVDB episode path) |
| C-8: RealEmissionTest in audit filter | Pattern `com.nexio.tv.core.trace.*Validator*Test` matches `RuntimeTraceValidatorRealEmissionTest`; test XML present in `06-localization-audit/` | PASS — F-I-03 resolved |
| C-9: OkHttp clients wired | `DerivedOkHttpClientTraceWiringTest` (architecture scan) + `YouTubeTrailerClientTraceInterceptorTest` (construction test) | PASS — F-I-05 closed |
| C-10: UI access control | `PlaybackSettingsSections.kt:635-642` — trace entry is in the Logging/Troubleshooting section with no `BuildConfig.DEBUG` gate; navigation is stubbed (`TODO: nav to RuntimeTraceSettingsScreen`) | OPEN — see I-07 |

---

## 5. Manual review still needed

- **Navigation stub for trace settings**: `PlaybackSettingsScreen.kt:433` leaves `onOpenRuntimeTrace = { /* TODO: nav to RuntimeTraceSettingsScreen */ }`. The UI entry exists in the settings menu but tapping it does nothing in any current build. The security question (any user can open it once nav is wired) remains open.

- **`SecondaryDoesNotOverwritePrimary` rule false-positive risk**: The rule fires whenever a `metadata.field_selected` event for `TITLE`, `OVERVIEW`, or `EPISODE_LIST` has a non-empty `rejectedCandidates` list. This condition is true whenever there was competition for the field — it does NOT check whether the winner was a secondary provider. A primary-wins scenario with rejected secondary candidates still triggers the rule. Verify whether this was intended, or whether the rule should additionally gate on `sourceRole == "SECONDARY"` (see Finding I-08).

- **`EXPIRED_MISS` and `STALE_HIT` validator rule coverage**: `TraceCacheDecision` has 7 values; `RuntimeTraceValidator` counts `STALE_HIT` (line 29) and `MISS_THEN_NETWORK` (line 23) in its report counters. The `FreshCacheHitSuppressesNetwork` rule only checks `HIT`. There is no rule asserting that an `EXPIRED_MISS` always results in a subsequent `http.request` event. Verify whether this invariant needs a rule.

- **`UnscopedNetworkPolicyGuard.reportUnscoped` in release builds**: When `isInternalBuild = false`, the guard emits a `policy.unscoped_network_call` trace event (with `sessionId() ?: "noop"`) but does NOT throw. When no session is active, `sessionId()` returns null and the event is emitted with `traceSessionId = "noop"` to the active `RuntimeTraceSink`. Since no active session means `activeSink()` returns `NoopRuntimeTraceSink`, the envelope is silently discarded. This is safe but means unscoped network calls in release production go completely undetected unless tracing is active. Confirm this is acceptable.

- **`JsonlTraceWriter` does not handle `IOException`**: `PrintWriter.write()` can silently swallow `IOException` (PrintWriter does not throw checked exceptions). If the underlying file stream fails (e.g., storage full mid-session), events will be silently dropped beyond what the BLOCKER bypass permits. The `droppedCount()` counter is only incremented for byte-cap drops, not IO failures. A try-catch wrapping `out.write(line)` with an increment to a separate IO-error counter would give operators visibility into storage failures (see Finding I-09).

- **`RuntimeTraceLiveStatusViewModel` polling loop has no `while (isActive)` guard**: The `while (true)` + `delay(1_000L)` loop at `RuntimeTraceLiveStatusViewModel.kt:36-39` runs inside `viewModelScope.launch`. Since `viewModelScope` is cancelled when the ViewModel is cleared, this is safe. However, the pattern is fragile — a `delay` cancellation exception propagates silently and could suppress subsequent work. Consider replacing with `flow { while (true) { emit(Unit); delay(1_000L) } }.collect { snapshot() }` which is easier to test and has explicit cancellation semantics.

---

## 6. Tests that would catch regression

| Test name | Location | What it locks |
|---|---|---|
| `RuntimeTraceValidatorTest` | `core/trace/RuntimeTraceValidatorTest.kt` | Validator basics: clean session PASS, synthetic preview violation FAIL, counters |
| `RuntimeTraceValidatorScheduledDispatchedTest` | `core/trace/RuntimeTraceValidatorScheduledDispatchedTest.kt` | `ScheduledResolversAreDispatched` rule — both `scheduled` and `networkResolvers` payload keys |
| `RuntimeTraceValidatorLocalizationPlanRuleTest` | `core/trace/RuntimeTraceValidatorLocalizationPlanRuleTest.kt` | `LocalizationPlanPrecedesProviderSteps` rule — TVDB, TMDB, Kitsu routes; non-covered providers pass |
| `RuntimeTraceValidatorRealEmissionTest` | `core/trace/RuntimeTraceValidatorRealEmissionTest.kt` | End-to-end schema drift: real emission sites → JSONL → validator; catches key-name mismatches between emit and rule lookups |
| `TraceBundleGoldenTest` | `core/trace/TraceBundleGoldenTest.kt` | Synthetic session PASS + bundle zip has 7 entries + no raw auth tokens |
| `TraceBundleExporterTest` | `core/trace/TraceBundleExporterTest.kt` | Bundle entry presence + no raw token strings |
| `RuntimeTraceInterceptorBodyGatingTest` | `core/trace/RuntimeTraceInterceptorBodyGatingTest.kt` | F-I-04 (partial): `INCLUDE_HTTP_SUMMARY` never emits body_sample; `INCLUDE_HTTP_BODIES_INTERNAL_ONLY` + non-internal never emits; `OFF` emits nothing |
| `TraceRedactorTest` | `core/trace/TraceRedactorTest.kt` | URL query, Authorization header, JSON body key redaction |
| `TraceRedactorAuthHeaderParityTest` | `core/trace/TraceRedactorAuthHeaderParityTest.kt` | F-I-01: `simkl-api-key`, `trakt-api-key`, `simkl-client-id`, `x-tvdb-apikey`, `code`, `client_id` redacted |
| `RuntimeTraceInterceptorTest` | `core/trace/RuntimeTraceInterceptorTest.kt` | Interceptor request/response/error events; unscoped guard invocation |
| `TraceInterceptorOrderingTest` | `core/trace/TraceInterceptorOrderingTest.kt` | Tagging interceptor precedes trace interceptor in the OkHttp chain |
| `DerivedOkHttpClientTraceWiringTest` | `core/network/DerivedOkHttpClientTraceWiringTest.kt` | F-I-05 part 1: every fresh `OkHttpClient.Builder()` in `NetworkModule` wires `traceInterceptor` |
| `YouTubeTrailerClientTraceInterceptorTest` | `core/network/YouTubeTrailerClientTraceInterceptorTest.kt` | F-I-05 part 2: YouTube trailer main + probe clients carry tagging + trace interceptors |
| `FileRuntimeTraceSinkTest` | `core/trace/FileRuntimeTraceSinkTest.kt` | Emit-to-JSONL round-trip, session ID mismatch throws |
| `FileRuntimeTraceSinkRingBufferTest` | `core/trace/FileRuntimeTraceSinkRingBufferTest.kt` | 100-event ring buffer eviction |
| `TraceMetadataEventsTest` | `core/trace/TraceMetadataEventsTest.kt` | `emitFirstPaint` payload shape; no-emit when `sessionId()` is null |
| `TraceMetadataEventsLocalizationPlanTest` | `core/trace/TraceMetadataEventsLocalizationPlanTest.kt` | `emitLocalizationPlan` payload shape |
| `TraceMetadataEventsScrobbleRejectedTest` | `core/trace/TraceMetadataEventsScrobbleRejectedTest.kt` | `emitScrobbleRejected` payload shape |
| `RuntimeTraceSettingsViewModelTest` | `ui/screens/settings/RuntimeTraceSettingsViewModelTest.kt` | ViewModel state transitions for mode selection, start/stop session |

---

## 7. Findings

### Finding I-01: `TraceRedactor` F-I-01 additions are not reflected in `TraceBundleExporter.redactionManifest()`

- **Severity:** P2
- **Evidence:** `TraceRedactor.kt:10-14` — `redactedHeaders` contains 10 entries after F-I-01: the original 6 (`authorization`, `cookie`, `set-cookie`, `x-api-key`, `x-auth-token`, `x-mdblist-apikey`) plus 4 new entries (`simkl-api-key`, `trakt-api-key`, `simkl-client-id`, `x-tvdb-apikey`). `TraceBundleExporter.kt:72-75` — `redactionManifest()` still lists only the original 6 headers and does NOT include the F-I-01 additions. The same divergence applies to `redactedJsonKeys`: `TraceRedactor` has 14 entries (added `code`, `client_id`), but the manifest lists only the original 12.
- **Violated contract:** C-3 / C-4: the redaction manifest is part of the exported bundle and is intended to document what was redacted. An out-of-date manifest could mislead a support engineer or security reviewer into thinking headers like `simkl-api-key` were not redacted, or vice-versa.
- **User-visible impact:** No data leak — actual redaction by `TraceRedactor` is correct. The manifest is informational, but an incorrect manifest can cause false negative security audit results or mislead Tier 2 support who rely on the manifest to know what to expect.
- **Required fix:** Add `"simkl-api-key"`, `"trakt-api-key"`, `"simkl-client-id"`, `"x-tvdb-apikey"` to the `"headers"` list in `redactionManifest()`. Add `"code"`, `"client_id"` to the `"jsonBodyKeys"` list. Add a unit test (or expand `TraceBundleExporterTest`) that asserts `redactionManifest()` contains exactly the same keys as the live `TraceRedactor` sets — so future additions to `TraceRedactor` must also update the manifest.
- **Test that should catch it:** A new `TraceRedactorManifestParityTest` that constructs both a `TraceRedactor` and a `TraceBundleExporter`, extracts the manifest, and asserts the manifest's `headers` list equals `TraceRedactor.redactedHeaders` and `jsonBodyKeys` equals `TraceRedactor.redactedJsonKeys`.

---

### Finding I-02: `emitFirstPaint` boundary is correct but the KDoc in `RuntimeTraceModule` says "addon-preview boundary" — mismatches the actual wiring

- **Severity:** Nit
- **Evidence:** `RuntimeTraceModule.kt:100-101` — the comment reads "wire FirstPaintTracer so MetaPreview.toHomeDisplayMetadata() emits `metadata.first_paint` from the canonical addon-preview boundary." `HomeFirstPaintMetadataMapper.kt:9-13` clarifies that `toFirstPaintHomeDisplayMetadata()` is only called "at the canonical Home first-paint boundaries (live presentation pipeline)." Production wiring confirms `ModernHomeModels.kt:569` calls `buildCatalogItem → item.toFirstPaintHomeDisplayMetadata()`, which is correct. The F-I-02 concern (wired to router pre-flight) is resolved — the emit is at the live-presentation boundary.
- **Violated contract:** No functional contract violated; the wiring is correct.
- **Required fix:** Update the comment at `RuntimeTraceModule.kt:100-101` from "MetaPreview.toHomeDisplayMetadata()" to "MetaPreview.toFirstPaintHomeDisplayMetadata()" and from "canonical addon-preview boundary" to "canonical Home live-presentation boundary." This makes the comment match the actual wiring and prevents future reviewers from assuming the plain `toHomeDisplayMetadata()` extension also emits.
- **Test that should catch it:** No new test needed; `RuntimeTraceValidatorRealEmissionTest` exercises the boundary.

---

### Finding I-03: Resolved — `RuntimeTraceValidatorRealEmissionTest` IS in the audit task filter

- **Severity:** Informational
- **Evidence:** `app/build.gradle.kts:411` — `includeTestsMatching("com.nexio.tv.core.trace.*Validator*Test")`. Gradle's `includeTestsMatching` supports `*` as a wildcard; `RuntimeTraceValidatorRealEmissionTest` matches because the class name contains `Validator` and ends with `Test`. The test XML `TEST-com.nexio.tv.core.trace.RuntimeTraceValidatorRealEmissionTest.xml` is present in `review-dossier-2/06-localization-audit/` with 1 test, 0 failures. F-I-03 is closed.
- **Note:** The pattern could be documented more explicitly — e.g., `includeTestsMatching("com.nexio.tv.core.trace.RuntimeTraceValidatorRealEmissionTest")` as a dedicated line — to make the intent unambiguous. The wildcard is functional but relies on the naming convention holding for all future validator tests.

---

### Finding I-04: Negative invariant "HTTP bodies absent when mode ≠ `INCLUDE_HTTP_BODIES_INTERNAL_ONLY`" is partially tested but the non-`isInternalBuild` branch is not exercised for `SAFE_METADATA_RUNTIME`

- **Severity:** P2
- **Evidence:** `RuntimeTraceInterceptorBodyGatingTest.kt` — five tests cover: `INCLUDE_HTTP_SUMMARY` + internal (no body), `INCLUDE_HTTP_SUMMARY` + non-internal (no body), `INCLUDE_HTTP_BODIES_INTERNAL_ONLY` + non-internal (no body), `INCLUDE_HTTP_BODIES_INTERNAL_ONLY` + internal (body emitted), `OFF` (nothing). Missing: `SAFE_METADATA_RUNTIME` + internal and `SAFE_METADATA_RUNTIME` + non-internal. The `SAFE_METADATA_RUNTIME` mode has `includesHttpSummary = false` so `RuntimeTraceInterceptor.intercept()` returns at line 31 before `captureBodySample` is even reached — meaning no body is possible. However, there is no test asserting this explicitly for `SAFE_METADATA_RUNTIME`.
- **Violated contract:** C-2: not fully asserted.
- **User-visible impact:** Low risk in practice because `SAFE_METADATA_RUNTIME` short-circuits at the `!mode.includesHttpSummary` check. The gap matters if mode flags are ever refactored.
- **Required fix:** Add two tests to `RuntimeTraceInterceptorBodyGatingTest`: `SAFE_METADATA_RUNTIME + internal` emits no `trace.body_sample`, and `SAFE_METADATA_RUNTIME + non-internal` emits no `trace.body_sample`.
- **Test that should catch it:** The two new tests described above.

---

### Finding I-05: `TraceCacheDecision` values `EXPIRED_MISS` and `WRITE` are emitted in production but no validator rule consumes them

- **Severity:** P2
- **Evidence:** `TraceCacheDecision.kt` — 7 values. `DefaultIntegrationRuntime.kt` emits `EXPIRED_MISS` (lines 488, 581) and `WRITE` (line 551). `RuntimeTraceValidator.kt:19-31` counts `HIT`, `MISS_THEN_NETWORK`, and `STALE_HIT` in `TraceValidationReport`, but `EXPIRED_MISS` and `WRITE` are not counted and no rule references them. `TraceValidationRules.FreshCacheHitSuppressesNetwork` only watches `HIT`. No rule asserts, for example, "an `EXPIRED_MISS` decision must be followed by a `http.request` for the same `runtimeOperationId`" or "a `WRITE` decision always follows a `MISS_THEN_NETWORK`."
- **Violated contract:** C-5 (audit coverage). Cross-reference Lane D finding F-D-03 (mentioned in scope for cross-lane trace integrity): if cache invalidation or eviction decisions are introduced, they will also have no validator rule.
- **User-visible impact:** No direct user impact. If a bug causes `EXPIRED_MISS` to silently skip the network call, no trace rule catches it.
- **Required fix:** Add a `TraceValidationRule` — `ExpiredMissPrecedesNetworkRequest` — that asserts every `runtime.cache_decision` with `decision == EXPIRED_MISS` is followed by an `http.request` for the same `runtimeOperationId`. Add `EXPIRED_MISS` and `WRITE` counters to `TraceValidationReport`. Also add `BYPASS_DISABLED` and `OBSERVE_ONLY` counts for completeness.
- **Test that should catch it:** New tests in `RuntimeTraceValidatorTest` and `RuntimeCacheDecisionTraceTest`.

---

### Finding I-06: `TraceSession.gitSha` is always `null` in production — audit bundle stamps `null` for `gitSha`

- **Severity:** P1
- **Evidence:** `RuntimeTraceModule.provideTraceBuildInfo()` at `core/di/RuntimeTraceModule.kt:41` — `gitSha = null`. `TraceBundleExporter.appBuildInfo()` at line 92 — `"gitSha" to session.gitSha` — so `app-build-info.json` always contains `"gitSha": null`. There is no Gradle `buildConfigField` that captures the current commit SHA. The Stage 1 audit observation noted the audit stamp reading `Git SHA: 9f0555a5a` while the actual worktree HEAD was `774a540f8`; that mismatch originated from a stale `gitSha` source (the audit task, not the trace bundle). However, the trace bundle itself has the same null-SHA problem: a support engineer receiving a `.zip` cannot determine which build produced it beyond `appVersion` + `buildType`.
- **Violated contract:** C-4 (bundle provenance). Integrity of the diagnostic artifact is compromised.
- **User-visible impact:** Support / QA engineers cannot correlate a trace bundle to a specific commit, reducing diagnostic utility. A trace bundle from a buggy build cannot be matched to the exact source code.
- **Required fix:** Add `buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")` in `app/build.gradle.kts` where `gitSha()` runs `git rev-parse --short HEAD` at build time (or reads `GIT_COMMIT` from CI env as fallback). Change `gitSha = null` in `provideTraceBuildInfo()` to `gitSha = BuildConfig.GIT_SHA.takeIf { it.isNotBlank() }`. For the separate audit-report stamp (Stage 1 observation): the `GenerateIntegrationRuntimeAuditTask` should read the same `BuildConfig.GIT_SHA` or accept the SHA as a Gradle input property from CI (e.g., `providers.environmentVariable("GIT_COMMIT").orElse(exec("git rev-parse HEAD"))`).
- **Test that should catch it:** A new `TraceBuildInfoGitShaTest` (unit test) that instantiates `TraceBuildInfo` with a non-null `gitSha` and asserts the exported `app-build-info.json` contains it. The audit-stamp fix should be verified by a Gradle task test or CI lint check.

---

### Finding I-07: Trace settings UI is reachable by any user in all builds — no dev-mode or debug-only gate

- **Severity:** P2
- **Evidence:** `PlaybackSettingsSections.kt:635-642` — the "Runtime & Metadata Trace" entry is rendered unconditionally in the Logging/Troubleshooting section with no `BuildConfig.DEBUG`, `BuildConfig.IS_DEBUG_BUILD`, or developer-mode check. The mode picker in `RuntimeTraceSettingsScreen` exposes all four `TraceMode` values — including `INCLUDE_HTTP_BODIES_INTERNAL_ONLY` — without any guard. `RuntimeTraceModule.provideRuntimeTraceInterceptor()` passes `isInternalBuild = BuildConfig.DEBUG`, so `trace.body_sample` will NOT be emitted in release even if a release user selects `INCLUDE_HTTP_BODIES_INTERNAL_ONLY`. The mode flag is stored in `TraceSettingsDataStore` (DataStore Preferences) and persists across app restarts. `RuntimeTraceInterceptor` re-reads `modeProvider.current` per request so a mode change takes effect without a restart.
- **Violated contract:** C-10. A non-technical user on a retail Fire TV / Android TV device can: (1) navigate to Playback settings, (2) open the trace entry (once nav is wired), (3) select `INCLUDE_HTTP_SUMMARY`, and (4) start a session. All HTTP URLs and response headers for every integration provider call will be written to the JSONL file in internal app storage (`filesDir/traces/`). Even though bodies are gated, URL parameters (redacted for auth keys) and header names are captured.
- **User-visible impact:** Privacy / data exposure risk on release builds. The JSONL file lives in `context.filesDir` which requires root or `adb backup` to extract; however, a compromised app or a device with developer options enabled could exfiltrate the traces dir.
- **Required fix (two options, pick one):**
  - **Option A (preferred):** Gate the trace settings entry with `if (BuildConfig.IS_DEBUG_BUILD)` in `PlaybackSettingsSections.kt`. In release, the entry does not appear.
  - **Option B:** Restrict available `TraceMode` values in the picker to `OFF` and `SAFE_METADATA_RUNTIME` for non-debug builds. `INCLUDE_HTTP_SUMMARY` and `INCLUDE_HTTP_BODIES_INTERNAL_ONLY` are only shown in debug builds.
- **Test that should catch it:** An architecture scan test that asserts the trace settings navigation callback is only reachable through a `BuildConfig.IS_DEBUG_BUILD` guard in release variants.

---

### Finding I-08: `SecondaryDoesNotOverwritePrimary` validator rule has inverted semantics — it fires on primary-wins scenarios with competition

- **Severity:** P2
- **Evidence:** `TraceValidationRules.SecondaryDoesNotOverwritePrimary` at `TraceValidationRules.kt:171-185` — the rule fires for any `metadata.field_selected` event where `field` is in `{"TITLE", "OVERVIEW", "EPISODE_LIST"}` AND `rejectedCandidates` is non-empty. But `rejectedCandidates` is populated whenever there were multiple candidates and the field resolver chose among them — including when the **primary provider wins** with secondary candidates rejected. The rule name says "secondary does not overwrite primary" but the implementation flags the presence of any competition, not the outcome. A field_selected event where `sourceRole = "PRIMARY"` (primary won) with one rejected secondary candidate will cause a FAIL verdict, which is a false positive. Conversely, a secondary-wins scenario where `rejectedCandidates` happens to be empty (no tracked rejections) would pass, which is a false negative.
- **Violated contract:** The rule as written cannot distinguish between "primary won" and "secondary won." It produces false positives and false negatives for the named invariant.
- **User-visible impact:** If production trace sessions are validated against this rule, legitimate multi-candidate resolution (primary wins, secondary rejected) will produce `FAIL` verdicts and alarm-fatigue. Conversely, a true secondary-overwrite bug could be missed if the `rejectedCandidates` list is empty.
- **Required fix:** Change the filter condition to also require `sourceRole == "SECONDARY"` (or whatever role name signals a non-primary winner). Example: `p["sourceRole"] != "PRIMARY" && rejected.isNotEmpty()`. Alternatively, if `field_selected` always encodes the winner's role in `sourceRole`, the correct invariant is: for protected fields, `sourceRole` must be `"PRIMARY"` (not just "has rejected candidates"). Update the rule name accordingly.
- **Test that should catch it:** Add a test case to `RuntimeTraceValidatorTest` that emits a `metadata.field_selected` for `TITLE` with `sourceRole = "PRIMARY"` and non-empty `rejectedCandidates`, and asserts the validator returns PASS. Conversely, add a test with `sourceRole = "SECONDARY"` that asserts FAIL.

---

### Finding I-09: `JsonlTraceWriter.append()` silently swallows `IOException` — storage-full events are undetectable

- **Severity:** P2
- **Evidence:** `JsonlTraceWriter.kt:21-30` — `out.write(line)` writes to a `PrintWriter`. `PrintWriter` does not throw checked exceptions; it catches `IOException` internally and sets an error flag (`checkError()`). The `append()` method does not call `out.checkError()` after writing. If the underlying storage stream throws (e.g., `ENOSPC` — no space left on device), the write silently fails. The `dropped` counter is only incremented for byte-cap violations, not for IO failures. `FileRuntimeTraceSink.eventsDropped()` delegates to `writer.droppedCount()`, so the UI shows 0 dropped events even when IO is failing.
- **Violated contract:** C-1: "active session captures all emitted events." IO failures silently violate this.
- **User-visible impact:** On a low-storage device or large trace session, events will be silently dropped without any indication in the UI (`Events dropped: 0`). A support engineer receiving a truncated `trace-events.jsonl` has no way to know events were lost.
- **Required fix:** Change `JsonlTraceWriter` to use `BufferedWriter` wrapping `FileWriter` (which throws `IOException`) instead of `PrintWriter`. Wrap `out.write(line); out.flush()` in a try-catch; increment a separate `ioErrors` counter on catch. Expose `ioErrorCount()` from `FileRuntimeTraceSink` and surface it in `RuntimeTraceLiveStatusUiState`.
- **Test that should catch it:** A new `JsonlTraceWriterIoFailureTest` that injects a failing `Writer` (throws `IOException` on `write`) and asserts `ioErrorCount() == 1` and `droppedCount() == 0` (separate counters).

---

### Finding I-10: `emitNormalizerWarning` (F-B-07) has no validator rule consuming `metadata.normalizer_warning` events

- **Severity:** P2
- **Evidence:** `TraceMetadataEvents.kt:143-159` — `emitNormalizerWarning` emits `eventType = "metadata.normalizer_warning"`. `MetadataRequestNormalizer.kt:48` calls it for `ContentType.TV → SERIES` coercions. `TraceValidationRules.ALL` (16 rules) — none reference `"metadata.normalizer_warning"`. `TraceMetadataEventsTest` tests the emit method itself but only verifies payload shape; no validator rule asserts a structural invariant about these events.
- **Violated contract:** No formal contract is violated. The finding is a "validator rule has no event source" flip-side: the event source has no validator consuming it.
- **User-visible impact:** If a future change causes `emitNormalizerWarning` to emit incorrect or missing events, no trace validation failure will be reported.
- **Required fix (two options):**
  - **Option A:** Add a `TraceValidationRule` — e.g., `NormalizerWarningHasContentId` — that asserts every `metadata.normalizer_warning` event carries a non-blank `contentId` and a non-blank `reason`. This is a minimal structural invariant.
  - **Option B:** Accept that `metadata.normalizer_warning` is an informational event not subject to invariant rules, and add a comment to `TraceValidationRules` documenting which event types are intentionally uncovered.
- **Test that should catch it:** If Option A: add a test to `RuntimeTraceValidatorTest` with a `metadata.normalizer_warning` event missing `contentId` and assert `FAIL`.

---

### Finding I-11: `emitScrobbleRejected` production callers exist but no validator rule consumes `playback.scrobble_rejected` events

- **Severity:** P2
- **Evidence:** `SimklScrobbleService.kt:251` and `TraktScrobbleService.kt:297` both call `emitScrobbleRejected`. `TraceMetadataEventsScrobbleRejectedTest.kt` tests payload shape. `TraceValidationRules.ALL` — no rule references `"playback.scrobble_rejected"`. This is the same pattern as I-10 but for the scrobble rejection domain.
- **Violated contract:** Same as I-10.
- **Required fix:** Add a `ScrobbleRejectedHasProfileIds` rule asserting that every `playback.scrobble_rejected` event carries non-null `envelopeProfileId` and `activeProfileId`. Or document as intentionally uncovered (see I-10 Option B).
- **Test that should catch it:** If a rule is added: a test in `RuntimeTraceValidatorTest` with a `playback.scrobble_rejected` event missing `envelopeProfileId`.

---

### Finding I-12: `LocalizationPlanPrecedesProviderSteps` rule is not validated end-to-end against real TVDB episode-path emissions in `RuntimeTraceValidatorRealEmissionTest` (cross-lane E-03)

- **Severity:** P2 (cross-lane: noted in Lane E as E-03)
- **Evidence:** `RuntimeTraceValidatorRealEmissionTest.kt` — the real-emission test drives `MetadataRouter.route()` with a `kitsu:7442` content ID and `ContentType.SERIES` routed via `InMemoryIdMappingStore`. It does NOT drive `TvdbMetadataProviderAdapter.execute()` with `TvdbApiShapes.SERIES_EPISODES_LANGUAGE`, which is the code path that emits the second `emitLocalizationPlan` call (`TvdbMetadataProviderAdapter.kt:84`) and the per-episode `emitFieldSelected` events (`TvdbMetadataProviderAdapter.kt:117`). The `LocalizationPlanPrecedesProviderSteps` rule uses payload key `"provider"` from the `metadata.localization_plan` event (e.g., `"TVDB"`). If a future refactor changes this key name in `TvdbMetadataProviderAdapter.emitLocalizationPlan(provider = "TVDB", ...)` (while keeping the rule lookup unchanged), the real-emission test will not catch it — only the synthetic `RuntimeTraceValidatorLocalizationPlanRuleTest` tests would.
- **Violated contract:** C-7 (end-to-end schema coherence between emission and rule).
- **Required fix:** Extend `RuntimeTraceValidatorRealEmissionTest` with a scenario that drives the TVDB episode-bundle path: mock or stub `TvdbMetadataProviderAdapter.execute()` with a `SERIES_EPISODES_LANGUAGE` step that emits both `emitLocalizationPlan` and per-episode `emitFieldSelected` events, then validate the session includes a PASS result for `LocalizationPlanPrecedesProviderSteps`.
- **Test that should catch it:** The extended `RuntimeTraceValidatorRealEmissionTest` scenario described above.

---

*End of Lane I dossier.*
