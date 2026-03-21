# TrueHD over HDMI Passthrough Root-Cause Guide

## Goal

Find why **AC-3, E-AC-3, DTS, and DTS-HD MA work**, but **TrueHD does not lock on the AVR** over the new Media3 + JNI + IEC/HBR path.

## Working assumption

Because **DTS-HD MA already works**, the broad **HBR transport path** is likely functional. That shifts suspicion away from “HDMI HBR is impossible” and toward **TrueHD-specific framing, cadence, buffering, or Android handling**. FFmpeg’s reference IEC muxer treats TrueHD differently from the other codecs: it states that TrueHD must be **encapsulated in MAT frames before IEC 61937**, and then **padded to a constant rate**. It also uses a **24-frame aggregation model**, a **61424-byte MAT frame size**, and a **2560-byte nominal spacing per TrueHD frame interval**.

## Key hypothesis

The failure is most likely in one of these four places, in order:

1. **Invalid TrueHD-to-MAT encapsulation**
2. **Wrong cadence / padding / repetition period**
3. **Burst corruption between JNI → Media3 → AudioTrack**
4. **Android route/config mismatch for IEC61937 / TrueHD / MAT**

Android explicitly distinguishes **ENCODING_IEC61937**, **ENCODING_DOLBY_TRUEHD**, and **ENCODING_DOLBY_MAT**, and documents MAT as the HDMI transport used for TrueHD and related Dolby streams. Android also notes that IEC61937 is compressed audio **wrapped in PCM**, and warns that writing IEC61937 through `byte[]` may cause endian problems on some platforms when converted internally.

---

## 1. Known-good facts to anchor the investigation

### TrueHD is not “just another IEC burst payload”

FFmpeg’s IEC 61937 muxer comments state:

- TrueHD frames appear to need **MAT encapsulation** before IEC 61937 transport.
- FFmpeg groups **24 TrueHD frames into one MAT frame**.
- It pads them to achieve a **constant rate**.
- It uses:
  - `MAT_FRAME_SIZE = 61424`
  - `TRUEHD_FRAME_OFFSET = 2560`

That is the biggest architectural difference from AC-3 / DTS style passthrough.

### Cadence matters

FFmpeg’s timing logic documents that for TrueHD the nominal transport budget is **2560 bytes per frame interval**, derived from the HDMI/IEC clocking model for both 48 kHz-family and 44.1 kHz-family timing. If output spacing is wrong, an AVR may never lock even when the payload bytes look plausible.

### Android can expose several related paths

Android defines:

- `ENCODING_IEC61937`
- `ENCODING_DOLBY_TRUEHD`
- `ENCODING_DOLBY_MAT`

and describes MAT as being used to transmit **Dolby TrueHD** over HDMI. Android’s HDMI codec mapping also associates **TRUEHD** with both **ENCODING_DOLBY_TRUEHD** and **ENCODING_DOLBY_MAT**.

---

## 2. Diagnosis strategy

Run the investigation in this order. Do not start by changing several variables at once.

### Phase A — prove the packed bytes are correct before Android touches them

Capture and save a binary dump at these points:

1. **Input TrueHD access units** entering the packer
2. **Output of the Kodi IEC/MAT packer** in JNI
3. **Exact bytes passed into AudioTrack.write**
4. Optionally, bytes after any Java-side buffer transformation if one exists

The first question to answer:

**Is the packer generating a valid, stable MAT/IEC stream before Android gets involved?**

#### What to inspect in the dump

Check for IEC burst structure:

- `Pa = 0xF872`
- `Pb = 0x4E1F`
- `Pc` contains data type / burst info
- `Pd` contains payload length info

#### What success looks like

You should see:

- stable burst preambles
- consistent repetition period
- no random drift in burst spacing
- MAT-structured payloads, not raw unsmoothed TrueHD AUs
- correct zero padding between bursts where required

#### Likely failure signatures

- correct preamble, wrong payload type or cadence
- payload lengths varying but no constant-rate compensation
- burst boundary splits in the middle of MAT units
- occasional endian-swapped preamble values
- missing or late bursts after buffer underflow or chunk-size changes

### Phase B — compare TrueHD packer behavior against the known working model

Use FFmpeg/Kodi behavior as the comparison target.

#### Validate these specific invariants

1. **Aggregation model**
   - Are multiple TrueHD frames collected into a MAT container?
   - Is the implementation effectively following the **24-frame** model or an equivalent working MAT cadence?

2. **Frame spacing**
   - Are successive TrueHD contributions placed on a **2560-byte interval grid**?

3. **Total MAT size**
   - Is the constructed MAT unit consistent with the reference-size expectations, including end markers and padding?
   - FFmpeg uses **61424 bytes**.

4. **Padding logic**
   - Is exact zero-padding inserted to maintain constant-rate transport?
   - Is padding based on time or sample deltas rather than just “fill remaining bytes”?

5. **Boundary alignment**
   - Does the handoff to AudioTrack preserve whole logical burst units?
   - Even if exact AU-to-MAT alignment is not always required, corrupting MAT grouping with arbitrary cuts is still a likely failure source.

#### Engineering conclusion for this phase

If the packer does **raw TrueHD AU → IEC burst** without the MAT-style constant-rate transport behavior, that is almost certainly the root cause.

### Phase C — verify JNI / Media3 / Java buffer handling is lossless

Even if the packer is right, the stream can still be destroyed in transit.

#### Inspect for these issues

##### Endianness corruption

Android explicitly warns that IEC61937 written as `byte[]` may have endian issues on some platforms when internally converted to shorts. If the path writes packed bursts through the wrong API shape, preambles may be byte-flipped or otherwise damaged.

Check:

- whether the path writes `byte[]`, `short[]`, or `ByteBuffer`
- native byte order assumptions
- any manual byte swapping in JNI
- whether AudioTrack or wrapper code repacks to shorts

##### Partial-write handling

Confirm that:

- `AudioTrack.write()` return values are checked
- short writes are retried correctly
- burst boundaries are preserved across retries
- no data is skipped when the sink backpressures

##### Buffer coalescing or splitting

Check whether Media3 or the sink wrapper:

- merges unrelated bursts
- splits bursts at arbitrary offsets
- re-chunks output based on PCM assumptions rather than transport unit boundaries

##### Silent data mutation

Audit for any stage that might apply:

- volume scaling
- mixing
- resampling
- format conversion
- channel remixing

Any of those will destroy an IEC/MAT bitstream immediately.

### Phase D — verify AudioTrack and route configuration

Android supports several relevant encodings, but exposure of a constant does not guarantee the route is actually using the intended hardware path.

#### Check these items

##### Direct or passthrough route

Verify that the track is actually opened in a direct passthrough-compatible path and not falling back to a mixer path.

##### Encoding selected

Log which encoding is used at runtime:

- `ENCODING_IEC61937`
- `ENCODING_DOLBY_TRUEHD`
- `ENCODING_DOLBY_MAT`

Do not assume the route negotiated what was requested.

##### Channel mask and sample rate

Because the HBR path is already proven by DTS-HD MA, this is less likely to be the primary bug, but still verify that the TrueHD path uses the same known-good HBR-style transport parameters and does not silently diverge.

##### Device capability reporting

Log sink capabilities as seen by Android and compare TrueHD/MAT exposure with DTS-HD MA exposure.

---

## 3. IEC 61937 burst preamble verification for TrueHD

Each IEC 61937 burst begins with four 16-bit words:

- **Pa** = sync word 1 = `0xF872`
- **Pb** = sync word 2 = `0x4E1F`
- **Pc** = burst info
- **Pd** = length code

In a hex dump, depending on host byte order and how the dump is rendered, these may appear as logical 16-bit values or byte-swapped byte pairs. The first check is that the burst stream contains the expected repeating **Pa/Pb** sync pattern.

### Expected fields for TrueHD

#### Pa (sync word 1)

- Logical value: `0xF872`
- Common byte views:
  - big-endian style: `F8 72`
  - little-endian style: `72 F8`

#### Pb (sync word 2)

- Logical value: `0x4E1F`
- Common byte views:
  - big-endian style: `4E 1F`
  - little-endian style: `1F 4E`

#### Pc (burst info)

Pc contains the IEC 61937 burst info fields. The important invariant for TrueHD is that the **low 7 bits identify data type `0x16`**. Engineering should therefore validate **the low 7 bits**, not assume the entire 16-bit Pc word is always literally `0x0016` in every implementation.

#### Pd (length code)

Pd is the payload length code, but it is **data-type-dependent**. It is not safe to describe it generically as “always bits” for TrueHD. FFmpeg’s working TrueHD/MAT path sets the TrueHD length code to `MAT_FRAME_SIZE`, which is **61424**, indicating a byte-oriented interpretation in that implementation. That is the right model to use for troubleshooting here.

### What engineering should look for in a dump

1. **Find the sync pair**
   - Search for repeated instances of:
     - `F8 72 4E 1F`, or
     - `72 F8 1F 4E`
   - If those sync words do not appear at all, the output is not a valid IEC 61937 burst stream.

2. **Validate TrueHD data type in Pc**
   - After Pa/Pb, inspect Pc and confirm that **`Pc & 0x7F == 0x16`**.
   - Do not rely only on a visual check for `00 16`, because upper bits in Pc may vary by implementation.

3. **Validate Pd against the expected transport model**
   - For this project, use the working MAT-style reference model: TrueHD is encapsulated into MAT-style transport, and the FFmpeg reference implementation sets Pd to **61424** for the assembled MAT frame.
   - If Pd semantics differ, that needs to be deliberate and justified, not accidental.

4. **Check burst spacing and zero padding**
   - The next preamble should begin at the expected repetition interval, and the unused gap between payload end and the next burst should be zero-filled.
   - For the FFmpeg TrueHD path, `MAT_FRAME_SIZE = 61424` and `pkt_offset = 61440`, so the payload is followed by the expected padding gap before the next burst.

### Why this matters

For AC-3 and ordinary DTS paths, “correct sync word + plausible payload” is often enough to get close. For TrueHD, that is not enough. A receiver may still fail to lock if:

- Pc does not identify TrueHD correctly
- Pd does not match the MAT-style burst construction
- burst repetition spacing is wrong
- the zero-padding region is wrong
- JNI / Media3 / AudioTrack alters byte order or splits bursts incorrectly

### Practical check to add to debug tooling

For each emitted burst, log:

- Pa
- Pb
- raw Pc
- `Pc & 0x7F`
- Pd
- payload bytes emitted
- zero-padding bytes emitted
- byte offset to next Pa/Pb preamble

That will make it much easier to prove whether the failure is in:

- preamble construction
- MAT assembly
- repetition-period math
- corruption after packing

---

## 4. Root-cause decision tree

### Case 1: Packer output is already wrong before AudioTrack

**Root cause:** TrueHD-specific encapsulation bug.

Most likely issues:

- raw TrueHD or MLP payload sent instead of MAT-style framing
- wrong MAT header or body construction
- wrong frame aggregation count
- wrong padding math
- wrong payload length fields
- wrong repetition period

**Fix direction:** Rework the packer to match a known working MAT/IEC construction and verify with hexdumps before sending to Android.

### Case 2: Packer output is correct, AudioTrack input differs

**Root cause:** JNI, Java, or Media3 corruption.

Most likely issues:

- endian swap
- chunk split or drop
- partial write bug
- ByteBuffer position or limit misuse
- extra copy path altering bytes

**Fix direction:** Reduce copies, use one binary-safe path, add byte-for-byte assertions between stages.

### Case 3: AudioTrack input is correct, AVR still does not lock

**Root cause:** route/config mismatch or sink rejection.

Most likely issues:

- wrong encoding or routing mode
- mixer path instead of direct path
- unsupported TrueHD/MAT exposure on this route or device
- HBR route okay for DTS-HD but not opened correctly for TrueHD signaling

**Fix direction:** instrument route negotiation and compare against a known-good platform or player behavior.

---

## 5. Concrete instrumentation checklist

Add logs for every write session:

- codec = TrueHD
- incoming AU size
- incoming AU sample count or timestamp delta
- packed burst size
- packed burst count
- preamble words
- computed `Pc`
- computed `Pd`
- padding bytes inserted
- MAT frame counter
- AudioTrack encoding
- AudioTrack sample rate
- channel mask
- direct, offload, or passthrough flags
- write size requested
- write size returned
- underrun and short-write counters

Also add a debug mode that writes:

- `truehd_in_au_%06d.bin`
- `truehd_packed_%06d.bin`
- `audiotrack_write_%06d.bin`

The first milestone is proving:

**`truehd_packed == audiotrack_write` byte-for-byte**

---

## 6. Fastest experiments to isolate the bug

### Experiment 1 — offline packer verification

Feed known TrueHD samples through the packer with Android removed from the loop. Compare output shape against a known-good implementation.

**Interpretation:**
- mismatch here means packer bug
- match here pushes suspicion to Android path

### Experiment 2 — byte equality across stages

Hash the buffer after packing and right before `AudioTrack.write`.

**Interpretation:**
- hash mismatch means transport corruption inside the app or framework path

### Experiment 3 — force same write granularity every time

Write fixed-size blocks aligned to burst or MAT boundaries.

**Interpretation:**
- if lock starts working, the bug is chunking or boundary related

### Experiment 4 — alternate encoding path

If feasible on the device, compare:

- custom HBR or IEC path
- native `ENCODING_IEC61937`
- native `ENCODING_DOLBY_TRUEHD`
- native `ENCODING_DOLBY_MAT`

**Interpretation:**
- only custom path failing suggests packer or transport bug
- only certain Android encodings failing suggests route or config issue

### Experiment 5 — known-good content matrix

Test several TrueHD files:

- 48 kHz
- 96 kHz
- 7.1
- 5.1
- Atmos-bearing TrueHD if available
- smaller and larger TrueHD frame sizes

**Interpretation:**
- only some files fail suggests frame-size or padding logic bug
- all files fail suggests systemic encapsulation or routing bug

---

## 7. Most probable fix paths

### Fix path A — rebase TrueHD packing to the proven MAT model

If the current implementation diverges from the known working FFmpeg or Kodi-style behavior, this is the top candidate. Use the reference model:

- MAT-style encapsulation
- 24-frame accumulation model
- 2560-byte frame interval logic
- constant-rate zero padding
- stable MAT frame termination and assembly

### Fix path B — make the app path byte-transparent

If the packer is correct but bytes change later:

- eliminate extra buffer conversions
- prefer direct `ByteBuffer` handling
- lock byte order explicitly
- handle short writes rigorously
- preserve burst boundaries

### Fix path C — align Android route selection with the actual transport

If bytes are correct but lock still fails:

- verify direct route selection
- verify reported sink support
- test `ENCODING_DOLBY_MAT` vs `ENCODING_IEC61937` behavior if available
- ensure nothing passes through mixer, resampler, or volume stages

---

## 8. Executive summary for engineering

**Why TrueHD is the outlier:**
AC-3, E-AC-3, DTS, and even DTS-HD MA can succeed once the HBR transport is working, but TrueHD is more sensitive because it relies on **MAT-style encapsulation and strict constant-rate pacing**, not just “wrap frame in IEC and send it.” FFmpeg’s reference implementation explicitly handles TrueHD this way, using **24-frame MAT grouping**, a **61424-byte MAT frame**, and **2560-byte spacing logic**. Android also distinguishes **TrueHD**, **MAT**, and **IEC61937** as separate transport-relevant encodings.

**Most likely root cause:**
The TrueHD path is failing in **MAT construction, padding cadence, or burst integrity through JNI or AudioTrack**, not in the basic HDMI HBR concept.

## References

- FFmpeg SPDIF / IEC 61937 implementation and comments on TrueHD MAT encapsulation:
  - https://www.ffmpeg.org/doxygen/3.0/spdifenc_8c_source.html
- FFmpeg older IEC 61937 preamble field documentation:
  - https://www.ffmpeg.org/doxygen/0.11/spdifenc_8c-source.html
- FFmpeg coverage view showing TrueHD spacing constants and packet offset behavior:
  - https://coverage.ffmpeg.org/index.spdifenc.c.5657511e324be5f44d97a42ac0311695.html
- FFmpeg mailing list definition mapping TrueHD data type to `0x16`:
  - https://ffmpeg.org/pipermail/ffmpeg-devel/2010-November/097783.html
- Android AudioFormat definitions for IEC61937, Dolby TrueHD, and Dolby MAT:
  - https://android.googlesource.com/platform/prebuilts/fullsdk/sources/%2B/refs/heads/androidx-compose-integration-release/android-34/android/media/AudioFormat.java
