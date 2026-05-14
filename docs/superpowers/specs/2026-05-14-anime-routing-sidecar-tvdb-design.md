# Anime Routing With TVDB Sidecar Design

## Problem

Anime items can legitimately carry TVDB IDs as sidecar metadata for artwork, episode dates, and provider lookups. They must not promote those TVDB IDs into the canonical rail/catalog routing identity when a Kitsu identity is known.

The observed Death Note duplicate showed both `kitsu:1376` and `tvdb:79481` in Continue Watching. The current visible home catalog snapshot did not contain a `tvdb:79481` rail item, but the identity pipeline still has a hole: TMDB/TVDB series enrichment can add TVDB sidecar IDs for anime, and `RailItemPreview.bestSupportedRoutingId()` can choose TVDB for series unless the row source is Kitsu. That violates the intended anime identity rule.

## Invariant

TVDB may remain in `stableIds.tvdb` for anime, but Kitsu is the canonical routing/display identity whenever `stableIds.kitsu` is known.

Concretely:

- Keep `stableIds.tvdb` for metadata, RPDB/artwork, episode enrichment, and air-date lookups.
- Do not let TVDB become `MetaPreview.id` for a known anime item.
- Do not let TMDB/TVDB series enrichment produce a known-anime preview that has TVDB but lacks Kitsu.

## Producer Boundary

`CatalogItemCrossIdEnricher` should check the anime map while enriching TMDB/TVDB series IDs.

For TMDB series:

1. Parse the TMDB ID from the preview.
2. Ask `AnimeIdMappingService.resolveKitsuId(AnimeStremioId(TMDB, id), SERIES)`.
3. If a Kitsu ID is found, enrich `firstPaintStableIds.kitsu`.
4. Continue carrying TVDB/IMDb sidecars from existing mapping sources.

For TVDB series:

1. Parse the TVDB ID from the preview.
2. Ask `AnimeIdMappingService.resolveKitsuId(AnimeStremioId(TVDB, id), SERIES)`.
3. If a Kitsu ID is found, enrich `firstPaintStableIds.kitsu`.
4. Continue carrying TVDB as a sidecar because it is the original provider ID.

The enricher should not rewrite the preview `id` directly. It should only ensure `firstPaintStableIds` contains enough information for downstream routing to choose correctly.

## Consumer Boundary

`RailItemPreview.bestSupportedRoutingId()` should prefer Kitsu whenever `stableIds.kitsu` is present.

Current behavior only prefers Kitsu when the source provider or rail source is Kitsu. The new behavior should make Kitsu identity authoritative for known anime regardless of source rail:

1. If `stableIds.kitsu != null`, return `kitsu:<id>`.
2. Else preserve existing movie TMDB and series TVDB routing.
3. Else fall back to `sourceItemId`.

This keeps sidecars available while preventing anime rows sourced from TMDB, Trakt, or another provider from becoming TVDB-routed when the anime map already resolved Kitsu.

## Continue Watching Implication

Continue Watching may still receive historical or retained TVDB rows from older snapshots. The separate CW dedup/retention fix should collapse those aliases at render/snapshot boundaries, but new catalog-derived anime identities should no longer mint TVDB as the canonical routed item once Kitsu is known.

This design does not remove the need for CW alias dedup. It prevents one upstream source of future TVDB anime identities.

## Tests

Add focused regression coverage:

- `CatalogItemCrossIdEnricher` test: a TMDB series ID that maps to Death Note should enrich `stableIds.kitsu = "1376"` while retaining `stableIds.tvdb = "79481"` when available.
- `CatalogItemCrossIdEnricher` test: a TVDB series ID that maps to Death Note should enrich `stableIds.kitsu = "1376"` while retaining the TVDB sidecar.
- `RailItemPreview` test: a non-Kitsu source row with `ProviderIds(kitsu = "1376", tvdb = "79481")` should route to `kitsu:1376`.
- Existing non-anime series behavior should stay unchanged: a row with only `stableIds.tvdb` should route to `tvdb:<id>`.

## Non-Goals

- Do not remove TVDB IDs from anime stable IDs.
- Do not disable TVDB episode/date enrichment for anime.
- Do not change metadata routing for explicit `tvdb:*` detail screens in this design.
- Do not rewrite existing persisted snapshots as part of this change; snapshot cleanup belongs to the Continue Watching alias/retention path.

## Success Criteria

- Known anime from TMDB/TVDB enrichment carries Kitsu in `firstPaintStableIds`.
- Known anime rail previews route as `kitsu:*` even when TVDB is also present.
- Non-anime series continue routing as TVDB when TVDB is the best available series ID.
- Tests pin both the sidecar behavior and the routing-priority behavior.
