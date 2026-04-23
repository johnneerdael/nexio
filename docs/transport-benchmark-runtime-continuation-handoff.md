# Transport & Benchmark Runtime Continuation Handoff

## Goal

Continue the investigation/implementation path for runtime transport specialization and benchmark-driven playback behavior without losing context.

## What is already confirmed

- `PlayerRuntimeController` initializes transport tuning from benchmark results and threads it into media-source construction.
- Stream switching updates `currentStreamServiceKey` before downstream factory behavior is used.
- VOD cache and warm-ahead are orthogonal but share upstream selection and `PlayerMediaSourceFactory` wiring.
- Benchmark profile write/read/application path is now end-to-end verified:
  - `DebridConfigBenchmarkService.runResolvedCandidateSession()` computes `RuntimeTransportHintsV2` from the best profile and writes it into `DebridConfigBenchmarkResult.summary.runtimeTransportHints`.
  - `DebridConfigBenchmarkService` sets `serviceKey = serviceKeyForBenchmarkProvider(provider)` (RD/PM/TB/ED) and persists benchmark artifact + hints via `DebridConfigBenchmarkStore.saveLatest(...)`.
  - `DebridConfigBenchmarkStore` stores results under provider-specific keys (`debrid_config_benchmark_latest_<provider.storageKey>`), and parses both `capabilityEnvelope` and `runtimeTransportHints` on read (`latestResult(...)`).
  - `PlayerRuntimeController.initializePlayer()` reads `debridConfigBenchmarkStore.latestResult(provider).first()` for all providers, then calls `selectTransportBenchmarkForServiceKey(currentStreamServiceKey, latestResultsByProvider)`.
  - `selectTransportBenchmarkForServiceKey(...)` resolves provider via `benchmarkProviderForServiceKey`, selects latest result, and only uses runtime hints when `isEligibleForSpecialization` is true (hints freshness gate).
  - Returned `capabilityEnvelope` drives `mediaSourceFactory.capabilityEnvelope` + `transportPolicyController`; returned `runtimeTransportHints` are assigned to `currentRuntimeTransportHints`.
  - Stream handoff sets `currentStreamServiceKey = normalizedBenchmarkServiceKey(stream.wrappedProviderId)` in both `switchToSourceStream` and `switchToEpisodeStream`, completing the profile application loop.

## Active execution path (high confidence)

1. **Initialization (`PlayerRuntimeControllerInitialization.kt`)**
   - Reads benchmark cache via `latestResult(provider).first()`.
   - Uses `selectTransportBenchmarkForServiceKey(currentStreamServiceKey, latestResultsByProvider)`.
   - Stores `currentRuntimeTransportHints` from `selectedTransportBenchmark?.runtimeTransportHints`.
   - Assigns `mediaSourceFactory.capabilityEnvelope` and a `TransportPolicyController` based on envelope (or default controller if none).
   - Wires:
     - `mediaSourceFactory.transportPolicyProvider = { reason -> effectiveRuntimeTransportPolicy(reason) }`
     - `mediaSourceFactory.onTransportObservation = { hostScope, transportClass, negotiatedProtocol, connectionHeader -> ... }`

2. **Stream handoff (`PlayerRuntimeControllerStreams.kt`)**
   - `switchToSourceStream(...)` and `switchToEpisodeStream(...)` both set:
     - `currentStreamServiceKey = normalizedBenchmarkServiceKey(stream.wrappedProviderId)`
   - This is the service-key source used during next policy resolution.

3. **Service-key normalization + provider mapping (`BenchmarkServiceKey.kt` + related benchmark models)**
   - `normalizedBenchmarkServiceKey` trims + uppercases only.
   - `benchmarkProviderForServiceKey` currently supports explicit keys: `RD`, `PM`, `TB`, `ED` → `DebridBenchmarkProvider`.

4. **Policy lookup (`PlayerRuntimeTransportPolicy.kt`)**
   - `effectiveRuntimeTransportPolicy(playbackReason)` computes runtime status by calling `resolveRuntimeTransportSpecialization(...)` using:
     - existing specialization preference flag,
     - stream service key,
     - runtime policy hints freshness/scope,
     - current transport observation.
   - It then applies `TransportPolicyController` overrides by playback state and returns final `RuntimeTransportPolicy`.

5. **Specialization resolution (`RuntimeTransportSpecialization.kt`)**
   - Baseline is only replaced when all gates pass:
     - specialization is enabled,
     - hint freshness and eligibility checks pass,
     - service key matches current stream key,
     - observed `hostScope` and `transportClass` match benchmark hint scope/class.
   - Otherwise falls back to baseline.

6. **Specialization transitions (`RuntimeTransportSpecializationTransition.kt`)**
   - Transition helper emits explicit events:
     - `transport_specialization_confirmed`
     - `transport_specialization_mismatch`
     - `transport_specialization_revoked`

7. **Policy behavior (`TransportPolicyController.kt`)**
   - `STARTUP / SEEK / REBUFFER`: urgent path only, `urgentChunkBytes` capped, no prefetch.
   - `STABILIZING`: limited prefetch with half-safe worker counts.
   - `STEADY`: prefetch enabled, more aggressive urgent worker scaling.
   - `applyRuntimeTransportSpecialization(...)` applies hinted worker/size/retry/budget deltas and still enforces safe limits unless `allowUrgentChunkAbove8MiB`.

8. **MediaSource construction and observation (`PlayerMediaSourceFactory.kt`)**
   - `createMediaSource(...)` creates PRDS only for progressive sources and only when both:
     - `useParallelConnections == true`
     - not HLS/DASH.
   - Factory receives:
     - `capabilityEnvelope.maxSafeUrgentWorkers`
     - `capabilityEnvelope.maxSafeUrgentChunkBytes`
     - policy callbacks (`transportPolicyProvider`, `onTransportObservation`, `onReadPositionAdvanced`)
     - startup prefetch gate (`parallelStartupPrefetchUnlocked`) and rebuffer pause logic.

9. **Runtime transport scheduling (`ParallelRangeDataSource`, `SharedParallelTransportManager`, `DualLaneScheduler`)**
   - Observation source: response headers map to transport-class and negotiated protocol.
   - PRDS uses dual lane scheduling:
     - urgent submissions are prioritized,
     - prefetch is blocked while urgent queue is non-empty.

10. **VOD cache / warm-ahead (`PlayerMediaSourceFactory.kt`)**
    - Progressive streams can be wrapped with cache support.
    - Warm-ahead uses separate upstream while progression may use a separate progressive upstream.
    - Cache state/selection is controlled by:
      - `currentVodCacheActive`,
      - `currentProgressiveUpstreamFactory`,
      - `currentWarmAheadUpstreamFactory`.

## Open hypotheses to verify next

- Service key mismatch risk:
  - confirm `wrappedProviderId` used for service key aligns with benchmark payload keys in all paths.
- Specialization mismatch reasons:
  - check telemetry for which guard in `resolveRuntimeTransportSpecialization(...)` fails (`hostScope`, transport class, or fresh/eeligible gate).
- Cache/perf interaction:
  - validate whether warm-ahead/cache changes alter observation patterns that affect specialization confidence.

## Confirmed findings from delegated sessions

### Facade/runtime/media3 handoff call chain (`ses_29831240effe1kLDWSeJ87KfjL`, `bg_820fe63e`)

Play action handoff is consistently:

1. `PlayerScreen` stream list callback dispatches `PlayerEvent.OnSourceStreamSelected` / `OnEpisodeStreamSelected`.
2. `PlayerViewModel.onEvent(...)` forwards to `PlayerRuntimeController.onEvent(...)`.
3. `PlayerRuntimeControllerPlaybackEvents` routes to:
   - `switchToSourceStream(...)`
   - `switchToEpisodeStream(...)`.
4. Both stream-switch methods set:
   - `currentStreamServiceKey = normalizedBenchmarkServiceKey(stream.wrappedProviderId)`
   - `currentStreamUrl`, `currentHeaders`, and `currentStreamType`.
5. They call `mediaSourceFactory.createMediaSource(...)` and then `setMediaSource`/`prepare`.
6. `PlayerRuntimeControllerInitialization.initializePlayer(...)` selects and wires transport state:
   - `selectTransportBenchmarkForServiceKey(currentStreamServiceKey, latestResultsByProvider)`
   - `mediaSourceFactory.capabilityEnvelope`
   - `mediaSourceFactory.transportPolicyProvider = { effectiveRuntimeTransportPolicy(...) }`
   - `mediaSourceFactory.onTransportObservation = { ... }`
7. `PlayerMediaSourceFactory.createMediaSource(...)` configures PRDS only for non-HLS/DASH when parallel mode is enabled and passes policy + observation callbacks into `ParallelRangeDataSource.Factory`.
8. `ParallelRangeDataSource` forwards both to `SharedParallelTransportManager`, which forwards policy to scheduler and observation back to controller.
9. `PlayerRuntimeTransportPolicy` applies specialization with `resolveRuntimeTransportSpecialization(...)` using active service key + current observation.

There was no additional hidden facade layer between `switchTo*Stream` and runtime transport wiring.

### VOD cache lifecycle (`ses_298312259ffe9egSl0mKXKunjG`, `bg_a2e91c5d`)

- Config/state entry points:
  - `PlayerMediaSourceFactory` (runtime cache state + lifecycle flags)
  - `PlayerSettingsDataStore` (`VodCacheSizeMode.ON/OFF`, `vodCacheSizeMb`, parallel settings)
  - `PlayerRuntimeController`/`PlaybackEvents` (`currentProgressiveIsEligibleForWarmAhead`, `currentVodCacheActive`).
- Initialization:
  - `PlayerRuntimeController.initializePlayer(...)` invokes `mediaSourceFactory.warmupVodCacheAsync()`.
  - `startVodCacheInitialization(...)` creates/returns singleton `SimpleCache` in `context.cacheDir/player_vod_cache_v2` with stable migration marker (`stable` mode).
  - `maybeApplyLiveVodCacheCapIncrease()` supports limited cap growth in-session when delta >= 64MB.
- Attachment:
  - `createMediaSource(...)` enables VOD cache only when enabled, progressive (non-HLS/DASH), HTTP(S) URL, and cache initialized.
  - `StableCacheKeyFactory.buildCacheKey(...)` is used with `CacheDataSource`.
- Warm-ahead lifecycle:
  - start: `notifyPlaybackFirstFrameRendered()` → `startVodWarmAheadIfEligible()`.
  - eligibility: cache-on + stream eligible + `policy.warmAheadEnabled` (STEADY) + warmed upstream factory exists.
  - loop: `runWarmAheadLoop(...)` uses `findNextUncachedHole`, `CacheWriter`, 16MB prefetch block, active-read guard.
  - stop: `stopVodWarmAhead()` on source changes, clear/reset/release, and error/disabled states.
- Reconfiguration/clear:
  - `clearVodCache()` and `clearVodCacheInternal(...)` release cache and delete cache directory.
  - `clearVodCacheForContent(contentKey)` removes per-resource spans.
- Observability:
  - `getVodCacheLogState(...)`, periodic telemetry in `maybeRefreshVodTelemetry(...)`, buffer logs include VOD state.

### Media3 reference validation (`ses_298312258ffeQfo1A870wAAaM8`, `bg_d625b185`)

- Confirmed expected architecture: progressive-only custom datasource insertion is intended for `ProgressiveMediaSource.Factory`.
- HLS and DASH are handled by dedicated factories (`HlsMediaSource.Factory`, `DashMediaSource.Factory`).
- The current implementation aligns with platform patterns:
  - progressive routes use `ProgressiveMediaSource.Factory(dataSourceFactory)` with `cacheDataSource` wrapper when enabled,
  - HLS/DASH routes avoid PRDS-style progressive datasource replacement.

## Key anchors to continue from

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeTransportPolicy.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/RuntimeTransportSpecialization.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/RuntimeTransportSpecializationTransition.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/DualLaneScheduler.kt`
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkServiceKey.kt`

## Next practical actions

1. Add focused logs/metrics at transition points for service-key + specialization decision:
    - selected service key,
    - selected benchmark key,
    - policy input/output,
    - mismatch reason + active observation.
2. Run a quick branch-local playback probe with at least two providers to validate transition and gating behavior.
3. Update remaining in-flight investigation branch(es) if needed (especially `bg_2c669780`) and reconcile any remaining call-chain ambiguities.

### Concrete telemetry plan (non-invasive)

- Add structured debug events in:
  - `PlayerRuntimeController.initializePlayer` after `selectTransportBenchmarkForServiceKey(...)` and `selectCapabilityEnvelopeForServiceKey(...)`
  - `PlayerRuntimeController.effectiveRuntimeTransportPolicy` decision points
  - `resolveRuntimeTransportSpecialization` before returning final specialization status
  - `nextRuntimeTransportSpecializationTransition` emission path
- For each event include: `old/new specialization`, `serviceKey`, `hintsScope`, `hintsClass`, `transportClass`, `hostScope`, `freshnessMillis`, `isEligible`, `isScopeSupported` and `reason` enums.
- Add a lightweight, temporary `DebugLogger` entry for PRDS/warm-ahead state transitions in `PlayerMediaSourceFactory`:
  - `currentProgressiveIsEligibleForWarmAhead`
  - `currentVodCacheActive`
  - `currentProgressiveUpstreamFactory`
  - `currentWarmAheadUpstreamFactory`
  - `parallelStartupPrefetchUnlocked`
- Verification target:
  - one session on provider with matching key + stable transport should show `transport_specialization_confirmed`
  - non-matching key/class combinations should emit `transport_specialization_mismatch`
  - repeated failures should emit `transport_specialization_revoked` only after confirmed stabilization logic runs

## Active delegated sessions status

- `bg_820fe63e` (explore, `ses_29831240effe1kLDWSeJ87KfjL`) is **completed**; findings integrated.
- `bg_a2e91c5d` (explore, `ses_298312259ffe9egSl0mKXKunjG`) is **completed**; findings integrated.
- `bg_d625b185` (librarian, `ses_298312258ffeQfo1A870wAAaM8`) is **completed**; findings integrated.
- `bg_a5774d42` (deep, `ses_29831bac5ffeoK8cz02ZTCnyCz`) failed after timeout and is not being retried because the same profile application gap was closed via direct file reads and integrated below.
- `bg_f071f0ac` (quick, `ses_2984528b6ffeXjfSssNiuWzVPO`) failed after timeout; not retried (low priority, no new architecture information required to continue).
- `bg_2c669780` (deep, `ses_2984c09dbffemBJ3oSaum6SDK5`) failed after timeout even after retry; duplicate of already-captured handoff findings, so **not retried**.
- `bg_706f4d58`, `bg_a144b83f`, `bg_088bf6ae`, `bg_111b59e5` failed previously after timeout; their `session_id`s and retry context are kept for continuity.
- `bg_16822199` (deep continuation of external transport/media3 references, `ses_29831a356ffeuc9DlJFhxaOz8L`) failed after timeout. Retry **not planned** because equivalent validated results were already delivered by `bg_d625b185`.
- `bg_ba12c312` (deep, `ses_29831978cffezpSPQU8md6VzvP`) failed after timeout. Retry **not planned** because transport call-chain findings were already delivered by `bg_820fe63e`.

## Relevant docs already tied to this area

- `docs/parallel-connections-playback-lld.md`
- `docs/parallel-connections-playback-addendum.md`
- `docs/vod-cache-playback-lld.md`
- `docs/vod-cache-playback-addendum.md`
- `docs/superpowers/specs/2026-04-03-realdebrid-parallel-transport-probe-design.md`
- `docs/superpowers/plans/2026-03-22-truehd-media3-incremental-handoff-parity.md`
