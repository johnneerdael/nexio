# Dolby Vision Sample Rewrite Allocation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce playback-time GC pressure from DV7 to DV8.1 sample rewriting while preserving the existing on-the-fly Dolby Vision conversion behavior.

**Architecture:** Extract the length-delimited HEVC sample rewrite into a pure, unit-tested helper that parses NAL ranges by offset, copies only tiny RPU NAL payloads for libdovi conversion, and writes the final rewritten sample with one exact-sized `ByteArray`. Keep Media3 hook semantics unchanged: return `null` when no sample transform is needed, return a replacement sample only when enhancement-layer NALs are dropped or an RPU changes.

**Tech Stack:** Kotlin, AndroidX Media3 extractor Dolby Vision sample transformer hooks, JNI `libdovi` bridge, JUnit unit tests.

---

## Research Findings

Primary sources checked:

- `quietvoid/dovi_tool` README: `-m 2` converts RPU to profile 8.1 compatibility and `convert --discard` discards the enhancement layer for DV7 to DV8.1 conversion. Source: https://github.com/quietvoid/dovi_tool
- `dovi_tool` `DoviRpu::convert_with_mode`: mode 2 sets profile 8.1 behavior and removes luma/chroma mapping for profile 7 FEL; legacy mode 5 preserves mapping. Source: https://raw.githubusercontent.com/quietvoid/dovi_tool/main/dolby_vision/src/rpu/dovi_rpu.rs
- Existing Nexio native bridge already delegates RPU conversion to `libdovi` with mode 2 or mode 5. The optimization must not reimplement RPU conversion; it should only avoid unnecessary full-sample/per-NAL allocations before and after that native call.

Important conversion invariants:

- Preserve base-layer HEVC NAL order.
- Drop enhancement-layer NALs where `nuh_layer_id > 0` except Dolby Vision RPU NAL type 62.
- Convert only RPU NAL type 62 through `DoviBridge.convertDv7RpuToDv81(...)`.
- Normalize converted RPU NAL `nuh_layer_id` to zero before writing it back.
- Preserve the existing `preserveMappingEnabled` behavior: profile 7 uses mode 5 only when that existing setting is active, otherwise mode 2.
- Preserve Media3 transformer return semantics: `null` means “use the original sample unchanged.”

## Current Root Cause

`app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt` currently allocates heavily inside `rewriteMp4HevcSample(...)`:

- `copyOfRange(...)` creates a new array for every NAL, including ordinary base-layer video NALs.
- `ByteArrayOutputStream(sampleLengthDelimited.size + 128)` allocates a large growable buffer for the whole sample.
- `out.toByteArray()` creates a second full rewritten sample copy.
- Runtime diagnostics showed `rewriteInMb=305`, `rewriteOutMb=305`, `nalCopyMb=305` by `pos=59s`, while actual RPU bytes were still below 1MB.

The allocation fix should target whole-sample/NAL copying, not `libdovi` itself.

## File Structure

- Modify: `app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt`
  - Responsibility after this change: Media3 hook installation, profile/mode selection, native bridge invocation, diagnostics counters.
  - Remove the heavy sample-rewrite implementation from this file.

- Create: `app/src/main/java/com/nexio/tv/core/player/DolbyVisionHevcSampleRewriter.kt`
  - Responsibility: pure length-delimited HEVC sample parsing and optimized sample rewrite.
  - No Android dependencies and no direct `DoviBridge` dependency.
  - Accept a converter function so tests can validate behavior without JNI.

- Create: `app/src/main/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelector.kt`
  - Responsibility: pure conversion-mode selection for Nexio’s DV conversion settings.
  - Preserve current behavior exactly: profile 7 uses mode 5 only when preserve mapping is enabled, otherwise mode 2; profile 5 uses mode 2 when conversion is allowed.

- Create: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionHevcSampleRewriterTest.kt`
  - Responsibility: focused tests for no-change passthrough, enhancement-layer drop, RPU conversion, invalid sample handling, and allocation-relevant metrics.

- Create: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelectorTest.kt`
  - Responsibility: lock Nexio’s two DV7 conversion features before touching the sample rewrite path.

- Modify: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionDiagnosticsTest.kt`
  - Responsibility: keep existing diagnostics reset checks; add no broad behavior tests here.

---

### Task 1: Pin Nexio DV Conversion Mode Selection

**Files:**
- Create: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelectorTest.kt`
- Create: `app/src/main/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelector.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt`

- [ ] **Step 1: Write the failing mode-selection test**

Create `app/src/test/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelectorTest.kt`:

```kotlin
package com.nexio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DolbyVisionConversionModeSelectorTest {

    @Test
    fun `profile 7 uses standard profile 8_1 conversion mode by default`() {
        val mode = DolbyVisionConversionModeSelector.selectedMode(
            sourceProfile = 7,
            preserveMappingEnabled = false,
            allowDv5Conversion = false
        )

        assertEquals(2, mode)
    }

    @Test
    fun `profile 7 uses preserve mapping conversion mode when enabled`() {
        val mode = DolbyVisionConversionModeSelector.selectedMode(
            sourceProfile = 7,
            preserveMappingEnabled = true,
            allowDv5Conversion = false
        )

        assertEquals(5, mode)
    }

    @Test
    fun `profile 5 uses profile 8_1 conversion mode only when allowed`() {
        val disabled = DolbyVisionConversionModeSelector.selectedMode(
            sourceProfile = 5,
            preserveMappingEnabled = true,
            allowDv5Conversion = false
        )
        val enabled = DolbyVisionConversionModeSelector.selectedMode(
            sourceProfile = 5,
            preserveMappingEnabled = true,
            allowDv5Conversion = true
        )

        assertNull(disabled)
        assertEquals(2, enabled)
    }

    @Test
    fun `unsupported and unknown profiles do not select a conversion mode`() {
        assertNull(
            DolbyVisionConversionModeSelector.selectedMode(
                sourceProfile = 8,
                preserveMappingEnabled = true,
                allowDv5Conversion = true
            )
        )
        assertNull(
            DolbyVisionConversionModeSelector.selectedMode(
                sourceProfile = null,
                preserveMappingEnabled = true,
                allowDv5Conversion = true
            )
        )
    }
}
```

- [ ] **Step 2: Run the test and verify it fails because the selector does not exist**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionConversionModeSelectorTest
```

Expected: compilation fails with unresolved reference `DolbyVisionConversionModeSelector`.

- [ ] **Step 3: Create the pure selector**

Create `app/src/main/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelector.kt`:

```kotlin
package com.nexio.tv.core.player

internal object DolbyVisionConversionModeSelector {
    const val MODE_PROFILE_8_1 = 2
    const val MODE_PROFILE_8_1_PRESERVE_MAPPING = 5

    fun selectedMode(
        sourceProfile: Int?,
        preserveMappingEnabled: Boolean,
        allowDv5Conversion: Boolean
    ): Int? {
        return when (sourceProfile) {
            7 -> if (preserveMappingEnabled) {
                MODE_PROFILE_8_1_PRESERVE_MAPPING
            } else {
                MODE_PROFILE_8_1
            }
            5 -> if (allowDv5Conversion) MODE_PROFILE_8_1 else null
            else -> null
        }
    }
}
```

- [ ] **Step 4: Wire the selector into `MatroskaDolbyVisionHookInstaller.kt` without changing behavior**

Replace the body of the nested `shouldAllowConversion(profile: Int?)` function with:

```kotlin
        fun shouldAllowConversion(profile: Int?): Boolean {
            if (!conversionEnabled) {
                lastSelectedConversionMode.set(null)
                if (conversionUnavailableLogged.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "Dolby Vision conversion disabled (native bridge unavailable) host=$host"
                    )
                }
                return false
            }
            val resolvedProfile = rememberProfile(profile)
            val selectedMode = DolbyVisionConversionModeSelector.selectedMode(
                sourceProfile = resolvedProfile,
                preserveMappingEnabled = preserveMappingEnabled,
                allowDv5Conversion = allowDv5Conversion
            )
            if (selectedMode != null) {
                return true
            }
            lastSelectedConversionMode.set(null)
            if (resolvedProfile != null && nonDv7ProfileLogged.compareAndSet(false, true)) {
                Log.i(
                    TAG,
                    "Skipping experimental DV conversion for unsupported profile=$resolvedProfile host=$host"
                )
            }
            return false
        }
```

Replace the body of the nested `selectedConversionMode(profile: Int?)` function with:

```kotlin
        fun selectedConversionMode(profile: Int?): Int {
            val resolvedProfile = rememberProfile(profile)
            val mode = DolbyVisionConversionModeSelector.selectedMode(
                sourceProfile = resolvedProfile,
                preserveMappingEnabled = preserveMappingEnabled,
                allowDv5Conversion = allowDv5Conversion
            ) ?: DolbyVisionConversionModeSelector.MODE_PROFILE_8_1
            lastSelectedConversionMode.set(mode)
            return mode
        }
```

This keeps the current behavior but makes the mode decision directly testable.

- [ ] **Step 5: Run the selector test**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionConversionModeSelectorTest
```

Expected: all selector tests pass.

- [ ] **Step 6: Commit the mode-selection guard**

```bash
git add app/src/main/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelector.kt app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt app/src/test/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelectorTest.kt
git commit -m "test(player): pin dolby vision conversion modes"
```

---

### Task 2: Add Focused Rewriter Tests First

**Files:**
- Create: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionHevcSampleRewriterTest.kt`

- [ ] **Step 1: Write the failing unit test file**

Create `app/src/test/java/com/nexio/tv/core/player/DolbyVisionHevcSampleRewriterTest.kt` with:

```kotlin
package com.nexio.tv.core.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionHevcSampleRewriterTest {

    @Test
    fun `returns null and never calls converter when sample has only base layer nal units`() {
        var conversionCalls = 0
        val sample = lengthDelimitedSample(
            nal(type = 32, layerId = 0, payload = byteArrayOf(0x10, 0x11)),
            nal(type = 19, layerId = 0, payload = byteArrayOf(0x20, 0x21, 0x22))
        )
        val metrics = DolbyVisionHevcSampleRewriter.Metrics()

        val rewritten = DolbyVisionHevcSampleRewriter.rewriteLengthDelimitedSample(
            sampleLengthDelimited = sample,
            nalUnitLengthFieldLength = 4,
            conversionMode = 2,
            metrics = metrics,
            convertRpu = { _, _ ->
                conversionCalls++
                error("ordinary base-layer NALs must not be converted")
            }
        )

        assertNull(rewritten)
        assertEquals(0, conversionCalls)
        assertEquals(1L, metrics.sampleCalls)
        assertEquals(sample.size.toLong(), metrics.inputBytes)
        assertEquals(0L, metrics.outputBytes)
        assertEquals(0L, metrics.sourceCopyBytes)
        assertEquals(0L, metrics.rpuInputBytes)
        assertEquals(0L, metrics.droppedBytes)
    }

    @Test
    fun `drops enhancement layer nal units and converts only rpu nal units`() {
        val baseVps = nal(type = 32, layerId = 0, payload = byteArrayOf(0x01))
        val enhancementSlice = nal(type = 1, layerId = 1, payload = byteArrayOf(0x02, 0x03, 0x04))
        val rpu = nal(type = 62, layerId = 1, payload = byteArrayOf(0x05, 0x06))
        val baseSlice = nal(type = 19, layerId = 0, payload = byteArrayOf(0x07, 0x08))
        val convertedRpu = nal(type = 62, layerId = 1, payload = byteArrayOf(0x55, 0x66, 0x77))
        val expectedNormalizedRpu = nal(type = 62, layerId = 0, payload = byteArrayOf(0x55, 0x66, 0x77))
        val sample = lengthDelimitedSample(baseVps, enhancementSlice, rpu, baseSlice)
        val expected = lengthDelimitedSample(baseVps, expectedNormalizedRpu, baseSlice)
        val convertedInputs = mutableListOf<ByteArray>()
        val metrics = DolbyVisionHevcSampleRewriter.Metrics()

        val rewritten = DolbyVisionHevcSampleRewriter.rewriteLengthDelimitedSample(
            sampleLengthDelimited = sample,
            nalUnitLengthFieldLength = 4,
            conversionMode = 2,
            metrics = metrics,
            convertRpu = { payload, mode ->
                assertEquals(2, mode)
                convertedInputs += payload
                convertedRpu
            }
        )

        assertArrayEquals(expected, rewritten)
        assertEquals(1, convertedInputs.size)
        assertArrayEquals(rpu, convertedInputs.single())
        assertEquals(1L, metrics.sampleCalls)
        assertEquals(sample.size.toLong(), metrics.inputBytes)
        assertEquals(expected.size.toLong(), metrics.outputBytes)
        assertEquals((baseVps.size + baseSlice.size).toLong(), metrics.sourceCopyBytes)
        assertEquals(rpu.size.toLong(), metrics.rpuInputBytes)
        assertEquals(convertedRpu.size.toLong(), metrics.rpuOutputBytes)
        assertEquals(enhancementSlice.size.toLong(), metrics.droppedBytes)
    }

    @Test
    fun `returns null for malformed length-delimited sample`() {
        var conversionCalls = 0
        val malformed = byteArrayOf(
            0x00, 0x00, 0x00, 0x10,
            0x40, 0x01, 0x10
        )
        val metrics = DolbyVisionHevcSampleRewriter.Metrics()

        val rewritten = DolbyVisionHevcSampleRewriter.rewriteLengthDelimitedSample(
            sampleLengthDelimited = malformed,
            nalUnitLengthFieldLength = 4,
            conversionMode = 2,
            metrics = metrics,
            convertRpu = { _, _ ->
                conversionCalls++
                byteArrayOf()
            }
        )

        assertNull(rewritten)
        assertEquals(0, conversionCalls)
        assertEquals(1L, metrics.sampleCalls)
        assertEquals(malformed.size.toLong(), metrics.inputBytes)
        assertEquals(0L, metrics.outputBytes)
    }

    @Test
    fun `normalizes converted rpu layer id to zero`() {
        val rpuLayerOne = nal(type = 62, layerId = 1, payload = byteArrayOf(0x01))
        val sample = lengthDelimitedSample(rpuLayerOne)

        val rewritten = DolbyVisionHevcSampleRewriter.rewriteLengthDelimitedSample(
            sampleLengthDelimited = sample,
            nalUnitLengthFieldLength = 4,
            conversionMode = 5,
            metrics = DolbyVisionHevcSampleRewriter.Metrics(),
            convertRpu = { payload, mode ->
                assertEquals(5, mode)
                payload
            }
        ) ?: error("RPU layer normalization should rewrite the sample")

        val normalizedRpu = nal(type = 62, layerId = 0, payload = byteArrayOf(0x01))
        assertArrayEquals(lengthDelimitedSample(normalizedRpu), rewritten)
    }

    private fun lengthDelimitedSample(vararg nals: ByteArray): ByteArray {
        val total = nals.sumOf { 4 + it.size }
        val out = ByteArray(total)
        var offset = 0
        for (nal in nals) {
            writeLength(out, offset, nal.size)
            offset += 4
            System.arraycopy(nal, 0, out, offset, nal.size)
            offset += nal.size
        }
        return out
    }

    private fun writeLength(out: ByteArray, offset: Int, value: Int) {
        out[offset] = ((value ushr 24) and 0xFF).toByte()
        out[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 3] = (value and 0xFF).toByte()
    }

    private fun nal(type: Int, layerId: Int, payload: ByteArray): ByteArray {
        require(type in 0..63)
        require(layerId in 0..63)
        val out = ByteArray(2 + payload.size)
        out[0] = ((type shl 1) or ((layerId ushr 5) and 0x01)).toByte()
        out[1] = (((layerId and 0x1F) shl 3) or 0x01).toByte()
        System.arraycopy(payload, 0, out, 2, payload.size)
        assertTrue("test NAL must include temporal_id_plus1", (out[1].toInt() and 0x07) != 0)
        return out
    }
}
```

- [ ] **Step 2: Run the test and verify it fails because the helper does not exist**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionHevcSampleRewriterTest
```

Expected: compilation fails with unresolved reference `DolbyVisionHevcSampleRewriter`.

- [ ] **Step 3: Commit the failing tests**

```bash
git add app/src/test/java/com/nexio/tv/core/player/DolbyVisionHevcSampleRewriterTest.kt
git commit -m "test(player): cover dolby vision sample rewriting"
```

---

### Task 3: Implement Allocation-Lean Sample Rewriter

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/player/DolbyVisionHevcSampleRewriter.kt`
- Test: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionHevcSampleRewriterTest.kt`

- [ ] **Step 1: Create the pure helper**

Create `app/src/main/java/com/nexio/tv/core/player/DolbyVisionHevcSampleRewriter.kt`:

```kotlin
package com.nexio.tv.core.player

internal object DolbyVisionHevcSampleRewriter {
    private const val NAL_TYPE_UNSPEC62 = 62

    data class Metrics(
        var sampleCalls: Long = 0L,
        var inputBytes: Long = 0L,
        var outputBytes: Long = 0L,
        var sourceCopyBytes: Long = 0L,
        var rpuInputBytes: Long = 0L,
        var rpuOutputBytes: Long = 0L,
        var droppedBytes: Long = 0L
    )

    fun rewriteLengthDelimitedSample(
        sampleLengthDelimited: ByteArray,
        nalUnitLengthFieldLength: Int,
        conversionMode: Int,
        metrics: Metrics? = null,
        convertRpu: (payload: ByteArray, mode: Int) -> ByteArray?
    ): ByteArray? {
        if (nalUnitLengthFieldLength !in 1..4) return null
        metrics?.sampleCalls = (metrics?.sampleCalls ?: 0L) + 1L
        metrics?.inputBytes = (metrics?.inputBytes ?: 0L) + sampleLengthDelimited.size.toLong()

        var offset = 0
        var changed = false
        var outputPayloadBytes = 0
        val entries = ArrayList<OutputNal>(8)

        while (offset + nalUnitLengthFieldLength <= sampleLengthDelimited.size) {
            val nalSize = readLengthField(sampleLengthDelimited, offset, nalUnitLengthFieldLength)
            if (nalSize < 0) return null
            offset += nalUnitLengthFieldLength
            if (offset + nalSize > sampleLengthDelimited.size) return null
            if (nalSize == 0) {
                entries += OutputNal.Source(offset = offset, length = 0)
                outputPayloadBytes += nalUnitLengthFieldLength
                offset += nalSize
                continue
            }

            val nalType = getNalUnitType(sampleLengthDelimited, offset, nalSize)
            val layerId = getNuhLayerId(sampleLengthDelimited, offset, nalSize)
            when {
                layerId > 0 && nalType != NAL_TYPE_UNSPEC62 -> {
                    changed = true
                    metrics?.droppedBytes = (metrics?.droppedBytes ?: 0L) + nalSize.toLong()
                }
                nalType == NAL_TYPE_UNSPEC62 -> {
                    val rpuCopy = sampleLengthDelimited.copyOfRange(offset, offset + nalSize)
                    metrics?.rpuInputBytes = (metrics?.rpuInputBytes ?: 0L) + rpuCopy.size.toLong()
                    val converted = convertRpu(rpuCopy, conversionMode)
                        ?.takeIf { it.isNotEmpty() }
                        ?: rpuCopy
                    val normalized = normalizeNuhLayerIdToZero(converted)
                    metrics?.rpuOutputBytes = (metrics?.rpuOutputBytes ?: 0L) + normalized.size.toLong()
                    if (normalized !== rpuCopy || converted !== rpuCopy || !bytesEqualAt(sampleLengthDelimited, offset, normalized)) {
                        changed = true
                    }
                    entries += OutputNal.Owned(normalized)
                    outputPayloadBytes += nalUnitLengthFieldLength + normalized.size
                }
                else -> {
                    entries += OutputNal.Source(offset = offset, length = nalSize)
                    outputPayloadBytes += nalUnitLengthFieldLength + nalSize
                }
            }
            offset += nalSize
        }

        if (offset != sampleLengthDelimited.size) return null
        if (!changed) return null
        if (entries.isEmpty() || outputPayloadBytes <= 0) return null

        val out = ByteArray(outputPayloadBytes)
        var outOffset = 0
        for (entry in entries) {
            val payloadLength = entry.length
            if (!writeLengthField(out, outOffset, payloadLength, nalUnitLengthFieldLength)) return null
            outOffset += nalUnitLengthFieldLength
            when (entry) {
                is OutputNal.Source -> {
                    System.arraycopy(sampleLengthDelimited, entry.offset, out, outOffset, entry.length)
                    metrics?.sourceCopyBytes =
                        (metrics?.sourceCopyBytes ?: 0L) + entry.length.toLong()
                }
                is OutputNal.Owned -> {
                    System.arraycopy(entry.bytes, 0, out, outOffset, entry.bytes.size)
                }
            }
            outOffset += payloadLength
        }

        if (outOffset != out.size) return null
        metrics?.outputBytes = (metrics?.outputBytes ?: 0L) + out.size.toLong()
        return out
    }

    private sealed class OutputNal {
        abstract val length: Int

        data class Source(val offset: Int, override val length: Int) : OutputNal()
        data class Owned(val bytes: ByteArray) : OutputNal() {
            override val length: Int = bytes.size
        }
    }

    private fun readLengthField(data: ByteArray, offset: Int, lengthBytes: Int): Int {
        var value = 0
        for (i in 0 until lengthBytes) {
            value = (value shl 8) or (data[offset + i].toInt() and 0xFF)
        }
        return value
    }

    private fun writeLengthField(out: ByteArray, offset: Int, value: Int, lengthBytes: Int): Boolean {
        if (value < 0) return false
        val maxNalSize = when (lengthBytes) {
            1 -> 0xFF
            2 -> 0xFFFF
            3 -> 0xFFFFFF
            4 -> Int.MAX_VALUE
            else -> return false
        }
        if (value > maxNalSize || offset + lengthBytes > out.size) return false
        for (shift in (lengthBytes - 1) downTo 0) {
            out[offset + (lengthBytes - 1 - shift)] = ((value ushr (shift * 8)) and 0xFF).toByte()
        }
        return true
    }

    private fun getNalUnitType(data: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0 || offset !in data.indices) return -1
        return (data[offset].toInt() ushr 1) and 0x3F
    }

    private fun getNuhLayerId(data: ByteArray, offset: Int, length: Int): Int {
        if (length < 2 || offset + 1 >= data.size) return 0
        val b0 = data[offset].toInt() and 0x01
        val b1 = data[offset + 1].toInt() and 0xF8
        return (b0 shl 5) or (b1 ushr 3)
    }

    private fun normalizeNuhLayerIdToZero(nalPayload: ByteArray): ByteArray {
        if (nalPayload.size < 2) return nalPayload
        val layerId = getNuhLayerId(nalPayload, 0, nalPayload.size)
        if (layerId == 0) return nalPayload
        val out = nalPayload.copyOf()
        out[0] = (out[0].toInt() and 0xFE).toByte()
        out[1] = (out[1].toInt() and 0x07).toByte()
        return out
    }

    private fun bytesEqualAt(source: ByteArray, sourceOffset: Int, candidate: ByteArray): Boolean {
        if (sourceOffset + candidate.size > source.size) return false
        for (i in candidate.indices) {
            if (source[sourceOffset + i] != candidate[i]) return false
        }
        return true
    }
}
```

- [ ] **Step 2: Run the focused test**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionHevcSampleRewriterTest
```

Expected: all tests pass.

- [ ] **Step 3: Commit the helper**

```bash
git add app/src/main/java/com/nexio/tv/core/player/DolbyVisionHevcSampleRewriter.kt app/src/test/java/com/nexio/tv/core/player/DolbyVisionHevcSampleRewriterTest.kt
git commit -m "perf(player): add allocation-lean dolby sample rewriter"
```

---

### Task 4: Wire Rewriter Into Existing Media3 Hook

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt`
- Test: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionHevcSampleRewriterTest.kt`

- [ ] **Step 1: Replace heavy `rewriteMp4HevcSample(...)` implementation**

In `MatroskaDolbyVisionHookInstaller.kt`, remove:

```kotlin
import java.io.ByteArrayOutputStream
```

Replace the body of `rewriteMp4HevcSample(...)` with:

```kotlin
    private fun rewriteMp4HevcSample(
        sampleLengthDelimited: ByteArray,
        nalUnitLengthFieldLength: Int,
        conversionMode: Int
    ): ByteArray? {
        val metrics = if (diagnosticsEnabled) {
            DolbyVisionHevcSampleRewriter.Metrics()
        } else {
            null
        }
        val rewritten = DolbyVisionHevcSampleRewriter.rewriteLengthDelimitedSample(
            sampleLengthDelimited = sampleLengthDelimited,
            nalUnitLengthFieldLength = nalUnitLengthFieldLength,
            conversionMode = conversionMode,
            metrics = metrics,
            convertRpu = { nalPayload, mode ->
                DoviBridge.convertDv7RpuToDv81(nalPayload, mode = mode)
            }
        )
        if (metrics != null) {
            rewriteSampleCalls.addAndGet(metrics.sampleCalls)
            rewriteInputBytes.addAndGet(metrics.inputBytes)
            rewriteOutputBytes.addAndGet(metrics.outputBytes)
            nalCopyBytes.addAndGet(metrics.sourceCopyBytes + metrics.rpuInputBytes + metrics.rpuOutputBytes)
        }
        return rewritten
    }
```

- [ ] **Step 2: Delete now-unused private helpers from `MatroskaDolbyVisionHookInstaller.kt`**

Remove these private functions from `MatroskaDolbyVisionHookInstaller.kt` only if no remaining code in that file uses them:

```kotlin
private fun writeLengthField(out: ByteArrayOutputStream, value: Int, lengthBytes: Int): Boolean
private fun normalizeNuhLayerIdToZero(nalPayload: ByteArray): ByteArray
```

Keep these functions if they are still used by non-sample paths:

```kotlin
private fun readLengthField(data: ByteArray, offset: Int, lengthBytes: Int): Int
private fun getNalUnitType(nalPayload: ByteArray): Int
private fun getNalUnitTypeOrMinusOne(nalPayload: ByteArray): Int
private fun getNuhLayerId(nalPayload: ByteArray): Int
```

Reason: `tapRpuFromLengthDelimitedSample(...)`, `tapPotentialRpuNal(...)`, and `maybeConvertDolbyVisionRpuNal(...)` still use some of the old helpers.

- [ ] **Step 3: Run focused tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.core.player.DolbyVisionHevcSampleRewriterTest \
  --tests com.nexio.tv.core.player.DolbyVisionConversionModeSelectorTest \
  --tests com.nexio.tv.core.player.DolbyVisionDiagnosticsTest \
  --tests com.nexio.tv.core.player.DolbyVisionAutoPlayGateTest
```

Expected: all selected tests pass.

- [ ] **Step 4: Commit the hook integration**

```bash
git add app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt app/src/main/java/com/nexio/tv/core/player/DolbyVisionHevcSampleRewriter.kt
git commit -m "perf(player): reduce dolby vision sample rewrite allocations"
```

---

### Task 5: Add Regression Test For Diagnostics Semantics

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionHevcSampleRewriterTest.kt`

- [ ] **Step 1: Add a test that proves ordinary NALs are not passed to the converter**

Append this test to `DolbyVisionHevcSampleRewriterTest`:

```kotlin
    @Test
    fun `converter receives only rpu payloads even when enhancement layer slices exist`() {
        val ordinaryBase = nal(type = 19, layerId = 0, payload = byteArrayOf(0x01, 0x02))
        val enhancement = nal(type = 1, layerId = 1, payload = byteArrayOf(0x03, 0x04))
        val rpuA = nal(type = 62, layerId = 1, payload = byteArrayOf(0x05))
        val rpuB = nal(type = 62, layerId = 0, payload = byteArrayOf(0x06))
        val sample = lengthDelimitedSample(ordinaryBase, enhancement, rpuA, rpuB)
        val seen = mutableListOf<ByteArray>()

        val rewritten = DolbyVisionHevcSampleRewriter.rewriteLengthDelimitedSample(
            sampleLengthDelimited = sample,
            nalUnitLengthFieldLength = 4,
            conversionMode = 2,
            metrics = DolbyVisionHevcSampleRewriter.Metrics(),
            convertRpu = { payload, _ ->
                seen += payload
                payload
            }
        )

        val expected = lengthDelimitedSample(
            ordinaryBase,
            nal(type = 62, layerId = 0, payload = byteArrayOf(0x05)),
            rpuB
        )
        assertArrayEquals(expected, rewritten)
        assertEquals(2, seen.size)
        assertArrayEquals(rpuA, seen[0])
        assertArrayEquals(rpuB, seen[1])
    }
```

- [ ] **Step 2: Run focused tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionHevcSampleRewriterTest
```

Expected: all tests pass.

- [ ] **Step 3: Commit diagnostics regression coverage**

```bash
git add app/src/test/java/com/nexio/tv/core/player/DolbyVisionHevcSampleRewriterTest.kt
git commit -m "test(player): pin dolby rpu-only conversion behavior"
```

---

### Task 6: Validate Build And Runtime Diagnostics

**Files:**
- No source changes expected.

- [ ] **Step 1: Run JVM test suite subset**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.core.player.DolbyVisionHevcSampleRewriterTest \
  --tests com.nexio.tv.core.player.DolbyVisionConversionModeSelectorTest \
  --tests com.nexio.tv.core.player.DolbyVisionDiagnosticsTest \
  --tests com.nexio.tv.core.player.DolbyVisionAutoPlayGateTest \
  --tests com.nexio.tv.core.player.FfmpegDolbyVisionProfileProbeTest
```

Expected: all selected tests pass.

- [ ] **Step 2: Build the profileable/release target used for device profiling**

Run the same build command used for the current profileable package in this branch. If the branch still defines the `universalProfileable` variant, run:

```bash
./gradlew -q :app:assembleUniversalProfileable
```

If that variant is not present, run:

```bash
./gradlew -q :app:assembleUniversalRelease
```

Expected: selected assemble task completes without compilation errors.

- [ ] **Step 3: Device verification with diagnostics enabled**

Install the built package according to the branch’s existing build/install flow. During the same 131GB / ~93Mbps DV7 stream scenario, enable:

- Playback Settings -> Troubleshooting -> Enable Playback Buffer Diagnostics
- Playback Settings -> Troubleshooting -> Enable Dolby Vision Diagnostics

Collect logs:

```bash
adb -s 192.168.50.58:5555 shell pidof com.nexio.tv.profileable
adb -s 192.168.50.58:5555 logcat -d -v threadtime --pid=<PID> -t 5000 | rg "BUFFER:|Background concurrent mark compact GC|nativeConvertDv7RpuToDv81 converted"
adb -s 192.168.50.58:5555 shell top -H -p <PID> -b -n 1 -m 12
adb -s 192.168.50.58:5555 shell dumpsys meminfo com.nexio.tv.profileable
```

Expected:

- Playback remains `PLAYING` with `error=null` in `dumpsys media_session`.
- `BUFFER:` logs still show `dv7dovi=on`, `sourceProfile=7`, `mode=2` or `mode=5` according to the existing setting, and successful conversion calls.
- `rewriteCalls` continues to grow, because samples still need transformation.
- `rewriteInMb` can continue to reflect source sample volume.
- `nalCopyMb` should drop sharply because ordinary base-layer NALs are no longer copied into temporary per-NAL arrays.
- `HeapTaskDaemon` CPU and GC cadence should improve compared with the captured baseline: about 38 GCs per recent tail sample and `HeapTaskDaemon` around 56% CPU.

- [ ] **Step 4: Commit validation notes only if a durable note file already exists**

Do not create a new permanent benchmark artifact unless the repo already has a current performance notes file for this work. If adding notes is appropriate, use the existing performance notes path and commit:

```bash
git add <existing-performance-notes-file>
git commit -m "docs(player): record dolby rewrite allocation validation"
```

If there is no existing notes file, skip this commit and include the validation numbers in the final response.

---

## Non-Goals

- Do not change `DoviBridge.convertDv7RpuToDv81(...)` or native `libdovi` mode mapping.
- Do not remove DV7 to DV8.1 conversion.
- Do not change profile 7 mode selection, including the existing mapping-preservation setting.
- Do not alter block-additional RPU append behavior in this optimization pass.
- Do not change disk spool behavior in this plan.
- Do not rename persisted preference keys as part of this plan.

## Self-Review

Spec coverage:

- Online research requirement covered in “Research Findings.”
- Preserve DV7 to DV8.1 conversion covered by invariants, tests, and non-goals.
- Surgical optimization target covered by Tasks 1-3.
- Runtime validation covered by Task 6.

Placeholder scan:

- No “TBD,” “TODO,” “implement later,” or undefined later-step functions remain.

Type consistency:

- The helper is consistently named `DolbyVisionHevcSampleRewriter`.
- The public helper method is consistently named `rewriteLengthDelimitedSample`.
- Metrics fields used in tests match the helper definition.
