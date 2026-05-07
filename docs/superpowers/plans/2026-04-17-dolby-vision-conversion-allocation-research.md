# Dolby Vision Conversion Allocation Research Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Determine whether Dolby Vision conversion/tone-map support contributes to playback GC churn, and identify safe optimization options without removing conversion capability.

**Architecture:** Add lightweight counters around existing Dolby Vision conversion hooks, run targeted playback/device validation, and inspect whether allocations come from sample rewriting, RPU conversion, RPU tapping, or unrelated playback paths. Any follow-up optimization must preserve the conversion feature and be separately planned after evidence.

**Tech Stack:** Kotlin, Media3 extractor Dolby Vision compatibility hooks, `DoviBridge`, ADB logcat/top/gfxinfo, JUnit/Robolectric tests for instrumentation helpers.

---

## Scope

This is a research/instrumentation plan, not an optimization implementation plan.

This plan includes:

- Add opt-in lightweight counters for Dolby Vision hook calls and byte volumes.
- Verify whether the current playback stream activates DV7/DV8.1 or DV5 conversion/tap paths.
- Identify allocation-heavy code paths and rank optimization options.

This plan does not:

- Remove Dolby Vision conversion.
- Disable Dolby Vision conversion by default.
- Change codec fallback behavior.
- Change disk spool behavior.
- Change playback selection or stream filtering.

## Current Code Context

High-allocation candidate sites:

- `app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt`
  - `rewriteMp4HevcSample(...)` uses `copyOfRange(...)`, `ByteArrayOutputStream(...)`, and `toByteArray()`.
  - `normalizeNuhLayerIdToZero(...)` uses `copyOf()`.
  - `appendLengthDelimitedNal(...)` allocates a new full output `ByteArray`.
  - RPU tap helpers use `copyOfRange(...)` to inspect NAL payloads.

- `app/src/main/java/com/nexio/tv/core/player/Dv5HardwareToneMapRpuTap.kt`
  - `onRpuSample(...)` copies each RPU payload and stores up to `512` entries.

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - Installs the hook through `MatroskaDolbyVisionHookInstaller.maybeInstall(...)`.
  - Configures DV5/DV7 conversion and RPU tap state from player settings.

## File Structure

- Modify: `app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt`
  - Add runtime counters for hook invocations, input/output bytes, allocation-shaped events, and profile/mode state.
  - Add a snapshot method for logging/tests.

- Modify: `app/src/main/java/com/nexio/tv/core/player/Dv5HardwareToneMapRpuTap.kt`
  - Add a runtime snapshot for queued bytes and copy count.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`
  - Extend existing `BUFFER:` diagnostics to include Dolby Vision allocation counters when buffer logs are enabled.

- Create: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionAllocationTelemetryTest.kt`
  - Test counter reset/snapshot behavior without requiring real native conversion.

---

### Task 1: Add Dolby Vision Hook Allocation Counters

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt`
- Create: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionAllocationTelemetryTest.kt`

- [ ] **Step 1: Write failing counter reset/snapshot test**

Create `app/src/test/java/com/nexio/tv/core/player/DolbyVisionAllocationTelemetryTest.kt`:

```kotlin
package com.nexio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DolbyVisionAllocationTelemetryTest {

    @Test
    fun `runtime allocation counters reset to zero`() {
        MatroskaDolbyVisionHookInstaller.resetRuntimeCounters()

        val snapshot = MatroskaDolbyVisionHookInstaller.runtimeAllocationSnapshot()

        assertEquals(0L, snapshot.rewriteSampleCalls)
        assertEquals(0L, snapshot.rewriteInputBytes)
        assertEquals(0L, snapshot.rewriteOutputBytes)
        assertEquals(0L, snapshot.nalCopyBytes)
        assertEquals(0L, snapshot.appendedSampleBytes)
        assertEquals(0L, snapshot.rpuNalTransformCalls)
    }
}
```

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionAllocationTelemetryTest
```

Expected: FAIL because `runtimeAllocationSnapshot()` and the snapshot type do not exist.

- [ ] **Step 3: Add snapshot type and counters**

In `MatroskaDolbyVisionHookInstaller.kt`, add this data class near the top of the object:

```kotlin
data class AllocationSnapshot(
    val rewriteSampleCalls: Long,
    val rewriteInputBytes: Long,
    val rewriteOutputBytes: Long,
    val nalCopyBytes: Long,
    val appendedSampleBytes: Long,
    val rpuNalTransformCalls: Long
)
```

Add these fields near existing counters:

```kotlin
private val rewriteSampleCalls = AtomicLong(0L)
private val rewriteInputBytes = AtomicLong(0L)
private val rewriteOutputBytes = AtomicLong(0L)
private val nalCopyBytes = AtomicLong(0L)
private val appendedSampleBytes = AtomicLong(0L)
private val rpuNalTransformCalls = AtomicLong(0L)
```

Extend `resetRuntimeCounters()`:

```kotlin
rewriteSampleCalls.set(0L)
rewriteInputBytes.set(0L)
rewriteOutputBytes.set(0L)
nalCopyBytes.set(0L)
appendedSampleBytes.set(0L)
rpuNalTransformCalls.set(0L)
```

Add:

```kotlin
fun runtimeAllocationSnapshot(): AllocationSnapshot {
    return AllocationSnapshot(
        rewriteSampleCalls = rewriteSampleCalls.get(),
        rewriteInputBytes = rewriteInputBytes.get(),
        rewriteOutputBytes = rewriteOutputBytes.get(),
        nalCopyBytes = nalCopyBytes.get(),
        appendedSampleBytes = appendedSampleBytes.get(),
        rpuNalTransformCalls = rpuNalTransformCalls.get()
    )
}
```

- [ ] **Step 4: Increment counters in allocation-shaped sites**

In `rewriteMp4HevcSample(...)`, at the top after validation:

```kotlin
rewriteSampleCalls.incrementAndGet()
rewriteInputBytes.addAndGet(sampleLengthDelimited.size.toLong())
```

After:

```kotlin
val originalNal = sampleLengthDelimited.copyOfRange(offset, offset + nalSize)
```

add:

```kotlin
nalCopyBytes.addAndGet(originalNal.size.toLong())
```

Before returning `out.toByteArray()`, change:

```kotlin
return out.toByteArray()
```

to:

```kotlin
val rewritten = out.toByteArray()
rewriteOutputBytes.addAndGet(rewritten.size.toLong())
return rewritten
```

In `maybeConvertDolbyVisionRpuNal(...)`, add:

```kotlin
rpuNalTransformCalls.incrementAndGet()
```

In `normalizeNuhLayerIdToZero(...)`, after `val out = nalPayload.copyOf()`, add:

```kotlin
nalCopyBytes.addAndGet(out.size.toLong())
```

In `appendLengthDelimitedNal(...)`, after allocating `out`, add:

```kotlin
appendedSampleBytes.addAndGet(out.size.toLong())
```

- [ ] **Step 5: Run test and verify GREEN**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionAllocationTelemetryTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt app/src/test/java/com/nexio/tv/core/player/DolbyVisionAllocationTelemetryTest.kt
git commit -m "instrument dolby vision conversion allocations"
```

---

### Task 2: Add DV5 RPU Tap Queue Telemetry

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/Dv5HardwareToneMapRpuTap.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionAllocationTelemetryTest.kt`

- [ ] **Step 1: Add failing RPU tap snapshot test**

Append this test to `DolbyVisionAllocationTelemetryTest.kt`:

```kotlin
@Test
fun `dv5 rpu tap snapshot resets to zero when disabled`() {
    Dv5HardwareToneMapRpuTap.setEnabledForPlayback(enabled = false, streamUrl = "https://example.com/movie.mkv")

    val snapshot = Dv5HardwareToneMapRpuTap.runtimeSnapshot()

    assertEquals(false, snapshot.enabled)
    assertEquals(0, snapshot.queuedEntries)
    assertEquals(0L, snapshot.queuedBytes)
    assertEquals(0L, snapshot.copiedBytes)
}
```

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionAllocationTelemetryTest
```

Expected: FAIL because `runtimeSnapshot()` does not exist.

- [ ] **Step 3: Add RPU tap runtime snapshot**

In `Dv5HardwareToneMapRpuTap.kt`, add:

```kotlin
data class RuntimeSnapshot(
    val enabled: Boolean,
    val queuedEntries: Int,
    val queuedBytes: Long,
    val copiedBytes: Long
)
```

Add field:

```kotlin
private val copiedBytes = AtomicLong(0L)
```

In `setEnabledForPlayback(...)`, reset:

```kotlin
copiedBytes.set(0L)
```

In `onRpuSample(...)`, replace:

```kotlin
queue[sampleTimeUs] = rpuNalPayload.copyOf()
```

with:

```kotlin
val copy = rpuNalPayload.copyOf()
queue[sampleTimeUs] = copy
copiedBytes.addAndGet(copy.size.toLong())
```

Add:

```kotlin
fun runtimeSnapshot(): RuntimeSnapshot {
    return synchronized(lock) {
        RuntimeSnapshot(
            enabled = enabled,
            queuedEntries = queue.size,
            queuedBytes = queue.values.sumOf { it.size.toLong() },
            copiedBytes = copiedBytes.get()
        )
    }
}
```

- [ ] **Step 4: Run test and verify GREEN**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionAllocationTelemetryTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/Dv5HardwareToneMapRpuTap.kt app/src/test/java/com/nexio/tv/core/player/DolbyVisionAllocationTelemetryTest.kt
git commit -m "instrument dv5 rpu tap allocations"
```

---

### Task 3: Include Dolby Allocation Telemetry In Buffer Logs

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`

- [ ] **Step 1: Add telemetry string in buffer log code**

In `PlayerRuntimeControllerPlaybackEvents.kt`, inside the existing buffer-log block where `dv7doviState` is built, add:

```kotlin
val dvAlloc = MatroskaDolbyVisionHookInstaller.runtimeAllocationSnapshot()
val rpuSnapshot = Dv5HardwareToneMapRpuTap.runtimeSnapshot()
```

Extend the `dv7doviState = buildString { ... }` block with:

```kotlin
append(",rewriteCalls=")
append(dvAlloc.rewriteSampleCalls)
append(",rewriteInMb=")
append(dvAlloc.rewriteInputBytes / (1024L * 1024L))
append(",rewriteOutMb=")
append(dvAlloc.rewriteOutputBytes / (1024L * 1024L))
append(",nalCopyMb=")
append(dvAlloc.nalCopyBytes / (1024L * 1024L))
append(",appendMb=")
append(dvAlloc.appendedSampleBytes / (1024L * 1024L))
append(",rpuCalls=")
append(dvAlloc.rpuNalTransformCalls)
append(",rpuTapEntries=")
append(rpuSnapshot.queuedEntries)
append(",rpuTapQueuedKb=")
append(rpuSnapshot.queuedBytes / 1024L)
append(",rpuTapCopiedKb=")
append(rpuSnapshot.copiedBytes / 1024L)
```

Add this import:

```kotlin
import com.nexio.tv.core.player.Dv5HardwareToneMapRpuTap
```

- [ ] **Step 2: Compile targeted player tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerPlaybackProgressUiStateTest
```

Expected: PASS. This catches compile errors in the player module.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt
git commit -m "log dolby vision allocation telemetry"
```

---

### Task 4: Device Research Run

**Files:**
- No source changes.

- [ ] **Step 1: Enable buffer logs in the app settings**

On the device, enable playback buffer logs through the existing debug/playback settings UI, or use the app’s existing debug settings path if available. Do not add a new setting in this plan.

- [ ] **Step 2: Start a Dolby Vision candidate playback**

Use a known stream that previously required DV conversion or is suspected of triggering the GC churn.

- [ ] **Step 3: Capture a clean telemetry window**

Run:

```bash
adb connect 192.168.50.58:5555
adb -s 192.168.50.58:5555 logcat -c
adb -s 192.168.50.58:5555 shell dumpsys gfxinfo com.nexio.tv reset
sleep 60
adb -s 192.168.50.58:5555 logcat -d | rg -n "BUFFER:|DV7_DOVI|DoviBridge|Dv7ExtractorHook|Dv5HwRpuTap|Background concurrent mark compact GC|WaitForGcToComplete|JankStats"
adb -s 192.168.50.58:5555 shell top -H -b -n 1 -p "$(adb -s 192.168.50.58:5555 shell pidof com.nexio.tv)" | head -80
adb -s 192.168.50.58:5555 shell dumpsys gfxinfo com.nexio.tv
```

Expected:

- If `rewriteCalls`, `rewriteInMb`, `rewriteOutMb`, or `nalCopyMb` grow rapidly in the same window as LOS GC logs, DV conversion is a confirmed contributor.
- If DV counters stay zero while LOS GCs continue, deprioritize DV conversion and continue with disk spool/adaptive headroom work.
- If RPU tap queued/copied bytes grow while DV5 hardware tone-map is active, plan a bounded-copy or native-side handoff optimization for RPU tap only.

- [ ] **Step 4: Write research summary**

Create `docs/superpowers/plans/2026-04-17-dolby-vision-conversion-allocation-findings.md` with this structure:

```markdown
# Dolby Vision Conversion Allocation Findings

## Device / Build

- Device:
- App version:
- Stream type:
- Player engine:

## Telemetry Window

- Duration:
- Playback state:
- GC events:
- Jank:

## Dolby Counters

- rewriteCalls:
- rewriteInMb:
- rewriteOutMb:
- nalCopyMb:
- appendMb:
- rpuCalls:
- rpuTapEntries:
- rpuTapQueuedKb:
- rpuTapCopiedKb:

## Conclusion

- Confirmed contributor: yes/no
- Confidence:
- Recommended follow-up plan:
```

- [ ] **Step 5: Commit research findings**

```bash
git add docs/superpowers/plans/2026-04-17-dolby-vision-conversion-allocation-findings.md
git commit -m "document dolby vision allocation telemetry findings"
```

---

## Candidate Optimization Ideas For Later

Do not implement these in this research plan. Use the telemetry to choose a follow-up.

1. Avoid `copyOfRange(...)` for non-RPU NAL units by passing source array offsets into inspection helpers.
2. Short-circuit `rewriteMp4HevcSample(...)` earlier when profile/mode indicates no rewrite is needed.
3. Replace `ByteArrayOutputStream + toByteArray()` with a pre-sized output only when changes are known.
4. Track whether `appendLengthDelimitedNal(...)` is necessary for the current extractor callback shape.
5. Bound or eliminate copies in `Dv5HardwareToneMapRpuTap` by storing only required RPU bytes or moving handoff closer to native.

## Self-Review

**Spec coverage:** The plan researches whether Dolby Vision conversion contributes to GC churn and explicitly forbids removing conversion.

**Placeholder scan:** The plan contains no TBD/TODO/fill-in instructions.

**Type consistency:** New types and methods are consistent: `AllocationSnapshot`, `runtimeAllocationSnapshot()`, `RuntimeSnapshot`, and `Dv5HardwareToneMapRpuTap.runtimeSnapshot()`.

