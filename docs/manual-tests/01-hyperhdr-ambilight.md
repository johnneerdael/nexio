# Manual test: Nexio → HyperHDR ambilight end-to-end

**Prereqs:**
- HyperHDR server running on the LAN, built from
  [`johnneerdael/HyperHDR-10bit`](https://github.com/johnneerdael/HyperHDR-10bit)
  branch `p010-wire-format`.
- HyperHDR has a working LED layout calibrated against a P010 test pattern.
- Nexio installed on a Google TV Streamer 4K (or other Android 14 / API 34
  HDR-capable device).
- A playlist of test content with both HEVC HDR10 and HEVC/H.264 SDR
  episodes accessible to Nexio.

## Configure

1. Open Nexio → **Settings → Integrations → HyperHDR ambilight**.
2. Toggle **Enabled** on.
3. Fill in:
   - **Host:** the HyperHDR server's IP
   - **FlatBuffer port:** `19400` (default)
   - **JSON-RPC port:** `19444` (default)
   - **Priority:** `100`
   - **HDR mode:** `Auto`
4. Tap **Test connection**. Expect:
   ```
   ✔ Connected to <hostname> · <instance-name>
   ```
   Both ports validated (FlatBuffer Register + JSON serverInfo).

## Verify HEVC HDR10 capture (auto-detected)

5. Start playback of an HEVC HDR10 source.
6. On the HyperHDR server, watch the log:
   ```
   journalctl -fu hyperhdr | grep -iE "P010|videomode"
   ```
   Within 3–5 seconds of playback starting, expect:
   - One `videomode` log line (`HDR=1`) from Nexio's JSON setHdrVideoMode call
   - The `Received first P010 frame.` line for the FlatBuffer side
7. LEDs follow the HDR content with bright, saturated highlights (PQ-encoded
   data going through HyperHDR's calibrated LUT).

## Verify SDR auto-detection (HEVC SDR / H.264)

8. Without changing HDR mode (leave on `Auto`), play an SDR source — H.264 or
   HEVC SDR.
9. Expect on the server:
   - `videomode` line with `HDR=0`
   - `Received first NV12 frame.` (NOT P010)
10. LEDs follow with SDR-style colour. Less saturated than HDR but
    correctly mapped through HyperHDR's NV12 path.

## HDR Mode override

11. Switch back to an HDR source. With **Auto** mode, the LEDs should be
    HDR. Pause, change **HDR mode** to **Force SDR**, resume.
12. Expect: `videomode` line with `HDR=0`, `NV12` frames on the wire,
    HyperHDR LUT switches to the SDR profile.
13. Switch back to **Auto** when done.

## Verify clean handoff between sessions

14. Start an HDR episode, let it play for ~10 seconds, hit Stop.
15. On the server: log shows our priority being released (priority slot
    timing out within ~10s, OR HyperHDR's "next priority" source taking
    over if you have one). LEDs should reflect the fallback within a few
    seconds.
16. Start a new SDR episode. Expect a fresh `videomode HDR=0` and `NV12`
    frames.

## Common failures

- **"Test connection" fails on FlatBuffer port** — wrong host/port, or
  HyperHDR isn't listening on the FlatBuffer endpoint. Verify with
  `nc -v <host> 19400`.
- **"Test connection" fails on JSON port** — same hostname but JSON-RPC
  isn't reachable. HyperHDR's JSON API might be on a different port; check
  the server's `webserver.htmlPort` setting.
- **HDR content captured as NV12** — Nexio didn't detect ST.2084/HLG.
  Causes: source's `colorInfo.colorTransfer` was missing/wrong (some
  containers strip it), or the format-detection picked it up after the
  first frame's already gone out. Force HDR isn't an option (intentionally),
  so the workaround is to verify the source actually carries HDR
  metadata (`mediainfo` on the file should show `Transfer characteristics:
  PQ` or `HLG`).
- **HDR detected but colours look very wrong** — the production shader's
  PQ-encode assumption (linear-light fp16 input) doesn't match the actual
  data Media3 delivers. See the deferred Task 7 spike from the v1 plan.
- **Playback stutters when effect is active** — PBO async readback isn't
  fully async on this hardware. Try reducing the rate gate from 30 fps to
  20 fps in `HyperHdrCaptureShaderProgram`'s `intervalNanos`.
- **`videomode` JSON call fails but FlatBuffer works** — non-fatal by
  design. HyperHDR will still receive frames; it just won't auto-switch
  HDR/SDR LUT profiles based on our signal. Check JSON port reachability
  separately with `nc -v <host> 19444`.

## Reverting / disabling

- **Per-session:** toggle the Settings entry off.
- **Permanently:** uninstall the build, OR clear app data. The DataStore
  preferences file (`hyperhdr_config`) holds the toggle state — wiping
  it returns to the default-off state.
