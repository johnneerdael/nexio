# IEC Passthrough

Nexio includes an experimental Android passthrough path built around a Kodi-derived AudioEngine transplant, IEC 61937 burst packing, and a custom Media3 audio sink wrapper. This is the deepest and most specialized part of the audio stack. It exists to pursue more faithful encoded output on devices where the standard Android passthrough path is too limited or unreliable.

## Status

This feature is **experimental and work in progress**.

When disabled, Nexio uses Media3's normal `DefaultAudioSink`.

When enabled, Nexio can route encoded audio through custom sinks backed by:

- copied Kodi AudioEngine source under `media/libraries/cpp_audiosink/kodi/xbmc/cores/AudioEngine`
- Nexio JNI adapters under `media/libraries/exoplayer_kodi_cpp_audiosink`
- Android `AudioTrack` output using IEC 61937 framing

That makes the path powerful, but also more sensitive to device quirks than the default Media3 route.

## Why this exists

Android passthrough support is uneven. Some devices expose direct playback capabilities that do not behave consistently in practice, especially for high-end home theater formats.

The custom IEC path exists to improve control over:

- AC-3, E-AC-3, DTS core, TrueHD, and DTS-HD family passthrough decisions
- packetization and IEC framing
- TrueHD MAT generation before IEC wrapping
- device and route diagnostics for operator validation

The goal is not "more codecs at any cost." The goal is a path that is closer to the expected bitstream layout and easier to validate end to end.

## Source of truth: Kodi AudioEngine

Per the project rules, the behavior of the IEC packer and Android sink path is grounded in Kodi source under:

- `AudioEngine/Utils/AEPackIEC61937.cpp`
- `AudioEngine/Utils/PackerMAT.cpp`
- `AudioEngine/Sinks/AESinkAUDIOTRACK.cpp`

Those files define the core ideas Nexio is reusing:

- IEC 61937 preambles and payload layout
- AC-3, E-AC-3, DTS, DTS-HD, and TrueHD packing rules
- MAT framing for TrueHD
- Android `AudioTrack` sink probing and encoded output behavior

Nexio does not document this as a generic Android trick. It is specifically a Kodi-derived transport experiment wrapped for Media3.

## How the Nexio IEC path is assembled

At runtime, the custom renderer factory can replace the default Media3 audio sink with:

- `KodiNativeAudioSink` for the baseline custom passthrough path
- `KodiTrueHdNativeAudioSink` for TrueHD-specific handling
- `KodiTrueHdEntryAudioSink` to choose between those sinks based on the active audio format

On the native side:

- `KodiIecPipeline` handles the baseline IEC packing flow
- `KodiTrueHdIecPipeline` adds TrueHD-specific MAT preparation and backlog handling
- `KodiAudioTrackOutput` and related classes handle the Android output session

In practical terms, Nexio separates "regular IEC passthrough" from "TrueHD needs extra MAT-aware startup and handoff logic."

## Perfect bitstreaming, realistically explained

"Perfect bitstreaming" on Android is an aspiration, not a guarantee. In Nexio, it means:

- preserving the encoded transport structure as far as Android's `AudioTrack` path allows
- emitting correctly packed IEC bursts
- validating that the bytes produced by the packer match the bytes written to `AudioTrack`
- monitoring whether the output route stays stable after playback starts

It does **not** mean every Android device will produce identical AVR behavior. Some devices still reopen outputs, reclassify routes, or advertise capabilities that later fail during sink initialization.

## Format handling

The current settings model exposes codec-level passthrough toggles for:

- AC-3
- E-AC-3
- DTS core
- TrueHD
- DTS-HD / DTS:X family

There is also a DTS-HD core fallback option. Nexio additionally builds a safer "stable capabilities" view for fallback mode so devices that misreport DTS-HD support can drop back to DTS core instead of repeatedly failing `AudioTrack` initialization.

## TrueHD and MAT framing

TrueHD is the most complex path.

Kodi's MAT packer logic matters because TrueHD is not sent as raw elementary frames. Nexio's TrueHD pipeline:

1. adapts passthrough access units through the ActiveAE stream adapter
2. packs TrueHD into MAT frames
3. wraps the MAT output in IEC 61937 bursts
4. writes those bursts through the custom Android sink

The implementation is intentionally conservative:

- startup waits for enough source inventory before handing off to steady-state writes
- the native and Java layers track startup versus steady-state ownership separately
- tests in `app/src/test/java/com/nexio/tv/debug/passthrough/` enforce that this handoff logic does not regress

For operators, the important takeaway is that TrueHD startup behavior is deliberately not identical to AC-3 or DTS startup behavior.

## Device behavior and fallbacks

This path is powerful, but it is not forgiving.

With the default Media3 sink, Nexio can retry some failures by:

- entering a safer audio mode
- constraining channels to device capabilities
- disabling audio entirely as a last resort

With the custom Kodi IEC sink enabled, Nexio intentionally does **not** apply those safe-audio retries. If `AudioTrack` initialization fails or the player gets stuck with no progress, Nexio logs the condition and leaves the failure visible instead of silently falling back.

That is the right behavior for validation work, but it also means this path should be enabled only when you explicitly want to test it.

## Validation workflow for testers and operators

Nexio includes a debug-only transport validation harness for this path.

What it does:

- downloads or reuses hosted validation assets
- parses reference IEC or MAT bursts from a known-good sample
- captures three stages of live playback:
  `packer input -> packed bursts -> AudioTrack write bursts`
- compares the live bursts against the reference
- scores runtime stability separately from pure transport correctness
- exports a zip bundle with JSON summaries and optional binary dumps

The receiver action is `com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION`, and it supports commands to:

- enable or disable validation
- choose a sample
- start or stop playback
- control burst capture and binary dump output
- enable runtime validation
- record operator observations such as weak AVR lock or choppy audio
- export the current session bundle

This only works in debug builds.

## What the validation bundle tells you

The exported bundle is designed for operator review, not just engineering archaeology. It includes:

- summary metadata
- comparison results
- route snapshot and route-health information
- runtime verdicts and failure codes
- sink-health analytics such as zero-write streaks, underruns, and output restarts
- burst-by-burst JSON records
- optional raw binary dumps for captured bursts

That makes it possible to answer two different questions:

- Did the packer produce the expected bytes?
- Did the playback session remain stable enough to trust the result?

## Troubleshooting guidance

- If passthrough fails only when the experimental IEC path is enabled, disable the custom sink first to confirm the issue is specific to the custom path.
- If TrueHD starts but drops out after the first burst, inspect startup-versus-steady-state handoff behavior in the validation bundle.
- If DTS-HD initializes badly on a device that claims support, try the DTS core fallback setting.
- If the route changes after startup, treat the session as degraded even if the initial transport comparison passed.
- If you need a reproducible operator report, export the validation bundle instead of relying on logcat alone.

## When to use this feature

Use it when you need:

- controlled passthrough validation on debug builds
- deeper insight into IEC burst correctness
- investigation of TrueHD, DTS-HD, or Fire OS sink behavior

Avoid it when you just want the safest general playback path. The default Media3 sink remains the better choice for ordinary use.

## Related pages

- [Media3](./media3.md)
- [FFmpeg](./ffmpeg.md)
- [Playback Interface](../screens/player.md)
- [Architecture](../../dev/architecture.md)
- [Deployment](../../dev/deployment.md)
