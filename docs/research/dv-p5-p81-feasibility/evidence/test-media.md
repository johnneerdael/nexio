# Test Media Manifest

## Required Assets

| ID | Required type | Exact source URI or file path | Container | Notes |
|----|---------------|-------------------------------|-----------|-------|
| p5-mkv | Dolby Vision Profile 5 MKV | not provided | MKV | Primary metadata-only baseline and SoC composer downconvert test |
| p5-mp4 | Dolby Vision Profile 5 MP4 | not provided | MP4 | MP4 extractor and dvcC/dvvC handoff test |
| p5-streaming | Dolby Vision Profile 5 DASH or HLS segment set | not provided | DASH/HLS | Fragmented MP4 or TS extractor coverage |
| p81-reference | Native Profile 8.1 reference from the same title or same-master comparison asset | not provided | MKV or MP4 | Visual and TV InfoFrame reference |
| hdr10-only-display-sample | HDR10-only display playback sample, can reuse p5-mkv or p5-mp4 | not provided | MKV or MP4 | Confirms fallback behavior on a non-DV sink |

## Displays

| ID | Type | Exact model | HDMI path | Dolby Vision advertised mode |
|----|------|-------------|-----------|------------------------------|
| dv-tv | Dolby Vision TV | not provided | not provided | not captured |
| hdr10-tv | HDR10-only TV | not provided | not provided | none |
