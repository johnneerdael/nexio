# Trakt Scrobble Correctness — Plan B Findings

Date: 2026-05-04

Plan B (`docs/superpowers/plans/2026-05-04-trakt-scrobble-correctness.md`) was executed and completed contract-test coverage for the working invariants. Three tasks escalated rather than locking a contract — they uncovered real production bugs that warrant their own dedicated patches.

## Tasks 2-5: ✅ Locked

- **B.2** `TraktScrobbleMutationAdapter409Test` — locks HTTP 409→success on `/scrobble/start`, `/scrobble/stop`, `/checkin`.
- **B.3** `PlayerScrobbleCompletionClampTest` — locks the `< 80f` early-return guard in `emitCompletionScrobbleStop`.
- **B.4** `PlayerScrobbleThresholdSplitContractTest` — locks the pause-vs-completion split between `emitPauseScrobble` and `emitCompletionScrobbleStop`.
- **B.5** `PlayerScrobbleCompletionGuardTest` — locks the `hasSentCompletionScrobbleForCurrentItem` per-item dedup guard, the flag-set ordering before dispatch, and the reset in `refreshScrobbleItem`.

## Task B.6: ✅ Resolved by Plan D (Core-only port)

**Finding:** `warmTraktEpisodeMappingForCurrentPlayback()` and the surrounding episode-mapping subsystem (`preparePlaybackBeforeStart`, `absoluteToRelative`) are absent from this fork. NuvioTV (`PlayerRuntimeControllerScrobble.kt:21-71` upstream) runs the warmup before `refreshScrobbleItem` so absolute-numbered shows (anime in particular) get the right (season, episode) numbers in the scrobble item.

**Impact:** For anime / absolute-numbered shows, `/scrobble/start` and `/scrobble/stop` post the wrong episode number. Trakt records the wrong episode as watched.

**Where it's broken in our fork:**
- No `PlayerRuntimeControllerScrobble.kt` file exists in the player package.
- `refreshScrobbleItem` exists at `PlayerRuntimeControllerPlaybackEvents.kt:281` and is called from `PlayerRuntimeController.kt:392` and `PlayerRuntimeControllerStreams.kt:771`, but no warmup sits before it.

**Fix shape:** Port `warmTraktEpisodeMappingForCurrentPlayback` from NuvioTV, wire it into the playback-prepare lifecycle so it runs before any scrobble-item construction. Likely a 50–100 LOC patch including a small Trakt episode-summary cache.

**Resolution (Plan D, commits f7335455a..65f6455b3):** Core-only port shipped. New `TraktApi.getShowSeasons` endpoint + DTO, `TraktEpisodeMapping` model + `TraktEpisodeMappingService` (Trakt-only — no addon fallback), warmup wired into `PlayerRuntimeController.init` and `PlayerRuntimeControllerStreams.switchToEpisodeStream`, `buildScrobbleItem` consumes the warmed mapping to compute `effectiveSeason`/`effectiveEpisode`. `PlayerScrobbleEpisodeMappingOrderingTest` + `PlayerScrobbleEpisodeMappingConsumerTest` lock the wiring. Anime / absolute-numbered scrobbles now post the correct (season, episode). The intentionally-skipped addon fallback (NuvioTV's `fetchSeriesMeta` → `MetaRepository.getMetaFromAllAddons`) means shows with broken Trakt season data still misfire — acceptable trade-off for the Core-only scope.

## Task B.7: ✅ Resolved by Plan D

**Finding:** When two shows share an IMDB id (a known Trakt data-quality issue), `getWatchedShowsSnapshot` (`TraktProgressService.kt:1356-1361`) silently overwrites one with the other via `put(alias, entry)` — "last writer wins". One show's watched-episode set is silently discarded. NuvioTV's `__ambiguous__` sentinel pattern is absent in our fork.

**Impact:** Users with two shows sharing an external id (rare but real on Trakt) silently lose progress on whichever show was processed first.

**Why the spec'd test wouldn't catch it:** The plan's assertion `watchedByImdb.size <= 1` passes under the buggy code (last-writer-wins yields one episode), so it gives a false green. The real bug is "showA's progress dropped", not "merged set returned".

**Fix shape:** In `getWatchedShowsSnapshot`, detect alias-key collisions during the `buildMap` pass; mark colliding entries with an ambiguous sentinel and have `observeEpisodeProgress` either return empty for that lookup or use the canonical id as the tiebreaker. Probably 30–50 LOC plus a behavioural test that asserts both shows' episode sets are preserved (looked up by their unique non-IMDB ids).

**Resolution (Plan D, commit 88fdb2e26):** Two-pass `buildMap` lands canonical keys first (always unique per show), then aliases with collision detection — when two shows want the same alias, that alias is removed from the lookup map. Plus `observeEpisodeProgress`'s linear-scan fallback was changed from `firstOrNull` to `singleOrNull` so an ambiguous lookup returns empty rather than the first match. Two behavioural tests in `TraktProgressServiceShowSiblingsAmbiguityTest` assert both shows' progress is preserved under their unique TVDB ids and the shared IMDB lookup returns empty.

## Task B.8: ✅ Resolved by Plan D

**Finding:** The hidden-dropped filter EXISTS but a normalisation mismatch we introduced in Plan A bypasses it:
- `getHiddenProgressSnapshot` populates `droppedShowIds` via the legacy `normalizeContentId(ids)` overload → IMDB-first → stores `"tt9999998"`.
- `mapWatchedShowItem` (Task 11 of the original plan) uses the kind-aware `normalizeContentId(ids, kind = MediaKind.SHOW)` overload → TVDB-first → canonical is `"tvdb:999"`.
- `deriveNextUpFromWatchedShows` filter compares `"tvdb:999" in {"tt9999998"}` → false → dropped show is NOT filtered.

**Impact:** Shows the user dropped on Trakt continue appearing in Continue Watching after the original plan's id-layer change shipped. This is a regression of our own doing — the existing filter wasn't updated when we shifted shows to TVDB-first canonicalisation.

**Where it's broken:**
- `TraktProgressService.kt:getHiddenProgressSnapshot` — uses legacy `normalizeContentId` (IMDB-first).
- `TraktProgressService.kt:1589-1592` — filter checks `canonicalContentId` and `canonicalLookupKey(contentId)` only, not the alias set.

**Fix shape:** Either (a) update `getHiddenProgressSnapshot` to use `normalizeContentId(ids, kind = MediaKind.SHOW)` for the dropped/hidden show ids, OR (b) update the filter to also consult `entry.aliasContentIds`. Option (a) is the smaller change but only protects shows; option (b) is more general. ~10 LOC + a behavioural test that uses the same fixture as the failing test in B.8's exploration.

**Resolution (Plan D, commits 33a3edde0 + 3add82d30):** Both options applied. `getHiddenProgressSnapshot` now uses a new `showAliasKeys(ids)` helper that calls `normalizeContentId(ids, kind = MediaKind.SHOW)` AND collects every alias id form. `deriveNextUpFromWatchedShows` filter additionally checks each watched entry's `aliasContentIds` against the hidden/dropped sets. A latent inconsistency at `validateNextUpCandidate` was also fixed (legacy `canonicalLookupKey` → kind-aware overload). Behavioural test in `TraktProgressServiceHiddenDroppedFilterTest` uses positive + negative controls so a broken filter cannot pass.

## Recommendation

These three findings warrant a small dedicated plan — call it **Plan D — Trakt watched-state correctness fixes**. The three issues share a theme: silent data loss / silent filter bypass in the watched-shows projection. Estimated 1 day total to fix all three with proper TDD coverage.

Plan B's locked contracts (B.2-B.5) protect against accidental removal of working invariants. The escalated bugs (B.6-B.8) need fixing before those contracts can be expanded to cover the broader behaviours.

## Status update — 2026-05-04

Plan D shipped. All three escalations resolved (commits referenced in each section above). B.6 was scoped to "Core only" — the `TraktEpisodeMappingService` ports without the addon-fallback path (`fetchSeriesMeta`/`MetaRepository.getMetaFromAllAddons`). Anime/absolute-numbered scrobbles now post correct (season, episode) when Trakt's season tree is complete; shows where Trakt's data is missing/wrong continue to misfire (small minority — addressed if/when the addon-fallback port is later prioritised).
