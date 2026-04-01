# Change: Add dav1d high-bit-depth output fallback

## Why

AV1 playback on Shield now reaches `Libdav1dVideoRenderer`, but 10-bit HDR streams still fail in
the JNI bridge with `dav1dGetFrame error: High bit depth (10 or 12 bits per pixel) output format
is not supported with YUV.` The current bridge only supports 8-bit output buffers and 8-bit YV12
surface copies even though dav1d can decode the stream successfully.

## What Changes

- Add high-bit-depth frame downconversion in the AV1 JNI bridge so dav1d 10/12-bit output can be
  presented through the existing 8-bit YUV and surface-YUV render paths.
- Keep the current 8-bit AV1 output behavior unchanged.
- Add a focused native regression test for the 16-bit to 8-bit downconversion helper.

## Impact

- Affected specs: `av1-playback`
- Affected code: `decoder_av1` JNI bridge, AV1 native build/test wiring, packaged AV1 decoder AAR
