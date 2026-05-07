# Disk Spool Diagnostics State Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make disk spool diagnostics visibly run, report failures, preflight capacity, and always free diagnostic storage while only persisting successful diagnostic stats.

**Architecture:** Keep `SpoolStorageProbeResult` as the only persisted diagnostic artifact. Add a transient ViewModel/UI state for `NotChecked`, `Running`, and `Failed`, derive `Measured` from the persisted stats, and make the disk benchmark responsible for cleaning temporary diagnostic files in normal, failed, and canceled paths. Clean stale `spool-diagnostic-*` files during disk spool directory cleanup on app/factory start.

**Tech Stack:** Kotlin, Android ViewModel + StateFlow, Compose settings UI, Robolectric/JUnit, MockK, Media3-adjacent player settings code.

---

## File Structure

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageDiagnostic.kt`
  - Add diagnostic file naming constants.
  - Add cleanup helper for stale diagnostic files.
  - Add capacity preflight before writing files.
  - Ensure cleanup runs in `finally` even when `shouldContinue()` becomes false.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - Reuse the diagnostic cleanup helper when cleaning disk spool directory at app/factory startup.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
  - Add transient `StateFlow<DiskSpoolStorageProbeUiState>`.
  - Set `Running` immediately on click.
  - Do not clear persisted probe stats at the start of a run.
  - Set `Failed(message)` on failures; set `NotChecked` after success because measured state is derived from persisted stats.
  - Keep `Failed(message)` visible until the user starts another probe or the ViewModel is recreated.
  - Keep successful stats persisted through `setSpoolStorageProbeResult(result)`.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
  - Accept the transient diagnostic UI state.
  - Render `Running` and `Failed(message)` explicitly.
  - Continue rendering persisted `Measured` from `playerSettings.spoolStorageProbeResultJson`.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
  - Thread the transient diagnostic state through the network settings section call.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
  - Collect `viewModel.diskSpoolStorageProbeUiState`.
  - Pass it into the settings sections.

- Modify: `app/src/main/res/values/strings.xml`
  - Add text for running and failed diagnostic states.

- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageDiagnosticTest.kt`
  - Verify cleanup on success and failure.
  - Verify stale diagnostic cleanup only deletes diagnostic files.
  - Verify capacity failure happens before temporary files are created.

- Test: `app/src/test/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModelSpoolModeTest.kt`
  - Verify running state appears immediately.
  - Verify existing persisted stats are not cleared at run start.
  - Verify failures produce `Failed(message)` without clearing successful stats.
  - Verify success returns to idle and persists stats.
  - Replace older failure tests that expected `setSpoolStorageProbeResult(null)` with assertions against transient failure state.

- Test: `app/src/test/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettingsTest.kt`
  - Verify status resolver prefers running/failed transient states over persisted stats.
  - Update existing `resolveDiskSpoolDiagnosticStatus(...)` assertions to pass `probeUiState = DiskSpoolStorageProbeUiState.NotChecked`.

---

### Task 1: Add Diagnostic Cleanup And Capacity Guard

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageDiagnostic.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageDiagnosticTest.kt`

- [ ] **Step 1: Write failing cleanup tests**

Add these tests to `DiskSpoolStorageDiagnosticTest`:

```kotlin
@Test
fun `diagnostic deletes temp files after successful run`() {
    val directory = temp.newFolder("spool-diagnostic-clean-success")

    DiskSpoolStorageDiagnostic(
        directory = directory,
        totalBytes = 8L * 1024L * 1024L,
        sequentialBlockBytes = 1024 * 1024,
        randomBlockBytes = 4 * 1024,
        randomWriteEnabled = true,
        randomSeed = 7L
    ).run()

    assertEquals(
        emptyList<String>(),
        directory.listFiles()
            ?.filter { it.name.startsWith("spool-diagnostic-") }
            ?.map { it.name }
            ?: emptyList()
    )
}

@Test
fun `diagnostic deletes temp files after failed run`() {
    val directory = temp.newFolder("spool-diagnostic-clean-failure")
    var shouldContinue = true

    val failure = runCatching {
        DiskSpoolStorageDiagnostic(
            directory = directory,
            totalBytes = 8L * 1024L * 1024L,
            sequentialBlockBytes = 1024 * 1024,
            randomBlockBytes = 4 * 1024,
            randomWriteEnabled = true,
            randomSeed = 7L,
            shouldContinue = {
                shouldContinue.also { shouldContinue = false }
            }
        ).run()
    }.exceptionOrNull()

    assertTrue(failure is java.util.concurrent.CancellationException)
    assertEquals(
        emptyList<String>(),
        directory.listFiles()
            ?.filter { it.name.startsWith("spool-diagnostic-") }
            ?.map { it.name }
            ?: emptyList()
    )
}

@Test
fun `cleanup removes only stale diagnostic files`() {
    val directory = temp.newFolder("spool-diagnostic-stale-cleanup")
    val diagnosticFile = File(directory, "spool-diagnostic-123.bin").also { it.writeText("old") }
    val diagnosticRandomFile = File(directory, "spool-diagnostic-123.bin.random").also { it.writeText("old") }
    val diagnosticConcurrentFile = File(directory, "spool-diagnostic-123.bin.concurrent").also { it.writeText("old") }
    val playbackSpoolFile = File(directory, "spool-123.bin").also { it.writeText("active") }
    val unrelatedFile = File(directory, "keep.txt").also { it.writeText("keep") }

    DiskSpoolStorageDiagnostic.cleanupStaleDiagnosticFiles(directory)

    assertEquals(false, diagnosticFile.exists())
    assertEquals(false, diagnosticRandomFile.exists())
    assertEquals(false, diagnosticConcurrentFile.exists())
    assertEquals(true, playbackSpoolFile.exists())
    assertEquals(true, unrelatedFile.exists())
}

@Test
fun `diagnostic fails before creating files when capacity is insufficient`() {
    val directory = temp.newFolder("spool-diagnostic-capacity")

    val failure = runCatching {
        DiskSpoolStorageDiagnostic(
            directory = directory,
            totalBytes = 8L * 1024L * 1024L,
            sequentialBlockBytes = 1024 * 1024,
            randomBlockBytes = 4 * 1024,
            randomWriteEnabled = true,
            randomSeed = 7L,
            availableBytesProvider = { 1L }
        ).run()
    }.exceptionOrNull()

    assertTrue(failure is java.io.IOException)
    assertEquals(
        emptyList<String>(),
        directory.listFiles()
            ?.filter { it.name.startsWith("spool-diagnostic-") }
            ?.map { it.name }
            ?: emptyList()
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageDiagnosticTest
```

Expected: FAIL because `cleanupStaleDiagnosticFiles` and `availableBytesProvider` do not exist, and cancellation does not currently throw a typed cancellation failure from the diagnostic runner.

- [ ] **Step 3: Add cleanup, preflight, and cancellation support**

Modify the `DiskSpoolStorageDiagnostic` constructor and companion object:

```kotlin
internal class DiskSpoolStorageDiagnostic(
    private val directory: File,
    private val totalBytes: Long = 512L * 1024L * 1024L,
    private val sequentialBlockBytes: Int = 1024 * 1024,
    private val randomBlockBytes: Int = 4 * 1024,
    private val randomWriteEnabled: Boolean,
    private val randomSeed: Long = System.nanoTime(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
    private val availableBytesProvider: (File) -> Long = { it.usableSpace }
) {
    companion object {
        private const val DIAGNOSTIC_PREFIX = "spool-diagnostic-"

        fun cleanupStaleDiagnosticFiles(directory: File) {
            directory.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith(DIAGNOSTIC_PREFIX)) {
                    file.delete()
                }
            }
        }
    }

    private fun ensureRunning() {
        if (!shouldContinue()) {
            throw java.util.concurrent.CancellationException("Disk spool storage diagnostic canceled")
        }
    }

    private fun requiredBytes(): Long {
        var files = 2L // main file + concurrent write file
        if (randomWriteEnabled) files += 1L
        return totalBytes * files
    }

    private fun ensureCapacity() {
        val availableBytes = availableBytesProvider(directory)
        if (availableBytes < requiredBytes()) {
            throw java.io.IOException(
                "Need ${requiredBytes() / 1024L / 1024L} MiB free for disk spool diagnostic, " +
                    "found ${availableBytes / 1024L / 1024L} MiB"
            )
        }
    }
}
```

Then update `run()` to call cleanup and capacity preflight before creating files:

```kotlin
fun run(): SpoolStorageProbeResult {
    require(totalBytes > 0L) { "totalBytes must be positive" }
    require(sequentialBlockBytes > 0) { "sequentialBlockBytes must be positive" }
    require(randomBlockBytes > 0) { "randomBlockBytes must be positive" }

    directory.mkdirs()
    runCatching { cleanupStaleDiagnosticFiles(directory) }
    ensureCapacity()
    ensureRunning()

    val measuredAtMs = nowMs()
    val file = File(directory, "spool-diagnostic-${SystemClock.elapsedRealtimeNanos()}.bin")
    val randomFile = File(directory, "${file.name}.random")
    val concurrentFile = File(directory, "${file.name}.concurrent")
    return try {
        val sequentialWriteMbps = sequentialWrite(file)
        ensureRunning()
        val sequentialReadMbps = sequentialRead(file)
        ensureRunning()
        val concurrentSequential = concurrentSequentialReadWrite(
            readFile = file,
            writeFile = concurrentFile
        )
        ensureRunning()
        val concurrentRandom = if (randomWriteEnabled) {
            sequentialWrite(randomFile)
            ensureRunning()
            concurrentSequentialReadRandomWrite(readFile = file, writeFile = randomFile)
        } else {
            null
        }
        val durationMs = (nowMs() - measuredAtMs).coerceAtLeast(1L)

        SpoolStorageProbeResult(
            writeMbps = sequentialWriteMbps,
            readMbps = sequentialReadMbps,
            combinedMbps = concurrentSequential.writeMbps + concurrentSequential.readMbps,
            p99ReadLatencyMs = 0L,
            maxReadStallMs = 0L,
            measuredAtMs = measuredAtMs,
            durationMs = durationMs,
            bytesWritten = totalBytes,
            bytesRead = totalBytes,
            spoolDirectoryPath = directory.absolutePath,
            concurrentSequentialWriteMbps = concurrentSequential.writeMbps,
            concurrentSequentialReadMbps = concurrentSequential.readMbps,
            concurrentRandomWriteMbps = concurrentRandom?.writeMbps
        )
    } finally {
        file.delete()
        randomFile.delete()
        concurrentFile.delete()
    }
}
```

In each workload loop (`sequentialWrite`, `sequentialRead`, `concurrentSequentialReadWrite`, `concurrentSequentialReadRandomWrite`), keep the existing `&& shouldContinue()` loop condition and add `ensureRunning()` after the loop before `sync()` or after the read loop. For example:

```kotlin
while (position < totalBytes && shouldContinue()) {
    val length = minOf(buffer.size.toLong(), totalBytes - position).toInt()
    writer.seek(position)
    writer.write(buffer, 0, length)
    position += length.toLong()
}
ensureRunning()
writer.fd.sync()
```

- [ ] **Step 4: Run tests to verify diagnostic cleanup passes**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageDiagnosticTest
```

Expected: PASS.

- [ ] **Step 5: Commit diagnostic cleanup**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageDiagnostic.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageDiagnosticTest.kt
git commit -m "fix: clean disk spool diagnostic files"
```

---

### Task 2: Clean Stale Diagnostic Files On App/Factory Startup

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Write failing factory cleanup test**

Add this test to `PlayerMediaSourceFactoryTest` near existing disk spool cleanup tests:

```kotlin
@Test
fun cleanupDiskSpoolDirectory_removesStaleDiagnosticFiles() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val staleDiagnostic = context.cacheDir
        .resolve("player_disk_spool")
        .resolve("spool-diagnostic-stale.bin")
    val staleRandom = context.cacheDir
        .resolve("player_disk_spool")
        .resolve("spool-diagnostic-stale.bin.random")
    val staleConcurrent = context.cacheDir
        .resolve("player_disk_spool")
        .resolve("spool-diagnostic-stale.bin.concurrent")
    val activeSpool = context.cacheDir
        .resolve("player_disk_spool")
        .resolve("spool-active.bin")
    val unrelatedFile = context.cacheDir
        .resolve("player_disk_spool")
        .resolve("keep.txt")
    staleDiagnostic.parentFile?.mkdirs()
    staleDiagnostic.writeText("diagnostic")
    staleRandom.writeText("random")
    staleConcurrent.writeText("concurrent")
    activeSpool.writeText("spool")
    unrelatedFile.writeText("keep")

    PlayerMediaSourceFactory(context, OkHttpClient()).cleanupDiskSpoolDirectoryForTesting()

    // Diagnostic derivatives are removed by DiskSpoolStorageDiagnostic.cleanupStaleDiagnosticFiles(),
    // which intentionally matches the diagnostic prefix without a suffix constraint.
    assertEquals(false, staleDiagnostic.exists())
    assertEquals(false, staleRandom.exists())
    assertEquals(false, staleConcurrent.exists())
    // Playback spool files are removed by PlayerMediaSourceFactory's existing
    // startsWith("spool-") && endsWith(".bin") cleanup branch.
    assertEquals(false, activeSpool.exists())
    assertEquals(true, unrelatedFile.exists())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: FAIL because diagnostic derivative files such as `.random` and `.concurrent` are not removed by `cleanupDiskSpoolDirectory()` yet.

- [ ] **Step 3: Call diagnostic cleanup from factory cleanup**

In `PlayerMediaSourceFactory.cleanupDiskSpoolDirectory()`, after `if (directory.exists()) {` add:

```kotlin
DiskSpoolStorageDiagnostic.cleanupStaleDiagnosticFiles(directory)
```

The method should end up shaped like:

```kotlin
private fun cleanupDiskSpoolDirectory() {
    runCatching {
        val directory = diskSpoolDirectoryOrNull() ?: return
        if (directory.exists()) {
            DiskSpoolStorageDiagnostic.cleanupStaleDiagnosticFiles(directory)
            directory.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith("spool-") && file.name.endsWith(".bin")) {
                    file.delete()
                }
            }
        }
        directory.mkdirs()
    }.onFailure { error ->
        Log.w(TAG, "Failed to clean disk spool directory", error)
    }
}
```

Add this import if not already present:

```kotlin
import com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageDiagnostic
```

- [ ] **Step 4: Run factory test**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: PASS.

- [ ] **Step 5: Commit factory cleanup**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "fix: clear stale disk spool diagnostic files on startup"
```

---

### Task 3: Add Transient Probe UI State In ViewModel

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModelSpoolModeTest.kt`

- [ ] **Step 1: Write failing ViewModel tests**

Add these imports to `PlaybackSettingsViewModelSpoolModeTest`:

```kotlin
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertNull
```

Add these tests:

```kotlin
@Test
fun `disk spool storage probe exposes running state without clearing persisted result`() {
    val playerSettingsDataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
    val context = mockk<Context>(relaxed = true)
    val cacheDirectory = temp.newFolder("cache-dir")
    val probeStarted = CountDownLatch(1)
    val releaseProbe = CountDownLatch(1)
    every { context.applicationContext } returns context
    every { context.cacheDir } returns cacheDirectory

    val viewModel = createViewModel(playerSettingsDataStore).apply {
        diskSpoolStorageProbeRunnerForTesting = { directory, _ ->
            probeStarted.countDown()
            assertTrue(releaseProbe.await(5, TimeUnit.SECONDS))
            passingProbeResult(directory)
        }
    }

    viewModel.runDiskSpoolStorageProbe(context)
    assertTrue(probeStarted.await(5, TimeUnit.SECONDS))

    assertEquals(DiskSpoolStorageProbeUiState.Running, viewModel.diskSpoolStorageProbeUiState.value)
    coVerify(exactly = 0) {
        playerSettingsDataStore.setSpoolStorageProbeResult(null)
    }

    releaseProbe.countDown()
    coVerify(timeout = 5_000) {
        playerSettingsDataStore.setSpoolStorageProbeResult(match<SpoolStorageProbeResult> { true })
    }
}

@Test
fun `disk spool storage probe failure exposes failed state without clearing stats`() {
    val playerSettingsDataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
    val context = mockk<Context>(relaxed = true)
    val cacheDirectory = temp.newFolder("cache-dir")
    every { context.applicationContext } returns context
    every { context.cacheDir } returns cacheDirectory

    val viewModel = createViewModel(playerSettingsDataStore).apply {
        diskSpoolStorageProbeRunnerForTesting = { _, _ ->
            throw IOException("not enough free space")
        }
    }

    viewModel.runDiskSpoolStorageProbe(context)
    advanceUntilIdle()

    val state = viewModel.diskSpoolStorageProbeUiState.value
    assertTrue(state is DiskSpoolStorageProbeUiState.Failed)
    assertEquals("not enough free space", (state as DiskSpoolStorageProbeUiState.Failed).message)
    coVerify(exactly = 0) {
        playerSettingsDataStore.setSpoolStorageProbeResult(null)
    }
}

@Test
fun `disk spool storage probe no directory exposes failed state`() {
    val playerSettingsDataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
    val context = mockk<Context>(relaxed = true)
    val cacheDirectory = temp.newFolder("cache-dir")
    every { context.applicationContext } returns context
    every { context.cacheDir } returns cacheDirectory

    val viewModel = createViewModel(playerSettingsDataStore).apply {
        diskSpoolDirectoryResolverForTesting = { _, _ -> null }
    }

    viewModel.runDiskSpoolStorageProbe(context)
    advanceUntilIdle()

    val state = viewModel.diskSpoolStorageProbeUiState.value
    assertTrue(state is DiskSpoolStorageProbeUiState.Failed)
    assertEquals("Disk spool storage location is unavailable", (state as DiskSpoolStorageProbeUiState.Failed).message)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.settings.PlaybackSettingsViewModelSpoolModeTest
```

Expected: FAIL because `DiskSpoolStorageProbeUiState` and `diskSpoolStorageProbeUiState` do not exist, and the viewmodel still clears persisted result at the start/failure.

- [ ] **Step 3: Add transient state type and StateFlow**

In `PlaybackSettingsViewModel.kt`, add imports:

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
```

Add this sealed class near the top-level settings UI model declarations:

```kotlin
sealed class DiskSpoolStorageProbeUiState {
    object NotChecked : DiskSpoolStorageProbeUiState()
    object Running : DiskSpoolStorageProbeUiState()
    data class Failed(val message: String) : DiskSpoolStorageProbeUiState()
}
```

Failed state is intentionally transient and retry-driven: it remains visible until the user starts another probe or the ViewModel is recreated. Do not add an auto-dismiss timeout in this pass; hiding failures would reintroduce the "nothing happened" ambiguity.

Inside `PlaybackSettingsViewModel`, add:

```kotlin
private val _diskSpoolStorageProbeUiState =
    MutableStateFlow<DiskSpoolStorageProbeUiState>(DiskSpoolStorageProbeUiState.NotChecked)
val diskSpoolStorageProbeUiState: StateFlow<DiskSpoolStorageProbeUiState> =
    _diskSpoolStorageProbeUiState
```

- [ ] **Step 4: Rewrite probe state transitions**

Replace `runDiskSpoolStorageProbe()` with:

```kotlin
@MainThread
fun runDiskSpoolStorageProbe(context: Context) {
    val probeGeneration = diskSpoolStorageProbeGeneration.incrementAndGet()
    diskSpoolStorageProbeJob?.cancel()
    _diskSpoolStorageProbeUiState.value = DiskSpoolStorageProbeUiState.Running
    val applicationContext = context.applicationContext
    diskSpoolStorageProbeJob = viewModelScope.launch(Dispatchers.IO) {
        fun isCurrentProbeGeneration(): Boolean {
            return coroutineContext.isActive &&
                diskSpoolStorageProbeGeneration.get() == probeGeneration
        }
        suspend fun commitProbeResultIfCurrent(result: SpoolStorageProbeResult) {
            diskSpoolStorageProbeCommitMutex.withLock {
                if (isCurrentProbeGeneration()) {
                    playerSettingsDataStore.setSpoolStorageProbeResult(result)
                    _diskSpoolStorageProbeUiState.value = DiskSpoolStorageProbeUiState.NotChecked
                }
            }
        }
        suspend fun failProbeIfCurrent(message: String) {
            diskSpoolStorageProbeCommitMutex.withLock {
                if (isCurrentProbeGeneration()) {
                    _diskSpoolStorageProbeUiState.value = DiskSpoolStorageProbeUiState.Failed(message)
                }
            }
        }
        try {
            coroutineContext.ensureActive()
            if (!isCurrentProbeGeneration()) return@launch
            val settings = playerSettingsDataStore.playerSettings.first()
            val spoolDirectory = diskSpoolDirectoryResolverForTesting?.invoke(
                applicationContext,
                settings.diskSpoolStorageLocation
            ) ?: DiskSpoolStorageResolver.resolveSpoolDirectory(
                applicationContext,
                settings.diskSpoolStorageLocation
            )
            if (spoolDirectory == null) {
                failProbeIfCurrent("Disk spool storage location is unavailable")
                return@launch
            }
            val shouldContinue = {
                coroutineContext.isActive && !Thread.currentThread().isInterrupted
            }
            val result = diskSpoolStorageProbeRunnerForTesting?.invoke(spoolDirectory, shouldContinue)
                ?: DiskSpoolStorageDiagnostic(
                    directory = spoolDirectory,
                    randomWriteEnabled = settings.useParallelConnections,
                    shouldContinue = shouldContinue
                ).run()
            coroutineContext.ensureActive()
            if (!isCurrentProbeGeneration()) return@launch
            commitProbeResultIfCurrent(result)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            failProbeIfCurrent(error.message ?: "Disk spool storage diagnostic failed")
            Log.w(TAG, "Disk spool storage probe failed", error)
        }
    }
}
```

This intentionally does not call `setSpoolStorageProbeResult(null)`. Successful stats are the only persisted diagnostic artifact.

Add this import for the main-thread contract:

```kotlin
import androidx.annotation.MainThread
```

- [ ] **Step 5: Run ViewModel tests**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.settings.PlaybackSettingsViewModelSpoolModeTest
```

Expected: PASS after updating older tests that expected `setSpoolStorageProbeResult(null)` on failure. Update those older assertions to check `DiskSpoolStorageProbeUiState.Failed` instead.

Specific existing tests to update:
- Rename `failed disk spool storage probe clears persisted result` to `failed disk spool storage probe reports failed state without clearing persisted result`.
- Replace its `coVerify { playerSettingsDataStore.setSpoolStorageProbeResult(null) }` assertion with:

```kotlin
advanceUntilIdle()
val state = viewModel.diskSpoolStorageProbeUiState.value
assertTrue(state is DiskSpoolStorageProbeUiState.Failed)
coVerify(exactly = 0) {
    playerSettingsDataStore.setSpoolStorageProbeResult(null)
}
```

- In `superseded failed disk spool storage probe does not clear newer probe state`, replace the exact count assertion for null writes with:

```kotlin
coVerify(exactly = 0) {
    playerSettingsDataStore.setSpoolStorageProbeResult(null)
}
```

- In `older delayed disk spool storage probe commit cannot overwrite newer successful result`, remove `nullWrites` / `staleClearEntered` logic and use latches in the probe runner itself to control stale failure ordering. Keep the final assertion:

```kotlin
assertEquals(successResult, committedResults.last())
coVerify(exactly = 0) {
    playerSettingsDataStore.setSpoolStorageProbeResult(null)
}
```

- [ ] **Step 6: Commit ViewModel state**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt app/src/test/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModelSpoolModeTest.kt
git commit -m "fix: expose disk spool diagnostic run state"
```

---

### Task 4: Render Running And Failed Diagnostic States

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettingsTest.kt`

- [ ] **Step 1: Add strings**

Add to `strings.xml` near the existing disk spool diagnostic strings:

```xml
<string name="playback_buffer_disk_spool_diagnostic_running">Storage diagnostic is running...</string>
<string name="playback_buffer_disk_spool_diagnostic_failed">Storage diagnostic failed: %1$s</string>
```

- [ ] **Step 2: Write failing resolver tests**

Add to `PlaybackBufferNetworkSettingsTest`:

```kotlin
@Test
fun diskSpoolDiagnosticStatus_prefersRunningAndFailedTransientState() {
    val path = "/data/user/0/com.nexio.tv/cache/player_disk_spool"
    val nowMs = 1_776_047_818_725L
    val passing = SpoolStorageProbeResult(
        writeMbps = 180.0,
        readMbps = 180.0,
        combinedMbps = 360.0,
        p99ReadLatencyMs = 40L,
        maxReadStallMs = 70L,
        measuredAtMs = nowMs - 1_000L,
        durationMs = 60_000L,
        bytesWritten = 1_350_000_000L,
        bytesRead = 1_350_000_000L,
        spoolDirectoryPath = path
    )

    assertEquals(
        DiskSpoolDiagnosticStatus.Running,
        resolveDiskSpoolDiagnosticStatus(
            result = passing,
            probeUiState = DiskSpoolStorageProbeUiState.Running,
            nowMs = nowMs,
            spoolDirectoryPath = path
        )
    )
    assertEquals(
        DiskSpoolDiagnosticStatus.Failed("not enough free space"),
        resolveDiskSpoolDiagnosticStatus(
            result = passing,
            probeUiState = DiskSpoolStorageProbeUiState.Failed("not enough free space"),
            nowMs = nowMs,
            spoolDirectoryPath = path
        )
    )
}
```

- [ ] **Step 3: Run resolver test to verify it fails**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.settings.PlaybackBufferNetworkSettingsTest
```

Expected: FAIL because `DiskSpoolDiagnosticStatus.Running`, `Failed`, and the new `probeUiState` parameter do not exist.

- [ ] **Step 4: Extend status resolver**

Update `DiskSpoolDiagnosticStatus`:

```kotlin
internal sealed class DiskSpoolDiagnosticStatus {
    object NotChecked : DiskSpoolDiagnosticStatus()
    object Running : DiskSpoolDiagnosticStatus()
    object Stale : DiskSpoolDiagnosticStatus()
    data class Failed(val message: String) : DiskSpoolDiagnosticStatus()
    data class Measured(
        val sequentialWriteMbps: Int,
        val sequentialReadMbps: Int,
        val randomWriteMbps: Int?
    ) : DiskSpoolDiagnosticStatus()
}
```

Update `resolveDiskSpoolDiagnosticStatus`:

```kotlin
internal fun resolveDiskSpoolDiagnosticStatus(
    result: SpoolStorageProbeResult?,
    probeUiState: DiskSpoolStorageProbeUiState,
    nowMs: Long,
    spoolDirectoryPath: String
): DiskSpoolDiagnosticStatus {
    when (probeUiState) {
        DiskSpoolStorageProbeUiState.Running -> return DiskSpoolDiagnosticStatus.Running
        is DiskSpoolStorageProbeUiState.Failed ->
            return DiskSpoolDiagnosticStatus.Failed(probeUiState.message)
        DiskSpoolStorageProbeUiState.NotChecked -> Unit
    }
    if (result == null) return DiskSpoolDiagnosticStatus.NotChecked
    if (!SpoolStoragePolicy.isFresh(result, nowMs, spoolDirectoryPath)) {
        return DiskSpoolDiagnosticStatus.Stale
    }
    return DiskSpoolDiagnosticStatus.Measured(
        sequentialWriteMbps = (result.concurrentSequentialWriteMbps ?: result.writeMbps).roundToInt(),
        sequentialReadMbps = (result.concurrentSequentialReadMbps ?: result.readMbps).roundToInt(),
        randomWriteMbps = result.concurrentRandomWriteMbps?.roundToInt()
    )
}
```

Update every existing `resolveDiskSpoolDiagnosticStatus(...)` call site to pass `probeUiState`.
Current required updates:

```kotlin
// PlaybackBufferNetworkSettings.kt
val status = resolveDiskSpoolDiagnosticStatus(
    result = SpoolStorageProbeResult.fromJsonOrNull(playerSettings.spoolStorageProbeResultJson),
    probeUiState = diskSpoolStorageProbeUiState,
    nowMs = System.currentTimeMillis(),
    spoolDirectoryPath = spoolDirectory.absolutePath
)
```

```kotlin
// PlaybackBufferNetworkSettingsTest.kt existing NotChecked assertion
resolveDiskSpoolDiagnosticStatus(
    result = null,
    probeUiState = DiskSpoolStorageProbeUiState.NotChecked,
    nowMs = 1_776_047_818_725L,
    spoolDirectoryPath = "/cache/player_disk_spool"
)
```

- [ ] **Step 5: Thread UI state through Compose calls**

Thread the parameter through the full current Compose chain:
- `PlaybackSettingsScreen` owns the `PlaybackSettingsViewModel`.
- `PlaybackSettingsContent` collects `viewModel.diskSpoolStorageProbeUiState`.
- `PlaybackSettingsContent` passes `diskSpoolStorageProbeUiState` into `PlaybackSettingsSections`.
- `PlaybackSettingsSections` passes `diskSpoolStorageProbeUiState` into `bufferAndNetworkSettingsItems`.
- `bufferAndNetworkSettingsItems` passes `diskSpoolStorageProbeUiState` into `resolveDiskSpoolDiagnosticStatus`.

In `PlaybackBufferNetworkSettings`, add a parameter:

```kotlin
diskSpoolStorageProbeUiState: DiskSpoolStorageProbeUiState,
```

Pass it into `resolveDiskSpoolDiagnosticStatus`:

```kotlin
val status = resolveDiskSpoolDiagnosticStatus(
    result = SpoolStorageProbeResult.fromJsonOrNull(playerSettings.spoolStorageProbeResultJson),
    probeUiState = diskSpoolStorageProbeUiState,
    nowMs = System.currentTimeMillis(),
    spoolDirectoryPath = spoolDirectory.absolutePath
)
```

Handle the new statuses:

```kotlin
val statusText = when (status) {
    DiskSpoolDiagnosticStatus.NotChecked ->
        stringResource(R.string.playback_buffer_disk_spool_diagnostic_not_checked)
    DiskSpoolDiagnosticStatus.Running ->
        stringResource(R.string.playback_buffer_disk_spool_diagnostic_running)
    is DiskSpoolDiagnosticStatus.Failed ->
        stringResource(R.string.playback_buffer_disk_spool_diagnostic_failed, status.message)
    is DiskSpoolDiagnosticStatus.Measured -> {
        val randomWriteMbps = status.randomWriteMbps
        if (randomWriteMbps != null) {
            stringResource(
                R.string.playback_buffer_disk_spool_diagnostic_measured_random,
                status.sequentialWriteMbps,
                status.sequentialReadMbps,
                randomWriteMbps
            )
        } else {
            stringResource(
                R.string.playback_buffer_disk_spool_diagnostic_measured,
                status.sequentialWriteMbps,
                status.sequentialReadMbps
            )
        }
    }
    DiskSpoolDiagnosticStatus.Stale ->
        stringResource(R.string.playback_buffer_disk_spool_diagnostic_stale)
}
```

In `PlaybackSettingsScreen`, collect:

```kotlin
val diskSpoolStorageProbeUiState by viewModel.diskSpoolStorageProbeUiState.collectAsState()
```

In `PlaybackSettingsContent`, add this named argument to the existing `PlaybackSettingsSections(...)` call:

```kotlin
diskSpoolStorageProbeUiState = diskSpoolStorageProbeUiState,
```

In `PlaybackSettingsSections`, add the parameter:

```kotlin
diskSpoolStorageProbeUiState: DiskSpoolStorageProbeUiState,
```

Then pass it into the buffer/network section:

```kotlin
bufferAndNetworkSettingsItems(
    playerSettings = playerSettings,
    diskSpoolStorageProbeUiState = diskSpoolStorageProbeUiState,
    onSetVodCacheSizeMode = onSetVodCacheSizeMode,
    onSetVodCacheSizeMb = onSetVodCacheSizeMb,
    onSetVodCacheWarmAheadEnabled = onSetVodCacheWarmAheadEnabled,
    onSetUseParallelConnections = onSetUseParallelConnections,
    onSetProgressivePlaybackDiskMode = onSetProgressivePlaybackDiskMode,
    onSetDiskSpoolStorageLocation = onSetDiskSpoolStorageLocation,
    onRunDiskSpoolStorageProbe = onRunDiskSpoolStorageProbe,
    onItemFocused = { focusedSection = PlaybackSection.BUFFER_NETWORK }
)
```

In `bufferAndNetworkSettingsItems`, add the parameter:

```kotlin
diskSpoolStorageProbeUiState: DiskSpoolStorageProbeUiState,
```

- [ ] **Step 6: Run settings tests**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.settings.PlaybackBufferNetworkSettingsTest --tests com.nexio.tv.ui.screens.settings.PlaybackSettingsViewModelSpoolModeTest
```

Expected: PASS.

- [ ] **Step 7: Commit UI state rendering**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt app/src/main/res/values/strings.xml app/src/test/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettingsTest.kt
git commit -m "fix: show disk spool diagnostic progress and failures"
```

---

### Task 5: Final Verification

**Files:**
- No additional source files expected.

- [ ] **Step 1: Run focused diagnostic tests**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageDiagnosticTest --tests com.nexio.tv.ui.screens.settings.PlaybackSettingsViewModelSpoolModeTest --tests com.nexio.tv.ui.screens.settings.PlaybackBufferNetworkSettingsTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: PASS.

- [ ] **Step 2: Run Kotlin compile**

Run:

```bash
./gradlew --no-daemon :app:compileUniversalDebugKotlin
```

Expected: PASS.

- [ ] **Step 3: Inspect working tree**

Run:

```bash
git status --short
```

Expected: only the unrelated pre-existing files remain dirty, or a clean tree if they were handled in a separate workflow. Do not add `streamselection.png`.

- [ ] **Step 4: Commit final fixes if any files remain unstaged from this plan**

If any plan-owned files are still modified:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageDiagnostic.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt app/src/main/res/values/strings.xml app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageDiagnosticTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt app/src/test/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModelSpoolModeTest.kt app/src/test/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettingsTest.kt
git commit -m "fix: stabilize disk spool diagnostics"
```

---

## Self-Review

**Spec coverage:**
- Running state: Task 3 and Task 4.
- Failure state: Task 3 and Task 4.
- Do not clear stats at start/failure: Task 3.
- Capacity failure: Task 1.
- End-of-run storage cleanup: Task 1.
- Canceled/stale diagnostic storage cleanup: Task 1 and Task 2.
- App-start stale cleanup: Task 2 through `PlayerMediaSourceFactory` initialization cleanup.
- Only successful diagnostic stats persisted: Task 3.
- Cleanup ownership clarified: Task 1 helper removes every `spool-diagnostic-*` derivative by prefix; Task 2 factory cleanup removes normal `spool-*.bin` playback files and invokes the helper for orphan diagnostic derivatives.
- Existing resolver callers/tests updated: Task 4 covers both the production call and existing test assertions with `probeUiState`.
- Compose threading enumerated: Task 4 names `PlaybackSettingsContent`, `PlaybackSettingsSections`, and `bufferAndNetworkSettingsItems`.
- Failed state recovery decision documented: Task 3 keeps failure visible until retry or ViewModel recreation.
- Stale cleanup failure isolation documented: Task 1 wraps diagnostic startup cleanup with `runCatching`.

**Placeholder scan:** No task uses forbidden placeholder language; each code-changing task includes concrete code and commands.

**Type consistency:** The plan consistently uses `DiskSpoolStorageProbeUiState`, `DiskSpoolDiagnosticStatus`, `SpoolStorageProbeResult`, and existing `setSpoolStorageProbeResult(result)` persistence.
