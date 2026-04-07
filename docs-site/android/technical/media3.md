# Media3

Nexio uses Media3 as the playback control plane, then applies optional playback-specific integrations on top. For contributors and testers, behavior is therefore the combination of:

- Media3 baseline behavior (track selection, renderer wiring, default transport behavior)
- Nexio extensions for network/data-source strategy and transport adaptation

The most important distinction for this doc is: **Media3-native adaptive streaming paths stay baseline for HLS/DASH**, while PRDS, VOD cache, and warm-ahead are intentionally gated to progressive HTTP sources.

## What Media3 does in Nexio

Media3 is responsible for:

- building the player instance and track selector
- choosing between platform decoders and extension decoders
- handling HLS, DASH, progressive files, and Blu-ray style sources
- driving subtitle renderers, audio sink selection, and playback state
- surfacing errors that Nexio can use for automatic retry and fallback decisions

Nexio currently extends that baseline in these places:

1. **Extractor hooks for Dolby Vision**
    Media3 extractors can be hooked so Nexio can inspect or rewrite Dolby Vision signaling before decode begins.

2. **Custom renderer and decoder selection**
   Nexio can prefer FFmpeg renderers for cases such as VC-1 software decoding, AV1 fallback, or experimental Dolby Vision tone-mapping paths.

3. **Custom network input path**
    Progressive HTTP playback can use both a VOD cache and an optional multi-connection range downloader.

## Playback pipeline at a glance

### Source routing (implementation-verified)

In `PlayerMediaSourceFactory.createMediaSource`, Nexio normalizes the URL and classifies streaming type:

```kotlin
val lowerPath = extractPath(url).lowercase(Locale.US)

val isHls = lowerPath.contains(".m3u8") ||
    lowerPath.contains("/playlist") ||
    lowerPath.contains("/hls") ||
    lowerPath.contains("m3u8")

val isDash = lowerPath.contains(".mpd") ||
    lowerPath.contains("/dash")
```

Then it chooses factories in this order:

1. Blu-ray local/remote handling (if detected) happens before adaptive checks.
2. If `customExtractorsFactory != null` or `customSubtitleParserFactory != null`, it uses a `DefaultMediaSourceFactory` with those hooks, which intentionally bypasses specialized HLS/DASH branch selection.
3. If not forced by custom hooks:

   - HLS: `HlsMediaSource.Factory(okHttpFactory).setAllowChunklessPreparation(true)`
   - DASH: `DashMediaSource.Factory(okHttpFactory)`
   - Else: progressive path using `DefaultMediaSourceFactory(progressiveFactory)`

#### Pipeline summary

- HTTP progressive source:
  `URL -> OkHttp/DefaultDataSource -> optional ParallelRangeDataSource -> optional VOD cache -> Media3 extractors -> track selector -> renderers`
- HLS/DASH stream:
  `URL -> HlsMediaSource / DashMediaSource -> Media3 extractors -> track selector -> renderers`

Media3-adaptive formats should therefore be treated as baseline for transport optimization purposes.

For Blu-ray-style content, Nexio also has special handling:

- local Blu-ray folders can be resolved through playlist parsing
- remote HTTP directory listings can be probed for `BDMV/PLAYLIST` and `BDMV/STREAM`
- BDAV `.m2ts` playback uses TS extractor flags that enable HDMV DTS audio support

## VOD cache and warm-ahead prefetch

Nexio includes a disk-backed VOD cache for progressive HTTP and HTTPS playback. This is intended to reduce repeated network reads, smooth seeks, and support background warm-ahead.

Key behaviors from the current implementation:

- VOD cache is opt-in and constrained by settings + runtime checks:
  - `ENABLE_VOD_CACHE == true`
  - `vodCacheSizeMode == VodCacheSizeMode.ON`
  - `!isHls && !isDash`
  - `shouldUseVodCache(url)` (HTTP(S) transport only)
- Cache directory is `player_vod_cache_v2` (`VOD_CACHE_DIR`) under app cache.
- Maximum cache cap is clamped at runtime to preserve disk headroom (`VOD_CACHE_FREE_SPACE_RESERVE_BYTES = 1 GiB`).
- `getVodCacheLogState()` emits the canonical state string:

  - `vod=off` (feature disabled by build flag or mode)
  - `vod=disabled` (runtime recovery disabled after failure)
  - `vod=on total=<used>/<cap>MB stream=<streamMB> active=<true|false>`
- `currentVodCacheActive` and `currentProgressiveIsEligibleForWarmAhead` are tracked per-source.

### Warm-ahead prefetch internals

Warm-ahead is a **PRDS-aware background fill loop** and is only reachable when:

- source is progressive and cache is active
- `currentProgressiveIsEligibleForWarmAhead == true`
- `transportPolicy.warmAheadEnabled == true`
- playback has rendered at least one frame (via `notifyPlaybackFirstFrameRendered()`)

Important constants and guards:

- `PREFETCH_BLOCK_BYTES = 16 MiB`
- `PREFETCH_ACTIVE_GUARD_BYTES = 8 MiB`
- `REBUFFER_PREFETCH_PAUSE_MS = 10_000L`

Behavior:

- warm-ahead advances in contiguous-file holes, writing with a `CacheWriter`
- it never prefetches bytes before `activeReadBytePosition + PREFETCH_ACTIVE_GUARD_BYTES`
- it pauses speculative background prefetch after rebuffer events because `notifyRebuffer()` updates the rebuffer timestamp; `shouldAllowBackgroundPrefetch` stays false until elapsed time exceeds `REBUFFER_PREFETCH_PAUSE_MS`

This feature helps most with large VOD files and repeated starts or seeks. It is less relevant for short clips and does not replace true offline download support.

## Parallel downloading for progressive playback

Nexio can replace the normal upstream with `ParallelRangeDataSource` for progressive HTTP(S) when `useParallelConnections` is enabled.

Gating:

- only when `usesHttpUpstream(url)` is true
- only when `!isHls && !isDash`
- only when `useParallelConnections == true`

When enabled, `ParallelRangeDataSource` receives a transport policy provider and observation callback:

- Media3 still sees a normal stream
- the upstream fetch layer may open multiple HTTP range requests in parallel
- the number of connections/chunk size can be user-configured
- startup prefetch starts unlocked = `false` (`parallelStartupPrefetchUnlocked`), then flips to `true` on first rendered frame
- in REBUFFER situations, background chunk prefetch waits for a 10s recovery window before resuming

Trade-offs:

- it can improve startup and seek behavior on high-latency hosts
- it increases memory and network concurrency
- aggressive settings can hurt weaker devices or unstable servers

The settings UI uses a runtime memory budget to keep buffer size and parallel chunking within a bounded share of the app heap.

## Decoder and renderer selection

Media3 remains the component that picks a renderer, but Nexio adjusts the decision:

- user decoder priority maps to Media3 extension renderer modes
- some retries force FFmpeg preference for a single problematic stream
- VC-1 failures can trigger a software-decode retry path
- AV1 `dav1d` failures can trigger an FFmpeg fallback
- experimental DV5 software tone mapping also forces FFmpeg preference

This is why the same title may start on hardware decode, then retry with a different renderer after a failure.

## Dolby Vision in the Media3 layer

Media3 is where Nexio installs its Dolby Vision sample transformers. If a build and device support the feature, Nexio can:

- probe whether the native `libdovi` bridge is actually available
- install extractor hooks for Matroska, MP4, fragmented MP4, and TS/H.265
- rewrite Dolby Vision codec strings when compatibility remapping is active
- transform RPU payloads before decode
- tap RPU timing data for experimental DV5 hardware tone-mapping work

The important practical point is that Dolby Vision compatibility work in Nexio is not a single decoder flag. It is a coordinated path across Media3 extractors, the native bridge, and sometimes FFmpeg.

## Compatibility expectations

Media3 in Nexio is stable for normal playback, but some advanced paths are intentionally cautious:

- VOD cache, PRDS, and warm-ahead are progressive-only optimizations
- DV7 to DV8.1 conversion depends on build flags, native library availability, and successful hook installation
- DV5 tone mapping remains experimental and device-sensitive
- the custom Kodi-derived IEC sink is opt-in and separate from the default Media3 audio path

If a feature is described elsewhere as experimental, Media3 is usually the point where Nexio decides whether to activate it for the current stream.

## Transport state machine and runtime specialization

Nexio maintains a runtime transport policy controller that changes network behavior over playback phases.

`TransportPolicyController` exposes these states:

- `STARTUP`
- `SEEK`
- `REBUFFER`
- `STABILIZING`
- `STEADY`

Transition rules:

- initial state is `STARTUP`
- first frame -> `STABILIZING`
- seek command -> `STARTUP`
- rebuffer -> `REBUFFER`
- if buffered ahead > 5s while in `STARTUP` or `REBUFFER` -> `STABILIZING`
- if buffered ahead > 15s while in `STABILIZING` -> `STEADY`

Per-state policy behavior:

- `STARTUP` / `SEEK` / `REBUFFER`
  - `prefetchWorkers = 0`
  - `prefetchChunkBytes = 0`
  - `warmAheadEnabled = false`
  - `urgentChunkBytes = min(envelope.maxSafeUrgentChunkBytes, 2 MiB)`
  - `urgentWorkers = envelope.maxSafeUrgentWorkers`

- `STABILIZING`
  - `prefetchWorkers = max(1, envelope.maxSafePrefetchWorkers / 2)`
  - `prefetchChunkBytes = envelope.maxSafePrefetchChunkBytes`
  - `warmAheadEnabled = false`

- `STEADY`
  - `prefetchWorkers = envelope.maxSafePrefetchWorkers`
  - `urgentWorkers = max(1, envelope.maxSafeUrgentWorkers - 1)`
  - `urgentChunkBytes = envelope.maxSafeUrgentChunkBytes`
  - `prefetchChunkBytes = envelope.maxSafePrefetchChunkBytes`
  - `warmAheadEnabled = true`

### Runtime transport specialization lifecycle

Observed transport metadata is emitted from `SharedParallelTransportManager.emitTransportObservation` and includes:

- `hostScope`
- `transportClass` (`connection_close` or `keep_alive` from `Connection` header)
- `negotiatedProtocol`
- `connectionHeader`

`nextRuntimeTransportSpecializationTransition(...)` computes status:

- `BASELINE`
- `MISMATCH`
- `CONFIRMED`

It emits events only on transitions with `type`:

- `transport_specialization_confirmed`
- `transport_specialization_mismatch`
- `transport_specialization_revoked`

Each event is logged as:

- `RUNTIME_TRANSPORT <type> <detail>`

`detail` includes keys like:

- `streamServiceKey`
- `hintServiceKey`
- `hostScope`
- `transportClass`
- `allowUrgentChunkAbove8MiB`
- `connectionBudgetHint`
- `retryMode`
- `reason`

Observed `reason` values:

- `feature_disabled`
- `no_runtime_hints`
- `service_key_mismatch`
- `awaiting_runtime_observation`
- `confirmed`
- `confirmation_lost`

Specialization can enforce a higher `urgentChunkBytes` cap, `retryMode`, optional `connectionBudgetHint`, and specialized prefetch workers/chunk sizes.

When a specialization carries `connectionBudgetHint`, warm-ahead is additionally budget-capped (`warmAheadBudgetMax = 1`).

## Troubleshooting

- If HLS/DASH behavior differs from progressive behavior:
  - confirm source detection (`isHls` / `isDash`) and whether PRDS + cache path was in scope.
  - if either adaptive flag is true, warm-ahead and PRDS are skipped and `HlsMediaSource`/`DashMediaSource` are used.
- If transport tuning never reaches steady behavior:
  - inspect `transportPolicyController.state` via logs or debugger.
  - confirm `onFirstFrame()` is firing and buffer ahead crosses 5s then 15s thresholds.
- If warm-ahead is not running:
  - check `cachedVodCacheLogState` includes `vod=on`
  - check `currentProgressiveIsEligibleForWarmAhead` and `currentVodCacheActive`
  - confirm `transportPolicy.warmAheadEnabled=true`
  - confirm no recent `notifyRebuffer()` cooldown is blocking it
- If transport specialization seems stuck in baseline:
  - check for `RUNTIME_TRANSPORT ...` events and `reason` value.
  - confirm `streamServiceKey`, `hostScope`, and `transportClass` in observation match benchmark hints.
- If startup is fast but seeking remains network-bound, verify:
  - source is progressive HTTP(S)
  - VOD cache is enabled (`vod=on`)
  - `useParallelConnections` is enabled when needed for bursty reads
- If a Dolby Vision title falls back unexpectedly, check whether the build reports the native bridge as loaded and whether the extractor hook installed successfully.
- If repeated restarts happen only on a specific codec, review whether Nexio forced a stream-specific FFmpeg retry path.

Useful runtime strings to watch:

- `RUNTIME_TRANSPORT transport_specialization_confirmed ...`
- `RUNTIME_TRANSPORT transport_specialization_mismatch ...`
- `RUNTIME_TRANSPORT transport_specialization_revoked ...`
- `VOD warm-ahead failed at offset=<MB> len=<MB>MB`
- `VOD cache initialized successfully with cap=<MB>MB`
- `Disabling VOD cache after synchronous initialization failure`

## Related pages

- [FFmpeg](./ffmpeg.md)
- [libdovi](./libdovi.md)
- [IEC Passthrough](./iec.md)
- [Playback Interface](../screens/player.md)
- [Architecture](../../dev/architecture.md)
