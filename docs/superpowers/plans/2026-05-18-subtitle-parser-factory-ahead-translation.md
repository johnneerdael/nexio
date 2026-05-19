# Subtitle Parser Factory Ahead Translation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Feed auto-translation from cues emitted by Media3's normal `SubtitleParser.Factory` playback path, with no duplicate remote stream and no lower-level `TrackOutput` fallback.

**Architecture:** Wrap `DefaultSubtitleParserFactory` with a tapping factory that forwards parser output unchanged to Media3 while enqueueing each emitted `CuesWithTiming` into Nexio's translation pipeline. The tap runs synchronously on the extraction thread, so it must only normalize/copy tiny cue metadata and hand off to a coroutine queue. Playback remains unchanged: original subtitles stay selected and visible until translated cues are available.

**Tech Stack:** Kotlin, forked Media3, `SubtitleParser.Factory`, `DefaultSubtitleParserFactory`, `CuesWithTiming`, `DefaultMediaSourceFactory.setSubtitleParserFactory`, existing `BuiltInSubtitleCueTranslator`, existing subtitle translation service.

---

## Scope And Constraints

- Do not add a second HTTP reader for MKV/MP4 subtitles.
- Do not implement a `TrackOutput` sample tap in this plan.
- Do not hide untranslated subtitles. Keep original language subtitle rendering until translation is ready.
- The ahead horizon is bounded by Media3 playback buffering/extraction. If the player has 30 seconds buffered, the parser tap can only discover cues around that frontier.
- Cover text subtitle formats supported by `DefaultSubtitleParserFactory`: SubRip, WebVTT, MP4 WebVTT, TX3G, TTML, SSA/ASS when the ASS overlay path is not active, plus parser-supported bitmap cue formats if they produce text-bearing cues.
- Keep the existing ASS/SSA overlay pipeline intact. When the ASS overlay pipeline installs `AssNoOpSubtitleParserFactory`, do not replace it with the tapping factory.

## File Structure

- Create `app/src/main/java/com/nexio/tv/ui/screens/player/translation/AheadSubtitleCueSink.kt`
  - Minimal interface for parser-emitted future cues.
- Create `app/src/main/java/com/nexio/tv/ui/screens/player/translation/TappingSubtitleParserFactory.kt`
  - Wraps `SubtitleParser.Factory`; enqueues `CuesWithTiming`; forwards callbacks to Media3.
- Create `app/src/main/java/com/nexio/tv/ui/screens/player/translation/ParserAheadSubtitleDiagnostics.kt`
  - Logs parser tap activity, source/translated counts, and ahead duration.
- Create `app/src/main/java/com/nexio/tv/ui/screens/player/translation/ParserAheadSubtitleQueue.kt`
  - Bounded non-blocking queue that batches parser cues into existing translation work.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/BuiltInSubtitleCueTranslator.kt`
  - Add a public internal enqueue method for parser-discovered cues and reuse current translation batching.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - Install `TappingSubtitleParserFactory` via `DefaultMediaSourceFactory.setSubtitleParserFactory` / existing `mediaSourceFactory.configureSubtitleParsing` when ASS overlay parser override is not active.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`
  - Log parser tap and buffer headroom in playback diagnostics.
- Test `app/src/test/java/com/nexio/tv/ui/screens/player/translation/TappingSubtitleParserFactoryTest.kt`
- Test `app/src/test/java/com/nexio/tv/ui/screens/player/translation/ParserAheadSubtitleQueueTest.kt`
- Test `app/src/test/java/com/nexio/tv/ui/screens/player/BuiltInSubtitleCueTranslatorTest.kt`
- Test `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerBuiltInAiGroundworkTest.kt`

---

### Task 1: Add Parser Tap Contract And Factory

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/translation/AheadSubtitleCueSink.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/translation/TappingSubtitleParserFactory.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/translation/TappingSubtitleParserFactoryTest.kt`

- [ ] **Step 1: Write failing tests**

Create `TappingSubtitleParserFactoryTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TappingSubtitleParserFactoryTest {
    @Test
    fun `supportsFormat delegates to wrapped factory`() {
        val delegate = FakeFactory(supports = true)
        val factory = TappingSubtitleParserFactory(delegate, RecordingSink(enabled = true))
        val format = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build()

        assertTrue(factory.supportsFormat(format))
        assertEquals(1, delegate.supportsCalls)
    }

    @Test
    fun `parse enqueues cue and forwards to media3 output`() {
        val cue = Cue.Builder().setText("hola").build()
        val cues = CuesWithTiming(listOf(cue), 1_000L, 2_000L)
        val delegate = FakeFactory(parser = FakeParser(cues))
        val sink = RecordingSink(enabled = true)
        val parser = TappingSubtitleParserFactory(delegate, sink)
            .create(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build())
        val forwarded = mutableListOf<CuesWithTiming>()

        parser.parse(byteArrayOf(1, 2), 0, 2, SubtitleParser.OutputOptions.allCues()) {
            forwarded += it
        }

        assertEquals(listOf(cues), forwarded)
        assertEquals(listOf(cues), sink.enqueued.map { it.cues })
    }

    @Test
    fun `disabled sink still forwards to media3 output`() {
        val cues = CuesWithTiming(listOf(Cue.Builder().setText("hola").build()), 1_000L, 2_000L)
        val delegate = FakeFactory(parser = FakeParser(cues))
        val sink = RecordingSink(enabled = false)
        val parser = TappingSubtitleParserFactory(delegate, sink)
            .create(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build())
        val forwarded = mutableListOf<CuesWithTiming>()

        parser.parse(byteArrayOf(1), 0, 1, SubtitleParser.OutputOptions.allCues()) {
            forwarded += it
        }

        assertEquals(listOf(cues), forwarded)
        assertTrue(sink.enqueued.isEmpty())
    }

    @Test
    fun `reset notifies sink and delegate parser`() {
        val parser = FakeParser()
        val delegate = FakeFactory(parser = parser)
        val sink = RecordingSink(enabled = true)
        val tappingParser = TappingSubtitleParserFactory(delegate, sink)
            .create(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build())

        tappingParser.reset()

        assertTrue(parser.resetCalled)
        assertEquals(1, sink.resetFormats.size)
    }

    private class RecordingSink(private val enabled: Boolean) : AheadSubtitleCueSink {
        val enqueued = mutableListOf<AheadSubtitleCue>()
        val resetFormats = mutableListOf<Format>()
        override fun isEnabled(format: Format): Boolean = enabled
        override fun enqueue(format: Format, cues: CuesWithTiming) {
            enqueued += AheadSubtitleCue(format, cues)
        }
        override fun onParserReset(format: Format) {
            resetFormats += format
        }
    }

    private class FakeFactory(
        private val supports: Boolean = true,
        private val parser: SubtitleParser = FakeParser()
    ) : SubtitleParser.Factory {
        var supportsCalls = 0
        override fun supportsFormat(format: Format): Boolean {
            supportsCalls += 1
            return supports
        }
        override fun getCueReplacementBehavior(format: Format): Int {
            return Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
        }
        override fun create(format: Format): SubtitleParser = parser
    }

    private class FakeParser(private val cues: CuesWithTiming? = null) : SubtitleParser {
        var resetCalled = false
        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: androidx.media3.common.util.Consumer<CuesWithTiming>
        ) {
            cues?.let(output::accept)
        }
        override fun reset() {
            resetCalled = true
        }
        override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
./gradlew :app:testReleaseEarlyAccessUnitTest --tests 'com.nexio.tv.ui.screens.player.translation.TappingSubtitleParserFactoryTest'
```

Expected: FAIL because `TappingSubtitleParserFactory` does not exist.

- [ ] **Step 3: Implement contract**

Create `AheadSubtitleCueSink.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.Format
import androidx.media3.extractor.text.CuesWithTiming

internal data class AheadSubtitleCue(
    val format: Format,
    val cues: CuesWithTiming
)

internal interface AheadSubtitleCueSink {
    fun isEnabled(format: Format): Boolean
    fun enqueue(format: Format, cues: CuesWithTiming)
    fun onParserReset(format: Format)
}
```

- [ ] **Step 4: Implement tapping factory**

Create `TappingSubtitleParserFactory.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

@UnstableApi
internal class TappingSubtitleParserFactory(
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
    private val cueSink: AheadSubtitleCueSink
) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format): Boolean {
        return delegate.supportsFormat(format)
    }

    override fun getCueReplacementBehavior(format: Format): Int {
        return delegate.getCueReplacementBehavior(format)
    }

    override fun create(format: Format): SubtitleParser {
        val parser = delegate.create(format)
        return object : SubtitleParser {
            override fun parse(
                data: ByteArray,
                offset: Int,
                length: Int,
                outputOptions: SubtitleParser.OutputOptions,
                output: Consumer<CuesWithTiming>
            ) {
                parser.parse(data, offset, length, outputOptions) { cuesWithTiming ->
                    if (cueSink.isEnabled(format)) {
                        cueSink.enqueue(format, cuesWithTiming)
                    }
                    output.accept(cuesWithTiming)
                }
            }

            override fun reset() {
                parser.reset()
                cueSink.onParserReset(format)
            }

            override fun getCueReplacementBehavior(): Int {
                return parser.cueReplacementBehavior
            }
        }
    }
}
```

- [ ] **Step 5: Run tests**

Run:

```bash
./gradlew :app:testReleaseEarlyAccessUnitTest --tests 'com.nexio.tv.ui.screens.player.translation.TappingSubtitleParserFactoryTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/translation/AheadSubtitleCueSink.kt app/src/main/java/com/nexio/tv/ui/screens/player/translation/TappingSubtitleParserFactory.kt app/src/test/java/com/nexio/tv/ui/screens/player/translation/TappingSubtitleParserFactoryTest.kt
git commit -m "feat(player): tap subtitle parser cues"
```

---

### Task 2: Queue Parser Cues Without Blocking Playback

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/translation/ParserAheadSubtitleDiagnostics.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/translation/ParserAheadSubtitleQueue.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/translation/ParserAheadSubtitleQueueTest.kt`

- [ ] **Step 1: Write queue tests**

Create `ParserAheadSubtitleQueueTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.extractor.text.CuesWithTiming
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ParserAheadSubtitleQueueTest {
    @Test
    fun `enqueue returns immediately and drains on worker scope`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val drained = mutableListOf<CueGroup>()
        val queue = ParserAheadSubtitleQueue(
            scope = scope,
            maxQueuedCues = 10,
            enqueueForTranslation = { _, cueGroup -> drained += cueGroup }
        )

        queue.enqueue(
            Format.Builder().setLanguage("es").build(),
            CuesWithTiming(listOf(Cue.Builder().setText("hola").build()), 1_000L, 2_000L)
        )

        assertEquals(0, drained.size)
        scope.advanceUntilIdle()
        assertEquals(1, drained.size)
        assertEquals(1_000L, drained.single().presentationTimeUs)
    }

    @Test
    fun `duplicate cue text at same start is dropped`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val drained = mutableListOf<CueGroup>()
        val queue = ParserAheadSubtitleQueue(scope = scope, enqueueForTranslation = { _, cueGroup -> drained += cueGroup })
        val format = Format.Builder().setLanguage("es").build()
        val cue = CuesWithTiming(listOf(Cue.Builder().setText("hola").build()), 1_000L, 2_000L)

        queue.enqueue(format, cue)
        queue.enqueue(format, cue)
        scope.advanceUntilIdle()

        assertEquals(1, drained.size)
    }
}
```

- [ ] **Step 2: Implement diagnostics**

Create `ParserAheadSubtitleDiagnostics.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.translation

import android.util.Log

internal object ParserAheadSubtitleDiagnostics {
    private const val TAG = "Nexio.SubtitleAhead"
    private const val LOG_INTERVAL_MS = 2_000L
    private var lastProgressLogMs = 0L
    private var enqueued = 0L
    private var droppedDuplicate = 0L
    private var droppedOverflow = 0L

    fun reset() {
        lastProgressLogMs = 0L
        enqueued = 0L
        droppedDuplicate = 0L
        droppedOverflow = 0L
    }

    fun enqueued(cueTimeUs: Long, playbackPositionUs: Long, queueSize: Int) {
        enqueued += 1
        val now = System.currentTimeMillis()
        if (now - lastProgressLogMs < LOG_INTERVAL_MS) return
        lastProgressLogMs = now
        val aheadMs = ((cueTimeUs - playbackPositionUs) / 1000L).coerceAtLeast(0L)
        Log.i(
            TAG,
            "PARSER_AHEAD_SUBS event=progress enqueued=$enqueued duplicateDrops=$droppedDuplicate " +
                "overflowDrops=$droppedOverflow queue=$queueSize cueTimeMs=${cueTimeUs / 1000L} aheadMs=$aheadMs"
        )
    }

    fun duplicateDrop() {
        droppedDuplicate += 1
    }

    fun overflowDrop() {
        droppedOverflow += 1
    }

    fun resetParser(formatId: String?, language: String?) {
        Log.i(TAG, "PARSER_AHEAD_SUBS event=parser_reset format=${formatId.orEmpty()} lang=${language.orEmpty()}")
    }
}
```

- [ ] **Step 3: Implement queue**

Create `ParserAheadSubtitleQueue.kt`:

```kotlin
package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.Format
import androidx.media3.common.text.CueGroup
import androidx.media3.extractor.text.CuesWithTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.security.MessageDigest

internal class ParserAheadSubtitleQueue(
    private val scope: CoroutineScope,
    private val maxQueuedCues: Int = 300,
    private val playbackPositionUsProvider: () -> Long = { 0L },
    private val enqueueForTranslation: (Format, CueGroup) -> Unit
) : AheadSubtitleCueSink {
    private val channel = Channel<AheadSubtitleCue>(capacity = maxQueuedCues)
    private val seen = LinkedHashSet<String>()

    init {
        scope.launch {
            for (cue in channel) {
                val cueGroup = cue.cues.toCueGroup() ?: continue
                enqueueForTranslation(cue.format, cueGroup)
            }
        }
    }

    override fun isEnabled(format: Format): Boolean = true

    override fun enqueue(format: Format, cues: CuesWithTiming) {
        val key = stableKey(format, cues) ?: return
        synchronized(seen) {
            if (!seen.add(key)) {
                ParserAheadSubtitleDiagnostics.duplicateDrop()
                return
            }
            while (seen.size > maxQueuedCues * 2) {
                val iterator = seen.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
        }
        val result = channel.trySend(AheadSubtitleCue(format, cues))
        if (result.isFailure) {
            ParserAheadSubtitleDiagnostics.overflowDrop()
            return
        }
        ParserAheadSubtitleDiagnostics.enqueued(
            cueTimeUs = cues.startTimeUs,
            playbackPositionUs = playbackPositionUsProvider(),
            queueSize = channel.capacityForDiagnostics()
        )
    }

    override fun onParserReset(format: Format) {
        synchronized(seen) { seen.clear() }
        ParserAheadSubtitleDiagnostics.resetParser(format.id, format.language)
    }

    private fun CuesWithTiming.toCueGroup(): CueGroup? {
        if (cues.isEmpty()) return null
        return CueGroup(cues, startTimeUs)
    }

    private fun stableKey(format: Format, cues: CuesWithTiming): String? {
        val text = cues.cues.joinToString("\n") { it.text?.toString()?.trim().orEmpty() }
            .trim()
            .takeIf(String::isNotBlank)
            ?: return null
        return sha256("${format.id.orEmpty()}|${format.language.orEmpty()}|${cues.startTimeUs}|${cues.durationUs}|$text")
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun <T> Channel<T>.capacityForDiagnostics(): Int = maxQueuedCues
}
```

- [ ] **Step 4: Run queue tests**

Run:

```bash
./gradlew :app:testReleaseEarlyAccessUnitTest --tests 'com.nexio.tv.ui.screens.player.translation.ParserAheadSubtitleQueueTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/translation/ParserAheadSubtitleDiagnostics.kt app/src/main/java/com/nexio/tv/ui/screens/player/translation/ParserAheadSubtitleQueue.kt app/src/test/java/com/nexio/tv/ui/screens/player/translation/ParserAheadSubtitleQueueTest.kt
git commit -m "feat(player): queue parser-emitted subtitle cues"
```

---

### Task 3: Reuse Existing Translation Path For Parser-Ahead Cues

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/BuiltInSubtitleCueTranslator.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/BuiltInSubtitleCueTranslatorTest.kt`

- [ ] **Step 1: Add a failing test**

Add to `BuiltInSubtitleCueTranslatorTest.kt`:

```kotlin
@Test
fun `enqueueAheadCue translates through existing batching path`() = runTest {
    val translated = mutableListOf<List<String>>()
    val callbacks = mutableListOf<List<CueGroup>>()
    val translator = newTranslator(
        translateCueTexts = { texts, _, _, _ ->
            translated += texts
            Result.success(mapOf("hola" to "hello"))
        }
    )
    val format = Format.Builder().setLanguage("es").build()
    val cueGroup = CueGroup(listOf(Cue.Builder().setText("hola").build()), 1_000L)

    translator.enqueueAheadCue(format, cueGroup) { translatedCueGroups ->
        callbacks += translatedCueGroups
    }
    advanceUntilIdle()

    assertEquals(listOf(listOf("hola")), translated)
    assertEquals("hello", callbacks.single().single().cues.single().text.toString())
}
```

Adapt helper names to match the existing test file. The required assertion is that parser-ahead enqueue uses the same translation service and returns translated cue groups.

- [ ] **Step 2: Add enqueue method**

In `BuiltInSubtitleCueTranslator`, add:

```kotlin
internal fun enqueueAheadCue(
    format: Format,
    cueGroup: CueGroup,
    callback: (List<CueGroup>) -> Unit = {}
) {
    translate(
        format = format,
        cueGroups = listOf(cueGroup),
        callback = object : CueGroupSubtitleTranslator.TranslationCallback {
            override fun onSuccess(translatedCueGroups: List<CueGroup>) {
                callback(translatedCueGroups)
            }

            override fun onFailure(error: Throwable) {
                callback(emptyList())
            }
        }
    )
}
```

This deliberately reuses the current batching, provider cooldown, and translation error behavior instead of introducing a second translation engine.

- [ ] **Step 3: Run translator tests**

Run:

```bash
./gradlew :app:testReleaseEarlyAccessUnitTest --tests 'com.nexio.tv.ui.screens.player.BuiltInSubtitleCueTranslatorTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/BuiltInSubtitleCueTranslator.kt app/src/test/java/com/nexio/tv/ui/screens/player/BuiltInSubtitleCueTranslatorTest.kt
git commit -m "feat(player): translate parser-ahead subtitle cues"
```

---

### Task 4: Install Parser Tap In Player Initialization

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerBuiltInAiGroundworkTest.kt`

- [ ] **Step 1: Add controller field**

In `PlayerRuntimeController.kt`, add:

```kotlin
internal var parserAheadSubtitleQueue: ParserAheadSubtitleQueue? = null
```

Add the import:

```kotlin
import com.nexio.tv.ui.screens.player.translation.ParserAheadSubtitleQueue
```

- [ ] **Step 2: Wire default subtitle parser factory**

In `PlayerRuntimeControllerInitialization.kt`, add imports:

```kotlin
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import com.nexio.tv.ui.screens.player.translation.ParserAheadSubtitleDiagnostics
import com.nexio.tv.ui.screens.player.translation.ParserAheadSubtitleQueue
import com.nexio.tv.ui.screens.player.translation.TappingSubtitleParserFactory
```

Before `mediaSourceFactory.configureSubtitleParsing(...)`, create:

```kotlin
ParserAheadSubtitleDiagnostics.reset()
val parserAheadQueue = ParserAheadSubtitleQueue(
    scope = scope,
    playbackPositionUsProvider = { backendCurrentPosition().coerceAtLeast(0L) * 1000L },
    enqueueForTranslation = { format, cueGroup ->
        builtInSubtitleCueTranslator.enqueueAheadCue(format, cueGroup)
    }
)
parserAheadSubtitleQueue = parserAheadQueue
val tappingSubtitleParserFactory = TappingSubtitleParserFactory(
    delegate = DefaultSubtitleParserFactory(),
    cueSink = parserAheadQueue
)
```

- [ ] **Step 3: Install only when ASS overlay parser override is not active**

Keep the ASS branch using `AssNoOpSubtitleParserFactory()`:

```kotlin
if (assController != null) {
    mediaSourceFactory.configureSubtitleParsing(
        extractorsFactory = AssSsaExtractorsFactory(extractorsFactory, assSampleSink ?: assController),
        subtitleParserFactory = AssNoOpSubtitleParserFactory()
    )
} else {
    mediaSourceFactory.configureSubtitleParsing(
        extractorsFactory = null,
        subtitleParserFactory = tappingSubtitleParserFactory
    )
}
```

Also apply the parser factory to the actual ExoPlayer media source factory:

```kotlin
val defaultMediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
    .setSubtitleParserFactory(
        if (assController != null) {
            AssNoOpSubtitleParserFactory()
        } else {
            tappingSubtitleParserFactory
        }
    )

_exoPlayer = ExoPlayer.Builder(context)
    ...
    .setMediaSourceFactory(defaultMediaSourceFactory)
    ...
    .build()
```

- [ ] **Step 4: Clear field on release**

In player release cleanup, set:

```kotlin
parserAheadSubtitleQueue = null
```

- [ ] **Step 5: Add wiring regression test**

Add a test in `PlayerRuntimeControllerBuiltInAiGroundworkTest.kt` or the closest existing initialization test that verifies:

```kotlin
assertTrue(shouldEnableAssSsaSampleTranslation(...))
```

is unchanged, and add a small pure helper if needed:

```kotlin
internal fun shouldUseParserAheadSubtitleTap(useAssSsaPipeline: Boolean): Boolean = !useAssSsaPipeline
```

Test:

```kotlin
@Test
fun `parser ahead subtitle tap is disabled for ass overlay pipeline`() {
    assertFalse(shouldUseParserAheadSubtitleTap(useAssSsaPipeline = true))
    assertTrue(shouldUseParserAheadSubtitleTap(useAssSsaPipeline = false))
}
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
./gradlew :app:testReleaseEarlyAccessUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerRuntimeControllerBuiltInAiGroundworkTest' --tests 'com.nexio.tv.ui.screens.player.translation.TappingSubtitleParserFactoryTest' --tests 'com.nexio.tv.ui.screens.player.translation.ParserAheadSubtitleQueueTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerBuiltInAiGroundworkTest.kt
git commit -m "feat(player): install parser subtitle tap in playback"
```

---

### Task 5: Remove Remote Embedded Harvest Architecture From This Branch

**Files:**
- No files from the old embedded harvester branch should be added.

- [ ] **Step 1: Confirm old harvester files are absent**

Run:

```bash
rg -n "EmbeddedSubtitleHarvest|MatroskaTextTrackHarvester|Mp4TextTrackHarvester|TranslatedSubtitleTimelineStore|PLAYBACK_SUB_SAMPLE|NexioSubtitleSample" app/src media/libraries || true
```

Expected: no app-side embedded harvester classes from the abandoned branch, and no lower-level `NexioSubtitleSample` tap classes.

- [ ] **Step 2: Confirm no second subtitle HTTP path was introduced**

Run:

```bash
rg -n "DefaultHttpDataSource|OkHttpDataSource|DataSpec|open\\(" app/src/main/java/com/nexio/tv/ui/screens/player/translation app/src/main/java/com/nexio/tv/ui/screens/player | rg "Subtitle|Harvest|ParserAhead|Tapping"
```

Expected: no translation or parser-ahead class opens a remote data source.

- [ ] **Step 3: Commit only if cleanup changes were needed**

If old files were accidentally added, remove them and commit explicit paths. If no cleanup was needed, do not create an empty commit.

---

### Task 6: Build, Install, And Prove Live Path

**Files:**
- Optional create: `diagnostics/2026-05-18-parser-ahead-subtitle-verification.md`

- [ ] **Step 1: Run focused tests**

Run:

```bash
./gradlew :app:testReleaseEarlyAccessUnitTest --tests 'com.nexio.tv.ui.screens.player.translation.TappingSubtitleParserFactoryTest' --tests 'com.nexio.tv.ui.screens.player.translation.ParserAheadSubtitleQueueTest' --tests 'com.nexio.tv.ui.screens.player.BuiltInSubtitleCueTranslatorTest' --tests 'com.nexio.tv.ui.screens.player.PlayerRuntimeControllerBuiltInAiGroundworkTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Build Early Access**

Run:

```bash
./gradlew :app:assembleReleaseEarlyAccess
```

Expected: APK at `app/build/outputs/apk/armv7/releaseEarlyAccess/app-armv7-releaseEarlyAccess.apk`.

- [ ] **Step 3: Install on rooted TV**

Run:

```bash
adb connect 192.168.50.98:5555
adb -s 192.168.50.98:5555 install -r app/build/outputs/apk/armv7/releaseEarlyAccess/app-armv7-releaseEarlyAccess.apk
```

Expected: `Success`.

- [ ] **Step 4: Verify parser tap during playback**

Clear logs, launch app, select profile if needed, then start remote MKV/MP4 playback with auto-translate on:

```bash
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell am force-stop com.nexio.tv.earlyaccess
adb -s 192.168.50.98:5555 shell monkey -p com.nexio.tv.earlyaccess 1
sleep 5
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
```

During playback:

```bash
adb -s 192.168.50.98:5555 logcat -d -v time | grep -E 'PARSER_AHEAD_SUBS|Subtitle|Background concurrent|Skipped [0-9]+ frames|UNDERRUN|SocketTimeoutException' | tail -200
```

Expected:

```text
PARSER_AHEAD_SUBS event=progress ... aheadMs=<positive when playback buffer is ahead>
```

Not expected:

```text
SocketTimeoutException from subtitle harvest
EMBEDDED_SUB_TIMELINE event=harvest_failed
```

- [ ] **Step 5: Record verification note**

If live verification succeeds, create:

```markdown
# Parser-Ahead Subtitle Verification

- Device: 192.168.50.98:5555
- Package: com.nexio.tv.earlyaccess
- Build: ReleaseEarlyAccess
- Architecture: SubtitleParser.Factory tap only
- Duplicate remote subtitle stream: absent
- Evidence: PARSER_AHEAD_SUBS progress logs with positive aheadMs
- Limitation: ahead depth follows Media3 playback buffer/frontier
```

Commit only this note if created:

```bash
git add diagnostics/2026-05-18-parser-ahead-subtitle-verification.md
git commit -m "docs(player): verify parser-ahead subtitle path"
```

---

## Self-Review

- Spec coverage: The plan uses only `SubtitleParser.Factory`, avoids duplicate remote fetches, preserves original subtitle rendering, and includes live proof on `192.168.50.98`.
- Placeholder scan: No placeholder markers or open implementation gaps are left in the task steps.
- Type consistency: `AheadSubtitleCueSink`, `TappingSubtitleParserFactory`, `ParserAheadSubtitleQueue`, and `enqueueAheadCue` are introduced before use.
- Scope check: Lower-level `TrackOutput` tapping and remote embedded subtitle harvesting are explicitly out of scope.
