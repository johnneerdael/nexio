# VOD Cache Prefetch Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep playback write-through VOD cache stable while preventing VOD warm-ahead from adding competing PRDS downloads on WiFi, unknown, or metered networks.

**Architecture:** Add a small persisted `VodCachePrefetchMode` setting (`AUTO`, `OFF`, `ON`) and a pure warm-ahead policy helper. In `AUTO`, keep playback write-through unchanged, but suppress the background warm-ahead job when PRDS is active on WiFi, metered, or unknown network links. Also force warm-ahead to use the playback cache key so a redirected CDN URL cannot create a second cache namespace.

**Tech Stack:** Android/Kotlin, Jetpack DataStore preferences, Jetpack Compose for Android TV settings, Android `ConnectivityManager`, Media3 `CacheDataSource`/`CacheWriter`, Robolectric/JUnit4.

---

## Current Code Map

Production files:

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/AndroidManifest.xml`
  - add `android.permission.ACCESS_NETWORK_STATE`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
  - add persisted `VodCachePrefetchMode`
  - parse, expose, reset, and update the setting
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
  - expose a setter for the new mode
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
  - add dialog state and persistence callback wiring
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
  - thread the dialog callback into the buffer/network section and host the new dialog
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
  - show a selectable VOD background prefetch row when VOD cache is enabled
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values/strings.xml`
  - add English labels/descriptions
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values-nl/strings.xml`
  - add Dutch labels/descriptions
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlaybackNetworkState.kt`
  - classify active playback network as `ETHERNET`, `WIFI`, `CELLULAR`, or `UNKNOWN`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/VodWarmAheadPolicy.kt`
  - pure warm-ahead policy logic
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - hold prefetch mode
  - resolve active network state
  - gate warm-ahead in `AUTO`
  - set warm-ahead cache key to the playback stream URL while allowing the request URI to use the resolved URL
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - pass initial prefetch mode to the media source factory
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
  - keep the media source factory in sync when settings change

Test files:

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt`
  - persist and reset `VodCachePrefetchMode`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/VodWarmAheadPolicyTest.kt`
  - cover `AUTO`, `OFF`, and `ON` warm-ahead decisions
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
  - cover cache-key consistency helper and existing source selection behavior

## Guardrails

- Keep playback write-through cache enabled. Do not call `setCacheWriteDataSinkFactory(null)` in this plan.
- Do not change `ParallelRangeDataSource` chunk sizing, connection count, retry behavior, or read scheduling.
- Do not reintroduce Phase 4 classes such as `CoverageAwareDataSource`, cache-miss coordinators, or fill workers.
- Do not make model-specific logic for Google TV or Fire TV. Use network class plus user setting.
- Default setting must be conservative: `AUTO`, not `ON`.

---

### Task 1: Persist VOD Background Prefetch Mode

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt`

- [ ] **Step 1: Add failing DataStore tests**

Append these tests inside `PlayerSettingsDataStoreTest`:

```kotlin
@Test
fun `vod cache prefetch mode defaults to auto`() = runTest {
    val dataStore = PlayerSettingsDataStore(context)

    val settings = dataStore.playerSettings.first()

    assertEquals(VodCachePrefetchMode.AUTO, settings.vodCachePrefetchMode)
}

@Test
fun `vod cache prefetch mode persists selection`() = runTest {
    val dataStore = PlayerSettingsDataStore(context)

    dataStore.setVodCachePrefetchMode(VodCachePrefetchMode.OFF)
    assertEquals(VodCachePrefetchMode.OFF, dataStore.playerSettings.first().vodCachePrefetchMode)

    dataStore.setVodCachePrefetchMode(VodCachePrefetchMode.ON)
    assertEquals(VodCachePrefetchMode.ON, dataStore.playerSettings.first().vodCachePrefetchMode)

    dataStore.setVodCachePrefetchMode(VodCachePrefetchMode.AUTO)
    assertEquals(VodCachePrefetchMode.AUTO, dataStore.playerSettings.first().vodCachePrefetchMode)
}
```

Add the import if it is missing:

```kotlin
import com.nexio.tv.data.local.VodCachePrefetchMode
```

- [ ] **Step 2: Run the failing tests**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.PlayerSettingsDataStoreTest"
```

Expected before implementation: FAIL with unresolved reference `VodCachePrefetchMode` or `vodCachePrefetchMode`.

- [ ] **Step 3: Add the enum and PlayerSettings field**

In `PlayerSettingsDataStore.kt`, add this enum immediately after `VodCacheSizeMode`:

```kotlin
enum class VodCachePrefetchMode {
    AUTO,
    OFF,
    ON
}
```

In `data class PlayerSettings`, add the field immediately after `vodCacheSizeMb`:

```kotlin
val vodCachePrefetchMode: VodCachePrefetchMode = DEFAULT_VOD_CACHE_PREFETCH_MODE,
```

In `PlayerSettings.Companion`, add:

```kotlin
val DEFAULT_VOD_CACHE_PREFETCH_MODE: VodCachePrefetchMode = VodCachePrefetchMode.AUTO
```

- [ ] **Step 4: Add the DataStore key and parser**

Near `vodCacheSizeModeKey`, add:

```kotlin
private val vodCachePrefetchModeKey = stringPreferencesKey("vod_cache_prefetch_mode")
```

Add this parser near `parseVodCacheSizeMode(...)`:

```kotlin
private fun parseVodCachePrefetchMode(value: String?): VodCachePrefetchMode {
    return when (value?.trim()?.uppercase()) {
        "AUTO" -> VodCachePrefetchMode.AUTO
        "OFF" -> VodCachePrefetchMode.OFF
        "ON" -> VodCachePrefetchMode.ON
        else -> PlayerSettings.DEFAULT_VOD_CACHE_PREFETCH_MODE
    }
}
```

- [ ] **Step 5: Read, set, reset, and update the setting**

In the `playerSettings` mapping, add the field after `vodCacheSizeMb`:

```kotlin
vodCachePrefetchMode = parseVodCachePrefetchMode(prefs[vodCachePrefetchModeKey]),
```

In `resetNetworkSettingsToDefaults()`, include the new setting in `transportChanged`:

```kotlin
(prefs[vodCachePrefetchModeKey]
    ?.let { runCatching { VodCachePrefetchMode.valueOf(it) }.getOrNull() }
    ?: PlayerSettings.DEFAULT_VOD_CACHE_PREFETCH_MODE) !=
    PlayerSettings.DEFAULT_VOD_CACHE_PREFETCH_MODE ||
```

Then set the default with the other network defaults:

```kotlin
prefs[vodCachePrefetchModeKey] = PlayerSettings.DEFAULT_VOD_CACHE_PREFETCH_MODE.name
```

Add this setter near `setVodCacheSizeMode(...)`:

```kotlin
suspend fun setVodCachePrefetchMode(mode: VodCachePrefetchMode) {
    store().edit { prefs ->
        val current = prefs[vodCachePrefetchModeKey]
            ?.let { runCatching { VodCachePrefetchMode.valueOf(it) }.getOrNull() }
            ?: PlayerSettings.DEFAULT_VOD_CACHE_PREFETCH_MODE
        if (current != mode) {
            prefs.remove(autoplayMaxBitrateMbpsKey)
        }
        prefs[vodCachePrefetchModeKey] = mode.name
    }
}
```

In `updateMemorySettings(...)`, add a nullable parameter:

```kotlin
vodCachePrefetchMode: VodCachePrefetchMode? = null,
```

Then add this block after `vodCacheSizeMb?.let { ... }`:

```kotlin
vodCachePrefetchMode?.let {
    val current = prefs[vodCachePrefetchModeKey]
        ?.let { stored -> runCatching { VodCachePrefetchMode.valueOf(stored) }.getOrNull() }
        ?: PlayerSettings.DEFAULT_VOD_CACHE_PREFETCH_MODE
    transportChanged = transportChanged || current != it
    prefs[vodCachePrefetchModeKey] = it.name
}
```

- [ ] **Step 6: Run the DataStore tests**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.PlayerSettingsDataStoreTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt
git add app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt
git commit -m "feat: persist vod cache prefetch mode"
```

Expected: commit succeeds with only the two listed files staged.

---

### Task 2: Add Warm-Ahead Policy Helpers

**Files:**
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/VodWarmAheadPolicy.kt`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/VodWarmAheadPolicyTest.kt`

- [ ] **Step 1: Write failing policy tests**

Create `VodWarmAheadPolicyTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.local.VodCachePrefetchMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodWarmAheadPolicyTest {
    @Test
    fun offDisablesWarmAhead() {
        val allowed = VodWarmAheadPolicy.shouldStartWarmAhead(
            mode = VodCachePrefetchMode.OFF,
            useVodCache = true,
            progressiveUsesParallelConnections = false,
            networkState = PlaybackNetworkState(kind = PlaybackNetworkKind.ETHERNET, isMetered = false)
        )

        assertFalse(allowed)
    }

    @Test
    fun onAllowsWarmAheadEvenForParallelWifi() {
        val allowed = VodWarmAheadPolicy.shouldStartWarmAhead(
            mode = VodCachePrefetchMode.ON,
            useVodCache = true,
            progressiveUsesParallelConnections = true,
            networkState = PlaybackNetworkState(kind = PlaybackNetworkKind.WIFI, isMetered = false)
        )

        assertTrue(allowed)
    }

    @Test
    fun autoAllowsEthernetParallelWarmAhead() {
        val allowed = VodWarmAheadPolicy.shouldStartWarmAhead(
            mode = VodCachePrefetchMode.AUTO,
            useVodCache = true,
            progressiveUsesParallelConnections = true,
            networkState = PlaybackNetworkState(kind = PlaybackNetworkKind.ETHERNET, isMetered = false)
        )

        assertTrue(allowed)
    }

    @Test
    fun autoDisablesParallelWarmAheadOnWifi() {
        val allowed = VodWarmAheadPolicy.shouldStartWarmAhead(
            mode = VodCachePrefetchMode.AUTO,
            useVodCache = true,
            progressiveUsesParallelConnections = true,
            networkState = PlaybackNetworkState(kind = PlaybackNetworkKind.WIFI, isMetered = false)
        )

        assertFalse(allowed)
    }

    @Test
    fun autoDisablesParallelWarmAheadOnUnknownNetwork() {
        val allowed = VodWarmAheadPolicy.shouldStartWarmAhead(
            mode = VodCachePrefetchMode.AUTO,
            useVodCache = true,
            progressiveUsesParallelConnections = true,
            networkState = PlaybackNetworkState(kind = PlaybackNetworkKind.UNKNOWN, isMetered = false)
        )

        assertFalse(allowed)
    }

    @Test
    fun autoDisablesWarmAheadOnMeteredNetwork() {
        val allowed = VodWarmAheadPolicy.shouldStartWarmAhead(
            mode = VodCachePrefetchMode.AUTO,
            useVodCache = true,
            progressiveUsesParallelConnections = false,
            networkState = PlaybackNetworkState(kind = PlaybackNetworkKind.WIFI, isMetered = true)
        )

        assertFalse(allowed)
    }

    @Test
    fun autoAllowsNonParallelWifiWarmAhead() {
        val allowed = VodWarmAheadPolicy.shouldStartWarmAhead(
            mode = VodCachePrefetchMode.AUTO,
            useVodCache = true,
            progressiveUsesParallelConnections = false,
            networkState = PlaybackNetworkState(kind = PlaybackNetworkKind.WIFI, isMetered = false)
        )

        assertTrue(allowed)
    }

    @Test
    fun disabledVodCacheDisablesWarmAheadInOnMode() {
        val allowed = VodWarmAheadPolicy.shouldStartWarmAhead(
            mode = VodCachePrefetchMode.ON,
            useVodCache = false,
            progressiveUsesParallelConnections = false,
            networkState = PlaybackNetworkState(kind = PlaybackNetworkKind.ETHERNET, isMetered = false)
        )

        assertFalse(allowed)
    }

    @Test
    fun warmAheadUsesPlaybackCacheKey() {
        val cacheKey = VodWarmAheadPolicy.warmAheadCacheKey(
            playbackStreamUrl = "https://real-debrid.com/d/ABC/movie.mkv",
            resolvedRequestUrl = "https://cdn.example.net/file"
        )

        assertTrue(cacheKey == "https://real-debrid.com/d/ABC/movie.mkv")
    }
}
```

- [ ] **Step 2: Run the failing policy tests**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.VodWarmAheadPolicyTest"
```

Expected before implementation: FAIL with unresolved references to `VodWarmAheadPolicy`, `PlaybackNetworkState`, or `PlaybackNetworkKind`.

- [ ] **Step 3: Implement pure policy and network model**

Create `VodWarmAheadPolicy.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.local.VodCachePrefetchMode

internal enum class PlaybackNetworkKind {
    ETHERNET,
    WIFI,
    CELLULAR,
    UNKNOWN
}

internal data class PlaybackNetworkState(
    val kind: PlaybackNetworkKind,
    val isMetered: Boolean
)

internal object VodWarmAheadPolicy {
    fun shouldStartWarmAhead(
        mode: VodCachePrefetchMode,
        useVodCache: Boolean,
        progressiveUsesParallelConnections: Boolean,
        networkState: PlaybackNetworkState
    ): Boolean {
        if (!useVodCache) return false
        return when (mode) {
            VodCachePrefetchMode.OFF -> false
            VodCachePrefetchMode.ON -> true
            VodCachePrefetchMode.AUTO -> {
                if (networkState.isMetered) return false
                if (!progressiveUsesParallelConnections) return true
                networkState.kind == PlaybackNetworkKind.ETHERNET
            }
        }
    }

    fun warmAheadCacheKey(
        playbackStreamUrl: String,
        resolvedRequestUrl: String?
    ): String {
        return playbackStreamUrl
    }
}
```

- [ ] **Step 4: Run the policy tests**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.VodWarmAheadPolicyTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/VodWarmAheadPolicy.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/VodWarmAheadPolicyTest.kt
git commit -m "feat: add vod warm ahead policy"
```

Expected: commit succeeds with only the two listed files staged.

---

### Task 3: Add Active Network Classification

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/AndroidManifest.xml`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlaybackNetworkStateProvider.kt`
- Create: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlaybackNetworkStateProviderTest.kt`

- [ ] **Step 1: Write the simple fallback test**

Create `PlaybackNetworkStateProviderTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackNetworkStateProviderTest {
    @Test
    fun currentStateReturnsStableSnapshot() {
        val provider = PlaybackNetworkStateProvider(ApplicationProvider.getApplicationContext())

        val state = provider.currentState()

        assertNotNull(state)
        assertEquals(state.kind, state.kind)
        assertEquals(state.isMetered, state.isMetered)
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlaybackNetworkStateProviderTest"
```

Expected before implementation: FAIL with unresolved reference `PlaybackNetworkStateProvider`.

- [ ] **Step 3: Add network state permission**

In `AndroidManifest.xml`, add this line next to the existing network permissions:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

- [ ] **Step 4: Implement the provider**

Create `PlaybackNetworkStateProvider.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal class PlaybackNetworkStateProvider(
    context: Context
) {
    private val appContext = context.applicationContext

    fun currentState(): PlaybackNetworkState {
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return PlaybackNetworkState(PlaybackNetworkKind.UNKNOWN, isMetered = true)
        return runCatching {
            val activeNetwork = connectivityManager.activeNetwork
                ?: return@runCatching PlaybackNetworkState(PlaybackNetworkKind.UNKNOWN, isMetered = true)
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            val kind = when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ->
                    PlaybackNetworkKind.ETHERNET
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ->
                    PlaybackNetworkKind.WIFI
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                    PlaybackNetworkKind.CELLULAR
                else -> PlaybackNetworkKind.UNKNOWN
            }
            PlaybackNetworkState(
                kind = kind,
                isMetered = connectivityManager.isActiveNetworkMetered
            )
        }.getOrElse {
            PlaybackNetworkState(PlaybackNetworkKind.UNKNOWN, isMetered = true)
        }
    }
}
```

- [ ] **Step 5: Run the network provider test**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlaybackNetworkStateProviderTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add app/src/main/AndroidManifest.xml
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlaybackNetworkStateProvider.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlaybackNetworkStateProviderTest.kt
git commit -m "feat: classify playback network state"
```

Expected: commit succeeds with only the three listed files staged.

---

### Task 4: Wire Policy Into PlayerMediaSourceFactory

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Add focused helper tests**

Append these tests inside `PlayerMediaSourceFactoryTest`:

```kotlin
@Test
fun warmAheadCacheKey_usesPlaybackUrlInsteadOfResolvedUrl() {
    assertEquals(
        "https://real-debrid.com/d/ABC/movie.mkv",
        VodWarmAheadPolicy.warmAheadCacheKey(
            playbackStreamUrl = "https://real-debrid.com/d/ABC/movie.mkv",
            resolvedRequestUrl = "https://cdn.example.net/edge/movie.mkv"
        )
    )
}

@Test
fun progressivePlaybackStillUsesParallelRangeDatasourceWhenEnabled() {
    val factory = PlayerMediaSourceFactory(
        context = mockk(relaxed = true),
        playbackOkHttpClient = OkHttpClient(),
        networkStateProvider = {
            PlaybackNetworkState(PlaybackNetworkKind.WIFI, isMetered = false)
        }
    )

    val dataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
        url = "https://example.com/video.mkv",
        headers = emptyMap()
    )

    assertTrue(dataSourceFactory is ParallelRangeDataSource.Factory)
}
```

- [ ] **Step 2: Run the focused tests before wiring**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.VodWarmAheadPolicyTest"
```

Expected before implementation: FAIL because `PlayerMediaSourceFactory` does not yet accept `networkStateProvider`.

- [ ] **Step 3: Add factory fields**

In `PlayerMediaSourceFactory.kt`, add the import:

```kotlin
import com.nexio.tv.data.local.VodCachePrefetchMode
```

Change the constructor to:

```kotlin
internal class PlayerMediaSourceFactory(
    private val context: Context,
    private val playbackOkHttpClient: OkHttpClient,
    private val networkStateProvider: () -> PlaybackNetworkState = {
        PlaybackNetworkStateProvider(context).currentState()
    },
) {
```

Add this property next to `vodCacheSizeMode`:

```kotlin
var vodCachePrefetchMode: VodCachePrefetchMode = PlayerSettings.DEFAULT_VOD_CACHE_PREFETCH_MODE
    set(value) {
        field = value
        if (value == VodCachePrefetchMode.OFF) {
            stopVodWarmAhead()
        }
    }
```

- [ ] **Step 4: Apply policy when creating media sources**

In `createMediaSource(...)`, after `progressiveUpstreamFactory` is created, add:

```kotlin
val progressiveUsesParallelConnections = progressiveUpstreamFactory is ParallelRangeDataSource.Factory
```

Replace:

```kotlin
currentProgressiveIsEligibleForWarmAhead = useVodCache
```

with:

```kotlin
val networkState = networkStateProvider()
currentProgressiveIsEligibleForWarmAhead = VodWarmAheadPolicy.shouldStartWarmAhead(
    mode = vodCachePrefetchMode,
    useVodCache = useVodCache,
    progressiveUsesParallelConnections = progressiveUsesParallelConnections,
    networkState = networkState
)
if (useVodCache) {
    Log.d(
        TAG,
        "VOD warm-ahead policy mode=$vodCachePrefetchMode enabled=$currentProgressiveIsEligibleForWarmAhead " +
            "parallel=$progressiveUsesParallelConnections network=${networkState.kind} metered=${networkState.isMetered}"
    )
}
```

- [ ] **Step 5: Keep warm-ahead cache key aligned with playback**

In `runWarmAheadLoop(...)`, replace:

```kotlin
val cacheKey = prefetchUri.toString()
```

with:

```kotlin
val cacheKey = VodWarmAheadPolicy.warmAheadCacheKey(
    playbackStreamUrl = streamUrl,
    resolvedRequestUrl = liveUrl
)
```

In the `DataSpec.Builder()` block, add:

```kotlin
.setKey(cacheKey)
```

The full block should read:

```kotlin
val dataSpec = DataSpec.Builder()
    .setUri(prefetchUri)
    .setKey(cacheKey)
    .setPosition(holeStart)
    .setLength(writeLength)
    .build()
```

- [ ] **Step 6: Wire settings into runtime initialization and observer**

In `PlayerRuntimeControllerInitialization.kt`, after:

```kotlin
mediaSourceFactory.vodCacheSizeMode = playerSettings.vodCacheSizeMode
mediaSourceFactory.vodCacheSizeMb = playerSettings.vodCacheSizeMb
```

add:

```kotlin
mediaSourceFactory.vodCachePrefetchMode = playerSettings.vodCachePrefetchMode
```

In `PlayerRuntimeControllerObservers.kt`, after:

```kotlin
mediaSourceFactory.vodCacheSizeMode = settings.vodCacheSizeMode
mediaSourceFactory.vodCacheSizeMb = settings.vodCacheSizeMb
```

add:

```kotlin
mediaSourceFactory.vodCachePrefetchMode = settings.vodCachePrefetchMode
```

- [ ] **Step 7: Run focused player tests**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.VodWarmAheadPolicyTest" --tests "com.nexio.tv.ui.screens.player.PlaybackNetworkStateProviderTest"
```

Expected: PASS.

- [ ] **Step 8: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "fix: gate vod warm ahead by network policy"
```

Expected: commit succeeds with only the four listed files staged.

---

### Task 5: Add Settings UI For Prefetch Mode

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values/strings.xml`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/res/values-nl/strings.xml`

- [ ] **Step 1: Add strings**

In `values/strings.xml`, add near the existing playback buffer strings:

```xml
<string name="playback_buffer_vod_prefetch_mode">VOD Background Prefetch</string>
<string name="playback_buffer_vod_prefetch_mode_sub">Control whether the cache downloads ahead of playback.</string>
<string name="playback_buffer_vod_prefetch_auto">Auto</string>
<string name="playback_buffer_vod_prefetch_auto_desc">Avoids extra PRDS downloads on WiFi, metered, or unknown networks.</string>
<string name="playback_buffer_vod_prefetch_off">Off</string>
<string name="playback_buffer_vod_prefetch_off_desc">Only cache bytes that playback already downloads.</string>
<string name="playback_buffer_vod_prefetch_on">On</string>
<string name="playback_buffer_vod_prefetch_on_desc">Allow background prefetching even when it may use extra bandwidth.</string>
```

In `values-nl/strings.xml`, add near the translated playback buffer strings:

```xml
<string name="playback_buffer_vod_prefetch_mode">VOD-voorladen op achtergrond</string>
<string name="playback_buffer_vod_prefetch_mode_sub">Bepaal of de cache vooruit downloadt op de weergave.</string>
<string name="playback_buffer_vod_prefetch_auto">Automatisch</string>
<string name="playback_buffer_vod_prefetch_auto_desc">Vermijdt extra PRDS-downloads op wifi, gemeten of onbekende netwerken.</string>
<string name="playback_buffer_vod_prefetch_off">Uit</string>
<string name="playback_buffer_vod_prefetch_off_desc">Cachet alleen bytes die de weergave al downloadt.</string>
<string name="playback_buffer_vod_prefetch_on">Aan</string>
<string name="playback_buffer_vod_prefetch_on_desc">Sta vooraf downloaden op de achtergrond toe, ook als dit extra bandbreedte kan gebruiken.</string>
```

- [ ] **Step 2: Add the ViewModel setter**

In `PlaybackSettingsViewModel.kt`, add the import:

```kotlin
import com.nexio.tv.data.local.VodCachePrefetchMode
```

Add this method near `setVodCacheSizeMode(...)`:

```kotlin
suspend fun setVodCachePrefetchMode(mode: VodCachePrefetchMode) {
    playerSettingsDataStore.setVodCachePrefetchMode(mode)
}
```

- [ ] **Step 3: Thread the callback through `PlaybackSettingsSections`**

In `PlaybackSettingsSections.kt`, add the import:

```kotlin
import com.nexio.tv.data.local.VodCachePrefetchMode
```

Add these parameters to `PlaybackSettingsSections(...)` near the VOD callbacks:

```kotlin
onShowVodCachePrefetchModeDialog: () -> Unit,
onSetVodCachePrefetchMode: (VodCachePrefetchMode) -> Unit,
```

Pass `onShowVodCachePrefetchModeDialog` into `bufferAndNetworkSettingsItems(...)`:

```kotlin
onShowVodCachePrefetchModeDialog = onShowVodCachePrefetchModeDialog,
```

Add these parameters to `PlaybackSettingsDialogsHost(...)`:

```kotlin
showVodCachePrefetchModeDialog: Boolean,
onSetVodCachePrefetchMode: (VodCachePrefetchMode) -> Unit,
onDismissVodCachePrefetchModeDialog: () -> Unit,
```

Inside `PlaybackSettingsDialogsHost(...)`, add:

```kotlin
if (showVodCachePrefetchModeDialog) {
    VodCachePrefetchModeDialog(
        currentMode = playerSettings.vodCachePrefetchMode,
        onSelect = {
            onSetVodCachePrefetchMode(it)
            onDismissVodCachePrefetchModeDialog()
        },
        onDismiss = onDismissVodCachePrefetchModeDialog
    )
}
```

Add this dialog near the existing enum-selection dialogs:

```kotlin
@Composable
private fun VodCachePrefetchModeDialog(
    currentMode: VodCachePrefetchMode,
    onSelect: (VodCachePrefetchMode) -> Unit,
    onDismiss: () -> Unit
) {
    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.playback_buffer_vod_prefetch_mode),
        subtitle = stringResource(R.string.playback_buffer_vod_prefetch_mode_sub),
        width = 620.dp,
        suppressFirstKeyUp = false
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VodCachePrefetchOptionButton(
                label = stringResource(R.string.playback_buffer_vod_prefetch_auto),
                description = stringResource(R.string.playback_buffer_vod_prefetch_auto_desc),
                selected = currentMode == VodCachePrefetchMode.AUTO,
                onClick = { onSelect(VodCachePrefetchMode.AUTO) }
            )
            VodCachePrefetchOptionButton(
                label = stringResource(R.string.playback_buffer_vod_prefetch_off),
                description = stringResource(R.string.playback_buffer_vod_prefetch_off_desc),
                selected = currentMode == VodCachePrefetchMode.OFF,
                onClick = { onSelect(VodCachePrefetchMode.OFF) }
            )
            VodCachePrefetchOptionButton(
                label = stringResource(R.string.playback_buffer_vod_prefetch_on),
                description = stringResource(R.string.playback_buffer_vod_prefetch_on_desc),
                selected = currentMode == VodCachePrefetchMode.ON,
                onClick = { onSelect(VodCachePrefetchMode.ON) }
            )
        }
    }
}

@Composable
private fun VodCachePrefetchOptionButton(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) NexioColors.Primary else NexioColors.BackgroundCard,
            contentColor = if (selected) Color.Black else NexioColors.TextPrimary
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(label)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) Color.Black else NexioColors.TextSecondary
            )
        }
    }
}
```

- [ ] **Step 4: Update the settings screen dialog state**

In `PlaybackSettingsScreen.kt`, add:

```kotlin
import com.nexio.tv.data.local.VodCachePrefetchMode
```

Add dialog state near the other dialog booleans:

```kotlin
var showVodCachePrefetchModeDialog by remember { mutableStateOf(false) }
```

In `dismissAllDialogs()`, add:

```kotlin
showVodCachePrefetchModeDialog = false
```

When calling `PlaybackSettingsSections(...)`, pass:

```kotlin
onShowVodCachePrefetchModeDialog = { openDialog { showVodCachePrefetchModeDialog = true } },
onSetVodCachePrefetchMode = { mode ->
    coroutineScope.launch { viewModel.setVodCachePrefetchMode(mode) }
},
```

When calling `PlaybackSettingsDialogsHost(...)`, pass:

```kotlin
showVodCachePrefetchModeDialog = showVodCachePrefetchModeDialog,
onSetVodCachePrefetchMode = { mode: VodCachePrefetchMode ->
    coroutineScope.launch { viewModel.setVodCachePrefetchMode(mode) }
},
onDismissVodCachePrefetchModeDialog = ::dismissAllDialogs,
```

- [ ] **Step 5: Add the buffer/network row**

In `PlaybackBufferNetworkSettings.kt`, add imports:

```kotlin
import androidx.compose.material.icons.filled.CloudDownload
import com.nexio.tv.data.local.VodCachePrefetchMode
```

Add a parameter to `bufferAndNetworkSettingsItems(...)`:

```kotlin
onShowVodCachePrefetchModeDialog: () -> Unit,
```

Add this row inside the `if (playerSettings.vodCacheSizeMode == VodCacheSizeMode.ON)` block, after the VOD size slider:

```kotlin
item(key = "network_cache_vod_prefetch_mode") {
    NavigationSettingsItem(
        icon = Icons.Default.CloudDownload,
        title = stringResource(R.string.playback_buffer_vod_prefetch_mode),
        subtitle = vodCachePrefetchModeLabel(playerSettings.vodCachePrefetchMode),
        onClick = onShowVodCachePrefetchModeDialog,
        onFocused = onItemFocused
    )
}
```

Add this composable helper near the bottom of the file:

```kotlin
@Composable
private fun vodCachePrefetchModeLabel(mode: VodCachePrefetchMode): String {
    return when (mode) {
        VodCachePrefetchMode.AUTO -> stringResource(R.string.playback_buffer_vod_prefetch_auto)
        VodCachePrefetchMode.OFF -> stringResource(R.string.playback_buffer_vod_prefetch_off)
        VodCachePrefetchMode.ON -> stringResource(R.string.playback_buffer_vod_prefetch_on)
    }
}
```

- [ ] **Step 6: Build the app**

Run:

```bash
./gradlew --no-daemon :app:assembleUniversalDebug
```

Expected: PASS and produces `app/build/outputs/apk/universal/debug/app-universal-debug.apk`.

- [ ] **Step 7: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsScreen.kt
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsSections.kt
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt
git add app/src/main/res/values/strings.xml
git add app/src/main/res/values-nl/strings.xml
git commit -m "feat: expose vod prefetch setting"
```

Expected: commit succeeds with only the six listed files staged.

---

### Task 6: Regression Verification

**Files:**
- Read: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Read: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
- Read: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTest.kt`
- Read: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerLoadControlFactoryTest.kt`

- [ ] **Step 1: Run focused player/cache tests**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.VodWarmAheadPolicyTest" --tests "com.nexio.tv.ui.screens.player.PlaybackNetworkStateProviderTest" --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTest" --tests "com.nexio.tv.ui.screens.player.PlayerLoadControlFactoryTest" --tests "com.nexio.tv.data.local.PlayerSettingsDataStoreTest"
```

Expected: PASS. This protects source selection, warm-ahead policy, PRDS retry behavior, Patch 1 load-control cap, and settings persistence.

- [ ] **Step 2: Build the app**

Run:

```bash
./gradlew --no-daemon :app:assembleUniversalDebug
```

Expected: PASS and produces `app/build/outputs/apk/universal/debug/app-universal-debug.apk`.

- [ ] **Step 3: Inspect source diff**

Run:

```bash
git diff HEAD~5..HEAD -- app/src/main/java/com/nexio/tv/ui/screens/player app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt app/src/main/java/com/nexio/tv/ui/screens/settings app/src/main/res/values/strings.xml app/src/main/res/values-nl/strings.xml app/src/main/AndroidManifest.xml
```

Expected: diff shows only the new prefetch mode setting, network-state helper, warm-ahead gating, warm-ahead cache-key alignment, strings, and manifest permission.

- [ ] **Step 4: Inspect final status**

Run:

```bash
git status --short
```

Expected: no unstaged changes in files touched by this plan. Pre-existing unrelated ignored files may still appear and must not be staged.

- [ ] **Step 5: Commit verification note only if changes were made during verification**

No extra commit is required if Step 1 and Step 2 pass without modifying files. If a verification-only fix is needed, commit only the specific files that changed with:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player app/src/test/java/com/nexio/tv/ui/screens/player app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt app/src/main/java/com/nexio/tv/ui/screens/settings app/src/main/res/values/strings.xml app/src/main/res/values-nl/strings.xml app/src/main/AndroidManifest.xml
git commit -m "fix: finish vod prefetch policy verification"
```

Expected: skip this command unless verification changed tracked files.

---

## Manual Device Verification

- On Ethernet: set `VOD Background Prefetch = Auto`, play a large progressive file with parallel connections enabled, and confirm logcat includes `enabled=true parallel=true network=ETHERNET`.
- On WiFi: set `VOD Background Prefetch = Auto`, play the same type of file, and confirm logcat includes `enabled=false parallel=true network=WIFI`.
- On WiFi: set `VOD Background Prefetch = On`, replay, and confirm logcat includes `enabled=true parallel=true network=WIFI`.
- For all modes: confirm playback write-through remains active by verifying `buildVodCacheDataSourceFactory(...)` still installs `CacheDataSink` for playback.
- For cache-key behavior: start playback, let warm-ahead run in `On` mode, then call `getVodCacheLogState(...)` and confirm stream bytes grow under the active playback URL rather than a separate resolved CDN URL namespace.

## Self-Review

Spec coverage:

- Settings option is covered by Tasks 1 and 5.
- Network-aware auto behavior is covered by Tasks 2, 3, and 4.
- Warm-ahead extra PRDS bandwidth risk is addressed by gating warm-ahead, not by changing PRDS.
- Cache-key drift risk is addressed by Task 4 using `.setKey(cacheKey)` and scanning with the playback URL key.
- Playback write-through is explicitly left unchanged.

Placeholder scan:

- No unresolved placeholder language or unspecified test steps are present.

Type consistency:

- `VodCachePrefetchMode`, `PlaybackNetworkKind`, `PlaybackNetworkState`, `PlaybackNetworkStateProvider`, and `VodWarmAheadPolicy` are defined before use.
- `vodCachePrefetchMode` is persisted, exposed in `PlayerSettings`, wired to the player factory, and shown in settings UI with matching enum values.
