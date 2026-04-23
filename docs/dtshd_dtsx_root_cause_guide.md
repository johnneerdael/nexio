# DTS-HD MA / DTS:X on Android HDMI
## Fresh-Start Root-Cause Guide for Integrating Kodi's IEC Packer into Media3

## Goal

Build a **research-backed, from-scratch engineering guide** for carrying **DTS-HD MA** and **DTS:X carried in DTS-HD** over Android HDMI passthrough using a **Kodi-style IEC packer integrated into Media3**.

This guide is intentionally written from a **fresh-start perspective**:

- do **not** assume an existing working integration
- prove each layer independently
- validate bytes against **real DTS-HD MA and DTS:X samples**
- use **FFmpeg, Android/AOSP, Media3, and Kodi** as the primary external references

---

## Scope

This guide is about **DTS-HD MA** and **DTS:X carried within the DTS-HD family transport path**, not a hypothetical standalone public IEC 61937 “DTS:X burst type.”

The transport model here is fundamentally different from TrueHD:

- **TrueHD** is primarily a **MAT / TrueHD** transport problem.
- **DTS-HD MA / DTS:X** is primarily a **DTS core + DTS-HD type IV IEC 61937** transport problem.

The most important working assumption is:

> At the HDMI/IEC layer, DTS:X-on-DTS-HD should be treated as a **DTS-HD type IV transport problem first**, not as a bespoke new IEC burst type.

That assumption is grounded in four external facts:

1. FFmpeg’s IEC/SPDIF muxer has explicit public handling for **DTS core** (`IEC61937_DTS1/2/3`) and **DTS-HD type IV** (`IEC61937_DTSHD = 0x11`), including subtype mapping and cadence logic.
2. FFmpeg’s DTS-HD path assumes a usable **core relationship** and may **fall back to core only** when the HD packet does not fit the chosen repetition period.
3. AOSP exposes **`ENCODING_DTS`**, **`ENCODING_DTS_HD`**, **`ENCODING_DTS_HD_MA`**, and **`ENCODING_IEC61937`**, but not a separate public `ENCODING_DTS_X` constant in `AudioFormat`.
4. Media3 release notes mention **direct playback support for DTS:X**, which is useful as a capability signal, but not as a substitute for the transport-layer DTS-HD type-IV rules.

So the transport strategy should be:

- preserve the input **DTS-HD / DTS:X-carried-in-DTS-HD bytes exactly**
- build the correct **DTS core or DTS-HD type-IV IEC burst framing**
- explicitly detect and reject **silent core-only fallback** unless intentionally allowed
- make sure Media3 and AudioTrack expose a route that can actually carry the chosen tuple

---

## Fresh-start methodology

Use this exact order. Do not skip steps and do not start by changing multiple variables at once.

1. **Prove the sample asset is what you think it is**
2. **Prove the packer emits valid DTS / DTS-HD IEC bursts offline**
3. **Generate a reference IEC stream with FFmpeg**
4. **Byte-compare your packer output against the FFmpeg reference**
5. **Verify byte equality at every app boundary**
6. **Verify Android route capability and AudioTrack tuple**
7. **Only then debug Media3 sink-state and scheduling behavior**

That sequence is the main lesson from the TrueHD work: it is much faster to isolate transport correctness early than to debug the whole player stack at once.

---

## 1. Known-good facts to anchor the investigation

### 1.1 DTS core and DTS-HD do not use the same IEC 61937 contract

FFmpeg’s IEC muxer treats ordinary DTS and DTS-HD differently:

- **DTS core** uses:
  - `IEC61937_DTS1 = 0x0B` for 512-sample frames
  - `IEC61937_DTS2 = 0x0C` for 1024-sample frames
  - `IEC61937_DTS3 = 0x0D` for 2048-sample frames
- **DTS-HD** uses **DTS type IV**, exposed as `IEC61937_DTSHD = 0x11`

That means a fresh-start guide must explicitly separate:

- core-only transport
- HD extension transport
- app-level behavior that accidentally falls back to core

### 1.2 DTS-HD type IV uses a repetition-period / subtype model

FFmpeg documents DTS type IV this way:

- DTS-HD can be transmitted with different **frame repetition periods**
- the repetition period is measured in **IEC 60958 frames (4 bytes)**
- supported subtype mappings are:
  - `512 -> 0x0`
  - `1024 -> 0x1`
  - `2048 -> 0x2`
  - `4096 -> 0x3`
  - `8192 -> 0x4`
  - `16384 -> 0x5`
- the packer computes:
  - `period = dtshd_rate * (blocks << 5) / sample_rate`
  - `pkt_offset = period * 4`
  - `data_type = IEC61937_DTSHD | (subtype << 8)`

That is the DTS-side equivalent of the TrueHD MAT transport problem: the hard part is not “just wrap bytes in IEC,” but **get the type-IV subtype and cadence right**.

### 1.3 FFmpeg’s DTS-HD transport assumes a paired core and rejects some HD-only situations

FFmpeg’s DTS parser / IEC muxer makes several important assumptions:

- **HD mode is not supported if there is no core**
- a **stray DTS-HD frame without core** at the beginning of a stream is treated as invalid

That is an important engineering anchor:

> For type-IV HDMI carriage, treat “full DTS-HD frame plus usable core relationship” as the default contract until proven otherwise.

### 1.4 FFmpeg can silently fall back to core-only when the HD packet does not fit

FFmpeg explicitly handles the case where a DTS-HD packet does not fit the chosen repetition period:

- if the packet exceeds `pkt_offset - BURST_HEADER_SIZE`, it logs that the **DTS-HD bitrate is too high** and **temporarily sends core only**
- then it uses `pkt_size = core_size`

This is one of the most important transport facts to carry into a fresh implementation:

> A path can “work” while actually delivering only the DTS core for some or all of the stream.

### 1.5 FFmpeg adds a DTS-HD wrapper inside the IEC payload

For DTS-HD type IV, FFmpeg constructs an HD payload beginning with a 10-byte start code:

- `01 00 00 00 00 00 00 00 FE FE`

Then it writes:

- a 16-bit payload size
- the DTS packet bytes

It also sets:

- `out_bytes = sizeof(start_code) + 2 + pkt_size`
- `length_code = FFALIGN(out_bytes + 0x8, 0x10) - 0x8`

FFmpeg comments that this alignment is reportedly needed by some receivers, even if the exact spec rationale is unclear.

### 1.6 Android exposes DTS, DTS-HD, DTS-HD MA, and IEC 61937 separately

Current AOSP `AudioFormat` includes:

- `ENCODING_DTS`
- `ENCODING_DTS_HD`
- `ENCODING_DTS_HD_MA`
- `ENCODING_IEC61937`

AOSP also documents for `ENCODING_IEC61937`:

- it is **compressed audio wrapped in PCM for HDMI or S/PDIF passthrough**
- for devices whose SDK version is **less than Android S / API 31**, the channel mask of an IEC 61937 track **must be stereo**
- data **should be written as `short[]`**, and writing `byte[]` can cause endian problems on some platforms

These details directly affect route bring-up and byte-integrity debugging.

### 1.7 Android TV capability checks must be route-scoped and current-route aware

Android TV’s audio-capabilities guidance says apps should choose the **best AudioTrack format supported by the currently routed device** and should query support using:

- `getAudioProfiles()` / `getEncodings()`
- `isDirectPlaybackSupported()`
- `AudioManager.getDirectPlaybackSupport()` on API 33+

The doc also warns that **before API 33**, `isDirectPlaybackSupported()` may return true based on some available output path, not necessarily the currently routed one. So direct-playback support must be treated as **route-scoped and time-sensitive**.

### 1.8 Media3 has DTS:X-specific capability behavior

Media3 release notes explicitly mention:

- support for **direct playback of DTS Express and DTS:X**

This is a signal that a fresh-start guide should include **route-capability probing** for DTS-family formats from the beginning.

### 1.9 Kodi’s Android sink is an important IEC reference for DTS-HD MA

Upstream Kodi’s `AESinkAUDIOTRACK.cpp` shows:

- in the IEC path, Kodi uses **`ENCODING_IEC61937`**
- Kodi forces **192000 Hz** sink rate for `STREAM_TYPE_DTSHD` and `STREAM_TYPE_DTSHD_MA`
- in IEC mode, Kodi keeps the **multichannel mask** for HD formats rather than collapsing them to stereo on newer Android IEC-capable paths
- Kodi also contains a telling warning in the non-IEC/raw path:
  - **Android’s packer appears to send only the DTS core even when DTS-HD MA is requested**

For a fresh integration, that strongly suggests:

> start by matching **Kodi’s DTS-HD IEC path**, not by inventing a separate DTS:X transport path.

### 1.10 Media3’s renderer is tightly coupled to sink-state reporting

Upstream Media3 `MediaCodecAudioRenderer` uses `audioSink.hasPendingData()` directly for two critical behaviors:

- `isReady()` returns `audioSink.hasPendingData()`
- buffer-progress logic treats `audioSink.hasPendingData()` and pending presentation times as a key signal for whether the sink is full and when playback can progress

Upstream `DefaultAudioSink` explicitly says it handles **playback position smoothing, non-blocking writes, and reconfiguration**. Its `hasPendingData()` is not just “queue non-empty”; it is tied to whether the output is initialized and whether written frames still remain pending in the output path.

That means a fresh custom sink must treat these as **first-class design requirements**.

### 1.11 Android CTS gives one useful methodology clue

Android’s `AudioTrackSurroundTest` contains a valuable comment for IEC 61937:

> for testing IEC61937, the Audio framework does not look at the wrapped data; it just passes it through over HDMI, so zeros can be used for the CTS throughput/rate test.

That does **not** mean receivers will lock to garbage. It means:

- you can bring up and validate the **AudioTrack IEC route** independently of real codec bytes
- then separately validate the **actual codec-correct burst bytes** needed for AVR lock

That is a very useful phase split for engineering.

---

## 2. Working assumptions and hypotheses

Because this guide starts from scratch, do **not** assume any of the following until proven:

- that Media3 is delivering clean DTS or DTS-HD access units
- that Kodi’s packer can be dropped in unchanged and still see the same packet contract
- that DTS:X requires a different public IEC data type than DTS-HD
- that `ffprobe` metadata alone proves the sample still contains the HD extension path you expect
- that successful audio necessarily means DTS-HD MA or DTS:X survived intact

### Main hypotheses

The most likely failures, in order, are:

1. **wrong sample / wrong input contract**
2. **wrong DTS core or DTS-HD type-IV burst construction**
3. **silent core-only fallback under overflow or parser mismatch**
4. **wrong `Pc` / `Pd` / subtype / repetition period / packet offset**
5. **byte corruption between packer and AudioTrack**
6. **wrong Android route tuple or stale capability assumptions**
7. **Media3 sink contract mismatch**
   - bad `handleBuffer()` backpressure
   - bad `hasPendingData()`
   - bad `getCurrentPositionUs()` timebase
   - blocking/non-blocking write policy that starves playback

---

## 3. Diagnosis strategy

## Phase A — validate the test assets first

### Goal

Prove that the test assets are real **DTS core**, **DTS-HD MA**, and **DTS:X-over-DTS-HD** candidates before debugging transport.

### Minimum commands

```bash
ffprobe -hide_banner -select_streams a -show_streams INPUT.mkv
ffmpeg -ss 00:00:02 -i INPUT.mkv -t 5 -map 0:v:0 -map 0:a:0 -c copy sample-5s.mkv
ffmpeg -i sample-5s.mkv -map 0:a:0 -c copy sample.dts
```

### What to record

- container format
- audio codec reported by `ffprobe`
- sample rate
- channel count
- whether the stream is flagged or labeled as DTS-HD / DTS-HD MA
- whether the DTS:X asset is only identifiable by source provenance or player behavior rather than container metadata

### Important caution

Public tools may still present the elementary stream simply as **DTS** or **DTS-HD**. That does **not** by itself disprove DTS:X carriage. Use a **known-good DTS:X test sample** and keep it as the fixed reference asset for the rest of the investigation.

### Test matrix

Keep at least these assets:

1. **plain DTS core sample**
2. **known DTS-HD MA sample**
3. **known DTS:X sample**

The plain DTS sample proves the core path. The DTS-HD MA sample proves type-IV HD carriage. The DTS:X sample proves whether preserving the exact input bytes also preserves the object-capable path seen by the AVR.

---

## Phase B — bring up Android’s IEC route independently of codec correctness

### Goal

Prove that Android can open and run the chosen IEC route before involving real DTS bytes.

### Why

CTS shows that the Android framework’s IEC path can be validated independently of payload correctness because the framework simply passes wrapped data through over HDMI.

### Experiment

Create a short `ENCODING_IEC61937` AudioTrack and write zeroed `short[]` buffers at the intended rate.

This phase is **not** expected to lock the AVR to DTS. It is expected to answer:

- can the route be opened?
- does the track stay initialized?
- does `play()` work?
- does the platform accept the sample-rate / channel-mask / encoding tuple?

If this phase fails, do not debug the DTS packer yet.

---

## Phase C — prove the packer works offline before Android touches it

### Goal

Make the Kodi IEC packer emit valid DTS or DTS-HD bursts **offline**, before JNI / Media3 / AudioTrack are involved.

### Required harness modes

1. **whole mode** — feed one giant blob to prove the packer contract is not arbitrary
2. **sizes mode** — feed parser-sized slices / one access unit at a time
3. **scan mode** — search for valid syncframe starts and feed those

### Success conditions

For DTS core:

- Pa / Pb present
- `Pc & 0x7F` identifies the correct DTS family type (`0x0B`, `0x0C`, or `0x0D`)
- `pkt_offset = blocks << 7`
- payload matches the intended core packet bytes

For DTS-HD type IV:

- Pa / Pb present
- low byte of `Pc` identifies **DTS-HD / `0x11`**
- high bits of `Pc` contain a valid subtype for the chosen repetition period
- `pkt_offset = period * 4`
- the payload begins with the DTS-HD wrapper start code
- emitted payload contains HD data and is **not silently core-only**

### Interpretation

- **offline works, app fails** → Media3/JNI slicing or byte corruption bug
- **offline fails too** → wrong packer contract or wrong assumptions about DTS frame boundaries / core-extension pairing

---

## Phase D — generate an FFmpeg reference IEC stream

### Goal

Produce a **reference IEC stream** from the real DTS-HD MA and DTS:X samples and compare your output against it.

### Reference commands

```bash
ffmpeg -i sample.dts -map 0:a:0 -c copy -f spdif ffmpeg_ref.spdif
xxd -g 1 -l 256 ffmpeg_ref.spdif
```

Run this separately for:

- the DTS core sample
- the DTS-HD MA sample
- the DTS:X sample

### Why this works

FFmpeg’s `spdif_header_dts()` and DTS-HD handling are an open reference implementation for the transport logic you need to reproduce:

- parse DTS core / HD packet structure
- determine block count and sample-rate relationship
- compute the correct DTS core type or DTS-HD subtype
- build the IEC burst and spacing
- optionally fall back to core only when the chosen DTS-HD repetition period is insufficient

### What to compare

Compare your packer output against `ffmpeg_ref.spdif` for:

- Pa / Pb
- `Pc`
- `Pd`
- burst-to-burst spacing
- payload byte count
- wrapper start code for DTS-HD type IV
- first burst payload bytes
- first N bursts, not just burst 1
- whether the DTS-HD MA / DTS:X samples are HD at the payload level or silently reduced to core

### Expected differences to account for

- If your app later writes through a little-endian `short[]`-based path, a dump at the AudioTrack boundary may show the **byte-swapped 16-bit view** of Pa/Pb.
- That is acceptable **only if** the logical 16-bit preamble words remain correct.

---

## Phase E — verify byte equality across the app pipeline

### Goal

Prove that the same valid bytes survive every app boundary.

### Capture points

1. input DTS access unit bytes
2. bytes passed into the native packer
3. native packed IEC burst bytes
4. bytes passed into `AudioTrack.write`

### Minimum invariant

For the first emitted burst:

**`packed_iec_before_android == audio_track_write_payload`**

If those differ, stop and debug the handoff before chasing route or AVR issues.

### Recommended artifact names

- `dts_in_au_000001.bin`
- `dts_packer_in_000001.bin`
- `dts_packed_000001.bin`
- `audiotrack_write_000001.bin`

### Why this matters for DTS-HD MA and DTS:X

Because DTS-HD MA and DTS:X depend on preserving the **full HD payload**, any strip/offset/repacketization bug can silently degrade the stream to “ordinary DTS core behavior” even when some audio still comes out.

---

## Phase F — verify Android route and AudioTrack tuple

### Goal

Choose the correct tuple for the **currently routed device**, not a guessed tuple.

### Probe these encodings on the active route

- `ENCODING_DTS`
- `ENCODING_DTS_HD`
- `ENCODING_DTS_HD_MA`
- `ENCODING_IEC61937`

### Route queries

Use:

- `getAudioProfiles()` / `getEncodings()`
- `isDirectPlaybackSupported()`
- `AudioManager.getDirectPlaybackSupport()` on API 33+

### Fresh-start rule

Do not hard-code “DTS:X means use a special new transport tuple.”

The public grounding suggests that the transport work should start from **DTS-HD type IV / IEC61937 semantics**, then route probing should determine which Android encoding tuple the current device will actually initialize.

### Kodi grounding

Kodi’s Android sink strongly suggests that its successful DTS-HD MA passthrough path is grounded in **IEC61937 / 192000 Hz / HD-format semantics**, not a separate Android DTS:X-specific transport path:

- `STREAM_TYPE_DTSHD` and `STREAM_TYPE_DTSHD_MA` align with 192000 Hz sink rate
- `ENCODING_IEC61937` is used when Kodi wants IEC passthrough
- the multichannel mask is preserved for HD formats on supported paths

That should be the baseline comparison for a fresh integration.

---

## Phase G — verify Media3 sink behavior from day one

### Goal

Treat Media3 sink correctness as part of the initial design, not a late-stage cleanup item.

### Instrument these methods immediately

- `configure(...)`
- `handleBuffer(...)`
- `play()`
- `pause()`
- `flush()`
- `playToEndOfStream()`
- `hasPendingData()`
- `isEnded()`
- `getCurrentPositionUs()`

### Why

Upstream Media3 makes these methods critical:

- `MediaCodecAudioRenderer.isReady()` returns `audioSink.hasPendingData()`
- renderer progress logic treats `hasPendingData()` and pending presentation times as a readiness / fullness signal
- `DefaultAudioSink` is designed around **non-blocking writes, position smoothing, reconfiguration, and backpressure**

A custom sink that simply “gets the bytes out” can still break playback if:

- `hasPendingData()` flips false too early
- `getCurrentPositionUs()` reports a sink-local timebase instead of stream time
- `handleBuffer()` accepts too much and creates huge queue growth
- blocking writes monopolize the playback thread

These are not secondary concerns. The TrueHD work proved they can become the final blocker even after transport is correct.

---

## 4. DTS core and DTS-HD transport invariants

### 4.1 DTS core and DTS-HD follow different IEC rules

Ordinary DTS core transport is simpler in FFmpeg:

- if the packet is truncated to `core_size`, FFmpeg selects DTS1/2/3 based on `blocks`
- it uses `pkt_offset = blocks << 7`
- and `length_code = core_size << 3`

DTS-HD type IV is different because the packer must:

- retain the HD payload and its core relationship
- compute a legal repetition period / subtype
- wrap the packet with the DTS-HD start code and payload size
- align `Pd` as the working implementation expects

### 4.2 DTS-HD type IV repetition-period rule

FFmpeg’s subtype mapping is:

- `512 -> 0x0`
- `1024 -> 0x1`
- `2048 -> 0x2`
- `4096 -> 0x3`
- `8192 -> 0x4`
- `16384 -> 0x5`

and the packet offset is:

- `pkt_offset = period * 4`

The practical meaning is:

- some DTS-HD packets will fit one chosen repetition period and others will not
- when they do not fit, a careless implementation may silently devolve to core-only behavior

### 4.3 FFmpeg’s DTS-HD wrapper and length-code behavior

For DTS-HD type IV:

- payload starts with `01 00 00 00 00 00 00 00 FE FE`
- then a 16-bit packet size
- then the DTS packet bytes
- `out_bytes = 10 + 2 + pkt_size`
- `length_code = FFALIGN(out_bytes + 0x8, 0x10) - 0x8`

This is the working transport model to match in a fresh packer.

### 4.4 DTS:X does not imply a different public IEC data type here

Because the public transport references describe **DTS-HD type IV** rather than a separate DTS:X burst type, the working engineering assumption should be:

> if the input DTS:X-carried-in-DTS-HD bitstream is preserved exactly, the IEC transport layer should still identify as DTS-HD type IV (`0x11` low byte) rather than a separate public DTS:X IEC type.

That is a working engineering assumption, not a licensing statement about proprietary DTS metadata internals.

---

## 5. IEC 61937 burst preamble verification for DTS and DTS-HD

Each IEC burst begins with four 16-bit words:

- **Pa** = `0xF872`
- **Pb** = `0x4E1F`
- **Pc** = burst info
- **Pd** = length code

### Common byte views

Logical values:

- `Pa = 0xF872`
- `Pb = 0x4E1F`

Possible byte views in dumps:

- big-endian style: `F8 72 4E 1F`
- little-endian short view: `72 F8 1F 4E`

### Pc for DTS core

For FFmpeg-style DTS core IEC bursts:

- `Pc & 0x7F == 0x0B` for 512-sample frames
- `Pc & 0x7F == 0x0C` for 1024-sample frames
- `Pc & 0x7F == 0x0D` for 2048-sample frames

### Pc for DTS-HD type IV

For FFmpeg-style DTS-HD type IV:

- low byte identifies **DTS-HD / `0x11`**
- high bits encode the subtype derived from the repetition period

Engineering should therefore validate:

- low byte identifies DTS-HD (`0x11` in the FFmpeg model)
- high bits contain the expected subtype
- the subtype matches the computed repetition period

### Pd is data-type-dependent

Do **not** assume `Pd` semantics are identical across DTS core and DTS-HD.

Examples from FFmpeg:

- for ordinary DTS core, `length_code = core_size << 3`
- for DTS-HD type IV, `length_code = FFALIGN(out_bytes + 0x8, 0x10) - 0x8`

So engineering should validate `Pd` against the **specific transport mode** being implemented, not a generic rule of thumb.

### DTS-HD payload wrapper

For DTS-HD type IV, look for the wrapper inside the burst payload:

- `01 00 00 00 00 00 00 00 FE FE`
- followed by a 16-bit payload size
- followed by the DTS packet bytes

### What to look for in a hex dump

1. find repeating Pa/Pb
2. confirm the DTS core or DTS-HD type in `Pc`
3. confirm `Pd` using the correct DTS-core or DTS-HD rule
4. confirm burst spacing is consistent with the selected `pkt_offset`
5. for DTS-HD, confirm the wrapper start code and payload-size field
6. confirm the payload is actually HD when HD is expected, not core only

---

## 6. Byte-validation procedure against real DTS-HD MA and DTS:X samples

This is the most important addition for a fresh integration.

## Step 1 — freeze real DTS-HD MA and DTS:X samples as golden assets

Use short, known-good samples and do not rotate them until the first valid end-to-end path works.

Keep:

- `dtshd-ma-sample.mkv`
- `dtshd-ma-sample.dts`
- `dtshd-ma-ffmpeg-ref.spdif`
- `dtsx-sample.mkv`
- `dtsx-sample.dts`
- `dtsx-ffmpeg-ref.spdif`

## Step 2 — generate FFmpeg’s reference transport

```bash
ffmpeg -i dtshd-ma-sample.mkv -map 0:a:0 -c copy dtshd-ma-sample.dts
ffmpeg -i dtshd-ma-sample.dts -map 0:a:0 -c copy -f spdif dtshd-ma-ffmpeg-ref.spdif

ffmpeg -i dtsx-sample.mkv -map 0:a:0 -c copy dtsx-sample.dts
ffmpeg -i dtsx-sample.dts -map 0:a:0 -c copy -f spdif dtsx-ffmpeg-ref.spdif
```

## Step 3 — dump the first few bursts

```bash
xxd -g 1 -l 512 dtshd-ma-ffmpeg-ref.spdif > dtshd-ma-ffmpeg-ref.hex
xxd -g 1 -l 512 your_dtshd_output.spdif > your_dtshd_output.hex

xxd -g 1 -l 512 dtsx-ffmpeg-ref.spdif > dtsx-ffmpeg-ref.hex
xxd -g 1 -l 512 your_dtsx_output.spdif > your_dtsx_output.hex
```

## Step 4 — compare burst-by-burst

For burst 1 and at least the next 5 bursts, compare:

- Pa / Pb
- Pc
- Pd
- payload byte count
- payload bytes
- inter-burst spacing
- DTS-HD wrapper start code
- subtype consistency

## Step 5 — verify HD vs core-only behavior explicitly

For both the DTS-HD MA sample and the DTS:X sample, decide whether the emitted burst is:

- true DTS-HD type-IV HD payload
- or core-only fallback

Do **not** accept “some audio came out” as success.

## Step 6 — compare against the Android-boundary dump

Also compare:

- `your_dtshd_output.spdif`
- `audiotrack_write_000001.bin`

If they differ, the problem is not the packer anymore.

## Step 7 — verify the input contract too

For the first burst your packer emits, identify the exact input DTS frames it consumed and compare those bytes against the corresponding source bytes in the extracted `.dts` file.

The chain you want to prove is:

**sample.dts -> app AU -> native packer input -> packed IEC burst -> AudioTrack write**

with no unexplained mutation.

## Step 8 — what counts as success

Success is not just “audio comes out.”

Success is:

- the packed bytes match FFmpeg’s transport model
- the app path preserves those bytes unchanged
- the sink tuple is supported on the current route
- Media3 remains stable
- the AVR reports **DTS-HD MA** or **DTS:X** for the intended sample, and not just DTS core

---

## 7. Root-cause decision tree

### Case 1 — offline packer never emits a burst

**Root area:** wrong DTS frame contract or wrong DTS-HD subtype/cadence logic.

Most likely causes:

- parser is not handing off full syncframes
- incorrect strip offset
- wrong assumption that one input frame equals one output burst
- wrong block-count or repetition-period handling
- missing usable core relationship for DTS-HD mode

### Case 2 — offline emits, app path does not

**Root area:** Media3 / JNI slicing or byte corruption.

Most likely causes:

- partial access units
- wrong starting offset
- input buffers transformed before packer call
- endian mutation on the app boundary

### Case 3 — app emits valid bursts, but AVR locks only DTS core

**Root area:** HD extension preservation.

Most likely causes:

- packer fell back to core only
- Android route only accepted a core path
- parser extracted only core-sized payloads
- extension bytes were discarded before the packer

### Case 4 — app emits valid bursts, but AudioTrack does not initialize

**Root area:** route/config mismatch.

Most likely causes:

- wrong encoding tuple
- stale route capability result
- unsupported channel mask / sample rate on the active route
- stereo-only requirement on older API levels for IEC61937

### Case 5 — audio works, but startup gaps or video stutter remain

**Root area:** Media3 sink contract.

Most likely causes:

- sink accepts too much and over-buffers
- `hasPendingData()` becomes false too early or stays true too long
- `getCurrentPositionUs()` reports sink-local time instead of stream time
- writes occur on the playback thread with poor pacing/backpressure

---

## 8. Instrumentation checklist

### Native packer logs

For every candidate DTS packet:

- packet index
- PTS
- size in bytes
- first 32–64 bytes
- detected sync word
- core size
- total packet size
- sample rate
- block count
- computed repetition period
- computed subtype
- selected `Pc`
- selected `Pd`
- DTS-HD wrapper size
- overflow fallback triggered? yes/no
- emitted burst count
- emitted burst size
- emitted `pkt_offset`

### Byte-capture artifacts

- `dts_in_au_%06d.bin`
- `dts_packer_in_%06d.bin`
- `dts_packed_%06d.bin`
- `audiotrack_write_%06d.bin`

### AudioTrack config logs

For each config attempt:

- encoding numeric value and symbolic name
- sample rate
- channel mask
- route device
- direct playback support result
- AudioTrack state after creation
- chosen write mode
- failure reason code if creation fails

### Media3 sink logs

For every event:

- `handleBuffer(size, ptsUs, result)`
- queue packets / bytes / estimated buffered duration
- `hasPendingData()` and reason
- `isEnded()`
- `getCurrentPositionUs()` branch and result
- `play()` / `pause()` / `flush()` / `playToEndOfStream()`
- bytes requested / bytes written / partial remainder

---

## 9. Practical first experiments

1. **Asset validation on the DTS core, DTS-HD MA, and DTS:X samples**
2. **FFmpeg reference transport generation for all three**
3. **Offline packer harness against the extracted `.dts` files**
4. **Byte comparison of first emitted burst vs FFmpeg reference**
5. **Android IEC route bring-up with zeroed IEC test track**
6. **Real AudioTrack run with byte dumps enabled**
7. **Media3 sink-state logging under steady playback for 30–60 seconds**

---

## 10. Recommended implementation principles

If you are integrating Kodi’s IEC packer into Media3 for DTS-HD MA / DTS:X from scratch, follow these design rules:

1. **Treat DTS:X-over-HDMI as a DTS-HD type-IV transport problem first**
2. **Do not invent a DTS:X-specific public IEC data type unless you can prove it from a primary source**
3. **Use FFmpeg’s DTS core and DTS-HD type-IV logic as the public transport reference**
4. **Mirror Kodi’s Android DTS-HD IEC assumptions before experimenting**
5. **Validate bytes against real DTS-HD MA and DTS:X samples early**
6. **Probe `ENCODING_DTS`, `ENCODING_DTS_HD`, `ENCODING_DTS_HD_MA`, and `ENCODING_IEC61937` on the current route**
7. **Make Media3 sink behavior stock-like from day one**
8. **Never declare success based only on “audio comes out”**

The true success condition is:

- route supported
- bursts valid
- bytes preserved
- no silent core-only fallback
- Media3 stable
- AVR locks the expected format
- no startup gap or video stutter regressions

---

## 11. Executive summary for engineering

The main transport insight is that **DTS-HD MA and DTS:X-over-HDMI are not MAT problems**. They should be treated as **DTS core / DTS-HD type-IV IEC 61937 transport problems** whose success depends on **preserving the original HD payload**, computing the correct repetition period / subtype, emitting the correct public IEC burst type, and explicitly detecting when the implementation has silently degraded to DTS core only.

The main process insight is that the fastest path is:

1. validate the DTS-HD MA and DTS:X assets
2. generate FFmpeg’s reference IEC bytes
3. make the Kodi packer emit offline first
4. prove byte equality through the app stack
5. only then debug Media3 control-plane issues like backpressure and position reporting

That is the fresh-start methodology most likely to avoid the kind of “some audio works, but not the right codec path” trap that commonly happens with DTS-HD MA and DTS:X over Android HDMI.

---

## References

### FFmpeg

- FFmpeg `spdifenc.c` (DTS core and DTS-HD type-IV logic, subtype mapping, wrapper, overflow fallback):
  - https://www.ffmpeg.org/doxygen/3.0/spdifenc_8c_source.html
- FFmpeg `spdif.h` (`SYNCWORD1`, `SYNCWORD2`, DTS family data types):
  - https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/spdif.h
- FFmpeg DTS-HD IEC encapsulation patch history:
  - https://ffmpeg.org/pipermail/ffmpeg-devel/2011-January/106830.html
  - https://ffmpeg.org/pipermail/ffmpeg-cvslog/2011-February/034363.html

### Android / AOSP

- AOSP `AudioFormat.java` (`ENCODING_DTS`, `ENCODING_DTS_HD`, `ENCODING_DTS_HD_MA`, `ENCODING_IEC61937`, IEC notes):
  - https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/AudioFormat.java
- Android TV audio capabilities guidance:
  - https://developer.android.com/training/tv/playback/audio-capabilities
- Android CTS `AudioTrackSurroundTest.java` (framework passes IEC61937 through over HDMI and CTS uses zeroed data to validate the route):
  - https://android.googlesource.com/platform/cts/+/c9e5f7b/tests/tests/media/src/android/media/cts/AudioTrackSurroundTest.java

### Media3

- Media3 release notes (direct playback support for DTS Express and DTS:X):
  - https://developer.android.com/jetpack/androidx/releases/media3
- Media3 `MediaCodecAudioRenderer` (renderer readiness/progress depends on `audioSink.hasPendingData()`):
  - https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/MediaCodecAudioRenderer.java
- Media3 `DefaultAudioSink` (non-blocking writes, position smoothing, reconfiguration, pending-data/end-of-stream behavior):
  - https://raw.githubusercontent.com/androidx/media/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java

### Kodi

- Kodi Android sink reference (`AESinkAUDIOTRACK.cpp`):
  - https://github.com/xbmc/xbmc/blob/master/xbmc/cores/AudioEngine/Sinks/AESinkAUDIOTRACK.cpp
