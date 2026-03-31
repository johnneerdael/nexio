## 1. Implementation

- [x] 1.1 Add the `home-performance-and-playback-resilience` OpenSpec delta covering prepared home
      presentation state, stream-selection D-pad throttling, playback/network resilience, and
      locale-aware metadata formatting.
- [x] 1.2 Move modern home presentation building into a ViewModel pipeline with warm-start output
      and cached row lookups consumed directly by `ModernHomeContent`.
- [x] 1.3 Remove stale watched badge memoization on home posters and extend the watched observer
      path to series posters via Trakt watched-show state.
- [x] 1.4 Throttle repeated directional D-pad input on the stream selection screen and player
      source side panel.
- [x] 1.5 Apply low-risk render/overdraw reductions on core home/sidebar/stream surfaces.
- [x] 1.6 Add transient playback retry, audio-track switch recovery, and permissive TLS wiring for
      app and playback clients.
- [x] 1.7 Normalize TMDB locale handling and switch continue-watching/detail air-date formatting to
      locale-aware patterns.
- [x] 1.8 Run verification for `:app:compileDebugKotlin` and record the existing unrelated build
      blockers that still fail the module compile.
