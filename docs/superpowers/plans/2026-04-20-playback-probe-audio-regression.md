# Playback Probe And Audio Regression Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the playback regressions introduced in the last 24 hours without masking the true root causes behind FFmpeg probing, ASS/SSA preflight, Dolby Vision autoplay fallback, and safe-audio recovery.

**Architecture:** Keep the lightweight FFmpeg stream metadata probe for ASS/SSA and AFR, but stop treating empty native output as valid metadata. Restore Dolby Vision classification to use packet-aware legacy profile probing when stream metadata cannot prove the DV profile. Keep ASS/SSA renderer activation tied to actual ASS evidence or overlay readiness, and make safe-audio mode a real PCM-only recovery path.

**Tech Stack:** Kotlin, Android Media3/ExoPlayer, bundled FFmpeg JNI, MockK/JUnit unit tests, Gradle.

---

## File Structure

- Modify `app/src/main/java/com/nexio/tv/core/player/FfmpegStreamMetadataProbe.kt`
  - Owns the shared lightweight FFmpeg metadata probe and cache semantics.
- Modify `app/src/main/java/com/nexio/tv/core/player/DolbyVisionAutoPlayGate.kt`
  - Owns DV autoplay probe interpretation and fallback decisions.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - Owns player startup, ASS/SSA preflight, overlay attachment, and safe-audio renderer setup.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
  - Exposes reinitialization only if ASS overlay retry needs it.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`
  - Owns deterministic autoplay selected and fallback candidate construction.
- Modify `app/src/test/java/com/nexio/tv/core/player/FfmpegStreamMetadataProbeTest.kt`
  - Covers empty native probe results and cache recovery.
- Modify `app/src/test/java/com/nexio/tv/core/player/FfmpegDolbyVisionProfileProbeTest.kt`
  - Covers fallback from stream metadata to packet-aware DV profile probing.
- Modify `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAssSsaPipelineTest.kt`
  - Covers ASS/SSA preflight fallback and overlay readiness.
- Create `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerSafeAudioCapabilitiesTest.kt`
  - Covers safe-audio encoded capability filtering.
- Modify `app/src/test/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModelDeterministicAutoplayTest.kt`
  - Covers non-DV fallback preservation outside the top preflight candidates.
- Do not keep investigation-only changes in `media/libraries/decoder_ffmpeg/src/main/jni/ffmain.cpp` unless a task explicitly adds permanent structured diagnostics. The current native logging hunk is for investigation only.

## Task 1: Make Shared FFmpeg Probe Fail Closed On Empty Native Metadata

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/FfmpegStreamMetadataProbe.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/player/FfmpegStreamMetadataProbeTest.kt`

- [ ] **Step 1: Add a failing test proving empty native metadata is not cached**

Add these imports to `FfmpegStreamMetadataProbeTest.kt`:

```kotlin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
```

Add this cleanup method and test inside the existing `FfmpegStreamMetadataProbeTest` class:

```kotlin
@After
fun resetProbeBackend() {
    FfmpegStreamMetadataProbe.resetForTesting()
}

@Test
fun emptyNativeResultDoesNotPopulateCacheAndNextSuccessCanRecover() {
    var calls = 0
    FfmpegStreamMetadataProbe.setBackendForTesting(
        object : FfmpegStreamMetadataBackend {
            override fun probeStreamMetadataJson(url: String, requestHeadersBlob: String?): String? {
                calls += 1
                return if (calls == 1) {
                    """{"streams":[]}"""
                } else {
                    """{"streams":[{"codec_type":"video","codec_name":"hevc"}]}"""
                }
            }
        }
    )

    assertNull(FfmpegStreamMetadataProbe.probeBlocking("https://example.test/video.mkv"))

    val recovered = FfmpegStreamMetadataProbe.probeBlocking("https://example.test/video.mkv")

    assertEquals(2, calls)
    assertEquals("hevc", recovered?.streams?.single()?.codecName)
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.core.player.FfmpegStreamMetadataProbeTest
```

Expected before implementation: compile fails because `FfmpegStreamMetadataBackend`, `setBackendForTesting`, and `resetForTesting` do not exist.

- [ ] **Step 3: Add an injectable backend and reject empty results**

In `FfmpegStreamMetadataProbe.kt`, add the backend type above `object FfmpegStreamMetadataProbe`:

```kotlin
internal interface FfmpegStreamMetadataBackend {
    fun probeStreamMetadataJson(url: String, requestHeadersBlob: String?): String?
}

private object DefaultFfmpegStreamMetadataBackend : FfmpegStreamMetadataBackend {
    override fun probeStreamMetadataJson(url: String, requestHeadersBlob: String?): String? {
        return FfmpegLibrary.probeDolbyVisionStreamMetadataJson(url, requestHeadersBlob)
    }
}
```

Inside `object FfmpegStreamMetadataProbe`, add:

```kotlin
@Volatile
private var backend: FfmpegStreamMetadataBackend = DefaultFfmpegStreamMetadataBackend

internal fun setBackendForTesting(testBackend: FfmpegStreamMetadataBackend) {
    synchronized(nativeProbeLock) {
        backend = testBackend
        cache.clear()
    }
}

internal fun resetForTesting() {
    synchronized(nativeProbeLock) {
        backend = DefaultFfmpegStreamMetadataBackend
        cache.clear()
    }
}
```

Replace the native call block in `probeBlocking()` with:

```kotlin
val parsed = backend.probeStreamMetadataJson(url, headerBlob)
    ?.let(::parse)
if (parsed == null || parsed.streams.isEmpty()) {
    Log.w(TAG, "FFmpeg stream metadata probe returned no streams")
    return null
}
parsed.also { cache[key] = it }
```

- [ ] **Step 4: Run the focused test**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.core.player.FfmpegStreamMetadataProbeTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/FfmpegStreamMetadataProbe.kt app/src/test/java/com/nexio/tv/core/player/FfmpegStreamMetadataProbeTest.kt
git commit -m "fix(player): avoid caching empty ffmpeg probe results"
```

## Task 2: Restore Packet-Aware Dolby Vision Profile Classification

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/DolbyVisionAutoPlayGate.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/player/FfmpegDolbyVisionProfileProbeTest.kt`

- [ ] **Step 1: Add failing tests for metadata miss plus legacy profile fallback**

Append these tests to `FfmpegDolbyVisionProfileProbeTest.kt`:

```kotlin
@Test
fun `metadata without dv profile falls back to legacy profile probe`() = runBlocking {
    val probe = FfmpegDolbyVisionProfileProbe(
        backend = object : NativeDolbyVisionProfileBackend {
            override fun probe(url: String, requestHeadersBlob: String?): Int = 7
            override fun probeStreamMetadataJson(url: String, requestHeadersBlob: String?): String? {
                return """
                    {
                      "streams": [
                        {"codec_type":"video","codec_name":"hevc","color_transfer":"smpte2084","color_primaries":"bt2020"},
                        {"codec_type":"audio","codec_name":"eac3"}
                      ]
                    }
                """.trimIndent()
            }
        }
    )

    val result = probe.probe(context, "https://example.com/dv-webdl.mkv", null, "dv-webdl.mkv")

    assertEquals(DolbyVisionProfileProbeStatus.DETECTED, result.status)
    assertEquals(7, result.profileNumber)
    assertEquals("hevc", result.videoCodec)
    assertEquals("eac3", result.audioCodec)
}

@Test
fun `metadata without dv profile remains not dolby vision when legacy probe finds no profile`() = runBlocking {
    val probe = FfmpegDolbyVisionProfileProbe(
        backend = object : NativeDolbyVisionProfileBackend {
            override fun probe(url: String, requestHeadersBlob: String?): Int = -2
            override fun probeStreamMetadataJson(url: String, requestHeadersBlob: String?): String? {
                return """{"streams":[{"codec_type":"video","codec_name":"h264"}]}"""
            }
        }
    )

    val result = probe.probe(context, "https://example.com/sdr.mkv", null, "sdr.mkv")

    assertEquals(DolbyVisionProfileProbeStatus.NOT_DOLBY_VISION, result.status)
    assertEquals(null, result.profileNumber)
}
```

- [ ] **Step 2: Run the failing tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.core.player.FfmpegDolbyVisionProfileProbeTest
```

Expected before implementation: first new test fails with `NOT_DOLBY_VISION` instead of `DETECTED`.

- [ ] **Step 3: Add legacy profile fallback in `FfmpegDolbyVisionProfileProbe.probe()`**

In `DolbyVisionAutoPlayGate.kt`, replace the return around `parseStreamMetadataProbeResult(...) ?: DolbyVisionProfileProbeResult.failed(...)` with:

```kotlin
val parsedResult = parseStreamMetadataProbeResult(
    metadata = metadata,
    device = deviceSnapshot
)
if (parsedResult?.status == DolbyVisionProfileProbeStatus.NOT_DOLBY_VISION) {
    val legacyProfile = backend.probe(url, headerBlob)
    if (legacyProfile >= 0) {
        return@runCatching DolbyVisionProfileProbeResult.detected(
            profileLabel = "dv_profile_$legacyProfile",
            profileNumber = legacyProfile,
            videoCodec = parsedResult.videoCodec,
            audioCodec = parsedResult.audioCodec,
            hdrType = parsedResult.hdrType ?: "dolbyvision"
        )
    }
}
parsedResult ?: DolbyVisionProfileProbeResult.failed("ffprobe_probe_failed")
```

- [ ] **Step 4: Run the focused tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.core.player.FfmpegDolbyVisionProfileProbeTest --tests com.nexio.tv.core.player.DolbyVisionAutoPlayGateTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/DolbyVisionAutoPlayGate.kt app/src/test/java/com/nexio/tv/core/player/FfmpegDolbyVisionProfileProbeTest.kt
git commit -m "fix(player): restore packet-aware dolby vision probing"
```

## Task 3: Prevent ASS/SSA Preflight From Enabling Renderer State Without Evidence

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAssSsaPipelineTest.kt`

- [ ] **Step 1: Replace tests that encode the bad behavior**

In `PlayerRuntimeControllerAssSsaPipelineTest.kt`, replace `negativeProbeEnablesAssReadyPipelineForProgressiveMkvFallback()` with:

```kotlin
@Test
fun negativeProbeOnlyEnablesAssReadyPipelineForKnownMkvOrWebmFilename() {
    assertTrue(
        shouldEnableAssSsaPipelineForProgressiveFallback(
            url = "https://example.test/proxy",
            filename = "episode.mkv"
        )
    )
    assertTrue(
        shouldEnableAssSsaPipelineForProgressiveFallback(
            url = "https://example.test/proxy",
            filename = "episode.webm"
        )
    )
    assertFalse(
        shouldEnableAssSsaPipelineForProgressiveFallback(
            url = "https://example.test/proxy",
            filename = null
        )
    )
    assertFalse(
        shouldEnableAssSsaPipelineForProgressiveFallback(
            url = "https://example.test/playlist.m3u8",
            filename = "episode.mkv"
        )
    )
}
```

Replace `overlayProviderNullStillStartsAssSsaPipeline()` with:

```kotlin
@Test
fun overlayProviderNullWaitsForRetryWithoutClaimingAssSsaPipelineActive() {
    val decision = resolveAssSsaPipelineOverlayDecision(
        requestedUseAssSsaPipeline = true,
        overlayAttached = false
    )

    assertFalse(decision.useAssSsaPipeline)
    assertFalse(decision.disableOverrideForCurrentStream)
}
```

Add:

```kotlin
@Test
fun overlayAvailabilityRetriesPendingAssSsaPipeline() {
    assertTrue(
        shouldRetryAssSsaPipelineWhenOverlayAvailable(
            overrideForCurrentStream = true,
            activePlayerUsesAssSsaRenderer = false,
            switchInFlight = false,
            fallbackHandled = false,
            overlayAvailable = true
        )
    )
    assertFalse(
        shouldRetryAssSsaPipelineWhenOverlayAvailable(
            overrideForCurrentStream = true,
            activePlayerUsesAssSsaRenderer = false,
            switchInFlight = false,
            fallbackHandled = true,
            overlayAvailable = true
        )
    )
}
```

- [ ] **Step 2: Run the failing ASS/SSA pipeline tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerAssSsaPipelineTest
```

Expected before implementation: failures for null filename fallback, overlay decision, and missing `shouldRetryAssSsaPipelineWhenOverlayAvailable`.

- [ ] **Step 3: Implement the ASS/SSA decision helpers**

In `PlayerRuntimeControllerInitialization.kt`, replace `resolveAssSsaPipelineOverlayDecision()` with:

```kotlin
internal fun resolveAssSsaPipelineOverlayDecision(
    requestedUseAssSsaPipeline: Boolean,
    overlayAttached: Boolean
): AssSsaPipelineOverlayDecision {
    return AssSsaPipelineOverlayDecision(
        useAssSsaPipeline = requestedUseAssSsaPipeline && overlayAttached,
        disableOverrideForCurrentStream = false
    )
}
```

Add below `resolveAssSsaPipelineTrackAdjustment()`:

```kotlin
internal fun shouldRetryAssSsaPipelineWhenOverlayAvailable(
    overrideForCurrentStream: Boolean?,
    activePlayerUsesAssSsaRenderer: Boolean,
    switchInFlight: Boolean,
    fallbackHandled: Boolean,
    overlayAvailable: Boolean
): Boolean {
    return overrideForCurrentStream == true &&
        !activePlayerUsesAssSsaRenderer &&
        !switchInFlight &&
        !fallbackHandled &&
        overlayAvailable
}
```

In `shouldEnableAssSsaPipelineForProgressiveFallback()`, replace the return with:

```kotlin
return normalizedFilename.endsWith(".mkv") ||
    normalizedFilename.endsWith(".webm")
```

- [ ] **Step 4: Expose controlled reinitialize for overlay retry**

In `PlayerRuntimeControllerObservers.kt`, change:

```kotlin
private fun PlayerRuntimeController.scheduleDeferredPlayerReinitialize(
```

to:

```kotlin
internal fun PlayerRuntimeController.scheduleDeferredPlayerReinitialize(
```

In `setAssSsaRenderOverlayViewProvider()` in `PlayerRuntimeControllerInitialization.kt`, replace the body with:

```kotlin
assSsaOverlayViewProvider = provider
val overlayView = provider?.invoke()
assSsaRenderController?.setOverlayView(overlayView)
if (shouldRetryAssSsaPipelineWhenOverlayAvailable(
        overrideForCurrentStream = assSsaPipelineOverrideForCurrentStream,
        activePlayerUsesAssSsaRenderer = activePlayerUsesAssSsaRenderer,
        switchInFlight = assSsaPipelineSwitchInFlight,
        fallbackHandled = assSsaPipelineFallbackHandledForCurrentStream,
        overlayAvailable = overlayView != null
    )
) {
    assSsaPipelineSwitchInFlight = true
    scheduleDeferredPlayerReinitialize(fromPositionMs = _exoPlayer?.currentPosition ?: 0L)
}
```

- [ ] **Step 5: Run the focused tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerAssSsaPipelineTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAssSsaPipelineTest.kt
git commit -m "fix(player): gate ass renderer on evidence and overlay readiness"
```

## Task 4: Make Safe-Audio Recovery PCM-Only

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerSafeAudioCapabilitiesTest.kt`

- [ ] **Step 1: Add a failing unit test for safe-audio encodings**

Create `PlayerSafeAudioCapabilitiesTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import androidx.media3.common.C
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PlayerSafeAudioCapabilitiesTest {
    @Test
    fun safeAudioModeAdvertisesOnlyPcm16() {
        assertArrayEquals(
            intArrayOf(C.ENCODING_PCM_16BIT),
            safeAudioModeSupportedEncodingsForTesting()
        )
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerSafeAudioCapabilitiesTest
```

Expected before implementation: compile fails because `safeAudioModeSupportedEncodingsForTesting()` does not exist.

- [ ] **Step 3: Add the helper and use it in safe-audio capabilities**

In `PlayerRuntimeControllerInitialization.kt`, add above `buildStableAudioCapabilities()`:

```kotlin
internal fun safeAudioModeSupportedEncodingsForTesting(): IntArray {
    return intArrayOf(C.ENCODING_PCM_16BIT)
}
```

Replace the return in `buildStableAudioCapabilities()` with:

```kotlin
return AudioCapabilities(
    safeAudioModeSupportedEncodingsForTesting(),
    detected.maxChannelCount
)
```

- [ ] **Step 4: Run audio tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerSafeAudioCapabilitiesTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerSafeAudioCapabilitiesTest.kt
git commit -m "fix(player): make safe audio fallback pcm only"
```

## Task 5: Preserve Non-DV Autoplay Fallback Candidates Beyond Top Preflight Picks

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModelDeterministicAutoplayTest.kt`

- [ ] **Step 1: Add a failing test for fallback candidate diversity**

Append to `StreamScreenViewModelDeterministicAutoplayTest.kt`:

```kotlin
@Test
fun `autoplay fallback list preserves non dv candidate outside cap`() {
    val fallback = selectAutoplayFallbackCandidatesForTesting(
        selectedKey = "dv-1",
        fallbackCandidates = listOf(
            scenarioCard("dv-1", "rd", visualTags = listOf("DV"), quality = "WEB-DL", filename = "Movie.2160p.WEB-DL.DV.mkv"),
            scenarioCard("dv-2", "pm", visualTags = listOf("DV"), quality = "WEB-DL", filename = "Movie.2160p.WEB-DL.DV.mkv"),
            scenarioCard("dv-3", "rd", visualTags = listOf("DV"), quality = "WEB-DL", filename = "Movie.2160p.WEB-DL.DV.mkv"),
            scenarioCard("hdr-1", "pm", visualTags = listOf("HDR10"), quality = "WEB-DL", filename = "Movie.2160p.WEB-DL.HDR.mkv")
        ),
        maxFallbackCandidates = 3
    )

    assertTrue(
        fallback.any { it.parsed.exactDuplicateKey == "hdr-1" }
    )
}
```

- [ ] **Step 2: Run the test**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.stream.StreamScreenViewModelDeterministicAutoplayTest
```

Expected before implementation: compile fails because `selectAutoplayFallbackCandidatesForTesting` does not exist.

- [ ] **Step 3: Keep fallback candidates broad and ordered**

In `StreamScreenViewModel.kt`, add these helpers near `buildStreamPlaybackInfo()`:

```kotlin
internal fun selectAutoplayFallbackCandidatesForTesting(
    selectedKey: String?,
    fallbackCandidates: List<StreamCardModel>,
    maxFallbackCandidates: Int = MAX_FALLBACK_CANDIDATES
): List<StreamCardModel> {
    return selectAutoplayFallbackCandidates(
        selectedKey = selectedKey,
        fallbackCandidates = fallbackCandidates,
        maxFallbackCandidates = maxFallbackCandidates
    )
}

private fun selectAutoplayFallbackCandidates(
    selectedKey: String?,
    fallbackCandidates: List<StreamCardModel>,
    maxFallbackCandidates: Int = MAX_FALLBACK_CANDIDATES
): List<StreamCardModel> {
    val candidates = fallbackCandidates.filter { candidate ->
        val candidateKey = candidate.stream.wrappedOriginalStreamKey ?: candidate.parsed.exactDuplicateKey
        candidateKey != selectedKey
    }
    val nonDv = candidates.filterNot { it.isDolbyVisionCandidateForAutoplay() }
    return (candidates.take(maxFallbackCandidates) + nonDv.take(1)).distinctBy {
        it.stream.wrappedOriginalStreamKey ?: it.parsed.exactDuplicateKey
    }
}

private fun StreamCardModel.isDolbyVisionCandidateForAutoplay(): Boolean {
    return parsed.visualTags.any { tag ->
        val normalized = tag.lowercase()
        normalized == "dv" || normalized.contains("dolby vision") || normalized.contains("dovi")
    }
}
```

In `buildStreamPlaybackInfo()`, replace:

```kotlin
autoPlayFallbackCandidates = fallbackCandidates
    .filter { candidate ->
        val candidateKey = candidate.stream.wrappedOriginalStreamKey ?: candidate.parsed.exactDuplicateKey
        candidateKey != selectedKey
    }
    .take(MAX_FALLBACK_CANDIDATES)
```

with:

```kotlin
autoPlayFallbackCandidates = selectAutoplayFallbackCandidates(
    selectedKey = selectedKey,
    fallbackCandidates = fallbackCandidates
)
```

- [ ] **Step 4: Run deterministic autoplay tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.stream.StreamScreenViewModelDeterministicAutoplayTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt app/src/test/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModelDeterministicAutoplayTest.kt
git commit -m "fix(autoplay): retain non-dv fallback candidates"
```

## Task 6: Remove Investigation-Only Native FFmpeg Logging And Verify Build

**Files:**
- Modify: `media/libraries/decoder_ffmpeg/src/main/jni/ffmain.cpp`

- [ ] **Step 1: Remove the temporary diagnostic hunk**

In `media/libraries/decoder_ffmpeg/src/main/jni/ffmain.cpp`, restore `ffmpegProbeDolbyVisionStreamMetadataJson()` to avoid new permanent `LOGE` calls. The stream metadata function should still return JSON, but native error logging should not be added unless it is behind an existing diagnostics setting.

The relevant block should be:

```cpp
if (open_result >= 0 && format_context != nullptr &&
    avformat_find_stream_info(format_context, nullptr) >= 0) {
    for (unsigned int i = 0; i < format_context->nb_streams; ++i) {
        AVStream *stream = format_context->streams[i];
        if (stream == nullptr || stream->codecpar == nullptr) {
            continue;
        }
```

- [ ] **Step 2: Verify no native diagnostic diff remains**

Run:

```bash
git -C media/libraries/decoder_ffmpeg diff -- src/main/jni/ffmain.cpp
```

Expected: no output.

- [ ] **Step 3: Run focused unit test suite**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests com.nexio.tv.core.player.FfmpegStreamMetadataProbeTest \
  --tests com.nexio.tv.core.player.FfmpegDolbyVisionProfileProbeTest \
  --tests com.nexio.tv.core.player.DolbyVisionAutoPlayGateTest \
  --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerAssSsaPipelineTest \
  --tests com.nexio.tv.ui.screens.player.PlayerSafeAudioCapabilitiesTest \
  --tests com.nexio.tv.ui.screens.stream.StreamScreenViewModelDeterministicAutoplayTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Compile app**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add media/libraries/decoder_ffmpeg/src/main/jni/ffmain.cpp
git commit -m "chore(player): remove temporary ffmpeg probe diagnostics"
```

## Task 7: Release Build Smoke Install

**Files:**
- No source files.

- [ ] **Step 1: Build release APK**

Run:

```bash
./gradlew :app:assembleUniversalRelease
```

Expected: BUILD SUCCESSFUL and `app/build/outputs/apk/universal/release/app-universal-release.apk` exists.

- [ ] **Step 2: Install release APK to the AM9 device**

Run:

```bash
adb -s 192.168.50.71:5555 install -r app/build/outputs/apk/universal/release/app-universal-release.apk
```

Expected: `Success`.

- [ ] **Step 3: Verify installed package version**

Run:

```bash
adb -s 192.168.50.71:5555 shell dumpsys package com.nexio.tv | rg "versionName|versionCode|lastUpdateTime"
```

Expected: `versionName=0.54` or the current branch release version, with `lastUpdateTime` matching the install time.

## Self-Review

Spec coverage:
- FFmpeg probe regression: Tasks 1 and 2.
- ASS/SSA startup and subtitle renderer state: Task 3.
- Audio track switch and safe-audio recovery: Task 4.
- Autoplay “no eligible streams” from fallback candidate starvation: Task 5.
- Investigation diff cleanup and verification: Tasks 6 and 7.

Placeholder scan:
- No `TBD`, `TODO`, or placeholder implementation steps remain.

Type consistency:
- New symbols introduced by the plan are `FfmpegStreamMetadataBackend`, `setBackendForTesting`, `resetForTesting`, `shouldRetryAssSsaPipelineWhenOverlayAvailable`, and `safeAudioModeSupportedEncodingsForTesting`; later tasks use those exact names.
