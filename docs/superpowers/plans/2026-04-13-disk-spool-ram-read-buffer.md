# Disk Spool RAM Read Buffer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a user-configurable disk-spool RAM read-ahead buffer that is automatically capped by available heap and only reads from the disk spool file, never from the network.

**Architecture:** Keep the pipeline `network -> disk spool file -> RAM read buffer -> ExoPlayer`. Add a small `DiskSpoolReadAheadBuffer` used only by `DiskSpoolDataSource` for sequential reads; it fills from `DiskSpoolSession.read(...)`, resets on seek/fallback, and is bounded by an effective MB cap derived from user settings and `MemoryBudget`. Settings persist a requested MB value, while the factory computes the runtime-effective cap before constructing the disk spool data source.

**Tech Stack:** Kotlin, Android DataStore Preferences, Media3 `DataSource`, Robolectric unit tests, existing `PlayerSettingsDataStore`, `PlayerMediaSourceFactory`, `DiskSpoolDataSource`, `DiskSpoolSession`, and `MemoryBudget`.

---

## File Structure

- Modify `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
  - Add requested RAM read-buffer MB setting, defaults, bounds, persistence, and setter.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/MemoryBudget.kt`
  - Add a heap-scaled cap function for disk spool RAM read-ahead.
- Create `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolReadAheadBuffer.kt`
  - Own the bounded in-memory sequential buffer, reading exclusively from `DiskSpoolSession`.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSource.kt`
  - Use `DiskSpoolReadAheadBuffer` before direct disk reads; reset on seek, close, and fallback.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - Compute effective read-ahead bytes from settings and heap budget, pass into `DiskSpoolDataSource.Factory`.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - Push the setting from `PlayerSettings` into `PlayerMediaSourceFactory` at startup.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
  - Push setting updates into `PlayerMediaSourceFactory`.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
  - Add a settings slider for requested RAM read buffer MB under disk spool settings.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
  - Wire the new callback to the view model.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
  - Thread the callback through the playback settings section.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
  - Add a setter that delegates to `PlayerSettingsDataStore`.
- Modify `app/src/main/res/values/strings.xml`
  - Add English labels for the new slider.
- Test `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSpoolModeTest.kt`
  - Cover persistence and bounds.
- Test `app/src/test/java/com/nexio/tv/ui/screens/player/MemoryBudgetTest.kt`
  - Cover heap cap calculation.
- Test `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolReadAheadBufferTest.kt`
  - Cover disk-only sequential buffering and reset behavior.
- Test `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSourceTest.kt`
  - Cover use of the buffer and fallback reset.
- Test `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
  - Cover effective cap wiring into the data source factory.

---

### Task 1: Persist Requested RAM Read Buffer Size

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSpoolModeTest.kt`

- [ ] **Step 1: Write the failing persistence test**

Add this test to `PlayerSettingsDataStoreSpoolModeTest` after the disk spool startup-buffer tests:

```kotlin
@Test
fun `setting disk spool ram read buffer persists with bounds`() = runTest {
    val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

    dataStore.setDiskSpoolRamReadBufferMb(96)

    assertEquals(96, dataStore.playerSettings.first().diskSpoolRamReadBufferMb)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.local.PlayerSettingsDataStoreSpoolModeTest.setting disk spool ram read buffer persists with bounds'
```

Expected: compile failure for missing `setDiskSpoolRamReadBufferMb` and `diskSpoolRamReadBufferMb`.

- [ ] **Step 3: Add setting fields, bounds, key, read path, and setter**

In `PlayerSettings`, near the disk spool settings:

```kotlin
val diskSpoolRamReadBufferMb: Int = DEFAULT_DISK_SPOOL_RAM_READ_BUFFER_MB,
```

In `PlayerSettings.Companion`:

```kotlin
const val DEFAULT_DISK_SPOOL_RAM_READ_BUFFER_MB = 64
const val MIN_DISK_SPOOL_RAM_READ_BUFFER_MB = 0
const val MAX_DISK_SPOOL_RAM_READ_BUFFER_MB = 512
```

In `PlayerSettingsDataStore`, near other disk spool keys:

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

Add this setter near the other disk spool setters:

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
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.local.PlayerSettingsDataStoreSpoolModeTest.setting disk spool ram read buffer persists with bounds'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSpoolModeTest.kt
git commit -m "feat: persist disk spool ram buffer setting"
```

---

### Task 2: Add Heap-Scaled Effective Buffer Cap

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/MemoryBudget.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/MemoryBudgetTest.kt`

- [ ] **Step 1: Write the failing cap test**

Create `MemoryBudgetTest.kt` if it does not exist:

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
    fun `disk spool ram buffer can be disabled`() {
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

- [ ] **Step 3: Add the cap helper**

In `MemoryBudget.Companion`, add:

```kotlin
fun effectiveDiskSpoolReadAheadBytes(
    requestedMb: Int,
    heapLimitBytes: Long
): Long {
    if (requestedMb <= 0 || heapLimitBytes <= 0L) return 0L
    val requestedBytes = requestedMb.toLong() * 1024L * 1024L
    val heapCapBytes = (heapLimitBytes / 4L)
        .coerceIn(16L * 1024L * 1024L, 128L * 1024L * 1024L)
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

### Task 3: Implement Disk-Only RAM Read-Ahead Buffer

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolReadAheadBuffer.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolReadAheadBufferTest.kt`

- [ ] **Step 1: Write the failing disk-only buffer tests**

Create `DiskSpoolReadAheadBufferTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.spool

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    fun `reads sequential bytes from disk spool session`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)
        val buffer = DiskSpoolReadAheadBuffer(session, capacityBytes = 8L)
        session.writeRange(0L, byteArrayOf(1, 2, 3, 4, 5, 6), 6)

        val out = ByteArray(4)
        val read = buffer.read(position = 0L, target = out, offset = 0, length = out.size)

        assertEquals(4, read)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), out)
        session.close()
    }

    @Test
    fun `resets when requested position is outside buffered range`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)
        val buffer = DiskSpoolReadAheadBuffer(session, capacityBytes = 4L)
        session.writeRange(0L, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 8)

        val first = ByteArray(2)
        assertEquals(2, buffer.read(position = 0L, target = first, offset = 0, length = first.size))
        val second = ByteArray(2)
        assertEquals(2, buffer.read(position = 6L, target = second, offset = 0, length = second.size))

        assertArrayEquals(byteArrayOf(7, 8), second)
        session.close()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolReadAheadBufferTest'
```

Expected: compile failure for missing `DiskSpoolReadAheadBuffer`.

- [ ] **Step 3: Add `DiskSpoolReadAheadBuffer`**

Create `DiskSpoolReadAheadBuffer.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.spool

internal class DiskSpoolReadAheadBuffer(
    private val session: DiskSpoolSession,
    capacityBytes: Long
) {
    private val capacity = capacityBytes.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private var buffer = if (capacity > 0) ByteArray(capacity) else ByteArray(0)
    private var startPosition = 0L
    private var length = 0

    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (capacity <= 0 || length <= 0) return 0
        if (position < startPosition || position >= startPosition + this.length) {
            refill(position)
        }
        val relative = (position - startPosition).toInt()
        if (relative < 0 || relative >= this.length) return 0
        val bytesToCopy = minOf(length, this.length - relative)
        System.arraycopy(buffer, relative, target, offset, bytesToCopy)
        return bytesToCopy
    }

    fun reset() {
        startPosition = 0L
        length = 0
    }

    private fun refill(position: Long) {
        startPosition = position
        length = 0
        val read = session.read(position, buffer, 0, buffer.size)
        if (read > 0) {
            length = read
        }
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
git commit -m "feat: add disk-backed ram read-ahead buffer"
```

---

### Task 4: Use RAM Buffer in DiskSpoolDataSource

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSource.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolDataSourceTest.kt`

- [ ] **Step 1: Write failing data source test**

Add this test to `DiskSpoolDataSourceTest`:

```kotlin
@Test
fun `sequential reads use ram read ahead buffer fed from disk spool`() {
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
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolDataSourceTest.sequential reads use ram read ahead buffer fed from disk spool'
```

Expected: compile failure for missing `ramReadAheadBytes`.

- [ ] **Step 3: Wire buffer into data source**

In `DiskSpoolDataSource` constructor, add:

```kotlin
private val ramReadAheadBytes: Long = 0L
```

Add property:

```kotlin
private val readAheadBuffer = ramReadAheadBytes
    .takeIf { it > 0L }
    ?.let { DiskSpoolReadAheadBuffer(session, it) }
```

In `read(...)`, before `session.read(...)`, add:

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

In `open(...)`, when opening a new `DataSpec`, reset:

```kotlin
readAheadBuffer?.reset()
```

In `openFallback(...)`, before opening fallback, reset:

```kotlin
readAheadBuffer?.reset()
```

In `Factory`, add:

```kotlin
private val ramReadAheadBytes: Long = 0L
```

and pass it into `DiskSpoolDataSource(...)`:

```kotlin
ramReadAheadBytes = ramReadAheadBytes
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
git commit -m "feat: use ram read ahead for disk spool playback"
```

---

### Task 5: Wire Effective RAM Cap Through PlayerMediaSourceFactory

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Write failing factory wiring test**

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
        diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
        diskSpoolWriterExecutorForTesting = Executor { }
        diskSpoolReadAheadObserverForTesting = { bytes -> capturedReadAheadBytes += bytes }
    }

    factory.progressiveUpstreamFactoryForTesting(
        url = "https://real-debrid.com/path/video.mkv",
        headers = emptyMap()
    )

    assertTrue(capturedReadAheadBytes.single() in (16L * 1024L * 1024L)..(128L * 1024L * 1024L))
    factory.shutdown()
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest.progressivePlayback_passesHeapCappedRamReadAheadBufferToDiskSpoolDataSource'
```

Expected: compile failure for missing `diskSpoolRamReadBufferMb` and observer.

- [ ] **Step 3: Wire the setting and effective cap**

In `PlayerMediaSourceFactory`, add properties:

```kotlin
var diskSpoolRamReadBufferMb: Int = PlayerSettings.DEFAULT_DISK_SPOOL_RAM_READ_BUFFER_MB
internal var diskSpoolReadAheadObserverForTesting: ((Long) -> Unit)? = null
```

Add helper:

```kotlin
private fun resolveDiskSpoolRamReadAheadBytes(): Long {
    val bytes = MemoryBudget.effectiveDiskSpoolReadAheadBytes(
        requestedMb = diskSpoolRamReadBufferMb,
        heapLimitBytes = MemoryBudget(context).heapLimitBytes
    )
    diskSpoolReadAheadObserverForTesting?.invoke(bytes)
    return bytes
}
```

Pass the value into `DiskSpoolDataSource.Factory(...)`:

```kotlin
ramReadAheadBytes = resolveDiskSpoolRamReadAheadBytes()
```

In `PlayerRuntimeControllerInitialization`, near other factory settings:

```kotlin
mediaSourceFactory.diskSpoolRamReadBufferMb = playerSettings.diskSpoolRamReadBufferMb
```

In `PlayerRuntimeControllerObservers`, near other factory settings:

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

### Task 6: Add Settings UI for RAM Read Buffer

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add ViewModel setter**

In `PlaybackSettingsViewModel`, near disk spool setters:

```kotlin
suspend fun setDiskSpoolRamReadBufferMb(mb: Int) {
    playerSettingsDataStore.setDiskSpoolRamReadBufferMb(mb)
}
```

- [ ] **Step 2: Thread callback through `PlaybackSettingsSections`**

Add parameter:

```kotlin
onSetDiskSpoolRamReadBufferMb: (Int) -> Unit,
```

Pass it into `bufferAndNetworkSettingsItems(...)`:

```kotlin
onSetDiskSpoolRamReadBufferMb = onSetDiskSpoolRamReadBufferMb,
```

- [ ] **Step 3: Thread callback through `PlaybackSettingsScreen`**

Add argument:

```kotlin
onSetDiskSpoolRamReadBufferMb = { mb ->
    coroutineScope.launch { viewModel.setDiskSpoolRamReadBufferMb(mb) }
},
```

- [ ] **Step 4: Add settings slider**

In `PlaybackBufferNetworkSettings`, add parameter:

```kotlin
onSetDiskSpoolRamReadBufferMb: (Int) -> Unit,
```

Inside the `if (playerSettings.progressivePlaybackDiskMode == ProgressivePlaybackDiskMode.SPOOL)` block, add:

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
<string name="playback_buffer_disk_spool_ram_read_buffer_sub">Reads ahead from the disk spool into a heap-capped memory buffer. Does not open another network path.</string>
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
- Verify: all files touched above

- [ ] **Step 1: Run spool and media source tests**

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

- [ ] **Step 3: Manual device validation on Google TV Streamer**

Install the built APK on `192.168.50.58` with the project’s existing install flow. Then set:

```text
Disk spool size: 2048 MB
Startup spool buffer: 384 MB
Disk spool RAM buffer: 64 MB
```

Play the same `104.11 Mbps` stream. In logcat, confirm that disk spool is active and playback starts only after the startup spool buffer is filled:

```bash
adb -s 192.168.50.58:5555 logcat -d -v time | rg -i 'disk spool|DiskSpool|PlayerMediaSource'
```

Expected: logs show disk spool session creation and no crash. Playback should have fewer visible stalls than the fixed 512 MB / no RAM buffer path.

- [ ] **Step 4: Commit final fixes if verification required changes**

If any verification fix was needed:

```bash
git add app/src/main/java app/src/test/java app/src/main/res/values/strings.xml
git commit -m "fix: stabilize disk spool ram buffer"
```

If no verification fix was needed, do not create an empty commit.

---

## Self-Review

**Spec coverage:** The plan covers user-configurable RAM buffer size, automatic heap cap, disk-only fill source, data source integration, settings UI, and verification on `192.168.50.58`.

**Placeholder scan:** No placeholders are present; every implementation task names exact files, exact commands, and concrete code snippets.

**Type consistency:** The plan consistently uses `diskSpoolRamReadBufferMb`, `DiskSpoolReadAheadBuffer`, `ramReadAheadBytes`, and `MemoryBudget.effectiveDiskSpoolReadAheadBytes(...)`.
