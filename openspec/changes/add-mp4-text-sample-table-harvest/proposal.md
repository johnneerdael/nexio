# Add MP4 Text Sample Table Harvest

## Why

The embedded subtitle harvest path no longer depends on renderer lookahead for MKV, and MP4 now seeks to the current playback region before harvesting. Live verification on `192.168.50.98` proved that MP4 initial seek works, but the current MP4 implementation still drives harvesting through `Mp4Extractor.read()`.

That extractor loop behaves like playback extraction: it seeks/reopens HTTP input around sparse sample offsets and gradually emits cue samples. It does not expose or consume the full text `TrackSampleTable` as a batch. As a result, MP4 translation can still behave like a moving ahead window instead of translating the whole remaining subtitle track.

## What Changes

- Add a Media3 fork API/path that exposes selected MP4 text track sample table entries needed for subtitle harvesting.
- Build an app-side MP4 text sample-table harvester that reads subtitle sample byte ranges directly, independent of playback and renderer lookahead.
- Coalesce nearby subtitle sample ranges when practical to avoid one HTTP request per cue.
- Decode supported MP4 text subtitle samples into the existing translated subtitle timeline.
- Preserve current playback behavior: original subtitles remain visible until translated replacements exist.
- Keep the existing extractor-loop MP4 path as a fallback until the sample-table path is proven on device.
- Add diagnostics proving sample table count, harvested count, read range count, byte range coalescing, translation progress, and completion.

## Impact

- MP4 embedded text subtitle translation can build a large/full ahead timeline rather than advancing only as extractor reads progress.
- Media3 fork gains a subtitle-harvest-oriented text sample table surface.
- The app gains a separate MP4 subtitle extraction path that is intentionally not a playback extractor.
- Verification requires rooted-device proof on `192.168.50.98` with the failing MP4 stream and auto-translate enabled.
