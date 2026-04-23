# Parallel Connections Playback LLD

## Purpose

This document explains Nexio's "parallel connections" playback feature end-to-end:

- what it does
- where the heavy lifting lives
- how it plugs into Media3
- how it interacts with VOD cache and benchmark code
- which files/modules own the implementation
- what is custom Nexio behavior versus stock Media3 behavior

The short answer is:

- **The heavy lifting is done by Nexio code, not by a stock Media3 feature toggle.**
- **Media3 is the playback framework and integration seam.**
- **OkHttp is the transport primitive used underneath Media3's `OkHttpDataSource`.**

For progressive HTTP playback, Nexio creates multiple concurrent byte-range requests and feeds the resulting bytes back through the Media3 `DataSource` contract. Media3 itself does not provide a standard "parallel progressive connections" feature that Nexio simply turns on.

## Executive Summary

### What problem this solves

Some debrid/CDN sources appear to be throughput-limited per TCP connection. A single progressive `GET` stream may underutilize available network capacity, especially for large MKV/MP4 files. Nexio addresses that by splitting the file into byte ranges and downloading multiple ranges in parallel.

### What improves playback

For eligible progressive streams:

- startup can become faster because the first window is read immediately and later bytes are prefetched
- sustained throughput can increase because multiple concurrent range requests can exceed the throughput of one connection
- seek recovery can improve because the transport can resume from a new offset and continue fetching chunk windows in parallel

### What does not use this path

This is intentionally **not** the path for segmented streaming formats that are explicitly routed away from the custom progressive path:

- HLS
- DASH

More precisely:

- `PlayerMediaSourceFactory` selects the custom data source for non-HLS/non-DASH HTTP playback when the setting is enabled
- `ParallelRangeDataSource.open(...)` then decides whether it can actually remain in parallel mode or must fall back to a single upstream connection based on range support and known length

## Ownership: Nexio vs Media3

## Nexio-owned implementation

The core implementation is app-owned Kotlin:

- `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/settings/MemoryBudget.kt`
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt`
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkService.kt`

These files implement:

- the user-facing settings
- memory gating
- runtime wiring
- the custom multi-range data source
- benchmark reuse of the same transport concept

## Media3-owned integration seams

Media3 provides the contracts and higher-level playback engine:

- `DataSource`
- `DataSpec`
- `OkHttpDataSource`
- `DefaultDataSource`
- `DefaultMediaSourceFactory`
- `ProgressiveMediaSource`
- `HlsMediaSource`
- `DashMediaSource`
- `CacheDataSource`
- `CacheWriter`
- `SimpleCache`
- `ExoPlayer`

Media3 is therefore the **consumer** of Nexio's custom transport, not the owner of the feature itself.

## Is this a standard Media3 feature?

No, not for progressive playback in the way Nexio uses it.

Stock Media3 already supports:

- segmented fetching for HLS/DASH/SmoothStreaming
- the `DataSource` abstraction
- OkHttp-backed network data sources
- caching and prefetch infrastructure

What Nexio adds is:

- a custom `DataSource` implementation that performs **multiple concurrent HTTP range reads for a single progressive asset**
- retry/resume logic per range chunk
- bootstrap-window reuse across immediate startup reopens
- integration with Nexio's VOD warm-ahead logic
- benchmark instrumentation for different connection-count/chunk-size profiles

So the feature is best described as:

- **Nexio custom transport built on top of Media3 extension points**

## Module and Dependency View

## App module ownership

The feature is implemented in the `app` module, not in a separate Kotlin utility module or a third-party module.

Relevant dependency wiring is in:

- `app/build.gradle.kts`

Important dependencies:

- `androidx.media3:media3-datasource`
- `androidx.media3:media3-datasource-okhttp`
- `androidx.media3:media3-exoplayer`
- `androidx.media3:media3-exoplayer-hls`
- `androidx.media3:media3-exoplayer-dash`
- `okhttp`

## Local Media3 fork note

The project can optionally substitute Media3 modules from the local `media/` tree via:

- `settings.gradle.kts`

That substitution includes modules like:

- `media3-exoplayer`
- `media3-datasource`
- `media3-datasource-okhttp`

and a custom audio sink artifact:

- `androidx.media3:media3-exoplayer-kodi-cpp-audiosink`

However, the parallel-connections feature itself is **not implemented inside that fork**. The custom logic still lives in app code in `ParallelRangeDataSource.kt` and its surrounding wiring.

## Third-party module note

There is no dedicated third-party Kotlin module implementing parallel connections.

Examples of third-party modules in the app that are unrelated to this feature:

- `ass-media` for ASS subtitle support
- `nextlib-mediainfo` for probing/AFR support

Those do not implement the parallel transport.

## End-to-End Flow

## 1. User settings are stored

Network settings are defined in:

- `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`

Key fields:

- `useParallelConnections`
- `parallelConnectionCount`
- `parallelChunkSizeMb`

Defaults:

- parallel enabled by default: `true`
- default connection count: `2`
- default chunk size: `16 MB`
- supported connection-count range: `2..4`
- supported chunk-size range: `8..128 MB`

Settings are persisted in DataStore using:

- `use_parallel_connections`
- `parallel_connection_count`
- `parallel_chunk_size_mb`

There is also a migration path that enables parallel connections by default for migrated installs.

## 2. Settings UI exposes the feature

The visible settings UI lives in:

- `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`

This screen exposes:

- a toggle for enabling/disabling parallel connections
- a slider for connection count
- a slider for chunk size

The view model logic lives in:

- `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`

This layer enforces consistency between:

- target playback buffer size
- chunk size
- connection count

## 3. Memory budget is enforced

Memory coordination is centralized in:

- `app/src/main/java/com/nexio/tv/ui/screens/settings/MemoryBudget.kt`

The budget model assumes:

- `ParallelRangeDataSource` will keep up to `parallelConnections + 1` chunk-sized buffers alive
- total memory usage is approximated as:
  - playback buffer
  - plus parallel chunk overhead when enabled

Key design detail:

- chunk size is reduced first to fit the safe budget
- buffer size is reduced only as a last resort

This matters because the parallel transport can be materially memory-heavy for large chunk sizes.

## 4. Player initialization copies settings into the media-source factory

At playback initialization, Nexio reads `PlayerSettings` and pushes the relevant fields into:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`

Specifically:

- `mediaSourceFactory.useParallelConnections = playerSettings.useParallelConnections`
- `mediaSourceFactory.parallelConnectionCount = playerSettings.parallelConnectionCount`
- `mediaSourceFactory.parallelChunkSizeMb = playerSettings.parallelChunkSizeMb`

This is the runtime handoff from stored settings into the playback transport configuration.

## 5. Playback requests route through `PlayerMediaSourceFactory`

The main media-source assembly lives in:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`

`createMediaSource(...)` decides which Media3 pipeline to build based on URL and stream type.

Decision split:

- local/non-HTTP: use standard base data source
- HLS: build `HlsMediaSource`
- DASH: build `DashMediaSource`
- progressive HTTP + parallel enabled: build `ParallelRangeDataSource.Factory`
- progressive HTTP + parallel disabled: use plain `OkHttpDataSource.Factory`

This is the most important ownership boundary in the system:

- Media3 still builds the `MediaSource`
- Nexio swaps the **progressive network `DataSource.Factory`**

## 6. ExoPlayer consumes the resulting MediaSource normally

The player itself is still a normal Media3 `ExoPlayer`.

The initial playback path in:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`

does the following:

1. create the `MediaSource` via `mediaSourceFactory.createMediaSource(...)`
2. call `player.setMediaSource(...)`
3. call `prepare()`

The same factory is also reused for:

- stream switching in `PlayerRuntimeControllerStreams.kt`
- subtitle-refresh media-source rebuilds in `PlayerRuntimeControllerTrackSelection.kt`

That means the same parallel transport decision is used not only on first play, but also on subsequent source rebuilds.

## Core Transport Design

## Class

The transport is implemented by:

- `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`

It is a Media3 `DataSource` implementation.

That choice is important because it means Nexio did not fork ExoPlayer playback logic to get this feature. It instead injected custom behavior at the exact network abstraction Media3 already supports.

## High-level behavior

When opened for an eligible progressive request, the data source:

1. performs an initial upstream open using a normal `OkHttpDataSource`
2. captures the final redirected URI
3. verifies the server supports ranges and returns a known length
4. reads a bootstrap window immediately
5. schedules future chunks as concurrent range requests
6. serves the combined data back through the normal `read(...)` contract expected by Media3

If the source is not suitable for parallelization, it falls back to a single upstream connection.

## Open path details

On `open(dataSpec)`:

- previous in-flight chunks are canceled
- the executor is recreated if necessary
- an optional bootstrap cache may be reused for immediate reopen scenarios
- otherwise a probe `OkHttpDataSource` is opened with the original `DataSpec`

The probe serves several purposes:

- obtain `openLength`
- inspect response headers
- detect range support via `Accept-Ranges` or `Content-Range`
- capture the resolved redirect target via `probeSource.uri`

Fallback conditions:

- unknown total length
- no range support

In those cases, `fallbackSource = probeSource` and the transport behaves like a normal single-source reader.

## Bootstrap window

If the source is eligible for parallel mode, the class immediately reads a small startup window from the probe connection.

Important constants:

- `BOOTSTRAP_READ_BYTES = 1 MB`
- startup bootstrap size is bounded by:
  - file length
  - configured chunk size
  - the bootstrap cap

This bootstrap window exists to reduce immediate reopen/startup churn.

Two special cases are handled:

- startup from position `0`
- reopen/seek from a non-zero position

At position `0`:

- a bootstrap cache entry may be stored and reused by the factory for a short time window

At non-zero positions:

- the already-open probe is retained as `continuationSource`
- playback can continue reading directly from that probe until the end of the active chunk window

This is a practical optimization. It avoids discarding the already-open upstream source and re-requesting the same chunk from the beginning after a seek/open.

## Chunk model

The file is conceptually partitioned into fixed-size chunks:

- chunk index = `position / chunkSize`

Each chunk is downloaded independently using a separate `OkHttpDataSource` instance and a `DataSpec` whose:

- `uri` is the resolved redirect target when available
- `position` is the chunk start plus any resumed offset
- `length` is the number of remaining bytes in that chunk

This is the core "parallel connections" behavior.

## Scheduling strategy

Background scheduling uses:

- `maxAhead = parallelConnections + 1`

So if the configured connection count is `4`, the data source may keep the current chunk plus additional chunks ahead of it in flight.

Chunk futures are stored in:

- `ConcurrentHashMap<Long, CompletableFuture<DownloadedChunk>>`

Workers run on:

- `Executors.newFixedThreadPool(parallelConnections)`

This means connection count directly controls transport concurrency.

## Read path

`read(...)` serves bytes in this order:

1. fallback source if parallel mode is disabled/fallback is active
2. bootstrap chunk if current position falls inside the cached bootstrap window
3. continuation source if a non-zero seek/open reused the original probe
4. scheduled chunk futures for the current chunk index

When a chunk is needed:

- it is scheduled if not already present
- the future is awaited with timeout/recovery logic
- old chunks behind the current read position are cleaned up and their buffers are returned to the pool
- more forward chunks are scheduled

From Media3's point of view, it is still just calling `read(buffer, offset, length)`.

## Buffer-pool design

The transport uses a pooled `ByteArray` strategy to reduce GC pressure:

- `ConcurrentLinkedDeque<ByteArray>`
- maximum idle pool size = `parallelConnections + 2`

Important nuance:

- the pool size only limits how many **idle** buffers are recycled
- actual concurrent memory footprint is driven by the number of in-flight chunks (`maxAhead`) and current chunk retention

This is why the separate `MemoryBudget` enforcement exists in settings and benchmark code.

## Failure handling and recovery

### Chunk-level recovery

Chunk downloads are resumable within the chunk.

If a chunk fails after partial progress:

- the next attempt reopens at `start + totalRead`
- it does not restart the entire chunk from zero

This is one of the most important implementation details because it preserves already downloaded bytes and makes transient resets much cheaper.

### Recoverable failures

Recoverable categories include:

- `InterruptedIOException`
- `InterruptedException`
- `SocketException`
- `EOFException`
- `ProtocolException`
- message patterns such as:
  - `connection reset`
  - `connection closed`
  - `unexpected end of stream`
  - `broken pipe`

### Retry budgets

The data source distinguishes:

- transient/recoverable attempts
- non-transient attempts

Constants:

- max transient chunk attempts: `4`
- max non-transient attempts: `2`
- max chunk wait retries: `3`

### Timeout handling

Waiting for an in-flight chunk is also recoverable.

If a chunk future times out:

- the future is canceled
- the chunk is removed
- it is rescheduled
- waiting resumes

If the timeout persists, the data source throws:

- `ChunkWaitTimeoutException`

Other download failures surface as:

- `ChunkDownloadException`

These typed exceptions are later reused by benchmark code for failure classification.

## Media3 Integration Details

## Media3 seam used by Nexio

Nexio's custom transport plugs into Media3 at the `DataSource.Factory` boundary.

The progressive path in `PlayerMediaSourceFactory.kt` constructs:

- `ParallelRangeDataSource.Factory(...)`

That factory is then handed to:

- `DefaultMediaSourceFactory(progressiveFactory, extractorsFactory)`

Media3 continues to own:

- extraction
- buffering policy
- renderer behavior
- playback state machine
- track selection
- timeline/media period orchestration

Nexio owns:

- how progressive bytes are fetched from the network before they reach Media3 extractors

## Why HLS and DASH are excluded

`PlayerMediaSourceFactory.kt` explicitly avoids the custom path for HLS and DASH.

Reason:

- those formats are already segment-oriented and Media3 already handles parallel/chunked fetching semantics for them at the media-source level

So the feature is explicitly targeted at:

- large progressive assets such as MKV and MP4

## Interaction with VOD Cache and Warm-Ahead

## Layering model

The progressive network factory is created first.

That means the upstream for progressive playback may be either:

- plain `OkHttpDataSource.Factory`
- `ParallelRangeDataSource.Factory`

After that, Nexio may optionally wrap the progressive upstream in Media3 cache infrastructure using:

- `CacheDataSource`
- `CacheWriter`
- `SimpleCache`

This layering is important:

- **parallel fetching can sit underneath Media3 cache**

## Startup prefetch gate

`PlayerMediaSourceFactory.kt` uses:

- `parallelStartupPrefetchUnlocked`

When parallel progressive playback is chosen:

- startup background prefetch is initially constrained
- it is unlocked after first frame via `notifyPlaybackFirstFrameRendered()`

The first-frame unlock is triggered from the player callback path in:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`

The goal is to avoid self-inflicted startup churn from aggressive background fetches before playback has stabilized.

## Warm-ahead loop

After first frame, if VOD cache is enabled and the stream is eligible, Nexio starts a warm-ahead process that:

- finds uncached holes in the cache
- writes future ranges into cache with `CacheWriter`
- guards against racing too close to the active read frontier

Crucially, the warm-ahead loop uses:

- `currentProgressiveUpstreamFactory`

If the current progressive upstream is `ParallelRangeDataSource.Factory`, warm-ahead is effectively using the same custom parallel transport beneath the cache layer.

## Read-frontier and redirect awareness

The custom data source reports:

- resolved URI via `onResolvedUri`
- active read position via `onReadPositionAdvanced`

`PlayerMediaSourceFactory` uses those callbacks to:

- key cache activity correctly after redirects
- avoid warm-ahead trampling the active playback frontier

This is not stock Media3 behavior; it is Nexio-specific coordination between the custom transport and the VOD cache policy layer.

## Benchmark and Tuning Path

## Why benchmark code matters

Nexio does not only expose static settings. It also benchmarks different parallel profiles against debrid-backed direct URLs.

That benchmark path reuses the same transport concept to evaluate:

- which connection count
- which chunk size

produce the best real throughput on a given provider/device/network combination.

## Main benchmark classes

Core files:

- `app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt`
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkMemoryGate.kt`
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunner.kt`
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DirectProfileBenchmarkTransport.kt`

## Benchmark transport behavior

`OptimizedBenchmarkTransport.kt` uses `ParallelRangeDataSource.Factory` when:

- `useParallelConnections != false`

Otherwise it uses plain `OkHttpDataSource.Factory`.

That means the benchmark path is a close proxy for the real playback transport, not a separate synthetic network stack.

## Benchmark-specific timeout scaling

Benchmark code adds a chunk-wait-timeout policy based on configured chunk size:

- `8 MB -> 1000 ms`
- `16 MB -> 2000 ms`
- `24 MB -> 3000 ms`
- `32 MB -> 4000 ms`
- capped at `6000 ms`

This logic lives in:

- `benchmarkChunkWaitTimeoutMs(...)`

This timeout scaling is benchmark-specific and should not be confused with the playback data source's default wait timeout constant.

## Benchmark matrix

The debrid config benchmark service sweeps this matrix:

- `2x / 8 MB`
- `3x / 8 MB`
- `4x / 8 MB`
- `2x / 16 MB`
- `3x / 16 MB`
- `4x / 16 MB`
- `2x / 24 MB`
- `3x / 24 MB`
- `4x / 24 MB`
- `2x / 32 MB`
- `3x / 32 MB`
- `4x / 32 MB`

This matrix is defined in:

- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkService.kt`

## Memory gate for benchmark profiles

Before running a benchmark profile, Nexio checks whether the estimated memory use is safe.

That guard lives in:

- `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridConfigBenchmarkMemoryGate.kt`

It uses the same `MemoryBudget` model as the settings/runtime path.

This keeps benchmark recommendations aligned with what the device can safely sustain in playback.

## Direct vs optimized benchmark modes

`DirectProfileBenchmarkTransport.kt` exists as the single-connection reference path:

- `useParallelConnections = false`

`OptimizedBenchmarkTransport.kt` is the parallel transport path.

So benchmark terminology maps to:

- `DIRECT` = plain single upstream connection
- `OPTIMIZED` = Nexio's parallel range transport

## Playback Improvement Mechanism

The feature does **not** improve playback because Media3 changed decoder behavior, buffer behavior, or extractor behavior.

It improves playback because it changes the network-side characteristics of progressive reads:

- multiple concurrent range requests
- better aggregate throughput
- resumable per-chunk retries
- controlled prefetch ahead of the read frontier
- optional cache filling on top of the same transport

This is fundamentally a transport-layer optimization wrapped in the Media3 `DataSource` contract.

## Full Path Map

### Settings path

- UI: `PlaybackBufferNetworkSettings.kt`
- view-model logic: `PlaybackSettingsViewModel.kt`
- persistence: `PlayerSettingsDataStore.kt`
- budget logic: `MemoryBudget.kt`

### Playback path

- runtime initialization: `PlayerRuntimeControllerInitialization.kt`
- media-source assembly: `PlayerMediaSourceFactory.kt`
- custom progressive transport: `ParallelRangeDataSource.kt`
- player source resets:
  - `PlayerRuntimeControllerInitialization.kt`
  - `PlayerRuntimeControllerStreams.kt`
  - `PlayerRuntimeControllerTrackSelection.kt`

### Benchmark/tuning path

- session snapshotting: `DebridBenchmarkSessionRunner.kt`
- transport implementation: `OptimizedBenchmarkTransport.kt`
- config sweeps: `DebridConfigBenchmarkService.kt`
- safety gate: `DebridConfigBenchmarkMemoryGate.kt`
- result serialization: `DebridConfigBenchmarkModels.kt`
- logging: `BenchmarkResultJsonLogger.kt`

## Test Coverage and What It Proves

## Transport tests

`app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTest.kt` verifies:

- recovery from a transient chunk reset
- repeated transient reset handling
- failure after excessive resets
- resume-from-partial-offset behavior
- transport-byte accounting for bootstrap reads

These tests are important because they prove the transport is not merely "fire multiple requests"; it also implements real recovery/resume semantics.

## Benchmark tests

`app/src/test/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransportTest.kt` verifies:

- config snapshot freezing at benchmark start
- timeout scaling by chunk size
- startup bootstrap reuse on sustained path
- bootstrap reuse disabled for seek benchmarking
- config benchmark failure behavior
- typed timeout reporting
- recovery from timeout/reset/EOF-style failures
- backoff behavior

These tests show the benchmark system understands the custom transport and classifies its failures intentionally.

## Answer to "Kotlin thirdparty module?"

If the question is whether Nexio is using an off-the-shelf Kotlin module that provides parallel connections for Media3 progressive playback, the answer is:

- **No.**

The feature is implemented directly in Nexio app code.

If the question is whether the project has third-party modules around playback in general, the answer is:

- yes, but they are adjacent concerns
- examples:
  - `media3-datasource-okhttp` for the standard Media3-to-OkHttp bridge
  - `ass-media` for subtitle rendering support
  - local Media3 fork/source substitution for broader playback customization

None of those are the owner of the parallel progressive transport feature itself.

## Practical Conclusions

### Primary conclusion

For progressive playback, parallel connections are a **Nexio-owned transport feature implemented above OkHttp and below Media3 extraction/playback orchestration**.

### Secondary conclusion

Media3 is still essential, but mainly as:

- the playback engine
- the abstraction layer (`DataSource`, `MediaSource`, `ExoPlayer`, cache)
- the protocol-specific implementation for HLS/DASH

### Third conclusion

The feature is not isolated to "just playback."

It also affects:

- VOD cache warm-ahead behavior
- benchmark/tuning workflows
- settings and memory-budget policy

So an engineer reviewing or changing it should treat it as a **cross-cutting transport feature**, not a one-file optimization.

## Review Checklist

An engineer reviewing this path should verify:

- progressive-only gating still excludes HLS and DASH
- fallback to single-source mode is preserved for unknown length / no-range support
- chunk scheduling remains bounded by memory policy
- warm-ahead does not race the active read frontier
- seek/open behavior still uses the continuation/bootstrap optimizations correctly
- benchmark profiles remain aligned with runtime-safe memory limits
- Media3 source-factory integration stays at the `DataSource.Factory` seam and does not accidentally fork broader playback behavior
