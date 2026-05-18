# Tasks

- [ ] Add temporary MP4 extractor-loop diagnostics for read count, input reopen count, seek count, cumulative open time, harvested count, and latest harvested cue time.
- [ ] Verify the current MP4 extractor-loop bottleneck on `192.168.50.98` with auto-translate enabled.
- [ ] Add Media3 fork tests for exposing selected MP4 text track sample table entries without exposing audio/video samples.
- [ ] Add Media3 fork API/path to return text track sample time, offset, size, flags, format, language, and selected text ordinal.
- [ ] Add app tests for coalescing MP4 subtitle sample byte ranges.
- [ ] Add app tests for decoding supported MP4 text samples from sample-table reads into timeline cue records.
- [ ] Implement MP4 sample-table harvester behind the existing embedded subtitle harvest coordinator.
- [ ] Keep extractor-loop MP4 harvesting as fallback when sample-table extraction is unavailable or unsupported.
- [ ] Add diagnostics for sample table count, sample read ranges, coalesced range count, harvested cue count, latest harvested cue time, and completion.
- [ ] Verify on `192.168.50.98` that MP4 harvest completes or builds a large/full ahead timeline quickly while playback continues rendering originals until translated replacements exist.
- [ ] Re-check playback choppiness separately with auto-translate on/off and heap evidence if GC churn remains severe.
