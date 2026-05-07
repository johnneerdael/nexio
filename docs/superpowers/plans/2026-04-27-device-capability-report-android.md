# Device Capability Report Uploader Implementation Plan (Android)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `DeviceCapabilityReportUploader` to Nexio that POSTs the latest `DeviceCapabilitySnapshot` to the collector exactly once per cold-start, gated on the existing `shadowAutoplayDataCollectionEnabled` setting; remove the dead `DebridBenchmarkCollectionUploader`.

**Architecture:** The new uploader mirrors `ShadowAutoplayCollectionUploader` (same OkHttp client, base URL, write token, client-info builder, gating pattern). Triggered from `MainActivity.onCreate` immediately after `deviceCapabilityRepository.ensureCached()` so it runs once per process and never on the playback critical path. Uploads are best-effort and silent on failure. The legacy `DebridBenchmarkCollectionUploader` (a no-op stub) and its single call-site in `DebridBenchmarkService` are removed.

**Tech Stack:** Kotlin, Android SDK 34, Hilt (DI), Coroutines, Gson, OkHttp 4 + MockWebServer, JUnit 4, MockK. Test runner: `./gradlew :app:testDebugUnitTest`.

**Prerequisite:** The server-side endpoint `POST /api/v1/device-capability-reports` (in `nexio-datacollection`) must be deployed first. See `2026-04-27-device-capability-report-server.md` in that repo.

---

## File Structure

- Create `app/src/main/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilityReportUploader.kt` — new uploader, mirrors `ShadowAutoplayCollectionUploader.kt`. One responsibility: serialize a `DeviceCapabilitySnapshot` and POST it once per process.
- Create `app/src/main/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilitySnapshotSerializer.kt` — small pure helper that turns a `DeviceCapabilitySnapshot` into a `JsonObject`. Kept separate so it's easy to unit-test without networking.
- Modify `app/src/main/java/com/nexio/tv/MainActivity.kt:382-384` — after `deviceCapabilityRepository.ensureCached()`, ask the uploader to submit if not yet submitted this process.
- Modify `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkService.kt:38, 57, 65, 170` — remove the `collectionUploader` field, the constructor parameter, and the `submitIfEnabled` call.
- Delete `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkCollectionUploader.kt`.
- Modify `app/src/test/java/com/nexio/tv/data/repository/benchmark/CollectorUploadersTest.kt` — drop any references to the deleted uploader (none today, but verify) and add tests for the new uploader.
- Create `app/src/test/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilitySnapshotSerializerTest.kt` — unit-tests the serializer.

The uploader and serializer live in the existing `data/repository/benchmark` package next to `ShadowAutoplayCollectionUploader` to share patterns and DI conventions.

---

## Task 1: Add the snapshot serializer

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilitySnapshotSerializer.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilitySnapshotSerializerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilitySnapshotSerializerTest.kt
package com.nexio.tv.data.repository.benchmark

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitySnapshotSerializerTest {

    @Test
    fun `serializer emits required fields and audio passthrough encodings`() {
        val snapshot = DeviceCapabilitySnapshot(
            model = "Google TV Streamer",
            manufacturer = "Google",
            sdkInt = 34,
            displayHdrTypes = setOf(DeviceHdrType.HDR10, DeviceHdrType.DOLBY_VISION),
            videoDecode = DeviceVideoDecodeCapabilities(decoders = emptyList()),
            audioOutput = DeviceAudioOutputCapabilities(
                passthrough = listOf("AC3", "EAC3")
            ),
            evidence = null,
            capturedAtMs = 1775519900000L
        )

        val json = DeviceCapabilitySnapshotSerializer.toJson(snapshot)

        assertEquals("Google TV Streamer", json.get("model").asString)
        assertEquals(34, json.get("sdkInt").asInt)
        assertEquals(1775519900000L, json.get("capturedAtMs").asLong)
        val hdr = json.getAsJsonArray("displayHdrTypes").map { it.asString }
        assertTrue(hdr.containsAll(listOf("HDR10", "DOLBY_VISION")))
        val passthrough = json.getAsJsonObject("audioOutput")
            .getAsJsonArray("passthrough")
            .map { it.asString }
        assertEquals(listOf("AC3", "EAC3"), passthrough)
    }

    @Test
    fun `serializer round-trips through the existing parser`() {
        val original = DeviceCapabilitySnapshot(
            model = "Test Device",
            manufacturer = "Acme",
            sdkInt = 33,
            displayHdrTypes = emptySet(),
            videoDecode = DeviceVideoDecodeCapabilities(decoders = emptyList()),
            audioOutput = DeviceAudioOutputCapabilities(passthrough = emptyList()),
            evidence = null,
            capturedAtMs = 1L
        )

        val raw = DeviceCapabilitySnapshotSerializer.toJson(original).toString()
        val parsed = parseDeviceCapabilitySnapshotJson(raw)

        assertEquals(original.model, parsed?.model)
        assertEquals(original.sdkInt, parsed?.sdkInt)
        assertEquals(original.capturedAtMs, parsed?.capturedAtMs)
    }
}
```

NOTE: Adjust the constructor call sites if your local `DeviceCapabilitySnapshot`, `DeviceVideoDecodeCapabilities`, or `DeviceAudioOutputCapabilities` data classes have additional required parameters. Read the data class declarations in `app/src/main/java/com/nexio/tv/data/repository/benchmark/` to confirm shape before running the test.

- [ ] **Step 2: Run the test and verify it fails to compile**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshotSerializerTest`
Expected: compilation failure — `Unresolved reference: DeviceCapabilitySnapshotSerializer`.

- [ ] **Step 3: Implement the serializer**

```kotlin
// app/src/main/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilitySnapshotSerializer.kt
package com.nexio.tv.data.repository.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal object DeviceCapabilitySnapshotSerializer {
    fun toJson(snapshot: DeviceCapabilitySnapshot): JsonObject {
        return JsonObject().apply {
            snapshot.model?.let { addProperty("model", it) }
            snapshot.manufacturer?.let { addProperty("manufacturer", it) }
            addProperty("sdkInt", snapshot.sdkInt)
            addProperty("capturedAtMs", snapshot.capturedAtMs)
            add("displayHdrTypes", JsonArray().also { array ->
                snapshot.displayHdrTypes
                    .map { it.wireKey }
                    .sorted()
                    .forEach { array.add(it) }
            })
            add("videoDecode", videoDecodeJson(snapshot.videoDecode))
            add("audioOutput", audioOutputJson(snapshot.audioOutput))
        }
    }

    private fun videoDecodeJson(video: DeviceVideoDecodeCapabilities): JsonObject {
        return JsonObject().apply {
            add("decoders", JsonArray().also { array ->
                video.decoders.forEach { decoder ->
                    val decoderJson = JsonObject().apply {
                        addProperty("name", decoder.name)
                        addProperty("hardwareAccelerated", decoder.hardwareAccelerated)
                        addProperty("softwareOnly", decoder.softwareOnly)
                        addProperty("secureSupported", decoder.secureSupported)
                    }
                    array.add(decoderJson)
                }
            })
        }
    }

    private fun audioOutputJson(audio: DeviceAudioOutputCapabilities): JsonObject {
        return JsonObject().apply {
            add("passthrough", JsonArray().also { array ->
                audio.passthrough.forEach { array.add(it) }
            })
        }
    }
}
```

NOTE: The exact field names for `DeviceVideoDecodeCapabilities.decoders` entries (`name`, `hardwareAccelerated`, `softwareOnly`, `secureSupported`) and for `DeviceAudioOutputCapabilities` (`passthrough`) are inferred from `DeviceCapabilitySnapshotProvider.captureVideoDecodeCapabilities()` and the parser in `DeviceCapabilitySnapshotJson.kt`. Read the actual data class declarations and adjust the property accesses to match. The serializer must produce JSON that `parseDeviceCapabilitySnapshotJson` (lines 1–50 of `DeviceCapabilitySnapshotJson.kt`) can parse — the second test case enforces this round-trip, and is the source of truth.

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshotSerializerTest`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilitySnapshotSerializer.kt \
        app/src/test/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilitySnapshotSerializerTest.kt
git commit -m "feat(benchmark): add DeviceCapabilitySnapshotSerializer"
```

---

## Task 2: Add a failing test for the uploader gating

**Files:**
- Test: `app/src/test/java/com/nexio/tv/data/repository/benchmark/CollectorUploadersTest.kt` (extend, do not rewrite).

- [ ] **Step 1: Append three tests covering enabled/disabled/once-per-process gating**

Add the following to `CollectorUploadersTest.kt` inside the existing test class (or append a new test class in the same file):

```kotlin
    @Test
    fun `device capability uploader posts envelope when data collection is enabled`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"id":"device-x"}"""))

        val uploader = DeviceCapabilityReportUploader(
            playerSettingsDataStore = playerSettingsDataStore(
                PlayerSettings(shadowAutoplayDataCollectionEnabled = true)
            ),
            deviceCapabilityRepository = mockk<com.nexio.tv.data.repository.device.DeviceCapabilityRepository>().also {
                coEvery { it.snapshotForAutoplay() } returns sampleSnapshot()
            },
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            tokenProvider = { "write-token" },
            clientInfoProvider = { clientInfoJson("device-x") }
        )

        uploader.submitOnceIfEnabled()

        val request = server.takeRequest()
        assertEquals("/api/v1/device-capability-reports", request.path)
        assertEquals("Bearer write-token", request.getHeader("Authorization"))
        val envelope = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("device-x", envelope.getAsJsonObject("client").get("androidId").asString)
        assertEquals(34, envelope.getAsJsonObject("report").get("sdkInt").asInt)
        server.shutdown()
    }

    @Test
    fun `device capability uploader does not post when data collection is disabled`() = runTest {
        val server = MockWebServer()
        server.start()

        val uploader = DeviceCapabilityReportUploader(
            playerSettingsDataStore = playerSettingsDataStore(
                PlayerSettings(shadowAutoplayDataCollectionEnabled = false)
            ),
            deviceCapabilityRepository = mockk<com.nexio.tv.data.repository.device.DeviceCapabilityRepository>().also {
                coEvery { it.snapshotForAutoplay() } returns sampleSnapshot()
            },
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            tokenProvider = { "write-token" },
            clientInfoProvider = { clientInfoJson("device-x") }
        )

        uploader.submitOnceIfEnabled()

        assertEquals(0, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `device capability uploader posts at most once per instance`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))

        val uploader = DeviceCapabilityReportUploader(
            playerSettingsDataStore = playerSettingsDataStore(
                PlayerSettings(shadowAutoplayDataCollectionEnabled = true)
            ),
            deviceCapabilityRepository = mockk<com.nexio.tv.data.repository.device.DeviceCapabilityRepository>().also {
                coEvery { it.snapshotForAutoplay() } returns sampleSnapshot()
            },
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            tokenProvider = { "write-token" },
            clientInfoProvider = { clientInfoJson("device-x") }
        )

        uploader.submitOnceIfEnabled()
        uploader.submitOnceIfEnabled()
        uploader.submitOnceIfEnabled()

        assertEquals(1, server.requestCount)
        server.shutdown()
    }

    private fun sampleSnapshot() = DeviceCapabilitySnapshot(
        model = "Google TV Streamer",
        manufacturer = "Google",
        sdkInt = 34,
        displayHdrTypes = setOf(DeviceHdrType.HDR10),
        videoDecode = DeviceVideoDecodeCapabilities(decoders = emptyList()),
        audioOutput = DeviceAudioOutputCapabilities(passthrough = emptyList()),
        evidence = null,
        capturedAtMs = 1775519900000L
    )
```

Add the imports at the top of the file (de-dupe with existing imports):

```kotlin
import io.mockk.coEvery
```

NOTE: If the existing `clientInfoJson` and `playerSettingsDataStore` helpers are private to the existing test class, either move the new tests into the same class or copy those helpers into a new class.

- [ ] **Step 2: Run the failing tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.CollectorUploadersTest.*device capability*"`
Expected: compilation failure — `Unresolved reference: DeviceCapabilityReportUploader`.

- [ ] **Step 3: Commit the failing tests**

```bash
git add app/src/test/java/com/nexio/tv/data/repository/benchmark/CollectorUploadersTest.kt
git commit -m "test(benchmark): add failing DeviceCapabilityReportUploader tests"
```

---

## Task 3: Implement `DeviceCapabilityReportUploader`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilityReportUploader.kt`

- [ ] **Step 1: Write the uploader**

```kotlin
// app/src/main/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilityReportUploader.kt
package com.nexio.tv.data.repository.benchmark

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.gson.JsonObject
import com.nexio.tv.BuildConfig
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.repository.device.DeviceCapabilityRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "DeviceCapabilityUpload"

@Singleton
class DeviceCapabilityReportUploader internal constructor(
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val deviceCapabilityRepository: DeviceCapabilityRepository,
    private val okHttpClient: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String,
    private val clientInfoProvider: () -> JsonObject
) {
    private val submitted = AtomicBoolean(false)

    @Inject
    constructor(
        @ApplicationContext context: Context,
        playerSettingsDataStore: PlayerSettingsDataStore,
        deviceCapabilityRepository: DeviceCapabilityRepository,
        okHttpClient: OkHttpClient
    ) : this(
        playerSettingsDataStore = playerSettingsDataStore,
        deviceCapabilityRepository = deviceCapabilityRepository,
        okHttpClient = okHttpClient,
        baseUrlProvider = { BuildConfig.SHADOW_DATA_COLLECTION_BASE_URL.trim().trimEnd('/') },
        tokenProvider = { BuildConfig.SHADOW_DATA_COLLECTION_WRITE_TOKEN.trim() },
        clientInfoProvider = { buildCapabilityCollectorClientInfo(context) }
    )

    suspend fun submitOnceIfEnabled() {
        if (submitted.get()) return
        val settings = playerSettingsDataStore.playerSettings.first()
        if (!settings.shadowAutoplayDataCollectionEnabled) return
        val baseUrl = baseUrlProvider()
        val token = tokenProvider()
        if (baseUrl.isBlank() || token.isBlank()) return
        val snapshot = deviceCapabilityRepository.snapshotForAutoplay() ?: return
        if (!submitted.compareAndSet(false, true)) return

        val envelope = JsonObject().apply {
            addProperty("sentAtMs", System.currentTimeMillis())
            add("client", clientInfoProvider())
            add("report", DeviceCapabilitySnapshotSerializer.toJson(snapshot))
        }.toString()

        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/api/v1/device-capability-reports")
                    .header("Authorization", "Bearer $token")
                    .post(envelope.toRequestBody("application/json".toMediaType()))
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        submitted.set(false)
                        Log.w(TAG, "Upload failed code=${response.code}")
                    }
                }
            }.onFailure { error ->
                submitted.set(false)
                Log.w(TAG, "Upload failed: ${error.message}")
            }
        }
    }
}

private fun buildCapabilityCollectorClientInfo(context: Context): JsonObject {
    return JsonObject().apply {
        addProperty("appVersion", BuildConfig.VERSION_NAME)
        addProperty("buildType", if (BuildConfig.IS_DEBUG_BUILD) "debug" else "release")
        addProperty("deviceModel", Build.MODEL)
        addProperty("sdkInt", Build.VERSION.SDK_INT)
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { addProperty("androidId", it) }
    }
}
```

NOTE on the `submitted` flag: it is set BEFORE the network call so concurrent callers cannot fan out duplicate requests. It is reset on failure so a transient network error does not silently disable uploads for the entire process — the next caller will retry. The test in Task 2 enqueues a 200 response, so the flag stays set after the first call and the second call short-circuits.

- [ ] **Step 2: Run the uploader tests and verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.CollectorUploadersTest.*device capability*"`
Expected: all three new tests PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DeviceCapabilityReportUploader.kt
git commit -m "feat(benchmark): add DeviceCapabilityReportUploader gated on data collection setting"
```

---

## Task 4: Wire the uploader into MainActivity

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/MainActivity.kt:382-384` (the existing `lifecycleScope.launch(Dispatchers.IO) { deviceCapabilityRepository.ensureCached() }` block).

- [ ] **Step 1: Inject the uploader and call it after `ensureCached`**

Find the existing block at MainActivity.kt:382:
```kotlin
        lifecycleScope.launch(Dispatchers.IO) {
            deviceCapabilityRepository.ensureCached()
        }
```

Replace with:
```kotlin
        lifecycleScope.launch(Dispatchers.IO) {
            deviceCapabilityRepository.ensureCached()
            deviceCapabilityReportUploader.submitOnceIfEnabled()
        }
```

Add the field declaration at the top of the class, alongside the other `@Inject lateinit var` fields (search for the existing `deviceCapabilityRepository` field declaration as a reference). The new field is:

```kotlin
    @Inject
    lateinit var deviceCapabilityReportUploader: com.nexio.tv.data.repository.benchmark.DeviceCapabilityReportUploader
```

Use a fully-qualified name only if the import would conflict; otherwise add a normal import at the top of the file:

```kotlin
import com.nexio.tv.data.repository.benchmark.DeviceCapabilityReportUploader
```

and declare:

```kotlin
    @Inject lateinit var deviceCapabilityReportUploader: DeviceCapabilityReportUploader
```

- [ ] **Step 2: Build and confirm DI graph resolves**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -40`
Expected: BUILD SUCCESSFUL. If Hilt fails with "cannot find binding for DeviceCapabilityReportUploader", verify the class is annotated `@Singleton` and has an `@Inject constructor` — both already done in Task 3.

- [ ] **Step 3: Commit the wiring**

```bash
git add app/src/main/java/com/nexio/tv/MainActivity.kt
git commit -m "feat(app): submit device capability report on cold-start when data collection is enabled"
```

---

## Task 5: Remove the dead `DebridBenchmarkCollectionUploader`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkService.kt`
- Delete: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkCollectionUploader.kt`

- [ ] **Step 1: Confirm the uploader is genuinely dead**

Run: `grep -rn 'DebridBenchmarkCollectionUploader' app/src 2>&1`
Expected: only the file itself, plus the three references in `DebridBenchmarkService.kt` (field at line 38, constructor parameter at line 57, constructor delegation at line 65, `submitIfEnabled` call at line 170). If anything else references it, stop and re-read the call sites — this task may need adjusting.

- [ ] **Step 2: Remove the field, constructor parameter, and call site from `DebridBenchmarkService`**

Edit `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkService.kt`:

Delete line 38: `    private val collectionUploader: DebridBenchmarkCollectionUploader,`

Delete the corresponding line in the `@Inject` constructor parameter list (line 57):
`        collectionUploader: DebridBenchmarkCollectionUploader,`

Delete the corresponding line in the constructor delegation (line 65):
`        collectionUploader = collectionUploader,`

Delete the call site (line 170):
`                collectionUploader.submitIfEnabled(rawResult)`

If the surrounding code at line 170 has a `runCatching` or other wrapping, remove only the single `collectionUploader.submitIfEnabled(rawResult)` line. Verify the remaining method body still compiles.

- [ ] **Step 3: Delete the uploader source file**

Run: `git rm app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkCollectionUploader.kt`
Expected: file removed from index.

- [ ] **Step 4: Build the app**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the relevant unit tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.*"`
Expected: all PASS — including the existing shadow autoplay uploader test, the new serializer tests, and the new capability uploader tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkService.kt \
        app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkCollectionUploader.kt
git commit -m "refactor(benchmark): remove dead DebridBenchmarkCollectionUploader"
```

---

## Task 6: Final verification

- [ ] **Step 1: Run the full unit-test suite**

Run: `./gradlew :app:testDebugUnitTest 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL with all tests passing.

- [ ] **Step 2: Build a debug APK to confirm Hilt graph and ProGuard pass**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke test on a real device or emulator (optional but recommended)**

1. Install the debug APK.
2. Open the app once with data collection **disabled** (default). Verify Logcat shows no `DeviceCapabilityUpload` log lines.
3. Enable "Shadow autoplay data collection" in Debrid settings.
4. Cold-start the app (force-stop, then reopen). Verify Logcat shows at most one `DeviceCapabilityUpload` line per cold-start, and that the collector dashboard at `/public/<token>` shows the latest capability report. Re-cold-start once more and confirm only one row exists for the device on the server (UPSERT).

If the manual step is skipped, note it in the PR description.

- [ ] **Step 4: Verify Git status is clean**

Run: `git status`
Expected: clean working tree, all 5 task commits visible in `git log --oneline`.

---

## Out of Scope (do not implement here)

- Migrating any historical local benchmark data.
- Changing the `shadowAutoplayDataCollectionEnabled` setting name or surface.
- Changing the `DeviceCapabilitySnapshot` data classes themselves — the serializer adapts to whatever fields they expose today.
- Re-uploading the report on settings toggle ON. Phase-1 is once-per-cold-start; the user can force-stop and re-open if they want to push a new report after toggling.
- Server-side changes — those live in the companion plan `2026-04-27-device-capability-report-server.md` in the `nexio-datacollection` repository.
