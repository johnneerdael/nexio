# libdovi

Nexio uses a small native bridge around `libdovi` to experiment with realtime Dolby Vision compatibility conversion, especially DV7 to DV8.1 remapping before decode. This is one of the most advanced parts of the playback stack, and it is intentionally guarded by build checks, runtime probes, and extractor-hook installation.

## What this capability is

At a high level, Nexio can intercept Dolby Vision RPU data during extraction, convert that metadata in native code, and feed the rewritten payload back into the playback pipeline. The current implementation is focused on:

- **DV7 to DV8.1 realtime conversion**
- optional **DV5 compatibility remapping**
- RPU tapping for **experimental DV5 hardware tone-mapping work**

This does not mean every build automatically performs conversion. The bridge can exist in either a real or stub mode.

## Build-time behavior

The native bridge is compiled from `app/src/main/cpp/dovi_bridge.cpp` and configured by `app/src/main/cpp/CMakeLists.txt`.

There are two important modes:

- **real mode**
  `libdovi` is linked successfully for the current ABI, and the bridge reports itself ready for conversion work

- **stub mode**
  the bridge is present but returns "not ready" and conversion calls produce no output

In other words, a build can expose the plumbing without shipping a working `libdovi` payload. The app accounts for that by probing the bridge before enabling the feature for a stream.

## Bundled libdovi provenance

The tracked Android prebuilts under `third_party/libdovi` are built from Nexio's `dovi_tool` fork after merging upstream `quietvoid/dovi_tool` 2.3.2. They also include Nexio's C API conversion-mode patch:

- C API mode `2`: default profile 8.1 conversion
- C API mode `4`: profile 8.1 conversion with mapping metadata preserved

The app intentionally exposes preserve mapping as app mode `5`, then maps it to C API mode `4` in `app/src/main/cpp/dovi_bridge.cpp`. This keeps the UI/runtime setting distinct from raw `libdovi` C API values and avoids accidentally selecting profile 8.4 from the DV7-to-DV8.1 bridge.

## How DV7 to DV8.1 conversion works

The runtime flow is:

1. Nexio checks whether Dolby Vision conversion is enabled in settings.
2. The app probes whether the native library loaded, whether the build says the extractor hook is integrated, and whether the native bridge reports the path ready.
3. Media3 extractor hooks are installed for Matroska, MP4, fragmented MP4, and TS/H.265 readers.
4. When Dolby Vision metadata is encountered, Nexio can rewrite codec signaling and transform RPU payloads through the native bridge.

For DV7 streams, Nexio currently uses this path as a compatibility layer before falling back to plain HEVC handling.

## Preserve mapping mode

Nexio also exposes an experimental "preserve mapping" variant for DV7 to DV8.1 conversion. In code, this selects a different conversion mode than the default compatibility remap.

Use this setting carefully:

- it is still experimental
- it changes how much original mapping intent is retained in the converted RPU
- compatibility can vary by source and display chain

For operators, the practical advice is simple: treat it as a troubleshooting toggle, not a universal improvement.

## Extractor hooks and supported container paths

Nexio installs Dolby Vision transformers into several Media3 extractors by reflection and proxy:

- Matroska extractor
- MP4 extractor
- fragmented MP4 extractor
- TS H.265 reader

Those hooks can:

- inspect block additional data and HEVC samples
- rewrite Dolby Vision codec strings
- transform Dolby Vision RPU NAL payloads
- tap RPU timing for DV5 hardware experiments

Because the work happens at extraction time, this feature is tightly coupled to Media3. If the hook cannot be installed, Nexio disables the active conversion path for that playback.

## Realtime safety checks

Before Nexio considers the feature active for a stream, it performs a startup probe that includes:

- build flag checks
- native library load status
- extractor-hook readiness
- a native readiness query
- a small self-test conversion attempt

The self-test is intentionally conservative. If the bridge reports ready but the synthetic sample is not fully verifiable, Nexio keeps the path available instead of hard-failing the feature.

## DV7, DV5, and FFmpeg interplay

`libdovi` is only one part of Dolby Vision handling in Nexio.

- **Media3** provides the extractor hook points
- **libdovi** performs the metadata conversion work
- **FFmpeg** becomes relevant when Nexio needs software decode or experimental tone mapping

This is especially important for DV5:

- DV5 compatibility remapping is separate from DV7 conversion
- DV5 software tone mapping is an FFmpeg path, not a `libdovi` feature
- DV5 hardware tone-map experiments use an RPU tap that queues payloads by timestamp and hands them to native rendering code

## Compatibility expectations

This feature is still experimental. Expect the following:

- it may be unavailable in some builds even if the toggle exists
- it depends on ABI-specific native artifacts
- hook installation can succeed on one container path and fail on another if the relevant transformer is absent
- Dolby Vision capable displays and non-Dolby Vision displays may need different paths

For advanced users, the safest interpretation is: this is a compatibility layer under active development, not a blanket promise that every Dolby Vision file will render correctly.

## Troubleshooting

- If the feature appears disabled, check whether the build loaded the native bridge in real mode rather than stub mode.
- If playback starts but conversion never seems to occur, verify that the extractor hook was installed for the container in use.
- If DV7 conversion is enabled but unstable, try disabling preserve mapping mode first.
- If the issue is specific to DV5 tone mapping, review [FFmpeg](./ffmpeg.md) as well. That path depends on different native components.

## Related pages

- [Media3](./media3.md)
- [FFmpeg](./ffmpeg.md)
- [IEC Passthrough](./iec.md)
- [Architecture](../../dev/architecture.md)
