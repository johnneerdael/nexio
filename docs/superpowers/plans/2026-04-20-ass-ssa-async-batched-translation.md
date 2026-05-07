# ASS/SSA Async Batched Translation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent missing live ASS/SSA subtitles by rendering originals immediately, translating upcoming samples asynchronously in batches, and replaying cached translated samples when they are ready in time.

**Architecture:** Convert `AssSsaTranslatingSampleSink` from "translate before forwarding" into a lookahead-aware cache and scheduler. Original samples are always cached and can render immediately; translation jobs batch upcoming samples per track/mode; `AssSsaRenderController` gets an explicit replay hook so seeks, mode toggles, and late-but-still-relevant translations can rebuild native libass state deterministically.

This plan assumes `2026-04-20-ass-ssa-lossless-parser-translation.md` is implemented first. Batching must call `AssSsaProtectedTranslationUnit.fromText(...)` so signs and dialogue share the same lossless extraction/reinsertion path.

**Tech Stack:** Kotlin, Android Media3/ExoPlayer, libass via `assrender`, Kotlin coroutines, JUnit/Robolectric, existing `AssSsaEventRecord`, `AssSsaProtectedTranslationUnit`, and `SubtitleTranslationService`.

---

## File Structure

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt`
  - Owns sample caching, async batch scheduling, fallback emission, translation result storage, and instrumentation.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderController.kt`
  - Adds replay/rebuild support for cached active-track samples without reinitializing ExoPlayer.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/AssSsaEventRecord.kt`
  - Adds duration/time parsing helpers used by the translation scheduler.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - Stores the translating sink separately and wires translation-mode changes/replay hooks.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAiSubtitles.kt`
  - Notifies ASS translation sink on AI toggle so native state is flushed/replayed.
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt`
  - Covers immediate original fallback, async translation cache, batching, late result policy, mode toggle replay, and instrumentation.
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderControllerTest.kt`
  - Covers replay/rebuild API and native flush behavior.
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaEventRecordTest.kt`
  - Covers ASS time parsing and event-window extraction.
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAssSsaPipelineTest.kt`
  - Covers wiring policy for AI toggle and ASS sink notification.

---

### Task 1: Add ASS Timing Helpers

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/AssSsaEventRecord.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/AssSsaEventRecordTest.kt`

- [ ] **Step 1: Write the failing timing tests**

Create `app/src/test/java/com/nexio/tv/data/repository/AssSsaEventRecordTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssSsaEventRecordTest {
    @Test
    fun parsesAssTimeToMicroseconds() {
        assertEquals(3_723_450_000L, parseAssTimeUsForTranslation("1:02:03.45"))
        assertEquals(3_723_450_000L, parseAssTimeUsForTranslation("1:02:03:45"))
        assertEquals(1_230_000L, parseAssTimeUsForTranslation("0:00:01.23"))
    }

    @Test
    fun rejectsMalformedAssTimes() {
        assertNull(parseAssTimeUsForTranslation(""))
        assertNull(parseAssTimeUsForTranslation("01:02"))
        assertNull(parseAssTimeUsForTranslation("hello"))
    }

    @Test
    fun exposesDialogueWindowFromStandardFormat() {
        val format = AssSsaEventFormat.standardDialogue()
        val record = checkNotNull(
            AssSsaEventRecord.parseDialogueLine(
                "Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,Hello",
                format
            )
        )

        assertEquals(1_000_000L, record.startTimeUsOrNull())
        assertEquals(3_500_000L, record.endTimeUsOrNull())
    }

    @Test
    fun matroskaRecordWithoutStartAndEndHasNoWindow() {
        val format = AssSsaEventFormat.matroskaAss()
        val record = checkNotNull(
            AssSsaEventRecord.parseDialogueLine(
                "Dialogue: 12,0,Default,,0,0,0,,Hello",
                format
            )
        )

        assertNull(record.startTimeUsOrNull())
        assertNull(record.endTimeUsOrNull())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaEventRecordTest
```

Expected: FAIL with unresolved references for `parseAssTimeUsForTranslation`, `startTimeUsOrNull`, and `endTimeUsOrNull`.

- [ ] **Step 3: Add minimal timing helpers**

Append this code to `app/src/main/java/com/nexio/tv/data/repository/AssSsaEventRecord.kt` after `formatAssTimeUs`:

```kotlin
internal fun AssSsaEventRecord.startTimeUsOrNull(): Long? {
    if (format.startIndex < 0) return null
    return values.getOrNull(format.startIndex)?.let(::parseAssTimeUsForTranslation)
}

internal fun AssSsaEventRecord.endTimeUsOrNull(): Long? {
    if (format.endIndex < 0) return null
    return values.getOrNull(format.endIndex)?.let(::parseAssTimeUsForTranslation)
}

internal fun parseAssTimeUsForTranslation(value: String): Long? {
    val parts = value.trim().split(":")
    if (parts.size != 3 && parts.size != 4) return null
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull() ?: return null
    val seconds: Long
    val centiseconds: Long
    if (parts.size == 4) {
        seconds = parts[2].toLongOrNull() ?: return null
        centiseconds = parts[3].take(2).padEnd(2, '0').toLongOrNull() ?: return null
    } else {
        val secondParts = parts[2].split(".", limit = 2)
        seconds = secondParts[0].toLongOrNull() ?: return null
        centiseconds = secondParts.getOrNull(1)
            ?.take(2)
            ?.padEnd(2, '0')
            ?.toLongOrNull()
            ?: 0L
    }
    return (((hours * 60L + minutes) * 60L + seconds) * 1_000_000L) +
        centiseconds * 10_000L
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AssSsaEventRecordTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/AssSsaEventRecord.kt app/src/test/java/com/nexio/tv/data/repository/AssSsaEventRecordTest.kt
git commit -m "test: cover ASS event timing helpers"
```

---

### Task 2: Add Immediate Original Fallback For Raw/System-Prompt Mode

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt`

- [ ] **Step 1: Write failing test for immediate raw fallback**

Add this test to `AssSsaTranslatingSampleSinkTest`:

```kotlin
@Test
fun systemPromptModeDelegatesOriginalImmediatelyBeforeAsyncTranslationFinishes() = runTest {
    val downstream = RecordingAssSsaSampleSink()
    val sample = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello"
    var releaseTranslation: (() -> Unit)? = null
    val sink = AssSsaTranslatingSampleSink(
        downstream = downstream,
        scope = this,
        isEnabled = { true },
        useSystemPromptTranslation = { true },
        translate = { error("placeholder path should not be used") },
        translateRawAssSsa = {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                releaseTranslation = {
                    continuation.resume(
                        "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hallo",
                        onCancellation = null
                    )
                }
            }
        }
    )

    sink.onSubtitleSample(trackId = 4, timeUs = 1_000_000L, data = sample.toByteArray())

    assertEquals(listOf(sample), downstream.samples.map { it.decodeToString() })

    releaseTranslation?.invoke()
    kotlinx.coroutines.test.runCurrent()

    assertEquals(
        listOf(
            sample,
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hallo"
        ),
        downstream.samples.map { it.decodeToString() }
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: FAIL because the original sample is not emitted until the raw provider returns or because `downstream.samples` is empty before `releaseTranslation`.

- [ ] **Step 3: Implement immediate fallback in raw mode**

In `AssSsaTranslatingSampleSink.onSubtitleSample`, replace the raw/system-prompt branch with:

```kotlin
if (useSystemPromptTranslation()) {
    downstream.onSubtitleSample(trackId, timeUs, data)
    scope.launch {
        val translatedSample = runCatching {
            translateRawAssSsa(text)
        }.getOrDefault(text)
        if (translatedSample != text) {
            downstream.onSubtitleSample(
                trackId = trackId,
                timeUs = timeUs,
                data = translatedSample.toByteArray()
            )
        }
    }
    return
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt
git commit -m "fix: render ASS originals while raw translation runs"
```

---

### Task 3: Introduce Sample Cache And Stable Keys

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt`

- [ ] **Step 1: Write failing test for translated cache**

Add this test:

```kotlin
@Test
fun cachesTranslatedRawSamplesByTrackAndTimeForReplay() = runTest {
    val downstream = RecordingAssSsaSampleSink()
    val sample = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello"
    val translated = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hallo"
    val sink = AssSsaTranslatingSampleSink(
        downstream = downstream,
        scope = this,
        isEnabled = { true },
        useSystemPromptTranslation = { true },
        translate = { error("placeholder path should not be used") },
        translateRawAssSsa = { translated }
    )

    sink.onSubtitleSample(trackId = 4, timeUs = 1_000_000L, data = sample.toByteArray())
    kotlinx.coroutines.test.runCurrent()

    assertEquals(
        listOf(sample, translated),
        downstream.samples.map { it.decodeToString() }
    )
    assertEquals(
        listOf(translated),
        sink.cachedSamplesForReplay(trackId = 4, positionUs = 2_000_000L).map { it.decodeToString() }
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: FAIL with unresolved reference `cachedSamplesForReplay`.

- [ ] **Step 3: Add cache model and replay accessor**

Inside `AssSsaTranslatingSampleSink`, add:

```kotlin
private data class CachedAssSample(
    val key: String,
    val trackId: Int,
    val timeUs: Long,
    val startUs: Long,
    val endUs: Long,
    val originalData: ByteArray,
    var translatedData: ByteArray? = null
) {
    fun bestData(): ByteArray = translatedData ?: originalData
}

private val cachedSamples = linkedMapOf<String, CachedAssSample>()

internal fun cachedSamplesForReplay(trackId: Int, positionUs: Long): List<ByteArray> {
    return cachedSamples.values
        .asSequence()
        .filter { it.trackId == trackId }
        .filter { it.endUs >= positionUs - REPLAY_LOOKBACK_US }
        .sortedBy { it.startUs }
        .map { it.bestData() }
        .toList()
}

private fun cacheSample(trackId: Int, timeUs: Long, data: ByteArray, records: List<AssSsaEventRecord>): CachedAssSample {
    val startUs = records.mapNotNull { it.startTimeUsOrNull() }.minOrNull() ?: timeUs
    val endUs = records.mapNotNull { it.endTimeUsOrNull() }.maxOrNull() ?: (timeUs + DEFAULT_SAMPLE_DURATION_US)
    val key = "$trackId:$timeUs:${data.contentHashCode()}"
    return cachedSamples.getOrPut(key) {
        CachedAssSample(
            key = key,
            trackId = trackId,
            timeUs = timeUs,
            startUs = startUs,
            endUs = endUs,
            originalData = data.copyOf()
        )
    }
}

private companion object {
    private const val DEFAULT_SAMPLE_DURATION_US = 5_000_000L
    private const val REPLAY_LOOKBACK_US = 5_000_000L
}
```

At the start of `onSubtitleSample`, after `records` is built and non-empty, call:

```kotlin
val cachedSample = cacheSample(trackId, timeUs, data, records)
```

In the raw branch, after translation succeeds:

```kotlin
cachedSample.translatedData = translatedSample.toByteArray()
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt
git commit -m "feat: cache ASS translation samples for replay"
```

---

### Task 4: Batch Upcoming Raw/System-Prompt Translation

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt`

- [ ] **Step 1: Write failing batching test**

Add this test:

```kotlin
@Test
fun systemPromptModeBatchesMultipleQueuedSamplesIntoOneProviderCall() = runTest {
    val downstream = RecordingAssSsaSampleSink()
    val rawSamples = listOf(
        "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello",
        "Dialogue: 0,0:00:03.00,0:00:05.00,Default,,0,0,0,,Goodbye"
    )
    var rawProviderCalls = 0
    val sink = AssSsaTranslatingSampleSink(
        downstream = downstream,
        scope = this,
        isEnabled = { true },
        useSystemPromptTranslation = { true },
        translate = { error("placeholder path should not be used") },
        translateRawAssSsa = { raw ->
            rawProviderCalls += 1
            assertEquals(rawSamples.joinToString("\n"), raw)
            raw.replace("Hello", "Hallo").replace("Goodbye", "Tot ziens")
        }
    )

    rawSamples.forEachIndexed { index, sample ->
        sink.onSubtitleSample(trackId = 4, timeUs = (index + 1) * 1_000_000L, data = sample.toByteArray())
    }
    kotlinx.coroutines.test.runCurrent()

    assertEquals(1, rawProviderCalls)
    assertEquals(
        listOf(
            rawSamples[0],
            rawSamples[1],
            rawSamples[0].replace("Hello", "Hallo"),
            rawSamples[1].replace("Goodbye", "Tot ziens")
        ),
        downstream.samples.map { it.decodeToString() }
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: FAIL because raw provider is called once per sample.

- [ ] **Step 3: Add raw batch queue**

Add fields to `AssSsaTranslatingSampleSink`:

```kotlin
private val pendingRawSamples = mutableListOf<CachedAssSample>()
private var rawBatchJob: kotlinx.coroutines.Job? = null
private val batchWindowMs: Long = 75L
```

Add methods:

```kotlin
private fun enqueueRawSampleForTranslation(sample: CachedAssSample) {
    if (pendingRawSamples.none { it.key == sample.key }) {
        pendingRawSamples += sample
    }
    if (rawBatchJob?.isActive == true) return
    rawBatchJob = scope.launch {
        kotlinx.coroutines.delay(batchWindowMs)
        flushRawTranslationBatch()
    }
}

private suspend fun flushRawTranslationBatch() {
    val batch = pendingRawSamples.toList()
    pendingRawSamples.clear()
    if (batch.isEmpty()) return
    val rawText = batch.joinToString("\n") { it.originalData.decodeToString() }
    val translatedText = runCatching { translateRawAssSsa(rawText) }.getOrDefault(rawText)
    val translatedLines = translatedText.lineSequence().toList()
    batch.forEachIndexed { index, sample ->
        val line = translatedLines.getOrNull(index) ?: return@forEachIndexed
        val translatedData = line.toByteArray()
        sample.translatedData = translatedData
        downstream.onSubtitleSample(sample.trackId, sample.timeUs, translatedData)
    }
}
```

Replace the raw branch with:

```kotlin
if (useSystemPromptTranslation()) {
    downstream.onSubtitleSample(trackId, timeUs, data)
    enqueueRawSampleForTranslation(cachedSample)
    return
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt
git commit -m "feat: batch raw ASS translation samples"
```

---

### Task 5: Add Late Translation Policy And Instrumentation

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt`

- [ ] **Step 1: Write failing late-policy test**

Add this test:

```kotlin
@Test
fun lateTranslatedSampleIsCachedButNotEmittedAfterCueWindow() = runTest {
    val downstream = RecordingAssSsaSampleSink()
    val sample = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello"
    var nowUs = 4_000_000L
    val sink = AssSsaTranslatingSampleSink(
        downstream = downstream,
        scope = this,
        isEnabled = { true },
        useSystemPromptTranslation = { true },
        translate = { error("placeholder path should not be used") },
        translateRawAssSsa = { it.replace("Hello", "Hallo") },
        currentPositionUs = { nowUs }
    )

    sink.onSubtitleSample(trackId = 4, timeUs = 1_000_000L, data = sample.toByteArray())
    kotlinx.coroutines.test.runCurrent()

    assertEquals(listOf(sample), downstream.samples.map { it.decodeToString() })
    assertEquals(
        listOf(sample.replace("Hello", "Hallo")),
        sink.cachedSamplesForReplay(trackId = 4, positionUs = 2_000_000L).map { it.decodeToString() }
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: FAIL because constructor lacks `currentPositionUs` and late translated sample is emitted.

- [ ] **Step 3: Add current position provider and late gate**

Update constructor:

```kotlin
private val currentPositionUs: () -> Long = { 0L }
```

Add:

```kotlin
private fun shouldEmitTranslatedSample(sample: CachedAssSample): Boolean {
    return currentPositionUs() <= sample.endUs
}

private fun logTranslationDecision(sample: CachedAssSample, decision: String) {
    android.util.Log.d(
        "AssSsaTranslating",
        "ASS_TRANSLATION sample=${sample.key} track=${sample.trackId} " +
            "startUs=${sample.startUs} endUs=${sample.endUs} nowUs=${currentPositionUs()} decision=$decision"
    )
}
```

In `flushRawTranslationBatch`, after `sample.translatedData = translatedData`:

```kotlin
if (shouldEmitTranslatedSample(sample)) {
    logTranslationDecision(sample, "emit_translated")
    downstream.onSubtitleSample(sample.trackId, sample.timeUs, translatedData)
} else {
    logTranslationDecision(sample, "cache_late_translation")
}
```

- [ ] **Step 4: Wire current position at construction site**

In `PlayerRuntimeControllerInitialization.kt`, update `AssSsaTranslatingSampleSink(...)` construction:

```kotlin
currentPositionUs = {
    (_exoPlayer?.currentPosition ?: 0L) * 1000L
}
```

- [ ] **Step 5: Run tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt
git commit -m "fix: cache late ASS translations instead of double rendering"
```

---

### Task 6: Add AssSsaRenderController Replay API

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderController.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderControllerTest.kt`

- [ ] **Step 1: Write failing replay test**

Add this test to `AssSsaRenderControllerTest`:

```kotlin
@Test
fun replayActiveTrackSamplesFlushesAndProcessesProvidedSamples() {
    val native = FakeAssSsaNativeApi()
    val controller = newController(native)
    val format = Format.Builder().setLanguage("en").build()

    controller.setVideoSize(640, 360)
    controller.onTrackHeader(trackId = 31, headerData = "[Script Info]".toByteArray(), format)
    controller.selectTrackByFormat(format)
    controller.renderCurrentFrameForTesting()
    native.clearRecordedCalls()

    controller.replayActiveTrackSamples(
        listOf(
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hallo".toByteArray()
        )
    )

    assertEquals(listOf(1L), native.flushedHandles)
    assertEquals(1, native.chunks.size)
    assertArrayEquals(
        "0,Default,,0,0,0,,Hallo".toByteArray(),
        native.chunks.single().data
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaRenderControllerTest
```

Expected: FAIL with unresolved reference `replayActiveTrackSamples`.

- [ ] **Step 3: Implement replay API**

Add to `AssSsaRenderController`:

```kotlin
fun replayActiveTrackSamples(samples: List<ByteArray>) {
    synchronized(stateLock) {
        if (released) return
        val activeHandle = handle
        if (activeHandle != 0L) {
            native.flush(activeHandle)
            loadedTrackId = null
            loadSelectedTrackHeader(activeHandle)
        }
        val trackId = selectedTrackId ?: return
        eventChunks.removeAll { it.trackId == trackId }
        rawSamples.removeAll { it.trackId == trackId }
        samples.forEach { data ->
            ingestSubtitleSampleLocked(trackId, currentTimeUs, data)
        }
    }
    startRenderLoopIfNeeded()
}
```

Extract existing sample ingestion body from `onSubtitleSample` into:

```kotlin
private fun ingestSubtitleSampleLocked(trackId: Int, timeUs: Long, data: ByteArray) {
    val chunk = data.decodeToString()
        .lineSequence()
        .mapNotNull { line -> line.toAssSsaEventChunk(trackId, timeUs) }
        .firstOrNull()

    if (chunk != null) {
        eventChunks += chunk
        if (trackId == selectedTrackId && ensureNativeInitialized(replayEvents = false)) {
            native.processChunk(handle, chunk.chunkData, chunk.startMs, chunk.durationMs)
        }
        return
    }

    val rawSample = RawSample(trackId, data)
    rawSamples += rawSample
    if (trackId == selectedTrackId && ensureNativeInitialized(replayEvents = false)) {
        native.processData(handle, rawSample.data)
    }
}
```

Then make `onSubtitleSample` call that method inside the existing lock.

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaRenderControllerTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderController.kt app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderControllerTest.kt
git commit -m "feat: replay cached ASS samples through assrender"
```

---

### Task 7: Replay Cached Translations On Seek And Toggle

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAiSubtitles.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAssSsaPipelineTest.kt`

- [ ] **Step 1: Write failing replay method test**

Add to `AssSsaTranslatingSampleSinkTest`:

```kotlin
@Test
fun replayCachedSamplesDelegatesBestAvailableSamplesToReplaySink() = runTest {
    val downstream = RecordingAssSsaSampleSink()
    val replayed = mutableListOf<ByteArray>()
    val sink = AssSsaTranslatingSampleSink(
        downstream = downstream,
        scope = this,
        isEnabled = { true },
        useSystemPromptTranslation = { true },
        translate = { error("placeholder path should not be used") },
        translateRawAssSsa = { it.replace("Hello", "Hallo") },
        currentPositionUs = { 2_000_000L },
        replayActiveTrackSamples = { samples -> replayed += samples }
    )
    val sample = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello"

    sink.onSubtitleSample(trackId = 4, timeUs = 1_000_000L, data = sample.toByteArray())
    kotlinx.coroutines.test.runCurrent()
    sink.replayCachedActiveTrack(trackId = 4, positionUs = 2_000_000L)

    assertEquals(listOf(sample.replace("Hello", "Hallo")), replayed.map { it.decodeToString() })
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: FAIL with unresolved constructor parameter `replayActiveTrackSamples` and method `replayCachedActiveTrack`.

- [ ] **Step 3: Add replay callback to sink**

Update `AssSsaTranslatingSampleSink` constructor:

```kotlin
private val replayActiveTrackSamples: (List<ByteArray>) -> Unit = {}
```

Add:

```kotlin
fun replayCachedActiveTrack(trackId: Int, positionUs: Long) {
    replayActiveTrackSamples(cachedSamplesForReplay(trackId, positionUs))
}
```

- [ ] **Step 4: Store sink separately in controller**

In `PlayerRuntimeController.kt`, add:

```kotlin
internal var assSsaTranslatingSampleSink: AssSsaTranslatingSampleSink? = null
```

In `PlayerRuntimeControllerInitialization.kt`, after creating `assSampleSink`, set:

```kotlin
assSsaTranslatingSampleSink = assSampleSink
```

When no ASS controller exists, clear it:

```kotlin
assSsaTranslatingSampleSink = null
```

In the `AssSsaTranslatingSampleSink(...)` constructor call, add:

```kotlin
replayActiveTrackSamples = { samples ->
    assSsaRenderController?.replayActiveTrackSamples(samples)
}
```

- [ ] **Step 5: Replay on seek**

In `PlayerRuntimeControllerInitialization.kt`, inside the existing seek callback that calls:

```kotlin
assSsaRenderController?.onSeekStarted()
```

append:

```kotlin
val selectedTrack = _uiState.value.selectedSubtitleTrackIndex
if (selectedTrack >= 0) {
    assSsaTranslatingSampleSink?.replayCachedActiveTrack(
        trackId = selectedTrack,
        positionUs = (_exoPlayer?.currentPosition ?: 0L) * 1000L
    )
}
```

- [ ] **Step 6: Replay on AI toggle**

In `PlayerRuntimeControllerAiSubtitles.kt`, after toggling `aiSubtitlesEnabled`, add:

```kotlin
val selectedTrack = _uiState.value.selectedSubtitleTrackIndex
if (selectedTrack >= 0) {
    assSsaRenderController?.onSeekStarted()
    assSsaTranslatingSampleSink?.replayCachedActiveTrack(
        trackId = selectedTrack,
        positionUs = (_exoPlayer?.currentPosition ?: 0L) * 1000L
    )
}
```

- [ ] **Step 7: Run focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaRenderControllerTest --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerAssSsaPipelineTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderController.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAiSubtitles.kt app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderControllerTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAssSsaPipelineTest.kt
git commit -m "feat: replay cached ASS translations on seek and toggle"
```

---

### Task 8: Add Protected-Mode Lookahead Batching

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSinkTest.kt`

- [ ] **Step 1: Write failing protected batching test**

Add:

```kotlin
@Test
fun protectedModeBatchesQueuedUnitsAndCachesTranslatedSamples() = runTest {
    val downstream = RecordingAssSsaSampleSink()
    var providerCalls = 0
    val sink = AssSsaTranslatingSampleSink(
        downstream = downstream,
        scope = this,
        isEnabled = { true },
        useSystemPromptTranslation = { false },
        translate = { units ->
            providerCalls += 1
            assertEquals(listOf("Hello", "Goodbye"), units.map { it.protectedText })
            mapOf("evt_0" to "Hallo", "evt_1" to "Tot ziens")
        },
        translateRawAssSsa = { error("raw path should not be used") },
        currentPositionUs = { 1_500_000L }
    )

    sink.onSubtitleSample(
        trackId = 4,
        timeUs = 1_000_000L,
        data = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello".toByteArray()
    )
    sink.onSubtitleSample(
        trackId = 4,
        timeUs = 2_000_000L,
        data = "Dialogue: 0,0:00:02.00,0:00:04.00,Default,,0,0,0,,Goodbye".toByteArray()
    )
    kotlinx.coroutines.test.runCurrent()

    assertEquals(1, providerCalls)
    assertEquals(
        listOf(
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello",
            "Dialogue: 0,0:00:02.00,0:00:04.00,Default,,0,0,0,,Goodbye",
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hallo",
            "Dialogue: 0,0:00:02.00,0:00:04.00,Default,,0,0,0,,Tot ziens"
        ),
        downstream.samples.map { it.decodeToString() }
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest
```

Expected: FAIL because protected mode still launches one translation per sample.

- [ ] **Step 3: Queue protected samples using existing batch planner**

Add:

```kotlin
private val pendingProtectedSamples = mutableListOf<CachedAssSample>()
private var protectedBatchJob: kotlinx.coroutines.Job? = null
```

Add:

```kotlin
private fun enqueueProtectedSampleForTranslation(sample: CachedAssSample) {
    if (pendingProtectedSamples.none { it.key == sample.key }) {
        pendingProtectedSamples += sample
    }
    if (protectedBatchJob?.isActive == true) return
    protectedBatchJob = scope.launch {
        kotlinx.coroutines.delay(batchWindowMs)
        flushProtectedTranslationBatch()
    }
}
```

Implement:

```kotlin
private suspend fun flushProtectedTranslationBatch() {
    val batch = pendingProtectedSamples.toList()
    pendingProtectedSamples.clear()
    if (batch.isEmpty()) return
    val sampleUnits = batch.mapIndexedNotNull { index, sample ->
        val records = parseRecords(sample.originalData.decodeToString(), sample.trackId)
        val record = records.singleOrNull() ?: return@mapIndexedNotNull null
        val unit = AssSsaProtectedTranslationUnit.fromTokens(
            id = "evt_$index",
            tokens = AssSsaTextTokenizer.tokenize(record.text)
        )
        Triple(sample, record, unit)
    }.filter { (_, _, unit) ->
        unit.risk != AssSsaRisk.PreserveOnly && unit.protectedText.isNotBlank()
    }
    if (sampleUnits.isEmpty()) return
    val translated = runCatching {
        translate(sampleUnits.map { it.third })
    }.getOrDefault(emptyMap())
    sampleUnits.forEachIndexed { index, (sample, record, unit) ->
        val translatedText = translated["evt_$index"] ?: return@forEachIndexed
        val reconstructed = unit.reconstruct(translatedText).getOrNull() ?: return@forEachIndexed
        val data = record.withText(reconstructed).render().toByteArray()
        sample.translatedData = data
        if (shouldEmitTranslatedSample(sample)) {
            downstream.onSubtitleSample(sample.trackId, sample.timeUs, data)
        }
    }
}
```

Extract record parsing into:

```kotlin
private fun parseRecords(text: String, trackId: Int): List<AssSsaEventRecord> {
    val format = trackFormats[trackId] ?: AssSsaEventFormat.standardDialogue()
    return text.lineSequence()
        .mapNotNull { line -> AssSsaEventRecord.parseDialogueLine(line, format) }
        .toList()
}
```

Replace protected branch translation launch with:

```kotlin
downstream.onSubtitleSample(trackId, timeUs, data)
enqueueProtectedSampleForTranslation(cachedSample)
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
git commit -m "feat: batch protected ASS translation samples"
```

---

### Task 9: Add Targeted Runtime Logging For Field Debugging

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderController.kt`

- [ ] **Step 1: Add translation metrics logs**

In `AssSsaTranslatingSampleSink`, add:

```kotlin
private fun logBatchStart(mode: String, count: Int) {
    android.util.Log.d("AssSsaTranslating", "ASS_TRANSLATION batch_start mode=$mode count=$count")
}

private fun logBatchFinish(mode: String, count: Int, elapsedMs: Long) {
    android.util.Log.d("AssSsaTranslating", "ASS_TRANSLATION batch_finish mode=$mode count=$count elapsedMs=$elapsedMs")
}
```

Wrap each `flush*TranslationBatch` provider call:

```kotlin
val startedAtMs = android.os.SystemClock.elapsedRealtime()
logBatchStart("raw", batch.size)
val translatedText = runCatching { translateRawAssSsa(rawText) }.getOrDefault(rawText)
logBatchFinish("raw", batch.size, android.os.SystemClock.elapsedRealtime() - startedAtMs)
```

Use `"protected"` for protected mode.

- [ ] **Step 2: Add render replay logs**

In `AssSsaRenderController.replayActiveTrackSamples`, add:

```kotlin
android.util.Log.d(
    "AssSsaRenderController",
    "ASS_RENDER_REPLAY samples=${samples.size} selectedTrackId=$selectedTrackId currentTimeUs=$currentTimeUs"
)
```

- [ ] **Step 3: Run compile and focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaTranslatingSampleSinkTest --tests com.nexio.tv.ui.screens.player.ass.AssSsaRenderControllerTest
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaTranslatingSampleSink.kt app/src/main/java/com/nexio/tv/ui/screens/player/ass/AssSsaRenderController.kt
git commit -m "chore: log ASS translation batch timing"
```

---

### Task 10: Device Verification

**Files:**
- No code changes.

- [ ] **Step 1: Install the build**

Run:

```bash
./gradlew :app:installUniversalDebug
```

Expected: Gradle reports `BUILD SUCCESSFUL`.

- [ ] **Step 2: Start filtered logcat**

Run:

```bash
adb connect 192.168.50.98:5555
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 logcat -v time | rg --line-buffered "ASS_TRANSLATION|ASS_RENDER_REPLAY|ASS_SSA_RENDER|assrender|Selecting INTERNAL|TRACKS updated|Playback error|Legacy decoding|text/x-ssa"
```

Expected during playback:

```text
ASS_SSA_RENDER: FFmpeg startup probe detected embedded ASS/SSA
assrender: Loaded ASS header
ASS_TRANSLATION batch_start mode=raw count=...
ASS_TRANSLATION batch_finish mode=raw count=... elapsedMs=...
```

- [ ] **Step 3: Verify raw/system-prompt mode**

Manual steps:

```text
1. Start the Bakemonogatari stream.
2. Enable AI subtitle translation with ASS/SSA system-prompt mode on.
3. Let playback run for 60 seconds.
4. Seek back 20 seconds.
5. Toggle translation off and on.
```

Expected:

```text
- Original subtitles appear immediately when translation is late.
- Translated subtitles appear for later cues after batch warm-up.
- No long blank gaps during active dialogue.
- Seek back replays cached translated lines.
- Toggle off/on does not leave stale translated native state.
- No "Legacy decoding is disabled" error.
```

- [ ] **Step 4: Verify protected mode**

Manual steps:

```text
1. Disable ASS/SSA system-prompt mode.
2. Keep AI translation enabled.
3. Play the same stream for 60 seconds.
4. Watch dialogue and signs separately.
```

Expected:

```text
- Dialogue style remains comparable to original ASS Default style.
- Sign-heavy lines either remain original or are translated without tiny dialogue styling regressions.
- No missing cue windows caused by provider latency.
```

- [ ] **Step 5: Commit verification note if needed**

If device verification uncovers a new reproducible issue, add a short note to the implementation PR/commit message rather than changing code in this task. Use a new plan/task for new behavior.

---

## Self-Review

**Spec coverage:** The plan covers missing subtitles in raw/system-prompt mode, async batching before render time, original fallback, replay on seek/toggle, late translation handling, instrumentation, and the tiny-subtitle investigation path.

**Placeholder scan:** No `TBD`, `TODO`, "add appropriate", or "similar to" placeholders remain. Every code-changing step has concrete code and commands.

**Type consistency:** The plan consistently uses `CachedAssSample`, `cachedSamplesForReplay`, `replayCachedActiveTrack`, `replayActiveTrackSamples`, `currentPositionUs`, and existing `AssSsaEventRecord` helpers across tasks.
