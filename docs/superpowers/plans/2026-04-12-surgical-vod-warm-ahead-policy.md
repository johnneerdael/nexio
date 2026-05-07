## Changes in v0.44
- Added a VOD Background Warm-Ahead setting for disabling background VOD prefetching when it competes with playback bandwidth
- Kept VOD playback write-through caching enabled while gating only the background warm-ahead job
- Added persistence for the VOD warm-ahead setting with the default enabled to preserve current playback behavior
- Improved VOD warm-ahead logging to show when prefetching starts and which upstream source is used
- Resolved a VOD cache-key mismatch by aligning warm-ahead cache keys with playback stream URLs
- Improved VOD cache behavior by increasing cache fragment size to reduce fragment churn
- Added English and Dutch settings copy for the new VOD warm-ahead toggle
- Added regression coverage for the warm-ahead policy, persisted settings, cache-key selection, and playback cache behavior
