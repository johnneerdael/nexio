# TMDB TV Default With TVDB Order Overrides Design

Date: 2026-05-18

## Context

NEXIO recently moved standard TV metadata and Continue Watching projection toward TVDB. That helped shows whose scene/release numbering follows TVDB, such as Australian Survivor, but it hurts shows whose streams and downstream metadata follow TMDB, such as Berlin. The current source fuses three separate concepts:

- metadata provider route
- canonical/stable title identity
- episode season/number coordinate system

The new design separates those concepts. TMDB becomes the default metadata and stable identity provider for TV shows. TVDB remains available as a manual, per-show episode-order override for titles where scene numbering follows TVDB.

## Goals

- Use TMDB as the default metadata provider for movies and TV shows.
- Keep Kitsu as the anime-led metadata path.
- Keep TVDB as an optional TV episode-order provider.
- Add a global manual show override: "Use TheTVDB season numbering".
- Keep canonical TV identity TMDB-based even when TVDB numbering is enabled.
- Apply the selected episode order to UX episode labels, Continue Watching next-up projection, local resume coordinates, and stream-fetch coordinates.
- Keep scrobbling target-native and avoid treating TVDB numbering as a universal scrobble coordinate.
- Migrate existing TVDB-canonical Continue Watching/resume records back to TMDB identity by default, unless the show has the TVDB numbering override enabled.

## Non-Goals

- Do not remove TVDB.
- Do not make the TVDB override a metadata-provider toggle.
- Do not add automatic heuristics for known split-series cases in the first version.
- Do not create new isolated network lookup paths outside the existing metadata hydration/runtime architecture.
- Do not change anime routing away from Kitsu.
- Do not add a global settings toggle for TVDB metadata.

## Proposed Approach

Use a split-identity model:

1. Canonical title identity is decided by media kind.
2. Provider IDs are collected as crosswalk facts.
3. Episode order is selected separately by a small policy repository.

For v1, the order policy is manual-only:

```kotlin
enum class TvEpisodeOrderProvider {
    TMDB_DEFAULT,
    TVDB_DEFAULT
}
```

Future order profiles such as `TVDB_ABSOLUTE`, `TVDB_DVD`, `TVDB_REGIONAL`, or TMDB episode groups can be added later without changing the central boundary.

## Canonical Identity Model

Extend the canonical stable ID model so TMDB TV is represented directly:

```kotlin
data class CanonicalStableIds(
    val tmdbMovieId: String? = null,
    val tmdbTvId: String? = null,
    val tvdbSeriesId: String? = null,
    val kitsuAnimeId: String? = null
)
```

Semantics:

- Movies populate `tmdbMovieId`.
- Standard TV shows populate `tmdbTvId`.
- Anime populates `kitsuAnimeId`.
- TVDB IDs are retained as provider/crosswalk IDs and become the episode-order source only when the manual override is enabled.

For `ContentType.SERIES` and `ContentType.TV`, `ProviderIds.tmdb` means TMDB TV ID. For `ContentType.MOVIE`, it means TMDB movie ID. Code that needs canonical semantics should read the typed canonical slot instead of inferring from the generic provider ID alone.

## Routing

The metadata router should route by media kind:

- `ContentType.MOVIE` -> TMDB movie route.
- `ContentType.SERIES` / `ContentType.TV` -> TMDB TV route.
- anime identity evidence -> Kitsu route.

A `tmdb:<id>` ID for series/TV is provider-native TMDB TV, not a type conflict to TVDB.

TVDB can still appear in `targetIds` through existing crosswalk resolution. It should not become the canonical TV identity unless a future explicit migration requires that for a narrow compatibility path.

## Episode Order Override Repository

Add a global, non-profile-scoped repository:

```kotlin
interface TvEpisodeOrderOverrideRepository {
    suspend fun getOrder(tmdbTvId: String): TvEpisodeOrderProvider
    suspend fun setOrder(tmdbTvId: String, provider: TvEpisodeOrderProvider)
    suspend fun clearOrder(tmdbTvId: String)
}
```

The key is the canonical TMDB TV ID, stored as `tmdb:tv:<id>`.

The store should be compact and global. Prefer a small file-backed JSON store under app files so it can grow without violating the project rule against large SharedPreferences/DataStore values:

```json
{
  "version": 1,
  "overrides": {
    "tmdb:tv:12345": "TVDB_DEFAULT"
  }
}
```

Default behavior for missing records is `TMDB_DEFAULT`.

## Order Resolution

Add a small resolver that consumes canonical identity and provider IDs:

```kotlin
interface TvEpisodeOrderResolver {
    suspend fun resolve(
        tmdbTvId: String,
        providerIds: ProviderIds
    ): TvEpisodeOrderProvider
}
```

Rules:

- Missing override -> `TMDB_DEFAULT`.
- Override present -> selected provider.
- If `TVDB_DEFAULT` is selected but no TVDB ID is available, return a recoverable failure or fall back to TMDB for that request without changing the stored override.

The resolver should not perform independent provider lookups in v1. It should use provider IDs supplied by the existing hydrated identity path.

## Affected Consumers

### Detail Episode List and UX Labels

Episode lists should use the selected order. Default TV shows use TMDB season/episode coordinates. TVDB-overridden shows use TVDB default season numbering.

### Continue Watching

The current unconditional TVDB next-up projection should become conditional:

- `TMDB_DEFAULT`: keep TMDB canonical content ID and TMDB coordinates.
- `TVDB_DEFAULT`: project to TVDB coordinates for next-up/resume/stream-search use, while preserving TMDB as canonical title identity.

Existing TVDB-canonical Continue Watching and resume records should migrate back to TMDB identity when a TMDB crosswalk exists. If the show has a TVDB-order override, keep or project the episode coordinate in TVDB order. If no crosswalk exists, retain the old record rather than dropping it.

### Stream Fetch

Stream-fetch identity should use the selected episode order for S/E coordinates:

- Berlin default: TMDB identity + TMDB season/episode.
- Australian Survivor after override: TMDB identity + TVDB season/episode coordinates where the addon/request format supports that coordinate system.

The stream ID builder should remain capability-aware and avoid assuming every addon accepts the same provider ID prefix.

### Scrobble

Scrobble payloads remain target-native. The order override does not mean "scrobble TVDB". Trakt, Simkl, and MDBList resolution should prefer their native IDs/expected payload shapes, with TMDB/IMDb/TVDB used as crosswalk support.

## UI

Expose the override on the show action surface:

- Long press / context menu on a hydrated non-anime TV show.
- Show the action only when canonical `tmdbTvId` is available.
- First-paint-only items may hide or disable the action until hydration completes.

Copy:

- Default state: `Use TheTVDB season numbering`
- Enabled state: `Use TMDB season numbering`

Selecting the action toggles the global override for that canonical TMDB TV show and refreshes affected surfaces: detail episode list, Continue Watching, and any active stream-search identity inputs.

The UI must not describe this as a TVDB metadata-provider toggle. Metadata, artwork, trailers, recaps, overview, title, and canonical identity remain TMDB-primary.

## Migration

Migration should run through existing hydrated identity/crosswalk paths:

- Rebuild TVDB-canonical TV records toward TMDB identity when `tmdbTvId` can be resolved from existing IDs.
- Preserve episode coordinates under `TMDB_DEFAULT`.
- Preserve or project TVDB coordinates only when the show has `TVDB_DEFAULT`.
- Leave unresolved records unchanged.

The migration should be conservative and idempotent.

## Testing

Router tests:

- `ContentType.SERIES` and `ContentType.TV` route to TMDB by default.
- `tmdb:<id>` with TV content is native TMDB TV.
- Movies still route to TMDB movie.
- Anime still routes to Kitsu.

Stable ID tests:

- TV routes populate `canonical.tmdbTvId`.
- Movie routes populate `canonical.tmdbMovieId`.
- TVDB IDs remain available as provider/crosswalk facts.
- Ordinary TV canonical provider becomes TMDB.

Order policy tests:

- Unknown shows default to `TMDB_DEFAULT`.
- Manual override returns `TVDB_DEFAULT`.
- Clearing the override restores `TMDB_DEFAULT`.

Continue Watching tests:

- Berlin-style TMDB show remains TMDB-canonical and does not project to TVDB by default.
- Australian Survivor-style show projects to TVDB coordinates only after the override exists.
- Existing TVDB-canonical records migrate to TMDB identity when crosswalk exists, unless the override is enabled.

Scrobble tests:

- Hydrated scrobble payloads retain available TMDB, TVDB, IMDb, Trakt, and Simkl IDs.
- Episode-order override does not force TVDB-only scrobble payloads.

Audit/golden tests:

- Existing "TMDB TV rail hydrates to TVDB canonical" expectations should flip to TMDB canonical.
- Add a separate TVDB-order override scenario to prove TVDB projection remains intentionally supported.

## Rollout

Implementation should be staged in this order:

1. Add `tmdbTvId` to canonical stable IDs and update identity propagation.
2. Route standard TV metadata to TMDB by default.
3. Add the global order override repository and resolver.
4. Gate TVDB coordinate projection behind the resolver.
5. Add the show action toggle.
6. Add conservative migration for existing TVDB-canonical Continue Watching/resume records.
7. Update docs and settings copy that currently describes TVDB as the TV metadata authority.

No runtime feature flag is required unless implementation uncovers a larger blast radius than expected.

## Open Questions For Implementation Planning

- Whether the migration should run lazily during snapshot rebuild or as an explicit one-time pass.
- Whether the UI should show a disabled action during first paint or hide it until hydration completes.
- The exact stream-fetch representation for "TMDB identity with TVDB coordinate" should follow existing addon capability policies.
