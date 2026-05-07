# Read-Only Streaming Cache Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a developer-flagged, read-only Media3 cache layer that can be enabled without reintroducing VOD cache write-through, PRDS, background fill workers, playback trace, or any extra playback threads.

**Architecture:** Phase 1 only adds a `SimpleCache` singleton and a read-only `CacheDataSource.Factory` wrapper around the existing clean `DefaultDataSource.Factory`. The feature flag defaults off, and the off path must remain byte-for-byte equivalent in behavior: no `SimpleCache` opened, no cache directory created, no fill worker, and plain OkHttp-backed `DefaultDataSource` used. Cache writes are disabled by setting `CacheDataSource.Factory.setCacheWriteDataSinkFactory(null)`.

**Tech Stack:** Kotlin, Android Media3 `CacheDataSource` / `SimpleCache` / `LeastRecentlyUsedCacheEvictor` / `StandaloneDatabaseProvider`, OkHttp, Robolectric, JUnit4, MockK, Gradle.

---

## Scope Boundary

This plan implements only Phase 1 from the approved architecture spec:

- Add a developer-only streaming cache flag, default off.
- Add `StreamingCacheProvider` for lazy `SimpleCache` lifecycle.
- Add read-only `CacheDataSource` wiring when the flag is on and the URL is HTTP(S).
- Add invariant tests proving the flag-off path stays clean.

This plan deliberately does not implement:

- `CacheFillWorker`
- `CacheMissCoordinator`
- `FillController`
- `BandwidthMonitor`
- `MemoryBudget`
- provider probing
- second fill connection
- LoadControl retuning
- normal settings UI
- JSONL tracing

Those belong in later plans after Phase 1 is validated and committed.

## File Structure

- Create `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheProvider.kt`
  - Owns lazy `SimpleCache` creation and release.
  - Exposes test-visible `hasCacheInstance` and `cacheDirectory`.
  - Does not start threads or write media bytes.

- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`
  - Keeps existing clean `DefaultDataSource.Factory` path as default.
  - Adds optional read-only `CacheDataSource.Factory` wrapper.
  - Does not open `SimpleCache` unless `useStreamingCache = true` and a provider is supplied.

- Modify `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - Adds a `StreamingCacheProvider` constructor dependency with a safe default.
  - Adds `streamingCacheEnabled` flag defaulting to `false`.
  - Enables the cache wrapper only for HTTP(S) URLs.
  - Releases the cache in `shutdown()`.

- Modify `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
  - Adds tests for flag-off invariants.
  - Adds tests for flag-on read-only cache factory wiring.
  - Keeps existing HLS/DASH/progressive tests green.

## Invariants

The worker must preserve these invariants while implementing every task:

- Feature flag off: `PlayerPlaybackNetworking.createDataSourceFactory(...)` returns the same plain `DefaultDataSource.Factory` shape as the current reset baseline.
- Feature flag off: no `SimpleCache` object is created.
- Feature flag off: no `stream-cache` directory is created by playback factory construction or media source creation.
- Feature flag on: cache write sink is explicitly null.
- Feature flag on: no background worker, no extra thread, no provider probe, no LoadControl changes.
- Non-HTTP URLs never activate streaming cache, even if the feature flag is true.

---

### Task 1: Add Lazy Streaming Cache Provider

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheProvider.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Write the failing provider laziness test**

Add this test to `PlayerMediaSourceFactoryTest`:

```kotlin
@Test
fun streamingCacheProvider_doesNotCreateCacheUntilRequested() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val provider = StreamingCacheProvider(
        context = context,
        cacheDirectoryName = "stream-cache-lazy-${System.nanoTime()}"
    )

    assertFalse(provider.hasCacheInstance)
    assertFalse(provider.cacheDirectory.exists())
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest"
```

Expected: fail with an unresolved reference to `StreamingCacheProvider`.

- [ ] **Step 3: Create the minimal provider implementation**

Create `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheProvider.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
internal class StreamingCacheProvider(
    context: Context,
    private val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
    private val cacheDirectoryName: String = DEFAULT_CACHE_DIRECTORY_NAME,
) {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var cache: SimpleCache? = null

    val cacheDirectory: File
        get() = File(appContext.cacheDir, cacheDirectoryName)

    @get:VisibleForTesting
    val hasCacheInstance: Boolean
        get() = cache != null

    fun getOrCreateCache(): SimpleCache {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val created = SimpleCache(
                cacheDirectory,
                LeastRecentlyUsedCacheEvictor(maxCacheBytes),
                StandaloneDatabaseProvider(appContext)
            )
            cache = created
            return created
        }
    }

    fun release() {
        synchronized(lock) {
            cache?.release()
            cache = null
        }
    }

    companion object {
        const val DEFAULT_CACHE_DIRECTORY_NAME = "stream-cache"
        const val DEFAULT_MAX_CACHE_BYTES = 500L * 1024L * 1024L
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest"
```

Expected: pass, including `streamingCacheProvider_doesNotCreateCacheUntilRequested`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheProvider.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "feat: add lazy streaming cache provider"
```

---

### Task 2: Add Read-Only CacheDataSource Factory Wiring

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Write failing tests for off/on networking behavior**

Add these tests to `PlayerMediaSourceFactoryTest`:

```kotlin
@Test
fun playbackNetworking_flagOff_returnsPlainDefaultDataSource_andDoesNotOpenCache() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val provider = StreamingCacheProvider(
        context = context,
        cacheDirectoryName = "stream-cache-off-${System.nanoTime()}"
    )

    val factory = PlayerPlaybackNetworking.createDataSourceFactory(
        context = context,
        client = OkHttpClient(),
        defaultHeaders = emptyMap(),
        streamingCacheProvider = provider,
        useStreamingCache = false
    )
    val dataSource = factory.createDataSource()

    assertFalse(dataSource is androidx.media3.datasource.cache.CacheDataSource)
    assertFalse(provider.hasCacheInstance)
    assertFalse(provider.cacheDirectory.exists())
}

@Test
fun playbackNetworking_flagOn_returnsReadOnlyCacheDataSource_andOpensCache() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val provider = StreamingCacheProvider(
        context = context,
        cacheDirectoryName = "stream-cache-on-${System.nanoTime()}"
    )

    val factory = PlayerPlaybackNetworking.createDataSourceFactory(
        context = context,
        client = OkHttpClient(),
        defaultHeaders = emptyMap(),
        streamingCacheProvider = provider,
        useStreamingCache = true
    )
    val dataSource = factory.createDataSource()

    assertTrue(dataSource is androidx.media3.datasource.cache.CacheDataSource)
    assertTrue(provider.hasCacheInstance)
    assertTrue(provider.cacheDirectory.exists())

    provider.release()
    provider.cacheDirectory.deleteRecursively()
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest"
```

Expected: fail because `createDataSourceFactory` does not yet accept `streamingCacheProvider` and `useStreamingCache`.

- [ ] **Step 3: Implement optional read-only cache factory wiring**

Replace `PlayerPlaybackNetworking.createDataSourceFactory(...)` with this implementation in `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient

internal object PlayerPlaybackNetworking {
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    @androidx.annotation.OptIn(UnstableApi::class)
    fun createDataSourceFactory(
        context: Context,
        client: OkHttpClient,
        defaultHeaders: Map<String, String> = emptyMap(),
        streamingCacheProvider: StreamingCacheProvider? = null,
        useStreamingCache: Boolean = false,
    ): DataSource.Factory {
        val httpFactory = OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(defaultHeaders)
            setUserAgent(DEFAULT_USER_AGENT)
        }
        val upstreamFactory = DefaultDataSource.Factory(context, httpFactory)
        if (!useStreamingCache || streamingCacheProvider == null) {
            return upstreamFactory
        }
        return CacheDataSource.Factory()
            .setCache(streamingCacheProvider.getOrCreateCache())
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest"
```

Expected: pass.

- [ ] **Step 5: Run compile**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "feat: add read-only streaming cache data source"
```

---

### Task 3: Wire Developer Flag Into PlayerMediaSourceFactory

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Write failing factory-level invariant tests**

Add these tests to `PlayerMediaSourceFactoryTest`:

```kotlin
@Test
fun mediaSourceFactory_flagOff_doesNotOpenStreamingCache() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val provider = StreamingCacheProvider(
        context = context,
        cacheDirectoryName = "media-source-cache-off-${System.nanoTime()}"
    )
    val factory = PlayerMediaSourceFactory(
        context = context,
        playbackOkHttpClient = OkHttpClient(),
        streamingCacheProvider = provider
    )

    factory.createMediaSource(
        url = "https://example.com/movie.mkv",
        headers = emptyMap()
    )

    assertFalse(provider.hasCacheInstance)
    assertFalse(provider.cacheDirectory.exists())
    factory.shutdown()
}

@Test
fun mediaSourceFactory_flagOnForHttp_opensStreamingCache() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val provider = StreamingCacheProvider(
        context = context,
        cacheDirectoryName = "media-source-cache-on-${System.nanoTime()}"
    )
    val factory = PlayerMediaSourceFactory(
        context = context,
        playbackOkHttpClient = OkHttpClient(),
        streamingCacheProvider = provider
    )

    factory.streamingCacheEnabled = true
    factory.createMediaSource(
        url = "https://example.com/movie.mkv",
        headers = emptyMap()
    )

    assertTrue(provider.hasCacheInstance)
    assertTrue(provider.cacheDirectory.exists())
    factory.shutdown()
    provider.cacheDirectory.deleteRecursively()
}

@Test
fun mediaSourceFactory_flagOnForAsset_doesNotOpenStreamingCache() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val provider = StreamingCacheProvider(
        context = context,
        cacheDirectoryName = "media-source-cache-asset-${System.nanoTime()}"
    )
    val factory = PlayerMediaSourceFactory(
        context = context,
        playbackOkHttpClient = OkHttpClient(),
        streamingCacheProvider = provider
    )

    factory.streamingCacheEnabled = true
    factory.createMediaSource(
        url = "asset:///movie.mkv",
        headers = emptyMap()
    )

    assertFalse(provider.hasCacheInstance)
    assertFalse(provider.cacheDirectory.exists())
    factory.shutdown()
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest"
```

Expected: fail because `PlayerMediaSourceFactory` does not yet accept `streamingCacheProvider` and does not expose `streamingCacheEnabled`.

- [ ] **Step 3: Modify PlayerMediaSourceFactory constructor and data source creation**

Change the constructor and `createMediaSource` data-source creation in `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt` to:

```kotlin
internal class PlayerMediaSourceFactory(
    private val context: Context,
    private val playbackOkHttpClient: OkHttpClient,
    private val streamingCacheProvider: StreamingCacheProvider = StreamingCacheProvider(context),
) {
    var streamingCacheEnabled: Boolean = false

    private var customExtractorsFactory: ExtractorsFactory? = null
    private var customSubtitleParserFactory: SubtitleParser.Factory? = null
    private val loadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy()
```

In `createMediaSource(...)`, replace the existing `PlayerPlaybackNetworking.createDataSourceFactory(...)` call with:

```kotlin
val dataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(
    context = context,
    client = playbackOkHttpClient,
    defaultHeaders = sanitizedHeaders,
    streamingCacheProvider = streamingCacheProvider,
    useStreamingCache = streamingCacheEnabled && usesHttpUpstream(url)
)
```

Replace `shutdown()` with:

```kotlin
fun shutdown() {
    streamingCacheProvider.release()
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest"
```

Expected: pass.

- [ ] **Step 5: Run compile**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "feat: gate streaming cache in media source factory"
```

---

### Task 4: Add Developer-Only Flag Source Without Settings UI

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`

- [ ] **Step 1: Add the debug setting flow**

In `app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt`, add:

```kotlin
private val streamingCacheEnabledKey = booleanPreferencesKey("streaming_cache_enabled")
```

Add the flow:

```kotlin
val streamingCacheEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
    prefs[streamingCacheEnabledKey] ?: false
}
```

Add the setter:

```kotlin
suspend fun setStreamingCacheEnabled(enabled: Boolean) {
    dataStore.edit { prefs ->
        prefs[streamingCacheEnabledKey] = enabled
    }
}
```

- [ ] **Step 2: Wire the runtime controller to keep the factory flag updated**

In `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`, add a job field near the other job fields:

```kotlin
internal var streamingCacheFlagJob: Job? = null
```

In the controller `init` block, add:

```kotlin
streamingCacheFlagJob = scope.launch {
    debugSettingsDataStore.streamingCacheEnabled.collect { enabled ->
        mediaSourceFactory.streamingCacheEnabled = enabled
    }
}
```

In `stopAndRelease()` or `onCleared()` cleanup, add:

```kotlin
streamingCacheFlagJob?.cancel()
streamingCacheFlagJob = null
```

If the current `PlayerRuntimeController` cleanup uses a single release method, place those two lines there so the collection cannot outlive the controller.

- [ ] **Step 3: Add startup flag assignment before first media source creation**

In `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`, after:

```kotlin
val playerSettings = playerSettingsDataStore.playerSettings.first()
```

add:

```kotlin
mediaSourceFactory.streamingCacheEnabled = debugSettingsDataStore.streamingCacheEnabled.first()
```

This ensures first playback creation does not race the background collector.

- [ ] **Step 4: Run compile**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin
```

Expected: pass.

- [ ] **Step 5: Run the factory tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest"
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt
git commit -m "feat: add developer flag for streaming cache"
```

---

### Task 5: Add Kill-Switch Skeleton

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheKillSwitch.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Add pure kill-switch decision tests**

Add these tests to `PlayerMediaSourceFactoryTest`:

```kotlin
@Test
fun streamingCacheKillSwitch_allowsWhenEnabledAndNoBadExit() {
    assertTrue(
        StreamingCacheKillSwitch.shouldEnable(
            requested = true,
            hasRecentLowMemoryOrSignaledExit = false
        )
    )
}

@Test
fun streamingCacheKillSwitch_disablesWhenRequestedFalse() {
    assertFalse(
        StreamingCacheKillSwitch.shouldEnable(
            requested = false,
            hasRecentLowMemoryOrSignaledExit = false
        )
    )
}

@Test
fun streamingCacheKillSwitch_disablesWhenRecentBadExitExists() {
    assertFalse(
        StreamingCacheKillSwitch.shouldEnable(
            requested = true,
            hasRecentLowMemoryOrSignaledExit = true
        )
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest"
```

Expected: fail because `StreamingCacheKillSwitch` does not exist.

- [ ] **Step 3: Create kill-switch helper**

Create `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheKillSwitch.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

internal object StreamingCacheKillSwitch {
    fun shouldEnable(
        requested: Boolean,
        hasRecentLowMemoryOrSignaledExit: Boolean
    ): Boolean {
        return requested && !hasRecentLowMemoryOrSignaledExit
    }

    fun hasRecentLowMemoryOrSignaledExit(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
        return activityManager
            .getHistoricalProcessExitReasons(null, 0, 5)
            .any { exit ->
                exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                    exit.reason == ApplicationExitInfo.REASON_SIGNALED
            }
    }
}
```

- [ ] **Step 4: Apply kill-switch decision during startup flag assignment**

In `PlayerRuntimeControllerInitialization.kt`, replace:

```kotlin
mediaSourceFactory.streamingCacheEnabled = debugSettingsDataStore.streamingCacheEnabled.first()
```

with:

```kotlin
val requestedStreamingCache = debugSettingsDataStore.streamingCacheEnabled.first()
val blockedByKillSwitch = StreamingCacheKillSwitch.hasRecentLowMemoryOrSignaledExit(context)
mediaSourceFactory.streamingCacheEnabled = StreamingCacheKillSwitch.shouldEnable(
    requested = requestedStreamingCache,
    hasRecentLowMemoryOrSignaledExit = blockedByKillSwitch
)
if (requestedStreamingCache && blockedByKillSwitch) {
    debugSettingsDataStore.setStreamingCacheEnabled(false)
}
```

- [ ] **Step 5: Run tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest"
```

Expected: pass.

- [ ] **Step 6: Run compile**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin
```

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheKillSwitch.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "feat: add streaming cache kill switch"
```

---

### Task 6: Phase 1 Final Verification And Device Smoke Test

**Files:**
- Verify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Verify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`
- Verify: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheProvider.kt`
- Verify: `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheKillSwitch.kt`
- Verify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Run removed-stack scan**

Run:

```bash
rg -n "ParallelRangeDataSource|SharedParallelTransportManager|PlaybackTrace|TransportValidation|DebridConfigBenchmark|vodCacheSizeMode|useParallelConnections|parallelConnectionCount|parallelChunkSizeMb" app/src/main/java app/src/test/java/com/nexio/tv
```

Expected: no output.

- [ ] **Step 2: Run focused unit test**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest"
```

Expected: pass.

- [ ] **Step 3: Run Kotlin compile**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin
```

Expected: pass.

- [ ] **Step 4: Assemble debug APK**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:assembleUniversalDebug
```

Expected: pass and produce `app/build/outputs/apk/universal/debug/app-universal-debug.apk`.

- [ ] **Step 5: Install to validation device**

Run:

```bash
adb -s 192.168.50.58:5555 install -r app/build/outputs/apk/universal/debug/app-universal-debug.apk
```

Expected: `Success`.

- [ ] **Step 6: Launch and check process**

Run:

```bash
adb -s 192.168.50.58:5555 shell monkey -p com.nexiodebug.tv 1
adb -s 192.168.50.58:5555 shell pidof com.nexiodebug.tv
adb -s 192.168.50.58:5555 shell dumpsys meminfo com.nexiodebug.tv | head -45
```

Expected:

```text
Events injected: 1
<non-empty pid>
TOTAL PSS: <well below reset baseline threshold for app launch>
```

Use the current reset launch snapshot as a rough reference: around `55 MB` PSS when no playback is running.

- [ ] **Step 7: Check exit info**

Run:

```bash
adb -s 192.168.50.58:5555 shell dumpsys activity exit-info com.nexiodebug.tv | head -55
```

Expected: no new `REASON_LOW_MEMORY` or `REASON_SIGNALED` entry after the install/launch. A `USER REQUESTED` exit caused by package reinstall is acceptable.

- [ ] **Step 8: Commit final verification note**

```bash
git status --short
git add app/src/main/java/com/nexio/tv/ui/screens/player app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "test: verify read-only streaming cache phase one"
```

---

## Self-Review

Spec coverage:

- One heap owner: Phase 1 does not introduce a fill worker or extra transfer heap. The read-only cache path does not write, and flag off remains clean.
- No playback-thread cache writes: Task 2 explicitly sets `setCacheWriteDataSinkFactory(null)`.
- Cache fill subordinate to playback: out of scope for Phase 1 because no fill worker exists.
- Explicit memory budget: out of scope for Phase 1 and reserved for Phase 3.
- Provider behavior measured: out of scope for Phase 1 and reserved for Phase 5.
- Clean baseline reachable: Tasks 2, 3, and 6 verify flag-off behavior.
- Minimal diagnostics: Phase 1 adds no diagnostics surface.
- Kill-switch: Task 5 adds the basic bad-exit gate before the feature can be used.

Placeholder scan:

- This plan contains no "TBD", "TODO", "implement later", "fill in details", or "similar to" directives.
- Every code-changing task includes concrete code snippets.

Type consistency:

- `StreamingCacheProvider` is introduced before use.
- `PlayerPlaybackNetworking.createDataSourceFactory(...)` signature is updated before `PlayerMediaSourceFactory` calls the new parameters.
- `StreamingCacheKillSwitch.shouldEnable(...)` and `hasRecentLowMemoryOrSignaledExit(...)` are defined before use.

Out-of-scope guardrail:

- Do not implement `CacheFillWorker`, `CacheMissCoordinator`, `FillController`, provider probes, LoadControl tuning, or second connection support in this Phase 1 plan.
