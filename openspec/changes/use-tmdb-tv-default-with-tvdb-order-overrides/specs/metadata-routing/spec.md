## ADDED Requirements

### Requirement: Standard TV metadata routes to TMDB by default

NEXIO SHALL route `ContentType.SERIES` and `ContentType.TV` to TMDB for metadata by default unless anime identity evidence routes the item to Kitsu.

#### Scenario: TMDB TV rail item hydrates as TMDB canonical

- **GIVEN** a standard TV item with `ContentType.SERIES`
- **WHEN** metadata routing runs
- **THEN** the route provider is TMDB
- **AND** `canonical.tmdbTvId` is populated with the TMDB TV ID
- **AND** `canonical.tmdbMovieId` is not populated
- **AND** any TVDB ID is retained only as a provider/crosswalk ID

#### Scenario: Anime identity still routes to Kitsu

- **GIVEN** a series item with Kitsu, MAL, AniList, AniDB, or anime identity evidence
- **WHEN** metadata routing runs
- **THEN** the route provider is Kitsu
