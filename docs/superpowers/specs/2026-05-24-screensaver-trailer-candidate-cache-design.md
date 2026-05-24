# Screensaver Trailer Candidate Cache Design

## Context

The startup HAR captured after profile selection shows a large YouTube trailer network burst before the user starts the idle screensaver. Source tracing points to the home publish path:

`updateCatalogRowsPipeline` / `applyHomeResolvedRowsToUiPipeline` -> `publishTmdbTrendingScreensaverSurface` -> `refreshScreensaverTrailerCachePipeline` -> `warmScreensaverTrailerCache` -> `metadataRouterFacade.fetchTrailer`.

That path was intended to make screensaver trailer candidates available, but the current warmer uses the broad trailer metadata facade and then re-publishes the screensaver surface so `selectedPlaybackRef` can be attached. In practice it crosses the boundary from "cache trailer candidates" into "prepare trailer playback", which can trigger YouTube playback-source extraction and the observed `youtubei` / `googlevideo` startup storm.

The intended behavior is narrower:

- Startup may prepare the idle screensaver candidate list from TMDB trending movies and TV.
- Startup may cache YouTube trailer identities for those candidates only when the candidate cache is missing or older than 48 hours.
- The cached YouTube trailer identity is only the YouTube video ID and/or canonical watch URL.
- Startup must not resolve extracted playable stream URLs, warm `googlevideo` URLs, call `youtubei`, or invoke `InAppYouTubeExtractor`.
- When the screensaver starts, it starts on trailer 1 during screensaver playback. While trailer 1 plays, the app may pre-resolve/pre-warm trailer 2 only.

## Goals

1. Eliminate boot-time YouTube playback-source pre-warming for idle screensaver trailers.
2. Preserve a startup-safe 48 hour cache of TMDB trending screensaver trailer candidates.
3. Make the boundary explicit in code and tests: startup can cache YouTube IDs/URLs, runtime playback can resolve playable streams.
4. Keep the resolved display screensaver surface as the source consumed by `IdleScreensaverRepository`.

## Non-Goals

- Do not change trailer playback quality selection.
- Do not change image screensaver behavior.
- Do not introduce a general trailer resolver rewrite.
- Do not fetch non-TMDB providers for the startup screensaver candidate cache.
- Do not pre-resolve all screensaver trailers when the screensaver starts; only the active trailer and the immediate next trailer may be prepared.

## Design

### Candidate Metadata Boundary

Add or repurpose a focused screensaver trailer candidate cache whose contract is:

```kotlin
suspend fun ensureFreshTmdbTrendingTrailerCandidates(
    profileId: String,
    movieItems: List<MetaPreview>,
    tvItems: List<MetaPreview>,
    ttlMs: Long = 48.hours
): ScreensaverTrailerCandidateSnapshot
```

The implementation may call TMDB trending-derived item video endpoints, such as `/movie/{id}/videos` and `/tv/{id}/videos`, only when no valid cache exists for the active profile or the cache is older than 48 hours. It stores only candidate metadata:

- content identity: content id, TMDB id, type, title, release year where available
- selected YouTube video ID when TMDB returns a ranked trailer
- optional canonical YouTube watch URL derived from that ID
- cache timestamp and profile id

It must not store `TrailerPlaybackSource`, `TrailerPlaybackRef.InAppSource`, extracted video/audio stream URLs, user agents, or any `googlevideo` URL.

### Startup Publish Path

`publishTmdbTrendingScreensaverSurface` should continue to publish the screensaver resolved display surface from TMDB trending source rows and overlays. It should no longer call `refreshScreensaverTrailerCachePipeline` or any broad trailer facade warmer.

Instead, before or during screensaver surface publication, the home pipeline may call the metadata-only candidate cache. Cached YouTube IDs are attached to the published items as fallback trailer IDs or `TrailerPlaybackRef.YouTubeId` candidates. This keeps `ScreensaverCandidateRepository` able to produce playable candidate refs later without running playback extraction during boot.

The old warm/re-publish loop should be removed from the startup path:

- no `warmScreensaverTrailerCache` call from home publish
- no `republishScreensaverSurfaceAfterWarm` call caused by startup candidate caching
- no boot path that depends on `metadataRouterFacade.fetchTrailer` for screensaver warming

### Runtime Screensaver Playback

Runtime playback remains the only place that can resolve playable stream URLs:

1. When the idle trailer screensaver starts, choose trailer candidate 1 and resolve its `TrailerPlaybackRef` to a playback source.
2. After trailer 1 playback has started, prepare the next candidate only.
3. On advance, use the prepared next playback when available. Then prepare the next one after that.

This means `TrailerService.resolvePlaybackSource`, `resolveYouTubeTrailer`, and `InAppYouTubeExtractor.extractPlaybackSource` are allowed only from the active screensaver runtime path, not from boot/home publication.

### Cache TTL And Failure Behavior

The TTL is 48 hours per profile. If the cache is valid, boot must not issue TMDB trailer-video calls for screensaver candidates.

If a refresh is needed and TMDB fails:

- keep the previous snapshot if one exists, even if stale
- publish screensaver image candidates normally
- omit trailer fallback IDs for items that have no cached candidate
- never fall back to playback-source extraction during startup

### Observability

Add trace or log events that distinguish candidate metadata caching from runtime playback extraction:

- `screensaver.trailer_candidate_cache.hit`
- `screensaver.trailer_candidate_cache.refresh_start`
- `screensaver.trailer_candidate_cache.refresh_done`
- `screensaver.trailer_candidate_cache.refresh_failed`
- existing runtime playback-source traces remain tied to active screensaver playback

The important diagnostic invariant is that boot may show TMDB candidate refresh events, but must not show runtime playback-source events or YouTube extractor events before the screensaver overlay is active.

## Testing

Unit tests should prove the boundary:

1. `publishTmdbTrendingScreensaverSurface` does not call the old screensaver trailer warmer or broad `metadataRouterFacade.fetchTrailer`.
2. A valid 48 hour candidate cache prevents TMDB `/videos` refresh.
3. An expired cache refreshes TMDB movie/TV videos and stores only YouTube IDs/URLs.
4. Candidate cache refresh never stores `TrailerPlaybackRef.InAppSource` or `TrailerPlaybackSource`.
5. Screensaver session candidate selection can use cached YouTube IDs directly.
6. Runtime overlay pre-resolves only the next trailer while playback is active.

Device verification should use the rooted release target after profile selection:

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexio.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexio.tv 1
sleep 5
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 30
adb -s 192.168.50.98:5555 logcat -d -t 1200
```

Expected boot/profile-selection result:

- candidate cache hit or refresh events may appear
- TMDB `/videos` calls appear only if the 48 hour cache is absent or expired
- no `youtubei` requests
- no `googlevideo` requests
- no `InAppYouTubeExtractor` playback-source extraction before the screensaver overlay starts

Then start the idle trailer screensaver and verify:

- trailer 1 resolves when the screensaver starts
- trailer 2 resolves only after trailer 1 playback is active
- no batch resolution of all candidates occurs

## Open Decisions

None. The user clarified that "cached YouTube trailer candidates" means YouTube ID and/or watch URL only, not a warmed playable URL.
