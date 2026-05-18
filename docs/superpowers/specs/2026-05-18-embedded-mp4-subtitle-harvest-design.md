# Embedded MP4 Subtitle Harvest Design

## Context

Auto-translate can still show untranslated subtitles during playback because the current renderer-driven translation path is bounded by playback extraction and small upcoming cue windows. The MKV harvester introduced an independent background path that reads embedded Matroska text tracks ahead of playback, stores source cues in `TranslatedSubtitleTimelineStore`, and lets the existing renderer swap translated cues when they are ready.

Live verification on `192.168.50.98` showed a failing playback session using `Mp4Extractor`, not `MatroskaExtractor`. The heap preview contained active MP4 atoms (`udta`, `meta`, `hdlr`), the route strings identified an `.mp4` stream, and logcat showed bounded `TextRenderer` prefetch windows such as `upcoming=1..12` with no embedded timeline session. The next step is to extend the independent embedded subtitle harvest architecture to MP4 text tracks.

## Goals

- Harvest embedded MP4 text subtitles independently of playback.
- Target all MP4 text subtitle formats Media3 can expose as text tracks, not one specific codec.
- Reuse the existing `TranslatedSubtitleTimelineStore` and translation loop.
- Keep playback behavior unchanged: original subtitles render until translated cues are available.
- Prove on device that MP4 harvest runs ahead of playback and is not limited to renderer prefetch windows.

## Non-Goals

- Do not hide or omit untranslated cues.
- Do not replace the renderer fallback path.
- Do not implement fragmented MP4/fMP4 in the first implementation unless it falls out naturally from shared code.
- Do not support bitmap/image subtitle tracks in this work.
- Do not patch `TextRenderer` to pretend the bounded renderer path is an ahead-of-playback solution.

## Architecture

Replace the MKV-specific harvester boundary with a container-neutral embedded subtitle harvesting layer.

`EmbeddedSubtitleHarvestCoordinator` remains the session owner. It decides when a session starts, changes, or cancels. It continues to own the active `TranslationTimelineSessionKey` and the translation loop.

`EmbeddedSubtitleHarvestEligibility` becomes container-aware. It classifies eligible streams as:

- `MATROSKA` for `.mkv`, `.mk3d`, and `.mka` evidence.
- `MP4` for `.mp4`, `.m4v`, and `.mov` evidence, plus container or track metadata where available.

A new `EmbeddedSubtitleTrackHarvester` interface represents the extraction backend. The first implementations are:

- `MatroskaEmbeddedSubtitleHarvester`, adapted from the current `MatroskaTextTrackHarvester`.
- `Mp4EmbeddedSubtitleHarvester`, using progressive `Mp4Extractor`.

Both harvesters publish normalized cues into `TranslatedSubtitleTimelineStore`. The translation loop and renderer lookup stay shared.

## MP4 Data Flow

1. Player state changes call `updateEmbeddedSubtitleHarvest()`.
2. Eligibility confirms auto-translate is enabled, an embedded subtitle track is selected, no addon subtitle is selected, translation settings are configured, and the container is supported.
3. The coordinator starts a session with `container=mp4`.
4. `Mp4EmbeddedSubtitleHarvester` opens the stream independently of playback on `Dispatchers.IO`.
5. The harvester runs `Mp4Extractor` with a custom `ExtractorOutput`.
6. The custom output discards audio/video tracks and attaches a publishing `TrackOutput` only to the selected text track ordinal.
7. Text samples are decoded or transcoded to `CueGroup`s and stored as source cues in `TranslatedSubtitleTimelineStore`.
8. The existing timeline translation loop translates pending cues.
9. The existing renderer lookup swaps translated cue groups when available; otherwise the renderer keeps showing original subtitles.

Track selection should no longer use SubRip-specific names. The shared state should carry `selectedSupportedTextOrdinal`, counting supported text tracks in playback track order. MP4 support includes text tracks Media3 exposes as `TRACK_TYPE_TEXT`, including `tx3g`/mov_text, `wvtt`, `stpp`/TTML, and SubRip if present. Bitmap/image tracks are not eligible.

## Normalization

The shared output normalizes container subtitle samples into the same timeline model:

- A text sample becomes one or more `CueGroup`s.
- Cue keys remain stable: presentation time plus normalized source text hash.
- Source language comes from selected track metadata when present.
- Empty cues, bitmap cues, and non-text cues are ignored.
- Multiple text cues at the same event time are stored in one `CueGroup`.
- Parser output with multiple event times stores each event time separately.

The MP4 harvester should prefer Media3's existing subtitle parser/transcoding path. MP4 text samples may be encoded as `tx3g`, `wvtt`, or `stpp`, so raw bytes should not be treated as SubRip text. If Media3 emits `application/x-media3-cues`, the harvester consumes that cue serialization and publishes decoded `CueGroup`s. If the extractor emits raw subtitle samples, the harvester decodes with the matching Media3 parser before storage.

## Instrumentation And Proof

Extend `EMBEDDED_SUB_TIMELINE` logs so device verification can prove the path is active and ahead of playback:

- `event=state`: `eligible`, `reason`, `container`, `filename`, `mime`, `codec`, `trackIndex`, `trackId`, `selectedTextOrdinal`.
- `event=session_started`: `container`, session hash, selected track fields.
- `event=cue_harvested`: `container`, cue time, cue hash.
- `event=progress`: `container`, `harvested`, `sourceStored`, `translated`, `pendingBackfill`, `lookupHit`, `lookupMiss`, `fallbackOriginal`.
- `event=harvest_completed`: `container`, `harvested`, `durationMs`.
- `event=harvest_failed`: `container`, sanitized reason.

Device proof criteria:

- For an MP4 with embedded text subtitles, logcat shows `EMBEDDED_SUB_TIMELINE event=session_started container=mp4`.
- `harvested` and `sourceStored` rise far beyond renderer `upcoming` windows.
- `translated` rises independently of playback position.
- `fallbackOriginal` stops increasing after translation catches up.
- No new sustained GC churn or monitor contention appears during harvest.

## Error Handling

Failure behavior must be conservative:

- If MP4 sniffing, range reads, or parsing fails, log `harvest_failed` and keep renderer fallback.
- If the selected track is not a supported text track, log `unsupported_track` and keep renderer fallback.
- If the stream does not support range seeks, retry only in bounded ways and fail back cleanly.
- If selected subtitle track changes, cancel the active harvest and start a new session.
- If playback switches stream, cancel the active harvest and clear the active session.
- If the translation provider fails or rate-limits, keep original subtitles rendering and retain pending timeline cues for later retry.

## Performance Constraints

- Run harvest work on `Dispatchers.IO`.
- Do not hold locks during network reads, extractor reads, parser work, translation requests, or disk writes.
- Do not retain raw MP4 subtitle sample bytes after normalizing cues.
- Do not retain full media bytes.
- Do not start parallel full-file harvests for old sessions.
- Use indexed loops in suspend code when iterating lists.
- Keep instrumentation rate-limited enough for live playback.

## Testing

Unit and integration coverage should include:

- Container classification for Matroska and MP4 filenames/URLs.
- MP4 text track eligibility across `tx3g`, `wvtt`, `stpp`/TTML, and SubRip-like formats.
- Unsupported bitmap/image subtitle rejection.
- Track ordinal selection using text-track ordinal, not SubRip-only ordinal.
- MP4 harvester publishes source cues into `TranslatedSubtitleTimelineStore`.
- Translation loop continues to translate MP4-harvested cues.
- Renderer fallback still returns original cues when no translated timeline cue exists.
- Session cancellation on stream switch and subtitle track switch.
- Diagnostics lines include `container=mp4` and progress counts.

## Implementation Sequence

1. Rename shared types from SubRip/MKV-specific names to embedded-text terminology where they cross coordinator boundaries.
2. Add container classification and text-track eligibility.
3. Introduce `EmbeddedSubtitleTrackHarvester`.
4. Adapt the existing Matroska harvester behind the interface without changing behavior.
5. Implement progressive MP4 harvester using `Mp4Extractor`.
6. Wire coordinator harvester selection by container.
7. Extend diagnostics and tests.
8. Install ReleaseEarlyAccess on `192.168.50.98` and verify with live MP4 playback.

