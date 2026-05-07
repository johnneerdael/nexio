# Disk Spool Async RAM Read Buffer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a user-configurable, heap-capped RAM read-ahead buffer for disk spool playback that smooths disk stalls without introducing any new network reads.

**Architecture:** Keep the pipeline `network -> disk spool file -> async chunked RAM buffer -> ExoPlayer`. The RAM buffer is fed only by `DiskSpoolSession.read(...)` on a background worker and stores fixed-size chunks in a bounded ring, avoiding huge contiguous heap allocations. `DiskSpoolDataSource` consumes from this RAM ring for sequential reads and falls back to the existing disk-spool read path when the RAM ring has no bytes ready.

**Tech Stack:** Kotlin, Android DataStore Preferences, Media3 `DataSource`, Robolectric unit tests, existing `PlayerSettingsDataStore`, `MemoryBudget`, `PlayerMediaSourceFactory`, `DiskSpoolDataSource`, and `DiskSpoolSession`.

---

## Review Incorporation

This plan supersedes `docs/superpowers/plans/2026-04-13-disk-spool-ram-read-buffer.md`.

External review identified two blocking issues in the original plan:

- A synchronous `refill()` method would run on the ExoPlayer loader thread and would not provide real read-ahead.
- A single `ByteArray(capacity)` allocation could cause OOM or heap fragmentation on Android TV.

This v2 plan addresses those findings by using:

- A background producer thread.
- A bounded queue of fixed-size chunks.
- A heap cap that returns `0` rather than forcing a minimum allocation on very small heaps.
- Explicit buffer release on `DiskSpoolDataSource.close()`.
- Deterministic heap-cap tests through a factory heap override.

Additional review of v2 identified one blocking concurrency bug and several hardening items. The implementation tasks below must include these corrections:

- Track a generation number in `DiskSpoolReadAheadBuffer`; increment it on every `start(...)`, `reset(...)`, and `release()`. The worker must capture the generation before blocking in `session.read(...)` and discard the read if the generation changed before it reacquires the lock.
- When `session.read(...)` returns `-1`, compare the attempted read position with `session.windowStartBytes()`. If the attempted position is behind the disk spool window, reset the RAM buffer to `session.windowStartBytes()` so the worker cannot retry a permanently evicted range forever.
- Add a short backoff after non-positive reads when the session is still open and the thread is not interrupted.
- Allocate one scratch buffer per worker thread outside the loop, not a new scratch buffer on every iteration.
- Avoid `Thread.sleep(...)` in tests as a synchronization mechanism. Use `awaitBufferedBytesForTesting(...)` or a latch/observer.
- `DiskSpoolDataSource` must recreate or revive the read-ahead buffer after `close()`. A buffer released on close must not be reused as a permanently dead `val` on a later `open(...)`.

---

## File Structure

- Modify `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
  - Persist requested disk-spool RAM buffer size.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/MemoryBudget.kt`
  - Compute effective heap-capped RAM buffer size.
- Create `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolReadAheadBuffer.kt`
  - Async disk-only chunked ring buffer.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSource.kt`
  - Consume from RAM ring before direct disk reads; reset/release on lifecycle transitions.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - Compute effective read-ahead bytes and pass them to `DiskSpoolDataSource.Factory`.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - Initialize factory setting from `PlayerSettings`.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
  - Keep factory setting synced with runtime settings.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
  - Add RAM buffer slider.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
  - Wire callback.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
  - Thread callback.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
  - Add setter.
- Modify `app/src/main/res/values/strings.xml`
  - Add UI strings.
- Test `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSpoolModeTest.kt`
- Test `app/src/test/java/com/nexio/tv/ui/screens/player/MemoryBudgetTest.kt`
- Test `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolReadAheadBufferTest.kt`
- Test `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSourceTest.kt`
- Test `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

---

### Task 1: Persist RAM Read Buffer Setting

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSpoolModeTest.kt`

- [ ] **Step 1: Write the failing persistence test**

Add to `PlayerSettingsDataStoreSpoolModeTest`:

```kotlin
@Test
fun `setting disk spool ram read buffer persists with bounds`() = runTest {
    val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

    dataStore.setDiskSpoolRamReadBufferMb(96)

    assertEquals(96, dataStore.playerSettings.first().diskSpoolRamReadBufferMb)
}

@Test
fun `disk spool ram read buffer clamps to supported range`() = runTest {
    val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

    dataStore.setDiskSpoolRamReadBufferMb(999)

    assertEquals(
        PlayerSettings.MAX_DISK_SPOOL_RAM_READ_BUFFER_MB,
        dataStore.playerSettings.first().diskSpoolRamReadBufferMb
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.local.PlayerSettingsDataStoreSpoolModeTest.setting disk spool ram read buffer persists with bounds' --tests 'com.nexio.tv.data.local.PlayerSettingsDataStoreSpoolModeTest.disk spool ram read buffer clamps to supported range'
```

Expected: compile failure for missing `setDiskSpoolRamReadBufferMb` and `diskSpoolRamReadBufferMb`.

- [ ] **Step 3: Add settings code**

In `PlayerSettings`, near disk-spool settings:

```kotlin
val diskSpoolRamReadBufferMb: Int = DEFAULT_DISK_SPOOL_RAM_READ_BUFFER_MB,
```

In `PlayerSettings.Companion`:

```kotlin
const val DEFAULT_DISK_SPOOL_RAM_READ_BUFFER_MB = 64
const val MIN_DISK_SPOOL_RAM_READ_BUFFER_MB = 0
const val MAX_DISK_SPOOL_RAM_READ_BUFFER_MB = 256
```

In `PlayerSettingsDataStore`, near disk spool preference keys:

```kotlin
private val diskSpoolRamReadBufferMbKey = intPreferencesKey("disk_spool_ram_read_buffer_mb")
```

In the `PlayerSettings(...)` mapping:

```kotlin
diskSpoolRamReadBufferMb = (prefs[diskSpoolRamReadBufferMbKey]
    ?: PlayerSettings.DEFAULT_DISK_SPOOL_RAM_READ_BUFFER_MB)
    .coerceIn(
        PlayerSettings.MIN_DISK_SPOOL_RAM_READ_BUFFER_MB,
        PlayerSettings.MAX_DISK_SPOOL_RAM_READ_BUFFER_MB
    ),
```

Add setter near disk-spool setters:

```kotlin
suspend fun setDiskSpoolRamReadBufferMb(mb: Int) {
    store().edit { prefs ->
        prefs[diskSpoolRamReadBufferMbKey] = mb.coerceIn(
            PlayerSettings.MIN_DISK_SPOOL_RAM_READ_BUFFER_MB,
            PlayerSettings.MAX_DISK_SPOOL_RAM_READ_BUFFER_MB
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.local.PlayerSettingsDataStoreSpoolModeTest.setting disk spool ram read buffer persists with bounds' --tests 'com.nexio.tv.data.local.PlayerSettingsDataStoreSpoolModeTest.disk spool ram read buffer clamps to supported range'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSpoolModeTest.kt
git commit -m "feat: persist disk spool ram buffer setting"
```

---

### Task 2: Add Heap-Scaled Effective Cap

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/MemoryBudget.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/MemoryBudgetTest.kt`

- [ ] **Step 1: Write failing tests**

Create or extend `MemoryBudgetTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryBudgetTest {
    @Test
    fun `disk spool ram buffer is capped by heap budget`() {
        assertEquals(
            64L * 1024L * 1024L,
            MemoryBudget.effectiveDiskSpoolReadAheadBytes(
                requestedMb = 128,
                heapLimitBytes = 256L * 1024L * 1024L
            )
        )
    }

    @Test
    fun `disk spool ram buffer disables itself on very small heaps`() {
        assertEquals(
            0L,
            MemoryBudget.effectiveDiskSpoolReadAheadBytes(
                requestedMb = 64,
                heapLimitBytes = 48L * 1024L * 1024L
            )
        )
    }

    @Test
    fun `disk spool ram buffer can be disabled by setting`() {
        assertEquals(
            0L,
            MemoryBudget.effectiveDiskSpoolReadAheadBytes(
                requestedMb = 0,
                heapLimitBytes = 512L * 1024L * 1024L
            )
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.MemoryBudgetTest'
```

Expected: compile failure for missing `effectiveDiskSpoolReadAheadBytes`.

- [ ] **Step 3: Add cap helper**

In `MemoryBudget.Companion`, add:

```kotlin
fun effectiveDiskSpoolReadAheadBytes(
    requestedMb: Int,
    heapLimitBytes: Long
): Long {
    if (requestedMb <= 0 || heapLimitBytes <= 0L) return 0L
    val requestedBytes = requestedMb.toLong() * 1024L * 1024L
    val rawHeapCapBytes = heapLimitBytes / 4L
    val minimumUsefulBytes = 16L * 1024L * 1024L
    if (rawHeapCapBytes < minimumUsefulBytes) return 0L
    val heapCapBytes = rawHeapCapBytes.coerceAtMost(128L * 1024L * 1024L)
    return requestedBytes.coerceAtMost(heapCapBytes)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.MemoryBudgetTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/MemoryBudget.kt app/src/test/java/com/nexio/tv/ui/screens/player/MemoryBudgetTest.kt
git commit -m "feat: cap disk spool ram buffer by heap"
```

---

### Task 3: Implement Async Chunked Disk-Only Read-Ahead Buffer

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolReadAheadBuffer.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolReadAheadBufferTest.kt`

- [ ] **Step 1: Write failing async/chunk tests**

Create `DiskSpoolReadAheadBufferTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.spool

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiskSpoolReadAheadBufferTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `background worker fills chunks from disk spool session`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)
        session.writeRange(0L, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 8)
        val buffer = DiskSpoolReadAheadBuffer(
            session = session,
            capacityBytes = 8L,
            chunkBytes = 4,
            workerName = "test-disk-spool-read-ahead"
        )

        try {
            buffer.start(0L)
            assertTrue(buffer.awaitBufferedBytesForTesting(minBytes = 8L, timeoutMs = 1_000L))

            val first = ByteArray(4)
            assertEquals(4, buffer.read(position = 0L, target = first, offset = 0, length = first.size))
            val second = ByteArray(4)
            assertEquals(4, buffer.read(position = 4L, target = second, offset = 0, length = second.size))

            assertArrayEquals(byteArrayOf(1, 2, 3, 4), first)
            assertArrayEquals(byteArrayOf(5, 6, 7, 8), second)
        } finally {
            buffer.release()
            session.close()
        }
    }

    @Test
    fun `reset clears old chunks and restarts from requested position`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)
        session.writeRange(0L, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 8)
        val buffer = DiskSpoolReadAheadBuffer(
            session = session,
            capacityBytes = 8L,
            chunkBytes = 4,
            workerName = "test-disk-spool-read-ahead-reset"
        )

        try {
            buffer.start(0L)
            assertTrue(buffer.awaitBufferedBytesForTesting(minBytes = 4L, timeoutMs = 1_000L))
            buffer.reset(4L)
            assertTrue(buffer.awaitBufferedBytesForTesting(minBytes = 4L, timeoutMs = 1_000L))

            val out = ByteArray(4)
            assertEquals(4, buffer.read(position = 4L, target = out, offset = 0, length = out.size))
            assertArrayEquals(byteArrayOf(5, 6, 7, 8), out)
        } finally {
            buffer.release()
            session.close()
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolReadAheadBufferTest'
```

Expected: compile failure for missing `DiskSpoolReadAheadBuffer`.

- [ ] **Step 3: Add async chunked buffer**

Create `DiskSpoolReadAheadBuffer.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.spool

internal class DiskSpoolReadAheadBuffer(
    private val session: DiskSpoolSession,
    capacityBytes: Long,
    private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
    workerName: String = "Nexio-disk-spool-read-ahead"
) {
    private data class Chunk(
        val start: Long,
        val bytes: ByteArray,
        val length: Int
    ) {
        val endExclusive: Long = start + length
    }

    private val lock = Object()
    private val capacity = capacityBytes.coerceIn(0L, MAX_CAPACITY_BYTES)
    private val chunks = ArrayDeque<Chunk>()
    private var bufferedBytes = 0L
    private var nextReadPosition = 0L
    private var generation = 0L
    private var released = false
    private var worker: Thread? = null

    private val workerName = workerName

    fun start(position: Long) {
        if (capacity <= 0L) return
        synchronized(lock) {
            if (released) return
            generation += 1L
            resetLocked(position)
            if (worker?.isAlive == true) {
                lock.notifyAll()
                return
            }
            worker = Thread(::runWorker, workerName).apply {
                isDaemon = true
                start()
            }
            lock.notifyAll()
        }
    }

    fun reset(position: Long) {
        synchronized(lock) {
            generation += 1L
            resetLocked(position)
            lock.notifyAll()
        }
    }

    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (capacity <= 0L || length <= 0) return 0
        synchronized(lock) {
            if (released) return 0
            val chunk = chunks.firstOrNull { position >= it.start && position < it.endExclusive }
                ?: return 0
            val relative = (position - chunk.start).toInt()
            val bytesToCopy = minOf(length, chunk.length - relative)
            System.arraycopy(chunk.bytes, relative, target, offset, bytesToCopy)
            dropChunksBefore(position + bytesToCopy)
            lock.notifyAll()
            return bytesToCopy
        }
    }

    fun release() {
        val thread = synchronized(lock) {
            released = true
            generation += 1L
            chunks.clear()
            bufferedBytes = 0L
            lock.notifyAll()
            worker
        }
        thread?.interrupt()
    }

    internal fun awaitBufferedBytesForTesting(minBytes: Long, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (!released && bufferedBytes < minBytes) {
                val remainingMs = deadline - System.currentTimeMillis()
                if (remainingMs <= 0L) return false
                lock.wait(minOf(remainingMs, 50L))
            }
            return bufferedBytes >= minBytes
        }
    }

    private fun runWorker() {
        val scratchSize = chunkBytes.coerceAtLeast(1)
        val scratch = ByteArray(scratchSize)
        while (true) {
            val (startPosition, readGeneration) = synchronized(lock) {
                while (!released && bufferedBytes >= capacity) {
                    lock.wait(50L)
                }
                if (released) return
                nextReadPosition to generation
            }
            val read = session.read(startPosition, scratch, 0, scratch.size)
            if (read <= 0) {
                if (Thread.currentThread().isInterrupted || session.isClosed()) return
                synchronized(lock) {
                    if (generation == readGeneration && startPosition < session.windowStartBytes()) {
                        resetLocked(session.windowStartBytes())
                    }
                }
                Thread.sleep(50L)
                continue
            }
            synchronized(lock) {
                if (released) return
                if (generation != readGeneration || startPosition != nextReadPosition) {
                    return@synchronized
                }
                val bytes = scratch.copyOf(read)
                chunks += Chunk(start = startPosition, bytes = bytes, length = read)
                bufferedBytes += read.toLong()
                nextReadPosition = startPosition + read
                trimToCapacityLocked()
                lock.notifyAll()
            }
        }
    }

    private fun resetLocked(position: Long) {
        chunks.clear()
        bufferedBytes = 0L
        nextReadPosition = position.coerceAtLeast(0L)
    }

    private fun trimToCapacityLocked() {
        while (bufferedBytes > capacity && chunks.isNotEmpty()) {
            val removed = chunks.removeFirst()
            bufferedBytes -= removed.length
        }
    }

    private fun dropChunksBefore(position: Long) {
        while (chunks.isNotEmpty() && chunks.first().endExclusive <= position) {
            val removed = chunks.removeFirst()
            bufferedBytes -= removed.length
        }
    }

    private companion object {
        const val DEFAULT_CHUNK_BYTES = 1024 * 1024
        const val MAX_CAPACITY_BYTES = 256L * 1024L * 1024L
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolReadAheadBufferTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolReadAheadBuffer.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolReadAheadBufferTest.kt
git commit -m "feat: add async disk spool ram buffer"
```

---

### Task 4: Use Async RAM Buffer in DiskSpoolDataSource

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSource.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSourceTest.kt`

- [ ] **Step 1: Write failing data source test**

Add this test to `DiskSpoolDataSourceTest`:

```kotlin
@Test
fun `sequential reads use async ram read ahead buffer fed from disk spool`() {
    val session = DiskSpoolSession(
        File(temp.root, "movie.spool"),
        capacityBytes = 1_024L,
        waitTimeoutMs = 1_000L
    )
    val uri = Uri.parse("https://example.com/movie.bin")
    session.writeRange(0L, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 8)
    val dataSource = DiskSpoolDataSource(
        session = session,
        uri = uri,
        contentLength = 8L,
        ramReadAheadBytes = 8L
    )

    try {
        assertEquals(8L, dataSource.open(DataSpec(uri)))
        assertTrue(dataSource.awaitReadAheadBufferedBytesForTesting(minBytes = 8L, timeoutMs = 1_000L))
        val first = ByteArray(4)
        assertEquals(4, dataSource.read(first, 0, first.size))
        val second = ByteArray(4)
        assertEquals(4, dataSource.read(second, 0, second.size))

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), first)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), second)
    } finally {
        dataSource.close()
        session.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolDataSourceTest.sequential reads use async ram read ahead buffer fed from disk spool'
```

Expected: compile failure for missing `ramReadAheadBytes`.

- [ ] **Step 3: Wire buffer into data source**

In `DiskSpoolDataSource` constructor:

```kotlin
private val ramReadAheadBytes: Long = 0L
```

Add property:

```kotlin
private var readAheadBuffer: DiskSpoolReadAheadBuffer? = null
```

In `open(...)`, after `position` is set:

```kotlin
readAheadBuffer?.release()
readAheadBuffer = ramReadAheadBytes
    .takeIf { it > 0L }
    ?.let { DiskSpoolReadAheadBuffer(session, it) }
readAheadBuffer?.start(position)
```

In `read(...)`, before direct `session.read(...)`:

```kotlin
val bufferedRead = readAheadBuffer?.read(position, buffer, offset, readLength) ?: 0
if (bufferedRead > 0) {
    position += bufferedRead.toLong()
    if (remaining != C.LENGTH_UNSET.toLong()) {
        remaining -= bufferedRead.toLong()
    }
    val dataSpec = openedDataSpec ?: return bufferedRead
    transferListeners.forEach { it.onBytesTransferred(this, dataSpec, false, bufferedRead) }
    return bufferedRead
}
```

In `openFallback(...)` and `openFallbackForCurrentPosition()` before switching to fallback:

```kotlin
readAheadBuffer?.reset(position)
```

In `close()`:

```kotlin
readAheadBuffer?.release()
readAheadBuffer = null
```

Add this testing helper to `DiskSpoolDataSource` so tests do not use sleeps:

```kotlin
internal fun awaitReadAheadBufferedBytesForTesting(minBytes: Long, timeoutMs: Long): Boolean {
    return readAheadBuffer?.awaitBufferedBytesForTesting(minBytes, timeoutMs) ?: false
}
```

In `Factory`, add:

```kotlin
private val ramReadAheadBytes: Long = 0L
```

Use the complete updated `Factory` shape:

```kotlin
internal class Factory(
    private val session: DiskSpoolSession,
    private val uri: Uri,
    private val contentLength: Long = C.LENGTH_UNSET.toLong(),
    private val randomAccessFallbackFactory: DataSource.Factory? = null,
    private val startupPrebufferBytes: Long = 0L,
    private val ramReadAheadBytes: Long = 0L
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return DiskSpoolDataSource(
            session = session,
            uri = uri,
            contentLength = contentLength,
            randomAccessFallbackFactory = randomAccessFallbackFactory,
            startupPrebufferBytes = startupPrebufferBytes,
            ramReadAheadBytes = ramReadAheadBytes
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolDataSourceTest' --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolReadAheadBufferTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSource.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSourceTest.kt
git commit -m "feat: use async ram buffer for disk spool"
```

---

### Task 5: Wire Effective RAM Cap Through PlayerMediaSourceFactory

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Write deterministic factory wiring test**

Add to `PlayerMediaSourceFactoryTest`:

```kotlin
@Test
fun progressivePlayback_passesHeapCappedRamReadAheadBufferToDiskSpoolDataSource() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val capturedReadAheadBytes = mutableListOf<Long>()
    val factory = PlayerMediaSourceFactory(
        context = context,
        playbackOkHttpClient = noNetworkOkHttpClient()
    ).apply {
        progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
        diskSpoolRamReadBufferMb = 128
        diskSpoolHeapLimitBytesForTesting = 256L * 1024L * 1024L
        diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
        diskSpoolWriterExecutorForTesting = Executor { }
        diskSpoolReadAheadObserverForTesting = { bytes -> capturedReadAheadBytes += bytes }
    }

    factory.progressiveUpstreamFactoryForTesting(
        url = "https://real-debrid.com/path/video.mkv",
        headers = emptyMap()
    )

    assertEquals(listOf(64L * 1024L * 1024L), capturedReadAheadBytes)
    factory.shutdown()
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest.progressivePlayback_passesHeapCappedRamReadAheadBufferToDiskSpoolDataSource'
```

Expected: compile failure for missing factory setting and observer.

- [ ] **Step 3: Wire factory cap**

In `PlayerMediaSourceFactory`, add:

```kotlin
var diskSpoolRamReadBufferMb: Int = PlayerSettings.DEFAULT_DISK_SPOOL_RAM_READ_BUFFER_MB
internal var diskSpoolHeapLimitBytesForTesting: Long? = null
internal var diskSpoolReadAheadObserverForTesting: ((Long) -> Unit)? = null
```

Add helper:

```kotlin
private fun resolveDiskSpoolRamReadAheadBytes(): Long {
    val heapLimitBytes = diskSpoolHeapLimitBytesForTesting
        ?: MemoryBudget(context).heapLimitBytes
    val bytes = MemoryBudget.effectiveDiskSpoolReadAheadBytes(
        requestedMb = diskSpoolRamReadBufferMb,
        heapLimitBytes = heapLimitBytes
    )
    diskSpoolReadAheadObserverForTesting?.invoke(bytes)
    return bytes
}
```

Pass into `DiskSpoolDataSource.Factory(...)`:

```kotlin
ramReadAheadBytes = resolveDiskSpoolRamReadAheadBytes()
```

In `PlayerRuntimeControllerInitialization`:

```kotlin
mediaSourceFactory.diskSpoolRamReadBufferMb = playerSettings.diskSpoolRamReadBufferMb
```

In `PlayerRuntimeControllerObservers`:

```kotlin
mediaSourceFactory.diskSpoolRamReadBufferMb = settings.diskSpoolRamReadBufferMb
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest.progressivePlayback_passesHeapCappedRamReadAheadBufferToDiskSpoolDataSource'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "feat: wire heap capped disk spool ram buffer"
```

---

### Task 6: Add Settings UI

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add ViewModel setter**

In `PlaybackSettingsViewModel`:

```kotlin
suspend fun setDiskSpoolRamReadBufferMb(mb: Int) {
    playerSettingsDataStore.setDiskSpoolRamReadBufferMb(mb)
}
```

- [ ] **Step 2: Thread callback through settings sections**

Add parameter to `PlaybackSettingsSections`:

```kotlin
onSetDiskSpoolRamReadBufferMb: (Int) -> Unit,
```

Pass it into `bufferAndNetworkSettingsItems(...)`:

```kotlin
onSetDiskSpoolRamReadBufferMb = onSetDiskSpoolRamReadBufferMb,
```

- [ ] **Step 3: Wire callback in `PlaybackSettingsScreen`**

Add argument:

```kotlin
onSetDiskSpoolRamReadBufferMb = { mb ->
    coroutineScope.launch { viewModel.setDiskSpoolRamReadBufferMb(mb) }
},
```

- [ ] **Step 4: Add slider**

In `PlaybackBufferNetworkSettings`, add parameter:

```kotlin
onSetDiskSpoolRamReadBufferMb: (Int) -> Unit,
```

Inside the disk spool settings block:

```kotlin
item(key = "network_cache_disk_spool_ram_read_buffer") {
    SliderSettingsItem(
        icon = Icons.Default.Storage,
        title = stringResource(R.string.playback_buffer_disk_spool_ram_read_buffer),
        subtitle = stringResource(R.string.playback_buffer_disk_spool_ram_read_buffer_sub),
        value = playerSettings.diskSpoolRamReadBufferMb,
        valueText = "${playerSettings.diskSpoolRamReadBufferMb} MB",
        minValue = PlayerSettings.MIN_DISK_SPOOL_RAM_READ_BUFFER_MB,
        maxValue = PlayerSettings.MAX_DISK_SPOOL_RAM_READ_BUFFER_MB,
        step = 16,
        onValueChange = onSetDiskSpoolRamReadBufferMb,
        onFocused = onItemFocused
    )
}
```

- [ ] **Step 5: Add strings**

In `app/src/main/res/values/strings.xml`:

```xml
<string name="playback_buffer_disk_spool_ram_read_buffer">Disk spool RAM buffer</string>
<string name="playback_buffer_disk_spool_ram_read_buffer_sub">Reads ahead from disk spool into heap-capped memory. Does not open another network path.</string>
```

- [ ] **Step 6: Run compile-focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest' --tests 'com.nexio.tv.data.local.PlayerSettingsDataStoreSpoolModeTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt app/src/main/res/values/strings.xml
git commit -m "feat: expose disk spool ram buffer setting"
```

---

### Task 7: Final Verification

**Files:**
- Verify all touched files.

- [ ] **Step 1: Run focused verification**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.local.PlayerSettingsDataStoreSpoolModeTest' --tests 'com.nexio.tv.ui.screens.player.MemoryBudgetTest' --tests 'com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest' --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolReadAheadBufferTest' --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolDataSourceTest' --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolSessionTest' --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest'
```

Expected: PASS.

- [ ] **Step 2: Check diff hygiene**

Run:

```bash
git diff --check
git status --short
```

Expected: `git diff --check` prints nothing. `git status --short` shows only intentional files.

- [ ] **Step 3: Manual device validation**

Install on `192.168.50.58`, then set:

```text
Disk spool size: 2048 MB
Startup spool buffer: 384 MB
Disk spool RAM buffer: 64 MB
```

Play the same `104.11 Mbps` stream and inspect:

```bash
adb -s 192.168.50.58:5555 logcat -d -v time | rg -i 'disk spool|DiskSpool|PlayerMediaSource'
```

Expected: disk spool is active; playback has fewer visible stalls than the no-RAM-buffer path; there is no evidence of a second network reader caused by the RAM buffer.

- [ ] **Step 4: Commit verification fixes only if needed**

If verification required fixes:

```bash
git add app/src/main/java app/src/test/java app/src/main/res/values/strings.xml
git commit -m "fix: stabilize disk spool ram buffer"
```

If no verification fix was needed, do not create an empty commit.

---

## Self-Review

**Spec coverage:** This v2 plan covers a user-configurable RAM read buffer, heap cap scaling, disk-only source constraint, asynchronous chunked read-ahead, release behavior, UI wiring, tests, and device validation.

**Placeholder scan:** No placeholder markers are present. Every task contains concrete files, commands, and code snippets.

**Type consistency:** The plan consistently uses `diskSpoolRamReadBufferMb`, `DiskSpoolReadAheadBuffer`, `ramReadAheadBytes`, `diskSpoolHeapLimitBytesForTesting`, and `MemoryBudget.effectiveDiskSpoolReadAheadBytes(...)`.
