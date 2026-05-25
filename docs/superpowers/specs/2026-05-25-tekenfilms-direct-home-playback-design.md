# Tekenfilms Direct Home Playback Design

Date: 2026-05-25

## Problem

The Nexio-only Tekenfilms add-on at `https://tekenfilms.nexioapp.org/manifest.json` exposes a
single movie catalog, `tekenfilms_nl`, containing locally hosted cartoons with Dutch audio tracks.
The live manifest identifies the add-on as `org.nexio.tekenfilms`; catalog items use
`tekenfilms:` ids and the stream endpoint returns player-ready URLs such as
`https://tekenfilms.nexioapp.org/nl-gesproken/...m4v`.

The generic add-on Home path is too heavy for this add-on. Modern Home currently truncates large
catalog rows before display, so not every local movie is visible. Generic clicks route through
detail navigation and metadata hydration, even though the add-on catalog response already carries
the title, poster, logo, background, description, and release year needed for first paint.

## Goals

- Show every Tekenfilms catalog item in its Modern Home rail.
- Keep Tekenfilms Home items first-paint-only.
- Exclude Tekenfilms Home items from detail navigation and metadata hydration.
- Launch playback directly from the add-on-provided stream URL when a Tekenfilms Home item is
  clicked.
- Make the behavior exclusive to the exact Tekenfilms add-on and impossible for other add-ons to
  inherit accidentally.
- Preserve display-authority rules and avoid new hot-list retention risks.

## Non-Goals

- Do not create a generic "direct-play add-on" framework.
- Do not let arbitrary add-on manifests opt into this behavior.
- Do not change search, classic home, grid home, catalog see-all, or detail-screen behavior unless
  needed to preserve Modern Home routing correctness.
- Do not change stream selection or hydration for non-Tekenfilms add-ons.
- Do not store new large JSON blobs in SharedPreferences or DataStore.

## Current Evidence

Live add-on endpoints:

- Manifest: `https://tekenfilms.nexioapp.org/manifest.json`
- Manifest id: `org.nexio.tekenfilms`
- Catalog: `catalog/movie/tekenfilms_nl.json`
- Item ids: `tekenfilms:<slug>`
- Stream: `stream/movie/<tekenfilms-id>.json`

Graph findings:

- `AddonCatalogRailSource.fetchRail` maps add-on catalog rows into `RailItemPreview` first-paint
  data.
- `HomeViewModelCatalogPipeline` applies a generic 25-item display cap for rows larger than 25
  unless the row uses skip pagination.
- `HomeCatalogRefreshCoordinator` and `HomeViewModelCatalogPipeline` schedule Home hydration and
  resolved-display publication.
- `ModernHomeRows` and `ModernHomeContent` route catalog item clicks into `onNavigateToDetail`.
- `StreamRepository.getStreamsFromAddon` can fetch streams from one add-on without searching every
  installed add-on.
- `Screen.Player.createRoute` can launch playback directly when a player-ready stream URL is
  available.

## Recommended Approach

Add a narrow policy helper, for example `TekenfilmsHomePlaybackPolicy`, that centralizes all
matching rules:

- normalized base URL equals `https://tekenfilms.nexioapp.org`;
- manifest id/add-on id equals `org.nexio.tekenfilms`;
- catalog id equals `tekenfilms_nl`;
- type equals `movie`;
- item id starts with `tekenfilms:`.

Use this helper at every exception point instead of repeating string checks in UI code. The helper
should reject partial matches so an add-on with a matching URL but wrong manifest id, or a matching
id but wrong URL, keeps generic behavior.

## Data Flow

Catalog fetch:

1. `AddonCatalogRailSource.fetchRail` continues to fetch the add-on catalog through
   `CatalogRepository.refreshCatalogToDisk`.
2. `RailItemPreview` and `MetaPreview` continue to carry first-paint add-on metadata.
3. The Tekenfilms policy is available to downstream Home code through existing row fields:
   `addonBaseUrl`, `addonId`, `catalogId`, `apiType`, and item id.

Display:

1. `HomeViewModelCatalogPipeline` keeps the full row for Modern Home when the row matches the
   Tekenfilms policy.
2. Generic rows continue to use the existing truncation branch.
3. Modern Home renders the row through the existing row model path. It does not add raw
   provider-specific artwork fallback logic to UI surfaces.

Hydration:

1. Home hydration candidate selection checks the Tekenfilms policy.
2. Matching Tekenfilms items are skipped before metadata/detail hydration or resolved-overlay work.
3. Non-Tekenfilms rows keep the existing hydration path.

Click and playback:

1. Modern Home item click checks the Tekenfilms policy.
2. Matching items call a scoped direct playback launcher instead of `onNavigateToDetail`.
3. The launcher calls `StreamRepository.getStreamsFromAddon` with base URL
   `https://tekenfilms.nexioapp.org`, type `movie`, and the clicked `tekenfilms:*` id.
4. If a player-ready stream URL is returned, the launcher navigates to `Screen.Player.createRoute`
   with title, content id, type, artwork, stream name, and `addonBaseUrl`.
5. If stream lookup fails, Home reports a recoverable launch failure and does not fall back to
   detail hydration or all-addons stream search.

## Error Handling

- No matching policy: use the current generic behavior.
- Matching policy but no stream: keep the user on Home and surface/log a direct playback launch
  failure.
- Stream endpoint error: keep the user on Home and do not route to Detail as a fallback.
- Multiple streams: pick the first player-ready direct URL returned by the Tekenfilms add-on. This
  matches the add-on's single-local-file contract and avoids invoking generic stream scoring.

## Testing

Add focused tests for:

- Policy matching accepts only the exact Tekenfilms URL, manifest id, catalog id, type, and item id
  prefix.
- Modern Home display row projection keeps all Tekenfilms items when the row contains more than 25
  items.
- Non-Tekenfilms add-on rows keep the current 25-item truncation.
- Home hydration candidate selection excludes Tekenfilms items.
- Tekenfilms click routing calls direct playback and does not call detail navigation.
- Non-Tekenfilms click routing still calls detail navigation.
- Direct playback fetches streams only from the Tekenfilms add-on and launches `Screen.Player`
  with the returned URL.

Verification should include `openspec validate add-tekenfilms-direct-home-playback --strict` and
the focused home/add-on/playback unit tests added by the implementation plan.

## Risks

- Unlimited rows increase the number of rendered Modern Home items. The exception is limited to
  this local catalog, and hydration is skipped to reduce memory and network work.
- Bypassing detail means playback launch errors need a clear Home-side failure path.
- Duplicating policy checks could drift. Centralizing the policy helper is required.
- Using only base URL would let an unrelated add-on inherit behavior. The implementation must also
  require manifest id and catalog/item shape.

## Decisions

- The exception is scoped to Modern Home for the Tekenfilms add-on only.
- Tekenfilms items are first-paint-only and do not enter detail hydration.
- Direct playback uses a scoped `StreamRepository.getStreamsFromAddon` call, not all-addons stream
  search.
- Failure to fetch a Tekenfilms stream does not fall back to Detail.
