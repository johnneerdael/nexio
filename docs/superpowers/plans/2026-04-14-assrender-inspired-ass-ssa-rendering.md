# ASS/SSA Assrender-Inspired Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the optional `ass-media` libass path with an always-on ASS/SSA rendering pipeline, inspired by `~/Scripts/assrender`, that preserves `SubtitleOffsetRenderersFactory`, subtitle delay, built-in AI subtitle translation, and full libass display semantics.

**Architecture:** Keep Nexio's existing `SubtitleOffsetRenderersFactory` as the single renderer factory and add an ASS/SSA time renderer to it instead of replacing it with an external factory. Port the useful `assrender` ideas into local Nexio code: raw Matroska ASS interception, no-op ASS parser for Media3, embedded font capture, a libass-backed native bridge, and a transparent overlay attached to the existing `PlayerView`. ASS/SSA tracks always trigger the libass path; there is no user-facing libass toggle and no remaining dependency on `io.github.peerless2012:ass-media`.

**Tech Stack:** Android Kotlin, Media3 1.10.0-beta01, ExoPlayer `DefaultMediaSourceFactory`, `MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA`, libass through JNI, CMake, Robolectric/JUnit 4, Android instrumentation smoke tests, Aegisub ASS override tag reference.

---

## Source Notes

- Aegisub ASS tag reference: https://aegisub.org/docs/latest/ass_tags/
- `assrender` checkout: `/Users/jneerdael/Scripts/assrender`
- `assrender` GitHub repo: https://github.com/LumeraD3v/assrender
- Current Nexio libass dependency: `app/build.gradle.kts` currently declares `io.github.peerless2012:ass-media:0.4.0-beta01`.
- Current Nexio renderer factory: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt` private class `SubtitleOffsetRenderersFactory`.
- Current Nexio ASS/SSA auto-translation plan: `docs/superpowers/plans/2026-04-14-ass-ssa-auto-translate.md`.

## File Map

- Create: `docs/subtitles/assrender-audit.md`
  - Records the `assrender` source audit, ASS/SSA tag coverage contract, and which functions are ported, adapted, or intentionally excluded.
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaFormatUtils.kt`
  - Owns ASS/SSA format detection helpers used by player setup, track switching, and tests.
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssNoOpSubtitleParserFactory.kt`
  - Owns the Media3 `SubtitleParser.Factory` wrapper that no-ops ASS/SSA samples and delegates non-ASS formats to `DefaultSubtitleParserFactory`.
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTrackOutput.kt`
  - Wraps `TrackOutput`, captures raw ASS headers and dialogue samples, forwards samples unchanged to Media3.
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaExtractorOutput.kt`
  - Wraps `ExtractorOutput` and returns `AssSsaTrackOutput` for ASS/SSA text tracks.
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaMatroskaExtractor.kt`
  - Extends `MatroskaExtractor`, enables raw subtitle data, wraps `extractorOutput`, and captures Matroska font attachments.
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaExtractorsFactory.kt`
  - Wraps an existing `ExtractorsFactory`, replacing Matroska extractors with `AssSsaMatroskaExtractor`.
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaNativeBridge.kt`
  - JNI bridge for libass init/header/font/chunk/render/flush/destroy calls.
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderOverlayView.kt`
  - Transparent bitmap overlay view attached to the existing `PlayerView.overlayFrameLayout`.
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderController.kt`
  - Coordinates track headers, samples, fonts, selected track, render loop, video size, subtitle delay, and resource release.
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTimeRenderer.kt`
  - `NoSampleRenderer` that feeds playback time into `AssSsaRenderController`.
- Create: `app/src/main/cpp/ass_direct.h`
- Create: `app/src/main/cpp/ass_direct.c`
- Create: `app/src/main/cpp/ass_direct_jni.c`
  - Local JNI/native libass rendering core ported from `/Users/jneerdael/Scripts/assrender/assrender/src/main/cpp/ass_direct.*`.
- Modify: `app/src/main/cpp/CMakeLists.txt`
  - Adds an `assrender_direct` shared library and links libass/freetype/fribidi/harfbuzz/fontconfig/expat from app-packaged prebuilt native libraries. This CMake target must be built independently of `DOVI_NATIVE_ENABLED`.
- Modify: `app/build.gradle.kts`
  - Removes `io.github.peerless2012:ass-media:0.4.0-beta01`; makes the native build run for `assrender_direct` even when Dolby Vision native support is disabled; packages libass dependency `.so` files from `/Users/jneerdael/Scripts/assrender/prebuilt/{arm64-v8a,armeabi-v7a}/lib`.
- Modify: `app/proguard-rules.pro`
  - Keeps `AssSsaNativeBridge` JNI method names.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - Removes `AssRenderType` usage, removes `buildWithAssSupportCompat`, preserves `SubtitleOffsetRenderersFactory`, installs `AssSsaTimeRenderer`, and configures `PlayerMediaSourceFactory` with ASS/SSA parser/extractor wrappers whenever the current stream has selected ASS/SSA.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - Keeps the existing `configureSubtitleParsing` extension point and documents it as the ASS/SSA parser/extractor hook.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt`
  - Replaces the user-toggle condition with automatic ASS/SSA detection and rebuild behavior.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
  - Replaces `requestedUseLibassByUser`, `activePlayerUsesLibass`, and `libassPipeline*` names with `assSsaRenderPipeline*` names that describe automatic behavior.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerLifecycle.kt`
  - Releases and nulls `AssSsaRenderController` during `releasePlayer()` so native libass handles are destroyed on stream switch and `onCleared()`.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerVideoSurface.kt`
  - Attaches/releases `AssSsaRenderOverlayView` to the `PlayerView` overlay frame and keeps native subtitles hidden only while the ASS/SSA overlay is active.
- Delete: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerLibassCompat.kt`
- Delete: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerLibassExtensions.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
  - Removes `useLibass`, `libassRenderType`, `LibassRenderType`, flows, setters, and preference keys from active settings.
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
  - Removes synced `useLibass` writes.
- Modify: `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`
  - Removes `useLibass` from current playback subtitle sync payload, with backward-compatible decode if needed by existing JSON defaults.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSubtitleSettings.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
  - Removes the libass toggle and render-mode choices from settings.
- Modify: localized strings under `app/src/main/res/values*/strings.xml`
  - Removes `sub_libass`, `sub_libass_sub`, `sub_libass_mode`, and render-mode strings if no longer referenced.
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaFormatUtilsTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssNoOpSubtitleParserFactoryTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTrackOutputTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAssSsaPipelineTest.kt`
- Test: `app/src/androidTest/java/com/nexio/tv/instrumentation/AssSsaNativeRenderSmokeTest.kt`

## ASS/SSA Coverage Contract

Rendering coverage must come from libass, not a hand-written Kotlin tag renderer. The local Kotlin pipeline is responsible for preserving and delivering raw ASS/SSA data losslessly.

Validate this Aegisub tag matrix by feeding representative lines through the pipeline and verifying the raw event text reaches `ass_process_chunk` unchanged:

| Category | Tags and data to preserve |
| --- | --- |
| Special characters | `\n`, `\N`, `\h` |
| Font face and weight | `\fn`, `\fs`, `\b`, `\i`, `\u`, `\s`, `\fe` |
| Font geometry | `\fscx`, `\fscy`, `\fsp`, `\frx`, `\fry`, `\frz`, `\fr`, `\fax`, `\fay` |
| Color and alpha | `\c`, `\1c`, `\2c`, `\3c`, `\4c`, `\alpha`, `\1a`, `\2a`, `\3a`, `\4a` |
| Border, shadow, blur | `\bord`, `\xbord`, `\ybord`, `\shad`, `\xshad`, `\yshad`, `\be`, `\blur` |
| Alignment and wrapping | `\an`, legacy `\a`, `\q` |
| Position and origin | `\pos`, `\move`, `\org` |
| Fade and animation | `\fad`, `\fade`, `\t` including animated font, geometry, color, alpha, border, shadow, blur, clip rectangle |
| Clipping | rectangle `\clip`, rectangle `\iclip`, vector `\clip`, vector `\iclip` |
| Karaoke | `\k`, `\K`, `\kf`, `\ko`, `\kt` |
| Reset and style selection | `\r`, `\r<StyleName>` |
| Drawing tags | `\p0`, `\p1`, `\pN`, `\pbo` |
| Drawing commands | `m`, `n`, `l`, `b`, `s`, `p`, `c` |

## Assrender Function Mapping

| Source function/class | Local destination | Decision |
| --- | --- | --- |
| `AssSubtitleParserFactory` | `AssNoOpSubtitleParserFactory` | Port behavior; normalize `Format.codecs` and `sampleMimeType`; delegate non-ASS formats. |
| `AssNoOpSubtitleParser` | `AssNoOpSubtitleParser` nested/private class | Port behavior; no cue output for ASS/SSA. |
| `AssMatroskaExtractor` | `AssSsaMatroskaExtractor` | Adapt; preserve existing `ExtractorsFactory`; use raw subtitle flag; wrap `extractorOutput`; capture font attachments. |
| `AssExtractorOutput` | `AssSsaExtractorOutput` | Port wrapper idea; keep track ids stable. |
| `AssTrackOutput.format` | `AssSsaTrackOutput.format` | Port and harden ASS detection from mime, codecs, and initialization data. |
| `AssTrackOutput.sampleData` overloads | `AssSsaTrackOutput.sampleData` | Port with tests that confirm forwarding and capture for both `ParsableByteArray` and `DataReader`. |
| `AssTrackOutput.sampleMetadata` | `AssSsaTrackOutput.sampleMetadata` | Port and call controller with sample time, size, offset-safe bytes. |
| `AssHandler.onTrackHeader` | `AssSsaRenderController.onTrackHeader` | Port; initialize native context lazily and load codec private header. |
| `AssHandler.onSubtitleSample` | `AssSsaRenderController.onSubtitleSample` | Adapt; parse Matroska ASS sample fields and pass `ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text` to native `ass_process_chunk` with sample time and duration. |
| `AssHandler.onFontAttachment` | `AssSsaRenderController.onFontAttachment` | Port; buffer fonts until native init; reconfigure font provider after adding fonts. |
| `AssHandler.selectTrackByFormat` | `AssSsaRenderController.selectTrackByFormat` | Adapt; prefer stable track id, then label/language fallback. |
| `AssHandler.renderFrame` | `AssSsaRenderController.renderFrame` | Adapt; subtract existing `subtitleDelayUsProvider` before rendering; avoid conflicting with `SubtitleOffsetRenderer`. |
| `AssHandler.clearOverlay/release` | `AssSsaRenderController.clearOverlay/release` | Port; release bitmap/native resources on player release and surface disposal. |
| `SubtitleOverlayView` | `AssSsaRenderOverlayView` | Port; attach to `PlayerView.overlayFrameLayout`; do not reuse `SubtitleView`. |
| `AssTimeRenderer` | `AssSsaTimeRenderer` | Port; add from `SubtitleOffsetRenderersFactory.createRenderers` after default renderers are built. |
| `AssDirectBridge` | `AssSsaNativeBridge` | Port with package names changed. |
| `ass_direct.*` | `app/src/main/cpp/ass_direct.*` | Port; keep direct libass path; exclude FFmpeg stream-opening pipeline. |
| `subtitle_pipeline.*` and `NativeBridge` | Not ported | Exclude; Nexio already uses Media3 extraction and only needs direct header/chunk rendering. |
| `AssSubtitleRenderer`, `AssRenderer`, `AssTextRenderer` | Not ported | Exclude; they duplicate the controller/overlay path and do not preserve Nexio's renderer factory. |

## Tasks

### Task 1: Write The Audit Artifact

**Files:**
- Create: `docs/subtitles/assrender-audit.md`

- [ ] **Step 1: Create the audit file with the mapping and coverage contract**

Add `docs/subtitles/assrender-audit.md`:

```markdown
# Assrender-Inspired ASS/SSA Rendering Audit

## Goal

Replace the optional `io.github.peerless2012:ass-media` path with a local, always-on ASS/SSA rendering pipeline that preserves Nexio's custom `SubtitleOffsetRenderersFactory`, subtitle delay, and AI subtitle translation hooks.

## Sources Audited

- `/Users/jneerdael/Scripts/assrender/assrender/src/main/kotlin/io/github/assrender/AssSubtitleParserFactory.kt`
- `/Users/jneerdael/Scripts/assrender/assrender/src/main/kotlin/io/github/assrender/AssMatroskaExtractor.kt`
- `/Users/jneerdael/Scripts/assrender/assrender/src/main/kotlin/io/github/assrender/AssExtractorOutput.kt`
- `/Users/jneerdael/Scripts/assrender/assrender/src/main/kotlin/io/github/assrender/AssTrackOutput.kt`
- `/Users/jneerdael/Scripts/assrender/assrender/src/main/kotlin/io/github/assrender/AssHandler.kt`
- `/Users/jneerdael/Scripts/assrender/assrender/src/main/kotlin/io/github/assrender/AssTimeRenderer.kt`
- `/Users/jneerdael/Scripts/assrender/assrender/src/main/kotlin/io/github/assrender/SubtitleOverlayView.kt`
- `/Users/jneerdael/Scripts/assrender/assrender/src/main/cpp/ass_direct.c`
- `/Users/jneerdael/Scripts/assrender/assrender/src/main/cpp/ass_direct_jni.c`
- `/Users/jneerdael/Scripts/assrender/assrender/src/main/cpp/ass_direct.h`
- Aegisub ASS tag reference: https://aegisub.org/docs/latest/ass_tags/

## Port Decisions

| Source | Decision |
| --- | --- |
| Parser factory and no-op parser | Port. ASS/SSA must be consumed by libass, not Media3 cues. |
| Matroska extractor interception | Port and adapt. Keep the raw subtitle flag and font attachment capture. |
| TrackOutput capture | Port and test both sampleData overloads. |
| Handler/controller | Port and adapt. Feed libass chunks using Media3 sample metadata time and ASS sample duration. |
| Overlay view | Port and attach to `PlayerView.overlayFrameLayout`. |
| Time renderer | Port into Nexio's existing renderer factory. |
| Native direct libass bridge | Port. |
| FFmpeg stream-opening pipeline | Exclude. Media3 remains the extractor and data-source owner. |
| External `ass-media` path | Remove. |

## Aegisub Coverage Validation

Coverage is achieved when these tags and drawing commands are delivered byte-for-byte to libass in the event text: `\n`, `\N`, `\h`, `\b`, `\i`, `\u`, `\s`, `\bord`, `\xbord`, `\ybord`, `\shad`, `\xshad`, `\yshad`, `\be`, `\blur`, `\fn`, `\fs`, `\fscx`, `\fscy`, `\fsp`, `\frx`, `\fry`, `\frz`, `\fr`, `\fax`, `\fay`, `\fe`, `\c`, `\1c`, `\2c`, `\3c`, `\4c`, `\alpha`, `\1a`, `\2a`, `\3a`, `\4a`, `\an`, `\a`, `\q`, `\pos`, `\move`, `\org`, `\fad`, `\fade`, `\t`, `\clip`, `\iclip`, `\k`, `\K`, `\kf`, `\ko`, `\kt`, `\r`, `\p`, `\pbo`, and drawing commands `m`, `n`, `l`, `b`, `s`, `p`, `c`.
```

- [ ] **Step 2: Verify the audit file contains no stale dependency target**

Run: `rg -n "peerless2012|ass-media" docs/subtitles/assrender-audit.md`

Expected: matches only the "External `ass-media` path | Remove." and "Replace the optional `io.github.peerless2012:ass-media` path" audit statements.

- [ ] **Step 3: Commit**

Run:

```bash
git add docs/subtitles/assrender-audit.md
git commit -m "docs: audit assrender ASS pipeline"
```

Expected: one commit containing only the audit document.

### Task 2: Add ASS/SSA Format Detection And Parser Factory

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaFormatUtils.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssNoOpSubtitleParserFactory.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaFormatUtilsTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssNoOpSubtitleParserFactoryTest.kt`

- [ ] **Step 1: Write failing format tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaFormatUtilsTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssSsaFormatUtilsTest {
    @Test
    fun detectsAssSsaBySampleMimeType() {
        assertTrue(Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build().isAssSsaFormat())
    }

    @Test
    fun detectsAssSsaByCodecString() {
        val format = Format.Builder()
            .setCodecs("avc1.640028, s_text/ass")
            .build()

        assertTrue(format.isAssSsaFormat())
    }

    @Test
    fun detectsAssSsaByInitializationHeader() {
        val format = Format.Builder()
            .setInitializationData(listOf("[Script Info]\nScriptType: v4.00+".toByteArray()))
            .build()

        assertTrue(format.isAssSsaFormat())
    }

    @Test
    fun ignoresWebVtt() {
        assertFalse(Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build().isAssSsaFormat())
    }
}
```

- [ ] **Step 2: Write failing parser factory tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssNoOpSubtitleParserFactoryTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertTrue
import org.junit.Test

class AssNoOpSubtitleParserFactoryTest {
    @Test
    fun supportsAssSsaFormats() {
        val factory = AssNoOpSubtitleParserFactory()

        assertTrue(factory.supportsFormat(Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()))
    }

    @Test
    fun assParserEmitsNoCuesBecauseLibassRendersTheTrack() {
        val parser = AssNoOpSubtitleParserFactory()
            .create(Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build())
        var emitted = false

        parser.parse(
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello".toByteArray(),
            0,
            57,
            SubtitleParser.OutputOptions.allCues(),
        ) {
            emitted = true
        }

        assertTrue(!emitted)
    }
}
```

- [ ] **Step 3: Run tests and verify failure**

Run: `./gradlew :app:testArm64DebugUnitTest --tests '*AssSsaFormatUtilsTest' --tests '*AssNoOpSubtitleParserFactoryTest'`

Expected: compile failure because `AssSsaFormatUtils.kt` and `AssNoOpSubtitleParserFactory.kt` do not exist.

- [ ] **Step 4: Implement detection helpers**

Create `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaFormatUtils.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.util.Locale

internal fun Format.isAssSsaFormat(): Boolean {
    if (sampleMimeType == MimeTypes.TEXT_SSA || sampleMimeType == "text/x-ass") return true
    val hasAssCodec = codecs
        ?.split(',')
        ?.asSequence()
        ?.map { it.trim().lowercase(Locale.US) }
        ?.any { codec ->
            codec == MimeTypes.TEXT_SSA ||
                codec == "text/x-ass" ||
                codec == "s_text/ass" ||
                codec == "s_text/ssa" ||
                codec.endsWith("/x-ssa")
        } == true
    if (hasAssCodec) return true
    return initializationData.any { data ->
        val preview = data.toString(Charsets.UTF_8)
        preview.contains("[Script Info]", ignoreCase = true) ||
            preview.contains("ScriptType:", ignoreCase = true)
    }
}
```

- [ ] **Step 5: Implement no-op parser factory**

Create `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssNoOpSubtitleParserFactory.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.ass

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

@OptIn(UnstableApi::class)
internal class AssNoOpSubtitleParserFactory : SubtitleParser.Factory {
    private val delegate = DefaultSubtitleParserFactory()

    override fun supportsFormat(format: Format): Boolean {
        return format.isAssSsaFormat() || delegate.supportsFormat(format)
    }

    override fun getCueReplacementBehavior(format: Format): Int {
        return if (format.isAssSsaFormat()) {
            Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
        } else {
            delegate.getCueReplacementBehavior(format)
        }
    }

    override fun create(format: Format): SubtitleParser {
        return if (format.isAssSsaFormat()) AssNoOpSubtitleParser else delegate.create(format)
    }
}

@OptIn(UnstableApi::class)
private object AssNoOpSubtitleParser : SubtitleParser {
    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>
    ) = Unit

    override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
}
```

- [ ] **Step 6: Run tests and commit**

Run: `./gradlew :app:testArm64DebugUnitTest --tests '*AssSsaFormatUtilsTest' --tests '*AssNoOpSubtitleParserFactoryTest'`

Expected: PASS.

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaFormatUtils.kt app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssNoOpSubtitleParserFactory.kt app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaFormatUtilsTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssNoOpSubtitleParserFactoryTest.kt
git commit -m "test: add ASS SSA parser factory contract"
```

Expected: one commit with detection and no-op parser code.

### Task 3: Port Raw ASS/SSA Extraction

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTrackOutput.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaExtractorOutput.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaMatroskaExtractor.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaExtractorsFactory.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTrackOutputTest.kt`

- [ ] **Step 1: Write failing TrackOutput tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTrackOutputTest.kt` with a fake `TrackOutput` and fake sink:

```kotlin
package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AssSsaTrackOutputTest {
    @Test
    fun capturesHeaderAndDialogueWhileForwardingSamples() {
        val delegate = RecordingTrackOutput()
        val sink = RecordingAssSampleSink()
        val output = AssSsaTrackOutput(delegate, sink, trackId = 7)
        val header = "[Script Info]\nScriptType: v4.00+".toByteArray()
        val sample = "Dialogue: 0:00:00.00,0:00:02.00,1,0,Default,,0,0,0,,{\\an5}Hi".toByteArray()

        output.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.TEXT_SSA)
                .setInitializationData(listOf(header))
                .build()
        )
        output.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        output.sampleMetadata(1_000_000L, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)

        assertEquals(7, sink.headers.single().trackId)
        assertArrayEquals(header, sink.headers.single().headerData)
        assertEquals(7, sink.samples.single().trackId)
        assertArrayEquals(sample, sink.samples.single().data)
        assertArrayEquals(sample, delegate.forwardedSample)
    }
}
```

The implementation should define local test-only fakes in the same file:

```kotlin
private class RecordingAssSampleSink : AssSsaSampleSink {
    val headers = mutableListOf<AssTrackHeader>()
    val samples = mutableListOf<AssSubtitleSample>()
    override fun onTrackHeader(trackId: Int, headerData: ByteArray, format: Format) {
        headers += AssTrackHeader(trackId, headerData, format)
    }
    override fun onSubtitleSample(trackId: Int, timeUs: Long, data: ByteArray) {
        samples += AssSubtitleSample(trackId, timeUs, data)
    }
}

private data class AssTrackHeader(val trackId: Int, val headerData: ByteArray, val format: Format)
private data class AssSubtitleSample(val trackId: Int, val timeUs: Long, val data: ByteArray)
```

- [ ] **Step 2: Run test and verify failure**

Run: `./gradlew :app:testArm64DebugUnitTest --tests '*AssSsaTrackOutputTest'`

Expected: compile failure because extraction classes do not exist.

- [ ] **Step 3: Implement extraction classes**

Implement:

```kotlin
internal interface AssSsaSampleSink {
    fun onTrackHeader(trackId: Int, headerData: ByteArray, format: Format)
    fun onSubtitleSample(trackId: Int, timeUs: Long, data: ByteArray)
    fun onFontAttachment(name: String, data: ByteArray) = Unit
}
```

In `AssSsaTrackOutput`, copy the `assrender` buffering strategy:

```kotlin
private var isAssTrack = false
private val pendingData = ByteArrayOutputStream()

override fun format(format: Format) {
    isAssTrack = format.isAssSsaFormat()
    if (isAssTrack) {
        format.initializationData
            .firstOrNull { data ->
                val preview = data.toString(Charsets.UTF_8)
                preview.contains("[Script Info]", ignoreCase = true) ||
                    preview.contains("ScriptType:", ignoreCase = true)
            }
            ?.let { sink.onTrackHeader(trackId, it, format) }
    }
    delegate.format(format)
}
```

Implement both `sampleData` overloads so captured bytes are also forwarded to the delegate. Implement `sampleMetadata` so it calls `sink.onSubtitleSample(trackId, timeUs, pendingData.toByteArray())` before resetting the buffer.

In `AssSsaMatroskaExtractor`, extend `MatroskaExtractor(AssNoOpSubtitleParserFactory(), MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA)`, intercept attachment EBML ids from `assrender`, wrap `extractorOutput` using reflection, and forward font bytes to `sink.onFontAttachment`.

The reflection must be centralized and fail closed:

```kotlin
private val extractorOutputField = runCatching {
    MatroskaExtractor::class.java.getDeclaredField("extractorOutput").apply {
        isAccessible = true
    }
}.getOrNull()

private fun wrapExtractorOutputIfPossible() {
    val field = extractorOutputField ?: return
    val current = field.get(this) as? ExtractorOutput ?: return
    if (current is AssSsaExtractorOutput) return
    field.set(this, AssSsaExtractorOutput(current, sink))
}
```

If the field is unavailable after a future Media3 update, log once and fall back to Media3's native subtitle parser rather than crashing playback. Add a unit test that constructs the extractor and asserts the missing-field path does not throw by injecting a null `Field` for testing.

- [ ] **Step 4: Run tests and commit**

Run: `./gradlew :app:testArm64DebugUnitTest --tests '*AssSsaTrackOutputTest'`

Expected: PASS.

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ass app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTrackOutputTest.kt
git commit -m "feat: capture raw ASS SSA subtitle samples"
```

Expected: one commit with extraction wrappers and tests.

### Task 4: Port The Native Libass Bridge

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaNativeBridge.kt`
- Create: `app/src/main/cpp/ass_direct.h`
- Create: `app/src/main/cpp/ass_direct.c`
- Create: `app/src/main/cpp/ass_direct_jni.c`
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Modify: `app/build.gradle.kts`
- Modify: `app/proguard-rules.pro`
- Test: `app/src/androidTest/java/com/nexio/tv/instrumentation/AssSsaNativeRenderSmokeTest.kt`

- [ ] **Step 1: Write native smoke test**

Create `app/src/androidTest/java/com/nexio/tv/instrumentation/AssSsaNativeRenderSmokeTest.kt`:

```kotlin
package com.nexio.tv.instrumentation

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexio.tv.ui.screens.player.ass.AssSsaNativeBridge
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssSsaNativeRenderSmokeTest {
    @Test
    fun rendersStyledAssDialogueIntoBitmap() {
            assertTrue(AssSsaNativeBridge.nativeAvailable)
            val handle = AssSsaNativeBridge.nativeInit(640, 360, 1.0f)
        assertTrue(handle != 0L)
        try {
            val header = """
                [Script Info]
                ScriptType: v4.00+
                PlayResX: 640
                PlayResY: 360

                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                Style: Default,Arial,36,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,2,0,5,10,10,10,1

                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            """.trimIndent().toByteArray()
            AssSsaNativeBridge.nativeLoadHeader(handle, header)
            AssSsaNativeBridge.nativeProcessChunk(
                handle,
                "0,Default,,0,0,0,,{\\an5\\bord4\\1c&H00FFFF&}Hello".toByteArray(),
                1000,
                2000
            )
            val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            assertTrue(AssSsaNativeBridge.nativeRender(handle, 1500, bitmap))
        } finally {
            AssSsaNativeBridge.nativeDestroy(handle)
        }
    }
}
```

- [ ] **Step 2: Run native test and verify failure**

Run: `./gradlew :app:connectedArm64DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nexio.tv.instrumentation.AssSsaNativeRenderSmokeTest`

Expected: compile failure because `AssSsaNativeBridge` and native symbols do not exist.

- [ ] **Step 3: Port direct libass files**

Copy and rename the direct bridge from:

```text
/Users/jneerdael/Scripts/assrender/assrender/src/main/cpp/ass_direct.h
/Users/jneerdael/Scripts/assrender/assrender/src/main/cpp/ass_direct.c
/Users/jneerdael/Scripts/assrender/assrender/src/main/cpp/ass_direct_jni.c
```

Change JNI symbol prefixes from:

```c
Java_io_github_assrender_AssDirectBridge_
```

to:

```c
Java_com_nexio_tv_ui_screens_player_ass_AssSsaNativeBridge_
```

Create `AssSsaNativeBridge.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.ass

import android.graphics.Bitmap

internal object AssSsaNativeBridge {
    val nativeAvailable: Boolean = runCatching {
        System.loadLibrary("assrender_direct")
        true
    }.getOrDefault(false)

    init {
        if (!nativeAvailable) {
            android.util.Log.w("AssSsaNativeBridge", "assrender_direct unavailable; falling back to Media3 SSA rendering")
        }
    }

    fun requireNativeAvailable(): Boolean = nativeAvailable

    external fun nativeInit(width: Int, height: Int, fontScale: Float): Long
    external fun nativeLoadHeader(handle: Long, headerData: ByteArray): Int
    external fun nativeAddFont(handle: Long, name: String?, fontData: ByteArray)
    external fun nativeProcessChunk(handle: Long, data: ByteArray, startMs: Long, durationMs: Long)
    external fun nativeProcessData(handle: Long, data: ByteArray)
    external fun nativeRender(handle: Long, timeMs: Long, bitmap: Bitmap): Boolean
    external fun nativeFlush(handle: Long)
    external fun nativeDestroy(handle: Long)
}
```

Every caller must check `AssSsaNativeBridge.nativeAvailable` before selecting the ASS/SSA renderer pipeline. If it is `false`, keep the default Media3 parser/rendering path so playback continues with degraded subtitle fidelity instead of crashing.

- [ ] **Step 4: Wire CMake and packaged native libraries**

In `app/build.gradle.kts`, make the app-level `externalNativeBuild` block unconditional. Keep DOVI-specific CMake arguments conditional inside `defaultConfig`, but do not gate the CMake project path behind `enableDoviNative`; otherwise `assrender_direct` is never built for default builds.

Use this shape:

```kotlin
externalNativeBuild {
    cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
        buildStagingDirectory = file("${rootProject.projectDir}/.cxx-build")
    }
}
```

Then copy prebuilt libraries from the local `assrender` checkout into the app:

```bash
mkdir -p app/src/main/jniLibs/arm64-v8a app/src/main/jniLibs/armeabi-v7a
cp /Users/jneerdael/Scripts/assrender/prebuilt/arm64-v8a/lib/libass.so app/src/main/jniLibs/arm64-v8a/
cp /Users/jneerdael/Scripts/assrender/prebuilt/arm64-v8a/lib/libfreetype.so app/src/main/jniLibs/arm64-v8a/
cp /Users/jneerdael/Scripts/assrender/prebuilt/arm64-v8a/lib/libfribidi.so app/src/main/jniLibs/arm64-v8a/
cp /Users/jneerdael/Scripts/assrender/prebuilt/arm64-v8a/lib/libharfbuzz.so app/src/main/jniLibs/arm64-v8a/
cp /Users/jneerdael/Scripts/assrender/prebuilt/arm64-v8a/lib/libfontconfig.so app/src/main/jniLibs/arm64-v8a/
cp /Users/jneerdael/Scripts/assrender/prebuilt/arm64-v8a/lib/libexpat.so app/src/main/jniLibs/arm64-v8a/
cp /Users/jneerdael/Scripts/assrender/prebuilt/armeabi-v7a/lib/libass.so app/src/main/jniLibs/armeabi-v7a/
cp /Users/jneerdael/Scripts/assrender/prebuilt/armeabi-v7a/lib/libfreetype.so app/src/main/jniLibs/armeabi-v7a/
cp /Users/jneerdael/Scripts/assrender/prebuilt/armeabi-v7a/lib/libfribidi.so app/src/main/jniLibs/armeabi-v7a/
cp /Users/jneerdael/Scripts/assrender/prebuilt/armeabi-v7a/lib/libharfbuzz.so app/src/main/jniLibs/armeabi-v7a/
cp /Users/jneerdael/Scripts/assrender/prebuilt/armeabi-v7a/lib/libfontconfig.so app/src/main/jniLibs/armeabi-v7a/
cp /Users/jneerdael/Scripts/assrender/prebuilt/armeabi-v7a/lib/libexpat.so app/src/main/jniLibs/armeabi-v7a/
```

Copy headers used by `ass_direct.c` to `app/src/main/cpp/assrender_include` from the same prebuilt tree:

```bash
mkdir -p app/src/main/cpp/assrender_include
cp -R /Users/jneerdael/Scripts/assrender/prebuilt/arm64-v8a/include/ass app/src/main/cpp/assrender_include/
```

In `app/src/main/cpp/CMakeLists.txt`, keep `dovi_bridge` unchanged and add `assrender_direct` as a second shared library. Link it against imported libass dependencies copied from the `assrender` prebuilt tree. Use this shape:

```cmake
add_library(assrender_direct SHARED
    ass_direct.c
    ass_direct_jni.c
)

find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_include_directories(assrender_direct PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/assrender_include"
)

target_link_libraries(assrender_direct
    ass
    freetype
    fribidi
    harfbuzz
    fontconfig
    expat
    ${android-lib}
    ${jnigraphics-lib}
    ${log-lib}
)
```

Add the imported library declarations before `target_link_libraries`, resolving each `IMPORTED_LOCATION` from the ABI-specific `app/src/main/jniLibs/${ANDROID_ABI}` directory.

- [ ] **Step 5: Configure fontconfig and keep JNI methods**

Port `assrender`'s fontconfig setup into the Kotlin controller: create an app-writable `filesDir/fontconfig/fonts.conf` that includes `/system/fonts`, uses `cacheDir/fontconfig` as `<cachedir>`, and call `Os.setenv("FONTCONFIG_PATH", fontconfigDir.absolutePath, true)` before the first `nativeInit`.

Add this rule to `app/proguard-rules.pro`:

```proguard
-keep class com.nexio.tv.ui.screens.player.ass.AssSsaNativeBridge { *; }
```

- [ ] **Step 6: Run native smoke test and commit**

Run: `./gradlew :app:connectedArm64DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nexio.tv.instrumentation.AssSsaNativeRenderSmokeTest`

Expected: PASS on an attached Android device/emulator with an ABI supported by the packaged libass dependencies.

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaNativeBridge.kt app/src/main/cpp app/src/main/jniLibs app/build.gradle.kts app/proguard-rules.pro app/src/androidTest/java/com/nexio/tv/instrumentation/AssSsaNativeRenderSmokeTest.kt
git commit -m "feat: add local ASS SSA native renderer"
```

Expected: one commit with the native renderer and smoke test.

### Task 5: Add Render Controller And Overlay

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderOverlayView.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderController.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTimeRenderer.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerVideoSurface.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderControllerTest.kt`

- [ ] **Step 1: Write controller tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderControllerTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class AssSsaRenderControllerTest {
    @Test
    fun convertsMkvDialogueSampleToLibassChunk() {
        val sample = "Dialogue: 0:00:00.00,0:00:02.50,42,0,Default,,0,0,0,,{\\an5}Hello".toByteArray()
        val event = AssSsaRenderController.parseMkvAssSampleForTesting(trackId = 3, timeUs = 1_000_000L, data = sample)

        assertEquals(3, event.trackId)
        assertEquals(1000L, event.startMs)
        assertEquals(2500L, event.durationMs)
        assertEquals("42,0,Default,,0,0,0,,{\\an5}Hello", event.chunkData.toString(Charsets.UTF_8))
    }

    @Test
    fun matchesTrackByFormatLanguage() {
        val controller = AssSsaRenderController.forTesting()
        controller.onTrackHeader(
            trackId = 8,
            headerData = "[Script Info]\nScriptType: v4.00+".toByteArray(),
            format = Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).setLanguage("ja").build()
        )

        assertEquals(8, controller.findTrackIdByFormatForTesting(Format.Builder().setLanguage("ja").build()))
    }
}
```

- [ ] **Step 2: Run tests and verify failure**

Run: `./gradlew :app:testArm64DebugUnitTest --tests '*AssSsaRenderControllerTest'`

Expected: compile failure because the controller does not exist.

- [ ] **Step 3: Implement render controller**

Implement `AssSsaRenderController` with these public/internal entry points:

```kotlin
internal class AssSsaRenderController(
    private val context: Context,
    private val overlayView: AssSsaRenderOverlayView,
    private val subtitleDelayUsProvider: () -> Long,
    private val native: AssSsaNativeApi = JniAssSsaNativeApi
) : AssSsaSampleSink {
    @Volatile var currentTimeUs: Long = 0L
    fun setPlayer(player: ExoPlayer?)
    fun setVideoSize(width: Int, height: Int)
    fun onSeekStarted()
    fun selectTrackByFormat(format: Format)
    fun clearOverlay()
    fun release()
}
```

Represent parsed MKV chunks with:

```kotlin
internal data class AssSsaEventChunk(
    val trackId: Int,
    val startMs: Long,
    val durationMs: Long,
    val chunkData: ByteArray
)
```

Use the `assrender` algorithm for Matroska samples:

```kotlin
val content = line.removePrefix("Dialogue:").trimStart()
val parts = content.split(",", limit = 11)
val durationMs = parseAssTimeMs(parts[1].trim())
val startMs = timeUs / 1000
val chunkData = "${parts[2].trim()},${parts[3].trim()},${parts[4].trim()},${parts[5].trim()},${parts[6].trim()},${parts[7].trim()},${parts[8].trim()},${parts[9].trim()},${parts[10]}"
```

When rendering, compute:

```kotlin
val adjustedPositionMs = ((player?.currentPosition ?: (currentTimeUs / 1000)) - (subtitleDelayUsProvider() / 1000))
    .coerceAtLeast(0L)
```

Then call `native.render(handle, adjustedPositionMs, bitmap)`.

When `onSeekStarted()` is called, clear the overlay and call `native.flush(handle)` before replaying stored events for the active track at the new position. This prevents stale events from flashing immediately after seeks.

- [ ] **Step 4: Implement overlay and time renderer**

Create `AssSsaRenderOverlayView` by porting `assrender`'s `SubtitleOverlayView`, keeping bitmap copy/double-buffer behavior and clearing with transparent draw mode. Do not allocate a fresh display bitmap every frame; allocate once per source size and reuse it until the video/storage size changes.

Create `AssSsaTimeRenderer`:

```kotlin
internal class AssSsaTimeRenderer(
    private val controller: AssSsaRenderController
) : NoSampleRenderer() {
    override fun getName(): String = "AssSsaTimeRenderer"
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        controller.currentTimeUs = positionUs
    }
    override fun isReady(): Boolean = true
    override fun isEnded(): Boolean = true
}
```

- [ ] **Step 5: Run tests and commit**

Run: `./gradlew :app:testArm64DebugUnitTest --tests '*AssSsaRenderControllerTest'`

Expected: PASS.

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ass app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderControllerTest.kt
git commit -m "feat: add ASS SSA render controller"
```

Expected: one commit with controller, overlay, and tests.

### Task 6: Preserve SubtitleOffsetRenderersFactory And Wire Automatic ASS/SSA Pipeline

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerLifecycle.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerVideoSurface.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAssSsaPipelineTest.kt`

- [ ] **Step 1: Write pipeline decision tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAssSsaPipelineTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRuntimeControllerAssSsaPipelineTest {
    @Test
    fun selectedAssTrackRequestsAssSsaPipeline() {
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()),
                    true,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(true)
                )
            )
        )

        assertTrue(tracks.hasSelectedAssSsaTextTrackForTesting())
    }

    @Test
    fun unselectedAssTrackDoesNotRequestAssSsaPipeline() {
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()),
                    true,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(false)
                )
            )
        )

        assertFalse(tracks.hasSelectedAssSsaTextTrackForTesting())
    }
}
```

- [ ] **Step 2: Run tests and verify current toggle-coupled behavior**

Run: `./gradlew :app:testArm64DebugUnitTest --tests '*PlayerRuntimeControllerAssSsaPipelineTest'`

Expected: compile failure because the test-only helper is not exposed.

- [ ] **Step 3: Wire automatic pipeline state**

In `PlayerRuntimeController.kt`, replace old fields:

```kotlin
internal var requestedUseLibassByUser: Boolean = false
internal var libassPipelineOverrideForCurrentStream: Boolean? = null
internal var activePlayerUsesLibass: Boolean = false
internal var libassPipelineSwitchInFlight: Boolean = false
internal var libassPipelineDecisionStreamUrl: String? = null
```

with:

```kotlin
internal var assSsaPipelineOverrideForCurrentStream: Boolean? = null
internal var activePlayerUsesAssSsaRenderer: Boolean = false
internal var assSsaPipelineSwitchInFlight: Boolean = false
internal var assSsaPipelineDecisionStreamUrl: String? = null
internal var assSsaRenderController: AssSsaRenderController? = null
```

In `maybeAdjustLibassPipelineForTracks`, rename the function to `maybeAdjustAssSsaPipelineForTracks` and set:

```kotlin
val desiredUseAssSsaPipeline = tracks.hasSelectedAssSsaTextTrack()
```

In `initializePlayer`, remove all `playerSettings.useLibass` and `AssRenderType` logic. Use:

```kotlin
val useAssSsaPipeline = AssSsaNativeBridge.nativeAvailable &&
    assSsaPipelineOverrideForCurrentStream == true
```

If `AssSsaNativeBridge.nativeAvailable` is false, log the fallback once and leave `mediaSourceFactory.configureSubtitleParsing(extractorsFactory = null, subtitleParserFactory = null)` so Media3's native SSA parser remains the degraded fallback.

When `useAssSsaPipeline` is true:

```kotlin
val assController = AssSsaRenderController(
    context = context,
    overlayView = requireNotNull(assSsaOverlayViewProvider?.invoke()) {
        "ASS/SSA overlay view must be attached before enabling the ASS/SSA render pipeline"
    },
    subtitleDelayUsProvider = subtitleDelayUs::get
)
assSsaRenderController = assController
mediaSourceFactory.configureSubtitleParsing(
    extractorsFactory = AssSsaExtractorsFactory(extractorsFactory, assController),
    subtitleParserFactory = AssNoOpSubtitleParserFactory()
)
```

Then build ExoPlayer with the existing `renderersFactory`. Do not call `buildWithAssSupportCompat`.

- [ ] **Step 4: Add overlay provider plumbing and release lifecycle**

In `PlayerRuntimeController.kt`, add:

```kotlin
internal var assSsaOverlayViewProvider: (() -> AssSsaRenderOverlayView?)? = null
```

In `PlayerVideoSurface.kt`, create and retain the overlay inside the `PlayerView.overlayFrameLayout`, then pass a provider to the controller when the `PlayerView` is attached. The provider must return `null` after the `AndroidView` is disposed.

In `PlayerRuntimeControllerLifecycle.kt`, release the native controller at the top of `releasePlayer()`:

```kotlin
assSsaRenderController?.release()
assSsaRenderController = null
```

`PlayerRuntimeController.onCleared()` already calls `releasePlayer()`, so this covers ViewModel teardown as well as stream switches.

- [ ] **Step 5: Add the time renderer to the existing factory**

Modify `SubtitleOffsetRenderersFactory` to accept:

```kotlin
private val assSsaRenderControllerProvider: () -> AssSsaRenderController?
```

Override `createRenderers` with the exact `DefaultRenderersFactory` signature:

```kotlin
override fun createRenderers(
    eventHandler: android.os.Handler,
    videoRendererEventListener: VideoRendererEventListener,
    audioRendererEventListener: AudioRendererEventListener,
    textRendererOutput: TextOutput,
    metadataRendererOutput: MetadataOutput
): Array<Renderer> {
    val renderers = super.createRenderers(
        eventHandler,
        videoRendererEventListener,
        audioRendererEventListener,
        textRendererOutput,
        metadataRendererOutput
    ).toMutableList()
    assSsaRenderControllerProvider()?.let { controller ->
        renderers += AssSsaTimeRenderer(controller)
    }
    return renderers.toTypedArray()
}
```

This preserves video, audio, text, metadata, AI translation, subtitle delay, Kodi IEC, and DV5 behavior while appending the no-sample ASS clock renderer.

- [ ] **Step 6: Hook video size, seeks, and overlay suppression**

In the existing player listener setup, call:

```kotlin
override fun onVideoSizeChanged(videoSize: VideoSize) {
    assSsaRenderController?.setVideoSize(videoSize.width, videoSize.height)
}
```

In the existing seek handling path, call:

```kotlin
assSsaRenderController?.onSeekStarted()
```

When the ASS/SSA overlay is active, suppress the native `SubtitleView` and the simple addon cue overlay. When a user selects an SRT/VTT addon subtitle, call `assSsaRenderController?.clearOverlay()` and clear the selected embedded ASS/SSA track override so both subtitle overlays do not render simultaneously.

- [ ] **Step 7: Run tests and commit**

Run: `./gradlew :app:testArm64DebugUnitTest --tests '*PlayerRuntimeControllerAssSsaPipelineTest'`

Expected: PASS.

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerLifecycle.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerVideoSurface.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAssSsaPipelineTest.kt
git commit -m "feat: auto-enable ASS SSA renderer pipeline"
```

Expected: one commit preserving `SubtitleOffsetRenderersFactory` while making ASS/SSA automatic.

### Task 7: Remove Current `ass-media` Libass Toggle Path

**Files:**
- Delete: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerLibassCompat.kt`
- Delete: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerLibassExtensions.kt`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSubtitleSettings.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-fr/strings.xml`
- Modify: `app/src/main/res/values-nl/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`

- [ ] **Step 1: Delete the old wrapper files**

Run:

```bash
git rm app/src/main/java/com/nexio/tv/ui/screens/player/PlayerLibassCompat.kt
git rm app/src/main/java/com/nexio/tv/ui/screens/player/PlayerLibassExtensions.kt
```

Expected: both files staged for deletion.

- [ ] **Step 2: Remove the old dependency**

In `app/build.gradle.kts`, delete:

```kotlin
// libass-android for ASS/SSA subtitle support (from Maven Central)
implementation("io.github.peerless2012:ass-media:0.4.0-beta01")
```

- [ ] **Step 3: Remove settings model fields and setters**

In `PlayerSettingsDataStore.kt`, remove `useLibass`, `libassRenderType`, `LibassRenderType`, `useLibassKey`, `libassRenderTypeKey`, `useLibass` flow, `libassRenderType` flow, `setUseLibass`, and `setLibassRenderType`.

Keep old stored preferences ignored. Do not add migration code that rewrites them; stale DataStore keys are harmless once unread.

- [ ] **Step 4: Remove settings UI**

In `PlaybackSubtitleSettings.kt`, remove the `subtitle_advanced_header`, `subtitle_libass`, and `subtitle_libass_*` items. In caller files, remove `onSetUseLibass` and `onSetLibassRenderType` parameters and callbacks.

- [ ] **Step 5: Remove account sync writes**

In `AccountSettingsSyncService.kt`, delete:

```kotlin
playerSettingsDataStore.setUseLibass(settings.playback.subtitles.useLibass)
```

In `AccountSyncModels.kt`, remove the active `useLibass` property from the subtitles sync data class. If JSON compatibility requires accepting old payloads, keep a private ignored DTO field only inside the decode adapter; do not expose it in `PlayerSettings`.

- [ ] **Step 6: Remove localized strings**

Delete `sub_libass`, `sub_libass_sub`, `sub_libass_mode`, `sub_mode_overlay_gl`, `sub_mode_overlay_gl_sub`, `sub_mode_overlay_canvas`, `sub_mode_overlay_canvas_sub`, `sub_mode_effects_gl`, `sub_mode_effects_gl_sub`, `sub_mode_effects_canvas`, `sub_mode_effects_canvas_sub`, `sub_mode_standard`, and `sub_mode_standard_sub` from each `values*` file if they have no remaining references.

- [ ] **Step 7: Verify no old path remains**

Run:

```bash
rg -n "peerless2012|ass-media|PlayerLibass|LibassRenderType|useLibass|libassRenderType|AssRenderType" app
```

Expected: no matches.

- [ ] **Step 8: Build and commit**

Run: `./gradlew :app:compileArm64DebugKotlin`

Expected: PASS.

Run:

```bash
git add app/build.gradle.kts app/src/main/java app/src/main/res
git commit -m "refactor: remove optional libass settings path"
```

Expected: one cleanup commit with no `peerless2012` dependency or toggle UI.

### Task 8: Validate AI Translation And ASS/SSA Rendering Together

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAiSubtitles.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAddonSubtitleOverlayTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAssSsaPipelineTest.kt`

- [ ] **Step 1: Write test for ASS/SSA translated-file path**

Extend `PlayerRuntimeControllerAddonSubtitleOverlayTest.kt`:

```kotlin
@Test
fun assSsaDoesNotUseSimpleCueOverlay() {
    assertFalse(addonSubtitleSupportsOverlay(MimeTypes.TEXT_SSA))
}
```

- [ ] **Step 2: Confirm AI translation still supports ASS/SSA**

Add or keep a test covering `MimeTypes.TEXT_SSA` in the AI subtitle support helper introduced by `2026-04-14-ass-ssa-auto-translate.md`.

- [ ] **Step 3: Run tests**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests '*PlayerRuntimeControllerAddonSubtitleOverlayTest' --tests '*PlayerRuntimeControllerAssSsaPipelineTest'
```

Expected: PASS.

- [ ] **Step 4: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAiSubtitles.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAddonSubtitleOverlayTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAssSsaPipelineTest.kt
git commit -m "test: preserve ASS SSA translation playback path"
```

Expected: one commit proving ASS/SSA translation and rendering paths do not collapse into the simple SRT/VTT overlay.

### Task 9: Final Verification

**Files:**
- Review all files touched above.

- [ ] **Step 1: Run stale reference search**

Run:

```bash
rg -n "peerless2012|ass-media|PlayerLibass|LibassRenderType|useLibass|libassRenderType|AssRenderType" app docs
```

Expected: no matches except historical references inside this plan and `docs/subtitles/assrender-audit.md`.

- [ ] **Step 2: Run unit tests**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests '*AssSsa*' --tests '*PlayerRuntimeControllerAssSsaPipelineTest' --tests '*PlayerRuntimeControllerAddonSubtitleOverlayTest'
```

Expected: PASS.

- [ ] **Step 3: Run Kotlin compile**

Run:

```bash
./gradlew :app:compileArm64DebugKotlin
```

Expected: PASS.

- [ ] **Step 4: Run native instrumentation smoke test**

Run:

```bash
./gradlew :app:connectedArm64DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nexio.tv.instrumentation.AssSsaNativeRenderSmokeTest
```

Expected: PASS on a connected device/emulator. If no device is connected, record "not run - no Android device" in the implementation handoff and do not claim native runtime verification.

- [ ] **Step 5: Commit final verification notes**

If verification changes docs or tests, commit them:

```bash
git add docs/subtitles app/src/test app/src/androidTest
git commit -m "test: verify ASS SSA renderer migration"
```

Expected: commit only if files changed during verification.

## Self-Review

- Spec coverage: The plan covers automatic ASS/SSA routing, preserving `SubtitleOffsetRenderersFactory`, AI translation compatibility, full Aegisub tag coverage by raw libass delivery, and cleanup of the current `ass-media` toggle path.
- Placeholder scan: No task relies on unspecified future work; each task names exact files, commands, and expected outcomes.
- Type consistency: New classes use `AssSsa*` naming throughout; old `LibassRenderType`, `AssRenderType`, and `PlayerLibass*` types are removed in Task 7.
