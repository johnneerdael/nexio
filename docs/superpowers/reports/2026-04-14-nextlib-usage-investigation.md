# NextLib Usage Investigation

## Root Cause

NextLib `libmediainfo.so` requires FFmpeg symbol versions from `LIBAVCODEC_60`, while the final APK's merged `libavcodec.so` exports `LIBAVCODEC_62`. Android resolves shared libraries by name, so `libmediainfo.so` binds to an incompatible `libavcodec.so`.

## Current Usage

- `FrameRateUtils.detectFrameRateFromNextLib`: used only for AFR probing before this change.
- `app/build.gradle.kts`: declared `io.github.anilbeesetti:nextlib-mediainfo:1.9.1-0.11.0`.

## Decision

Remove NextLib from production. AFR now uses Nexio's forked FFmpeg stream metadata probe, which already shares the same FFmpeg native dependency set as playback support code and avoids the shared-library version conflict.

## Future Repair Options

If a future feature needs NextLib/MediaInfo again, do not re-add the dependency directly. Use one of these approaches:

1. Upgrade to a NextLib version whose FFmpeg symbol versions match the app's merged native FFmpeg libraries, then verify with `llvm-nm`.
2. Rebuild NextLib/MediaInfo with uniquely named FFmpeg shared libraries so it cannot bind to mpv or Media3 FFmpeg decoder libraries by accident.
3. Prefer extending the forked FFmpeg metadata probe when the needed data is available from FFmpeg.
