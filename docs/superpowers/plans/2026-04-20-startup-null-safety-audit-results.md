# Startup Null-Safety Audit Results

## Confirmed Crash Class

Legacy Gson/SharedPreferences JSON can hydrate newly added Kotlin non-null fields as `null`, bypassing constructor defaults. This can crash generated `hashCode`, `copy`, equality checks, and UI code.

The concrete crash after profile selection came from cached/snapshot models that predated `ratingSource`:

- `MetaPreview.hashCode()` crashed when snapshot comparisons reached a null `ratingSource`.
- `HomeDisplayMetadata` construction failed when legacy continue-watching display metadata omitted `ratingSource`.
- `TmdbEnrichment.copy()` failed when legacy TMDB disk cache omitted `ratingSource`.

This crash class can affect users with only the default profile because the affected stores still read profile-scoped startup/home/discovery snapshots for profile 1.

## High-Risk Paths Hardened

- `MetadataDiskCacheStore`: `Meta`, `TmdbEnrichment`, `TvMetadataEnrichment`
- `HomeCatalogSnapshotStore`: `CatalogRow`, `MetaPreview`
- `SyntheticHomeCatalogStore`: `CatalogRow`, `MetaPreview`
- `TraktDiscoverySnapshotStore`: `MetaPreview`, `TraktCustomListCatalog`
- `SimklDiscoverySnapshotStore`: `MetaPreview`
- `MDBListDiscoverySnapshotStore`: `MDBListCustomCatalog`
- `ContinueWatchingSnapshotStore`: `HomeDisplayMetadata`
- `CatalogDiskCacheStore`: `CatalogRow`, `MetaPreview`

The hardening uses `MetadataModelSanitizers.kt` at decode boundaries, so restored models are normalized before hash/equality/copy/UI code touches them.

## Recent Release Features Reviewed

- Title rating provider sources: high risk. Fixed by making the new source field nullable for Gson compatibility and sanitizing restored models.
- TMDB/TVDB enrichment changes: medium risk because enrichment objects are disk-cached. Fixed by disk-cache sanitizers.
- Kitsu settings/auth: low risk because settings are stored in DataStore preferences with explicit defaults rather than raw Gson model snapshots.
- Kitsu metadata rows: medium risk only where anime items enter home/discovery snapshots as `MetaPreview`. Covered by `MetaPreview` sanitization.
- ASS/SSA translation AST/protection: low startup risk because those new data classes are not restored from startup SharedPreferences snapshots.
- Custom IMDb bulk ratings: low startup risk because title-rating cache is in-memory only.

## Ongoing Rule

Any new field added to a model decoded from Gson/SharedPreferences must be either nullable and normalized after decode or covered by a schema bump plus cache invalidation. Kotlin constructor defaults alone are not sufficient for Gson-loaded legacy data.

For startup-critical snapshots, prefer decode-time sanitizers over scattered UI guards. UI guards can prevent one surface from crashing, but snapshot comparisons, generated `hashCode`, and `copy` calls may run earlier during startup.
