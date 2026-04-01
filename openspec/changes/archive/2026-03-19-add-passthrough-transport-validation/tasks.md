## 1. Implementation

- [x] 1.1 Add debug-only validation state/config storage and controller plumbing
- [x] 1.2 Add bundled validation manifest loader and sample packaging path
- [x] 1.2.1 Implement build-time copying of the selected repository-root golden assets into debug-capable Android asset sets
- [x] 1.3 Add debug UI controls for enablement, bundled sample selection, capture mode, binary dumps, and export
- [x] 1.4 Add ADB-triggerable command surface for enable/disable, sample selection, start/stop, export, and clearing prior sessions
- [x] 1.5 Add playback launcher for bundled validation samples
- [x] 1.6 Add sink/JNI capture hooks for pre-packer, packed burst, and pre-`AudioTrack.write` boundaries without mutating transport bytes
- [x] 1.7 Add burst-index-aligned comparator that compares reference burst N to live burst N with normalized Pa/Pb/Pc/Pd interpretation
- [x] 1.8 Add codec-specific validation rules and explicit failure codes for TrueHD, DTS-family, and Dolby-family bundled samples
- [x] 1.9 Add optional binary dump capture with stable file naming and diagnostics export bundles that always include route/config snapshots, manifest version, and asset checksum information
- [x] 1.10 Add per-codec documentation for ADB validation and collection flows under docs/
- [x] 1.11 Add targeted tests for manifest loading, burst alignment, codec-specific comparison, and failure-code mapping
