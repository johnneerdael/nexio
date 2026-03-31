# Change: Adapt NuvioTV performance and bugfix batches

## Why

Nexio's fork point is missing a set of upstream performance fixes and directly applicable bugfixes
from NuvioTV. The biggest gaps are on the modern home hot path, D-pad responsiveness in stream
selection flows, playback/network resilience, and locale-sensitive metadata formatting.

## What Changes

- Move modern home row/presentation building out of `ModernHomeContent` and into a ViewModel-side
  prepared presentation pipeline with warm-start behavior and cached row lookups.
- Remove redundant render work on critical surfaces by dropping duplicate root backgrounds, removing
  the full-screen sidebar haze pass, nulling the window background, and making poster card
  containers transparent where images already paint the surface.
- Throttle repeated D-pad directional input on the stream selection screen and player source side
  panel.
- Improve playback resilience with a generic transient-error retry, audio-track switch recovery,
  and permissive TLS across app/network/playback clients.
- Normalize TMDB locale handling and make continue-watching/detail date formatting locale-aware.
- Keep home watched badges live for both movies and series instead of freezing stale per-item
  values.

## Impact

- Affected specs: `home-performance-and-playback-resilience`
- Affected code: home presentation pipeline/content, stream selection UI, player runtime/media
  source/network stack, TMDB metadata service, continue-watching/date formatting
