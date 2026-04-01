## 1. Implementation
- [x] 1.1 Update `TrailerService.resolveYouTubeTrailer(...)` so the shared YouTube resolution order is cache, native extractor, authenticated helper, then backend bridge.
- [x] 1.2 Change the in-memory YouTube playback cache so native-resolved sources remain reusable during signed-in playback while keeping the existing TTL expiry behavior.
- [x] 1.3 Add focused debug logging for cache hits, native success or miss, helper success or miss, and backend success or miss.
- [x] 1.4 Add targeted unit coverage for signed-in native-first playback, signed-in native miss fallback to helper, signed-in native cache reuse, helper cache reuse, and cache expiry.
- [ ] 1.5 Validate the OpenSpec change with `openspec validate refactor-youtube-trailer-native-first --strict`.
