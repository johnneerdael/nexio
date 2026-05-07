# Streaming Cache Phase 3 LoadControl Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add device-aware, byte-capped `DefaultLoadControl` for the streaming-cache debug path so Media3 SampleQueue stays inside `MemoryBudget.effectiveSampleQueueBytes`.

**Architecture:** Keep the clean baseline reachable: streaming-cache flag OFF continues using `DefaultLoadControl.Builder().build()`. When streaming cache is enabled for an HTTP stream, build a memory-budgeted load control from `MemoryBudget` using a conservative 120 Mbps bitrate assumption because duration/bitrate is not reliably known before player construction.

**Tech Stack:** Kotlin, Media3 `DefaultLoadControl`, existing `MemoryBudget`, Robolectric/JUnit unit tests, Gradle universal debug test/compile tasks.

---

## Scope

Implement only Phase 3:

- Add a small `PlayerLoadControlFactory` that computes an inspectable `LoadControlSpec` and builds `DefaultLoadControl`.
- Use budgeted load control only when `StreamingCacheKillSwitch.evaluate(...).enabled == true`.
- Leave trailers, provider probing, coverage-aware miss coordination, second connection support, UI, and diagnostics surfaces unchanged.
- Preserve user/application baseline behavior when streaming cache is disabled.

Important design decisions:

- Use `DEFAULT_ESTIMATED_BITRATE_BPS = 120_000_000L` for Phase 3. Do not infer bitrate from content length alone; runtime duration is not available before `ExoPlayer` construction.
- Compute `maxBufferSeconds = (effectiveSampleQueueBytes / bytesPerSecond).coerceIn(8, 30)`.
- Compute `minBufferMs = max(maxBufferSeconds * 500, bufferForPlaybackAfterRebufferMs)` so Media3 builder invariants are always satisfied.
- Use `bufferForPlaybackMs = 2_500` and `bufferForPlaybackAfterRebufferMs = 5_000`, matching the architecture plan.
- Use `setPrioritizeTimeOverSizeThresholdsForStreaming(false)` so byte cap is authoritative for streaming playback.
- Use `setTargetBufferBytes(effectiveSampleQueueBytes.toInt())`; the current cap is 350 MiB, safely below `Int.MAX_VALUE`.

---

### Task 1: Add Budgeted LoadControl Factory

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerLoadControlFactory.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerLoadControlFactoryTest.kt`

- [ ] **Step 1: Write failing factory tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerLoadControlFactoryTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLoadControlFactoryTest {

    @Test
    fun budgetedSpec_capsTargetBufferToMemoryBudget() {
        val spec = PlayerLoadControlFactory.buildBudgetedSpec(
            effectiveSampleQueueBytes = 128L * 1024L * 1024L,
            estimatedBitrateBps = 120_000_000L
        )

        assertEquals(128 * 1024 * 1024, spec.targetBufferBytes)
        assertFalse(spec.prioritizeTimeOverSizeThresholdsForStreaming)
        assertTrue(spec.maxBufferMs in 8_000..30_000)
    }

    @Test
    fun budgetedSpec_minBufferAlwaysSatisfiesPlaybackThresholds() {
        val spec = PlayerLoadControlFactory.buildBudgetedSpec(
            effectiveSampleQueueBytes = 32L * 1024L * 1024L,
            estimatedBitrateBps = 120_000_000L
        )

        assertTrue(spec.minBufferMs >= spec.bufferForPlaybackMs)
        assertTrue(spec.minBufferMs >= spec.bufferForPlaybackAfterRebufferMs)
        assertTrue(spec.maxBufferMs >= spec.minBufferMs)
    }

    @Test
    fun budgetedSpec_usesThirtySecondCapForLargeBudget() {
        val spec = PlayerLoadControlFactory.buildBudgetedSpec(
            effectiveSampleQueueBytes = 900L * 1024L * 1024L,
            estimatedBitrateBps = 120_000_000L
        )

        assertEquals(30_000, spec.maxBufferMs)
        assertEquals(15_000, spec.minBufferMs)
    }

    @Test
    fun buildBudgetedLoadControl_constructsMedia3LoadControl() {
        val loadControl = PlayerLoadControlFactory.buildBudgetedLoadControl(
            effectiveSampleQueueBytes = 128L * 1024L * 1024L
        )

        assertTrue(loadControl is androidx.media3.exoplayer.DefaultLoadControl)
    }
}
```

- [ ] **Step 2: Run failing factory tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerLoadControlFactoryTest
```

Expected: FAIL because `PlayerLoadControlFactory` does not exist.

- [ ] **Step 3: Implement the factory**

Create `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerLoadControlFactory.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import kotlin.math.max

@androidx.annotation.OptIn(UnstableApi::class)
internal object PlayerLoadControlFactory {
    const val DEFAULT_ESTIMATED_BITRATE_BPS = 120_000_000L
    const val BUFFER_FOR_PLAYBACK_MS = 2_500
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

    data class LoadControlSpec(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferForPlaybackAfterRebufferMs: Int,
        val targetBufferBytes: Int,
        val prioritizeTimeOverSizeThresholdsForStreaming: Boolean
    )

    fun buildDefaultLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder().build()
    }

    fun buildBudgetedLoadControl(
        effectiveSampleQueueBytes: Long,
        estimatedBitrateBps: Long = DEFAULT_ESTIMATED_BITRATE_BPS
    ): DefaultLoadControl {
        val spec = buildBudgetedSpec(
            effectiveSampleQueueBytes = effectiveSampleQueueBytes,
            estimatedBitrateBps = estimatedBitrateBps
        )
        return DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(
                spec.minBufferMs,
                spec.maxBufferMs,
                spec.bufferForPlaybackMs,
                spec.bufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(spec.targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholdsForStreaming(
                spec.prioritizeTimeOverSizeThresholdsForStreaming
            )
            .build()
    }

    fun buildBudgetedSpec(
        effectiveSampleQueueBytes: Long,
        estimatedBitrateBps: Long = DEFAULT_ESTIMATED_BITRATE_BPS
    ): LoadControlSpec {
        val bytesPerSecond = (estimatedBitrateBps / 8L).coerceAtLeast(1L)
        val maxBufferSeconds = (effectiveSampleQueueBytes / bytesPerSecond).coerceIn(8L, 30L)
        val maxBufferMs = (maxBufferSeconds * 1_000L).toInt()
        val minBufferMs = max(
            (maxBufferSeconds * 500L).toInt(),
            BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        )
        return LoadControlSpec(
            minBufferMs = minBufferMs,
            maxBufferMs = maxBufferMs,
            bufferForPlaybackMs = BUFFER_FOR_PLAYBACK_MS,
            bufferForPlaybackAfterRebufferMs = BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            targetBufferBytes = effectiveSampleQueueBytes
                .coerceIn(MemoryBudget.MIN_SAMPLE_QUEUE_BYTES, MemoryBudget.MAX_SAMPLE_QUEUE_BYTES)
                .toInt(),
            prioritizeTimeOverSizeThresholdsForStreaming = false
        )
    }
}
```

- [ ] **Step 4: Run factory tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerLoadControlFactoryTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerLoadControlFactory.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerLoadControlFactoryTest.kt
git commit -m "feat: add streaming cache load control factory"
```

---

### Task 2: Gate Budgeted LoadControl Into Player Initialization

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerLoadControlFactoryTest.kt`

- [ ] **Step 1: Add structural regression tests**

Extend `PlayerLoadControlFactoryTest.kt` with source-level guard tests. Use the same `Paths.get("").toAbsolutePath()` source-root helper pattern used in `PlayerStreamingCacheFillWiringTest`:

```kotlin
@Test
fun `player initialization uses default load control when streaming cache disabled`() {
    val source = sourceFile(
        "com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt"
    ).toFile().readText()

    assertTrue(source.contains("PlayerLoadControlFactory.buildDefaultLoadControl()"))
}

@Test
fun `player initialization uses budgeted load control when streaming cache enabled`() {
    val source = sourceFile(
        "com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt"
    ).toFile().readText()

    assertTrue(source.contains("PlayerLoadControlFactory.buildBudgetedLoadControl("))
    assertTrue(source.contains("MemoryBudget(context).effectiveSampleQueueBytes"))
}
```

Add imports:

```kotlin
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.invariantSeparatorsPathString
```

Add helper:

```kotlin
private fun sourceFile(relativePath: String): Path {
    val cwd = Paths.get("").toAbsolutePath().normalize()
    val relative = Paths.get("app", "src", "main", "java")
    val directCandidate = cwd.resolve(relative)
    val parentCandidate = cwd.resolve("..").resolve(relative).normalize()
    val sourceRoot = when {
        Files.exists(directCandidate) -> directCandidate
        Files.exists(parentCandidate) -> parentCandidate
        else -> error("Unable to locate app/src/main/java from working directory $cwd")
    }
    return sourceRoot.resolve(relativePath).also { path ->
        require(path.invariantSeparatorsPathString.endsWith(relativePath))
    }
}
```

- [ ] **Step 2: Run failing structural tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerLoadControlFactoryTest
```

Expected: FAIL because player initialization still uses `DefaultLoadControl.Builder().build()` directly.

- [ ] **Step 3: Modify player initialization**

In `PlayerRuntimeControllerInitialization.kt`, replace:

```kotlin
val loadControl = DefaultLoadControl.Builder().build()
```

with:

```kotlin
val loadControl = if (streamingCacheDecision.enabled) {
    PlayerLoadControlFactory.buildBudgetedLoadControl(
        effectiveSampleQueueBytes = MemoryBudget(context).effectiveSampleQueueBytes
    )
} else {
    PlayerLoadControlFactory.buildDefaultLoadControl()
}
```

Remove the now-unused `DefaultLoadControl` import if it is no longer needed.

Important: use `streamingCacheDecision.enabled`, not raw `requestedStreamingCache`, so the kill-switch still controls the budgeted path.

- [ ] **Step 4: Run targeted tests and compile**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerLoadControlFactoryTest
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin
```

Expected: both PASS.

- [ ] **Step 5: Commit Task 2**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerLoadControlFactoryTest.kt
git commit -m "feat: gate budgeted load control behind streaming cache"
```

---

### Task 3: Final Verification

**Files:**
- Modify only if verification exposes an issue.

- [ ] **Step 1: Run Phase 3 targeted tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon --rerun-tasks :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerLoadControlFactoryTest --tests com.nexio.tv.ui.screens.player.MemoryBudgetTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: PASS.

- [ ] **Step 2: Run compile**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin
```

Expected: PASS.

- [ ] **Step 3: Verify acceptance constraints**

Check the diff:

```bash
git diff origin/main..HEAD -- app/src/main/java/com/nexio/tv/ui/screens/player
```

Expected:

- No `LoadControl` change in `TrailerPlayer.kt`.
- No `CacheFillWorker`, provider probe, `CoverageAwareDataSource`, or second-connection changes.
- `PlayerRuntimeControllerInitialization.kt` uses budgeted load control only when `streamingCacheDecision.enabled`.
- Flag OFF path still uses `PlayerLoadControlFactory.buildDefaultLoadControl()`.

- [ ] **Step 4: Commit verification fixes if needed**

If verification required fixes:

```bash
git add <changed-files>
git commit -m "fix: stabilize streaming cache load control"
```

If no fixes were needed, do not create an empty commit.

---

## Self-Review

**Spec coverage:** This plan covers Phase 3 only: budgeted `DefaultLoadControl` using `MemoryBudget`, byte cap enforcement via `setTargetBufferBytes`, `setPrioritizeTimeOverSizeThresholdsForStreaming(false)`, and clean feature-flag OFF baseline. It intentionally excludes provider probe, miss coordination, second connection, diagnostics UI, and trailer playback.

**Placeholder scan:** No placeholders or “add tests for this” steps remain; all tests and production snippets are concrete.

**Type consistency:** The plan consistently uses `PlayerLoadControlFactory`, `LoadControlSpec`, `MemoryBudget.effectiveSampleQueueBytes`, and `streamingCacheDecision.enabled`.
