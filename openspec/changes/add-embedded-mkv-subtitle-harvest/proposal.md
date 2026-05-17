# Add Embedded MKV Subtitle Harvest

## Why

Auto-translate currently depends on renderer lookahead. On real playback this can leave only a handful of future cue groups available, causing regular untranslated subtitle flashes when translation happens near display time.

Live debugging on `192.168.50.98` confirmed the common failing path is embedded MKV text subtitles: the active stream was an MKV with embedded `subrip` tracks, while logcat showed repeated near-real-time translation batches instead of a strong ahead timeline.

## What Changes

- Add a background embedded Matroska text-track harvest path for selected internal subtitle tracks.
- Support MKV/Matroska `subrip` tracks first.
- Translate harvested cues into a session-scoped translated subtitle timeline.
- Let playback continue rendering original subtitles until translated replacements exist.
- Backfill renderer misses so future display/seeks can use translated cues.
- Add diagnostics for harvest progress, translation progress, renderer timeline hits/misses, and fallback reasons.

## Impact

- The common embedded MKV text subtitle path no longer depends on the renderer's limited future cue buffer for primary translation.
- Playback startup and subtitle continuity remain unchanged.
- Original-language subtitles remain visible when translation is late.
- Implementation touches app player coordination, Media3/fork extraction integration, subtitle translation batching, and renderer lookup behavior.
