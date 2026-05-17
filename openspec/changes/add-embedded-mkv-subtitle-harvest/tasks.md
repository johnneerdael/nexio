# Tasks

- [ ] Add embedded subtitle harvest eligibility detection for selected internal MKV `subrip` tracks.
- [ ] Add session-scoped translated subtitle timeline model and lookup keying.
- [ ] Add Matroska selected-track subtitle harvester using the existing extractor interception precedent.
- [ ] Add ahead-of-playback translation pipeline for harvested cue records.
- [ ] Register renderer misses for translation backfill.
- [ ] Integrate renderer lookup so translated cues replace originals only on timeline hits.
- [ ] Preserve original subtitle rendering on every miss, failure, unsupported format, or late translation.
- [ ] Add diagnostics for selected track identity, harvest progress, translation progress, timeline hit/miss counts, and fallback reasons.
- [ ] Add unit tests for eligibility, keying, lookup, miss backfill, and session cancellation.
- [ ] Add Media3/fork tests for MKV `subrip` harvesting, selected-track filtering, timing accuracy, and cancellation.
- [ ] Verify on `192.168.50.98` with embedded MKV text subtitles.
