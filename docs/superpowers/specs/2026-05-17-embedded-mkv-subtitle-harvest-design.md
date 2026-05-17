# Embedded MKV Subtitle Harvest Design

## Context

Live debugging on `192.168.50.98` confirmed the failing playback path is an embedded MKV text subtitle path:

- The cached active stream was `Berlin.2023.S02E02.MULTi.1080p.WEB.x264-SUPPLY.mkv`.
- `ffprobe` showed embedded `subrip` subtitle streams, including French forced/full/SDH tracks.
- Logcat showed `TextRenderer` translating French cues in tiny near-real-time batches, often with only `upcoming=1..8` and `futureSubtitles=0`.
- The rooted app cache had no durable translated subtitle timeline for the current stream.

The current renderer-driven architecture cannot guarantee enough subtitle lookahead because it only sees cues made available by playback extraction and decoder buffering. Increasing renderer buffer constants can reduce symptoms but does not create a whole-track subtitle source.

## Decision

Build Phase 1 around an independent background Matroska text-track harvester for embedded MKV subtitles. Playback behavior stays exactly as it is today: original subtitles render until translated replacements are available for the same cue timing.

Phase 1 scope:

- MKV/Matroska only.
- Embedded internal text subtitle tracks only.
- `subrip` first.
- The selected internal subtitle track only.
- No playback blocking.
- No source subtitle suppression or blanking when translation is late.
- No MP4, HLS, image subtitles, external addon subtitles, or ASS/SSA whole-track rewrite in this pass.

## Architecture

Add an app-side `EmbeddedSubtitleHarvestCoordinator` that starts a session-scoped harvest job when playback has:

- a resolved stream URL,
- auto-translate enabled,
- a selected internal subtitle track,
- a Matroska container with supported embedded text subtitles,
- a target language and translation settings signature.

The coordinator runs a `MatroskaTextTrackHarvester` independently of playback. The harvester opens the same stream URL and extracts selected-track subtitle samples into normalized records:

- stream identity,
- track identity,
- start/end time,
- source text,
- stable cue key.

A `SubtitleTimelineTranslationPipeline` translates harvested records in large ahead batches, deduplicating safe repeated text while preserving each timing record. Translated records are published into a `TranslatedSubtitleTimelineStore`.

Renderer integration stays conservative. The renderer still receives and displays original Media3 cues. Before rendering a cue, it checks the translated timeline store. On a hit, it renders the translated cue. On a miss, it renders the original cue unchanged. The current near-real-time `CueGroupSubtitleTranslator` path becomes fallback behavior for cues not yet covered by the harvested timeline.

## Data Flow

1. Playback starts normally.
2. The selected internal subtitle track is detected from player track metadata.
3. The coordinator checks eligibility for Phase 1.
4. The harvester opens the same stream URL in the background.
5. Harvested source cues are appended to the session timeline.
6. The translation pipeline consumes harvested cues in ahead-of-playback batches.
7. Translated cues are stored by stream, track, language, settings signature, timing, and text hash.
8. Renderer lookup replaces original cues only when a matching translated cue exists.
9. Missed display-time cues are still registered/backfilled so seeks or repeated timings can use translated results later.

Seeking should reprioritize translation around the new playback position while retaining already translated ranges.

## Error Handling

Every unsupported or failed branch falls back to original subtitle rendering. The system must not blank subtitles because translation is late.

Explicit fallback reasons should be logged for:

- unsupported container,
- unsupported subtitle codec,
- no selected internal subtitle track,
- stream open failure,
- track identity mismatch,
- harvest timeout or cancellation,
- translation provider failure.

Resource bounds:

- one active harvester per playback session,
- cancellation on stop/release/session change,
- bounded in-memory session store,
- no large subtitle timelines in SharedPreferences or DataStore,
- disk persistence deferred until Phase 1 correctness is proven.

## Diagnostics

The implementation should expose logs that prove the active architecture:

- active subtitle source is embedded MKV text,
- selected track identity, codec, and language,
- harvest progress and latest harvested time range,
- translation progress, pending count, and failures,
- renderer timeline hit/miss counts,
- fallback reason whenever original subtitle rendering is used for a translatable cue.

The key regression signal is renderer-driven micro-batching. For Phase 1 content, logs should show translation driven by harvested timeline batches rather than repeated 1-cue or 3-cue display-time requests.

## Testing

Unit tests:

- MKV + internal `subrip` eligibility is accepted.
- Unsupported formats are rejected.
- Stable cue keys do not leak translations across streams, tracks, languages, or settings.
- Timeline lookup returns original cues on misses and translated cues on exact hits.
- Missed cues are registered for backfill.
- Session cancellation prevents stale translation reuse in a new stream.

Media3/fork tests:

- A local MKV fixture with multiple `subrip` tracks is harvested.
- Selected-track filtering ignores other subtitle tracks.
- Harvested start/end times match fixture expectations.
- Harvester cancellation stops cleanly.

Device verification:

- Install on `192.168.50.98`.
- Play the confirmed Berlin S02E02 MKV path or another MKV with embedded `subrip`.
- Verify original French subtitles continue appearing before translation is ready.
- Verify Dutch replacements appear as translated timeline hits.
- Verify logs show large ahead batches and harvested ranges.
- Seek forward and verify already translated ranges render without display-time micro-prefetch.

## Success Criteria

- No blank subtitles due to late translation.
- No source subtitle suppression as a workaround.
- Original subtitles render until translated replacements are available.
- Translation work moves from renderer micro-batches to ahead-of-playback harvested timeline batches.
- Missed cues are backfilled for later playback or seeks.
- Diagnostics make the embedded MKV harvest path observable on device.
