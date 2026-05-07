# AFR FFmpeg Probe And Device Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace AFR's broken NextLib frame-rate probe with Nexio's existing lightweight FFmpeg probe, then make the AFR settings UI reflect Android's device-level match-content-frame-rate setting.

**Architecture:** Use the forked FFmpeg extension as the single native media-probe source for frame rate, resolution, codec, HDR, and Dolby Vision metadata. Keep Nexio's in-app AFR setting as the app's request/restore policy, but surface Android's device setting as the system gate and remove the separate in-app resolution-matching choice from the UI.

**Tech Stack:** Kotlin, Android Settings.Secure, Media3 fork, JNI/C++ FFmpeg extension, Gson JSON parsing, Robolectric/JUnit.

---

## Current Evidence

- `FrameRateUtils` currently tries NextLib through `MediaInfoBuilder`.
- Runtime logs on Google TV Streamer show:

```text
FrameRateUtils: NextLib frame rate probe failed:
dlopen failed: cannot locate symbol "avcodec_descriptor_get" referenced by libmediainfo.so
PlayerViewModel: AFR preflight probe timed out/failed (NextLib + extractor fallback)
```

- Native symbol inspection shows the final APK has a versioned symbol mismatch:

```text
libmediainfo.so requires: avcodec_descriptor_get@LIBAVCODEC_60
merged libavcodec.so exports: avcodec_descriptor_get@@LIBAVCODEC_62
```

- The existing FFmpeg extension already emits compact stream metadata JSON from `FfmpegLibrary.probeDolbyVisionStreamMetadataJson`.
- Android device-level AFR is readable with `Settings.Secure.getInt(contentResolver, "match_content_frame_rate", 0)`.
- The Google TV Streamer reported `match_content_frame_rate=2` after enabling it in Android settings.

## File Structure

- Modify `media/libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegLibrary.java`
  - Update docs for `probeDolbyVisionStreamMetadataJson` so it documents width/height/frame-rate fields.

- Modify `media/libraries/decoder_ffmpeg/src/main/jni/ffmain.cpp`
  - Extend `ffmpegProbeDolbyVisionStreamMetadataJson` to emit selected stream width, height, `avg_frame_rate`, and `r_frame_rate`.

- Modify `app/src/main/java/com/nexio/tv/core/player/FrameRateUtils.kt`
  - Add a Kotlin wrapper for the FFmpeg stream metadata probe.
  - Parse rational frame-rate values.
  - Prefer FFmpeg metadata for AFR detection before `MediaExtractor`.
  - Stop using NextLib in the AFR path.

- Modify `app/src/main/java/com/nexio/tv/core/player/DolbyVisionAutoPlayGate.kt`
  - Extend `StreamProbeStreamMetadata` so DV autoplay parsing can tolerate and optionally retain width/height/fps fields.

- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAfrPreflight.kt`
  - Use the FFmpeg-first source probe and reduce probe timeout to a startup-safe window.
  - Remove the 60-second NextLib preflight branch.

- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt`
  - Use device-derived resolution-switching permission instead of user-configured `resolutionMatchingEnabled`.

- Create `app/src/main/java/com/nexio/tv/core/player/AndroidFrameRateSettings.kt`
  - Read Android's `match_content_frame_rate` setting.
  - Provide display labels for disabled, seamless-only, always/non-seamless, and unknown.
  - Provide an intent builder for Android display settings.

- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAudioSettings.kt`
  - Change AFR section subtitle to show Android device status.
  - Add a row to open Android display settings.
  - Remove the resolution-matching toggle from the expanded AFR options.

- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
  - Pass Android AFR status label/action into the video settings section.
  - Remove `resolutionMatchingEnabled` from `FrameRateMatchingModeOptions`.

- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
  - Read Android AFR status with lifecycle state.
  - Launch Android display settings from the AFR section.

- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
  - Expose Android AFR status as a simple read function or flow.
  - Keep existing `setResolutionMatchingEnabled` for migration/backward compatibility only.

- Modify `app/src/main/res/values/strings.xml`
  - Add strings for Android AFR status and "Open Android display settings".

- Modify localized `strings.xml` files only by adding English fallback values when required by the build. Use native translations in a later localization pass.

- Test `app/src/test/java/com/nexio/tv/core/player/FrameRateUtilsTest.kt`
  - Add rational frame-rate parsing and FFmpeg metadata parsing coverage.

- Test `app/src/test/java/com/nexio/tv/core/player/FfmpegDolbyVisionProfileProbeTest.kt`
  - Add coverage that existing DV parsing ignores extra width/height/fps fields safely.

- Test `app/src/test/java/com/nexio/tv/core/player/AndroidFrameRateSettingsTest.kt`
  - Add status-label mapping tests.

- Phase 2 investigation output: `docs/superpowers/reports/2026-04-14-nextlib-usage-investigation.md`
  - Document all remaining NextLib references and whether to remove, upgrade, or isolate them.

---

## Task 1: Extend Native FFmpeg Metadata JSON

**Files:**
- Modify: `media/libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegLibrary.java`
- Modify: `media/libraries/decoder_ffmpeg/src/main/jni/ffmain.cpp`

- [ ] **Step 1: Update Java documentation for the probe payload**

In `FfmpegLibrary.java`, replace the current `probeDolbyVisionStreamMetadataJson` doc payload list:

```java
  /**
   * Probes all streams once and returns stream metadata JSON for autoplay scoring and AFR.
   *
   * <p>The payload mirrors the small ffprobe-style subset used by Nexio:
   * {@code codec_type}, {@code codec_name}, {@code width}, {@code height},
   * {@code avg_frame_rate}, {@code r_frame_rate}, {@code color_transfer},
   * {@code color_primaries}, {@code dv_profile}, and {@code hdr10_plus}.
   */
```

- [ ] **Step 2: Add a rational helper in native code**

In `ffmain.cpp`, near existing JSON helper functions such as `escapeJsonString`, add:

```cpp
static std::string rationalToJsonString(AVRational rational) {
    if (rational.num <= 0 || rational.den <= 0) {
        return "";
    }
    return std::to_string(rational.num) + "/" + std::to_string(rational.den);
}
```

- [ ] **Step 3: Emit video width, height, and frame-rate rationals**

Inside `Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_ffmpegProbeDolbyVisionStreamMetadataJson`, after:

```cpp
json += "\"codec_type\":\"" + escapeJsonString(codec_type) + "\"";
json += ",\"codec_name\":\"" + escapeJsonString(codec_name) + "\"";
```

add:

```cpp
if (codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
    if (codecpar->width > 0) {
        json += ",\"width\":" + std::to_string(codecpar->width);
    }
    if (codecpar->height > 0) {
        json += ",\"height\":" + std::to_string(codecpar->height);
    }

    const std::string avg_frame_rate = rationalToJsonString(stream->avg_frame_rate);
    if (!avg_frame_rate.empty()) {
        json += ",\"avg_frame_rate\":\"" + escapeJsonString(avg_frame_rate) + "\"";
    }

    const std::string r_frame_rate = rationalToJsonString(stream->r_frame_rate);
    if (!r_frame_rate.empty()) {
        json += ",\"r_frame_rate\":\"" + escapeJsonString(r_frame_rate) + "\"";
    }
}
```

- [ ] **Step 4: Compile native-aware app source**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin
```

Expected: build succeeds. Existing warnings about native library architecture are acceptable; Kotlin or C++ compile errors are not.

- [ ] **Step 5: Commit native metadata extension**

```bash
git add media/libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegLibrary.java media/libraries/decoder_ffmpeg/src/main/jni/ffmain.cpp
git commit -m "feat(player): expose frame metadata from ffmpeg probe"
```

---

## Task 2: Parse FFmpeg Frame Metadata In Kotlin

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/FrameRateUtils.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/player/FrameRateUtilsTest.kt`

- [ ] **Step 1: Write failing rational parsing tests**

Add tests to `FrameRateUtilsTest.kt`:

```kotlin
@Test
fun `parse probe rational handles ntsc cinema rate`() {
    assertEquals(24000f / 1001f, FrameRateUtils.parseProbeRationalForTests("24000/1001")!!, 0.0001f)
}

@Test
fun `parse probe rational rejects empty and zero denominator`() {
    assertEquals(null, FrameRateUtils.parseProbeRationalForTests(""))
    assertEquals(null, FrameRateUtils.parseProbeRationalForTests("0/0"))
    assertEquals(null, FrameRateUtils.parseProbeRationalForTests("24/0"))
}

@Test
fun `parse probe rational handles decimal fallback`() {
    assertEquals(23.976f, FrameRateUtils.parseProbeRationalForTests("23.976")!!, 0.0001f)
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.core.player.FrameRateUtilsTest'
```

Expected: fails because `parseProbeRationalForTests` does not exist.

- [ ] **Step 3: Add rational parser to `FrameRateUtils`**

Add this private parser near `snapToStandardRate`:

```kotlin
private fun parseProbeRational(value: String?): Float? {
    val normalized = value?.trim().orEmpty()
    if (normalized.isBlank()) return null
    val slash = normalized.indexOf('/')
    val parsed = if (slash >= 0) {
        val numerator = normalized.substring(0, slash).toDoubleOrNull() ?: return null
        val denominator = normalized.substring(slash + 1).toDoubleOrNull() ?: return null
        if (numerator <= 0.0 || denominator <= 0.0) return null
        numerator / denominator
    } else {
        normalized.toDoubleOrNull() ?: return null
    }
    val frameRate = parsed.toFloat()
    return if (isValidVideoFrameRate(frameRate)) frameRate else null
}
```

Add test accessor at the end of `FrameRateUtils` near other `ForTests` helpers:

```kotlin
internal fun parseProbeRationalForTests(value: String?): Float? = parseProbeRational(value)
```

- [ ] **Step 4: Run tests to verify parser passes**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.core.player.FrameRateUtilsTest'
```

Expected: parser tests pass, unless unrelated unit-test source compilation failures block the test task. If blocked by unrelated tests, run `./gradlew :app:compileUniversalDebugKotlin` and document the unrelated compile failures.

- [ ] **Step 5: Commit parser**

```bash
git add app/src/main/java/com/nexio/tv/core/player/FrameRateUtils.kt app/src/test/java/com/nexio/tv/core/player/FrameRateUtilsTest.kt
git commit -m "test(player): parse ffmpeg probe frame rates"
```

---

## Task 3: Replace AFR NextLib Detection With FFmpeg Metadata Detection

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/FrameRateUtils.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAfrPreflight.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/player/FrameRateUtilsTest.kt`

- [ ] **Step 1: Write failing FFmpeg metadata detection test**

Add to `FrameRateUtilsTest.kt`:

```kotlin
@Test
fun `parse ffmpeg stream metadata returns frame rate and resolution`() {
    val detection = FrameRateUtils.parseFfmpegStreamMetadataForTests(
        """
        {
          "streams": [
            {"codec_type":"audio","codec_name":"truehd"},
            {"codec_type":"video","codec_name":"hevc","width":3840,"height":2160,"avg_frame_rate":"24000/1001","r_frame_rate":"24/1"}
          ]
        }
        """.trimIndent()
    )

    assertEquals(24000f / 1001f, detection!!.raw, 0.0001f)
    assertEquals(24000f / 1001f, detection.snapped, 0.0001f)
    assertEquals(3840, detection.videoWidth)
    assertEquals(2160, detection.videoHeight)
}
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.core.player.FrameRateUtilsTest'
```

Expected: fails because `parseFfmpegStreamMetadataForTests` does not exist.

- [ ] **Step 3: Add FFmpeg metadata parser**

In `FrameRateUtils.kt`, import:

```kotlin
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import com.google.gson.JsonParser
```

Add this parser:

```kotlin
private fun parseFfmpegStreamMetadata(json: String?): FrameRateDetection? {
    if (json.isNullOrBlank()) return null
    val streams = runCatching {
        JsonParser.parseString(json)
            .asJsonObject
            .getAsJsonArray("streams")
            .orEmpty()
            .mapNotNull { element ->
                element?.asJsonObject
            }
    }.getOrNull() ?: return null

    val video = streams.firstOrNull { stream ->
        stream.get("codec_type")?.asString.equals("video", ignoreCase = true)
    } ?: return null

    val width = video.get("width")?.asInt?.takeIf { it > 0 }
    val height = video.get("height")?.asInt?.takeIf { it > 0 }
    val measured = parseProbeRational(video.get("avg_frame_rate")?.asString)
        ?: parseProbeRational(video.get("r_frame_rate")?.asString)
        ?: return null

    return FrameRateDetection(
        raw = measured,
        snapped = snapToStandardRate(measured),
        videoWidth = width,
        videoHeight = height
    )
}
```

Add test accessor:

```kotlin
internal fun parseFfmpegStreamMetadataForTests(json: String?): FrameRateDetection? =
    parseFfmpegStreamMetadata(json)
```

- [ ] **Step 4: Add FFmpeg probe wrapper**

Add this public function to `FrameRateUtils`:

```kotlin
fun detectFrameRateFromFfmpegProbe(
    sourceUrl: String,
    headers: Map<String, String> = emptyMap()
): FrameRateDetection? {
    val headerBlob = headers
        .filterKeys { !it.equals("Range", ignoreCase = true) }
        .entries
        .joinToString(separator = "") { (key, value) -> "$key: $value\r\n" }
        .ifBlank { null }

    return runCatching {
        parseFfmpegStreamMetadata(
            FfmpegLibrary.probeDolbyVisionStreamMetadataJson(sourceUrl, headerBlob)
        )
    }.getOrElse { error ->
        Log.w(TAG, "FFmpeg frame rate probe failed: ${error.message}")
        null
    }
}
```

- [ ] **Step 5: Prefer FFmpeg in source detection**

Change `detectFrameRateFromSource` to:

```kotlin
fun detectFrameRateFromSource(
    context: Context,
    sourceUrl: String,
    headers: Map<String, String> = emptyMap()
): FrameRateDetection? {
    detectFrameRateFromFfmpegProbe(sourceUrl, headers)?.let { return it }
    return detectFrameRateFromExtractor(context, sourceUrl, headers)
}
```

Keep `detectFrameRateFromNextLib` in the file for phase-2 investigation unless task 8 removes the dependency.

- [ ] **Step 6: Remove NextLib preflight branch**

In `PlayerRuntimeControllerAfrPreflight.kt`, replace the 60-second NextLib plus 5.5-second extractor branch with:

```kotlin
private const val AFR_PREFLIGHT_PROBE_TIMEOUT_MS = 6500L
```

Then replace the `nextLibDetection` / fallback block with:

```kotlin
val detection = withTimeoutOrNull(AFR_PREFLIGHT_PROBE_TIMEOUT_MS) {
    withContext(Dispatchers.IO) {
        FrameRateUtils.detectFrameRateFromSource(
            context = context,
            sourceUrl = url,
            headers = probeHeaders
        )
    }
}
```

Update the failure log to:

```kotlin
Log.w(
    PlayerRuntimeController.TAG,
    "AFR preflight probe timed out/failed after ${AFR_PREFLIGHT_PROBE_TIMEOUT_MS}ms"
)
```

- [ ] **Step 7: Run compile verification**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin
```

Expected: compile succeeds.

- [ ] **Step 8: Commit FFmpeg AFR probe switch**

```bash
git add app/src/main/java/com/nexio/tv/core/player/FrameRateUtils.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAfrPreflight.kt app/src/test/java/com/nexio/tv/core/player/FrameRateUtilsTest.kt
git commit -m "fix(player): use ffmpeg metadata for afr probing"
```

---

## Task 4: Preserve Dolby Vision Probe Compatibility With New JSON Fields

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/DolbyVisionAutoPlayGate.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/player/FfmpegDolbyVisionProfileProbeTest.kt`

- [ ] **Step 1: Write compatibility test**

Add to `FfmpegDolbyVisionProfileProbeTest.kt`:

```kotlin
@Test
fun `stream metadata ignores frame metadata for dolby vision decision`() = runBlocking {
    val probe = FfmpegDolbyVisionProfileProbe(
        backend = fakeBackend(
            streamMetadataJson = """
                {
                  "streams": [
                    {
                      "codec_type":"video",
                      "codec_name":"hevc",
                      "width":3840,
                      "height":2160,
                      "avg_frame_rate":"24000/1001",
                      "r_frame_rate":"24/1",
                      "color_transfer":"smpte2084",
                      "color_primaries":"bt2020",
                      "dv_profile":7
                    },
                    {"codec_type":"audio","codec_name":"truehd"}
                  ]
                }
            """.trimIndent()
        )
    )

    val result = probe.probe(context, "https://example.com/test.mkv", null, "test.mkv")

    assertEquals(DolbyVisionProfileProbeStatus.DETECTED, result.status)
    assertEquals(7, result.profileNumber)
    assertEquals("hevc", result.videoCodec)
    assertEquals("truehd", result.audioCodec)
}
```

- [ ] **Step 2: Run test**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.core.player.FfmpegDolbyVisionProfileProbeTest'
```

Expected: pass if unit-test sources compile. If blocked by unrelated test-source compile failures, document the blocker and run `./gradlew :app:compileUniversalDebugKotlin`.

- [ ] **Step 3: Optionally retain fields in model**

If the test passes without code changes, leave `StreamProbeStreamMetadata` unchanged. If parsing fails, extend it:

```kotlin
private data class StreamProbeStreamMetadata(
    val codecType: String,
    val codecName: String?,
    val colorTransfer: String?,
    val colorPrimaries: String?,
    val dvProfile: Int?,
    val hdr10Plus: Boolean,
    val width: Int? = null,
    val height: Int? = null,
    val avgFrameRate: String? = null,
    val rFrameRate: String? = null
)
```

Then populate the optional fields in `parseStreamMetadataProbeResult`.

- [ ] **Step 4: Commit compatibility coverage**

```bash
git add app/src/main/java/com/nexio/tv/core/player/DolbyVisionAutoPlayGate.kt app/src/test/java/com/nexio/tv/core/player/FfmpegDolbyVisionProfileProbeTest.kt
git commit -m "test(player): keep dv probe compatible with frame metadata"
```

---

## Task 5: Read Android Device-Level AFR Status

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/player/AndroidFrameRateSettings.kt`
- Test: `app/src/test/java/com/nexio/tv/core/player/AndroidFrameRateSettingsTest.kt`

- [ ] **Step 1: Write status-label tests**

Create `AndroidFrameRateSettingsTest.kt`:

```kotlin
package com.nexio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidFrameRateSettingsTest {
    @Test
    fun `device afr disabled label`() {
        assertEquals(
            "Android: disabled",
            AndroidFrameRateSettings.statusLabelForTests(AndroidFrameRateSettings.Status.Disabled)
        )
    }

    @Test
    fun `device afr seamless only label`() {
        assertEquals(
            "Android: seamless only",
            AndroidFrameRateSettings.statusLabelForTests(AndroidFrameRateSettings.Status.SeamlessOnly)
        )
    }

    @Test
    fun `device afr always label`() {
        assertEquals(
            "Android: enabled",
            AndroidFrameRateSettings.statusLabelForTests(AndroidFrameRateSettings.Status.Always)
        )
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.core.player.AndroidFrameRateSettingsTest'
```

Expected: fails because `AndroidFrameRateSettings` does not exist.

- [ ] **Step 3: Implement device setting reader**

Create `AndroidFrameRateSettings.kt`:

```kotlin
package com.nexio.tv.core.player

import android.content.Context
import android.content.Intent
import android.provider.Settings

object AndroidFrameRateSettings {
    private const val MATCH_CONTENT_FRAME_RATE = "match_content_frame_rate"

    enum class Status {
        Unknown,
        Disabled,
        SeamlessOnly,
        Always
    }

    fun readStatus(context: Context): Status {
        val value = runCatching {
            Settings.Secure.getInt(context.contentResolver, MATCH_CONTENT_FRAME_RATE, 0)
        }.getOrDefault(0)
        return when (value) {
            0 -> Status.Disabled
            1 -> Status.SeamlessOnly
            2 -> Status.Always
            3 -> Status.Always
            else -> Status.Unknown
        }
    }

    fun statusLabel(status: Status): String {
        return when (status) {
            Status.Unknown -> "Android: unknown"
            Status.Disabled -> "Android: disabled"
            Status.SeamlessOnly -> "Android: seamless only"
            Status.Always -> "Android: enabled"
        }
    }

    fun displaySettingsIntent(): Intent {
        return Intent(Settings.ACTION_DISPLAY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    internal fun statusLabelForTests(status: Status): String = statusLabel(status)
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.core.player.AndroidFrameRateSettingsTest'
```

Expected: pass if unit-test source compilation is healthy.

- [ ] **Step 5: Commit device setting reader**

```bash
git add app/src/main/java/com/nexio/tv/core/player/AndroidFrameRateSettings.kt app/src/test/java/com/nexio/tv/core/player/AndroidFrameRateSettingsTest.kt
git commit -m "feat(player): read android frame-rate setting"
```

---

## Task 6: Update AFR Settings UI And Remove Resolution Toggle

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAudioSettings.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add strings**

In `app/src/main/res/values/strings.xml`, add:

```xml
<string name="playback_afr_android_open">Open Android display settings</string>
<string name="playback_afr_android_open_sub">Configure the device match-content frame-rate setting used by Android TV.</string>
<string name="playback_afr_android_disabled">Android: disabled</string>
<string name="playback_afr_android_seamless">Android: seamless only</string>
<string name="playback_afr_android_enabled">Android: enabled</string>
<string name="playback_afr_android_unknown">Android: unknown</string>
```

- [ ] **Step 2: Expose status from ViewModel**

In `PlaybackSettingsViewModel.kt`, import `AndroidFrameRateSettings` and add:

```kotlin
fun androidFrameRateStatus(): AndroidFrameRateSettings.Status {
    return AndroidFrameRateSettings.readStatus(context)
}
```

If the ViewModel does not currently keep `context`, add an `applicationContext` dependency following existing constructor patterns instead of using an Activity reference.

- [ ] **Step 3: Read status in screen**

In `PlaybackSettingsScreen.kt`, compute status when composing settings:

```kotlin
val androidFrameRateStatus = remember { mutableStateOf(viewModel.androidFrameRateStatus()) }
val lifecycleOwner = LocalLifecycleOwner.current
DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            androidFrameRateStatus.value = viewModel.androidFrameRateStatus()
        }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
}
```

Add a launcher/action:

```kotlin
val context = LocalContext.current
val openAndroidDisplaySettings = {
    runCatching {
        context.startActivity(AndroidFrameRateSettings.displaySettingsIntent())
    }.onFailure {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
```

- [ ] **Step 4: Pass status and action through sections**

Add parameters to `PlaybackSettingsSections` and `playbackAudioSettingsItems`:

```kotlin
androidFrameRateStatus: AndroidFrameRateSettings.Status,
onOpenAndroidDisplaySettings: () -> Unit,
```

Build subtitle text:

```kotlin
val androidAfrLabel = when (androidFrameRateStatus) {
    AndroidFrameRateSettings.Status.Disabled -> stringResource(R.string.playback_afr_android_disabled)
    AndroidFrameRateSettings.Status.SeamlessOnly -> stringResource(R.string.playback_afr_android_seamless)
    AndroidFrameRateSettings.Status.Always -> stringResource(R.string.playback_afr_android_enabled)
    AndroidFrameRateSettings.Status.Unknown -> stringResource(R.string.playback_afr_android_unknown)
}
```

Then make the AFR header description:

```kotlin
description = "$frameRateMatchingLabel • $androidAfrLabel"
```

- [ ] **Step 5: Remove resolution matching toggle from expanded AFR UI**

Change `FrameRateMatchingModeOptions` signature from:

```kotlin
resolutionMatchingEnabled: Boolean,
onSetResolutionMatchingEnabled: (Boolean) -> Unit,
```

to no resolution parameters. Delete the `ToggleSettingsItem` block with:

```kotlin
title = stringResource(R.string.playback_resolution_matching)
```

- [ ] **Step 6: Add Android settings row under AFR options**

At the bottom of `FrameRateMatchingModeOptions`, add:

```kotlin
Spacer(modifier = Modifier.height(8.dp))

SettingsActionItem(
    icon = Icons.Default.OpenInNew,
    title = stringResource(R.string.playback_afr_android_open),
    subtitle = stringResource(R.string.playback_afr_android_open_sub),
    onClick = onOpenAndroidDisplaySettings,
    onFocused = onFocused,
    enabled = enabled
)
```

If `SettingsActionItem` does not exist, use the local clickable row pattern already used in settings screens. Do not create a new visual style.

- [ ] **Step 7: Compile UI**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin
```

Expected: compile succeeds.

- [ ] **Step 8: Commit UI changes**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAudioSettings.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt app/src/main/res/values/strings.xml
git commit -m "feat(settings): show android frame-rate status"
```

---

## Task 7: Use Device Capability Instead Of User Resolution Setting

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/AndroidFrameRateSettings.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAfrPreflight.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt`

- [ ] **Step 1: Add capability helper**

In `AndroidFrameRateSettings.kt`, add:

```kotlin
fun canRequestResolutionSwitch(context: Context): Boolean {
    return readStatus(context) != Status.Disabled
}
```

This does not guarantee the TV will switch resolution; it only gates Nexio from offering a separate user option. Android and the display policy remain authoritative.

- [ ] **Step 2: Use helper in AFR preflight**

In `PlayerRuntimeControllerAfrPreflight.kt`, ignore the passed `resolutionMatchingEnabled` for mode selection and compute:

```kotlin
val allowResolutionSwitch = AndroidFrameRateSettings.canRequestResolutionSwitch(context)
```

Pass:

```kotlin
resolutionMatchingEnabled = allowResolutionSwitch
```

to `FrameRateUtils.matchFrameRateAndWait`.

- [ ] **Step 3: Use helper in track-based AFR fallback**

In `PlayerRuntimeControllerTracks.kt`, replace:

```kotlin
resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled
```

with:

```kotlin
resolutionMatchingEnabled = AndroidFrameRateSettings.canRequestResolutionSwitch(context)
```

Import `AndroidFrameRateSettings`.

- [ ] **Step 4: Keep stored setting but stop using it**

Do not delete `resolutionMatchingEnabled` from `PlayerSettingsDataStore` in this task. It may exist in synced settings and persisted profiles. Stop presenting it in the UI and stop using it for AFR decisions.

- [ ] **Step 5: Compile**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin
```

Expected: compile succeeds.

- [ ] **Step 6: Commit resolution policy cleanup**

```bash
git add app/src/main/java/com/nexio/tv/core/player/AndroidFrameRateSettings.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAfrPreflight.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt
git commit -m "refactor(player): defer resolution switching to android policy"
```

---

## Task 8: Investigate Remaining NextLib Usage And Decide Removal Or Repair

**Files:**
- Create: `docs/superpowers/reports/2026-04-14-nextlib-usage-investigation.md`
- Possibly modify: `app/build.gradle.kts`

- [ ] **Step 1: Search all NextLib usage**

Run:

```bash
rg -n "nextlib|MediaInfoBuilder|io.github.anilbeesetti" app media gradle build.gradle.kts settings.gradle.kts
```

Expected current important hits:

```text
app/build.gradle.kts
app/src/main/java/com/nexio/tv/core/player/FrameRateUtils.kt
```

- [ ] **Step 2: Write investigation report**

Create `docs/superpowers/reports/2026-04-14-nextlib-usage-investigation.md` with this structure:

```markdown
# NextLib Usage Investigation

## Root Cause

NextLib `libmediainfo.so` requires FFmpeg symbol versions from `LIBAVCODEC_60`, while the final APK's merged `libavcodec.so` exports `LIBAVCODEC_62`. Android resolves shared libraries by name, so `libmediainfo.so` binds to an incompatible `libavcodec.so`.

## Current Usage

- `FrameRateUtils.detectFrameRateFromNextLib`: used only for AFR probing before this plan.
- `app/build.gradle.kts`: declares `io.github.anilbeesetti:nextlib-mediainfo:1.9.1-0.11.0`.

## Recommendation

Remove NextLib if no other runtime path uses it after AFR switches to FFmpeg metadata. If another feature needs it, isolate its FFmpeg libraries by rebuilding NextLib with unique library names or align all FFmpeg-native dependencies to the same ABI/symbol version.
```

- [ ] **Step 3: Decide dependency action**

If `rg` confirms no production usage remains after Task 3, remove:

```kotlin
implementation("io.github.anilbeesetti:nextlib-mediainfo:1.9.1-0.11.0")
```

from `app/build.gradle.kts`, and remove NextLib imports/functions from `FrameRateUtils.kt`.

If production usage remains, keep the dependency and add a report section:

```markdown
## Phase 2 Repair Options

1. Upgrade to `nextlib-mediainfo:1.9.3-0.12.0` and verify symbol versions.
2. Rebuild NextLib/native MediaInfo with renamed FFmpeg libraries.
3. Replace remaining NextLib calls with the forked FFmpeg probe.
```

- [ ] **Step 4: Verify dependency removal or retained state**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin
```

Expected: compile succeeds.

If dependency was removed, also run:

```bash
rg -n "nextlib|MediaInfoBuilder|io.github.anilbeesetti" app/src/main app/build.gradle.kts
```

Expected: no production usage remains.

- [ ] **Step 5: Commit investigation**

If NextLib removed:

```bash
git add docs/superpowers/reports/2026-04-14-nextlib-usage-investigation.md app/build.gradle.kts app/src/main/java/com/nexio/tv/core/player/FrameRateUtils.kt
git commit -m "chore(player): remove broken nextlib probe dependency"
```

If NextLib retained:

```bash
git add docs/superpowers/reports/2026-04-14-nextlib-usage-investigation.md
git commit -m "docs(player): record nextlib native conflict"
```

---

## Task 9: Device Verification On Google TV Streamer

**Files:**
- No source changes.

- [ ] **Step 1: Build and install the changed app**

Run the repo's normal install command for the active variant. If no wrapper exists, use:

```bash
./gradlew :app:installUniversalDebug
```

Expected: install succeeds.

- [ ] **Step 2: Confirm Android device AFR status**

Run:

```bash
adb -s 192.168.50.58:5555 shell settings get secure match_content_frame_rate
```

Expected: `1`, `2`, or `3`. `0` means Android device-level match-content frame rate is disabled.

- [ ] **Step 3: Start the same test stream**

Use the app UI to start the same high-bitrate 23.976/24 fps stream used during diagnosis.

- [ ] **Step 4: Confirm FFmpeg probe is used**

Run:

```bash
adb -s 192.168.50.58:5555 logcat -c
sleep 15
adb -s 192.168.50.58:5555 logcat -d | grep -i -E 'AFR|FFmpeg frame|FrameRateUtils|Switching display|Display mode switch'
```

Expected: no `NextLib frame rate probe failed` log. Expected to see either a successful display switch log or a clear Android policy refusal.

- [ ] **Step 5: Confirm display mode**

Run:

```bash
adb -s 192.168.50.58:5555 shell dumpsys display | grep -i -E 'mActiveModeId|modeId|renderFrameRate|frameRateOverride|mRefreshRateChangeable' | head -n 30
```

Expected after a successful 24 fps switch: active mode is one of the TV's 23.976/24 Hz modes, not mode 38 at 60 Hz.

- [ ] **Step 6: Confirm video cadence**

Find the current SurfaceView id:

```bash
adb -s 192.168.50.58:5555 shell dumpsys SurfaceFlinger --list | grep -i 'SurfaceView\\[com.nexio.tv'
```

Clear and sample:

```bash
adb -s 192.168.50.58:5555 shell 'dumpsys SurfaceFlinger --latency-clear "SurfaceView[com.nexio.tv/com.nexio.tv.MainActivity](BLAST)#ID"'
sleep 30
adb -s 192.168.50.58:5555 shell 'dumpsys SurfaceFlinger --latency "SurfaceView[com.nexio.tv/com.nexio.tv.MainActivity](BLAST)#ID"' | awk 'NR==1 {period=$1; next} NF==3 && $1>0 { if (prev>0) { d=($1-prev)/1000000; bucket[int(d+0.5)]++ ; total++ } prev=$1 } END { print "period_ns=" period; print "intervals=" total; for (b in bucket) print b "ms=" bucket[b] }'
```

Expected improvement: the previous alternating `33ms` / `50ms` cadence disappears. For 23.976/24 Hz display mode, intervals should cluster around one video/display frame duration.

---

## Self-Review

- Spec coverage:
  - FFmpeg replacement for AFR: Tasks 1-3.
  - Probe before playback: Task 3 changes startup AFR preflight to use FFmpeg source detection before mode switch.
  - Existing lightweight ffprobe expanded with frame rate/resolution: Task 1.
  - AFR menu title remains and subheader shows Android config: Task 6.
  - Android settings open action: Task 6.
  - Remove in-app auto resolution control in favor of Android/device policy: Tasks 6-7.
  - NextLib usage investigation and phase-2 dependency decision: Task 8.

- Placeholder scan:
  - No "TBD", "TODO", "implement later", or undefined follow-up-only requirements remain.

- Type consistency:
  - `FrameRateUtils.FrameRateDetection` remains the shared result type.
  - `AndroidFrameRateSettings.Status` is used consistently by settings UI and tests.
  - Native JSON field names match Kotlin parser names: `width`, `height`, `avg_frame_rate`, `r_frame_rate`.

