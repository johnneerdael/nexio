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

## SDR-only device behaviour (compositor without wide-color support)

Some Android TV devices — including UGOOS-AM6 on Android 9 and older
hardware in general — have compositors that only report
`COLOR_MODE_DEFAULT` (sRGB). On these devices, Nexio's HyperHDR
integration automatically forces SDR_NV12 regardless of the user's HDR
Mode setting or the source's colorimetry, because no HDR data reaches
the GL effect pipeline anyway.

You'll see this in the Settings screen:

- Toggle **Enabled** on
- Fill in host/ports/priority as normal
- Where the HDR Mode picker would be, the screen displays:
  > • Force SDR (locked)
  >
  > This device's compositor only supports sRGB. HDR ambilight requires
  > a wide-color-capable Android TV (Google TV Streamer, Shield, or any
  > Android 13+ device whose Display reports a BT2020 or BT2100 color
  > mode). SDR ambilight works correctly here.

Behaviour on the wire is then identical to the **HDR Mode override** →
**Force SDR** scenario: NV12 frames, `videomode HDR=0` JSON signal,
HyperHDR's SDR LUT engaged. The connected HDR display still shows HDR
content from the device's hardware overlay path — Nexio's ambilight
just reflects the SDR composition that the compositor produces, not
the HDR overlay.

To confirm the device is SDR-only:
```bash
adb -s <device-ip>:5555 shell dumpsys display | grep "supportedColorModes"
```
If the output reads `supportedColorModes=[0]` or `mSupportedColorModes=[0]`,
this gate is correctly engaged. If the array contains additional entries
(e.g. `[0, 12, 13]` for BT2020/BT2100_PQ), the device IS wide-color
capable and the HDR Mode picker should be visible.

## Verify clean handoff between sessions

14. Start an HDR episode, let it play for ~10 seconds, hit Stop.
15. On the server: log shows our priority being released (priority slot
    timing out within ~10s, OR HyperHDR's "next priority" source taking
    over if you have one). LEDs should reflect the fallback within a few
    seconds.
16. Start a new SDR episode. Expect a fresh `videomode HDR=0` and `NV12`
    frames.

## Verify mid-session reconnect

17. With HDR playback running and LEDs following, kill the HyperHDR server
    on the host machine (Ctrl-C in the journalctl terminal, or
    `sudo systemctl stop hyperhdr`).
18. Within 5 seconds, the player overlay's HyperHDR badge should change
    from "HyperHDR · HDR" (or just "HyperHDR" for SDR) to
    "HyperHDR ⟲" (the reconnecting indicator). LEDs will time out and
    release back to HyperHDR's fallback (or stay on the last frame, depending
    on server config).
19. Restart HyperHDR. Within ~5 seconds the badge should return to
    "HyperHDR · HDR" and LEDs resume following.
20. Confirms the Reconnector is doing its job — no app interaction needed.

## Verify Bearer-token auth (only relevant if HyperHDR has tokens enabled)

21. In HyperHDR's Web UI, enable JSON-RPC token authentication and copy
    the generated token.
22. In Nexio Settings → Integrations → HyperHDR → JSON API token, paste
    the token. Test connection should now succeed (returns "Connected to
    {hostname}").
23. Without the token (clear it in Settings), Test connection should
    fail at the JSON port phase: "JSON port 19444 unreachable: …".
    FlatBuffer port still validates fine — frame delivery doesn't require
    a token, only the JSON videomode signal does.

## Verify mDNS server picker

24. With multiple HyperHDR servers on the LAN (or just one, plus the
    Nexio device on the same subnet), open Settings → Integrations →
    HyperHDR → Host (tap to open).
25. The "Choose HyperHDR server" dialog should appear with
    "Searching the network…" briefly, then populated with the discovered
    server(s). Each entry shows hostname, IP:port, and JSON port (if
    advertised).
26. Pick a server. The Host, FlatBuffer port, and JSON port fields all
    update at once. Test connection should now succeed.
27. If you don't see your server in the list, "Enter manually" opens the
    standard host text-input dialog as a fallback. Some networks
    block mDNS broadcasts (corporate networks, isolated VLANs).

## Verify status badge in player overlay

28. Start HDR playback with HyperHDR enabled. Pause to show the player
    overlay (or just look at the always-visible badge row).
29. The badge row should show "HyperHDR · HDR" alongside the existing
    quality chips (resolution, codec, audio).
30. Switch to an SDR source. Badge changes to "HyperHDR" (no HDR suffix).
31. Disable the HyperHDR toggle in Settings. Resume playback. The badge
    is now absent from the row.

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
- **HDR Mode picker isn't visible on a device you expect to be HDR-capable** —
  the device's compositor doesn't actually report HDR modes. Verify with
  the dumpsys command above. Some HDR-capable TVs/displays connected to
  SDR-compositor source devices (e.g. AM6 → HDR TV) won't unlock the
  picker, because Nexio's capture path operates on the device's
  compositor output, not the display.
- **Badge stuck on "HyperHDR ⟲" forever** — Reconnector can't reach the
  server. Check that HyperHDR is actually running (`journalctl -fu hyperhdr`),
  that the IP/port in Settings is current (DHCP can shift over time), and
  that no firewall is blocking the FlatBuffer port. The Reconnector keeps
  retrying every 5 seconds with backoff up to 5s; persistent failure means
  the server is genuinely unreachable.
- **Test connection passes but JSON videomode fails silently in playback
  logs** — most often a missing or stale Bearer token. Re-copy the token
  from HyperHDR's Web UI into Nexio Settings.
- **mDNS discovery shows empty list despite HyperHDR running** — your
  network blocks mDNS broadcasts, OR HyperHDR isn't advertising the
  service. Verify advertisement with `avahi-browse -arv | grep hyperhdr`
  on a Linux box on the same subnet. If avahi sees them but Nexio doesn't,
  Android's network is on a different broadcast domain (e.g. guest VLAN).

## Reverting / disabling

- **Per-session:** toggle the Settings entry off.
- **Permanently:** uninstall the build, OR clear app data. The DataStore
  preferences file (`hyperhdr_config`) holds the toggle state — wiping
  it returns to the default-off state.
