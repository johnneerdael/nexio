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

#### Scenario: Canonical addon fields win over aliases

- **GIVEN** an addon catalog response item has `genres: ["Drama"]`, `genre: ["Science Fiction"]`, `releaseInfo: "2026-03-15"`, and `year: "2026"`
- **WHEN** the item is adapted into `MetaPreview`
- **THEN** `MetaPreview.genres` is `["Drama"]`
- **AND** `MetaPreview.releaseInfo` is `"2026-03-15"`.

### Requirement: Addon catalog preview harvests stable IDs

Addon catalog item payloads SHALL preserve directly supplied stable IDs before visible hydration.

#### Scenario: TMDB addon catalog item supplies TMDB and IMDb IDs

- **GIVEN** an addon catalog item has `id: "tmdb:687163"`, `imdb_id: "tt12042730"`, and `behaviorHints.defaultVideoId: "tt12042730"`
- **WHEN** the item is adapted into `MetaPreview`
- **THEN** `MetaPreview.firstPaintStableIds.tmdb` is `"687163"`
- **AND** `MetaPreview.firstPaintStableIds.imdb` is `"tt12042730"`.

#### Scenario: Addon behavior hints provide IMDb ID when imdb_id is absent

- **GIVEN** an addon catalog item has `id: "tmdb:687163"`, no `imdb_id`, and `behaviorHints.defaultVideoId: "tt12042730"`
- **WHEN** the item is adapted into `MetaPreview`
- **THEN** `MetaPreview.firstPaintStableIds.tmdb` is `"687163"`
- **AND** `MetaPreview.firstPaintStableIds.imdb` is `"tt12042730"`.

### Requirement: Addon preview stable IDs feed visible hydration targets

Addon preview stable IDs SHALL be used by the existing metadata routing pipeline when a first-paint item becomes visible or focused.

#### Scenario: Raw IMDb movie add-on item resolves to TMDB target before provider execution

- **GIVEN** an add-on catalog item has `id: "tt12042730"`, `type: "movie"`, and no direct `tmdb:` content ID
- **AND** visible hydration receives an adapted preview where `MetaPreview.firstPaintStableIds.imdb` is `"tt12042730"`
- **AND** the existing TMDB external-ID lookup maps IMDb ID `tt12042730` to TMDB ID `687163`
- **WHEN** the item is hydrated through `MetadataRouterFacade.resolveRequest(...)`
- **THEN** the route provider is `TMDB`
- **AND** `route.targetIds[TMDB]` is `"tmdb:687163"`
- **AND** provider execution never receives `"tt12042730"` as a TMDB target ID.

#### Scenario: Raw IMDb series add-on item resolves through TMDB then TVDB before provider execution

- **GIVEN** an add-on catalog item has `id: "tt0903747"`, `type: "series"`, and no direct `tvdb:` content ID
- **AND** visible hydration receives an adapted preview where `MetaPreview.firstPaintStableIds.imdb` is `"tt0903747"`
- **AND** the existing TMDB external-ID lookup maps IMDb ID `tt0903747` to TMDB TV ID `1396`
- **AND** the existing identity resolver maps TMDB TV ID `1396` to TVDB ID `81189`
- **WHEN** the item is hydrated through `MetadataRouterFacade.resolveRequest(...)`
- **THEN** the route provider is `TVDB`
- **AND** `route.targetIds[TVDB]` is `"tvdb:81189"`
- **AND** provider execution never receives `"tt0903747"` as a TVDB target ID.

#### Scenario: Preview stable provider IDs win over raw IMDb content ID

- **GIVEN** an add-on catalog item has `id: "tt12042730"` and `type: "movie"`
- **AND** visible hydration receives an adapted preview where `MetaPreview.firstPaintStableIds.tmdb` is `"687163"`
- **WHEN** the item is hydrated through `MetadataRouterFacade.resolveRequest(...)`
- **THEN** `route.targetIds[TMDB]` is `"tmdb:687163"`
- **AND** no add-on-specific renderer, scheduler, or metadata bypass is used.

### Requirement: Addon route type does not override item type

Addon catalog route type and manifest catalog type SHALL NOT override the `type` field on individual `metas` items.

#### Scenario: Top Streaming series route returns mixed items

- **GIVEN** an addon route is requested as `catalog/series/...`
- **AND** the response contains one item with `type: "movie"` and another with `type: "series"`
- **WHEN** the catalog row is adapted
- **THEN** the movie item has `apiType == "movie"`
- **AND** the series item has `apiType == "series"`.
