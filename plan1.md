• Summary

  The strongest code-level conclusion is not “Android TV can’t handle high bitrate.” It’s that the
  current architecture is holding the same media bytes in too many places at once.

  Your observation fits the code:

  - baseline playback/render memory stays high even with everything off, around 350MB
  - the instability appears when playback trace + VOD cache + parallel connections are enabled
  - therefore the real problem is the incremental memory and CPU added by those features, not the
    base player alone

  From code plus official docs, the right architecture for Android TV is:

  1. one owner of active playback bytes
  2. one separate background fill path for cache
  3. strict memory budgets for in-flight bytes
  4. no synchronous cache writes on the playback path
  5. minimal instrumentation on device unless explicitly profiling

  Right now the current code violates that model.

  What The Docs Say

  From official Media3 docs:

  - CacheDataSource reads from cache and, on cache miss, reads from upstream and writes into cache.
    If cacheWriteDataSink is null, it becomes read-only.
    Source: Android Media3 CacheDataSource API
    https://developer.android.com/reference/androidx/media3/datasource/cache/CacheDataSource
  - SimpleCache maintains an in-memory representation and only one instance may exist per directory.
    Source: Android Media3 SimpleCache API
    https://developer.android.com/reference/androidx/media3/datasource/cache/SimpleCache
  - DefaultLoadControl already manages substantial player-side buffering memory.
    Source: Android Media3 DefaultLoadControl API
    https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl
  - Android memory guidance is explicit that memory you touch and retain stays resident until
    references are released.
    Source: Android memory overview
    https://developer.android.com/topic/performance/memory-overview
  - Android recommends using ApplicationExitInfo to understand kills and low-memory/resource exits.
    Source: ApplicationExitInfo and LMK docs
    https://developer.android.com/reference/android/app/ApplicationExitInfo
    https://developer.android.com/topic/performance/vitals/lmk
  - OkHttp’s EventListener must execute fast and avoid external locking or I/O.
    Source: OkHttp EventListener docs
    https://square.github.io/okhttp/5.x/okhttp/okhttp3/-event-listener/index.html
  - OkHttp dispatcher limits queue requests in memory once maxRequests / maxRequestsPerHost are
    exceeded.
    Source: OkHttp Dispatcher docs
    https://square.github.io/okhttp/5.x/okhttp/okhttp3/-dispatcher/index.html
    https://square.github.io/okhttp/5.x/okhttp/okhttp3/-dispatcher/max-requests-per-host.html

  These point to a clear principle: on constrained devices, do not duplicate hot playback bytes
  across multiple buffering and cache layers unless you have a hard byte budget.

  What The Current Code Is Doing

  1. PRDS now has multiple simultaneous byte reservoirs
  Current code can hold media bytes in all of these at once:

  - bootstrap arrays in app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt
  - live frontier pages in app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt
  - prefetch scratch buffers in app/src/main/java/com/nexio/tv/ui/screens/player/
    DualLaneScheduler.kt
  - keep-behind retention in app/src/main/java/com/nexio/tv/ui/screens/player/
    ParallelRangeDataSource.kt
  - cache write buffers via CacheDataSource / CacheWriter in app/src/main/java/com/nexio/tv/ui/
    screens/player/PlayerMediaSourceFactory.kt

  That is the architectural mismatch.

  2. VOD cache is on the playback path, not beside it
  Current app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt builds
  playback around CacheDataSource with a write sink when cache is active. That means on cache miss,
  playback is doing:

  - upstream read
  - active playback consumption
  - cache write
    all together.

  That’s exactly what Media3 supports, but it’s not what you want on a weak Android TV device at
  very high bitrates.

  3. Warm-ahead duplicates bytes again
  The warm-ahead loop in app/src/main/java/com/nexio/tv/ui/screens/player/
  PlayerMediaSourceFactory.kt uses CacheWriter in the background. So with PRDS + cache + warm-ahead,
  you can have:

  - PRDS urgent bytes
  - PRDS prefetch bytes
  - cache writer bytes
  - player allocator bytes
    all alive simultaneously.

  4. Current PRDS memory is not explicitly budgeted
  In nexio-old, there was an explicit memory model in /Users/jneerdael/Scripts/nexio-old/app/src/
  main/java/com/nexio/tv/ui/screens/settings/MemoryBudget.kt. It assumed:

  - buffer budget + chunk budget
  - parallelConnections + 1 chunk buffers
  - hard clamping

  The old PRDS implementation in /Users/jneerdael/Scripts/nexio-old/app/src/main/java/com/nexio/tv/
  ui/screens/player/ParallelRangeDataSource.kt also had a simpler, more bounded model:

  - one bufferPool
  - maxPoolSize = parallelConnections + 2
  - one current chunk
  - one bootstrap chunk
  - chunk futures

  That older model was still imperfect at extreme bitrates, but it was much more predictable in-
  memory than the current layered PRDS + paged frontier + scratch + cache architecture.

  5. Current code has no single “active byte owner”
  That is the core design flaw.

  Today:

  - PRDS owns active transport bytes
  - PagedFrontierBuffer owns readable bytes
  - SimpleCache also stores those bytes
  - CacheWriter may also be filling ahead
  - Media3 LoadControl is also buffering decoded media

  You don’t want all of those active simultaneously for the same stream window on Android TV.

  Best Architecture For Android TV

  The architecture I would recommend, based on the docs plus your old code behavior, is:

  A. Split playback transport from cache fill
  Use two different modes:

  - Playback path
      - one active byte source only
      - either direct upstream or PRDS urgent/frontier
      - no cache writes on the playback thread
  - Background cache fill path
      - separate
      - opportunistic
      - cancellable
      - bounded by memory and device state

  That means:

  - attach cache read-only on playback path
  - do cache writes only from background fill logic

  Media3 supports this directly because CacheDataSource can be configured read-only by omitting the
  write sink.

  B. PRDS should be urgent-only
  For Android TV, PRDS should focus on one thing:

  - keeping the current playback frontier fed

  It should not also be your general-purpose speculative cache/warm-ahead engine.

  A better split:

  - PRDS urgent lane only for active playback
  - one background cache fill worker, at most one, outside PRDS
  - no PRDS prefetch scratch buffers for speculative work

  This is especially important because your traces show PM/RD provider behavior where connection
  setup and chunk boundaries matter. You want PRDS using a provider-aware urgent shape, but you do
  not want PRDS carrying extra speculative memory just to write cache ahead.

  C. Use chunk-part or page-part prefetch, not full-chunk scratch
  If you keep any PRDS prefetch at all, do not allocate full 16-64MB scratch arrays.
  Use:

  - 1-4MB parts
  - bounded queue depth
  - direct publish-to-cache or direct publish-to-frontier page slices

  Full-chunk scratch arrays are too expensive on TV sticks and still costly on SHIELD when combined
  with the rest of the stack.

  D. Reintroduce a hard memory budget model
  Bring back something like the old /Users/jneerdael/Scripts/nexio-old/app/src/main/java/com/nexio/
  tv/ui/screens/settings/MemoryBudget.kt, but make it architecture-wide.

  Budget should explicitly include:

  - Media3 target buffer bytes
  - PRDS bootstrap bytes
  - PRDS urgent in-flight bytes
  - PRDS prefetch bytes, if any
  - keep-behind bytes
  - cache fill working set
  - subtitles / overlays headroom
  - tracing headroom

  On Android TV devices, this budget should be derived from:

  - memoryClass
  - total RAM
  - device family override

  And enforced at runtime.

  E. Direct path should be read-only cache + async fill
  For parallelConnections = false, the stable architecture should be:

  - playback reads from upstream, optionally through read-only cache
  - background cache fill writes later
  - no synchronous write-through on playback path

  That matches your observation that the older direct path behaved better.

  F. PRDS should reuse state for extractor probes aggressively
  Your traces show large extractor-like reopens. The current retained-coverage reuse helps only some
  cases.
  The right model is:

  - bounded probe opens should never rebuild transport if already-covered bytes exist
  - near-EOF and seek-map probes should prefer cache hit or retained coverage
  - only real playhead migration should rebuild urgent transport

  G. Instrumentation on device should be “flight recorder” style
  The current trace system is still too buffered and too expensive to help when the app is unstable.
  On-device diagnostics should default to:

  - immediate flush of session header
  - periodic checkpoint flushes
  - essential events only
  - heavy families opt-in only

  That is a separate observability design issue, but it matters because crash diagnostics should not
  destabilize playback.

  Recommended Target Architecture

  For Android TV and debrid high-bitrate playback:

  1. Direct path

  - upstream OkHttpDataSource
  - optional CacheDataSource in read-only mode
  - one async cache-fill worker
  - no warm-ahead writes on the playback thread

  2. Parallel path

  - PRDS urgent-only transport
  - provider-aware chunk/worker policy
  - no speculative PRDS scratch beyond the active playback frontier
  - optional single background cache-fill worker separate from PRDS

  3. Cache strategy

  - cache is for reopen/seek reuse and future playback benefit
  - cache is not the same thing as active playback buffering
  - keep it logically separate

  4. Memory budget

  - explicit total budget
  - dynamic per device
  - hard-disable speculative features when budget is exceeded

  5. Fallback behavior

  - if device is under memory pressure or process has recent ApplicationExitInfo kills, disable:
      - PRDS prefetch
      - warm-ahead
      - cache writes on playback path
      - heavy tracing

  Most Important Differences From Current Code

  Current code:

  - PRDS + VOD cache + warm-ahead can all hold the same bytes
  - no single active-byte owner
  - current direct path still writes cache inline
  - memory budgeting is implicit or absent

  Recommended code:

  - one active-byte owner
  - cache fill is separate and opportunistic
  - PRDS only solves current playback throughput
  - explicit memory caps control everything

  Why nexio-old Felt Better

  From the local old code:

  - old PRDS in /Users/jneerdael/Scripts/nexio-old/app/src/main/java/com/nexio/tv/ui/screens/player/
    ParallelRangeDataSource.kt had a more bounded in-memory model
  - old /Users/jneerdael/Scripts/nexio-old/app/src/main/java/com/nexio/tv/ui/screens/settings/
    MemoryBudget.kt explicitly modeled chunk buffer cost
  - old VOD cache path in /Users/jneerdael/Scripts/nexio-old/app/src/main/java/com/nexio/tv/ui/
    screens/player/PlayerMediaSourceFactory.kt was simpler and had no playback tracing overlay
  - it still struggled at extreme bitrates, which suggests the provider/network problem was real,
    but it was not compounding that with as much duplicated app-side residency

  So the old architecture was closer to the right shape, even if it wasn’t fully sufficient.

  Bottom Line

  The likely winning architecture is not:

  - “more aggressive PRDS”
  - “more VOD cache”
  - “more warm-ahead”

  It is:

  - strict separation of active playback transport from background cache fill
  - provider-aware urgent parallelism only
  - explicit memory budgeting
  - read-only cache on playback path
  - bounded speculative work

  If you want, I can now turn this audit and research into a concrete implementation plan.

  Sources

  - Android Media3 CacheDataSource:
    https://developer.android.com/reference/androidx/media3/datasource/cache/CacheDataSource
  - Android Media3 cache package summary:
    https://developer.android.com/reference/androidx/media3/datasource/cache/package-summary
  - Android Media3 SimpleCache:
    https://developer.android.com/reference/androidx/media3/datasource/cache/SimpleCache
  - Android Media3 DefaultLoadControl:
    https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl
  - Android memory overview: https://developer.android.com/topic/performance/memory-overview
  - Android LMK docs: https://developer.android.com/topic/performance/vitals/lmk
  - Android ApplicationExitInfo:
    https://developer.android.com/reference/android/app/ApplicationExitInfo
  - OkHttp Dispatcher: https://square.github.io/okhttp/5.x/okhttp/okhttp3/-dispatcher/index.html
  - OkHttp maxRequestsPerHost:
    https://square.github.io/okhttp/5.x/okhttp/okhttp3/-dispatcher/max-requests-per-host.html
  - OkHttp EventListener:
    https://square.github.io/okhttp/5.x/okhttp/okhttp3/-event-listener/index.html
