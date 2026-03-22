# Media3

Nexio uses Media3 as the playback control plane, then layers custom extractors, decoder extensions, native bridges, and network optimizations on top of it. For most users this still feels like a normal Android TV player. For contributors and testers, it means playback behavior is determined by both upstream Media3 rules and Nexio-specific integrations.

## What Media3 does in Nexio

Media3 is responsible for:

- building the player instance and track selector
- choosing between platform decoders and extension decoders
- handling HLS, DASH, progressive files, and Blu-ray style sources
- driving subtitle renderers, audio sink selection, and playback state
- surfacing errors that Nexio can use for automatic retry and fallback decisions

Nexio extends that baseline in three important places:

1. **Extractor hooks for Dolby Vision**
   Media3 extractors can be hooked so Nexio can inspect or rewrite Dolby Vision signaling before decode begins.

2. **Custom renderer and decoder selection**
   Nexio can prefer FFmpeg renderers for cases such as VC-1 software decoding, AV1 fallback, or experimental Dolby Vision tone-mapping paths.

3. **Custom network input path**
   Progressive HTTP playback can use both a VOD cache and an optional multi-connection range downloader.

## Playback pipeline at a glance

For a normal HTTP progressive stream, the pipeline is:

`URL -> OkHttp/DefaultDataSource or ParallelRangeDataSource -> optional VOD cache -> Media3 extractors -> track selector -> platform or FFmpeg renderers -> audio sink / video sink`

For HLS and DASH, Nexio stays closer to standard Media3 behavior:

- HLS uses `HlsMediaSource`
- DASH uses `DashMediaSource`
- the progressive-only VOD cache and parallel range downloader do not apply

For Blu-ray-style content, Nexio also has special handling:

- local Blu-ray folders can be resolved through playlist parsing
- remote HTTP directory listings can be probed for `BDMV/PLAYLIST` and `BDMV/STREAM`
- BDAV `.m2ts` playback uses TS extractor flags that enable HDMV DTS audio support

## VOD cache and warm-ahead prefetch

Nexio includes a disk-backed VOD cache for progressive HTTP and HTTPS playback. This is intended to reduce repeated network reads, smooth seeking, and give long-running streams a local read-ahead buffer.

Key behaviors from the current implementation:

- the cache is enabled only for progressive HTTP playback, not HLS or DASH
- the cache lives under the app cache directory as `player_vod_cache`
- the configured size is clamped at runtime so the app keeps disk headroom
- Nexio reserves roughly 1 GiB of free space when possible before growing the cache
- after the first rendered frame, Nexio can start a background warm-ahead loop that fills uncached regions in 16 MiB blocks
- the warm-ahead loop stays behind an active-read guard so it does not compete with the part of the file currently being consumed

This feature helps most with large VOD files and repeated starts or seeks. It is less relevant for short clips and does not replace true offline download support.

## Parallel downloading for progressive playback

Nexio can optionally replace the normal upstream data source with a multi-connection range downloader for progressive streams. This mode is not used for HLS or DASH.

When enabled:

- Media3 still sees a normal stream
- the upstream fetch layer may open multiple HTTP range requests in parallel
- the number of connections and chunk size are user-configurable
- startup prefetch stays locked until initial playback is stable, then background prefetch may continue

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

- VOD cache and parallel downloading are progressive-only optimizations
- DV7 to DV8.1 conversion depends on build flags, native library availability, and successful hook installation
- DV5 tone mapping remains experimental and device-sensitive
- the custom Kodi-derived IEC sink is opt-in and separate from the default Media3 audio path

If a feature is described elsewhere as experimental, Media3 is usually the point where Nexio decides whether to activate it for the current stream.

## Troubleshooting

- If HLS or DASH behavior does not match progressive playback behavior, check whether cache and parallel downloading are in scope. They are not used for adaptive streaming.
- If a Dolby Vision title falls back unexpectedly, check whether the build reports the native bridge as loaded and whether the extractor hook installed successfully.
- If repeated restarts happen only on a specific codec, review whether Nexio forced a stream-specific FFmpeg retry path.
- If startup is fast but seeking remains network-bound, verify that VOD cache is enabled and that the source is a progressive HTTP URL.

## Related pages

- [FFmpeg](./ffmpeg.md)
- [libdovi](./libdovi.md)
- [IEC Passthrough](./iec.md)
- [Playback Interface](../screens/player.md)
- [Architecture](../../dev/architecture.md)
