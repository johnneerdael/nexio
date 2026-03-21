## 1. Implementation
- [x] 1.1 Add source-selection logic so the idle screensaver uses Trakt trending snapshot data only when Trakt is authenticated and both home trending rails are enabled.
- [x] 1.2 Reuse the persisted Trakt discovery snapshot for screensaver items, taking up to 10 trending movies and 10 trending shows before falling back to Cinemeta when the Trakt source is ineligible.
- [x] 1.3 Ensure the Trakt-backed screensaver path relies on the existing startup refresh/snapshot flow rather than introducing a second duplicate Trakt fetch.
- [x] 1.4 Add or update targeted tests for source selection, item-count limits, and fallback behavior.
