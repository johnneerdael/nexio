# Assrender-Inspired ASS/SSA Rendering Audit

## Goal

Replace the optional `io.github.peerless2012:ass-media` path with a local, always-on ASS/SSA rendering pipeline that preserves Nexio's custom `SubtitleOffsetRenderersFactory`, subtitle delay, and AI subtitle translation hooks.

## Sources Audited

Reference checkout: `~/Scripts/assrender`. Paths below are relative to that checkout.

- `assrender/src/main/kotlin/io/github/assrender/AssSubtitleParserFactory.kt`
- `assrender/src/main/kotlin/io/github/assrender/AssMatroskaExtractor.kt`
- `assrender/src/main/kotlin/io/github/assrender/AssExtractorOutput.kt`
- `assrender/src/main/kotlin/io/github/assrender/AssTrackOutput.kt`
- `assrender/src/main/kotlin/io/github/assrender/AssHandler.kt`
- `assrender/src/main/kotlin/io/github/assrender/AssTimeRenderer.kt`
- `assrender/src/main/kotlin/io/github/assrender/SubtitleOverlayView.kt`
- `assrender/src/main/cpp/ass_direct.c`
- `assrender/src/main/cpp/ass_direct_jni.c`
- `assrender/src/main/cpp/ass_direct.h`
- Aegisub ASS tag reference: https://aegisub.org/docs/latest/ass_tags/

## Port Decisions

| Source | Decision |
| --- | --- |
| Parser factory and no-op parser | Port. ASS/SSA must be consumed by libass, not Media3 cues. |
| Matroska extractor interception | Port and adapt. Keep the raw subtitle flag and font attachment capture. |
| TrackOutput capture | Port and test both sampleData overloads. |
| Handler/controller | Port and adapt. Feed libass chunks using Media3 sample metadata time and ASS sample duration. |
| Overlay view | Port and attach to `PlayerView.overlayFrameLayout`. |
| Time renderer | Port into Nexio's existing renderer factory. |
| Native direct libass bridge | Port. |
| FFmpeg stream-opening pipeline | Exclude. Media3 remains the extractor and data-source owner. |
| External `ass-media` path | Remove. |

## Aegisub Coverage Validation

Coverage is achieved when these tags and drawing commands are delivered byte-for-byte to libass in the event text: `\n`, `\N`, `\h`, `\b`, `\i`, `\u`, `\s`, `\bord`, `\xbord`, `\ybord`, `\shad`, `\xshad`, `\yshad`, `\be`, `\blur`, `\fn`, `\fs`, `\fscx`, `\fscy`, `\fsp`, `\frx`, `\fry`, `\frz`, `\fr`, `\fax`, `\fay`, `\fe`, `\c`, `\1c`, `\2c`, `\3c`, `\4c`, `\alpha`, `\1a`, `\2a`, `\3a`, `\4a`, `\an`, `\a`, `\q`, `\pos`, `\move`, `\org`, `\fad`, `\fade`, `\t`, `\clip`, `\iclip`, `\k`, `\K`, `\kf`, `\ko`, `\kt`, `\r`, `\p`, `\pbo`, and drawing commands `m`, `n`, `l`, `b`, `s`, `p`, `c`.
