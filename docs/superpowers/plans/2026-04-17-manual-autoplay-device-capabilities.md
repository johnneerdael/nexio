# Manual Autoplay Device Capabilities Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make deterministic autoplay fully manual-bitrate based while preserving device-capability-aware codec, HDR, and audio scoring without requiring provider benchmarks.

**Architecture:** Move device capability capture/persistence out of the benchmark flow into its own startup-warmed store. Refactor autoplay scoring so the manual path uses the same content/device scoring as benchmark-aware autoplay, with manual bitrate as the only transport budget input. Remove benchmark-aware autoplay modes, settings, and reachable benchmark UI paths from the app.

**Tech Stack:** Kotlin, Android DataStore Preferences, Hilt, Jetpack Compose for TV settings, JUnit4, coroutine test utilities, Gradle Android unit tests.

---

## Scope Decision

This plan removes benchmark-aware autoplay from the app. It also removes the user-facing provider benchmark rows and benchmark data-collection toggle from settings because they only exist to support the old benchmark-autoplay workflow. Low-level benchmark classes can be left in source temporarily if other compile-time helpers still reference their models, but no UI, startup injection, or autoplay path should depend on running or reading a provider benchmark.

## File Structure

- Create `app/src/main/java/com/nexio/tv/data/local/DeviceCapabilityStore.kt`
  - Persist one `DeviceCapabilitySnapshot` JSON string in a dedicated DataStore.
  - Expose `latestSnapshot: Flow<DeviceCapabilitySnapshot?>`, `save(snapshot)`, and `clear()`.
- Create `app/src/main/java/com/nexio/tv/data/repository/device/DeviceCapabilityRepository.kt`
  - Provide startup behavior: read existing disk snapshot, capture and save only when absent.
  - Provide `snapshotForAutoplay(): DeviceCapabilitySnapshot?` for stream scoring.
- Create `app/src/main/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilitySnapshotJson.kt`
  - Own JSON serialization/deserialization for `DeviceCapabilitySnapshot`.
  - Reuse `DeviceCapabilitySnapshot.toJsonObject()` shape from `BenchmarkResultJsonLogger.kt`.
- Modify `app/src/main/java/com/nexio/tv/data/local/DebridBenchmarkStore.kt`
  - Replace its private device snapshot parser with the shared parser.
- Modify `app/src/main/java/com/nexio/tv/MainActivity.kt`
  - Inject `DeviceCapabilityRepository`.
  - Warm the device capability cache once during app startup.
  - Remove `DebridBenchmarkService` injection when benchmark UI is removed.
- Modify `app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt`
  - Rename or extend manual scoring so it accepts `DeviceCapabilitySnapshot?`.
  - Ensure manual scoring passes device into codec/HDR/audio content scoring.
  - Keep manual bitrate as the only transport budget.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`
  - Inject `DeviceCapabilityRepository` instead of `DebridBenchmarkStore`.
  - Remove `latestValidBenchmarkSessions`, `latestBenchmarkSessions`, `hasValidBenchmark`, `AutoplayBandwidthMode`, and benchmark branch logic.
  - Always score deterministic autoplay through manual-bitrate + device-capability path.
- Modify `ShadowAutoPlayReplayCoordinator` in `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`
  - Store latest device snapshot.
  - Build shadow autoplay events through the same manual device-aware scorer.
- Modify `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
  - Remove `AutoplayBandwidthMode` from active settings.
  - Keep preference migration that maps old AUTO/manual values to manual mode and `manualBitrateLimitMbps`.
  - Remove `autoplayMaxBitrateEnabled` and `autoplayMaxBitrateMbps` from active `PlayerSettings` if no remaining non-autoplay feature uses them.
- Modify settings UI files:
  - `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAutoPlaySettings.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailability.kt`
  - Remove Auto (Benchmark), benchmark availability gating, provider benchmark rows, benchmark dialogs, and benchmark data collection toggles.
- Modify strings in `app/src/main/res/values/strings.xml`
  - Remove or rewrite benchmark/autoplay benchmark copy.
  - Keep manual bitrate copy.
- Update tests:
  - `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt`
  - `app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareScoringHarnessTest.kt`
  - `app/src/test/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilitySnapshotProviderTest.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailabilityTest.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModelDeterministicAutoplayTest.kt`
  - Add tests for device store, startup warm behavior, and manual scorer device-awareness.

## Current Code Facts

- `DeviceCapabilitySnapshotProvider.capture(playerSettings)` already captures display HDR, decoder, and audio output capabilities.
- `DebridBenchmarkResult.device` currently carries those capabilities into `BenchmarkAwareStreamScorer.score`.
- `BenchmarkAwareStreamScorer.evaluateStreamWithManualCap` currently passes `device = null` into `buildContentScoreBreakdown`, so manual autoplay ignores device capabilities.
- `StreamScreenViewModel` currently chooses benchmark scoring only when valid provider benchmarks exist; otherwise it uses manual scoring.
- `PlaybackAutoPlaySettings` currently exposes `AutoplayBandwidthMode.AUTO` as `Auto (Benchmark)`.
- `DebridSettingsContent` currently shows provider benchmark rows and benchmark data-collection toggles.

---

### Task 1: Extract Device Capability Snapshot JSON

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilitySnapshotJson.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/DebridBenchmarkStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilitySnapshotJsonTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilitySnapshotJsonTest.kt`:

```kotlin
package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitySnapshotJsonTest {

    @Test
    fun `device snapshot json round trips capability fields`() {
        val snapshot = deviceSnapshot()

        val restored = parseDeviceCapabilitySnapshotJson(
            snapshot.toJsonObject().toString()
        )

        assertEquals(snapshot.model, restored?.model)
        assertEquals(snapshot.manufacturer, restored?.manufacturer)
        assertEquals(snapshot.sdkInt, restored?.sdkInt)
        assertEquals(snapshot.displayHdrTypes, restored?.displayHdrTypes)
        assertTrue(restored?.videoDecode?.hevc?.hardwareAccelerated == true)
        assertFalse(restored?.videoDecode?.av1?.hardwareAccelerated == true)
        assertTrue(restored?.audioOutput?.eac3?.supported == true)
        assertFalse(restored?.audioOutput?.truehd?.supported == true)
        assertEquals(snapshot.capturedAtMs, restored?.capturedAtMs)
    }

    @Test
    fun `invalid device snapshot json returns null`() {
        assertEquals(null, parseDeviceCapabilitySnapshotJson("{\"sdkInt\":0}"))
        assertEquals(null, parseDeviceCapabilitySnapshotJson("not-json"))
    }

    private fun deviceSnapshot(): DeviceCapabilitySnapshot {
        return DeviceCapabilitySnapshot(
            model = "AM9 PRO",
            manufacturer = "UGOOS",
            sdkInt = 34,
            displayHdrTypes = setOf(DeviceHdrType.HDR10),
            videoDecode = DeviceVideoDecodeCapabilities(
                h264 = CodecSupport(true, false, true),
                hevc = CodecSupport(true, false, true),
                av1 = CodecSupport(false, true, false),
                dolbyVision = CodecSupport(false, false, false)
            ),
            audioOutput = DeviceAudioOutputCapabilities(
                ac3 = AudioEncodingSupport(true, true),
                eac3 = AudioEncodingSupport(true, true),
                atmos = AudioEncodingSupport(true, true),
                truehd = AudioEncodingSupport(false, false),
                dts = AudioEncodingSupport(false, false),
                dtshd = AudioEncodingSupport(false, false),
                dtsx = AudioEncodingSupport(false, false)
            ),
            capturedAtMs = 123_456L
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshotJsonTest"
```

Expected: FAIL during compilation because `parseDeviceCapabilitySnapshotJson` does not exist.

- [ ] **Step 3: Add shared parser implementation**

Create `app/src/main/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilitySnapshotJson.kt`:

```kotlin
package com.nexio.tv.data.repository.benchmark

import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal fun parseDeviceCapabilitySnapshotJson(raw: String): DeviceCapabilitySnapshot? {
    val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
    return parseDeviceCapabilitySnapshotBestEffort(root)
}

internal fun parseDeviceCapabilitySnapshotBestEffort(deviceJson: JsonObject): DeviceCapabilitySnapshot? {
    return runCatching { parseDeviceCapabilitySnapshot(deviceJson) }
        .recoverCatching { parseLegacyDeviceCapabilitySnapshot(deviceJson) }
        .getOrNull()
}

private fun parseDeviceCapabilitySnapshot(deviceJson: JsonObject): DeviceCapabilitySnapshot {
    return DeviceCapabilitySnapshot(
        model = deviceJson.stringOrNull("model"),
        manufacturer = deviceJson.stringOrNull("manufacturer"),
        sdkInt = deviceJson.strictIntegralIntOrNull("sdkInt")?.takeIf { it > 0 }
            ?: throw InvalidDeviceCapabilityPayload,
        displayHdrTypes = deviceJson.arrayOrEmpty("displayHdrTypes").map { hdrType ->
            hdrType.asStringOrThrow().let(DeviceHdrType::fromWireKey)
                ?: throw InvalidDeviceCapabilityPayload
        }.toSet(),
        videoDecode = deviceJson.requiredObject("videoDecode").let(::parseVideoDecode),
        audioOutput = deviceJson.requiredObject("audioOutput").let(::parseAudioOutput),
        evidence = deviceJson.optionalObject("evidence")?.let(::parseDeviceCapabilityEvidence),
        capturedAtMs = deviceJson.strictIntegralLongOrNull("capturedAtMs")?.takeIf { it > 0L }
            ?: throw InvalidDeviceCapabilityPayload
    )
}

private fun parseLegacyDeviceCapabilitySnapshot(deviceJson: JsonObject): DeviceCapabilitySnapshot {
    return DeviceCapabilitySnapshot(
        model = deviceJson.stringOrNull("model"),
        manufacturer = deviceJson.stringOrNull("manufacturer"),
        sdkInt = deviceJson.strictIntegralIntOrNull("sdkInt")?.takeIf { it > 0 }
            ?: throw InvalidDeviceCapabilityPayload,
        displayHdrTypes = deviceJson.arrayOrEmpty("displayHdrTypes").map { hdrType ->
            hdrType.asStringOrThrow().let(DeviceHdrType::fromWireKey)
                ?: throw InvalidDeviceCapabilityPayload
        }.toSet(),
        videoDecode = deviceJson.requiredObject("videoDecode").let(::parseVideoDecode),
        audioOutput = deviceJson.requiredObject("audioOutput").let(::parseAudioOutput),
        evidence = null,
        capturedAtMs = deviceJson.strictIntegralLongOrNull("capturedAtMs")?.takeIf { it > 0L }
            ?: throw InvalidDeviceCapabilityPayload
    )
}

private fun parseDeviceCapabilityEvidence(evidenceJson: JsonObject): DeviceCapabilityEvidence {
    return DeviceCapabilityEvidence(
        hdr = evidenceJson.optionalObject("hdr")?.let(::parseHdrEvidence),
        audio = evidenceJson.optionalObject("audio")?.let(::parseAudioEvidence),
        video = evidenceJson.optionalObject("video")?.let(::parseVideoEvidence)
    )
}

private fun parseHdrEvidence(hdrJson: JsonObject): DeviceHdrCapabilityEvidence {
    return DeviceHdrCapabilityEvidence(
        displayId = hdrJson.optionalStrictIntegralIntOrNull("displayId"),
        rawSupportedHdrTypes = hdrJson.arrayOrEmpty("rawSupportedHdrTypes").map { it.asStringOrThrow() }
    )
}

private fun parseAudioEvidence(audioJson: JsonObject): DeviceAudioCapabilityEvidence {
    return DeviceAudioCapabilityEvidence(
        discoveryMode = audioJson.stringOrNull("discoveryMode"),
        routedDeviceTypes = audioJson.arrayOrEmpty("routedDeviceTypes").map { it.asStringOrThrow() },
        outputDevices = audioJson.arrayOrEmpty("outputDevices").map { parseAudioOutputDeviceEvidence(it.asJsonObjectOrThrow()) },
        directProfiles = audioJson.arrayOrEmpty("directProfiles").map { parseAudioDirectProfileEvidence(it.asJsonObjectOrThrow()) },
        directPlaybackProbes = audioJson.arrayOrEmpty("directPlaybackProbes").map { parseAudioPlaybackProbeEvidence(it.asJsonObjectOrThrow()) }
    )
}

private fun parseAudioOutputDeviceEvidence(deviceJson: JsonObject): AudioOutputDeviceEvidence {
    return AudioOutputDeviceEvidence(
        id = deviceJson.optionalStrictIntegralIntOrNull("id"),
        type = deviceJson.stringOrNull("type") ?: throw InvalidDeviceCapabilityPayload,
        productName = deviceJson.stringOrNull("productName"),
        encodings = deviceJson.arrayOrEmpty("encodings").map { it.asStringOrThrow() }
    )
}

private fun parseAudioDirectProfileEvidence(profileJson: JsonObject): AudioDirectProfileEvidence {
    return AudioDirectProfileEvidence(
        format = profileJson.stringOrNull("format") ?: throw InvalidDeviceCapabilityPayload,
        channelMasks = profileJson.arrayOrEmpty("channelMasks").map { it.asIntegralIntOrThrow() },
        sampleRates = profileJson.arrayOrEmpty("sampleRates").map { it.asIntegralIntOrThrow() }
    )
}

private fun parseAudioPlaybackProbeEvidence(probeJson: JsonObject): AudioPlaybackProbeEvidence {
    return AudioPlaybackProbeEvidence(
        bucket = probeJson.stringOrNull("bucket") ?: throw InvalidDeviceCapabilityPayload,
        format = probeJson.stringOrNull("format") ?: throw InvalidDeviceCapabilityPayload,
        channelMask = probeJson.strictIntegralIntOrNull("channelMask") ?: throw InvalidDeviceCapabilityPayload,
        sampleRateHz = probeJson.strictIntegralIntOrNull("sampleRateHz") ?: throw InvalidDeviceCapabilityPayload,
        supportMode = probeJson.stringOrNull("supportMode") ?: throw InvalidDeviceCapabilityPayload
    )
}

private fun parseVideoEvidence(videoJson: JsonObject): DeviceVideoDecoderEvidence {
    return DeviceVideoDecoderEvidence(
        scannedDecoderCount = videoJson.optionalStrictIntegralIntOrNull("scannedDecoderCount") ?: 0,
        decoders = videoJson.arrayOrEmpty("decoders").map { parseVideoDecoderEvidence(it.asJsonObjectOrThrow()) }
    )
}

private fun parseVideoDecoderEvidence(decoderJson: JsonObject): VideoDecoderEvidence {
    return VideoDecoderEvidence(
        codecName = decoderJson.stringOrNull("codecName") ?: throw InvalidDeviceCapabilityPayload,
        mimeType = decoderJson.stringOrNull("mimeType") ?: throw InvalidDeviceCapabilityPayload,
        hardwareAccelerated = decoderJson.strictBooleanOrNull("hardwareAccelerated") ?: throw InvalidDeviceCapabilityPayload,
        softwareOnly = decoderJson.strictBooleanOrNull("softwareOnly") ?: throw InvalidDeviceCapabilityPayload,
        secureSupported = decoderJson.strictBooleanOrNull("secureSupported") ?: throw InvalidDeviceCapabilityPayload
    )
}

private fun parseVideoDecode(videoDecodeJson: JsonObject): DeviceVideoDecodeCapabilities {
    return DeviceVideoDecodeCapabilities(
        h264 = videoDecodeJson.optionalObject("h264")?.let(::parseCodecSupport),
        hevc = videoDecodeJson.optionalObject("hevc")?.let(::parseCodecSupport),
        av1 = videoDecodeJson.optionalObject("av1")?.let(::parseCodecSupport),
        dolbyVision = videoDecodeJson.optionalObject("dolbyVision")?.let(::parseCodecSupport)
    )
}

private fun parseCodecSupport(codecJson: JsonObject): CodecSupport {
    return CodecSupport(
        hardwareAccelerated = codecJson.strictBooleanOrNull("hardwareAccelerated") ?: throw InvalidDeviceCapabilityPayload,
        softwareOnlyAvailable = codecJson.strictBooleanOrNull("softwareOnlyAvailable") ?: throw InvalidDeviceCapabilityPayload,
        secureSupported = codecJson.strictBooleanOrNull("secureSupported") ?: throw InvalidDeviceCapabilityPayload
    )
}

private fun parseAudioOutput(audioOutputJson: JsonObject): DeviceAudioOutputCapabilities {
    return DeviceAudioOutputCapabilities(
        ac3 = audioOutputJson.requiredObject("ac3").let(::parseAudioEncodingSupport),
        eac3 = audioOutputJson.requiredObject("eac3").let(::parseAudioEncodingSupport),
        atmos = audioOutputJson.optionalObject("atmos")?.let(::parseAudioEncodingSupport)
            ?: audioOutputJson.requiredObject("eac3").let(::parseAudioEncodingSupport),
        truehd = audioOutputJson.requiredObject("truehd").let(::parseAudioEncodingSupport),
        dts = audioOutputJson.requiredObject("dts").let(::parseAudioEncodingSupport),
        dtshd = audioOutputJson.requiredObject("dtshd").let(::parseAudioEncodingSupport),
        dtsx = audioOutputJson.optionalObject("dtsx")?.let(::parseAudioEncodingSupport)
            ?: audioOutputJson.requiredObject("dtshd").let(::parseAudioEncodingSupport)
    )
}

private fun parseAudioEncodingSupport(audioEncodingJson: JsonObject): AudioEncodingSupport {
    return AudioEncodingSupport(
        supported = audioEncodingJson.strictBooleanOrNull("supported") ?: throw InvalidDeviceCapabilityPayload,
        passthroughLikely = audioEncodingJson.strictBooleanOrNull("passthroughLikely") ?: throw InvalidDeviceCapabilityPayload
    )
}

private object InvalidDeviceCapabilityPayload : RuntimeException()

private fun JsonObject.stringOrNull(key: String): String? {
    return runCatching {
        get(key)?.takeIf { !it.isJsonNull }?.asString
    }.getOrNull()
}

private fun JsonObject.optionalObject(key: String): JsonObject? {
    val value = get(key) ?: return null
    if (value.isJsonNull) return null
    return value.takeIf { it.isJsonObject }?.asJsonObject ?: throw InvalidDeviceCapabilityPayload
}

private fun JsonObject.requiredObject(key: String): JsonObject {
    return optionalObject(key) ?: throw InvalidDeviceCapabilityPayload
}

private fun JsonObject.arrayOrEmpty(key: String) =
    get(key)?.let { value ->
        if (!value.isJsonArray) throw InvalidDeviceCapabilityPayload
        value.asJsonArray.asList()
    } ?: emptyList()

private fun JsonObject.strictIntegralLongOrNull(key: String): Long? {
    val primitive = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    if (!primitive.isNumber) return null
    val text = primitive.asString.trim()
    if (!text.matches(INTEGRAL_NUMBER_REGEX)) return null
    return text.toLongOrNull()
}

private fun JsonObject.strictIntegralIntOrNull(key: String): Int? {
    return strictIntegralLongOrNull(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}

private fun JsonObject.optionalStrictIntegralIntOrNull(key: String): Int? {
    if (!has(key) || get(key)?.isJsonNull == true) return null
    return strictIntegralIntOrNull(key)?.takeIf { it >= 0 } ?: throw InvalidDeviceCapabilityPayload
}

private fun JsonObject.strictBooleanOrNull(key: String): Boolean? {
    val primitive = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    if (!primitive.isBoolean) return null
    return primitive.asBoolean
}

private fun com.google.gson.JsonElement.asJsonObjectOrThrow(): JsonObject {
    return takeIf { it.isJsonObject }?.asJsonObject ?: throw InvalidDeviceCapabilityPayload
}

private fun com.google.gson.JsonElement.asStringOrThrow(): String {
    return takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: throw InvalidDeviceCapabilityPayload
}

private fun com.google.gson.JsonElement.asIntegralIntOrThrow(): Int {
    val primitive = takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: throw InvalidDeviceCapabilityPayload
    if (!primitive.isNumber) throw InvalidDeviceCapabilityPayload
    val text = primitive.asString.trim()
    if (!text.matches(INTEGRAL_NUMBER_REGEX)) throw InvalidDeviceCapabilityPayload
    return text.toIntOrNull() ?: throw InvalidDeviceCapabilityPayload
}

private val INTEGRAL_NUMBER_REGEX = Regex("^-?\\d+$")
```

- [ ] **Step 4: Replace private device parser in benchmark store**

Modify `app/src/main/java/com/nexio/tv/data/local/DebridBenchmarkStore.kt`.

Replace:

```kotlin
val device = root.optionalObject("device")?.let(::parseDeviceSnapshotBestEffort)
```

with:

```kotlin
val device = root.optionalObject("device")?.let { parseDeviceCapabilitySnapshotBestEffort(it) }
```

Add import:

```kotlin
import com.nexio.tv.data.repository.benchmark.parseDeviceCapabilitySnapshotBestEffort
```

Delete these private functions from `DebridBenchmarkStore` after the import compiles: `parseDeviceSnapshotBestEffort`, `parseDeviceSnapshot`, `parseLegacyDeviceSnapshot`, `parseDeviceCapabilityEvidence`, `parseHdrEvidence`, `parseAudioEvidence`, `parseAudioOutputDeviceEvidence`, `parseAudioDirectProfileEvidence`, `parseAudioPlaybackProbeEvidence`, `parseVideoEvidence`, `parseVideoDecoderEvidence`, `parseVideoDecode`, `parseCodecSupport`, `parseAudioOutput`, and `parseAudioEncodingSupport`.

Remove imports that are now unused from `DebridBenchmarkStore.kt`:

```kotlin
import com.nexio.tv.data.repository.benchmark.AudioEncodingSupport
import com.nexio.tv.data.repository.benchmark.AudioDirectProfileEvidence
import com.nexio.tv.data.repository.benchmark.AudioPlaybackProbeEvidence
import com.nexio.tv.data.repository.benchmark.AudioOutputDeviceEvidence
import com.nexio.tv.data.repository.benchmark.CodecSupport
import com.nexio.tv.data.repository.benchmark.DeviceAudioCapabilityEvidence
import com.nexio.tv.data.repository.benchmark.DeviceAudioOutputCapabilities
import com.nexio.tv.data.repository.benchmark.DeviceCapabilityEvidence
import com.nexio.tv.data.repository.benchmark.DeviceHdrCapabilityEvidence
import com.nexio.tv.data.repository.benchmark.DeviceVideoDecoderEvidence
import com.nexio.tv.data.repository.benchmark.DeviceVideoDecodeCapabilities
import com.nexio.tv.data.repository.benchmark.VideoDecoderEvidence
```

- [ ] **Step 5: Run tests**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshotJsonTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilitySnapshotJson.kt app/src/main/java/com/nexio/tv/data/local/DebridBenchmarkStore.kt app/src/test/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilitySnapshotJsonTest.kt
git commit -m "refactor: share device capability snapshot json"
```

---

### Task 2: Persist Device Capabilities Outside Benchmarks

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/local/DeviceCapabilityStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/DeviceCapabilityStoreTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/local/DeviceCapabilityStoreTest.kt`:

```kotlin
package com.nexio.tv.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nexio.tv.data.repository.benchmark.AudioEncodingSupport
import com.nexio.tv.data.repository.benchmark.CodecSupport
import com.nexio.tv.data.repository.benchmark.DeviceAudioOutputCapabilities
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshot
import com.nexio.tv.data.repository.benchmark.DeviceHdrType
import com.nexio.tv.data.repository.benchmark.DeviceVideoDecodeCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceCapabilityStoreTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher + Job())
    private val tempFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        tempFiles.forEach { it.deleteRecursively() }
        scope.cancel()
    }

    @Test
    fun `latest snapshot defaults to null`() = runTest(dispatcher) {
        val store = store()

        assertNull(store.latestSnapshot.first())
    }

    @Test
    fun `saved snapshot is restored`() = runTest(dispatcher) {
        val store = store()
        val snapshot = snapshot()

        store.save(snapshot)

        assertEquals(snapshot, store.latestSnapshot.first())
    }

    @Test
    fun `clear removes stored snapshot`() = runTest(dispatcher) {
        val store = store()
        store.save(snapshot())

        store.clear()

        assertNull(store.latestSnapshot.first())
    }

    private fun store(): DeviceCapabilityStore {
        val file = File.createTempFile("device-capability", ".preferences_pb").also {
            it.delete()
            tempFiles += it
        }
        return DeviceCapabilityStore(
            dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        )
    }

    private fun snapshot(): DeviceCapabilitySnapshot {
        return DeviceCapabilitySnapshot(
            model = "AM9 PRO",
            manufacturer = "UGOOS",
            sdkInt = 34,
            displayHdrTypes = setOf(DeviceHdrType.HDR10),
            videoDecode = DeviceVideoDecodeCapabilities(
                hevc = CodecSupport(true, false, true)
            ),
            audioOutput = DeviceAudioOutputCapabilities(
                eac3 = AudioEncodingSupport(true, true),
                truehd = AudioEncodingSupport(false, false)
            ),
            capturedAtMs = 42L
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.local.DeviceCapabilityStoreTest"
```

Expected: FAIL during compilation because `DeviceCapabilityStore` does not exist.

- [ ] **Step 3: Add store implementation**

Create `app/src/main/java/com/nexio/tv/data/local/DeviceCapabilityStore.kt`:

```kotlin
package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshot
import com.nexio.tv.data.repository.benchmark.parseDeviceCapabilitySnapshotJson
import com.nexio.tv.data.repository.benchmark.toJsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deviceCapabilityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "device_capability",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Singleton
class DeviceCapabilityStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.deviceCapabilityDataStore)

    val latestSnapshot: Flow<DeviceCapabilitySnapshot?> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences ->
            preferences[deviceCapabilitySnapshotKey]?.let(::parseDeviceCapabilitySnapshotJson)
        }
        .distinctUntilChanged()

    suspend fun save(snapshot: DeviceCapabilitySnapshot) {
        dataStore.edit { preferences ->
            preferences[deviceCapabilitySnapshotKey] = snapshot.toJsonObject().toString()
        }
    }

    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(deviceCapabilitySnapshotKey)
        }
    }

    private companion object {
        val deviceCapabilitySnapshotKey = stringPreferencesKey("device_capability_snapshot")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.local.DeviceCapabilityStoreTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/DeviceCapabilityStore.kt app/src/test/java/com/nexio/tv/data/local/DeviceCapabilityStoreTest.kt
git commit -m "feat: persist device capability snapshot"
```

---

### Task 3: Add Startup-Warmed Device Capability Repository

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/device/DeviceCapabilityRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/device/DeviceCapabilityRepositoryTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/MainActivity.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/repository/device/DeviceCapabilityRepositoryTest.kt`:

```kotlin
package com.nexio.tv.data.repository.device

import com.nexio.tv.data.local.DeviceCapabilityStore
import com.nexio.tv.data.local.DeviceCapabilityStoreContract
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceCapabilityRepositoryTest {

    @Test
    fun `ensure cached reuses existing snapshot`() = runTest(UnconfinedTestDispatcher()) {
        val existing = snapshot(1L)
        var captureCount = 0
        val repository = DeviceCapabilityRepository(
            store = FakeStore(existing),
            capture = {
                captureCount += 1
                snapshot(2L)
            }
        )

        assertEquals(existing, repository.ensureCached())
        assertEquals(0, captureCount)
    }

    @Test
    fun `ensure cached captures and stores snapshot when missing`() = runTest(UnconfinedTestDispatcher()) {
        val captured = snapshot(2L)
        val store = FakeStore(null)
        val repository = DeviceCapabilityRepository(
            store = store,
            capture = { captured }
        )

        assertEquals(captured, repository.ensureCached())
        assertEquals(captured, store.latestSnapshot.first())
    }

    @Test
    fun `snapshot for autoplay ensures a cached snapshot exists`() = runTest(UnconfinedTestDispatcher()) {
        val captured = snapshot(3L)
        val repository = DeviceCapabilityRepository(
            store = FakeStore(null),
            capture = { captured }
        )

        assertEquals(captured, repository.snapshotForAutoplay())
    }

    private fun snapshot(capturedAtMs: Long): DeviceCapabilitySnapshot {
        return DeviceCapabilitySnapshot(
            model = "AM9 PRO",
            manufacturer = "UGOOS",
            sdkInt = 34,
            capturedAtMs = capturedAtMs
        )
    }

    private class FakeStore(initial: DeviceCapabilitySnapshot?) : DeviceCapabilityStoreContract {
        private val state = MutableStateFlow(initial)
        override val latestSnapshot: Flow<DeviceCapabilitySnapshot?> = state
        override suspend fun save(snapshot: DeviceCapabilitySnapshot) {
            state.value = snapshot
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.device.DeviceCapabilityRepositoryTest"
```

Expected: FAIL during compilation because `DeviceCapabilityRepository` and `DeviceCapabilityStoreContract` do not exist.

- [ ] **Step 3: Introduce store contract**

Modify `app/src/main/java/com/nexio/tv/data/local/DeviceCapabilityStore.kt`.

Add:

```kotlin
interface DeviceCapabilityStoreContract {
    val latestSnapshot: Flow<DeviceCapabilitySnapshot?>
    suspend fun save(snapshot: DeviceCapabilitySnapshot)
}
```

Change class declaration:

```kotlin
class DeviceCapabilityStore internal constructor(
    private val dataStore: DataStore<Preferences>
) : DeviceCapabilityStoreContract {
```

Keep `clear()` as an extra method on concrete `DeviceCapabilityStore`.

- [ ] **Step 4: Add repository implementation**

Create `app/src/main/java/com/nexio/tv/data/repository/device/DeviceCapabilityRepository.kt`:

```kotlin
package com.nexio.tv.data.repository.device

import com.nexio.tv.data.local.DeviceCapabilityStore
import com.nexio.tv.data.local.DeviceCapabilityStoreContract
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshot
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshotProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceCapabilityRepository internal constructor(
    private val store: DeviceCapabilityStoreContract,
    private val capture: suspend () -> DeviceCapabilitySnapshot?
) {
    @Inject
    constructor(
        store: DeviceCapabilityStore,
        provider: DeviceCapabilitySnapshotProvider,
        playerSettingsDataStore: PlayerSettingsDataStore
    ) : this(
        store = store,
        capture = { provider.capture(playerSettingsDataStore.playerSettings.first()) }
    )

    private val mutex = Mutex()

    suspend fun ensureCached(): DeviceCapabilitySnapshot? = mutex.withLock {
        store.latestSnapshot.first()?.let { return@withLock it }
        val snapshot = capture()
        snapshot?.let { store.save(it) }
        snapshot
    }

    suspend fun snapshotForAutoplay(): DeviceCapabilitySnapshot? = ensureCached()
}
```

- [ ] **Step 5: Warm cache at app startup**

Modify `app/src/main/java/com/nexio/tv/MainActivity.kt`.

Remove:

```kotlin
@Inject
lateinit var debridBenchmarkService: DebridBenchmarkService
```

Add:

```kotlin
@Inject
lateinit var deviceCapabilityRepository: com.nexio.tv.data.repository.device.DeviceCapabilityRepository
```

Inside `onCreate`, after existing deferred startup launch setup has a lifecycle scope available, add:

```kotlin
lifecycleScope.launch(Dispatchers.IO) {
    deviceCapabilityRepository.ensureCached()
}
```

If `Dispatchers` is not imported in `MainActivity.kt`, add:

```kotlin
import kotlinx.coroutines.Dispatchers
```

- [ ] **Step 6: Run repository test**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.device.DeviceCapabilityRepositoryTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/DeviceCapabilityStore.kt app/src/main/java/com/nexio/tv/data/repository/device/DeviceCapabilityRepository.kt app/src/main/java/com/nexio/tv/MainActivity.kt app/src/test/java/com/nexio/tv/data/repository/device/DeviceCapabilityRepositoryTest.kt
git commit -m "feat: warm device capabilities on startup"
```

---

### Task 4: Make Manual Scoring Device-Aware

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareScoringHarnessTest.kt`

- [ ] **Step 1: Write failing tests**

Add these tests to `BenchmarkAwareScoringHarnessTest`:

```kotlin
@Test
fun `manual cap scoring uses device audio capabilities`() {
    val request = ShadowRequestContext(
        requestId = "manual-device-audio",
        videoId = "tt123",
        contentType = "movie",
        title = "Example",
        season = null,
        episode = null,
        runtimeMinutes = 120
    )
    val trueHd = BenchmarkAwareScoringScenarioStream(
        streamKey = "truehd",
        providerId = "RD",
        resolution = "2160p",
        quality = "BluRay Remux",
        encode = "HEVC",
        sizeBytes = 12L * 1024L * 1024L * 1024L,
        durationMs = 120L * 60_000L,
        visualTags = emptyList(),
        audioTags = listOf("TrueHD", "Atmos")
    ).toStreamCardModel()
    val ddp = BenchmarkAwareScoringScenarioStream(
        streamKey = "ddp",
        providerId = "RD",
        resolution = "2160p",
        quality = "BluRay Remux",
        encode = "HEVC",
        sizeBytes = 12L * 1024L * 1024L * 1024L,
        durationMs = 120L * 60_000L,
        visualTags = emptyList(),
        audioTags = listOf("DD+", "Atmos")
    ).toStreamCardModel()

    val event = BenchmarkAwareStreamScorer().scoreWithManualCap(
        request = request,
        streams = listOf(trueHd, ddp),
        manualBitrateCap = 200.0,
        device = deviceSnapshot(
            truehdSupported = false,
            truehdPassthrough = false,
            eac3Supported = true,
            eac3Passthrough = true,
            ac3Supported = true,
            ac3Passthrough = true,
            dtsSupported = false,
            dtsPassthrough = false,
            dtshdSupported = false,
            dtshdPassthrough = false
        )
    )

    assertEquals("ddp|RD", event.selected?.streamKey)
    assertEquals("unsupported", event.winners.first { it.streamKey == "truehd|RD" }.breakdown.content.audioSupportTier)
}

@Test
fun `manual cap scoring uses device hdr capabilities`() {
    val request = ShadowRequestContext(
        requestId = "manual-device-hdr",
        videoId = "tt123",
        contentType = "movie",
        title = "Example",
        season = null,
        episode = null,
        runtimeMinutes = 120
    )
    val dolbyVision = BenchmarkAwareScoringScenarioStream(
        streamKey = "dv",
        providerId = "RD",
        resolution = "2160p",
        quality = "WEB-DL",
        encode = "HEVC",
        sizeBytes = 8L * 1024L * 1024L * 1024L,
        durationMs = 120L * 60_000L,
        visualTags = listOf("DV"),
        audioTags = listOf("DD+")
    ).toStreamCardModel()
    val hdr10 = BenchmarkAwareScoringScenarioStream(
        streamKey = "hdr10",
        providerId = "RD",
        resolution = "2160p",
        quality = "WEB-DL",
        encode = "HEVC",
        sizeBytes = 8L * 1024L * 1024L * 1024L,
        durationMs = 120L * 60_000L,
        visualTags = listOf("HDR10"),
        audioTags = listOf("DD+")
    ).toStreamCardModel()

    val event = BenchmarkAwareStreamScorer().scoreWithManualCap(
        request = request,
        streams = listOf(dolbyVision, hdr10),
        manualBitrateCap = 200.0,
        device = deviceSnapshot(
            displayHdrTypes = setOf(DeviceHdrType.HDR10),
            truehdSupported = false,
            truehdPassthrough = false,
            eac3Supported = true,
            eac3Passthrough = true,
            ac3Supported = true,
            ac3Passthrough = true,
            dtsSupported = false,
            dtsPassthrough = false,
            dtshdSupported = false,
            dtshdPassthrough = false
        )
    )

    assertEquals("hdr10|RD", event.selected?.streamKey)
    assertEquals("fallback", event.winners.first { it.streamKey == "dv|RD" }.breakdown.content.hdrSupportTier)
}
```

If `deviceSnapshot` in this test file lacks `displayHdrTypes`, extend its helper signature:

```kotlin
private fun deviceSnapshot(
    displayHdrTypes: Set<DeviceHdrType> = setOf(DeviceHdrType.DOLBY_VISION, DeviceHdrType.HDR10),
    truehdSupported: Boolean,
    truehdPassthrough: Boolean,
    eac3Supported: Boolean,
    eac3Passthrough: Boolean,
    ac3Supported: Boolean,
    ac3Passthrough: Boolean,
    dtsSupported: Boolean,
    dtsPassthrough: Boolean,
    dtshdSupported: Boolean,
    dtshdPassthrough: Boolean
): DeviceCapabilitySnapshot
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.BenchmarkAwareScoringHarnessTest"
```

Expected: FAIL because `scoreWithManualCap` has no `device` parameter or because selected winners do not reflect device capability support.

- [ ] **Step 3: Extend manual scorer signature**

Modify the `BenchmarkAwareStreamScorer.scoreWithManualCap` signature:

```kotlin
fun scoreWithManualCap(
    request: ShadowRequestContext,
    streams: List<StreamCardModel>,
    manualBitrateCap: Double,
    elapsedMs: Long? = null,
    device: DeviceCapabilitySnapshot? = null
): ShadowAutoPlayDecisionEvent
```

Modify its call to `evaluateStreamWithManualCap`:

```kotlin
evaluateStreamWithManualCap(
    item = item,
    provider = provider,
    request = request,
    manualBitrateCap = safeManualCap,
    device = device
)
```

Modify the `evaluateStreamWithManualCap` signature:

```kotlin
private fun evaluateStreamWithManualCap(
    item: StreamCardModel,
    provider: DebridBenchmarkProvider,
    request: ShadowRequestContext,
    manualBitrateCap: Double,
    device: DeviceCapabilitySnapshot?
): EitherSuccessOrReject<ShadowStreamDecision>
```

Replace:

```kotlin
var codecTier = resolveVideoCodecTier(parsed.encode, device = null)
```

with:

```kotlin
var codecTier = resolveVideoCodecTier(parsed.encode, device)
```

Replace:

```kotlin
device = null,
```

inside the `buildContentScoreBreakdown` call with:

```kotlin
device = device,
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.BenchmarkAwareScoringHarnessTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareScoringHarnessTest.kt
git commit -m "fix: make manual autoplay scoring device aware"
```

---

### Task 5: Route Autoplay Through Manual Device-Aware Scoring Only

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModelDeterministicAutoplayTest.kt`

- [ ] **Step 1: Write the failing test**

Update the existing `shadow autoplay replay coordinator emits manual cap event without benchmarks` test in `StreamScreenViewModelDeterministicAutoplayTest`.

Replace coordinator construction:

```kotlin
val coordinator = ShadowAutoPlayReplayCoordinator(BenchmarkAwareStreamScorer())
```

with:

```kotlin
val coordinator = ShadowAutoPlayReplayCoordinator(BenchmarkAwareStreamScorer())
val device = DeviceCapabilitySnapshot(
    model = "AM9 PRO",
    manufacturer = "UGOOS",
    sdkInt = 34,
    audioOutput = DeviceAudioOutputCapabilities(
        truehd = AudioEncodingSupport(false, false),
        eac3 = AudioEncodingSupport(true, true),
        atmos = AudioEncodingSupport(true, true)
    ),
    capturedAtMs = 42L
)
```

Add `deviceSnapshot = device` to the `coordinator.updateCandidates` call:

```kotlin
coordinator.updateCandidates(
    request = request,
    organizedStreams = listOf(
        BenchmarkAwareScoringScenarioStream(
            streamKey = "manual-cap-1080p",
            providerId = "RD",
            resolution = "1080p",
            quality = "WEB-DL",
            encode = "H264",
            sizeBytes = 12L * 1024L * 1024L * 1024L,
            durationMs = 120L * 60_000L,
            visualTags = emptyList(),
            audioTags = listOf("DD+")
        ).toStreamCardModel()
    ),
    autoplayMaxBitrateMbps = 20.0,
    deviceSnapshot = device,
    isFinalPass = true,
    allowEarlyFinishTerminal = false
)
```

Remove this argument from the final event call:

```kotlin
benchmarkSessions = emptyMap(),
```

so the call becomes:

```kotlin
val event = coordinator.buildEventIfReady(
    timingsMs = 7L
)
```

Add assertion:

```kotlin
assertEquals("supported", event?.selected?.breakdown?.content?.audioSupportTier)
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.stream.StreamScreenViewModelDeterministicAutoplayTest"
```

Expected: FAIL because `updateCandidates` has no `deviceSnapshot` parameter and `buildEventIfReady` still requires benchmark sessions.

- [ ] **Step 3: Update coordinator state**

Modify `ShadowAutoPlayReplayCoordinator` inside `StreamScreenViewModel.kt`.

Replace fields:

```kotlin
private var latestManualBandwidthMode: Boolean = true
private var latestActiveTransportMode: DebridBenchmarkTransportMode? = null
```

with:

```kotlin
private var latestDeviceSnapshot: DeviceCapabilitySnapshot? = null
```

Add import:

```kotlin
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshot
```

Modify the `updateCandidates` signature:

```kotlin
fun updateCandidates(
    request: ShadowRequestContext,
    organizedStreams: List<StreamCardModel>,
    autoplayMaxBitrateMbps: Double?,
    deviceSnapshot: DeviceCapabilitySnapshot?,
    isFinalPass: Boolean,
    allowEarlyFinishTerminal: Boolean
)
```

Inside `updateCandidates`, set:

```kotlin
latestDeviceSnapshot = deviceSnapshot
```

Remove assignments to `latestActiveTransportMode` and `latestManualBandwidthMode`.

Modify the `buildEventIfReady` signature:

```kotlin
fun buildEventIfReady(
    timingsMs: Long? = null
): ShadowAutoPlayDecisionEvent?
```

Replace its event selection branch:

```kotlin
val event = scorer.scoreWithManualCap(
    request = request,
    streams = latestStreams,
    manualBitrateCap = latestAutoplayMaxBitrateMbps ?: return null,
    elapsedMs = timingsMs,
    device = latestDeviceSnapshot
)
```

- [ ] **Step 4: Update StreamScreenViewModel scoring calls**

Modify `StreamScreenViewModel` constructor:

```kotlin
private val deviceCapabilityRepository: DeviceCapabilityRepository,
```

Add import:

```kotlin
import com.nexio.tv.data.repository.device.DeviceCapabilityRepository
```

Remove constructor dependency:

```kotlin
private val debridBenchmarkStore: DebridBenchmarkStore,
```

Remove imports:

```kotlin
import com.nexio.tv.data.local.AutoplayBandwidthMode
import com.nexio.tv.data.local.DebridBenchmarkStore
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkResult
import com.nexio.tv.data.repository.benchmark.hasValidAutoplayBenchmarkFor
```

Replace code where `validBenchmarkSessions`, `hasValidBenchmark`, `isManualBandwidthMode`, and `autoplayMaxBitrateMbps` are computed with:

```kotlin
val deviceSnapshot = deviceCapabilityRepository.snapshotForAutoplay()
val autoplayMaxBitrateMbps = playerSettings.manualBitrateLimitMbps
```

Modify the `buildDeterministicAutoPlayPlaybackInfo` signature:

```kotlin
private suspend fun buildDeterministicAutoPlayPlaybackInfo(
    request: ShadowRequestContext,
    organizedStreams: List<StreamCardModel>,
    autoplayMaxBitrateMbps: Double?,
    deviceSnapshot: DeviceCapabilitySnapshot?,
    isFinalPass: Boolean
): StreamPlaybackInfo?
```

Replace its scoring branch with:

```kotlin
val event = benchmarkAwareStreamScorer.scoreWithManualCap(
    request = request,
    streams = eligibleStreams,
    manualBitrateCap = autoplayMaxBitrateMbps ?: return null,
    device = deviceSnapshot
)
```

Modify the `updateShadowAutoPlayDecision` signature:

```kotlin
private suspend fun updateShadowAutoPlayDecision(
    request: ShadowRequestContext,
    organizedStreams: List<StreamCardModel>,
    autoplayMaxBitrateMbps: Double?,
    deviceSnapshot: DeviceCapabilitySnapshot?,
    isFinalPass: Boolean,
    allowEarlyFinishTerminal: Boolean
)
```

Modify its coordinator call:

```kotlin
shadowAutoPlayReplayCoordinator.updateCandidates(
    request = request,
    organizedStreams = organizedStreams,
    autoplayMaxBitrateMbps = autoplayMaxBitrateMbps,
    deviceSnapshot = deviceSnapshot,
    isFinalPass = isFinalPass,
    allowEarlyFinishTerminal = allowEarlyFinishTerminal
)
```

Modify `emitShadowAutoPlayDecisionIfReady()`:

```kotlin
val event = withContext(Dispatchers.Default) {
    shadowAutoPlayReplayCoordinator.buildEventIfReady(
        timingsMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
    )
} ?: return
```

Delete these helper functions from the bottom of `StreamScreenViewModel.kt`: `PlayerSettings.toShadowActiveTransportMode`, `PlayerSettings.autoplayMaxBitrateForScoring`, and `PlayerSettings.isManualBandwidthMode`.

Delete these benchmark-session functions from `StreamScreenViewModel.kt`: `latestValidBenchmarkSessions`, `latestBenchmarkSessions`, and `buildShadowAutoPlayDecisionEvent`.

Delete `buildBenchmarkAwareAutoPlayPlaybackInfo` after verifying `rg -n "buildBenchmarkAwareAutoPlayPlaybackInfo" app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt` returns only the function declaration.

- [ ] **Step 5: Run stream autoplay tests**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.stream.StreamScreenViewModelDeterministicAutoplayTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt app/src/test/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModelDeterministicAutoplayTest.kt
git commit -m "refactor: use manual device-aware autoplay path"
```

---

### Task 6: Remove Benchmark Autoplay Settings and AUTO Mode

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAutoPlaySettings.kt`
- Delete: `app/src/main/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailability.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt`
- Delete: `app/src/test/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailabilityTest.kt`

- [ ] **Step 1: Update PlayerSettings tests first**

Modify `PlayerSettingsDataStoreTest`.

Replace the test named:

```kotlin
fun `autoplay bandwidth mode defaults to manual with 40 mbps manual cap`()
```

with:

```kotlin
@Test
fun `autoplay defaults to deterministic manual cap`() = runTest {
    val dataStore = createDataStore()
    val settings = dataStore.playerSettings.first()

    assertTrue(settings.deterministicAutoplayEnabled)
    assertEquals(40.0, settings.manualBitrateLimitMbps, 0.0)
}
```

Replace the migration test named:

```kotlin
fun `autoplay defaults migration enables deterministic manual mode once`()
```

with:

```kotlin
@Test
fun `autoplay defaults migration maps legacy auto benchmark mode to manual cap`() {
    val deterministicAutoplayEnabledKey = booleanPreferencesKey("deterministic_autoplay_enabled")
    val autoplayBandwidthModeKey = stringPreferencesKey("autoplay_bandwidth_mode")
    val manualBitrateLimitMbpsKey = doublePreferencesKey("manual_bitrate_limit_mbps")
    val migrationDoneKey = booleanPreferencesKey("migration_autoplay_manual_defaults_done")
    val prefs = mutablePreferencesOf(
        deterministicAutoplayEnabledKey to false,
        autoplayBandwidthModeKey to "AUTO",
        manualBitrateLimitMbpsKey to 20.0
    )

    applyPlayerSettingsMigrations(prefs)

    assertEquals(true, prefs[deterministicAutoplayEnabledKey])
    assertEquals("MANUAL", prefs[autoplayBandwidthModeKey])
    assertEquals(40.0, prefs[manualBitrateLimitMbpsKey] ?: -1.0, 0.0)
    assertEquals(true, prefs[migrationDoneKey])
}
```

Delete tests that assert persisted `AutoplayBandwidthMode.AUTO` remains active after migration. Add:

```kotlin
@Test
fun `setting manual bitrate persists autoplay cap`() = runTest {
    val dataStore = createDataStore()

    dataStore.setManualBitrateLimitMbps(85.0)

    assertEquals(85.0, dataStore.playerSettings.first().manualBitrateLimitMbps, 0.0)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.local.PlayerSettingsDataStoreTest"
```

Expected: FAIL because active settings still expose `AutoplayBandwidthMode` and benchmark fields.

- [ ] **Step 3: Remove active AUTO mode from PlayerSettings**

Modify `PlayerSettings` in `PlayerSettingsDataStore.kt`.

Remove fields:

```kotlin
val autoplayMaxBitrateEnabled: Boolean = true,
val autoplayMaxBitrateMbps: Double? = null,
val autoplayBandwidthMode: AutoplayBandwidthMode = AutoplayBandwidthMode.MANUAL,
```

Delete enum:

```kotlin
enum class AutoplayBandwidthMode {
    AUTO,
    MANUAL
}
```

Keep legacy keys:

```kotlin
private val autoplayBandwidthModeMigrationKey = stringPreferencesKey("autoplay_bandwidth_mode")
private val autoplayMaxBitrateEnabledKey = booleanPreferencesKey("autoplay_max_bitrate_enabled")
private val autoplayMaxBitrateMbpsKey = doublePreferencesKey("autoplay_max_bitrate_mbps")
```

Remove active reads:

```kotlin
autoplayMaxBitrateEnabled = prefs[autoplayMaxBitrateEnabledKey] ?: true,
autoplayMaxBitrateMbps = prefs[autoplayMaxBitrateMbpsKey]?.takeIf { it.isFinite() && it > 0.0 },
autoplayBandwidthMode = prefs[autoplayBandwidthModeMigrationKey]?.let {
    runCatching { AutoplayBandwidthMode.valueOf(it) }.getOrDefault(AutoplayBandwidthMode.MANUAL)
} ?: AutoplayBandwidthMode.MANUAL,
```

Keep migration writing old `autoplay_bandwidth_mode` to `"MANUAL"` so older installs stop seeing `"AUTO"` if downgraded.

Delete methods:

```kotlin
suspend fun setAutoplayMaxBitrateEnabled(enabled: Boolean)
suspend fun setAutoplayMaxBitrate(mbps: Double?)
suspend fun setAutoplayBandwidthMode(mode: AutoplayBandwidthMode)
```

Modify `diskSpoolTargetBitrateMbps()`:

```kotlin
fun PlayerSettings.diskSpoolTargetBitrateMbps(): Double? {
    return manualBitrateLimitMbps.takeIf { it.isFinite() && it > 0.0 }
}
```

For transport setting setters that currently remove `autoplayMaxBitrateMbpsKey`, remove those `prefs.remove(autoplayMaxBitrateMbpsKey)` lines because benchmark-derived cap is no longer active state.

- [ ] **Step 4: Simplify PlaybackAutoPlaySettings UI**

Modify `PlaybackAutoPlaySettings.kt`.

Remove import:

```kotlin
import com.nexio.tv.data.local.AutoplayBandwidthMode
```

Change the `autoPlaySettingsItems` signature by removing:

```kotlin
autoplayBenchmarkAvailable: Boolean,
onShowAutoplayBandwidthModeDialog: () -> Unit,
```

Delete the local variables `effectiveBandwidthMode` and `deterministicAutoplayAvailable` from `autoPlaySettingsItems`.

Change deterministic autoplay item subtitle:

```kotlin
subtitle = stringResource(R.string.autoplay_deterministic_sub)
enabled = true
```

Delete the `autoplay_bandwidth_mode` item.

Always show the `autoplay_manual_bitrate_limit` item:

```kotlin
item(key = "autoplay_manual_bitrate_limit") {
    val sliderValue = (playerSettings.manualBitrateLimitMbps / 5.0).roundToInt()
    SliderSettingsItem(
        icon = Icons.Default.Tune,
        title = stringResource(R.string.autoplay_manual_bitrate_limit_title),
        subtitle = stringResource(R.string.autoplay_manual_bitrate_limit_sub),
        value = sliderValue,
        valueText = "${sliderValue * 5} Mbps",
        minValue = 1,
        maxValue = 40,
        step = 1,
        onValueChange = { onSetManualBitrateLimitMbps(it * 5.0) },
        onFocused = onItemFocused
    )
}
```

Delete the private composable named `AutoplayBandwidthModeDialog`.

Remove `showAutoplayBandwidthModeDialog`, `autoplayBenchmarkAvailable`, `onSetAutoplayBandwidthMode`, and `onDismissAutoplayBandwidthModeDialog` from `AutoPlaySettingsDialogs`.

- [ ] **Step 5: Simplify playback settings screen/viewmodel**

Modify `PlaybackSettingsViewModel.kt`.

Remove imports:

```kotlin
import com.nexio.tv.data.local.AutoplayBandwidthMode
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkService
import com.nexio.tv.data.repository.benchmark.hasValidAutoplayBenchmarkFor
```

Remove constructor parameter:

```kotlin
private val debridBenchmarkService: DebridBenchmarkService,
```

Delete `latestBenchmarkResults`, `autoplayBenchmarkAvailable`, `currentAutoplayBenchmarkAvailable`, and `setAutoplayBandwidthMode`.

Modify `PlaybackSettingsScreen.kt`.

Remove import:

```kotlin
import com.nexio.tv.data.local.AutoplayBandwidthMode
```

Remove state:

```kotlin
val autoplayBenchmarkAvailable by viewModel.autoplayBenchmarkAvailable.collectAsStateWithLifecycle(initialValue = false)
var showAutoplayBandwidthModeDialog by remember { mutableStateOf(false) }
```

Remove the `autoplayBenchmarkAvailable`, `onShowAutoplayBandwidthModeDialog`, `showAutoplayBandwidthModeDialog`, `onSetAutoplayBandwidthMode`, and `onDismissAutoplayBandwidthModeDialog` arguments from all `PlaybackSettingsSections` and `AutoPlaySettingsDialogs` calls.

Modify `PlaybackSettingsSections.kt` signatures to remove the same autoplay benchmark/mode dialog parameters.

Delete file:

```bash
rm app/src/main/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailability.kt
```

Delete test:

```bash
rm app/src/test/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailabilityTest.kt
```

- [ ] **Step 6: Update strings**

Modify `app/src/main/res/values/strings.xml`.

Remove strings:

```xml
<string name="autoplay_bandwidth_mode_auto">Auto (Benchmark)</string>
<string name="autoplay_bandwidth_mode_auto_desc">Uses benchmark data to score streams by transport quality. Requires a completed benchmark.</string>
<string name="autoplay_bandwidth_mode_auto_unavailable_desc">Run a benchmark with the current VOD cache and parallel connection settings before using Auto.</string>
<string name="autoplay_bandwidth_mode_manual">Manual</string>
<string name="autoplay_bandwidth_mode_title">Autoplay bandwidth mode</string>
<string name="autoplay_deterministic_unavailable_sub">Manual mode works without Service Wrap. Auto mode uses Manual until a current valid benchmark exists.</string>
```

Change manual bitrate subtitle:

```xml
<string name="autoplay_manual_bitrate_limit_sub">Maximum average bitrate autoplay may select. Device video, HDR, and audio capabilities still influence ranking.</string>
```

- [ ] **Step 7: Run settings and datastore tests**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest \
  --tests "com.nexio.tv.data.local.PlayerSettingsDataStoreTest" \
  --tests "com.nexio.tv.ui.screens.settings.PlaybackSettingsViewModelSpoolModeTest"
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAutoPlaySettings.kt app/src/main/res/values/strings.xml app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt
git rm app/src/main/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailability.kt app/src/test/java/com/nexio/tv/ui/screens/settings/DeterministicAutoplayAvailabilityTest.kt
git commit -m "refactor: remove benchmark autoplay mode"
```

---

### Task 7: Remove User-Facing Debrid Benchmark Feature

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: existing compile/unit tests

- [ ] **Step 1: Remove benchmark rows from Debrid settings**

Modify `DebridSettingsContent.kt`.

Remove constructor dependency:

```kotlin
private val debridBenchmarkService: DebridBenchmarkService,
```

Remove imports:

```kotlin
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkRuntimeState
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkService
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSummary
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTerminationReason
import com.nexio.tv.data.repository.benchmark.hasValidAutoplayBenchmarkFor
import com.nexio.tv.data.repository.benchmark.safeSustainedBudgetMbps
import com.nexio.tv.data.repository.benchmark.playbackStability
```

Remove state properties:

```kotlin
val realDebridBenchmark: DebridProviderBenchmarkUi = DebridProviderBenchmarkUi()
val premiumizeBenchmark: DebridProviderBenchmarkUi = DebridProviderBenchmarkUi()
val torBoxBenchmark: DebridProviderBenchmarkUi = DebridProviderBenchmarkUi()
val easyDebridBenchmark: DebridProviderBenchmarkUi = DebridProviderBenchmarkUi()
val debridBenchmarkDataCollectionEnabled: Boolean = false
val autoplayMaxBitrateEnabled: Boolean = true
val autoplayMaxBitrateMbps: Double? = null
val benchmarkResultDialog: DebridBenchmarkResultDialogUi? = null
```

Delete data classes named `DebridProviderBenchmarkUi`, `DebridBenchmarkResultDialogUi`, `DebridBenchmarkSnapshot`, and `DebridDialogSnapshot`.

Delete UI items keyed:

```kotlin
"debrid_rd_benchmark"
"debrid_rd_benchmark_result"
"debrid_autoplay_max_bitrate"
"debrid_pm_benchmark"
"debrid_pm_benchmark_result"
"debrid_tb_benchmark"
"debrid_tb_benchmark_result"
"debrid_ed_benchmark"
"debrid_ed_benchmark_result"
```

Delete composables and functions named `DebridBenchmarkRow`, `DebridBenchmarkResultRow`, `DebridBenchmarkResultDialog`, `DebridBenchmarkMetricRow`, `DebridBenchmarkCompactMetricRow`, `DebridBenchmarkHeaderMetric`, `DebridBenchmarkComparisonHighlights`, `benchmarkRowSubtitle`, `formatRunningBenchmarkSummary`, `formatLatestBenchmarkSummary`, `formatBenchmarkThroughput`, `formatBenchmarkStartup`, `formatBenchmarkLatency`, `formatBenchmarkWinner`, `formatBenchmarkStability`, `formatBenchmarkGainOverDirect`, `formatBenchmarkCv`, `formatBenchmarkPercent`, `formatBenchmarkMeasuredAt`, `formatBenchmarkConfigSnapshot`, `formatBenchmarkElapsed`, `formatBenchmarkBytes`, `startBenchmark`, `cancelBenchmark`, `openLatestBenchmarkResult`, `dismissBenchmarkResultDialog`, `setBenchmarkResultDialog`, and `buildDebridBenchmarkUi`.

Remove the `benchmarkState`, `deterministicAutoplayAvailable`, and `dialogState` flows from `init`, and remove the `viewModelScope.launch` collector for `debridBenchmarkService.outcomes`.

In the UI state combine, combine only connection, player settings, and collector dashboard state.

- [ ] **Step 2: Remove benchmark data collection toggle**

Modify `PlaybackSettingsSections.kt`.

Remove parameters:

```kotlin
debridBenchmarkDataCollectionEnabled: Boolean,
onSetDebridBenchmarkDataCollectionEnabled: (Boolean) -> Unit,
```

Remove the `item(key = "troubleshooting_benchmark_data_collection")` block from `PlaybackSettingsSections.kt`.

Modify `PlayerSettings` in `PlayerSettingsDataStore.kt`:

Remove:

```kotlin
val debridBenchmarkDataCollectionEnabled: Boolean = false,
```

Delete key and setter:

```kotlin
private val debridBenchmarkDataCollectionEnabledKey = booleanPreferencesKey("debrid_benchmark_data_collection_enabled")
suspend fun setDebridBenchmarkDataCollectionEnabled(enabled: Boolean)
```

- [ ] **Step 3: Remove benchmark strings**

Remove string resources named `debrid_real_debrid_benchmark_title`, `debrid_real_debrid_config_benchmark_title`, `debrid_premiumize_benchmark_title`, `debrid_premiumize_config_benchmark_title`, `debrid_benchmark_description`, `debrid_config_benchmark_description`, `debrid_benchmark_run_action`, `debrid_config_benchmark_run_action`, `debrid_benchmark_cancel_action`, `debrid_benchmark_busy`, `debrid_real_debrid_benchmark_result_title`, `debrid_real_debrid_config_benchmark_result_title`, `debrid_premiumize_benchmark_result_title`, `debrid_premiumize_config_benchmark_result_title`, `debrid_benchmark_view_action`, `debrid_benchmark_results_title`, `debrid_config_benchmark_results_title`, `debrid_benchmark_results_subtitle`, `debrid_config_benchmark_best_profile_label`, `debrid_config_benchmark_chunk_group_title`, `debrid_config_benchmark_profile_label`, `debrid_config_benchmark_running`, `debrid_benchmark_metric_startup`, `debrid_benchmark_metric_sustained`, `debrid_benchmark_metric_transferred`, `debrid_benchmark_metric_elapsed`, `debrid_benchmark_metric_unavailable`, `debrid_benchmark_label_file`, `debrid_benchmark_label_host`, `debrid_benchmark_label_file_size`, `debrid_benchmark_label_measured_at`, `debrid_benchmark_column_direct`, `debrid_benchmark_column_optimized`, `debrid_benchmark_config_snapshot`, `debrid_benchmark_config_parallel_off`, `debrid_benchmark_metric_initial_ttfb`, `debrid_benchmark_metric_safe_budget`, `debrid_benchmark_metric_average_throughput`, `debrid_benchmark_metric_p10_throughput`, `debrid_benchmark_metric_peak_throughput`, `debrid_benchmark_metric_throughput_stddev`, `debrid_benchmark_metric_throughput_cv`, `debrid_benchmark_metric_stall_count`, `debrid_benchmark_metric_stability`, `debrid_benchmark_metric_max_read_gap`, `debrid_benchmark_metric_seek_p50`, `debrid_benchmark_metric_seek_p95`, `debrid_benchmark_metric_seek_p99`, `debrid_benchmark_metric_seek_fail_rate`, `debrid_benchmark_comparison_title`, `debrid_benchmark_metric_sustained_winner`, `debrid_benchmark_metric_seek_winner`, `debrid_benchmark_metric_stability_winner`, `debrid_benchmark_metric_gain_over_direct`, `debrid_benchmark_title`, and `debrid_benchmark_subtitle`.

- [ ] **Step 4: Run compile-focused tests**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.local.PlayerSettingsDataStoreTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt app/src/main/res/values/strings.xml
git commit -m "refactor: remove provider benchmark UI"
```

---

### Task 8: Remove Benchmark Runtime Dependencies from Player and Startup

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- Modify: player tests that construct these classes
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Remove unused benchmark store injection**

Modify `PlayerViewModel.kt`.

Remove import:

```kotlin
import com.nexio.tv.data.local.DebridBenchmarkStore
```

Remove constructor parameter:

```kotlin
private val debridBenchmarkStore: DebridBenchmarkStore,
```

Remove controller argument:

```kotlin
debridBenchmarkStore = debridBenchmarkStore,
```

Modify `PlayerRuntimeController.kt`.

Remove import:

```kotlin
import com.nexio.tv.data.local.DebridBenchmarkStore
```

Remove constructor property:

```kotlin
internal val debridBenchmarkStore: DebridBenchmarkStore,
```

- [ ] **Step 2: Update tests that construct player runtime/controller**

Run this search:

```bash
rg -n "debridBenchmarkStore|DebridBenchmarkStore" app/src/test/java/com/nexio/tv/ui/screens/player app/src/test/java/com/nexio/tv
```

For each test constructor call, remove the named argument:

```kotlin
debridBenchmarkStore = fakeDebridBenchmarkStore,
```

For each fake field/import, remove:

```kotlin
import com.nexio.tv.data.local.DebridBenchmarkStore
```

- [ ] **Step 3: Run focused player tests**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest"
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt app/src/test/java/com/nexio/tv/ui/screens/player
git commit -m "refactor: remove benchmark dependency from player runtime"
```

---

### Task 9: Clean Up Remaining Benchmark-Autoplay References

**Files:**
- Modify: any files reported by the search commands below
- Test: compile/unit suite

- [ ] **Step 1: Search for forbidden benchmark-autoplay references**

Run:

```bash
rg -n "AutoplayBandwidthMode|autoplayBenchmarkAvailable|hasValidAutoplayBenchmarkFor|autoplayMaxBitrate|Benchmark Data Collection|Auto \\(Benchmark\\)|debridBenchmarkService|DebridBenchmarkService|DebridBenchmarkStore" app/src/main/java app/src/test/java app/src/main/res/values/strings.xml
```

Expected remaining allowed references only in low-level benchmark package tests/classes:

```text
app/src/main/java/com/nexio/tv/data/repository/benchmark/
app/src/test/java/com/nexio/tv/data/repository/benchmark/
```

No remaining references should exist in:

```text
app/src/main/java/com/nexio/tv/ui/screens/stream/
app/src/main/java/com/nexio/tv/ui/screens/settings/
app/src/main/java/com/nexio/tv/ui/screens/player/
app/src/main/java/com/nexio/tv/MainActivity.kt
app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt
```

- [ ] **Step 2: Remove disallowed references**

For any disallowed reference from Step 1, remove the import, constructor argument, state field, UI parameter, string, or test assertion. Use this replacement pattern for autoplay scoring:

```kotlin
val autoplayMaxBitrateMbps = playerSettings.manualBitrateLimitMbps
val deviceSnapshot = deviceCapabilityRepository.snapshotForAutoplay()
```

Use this replacement pattern for settings availability:

```kotlin
val deterministicAutoplayAvailable = true
```

Then remove the intermediate variable entirely if it is only passed as `enabled = true`.

- [ ] **Step 3: Run broad compile/test check**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java app/src/test/java app/src/main/res/values/strings.xml
git commit -m "chore: remove benchmark autoplay references"
```

---

### Task 10: Manual Device Validation

**Files:**
- No source changes expected

- [ ] **Step 1: Install debug build on AM9 Pro**

Run:

```bash
adb connect 192.168.50.71
./gradlew :app:installArm64Debug
```

Expected:

```text
already connected to 192.168.50.71:5555
BUILD SUCCESSFUL
```

- [ ] **Step 2: Verify first-start capability cache**

Clear app data only if intentionally testing first-start behavior:

```bash
adb -s 192.168.50.71:5555 shell pm clear com.nexiodebug.tv
```

Launch app:

```bash
adb -s 192.168.50.71:5555 shell monkey -p com.nexiodebug.tv 1
```

Collect logs:

```bash
adb -s 192.168.50.71:5555 logcat -d | grep -E "DeviceCapability|device capability|AUTOPLAY|AutoPlayShadow"
```

Expected:

```text
Device capability cache miss
Device capability captured
```

Relaunch app without clearing data and collect logs again.

Expected:

```text
Device capability cache hit
```

- [ ] **Step 3: Validate Survivor S50E08 autoplay**

Open Survivor S50E08 and start deterministic autoplay.

Capture logs:

```bash
adb -s 192.168.50.71:5555 logcat -d | grep -E "AutoPlayShadowJson|AutoPlayShadow|AUTOPLAY_DV|missing_benchmark|missing_runtime"
```

Expected absence:

```text
missing_benchmark
winner=none eligible=0
```

Expected scoring event includes device-aware content fields:

```text
audioSupportTier
hdrSupportTier
codecTier
```

- [ ] **Step 4: Verify settings UI has no benchmark autoplay controls**

Navigate to Playback settings.

Expected:

```text
Deterministic Autoplay
Manual bitrate limit
```

Expected absence:

```text
Auto (Benchmark)
Autoplay bandwidth mode
Run benchmark
Benchmark Data Collection
```

---

## Self-Review

**Spec coverage:** Task 2 and Task 3 cover first-start device capability disk caching. Task 4 covers device-aware manual scoring. Task 5 routes autoplay exclusively through manual device-aware scoring. Task 6 removes benchmark autoplay mode and AUTO settings. Task 7 removes reachable provider benchmark UI/data collection. Task 8 removes player/startup benchmark dependencies. Task 9 validates no benchmark-autoplay references remain in app flows. Task 10 covers manual validation on the Android TV device.

**Placeholder scan:** The plan contains concrete implementation, deletion, and verification steps. Deletion tasks list exact symbols, files, and replacement snippets.

**Type consistency:** The plan consistently uses `DeviceCapabilityStore`, `DeviceCapabilityRepository`, `parseDeviceCapabilitySnapshotJson`, `parseDeviceCapabilitySnapshotBestEffort`, `DeviceCapabilitySnapshot`, `scoreWithManualCap` with a `device` parameter, and `manualBitrateLimitMbps`.
