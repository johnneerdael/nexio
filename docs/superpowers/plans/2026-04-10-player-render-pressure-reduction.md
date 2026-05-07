# Player Render Pressure Reduction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce steady-playback render, decode-adjacent, and graphics pressure on Android TV by decoupling fast progress updates from the main player scene, making `PlayerView` updates conditional, and replacing addon subtitle overlay polling with event-driven scheduling.

**Architecture:** Keep the DV5 / custom sink paths untouched. Treat the current problem as UI/render pressure around the playback surface rather than transport. Split timing/progress state into its own flow so the whole player scene does not recompose every 500–1000 ms, isolate the video surface into a composable that only mutates `PlayerView` when its render inputs actually change, and change addon subtitle overlay updates from a fixed 150 ms poll loop to a cue-boundary scheduler with a bounded fallback wake-up.

**Tech Stack:** Android/Kotlin, Jetpack Compose, Media3 `PlayerView` / `SubtitleView`, ExoPlayer, StateFlow, JUnit4, Robolectric

---

## File Map

### Production files

- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackProgressUiState.kt`
  - own the fast-changing playback timing state (`currentPosition`, `duration`, preview seek)
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerVideoSurface.kt`
  - isolate `PlayerView` binding and conditional subtitle/resize mutations from the rest of `PlayerScreen`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerUiState.kt`
  - remove progress-only fields from the large UI state object
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
  - expose a dedicated progress `StateFlow`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt`
  - expose the dedicated progress flow to Compose
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`
  - update dedicated progress state instead of the monolithic UI state
  - keep seek-preview writes in the progress state
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt`
  - stop collecting progress through the full `PlayerUiState`
  - delegate video-surface binding to `PlayerVideoSurface`
  - let progress-sensitive subcomposables read only the progress flow they need
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAddonSubtitleOverlay.kt`
  - replace fixed interval polling with cue-boundary scheduling

### Test files

- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerPlaybackProgressUiStateTest.kt`
  - pin the dedicated progress-state helpers and ensure `PlayerUiState` no longer carries progress fields
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerVideoSurfaceStateTest.kt`
  - pin overlay-cue resolution and conditional `PlayerView` mutation planning
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAddonSubtitleOverlayTest.kt`
  - pin the new cue-boundary delay scheduling

## Guardrails

- Do not touch DV5 hardware tone-map code, Dolby Vision conversion, or custom audio sink logic in this plan.
- Do not mix transport/cache changes into this work.
- Do not remove subtitle overlays; change only how updates are scheduled and applied.
- Do not let progress updates stop entirely when controls are visible; only isolate them from the rest of the scene.
- Prefer pure helper functions and small composables that are easy to unit test over broad behavioral rewrites.

---

### Task 1: Split Playback Progress State Out Of `PlayerUiState`

**Files:**
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackProgressUiState.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerUiState.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerPlaybackProgressUiStateTest.kt`

- [ ] **Step 1: Write the failing progress-state tests**

```kotlin
package com.nexio.tv.ui.screens.player

import kotlin.reflect.full.primaryConstructor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerPlaybackProgressUiStateTest {

    @Test
    fun `player ui state no longer owns progress timing fields`() {
        val names = PlayerUiState::class.primaryConstructor!!.parameters.mapNotNull { it.name }.toSet()

        assertFalse(names.contains("currentPosition"))
        assertFalse(names.contains("duration"))
        assertFalse(names.contains("pendingPreviewSeekPosition"))
    }

    @Test
    fun `display position prefers preview seek when present`() {
        val state = PlayerPlaybackProgressUiState(
            currentPosition = 10_000L,
            duration = 100_000L,
            pendingPreviewSeekPosition = 42_000L
        )

        assertEquals(42_000L, state.displayPosition)
    }

    @Test
    fun `display position falls back to current position when no preview seek exists`() {
        val state = PlayerPlaybackProgressUiState(
            currentPosition = 10_000L,
            duration = 100_000L,
            pendingPreviewSeekPosition = null
        )

        assertEquals(10_000L, state.displayPosition)
    }
}
```

- [ ] **Step 2: Run the focused progress-state tests to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerPlaybackProgressUiStateTest"`

Expected before implementation: FAIL because `PlayerPlaybackProgressUiState` does not exist and `PlayerUiState` still carries progress timing fields.

- [ ] **Step 3: Add the dedicated progress UI state**

```kotlin
package com.nexio.tv.ui.screens.player

data class PlayerPlaybackProgressUiState(
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val pendingPreviewSeekPosition: Long? = null
) {
    val displayPosition: Long
        get() = pendingPreviewSeekPosition ?: currentPosition
}
```

- [ ] **Step 4: Move progress fields out of `PlayerUiState` and expose a separate flow**

```kotlin
data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val isSwappingAddonSubtitle: Boolean = false,
    val playbackEnded: Boolean = false,
    val title: String = "",
    // ...
)
```

```kotlin
internal val _progressUiState = MutableStateFlow(PlayerPlaybackProgressUiState())
val progressUiState: StateFlow<PlayerPlaybackProgressUiState> = _progressUiState.asStateFlow()
```

```kotlin
val progressUiState: StateFlow<PlayerPlaybackProgressUiState>
    get() = controller.progressUiState
```

- [ ] **Step 5: Route progress writes through the dedicated flow**

```kotlin
internal fun PlayerRuntimeController.startProgressUpdates() {
    progressJob?.cancel()
    progressJob = scope.launch {
        while (isActive) {
            val nowUptime = SystemClock.uptimeMillis()
            val pos = backendCurrentPosition().coerceAtLeast(0L)
            val playerDuration = backendDuration().coerceAtLeast(0L)
            if (playerDuration > lastKnownDuration) {
                lastKnownDuration = playerDuration
            }

            val previewSeek = progressUiState.value.pendingPreviewSeekPosition
            val displayPosition = previewSeek ?: pos
            val controlsVisible = _uiState.value.showControls
            val progressUpdateIntervalMs =
                if (controlsVisible || previewSeek != null) 500L else 1_000L

            if (
                nowUptime - lastProgressUiUpdateUptimeMs >= progressUpdateIntervalMs ||
                progressUiState.value.currentPosition != pos ||
                progressUiState.value.duration != playerDuration ||
                progressUiState.value.pendingPreviewSeekPosition != previewSeek
            ) {
                lastProgressUiUpdateUptimeMs = nowUptime
                _progressUiState.update {
                    it.copy(
                        currentPosition = pos,
                        duration = playerDuration,
                        pendingPreviewSeekPosition = previewSeek
                    )
                }
            }

            delay(500)
        }
    }
}
```

- [ ] **Step 6: Re-run the focused progress-state tests**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerPlaybackProgressUiStateTest"`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackProgressUiState.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerUiState.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerPlaybackProgressUiStateTest.kt
git commit -m "refactor: split playback progress state from player ui state"
```

---

### Task 2: Isolate `PlayerView` Binding And Only Mutate It When Render Inputs Change

**Files:**
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerVideoSurface.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerVideoSurfaceStateTest.kt`

- [ ] **Step 1: Write the failing surface-state tests**

```kotlin
package com.nexio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import com.nexio.tv.data.local.SubtitleStyleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerVideoSurfaceStateTest {

    @Test
    fun `addon overlay cues override built in ai cues`() {
        val addonCue = Cue.Builder().setText("addon").build()
        val aiCue = Cue.Builder().setText("ai").build()

        val result = resolveOverlayCues(
            useAiOverlay = true,
            translatedBuiltInCues = listOf(aiCue),
            addonOverlayCues = listOf(addonCue)
        )

        assertEquals(listOf(addonCue), result)
    }

    @Test
    fun `mutation plan skips work when surface state is unchanged`() {
        val state = PlayerSurfaceRenderState(
            resizeMode = 1,
            subtitleStyle = SubtitleStyleSettings(),
            overlayCues = emptyList()
        )

        val plan = buildPlayerViewMutationPlan(previous = state, current = state)

        assertFalse(plan.updateResizeMode)
        assertFalse(plan.updateSubtitleStyle)
        assertFalse(plan.updateOverlay)
    }

    @Test
    fun `mutation plan updates overlay only when cues change`() {
        val previous = PlayerSurfaceRenderState(
            resizeMode = 1,
            subtitleStyle = SubtitleStyleSettings(),
            overlayCues = emptyList()
        )
        val current = previous.copy(
            overlayCues = listOf(Cue.Builder().setText("Hello").build())
        )

        val plan = buildPlayerViewMutationPlan(previous = previous, current = current)

        assertFalse(plan.updateResizeMode)
        assertFalse(plan.updateSubtitleStyle)
        assertTrue(plan.updateOverlay)
    }
}
```

- [ ] **Step 2: Run the focused surface-state tests to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerVideoSurfaceStateTest"`

Expected before implementation: FAIL because the extracted surface state helpers do not exist and `PlayerScreen` still owns the unconditional `PlayerView` update path.

- [ ] **Step 3: Extract a pure surface render model and mutation plan**

```kotlin
package com.nexio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import com.nexio.tv.data.local.SubtitleStyleSettings

internal data class PlayerSurfaceRenderState(
    val resizeMode: Int,
    val subtitleStyle: SubtitleStyleSettings,
    val overlayCues: List<Cue>
)

internal data class PlayerViewMutationPlan(
    val updateResizeMode: Boolean,
    val updateSubtitleStyle: Boolean,
    val updateOverlay: Boolean
)

internal fun resolveOverlayCues(
    useAiOverlay: Boolean,
    translatedBuiltInCues: List<Cue>,
    addonOverlayCues: List<Cue>
): List<Cue> {
    return when {
        addonOverlayCues.isNotEmpty() -> addonOverlayCues
        useAiOverlay && translatedBuiltInCues.isNotEmpty() -> translatedBuiltInCues
        else -> emptyList()
    }
}

internal fun buildPlayerViewMutationPlan(
    previous: PlayerSurfaceRenderState?,
    current: PlayerSurfaceRenderState
): PlayerViewMutationPlan {
    return PlayerViewMutationPlan(
        updateResizeMode = previous?.resizeMode != current.resizeMode,
        updateSubtitleStyle = previous?.subtitleStyle != current.subtitleStyle,
        updateOverlay = previous?.overlayCues != current.overlayCues
    )
}
```

- [ ] **Step 4: Move `PlayerView` binding into `PlayerVideoSurface`**

```kotlin
@Composable
internal fun PlayerVideoSurface(
    player: androidx.media3.exoplayer.ExoPlayer,
    renderState: PlayerSurfaceRenderState,
    modifier: Modifier = Modifier
) {
    var lastAppliedState by remember(player) {
        mutableStateOf<PlayerSurfaceRenderState?>(null)
    }

    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                this.player = player
                useController = false
                keepScreenOn = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            }
        },
        update = { playerView ->
            if (playerView.player !== player) {
                playerView.player = player
            }

            val plan = buildPlayerViewMutationPlan(lastAppliedState, renderState)
            if (plan.updateResizeMode) {
                playerView.resizeMode = renderState.resizeMode
            }
            if (plan.updateSubtitleStyle) {
                playerView.subtitleView?.let { applySubtitleStyle(it, renderState.subtitleStyle) }
                playerView.ensureExternalSubtitleOverlay()?.let { applySubtitleStyle(it, renderState.subtitleStyle) }
            }
            if (plan.updateOverlay) {
                playerView.ensureExternalSubtitleOverlay()?.let { subtitleOverlay ->
                    val hasCues = renderState.overlayCues.isNotEmpty()
                    subtitleOverlay.visibility = if (hasCues) View.VISIBLE else View.GONE
                    subtitleOverlay.setCues(renderState.overlayCues)
                    playerView.subtitleView?.visibility =
                        if (hasCues) View.INVISIBLE else View.VISIBLE
                }
            }

            lastAppliedState = renderState
        },
        modifier = modifier
    )
}
```

- [ ] **Step 5: Make `PlayerScreen` stop driving `PlayerView` directly**

```kotlin
val uiState by viewModel.uiState.collectAsState()

viewModel.exoPlayer?.let { player ->
    val renderState = remember(
        uiState.resizeMode,
        uiState.subtitleStyle,
        uiState.useBuiltInAiSubtitleOverlay,
        uiState.translatedBuiltInCues,
        uiState.addonOverlayCues
    ) {
        PlayerSurfaceRenderState(
            resizeMode = uiState.resizeMode,
            subtitleStyle = uiState.subtitleStyle,
            overlayCues = resolveOverlayCues(
                useAiOverlay = uiState.useBuiltInAiSubtitleOverlay,
                translatedBuiltInCues = uiState.translatedBuiltInCues,
                addonOverlayCues = uiState.addonOverlayCues
            )
        )
    }

    PlayerVideoSurface(
        player = player,
        renderState = renderState,
        modifier = Modifier.fillMaxSize()
    )
}
```

- [ ] **Step 6: Re-run the focused surface-state tests**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerVideoSurfaceStateTest"`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerVideoSurface.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerVideoSurfaceStateTest.kt
git commit -m "refactor: isolate player view binding from fast ui churn"
```

---

### Task 3: Replace Addon Subtitle Overlay Polling With Cue-Boundary Scheduling

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAddonSubtitleOverlay.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAddonSubtitleOverlayTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt`

- [ ] **Step 1: Write the failing overlay scheduler tests**

```kotlin
@Test
fun `next overlay delay wakes at next cue start boundary`() {
    val groups = listOf(
        TimedAddonCueGroup(startMs = 1_000L, endMs = 2_000L, cues = listOf(cue("a"))),
        TimedAddonCueGroup(startMs = 3_500L, endMs = 4_000L, cues = listOf(cue("b")))
    )

    assertEquals(250L, nextAddonOverlayUpdateDelayMs(groups, positionMs = 750L))
}

@Test
fun `next overlay delay wakes at next cue end boundary`() {
    val groups = listOf(
        TimedAddonCueGroup(startMs = 1_000L, endMs = 2_000L, cues = listOf(cue("a")))
    )

    assertEquals(200L, nextAddonOverlayUpdateDelayMs(groups, positionMs = 1_800L))
}

@Test
fun `next overlay delay falls back to bounded idle delay when no further cues exist`() {
    val groups = listOf(
        TimedAddonCueGroup(startMs = 1_000L, endMs = 2_000L, cues = listOf(cue("a")))
    )

    assertEquals(500L, nextAddonOverlayUpdateDelayMs(groups, positionMs = 5_000L))
}
```

- [ ] **Step 2: Run the focused overlay tests to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerRuntimeControllerAddonSubtitleOverlayTest"`

Expected before implementation: FAIL because `nextAddonOverlayUpdateDelayMs(...)` does not exist and the overlay still polls every `150ms`.

- [ ] **Step 3: Add a cue-boundary scheduler helper**

```kotlin
private const val ADDON_SUBTITLE_OVERLAY_IDLE_DELAY_MS = 500L
private const val ADDON_SUBTITLE_OVERLAY_MIN_DELAY_MS = 40L

internal fun nextAddonOverlayUpdateDelayMs(
    cueGroups: List<TimedAddonCueGroup>,
    positionMs: Long
): Long {
    val nextBoundary = cueGroups
        .asSequence()
        .flatMap { sequenceOf(it.startMs, it.endMs) }
        .filter { it > positionMs }
        .minOrNull()
        ?: return ADDON_SUBTITLE_OVERLAY_IDLE_DELAY_MS

    return (nextBoundary - positionMs)
        .coerceAtLeast(ADDON_SUBTITLE_OVERLAY_MIN_DELAY_MS)
}
```

- [ ] **Step 4: Replace fixed polling with boundary-driven scheduling**

```kotlin
private suspend fun PlayerRuntimeController.pollAddonSubtitleOverlayCues(
    player: ExoPlayer,
    generation: Long,
    cueGroups: List<TimedAddonCueGroup>
) {
    var lastCues: List<Cue> = emptyList()
    while (
        currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive != false &&
        isAddonSubtitleOverlayGenerationActive(generation, player)
    ) {
        val positionMs = delayedAddonSubtitleOverlayPositionMs(
            player.currentPosition,
            subtitleDelayUs.get()
        )
        val activeCues = activeAddonOverlayCuesAt(
            cueGroups = cueGroups,
            positionMs = positionMs
        )
        if (activeCues != lastCues) {
            lastCues = activeCues
            _uiState.update { it.copy(addonOverlayCues = activeCues) }
        }
        delay(nextAddonOverlayUpdateDelayMs(cueGroups, positionMs))
    }
}
```

- [ ] **Step 5: Route progress-sensitive controls through the dedicated progress state**

```kotlin
@Composable
private fun PlayerProgressBoundControls(
    viewModel: PlayerViewModel,
    uiState: PlayerUiState,
    progressBarFocusRequester: FocusRequester,
    playPauseFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val progressUiState by viewModel.progressUiState.collectAsState()

    PlayerControlsOverlay(
        uiState = uiState.copy(),
        currentPosition = progressUiState.displayPosition,
        duration = progressUiState.duration,
        viewModel = viewModel,
        playPauseFocusRequester = playPauseFocusRequester,
        progressBarFocusRequester = progressBarFocusRequester,
        modifier = modifier
    )
}
```

- [ ] **Step 6: Re-run the focused overlay and progress-related tests**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerRuntimeControllerAddonSubtitleOverlayTest" --tests "com.nexio.tv.ui.screens.player.PlayerPlaybackProgressUiStateTest" --tests "com.nexio.tv.ui.screens.player.PlayerVideoSurfaceStateTest"`

Expected: PASS

- [ ] **Step 7: Run the final focused verification suite**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerPlaybackProgressUiStateTest" --tests "com.nexio.tv.ui.screens.player.PlayerVideoSurfaceStateTest" --tests "com.nexio.tv.ui.screens.player.PlayerRuntimeControllerAddonSubtitleOverlayTest" --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryDirectPathRegressionTest"`

Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAddonSubtitleOverlay.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAddonSubtitleOverlayTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerPlaybackProgressUiStateTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerVideoSurfaceStateTest.kt
git commit -m "fix: reduce steady playback render pressure"
```

---

## Acceptance Criteria

- The main `PlayerUiState` no longer changes every 500–1000 ms for playback progress.
- `PlayerView` binding is isolated from unrelated `PlayerScreen` recompositions.
- `AndroidView.update` no longer reapplies resize mode, subtitle style, and overlay cues when those inputs are unchanged.
- Addon subtitle overlay updates are driven by cue-boundary timing rather than a fixed `150ms` poll loop.
- DV5, Dolby Vision conversion, and custom audio sink paths remain untouched.
