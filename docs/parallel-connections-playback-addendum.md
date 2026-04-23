# Parallel Connections Playback Addendum

## Scope

This addendum evaluates the follow-up findings against the current Nexio codebase and turns them into directional guidelines for future work.

It should be read alongside:

- `docs/parallel-connections-playback-lld.md`
- `docs/vod-cache-playback-lld.md`

The headline conclusion remains:

- the **architecture seam is correct**
- the **current transport shape is the part that should change next**

Nexio is customizing at the right Media3 seam:

- `DataSource.Factory`

That aligns with Media3's intended customization model for transport-layer behavior rather than forking the playback engine itself.

References:

- Android Media3 customization: <https://developer.android.com/media/media3/exoplayer/customization>
- Android Media3 network stacks: <https://developer.android.com/media/media3/exoplayer/network-stacks>
- Android Media3 analytics: <https://developer.android.com/media/media3/exoplayer/analytics>
- Android Media3 troubleshooting: <https://developer.android.com/media/media3/exoplayer/troubleshooting>
- OkHttp client guidance: <https://square.github.io/okhttp/5.x/okhttp/okhttp3/-ok-http-client/>
- OkHttp dispatcher `maxRequestsPerHost`: <https://square.github.io/okhttp/5.x/okhttp/okhttp3/-dispatcher/max-requests-per-host.html>

## Findings Assessment

## 1. Full-chunk head-of-line blocking

### Verdict

- **Confirmed**

### Why this is confirmed in code

The current transport only exposes a chunk to the active read path once the whole chunk has completed.

Evidence:

- `ParallelRangeDataSource` stores chunk work as `CompletableFuture<DownloadedChunk>`
- `read()` calls `awaitChunkWithRecovery(...)`
- `awaitChunkWithRecovery(...)` blocks on `future.get(...)`
- `downloadChunk(...)` returns `DownloadedChunk(buffer, totalRead)` only when `totalRead >= expectedBytes`

Relevant files:

- `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`

This means the active playback frontier cannot consume the first readable bytes of a chunk while the tail is still downloading. The current model is:

- chunk scheduled
- chunk downloads to completion
- completed chunk is handed to `read()`
- playback can finally consume it

That matches the concern exactly.

### Directional guideline

Treat this as the primary transport redesign target.

Future rule:

- **The playback frontier must become readable on partial progress, not on full logical-chunk completion.**

Recommended direction:

- keep logical chunks for scheduling and accounting
- internally split them into smaller pages
- expose bytes to `read()` based on the contiguous ready frontier
- make timeout/recovery decisions on frontier progress, not on chunk completion

Good starting shape:

- logical chunk: `2-8 MB`
- internal page: `128-512 KB`

## 2. Warm-ahead can compete with playback

### Verdict

- **Confirmed, with nuance**

### What the code actually does

Warm-ahead is not fully unconstrained, but it can still contend with playback in meaningful ways.

Confirmed facts:

- warm-ahead starts after first frame via `mediaSourceFactory.notifyPlaybackFirstFrameRendered()`
- that unlocks `parallelStartupPrefetchUnlocked` and may start `startVodWarmAheadIfEligible()`
- warm-ahead uses `currentProgressiveUpstreamFactory`
- if that upstream is `ParallelRangeDataSource.Factory`, warm-ahead inherits the same parallel range transport behavior
- the playback path and warm-ahead path share the same `OkHttpClient`
- `PlayerMediaSourceFactory` sets `Dispatcher.maxRequestsPerHost = 12`

Relevant files:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`

Important nuance:

- there is only one outer warm-ahead worker thread in `prefetchExecutor`
- but a single warm-ahead `CacheWriter` can still drive a full parallel transport under that layer

So contention can happen at:

- per-host request budget
- bandwidth
- transport memory
- buffer churn

### Directional guideline

Warm-ahead must become a lower-priority lane than frontier playback.

Future rules:

- **Warm-ahead must never consume the same urgency budget as the playback frontier.**
- **Warm-ahead must pause immediately on seek, rebuffer, or weak buffer health.**
- **Warm-ahead concurrency should be capped independently from playback concurrency.**

Recommended first policy:

- warm-ahead outer workers: `1 max`
- warm-ahead effective transport workers: `1` or a distinct reduced policy
- warm-ahead disabled during:
  - startup
  - seek recovery
  - rebuffer
  - unstable buffer periods

## 3. Memory model is optimistic

### Verdict

- **Likely true**

### Why this is likely true

Current settings-side budgeting assumes:

- `bufferCount(connectionCount) = connectionCount + 1`

That is directionally reasonable, but the real transport can hold more state than that simplified model implies.

Code-level contributors:

- `currentChunk`
- `bootstrapChunk`
- queued/completed futures in `chunks`
- `parallelConnections + 1` scheduled look-ahead
- continuation-source overlap on non-zero open/seek
- separate warm-ahead path using cache + upstream transport

Relevant files:

- `app/src/main/java/com/nexio/tv/ui/screens/settings/MemoryBudget.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`

The `MemoryBudget` model is useful as a guardrail, but it should not be treated as a faithful peak-memory model for the current implementation.

### Directional guideline

Move from a simplified count-based estimate toward measured peak accounting.

Future rules:

- **Budgeting must include overlap states, not just steady-state frontier buffers.**
- **Warm-ahead memory must be accounted separately from active playback memory.**
- **The transport should expose peak live-page/live-buffer metrics for real validation.**

Recommended next step:

- instrument peak live transport memory during playback and benchmark runs
- validate budget assumptions on lower-heap Android TV devices

## 4. Static runtime policy is too rigid

### Verdict

- **Confirmed**

### Why this is confirmed in code

Runtime playback currently uses static settings:

- `useParallelConnections`
- `parallelConnectionCount`
- `parallelChunkSizeMb`

Those values are copied once into `PlayerMediaSourceFactory` during initialization and then used as fixed transport inputs.

Relevant files:

- `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`

Benchmarking is also single-profile-shaped at the summary/UI level:

- the config benchmark stores the full matrix
- but it explicitly computes and persists a single `bestProfile`
- settings UI surfaces that best profile as the main summary

Relevant files:

- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkService.kt`
- `app/src/main/java/com/nexio/tv/data/local/DebridConfigBenchmarkStore.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`

### Directional guideline

Keep the benchmark sweep, but stop treating the output as one static runtime answer.

Future rules:

- **Benchmark output should become a capability envelope, not a single recommended profile.**
- **Runtime should choose a policy inside that envelope based on player state.**

Recommended envelope fields:

- maximum safe urgent worker count
- maximum safe prefetch worker count
- maximum safe urgent chunk size
- maximum safe prefetch chunk size
- marginal gain for worker-count increases
- marginal gain for chunk-size increases
- protocol observed during the run
- stability penalty under failure/recovery conditions

## 5. Reserved urgency lane is missing

### Verdict

- **Confirmed**

### Why this is confirmed in code

Current scheduling uses:

- one fixed-size executor created with `Executors.newFixedThreadPool(parallelConnections)`
- one undifferentiated `chunks` map
- equal scheduling treatment for all chunk futures

There is no explicit concept of:

- frontier lane
- speculative lane
- cancelable prefetch budget
- admission control that protects the active read frontier

Relevant file:

- `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`

### Directional guideline

Introduce explicit transport priority classes.

Future rules:

- **The playback frontier must always have reserved execution capacity.**
- **Speculative work must be preemptible.**
- **Far-ahead work must never occupy every worker.**

Recommended first model:

- urgent lane:
  - owns the active read frontier
  - small chunks/pages
  - fast no-progress recovery
- prefetch lane:
  - lower priority
  - cancellable
  - capped independently

## 6. Full-chunk wait timeout is the wrong primary signal

### Verdict

- **Confirmed as a design limitation**

### Why this is confirmed in code

The active data source currently treats chunk availability as a completed-future question:

- `awaitChunkWithRecovery(...)` waits for whole-chunk completion with `future.get(chunkWaitTimeoutMs, ...)`

That makes sense for the current monolithic chunk model, but it is too coarse as the main frontier-health signal.

Important nuance:

- `OptimizedBenchmarkTransport` already has an outer `noProgressFailureTimeoutMs`
- but the playback transport itself still gates the frontier on full chunk completion

Relevant files:

- `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt`

### Directional guideline

Promote **no-progress time** above **not-yet-finished chunk time**.

Future rules:

- **Urgent frontier health must be based on contiguous-byte progress, not chunk completion latency.**
- **Timeouts should trip on lack of forward progress, not just elapsed time waiting for a monolithic future.**

Recommended policy:

- keep whole-operation deadlines for safety
- add short frontier no-progress thresholds
- cancel/resume the urgent suffix when those thresholds are exceeded

## 7. "Parallel connections" currently guarantees parallel requests, not physical sockets

### Verdict

- **Confirmed as an instrumentation gap**

### Why this is confirmed in code

The code definitely issues parallel range requests:

- multiple `OkHttpDataSource` instances
- one shared `OkHttpClient`
- configurable request concurrency via chunk workers

But the code does not currently prove that those requests become distinct physical TCP connections.

That depends on:

- negotiated protocol
- connection reuse
- transport stack behavior

With modern stacks, multiplexing can mean:

- parallel requests over one connection

Relevant files:

- `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`

Media3 network-stack documentation makes that a real possibility, especially with HTTP/2 and HTTP/3-capable stacks.

### Directional guideline

Stop assuming request count equals socket count.

Future rules:

- **Instrumentation must report protocol and connection reuse explicitly.**
- **Benchmark conclusions must distinguish request concurrency from physical connection parallelism.**

Recommended telemetry additions:

- negotiated protocol
- connection reuse / route reuse
- request queue delay
- per-range TTFB
- no-progress intervals
- bytes delivered before cancel/retry

## 8. Existing analytics infrastructure should be reused, not bypassed

### Verdict

- **Confirmed opportunity**

### Why this matters

Nexio already has a runtime collection path that attaches:

- `AnalyticsListener`
- `PlaybackStatsListener`

and records:

- first frame
- playback state changes
- dropped video frames
- audio underruns
- player errors

Relevant file:

- `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollector.kt`

This is the correct place to extend player-side outcome telemetry instead of inventing a separate unrelated reporting path.

### Directional guideline

Build future transport validation on top of the existing runtime collector.

Future rules:

- **Player outcome metrics should continue to come from Media3 analytics listeners.**
- **Transport-specific metrics should be attached alongside, not in place of, player analytics.**

Recommended additions to the existing collector or adjacent transport telemetry:

- frontier throughput
- per-range TTFB
- no-progress gaps
- canceled speculative bytes
- peak transport memory
- protocol / connection reuse

## 9. VOD cache remains the right L2 buffer, but its current role is still transitional

### Verdict

- **Confirmed directionally**

### What is already true in code

The current VOD cache architecture is compatible with the intended long-term shape:

- singleton `SimpleCache`
- `LeastRecentlyUsedCacheEvictor(maxBytes)`
- `CacheDataSource.Factory`
- warm initialization and warm-ahead wrapped around the active progressive upstream

Relevant files:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`

That matches the right layering:

- L1 RAM for player/decoder/live transport windows
- L2 transport for active frontier and speculative range fetching
- L3 disk for longer-lived byte reuse and jitter absorption

### What is not yet true in code

The current VOD cache is still session-scoped in practice because playback shutdown clears it:

- `PlayerRuntimeController.stopAndRelease()`
- `mediaSourceFactory.clearVodCache()`
- `clearVodCacheInternal(...)`

So the current implementation is **disk-backed**, but not yet a true long-lived rolling cache horizon across playback sessions.

### Directional guideline

Keep VOD cache, but narrow and strengthen its job.

Future rules:

- **VOD cache should become a rolling ahead-of-frontier jitter horizon, not a generic “download as much as possible” hole filler.**
- **Warm-ahead should target ahead-of-frontier time and stability, not raw byte accumulation.**
- **Backward seeks should prefer cached bytes; large forward seeks should reset the horizon around the new frontier.**
- **The global app cache limit and the current-item horizon should be modeled separately.**

Capacity planning note:

- for giant progressive titles, even a 5-10 minute disk horizon can translate into many gigabytes at remux-class bitrates
- that is exactly why disk is the right L3 shock absorber and heap is not

## 10. Decode limits remain a separate failure domain

### Verdict

- **Confirmed**

### Why this matters

Even a perfect transport/cache design cannot guarantee smooth playback if:

- device decode capability is insufficient
- the file’s real bitrate or codec complexity exceeds what the device can sustain

Media3 troubleshooting guidance explicitly separates decode capability from pure network/transport issues.

### Directional guideline

Transport tuning and decode certification must remain separate.

Future rules:

- **Every rebuffer/root-cause analysis should distinguish decode pressure from transport/cache starvation.**
- **Benchmarking should score both playback outcome and transport outcome, not collapse everything into throughput.**

## Recommended Future Policy Model

The current codebase should evolve toward a dual-lane policy:

- urgent playback lane
- speculative prefetch lane

At the architectural level, the intended steady-state stack is:

- L1 RAM: normal Media3/player/decoder buffering plus small live transport page windows
- L2 transport: paged urgent/prefetch fetching with reserved frontier capacity
- L3 disk: VOD cache horizon with a global LRU cap

## Startup / join / seek / rebuffer

Recommended direction:

- urgent workers: `2`
- urgent chunk: `4 MB`
- prefetch workers: `0-1`
- prefetch chunk: `8-16 MB`
- warm-ahead: off

Rules:

- urgent lane owns the frontier
- no-progress recovery is aggressive
- speculative work is paused quickly

## Stabilizing playback

Recommended direction:

- urgent workers: `2`
- urgent chunk: `4-8 MB`
- prefetch workers: `1`
- prefetch chunk: `8-16 MB`
- warm-ahead: still off unless buffer is clearly healthy

## Steady playback

Recommended direction:

- urgent workers: `1-2`
- urgent chunk: `8 MB`
- prefetch workers: `1`
- prefetch chunk: `16 MB`
- warm-ahead: on only when buffer remains healthy

## Deep buffer

Recommended direction:

- do not inflate urgent concurrency just because the buffer is deep
- give any extra budget to low-priority prefetch
- revoke speculative budget immediately on seek or rebuffer

## Engineering Guidelines

## Keep

- the Media3 `DataSource.Factory` seam
- the single-source fallback for unsuitable responses
- the shared runtime telemetry path
- the benchmark sweep concept
- the singleton cache-per-directory model
- VOD cache as a retained disk layer in the design direction

## Change

- full-chunk gating
- equal-priority worker scheduling
- static single-profile runtime policy
- simplified memory accounting
- assumption that range-request count equals connection count
- warm-ahead as a generic hole-filler instead of a horizon manager
- benchmarking that bypasses real player outcomes

## Avoid

- moving transport policy into Media3 internals
- tying warm-ahead to the same urgency class as playback
- benchmarking only for average throughput without frontier-latency analysis
- treating larger chunks as inherently better once the frontier becomes tail-latency-sensitive
- treating VOD cache as if it were already a durable multi-session horizon when current code still clears it on stop

## Suggested Phasing

## Phase 1: Instrumentation first

- add protocol and connection-reuse telemetry
- add frontier no-progress telemetry
- add peak transport-memory telemetry
- extend the existing runtime collector with transport-specific metrics
- log enough data to classify each rebuffer as decode pressure, cache starvation, frontier stall, or scheduler contention

## Phase 2: Low-risk hotfixes first

- make warm-ahead strictly lower priority than playback
- pause warm-ahead during startup, seek recovery, rebuffer, and weak-buffer periods
- cap warm-ahead to `1` outer worker and a reduced effective transport policy
- stop treating the current single `bestProfile` as the runtime truth
- while chunks remain monolithic, keep urgent chunks at `8 MB` or below

## Phase 3: Frontier-safe transport

- replace monolithic `DownloadedChunk` readiness with paged readiness
- let `read()` consume bytes from the contiguous ready frontier
- shift timeout/recovery logic toward no-progress on the frontier

## Phase 4: Dual-lane scheduler

- reserve urgent capacity for the playback frontier
- make speculative work cancellable and lower priority
- separate urgent and prefetch budgets
- degrade speculative work before urgent work

## Phase 5: Rolling VOD horizon

- redefine warm-ahead around a rolling jitter horizon
- model both global cache cap and per-item target horizon
- align horizon control to player state and bitrate reality rather than generic hole filling

## Phase 6: Benchmark through the real player path

- run certification through the real Media3 playback path
- score by player outcome plus transport diagnostics
- change benchmark output from `bestProfile` to a capability envelope

## Phase 7: Protocol-aware network stack testing

- A/B current OkHttp against other supported Media3 stacks where practical
- instrument negotiated protocol and connection reuse
- treat “parallel requests” and “parallel sockets” as separate measured questions

## Final Position

The current implementation is still architecturally sound.

The right future move is **not** to relocate the feature to a different integration seam. The right move is to keep the seam and redesign the transport and cache behavior behind it.

The consensus version of that position is:

- keep parallel connections
- keep VOD cache
- make transport frontier-safe and priority-aware
- make VOD cache a disk-backed rolling shock absorber
- keep player-side truth grounded in Media3 analytics and playback outcomes

The most important next change is:

- **make the frontier partially readable and progress-driven**

The second most important change is:

- **separate urgent playback work from speculative warm-ahead work**

The third important correction is:

- **stop treating one benchmark “best profile” as a universal runtime setting**

If those two changes land, the benchmark results should become materially more trustworthy and playback behavior should become less sensitive to one slow chunk tail or one over-eager warm-ahead pass.
