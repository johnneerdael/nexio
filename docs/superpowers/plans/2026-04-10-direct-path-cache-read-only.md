# Direct Path Read-Only Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the direct playback path on Android TV use cache reads only and stop synchronous cache write-through on cache misses, so playback is optimized for steady low CPU/IO rather than foreground cache population.

**Architecture:** Keep the VOD cache feature, but split cache policy by playback path. The direct path should use a read-only cache attachment: read cached bytes when present, fetch uncached bytes directly from the network, and do not write those miss bytes through `CacheDataSink` on the playback loader path. Writable cache behavior remains available for warm-ahead/background fill and for the optimized path if needed later, but this plan only changes the direct path.

**Tech Stack:** Android/Kotlin, Media3 `CacheDataSource`, `CacheDataSink`, ExoPlayer, OkHttp, JUnit4, Robolectric

---

## File Map

### Production files

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - add an explicit direct-path cache attach mode
  - make the direct path use read-only cache attachment
  - keep writable cache writes for explicit warm-ahead only

### Test files

- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
  - pin cache trace listener attachment and the new direct-path cache attach policy helpers
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt`
  - pin that direct playback never becomes writable-on-miss and still never starts warm-ahead

## Guardrails

- Do not change PRDS behavior in this plan.
- Do not remove VOD cache entirely.
- Do not re-enable warm-ahead for the direct path.
- Do not change playback diagnostics gating in this plan; that work already landed separately.
- Do not make the direct path pay extra indirection beyond one explicit attach-mode branch.

---

### Task 1: Introduce An Explicit Cache Attach Mode For Direct Playback

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt`

- [ ] **Step 1: Write the failing policy tests**

```kotlin
@Test
fun `direct path resolves read only cache attach mode`() {
    val mode = PlayerMediaSourceFactory.resolveVodCacheAttachMode(
        useVodCache = true,
        useParallelConnections = false
    )

    assertEquals(PlayerMediaSourceFactory.VodCacheAttachMode.READ_ONLY, mode)
}

@Test
fun `parallel path resolves read write cache attach mode`() {
    val mode = PlayerMediaSourceFactory.resolveVodCacheAttachMode(
        useVodCache = true,
        useParallelConnections = true
    )

    assertEquals(PlayerMediaSourceFactory.VodCacheAttachMode.READ_WRITE, mode)
}

@Test
fun `cache disabled resolves disabled attach mode`() {
    val mode = PlayerMediaSourceFactory.resolveVodCacheAttachMode(
        useVodCache = false,
        useParallelConnections = false
    )

    assertEquals(PlayerMediaSourceFactory.VodCacheAttachMode.DISABLED, mode)
}
```

- [ ] **Step 2: Run the focused tests to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryDirectPathRegressionTest"`

Expected before implementation: FAIL because `VodCacheAttachMode` and `resolveVodCacheAttachMode(...)` do not exist.

- [ ] **Step 3: Add the cache attach mode to `PlayerMediaSourceFactory`**

```kotlin
internal enum class VodCacheAttachMode {
    DISABLED,
    READ_ONLY,
    READ_WRITE,
}

companion object {
    internal fun resolveVodCacheAttachMode(
        useVodCache: Boolean,
        useParallelConnections: Boolean
    ): VodCacheAttachMode {
        return when {
            !useVodCache -> VodCacheAttachMode.DISABLED
            useParallelConnections -> VodCacheAttachMode.READ_WRITE
            else -> VodCacheAttachMode.READ_ONLY
        }
    }
}
```

- [ ] **Step 4: Use the attach mode when selecting direct-path cache behavior**

```kotlin
val cacheAttachMode = resolveVodCacheAttachMode(
    useVodCache = useVodCache && !isVodCacheDisabled,
    useParallelConnections = useParallelConnections
)

currentProgressiveIsEligibleForWarmAhead =
    cacheAttachMode == VodCacheAttachMode.READ_WRITE && useParallelConnections
```

- [ ] **Step 5: Re-run the focused tests**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryDirectPathRegressionTest"`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt
git commit -m "refactor: add direct path cache attach mode"
```

---

### Task 2: Make Direct Playback Cache Attachment Read-Only On Misses

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt`

- [ ] **Step 1: Write the failing direct-path cache write tests**

```kotlin
@Test
fun `direct path cache factory disables foreground writes`() {
    assertFalse(
        PlayerMediaSourceFactory.shouldWriteCacheOnPlaybackPath(
            attachMode = PlayerMediaSourceFactory.VodCacheAttachMode.READ_ONLY,
            blockOnCache = false
        )
    )
}

@Test
fun `warm ahead cache factory still writes`() {
    assertTrue(
        PlayerMediaSourceFactory.shouldWriteCacheOnPlaybackPath(
            attachMode = PlayerMediaSourceFactory.VodCacheAttachMode.READ_ONLY,
            blockOnCache = true
        )
    )
}

@Test
fun `parallel path cache factory still writes`() {
    assertTrue(
        PlayerMediaSourceFactory.shouldWriteCacheOnPlaybackPath(
            attachMode = PlayerMediaSourceFactory.VodCacheAttachMode.READ_WRITE,
            blockOnCache = false
        )
    )
}
```

- [ ] **Step 2: Run the focused tests to verify failure**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryDirectPathRegressionTest"`

Expected before implementation: FAIL because `shouldWriteCacheOnPlaybackPath(...)` does not exist and the cache factory always installs `CacheDataSink`.

- [ ] **Step 3: Add an explicit foreground-write decision helper**

```kotlin
companion object {
    internal fun shouldWriteCacheOnPlaybackPath(
        attachMode: VodCacheAttachMode,
        blockOnCache: Boolean
    ): Boolean {
        return blockOnCache || attachMode == VodCacheAttachMode.READ_WRITE
    }
}
```

- [ ] **Step 4: Update `buildVodCacheDataSourceFactory(...)` to make writes optional**

```kotlin
private fun buildVodCacheDataSourceFactory(
    upstreamFactory: DataSource.Factory,
    cache: SimpleCache,
    attachMode: VodCacheAttachMode,
    blockOnCache: Boolean = false
): DataSource.Factory {
    val dataSinkFactory = CacheDataSink.Factory()
        .setCache(cache)
        .setFragmentSize(2L * 1024L * 1024L)
    var flags = CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
    if (blockOnCache) {
        flags = flags or CacheDataSource.FLAG_BLOCK_ON_CACHE
    }
    return CacheDataSource.Factory()
        .setCache(cache)
        .setCacheKeyFactory(stableCacheKeyFactory)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .apply {
            if (shouldWriteCacheOnPlaybackPath(attachMode, blockOnCache)) {
                setCacheWriteDataSinkFactory(dataSinkFactory)
            }
            if (shouldAttachCacheTraceListener(PlaybackTracer.enabled)) {
                setEventListener(
                    PlaybackTraceCacheEventListener(
                        if (blockOnCache) "warm_ahead" else "progressive"
                    )
                )
            }
        }
        .setFlags(flags)
}
```

- [ ] **Step 5: Pass read-only mode for direct playback and writable mode for warm-ahead**

```kotlin
val progressiveFactory = if (cache != null) {
    buildVodCacheDataSourceFactory(
        upstreamFactory = progressiveUpstreamFactory,
        cache = cache,
        attachMode = cacheAttachMode
    )
} else {
    progressiveUpstreamFactory
}
```

```kotlin
val prefetchFactory = buildVodCacheDataSourceFactory(
    upstreamFactory = upstreamFactory,
    cache = cache,
    attachMode = VodCacheAttachMode.READ_WRITE,
    blockOnCache = true
)
```

- [ ] **Step 6: Re-run the focused tests**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryDirectPathRegressionTest"`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt
git commit -m "fix: make direct path cache read only on misses"
```

---

### Task 3: Pin Android TV Direct-Path Intent In Regression Tests

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Add a direct-path regression test for the final policy**

```kotlin
@Test
fun `direct playback is read only cache plus no warm ahead`() {
    val attachMode = PlayerMediaSourceFactory.resolveVodCacheAttachMode(
        useVodCache = true,
        useParallelConnections = false
    )

    assertEquals(PlayerMediaSourceFactory.VodCacheAttachMode.READ_ONLY, attachMode)
    assertFalse(
        PlayerMediaSourceFactory.shouldWriteCacheOnPlaybackPath(
            attachMode = attachMode,
            blockOnCache = false
        )
    )
}
```

- [ ] **Step 2: Run the final focused direct-path suite**

Run: `./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryDirectPathRegressionTest"`

Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryDirectPathRegressionTest.kt
git commit -m "test: pin android tv direct path cache policy"
```

---

## Acceptance Criteria

- Direct playback still reads cached bytes when they already exist.
- Direct playback no longer writes cache misses through the foreground playback loader path.
- Warm-ahead remains off for the direct path.
- Explicit warm-ahead/background cache fill remains writable.
- The implementation stays local to `PlayerMediaSourceFactory` and does not perturb PRDS or broader playback diagnostics behavior.
