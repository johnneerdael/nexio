# On-Device Trace Design

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Phase:** 5 (Lane I)
- **Owner task:** Task 33
- **Scope:** describes the architecture of the on-device trace mode that backs `core/trace`. Findings live in `lanes/I-trace-mode.md` — not here.

## High-level data flow

```
Settings UI ─▶ TraceSettingsDataStore ─▶ DataStoreTraceModeProvider ─┐
                                                                     ▼
                                                          TraceSessionManager ─▶ FileRuntimeTraceSink ─▶ JsonlTraceWriter ─▶ <traces dir>/<sessionId>/trace-events.jsonl
                                                                     │                                                       │
Production emission sites ─────────────────────────────▶ RuntimeTraceSink (active)                                            │
  • DefaultIntegrationRuntime  (runtime.operation_start/finish/failed, runtime.cache_decision)                                │
  • OkHttp interceptor / event listener (http.request/response/error/timing, trace.body_sample)                               │
  • MetadataRouter / IdentityResolver / ProviderPlanRunner / ResolverOrchestrator / FieldResolver (metadata.*)                │
  • FirstPaintTracer ⇐ HomeFirstPaintMetadataMapper (metadata.first_paint)                                                    │
  • ProfileBoundaryEnforcer (profile.boundary_check)                                                                          │
  • ContinueWatchingSnapshotService (continue_watching.snapshot_write|read)                                                   │
  • UnscopedNetworkPolicyGuard (policy.unscoped_network_call)                                                                 ▼
                                                                                                              RuntimeTraceValidator ─▶ TraceValidationReport
                                                                                                              TraceSummaryGenerator ─▶ markdown summary
                                                                                                              TraceBundleExporter   ─▶ zipped bundle for export
```

## Components

### Toggle and persistence

- `app/src/main/java/com/nexio/tv/ui/screens/settings/RuntimeTraceSettingsScreen.kt` and `RuntimeTraceSettingsViewModel.kt` — Compose surface and ViewModel for selecting a `TraceMode` and starting/stopping a session.
- `app/src/main/java/com/nexio/tv/data/local/TraceSettingsDataStore.kt` — Preferences DataStore (`trace_settings`, key `trace_mode`); persists as the enum's `name`. `TraceMode.parse(...)` defaults to `OFF` for unknown values.
- `app/src/main/java/com/nexio/tv/core/trace/DataStoreTraceModeProvider.kt` — collects the persisted mode into a `MutableStateFlow<TraceMode>` (default `OFF`) so callers (`RuntimeTraceInterceptor`, `RuntimeTraceEventListener`) can read `current` synchronously without suspending.

### Mode taxonomy

`app/src/main/java/com/nexio/tv/core/trace/TraceMode.kt`:

- `OFF` — no session, no events.
- `SAFE_METADATA_RUNTIME` — runtime + metadata events only; no HTTP summary, no bodies.
- `INCLUDE_HTTP_SUMMARY` — adds `http.request/response/error` and `http.timing`, headers redacted.
- `INCLUDE_HTTP_BODIES_INTERNAL_ONLY` — additionally captures up to 64 KiB of textual response bodies, redacted; gated to `BuildConfig.DEBUG = true` (`RuntimeTraceInterceptor.kt:79`, bound at `RuntimeTraceModule.kt:127, :147`).

### Session lifecycle

`app/src/main/java/com/nexio/tv/core/trace/TraceSessionManager.kt`:

- Holds an `AtomicReference<State>` of `(TraceSession, FileRuntimeTraceSink)`.
- `start(mode, activeProfileHash)` — generates a UUID session id, mkdirs `<filesDir>/traces/<sessionId>/`, opens `trace-events.jsonl`, and constructs a `FileRuntimeTraceSink` with a 50 MiB cap.
- `stop()` — closes the writer (which flushes), nulls the state.
- `activeSink()` returns `NoopRuntimeTraceSink` when no session is active — this is the choke-point that makes Lane I Contract 1 ("`OFF` ⇒ zero events") true at every emission site.

### Sink + writer

- `app/src/main/java/com/nexio/tv/core/trace/RuntimeTraceSink.kt` — interface plus `NoopRuntimeTraceSink` singleton.
- `app/src/main/java/com/nexio/tv/core/trace/FileRuntimeTraceSink.kt`:
  - Validates `event.traceSessionId == sessionId` on every emit.
  - Assigns a `TraceEventPriority` per event-type prefix (`policy.* = BLOCKER`, `runtime.*`/`metadata.* = HIGH`, `http.* = MEDIUM`, `trace.body_sample = LOW`).
  - Mirrors the last 100 events into a synchronized ring buffer for the live-status UI.
- `app/src/main/java/com/nexio/tv/core/trace/JsonlTraceWriter.kt`:
  - One JSON object per line via Gson.
  - Writes are `@Synchronized`. Non-`BLOCKER` priority is dropped when `written + lineBytes > maxBytes` (50 MiB hard cap, `BLOCKER` always written).
  - **Flushes after every write** (commit `889965176`) so a process kill cannot lose buffered events. `close()` re-flushes.

### OkHttp wiring (the ordering is load-bearing)

- `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt:107–207` (default + playback clients):
  - **Application interceptor (first):** `RuntimeTraceContextRequestTaggingInterceptor` (`addInterceptor`).
  - **Network interceptor:** `RuntimeTraceInterceptor` (`addNetworkInterceptor`).
  - **Event listener factory:** `RuntimeTraceEventListener` (`http.timing`).
- `app/src/main/java/com/nexio/tv/core/trace/RuntimeTraceContextElement.kt`:
  - Coroutine context element that doubles as `ThreadContextElement<RuntimeTraceContext?>`.
  - On dispatch resume, `updateThreadContext` swaps a `ThreadLocal<RuntimeTraceContext?>` so non-coroutine code (the OkHttp app interceptor) can read the active `RuntimeTraceContext` via `RuntimeTraceContextElement.activeOnThread()`.
- `app/src/main/java/com/nexio/tv/core/trace/RuntimeTraceContextRequestTaggingInterceptor.kt`:
  - Reads the thread-local and attaches the `RuntimeTraceContext` to `Request.tag(RuntimeTraceContext::class.java)`. Must be an *application* interceptor so it runs on the calling coroutine's thread, where the thread-local is set.
- `app/src/main/java/com/nexio/tv/core/trace/RuntimeTraceInterceptor.kt`:
  - Runs as a *network* interceptor so it observes the final outgoing request (including auth headers added by app-level interceptors on derived clients).
  - Reads the tag, emits `http.request`/`http.response`/`http.error` (per-mode), and `trace.body_sample` (only if `mode.includesHttpBodies && isInternalBuild`).
  - Reports a `policy.unscoped_network_call` violation via `UnscopedNetworkPolicyGuard` for any request without a `RuntimeTraceContext` tag (skipped when `mode == OFF`).

The combined ordering — commits `2b696f168` + `ad69364f0` — is the post-fix arrangement that Lane I Contract 2 verifies.

### Emission sites (one row per validator-affecting event type)

| Event type | Producer | File:line |
|---|---|---|
| `runtime.operation_start` | `DefaultIntegrationRuntime.startOperation` (call/get/open) | `core/integration/DefaultIntegrationRuntime.kt:133, 216, 332` |
| `runtime.operation_finish` | `DefaultIntegrationRuntime` (terminal phases) | `core/integration/DefaultIntegrationRuntime.kt:139, 222, 338` |
| `runtime.cache_decision` | `DefaultIntegrationRuntime.emitCacheDecision` | `core/integration/DefaultIntegrationRuntime.kt:97` |
| `metadata.first_paint` | `FirstPaintTracer.recordHomePreview` ← `HomeFirstPaintMetadataMapper.toFirstPaintHomeDisplayMetadata` | `core/trace/FirstPaintTracer.kt:31`, `ui/screens/home/HomeFirstPaintMetadataMapper.kt:17` |
| `metadata.identity_resolution` | `MetadataIdentityResolver` | `core/metadata/router/MetadataIdentityResolver.kt:32` |
| `metadata.provider_plan` | `ProviderPlanRunner` | `core/metadata/router/ProviderPlanRunner.kt:15` |
| `metadata.resolver_schedule` | `ResolverOrchestrator` | `core/metadata/router/ResolverOrchestrator.kt:55` |
| `metadata.field_selected` | `FieldResolver` | `core/metadata/router/FieldResolver.kt:74` |
| `metadata.route_decision` | `MetadataRouter` (private `route()` builder) | `core/metadata/router/MetadataRouter.kt:255` |
| `http.request` / `http.response` / `http.error` | `RuntimeTraceInterceptor` | `core/trace/RuntimeTraceInterceptor.kt:36, 65, 52` |
| `http.timing` | `RuntimeTraceEventListener` | `core/trace/RuntimeTraceEventListener.kt:58` |
| `trace.body_sample` | `RuntimeTraceInterceptor.captureBodySample` (gated) | `core/trace/RuntimeTraceInterceptor.kt:101` |
| `policy.unscoped_network_call` | `UnscopedNetworkPolicyGuard` | `core/trace/UnscopedNetworkPolicyGuard.kt:25` |
| `profile.boundary_check` | `ProfileBoundaryEnforcer.emitBoundaryCheck` | `core/integration/ProfileBoundaryEnforcer.kt:106` |
| `continue_watching.snapshot_write` / `_read` | `ContinueWatchingSnapshotService` | `data/repository/ContinueWatchingSnapshotService.kt:1355, 1377` |

### Static-sink install points

`FirstPaintTracer`, `ProfileBoundaryEnforcer`, and `ContinueWatchingSnapshotService` are reached from layers without DI access (pure-domain extensions, low-level enforcers). They expose static `installTraceSink(sink, sessionIdProvider)` slots wired from `RuntimeTraceModule.provideRuntimeTraceSink` (`core/di/RuntimeTraceModule.kt:67–75`) and `provideTraceMetadataEvents` (`:91–117`). The Hilt singleton always returns an `ActiveSessionRuntimeTraceSink` whose `emit` delegates to `manager.activeSink()` — so toggling `OFF` mid-flight short-circuits to the noop sink without any disinstallation.

### Redaction

`app/src/main/java/com/nexio/tv/core/trace/TraceRedactor.kt` — single source of truth for redaction. Used by `RuntimeTraceInterceptor` for headers / URLs / body samples, and by `TraceSummaryGenerator` / `TracedTransport` for derived event surfaces. Header set, URL-query set, and JSON-body-key set are listed in Lane I Contract 4.

### Hashing

`app/src/main/java/com/nexio/tv/core/trace/TraceHash.kt` — `TraceHash.of(sessionId, value)` produces session-salted hashes used wherever a profile id, account id, or credential surfaces in a trace event (e.g. `profileHash`, `credentialTraceHash`, `activeProfileHash` on `profile.boundary_check`; `profileHash` on `continue_watching.snapshot_*`). Salt is generated per-session in `TraceSessionManager.randomSalt()` so traces from different sessions cannot be cross-correlated by hash.

### Validator and reports

- `app/src/main/java/com/nexio/tv/core/trace/RuntimeTraceValidator.kt` — runs the rule list (`TraceValidationRules.ALL`) against a captured event sequence and produces a `TraceValidationReport(verdict, failures, warnings, totalEvents, httpEvents, cacheHits, cacheMisses, staleHits, routeDecisions)`.
- `app/src/main/java/com/nexio/tv/core/trace/TraceValidationRules.kt` — 14 rules, each phrased as a "find events that violate invariant X" filter. Lane I Contract 8 cross-checks each rule against at least one production emission site.
- `app/src/main/java/com/nexio/tv/core/trace/TraceSummaryGenerator.kt` — markdown summary of HTTP request/response counts and per-provider breakdown.
- `app/src/main/java/com/nexio/tv/core/trace/TraceBundleExporter.kt` — zips the JSONL + summary + validator report for export from the settings UI (export action TODO in `RuntimeTraceSettingsScreen.kt:22`).

### End-to-end gate

`app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceValidatorRealEmissionTest.kt` (added in `39b0df54a`) drives real emission sites through a real `FileRuntimeTraceSink`, reads the JSONL back, and asserts the validator returns `PASS`. This is the schema-parity gate between emission key names and validator lookups (Lane I Contract 9). See Lane I F-I-03 for the audit-task-filter follow-up.

## Build-type and BuildConfig dependencies

- `BuildConfig.DEBUG` gates `INCLUDE_HTTP_BODIES_INTERNAL_ONLY` body capture (`RuntimeTraceModule.kt:127, :147`).
- `BuildConfig.DEBUG` gates `UnscopedNetworkPolicyGuard.isInternalBuild` (`RuntimeTraceModule.kt:128`) so policy violations only escalate in internal builds.
- `BuildConfig.VERSION_NAME` and `BuildConfig.BUILD_TYPE` are stamped onto every `TraceSession` via `TraceBuildInfo` (`RuntimeTraceModule.kt:38–44`) so exported bundles carry their provenance.

## Cross-references

- Findings: `review-dossier/lanes/I-trace-mode.md` (F-I-01 … F-I-05).
- Validator audit: `review-dossier/06-trace-validator-audit/SUMMARY.md`.
- Production paths exercising trace events: `review-dossier/paths/01-home-row-preview.md`, `paths/02-home-visible-item-enrichment.md`.
- Trace event taxonomy specification: OpenSpec `add-runtime-trace-mode`.
