# Media3 1.10.0 App Adoption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adopt the useful Media3 1.10.0 app-side improvements now that the `media` submodule points at the rebased 1.10.0 fork.

**Architecture:** Keep the Media3 fork work separate and make only small Nexio app changes: align the declared Media3 version, centralize Dolby Vision codec profile parsing, document Profile 10 behavior with tests, and add a disabled-by-default dynamic scheduling toggle that wires both required Media3 1.10.0 APIs. Treat metadata renderer count and HLS fallback as verification-only items.

**Tech Stack:** Kotlin, AndroidX Media3 1.10.0, Gradle version catalog, DataStore Preferences, Jetpack Compose for Android TV settings, JUnit4/Robolectric tests.

---

## Current-State Corrections

- `media/constants.gradle` is already `releaseVersion = '1.10.0'`, and `media` local `main` points at `edaa8415c8`.
- `gradle/libs.versions.toml` still declares `media3 = "1.10.0-beta01"` and must be aligned to `1.10.0`.
- The Media3 1.10.0 API in this checkout is `DefaultRenderersFactory.setEnableMediaCodecVideoRendererDurationToProgressUs(...)`, not `experimentalSetEnableMediaCodecVideoRendererDurationToProgressUs(...)`.
- Dynamic scheduling requires two flags to be useful:
  - `DefaultRenderersFactory.setEnableMediaCodecVideoRendererDurationToProgressUs(true)`
  - `ExoPlayer.Builder.experimentalSetDynamicSchedulingEnabled(true)`
- `DolbyVisionAutoPlayGate` already allows every detected Dolby Vision profile except Profile 5. Profile 10 therefore needs tests and telemetry coverage more than a behavior change.
- Current app-side Dolby Vision codec parsing only recognizes `dvhe` and `dvh1`; Media3 1.10.0 recognizes `dvav`, `dva1`, and `dav1` too. Profile 10 uses `dav1.10.*`.

## File Structure

- Create `app/src/main/java/com/nexio/tv/core/player/DolbyVisionCodecStrings.kt`: shared app-side Dolby Vision codec string parser for `dvhe`, `dvh1`, `dvav`, `dva1`, and `dav1`.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt`: use the shared parser instead of its private HEVC-only parser.
- Modify `app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt`: use the shared parser so `dav1.10.*` is identified and skipped as unsupported conversion instead of being treated as unknown.
- Modify `app/src/main/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelector.kt`: add an explicit Profile 10 no-conversion branch for readability and regression protection.
- Modify `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`: add `dynamicVideoSchedulingEnabled`, default `false`, key, read mapping, and setter.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`, `PlaybackSettingsScreen.kt`, `PlaybackSettingsSections.kt`, and `PlaybackAudioSettings.kt`: surface the toggle in Video settings.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`: pass the setting into `SubtitleOffsetRenderersFactory`, set the Media3 renderer duration-to-progress flag, and set ExoPlayer dynamic scheduling on the builder.
- Modify `gradle/libs.versions.toml` and `app/build.gradle.kts`: align test-utils and artifacts to `1.10.0`.
- Add/update tests in `app/src/test/java/com/nexio/tv/core/player/`, `app/src/test/java/com/nexio/tv/data/local/`, and, if practical, a focused source text contract test for the renderer factory wiring.

---

### Task 1: Align Declared Media3 Version

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Write the failing version drift check**

Run:

```bash
rg -n '1\.10\.0-beta01|media3-test-utils:1\.10\.0-beta01' gradle/libs.versions.toml app/build.gradle.kts
```

Expected before implementation: output includes `gradle/libs.versions.toml` and `app/build.gradle.kts`.

- [ ] **Step 2: Update the version catalog**

Change `gradle/libs.versions.toml`:

```toml
media3 = "1.10.0"
```

- [ ] **Step 3: Use the version catalog for Media3 test utils**

Change the `testImplementation` line in `app/build.gradle.kts`:

```kotlin
testImplementation("androidx.media3:media3-test-utils:${libs.versions.media3.get()}")
```

- [ ] **Step 4: Verify no stale beta references remain**

Run:

```bash
rg -n '1\.10\.0-beta01|media3-test-utils:1\.10\.0-beta01' gradle/libs.versions.toml app/build.gradle.kts
```

Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "chore(media3): align app dependencies with 1.10.0"
```

---

### Task 2: Centralize Dolby Vision Codec Profile Parsing

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/player/DolbyVisionCodecStrings.kt`
- Create: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionCodecStringsTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt`

- [ ] **Step 1: Add the parser tests first**

Create `app/src/test/java/com/nexio/tv/core/player/DolbyVisionCodecStringsTest.kt`:

```kotlin
package com.nexio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DolbyVisionCodecStringsTest {

    @Test
    fun `parses hevc dolby vision profiles`() {
        assertEquals(5, resolveDolbyVisionProfileFromCodecString("dvhe.05.06"))
        assertEquals(7, resolveDolbyVisionProfileFromCodecString("dvh1.07.06"))
    }

    @Test
    fun `parses avc and av1 dolby vision profiles`() {
        assertEquals(9, resolveDolbyVisionProfileFromCodecString("dvav.09.01"))
        assertEquals(9, resolveDolbyVisionProfileFromCodecString("dva1.09.01"))
        assertEquals(10, resolveDolbyVisionProfileFromCodecString("dav1.10.09"))
    }

    @Test
    fun `parses supplemental dolby vision codec from comma separated codecs`() {
        assertEquals(10, resolveDolbyVisionProfileFromCodecString("av01.0.13M.10,dav1.10.09"))
    }

    @Test
    fun `ignores non dolby vision codecs and malformed profiles`() {
        assertNull(resolveDolbyVisionProfileFromCodecString("av01.0.13M.10"))
        assertNull(resolveDolbyVisionProfileFromCodecString("hevc"))
        assertNull(resolveDolbyVisionProfileFromCodecString("dav1.profile10.09"))
        assertNull(resolveDolbyVisionProfileFromCodecString(null))
    }
}
```

- [ ] **Step 2: Run the new test and confirm it fails**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.core.player.DolbyVisionCodecStringsTest"
```

Expected: fails because `resolveDolbyVisionProfileFromCodecString` does not exist in `com.nexio.tv.core.player`.

- [ ] **Step 3: Add the shared parser**

Create `app/src/main/java/com/nexio/tv/core/player/DolbyVisionCodecStrings.kt`:

```kotlin
package com.nexio.tv.core.player

import java.util.Locale

private val DOLBY_VISION_CODEC_PREFIXES = setOf("dvhe", "dvh1", "dvav", "dva1", "dav1")

internal fun resolveDolbyVisionProfileFromCodecString(codecs: String?): Int? {
    val entries = codecs
        ?.split(',')
        ?.asSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: return null

    for (entry in entries) {
        val parts = entry.split('.')
        if (parts.size < 2) continue
        val prefix = parts[0].lowercase(Locale.ROOT)
        if (prefix !in DOLBY_VISION_CODEC_PREFIXES) continue
        return parts[1].toIntOrNull()
    }
    return null
}
```

- [ ] **Step 4: Replace the private parser in player track code**

In `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt`, add:

```kotlin
import com.nexio.tv.core.player.resolveDolbyVisionProfileFromCodecString
```

Keep `isDolbyVisionProfile5VideoFormat`:

```kotlin
private fun isDolbyVisionProfile5VideoFormat(codecs: String?): Boolean {
    return resolveDolbyVisionProfileFromCodecString(codecs) == 5
}
```

Delete the private `resolveDolbyVisionProfileFromCodecString` function from this file.

- [ ] **Step 5: Replace the private parser in the extractor hook installer**

In `app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt`, delete the private `resolveDolbyVisionProfileFromCodecString` function. The existing `resolveDolbyVisionProfile(...)` function remains:

```kotlin
private fun resolveDolbyVisionProfile(
    codecs: String? = null,
    configBytes: ByteArray? = null
): Int? {
    resolveDolbyVisionProfileFromCodecString(codecs)?.let { return it }
    if (configBytes == null || configBytes.isEmpty()) return null
    return runCatching {
        DolbyVisionConfig.parse(ParsableByteArray(configBytes))?.profile
    }.getOrNull()
}
```

Because `MatroskaDolbyVisionHookInstaller.kt` is in `com.nexio.tv.core.player`, it can use the new shared internal function without an import.

- [ ] **Step 6: Run the focused parser test**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.core.player.DolbyVisionCodecStringsTest"
```

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/DolbyVisionCodecStrings.kt \
  app/src/test/java/com/nexio/tv/core/player/DolbyVisionCodecStringsTest.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt \
  app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt
git commit -m "feat(player): recognize Media3 Dolby Vision profile 10 codecs"
```

---

### Task 3: Lock Profile 10 Playback Policy With Tests

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelector.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelectorTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionAutoPlayGateTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/player/FfmpegDolbyVisionProfileProbeTest.kt`

- [ ] **Step 1: Add failing conversion selector test**

Append to `DolbyVisionConversionModeSelectorTest`:

```kotlin
@Test
fun `profile 10 selects no conversion mode`() {
    assertNull(
        DolbyVisionConversionModeSelector.selectedMode(
            sourceProfile = 10,
            preserveMappingEnabled = true,
            allowDv5Conversion = true
        )
    )
}
```

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.core.player.DolbyVisionConversionModeSelectorTest.profile 10 selects no conversion mode"
```

Expected before implementation: pass may already occur through the `else -> null` branch. If it passes, still implement Step 2 for explicit policy documentation.

- [ ] **Step 2: Make Profile 10 explicit in the selector**

Change `DolbyVisionConversionModeSelector.selectedMode(...)`:

```kotlin
return when (sourceProfile) {
    7 -> if (preserveMappingEnabled) {
        MODE_PROFILE_8_1_PRESERVE_MAPPING
    } else {
        MODE_PROFILE_8_1
    }
    5 -> if (allowDv5Conversion) MODE_PROFILE_8_1 else null
    10 -> null
    else -> null
}
```

- [ ] **Step 3: Add Profile 10 autoplay gate coverage**

Append to `DolbyVisionAutoPlayGateTest`:

```kotlin
@Test
fun `autoplay keeps primary stream on non dv displays when probe detects profile 10`() = runBlocking {
    val probe = RecordingDolbyVisionProfileProbe(
        DolbyVisionProfileProbeResult.detected(profileLabel = "dav1.10", profileNumber = 10)
    )
    val gate = DolbyVisionAutoPlayGate(probe)

    val resolved = gate.resolve(
        context = context,
        playbackInfo = primaryPlaybackInfo(),
        autoPlay = true,
        displaySupportsDolbyVision = false
    )

    assertEquals("primary", resolved.playbackInfo.streamKey)
    assertFalse(resolved.fallbackApplied)
    assertEquals(DolbyVisionAutoPlayDecisionReason.PROFILE_ALLOWED, resolved.reason)
    assertEquals(1, probe.invocations)
}
```

- [ ] **Step 4: Add FFmpeg metadata probe Profile 10 coverage**

Append to `FfmpegDolbyVisionProfileProbeTest`:

```kotlin
@Test
fun `stream metadata result 10 maps to detected profile 10`() = runBlocking {
    val probe = FfmpegDolbyVisionProfileProbe(
        backend = fakeBackend(
            streamMetadataJson = """
                {
                  "streams": [
                    {"codec_type":"video","codec_name":"av1","color_transfer":"smpte2084","color_primaries":"bt2020","dv_profile":10},
                    {"codec_type":"audio","codec_name":"eac3"}
                  ]
                }
            """.trimIndent()
        )
    )

    val result = probe.probe(context, "https://example.com/dv10.mp4", null, "dv10.mp4")

    assertEquals(DolbyVisionProfileProbeStatus.DETECTED, result.status)
    assertEquals(10, result.profileNumber)
    assertEquals("av1", result.videoCodec)
    assertEquals("eac3", result.audioCodec)
    assertEquals("dolbyvision", result.hdrType)
}
```

- [ ] **Step 5: Run the DV focused tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.core.player.DolbyVisionConversionModeSelectorTest" \
  --tests "com.nexio.tv.core.player.DolbyVisionAutoPlayGateTest" \
  --tests "com.nexio.tv.core.player.FfmpegDolbyVisionProfileProbeTest"
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelector.kt \
  app/src/test/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelectorTest.kt \
  app/src/test/java/com/nexio/tv/core/player/DolbyVisionAutoPlayGateTest.kt \
  app/src/test/java/com/nexio/tv/core/player/FfmpegDolbyVisionProfileProbeTest.kt
git commit -m "test(player): lock Dolby Vision profile 10 policy"
```

---

### Task 4: Add Disabled-By-Default Dynamic Scheduling Setting

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt`
- Modify: `docs/settings/settings-menu-inventory.md`

- [ ] **Step 1: Add failing DataStore test**

Append to `PlayerSettingsDataStoreTest`:

```kotlin
@Test
fun `dynamic video scheduling defaults disabled and persists selection`() = runTest {
    val dataStore = playerSettingsDataStoreForTest()

    assertEquals(false, dataStore.playerSettings.first().dynamicVideoSchedulingEnabled)

    dataStore.setDynamicVideoSchedulingEnabled(true)
    assertEquals(true, dataStore.playerSettings.first().dynamicVideoSchedulingEnabled)

    dataStore.setDynamicVideoSchedulingEnabled(false)
    assertEquals(false, dataStore.playerSettings.first().dynamicVideoSchedulingEnabled)
}
```

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.PlayerSettingsDataStoreTest.dynamic video scheduling defaults disabled and persists selection"
```

Expected: fail because `dynamicVideoSchedulingEnabled` and setter do not exist.

- [ ] **Step 2: Add the setting to `PlayerSettings`**

In `PlayerSettings`, add near `tunnelingEnabled` or other video playback toggles:

```kotlin
// Experimental: allow Media3 to sleep the playback loop until video renderer progress is possible.
val dynamicVideoSchedulingEnabled: Boolean = false,
```

- [ ] **Step 3: Add the preference key**

In `PlayerSettingsDataStore`, add near `tunnelingEnabledKey`:

```kotlin
private val dynamicVideoSchedulingEnabledKey =
    booleanPreferencesKey("dynamic_video_scheduling_enabled")
```

- [ ] **Step 4: Read the setting from DataStore**

In the `PlayerSettings(` mapping, add:

```kotlin
dynamicVideoSchedulingEnabled = prefs[dynamicVideoSchedulingEnabledKey] ?: false,
```

- [ ] **Step 5: Add the setter**

Add near `setTunnelingEnabled`:

```kotlin
suspend fun setDynamicVideoSchedulingEnabled(enabled: Boolean) {
    store().edit { prefs ->
        prefs[dynamicVideoSchedulingEnabledKey] = enabled
    }
}
```

- [ ] **Step 6: Document the setting inventory**

In `docs/settings/settings-menu-inventory.md`, add under the video/audio playback settings list:

```markdown
- dynamic_video_scheduling_enabled (default `false`)
```

- [ ] **Step 7: Run the DataStore test**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.PlayerSettingsDataStoreTest.dynamic video scheduling defaults disabled and persists selection"
```

Expected: pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt \
  app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt \
  docs/settings/settings-menu-inventory.md
git commit -m "feat(settings): persist dynamic video scheduling toggle"
```

---

### Task 5: Surface Dynamic Scheduling in Playback Settings

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAudioSettings.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add strings**

Add to `app/src/main/res/values/strings.xml` near `audio_tunneled`:

```xml
<string name="video_dynamic_scheduling_title">Dynamic video scheduling</string>
<string name="video_dynamic_scheduling_sub">Experimental Media3 scheduling that can reduce CPU wakeups by letting playback sleep until video renderer progress is possible. Leave off unless benchmarking shows an improvement.</string>
```

- [ ] **Step 2: Add ViewModel setter**

In `PlaybackSettingsViewModel`, add near `setTunnelingEnabled`:

```kotlin
suspend fun setDynamicVideoSchedulingEnabled(enabled: Boolean) {
    playerSettingsDataStore.setDynamicVideoSchedulingEnabled(enabled)
}
```

- [ ] **Step 3: Thread the callback through `PlaybackSettingsSections`**

Add a parameter next to `onSetTunnelingEnabled`:

```kotlin
onSetDynamicVideoSchedulingEnabled: (Boolean) -> Unit,
```

Pass it into `videoSettingsItems`:

```kotlin
onSetDynamicVideoSchedulingEnabled = onSetDynamicVideoSchedulingEnabled,
```

- [ ] **Step 4: Add the callback to `videoSettingsItems`**

In `PlaybackAudioSettings.kt`, add a parameter next to `onSetTunnelingEnabled`:

```kotlin
onSetDynamicVideoSchedulingEnabled: (Boolean) -> Unit,
```

Add this item after `audio_tunneled_playback`:

```kotlin
item(key = "video_dynamic_scheduling") {
    ToggleSettingsItem(
        icon = Icons.Default.Speed,
        title = stringResource(R.string.video_dynamic_scheduling_title),
        subtitle = stringResource(R.string.video_dynamic_scheduling_sub),
        isChecked = playerSettings.dynamicVideoSchedulingEnabled,
        onCheckedChange = onSetDynamicVideoSchedulingEnabled,
        onFocused = onItemFocused,
        enabled = enabled
    )
}
```

- [ ] **Step 5: Wire `PlaybackSettingsScreen`**

In the `PlaybackSettingsSections(` call, add:

```kotlin
onSetDynamicVideoSchedulingEnabled = { enabled ->
    coroutineScope.launch { viewModel.setDynamicVideoSchedulingEnabled(enabled) }
},
```

- [ ] **Step 6: Run a compile check**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin -PUSE_MEDIA3_SOURCE=true
```

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt \
  app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt \
  app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt \
  app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAudioSettings.kt \
  app/src/main/res/values/strings.xml
git commit -m "feat(settings): expose dynamic video scheduling toggle"
```

---

### Task 6: Wire Dynamic Scheduling Into Media3 Player Construction

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`

- [ ] **Step 1: Add the required opt-in import or annotation**

Add import if missing:

```kotlin
import androidx.media3.common.util.ExperimentalApi
```

Update the function annotation:

```kotlin
@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
internal fun PlayerRuntimeController.initializePlayer(url: String, headers: Map<String, String>) {
```

- [ ] **Step 2: Pass the setting into the custom renderers factory**

Add a constructor parameter to `SubtitleOffsetRenderersFactory`:

```kotlin
private val dynamicVideoSchedulingEnabled: Boolean,
```

Pass it at construction:

```kotlin
dynamicVideoSchedulingEnabled = playerSettings.dynamicVideoSchedulingEnabled,
```

- [ ] **Step 3: Set the renderer duration-to-progress flag**

In the `renderersFactory` builder chain, add before `.setExtensionRendererMode(...)` or immediately after construction:

```kotlin
.setEnableMediaCodecVideoRendererDurationToProgressUs(
    playerSettings.dynamicVideoSchedulingEnabled
)
```

The chain should be:

```kotlin
val renderersFactory = SubtitleOffsetRenderersFactory(
    context = context,
    subtitleDelayUsProvider = subtitleDelayUs::get,
    safeAudioModeEnabled = safeAudioModeEnabled,
    cueGroupSubtitleTranslator = builtInSubtitleCueTranslator,
    experimentalFireOsIecPassthroughEnabled =
        playerSettings.experimentalDtsIecPassthroughEnabled,
    disableDav1dForAv1 = av1FfmpegFallbackActive,
    experimentalDv5HardwareToneMapEnabled = dv5HardwareToneMapActive,
    experimentalDv5HardwareToneMapCpuFallbackEnabled =
        dv5HardwareToneMapCpuFallbackEnabled,
    dynamicVideoSchedulingEnabled = playerSettings.dynamicVideoSchedulingEnabled,
    assSsaRenderControllerProvider = { assSsaRenderController }
)
    .setEnableMediaCodecVideoRendererDurationToProgressUs(
        playerSettings.dynamicVideoSchedulingEnabled
    )
    .setExtensionRendererMode(effectiveDecoderPriority)
    .setEnableDecoderFallback(true)
    .setMediaCodecSelector(codecSelector)
```

- [ ] **Step 4: Set ExoPlayer dynamic scheduling on the builder**

Change the builder chain:

```kotlin
_exoPlayer = ExoPlayer.Builder(context)
    .experimentalSetDynamicSchedulingEnabled(playerSettings.dynamicVideoSchedulingEnabled)
    .setTrackSelector(trackSelector!!)
    .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
    .setRenderersFactory(renderersFactory)
    .setLoadControl(loadControl)
    .build()
    .also { assController?.setPlayer(it) }
```

- [ ] **Step 5: Add a diagnostic log field**

In the existing `VIDEO_PATH` log details, add:

```kotlin
"dynamicVideoScheduling=${playerSettings.dynamicVideoSchedulingEnabled} " +
```

- [ ] **Step 6: Run a source check for both required APIs**

Run:

```bash
rg -n 'setEnableMediaCodecVideoRendererDurationToProgressUs|experimentalSetDynamicSchedulingEnabled|dynamicVideoScheduling=' app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt
```

Expected: output contains all three strings.

- [ ] **Step 7: Compile**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin -PUSE_MEDIA3_SOURCE=true
```

Expected: pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt
git commit -m "feat(player): wire Media3 dynamic video scheduling"
```

---

### Task 7: Verify Transparent Media3 1.10.0 Adoption Items

**Files:**
- No source changes expected.

- [ ] **Step 1: Verify metadata renderer pass-through**

Run:

```bash
sed -n '1336,1350p' app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt
```

Expected: `SubtitleOffsetRenderersFactory.createRenderers(...)` still passes `metadataRendererOutput` unchanged into `super.createRenderers(...)`.

- [ ] **Step 2: Verify Media3 default renderer count is 4 in the fork**

Run:

```bash
sed -n '823,833p' media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultRenderersFactory.java
```

Expected: loop creates four `MetadataRenderer` instances.

- [ ] **Step 3: Verify HLS factory construction still uses Media3 HLS source**

Run:

```bash
rg -n 'HlsMediaSource\\.Factory|loadErrorHandlingPolicy|DefaultLoadErrorHandlingPolicy' app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
```

Expected: HLS source factory construction remains intact; no custom code bypasses Media3's 1.10.0 redundant-location fallback before Media3 sees load errors.

- [ ] **Step 4: Record verification result in the final implementation notes**

Use this exact note if the checks pass:

```text
Verified Media3 metadata renderer pass-through and HLS factory wiring. No app code change needed for metadata renderer count or HLS redundant-location fallback.
```

---

### Task 8: Final Verification

**Files:**
- No additional source changes unless a verification command reveals a compile failure.

- [ ] **Step 1: Run focused unit tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.core.player.DolbyVisionCodecStringsTest" \
  --tests "com.nexio.tv.core.player.DolbyVisionConversionModeSelectorTest" \
  --tests "com.nexio.tv.core.player.DolbyVisionAutoPlayGateTest" \
  --tests "com.nexio.tv.core.player.FfmpegDolbyVisionProfileProbeTest" \
  --tests "com.nexio.tv.data.local.PlayerSettingsDataStoreTest"
```

Expected: pass.

- [ ] **Step 2: Run app assemble**

Run:

```bash
./gradlew :app:assembleDebug -PUSE_MEDIA3_SOURCE=true
```

Expected: pass. Existing Chaquopy Python 3.11 bytecode warnings and 32-bit native-library warnings may appear; they are not introduced by this work.

- [ ] **Step 3: Run targeted runtime smoke on device**

Run a manual playback smoke with these streams:

```text
DV5 HEVC source: conversion/tone-map toggles behave as before.
DV7 HEVC source: DV7 -> DV8.1 conversion still activates when enabled.
DV10 AV1 source: app logs profileNumber=10, keeps primary stream, does not activate extractor conversion hook.
HLS source: playback starts and no load-error retry regression appears.
Progressive MKV source: playback starts through ParallelRangeDataSource/disk spool path as configured.
```

- [ ] **Step 4: Dynamic scheduling measurement**

Run a 20-minute A/B playback on a Google TV Streamer or Fire TV Stick:

```text
Off: dynamic_video_scheduling_enabled=false
On: dynamic_video_scheduling_enabled=true
```

Collect:

```text
JankStats dropped frame rate
logcat VIDEO_PATH dynamicVideoScheduling value
device surface temperature trend
visible stutter/regression notes
```

Decision rule:

```text
Keep default false unless dynamic scheduling reduces wake/thermal pressure with no frame pacing regression on at least one constrained TV device.
```

- [ ] **Step 5: Final commit if verification-only notes changed docs**

If Task 7 adds notes to a docs file, commit them:

```bash
git add docs/settings/settings-menu-inventory.md
git commit -m "docs(settings): record Media3 1.10 playback verification"
```

If no docs changed, skip this commit.

---

## Assumptions

- The Media3 fork rebase is complete and the parent repo intentionally points `media` to `edaa8415c8` or a descendant of it.
- Dynamic scheduling remains disabled by default until measured on real TV hardware.
- Profile 10 should not use Nexio's DV5/DV7 conversion hooks; it should remain on Media3/device native handling or Media3's AV1 fallback behavior.
- Localized strings can fall back to base `values/strings.xml` for this scoped implementation. A separate translation pass can update `values-de`, `values-es`, `values-nl`, `values-zh-rCN`, and `values-fr`.
- Full `:app:testDebugUnitTest` and full Media3 `:lib-exoplayer:testDebugUnitTest` currently have unrelated failures in this checkout; use focused tests and `:app:assembleDebug` as the acceptance gate for this plan.

## Self-Review

- Spec coverage: DV Profile 10, dynamic scheduling, metadata renderer pass-through, HLS fallback verification, and version drift are covered.
- Placeholder scan: no implementation step uses TODO/TBD/fill-in language.
- Type consistency: the setting name is consistently `dynamicVideoSchedulingEnabled`; the DataStore setter is `setDynamicVideoSchedulingEnabled`; the Media3 renderer API is `setEnableMediaCodecVideoRendererDurationToProgressUs`; the ExoPlayer builder API is `experimentalSetDynamicSchedulingEnabled`.
