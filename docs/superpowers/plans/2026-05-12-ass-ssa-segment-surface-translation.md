# ASS/SSA Segment-Surface Translation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace both existing ASS/SSA translation modes with one parser-driven segment-surface pipeline that sends only plain segment arrays and local intraword markers to the LLM.

**Architecture:** Introduce a focused ASS/SSA segment-surface model that parses each event `Text` field into raw prefix/separators/suffix plus translatable segments. Route file and embedded-sample ASS/SSA translation through structured JSON response validation, per-event fallback, and deterministic recomposition. Remove the user-facing ASS/SSA raw system-prompt setting and all raw ASS/SSA prompt code while leaving SRT raw prompt behavior intact.

**Tech Stack:** Kotlin, Android app module, JUnit4, kotlinx-coroutines-test, MockWebServer, existing `org.json` request/response parsing, existing ASS/SSA tokenizer and event-record parser.

---

## File Structure

- Create `app/src/main/java/com/nexio/tv/data/repository/AssSsaSegmentSurfaceTranslation.kt`
  - Owns `AssSsaTranslationSurface`, parse result types, segment parser, response validation helpers, and recomposition.
  - Depends on `AssSsaTextTokenizer` only for text-tokenization; no provider or UI dependencies.
- Create `app/src/test/java/com/nexio/tv/data/repository/AssSsaSegmentSurfaceTranslationTest.kt`
  - Parser, validation, repair, and recomposition unit tests.
- Modify `app/src/main/java/com/nexio/tv/data/repository/TimedTextDocument.kt`
  - Replace `assSsaProtectedUnits()` / `renderAssSsaProtected()` with segment-surface equivalents.
- Modify `app/src/main/java/com/nexio/tv/data/repository/AssSsaTimedTextDocument.kt`
  - Expose raw text and render-with-text behavior from `AssSsaDialogueBlock`.
- Modify `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`
  - Replace protected ASS/SSA request/response path and raw ASS path with segment-surface request/response path.
  - Keep SRT raw prompt code unchanged.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt`
  - Remove raw-mode branching and translate embedded samples with segment surfaces.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - Remove raw ASS lambdas from `AssSsaTranslatingSampleSink` construction.
- Modify `app/src/main/java/com/nexio/tv/domain/model/GeminiSettings.kt`
  - Remove `assSsaSystemPromptEnabled`.
- Modify `app/src/main/java/com/nexio/tv/data/local/SubtitleTranslationSettingsDataStore.kt`
  - Stop reading/writing the ASS raw prompt preference and remove its setter.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/SubtitleTranslationSettingsViewModel.kt`
  - Remove ASS raw prompt UI state and setter.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/SubtitleTranslationSettingsScreen.kt`
  - Remove ASS raw prompt toggle row.
- Modify `app/src/main/res/values/strings.xml`
  - Remove ASS raw prompt title/subtitle strings.
- Modify `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`
  - Confirm domain conversion compiles without the removed field.
- Modify tests:
  - `app/src/test/java/com/nexio/tv/data/repository/AssSsaTimedTextDocumentTest.kt`
  - `app/src/test/java/com/nexio/tv/data/repository/AssSsaLocalFixtureRegressionTest.kt`
  - `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServiceProviderTest.kt`
  - `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServicePromptTest.kt`
  - `app/src/test/java/com/nexio/tv/data/local/SubtitleTranslationSettingsDataStoreTest.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/settings/SubtitleTranslationSettingsViewModelTest.kt`

## Task 1: Segment Surface Parser And Recomposer

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/AssSsaSegmentSurfaceTranslation.kt`
- Create: `app/src/test/java/com/nexio/tv/data/repository/AssSsaSegmentSurfaceTranslationTest.kt`

- [ ] **Step 1: Write failing parser/recomposer tests**

Create `app/src/test/java/com/nexio/tv/data/repository/AssSsaSegmentSurfaceTranslationTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssSsaSegmentSurfaceTranslationTest {
    @Test
    fun leadingStyleBlockBecomesRawPrefix() {
        val surface = parseSurface(
            "{\\fad(390,350)\\shad0\\fnArial\\an3\\blur2\\fs17\\b1\\pos(600,307)\\c&H2D6E87&}Initiative"
        )

        assertEquals(
            "{\\fad(390,350)\\shad0\\fnArial\\an3\\blur2\\fs17\\b1\\pos(600,307)\\c&H2D6E87&}",
            surface.prefixRaw
        )
        assertEquals(listOf("Initiative"), surface.segments)
        assertEquals(emptyList<String>(), surface.separators)
        assertEquals("Initiative", surface.context)
        assertEquals(
            "{\\fad(390,350)\\shad0\\fnArial\\an3\\blur2\\fs17\\b1\\pos(600,307)\\c&H2D6E87&}Initiatief",
            surface.recomposeOrThrow(listOf("Initiatief"))
        )
    }

    @Test
    fun intrawordOverrideBlockBecomesLocalMarker() {
        val surface = parseSurface("I{\\c&H0F00A1&}nitiative")

        assertEquals("", surface.prefixRaw)
        assertEquals(listOf("I<1/>nitiative"), surface.segments)
        assertEquals(mapOf("<1/>" to "{\\c&H0F00A1&}"), surface.inlineMarkers)
        assertEquals(
            "I{\\c&H0F00A1&}nitiatief",
            surface.recomposeOrThrow(listOf("I<1/>nitiatief"))
        )
    }

    @Test
    fun formattingAroundWordUsesSegmentSeparators() {
        val surface = parseSurface("with {\\i1}me{\\i0} today")

        assertEquals(listOf("with", "me", "today"), surface.segments)
        assertEquals(listOf(" {\\i1}", "{\\i0} "), surface.separators)
        assertEquals(
            "met {\\i1}mij{\\i0} vandaag",
            surface.recomposeOrThrow(listOf("met", "mij", "vandaag"))
        )
    }

    @Test
    fun lineBreakAndItalicTagsBecomeSeparators() {
        val surface = parseSurface("On the contrary \\Nfrom the start that he {\\i1}couldn't{\\i0} be X.")

        assertEquals(
            listOf("On the contrary", "from the start that he", "couldn't", "be X."),
            surface.segments
        )
        assertEquals(listOf(" \\N", " {\\i1}", "{\\i0} "), surface.separators)
        assertEquals(
            "Integendeel \\Nvanaf het begin dat hij {\\i1}niet{\\i0} X kon zijn.",
            surface.recomposeOrThrow(
                listOf("Integendeel", "vanaf het begin dat hij", "niet", "X kon zijn.")
            )
        )
    }

    @Test
    fun karaokeBetweenWordsUsesSeparators() {
        val surface = parseSurface("{\\k20}Good {\\K30}morning")

        assertEquals("{\\k20}", surface.prefixRaw)
        assertEquals(listOf("Good", "morning"), surface.segments)
        assertEquals(listOf(" {\\K30}"), surface.separators)
        assertEquals("{\\k20}Goed {\\K30}morgen", surface.recomposeOrThrow(listOf("Goed", "morgen")))
    }

    @Test
    fun karaokeInsideWordUsesInlineMarker() {
        val surface = parseSurface("go{\\k10}od")

        assertEquals(listOf("go<1/>od"), surface.segments)
        assertEquals(mapOf("<1/>" to "{\\k10}"), surface.inlineMarkers)
        assertEquals("go{\\k10}ed", surface.recomposeOrThrow(listOf("go<1/>ed")))
    }

    @Test
    fun drawingOnlyIsPreserveOnly() {
        val result = AssSsaSegmentSurfaceParser.parse("evt_0", "{\\p1}m 0 0 l 100 0{\\p0}")

        assertTrue(result is AssSsaSurfaceParseResult.PreserveOnly)
    }

    @Test
    fun validationRepairsSpacesAroundKnownMarkers() {
        val surface = parseSurface("I{\\c&H0F00A1&}nitiative")
        val repaired = surface.validateTranslatedSegments(listOf("I <1/> nitiatief")).getOrThrow()

        assertEquals(listOf("I<1/>nitiatief"), repaired)
        assertEquals("I{\\c&H0F00A1&}nitiatief", surface.recomposeOrThrow(repaired))
    }

    @Test
    fun validationRejectsRawAssSyntax() {
        val surface = parseSurface("Hello")
        val result = surface.validateTranslatedSegments(listOf("{\\i1}Hallo"))

        assertTrue(result.isFailure)
    }

    private fun parseSurface(text: String): AssSsaTranslationSurface {
        return when (val result = AssSsaSegmentSurfaceParser.parse("evt_0", text)) {
            is AssSsaSurfaceParseResult.Translatable -> result.surface
            is AssSsaSurfaceParseResult.PreserveOnly -> error("Expected translatable surface for $text")
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.data.repository.AssSsaSegmentSurfaceTranslationTest'
```

Expected: compilation fails because `AssSsaSegmentSurfaceParser`, `AssSsaSurfaceParseResult`, and `AssSsaTranslationSurface` do not exist.

- [ ] **Step 3: Add segment-surface model and parser**

Create `app/src/main/java/com/nexio/tv/data/repository/AssSsaSegmentSurfaceTranslation.kt`:

```kotlin
package com.nexio.tv.data.repository

import org.json.JSONArray
import org.json.JSONObject

internal sealed interface AssSsaSurfaceParseResult {
    data class Translatable(val surface: AssSsaTranslationSurface) : AssSsaSurfaceParseResult
    data class PreserveOnly(val rawText: String) : AssSsaSurfaceParseResult
}

internal data class AssSsaTranslationSurface(
    val id: String,
    val originalText: String,
    val prefixRaw: String,
    val segments: List<String>,
    val separators: List<String>,
    val suffixRaw: String,
    val inlineMarkers: Map<String, String>,
    val context: String
) {
    init {
        require(separators.size == (segments.size - 1).coerceAtLeast(0)) {
            "ASS/SSA surface separator count must be one less than segment count."
        }
    }

    fun validateTranslatedSegments(translatedSegments: List<String>): Result<List<String>> = runCatching {
        check(translatedSegments.size == segments.size) {
            "Translated ASS/SSA segment count changed for $id."
        }
        val markerRegex = Regex("""<\d+/>""")
        val repaired = translatedSegments.map { segment ->
            var next = segment
            inlineMarkers.keys.forEach { marker ->
                next = next.replace(Regex("""\s*${Regex.escape(marker)}\s*"""), marker)
            }
            next
        }
        repaired.forEachIndexed { index, segment ->
            check(!segment.contains('\n') && !segment.contains('\r')) {
                "Translated ASS/SSA segment $index contains a line break."
            }
            check(!RAW_ASS_SURFACE_SYNTAX_PATTERN.containsMatchIn(segment)) {
                "Translated ASS/SSA segment $index introduced ASS/SSA syntax."
            }
            val unknownMarkers = markerRegex.findAll(segment)
                .map { it.value }
                .filterNot { it in inlineMarkers }
                .toList()
            check(unknownMarkers.isEmpty()) {
                "Translated ASS/SSA segment $index introduced unknown markers ${unknownMarkers.joinToString()}."
            }
            if (segments[index].isNotEmpty()) {
                check(segment.isNotEmpty()) {
                    "Translated ASS/SSA segment $index is empty."
                }
            }
        }
        inlineMarkers.keys.forEach { marker ->
            val count = repaired.sumOf { segment -> Regex.escape(marker).toRegex().findAll(segment).count() }
            check(count == 1) {
                "Translated ASS/SSA item $id must contain marker $marker exactly once."
            }
        }
        repaired
    }

    fun recomposeOrThrow(translatedSegments: List<String>): String {
        val checkedSegments = validateTranslatedSegments(translatedSegments).getOrThrow()
        val out = StringBuilder(prefixRaw)
        checkedSegments.forEachIndexed { index, translated ->
            var segment = translated
            inlineMarkers.forEach { (marker, rawAss) ->
                segment = segment.replace(marker, rawAss)
            }
            out.append(segment)
            if (index < separators.size) {
                out.append(separators[index])
            }
        }
        out.append(suffixRaw)
        return out.toString()
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("context", context)
            .put("segments", JSONArray().apply { segments.forEach(::put) })
    }
}

internal object AssSsaSegmentSurfaceParser {
    fun parse(id: String, text: String): AssSsaSurfaceParseResult {
        val tokens = AssSsaTextTokenizer.tokenize(text)
        if (tokens.none { it is AssSsaTextToken.Text && it.raw.isNotBlank() }) {
            return AssSsaSurfaceParseResult.PreserveOnly(text)
        }
        if (tokens.any { it is AssSsaTextToken.Malformed }) {
            return AssSsaSurfaceParseResult.PreserveOnly(text)
        }

        val prefix = StringBuilder()
        val suffix = StringBuilder()
        val segments = mutableListOf<String>()
        val separators = mutableListOf<String>()
        val inlineMarkers = linkedMapOf<String, String>()
        var current = StringBuilder()
        var pendingSeparator = StringBuilder()
        var sawVisibleText = false
        var markerIndex = 1

        fun flushCurrent() {
            val raw = current.toString()
            if (raw.isEmpty()) return
            val leading = raw.takeWhile { it.isWhitespace() }
            val trailing = raw.takeLastWhile { it.isWhitespace() }
            val bodyEnd = raw.length - trailing.length
            val body = raw.substring(leading.length, bodyEnd)
            if (body.isEmpty()) {
                pendingSeparator.append(raw)
                current = StringBuilder()
                return
            }
            if (segments.isNotEmpty()) {
                if (leading.isNotEmpty()) pendingSeparator.append(leading)
                separators += pendingSeparator.toString()
                pendingSeparator = StringBuilder()
            } else if (!sawVisibleText) {
                prefix.append(leading)
            }
            segments += body
            if (trailing.isNotEmpty()) pendingSeparator.append(trailing)
            sawVisibleText = true
            current = StringBuilder()
        }

        tokens.forEachIndexed { index, token ->
            when (token) {
                is AssSsaTextToken.Text -> current.append(token.raw)
                is AssSsaTextToken.Drawing -> {
                    if (!sawVisibleText && current.isEmpty()) {
                        return AssSsaSurfaceParseResult.PreserveOnly(text)
                    }
                    flushCurrent()
                    pendingSeparator.append(token.raw)
                }
                is AssSsaTextToken.OverrideBlock,
                is AssSsaTextToken.LineBreak,
                is AssSsaTextToken.HardSpace -> {
                    val raw = token.raw
                    val left = previousSurfaceChar(tokens, index, current.toString())
                    val right = nextSurfaceChar(tokens, index)
                    if (left != null && right != null && left.isAssSsaWordChar() && right.isAssSsaWordChar()) {
                        val marker = "<${markerIndex++}/>"
                        inlineMarkers[marker] = raw
                        current.append(marker)
                    } else {
                        flushCurrent()
                        if (!sawVisibleText && segments.isEmpty()) {
                            prefix.append(raw)
                        } else {
                            pendingSeparator.append(raw)
                        }
                    }
                }
                is AssSsaTextToken.Malformed -> return AssSsaSurfaceParseResult.PreserveOnly(text)
            }
        }
        flushCurrent()
        if (segments.isEmpty()) return AssSsaSurfaceParseResult.PreserveOnly(text)
        suffix.append(pendingSeparator)

        return AssSsaSurfaceParseResult.Translatable(
            AssSsaTranslationSurface(
                id = id,
                originalText = text,
                prefixRaw = prefix.toString(),
                segments = segments,
                separators = separators,
                suffixRaw = suffix.toString(),
                inlineMarkers = inlineMarkers,
                context = segments.joinToString(" ").replace(Regex("""\s+"""), " ").trim()
            )
        )
    }

    private fun previousSurfaceChar(
        tokens: List<AssSsaTextToken>,
        tokenIndex: Int,
        currentText: String
    ): Char? {
        currentText.lastOrNull()?.let { return it }
        for (index in tokenIndex - 1 downTo 0) {
            val text = (tokens[index] as? AssSsaTextToken.Text)?.raw ?: continue
            text.lastOrNull()?.let { return it }
        }
        return null
    }

    private fun nextSurfaceChar(tokens: List<AssSsaTextToken>, tokenIndex: Int): Char? {
        for (index in tokenIndex + 1 until tokens.size) {
            val text = (tokens[index] as? AssSsaTextToken.Text)?.raw ?: continue
            text.firstOrNull()?.let { return it }
        }
        return null
    }
}

private val RAW_ASS_SURFACE_SYNTAX_PATTERN =
    Regex("""\{[^}\n]*}|\\[Nnh]""")

private fun Char.isAssSsaWordChar(): Boolean {
    return isLetterOrDigit() || Character.getType(this).let { type ->
        type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }
}
```

- [ ] **Step 4: Run parser tests**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.data.repository.AssSsaSegmentSurfaceTranslationTest'
```

Expected: all tests in `AssSsaSegmentSurfaceTranslationTest` pass.

- [ ] **Step 5: Commit parser model**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/AssSsaSegmentSurfaceTranslation.kt app/src/test/java/com/nexio/tv/data/repository/AssSsaSegmentSurfaceTranslationTest.kt
git commit -m "feat(subtitles): add ASS SSA segment surface parser"
```

## Task 2: TimedTextDocument Segment-Surface Integration

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTimedTextDocument.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TimedTextDocument.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/AssSsaTimedTextDocumentTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/AssSsaLocalFixtureRegressionTest.kt`

- [ ] **Step 1: Write failing document-level tests**

Add these tests to `AssSsaTimedTextDocumentTest`:

```kotlin
@Test
fun segmentSurfaceRenderTranslatesDialogueAndCommentEvents() {
    val document = TimedTextDocument.parse(
        raw = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello {\i1}world{\i0}
            Comment: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,Sign {\b1}text{\b0}
        """.trimIndent(),
        url = "file:///tmp/subtitle.ass"
    )!!

    val surfaces = document.assSsaSegmentSurfaces()

    assertEquals(listOf("ass_0", "ass_1"), surfaces.map { it.id })
    assertEquals(listOf("Hello", "world"), surfaces[0].segments)
    assertEquals(listOf("Sign", "text"), surfaces[1].segments)
    assertEquals(
        """
        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hallo {\i1}wereld{\i0}
        Comment: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,Bord {\b1}tekst{\b0}
        """.trimIndent() + "\n",
        document.renderAssSsaSegmentSurfaces(
            mapOf(
                "ass_0" to listOf("Hallo", "wereld"),
                "ass_1" to listOf("Bord", "tekst")
            )
        )
    )
}

@Test
fun segmentSurfaceRenderPreservesOneFailedEventOnly() {
    val document = TimedTextDocument.parse(
        raw = """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:02.00,Hello
            Dialogue: 0:00:03.00,0:00:04.00,World
        """.trimIndent(),
        url = "file:///tmp/subtitle.ass"
    )!!

    assertEquals(
        """
        [Events]
        Format: Start, End, Text
        Dialogue: 0:00:01.00,0:00:02.00,Hallo
        Dialogue: 0:00:03.00,0:00:04.00,World
        """.trimIndent() + "\n",
        document.renderAssSsaSegmentSurfaces(mapOf("ass_0" to listOf("Hallo")))
    )
}
```

Update the protected-mode expectation in `AssSsaLocalFixtureRegressionTest.ganbareMovingSignTranslatesVisibleTextOnly` to assert segment surfaces:

```kotlin
@Test
fun ganbareMovingSignBuildsSegmentSurface() {
    val text = "{\\an8\\fnComic Sans MS\\b1\\fs45\\bord2.5\\shad3\\move(165,182,165,-182)\\3c&H181060&\\4c&H181060&\\c&H3093F2&}Lov{\\c&H55C8F8&}able {\\c&H3093F2&}Lun{\\c&H55C8F8&}ches!"
    val surface = (AssSsaSegmentSurfaceParser.parse("evt_0", text) as AssSsaSurfaceParseResult.Translatable).surface

    assertEquals(
        "{\\an8\\fnComic Sans MS\\b1\\fs45\\bord2.5\\shad3\\move(165,182,165,-182)\\3c&H181060&\\4c&H181060&\\c&H3093F2&}",
        surface.prefixRaw
    )
    assertEquals(listOf("Lov<1/>able", "Lun<2/>ches!"), surface.segments)
    assertEquals(listOf(" {\\c&H3093F2&}"), surface.separators)
    assertEquals(
        "{\\an8\\fnComic Sans MS\\b1\\fs45\\bord2.5\\shad3\\move(165,182,165,-182)\\3c&H181060&\\4c&H181060&\\c&H3093F2&}Lie{\\c&H55C8F8&}felijke {\\c&H3093F2&}lun{\\c&H55C8F8&}ches!",
        surface.recomposeOrThrow(listOf("Lie<1/>felijke", "lun<2/>ches!"))
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.data.repository.AssSsaTimedTextDocumentTest' --tests 'com.nexio.tv.data.repository.AssSsaLocalFixtureRegressionTest'
```

Expected: compilation fails because `assSsaSegmentSurfaces()` and `renderAssSsaSegmentSurfaces()` do not exist.

- [ ] **Step 3: Expose raw text and segment rendering from ASS/SSA blocks**

In `AssSsaTimedTextDocument.kt`, change the event-line check in `parseAssSsaTimedTextDocument` so both dialogue and comment event records can enter the same parser:

```kotlin
if (inEventsSection &&
    textFieldIndex >= 0 &&
    (trimmed.startsWith("Dialogue:", ignoreCase = true) ||
        trimmed.startsWith("Comment:", ignoreCase = true))
) {
    val parsedDialogue = parseAssSsaDialogueLine(
        line = line,
        eventFieldCount = eventFormat.size,
        textFieldIndex = textFieldIndex,
        nextBlockId = nextBlockId
    )
    if (parsedDialogue != null) {
        parsed += parsedDialogue.block
        nextBlockId = parsedDialogue.nextBlockId
        continue
    }
}
```

In `AssSsaTimedTextDocument.kt`, update `AssSsaDialogueBlock` with these members:

```kotlin
internal data class AssSsaDialogueBlock(
    private val prefix: String,
    private val fieldsBeforeText: List<String>,
    private val textSegments: List<AssSsaTextSegment>
) : TimedTextBlock() {
    fun translatableBlocks(): List<TranslatableTimedTextBlock> {
        return textSegments.mapNotNull { segment ->
            when (segment) {
                is TranslatableAssSsaTextSegment -> TranslatableTimedTextBlock(
                    blockId = segment.blockId,
                    prefixLines = emptyList(),
                    text = segment.fallback
                )
                else -> null
            }
        }
    }

    override fun render(translations: Map<Int, String>): String {
        return renderWithText(textSegments.joinToString("") { segment -> segment.render(translations) })
    }

    fun rawText(): String {
        return textSegments.joinToString("") { segment -> segment.render(emptyMap()) }
    }

    fun segmentSurface(id: String): AssSsaTranslationSurface? {
        return when (val result = AssSsaSegmentSurfaceParser.parse(id, rawText())) {
            is AssSsaSurfaceParseResult.Translatable -> result.surface
            is AssSsaSurfaceParseResult.PreserveOnly -> null
        }
    }

    fun renderSegmentSurface(surface: AssSsaTranslationSurface?, translatedSegments: List<String>?): String {
        val nextText = if (surface != null && translatedSegments != null) {
            runCatching { surface.recomposeOrThrow(translatedSegments) }.getOrDefault(rawText())
        } else {
            rawText()
        }
        return renderWithText(nextText)
    }

    private fun renderWithText(text: String): String {
        return if (fieldsBeforeText.isEmpty()) {
            prefix + text
        } else {
            prefix + fieldsBeforeText.joinToString(",") + "," + text
        }
    }
}
```

- [ ] **Step 4: Add document-level surface APIs**

In `TimedTextDocument.kt`, replace the ASS protected helpers with:

```kotlin
fun assSsaSegmentSurfaces(): List<AssSsaTranslationSurface> {
    if (format != TimedTextFormat.ASS && format != TimedTextFormat.SSA) return emptyList()
    var dialogueIndex = 0
    return blocks
        .filterIsInstance<AssSsaDialogueBlock>()
        .mapNotNull { block ->
            block.segmentSurface("ass_${dialogueIndex++}")
        }
}

fun renderAssSsaSegmentSurfaces(translations: Map<String, List<String>>): String {
    if (format != TimedTextFormat.ASS && format != TimedTextFormat.SSA) {
        return render(emptyMap())
    }
    var dialogueIndex = 0
    return blocks.joinToString("\n") { block ->
        if (block is AssSsaDialogueBlock) {
            val id = "ass_${dialogueIndex++}"
            val surface = block.segmentSurface(id)
            block.renderSegmentSurface(surface, translations[id])
        } else {
            block.render(emptyMap())
        }
    }.trim() + "\n"
}
```

- [ ] **Step 5: Run document tests**

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.data.repository.AssSsaTimedTextDocumentTest' --tests 'com.nexio.tv.data.repository.AssSsaLocalFixtureRegressionTest'
```

Expected: document integration tests pass, while unrelated service tests still reference old protected APIs.

- [ ] **Step 6: Commit document integration**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/AssSsaTimedTextDocument.kt app/src/main/java/com/nexio/tv/data/repository/TimedTextDocument.kt app/src/test/java/com/nexio/tv/data/repository/AssSsaTimedTextDocumentTest.kt app/src/test/java/com/nexio/tv/data/repository/AssSsaLocalFixtureRegressionTest.kt
git commit -m "feat(subtitles): render ASS SSA segment surfaces"
```

## Task 3: Structured ASS/SSA Provider Contract

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/AssSsaSegmentSurfaceTranslation.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServiceProviderTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServicePromptTest.kt`

- [ ] **Step 1: Write failing provider-contract tests**

Add to `SubtitleTranslationServiceProviderTest`:

```kotlin
@Test
fun assSsaSegmentTranslationSendsSegmentArraysAndRecomposesValidItems() = runTest {
    val server = MockWebServer()
    server.enqueue(
        MockResponse()
            .setResponseCode(200)
            .setBody(
                """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\"items\":[{\"id\":\"ass_0\",\"segments\":[\"Hallo\",\"wereld\"]}]}"
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
    )
    server.start()
    try {
        val service = SubtitleTranslationService(
            context = mockk<Context>(relaxed = true),
            subtitleTranslationIntegrationProvider = subtitleTranslationIntegrationProvider(OkHttpClient()),
            subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider(OkHttpClient())
        )
        val surface = (AssSsaSegmentSurfaceParser.parse("ass_0", "Hello {\\i1}world{\\i0}") as AssSsaSurfaceParseResult.Translatable).surface

        val result = service.translateAssSsaSegmentSurfaces(
            surfaces = listOf(surface),
            targetLanguageCode = "nl",
            sourceLanguageCode = "en",
            settings = SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.OPENAI,
                apiKey = "test-key",
                model = "gpt-5-nano",
                baseUrl = server.url("/v1").toString()
            )
        ).getOrThrow()

        assertEquals(mapOf("ass_0" to listOf("Hallo", "wereld")), result)
        val requestBody = server.takeRequest().body.readUtf8()
        assertTrue(requestBody.contains(""""segments":["Hello","world"]"""))
        assertTrue(requestBody.contains(""""context":"Hello world""""))
        assertFalse(requestBody.contains("⟦ASS_"))
        assertFalse(requestBody.contains("{\\i1}"))
    } finally {
        server.shutdown()
    }
}

@Test
fun assSsaSegmentTranslationDropsInvalidItemButKeepsValidItem() = runTest {
    val server = MockWebServer()
    server.enqueue(
        MockResponse()
            .setResponseCode(200)
            .setBody(
                """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\"items\":[{\"id\":\"ass_0\",\"segments\":[\"Hallo\"]},{\"id\":\"ass_1\",\"segments\":[\"{\\\\i1}Wereld\"]}]}"
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
    )
    server.start()
    try {
        val service = SubtitleTranslationService(
            context = mockk<Context>(relaxed = true),
            subtitleTranslationIntegrationProvider = subtitleTranslationIntegrationProvider(OkHttpClient()),
            subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider(OkHttpClient())
        )
        val surfaces = listOf(
            (AssSsaSegmentSurfaceParser.parse("ass_0", "Hello") as AssSsaSurfaceParseResult.Translatable).surface,
            (AssSsaSegmentSurfaceParser.parse("ass_1", "World") as AssSsaSurfaceParseResult.Translatable).surface
        )

        val result = service.translateAssSsaSegmentSurfaces(
            surfaces = surfaces,
            targetLanguageCode = "nl",
            sourceLanguageCode = "en",
            settings = SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.OPENAI,
                apiKey = "test-key",
                model = "gpt-5-nano",
                baseUrl = server.url("/v1").toString()
            )
        ).getOrThrow()

        assertEquals(mapOf("ass_0" to listOf("Hallo")), result)
    } finally {
        server.shutdown()
    }
}
```

In `SubtitleTranslationServicePromptTest`, remove both `buildRawAssSsaSystemPrompt...` tests and add:

```kotlin
@Test
fun `buildAssSsaSegmentSystemPrompt keeps model contract short`() {
    val prompt = SubtitleTranslationService.buildAssSsaSegmentSystemPromptForTest()

    assertTrue(prompt.contains("Keep exactly the same number of segments"))
    assertTrue(prompt.contains("Preserve placeholders like <1/>"))
    assertTrue(prompt.contains("Do not output ASS/SSA syntax"))
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest' --tests 'com.nexio.tv.data.repository.SubtitleTranslationServicePromptTest'
```

Expected: compilation fails because `translateAssSsaSegmentSurfaces()` and `buildAssSsaSegmentSystemPromptForTest()` do not exist.

- [ ] **Step 3: Add response parser helpers**

Append these helpers to `AssSsaSegmentSurfaceTranslation.kt`:

```kotlin
internal fun parseAssSsaSegmentResponse(
    responseText: String,
    surfaces: List<AssSsaTranslationSurface>
): Map<String, List<String>> {
    val normalized = sanitizeJsonLikeResponse(responseText)
    val root = JSONObject(normalized)
    val items = root.optJSONArray("items") ?: JSONArray()
    val byId = surfaces.associateBy { it.id }
    val parsed = mutableMapOf<String, List<String>>()
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        val id = item.optString("id")
        val surface = byId[id] ?: continue
        val segmentsJson = item.optJSONArray("segments") ?: continue
        val segments = buildList {
            for (segmentIndex in 0 until segmentsJson.length()) {
                add(segmentsJson.optString(segmentIndex))
            }
        }
        val checked = surface.validateTranslatedSegments(segments).getOrNull() ?: continue
        parsed[id] = checked
    }
    return parsed
}

private fun sanitizeJsonLikeResponse(responseText: String): String {
    val trimmed = responseText.trim()
    val unfenced = if (trimmed.startsWith("```")) {
        trimmed
            .replaceFirst(Regex("""^```[A-Za-z0-9_-]*\s*"""), "")
            .replace(Regex("""\s*```\s*$"""), "")
            .trim()
    } else {
        trimmed
    }
    val start = unfenced.indexOf('{')
    val end = unfenced.lastIndexOf('}')
    return if (start >= 0 && end >= start) unfenced.substring(start, end + 1) else unfenced
}
```

- [ ] **Step 4: Add service prompt and translation method**

In `SubtitleTranslationService` companion object, add:

```kotlin
@androidx.annotation.VisibleForTesting
internal fun buildAssSsaSegmentSystemPromptForTest(): String {
    return """
        Translate subtitle segments to the target language.
        Return valid JSON only.
        Keep the same item ids.
        Keep exactly the same number of segments for each item.
        Do not merge, split, reorder, or omit segments.
        Preserve placeholders like <1/>, <2/>, <3/> exactly.
        Place placeholders inside the equivalent translated word when possible.
        Do not output ASS/SSA syntax such as {...}, \N, \n, or \h.
        Keep subtitle phrasing concise and natural.
    """.trimIndent()
}
```

Add this method near `translateProtectedAssSsaUnits`:

```kotlin
internal suspend fun translateAssSsaSegmentSurfaces(
    surfaces: List<AssSsaTranslationSurface>,
    targetLanguageCode: String,
    sourceLanguageCode: String?,
    settings: SubtitleTranslationSettings
): Result<Map<String, List<String>>> = withContext(Dispatchers.IO) {
    runCatching {
        val normalizedTarget = targetLanguageCode.trim().ifBlank {
            throw IllegalArgumentException("Target language is required.")
        }
        val normalizedSettings = settings.copy(apiKey = settings.apiKey.trim())
        if (normalizedSettings.apiKey.isBlank()) {
            throw IllegalArgumentException("Subtitle translation API key is missing.")
        }
        if (surfaces.isEmpty()) return@runCatching emptyMap()

        val payload = JSONObject()
            .put("sourceLanguage", displaySourceLanguage(sourceLanguageCode))
            .put("targetLanguage", displayLanguage(normalizedTarget))
            .put("items", JSONArray().apply { surfaces.forEach { put(it.toJson()) } })

        val response = executeTranslationRequest(
            promptPayload = payload,
            targetLanguageCode = normalizedTarget,
            targetLanguageName = displayLanguage(normalizedTarget),
            sourceLanguageName = displaySourceLanguage(sourceLanguageCode),
            markerPayload = null,
            settings = normalizedSettings,
            includeSchema = true,
            systemPromptOverride = buildAssSsaSegmentSystemPromptForTest()
        ) ?: throw IllegalStateException("Subtitle translation provider did not return an ASS/SSA segment payload.")

        parseAssSsaSegmentResponse(response, surfaces).also { parsed ->
            diagnosticsLogger.log(
                "ass_segment_parse_success requestedItems=${surfaces.size} parsedItems=${parsed.size} " +
                    "droppedItems=${(surfaces.size - parsed.size).coerceAtLeast(0)}"
            )
        }
    }
}
```

- [ ] **Step 5: Run provider-contract tests**

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest' --tests 'com.nexio.tv.data.repository.SubtitleTranslationServicePromptTest'
```

Expected: provider-contract tests pass.

- [ ] **Step 6: Commit provider contract**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/AssSsaSegmentSurfaceTranslation.kt app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServiceProviderTest.kt app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServicePromptTest.kt
git commit -m "feat(subtitles): translate ASS SSA segment surfaces"
```

## Task 4: Route File And Embedded ASS/SSA Translation Through Segment Surfaces

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt`

- [ ] **Step 1: Rewrite sample-sink tests for the single path**

In `AssSsaTranslatingSampleSinkTest`, update constructor calls to remove `useSystemPromptTranslation` and `translateRawAssSsa`, and replace raw-mode tests with:

```kotlin
@Test
fun translatesDialogueSampleThroughSegmentSurfaces() = runTest {
    val downstream = RecordingAssSsaSampleSink()
    val sink = AssSsaTranslatingSampleSink(
        downstream = downstream,
        scope = CoroutineScope(Dispatchers.Unconfined),
        isEnabled = { true },
        translate = { surfaces ->
            assertEquals(listOf(listOf("I am", "not", "angry")), surfaces.map { it.segments })
            mapOf("evt_0" to listOf("Ik ben", "niet", "boos"))
        }
    )

    sink.onSubtitleSample(
        trackId = 4,
        timeUs = 1_000_000L,
        data = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,I am {\\i1}not{\\i0} angry".toByteArray()
    )

    assertEquals(
        "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Ik ben {\\i1}niet{\\i0} boos",
        downstream.samples.single().decodeToString()
    )
}

@Test
fun translatesCommentSampleWhenItMatchesEventFormat() = runTest {
    val downstream = RecordingAssSsaSampleSink()
    val sink = AssSsaTranslatingSampleSink(
        downstream = downstream,
        scope = CoroutineScope(Dispatchers.Unconfined),
        isEnabled = { true },
        translate = { surfaces ->
            assertEquals(listOf("evt_0"), surfaces.map { it.id })
            mapOf("evt_0" to listOf("Bordtekst"))
        }
    )
    val sample = "Comment: 0,0:00:01.00,0:00:03.00,Default,SIGN,0,0,0,,Sign text"

    sink.onSubtitleSample(trackId = 4, timeUs = 1_000_000L, data = sample.toByteArray())

    assertEquals(
        "Comment: 0,0:00:01.00,0:00:03.00,Default,SIGN,0,0,0,,Bordtekst",
        downstream.samples.single().decodeToString()
    )
}
```

Delete these tests because the raw ASS path no longer exists:

```text
systemPromptModeTranslatesSignLikeSamplesThroughRawProvider
systemPromptModeSendsUnclassifiedAssSampleThroughRawProvider
systemPromptModeBatchesRawAssSampleBeforeDelegating
systemPromptModeFallsBackToOriginalSampleWhenRawProviderThrows
```

- [ ] **Step 2: Run sample-sink tests to verify they fail**

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest'
```

Expected: compilation fails because `AssSsaTranslatingSampleSink` still expects protected units and raw-mode lambdas.

- [ ] **Step 3: Route downloaded ASS/SSA files through segment surfaces**

In `SubtitleTranslationService.translateSubtitle`, replace the ASS/SSA branch with:

```kotlin
} else if (document.format == TimedTextFormat.ASS ||
    document.format == TimedTextFormat.SSA
) {
    val surfaces = document.assSsaSegmentSurfaces()
    val batches = AssSsaSegmentSurfaceBatchPlanner.plan(surfaces)
    val translatedSegments = mutableMapOf<String, List<String>>()
    batches.forEach { batch ->
        val response = translateAssSsaSegmentSurfaces(
            surfaces = batch.units,
            targetLanguageCode = normalizedTarget,
            sourceLanguageCode = sourceLanguageCode,
            settings = normalizedSettings
        ).getOrThrow()
        batch.coreUnits.forEach { surface ->
            response[surface.id]?.let { translatedSegments[surface.id] = it }
        }
    }
    diagnosticsLogger.log(
        "ass_segment_translate_merge batches=${batches.size} surfaces=${surfaces.size} translated=${translatedSegments.size}"
    )
    document.renderAssSsaSegmentSurfaces(translatedSegments)
} else {
```

Add the batch planner to `AssSsaSegmentSurfaceTranslation.kt`:

```kotlin
internal data class AssSsaSegmentSurfaceBatch(
    val units: List<AssSsaTranslationSurface>,
    val leadOverlap: Int,
    val coreCount: Int,
    val trailOverlap: Int
) {
    val coreUnits: List<AssSsaTranslationSurface>
        get() = units.subList(leadOverlap, leadOverlap + coreCount)
}

internal object AssSsaSegmentSurfaceBatchPlanner {
    fun plan(
        surfaces: List<AssSsaTranslationSurface>,
        config: AssSsaTranslationBatchConfig = AssSsaTranslationBatchConfig()
    ): List<AssSsaSegmentSurfaceBatch> {
        if (surfaces.isEmpty()) return emptyList()
        return planRampedTranslationBatches(
            items = surfaces,
            maxCoreEntries = config.maxEvents,
            maxCoreChars = config.maxVisibleChars,
            sizeOf = { surface ->
                surface.context.length + surface.segments.sumOf { it.length }
            },
            rampUpEnabled = config.rampUpEnabled
        ).map { ramped ->
            AssSsaSegmentSurfaceBatch(
                units = ramped.items,
                leadOverlap = ramped.leadOverlap,
                coreCount = ramped.coreCount,
                trailOverlap = ramped.trailOverlap
            )
        }
    }
}
```

- [ ] **Step 4: Simplify embedded sample sink**

Change `AssSsaTranslatingSampleSink` constructor to:

```kotlin
internal class AssSsaTranslatingSampleSink(
    private val downstream: AssSsaSampleSink,
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean,
    private val translate: suspend (List<AssSsaTranslationSurface>) -> Map<String, List<String>>,
    private val diagnosticsLogger: AutoTranslateDiagnosticsLogger =
        AutoTranslateDiagnosticsLogger.disabled()
) : AssSsaSampleSink {
```

Replace `onSubtitleSample` translation logic after the disabled check with:

```kotlin
val format = trackFormats[trackId] ?: AssSsaEventFormat.standardDialogue()
val records = text.lineSequence()
    .mapNotNull { line -> AssSsaEventRecord.parseDialogueLine(line, format) }
    .toList()
if (records.isEmpty()) {
    diagnosticsLogger.log(
        "sample_emit_original reason=no_event_records track=$trackId timeUs=$timeUs bytes=${data.size}"
    )
    downstream.onSubtitleSample(trackId, timeUs, data)
    return
}

val surfacesByIndex = records.mapIndexedNotNull { index, record ->
    val id = "evt_$index"
    when (val result = AssSsaSegmentSurfaceParser.parse(id, record.text)) {
        is AssSsaSurfaceParseResult.Translatable -> index to result.surface
        is AssSsaSurfaceParseResult.PreserveOnly -> null
    }
}
scope.launch {
    val surfaces = surfacesByIndex.map { it.second }
    diagnosticsLogger.log(
        "sample_translate_start mode=ass_segment track=$trackId timeUs=$timeUs records=${records.size} " +
            "surfaces=${surfaces.size} bytes=${data.size} hash=${AutoTranslateDiagnosticsLogger.sha256Short(text)}"
    )
    if (surfaces.isEmpty()) {
        diagnosticsLogger.log(
            "sample_emit_original reason=no_translatable_surfaces mode=ass_segment track=$trackId timeUs=$timeUs"
        )
        downstream.onSubtitleSample(trackId, timeUs, data)
        return@launch
    }
    val translated = runCatching {
        translate(surfaces)
    }.onFailure { error ->
        diagnosticsLogger.log(
            "sample_translate_failed mode=ass_segment track=$trackId timeUs=$timeUs " +
                "error=${error::class.simpleName}:${error.message}"
        )
    }.getOrDefault(emptyMap())
    val surfaceByIndex = surfacesByIndex.toMap()
    val translatedLines = records.mapIndexed { index, record ->
        val surface = surfaceByIndex[index]
        val translatedText = surface
            ?.let { translated[it.id]?.let { segments -> runCatching { it.recomposeOrThrow(segments) }.getOrNull() } }
            ?: record.text
        record.withText(translatedText).render()
    }
    val output = translatedLines.joinToString("\n")
    downstream.onSubtitleSample(trackId = trackId, timeUs = timeUs, data = output.toByteArray())
    diagnosticsLogger.log(
        "sample_emit_translated mode=ass_segment track=$trackId timeUs=$timeUs " +
            "translatedItems=${translated.size} outputBytes=${output.toByteArray().size} " +
            "outputHash=${AutoTranslateDiagnosticsLogger.sha256Short(output)}"
    )
    diagnosticsLogger.logUnsafe("sample_ass_segment_output track=$trackId timeUs=$timeUs", output)
}
```

- [ ] **Step 5: Update player construction**

In `PlayerRuntimeControllerInitialization.kt`, replace the `AssSsaTranslatingSampleSink` construction with:

```kotlin
val assSampleSink = assController?.let { controller ->
    AssSsaTranslatingSampleSink(
        downstream = controller,
        scope = scope,
        isEnabled = {
            shouldEnableAssSsaSampleTranslation(
                aiSubtitlesEnabled = _uiState.value.aiSubtitlesEnabled,
                selectedAddonSubtitlePresent = _uiState.value.selectedAddonSubtitle != null,
                selectedSubtitleTrackIndex = _uiState.value.selectedSubtitleTrackIndex,
                translationSettingsEnabled = subtitleTranslationSettings.enabled,
                translationApiKeyPresent = subtitleTranslationSettings.apiKey.isNotBlank()
            )
        },
        translate = { surfaces ->
            val translated = mutableMapOf<String, List<String>>()
            AssSsaSegmentSurfaceBatchPlanner.plan(surfaces).forEach { batch ->
                val response = subtitleTranslationService.translateAssSsaSegmentSurfaces(
                    surfaces = batch.units,
                    targetLanguageCode = _uiState.value.subtitleStyle.preferredLanguage,
                    sourceLanguageCode = null,
                    settings = subtitleTranslationSettings
                ).getOrThrow()
                batch.coreUnits.forEach { surface ->
                    response[surface.id]?.let { translated[surface.id] = it }
                }
            }
            translated
        },
        diagnosticsLogger = subtitleTranslationService.diagnosticsLogger
    )
}
```

- [ ] **Step 6: Run routing tests**

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest' --tests 'com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest'
```

Expected: routing and provider tests pass.

- [ ] **Step 7: Commit routing changes**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/AssSsaSegmentSurfaceTranslation.kt app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt
git commit -m "feat(subtitles): route ASS SSA through segment translation"
```

## Task 5: Remove ASS/SSA Raw System-Prompt Setting

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/GeminiSettings.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/SubtitleTranslationSettingsDataStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/SubtitleTranslationSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/SubtitleTranslationSettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/java/com/nexio/tv/data/local/SubtitleTranslationSettingsDataStoreTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/settings/SubtitleTranslationSettingsViewModelTest.kt`

- [ ] **Step 1: Update setting tests to expect only SRT raw prompt state**

In `SubtitleTranslationSettingsDataStoreTest`, replace `openRouterEndpointKeepsCustomModelAndBaseUrl` with:

```kotlin
@Test
fun openRouterEndpointKeepsCustomModelBaseUrlAndSubRipPromptFlag() {
    val settings = normalizeSubtitleTranslationSettings(
        enabled = true,
        providerName = "OPENAI",
        apiKey = "openrouter-key",
        model = "openai/gpt-5.2",
        baseUrl = "https://openrouter.ai/api/v1/",
        subRipSystemPromptEnabled = true
    )

    assertEquals(SubtitleTranslationProvider.OPENAI, settings.provider)
    assertEquals("openai/gpt-5.2", settings.model)
    assertEquals("https://openrouter.ai/api/v1", settings.baseUrl)
    assertEquals(true, settings.subRipSystemPromptEnabled)
}
```

Replace `missingSystemPromptPreferencesDefaultDisabled` with:

```kotlin
@Test
fun missingSubRipSystemPromptPreferenceDefaultsDisabled() {
    val settings = normalizeSubtitleTranslationSettings(
        enabled = true,
        providerName = "OPENAI",
        apiKey = "openrouter-key",
        model = "openai/gpt-5.2",
        baseUrl = "https://openrouter.ai/api/v1"
    )

    assertEquals(false, settings.subRipSystemPromptEnabled)
}
```

In `switchingProviderResetsStoredModelAndBaseUrlToProviderDefaults`, remove `assSsaSystemPromptKey` from `mutablePreferencesOf` and remove the final assertion for that key.

In `SubtitleTranslationSettingsViewModelTest`, remove assertions or setup references to `assSsaSystemPromptEnabled`; keep `subRipSystemPromptEnabled` tests unchanged.

- [ ] **Step 2: Run setting tests to verify they fail**

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.data.local.SubtitleTranslationSettingsDataStoreTest' --tests 'com.nexio.tv.ui.screens.settings.SubtitleTranslationSettingsViewModelTest'
```

Expected: compilation fails until production setting fields and methods are removed.

- [ ] **Step 3: Remove the domain setting field**

In `GeminiSettings.kt`, change `SubtitleTranslationSettings` to:

```kotlin
data class SubtitleTranslationSettings(
    val enabled: Boolean = false,
    val provider: SubtitleTranslationProvider = SubtitleTranslationProvider.OPENAI,
    val apiKey: String = "",
    val model: String = SubtitleTranslationDefaults.OPENAI_MODEL,
    val baseUrl: String = SubtitleTranslationDefaults.OPENAI_BASE_URL,
    val subRipSystemPromptEnabled: Boolean = false
)
```

- [ ] **Step 4: Remove DataStore ASS setting read/write**

In `SubtitleTranslationSettingsDataStore.kt`, change `normalizeSubtitleTranslationSettings` signature and copy:

```kotlin
internal fun normalizeSubtitleTranslationSettings(
    enabled: Boolean,
    providerName: String?,
    apiKey: String?,
    model: String?,
    baseUrl: String?,
    subRipSystemPromptEnabled: Boolean = false
): SubtitleTranslationSettings {
    val trimmedApiKey = apiKey?.trim().orEmpty()
    val trimmedProvider = providerName?.trim().orEmpty()
    val provider = if (trimmedProvider.isBlank()) {
        if (trimmedApiKey.isNotBlank()) SubtitleTranslationProvider.GEMINI else SubtitleTranslationProvider.OPENAI
    } else {
        runCatching { SubtitleTranslationProvider.valueOf(trimmedProvider.uppercase()) }
            .getOrDefault(SubtitleTranslationProvider.OPENAI)
    }
    val defaults = defaultSubtitleTranslationSettings(provider)
    return defaults.copy(
        enabled = enabled,
        apiKey = trimmedApiKey,
        model = model?.trim()?.takeIf(String::isNotBlank) ?: defaults.model,
        baseUrl = baseUrl?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank) ?: defaults.baseUrl,
        subRipSystemPromptEnabled = subRipSystemPromptEnabled
    )
}
```

Remove:

```kotlin
private val assSsaSystemPromptEnabledKey = booleanPreferencesKey("subtitle_translation_ass_ssa_system_prompt_enabled")
```

In the `settings` flow call, remove `assSsaSystemPromptEnabled = prefs[assSsaSystemPromptEnabledKey] ?: false`.

Delete:

```kotlin
suspend fun setAssSsaSystemPromptEnabled(enabled: Boolean) {
    store().edit { prefs ->
        prefs[assSsaSystemPromptEnabledKey] = enabled
    }
}
```

- [ ] **Step 5: Remove ViewModel and UI row**

In `SubtitleTranslationSettingsViewModel.kt`, delete:

```kotlin
fun setAssSsaSystemPromptEnabled(enabled: Boolean) {
    viewModelScope.launch {
        dataStore.setAssSsaSystemPromptEnabled(enabled)
    }
}
```

Remove `assSsaSystemPromptEnabled` from `SubtitleTranslationSettingsUiState` and from `fromSettings`.

In `SubtitleTranslationSettingsScreen.kt`, delete the `item(key = "subtitle_translation_ass_ssa_system_prompt")` block.

In `strings.xml`, delete:

```xml
<string name="subtitle_translation_ass_ssa_system_prompt_title">ASS/SSA system prompt mode</string>
<string name="subtitle_translation_ass_ssa_system_prompt_subtitle">Use the provider\'s raw ASS/SSA prompt path for embedded styled subtitles; falls back to the existing protected placeholder path when disabled</string>
```

- [ ] **Step 6: Run setting tests**

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.data.local.SubtitleTranslationSettingsDataStoreTest' --tests 'com.nexio.tv.ui.screens.settings.SubtitleTranslationSettingsViewModelTest'
```

Expected: setting tests pass.

- [ ] **Step 7: Commit setting removal**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/GeminiSettings.kt app/src/main/java/com/nexio/tv/data/local/SubtitleTranslationSettingsDataStore.kt app/src/main/java/com/nexio/tv/ui/screens/settings/SubtitleTranslationSettingsViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/settings/SubtitleTranslationSettingsScreen.kt app/src/main/res/values/strings.xml app/src/test/java/com/nexio/tv/data/local/SubtitleTranslationSettingsDataStoreTest.kt app/src/test/java/com/nexio/tv/ui/screens/settings/SubtitleTranslationSettingsViewModelTest.kt
git commit -m "refactor(subtitles): remove ASS SSA raw prompt setting"
```

## Task 6: Remove Raw/Protected ASS Translation Code And Bump Cache Key

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/AssSsaProtectedTranslation.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAstTranslation.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTranslationBatchPlanner.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/AssSsaProtectedTranslationTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/AssSsaTextAstTranslationTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/AssSsaTranslationBatchPlannerTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServiceProviderTest.kt`

- [ ] **Step 1: Update cache-key test**

In `SubtitleTranslationServiceProviderTest.diskCacheKeyIncludesProviderModelAndEndpoint`, add:

```kotlin
assertNotEquals(
    subtitleTranslationDiskCacheKey("https://subs.example/movie.ass", "nl", baseline),
    subtitleTranslationDiskCacheKey("https://subs.example/movie.ass", "nl", baseline.copy(subRipSystemPromptEnabled = true))
)
```

Add a new test:

```kotlin
@Test
fun diskCacheKeyUsesSegmentSurfaceVersion() {
    val key = subtitleTranslationDiskCacheKey(
        sourceUrl = "https://subs.example/movie.ass",
        targetLanguage = "nl",
        settings = SubtitleTranslationSettings(
            provider = SubtitleTranslationProvider.OPENAI,
            model = "gpt-5-nano",
            baseUrl = "https://api.openai.com/v1"
        )
    )

    assertEquals(64, key.length)
    assertNotEquals(
        key,
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("file|https://subs.example/movie.ass|nl|OPENAI|gpt-5-nano|https://api.openai.com/v1|srtRaw=false|v3".toByteArray())
            .joinToString("") { "%02x".format(it) }
    )
}
```

- [ ] **Step 2: Run provider tests to verify they fail**

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest'
```

Expected: cache-version assertion fails until the key version changes.

- [ ] **Step 3: Bump cache key and remove ASS raw flags from key**

In `SubtitleTranslationService.kt`, change `subtitleTranslationDiskCacheKey` to:

```kotlin
internal fun subtitleTranslationDiskCacheKey(
    sourceUrl: String,
    targetLanguage: String,
    settings: SubtitleTranslationSettings
): String {
    return sha256(
        "file|$sourceUrl|$targetLanguage|${settings.provider}|${settings.model}|${settings.baseUrl}|" +
            "srtRaw=${settings.subRipSystemPromptEnabled}|assSegment=v1|v4"
    )
}
```

- [ ] **Step 4: Remove raw ASS service methods and prompt helpers**

In `SubtitleTranslationService.kt`, delete:

```text
RAW_ASS_TRANSLATION_SYNTAX_PATTERN
buildRawAssSsaSystemPromptForTest
buildRawAssSsaSystemPrompt
translateRawAssSsaText
sanitizeRawAssSsaResponse
validateRawAssSsaTranslation
validateRawAssSsaTextSyntax
protectedRawSequence
rawAssSsaSourceClause
```

Also delete `translateProtectedAssSsaUnits`, `buildProtectedAssSsaSystemPrompt`, and `parseProtectedAssSsaResponse` after all callers are gone.

- [ ] **Step 5: Delete obsolete protected translation files and tests**

Remove these files if `rg "AssSsaProtectedTranslationUnit|AssSsaTextAstTranslationUnit|AssSsaTranslationBatchPlanner"` shows no production references:

```bash
git rm app/src/main/java/com/nexio/tv/data/repository/AssSsaProtectedTranslation.kt
git rm app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAstTranslation.kt
git rm app/src/main/java/com/nexio/tv/data/repository/AssSsaTranslationBatchPlanner.kt
git rm app/src/test/java/com/nexio/tv/data/repository/AssSsaProtectedTranslationTest.kt
git rm app/src/test/java/com/nexio/tv/data/repository/AssSsaTextAstTranslationTest.kt
git rm app/src/test/java/com/nexio/tv/data/repository/AssSsaTranslationBatchPlannerTest.kt
```

- [ ] **Step 6: Verify no old ASS translation symbols remain**

Run:

```bash
rg -n "AssSsaProtectedTranslationUnit|AssSsaTextAstTranslationUnit|translateRawAssSsaText|buildRawAssSsaSystemPrompt|assSsaSystemPromptEnabled|⟦ASS_|⟦LB_" app/src/main app/src/test
```

Expected: no matches.

- [ ] **Step 7: Run cleanup tests**

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest' --tests 'com.nexio.tv.data.repository.SubtitleTranslationServicePromptTest' --tests 'com.nexio.tv.data.repository.AssSsaSegmentSurfaceTranslationTest'
```

Expected: tests pass.

- [ ] **Step 8: Commit cleanup**

```bash
git add app/src/main/java/com/nexio/tv/data/repository app/src/test/java/com/nexio/tv/data/repository
git commit -m "refactor(subtitles): remove old ASS SSA translation paths"
```

## Task 7: Full Regression And Build Verification

**Files:**
- Modify only files required by failures found in this task.

- [ ] **Step 1: Run targeted subtitle test suite**

```bash
./gradlew testDebugUnitTest \
  --tests 'com.nexio.tv.data.repository.AssSsa*' \
  --tests 'com.nexio.tv.data.repository.SubtitleTranslation*' \
  --tests 'com.nexio.tv.ui.screens.player.ass.*' \
  --tests 'com.nexio.tv.ui.screens.settings.SubtitleTranslationSettingsViewModelTest' \
  --tests 'com.nexio.tv.data.local.SubtitleTranslationSettingsDataStoreTest'
```

Expected: all targeted tests pass.

- [ ] **Step 2: Run broad unit tests for touched module areas**

```bash
./gradlew testDebugUnitTest
```

Expected: unit test suite passes.

- [ ] **Step 3: Run compile check**

```bash
./gradlew assembleDebug
```

Expected: debug APK compiles.

- [ ] **Step 4: Search for removed setting and old placeholders**

```bash
rg -n "assSsaSystemPromptEnabled|subtitle_translation_ass_ssa_system_prompt|ASS/SSA system prompt|translateRawAssSsaText|⟦ASS_|⟦LB_" app/src/main app/src/test docs/superpowers/specs/2026-05-12-ass-ssa-segment-surface-translation-design.md
```

Expected: matches are limited to the design spec where historical behavior is described. There are no matches in `app/src/main` or `app/src/test`.

- [ ] **Step 5: Review git diff**

```bash
git diff --stat
git diff -- app/src/main/java/com/nexio/tv/data/repository app/src/main/java/com/nexio/tv/ui/screens/player app/src/main/java/com/nexio/tv/ui/screens/settings app/src/main/java/com/nexio/tv/domain/model app/src/main/res/values/strings.xml app/src/test
```

Expected: diff is limited to ASS/SSA translation, subtitle translation settings removal, and corresponding tests.

- [ ] **Step 6: Commit verification fixes if any were needed**

If Step 1, Step 2, Step 3, or Step 4 required code or test changes, commit them:

```bash
git add app/src/main app/src/test
git commit -m "test(subtitles): verify ASS SSA segment translation"
```

If no changes were required after Task 6, do not create an empty commit.

## Self-Review Notes

Spec coverage:

- Single segment-surface ASS/SSA path: Tasks 1, 2, 3, 4, and 6.
- Replacement of both raw and protected modes: Tasks 4, 5, and 6.
- Event lines beyond `Dialogue:`: Tasks 2 and 4.
- Segment-boundary parser with intraword `<1/>` markers: Task 1.
- Structured JSON provider contract: Task 3.
- Per-event fallback: Tasks 2, 3, and 4.
- Cache-key version bump: Task 6.
- Removal of ASS raw prompt setting/UI: Task 5.
- SRT raw prompt preservation: Tasks 3, 5, and 6 keep SRT-specific code and tests.

Type consistency:

- `AssSsaTranslationSurface` is introduced in Task 1 and used consistently in document, service, and sample-sink tasks.
- `Map<String, List<String>>` is the translation result type for segment surfaces across Tasks 2, 3, and 4.
- `AssSsaSegmentSurfaceBatchPlanner` replaces `AssSsaTranslationBatchPlanner` only for segment surfaces.
