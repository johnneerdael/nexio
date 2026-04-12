# NEXIO High-Bitrate Streaming Architecture — Greenfield Design



## Context



NEXIO needs to stream 160 GB remux files (3-hour movies, ~120 Mbps sustained) on Android TV devices with 1-2 GB RAM. This document designs a feature stack between OkHttp and Media3 from first principles.



**The core architectural principle:** On constrained devices, there must be **one owner of active playback bytes** at any point in the pipeline. Bytes must not be duplicated across multiple buffering layers simultaneously. Cache is separate from active transport — read-only on the playback path, written only by a background fill worker.



This principle is grounded in Android's memory documentation: memory you touch and retain stays resident until references are released ([Android memory overview](https://developer.android.com/topic/performance/memory-overview)). On a 2 GB device where the OS claims ~800 MB, every duplicated buffer directly competes with the player's decoded sample queue.



---



## Provider Constraints



| | Real-Debrid | Premiumize |

|---|---|---|

| Parallel connections | 1-2 | 1-2 |

| Connection reuse | **No** — `Connection: close` forced per chunk | **Yes** — keep-alive with HTTP Range |

| Per-chunk overhead | Full TCP+TLS handshake (~150-200ms for TLS 1.3) | Negligible (connection reused) |

| Rate limit | 250 req/min ([Real-Debrid API](https://api.real-debrid.com/)) | None documented |

| Best chunk strategy | Large (32 MiB) to amortize handshake cost | Smaller (8-16 MiB) for granularity |



**Key observation:** A single connection sometimes bottlenecks even on fast networks. Two connections can truly double throughput. But two connections also double in-flight byte residency — the architecture must account for this.



## Device Constraints



| Device | RAM | `memoryClass` (typical) | Safe app heap |

|---|---|---|---|

| Fire TV Stick 4K (2023) | 2 GB LPDDR4 | 256 MB | ~256-384 MB |

| Chromecast w/ Google TV 4K | 2 GB | 256 MB | ~256-384 MB |

| Shield TV (2019) | 3 GB | 256-512 MB | ~384-512 MB |

| Fire TV Stick HD / Select | 1 GB | 128-192 MB | ~128-192 MB |



Sources: [Amazon Fire TV specs](https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html), [Chromecast specs](https://support.google.com/chromecast/answer/3046409), [Android TV memory guidance](https://developer.android.com/training/tv/playback/memory)



**Critical:** `ActivityManager.getMemoryClass()` returns the **per-app heap limit in MB** — typically 256 MB on 2 GB TV devices. `Runtime.getRuntime().maxMemory()` returns the same value in bytes. This is NOT total device RAM. The Media3 SampleQueue, all download buffers, the app UI, and image caches must fit within this single number. ([ActivityManager API](https://developer.android.com/reference/android/app/ActivityManager))



For `largeHeap=true` apps, `getLargeMemoryClass()` may return more (384-512 MB), but Android's LMK becomes more aggressive. Use `ApplicationExitInfo.REASON_LOW_MEMORY` to detect if previous sessions were killed. ([ApplicationExitInfo API](https://developer.android.com/reference/android/app/ApplicationExitInfo), [LMK docs](https://developer.android.com/topic/performance/vitals/lmk))



### Key Numbers



- 120 Mbps = **15 MB/s** sustained download and disk write rate

- 1 second of video at 120 Mbps = ~15 MB

- 20 seconds in SampleQueue = ~300 MB (already exceeds `memoryClass` of 256 MB alone)

- TLS 1.3 full handshake: ~150-200ms; session resumption: ~50-100ms

- Android TV sustained disk write: 20-50 MB/s (device-dependent)



---



## Architectural Principle: One Active Byte Owner



The fundamental problem with naively layering caching, parallel transport, and prefetch is **byte duplication**. If the same media bytes exist simultaneously in:



1. OkHttp response body buffers (in-flight download)

2. An in-memory staging/frontier buffer

3. A disk cache write-through sink

4. Media3's SampleQueue (decoded samples)



...then a 20-second window at 120 Mbps isn't 300 MB — it's 600-900 MB across the layers. That kills a 2 GB device.



**The rule:** At any moment, each byte of media exists in exactly one of these locations:



| Location | Role | Owner |

|---|---|---|

| OkHttp response → small read buffer | In-flight network transfer | Background cache-fill worker |

| Disk cache (SimpleCache) | Persistent storage of fetched bytes | Shared (write: fill worker, read: playback) |

| Media3 SampleQueue (DefaultAllocator) | Decoded samples for rendering | ExoPlayer LoadControl |



There is **no** in-memory frontier buffer, **no** write-through on the playback path, **no** prefetch scratch arrays. The download worker writes directly to disk cache. The playback path reads from disk cache (read-only). Media3 manages its own decoded buffer via DefaultLoadControl.



This matches the pattern documented for Media3's CacheDataSource: when `cacheWriteDataSink` is null, the source becomes read-only — playback reads from cache without writing. ([CacheDataSource API](https://developer.android.com/reference/androidx/media3/datasource/cache/CacheDataSource))



---



## Architecture Overview



```

         ┌─────────────────────────────────────┐

         │   Background Cache-Fill Worker       │

         │   (1 thread, runs independently)     │

         │                                      │

         │   Reads: HTTP (OkHttp Range reqs)    │

         │   Writes: SimpleCache on disk        │

         │   Bounded: small read buffer only    │

         │   Provider-aware: chunk size, conns  │

         └──────────────┬──────────────────────┘

                        │ writes to disk

                        ▼

         ┌─────────────────────────────────────┐

         │   SimpleCache (Disk)                 │

         │   LRU + position-aware eviction      │

         │   Rolling window: 30-60s ahead       │

         │   Budget: 500 MB - 2 GB              │

         └──────────────┬──────────────────────┘

                        │ reads from disk

                        ▼

         ┌─────────────────────────────────────┐

         │   Playback DataSource                │

         │   CacheDataSource (READ-ONLY)        │

         │   + upstream OkHttp fallback         │

         │   No cache writes on this path       │

         └──────────────┬──────────────────────┘

                        │ feeds bytes

                        ▼

         ┌─────────────────────────────────────┐

         │   ExoPlayer                          │

         │   DefaultLoadControl (tuned)         │

         │   SampleQueue (hard byte cap)        │

         │   MediaCodec → Video/Audio output    │

         └─────────────────────────────────────┘

```



**Two independent data paths. One shared disk cache. No byte duplication in memory.**



---



## Component Design



### 1. ProviderProfile — Provider Behavioral Abstraction



**Purpose:** Encode the behavioral contract of each debrid provider so all downstream components can adapt without provider-specific branching.



```kotlin

data class ProviderProfile(

    val id: String,                         // "real-debrid" | "premiumize"

    val maxConnections: Int,                // 1 or 2

    val chunkBytes: Long,                   // bytes per HTTP range request

    val connectionReuse: Boolean,           // false for RD, true for PM

    val fillHorizonSeconds: Int,            // how far ahead to fill cache

    val connectionPoolConfig: ConnectionPoolConfig,

)



data class ConnectionPoolConfig(

    val maxIdleConnections: Int,            // 0 for RD (useless), 4 for PM

    val keepAliveDurationSeconds: Long,     // 1 for RD, 300 for PM

)

```



**Real-Debrid profile:**

- `chunkBytes = 32 * 1024 * 1024` (32 MiB). At 120 Mbps, each chunk downloads in ~2.1s. The ~150-200ms TLS handshake is 7% overhead — acceptable. At 16 MiB it would be 14% — wasteful. ([OkHttp connections](https://square.github.io/okhttp/features/connections/))

- `connectionPoolConfig = ConnectionPoolConfig(0, 1)`. Connections are reset per chunk, so pooling idle connections wastes memory. ([OkHttp ConnectionPool](https://square.github.io/okhttp/3.x/okhttp/okhttp3/ConnectionPool.html))

- `maxConnections = 2`. A single connection at 60 Mbps is common even on 200+ Mbps links; two connections recover the full bandwidth.

- `fillHorizonSeconds = 35`. Conservative — each fill chunk costs a full handshake.



**Premiumize profile:**

- `chunkBytes = 16 * 1024 * 1024` (16 MiB). No handshake penalty, finer scheduling granularity. Better seek responsiveness (less data wasted on cancel).

- `connectionPoolConfig = ConnectionPoolConfig(4, 300)`. Full connection reuse. ([Booking.com connection reuse analysis](https://medium.com/booking-com-development/maximizing-okhttp-connection-reuse-b1f0ad6ec66c))

- `maxConnections = 2`

- `fillHorizonSeconds = 50`. Aggressive — reuse means fill is nearly free.



**Rate budget check (RD):** 250 req/min = 4.2 req/sec. At 32 MiB chunks, 2 connections: ~1 chunk/sec per connection = 2 req/sec total. Well within limit.



---



### 2. CacheFillWorker — Background Cache Population



**Purpose:** The sole writer to the disk cache. Runs on its own thread(s), completely independent of the playback path. Downloads HTTP range chunks and writes them to SimpleCache. This is the only component that holds in-flight network bytes.



**Why separate from playback:**

- Media3's CacheDataSource supports write-through via TeeDataSource, but that makes the playback thread responsible for both reading *and* writing — blocking the decoder pipeline on disk I/O at 15 MB/s. ([CacheDataSource API](https://developer.android.com/reference/androidx/media3/datasource/cache/CacheDataSource))

- The documented pre-caching pattern in Media3 uses CacheWriter on a background thread, separate from playback. Pre-caching should be cancelled when playback's read path needs to take priority over the same cache region. ([Pre-caching progressive streams](https://medium.com/google-exoplayer/pre-caching-downloading-progressive-streams-in-exoplayer-3a816c75e8f6))

- aria2's architecture validates this: it writes directly to disk at the target offset, with ~4 MiB working memory per download when disk cache is off. ([aria2 manual](https://aria2.github.io/manual/en/html/aria2c.html))



```kotlin

class CacheFillWorker(

    private val profile: ProviderProfile,

    private val cache: SimpleCache,

    private val cacheKeyFactory: CacheKeyFactory,

    private val okHttpClient: OkHttpClient,

    private val memoryBudget: MemoryBudget,

    private val bandwidthMonitor: BandwidthMonitor,

) {

    // Lifecycle

    fun start(url: String, contentLength: Long, startPosition: Long)

    fun seekTo(newPosition: Long)     // cancel in-flight, restart from position

    fun stop()



    // Backpressure

    fun pause()                        // called when cache is far enough ahead

    fun resume()                       // called when cache is running low



    // State

    val fillFrontierPosition: Long     // how far ahead we've filled

    val isActive: Boolean

}

```



**Internal architecture:**



```

CacheFillWorker

  ├── fillThread (single Thread or 2 threads for 2-connection providers)

  │     └── loop:

  │           1. Pick next unfilled range [frontier, frontier + chunkBytes)

  │           2. HTTP GET with Range header

  │           3. Read response body in READ_BUFFER_SIZE (512 KB) increments

  │           4. Write each increment to CacheDataSink → SimpleCache

  │           5. Update fillFrontierPosition

  │           6. Check backpressure: if horizon reached, park thread

  │           7. On connection-close provider: next iteration opens new connection

  │              (OkHttp handles this automatically)

  │

  └── Read buffer: single ByteArray(READ_BUFFER_SIZE) per thread

        This is the ONLY in-memory staging — 512 KB per active connection

        Total: 512 KB (1 conn) or 1 MB (2 conns)

```



**Write path — using Media3's CacheDataSink:**



Rather than inventing a cache writer, use Media3's own `CacheDataSink` which handles:

- Atomic writes (temp file → rename)

- Span registration with SimpleCache

- Fragment size control



```kotlin

// Per-chunk write on fill thread:

val dataSpec = DataSpec.Builder()

    .setUri(url)

    .setPosition(chunkStart)

    .setLength(chunkBytes)

    .setKey(cacheKeyFactory.buildCacheKey(dataSpec))

    .build()



val dataSink = CacheDataSink(cache, chunkBytes)

dataSink.open(dataSpec)

try {

    while (totalRead < chunkBytes) {

        val read = responseBody.read(readBuffer, 0, READ_BUFFER_SIZE)

        if (read == -1) break

        dataSink.write(readBuffer, 0, read)

        totalRead += read

        bandwidthMonitor.onBytesTransferred(connectionId, read.toLong())

    }

} finally {

    dataSink.close()

}

```



**Connection pre-warming for Real-Debrid:**



When the current chunk is ~75% complete, the fill worker can initiate the next HTTP request on a second connection. OkHttp opens a new socket for the request (since RD closes connections), and the TLS handshake overlaps with the remaining 25% of the current chunk's transfer.



At 32 MiB chunks and 120 Mbps: 25% remaining = ~8 MiB = ~530ms of transfer time. A TLS 1.3 handshake takes ~150-200ms. The overlap completely hides the handshake. No explicit pre-warm API is needed — just schedule the next request early.



Note: OkHttp does not expose an API for manual connection pre-creation. Connections are created on demand when `newCall(request).execute()` is invoked. ([OkHttp concurrency docs](https://square.github.io/okhttp/contribute/concurrency/))



**Retry strategy:**

- Transient failures (SocketTimeoutException, ConnectionResetException): retry up to 4 times with exponential backoff (0s, 1s, 2s, 4s)

- Non-transient (HTTP 4xx, 5xx): retry up to 2 times

- HTTP 429 (rate limited): backoff to single connection, double chunk size

- Retry with `Range: bytes=lastByteReceived-` to resume partial chunks



**Memory footprint:** 512 KB read buffer per active connection. Total: 512 KB - 1 MB. That's it. No frontier buffers, no scratch arrays, no full-chunk staging.



---



### 3. Disk Cache — SimpleCache with Position-Aware Eviction



**Purpose:** The shared storage layer. The fill worker writes; the playback path reads. Rolling window: keeps data ahead of playback, evicts behind.



**Why Media3's SimpleCache:**

- Thread-safe concurrent read/write with serialized listener callbacks ([SimpleCache API](https://developer.android.com/reference/androidx/media3/datasource/cache/SimpleCache))

- CacheSpan-based byte range tracking — knows exactly which ranges are cached

- Subdirectory sharding (10 dirs) for filesystem performance

- `StandaloneDatabaseProvider` for metadata persistence across app restarts

- Only one SimpleCache instance per directory allowed



**Configuration:**

```kotlin

val cacheDir = File(context.cacheDir, "stream-cache")

val evictor = LeastRecentlyUsedCacheEvictor(maxCacheBytes)

val cache = SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(context))

```



**`maxCacheBytes` derivation:**

```kotlin

val storageManager = context.getSystemService(StorageManager::class.java)

val quota = storageManager.getCacheQuotaBytes(

    storageManager.getUuidForPath(cacheDir)

)

val maxCacheBytes = if (quota > 0) {

    (quota * 0.8).toLong()  // leave 20% headroom

} else {

    500L * 1024 * 1024      // fallback: 500 MB

}

```



**Position-aware eviction (beyond stock LRU):**



Stock `LeastRecentlyUsedCacheEvictor` evicts by access timestamp, which is wrong for sequential playback — it might evict data *ahead* of the player if the fill worker wrote it long ago. We need to evict by byte position relative to the playhead.



```kotlin

fun evictBehindPlayback(cacheKey: String, playbackPosition: Long, retainBehindBytes: Long) {

    val evictBefore = (playbackPosition - retainBehindBytes).coerceAtLeast(0L)

    val spans = cache.getCachedSpans(cacheKey)

    for (span in spans) {

        if (span.position + span.length <= evictBefore) {

            cache.removeSpan(span)

        }

    }

}

```



Call this periodically from the playback DataSource as the playhead advances (e.g., every chunk-size worth of advancement). This keeps the cache as a forward-looking sliding window.



**Sizing at 120 Mbps:**

- 30s ahead = 450 MB on disk

- 45s ahead = 675 MB on disk

- 60s ahead = 900 MB on disk

- Retain behind: 2 × chunkSize (64 MB for RD, 32 MB for PM) for short backward seeks



**Disk write throughput concern:**

At 15 MB/s sustained write rate, Android TV flash storage (rated 20-50 MB/s sequential) has 33-233% headroom. However: concurrent reads (playback) and writes (fill) share the same flash controller. Monitor with `System.nanoTime()` around writes to detect I/O saturation. If writes stall, reduce fill aggressiveness.



**Warning on cache file accumulation:** ExoPlayer GitHub issue #3696 documents that after several GB, thousands of `.exo` cache files accumulate and Android's filesystem indexation degrades startup. Mitigate by using larger fragment sizes in `CacheDataSink(cache, fragmentSize)` — e.g., 8-16 MB fragments instead of the default. ([ExoPlayer issue #3696](https://github.com/google/ExoPlayer/issues/3696))



---



### 4. PlaybackDataSource — Read-Only Cache + Upstream Fallback



**Purpose:** The DataSource that Media3 calls. Reads from disk cache when available (fast path). On cache miss, falls back to direct upstream HTTP read (slow path — no write-through). The playback path **never writes to cache**.



**Implementation using CacheDataSource in read-only mode:**



```kotlin

val playbackDataSourceFactory = CacheDataSource.Factory()

    .setCache(cache)

    .setUpstreamDataSourceFactory(okHttpDataSourceFactory)  // fallback on cache miss

    .setCacheWriteDataSinkFactory(null)                      // READ-ONLY: no writes

    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)    // don't block on cache errors

```



This is the documented pattern for playback alongside a download service: the download writes the cache, the player reads it. ([Downloading media guide](https://developer.android.com/media/media3/exoplayer/downloading-media))



**How it works on cache hit:**

1. ExoPlayer calls `open(DataSpec)` with requested byte range

2. CacheDataSource checks SimpleCache for matching spans

3. If cached: reads directly from disk via `FileDataSource` — zero network, zero memory staging

4. Returns bytes to ExoPlayer's loading thread (disk I/O on loader thread is safe and expected — this is how CacheDataSource always works)



**How it works on cache miss:**

1. CacheDataSource finds no cached span for requested range

2. Falls through to upstream `OkHttpDataSource` for direct HTTP read

3. Because `cacheWriteDataSinkFactory = null`, the fetched bytes are **not** written to cache — they go directly to ExoPlayer's SampleQueue

4. The CacheFillWorker will eventually fill this region; future reads will hit cache



**Why not write-through on miss?**

On a 2 GB device at 120 Mbps, the playback thread is already doing 15 MB/s of reads. Adding synchronous disk writes at 15 MB/s to the same thread doubles its I/O load. The TeeDataSource pattern (read + write simultaneously) works well at normal bitrates, but at 120 Mbps on flash storage with a shared I/O controller, it risks stalling the decoder pipeline. The fill worker handles writes on its own thread with its own I/O scheduling.



**Memory footprint of this component:** Zero additional staging. CacheDataSource reads into ExoPlayer's own allocator buffers (the same `byte[]` arrays managed by `DefaultAllocator`). There is no intermediate copy.



---



### 5. MemoryBudget — Explicit, Device-Aware Memory Caps



**Purpose:** Derive a hard memory budget from the device's actual capabilities and enforce it across all components. This replaces implicit "hope it fits" with explicit accounting.



```kotlin

class MemoryBudget(context: Context) {

    private val am = context.getSystemService(ActivityManager::class.java)!!

    private val memoryClassBytes: Long = am.memoryClass.toLong() * 1024 * 1024

    private val largeMemoryClassBytes: Long = am.largeMemoryClass.toLong() * 1024 * 1024



    // Fixed reservations (measured/estimated)

    val appBaselineBytes: Long = 80L * 1024 * 1024       // UI, Compose, nav, image cache

    val fillWorkerBytes: Long = 1L * 1024 * 1024          // 2 × 512 KB read buffers

    val cacheIndexBytes: Long = 5L * 1024 * 1024           // SimpleCache in-memory spans

    val headroomBytes: Long = 30L * 1024 * 1024            // GC, spikes, system



    // What's left for Media3's SampleQueue

    val sampleQueueBudgetBytes: Long

        get() = (effectiveHeapBytes - appBaselineBytes - fillWorkerBytes

                 - cacheIndexBytes - headroomBytes).coerceAtLeast(64L * 1024 * 1024)



    // Use largeHeap if previous sessions weren't killed by LMK

    val effectiveHeapBytes: Long

        get() {

            val wasKilledByLmk = checkPreviousLmkKill()

            return if (wasKilledByLmk) memoryClassBytes else largeMemoryClassBytes

        }



    private fun checkPreviousLmkKill(): Boolean {

        if (Build.VERSION.SDK_INT < 30) return false

        val exits = am.getHistoricalProcessExitReasons(null, 0, 5)

        return exits.any { it.reason == ApplicationExitInfo.REASON_LOW_MEMORY }

    }

}

```



Sources: [ActivityManager.getMemoryClass()](https://developer.android.com/reference/android/app/ActivityManager), [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)



**Budget on a 2 GB Fire TV Stick 4K (`memoryClass` = 256 MB):**



| Component | Budget | Notes |

|---|---|---|

| App baseline (UI, Compose, images) | ~80 MB | Measured |

| CacheFillWorker read buffers | ~1 MB | 2 × 512 KB |

| SimpleCache in-memory index | ~5 MB | CacheSpan tracking |

| Headroom (GC, transients) | ~30 MB | Safety margin |

| **Media3 SampleQueue** | **~140 MB** | `256 - 80 - 1 - 5 - 30` |



With `largeHeap=true` and no prior LMK kills (`largeMemoryClass` ~384 MB):



| Component | Budget | Notes |

|---|---|---|

| App baseline | ~80 MB | |

| CacheFillWorker | ~1 MB | |

| Cache index | ~5 MB | |

| Headroom | ~30 MB | |

| **Media3 SampleQueue** | **~268 MB** | `384 - 80 - 1 - 5 - 30` |



At 120 Mbps, 268 MB = ~18 seconds in the SampleQueue. Combined with 30-45 seconds on disk in the cache, total buffer = ~48-63 seconds. That's substantial resilience against network interruptions.



**Crucially:** The fill worker's memory is just ~1 MB regardless of bitrate or buffer depth. All the buffered data lives on disk, not in heap. This is why the "one owner" principle matters — we've eliminated the intermediate in-memory layers that would blow the budget.



---



### 6. Tuned DefaultLoadControl — Bitrate-Aware SampleQueue Sizing



**Purpose:** Configure Media3's buffer management to stay within the MemoryBudget's SampleQueue allocation.



```kotlin

fun buildLoadControl(budget: MemoryBudget, estimatedBitrateBps: Long): DefaultLoadControl {

    val bytesPerSecond = estimatedBitrateBps / 8

    val maxBufferSeconds = (budget.sampleQueueBudgetBytes / bytesPerSecond)

        .coerceIn(8, 30)  // floor 8s, cap 30s



    return DefaultLoadControl.Builder()

        .setBufferDurationsMs(

            /* minBufferMs = */ (maxBufferSeconds * 500).toInt(),   // half of max

            /* maxBufferMs = */ (maxBufferSeconds * 1000).toInt(),

            /* bufferForPlaybackMs = */ 2_500,

            /* bufferForPlaybackAfterRebufferMs = */ 5_000,

        )

        .setTargetBufferBytes(budget.sampleQueueBudgetBytes.toInt())

        .setPrioritizeTimeOverSizeThresholds(false)  // byte cap is king on constrained RAM

        .build()

}

```



Sources: [DefaultLoadControl API](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl), [DefaultAllocator](https://developer.android.com/reference/androidx/media3/exoplayer/upstream/DefaultAllocator)



**Why `setPrioritizeTimeOverSizeThresholds(false)`:** When true (default), ExoPlayer continues loading even after `targetBufferBytes` is exceeded, as long as the time-based threshold isn't met. At 120 Mbps, this means the byte cap is ignored — defeating the purpose. Setting false makes the byte cap authoritative. ([ExoPlayer buffering strategy](https://ianbird.dev/exoplayer-loadcontrol/))



**Example on Fire TV 4K with `largeHeap`:**

- `sampleQueueBudgetBytes` = 268 MB

- At 120 Mbps (15 MB/s): `maxBufferSeconds` = 268/15 = ~17s → clamped to 17s

- `minBufferMs` = 8500, `maxBufferMs` = 17000

- Drip-feeding behavior: ExoPlayer continuously loads between 8.5-17s, steady not bursty

- The drip-feeding technique (continuous refill vs burst-pause) reduces rebuffering by up to 4x. ([Drip-feeding in ExoPlayer](https://medium.com/@filipluch/how-to-improve-buffering-by-4-times-with-drip-feeding-technique-in-exoplayer-on-android-b59eb0c4d9cc))



---



### 7. FillController — Adaptive Pacing and Backpressure



**Purpose:** Controls the CacheFillWorker's aggressiveness based on cache state, memory pressure, and bandwidth. Prevents the fill worker from running away and exhausting disk space or I/O bandwidth.



```kotlin

class FillController(

    private val profile: ProviderProfile,

    private val cache: SimpleCache,

    private val fillWorker: CacheFillWorker,

    private val budget: MemoryBudget,

    private val bandwidthMonitor: BandwidthMonitor,

) {

    fun onPlaybackPositionAdvanced(positionBytes: Long)

    fun onSeek(newPositionBytes: Long)

    fun onRebuffer()

    fun onMemoryWarning()  // from ComponentCallbacks2.onTrimMemory()

}

```



**State machine:**

```

STARTUP ──→ FILLING ──→ HORIZON_REACHED ──→ FILLING (on playback advance)

   │            │                                │

   │            └──→ SEEK ──→ FILLING            │

   │            └──→ REBUFFER ──→ PAUSED(2s) ──→ FILLING

   │            └──→ MEMORY_PRESSURE ──→ PAUSED  │

   └────────────────────────────────────────────→ STOPPED

```



| State | Fill worker | Trigger to leave |

|---|---|---|

| STARTUP | Not started yet | `start()` called |

| FILLING | Active, downloading chunks | Horizon reached, or seek, or memory |

| HORIZON_REACHED | Paused (thread parked) | Playback position advances past low-water mark |

| SEEK | Cancelled + restarted at new position | Fill resumes from new position |

| REBUFFER | Paused for 2s | Timer expires, then FILLING |

| MEMORY_PRESSURE | Paused indefinitely | `onTrimMemory(RUNNING_LOW)` clears, or manual resume |

| STOPPED | Fully stopped | Session end |



**Backpressure — high/low water marks:**

- **High water (pause fill):** cache has `fillHorizonSeconds` worth of data ahead of playback

  - RD: 35s × 15 MB/s = 525 MB on disk. Fill worker parks.

  - PM: 50s × 15 MB/s = 750 MB on disk. Fill worker parks.

- **Low water (resume fill):** cache drops to 50% of horizon ahead of playback

  - RD: 17.5s ahead → resume

  - PM: 25s ahead → resume

- This creates steady drip-feeding to disk, not burst-then-stall.



**Memory pressure integration:**



Register `ComponentCallbacks2` to receive `onTrimMemory()` signals from the OS:

```kotlin

context.registerComponentCallbacks(object : ComponentCallbacks2 {

    override fun onTrimMemory(level: Int) {

        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {

            fillController.onMemoryWarning()

        }

    }

})

```



On memory warning: pause fill, call `evictBehindPlayback()` aggressively (retain 0 behind), and reduce disk cache max to 50% of original budget.



---



### 8. BandwidthMonitor — Throughput Tracking



**Purpose:** Sliding-window throughput estimate. Feeds into FillController for pacing decisions and provides diagnostics.



```kotlin

class BandwidthMonitor {

    private val windowMs = 5_000L

    private val samples = ConcurrentLinkedDeque<Sample>()



    fun onBytesTransferred(bytes: Long) {

        samples.addLast(Sample(SystemClock.elapsedRealtime(), bytes))

        evictOldSamples()

    }



    fun estimatedBytesPerSecond(): Long {

        evictOldSamples()

        val totalBytes = samples.sumOf { it.bytes }

        val elapsed = (samples.peekLast()?.timeMs ?: 0) - (samples.peekFirst()?.timeMs ?: 0)

        return if (elapsed > 0) totalBytes * 1000 / elapsed else 0

    }



    fun isThrottled(): Boolean {

        // If sustained throughput < 70% of peak for > 30s

    }

}

```



**Note on OkHttp EventListener:** OkHttp's EventListener must execute fast and avoid external locking or I/O. The bandwidth monitor callback should be a simple append to a concurrent deque — no blocking. ([OkHttp EventListener](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-event-listener/index.html))



---



### 9. SeekHandler — Fast Position Reset



**Purpose:** When the user seeks, the system must quickly: cancel in-flight fill work, check cache for the target position, reposition the fill worker, and let the playback DataSource serve from the new position.



**Seek within cached region (short seek):**

1. ExoPlayer closes and reopens DataSource at new position

2. CacheDataSource (read-only) checks SimpleCache → cache hit

3. Bytes served from disk immediately — zero network latency

4. FillController adjusts fill frontier if needed (but may already be ahead)



**Seek outside cached region (long seek):**

1. ExoPlayer closes and reopens DataSource at new position

2. CacheDataSource checks SimpleCache → cache miss

3. Falls through to upstream OkHttpDataSource for direct network read

4. Simultaneously: `fillController.onSeek(newPosition)` → fill worker cancels, restarts from new position

5. `evictBehindPlayback()` with `retainBehindBytes = 0` to reclaim all old cache

6. Fill worker begins writing at new position; future reads shift to cache hits



**Critical for 160 GB files:** Seeking from byte 0 to byte 120 GB must not trigger a linear scan of cache spans. SimpleCache's in-memory index is a `TreeMap` by position, so span lookup is O(log n). With 8-16 MB fragment sizes, a 160 GB file has ~10K-20K spans at most — lookup is negligible.



---



## Data Flow: Complete Lifecycle



### Playback Start

```

1. App resolves debrid provider URL

2. Create ProviderProfile for provider type

3. Create MemoryBudget from device capabilities

4. Open SimpleCache (or reuse singleton per cache dir)

5. Create CacheFillWorker with profile, cache, OkHttpClient

6. Create read-only CacheDataSource.Factory:

     .setCache(cache)

     .setCacheWriteDataSinkFactory(null)   // READ-ONLY

     .setUpstreamDataSourceFactory(okHttp) // fallback

7. Create ExoPlayer with tuned DefaultLoadControl(budget, bitrate)

8. player.prepare(ProgressiveMediaSource(cacheDataSourceFactory, mediaItem))

9. ExoPlayer calls DataSource.open() → first read is cache miss → upstream OkHttp

10. Simultaneously: fillWorker.start(url, contentLength, 0)

11. Fill worker begins writing chunks to SimpleCache on disk

12. Within seconds, playback reads shift from upstream to cache hits

13. FillController monitors: cache ahead grows → eventually reaches horizon → fill pauses

14. Playback consumes cached data → cache ahead shrinks → fill resumes

15. Steady state: fill worker keeps 30-60s ahead on disk, ExoPlayer keeps 10-18s in SampleQueue

```



### Playback Seek

```

1. User seeks to timestamp T (byte position P)

2. ExoPlayer: DataSource.close() + DataSource.open(DataSpec(position=P))

3. CacheDataSource checks cache at position P

4. IF HIT: serve from disk, zero latency

5. IF MISS: upstream OkHttp reads directly (no cache write)

6. FillController.onSeek(P):

   - fillWorker.seekTo(P): cancel in-flight, restart from P

   - evictBehindPlayback(P, retainBehind=0): reclaim all old cache

7. Fill worker builds cache ahead from P

8. Future reads transition from upstream-miss to cache-hit

```



### Memory Pressure Event

```

1. OS sends onTrimMemory(TRIM_MEMORY_RUNNING_LOW)

2. FillController.onMemoryWarning():

   - fillWorker.pause()

   - evictBehindPlayback(currentPosition, retainBehind=0)

   - Reduce disk cache budget to 50%

3. Playback continues from existing cache + upstream fallback

4. When pressure eases: fillWorker.resume()

```



---



## Thread Model



| Thread | Owns | Blocking I/O? |

|---|---|---|

| Main (UI) | Compose UI, player event callbacks, FillController state | No |

| ExoPlayer-Internal (loader) | DataSource.open/read/close, SampleQueue writes | Yes (disk reads via CacheDataSource, or network reads via upstream) |

| CacheFill-0 | First fill connection (HTTP read → cache write) | Yes (network + disk) |

| CacheFill-1 (optional) | Second fill connection (when maxConnections=2) | Yes (network + disk) |

| OkHttp-Dispatcher | HTTP connection management | Yes (managed by OkHttp) |



**Total threads:** 4-5 (vs. potentially 8+ with separate urgent/prefetch/warm-ahead/watchdog threads). Fewer threads = less context switching on the weak CPUs in TV sticks.



---



## Memory Budget Walkthrough (2 GB Fire TV Stick 4K)



```

Device RAM:        2048 MB

  - Android OS:    ~800 MB (kernel, system_server, launcher, etc.)

  - Available:     ~1248 MB for apps



App heap limit:    memoryClass = 256 MB (largeHeap = ~384 MB)



With largeHeap and no prior LMK kills:



  App baseline:       80 MB  (Compose, nav, Coil image cache, DI)

  CacheFillWorker:     1 MB  (2 × 512 KB read buffers)

  SimpleCache index:   5 MB  (CacheSpan TreeMap for ~10K spans)

  GC/transient:       30 MB  (headroom for allocations, GC pressure)

  ─────────────────────────

  Reserved:          116 MB

  SampleQueue:       268 MB  (384 - 116)



  At 120 Mbps:  268 MB = ~18s in SampleQueue (decoded, ready to render)

  On disk:      450-750 MB = 30-50s in SimpleCache (compressed, not in heap)

  ─────────────────────────

  Total buffer:  ~48-68 seconds



If previous session was LMK-killed (fall back to memoryClass = 256 MB):



  SampleQueue:       140 MB = ~9s in SampleQueue

  On disk:           same

  Total buffer:     ~39-59 seconds



Both scenarios provide substantial resilience. The difference is just how long

the player can sustain if the network drops completely.

```



**Compare with a naively layered design:**

```

  App baseline:       80 MB

  In-memory frontier: 100 MB  (paged buffer holding recent bytes)

  Prefetch scratch:    64 MB  (one full chunk staging area)

  Cache write-through: 30 MB  (TeeDataSource buffers)

  SampleQueue:        268 MB

  ─────────────────────────

  Total:             542 MB  → EXCEEDS 384 MB heap limit → OOM or LMK kill

```



The single-owner architecture saves ~194 MB by eliminating the frontier buffer, scratch, and write-through duplication.



---



## Edge Cases & Failure Modes



| Scenario | Handling | Source |

|---|---|---|

| **Cache miss on playback path** | Upstream OkHttp fallback (no write-through). Fill worker will catch up. | [CacheDataSource](https://developer.android.com/reference/androidx/media3/datasource/cache/CacheDataSource) |

| **Fill worker can't keep up** | Playback falls back to direct upstream reads. Works but without seek cache benefit. | By design |

| **Disk I/O slower than download** | BandwidthMonitor detects write stalls; FillController reduces chunk frequency. | Measured at runtime |

| **LMK kill on previous session** | `ApplicationExitInfo.REASON_LOW_MEMORY` detected → reduce to `memoryClass` heap, disable largeHeap. | [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo) |

| **Connection reset mid-chunk (RD)** | Retry with Range from last byte. OkHttp opens new connection automatically. | [OkHttp connections](https://square.github.io/okhttp/features/connections/) |

| **Seek to uncached position** | Cache miss → upstream direct read. Fill worker restarts at new position. Old cache evicted. | By design |

| **1 GB device (Fire TV Stick HD)** | `memoryClass` ~128 MB → SampleQueue = ~12 MB (~0.8s). Barely viable. Consider disabling parallel fill. | [TV memory guidance](https://developer.android.com/training/tv/playback/memory) |

| **Cache dir full (storage quota exceeded)** | Position-aware eviction. If still full, reduce `fillHorizonSeconds` to 10s. Last resort: disable fill, direct-only. | [StorageManager](https://developer.android.com/reference/android/os/storage/StorageManager) |

| **OkHttp dispatcher queue buildup** | Cap `maxRequests=4`, `maxRequestsPerHost=2`. Prevents unbounded request queuing in memory. | [OkHttp Dispatcher](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-dispatcher/index.html) |

| **Thousands of .exo cache files** | Use large fragment size (8-16 MB) in CacheDataSink to reduce file count. | [ExoPlayer #3696](https://github.com/google/ExoPlayer/issues/3696) |



---



## What This Design Explicitly Does NOT Do



1. **No in-memory frontier buffer.** Bytes go from network → disk → SampleQueue. No intermediate RAM staging beyond the 512 KB read buffer.

2. **No write-through on the playback path.** CacheDataSource is read-only. Writes happen only from the fill worker.

3. **No prefetch scratch arrays.** The fill worker writes directly to CacheDataSink, not to a full-chunk staging buffer.

4. **No warm-ahead on the playback thread.** Cache fill is background-only.

5. **No complex multi-lane scheduler.** One fill worker thread (or two for 2-connection providers). Simplicity over cleverness.

6. **No speculative parallelism beyond the fill horizon.** The fill worker stops when it's far enough ahead.



These are deliberate constraints, not missing features. Each one eliminates a source of byte duplication or unbounded memory growth.



---



## Verification Plan



### Unit Tests

- `ProviderProfile`: verify correct chunk sizes, connection pool configs, fill horizons per provider

- `CacheFillWorker`: mock OkHttp responses, verify writes to SimpleCache, verify retry/backoff, verify pause/resume

- `MemoryBudget`: mock ActivityManager with various `memoryClass` values, verify budget derivation

- `FillController`: verify state machine transitions, backpressure thresholds, seek behavior

- `Position-aware eviction`: verify spans behind playback are removed, spans ahead are retained



### Integration Tests

- Mock HTTP server returning range responses. Verify: fill worker populates cache → read-only CacheDataSource serves the data → no writes on playback path

- Simulate connection-close (RD): verify each chunk opens new connection, verify pre-warm overlap

- Simulate seek: verify cache eviction, fill restart, playback DataSource falls back to upstream



### On-Device Validation

- Fire TV Stick 4K: stream 80+ GB remux, monitor memory via `adb shell dumpsys meminfo <pid>`

- Verify peak heap stays under `largeMemoryClass` (384 MB)

- Monitor disk cache: `adb shell du -sh /data/data/com.nexio.tv/cache/stream-cache/` — should stay within budget

- Check `ApplicationExitInfo` after 3-hour session: no `REASON_LOW_MEMORY` exits

- Test seek at 25%, 50%, 75% of file: measure time-to-first-frame (<3s target for cache hit, <5s for cache miss)



### Stress Tests

- 3-hour continuous playback: verify no memory leaks, cache stays bounded

- Kill fill worker mid-chunk: verify playback continues via upstream fallback

- Simulate slow disk (10 MB/s): verify FillController adapts

- Simulate network drop for 30s: verify SampleQueue drains gracefully, rebuffers, recovers when network returns


