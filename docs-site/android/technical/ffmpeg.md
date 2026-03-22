# FFmpeg

Nexio ships a custom Media3 FFmpeg decoder module to cover formats and fallback cases that are unreliable or unsupported through Android's normal hardware decoder path. In practice, FFmpeg is most important for difficult video formats, advanced Dolby Vision experiments, and stream-specific recovery when a platform decoder fails.

## What FFmpeg is used for

The local `decoder_ffmpeg` module adds:

- `FfmpegAudioRenderer` for audio fallback
- `ExperimentalFfmpegVideoRenderer` for video fallback
- native glue code that exposes FFmpeg capabilities to Media3
- optional experimental Dolby Vision tone-mapping hooks

Nexio does not use FFmpeg for every file. Media3 still decides which renderer wins, and Nexio only forces FFmpeg preference in selected cases.

## Built codecs that matter most in Nexio

The repository build scripts are maintained against the local FFmpeg 8.x tree and include decoders such as:

- AAC, AC-3, E-AC-3, AC-4, TrueHD, DTS family, FLAC, ALAC, Opus, Vorbis
- H.264, HEVC, AV1, VC-1, MPEG-2, MPEG-4 ASP, VP8, VP9, MJPEG

For Nexio operators, the most notable entries are:

- **VC-1**, because Android TV hardware decode support is inconsistent
- **HEVC**, because Dolby Vision compatibility work ultimately rides on HEVC decode behavior
- **TrueHD and DTS family audio**, because those formats interact with passthrough and sink selection

## VC-1 software decoding

VC-1 is one of the clearest reasons this module exists.

Current runtime behavior:

- Nexio can detect VC-1 decode failures from Media3 playback exceptions
- on first failure, the current stream is retried with platform VC-1 decoders hidden from Media3
- that causes Media3 to prefer the FFmpeg video renderer for VC-1
- if the issue persists, Nexio can retry again with a more aggressive track-selection bypass

Why this matters:

- many Android TV devices still advertise uneven or brittle VC-1 support
- some files fail during decoder init rather than at track selection time
- a software fallback is often more useful than leaving the user with a hard playback failure

Trade-offs:

- software decode can cost more CPU and power
- high-bitrate VC-1 content may still challenge lower-end hardware
- FFmpeg fallback improves compatibility, not guaranteed performance

## How renderer selection works

Nexio maps its playback setting for decoder priority onto Media3 extension renderer modes:

- device-first behavior keeps platform decoders ahead of FFmpeg
- app-preferred behavior gives FFmpeg higher priority
- some automatic retries temporarily force `prefer` mode for the current stream

Nexio currently forces FFmpeg preference for cases including:

- VC-1 retry mode
- AV1 fallback after `dav1d` init failure
- DV5 software tone mapping to SDR

That makes FFmpeg a targeted fallback tool rather than a blanket replacement for MediaCodec.

## Dolby Vision and FFmpeg

FFmpeg plays two distinct Dolby Vision roles in Nexio:

1. **Decode support**
   `video/dolby_vision` is mapped to FFmpeg's HEVC decoder, which is how the module handles Dolby Vision elementary streams.

2. **Experimental tone-mapping**
   The module exposes an experimental DV5 software tone-map path and a separate hardware-frame bridge for native rendering experiments.

Important caveats:

- DV7 to DV8.1 compatibility remapping is handled by Nexio's `libdovi` bridge, not by FFmpeg alone
- FFmpeg becomes part of the Dolby Vision story when Nexio needs software decode or native tone-map plumbing
- the software DV5 tone-map path depends on a build that includes `libavfilter` and `libplacebo`
- runtime support also depends on Vulkan availability on the target device

## FFmpeg, Media3, and libdovi together

These components solve different layers of the same problem:

- **Media3** selects extractors, tracks, and renderers
- **libdovi** rewrites Dolby Vision RPU data when Nexio enables realtime DV compatibility conversion
- **FFmpeg** provides software decode and experimental native video processing paths when hardware decode is not enough

A useful mental model is:

`Media3 chooses the path -> libdovi can rewrite the Dolby Vision metadata -> FFmpeg may decode or render the result`

## Build and maintenance notes

The repo does not rely on Maven-published FFmpeg extensions. The module is built locally, and the current scripts are designed around the repository's FFmpeg checkout.

Contributor-facing facts that matter:

- the build scripts expect FFmpeg 8.x for the current Dolby Vision tone-map path
- decoder availability is determined by the native FFmpeg build, not just by Java code
- `FfmpegLibrary.supportsFormat()` only returns true if the codec is both mapped and compiled in

If a codec appears to be "supported in code" but never activates at runtime, the native FFmpeg build is the first thing to verify.

## Limitations

- FFmpeg fallback does not guarantee smooth playback for every high-bitrate file
- DRM-protected content is not handled by the FFmpeg video renderer path
- the DV5 tone-mapping paths are experimental and intentionally gated by both build and runtime checks
- FFmpeg support is only as complete as the native build configuration included in the APK

## Troubleshooting

- If VC-1 still fails after retry, check logs for whether the stream entered FFmpeg-preferred mode.
- If FFmpeg never activates for a codec, verify that the native module was built with that decoder enabled.
- If DV5 software tone mapping stays disabled, check both build support and Vulkan runtime support.
- If a Dolby Vision file decodes but displays incorrectly, review the [libdovi](./libdovi.md) page as well as the active renderer path.

## Related pages

- [Media3](./media3.md)
- [libdovi](./libdovi.md)
- [IEC Passthrough](./iec.md)
- [Architecture](../../dev/architecture.md)
