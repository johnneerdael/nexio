# Universal Formatter Smaller Filename And Larger Repositioned Media Icons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move Universal formatter media icons to the last detail line, render those icons larger per token, and make the filename line compact wherever it appears.

**Architecture:** Extend the inline icon token model with an optional per-token scale override parsed from `[[icon:id:scale]]`. Keep default rendering unchanged for existing `[[icon:id]]` tokens, then update only the Universal description template to emit scaled media-quality icons on the last line. Make the filename compaction helper prefix-based instead of final-line-based so the reordered Universal template keeps the smaller filename styling.

**Tech Stack:** Kotlin, Jetpack Compose text inline content, Android JVM unit tests, Gradle Android build tasks.

---

## File Structure

- Modify `app/src/main/java/com/nexio/tv/ui/components/InlineIconTokenRegistry.kt`: parse optional scale suffixes and store them on icon segments.
- Modify `app/src/main/java/com/nexio/tv/ui/components/InlineIconText.kt`: choose the scale override when present while preserving `ScaleClass` defaults for all other icons.
- Modify `app/src/main/java/com/nexio/tv/core/stream/AioStreamFormatting.kt`: reorder `AioBuiltInFormatters.UNIVERSAL.descriptionTemplate` lines and add `:1.75` to media quality icon tokens only.
- Modify `app/src/main/java/com/nexio/tv/ui/components/StreamDetailLines.kt`: compact `📄 ` filename lines at any position and reduce the compact style to `7.sp` / `10.sp`.
- Modify `app/src/test/java/com/nexio/tv/ui/components/InlineIconTokenRegistryTest.kt`: assert scale suffix tokenization and default-null behavior.
- Create `app/src/test/java/com/nexio/tv/ui/components/InlineIconTextTest.kt`: assert the rendering scale selector uses overrides and defaults.
- Modify `app/src/test/java/com/nexio/tv/core/stream/AioTemplateFormatterTest.kt`: assert Universal line order and scaled icon tokens.
- Modify `app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt`: update Universal expected detail-line output.
- Modify `app/src/test/java/com/nexio/tv/ui/components/StreamDetailLinesTest.kt`: assert filename compaction is position-independent and uses `7.sp` / `10.sp`.

## Task 1: Tokenizer Scale Override

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/components/InlineIconTokenRegistryTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/InlineIconTokenRegistry.kt`

- [ ] **Step 1: Write the failing tokenizer test**

In `app/src/test/java/com/nexio/tv/ui/components/InlineIconTokenRegistryTest.kt`, add this import:

```kotlin
import org.junit.Assert.assertNull
```

Add this test inside `class InlineIconTokenRegistryTest`:

```kotlin
@Test
fun `tokenize parses optional icon scale override`() {
    val segments = InlineIconTokenRegistry.tokenize("Before [[icon:dovi:1.75]] after [[icon:hdr10]]")
    val iconSegments = segments.filterIsInstance<InlineIconSegment.IconSegment>()

    assertEquals(2, iconSegments.size)
    assertEquals("dovi", iconSegments[0].token.id)
    assertEquals(1.75f, iconSegments[0].scaleOverride ?: -1f, 0.0001f)
    assertEquals("hdr10", iconSegments[1].token.id)
    assertNull(iconSegments[1].scaleOverride)
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.components.InlineIconTokenRegistryTest'
```

Expected: FAIL at Kotlin compilation because `InlineIconSegment.IconSegment` does not yet expose `scaleOverride`.

- [ ] **Step 3: Add the scale field and parser**

In `app/src/main/java/com/nexio/tv/ui/components/InlineIconTokenRegistry.kt`, replace the sealed interface model and token pattern with:

```kotlin
sealed interface InlineIconSegment {
    data class TextSegment(val text: String) : InlineIconSegment
    data class IconSegment(
        val token: InlineIconToken,
        val scaleOverride: Float? = null
    ) : InlineIconSegment
}

object InlineIconTokenRegistry {
    private val tokenPattern = Regex("""\[\[icon:([a-z0-9_]+)(?::([0-9]+(?:\.[0-9]+)?))?\]\]""", RegexOption.IGNORE_CASE)
```

In the `tokenize()` loop, replace the icon segment construction block with:

```kotlin
val rawTokenId = match.groupValues[1]
val rawScaleOverride = match.groups[2]?.value
val token = resolve(rawTokenId)
if (token != null) {
    segments += InlineIconSegment.IconSegment(
        token = token,
        scaleOverride = rawScaleOverride?.toFloatOrNull()
    )
} else {
    segments.appendText(rawTokenId)
}
```

- [ ] **Step 4: Run the focused test to verify it passes**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.components.InlineIconTokenRegistryTest'
```

Expected: PASS for `InlineIconTokenRegistryTest`.

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/components/InlineIconTokenRegistry.kt app/src/test/java/com/nexio/tv/ui/components/InlineIconTokenRegistryTest.kt
git commit -m "feat: parse inline icon scale overrides"
```

## Task 2: Inline Icon Rendering Scale

**Files:**
- Create: `app/src/test/java/com/nexio/tv/ui/components/InlineIconTextTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/InlineIconText.kt`

- [ ] **Step 1: Write the failing scale selection tests**

Create `app/src/test/java/com/nexio/tv/ui/components/InlineIconTextTest.kt` with:

```kotlin
package com.nexio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class InlineIconTextTest {

    @Test
    fun `inline icon scale uses override when present`() {
        val token = InlineIconTokenRegistry.resolve("dovi") ?: error("dovi token missing")
        val segment = InlineIconSegment.IconSegment(
            token = token,
            scaleOverride = 1.75f
        )

        assertEquals(1.75f, inlineIconScale(segment), 0.0001f)
    }

    @Test
    fun `inline icon scale falls back to scale class when override is absent`() {
        val titleToken = InlineIconTokenRegistry.resolve("4k") ?: error("4k token missing")
        val inlineToken = InlineIconTokenRegistry.resolve("dovi") ?: error("dovi token missing")

        assertEquals(
            1.1f,
            inlineIconScale(InlineIconSegment.IconSegment(titleToken)),
            0.0001f
        )
        assertEquals(
            1.0f,
            inlineIconScale(InlineIconSegment.IconSegment(inlineToken)),
            0.0001f
        )
    }
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.components.InlineIconTextTest'
```

Expected: FAIL at Kotlin compilation because `inlineIconScale` is not defined.

- [ ] **Step 3: Add a testable scale helper and use it in rendering**

In `app/src/main/java/com/nexio/tv/ui/components/InlineIconText.kt`, replace this block inside `mapIndexedNotNull`:

```kotlin
val scale = when (token.scaleClass) {
    ScaleClass.TITLE_PROMINENT -> 1.1f
    ScaleClass.INLINE -> 1.0f
}
```

with:

```kotlin
val scale = inlineIconScale(iconSegment)
```

Add this top-level helper near the bottom of the file, before `private fun decodeBitmapResource(...)`:

```kotlin
internal fun inlineIconScale(iconSegment: InlineIconSegment.IconSegment): Float {
    iconSegment.scaleOverride?.let { return it }

    return when (iconSegment.token.scaleClass) {
        ScaleClass.TITLE_PROMINENT -> 1.1f
        ScaleClass.INLINE -> 1.0f
    }
}
```

- [ ] **Step 4: Run the focused test to verify it passes**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.components.InlineIconTextTest'
```

Expected: PASS for `InlineIconTextTest`.

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/components/InlineIconText.kt app/src/test/java/com/nexio/tv/ui/components/InlineIconTextTest.kt
git commit -m "feat: apply inline icon scale overrides"
```

## Task 3: Universal Template Line Order And Scaled Media Icons

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/stream/AioTemplateFormatterTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/stream/AioStreamFormatting.kt`

- [ ] **Step 1: Update formatter expectations before implementation**

In `app/src/test/java/com/nexio/tv/core/stream/AioTemplateFormatterTest.kt`, inside `built in universal template renders richer aio style multiline output`, replace the icon and detail assertions after `assertFalse(titleLine.startsWith("⭐"))` with:

```kotlin
assertEquals(
    listOf(
        "💾 10.74 GB",
        "[[icon:netflix]] Netflix • [[icon:realdebrid]] Real-Debrid",
        "📄 Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.NF.mkv",
        "[[icon:dovi:1.75]] [[icon:atmos:1.75]] [[icon:truehd:1.75]]"
    ),
    detailLine.lines()
)
```

In `app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt`, inside `shrinking filename maps to clean aio style title and details`, replace the expected `item.detailLines` list with:

```kotlin
listOf(
    "💾 10.74 GB",
    "[[icon:appletv]] Apple TV+ • [[icon:premiumize]] Premiumize",
    "📄 Shrinking.S03E06.Dereks.Dont.Die.1080p.ATVP.WEB-DL.DDP5.1.Atmos.ENG.ITA.H264-TheShrink.mkv",
    "[[icon:atmos:1.75]] [[icon:ddp:1.75]]"
)
```

In `app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt`, inside `shelter filename maps to clean movie title and languages`, replace the expected `item.detailLines` list with:

```kotlin
listOf(
    "💾 10.74 GB",
    "[[icon:realdebrid]] Real-Debrid",
    "📄 Shelter.2026.MULTi.VFQ.2160p.HDR.WEB-DL.H265-Slay3R.mkv",
    "[[icon:hdr10:1.75]]"
)
```

In `app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt`, inside `universal template renders full aio style movie card details`, replace the three media icon assertions with:

```kotlin
assertTrue(detailOutput.contains("[[icon:atmos:1.75]]"))
assertTrue(detailOutput.contains("[[icon:truehd:1.75]]"))
assertTrue(detailOutput.contains("[[icon:dovi:1.75]]"))
```

- [ ] **Step 2: Run focused formatter tests to verify they fail**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.core.stream.AioTemplateFormatterTest' --tests 'com.nexio.tv.core.stream.StreamPresentationEngineTest'
```

Expected: FAIL because Universal still emits media icons on the first detail line without `:1.75`.

- [ ] **Step 3: Reorder the Universal description template**

In `app/src/main/java/com/nexio/tv/core/stream/AioStreamFormatting.kt`, replace only `AioBuiltInFormatters.UNIVERSAL.descriptionTemplate` with:

```kotlin
descriptionTemplate = """
💾 {stream.size::>0["{stream.size::bytes}"||"Unknown"]}{stream.size::>0::and::stream.duration::>0[" • "||""]}{stream.duration::>0["⏱️ {stream.duration::time}"||""]}
{stream.filename::~NF["[[icon:netflix]] Netflix"||""]}{stream.filename::~DSNP["[[icon:disneyplus]] Disney+"||""]}{stream.filename::~HMAX["[[icon:hbo]] HBO Max"||""]}{stream.filename::~.MAX.["[[icon:max]] Max"||""]}{stream.filename::~AMZN["[[icon:prime]] Amazon"||""]}{stream.filename::~APTV["[[icon:appletv]] Apple TV+"||""]}{stream.filename::~ATVP["[[icon:appletv]] Apple TV+"||""]}{stream.filename::~PMTP["[[icon:paramount]] Paramount+"||""]}{stream.filename::~PCOK["[[icon:peacock]] Peacock"||""]}{stream.filename::~CRTC["[[icon:crunchyroll]] Crunchyroll"||""]}{stream.filename::~CR.["[[icon:crunchyroll]] Crunchyroll"||""]}{stream.filename::~NF::or::stream.filename::~DSNP::or::stream.filename::~HMAX::or::stream.filename::~.MAX.::or::stream.filename::~AMZN::or::stream.filename::~APTV::or::stream.filename::~ATVP::or::stream.filename::~PMTP::or::stream.filename::~PCOK::or::stream.filename::~CRTC::or::stream.filename::~CR.::and::service.name::exists[" • "||""]}{service.name::~Real-Debrid["[[icon:realdebrid]] Real-Debrid"||""]}{service.name::~Premiumize["[[icon:premiumize]] Premiumize"||""]}{service.name::~AllDebrid["[[icon:alldebrid]] AllDebrid"||""]}{service.name::~Debrid-Link["[[icon:debridlink]] Debrid-Link"||""]}{service.name::~TorBox["[[icon:torbox]] TorBox"||""]}{service.name::~Offcloud["[[icon:offcloud]] Offcloud"||""]}{service.name::~put.io["[[icon:putio]] put.io"||""]}{service.name::~EasyDebrid["[[icon:easydebrid]] EasyDebrid"||""]}{service.name::~Debrider["[[icon:debrider]] Debrider"||""]}{service.name::~PikPak["[[icon:pikpak]] PikPak"||""]}{service.name::~Seedr["[[icon:seedr]] Seedr"||""]}{service.name::~Easynews["[[icon:easynews]] Easynews"||""]}{service.name::~NzbDAV["[[icon:nzbdav]] NzbDAV"||""]}{service.name::~AltMount["[[icon:altmount]] AltMount"||""]}{service.name::~Stremio NNTP["[[icon:stremionntp]] Stremio NNTP"||""]}{service.name::~StremThru Newz["[[icon:stremthrunewz]] StremThru Newz"||""]}{service.name::~Real-Debrid::isfalse::and::service.name::~Premiumize::isfalse::and::service.name::~AllDebrid::isfalse::and::service.name::~Debrid-Link::isfalse::and::service.name::~TorBox::isfalse::and::service.name::~Offcloud::isfalse::and::service.name::~put.io::isfalse::and::service.name::~EasyDebrid::isfalse::and::service.name::~Debrider::isfalse::and::service.name::~PikPak::isfalse::and::service.name::~Seedr::isfalse::and::service.name::~Easynews::isfalse::and::service.name::~NzbDAV::isfalse::and::service.name::~AltMount::isfalse::and::service.name::~Stremio NNTP::isfalse::and::service.name::~StremThru Newz::isfalse::and::service.name::exists["{service.name}"||""]}
📄 {stream.filename::exists["{stream.filename}"||"—"]}
{stream.visualTags::exists["{stream.visualTags::join(' ')::replace('Dolby Vision','[[icon:dovi:1.75]]')::replace('DoVi','[[icon:dovi:1.75]]')::replace('DV','[[icon:dovi:1.75]]')::replace('HDR10+','[[icon:hdr10:1.75]]')::replace('HDR10','[[icon:hdr10:1.75]]')::replace('HDR','[[icon:hdr10:1.75]]')}"||""]}{stream.visualTags::exists::and::stream.audioTags::exists[" "||""]}{stream.audioTags::exists["{stream.audioTags::join(' ')::replace('Atmos','[[icon:atmos:1.75]]')::replace('TrueHD','[[icon:truehd:1.75]]')::replace('DTS-HD MA','[[icon:dtshd:1.75]]')::replace('DTS:X','[[icon:dtsx:1.75]]')::replace('DD+','[[icon:ddp:1.75]]')::replace('DD','[[icon:dd:1.75]]')::replace('EAC3','[[icon:ddp:1.75]]')::replace('AC3','[[icon:dd:1.75]]')::replace('DTS','[[icon:dts:1.75]]')}"||""]}
""".trimIndent()
```

- [ ] **Step 4: Run focused formatter tests to verify they pass**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.core.stream.AioTemplateFormatterTest' --tests 'com.nexio.tv.core.stream.StreamPresentationEngineTest'
```

Expected: PASS for `AioTemplateFormatterTest` and `StreamPresentationEngineTest`.

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/stream/AioStreamFormatting.kt app/src/test/java/com/nexio/tv/core/stream/AioTemplateFormatterTest.kt app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt
git commit -m "feat: reposition universal formatter media icons"
```

## Task 4: Filename Compaction At Any Detail-Line Position

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/components/StreamDetailLinesTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/StreamDetailLines.kt`

- [ ] **Step 1: Update the filename compaction test before implementation**

In `app/src/test/java/com/nexio/tv/ui/components/StreamDetailLinesTest.kt`, replace `compacts only final filename line` with:

```kotlin
@Test
fun `compacts filename line at any position`() {
    val detailLines = listOf(
        "💾 41.38 GB",
        "[[icon:prime]] Amazon • [[icon:premiumize]] Premiumize",
        "📄 Avatar.Fire.And.Ash.2025.2160p.AMZN.WEB-DL.DDP5.1.mkv",
        "[[icon:hdr10:1.75]] [[icon:ddp:1.75]]"
    )

    assertFalse(shouldCompactFinalFilenameLine(detailLines, 0))
    assertFalse(shouldCompactFinalFilenameLine(detailLines, 1))
    assertTrue(shouldCompactFinalFilenameLine(detailLines, 2))
    assertFalse(shouldCompactFinalFilenameLine(detailLines, 3))

    val compactStyle = streamDetailLineStyle(
        detailLines = detailLines,
        index = 2,
        baseStyle = baseStyle
    )

    assertEquals(7.sp, compactStyle.fontSize)
    assertEquals(10.sp, compactStyle.lineHeight)
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.components.StreamDetailLinesTest'
```

Expected: FAIL because `shouldCompactFinalFilenameLine(detailLines, 2)` is false while the filename is not the final line, and the compact style is still `10.sp` / `14.sp`.

- [ ] **Step 3: Make filename compaction position-independent and smaller**

In `app/src/main/java/com/nexio/tv/ui/components/StreamDetailLines.kt`, replace `streamDetailLineStyle` and `shouldCompactFinalFilenameLine` with:

```kotlin
internal fun streamDetailLineStyle(
    detailLines: List<String>,
    index: Int,
    baseStyle: TextStyle
): TextStyle {
    if (!shouldCompactFinalFilenameLine(detailLines, index)) return baseStyle
    return baseStyle.copy(fontSize = 7.sp, lineHeight = 10.sp)
}

internal fun shouldCompactFinalFilenameLine(
    detailLines: List<String>,
    index: Int
): Boolean {
    val detail = detailLines.getOrNull(index)?.trim().orEmpty()
    return detail.startsWith(FilenameLinePrefix)
}
```

- [ ] **Step 4: Run the focused test to verify it passes**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.ui.components.StreamDetailLinesTest'
```

Expected: PASS for `StreamDetailLinesTest`.

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/components/StreamDetailLines.kt app/src/test/java/com/nexio/tv/ui/components/StreamDetailLinesTest.kt
git commit -m "feat: compact universal filename detail line"
```

## Task 5: Final Verification

**Files:**
- Verify: `app/src/main/java/com/nexio/tv/ui/components/InlineIconTokenRegistry.kt`
- Verify: `app/src/main/java/com/nexio/tv/ui/components/InlineIconText.kt`
- Verify: `app/src/main/java/com/nexio/tv/core/stream/AioStreamFormatting.kt`
- Verify: `app/src/main/java/com/nexio/tv/ui/components/StreamDetailLines.kt`
- Verify: `app/src/test/java/com/nexio/tv/ui/components/InlineIconTokenRegistryTest.kt`
- Verify: `app/src/test/java/com/nexio/tv/ui/components/InlineIconTextTest.kt`
- Verify: `app/src/test/java/com/nexio/tv/core/stream/AioTemplateFormatterTest.kt`
- Verify: `app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt`
- Verify: `app/src/test/java/com/nexio/tv/ui/components/StreamDetailLinesTest.kt`

- [ ] **Step 1: Run all requested unit tests**

Run:

```bash
./gradlew testArm64DebugUnitTest
```

Expected: PASS for the Arm64 debug JVM unit test suite.

- [ ] **Step 2: Run the requested build**

Run:

```bash
./gradlew assembleArm64Debug
```

Expected: PASS and an Arm64 debug APK is assembled.

- [ ] **Step 3: Inspect the changed Universal formatter output in tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests 'com.nexio.tv.core.stream.AioTemplateFormatterTest.built in universal template renders richer aio style multiline output'
```

Expected: PASS with the Universal description lines in this order:

```text
💾 10.74 GB
[[icon:netflix]] Netflix • [[icon:realdebrid]] Real-Debrid
📄 Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.NF.mkv
[[icon:dovi:1.75]] [[icon:atmos:1.75]] [[icon:truehd:1.75]]
```

- [ ] **Step 4: Perform the requested visual check on device**

Open a Universal formatter stream card on the target TV/device using a filename with media tags such as:

```text
Shrinking.S03E06.Dereks.Dont.Die.1080p.ATVP.WEB-DL.DDP5.1.Atmos.ENG.ITA.H264-TheShrink.mkv
```

Expected visual result:

```text
💾 10.74 GB
Apple TV+ • Premiumize
📄 Shrinking.S03E06.Dereks.Dont.Die.1080p.ATVP.WEB-DL.DDP5.1.Atmos.ENG.ITA.H264-TheShrink.mkv
Atmos icon and DD+ icon at 1.75x on the last detail line
```

- [ ] **Step 5: Spot-check non-Universal formatter output**

Use existing formatter selections for Torrentio, GDrive, Prism, and Light GDrive.

Expected: their template order and unscaled `[[icon:id]]` tokens remain visually unchanged because only `AioBuiltInFormatters.UNIVERSAL.descriptionTemplate` emits `[[icon:id:1.75]]`.

- [ ] **Step 6: Commit verification cleanup if any test-only adjustment was needed**

If final verification required no file edits, skip this step. If a test expectation needed a correction to match the intended behavior, run:

```bash
git add app/src/test/java/com/nexio/tv/ui/components/InlineIconTokenRegistryTest.kt app/src/test/java/com/nexio/tv/ui/components/InlineIconTextTest.kt app/src/test/java/com/nexio/tv/core/stream/AioTemplateFormatterTest.kt app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt app/src/test/java/com/nexio/tv/ui/components/StreamDetailLinesTest.kt
git commit -m "test: align universal formatter expectations"
```

## Self-Review

- Spec coverage: Task 1 covers optional `:scale` token syntax and the nullable `scaleOverride`; Task 2 covers render-time override selection; Task 3 covers Universal line reorder and `:1.75` media tokens; Task 4 covers filename compaction at any line position and the `7.sp` / `10.sp` size; Task 5 covers build, unit tests, device visual check, and non-Universal spot checks.
- Placeholder scan: no placeholder steps are left in the task list; code blocks contain exact snippets and commands.
- Type consistency: `scaleOverride` is a `Float?` on `InlineIconSegment.IconSegment`; `inlineIconScale` accepts `InlineIconSegment.IconSegment` and returns `Float`; tests use the same names.
- Scope check: no plugin release metadata, root changelog entries, or README component counts are touched because this change is Android app UI and formatter behavior, not `plugins/compound-engineering/` plugin development.
