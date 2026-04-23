# Device Baseline

## Summary

| Device | ADB target | SoC | GPU | Android | Kernel | Root | DV sysfs visible to shell | DV sysfs visible to root | Stock DV module evidence | Vulkan API | Current display EDID | Display DV advertised |
|--------|------------|-----|-----|---------|--------|------|---------------------------|--------------------------|--------------------------|------------|----------------------|-----------------------|
| AM9 Pro | `192.168.50.71:5555` | Amlogic AML-S905X5 (`ro.soc.model=AMLS905X5`) | Mali-G310 | Android 14 / SDK 34 / UGOOS 2.0.8 / userdebug test-keys | `5.15.192-android14-11-g4cc28a24a606` | yes via `adb root`; `su` binary unavailable before `adb root` | yes, but under `/sys/module/aml_media/parameters/` rather than `/sys/module/amdolby_vision/parameters/` | yes | `dovi_s6_5_15_stb26` loaded and held by `aml_media` | `cmd gpu vkjson` reports Mali-G310 Vulkan with driver `v1.r44p1-01eac0.9b95285bf71814048571d5e10c324e77` | Samsung EDID captured | no, `dv_cap` says receiver does not support Dolby Vision |
| AM8 Pro | not connected | not captured | not captured | not captured | not captured | not captured | not captured | not captured | not captured | not captured | not captured | not captured |

## AM9 Pro Evidence

- Firmware and Android version: `evidence/am9-stock/getprop.txt`
- Kernel: `evidence/am9-stock/uname.txt`
- Initial shell identity: `evidence/am9-stock/shell-id.txt`
- Initial `su` result: `evidence/am9-stock/su-id.txt`
- Root ADB identity after `adb root`: `evidence/am9-stock/adb-root-id.txt`
- SurfaceFlinger HDR/color: `evidence/am9-stock/surfaceflinger-hdr-color.txt`
- Media services: `evidence/am9-stock/service-list-media.txt`, `evidence/am9-stock/cmd-media-player.txt`, and `evidence/am9-stock/dumpsys-media-player.txt`
- Codec service result: `evidence/am9-stock/dumpsys-media-codec.txt` and `evidence/am9-stock/cmd-media-codec.txt`
- Codec XML files: `evidence/am9-stock/media-codec-xml-files.txt`
- Amlogic Dolby Vision codec XML: `evidence/am9-stock/media_codecs_amlogic_dolby_vision.xml`
- Amlogic Dolby Vision performance XML: `evidence/am9-stock/media_codecs_amlogic_performance_dolby_vision.xml`
- Dolby Vision sysfs discovery: `evidence/am9-stock/sys-find-dolby.txt`, `evidence/am9-stock/sys-find-dovi.txt`, and `evidence/am9-stock/sys-find-amdv.txt`
- Original missing expected path: `evidence/am9-stock/amdolby-parameters-ls.txt`
- Captured Dolby/Amdv parameter values: `evidence/am9-stock/dolby-amdv-parameters-values.txt`
- Loaded Dolby module evidence: `evidence/am9-stock/lsmod-dovi.txt`
- HDMI caps: `evidence/am9-stock/dv-cap.txt`, `evidence/am9-stock/hdr-cap.txt`, `evidence/am9-stock/attr.txt`, and `evidence/am9-stock/config.txt`
- Vulkan: `evidence/am9-stock/vulkaninfo-summary.txt` and `evidence/am9-stock/cmd-gpu-vkjson.txt`
- EDID: `evidence/am9-stock/edid-current-display.bin`

## AM9 Pro Findings

The original expected path `/sys/module/amdolby_vision/parameters/` is absent, but root discovery found Dolby Vision controls under `/sys/module/aml_media/parameters/` and decoder flags under `/sys/module/amvdec_h265*/parameters/`.

Important captured controls include:

- `dolby_vision_enable=N`
- `dolby_vision_mode=5`
- `dolby_vision_policy=1`
- `dolby_vision_hdr10_policy=41`
- `dolby_vision_ll_policy=0`
- `dolby_vision_flags=536870917`
- `amdv_target_mode=5`
- `amdv_mask=7`
- `parser_dolby_vision_enable=1` for both `amvdec_h265` and `amvdec_h265_v4l`

The AM9 firmware advertises Amlogic Dolby Vision decoders in `/vendor/etc/media_codecs_amlogic_dolby_vision.xml`:

- `c2.amlogic.dolby-vision.dvhe.decoder`
- `c2.amlogic.dolby-vision.dvhe.decoder.secure`
- `c2.amlogic.dolby-vision.dvav.decoder`
- `c2.amlogic.dolby-vision.dvav.decoder.secure`
- `c2.amlogic.dolby-vision.dav1.decoder`
- `c2.amlogic.dolby-vision.dav1.decoder.secure`

All listed Dolby Vision decoders advertise adaptive playback and tunneled playback. The performance XML advertises 4K60 performance points for those Dolby Vision decoders.

The currently attached display is not a Dolby Vision TV. `dv_cap.txt` says `The Rx don't support DolbyVision`, while `hdr-cap.txt` advertises HDR10, HDR10+, PQ, and HLG support. SurfaceFlinger agrees with `dv=false`, `hdr10plus=true`, `hdr10=true`, and `hlg=true`. This display is the primary sink for the current correctness gate: P5 playback must produce correct HDR10 here and must not show green/purple.

`vulkaninfo` is not installed on the AM9 firmware, but `cmd gpu vkjson` is available. It reports:

- device name: `Mali-G310`
- driver name: `Mali-G310`
- driver info: `v1.r44p1-01eac0.9b95285bf71814048571d5e10c324e77`
- `VK_KHR_sampler_ycbcr_conversion`
- `VK_ANDROID_external_memory_android_hardware_buffer`
- `VK_EXT_astc_decode_mode`
- FP16 shader/storage support
- subgroup size `16`

`VK_EXT_ycbcr_2plane_444_formats` was not found in the captured `cmd gpu vkjson` output.

## AM8 Pro Evidence

The AM8 Pro is not connected in the current ADB device list. The only non-AM9 connected device was:

```text
192.168.50.58:5555     device product:kirkwood model:Google_TV_Streamer device:kirkwood
```

That device was not used as AM8 evidence.

## Baseline Gate

Pipeline 1, SoC DV composer downconvert, remains worth investigating on AM9 Pro because the stock firmware already loads `dovi_s6_5_15_stb26`, exposes Amlogic Dolby Vision C2 decoders, exposes Dolby Vision parser controls, and supports ADB root on this unit.

The current display path cannot prove Dolby Vision-on-wire output because the connected Samsung sink does not advertise Dolby Vision. That is no longer a blocker for the primary gate. DV TV testing is optional follow-up if Nexio later wants to preserve dynamic metadata over HDMI on DV-capable displays.

Pipeline 2, libplacebo pixel conversion, remains the fallback for HDR10-only displays only if pipeline 1 cannot produce correct HDR10 from stock SoC composer downconvert.
