# Split PRDS Warm-Ahead Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let playback and VOD warm-ahead use different PRDS profiles when background warm-ahead is enabled, while preserving the current playback profiles when warm-ahead is disabled.

**Architecture:** Keep `ParallelRangeDataSource` unchanged and keep playback write-through unchanged. Move provider profile selection into a small profile-pair resolver in `PlayerMediaSourceFactory`: when `vodCacheWarmAheadEnabled` is false or VOD cache is not active, playback uses today’s profile; when warm-ahead is enabled for the stream, playback and warm-ahead each receive an explicit profile so total background contention is bounded.

**Tech Stack:** Android/Kotlin, Media3 `DataSource.Factory`, existing `ParallelRangeDataSource.Factory`, JUnit4/Robolectric.

---

## Current Baseline

Current provider profiles in `PlayerMediaSourceFactory.kt`:

- Default fallback playback PRDS: `2 x 24 MB`
- Real-Debrid playback PRDS: `2 x 24 MB`
- Premiumize playback PRDS: `3 x 16 MB`

Current warm-ahead uses `currentProgressiveUpstreamFactory`, so when playback uses PRDS, warm-ahead can create another PRDS instance with the same playback profile.

## Target Behavior

When VOD warm-ahead is disabled for the stream:

| Provider | Playback PRDS | Warm-ahead PRDS |
|---|---:|---:|
| Default fallback | `2 x 24 MB` | disabled |
| Real-Debrid | `2 x 24 MB` | disabled |
| Premiumize | `3 x 16 MB` | disabled |

When VOD warm-ahead is enabled for the stream:

| Provider | Playback PRDS | Warm-ahead PRDS |
|---|---:|---:|
| Default fallback | `2 x 24 MB` | `1 x 16 MB` |
| Real-Debrid | `1 x 24 MB` | `1 x 24 MB` |
| Premiumize | `2 x 16 MB` | `1 x 16 MB` |

Important operational note: since the existing UI toggle defaults to enabled, this changes the default Real-Debrid behavior from `2 x 24 MB` playback-only plus same-profile warm-ahead to `1 x 24 MB` playback plus `1 x 24 MB` warm-ahead. Users who need today’s exact playback profile can turn `VOD Background Warm-Ahead` off.

## Guardrails

- Do not modify `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`.
- Do not change playback write-through cache or call `setCacheWriteDataSinkFactory(null)`.
- Do not add network detection or device-model detection.
- Do not change the VOD warm-ahead toggle UI.
- Do not change benchmark transport behavior in this plan. Benchmark transport should continue to use current playback profiles because it does not run the warm-ahead thread.
- Do not stage unrelated dirty files currently visible in `git status`.

## File Map

Production files:

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - add split playback/warm-ahead profile resolver
  - store `currentWarmAheadUpstreamFactory`
  - build warm-ahead PRDS factory separately from playback PRDS factory

Test files:

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
  - add profile resolver tests for fallback, Real-Debrid, Premiumize, warm-ahead on/off
  - add a warm-ahead upstream profile test that verifies the warm-ahead factory uses the dedicated profile

---

### Task 1: Add Split Profile Resolver

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Add failing resolver tests**

Append these tests inside `PlayerMediaSourceFactoryTest`:

```kotlin
@Test
fun parallelProviderProfiles_warmAheadDisabledKeepsRealDebridCurrentProfile() {
    val factory = PlayerMediaSourceFactory(
        context = mockk(relaxed = true),
        playbackOkHttpClient = OkHttpClient()
    )

    assertEquals(
        (2 to 24) to null,
        factory.parallelProviderProfilesForTesting(
            url = "https://real-debrid.com/path/movie.mkv",
            warmAheadEnabledForStream = false
        )
    )
}

@Test
fun parallelProviderProfiles_warmAheadEnabledSplitsRealDebridOneAndOne() {
    val factory = PlayerMediaSourceFactory(
        context = mockk(relaxed = true),
        playbackOkHttpClient = OkHttpClient()
    )

    assertEquals(
        (1 to 24) to (1 to 24),
        factory.parallelProviderProfilesForTesting(
            url = "https://real-debrid.com/path/movie.mkv",
            warmAheadEnabledForStream = true
        )
    )
}

@Test
fun parallelProviderProfiles_warmAheadDisabledKeepsPremiumizeCurrentProfile() {
    val factory = PlayerMediaSourceFactory(
        context = mockk(relaxed = true),
        playbackOkHttpClient = OkHttpClient()
    )

    assertEquals(
        (3 to 16) to null,
        factory.parallelProviderProfilesForTesting(
            url = "https://premiumize.me/path/movie.mkv",
            warmAheadEnabledForStream = false
        )
    )
}

@Test
fun parallelProviderProfiles_warmAheadEnabledSplitsPremiumizeTwoAndOne() {
    val factory = PlayerMediaSourceFactory(
        context = mockk(relaxed = true),
        playbackOkHttpClient = OkHttpClient()
    )

    assertEquals(
        (2 to 16) to (1 to 16),
        factory.parallelProviderProfilesForTesting(
            url = "https://premiumize.me/path/movie.mkv",
            warmAheadEnabledForStream = true
        )
    )
}

@Test
fun parallelProviderProfiles_warmAheadEnabledSplitsFallbackTwoAndOne() {
    val factory = PlayerMediaSourceFactory(
        context = mockk(relaxed = true),
        playbackOkHttpClient = OkHttpClient()
    )

    assertEquals(
        (2 to 24) to (1 to 16),
        factory.parallelProviderProfilesForTesting(
            url = "https://example.com/path/movie.mkv",
            warmAheadEnabledForStream = true
        )
    )
}
```

- [ ] **Step 2: Run the failing resolver tests**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest"
```

Expected before implementation: FAIL with unresolved reference `parallelProviderProfilesForTesting`.

- [ ] **Step 3: Add the split profile data class and helper**

In `PlayerMediaSourceFactory.kt`, immediately after the existing `ParallelProviderProfile` data class, add:

```kotlin
private data class ParallelProviderProfiles(
    val playback: ParallelProviderProfile,
    val warmAhead: ParallelProviderProfile?
)
```

Add this test helper next to `parallelProviderProfileForTesting(...)`:

```kotlin
internal fun parallelProviderProfilesForTesting(
    url: String,
    warmAheadEnabledForStream: Boolean
): Pair<Pair<Int, Int>, Pair<Int, Int>?> {
    val profiles = resolveParallelProviderProfiles(
        url = url,
        warmAheadEnabledForStream = warmAheadEnabledForStream
    )
    return (profiles.playback.connectionCount to profiles.playback.chunkSizeMb) to
        profiles.warmAhead?.let { it.connectionCount to it.chunkSizeMb }
}
```

- [ ] **Step 4: Implement split profile resolution**

Replace the current `resolveParallelProviderProfile(...)` function with:

```kotlin
private fun resolveParallelProviderProfiles(
    url: String,
    warmAheadEnabledForStream: Boolean,
    fallbackConnectionCount: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
    fallbackChunkSizeMb: Int = SAFE_DEFAULT_PARALLEL_CHUNK_SIZE_MB
): ParallelProviderProfiles {
    val host = runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.US) }
        .getOrDefault("")
    val isPremiumize =
        host.contains("premiumize") || host.startsWith("pm.") || host.contains(".pm.")
    val isRealDebrid =
        host.contains("real-debrid") ||
            host.contains("realdebrid") ||
            host.startsWith("rd.") ||
            host.contains(".rd.")

    if (warmAheadEnabledForStream) {
        return when {
            isPremiumize -> ParallelProviderProfiles(
                playback = ParallelProviderProfile(connectionCount = 2, chunkSizeMb = 16),
                warmAhead = ParallelProviderProfile(connectionCount = 1, chunkSizeMb = 16)
            )
            isRealDebrid -> ParallelProviderProfiles(
                playback = ParallelProviderProfile(connectionCount = 1, chunkSizeMb = 24),
                warmAhead = ParallelProviderProfile(connectionCount = 1, chunkSizeMb = 24)
            )
            else -> ParallelProviderProfiles(
                playback = ParallelProviderProfile(
                    connectionCount = fallbackConnectionCount.coerceIn(
                        PlayerSettings.MIN_PARALLEL_CONNECTION_COUNT,
                        PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT
                    ),
                    chunkSizeMb = fallbackChunkSizeMb.coerceIn(
                        PlayerSettings.MIN_PARALLEL_CHUNK_SIZE_MB,
                        PlayerSettings.MAX_PARALLEL_CHUNK_SIZE_MB
                    )
                ),
                warmAhead = ParallelProviderProfile(connectionCount = 1, chunkSizeMb = 16)
            )
        }
    }

    return when {
        isPremiumize -> ParallelProviderProfiles(
            playback = ParallelProviderProfile(connectionCount = 3, chunkSizeMb = 16),
            warmAhead = null
        )
        isRealDebrid -> ParallelProviderProfiles(
            playback = ParallelProviderProfile(connectionCount = 2, chunkSizeMb = 24),
            warmAhead = null
        )
        else -> ParallelProviderProfiles(
            playback = ParallelProviderProfile(
                connectionCount = fallbackConnectionCount.coerceIn(
                    PlayerSettings.MIN_PARALLEL_CONNECTION_COUNT,
                    PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT
                ),
                chunkSizeMb = fallbackChunkSizeMb.coerceIn(
                    PlayerSettings.MIN_PARALLEL_CHUNK_SIZE_MB,
                    PlayerSettings.MAX_PARALLEL_CHUNK_SIZE_MB
                )
            ),
            warmAhead = null
        )
    }
}
```

Keep `parallelProviderProfileForTesting(url)` by making it use the playback profile with warm-ahead disabled:

```kotlin
internal fun parallelProviderProfileForTesting(url: String): Pair<Int, Int> {
    val profile = resolveParallelProviderProfiles(
        url = url,
        warmAheadEnabledForStream = false
    ).playback
    return profile.connectionCount to profile.chunkSizeMb
}
```

- [ ] **Step 5: Run the resolver tests**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "test: define split prds warm ahead profiles"
```

Expected: commit succeeds with only the two listed files staged.

---

### Task 2: Build Separate Warm-Ahead PRDS Factory

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Add failing factory selection tests**

Append these tests inside `PlayerMediaSourceFactoryTest`:

```kotlin
@Test
fun warmAheadFactoryProfile_usesRealDebridWarmAheadProfileWhenEnabled() {
    val factory = PlayerMediaSourceFactory(
        context = mockk(relaxed = true),
        playbackOkHttpClient = OkHttpClient()
    )

    assertEquals(
        1 to 24,
        factory.warmAheadProviderProfileForTesting(
            url = "https://real-debrid.com/path/movie.mkv",
            warmAheadEnabledForStream = true
        )
    )
}

@Test
fun warmAheadFactoryProfile_isNullWhenWarmAheadDisabled() {
    val factory = PlayerMediaSourceFactory(
        context = mockk(relaxed = true),
        playbackOkHttpClient = OkHttpClient()
    )

    assertEquals(
        null,
        factory.warmAheadProviderProfileForTesting(
            url = "https://real-debrid.com/path/movie.mkv",
            warmAheadEnabledForStream = false
        )
    )
}
```

- [ ] **Step 2: Run the failing factory selection tests**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest"
```

Expected before implementation: FAIL with unresolved reference `warmAheadProviderProfileForTesting`.

- [ ] **Step 3: Add warm-ahead upstream state**

In `PlayerMediaSourceFactory.kt`, add this field near `currentProgressiveUpstreamFactory`:

```kotlin
@Volatile private var currentWarmAheadUpstreamFactory: DataSource.Factory? = null
```

When clearing stream state in `createMediaSource(...)`, set:

```kotlin
currentWarmAheadUpstreamFactory = null
```

Also set `currentWarmAheadUpstreamFactory = null` in the `else` branch where VOD cache is inactive.

- [ ] **Step 4: Add helper to build a PRDS factory from a profile**

Add this private helper near `selectProgressiveUpstreamFactory(...)`:

```kotlin
private fun buildParallelRangeDataSourceFactory(
    okHttpFactory: OkHttpDataSource.Factory,
    profile: ParallelProviderProfile,
    allowStartupBootstrapReuse: Boolean,
    transportSampleTimeMs: () -> Long,
    onTransportBytesDownloaded: (Long, Long) -> Unit,
    onResolvedUri: (Uri?) -> Unit,
    onReadPositionAdvanced: (Long) -> Unit
): ParallelRangeDataSource.Factory {
    return ParallelRangeDataSource.Factory(
        okHttpFactory,
        profile.connectionCount,
        profile.chunkSizeMb.toLong() * 1024L * 1024L,
        shouldAllowBackgroundPrefetch = { parallelStartupPrefetchUnlocked.get() },
        transportSampleTimeMs = transportSampleTimeMs,
        onTransportBytesDownloaded = onTransportBytesDownloaded,
        onResolvedUri = onResolvedUri,
        onReadPositionAdvanced = onReadPositionAdvanced,
        allowStartupBootstrapReuse = allowStartupBootstrapReuse
    )
}
```

- [ ] **Step 5: Add warm-ahead profile test helper**

Add this helper near `parallelProviderProfilesForTesting(...)`:

```kotlin
internal fun warmAheadProviderProfileForTesting(
    url: String,
    warmAheadEnabledForStream: Boolean
): Pair<Int, Int>? {
    val profile = resolveParallelProviderProfiles(
        url = url,
        warmAheadEnabledForStream = warmAheadEnabledForStream
    ).warmAhead
    return profile?.let { it.connectionCount to it.chunkSizeMb }
}
```

- [ ] **Step 6: Create separate playback and warm-ahead factories**

In `createMediaSource(...)`, move the `useVodCache` calculation so it happens before `selectProgressiveUpstreamFactory(...)`. Then call selection with the stream’s warm-ahead setting:

```kotlin
val useVodCache = ENABLE_VOD_CACHE &&
    vodCacheSizeMode == VodCacheSizeMode.ON &&
    !isHls &&
    !isDash &&
    shouldUseVodCache(url)
val warmAheadEnabledForStream = VodWarmAheadPolicy.shouldStartWarmAhead(
    useVodCache = useVodCache,
    warmAheadEnabled = vodCacheWarmAheadEnabled
)
val progressiveUpstreamFactory = selectProgressiveUpstreamFactory(
    url = url,
    isHls = isHls,
    isDash = isDash,
    okHttpFactory = okHttpFactory,
    baseDataSourceFactory = baseDataSourceFactory,
    warmAheadEnabledForProfile = warmAheadEnabledForStream
)
```

Update `selectProgressiveUpstreamFactory(...)` signature to add:

```kotlin
warmAheadEnabledForProfile: Boolean = false,
```

Inside the PRDS branch of `selectProgressiveUpstreamFactory(...)`, replace direct `resolveParallelProviderProfile(...)` use with:

```kotlin
val profiles = resolveParallelProviderProfiles(
    url = url,
    warmAheadEnabledForStream = warmAheadEnabledForProfile,
    fallbackConnectionCount = fallbackParallelConnectionCount,
    fallbackChunkSizeMb = fallbackParallelChunkSizeMb
)
val resolvedUriCallback: (Uri?) -> Unit = { resolved ->
    currentVodCacheResolvedUrl = ResolvedVodCacheUrl(
        playbackUrl = url,
        resolvedUrl = resolved?.toString()
    )
}
val readPositionCallback: (Long) -> Unit = { position ->
    activeReadBytePosition.accumulateAndGet(position) { current, next ->
        if (next > current) next else current
    }
}
currentWarmAheadUpstreamFactory = profiles.warmAhead?.let { warmAheadProfile ->
    buildParallelRangeDataSourceFactory(
        okHttpFactory = okHttpFactory,
        profile = warmAheadProfile,
        allowStartupBootstrapReuse = false,
        transportSampleTimeMs = transportSampleTimeMs,
        onTransportBytesDownloaded = onTransportBytesDownloaded,
        onResolvedUri = resolvedUriCallback,
        onReadPositionAdvanced = { }
    )
}
buildParallelRangeDataSourceFactory(
    okHttpFactory = okHttpFactory,
    profile = profiles.playback,
    allowStartupBootstrapReuse = allowStartupBootstrapReuse,
    transportSampleTimeMs = transportSampleTimeMs,
    onTransportBytesDownloaded = onTransportBytesDownloaded,
    onResolvedUri = resolvedUriCallback,
    onReadPositionAdvanced = readPositionCallback
)
```

In non-PRDS branches, set:

```kotlin
currentWarmAheadUpstreamFactory = null
```

In `createBenchmarkProgressiveDataSourceFactory(...)`, pass:

```kotlin
warmAheadEnabledForProfile = false
```

so benchmark transport keeps current profiles.

- [ ] **Step 7: Run focused tests**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest"
```

Expected: PASS.

- [ ] **Step 8: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "feat: split playback and warm ahead prds factories"
```

Expected: commit succeeds with only the two listed files staged.

---

### Task 3: Use Dedicated Warm-Ahead Factory

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Add source selection helper tests**

Append these tests inside `PlayerMediaSourceFactoryTest`:

```kotlin
@Test
fun warmAheadUpstreamKind_reportsSingleWhenDedicatedWarmAheadFactoryIsAbsent() {
    val factory = PlayerMediaSourceFactory(
        context = mockk(relaxed = true),
        playbackOkHttpClient = OkHttpClient()
    )

    assertEquals("single", factory.currentWarmAheadUpstreamKindForTesting())
}

@Test
fun warmAheadUpstreamKind_reportsPrdsWhenDedicatedWarmAheadFactoryIsPresent() {
    val factory = PlayerMediaSourceFactory(
        context = mockk(relaxed = true),
        playbackOkHttpClient = OkHttpClient()
    )

    factory.progressiveUpstreamFactoryForTesting(
        url = "https://real-debrid.com/path/movie.mkv",
        headers = emptyMap(),
        warmAheadEnabledForProfile = true
    )

    assertEquals("prds", factory.currentWarmAheadUpstreamKindForTesting())
}
```

If `progressiveUpstreamFactoryForTesting(...)` does not yet expose `warmAheadEnabledForProfile`, update it in Step 2.

- [ ] **Step 2: Expose warm-ahead profile in test helper**

Update `progressiveUpstreamFactoryForTesting(...)` signature:

```kotlin
internal fun progressiveUpstreamFactoryForTesting(
    url: String,
    headers: Map<String, String> = emptyMap(),
    warmAheadEnabledForProfile: Boolean = false
): DataSource.Factory {
```

Pass `warmAheadEnabledForProfile` through to `selectProgressiveUpstreamFactory(...)`.

Add this helper to `PlayerMediaSourceFactory`:

```kotlin
internal fun currentWarmAheadUpstreamKindForTesting(): String {
    val factory = currentWarmAheadUpstreamFactory ?: return "single"
    return warmAheadUpstreamKindForTesting(factory)
}
```

- [ ] **Step 3: Use the dedicated warm-ahead factory in `startVodWarmAheadIfEligible()`**

Replace:

```kotlin
val upstreamFactory = currentProgressiveUpstreamFactory ?: return
```

with:

```kotlin
val upstreamFactory = currentWarmAheadUpstreamFactory ?: currentProgressiveUpstreamFactory ?: return
```

This preserves non-PRDS behavior and uses a split profile only when one exists.

- [ ] **Step 4: Run focused tests**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.VodWarmAheadPolicyTest"
```

Expected: PASS.

- [ ] **Step 5: Guardrail diff check**

Run:

```bash
git diff -- app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
```

Expected:

```text
No diff for ParallelRangeDataSource.kt.
No setCacheWriteDataSinkFactory(null) in PlayerMediaSourceFactory.kt.
```

- [ ] **Step 6: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "fix: use dedicated warm ahead prds profile"
```

Expected: commit succeeds with only the two listed files staged.

---

### Task 4: Regression Verification

**Files:**
- Read: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Read: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- Read: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
- Read: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTest.kt`

- [ ] **Step 1: Run focused player/cache tests**

Run:

```bash
./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.VodWarmAheadPolicyTest" --tests "com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTest" --tests "com.nexio.tv.ui.screens.player.PlayerLoadControlFactoryTest"
```

Expected: PASS.

- [ ] **Step 2: Build the app**

Run:

```bash
./gradlew --no-daemon :app:assembleUniversalDebug
```

Expected: PASS and produces `app/build/outputs/apk/universal/debug/app-universal-debug.apk`.

- [ ] **Step 3: Inspect guardrail diff**

Run:

```bash
git diff HEAD~3..HEAD -- app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
```

Expected:

```text
No changes in ParallelRangeDataSource.kt.
No setCacheWriteDataSinkFactory(null) in PlayerMediaSourceFactory.kt.
Current profiles are preserved when warm-ahead is disabled.
Split profiles are used only when warm-ahead is enabled for the stream.
```

- [ ] **Step 4: Manual log verification**

On Real-Debrid with `VOD Background Warm-Ahead` disabled, play a progressive stream and run:

```bash
adb logcat | grep "ParallelRangeDS"
```

Expected: playback logs `2 connections, 24MB chunks`.

On Real-Debrid with `VOD Background Warm-Ahead` enabled, play a progressive stream and run:

```bash
adb logcat | grep "ParallelRangeDS"
```

Expected: playback logs include `1 connections, 24MB chunks` and warm-ahead logs include `1 connections, 24MB chunks` after warm-ahead starts.

On Premiumize with `VOD Background Warm-Ahead` enabled, play a progressive stream and run:

```bash
adb logcat | grep "ParallelRangeDS"
```

Expected: playback logs include `2 connections, 16MB chunks` and warm-ahead logs include `1 connections, 16MB chunks` after warm-ahead starts.

- [ ] **Step 5: Inspect final status**

Run:

```bash
git status --short
```

Expected: no unstaged changes in files touched by this plan. Existing unrelated dirty files outside this plan may remain and must not be staged.

## Self-Review

Spec coverage:

- Warm-ahead disabled preserves current profiles: Task 1 resolver tests.
- Real-Debrid split profile: Task 1 resolver tests and Task 2 factory construction.
- Premiumize split profile: Task 1 resolver tests and Task 2 factory construction.
- Default fallback split profile: Task 1 resolver tests.
- Warm-ahead uses a dedicated profile: Tasks 2 and 3.
- PRDS implementation remains untouched: Tasks 3 and 4 guardrail checks.

Placeholder scan:

- No unresolved placeholder language or unspecified test steps are present.

Type consistency:

- `ParallelProviderProfile`, `ParallelProviderProfiles`, `resolveParallelProviderProfiles(...)`, `warmAheadProviderProfileForTesting(...)`, and `currentWarmAheadUpstreamFactory` names are defined before use in later tasks.
