# libplacebo Pixel Conversion Benchmark

## Status

Benchmark evidence gathering is not complete.

Completed:

- AM9 Vulkan capability capture via `cmd gpu vkjson`.
- AM9 display sink classification as HDR10/HDR10+/HLG, no Dolby Vision.

Blocked:

- External libplacebo probe APK/workspace has not been created.
- AM8 Pro is not connected.
- No exact P5 decoded-frame benchmark input was provided.
- Existing Nexio `ExperimentalDv5HardwareToneMapVideoSink` was not run because no P5 media path/URI was provided.

## Required Pipeline

- Input: decoded Dolby Vision Profile 5 10-bit YUV frame.
- Transform: libplacebo `pl_renderer` with Dolby Vision mapping enabled.
- Output: BT.2020 PQ HDR10 RGB suitable for HDR10-only displays.
- Purpose: fallback only if the stock Amlogic SoC DV composer downconvert path cannot produce correct HDR10 from P5 on the attached Samsung non-DV sink.
- Target budget: at most 12 ms per frame at 4K60, leaving room for decode and present.
- Required devices: AM8 Pro and AM9 Pro.

## Results

| Device | GPU | Resolution/frame rate | Import path | Mean ms/frame | P95 ms/frame | 30 min thermals | Dropped frames | Verdict |
|--------|-----|-----------------------|-------------|---------------|--------------|-----------------|----------------|---------|
| AM9 Pro | Mali-G310 V2 | 1080p24 | not run | not observed | not observed | not observed | not observed | blocked |
| AM9 Pro | Mali-G310 V2 | 4K24 | not run | not observed | not observed | not observed | not observed | blocked |
| AM9 Pro | Mali-G310 V2 | 4K60 | not run | not observed | not observed | not observed | not observed | blocked |
| AM8 Pro | Mali-G57 MC2 | 1080p24 | not run | not observed | not observed | not observed | not observed | blocked, device absent |
| AM8 Pro | Mali-G57 MC2 | 4K24 | not run | not observed | not observed | not observed | not observed | blocked, device absent |
| AM8 Pro | Mali-G57 MC2 | 4K60 | not run | not observed | not observed | not observed | not observed | blocked, device absent |

## Zero-Copy Finding

| Device | AHardwareBuffer decoder output available | `VK_ANDROID_external_memory_android_hardware_buffer` import works | CPU readback required | Evidence |
|--------|------------------------------------------|---------------------------------------------------------------|-----------------------|----------|
| AM9 Pro | not tested | extension advertised by Vulkan driver, import not tested | unknown | `evidence/am9-stock/cmd-gpu-vkjson.txt` |
| AM8 Pro | not tested | not observed | unknown | AM8 not connected |

## Existing Nexio VideoSink Probe

| Device | `ExperimentalDv5HardwareToneMapVideoSink` P010 output | Native bridge accepts frame | Frame drops | Evidence |
|--------|-------------------------------------------------------|-----------------------------|-------------|----------|
| AM9 Pro | not run | not observed | not observed | no P5 media path/URI provided |
| AM8 Pro | not run | not observed | not observed | AM8 not connected |

## AM9 Vulkan Evidence

`vulkaninfo` is not installed on the AM9 firmware, but Android's GPU service exposes `cmd gpu vkjson`.

Captured evidence:

- `evidence/am9-stock/vulkaninfo-summary.txt`
- `evidence/am9-stock/cmd-gpu-vkjson.txt`
- `evidence/am9-stock/cmd-gpu-help.txt`

Key AM9 findings:

- Device name: `Mali-G310`
- Driver info: `v1.r44p1-01eac0.9b95285bf71814048571d5e10c324e77`
- `VK_KHR_sampler_ycbcr_conversion` is present.
- `VK_ANDROID_external_memory_android_hardware_buffer` is present.
- `VK_EXT_astc_decode_mode` is present.
- FP16 shader and 16-bit storage features are present.
- Subgroup size is `16`.
- `VK_EXT_ycbcr_2plane_444_formats` was not found in the captured JSON.

## Interim Verdict

The AM9 Vulkan driver has the important external-memory and YCbCr-conversion primitives that make a zero-copy libplacebo path plausible, but this is not performance evidence. The libplacebo fallback remains unproven until an external probe measures real P5 reshape and IPT-to-BT.2020 PQ frame time. Do not prioritize this probe until the cheaper SoC composer downconvert path has been tested with the same P5 file on the Samsung HDR10 display.
