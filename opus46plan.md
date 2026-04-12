# Stable Build — Safe Patches for Bandwidth and Power Constrained Devices

## Status

**Patch 1: MERGED and resolved** — `targetBufferBytes` cap via `PlayerLoadControlFactory` + `MemoryBudget` eliminated OOM/GC crashes and rebuffering on 160 GB remux playback. Implementation ended up in `PlayerLoadControlFactory.kt` (not directly at `PlayerRuntimeControllerInitialization.kt:195` as originally noted — it was refactored into its own factory class).

The question now: are there safe additional patches for **bandwidth-constrained** (slow WiFi, weak radio, throttled hotspot) and **power-constrained** (Fire TV Stick HD, 1–2 GB RAM budget devices) Android TV targets?

---

## Architecture Summary (Post-Patch-1)

**Playback byte flow:**
```
HTTP → PRDS (2 parallel connections, 16 MB chunks)
     → CacheDataSource (write-through, 500 MB LRU disk cache, 2 MB fragments)
     → ProgressiveMediaSource → ExoPlayer SampleQueue → MediaCodec
```

**Warm-ahead byte flow (background thread `Nexio-vod-prefetch`):**
```
CacheWriter → CacheDataSource → progressiveUpstreamFactory
                                  ↳ ParallelRangeDataSource.Factory (when PRDS enabled)
                                       → 2 MORE HTTP connections
```

**Max concurrent connections: 4** (2 playback PRDS + 2 warm-ahead PRDS) when both are active.

**Key constants in `PlayerMediaSourceFactory.kt`:**
- `PREFETCH_BLOCK_BYTES = 16 MB` (bytes written per warm-ahead CacheWriter iteration)
- `PREFETCH_ACTIVE_GUARD_BYTES = 8 MB` (guard behind active read position)
- `PREFETCH_IDLE_SLEEP_MS = 250 ms` (sleep between warm-ahead iterations when idle)
- `PREFETCH_MAX_IDLE_CYCLES = 20`

**Key constant in `ParallelRangeDataSource.kt`:**
- `maxAhead = parallelConnections + 1 = 3` (chunks scheduled ahead)
- Buffer pool size = `parallelConnections + 2 = 4` buffers × 16 MB = 64 MB

---

## Why Original Patches 3 and 4 Are Risky for Constrained Devices

**Patch 3** (read-only CacheDataSource on playback path) was intended to remove write-through I/O from the loader thread. The problem: making playback read-only means warm-ahead becomes the *sole* writer. The warm-ahead already runs with `progressiveUpstreamFactory` as its upstream — when PRDS is enabled, each CacheWriter miss calls `ParallelRangeDataSource.Factory.createDataSource()`, spawning a full new PRDS instance with 2 parallel connections. Patch 3 would cause warm-ahead to run more aggressively (it now owns all writing), stealing more bandwidth on constrained WiFi — the opposite of the intended effect.

**Patch 4** (position guard in warm-ahead) adds a `WARM_AHEAD_MIN_LEAD_BYTES` guard but does not change the *upstream* the warm-ahead uses. Even with a larger guard, when warm-ahead does write, it still uses PRDS with 2 connections. The bandwidth theft only shifts farther ahead of the playhead.

**Both patches are safe on fast networks and capable devices. They are risky on bandwidth-limited or memory-limited devices.** Without first fixing the warm-ahead upstream (Patch A below), neither is safe to apply on constrained hardware.

---

## Safe Patches for Constrained Devices

### Patch A — Single-connection upstream for warm-ahead ✦ CRITICAL

**Problem:** `runWarmAheadLoop` (line ~367 in `PlayerMediaSourceFactory.kt`) receives `upstreamFactory: DataSource.Factory` from its call site. When PRDS is enabled, this is a `ParallelRangeDataSource.Factory`. Inside the loop:

```kotlin
val prefetchFactory = buildVodCacheDataSourceFactory(
    upstreamFactory = upstreamFactory,   // ← PRDS factory → spawns 2 connections on cache miss
    cache = cache,
    blockOnCache = true
)
val writer = CacheWriter(prefetchFactory.createDataSource() as CacheDataSource, dataSpec, null, null)
writer.cache()
```

Each 16 MB CacheWriter miss triggers a new PRDS instance fetching with 2 parallel connections → 4 total concurrent connections → bandwidth stolen from playback on constrained WiFi.

**Fix:** At the `runWarmAheadLoop` call site, pass `okHttpFactory` (single-connection direct OkHttp source) instead of `progressiveUpstreamFactory`.

```kotlin
// BEFORE — warm-ahead uses PRDS factory (2 connections per miss):
runWarmAheadLoop(
    streamUrl = resolvedUrl,
    upstreamFactory = progressiveUpstreamFactory,
    cache = cache,
    capBytes = capBytes
)

// AFTER — warm-ahead uses 1 direct HTTP connection:
runWarmAheadLoop(
    streamUrl = resolvedUrl,
    upstreamFactory = okHttpFactory,     // okHttpFactory is already in scope here
    cache = cache,
    capBytes = capBytes
)
```

**Why safe:**
- Active playback is unchanged — PRDS still runs with 2 parallel connections
- Warm-ahead is a background filler; it doesn't need parallelism. It's writing sequentially anyway
- Max concurrent connections drops from 4 to 3 (2 PRDS + 1 warm-ahead)
- On constrained WiFi, this reclaims 1 full HTTP connection worth of bandwidth for playback
- `okHttpFactory` is the `OkHttpDataSource.Factory` already used as the base for PRDS — it is already in scope at the warm-ahead launch site

**Risk:** None. Warm-ahead fills at the speed of 1 connection instead of 2. Since it runs ahead of playback and is capped by `PREFETCH_BLOCK_BYTES + PREFETCH_IDLE_SLEEP_MS` pacing, 1 connection is sufficient.

**Files:**
- `PlayerMediaSourceFactory.kt` — find the `runWarmAheadLoop(...)` call and change its `upstreamFactory` argument

---

### Patch B — PRDS-aware warm-ahead guard bytes ✦ HIGH

**Problem:** `PREFETCH_ACTIVE_GUARD_BYTES = 8 MB` (`PlayerMediaSourceFactory.kt:643`). PRDS schedules `maxAhead = parallelConnections + 1 = 3` chunks ahead of the read position — that's 3 × 16 MB = **48 MB** of in-flight data. The 8 MB guard means warm-ahead can start writing at `readPos + 8 MB`, directly inside the 48 MB window PRDS is already downloading. This causes duplicate downloads: same bytes fetched by both PRDS and warm-ahead on separate connections.

**Fix:** Increase the guard to cover the full PRDS look-ahead window:

```kotlin
// BEFORE (PlayerMediaSourceFactory.kt):
private const val PREFETCH_ACTIVE_GUARD_BYTES = 8L * 1024L * 1024L

// AFTER — covers maxAhead (3) × default chunkSize (16 MB) = 48 MB:
private const val PREFETCH_ACTIVE_GUARD_BYTES = 48L * 1024L * 1024L
```

If chunk size is user-configurable and accessible in this context, a dynamic formula is better:

```kotlin
// Dynamic version (if parallelProfile is accessible at warm-ahead launch):
val warmAheadGuardBytes = parallelProfile.chunkSizeMb.toLong() * 1024L * 1024L * (parallelProfile.connectionCount + 1)
// pass as parameter to runWarmAheadLoop
```

**Why safe:** Warm-ahead simply starts writing further ahead of the playhead. It still fills the same total bytes — just with no overlap with PRDS in-flight work. On a 170 GB file, starting at `readPos + 48 MB` instead of `readPos + 8 MB` makes no practical difference.

**Risk:** None. Worst case: warm-ahead fills slightly later relative to playback. The VOD cache still fills well ahead of any real seek.

**Files:**
- `PlayerMediaSourceFactory.kt:643` — `PREFETCH_ACTIVE_GUARD_BYTES` constant

---

### Patch C — Reduce warm-ahead block size for power-constrained devices ✦ HIGH

**Problem:** `PREFETCH_BLOCK_BYTES = 16 MB` (`PlayerMediaSourceFactory.kt:641`). On budget Fire TV Sticks with slow eMMC flash (10–20 MB/s sustained write speed), each `CacheWriter.cache()` call writes 16 MB continuously. At 20 MB/s that's an **800 ms contiguous I/O burst** that competes with any other flash activity, including the ExoPlayer loader writing to its internal buffers. This can cause I/O stalls that propagate to audio underruns.

**Fix:** Reduce block size from 16 MB to 4 MB:

```kotlin
// BEFORE:
private const val PREFETCH_BLOCK_BYTES = 16L * 1024L * 1024L

// AFTER:
private const val PREFETCH_BLOCK_BYTES = 4L * 1024L * 1024L
```

**Why safe:**
- Total bytes written to the VOD cache is unchanged — the loop still fills the same holes, just in 4 × smaller bursts
- The 250 ms idle sleep (`PREFETCH_IDLE_SLEEP_MS`) between bursts gives the flash controller time to service other I/O
- Memory pressure during warm-ahead is lower: at most 4 MB in-flight per write iteration instead of 16 MB
- I/O burst duration drops from ~800 ms to ~200 ms at 20 MB/s flash speed

**Risk:** None. Slightly more open/close cycles in CacheWriter, negligible overhead. The hole-finding logic handles this correctly — each loop iteration finds the next uncached hole starting where the previous one ended.

**Files:**
- `PlayerMediaSourceFactory.kt:641` — `PREFETCH_BLOCK_BYTES` constant

---

### Patch D — BandwidthMonitor-throttled warm-ahead ✦ MEDIUM

**Problem:** Warm-ahead runs at full speed regardless of available network bandwidth. On constrained WiFi where playback is barely keeping up, warm-ahead's background downloads compete with the playback TCP streams for the same bottleneck link.

**Context:** `BandwidthMonitor` already exists (`BandwidthMonitor.kt`) with `estimatedBytesPerSecond()` tracking a 5-second sliding window. It is wired to the PRDS `onTransportBytesDownloaded` callback in `selectProgressiveUpstreamFactory`. Need to verify the `BandwidthMonitor` instance is accessible within the `PlayerMediaSourceFactory` scope before this patch can be implemented.

**Fix:** In `runWarmAheadLoop`, before each `CacheWriter.cache()` block, check estimated bandwidth and back off when the network is congested:

```kotlin
// At the top of each write iteration in runWarmAheadLoop():
val estimatedBps = bandwidthMonitor?.estimatedBytesPerSecond() ?: Long.MAX_VALUE
if (estimatedBps in 1L until WARM_AHEAD_THROTTLE_THRESHOLD_BPS) {
    // Network is constrained — yield bandwidth to playback
    Thread.sleep(PREFETCH_CONSTRAINED_SLEEP_MS)
    continue
}
```

Where:
```kotlin
private const val WARM_AHEAD_THROTTLE_THRESHOLD_BPS = 5L * 1024L * 1024L   // 5 MB/s = 40 Mbps
private const val PREFETCH_CONSTRAINED_SLEEP_MS = 1_000L                    // 1 s backoff
```

**Why:** At 5 MB/s or below, the available bandwidth is below comfortable playback headroom for 126 Mbps content. Any warm-ahead download at this point takes bandwidth from playback. The 1 s sleep per iteration limits warm-ahead to 4 MB/s fill rate (4 MB block ÷ 1 s sleep, assuming Patch C is applied).

**Risk:** Low. On fast networks (`estimatedBps >= WARM_AHEAD_THROTTLE_THRESHOLD_BPS`), behavior is identical. On slow networks, warm-ahead backs off — which is exactly what constrained devices need.

**Prerequisite:** Verify `BandwidthMonitor` is instantiated as a field in `PlayerMediaSourceFactory` and populated via the `onTransportBytesDownloaded` lambda. If not wired, this patch requires adding the reference.

**Files:**
- `PlayerMediaSourceFactory.kt` — `runWarmAheadLoop` body; add `bandwidthMonitor` parameter or capture from outer scope
- `BandwidthMonitor.kt` — no changes needed, just consume `estimatedBytesPerSecond()`

---

### Patch E — Connection pool for connection-close providers ✦ LOW

**Problem:** OkHttp playback client (`NetworkModule.kt`) uses `ConnectionPool(5, 5, TimeUnit.MINUTES)`. For Real-Debrid (and other providers) that respond with `Connection: close`, every HTTP response closes the connection. The 5 pooled connections are never reused but hold TLS session state for 5 minutes — consuming ~1–2 MB each = 5–10 MB of wasted heap on every low-memory device.

**Fix:** Detect `Connection: close` on the first chunk response in `ParallelRangeDataSource`. Store as a session flag. Pass an optional callback to the playback OkHttp client factory to shrink the pool:

```kotlin
// In ParallelRangeDataSource after first chunk response header read:
val isConnectionClose = responseHeaders["Connection"]
    ?.any { it.contains("close", ignoreCase = true) } == true
if (isConnectionClose) onConnectionCloseDetected?.invoke()
```

```kotlin
// The callback, provided at construction time from PlayerMediaSourceFactory:
onConnectionCloseDetected = {
    // OkHttpClient.newBuilder() shares the dispatcher — safe to reconstruct
    okHttpClient = okHttpClient.newBuilder()
        .connectionPool(ConnectionPool(0, 1L, TimeUnit.SECONDS))
        .build()
}
```

**Risk:** Very low. `OkHttpClient.newBuilder()` creates a shallow copy that shares the same `Dispatcher`. In-flight requests are unaffected. Memory win of 5–10 MB on connection-close providers.

**Files:**
- `ParallelRangeDataSource.kt` — add `onConnectionCloseDetected: (() -> Unit)?` constructor param; detect header on first chunk
- `PlayerMediaSourceFactory.kt` / `NetworkModule.kt` — wire the callback to rebuild `okHttpClient` with empty pool

---

## Patches NOT Recommended for Constrained Devices

| Original Patch | Reason to skip on constrained devices |
|---|---|
| Patch 2 — larger chunks (32 MB) for connection-close | Buffer pool grows from 64 MB to 128 MB. On 1 GB devices with 256 MB heap, this directly increases OOM risk. Skip. |
| Patch 3 — read-only playback cache | Without Patch A, warm-ahead (sole writer) runs with PRDS upstream → MORE bandwidth stealing. Even with Patch A, the benefit is marginal: disk write rate is already <16 MB/s (capped by Patch C block size), and warm-ahead now uses only 1 connection. The risk/benefit ratio doesn't justify it. |
| Patch 4 — position guard in warm-ahead | Fully superseded by Patch B, which is simpler and more correct. Patch 4 guards by position but still allows warm-ahead to run with PRDS; Patch B keeps the same guard logic while Patch A eliminates the PRDS issue. |

---

## Implementation Order

Apply in this order; each patch is independently safe:

1. **Patch A** — warm-ahead upstream → `okHttpFactory` (zero regression risk, eliminates bandwidth stealing)
2. **Patch B** — guard bytes → 48 MB (zero regression risk, prevents PRDS overlap)
3. **Patch C** — block size → 4 MB (zero regression risk, reduces I/O burst length)
4. **Patch D** — BandwidthMonitor throttle (verify `BandwidthMonitor` is accessible first)
5. **Patch E** — connection pool reduction for connection-close providers (lowest priority, small gain)

Patches A, B, C together form a coherent set. They can be applied in one commit. Patch D requires a code path check first. Patch E is a separate independent change.

---

## Critical Files

| File | Patches |
|---|---|
| `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt` | A (call site), B (constant), C (constant), D (loop body) |
| `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt` | E (connection-close detection) |
| `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt` | E (connection pool callback) |
| `app/src/main/java/com/nexio/tv/ui/screens/player/BandwidthMonitor.kt` | D (read-only, no changes) |

---

## Verification

For each patch, stream a 100+ GB remux on constrained WiFi (hotspot throttled to 20–25 Mbps) for 15 minutes:

1. **Connections:** `adb logcat | grep "ParallelRangeDS"` — after Patch A, should show at most 3 simultaneous connections (not 4) when warm-ahead is active
2. **Memory:** `adb shell dumpsys meminfo <pid>` — Java Heap should stay under 85% of `maxMemory`
3. **Underruns:** `adb logcat | grep -E "AudioTrack|underrun|rebuffer"` — should show fewer/no underruns during warm-ahead write periods (measurable especially on Fire TV Stick)
4. **I/O bursts:** `adb shell cat /proc/<pid>/io` — `write_bytes` should show shorter, lower-amplitude bursts after Patch C vs baseline
5. **Seek quality:** Seek to 25%, 50%, 75% — time-to-first-frame should remain < 5 s (warm-ahead guard increase should not harm this)
