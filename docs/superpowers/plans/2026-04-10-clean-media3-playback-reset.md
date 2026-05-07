# Clean Media3 Playback Reset Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the current VOD cache, parallel connections/PRDS, and playback-trace stack, restore a clean Media3 playback path modeled after `~/Scripts/Nuvio-Fork`, and verify stable normal playback before any new advanced playback work begins.

**Architecture:** Replace the current layered playback stack with a simple Media3/OkHttp pipeline: one playback HTTP client, standard Media3 `DefaultMediaSourceFactory` / HLS / DASH handling, MIME probing, and no playback-specific cache, PRDS, or diagnostics runtime. The reset is intentionally destructive: remove the current advanced playback features completely rather than trying to preserve compatibility shims that keep dead architecture alive.

**Tech Stack:** Android/Kotlin, Media3, OkHttp, JUnit4, Robolectric, Hilt, DataStore

---

## File Map

### Production files

- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`
  - restore a clean, reusable playback networking helper modeled after `Nuvio-Fork`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - replace the current VOD cache / PRDS / trace-aware factory with a clean Media3 playback factory
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - remove PRDS/cache/trace wiring and restore simple player initialization
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
  - stop pushing VOD cache / parallel settings into the player factory
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
  - remove playback trace session lifecycle hooks
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/NexioApplication.kt`
  - remove playback-trace startup initialization
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
  - remove VOD cache and parallel connection controls
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
  - stop rendering playback diagnostics and parallel/cache controls
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
  - remove diagnostics section wiring
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
  - remove playback trace / adb-control dependencies
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
  - remove persisted VOD cache / parallel connection settings
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt`
  - stop depending on `ParallelRangeDataSource`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunner.kt`
  - stop threading parallel-connection configuration into transport selection
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/DirectProfileBenchmarkTransport.kt`
  - become the only benchmark transport path temporarily
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/DualLaneScheduler.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/AbsoluteByteStore.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SequentialReadCursor.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/RollingHorizonManager.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/TransportPolicyController.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTracer.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/TraceRecord.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PayloadBuilder.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceToggle.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceAdbReceiver.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceAdbControlToggle.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/DeviceHealthSampler.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackOkHttpEventListener.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollector.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationSessionStore.kt`

### Test files

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
  - rewrite around the clean Media3 playback path
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitializationTest.kt`
  - assert player initialization no longer touches PRDS/cache/trace configuration
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/settings/DebridSettingsViewModelTest.kt`
  - remove playback trace expectations
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/settings/CatalogSelectionPersistenceTest.kt`
  - keep unrelated settings coverage stable after playback-setting removal
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransportTest.kt`
  - rewrite to direct transport-only expectations
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceToggleTest.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTracerTest.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceReopenCostTest.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/SequentialReadCursorTest.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollectorTest.kt`

### Documentation files

- Modify: `/Users/jneerdael/Scripts/nexio/docs/instrumentation/collecting-traces.md`
  - remove playback trace user guidance
- Modify: `/Users/jneerdael/Scripts/nexio/docs/instrumentation/android-tv-playback-architecture-audit.md`
  - add note that the legacy stack was removed and replaced with clean Media3

## Guardrails

- Do not preserve compatibility shims for PRDS, VOD cache, or playback trace.
- Do not leave dead settings in DataStore or the settings UI.
- Do not keep benchmark code referencing deleted playback transport classes.
- The replacement playback path must be modeled on `Nuvio-Fork` simplicity, not on the current Nexio player stack.
- Stability verification must happen before any follow-up “new architecture” planning.

---

### Task 1: Restore A Clean Media3 Playback Factory

**Files:**
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Write the failing clean-factory test**

```kotlin
@Test
fun `progressive playback uses plain Media3 datasource without cache or PRDS`() {
    val factory = PlayerMediaSourceFactory()

    val mediaSource = factory.createMediaSource(
        context = context,
        url = "https://example.com/video.mkv",
        headers = mapOf("Authorization" to "Bearer token"),
    )

    assertTrue(mediaSource is ProgressiveMediaSource)
    assertFalse(PlayerMediaSourceFactory::class.java.name.contains("ParallelRange"))
}
```

- [ ] **Step 2: Run the player factory test to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest"`

Expected: FAIL because the current factory still wires cache, PRDS, and trace behavior.

- [ ] **Step 3: Add the clean playback networking helper modeled after `Nuvio-Fork`**

```kotlin
internal object PlayerPlaybackNetworking {
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val playbackHostnameVerifier = HostnameVerifier { _, _ -> true }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    private val playbackHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier(playbackHostnameVerifier)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    @OptIn(UnstableApi::class)
    fun createDataSourceFactory(
        context: Context,
        defaultHeaders: Map<String, String> = emptyMap()
    ): DataSource.Factory {
        val httpFactory = OkHttpDataSource.Factory(playbackHttpClient).apply {
            setDefaultRequestProperties(defaultHeaders)
            setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
        }
        return DefaultDataSource.Factory(context, httpFactory)
    }
}
```

- [ ] **Step 4: Replace the current factory body with the clean Media3 path**

```kotlin
internal class PlayerMediaSourceFactory {
    private var customExtractorsFactory: ExtractorsFactory? = null
    private var customSubtitleParserFactory: SubtitleParser.Factory? = null

    fun createMediaSource(
        context: Context,
        url: String,
        headers: Map<String, String>,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList(),
        filename: String? = null,
        responseHeaders: Map<String, String> = emptyMap(),
        mimeTypeOverride: String? = null
    ): MediaSource {
        val sanitizedHeaders = sanitizeHeaders(headers)
        val dataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(context, sanitizedHeaders)
        val resolvedMimeType = mimeTypeOverride ?: inferMimeType(url, filename, responseHeaders)
        val isHls = resolvedMimeType == MimeTypes.APPLICATION_M3U8
        val isDash = resolvedMimeType == MimeTypes.APPLICATION_MPD

        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        resolvedMimeType?.let(mediaItemBuilder::setMimeType)
        if (subtitleConfigurations.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigurations)
        }
        val mediaItem = mediaItemBuilder.build()

        val extractorsFactory = customExtractorsFactory ?: DefaultExtractorsFactory()
        val defaultFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory).apply {
            customSubtitleParserFactory?.let(::setSubtitleParserFactory)
        }

        return when {
            subtitleConfigurations.isNotEmpty() -> defaultFactory.createMediaSource(mediaItem)
            isHls -> HlsMediaSource.Factory(dataSourceFactory).setAllowChunklessPreparation(true).createMediaSource(mediaItem)
            isDash -> DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            else -> defaultFactory.createMediaSource(mediaItem)
        }
    }

    fun shutdown() = Unit
}
```

- [ ] **Step 5: Run architecture review for the clean playback factory**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`

Expected review outcome:
- playback has one simple Media3/OkHttp path
- no cache/PRDS/trace dependencies remain in the factory

- [ ] **Step 6: Re-run the player factory test and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "refactor: restore clean Media3 playback factory"
```

---

### Task 2: Remove PRDS And VOD Cache Wiring From Player Runtime

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitializationTest.kt`

- [ ] **Step 1: Write the failing initialization test**

```kotlin
@Test
fun `initializePlayer no longer configures parallel or cache state`() {
    controller.initializePlayer(url = "https://example.com/video.mkv", headers = emptyMap())

    assertFalse(controller.mediaSourceFactory::class.java.declaredFields.any { it.name.contains("vodCache", ignoreCase = true) })
}
```

- [ ] **Step 2: Run the initialization test to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerRuntimeControllerInitializationTest"`

Expected: FAIL because initialization still pushes `useParallelConnections`, `parallelConnectionCount`, `parallelChunkSizeMb`, and `vodCacheSizeMode` into the factory.

- [ ] **Step 3: Remove advanced playback configuration from initialization**

```kotlin
// Delete:
mediaSourceFactory.useParallelConnections = playerSettings.useParallelConnections
mediaSourceFactory.parallelConnectionCount = playerSettings.parallelConnectionCount
mediaSourceFactory.parallelChunkSizeMb = playerSettings.parallelChunkSizeMb
mediaSourceFactory.vodCacheSizeMode = playerSettings.vodCacheSizeMode
mediaSourceFactory.vodCacheSizeMb = playerSettings.vodCacheSizeMb
refreshPlaybackTraceProvenanceForCurrentStream()
```

- [ ] **Step 4: Remove trace session lifecycle hooks from the runtime controller**

```kotlin
// Delete calls to:
mediaSourceFactory.endPlaybackTraceSession()
transportValidationRuntimeCollector.bindSession(null)
```

- [ ] **Step 5: Run architecture review for runtime cleanup**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`

Expected review outcome:
- playback runtime no longer depends on removed advanced transport/caching/tracing layers

- [ ] **Step 6: Re-run initialization test and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerRuntimeControllerInitializationTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitializationTest.kt
git commit -m "refactor: remove advanced playback wiring from runtime init"
```

---

### Task 3: Remove Playback Trace Feature Completely

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/NexioApplication.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTracer.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/TraceRecord.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PayloadBuilder.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceToggle.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceAdbReceiver.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceAdbControlToggle.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/DeviceHealthSampler.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/instrumentation/PlaybackOkHttpEventListener.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollector.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationSessionStore.kt`

- [ ] **Step 1: Write the failing settings test**

```kotlin
@Test
fun `playback settings no longer expose diagnostics actions`() {
    val rendered = renderPlaybackSettingsScreen()
    assertFalse(rendered.contains("Allow ADB control"))
    assertFalse(rendered.contains("Clear all traces"))
}
```

- [ ] **Step 2: Run the settings test to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.DebridSettingsViewModelTest"`

Expected: FAIL because playback trace settings and actions still exist.

- [ ] **Step 3: Remove playback-trace startup wiring from `NexioApplication`**

```kotlin
// Delete:
@Inject lateinit var playbackTraceToggle: PlaybackTraceToggle
PlaybackTracer.installFilesDir(this)
PlaybackTracer.enabled = runBlocking { playbackTraceToggle.enabledFlow.first() }
PlaybackTracer.applyCrashIsolationProfile()
Log.i(TAG, "playback trace restored enabled=${PlaybackTracer.enabled}")
```

- [ ] **Step 4: Remove diagnostics UI and feature dependencies**

```kotlin
// Delete diagnostics section wiring and state:
onTogglePlaybackTrace = { ... }
onTogglePlaybackTraceAdbControl = { ... }
playbackTraceEnabled
playbackTraceAdbControlEnabled
export/copy/clear trace actions
```

- [ ] **Step 5: Delete the instrumentation and runtime collector files**

```bash
git rm app/src/main/java/com/nexio/tv/instrumentation/PlaybackTracer.kt
git rm app/src/main/java/com/nexio/tv/instrumentation/SessionWriter.kt
git rm app/src/main/java/com/nexio/tv/instrumentation/TraceRecord.kt
git rm app/src/main/java/com/nexio/tv/instrumentation/PayloadBuilder.kt
git rm app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt
git rm app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceToggle.kt
git rm app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceAdbReceiver.kt
git rm app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceAdbControlToggle.kt
git rm app/src/main/java/com/nexio/tv/instrumentation/DeviceHealthSampler.kt
git rm app/src/main/java/com/nexio/tv/instrumentation/PlaybackOkHttpEventListener.kt
git rm app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollector.kt
git rm app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationSessionStore.kt
```

- [ ] **Step 6: Run architecture review for trace removal**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/NexioApplication.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt`

Expected review outcome:
- no playback lifecycle path depends on the removed diagnostics stack
- settings UI reflects the feature removal cleanly

- [ ] **Step 7: Re-run the settings test and commit**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.DebridSettingsViewModelTest"`

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/NexioApplication.kt
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt
git add app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt
git commit -m "refactor: remove playback trace feature"
```

---

### Task 4: Remove PRDS, VOD Cache, And Related Settings Completely

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunner.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/DirectProfileBenchmarkTransport.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/DualLaneScheduler.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/AbsoluteByteStore.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/SequentialReadCursor.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/RollingHorizonManager.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/TransportPolicyController.kt`

- [ ] **Step 1: Write the failing settings-storage test**

```kotlin
@Test
fun `player settings no longer persist parallel or vod cache fields`() {
    val defaults = PlayerSettingsDefaults.load()

    assertFalse(defaults.containsKey("use_parallel_connections"))
    assertFalse(defaults.containsKey("parallel_connection_count"))
    assertFalse(defaults.containsKey("parallel_chunk_size_mb"))
    assertFalse(defaults.containsKey("vod_cache_size_mode"))
    assertFalse(defaults.containsKey("vod_cache_size_mb"))
}
```

- [ ] **Step 2: Run the relevant settings/benchmark tests to verify failure**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.ui.screens.settings.CatalogSelectionPersistenceTest" \
  --tests "com.nexio.tv.data.repository.benchmark.OptimizedBenchmarkTransportTest"
```

Expected: FAIL because the app still persists and uses the removed settings and transport classes.

- [ ] **Step 3: Remove VOD cache and parallel settings from `PlayerSettingsDataStore`**

```kotlin
// Delete from PlayerSettings:
val vodCacheSizeMode: VodCacheSizeMode
val vodCacheSizeMb: Int
val useParallelConnections: Boolean
val parallelConnectionCount: Int
val parallelChunkSizeMb: Int

// Delete related preference keys and migrations:
vodCacheSizeModeKey
vodCacheSizeMbKey
useParallelConnectionsKey
parallelConnectionCountKey
parallelChunkSizeMbKey
```

- [ ] **Step 4: Remove UI controls and benchmark transport dependence**

```kotlin
// PlaybackBufferNetworkSettings:
// keep only a short explanatory note or remove the section entirely

Text(
    text = stringResource(R.string.playback_network_reset_message),
    style = MaterialTheme.typography.bodySmall,
    color = NexioColors.TextSecondary
)
```

And in benchmark code:

```kotlin
// DirectProfileBenchmarkTransport becomes the only transport.
val dataSourceFactory: DataSource.Factory = directFactory
```

- [ ] **Step 5: Delete the PRDS / VOD cache classes**

```bash
git rm app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt
git rm app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt
git rm app/src/main/java/com/nexio/tv/ui/screens/player/DualLaneScheduler.kt
git rm app/src/main/java/com/nexio/tv/ui/screens/player/AbsoluteByteStore.kt
git rm app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt
git rm app/src/main/java/com/nexio/tv/ui/screens/player/SequentialReadCursor.kt
git rm app/src/main/java/com/nexio/tv/ui/screens/player/RollingHorizonManager.kt
git rm app/src/main/java/com/nexio/tv/ui/screens/player/TransportPolicyController.kt
```

- [ ] **Step 6: Run architecture review for the reset**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/repository/benchmark/DirectProfileBenchmarkTransport.kt`

Expected review outcome:
- no dead settings or dead transport abstractions remain
- baseline playback and benchmark paths use one simple direct transport

- [ ] **Step 7: Re-run settings/benchmark tests and commit**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.ui.screens.settings.CatalogSelectionPersistenceTest" \
  --tests "com.nexio.tv.data.repository.benchmark.OptimizedBenchmarkTransportTest"
```

Expected: PASS

```bash
git add app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunner.kt
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DirectProfileBenchmarkTransport.kt
git commit -m "refactor: remove legacy parallel and vod cache stack"
```

---

### Task 5: Delete Obsolete Tests And Restore Stable Playback Verification

**Files:**
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceToggleTest.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/PlaybackTracerTest.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceReopenCostTest.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/SequentialReadCursorTest.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt`
- Delete: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollectorTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/docs/instrumentation/collecting-traces.md`
- Modify: `/Users/jneerdael/Scripts/nexio/docs/instrumentation/android-tv-playback-architecture-audit.md`

- [ ] **Step 1: Delete tests that only validate removed architecture**

```bash
git rm app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceControllerTest.kt
git rm app/src/test/java/com/nexio/tv/instrumentation/PlaybackTraceToggleTest.kt
git rm app/src/test/java/com/nexio/tv/instrumentation/PlaybackTracerTest.kt
git rm app/src/test/java/com/nexio/tv/instrumentation/SessionWriterJsonlIntegrityTest.kt
git rm app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt
git rm app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceReopenCostTest.kt
git rm app/src/test/java/com/nexio/tv/ui/screens/player/SequentialReadCursorTest.kt
git rm app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt
git rm app/src/test/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollectorTest.kt
```

- [ ] **Step 2: Update docs to reflect removal**

```markdown
## Playback diagnostics removed

The legacy playback trace, VOD cache, and parallel connection stack were removed as part of the playback reset.
This document is kept only as historical context and is not applicable to the current player.
```

- [ ] **Step 3: Run the stability verification suite**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" \
  --tests "com.nexio.tv.ui.screens.player.PlayerRuntimeControllerInitializationTest" \
  --tests "com.nexio.tv.ui.screens.settings.DebridSettingsViewModelTest" \
  --tests "com.nexio.tv.data.repository.benchmark.OptimizedBenchmarkTransportTest"
```

Expected: PASS

- [ ] **Step 4: Run compile and build gates**

Run:

```bash
./gradlew :app:compileUniversalDebugKotlin
./gradlew --no-daemon :app:assembleUniversalDebug
```

Expected: PASS

- [ ] **Step 5: Install and verify normal playback stability on device**

Run:

```bash
adb -s 192.168.50.58:5555 install -r app/build/outputs/apk/universal/debug/app-universal-debug.apk
```

Then verify on device:

1. open normal playback
2. confirm playback starts
3. confirm no PRDS/VOD cache/trace settings appear
4. confirm no immediate crash or zero-byte playback trace file problem exists because the feature is gone

- [ ] **Step 6: Run architecture review for the final reset**

Review target:
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`

Expected review outcome:
- playback has returned to a clean Media3 baseline
- advanced playback layers are fully gone
- the codebase is in a safe state for future best-practice reintroduction

- [ ] **Step 7: Commit the reset**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt
git add app/src/main/java/com/nexio/tv/NexioApplication.kt
git add app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt
git add app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkSessionRunner.kt
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/DirectProfileBenchmarkTransport.kt
git add docs/instrumentation/collecting-traces.md
git add docs/instrumentation/android-tv-playback-architecture-audit.md
git commit -m "refactor: reset playback to clean Media3 baseline"
```

