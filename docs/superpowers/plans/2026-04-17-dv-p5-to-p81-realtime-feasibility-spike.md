# DV Profile 5 To Profile 8.1 Realtime Playback Feasibility Spike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a go/no-go decision and production-path plan for realtime Dolby Vision Profile 5 playback with correct HDR10 output on non-Dolby Vision HDR10 TVs.

**Architecture:** Run an evidence-first feasibility spike that compares three pipelines on the attached Samsung HDR10 sink: the current metadata-only DV5 to P8.1 path, the stock Amlogic SoC Dolby Vision composer downconvert path, and a libplacebo pixel-conversion fallback. The spike does not modify Nexio playback code; it collects device facts, visual playback behavior, decoder-routing evidence, `dovi.ko` static evidence, and GPU benchmark data, then turns those facts into a production implementation plan.

**Tech Stack:** Android TV, ADB, Ugoos AM8 Pro, rooted Ugoos AM9 Pro at `192.168.50.71`, Amlogic Dolby Vision sysfs, Media3 extractor Dolby Vision hooks, Nexio `DoviBridge`, vendored `dovi_tool`, CoreELEC `dovi.ko`, Vulkan 1.2, libplacebo, FFmpeg, markdown evidence reports.

---

## Scope

This is a research and planning workstream. It decides which production architecture Nexio should build next. It does not implement the production path.

This plan includes:

- Baseline AM8 Pro and AM9 Pro firmware, display, codec, sysfs, Vulkan, and EDID facts.
- Test the current metadata-only DV5 to P8.1 path as the presumed green/purple baseline on the Samsung HDR10 sink.
- Test raw/bypassed Nexio P5 playback to learn whether it already routes to Amlogic Dolby Vision decode or generic HEVC decode.
- Test VLC/MX Player/Kodi playback of the same P5 file as the stock Android SoC composer downconvert control.
- Determine whether stock Android on AM9 Pro can use the Amlogic hardware Dolby Vision composer to emit correct HDR10 for a non-DV TV.
- Use `~/Downloads/dovi.ko` as a read-only reference for Amlogic/CoreELEC Dolby Vision composer behavior.
- Benchmark whether a libplacebo P5 to BT.2020 PQ path is viable on AM8 Pro and AM9 Pro only if the stock SoC composer path is unusable or inaccessible.
- Write a final production plan with per-device go/no-go and a recommended architecture.

This plan does not:

- Modify files under `app/`, `media/`, `dovi_tool/`, or Gradle build logic.
- Improve or ship metadata-only LLDV output.
- Add AM6B Plus, Nvidia Shield, or Profile 7 FEL support.
- Require a Dolby Vision TV for the primary HDR10 correctness decision.
- Load `~/Downloads/dovi.ko` on stock Android.
- Redistribute `~/Downloads/dovi.ko` or any extracted Dolby proprietary source strings.
- Cut a release, bump versions, or edit root `CHANGELOG.md`.

## Current Code Context

The spike should reuse existing Nexio plumbing as observed in the repo:

- `app/src/main/java/com/nexio/tv/core/player/DoviBridge.kt`
  - JNI-facing bridge for realtime RPU conversion diagnostics.
  - `convertDv7RpuToDv81(payload, mode)` calls the native bridge.
  - Mode `2` is the current P8.1 conversion mode. Mode `5` preserves mapping for existing DV7 behavior.

- `app/src/main/cpp/dovi_bridge.cpp`
  - Wraps `dovi_parse_unspec62_nalu`, `dovi_convert_rpu_with_mode`, and `dovi_write_unspec62_nalu` when `DOVI_REAL_LINKED` is true.
  - Maps Kotlin mode `2` to libdovi mode `2`, and Kotlin mode `5` to libdovi mode `4`.

- `app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt`
  - Installs Matroska, MP4, fragmented MP4, and TS/H.265 Dolby Vision transformers.
  - Rewrites codec strings, rewrites length-delimited HEVC samples, converts RPU NAL units, and can feed the DV5 RPU tap.

- `app/src/main/java/com/nexio/tv/core/player/DolbyVisionHevcSampleRewriter.kt`
  - Pure HEVC sample rewriter.
  - Drops enhancement-layer NAL units and normalizes RPU layer IDs.

- `app/src/main/java/com/nexio/tv/core/player/Dv5HardwareToneMapRpuTap.kt`
  - Queues RPU NAL payloads by PTS and matches them in `onFrameAboutToRender`.
  - This is the existing timing model for the libplacebo fallback.

- `app/src/main/java/androidx/media3/exoplayer/video/ExperimentalDv5HardwareToneMapVideoSink.java`
  - Existing Shield-era VideoSink scaffold: MediaCodec surface decode to `ImageReader`, P010 or CPU-readable fallback, native FFmpeg bridge, output surface.
  - Reusable as evidence for architecture 2, but not changed during this spike.

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - Reads display HDR capabilities, enables `DolbyVisionCompatibility.setMapDv7ToHevcEnabled`, probes `DoviBridge`, installs extractor hooks, and gates the current DV5 hardware tonemap path to Nvidia Shield.

- `media/libraries/extractor/src/main/java/androidx/media3/extractor/*`
  - The vendored Media3 fork already exposes `DolbyVisionSampleTransformer` and `DolbyVisionNalTransformer` hooks for Matroska, MP4, fMP4, and TS.

## File Structure

Create only research artifacts under `docs/research/dv-p5-p81-feasibility/`.

- Create: `docs/research/dv-p5-p81-feasibility/README.md`
  - Human index of the spike, exact device targets, sample manifest, and final verdict links.

- Create: `docs/research/dv-p5-p81-feasibility/evidence/device-ids.env`
  - Shell-readable device identifiers discovered during Task 1.

- Create: `docs/research/dv-p5-p81-feasibility/evidence/test-media.md`
  - Exact media assets used for all playback tests.

- Create: `docs/research/dv-p5-p81-feasibility/device-baseline.md`
  - AM8 and AM9 firmware, sysfs, codec, Vulkan, EDID, and rootability findings.

- Create: `docs/research/dv-p5-p81-feasibility/tunnel-verdict.md`
  - HDR10 output correctness matrix, SoC composer downconvert evidence, decoder-routing observations, sysfs observations, `dovi.ko` context, and labelled gate.

- Create: `docs/research/dv-p5-p81-feasibility/libplacebo-benchmark.md`
  - Device by resolution benchmark table, thermal observations, zero-copy finding, and fallback viability.

- Create: `docs/research/dv-p5-p81-feasibility/production-plan.md`
  - Production-path recommendation, per-device gating, file-level production scope, settings/telemetry/rollback/test plan, and risks.

- Create directories under `docs/research/dv-p5-p81-feasibility/evidence/`
  - `am9-stock/`
  - `am9-coreelec/`
  - `am8-stock/`
  - `dovi-ko/`
  - `libplacebo/`

Do not create helper scripts in the Nexio repo. Use command transcripts and markdown reports instead.

## Research Inputs

- Rooted AM9 Pro ADB target: `192.168.50.71:5555`.
- CoreELEC Dolby Vision module reference: `/Users/jneerdael/Downloads/dovi.ko`.
- `dovi.ko` metadata already observed:
  - `file /Users/jneerdael/Downloads/dovi.ko` reports an aarch64 ELF relocatable kernel module with BuildID `11509eb66696b72bbde0fcdbffa500e6f1764eec`.
  - Strings include `description=Amlogic Dolby Vision Driver`, `name=dovi_gen_5_15_2026`, `dv_source_code/libs/vdr_rpu`, `dv_source_code/libs/dm/dlb_dm_process`, `metadata_parser_process`, `multi_control_path`, `get_hdmi_metadata_buffer`, and `set_L11_hdmi_tx`.
  - `nm --defined-only` exposes Dolby metadata parser/control-path style symbols including `dv_md_parser_process`, `rpu_decoder_process_buffer`, `get_dolby_control_data`, `get_hdmi_metadata_buffer`, `set_dovi2hdr10_mapping`, and `set_L11_hdmi_tx`.

---

### Task 1: Create Evidence Workspace And Device Manifest

**Files:**
- Create: `docs/research/dv-p5-p81-feasibility/README.md`
- Create: `docs/research/dv-p5-p81-feasibility/evidence/device-ids.env`
- Create: `docs/research/dv-p5-p81-feasibility/evidence/test-media.md`

- [ ] **Step 1: Verify clean production-code boundary**

Run:

```bash
git status --short app media dovi_tool app/build.gradle.kts
```

Expected: no output, or only unrelated pre-existing user changes that are recorded in the task notes before continuing.

- [ ] **Step 2: Create the evidence directories**

Run:

```bash
mkdir -p docs/research/dv-p5-p81-feasibility/evidence/am9-stock
mkdir -p docs/research/dv-p5-p81-feasibility/evidence/am9-coreelec
mkdir -p docs/research/dv-p5-p81-feasibility/evidence/am8-stock
mkdir -p docs/research/dv-p5-p81-feasibility/evidence/dovi-ko
mkdir -p docs/research/dv-p5-p81-feasibility/evidence/libplacebo
```

Expected: all five directories exist.

- [ ] **Step 3: Connect the rooted AM9 Pro**

Run:

```bash
adb connect 192.168.50.71
adb devices -l
```

Expected: `192.168.50.71:5555` appears as `device`. If it appears as `unauthorized`, accept the RSA prompt on the AM9 Pro and run the commands again.

- [ ] **Step 4: Record device identifiers**

Run:

```bash
printf 'AM9_ADB=192.168.50.71:5555\n' > docs/research/dv-p5-p81-feasibility/evidence/device-ids.env
adb devices -l | tee docs/research/dv-p5-p81-feasibility/evidence/adb-devices-initial.txt
```

Then append the AM8 Pro identifier after connecting it over USB or TCP. This command selects the first connected device that is not the AM9 Pro:

```bash
adb devices -l
printf 'AM8_ADB=%s\n' "$(adb devices | awk '$1 ~ /device$/ {next} $2 == "device" && $1 != "192.168.50.71:5555" {print $1; exit}')" >> docs/research/dv-p5-p81-feasibility/evidence/device-ids.env
```

Verify `docs/research/dv-p5-p81-feasibility/evidence/device-ids.env` contains exactly two non-empty shell assignments:

```bash
grep -E '^AM9_ADB=192\.168\.50\.71:5555$' docs/research/dv-p5-p81-feasibility/evidence/device-ids.env
grep -E '^AM8_ADB=.+$' docs/research/dv-p5-p81-feasibility/evidence/device-ids.env
```

Expected: `source docs/research/dv-p5-p81-feasibility/evidence/device-ids.env` succeeds in the current shell, and `adb -s "$AM9_ADB" get-state` prints `device`.

- [ ] **Step 5: Create the media manifest**

Create `docs/research/dv-p5-p81-feasibility/evidence/test-media.md` with the exact playback assets selected for the spike:

```markdown
# Test Media Manifest

## Required Assets

| ID | Required type | Exact source URI or file path | Container | Notes |
|----|---------------|-------------------------------|-----------|-------|
| p5-mkv | Dolby Vision Profile 5 MKV | | MKV | Primary metadata-only baseline and SoC composer downconvert test |
| p5-mp4 | Dolby Vision Profile 5 MP4 | | MP4 | MP4 extractor and dvcC/dvvC handoff test |
| p5-streaming | Dolby Vision Profile 5 DASH or HLS segment set | | DASH/HLS | Fragmented MP4 or TS extractor coverage |
| p81-reference | Native Profile 8.1 reference from the same title or same-master comparison asset | | MKV or MP4 | Visual and TV InfoFrame reference |
| hdr10-only-display-sample | HDR10-only display playback sample, can reuse p5-mkv or p5-mp4 | | MKV or MP4 | Confirms fallback behavior on a non-DV sink |

## Displays

| ID | Type | Exact model | HDMI path | Dolby Vision advertised mode |
|----|------|-------------|-----------|------------------------------|
| dv-tv | Dolby Vision TV | | | |
| hdr10-tv | HDR10-only TV | | | none |
```

Fill every blank cell before Task 2 begins. Use exact URIs or local file paths, not title names alone.

- [ ] **Step 6: Create the spike index**

Create `docs/research/dv-p5-p81-feasibility/README.md`:

```markdown
# DV P5 To P8.1 Realtime Playback Feasibility Spike

## Decision Bar

Nexio only accepts a path that produces correct HDR10 colors from Dolby Vision Profile 5 on non-Dolby Vision HDR10 TVs. Metadata-only output that shows green/purple on an HDR10 sink is not acceptable.

Dolby Vision over HDMI is a bonus follow-up gate, not a blocker for the primary correctness decision.

## Candidate Architectures

1. Current metadata-only DV5 to P8.1 path: verify the presumed green/purple baseline on AM9 plus Samsung.
2. SoC DV composer downconvert: route P5 to the Amlogic Dolby Vision decoder/composer and let stock firmware emit HDR10 for the attached non-DV sink.
3. libplacebo pixel conversion fallback: decode Profile 5 as Main10, reshape and convert IPT to BT.2020 PQ on GPU, and output HDR10 RGB.

## Evidence

- Device baseline: `device-baseline.md`
- HDR10 correctness verdict: `tunnel-verdict.md`
- libplacebo benchmark: `libplacebo-benchmark.md`
- Production plan: `production-plan.md`
- Device identifiers: `evidence/device-ids.env`
- Test media: `evidence/test-media.md`

## Known Inputs

- AM9 Pro rooted ADB target: `192.168.50.71:5555`
- CoreELEC Dolby Vision module reference: `/Users/jneerdael/Downloads/dovi.ko`
```

- [ ] **Step 7: Commit the evidence workspace**

Run:

```bash
git add docs/research/dv-p5-p81-feasibility/README.md docs/research/dv-p5-p81-feasibility/evidence/device-ids.env docs/research/dv-p5-p81-feasibility/evidence/test-media.md
git commit -m "docs: set up dv p5 feasibility spike workspace"
```

Expected: commit succeeds with only `docs/research/dv-p5-p81-feasibility/` files staged.

---

### Task 2: Baseline AM9 Pro And AM8 Pro Device Capabilities

**Files:**
- Create: `docs/research/dv-p5-p81-feasibility/device-baseline.md`
- Write evidence under:
  - `docs/research/dv-p5-p81-feasibility/evidence/am9-stock/`
  - `docs/research/dv-p5-p81-feasibility/evidence/am8-stock/`

- [ ] **Step 1: Load device identifiers**

Run:

```bash
source docs/research/dv-p5-p81-feasibility/evidence/device-ids.env
adb -s "$AM9_ADB" get-state
adb -s "$AM8_ADB" get-state
```

Expected: both commands print `device`.

- [ ] **Step 2: Capture AM9 firmware and platform properties**

Run:

```bash
adb -s "$AM9_ADB" shell getprop > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/getprop.txt
adb -s "$AM9_ADB" shell uname -a > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/uname.txt
adb -s "$AM9_ADB" shell id > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/shell-id.txt
adb -s "$AM9_ADB" shell su -c id > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/su-id.txt
adb -s "$AM9_ADB" shell lsmod > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/lsmod.txt
```

Expected: `su-id.txt` shows `uid=0`, and `lsmod.txt` is captured even if no Dolby Vision module name appears.

- [ ] **Step 3: Capture AM8 firmware and platform properties**

Run:

```bash
adb -s "$AM8_ADB" shell getprop > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/getprop.txt
adb -s "$AM8_ADB" shell uname -a > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/uname.txt
adb -s "$AM8_ADB" shell id > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/shell-id.txt
adb -s "$AM8_ADB" shell su -c id > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/su-id.txt
adb -s "$AM8_ADB" shell lsmod > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/lsmod.txt
```

Expected: files are captured. If AM8 is not rooted, `su-id.txt` contains the exact failure text.

- [ ] **Step 4: Capture display and media service state**

Run for AM9:

```bash
adb -s "$AM9_ADB" shell dumpsys SurfaceFlinger > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/surfaceflinger.txt
adb -s "$AM9_ADB" shell dumpsys SurfaceFlinger | grep -iE "hdr|dolby|color" > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/surfaceflinger-hdr-color.txt
adb -s "$AM9_ADB" shell cmd media.player > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/cmd-media-player.txt
adb -s "$AM9_ADB" shell dumpsys media.player > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/dumpsys-media-player.txt
adb -s "$AM9_ADB" shell dumpsys media.codec > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/dumpsys-media-codec.txt
```

Run for AM8:

```bash
adb -s "$AM8_ADB" shell dumpsys SurfaceFlinger > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/surfaceflinger.txt
adb -s "$AM8_ADB" shell dumpsys SurfaceFlinger | grep -iE "hdr|dolby|color" > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/surfaceflinger-hdr-color.txt
adb -s "$AM8_ADB" shell cmd media.player > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/cmd-media-player.txt
adb -s "$AM8_ADB" shell dumpsys media.player > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/dumpsys-media-player.txt
adb -s "$AM8_ADB" shell dumpsys media.codec > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/dumpsys-media-codec.txt
```

Expected: HDR/Dolby/color capability lines are present or the report explicitly records that the command returned no matching lines.

- [ ] **Step 5: Capture sysfs Dolby Vision and HDMI state**

Run for AM9:

```bash
adb -s "$AM9_ADB" shell ls -R /sys/module/amdolby_vision/parameters/ > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/amdolby-parameters-ls.txt
adb -s "$AM9_ADB" shell ls -R /sys/class/amhdmitx/amhdmitx0/ > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/amhdmitx-ls.txt
adb -s "$AM9_ADB" shell cat /sys/class/amhdmitx/amhdmitx0/dv_cap > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/dv-cap.txt
adb -s "$AM9_ADB" shell cat /sys/class/amhdmitx/amhdmitx0/hdr_cap > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/hdr-cap.txt
adb -s "$AM9_ADB" shell cat /sys/class/amhdmitx/amhdmitx0/attr > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/attr.txt
adb -s "$AM9_ADB" shell cat /sys/class/amhdmitx/amhdmitx0/config > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/config.txt
adb -s "$AM9_ADB" shell dmesg | grep -iE "avc|denied|dolby|dovi|amdv|amdolby" > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/dmesg-dovi-selinux.txt
```

Run for AM8:

```bash
adb -s "$AM8_ADB" shell ls -R /sys/module/amdolby_vision/parameters/ > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/amdolby-parameters-ls.txt
adb -s "$AM8_ADB" shell ls -R /sys/class/amhdmitx/amhdmitx0/ > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/amhdmitx-ls.txt
adb -s "$AM8_ADB" shell cat /sys/class/amhdmitx/amhdmitx0/dv_cap > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/dv-cap.txt
adb -s "$AM8_ADB" shell cat /sys/class/amhdmitx/amhdmitx0/hdr_cap > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/hdr-cap.txt
adb -s "$AM8_ADB" shell cat /sys/class/amhdmitx/amhdmitx0/attr > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/attr.txt
adb -s "$AM8_ADB" shell cat /sys/class/amhdmitx/amhdmitx0/config > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/config.txt
adb -s "$AM8_ADB" shell dmesg | grep -iE "avc|denied|dolby|dovi|amdv|amdolby" > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/dmesg-dovi-selinux.txt
```

Expected: successful reads produce captured text. Permission failures are kept as evidence in the corresponding file by rerunning the failing command with `2>&1` and overwriting that one file.

- [ ] **Step 6: Capture every readable AM9 Dolby Vision sysfs parameter**

Run:

```bash
adb -s "$AM9_ADB" shell 'for f in /sys/module/amdolby_vision/parameters/*; do echo "### $f"; cat "$f"; done' > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/amdolby-parameters-values.txt
```

Expected: file includes parameter names such as `dolby_vision_mode`, `dolby_vision_policy`, `dolby_vision_flags`, `dolby_vision_use_source_meta_levels`, or exact failure text if the path is absent.

- [ ] **Step 7: Capture Vulkan facts**

Run for AM9:

```bash
adb -s "$AM9_ADB" shell vulkaninfo --summary > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/vulkaninfo-summary.txt
```

Run for AM8:

```bash
adb -s "$AM8_ADB" shell vulkaninfo --summary > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/vulkaninfo-summary.txt
```

Expected: each file reports API version, GPU name, driver version, and extension summaries. If `vulkaninfo` is not present, capture the exact error and use the libplacebo probe in Task 6 as the authoritative Vulkan runtime test.

- [ ] **Step 8: Capture EDID for both display classes**

For each device and each attached TV, run:

```bash
adb -s "$AM9_ADB" shell cat /sys/class/amhdmitx/amhdmitx0/edid > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/edid-current-display.bin
adb -s "$AM8_ADB" shell cat /sys/class/amhdmitx/amhdmitx0/edid > docs/research/dv-p5-p81-feasibility/evidence/am8-stock/edid-current-display.bin
```

Rename the resulting files after capture so the names include `dv-tv` or `hdr10-tv`, for example `edid-dv-tv.bin` and `edid-hdr10-tv.bin`.

Expected: at least the attached Samsung HDR10-only TV EDID is present before writing the primary verdict. Dolby Vision TV EDID is optional follow-up evidence for DV-on-wire behavior.

- [ ] **Step 9: Write the baseline report**

Create `docs/research/dv-p5-p81-feasibility/device-baseline.md`:

```markdown
# Device Baseline

## Summary

| Device | ADB target | SoC | GPU | Android | Kernel | Root | DV sysfs visible to shell | DV sysfs visible to root | Stock DV module evidence | Vulkan API | HDR10 TV EDID | Optional DV TV EDID |
|--------|------------|-----|-----|---------|--------|------|---------------------------|--------------------------|--------------------------|------------|------------|---------------|
| AM9 Pro | 192.168.50.71:5555 | S905X5-J | Mali-G310 V2 | | | yes | | | | | | |
| AM8 Pro | value from evidence/device-ids.env | S928X-J | Mali-G57 MC2 | | | | | | | | | |

## AM9 Pro Evidence

- Firmware and Android version: see `evidence/am9-stock/getprop.txt`
- Kernel: see `evidence/am9-stock/uname.txt`
- Root: see `evidence/am9-stock/su-id.txt`
- SurfaceFlinger HDR/color: see `evidence/am9-stock/surfaceflinger-hdr-color.txt`
- Media services: see `evidence/am9-stock/cmd-media-player.txt` and `evidence/am9-stock/dumpsys-media-player.txt`
- Codec list: see `evidence/am9-stock/dumpsys-media-codec.txt`
- Dolby Vision sysfs: see `evidence/am9-stock/amdolby-parameters-ls.txt` and `evidence/am9-stock/amdolby-parameters-values.txt`
- HDMI caps: see `evidence/am9-stock/dv-cap.txt`, `evidence/am9-stock/hdr-cap.txt`, `evidence/am9-stock/attr.txt`, and `evidence/am9-stock/config.txt`
- Vulkan: see `evidence/am9-stock/vulkaninfo-summary.txt`

## AM8 Pro Evidence

- Firmware and Android version: see `evidence/am8-stock/getprop.txt`
- Kernel: see `evidence/am8-stock/uname.txt`
- Root: see `evidence/am8-stock/su-id.txt`
- SurfaceFlinger HDR/color: see `evidence/am8-stock/surfaceflinger-hdr-color.txt`
- Media services: see `evidence/am8-stock/cmd-media-player.txt` and `evidence/am8-stock/dumpsys-media-player.txt`
- Codec list: see `evidence/am8-stock/dumpsys-media-codec.txt`
- Dolby Vision sysfs: see `evidence/am8-stock/amdolby-parameters-ls.txt`
- HDMI caps: see `evidence/am8-stock/dv-cap.txt`, `evidence/am8-stock/hdr-cap.txt`, `evidence/am8-stock/attr.txt`, and `evidence/am8-stock/config.txt`
- Vulkan: see `evidence/am8-stock/vulkaninfo-summary.txt`

## Baseline Gate

Primary pipeline priority after baseline:

- Promote SoC DV composer downconvert when AM9 exposes Dolby Vision decode/composer controls and stock Android playback can emit correct HDR10 to the Samsung sink.
- Demote SoC DV composer downconvert when stock players cannot produce correct HDR10 from the same P5 file or Nexio cannot route to the Amlogic DV decoder/composer without root-only controls.
- Keep libplacebo fallback mandatory only when the SoC composer downconvert path fails, is inaccessible from app playback, or is too device-fragile to ship.
- Treat Dolby Vision TV validation as optional follow-up for DV-on-wire behavior, not as a blocker for the HDR10 correctness gate.
```

Fill the summary table from captured files. Keep evidence links relative and exact.

- [ ] **Step 10: Verify no production files changed**

Run:

```bash
git status --short app media dovi_tool app/build.gradle.kts
```

Expected: no output, or only the unrelated pre-existing user changes recorded in Task 1.

- [ ] **Step 11: Commit the baseline report**

Run:

```bash
git add docs/research/dv-p5-p81-feasibility/device-baseline.md docs/research/dv-p5-p81-feasibility/evidence/am9-stock docs/research/dv-p5-p81-feasibility/evidence/am8-stock
git commit -m "docs: baseline ugoos dolby vision devices"
```

Expected: commit includes only research evidence and the baseline report.

---

### Task 3: Establish Samsung HDR10 Visual Baseline On AM9 Pro

**Files:**
- Modify: `docs/research/dv-p5-p81-feasibility/tunnel-verdict.md`
- Write evidence under: `docs/research/dv-p5-p81-feasibility/evidence/am9-stock/`

- [ ] **Step 1: Build the existing debug APK without production code edits**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: build succeeds and produces `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Install on the AM9 Pro**

Run:

```bash
source docs/research/dv-p5-p81-feasibility/evidence/device-ids.env
adb -s "$AM9_ADB" install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Success`.

- [ ] **Step 3: Run pipeline 0A with the current metadata-only baseline enabled**

On the AM9 Pro, open Nexio settings and enable:

```text
experimentalDv7ToDv81Enabled=true
experimentalDv5ToDv81Enabled=true
experimentalDv5HardwareToneMapToSdrEnabled=false
experimentalDv5HardwareToneMapCpuFallbackEnabled=false
experimentalDv7HevcBaseLayerEnabled=false
```

Expected: the subsequent playback log includes `DV7_DOVI:` with `dv5Compat=true`, and `Dv7ExtractorHook` logs show the extractor hook is installed.

- [ ] **Step 4: Capture pipeline 0A playback logs and visual result**

Run before playback:

```bash
adb -s "$AM9_ADB" logcat -c
```

Play `p5-mkv` from `evidence/test-media.md` for at least 120 seconds on the attached Samsung HDR10-only TV.

Run after playback:

```bash
adb -s "$AM9_ADB" logcat -d -v threadtime > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-metadata-baseline-logcat.txt
adb -s "$AM9_ADB" shell dumpsys SurfaceFlinger > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-metadata-baseline-surfaceflinger.txt
adb -s "$AM9_ADB" shell cat /sys/class/amhdmitx/amhdmitx0/attr > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-metadata-baseline-attr.txt
adb -s "$AM9_ADB" shell cat /sys/class/amhdmitx/amhdmitx0/config > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-metadata-baseline-config.txt
adb -s "$AM9_ADB" shell cat /sys/module/aml_media/parameters/dolby_vision_enable > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-metadata-baseline-dolby_vision_enable.txt
adb -s "$AM9_ADB" shell cat /sys/module/aml_media/parameters/dolby_vision_status > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-metadata-baseline-dolby_vision_status.txt
```

Expected: logcat contains `PlayerRuntimeController`, `DV7_DOVI`, `Dv7ExtractorHook`, `DoviBridge`, `DoviBridgeNative`, and MediaCodec decoder-selection lines. Record the TV OSD and visual result in `tunnel-verdict.md`; the important visual labels are `correct HDR10`, `green/purple`, `SDR-looking`, `black/no video`, or the exact observed failure.

- [ ] **Step 5: Run pipeline 0B with Nexio DV5 to P8.1 conversion disabled**

On the AM9 Pro, open Nexio settings and set:

```text
experimentalDv5ToDv81Enabled=false
experimentalDv7ToDv81Enabled=false
experimentalDv5HardwareToneMapToSdrEnabled=false
experimentalDv5HardwareToneMapCpuFallbackEnabled=false
experimentalDv7HevcBaseLayerEnabled=false
```

Run before playback:

```bash
adb -s "$AM9_ADB" logcat -c
```

Play the same `p5-mkv` from `evidence/test-media.md` for at least 120 seconds on the attached Samsung HDR10-only TV.

Run after playback:

```bash
adb -s "$AM9_ADB" logcat -d -v threadtime > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-nexio-raw-logcat.txt
adb -s "$AM9_ADB" shell dumpsys SurfaceFlinger > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-nexio-raw-surfaceflinger.txt
adb -s "$AM9_ADB" shell cat /sys/class/amhdmitx/amhdmitx0/attr > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-nexio-raw-attr.txt
adb -s "$AM9_ADB" shell cat /sys/class/amhdmitx/amhdmitx0/config > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-nexio-raw-config.txt
adb -s "$AM9_ADB" shell cat /sys/module/aml_media/parameters/dolby_vision_enable > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-nexio-raw-dolby_vision_enable.txt
adb -s "$AM9_ADB" shell cat /sys/module/aml_media/parameters/dolby_vision_status > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-nexio-raw-dolby_vision_status.txt
```

Expected: this identifies whether Nexio already routes raw P5 to the Amlogic Dolby Vision decoder/composer, forces generic HEVC, or fails before video output.

- [ ] **Step 6: Run pipeline 1A through a stock Android player**

Install or open one stock-control player available on the AM9 Pro: VLC, MX Player, or Kodi. Play the same `p5-mkv` on the attached Samsung HDR10-only TV.

Run before playback:

```bash
adb -s "$AM9_ADB" logcat -c
```

Run after at least 120 seconds of playback:

```bash
adb -s "$AM9_ADB" logcat -d -v threadtime > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-stock-player-logcat.txt
adb -s "$AM9_ADB" shell dumpsys SurfaceFlinger > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-stock-player-surfaceflinger.txt
adb -s "$AM9_ADB" shell cat /sys/class/amhdmitx/amhdmitx0/attr > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-stock-player-attr.txt
adb -s "$AM9_ADB" shell cat /sys/class/amhdmitx/amhdmitx0/config > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-stock-player-config.txt
adb -s "$AM9_ADB" shell cat /sys/module/aml_media/parameters/dolby_vision_enable > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-stock-player-dolby_vision_enable.txt
adb -s "$AM9_ADB" shell cat /sys/module/aml_media/parameters/dolby_vision_status > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-stock-player-dolby_vision_status.txt
```

Expected: if the stock player produces correct HDR10 where Nexio does not, pipeline 1 is viable and the production work should prioritize decoder/MIME routing.

- [ ] **Step 7: Start the HDR10 correctness verdict report**

Create `docs/research/dv-p5-p81-feasibility/tunnel-verdict.md`:

```markdown
# HDR10 Output Correctness Verdict

## Visual Baseline Matrix

| Case | Device | Display | Source | Nexio setting | TV signal string | Visual result | SurfaceFlinger state | HDMI attr/config | Log evidence |
|------|--------|---------|--------|---------------|------------------|---------------|----------------------|------------------|--------------|
| pipeline-0A | AM9 Pro | Samsung HDR10-only sink | p5-mkv | DV5 to P8.1 metadata-only enabled | | | `evidence/am9-stock/p5-mkv-metadata-baseline-surfaceflinger.txt` | `evidence/am9-stock/p5-mkv-metadata-baseline-attr.txt`, `evidence/am9-stock/p5-mkv-metadata-baseline-config.txt` | `evidence/am9-stock/p5-mkv-metadata-baseline-logcat.txt` |
| pipeline-0B | AM9 Pro | Samsung HDR10-only sink | p5-mkv | DV5 to P8.1 disabled | | | `evidence/am9-stock/p5-mkv-nexio-raw-surfaceflinger.txt` | `evidence/am9-stock/p5-mkv-nexio-raw-attr.txt`, `evidence/am9-stock/p5-mkv-nexio-raw-config.txt` | `evidence/am9-stock/p5-mkv-nexio-raw-logcat.txt` |
| pipeline-1A | AM9 Pro | Samsung HDR10-only sink | p5-mkv | stock Android player | | | `evidence/am9-stock/p5-mkv-stock-player-surfaceflinger.txt` | `evidence/am9-stock/p5-mkv-stock-player-attr.txt`, `evidence/am9-stock/p5-mkv-stock-player-config.txt` | `evidence/am9-stock/p5-mkv-stock-player-logcat.txt` |

## Baseline Conclusion

Record whether each path is correct HDR10, green/purple, SDR-looking, black/no video, or another exact observed failure. The primary gate is correct HDR10 on this Samsung non-DV sink.
```

Fill the blank table cells from the TV OSD and visual observation immediately after playback.

- [ ] **Step 8: Verify no production files changed**

Run:

```bash
git status --short app media dovi_tool app/build.gradle.kts
```

Expected: no output, or only unrelated pre-existing user changes.

- [ ] **Step 9: Commit the baseline playback evidence**

Run:

```bash
git add docs/research/dv-p5-p81-feasibility/tunnel-verdict.md docs/research/dv-p5-p81-feasibility/evidence/am9-stock
git commit -m "docs: capture am9 dv metadata baseline"
```

Expected: commit includes only research evidence and the HDR10 correctness verdict draft.

---

### Task 4: Answer Nexio MIME And SoC Composer Routing

**Files:**
- Modify: `docs/research/dv-p5-p81-feasibility/tunnel-verdict.md`
- Write evidence under: `docs/research/dv-p5-p81-feasibility/evidence/am9-stock/`

- [ ] **Step 1: Define the decoder-routing matrix in the report**

Append this section to `docs/research/dv-p5-p81-feasibility/tunnel-verdict.md`:

```markdown
## MIME And Decoder Routing Matrix

| Case | Player/path | Source asset | Format MIME/codecs seen by Android | Selected decoder | Dolby sysfs status | TV signal | Visual result | Interpretation |
|------|-------------|--------------|-------------------------------------|------------------|---------------------|-----------|---------------|----------------|
| R0 | Nexio metadata-only | p5-mkv | | | | | | |
| R1 | Nexio raw/bypass | p5-mkv | | | | | | |
| R2 | VLC/MX/Kodi stock control | p5-mkv | | | | | | |
```

- [ ] **Step 2: Capture exact media metadata for the single P5 input**

Use `evidence/test-media.md` to define the exact `p5-mkv` source. Store any local copies outside the Nexio repo under `/Users/jneerdael/Scripts/dv-p5-p81-samples/`.

Run:

```bash
mkdir -p /Users/jneerdael/Scripts/dv-p5-p81-samples
ffmpeg -version > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/ffmpeg-version.txt
```

Expected: `ffmpeg` version is captured. If `ffmpeg` is unavailable, install it outside this repo before continuing.

Create `docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-input-file.txt` with one exact local file path for the P5 test file. Then run:

```bash
P5_INPUT="$(cat docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-input-file.txt)"
shasum -a 256 "$P5_INPUT" > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-input-sha256.txt
ffprobe -hide_banner -show_format -show_streams "$P5_INPUT" > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-input-ffprobe.txt
```

Expected: `p5-input-ffprobe.txt` records Dolby Vision Profile 5 stream metadata.

- [ ] **Step 3: Extract decoder selection from the three baseline logs**

Run:

```bash
grep -iE "MediaCodec|CCodec|c2\\.amlogic|dolby-vision|video/dolby-vision|video/hevc|OMX|decoder" docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-metadata-baseline-logcat.txt > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-metadata-baseline-decoder-lines.txt
grep -iE "MediaCodec|CCodec|c2\\.amlogic|dolby-vision|video/dolby-vision|video/hevc|OMX|decoder" docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-nexio-raw-logcat.txt > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-nexio-raw-decoder-lines.txt
grep -iE "MediaCodec|CCodec|c2\\.amlogic|dolby-vision|video/dolby-vision|video/hevc|OMX|decoder" docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-stock-player-logcat.txt > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/p5-mkv-stock-player-decoder-lines.txt
```

Expected: each file shows whether playback used `c2.amlogic.dolby-vision.dvhe.decoder`, generic `c2.amlogic.hevc.decoder`, or another decoder.

- [ ] **Step 4: Interpret the routing matrix**

Append:

```markdown
## Routing Interpretation

Answer these questions from the table above:

1. Does Nexio route P5 as `video/dolby-vision` to `c2.amlogic.dolby-vision.dvhe.decoder` when the metadata-only toggle is on?
2. Does Nexio route raw P5 to the Amlogic DV decoder or generic HEVC when the toggle is off?
3. Does the stock player route the same file to the Amlogic DV decoder?
4. Does `dolby_vision_status` or `dolby_vision_enable` change during any path?
5. Which path, if any, produces correct HDR10 on the Samsung display?
```

Fill each answer with the observed evidence file paths.

- [ ] **Step 7: Verify no production files changed**

Run:

```bash
git status --short app media dovi_tool app/build.gradle.kts
```

Expected: no output, or only unrelated pre-existing user changes.

- [ ] **Step 8: Commit the routing matrix evidence**

Run:

```bash
git add docs/research/dv-p5-p81-feasibility/tunnel-verdict.md docs/research/dv-p5-p81-feasibility/evidence/am9-stock
git commit -m "docs: evaluate am9 p5 hdr10 routing"
```

Expected: commit contains matrix evidence and report updates only.

---

### Task 5: Compare sysfs And dovi.ko SoC Composer Evidence

**Files:**
- Modify: `docs/research/dv-p5-p81-feasibility/tunnel-verdict.md`
- Write evidence under:
  - `docs/research/dv-p5-p81-feasibility/evidence/am9-stock/`
  - `docs/research/dv-p5-p81-feasibility/evidence/am9-coreelec/`
  - `docs/research/dv-p5-p81-feasibility/evidence/dovi-ko/`

- [ ] **Step 1: Record AM9 stock sysfs read/write facts without changing values**

Run:

```bash
source docs/research/dv-p5-p81-feasibility/evidence/device-ids.env
adb -s "$AM9_ADB" shell 'for f in /sys/module/aml_media/parameters/*dolby* /sys/module/aml_media/parameters/amdv_* /sys/module/amvdec_h265/parameters/*dolby* /sys/module/amvdec_h265_v4l/parameters/*dolby* /sys/module/amvdec_h265/parameters/parser_dolby_vision_enable /sys/module/amvdec_h265_v4l/parameters/parser_dolby_vision_enable; do echo "### $f"; ls -l "$f"; cat "$f"; done' > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/dolby-amdv-parameter-permissions-and-values.txt
adb -s "$AM9_ADB" root
adb -s "$AM9_ADB" wait-for-device
adb -s "$AM9_ADB" shell 'for f in /sys/module/aml_media/parameters/*dolby* /sys/module/aml_media/parameters/amdv_* /sys/module/amvdec_h265/parameters/*dolby* /sys/module/amvdec_h265_v4l/parameters/*dolby* /sys/module/amvdec_h265/parameters/parser_dolby_vision_enable /sys/module/amvdec_h265_v4l/parameters/parser_dolby_vision_enable; do echo "### $f"; ls -l "$f"; cat "$f"; done' > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/dolby-amdv-parameter-root-values.txt
```

Expected: the report can distinguish regular shell visibility from root visibility. Do not write to any sysfs entry in this task.

- [ ] **Step 2: If sysfs writes are considered, run a read-restore-only probe**

Only execute this step if Task 2 showed root write access and the current value can be restored immediately.

For each candidate parameter, capture the current value:

```bash
adb -s "$AM9_ADB" shell cat /sys/module/aml_media/parameters/dolby_vision_mode > docs/research/dv-p5-p81-feasibility/evidence/am9-stock/dolby_vision_mode-before.txt
```

If a write is performed during the spike, record the exact value before and after, then restore the original value in the same shell session:

```bash
adb -s "$AM9_ADB" shell 'ORIG=$(cat /sys/module/aml_media/parameters/dolby_vision_mode); echo "$ORIG"; echo "$ORIG" > /sys/module/aml_media/parameters/dolby_vision_mode; cat /sys/module/aml_media/parameters/dolby_vision_mode'
```

Expected: no persistent sysfs state changes remain after the command. Record the output in `tunnel-verdict.md`. If this step is skipped, record `No sysfs writes performed` in the report.

- [ ] **Step 3: Optionally boot AM9 Pro into CoreELEC for later DV-on-wire context**

Skip this step for the primary HDR10 correctness gate unless review explicitly asks for CoreELEC context. If run later, boot CoreELEC from USB on the AM9 Pro and use the same `p5-mkv` asset from `test-media.md`.

Capture CoreELEC evidence into `docs/research/dv-p5-p81-feasibility/evidence/am9-coreelec/`:

```bash
uname -a > docs/research/dv-p5-p81-feasibility/evidence/am9-coreelec/uname.txt
lsmod > docs/research/dv-p5-p81-feasibility/evidence/am9-coreelec/lsmod.txt
dmesg > docs/research/dv-p5-p81-feasibility/evidence/am9-coreelec/dmesg.txt
```

If CoreELEC exposes the same HDMI sysfs paths, capture:

```bash
cat /sys/class/amhdmitx/amhdmitx0/dv_cap > docs/research/dv-p5-p81-feasibility/evidence/am9-coreelec/dv-cap.txt
cat /sys/class/amhdmitx/amhdmitx0/hdr_cap > docs/research/dv-p5-p81-feasibility/evidence/am9-coreelec/hdr-cap.txt
cat /sys/class/amhdmitx/amhdmitx0/attr > docs/research/dv-p5-p81-feasibility/evidence/am9-coreelec/attr.txt
cat /sys/class/amhdmitx/amhdmitx0/config > docs/research/dv-p5-p81-feasibility/evidence/am9-coreelec/config.txt
```

Expected: the report records whether CoreELEC produces correct HDR10 on a non-DV sink and, if a DV TV is attached later, whether it can also produce Dolby Vision STD/LL on the wire.

- [ ] **Step 4: Hash and inspect dovi.ko without loading it**

Run:

```bash
shasum -a 256 /Users/jneerdael/Downloads/dovi.ko > docs/research/dv-p5-p81-feasibility/evidence/dovi-ko/dovi-ko-sha256.txt
file /Users/jneerdael/Downloads/dovi.ko > docs/research/dv-p5-p81-feasibility/evidence/dovi-ko/dovi-ko-file.txt
modinfo /Users/jneerdael/Downloads/dovi.ko > docs/research/dv-p5-p81-feasibility/evidence/dovi-ko/dovi-ko-modinfo.txt
nm --defined-only /Users/jneerdael/Downloads/dovi.ko > docs/research/dv-p5-p81-feasibility/evidence/dovi-ko/dovi-ko-defined-symbols.txt
strings /Users/jneerdael/Downloads/dovi.ko | grep -iE "dovi|dolby|amdv|vision|vdr|rpu|dm|hdmi|metadata" > docs/research/dv-p5-p81-feasibility/evidence/dovi-ko/dovi-ko-relevant-strings.txt
```

Expected:

- `dovi-ko-file.txt` identifies an aarch64 kernel module.
- `dovi-ko-defined-symbols.txt` includes control-path symbols such as `metadata_parser_process`, `multi_control_path`, `get_dolby_control_data`, or `get_hdmi_metadata_buffer`.
- `dovi-ko-relevant-strings.txt` confirms the module embeds Dolby Vision parser and display management library paths.

- [ ] **Step 5: Cross-reference dovi.ko with stock firmware evidence**

Append:

```markdown
## CoreELEC And dovi.ko Evidence

| Question | Evidence | Finding |
|----------|----------|---------|
| Does stock Android expose Dolby Vision composer controls for HDR10 downconvert? | `evidence/am9-stock/dolby-amdv-parameter-permissions-and-values.txt` | |
| Does root on stock Android expose more than shell? | `evidence/am9-stock/dolby-amdv-parameter-root-values.txt` | |
| Does `dovi.ko` contain Dolby parser/control-path code consistent with Amlogic DV composer behavior? | `evidence/dovi-ko/dovi-ko-defined-symbols.txt`, `evidence/dovi-ko/dovi-ko-relevant-strings.txt` | |
| Would a shipping Android app need root, a custom kernel, or a vendor API for HDR10 downconvert? | combined playback and sysfs evidence | |
| Does CoreELEC add information needed for the primary HDR10 gate? | `evidence/am9-coreelec/` | optional; not required unless stock Android playback evidence conflicts |
```

Fill each finding with concrete evidence. Do not paste long proprietary strings from `dovi.ko`; summarize symbol categories and source-path evidence.

- [ ] **Step 6: Write the HDR10 correctness gate**

Append:

```markdown
## HDR10 Correctness Gate

Verdict label:

- `(a) stock SoC composer downconvert works from current Nexio`
- `(b) stock SoC composer downconvert works in other Android players and Nexio can likely route to it`
- `(c) stock SoC composer downconvert is inaccessible or incorrect from app playback; use libplacebo`
- `(d) current metadata-only path unexpectedly produces correct HDR10 on AM9/Samsung`
- `(e) no tested path produces correct HDR10 yet`

Selected label:

Evidence summary:

Production implication:
```

Fill `Selected label`, `Evidence summary`, and `Production implication` from the visual baseline, decoder-routing matrix, sysfs, and `dovi.ko` findings.

- [ ] **Step 7: Verify no production files changed**

Run:

```bash
git status --short app media dovi_tool app/build.gradle.kts
```

Expected: no output, or only unrelated pre-existing user changes.

- [ ] **Step 8: Commit the HDR10 correctness verdict**

Run:

```bash
git add docs/research/dv-p5-p81-feasibility/tunnel-verdict.md docs/research/dv-p5-p81-feasibility/evidence/am9-stock docs/research/dv-p5-p81-feasibility/evidence/am9-coreelec docs/research/dv-p5-p81-feasibility/evidence/dovi-ko
git commit -m "docs: decide am9 p5 hdr10 feasibility"
```

Expected: commit contains research evidence and the completed HDR10 correctness verdict only.

---

### Task 6: Benchmark libplacebo Pixel Conversion Fallback

**Files:**
- Create: `docs/research/dv-p5-p81-feasibility/libplacebo-benchmark.md`
- Write evidence under: `docs/research/dv-p5-p81-feasibility/evidence/libplacebo/`

- [ ] **Step 1: Record the benchmark contract**

Create `docs/research/dv-p5-p81-feasibility/libplacebo-benchmark.md`:

```markdown
# libplacebo Pixel Conversion Benchmark

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
| AM9 Pro | Mali-G310 V2 | 1080p24 | | | | | | |
| AM9 Pro | Mali-G310 V2 | 4K24 | | | | | | |
| AM9 Pro | Mali-G310 V2 | 4K60 | | | | | | |
| AM8 Pro | Mali-G57 MC2 | 1080p24 | | | | | | |
| AM8 Pro | Mali-G57 MC2 | 4K24 | | | | | | |
| AM8 Pro | Mali-G57 MC2 | 4K60 | | | | | | |

## Zero-Copy Finding

| Device | AHardwareBuffer decoder output available | `VK_ANDROID_external_memory_android_hardware_buffer` import works | CPU readback required | Evidence |
|--------|------------------------------------------|---------------------------------------------------------------|-----------------------|----------|
| AM9 Pro | | | | |
| AM8 Pro | | | | |

## Existing Nexio VideoSink Probe

| Device | `ExperimentalDv5HardwareToneMapVideoSink` P010 output | Native bridge accepts frame | Frame drops | Evidence |
|--------|-------------------------------------------------------|-----------------------------|-------------|----------|
| AM9 Pro | | | | |
| AM8 Pro | | | | |
```

- [ ] **Step 2: Create the external benchmark workspace**

Run outside the Nexio repo:

```bash
mkdir -p /Users/jneerdael/Scripts/dv-p5-libplacebo-probe
```

Expected: all benchmark source code and APKs live under `/Users/jneerdael/Scripts/dv-p5-libplacebo-probe`, not under Nexio.

- [ ] **Step 3: Record benchmark build metadata**

In the external benchmark workspace, capture the tool versions used:

```bash
pwd > /Users/jneerdael/Scripts/nexio/docs/research/dv-p5-p81-feasibility/evidence/libplacebo/probe-workspace.txt
git status --short > /Users/jneerdael/Scripts/nexio/docs/research/dv-p5-p81-feasibility/evidence/libplacebo/probe-git-status.txt
```

From the Nexio repo, capture device Vulkan summaries copied from Task 2:

```bash
cp docs/research/dv-p5-p81-feasibility/evidence/am9-stock/vulkaninfo-summary.txt docs/research/dv-p5-p81-feasibility/evidence/libplacebo/am9-vulkaninfo-summary.txt
cp docs/research/dv-p5-p81-feasibility/evidence/am8-stock/vulkaninfo-summary.txt docs/research/dv-p5-p81-feasibility/evidence/libplacebo/am8-vulkaninfo-summary.txt
```

Expected: the benchmark report can tie results to a specific external workspace and Vulkan driver evidence.

- [ ] **Step 4: Run standalone libplacebo frame benchmarks**

Run the external probe APK on AM9 and AM8 for:

```text
1080p24
4K24
4K60
```

For each run, write the raw output to:

```text
docs/research/dv-p5-p81-feasibility/evidence/libplacebo/am9-1080p24.txt
docs/research/dv-p5-p81-feasibility/evidence/libplacebo/am9-4k24.txt
docs/research/dv-p5-p81-feasibility/evidence/libplacebo/am9-4k60.txt
docs/research/dv-p5-p81-feasibility/evidence/libplacebo/am8-1080p24.txt
docs/research/dv-p5-p81-feasibility/evidence/libplacebo/am8-4k24.txt
docs/research/dv-p5-p81-feasibility/evidence/libplacebo/am8-4k60.txt
```

Expected: each file includes resolution, frame count, mean ms/frame, P95 ms/frame, import path, and whether CPU readback was used.

- [ ] **Step 5: Run 30-minute thermal checks**

For AM9:

```bash
adb -s "$AM9_ADB" shell dumpsys thermalservice > docs/research/dv-p5-p81-feasibility/evidence/libplacebo/am9-thermal-before.txt
```

Run the 4K60 external probe for 30 minutes, then:

```bash
adb -s "$AM9_ADB" shell dumpsys thermalservice > docs/research/dv-p5-p81-feasibility/evidence/libplacebo/am9-thermal-after.txt
```

For AM8:

```bash
adb -s "$AM8_ADB" shell dumpsys thermalservice > docs/research/dv-p5-p81-feasibility/evidence/libplacebo/am8-thermal-before.txt
```

Run the 4K60 external probe for 30 minutes, then:

```bash
adb -s "$AM8_ADB" shell dumpsys thermalservice > docs/research/dv-p5-p81-feasibility/evidence/libplacebo/am8-thermal-after.txt
```

Expected: report records whether clocks throttle, frame time rises, or thermal status changes during sustained 4K60.

- [ ] **Step 6: Probe existing Nexio VideoSink without changing it**

Enable the existing `experimentalDv5HardwareToneMapToSdrEnabled` setting and force the path only for diagnostic playback. Play `p5-mkv` on AM8 and AM9 long enough to observe the ImageReader path.

Capture logs:

```bash
adb -s "$AM9_ADB" logcat -d -v threadtime > docs/research/dv-p5-p81-feasibility/evidence/libplacebo/am9-existing-videosink-logcat.txt
adb -s "$AM8_ADB" logcat -d -v threadtime > docs/research/dv-p5-p81-feasibility/evidence/libplacebo/am8-existing-videosink-logcat.txt
```

Expected: logs answer whether P010 frames appear, whether CPU fallback activates, whether the FFmpeg bridge receives frames/RPUs, and whether frame drops occur.

- [ ] **Step 7: Fill the benchmark verdict**

Complete every blank in `libplacebo-benchmark.md`.

Use these verdicts:

- `go-4k60` when P95 is at most 12 ms/frame at 4K60 with acceptable thermal behavior.
- `go-limited` when 4K24 or 1080p60 is viable but 4K60 exceeds budget.
- `no-go` when P5 to HDR10 conversion cannot sustain a useful target or requires CPU readback that drops frames.

- [ ] **Step 8: Verify no production files changed**

Run:

```bash
git status --short app media dovi_tool app/build.gradle.kts
```

Expected: no output, or only unrelated pre-existing user changes.

- [ ] **Step 9: Commit libplacebo benchmark evidence**

Run:

```bash
git add docs/research/dv-p5-p81-feasibility/libplacebo-benchmark.md docs/research/dv-p5-p81-feasibility/evidence/libplacebo
git commit -m "docs: benchmark dv p5 libplacebo fallback"
```

Expected: commit contains benchmark evidence and report only.

---

### Task 7: Write Go/No-Go And Production Path Plan

**Files:**
- Create: `docs/research/dv-p5-p81-feasibility/production-plan.md`
- Modify: `docs/research/dv-p5-p81-feasibility/README.md`

- [ ] **Step 1: Verify required spike artifacts exist**

Run:

```bash
test -s docs/research/dv-p5-p81-feasibility/device-baseline.md
test -s docs/research/dv-p5-p81-feasibility/tunnel-verdict.md
test -s docs/research/dv-p5-p81-feasibility/libplacebo-benchmark.md
```

Expected: all commands exit successfully.

- [ ] **Step 2: Create the production plan**

Create `docs/research/dv-p5-p81-feasibility/production-plan.md`:

```markdown
# DV P5 Realtime Playback Production Plan

## Final Decision

| Device | Tunnel path | libplacebo fallback | Recommended production behavior |
|--------|-------------|---------------------|---------------------------------|
| AM9 Pro | | | |
| AM8 Pro | | | |

## Architecture Recommendation

Select exactly one:

- SoC-composer-downconvert-only
- libplacebo-only
- SoC-composer-downconvert with libplacebo fallback
- gated per device

Selected architecture:

Reason:

## Production Scope By File

| File | Planned change | Reason |
|------|----------------|--------|
| `app/src/main/java/com/nexio/tv/core/player/DoviBridge.kt` | | |
| `app/src/main/cpp/dovi_bridge.cpp` | | |
| `app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt` | | |
| `app/src/main/java/com/nexio/tv/core/player/DolbyVisionHevcSampleRewriter.kt` | | |
| `app/src/main/java/com/nexio/tv/core/player/Dv5HardwareToneMapRpuTap.kt` | | |
| `app/src/main/java/androidx/media3/exoplayer/video/ExperimentalDv5HardwareToneMapVideoSink.java` | | |
| `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt` | | |
| `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt` | | |
| `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAudioSettings.kt` | | |
| `app/src/main/java/com/nexio/tv/core/player/DolbyVisionBaseLayerPolicy.kt` | | |

## Device Gating

| Gate | AM9 Pro | AM8 Pro | Fallback |
|------|---------|---------|----------|
| SoC allowlist | | | |
| Vulkan requirement | | | |
| Dolby Vision display requirement | | | |
| HDR10-only display behavior | | | |
| Root or sysfs dependency | | | |

## Settings Surface

State what happens to:

- `experimentalDv5ToDv81Enabled`
- `experimentalDv7ToDv81Enabled`
- `experimentalDv5HardwareToneMapToSdrEnabled`
- `experimentalDv5HardwareToneMapCpuFallbackEnabled`

## Telemetry And Diagnostics

List exact runtime facts production code must log:

- selected DV architecture
- source DV profile and container
- display HDR capabilities
- decoder selected by MediaCodec
- SoC composer sysfs or vendor-control state when used
- libplacebo import path and frame timing when used
- fallback reason when the preferred path is disabled

## Rollback Story

Describe how to disable the new path from settings or device gates without breaking current playback.

## Acceptance Test Matrix

| Source | Container/protocol | Device | Display | Expected signal | Expected colors | Required evidence |
|--------|--------------------|--------|---------|-----------------|-----------------|-------------------|
| P5 | MKV | AM9 Pro | HDR10 TV | | correct | logcat, SurfaceFlinger, TV OSD |
| P5 | MP4 | AM9 Pro | HDR10 TV | | correct | logcat, SurfaceFlinger, TV OSD |
| P5 | DASH/HLS | AM9 Pro | HDR10 TV | | correct | logcat, SurfaceFlinger, TV OSD |
| P5 | MKV | AM8 Pro | HDR10 TV | | correct | logcat, SurfaceFlinger, TV OSD |
| P5 | MP4 | AM8 Pro | HDR10 TV | | correct | logcat, SurfaceFlinger, TV OSD |
| P5 | DASH/HLS | AM8 Pro | HDR10 TV | | correct | logcat, SurfaceFlinger, TV OSD |
| P5 | MKV | AM9 Pro | DV TV | optional follow-up | correct and/or DV-on-wire | logcat, SurfaceFlinger, TV OSD |

## Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Dolby licensing for a shipping P5 to P8.1 converter | | |
| Amlogic firmware update breaks sysfs or vendor controls | | |
| Dynamic metadata lost on libplacebo fallback HDMI output | | |
| Sustained 4K60 thermals exceed frame budget | | |
| Device reports Dolby Vision support but HDMI sink only accepts LL mode | | |

## Required Follow-Up Plan

The next plan must be a production implementation plan for the selected architecture. It must start from this file and include TDD tasks for device gating, settings migration, telemetry, and playback-path tests.
```

Fill every blank cell from `device-baseline.md`, `tunnel-verdict.md`, and `libplacebo-benchmark.md`.

- [ ] **Step 3: Update the spike index with the final decision**

Modify `docs/research/dv-p5-p81-feasibility/README.md` and add:

```markdown
## Final Result

- Production recommendation: see `production-plan.md`
- HDR10 correctness verdict: see `tunnel-verdict.md`
- libplacebo fallback verdict: see `libplacebo-benchmark.md`
- Device baseline: see `device-baseline.md`
```

- [ ] **Step 4: Run the production-code boundary check**

Run:

```bash
git status --short app media dovi_tool app/build.gradle.kts
```

Expected: no output, or only unrelated pre-existing user changes. If any spike work changed these paths, stop and revert only the spike-owned changes after confirming they are not user changes.

- [ ] **Step 5: Check the reports for missing blanks**

Run:

```bash
grep -R "| |" docs/research/dv-p5-p81-feasibility/*.md
```

Expected: no output for final reports. If the command prints table rows with blank cells, fill them from evidence or mark the exact reason in plain language, for example `not observed because AM8 Pro was not connected during the CoreELEC comparison`.

- [ ] **Step 6: Commit the decision and production plan**

Run:

```bash
git add docs/research/dv-p5-p81-feasibility/README.md docs/research/dv-p5-p81-feasibility/production-plan.md
git commit -m "docs: plan dv p5 realtime playback production path"
```

Expected: commit contains final research docs only.

---

## Verification

The spike is complete when these artifacts exist and are filled with evidence:

1. `docs/research/dv-p5-p81-feasibility/device-baseline.md`
2. `docs/research/dv-p5-p81-feasibility/tunnel-verdict.md`
3. `docs/research/dv-p5-p81-feasibility/libplacebo-benchmark.md`
4. `docs/research/dv-p5-p81-feasibility/production-plan.md`

Run:

```bash
test -s docs/research/dv-p5-p81-feasibility/device-baseline.md
test -s docs/research/dv-p5-p81-feasibility/tunnel-verdict.md
test -s docs/research/dv-p5-p81-feasibility/libplacebo-benchmark.md
test -s docs/research/dv-p5-p81-feasibility/production-plan.md
git status --short app media dovi_tool app/build.gradle.kts
```

Expected:

- All `test -s` commands pass.
- `git status --short app media dovi_tool app/build.gradle.kts` prints no spike-owned changes.

## Self-Review Notes

Spec coverage:

- Device baselining is covered by Task 2.
- Current metadata-only baseline is covered by Task 3.
- SoC composer downconvert investigation, sysfs, optional CoreELEC context, and `dovi.ko` evidence are covered by Tasks 4 and 5.
- libplacebo fallback benchmark is covered by Task 6.
- Final go/no-go and production plan are covered by Task 7.
- Explicit non-goals are captured in Scope and production-code boundary checks.

Placeholder scan:

- The plan intentionally uses runtime values from device commands and media manifests, but no section is left as an unbounded future decision.
- Any blank cells created during report drafting must be filled before the corresponding task commit.

Type and path consistency:

- All Nexio code paths match the current repository layout.
- All permanent spike artifacts live under `docs/research/dv-p5-p81-feasibility/`.
- External benchmark code lives outside the Nexio repo at `/Users/jneerdael/Scripts/dv-p5-libplacebo-probe`.
