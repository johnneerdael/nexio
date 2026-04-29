# addon-first-paint Spec Delta

## ADDED Requirements

### Requirement: Addon catalog item aliases feed shared first paint

Addon catalog item payloads SHALL preserve documented Stremio preview aliases when adapting `metas` entries into shared first-paint previews.

#### Scenario: TMDB addon search uses `genre` and `year`

- **GIVEN** an addon catalog response item has `genre: ["Science Fiction", "Adventure"]` and `year: "2026"`
- **WHEN** the item is adapted into `MetaPreview`
- **THEN** `MetaPreview.genres` is `["Science Fiction", "Adventure"]`
- **AND** `MetaPreview.releaseInfo` is `"2026"`
- **AND** no metadata router or provider detail call is required for those fields.

### Requirement: Addon catalog preview harvests stable IDs

Addon catalog item payloads SHALL preserve directly supplied stable IDs before visible hydration.

#### Scenario: TMDB addon catalog item supplies TMDB and IMDb IDs

- **GIVEN** an addon catalog item has `id: "tmdb:687163"`, `imdb_id: "tt12042730"`, and `behaviorHints.defaultVideoId: "tt12042730"`
- **WHEN** the item is adapted into `MetaPreview`
- **THEN** `MetaPreview.firstPaintStableIds.tmdb` is `"687163"`
- **AND** `MetaPreview.firstPaintStableIds.imdb` is `"tt12042730"`.

### Requirement: Addon route type does not override item type

Addon catalog route type and manifest catalog type SHALL NOT override the `type` field on individual `metas` items.

#### Scenario: Top Streaming series route returns mixed items

- **GIVEN** an addon route is requested as `catalog/series/...`
- **AND** the response contains one item with `type: "movie"` and another with `type: "series"`
- **WHEN** the catalog row is adapted
- **THEN** the movie item has `apiType == "movie"`
- **AND** the series item has `apiType == "series"`.
