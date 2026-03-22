# Architecture

This page explains how the Nexio repository is organized and how the major runtime systems fit together. It is written for contributors, advanced testers, and maintainers who need to understand where a behavior lives before they change it or document it.

## Repository structure

The repo is split into four main areas:

- `app/`
  the Android TV application, including UI, playback orchestration, settings, account integration, and debug tools

- `media/`
  local Media3-derived libraries plus custom playback modules such as the FFmpeg decoder extension and the Kodi-derived audio sink adapter

- `nexio-web/`
  the account portal and admin workspace used for addon, catalog, and formatter management

- `docs-site/`
  the VitePress documentation portal published to GitHub Pages

## Playback architecture

On Android, the playback stack is layered rather than monolithic.

### Control layer

The app builds and owns playback sessions in `app/`, including:

- player construction
- track selection
- playback settings
- retries and fallback behavior
- stream metadata and subtitle handling

### Media layer

The local `media/` tree provides the heavy lifting:

- Media3 player, extractor, and renderer foundations
- FFmpeg decoder extensions for audio and video fallback
- custom video sink work for experimental Dolby Vision tone mapping
- the Kodi-derived audio sink path for IEC passthrough experiments

### Native bridge layer

Nexio uses native code where Java alone is not enough:

- `dovi_bridge` for experimental Dolby Vision metadata conversion
- FFmpeg JNI for decode and native rendering hooks
- JNI wrappers around the Kodi-derived audio sink implementation

## How advanced playback features fit together

Several of Nexio's most technical features are intentionally split across subsystems:

### Dolby Vision compatibility

- Media3 supplies extractor hook points
- the `libdovi` bridge can rewrite RPU metadata in realtime
- FFmpeg participates when software decode or experimental tone mapping is needed

### VC-1 fallback

- Media3 detects the playback failure
- Nexio retries the stream with platform VC-1 decoders hidden
- FFmpeg becomes the preferred software decode path

### IEC passthrough

- Media3 owns renderer creation
- the custom renderer factory swaps in a Kodi-backed audio sink when enabled
- the native IEC pipeline uses Kodi AudioEngine logic for packing and Android `AudioTrack` output
- debug validation tools capture both transport and runtime stability data

### VOD cache and parallel downloading

- the app configures these features from playback settings
- the progressive media source factory decides whether they apply to the current stream
- only progressive HTTP playback uses the VOD cache and parallel range path

## Operational boundaries

Understanding boundaries matters in this repo.

- UI behavior usually lives in `app/`
- decoder and renderer behavior often lives in `media/`
- native playback experiments span both `app/` and `media/`
- docs changes should describe what the code does today, not what a review thread hoped to land later

For the custom audio sink in particular, Kodi source under `media/libraries/cpp_audiosink/kodi/xbmc/cores/AudioEngine` is the behavioral reference point. Nexio-specific adaptation belongs around that copied source, not inside the copied subtree.

## Documentation architecture

The published docs are intentionally audience-based:

- `docs-site/web/` for portal and admin workflows
- `docs-site/android/` for Android use, troubleshooting, and playback features
- `docs-site/dev/` for contributor and operator guidance

The technical Android pages are the bridge between user-facing playback behavior and contributor-facing implementation details.

## What to read for common tasks

- For playback pipeline questions, start with [Media3](../android/technical/media3.md).
- For software decode and codec support questions, read [FFmpeg](../android/technical/ffmpeg.md).
- For Dolby Vision conversion behavior, read [libdovi](../android/technical/libdovi.md).
- For encoded audio transport and validation, read [IEC Passthrough](../android/technical/iec.md).
- For docs publishing, read [Deployment](./deployment.md).

## Design principles visible in the code

The current codebase follows a few practical patterns:

- keep the default path conservative and use retries before forcing advanced fallbacks
- gate experimental features behind explicit settings and runtime probes
- preserve diagnostic visibility instead of hiding failures in highly experimental paths
- reuse upstream behavior where possible and isolate Nexio-specific adaptations around it

That pattern is especially clear in playback: Nexio will often start with standard Media3 behavior, then opt into FFmpeg, `libdovi`, or the Kodi-derived sink only when the stream, device, and settings justify it.

## Related pages

- [Media3](../android/technical/media3.md)
- [FFmpeg](../android/technical/ffmpeg.md)
- [libdovi](../android/technical/libdovi.md)
- [IEC Passthrough](../android/technical/iec.md)
- [Deployment](./deployment.md)
