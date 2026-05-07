# Disk Spool Playback Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fully functional experimental disk-spool playback option for progressive VOD streams.

**Architecture:** Disk-spool mode is a cache-first progressive pipeline: a background OkHttp range writer fills a bounded ring-buffer file, a session exposes a logical contiguous frontier and seek-priority channel, and a Media3 `DataSource` reads only from disk. The setting is disabled by default and selected only when a persisted concurrent storage probe passes for the active spool directory.

**Tech Stack:** Kotlin, AndroidX Media3 `DataSource`, OkHttp, `MockWebServer`, `FileChannel`, `StatFs`, DataStore preferences, Robolectric tests via `testArm64DebugUnitTest`, Android TV ADB validation.

---

## Acceptance Requirements

- Use a ring-buffer file. Do not use `setLength()` as physical eviction. Logical byte position maps to physical offset `position % capacityBytes`.
- Stream OkHttp response bodies into disk in bounded blocks, default `512 KiB`; never use `response.body.bytes()` for a full network chunk.
- `DiskSpoolDataSource.close()` must not close the shared `DiskSpoolSession`; Media3 closes/reopens data sources on seeks.
- `PlayerMediaSourceFactory.createMediaSource()` must not synchronously call `writer.probe(url)`. The writer probes on its background thread, writes metadata into the session, and the `DataSource` reads content length from the session when available.
- The writer must check seek priority inside the streaming loop so seek response latency is not one full chunk.
- The writer must support cancellation via `session.isClosed()` and `Thread.currentThread().isInterrupted`.
- Add retry coverage for failed range requests.
- Add `@UnstableApi` to the Media3 `DataSource` implementation.
- The concurrent storage probe must delete the probe file in `finally`.
- The spool setting must not clear `autoplayMaxBitrateMbpsKey`.
- All Android API tests must use `@RunWith(RobolectricTestRunner::class)`.

---

## Files

- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/SpoolStorageCapability.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/SpoolStorageCapabilityProbe.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSession.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSource.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/*Test.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSpoolModeTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

---

## Task 1: Capability Policy and Probe Result Model

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/SpoolStorageCapability.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/SpoolStorageCapabilityTest.kt`

- [ ] **Step 1: Write failing tests**

Create `SpoolStorageCapabilityTest` with tests for:

```kotlin
assertEquals(63.0, SpoolStoragePolicy.targetBitrateMbps(63.0, 40.0), 0.01)
assertEquals(40.0, SpoolStoragePolicy.targetBitrateMbps(null, 40.0), 0.01)
assertEquals(80.0, SpoolStoragePolicy.targetBitrateMbps(null, null), 0.01)
```

And a pass/fail storage policy test using:

```kotlin
val pass = SpoolStorageProbeResult(
    writeMbps = 180.0,
    readMbps = 180.0,
    combinedMbps = 360.0,
    p99ReadLatencyMs = 40L,
    maxReadStallMs = 70L,
    measuredAtMs = 1_776_047_817_725L,
    durationMs = 60_000L,
    bytesWritten = 1_350_000_000L,
    bytesRead = 1_350_000_000L,
    spoolDirectoryPath = "/data/user/0/com.nexio.tv/cache/player_disk_spool"
)
```

Assert:

```kotlin
assertTrue(SpoolStoragePolicy.canSustain(pass, targetVideoMbps = 80.0))
assertFalse(SpoolStoragePolicy.canSustain(pass.copy(combinedMbps = 164.0, writeMbps = 82.0, readMbps = 82.0), targetVideoMbps = 80.0))
assertFalse(SpoolStoragePolicy.canSustain(pass.copy(p99ReadLatencyMs = 250L, maxReadStallMs = 900L), targetVideoMbps = 80.0))
assertTrue(SpoolStoragePolicy.isFresh(pass, nowMs = pass.measuredAtMs + 1_000L, spoolDirectoryPath = pass.spoolDirectoryPath))
assertFalse(SpoolStoragePolicy.isFresh(pass, nowMs = pass.measuredAtMs + 8L * 24L * 60L * 60L * 1000L, spoolDirectoryPath = pass.spoolDirectoryPath))
assertFalse(SpoolStoragePolicy.isFresh(pass, nowMs = pass.measuredAtMs + 1_000L, spoolDirectoryPath = "/other"))
```

- [ ] **Step 2: Verify RED**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.SpoolStorageCapabilityTest'
```

Expected: FAIL because `SpoolStorageProbeResult` and `SpoolStoragePolicy` do not exist.

- [ ] **Step 3: Implement**

Create `SpoolStorageCapability.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.spool

internal data class SpoolStorageProbeResult(
    val writeMbps: Double,
    val readMbps: Double,
    val combinedMbps: Double,
    val p99ReadLatencyMs: Long,
    val maxReadStallMs: Long,
    val measuredAtMs: Long,
    val durationMs: Long,
    val bytesWritten: Long,
    val bytesRead: Long,
    val spoolDirectoryPath: String
)

internal object SpoolStoragePolicy {
    private const val FALLBACK_TARGET_MBPS = 80.0
    private const val MIN_HEADROOM_MULTIPLIER = 2.5
    private const val MAX_P99_READ_LATENCY_MS = 100L
    private const val MAX_READ_STALL_MS = 500L
    private const val MAX_RESULT_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    fun targetBitrateMbps(streamBitrateMbps: Double?, userCapMbps: Double?): Double = when {
        streamBitrateMbps != null && streamBitrateMbps.isFinite() && streamBitrateMbps > 0.0 -> streamBitrateMbps
        userCapMbps != null && userCapMbps.isFinite() && userCapMbps > 0.0 -> userCapMbps
        else -> FALLBACK_TARGET_MBPS
    }

    fun canSustain(result: SpoolStorageProbeResult, targetVideoMbps: Double): Boolean {
        if (!targetVideoMbps.isFinite() || targetVideoMbps <= 0.0) return false
        if (result.durationMs < 30_000L) return false
        return result.combinedMbps >= targetVideoMbps * MIN_HEADROOM_MULTIPLIER &&
            result.writeMbps >= targetVideoMbps &&
            result.readMbps >= targetVideoMbps &&
            result.p99ReadLatencyMs <= MAX_P99_READ_LATENCY_MS &&
            result.maxReadStallMs <= MAX_READ_STALL_MS
    }

    fun isFresh(result: SpoolStorageProbeResult, nowMs: Long, spoolDirectoryPath: String): Boolean {
        return result.spoolDirectoryPath == spoolDirectoryPath &&
            nowMs - result.measuredAtMs in 0L..MAX_RESULT_AGE_MS
    }
}
```

- [ ] **Step 4: Verify GREEN**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.SpoolStorageCapabilityTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/SpoolStorageCapability.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/SpoolStorageCapabilityTest.kt
git commit -m "feat: add disk spool storage capability policy"
```

---

## Task 2: Concurrent Storage Probe

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/SpoolStorageCapabilityProbe.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/SpoolStorageCapabilityProbeTest.kt`

- [ ] **Step 1: Write failing tests**

Create tests for `summarizeForTesting(...)` and for overlap detection:

```kotlin
assertEquals(80.0, result.writeMbps, 0.01)
assertEquals(64.0, result.readMbps, 0.01)
assertEquals(144.0, result.combinedMbps, 0.01)
assertEquals(100L, result.p99ReadLatencyMs)
assertTrue(SpoolStorageCapabilityProbe.concurrentProbeEventsForTesting(10L, 12L, 60L, 62L).overlapped)
```

- [ ] **Step 2: Verify RED**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.SpoolStorageCapabilityProbeTest'
```

Expected: FAIL because `SpoolStorageCapabilityProbe` does not exist.

- [ ] **Step 3: Implement**

Implement `SpoolStorageCapabilityProbe` with:

- Two-worker `Executors.newFixedThreadPool(2)`.
- `CountDownLatch` so read and write loops start together.
- Separate `RandomAccessFile(probeFile, "rw")` and `RandomAccessFile(probeFile, "r")`.
- Direct `ByteBuffer` writes and reads using `FileChannel`.
- `SystemClock.elapsedRealtimeNanos()` for read latency.
- `try/finally` that always runs `executor.shutdownNow()` and `probeFile.delete()`.
- `summarizeForTesting(...)` returning `SpoolStorageProbeResult`.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.SpoolStorageCapabilityProbeTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/SpoolStorageCapabilityProbe.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/SpoolStorageCapabilityProbeTest.kt
git commit -m "feat: add concurrent disk spool storage probe"
```

---

## Task 3: Ring-Buffer Spool Session

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSession.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSessionTest.kt`

- [ ] **Step 1: Write failing tests**

Create `DiskSpoolSessionTest` with `@RunWith(RobolectricTestRunner::class)` and tests for:

- `writeRange(0, bytes)` advances frontier and `read(0)` returns the same bytes.
- overlapping writes `[0,100)` then `[50,200)` advance frontier to `200`.
- wrap-around: capacity `16`, write `[0,16)` then `[16,24)`, read `[16,24)` returns second bytes and `windowStartBytes() == 8`.
- wrap-boundary straddle: capacity `16`, write `[0,14)`, then write four bytes at logical position `14`; read `[14,18)` returns the four straddling bytes and proves split I/O across the ring boundary.
- seek ahead of frontier: `read(2048)` returns `-1`, `consumePriorityPosition()` returns `2048`, `rebaseTo(2048)` sets `windowStartBytes()` and `contiguousFrontierBytes()` to `2048`.
- `close()` unblocks a waiting reader.

- [ ] **Step 2: Verify RED**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolSessionTest'
```

Expected: FAIL because `DiskSpoolSession` does not exist.

- [ ] **Step 3: Implement**

Implement `DiskSpoolSession` with:

- Constructor `DiskSpoolSession(spoolFile: File, capacityBytes: Long, waitTimeoutMs: Long = 10_000L)`.
- `writer = RandomAccessFile(spoolFile, "rw")`, `writer.setLength(capacityBytes)`, then `reader = RandomAccessFile(spoolFile, "r")`.
- Atomics: `closed`, `windowStart`, `frontier`, `priorityPosition`, `contentLength`, `supportsRanges`.
- `setSourceMetadata(contentLength: Long, supportsRanges: Boolean)`.
- `contentLengthBytes()`, `supportsRanges()`, `isClosed()`, `contiguousFrontierBytes()`, `windowStartBytes()`, `consumePriorityPosition()`.
- `rebaseTo(position)` clears pending ranges and sets both `windowStart` and `frontier` to `position`.
- `writeRange(start, bytes, length)` writes using ring offset `start % capacityBytes`, coalesces pending ranges, advances logical window only.
- `read(position, buffer, offset, length)` waits using `SystemClock.elapsedRealtime()`, returns `-1` and records priority if position is unavailable or evicted.
- `close()` closes handles, notifies waiters, and deletes the spool file.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolSessionTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSession.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolSessionTest.kt
git commit -m "feat: add ring-buffer disk spool session"
```

---

## Task 4: Streaming Writer

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt`

- [ ] **Step 1: Write failing tests**

Create `DiskSpoolWriterTest` with `@RunWith(RobolectricTestRunner::class)` and `MockWebServer` tests for:

- `probe(url)` sends `Range: bytes=0-0`, receives `206` with `Content-Range: bytes 0-0/1048576`, and returns `contentLength=1048576`, `supportsRanges=true`.
- `downloadUntil(...)` streams ranges into `DiskSpoolSession` and advances frontier.
- writer rebases when `session.consumePriorityPosition()` reports a seek.
- retry test: first data range request returns `503`, second returns `206`, `rangeAttempts >= 2`, frontier reaches content size.

- [ ] **Step 2: Verify RED**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest'
```

Expected: FAIL because `DiskSpoolWriter` does not exist.

- [ ] **Step 3: Implement**

Implement `DiskSpoolWriter` with:

- Constructor `DiskSpoolWriter(okHttpClient: OkHttpClient, chunkBytes: Int = 18 * 1024 * 1024, ioBufferBytes: Int = 512 * 1024)`.
- `probe(url)` using OkHttp, not `HttpURLConnection`.
- `downloadUntil(url, session, targetFrontierBytes)`:
  - Calls `probe(url)` on the writer thread.
  - Calls `session.setSourceMetadata(metadata.contentLength, metadata.supportsRanges)`.
  - Logs `Log.d("DiskSpoolWriter", "spool writer starting length=...")`.
  - Loops while `!session.isClosed()` and `!Thread.currentThread().isInterrupted`.
  - Consumes priority before starting a range; if present, calls `session.rebaseTo(priority)` and sets `cursor = priority`.
- `downloadRangeIntoSession(...)`:
  - Performs OkHttp range request.
  - Streams `response.body.source().read(buffer, 0, maxRead)` into `session.writeRange(cursor, buffer, read)`.
  - Checks `session.consumePriorityPosition()` inside the streaming loop; if present, calls `session.rebaseTo(priority)`, logs it, and breaks the current range early.
  - Retries `IOException` up to 4 attempts with small backoff unless session is closed.
  - Never calls `response.body.bytes()` for a full chunk.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt
git commit -m "feat: add streaming disk spool writer"
```

---

## Task 5: DataSource and Concurrent Pipeline

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSource.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSourceTest.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolPipelineTest.kt`

- [ ] **Step 1: Write failing tests**

Create `DiskSpoolDataSourceTest` with `@RunWith(RobolectricTestRunner::class)`:

- Write four bytes into a session.
- Open `DiskSpoolDataSource(session, uri, contentLength=4)`.
- Assert `open()` returns `4`.
- Assert `read()` returns the four bytes.
- Call `dataSource.close()` and then assert `session.isClosed()` is still false.
- Call `session.close()` explicitly.

Create `DiskSpoolPipelineTest` with `@RunWith(RobolectricTestRunner::class)`:

- `MockWebServer` serves a `128 KiB` byte array via `206` range responses.
- Create session, writer, and `DiskSpoolDataSource(session, uri, contentLength = C.LENGTH_UNSET.toLong())`.
- Start writer on a background `Thread`.
- Call `dataSource.open(DataSpec(uri))` before the writer finishes.
- Read until the byte array is full.
- Assert bytes equal expected content.
- Join writer.
- Close dataSource and session.

- [ ] **Step 2: Verify RED**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolDataSourceTest' --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolPipelineTest'
```

Expected: FAIL because `DiskSpoolDataSource` does not exist.

- [ ] **Step 3: Implement**

Implement `DiskSpoolDataSource` with:

- `@UnstableApi`.
- Constructor `DiskSpoolDataSource(session: DiskSpoolSession, uri: Uri, contentLength: Long = C.LENGTH_UNSET.toLong())`.
- `open(dataSpec)`:
  - Sets position from `dataSpec.position`.
  - Determines remaining from `dataSpec.length`, constructor `contentLength`, or `session.contentLengthBytes()`.
  - May return `C.LENGTH_UNSET.toLong()` if writer metadata is not available yet.
  - Emits `onTransferInitializing` and `onTransferStart`.
- `read(...)`:
  - Reads from session.
  - Emits `onBytesTransferred`.
  - Returns `C.RESULT_END_OF_INPUT` only when remaining is zero or session reports no available bytes after its wait timeout.
- `close()`:
  - Emits `onTransferEnd`.
  - Clears internal state.
  - Does **not** close `session`.
- `Factory(session, uri, contentLength = C.LENGTH_UNSET.toLong())`.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolDataSourceTest' --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolPipelineTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSource.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSourceTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolPipelineTest.kt
git commit -m "feat: add disk spool datasource pipeline"
```

---

## Task 6: Settings, Probe Persistence, Factory Gate, and Cleanup

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSpoolModeTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Write failing tests**

Add settings tests:

```kotlin
assertEquals(ProgressivePlaybackDiskMode.OFF, settings.progressivePlaybackDiskMode)
dataStore.setAutoplayMaxBitrate(42.0)
dataStore.setProgressivePlaybackDiskMode(ProgressivePlaybackDiskMode.SPOOL)
assertEquals(42.0, dataStore.playerSettings.first().autoplayMaxBitrateMbps ?: 0.0, 0.01)
```

Add factory gate tests:

- positive: mode `SPOOL`, progressive HTTP URL, fresh passing probe, enough `StatFs` available bytes => returns `DiskSpoolDataSource.Factory`.
- negative: mode `OFF` => returns current progressive factory.
- negative: stale or failed probe => returns current progressive factory.
- negative: HLS/DASH => does not return `DiskSpoolDataSource.Factory`.

- [ ] **Step 2: Verify RED**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests '*SpoolModeTest' --tests 'com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest'
```

Expected: FAIL because settings and factory gate are not wired.

- [ ] **Step 3: Implement settings and persistence**

Add:

- `enum class ProgressivePlaybackDiskMode { OFF, SPOOL }`
- `PlayerSettings.progressivePlaybackDiskMode`
- `progressivePlaybackDiskModeKey`
- `spoolStorageProbeResultJsonKey`
- JSON parse/serialize for `SpoolStorageProbeResult`
- `setProgressivePlaybackDiskMode(mode)` without removing `autoplayMaxBitrateMbpsKey`
- `setSpoolStorageProbeResult(result)`

- [ ] **Step 4: Implement UI and ViewModel**

Add:

- ViewModel setter for disk-spool mode.
- ViewModel trigger to run `SpoolStorageCapabilityProbe(File(context.cacheDir, "player_disk_spool")).run()` off main thread.
- Settings toggle and probe status text in `PlaybackBufferNetworkSettings.kt`.
- Strings:

```xml
<string name="playback_buffer_disk_spool_mode">Disk spool playback</string>
<string name="playback_buffer_disk_spool_mode_sub">Experimental: plays progressive VOD from a warmed disk spool after storage passes a speed check.</string>
<string name="playback_buffer_disk_spool_probe">Run disk speed check</string>
```

- [ ] **Step 5: Implement factory gate and cleanup**

In `PlayerMediaSourceFactory`:

- Add `diskSpoolDir = File(context.cacheDir, "player_disk_spool")`.
- Add startup cleanup that deletes orphan spool files in `diskSpoolDir`.
- Insert the disk-spool gate in `selectProgressiveUpstreamFactory(...)`, before returning the normal `ParallelRangeDataSource.Factory` or OkHttp factory for progressive HTTP streams. Do not add the gate inside HLS/DASH branches.
- Gate only when:
  - mode is `SPOOL`,
  - URL is progressive HTTP,
  - persisted probe is fresh for `diskSpoolDir.absolutePath`,
  - storage policy passes dynamic target bitrate,
  - `StatFs.availableBytes >= requestedSpoolBytes + 512 MiB`.
- Create unique file `spool-${SystemClock.elapsedRealtimeNanos()}.bin`.
- Create session and writer.
- Start writer on a background executor and let `downloadUntil()` do the probe.
- Return `DiskSpoolDataSource.Factory(session, Uri.parse(url))` immediately. Do not call `writer.probe(url)` on the factory hot path.

- [ ] **Step 6: Verify GREEN**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests '*SpoolModeTest' --tests 'com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/main/res/values/strings.xml app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSpoolModeTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "feat: gate disk spool playback mode"
```

---

## ADB Validation

Run on Shield after `assembleArm64Debug` and install:

```bash
adb -s 192.168.50.13:5555 logcat -c
adb -s 192.168.50.13:5555 shell dumpsys gfxinfo com.nexio.tv reset
adb -s 192.168.50.13:5555 logcat -v threadtime -s NexioCapture:I PlayerMediaSource:D DiskSpoolWriter:D nexio.transport:I AndroidRuntime:E libc:W '*:S'
```

Expected:

- Probe result logs show concurrent storage pass before selection.
- `createMediaSource()` does not block on a network probe.
- Disk-spool source eventually reports known content length once writer metadata is available.
- Seeks ahead of the frontier cause rebase and writer reprioritization.
- `DataSource.close()` during seek does not delete the spool session.
- No `Process com.nexio.tv ... died`.
- No `OutOfMemoryError`.
- No app native `Fatal signal`.

## Self-Review

- Spec coverage: The plan includes full feature scope and addresses final review findings: no `session.close()` from `DataSource.close()`, no synchronous factory probe, early priority checks, retry test, `@UnstableApi`, probe cleanup in `finally`, streaming writes, ring-buffer storage, settings/probe persistence, cleanup, and ADB validation.
- Placeholder scan: No placeholder implementation steps are present.
- Type consistency: `SpoolStorageProbeResult`, `SpoolStoragePolicy`, `SpoolStorageCapabilityProbe`, `DiskSpoolSession`, `DiskSpoolWriter`, `DiskSpoolDataSource`, and `ProgressivePlaybackDiskMode` are introduced before use.
- Scope check: This remains a full playback option plan, not a prototype or reduced-scope vertical slice.
