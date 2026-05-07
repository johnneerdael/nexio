# MPV Secondary Player Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add libmpv as a selectable secondary internal player engine in Nexio while keeping ExoPlayer as the default.

**Architecture:** Treat the MPV implementation in `~/Scripts/NuvioTV` as source material, not as a direct cherry-pick. Nexio already has a cleaner backend shim in `PlayerRuntimeControllerBackend.kt`, a newer playback pipeline, built-in AI subtitle overlays, custom Media3 audio work, and no Nuvio torrent/plugin/audio-amplification/AspectMode assumptions, so the MPV port should plug into Nexio's existing backend functions and skip Nuvio-only features.

**Tech Stack:** Android Kotlin, Jetpack Compose for TV, Media3/ExoPlayer, `io.github.abdallahmehiz:mpv-android-lib:0.1.12`, DataStore Preferences, Hilt, coroutines, Robolectric unit tests.

---

## Source Map

Use these NuvioTV files as reference material:

- `~/Scripts/NuvioTV/app/build.gradle.kts`
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/local/PlayerSettingsDataStore.kt`
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/player/NuvioMpvSurfaceView.kt`
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerMpv.kt`
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerTrackSelection.kt`
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt`
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerScreen.kt`
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/settings/PlaybackAudioSettings.kt`
- `~/Scripts/NuvioTV/app/src/main/res/values/strings.xml`

Do not port these NuvioTV assumptions into Nexio:

- `AspectMode`; Nexio currently uses `resizeMode`.
- `audioAmplificationDb` and `applyAudioAmplificationDb`; Nexio does not have that setting.
- Torrent, plugin manager, parental guide, profile-specific settings, and Nuvio package names.
- The Nuvio enum typo `MVP_PLAYER`; use `InternalPlayerEngine.LIBMPV` in Nexio and parse old stored values defensively.

## File Structure

- Modify `app/build.gradle.kts`
  - Add the MPV Android library dependency next to the player/media dependencies.
- Modify `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
  - Add `InternalPlayerEngine`, `MpvHardwareDecodeMode`, keys, parsing, settings fields, and setters.
- Modify `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt`
  - Test MPV engine and hardware-decode settings persistence plus legacy value parsing.
- Create `app/src/main/java/com/nexio/tv/ui/screens/player/NexioMpvSurfaceView.kt`
  - Wrap `BaseMPVView` and expose the small backend operations the controller needs.
- Create `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMpv.kt`
  - Initialize MPV, attach/detach the view, map MPV tracks into Nexio `TrackInfo`, and apply pending resume seeks.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
  - Add MPV state fields.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerBackend.kt`
  - Route backend operations to ExoPlayer or MPV.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - Choose ExoPlayer or libmpv during startup.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
  - Keep MPV settings and language preferences live.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerLifecycle.kt`
  - Release MPV and ExoPlayer resources cleanly.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTrackSelection.kt`
  - Route audio/subtitle/addon-subtitle selection to MPV when active.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt`
  - Allow auto-selection to run once MPV track metadata is available.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerUiState.kt`
  - Track the active internal engine and transient engine-switch indicator state.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt`
  - Expose `attachMpvView`.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt`
  - Render `NexioMpvSurfaceView` when libmpv is active.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
  - Add setters for internal engine, startup failover, and MPV hardware decoding.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
  - Track the new settings dialogs.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
  - Add the internal-engine row and dialog.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAudioSettings.kt`
  - Add the MPV hardware decoding row and dialog.
- Modify `app/src/main/res/values/strings.xml`
  - Add Nexio-specific settings, OSD, and accessibility strings.

## Task 1: Persist Internal Engine And MPV Hardware Settings

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt`

- [ ] **Step 1: Write the failing persistence tests**

Add these tests to `PlayerSettingsDataStoreTest`:

```kotlin
@Test
fun `internal player engine defaults to exoplayer and persists libmpv selection`() = runTest {
    val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

    assertEquals(InternalPlayerEngine.EXOPLAYER, dataStore.playerSettings.first().internalPlayerEngine)

    dataStore.setInternalPlayerEngine(InternalPlayerEngine.LIBMPV)

    assertEquals(InternalPlayerEngine.LIBMPV, dataStore.playerSettings.first().internalPlayerEngine)
}

@Test
fun `auto switch internal player setting persists`() = runTest {
    val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

    assertEquals(false, dataStore.playerSettings.first().autoSwitchInternalPlayerOnError)

    dataStore.setAutoSwitchInternalPlayerOnError(true)

    assertEquals(true, dataStore.playerSettings.first().autoSwitchInternalPlayerOnError)
}

@Test
fun `mpv hardware decode mode defaults to auto safe and persists direct selection`() = runTest {
    val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

    assertEquals(MpvHardwareDecodeMode.AUTO_SAFE, dataStore.playerSettings.first().mpvHardwareDecodeMode)

    dataStore.setMpvHardwareDecodeMode(MpvHardwareDecodeMode.HARDWARE_DIRECT)

    assertEquals(MpvHardwareDecodeMode.HARDWARE_DIRECT, dataStore.playerSettings.first().mpvHardwareDecodeMode)
}
```

- [ ] **Step 2: Run the failing tests**

Run: `./gradlew :app:testArm64DebugUnitTest --tests 'com.nexio.tv.data.local.PlayerSettingsDataStoreTest'`

Expected: FAIL with unresolved references for `InternalPlayerEngine`, `MpvHardwareDecodeMode`, `setInternalPlayerEngine`, `setAutoSwitchInternalPlayerOnError`, and `setMpvHardwareDecodeMode`.

- [ ] **Step 3: Add settings models, keys, parsers, and setters**

Add this model surface near `PlayerPreference` in `PlayerSettingsDataStore.kt`:

```kotlin
enum class InternalPlayerEngine {
    EXOPLAYER,
    LIBMPV
}

enum class MpvHardwareDecodeMode {
    LEGACY_DIRECT_COPY,
    AUTO_SAFE,
    HARDWARE_COPY,
    HARDWARE_DIRECT,
    DISABLED
}
```

Add these fields to `PlayerSettings` immediately after `playerPreference`:

```kotlin
val internalPlayerEngine: InternalPlayerEngine = InternalPlayerEngine.EXOPLAYER,
val autoSwitchInternalPlayerOnError: Boolean = false,
val mpvHardwareDecodeMode: MpvHardwareDecodeMode = MpvHardwareDecodeMode.AUTO_SAFE,
```

Add these keys next to `playerPreferenceKey`:

```kotlin
private val internalPlayerEngineKey = stringPreferencesKey("internal_player_engine")
private val autoSwitchInternalPlayerOnErrorKey =
    booleanPreferencesKey("auto_switch_internal_player_on_error")
private val mpvHardwareDecodeModeKey = stringPreferencesKey("mpv_hardware_decode_mode")
```

Add these values to the `PlayerSettings(...)` mapping:

```kotlin
internalPlayerEngine = parseInternalPlayerEngine(prefs[internalPlayerEngineKey]),
autoSwitchInternalPlayerOnError = prefs[autoSwitchInternalPlayerOnErrorKey] ?: false,
mpvHardwareDecodeMode = parseMpvHardwareDecodeMode(prefs[mpvHardwareDecodeModeKey]),
```

Add these helper functions near the other private parsers:

```kotlin
private fun parseInternalPlayerEngine(value: String?): InternalPlayerEngine {
    return when (value) {
        "LIBMPV", "MVP_PLAYER", "MPV_PLAYER" -> InternalPlayerEngine.LIBMPV
        "EXOPLAYER" -> InternalPlayerEngine.EXOPLAYER
        else -> InternalPlayerEngine.EXOPLAYER
    }
}

private fun parseMpvHardwareDecodeMode(value: String?): MpvHardwareDecodeMode {
    return when (value) {
        null, "AUTO_SAFE" -> MpvHardwareDecodeMode.AUTO_SAFE
        "HARDWARE_COPY" -> MpvHardwareDecodeMode.HARDWARE_COPY
        "HARDWARE_DIRECT" -> MpvHardwareDecodeMode.HARDWARE_DIRECT
        "DISABLED" -> MpvHardwareDecodeMode.DISABLED
        "LEGACY_DIRECT_COPY" -> MpvHardwareDecodeMode.LEGACY_DIRECT_COPY
        else -> MpvHardwareDecodeMode.AUTO_SAFE
    }
}
```

Add these setters next to `setPlayerPreference`:

```kotlin
suspend fun setInternalPlayerEngine(engine: InternalPlayerEngine) {
    store().edit { prefs ->
        prefs[internalPlayerEngineKey] = engine.name
    }
}

suspend fun setAutoSwitchInternalPlayerOnError(enabled: Boolean) {
    store().edit { prefs ->
        prefs[autoSwitchInternalPlayerOnErrorKey] = enabled
    }
}

suspend fun setMpvHardwareDecodeMode(mode: MpvHardwareDecodeMode) {
    store().edit { prefs ->
        prefs[mpvHardwareDecodeModeKey] = mode.name
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:testArm64DebugUnitTest --tests 'com.nexio.tv.data.local.PlayerSettingsDataStoreTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt
git commit -m "feat: persist secondary player engine settings"
```

## Task 2: Add MPV Dependency And Surface Wrapper

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/NexioMpvSurfaceView.kt`

- [ ] **Step 1: Add the MPV dependency**

Add this dependency below the existing `nextlib-mediainfo` dependency in `app/build.gradle.kts`:

```kotlin
implementation("io.github.abdallahmehiz:mpv-android-lib:0.1.12")
```

- [ ] **Step 2: Create the surface wrapper from the NuvioTV reference**

Start from the NuvioTV file and apply package/class adaptations:

```bash
cp ~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/player/NuvioMpvSurfaceView.kt app/src/main/java/com/nexio/tv/ui/screens/player/NexioMpvSurfaceView.kt
perl -0pi -e 's/package com\\.nuvio\\.tv\\.ui\\.screens\\.player/package com.nexio.tv.ui.screens.player/; s/com\\.nuvio\\.tv\\.data\\.local/com.nexio.tv.data.local/g; s/NuvioMpvSurfaceView/NexioMpvSurfaceView/g; s/TAG = "NuvioMpvSurfaceView"/TAG = "NexioMpvSurfaceView"/' app/src/main/java/com/nexio/tv/ui/screens/player/NexioMpvSurfaceView.kt
perl -0pi -e 's/MpvHardwareDecodeMode\\.MVP_PLAYER/MpvHardwareDecodeMode.LIBMPV/g; s/InternalPlayerEngine\\.MVP_PLAYER/InternalPlayerEngine.LIBMPV/g' app/src/main/java/com/nexio/tv/ui/screens/player/NexioMpvSurfaceView.kt
```

Then edit `NexioMpvSurfaceView.kt` with these exact adaptations:

```kotlin
// Delete applyAudioAmplificationDb entirely. Nexio has no audio amplification setting.

// Replace applyAspectMode(mode: AspectMode) with this resizeMode adapter.
fun applyResizeMode(resizeMode: Int) {
    when (resizeMode) {
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> {
            scaleX = 1.0f
            scaleY = 1.0f
        }
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> applyCoverAspectScale()
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> {
            scaleX = 1.3333f
            scaleY = 1.0f
        }
        else -> {
            scaleX = 1.0f
            scaleY = 1.0f
        }
    }
}
```

Keep these methods from NuvioTV because Nexio needs them:

```kotlin
ensureInitialized()
setMedia(url, headers)
setPaused(paused)
isPlayingNow()
isPausedForCacheNow()
isCoreIdleNow()
seekToMs(positionMs)
currentPositionMs()
durationMs()
hasVideoTrackSelectedNow()
setPlaybackSpeed(speed)
applyAudioLanguagePreferences(languages)
applyHardwareDecodeMode(mode)
setSubtitleDelayMs(delayMs)
applySubtitleStyle(style)
selectAudioTrackById(trackId)
selectSubtitleTrackById(trackId)
disableSubtitles()
addAndSelectExternalSubtitle(url, title, language)
applySubtitleLanguagePreferences(preferred, secondary)
readTrackSnapshot()
releasePlayer()
```

- [ ] **Step 3: Compile and fix only surface-wrapper errors**

Run: `./gradlew :app:compileArm64DebugKotlin`

Expected before the wrapper edits are complete: FAIL with unresolved `AspectMode` or `applyAudioAmplificationDb` references.

Expected after the wrapper edits are complete: either PASS or fail later because controller code has not yet introduced MPV fields. If the only remaining errors are missing controller MPV fields, continue to Task 3.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/nexio/tv/ui/screens/player/NexioMpvSurfaceView.kt
git commit -m "feat: add libmpv surface wrapper"
```

## Task 3: Add MPV Runtime State And Backend Routing

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerBackend.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMpv.kt`

- [ ] **Step 1: Add controller fields**

Add imports in `PlayerRuntimeController.kt`:

```kotlin
import com.nexio.tv.data.local.InternalPlayerEngine
import com.nexio.tv.data.local.MpvHardwareDecodeMode
```

Add these fields near the ExoPlayer fields:

```kotlin
internal var currentInternalPlayerEngine: InternalPlayerEngine = InternalPlayerEngine.EXOPLAYER
internal var autoSwitchInternalPlayerOnErrorEnabled: Boolean = false
internal var mpvHardwareDecodeModeSetting: MpvHardwareDecodeMode = MpvHardwareDecodeMode.AUTO_SAFE
internal var mpvPreferredAudioLanguages: List<String> = emptyList()
internal var mpvView: NexioMpvSurfaceView? = null
internal var mpvInitializationInProgress: Boolean = false
internal var mpvTrackRefreshInProgress: Boolean = false
internal var delayMpvResumeSeekUntilVideoTrack: Boolean = false
```

- [ ] **Step 2: Create the MPV controller adapter**

Create `PlayerRuntimeControllerMpv.kt` by adapting NuvioTV's `PlayerRuntimeControllerMpv.kt`. Use `NexioMpvSurfaceView`, `InternalPlayerEngine.LIBMPV`, and omit Nuvio-only engine-switch track-preference code. The Nexio file must include these functions:

```kotlin
internal fun PlayerRuntimeController.isUsingMpvEngine(): Boolean {
    return currentInternalPlayerEngine == InternalPlayerEngine.LIBMPV
}

internal fun PlayerRuntimeController.attachMpvView(view: NexioMpvSurfaceView?) {
    if (mpvView === view) return
    mpvView = view
    if (view == null) return
    if (!isUsingMpvEngine()) return
    if (currentStreamUrl.isBlank()) return
    if (mpvInitializationInProgress) return
    initializeMpvPlayer(currentStreamUrl, currentHeaders)
}

internal fun PlayerRuntimeController.initializeMpvPlayer(
    url: String,
    headers: Map<String, String>
) {
    _exoPlayer?.release()
    _exoPlayer = null
    trackSelector = null
    runCatching { currentMediaSession?.release() }
    currentMediaSession = null

    val view = mpvView
    if (view == null) {
        _uiState.update {
            it.copy(isBuffering = true, isPlaying = false, showLoadingOverlay = it.loadingOverlayEnabled, error = null)
        }
        return
    }

    runCatching {
        view.applyHardwareDecodeMode(mpvHardwareDecodeModeSetting)
        view.setMedia(url, headers)
        view.setPlaybackSpeed(_uiState.value.playbackSpeed)
        view.applyAudioLanguagePreferences(mpvPreferredAudioLanguages)
        view.applySubtitleLanguagePreferences(
            preferred = _uiState.value.subtitleStyle.preferredLanguage,
            secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage
        )
        view.applySubtitleStyle(_uiState.value.subtitleStyle)
        view.applyResizeMode(_uiState.value.resizeMode)
        view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        view.setPaused(false)
        applyPendingMpvSeekIfNeeded(view)
        hasRenderedFirstFrame = false
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = view.isPlayingNow(),
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                selectedAudioTrackIndex = -1,
                selectedSubtitleTrackIndex = -1
            )
        }
        cancelPauseOverlay()
        startProgressUpdates()
        startWatchProgressSaving()
        updateMpvAvailableTracks()
        tryAutoSelectPreferredSubtitleFromAvailableTracks()
        scheduleHideControls()
        emitScrobbleStart()
    }.onFailure { error ->
        _uiState.update {
            it.copy(error = error.message ?: "Failed to initialize libmpv playback", showLoadingOverlay = false, isBuffering = false)
        }
    }
}
```

Also adapt NuvioTV's `updateMpvAvailableTracks`, `applyPendingMpvSeekIfNeeded`, and `keepMpvPlayingIfNeeded`. In `updateMpvAvailableTracks`, call `maybeApplyRememberedAudioSelection(audioTracks)` after updating state, because Nexio uses remembered audio navigation args instead of Nuvio's engine-switch preference model.

- [ ] **Step 3: Route backend functions**

Modify `PlayerRuntimeControllerBackend.kt` so each backend method checks `isUsingMpvEngine()`. For example:

```kotlin
internal fun PlayerRuntimeController.backendCurrentPosition(): Long {
    return if (isUsingMpvEngine()) mpvView?.currentPositionMs() ?: 0L else _exoPlayer?.currentPosition ?: 0L
}

internal fun PlayerRuntimeController.backendDuration(): Long {
    return if (isUsingMpvEngine()) mpvView?.durationMs() ?: 0L else _exoPlayer?.duration ?: 0L
}

internal fun PlayerRuntimeController.backendIsReady(): Boolean {
    return if (isUsingMpvEngine()) mpvView != null && !_uiState.value.isBuffering else _exoPlayer?.playbackState == Player.STATE_READY
}

internal fun PlayerRuntimeController.backendIsPlaying(): Boolean {
    return if (isUsingMpvEngine()) mpvView?.isPlayingNow() == true else _exoPlayer?.isPlaying == true
}

internal fun PlayerRuntimeController.backendPause() {
    if (isUsingMpvEngine()) mpvView?.setPaused(true) else _exoPlayer?.pause()
}

internal fun PlayerRuntimeController.backendPlay() {
    if (isUsingMpvEngine()) mpvView?.setPaused(false) else _exoPlayer?.play()
}

internal fun PlayerRuntimeController.backendSeekTo(positionMs: Long) {
    if (isUsingMpvEngine()) mpvView?.seekToMs(positionMs) else _exoPlayer?.seekTo(positionMs)
}
```

Update `pausePlaybackForLifecycle`, `resumePlaybackForLifecycle`, `isPlaybackCurrentlyPlaying`, `seekPlaybackTo`, `setPlaybackSpeedInternal`, `setPlaybackPaused`, and `tryApplyPendingResumeProgressForCurrentBackend` with the same pattern.

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileArm64DebugKotlin`

Expected: FAIL only in startup, UI, or track-selection call sites that still assume `_exoPlayer` exists for every backend. Continue to the next tasks to remove those assumptions.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerBackend.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMpv.kt
git commit -m "feat: route playback backend through libmpv"
```

## Task 4: Select MPV During Player Startup And Settings Observation

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerLifecycle.kt`

- [ ] **Step 1: Route startup to MPV**

In `initializePlayer`, after `val playerSettings = playerSettingsDataStore.playerSettings.first()` and before ExoPlayer-specific setup, add:

```kotlin
currentInternalPlayerEngine = playerSettings.internalPlayerEngine
autoSwitchInternalPlayerOnErrorEnabled = playerSettings.autoSwitchInternalPlayerOnError
mpvHardwareDecodeModeSetting = playerSettings.mpvHardwareDecodeMode
mpvPreferredAudioLanguages = resolvePreferredAudioLanguages(
    preferredAudioLanguage = playerSettings.preferredAudioLanguage,
    secondaryPreferredAudioLanguage = playerSettings.secondaryPreferredAudioLanguage,
    deviceLanguages = resolveDeviceAudioLanguages(),
    originalLanguage = originalLanguage
)
_uiState.update {
    it.copy(
        internalPlayerEngine = currentInternalPlayerEngine,
        frameRateMatchingMode = playerSettings.frameRateMatchingMode
    )
}

if (currentInternalPlayerEngine == InternalPlayerEngine.LIBMPV) {
    mpvInitializationInProgress = true
    try {
        initializeMpvPlayer(url, headers)
        fetchAddonSubtitles()
    } finally {
        mpvInitializationInProgress = false
    }
    return@launch
}
```

- [ ] **Step 2: Observe MPV setting changes**

In `observePlayerSettings`, update state and MPV view:

```kotlin
val previousMpvHardwareDecodeMode = mpvHardwareDecodeModeSetting
currentInternalPlayerEngine = settings.internalPlayerEngine
autoSwitchInternalPlayerOnErrorEnabled = settings.autoSwitchInternalPlayerOnError
mpvHardwareDecodeModeSetting = settings.mpvHardwareDecodeMode
_uiState.update { it.copy(internalPlayerEngine = settings.internalPlayerEngine) }
if (isUsingMpvEngine() && previousMpvHardwareDecodeMode != mpvHardwareDecodeModeSetting) {
    mpvView?.applyHardwareDecodeMode(mpvHardwareDecodeModeSetting)
}

val resolvedAudioLanguages = resolvePreferredAudioLanguages(
    preferredAudioLanguage = settings.preferredAudioLanguage,
    secondaryPreferredAudioLanguage = settings.secondaryPreferredAudioLanguage,
    deviceLanguages = resolveDeviceAudioLanguages(),
    originalLanguage = originalLanguage
)
if (resolvedAudioLanguages != mpvPreferredAudioLanguages) {
    mpvPreferredAudioLanguages = resolvedAudioLanguages
    if (isUsingMpvEngine()) {
        mpvView?.applyAudioLanguagePreferences(resolvedAudioLanguages)
        updateMpvAvailableTracks()
    }
}
```

- [ ] **Step 3: Release MPV during lifecycle cleanup**

In `releasePlayer`, before or after releasing ExoPlayer, add:

```kotlin
runCatching { mpvView?.releasePlayer() }
mpvView = null
mpvInitializationInProgress = false
mpvTrackRefreshInProgress = false
```

Do not call `mpvView = null` if the call site is a transient Exo-only release that immediately keeps the Compose AndroidView alive. If that distinction is needed, split a helper:

```kotlin
internal fun PlayerRuntimeController.releaseMpvPlayer(clearViewReference: Boolean) {
    runCatching { mpvView?.releasePlayer() }
    if (clearViewReference) mpvView = null
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileArm64DebugKotlin`

Expected: compile errors should now be limited to `PlayerUiState`, `PlayerScreen`, or settings UI fields that do not exist yet.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerLifecycle.kt
git commit -m "feat: initialize playback with selected internal engine"
```

## Task 5: Render The MPV Surface

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerUiState.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt`

- [ ] **Step 1: Add player UI state fields and event**

Add an import to `PlayerUiState.kt`:

```kotlin
import com.nexio.tv.data.local.InternalPlayerEngine
```

Add these fields near the stream source indicator fields:

```kotlin
val showPlayerEngineSwitchInfo: Boolean = false,
val playerEngineSwitchInfoText: String = "",
val internalPlayerEngine: InternalPlayerEngine = InternalPlayerEngine.EXOPLAYER,
```

Add this event near the other player events:

```kotlin
data object OnSwitchInternalPlayerEngine : PlayerEvent()
```

- [ ] **Step 2: Expose MPV view attachment**

Add to `PlayerViewModel.kt`:

```kotlin
fun attachMpvView(view: NexioMpvSurfaceView?) {
    controller.attachMpvView(view)
}
```

- [ ] **Step 3: Branch the player surface**

In `PlayerScreen.kt`, replace the unconditional ExoPlayer surface block with:

```kotlin
if (uiState.internalPlayerEngine == InternalPlayerEngine.LIBMPV) {
    AndroidView(
        factory = { context ->
            NexioMpvSurfaceView(context).also { view ->
                viewModel.attachMpvView(view)
            }
        },
        update = { view ->
            viewModel.attachMpvView(view)
            view.keepScreenOn = uiState.isPlaying || uiState.isBuffering
            view.applyResizeMode(uiState.resizeMode)
            view.applySubtitleStyle(uiState.subtitleStyle)
        },
        modifier = Modifier.fillMaxSize()
    )
    DisposableEffect(Unit) {
        onDispose {
            viewModel.attachMpvView(null)
        }
    }
} else {
    viewModel.exoPlayer?.let { player ->
        PlayerVideoSurface(
            player = player,
            renderState = PlayerSurfaceRenderState(
                resizeMode = uiState.resizeMode,
                subtitleStyle = uiState.subtitleStyle,
                keepScreenOn = uiState.isPlaying || uiState.isBuffering,
                overlayCues = resolveOverlayCues(
                    useAiOverlay = uiState.useBuiltInAiSubtitleOverlay,
                    translatedBuiltInCues = uiState.translatedBuiltInCues,
                    addonOverlayCues = uiState.addonOverlayCues
                ),
                suppressNativeSubtitles = uiState.useBuiltInAiSubtitleOverlay
            ),
            modifier = Modifier.fillMaxSize()
        )
    }
}
```

Add imports if missing:

```kotlin
import androidx.compose.ui.viewinterop.AndroidView
import com.nexio.tv.data.local.InternalPlayerEngine
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileArm64DebugKotlin`

Expected: PASS for surface routing, or fail only on track-selection/settings code that later tasks handle.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerUiState.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt
git commit -m "feat: render libmpv player surface"
```

## Task 6: Route Audio, Internal Subtitle, And Addon Subtitle Selection

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTrackSelection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`

- [ ] **Step 1: Add MPV branches to track selection**

At the start of `selectAudioTrack`, add:

```kotlin
if (isUsingMpvEngine()) {
    val track = _uiState.value.audioTracks.getOrNull(trackIndex) ?: return
    val trackId = track.trackId?.toIntOrNull() ?: return
    if (mpvView?.selectAudioTrackById(trackId) == true) {
        _uiState.update { state ->
            state.copy(
                selectedAudioTrackIndex = trackIndex,
                audioTracks = state.audioTracks.mapIndexed { index, item ->
                    item.copy(isSelected = index == trackIndex)
                }
            )
        }
        persistRememberedLinkAudioSelection(trackIndex)
    }
    return
}
```

At the start of `selectSubtitleTrack`, add:

```kotlin
if (isUsingMpvEngine()) {
    val track = _uiState.value.subtitleTracks.getOrNull(trackIndex) ?: return
    val trackId = track.trackId?.toIntOrNull() ?: return
    if (mpvView?.selectSubtitleTrackById(trackId) == true) {
        _uiState.update { state ->
            state.copy(
                selectedSubtitleTrackIndex = trackIndex,
                selectedAddonSubtitle = null,
                subtitleTracks = state.subtitleTracks.mapIndexed { index, item ->
                    item.copy(isSelected = index == trackIndex)
                }
            )
        }
    }
    return
}
```

At the start of `disableSubtitles`, add:

```kotlin
if (isUsingMpvEngine()) {
    if (mpvView?.disableSubtitles() == true) {
        _uiState.update {
            it.copy(selectedSubtitleTrackIndex = -1, selectedAddonSubtitle = null)
        }
    }
    return
}
```

At the start of `selectAddonSubtitle`, add:

```kotlin
if (isUsingMpvEngine()) {
    if (mpvView?.addAndSelectExternalSubtitle(subtitle.url, buildAddonSubtitleTrackId(subtitle), subtitle.lang) == true) {
        _uiState.update {
            it.copy(
                selectedAddonSubtitle = selectedSubtitle,
                selectedSubtitleTrackIndex = -1,
                addonOverlayCues = emptyList()
            )
        }
        updateMpvAvailableTracks()
    }
    return
}
```

- [ ] **Step 2: Keep MPV subtitle preferences live**

In `applySubtitlePreferences`, add an MPV branch before ExoPlayer-specific text track changes:

```kotlin
if (isUsingMpvEngine()) {
    mpvView?.applySubtitleLanguagePreferences(preferred, secondary)
    updateMpvAvailableTracks()
    return
}
```

- [ ] **Step 3: Refresh MPV tracks during progress**

In `startProgressUpdates`, after reading MPV position/duration through `backendCurrentPosition()` and `backendDuration()`, add a guarded refresh:

```kotlin
if (isUsingMpvEngine()) {
    mpvView?.let { view ->
        applyPendingMpvSeekIfNeeded(view)
        updateMpvAvailableTracks()
    }
}
```

- [ ] **Step 4: Compile and run player tests**

Run: `./gradlew :app:testArm64DebugUnitTest --tests 'com.nexio.tv.ui.screens.player.*'`

Expected: PASS. If tests fail because existing test fakes assume ExoPlayer-only backends, update those fakes to set `currentInternalPlayerEngine = InternalPlayerEngine.EXOPLAYER` explicitly.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTrackSelection.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt
git commit -m "feat: support track selection on libmpv backend"
```

## Task 7: Add Playback Settings UI

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAudioSettings.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add settings view-model setters**

Add imports:

```kotlin
import com.nexio.tv.data.local.InternalPlayerEngine
import com.nexio.tv.data.local.MpvHardwareDecodeMode
```

Add methods:

```kotlin
suspend fun setInternalPlayerEngine(engine: InternalPlayerEngine) {
    playerSettingsDataStore.setInternalPlayerEngine(engine)
}

suspend fun setAutoSwitchInternalPlayerOnError(enabled: Boolean) {
    playerSettingsDataStore.setAutoSwitchInternalPlayerOnError(enabled)
}

suspend fun setMpvHardwareDecodeMode(mode: MpvHardwareDecodeMode) {
    playerSettingsDataStore.setMpvHardwareDecodeMode(mode)
}
```

- [ ] **Step 2: Add strings**

Add these strings to `values/strings.xml`:

```xml
<string name="playback_internal_player_engine">Internal Engine</string>
<string name="playback_engine_exoplayer">ExoPlayer</string>
<string name="playback_engine_libmpv">Libmpv (Beta)</string>
<string name="playback_engine_exoplayer_desc">Best compatibility with current Nexio playback features.</string>
<string name="playback_engine_libmpv_desc">Uses libmpv with Nexio controls. Experimental.</string>
<string name="playback_auto_switch_internal_player_on_error">Auto-switch engine on startup error</string>
<string name="playback_auto_switch_internal_player_on_error_sub">If stream startup fails, switch between ExoPlayer and libmpv automatically.</string>
<string name="audio_mpv_hwdec_title">Hardware Decoding (MPV-only)</string>
<string name="audio_mpv_hwdec_dialog_subtitle">Select how libmpv should use hardware decoders.</string>
<string name="audio_mpv_hwdec_legacy_direct_copy">Legacy (direct -> copy)</string>
<string name="audio_mpv_hwdec_legacy_direct_copy_desc">Try direct hardware first, then copy-back.</string>
<string name="audio_mpv_hwdec_auto_safe">Auto (auto-safe)</string>
<string name="audio_mpv_hwdec_auto_safe_desc">Use libmpv's safer automatic hardware-decoding mode.</string>
<string name="audio_mpv_hwdec_hardware_copy">Hardware copy (mediacodec-copy)</string>
<string name="audio_mpv_hwdec_hardware_copy_desc">Use hardware decoding with copy-back to software memory.</string>
<string name="audio_mpv_hwdec_hardware_direct">Hardware direct (mediacodec)</string>
<string name="audio_mpv_hwdec_hardware_direct_desc">Use direct hardware decoding on compatible devices.</string>
<string name="audio_mpv_hwdec_disabled">Disabled (no)</string>
<string name="audio_mpv_hwdec_disabled_desc">Disable hardware decoding and use software decoding only.</string>
<string name="player_engine_switching_title">Switching Player Engine</string>
<string name="player_engine_switching_message">Startup failed. Switching to %1$s...</string>
<string name="player_engine_switching_manual_message">Switching to %1$s...</string>
<string name="cd_switch_player_engine">Switch player engine</string>
```

- [ ] **Step 3: Add internal-engine settings row and dialog**

In `PlaybackSettingsSections.kt`, add `InternalPlayerEngine` imports and add a row below the existing player preference row:

```kotlin
NavigationSettingsItem(
    icon = Icons.Default.SmartDisplay,
    title = stringResource(R.string.playback_internal_player_engine),
    subtitle = when (playerSettings.internalPlayerEngine) {
        InternalPlayerEngine.EXOPLAYER -> stringResource(R.string.playback_engine_exoplayer)
        InternalPlayerEngine.LIBMPV -> stringResource(R.string.playback_engine_libmpv)
    },
    onClick = onShowInternalPlayerEngineDialog,
    onFocused = onItemFocused,
    enabled = playerSettings.playerPreference != PlayerPreference.EXTERNAL
)
```

Add a toggle row:

```kotlin
ToggleSettingsItem(
    icon = Icons.Default.SwapHoriz,
    title = stringResource(R.string.playback_auto_switch_internal_player_on_error),
    subtitle = stringResource(R.string.playback_auto_switch_internal_player_on_error_sub),
    isChecked = playerSettings.autoSwitchInternalPlayerOnError,
    onCheckedChange = onSetAutoSwitchInternalPlayerOnError,
    onFocused = onItemFocused,
    enabled = playerSettings.playerPreference != PlayerPreference.EXTERNAL
)
```

Add `InternalPlayerEngineDialog` modeled on `PlayerPreferenceDialog`, with options:

```kotlin
Triple(InternalPlayerEngine.EXOPLAYER, stringResource(R.string.playback_engine_exoplayer), stringResource(R.string.playback_engine_exoplayer_desc))
Triple(InternalPlayerEngine.LIBMPV, stringResource(R.string.playback_engine_libmpv), stringResource(R.string.playback_engine_libmpv_desc))
```

- [ ] **Step 4: Add MPV hardware-decode row and dialog**

In `PlaybackAudioSettings.kt`, add a row after decoder priority:

```kotlin
NavigationSettingsItem(
    icon = Icons.Default.Tune,
    title = stringResource(R.string.audio_mpv_hwdec_title),
    subtitle = when (playerSettings.mpvHardwareDecodeMode) {
        MpvHardwareDecodeMode.LEGACY_DIRECT_COPY -> stringResource(R.string.audio_mpv_hwdec_legacy_direct_copy)
        MpvHardwareDecodeMode.AUTO_SAFE -> stringResource(R.string.audio_mpv_hwdec_auto_safe)
        MpvHardwareDecodeMode.HARDWARE_COPY -> stringResource(R.string.audio_mpv_hwdec_hardware_copy)
        MpvHardwareDecodeMode.HARDWARE_DIRECT -> stringResource(R.string.audio_mpv_hwdec_hardware_direct)
        MpvHardwareDecodeMode.DISABLED -> stringResource(R.string.audio_mpv_hwdec_disabled)
    },
    onClick = onShowMpvHardwareDecodeModeDialog,
    onFocused = onItemFocused,
    enabled = enabled
)
```

Add `MpvHardwareDecodeModeDialog` using the same dialog component used by decoder priority. The options are the five enum values and the five label/description string pairs from Step 2.

- [ ] **Step 5: Wire dialog state in `PlaybackSettingsScreen.kt`**

Add `remember` state for:

```kotlin
var showInternalPlayerEngineDialog by remember { mutableStateOf(false) }
var showMpvHardwareDecodeModeDialog by remember { mutableStateOf(false) }
```

Thread callbacks through `PlaybackSettingsContent` and `PlaybackSettingsDialogsHost`:

```kotlin
onShowInternalPlayerEngineDialog = { openDialog { showInternalPlayerEngineDialog = true } },
onShowMpvHardwareDecodeModeDialog = { openDialog { showMpvHardwareDecodeModeDialog = true } },
onSetInternalPlayerEngine = { engine -> coroutineScope.launch { viewModel.setInternalPlayerEngine(engine) } },
onSetAutoSwitchInternalPlayerOnError = { enabled -> coroutineScope.launch { viewModel.setAutoSwitchInternalPlayerOnError(enabled) } },
onSetMpvHardwareDecodeMode = { mode -> coroutineScope.launch { viewModel.setMpvHardwareDecodeMode(mode) } },
```

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileArm64DebugKotlin`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAudioSettings.kt app/src/main/res/values/strings.xml
git commit -m "feat: expose libmpv playback settings"
```

## Task 8: Add Manual Engine Switch And Startup Failover

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMpv.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerEngineSwitch.kt`

- [ ] **Step 1: Create engine-switch helper**

Create `PlayerRuntimeControllerEngineSwitch.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import com.nexio.tv.R
import com.nexio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.switchInternalPlayerEngineManually() {
    if (currentStreamUrl.isBlank()) return

    val targetEngine = when (currentInternalPlayerEngine) {
        InternalPlayerEngine.EXOPLAYER -> InternalPlayerEngine.LIBMPV
        InternalPlayerEngine.LIBMPV -> InternalPlayerEngine.EXOPLAYER
    }
    val currentPosition = backendCurrentPosition().coerceAtLeast(0L)
    if (currentPosition > 0L) {
        pendingResumeProgress = null
        _uiState.update { it.copy(pendingSeekPosition = currentPosition) }
    }

    hidePlayerEngineSwitchInfoJob?.cancel()
    currentInternalPlayerEngine = targetEngine
    _uiState.update {
        it.copy(
            error = null,
            showPauseOverlay = false,
            showLoadingOverlay = it.loadingOverlayEnabled,
            showControls = false,
            showAudioDialog = false,
            showSubtitleDialog = false,
            showSubtitleStylePanel = false,
            showSubtitleDelayOverlay = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            internalPlayerEngine = targetEngine,
            showPlayerEngineSwitchInfo = true,
            playerEngineSwitchInfoText = context.getString(
                R.string.player_engine_switching_manual_message,
                targetEngineLabel(targetEngine)
            )
        )
    }

    releasePlayer()
    initializePlayer(currentStreamUrl, currentHeaders)

    hidePlayerEngineSwitchInfoJob = scope.launch {
        delay(2200)
        _uiState.update { state -> state.copy(showPlayerEngineSwitchInfo = false) }
    }
}

internal fun PlayerRuntimeController.targetEngineLabel(targetEngine: InternalPlayerEngine): String {
    return when (targetEngine) {
        InternalPlayerEngine.EXOPLAYER -> context.getString(R.string.playback_engine_exoplayer)
        InternalPlayerEngine.LIBMPV -> context.getString(R.string.playback_engine_libmpv)
    }
}
```

Add this field to `PlayerRuntimeController.kt`:

```kotlin
internal var hidePlayerEngineSwitchInfoJob: Job? = null
```

- [ ] **Step 2: Handle the switch event**

In `PlayerRuntimeControllerPlaybackEvents.kt`, add:

```kotlin
PlayerEvent.OnSwitchInternalPlayerEngine -> {
    switchInternalPlayerEngineManually()
}
```

- [ ] **Step 3: Add a control button**

In `PlayerScreen.kt`, add a switch button in the expanded more-actions row:

```kotlin
ControlButton(
    icon = Icons.Default.SwapHoriz,
    contentDescription = stringResource(R.string.cd_switch_player_engine),
    onClick = { viewModel.onEvent(PlayerEvent.OnSwitchInternalPlayerEngine) },
    upFocusRequester = progressBarFocusRequester,
    onFocused = onResetHideTimer
)
```

Add a center indicator near the existing stream source indicator:

```kotlin
AnimatedVisibility(
    visible = uiState.showPlayerEngineSwitchInfo && uiState.error == null,
    enter = fadeIn(animationSpec = tween(180)),
    exit = fadeOut(animationSpec = tween(180)),
    modifier = Modifier.align(Alignment.Center).zIndex(2.35f)
) {
    LoadingOverlay(
        visible = true,
        backdropUrl = null,
        logoUrl = null,
        title = stringResource(R.string.player_engine_switching_title),
        message = uiState.playerEngineSwitchInfoText,
        modifier = Modifier.fillMaxSize()
    )
}
```

- [ ] **Step 4: Add startup failover call**

In `initializeMpvPlayer` and the ExoPlayer error listener, when startup fails before `hasRenderedFirstFrame`, call a helper:

```kotlin
internal fun PlayerRuntimeController.maybeAutoSwitchInternalPlayerOnStartupError(message: String): Boolean {
    if (!autoSwitchInternalPlayerOnErrorEnabled) return false
    if (hasRenderedFirstFrame) return false
    switchInternalPlayerEngineManually()
    _uiState.update {
        it.copy(playerEngineSwitchInfoText = context.getString(R.string.player_engine_switching_message, targetEngineLabel(currentInternalPlayerEngine)))
    }
    return true
}
```

Use this from MPV initialization failure before setting `error`, and from the ExoPlayer `onPlayerError` branch only when the error occurs during startup.

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileArm64DebugKotlin`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMpv.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerEngineSwitch.kt
git commit -m "feat: switch between internal playback engines"
```

## Task 9: Full Verification

**Files:**
- No source edits unless verification finds a compile or behavior defect.

- [ ] **Step 1: Run focused unit tests**

Run: `./gradlew :app:testArm64DebugUnitTest --tests 'com.nexio.tv.data.local.PlayerSettingsDataStoreTest' --tests 'com.nexio.tv.ui.screens.player.*'`

Expected: PASS.

- [ ] **Step 2: Compile the debug app**

Run: `./gradlew :app:compileArm64DebugKotlin`

Expected: PASS.

- [ ] **Step 3: Build an installable APK**

Run: `./gradlew :app:assembleArm64Debug`

Expected: PASS and APK at `app/build/outputs/apk/arm64/debug/app-arm64-debug.apk`.

- [ ] **Step 4: Manual TV-device verification**

Install and verify on an Android TV device or emulator:

```bash
adb install -r app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
```

Manual checks:

- Playback settings default to ExoPlayer.
- Switching Internal Engine to Libmpv starts the same debrid HTTP stream.
- D-pad play/pause, seek forward/back, audio track, subtitle track, addon subtitle, subtitle style, resize/aspect behavior, and back exit work on MPV.
- Switching back to ExoPlayer starts playback from the previous approximate position.
- External player preference still launches external player and does not force MPV.
- MPV hardware decode modes persist and do not crash startup.

- [ ] **Step 5: Commit verification fixes**

If verification required fixes:

```bash
git add app/src/main/java app/src/test/java app/src/main/res/values/strings.xml app/build.gradle.kts
git commit -m "fix: stabilize libmpv secondary playback"
```

If no fixes were required, do not create an empty commit.

## Self-Review

Spec coverage:

- MPV as a secondary player is covered by Tasks 1, 3, 4, 5, and 7.
- NuvioTV MPV integration is used as source material in Tasks 2, 3, 6, 7, and 8.
- Nexio-specific adaptation is covered by the no-port list and backend-routing tasks.

Placeholder scan:

- No forbidden placeholder terms or intentionally blank implementation steps remain.
- Steps that modify code include the exact snippets or exact NuvioTV source files to adapt.

Type consistency:

- Nexio uses `InternalPlayerEngine.LIBMPV`.
- Legacy stored values `MVP_PLAYER`, `MPV_PLAYER`, and `LIBMPV` parse to `LIBMPV`.
- The MPV view class is `NexioMpvSurfaceView`.
