# ADB Passthrough Transport Validation

This workflow is debug-build only and uses the hosted validator asset source at:

- `https://files.thepi.es/validator`

The app downloads the manifest and required sample/reference files into app-specific local storage
and reuses cached files when their checksums still match the manifest.

## Receiver

- Application id: `com.nexiodebug.tv`
- Receiver: `com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver`
- Action: `com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION`

## Common Commands

Enable validation:

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action enable
```

Disable validation:

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action disable
```

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

Record operator runtime observation:

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action mark_runtime_observation \
  --es avr_lock weak \
  --es audio_quality choppy \
  --es note "frequent glitches every few seconds"
```

Set capture burst count:

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action capture \
  --ei bursts 8
```

Export the current validation bundle:

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action export
```

Clear the current validation session:

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action clear
```

Stop validation playback:

```bash
adb -s <serial> shell am broadcast \
  -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver \
  -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION \
  --es action stop
```

## Sample IDs

- `ac3`
- `eac3`
- `dts`
- `dtshd`
- `dtsx`
- `truehd`

## Per-Codec Launch Flows

### Dolby Digital (AC-3)

```bash
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action sample --es name ac3
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action start --es name ac3
```

Hosted files:

- source container: `dolbydigital.mkv`
- extracted elementary stream: `dolbydigital.ac3`
- SPDIF golden reference: `dolbydigital.spdif`

### Dolby Digital Plus / Atmos Transport (E-AC-3)

```bash
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action sample --es name eac3
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action start --es name eac3
```

Hosted files:

- source container: `dolbydigitalplus.mkv`
- extracted elementary stream: `dolbydigitalplus.eac3`
- SPDIF golden reference: `dolbydigitalplus.spdif`

### DTS Core

```bash
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action sample --es name dts
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action start --es name dts
```

Hosted files:

- source container: `dts.vob`
- extracted elementary stream: `dts.dts`
- SPDIF golden reference: `dts.spdif`

### DTS-HD MA

```bash
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action sample --es name dtshd
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action start --es name dtshd
```

Hosted files:

- source container: `dtshd.mkv`
- extracted elementary stream: `dtshd.dts`
- SPDIF golden reference: `dtshd.spdif`

### DTS:X

```bash
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action sample --es name dtsx
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action start --es name dtsx
```

Hosted files:

- source container: `dtsx.mkv`
- extracted elementary stream: `dtsx.dts`
- SPDIF golden reference: `dtsx.spdif`

### Dolby TrueHD

```bash
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action sample --es name truehd
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action start --es name truehd
```

Hosted files:

- source container: `truehd.mkv`
- extracted elementary stream: `truehd.thd`
- SPDIF golden reference: `truehd.spdif`

## Log Collection

Use one capture per validation run:

```bash
adb -s <serial> logcat -c
adb -s <serial> logcat -v threadtime | tee /tmp/passthrough-validation.log
```

Then export the current bundle and pull it:

```bash
adb -s <serial> shell run-as com.nexiodebug.tv ls files/transport-validation
adb -s <serial> shell run-as com.nexiodebug.tv cat files/transport-validation/<bundle>.zip > /tmp/<bundle>.zip
```

The export bundle always includes:

- manifest version
- validator asset source URL
- selected sample metadata
- source/reference/elementary asset checksums
- cache state for the local files used by the validation run
- route snapshot fields
- burst-count summary for each captured boundary
- `transportVerdict` and `runtimeVerdict`
- `runtime-summary.json`
- `playback-stats.json`
- `player-events.json`
- `analytics-events.json`
- `sink-health.json`
- `route-health.json`
- `playback-head-health.json`
- `operator-observation.json`

## Transport Versus Runtime Verdicts

- `transportVerdict` proves byte integrity through the packer and `AudioTrack.write()` boundaries.
- `runtimeVerdict` scores playback quality independently using Media3 player and analytics
  signals.
- Runtime collection is additive. It does not change transport capture behavior.

The runtime layer currently tracks:

- startup time to `STATE_READY`
- startup time to `isPlaying=true`
- time to first rendered frame
- total buffering time and rebuffer count
- dropped video frames
- audio underruns
- playback-state transitions and `READY`/`BUFFERING` oscillation
- playback-position stall detection over a configurable observation window
- sink continuity metrics such as zero-write streaks, partial writes, output restarts, and stuck
  remainder duration
- playback-head health samples and longest no-advance window
- route-stability samples after stable start, including tuple changes and reopen/state churn
- structured operator observations for AVR lock and audible audio quality

The runtime summary now exports sub-verdicts for:

- `playerStateVerdict`
- `sinkContinuityVerdict`
- `routeStabilityVerdict`
- `operatorObservationVerdict`
