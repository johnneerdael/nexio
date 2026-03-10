# DV5 ADB Log Gathering and Troubleshooting

This guide explains how to collect actionable logs for **DV5 (Dolby Vision Profile 5) hardware tone mapping** issues.

## 1. Prerequisites

- Enable `Developer options` on the playback device.
- Enable `USB debugging` (or network ADB).
- Connect device:

```bash
adb devices
```

## 2. Known Runtime Markers

Look for these markers in `logcat`:

- `DV5_HW_RENDER: runtimePath=...`
  - Emitted once per playback session by `ExperimentalDv5HardwareToneMapVideoSink`.
  - Confirms selected input mode and final `ImageReader` format.
- `DV5_HW_RENDER: cpu-readback chromaLayout=...`
  - Emitted once per native renderer context.
  - Confirms detected `YUV_420_888` chroma layout (`NV12`, `NV21`, `I420`, or `UNKNOWN`).
- `DV5_HW_PURE: AHB has VK_FORMAT_UNDEFINED...`
  - Vulkan import of `AHardwareBuffer` failed (common on Shield with `PRIVATE` buffers).
- `DV5_HW_RENDER: pure native renderer unavailable; using FFmpeg fallback`
  - Pure renderer failed; JNI FFmpeg path is used.
- `DV5_HW_RPU: ...`
  - RPU queue/match diagnostics.
- `VIDEO_PATH: ...`
  - Session-level playback path + feature toggle state.

## 3. Capture Logs (Recommended Flow)

1. Clear existing logs:

```bash
adb logcat -c
```

2. Start focused capture:

```bash
adb logcat -v threadtime | grep -E "DV5_HW_|DV7_DOVI|VIDEO_PATH|MediaCodec|ExoPlayer"
```

3. Reproduce issue:
- Start playback for the problematic DV5 stream.
- Let it play for at least 20-30 seconds.
- Capture the time where artifact/stutter/black screen starts.

4. Save full log dump:

```bash
adb logcat -d > dv5-logcat.txt
```

## 4. Collect Device Context

```bash
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.hardware
```

Optional codec snapshot:

```bash
adb shell dumpsys media.codec > dv5-media-codec.txt
```

## 5. How to Interpret the Main Markers

### A. Runtime Path Marker

Example:

```text
DV5_HW_RENDER: runtimePath=GPU_OPTIMIZED format=YCBCR_P010(...) size=3840x2160 forceCpuFallback=false
```

- `GPU_OPTIMIZED`: tries AHB Vulkan import first (zero-copy candidate).
- `CPU_READBACK`: forced `YUV_420_888` CPU-readable path.
- `format=PRIVATE` with later `VK_FORMAT_UNDEFINED` usually indicates opaque buffer import limitation.

### B. Chroma Layout Marker

Example:

```text
DV5_HW_RENDER: cpu-readback chromaLayout=NV12_INTERLEAVED format=... planeCount=...
```

- `NV12_INTERLEAVED`: expected on many Shield paths.
- `NV21_INTERLEAVED`: U/V swapped interleaved chroma.
- `I420_PLANAR`: separate U and V planes.
- `UNKNOWN`: unusual vendor output; investigate `pixelStride/rowStride` in same line.

### C. RPU Marker

If `misses` grows while `matched` stays low, frame timestamps are not matching queued RPU samples reliably.

## 6. Common Failure Patterns

- **Black screen + `VK_FORMAT_UNDEFINED`**:
  - Device/driver rejected practical AHB import format for libplacebo v5 path.
  - Expected mitigation: CPU-readback fallback path.

- **Purple/green tint**:
  - Tone mapping or DV metadata path likely not applied; verify `DV5_HW_RPU` and render path logs.

- **Thermal throttling/stutter on Shield**:
  - CPU-readback path may be active (`runtimePath=CPU_READBACK`), increasing memory bandwidth pressure.

## 7. Bug Report Template

Include:

- Device model + Android version.
- Stream/container details (codec, resolution, frame rate, HDR/DV info).
- Feature toggles used:
  - `DV5 - Hardware decode tone map`
  - `DV5 - Shield CPU readback fallback`
  - `DV5 - Tone map to SDR` (software path)
- `dv5-logcat.txt` + optional `dv5-media-codec.txt`.
- Timestamp where issue occurs.

## 8. Quick One-Liner (Capture Everything)

```bash
adb logcat -c && adb logcat -v threadtime > dv5-session.log
```

Start playback, reproduce, then stop capture with `Ctrl+C`.

