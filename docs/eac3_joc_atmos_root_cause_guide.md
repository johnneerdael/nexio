# Dolby Atmos over E-AC-3 (Dolby Digital Plus JOC) on Android HDMI

## Files for testing

nexio git:(main) ✗ xxd -g 2 -l 16 ref_eac3_joc.spdif
00000000: 72f8 1f4e 1500 0014 770b 9638 3024 bce1  r..N....w..80$..

e-ac3.m2ts (track 3)
sample.eac3
ref_eac3_joc.spdif

e-ac3.m2ts (track 2)
sample.ac3
ref_ac3.spdif

Built test cases in app, embed tracks in source code and allow

## Fresh-Start Root-Cause Guide for Integrating Kodi's IEC Packer into Media3

## Goal

Build a **research-backed, from-scratch engineering guide** for carrying **Dolby Atmos delivered in Dolby Digital Plus / E-AC-3 JOC** over Android HDMI passthrough using a **Kodi-style IEC packer integrated into Media3**.

This guide is intentionally written from a **fresh-start perspective**:

- do **not** assume an existing working integration
- prove each layer independently
- validate bytes against a **real Dolby Atmos-over-E-AC-3 sample**
- use **FFmpeg, Android/AOSP, Media3, Kodi, and Dolby** as the primary external references

---

## Scope

This guide is about **Dolby Atmos over E-AC-3 JOC**, not TrueHD Atmos.

The transport model here is fundamentally different from TrueHD:

- **TrueHD Atmos** is primarily a **MAT / TrueHD** transport problem.
- **Dolby Atmos over E-AC-3 JOC** is primarily an **E-AC-3 IEC 61937 transport problem**.

The most important working assumption is:

> At the HDMI/IEC layer, Atmos-over-E-AC-3 JOC should be treated as a **bit-exact E-AC-3 transport problem first**, not as a new bespoke IEC data type.

That assumption is grounded in three external facts:

1. Dolby describes **Dolby Digital Plus with Dolby Atmos / JOC** as Dolby Atmos delivered **by Dolby Digital Plus**, where a Dolby Digital Plus with Atmos decoder reconstructs the immersive mix from a legacy 5.1 mix plus sideband/object metadata.
2. AOSP exposes **`ENCODING_E_AC3_JOC`** as a distinct compressed encoding, but explicitly says that if the downstream device supports **E-AC-3** and not **E-AC-3 JOC**, apps should use **`ENCODING_E_AC3`** for AudioTrack.
3. FFmpeg's SPDIF / IEC muxer has **E-AC-3** transport handling (`IEC61937_EAC3 = 0x15`) but no separate public IEC data type for JOC.

So the transport strategy should be:

- preserve the input **E-AC-3 JOC bitstream bytes exactly**
- perform the correct **E-AC-3 IEC aggregation and burst framing**
- make sure Media3 and AudioTrack expose a route that can actually carry the chosen tuple

---

## Fresh-start methodology

Use this exact order. Do not skip steps and do not start by changing multiple variables at once.

1. **Prove the sample asset is what you think it is**
2. **Prove the packer emits valid E-AC-3 IEC bursts offline**
3. **Generate a reference IEC stream with FFmpeg**
4. **Byte-compare your packer output against the FFmpeg reference**
5. **Verify byte equality at every app boundary**
6. **Verify Android route capability and AudioTrack tuple**
7. **Only then debug Media3 sink-state and scheduling behavior**

That sequence is the main lesson from the TrueHD work: it is much faster to isolate transport correctness early than to debug the whole player stack at once.

---

## 1. Known-good facts to anchor the investigation

### 1.1 Dolby Atmos over Dolby Digital Plus is JOC, and it is backward compatible with ordinary Dolby Digital Plus

Dolby states that Dolby Atmos can be delivered by **Dolby Digital Plus**, known as **Dolby Digital Plus with Dolby Atmos** or **Dolby Digital Plus JOC (Joint Object Coding)**. Dolby further explains that Joint Object Coding is the process by which Dolby Digital Plus with Atmos decoders reconstruct the original Dolby Atmos mix from a **legacy 5.1 mix and sideband metadata**, and that legacy Dolby Digital Plus systems still receive a surround-sound experience. Dolby also states that Dolby Digital Plus with Dolby Atmos bitstreams are **fully backward compatible with Dolby Digital Plus decoders**.

### 1.2 Android exposes both `ENCODING_E_AC3` and `ENCODING_E_AC3_JOC`

AOSP `AudioFormat` defines both:

- `ENCODING_E_AC3`
- `ENCODING_E_AC3_JOC`

and documents an important fallback rule:

> E-AC-3-JOC streams can be decoded by downstream devices supporting `ENCODING_E_AC3`; use `ENCODING_E_AC3` as the AudioTrack encoding when the downstream device supports `ENCODING_E_AC3` but not `ENCODING_E_AC3_JOC`.

That means a fresh-start implementation should **probe both encodings**, not assume one static choice.

### 1.3 Android's public IEC 61937 path is special

AOSP documents `ENCODING_IEC61937` as **compressed audio wrapped in PCM for HDMI or S/PDIF passthrough**. It also says:

- for devices whose SDK version is **less than Android S / API 31**, the channel mask of an IEC 61937 track **must be stereo**
- data **should be written as `short[]`**, and writing `byte[]` can cause endian problems on some platforms
- `ENCODING_IEC61937` counts as **linear frames** for size accounting even though it is compressed payload wrapped in PCM-like framing

These details are not optional; they directly affect route bring-up and byte-integrity debugging.

### 1.4 FFmpeg's E-AC-3 IEC transport logic is the most useful public reference

FFmpeg's `spdif_header_eac3()` is the key transport reference for a from-scratch packer.

It does four important things:

1. It reads `numblkscod` from the E-AC-3 syncframe header.
2. It maps that to an **aggregation count** using:
   - `eac3_repeat[4] = {6, 3, 2, 1}`
3. It concatenates that many E-AC-3 frames into a temporary HD buffer.
4. Once enough input frames are accumulated, it emits an IEC burst with:
   - `data_type = IEC61937_EAC3`
   - `pkt_offset = 24576`
   - `out_bytes = hd_buf_filled`
   - `length_code = hd_buf_filled`

That means E-AC-3 transport is **not** always “one syncframe in, one IEC burst out.”

The transport layer complexity comes from the fact that E-AC-3 syncframes can carry different numbers of audio blocks. The packer therefore has to **accumulate the right number of syncframes** before emitting one IEC burst.

### 1.5 For E-AC-3, FFmpeg uses IEC data type `0x15`

FFmpeg's public `spdif.h` definitions list:

- `SYNCWORD1 = 0xF872`
- `SYNCWORD2 = 0x4E1F`
- `IEC61937_EAC3 = 0x15`

So for Dolby Atmos over E-AC-3 JOC, the IEC burst should still identify as **E-AC-3 / `0x15`** at the public burst-data-type level.

### 1.6 Kodi's Android sink treats E-AC-3 as E-AC-3, not as a separate JOC transport type

Upstream Kodi's `AESinkAUDIOTRACK.cpp` shows:

- `STREAM_TYPE_EAC3` maps to `ENCODING_E_AC3`
- for E-AC-3 passthrough, Kodi notes that **“EAC3 needs real samplerate not the modulation”** and sets the sink sample rate from the stream's real sample rate
- when Kodi wants IEC passthrough and `ENCODING_IEC61937` is available, it switches to `ENCODING_IEC61937`
- for E-AC-3 in the IEC path, Kodi keeps the sink sample rate aligned to the stream sample rate
- unlike DTS-HD MA and TrueHD, Kodi does **not** preserve a multichannel mask for E-AC-3 IEC; it falls back to the stereo IEC mask path
- in Kodi's raw/non-IEC passthrough sizing logic, `STREAM_TYPE_EAC3` is explicitly commented as **“currently not supported”** and uses provisional sizing that “needs testing”
- there is **no separate `STREAM_TYPE_EAC3_JOC` handling visible in this file**

For a fresh integration, that strongly suggests:

> start by matching **Kodi's E-AC-3 IEC path**, not by inventing a JOC-specific transport path.

### 1.7 Android TV capability checks must be route-scoped and current-route aware

Android TV's audio-capabilities guidance says apps should choose the **best AudioTrack format supported by the currently routed device** and should query support using:

- `getAudioProfiles()` / `getEncodings()`
- `isDirectPlaybackSupported()`
- `AudioManager.getDirectPlaybackSupport()` on API 33+

The doc also warns that **before API 33**, `isDirectPlaybackSupported()` may return true based on some available output path, not necessarily the currently routed one. So direct-playback support must be treated as **route-scoped and time-sensitive**.

### 1.8 Media3 has known E-AC-3 JOC-specific behavior

Media3 release notes explicitly mention:

- a fix for **error checking audio capabilities for Dolby Atmos (E-AC3-JOC) in HLS**
- a fix for **decoder fallback logic for Dolby Atmos (E-AC3-JOC)** to use a compatible E-AC-3 decoder if needed

This is a strong signal that a fresh-start guide must include **source/container/parser validation** and not just transport validation.

### 1.9 Media3's renderer is tightly coupled to sink-state reporting

Upstream Media3 `MediaCodecAudioRenderer` uses `audioSink.hasPendingData()` directly for two critical behaviors:

- `isReady()` returns `audioSink.hasPendingData()`
- buffer-progress logic treats `audioSink.hasPendingData()` and `nextBufferToWritePresentationTimeUs` as a key signal for whether the sink is full and when playback can progress

Upstream `DefaultAudioSink` explicitly says it handles **playback position smoothing, non-blocking writes, and reconfiguration**. Its `hasPendingData()` is not just “queue non-empty”; it is tied to whether the output is initialized and whether written frames still remain pending in the output path.

That means a fresh custom sink must treat these as first-class design requirements, not later cleanup.

### 1.10 Android CTS gives one useful methodology clue

Android's `AudioTrackSurroundTest` contains a valuable comment for IEC 61937:

> for testing IEC61937, the Audio framework does not look at the wrapped data; it just passes it through over HDMI, so zeros can be used for the CTS throughput/rate test.

That does **not** mean receivers will lock to garbage. It means:

- you can bring up and validate the **AudioTrack IEC route** independently of real codec bytes
- then separately validate the **actual codec-correct burst bytes** needed for AVR lock

That is a very useful phase split for engineering.

---

## 2. Working assumptions and hypotheses

Because this guide starts from scratch, do **not** assume any of the following until proven:

- that Media3 is delivering clean E-AC-3 access units
- that Kodi's packer can be dropped in unchanged and still see the same packet contract
- that Android wants `ENCODING_E_AC3_JOC` rather than `ENCODING_E_AC3` or `ENCODING_IEC61937`
- that `ffprobe` metadata alone proves the sample still contains JOC
- that successful audio necessarily means Atmos/JOC survived intact

### Main hypotheses

The most likely failures, in order, are:

1. **wrong sample / wrong input contract**
2. **wrong E-AC-3 frame aggregation before IEC burst emission**
3. **wrong `Pc` / `Pd` / repetition period / packet offset**
4. **byte corruption between packer and AudioTrack**
5. **wrong Android route tuple or stale capability assumptions**
6. **Media3 sink contract mismatch**
   - bad `handleBuffer()` backpressure
   - bad `hasPendingData()`
   - bad `getCurrentPositionUs()` timebase
   - blocking/non-blocking write policy that starves playback

---

## 3. Diagnosis strategy

## Phase A — validate the test asset first

### Goal

Prove that the test asset is a real **E-AC-3 / Dolby Atmos** candidate before debugging transport.

### Minimum commands

```bash
ffprobe -hide_banner -select_streams a -show_streams INPUT.mkv
ffmpeg -ss 00:00:02 -i INPUT.mkv -t 5 -map 0:v:0 -map 0:a:0 -c copy sample-5s.mkv
ffmpeg -i sample-5s.mkv -map 0:a:0 -c copy sample.eac3
```

### What to record

- container format
- audio codec reported by `ffprobe`
- sample rate
- channel count
- any container-level Dolby/Atmos tags if present

### Important caution

Public tools may still report the elementary stream simply as **E-AC-3**. That does **not** by itself disprove Atmos/JOC, because Dolby's model is that Atmos is delivered **through Dolby Digital Plus / JOC**, not as a totally separate transport family. Use a **known-good Atmos test sample** and keep it as the fixed reference asset for the rest of the investigation.

### Test matrix

Keep at least these assets:

1. **plain E-AC-3 sample**
2. **known Dolby Atmos-over-E-AC-3 / JOC sample**
3. optional **AC-3 control sample**

The plain E-AC-3 sample helps prove base transport. The Atmos/JOC sample proves that preserving the exact input bytes also preserves the immersive extension path.

---

## Phase B — bring up Android's IEC route independently of codec correctness

### Goal

Prove that Android can open and run the chosen IEC route before involving real E-AC-3 bytes.

### Why

CTS shows that the Android framework's IEC path can be validated independently of payload correctness because the framework simply passes wrapped data through over HDMI.

### Experiment

Create a short `ENCODING_IEC61937` stereo AudioTrack and write zeroed `short[]` buffers at the intended rate.

This phase is **not** expected to lock the AVR to Dolby. It is expected to answer:

- can the route be opened?
- does the track stay initialized?
- does `play()` work?
- does the platform accept the sample rate / channel mask / encoding tuple?

If this phase fails, do not debug the E-AC-3 packer yet.

---

## Phase C — prove the packer works offline before Android touches it

### Goal

Make the Kodi IEC packer emit valid E-AC-3 bursts **offline**, before JNI / Media3 / AudioTrack are involved.

### Required harness modes

1. **whole mode** — feed one giant blob to prove the packer contract is not arbitrary
2. **sizes mode** — feed parser-sized slices / one access unit at a time
3. **scan mode** — search for valid syncframe starts and feed those

### Success condition

The harness emits a valid burst with:

- Pa / Pb present
- `Pc & 0x7F == 0x15`
- `pkt_offset == 24576`
- output payload equal to the concatenated repeated E-AC-3 syncframes for that burst

### Interpretation

- **offline works, app fails** → Media3/JNI slicing or byte corruption bug
- **offline fails too** → wrong packer contract or wrong assumptions about E-AC-3 frame boundaries

---

## Phase D — generate an FFmpeg reference IEC stream

### Goal

Produce a **reference IEC stream** from the real Atmos-over-E-AC-3 sample and compare your output against it.

### Reference commands

```bash
ffmpeg -i sample.eac3 -map 0:a:0 -c copy -f spdif ffmpeg_ref.spdif
xxd -g 1 -l 256 ffmpeg_ref.spdif
```

### Why this works

FFmpeg's `spdif_header_eac3()` is an open reference implementation for the exact transport logic you need to reproduce:

- parse E-AC-3 frame header
- determine aggregation count from `numblkscod`
- accumulate frames until ready
- emit `IEC61937_EAC3`
- use `pkt_offset = 24576`
- set `length_code = aggregated_payload_bytes`

### What to compare

Compare your packer output against `ffmpeg_ref.spdif` for:

- Pa / Pb
- `Pc`
- `Pd`
- burst-to-burst spacing
- payload byte count
- first burst payload bytes
- first N bursts, not just burst 1

### Expected differences to account for

- If your app later writes through a little-endian `short[]`-based path, a dump at the AudioTrack boundary may show the **byte-swapped 16-bit view** of Pa/Pb.
- That is acceptable **only if** the logical 16-bit preamble words remain correct.

---

## Phase E — verify byte equality across the app pipeline

### Goal

Prove that the same valid bytes survive every app boundary.

### Capture points

1. input E-AC-3 access unit bytes
2. bytes passed into the native packer
3. native packed IEC burst bytes
4. bytes passed into `AudioTrack.write`

### Minimum invariant

For the first emitted burst:

**`packed_iec_before_android == audio_track_write_payload`**

If those differ, stop and debug the handoff before chasing route or AVR issues.

### Recommended artifact names

- `eac3_in_au_000001.bin`
- `eac3_packed_000001.bin`
- `audiotrack_write_000001.bin`

### Why this matters for Atmos/JOC

Because Atmos-over-E-AC-3 depends on preserving the **original Dolby Digital Plus JOC bytes**, any strip/offset/repacketization bug can silently degrade the stream to “ordinary E-AC-3 behavior” even when some audio still comes out.

---

## Phase F — verify Android route and AudioTrack tuple

### Goal

Choose the correct tuple for the **currently routed device**, not a guessed tuple.

### Probe these encodings on the active route

- `ENCODING_E_AC3`
- `ENCODING_E_AC3_JOC`
- `ENCODING_IEC61937`

### Route queries

Use:

- `getAudioProfiles()` / `getEncodings()`
- `isDirectPlaybackSupported()`
- `AudioManager.getDirectPlaybackSupport()` on API 33+

### Fresh-start rule

Do not hard-code “JOC means use `ENCODING_E_AC3_JOC`.”

AOSP explicitly says to use `ENCODING_E_AC3` when the downstream device supports E-AC-3 but not E-AC-3 JOC.

### Kodi grounding

Kodi's Android sink strongly suggests that its actual E-AC-3 passthrough path is grounded in **E-AC-3 / IEC61937 semantics**, not a separate Android JOC-specific transport path:

- `STREAM_TYPE_EAC3 -> ENCODING_E_AC3`
- real sample rate for E-AC-3
- `ENCODING_IEC61937` if Kodi wants IEC passthrough
- stereo IEC mask for E-AC-3 on Android

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

## 4. E-AC-3 / Dolby Atmos-over-E-AC-3 transport invariants

### 4.1 E-AC-3 is different from AC-3 at the IEC level

Ordinary AC-3 transport is much simpler in FFmpeg:

- `data_type = IEC61937_AC3 | (bitstream_mode << 8)`
- `pkt_offset = AC3_FRAME_SIZE << 2`

E-AC-3 is different because the packer may need to **aggregate multiple syncframes** before one IEC burst is emitted.

### 4.2 FFmpeg's E-AC-3 aggregation rule

FFmpeg uses:

- `eac3_repeat[4] = {6, 3, 2, 1}`

and, when `fscod != 0x3`, chooses the aggregation count from:

- `repeat = eac3_repeat[(numblkscod)]`

The practical meaning is:

- some E-AC-3 syncframes need to be grouped before one IEC burst is ready
- until the required count is reached, FFmpeg sets `pkt_offset = 0` and emits nothing

This is one of the most important transport facts to carry into a fresh packer.

### 4.3 Final E-AC-3 burst settings in FFmpeg

Once enough frames are accumulated, FFmpeg sets:

- `data_type = IEC61937_EAC3`
- `pkt_offset = 24576`
- `out_buf = hd_buf`
- `out_bytes = hd_buf_filled`
- `length_code = hd_buf_filled`

For engineering, that means:

- **Pc low 7 bits should identify `0x15`**
- **Pd should match the aggregated payload byte count**
- the next burst should start at a spacing consistent with `pkt_offset = 24576`

### 4.4 Atmos/JOC does not imply a different public IEC data type here

Because Dolby describes Atmos delivery here as **Dolby Digital Plus with JOC**, and FFmpeg's public E-AC-3 IEC path uses `IEC61937_EAC3`, the transport hypothesis should be:

> if the input JOC bitstream is preserved exactly, the IEC transport layer should still identify as E-AC-3 (`0x15`) rather than a special new JOC IEC type.

That is a working engineering assumption, not a licensing statement about proprietary metadata internals.

---

## 5. IEC 61937 burst preamble verification for E-AC-3 / E-AC-3 JOC

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

### Pc for E-AC-3

For FFmpeg-style E-AC-3 IEC bursts:

- `IEC61937_EAC3 = 0x15`

So the key validation is:

- `Pc & 0x7F == 0x15`

### Pd for E-AC-3

For FFmpeg's E-AC-3 path, `length_code = hd_buf_filled`.

That means `Pd` should be interpreted as the **aggregated payload byte count** in this working implementation.

### What to look for in a hex dump

1. find repeating Pa/Pb
2. confirm `Pc & 0x7F == 0x15`
3. confirm `Pd == aggregated_payload_bytes`
4. confirm the burst spacing is consistent with `pkt_offset = 24576`
5. confirm no stray strip/offset has altered the original E-AC-3 bytes inside the payload

---

## 6. Byte-validation procedure against a real Atmos-over-E-AC-3 sample

This is the most important addition for a fresh integration.

## Step 1 — freeze one real Atmos-over-E-AC-3 sample as the golden asset

Use one short, known-good sample and do not rotate samples until the first valid end-to-end path works.

Keep:

- `atmos-ddp-sample.mkv`
- `atmos-ddp-sample.eac3`
- `ffmpeg_ref.spdif`

## Step 2 — generate FFmpeg's reference transport

```bash
ffmpeg -i atmos-ddp-sample.mkv -map 0:a:0 -c copy atmos-ddp-sample.eac3
ffmpeg -i atmos-ddp-sample.eac3 -map 0:a:0 -c copy -f spdif ffmpeg_ref.spdif
```

## Step 3 — dump the first few bursts

```bash
xxd -g 1 -l 512 ffmpeg_ref.spdif > ffmpeg_ref.hex
xxd -g 1 -l 512 your_packer_output.spdif > your_packer.hex
```

## Step 4 — compare burst-by-burst

For burst 1 and at least the next 5 bursts, compare:

- Pa / Pb
- Pc
- Pd
- payload byte count
- payload bytes
- inter-burst spacing

## Step 5 — compare against the Android-boundary dump

Also compare:

- `your_packer_output.spdif`
- `audiotrack_write_000001.bin`

If they differ, the problem is not the packer anymore.

## Step 6 — verify the input contract too

For the first burst your packer emits, identify the exact input E-AC-3 frames it consumed and compare those bytes against the corresponding source bytes in `atmos-ddp-sample.eac3`.

The chain you want to prove is:

**sample.eac3 -> app AU -> native packer input -> packed IEC burst -> AudioTrack write**

with no unexplained mutation.

## Step 7 — what counts as success

Success is not just “audio comes out.”

Success is:

- the packed bytes match FFmpeg's transport model
- the app path preserves those bytes unchanged
- the sink tuple is supported on the current route
- Media3 remains stable
- the AVR reports Dolby Atmos (or, at minimum during early bring-up, Dolby Digital Plus when testing with a non-JOC control sample)

---

## 7. Root-cause decision tree

### Case 1 — offline packer never emits a burst

**Root area:** wrong E-AC-3 frame contract or wrong aggregation logic.

Most likely causes:

- parser is not handing off full syncframes
- incorrect strip offset
- wrong assumption that one input frame equals one output burst
- wrong `numblkscod` / repeat handling

### Case 2 — offline emits, app path does not

**Root area:** Media3 / JNI slicing or byte corruption.

Most likely causes:

- partial access units
- wrong starting offset
- input buffers transformed before packer call
- endian mutation on the app boundary

### Case 3 — app emits valid bursts, but AVR does not lock Atmos

**Root area:** route/config mismatch or JOC content not surviving.

Most likely causes:

- track opened with the wrong encoding tuple
- stale route capability result
- IEC path correct, but source bytes are no longer the original JOC payload
- upstream decode/transcode or parser fallback removed Atmos information while leaving base E-AC-3 intact

### Case 4 — audio works, but startup gaps or video stutter remain

**Root area:** Media3 sink contract.

Most likely causes:

- sink accepts too much and over-buffers
- `hasPendingData()` becomes false too early or stays true too long
- `getCurrentPositionUs()` reports sink-local time instead of stream time
- writes occur on the playback thread with poor pacing/backpressure

---

## 8. Instrumentation checklist

### Native packer logs

For each candidate E-AC-3 frame:

- frame index
- PTS
- size in bytes
- first 32–64 bytes
- `fscod`
- `numblkscod`
- computed repeat count
- accumulated frame count toward next burst
- accumulated payload bytes toward next burst
- emitted burst count
- emitted `Pc`
- emitted `Pd`
- emitted burst size
- emitted `pkt_offset`

### Byte-capture artifacts

- `eac3_in_au_%06d.bin`
- `eac3_packer_in_%06d.bin`
- `eac3_packed_%06d.bin`
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

1. **Asset validation on the real Atmos-over-E-AC-3 sample**
2. **FFmpeg reference transport generation**
3. **Offline packer harness against the extracted `.eac3`**
4. **Byte comparison of first emitted burst vs FFmpeg reference**
5. **Android IEC route bring-up with zeroed IEC test track**
6. **Real AudioTrack run with byte dumps enabled**
7. **Media3 sink-state logging under steady playback for 30–60 seconds**

---

## 10. Recommended implementation principles

If you are integrating Kodi's IEC packer into Media3 for Atmos-over-E-AC-3 from scratch, follow these design rules:

1. **Treat Dolby Atmos-over-E-AC-3 as an E-AC-3 transport problem first**
2. **Do not invent a JOC-specific IEC data type unless you can prove it from a primary source**
3. **Use FFmpeg's `spdif_header_eac3()` as the public transport reference**
4. **Mirror Kodi's Android E-AC-3 IEC assumptions before experimenting**
5. **Validate bytes against a real Atmos sample early**
6. **Probe `ENCODING_E_AC3`, `ENCODING_E_AC3_JOC`, and `ENCODING_IEC61937` on the current route**
7. **Make Media3 sink behavior stock-like from day one**
8. **Never declare success based only on “audio comes out”**

The true success condition is:

- route supported
- bursts valid
- bytes preserved
- Media3 stable
- AVR locks the expected format
- no startup gap or video stutter regressions

---

## 11. Executive summary for engineering

The main transport insight is that **Dolby Atmos over Dolby Digital Plus / JOC is not a MAT problem**. It should be treated as an **E-AC-3 IEC 61937 transport problem** whose success depends on **preserving the original E-AC-3 JOC bytes**, aggregating the right number of syncframes per burst, and emitting a correct `IEC61937_EAC3` burst (`Pc = 0x15`, `pkt_offset = 24576`, `Pd = aggregated payload bytes`) while using a route-supported Android tuple.

The main process insight is that the fastest path is:

1. validate the Atmos-over-E-AC-3 asset
2. generate FFmpeg's reference IEC bytes
3. make the Kodi packer emit offline first
4. prove byte equality through the app stack
5. only then debug Media3 control-plane issues like backpressure and position reporting

That is the fresh-start methodology most likely to avoid the kind of “audio works but not correctly” trap that commonly happens with E-AC-3 and Atmos over Android HDMI.

---

## References

### FFmpeg

- FFmpeg `spdifenc.c` (E-AC-3 header logic, aggregation, `pkt_offset = 24576`, `length_code = hd_buf_filled`):
  - https://www.ffmpeg.org/doxygen/3.0/spdifenc_8c_source.html
- FFmpeg `spdif.h` (`SYNCWORD1`, `SYNCWORD2`, `IEC61937_EAC3 = 0x15`):
  - https://ffmpeg.org/doxygen/7.1/spdif_8h.html

### Android / AOSP

- AOSP `AudioFormat.java` (`ENCODING_IEC61937`, stereo rule on pre-S, short[] note, `ENCODING_E_AC3_JOC` fallback guidance):
  - https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/AudioFormat.java
- Android TV audio capabilities guidance:
  - https://developer.android.com/training/tv/playback/audio-capabilities
- Android CTS `AudioTrackSurroundTest.java` (framework passes IEC 61937 through over HDMI and CTS uses zeroed data to validate the route):
  - https://android.googlesource.com/platform/cts/+/c9e5f7b/tests/tests/media/src/android/media/cts/AudioTrackSurroundTest.java

### Media3

- Media3 release notes (E-AC3-JOC capability/error-checking and decoder fallback fixes):
  - https://developer.android.com/jetpack/androidx/releases/media3
- Media3 `MediaCodecAudioRenderer` (renderer readiness/progress depends on `audioSink.hasPendingData()`):
  - https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/MediaCodecAudioRenderer.java
- Media3 `DefaultAudioSink` (non-blocking writes, position smoothing, reconfiguration, pending-data/end-of-stream behavior):
  - https://raw.githubusercontent.com/androidx/media/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java

### Kodi

- Kodi Android sink reference (`AESinkAUDIOTRACK.cpp`):
  - https://github.com/xbmc/xbmc/blob/master/xbmc/cores/AudioEngine/Sinks/AESinkAUDIOTRACK.cpp

### Dolby

- Dolby AC-4 / Dolby Atmos page with explanation that Dolby Atmos can also be delivered by **Dolby Digital Plus**, called **Dolby Digital Plus with Dolby Atmos / Dolby Digital Plus JOC**, using a legacy 5.1 mix plus sideband metadata:
  - https://professional.dolby.com/en-gb/technologies/ac-4/
- Dolby support summary stating Dolby Digital Plus with Dolby Atmos bitstreams are backward compatible with Dolby Digital Plus decoders:
  - https://professionalsupport.dolby.com/s/article/Dolby-Atmos-Backward-Compatible
