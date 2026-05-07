# ASS/SSA Protected Translation via assrender Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reliable ASS/SSA subtitle translation pipeline that preserves ASS syntax losslessly, translates only human-language text, validates model output, reconstructs valid ASS/SSA events, and renders translated embedded subtitles through the existing assrender/libass path.

**Architecture:** Split translation into a pure ASS/SSA structure layer and a playback integration layer. The structure layer parses event fields, tokenizes only the `Text` field, builds placeholder-protected phrase-mode translation units, validates translated placeholders, and reconstructs ASS text from original raw tokens. The playback layer keeps Media3 cue translation disabled for ASS/SSA and routes embedded Matroska ASS samples through a translating `AssSsaSampleSink` wrapper before `AssSsaRenderController` feeds chunks to libass.

**Tech Stack:** Kotlin, Android, Media3 fork, OkHttp-backed `SubtitleTranslationService`, MockWebServer, JUnit4, MockK, existing `AssSsaRenderController` JNI/libass renderer.

---

## References

- Aegisub ASS override tags: https://aegisub.org/docs/latest/ass_tags/
- Matroska subtitle storage: https://www.matroska.org/technical/subtitles.html
- Existing file parser: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTimedTextDocument.kt`
- Existing translation service: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`
- Existing embedded sample hook: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTrackOutput.kt`
- Existing assrender controller: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderController.kt`
- Existing Media3 cue translator: `app/src/main/java/com/nexio/tv/ui/screens/player/BuiltInSubtitleCueTranslator.kt`

## Scope

This plan intentionally does not ask the model to preserve raw ASS. The app owns all ASS syntax. The model receives only placeholder-protected visible text and returns JSON.

In scope:
- sidecar `.ass` / `.ssa` document translation
- embedded Matroska `S_TEXT/ASS` and `S_TEXT/SSA` sample translation before assrender
- batching large groups of events per provider request
- deterministic validation and fallback
- unit tests for tokenizer, placeholder validation, batch planning, and assrender sink routing

Out of scope for this plan:
- visual screenshot comparison against real video
- automatic font-size or position mutation to fit longer translations
- changing video playback timing to wait for translation
- replacing libass rendering

## File Structure

Create:
- `app/src/main/java/com/nexio/tv/data/repository/AssSsaEventRecord.kt`
  - Parse ASS/SSA event format lines and `Dialogue:` records by format field names.
  - Parse Matroska sample payloads into event records using the Matroska ASS sample field shape.
- `app/src/main/java/com/nexio/tv/data/repository/AssSsaTextTokenizer.kt`
  - Tokenize only the ASS `Text` field.
  - Preserve override blocks, line breaks, hard spaces, drawing payloads, and malformed spans verbatim.
- `app/src/main/java/com/nexio/tv/data/repository/AssSsaProtectedTranslation.kt`
  - Classify event risk.
  - Build phrase-mode and run-mode translation units with immutable placeholders.
  - Validate provider output and reconstruct ASS text.
- `app/src/main/java/com/nexio/tv/data/repository/AssSsaTranslationBatchPlanner.kt`
  - Batch many events by count, visible characters, and time span.
- `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt`
  - Wrap `AssSsaSampleSink`, translate embedded ASS samples asynchronously, and emit translated or original samples to `AssSsaRenderController`.
- `app/src/test/java/com/nexio/tv/data/repository/AssSsaEventRecordTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/AssSsaTextTokenizerTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/AssSsaProtectedTranslationTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/AssSsaTranslationBatchPlannerTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt`

Modify:
- `app/src/main/java/com/nexio/tv/data/repository/AssSsaTimedTextDocument.kt`
  - Use the new event parser/tokenizer/reconstructor instead of run-by-run replacement.
- `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`
  - Add a protected ASS/SSA batch translation method using strict JSON payloads and validation.
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - Construct a translating sink wrapper for the ASS renderer path when AI translation is configured.
- `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderController.kt`
  - Keep rendering-only responsibilities; accept already-translated samples through the existing sink interface.
- `app/src/main/java/com/nexio/tv/ui/screens/player/BuiltInSubtitleCueTranslator.kt`
  - Keep the ASS/SSA opt-out guard so Media3 cue translation never handles ASS/SSA.

---

### Task 1: Lock The Media3 Cue Translator Guard

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/BuiltInSubtitleCueTranslator.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/BuiltInSubtitleCueTranslatorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/BuiltInSubtitleCueTranslatorTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.nexio.tv.data.repository.SubtitleTranslationService
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BuiltInSubtitleCueTranslatorTest {
    private fun translator(): BuiltInSubtitleCueTranslator {
        return BuiltInSubtitleCueTranslator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            translationService = mockk<SubtitleTranslationService>(relaxed = true),
            isEnabledProvider = { true },
            settingsProvider = {
                SubtitleTranslationSettings(
                    enabled = true,
                    apiKey = "test-key",
                    model = "test-model"
                )
            },
            targetLanguageProvider = { "nl" },
            onTranslatingChanged = {},
            onTranslationError = {}
        )
    }

    @Test
    fun configurationTokenIsDisabledForTranscodedAssSsaTracks() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_SSA)
            .build()

        assertNull(translator().getConfigurationToken(format))
    }

    @Test
    fun configurationTokenIsStillEnabledForNonAssTextTracks() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_VTT)
            .setLanguage("en")
            .build()

        assertNotNull(translator().getConfigurationToken(format))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.BuiltInSubtitleCueTranslatorTest
```

Expected: `configurationTokenIsDisabledForTranscodedAssSsaTracks` fails because the translator still returns a non-null token for `codecs=text/x-ssa`.

- [ ] **Step 3: Implement the guard**

In `app/src/main/java/com/nexio/tv/ui/screens/player/BuiltInSubtitleCueTranslator.kt`, add imports:

```kotlin
import androidx.media3.common.MimeTypes
import java.util.Locale
```

In `getConfigurationToken`, add the guard after the `isEnabledProvider` check:

```kotlin
if (format.isAssSsaCueTranslationUnsupported()) {
    return null
}
```

At the end of the file, add:

```kotlin
private fun Format.isAssSsaCueTranslationUnsupported(): Boolean {
    if (sampleMimeType == MimeTypes.TEXT_SSA || sampleMimeType == "text/x-ass") {
        return true
    }
    return codecs
        ?.split(',')
        ?.map { it.trim().lowercase(Locale.US) }
        ?.any { codec -> codec == MimeTypes.TEXT_SSA || codec == "text/x-ass" }
        ?: false
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.BuiltInSubtitleCueTranslatorTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/BuiltInSubtitleCueTranslator.kt app/src/test/java/com/nexio/tv/ui/screens/player/BuiltInSubtitleCueTranslatorTest.kt
git commit -m "fix: keep ass subtitles out of cue translation"
```

---

### Task 2: Add ASS/SSA Event Record Parsing

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/AssSsaEventRecord.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaEventRecordTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/nexio/tv/data/repository/AssSsaEventRecordTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssSsaEventRecordTest {
    @Test
    fun parsesDialogueWithFormatDefinedTextFieldAndCommasInText() {
        val format = AssSsaEventFormat.parse(
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
        )!!
        val record = AssSsaEventRecord.parseDialogueLine(
            line = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello, world, again",
            format = format
        )!!

        assertEquals("Dialogue", record.kind)
        assertEquals("0:00:01.00", record.field("Start"))
        assertEquals("0:00:03.00", record.field("End"))
        assertEquals("Hello, world, again", record.text)
        assertEquals(
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hallo, wereld, opnieuw",
            record.withText("Hallo, wereld, opnieuw").render()
        )
    }

    @Test
    fun returnsNullWhenTextFieldIsMissing() {
        val format = AssSsaEventFormat.parse("Format: Start, End, Style")!!

        assertNull(
            AssSsaEventRecord.parseDialogueLine(
                line = "Dialogue: 0:00:01.00,0:00:03.00,Default",
                format = format
            )
        )
    }

    @Test
    fun parsesMatroskaAssPayloadUsingContainerTiming() {
        val format = AssSsaEventFormat.matroskaAss()
        val record = AssSsaEventRecord.parseMatroskaSample(
            sampleText = "17,0,Default,,0,0,0,,Hello from mkv",
            format = format,
            timeUs = 1_000_000L,
            durationUs = 2_000_000L
        )!!

        assertEquals("Dialogue", record.kind)
        assertEquals("0:00:01.00", record.field("Start"))
        assertEquals("0:00:03.00", record.field("End"))
        assertEquals("Hello from mkv", record.text)
        assertEquals(
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hallo uit mkv",
            record.withText("Hallo uit mkv").render()
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaEventRecordTest
```

Expected: compilation fails because `AssSsaEventFormat` and `AssSsaEventRecord` do not exist.

- [ ] **Step 3: Create the implementation**

Create `app/src/main/java/com/nexio/tv/data/repository/AssSsaEventRecord.kt`:

```kotlin
package com.nexio.tv.data.repository

import java.util.Locale
import kotlin.math.floor

internal data class AssSsaEventFormat(
    val fields: List<String>,
    val textIndex: Int,
    val startIndex: Int,
    val endIndex: Int
) {
    companion object {
        fun parse(line: String): AssSsaEventFormat? {
            if (!line.trimStart().startsWith("Format:", ignoreCase = true)) return null
            val fields = line.substringAfter(':').split(',').map { it.trim() }
            val textIndex = fields.indexOfField("Text")
            if (textIndex < 0) return null
            return AssSsaEventFormat(
                fields = fields,
                textIndex = textIndex,
                startIndex = fields.indexOfField("Start"),
                endIndex = fields.indexOfField("End")
            )
        }

        fun matroskaAss(): AssSsaEventFormat {
            val fields = listOf(
                "ReadOrder",
                "Layer",
                "Style",
                "Name",
                "MarginL",
                "MarginR",
                "MarginV",
                "Effect",
                "Text"
            )
            return AssSsaEventFormat(
                fields = fields,
                textIndex = fields.indexOfField("Text"),
                startIndex = -1,
                endIndex = -1
            )
        }
    }
}

internal data class AssSsaEventRecord(
    val kind: String,
    val prefix: String,
    val format: AssSsaEventFormat,
    val values: List<String>
) {
    val text: String
        get() = values[format.textIndex]

    fun field(name: String): String? {
        val index = format.fields.indexOfField(name)
        return values.getOrNull(index)
    }

    fun withText(text: String): AssSsaEventRecord {
        val next = values.toMutableList()
        next[format.textIndex] = text
        return copy(values = next)
    }

    fun render(): String {
        return prefix + values.joinToString(",")
    }

    companion object {
        fun parseDialogueLine(line: String, format: AssSsaEventFormat): AssSsaEventRecord? {
            val colon = line.indexOf(':')
            if (colon < 0) return null
            val kind = line.take(colon).trim()
            if (!kind.equals("Dialogue", ignoreCase = true) &&
                !kind.equals("Comment", ignoreCase = true)
            ) {
                return null
            }
            var payloadStart = colon + 1
            while (payloadStart < line.length && line[payloadStart].isWhitespace()) {
                payloadStart += 1
            }
            val prefix = line.take(payloadStart)
            val values = line.drop(payloadStart).split(',', limit = format.fields.size)
            if (values.size <= format.textIndex) return null
            return AssSsaEventRecord(kind = kind, prefix = prefix, format = format, values = values)
        }

        fun parseMatroskaSample(
            sampleText: String,
            format: AssSsaEventFormat,
            timeUs: Long,
            durationUs: Long
        ): AssSsaEventRecord? {
            val values = sampleText.split(',', limit = format.fields.size)
            if (values.size <= format.textIndex) return null
            val renderedValues = mutableListOf<String>()
            renderedValues += values.getOrNull(1)?.trim().orEmpty()
            renderedValues += formatAssTimeUs(timeUs)
            renderedValues += formatAssTimeUs(timeUs + durationUs)
            renderedValues += values.getOrNull(2).orEmpty()
            renderedValues += values.getOrNull(3).orEmpty()
            renderedValues += values.getOrNull(4).orEmpty()
            renderedValues += values.getOrNull(5).orEmpty()
            renderedValues += values.getOrNull(6).orEmpty()
            renderedValues += values.getOrNull(7).orEmpty()
            renderedValues += values[format.textIndex]
            val renderedFormat = AssSsaEventFormat.parse(
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
            )!!
            return AssSsaEventRecord(
                kind = "Dialogue",
                prefix = "Dialogue: ",
                format = renderedFormat,
                values = renderedValues
            )
        }
    }
}

private fun List<String>.indexOfField(name: String): Int {
    return indexOfFirst { it.trim().equals(name, ignoreCase = true) }
}

internal fun formatAssTimeUs(timeUs: Long): String {
    val totalCentiseconds = floor(timeUs / 10_000.0).toLong().coerceAtLeast(0L)
    val centiseconds = totalCentiseconds % 100
    val totalSeconds = totalCentiseconds / 100
    val seconds = totalSeconds % 60
    val totalMinutes = totalSeconds / 60
    val minutes = totalMinutes % 60
    val hours = totalMinutes / 60
    return String.format(
        Locale.US,
        "%d:%02d:%02d.%02d",
        hours,
        minutes,
        seconds,
        centiseconds
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaEventRecordTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/AssSsaEventRecord.kt app/src/test/java/com/nexio/tv/data/repository/AssSsaEventRecordTest.kt
git commit -m "feat: parse ass event records by format"
```

---

### Task 3: Add Lossless ASS Text Tokenizer

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTextTokenizer.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaTextTokenizerTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/nexio/tv/data/repository/AssSsaTextTokenizerTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AssSsaTextTokenizerTest {
    @Test
    fun tokenizesOverrideBlocksEscapesAndText() {
        val tokens = AssSsaTextTokenizer.tokenize(
            """{\an8\pos(320,40)}I am {\i1}not{\i0} amused.\NReally."""
        )

        assertEquals(
            listOf(
                AssSsaTextToken.OverrideBlock("""{\an8\pos(320,40)}"""),
                AssSsaTextToken.Text("I am "),
                AssSsaTextToken.OverrideBlock("""{\i1}"""),
                AssSsaTextToken.Text("not"),
                AssSsaTextToken.OverrideBlock("""{\i0}"""),
                AssSsaTextToken.Text(" amused."),
                AssSsaTextToken.LineBreak("""\N"""),
                AssSsaTextToken.Text("Really.")
            ),
            tokens
        )
    }

    @Test
    fun treatsDrawingModePayloadAsDrawing() {
        val tokens = AssSsaTextTokenizer.tokenize(
            """{\p1}m 0 0 l 100 0 100 100 0 100{\p0}Square"""
        )

        assertEquals(
            listOf(
                AssSsaTextToken.OverrideBlock("""{\p1}"""),
                AssSsaTextToken.Drawing("m 0 0 l 100 0 100 100 0 100"),
                AssSsaTextToken.OverrideBlock("""{\p0}"""),
                AssSsaTextToken.Text("Square")
            ),
            tokens
        )
    }

    @Test
    fun preservesMalformedOverrideBlock() {
        val tokens = AssSsaTextTokenizer.tokenize("""Hello {\i1broken""")

        assertEquals(
            listOf(
                AssSsaTextToken.Text("Hello "),
                AssSsaTextToken.Malformed("""{\i1broken""")
            ),
            tokens
        )
    }

    @Test
    fun preservesHardSpaceSeparately() {
        val tokens = AssSsaTextTokenizer.tokenize("""Hello\hworld""")

        assertEquals(
            listOf(
                AssSsaTextToken.Text("Hello"),
                AssSsaTextToken.HardSpace("""\h"""),
                AssSsaTextToken.Text("world")
            ),
            tokens
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaTextTokenizerTest
```

Expected: compilation fails because `AssSsaTextTokenizer` and `AssSsaTextToken` do not exist.

- [ ] **Step 3: Create the implementation**

Create `app/src/main/java/com/nexio/tv/data/repository/AssSsaTextTokenizer.kt`:

```kotlin
package com.nexio.tv.data.repository

internal sealed interface AssSsaTextToken {
    val raw: String

    data class Text(override val raw: String) : AssSsaTextToken
    data class OverrideBlock(override val raw: String) : AssSsaTextToken
    data class LineBreak(override val raw: String) : AssSsaTextToken
    data class HardSpace(override val raw: String) : AssSsaTextToken
    data class Drawing(override val raw: String) : AssSsaTextToken
    data class Malformed(override val raw: String) : AssSsaTextToken
}

internal object AssSsaTextTokenizer {
    fun tokenize(text: String): List<AssSsaTextToken> {
        val tokens = mutableListOf<AssSsaTextToken>()
        var drawingMode = 0
        var index = 0

        fun addPlain(raw: String) {
            if (raw.isEmpty()) return
            if (drawingMode > 0) {
                tokens += AssSsaTextToken.Drawing(raw)
                return
            }
            var cursor = 0
            val escapeRegex = Regex("""\\[Nnh]""")
            for (match in escapeRegex.findAll(raw)) {
                if (match.range.first > cursor) {
                    tokens += AssSsaTextToken.Text(raw.substring(cursor, match.range.first))
                }
                val escape = match.value
                tokens += when (escape) {
                    """\N""",
                    """\n""" -> AssSsaTextToken.LineBreak(escape)
                    """\h""" -> AssSsaTextToken.HardSpace(escape)
                    else -> AssSsaTextToken.Text(escape)
                }
                cursor = match.range.last + 1
            }
            if (cursor < raw.length) {
                tokens += AssSsaTextToken.Text(raw.substring(cursor))
            }
        }

        while (index < text.length) {
            val blockStart = text.indexOf('{', startIndex = index)
            if (blockStart < 0) {
                addPlain(text.substring(index))
                break
            }
            if (blockStart > index) {
                addPlain(text.substring(index, blockStart))
            }
            val blockEnd = text.indexOf('}', startIndex = blockStart + 1)
            if (blockEnd < 0) {
                tokens += AssSsaTextToken.Malformed(text.substring(blockStart))
                break
            }
            val block = text.substring(blockStart, blockEnd + 1)
            drawingMode = drawingModeAfterOverrideBlock(block, drawingMode)
            tokens += AssSsaTextToken.OverrideBlock(block)
            index = blockEnd + 1
        }

        return tokens.mergeAdjacentTextLikeTokens()
    }
}

private fun List<AssSsaTextToken>.mergeAdjacentTextLikeTokens(): List<AssSsaTextToken> {
    if (isEmpty()) return this
    val merged = mutableListOf<AssSsaTextToken>()
    for (token in this) {
        val previous = merged.lastOrNull()
        if (previous is AssSsaTextToken.Text && token is AssSsaTextToken.Text) {
            merged[merged.lastIndex] = AssSsaTextToken.Text(previous.raw + token.raw)
        } else if (previous is AssSsaTextToken.Drawing && token is AssSsaTextToken.Drawing) {
            merged[merged.lastIndex] = AssSsaTextToken.Drawing(previous.raw + token.raw)
        } else {
            merged += token
        }
    }
    return merged
}

private fun drawingModeAfterOverrideBlock(block: String, current: Int): Int {
    var drawingMode = current
    val inner = block.removePrefix("{").removeSuffix("}")
    var index = 0
    while (index < inner.length) {
        val slash = inner.indexOf('\\', startIndex = index)
        if (slash < 0 || slash == inner.lastIndex) break
        val nameStart = slash + 1
        if (inner[nameStart] == 'p') {
            val argStart = nameStart + 1
            if (argStart >= inner.length || !inner[argStart].isLetter()) {
                var argEnd = argStart
                while (argEnd < inner.length && inner[argEnd] != '\\') {
                    argEnd += 1
                }
                inner.substring(argStart, argEnd).trim().toIntOrNull()?.let { value ->
                    drawingMode = value
                }
                index = argEnd
                continue
            }
        }
        var nameEnd = nameStart
        while (nameEnd < inner.length && inner[nameEnd].isLetterOrDigit()) {
            nameEnd += 1
        }
        index = nameEnd
    }
    return drawingMode
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaTextTokenizerTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/AssSsaTextTokenizer.kt app/src/test/java/com/nexio/tv/data/repository/AssSsaTextTokenizerTest.kt
git commit -m "feat: tokenize ass text fields losslessly"
```

---

### Task 4: Add Protected Translation Units, Risk Classification, Validation, And Reconstruction

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/AssSsaProtectedTranslation.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaProtectedTranslationTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/nexio/tv/data/repository/AssSsaProtectedTranslationTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssSsaProtectedTranslationTest {
    @Test
    fun buildsPhraseUnitWithStyleAndLineBreakPlaceholders() {
        val unit = AssSsaProtectedTranslationUnit.fromTokens(
            id = "evt_1",
            tokens = AssSsaTextTokenizer.tokenize(
                """{\an8\pos(320,40)}I am {\i1}not{\i0} amused.\NReally."""
            )
        )

        assertEquals(AssSsaRisk.Normal, unit.risk)
        assertEquals(
            "I am ⟦ASS_000⟧not⟦ASS_001⟧ amused.⟦LB_002⟧Really.",
            unit.protectedText
        )
        assertEquals(
            """{\an8\pos(320,40)}Ik ben {\i1}niet{\i0} geamuseerd.\NEcht niet.""",
            unit.reconstruct("Ik ben ⟦ASS_000⟧niet⟦ASS_001⟧ geamuseerd.⟦LB_002⟧Echt niet.").getOrThrow()
        )
    }

    @Test
    fun rejectsMissingPlaceholder() {
        val unit = AssSsaProtectedTranslationUnit.fromTokens(
            id = "evt_1",
            tokens = AssSsaTextTokenizer.tokenize("""Hello {\i1}world{\i0}""")
        )

        val result = unit.reconstruct("Hallo wereld")

        assertTrue(result.isFailure)
        assertEquals("Missing placeholder ⟦ASS_000⟧", result.exceptionOrNull()?.message)
    }

    @Test
    fun rejectsRawAssSyntaxIntroducedByModel() {
        val unit = AssSsaProtectedTranslationUnit.fromTokens(
            id = "evt_1",
            tokens = AssSsaTextTokenizer.tokenize("Hello world")
        )

        val result = unit.reconstruct("""Hallo {\pos(1,2)}wereld""")

        assertTrue(result.isFailure)
        assertEquals("Translated text introduced raw ASS syntax", result.exceptionOrNull()?.message)
    }

    @Test
    fun classifiesDrawingModeAsPreserveOnly() {
        val unit = AssSsaProtectedTranslationUnit.fromTokens(
            id = "evt_1",
            tokens = AssSsaTextTokenizer.tokenize("""{\p1}m 0 0 l 100 0{\p0}Square""")
        )

        assertEquals(AssSsaRisk.PreserveOnly, unit.risk)
        assertEquals("Square", unit.protectedText)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaProtectedTranslationTest
```

Expected: compilation fails because protected translation classes do not exist.

- [ ] **Step 3: Create the implementation**

Create `app/src/main/java/com/nexio/tv/data/repository/AssSsaProtectedTranslation.kt`:

```kotlin
package com.nexio.tv.data.repository

internal enum class AssSsaRisk {
    Normal,
    Complex,
    PreserveOnly
}

internal data class AssSsaPlaceholder(
    val token: String,
    val raw: String,
    val sourceToken: AssSsaTextToken
)

internal data class AssSsaProtectedTranslationUnit(
    val id: String,
    val originalTokens: List<AssSsaTextToken>,
    val protectedText: String,
    val placeholders: List<AssSsaPlaceholder>,
    val risk: AssSsaRisk
) {
    fun reconstruct(translatedText: String): Result<String> = runCatching {
        validateTranslatedText(translatedText)
        var result = translatedText
        placeholders.forEach { placeholder ->
            result = result.replace(placeholder.token, placeholder.raw)
        }
        rebuildOriginalPrefixAndProtectedText(result)
    }

    private fun validateTranslatedText(translatedText: String) {
        placeholders.forEach { placeholder ->
            if (!translatedText.contains(placeholder.token)) {
                throw IllegalStateException("Missing placeholder ${placeholder.token}")
            }
        }
        if (translatedText.contains('{') ||
            translatedText.contains('}') ||
            Regex("""\\(?:pos|move|clip|iclip|p|t|fad|fade|org|an|c|alpha)""")
                .containsMatchIn(translatedText)
        ) {
            throw IllegalStateException("Translated text introduced raw ASS syntax")
        }
    }

    private fun rebuildOriginalPrefixAndProtectedText(reconstructedVisibleText: String): String {
        val leadingStructural = originalTokens
            .takeWhile { token ->
                token is AssSsaTextToken.OverrideBlock &&
                    token.raw.contains(Regex("""\\(?:an|pos|move|clip|iclip|org|fad|fade)"""))
            }
            .joinToString("") { it.raw }
        return leadingStructural + reconstructedVisibleText
    }

    companion object {
        fun fromTokens(id: String, tokens: List<AssSsaTextToken>): AssSsaProtectedTranslationUnit {
            val placeholders = mutableListOf<AssSsaPlaceholder>()
            val protected = StringBuilder()
            var index = 0
            var sawDrawing = false
            var sawKaraoke = false

            tokens.forEach { token ->
                when (token) {
                    is AssSsaTextToken.Text -> protected.append(token.raw)
                    is AssSsaTextToken.LineBreak -> {
                        val marker = "⟦LB_${index.toString().padStart(3, '0')}⟧"
                        index += 1
                        placeholders += AssSsaPlaceholder(marker, token.raw, token)
                        protected.append(marker)
                    }
                    is AssSsaTextToken.HardSpace -> {
                        val marker = "⟦HS_${index.toString().padStart(3, '0')}⟧"
                        index += 1
                        placeholders += AssSsaPlaceholder(marker, token.raw, token)
                        protected.append(marker)
                    }
                    is AssSsaTextToken.OverrideBlock -> {
                        if (token.raw.contains(Regex("""\\(?:k|K|kf|ko|kt)\d*"""))) {
                            sawKaraoke = true
                        }
                        if (token.raw.contains(Regex("""\\(?:an|pos|move|clip|iclip|org|fad|fade)"""))) {
                            return@forEach
                        }
                        val marker = "⟦ASS_${index.toString().padStart(3, '0')}⟧"
                        index += 1
                        placeholders += AssSsaPlaceholder(marker, token.raw, token)
                        protected.append(marker)
                    }
                    is AssSsaTextToken.Drawing -> {
                        sawDrawing = true
                    }
                    is AssSsaTextToken.Malformed -> {
                        sawDrawing = true
                    }
                }
            }

            val risk = when {
                sawDrawing -> AssSsaRisk.PreserveOnly
                sawKaraoke -> AssSsaRisk.Complex
                placeholders.size >= 8 -> AssSsaRisk.Complex
                else -> AssSsaRisk.Normal
            }
            return AssSsaProtectedTranslationUnit(
                id = id,
                originalTokens = tokens,
                protectedText = protected.toString(),
                placeholders = placeholders,
                risk = risk
            )
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaProtectedTranslationTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/AssSsaProtectedTranslation.kt app/src/test/java/com/nexio/tv/data/repository/AssSsaProtectedTranslationTest.kt
git commit -m "feat: protect ass text during translation"
```

---

### Task 5: Add ASS/SSA Batch Planning

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTranslationBatchPlanner.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaTranslationBatchPlannerTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/nexio/tv/data/repository/AssSsaTranslationBatchPlannerTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AssSsaTranslationBatchPlannerTest {
    @Test
    fun batchesByEventCount() {
        val units = (0 until 5).map { index ->
            AssSsaProtectedTranslationUnit.fromTokens(
                id = "evt_$index",
                tokens = AssSsaTextTokenizer.tokenize("Line $index")
            )
        }

        val batches = AssSsaTranslationBatchPlanner.plan(
            units = units,
            config = AssSsaTranslationBatchConfig(maxEvents = 2, maxVisibleChars = 10_000)
        )

        assertEquals(listOf(2, 2, 1), batches.map { it.units.size })
    }

    @Test
    fun excludesPreserveOnlyUnitsFromProviderBatches() {
        val normal = AssSsaProtectedTranslationUnit.fromTokens(
            id = "normal",
            tokens = AssSsaTextTokenizer.tokenize("Hello")
        )
        val preserve = AssSsaProtectedTranslationUnit.fromTokens(
            id = "drawing",
            tokens = AssSsaTextTokenizer.tokenize("""{\p1}m 0 0 l 10 0{\p0}""")
        )

        val batches = AssSsaTranslationBatchPlanner.plan(
            units = listOf(normal, preserve),
            config = AssSsaTranslationBatchConfig(maxEvents = 10, maxVisibleChars = 10_000)
        )

        assertEquals(listOf(listOf("normal")), batches.map { batch -> batch.units.map { it.id } })
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaTranslationBatchPlannerTest
```

Expected: compilation fails because batch planner classes do not exist.

- [ ] **Step 3: Create the implementation**

Create `app/src/main/java/com/nexio/tv/data/repository/AssSsaTranslationBatchPlanner.kt`:

```kotlin
package com.nexio.tv.data.repository

internal data class AssSsaTranslationBatchConfig(
    val maxEvents: Int = 80,
    val maxVisibleChars: Int = 8_000
)

internal data class AssSsaTranslationBatch(
    val units: List<AssSsaProtectedTranslationUnit>
)

internal object AssSsaTranslationBatchPlanner {
    fun plan(
        units: List<AssSsaProtectedTranslationUnit>,
        config: AssSsaTranslationBatchConfig = AssSsaTranslationBatchConfig()
    ): List<AssSsaTranslationBatch> {
        val batches = mutableListOf<AssSsaTranslationBatch>()
        var current = mutableListOf<AssSsaProtectedTranslationUnit>()
        var chars = 0

        fun flush() {
            if (current.isNotEmpty()) {
                batches += AssSsaTranslationBatch(current.toList())
                current = mutableListOf()
                chars = 0
            }
        }

        units.filter { it.risk != AssSsaRisk.PreserveOnly && it.protectedText.isNotBlank() }
            .forEach { unit ->
                val nextChars = chars + unit.protectedText.length
                if (current.isNotEmpty() &&
                    (current.size >= config.maxEvents || nextChars > config.maxVisibleChars)
                ) {
                    flush()
                }
                current += unit
                chars += unit.protectedText.length
            }
        flush()
        return batches
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaTranslationBatchPlannerTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/AssSsaTranslationBatchPlanner.kt app/src/test/java/com/nexio/tv/data/repository/AssSsaTranslationBatchPlannerTest.kt
git commit -m "feat: batch protected ass translation units"
```

---

### Task 6: Add Protected ASS Batch Translation To SubtitleTranslationService

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServiceProviderTest.kt`

- [ ] **Step 1: Add failing provider test**

Append this test to `SubtitleTranslationServiceProviderTest`:

```kotlin
@Test
fun protectedAssBatchTranslationSendsPlaceholderContractAndMapsItems() = runTest {
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
                        "content": "{\"items\":[{\"id\":\"evt_1\",\"text\":\"Ik ben ⟦ASS_000⟧niet⟦ASS_001⟧ boos\"}]}"
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
            httpClient = OkHttpClient()
        )
        val unit = AssSsaProtectedTranslationUnit.fromTokens(
            id = "evt_1",
            tokens = AssSsaTextTokenizer.tokenize("""I am {\i1}not{\i0} angry""")
        )

        val result = service.translateProtectedAssSsaUnits(
            units = listOf(unit),
            targetLanguageCode = "nl",
            sourceLanguageCode = "en",
            settings = SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.OPENAI,
                apiKey = "test-key",
                model = "gpt-5-nano",
                baseUrl = server.url("/v1").toString()
            )
        )

        assertEquals(
            mapOf("evt_1" to "Ik ben ⟦ASS_000⟧niet⟦ASS_001⟧ boos"),
            result.getOrThrow()
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("Translate subtitle text from the source language to the target language."))
        assertTrue(body.contains("⟦[A-Z_]+_[0-9]+⟧"))
        assertTrue(body.contains("I am ⟦ASS_000⟧not⟦ASS_001⟧ angry"))
    } finally {
        server.shutdown()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest.protectedAssBatchTranslationSendsPlaceholderContractAndMapsItems
```

Expected: compilation fails because `translateProtectedAssSsaUnits` does not exist.

- [ ] **Step 3: Add the service method and prompt builder**

In `SubtitleTranslationService`, add:

```kotlin
suspend fun translateProtectedAssSsaUnits(
    units: List<AssSsaProtectedTranslationUnit>,
    targetLanguageCode: String,
    sourceLanguageCode: String?,
    settings: SubtitleTranslationSettings
): Result<Map<String, String>> = withContext(Dispatchers.IO) {
    runCatching {
        val normalizedSettings = settings.copy(apiKey = settings.apiKey.trim())
        if (normalizedSettings.apiKey.isBlank()) {
            throw IllegalArgumentException("Subtitle translation API key is missing.")
        }
        if (units.isEmpty()) return@runCatching emptyMap()

        val payload = JSONObject()
            .put("source_language", sourceLanguageCode.orEmpty().ifBlank { "auto" })
            .put("target_language", targetLanguageCode)
            .put("placeholder_pattern", "⟦[A-Z_]+_[0-9]+⟧")
            .put(
                "items",
                JSONArray().apply {
                    units.forEach { unit ->
                        put(
                            JSONObject()
                                .put("id", unit.id)
                                .put("text", unit.protectedText)
                                .put(
                                    "placeholders",
                                    JSONArray().apply {
                                        unit.placeholders.forEach { placeholder ->
                                            put(placeholder.token)
                                        }
                                    }
                                )
                        )
                    }
                }
            )

        val response = executeTranslationRequest(
            promptPayload = payload,
            targetLanguageCode = targetLanguageCode,
            targetLanguageName = displayLanguage(targetLanguageCode),
            sourceLanguageName = displaySourceLanguage(sourceLanguageCode),
            markerPayload = null,
            settings = normalizedSettings,
            includeSchema = true,
            systemPromptOverride = buildProtectedAssSsaSystemPrompt()
        ) ?: throw IllegalStateException("Subtitle translation provider did not return a translation payload.")

        parseProtectedAssSsaResponse(response, units)
    }
}
```

Change `executeTranslationRequest` signature to accept an optional prompt:

```kotlin
private fun executeTranslationRequest(
    promptPayload: JSONObject,
    targetLanguageCode: String,
    targetLanguageName: String,
    sourceLanguageName: String,
    markerPayload: String?,
    settings: SubtitleTranslationSettings,
    includeSchema: Boolean,
    systemPromptOverride: String? = null
): String? {
    val systemPrompt = systemPromptOverride
        ?: buildTranslationSystemPrompt(targetLanguageCode, targetLanguageName)
}
```

After adding the `systemPromptOverride` parameter and the two-line `systemPrompt` assignment shown above, keep the existing provider request construction, retry loop, error classification, and response parsing logic in `executeTranslationRequest` unchanged.

Add:

```kotlin
private fun buildProtectedAssSsaSystemPrompt(): String {
    return """
        You are a subtitle localization engine.
        Translate subtitle text from the source language to the target language.
        Return JSON only with this exact shape: {"items":[{"id":"same id as input","text":"translated subtitle text"}]}.
        Translate only item.text.
        Do not translate, edit, remove, reorder, duplicate, or normalize placeholders.
        Placeholders match this pattern: ⟦[A-Z_]+_[0-9]+⟧.
        Copy every placeholder from the source item.text into the translated item.text exactly.
        Preserve the semantic role of placeholders. If a placeholder marks styling around a word, place it around the translated equivalent of that word.
        Preserve line-break placeholders unless the input explicitly says line breaks may be adjusted.
        Do not introduce raw ASS/SSA syntax. Do not output "{", "}", "\pos", "\move", "\clip", "\iclip", "\p", "\t", "\fad", "\fade", "\org", color tags, or any backslash ASS override tag.
        Do not add markdown, comments, explanations, code fences, or extra keys.
        Preserve subtitle brevity and natural speech.
        The number of output items must equal the number of input items.
        Every output id must match one input id exactly.
    """.trimIndent()
}

private fun parseProtectedAssSsaResponse(
    responseText: String,
    units: List<AssSsaProtectedTranslationUnit>
): Map<String, String> {
    val normalized = sanitizeJsonResponse(responseText)
    val array = when {
        normalized.startsWith("[") -> JSONArray(normalized)
        normalized.startsWith("{") -> JSONObject(normalized).optJSONArray("items") ?: JSONArray()
        else -> JSONArray()
    }
    if (array.length() == 0) {
        throw IllegalStateException("Subtitle translation provider returned an empty translation payload.")
    }
    val expected = units.associateBy { it.id }
    val parsed = mutableMapOf<String, String>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val id = item.optString("id")
        val text = item.optString("text")
        val unit = expected[id] ?: continue
        unit.reconstruct(text).getOrThrow()
        parsed[id] = text
    }
    expected.keys.forEach { id ->
        if (!parsed.containsKey(id)) {
            throw IllegalStateException("Subtitle translation provider returned an incomplete translation payload.")
        }
    }
    return parsed
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest.protectedAssBatchTranslationSendsPlaceholderContractAndMapsItems
```

Expected: PASS.

- [ ] **Step 5: Run existing provider tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServiceProviderTest.kt
git commit -m "feat: translate protected ass batches"
```

---

### Task 7: Convert Sidecar ASS/SSA Translation To Protected Phrase Mode

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTimedTextDocument.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TimedTextDocument.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaTimedTextDocumentTest.kt`

- [ ] **Step 1: Add failing sidecar phrase-mode test**

Append this test to `AssSsaTimedTextDocumentTest`:

```kotlin
@Test
fun phraseModeKeepsInlineStylePlaceholderAroundTranslatedEquivalent() {
    val document = TimedTextDocument.parse(
        raw = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,I am {\i1}not{\i0} amused.\NReally.
        """.trimIndent(),
        url = "file:///tmp/subtitle.ass"
    )!!

    val units = document.assSsaProtectedUnits()

    assertEquals(
        listOf("I am ⟦ASS_000⟧not⟦ASS_001⟧ amused.⟦LB_002⟧Really."),
        units.map { it.protectedText }
    )
    assertEquals(
        """
        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Ik ben {\i1}niet{\i0} geamuseerd.\NEcht niet.
        """.trimIndent() + "\n",
        document.renderAssSsaProtected(
            mapOf(
                "ass_0" to "Ik ben ⟦ASS_000⟧niet⟦ASS_001⟧ geamuseerd.⟦LB_002⟧Echt niet."
            )
        )
    )
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaTimedTextDocumentTest.phraseModeKeepsInlineStylePlaceholderAroundTranslatedEquivalent
```

Expected: compilation fails because `assSsaProtectedUnits` and `renderAssSsaProtected` do not exist.

- [ ] **Step 3: Add protected blocks to TimedTextDocument**

Add to `TimedTextDocument`:

```kotlin
fun assSsaProtectedUnits(): List<AssSsaProtectedTranslationUnit> {
    if (format != TimedTextFormat.ASS && format != TimedTextFormat.SSA) return emptyList()
    return blocks.mapIndexedNotNull { index, block ->
        (block as? AssSsaDialogueBlock)?.toProtectedTranslationUnit("ass_$index")
    }
}

fun renderAssSsaProtected(translations: Map<String, String>): String {
    if (format != TimedTextFormat.ASS && format != TimedTextFormat.SSA) {
        return render(emptyMap())
    }
    return blocks.mapIndexed { index, block ->
        if (block is AssSsaDialogueBlock) {
            val unitId = "ass_$index"
            block.renderProtected(translations[unitId])
        } else {
            block.render(emptyMap())
        }
    }.joinToString("\n").trim() + "\n"
}
```

Add to `AssSsaDialogueBlock`:

```kotlin
fun toProtectedTranslationUnit(id: String): AssSsaProtectedTranslationUnit {
    return AssSsaProtectedTranslationUnit.fromTokens(
        id = id,
        tokens = textSegments.map { segment ->
            when (segment) {
                is LiteralAssSsaTextSegment -> AssSsaTextTokenizer.tokenize(segment.raw)
                is TranslatableAssSsaTextSegment -> listOf(AssSsaTextToken.Text(segment.fallback))
            }
        }.flatten()
    )
}

fun renderProtected(translatedProtectedText: String?): String {
    if (translatedProtectedText.isNullOrBlank()) return render(emptyMap())
    val unit = toProtectedTranslationUnit("render")
    val translatedText = unit.reconstruct(translatedProtectedText).getOrElse { return render(emptyMap()) }
    return if (fieldsBeforeText.isEmpty()) {
        prefix + translatedText
    } else {
        prefix + fieldsBeforeText.joinToString(",") + "," + translatedText
    }
}
```

Change `LiteralAssSsaTextSegment` visibility and property:

```kotlin
internal data class LiteralAssSsaTextSegment(
    val raw: String
) : AssSsaTextSegment {
    override fun render(translations: Map<Int, String>): String = raw
}
```

- [ ] **Step 4: Route ASS document translation through protected units**

In `SubtitleTranslationService.translateSubtitle`, when `document.format` is `ASS` or `SSA`, use:

```kotlin
val payload = if (document.format == TimedTextFormat.ASS || document.format == TimedTextFormat.SSA) {
    val units = document.assSsaProtectedUnits()
    val batches = AssSsaTranslationBatchPlanner.plan(units)
    val protectedTranslations = mutableMapOf<String, String>()
    batches.forEach { batch ->
        protectedTranslations += translateProtectedAssSsaUnits(
            units = batch.units,
            targetLanguageCode = normalizedTarget,
            sourceLanguageCode = sourceLanguageCode,
            settings = normalizedSettings
        ).getOrThrow()
    }
    document.renderAssSsaProtected(protectedTranslations)
} else {
    val translatedBlocks = translateBlocks(
        blocks = document.translatableBlocks,
        targetLanguageCode = normalizedTarget,
        sourceLanguageCode = sourceLanguageCode,
        settings = normalizedSettings
    )
    document.render(translatedBlocks)
}
```

- [ ] **Step 5: Run tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaTimedTextDocumentTest --tests com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/AssSsaTimedTextDocument.kt app/src/main/java/com/nexio/tv/data/repository/TimedTextDocument.kt app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt app/src/test/java/com/nexio/tv/data/repository/AssSsaTimedTextDocumentTest.kt
git commit -m "feat: translate sidecar ass with protected placeholders"
```

---

### Task 8: Add Embedded assrender Translation Sink Wrapper

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.nexio.tv.data.repository.AssSsaProtectedTranslationUnit
import com.nexio.tv.data.repository.AssSsaTextTokenizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AssSsaTranslatingSampleSinkTest {
    @Test
    fun translatesDialogueSampleBeforeDelegatingToAssRenderer() = runTest {
        val downstream = RecordingAssSsaSampleSink()
        val sink = AssSsaTranslatingSampleSink(
            downstream = downstream,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isEnabled = { true },
            translate = { units ->
                assertEquals(listOf("I am ⟦ASS_000⟧not⟦ASS_001⟧ angry"), units.map { it.protectedText })
                mapOf("evt_0" to "Ik ben ⟦ASS_000⟧niet⟦ASS_001⟧ boos")
            }
        )

        sink.onTrackHeader(
            trackId = 4,
            headerData = "[Script Info]\nScriptType: v4.00+\n".toByteArray(),
            format = Format.Builder()
                .setSampleMimeType(MimeTypes.TEXT_SSA)
                .setContainerMimeType(MimeTypes.VIDEO_MATROSKA)
                .build()
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
    fun delegatesOriginalSampleWhenTranslationIsDisabled() = runTest {
        val downstream = RecordingAssSsaSampleSink()
        val sink = AssSsaTranslatingSampleSink(
            downstream = downstream,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isEnabled = { false },
            translate = { emptyMap() }
        )
        val sample = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello".toByteArray()

        sink.onSubtitleSample(trackId = 4, timeUs = 1_000_000L, data = sample)

        assertEquals("Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello", downstream.samples.single().decodeToString())
    }

    private class RecordingAssSsaSampleSink : AssSsaSampleSink {
        val samples = mutableListOf<ByteArray>()

        override fun onTrackHeader(trackId: Int, headerData: ByteArray, format: Format) = Unit

        override fun onSubtitleSample(trackId: Int, timeUs: Long, data: ByteArray) {
            samples += data
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: compilation fails because `AssSsaTranslatingSampleSink` does not exist.

- [ ] **Step 3: Create the sink wrapper**

Create `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.Format
import com.nexio.tv.data.repository.AssSsaEventFormat
import com.nexio.tv.data.repository.AssSsaEventRecord
import com.nexio.tv.data.repository.AssSsaProtectedTranslationUnit
import com.nexio.tv.data.repository.AssSsaTextTokenizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class AssSsaTranslatingSampleSink(
    private val downstream: AssSsaSampleSink,
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean,
    private val translate: suspend (List<AssSsaProtectedTranslationUnit>) -> Map<String, String>
) : AssSsaSampleSink {
    private val trackFormats = linkedMapOf<Int, AssSsaEventFormat>()

    override fun onTrackHeader(trackId: Int, headerData: ByteArray, format: Format) {
        trackFormats[trackId] = AssSsaEventFormat.parse(
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
        )!!
        downstream.onTrackHeader(trackId, headerData, format)
    }

    override fun onSubtitleSample(trackId: Int, timeUs: Long, data: ByteArray) {
        if (!isEnabled()) {
            downstream.onSubtitleSample(trackId, timeUs, data)
            return
        }

        val text = data.decodeToString()
        val format = trackFormats[trackId] ?: AssSsaEventFormat.parse(
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
        )!!
        val records = text.lineSequence()
            .mapNotNull { line -> AssSsaEventRecord.parseDialogueLine(line, format) }
            .toList()
        if (records.isEmpty()) {
            downstream.onSubtitleSample(trackId, timeUs, data)
            return
        }

        val unitsById = records.mapIndexed { index, record ->
            "evt_$index" to AssSsaProtectedTranslationUnit.fromTokens(
                id = "evt_$index",
                tokens = AssSsaTextTokenizer.tokenize(record.text)
            )
        }
        scope.launch {
            val translated = runCatching {
                translate(unitsById.map { it.second })
            }.getOrDefault(emptyMap())
            val translatedLines = records.mapIndexed { index, record ->
                val unitId = "evt_$index"
                val unit = unitsById[index].second
                val translatedText = translated[unitId]
                val reconstructed = translatedText
                    ?.let { unit.reconstruct(it).getOrNull() }
                    ?: record.text
                record.withText(reconstructed).render()
            }
            downstream.onSubtitleSample(trackId, timeUs, translatedLines.joinToString("\n").toByteArray())
        }
    }

    override fun onFontAttachment(name: String, data: ByteArray) {
        downstream.onFontAttachment(name, data)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt
git commit -m "feat: translate ass samples before assrender"
```

---

### Task 9: Wire Embedded Translation Into Player Initialization

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAssSsaPipelineTest.kt`

- [ ] **Step 1: Add a small factory test**

Append this test to `PlayerRuntimeControllerAssSsaPipelineTest`:

```kotlin
@Test
fun assSsaTranslationSinkIsOnlyEnabledWhenAiTranslationIsConfigured() {
    assertEquals(
        false,
        shouldEnableAssSsaSampleTranslation(
            aiSubtitlesEnabled = false,
            selectedAddonSubtitlePresent = false,
            selectedSubtitleTrackIndex = 0,
            translationSettingsEnabled = true,
            translationApiKeyPresent = true
        )
    )
    assertEquals(
        true,
        shouldEnableAssSsaSampleTranslation(
            aiSubtitlesEnabled = true,
            selectedAddonSubtitlePresent = false,
            selectedSubtitleTrackIndex = 0,
            translationSettingsEnabled = true,
            translationApiKeyPresent = true
        )
    )
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerAssSsaPipelineTest.assSsaTranslationSinkIsOnlyEnabledWhenAiTranslationIsConfigured
```

Expected: compilation fails because `shouldEnableAssSsaSampleTranslation` does not exist.

- [ ] **Step 3: Add the pure decision helper**

In `PlayerRuntimeControllerInitialization.kt`, add:

```kotlin
internal fun shouldEnableAssSsaSampleTranslation(
    aiSubtitlesEnabled: Boolean,
    selectedAddonSubtitlePresent: Boolean,
    selectedSubtitleTrackIndex: Int,
    translationSettingsEnabled: Boolean,
    translationApiKeyPresent: Boolean
): Boolean {
    return aiSubtitlesEnabled &&
        !selectedAddonSubtitlePresent &&
        selectedSubtitleTrackIndex >= 0 &&
        translationSettingsEnabled &&
        translationApiKeyPresent
}
```

- [ ] **Step 4: Wire sink construction**

In `initializePlayer`, replace:

```kotlin
val assController = if (useAssSsaPipeline && assSsaOverlayView != null) {
    AssSsaRenderController(
        context = context,
        overlayView = assSsaOverlayView,
        subtitleDelayUsProvider = subtitleDelayUs::get
    )
} else {
    null
}
```

with:

```kotlin
val assController = if (useAssSsaPipeline && assSsaOverlayView != null) {
    AssSsaRenderController(
        context = context,
        overlayView = assSsaOverlayView,
        subtitleDelayUsProvider = subtitleDelayUs::get
    )
} else {
    null
}
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
        translate = { units ->
            val batches = AssSsaTranslationBatchPlanner.plan(units)
            val translated = mutableMapOf<String, String>()
            batches.forEach { batch ->
                translated += subtitleTranslationService.translateProtectedAssSsaUnits(
                    units = batch.units,
                    targetLanguageCode = _uiState.value.subtitleStyle.preferredLanguage,
                    sourceLanguageCode = null,
                    settings = subtitleTranslationSettings
                ).getOrThrow()
            }
            translated
        }
    )
}
```

Then replace:

```kotlin
extractorsFactory = AssSsaExtractorsFactory(extractorsFactory, assController),
```

with:

```kotlin
extractorsFactory = AssSsaExtractorsFactory(extractorsFactory, assSampleSink ?: assController),
```

Add imports:

```kotlin
import com.nexio.tv.data.repository.AssSsaTranslationBatchPlanner
import com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSink
```

- [ ] **Step 5: Run tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerAssSsaPipelineTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAssSsaPipelineTest.kt
git commit -m "feat: route embedded ass translation through assrender"
```

---

### Task 10: Add Fallback And Backpressure Behavior

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt`

- [ ] **Step 1: Add failing fallback tests**

Append:

```kotlin
@Test
fun fallsBackToOriginalSampleWhenProviderThrows() = runTest {
    val downstream = RecordingAssSsaSampleSink()
    val sink = AssSsaTranslatingSampleSink(
        downstream = downstream,
        scope = CoroutineScope(Dispatchers.Unconfined),
        isEnabled = { true },
        translate = { throw IllegalStateException("provider down") }
    )
    val sample = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello".toByteArray()

    sink.onSubtitleSample(trackId = 4, timeUs = 1_000_000L, data = sample)

    assertEquals("Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello", downstream.samples.single().decodeToString())
}

@Test
fun preserveOnlyDrawingSampleIsNotSentToProvider() = runTest {
    var providerCalls = 0
    val downstream = RecordingAssSsaSampleSink()
    val sink = AssSsaTranslatingSampleSink(
        downstream = downstream,
        scope = CoroutineScope(Dispatchers.Unconfined),
        isEnabled = { true },
        translate = {
            providerCalls += 1
            emptyMap()
        }
    )
    val sample = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\p1}m 0 0 l 100 0{\\p0}".toByteArray()

    sink.onSubtitleSample(trackId = 4, timeUs = 1_000_000L, data = sample)

    assertEquals(0, providerCalls)
    assertEquals(sample.decodeToString(), downstream.samples.single().decodeToString())
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: `preserveOnlyDrawingSampleIsNotSentToProvider` fails because preserve-only units are currently included in provider calls.

- [ ] **Step 3: Filter preserve-only units**

In `AssSsaTranslatingSampleSink.onSubtitleSample`, replace:

```kotlin
val translated = runCatching {
    translate(unitsById.map { it.second })
}.getOrDefault(emptyMap())
```

with:

```kotlin
val translatableUnits = unitsById
    .map { it.second }
    .filter { it.risk != AssSsaRisk.PreserveOnly && it.protectedText.isNotBlank() }
if (translatableUnits.isEmpty()) {
    downstream.onSubtitleSample(trackId, timeUs, data)
    return@launch
}
val translated = runCatching {
    translate(translatableUnits)
}.getOrDefault(emptyMap())
```

Add import:

```kotlin
import com.nexio.tv.data.repository.AssSsaRisk
```

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt
git commit -m "fix: fallback safely for complex ass samples"
```

---

### Task 11: Add Documentation And Manual Validation Notes

**Files:**
- Modify: `README.md`
- Create: `docs/solutions/ass-ssa-protected-translation.md`

- [ ] **Step 1: Create solution documentation**

Create `docs/solutions/ass-ssa-protected-translation.md`:

```markdown
# ASS/SSA Protected Translation

Nexio translates ASS/SSA subtitles with a syntax-preserving pipeline:

1. Parse event records using the active `Format:` line.
2. Tokenize only the event `Text` field.
3. Preserve ASS override blocks, line breaks, hard spaces, drawing payloads, and malformed spans verbatim.
4. Send only visible subtitle language to the provider using immutable placeholders such as `⟦ASS_000⟧` and `⟦LB_000⟧`.
5. Validate every provider response before reconstruction.
6. Reconstruct ASS/SSA text from original raw tokens.
7. Render embedded subtitles through `AssSsaRenderController` and libass.

The Media3 cue translator intentionally does not handle ASS/SSA because Media3 converts ASS into generic cue geometry and strips override blocks. That path loses semantics for tags such as `\pos`, `\move`, `\clip`, `\iclip`, `\org`, `\fade`, `\fad`, drawing mode, and karaoke timing.

Manual validation:

```bash
adb connect 192.168.50.71
adb -s 192.168.50.71:5555 logcat -c
adb -s 192.168.50.71:5555 logcat -v threadtime | grep -E 'ASS_SSA_RENDER|SubtitleTranslation|TextRenderer'
```

Expected behavior:

- ASS/SSA playback uses `ASS_SSA_RENDER` / assrender logs.
- `TextRenderer` does not log entries that contain both `streamFormat=` and `text/x-ssa` for translation failures.
- Translated ASS lines preserve original positioning and movement.
- Drawing-only events are preserved without provider calls.
```

- [ ] **Step 2: Add README note**

Add a short note under the subtitle or playback section in `README.md`:

```markdown
ASS/SSA subtitles use a protected translation pipeline: Nexio tokenizes ASS structure, translates only visible language text, validates placeholders, reconstructs ASS events, and renders through libass/assrender. Generic Media3 cue translation is disabled for ASS/SSA to preserve positioning, movement, drawing, and karaoke semantics.
```

- [ ] **Step 3: Commit**

```bash
git add README.md docs/solutions/ass-ssa-protected-translation.md
git commit -m "docs: document ass protected translation"
```

---

## Final Verification

- [ ] **Step 1: Run targeted unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.nexio.tv.ui.screens.player.BuiltInSubtitleCueTranslatorTest \
  --tests com.nexio.tv.data.repository.AssSsaEventRecordTest \
  --tests com.nexio.tv.data.repository.AssSsaTextTokenizerTest \
  --tests com.nexio.tv.data.repository.AssSsaProtectedTranslationTest \
  --tests com.nexio.tv.data.repository.AssSsaTranslationBatchPlannerTest \
  --tests com.nexio.tv.data.repository.AssSsaTimedTextDocumentTest \
  --tests com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest \
  --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerAssSsaPipelineTest \
  --tests com.nexio.tv.ui.screens.player.ass.AssNoOpSubtitleParserFactoryTest \
  --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Build debug APK**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Device smoke test**

Run:

```bash
adb connect 192.168.50.71
adb -s 192.168.50.71:5555 install -r app/build/outputs/apk/universal/debug/app-universal-debug.apk
adb -s 192.168.50.71:5555 logcat -c
```

Then play an embedded ASS/SSA stream with AI subtitles enabled.

Run:

```bash
adb -s 192.168.50.71:5555 logcat -d -v threadtime | grep -E 'ASS_SSA_RENDER|SubtitleTranslation|TextRenderer|text/x-ssa' | tail -n 120
```

Expected:
- no repeated `TextRenderer` lines that contain both `Cue group translation failed` and `text/x-ssa`
- assrender overlay remains active for ASS/SSA tracks
- positioned translated lines stay in their original visual region
- drawing-heavy lines remain unchanged rather than malformed

---

## Self-Review

**Spec coverage:**
- Lossless parse is covered by Tasks 2 and 3.
- Protected phrase-mode translation is covered by Tasks 4 and 6.
- Placeholder validation is covered by Task 4.
- Large chunk batching is covered by Task 5 and used by Tasks 7 and 9.
- assrender path integration is covered by Tasks 8 and 9.
- Fallback/backpressure behavior is covered by Task 10.
- Documentation is covered by Task 11.

**Placeholder scan:**
- A scan was performed for banned placeholder phrases and remaining matches were removed.
- Every code-facing task includes concrete test code, implementation code, commands, and expected results.

**Type consistency:**
- `AssSsaEventFormat`, `AssSsaEventRecord`, `AssSsaTextToken`, `AssSsaTextTokenizer`, `AssSsaProtectedTranslationUnit`, `AssSsaTranslationBatchPlanner`, and `AssSsaTranslatingSampleSink` are introduced before use.
- The assrender integration uses the existing `AssSsaSampleSink` boundary.
- The provider integration uses the existing `SubtitleTranslationService` dependency.
