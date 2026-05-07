# ASS/SSA Auto-Translate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend subtitle auto-translate so addon ASS/SSA subtitle files can be translated while preserving override blocks, drawing payloads, timing, styles, and non-dialogue file content.

**Architecture:** Keep the existing provider/chunking/cache behavior in `SubtitleTranslationService`, but extract subtitle document parsing into focused Kotlin files. Add an ASS/SSA parser that only translates dialogue `Text` field visible-language segments, preserves all override blocks exactly, skips drawing payload while `\pN` is active, and reconstructs the original ASS/SSA event lines with translated text inserted back into the same `Text` column. Wire ASS/SSA through the existing translated-file media-source path, not the SRT/VTT no-rebuffer overlay path.

**Tech Stack:** Android Kotlin, Media3 `MimeTypes.TEXT_SSA`, OkHttp-backed subtitle download, existing `SubtitleTranslationService`, JUnit 4 Gradle unit tests, docs-site Markdown.

---

## Source Notes

- Use the Aegisub ASS Override Tags manual as the practical tag behavior reference: https://aegisub.org/docs/latest/ass_tags/
- Do not add `ass-compiler`, `ass-parser`, or `ass-stringify` as runtime dependencies. They are JavaScript packages, and `ass-parser` leaves the dialogue `Text` field as raw text while `ass-compiler` compile mode normalizes content. The needed Android runtime behavior is a small Kotlin lossless text-field tokenizer.

## File Map

- Create: `app/src/main/java/com/nexio/tv/data/repository/TimedTextDocument.kt`
  - Owns `TimedTextFormat`, `TimedTextBlock`, `TranslatableTimedTextBlock`, `PassthroughTimedTextBlock`, `TimedTextDocument`, and the SRT/VTT parser currently private to `SubtitleTranslationService`.
  - Adds dispatch for `.ass` and `.ssa`.
- Create: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTimedTextDocument.kt`
  - Parses ASS/SSA `[Events]` `Format:` lines, `Dialogue:` lines, and dialogue `Text` fields.
  - Tokenizes dialogue text so visible text is translated, override blocks and control escapes are preserved, and drawing payload is preserved while `\pN` is active.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`
  - Removes the private timed-text classes from the bottom of the file and calls the extracted parser.
  - No provider request, chunking, cache-key, or retry behavior changes.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAiSubtitles.kt`
  - Adds `MimeTypes.TEXT_SSA` to `supportsAiTranslation`.
  - Leaves `addonSubtitleSupportsOverlay` unchanged so ASS/SSA goes through translated-file playback instead of the overlay path.
- Test: `app/src/test/java/com/nexio/tv/data/repository/TimedTextDocumentTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaTimedTextDocumentTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAddonSubtitleOverlayTest.kt`
- Modify: `docs-site/playback/subtitles-and-auto-translate.md`
- Modify: `docs-site/features/index.md`
- Modify: `docs-site/troubleshooting/index.md`

## ASS/SSA Parsing Contract

Use this contract when implementing and reviewing:

- `.ass` and `.ssa` files are text subtitle files eligible for auto-translate.
- Only `Dialogue:` event text is translated. `[Script Info]`, styles, fonts, comments, attachments, and non-dialogue event lines are preserved as passthrough content.
- The active `[Events]` `Format:` line determines which comma-delimited field is `Text`. Split each `Dialogue:` line into exactly `format.size` fields by using `limit = format.size`; this keeps commas inside the `Text` field intact.
- Inside the `Text` field:
  - Preserve `{...}` override blocks exactly.
  - Preserve `\N`, `\n`, and `\h` outside override blocks exactly.
  - Translate visible text outside override blocks only while drawing mode is off.
  - Update drawing mode when an override block contains `\pN`; `N > 0` means following plain payload is vector drawing data until a later `\p0`.
  - Preserve drawing payload exactly.
  - Preserve karaoke tags exactly. Text following `\k`, `\K`, `\kf`, `\ko`, or `\kt` remains visible text and is still translatable.
  - Preserve unknown tags and unrecognized override-block content exactly.

## Tasks

### Task 1: Extract Existing SRT/VTT Parser

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/TimedTextDocument.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/TimedTextDocumentTest.kt`

- [ ] **Step 1: Write the failing parser extraction tests**

Create `app/src/test/java/com/nexio/tv/data/repository/TimedTextDocumentTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TimedTextDocumentTest {
    @Test
    fun parseSrtKeepsTimingLinesAndReplacesCueText() {
        val document = TimedTextDocument.parse(
            raw = """
                1
                00:00:01,000 --> 00:00:02,500
                Hello
                world
            """.trimIndent(),
            url = "https://example.test/subtitle.srt"
        )

        assertNotNull(document)
        val parsed = document!!
        assertEquals("srt", parsed.extension)
        assertEquals(1, parsed.translatableBlocks.size)
        assertEquals("Hello\nworld", parsed.translatableBlocks.single().text)
        assertEquals(
            """
            1
            00:00:01,000 --> 00:00:02,500
            Hallo
            wereld
            """.trimIndent() + "\n",
            parsed.render(mapOf(0 to "Hallo\nwereld"))
        )
    }

    @Test
    fun parseVttKeepsHeaderAsPassthroughAndTranslatesCueText() {
        val document = TimedTextDocument.parse(
            raw = """
                WEBVTT

                00:00:01.000 --> 00:00:02.500
                Hello
            """.trimIndent(),
            url = "https://example.test/subtitle.vtt"
        )

        assertNotNull(document)
        val parsed = document!!
        assertEquals("vtt", parsed.extension)
        assertEquals(1, parsed.translatableBlocks.size)
        assertEquals(
            """
            WEBVTT

            00:00:01.000 --> 00:00:02.500
            Hallo
            """.trimIndent() + "\n",
            parsed.render(mapOf(0 to "Hallo"))
        )
    }
}
```

- [ ] **Step 2: Run the failing parser extraction tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.repository.TimedTextDocumentTest
```

Expected: FAIL with unresolved reference errors for `TimedTextDocument`, because it is still private inside `SubtitleTranslationService.kt`.

- [ ] **Step 3: Create the extracted timed-text document file**

Create `app/src/main/java/com/nexio/tv/data/repository/TimedTextDocument.kt` by moving the existing private timed-text classes from `SubtitleTranslationService.kt` into this file and making them `internal`. The file content must include this public surface exactly:

```kotlin
package com.nexio.tv.data.repository

internal enum class TimedTextFormat(val extension: String) {
    SRT("srt"),
    VTT("vtt"),
    ASS("ass"),
    SSA("ssa")
}

internal sealed class TimedTextBlock {
    abstract fun render(translations: Map<Int, String>): String
}

internal data class PassthroughTimedTextBlock(
    private val lines: List<String>
) : TimedTextBlock() {
    override fun render(translations: Map<Int, String>): String = lines.joinToString("\n")
}

internal data class TranslatableTimedTextBlock(
    val blockId: Int,
    val prefixLines: List<String>,
    val text: String
) : TimedTextBlock() {
    override fun render(translations: Map<Int, String>): String {
        val translatedText = translations[blockId]?.trim().takeUnless { it.isNullOrBlank() } ?: text
        return (prefixLines + translatedText.split('\n')).joinToString("\n")
    }
}

internal data class TimedTextDocument(
    val format: TimedTextFormat,
    val blocks: List<TimedTextBlock>
) {
    val extension: String
        get() = format.extension

    val translatableBlocks: List<TranslatableTimedTextBlock>
        get() = blocks.filterIsInstance<TranslatableTimedTextBlock>()

    fun render(translations: Map<Int, String>): String {
        return blocks.joinToString("\n\n") { it.render(translations) }.trim() + "\n"
    }

    companion object {
        fun parse(raw: String, url: String): TimedTextDocument? {
            val normalized = raw
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
            if (normalized.isBlank()) return null

            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return when {
                path.endsWith(".ass") -> null
                path.endsWith(".ssa") -> null
                normalized.startsWith("WEBVTT", ignoreCase = true) || path.endsWith(".vtt") -> parseVtt(normalized)
                path.endsWith(".srt") -> parseSrt(normalized)
                else -> {
                    val looksLikeSrt = normalized.lineSequence().any { it.contains("-->") }
                    if (looksLikeSrt) parseSrt(normalized) else null
                }
            }
        }

        private fun parseSrt(raw: String): TimedTextDocument {
            val blocks = raw.split(Regex("\n{2,}"))
            val parsed = mutableListOf<TimedTextBlock>()
            var nextBlockId = 0
            for (block in blocks) {
                val lines = block.lines().filterNot { it.isEmpty() }
                val timestampIndex = lines.indexOfFirst { it.contains("-->") }
                if (timestampIndex >= 0 && timestampIndex < lines.lastIndex) {
                    parsed += TranslatableTimedTextBlock(
                        blockId = nextBlockId++,
                        prefixLines = lines.take(timestampIndex + 1),
                        text = lines.drop(timestampIndex + 1).joinToString("\n")
                    )
                } else {
                    parsed += PassthroughTimedTextBlock(lines)
                }
            }
            return TimedTextDocument(TimedTextFormat.SRT, parsed)
        }

        private fun parseVtt(raw: String): TimedTextDocument {
            val blocks = raw.split(Regex("\n{2,}"))
            val parsed = mutableListOf<TimedTextBlock>()
            var nextBlockId = 0
            for (block in blocks) {
                val lines = block.lines().filterNot { it.isEmpty() }
                val timestampIndex = lines.indexOfFirst { it.contains("-->") }
                if (timestampIndex >= 0 && timestampIndex < lines.lastIndex) {
                    parsed += TranslatableTimedTextBlock(
                        blockId = nextBlockId++,
                        prefixLines = lines.take(timestampIndex + 1),
                        text = lines.drop(timestampIndex + 1).joinToString("\n")
                    )
                } else {
                    parsed += PassthroughTimedTextBlock(lines)
                }
            }
            return TimedTextDocument(TimedTextFormat.VTT, parsed)
        }
    }
}
```

- [ ] **Step 4: Remove the duplicate private timed-text classes from the service**

Edit `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt` and delete the private declarations for:

```kotlin
private enum class TimedTextFormat
private sealed class TimedTextBlock
private data class PassthroughTimedTextBlock
private data class TranslatableTimedTextBlock
private data class TimedTextDocument
```

Keep these compatibility typealiases at the bottom of `SubtitleTranslationService.kt`:

```kotlin
typealias GeminiSubtitleTranslationService = SubtitleTranslationService
typealias GeminiTranslatedSubtitleAsset = TranslatedSubtitleAsset
typealias GeminiTranslationChunkConfig = SubtitleTranslationChunkConfig
```

- [ ] **Step 5: Run the parser extraction tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.repository.TimedTextDocumentTest
```

Expected: PASS.

- [ ] **Step 6: Commit the extraction**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TimedTextDocument.kt app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt app/src/test/java/com/nexio/tv/data/repository/TimedTextDocumentTest.kt
git commit -m "refactor: extract timed text translation parser"
```

### Task 2: Add ASS/SSA Dialogue Text Parser

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTimedTextDocument.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaTimedTextDocumentTest.kt`

- [ ] **Step 1: Write failing ASS/SSA parser tests**

Create `app/src/test/java/com/nexio/tv/data/repository/AssSsaTimedTextDocumentTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AssSsaTimedTextDocumentTest {
    @Test
    fun assDialogueTranslatesVisibleTextAndPreservesOverrideBlocks() {
        val document = TimedTextDocument.parse(
            raw = """
                [Script Info]
                ScriptType: v4.00+

                [V4+ Styles]
                Format: Name, Fontname, Fontsize
                Style: Default,Arial,20

                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\an8}Hello {\i1}world{\i0}\NNext line
            """.trimIndent(),
            url = "https://example.test/subtitle.ass"
        )

        assertNotNull(document)
        val parsed = document!!
        assertEquals("ass", parsed.extension)
        assertEquals(listOf("Hello ", "world", "Next line"), parsed.translatableBlocks.map { it.text })
        assertEquals(
            """
            [Script Info]
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,20

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\an8}Hallo {\i1}wereld{\i0}\NVolgende regel
            """.trimIndent() + "\n",
            parsed.render(
                mapOf(
                    0 to "Hallo ",
                    1 to "wereld",
                    2 to "Volgende regel"
                )
            )
        )
    }

    @Test
    fun drawingPayloadIsPreservedUntilPZero() {
        val document = TimedTextDocument.parse(
            raw = """
                [Events]
                Format: Start, End, Text
                Dialogue: 0:00:01.00,0:00:02.00,{\p1}m 0 0 l 100 0 100 100 0 100{\p0}Square
            """.trimIndent(),
            url = "file:///tmp/subtitle.ass"
        )

        assertNotNull(document)
        val parsed = document!!
        assertEquals(listOf("Square"), parsed.translatableBlocks.map { it.text })
        assertEquals(
            """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:02.00,{\p1}m 0 0 l 100 0 100 100 0 100{\p0}Vierkant
            """.trimIndent() + "\n",
            parsed.render(mapOf(0 to "Vierkant"))
        )
    }

    @Test
    fun commasInsideTextFieldStayInsideTextField() {
        val document = TimedTextDocument.parse(
            raw = """
                [Events]
                Format: Start, End, Text
                Dialogue: 0:00:01.00,0:00:02.00,Hello, world, again
            """.trimIndent(),
            url = "file:///tmp/subtitle.ssa"
        )

        assertNotNull(document)
        val parsed = document!!
        assertEquals("ssa", parsed.extension)
        assertEquals(listOf("Hello, world, again"), parsed.translatableBlocks.map { it.text })
        assertEquals(
            """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:02.00,Hallo, wereld, opnieuw
            """.trimIndent() + "\n",
            parsed.render(mapOf(0 to "Hallo, wereld, opnieuw"))
        )
    }

    @Test
    fun karaokeTagsStayUntouchedAndSyllableTextTranslates() {
        val document = TimedTextDocument.parse(
            raw = """
                [Events]
                Format: Start, End, Text
                Dialogue: 0:00:01.00,0:00:02.00,{\k20}Good {\K30}morning
            """.trimIndent(),
            url = "file:///tmp/subtitle.ass"
        )

        assertNotNull(document)
        val parsed = document!!
        assertEquals(listOf("Good ", "morning"), parsed.translatableBlocks.map { it.text })
        assertEquals(
            """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:02.00,{\k20}Goed {\K30}morgen
            """.trimIndent() + "\n",
            parsed.render(mapOf(0 to "Goed ", 1 to "morgen"))
        )
    }

    @Test
    fun unknownOverrideContentIsPreserved() {
        val document = TimedTextDocument.parse(
            raw = """
                [Events]
                Format: Start, End, Text
                Dialogue: 0:00:01.00,0:00:02.00,{\i1 some comment}Hello
            """.trimIndent(),
            url = "file:///tmp/subtitle.ass"
        )

        assertNotNull(document)
        val parsed = document!!
        assertEquals(listOf("Hello"), parsed.translatableBlocks.map { it.text })
        assertEquals(
            """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:02.00,{\i1 some comment}Hallo
            """.trimIndent() + "\n",
            parsed.render(mapOf(0 to "Hallo"))
        )
    }
}
```

- [ ] **Step 2: Run the failing ASS/SSA parser tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaTimedTextDocumentTest
```

Expected: FAIL because `.ass` and `.ssa` dispatch currently returns null.

- [ ] **Step 3: Enable ASS/SSA parser dispatch**

Edit `app/src/main/java/com/nexio/tv/data/repository/TimedTextDocument.kt` so the ASS/SSA branches call the parser that this task adds:

```kotlin
val path = url.substringBefore('?').substringBefore('#').lowercase()
return when {
    path.endsWith(".ass") -> parseAssSsaTimedTextDocument(normalized, TimedTextFormat.ASS)
    path.endsWith(".ssa") -> parseAssSsaTimedTextDocument(normalized, TimedTextFormat.SSA)
    normalized.startsWith("WEBVTT", ignoreCase = true) || path.endsWith(".vtt") -> parseVtt(normalized)
    path.endsWith(".srt") -> parseSrt(normalized)
    else -> {
        val looksLikeSrt = normalized.lineSequence().any { it.contains("-->") }
        if (looksLikeSrt) parseSrt(normalized) else null
    }
}
```

- [ ] **Step 4: Add ASS/SSA parser data blocks**

Create `app/src/main/java/com/nexio/tv/data/repository/AssSsaTimedTextDocument.kt` with these data blocks and parser entrypoint:

```kotlin
package com.nexio.tv.data.repository

private const val EVENTS_SECTION = "[Events]"

internal fun parseAssSsaTimedTextDocument(
    raw: String,
    format: TimedTextFormat
): TimedTextDocument {
    val lines = raw.lines()
    val parsed = mutableListOf<TimedTextBlock>()
    var inEventsSection = false
    var eventFormat: List<String> = emptyList()
    var textFieldIndex = -1
    var nextBlockId = 0

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            inEventsSection = trimmed.equals(EVENTS_SECTION, ignoreCase = true)
            parsed += AssSsaPassthroughLineBlock(line)
            continue
        }

        if (inEventsSection && trimmed.startsWith("Format:", ignoreCase = true)) {
            eventFormat = line.substringAfter(":", "").split(',').map { it.trim() }
            textFieldIndex = eventFormat.indexOfFirst { it.equals("Text", ignoreCase = true) }
            parsed += AssSsaPassthroughLineBlock(line)
            continue
        }

        if (inEventsSection && trimmed.startsWith("Dialogue:", ignoreCase = true) && textFieldIndex >= 0) {
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

        parsed += AssSsaPassthroughLineBlock(line)
    }

    return TimedTextDocument(format, parsed)
}

private data class AssSsaParseResult(
    val block: AssSsaDialogueBlock,
    val nextBlockId: Int
)

private data class AssSsaPassthroughLineBlock(
    private val line: String
) : TimedTextBlock() {
    override fun render(translations: Map<Int, String>): String = line
}

private data class AssSsaDialogueBlock(
    private val prefix: String,
    private val fieldsBeforeText: List<String>,
    private val textSegments: List<AssSsaTextSegment>
) : TimedTextBlock() {
    override fun render(translations: Map<Int, String>): String {
        return prefix + fieldsBeforeText.joinToString(",") + "," + textSegments.joinToString("") { segment ->
            segment.render(translations)
        }
    }
}

private sealed interface AssSsaTextSegment {
    fun render(translations: Map<Int, String>): String
}

private data class LiteralAssSsaTextSegment(
    private val raw: String
) : AssSsaTextSegment {
    override fun render(translations: Map<Int, String>): String = raw
}

private data class TranslatableAssSsaTextSegment(
    private val blockId: Int,
    private val fallback: String
) : AssSsaTextSegment {
    override fun render(translations: Map<Int, String>): String {
        return translations[blockId]?.takeIf { it.isNotBlank() } ?: fallback
    }
}
```

- [ ] **Step 5: Add ASS/SSA dialogue line splitting**

Append this function to `AssSsaTimedTextDocument.kt`:

```kotlin
private fun parseAssSsaDialogueLine(
    line: String,
    eventFieldCount: Int,
    textFieldIndex: Int,
    nextBlockId: Int
): AssSsaParseResult? {
    val prefixEnd = line.indexOf(':')
    if (prefixEnd < 0) return null
    var payloadStart = prefixEnd + 1
    while (payloadStart < line.length && line[payloadStart].isWhitespace()) {
        payloadStart += 1
    }
    val prefix = line.take(payloadStart)
    val payload = line.drop(payloadStart)
    val fields = payload.split(',', limit = eventFieldCount)
    if (fields.size <= textFieldIndex) return null

    val beforeText = fields.take(textFieldIndex)
    val text = fields[textFieldIndex]
    val tokenized = tokenizeAssSsaDialogueText(text, nextBlockId)
    return AssSsaParseResult(
        block = AssSsaDialogueBlock(
            prefix = prefix,
            fieldsBeforeText = beforeText,
            textSegments = tokenized.segments
        ),
        nextBlockId = tokenized.nextBlockId
    )
}

private data class AssSsaTokenizeResult(
    val segments: List<AssSsaTextSegment>,
    val nextBlockId: Int
)
```

- [ ] **Step 6: Add the ASS/SSA dialogue text tokenizer**

Append this tokenizer to `AssSsaTimedTextDocument.kt`:

```kotlin
private fun tokenizeAssSsaDialogueText(
    text: String,
    firstBlockId: Int
): AssSsaTokenizeResult {
    val segments = mutableListOf<AssSsaTextSegment>()
    var drawingMode = 0
    var blockId = firstBlockId
    var index = 0

    fun addPlain(raw: String) {
        if (raw.isEmpty()) return
        if (drawingMode > 0) {
            segments += LiteralAssSsaTextSegment(raw)
            return
        }

        var cursor = 0
        val escapeRegex = Regex("""\\[Nnh]""")
        for (match in escapeRegex.findAll(raw)) {
            if (match.range.first > cursor) {
                val visible = raw.substring(cursor, match.range.first)
                if (visible.isNotEmpty()) {
                    segments += TranslatableAssSsaTextSegment(blockId++, visible)
                }
            }
            segments += LiteralAssSsaTextSegment(match.value)
            cursor = match.range.last + 1
        }
        if (cursor < raw.length) {
            val visible = raw.substring(cursor)
            if (visible.isNotEmpty()) {
                segments += TranslatableAssSsaTextSegment(blockId++, visible)
            }
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
            addPlain(text.substring(blockStart))
            break
        }
        val block = text.substring(blockStart, blockEnd + 1)
        drawingMode = drawingModeAfterOverrideBlock(block, drawingMode)
        segments += LiteralAssSsaTextSegment(block)
        index = blockEnd + 1
    }

    return AssSsaTokenizeResult(segments, blockId)
}

private fun drawingModeAfterOverrideBlock(block: String, current: Int): Int {
    var drawingMode = current
    val inner = block.removePrefix("{").removeSuffix("}")
    var index = 0
    while (index < inner.length) {
        val slash = inner.indexOf('\\', startIndex = index)
        if (slash < 0 || slash == inner.lastIndex) break
        val nameStart = slash + 1
        var nameEnd = nameStart
        while (nameEnd < inner.length && inner[nameEnd].isLetterOrDigit()) {
            nameEnd += 1
        }
        val name = inner.substring(nameStart, nameEnd)
        if (name == "p") {
            var argEnd = nameEnd
            while (argEnd < inner.length && inner[argEnd] != '\\') {
                argEnd += 1
            }
            val value = inner.substring(nameEnd, argEnd).trim().toIntOrNull()
            if (value != null) {
                drawingMode = value
            }
            index = argEnd
        } else {
            index = nameEnd
        }
    }
    return drawingMode
}
```

- [ ] **Step 7: Add translatable block exposure for ASS/SSA segments**

Change `TimedTextDocument.translatableBlocks` in `TimedTextDocument.kt` to collect both regular `TranslatableTimedTextBlock` and ASS/SSA `TranslatableAssSsaTextSegment` blocks. Use this shape:

```kotlin
val translatableBlocks: List<TranslatableTimedTextBlock>
    get() = blocks.flatMap { block ->
        when (block) {
            is TranslatableTimedTextBlock -> listOf(block)
            is AssSsaDialogueBlock -> block.translatableBlocks()
            else -> emptyList()
        }
    }
```

Then change `AssSsaTextSegment`, `AssSsaDialogueBlock`, and `TranslatableAssSsaTextSegment` visibility in `AssSsaTimedTextDocument.kt` from `private` to `internal`, and add:

```kotlin
internal sealed interface AssSsaTextSegment {
    fun render(translations: Map<Int, String>): String
}

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
        return prefix + fieldsBeforeText.joinToString(",") + "," + textSegments.joinToString("") { segment ->
            segment.render(translations)
        }
    }
}

internal data class TranslatableAssSsaTextSegment(
    val blockId: Int,
    val fallback: String
) : AssSsaTextSegment {
    override fun render(translations: Map<Int, String>): String {
        return translations[blockId]?.takeIf { it.isNotBlank() } ?: fallback
    }
}
```

- [ ] **Step 8: Run the ASS/SSA parser tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaTimedTextDocumentTest
```

Expected: PASS.

- [ ] **Step 9: Commit the parser**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TimedTextDocument.kt app/src/main/java/com/nexio/tv/data/repository/AssSsaTimedTextDocument.kt app/src/test/java/com/nexio/tv/data/repository/AssSsaTimedTextDocumentTest.kt
git commit -m "feat: parse ass ssa text for translation"
```

### Task 3: Wire ASS/SSA Through Auto-Translate

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAiSubtitles.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAddonSubtitleOverlayTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaTimedTextDocumentTest.kt`

- [ ] **Step 1: Write the failing player support test**

Append this test to `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAddonSubtitleOverlayTest.kt`:

```kotlin
@Test
fun `ai translation accepts ass and ssa but overlay still rejects them`() {
    assertTrue(subtitleSupportsAiTranslationForTest("https://example.test/subtitle.ass"))
    assertTrue(subtitleSupportsAiTranslationForTest("https://example.test/subtitle.ssa"))
    assertFalse(addonSubtitleSupportsOverlay(MimeTypes.TEXT_SSA))
}
```

Because `supportsAiTranslation` is currently private, add this internal test seam near it in `PlayerRuntimeControllerAiSubtitles.kt`:

```kotlin
internal fun subtitleSupportsAiTranslationForTest(url: String): Boolean {
    return supportsAiTranslation(Subtitle(id = "test", url = url, lang = "en", addonName = "test", addonLogo = null))
}
```

This test seam must stay package-internal and has no production call sites.

- [ ] **Step 2: Run the failing player support test**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerAddonSubtitleOverlayTest
```

Expected: FAIL because `supportsAiTranslation` does not include `MimeTypes.TEXT_SSA`.

- [ ] **Step 3: Add ASS/SSA to AI translation format support**

Edit `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAiSubtitles.kt`:

```kotlin
private fun supportsAiTranslation(subtitle: Subtitle): Boolean {
    return when (PlayerSubtitleUtils.mimeTypeFromUrl(subtitle.url)) {
        MimeTypes.APPLICATION_SUBRIP,
        MimeTypes.TEXT_VTT,
        MimeTypes.TEXT_SSA -> true
        else -> false
    }
}
```

Keep `addonSubtitleSupportsOverlay` in `PlayerRuntimeControllerTrackSelection.kt` as:

```kotlin
internal fun addonSubtitleSupportsOverlay(mimeType: String): Boolean {
    return mimeType == MimeTypes.APPLICATION_SUBRIP || mimeType == MimeTypes.TEXT_VTT
}
```

- [ ] **Step 4: Run the player support test**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerAddonSubtitleOverlayTest
```

Expected: PASS.

- [ ] **Step 5: Run the service parser tests together**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.repository.TimedTextDocumentTest --tests com.nexio.tv.data.repository.AssSsaTimedTextDocumentTest --tests com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest
```

Expected: PASS.

- [ ] **Step 6: Commit the wiring**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAiSubtitles.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAddonSubtitleOverlayTest.kt
git commit -m "feat: enable ass ssa subtitle translation"
```

### Task 4: Update User Docs

**Files:**
- Modify: `docs-site/playback/subtitles-and-auto-translate.md`
- Modify: `docs-site/features/index.md`
- Modify: `docs-site/troubleshooting/index.md`

- [ ] **Step 1: Update playback docs**

Edit `docs-site/playback/subtitles-and-auto-translate.md` so the format bullets read:

```markdown
- Text-based subtitle formats such as SRT, VTT, ASS, and SSA are supported.
- ASS/SSA translation preserves styling and drawing markup, but only visible dialogue text is sent to the provider.
- Bitmap or image-based built-in subtitles are not supported for translation.
```

Also change the troubleshooting bullet from:

```markdown
- Use a text subtitle track such as SRT or VTT. Bitmap subtitle tracks cannot be translated.
```

to:

```markdown
- Use a text subtitle track such as SRT, VTT, ASS, or SSA. Bitmap subtitle tracks cannot be translated.
```

- [ ] **Step 2: Update feature docs**

In `docs-site/features/index.md`, change the subtitle translation list item to:

```markdown
- **Subtitle Translation** with OpenAI-compatible, Anthropic-compatible, Google Gemini, or Alibaba DashScope providers for SRT, VTT, ASS, and SSA text subtitles
```

- [ ] **Step 3: Update troubleshooting docs**

In `docs-site/troubleshooting/index.md`, change the subtitle translation troubleshooting sentence to mention:

```markdown
choose a text subtitle track such as SRT, VTT, ASS, or SSA instead of a bitmap track.
```

- [ ] **Step 4: Run docs link check smoke test**

Run:

```bash
npm --prefix docs-site run build
```

Expected: PASS with the docs site build completed.

- [ ] **Step 5: Commit the docs**

```bash
git add docs-site/playback/subtitles-and-auto-translate.md docs-site/features/index.md docs-site/troubleshooting/index.md
git commit -m "docs: document ass ssa subtitle translation"
```

### Task 5: Final Verification

**Files:**
- Verify: `app/src/main/java/com/nexio/tv/data/repository/TimedTextDocument.kt`
- Verify: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTimedTextDocument.kt`
- Verify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`
- Verify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAiSubtitles.kt`
- Verify: `docs-site/playback/subtitles-and-auto-translate.md`

- [ ] **Step 1: Run focused unit tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.repository.TimedTextDocumentTest --tests com.nexio.tv.data.repository.AssSsaTimedTextDocumentTest --tests com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerAddonSubtitleOverlayTest
```

Expected: PASS.

- [ ] **Step 2: Run existing subtitle-adjacent tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.repository.SubtitleTranslationProviderRequestsTest --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerBuiltInAiGroundworkTest
```

Expected: PASS.

- [ ] **Step 3: Check worktree diff**

Run:

```bash
git diff --stat
```

Expected: changes are limited to the parser/service/player translation/docs files named in this plan.

- [ ] **Step 4: Commit final verification fixes if needed**

If Step 1 or Step 2 exposed a compile or assertion failure and a fix was applied, commit only those touched files:

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TimedTextDocument.kt app/src/main/java/com/nexio/tv/data/repository/AssSsaTimedTextDocument.kt app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAiSubtitles.kt app/src/test/java/com/nexio/tv/data/repository/TimedTextDocumentTest.kt app/src/test/java/com/nexio/tv/data/repository/AssSsaTimedTextDocumentTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAddonSubtitleOverlayTest.kt docs-site/playback/subtitles-and-auto-translate.md docs-site/features/index.md docs-site/troubleshooting/index.md
git commit -m "fix: stabilize ass ssa subtitle translation"
```

Expected: no commit is created if Steps 1 and 2 already passed without additional edits.

## Self-Review

- Spec coverage: The plan covers ASS/SSA format detection, dialogue `Text` field parsing, override-block preservation, `\N`/`\n`/`\h` preservation, drawing-mode skipping, karaoke text translation, unknown override preservation, translated-file reconstruction, and docs updates. It deliberately keeps renderer validation out of scope because translation only needs a lossless text transform and Media3/libass already render the resulting ASS/SSA file.
- Placeholder scan: No placeholder tasks remain; every task names exact files, test code, commands, expected outcomes, and commit commands.
- Type consistency: `TimedTextDocument`, `TranslatableTimedTextBlock`, `AssSsaDialogueBlock`, and `TranslatableAssSsaTextSegment` names are consistent across task steps. `MimeTypes.TEXT_SSA` is the existing project MIME identifier for both `.ass` and `.ssa`.
