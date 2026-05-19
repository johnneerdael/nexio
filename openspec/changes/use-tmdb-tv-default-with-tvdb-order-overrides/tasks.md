## 1. Canonical identity and routing

- [ ] Add `tmdbTvId` to canonical stable IDs.
- [ ] Populate `tmdbMovieId` only for movies and `tmdbTvId` for TV.
- [ ] Route standard TV to TMDB by default.
- [ ] Keep anime on Kitsu.

## 2. Episode order override

- [ ] Add global file-backed TV episode order override storage.
- [ ] Add resolver that defaults to TMDB and returns TVDB only for explicit overrides.
- [ ] Gate TVDB coordinate projection behind the resolver.

## 3. Consumers

- [ ] Update Continue Watching identity/projection and migration.
- [ ] Update stream-fetch coordinate selection.
- [ ] Update detail episode-list enrichment.
- [ ] Add show-level toggle action.

## 4. Verification

- [ ] Update router, stable ID, order policy, Continue Watching, stream-fetch, UI, and audit tests.
- [ ] Update user-facing docs/copy.
- [ ] Run focused unit tests and `openspec validate use-tmdb-tv-default-with-tvdb-order-overrides --strict`.
