# PRDS Resource Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stabilize Real-Debrid progressive playback by landing the measured provider profile and adding PRDS resource telemetry before attempting larger transport architecture changes.

**Architecture:** Keep provider-specific profile policy in `PlayerMediaSourceFactory`; keep transport mechanics in `ParallelRangeDataSource`. This plan deliberately avoids shared executors, process-wide governors, and ring-buffer transport rewrites until telemetry proves they are required.

**Tech Stack:** Kotlin, AndroidX Media3 `DataSource`, OkHttp, Robolectric unit tests, `PlayerTransportTelemetry` structured log helper.

---

## Architecture Review Summary

| Candidate | Classification | Decision |
|---|---|---|
| Real-Debrid provider profile tuning | Small safe patch | Include now. Use measured `2 x 18MiB` playback and optional `1 x 18MiB` warm-ahead. |
| PRDS telemetry for active datasource count, scheduled chunks, buffer pool size, close/cancel timing | Small safe patch | Include now. Observability-only and transport-local. |
| Cap `maxAhead` below `parallelConnections + 1` | Medium contained refactor | Defer until telemetry shows scheduled chunk buffers are the pressure point. |
| Real-Debrid warm-ahead single-connection while playback uses PRDS | Medium contained refactor | Defer until telemetry shows warm-ahead duplicates PRDS pressure. |
| Shared bounded executor per process | Medium-high refactor | Defer. Thread ownership and cancellation semantics change. |
| Global active PRDS / in-flight buffer governor | Large architectural change | Defer. Requires process-wide admission control and fairness rules. |
| Decouple network range size from resident memory buffer size | Large architectural change | Defer. This rewrites the PRDS read path. |

## File Structure

- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - Owns provider-specific profile selection.
  - Real-Debrid profile should be `2 x 18MiB` playback and optional `1 x 18MiB` warm-ahead.
- Modify `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
  - Verifies Real-Debrid, Premiumize, and fallback provider profile policy.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
  - Owns PRDS chunk scheduling, full-chunk buffer pool, executor lifecycle, and close/cancel behavior.
  - Add instance-local diagnostic snapshot and structured telemetry logs.
- Modify `app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTest.kt`
  - Verifies PRDS diagnostic counters for scheduled chunks, pooled buffers, and close state.

---

### Task 1: Land Real-Debrid 2x18/1x18 Provider Profile

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Write the failing provider profile tests**

In `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`, update the Real-Debrid expectations only:

```kotlin
@Test
fun parallelProviderProfile_realDebridUsesTwoByEighteen() {
    val factory = PlayerMediaSourceFactory(
        context = mockk(relaxed = true),
        playbackOkHttpClient = OkHttpClient()
    )

    assertEquals(
        2 to 18,
        factory.parallelProviderProfileForTesting("https://real-debrid.com/path/movie.mkv")
    )
}
```

Update the warm-ahead disabled Real-Debrid profile test to:

```kotlin
assertEquals(
    (2 to 18) to null,
    factory.parallelProviderProfilesForTesting(
        url = "https://real-debrid.com/path/movie.mkv",
        warmAheadEnabledForStream = false
    )
)
```

Update the warm-ahead enabled Real-Debrid split test to:

```kotlin
assertEquals(
    (2 to 18) to (1 to 18),
    factory.parallelProviderProfilesForTesting(
        url = "https://real-debrid.com/path/movie.mkv",
        warmAheadEnabledForStream = true
    )
)
```

Update the Real-Debrid warm-ahead profile test to:

```kotlin
assertEquals(
    1 to 18,
    factory.warmAheadProviderProfileForTesting(
        url = "https://real-debrid.com/path/movie.mkv",
        warmAheadEnabledForStream = true
    )
)
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest'
```

Expected: FAIL with the Real-Debrid profile tests still returning `24` instead of `18`.

- [ ] **Step 3: Implement the minimal provider profile change**

In `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`, update only the `isRealDebrid` branches in `resolveParallelProviderProfiles()`:

```kotlin
isRealDebrid -> ParallelProviderProfiles(
    playback = ParallelProviderProfile(connectionCount = 2, chunkSizeMb = 18),
    warmAhead = ParallelProviderProfile(connectionCount = 1, chunkSizeMb = 18)
)
```

And:

```kotlin
isRealDebrid -> ParallelProviderProfiles(
    playback = ParallelProviderProfile(connectionCount = 2, chunkSizeMb = 18),
    warmAhead = null
)
```

Do not change Premiumize. Do not change fallback provider settings.

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "fix: tune Real-Debrid parallel transport profile"
```

---

### Task 2: Add Local PRDS Resource Telemetry

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTest.kt`

- [ ] **Step 1: Write the failing diagnostic snapshot test**

In `app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTest.kt`, add this test near the other `ParallelRangeDataSourceTest` tests:

```kotlin
@Test(timeout = 5_000L)
fun `parallel range datasource diagnostic snapshot tracks chunks and close state`() {
    val fixture = RangeServerFixture(
        content = ByteArray(512 * 1024) { (it % 251).toByte() },
        chunkSize = 64 * 1024L,
        transientFailuresByChunkIndex = mutableMapOf()
    )

    fixture.use { server ->
        val dataSource = server.createDataSource()
        dataSource.open(server.dataSpec())
        val buffer = ByteArray(32 * 1024)
        val read = dataSource.read(buffer, 0, buffer.size)

        assertTrue(read > 0)
        val openSnapshot = dataSource.diagnosticSnapshotForTesting()
        assertEquals(false, openSnapshot.closed)
        assertEquals(4, openSnapshot.parallelConnections)
        assertEquals(64 * 1024L, openSnapshot.chunkSizeBytes)
        assertTrue(openSnapshot.scheduledChunks >= 0)

        dataSource.close()
        val closedSnapshot = dataSource.diagnosticSnapshotForTesting()
        assertEquals(true, closedSnapshot.closed)
        assertEquals(0, closedSnapshot.scheduledChunks)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTest.parallel range datasource diagnostic snapshot tracks chunks and close state'
```

Expected: FAIL because `diagnosticSnapshotForTesting()` and its snapshot type do not exist.

- [ ] **Step 3: Add diagnostic snapshot type and method**

In `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`, inside `ParallelRangeDataSource`, add this data class near `BootstrapCacheEntry`:

```kotlin
internal data class DiagnosticSnapshot(
    val closed: Boolean,
    val parallelConnections: Int,
    val chunkSizeBytes: Long,
    val scheduledChunks: Int,
    val pooledBuffers: Int,
    val executorShutdown: Boolean,
    val currentChunkIndex: Long
)
```

Add this method near `getResponseHeaders()`:

```kotlin
internal fun diagnosticSnapshotForTesting(): DiagnosticSnapshot {
    return DiagnosticSnapshot(
        closed = closed.get(),
        parallelConnections = parallelConnections,
        chunkSizeBytes = chunkSize,
        scheduledChunks = chunks.size,
        pooledBuffers = bufferPool.size,
        executorShutdown = executor.isShutdown,
        currentChunkIndex = currentChunkIndex
    )
}
```

- [ ] **Step 4: Add structured telemetry logs without changing transport behavior**

In `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`, add this helper near `diagnosticSnapshotForTesting()`:

```kotlin
private fun logDiagnostic(site: String, windowMs: Long = 5_000L) {
    val snapshot = diagnosticSnapshotForTesting()
    PlayerTransportTelemetry.logThrottled(
        site = "prds.$site",
        windowMs = windowMs,
        pairs = mapOf(
            "closed" to snapshot.closed,
            "connections" to snapshot.parallelConnections,
            "chunkMb" to (snapshot.chunkSizeBytes / 1024L / 1024L),
            "scheduled" to snapshot.scheduledChunks,
            "pooled" to snapshot.pooledBuffers,
            "executorShutdown" to snapshot.executorShutdown,
            "currentChunk" to snapshot.currentChunkIndex,
            "posMb" to (position / 1024L / 1024L),
            "remainingMb" to if (bytesRemaining == C.LENGTH_UNSET.toLong()) "unset" else bytesRemaining / 1024L / 1024L
        )
    )
}
```

Call it at these points:

```kotlin
// In open(), after logging "Parallel mode: ..."
logDiagnostic("open", windowMs = 0L)
```

```kotlin
// At the end of scheduleChunks(), after the for-loop
logDiagnostic("schedule")
```

```kotlin
// In close(), after bufferPool.clear()
logDiagnostic("close", windowMs = 0L)
```

Do not log in `read()` on every call. The schedule and close hooks are enough for the next ADB pass.

- [ ] **Step 5: Run test to verify it passes**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTest.kt
git commit -m "chore: add parallel range datasource diagnostics"
```

---

### Task 3: Re-run Shield ADB Validation With Telemetry

**Files:**
- Read: `diagnostics/adb-captures/`

- [ ] **Step 1: Build an arm64 debug APK**

Run:

```bash
./gradlew :app:assembleArm64Debug
```

Expected: PASS and APK at:

```text
app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
```

- [ ] **Step 2: Install on Shield .13**

Run:

```bash
adb -s 192.168.50.13:5555 install -r app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
```

Expected: `Success`.

- [ ] **Step 3: Capture a Real-Debrid playback run with VOD cache ON, warm-ahead ON, parallel ON**

Use the same capture structure already used in `diagnostics/adb-captures/`:

```bash
RUN_DIR="diagnostics/adb-captures/$(date +%Y%m%d-%H%M%S)-shield13-rd-2x18-warmahead-telemetry"
mkdir -p "$RUN_DIR"
adb -s 192.168.50.13:5555 logcat -c
adb -s 192.168.50.13:5555 shell dumpsys gfxinfo com.nexio.tv reset
adb -s 192.168.50.13:5555 shell log -t NexioCapture "START rd_2x18_warmahead_telemetry"
adb -s 192.168.50.13:5555 logcat -v threadtime > "$RUN_DIR/logcat_full.txt"
```

Keep a second terminal running:

```bash
adb -s 192.168.50.13:5555 logcat -v threadtime -s nexio.transport:I PlayerMediaSource:D ParallelRangeDS:D AndroidRuntime:E libc:W '*:S' > "$RUN_DIR/logcat_playback_focus.txt"
```

Expected: `logcat_playback_focus.txt` contains `site=prds.open`, `site=prds.schedule`, and `site=prds.close` lines.

- [ ] **Step 4: Stop capture and extract metrics**

Run:

```bash
adb -s 192.168.50.13:5555 shell log -t NexioCapture "END rd_2x18_warmahead_telemetry"
adb -s 192.168.50.13:5555 shell dumpsys meminfo com.nexio.tv > "$RUN_DIR/meminfo_final.txt"
adb -s 192.168.50.13:5555 shell dumpsys gfxinfo com.nexio.tv > "$RUN_DIR/gfxinfo_final.txt"
adb -s 192.168.50.13:5555 logcat -b crash -d -v threadtime > "$RUN_DIR/logcat_crash_buffer_final.txt"
rg -i "site=prds|ParallelRangeDS|PlayerMediaSource|UNDERRUN|Process com\\.nexio\\.tv .*died|OutOfMemoryError|Fatal signal" "$RUN_DIR" > "$RUN_DIR/extract_transport_resource_events.txt"
```

Expected:
- No `Process com.nexio.tv ... died`.
- No `OutOfMemoryError`.
- No app native `Fatal signal`.
- `site=prds.schedule` lines show `connections=2`, `chunkMb=18`, and bounded `scheduled` values.

- [ ] **Step 5: Commit or archive the run**

If this run is part of the PR, keep only a compact text summary in the PR body. Do not commit raw `diagnostics/adb-captures/` unless explicitly requested.

---

## Deferred Changes

Do not implement these in this plan:

- Shared process-wide PRDS executor.
- Global PRDS instance/chunk governor.
- Ring-buffer or paged chunk storage.
- Real-Debrid warm-ahead single-connection policy.
- `maxAhead` capping.

Revisit the deferred list only after telemetry shows whether the dominant pressure is duplicate PRDS instances, scheduled chunk count, executor churn, or VOD warm-ahead overlap.

## Self-Review

- Spec coverage: The plan implements the approved RD `2 x 18MiB` / `1 x 18MiB` profile and adds telemetry to classify the remaining architecture candidates.
- Placeholder scan: No placeholder implementation steps are present.
- Type consistency: `DiagnosticSnapshot`, `diagnosticSnapshotForTesting()`, and `logDiagnostic()` are introduced before they are used.
- Scope check: Large transport rewrites are explicitly deferred.
