# ASS/SSA Lossless Parser Translation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a lossless ASS/SSA event text parser that extracts only visible translatable text and reinserts translated text without losing or regenerating ASS formatting for dialogue or signs.

**Architecture:** Replace the current broad sign-preserve shortcut with a structural parser/reconstruction layer. The parser stores all ASS syntax as raw immutable tokens, tracks tag state such as drawing mode, treats text spans as the only mutable content, and reconstructs from original raw tokens after validated translation. Dialogue and signs use the same parser; high-risk non-language spans such as vector drawings are preserved but do not block surrounding visible text.

**Tech Stack:** Kotlin, Android Media3/ExoPlayer, libass via `assrender`, Kotlin coroutines, JUnit/Robolectric, existing `AssSsaEventRecord`, `AssSsaTextTokenizer`, `AssSsaProtectedTranslationUnit`, and `AssSsaTranslatingSampleSink`. Reference behavior is based on Aegisub ASS override tag docs: https://aegisub.org/docs/latest/ass_tags/

---

## File Structure

- Create: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAst.kt`
  - Defines the lossless token/span model used to preserve raw ASS syntax and replace text only.
- Create: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAstParser.kt`
  - Parses ASS event `Text` field into raw override blocks, special escapes, drawing spans, and translatable text spans.
- Create: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAstTranslation.kt`
  - Builds placeholder payloads and reconstructs translated ASS text from the AST.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/AssSsaProtectedTranslation.kt`
  - Uses the AST translation unit or delegates to it while preserving the existing public shape used by services.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt`
  - Removes broad sign-preserve behavior and uses the new AST parser for every dialogue/sign event.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`
  - Keeps current provider contract but accepts richer sign-safe protected units.
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaTextAstParserTest.kt`
  - Unit tests parser state and raw preservation.
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaTextAstTranslationTest.kt`
  - Unit tests extraction/reinsertion for dialogue, signs, line breaks, drawing mode, clips, transforms, karaoke.
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt`
  - Integration tests the sink translates sign-like samples through protected translation instead of preserving all signs.
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaLocalFixtureRegressionTest.kt`
  - Regression tests representative lines from `~/Downloads/ass`.

---

### Task 1: Define Lossless ASS Text AST

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAst.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaTextAstParserTest.kt`

- [ ] **Step 1: Write failing AST smoke test**

Create `app/src/test/java/com/nexio/tv/data/repository/AssSsaTextAstParserTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssSsaTextAstParserTest {
    @Test
    fun parsesPlainTextAsOneTranslatableSpan() {
        val ast = AssSsaTextAst.parse("Hello world")

        assertEquals("Hello world", ast.raw)
        assertEquals("Hello world", ast.render())
        assertEquals(listOf("Hello world"), ast.translatableSpans().map { it.raw })
    }

    @Test
    fun parserKeepsStableSpanIds() {
        val ast = AssSsaTextAst.parse("Hello world")

        assertEquals("txt_000", ast.translatableSpans().single().id)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaTextAstParserTest
```

Expected: FAIL with unresolved reference `AssSsaTextAst`.

- [ ] **Step 3: Add minimal AST model**

Create `app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAst.kt`:

```kotlin
package com.nexio.tv.data.repository

internal data class AssSsaTextAst(
    val raw: String,
    val nodes: List<AssSsaTextNode>
) {
    fun render(): String = nodes.joinToString("") { it.raw }

    fun translatableSpans(): List<AssSsaTextNode.TextSpan> {
        return nodes.filterIsInstance<AssSsaTextNode.TextSpan>()
    }

    companion object {
        fun parse(raw: String): AssSsaTextAst {
            return AssSsaTextAst(
                raw = raw,
                nodes = if (raw.isEmpty()) emptyList() else listOf(
                    AssSsaTextNode.TextSpan(id = "txt_000", raw = raw)
                )
            )
        }
    }
}

internal sealed interface AssSsaTextNode {
    val raw: String

    data class TextSpan(
        val id: String,
        override val raw: String
    ) : AssSsaTextNode

    data class OverrideBlock(
        override val raw: String,
        val tags: List<AssSsaOverrideTag> = emptyList()
    ) : AssSsaTextNode

    data class LineBreak(override val raw: String) : AssSsaTextNode
    data class HardSpace(override val raw: String) : AssSsaTextNode
    data class DrawingSpan(override val raw: String) : AssSsaTextNode
    data class Malformed(override val raw: String) : AssSsaTextNode
}

internal data class AssSsaOverrideTag(
    val name: String,
    val raw: String,
    val arguments: String?
)
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaTextAstParserTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAst.kt app/src/test/java/com/nexio/tv/data/repository/AssSsaTextAstParserTest.kt
git commit -m "feat: add ASS text AST model"
```

---

### Task 2: Parse Override Blocks And Special Escapes Losslessly

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAstParser.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAst.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaTextAstParserTest.kt`

- [ ] **Step 1: Add failing parser tests**

Append to `AssSsaTextAstParserTest`:

```kotlin
@Test
fun parsesOverrideBlocksAndTextSpansWithoutChangingRawText() {
    val source = "{\\an8\\pos(320,40)}I am {\\i1}not{\\i0} amused."

    val ast = AssSsaTextAst.parse(source)

    assertEquals(source, ast.render())
    assertEquals(
        listOf("I am ", "not", " amused."),
        ast.translatableSpans().map { it.raw }
    )
    assertEquals(
        listOf("{\\an8\\pos(320,40)}", "{\\i1}", "{\\i0}"),
        ast.nodes.filterIsInstance<AssSsaTextNode.OverrideBlock>().map { it.raw }
    )
}

@Test
fun parsesLineBreaksAndHardSpacesAsStructuralTokens() {
    val source = "Hello\\Nworld\\hagain\\nsoft"

    val ast = AssSsaTextAst.parse(source)

    assertEquals(source, ast.render())
    assertEquals(listOf("Hello", "world", "again", "soft"), ast.translatableSpans().map { it.raw })
    assertEquals(
        listOf("\\N", "\\h", "\\n"),
        ast.nodes.filterNot { it is AssSsaTextNode.TextSpan }.map { it.raw }
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaTextAstParserTest
```

Expected: FAIL because `AssSsaTextAst.parse` treats the whole string as one span.

- [ ] **Step 3: Move parsing into parser object**

Create `app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAstParser.kt`:

```kotlin
package com.nexio.tv.data.repository

internal object AssSsaTextAstParser {
    fun parse(raw: String): AssSsaTextAst {
        val nodes = mutableListOf<AssSsaTextNode>()
        var textSpanIndex = 0
        var index = 0

        fun nextTextId(): String = "txt_${(textSpanIndex++).toString().padStart(3, '0')}"

        fun addTextSpan(text: String) {
            if (text.isNotEmpty()) {
                nodes += AssSsaTextNode.TextSpan(id = nextTextId(), raw = text)
            }
        }

        fun addPlain(rawText: String) {
            var cursor = 0
            val escapeRegex = Regex("""\\[Nnh]""")
            escapeRegex.findAll(rawText).forEach { match ->
                addTextSpan(rawText.substring(cursor, match.range.first))
                when (match.value) {
                    "\\N", "\\n" -> nodes += AssSsaTextNode.LineBreak(match.value)
                    "\\h" -> nodes += AssSsaTextNode.HardSpace(match.value)
                }
                cursor = match.range.last + 1
            }
            addTextSpan(rawText.substring(cursor))
        }

        while (index < raw.length) {
            val blockStart = raw.indexOf('{', startIndex = index)
            if (blockStart < 0) {
                addPlain(raw.substring(index))
                break
            }
            addPlain(raw.substring(index, blockStart))
            val blockEnd = raw.indexOf('}', startIndex = blockStart + 1)
            if (blockEnd < 0) {
                nodes += AssSsaTextNode.Malformed(raw.substring(blockStart))
                break
            }
            val block = raw.substring(blockStart, blockEnd + 1)
            nodes += AssSsaTextNode.OverrideBlock(
                raw = block,
                tags = AssSsaOverrideTagParser.parseBlock(block)
            )
            index = blockEnd + 1
        }

        return AssSsaTextAst(raw = raw, nodes = nodes)
    }
}

internal object AssSsaOverrideTagParser {
    fun parseBlock(block: String): List<AssSsaOverrideTag> {
        val inner = block.removePrefix("{").removeSuffix("}")
        val tags = mutableListOf<AssSsaOverrideTag>()
        var index = 0
        while (index < inner.length) {
            val slash = inner.indexOf('\\', startIndex = index)
            if (slash < 0 || slash == inner.lastIndex) break
            val nameStart = slash + 1
            var nameEnd = nameStart
            while (nameEnd < inner.length && inner[nameEnd].isLetterOrDigit()) {
                nameEnd += 1
            }
            if (nameEnd == nameStart) {
                index = slash + 1
                continue
            }
            val name = inner.substring(nameStart, nameEnd)
            val nextSlash = findNextTopLevelSlash(inner, nameEnd)
            val rawTag = inner.substring(slash, nextSlash ?: inner.length)
            val arguments = rawTag.removePrefix("\\$name").takeIf { it.isNotBlank() }
            tags += AssSsaOverrideTag(name = name, raw = rawTag, arguments = arguments)
            index = nextSlash ?: inner.length
        }
        return tags
    }

    private fun findNextTopLevelSlash(value: String, startIndex: Int): Int? {
        var depth = 0
        var index = startIndex
        while (index < value.length) {
            when (value[index]) {
                '(' -> depth += 1
                ')' -> depth = (depth - 1).coerceAtLeast(0)
                '\\' -> if (depth == 0) return index
            }
            index += 1
        }
        return null
    }
}
```

Modify `AssSsaTextAst.parse` in `AssSsaTextAst.kt`:

```kotlin
companion object {
    fun parse(raw: String): AssSsaTextAst = AssSsaTextAstParser.parse(raw)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaTextAstParserTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAst.kt app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAstParser.kt app/src/test/java/com/nexio/tv/data/repository/AssSsaTextAstParserTest.kt
git commit -m "feat: parse ASS text override blocks losslessly"
```

---

### Task 3: Track Drawing Mode And Preserve Vector Data

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAstParser.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaTextAstParserTest.kt`

- [ ] **Step 1: Add failing drawing tests**

Append:

```kotlin
@Test
fun drawingModePlainContentIsNotTranslatableText() {
    val source = "{\\p1}m 0 0 l 100 0 100 100{\\p0}Label"

    val ast = AssSsaTextAst.parse(source)

    assertEquals(source, ast.render())
    assertEquals(listOf("m 0 0 l 100 0 100 100"), ast.nodes.filterIsInstance<AssSsaTextNode.DrawingSpan>().map { it.raw })
    assertEquals(listOf("Label"), ast.translatableSpans().map { it.raw })
}

@Test
fun vectorClipInsideOverrideBlockIsPreservedInsideRawBlock() {
    val source = "{\\clip(m 0 0 l 100 0 100 100)\\pos(40,50)}Cafe"

    val ast = AssSsaTextAst.parse(source)

    assertEquals(source, ast.render())
    assertEquals(listOf("Cafe"), ast.translatableSpans().map { it.raw })
    assertEquals(
        "{\\clip(m 0 0 l 100 0 100 100)\\pos(40,50)}",
        ast.nodes.filterIsInstance<AssSsaTextNode.OverrideBlock>().single().raw
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaTextAstParserTest
```

Expected: FAIL because drawing-mode content is still parsed as `TextSpan`.

- [ ] **Step 3: Implement drawing mode state**

In `AssSsaTextAstParser.parse`, add:

```kotlin
var drawingMode = 0
```

Change `addTextSpan` to:

```kotlin
fun addTextSpan(text: String) {
    if (text.isEmpty()) return
    if (drawingMode > 0) {
        nodes += AssSsaTextNode.DrawingSpan(text)
    } else {
        nodes += AssSsaTextNode.TextSpan(id = nextTextId(), raw = text)
    }
}
```

After parsing an override block and before adding it:

```kotlin
val tags = AssSsaOverrideTagParser.parseBlock(block)
drawingMode = drawingModeAfterTags(tags, drawingMode)
nodes += AssSsaTextNode.OverrideBlock(raw = block, tags = tags)
```

Add this helper to `AssSsaTextAstParser.kt`:

```kotlin
private fun drawingModeAfterTags(tags: List<AssSsaOverrideTag>, current: Int): Int {
    var drawingMode = current
    tags.forEach { tag ->
        if (tag.name.equals("p", ignoreCase = true)) {
            val value = tag.arguments.orEmpty().trim().toIntOrNull()
            if (value != null) {
                drawingMode = value
            }
        }
    }
    return drawingMode
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaTextAstParserTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAstParser.kt app/src/test/java/com/nexio/tv/data/repository/AssSsaTextAstParserTest.kt
git commit -m "feat: preserve ASS drawing mode text"
```

---

### Task 4: Build Placeholder Payloads For Text Spans Only

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAstTranslation.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaTextAstTranslationTest.kt`

- [ ] **Step 1: Write failing extraction/reconstruction tests**

Create `app/src/test/java/com/nexio/tv/data/repository/AssSsaTextAstTranslationTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AssSsaTextAstTranslationTest {
    @Test
    fun buildsPayloadFromTextSpansAndPreservesOverrideBlocks() {
        val unit = AssSsaTextAstTranslationUnit.fromText(
            id = "evt_0",
            text = "{\\bord3\\shad0\\fs14\\pos(475.43,40)}My best friend?!"
        )

        assertEquals("My best friend?!", unit.protectedText)
        assertEquals(
            "{\\bord3\\shad0\\fs14\\pos(475.43,40)}Mijn beste vriend?!",
            unit.reconstruct("Mijn beste vriend?!").getOrThrow()
        )
    }

    @Test
    fun preservesInlineEmphasisPlaceholdersAroundTranslatedText() {
        val unit = AssSsaTextAstTranslationUnit.fromText(
            id = "evt_0",
            text = "I am {\\i1}not{\\i0} angry"
        )

        assertEquals("I am ⟦ASS_000⟧not⟦ASS_001⟧ angry", unit.protectedText)
        assertEquals(
            "Ik ben {\\i1}niet{\\i0} boos",
            unit.reconstruct("Ik ben ⟦ASS_000⟧niet⟦ASS_001⟧ boos").getOrThrow()
        )
    }

    @Test
    fun preservesLineBreakPlaceholders() {
        val unit = AssSsaTextAstTranslationUnit.fromText(
            id = "evt_0",
            text = "Hello\\Nworld"
        )

        assertEquals("Hello⟦LB_000⟧world", unit.protectedText)
        assertEquals("Hallo\\Nwereld", unit.reconstruct("Hallo⟦LB_000⟧wereld").getOrThrow())
    }

    @Test
    fun drawingSpansAreNotSentToTranslationButRemainInReconstruction() {
        val unit = AssSsaTextAstTranslationUnit.fromText(
            id = "evt_0",
            text = "{\\p1}m 0 0 l 100 0{\\p0}Logo"
        )

        assertEquals("Logo", unit.protectedText)
        assertEquals("{\\p1}m 0 0 l 100 0{\\p0}Merk", unit.reconstruct("Merk").getOrThrow())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaTextAstTranslationTest
```

Expected: FAIL with unresolved reference `AssSsaTextAstTranslationUnit`.

- [ ] **Step 3: Add AST translation unit**

Create `app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAstTranslation.kt`:

```kotlin
package com.nexio.tv.data.repository

internal data class AssSsaTextAstPlaceholder(
    val token: String,
    val raw: String,
    val nodeIndex: Int
)

internal data class AssSsaTextAstTranslationUnit(
    val id: String,
    val ast: AssSsaTextAst,
    val protectedText: String,
    val placeholders: List<AssSsaTextAstPlaceholder>
) {
    fun reconstruct(translatedText: String): Result<String> = runCatching {
        placeholders.forEach { placeholder ->
            check(translatedText.contains(placeholder.token)) {
                "Missing placeholder ${placeholder.token}"
            }
        }
        check(!translatedText.contains("{") && !translatedText.contains("}")) {
            "Translated text introduced raw ASS override braces"
        }

        val translatedPieces = splitTranslatedTextByPlaceholders(translatedText)
        val textValues = translatedPieces.textValues.iterator()
        val nodes = ast.nodes.mapIndexed { index, node ->
            when (node) {
                is AssSsaTextNode.TextSpan -> {
                    val next = if (textValues.hasNext()) textValues.next() else node.raw
                    node.copy(raw = next)
                }
                else -> node
            }
        }
        nodes.joinToString("") { node ->
            when (node) {
                is AssSsaTextNode.TextSpan -> node.raw
                else -> node.raw
            }
        }.replacePlaceholders(placeholders)
    }

    private fun splitTranslatedTextByPlaceholders(translatedText: String): TranslatedPieces {
        var working = translatedText
        placeholders.forEach { placeholder ->
            working = working.replace(placeholder.token, "\u0000${placeholder.token}\u0000")
        }
        val textValues = working
            .split(Regex("""\u0000⟦(?:ASS|LB|HS)_\d{3}⟧\u0000"""))
            .filter { it.isNotEmpty() }
        return TranslatedPieces(textValues)
    }

    private fun String.replacePlaceholders(placeholders: List<AssSsaTextAstPlaceholder>): String {
        var result = this
        placeholders.forEach { placeholder ->
            result = result.replace(placeholder.token, placeholder.raw)
        }
        return result
    }

    private data class TranslatedPieces(val textValues: List<String>)

    companion object {
        fun fromText(id: String, text: String): AssSsaTextAstTranslationUnit {
            val ast = AssSsaTextAst.parse(text)
            val placeholders = mutableListOf<AssSsaTextAstPlaceholder>()
            val protected = StringBuilder()
            var placeholderIndex = 0

            ast.nodes.forEachIndexed { nodeIndex, node ->
                when (node) {
                    is AssSsaTextNode.TextSpan -> protected.append(node.raw)
                    is AssSsaTextNode.OverrideBlock -> {
                        if (node.raw.hasLineLevelAssTag() || node.raw.hasDrawingModeTag()) {
                            return@forEachIndexed
                        }
                        val token = "⟦ASS_${placeholderIndex.toString().padStart(3, '0')}⟧"
                        placeholderIndex += 1
                        placeholders += AssSsaTextAstPlaceholder(token, node.raw, nodeIndex)
                        protected.append(token)
                    }
                    is AssSsaTextNode.LineBreak -> {
                        val token = "⟦LB_${placeholderIndex.toString().padStart(3, '0')}⟧"
                        placeholderIndex += 1
                        placeholders += AssSsaTextAstPlaceholder(token, node.raw, nodeIndex)
                        protected.append(token)
                    }
                    is AssSsaTextNode.HardSpace -> {
                        val token = "⟦HS_${placeholderIndex.toString().padStart(3, '0')}⟧"
                        placeholderIndex += 1
                        placeholders += AssSsaTextAstPlaceholder(token, node.raw, nodeIndex)
                        protected.append(token)
                    }
                    is AssSsaTextNode.DrawingSpan,
                    is AssSsaTextNode.Malformed -> Unit
                }
            }

            return AssSsaTextAstTranslationUnit(
                id = id,
                ast = ast,
                protectedText = protected.toString(),
                placeholders = placeholders
            )
        }
    }
}

private fun String.hasLineLevelAssTag(): Boolean {
    return Regex("""\\(?:an\d+|pos\(|move\(|clip\(|iclip\(|org\(|fad\(|fade\()""")
        .containsMatchIn(this)
}

private fun String.hasDrawingModeTag(): Boolean {
    return Regex("""\\p-?\d*""").containsMatchIn(this)
}
```

- [ ] **Step 4: Run test and fix reconstruction splitting if needed**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaTextAstTranslationTest
```

Expected: PASS. If only `buildsPayloadFromTextSpansAndPreservesOverrideBlocks` fails, simplify `reconstruct` by replacing text spans sequentially and then replacing placeholder tokens; do not change parser behavior.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/AssSsaTextAstTranslation.kt app/src/test/java/com/nexio/tv/data/repository/AssSsaTextAstTranslationTest.kt
git commit -m "feat: reconstruct translated ASS text from AST"
```

---

### Task 5: Replace Existing Protected Unit Internals With AST Translation

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/AssSsaProtectedTranslation.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaProtectedTranslationTest.kt`

- [ ] **Step 1: Add failing compatibility tests**

Create or append to `app/src/test/java/com/nexio/tv/data/repository/AssSsaProtectedTranslationTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AssSsaProtectedTranslationTest {
    @Test
    fun signLineWithPositionAndTinyFontIsStillTranslatable() {
        val unit = AssSsaProtectedTranslationUnit.fromText(
            id = "evt_0",
            text = "{\\bord3\\shad0\\fs14\\pos(475.43,40)}My best friend?!"
        )

        assertEquals("My best friend?!", unit.protectedText)
        assertEquals(AssSsaRisk.Normal, unit.risk)
        assertEquals(
            "{\\bord3\\shad0\\fs14\\pos(475.43,40)}Mijn beste vriend?!",
            unit.reconstruct("Mijn beste vriend?!").getOrThrow()
        )
    }

    @Test
    fun vectorDrawingIsPreservedWhileTextAfterDrawingCanTranslate() {
        val unit = AssSsaProtectedTranslationUnit.fromText(
            id = "evt_0",
            text = "{\\p1}m 0 0 l 100 0{\\p0}Logo"
        )

        assertEquals("Logo", unit.protectedText)
        assertEquals(AssSsaRisk.Complex, unit.risk)
        assertEquals("{\\p1}m 0 0 l 100 0{\\p0}Merk", unit.reconstruct("Merk").getOrThrow())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaProtectedTranslationTest
```

Expected: FAIL with unresolved `fromText` or with risk/reconstruction mismatch.

- [ ] **Step 3: Add compatibility constructor and delegate internals**

Modify `AssSsaProtectedTranslationUnit` in `AssSsaProtectedTranslation.kt` to include an optional AST unit:

```kotlin
private val astUnit: AssSsaTextAstTranslationUnit? = null
```

Update `reconstruct` first line:

```kotlin
astUnit?.let { return it.reconstruct(translatedText) }
```

Add to companion object:

```kotlin
fun fromText(id: String, text: String): AssSsaProtectedTranslationUnit {
    val astUnit = AssSsaTextAstTranslationUnit.fromText(id, text)
    val hasDrawing = astUnit.ast.nodes.any { it is AssSsaTextNode.DrawingSpan || it is AssSsaTextNode.Malformed }
    val risk = when {
        astUnit.protectedText.isBlank() -> AssSsaRisk.PreserveOnly
        hasDrawing -> AssSsaRisk.Complex
        astUnit.placeholders.size >= COMPLEX_PLACEHOLDER_COUNT -> AssSsaRisk.Complex
        else -> AssSsaRisk.Normal
    }
    return AssSsaProtectedTranslationUnit(
        id = id,
        originalTokens = AssSsaTextTokenizer.tokenize(text),
        protectedText = astUnit.protectedText,
        placeholders = emptyList(),
        risk = risk,
        astUnit = astUnit
    )
}
```

Change existing `fromTokens` implementation to:

```kotlin
fun fromTokens(id: String, tokens: List<AssSsaTextToken>): AssSsaProtectedTranslationUnit {
    return fromText(id = id, text = tokens.joinToString("") { it.raw })
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaProtectedTranslationTest --tests com.nexio.tv.data.repository.AssSsaTextAstTranslationTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/AssSsaProtectedTranslation.kt app/src/test/java/com/nexio/tv/data/repository/AssSsaProtectedTranslationTest.kt
git commit -m "feat: use AST-backed ASS protected translation"
```

---

### Task 6: Translate Sign Events Through Protected Path In Sample Sink

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt`

- [ ] **Step 1: Replace bad preserve test with sign translation test**

Replace `signLikeSamplesArePreservedInProtectedModeToAvoidTinyDialogue` with:

```kotlin
@Test
fun signLikeSamplesTranslateThroughProtectedPathWithoutLosingFormatting() = runTest {
    val downstream = RecordingAssSsaSampleSink()
    val sample = "Dialogue: 0,0:00:43.77,0:00:45.65,Default,SIGN,0,0,0,,{\\bord3\\shad0\\fs14\\pos(475.43,40)}My best friend?!"
    var protectedProviderCalls = 0
    var rawProviderCalls = 0
    val sink = AssSsaTranslatingSampleSink(
        downstream = downstream,
        scope = CoroutineScope(Dispatchers.Unconfined),
        isEnabled = { true },
        useSystemPromptTranslation = { false },
        translate = { units ->
            protectedProviderCalls += 1
            assertEquals(listOf("My best friend?!"), units.map { it.protectedText })
            mapOf("evt_0" to "Mijn beste vriend?!")
        },
        translateRawAssSsa = {
            rawProviderCalls += 1
            it
        }
    )

    sink.onSubtitleSample(trackId = 4, timeUs = 43_770_000L, data = sample.toByteArray())

    assertEquals(1, protectedProviderCalls)
    assertEquals(0, rawProviderCalls)
    assertEquals(
        "Dialogue: 0,0:00:43.77,0:00:45.65,Default,SIGN,0,0,0,,{\\bord3\\shad0\\fs14\\pos(475.43,40)}Mijn beste vriend?!",
        downstream.samples.single().decodeToString()
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: FAIL because the current sign-like preserve guard prevents provider calls.

- [ ] **Step 3: Remove broad sign preserve guard**

In `AssSsaTranslatingSampleSink.onSubtitleSample`, delete:

```kotlin
if (records.any { it.isSignLikeAssSsaEvent() }) {
    downstream.onSubtitleSample(trackId, timeUs, data)
    return
}
```

Delete the private helper and constants:

```kotlin
private fun AssSsaEventRecord.isSignLikeAssSsaEvent(): Boolean
private val POSITIONED_SIGN_TAG_PATTERN
private val INLINE_FONT_SIZE_PATTERN
private const val SIGN_INLINE_FONT_SIZE_THRESHOLD
```

Change unit creation to use the new AST constructor:

```kotlin
val unitsById = records.mapIndexed { index, record ->
    "evt_$index" to AssSsaProtectedTranslationUnit.fromText(
        id = "evt_$index",
        text = record.text
    )
}
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
git commit -m "fix: translate ASS signs without dropping formatting"
```

---

### Task 7: Add Local Fixture Regression Tests

**Files:**
- Create: `app/src/test/java/com/nexio/tv/data/repository/AssSsaLocalFixtureRegressionTest.kt`

- [ ] **Step 1: Add regression test with representative local lines**

Create:

```kotlin
package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AssSsaLocalFixtureRegressionTest {
    @Test
    fun ganbareDefaultSignLineTranslatesTextOnly() {
        val text = "{\\bord3\\shad0\\fs14\\fax0.12\\c&H353135&\\3c&HF5F5F5&\\frz29.62\\pos(63,80)}My best friend?!"
        val unit = AssSsaProtectedTranslationUnit.fromText("evt_0", text)

        assertEquals("My best friend?!", unit.protectedText)
        assertEquals(
            "{\\bord3\\shad0\\fs14\\fax0.12\\c&H353135&\\3c&HF5F5F5&\\frz29.62\\pos(63,80)}Mijn beste vriend?!",
            unit.reconstruct("Mijn beste vriend?!").getOrThrow()
        )
    }

    @Test
    fun ganbareMovingSignTranslatesVisibleTextOnly() {
        val text = "{\\an8\\fnComic Sans MS\\b1\\fs45\\bord2.5\\shad3\\move(165,182,165,-182)\\3c&H181060&\\4c&H181060&\\c&H3093F2&}Lov{\\c&H55C8F8&}able {\\c&H3093F2&}Lun{\\c&H55C8F8&}ches!"
        val unit = AssSsaProtectedTranslationUnit.fromText("evt_0", text)

        assertEquals("Lov⟦ASS_000⟧able ⟦ASS_001⟧Lun⟦ASS_002⟧ches!", unit.protectedText)
        assertEquals(
            "{\\an8\\fnComic Sans MS\\b1\\fs45\\bord2.5\\shad3\\move(165,182,165,-182)\\3c&H181060&\\4c&H181060&\\c&H3093F2&}Lie{\\c&H55C8F8&}felijke {\\c&H3093F2&}lun{\\c&H55C8F8&}ches!",
            unit.reconstruct("Lie⟦ASS_000⟧felijke ⟦ASS_001⟧lun⟦ASS_002⟧ches!").getOrThrow()
        )
    }

    @Test
    fun classDeSignsStyleTranslatesTextOnly() {
        val text = "{\\pos(312,198.667)}MAEHARA"
        val unit = AssSsaProtectedTranslationUnit.fromText("evt_0", text)

        assertEquals("MAEHARA", unit.protectedText)
        assertEquals("{\\pos(312,198.667)}MAEHARA", unit.reconstruct("MAEHARA").getOrThrow())
    }
}
```

- [ ] **Step 2: Run test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaLocalFixtureRegressionTest
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/data/repository/AssSsaLocalFixtureRegressionTest.kt
git commit -m "test: cover ASS sign translation fixtures"
```

---

### Task 8: Integrate With Async Lookahead Plan

**Files:**
- Modify: `docs/superpowers/plans/2026-04-20-ass-ssa-async-batched-translation.md`

- [ ] **Step 1: Update batching plan to depend on AST parser**

In `docs/superpowers/plans/2026-04-20-ass-ssa-async-batched-translation.md`, add this paragraph under the Architecture header:

```markdown
This plan assumes `2026-04-20-ass-ssa-lossless-parser-translation.md` is implemented first. Batching must call `AssSsaProtectedTranslationUnit.fromText(...)` so signs and dialogue share the same lossless extraction/reinsertion path.
```

- [ ] **Step 2: Run tests touched by both plans**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaTextAstTranslationTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/plans/2026-04-20-ass-ssa-async-batched-translation.md
git commit -m "docs: link ASS batching to lossless parser plan"
```

---

### Task 9: Device Verification

**Files:**
- No code changes.

- [ ] **Step 1: Install debug build**

Run:

```bash
./gradlew :app:installUniversalDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Start logcat filter**

Run:

```bash
adb connect 192.168.50.98:5555
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 logcat -v time | rg --line-buffered "ASS_SSA_RENDER|assrender|AssSsa|Translation|translateProtectedAssSsa|Selecting INTERNAL|TRACKS updated|Legacy decoding|text/x-ssa"
```

Expected:

```text
ASS_SSA_RENDER: FFmpeg startup probe detected embedded ASS/SSA
assrender: Loaded ASS header
Selecting INTERNAL subtitle trackIndex=...
```

- [ ] **Step 3: Test Ganbare local-pattern stream**

Manual steps:

```text
1. Start Ganbare Nakamura-kun episode 04 or equivalent stream.
2. Enable AI translation in native/protected mode.
3. Seek to 00:43 where multiple Default,SIGN lines with \fs14 appear.
4. Watch dialogue and signs.
```

Expected:

```text
- Normal dialogue translates.
- The \fs14 sign lines may translate if provider returns in time.
- Translated signs retain \fs14, \pos, color, border, and rotation.
- No translated dialogue appears as tiny sign text.
- No "Legacy decoding is disabled" crash.
```

- [ ] **Step 4: Test Bakemonogatari stream**

Manual steps:

```text
1. Start https://127-4.download.real-debrid.com/d/OL7FB7VXJFJEC/%5BMTBB%5D%20Bakemonogatari%20-%2001v2%20%5B346DABB1%5D.mkv
2. Select the default English ASS track.
3. Enable native/protected AI translation.
4. Observe normal dialogue and signs for 90 seconds.
```

Expected:

```text
- Dialogue translates through protected mode.
- Positioned/sign text remains formatted and does not shrink unrelated dialogue.
- Missing subtitles are limited to provider latency until async batching plan is implemented.
```

---

## Self-Review

**Spec coverage:** The plan covers a proper parser, lossless formatting preservation, dialogue and sign support, drawing-mode safety, placeholder extraction, text reinsertion, integration into the native translation path, local fixture regressions, and device verification.

**Placeholder scan:** No `TBD`, `TODO`, "add appropriate", "similar to", or unspecified "write tests" instructions remain. Every task includes exact paths, concrete test code, implementation code, commands, and expected outcomes.

**Type consistency:** Types are consistent across tasks: `AssSsaTextAst`, `AssSsaTextNode`, `AssSsaOverrideTag`, `AssSsaTextAstTranslationUnit`, and `AssSsaProtectedTranslationUnit.fromText(...)` are introduced before use in integration tasks.

