# HDR10 Output Correctness Verdict

## Reframed Goal

The primary bar is not Dolby Vision over HDMI. The primary bar is: **Profile 5 input must not show green/purple on a non-Dolby Vision HDR10 display.**

The currently attached Samsung sink is therefore the primary test display, not a blocker. It advertises HDR10/HDR10+/HLG and does not advertise Dolby Vision. Correctness for this phase means:

- no green/purple chroma failure
- plausible HDR tone and color by visual inspection
- SurfaceFlinger and HDMI state indicate HDR10/PQ output rather than SDR or broken Dolby Vision signalling

Dolby Vision on the wire is a later bonus gate. It is useful if Nexio also wants to preserve dynamic metadata on DV TVs, but it is not required to prove the non-DV TV fix.

## Candidate Pipelines

| # | Pipeline | What happens | Testable on current Samsung HDR10 sink |
|---|----------|--------------|----------------------------------------|
| 0 | Current metadata-only DV5 to P8.1 | Nexio rewrites the container/metadata path with mode 2, but pixels may remain IPT. If the TV interprets IPT chroma as BT.2020, output is green/purple. | Yes. Play P5 through current Nexio with `experimentalDv5ToDv81Enabled=true`. |
| 1 | Amlogic C2 Dolby Vision decoder plus stock HDR10 downconvert | Feed P5 to `c2.amlogic.dolby-vision.dvhe.decoder`; the SoC DV composer converts IPT to BT.2020 PQ internally and emits HDR10 because EDID is HDR10-only. | Yes. Compare VLC/MX/Kodi playback and Nexio with hooks bypassed or raw P5 routing. |
| 2 | libplacebo pixel conversion | Decode P5 as Main10, reshape and convert IPT to BT.2020 PQ on GPU, output HDR10 RGB. | Yes, after building the external probe APK. |

Pipeline 1 is the cheapest possible production win if it works. The previous "tunnel" name is misleading for the primary gate; this path should be treated as **SoC DV composer downconvert to HDR10**, not HDMI DV tunnelling.

## Status

Evidence gathering is partially complete and intentionally stopped before a final correctness verdict.

Completed:

- AM9 stock firmware, sysfs, codec XML, HDMI, display, and Vulkan baseline.
- Read-only `dovi.ko` static analysis.
- Confirmed the attached Samsung sink is an HDR10-class non-DV test target.

Still needed:

- Exact P5 test media path or URI.
- Visual playback capture on the Samsung TV.
- Per-playback captures of `dolby_vision_enable`, `dolby_vision_status`, `attr`, SurfaceFlinger state, HDMI state, and MediaCodec logcat.
- Confirmation of which decoder Nexio selects for P5 today.

Not critical for this gate:

- AM8 Pro coverage. Needed before shipping a device matrix, not before proving the AM9 path.
- Dolby Vision TV. Needed only for optional DV-on-wire/dynamic-metadata follow-up.
- CoreELEC comparison. Useful background, not needed to answer whether stock Android can emit correct HDR10 on the attached sink.

## Visual Baseline Matrix

| Case | Pipeline | Player | Nexio setting or route | Expected/Question | Current result | Required evidence |
|------|----------|--------|------------------------|-------------------|----------------|-------------------|
| 0A | Current metadata-only | Nexio | `experimentalDv5ToDv81Enabled=true` | Presumed green/purple; verify on AM9/Samsung. | not run; missing P5 media path/URI | screen photo/video, logcat, SurfaceFlinger, `attr`, `dolby_vision_enable`, `dolby_vision_status` |
| 0B | Current raw/bypass behavior | Nexio | DV5 to P8.1 toggle off, no extractor rewrite | Determine whether raw P5 already hits generic HEVC, DV decoder, or fails. | not run; missing P5 media path/URI | screen photo/video, logcat, SurfaceFlinger, `attr`, `dolby_vision_enable`, `dolby_vision_status` |
| 1A | SoC DV composer downconvert | VLC/MX Player/Kodi | Stock player route to Amlogic DV decoder | Control: does stock Android produce correct HDR10 from the same P5 file? | not run; missing P5 media path/URI and player choice | screen photo/video, logcat, SurfaceFlinger, `attr`, `dolby_vision_enable`, `dolby_vision_status` |
| 1B | SoC DV composer downconvert | Nexio | Force or preserve `video/dolby-vision` route to Amlogic C2 DV decoder | Determine whether Nexio can get the same stock SoC result. | not run; requires MIME/decoder selection confirmation | decoder selection log, screen photo/video, logcat, SurfaceFlinger |
| 2A | libplacebo conversion | External probe APK | P5 decode plus GPU IPT to BT.2020 PQ | Only needed if pipeline 1 fails or is inaccessible. | not run; probe APK not created | benchmark output and screen/photo verification |

## Just Player P5 Result

Just Player was tested on the AM9 Pro with a P5 file on the Samsung HDR10-only sink. The observed picture was the expected purple/green failure, not correct HDR10.

Captured evidence:

- `evidence/am9-stock/p5-mkv-just-player-purple-logcat.txt`
- `evidence/am9-stock/p5-mkv-just-player-purple-decoder-lines.txt`
- `evidence/am9-stock/p5-mkv-just-player-purple-media-metrics.txt`
- `evidence/am9-stock/p5-mkv-just-player-purple-surfaceflinger.txt`
- `evidence/am9-stock/p5-mkv-just-player-purple-surfaceflinger-hdr-lines.txt`
- `evidence/am9-stock/p5-mkv-just-player-purple-attr.txt`
- `evidence/am9-stock/p5-mkv-just-player-purple-config.txt`
- `evidence/am9-stock/p5-mkv-just-player-purple-dolby_vision_enable.txt`
- `evidence/am9-stock/p5-mkv-just-player-purple-dolby_vision_status.txt`
- `evidence/am9-stock/p5-mkv-just-player-purple-dolby_vision_mode.txt`
- `evidence/am9-stock/p5-mkv-just-player-purple-dolby_vision_policy.txt`

Key findings:

- Just Player is `com.brouken.player` and uses AndroidX Media3/ExoPlayer `1.10.0` on this device.
- Logcat shows it selected `c2.amlogic.dolby-vision.dvhe.decoder` for `video/dolby-vision`.
- Logcat shows `OnStart DolbyVision:1` in the Amlogic C2 decoder path.
- The Amlogic display/Dolby composer state stayed off: `dolby_vision_enable=N`, `dolby_vision_status=0`, `dolby_vision_mode=5`, `amdv_type=0`.
- HDMI output was `2160p24hz`, `422,12bit`.
- SurfaceFlinger/HWC showed HDR-capable output state and a BT.2020 layer, but the visual output was still purple. This indicates the DV decoder route alone did not perform the required IPT-to-BT.2020 color conversion for the non-DV sink.

Interpretation:

Pipeline 1 is not proven by "route P5 to `c2.amlogic.dolby-vision.dvhe.decoder`" alone. On this firmware/display combination, Just Player reached the Amlogic Dolby Vision decoder but did not engage the Dolby Vision composer/downconvert path needed to produce correct HDR10. The remaining SoC-composer question is whether a vendor/sysfs policy poke can enable HDR10 downconvert during app playback. If not, pipeline 2, libplacebo pixel conversion, becomes the primary production path.

## UPlayer Private API Investigation

The firmware contains a real UPlayer APK at `/product/preinstall/UPlayer/UPlayer.apk`, but it was not installed before this investigation. It has now been installed for testing.

Captured facts:

- APK package: `com.uapplication.uplayer`
- Version: `0.4.40`
- Installed UID after manual install: `10093`
- APK SHA-256: `5183e67118c9f6085322b94a51be5dd6809140d9fa4921e960f926c13aa6ce48`
- Launchable activity: `com.uapplication.uplayer.player.ui.NewPlayerActivity`
- Native ABI: `armeabi-v7a`
- Bundled native stack includes FFmpeg-style libraries and MediaCodec support.

Static inspection did **not** show UPlayer as a privileged Ugoos/Amlogic system API client:

- No `sharedUserId="android.uid.system"` in the manifest.
- No request for `droidlogic.permission.SYSTEM_CONTROL`.
- No request for `android.permission.MODIFY_HDR_CONVERSION_MODE`.
- No obvious references to `/sys/module/aml_media/parameters/*`, `/sys/class/amdolby_vision/*`, `dolby_vision_mode`, `dolby_vision_policy`, `amdv_*`, or VS10-specific controls in filtered Java/native strings.
- It does contain ExoPlayer/Media3, MediaCodec, FFmpeg, Dolby metadata parsing strings, `video/dolby-vision`, `video/hevc`, and `video/hevcdv`.

The actual privileged Amlogic control surface appears elsewhere:

- `droidlogic.permission.SYSTEM_CONTROL` is declared by `com.droidlogic` with `signature` protection.
- `droidlogic.software.core.jar` exposes `com.droidlogic.app.SystemControlManager`, `OutputModeManager`, and `DolbyVisionSettingManager`.
- Those framework APIs include sysfs/property-style helpers such as `readSysfsOri`, `readSysFsOri`, `setProperty`, and display/HDR mode constants.

Interpretation:

UPlayer is not evidence that an ordinary app can access private Amlogic VS10/Dolby composer controls. It appears to be a normal app-level player using MediaCodec and bundled native media libraries. If it plays P5 better than Just Player, that still needs runtime proof, but the likely explanation would be app-side handling or decoder selection, not privileged `SystemControlManager` access.

For Nexio, the private API route has two separate tracks:

1. **Root/dev-device experiment:** use root to poke Dolby/Amdv sysfs directly during playback and see whether forced HDR10 output fixes P5.
2. **Shipping app feasibility:** unlikely through `SystemControlManager` unless Nexio is platform-signed, installed as a privileged/system app, or Ugoos exposes a public API. `droidlogic.permission.SYSTEM_CONTROL` is signature-protected.

## MIME And Decoder Selection Question

This remains the next key technical question for Nexio:

| Question | Evidence needed | Current state |
|----------|-----------------|---------------|
| Does Nexio route P5 as `video/dolby-vision` to `c2.amlogic.dolby-vision.dvhe.decoder` today? | MediaCodec logcat during P5 playback with toggle off and on. | not observed; Just Player did route P5 to this decoder and still showed purple |
| Does Nexio rewrite P5 into `video/hevc` or otherwise force the generic `c2.amlogic.hevc.decoder` path? | MediaCodec logcat plus extractor hook logs. | not observed |
| Does `experimentalDv5ToDv81Enabled=true` prevent the SoC composer from seeing the original P5 stream? | Compare cases 0A and 0B. | not observed |

If Nexio already routes P5 into the Amlogic DV decoder and the output is still green/purple, that matches the Just Player result and weakens pipeline 1 unless a productisable composer-policy control is found. If another stock player works where Just Player fails, inspect what additional sysfs/vendor state changes during that playback.

## External Player Validation Status

VLC and MX Player are installed on the AM9 Pro:

- `evidence/am9-stock/installed-video-players.txt`

Android resolves `video/dolby-vision` VIEW intents to MX Player and VLC:

- `evidence/am9-stock/video-dolby-vision-intent-activities.txt`
- `evidence/am9-stock/video-wildcard-intent-activities.txt`

This is not enough to prove either player uses the Amlogic Dolby Vision MediaCodec path. It only proves those apps can be launched for a Dolby Vision MIME intent. Actual validation still requires playing the same P5 file and capturing logcat lines that show whether the selected decoder is `c2.amlogic.dolby-vision.dvhe.decoder`, generic `c2.amlogic.hevc.decoder`, software decode, or an internal FFmpeg path.

The production plan must not depend on a specific external Android TV app. VLC/MX/Kodi are controls for stock Android behavior only. If any of them produce correct HDR10 and Nexio does not, the actionable finding is the platform route, not the third-party app.

Recommended external-player order for the next evidence pass:

1. **Just Player** if it can be installed. It is ExoPlayer-based, has Android TV support, claims HDR10+/Dolby Vision playback on compatible hardware, and is closest to Nexio's Media3/ExoPlayer architecture. It is the best diagnostic control for "can a normal MediaCodec/ExoPlayer-style Android app hit the platform DV path?"
2. **MX Player** as the second control. It is already installed and Android resolves `video/dolby-vision` intents to it, but its internal HW/HW+/SW selection is more opaque, so it is a weaker platform-parity signal than Just Player.
3. **VLC** only as a negative/secondary control. It is installed and resolves `video/dolby-vision`, but libVLC may choose its own pipeline, so a VLC result does not cleanly prove the Android platform MediaCodec route.
4. **Kodi/Kodinerds** only if the first two are inconclusive. It is useful as an ecosystem comparison, but it introduces build/configuration variability and is not as close to Nexio's production path.

## Stock Firmware SoC Composer Evidence

AM9 has strong stock firmware evidence that the SoC DV composer path exists:

- `evidence/am9-stock/lsmod-dovi.txt` shows `dovi_s6_5_15_stb26` loaded.
- `evidence/am9-stock/sys-find-dolby.txt` shows `/sys/class/amdolby_vision`, `/sys/devices/platform/amdolby_vision`, and Dolby Vision parameters under `/sys/module/aml_media/parameters/`.
- `evidence/am9-stock/dolby-amdv-parameters-values.txt` shows `dolby_vision_mode`, `dolby_vision_policy`, `dolby_vision_hdr10_policy=41`, `dolby_vision_flags`, `dolby_vision_enable`, `dolby_vision_status`, `amdv_target_mode`, and H.265 `parser_dolby_vision_enable` controls.
- `evidence/am9-stock/media_codecs_amlogic_dolby_vision.xml` advertises `c2.amlogic.dolby-vision.dvhe.decoder` with tunneled playback.
- `evidence/am9-stock/media_codecs_amlogic_performance_dolby_vision.xml` advertises 4K60 performance points for Amlogic Dolby Vision decoders.
- `evidence/am9-stock/dv-cap.txt` says the current receiver does not support Dolby Vision, which is exactly the sink class for the primary HDR10 correctness gate.
- `evidence/am9-stock/hdr-cap.txt` shows the sink supports HDR10/HDR10+/PQ/HLG.

## CoreELEC And dovi.ko Evidence

| Question | Evidence | Finding |
|----------|----------|---------|
| Does CoreELEC on AM9 Pro output real DV for the same P5 file? | `evidence/am9-coreelec/` | not needed for the primary HDR10 correctness gate; not tested in this pass |
| Does stock Android expose Dolby Vision kernel/user controls? | `evidence/am9-stock/dolby-amdv-parameters-values.txt` | yes; stock Android exposes Dolby/Amdv controls under `aml_media` |
| Does root on stock Android expose more than shell? | `evidence/am9-stock/adb-root-id.txt` | yes, `adb root` succeeds and shell becomes `uid=0(root)` |
| Does `dovi.ko` contain Dolby parser/control-path code consistent with Amlogic DV composer behavior? | `evidence/dovi-ko/dovi-ko-defined-symbols.txt`, `evidence/dovi-ko/dovi-ko-relevant-strings.txt` | yes; symbols include metadata parser, RPU decoder, HDMI metadata, and control-path functions |
| Would a shipping Android app need root, a custom kernel, or a vendor API for HDR10 downconvert? | playback evidence still needed | not decided; stock firmware has loaded DV module and Amlogic DV decoders, but app-level routing remains untested |

`/Users/jneerdael/Downloads/dovi.ko` static facts:

- `evidence/dovi-ko/dovi-ko-file.txt` identifies an aarch64 ELF relocatable kernel module, not stripped, with BuildID `11509eb66696b72bbde0fcdbffa500e6f1764eec`.
- `evidence/dovi-ko/dovi-ko-sha256.txt` records SHA-256 `5780712a2a66f52428cc015fa06c45c9d71e4ca330c9d49f273d500219ca32a4`.
- `evidence/dovi-ko/dovi-ko-relevant-strings.txt` includes `description=Amlogic Dolby Vision Driver` and `name=dovi_gen_5_15_2026`.
- `evidence/dovi-ko/dovi-ko-defined-symbols.txt` includes `dv_md_parser_process`, `metadata_parser_process`, `multi_control_path`, `rpu_decoder_process_buffer`, `get_dolby_control_data`, `get_hdmi_metadata_buffer`, and `set_L11_hdmi_tx`.
- `modinfo` is not installed on this macOS host, so `evidence/dovi-ko/dovi-ko-modinfo.txt` records that tool failure.

## HDR10 Correctness Gate

Verdict labels:

- `(a) stock SoC composer downconvert works from current Nexio`
- `(b) stock SoC composer downconvert works in other Android players and Nexio can likely route to it`
- `(c) stock SoC composer downconvert is inaccessible or incorrect from app playback; use libplacebo`
- `(d) current metadata-only path unexpectedly produces correct HDR10 on AM9/Samsung`
- `(e) no tested path produces correct HDR10 yet`

Selected label: not selected yet.

Evidence summary:

AM9 stock firmware has the Dolby Vision kernel module, Dolby/Amdv sysfs controls, Dolby Vision C2 decoders, and an attached HDR10-only Samsung sink. That makes the current setup sufficient for the primary correctness gate once exact P5 media is available.

Production implication:

Do not block the next evidence pass on a Dolby Vision TV. The immediate next step is the three-case visual baseline on the Samsung HDR10 display:

1. Nexio with DV5 to P8.1 toggle on.
2. Nexio with the toggle off.
3. VLC/MX Player/Kodi playing the same P5 file.

If case 3 works and Nexio does not, prioritize MIME/decoder routing to `c2.amlogic.dolby-vision.dvhe.decoder`. Only scaffold the libplacebo probe after pipeline 1 is shown unusable or inaccessible.
