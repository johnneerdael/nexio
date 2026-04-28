# Lane I — On-Device Trace Mode

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Phase:** 5
- **Owner task:** Task 33
- **Files inspected:** 30
  - `app/src/main/java/com/nexio/tv/core/trace/` (27 files: `TraceMode.kt`, `TraceSession.kt`, `TraceSessionManager.kt`, `TraceEventEnvelope.kt`, `TraceMetadataEvents.kt`, `TraceCacheDecision.kt`, `RuntimeTraceContext.kt`, `RuntimeTraceContextElement.kt`, `RuntimeTraceContextRequestTaggingInterceptor.kt`, `RuntimeTraceInterceptor.kt`, `RuntimeTraceEventListener.kt`, `RuntimeTraceSink.kt`, `FileRuntimeTraceSink.kt`, `JsonlTraceWriter.kt`, `TraceRedactor.kt`, `TraceHash.kt`, `SourceSurface.kt`, `FirstPaintTracer.kt`, `UnscopedNetworkPolicyGuard.kt`, `RuntimeTraceValidator.kt`, `TraceValidationRule.kt`, `TraceValidationRules.kt`, `TraceValidationReport.kt`, `TraceSummaryGenerator.kt`, `TraceBundleExporter.kt`, `TracedTransport.kt`, `DataStoreTraceModeProvider.kt`)
  - `app/src/main/java/com/nexio/tv/core/di/RuntimeTraceModule.kt`
  - `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt` (interceptor wiring lines 100–207)
  - `app/src/main/java/com/nexio/tv/data/local/TraceSettingsDataStore.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/settings/RuntimeTraceSettingsViewModel.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/settings/RuntimeTraceSettingsScreen.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/settings/RuntimeTraceLiveStatusViewModel.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/settings/RuntimeTraceLiveStatusScreen.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/home/HomeFirstPaintMetadataMapper.kt`

## What changed (per diff map)

`core/trace` is a new package introducing the on-device trace mode (toggle → `TraceSettingsDataStore` → `DataStoreTraceModeProvider` → `TraceSessionManager` → `FileRuntimeTraceSink` → `JsonlTraceWriter` → `RuntimeTraceValidator` → `TraceBundleExporter`). Production emission sites were retrofitted into `DefaultIntegrationRuntime`, `MetadataRouter`, `MetadataIdentityResolver`, `ProviderPlanRunner`, `ResolverOrchestrator`, `FieldResolver`, `ProfileBoundaryEnforcer`, `ContinueWatchingSnapshotService`, and the OkHttp interceptor / event-listener stack. Two recent fix commits hardened the runtime: `2b696f168` + `ad69364f0` reordered the OkHttp interceptors so `RuntimeTraceContextRequestTaggingInterceptor` is the first application interceptor and `RuntimeTraceInterceptor` is registered as a NETWORK interceptor, and `889965176` added `out.flush()` after every JSONL append so a process kill cannot lose buffered events. `ae3f1309c` extracted `metadata.first_paint` emission out of the `domain` layer into a UI-layer wrapper (`HomeFirstPaintMetadataMapper.kt`). `39b0df54a` added `RuntimeTraceValidatorRealEmissionTest`, the end-to-end schema-parity gate that drives real emissions through real sinks and validates the resulting JSONL.

## Contract verdicts

| # | Contract | Verdict | Evidence |
|---|---|---|---|
| 1 | `TraceMode.OFF` ⇒ `activeSink() === NoopRuntimeTraceSink` (zero events) | ✅ | `TraceSessionManager.kt:32` (`state.get()?.sink ?: NoopRuntimeTraceSink`), `:35` (`if (mode == TraceMode.OFF) return`); ProfileBoundaryEnforcer/ContinueWatching short-circuit on `traceSink === NoopRuntimeTraceSink` (`ProfileBoundaryEnforcer.kt:91`); `RuntimeTraceModule.kt:166–174` (`ActiveSessionRuntimeTraceSink` always delegates to `manager.activeSink()`, which returns `NoopRuntimeTraceSink` when no session is active). |
| 2 | `RuntimeTraceContextRequestTaggingInterceptor` is the FIRST application interceptor; `RuntimeTraceInterceptor` is a NETWORK interceptor | ✅ | `NetworkModule.kt:113–127` (default client) — only `IntegrationNetworkPermitInterceptor` precedes the tagging interceptor in the application chain; tagging interceptor at `:123`, network interceptor at `:127`. `NetworkModule.kt:189` + `:205` (playback client) — tagging app-interceptor before redirect interceptor; trace network-interceptor at `:205`. Comment block `:119–122` and `:186–188` documents intent. |
| 3 | `RuntimeTraceContextElement` implements `ThreadContextElement` so the OkHttp interceptor can read the context | ✅ | `RuntimeTraceContextElement.kt:13–27` implements `ThreadContextElement<RuntimeTraceContext?>` with `updateThreadContext`/`restoreThreadContext` swapping a `ThreadLocal<RuntimeTraceContext?>`; `activeOnThread()` exposes it (`:40`); the tagging interceptor consumes that thread-local at `RuntimeTraceContextRequestTaggingInterceptor.kt:25`. |
| 4 | `TraceRedactor` covers the full headers / URL-keys / JSON-body-keys list | ✅ | `TraceRedactor.kt:4–18`. Headers: `authorization, cookie, set-cookie, x-api-key, x-auth-token, x-mdblist-apikey` ✓. URL query keys: `api_key, apikey, token, access_token, refresh_token, client_secret, device_code, user_code, pin` ✓. JSON-body keys cover the same secrets plus `password, email, username` ✓. See F-I-01 for outstanding redaction gaps (Simkl/Trakt header parity). |
| 5 | Body capture gated to `INCLUDE_HTTP_BODIES_INTERNAL_ONLY` AND `BuildConfig.DEBUG = true` | ✅ | `RuntimeTraceInterceptor.kt:79` `if (mode.includesHttpBodies && isInternalBuild) { captureBodySample(...) }`; `RuntimeTraceModule.kt:127` and `:147` bind `isInternalBuild = BuildConfig.DEBUG`; `TraceMode.kt:13` sets `includesHttpBodies = true` only for `INCLUDE_HTTP_BODIES_INTERNAL_ONLY`; `RuntimeTraceInterceptor.kt:97` additionally caps body sample at 64 KiB and silently drops larger payloads. |
| 6 | `JsonlTraceWriter` flushes after every `append` | ✅ | `JsonlTraceWriter.kt:28–30` (`out.write(line); out.flush(); written.addAndGet(...)`) — flush is unconditional, inside the `@Synchronized` block. `close()` (`:37`) re-flushes before close. Confirmed flush-on-append matches commit `889965176`. |
| 7 | `FirstPaintTracer` emission lives in the UI layer, not in `domain` | ✅ | `HomeFirstPaintMetadataMapper.kt:15` (`fun MetaPreview.toFirstPaintHomeDisplayMetadata()`) is the sole UI-layer wrapper that calls the pure domain `MetaPreview.toHomeDisplayMetadata()` then `FirstPaintTracer.recordHomePreview(...)`. The domain extension (`domain/model/HomeDisplayMetadata.kt:21`) is side-effect-free. Commit `ae3f1309c` enforced the move. **Caveat:** placement of the wrapper's only callers is wrong — see F-I-02 (cross-ref F-01 / F-02-01 / F-RF-02). |
| 8 | Every event type the validator inspects has at least one production emission site | ✅ | Cross-checked validator rules in `TraceValidationRules.kt` against emission grep: `metadata.first_paint` ← `TraceMetadataEvents.kt:44` (via `FirstPaintTracer`); `metadata.route_decision` ← `:193` (via `MetadataRouter`); `metadata.field_selected` ← `:159` (via `FieldResolver`); `metadata.identity_resolution` ← `:77`; `metadata.provider_plan` ← `:107`; `metadata.resolver_schedule` ← `:132`; `runtime.operation_start`/`operation_finish` ← `DefaultIntegrationRuntime.kt:133, 139, 216, 222, 332, 338`; `runtime.cache_decision` ← `DefaultIntegrationRuntime.kt:97`; `http.request`/`http.response`/`http.error`/`trace.body_sample` ← `RuntimeTraceInterceptor.kt:36, 65, 52, 101`; `http.timing` ← `RuntimeTraceEventListener.kt:58`; `policy.unscoped_network_call` ← `UnscopedNetworkPolicyGuard.kt:25`; `profile.boundary_check` ← `ProfileBoundaryEnforcer.kt:106`; `continue_watching.snapshot_write|read` ← `ContinueWatchingSnapshotService.kt:1355, 1377`. Cross-ref `red-flags/scan-results.md`: "All validator rules have at least one production emission source." |
| 9 | `RuntimeTraceValidatorRealEmissionTest` (commit `39b0df54a`) PASSes — schema parity confirmed | ✅ | Test file: `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceValidatorRealEmissionTest.kt:51`. Drives `DefaultIntegrationRuntime.call`, `MetadataRouter.route`, `FieldResolver.resolve`, two `ProfileBoundaryEnforcer.validateRequest` calls, and `MetaPreview.toFirstPaintHomeDisplayMetadata()` against a real `FileRuntimeTraceSink`/`JsonlTraceWriter`, reads the JSONL back, and asserts `RuntimeTraceValidator.validate(...).verdict == PASS`. Re-ran via `./gradlew :app:testUniversalDebugUnitTest --tests RuntimeTraceValidatorRealEmissionTest` — UP-TO-DATE (PASS preserved from prior run); JUnit XML present at `review-dossier/06-trace-validator-audit/TEST-com.nexio.tv.core.trace.RuntimeTraceValidatorRealEmissionTest.xml`. |

## Findings

### F-I-01: `TraceRedactor` redaction set lags the actual auth surface

- **Severity:** P2
- **Lane:** I
- **Evidence:** `app/src/main/java/com/nexio/tv/core/trace/TraceRedactor.kt:4–18`; `app/src/test/java/com/nexio/tv/core/trace/TraceRedactorTest.kt` covers only `Authorization`, `api_key`, `access_token`, `refresh_token`. Cross-ref `08-test-matrix.md` F-TM-09.
- **Violated contract:** Contract 4 — "redactor covers Authorization, Cookie, Set-Cookie, X-API-Key, X-Auth-Token, X-MDBList-APIKey, query keys api_key/apikey/token/access_token/refresh_token/client_secret/device_code/user_code/pin, JSON body keys for the same."
- **Observation:** The current header set covers the contract list as written, but inspection of the integration providers shows two real-world auth shapes that are **not** in the set:
  - `simkl-api-key` (Simkl `SimklAuthIntegrationProvider`, all Simkl integration providers under `data/integration/simkl/**`).
  - `trakt-api-key` (Trakt clients in `NetworkModule.kt:227`).
  Neither header is in `redactedHeaders`; both would be emitted in plaintext as part of `redactor.redactHeaders(request.headers.asMap())` whenever `INCLUDE_HTTP_SUMMARY` (or higher) is active. Additionally `redactedJsonKeys` lacks `simkl-api-key`, `trakt-api-key`, `pin_code` (TVDB device-flow alias), and the OAuth body parameter `code` used by Trakt token-exchange POSTs.
- **User-visible impact:** Internal trace bundles uploaded via `TraceBundleExporter` from a debug build (or shared with support) carry plaintext provider API keys for Simkl/Trakt and TVDB pin variants. Because the redactor is the sole choke-point — `RuntimeTraceInterceptor`, `TraceSummaryGenerator`, and `TracedTransport` all delegate to it — a missing key means leakage everywhere.
- **Required fix:** Extend `redactedHeaders` with `simkl-api-key`, `trakt-api-key`, `simkl-client-id`. Extend `redactedJsonKeys` with the Trakt OAuth POST keys (`code`, `client_id`, `pin`). Add the three header cases to `TraceRedactorTest` (closing F-TM-09).
- **Test or report that should catch it:** Parameterised header-name fixture in `TraceRedactorTest` enumerating every header sent by `data/integration/**` providers; CI lint that `BuildConfig.TRAKT_CLIENT_ID` / Simkl secrets never appear in any captured trace JSONL.

### F-I-02: First-paint emission is wired to a router pre-flight, not the canonical first-paint boundary

- **Severity:** P1
- **Lane:** I (cross-ref Lane B)
- **Evidence:** `app/src/main/java/com/nexio/tv/ui/screens/home/HomeFirstPaintMetadataMapper.kt:15` (UI wrapper) is invoked only from `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt:729` and `:749` — inside `HomeViewModel.fetchProviderEnrichmentForPreview`, which immediately calls `metadataRouterFacade.fetchTvEnrichment(...)` (`:723, :743`). The actual Home tile first paint (`buildCatalogItem` in `ModernHomeModels.kt:570`) calls the **pure** `MetaPreview.toHomeDisplayMetadata()` and never touches `FirstPaintTracer`. Cross-references **F-01** (`paths/01-home-row-preview.md`), **F-02-01** (`paths/02-home-visible-item-enrichment.md`), and **F-RF-02** (`red-flags/scan-results.md`).
- **Violated contract:** Trace event taxonomy (`add-runtime-trace-mode` OpenSpec) — `metadata.first_paint(routerExecuted=false, networkExecuted=false)` must mark the addon-only render boundary so the validator rule `PreviewMustNotRouteOrNetwork` can correlate it with the absence of a subsequent `metadata.route_decision` for the same `contentId`. Contract 7 of this lane verifies the wrapper exists in the UI layer (commit `ae3f1309c`); the call-site placement is the residual defect.
- **User-visible impact:**
  1. `PreviewMustNotRouteOrNetwork` becomes a no-op for the Home carousel: every tile that paints from `buildCatalogItem` produces zero `metadata.first_paint` events, so the rule has nothing to evaluate against the router events the same trace eventually records.
  2. Every focused row item produces a `metadata.first_paint(routerExecuted=false)` event immediately followed by `metadata.route_decision`/`metadata.provider_plan`/`runtime.operation_start` for the same `contentId`. Trace recordings cannot distinguish "first paint" from "router pre-flight enrichment of an already-painted tile", and Path 01 vs Path 02 cannot be cleanly separated in audit replays.
  3. The hard-coded `routerExecuted = false` in `FirstPaintTracer.recordHomePreview` is technically truthful for the act of constructing the snapshot in isolation, but co-occurrence with router events makes the event misleading to anyone reading a captured trace.
- **Required fix:** Move `FirstPaintTracer.recordHomePreview(...)` invocation into `buildCatalogItem` (or an adjacent presentation-builder once-per-contentId throttle) inside `ui/screens/home/ModernHomeModels.kt`. Replace the two call sites at `HomeViewModelPresentationPipeline.kt:729, :749` with the pure `item.toHomeDisplayMetadata()` (which is what they actually want — a snapshot for `MetadataSourceContext.addonMetadata`, not a first-paint event).
- **Test or report that should catch it:** New unit/UI test asserting (a) `buildCatalogItem` emits exactly one `metadata.first_paint` per content-id per render, (b) `fetchProviderEnrichmentForPreview` emits zero `metadata.first_paint` events. Strengthen `PreviewMustNotRouteOrNetwork` to assert that any `metadata.first_paint(routerExecuted=false)` is NOT followed within the same operation correlation window by a `metadata.route_decision` for the same `contentId`.

### F-I-03: `RuntimeTraceValidatorRealEmissionTest` is excluded from the audit-task filter

- **Severity:** P1
- **Lane:** I
- **Evidence:** `app/build.gradle.kts:403–410` — the `generateTraceValidatorAudit` task's `includeTestsMatching` filter is restricted to `com.nexio.tv.core.trace.TraceBundleGoldenTest`, so `RuntimeTraceValidatorRealEmissionTest` (added in `39b0df54a`) is not exercised by the audit gate. Cross-ref `06-trace-validator-audit/SUMMARY.md` (P1 follow-up assigned to Lane I).
- **Violated contract:** Audit-gate intent: every validator-affecting test should run under the audit task so a regression in either an emission key name or a validator lookup is caught at gate time. Contract 9 of this lane verifies the test passes when run explicitly; the gap is purely scope-of-gate.
- **User-visible impact:** A future change that drifts the schema between an emission site (e.g. renames `apiShapeId` to `apiShape`, or changes the `scope` enum spelling on `profile.boundary_check`) will pass `TraceBundleGoldenTest` (synthetic events) but break the real-emission test silently — until someone runs `:app:testUniversalDebugUnitTest` directly. The audit summary will report PASS while production traces fail validation.
- **Required fix:** Extend the `includeTestsMatching` filter in `app/build.gradle.kts:403–410` to also include `com.nexio.tv.core.trace.RuntimeTraceValidatorRealEmissionTest`. Optionally widen to `com.nexio.tv.core.trace.*Validator*Test` so any future validator-companion test is auto-included.
- **Test or report that should catch it:** A meta-assertion in the audit task itself asserting that every test class under `core.trace` whose name matches `*Validator*Test` is in the filter. The audit JUnit XML directory should list both `TEST-…TraceBundleGoldenTest.xml` and `TEST-…RuntimeTraceValidatorRealEmissionTest.xml`.

### F-I-04: Negative invariant "bodies absent when `mode != INCLUDE_HTTP_BODIES_INTERNAL_ONLY`" is not asserted by any test

- **Severity:** P2
- **Lane:** I
- **Evidence:** Inspection of `app/src/test/java/com/nexio/tv/core/trace/` — `TraceModeTest.kt` toggles modes; no test exercises an HTTP request through `RuntimeTraceInterceptor` in `INCLUDE_HTTP_SUMMARY` mode and asserts that no `trace.body_sample` event is emitted, nor that `http.request`/`http.response` payloads contain no body field. Cross-ref `08-test-matrix.md` F-TM-10.
- **Violated contract:** Contract 5 of this lane (production code is correct; the regression guard is missing). Trace event taxonomy: bodies must never appear in a release build or in any non-`INTERNAL_ONLY` mode.
- **User-visible impact:** A future change that removes the `&& isInternalBuild` guard on `RuntimeTraceInterceptor.kt:79`, or that adds a body-emit path on a sibling event type, would not be caught by any test. Given the redactor gap (F-I-01) the blast radius is "leak provider auth in plaintext to release-channel trace bundles".
- **Required fix:** Add `RuntimeTraceInterceptorBodyGatingTest` in `app/src/test/java/com/nexio/tv/core/trace/` driving an OkHttp request through a fake chain in each `TraceMode` × `isInternalBuild` combination, asserting `trace.body_sample` is emitted iff both `mode == INCLUDE_HTTP_BODIES_INTERNAL_ONLY` and `isInternalBuild == true`.
- **Test or report that should catch it:** Same as required-fix; add to the `generateTraceValidatorAudit` `includeTestsMatching` filter together with F-I-03.

### F-I-05: Derived OkHttp clients (`okHttpClient.newBuilder()`) are not pinned by an interceptor-survival test

- **Severity:** P2
- **Lane:** I
- **Evidence:** `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt:212–298` (Trakt, Simkl, MDBList, etc. derived clients via `okHttpClient.newBuilder()`); `TraceInterceptorOrderingTest` covers the base client only. Cross-ref `08-test-matrix.md` F-TM-11.
- **Violated contract:** Contract 2 (production wiring is correct because OkHttp's `newBuilder()` preserves both interceptor lists; the regression guard is missing).
- **User-visible impact:** A maintainer who introduces a new derived client and accidentally calls `OkHttpClient.Builder()` from scratch (instead of `okHttpClient.newBuilder()`) — or who calls `.eventListenerFactory(...)` last and overrides the trace one — will silently lose tracing for an entire provider. The audit will not show this regression because the derived client is built lazily under DI.
- **Required fix:** Add `DerivedOkHttpClientTraceWiringTest` asserting that for each `@Named` `OkHttpClient` provided by `NetworkModule`, `client.networkInterceptors().any { it is RuntimeTraceInterceptor }` and `client.interceptors().any { it is RuntimeTraceContextRequestTaggingInterceptor }`.
- **Test or report that should catch it:** Same as required-fix; complementary to F-I-03 and F-I-04 in the audit-task filter expansion.

## Pre-staged findings disposition

- **F-RF-02** (cross-ref **F-01**) — confirmed and folded into **F-I-02** above. The emission site is the wrong boundary; the fix is to relocate the call into `buildCatalogItem` and switch the router-preflight call sites to the pure conversion.

## Cross-references

- Production-path findings carrying first-paint misplacement: `paths/01-home-row-preview.md` (F-01), `paths/02-home-visible-item-enrichment.md` (F-02-01), `red-flags/scan-results.md` (F-RF-02). All three converge on F-I-02.
- Trace validator audit (Task 6): `review-dossier/06-trace-validator-audit/SUMMARY.md` — pre-staged P1 routed here as F-I-03.
- Test matrix gaps routed to Lane I: `review-dossier/08-test-matrix.md` F-TM-09 (→ F-I-01), F-TM-10 (→ F-I-04), F-TM-11 (→ F-I-05).
- Related lanes: A (runtime-control-plane: cache_decision and operation_start emission sites), B (metadata-router: route_decision/provider_plan/field_selected/identity_resolution/resolver_schedule emission sites), F (profile-boundaries: profile.boundary_check emission), G (continue-watching: continue_watching.snapshot_* emission).
- Trace-mode design summary: `review-dossier/07-on-device-trace-design.md`.
- Hardening commits referenced by contracts: `2b696f168` and `ad69364f0` (interceptor ordering — Contract 2), `889965176` (JSONL flush-on-append — Contract 6), `ae3f1309c` (first-paint moved to UI — Contract 7), `39b0df54a` (`RuntimeTraceValidatorRealEmissionTest` added — Contract 9).
