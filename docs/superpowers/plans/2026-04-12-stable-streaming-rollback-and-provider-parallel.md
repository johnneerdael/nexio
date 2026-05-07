# Stable Streaming Rollback And Provider Parallel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore a stable high-bitrate playback architecture by returning to the no-VOD-cache/no-parallel baseline, reintroducing the previously stable VOD cache + parallel range transport, and making only one allowed deviation: fixed provider-specific parallel profiles.

**Architecture:** Start from the clean rollback baseline commit `e231bb8497c0397717bd5719f91083923f0ce5fe` rather than continuing to patch the current streaming-cache branch. Port the old stable transport/cache implementation from `/Users/jneerdael/Scripts/nexio-debugbuild` with no behavioral deviation except provider-fixed parallel settings. Keep the current Phase 4 streaming-cache experiment out of the rebuilt stable path; re-merge unrelated interim features later through their already-open PRs.

**Tech Stack:** Android/Kotlin, Media3 ExoPlayer, OkHttp, Media3 `CacheDataSource`/`SimpleCache`, `ParallelRangeDataSource`, Gradle universal debug variant, Robolectric unit tests.

---

## Current Evidence And Constraints

Do not continue hardening the current Phase 4 streaming-cache branch as the default path. Recent device evidence showed:

- `PHASE3_CACHE_WITH_FILL`: `STREAM_CACHE_DATASOURCE branch=CacheDataSource mode=PHASE3_CACHE_WITH_FILL`, fill active, repeated stutter/rebuffer.
- `COVERAGE_ONLY`: `STREAM_CACHE_DATASOURCE branch=CoverageAwareDataSource mode=COVERAGE_ONLY`, fill skipped, stutter still present.
- `PHASE3_CACHE_ONLY`: `STREAM_CACHE_DATASOURCE branch=CacheDataSource mode=PHASE3_CACHE_ONLY`, fill skipped, no `CacheFill-0`, stutter still present.
- Therefore the stable recovery path should be rebuilt from the old transport-first architecture, not by patching `CoverageAwareDataSource` or `CacheFillWorker`.

Baseline and reference roots:

- Current repo: `/Users/jneerdael/Scripts/nexio`
- No-VOD/no-parallel reference: `/Users/jneerdael/Scripts/NuvioTV`
- Old stable VOD/parallel reference: `/Users/jneerdael/Scripts/nexio-debugbuild`
- Preferred rollback anchor: `e231bb8497c0397717bd5719f91083923f0ce5fe`
- Earlier rollback anchor if `e231bb8497c0397717bd5719f91083923f0ce5fe` proves wrong: `1744464953fe8223cf2670998f73f4eee2d0fd0f`

Important working-tree constraint:

- Do not implement this in the current dirty worktree. Current local tree contains unrelated modified files such as `.gitignore`, `MetadataDiskCacheStore.kt`, `CapabilityEnvelope.kt`, and `SimklLibraryServiceTest.kt`. Create a clean worktree for this rollback.

Explicitly out of scope for this rollback branch:

- GitHub PR `#7`: `Fix TV focus during detail and stream loading`
- GitHub PR `#8`: `Add vendor-agnostic subtitle translation settings`

Those PRs already target `main`, are open, and can be merged after the stable streaming rollback is validated. Do not cherry-pick their commits into this rollback branch.

Boot cleanup requirement:

- The release app package and debug app package have separate Android sandbox directories. One package cannot delete the other package's cache data.
- Ship the same obsolete-cache cleanup code in both release and debug builds so each package removes stale playback cache data from its own `cacheDir` on boot.
- Obsolete directories to delete on boot: `stream-cache` and `player_vod_cache_v2`.
- Do not delete `player_vod_cache` after Phase 2, because that is the old stable VOD cache directory being intentionally restored.

---

## File Structure

Files restored from rollback baseline:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt` — clean Media3/OkHttp source factory at baseline.
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt` — player construction and load control at baseline.
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt` — playback logging/telemetry hooks at baseline.
- `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt` — baseline settings store before VOD/parallel reintroduction.
- Settings UI files under `app/src/main/java/com/nexio/tv/ui/screens/settings/` — baseline playback settings before VOD/parallel reintroduction.

Files added for obsolete cache cleanup:

- `app/src/main/java/com/nexio/tv/ui/screens/player/ObsoletePlaybackCacheCleanup.kt` — deletes stale playback cache directories that belong to removed experiments.
- `app/src/test/java/com/nexio/tv/ui/screens/player/ObsoletePlaybackCacheCleanupTest.kt` — verifies stale cache directories are removed while the restored `player_vod_cache` directory is preserved.
- `app/src/main/java/com/nexio/tv/NexioApplication.kt` — invokes cleanup from `onCreate()` so both release and debug builds clean their own sandbox cache directories at boot.

Files copied from `/Users/jneerdael/Scripts/nexio-debugbuild` during Phase 2:

- `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTest.kt`
- Relevant VOD cache code sections in `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Relevant playback settings state/UI code for `VodCacheSizeMode`, VOD cache enabled/size, and parallel enabled.

Files modified in Phase 3 for provider-fixed profiles:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Playback settings UI/ViewModel files that currently expose per-user parallel connection count and chunk size.
- Tests for provider profile selection.

Files that must not be reintroduced:

- `CoverageAwareDataSource.kt`
- `CacheFillWorker.kt`
- `StreamingCacheFillSession.kt`
- `FillController.kt`
- `StreamingCacheProvider.kt`
- `StreamingCacheMissCoordinator.kt`
- `StreamingRangeCoordinator.kt`
- `StreamingCacheDebugMode.kt`
- `StreamingCacheKillSwitch.kt`
- `StreamingCacheMemoryPressureMonitor.kt`
- `StreamingMetrics.kt`
- `PlayerPlaybackNetworking.kt`, unless the chosen baseline commit already contains it and it is needed for clean Media3 wiring.

---

## Task 1: Create Clean Rollback Worktree From Baseline

**Files:**
- No source edits.
- Worktree path: `/Users/jneerdael/Scripts/nexio-stable-streaming-rollback`

- [ ] **Step 1: Verify current worktree is not used for implementation**

Run:

```bash
git status --short
```

Expected: output may include unrelated local changes. Do not stage, revert, or edit those changes for this plan.

- [ ] **Step 2: Create a dedicated clean worktree from `e231bb8497c0397717bd5719f91083923f0ce5fe`**

Run:

```bash
git worktree add /Users/jneerdael/Scripts/nexio-stable-streaming-rollback e231bb8497c0397717bd5719f91083923f0ce5fe
```

Expected: command succeeds and checks out the detached baseline commit.

- [ ] **Step 3: Create a branch in the new worktree**

Run:

```bash
git -C /Users/jneerdael/Scripts/nexio-stable-streaming-rollback checkout -b stable-streaming-rollback
```

Expected: `Switched to a new branch 'stable-streaming-rollback'`.

- [ ] **Step 4: Verify the new worktree is clean**

Run:

```bash
git -C /Users/jneerdael/Scripts/nexio-stable-streaming-rollback status --short
```

Expected: no output.

- [ ] **Step 5: Initialize submodules in the rollback worktree**

Run:

```bash
git -C /Users/jneerdael/Scripts/nexio-stable-streaming-rollback submodule update --init --recursive
```

Expected: submodules including `media`, `dovi_tool`, `nexio-web`, and nested `media/FFmpeg` are checked out. This step is mandatory before compilation because player code references custom Media3/Kodi/Dolby source in the `media` submodule.

- [ ] **Step 6: Create local Media3 source-mode configuration**

Create `/Users/jneerdael/Scripts/nexio-stable-streaming-rollback/local.dev.properties` with:

```properties
USE_MEDIA3_SOURCE=true
DOVI_NATIVE_ENABLED=false
```

Expected: the file remains untracked/ignored. This step is mandatory because `settings.gradle.kts` and `app/build.gradle.kts` must both read `USE_MEDIA3_SOURCE=true`; passing `-PUSE_MEDIA3_SOURCE=true` alone is not sufficient for `app/build.gradle.kts`.

- [ ] **Step 7: Verify submodules are present**

Run:

```bash
test -f /Users/jneerdael/Scripts/nexio-stable-streaming-rollback/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/text/CueGroupSubtitleTranslator.java
test -f /Users/jneerdael/Scripts/nexio-stable-streaming-rollback/media/libraries/common/src/main/java/androidx/media3/common/util/DolbyVisionCompatibility.java
test -f /Users/jneerdael/Scripts/nexio-stable-streaming-rollback/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java
```

Expected: all three commands exit `0`.

- [ ] **Step 8: Commit marker is not needed**

No commit for this task. The worktree branch points at the rollback baseline.

---

## Task 2: Validate Phase 1 No-VOD/No-Parallel Baseline

**Files:**
- Read only:
  - `/Users/jneerdael/Scripts/nexio-stable-streaming-rollback/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - `/Users/jneerdael/Scripts/nexio-stable-streaming-rollback/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - `/Users/jneerdael/Scripts/nexio-stable-streaming-rollback/app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`

- [ ] **Step 1: Assert the new streaming-cache experiment is absent**

Run:

```bash
for file in \
  app/src/main/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSource.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/CacheFillWorker.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheFillSession.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheProvider.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheMissCoordinator.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinator.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheKillSwitch.kt \
  app/src/main/java/com/nexio/tv/data/local/StreamingCacheDebugMode.kt; do \
  test ! -e "/Users/jneerdael/Scripts/nexio-stable-streaming-rollback/$file" || exit 1; \
done
```

Expected: exit code `0`.

- [ ] **Step 2: Assert old VOD/parallel transport is absent in Phase 1**

Run:

```bash
test ! -e /Users/jneerdael/Scripts/nexio-stable-streaming-rollback/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt
```

Expected: exit code `0`.

Run:

```bash
rg -n "vodCache|VodCache|ParallelRange|parallelConnection|useParallelConnections|player_vod_cache" /Users/jneerdael/Scripts/nexio-stable-streaming-rollback/app/src/main/java/com/nexio/tv/ui/screens/player /Users/jneerdael/Scripts/nexio-stable-streaming-rollback/app/src/main/java/com/nexio/tv/data/local
```

Expected: no matches. If there are matches, inspect them. If they are real VOD/parallel behavior rather than comments or unrelated text, stop and restart Task 1 from `1744464953fe8223cf2670998f73f4eee2d0fd0f`.

- [ ] **Step 3: Run baseline player/media source tests**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerInitializationTest
```

Expected: `BUILD SUCCESSFUL`. If `PlayerRuntimeControllerInitializationTest` does not exist at this baseline, rerun with only `PlayerMediaSourceFactoryTest` and record that the initialization test is absent at baseline.

- [ ] **Step 4: Compile Phase 1 baseline**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit Phase 1 validation note**

Create a small validation note to make the branch history explicit:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
mkdir -p docs/superpowers/reports
printf '%s\n' \
  '# Stable Streaming Rollback Phase 1 Validation' \
  '' \
  '- Baseline commit: e231bb8497c0397717bd5719f91083923f0ce5fe' \
  '- Expected state: no VOD cache, no parallel connections, no streaming-cache Phase 4 code.' \
  '- Validation commands: see implementation plan 2026-04-12-stable-streaming-rollback-and-provider-parallel.md.' \
  > docs/superpowers/reports/2026-04-12-stable-streaming-phase1-validation.md
git add docs/superpowers/reports/2026-04-12-stable-streaming-phase1-validation.md
git commit -m "docs: mark stable streaming rollback baseline"
```

Expected: one docs-only commit.

---

## Task 3: Add Boot Cleanup For Obsolete Playback Cache Directories

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/ObsoletePlaybackCacheCleanup.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/ObsoletePlaybackCacheCleanupTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/NexioApplication.kt`

- [ ] **Step 1: Write failing cleanup tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/ObsoletePlaybackCacheCleanupTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObsoletePlaybackCacheCleanupTest {

    @Test
    fun cleanup_removesStreamingCacheAndVodCacheV2() {
        val root = createTempDirectory("obsolete-playback-cache").toFile()
        val streamCache = File(root, "stream-cache").apply {
            mkdirs()
            File(this, "cached.exo").writeText("old-stream-cache")
        }
        val vodCacheV2 = File(root, "player_vod_cache_v2").apply {
            mkdirs()
            File(this, "cached.exo").writeText("old-v2-cache")
        }

        try {
            ObsoletePlaybackCacheCleanup.cleanup(root)

            assertFalse(streamCache.exists())
            assertFalse(vodCacheV2.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanup_preservesRestoredVodCacheDirectory() {
        val root = createTempDirectory("obsolete-playback-cache").toFile()
        val restoredVodCache = File(root, "player_vod_cache").apply {
            mkdirs()
            File(this, "cached.exo").writeText("stable-cache")
        }

        try {
            ObsoletePlaybackCacheCleanup.cleanup(root)

            assertTrue(restoredVodCache.exists())
            assertTrue(File(restoredVodCache, "cached.exo").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
```

- [ ] **Step 2: Run cleanup tests to verify failure**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.ObsoletePlaybackCacheCleanupTest
```

Expected: FAIL because `ObsoletePlaybackCacheCleanup` does not exist.

- [ ] **Step 3: Implement obsolete cache cleanup**

Create `app/src/main/java/com/nexio/tv/ui/screens/player/ObsoletePlaybackCacheCleanup.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import java.io.File

internal object ObsoletePlaybackCacheCleanup {
    private val obsoleteDirectoryNames = setOf(
        "stream-cache",
        "player_vod_cache_v2"
    )

    fun cleanup(cacheRoot: File) {
        obsoleteDirectoryNames.forEach { directoryName ->
            val staleDir = File(cacheRoot, directoryName)
            if (!staleDir.exists()) return@forEach
            if (!staleDir.isDirectory) return@forEach
            runCatching { staleDir.deleteRecursively() }
        }
    }
}
```

- [ ] **Step 4: Wire cleanup into application boot**

Modify `app/src/main/java/com/nexio/tv/NexioApplication.kt`:

```kotlin
import com.nexio.tv.ui.screens.player.ObsoletePlaybackCacheCleanup
```

In `onCreate()`, inside the existing `appScope.launch { ... }` block, call cleanup before poster cleanup:

```kotlin
appScope.launch {
    ObsoletePlaybackCacheCleanup.cleanup(cacheDir)
    runPosterCacheCleanup()
}
```

If there is no existing `appScope.launch` block at the rollback baseline, add:

```kotlin
private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

and use the same `appScope.launch { ... }` block. Do not perform recursive file deletion on the main thread.

- [ ] **Step 5: Run cleanup tests**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.ObsoletePlaybackCacheCleanupTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Compile after boot cleanup**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit obsolete cache cleanup**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
git add app/src/main/java/com/nexio/tv/ui/screens/player/ObsoletePlaybackCacheCleanup.kt app/src/test/java/com/nexio/tv/ui/screens/player/ObsoletePlaybackCacheCleanupTest.kt app/src/main/java/com/nexio/tv/NexioApplication.kt
git commit -m "fix: remove obsolete playback caches on boot"
```

Expected: one commit that runs in both release and debug builds. This removes each package's own stale `stream-cache` and `player_vod_cache_v2` directories, but preserves the restored `player_vod_cache` directory.

---

## Task 4: Reintroduce Old Stable Parallel Range Transport

**Files:**
- Copy from old stable:
  - `/Users/jneerdael/Scripts/nexio-debugbuild/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`
  - `/Users/jneerdael/Scripts/nexio-debugbuild/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTest.kt`
- Modify:
  - `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
  - Playback settings files if required by copied settings model.

- [ ] **Step 1: Copy the old stable `ParallelRangeDataSource` implementation exactly**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
cp /Users/jneerdael/Scripts/nexio-debugbuild/app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt
cp /Users/jneerdael/Scripts/nexio-debugbuild/app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTest.kt app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTest.kt
```

Expected: two files copied.

- [ ] **Step 2: Port only the old stable parallel settings model**

From `/Users/jneerdael/Scripts/nexio-debugbuild/app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`, port these exact model fields and persistence keys into the rollback worktree:

```kotlin
val useParallelConnections: Boolean = DEFAULT_USE_PARALLEL_CONNECTIONS,
val parallelConnectionCount: Int = DEFAULT_PARALLEL_CONNECTION_COUNT,
val parallelChunkSizeMb: Int = DEFAULT_PARALLEL_CHUNK_SIZE_MB,
```

and constants:

```kotlin
const val DEFAULT_USE_PARALLEL_CONNECTIONS = true
const val DEFAULT_PARALLEL_CONNECTION_COUNT = 2
const val DEFAULT_PARALLEL_CHUNK_SIZE_MB = 16
const val MIN_PARALLEL_CONNECTION_COUNT = 2
const val MAX_PARALLEL_CONNECTION_COUNT = 4
```

and keys:

```kotlin
private val useParallelConnectionsKey = booleanPreferencesKey("use_parallel_connections")
private val parallelConnectionCountKey = intPreferencesKey("parallel_connection_count")
private val parallelChunkSizeMbKey = intPreferencesKey("parallel_chunk_size_mb")
```

Do not port benchmark matrix settings or debrid config benchmark code.

- [ ] **Step 3: Wire the old stable parallel path into `PlayerMediaSourceFactory`**

In `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`, port the old stable logic from `/Users/jneerdael/Scripts/nexio-debugbuild/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`:

```kotlin
parallelStartupPrefetchUnlocked.set(!(useParallelConnections && !isHls && !isDash))
activeReadBytePosition.set(0L)
val progressiveUpstreamFactory: DataSource.Factory = when {
    !usesHttpUpstream(url) -> baseDataSourceFactory
    useParallelConnections && !isHls && !isDash -> ParallelRangeDataSource.Factory(
        okHttpFactory,
        parallelConnectionCount,
        parallelChunkSizeMb.toLong() * 1024L * 1024L,
        shouldAllowBackgroundPrefetch = { parallelStartupPrefetchUnlocked.get() },
        onResolvedUri = { resolved ->
            currentVodCacheResolvedUrl = resolved?.toString()
        },
        onReadPositionAdvanced = { position ->
            activeReadBytePosition.accumulateAndGet(position) { current, next ->
                if (next > current) next else current
            }
        }
    )
    else -> okHttpFactory
}
```

Expected: progressive HTTP streams use `ParallelRangeDataSource.Factory` when the parallel toggle is enabled; HLS/DASH stay on normal factories.

- [ ] **Step 4: Wire player settings into media source factory**

In `PlayerRuntimeControllerInitialization.kt`, port:

```kotlin
mediaSourceFactory.useParallelConnections = playerSettings.useParallelConnections
mediaSourceFactory.parallelConnectionCount = playerSettings.parallelConnectionCount
mediaSourceFactory.parallelChunkSizeMb = playerSettings.parallelChunkSizeMb
```

Expected: runtime player settings control old stable parallel transport.

- [ ] **Step 5: Run old stable parallel tests**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Compile after parallel transport**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit parallel transport restore**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
git add app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTest.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt app/src/test/java
git commit -m "feat: restore stable parallel range playback"
```

Expected: one commit restoring old stable parallel range playback.

---

## Task 5: Reintroduce Old Stable VOD Cache

**Files:**
- Modify:
  - `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
  - `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
  - Playback settings UI/ViewModel files needed for VOD cache toggle/size.

- [ ] **Step 1: Port `VodCacheSizeMode` and VOD cache settings from old stable**

From `/Users/jneerdael/Scripts/nexio-debugbuild/app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`, port:

```kotlin
enum class VodCacheSizeMode {
    ON,
    OFF
}
```

and settings fields:

```kotlin
val vodCacheSizeMode: VodCacheSizeMode = DEFAULT_VOD_CACHE_SIZE_MODE,
val vodCacheSizeMb: Int = DEFAULT_VOD_CACHE_SIZE_MB,
```

and constants:

```kotlin
const val DEFAULT_VOD_CACHE_SIZE_MB = 500
const val MIN_VOD_CACHE_SIZE_MB = 100
const val MAX_VOD_CACHE_SIZE_MB = 65_536
val DEFAULT_VOD_CACHE_SIZE_MODE: VodCacheSizeMode = VodCacheSizeMode.ON
```

and keys:

```kotlin
private val vodCacheSizeModeKey = stringPreferencesKey("vod_cache_size_mode")
private val vodCacheSizeMbKey = intPreferencesKey("vod_cache_size_mb")
```

and setters:

```kotlin
suspend fun setVodCacheSizeMode(mode: VodCacheSizeMode) {
    dataStore.edit { prefs ->
        prefs[vodCacheSizeModeKey] = mode.name
    }
}

suspend fun setVodCacheSizeMb(mb: Int) {
    dataStore.edit { prefs ->
        prefs[vodCacheSizeMbKey] = mb.coerceIn(
            PlayerSettings.MIN_VOD_CACHE_SIZE_MB,
            PlayerSettings.MAX_VOD_CACHE_SIZE_MB
        )
    }
}
```

- [ ] **Step 2: Port VOD cache fields and lifecycle into `PlayerMediaSourceFactory`**

Port the old stable fields from `/Users/jneerdael/Scripts/nexio-debugbuild/app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`:

```kotlin
@Volatile private var currentVodCacheUrl: String? = null
@Volatile private var currentVodCacheResolvedUrl: String? = null
@Volatile private var currentVodCacheActive: Boolean = false
@Volatile private var currentProgressiveUpstreamFactory: DataSource.Factory? = null
@Volatile private var currentProgressiveIsEligibleForWarmAhead: Boolean = false
private val parallelStartupPrefetchUnlocked = AtomicBoolean(true)
private val activeReadBytePosition = AtomicLong(0L)
private val prefetchStop = AtomicBoolean(false)
private var prefetchFuture: Future<*>? = null
private var activePrefetchWriter: CacheWriter? = null
private val prefetchExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "Nexio-vod-prefetch").apply { isDaemon = true }
}
```

and public methods:

```kotlin
fun warmupVodCacheAsync()
fun notifyPlaybackFirstFrameRendered()
fun getVodCacheLogState(currentStreamUrl: String? = null): String
fun clearVodCache()
```

Expected: these methods compile and match old stable behavior.

- [ ] **Step 3: Port VOD cache creation and warm-ahead exactly**

Port these old stable methods from `nexio-debugbuild` without changing behavior:

```kotlin
private fun startVodWarmAheadIfEligible()
internal fun stopVodWarmAhead()
private fun runWarmAheadLoop(...)
private fun findNextUncachedHole(...)
private fun contiguousCachedPrefix(...)
private fun buildVodCacheDataSourceFactory(...)
private fun shouldUseVodCache(url: String): Boolean
private fun resolveVodCacheMaxBytes(context: Context): Long
private fun resolveRuntimeVodCacheUpperBoundBytes(context: Context, hardMaxBytes: Long): Long
private fun getReadySimpleCache(expectedMaxBytes: Long): SimpleCache?
private fun getAnySimpleCache(): SimpleCache?
private fun clearVodCacheInternal(context: Context)
private fun getOrCreateSimpleCache(context: Context, maxBytes: Long): SimpleCache
private fun maybeApplyLiveVodCacheCapIncrease(...)
private fun startVodCacheInitialization(context: Context, maxBytes: Long)
private fun maybeLogDeferredReconfigure(requestedMaxBytes: Long)
```

Keep old stable constants:

```kotlin
private const val ENABLE_VOD_CACHE = true
private const val VOD_CACHE_DIR = "player_vod_cache"
private const val VOD_CACHE_FREE_SPACE_RESERVE_BYTES = 1024L * 1024L * 1024L
private const val MIN_RUNTIME_VOD_CACHE_BYTES = 1L * 1024L * 1024L
private const val PREFETCH_BLOCK_BYTES = 16L * 1024L * 1024L
private const val PREFETCH_ACTIVE_GUARD_BYTES = 8L * 1024L * 1024L
private const val PREFETCH_REBASE_SLEEP_MS = 100L
private const val PREFETCH_IDLE_SLEEP_MS = 250L
private const val PREFETCH_MAX_IDLE_CYCLES = 20
private const val LIVE_CACHE_RECONFIGURE_MIN_DELTA_BYTES = 64L * 1024L * 1024L
```

- [ ] **Step 4: Wire old VOD telemetry hooks**

From old stable `PlayerRuntimeController.kt` and `PlayerRuntimeControllerPlaybackEvents.kt`, port:

```kotlin
internal var vodTelemetryJob: Job? = null
internal var cachedVodCacheLogState: String = "vod=warming"
```

and the periodic `mediaSourceFactory.getVodCacheLogState(streamUrl)` update used by buffer logs.

Expected: buffer logs can report `vod=on total=X/YMB stream=ZMB active=true/false`.

- [ ] **Step 5: Wire VOD cache settings into initialization and observers**

Port:

```kotlin
mediaSourceFactory.vodCacheSizeMode = playerSettings.vodCacheSizeMode
mediaSourceFactory.vodCacheSizeMb = playerSettings.vodCacheSizeMb
```

into initialization and settings observer paths, as in old stable.

- [ ] **Step 6: Run VOD/parallel focused tests**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest --tests com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Compile after VOD cache**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit VOD cache restore**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
git add app/src/main/java/com/nexio/tv/ui/screens/player app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt app/src/main/java/com/nexio/tv/ui/screens/settings app/src/test/java
git commit -m "feat: restore stable vod cache warm-ahead"
```

Expected: one commit restoring the old stable VOD cache and warm-ahead behavior.

---

## Task 6: Replace User-Configurable Parallel Knobs With Provider-Fixed Profiles

**Files:**
- Modify:
  - `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  - `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
  - Playback settings UI/ViewModel files that expose connection count/chunk size.
- Test:
  - `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`

- [ ] **Step 1: Add provider profile data class**

In `PlayerMediaSourceFactory.kt`, add:

```kotlin
private data class ParallelProviderProfile(
    val connectionCount: Int,
    val chunkSizeMb: Int
)
```

- [ ] **Step 2: Add provider profile resolver**

In `PlayerMediaSourceFactory.kt`, add:

```kotlin
private fun resolveParallelProviderProfile(url: String): ParallelProviderProfile {
    val host = runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.US) }
        .getOrDefault("")
    return when {
        host.contains("premiumize") || host.contains("pm") -> ParallelProviderProfile(
            connectionCount = 3,
            chunkSizeMb = 16
        )
        host.contains("real-debrid") || host.contains("realdebrid") || host.contains("rd") -> ParallelProviderProfile(
            connectionCount = 2,
            chunkSizeMb = 24
        )
        else -> ParallelProviderProfile(
            connectionCount = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
            chunkSizeMb = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB
        )
    }
}
```

Note: If host matching is too weak for resolved provider URLs, replace host substring matching with the existing `serviceKey`/provider key if available in `PlayerNavigationArgs`. Do not add benchmark-driven profile selection in this plan.

- [ ] **Step 3: Apply provider profile only when parallel toggle is enabled**

Replace the old wiring:

```kotlin
ParallelRangeDataSource.Factory(
    okHttpFactory,
    parallelConnectionCount,
    parallelChunkSizeMb.toLong() * 1024L * 1024L,
    ...
)
```

with:

```kotlin
val parallelProfile = resolveParallelProviderProfile(url)
ParallelRangeDataSource.Factory(
    okHttpFactory,
    parallelProfile.connectionCount,
    parallelProfile.chunkSizeMb.toLong() * 1024L * 1024L,
    shouldAllowBackgroundPrefetch = { parallelStartupPrefetchUnlocked.get() },
    onResolvedUri = { resolved ->
        currentVodCacheResolvedUrl = resolved?.toString()
    },
    onReadPositionAdvanced = { position ->
        activeReadBytePosition.accumulateAndGet(position) { current, next ->
            if (next > current) next else current
        }
    }
)
```

Expected:

```text
parallel toggle OFF -> no ParallelRangeDataSource
parallel toggle ON + Premiumize -> 3 connections, 16 MB chunks
parallel toggle ON + Real-Debrid -> 2 connections, 24 MB chunks
parallel toggle ON + unknown provider -> old default 2 connections, 16 MB chunks
```

- [ ] **Step 4: Keep only the parallel enabled toggle in settings**

Remove or hide user-facing controls for:

```text
parallelConnectionCount
parallelChunkSizeMb
```

Keep only:

```text
useParallelConnections
```

Do not remove the persisted keys yet; leaving them ignored is safer than writing a migration during rollback. Do not let those persisted values control runtime behavior.

- [ ] **Step 5: Add provider profile tests**

In `PlayerMediaSourceFactoryTest.kt`, add tests that construct a progressive media source with `useParallelConnections=true` and assert logs or factory state for provider profile selection. If the factory internals are not inspectable, add a `@VisibleForTesting` method:

```kotlin
@VisibleForTesting
internal fun parallelProviderProfileForTesting(url: String): Pair<Int, Int> {
    val profile = resolveParallelProviderProfile(url)
    return profile.connectionCount to profile.chunkSizeMb
}
```

Add tests:

```kotlin
@Test
fun parallelProviderProfile_premiumizeUsesThreeBySixteen() {
    val factory = PlayerMediaSourceFactory(appContext())

    assertEquals(3 to 16, factory.parallelProviderProfileForTesting("https://premiumize.me/path/movie.mkv"))
}

@Test
fun parallelProviderProfile_realDebridUsesTwoByTwentyFour() {
    val factory = PlayerMediaSourceFactory(appContext())

    assertEquals(2 to 24, factory.parallelProviderProfileForTesting("https://real-debrid.com/path/movie.mkv"))
}
```

- [ ] **Step 6: Run provider profile tests**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit provider-fixed parallel profiles**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt app/src/main/java/com/nexio/tv/ui/screens/settings app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt
git commit -m "feat: use provider fixed parallel profiles"
```

Expected: one commit containing the only intentional deviation from `/Users/jneerdael/Scripts/nexio-debugbuild`.

---

## Task 7: Remove Current Streaming-Cache Experiment From Stable Branch

**Files:**
- Delete if present:
  - `app/src/main/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSource.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/player/CacheFillWorker.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheFillSession.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/player/FillController.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheProvider.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheMissCoordinator.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinator.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheDebugMode.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheKillSwitch.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheMemoryPressureMonitor.kt`
  - `app/src/main/java/com/nexio/tv/ui/screens/player/StreamingMetrics.kt`

- [ ] **Step 1: Assert experiment files are absent**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
for file in \
  app/src/main/java/com/nexio/tv/ui/screens/player/CoverageAwareDataSource.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/CacheFillWorker.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheFillSession.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/FillController.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheProvider.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheMissCoordinator.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/StreamingRangeCoordinator.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheKillSwitch.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/StreamingCacheMemoryPressureMonitor.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/StreamingMetrics.kt \
  app/src/main/java/com/nexio/tv/data/local/StreamingCacheDebugMode.kt; do \
  test ! -e "$file" || exit 1; \
done
```

Expected: exit code `0`.

- [ ] **Step 2: Search for forbidden references**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
rg -n "CoverageAware|CacheFillWorker|StreamingCache|streaming_cache|stream-cache|CacheMissCoordinator|FillController|StreamingMetrics" app/src/main/java app/src/test/java
```

Expected: no matches except intentionally named documentation in this plan or migration notes. Source code matches are a failure.

- [ ] **Step 3: Commit only if files were removed**

If Task 7 removed files, run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
git add -A app/src/main/java app/src/test/java
git commit -m "chore: remove experimental streaming cache path"
```

Expected: one cleanup commit. If no files were present, skip commit.

---

## Task 8: Final Verification And Device Install

**Files:**
- No production edits unless tests expose defects.

- [ ] **Step 1: Run focused unit tests**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest --tests com.nexio.tv.ui.screens.player.ParallelRangeDataSourceTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Compile and assemble**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:assembleUniversalDebug
```

Expected: both commands end with `BUILD SUCCESSFUL`.

- [ ] **Step 3: Install to `192.168.50.71`**

Run:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
adb -s 192.168.50.71:5555 install -r app/build/outputs/apk/universal/debug/app-universal-debug.apk
```

Expected: `Success`.

- [ ] **Step 4: Validate runtime path on device**

Force-stop and relaunch the app, start the same high-bitrate stream, then run:

```bash
adb -s 192.168.50.71:5555 shell pidof com.nexiodebug.tv
adb -s 192.168.50.71:5555 logcat -d --pid <pid> -t 3000 | grep -Ei 'Parallel mode|ParallelRangeDS|Using VOD cache|BUFFER:|AudioTrack|JankStats|GC|Playback error|Source error'
adb -s 192.168.50.71:5555 shell ps -T -p <pid> | grep -E 'Parallel|ExoPlayer|AudioTrack|OkHttp|Nexio-vod-prefetch'
adb -s 192.168.50.71:5555 shell dumpsys meminfo <pid>
adb -s 192.168.50.71:5555 shell run-as com.nexiodebug.tv du -sh cache/player_vod_cache cache
```

Expected runtime indicators:

```text
ParallelRangeDS: Parallel mode: 3 connections, 16MB chunks ...   // Premiumize stream
ParallelRangeDS: Parallel mode: 2 connections, 24MB chunks ...   // Real-Debrid stream
Using VOD cache for host=...
Nexio-vod-prefetch may exist only after first frame; it must not outrun the active read position guard.
No CoverageAwareDataSource logs.
No CacheFill-0 thread.
No stream-cache directory growth.
```

- [ ] **Step 5: Commit verification report**

Create:

```bash
cd /Users/jneerdael/Scripts/nexio-stable-streaming-rollback
mkdir -p docs/superpowers/reports
printf '%s\n' \
  '# Stable Streaming Rollback Final Verification' \
  '' \
  '- Restored baseline from e231bb8497c0397717bd5719f91083923f0ce5fe.' \
  '- Reintroduced old stable VOD cache and ParallelRangeDataSource.' \
  '- Applied provider-fixed profile deviation: Premiumize 3x16MB, Real-Debrid 2x24MB.' \
  '- Confirmed no CoverageAwareDataSource / CacheFillWorker / StreamingCacheFillSession in stable path.' \
  > docs/superpowers/reports/2026-04-12-stable-streaming-final-verification.md
git add docs/superpowers/reports/2026-04-12-stable-streaming-final-verification.md
git commit -m "docs: record stable streaming rollback verification"
```

Expected: one docs-only verification commit.

---

## Self-Review

**Spec coverage:** This plan covers the requested three phases:

- Phase 1: rollback to no VOD cache / no parallel connections via `e231bb8497c0397717bd5719f91083923f0ce5fe`, with validation against forbidden streaming-cache files.
- Boot cleanup: remove obsolete `stream-cache` and `player_vod_cache_v2` directories from each build variant's own sandbox on startup while preserving restored `player_vod_cache`.
- Phase 2: reintroduce VOD cache + `ParallelRangeDataSource` from `/Users/jneerdael/Scripts/nexio-debugbuild`.
- Phase 3: single allowed deviation, provider-fixed parallel profiles: Premiumize `3x16MB`, Real-Debrid `2x24MB`.
- No other intentional code deviations from the old stable VOD/parallel implementation.

**Placeholder scan:** No `TBD`, `TODO`, or unspecified implementation steps remain. Conditional stops are explicit safety gates, not placeholders.

**Type consistency:** Names used in tasks match inspected code or are introduced in the relevant task: `ParallelRangeDataSource`, `VodCacheSizeMode`, `PlayerMediaSourceFactory`, `PlayerSettingsDataStore`, `ParallelProviderProfile`.

**Important execution warning:** Do not execute this plan inside `/Users/jneerdael/Scripts/nexio` while it has unrelated dirty files. Use `/Users/jneerdael/Scripts/nexio-stable-streaming-rollback`.
