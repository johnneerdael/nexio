# Change: Add unified artwork pipeline

## Why

Nexio currently treats metadata artwork largely as display strings that are handed to Coil. Coil is
useful as the Android renderer and bitmap cache, but it is not the right owner for artwork
precedence, provider policy, runtime cache decisions, stale behavior, profile/language scope, or
auditability.

This creates two problems:

- premium poster providers can fail to win over primary provider artwork because poster ownership is
  resolved as ordinary metadata fields rather than through a first-class artwork decision
- TMDB, TVDB, Kitsu, addon, rail, and premium artwork can still be represented as raw remote URLs in
  UI-facing models, so runtime/cache traces cannot prove network suppression or policy ownership

The app needs a unified artwork pipeline for all metadata artwork, with typed display references as
the canonical model and legacy string fields kept only as derived compatibility projections during a
staged migration.

## What Changes

- Add canonical artwork models: `ArtworkBundle`, `ArtworkDisplayRef`, runtime
  `ArtworkCandidate`, persisted-safe `ArtworkDecision`, and `ArtworkAssetRecord`.
- Add an `ArtworkRouter` ownership boundary that creates candidates, applies provider capability
  checks, selects winning artwork by type, and records rejected candidates.
- Add an `ArtworkDecisionCache` for semantic choices such as "poster for this item uses
  TOP_POSTERS with these settings."
- Add an `ArtworkAssetRepository` and app-owned `ArtworkAssetDiskCache` for image bytes/files.
- Route metadata artwork fetches through `IntegrationRuntime` with explicit `CacheFirst` policies,
  runtime `apiShapeId`, cache decisions, stale windows, and backoff behavior.
- Add a `nexio-artwork://` Coil fetcher so Coil renders local/runtime-backed artwork assets rather
  than raw provider URLs.
- Keep legacy `poster`, `backdrop`/`background`, `logo`, and `thumbnail` string fields during a
  compatibility period, but derive them only from `ArtworkDisplayRef`.
- Add boundary tests, runtime/cache tests, and metadata execution report output proving that artwork
  decisions and asset fetches are traceable.

Fanart.tv support, premium backdrops/logos, and additional premium artwork providers are design
targets for extensibility, but Fanart.tv implementation is not part of this change.

## Impact

- Affected app: `app`
- Affected areas:
  - `app/src/main/java/com/nexio/tv/core/artwork/`
  - `app/src/main/java/com/nexio/tv/core/image/`
  - `app/src/main/java/com/nexio/tv/core/integration/`
  - `app/src/main/java/com/nexio/tv/data/metadata/`
  - `app/src/main/java/com/nexio/tv/data/home/`
  - `app/src/main/java/com/nexio/tv/ui/`
  - `app/src/test/`
- Affected spec:
  - `artwork-cache-pipeline`
- Affected rollout:
  - staged migration with typed artwork references canonical immediately
  - legacy string fields remain temporarily as one-way compatibility projections
  - raw remote provider URLs remain allowed only as source payload/fetch data, not final metadata
    display ownership
  - primary metadata caches must survive premium artwork setting changes
