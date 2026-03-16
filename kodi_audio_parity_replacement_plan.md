# Kodi Audio Parity Replacement Plan

## Goal

Replace the reduced settings / device-selection slice in the custom Kodi AudioSink path with Kodi-shaped logic, not incremental fixes on top of the reduced model.

Target scope:

- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/ActiveAESettings.*`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/ActiveAESink.*`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/ActiveAEPolicy.*`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiCapabilitySelector.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AESinkAUDIOTRACK.*`

Primary Kodi references:

- `xbmc/xbmc/cores/AudioEngine/Sinks/AESinkAUDIOTRACK.cpp`
- `xbmc/xbmc/cores/AudioEngine/Engines/ActiveAE/ActiveAE.cpp`
- `xbmc/xbmc/cores/AudioEngine/Engines/ActiveAE/ActiveAESink.cpp`
- `xbmc/xbmc/cores/AudioEngine/Engines/ActiveAE/ActiveAESettings.cpp`

## Replacement Items

### 1. Replace reduced passthrough-device defaulting

Current reduced behavior:

- `ActiveAESettings::Load(...)` hardcodes device names directly.
- No Kodi-style validation of audio device / passthrough device strings.

Required Kodi-shaped replacement:

- Port the relevant `ValidateOuputDevice(...)` behavior from Kodi `ActiveAESink.cpp`.
- Resolve the active audio device and passthrough device through validation, not hardcoded assumptions.
- Ensure default passthrough selection resolves the same way Kodi does when multiple AudioTrack passthrough devices exist.

### 2. Replace reduced passthrough support search

Current reduced behavior:

- `ActiveAEPolicy::SupportsRaw(...)` calls `FindSupportingPassthroughDevice(...)`.
- That is a reduced policy path, not the Kodi `SupportsRaw(...)` behavior.

Required Kodi-shaped replacement:

- Make `SupportsRaw(...)` check the validated passthrough device directly, matching Kodi `ActiveAE.cpp`.
- Remove the reduced “search any supporting passthrough device” branch from the authority path.

### 3. Replace reduced sink inventory model

Current reduced behavior:

- `ActiveAESink` stores a flat list only.
- No Kodi-style sink/device enumeration structure.
- No Kodi-style passthrough-vs-PCM list filtering / device-string handling.

Required Kodi-shaped replacement:

- Introduce Kodi-shaped sink inventory handling sufficient for the AudioTrack-only port:
  - sink name
  - device list
  - `EnumerateOutputDevices(..., passthrough)`
  - `ValidateOuputDevice(...)`
- Preserve Kodi device-string matching semantics as far as they apply to the single-sink Android port.

### 4. Replace reduced selector authority

Current reduced behavior:

- `KodiCapabilitySelector.cpp` still acts as a reduced synthetic selector.
- It builds a passthrough decision through reduced settings + reduced search logic.

Required Kodi-shaped replacement:

- Keep MIME-kind entry from Media3, but make the actual raw/IEC device decision flow use Kodi-shaped:
  - sink inventory
  - validated passthrough device
  - direct `SupportsFormat(...)`
  - DTS core fallback
- Do not keep non-Kodi selection branches once the replacement path is in place.

### 5. Replace remaining AudioTrack device-enumeration gaps

Current reduced behavior:

- `AESinkAUDIOTRACK::EnumerateDevicesEx(...)` still differs from Kodi in device ordering and passthrough-only marking.

Required Kodi-shaped replacement:

- Preserve Kodi ordering semantics:
  - IEC device first when available
  - RAW device afterwards
- Preserve Kodi passthrough-only behavior for RAW when IEC is present.
- Keep the exact Kodi stream-type families and carrier-rate rules already aligned in earlier fixes.

## Success Criteria

- For E-AC3 on devices where Kodi chooses IEC, our log should stop selecting RAW by default and instead follow the Kodi-shaped passthrough device resolution.
- `SupportsRaw(...)` should no longer depend on any reduced “find any device” policy logic.
- Remaining non-Kodi gaps, if any, must be outside this slice and explicitly labeled as either `Media3 integration` or `App startup/recovery`.
