## 1. Implementation

- [ ] 1.1 Add the `library-playback` OpenSpec delta covering Trakt library metadata hydration and
      direct-library playback recovery.
- [ ] 1.2 Add focused regression tests for Trakt library metadata hydration across watchlist and
      custom-list items.
- [ ] 1.3 Add focused regression tests for Real-Debrid direct-play URL resolution and
      direct-library player launch/back behavior.
- [ ] 1.4 Update Trakt library refresh logic to emit hydrated metadata for Library items while
      preserving the existing watchlist plus custom-list shape.
- [ ] 1.5 Update debrid library refresh logic so only player-ready direct-play URLs are exposed,
      including Real-Debrid download-link resolution.
- [ ] 1.6 Update player navigation args and host routing so direct Library playback returns to
      Library on both normal back and playback-error back.
- [ ] 1.7 Run focused verification for the new tests and `openspec validate fix-library-metadata-direct-playback --strict`.
