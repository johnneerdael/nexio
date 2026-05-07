# Disk Spool Storage And Parallel Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let disk-spool playback run immediately with built-in or removable external app-specific cache storage, keep VOD cache and disk spool mutually exclusive, support parallel disk-spool fetching, and move disk speed testing to non-gating diagnostics.

**Architecture:** Split disk spool into a playback path and a diagnostics path. The playback path chooses a spool directory, starts a writer, and exposes a `DataSource` without requiring a prior storage probe; the diagnostics path can run CPDT-inspired sequential and random write/read tests and report warnings without blocking playback. Disk spool can use provider parallel profiles, but the UI warns that parallel spool writes become random/interleaved-write heavy and need fast storage.

**Tech Stack:** Kotlin, Android app-specific storage APIs (`Context.cacheDir`, `Context.externalCacheDirs`, `Environment.getExternalStorageState`, `Environment.isExternalStorageRemovable`), Jetpack DataStore, Media3 DataSource, OkHttp range requests, `RandomAccessFile`/`FileChannel`, Robolectric unit tests. CPDT reference: `~/Scripts/crossplatformdisktest`, specifically `Saplin.CPDT.UICore/ViewModels/DriveTestViewModel.cs`, `README.md`, and the referenced `Saplin.StorageSpeedMeter` concepts.

---

## Required Behavior

- Disk-spool playback is not gated by speed-test/probe results.
- Speed test is an add-on diagnostics feature that can show readiness/warnings later.
- Builtin storage means `context.cacheDir/player_disk_spool`.
- External storage means a mounted removable app-specific external cache directory from `context.externalCacheDirs`, with no SAF folder picker and no broad storage permission.
- The UI option is simple: Builtin / External. External is only enabled when a mounted removable app-specific cache dir is detected.
- VOD cache and disk spool are mutually exclusive. Enabling one disables the other in settings, and the factory must avoid wrapping disk spool in `CacheDataSource` even if stale settings contain both.
- Disk spool supports parallel connections by using existing provider profile logic. Parallel write mode should still stream through small buffers and must not allocate whole chunks into memory.
- Parallel warning text must say that disk spool + parallel fetching can become random/interleaved-write heavy and should be used with fast storage.
- Diagnostics should measure two workload shapes:
  - Sequential write and sequential read at the same time.
  - Sequential read plus random write at the same time when parallel connections are enabled.
- Playback startup should use an explicit startup frontier target. Use 100 MiB or the content length, whichever is smaller. If the writer cannot reach 100 MiB quickly, playback should still be able to make progress after the existing read wait path; do not make startup prebuffer an indefinite hard block.

## Current Playback Start Clarification

Current `main` behavior has no 100 MiB startup threshold. `DiskSpoolDataSource.open()` returns immediately, then `read()` blocks until the requested byte position is available or the session wait timeout expires. In practice playback can start as soon as the writer has produced the first bytes Media3 requests, not after 100 MiB.

This plan changes that to an explicit soft startup target: the writer prioritizes the first 100 MiB before broad parallel-ahead scheduling, but the DataSource does not wait forever for 100 MiB. This gives the “spooled before playback” behavior without making a slow disk test or slow network block the feature entirely.

## CPDT Notes To Reuse

The CPDT checkout mostly references `Saplin.StorageSpeedMeter.dll`; the actual meter implementation is not present as source in this checkout. Still reuse the exposed design:

- `README.md` states sequential tests transfer large megabyte chunks between RAM and storage.
- `README.md` states random tests use small 4 KiB and 32 KiB chunks at random file positions.
- `README.md` notes CPDT is single-threaded and has no I/O queues or parallel execution. Our diagnostic differs because the disk-spool workload is concurrent by design.
- `DriveTestViewModel.cs` constructs a `BigTest` with write-buffering and memory-cache options and uses app-specific mobile drive folders.
- `DriveTestViewModel.cs` tracks free space with an extra 512 MiB reserve; keep that reserve pattern.

For Nexio, port the benchmark semantics, not the Xamarin/C# UI or the prebuilt DLL.

---

## File Structure

- Create `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageResolver.kt`
  - Resolve built-in/external spool directories.
- Create `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageResolverTest.kt`
  - Test directory selection.
- Modify `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
  - Add `DiskSpoolStorageLocation`.
  - Persist storage location.
  - Enforce VOD cache / disk spool mutual exclusion.
- Modify `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSpoolModeTest.kt`
  - Test persistence and mutual exclusion.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
  - Add Builtin/External row.
  - Add parallel warning.
  - Make speed test status diagnostic-only.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
  - Add storage setter.
  - Run diagnostics against selected storage.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
  - Thread callback.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
  - Wire callback.
- Modify `app/src/main/res/values/strings.xml`
  - Add copy.
- Create or replace `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageDiagnostic.kt`
  - CPDT-inspired concurrent diagnostic workloads.
- Create `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageDiagnosticTest.kt`
  - Deterministic workload tests.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/spool/SpoolStorageCapability.kt`
  - Store diagnostic-only results for sequential read/write and sequential-read-plus-random-write.
- Modify `app/src/test/java/com/nexio/tv/ui/screens/player/spool/SpoolStorageCapabilityTest.kt`
  - JSON compatibility tests.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt`
  - Add parallel range scheduling and soft startup target support.
- Modify `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt`
  - Test parallel range scheduling and startup prioritization.
- Modify `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolPipelineTest.kt`
  - Test concurrent parallel writer and reader.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - Resolve selected spool directory.
  - Remove probe/freshness/capability gating from disk spool factory path.
  - Keep speed diagnostic result only for logs/UI.
  - Pass parallel provider profiles into `DiskSpoolWriter`.
- Modify `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
  - Test non-gated spool activation, VOD cache exclusion, external directory selection, and parallel profile wiring.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - Propagate storage location into factory.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
  - Propagate runtime setting changes into factory.

---

## Task 1: Storage Location Resolver

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageResolver.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageResolverTest.kt`

- [ ] **Step 1: Write the failing resolver tests**

```kotlin
package com.nexio.tv.ui.screens.player.spool

import android.os.Environment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskSpoolStorageResolverTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `builtin directory is app cache player disk spool`() {
        val cacheDir = temp.newFolder("cache")

        assertEquals(
            File(cacheDir, "player_disk_spool"),
            DiskSpoolStorageResolver.builtinSpoolDirectory(cacheDir)
        )
    }

    @Test
    fun `external directory uses first mounted removable app cache directory`() {
        val primary = temp.newFolder("primary")
        val usb = temp.newFolder("usb")

        val result = DiskSpoolStorageResolver.externalSpoolDirectoryFromCandidates(
            externalCacheDirs = arrayOf(primary, usb),
            stateOf = { Environment.MEDIA_MOUNTED },
            removableOf = { file -> file == usb }
        )

        assertEquals(File(usb, "player_disk_spool"), result)
    }

    @Test
    fun `external directory is unavailable when no removable mounted app cache dir exists`() {
        val primary = temp.newFolder("primary")
        val usb = temp.newFolder("usb")

        val result = DiskSpoolStorageResolver.externalSpoolDirectoryFromCandidates(
            externalCacheDirs = arrayOf(primary, usb),
            stateOf = { file -> if (file == usb) Environment.MEDIA_UNMOUNTED else Environment.MEDIA_MOUNTED },
            removableOf = { true }
        )

        assertNull(result)
    }

    @Test
    fun `external request returns null when external storage is unavailable`() {
        val cacheDir = temp.newFolder("cache")

        val result = DiskSpoolStorageResolver.resolveSpoolDirectory(
            cacheDir = cacheDir,
            externalCacheDirs = emptyArray(),
            location = DiskSpoolStorageLocation.EXTERNAL,
            stateOf = { Environment.MEDIA_MOUNTED },
            removableOf = { true }
        )

        assertNull(result)
    }
}
```

- [ ] **Step 2: Run resolver tests to verify RED**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageResolverTest'
```

Expected: FAIL because `DiskSpoolStorageLocation` and `DiskSpoolStorageResolver` do not exist.

- [ ] **Step 3: Implement resolver**

```kotlin
package com.nexio.tv.ui.screens.player.spool

import android.content.Context
import android.os.Environment
import java.io.File

enum class DiskSpoolStorageLocation {
    BUILTIN,
    EXTERNAL
}

internal object DiskSpoolStorageResolver {
    private const val DISK_SPOOL_DIR = "player_disk_spool"

    fun builtinSpoolDirectory(cacheDir: File): File {
        return File(cacheDir, DISK_SPOOL_DIR)
    }

    fun builtinSpoolDirectory(context: Context): File {
        return builtinSpoolDirectory(context.cacheDir)
    }

    fun externalSpoolDirectoryOrNull(context: Context): File? {
        return externalSpoolDirectoryFromCandidates(
            externalCacheDirs = context.externalCacheDirs,
            stateOf = { file -> Environment.getExternalStorageState(file) },
            removableOf = { file -> Environment.isExternalStorageRemovable(file) }
        )
    }

    fun resolveSpoolDirectory(context: Context, location: DiskSpoolStorageLocation): File? {
        return when (location) {
            DiskSpoolStorageLocation.BUILTIN -> builtinSpoolDirectory(context)
            DiskSpoolStorageLocation.EXTERNAL -> externalSpoolDirectoryOrNull(context)
        }
    }

    internal fun resolveSpoolDirectory(
        cacheDir: File,
        externalCacheDirs: Array<File>,
        location: DiskSpoolStorageLocation,
        stateOf: (File) -> String,
        removableOf: (File) -> Boolean
    ): File? {
        return when (location) {
            DiskSpoolStorageLocation.BUILTIN -> builtinSpoolDirectory(cacheDir)
            DiskSpoolStorageLocation.EXTERNAL -> externalSpoolDirectoryFromCandidates(
                externalCacheDirs = externalCacheDirs,
                stateOf = stateOf,
                removableOf = removableOf
            )
        }
    }

    internal fun externalSpoolDirectoryFromCandidates(
        externalCacheDirs: Array<File>,
        stateOf: (File) -> String,
        removableOf: (File) -> Boolean
    ): File? {
        return externalCacheDirs
            .filter { file -> stateOf(file) == Environment.MEDIA_MOUNTED }
            .firstOrNull { file -> removableOf(file) }
            ?.resolve(DISK_SPOOL_DIR)
    }
}
```

- [ ] **Step 4: Run resolver tests to verify GREEN**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageResolverTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageResolver.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageResolverTest.kt
git commit -m "feat: add disk spool storage resolver"
```

---

## Task 2: DataStore Settings And Mutual Exclusion

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSpoolModeTest.kt`

- [ ] **Step 1: Write failing DataStore tests**

Append to `PlayerSettingsDataStoreSpoolModeTest`:

```kotlin
@Test
fun `default disk spool storage location is builtin`() {
    assertEquals(DiskSpoolStorageLocation.BUILTIN, PlayerSettings().diskSpoolStorageLocation)
}

@Test
fun `setting disk spool storage location persists`() = runTest {
    val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

    dataStore.setDiskSpoolStorageLocation(DiskSpoolStorageLocation.EXTERNAL)

    assertEquals(
        DiskSpoolStorageLocation.EXTERNAL,
        dataStore.playerSettings.first().diskSpoolStorageLocation
    )
}

@Test
fun `changing disk spool storage location clears diagnostic result`() = runTest {
    val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

    dataStore.setSpoolStorageProbeResult(probeResult())
    dataStore.setDiskSpoolStorageLocation(DiskSpoolStorageLocation.EXTERNAL)

    assertNull(dataStore.playerSettings.first().spoolStorageProbeResultJson)
}

@Test
fun `enabling disk spool turns vod cache off`() = runTest {
    val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

    dataStore.setVodCacheSizeMode(VodCacheSizeMode.ON)
    dataStore.setProgressivePlaybackDiskMode(ProgressivePlaybackDiskMode.SPOOL)

    val settings = dataStore.playerSettings.first()
    assertEquals(ProgressivePlaybackDiskMode.SPOOL, settings.progressivePlaybackDiskMode)
    assertEquals(VodCacheSizeMode.OFF, settings.vodCacheSizeMode)
}

@Test
fun `enabling vod cache turns disk spool off`() = runTest {
    val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

    dataStore.setProgressivePlaybackDiskMode(ProgressivePlaybackDiskMode.SPOOL)
    dataStore.setVodCacheSizeMode(VodCacheSizeMode.ON)

    val settings = dataStore.playerSettings.first()
    assertEquals(VodCacheSizeMode.ON, settings.vodCacheSizeMode)
    assertEquals(ProgressivePlaybackDiskMode.OFF, settings.progressivePlaybackDiskMode)
}
```

Add imports:

```kotlin
import com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageLocation
```

- [ ] **Step 2: Run DataStore tests to verify RED**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.data.local.PlayerSettingsDataStoreSpoolModeTest'
```

Expected: FAIL because location persistence and mutual exclusion are not implemented.

- [ ] **Step 3: Implement DataStore settings**

In `PlayerSettingsDataStore.kt`, import:

```kotlin
import com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageLocation
```

Add to `PlayerSettings` next to `progressivePlaybackDiskMode`:

```kotlin
val diskSpoolStorageLocation: DiskSpoolStorageLocation = DiskSpoolStorageLocation.BUILTIN,
```

Add key:

```kotlin
private val diskSpoolStorageLocationKey = stringPreferencesKey("disk_spool_storage_location")
```

Add parse helper:

```kotlin
private fun parseDiskSpoolStorageLocation(value: String?): DiskSpoolStorageLocation {
    return when (value) {
        "EXTERNAL" -> DiskSpoolStorageLocation.EXTERNAL
        "BUILTIN" -> DiskSpoolStorageLocation.BUILTIN
        else -> DiskSpoolStorageLocation.BUILTIN
    }
}
```

Add to `PlayerSettings(...)` construction:

```kotlin
diskSpoolStorageLocation = parseDiskSpoolStorageLocation(prefs[diskSpoolStorageLocationKey]),
```

Replace `setVodCacheSizeMode` with:

```kotlin
suspend fun setVodCacheSizeMode(mode: VodCacheSizeMode) {
    store().edit { prefs ->
        val current = prefs[vodCacheSizeModeKey]
            ?.let { runCatching { VodCacheSizeMode.valueOf(it) }.getOrNull() }
            ?: PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE
        if (current != mode) {
            prefs.remove(autoplayMaxBitrateMbpsKey)
        }
        prefs[vodCacheSizeModeKey] = mode.name
        if (mode == VodCacheSizeMode.ON) {
            prefs[progressivePlaybackDiskModeKey] = ProgressivePlaybackDiskMode.OFF.name
        }
    }
}
```

Replace `setProgressivePlaybackDiskMode` with:

```kotlin
suspend fun setProgressivePlaybackDiskMode(mode: ProgressivePlaybackDiskMode) {
    store().edit { prefs ->
        prefs[progressivePlaybackDiskModeKey] = mode.name
        if (mode == ProgressivePlaybackDiskMode.SPOOL) {
            prefs[vodCacheSizeModeKey] = VodCacheSizeMode.OFF.name
        }
    }
}
```

Add:

```kotlin
suspend fun setDiskSpoolStorageLocation(location: DiskSpoolStorageLocation) {
    store().edit { prefs ->
        val current = parseDiskSpoolStorageLocation(prefs[diskSpoolStorageLocationKey])
        if (current != location) {
            prefs.remove(spoolStorageProbeResultJsonKey)
        }
        prefs[diskSpoolStorageLocationKey] = location.name
    }
}
```

- [ ] **Step 4: Run DataStore tests to verify GREEN**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.data.local.PlayerSettingsDataStoreSpoolModeTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSpoolModeTest.kt
git commit -m "feat: configure disk spool cache settings"
```

---

## Task 3: Non-Gating CPDT-Style Storage Diagnostics

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageDiagnostic.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageDiagnosticTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/SpoolStorageCapability.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/SpoolStorageCapabilityTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModelSpoolModeTest.kt`

- [ ] **Step 1: Write failing diagnostic tests**

Create `DiskSpoolStorageDiagnosticTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.spool

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskSpoolStorageDiagnosticTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `diagnostic returns sequential read write and random write results`() {
        val result = DiskSpoolStorageDiagnostic(
            directory = temp.newFolder("spool-diagnostic"),
            totalBytes = 8L * 1024L * 1024L,
            sequentialBlockBytes = 1024 * 1024,
            randomBlockBytes = 4 * 1024,
            randomWriteEnabled = true,
            randomSeed = 7L
        ).run()

        assertTrue(result.sequentialWriteMbps > 0.0)
        assertTrue(result.sequentialReadMbps > 0.0)
        assertTrue(result.concurrentSequentialWriteMbps > 0.0)
        assertTrue(result.concurrentSequentialReadMbps > 0.0)
        assertTrue(result.concurrentRandomWriteMbps != null)
        assertEquals(File(temp.root, "spool-diagnostic").absolutePath, result.spoolDirectoryPath)
    }

    @Test
    fun `diagnostic can skip random write workload when parallel is disabled`() {
        val result = DiskSpoolStorageDiagnostic(
            directory = temp.newFolder("spool-diagnostic-no-random"),
            totalBytes = 8L * 1024L * 1024L,
            sequentialBlockBytes = 1024 * 1024,
            randomBlockBytes = 4 * 1024,
            randomWriteEnabled = false,
            randomSeed = 7L
        ).run()

        assertEquals(null, result.concurrentRandomWriteMbps)
    }
}
```

- [ ] **Step 2: Run diagnostic tests to verify RED**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageDiagnosticTest'
```

Expected: FAIL because `DiskSpoolStorageDiagnostic` does not exist.

- [ ] **Step 3: Implement diagnostic result model**

Add to `SpoolStorageCapability.kt` or replace the existing probe result fields with backward-compatible optional fields:

```kotlin
data class SpoolStorageProbeResult(
    val writeMbps: Double,
    val readMbps: Double,
    val combinedMbps: Double,
    val p99ReadLatencyMs: Long,
    val maxReadStallMs: Long,
    val measuredAtMs: Long,
    val durationMs: Long,
    val bytesWritten: Long,
    val bytesRead: Long,
    val spoolDirectoryPath: String,
    val concurrentSequentialWriteMbps: Double? = null,
    val concurrentSequentialReadMbps: Double? = null,
    val concurrentRandomWriteMbps: Double? = null
)
```

Keep existing JSON fields as required so older tests and persisted values still parse. Add optional JSON writes:

```kotlin
concurrentSequentialWriteMbps?.let { put("concurrentSequentialWriteMbps", it) }
concurrentSequentialReadMbps?.let { put("concurrentSequentialReadMbps", it) }
concurrentRandomWriteMbps?.let { put("concurrentRandomWriteMbps", it) }
```

Parse those fields with existing strict optional double helpers. Reject non-finite values when present.

- [ ] **Step 4: Implement diagnostic runner**

Create `DiskSpoolStorageDiagnostic.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.spool

import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

internal class DiskSpoolStorageDiagnostic(
    private val directory: File,
    private val totalBytes: Long = 512L * 1024L * 1024L,
    private val sequentialBlockBytes: Int = 1024 * 1024,
    private val randomBlockBytes: Int = 4 * 1024,
    private val randomWriteEnabled: Boolean,
    private val randomSeed: Long = System.nanoTime(),
    private val shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted }
) {
    fun run(): SpoolStorageProbeResult {
        directory.mkdirs()
        val file = File(directory, "spool-diagnostic-${SystemClock.elapsedRealtimeNanos()}.bin")
        val startedAtMs = System.currentTimeMillis()
        return try {
            val sequentialWriteMbps = sequentialWrite(file)
            val sequentialReadMbps = sequentialRead(file)
            val concurrentSequential = concurrentSequentialReadWrite(file)
            val concurrentRandomWriteMbps = if (randomWriteEnabled) {
                concurrentSequentialReadRandomWrite(file).second
            } else {
                null
            }
            val durationMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)

            SpoolStorageProbeResult(
                writeMbps = sequentialWriteMbps,
                readMbps = sequentialReadMbps,
                combinedMbps = concurrentSequential.first + concurrentSequential.second,
                p99ReadLatencyMs = 0L,
                maxReadStallMs = 0L,
                measuredAtMs = startedAtMs,
                durationMs = durationMs,
                bytesWritten = totalBytes,
                bytesRead = totalBytes,
                spoolDirectoryPath = directory.absolutePath,
                concurrentSequentialWriteMbps = concurrentSequential.first,
                concurrentSequentialReadMbps = concurrentSequential.second,
                concurrentRandomWriteMbps = concurrentRandomWriteMbps
            )
        } finally {
            file.delete()
        }
    }

    private fun sequentialWrite(file: File): Double {
        val buffer = ByteArray(sequentialBlockBytes) { 0x5A.toByte() }
        val elapsedNs = timedNs {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(totalBytes)
                var position = 0L
                while (position < totalBytes && shouldContinue()) {
                    val length = minOf(buffer.size.toLong(), totalBytes - position).toInt()
                    raf.seek(position)
                    raf.write(buffer, 0, length)
                    position += length
                }
                raf.fd.sync()
            }
        }
        return mbps(totalBytes, elapsedNs)
    }

    private fun sequentialRead(file: File): Double {
        val buffer = ByteArray(sequentialBlockBytes)
        val elapsedNs = timedNs {
            RandomAccessFile(file, "r").use { raf ->
                var position = 0L
                while (position < totalBytes && shouldContinue()) {
                    val length = minOf(buffer.size.toLong(), totalBytes - position).toInt()
                    raf.seek(position)
                    raf.readFully(buffer, 0, length)
                    position += length
                }
            }
        }
        return mbps(totalBytes, elapsedNs)
    }

    private fun concurrentSequentialReadWrite(file: File): Pair<Double, Double> {
        val readFile = file
        val writeFile = File(directory, "${file.name}.seq-write")
        sequentialWrite(readFile)
        return runConcurrent(
            readBytes = totalBytes,
            writeBytes = totalBytes,
            readBlock = { buffer, position, length ->
                RandomAccessFile(readFile, "r").use { raf ->
                    raf.seek(position)
                    raf.readFully(buffer, 0, length)
                }
            },
            writeBlock = { buffer, position, length ->
                RandomAccessFile(writeFile, "rw").use { raf ->
                    raf.seek(position)
                    raf.write(buffer, 0, length)
                }
            }
        ).also {
            writeFile.delete()
        }
    }

    private fun concurrentSequentialReadRandomWrite(file: File): Pair<Double, Double> {
        val randomFile = File(directory, "${file.name}.random-write")
        sequentialWrite(randomFile)
        val random = Random(randomSeed)
        val maxBlock = max(1L, totalBytes / randomBlockBytes)
        return runConcurrent(
            readBytes = totalBytes,
            writeBytes = totalBytes,
            readBlock = { buffer, position, length ->
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(position)
                    raf.readFully(buffer, 0, length)
                }
            },
            writeBlock = { buffer, _, length ->
                val block = random.nextLong().floorMod(maxBlock)
                RandomAccessFile(randomFile, "rw").use { raf ->
                    raf.seek(block * randomBlockBytes.toLong())
                    raf.write(buffer, 0, length)
                }
            },
            writeBlockBytes = randomBlockBytes
        ).also {
            randomFile.delete()
        }
    }

    private fun runConcurrent(
        readBytes: Long,
        writeBytes: Long,
        readBlock: (ByteArray, Long, Int) -> Unit,
        writeBlock: (ByteArray, Long, Int) -> Unit,
        writeBlockBytes: Int = sequentialBlockBytes
    ): Pair<Double, Double> {
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        var readElapsedNs = 1L
        var writeElapsedNs = 1L
        val reader = Thread {
            val buffer = ByteArray(sequentialBlockBytes)
            try {
                start.await()
                readElapsedNs = timedNs {
                    var position = 0L
                    while (position < readBytes && shouldContinue()) {
                        val length = minOf(buffer.size.toLong(), readBytes - position).toInt()
                        readBlock(buffer, position, length)
                        position += length
                    }
                }
            } catch (throwable: Throwable) {
                failure.compareAndSet(null, throwable)
            }
        }
        val writer = Thread {
            val buffer = ByteArray(writeBlockBytes) { 0x33.toByte() }
            try {
                start.await()
                writeElapsedNs = timedNs {
                    var position = 0L
                    while (position < writeBytes && shouldContinue()) {
                        val length = minOf(buffer.size.toLong(), writeBytes - position).toInt()
                        writeBlock(buffer, position, length)
                        position += length
                    }
                }
            } catch (throwable: Throwable) {
                failure.compareAndSet(null, throwable)
            }
        }
        reader.start()
        writer.start()
        start.countDown()
        reader.join()
        writer.join()
        failure.get()?.let { throw it }
        return mbps(writeBytes, writeElapsedNs) to mbps(readBytes, readElapsedNs)
    }

    private fun timedNs(block: () -> Unit): Long {
        val startedAt = System.nanoTime()
        block()
        return (System.nanoTime() - startedAt).coerceAtLeast(1L)
    }

    private fun mbps(bytes: Long, elapsedNs: Long): Double {
        return (bytes.toDouble() * 8.0) / elapsedNs.toDouble() * 1000.0
    }

    private fun Long.floorMod(divisor: Long): Long {
        val value = this % divisor
        return if (value >= 0L) value else value + divisor
    }
}
```

- [ ] **Step 5: Fix handle churn in diagnostic implementation**

Refactor the `runConcurrent` implementation to keep one `RandomAccessFile` per worker open instead of opening per block. Replace call sites with worker-local functions:

```kotlin
RandomAccessFile(readFile, "r").use { reader ->
    RandomAccessFile(writeFile, "rw").use { writer ->
        // use reader.seek/readFully and writer.seek/write inside the loops
    }
}
```

Expected: this keeps the CPDT-style file API behavior while avoiding per-block open/close overhead.

- [ ] **Step 6: Run diagnostic tests to verify GREEN**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageDiagnosticTest' --tests 'com.nexio.tv.ui.screens.player.spool.SpoolStorageCapabilityTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageDiagnostic.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolStorageDiagnosticTest.kt app/src/main/java/com/nexio/tv/ui/screens/player/spool/SpoolStorageCapability.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/SpoolStorageCapabilityTest.kt
git commit -m "feat: add non-gating disk spool diagnostics"
```

---

## Task 4: Settings UI For Storage And Diagnostics

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettingsTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModelSpoolModeTest.kt`

- [ ] **Step 1: Write failing UI helper tests**

Append to `PlaybackBufferNetworkSettingsTest`:

```kotlin
@Test
fun parallelConnectionsSubtitle_warnsWhenDiskSpoolAndParallelAreEnabled() {
    assertEquals(
        ParallelConnectionsSubtitle.WarningForDiskSpool,
        resolveParallelConnectionsSubtitle(
            useParallelConnections = true,
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
        )
    )
}

@Test
fun nextDiskSpoolStorageLocation_togglesOnlyWhenExternalIsAvailable() {
    assertEquals(
        DiskSpoolStorageLocation.EXTERNAL,
        nextDiskSpoolStorageLocation(DiskSpoolStorageLocation.BUILTIN, externalAvailable = true)
    )
    assertEquals(
        DiskSpoolStorageLocation.BUILTIN,
        nextDiskSpoolStorageLocation(DiskSpoolStorageLocation.EXTERNAL, externalAvailable = true)
    )
    assertEquals(
        DiskSpoolStorageLocation.BUILTIN,
        nextDiskSpoolStorageLocation(DiskSpoolStorageLocation.EXTERNAL, externalAvailable = false)
    )
}

@Test
fun diskSpoolDiagnosticStatus_doesNotDisableSpoolWhenResultIsMissingOrFailed() {
    assertEquals(
        DiskSpoolDiagnosticStatus.NotChecked,
        resolveDiskSpoolDiagnosticStatus(
            result = null,
            nowMs = 1_776_047_818_725L,
            spoolDirectoryPath = "/cache/player_disk_spool"
        )
    )
}
```

Add import:

```kotlin
import com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageLocation
```

- [ ] **Step 2: Run UI tests to verify RED**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.settings.PlaybackBufferNetworkSettingsTest'
```

Expected: FAIL because helpers do not exist or still model probe status as a gate.

- [ ] **Step 3: Implement UI helpers and copy**

In `PlaybackBufferNetworkSettings.kt`, add:

```kotlin
internal enum class ParallelConnectionsSubtitle {
    Default,
    WarningForDiskSpool
}

internal fun resolveParallelConnectionsSubtitle(
    useParallelConnections: Boolean,
    progressivePlaybackDiskMode: ProgressivePlaybackDiskMode
): ParallelConnectionsSubtitle {
    return if (useParallelConnections && progressivePlaybackDiskMode == ProgressivePlaybackDiskMode.SPOOL) {
        ParallelConnectionsSubtitle.WarningForDiskSpool
    } else {
        ParallelConnectionsSubtitle.Default
    }
}

internal fun nextDiskSpoolStorageLocation(
    current: DiskSpoolStorageLocation,
    externalAvailable: Boolean
): DiskSpoolStorageLocation {
    if (!externalAvailable) return DiskSpoolStorageLocation.BUILTIN
    return when (current) {
        DiskSpoolStorageLocation.BUILTIN -> DiskSpoolStorageLocation.EXTERNAL
        DiskSpoolStorageLocation.EXTERNAL -> DiskSpoolStorageLocation.BUILTIN
    }
}

internal sealed class DiskSpoolDiagnosticStatus {
    object NotChecked : DiskSpoolDiagnosticStatus()
    object Stale : DiskSpoolDiagnosticStatus()
    data class Measured(
        val sequentialWriteMbps: Int,
        val sequentialReadMbps: Int,
        val randomWriteMbps: Int?
    ) : DiskSpoolDiagnosticStatus()
}

internal fun resolveDiskSpoolDiagnosticStatus(
    result: SpoolStorageProbeResult?,
    nowMs: Long,
    spoolDirectoryPath: String
): DiskSpoolDiagnosticStatus {
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

Use this warning subtitle in the parallel row:

```kotlin
val parallelSubtitle = when (
    resolveParallelConnectionsSubtitle(
        useParallelConnections = playerSettings.useParallelConnections,
        progressivePlaybackDiskMode = playerSettings.progressivePlaybackDiskMode
    )
) {
    ParallelConnectionsSubtitle.Default -> stringResource(R.string.playback_buffer_parallel_connections_sub)
    ParallelConnectionsSubtitle.WarningForDiskSpool ->
        stringResource(R.string.playback_buffer_parallel_connections_disk_spool_warning)
}
```

Add a storage row:

```kotlin
val context = LocalContext.current
val externalAvailable = DiskSpoolStorageResolver.externalSpoolDirectoryOrNull(context) != null
val effectiveLocation = if (externalAvailable) playerSettings.diskSpoolStorageLocation else DiskSpoolStorageLocation.BUILTIN
SettingsActionRow(
    title = stringResource(R.string.playback_buffer_disk_spool_storage_location),
    subtitle = if (externalAvailable) {
        stringResource(R.string.playback_buffer_disk_spool_storage_location_sub)
    } else {
        stringResource(R.string.playback_buffer_disk_spool_storage_location_no_external)
    },
    value = when (effectiveLocation) {
        DiskSpoolStorageLocation.BUILTIN -> stringResource(R.string.playback_buffer_disk_spool_storage_builtin)
        DiskSpoolStorageLocation.EXTERNAL -> stringResource(R.string.playback_buffer_disk_spool_storage_external)
    },
    enabled = externalAvailable,
    onClick = {
        onSetDiskSpoolStorageLocation(nextDiskSpoolStorageLocation(effectiveLocation, externalAvailable))
    },
    onFocused = onItemFocused
)
```

Rename user-facing “probe” copy to “storage diagnostic” copy. Do not mention that disk spool is disabled when the diagnostic fails.

- [ ] **Step 4: Wire ViewModel and callbacks**

In `PlaybackSettingsViewModel.kt`, add:

```kotlin
suspend fun setDiskSpoolStorageLocation(location: DiskSpoolStorageLocation) {
    playerSettingsDataStore.setDiskSpoolStorageLocation(location)
}
```

Update `runDiskSpoolStorageProbe` to resolve selected storage:

```kotlin
val settings = playerSettingsDataStore.playerSettings.first()
val spoolDirectory = DiskSpoolStorageResolver.resolveSpoolDirectory(
    applicationContext,
    settings.diskSpoolStorageLocation
)
if (spoolDirectory == null) {
    commitProbeResultIfCurrent(null)
    return@launch
}
val result = diskSpoolStorageProbeRunnerForTesting?.invoke(spoolDirectory, shouldContinue)
    ?: DiskSpoolStorageDiagnostic(
        directory = spoolDirectory,
        randomWriteEnabled = settings.useParallelConnections,
        shouldContinue = shouldContinue
    ).run()
```

Keep the method name for internal churn if preferred, but update strings and UI to call it a diagnostic.

Thread `onSetDiskSpoolStorageLocation` through `PlaybackSettingsSections.kt` and `PlaybackSettingsScreen.kt`.

- [ ] **Step 5: Add strings**

Add to `strings.xml`:

```xml
<string name="playback_buffer_parallel_connections_disk_spool_warning">Disk spool can use parallel fetching, but this may become random write heavy. Use fast storage for best results.</string>
<string name="playback_buffer_disk_spool_storage_location">Disk spool storage</string>
<string name="playback_buffer_disk_spool_storage_location_sub">Choose where disk spool writes its temporary playback file.</string>
<string name="playback_buffer_disk_spool_storage_location_no_external">No mounted external app storage detected.</string>
<string name="playback_buffer_disk_spool_storage_builtin">Builtin</string>
<string name="playback_buffer_disk_spool_storage_external">External</string>
<string name="playback_buffer_disk_spool_diagnostic">Run storage diagnostic</string>
<string name="playback_buffer_disk_spool_diagnostic_not_checked">Storage diagnostic has not been run. Disk spool can still be tested.</string>
<string name="playback_buffer_disk_spool_diagnostic_stale">Storage diagnostic is stale for the selected storage.</string>
<string name="playback_buffer_disk_spool_diagnostic_measured">Storage diagnostic: sequential write %1$d Mbps, sequential read %2$d Mbps.</string>
<string name="playback_buffer_disk_spool_diagnostic_measured_random">Storage diagnostic: sequential write %1$d Mbps, sequential read %2$d Mbps, random write %3$d Mbps.</string>
```

- [ ] **Step 6: Run settings tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.settings.PlaybackBufferNetworkSettingsTest' --tests 'com.nexio.tv.ui.screens.settings.PlaybackSettingsViewModelSpoolModeTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt app/src/main/res/values/strings.xml app/src/test/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettingsTest.kt app/src/test/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModelSpoolModeTest.kt
git commit -m "feat: expose disk spool diagnostics settings"
```

---

## Task 5: Parallel Disk-Spool Writer With Startup Priority

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolPipelineTest.kt`

- [ ] **Step 1: Write failing writer tests**

Append to `DiskSpoolWriterTest`:

```kotlin
@Test
fun `parallel writer schedules multiple adjacent range requests into one session`() {
    val content = ByteArray(96 * 1024) { (it % 251).toByte() }
    val requestedRanges = java.util.concurrent.CopyOnWriteArrayList<String>()
    val server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Range")
            if (range != null) requestedRanges += range
            return when (range) {
                "bytes=0-0" -> MockResponse()
                    .setResponseCode(206)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes 0-0/${content.size}")
                    .setHeader("Content-Length", 1)
                    .setBody(Buffer().writeByte(0x2A))
                else -> rangedResponse(content, range ?: error("Missing range"))
            }
        }
    }
    server.start()
    val session = DiskSpoolSession(File(temp.root, "parallel.spool"), capacityBytes = 128 * 1024L)

    try {
        DiskSpoolWriter(
            okHttpClient = OkHttpClient(),
            chunkBytes = 32 * 1024,
            ioBufferBytes = 4 * 1024,
            parallelConnections = 3,
            startupPriorityBytes = 64 * 1024L
        ).downloadUntil(server.url("/movie.bin").toString(), session, content.size.toLong())

        val buffer = ByteArray(content.size)
        assertEquals(content.size, session.read(0L, buffer, 0, buffer.size))
        assertArrayEquals(content, buffer)
        assertTrue(requestedRanges.contains("bytes=0-32767"))
        assertTrue(requestedRanges.contains("bytes=32768-65535"))
        assertTrue(requestedRanges.contains("bytes=65536-98303"))
    } finally {
        session.close()
        server.shutdown()
    }
}
```

- [ ] **Step 2: Run writer tests to verify RED**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest.parallel writer schedules multiple adjacent range requests into one session'
```

Expected: FAIL because `parallelConnections` and `startupPriorityBytes` are not implemented.

- [ ] **Step 3: Implement constructor parameters**

In `DiskSpoolWriter`, add:

```kotlin
private val parallelConnections: Int = 1,
private val startupPriorityBytes: Long = 100L * 1024L * 1024L
```

Add:

```kotlin
private val normalizedParallelConnections = parallelConnections.coerceAtLeast(1)
```

- [ ] **Step 4: Implement startup-priority then parallel scheduling**

In `downloadUntil`, after metadata is set:

```kotlin
val bridge = SessionAdapter(session)
val startupTarget = minOf(startupPriorityBytes.coerceAtLeast(0L), targetFrontierBytes, metadata.contentLength)
if (startupTarget > bridge.contiguousFrontierBytes()) {
    downloadSequentially(url, bridge, startupTarget, metadata.contentLength)
}
if (normalizedParallelConnections <= 1) {
    downloadSequentially(url, bridge, targetFrontierBytes, metadata.contentLength)
} else {
    downloadParallel(url, bridge, targetFrontierBytes, metadata.contentLength)
}
```

Move the existing loop into `downloadSequentially`.

Implement `downloadParallel` with a fixed thread pool, shared `AtomicLong nextStart`, `AtomicReference<Throwable?> failure`, and existing `downloadRangeIntoSession(...)`. Each worker claims adjacent `chunkBytes` ranges. Each worker still checks `consumePriorityPosition()` and calls `rebaseTo()` so seeks can interrupt the queue.

Important: keep `downloadRangeIntoSession(source, ...)` streaming through `ioBufferBytes`; do not replace it with `response.body.bytes()`.

- [ ] **Step 5: Run writer tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolWriterTest'
```

Expected: PASS.

- [ ] **Step 6: Add parallel pipeline test**

Append to `DiskSpoolPipelineTest`:

```kotlin
@Test
fun `parallel writer feeds datasource while playback reads from spool`() {
    val content = ByteArray(192 * 1024) { (it % 251).toByte() }
    val server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Range")
            return when (range) {
                "bytes=0-0" -> MockResponse()
                    .setResponseCode(206)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes 0-0/${content.size}")
                    .setHeader("Content-Length", 1)
                    .setBody(Buffer().writeByte(0x2A))
                else -> rangedResponse(content, range ?: error("Missing range"))
            }
        }
    }
    server.start()
    val session = DiskSpoolSession(File(temp.root, "parallel-pipeline.spool"), capacityBytes = 256 * 1024L)
    val uri = android.net.Uri.parse(server.url("/movie.bin").toString())
    val writerThread = Thread {
        DiskSpoolWriter(
            okHttpClient = OkHttpClient(),
            chunkBytes = 64 * 1024,
            ioBufferBytes = 8 * 1024,
            parallelConnections = 2,
            startupPriorityBytes = 64 * 1024L
        ).downloadUntil(uri.toString(), session, content.size.toLong())
    }
    val dataSource = DiskSpoolDataSource(session, uri)

    try {
        writerThread.start()
        dataSource.open(DataSpec(uri))
        val actual = ByteArray(content.size)
        var offset = 0
        while (offset < actual.size) {
            val read = dataSource.read(actual, offset, actual.size - offset)
            if (read == C.RESULT_END_OF_INPUT) break
            offset += read
        }
        assertEquals(content.size, offset)
        assertArrayEquals(content, actual)
    } finally {
        dataSource.close()
        session.close()
        writerThread.join(5_000L)
        server.shutdown()
    }
}
```

- [ ] **Step 7: Run pipeline tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.spool.DiskSpoolPipelineTest'
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriterTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolPipelineTest.kt
git commit -m "feat: support parallel disk spool downloads"
```

---

## Task 6: Factory Integration Without Diagnostic Gate

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Write failing factory tests**

Append to `PlayerMediaSourceFactoryTest`:

```kotlin
@Test
fun progressivePlayback_usesDiskSpoolWithoutDiagnosticResult() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val factory = PlayerMediaSourceFactory(
        context = context,
        playbackOkHttpClient = noNetworkOkHttpClient()
    ).apply {
        progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
        spoolStorageProbeResult = null
        diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
        diskSpoolWriterExecutorForTesting = Executor { }
    }

    val dataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
        url = "https://example.com/video.mkv",
        headers = emptyMap()
    )

    assertTrue(dataSourceFactory is DiskSpoolDataSource.Factory)
    factory.shutdown()
}

@Test
fun createMediaSource_doesNotWrapDiskSpoolWithVodCacheWhenBothSettingsAreOn() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val factory = PlayerMediaSourceFactory(
        context = context,
        playbackOkHttpClient = noNetworkOkHttpClient()
    ).apply {
        vodCacheSizeMode = VodCacheSizeMode.ON
        progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
        diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
        diskSpoolWriterExecutorForTesting = Executor { }
    }

    factory.createMediaSource("https://example.com/video.mkv", emptyMap())

    assertFalse(factory.getVodCacheLogState("https://example.com/video.mkv").contains("active"))
    factory.shutdown()
}

@Test
fun progressivePlayback_passesParallelProfileIntoDiskSpoolWriterWhenEnabled() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val capturedProfiles = mutableListOf<Pair<Int, Int>>()
    val factory = PlayerMediaSourceFactory(
        context = context,
        playbackOkHttpClient = noNetworkOkHttpClient()
    ).apply {
        useParallelConnections = true
        progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
        diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
        diskSpoolWriterExecutorForTesting = Executor { }
        diskSpoolWriterProfileObserverForTesting = { connections, chunkBytes ->
            capturedProfiles += connections to (chunkBytes / 1024 / 1024)
        }
    }

    factory.progressiveUpstreamFactoryForTesting(
        url = "https://real-debrid.com/path/video.mkv",
        headers = emptyMap()
    )

    assertEquals(listOf(2 to 18), capturedProfiles)
    factory.shutdown()
}
```

Add imports:

```kotlin
import com.nexio.tv.data.local.VodCacheSizeMode
```

- [ ] **Step 2: Run factory tests to verify RED**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest'
```

Expected: FAIL because disk spool is still gated on `spoolStorageProbeResult`, and parallel profile hook is missing.

- [ ] **Step 3: Remove diagnostic gating**

In `createDiskSpoolFactoryIfEligible`, delete the required probe freshness/capability gate:

```kotlin
val result = spoolStorageProbeResult ?: return null
...
if (!SpoolStoragePolicy.isFresh(...)) return null
...
if (!SpoolStoragePolicy.canSustain(...)) return null
```

Keep capacity/free-space checks. The feature can fall back when the selected spool directory is unavailable or does not have enough free space, but it must not require a diagnostic result.

- [ ] **Step 4: Keep VOD cache out of disk spool path**

In `createMediaSource`, compute:

```kotlin
val diskSpoolEligibleBySetting = progressivePlaybackDiskMode == ProgressivePlaybackDiskMode.SPOOL &&
    !isHls &&
    !isDash &&
    shouldUseVodCache(url)
```

Change `useVodCache` to include:

```kotlin
!diskSpoolEligibleBySetting
```

This prevents stale settings from wrapping disk spool in `CacheDataSource`.

- [ ] **Step 5: Add storage location and parallel profile wiring**

Add factory var:

```kotlin
var diskSpoolStorageLocation: DiskSpoolStorageLocation = DiskSpoolStorageLocation.BUILTIN
```

Add test hooks:

```kotlin
internal var diskSpoolDirectoryResolverForTesting: ((Context, DiskSpoolStorageLocation) -> File?)? = null
internal var diskSpoolWriterProfileObserverForTesting: ((Int, Int) -> Unit)? = null
```

Implement directory resolution:

```kotlin
private fun diskSpoolDirectoryOrNull(): File? {
    return diskSpoolDirectoryResolverForTesting?.invoke(context, diskSpoolStorageLocation)
        ?: DiskSpoolStorageResolver.resolveSpoolDirectory(context, diskSpoolStorageLocation)
}
```

Pass parallel args from `selectProgressiveUpstreamFactory` into `createDiskSpoolFactoryIfEligible`:

```kotlin
parallelConnectionsEnabled = parallelConnectionsEnabled,
fallbackParallelConnectionCount = fallbackParallelConnectionCount,
fallbackParallelChunkSizeMb = fallbackParallelChunkSizeMb
```

Compute profile:

```kotlin
val diskSpoolProfile = if (parallelConnectionsEnabled) {
    resolveParallelProviderProfiles(
        url = url,
        warmAheadEnabledForStream = false,
        fallbackConnectionCount = fallbackParallelConnectionCount,
        fallbackChunkSizeMb = fallbackParallelChunkSizeMb
    ).playback
} else {
    ParallelProviderProfile(connectionCount = 1, chunkSizeMb = SAFE_DEFAULT_PARALLEL_CHUNK_SIZE_MB)
}
```

Pass profile into `scheduleDiskSpoolWriter`, and construct writer with:

```kotlin
DiskSpoolWriter(
    playbackOkHttpClient,
    requestHeaders = requestHeaders,
    chunkBytes = profile.chunkSizeMb * 1024 * 1024,
    parallelConnections = profile.connectionCount,
    startupPriorityBytes = DISK_SPOOL_STARTUP_PRIORITY_BYTES
)
```

Add constant:

```kotlin
private const val DISK_SPOOL_STARTUP_PRIORITY_BYTES = 100L * 1024L * 1024L
```

Call the observer before scheduling:

```kotlin
diskSpoolWriterProfileObserverForTesting?.invoke(profile.connectionCount, profile.chunkSizeMb * 1024 * 1024)
```

- [ ] **Step 6: Run factory tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "feat: enable non-gated disk spool playback"
```

---

## Task 7: Runtime Wiring And Verification

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`

- [ ] **Step 1: Wire storage location into factory**

Where existing settings are copied into `PlayerMediaSourceFactory`, add:

```kotlin
mediaSourceFactory.diskSpoolStorageLocation = settings.diskSpoolStorageLocation
```

Do this in both initialization and observer paths.

- [ ] **Step 2: Run focused verification**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.data.local.PlayerSettingsDataStoreSpoolModeTest' --tests 'com.nexio.tv.ui.screens.settings.PlaybackBufferNetworkSettingsTest' --tests 'com.nexio.tv.ui.screens.settings.PlaybackSettingsViewModelSpoolModeTest' --tests 'com.nexio.tv.ui.screens.player.spool.*Test' --tests 'com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest'
```

Expected: PASS.

- [ ] **Step 3: Run diff hygiene**

Run:

```bash
git diff --check
git status --short
```

Expected: `git diff --check` prints nothing. `git status --short` only shows intended modified files before final commit.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt
git commit -m "feat: apply disk spool storage selection at runtime"
```

---

## Acceptance Requirements

- Disk-spool playback does not require a speed-test/diagnostic result.
- Builtin/external storage selection works with app-specific directories.
- External is only enabled when mounted removable external app cache storage exists.
- VOD cache and disk spool are mutually exclusive in settings and factory behavior.
- Parallel connections are supported by disk spool and use existing provider profiles.
- Parallel disk spool keeps memory bounded by streaming buffers.
- UI warns about random/interleaved-write risk when disk spool and parallel connections are enabled.
- Diagnostic reports sequential write/read under concurrent load.
- Diagnostic reports sequential read plus random write under concurrent load when parallel is enabled.
- Diagnostic result is displayed as advisory status only and never blocks disk-spool playback.
- Playback startup prioritizes the first 100 MiB of the file, but does not make 100 MiB an indefinite hard gate.

## Self-Review

- Spec coverage: external/builtin storage, VOD mutual exclusion, disk spool parallel support, CPDT-style diagnostics, non-gating behavior, and 100 MiB startup clarification are all mapped to tasks.
- Self-review scan: no open planning markers remain.
- Type consistency: `DiskSpoolStorageLocation`, `DiskSpoolStorageResolver`, `DiskSpoolStorageDiagnostic`, `concurrentSequentialWriteMbps`, `concurrentSequentialReadMbps`, `concurrentRandomWriteMbps`, and factory profile hooks are consistently named.
- Scope check: the plan does not add SAF, folder picking, broad storage permissions, or diagnostic-based feature gating.
