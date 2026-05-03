# Manual test: Nexio → HyperHDR ambilight end-to-end

**Prereqs:**
- HyperHDR server running on the LAN, built from
  [`johnneerdael/HyperHDR-10bit`](https://github.com/johnneerdael/HyperHDR-10bit)
  branch `p010-wire-format` (or the upstream merged version, when that lands).
  See `docs/INSTALL-UBUNTU.md` in that repo for compile instructions.
- HyperHDR has a working LED layout — at minimum four LEDs in a row,
  calibrated against a P010 test pattern.
- Nexio installed on a Google TV Streamer 4K (or other Android 14 / API 34
  HDR-capable device) and configured with at least one playable source.
- HEVC HDR10 test content available — a Dolby/Sony/LG demo clip on YouTube,
  a Netflix HDR title, or a local HEVC HDR10 file the player can find.

## Configure

1. Open Nexio on the TV. Navigate **Settings → Integrations → HyperHDR
   ambilight**.
2. Toggle **Enabled** on.
3. Fill in **Host** with the HyperHDR server's IP, **Port** with `19400`
   (the FlatBuffer port — change only if your HyperHDR is configured
   differently), **Priority** with `100` (lower numbers preempt higher;
   100 is the conventional default for video grabbers).
4. Tap **Test connection**. Expect "✔ Connected and registered" within
   ~1 second on the LAN. If you see a failure message, the network client
   couldn't reach the server — verify with `nc -v <host> 19400` from a dev
   machine.

## Verify HEVC HDR10 capture

5. Start playback of an HEVC HDR10 source.
6. On the HyperHDR server, watch the log:
   ```
   journalctl -fu hyperhdr | grep -i "P010 frame"
   ```
   Expect within 3–5 seconds of playback starting:
   ```
   [FlatBufferServer] Debug: Received first P010 frame.
   First plane size: <N> (stride: <S>).
   Second plane size: <M> (stride: <S>).
   Image size: <T> (192 x 108)
   ```
7. The LEDs should now follow the playing video. With HEVC HDR10 content
   the colour palette should look meaningfully different from SDR
   content (more saturated reds, deeper blacks, brighter highlights —
   what the LUT-calibrated tone-map produces from real PQ input).

## Verify behaviour on SDR sources

8. Switch to an SDR source (HEVC SDR or H.264).
9. The LEDs should continue to follow but with a less HDR-saturated
   palette. The captured P010 bytes for SDR content will use a smaller
   range of the 10-bit space (Y values clustered in the low-mid bins),
   which HyperHDR's LUT correctly translates to SDR-style LED output.

## Toggle off

10. Pause playback. Open Settings → Integrations → HyperHDR ambilight →
    toggle **Enabled** off.
11. The HyperHDR LEDs should release back to whatever priority/source
    HyperHDR next chooses (often "off" or a static colour, depending on
    your HyperHDR configuration).
12. Resume playback. LEDs should NOT follow — confirms the
    `setVideoEffects(emptyList())` path correctly tore down the effect
    and the connection.

## Pass criteria

- HyperHDR logs the "Received first P010 frame" line within 5 seconds
  of starting HEVC HDR10 playback.
- LEDs follow the playing content with visibly correct colour for HDR
  highlights (e.g. an explosion produces orange/red on the LEDs near
  it, not a washed-out approximation).
- Disabling the toggle stops LED activity within ~1 second.
- No playback stutter, no audio drift, no perceptible lag added by the
  effect being active.

## Common failures

- **"Test connection" fails with "Connection refused"** — wrong host or
  port, or HyperHDR isn't running. Verify with `nc -v <host> 19400` from
  the dev machine.
- **LEDs don't follow at all but Test connection succeeds** — the effect
  isn't being applied, OR Media3 selected a tunnelled HEVC decoder
  output that bypasses the GL effect pipeline. Check
  `adb -s 192.168.50.102:5555 logcat -s HyperHdrIntegration:V HyperHdrCapture:V`
  for setVideoEffects errors. Most likely cause: the codec's tunnel mode
  isn't disabled when effects are present — Media3 should automatically
  switch to GPU composition when `setVideoEffects()` is non-empty, but
  some vendor codecs ignore this signal.
- **HyperHDR logs "Unsupported flatbuffers image format"** — server is
  on upstream master, not the johnneerdael/HyperHDR-10bit fork. Rebuild
  the server from the right branch.
- **Colours look very wrong (washed/oversaturated)** — the production
  shader's PQ-encode assumption (linear-light fp16 input) doesn't match
  the actual data Media3 delivers. Re-run the diagnostic spike from
  Task 7 of the integration plan, capture findings, adapt
  `PqDownscaleShaders.kt` to match (e.g. drop the `pq_encode(...)` call
  if input is already PQ-encoded `[0,1]`).
- **Playback stutters when effect is active** — the side-tap is blocking
  the GL pipeline. Check that PBO async readback is in use (search
  `glMapBufferRange` in `HyperHdrCaptureShaderProgram.kt`) and the ring
  buffer has 3 entries.
- **HyperHDR shows zero/black frames** — the FBO save/restore in
  `renderSideTap` isn't doing its job, or the input texture id reaching
  `HyperHdrCaptureShaderProgram.drawFrame` is the wrong texture.
  `adb logcat -s HyperHdrCapture:V` should show side-tap activity at
  30 Hz; if it shows the right cadence but content is black, the input
  sampling is off.

## Reverting / disabling permanently

- **Per-session:** toggle the Settings entry off.
- **Permanently:** uninstall the build, OR clear app data. The DataStore
  preferences file (`hyperhdr_config`) holds the toggle state — wiping
  it returns to the default-off state.
