# Android TV Playback Architecture Audit

## Summary

This audit focuses on the incremental playback instability caused by the current `parallel connections + VOD cache + playback diagnostics` architecture on Android TV devices.

Observed behavior:

- Baseline playback/render memory remains high even with tracing, VOD cache, and parallel connections disabled, around `350MB`.
- Enabling playback diagnostics, VOD cache, and parallel connections introduces severe instability.
- Recent debug-build runs ended with process kills under extreme memory pressure.
- The current playback trace system often fails to flush usable breadcrumbs before process death.

The core conclusion is:

The current architecture holds the same media bytes in too many places at once. On Android TV hardware, that duplication pushes the app from a high but survivable baseline into unstable territory.

## Current Problem Framing

The issue is not simply "Android TV cannot handle high bitrate remuxes."

The stronger explanation is:

- Base player/render/surface memory is already expensive.
- On top of that, the app adds:
  - PRDS bootstrap buffers
  - PRDS frontier buffers
  - PRDS prefetch scratch buffers
  - VOD cache write-through buffers
  - warm-ahead cache fill buffers
  - tracing overhead
- Reopen/probe churn causes that allocation pattern to repeat during one playback attempt.

This is why instability appears only when the advanced playback features are enabled, even though the baseline player still uses a large amount of memory.

## What The Current Code Is Doing

### 1. PRDS has multiple simultaneous byte reservoirs

Current code can hold playback bytes in all of these places at once:

- bootstrap arrays in [ParallelRangeDataSource.kt](/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt)
- live frontier pages in [PagedFrontierBuffer.kt](/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt)
- prefetch scratch buffers in [DualLaneScheduler.kt](/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/DualLaneScheduler.kt)
- keep-behind retention in [ParallelRangeDataSource.kt](/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt)

For Premiumize locked shape:

- urgent chunk: `16MiB`
- prefetch chunk: `16MiB`
- keep-behind: `32MiB`

That means a single active session can easily carry tens of MiB of duplicated playback byte state before considering player allocators or cache.

### 2. VOD cache writes still occur on the playback path

The playback path is wrapped by `CacheDataSource` / `CacheDataSink` in [PlayerMediaSourceFactory.kt](/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt).

On a cache miss, playback is effectively doing:

- network read
- active playback consumption
- cache write

at the same time.

That is supported by Media3, but it is a poor fit for constrained Android TV hardware when the upstream stream is already high bitrate.

### 3. Warm-ahead duplicates bytes again

The warm-ahead loop in [PlayerMediaSourceFactory.kt](/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt) uses `CacheWriter` in the background.

So when PRDS, cache, and warm-ahead are all enabled together, the same region of media can exist simultaneously as:

- PRDS urgent bytes
- PRDS prefetch bytes
- cache-writer bytes
- player-side buffered media

That architecture is exactly the kind of byte duplication Android TV devices do not tolerate well.

### 4. Reopen/probe churn multiplies allocation spikes

Recent traces show multiple `seek_like_reopen` events during one playback attempt. Every real reopen through [ParallelRangeDataSource.kt](/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt) can rebuild state and reallocate bootstrap windows and scheduling structures.

Even after reuse improvements, repeated open/setup churn still compounds the memory footprint and CPU cost.

### 5. Metadata cache writes still compete with playback

Even after batching changes, debug-build logcat still shows repeated fsyncs for:

- `/data/user/0/com.nexiodebug.tv/shared_prefs/metadata_disk_cache_v1.xml`

The active write paths are in:

- [MetadataDiskCacheStore.kt](/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt)
- [MetaRepositoryImpl.kt](/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/MetaRepositoryImpl.kt)
- [TmdbMetadataService.kt](/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt)
- [TrailerService.kt](/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt)

These writes are probably not the dominant resident-memory source, but they clearly add pressure during unstable playback windows.

### 6. The trace system is too buffered to help when the process dies

Recent debug-build runs created trace files in the app sandbox, but they remained `0` bytes:

- `c8ff6fef-b758-4595-90a3-35c479651723.jsonl`
- `e40e399e-54f0-4e30-9e91-e9cc633b58f0.jsonl`

This means the trace system is not durably flushing even the session header before the app is killed.

That is an observability problem, not the main resident-memory cause, but it prevents effective diagnosis under kill conditions.

## Why `nexio-old` Felt Better

The older implementation in `~/Scripts/nexio-old` had a simpler and more bounded memory model.

### Old PRDS model

[ParallelRangeDataSource.kt](/Users/jneerdael/Scripts/nexio-old/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt) used:

- one `bufferPool`
- `maxPoolSize = parallelConnections + 2`
- one current chunk
- one bootstrap chunk
- chunk futures

That is much more predictable than the current layered frontier + scratch + cache model.

### Old explicit budget model

[MemoryBudget.kt](/Users/jneerdael/Scripts/nexio-old/app/src/main/java/com/nexio/tv/ui/screens/settings/MemoryBudget.kt) explicitly modeled:

- buffer budget
- parallel chunk overhead
- hard limits based on connection count and chunk size

It was not sufficient for the highest bitrates, but it was architecturally closer to the correct model for Android TV.

### Old cache path was simpler

The old [PlayerMediaSourceFactory.kt](/Users/jneerdael/Scripts/nexio-old/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt) still used cache, but the overall system did not also layer in the current amount of diagnostics and PRDS complexity.

So the old path was easier on memory and CPU, even if it still struggled at the absolute top end.

## Official Guidance And What It Implies

### Media3 `CacheDataSource`

`CacheDataSource` can read from cache and optionally write into cache on misses. If no cache write sink is provided, the playback path can be read-only from the cache perspective.

Implication:

Playback should not do synchronous cache writes unless that is clearly worth the memory and I/O tradeoff.

### Media3 `SimpleCache`

`SimpleCache` maintains an in-memory representation of the cache index, and only one instance should exist per cache directory.

Implication:

Cache should be treated as a global resource with its own memory budget, not casually combined with other large playback buffers.

### Media3 `DefaultLoadControl`

The player already has its own buffering model.

Implication:

App-side transport buffering must be coordinated with player-side buffering, not layered blindly on top of it.

### Android memory guidance

Android’s guidance emphasizes that retained objects and duplicate copies directly increase resident memory and can lead to LMK or process death.

Implication:

The architecture must minimize duplicated byte ownership, especially on constrained devices.

### OkHttp guidance

OkHttp `EventListener` is designed for lightweight observation, not expensive work or large allocations.

Implication:

Diagnostics must be lightweight, rate-limited, and ideally off the hot playback path on-device.

## Recommended Target Architecture

### 1. One active-byte owner

The playback path should have exactly one owner of active streaming bytes:

- direct path: direct upstream
- parallel path: urgent PRDS frontier

Do not let cache fill act as a second owner of the same active bytes.

### 2. Read-only cache on the playback path

The playback path should attach cache in read-only mode:

- cache hits are free wins
- cache misses should not synchronously write while playback is trying to stay alive

Cache writes should happen in a separate background fill path.

### 3. PRDS should be urgent-only

PRDS should focus on maintaining the live playback frontier.

Speculative prefetch should not live inside PRDS if it requires large scratch arrays and duplicated byte state. If speculative fill is needed, it should be separate and tightly bounded.

### 4. Background cache fill should be separate and bounded

Use one background cache fill worker at most:

- cancellable
- device-state-aware
- disabled under memory pressure or rebuffer
- no large full-chunk scratch arrays

### 5. Hard architecture-wide memory budget

A real budget should include:

- Media3 allocator / load control
- PRDS bootstrap
- PRDS urgent in-flight data
- PRDS keep-behind
- prefetch working set, if any
- cache fill working set
- subtitles/overlays margin
- diagnostics margin

This budget should be enforced dynamically per device class.

### 6. Aggressive probe/reopen reuse

Extractor and seek-map probes should reuse already-readable coverage whenever possible. Small non-zero bounded probe opens should not rebuild transport.

### 7. Flight-recorder diagnostics

On-device tracing should default to:

- immediate session header flush
- low-frequency checkpoint flushes
- essential events only
- heavy families opt-in only

Diagnostics must not destabilize playback.

## Recommended Concrete Design

### Direct path

- upstream `OkHttpDataSource`
- optional read-only `CacheDataSource`
- one background cache-fill worker
- no write-through cache on the playback thread

### Parallel path

- provider-aware urgent PRDS
- no large speculative PRDS scratch buffers
- separate optional cache-fill worker
- strict byte budget

### Cache strategy

Cache should exist for:

- quick reopen/seek reuse
- future playback benefit

Cache should not be treated as the same thing as active playback buffering.

## Most Important Next Engineering Moves

1. Remove cache writes from the playback read path.
2. Collapse PRDS to urgent-only for active playback.
3. Eliminate full-chunk speculative scratch allocations.
4. Reintroduce an explicit memory budget model.
5. Make trace headers flush immediately so process kills leave breadcrumbs.

## Source Links

- Android Media3 `CacheDataSource`:
  https://developer.android.com/reference/androidx/media3/datasource/cache/CacheDataSource
- Android Media3 cache package summary:
  https://developer.android.com/reference/androidx/media3/datasource/cache/package-summary
- Android Media3 `SimpleCache`:
  https://developer.android.com/reference/androidx/media3/datasource/cache/SimpleCache
- Android Media3 `DefaultLoadControl`:
  https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl
- Android memory overview:
  https://developer.android.com/topic/performance/memory-overview
- Android low-memory kill guidance:
  https://developer.android.com/topic/performance/vitals/lmk
- Android `ApplicationExitInfo`:
  https://developer.android.com/reference/android/app/ApplicationExitInfo
- OkHttp `Dispatcher`:
  https://square.github.io/okhttp/5.x/okhttp/okhttp3/-dispatcher/index.html
- OkHttp `maxRequestsPerHost`:
  https://square.github.io/okhttp/5.x/okhttp/okhttp3/-dispatcher/max-requests-per-host.html
- OkHttp `EventListener`:
  https://square.github.io/okhttp/5.x/okhttp/okhttp3/-event-listener/index.html
