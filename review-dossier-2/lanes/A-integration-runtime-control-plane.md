# Lane A — IntegrationRuntime Control Plane

Review SHA: `774a540f8` — Generated 2026-04-29

---

## 1. What changed in this lane on this branch

**New infrastructure added.** The branch delivered a complete control-plane overhaul in `core/integration/`. The primary additions are: `DefaultIntegrationRuntime` (implementing `IntegrationRuntime`) centralising all network dispatch through a single facade; `IntegrationBackoffManager` with exponential backoff + jitter (F-D-06) persisted via Room DAO; `IntegrationSingleFlight` with typed keys (F-D-05) guarding both `get()` and `call()` paths against concurrent redundant network starts; `IntegrationCachePolicy` sealed type (`Disabled`, `ObserveOnly`, `CacheFirst`, `Mutation`) with explicit per-call declaration; and a full `IntegrationApiShapes` registry (`*ApiShapes` objects) replacing all inline string literals (F-C-02). The trace subsystem was wired: `RuntimeTraceContextRequestTaggingInterceptor` (application interceptor) bridges the coroutine-scoped `RuntimeTraceContext` thread-local onto OkHttp request tags; `RuntimeTraceInterceptor` (network interceptor) emits `http.request/response/error` events; and `DefaultIntegrationRuntime` emits `runtime.operation_start/finish/failed` and `runtime.cache_decision` events at every policy branch. YouTube trailer OkHttp clients gained explicit trace wiring (F-I-05). The `IntegrationRuntimeAuditArtifactTest` pin verifies that audit generation is externalized into `buildSrc` logic and that the generated artifact is driven by real runtime-test events rather than synthesized ones.

**Retired or deprecated.** `IntegrationScope.Global` was deprecated with `ReplaceWith` pointing to `GlobalContent`; all production callers were migrated to `GlobalContent`, `GlobalLocalizedContent`, or `GlobalEnglishImage` (F-J-03). The `IntegrationScope.Account(providerAccountId)` secondary constructor was deleted (F-J-02). The unreachable `validateLegacyAccountScope` branch in `ProfileBoundaryEnforcer` was removed (F-F-05 / F-J-02 part 2). Architecture pins were added for: no production use of `IntegrationScope.Global` (F-J-03), every `@Deprecated` carries `ReplaceWith` (F-J-04), `apiShapeId` arguments are property references not literals (F-C-02).

**What remains unchanged.** The `IntegrationNetworkPermitInterceptor` is registered in `AUDIT_ONLY` mode — violations are logged to the trace sink but not thrown. The `IntegrationAuditSink` production binding is `NoOpIntegrationAuditSink` (runtime events are captured only during test via `RecordingIntegrationAuditSink`). The core/tmdb and core/tvdb packages are on the `NoIntegrationRuntimeInjectionOutsideBoundaryTest` allowlist but no file in those packages currently references `IntegrationRuntime` directly. Auth-service carve-outs (`KitsuAuthService`, `RealDebridAuthService`, `SimklAuthService`, `TvdbAuthService`) remain exempt from the Retrofit-usage boundary test.

---

## 2. Architecture surfaces in scope

| Surface | File | Status |
|---|---|---|
| `IntegrationRuntime` interface | `core/integration/IntegrationRuntime.kt` | active |
| `DefaultIntegrationRuntime` | `core/integration/DefaultIntegrationRuntime.kt` | active |
| `IntegrationSpec<T>` | `core/integration/IntegrationSpec.kt` | active |
| `IntegrationCallSpec<T>` | `core/integration/IntegrationCallSpec.kt` | active |
| `IntegrationStreamSpec<T>` | `core/integration/IntegrationStreamSpec.kt` | active |
| `IntegrationCachePolicy` | `core/integration/IntegrationCachePolicy.kt` | active |
| `IntegrationBackoffManager` | `core/integration/IntegrationBackoffManager.kt` | active |
| `IntegrationSingleFlight` + `TypedSingleFlightKey` | `core/integration/IntegrationSingleFlight.kt` | active |
| `IntegrationScope` (sealed) | `core/integration/IntegrationScope.kt` | active (with `Global` deprecated) |
| `IntegrationScope.Global` | `core/integration/IntegrationScope.kt:40` | deprecated — no production callers; F-J-03 pin |
| `IntegrationProvider` enum | `core/integration/IntegrationProvider.kt` | active — 24 providers |
| `IntegrationProviderPolicy` | `core/integration/IntegrationProviderPolicy.kt` | active |
| `IntegrationPolicyRegistry` + `defaultIntegrationPolicyRegistry()` | `core/integration/IntegrationPolicyRegistry.kt` | active |
| `IntegrationHeaderPolicies` | `core/integration/IntegrationHeaderPolicies.kt` | active |
| `IntegrationWorkClass` enum | `core/integration/IntegrationWorkClass.kt` | active |
| `IntegrationPlaybackGate` | `core/integration/IntegrationPlaybackGate.kt` | active |
| `IntegrationNetworkPermit` + `IntegrationHostClassifier` + `IntegrationNetworkPermitInterceptor` | `core/integration/IntegrationNetworkPermit.kt` | active (interceptor in `AUDIT_ONLY` mode) |
| `ProfileBoundaryEnforcer` | `core/integration/ProfileBoundaryEnforcer.kt` | active |
| `IntegrationAuditSink` / `NoOpIntegrationAuditSink` | `core/integration/IntegrationAudit.kt` | active (`NoOp` in production) |
| `IntegrationAuditEvent`, `IntegrationAuditPhase`, `IntegrationOutcome` | `core/integration/IntegrationAudit.kt` | active |
| `*ApiShapes` constant objects | `core/integration/IntegrationApiShapes.kt` | active — 127 shapes across 15 objects |
| `RuntimeTraceContextRequestTaggingInterceptor` | `core/trace/RuntimeTraceContextRequestTaggingInterceptor.kt` | active |
| `RuntimeTraceInterceptor` | `core/trace/RuntimeTraceInterceptor.kt` | active |
| `RuntimeTraceSink` / `NoopRuntimeTraceSink` | `core/trace/RuntimeTraceSink.kt` | active (`Noop` default; real sink injected by `RuntimeTraceModule`) |
| `UnscopedNetworkPolicyGuard` | `core/trace/UnscopedNetworkPolicyGuard.kt` | active |
| `IntegrationRuntimeModule` (Hilt) | `core/di/IntegrationRuntimeModule.kt` | active |
| `RuntimeTraceModule` (Hilt) | `core/di/RuntimeTraceModule.kt` | active |
| Audit harness (`IntegrationRuntimeAuditArtifactTest`) | `app/src/test/java/com/nexio/tv/architecture/IntegrationRuntimeAuditArtifactTest.kt` | active |

---

## 3. Contracts this lane must satisfy

1. Every production HTTP call to a third-party integration provider goes through `IntegrationRuntime.get()`, `.call()`, or `.open()` — no raw Retrofit/OkHttp invocations outside the integration layer.
2. Every `IntegrationSpec`, `IntegrationCallSpec`, and `IntegrationStreamSpec` carries a non-blank `apiShapeId` resolved from a `*ApiShapes` constant (not a string literal).
3. Every `IntegrationSpec`, `IntegrationCallSpec`, and `IntegrationStreamSpec` carries a non-blank `headerPolicyId` from `IntegrationHeaderPolicies`.
4. Every integration provider enumerated in `IntegrationProvider` has a corresponding entry in `defaultIntegrationPolicyRegistry()` — `policyFor()` must never throw `NoSuchElementException`.
5. `IntegrationScope.Global` is not constructed in production code (deprecated; F-J-03 pin).
6. All `@Deprecated` annotations carry `ReplaceWith` (F-J-04 pin).
7. All `OkHttpClient.Builder()` fresh constructions in `NetworkModule` wire the `RuntimeTraceContextRequestTaggingInterceptor` (application interceptor) and `RuntimeTraceInterceptor` (network interceptor) — trace coverage for all integration provider HTTP calls.
8. `IntegrationBackoffManager.clear()` is called on every successful provider load, resetting the exponential backoff schedule (F-D-06).
9. A provider blocked by backoff is blocked on all three runtime entry points (`get()`, `call()`, `open()`), and a successful operation through any entry point resets the block.
10. Cache policy mode (`Disabled` / `ObserveOnly` / `CacheFirst` / `Mutation`) is set explicitly per call; no endpoint shape has an undocumented cache mode.
11. `CacheFirst` specs carry a non-blank `cacheKey` (validated at construction time by `IntegrationSpec.init`).
12. Single-flight keys are typed (`TypedSingleFlightKey`) so two specs sharing a `cacheKey` but with different `mimeType` codecs never collide.
13. `RuntimeTraceContextRequestTaggingInterceptor` is registered as an *application* interceptor (runs before `RuntimeTraceInterceptor` which is a network interceptor) on every OkHttp client that handles integration provider traffic.
14. The `IntegrationNetworkPermitInterceptor` is wired on the base `OkHttpClient` to detect permit-less in-scope network calls.
15. `ProfileBoundaryEnforcer.validateRequest()` is invoked in the `init` block of every spec type.

---

## 4. Generated reports proving (or not) each contract

| Contract | Generated proof | Verdict |
|---|---|---|
| C-1: Every production call through runtime (no raw bypasses) | `generateIntegrationRuntimeAudit` — 0 direct-bypass calls, 93 runtime-covered calls | PASS |
| C-2: `apiShapeId` from `*ApiShapes` constant | `IntegrationApiShapeRegistryCoverageTest` (F-C-02 pin) — scans all production sources for literal `apiShapeId = "..."` patterns | PASS |
| C-3: `headerPolicyId` non-blank | `IntegrationRuntimeAuditArtifactTest.runtime specs expose auditable endpoint shape id` — checks `headerPolicyId must not be blank` in all three spec types; audit reports 0 missing header policies | PASS |
| C-4: Every provider has a policy entry | `IntegrationPolicyRegistryTest` (inferred) — `policyFor()` on all 24 providers in generated audit | PASS |
| C-5: `IntegrationScope.Global` not used in production | `IntegrationScopeGlobalDeprecatedNoCallersTest` (F-J-03) — scans all production sources | PASS |
| C-6: All `@Deprecated` carry `ReplaceWith` | `DeprecatedAnnotationsHaveReplaceWithTest` (F-J-04) — scans all production sources | PASS |
| C-7: Trace interceptors wired on all fresh OkHttp clients in NetworkModule | `DerivedOkHttpClientTraceWiringTest` (F-I-05) — inspects NetworkModule source, 4 fresh builds all wire `traceInterceptor` | PASS |
| C-8: `backoffManager.clear()` on every successful `get()` load | `IntegrationBackoffManagerExponentialTest` + `DefaultIntegrationRuntimeStaleOn429Test` — tests clear after success | PASS for `get()` path only |
| C-9: Backoff reset on successful `call()` or `open()` | No test exists asserting this | UNVERIFIED (see Finding A-01) |
| C-10: Cache policy explicitly set per call, no undocumented modes | `generateIntegrationRuntimeAudit` — 0 missing policies, 0 endpoint-shape mismatches | PASS |
| C-11: `CacheFirst` specs have non-blank `cacheKey` | `IntegrationSpec.init` runtime guard; `DefaultIntegrationRuntimeTest` exercises CacheFirst path | PASS |
| C-12: Typed single-flight keys prevent codec collisions | `IntegrationSingleFlightTest` covers typed key separation | PASS |
| C-13: Tagging interceptor before trace interceptor | `NetworkModule` source — `addInterceptor(taggingInterceptor)` precedes `addNetworkInterceptor(traceInterceptor)` on all four fresh builds | PASS |
| C-14: `IntegrationNetworkPermitInterceptor` wired on base client | `NetworkModule:114-116` — wired as first application interceptor on default `OkHttpClient` | PASS (AUDIT_ONLY mode; see Finding A-02) |
| C-15: `ProfileBoundaryEnforcer.validateRequest()` in all spec `init` blocks | `IntegrationRuntimeAuditArtifactTest.runtime event sample is not synthesized…` confirms spec files contain the guard | PASS |

---

## 5. Manual review still needed

- **Backoff asymmetry**: Verify intentional design decision that `IntegrationBackoffManager.clear()` is NOT called on a successful `IntegrationCallSpec` or `IntegrationStreamSpec` result. If it is intentional (e.g., because `call()` paths use different backoff semantics), add an explicit code comment and a named constant. If unintentional, the fix is straightforward (see Finding A-01).

- **`IntegrationNetworkPermitInterceptor.Mode.ENFORCE` readiness**: The interceptor supports `ENFORCE` mode (throws `IllegalStateException` on permit-less in-scope calls) but is wired in `AUDIT_ONLY`. Determine whether a future milestone should flip this and what the rollout plan is. The `ENFORCE` code path has no dedicated test that verifies the throw contract in production conditions.

- **Allowlist staleness in `NoIntegrationRuntimeInjectionOutsideBoundaryTest`**: `core.tmdb` and `core.tvdb` are on the allowlist but no file in those packages currently references `IntegrationRuntime`. Confirm these are defensive entries for anticipated future work or remove them to tighten the boundary (see Finding A-03).

- **Stale test comment in `DefaultIntegrationRuntimeStreamBackoffTest`**: The comment says "expected to FAIL until Task 3 fixes the catch branch" but the fix (F-A-01, commit `dcbde6603`) was already applied. The test now passes. The misleading comment should be removed.

- **`IntegrationNetworkPermitInterceptor` does not tag the request**: When `Mode.AUDIT_ONLY`, the interceptor reads the permit but does not attach it as a request tag (unlike `RuntimeTraceContextRequestTaggingInterceptor`). This means any downstream network interceptor that might want to read the permit from the request tag cannot do so. Verify this is intentional or whether the permit should also be tagged for future observability.

- **Auth service Retrofit carve-outs**: `KitsuAuthService`, `RealDebridAuthService`, `SimklAuthService`, and `TvdbAuthService` are exempt from `IntegrationBoundaryTest`. Periodically confirm these carve-outs are still necessary and that the auth services are not evolving into general-purpose data-access layers (which would require proper IntegrationRuntime wrapping).

- **`core/tmdb` and `core/tvdb` inject `TmdbIntegrationProvider` / `TvdbIntegrationProvider` directly** (not through `IntegrationRuntime`). These packages call provider methods that themselves call `IntegrationRuntime`. Confirm this layering is the intended pattern and whether the provider facade should be the canonical dependency or whether these services should ultimately inject `IntegrationRuntime` and build specs themselves.

---

## 6. Tests that would catch regression

| Test name | Location | What it locks |
|---|---|---|
| `IntegrationScopeGlobalDeprecatedNoCallersTest` | `architecture/IntegrationScopeGlobalDeprecatedNoCallersTest.kt` | F-J-03: no production construction of `IntegrationScope.Global` |
| `DeprecatedAnnotationsHaveReplaceWithTest` | `architecture/DeprecatedAnnotationsHaveReplaceWithTest.kt` | F-J-04: all `@Deprecated` carry `ReplaceWith` |
| `IntegrationApiShapeRegistryCoverageTest` | `architecture/IntegrationApiShapeRegistryCoverageTest.kt` | F-C-02: `apiShapeId` is a constant reference, never a string literal |
| `DerivedOkHttpClientTraceWiringTest` | `core/network/DerivedOkHttpClientTraceWiringTest.kt` | F-I-05: every fresh `OkHttpClient.Builder()` in `NetworkModule` wires `traceInterceptor` |
| `NoIntegrationRuntimeInjectionOutsideBoundaryTest` | `architecture/NoIntegrationRuntimeInjectionOutsideBoundaryTest.kt` | `IntegrationRuntime` cannot escape approved layers |
| `IntegrationBoundaryTest` | `architecture/IntegrationBoundaryTest.kt` | Retrofit APIs are not used outside integration packages / DI / auth-service carve-outs |
| `NoDirectOkHttpOutsideRuntimeTransportPackagesTest` | `architecture/NoDirectOkHttpOutsideRuntimeTransportPackagesTest.kt` | Raw `OkHttpClient` / `Request.Builder` / `.newCall()` are confined to DI and transport packages |
| `IntegrationRuntimeAuditArtifactTest` | `architecture/IntegrationRuntimeAuditArtifactTest.kt` | Audit build logic is externalized; spec files expose `apiShapeId` + `headerPolicyId`; every shape has a header policy; build fails on `FAIL` verdict |
| `DefaultIntegrationRuntimeStreamBackoffTest` | `core/integration/DefaultIntegrationRuntimeStreamBackoffTest.kt` | F-A-01: stream `open()` exception engages backoff |
| `DefaultIntegrationRuntimeStaleOn429Test` | `core/integration/DefaultIntegrationRuntimeStaleOn429Test.kt` | F-D-01: 429/5xx error path falls back to stale cache |
| `IntegrationCallRuntimeTest` | `core/integration/IntegrationCallRuntimeTest.kt` | `call()` path: backoff block, playback block, network-error-to-backoff |
| `IntegrationBackoffManagerExponentialTest` | `core/integration/IntegrationBackoffManagerExponentialTest.kt` | Exponential growth + jitter + Retry-After precedence |
| `IntegrationSingleFlightTest` | `core/integration/IntegrationSingleFlightTest.kt` | Typed single-flight key, concurrency coalescing, exception propagation |
| `ProfileBoundaryEnforcerTest` | `core/integration/ProfileBoundaryEnforcerTest.kt` | Profile-bound spec must carry matching `profileContext` |
| `IntegrationPolicyRegistryTest` | `core/integration/IntegrationPolicyRegistryTest.kt` | Every `IntegrationProvider` value has a policy entry |

---

## 7. Findings

### Finding A-01: `backoffManager.clear()` not called on successful `call()` or `open()` result

- **Severity:** P1
- **Evidence:** `DefaultIntegrationRuntime.kt:318-320` (`doCallInternal` success branch) and `DefaultIntegrationRuntime.kt:392-395` (`openInternal` success branch) — neither calls `backoffManager.clear(spec.provider, spec.scope)`. Contrast with `DefaultIntegrationRuntime.kt:632-634` in `executeProviderLoad` (the `get()` path) which does call `backoffManager.clear()` on `IntegrationLoadResult.Success`.
- **Violated contract:** C-8 / C-9: "A successful operation through any runtime entry point resets the backoff block."
- **User-visible impact:** If a provider enters backoff state due to a transient error observed on a `get()` call, a later successful `call()` or `open()` to the same provider+scope will NOT clear the backoff. The provider stays blocked for the full backoff window even though the network recovered. For providers like YOUTUBE_TRAILER and SUBTITLE_SOURCE_DOWNLOAD whose runtime-covered calls are exclusively `call()`/`open()` paths (audit: 3 and 2 calls respectively), a single 429/5xx that triggers backoff via any `get()` path for the same provider+scope will lock out the `call()`/`open()` paths until the window expires, resulting in silently missing subtitles or trailer playback.
- **Required fix:** In `doCallInternal`, add `backoffManager.clear(spec.provider, spec.scope)` inside the `is IntegrationCallResult.Success` branch (after the `record()` call). In `openInternal`, add `backoffManager.clear(spec.provider, spec.scope)` after the `spec.open().also { record(...) }` call in the try block. Mirror the pattern from `executeProviderLoad:632-634`.
- **Test that should catch it:** A new test in `IntegrationCallRuntimeTest`: "successful call clears backoff for provider-scope" — note failure of a subsequent `get()` to the same scope still returns normally. Similarly a new test in `IntegrationStreamRuntimeTest`. The existing `DefaultIntegrationRuntimeStreamBackoffTest` only tests that a failure _engages_ backoff; it does not test that a success _clears_ it.

---

### Finding A-02: `IntegrationNetworkPermitInterceptor` is in `AUDIT_ONLY` mode with no plan or test for `ENFORCE`

- **Severity:** P2
- **Evidence:** `NetworkModule.kt:114-117` — `mode = IntegrationNetworkPermitInterceptor.Mode.AUDIT_ONLY`. The `ENFORCE` branch at `IntegrationNetworkPermit.kt:74` throws `IllegalStateException` but is never exercised in production. There is no Gradle property, build variant, or architecture pin documenting when `ENFORCE` mode should be activated.
- **Violated contract:** C-14 (partially): the permit interceptor exists and is wired, but unscoped in-scope calls are only logged (via `UnscopedNetworkPolicyGuard`), not blocked. The `policy.unscoped_network_call` trace event is only emitted when a trace session is active.
- **User-visible impact:** No immediate user impact. Silent degradation risk: a developer can add a raw integration call that bypasses `IntegrationRuntime`, and it will not fail CI. The audit catches direct Retrofit usage via `IntegrationBoundaryTest`, but the permit interceptor was intended to be the runtime enforcement point.
- **Required fix:** Document the migration path in a code comment on `NetworkModule.kt:114`. Add a TODO / tracked issue for switching to `ENFORCE` mode (suggested: debug builds first). Add a unit test that constructs an `IntegrationNetworkPermitInterceptor(mode = Mode.ENFORCE)` and asserts it throws on a permit-less in-scope URL.
- **Test that should catch it:** A new test `IntegrationNetworkPermitEnforceModeTest` that verifies the throw contract. The `DerivedOkHttpClientTraceWiringTest` indirectly covers permit presence via trace context, but not the enforcement path.

---

### Finding A-03: `NoIntegrationRuntimeInjectionOutsideBoundaryTest` allowlist contains stale entries for `core.tmdb` and `core.tvdb`

- **Severity:** Nit
- **Evidence:** `NoIntegrationRuntimeInjectionOutsideBoundaryTest.kt:11-17` — `core.tmdb` and `core.tvdb` are in `allowedPackages`. A grep of all `.kt` files in `core/tmdb/` and `core/tvdb/` shows zero occurrences of the string `IntegrationRuntime`. These packages inject `TmdbIntegrationProvider` and `TvdbIntegrationProvider` (which are in `data.integration.*` and therefore already in the boundary scan's allowed set), not `IntegrationRuntime` itself.
- **Violated contract:** No active contract is violated. The allowlist weakens the boundary guard: if someone later adds a direct `IntegrationRuntime` injection into `core.tmdb` or `core.tvdb`, the test will not catch it.
- **User-visible impact:** None.
- **Required fix:** Remove `"com.nexio.tv.core.tmdb"` and `"com.nexio.tv.core.tvdb"` from the `allowedPackages` set in `NoIntegrationRuntimeInjectionOutsideBoundaryTest`. If `IntegrationRuntime` injection is planned for these packages in an upcoming cluster, add a TODO comment referencing the cluster task instead of silently pre-allowing it.
- **Test that should catch it:** The `NoIntegrationRuntimeInjectionOutsideBoundaryTest` itself after this cleanup.

---

### Finding A-04: Stale test comment in `DefaultIntegrationRuntimeStreamBackoffTest` misrepresents current state

- **Severity:** Nit
- **Evidence:** `DefaultIntegrationRuntimeStreamBackoffTest.kt:17` — "expected to FAIL until Task 3 fixes the catch branch." Commit `dcbde6603` (`fix(runtime): openInternal failures engage backoff`) fixed the catch branch; the test now passes. The comment is a factual misstatement about the current state.
- **Violated contract:** No functional contract violated. The misleading comment could cause a reviewer to suppress or skip the test thinking it is a known-failing pin.
- **User-visible impact:** None.
- **Required fix:** Replace the KDoc comment with one that describes what the test verifies now that the fix is in place — e.g., "F-A-01: stream open() exceptions must engage backoff for the provider+scope so subsequent calls are blocked."
- **Test that should catch it:** No additional test needed; this is a pure documentation fix.

---

### Finding A-05: `ObserveOnly` cache policy is behaviourally identical to `Disabled` — the name misleads

- **Severity:** Nit
- **Evidence:** `DefaultIntegrationRuntime.kt:423-430` — `executeObserveOnly` delegates unconditionally to `executeWithoutCache`, which simply skips cache read and write and calls `executeProviderLoad`. `IntegrationCachePolicy.Disabled` also calls `executeWithoutCache` (line 178). The only observable differences are the trace event label (`OBSERVE_ONLY` vs `BYPASS_DISABLED`) and the presence of a `reason` field in `ObserveOnly`. At the `IntegrationCachePolicy` level, callers may expect `ObserveOnly` to read from cache but suppress writes (as the name implies), which it does not.
- **Violated contract:** No formal contract is violated (the audit shows 0 undocumented cache modes). The risk is that a future developer adds an `ObserveOnly` spec expecting read-cache / suppress-write semantics and is surprised to find the network is always hit.
- **User-visible impact:** Potential future performance regression if `ObserveOnly` is misapplied expecting cache reads.
- **Required fix:** Add a KDoc comment to `IntegrationCachePolicy.ObserveOnly` clarifying that this is a network-pass-through policy (identical to `Disabled` in execution) whose purpose is to carry a documented `reason` string for audit and tracing. If read-cache / suppress-write semantics are desired in the future, a separate policy variant (e.g., `ReadCacheNeverWrite`) should be added. Optionally rename to `ObserveNetwork` to clarify intent — though that is a wider refactor.
- **Test that should catch it:** A comment/doc change; no test needed for the nit itself.

---

*End of Lane A dossier.*
