---
name: validating-nexio-adb-passthrough
description: Executes the exact ADB broadcast workflow to validate audio passthrough transport and runtime playback quality in the Nexio TV debug app, capture logcat evidence, and export a validation bundle. Use when testing passthrough samples ac3, eac3, dts, dtshd, dtsx, or truehd on com.nexiodebug.tv, or when the user asks to run Nexio passthrough transport or runtime validation via ADB.
---

# Validating Nexio ADB Passthrough

## Scope

Use this skill only for the Nexio TV debug build.

- App id: `com.nexiodebug.tv`
- Receiver: `com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver`
- Action: `com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION`
- Supported sample ids: `ac3`, `eac3`, `dts`, `dtshd`, `dtsx`, `truehd`
- Hosted validator asset source: `https://files.thepi.es/validator`

## Golden rule

When passthrough transport already validates but runtime playback quality is broken, treat
the Media3 sink contract as the primary debugging reference.

Use this order:

1. Prove transport integrity first.
2. If transport passes, debug runtime as a sink-contract problem before blaming codec bytes again.
3. Keep Media3-facing behavior stock-like, while allowing codec-specific internals underneath.

For runtime debugging, first inspect whether the custom sink still behaves like a well-formed
Media3 `AudioSink` at the boundary expected by:

- `handleBuffer(...)`
- `hasPendingData()`
- `isEnded()`
- `getCurrentPositionUs(...)`
- `play()`, `pause()`, `flush()`, `playToEndOfStream()`

Use this rule of thumb:

- `handleBuffer(...)` should return `false` because the sink cannot make real progress now,
  not because a custom buffering policy merely decided it has "enough queued".
- `hasPendingData()` should mean audio is effectively still in flight at the output path,
  not just that a Java-side startup queue is non-empty.
- `isEnded()` should remain stock-like: end-of-stream handled, and no pending data left.
- `getCurrentPositionUs(...)` must stay on the player/media timeline Media3 expects.
- Startup backpressure should come primarily from real write/no-progress state and actual
  pending output, not arbitrary queue thresholds unless the codec transport truly forces it.

If runtime validation fails while transport passes, explicitly separate:

- transport truth: bytes are correct through packer and `AudioTrack.write()`
- runtime truth: playback contract, buffering, readiness, progress, underruns, and errors

Do not weaken transport conclusions because runtime is still bad.

## Degrees of freedom

**LOW FREEDOM**

These ADB broadcast operations are fragile.

- Use the exact commands below without changing package names, receiver names, action strings, flags, sample ids, or burst count.
- Run exactly one capture operation per validation run, requesting `8` bursts in that single capture.
- Always perform teardown, even if validation fails.
- Do not report success unless both the exported bundle and the log file were collected and checked.

## Required inputs

Before starting, confirm all of the following are available:

- Device serial: `<serial>`
- Target sample id: one of `ac3`, `eac3`, `dts`, `dtshd`, `dtsx`, `truehd`
- Writable temp paths under `/tmp`
- Debug build with network access to the hosted validator asset source

## Sample-to-asset map

Use this table when validating that the selected sample metadata matches the exported bundle.

| Sample id | Source container | Elementary stream | SPDIF golden reference |
|---|---|---|---|
| `ac3` | `dolbydigital.mkv` | `dolbydigital.ac3` | `dolbydigital.spdif` |
| `eac3` | `dolbydigitalplus.mkv` | `dolbydigitalplus.eac3` | `dolbydigitalplus.spdif` |
| `dts` | `dts.vob` | `dts.dts` | `dts.spdif` |
| `dtshd` | `dtshd.mkv` | `dtshd.dts` | `dtshd.spdif` |
| `dtsx` | `dtsx.mkv` | `dtsx.dts` | `dtsx.spdif` |
| `truehd` | `truehd.mkv` | `truehd.thd` | `truehd.spdif` |

## Workflow

Copy this checklist into the working response and keep it updated:

```text
Passthrough validation progress
- [ ] Create Debug build: ./gradlew :app:assembleDebug
- [ ] Install Debug build:  adb -s x.x.x.x:5555 install -r /Users/jneerdael/Scripts/nexio/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
- [ ] Start Debuggable process and sleep for 20 (wait for boot)
- [ ] Press left on keypad to wake from screensaver
- [ ] Enable validation
- [ ] Enable runtime validation if runtime quality should be scored
- [ ] Set runtime startup timeout if needed
- [ ] Set runtime observation window if needed
- [ ] Load sample
- [ ] Start playback
- [ ] Start log collection
- [ ] Trigger one capture burst
- [ ] Export bundle
- [ ] Pull bundle to /tmp
- [ ] Validate bundle against logs
- [ ] Stop playback
- [ ] Clear session
- [ ] Disable validation
```

### 1) Enable validation

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action enable
```

### 2) Load the target sample

Replace `<codec_name>` with exactly one supported sample id.

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action sample --es name <codec_name>
```

### 2a) Optional runtime validation controls

Enable runtime validation:

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action runtime \
  --ez enabled true
```

Set runtime startup timeout:

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action runtime_timeout \
  --ei ms 5000
```

Set runtime observation window:

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action runtime_window \
  --ei ms 30000
```

### 3) Start playback

Before starting playback, clear any screensaver or focus-stealing overlay with `DPAD_LEFT`.

Do not send `BACK` as part of the standard validator workflow on this device path, because it can exit Nexio instead of just dismissing the overlay.

```bash
adb -s <serial> shell input keyevent KEYCODE_DPAD_LEFT
```

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action start --es name <codec_name>
```

### 4) Start log collection

The logcat command is streaming. Start it before the capture/export window so the run evidence is recorded.

First clear logcat:

```bash
adb -s <serial> logcat -c
```

Then start collection in a separate shell/session and leave it running until export is complete:

```bash
adb -s <serial> logcat -v threadtime | tee /tmp/passthrough-validation.log
```

### 5) Trigger exactly one capture operation

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action capture \
  --ei bursts 8
```

### 6) Export the validation bundle

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action export
```

### 7) Locate and pull the exported bundle

Identify the bundle name:

```bash
adb -s <serial> shell run-as com.nexiodebug.tv ls files/transport-validation
```

Pull the chosen bundle to `/tmp`:

```bash
adb -s <serial> shell run-as com.nexiodebug.tv cat files/transport-validation/<bundle>.zip > /tmp/<bundle>.zip
```

After the bundle is exported and pulled, stop the streaming logcat session.

## Validation decision

Use the exported `/tmp/<bundle>.zip` and `/tmp/passthrough-validation.log` together. A run is only a **PASS** if the bundle is readable and the log evidence agrees with it.

### Check the exported bundle

Verify all of the following:

- Manifest version is present
- Hosted validator source metadata is present
- Selected sample metadata is present and matches `<codec_name>`
- Source container, elementary stream, and SPDIF golden reference match the sample-to-asset map above
- Checksums are present for the source/reference/elementary assets
- Cache-state metadata is present for the downloaded or reused local files
- Route snapshot fields are present
- Burst-count summary is present and reflects the requested `8` bursts
- If runtime validation was enabled:
  - `runtimeVerdict` is present
  - `runtime-summary.json` is present
  - `playback-stats.json` is present
  - `player-events.json` is present
  - `analytics-events.json` is present

### Check the log file

Verify the log contains evidence for the same run:

- Validation mode enabled
- Sample selected for `<codec_name>`
- Playback started for `<codec_name>`
- Capture initiated
- Export initiated
- No obvious broadcast, receiver, playback, permission, or fatal exception errors
- If runtime validation was enabled, no obvious startup-timeout, playback-stall, or player-error evidence contradicts the exported runtime verdict

### Runtime debugging order

When the bundle shows:

- `transportVerdict=PASS`
- but `runtimeVerdict!=PASS`

follow this order before proposing codec-byte changes:

1. Check the runtime artifacts first:
   - `runtime-summary.json`
   - `playback-stats.json`
   - `player-events.json`
   - `analytics-events.json`
2. Classify the failure shape:
   - startup timeout
   - ready/buffering oscillation
   - position stalled
   - player error
   - underrun / dropped frames / route churn
3. Inspect the Media3 sink contract at the active sink boundary:
   - `handleBuffer(...)`
   - `hasPendingData()`
   - `isEnded()`
   - `getCurrentPositionUs(...)`
   - lifecycle methods
4. Only after that, inspect codec-family runtime internals:
   - startup pacing
   - pending output accounting
   - drain/reconfigure behavior
   - route stabilization
5. Revisit codec bytes only if transport validation stopped passing or new evidence shows the
   transport is no longer correct.

Important runtime heuristics:

- `hasPendingData()` returning true because an internal startup queue is non-empty is suspicious.
- `handleBuffer(...)` returning false due to custom queue thresholds rather than real no-progress
  is suspicious.
- `position` stalling while `buffered position` continues to rise is usually a sink/runtime issue
  before it is a transport-integrity issue.
- repeated `READY`/`BUFFERING` churn with transport pass should be treated as a runtime/sink
  contract problem first.

### Decide PASS or FAIL

Return **PASS** only when all required bundle fields are present, the sample metadata matches the requested codec, the burst summary matches `8`, and the logs show the same run without obvious errors.

Return **FAIL** if any of the following happens:

- Bundle export failed or `/tmp/<bundle>.zip` was not pulled
- Bundle is unreadable or missing required fields
- Sample metadata does not match the requested codec
- Asset names do not match the sample-to-asset map
- Checksums are missing
- Hosted asset source or cache-state metadata is missing
- Burst summary is missing or does not match `8`
- Runtime artifacts are missing when runtime validation was enabled
- Log evidence is missing or shows obvious failures

## Required output format

Respond with a concise result block:

```text
Nexio passthrough validation result
- Device: <serial>
- Sample: <codec_name>
- Bundle: /tmp/<bundle>.zip
- Log: /tmp/passthrough-validation.log
- Verdict: PASS | FAIL
- Runtime verdict: PASS | DEGRADED | FAIL | UNKNOWN
- Evidence:
  - Bundle checks: ...
  - Log checks: ...
  - Mismatches or errors: ...
- Teardown: completed | not completed
```

Do not claim success without naming the bundle path, log path, and concrete evidence.

## Mandatory teardown

Always run teardown after the decision is made, even when the run fails.

### 1) Stop playback

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action stop
```

### 2) Clear the session

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action clear
```

### 3) Disable validation

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action disable
```

## Authoring notes

This skill is intentionally concise and operational:

- Discovery is handled by a trigger-rich `description`
- The body is focused on one fragile workflow
- The workflow includes an explicit validate-before-pass decision
- The output contract requires verifiable evidence instead of a generic success claim
- The instructions avoid repo-local paths and other environment-specific details
