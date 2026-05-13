## ADDED Requirements

### Requirement: Typed cache stores avoid generic JSON tree retention

Hydrated home overlay and media clip cache stores SHALL keep resident cache state in typed Kotlin records rather than generic `JsonObject` trees after migration to their v2 file formats.

#### Scenario: Hydrated overlay aliases are loaded

- **GIVEN** hydrated overlay aliases are persisted in the v2 cache file
- **WHEN** `HydratedHomeOverlayStore` loads the cache
- **THEN** aliases are retained as string-to-string mappings
- **AND** overlay records are retained as `HydratedHomeOverlay` values
- **AND** the store does not retain alias entries as `JsonObject` wrappers

#### Scenario: Media clip records are queried

- **GIVEN** media clip records are persisted in the v2 cache file
- **WHEN** `MediaClipStore` queries candidates
- **THEN** matching, TTL, stale-hit, language, and sorting behavior matches the v1 store
- **AND** records are scanned as typed `StoredMediaClipRecord` values
- **AND** the query does not parse each record from a resident `JsonObject`

### Requirement: Typed cache stores preserve old data through migration

Typed v2 cache stores SHALL migrate valid data from current v1 file-backed JSON and legacy SharedPreferences without overwriting newer v2 entries.

#### Scenario: V1 file-backed cache exists

- **GIVEN** a v2 typed cache file is missing
- **AND** a current v1 `entries.json` cache file exists
- **WHEN** the store is first accessed
- **THEN** valid v1 entries are migrated into the v2 typed file
- **AND** malformed v1 entries are skipped
- **AND** v1 data is cleared only after the v2 write succeeds

#### Scenario: Legacy SharedPreferences cache exists

- **GIVEN** no v2 cache file exists
- **AND** legacy SharedPreferences entries exist for the store
- **WHEN** the store is first accessed
- **THEN** valid legacy entries are migrated into the v2 typed file
- **AND** SharedPreferences entries are cleared only after the v2 write succeeds

#### Scenario: V2 entries already exist

- **GIVEN** a v2 cache file already contains an entry
- **AND** older v1 or legacy storage contains an entry for the same key
- **WHEN** migration runs
- **THEN** the existing v2 entry is preserved
- **AND** the older duplicate entry is not allowed to overwrite it
