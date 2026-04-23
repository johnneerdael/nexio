# VOD Cache Playback Addendum

## Scope

This addendum evaluates the follow-up feedback against Nexio's current VOD cache implementation and turns the agreed conclusions into future-facing engineering guidance.

It should be read alongside:

- `docs/vod-cache-playback-lld.md`
- `docs/parallel-connections-playback-lld.md`
- `docs/parallel-connections-playback-addendum.md`

The headline conclusion is:

- the **VOD cache seam is correct**
- the **current cache lifecycle, sizing model, and warm-ahead policy should evolve**

Nexio is still using the right integration surface:

- `SimpleCache`
- `CacheDataSource.Factory`
- cache wrapped above the chosen progressive upstream

That matches Media3's intended on-the-fly disk caching model.

References:

- Media3 network stacks and disk caching: <https://developer.android.com/media/media3/exoplayer/network-stacks>
- `SimpleCache` API reference: <https://developer.android.com/reference/kotlin/androidx/media3/datasource/cache/SimpleCache>
- `CacheKeyFactory` API reference: <https://developer.android.com/reference/androidx/media3/datasource/cache/CacheKeyFactory>
- `CacheDataSource.EventListener` API reference: <https://developer.android.com/reference/androidx/media3/datasource/cache/CacheDataSource.EventListener>
- Media3 troubleshooting: <https://developer.android.com/media/media3/exoplayer/troubleshooting>

## Consensus Summary

The agreed position is:

- keep VOD cache
- keep the current Media3 cache seam
- stop treating cache as a disposable session scratchpad
- make VOD cache a disk-backed rolling playback shock absorber
- make warm-ahead subordinate to active playback
- move from byte-block thinking toward time-horizon thinking
- add stable cache keys before relying on persistent reuse

The main caveat is that some of this is **future state**, not current behavior. Today the cache is disk-backed, but it is still cleared on playback stop, so it does not yet function as a persistent rolling horizon across sessions.

## Findings Assessment

## 1. The cache seam is correct

### Verdict

- **Confirmed**

### Why this is confirmed in code

The VOD cache is built around the right Media3 seam:

- `SimpleCache`
- `LeastRecentlyUsedCacheEvictor(maxBytes)`
- `CacheDataSource.Factory`
- `CacheDataSink.Factory`
- `CacheWriter`

The cache is applied above the selected progressive upstream in:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`

That means Nexio is not re-implementing Media3 cache primitives. It is using standard Media3 cache infrastructure and supplying custom policy around:

- eligibility
- lifecycle
- sizing
- warm-ahead
- logging

### Directional guideline

Keep the current seam.

Future rule:

- **Do not move VOD cache policy into Media3 internals or the local Media3 fork unless a primitive is missing.**

## 2. The current cache is still a disposable session scratchpad

### Verdict

- **Confirmed**

### Why this is confirmed in code

Current shutdown behavior clears the whole cache:

- `PlayerRuntimeController.stopAndRelease()` calls `mediaSourceFactory.clearVodCache()`
- `clearVodCache()` calls `clearVodCacheInternal(...)`
- `clearVodCacheInternal(...)` releases `sharedSimpleCache`
- then recursively deletes the `player_vod_cache` directory

Relevant files:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`

This means the implementation is:

- disk-backed
- but session-scoped in practice

That gives up one of the biggest reasons to keep disk cache at all:

- reusing already-fetched bytes across restart/resume/seek/replay flows

### External API alignment

The Media3 `SimpleCache` API explicitly documents:

- only one live `SimpleCache` instance is allowed per directory at a time
- full deletion should use `SimpleCache.delete(...)` rather than raw directory deletion

### Directional guideline

This is the single biggest cache-side design correction.

Future rules:

- **Do not clear the whole VOD cache on every playback stop.**
- **Let LRU eviction handle normal cleanup.**
- **Add an explicit user-facing “clear VOD cache” action instead of implicit session wipe.**
- **Use full cache deletion only for corruption/reset flows.**
- **If item-level cleanup is needed, remove that item's resource instead of wiping the directory.**

Preferred API-level future hooks:

- full delete: `SimpleCache.delete(...)`
- item-level cleanup: `removeResource(key)`

Target behavior:

- persistent cache across sessions
- global LRU cleanup
- explicit/manual reset path

## 3. The current warm-ahead policy is too byte-oriented

### Verdict

- **Confirmed directionally**

### Why this is true in code

The current warm-ahead loop is built around fixed byte constants:

- `PREFETCH_BLOCK_BYTES = 16 MB`
- `PREFETCH_ACTIVE_GUARD_BYTES = 8 MB`

Relevant file:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`

That works as a simple implementation, but it is mismatched to the actual problem on giant remuxes:

- playback risk is measured in **seconds of future media runway**
- not in raw block bytes detached from bitrate

For very high-bitrate assets, `8-16 MB` may correspond to well under one second of media, which is too small to act as a serious jitter absorber.

### Directional guideline

Reframe the cache policy around **time horizon** rather than fixed byte blocks.

Future rules:

- **The cache should target contiguous cached-ahead seconds, not just generic byte holes.**
- **Warm-ahead should maintain low-water and high-water runway thresholds for the current item.**
- **The player frontier and estimated media byte rate should be first-class inputs to cache policy.**

Recommended future metric:

- `cachedAheadSeconds = contiguousCachedAheadBytes / estimatedMediaBytesPerSecond`

Recommended starting policy:

- startup / stabilizing: `60-120` seconds target
- giant remux steady playback: `300+` seconds when disk budget allows
- seek / rebuffer: reset urgency near the new frontier and suspend far-ahead fill

## 4. The current default cache sizes are too small for giant remux goals

### Verdict

- **Directionally true**

### Why this matters

Current defaults from `PlayerSettingsDataStore.kt`:

- default VOD cache size: `500 MB`
- minimum size: `100 MB`

Those values are reasonable for a generic progressive cache, but they are small if the explicit design target is:

- very large high-bitrate progressive remux playback

For giant files, a useful disk-backed horizon quickly moves into multi-gigabyte territory. That does not mean the app should immediately fill that much space. It means the allowed ceiling must be large enough for a meaningful rolling runway.

### Directional guideline

The current defaults should stop being treated as the practical target for giant-file mode.

Future rules:

- **Add an Auto/Recommended policy that scales higher when free disk permits.**
- **Treat 500 MB as a compatibility baseline, not as the ideal giant-remux recommendation.**
- **For large-file mode, make multi-gigabyte horizons a first-class supported path.**

Recommended target tiers:

- baseline generic mode: current smaller caps remain acceptable
- large-file mode: move recommended ceilings into roughly `8-16 GB`
- optional advanced mode: allow larger ceilings when disk headroom is abundant

## 5. Warm-ahead must be subordinate to playback, not just delayed until first frame

### Verdict

- **Confirmed**

### Why this is confirmed in code

Current behavior already includes a good first gate:

- warm-ahead is not unlocked until first frame

But after that, warm-ahead can still compete with playback because:

- it reuses `currentProgressiveUpstreamFactory`
- that upstream may be `ParallelRangeDataSource.Factory`
- it shares the same `OkHttpClient`
- playback and warm-ahead therefore share per-host request budget and transport resources

Relevant file:

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`

This means first-frame gating is necessary, but not sufficient.

### Directional guideline

Warm-ahead should become a revocable, reduced-budget lane.

Future rules:

- **Warm-ahead should require healthy playback conditions, not just “first frame rendered.”**
- **Warm-ahead should pause on seek, rebuffer, weak frontier progress, or weak player buffer.**
- **Warm-ahead should never consume the last urgent transport slot.**
- **Warm-ahead should start with one reduced effective worker policy.**

Recommended first policy:

- first-frame gate remains
- plus:
  - no recent rebuffer
  - no recent seek
  - stable frontier progress
  - healthy player buffer
  - spare transport capacity

## 6. Stable cache keys are missing for persistent cache reuse

### Verdict

- **Confirmed**

### Why this is confirmed in code

Current cache identity is effectively URL-driven. Nexio tracks:

- original URL
- resolved redirected URL

through:

- `currentVodCacheUrl`
- `currentVodCacheResolvedUrl`

That is useful inside a session, but there is no current use of:

- `CacheKeyFactory`

and no stable asset identity layer for cache reuse across URL rotations.

That becomes a real problem as soon as cache is made persistent, because debrid/CDN URLs can rotate or expire.

### Directional guideline

Stable cache keys must land before persistent cache can deliver its full value.

Future rules:

- **Cache keys should be asset-stable, not URL-token-stable.**
- **Signed URLs, redirect hosts, ephemeral query params, and byte-range parameters must not define cache identity.**
- **Persistent reuse should be keyed on media identity, not transport address.**

Recommended cache-key inputs:

- provider identity
- content/torrent hash
- file index or file path inside the torrent
- file size
- stable media identifier where available

Preferred seam:

- `CacheKeyFactory`

## 7. Very small effective cache capacities should not trigger speculative warm-ahead

### Verdict

- **Directionally true**

### Why this matters

Runtime cache capacity can be clamped by free disk. The current code even supports a runtime floor low enough that speculative warm-ahead may become churn rather than protection.

For giant files, an extremely small resolved capacity cannot create meaningful future runway. In that state, aggressive speculative writes can add:

- disk churn
- eviction churn
- transport contention

without buying useful stability.

### Directional guideline

Warm-ahead should require a minimum meaningful effective horizon.

Future rules:

- **If resolved cache capacity is too small to provide meaningful ahead-of-frontier time, disable speculative warm-ahead.**
- **Fallback should be read-through caching only when horizon capacity is below the useful threshold.**

Recommended minimum threshold shape:

- `max(512 MB, 30-60 seconds of estimated media bitrate)`

The exact constants can be tuned later, but the rule itself should exist.

## 8. Cache telemetry should use Media3 cache APIs more directly

### Verdict

- **Confirmed opportunity**

### Why this is confirmed in code

Current visibility is mostly ad hoc summary logging:

- whether cache is on/off/disabled
- total used cache size
- current-stream cached bytes
- whether cache is active for the current stream

That is useful, but the implementation does not currently use:

- `CacheDataSource.EventListener`
- `CacheKeyFactory`
- `SimpleCache.addListener(...)`
- cache listener-style span observation for the VOD cache path

### Directional guideline

Use Media3's cache telemetry seams directly.

Future rules:

- **Cache hit/miss accounting should come from cache-aware listeners, not only inferred logs.**
- **The main cache metric should be contiguous cached-ahead runway from the current frontier.**
- **Cache bypass reasons should be observable and reportable.**

Recommended telemetry additions:

- cache hit bytes
- cache miss bytes
- contiguous cached-ahead bytes
- contiguous cached-ahead seconds
- warm-ahead fill rate
- cache ignored / bypass reasons
- rebuffer-with-runway versus rebuffer-without-runway classification

## Practical Priority Order

If the goal is the highest cache-side return with the least architectural churn, the consensus order is:

1. stop clearing the whole cache on playback stop
2. switch warm-ahead from fixed MB blocks to a time-based rolling horizon
3. raise the effective default or recommended cache size for giant progressive titles
4. make warm-ahead use a reduced, revocable transport budget
5. add stable cache keys

## 9. Decode limits remain a separate cause of stutter

### Verdict

- **Confirmed**

### Why this matters

Even an ideal disk cache and transport scheduler cannot guarantee smooth playback if:

- device decode capability is insufficient
- sustained decode complexity exceeds what the hardware can handle

Media3 troubleshooting documentation explicitly treats decode capability as a separate failure domain from transport/caching.

### Directional guideline

Keep cache optimization and decode certification separate.

Future rules:

- **Do not attribute every stutter to network or cache policy.**
- **Runtime diagnostics should distinguish decode pressure from cache starvation and transport stalls.**

## Engineering Guidelines

## Keep

- app-owned cache policy
- `SimpleCache` singleton-per-directory model
- dedicated cache directory
- progressive HTTP-only eligibility
- fallback to network playback when cache is unavailable
- `FLAG_IGNORE_CACHE_ON_ERROR`
- first-frame gating as the baseline warm-ahead gate

## Change

- per-stop full cache wipe
- URL-shaped cache identity
- fixed byte-block warm-ahead policy
- treating small default cache sizes as sufficient for giant-file mode
- warm-ahead that runs without broader playback health checks
- cache telemetry that cannot explain real cache effectiveness

## Avoid

- moving cache policy into Media3 internals
- persisting cache without first stabilizing cache keys
- treating “bytes written to disk” as equivalent to “useful playback runway”
- allowing warm-ahead to run when effective horizon is too small to matter

## Detailed Future State Guide

## Future State Goals

The target VOD cache should behave as:

- a persistent, disk-backed, singleton cache
- with global LRU cleanup
- keyed by stable asset identity
- maintaining a rolling ahead-of-frontier horizon
- subordinate to playback urgency
- measurable in time runway, not just bytes

At the system level, the intended stack is:

- L1 RAM: player/decode buffer plus small transport windows
- L2 transport: urgent and speculative range fetching
- L3 disk: persistent VOD horizon with LRU cap

## Stage 1: Instrument before changing behavior

Add enough telemetry to explain whether cache is helping.

Use:

- existing player analytics path
- cache-aware telemetry
- transport telemetry from the parallel addendum direction

Minimum cache-side metrics:

- contiguous cached-ahead bytes
- contiguous cached-ahead seconds
- cache hit bytes
- cache miss bytes
- cache bypass/ignore reasons
- warm-ahead fill rate
- peak live cache working set for current item

Exit gate:

- every rebuffer can be classified as decode pressure, frontier stall, cache starvation, or scheduler contention

## Stage 2: Stop full cache wipe on stop

This is the highest-value lifecycle correction.

Change:

- remove implicit full delete on playback stop
- preserve cache across playback sessions

Add:

- explicit user-facing clear-cache action
- corruption/reset-only full wipe path
- optional item-level removal path using cache resource removal

Also align deletion behavior with Media3 guidance:

- use `SimpleCache.delete(...)` for full deletion paths instead of raw directory deletion

Exit gate:

- stopping and restarting the same item can reuse previously cached bytes

## Stage 3: Introduce stable cache keys

Before persistent reuse is trusted, cache identity must stop depending on ephemeral URLs.

Change:

- add `CacheKeyFactory`
- normalize identity around the media asset

Recommended stable key inputs:

- provider/source
- torrent/content hash
- file path/index
- file size
- stable media id if available

Exit gate:

- CDN/debrid URL rotation no longer destroys cache reuse for the same underlying asset

## Stage 4: Move from byte-block fill to rolling time horizon

Replace fixed `16 MB` warm-ahead blocks as the main policy input with:

- target contiguous cached-ahead seconds

Use:

- current playback frontier
- estimated media bytes per second
- current buffer health
- spare disk and transport capacity

Policy model:

- low-water mark: trigger fill when ahead horizon falls below target
- high-water mark: stop fill when enough ahead runway already exists

Exit gate:

- cache behavior is explainable in seconds of runway, not just in fixed block writes

## Stage 5: Make warm-ahead subordinate and revocable

Keep first-frame gating, then add stronger runtime gates.

Warm-ahead should require:

- healthy buffer
- no recent seek
- no recent rebuffer
- stable frontier progress
- enough spare transport capacity

Warm-ahead should use:

- max `1` effective worker to start
- lower-priority admission
- immediate pause on seek/rebuffer

Exit gate:

- enabling warm-ahead does not increase rebuffer frequency on matched playback scenarios

## Stage 6: Add giant-file cache policy tiers

Stop treating current defaults as the only meaningful policy.

Introduce:

- Auto mode
- standard mode
- large-file mode

Suggested policy shape:

- standard mode: smaller conservative cache caps
- large-file mode: recommended ceilings in roughly `8-16 GB`
- advanced mode: higher limits when disk permits

The cache does not need to fill the whole cap. The cap exists so the rolling horizon can become useful.

Exit gate:

- giant remux playback can retain several minutes of useful disk runway when the device has space

## Stage 7: Add a minimum effective horizon rule

If resolved cache capacity is too small to help, speculative warm-ahead should not run.

Rule shape:

- below a minimum meaningful horizon, use read-through caching only

Recommended starting threshold:

- `max(512 MB, 30-60 seconds of estimated media bitrate)`

Exit gate:

- small-capacity sessions avoid speculative cache churn with negligible playback value

## Stage 8: Rebuild telemetry and certification around cache usefulness

Treat cache as successful when it improves playback outcomes, not just when it writes bytes.

Success metrics:

- fewer rebuffers
- shorter seek recovery
- better replay/back-seek reuse
- less transport instability when runway is healthy

For each playback, report:

- player-side outcome
- transport-side root cause
- cache-side runway effectiveness

## Recommended End State

The clean target architecture is:

- persistent singleton `SimpleCache`
- stable cache keys
- read-through plus rolling warm-ahead horizon
- global LRU cap
- per-item adaptive horizon target
- warm-ahead subordinate to active playback
- cache effectiveness measured in ahead-of-frontier time

This is the right VOD-cache design to pair with parallel connections for giant progressive remuxes on Android TV.

## Final Position

The VOD cache architecture is already on the right seam.

The next improvement is not to replace Media3 cache primitives. It is to make the policy behind them more useful and less destructive.

The most important cache-side change is:

- **stop wiping the whole cache on playback stop**

The second most important cache-side change is:

- **switch warm-ahead from fixed byte blocks to a rolling time-based horizon**

The third major prerequisite is:

- **add stable cache keys before relying on persistent reuse**

If those three changes land, VOD cache stops being just a disk-backed scratch path and becomes a real persistent playback shock absorber that complements the future frontier-safe parallel transport.
