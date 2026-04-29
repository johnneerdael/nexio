## 1. Foundation
- [ ] 1.1 OpenSpec scaffold

## 2. F-I-05 — wire trace interceptor into YouTube trailer clients
- [ ] 2.1 Inject and wire trace interceptor into provideYouTubeTrailerMainOkHttpClient + provideYouTubeTrailerProbeOkHttpClient
- [ ] 2.2 Ratchet DerivedOkHttpClientTraceWiringTest count to <= 2 + add positive YouTubeTrailerClientTraceInterceptorTest

## 3. F-G-01 path B — typed profile-scoped snapshot flow
- [ ] 3.1 Add observeProfileSnapshot(profileId) to ContinueWatchingSnapshotService + test
- [ ] 3.2 Migrate HomeViewModelContinueWatching to observeProfileSnapshot
- [ ] 3.3 Migrate AndroidTvFeedCatalogService (both sites) to observeProfileSnapshot

## 4. Sign-off
- [ ] 4.1 Re-run audits; update SIGN-OFF
