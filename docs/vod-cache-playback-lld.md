# VOD Cache Playback LLD

## Purpose

This document explains Nexio's VOD cache implementation end-to-end:

- what the cache does
- where the implementation lives
- how it integrates with Media3
- when it is enabled or bypassed
- how it interacts with the parallel-connections transport
- how capacity is chosen, initialized, warmed, and cleared
- what is custom Nexio behavior versus standard Media3 cache behavior

The short answer is:

- **The VOD cache is a Nexio-owned policy and lifecycle layer built on top of Media3 cache primitives.**
- **Media3 provides the cache building blocks (`SimpleCache`, `CacheDataSource`, `CacheWriter`, evictors).**
- **Nexio decides when to use cache, how large it may be, when it is warmed, and when it is destroyed.**

## Executive Summary

### What problem this solves

The cache exists to absorb bandwidth variability for progressive VOD playback by writing already-fetched or speculative future data to disk. That reduces repeated network pressure during the same playback session and gives Nexio a warm-ahead runway behind the player.

### What the cache is

This is **not** an offline download system and **not** a long-lived library cache for all future playbacks.

It is best understood as:

- a **progressive playback transport cache**
- backed by Media3 `SimpleCache`
- wrapped around the current playback upstream
- warmed opportunistically after first frame

### What is architecturally important

The cache sits **above** the selected progressive upstream:

- plain single-connection `OkHttpDataSource`
- or Nexio's `ParallelRangeDataSource`

That means VOD cache can accelerate either:

- normal progressive playback
- or parallel-range progressive playback

without changing the player or extractor stack.

## Ownership: Nexio vs Media3

## Nexio-owned implementation

The VOD cache policy and lifecycle live primarily in:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`

These files implement:

- the user-visible setting
- the configured cache cap
- runtime propagation into the media-source factory
- async cache initialization
- live cap handling
- warm-ahead policy
- log/telemetry state
- final cleanup

## Media3-owned integration seams

Media3 provides the reusable cache primitives:

- `SimpleCache`
- `LeastRecentlyUsedCacheEvictor`
- `CacheDataSource`
- `CacheDataSink`
- `CacheWriter`
- `StandaloneDatabaseProvider`

So the VOD cache feature is best described as:

- **Nexio-owned cache policy and orchestration built on top of Media3 cache primitives**

## Is this a standard Media3 feature?

Partly.

Standard Media3 provides:

- disk cache storage
- cache-aware data sources
- cache writing support
- LRU eviction

What Nexio adds is:

- choosing only certain playback paths for caching
- dynamic cache-cap resolution based on free disk
- startup warm initialization
- first-frame-gated warm-ahead
- resolved-URL-aware cache-key tracking
- session teardown behavior that clears the cache

So the cache is **not** just "Media3 cache turned on." It is a Nexio-managed runtime cache strategy.

## Module and Dependency View

## App module ownership

The implementation is in the `app` module. There is no separate cache module.

Relevant dependencies in:

- `app/build.gradle.kts`

Important libraries:

- `androidx.media3:media3-datasource`
- `androidx.media3:media3-datasource-okhttp`
- `androidx.media3:media3-exoplayer`
- `okhttp`

## Local Media3 fork note

The project can optionally substitute Media3 modules from the local `media/` source tree through:

- `settings.gradle.kts`

That may affect the exact implementation behind Media3 cache classes, but the VOD cache policy itself still lives in app code in `PlayerMediaSourceFactory.kt`.

## Settings and Configuration

## Persistent settings

VOD cache settings live in:

- `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`

Relevant fields:

- `vodCacheSizeMode`
- `vodCacheSizeMb`

Defaults:

- mode default: `ON`
- size default: `500 MB`
- minimum size: `100 MB`
- maximum configured size: `65536 MB`

Stored DataStore keys:

- `vod_cache_size_mode`
- `vod_cache_size_mb`

## Settings UI

The user-facing UI lives in:

- `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`

The UI presents:

- a toggle to enable/disable VOD cache
- a slider for configured cache size
- a dynamic info line showing allowed size range and disk headroom

The copy explicitly describes the feature as:

- caching **progressive VOD**
- with **LRU eviction**

Relevant strings live in:

- `app/src/main/res/values/strings.xml`

## View-model behavior

The settings write path lives in:

- `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`

This layer mostly forwards:

- `setVodCacheSizeMode(...)`
- `setVodCacheSizeMb(...)`

Unlike the parallel-connections path, there is no extra memory-budget model here. Capacity control is mostly disk-based rather than heap-based.

## Runtime Propagation

At playback initialization, settings are copied into the media-source factory in:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`

Specifically:

- `mediaSourceFactory.vodCacheSizeMode = playerSettings.vodCacheSizeMode`
- `mediaSourceFactory.vodCacheSizeMb = playerSettings.vodCacheSizeMb`

Those fields are also kept up to date while the player is alive through:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`

So the cache policy is not fixed only once at player creation; live settings changes propagate to the factory state as well.

## High-Level Runtime Flow

## 1. Cache warmup starts early

`PlayerRuntimeController` calls:

- `mediaSourceFactory.warmupVodCacheAsync()`

from its `init` block.

Relevant file:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`

This does **not** start prefetching media data. It starts asynchronous cache initialization so the `SimpleCache` instance can be ready by the time playback needs it.

## 2. Media-source assembly decides whether cache is eligible

The main decision point is:

- `PlayerMediaSourceFactory.createMediaSource(...)`

VOD cache is only considered for:

- progressive playback
- HTTP-backed upstreams

It is bypassed for:

- HLS
- DASH
- non-HTTP/local upstreams
- paths where `shouldUseVodCache(url)` is false

`shouldUseVodCache(url)` currently resolves to:

- `usesHttpUpstream(url)`

So the cache is targeted at progressive HTTP VOD playback.

## 3. Upstream is selected first

Before caching is applied, `PlayerMediaSourceFactory` first chooses the progressive upstream factory:

- plain `OkHttpDataSource.Factory`
- or `ParallelRangeDataSource.Factory`

This matters because cache wraps **whatever progressive upstream was selected**.

That gives the layering model:

- player
- media source
- cache-aware data source
- chosen progressive upstream
- network

## 4. Cache wraps the progressive upstream

If eligible and available, `PlayerMediaSourceFactory` wraps the upstream in:

- `CacheDataSource.Factory`

using:

- `SimpleCache`
- `CacheDataSink.Factory`

This means the player reads through Media3's cache-aware data source while still using the same upstream transport under cache misses.

## Eligibility Rules

## Positive eligibility

The cache is considered when all of the following are true:

- cache feature is globally enabled in code
- user mode is `VodCacheSizeMode.ON`
- stream is not HLS
- stream is not DASH
- upstream is HTTP-based

## Negative eligibility

The cache is bypassed when:

- the user disables it
- the stream is segmented streaming
- cache initialization is not ready and no synchronous fallback succeeds
- cache has been disabled after an initialization or datasource failure

## Important nuance

The cache can be **temporarily not ready** and the player will still continue with plain network playback.

This is intentional. The player does not hard-fail just because cache is unavailable.

## Capacity and Disk Headroom Model

## Configured cap

The user-facing requested cap is:

- `vodCacheSizeMb`

but Nexio does not blindly trust that value at runtime.

## Runtime cap resolution

`PlayerMediaSourceFactory.resolveVodCacheMaxBytes(...)` clamps the requested cap against:

- app-level configured min/max
- current free disk
- a reserved free-space headroom

Key runtime behavior:

- if enough disk is available, Nexio reserves `1 GB` headroom
- otherwise it falls back to roughly `80%` of available space

Relevant constants:

- `VOD_CACHE_FREE_SPACE_RESERVE_BYTES = 1 GB`
- `MIN_RUNTIME_VOD_CACHE_BYTES = 1 MB`

This is a runtime safety model to avoid exhausting device storage.

## UI-side disk range

The UI computes a manual max size using free disk as well, keeping:

- roughly `1024 MB` reserved headroom

This makes the visible slider range broadly consistent with runtime safety behavior.

## Cache Object Lifecycle

## Shared singleton cache

The cache object is process-local and shared through static state in `PlayerMediaSourceFactory`:

- `sharedSimpleCache`
- `cacheDatabaseProvider`
- `configuredVodCacheMaxBytes`

That means there is one active `SimpleCache` instance for the process, not one per playback item.

## Backing directory

The cache lives under:

- `context.cacheDir / player_vod_cache`

So it uses app cache storage, not external storage or a custom data directory.

## Initialization path

Async initialization goes through:

- `startVodCacheInitialization(...)`

Behavior:

- return immediately if cache is disabled
- do nothing if the desired cache already exists
- avoid duplicate init with `cacheInitStarted`
- initialize on a dedicated single-thread executor

If initialization succeeds:

- `sharedSimpleCache` becomes ready

If it fails:

- `isVodCacheDisabled = true`

This is a fail-closed design: repeated failures disable the feature rather than repeatedly destabilizing playback.

## Synchronous attach fallback

When playback needs cache immediately, `createMediaSource(...)` may still fall back to a synchronous:

- `getOrCreateSimpleCache(...)`

if async warmup has not completed yet.

That ensures first playback can still attach cache if initialization has not yet finished in the background.

## Reconfiguration Behavior

## LRU eviction

The cache uses:

- `LeastRecentlyUsedCacheEvictor(maxBytes)`

So within one live cache instance, eviction is LRU-based.

## Cap changes while cache exists

Live cap behavior is intentionally conservative.

If a cache instance already exists and the requested cap changes:

- small or unsafe changes are deferred
- some large increases may be applied by recreating the cache live

Key behavior:

- live increase only considered if delta is at least `64 MB`
- if live reconfigure is not allowed, Nexio logs deferral until restart
- if live reconfigure is attempted and fails, it tries to restore the previous cache
- if restore fails, VOD cache is disabled

This avoids unsafe in-use cache mutation while still allowing some growth scenarios.

## Notably asymmetric behavior

The current code is much more focused on:

- cap increases

than on:

- cap decreases

That makes sense operationally because shrinking an in-use cache is harder to do safely without disruption.

## Cache-Aware Read Path

## Construction

`buildVodCacheDataSourceFactory(...)` builds a `CacheDataSource.Factory` with:

- `setCache(cache)`
- `setCacheWriteDataSinkFactory(dataSinkFactory)`
- `setUpstreamDataSourceFactory(upstreamFactory)`

Important flags:

- always uses `CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR`
- optionally adds `CacheDataSource.FLAG_BLOCK_ON_CACHE` for warm-ahead writes

## Meaning of those choices

`FLAG_IGNORE_CACHE_ON_ERROR` means:

- playback should keep going via network even if cache has problems

`FLAG_BLOCK_ON_CACHE` in warm-ahead means:

- prefetch work should coordinate correctly against cache state rather than racing past active writes

## Warm-Ahead Design

## Trigger

Warm-ahead does **not** start immediately when playback begins.

It is unlocked after first frame:

- `PlayerRuntimeControllerInitialization.kt` calls `mediaSourceFactory.notifyPlaybackFirstFrameRendered()`
- `notifyPlaybackFirstFrameRendered()` sets `parallelStartupPrefetchUnlocked = true`
- then `startVodWarmAheadIfEligible()` may run

This is important because it avoids letting prefetch compete during the most fragile startup phase.

## Preconditions

Warm-ahead requires:

- cache feature enabled
- cache mode on
- cache not globally disabled
- current playback path eligible for warm-ahead
- current playback actively using cache
- a current progressive upstream factory
- a current cache instance

If any of those are missing, warm-ahead does nothing.

## Outer execution model

Warm-ahead uses:

- one dedicated single-thread executor

and one background future:

- `prefetchFuture`

This outer scheduler is serial, but the upstream it uses may still be parallel internally if the progressive upstream factory is `ParallelRangeDataSource.Factory`.

## What the warm-ahead loop does

`runWarmAheadLoop(...)` repeatedly:

1. determines the live cache key from resolved URL or current stream URL
2. computes the contiguous cached prefix
3. advances the active-read frontier if cache is already ahead
4. finds the next uncached hole
5. skips work that is too close to active playback
6. selects a fixed write block length
7. runs `CacheWriter` for that future block

Important constants:

- prefetch block size: `16 MB`
- active guard bytes: `8 MB`
- idle sleep: `250 ms`
- rebase sleep: `100 ms`

This is a simple and pragmatic hole-filling strategy rather than a sophisticated scheduler.

## Active frontier protection

Warm-ahead tries not to trample the player frontier by computing:

- `activeReadBytePosition`

and keeping a guard window ahead of it.

This is one of the key Nexio-owned behaviors that makes warm-ahead usable during active playback rather than just blindly writing ahead from byte zero.

## URL and Cache-Key Tracking

## Why resolved URL matters

Progressive media URLs may redirect to CDN-specific final URLs.

The cache logic tracks:

- original stream URL
- resolved playback URL

through:

- `currentVodCacheUrl`
- `currentVodCacheResolvedUrl`

This matters because cache spans are keyed by URL string, and warm-ahead needs to find the right key even if the actual bytes came from a redirected CDN URL.

## Read-position tracking

The progressive transport reports active read advancement through:

- `onReadPositionAdvanced`

That is fed into:

- `activeReadBytePosition`

and used by warm-ahead and telemetry.

If the progressive upstream is the parallel transport, this gives the cache layer visibility into actual playback-frontier movement.

## Telemetry and Operator Visibility

## Human-readable cache state

`PlayerMediaSourceFactory.getVodCacheLogState(...)` returns a compact runtime string with:

- mode
- total used cache MB
- cap MB
- cached bytes for the current stream
- whether cache is active for the current stream

This is then reused in playback buffer logging through:

- `PlayerRuntimeControllerPlaybackEvents.kt`

So buffer logs can include VOD cache state alongside heap and buffering information.

## What is already visible

Current logs can expose:

- whether cache is on/off/disabled
- total cache usage
- current-stream cached usage
- whether cache is actively attached for the stream

This is useful operationally even without a full analytics pipeline.

## Cleanup and Lifetime Semantics

## Important design fact

Although this uses a disk-backed `SimpleCache`, the current implementation clears cache contents on playback stop.

That happens through:

- `PlayerRuntimeController.stopAndRelease()`
- which calls `mediaSourceFactory.clearVodCache()`
- which calls `clearVodCacheInternal(...)`

`clearVodCacheInternal(...)`:

- releases `sharedSimpleCache`
- resets cache static state
- deletes the `player_vod_cache` directory recursively

It also logs:

- `Cleared VOD cache contents on playback stop`

## Practical implication

This means the current VOD cache is:

- **disk-backed**
- but **session-scoped in practice**

It is not intended to accumulate across many independent playback sessions the way a library-wide cache would.

That is one of the most important things for an engineer to understand when reasoning about the feature.

## End-to-End Path Map

### Settings path

- `PlayerSettingsDataStore.kt`
- `PlaybackSettingsViewModel.kt`
- `PlaybackBufferNetworkSettings.kt`
- `strings.xml`

### Playback runtime path

- `PlayerRuntimeController.kt`
- `PlayerRuntimeControllerInitialization.kt`
- `PlayerRuntimeControllerObservers.kt`
- `PlayerMediaSourceFactory.kt`

### Logging / visibility path

- `PlayerRuntimeControllerPlaybackEvents.kt`
- `PlayerMediaSourceFactory.kt`

## Interaction with Parallel Connections

The cache sits above the selected progressive transport.

So if progressive playback uses:

- `ParallelRangeDataSource.Factory`

then cache misses and warm-ahead writes may be satisfied by the parallel-range transport underneath.

This means the two systems are not independent:

- parallel connections shape network behavior
- VOD cache shapes reuse and speculative disk fill

The integration seam is still clean, but their runtime effects are coupled.

## Test Coverage

I did not find dedicated VOD cache tests in the current app test tree.

That does **not** mean the feature is untested at runtime, but it does mean there is no obvious focused unit/integration test coverage for:

- cache eligibility
- initialization fallback
- warm-ahead hole filling
- live cap increase handling
- cleanup semantics

For this feature, that is a real coverage gap.

## Practical Conclusions

### Primary conclusion

The VOD cache architecture is sound:

- Nexio uses standard Media3 cache primitives
- but owns the policy, lifecycle, and warm-ahead behavior in app code

### Secondary conclusion

The cache is specifically aimed at:

- progressive HTTP playback

not segmented streaming or offline library caching.

### Third conclusion

The current implementation is best thought of as:

- a **session-scoped progressive transport cache**

because it is actively cleared on playback stop.

### Fourth conclusion

The most important coupling for future engineers to remember is:

- VOD cache wraps the currently selected progressive upstream
- so cache behavior must be analyzed together with parallel-connections behavior when both are enabled

## Review Checklist

An engineer reviewing this path should verify:

- cache is still only applied to intended progressive HTTP paths
- first playback still falls back cleanly when cache is not ready
- cache failures still degrade to network instead of breaking playback
- warm-ahead remains gated until after first frame
- resolved-URL cache-key logic remains consistent across redirects
- live cap changes remain safe while cache is in use
- cleanup behavior still matches the intended session-lifetime semantics
- any future persistence changes explicitly revisit the current "clear on stop" behavior
