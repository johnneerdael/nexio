# Passthrough Transport Validation Mode Design

## Context

Nexio's passthrough bring-up work for TrueHD, DTS-HD/DTS:X, and E-AC-3 Atmos currently relies on
manual log inspection and AVR behavior inference. That is too slow and too ambiguous for repeated
 codec-specific validation. The app already has a debug settings surface, custom Media3 audio sink
hooks, and bundled golden sample assets in the repository root at `assets/`.

The goal of this feature is to make transport validation deterministic:

- launch known bundled validation samples from the debug tool itself
- compare live emitted bursts against golden references automatically
- expose the workflow from both the in-app debug UI and ADB
- export a diagnostics package and document the ADB collection flow per codec

This feature is debug-only. It must not alter or regress the normal shared passthrough path.

## Goals

- Add a debug-only `Passthrough Transport Validation Mode`
- Support both UI and ADB control surfaces
- Restrict automatic validation to bundled golden validation assets only
- Perform automatic burst-by-burst comparison in the first implementation
- Validate at three boundaries:
  - pre-packer input
  - packed IEC burst
  - pre-`AudioTrack.write`
- Produce structured per-burst pass/fail diagnostics
- Support optional binary dump capture and export packaging
- Add per-codec documentation under `docs/`

## Non-Goals

- No production-build availability
- No arbitrary live-stream validation against bundled references
- No mutation of the live transport path to “fix” validation failures
- No expansion into a general-purpose media test framework
- The validator does not prove AVR decode success; it proves transport integrity through the app
  boundaries

## Recommended Architecture

### 1. Debug Controller Layer

Add a dedicated debug controller owned by the app layer that manages:

- whether validation mode is enabled
- selected sample / codec family
- comparison mode
- dump mode
- capture limits
- export requests

This controller should integrate with the existing debug settings structure rather than creating a
new standalone tool.

### 2. Bundled Golden Library

Use the repository-root `assets/` directory as the source of truth for bundled validation content.
The implementation should package only the required golden files into debug-capable app builds.
The packaging strategy is build-time copying of the selected repository-root golden files and
manifest into debug-capable Android asset sets so the validator can load them through normal app
asset APIs.

Each validation sample should have manifest metadata describing:

- sample id
- codec family
- container source
- extracted/reference file names
- expected logical IEC type / compare rules
- route tuple expectations
- validation notes

### 3. Validation Playback Launcher

Validation playback must be launched from the debug tool itself.

This path should:

- resolve the selected bundled sample
- create a dedicated playback request flagged as validation playback
- route that session through the existing custom sink path
- attach the selected manifest/reference metadata to the validation session

### 4. Validation Capture Hooks

Hook the validator at three boundaries:

- Java/native sink wrapper before packer ingestion
- native packed IEC burst output
- exact bytes passed to `AudioTrack.write`

These hooks must remain observational. They may capture, hash, compare, and export, but they must
not rewrite transport bytes.

The three-boundary proof chain is the minimum useful invariant for every codec:

- golden sample
- packer input
- packed IEC burst
- `AudioTrack.write` boundary bytes

### 5. Burst Comparator

The comparator must align:

- reference burst `N`
- live burst `N`

Comparison should operate on normalized logical burst interpretation, not arbitrary byte prefixes.
The comparator must compare reference burst `N` against live burst `N` after normalizing the same
byte-order interpretation for `Pa/Pb/Pc/Pd`. It must not compare arbitrary leading bytes of the
stream in place of aligned burst records.

It should produce compact structured results including:

- codec
- sample id
- burst index
- PTS
- size
- logical preamble fields
- payload / padding counts
- hash summary
- pass/fail result
- failure code

### 6. Control Surfaces

Two first-class entry points:

- Debug settings UI in the app
- ADB-triggerable debug command surface

ADB control should support:

- enable/disable validation mode
- select bundled sample
- start validation playback
- stop validation
- configure dump/capture options
- export diagnostics

## Data Flow

1. User or ADB selects a bundled validation sample.
2. Debug controller loads manifest/reference metadata.
3. Validation playback launcher starts the sample.
4. Sink/JNI hooks capture and tag bursts with sample/session metadata.
5. Comparator evaluates each live burst against the corresponding golden burst.
6. Structured results are emitted to logcat and optional binary dumps are written.
7. Export bundles logs, dumps, route/config snapshots, and manifest metadata.

## Validation Rules

### TrueHD

- MAT burst required
- validate `Pc & 0x7F == 0x16`
- validate MAT payload length / burst structure
- validate packed burst vs `AudioTrack.write` consistency

### DTS Core / DTS-HD MA / DTS:X

- validate expected type-IV behavior and logical type fields
- detect silent core-only fallback
- validate wrapper/payload continuity

### AC-3 / E-AC-3 / E-AC-3 JOC

- validate correct logical IEC type
- validate aggregate payload length and burst sizing
- validate byte preservation for JOC-bearing content

These rules are codec-specific by design. The first implementation should not use a single generic
"pass/fail" comparator; it should implement per-codec validation rules and failure codes.

## Failure Codes

First implementation should support explicit machine-readable failure reasons, including:

- `PREAMBLE_MISMATCH`
- `PC_TYPE_MISMATCH`
- `PD_LENGTH_MISMATCH`
- `BURST_SIZE_MISMATCH`
- `BURST_INDEX_ALIGNMENT_FAILED`
- `PACKER_TO_AUDIOTRACK_MUTATION`
- `DTSHD_CORE_ONLY_FALLBACK`
- `TRUEHD_MAT_INVALID`
- `EAC3_AGGREGATION_MISMATCH`

## Logging and Export

Two layers:

- compact structured logcat for every burst
- optional binary dumps for selected captures

Export package should include:

- structured logs
- binary dumps
- active route/config snapshot
- selected sample metadata
- device/build metadata relevant to passthrough validation
- manifest version and asset checksum information proving which bundled golden reference set was used

The route/config snapshot is mandatory and should include:

- selected sample and manifest metadata
- current routed device
- encoding
- sample rate
- channel mask
- direct playback support result
- `AudioTrack` state
- per-burst comparison results

## Debug Control Surface

The debug UI and ADB command path should expose:

- validation enabled
- codec/sample selector
- capture mode:
  - preamble only
  - first `N` bursts
  - until failure
- binary dump enabled
- export diagnostics

ADB command support should include:

- enable / disable validation
- select sample
- start validation
- stop validation
- export bundle
- clear previous session

## Per-Burst Record Format

Every emitted burst record should include:

- codec
- sample name
- burst index
- source PTS
- burst size
- `Pa`
- `Pb`
- raw `Pc`
- `Pc & 0x7F`
- `Pd`
- payload bytes
- zero-padding bytes
- first-64-byte hash
- full-burst hash
- comparison result
- mismatch reason code

## Binary Dump Naming

Binary dumps should use stable names such as:

- `codec_packer_in_000001.bin`
- `codec_packed_000001.bin`
- `codec_audiotrack_write_000001.bin`

## Safety and Isolation

- Debug/dev only
- Bundled golden assets only
- No shared-path behavior changes outside explicit validation hooks
- No changes to `media/libraries/cpp_audiosink` unless unavoidable for JNI bridge support
- Existing Atmos/DTS/DTS-HD/TrueHD runtime path must remain unaffected when validation mode is off

## Testing Strategy

- Unit test manifest parsing and comparison alignment logic
- Unit test failure code mapping for representative burst mismatches
- Manual validation runs on bundled samples through both:
  - in-app UI
  - ADB command surface
- Verify that validation mode off leaves existing playback behavior unchanged
