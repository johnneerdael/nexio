# VOD Cache + Parallel Transport + Media3 Facade Design

## 1. Executive summary

This document is the current-code handoff reference for Nexio's progressive playback transport stack as of **April 7, 2026**. It covers four tightly-related pieces of behavior:

1. the **parallel progressive transport**
2. the **Media3-facing facade** that exposes that transport to playback
3. the **VOD cache / warm-ahead layer**
4. the **benchmark + capability-envelope + runtime-specialization path** that is supposed to tune transport behavior per provider/device

### Headline conclusions

- Nexio is customizing the transport at the **correct Media3 seam**: a custom `DataSource.Factory` / `DataSource` path selected by `PlayerMediaSourceFactory` for eligible progressive HTTP playback.
- The concrete Media3-facing facade is `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`.
- The actual transport engine now lives primarily under that facade in `app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`, with `PagedFrontierBuffer` and `SequentialReadCursor` handling the readable contiguous frontier.
- The current VOD cache is a Nexio-owned policy layer built on Media3 cache primitives, but **warm-ahead currently runs over single-connection OkHttp**, not over the parallel transport.
- The most likely explanation for the benchmark gap is **not one single bug**. The highest-signal explanation is a stack of mismatches:
  - the on-device manual benchmark used a **`2 x 16 MiB`** snapshot, while the frontier simulator result used **`2 urgent + 1 prefetch @ 24 MiB`**
  - runtime specialization only unlocks the more aggressive provider-specific path if **service key + host scope + transport class** all confirm
  - several runtime policy knobs are computed but only **partially applied** to the active playback transport

### Benchmark delta that motivated this document

#### On-device benchmark artifact
Source: `benchmark-16.json`

- measured at **2026-04-07 10:08:09**
- provider: `real_debrid`
- optimized config snapshot:
  - `useParallelConnections=true`
  - `parallelConnectionCount=2`
  - `parallelChunkSizeMb=16`
  - `chunkWaitTimeoutMs=2000`
- optimized sustained average throughput: about **200.34 Mbps**
- optimized safe sustained budget: about **174.71 Mbps**

#### Frontier simulator comparison
Source: `~/Scripts/frontiersimulator/traces/run_1775557385965/summary.json`

- host: `108-4.download.real-debrid.com`
- run duration: about **120.5s**
- effective envelope:
  - `maxSafeUrgentWorkers = 2`
  - `maxSafePrefetchWorkers = 1`
  - `maxSafeUrgentChunkBytes = 24 MiB`
  - `maxSafePrefetchChunkBytes = 48 MiB`
- sustained throughput: about **636.64 Mbps**
- safe budget: about **484.70 Mbps**

### Working interpretation

The gap is large enough that it should be treated as a **real implementation/runtime issue until disproven**. But the numbers are also **not apples-to-apples** yet, because the compared runs differ in configuration, stack, and observed host.

The two most important immediate findings are:

1. **The manual on-device benchmark does not automatically apply the best Real-Debrid config-benchmark profile or its runtime hints.** It runs the current player settings snapshot from `DebridBenchmarkSessionRunner`.
2. **The playback runtime computes dynamic transport policy, but not all policy outputs are actually wired into the active transport session.**

---

## 2. Scope, goals, non-goals, terminology

### Scope

This document covers:

- progressive HTTP playback only
- VOD cache for progressive playback
- the custom parallel range transport
- the Media3 playback seam and facade contract
- benchmark/config-benchmark/runtime-specialization logic that influences transport

It does **not** cover:

- HLS or DASH segmented transport internals
- unrelated Media3 audio sink work in `media/`
- implementing a redesign in this document

### Terminology

### “Facade”

In this document, **facade** refers to the Media3-facing transport surface implemented by:

- `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`

That class owns the `DataSource` contract that Media3 sees. The deeper transport engine lives below it.

### “Parallel transport”

The transport path that breaks a progressive file into byte ranges and downloads them concurrently.

### “Warm-ahead”

The background cache-filling loop that writes future progressive bytes into VOD cache after the first frame.

### “Manual benchmark”

The benchmark path centered on:

- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunner.kt`
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt`

This is the path that produced `benchmark-16.json`.

### “Config benchmark”

The matrix benchmark centered on:

- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkService.kt`

This path produces `CapabilityEnvelope` and `RuntimeTransportHintsV2` artifacts intended for later playback specialization.

---

## 3. System ownership and boundaries

### Nexio-owned code

The core transport/cache logic is app-owned Kotlin in `app/src/main/java/com/nexio/tv/`.

Primary files:

- `ui/screens/player/PlayerMediaSourceFactory.kt`
- `ui/screens/player/ParallelRangeDataSource.kt`
- `ui/screens/player/SharedParallelTransportManager.kt`
- `ui/screens/player/PagedFrontierBuffer.kt`
- `ui/screens/player/SequentialReadCursor.kt`
- `ui/screens/player/DualLaneScheduler.kt`
- `ui/screens/player/TransportPolicyController.kt`
- `ui/screens/player/RuntimeTransportSpecialization.kt`
- `ui/screens/player/PlayerTransportBenchmarkSelection.kt`
- `data/repository/benchmark/*.kt`

### Media3-owned building blocks

Media3 provides the framework contracts and cache primitives Nexio plugs into:

- `DataSource`
- `DataSpec`
- `OkHttpDataSource`
- `ProgressiveMediaSource`
- `CacheDataSource`
- `CacheWriter`
- `SimpleCache`
- `ExoPlayer`

### OkHttp-owned building blocks

OkHttp provides the underlying HTTP client used by both playback and benchmark transport.

Relevant runtime setup is in:

- `PlayerMediaSourceFactory.getOrCreateOkHttpClient()`

Notable configuration:

- `maxRequests = 64`
- `maxRequestsPerHost = 12`
- `ConnectionPool(5, 5 minutes)`
- redirects followed manually via interceptor plus standard redirect handling

---

## 4. Subsystem map

```text
PlayerRuntimeController
  -> PlayerMediaSourceFactory
       -> chooses progressive upstream factory
       -> maybe wraps with CacheDataSource / SimpleCache
       -> supplies transportPolicyProvider + observation callbacks
       -> Media3 consumes produced MediaSource

Parallel progressive path
  PlayerMediaSourceFactory
    -> ParallelRangeDataSource.Factory
       -> ParallelRangeDataSource (Media3-facing facade)
          -> SharedParallelTransportManager
          -> PagedFrontierByteStore / PagedFrontierBuffer
          -> SequentialReadCursor
          -> optional bootstrap reuse / continuation pump / fallback pump

Config benchmark path
  DebridConfigBenchmarkService
    -> OptimizedBenchmarkTransport.runConfigProfile(...)
       -> ParallelRangeDataSource.Factory
       -> produces CapabilityEnvelope + RuntimeTransportHintsV2
       -> stores latest result per provider

Manual benchmark path
  DebridBenchmarkService
    -> DebridBenchmarkSessionRunner
       -> OptimizedBenchmarkTransport.runProfile(...)
       -> uses current player settings snapshot
       -> produces `benchmark-16.json`-style result
```

---

## 5. Playback entrypoint and settings propagation

### Settings source

Persistent player/network settings come from `PlayerSettingsDataStore` and are copied into playback at initialization time in:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`

Important fields copied into `PlayerMediaSourceFactory`:

- `useParallelConnections`
- `parallelConnectionCount`
- `parallelChunkSizeMb`
- `vodCacheSizeMode`
- `vodCacheSizeMb`

### Benchmark-derived runtime inputs

At player initialization, the runtime also attempts to load the latest config-benchmark result for the current provider via:

- `PlayerTransportBenchmarkSelection.selectTransportBenchmarkForServiceKey(...)`

If present, it selects:

- `capabilityEnvelope`
- `runtimeTransportHints`

and applies them to:

- `mediaSourceFactory.capabilityEnvelope`
- `transportPolicyController`

Code path:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerTransportBenchmarkSelection.kt`

### Important constraint

The selection is **provider keyed**, not automatically host keyed. But later specialization confirmation is stricter: it requires matching provider/service, host scope, and transport class.

---

## 6. Media3 seam and facade contract

### Primary seam

The progressive transport customization happens at the `DataSource.Factory` seam inside:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`

For eligible non-HLS/non-DASH HTTP URLs, the factory builds:

- `ParallelRangeDataSource.Factory(...)`

Otherwise it falls back to plain OkHttp-backed transport.

### Why this seam is architecturally correct

This keeps Nexio’s transport logic below Media3’s extractor/player stack rather than forking Media3 playback internals. That is the correct ownership boundary for transport policy.

### What the facade owns

`ParallelRangeDataSource` owns the Media3-visible contract:

- `open(DataSpec)`
- `read(buffer, offset, length)`
- `close()`
- transfer callback timing
- resolved URI reporting
- fallback to single-connection behavior when range mode is impossible
- bootstrap reuse and continuation pump orchestration

Source:

- `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`

### What the facade delegates downward

The facade delegates range scheduling/retry/budget behavior to:

- `app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`

and readable-byte ownership to:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/SequentialReadCursor.kt`

### Important architectural split

This split is the current “restored contract with the facade”:

- `ParallelRangeDataSource` = Media3 facade / lifecycle owner
- `SharedParallelTransportManager` = transport engine
- `SequentialReadCursor` = sole reader presented to Media3
- producers write bytes into `AbsoluteByteStore`

That separation is correct and should stay explicit in future work.

---

## 7. End-to-end progressive playback flow

## 7.1 Media source creation

`PlayerMediaSourceFactory.createMediaSource(...)` chooses one of these broad paths:

- local Blu-ray path
- segmented HLS/DASH path
- progressive HTTP path

For progressive HTTP with parallel enabled:

1. resolve current settings / capability envelope
2. build `ParallelRangeDataSource.Factory`
3. maybe wrap upstream with VOD cache via `CacheDataSource`
4. return a `ProgressiveMediaSource`

Relevant file:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`

## 7.2 Open phase inside facade

At `ParallelRangeDataSource.open(...)`:

1. session state is reset
2. a probe source opens the requested URI/range
3. transport observation is emitted from probe response headers
4. the code decides whether ranges are supported
5. the total file length is derived where possible
6. one of three paths is chosen:
   - zero-length successful open
   - fallback single-connection pump
   - full parallel session attach

### Bootstrap / continuation behavior

If the open can stay in parallel mode, the facade reads a small bootstrap window first.

Key constants:

- `BOOTSTRAP_READ_BYTES = 1 MiB`
- `READ_BUFFER_SIZE = 512 KiB`

Then it may:

- write bootstrap bytes directly into the frontier store
- cache startup bootstrap bytes for immediate reopen reuse at position `0`
- reuse the existing probe as a **continuation source** for a non-zero seek open instead of immediately discarding it

This logic lives in:

- `ParallelRangeDataSource.readBootstrapChunk(...)`
- `ParallelRangeDataSource.startContinuationPump(...)`

## 7.3 Read phase inside facade

The `read()` path is intentionally simplified:

- `ParallelRangeDataSource.readInternal(...)` delegates all readable-byte semantics to `SequentialReadCursor`
- `SequentialReadCursor` reads only from contiguous bytes in the store
- if bytes are not yet readable, it blocks on the facade’s wait hook

This keeps Media3 on a single reader path even though multiple producers are publishing into the store.

---

## 8. Internal transport architecture

## 8.1 Shared transport manager

`SharedParallelTransportManager` owns:

- the scheduler
- transport observations
- connection budget throttling
- chunk download retries/backoff
- frontier-blocking promotions

Source:

- `app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`

## 8.2 Scheduler model

The scheduler is implemented by:

- `app/src/main/java/com/nexio/tv/ui/screens/player/DualLaneScheduler.kt`

It has two executors:

- urgent lane
- prefetch lane

But the current attach-time construction is hardcoded as:

- `DualLaneScheduler(parallelConnections, 1)`

This is important because runtime policy later computes `urgentWorkers` and `prefetchWorkers`, but the actual executor pool sizing is not dynamically updated from that policy.

## 8.3 Frontier model

`SharedParallelTransportManager.promoteRanges(...)` translates reader position into chunk scheduling hints:

- current chunk becomes urgent
- additional ahead chunks are queued
- a subset is classified urgent vs non-urgent based on `urgentWorkers`

Important nuance:

- `prefetchWorkers` is **not** used in this scheduling decision
- `maxAhead` is derived from `parallelConnections + 1` and page coverage requirements

So current runtime policy only partially controls the transport.

## 8.4 Retry model

Range downloads retry with transport-specific backoff.

The manager distinguishes:

- transient interruption / connection-reset style failures
- non-transient failures

and applies a different backoff when runtime specialization indicates `CONNECTION_CLOSE` behavior.

This is one of the few dynamic policy knobs that is actually applied continuously during the session.

## 8.5 Connection-budget model

If runtime specialization confirms a provider path with explicit connection-budget guidance, the manager rate-limits new connections per second through:

- `awaitConnectionBudgetIfNeeded()`

This uses `connectionBudgetHint` from `TransportPolicy`.

---

## 9. Byte store and readable frontier

## 9.1 Current reality: page-level readable frontier

The current transport is **not** full logical-chunk head-of-line blocked anymore.

The active readable store is:

- `PagedFrontierByteStore` over `PagedFrontierBuffer`

`PagedFrontierBuffer` works in **128 KiB pages**.

Key behavior:

- bytes are written at absolute offsets
- per-page fill state tracks contiguous low-water plus a pending out-of-order range
- a page becomes readable only when its required bytes are complete
- global `frontier` advances as contiguous completed pages become available

This is strictly better than “wait for the whole 8/16/24 MiB logical chunk before playback can read anything”, but it is still page-based rather than byte-by-byte streaming.

Source:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`

## 9.2 Stale-doc divergence

`docs/parallel-connections-playback-addendum.md` contains findings based on an older transport shape that described full-chunk handoff semantics as confirmed.

That specific conclusion is now stale relative to current code:

- current code uses page-level readable frontier
- logical chunks still exist for scheduling/accounting
- page completion, not logical chunk completion, gates readability

The addendum remains useful for directional reasoning, but not as a literal statement of today’s implementation.

---

## 10. VOD cache architecture and lifecycle

## 10.1 Ownership

The VOD cache policy is owned by:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`

Media3 primitives used underneath:

- `SimpleCache`
- `CacheDataSource`
- `CacheDataSink`
- `CacheWriter`

## 10.2 When cache is used

For eligible progressive HTTP playback:

1. `PlayerMediaSourceFactory` builds the progressive upstream
2. if VOD cache is enabled and available, it wraps that upstream in `CacheDataSource`
3. otherwise it falls back to the raw progressive upstream

This means the cache sits **above** the selected upstream transport.

## 10.3 Warm initialization

The cache can be initialized eagerly via:

- `warmupVodCacheAsync()`
- `startVodCacheInitialization(...)`

## 10.4 Warm-ahead gate

Warm-ahead starts only after:

- first frame rendered → `notifyPlaybackFirstFrameRendered()`

and only if:

- VOD cache is enabled
- the current stream is eligible
- cache is active
- current transport policy allows warm-ahead

Relevant methods:

- `notifyPlaybackFirstFrameRendered()`
- `notifyRebuffer()`
- `startVodWarmAheadIfEligible()`
- `runWarmAheadLoop(...)`

## 10.5 Important current implementation detail

Although the cache may wrap a parallel playback upstream, warm-ahead itself is currently built from:

- `currentWarmAheadUpstreamFactory = okHttpFactory`

not the current progressive upstream.

That means warm-ahead currently runs over **single-connection OkHttp**, not parallel transport.

This is a major current-code fact and an important divergence from some earlier design reasoning.

## 10.6 Rebuffer interaction

On rebuffer, the runtime controller calls:

- `PlayerMediaSourceFactory.notifyRebuffer()`

This updates `lastRebufferUptimeMs`, which the parallel playback transport uses to suppress its own background chunk-prefetch pressure for a configured recovery window.

Important nuance: the currently running VOD warm-ahead loop is not explicitly stopped by `notifyRebuffer()`. Warm-ahead is gated when it starts, but `runWarmAheadLoop(...)` does not re-check rebuffer state on each iteration.

---

## 11. Benchmark architecture

## 11.1 Manual benchmark path

Primary files:

- `DebridBenchmarkService.kt`
- `DebridBenchmarkSessionRunner.kt`
- `OptimizedBenchmarkTransport.kt`
- `DebridBenchmarkMetricsCollector.kt`

Key facts:

- the manual benchmark runs the **optimized transport** only
- it does **not** use config-benchmark profile selection
- it does **not** read the latest `CapabilityEnvelope` or `RuntimeTransportHintsV2`
- it uses the current player settings snapshot through:
  - `PlayerSettings.toBenchmarkTransportConfigSnapshot()`

That is why `benchmark-16.json` reflects the local settings state (`2 x 16 MiB`) rather than the frontier-sim configuration.

## 11.2 Config benchmark path

Primary file:

- `DebridConfigBenchmarkService.kt`

This path:

- runs a provider-specific matrix
- scores profiles
- computes `bestProfile`
- derives `CapabilityEnvelope`
- derives `RuntimeTransportHintsV2`
- stores latest result per provider

For Real-Debrid, the certification matrix currently includes:

- `(2, 16)`
- `(2, 24)`
- `(2, 32)`
- `(3, 16)`
- `(3, 24)`

Source:

- `DebridConfigBenchmarkService.RD_CERTIFICATION_MATRIX`

## 11.3 Capability envelope model

Primary files:

- `CapabilityEnvelope.kt`
- `DebridConfigBenchmarkModels.kt`

The envelope holds:

- max safe urgent workers
- max safe prefetch workers
- urgent chunk bytes
- prefetch chunk bytes
- sustained throughput
- stability penalty
- range support
- timestamp

### Important limitation

`DebridConfigBenchmarkSessionSummary.toCapabilityEnvelope(...)` currently synthesizes a legacy envelope as:

- urgent chunk bytes = `min(best.chunkSizeMb, 8 MiB)`
- prefetch chunk bytes = `best.chunkSizeMb`
- prefetch workers = `1`

So a best profile above `8 MiB` is compressed back down to `8 MiB` in the legacy envelope path.

This is one of the clearest code-level suspects for “why the fast profile is not actually getting applied”.

## 11.4 Runtime transport specialization

Primary files:

- `RuntimeTransportSpecialization.kt`
- `RuntimeTransportSpecializationTransition.kt`
- `PlayerRuntimeTransportPolicy.kt`

Specialization only activates when all of these match:

- active service key
- observed host scope
- observed transport class
- runtime hints are fresh and v2-eligible

Only then can playback unlock behavior such as:

- allowing urgent chunk size above `8 MiB`
- connection-close retry mode
- connection budget hint
- provider-specific prefetch recommendations

This is a strong safety fence, but it is also a strong potential source of under-application.

---

## 12. Stale-doc divergences to carry forward explicitly

The existing docs are useful source material but should not be treated as the current implementation without code cross-check.

### Divergence A — full chunk HOL claim is stale

Older reasoning in `docs/parallel-connections-playback-addendum.md` described full logical chunk completion as the readable frontier gate.

Current code now uses page-level frontier in `PagedFrontierBuffer.kt`.

### Divergence B — warm-ahead transport inheritance is stale

The addendum discusses warm-ahead as potentially inheriting the same parallel upstream path.

Current code explicitly sets:

- `currentWarmAheadUpstreamFactory = okHttpFactory`

So current warm-ahead behavior is single-connection.

### Divergence C — dynamic runtime policy is more aspirational than fully wired

Current docs and naming imply a fuller runtime policy loop than is actually applied. In current code:

- some policy outputs are used continuously (`retryMode`, `connectionBudgetHint`, `urgentWorkers` classification)
- some are only captured at open (`urgentChunkBytes`)
- some appear computed but unused (`prefetchWorkers`, `warmAheadBudgetMax`)

---

## 13. Bottleneck and mismatch register

This section is the most important debugging section in the handoff.

### Confirmed findings

### C1. Manual benchmark path uses current player settings, not the best config-benchmark result

**Confidence:** high

**Evidence:**

- `DebridBenchmarkSessionRunner.run(...)`
- `PlayerSettings.toBenchmarkTransportConfigSnapshot()`
- `benchmark-16.json` shows `parallelConnectionCount=2`, `parallelChunkSizeMb=16`

**Why it matters:**

The compared frontier-sim profile used `24 MiB` urgent chunks and a prefetch lane. The manual benchmark result used the current settings snapshot, so the two results are not configuration-equivalent.

### C2. Legacy envelope synthesis clamps urgent chunk size to `8 MiB`

**Confidence:** high

**Evidence:**

- `DebridConfigBenchmarkSessionSummary.toCapabilityEnvelope(...)` in `DebridConfigBenchmarkModels.kt`

**Why it matters:**

Even if the config benchmark identifies a better chunk size such as `16` or `24 MiB`, the legacy envelope path can collapse the applied urgent chunk back to `8 MiB` unless the newer runtime-hint specialization confirms and overrides it.

### C3. Runtime specialization only applies after exact service + host + transport confirmation

**Confidence:** high

**Evidence:**

- `resolveRuntimeTransportSpecialization(...)`
- `nextRuntimeTransportSpecializationTransition(...)`
- `RuntimeTransportSpecializationTest`

**Why it matters:**

This is likely over-conservative for providers whose CDN aliases/hosts change often. If the current playback host does not exactly match the host captured by the config benchmark result, playback stays on the safer baseline path.

### C4. Executor sizing is fixed at attach time and not reconfigured from runtime policy

**Confidence:** high

**Evidence:**

- `SharedParallelTransportManager.attachSession()` → `DualLaneScheduler(parallelConnections, 1)`
- `DualLaneScheduler.reconfigure(...)` exists but is not called

**Why it matters:**

Transport policy computes `urgentWorkers` and `prefetchWorkers`, but the actual executor pools do not follow those values for the active session.

### C5. `prefetchWorkers` is computed but not directly wired into scheduler sizing or promotion admission

**Confidence:** high

**Evidence:**

- `TransportPolicyController` computes `prefetchWorkers`
- `SharedParallelTransportManager.promoteRanges(...)` uses only `urgentWorkers` to classify scheduled chunks
- attach-time scheduler always includes one prefetch worker

**Why it matters:**

The runtime policy says prefetch should be `0` during startup/rebuffer, but the active transport still has a real prefetch lane and the promotion logic can still classify later work as non-urgent. The runtime policy does not directly size or disable that lane.

### C6. `urgentChunkBytes` is captured on `open()` and not dynamically re-applied later in the same session

**Confidence:** high

**Evidence:**

- `ParallelRangeDataSource.open(...)` sets `activeChunkSize = transportPolicyProvider()?.urgentChunkBytes ?: chunkSize`
- no later reconfiguration of `activeChunkSize` exists in the active session

**Why it matters:**

The transport policy transitions from startup to stabilizing to steady, but chunk size for the session is effectively fixed at open time.

### C7. Warm-ahead currently runs on single-connection OkHttp

**Confidence:** high

**Evidence:**

- `PlayerMediaSourceFactory.kt` sets `currentWarmAheadUpstreamFactory = okHttpFactory`

**Why it matters:**

Any comparison against frontier-sim results that assume a parallel speculative lane inside playback must account for the fact that current warm-ahead does not use the parallel transport.

### C8. Manual benchmark does not persist runtime transport observation metadata the way config benchmark does

**Confidence:** high

**Evidence:**

- `OptimizedBenchmarkTransport.runConfigProfile(...)` captures `observedHostScope`, `observedTransportClass`, `negotiatedProtocol`
- `OptimizedBenchmarkTransport.runProfile(...)` does not surface these into the final result model
- `benchmark-16.json` therefore does not show specialization-relevant transport metadata

**Why it matters:**

This makes it harder to prove whether the on-device benchmark was exercising the same provider/transport class assumptions as playback or config benchmark.

### C10. Rebuffer signaling does not explicitly stop an already running warm-ahead loop

**Confidence:** high

**Evidence:**

- `PlayerMediaSourceFactory.notifyRebuffer()` only updates `lastRebufferUptimeMs`
- `startVodWarmAheadIfEligible()` checks policy only when starting
- `runWarmAheadLoop(...)` does not re-check rebuffer state on each iteration

**Why it matters:**

Even though warm-ahead currently uses single-connection OkHttp, it can still consume bandwidth, cache IO, and thread time during recovery if it was already running before the rebuffer.

### C9. Current memory estimation is simplified and does not fully model overlap/live transport state

**Confidence:** high

**Evidence:**

- `MemoryBudget.totalUsageMb(...)` uses a simple `connectionCount + 1` model
- `DebridConfigBenchmarkMemoryGate` uses that estimate
- `MemoryBudget.peakTransportMemoryMb(...)` exists but is not the gate used by config benchmark

**Why it matters:**

The budgeting model may be conservative or inaccurate relative to the real live transport, affecting which matrix profiles are allowed or recommended.

### Plausible hypotheses

### H1. Real-Debrid specialization may frequently fail to confirm in real playback because host-scope equality is too strict

**Confidence:** medium-high

**Why plausible:**

- user suspicion aligns with this
- code demands exact host scope equality
- observed hosts already differ between compared artifacts (`43-4...` vs `108-4...`)

**What would confirm it:**

- instrument transition logs from `RuntimeTransportSpecializationTransition`
- capture real playback host + transport class + selected hint host/class on affected device

### H2. The effective playback path may spend much of its time on an “8 MiB-safe baseline” even when better envelopes exist

**Confidence:** high

**Why plausible:**

- legacy envelope clamp + strict specialization confirmation + partial runtime-policy application all push in that direction

### H3. The current scheduler shape may be leaving performance on the table even when settings look aggressive

**Confidence:** medium

**Why plausible:**

- executor sizing is static
- policy application is partial
- prefetch semantics are not explicitly tied to `prefetchWorkers`

### Benchmark confounders that must stay separate from implementation bugs

### M1. Different transport stacks

Frontier simulator is Rust/libcurl multi on macOS. On-device benchmark is Android/Media3/OkHttp/custom `DataSource`.

### M2. Different configuration profiles

- on-device artifact: `2 x 16 MiB`
- frontier-sim result: `2 urgent + 1 prefetch @ 24 MiB`

### M3. Different observed hosts / potentially different CDN behavior

- `43-4.download.real-debrid.com`
- `108-4.download.real-debrid.com`

### M4. Different assets

The compared benchmark artifacts reference different titles/assets.

These confounders do **not** explain away the issue, but they do mean the current numbers should not be interpreted as one perfect A/B comparison yet.

---

## 14. Suggested validation and instrumentation follow-ups

These are the best next debugging moves.

### Priority 1 — prove whether playback specialization is actually confirming

Instrument and record on affected playback sessions:

- active service key
- observed host scope
- observed transport class
- selected runtime hint host scope
- selected runtime hint transport class
- whether specialization reached `CONFIRMED`

Relevant files:

- `RuntimeTransportSpecializationTransition.kt`
- `PlayerRuntimeControllerInitialization.kt`

### Priority 2 — surface transport observations in manual benchmark output

Extend manual benchmark result serialization so the final JSON includes:

- observed host scope
- observed transport class
- negotiated protocol
- connection header / inferred connection behavior

Relevant files:

- `OptimizedBenchmarkTransport.kt`
- `DebridBenchmarkSessionRunner.kt`
- `BenchmarkResultJsonLogger.kt`

### Priority 3 — verify whether runtime policy is being partially ignored in live playback

Specifically verify:

- active chunk size after startup → steady transition
- effective urgent/prefetch lane counts in active session
- whether startup/rebuffer really suppresses prefetch work

Relevant files:

- `ParallelRangeDataSource.kt`
- `SharedParallelTransportManager.kt`
- `DualLaneScheduler.kt`
- `TransportPolicyController.kt`

### Priority 4 — run a same-profile on-device comparison

To isolate “profile mismatch” from “implementation overhead”, run an on-device benchmark using the same effective profile as frontier-sim as closely as possible:

- `24 MiB` urgent chunk
- explicit prefetch lane semantics if available
- same provider / same class of asset where possible

### Priority 5 — decide whether host-scope matching should be relaxed

Potential future options:

- provider-level confirmation only
- host family / suffix matching instead of exact host
- transport-class-only confirmation for known providers

This should be treated as a controlled policy decision, not a blind relaxation.

---

## 15. Implementation guidance for future engineers

### Keep these boundaries

Future work should preserve this shape:

- `PlayerMediaSourceFactory` chooses policy + upstream shape
- `ParallelRangeDataSource` owns Media3 contract and session lifecycle
- `SharedParallelTransportManager` owns transport execution
- readable frontier remains separated from producers via `AbsoluteByteStore` / `SequentialReadCursor`

### Avoid these anti-patterns

- moving transport policy directly into Media3 internals
- hiding specialization failures by silently widening scopes without telemetry
- treating config benchmark `bestProfile` as automatically applied playback truth
- assuming old LLD wording is still current without code cross-check

---

## 16. File-by-file reference appendix

### Playback / transport core

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - transport selection, cache wrapping, warm-ahead orchestration, OkHttp client
- `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
  - Media3-facing facade, open/read lifecycle, bootstrap/continuation/fallback orchestration
- `app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`
  - range scheduling, retries, connection budgeting, frontier promotions
- `app/src/main/java/com/nexio/tv/ui/screens/player/DualLaneScheduler.kt`
  - urgent/prefetch executors
- `app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`
  - page-level frontier storage
- `app/src/main/java/com/nexio/tv/ui/screens/player/AbsoluteByteStore.kt`
  - abstraction over frontier store
- `app/src/main/java/com/nexio/tv/ui/screens/player/SequentialReadCursor.kt`
  - sole reader presented to Media3
- `app/src/main/java/com/nexio/tv/ui/screens/player/OpenSession.kt`
  - immutable per-open session metadata

### Runtime policy / specialization

- `app/src/main/java/com/nexio/tv/ui/screens/player/TransportPolicyController.kt`
  - startup/stabilizing/steady policy
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeTransportPolicy.kt`
  - combines base policy + specialization
- `app/src/main/java/com/nexio/tv/ui/screens/player/RuntimeTransportSpecialization.kt`
  - specialization resolution
- `app/src/main/java/com/nexio/tv/ui/screens/player/RuntimeTransportSpecializationTransition.kt`
  - transition status/events
- `app/src/main/java/com/nexio/tv/ui/screens/player/RuntimeTransportObservation.kt`
  - runtime observation model
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerTransportBenchmarkSelection.kt`
  - selects provider-matched latest benchmark result

### Player lifecycle wiring

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - wires settings, capability envelope, runtime hints, first-frame callback
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
  - propagates live settings updates
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`
  - stable/steady transitions, telemetry logs
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerLifecycle.kt`
  - release / teardown stops warm-ahead

### Benchmarking

- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkService.kt`
  - manual benchmark service orchestration
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunner.kt`
  - manual benchmark session and current-settings snapshot application
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt`
  - optimized benchmark readable-source factory and benchmark phases
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkMetricsCollector.kt`
  - throughput/frontier/seek collection
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkService.kt`
  - config benchmark matrix, envelope, runtime hints
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkModels.kt`
  - envelope + runtime hints models and serialization
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/CapabilityEnvelope.kt`
  - capability envelope JSON model
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkMemoryGate.kt`
  - config-benchmark memory admission
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkExecutionGate.kt`
  - single-flight benchmark gate

### Settings / budgeting

- `app/src/main/java/com/nexio/tv/ui/screens/settings/MemoryBudget.kt`
  - simplified memory budget model and peak estimator

### Tests worth reading first

- `app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceContractTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceFrontierPromotionTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceParityTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerWarmAheadBudgetPolicyTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/player/RuntimeTransportSpecializationTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTransportHintsTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkServiceTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransportTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkMemoryGateTest.kt`

---

## 17. Final engineering takeaway

The architecture seam is still correct. The main issue is not “we chose the wrong layer”; it is that the current system has a gap between:

- what benchmark/config-benchmark artifacts can describe
- what runtime policy computes
- what the active playback transport actually applies

If the next engineer only remembers one sentence, it should be this:

> The current performance gap most likely comes from **profile mismatch plus under-applied specialization/policy wiring**, not from the existence of the facade itself.
