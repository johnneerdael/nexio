# ADB Passthrough Transport Validation

This workflow is debug-build only and uses the bundled validation assets copied from the repo-root
[`assets/`](/Users/jneerdael/Scripts/nexio/assets) directory.

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

Bundled files:

- source container: `dolbydigital.mkv`
- extracted elementary stream: `dolbydigital.ac3`
- SPDIF golden reference: `dolbydigital.spdif`

### Dolby Digital Plus / Atmos Transport (E-AC-3)

```bash
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action sample --es name eac3
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action start --es name eac3
```

Bundled files:

- source container: `dolbydigitalplus.mkv`
- extracted elementary stream: `dolbydigitalplus.eac3`
- SPDIF golden reference: `dolbydigitalplus.spdif`

### DTS Core

```bash
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action sample --es name dts
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action start --es name dts
```

Bundled files:

- source container: `dts.vob`
- extracted elementary stream: `dts.dts`
- SPDIF golden reference: `dts.spdif`

### DTS-HD MA

```bash
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action sample --es name dtshd
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action start --es name dtshd
```

Bundled files:

- source container: `dtshd.mkv`
- extracted elementary stream: `dtshd.dts`
- SPDIF golden reference: `dtshd.spdif`

### DTS:X

```bash
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action sample --es name dtsx
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action start --es name dtsx
```

Bundled files:

- source container: `dtsx.mkv`
- extracted elementary stream: `dtsx.dts`
- SPDIF golden reference: `dtsx.spdif`

### Dolby TrueHD

```bash
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action sample --es name truehd
adb -s <serial> shell am broadcast -n com.nexiodebug.tv/com.nexio.tv.debug.passthrough.TransportValidationReceiver -a com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION --es action start --es name truehd
```

Bundled files:

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
- selected sample metadata
- source/reference/elementary asset checksums
- route snapshot fields
- burst-count summary for each captured boundary
