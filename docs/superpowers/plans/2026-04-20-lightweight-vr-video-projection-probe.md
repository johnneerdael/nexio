# Lightweight VR Video Projection Probe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect 180/360/3D video projection metadata before playback on VR-capable devices without adding any FFmpeg probe cost to normal Android TV playback, then route spatial content to a Meta Spatial SDK playback path.

**Architecture:** Keep `FfmpegStreamMetadataProbe` unchanged for the existing DV/AFR/shared metadata path. Add a separate lightweight VR projection probe that only runs when the device is VR-capable; it asks native FFmpeg for `width`, `height`, `sample_aspect_ratio`, `display_aspect_ratio`, `stereo_mode`, and `SPHERICAL-VIDEO`, then classifies the stream. On Quest builds, spatial classifications launch a Meta Spatial SDK immersive playback activity; normal Android TV builds and non-spatial streams continue through the existing player.

**Tech Stack:** Android/Kotlin, Media3 fork FFmpeg JNI, Gson, Kotlin coroutines, Meta Spatial SDK, JUnit.

---

## Scope Check

This plan covers the lightweight pre-start detection layer and the app routing seam for Meta Spatial SDK playback. The first implementation milestone should stop after the detection/routing seam if the build graph changes are risky; the Meta renderer tasks are written as a second milestone in this same plan because the detector exists to feed that path.

The tested sample URL:

```text
https://0-cdn2-ovh-fra.energycdn.com/cdn3sto/smarthorse-sto/67bd0c0e9c8317.25385184/702605212/1776649585/07e92bf69d0cdec18162dff5daca42a3941dcd9f/3dc8bb5c5192d8dbf92fc24c276c95c319d9a70a6b99b64618abd042a6559f9e/SLR_SLR%20Originals_SLR%20Getaway%2C%20Part%201_%20The%20Visit_original_23100_MKX200_FB360.mkv
```

Minimal ffprobe command that surfaces the necessary data:

```bash
ffprobe -v error -select_streams v:0 \
  -show_entries stream=width,height,sample_aspect_ratio,display_aspect_ratio:stream_tags=stereo_mode,SPHERICAL-VIDEO \
  -of json "$URL"
```

Observed classification for that sample:

```text
width=5800
height=2900
display_aspect_ratio=2:1
SPHERICAL-VIDEO ProjectionType=equirectangular
SPHERICAL-VIDEO StereoMode=mono
classification=VR_360_MONO
```

## File Structure

- Create `app/src/main/java/com/nexio/tv/core/player/VrDeviceCapability.kt`
  - Determines whether the current device should pay the VR probe cost.
  - Uses package manager feature checks first, then Quest/Oculus/Meta model fallback.

- Create `app/src/main/java/com/nexio/tv/core/player/VrVideoProjection.kt`
  - Pure Kotlin metadata model, spherical XML parser, filename fallback helpers, and classification policy.

- Create `app/src/main/java/com/nexio/tv/core/player/VrVideoProjectionProbe.kt`
  - Calls the native VR-only FFmpeg probe when the device gate allows it.
  - Caches by URL + request headers blob, following the existing `FfmpegStreamMetadataProbe` style.

- Modify `media/libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegLibrary.java`
  - Adds `probeVrVideoProjectionMetadataJson()`.
  - Keeps `probeDolbyVisionStreamMetadataJson()` unchanged so normal shared probe payload stays stable.

- Modify `media/libraries/decoder_ffmpeg/src/main/jni/ffmain.cpp`
  - Adds a separate JNI function that emits only the VR projection subset.
  - Reuses existing URL fallback/open helpers and JSON escaping.

- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerUiState.kt`
  - Adds nullable `vrVideoProjectionDecision` for future renderer/UI consumers.

- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
  - Adds `currentVrVideoProjectionDecision`.

- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - Runs the VR projection probe only after confirming a VR-capable device and before ExoPlayer surface selection.

- Future follow-up `app/src/main/res/layout/exo_player_view_spherical.xml`
  - Can use Media3 `PlayerView` with `app:surface_type="spherical_gl_surface_view"` as an interim flat-window 360 player, based on the ProAndroidDev ExoPlayer VR article.
  - This is not part of this detection slice because true Quest immersion still needs the Meta Spatial SDK renderer.

- Create `quest-player/src/main/AndroidManifest.xml`
  - Quest-only manifest with HorizonOS/VR declarations and immersive activity entry points.

- Create `quest-player/build.gradle.kts`
  - Small Quest-specific app/module that depends on shared app/player code where feasible and applies the Meta Spatial SDK plugin/dependencies without changing normal Android TV packaging.

- Create `quest-player/src/main/java/com/nexio/tv/quest/QuestSpatialPlayerActivity.kt`
  - Meta Spatial SDK `AppSystemActivity` that receives URL, headers, title, and `VrVideoProjectionDecision`.
  - Creates a direct-to-surface ExoPlayer panel with `StereoMode.LeftRight`, `StereoMode.UpDown`, or mono.

- Create `app/src/main/java/com/nexio/tv/core/player/QuestSpatialPlayerLauncher.kt`
  - Launches the Quest immersive playback entrypoint when available.
  - Keeps the normal player path as fallback if the Quest activity is missing or fails to resolve.

- Modify `settings.gradle.kts`
  - Includes `:quest-player` only behind a Gradle property such as `ENABLE_QUEST_PLAYER=true` to avoid imposing Meta SDK setup on every developer/build.

- Create `app/src/test/java/com/nexio/tv/core/player/VrDeviceCapabilityTest.kt`
  - Tests feature/model based Quest detection.

- Create `app/src/test/java/com/nexio/tv/core/player/VrVideoProjectionTest.kt`
  - Tests sample JSON/XML classification and fallback behavior.

- Create `app/src/test/java/com/nexio/tv/core/player/VrVideoProjectionProbeTest.kt`
  - Tests the probe policy skips native work on non-VR devices and calls native work on VR devices.

---

### Task 1: Add Pure VR Projection Classifier

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/player/VrVideoProjection.kt`
- Test: `app/src/test/java/com/nexio/tv/core/player/VrVideoProjectionTest.kt`

- [ ] **Step 1: Write the failing classifier tests**

Create `app/src/test/java/com/nexio/tv/core/player/VrVideoProjectionTest.kt`:

```kotlin
package com.nexio.tv.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VrVideoProjectionTest {

    @Test
    fun `sample spherical mono metadata classifies as 360 mono vr`() {
        val metadata = VrVideoProjectionMetadata(
            width = 5800,
            height = 2900,
            sampleAspectRatio = "1:1",
            displayAspectRatio = "2:1",
            stereoModeTag = null,
            sphericalVideoXml = SAMPLE_SPHERICAL_XML
        )

        val decision = classifyVrVideoProjection(
            metadata = metadata,
            filename = "SLR_SLR Originals_SLR Getaway, Part 1_ The Visit_original_23100_MKX200_FB360.mkv"
        )

        assertEquals(VrVideoProjection.VR_360_MONO, decision.projection)
        assertEquals(VrVideoStereoMode.MONO, decision.stereoMode)
        assertEquals(VrVideoViewMode.VIEW_360, decision.viewMode)
        assertTrue(decision.shouldUseVrRenderer)
        assertEquals("spherical_xml:equirectangular:mono", decision.reason)
    }

    @Test
    fun `left right stereo mode tag classifies as sbs 3d without spherical metadata`() {
        val metadata = VrVideoProjectionMetadata(
            width = 3840,
            height = 1080,
            sampleAspectRatio = "1:1",
            displayAspectRatio = "32:9",
            stereoModeTag = "left_right",
            sphericalVideoXml = null
        )

        val decision = classifyVrVideoProjection(metadata, filename = "Movie.3D.SBS.mkv")

        assertEquals(VrVideoProjection.THREE_D_SBS, decision.projection)
        assertEquals(VrVideoStereoMode.LEFT_RIGHT, decision.stereoMode)
        assertEquals(VrVideoViewMode.RECTILINEAR, decision.viewMode)
        assertTrue(decision.shouldUseVrRenderer)
        assertEquals("stereo_mode_tag:left_right", decision.reason)
    }

    @Test
    fun `vr180 filename plus sbs geometry classifies as 180 sbs`() {
        val metadata = VrVideoProjectionMetadata(
            width = 5760,
            height = 2880,
            sampleAspectRatio = "1:1",
            displayAspectRatio = "2:1",
            stereoModeTag = null,
            sphericalVideoXml = null
        )

        val decision = classifyVrVideoProjection(metadata, filename = "Travel.VR180.SBS.5760x2880.mp4")

        assertEquals(VrVideoProjection.VR_180_SBS, decision.projection)
        assertEquals(VrVideoStereoMode.LEFT_RIGHT, decision.stereoMode)
        assertEquals(VrVideoViewMode.VIEW_180, decision.viewMode)
        assertTrue(decision.shouldUseVrRenderer)
        assertEquals("filename:vr180:sbs", decision.reason)
    }

    @Test
    fun `plain 2 to 1 dimensions without vr metadata stay unknown not sbs`() {
        val metadata = VrVideoProjectionMetadata(
            width = 3840,
            height = 1920,
            sampleAspectRatio = "1:1",
            displayAspectRatio = "2:1",
            stereoModeTag = null,
            sphericalVideoXml = null
        )

        val decision = classifyVrVideoProjection(metadata, filename = "Documentary.3840x1920.mp4")

        assertEquals(VrVideoProjection.UNKNOWN, decision.projection)
        assertEquals(VrVideoStereoMode.UNKNOWN, decision.stereoMode)
        assertEquals(VrVideoViewMode.UNKNOWN, decision.viewMode)
        assertFalse(decision.shouldUseVrRenderer)
        assertEquals("ambiguous_geometry:2:1", decision.reason)
    }

    @Test
    fun `flat widescreen content stays flat`() {
        val metadata = VrVideoProjectionMetadata(
            width = 3840,
            height = 2160,
            sampleAspectRatio = "1:1",
            displayAspectRatio = "16:9",
            stereoModeTag = null,
            sphericalVideoXml = null
        )

        val decision = classifyVrVideoProjection(metadata, filename = "Movie.2160p.WEB-DL.mkv")

        assertEquals(VrVideoProjection.FLAT, decision.projection)
        assertFalse(decision.shouldUseVrRenderer)
        assertEquals("flat:no_vr_metadata", decision.reason)
    }

    @Test
    fun `spherical xml parser extracts projection and stereo mode`() {
        val spherical = parseSphericalVideoMetadata(SAMPLE_SPHERICAL_XML)

        assertEquals(true, spherical?.spherical)
        assertEquals("equirectangular", spherical?.projectionType)
        assertEquals("mono", spherical?.stereoMode)
    }

    companion object {
        private val SAMPLE_SPHERICAL_XML = """
            <?xml version="1.0"?>
            <rdf:SphericalVideo xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:GSpherical="http://ns.google.com/videos/1.0/spherical/">
              <GSpherical:Spherical>true</GSpherical:Spherical>
              <GSpherical:Stitched>true</GSpherical:Stitched>
              <GSpherical:StitchingSoftware>Facebook 360 Spatial Workstation</GSpherical:StitchingSoftware>
              <GSpherical:ProjectionType>equirectangular</GSpherical:ProjectionType>
              <GSpherical:StereoMode>mono</GSpherical:StereoMode>
            </rdf:SphericalVideo>
        """.trimIndent()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.player.VrVideoProjectionTest'
```

Expected: FAIL with unresolved references such as `VrVideoProjectionMetadata` and `classifyVrVideoProjection`.

- [ ] **Step 3: Add the classifier implementation**

Create `app/src/main/java/com/nexio/tv/core/player/VrVideoProjection.kt`:

```kotlin
package com.nexio.tv.core.player

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale
import kotlin.math.abs

internal enum class VrVideoProjection {
    FLAT,
    VR_360_MONO,
    VR_360_SBS,
    VR_180_MONO,
    VR_180_SBS,
    THREE_D_SBS,
    UNKNOWN
}

internal enum class VrVideoStereoMode {
    MONO,
    LEFT_RIGHT,
    RIGHT_LEFT,
    TOP_BOTTOM,
    UNKNOWN
}

internal enum class VrVideoViewMode {
    RECTILINEAR,
    VIEW_180,
    VIEW_360,
    UNKNOWN
}

internal data class VrVideoProjectionMetadata(
    val width: Int?,
    val height: Int?,
    val sampleAspectRatio: String?,
    val displayAspectRatio: String?,
    val stereoModeTag: String?,
    val sphericalVideoXml: String?
)

internal data class SphericalVideoMetadata(
    val spherical: Boolean,
    val projectionType: String?,
    val stereoMode: String?
)

internal data class VrVideoProjectionDecision(
    val projection: VrVideoProjection,
    val stereoMode: VrVideoStereoMode,
    val viewMode: VrVideoViewMode,
    val shouldUseVrRenderer: Boolean,
    val reason: String
)

internal fun parseVrProjectionMetadataJson(json: String?): VrVideoProjectionMetadata? {
    if (json.isNullOrBlank()) return null
    val stream = runCatching {
        JsonParser.parseString(json)
            .asJsonObject
            .getAsJsonArray("streams")
            ?.firstOrNull()
            ?.asJsonObject
    }.getOrNull() ?: return null

    val tags = stream.getAsJsonObjectOrNull("tags")
    return VrVideoProjectionMetadata(
        width = stream.intOrNull("width"),
        height = stream.intOrNull("height"),
        sampleAspectRatio = stream.stringOrNull("sample_aspect_ratio"),
        displayAspectRatio = stream.stringOrNull("display_aspect_ratio"),
        stereoModeTag = tags?.stringOrNull("stereo_mode"),
        sphericalVideoXml = tags?.stringOrNull("SPHERICAL-VIDEO")
            ?: tags?.stringOrNull("SPHERICAL_VIDEO")
    )
}

internal fun classifyVrVideoProjection(
    metadata: VrVideoProjectionMetadata,
    filename: String?
): VrVideoProjectionDecision {
    val filenameHints = VrFilenameHints.from(filename)
    val spherical = parseSphericalVideoMetadata(metadata.sphericalVideoXml)
    val sphericalStereo = spherical?.stereoMode?.let(::parseStereoMode)
    val tagStereo = metadata.stereoModeTag?.let(::parseStereoMode)
    val stereoMode = firstKnown(tagStereo, sphericalStereo)

    if (spherical?.spherical == true) {
        val viewMode = when {
            filenameHints.view180 -> VrVideoViewMode.VIEW_180
            filenameHints.view360 -> VrVideoViewMode.VIEW_360
            else -> VrVideoViewMode.VIEW_360
        }
        return when (stereoMode) {
            VrVideoStereoMode.LEFT_RIGHT,
            VrVideoStereoMode.RIGHT_LEFT -> decision(
                projection = if (viewMode == VrVideoViewMode.VIEW_180) {
                    VrVideoProjection.VR_180_SBS
                } else {
                    VrVideoProjection.VR_360_SBS
                },
                stereoMode = stereoMode,
                viewMode = viewMode,
                reason = "spherical_xml:${spherical.projectionType.normalizedReasonValue()}:${stereoMode.reasonValue}"
            )
            VrVideoStereoMode.MONO,
            VrVideoStereoMode.UNKNOWN,
            VrVideoStereoMode.TOP_BOTTOM -> decision(
                projection = if (viewMode == VrVideoViewMode.VIEW_180) {
                    VrVideoProjection.VR_180_MONO
                } else {
                    VrVideoProjection.VR_360_MONO
                },
                stereoMode = if (stereoMode == VrVideoStereoMode.UNKNOWN) {
                    VrVideoStereoMode.MONO
                } else {
                    stereoMode
                },
                viewMode = viewMode,
                reason = "spherical_xml:${spherical.projectionType.normalizedReasonValue()}:${(spherical.stereoMode ?: "unknown").lowercase(Locale.US)}"
            )
        }
    }

    if (stereoMode == VrVideoStereoMode.LEFT_RIGHT || stereoMode == VrVideoStereoMode.RIGHT_LEFT) {
        return decision(
            projection = if (filenameHints.view180) {
                VrVideoProjection.VR_180_SBS
            } else if (filenameHints.view360) {
                VrVideoProjection.VR_360_SBS
            } else {
                VrVideoProjection.THREE_D_SBS
            },
            stereoMode = stereoMode,
            viewMode = when {
                filenameHints.view180 -> VrVideoViewMode.VIEW_180
                filenameHints.view360 -> VrVideoViewMode.VIEW_360
                else -> VrVideoViewMode.RECTILINEAR
            },
            reason = "stereo_mode_tag:${metadata.stereoModeTag?.lowercase(Locale.US) ?: stereoMode.reasonValue}"
        )
    }

    if (filenameHints.view180 && filenameHints.sbs) {
        return decision(
            projection = VrVideoProjection.VR_180_SBS,
            stereoMode = VrVideoStereoMode.LEFT_RIGHT,
            viewMode = VrVideoViewMode.VIEW_180,
            reason = "filename:vr180:sbs"
        )
    }

    if (filenameHints.view360 && filenameHints.sbs) {
        return decision(
            projection = VrVideoProjection.VR_360_SBS,
            stereoMode = VrVideoStereoMode.LEFT_RIGHT,
            viewMode = VrVideoViewMode.VIEW_360,
            reason = "filename:vr360:sbs"
        )
    }

    if (filenameHints.sbs) {
        return decision(
            projection = VrVideoProjection.THREE_D_SBS,
            stereoMode = VrVideoStereoMode.LEFT_RIGHT,
            viewMode = VrVideoViewMode.RECTILINEAR,
            reason = "filename:sbs"
        )
    }

    if (metadata.displayAspectRatio == "2:1" || metadata.approxAspectRatio(2f)) {
        return VrVideoProjectionDecision(
            projection = VrVideoProjection.UNKNOWN,
            stereoMode = VrVideoStereoMode.UNKNOWN,
            viewMode = VrVideoViewMode.UNKNOWN,
            shouldUseVrRenderer = false,
            reason = "ambiguous_geometry:2:1"
        )
    }

    return VrVideoProjectionDecision(
        projection = VrVideoProjection.FLAT,
        stereoMode = VrVideoStereoMode.MONO,
        viewMode = VrVideoViewMode.RECTILINEAR,
        shouldUseVrRenderer = false,
        reason = "flat:no_vr_metadata"
    )
}

internal fun parseSphericalVideoMetadata(xml: String?): SphericalVideoMetadata? {
    if (xml.isNullOrBlank()) return null
    val spherical = extractXmlTag(xml, "Spherical")?.equals("true", ignoreCase = true) == true
    val projectionType = extractXmlTag(xml, "ProjectionType")?.lowercase(Locale.US)
    val stereoMode = extractXmlTag(xml, "StereoMode")?.lowercase(Locale.US)
    if (!spherical && projectionType == null && stereoMode == null) return null
    return SphericalVideoMetadata(
        spherical = spherical,
        projectionType = projectionType,
        stereoMode = stereoMode
    )
}

private data class VrFilenameHints(
    val view180: Boolean,
    val view360: Boolean,
    val sbs: Boolean
) {
    companion object {
        fun from(filename: String?): VrFilenameHints {
            val normalized = filename.orEmpty().lowercase(Locale.US)
            return VrFilenameHints(
                view180 = normalized.contains("vr180") ||
                    Regex("""(^|[^0-9])180([^0-9]|$)""").containsMatchIn(normalized),
                view360 = normalized.contains("fb360") ||
                    normalized.contains("vr360") ||
                    Regex("""(^|[^0-9])360([^0-9]|$)""").containsMatchIn(normalized),
                sbs = Regex("""(^|[.\s_\-])(sbs|side.?by.?side|lr|left.?right)([.\s_\-]|$)""")
                    .containsMatchIn(normalized)
            )
        }
    }
}

private fun parseStereoMode(raw: String): VrVideoStereoMode {
    val normalized = raw.trim().lowercase(Locale.US).replace("-", "_")
    return when (normalized) {
        "mono", "none" -> VrVideoStereoMode.MONO
        "left_right", "left-right", "sbs", "side_by_side", "side-by-side" ->
            VrVideoStereoMode.LEFT_RIGHT
        "right_left", "right-left" -> VrVideoStereoMode.RIGHT_LEFT
        "top_bottom", "top-bottom", "bottom_top", "bottom-top", "tb", "ou" ->
            VrVideoStereoMode.TOP_BOTTOM
        else -> VrVideoStereoMode.UNKNOWN
    }
}

private fun firstKnown(
    first: VrVideoStereoMode?,
    second: VrVideoStereoMode?
): VrVideoStereoMode {
    return when {
        first != null && first != VrVideoStereoMode.UNKNOWN -> first
        second != null && second != VrVideoStereoMode.UNKNOWN -> second
        else -> VrVideoStereoMode.UNKNOWN
    }
}

private fun decision(
    projection: VrVideoProjection,
    stereoMode: VrVideoStereoMode,
    viewMode: VrVideoViewMode,
    reason: String
): VrVideoProjectionDecision {
    return VrVideoProjectionDecision(
        projection = projection,
        stereoMode = stereoMode,
        viewMode = viewMode,
        shouldUseVrRenderer = true,
        reason = reason
    )
}

private fun extractXmlTag(xml: String, localName: String): String? {
    val pattern = Regex("""<[^>]*:?$localName[^>]*>(.*?)</[^>]*:?$localName>""", RegexOption.IGNORE_CASE)
    return pattern.find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
}

private fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? {
    return get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}

private fun JsonObject.stringOrNull(key: String): String? {
    return runCatching { get(key)?.asString }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun JsonObject.intOrNull(key: String): Int? {
    return runCatching { get(key)?.asInt }.getOrNull()
}

private fun VrVideoProjectionMetadata.approxAspectRatio(target: Float): Boolean {
    val w = width?.takeIf { it > 0 } ?: return false
    val h = height?.takeIf { it > 0 } ?: return false
    return abs((w.toFloat() / h.toFloat()) - target) < 0.03f
}

private fun String?.normalizedReasonValue(): String {
    return this?.takeIf { it.isNotBlank() }?.lowercase(Locale.US) ?: "unknown"
}

private val VrVideoStereoMode.reasonValue: String
    get() = name.lowercase(Locale.US)
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.player.VrVideoProjectionTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/VrVideoProjection.kt app/src/test/java/com/nexio/tv/core/player/VrVideoProjectionTest.kt
git commit -m "feat: classify VR video projection metadata"
```

---

### Task 2: Add VR Device Gate

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/player/VrDeviceCapability.kt`
- Test: `app/src/test/java/com/nexio/tv/core/player/VrDeviceCapabilityTest.kt`

- [ ] **Step 1: Write the failing device gate tests**

Create `app/src/test/java/com/nexio/tv/core/player/VrDeviceCapabilityTest.kt`:

```kotlin
package com.nexio.tv.core.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VrDeviceCapabilityTest {

    @Test
    fun `headtracking feature marks device as vr capable`() {
        val identity = VrDeviceIdentity(
            manufacturer = "unknown",
            model = "unknown",
            features = setOf("android.hardware.vr.headtracking")
        )

        assertTrue(isVrCapableDevice(identity))
    }

    @Test
    fun `oculus boundaryless feature marks device as vr capable`() {
        val identity = VrDeviceIdentity(
            manufacturer = "unknown",
            model = "unknown",
            features = setOf("com.oculus.feature.BOUNDARYLESS_APP")
        )

        assertTrue(isVrCapableDevice(identity))
    }

    @Test
    fun `quest model fallback marks device as vr capable`() {
        val identity = VrDeviceIdentity(
            manufacturer = "Meta",
            model = "Quest 3",
            features = emptySet()
        )

        assertTrue(isVrCapableDevice(identity))
    }

    @Test
    fun `normal android tv is not vr capable`() {
        val identity = VrDeviceIdentity(
            manufacturer = "Amazon",
            model = "AFTSS",
            features = setOf("android.software.leanback")
        )

        assertFalse(isVrCapableDevice(identity))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.player.VrDeviceCapabilityTest'
```

Expected: FAIL with unresolved references such as `VrDeviceIdentity`.

- [ ] **Step 3: Add the device gate implementation**

Create `app/src/main/java/com/nexio/tv/core/player/VrDeviceCapability.kt`:

```kotlin
package com.nexio.tv.core.player

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

internal data class VrDeviceIdentity(
    val manufacturer: String?,
    val model: String?,
    val features: Set<String>
)

internal object VrDeviceCapability {
    private val vrFeatureNames = setOf(
        "android.hardware.vr.headtracking",
        "com.oculus.feature.BOUNDARYLESS_APP",
        "com.oculus.feature.RENDER_MODEL",
        "com.oculus.feature.PASSTHROUGH"
    )

    fun isVrCapable(context: Context): Boolean {
        val packageManager = context.packageManager
        val features = vrFeatureNames.filterTo(mutableSetOf()) { feature ->
            packageManager.hasSystemFeature(feature)
        }
        return isVrCapableDevice(
            VrDeviceIdentity(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                features = features
            )
        )
    }
}

internal fun isVrCapableDevice(identity: VrDeviceIdentity): Boolean {
    if (identity.features.any { it in knownVrFeatures }) return true

    val manufacturer = identity.manufacturer.orEmpty().lowercase(Locale.US)
    val model = identity.model.orEmpty().lowercase(Locale.US)
    return manufacturer.contains("oculus") ||
        manufacturer.contains("meta") && model.contains("quest") ||
        model.contains("oculus quest") ||
        model.contains("quest 2") ||
        model.contains("quest 3") ||
        model.contains("quest pro")
}

private val knownVrFeatures = setOf(
    PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE,
    "android.hardware.vr.headtracking",
    "com.oculus.feature.BOUNDARYLESS_APP",
    "com.oculus.feature.RENDER_MODEL",
    "com.oculus.feature.PASSTHROUGH"
)
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.player.VrDeviceCapabilityTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/VrDeviceCapability.kt app/src/test/java/com/nexio/tv/core/player/VrDeviceCapabilityTest.kt
git commit -m "feat: gate VR probes to headset devices"
```

---

### Task 3: Add Native Minimal VR Projection Probe

**Files:**
- Modify: `media/libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegLibrary.java`
- Modify: `media/libraries/decoder_ffmpeg/src/main/jni/ffmain.cpp`
- Test: compile via `./gradlew :app:compileUniversalDebugKotlin`

- [ ] **Step 1: Add the Java API and native declaration**

Modify `media/libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegLibrary.java`.

Add this public method immediately after `probeDolbyVisionStreamMetadataJson`:

```java
  /**
   * Probes the first video stream for the small VR projection subset.
   *
   * <p>This is intentionally separate from {@link #probeDolbyVisionStreamMetadataJson} so Android
   * TV playback does not pay tag extraction cost unless the app has already detected a VR-capable
   * device. The payload contains at most one stream with {@code width}, {@code height},
   * {@code sample_aspect_ratio}, {@code display_aspect_ratio}, {@code tags.stereo_mode}, and
   * {@code tags.SPHERICAL-VIDEO}.
   */
  @Nullable
  public static String probeVrVideoProjectionMetadataJson(
      String url, @Nullable String requestHeadersBlob) {
    if (!isAvailable()) {
      return null;
    }
    return ffmpegProbeVrVideoProjectionMetadataJson(url, requestHeadersBlob);
  }
```

Add this private native declaration at the bottom, after `ffmpegProbeDolbyVisionStreamMetadataJson`:

```java
  private static native @Nullable String ffmpegProbeVrVideoProjectionMetadataJson(
      String url, @Nullable String requestHeadersBlob);
```

- [ ] **Step 2: Add C++ helpers and JNI function**

Modify `media/libraries/decoder_ffmpeg/src/main/jni/ffmain.cpp`.

Add these helpers near the existing `rationalToJsonString` helper:

```cpp
static std::string ratioToJsonString(AVRational ratio) {
    if (ratio.num <= 0 || ratio.den <= 0) {
        return "";
    }
    int reduced_num = 0;
    int reduced_den = 0;
    av_reduce(&reduced_num, &reduced_den, ratio.num, ratio.den, INT_MAX);
    if (reduced_num <= 0 || reduced_den <= 0) {
        return "";
    }
    return std::to_string(reduced_num) + ":" + std::to_string(reduced_den);
}

static void appendJsonStringField(
        std::string *json,
        const char *name,
        const std::string &value) {
    if (value.empty()) {
        return;
    }
    *json += ",\"" + std::string(name) + "\":\"" + escapeJsonString(value) + "\"";
}

static std::string streamMetadataValue(AVDictionary *metadata, const char *key) {
    AVDictionaryEntry *entry = av_dict_get(metadata, key, nullptr, AV_DICT_MATCH_CASE);
    if (entry == nullptr || entry->value == nullptr || entry->value[0] == '\0') {
        return "";
    }
    return std::string(entry->value);
}
```

Add this JNI function after `Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_ffmpegProbeDolbyVisionStreamMetadataJson`:

```cpp
extern "C"
JNIEXPORT jstring JNICALL
Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_ffmpegProbeVrVideoProjectionMetadataJson(
        JNIEnv *env,
        jclass clazz,
        jstring url,
        jstring request_headers_blob) {
    (void) clazz;
    if (url == nullptr) {
        return nullptr;
    }

    const char *url_chars = env->GetStringUTFChars(url, nullptr);
    const char *headers_chars =
            request_headers_blob != nullptr
            ? env->GetStringUTFChars(request_headers_blob, nullptr)
            : nullptr;

    AVFormatContext *format_context = nullptr;
    avformat_network_init();

    std::vector<std::string> probe_urls;
    auto add_unique_probe_url = [&](const std::string &candidate) {
        if (candidate.empty()) {
            return;
        }
        if (std::find(probe_urls.begin(), probe_urls.end(), candidate) == probe_urls.end()) {
            probe_urls.push_back(candidate);
        }
    };

    add_unique_probe_url(url_chars);
    add_unique_probe_url(extractEmbeddedResolveUrl(url_chars));
    for (size_t i = 0; i < probe_urls.size(); ++i) {
        add_unique_probe_url(toHttpFallbackUrl(probe_urls[i]));
    }

    int open_result = -1;
    for (const auto &probe_url : probe_urls) {
        open_result = openInputForProbe(&format_context, probe_url.c_str(), headers_chars);
        if (open_result >= 0 && format_context != nullptr) {
            break;
        }
        format_context = nullptr;
    }

    std::string json = "{\"streams\":[";
    bool wrote_stream = false;

    if (open_result >= 0 && format_context != nullptr &&
        avformat_find_stream_info(format_context, nullptr) >= 0) {
        for (unsigned int i = 0; i < format_context->nb_streams; ++i) {
            AVStream *stream = format_context->streams[i];
            if (stream == nullptr || stream->codecpar == nullptr) {
                continue;
            }
            const AVCodecParameters *codecpar = stream->codecpar;
            if (codecpar->codec_type != AVMEDIA_TYPE_VIDEO) {
                continue;
            }

            json += "{";
            if (codecpar->width > 0) {
                json += "\"width\":" + std::to_string(codecpar->width);
            }
            if (codecpar->height > 0) {
                if (codecpar->width > 0) {
                    json += ",";
                }
                json += "\"height\":" + std::to_string(codecpar->height);
            }

            AVRational sample_aspect_ratio = av_guess_sample_aspect_ratio(
                    format_context,
                    stream,
                    nullptr);
            const std::string sar = ratioToJsonString(sample_aspect_ratio);
            appendJsonStringField(&json, "sample_aspect_ratio", sar);

            if (codecpar->width > 0 && codecpar->height > 0 &&
                sample_aspect_ratio.num > 0 && sample_aspect_ratio.den > 0) {
                AVRational display_aspect_ratio = av_mul_q(
                        AVRational{codecpar->width, codecpar->height},
                        sample_aspect_ratio);
                appendJsonStringField(
                        &json,
                        "display_aspect_ratio",
                        ratioToJsonString(display_aspect_ratio));
            }

            const std::string stereo_mode = streamMetadataValue(stream->metadata, "stereo_mode");
            const std::string spherical_video =
                    streamMetadataValue(stream->metadata, "SPHERICAL-VIDEO");
            if (!stereo_mode.empty() || !spherical_video.empty()) {
                json += ",\"tags\":{";
                bool wrote_tag = false;
                if (!stereo_mode.empty()) {
                    json += "\"stereo_mode\":\"" + escapeJsonString(stereo_mode) + "\"";
                    wrote_tag = true;
                }
                if (!spherical_video.empty()) {
                    if (wrote_tag) {
                        json += ",";
                    }
                    json += "\"SPHERICAL-VIDEO\":\"" + escapeJsonString(spherical_video) + "\"";
                }
                json += "}";
            }

            json += "}";
            wrote_stream = true;
            break;
        }
    }

    json += "]}";

    if (format_context != nullptr) {
        avformat_close_input(&format_context);
    }
    if (headers_chars != nullptr) {
        env->ReleaseStringUTFChars(request_headers_blob, headers_chars);
    }
    env->ReleaseStringUTFChars(url, url_chars);

    return wrote_stream ? env->NewStringUTF(json.c_str()) : nullptr;
}
```

- [ ] **Step 3: Compile to catch Java/JNI syntax errors**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin
```

Expected: PASS. If native C++ is compiled in the active local configuration, it should also compile; if Gradle skips native rebuild because the current local config uses prebuilt artifacts, continue to Step 4 and verify through a full assemble command.

- [ ] **Step 4: Build an APK variant to force packaging checks**

Run:

```bash
./gradlew :app:assembleUniversalDebug
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add media/libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegLibrary.java media/libraries/decoder_ffmpeg/src/main/jni/ffmain.cpp
git commit -m "feat: add lightweight VR projection ffmpeg probe"
```

---

### Task 4: Add VR Probe Wrapper and Policy Tests

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/player/VrVideoProjectionProbe.kt`
- Test: `app/src/test/java/com/nexio/tv/core/player/VrVideoProjectionProbeTest.kt`

- [ ] **Step 1: Write the failing probe policy tests**

Create `app/src/test/java/com/nexio/tv/core/player/VrVideoProjectionProbeTest.kt`:

```kotlin
package com.nexio.tv.core.player

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VrVideoProjectionProbeTest {

    @Test
    fun `probe skips backend on non vr device`() = runTest {
        val backend = RecordingBackend(
            json = """{"streams":[{"width":5800,"height":2900,"display_aspect_ratio":"2:1"}]}"""
        )
        val probe = VrVideoProjectionProbe(backend = backend)

        val decision = probe.probeIfVrCapable(
            vrCapable = false,
            url = "https://example.com/video.mkv",
            headers = emptyMap(),
            filename = "video.mkv"
        )

        assertNull(decision)
        assertEquals(0, backend.calls)
    }

    @Test
    fun `probe calls backend on vr device and classifies result`() = runTest {
        val backend = RecordingBackend(
            json = """
                {
                  "streams": [
                    {
                      "width": 5800,
                      "height": 2900,
                      "sample_aspect_ratio": "1:1",
                      "display_aspect_ratio": "2:1",
                      "tags": {
                        "SPHERICAL-VIDEO": "${SAMPLE_SPHERICAL_XML.jsonEscaped()}"
                      }
                    }
                  ]
                }
            """.trimIndent()
        )
        val probe = VrVideoProjectionProbe(backend = backend)

        val decision = probe.probeIfVrCapable(
            vrCapable = true,
            url = "https://example.com/fb360.mkv",
            headers = mapOf("Authorization" to "Bearer token", "Range" to "bytes=0-1"),
            filename = "Example.FB360.mkv"
        )

        assertEquals(1, backend.calls)
        assertEquals("Authorization: Bearer token\r\n", backend.lastHeaderBlob)
        assertEquals(VrVideoProjection.VR_360_MONO, decision?.projection)
    }

    private class RecordingBackend(
        private val json: String?
    ) : VrVideoProjectionProbeBackend {
        var calls = 0
        var lastHeaderBlob: String? = null

        override fun probeProjectionJson(url: String, requestHeadersBlob: String?): String? {
            calls += 1
            lastHeaderBlob = requestHeadersBlob
            return json
        }
    }

    companion object {
        private val SAMPLE_SPHERICAL_XML = """
            <?xml version="1.0"?>
            <rdf:SphericalVideo xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:GSpherical="http://ns.google.com/videos/1.0/spherical/">
              <GSpherical:Spherical>true</GSpherical:Spherical>
              <GSpherical:ProjectionType>equirectangular</GSpherical:ProjectionType>
              <GSpherical:StereoMode>mono</GSpherical:StereoMode>
            </rdf:SphericalVideo>
        """.trimIndent()
    }
}

private fun String.jsonEscaped(): String {
    return replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.player.VrVideoProjectionProbeTest'
```

Expected: FAIL with unresolved references such as `VrVideoProjectionProbe`.

- [ ] **Step 3: Add the probe wrapper**

Create `app/src/main/java/com/nexio/tv/core/player/VrVideoProjectionProbe.kt`:

```kotlin
package com.nexio.tv.core.player

import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface VrVideoProjectionProbeBackend {
    fun probeProjectionJson(url: String, requestHeadersBlob: String?): String?
}

internal object NativeVrVideoProjectionProbeBackend : VrVideoProjectionProbeBackend {
    override fun probeProjectionJson(url: String, requestHeadersBlob: String?): String? {
        return FfmpegLibrary.probeVrVideoProjectionMetadataJson(url, requestHeadersBlob)
    }
}

internal class VrVideoProjectionProbe(
    private val backend: VrVideoProjectionProbeBackend = NativeVrVideoProjectionProbeBackend
) {
    private val nativeProbeLock = Any()
    private val cache = object : LinkedHashMap<ProbeKey, VrVideoProjectionDecision>(
        MAX_CACHE_ENTRIES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ProbeKey, VrVideoProjectionDecision>?
        ): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    suspend fun probeIfVrCapable(
        vrCapable: Boolean,
        url: String,
        headers: Map<String, String>,
        filename: String?
    ): VrVideoProjectionDecision? = withContext(Dispatchers.IO) {
        if (!vrCapable || url.isBlank()) return@withContext null

        val headerBlob = headers.toVrProbeHeaderBlob()
        val key = ProbeKey(url = url, requestHeadersBlob = headerBlob, filename = filename)

        synchronized(nativeProbeLock) {
            cache[key]?.let { return@withContext it }
            val metadata = parseVrProjectionMetadataJson(
                backend.probeProjectionJson(url, headerBlob)
            ) ?: return@withContext null

            classifyVrVideoProjection(metadata = metadata, filename = filename)
                .also { cache[key] = it }
        }
    }

    private data class ProbeKey(
        val url: String,
        val requestHeadersBlob: String?,
        val filename: String?
    )

    companion object {
        private const val MAX_CACHE_ENTRIES = 12
    }
}

internal fun Map<String, String>.toVrProbeHeaderBlob(): String? {
    val entries = filterKeys { !it.equals("Range", ignoreCase = true) }
    if (entries.isEmpty()) return null
    return entries.entries.joinToString(separator = "\r\n", postfix = "\r\n") { (key, value) ->
        "$key: $value"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.player.VrVideoProjectionProbeTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/VrVideoProjectionProbe.kt app/src/test/java/com/nexio/tv/core/player/VrVideoProjectionProbeTest.kt
git commit -m "feat: run VR projection probe only on headsets"
```

---

### Task 5: Store VR Projection Decision During Player Startup

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerUiState.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Test: `app/src/test/java/com/nexio/tv/core/player/VrVideoProjectionProbeTest.kt`

- [ ] **Step 1: Add UI/runtime state fields**

Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerUiState.kt`.

Add this import:

```kotlin
import com.nexio.tv.core.player.VrVideoProjectionDecision
```

Add this field near the existing video quality fields:

```kotlin
    val vrVideoProjectionDecision: VrVideoProjectionDecision? = null,
```

Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`.

Add this import:

```kotlin
import com.nexio.tv.core.player.VrVideoProjectionDecision
import com.nexio.tv.core.player.VrVideoProjectionProbe
```

Add this internal property near `currentFilename`:

```kotlin
    internal var currentVrVideoProjectionDecision: VrVideoProjectionDecision? = null
    internal val vrVideoProjectionProbe = VrVideoProjectionProbe()
```

- [ ] **Step 2: Wire the gated probe into player startup**

Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`.

Add imports:

```kotlin
import com.nexio.tv.core.player.VrDeviceCapability
```

Inside `initializePlayer`, after `currentInternalPlayerEngine = playerSettings.internalPlayerEngine` and before the `LIBMPV` branch, add:

```kotlin
            currentVrVideoProjectionDecision = vrVideoProjectionProbe.probeIfVrCapable(
                vrCapable = VrDeviceCapability.isVrCapable(context),
                url = url,
                headers = headers,
                filename = currentFilename
            )
            _uiState.update {
                it.copy(vrVideoProjectionDecision = currentVrVideoProjectionDecision)
            }
```

This placement intentionally runs after settings are loaded and before the engine-specific initialization path. On non-VR devices the method returns before calling native FFmpeg.

- [ ] **Step 3: Run focused tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.player.VrVideoProjection*'
```

Expected: PASS.

- [ ] **Step 4: Run player compile check**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerUiState.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt
git commit -m "feat: capture VR projection decision before playback"
```

---

### Task 6: Verify Against Real Sample URL

**Files:**
- No code files unless a previous task failed and required fixes.

- [ ] **Step 1: Confirm minimal CLI probe output**

Run:

```bash
URL='https://0-cdn2-ovh-fra.energycdn.com/cdn3sto/smarthorse-sto/67bd0c0e9c8317.25385184/702605212/1776649585/07e92bf69d0cdec18162dff5daca42a3941dcd9f/3dc8bb5c5192d8dbf92fc24c276c95c319d9a70a6b99b64618abd042a6559f9e/SLR_SLR%20Originals_SLR%20Getaway%2C%20Part%201_%20The%20Visit_original_23100_MKX200_FB360.mkv'
ffprobe -v error -select_streams v:0 \
  -show_entries stream=width,height,sample_aspect_ratio,display_aspect_ratio:stream_tags=stereo_mode,SPHERICAL-VIDEO \
  -of json "$URL"
```

Expected output includes:

```text
"width": 5800
"height": 2900
"sample_aspect_ratio": "1:1"
"display_aspect_ratio": "2:1"
"SPHERICAL-VIDEO"
"GSpherical:ProjectionType>equirectangular"
"GSpherical:StereoMode>mono"
```

- [ ] **Step 2: Run all new unit tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.player.Vr*'
```

Expected: PASS.

- [ ] **Step 3: Run existing FFmpeg metadata regression tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.player.FfmpegStreamMetadataProbeTest' --tests 'com.nexio.tv.core.player.FfmpegDolbyVisionProfileProbeTest' --tests 'com.nexio.tv.core.player.FrameRateUtilsTest'
```

Expected: PASS. This confirms the normal shared probe path remains compatible.

- [ ] **Step 4: Build debug APK**

Run:

```bash
./gradlew :app:assembleUniversalDebug
```

Expected: PASS.

- [ ] **Step 5: Commit verification-only fixes if needed**

If Step 1 through Step 4 passed without code changes, skip this commit. If fixes were required, commit only the touched implementation/test files:

```bash
git add app/src/main/java/com/nexio/tv/core/player app/src/test/java/com/nexio/tv/core/player media/libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegLibrary.java media/libraries/decoder_ffmpeg/src/main/jni/ffmain.cpp app/src/main/java/com/nexio/tv/ui/screens/player
    git commit -m "fix: stabilize VR projection probe"
```

---

### Task 7: Add Quest Spatial Player Module Skeleton

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `quest-player/build.gradle.kts`
- Create: `quest-player/src/main/AndroidManifest.xml`
- Create: `quest-player/src/main/java/com/nexio/tv/quest/QuestSpatialPlayerActivity.kt`

- [ ] **Step 1: Add Meta Spatial SDK version catalog entries**

Modify `gradle/libs.versions.toml`.

Add these versions:

```toml
metaSpatialSdk = "0.12.0"
```

Add these libraries:

```toml
meta-spatial-sdk-base = { group = "com.meta.spatial", name = "meta-spatial-sdk", version.ref = "metaSpatialSdk" }
meta-spatial-sdk-vr = { group = "com.meta.spatial", name = "meta-spatial-sdk-vr", version.ref = "metaSpatialSdk" }
meta-spatial-sdk-toolkit = { group = "com.meta.spatial", name = "meta-spatial-sdk-toolkit", version.ref = "metaSpatialSdk" }
meta-spatial-sdk-compose = { group = "com.meta.spatial", name = "meta-spatial-sdk-compose", version.ref = "metaSpatialSdk" }
```

Add this plugin:

```toml
meta-spatial-plugin = { id = "com.meta.spatial.plugin", version.ref = "metaSpatialSdk" }
```

- [ ] **Step 2: Gate the Quest module behind a Gradle property**

Modify `settings.gradle.kts`.

Add this helper after `readUseMedia3SourceFlag()`:

```kotlin
fun readBooleanFlag(name: String, defaultValue: String = "false"): Boolean {
    val mergedLocalProps = Properties().apply {
        listOf("local.properties", "local.dev.properties").forEach { fileName ->
            val file = file(fileName)
            if (file.exists()) {
                file.inputStream().use { load(it) }
            }
        }
    }
    return parseBooleanProperty(
        providers.gradleProperty(name).orNull ?: mergedLocalProps.getProperty(name) ?: defaultValue
    )
}
```

Add this include after `include(":app")`:

```kotlin
if (readBooleanFlag("ENABLE_QUEST_PLAYER")) {
    include(":quest-player")
}
```

- [ ] **Step 3: Create the Quest module Gradle file**

Create `quest-player/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.meta.spatial.plugin)
}

android {
    namespace = "com.nexio.tv.quest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nexio.tv.quest"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndkVersion = "27.0.12077973"
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.common)

    implementation(libs.meta.spatial.sdk.base)
    implementation(libs.meta.spatial.sdk.vr)
    implementation(libs.meta.spatial.sdk.toolkit)
    implementation(libs.meta.spatial.sdk.compose)
}
```

- [ ] **Step 4: Create the Quest manifest**

Create `quest-player/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:horizonos="http://schemas.horizonos/sdk"
    android:installLocation="auto">

    <horizonos:uses-horizonos-sdk
        horizonos:minSdkVersion="69"
        horizonos:targetSdkVersion="69" />

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="com.oculus.permission.USE_SCENE" />

    <uses-feature
        android:name="android.hardware.vr.headtracking"
        android:required="true" />
    <uses-feature
        android:name="com.oculus.feature.BOUNDARYLESS_APP"
        android:required="false" />
    <uses-feature
        android:name="com.oculus.feature.PASSTHROUGH"
        android:required="false" />
    <uses-feature android:glEsVersion="0x00030001" />

    <application
        android:allowBackup="false"
        android:label="Nexio VR">
        <meta-data
            android:name="com.oculus.supportedDevices"
            android:value="quest2|questpro|quest3" />
        <meta-data
            android:name="com.oculus.vr.focusaware"
            android:value="true" />
        <uses-native-library
            android:name="libossdk.oculus.so"
            android:required="true" />

        <activity
            android:name=".QuestSpatialPlayerActivity"
            android:configChanges="screenSize|screenLayout|orientation|keyboardHidden|keyboard|navigation|uiMode"
            android:exported="true"
            android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="com.oculus.intent.category.VR" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 5: Create a minimal immersive activity**

Create `quest-player/src/main/java/com/nexio/tv/quest/QuestSpatialPlayerActivity.kt`:

```kotlin
package com.nexio.tv.quest

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.vr.VRFeature

class QuestSpatialPlayerActivity : AppSystemActivity() {
    private var player: ExoPlayer? = null

    override fun registerFeatures(): List<SpatialFeature> {
        return listOf(VRFeature(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_STREAM_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            prepare()
            playWhenReady = true
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STREAM_URL = "com.nexio.tv.quest.extra.STREAM_URL"
        const val EXTRA_TITLE = "com.nexio.tv.quest.extra.TITLE"
        const val EXTRA_PROJECTION = "com.nexio.tv.quest.extra.PROJECTION"
        const val EXTRA_STEREO_MODE = "com.nexio.tv.quest.extra.STEREO_MODE"
        const val EXTRA_VIEW_MODE = "com.nexio.tv.quest.extra.VIEW_MODE"
    }
}
```

This activity is intentionally a skeleton. It verifies the Quest module, Meta Spatial SDK plugin, manifest, and ExoPlayer construction compile before adding direct-to-surface panel rendering.

- [ ] **Step 6: Build the Quest module**

Run:

```bash
./gradlew -PENABLE_QUEST_PLAYER=true :quest-player:assembleDebug
```

Expected: PASS. If the Meta Spatial SDK plugin requires a locally installed Meta Spatial Editor CLI, configure the documented local SDK path and rerun the same command.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml quest-player
git commit -m "feat: add Quest spatial player module"
```

---

### Task 8: Add Direct-To-Surface Spatial Video Panel

**Files:**
- Modify: `quest-player/src/main/java/com/nexio/tv/quest/QuestSpatialPlayerActivity.kt`
- Create: `quest-player/src/main/java/com/nexio/tv/quest/QuestSpatialMedia.kt`

- [ ] **Step 1: Create projection mapping model**

Create `quest-player/src/main/java/com/nexio/tv/quest/QuestSpatialMedia.kt`:

```kotlin
package com.nexio.tv.quest

import com.meta.spatial.runtime.StereoMode

enum class QuestSpatialShape {
    RECTILINEAR,
    EQUIRECT_180,
    EQUIRECT_360
}

data class QuestSpatialMedia(
    val url: String,
    val title: String?,
    val shape: QuestSpatialShape,
    val stereoMode: StereoMode,
    val displayWidthPx: Int,
    val displayHeightPx: Int
)

fun questSpatialMediaFromIntent(activity: QuestSpatialPlayerActivity): QuestSpatialMedia? {
    val url = activity.intent.getStringExtra(QuestSpatialPlayerActivity.EXTRA_STREAM_URL)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val projection = activity.intent.getStringExtra(QuestSpatialPlayerActivity.EXTRA_PROJECTION).orEmpty()
    val stereo = activity.intent.getStringExtra(QuestSpatialPlayerActivity.EXTRA_STEREO_MODE).orEmpty()
    val viewMode = activity.intent.getStringExtra(QuestSpatialPlayerActivity.EXTRA_VIEW_MODE).orEmpty()

    val shape = when {
        viewMode == "VIEW_180" -> QuestSpatialShape.EQUIRECT_180
        viewMode == "VIEW_360" -> QuestSpatialShape.EQUIRECT_360
        projection == "THREE_D_SBS" -> QuestSpatialShape.RECTILINEAR
        else -> QuestSpatialShape.RECTILINEAR
    }

    val stereoMode = when (stereo) {
        "LEFT_RIGHT", "RIGHT_LEFT" -> StereoMode.LeftRight
        "TOP_BOTTOM" -> StereoMode.UpDown
        else -> StereoMode.None
    }

    return QuestSpatialMedia(
        url = url,
        title = activity.intent.getStringExtra(QuestSpatialPlayerActivity.EXTRA_TITLE),
        shape = shape,
        stereoMode = stereoMode,
        displayWidthPx = 3840,
        displayHeightPx = 2160
    )
}
```

- [ ] **Step 2: Replace skeleton playback with a direct-to-surface panel**

Modify `quest-player/src/main/java/com/nexio/tv/quest/QuestSpatialPlayerActivity.kt` to this complete file:

```kotlin
package com.nexio.tv.quest

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Equirect180ShapeOptions
import com.meta.spatial.toolkit.Equirect360ShapeOptions
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.vr.VRFeature
import java.util.concurrent.atomic.AtomicInteger

class QuestSpatialPlayerActivity : AppSystemActivity() {
    private val panelId = nextPanelId.incrementAndGet()
    private var player: ExoPlayer? = null
    private var media: QuestSpatialMedia? = null

    override fun registerFeatures(): List<SpatialFeature> {
        return listOf(VRFeature(this))
    }

    override fun registerPanels(): List<PanelRegistration> {
        val resolved = media ?: return emptyList()
        return listOf(
            VideoSurfacePanelRegistration(
                panelId,
                surfaceConsumer = { _, surface ->
                    player = ExoPlayer.Builder(this).build().apply {
                        setVideoSurface(surface)
                        setMediaItem(MediaItem.fromUri(Uri.parse(resolved.url)))
                        prepare()
                        playWhenReady = true
                    }
                },
                settingsCreator = {
                    MediaPanelSettings(
                        shape = when (resolved.shape) {
                            QuestSpatialShape.RECTILINEAR -> QuadShapeOptions(width = 3.2f, height = 1.8f)
                            QuestSpatialShape.EQUIRECT_180 -> Equirect180ShapeOptions(radius = 50f)
                            QuestSpatialShape.EQUIRECT_360 -> Equirect360ShapeOptions(radius = 50f)
                        },
                        display = PixelDisplayOptions(
                            width = resolved.displayWidthPx,
                            height = resolved.displayHeightPx
                        ),
                        rendering = MediaPanelRenderOptions(
                            stereoMode = resolved.stereoMode,
                            zIndex = if (resolved.shape == QuestSpatialShape.RECTILINEAR) 0 else -1
                        ),
                        style = PanelStyleOptions(themeResourceId = android.R.style.Theme_Translucent_NoTitleBar)
                    )
                }
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        media = questSpatialMediaFromIntent(this)
        if (media == null) {
            finish()
            return
        }
        super.onCreate(savedInstanceState)
    }

    override fun onSceneReady() {
        super.onSceneReady()
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    }

    override fun onVRReady() {
        super.onVRReady()
        Entity(panelId).setComponents(
            Panel(panelId),
            Transform(Pose(Vector3(0f, 1.4f, 2.4f)))
        )
    }

    override fun onDestroy() {
        player?.setVideoSurface(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STREAM_URL = "com.nexio.tv.quest.extra.STREAM_URL"
        const val EXTRA_TITLE = "com.nexio.tv.quest.extra.TITLE"
        const val EXTRA_PROJECTION = "com.nexio.tv.quest.extra.PROJECTION"
        const val EXTRA_STEREO_MODE = "com.nexio.tv.quest.extra.STEREO_MODE"
        const val EXTRA_VIEW_MODE = "com.nexio.tv.quest.extra.VIEW_MODE"

        private val nextPanelId = AtomicInteger(100_000)
    }
}
```

- [ ] **Step 3: Build the Quest module**

Run:

```bash
./gradlew -PENABLE_QUEST_PLAYER=true :quest-player:assembleDebug
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add quest-player/src/main/java/com/nexio/tv/quest/QuestSpatialPlayerActivity.kt quest-player/src/main/java/com/nexio/tv/quest/QuestSpatialMedia.kt
git commit -m "feat: render Quest spatial video surfaces"
```

---

### Task 9: Route Detected Spatial Content to Quest Player

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/player/QuestSpatialPlayerLauncher.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Test: `app/src/test/java/com/nexio/tv/core/player/QuestSpatialPlayerLauncherTest.kt`

- [ ] **Step 1: Write the failing launcher tests**

Create `app/src/test/java/com/nexio/tv/core/player/QuestSpatialPlayerLauncherTest.kt`:

```kotlin
package com.nexio.tv.core.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuestSpatialPlayerLauncherTest {

    @Test
    fun `launcher requires vr renderer decision`() {
        assertFalse(
            shouldLaunchQuestSpatialPlayer(
                vrCapable = true,
                decision = VrVideoProjectionDecision(
                    projection = VrVideoProjection.FLAT,
                    stereoMode = VrVideoStereoMode.MONO,
                    viewMode = VrVideoViewMode.RECTILINEAR,
                    shouldUseVrRenderer = false,
                    reason = "flat"
                )
            )
        )
    }

    @Test
    fun `launcher accepts spatial decision on vr device`() {
        assertTrue(
            shouldLaunchQuestSpatialPlayer(
                vrCapable = true,
                decision = VrVideoProjectionDecision(
                    projection = VrVideoProjection.VR_360_MONO,
                    stereoMode = VrVideoStereoMode.MONO,
                    viewMode = VrVideoViewMode.VIEW_360,
                    shouldUseVrRenderer = true,
                    reason = "spherical_xml:equirectangular:mono"
                )
            )
        )
    }

    @Test
    fun `launcher skips spatial decision on non vr device`() {
        assertFalse(
            shouldLaunchQuestSpatialPlayer(
                vrCapable = false,
                decision = VrVideoProjectionDecision(
                    projection = VrVideoProjection.VR_360_MONO,
                    stereoMode = VrVideoStereoMode.MONO,
                    viewMode = VrVideoViewMode.VIEW_360,
                    shouldUseVrRenderer = true,
                    reason = "spherical_xml:equirectangular:mono"
                )
            )
        )
    }
}
```

- [ ] **Step 2: Add the launcher**

Create `app/src/main/java/com/nexio/tv/core/player/QuestSpatialPlayerLauncher.kt`:

```kotlin
package com.nexio.tv.core.player

import android.content.Context
import android.content.Intent
import android.util.Log

internal object QuestSpatialPlayerLauncher {
    private const val TAG = "QuestSpatialLauncher"
    private const val QUEST_PACKAGE = "com.nexio.tv.quest"
    private const val QUEST_ACTIVITY = "com.nexio.tv.quest.QuestSpatialPlayerActivity"

    fun launch(
        context: Context,
        streamUrl: String,
        title: String?,
        decision: VrVideoProjectionDecision
    ): Boolean {
        val intent = Intent().apply {
            setClassName(QUEST_PACKAGE, QUEST_ACTIVITY)
            putExtra("com.nexio.tv.quest.extra.STREAM_URL", streamUrl)
            putExtra("com.nexio.tv.quest.extra.TITLE", title)
            putExtra("com.nexio.tv.quest.extra.PROJECTION", decision.projection.name)
            putExtra("com.nexio.tv.quest.extra.STEREO_MODE", decision.stereoMode.name)
            putExtra("com.nexio.tv.quest.extra.VIEW_MODE", decision.viewMode.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { error ->
            Log.w(TAG, "Quest spatial player launch failed: ${error.message}")
            false
        }
    }
}

internal fun shouldLaunchQuestSpatialPlayer(
    vrCapable: Boolean,
    decision: VrVideoProjectionDecision?
): Boolean {
    return vrCapable && decision?.shouldUseVrRenderer == true
}
```

- [ ] **Step 3: Run launcher tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.player.QuestSpatialPlayerLauncherTest'
```

Expected: PASS.

- [ ] **Step 4: Wire launch before flat player initialization**

Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`.

Add import:

```kotlin
import com.nexio.tv.core.player.QuestSpatialPlayerLauncher
import com.nexio.tv.core.player.shouldLaunchQuestSpatialPlayer
```

Replace the VR probe block from Task 5 with:

```kotlin
            val vrCapable = VrDeviceCapability.isVrCapable(context)
            currentVrVideoProjectionDecision = vrVideoProjectionProbe.probeIfVrCapable(
                vrCapable = vrCapable,
                url = url,
                headers = headers,
                filename = currentFilename
            )
            _uiState.update {
                it.copy(vrVideoProjectionDecision = currentVrVideoProjectionDecision)
            }
            if (shouldLaunchQuestSpatialPlayer(vrCapable, currentVrVideoProjectionDecision)) {
                val launched = QuestSpatialPlayerLauncher.launch(
                    context = context.applicationContext,
                    streamUrl = url,
                    title = title,
                    decision = currentVrVideoProjectionDecision!!
                )
                if (launched) {
                    releasePlayer()
                    _uiState.update { it.copy(showLoadingOverlay = false) }
                    return@launch
                }
            }
```

- [ ] **Step 5: Run focused app tests and compile**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.player.QuestSpatialPlayerLauncherTest' --tests 'com.nexio.tv.core.player.Vr*'
./gradlew :app:compileUniversalDebugKotlin
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/QuestSpatialPlayerLauncher.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/test/java/com/nexio/tv/core/player/QuestSpatialPlayerLauncherTest.kt
git commit -m "feat: route spatial streams to Quest player"
```

---

## Self-Review

**Spec coverage:** The plan preserves the normal shared FFmpeg metadata probe, adds a VR-only expanded probe, gates native calls to VR-capable devices, parses `SPHERICAL-VIDEO` and `stereo_mode`, classifies the provided sample as `VR_360_MONO`, stores the result before playback, and routes spatial streams to a Meta Spatial SDK playback module when enabled.

**Placeholder scan:** No implementation steps contain `TBD`, `TODO`, "implement later", or "write tests for the above" without concrete code.

**Type consistency:** `VrVideoProjectionMetadata`, `VrVideoProjectionDecision`, `VrVideoProjectionProbe`, `VrDeviceCapability`, `QuestSpatialMedia`, and `QuestSpatialPlayerLauncher` names match across tasks. The native Java method is `probeVrVideoProjectionMetadataJson`; the private native binding is `ffmpegProbeVrVideoProjectionMetadataJson`; the JNI function name matches the Java private native method.

## Follow-Up: Media3 Spherical Surface Fallback

The ProAndroidDev article describes the standard ExoPlayer approach for a simple 360 player: set `PlayerView` to `app:surface_type="spherical_gl_surface_view"` and call `PlayerView.onResume()` / `PlayerView.onPause()` so the spherical surface can use motion sensors. This repo's Media3 fork still supports that surface type in `media/libraries/ui/src/main/java/androidx/media3/ui/PlayerView.java`.

Use this as a second, smaller plan if the goal is to make detected 360 content usable in Nexio before the Quest immersive renderer is ready:

- Create `app/src/main/res/layout/exo_player_view_spherical.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.media3.ui.PlayerView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/spherical_player_view"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:surface_type="spherical_gl_surface_view"
    app:use_controller="false" />
```

- Add a `useSphericalSurface: Boolean` field to `PlayerSurfaceRenderState`.
- In `PlayerVideoSurface`, inflate `exo_player_view_spherical` when `renderState.useSphericalSurface` is true; otherwise keep constructing `PlayerView(context)`.
- Call `playerView.onResume()` in the `AndroidView` factory/update path when using spherical mode and `playerView.onPause()` in `DisposableEffect` cleanup.
- For `VrVideoProjectionDecision.VR_360_MONO`, use spherical mode with monoscopic default.
- For `VrVideoProjectionDecision.VR_360_SBS` and `VR_180_SBS`, keep using the future Meta Spatial SDK renderer unless tests prove Media3's spherical surface gives acceptable Quest-window behavior.

This fallback is intentionally a flat Android surface. It can provide a lightweight 360 viewing experience, but it does not replace the headset-eye-correct immersive Quest renderer.
