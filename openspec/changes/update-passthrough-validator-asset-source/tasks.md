## 1. Remote Manifest and Asset Source
- [x] 1.1 Add a hosted validator asset source configuration and remote manifest loading path
- [x] 1.2 Replace APK-asset sample catalog loading with remote-manifest sample catalog loading

## 2. Local Cache and Playback
- [x] 2.1 Add validator file caching with checksum-based reuse for source, elementary, and reference files
- [x] 2.2 Update validation playback and reference parsing to use cached local files instead of packaged assets

## 3. Build and Diagnostics
- [x] 3.1 Remove large validator media/reference files from debug APK packaging
- [x] 3.2 Extend diagnostics/export metadata with remote source and cache state

## 4. Docs and Validation
- [x] 4.1 Update validator docs and debug controls to reflect remote downloads and cache behavior
- [x] 4.2 Add targeted tests for remote manifest parsing, cache reuse, and missing-download failure handling
- [x] 4.3 Validate the OpenSpec change with `openspec validate update-passthrough-validator-asset-source --strict`
