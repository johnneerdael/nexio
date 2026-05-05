# Nexio Anime Mapping Pack Design

## Context

Phases 1 and 2 of the anime season/episode projection layer (shipped in `feature/anime-season-projection-phase1` and `feature/anime-season-projection-phase2`) implemented a runtime projection resolver that derives Kitsu→TVDB/TMDB season and episode coordinates by calling Kitsu's episode-enrichment API for every member of a work group, then applying heuristics — including a flat-franchise fallback that refuses to scrobble shows like One Piece because Kitsu reports all episodes under season 1.

Investigation in `review-dossier/android-modern-home-catalog-rail-order-rca.md` confirmed that the curated season and per-episode mapping data we are approximating with runtime heuristics already exists upstream:

- **ScudLee** (`Anime-Lists/anime-lists`) carries `defaulttvdbseason` per AniDB resource and `<mapping-list>` rules with explicit per-episode and range-based projections to TVDB and TMDB seasons.
- **Fribb** (`Fribb/anime-lists`) is a derived ID bridge produced by reducing ScudLee + manami's anime-offline-database; it carries the cross-provider identity mapping but only a partial subset of the season information and none of the per-episode rules.
- **manami's anime-offline-database** has no TVDB/TMDB/IMDb references at all and is not useful for routing.

Today's pipeline ingests Fribb but throws away even the per-resource `season` field that Fribb already provides. The runtime then rebuilds season info from Kitsu, fails on flat franchises, and pays an N×M cost in network calls.

This design replaces runtime projection heuristics with build-time curated data combining Fribb (identity bridge) + ScudLee (season and episode rules) + a hand-curated Nexio overlay (corrections), generated in CI and bundled in the APK.

## Locked Decisions

| # | Decision | Choice | Rationale |
|---|---|---|---|
| 1 | Cutover scope | Single phase: per-resource season + per-episode rules together | Per-episode parsing is incremental cost over per-resource parsing; splitting just defers One Piece |
| 2 | Fallback when curated data missing | Strict — typed unresolved | Deterministic, never invent coordinates; matches user preference for explicit unresolved states |
| 3 | Asset format | Plain JSON | Parity with existing pipeline, no new deps; APK impact (~2MB) acceptable |
| 4 | Replace vs coexist with `anime-id-map.json` | Replace, rename to `nexio-anime-map-v1.json` | Single source of truth; no synchronization hazard |
| 5 | Parser location | New `:tools:anime-mapping-generator` module | XML rule parsing needs unit-testable surface, separate from Gradle wiring |
| 6 | Overlay | Single JSON file, four modes (`patch-identity`, `patch-mapping`, `replace`, `drop`) | Covers every override case; small enough not to need per-file split yet |
| — | Source freshness | Track `refs/heads/master`, no commit pinning, no count-drift assertion | User explicitly preferred frequent updates over reproducibility |
| — | Generation timing | CI build time, bundled in APK | Not OTA; release cadence + weekly cron drives freshness |

## Goals / Non-Goals

**Goals:**
- Replace `DefaultAnimeSeasonProjectionResolver`'s runtime Kitsu enrichment loop with curated build-time data.
- Produce correct TVDB/TMDB scrobble coordinates for both seasonal anime (MHA) and flat-franchise anime (One Piece, Naruto, Bleach).
- Eliminate `LOW_CONFIDENCE_FLAT_KITSU` and `NO_TVDB_MAPPING` heuristic-based unresolved cases.
- Surface telemetry for unresolved cases so we can size and patch real-world gaps.
- Provide a hand-curated overlay so engineering can fix upstream errors without waiting for upstream PRs.

**Non-Goals:**
- OTA / runtime fetch of the mapping pack. Bundled-in-APK only.
- Backwards compatibility with `anime-id-map.json` schema v1. Replaced wholesale in the same release.
- Replacing `KitsuMetadataService` for episode metadata (titles, thumbnails, runtime). Only the season-discovery use is removed.
- License review of upstream redistribution. Captured as a release-blocker checklist item, not a code task.
- Anime-offline-database integration. Dropped — no routing-relevant fields.

## Architecture

```
[CI cron / release build]
        │
        ▼
┌─────────────────────────────────────────────────────────────────────┐
│ :tools:anime-mapping-generator                                       │
│                                                                      │
│   fetch  ──►  Fribb anime-list-full.json    (refs/heads/master)      │
│   fetch  ──►  ScudLee anime-list-full.xml   (refs/heads/master)      │
│                                                                      │
│   parse  ──►  IdentityFragments (by AniDB)  ◄── Fribb               │
│   parse  ──►  MappingFragments  (by AniDB)  ◄── ScudLee             │
│                                                                      │
│   merge  ──►  IdentityRecord  (per Kitsu)                            │
│              EpisodeMappingRecord (per AniDB, only when ranges exist)│
│                                                                      │
│   apply  ──►  nexio-anime-overlay.json (4-mode patches)              │
│                                                                      │
│   emit   ──►  nexio-anime-map-v1.json                                │
│              nexio-anime-map-provenance.json                         │
└─────────────────────────────────────────────────────────────────────┘
        │
        ▼
[bundled in APK at app/src/main/assets/anime/]
        │
        ▼
[runtime: AnimeIdMappingService → DefaultAnimeSeasonProjectionResolver]
        │
        ▼
[strict resolution: explicit map → range rule → tvdbSeason+offset → typed unresolved]
        │
        ▼
[downstream consumers: Trakt scrobble, Top-Posters thumbnails, season tabs, rail dedup]
```

## Source Pipeline & Module Layout

### Module structure

```
tools/anime-mapping-generator/
  build.gradle.kts                             # Kotlin JVM module
  src/
    main/kotlin/com/nexio/animemap/
      Main.kt                                  # CLI entry for one-off regeneration
      fetch/UpstreamFetcher.kt                 # HTTP + commit-SHA capture
      parse/FribbJsonParser.kt
      parse/ScudleeXmlParser.kt
      parse/MappingListExpander.kt             # <mapping-list> rule expansion
      merge/IdentityMerger.kt                  # Fribb ⋈ ScudLee by AniDB
      merge/OverlayApplier.kt
      emit/AssetWriter.kt
    main/resources/
      nexio-anime-overlay.json                 # the curated overrides
    test/kotlin/com/nexio/animemap/
      ...                                      # see Test Strategy
    test/resources/fixtures/
      mha-eight-seasons.xml
      one-piece.xml
      chobits.xml
      overlay-examples.json
```

### Gradle tasks

| Task | Inputs | Outputs | Caching |
|---|---|---|---|
| `fetchAnimeMappingSources` | Source URLs (configurable) | `build/cache/fribb.json`, `build/cache/scudlee.xml`, `build/cache/source-shas.json` | UP-TO-DATE if cache exists; force via `--rerun` |
| `generateAnimeMappingAsset` | cache files + `nexio-anime-overlay.json` | `app/src/main/assets/anime/nexio-anime-map-v1.json`, `nexio-anime-map-provenance.json` | UP-TO-DATE on file hashes |
| `checkAnimeMappingAsset` | `app/src/main/assets/anime/nexio-anime-map-v1.json` | none (validation) | runs in PR builds; fails if asset is missing or `schemaVersion != 2` |

**The committed `nexio-anime-map-v1.json` is the source of truth for normal builds.** Neither `:app:preBuild` nor any normal app build task depends on `generateAnimeMappingAsset` — the generator only runs when explicitly invoked, either manually or by the regeneration CI job. PR builds use the asset committed on the branch and run `checkAnimeMappingAsset` to verify it parses. The existing inline Fribb generator code at `app/build.gradle.kts:158-296` is deleted in this PR.

### Local dev vs CI

- **Local dev:** the committed asset is used. `fetchAnimeMappingSources` and `generateAnimeMappingAsset` only run when explicitly invoked (`./gradlew :tools:anime-mapping-generator:generateAnimeMappingAsset --rerun`). No network during normal builds.
- **PR builds:** `:tools:anime-mapping-generator:test` and `:app:checkAnimeMappingAsset` run. No upstream fetch. The committed asset on the branch is used.
- **Regeneration CI job (separate workflow):** on-demand triggers run `fetchAnimeMappingSources --rerun` followed by `generateAnimeMappingAsset`, then opens a PR or directly commits the regenerated asset back to main. This is the *only* path that touches upstream. A weekly cron triggering this same flow is desired but is an **ops follow-up** outside this PR's code scope; until it lands, regeneration is manual.
- **Release builds:** use whatever asset is committed at the release tag — same as PR builds, just with release flags.
- **Provenance JSON** is committed alongside the asset and captures resolved commit SHAs at fetch time — forensic record of which upstream snapshot a given release shipped.

### Provenance file

`app/src/main/assets/anime/nexio-anime-map-provenance.json` (~500 bytes):

```json
{
  "generatedAt": "2026-05-06T08:30:00Z",
  "sources": {
    "fribb":   {"url": "...", "commit": "abc123...", "fetchedAt": "..."},
    "scudlee": {"url": "...", "commit": "def456...", "fetchedAt": "..."}
  },
  "overlay": {"version": 1, "entryCount": 7},
  "counts":  {"identityRecords": 10668, "episodeMappingRecords": 412, "skippedCount": 0}
}
```

## Asset Schema

### Top-level shape (`nexio-anime-map-v1.json`)

```json
{
  "$schema": "nexio-anime-map.schema.json",
  "schemaVersion": 2,
  "mappingPolicyVersion": 1,
  "generatedAt": "2026-05-06T08:30:00Z",
  "counts": { "identityRecords": 10668, "episodeMappingRecords": 412 },
  "identityRecordsByKitsu": { "13881": { ... }, ... },
  "episodeMappingsByAnidb":  { "69":    { ... }, ... },
  "indexes": {
    "byKitsu":     { ... },
    "byMal":       { ... },
    "byAnilist":   { ... },
    "byAnidb":     { ... },
    "byTvdb":      { ... },
    "byTmdbTv":    { ... },
    "byTmdbMovie": { ... },
    "byImdb":      { ... }
  }
}
```

`schemaVersion` bumps `1 → 2`. The current `AnimeIdMapAsset` reader is replaced wholesale in the same PR; no compat shim.

### IdentityRecord (per Kitsu ID)

```kotlin
@JsonClass(generateAdapter = true)
data class IdentityRecord(
    val kitsu: String,                       // required
    val mal: String? = null,
    val anilist: String? = null,
    val anidb: String? = null,
    val tmdb: String? = null,
    val tvdb: String? = null,
    val imdb: String? = null,
    val mediaType: String? = null,           // "series" | "movie"
    val sourceType: String? = null,          // "TV" | "OVA" | "MOVIE" | ...
    val tvdbSeason: String? = null,          // SeasonMarker wire form
    val tmdbSeason: String? = null,
    val tvdbEpisodeOffset: Int? = null,
    val tmdbEpisodeOffset: Int? = null,
    val hasMappingRules: Boolean = false,
    val evidence: List<String> = emptyList()
)
```

Fields ending in `Season` are encoded as strings to mirror ScudLee's wire format. The runtime parses them via `SeasonMarker.fromWire()`:

```kotlin
sealed interface SeasonMarker {
    data class Number(val season: Int) : SeasonMarker  // "1", "2", ...
    data object Absolute : SeasonMarker                // "a"
    data object Hentai : SeasonMarker                  // "hentai"
    data object Unknown : SeasonMarker                 // "unknown"

    companion object {
        fun fromWire(value: String?): SeasonMarker? = when (value) {
            null, "" -> null
            "a" -> Absolute
            "hentai" -> Hentai
            "unknown" -> Unknown
            else -> value.toIntOrNull()?.let(::Number)
        }
    }
}
```

### EpisodeMappingRecord (per AniDB ID)

Only present for AniDB IDs whose ScudLee entry has a `<mapping-list>` block (estimated ~400 entries):

```kotlin
@JsonClass(generateAdapter = true)
data class EpisodeMappingRecord(
    val anidb: String,
    val name: String? = null,
    val tvdbSeriesId: String? = null,
    val tmdbTvId: String? = null,
    val ranges: List<RangeRule> = emptyList(),
    val explicitMaps: List<ExplicitMap> = emptyList(),
    val evidence: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class RangeRule(
    val sourceSeason: Int,
    val startEpisode: Int,
    val endEpisode: Int?,                    // null = open-ended
    val targetProvider: String,              // "TVDB" | "TMDB"
    val targetSeason: Int,
    val offset: Int
)

@JsonClass(generateAdapter = true)
data class ExplicitMap(
    val sourceSeason: Int,
    val sourceEpisode: Int,
    val targetProvider: String,
    val targetSeason: Int,
    val targetEpisode: Int
)
```

### Indexes

Same shape as v1 with one breaking change:

- `byTvdb` becomes `Map<String, List<String>>` (TVDB series ID → list of member Kitsu IDs). Reflects the one-to-many reality (one TVDB series ↔ many Kitsu seasonal resources). Existing single-value callers must update.
- `byTmdbSeries` is renamed to `byTmdbTv` to match ScudLee terminology and becomes `Map<String, List<String>>` for the same one-to-many reason.
- **`byImdb` becomes `Map<String, List<String>>`** for the same one-to-many reason — empirically all eight MHA Kitsu resources share `imdb=tt5626028`. A single-value index would silently pick whichever record was inserted first (typically S1) and drop the other seven.
- `byTmdbMovie` stays `Map<String, String>` (movies are 1:1).
- All other indexes (`byKitsu`, `byMal`, `byAnilist`, `byAnidb`) remain `Map<String, String>` (anime-side IDs are unique-per-resource by construction).

Lookup callers should expose:
- `recordsForImdbId(imdbId): List<AnimeIdMapRecord>` — for "is this anime?" routing checks (any non-empty result means yes).
- For scrobble/detail-side resolution that needs to pick a *specific* season, the caller must pass additional context (e.g. season-number hint from the source) via the resolver — never just take the first list element.

### Resolution order

When projecting `(kitsu, sourceSeason, sourceEpisode) → (targetProvider, targetSeason, targetEpisode)`:

1. Lookup `identityRecord = identityRecordsByKitsu[kitsu]`. Missing → unresolved, `KITSU_NOT_IN_PACK`.
2. If `identityRecord.anidb` and `episodeMappingsByAnidb[anidb]` exist:
   - **a.** Match `explicitMaps[]` for `(sourceSeason, sourceEpisode, targetProvider)` → return.
   - **b.** Match `ranges[]` where `targetProvider` matches the requested target, `sourceSeason` matches, and `sourceEpisode ∈ [startEpisode, endEpisode ?: ∞]` → return `(targetProvider, targetSeason, sourceEpisode + offset)`.
3. Fall back to `identityRecord.{tvdbSeason | tmdbSeason}` for the requested provider:
   - `Number(N)` → return `(target, N, sourceEpisode + (offset ?: 0))`.
   - `Absolute` → unresolved, `EPISODE_OUT_OF_RANGE` (rules should have covered it; absence means stale data).
   - `Hentai` → unresolved, `SEASON_MARKER_HENTAI`.
   - `Unknown` → unresolved, `SEASON_MARKER_UNKNOWN`.
   - `null` → unresolved, `NO_CURATED_SEASON`.

## Overlay Format

File: `tools/anime-mapping-generator/src/main/resources/nexio-anime-overlay.json`. Edited via PR. Generator applies entries in file order, last in the merge pipeline.

```json
{
  "schemaVersion": 1,
  "entries": [ /* see modes below */ ]
}
```

### Mode 1: `patch-identity`

Field-level patch on an existing identity record (keyed by AniDB ID). Errors if record doesn't exist.

```json
{
  "anidb": "13881",
  "mode": "patch-identity",
  "reason": "ScudLee defaulttvdbseason=3 is correct (example only)",
  "patch": {
    "tvdbSeason": "3",
    "tmdbSeason": "3"
  }
}
```

### Mode 2: `patch-mapping`

Surgical change on an existing episode mapping record. Errors if record doesn't exist.

```json
{
  "anidb": "69",
  "mode": "patch-mapping",
  "reason": "Extend One Piece TVDB S22 end to 1155 ahead of upstream update",
  "patch": {
    "addRanges": [
      {"sourceSeason": 1, "startEpisode": 1086, "endEpisode": 1155,
       "targetProvider": "TVDB", "targetSeason": 22, "offset": -1085}
    ],
    "removeRanges": [
      {"sourceSeason": 1, "startEpisode": 1086, "targetProvider": "TVDB"}
    ],
    "addExplicitMaps": [],
    "removeExplicitMaps": []
  }
}
```

`removeRanges` matches by `(sourceSeason, startEpisode, targetProvider)`. `removeExplicitMaps` matches by `(sourceSeason, sourceEpisode, targetProvider)`. Removes apply before adds within a single patch.

### Mode 3: `replace`

Full record replacement (or creation). Required `target: "identity" | "mapping"` selects which map.

```json
{
  "anidb": "99999",
  "mode": "replace",
  "target": "mapping",
  "reason": "Upstream missing entirely — hand-curated based on TVDB pages",
  "record": { /* full EpisodeMappingRecord shape */ }
}
```

### Mode 4: `drop`

Removes both the identity record (any Kitsu ID linked to this AniDB) and any mapping record. Indexes are rebuilt without it.

```json
{
  "anidb": "10000",
  "mode": "drop",
  "reason": "Hentai marker, never want this in the bundled asset"
}
```

### Generator validation

Build fails on:
- Missing or non-numeric `anidb`.
- Invalid `mode` value.
- Missing or empty `reason`.
- `patch-*` against a missing target record.
- `replace.record` payload not matching the schema.
- Duplicate `(anidb, mode)` pairs.

## Runtime Resolver Changes

### `DefaultAnimeSeasonProjectionResolver`

**Constructor sheds two dependencies:**

```kotlin
class DefaultAnimeSeasonProjectionResolver @Inject constructor(
    private val mappingService: AnimeIdMappingService,        // reads new schema
    private val store: AnimeEpisodeCoordinateStore,           // unchanged
    private val traceEvents: AnimeProjectionTraceEvents,      // unchanged
    // REMOVED: kitsuMetadataService, presentationCache
) : AnimeSeasonProjectionResolver
```

**`resolveSeasonPresentation`** — no Kitsu fetch loop, reads curated data only:

```
1. lookup identityRecord by Kitsu ID; null → unresolved (KITSU_NOT_IN_PACK)
2. if identityRecord.anidb has an episodeMappingRecord with ranges:
     tabs   = ranges.distinctBy { it.targetSeason }.map { ... }
     source = CURATED_RANGE_RULES                            // One Piece path
3. else if any record sharing the same tvdb has tvdbSeason as Number:
     tabs   = byTvdb[identityRecord.tvdb]
                .map { kitsuId -> identityRecordsByKitsu[kitsuId] }
                .map { AnimeSeasonTab(seasonNumber = (it.tvdbSeason as Number).season, ...) }
                .distinctBy { it.seasonNumber }
     source = CURATED_PER_RESOURCE                           // MHA path
4. else: unresolved (NO_CURATED_SEASON | SEASON_MARKER_HENTAI | SEASON_MARKER_UNKNOWN)
```

**`resolveEpisodeProjection`** — implements the resolution order from the schema section.

### Type changes

`FallbackReason` enum gains:
- `KITSU_NOT_IN_PACK`
- `NO_CURATED_SEASON`
- `SEASON_MARKER_HENTAI`
- `SEASON_MARKER_UNKNOWN`
- `EPISODE_OUT_OF_RANGE`
- `OVERLAY_DROPPED`

And loses (replaced):
- `LOW_CONFIDENCE_FLAT_KITSU`
- `NO_TVDB_MAPPING`

`SeasonPresentationSource` gains `CURATED_PER_RESOURCE`, `CURATED_RANGE_RULES`. Loses `KITSU_FLAT_FALLBACK`.

### Code retired in this PR

| File / element | Reason |
|---|---|
| `AnimeSeasonPresentationCache.kt` + impl + test | Phase 2.1 — no longer needed without Kitsu fetch loop |
| `@Binds` for `AnimeSeasonPresentationCache` in `AnimeProjectionModule` | follows above |
| `presentationCache` constructor param everywhere | follows above |
| `FLAT_KITSU_MIN_EPISODES` constant + flat-franchise block | Strict mode replaces it |
| `app/src/main/assets/anime/anime-id-map.json` | Replaced by `nexio-anime-map-v1.json` |
| Inline Fribb generator at `app/build.gradle.kts:158-296` | Moved to `:tools:anime-mapping-generator` |
| Tests asserting flat-franchise rejection | Replaced with positive-projection tests |

### Code that stays

`KitsuRailFranchiseGrouper`, `AnimeWorkGroupKey` / `AnimeWorkIdentity` / `resolveWork`, `AnimeEpisodeCoordinateStore`, `KitsuMetadataService` itself, `AnimeIdMappingService` (renamed internals, same Hilt singleton, same call sites).

### Behavior changes visible to the rest of the app

| Scenario | Before | After |
|---|---|---|
| Trakt/Simkl scrobble for One Piece | Rejected, `LOW_CONFIDENCE_FLAT_KITSU` | Correct `(TVDB S22 EX)` from range rules |
| MHA detail page season discovery | 8 Kitsu network calls per work group to discover seasons | Zero season-discovery calls; per-resource `tvdbSeason` read from curated data. Episode metadata enrichment (titles, thumbnails, runtime) still runs as today. |
| One Piece detail page | 1 flat season tab | 23 TVDB-canonical season tabs |
| Kitsu-only anime (no Fribb mapping) | Heuristic-derived coordinates | Typed unresolved; display falls back to Kitsu-native, no scrobble |

The last row is the explicit regression risk accepted under decision #2 (strict mode).

## Test Strategy

### Generator tests (in `:tools:anime-mapping-generator`)

**`ScudleeXmlParserTest`:**
- `parses_default_tvdbseason_as_number`
- `parses_default_tvdbseason_a_as_absolute_marker`
- `parses_default_tvdbseason_hentai_marker`
- `parses_episodeoffset_negative_and_positive`
- `parses_tmdbtv_separately_from_tmdbid`
- `parses_inline_explicit_mapping_;1-27;2-3;`
- `parses_inline_mapping_with_zero_target_;1-0;`
- `parses_range_mapping_with_start_end_offset`
- `parses_open_ended_range_without_end_attribute`
- `tvdbid_hentai_does_not_create_tvdb_series_id`
- `empty_anime_entry_is_skipped`

**`FribbJsonParserTest`:**
- `extracts_all_id_fields`
- `season_field_present_for_seasonal_anime`
- `season_field_absent_for_flat_franchise`
- `respects_anidb_id_as_join_key`

**`MappingListExpanderTest`:**
- `range_rule_offset_application_one_piece_s21_ep892_to_tvdb_s21_ep1`
- `range_rule_open_ended_one_piece_s23_ep1156`
- `explicit_map_overrides_range_for_specials`
- `multiple_target_providers_emit_separately_tvdb_and_tmdb`

**`IdentityMergerTest`:**
- `fribb_kitsu_id_wins_when_scudlee_lacks_kitsu`
- `scudlee_default_tvdbseason_wins_when_fribb_lacks_season`
- `evidence_trail_carries_both_sources`
- `kitsu_only_record_no_scudlee_match_carries_no_season`

**`OverlayApplierTest`:**
- `patch_identity_modifies_named_field_only`
- `patch_identity_fails_when_record_missing`
- `patch_mapping_remove_then_add_replaces_rule`
- `replace_target_identity_creates_new_record`
- `replace_target_mapping_creates_new_record`
- `drop_removes_from_both_maps`
- `duplicate_anidb_mode_pair_fails_build`
- `empty_reason_fails_build`

**Fixture tests** — golden-snapshot tests over real-show slices in `test/resources/fixtures/`:
- `fixture_mha_eight_resources_have_tvdb_season_1_through_8`
- `fixture_one_piece_episode_892_projects_to_tvdb_s21_e1`
- `fixture_one_piece_episode_1156_projects_to_tvdb_s23_e1`
- `fixture_one_piece_episode_1_projects_to_tmdb_s1_e1`
- `fixture_chobits_inline_mapping_specials_parse_correctly`
- `fixture_movie_record_does_not_create_tvdb_series_projection`
- `fixture_hentai_marker_record_returns_no_projection`

Generator tests run against fixture snippets, not live upstream — hermetic.

### Runtime tests (in `:app`)

**Resolver tests** (replace existing flat-franchise tests):
- `resolveSeasonPresentation_mha_s3_uses_curated_per_resource_source`
- `resolveSeasonPresentation_one_piece_returns_23_seasons_from_range_rules`
- `resolveSeasonPresentation_kitsu_only_anime_returns_unresolved_with_KITSU_NOT_IN_PACK`
- `resolveEpisodeProjection_mha_s3e1_uses_tvdbSeason_3_offset_0`
- `resolveEpisodeProjection_one_piece_ep892_uses_range_rule_to_tvdb_s21e1`
- `resolveEpisodeProjection_one_piece_ep1156_open_ended_range_to_tvdb_s23e1`
- `resolveEpisodeProjection_explicit_map_wins_over_range`
- `resolveEpisodeProjection_hentai_marker_returns_unresolved_with_SEASON_MARKER_HENTAI`
- `resolveEpisodeProjection_episode_outside_all_ranges_returns_EPISODE_OUT_OF_RANGE`

**Consumer tests** verify the strict policy at each call site:
- `TrackingScrobbleService_one_piece_ep892_emits_tvdb_s21e1_to_trakt`
- `TrackingScrobbleService_unresolved_anime_does_not_emit_scrobble`
- `TopPostersMetadataProviderAdapter_one_piece_uses_projected_tvdb_coordinate`
- `KitsuRailFranchiseGrouper_still_groups_mha_seasons_under_one_card_after_schema_change`

### Test data placement

```
tools/anime-mapping-generator/src/test/resources/fixtures/
  mha-eight-seasons.xml
  one-piece.xml
  chobits.xml
  overlay-examples.json

app/src/test/resources/fixtures/
  nexio-anime-map-v1-test.json     # tiny pack with MHA + OP for resolver tests
```

### What is not tested

- Live upstream HTTP fetch (smoke-tested in CI, not in the unit suite).
- Asset compression (we picked plain JSON).
- Schema-version-1 backward compat (replaced wholesale, no migration code).

## Release, Rollout, and Risks

### Release-blocker checklist

Before tagging the release that ships `nexio-anime-map-v1.json` to users:

1. License review for ScudLee redistribution (likely fine — HAMA, Kometa, Jellyseerr already do this).
2. License review for Fribb redistribution.
3. Decide whether attribution is required in the app (Settings → About → Acknowledgements entry).

Engineering can prototype, run CI, validate the generator, and merge to a feature branch before these are settled. The release tag waits.

### Generator failure policy

| Failure type | Action |
|---|---|
| Overlay validation error | Fail build. Overlay is hand-curated; errors are bugs. |
| Upstream HTTP fetch error | Fail build. CI must not produce a build with stale/missing data unnoticed. |
| Single record fails to parse (malformed XML/JSON) | Log + skip + record in provenance `skippedCount`. Better to ship a 99.99%-complete pack than no pack. |
| Entry count drops dramatically | Surface in provenance, do not fail. No count-drift assertion per the locked freshness preference. |

### CI integration

- **PR builds:** `:tools:anime-mapping-generator:test` + `:app:checkAnimeMappingAsset`. No upstream fetch. The committed asset on the branch is the source of truth. App builds do not depend on the generator running.
- **Regeneration CI job (separate workflow, not part of normal app builds):** on-demand or scheduled trigger runs `fetchAnimeMappingSources --rerun` + `generateAnimeMappingAsset`, then opens a PR (or directly commits) the regenerated asset back to main. The PR includes the updated `nexio-anime-map-v1.json` and `nexio-anime-map-provenance.json`. This is the **only** path that touches upstream.
- **Release builds:** use whatever asset is committed at the release tag — same as PR builds.

A weekly cron triggering the regeneration job is desired but is an **ops follow-up** outside this PR's code scope; until it lands, regeneration is manual. The cron (once it lands) is the lever that delivers freshness without making any normal build network-dependent.

### Rollout

Single PR. No feature flag. No phased rollout.

- Strict-mode contract is the entire point of the change; gating it would hide the regressions we want to surface.
- Bundled asset, atomic with the release.
- Phase 1 + Phase 2 already shipped behind no flag with no issues.

### Telemetry

Three new `AnimeProjectionTraceEvents` events, sized for in-field measurement of strict-mode behavior:

- `anime.projection.curated_hit` — `source: per-resource | range | explicit-map`. Confirms the pack is doing real work.
- `anime.projection.unresolved` — `reason: KITSU_NOT_IN_PACK | NO_CURATED_SEASON | SEASON_MARKER_HENTAI | SEASON_MARKER_UNKNOWN | EPISODE_OUT_OF_RANGE`. Surfaces real-world gaps.
- `anime.projection.unresolved_top_kitsu_ids` — periodic aggregation of the most-frequently-unresolved Kitsu IDs. Drives overlay additions.

Same trace bus as Phase 1.

### Open risks

1. **License review may force changes.** If review concludes Fribb data needs explicit attribution, we add a Settings entry in a follow-up; doesn't block engineering.
2. **Strict-mode regressions for unmapped anime.** Anime currently scrobbling via Kitsu-enrichment guessing will stop scrobbling. The `unresolved` telemetry is how we discover them; the overlay is how we patch them.
3. **Upstream restructure** (ScudLee schema change). Generator parser breaks. Fix in `:tools:anime-mapping-generator`, regenerate. Caught by fixture tests on PRs that touch the parser; in the wild caught by the post-merge cron failing.
4. **Drift between weekly cron and release cadence.** If we release every 6 weeks but the cron commits every week, the committed asset on main is fresher than what users on older releases see. Acceptable — freshness goal is "next release ships fresh," not "users see fresh within a week of upstream."

## Glossary

- **Identity record** — per-Kitsu-ID row holding cross-provider identifiers and per-resource season hints.
- **Episode mapping record** — per-AniDB-ID row holding range rules and explicit per-episode maps; only present when curated.
- **SeasonMarker** — the typed parse of the wire-format `tvdbSeason` / `tmdbSeason` field. Number / Absolute / Hentai / Unknown.
- **Range rule** — a `(sourceSeason, [startEpisode, endEpisode], targetProvider, targetSeason, offset)` tuple producing a target episode by `sourceEpisode + offset`.
- **Explicit map** — a fully specified `(sourceSeason, sourceEpisode, targetProvider, targetSeason, targetEpisode)` tuple that wins over any range rule for the same source coordinate.
- **Strict mode** — the runtime policy that returns typed unresolved when curated data is missing, rather than inventing coordinates.
- **Overlay** — the hand-curated JSON file at `tools/anime-mapping-generator/src/main/resources/nexio-anime-overlay.json` whose entries patch, replace, or drop upstream data after merge.
