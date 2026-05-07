# Media3 Dolby Streaming Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move DV7 to DV8.1 sample rewriting into the Nexio-owned Media3 fork so Matroska playback can stream base-layer HEVC NALs directly instead of allocating full input and output sample byte arrays.

**Architecture:** Add an optional streaming/NAL-level Dolby Vision transformer API to the forked Media3 Matroska extractor while keeping the existing full-sample `byte[] transformHevcSample(...)` fallback. Media3 will own length-delimited sample parsing, enhancement-layer dropping, Annex-B output, sample byte accounting, and malformed-sample handling; the Nexio app hook will own profile/mode selection and tiny RPU conversion only. Implement Matroska first because the measured 131GB DV7 REMUX is MKV; MP4/FMP4 remain on the existing byte-array fallback until separately profiled.

**Tech Stack:** Java Media3 fork (`media/libraries/extractor`), Kotlin Nexio app hook, AndroidX `TrackOutput`, JUnit/Robolectric-style unit tests, Gradle composite source mode (`USE_MEDIA3_SOURCE=true` by default).

---

## Evidence And Scope

Current fork behavior allocates full samples before app code can transform them:

- `media/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java`
  - `DolbyVisionSampleTransformer.transformHevcSample(...)` returns `byte[]`.
  - The DV path reads the full sample into `sampleLengthDelimitedData`, calls the transformer, then writes the full returned sample.
- `media/libraries/extractor/src/main/java/androidx/media3/extractor/mp4/Mp4Extractor.java`
  - Same `byte[] transformHevcSample(...)` shape.
- `media/libraries/extractor/src/main/java/androidx/media3/extractor/mp4/FragmentedMp4Extractor.java`
  - Same `byte[] transformHevcSample(...)` shape.

Runtime baseline before the app-side helper:

- `rewriteInMb=305`, `rewriteOutMb=305`, and old `nalCopyMb=305` by `pos=59s`.
- After the app-side helper, full-sample input and output arrays still remain because the Media3 API requires `byte[]` samples.

This plan targets the next ceiling: avoid full-sample arrays in the Matroska normal write path.

## Non-Goals

- Do not change libdovi native conversion.
- Do not change DV7 standard mode `2` or preserve-mapping mode `5`.
- Do not remove the existing full-sample transformer API.
- Do not optimize MP4/FMP4 in this pass.
- Do not change TS RPU conversion in this pass.
- Do not change behavior for `deferSupplementalMainSampleSizePrefix`; keep that branch on the existing full-buffer fallback unless a later task explicitly adds a two-pass size preflight.
- Do not change user-facing playback settings in this plan.

## File Structure

- Modify: `media/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java`
  - Add a streaming transformer method to `DolbyVisionSampleTransformer`.
  - Add a private helper that reads length-delimited HEVC NALs from `ExtractorInput`, streams base-layer NALs to `TrackOutput` as Annex-B, drops enhancement-layer non-RPU NALs, converts RPU NALs via transformer callback, and returns bytes written.
  - Keep existing `transformHevcSample(...)` full-sample path as fallback.

- Create: `media/libraries/extractor/src/test/java/androidx/media3/extractor/mkv/MatroskaDolbyVisionStreamingRewriterTest.java`
  - Unit-test the streaming helper behavior with synthetic length-delimited HEVC samples.
  - Use reflection only if the helper remains private; otherwise make the helper package-private and `@VisibleForTesting`.

- Modify: `app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt`
  - Implement the new streaming transformer methods through the existing dynamic proxy.
  - Keep `transformHevcSample(...)` for MP4/FMP4 fallback.
  - Route streaming RPU conversion through the existing `DoviBridge.convertDv7RpuToDv81(...)`.

- Modify: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionDiagnosticsTest.kt`
  - Add/adjust reflection-based tests proving the new streaming hook can publish mode diagnostics and convert RPU payloads without invoking full-sample transformation.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`
  - Only if new diagnostics counters are added; otherwise leave unchanged.

---

### Task 1: Add Media3 Streaming API Contract

**Files:**
- Modify: `media/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java`
- Create: `media/libraries/extractor/src/test/java/androidx/media3/extractor/mkv/MatroskaDolbyVisionStreamingRewriterTest.java`

- [ ] **Step 1: Add red contract tests for a new streaming callback**

Create `media/libraries/extractor/src/test/java/androidx/media3/extractor/mkv/MatroskaDolbyVisionStreamingRewriterTest.java`:

```java
package androidx.media3.extractor.mkv;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.TrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class MatroskaDolbyVisionStreamingRewriterTest {

  @Test
  public void streamingTransformerInterface_hasRpuCallbackAndSampleDecision() {
    MatroskaExtractor.DolbyVisionSampleTransformer transformer =
        new MatroskaExtractor.DolbyVisionSampleTransformer() {};

    assertThat(
            transformer.shouldTransformHevcSampleNalByNal(
                /* sampleTimeUs= */ 12_345L,
                /* nalUnitLengthFieldLength= */ 4,
                /* blockAdditionalData= */ null,
                /* dolbyVisionConfigBytes= */ null))
        .isFalse();

    byte[] rpu = nal(/* type= */ 62, /* layerId= */ 1, new byte[] {0x11});
    assertThat(
            transformer.transformDolbyVisionRpuNal(
                rpu,
                /* sampleTimeUs= */ 12_345L,
                /* blockAdditionalData= */ null,
                /* dolbyVisionConfigBytes= */ null))
        .isNull();
  }

  private static byte[] nal(int type, int layerId, byte[] payload) {
    byte[] out = new byte[2 + payload.length];
    out[0] = (byte) ((type << 1) | ((layerId >>> 5) & 0x01));
    out[1] = (byte) (((layerId & 0x1F) << 3) | 0x01);
    System.arraycopy(payload, 0, out, 2, payload.length);
    return out;
  }

  private static final class CapturingTrackOutput implements TrackOutput {
    final List<byte[]> chunks = new ArrayList<>();

    @Override
    public void format(androidx.media3.common.Format format) {}

    @Override
    public int sampleData(
        androidx.media3.common.DataReader input, int length, boolean allowEndOfInput, int sampleDataPart) {
      throw new UnsupportedOperationException("DataReader path is not used by this contract test");
    }

    @Override
    public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
      byte[] copy = new byte[length];
      data.readBytes(copy, 0, length);
      chunks.add(copy);
    }

    @Override
    public void sampleMetadata(
        long timeUs,
        @C.BufferFlags int flags,
        int size,
        int offset,
        CryptoData cryptoData) {}
  }
}
```

- [ ] **Step 2: Run the contract test and verify it fails**

Run:

```bash
./gradlew -q :lib-extractor:testDebugUnitTest --tests androidx.media3.extractor.mkv.MatroskaDolbyVisionStreamingRewriterTest
```

Expected: compilation fails because `shouldTransformHevcSampleNalByNal(...)` and `transformDolbyVisionRpuNal(...)` do not exist.

- [ ] **Step 3: Add default methods to `DolbyVisionSampleTransformer`**

In `media/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java`, add these methods inside `public interface DolbyVisionSampleTransformer`, after the existing `onHevcSample(...)` overloads and before full-sample `transformHevcSample(...)`:

```java
    /**
     * Returns whether this transformer wants the extractor to stream-rewrite this HEVC sample NAL
     * by NAL instead of materializing the full sample for {@link #transformHevcSample}.
     *
     * <p>When true, the extractor owns length-delimited parsing and Annex-B output. Base-layer NALs
     * are streamed directly, enhancement-layer non-RPU NALs are discarded, and RPU NALs are passed
     * to {@link #transformDolbyVisionRpuNal}.
     */
    default boolean shouldTransformHevcSampleNalByNal(
        long sampleTimeUs,
        int nalUnitLengthFieldLength,
        @Nullable byte[] blockAdditionalData,
        @Nullable byte[] dolbyVisionConfigBytes) {
      return false;
    }

    /**
     * Optionally rewrites one Dolby Vision RPU NAL payload while streaming a length-delimited HEVC
     * sample.
     *
     * @param rpuNalPayload RPU NAL payload bytes including the two-byte HEVC NAL header.
     * @param sampleTimeUs Sample presentation timestamp in microseconds.
     * @param blockAdditionalData BlockAdditional payload associated with this sample, if present.
     * @param dolbyVisionConfigBytes Track-level Dolby Vision config bytes, if available.
     * @return Replacement RPU NAL payload, or null to keep the original RPU.
     */
    @Nullable
    default byte[] transformDolbyVisionRpuNal(
        byte[] rpuNalPayload,
        long sampleTimeUs,
        @Nullable byte[] blockAdditionalData,
        @Nullable byte[] dolbyVisionConfigBytes) {
      return null;
    }
```

- [ ] **Step 4: Run the contract test and verify it passes**

Run:

```bash
./gradlew -q :lib-extractor:testDebugUnitTest --tests androidx.media3.extractor.mkv.MatroskaDolbyVisionStreamingRewriterTest
```

Expected: test passes.

- [ ] **Step 5: Commit the API contract**

```bash
git add media/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java media/libraries/extractor/src/test/java/androidx/media3/extractor/mkv/MatroskaDolbyVisionStreamingRewriterTest.java
git commit -m "feat(media3): add matroska dolby streaming hook contract"
```

---

### Task 2: Implement And Test Matroska Streaming Rewrite Helper

**Files:**
- Modify: `media/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java`
- Modify: `media/libraries/extractor/src/test/java/androidx/media3/extractor/mkv/MatroskaDolbyVisionStreamingRewriterTest.java`

- [ ] **Step 1: Extend the test file with streaming rewrite cases**

Append these tests and helpers to `MatroskaDolbyVisionStreamingRewriterTest.java`:

```java
  @Test
  public void writeHevcSampleNalByNal_dropsEnhancementLayerAndConvertsRpu() throws Exception {
    byte[] base = nal(/* type= */ 19, /* layerId= */ 0, new byte[] {0x01, 0x02});
    byte[] enhancement = nal(/* type= */ 1, /* layerId= */ 1, new byte[] {0x03, 0x04});
    byte[] rpu = nal(/* type= */ 62, /* layerId= */ 1, new byte[] {0x05});
    byte[] convertedRpu = nal(/* type= */ 62, /* layerId= */ 0, new byte[] {0x55});
    CapturingTrackOutput output = new CapturingTrackOutput();

    int bytesWritten =
        MatroskaExtractor.writeDolbyVisionHevcSampleNalByNalForTest(
            lengthDelimitedSample(4, base, enhancement, rpu),
            /* nalUnitLengthFieldLength= */ 4,
            output,
            new MatroskaExtractor.DolbyVisionSampleTransformer() {
              @Override
              public boolean shouldTransformHevcSampleNalByNal(
                  long sampleTimeUs,
                  int nalUnitLengthFieldLength,
                  byte[] blockAdditionalData,
                  byte[] dolbyVisionConfigBytes) {
                return true;
              }

              @Override
              public byte[] transformDolbyVisionRpuNal(
                  byte[] rpuNalPayload,
                  long sampleTimeUs,
                  byte[] blockAdditionalData,
                  byte[] dolbyVisionConfigBytes) {
                assertThat(rpuNalPayload).isEqualTo(rpu);
                return convertedRpu;
              }
            },
            /* sampleTimeUs= */ 123L,
            /* blockAdditionalData= */ null,
            /* dolbyVisionConfigBytes= */ null);

    assertThat(bytesWritten).isEqualTo(4 + base.length + 4 + convertedRpu.length);
    assertThat(flatten(output.chunks))
        .isEqualTo(annexBSample(base, convertedRpu));
  }

  @Test
  public void writeHevcSampleNalByNal_returnsMinusOneForMalformedNal() throws Exception {
    byte[] malformed = new byte[] {0, 0, 0, 4, 0x7C, 0x01};
    CapturingTrackOutput output = new CapturingTrackOutput();

    int bytesWritten =
        MatroskaExtractor.writeDolbyVisionHevcSampleNalByNalForTest(
            malformed,
            /* nalUnitLengthFieldLength= */ 4,
            output,
            new MatroskaExtractor.DolbyVisionSampleTransformer() {
              @Override
              public boolean shouldTransformHevcSampleNalByNal(
                  long sampleTimeUs,
                  int nalUnitLengthFieldLength,
                  byte[] blockAdditionalData,
                  byte[] dolbyVisionConfigBytes) {
                return true;
              }
            },
            /* sampleTimeUs= */ 123L,
            /* blockAdditionalData= */ null,
            /* dolbyVisionConfigBytes= */ null);

    assertThat(bytesWritten).isEqualTo(-1);
    assertThat(output.chunks).isEmpty();
  }

  private static byte[] lengthDelimitedSample(int lengthFieldBytes, byte[]... nals) {
    int total = 0;
    for (byte[] nal : nals) {
      total += lengthFieldBytes + nal.length;
    }
    byte[] out = new byte[total];
    int offset = 0;
    for (byte[] nal : nals) {
      writeLength(out, offset, lengthFieldBytes, nal.length);
      offset += lengthFieldBytes;
      System.arraycopy(nal, 0, out, offset, nal.length);
      offset += nal.length;
    }
    return out;
  }

  private static byte[] annexBSample(byte[]... nals) {
    int total = 0;
    for (byte[] nal : nals) {
      total += 4 + nal.length;
    }
    byte[] out = new byte[total];
    int offset = 0;
    for (byte[] nal : nals) {
      out[offset++] = 0;
      out[offset++] = 0;
      out[offset++] = 0;
      out[offset++] = 1;
      System.arraycopy(nal, 0, out, offset, nal.length);
      offset += nal.length;
    }
    return out;
  }

  private static byte[] flatten(List<byte[]> chunks) {
    int total = 0;
    for (byte[] chunk : chunks) {
      total += chunk.length;
    }
    byte[] out = new byte[total];
    int offset = 0;
    for (byte[] chunk : chunks) {
      System.arraycopy(chunk, 0, out, offset, chunk.length);
      offset += chunk.length;
    }
    return out;
  }

  private static void writeLength(byte[] out, int offset, int lengthFieldBytes, int value) {
    for (int i = 0; i < lengthFieldBytes; i++) {
      int shift = 8 * (lengthFieldBytes - 1 - i);
      out[offset + i] = (byte) ((value >>> shift) & 0xFF);
    }
  }
```

- [ ] **Step 2: Run the test and verify it fails because helper does not exist**

Run:

```bash
./gradlew -q :lib-extractor:testDebugUnitTest --tests androidx.media3.extractor.mkv.MatroskaDolbyVisionStreamingRewriterTest
```

Expected: compilation fails with unresolved `writeDolbyVisionHevcSampleNalByNalForTest`.

- [ ] **Step 3: Add the package-visible streaming helper to MatroskaExtractor**

In `MatroskaExtractor.java`, add constants near the other private static constants:

```java
  private static final int HEVC_NAL_TYPE_UNSPEC62 = 62;
  private static final byte[] NAL_START_CODE = new byte[] {0, 0, 0, 1};
```

Add this method near `writeLengthDelimitedSampleAsAnnexB(...)`:

```java
  static int writeDolbyVisionHevcSampleNalByNalForTest(
      byte[] sampleLengthDelimitedData,
      int nalUnitLengthFieldLength,
      TrackOutput output,
      DolbyVisionSampleTransformer transformer,
      long sampleTimeUs,
      @Nullable byte[] blockAdditionalData,
      @Nullable byte[] dolbyVisionConfigBytes)
      throws ParserException {
    return writeDolbyVisionHevcSampleNalByNalFromArray(
        sampleLengthDelimitedData,
        nalUnitLengthFieldLength,
        output,
        transformer,
        sampleTimeUs,
        blockAdditionalData,
        dolbyVisionConfigBytes);
  }

  private static int writeDolbyVisionHevcSampleNalByNalFromArray(
      byte[] sampleLengthDelimitedData,
      int nalUnitLengthFieldLength,
      TrackOutput output,
      DolbyVisionSampleTransformer transformer,
      long sampleTimeUs,
      @Nullable byte[] blockAdditionalData,
      @Nullable byte[] dolbyVisionConfigBytes)
      throws ParserException {
    if (nalUnitLengthFieldLength <= 0 || nalUnitLengthFieldLength > 4) {
      return -1;
    }
    int offset = 0;
    int bytesWritten = 0;
    ParsableByteArray scratch = new ParsableByteArray();
    while (offset < sampleLengthDelimitedData.length) {
      if (offset + nalUnitLengthFieldLength > sampleLengthDelimitedData.length) {
        return -1;
      }
      int nalLength = 0;
      for (int i = 0; i < nalUnitLengthFieldLength; i++) {
        nalLength = (nalLength << 8) | (sampleLengthDelimitedData[offset + i] & 0xFF);
      }
      offset += nalUnitLengthFieldLength;
      if (nalLength < 2 || offset + nalLength > sampleLengthDelimitedData.length) {
        return -1;
      }

      int nalType = getHevcNalUnitType(sampleLengthDelimitedData[offset]);
      int layerId = getHevcNuhLayerId(sampleLengthDelimitedData, offset, nalLength);
      if (layerId > 0 && nalType != HEVC_NAL_TYPE_UNSPEC62) {
        offset += nalLength;
        continue;
      }

      byte[] nalToWrite;
      if (nalType == HEVC_NAL_TYPE_UNSPEC62) {
        byte[] rpuPayload = new byte[nalLength];
        System.arraycopy(sampleLengthDelimitedData, offset, rpuPayload, 0, nalLength);
        byte[] transformed =
            transformer.transformDolbyVisionRpuNal(
                rpuPayload, sampleTimeUs, blockAdditionalData, dolbyVisionConfigBytes);
        nalToWrite = transformed != null && transformed.length > 0 ? transformed : rpuPayload;
        if (nalToWrite.length < 2) {
          return -1;
        }
      } else {
        nalToWrite = null;
      }

      scratch.reset(NAL_START_CODE);
      output.sampleData(scratch, NAL_START_CODE.length);
      bytesWritten += NAL_START_CODE.length;
      if (nalToWrite != null) {
        scratch.reset(nalToWrite);
        output.sampleData(scratch, nalToWrite.length);
        bytesWritten += nalToWrite.length;
      } else {
        scratch.reset(sampleLengthDelimitedData, offset + nalLength);
        scratch.setPosition(offset);
        output.sampleData(scratch, nalLength);
        bytesWritten += nalLength;
      }
      offset += nalLength;
    }
    return bytesWritten;
  }

  private static int getHevcNalUnitType(byte firstHeaderByte) {
    return (firstHeaderByte & 0x7E) >> 1;
  }

  private static int getHevcNuhLayerId(byte[] data, int offset, int nalLength) {
    if (nalLength < 2) {
      return 0;
    }
    int b0 = data[offset] & 0x01;
    int b1 = data[offset + 1] & 0xF8;
    return (b0 << 5) | (b1 >> 3);
  }
```

- [ ] **Step 4: Run the helper tests**

Run:

```bash
./gradlew -q :lib-extractor:testDebugUnitTest --tests androidx.media3.extractor.mkv.MatroskaDolbyVisionStreamingRewriterTest
```

Expected: all tests pass.

- [ ] **Step 5: Commit the helper**

```bash
git add media/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java media/libraries/extractor/src/test/java/androidx/media3/extractor/mkv/MatroskaDolbyVisionStreamingRewriterTest.java
git commit -m "feat(media3): stream matroska dolby sample rewrites"
```

---

### Task 3: Route Normal Matroska Playback Through Streaming Rewrite

**Files:**
- Modify: `media/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java`
- Modify: `media/libraries/extractor/src/test/java/androidx/media3/extractor/mkv/MatroskaDolbyVisionStreamingRewriterTest.java`

- [ ] **Step 1: Add a routing test for fallback behavior**

Append this test to `MatroskaDolbyVisionStreamingRewriterTest.java`:

```java
  @Test
  public void streamingDecisionFalse_keepsFullSampleFallbackAvailable() {
    MatroskaExtractor.DolbyVisionSampleTransformer transformer =
        new MatroskaExtractor.DolbyVisionSampleTransformer() {
          @Override
          public byte[] transformHevcSample(
              byte[] sampleLengthDelimitedData,
              int nalUnitLengthFieldLength,
              byte[] blockAdditionalData,
              byte[] dolbyVisionConfigBytes,
              long sampleTimeUs) {
            return sampleLengthDelimitedData;
          }
        };

    assertThat(
            transformer.shouldTransformHevcSampleNalByNal(
                /* sampleTimeUs= */ 1L,
                /* nalUnitLengthFieldLength= */ 4,
                /* blockAdditionalData= */ null,
                /* dolbyVisionConfigBytes= */ null))
        .isFalse();
  }
```

- [ ] **Step 2: Update the Matroska DV sample write branch**

In `MatroskaExtractor.writeSampleData(...)`, inside:

```java
    if (CODEC_ID_H265.equals(track.codecId) && dolbyVisionSampleTransformer != null) {
```

replace the beginning of the branch with this structure:

```java
      boolean useStreamingDolbyVisionRewrite =
          !deferSupplementalMainSampleSizePrefix
              && dolbyVisionSampleTransformer.shouldTransformHevcSampleNalByNal(
                  blockTimeUs,
                  track.nalUnitLengthFieldLength,
                  track.pendingDolbyVisionBlockAdditionalData,
                  track.dolbyVisionConfigBytes);
      if (useStreamingDolbyVisionRewrite) {
        int remainingSampleBytes = size - sampleBytesRead;
        byte[] sampleLengthDelimitedData = new byte[remainingSampleBytes];
        writeToTarget(input, sampleLengthDelimitedData, /* offset= */ 0, remainingSampleBytes);
        sampleBytesRead += remainingSampleBytes;
        int bytesWritten =
            writeDolbyVisionHevcSampleNalByNalFromArray(
                sampleLengthDelimitedData,
                track.nalUnitLengthFieldLength,
                output,
                dolbyVisionSampleTransformer,
                blockTimeUs,
                track.pendingDolbyVisionBlockAdditionalData,
                track.dolbyVisionConfigBytes);
        if (bytesWritten >= 0) {
          sampleBytesWritten += bytesWritten;
        } else {
          ParsableByteArray fallbackData = new ParsableByteArray(sampleLengthDelimitedData);
          int fallbackBytesWritten =
              writeLengthDelimitedSampleAsAnnexB(
                  output, sampleLengthDelimitedData, track.nalUnitLengthFieldLength, track.codecId);
          sampleBytesWritten += fallbackBytesWritten;
        }
      } else {
        // existing full-sample transform branch remains here unchanged
      }
```

Important: this still reads the whole sample into a `byte[]`. That is an intermediate checkpoint that proves routing and semantics before replacing the input with a true streaming reader in Task 4. The final optimization arrives in Task 4.

- [ ] **Step 3: Run extractor tests**

Run:

```bash
./gradlew -q :lib-extractor:testDebugUnitTest --tests androidx.media3.extractor.mkv.MatroskaDolbyVisionStreamingRewriterTest
```

Expected: pass.

- [ ] **Step 4: Run app-focused DV tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.core.player.DolbyVisionHevcSampleRewriterTest \
  --tests com.nexio.tv.core.player.DolbyVisionDiagnosticsTest
```

Expected: pass.

- [ ] **Step 5: Commit routing checkpoint**

```bash
git add media/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java media/libraries/extractor/src/test/java/androidx/media3/extractor/mkv/MatroskaDolbyVisionStreamingRewriterTest.java
git commit -m "feat(media3): route matroska dolby samples through streaming hook"
```

---

### Task 4: Replace Matroska Full-Sample Read With True Streaming

**Files:**
- Modify: `media/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java`
- Modify: `media/libraries/extractor/src/test/java/androidx/media3/extractor/mkv/MatroskaDolbyVisionStreamingRewriterTest.java`

- [ ] **Step 1: Add an extractor helper test proving only RPU bytes are materialized**

Add a counter to the test transformer in `writeHevcSampleNalByNal_dropsEnhancementLayerAndConvertsRpu`:

```java
    final int[] rpuBytesSeen = new int[1];
```

Inside `transformDolbyVisionRpuNal(...)`:

```java
                rpuBytesSeen[0] += rpuNalPayload.length;
```

After output assertion:

```java
    assertThat(rpuBytesSeen[0]).isEqualTo(rpu.length);
```

This pins the intended boundary: only RPU NAL payloads enter transformer-owned arrays.

- [ ] **Step 2: Add a true streaming helper**

In `MatroskaExtractor.java`, add a helper next to `writeDolbyVisionHevcSampleNalByNalFromArray(...)`:

```java
  private int writeDolbyVisionHevcSampleNalByNalFromInput(
      ExtractorInput input,
      TrackOutput output,
      int remainingSampleBytes,
      int nalUnitLengthFieldLength,
      DolbyVisionSampleTransformer transformer,
      long sampleTimeUs,
      @Nullable byte[] blockAdditionalData,
      @Nullable byte[] dolbyVisionConfigBytes)
      throws IOException, ParserException {
    if (nalUnitLengthFieldLength <= 0 || nalUnitLengthFieldLength > 4) {
      return -1;
    }
    int bytesRead = 0;
    int bytesWritten = 0;
    byte[] lengthField = nalLength.getData();
    lengthField[0] = 0;
    lengthField[1] = 0;
    lengthField[2] = 0;
    int lengthFieldOffset = 4 - nalUnitLengthFieldLength;
    ParsableByteArray scratch = new ParsableByteArray();
    while (bytesRead < remainingSampleBytes) {
      if (remainingSampleBytes - bytesRead < nalUnitLengthFieldLength) {
        return -1;
      }
      writeToTarget(input, lengthField, lengthFieldOffset, nalUnitLengthFieldLength);
      bytesRead += nalUnitLengthFieldLength;
      nalLength.setPosition(0);
      int nalLengthValue = nalLength.readUnsignedIntToInt();
      if (nalLengthValue < 2 || nalLengthValue > remainingSampleBytes - bytesRead) {
        return -1;
      }

      byte[] header = new byte[2];
      writeToTarget(input, header, /* offset= */ 0, /* length= */ 2);
      bytesRead += 2;
      int nalType = getHevcNalUnitType(header[0]);
      int layerId = getHevcNuhLayerId(header, 0, header.length);
      int payloadTailBytes = nalLengthValue - 2;

      if (layerId > 0 && nalType != HEVC_NAL_TYPE_UNSPEC62) {
        byte[] skipBuffer = new byte[Math.min(8192, payloadTailBytes)];
        int skipped = 0;
        while (skipped < payloadTailBytes) {
          int toRead = Math.min(skipBuffer.length, payloadTailBytes - skipped);
          writeToTarget(input, skipBuffer, /* offset= */ 0, toRead);
          skipped += toRead;
        }
        bytesRead += payloadTailBytes;
        continue;
      }

      scratch.reset(NAL_START_CODE);
      output.sampleData(scratch, NAL_START_CODE.length);
      bytesWritten += NAL_START_CODE.length;
      if (nalType == HEVC_NAL_TYPE_UNSPEC62) {
        byte[] rpuPayload = new byte[nalLengthValue];
        rpuPayload[0] = header[0];
        rpuPayload[1] = header[1];
        writeToTarget(input, rpuPayload, /* offset= */ 2, payloadTailBytes);
        bytesRead += payloadTailBytes;
        byte[] transformed =
            transformer.transformDolbyVisionRpuNal(
                rpuPayload, sampleTimeUs, blockAdditionalData, dolbyVisionConfigBytes);
        byte[] rpuToWrite = transformed != null && transformed.length > 0 ? transformed : rpuPayload;
        if (rpuToWrite.length < 2) {
          return -1;
        }
        scratch.reset(rpuToWrite);
        output.sampleData(scratch, rpuToWrite.length);
        bytesWritten += rpuToWrite.length;
      } else {
        scratch.reset(header);
        output.sampleData(scratch, header.length);
        bytesWritten += header.length;
        int copied = writeToOutput(input, output, payloadTailBytes);
        bytesRead += copied;
        bytesWritten += copied;
        if (copied != payloadTailBytes) {
          return -1;
        }
      }
    }
    return bytesRead == remainingSampleBytes ? bytesWritten : -1;
  }
```

Note: this helper allocates a small skip buffer per dropped enhancement NAL. If code quality review asks for reuse, move the skip buffer allocation outside the loop.

- [ ] **Step 3: Replace routing checkpoint with true streaming**

In the Task 3 streaming branch, replace:

```java
        byte[] sampleLengthDelimitedData = new byte[remainingSampleBytes];
        writeToTarget(input, sampleLengthDelimitedData, /* offset= */ 0, remainingSampleBytes);
        sampleBytesRead += remainingSampleBytes;
        int bytesWritten =
            writeDolbyVisionHevcSampleNalByNalFromArray(...)
```

with:

```java
        int bytesWritten =
            writeDolbyVisionHevcSampleNalByNalFromInput(
                input,
                output,
                remainingSampleBytes,
                track.nalUnitLengthFieldLength,
                dolbyVisionSampleTransformer,
                blockTimeUs,
                track.pendingDolbyVisionBlockAdditionalData,
                track.dolbyVisionConfigBytes);
        sampleBytesRead += remainingSampleBytes;
```

If `bytesWritten < 0`, throw a malformed-container parser exception instead of trying to fallback, because the input has already been consumed:

```java
        if (bytesWritten < 0) {
          throw ParserException.createForMalformedContainer(
              "Malformed HEVC sample during Dolby Vision streaming rewrite", /* cause= */ null);
        }
        sampleBytesWritten += bytesWritten;
```

- [ ] **Step 4: Run extractor tests**

Run:

```bash
./gradlew -q :lib-extractor:testDebugUnitTest --tests androidx.media3.extractor.mkv.MatroskaDolbyVisionStreamingRewriterTest
```

Expected: pass.

- [ ] **Step 5: Run app DV tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.core.player.DolbyVisionHevcSampleRewriterTest \
  --tests com.nexio.tv.core.player.DolbyVisionDiagnosticsTest
```

Expected: pass.

- [ ] **Step 6: Commit true streaming**

```bash
git add media/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java media/libraries/extractor/src/test/java/androidx/media3/extractor/mkv/MatroskaDolbyVisionStreamingRewriterTest.java
git commit -m "perf(media3): stream matroska dolby base-layer nals"
```

---

### Task 5: Wire Nexio App Hook To Streaming Matroska API

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionDiagnosticsTest.kt`

- [ ] **Step 1: Add failing app hook test for new streaming methods**

In `app/src/test/java/com/nexio/tv/core/player/DolbyVisionDiagnosticsTest.kt`, add:

```kotlin
    @Test
    fun `matroska streaming hook converts only rpu nal and publishes selected mode`() {
        MatroskaDolbyVisionHookInstaller.resetRuntimeCounters()
        val handler = createHookInvocationHandler(
            conversionEnabled = true,
            allowDv5Conversion = false,
            preserveMappingEnabled = true,
            enableRpuTap = false
        )
        val shouldStream = handler.invoke(
            proxy = Any(),
            method = FakeMatroskaStreamingMethods.shouldTransform,
            args = arrayOf(123L, 4, null, dvConfig(profile = 7))
        ) as Boolean

        val rpu = byteArrayOf(0x7D.toByte(), 0xF9.toByte(), 0x01)
        val converted = handler.invoke(
            proxy = Any(),
            method = FakeMatroskaStreamingMethods.transformRpu,
            args = arrayOf(rpu, 123L, null, dvConfig(profile = 7))
        ) as ByteArray?

        assertTrue(shouldStream)
        assertEquals(5, MatroskaDolbyVisionHookInstaller.getLastSelectedConversionMode())
        if (DoviBridge.isAvailable()) {
            assertTrue(converted == null || converted.isNotEmpty())
        }
    }
```

Add helper methods/classes near the existing reflection helpers in that test:

```kotlin
    private object FakeMatroskaStreamingMethods {
        val shouldTransform: java.lang.reflect.Method =
            FakeMatroskaStreamingApi::class.java.getMethod(
                "shouldTransformHevcSampleNalByNal",
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                ByteArray::class.java,
                ByteArray::class.java
            )
        val transformRpu: java.lang.reflect.Method =
            FakeMatroskaStreamingApi::class.java.getMethod(
                "transformDolbyVisionRpuNal",
                ByteArray::class.java,
                Long::class.javaPrimitiveType,
                ByteArray::class.java,
                ByteArray::class.java
            )
    }

    private interface FakeMatroskaStreamingApi {
        fun shouldTransformHevcSampleNalByNal(
            sampleTimeUs: Long,
            nalUnitLengthFieldLength: Int,
            blockAdditionalData: ByteArray?,
            dolbyVisionConfigBytes: ByteArray?
        ): Boolean

        fun transformDolbyVisionRpuNal(
            rpuNalPayload: ByteArray,
            sampleTimeUs: Long,
            blockAdditionalData: ByteArray?,
            dolbyVisionConfigBytes: ByteArray?
        ): ByteArray?
    }
```

- [ ] **Step 2: Run the app test and verify it fails**

Run:

```bash
./gradlew -q :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionDiagnosticsTest
```

Expected: new test fails because the invocation handler does not yet handle the new method names.

- [ ] **Step 3: Implement streaming methods in invocation handler**

In `MatroskaDolbyVisionHookInstaller.kt`, add cases in the `when (method.name)` block before `transformHevcSample`:

```kotlin
                "shouldTransformHevcSampleNalByNal" -> {
                    val nalUnitLengthFieldLength =
                        (invocationArgs.getOrNull(1) as? Number)?.toInt() ?: return@InvocationHandler false
                    val dolbyVisionConfigBytes = invocationArgs.getOrNull(3) as? ByteArray
                    val profile = resolveDolbyVisionProfile(configBytes = dolbyVisionConfigBytes)
                    nalUnitLengthFieldLength in 1..4 && shouldAllowConversion(profile)
                }
                "transformDolbyVisionRpuNal" -> {
                    val nalPayload = invocationArgs.getOrNull(0) as? ByteArray
                        ?: return@InvocationHandler null
                    val dolbyVisionConfigBytes = invocationArgs.getOrNull(3) as? ByteArray
                    val profile = resolveDolbyVisionProfile(configBytes = dolbyVisionConfigBytes)
                    if (!shouldAllowConversion(profile)) {
                        return@InvocationHandler null
                    }
                    maybeConvertDolbyVisionRpuNal(nalPayload, selectedConversionMode(profile))
                }
```

If this conflicts with the existing TS `"transformDolbyVisionRpuNal"` method signature, disambiguate by argument shape:

```kotlin
val secondArg = invocationArgs.getOrNull(1)
if (secondArg is Number) {
    // Matroska streaming RPU method
} else {
    // existing TS method using codecs string
}
```

Do not remove the existing full-sample `"transformHevcSample"` path; it remains MP4/FMP4 fallback and Matroska fallback when streaming is not selected.

- [ ] **Step 4: Run app DV tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.core.player.DolbyVisionDiagnosticsTest \
  --tests com.nexio.tv.core.player.DolbyVisionHevcSampleRewriterTest \
  --tests com.nexio.tv.core.player.DolbyVisionConversionModeSelectorTest
```

Expected: pass.

- [ ] **Step 5: Commit app hook wiring**

```bash
git add app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt app/src/test/java/com/nexio/tv/core/player/DolbyVisionDiagnosticsTest.kt
git commit -m "feat(player): use media3 matroska dolby streaming hook"
```

---

### Task 6: Add Runtime Diagnostics For Streaming Path

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionDiagnosticsTest.kt`

- [ ] **Step 1: Add streaming counters to snapshot**

In `MatroskaDolbyVisionHookInstaller.kt`, add counters:

```kotlin
    private val streamingSamples = AtomicLong(0L)
    private val streamingRpuBytes = AtomicLong(0L)
    private val streamingConvertedRpuBytes = AtomicLong(0L)
```

Add fields to `AllocationSnapshot`:

```kotlin
        val streamingSamples: Long,
        val streamingRpuBytes: Long,
        val streamingConvertedRpuBytes: Long,
```

Reset them in `resetRuntimeCounters()` and populate them in `runtimeAllocationSnapshot()`.

- [ ] **Step 2: Increment streaming counters**

In `"shouldTransformHevcSampleNalByNal"`, when returning true:

```kotlin
if (allowed && diagnosticsEnabled) {
    streamingSamples.incrementAndGet()
}
allowed
```

In Matroska streaming `"transformDolbyVisionRpuNal"` case:

```kotlin
if (diagnosticsEnabled) {
    streamingRpuBytes.addAndGet(nalPayload.size.toLong())
}
val converted = maybeConvertDolbyVisionRpuNal(nalPayload, selectedConversionMode(profile))
if (diagnosticsEnabled) {
    streamingConvertedRpuBytes.addAndGet(converted.size.toLong())
}
converted
```

- [ ] **Step 3: Update runtime buffer log**

In `PlayerRuntimeControllerPlaybackEvents.kt`, inside the `dvDiag=on` block, append:

```kotlin
                                    append(",streamSamples=")
                                    append(hookDiagnostics.streamingSamples)
                                    append(",streamRpuKb=")
                                    append(hookDiagnostics.streamingRpuBytes / 1024L)
                                    append(",streamRpuOutKb=")
                                    append(hookDiagnostics.streamingConvertedRpuBytes / 1024L)
```

- [ ] **Step 4: Update diagnostics test defaults**

In `DolbyVisionDiagnosticsTest.kt`, extend the zero/default snapshot test:

```kotlin
        assertTrue(hook.streamingSamples == 0L)
        assertTrue(hook.streamingRpuBytes == 0L)
        assertTrue(hook.streamingConvertedRpuBytes == 0L)
```

- [ ] **Step 5: Run tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.core.player.DolbyVisionDiagnosticsTest \
  --tests com.nexio.tv.core.player.DolbyVisionHevcSampleRewriterTest
```

Expected: pass.

- [ ] **Step 6: Commit diagnostics**

```bash
git add app/src/main/java/com/nexio/tv/core/player/MatroskaDolbyVisionHookInstaller.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt app/src/test/java/com/nexio/tv/core/player/DolbyVisionDiagnosticsTest.kt
git commit -m "feat(player): report matroska dolby streaming diagnostics"
```

---

### Task 7: Validation And Device Profiling

**Files:**
- No source edits expected.

- [ ] **Step 1: Run focused Media3 extractor tests**

Run:

```bash
./gradlew -q :lib-extractor:testDebugUnitTest --tests androidx.media3.extractor.mkv.MatroskaDolbyVisionStreamingRewriterTest
```

Expected: pass.

- [ ] **Step 2: Run app DV tests**

Run:

```bash
./gradlew -q :app:testDebugUnitTest \
  --tests com.nexio.tv.core.player.DolbyVisionHevcSampleRewriterTest \
  --tests com.nexio.tv.core.player.DolbyVisionConversionModeSelectorTest \
  --tests com.nexio.tv.core.player.DolbyVisionDiagnosticsTest \
  --tests com.nexio.tv.core.player.DolbyVisionAutoPlayGateTest \
  --tests com.nexio.tv.core.player.FfmpegDolbyVisionProfileProbeTest
```

Expected: pass.

- [ ] **Step 3: Build profileable package**

Run:

```bash
./gradlew -q :app:assembleUniversalReleaseProfileable
```

Expected: pass.

- [ ] **Step 4: Device profiling on 192.168.50.58**

Install and run the new profileable build using the project’s normal release install flow. Start the same 131GB / ~93Mbps DV7 MKV stream. Enable:

- Playback Settings -> Troubleshooting -> Enable Playback Buffer Diagnostics
- Playback Settings -> Troubleshooting -> Enable Dolby Vision Diagnostics

Collect:

```bash
PROFILEABLE_PID="$(adb -s 192.168.50.58:5555 shell pidof com.nexio.tv.profileable | tr -d '\r')"
test -n "$PROFILEABLE_PID"
adb -s 192.168.50.58:5555 shell dumpsys media_session
adb -s 192.168.50.58:5555 logcat -d -v threadtime --pid="$PROFILEABLE_PID" -t 5000 | rg "BUFFER:|Background concurrent mark compact GC|nativeConvertDv7RpuToDv81 converted"
adb -s 192.168.50.58:5555 shell top -H -p "$PROFILEABLE_PID" -b -n 1 -m 12
adb -s 192.168.50.58:5555 shell dumpsys meminfo com.nexio.tv.profileable
```

Expected:

- Media session stays `PLAYING`, `error=null`.
- `BUFFER:` logs show `dv7dovi=on`, `sourceProfile=7`, `mode=2` or `mode=5`.
- `streamSamples` increases for Matroska playback.
- `rewriteInMb`/`rewriteOutMb` should grow slowly or stay near zero for normal Matroska streaming path.
- `rewriteCopyMb` should grow slowly compared with previous full-sample rewrite.
- `streamRpuKb` and `streamRpuOutKb` should grow modestly.
- GC cadence should improve against prior baseline: previous app-side-only path still showed frequent `Background concurrent mark compact GC` and high `HeapTaskDaemon` CPU during DV conversion.

- [ ] **Step 5: Record validation numbers in the final response**

Do not create a new benchmark artifact as part of this task. Include the before/after log counts, GC cadence, `HeapTaskDaemon` CPU, `rewriteInMb`, `rewriteOutMb`, `rewriteCopyMb`, `streamSamples`, `streamRpuKb`, and `streamRpuOutKb` in the implementation final response.

---

## Risk Controls

- Existing full-sample `transformHevcSample(...)` remains available and unchanged for MP4/FMP4.
- Matroska `deferSupplementalMainSampleSizePrefix` stays on the full-buffer path because it needs sample size before writing.
- Streaming helper returns/throws malformed-container failure only after streaming path has opted in; non-DV or fallback path remains unchanged.
- App hook keeps mode selection in `DolbyVisionConversionModeSelector`, preserving mode `2` and mode `5`.
- TS method-name collision is handled by argument shape in the invocation handler.
- All new Media3 APIs are default methods, preserving binary/source compatibility for existing transformer implementations.

## Expected Improvement

This should remove the remaining full-sample input/output Java byte-array churn from normal Matroska DV streaming.

Before this plan, even after the app-side helper, Matroska still requires:

```text
full input sample byte[] + full rewritten output byte[]
```

For the measured stream this is hundreds of MB per minute of large-array churn. After the streaming path, ordinary base-layer NAL bytes are streamed from `ExtractorInput` to `TrackOutput`; only RPU NAL payloads require tiny byte arrays for libdovi conversion.

## Self-Review

Spec coverage:

- Fork-level Media3 change covered in Tasks 1-4.
- Nexio app integration covered in Task 5.
- Diagnostics covered in Task 6.
- Runtime validation covered in Task 7.
- DV7 mode 2 and preserve-mapping mode 5 are preserved through existing selector usage.

Placeholder scan:

- No `TBD`, `TODO`, “implement later”, or “similar to” placeholders remain.

Type consistency:

- Streaming method names are consistently `shouldTransformHevcSampleNalByNal` and `transformDolbyVisionRpuNal`.
- Media3 test helper is consistently `writeDolbyVisionHevcSampleNalByNalForTest`.
- Diagnostics fields are consistently `streamingSamples`, `streamingRpuBytes`, and `streamingConvertedRpuBytes`.
