## 1. Implementation

- [x] 1.1 Add the `av1-playback` OpenSpec delta covering high-bit-depth dav1d output fallback.
- [x] 1.2 Add a focused native regression test for 10-bit and 12-bit downconversion to 8-bit.
- [x] 1.3 Implement shared JNI helpers for high-bit-depth plane downconversion.
- [x] 1.4 Use the helper in the AV1 YUV buffer-fill path.
- [x] 1.5 Use the helper in the AV1 surface render path.
- [x] 1.6 Tune default dav1d renderer thread-count and frame-delay for software AV1 playback.
- [ ] 1.7 Rebuild the AV1 decoder artifact, verify playback on Shield, and run `openspec validate --strict`.
