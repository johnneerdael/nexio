# Unified Watchlist Library — Design

**Status:** Spec / brainstorm. Implementation plan to be drafted via `superpowers:writing-plans` after user review.

**Date:** 2026-05-13

**Reference material:**
- `~/Scripts/CrossWatch/providers/scrobble/trakt/sink.py`
- `~/Scripts/CrossWatch/providers/scrobble/simkl/sink.py`
- `~/Scripts/CrossWatch/providers/scrobble/mdblist/sink.py`
- `~/Scripts/CrossWatch/providers/scrobble/scrobble.py`
- CrossWatch docs: `https://wiki.crosswatch.app/crosswatch/scrobble`
- CrossWatch docs: `https://wiki.crosswatch.app/crosswatch/providers/authentication/auth-trackers/auth-mdblist`
- CrossWatch docs: `https://wiki.crosswatch.app/crosswatch/configuration-config-json`

## Problem

Nexio already has strong Trakt, Simkl, MDBList, Modern Home, Continue Watching, and resolved-display infrastructure. CrossWatch adds two TV-app-relevant ideas that fit Nexio:

1. A unified watchlist view across tracking providers.
2. MDBList as a scrobble sink, in the same family as Trakt and Simkl scrobbling.

The first feature should ship before MDBList scrobble. A unified watchlist needs correct provider membership and canonical display identity; MDBList writes should not be added until the existing Trakt/Simkl scrobble behavior has been audited against the CrossWatch implementation and the shared write/rate-limit model is clear.

## Goals

1. Add **Unified Watchlist** as the default full-screen Library tab.
2. Render one canonical watchlist item per movie or show.
3. Reuse the exact existing Modern Home / resolved-display hydration path for item identity and metadata. Do not invent a second ID resolver or display authority.
4. Keep provider membership and raw provider keys as hidden operational state for filtering, diagnostics, and future mutation routing.
5. Keep the UI provider-neutral. Do not show provider badges on normal cards.
6. Treat series as show-level library entries. Do not show one row per season or episode.
7. Keep Continue Watching / Up Next / playback progress out of this surface.
8. Audit current Trakt and Simkl scrobble behavior against CrossWatch before designing MDBList scrobble implementation.
9. Shape the architecture so MDBList scrobble can be added next using the same event, outbox, rate-limit, and ID-body rules.

## Non-goals

- No MDBList scrobble implementation in this first feature.
- No new metadata resolver, stable-ID resolver, or artwork path.
- No progress, resume, next-episode, or watched-history row behavior in Unified Watchlist.
- No destructive cross-provider remove/mirror behavior in the first version.
- No provider badges on item cards.
- No portal/control-plane feature work.

## User-Facing Behavior

Library opens to the Unified Watchlist tab by default. The tab presents a grid/list of movies and shows that appear in any supported watchlist source.

Each card is provider-neutral:

- title
- artwork
- year when available
- movie/show type affordance when the existing card pattern supports it
- standard click-through into the existing detail/playback route

If an item is present in Trakt, Simkl, MDBList, and local state, it still renders once. Source membership is not decorative UI. It may be exposed only in diagnostics, filters, or an item overflow/details affordance if that becomes necessary.

Series render as a show row. Episode-level source data may be used internally only to discover that the show belongs in the watchlist, but it must not create per-episode rows and must not display progress or next-episode context.

## Architecture

### Existing Display Authority

Unified Watchlist must feed the same resolved-display pipeline used by Modern Home:

- `ResolvedDisplaySurfaceRepository` remains the display authority.
- Existing hydration, metadata routing, artwork decision, and stable-ID behavior remain authoritative.
- New code may collect watchlist membership, but it must not decide final artwork/title/year independently from the resolved-display path.

The implementation should create a watchlist-specific surface or approved projection that resolves through the same authority rules as home. Raw provider DTOs may be used only at the ingestion boundary.

### Components

#### `UnifiedWatchlistRepository`

Collect cached/snapshot-backed watchlist membership from existing provider services:

- Trakt watchlist/library state.
- Simkl watchlist/library state if available.
- MDBList watchlist/list state if available.
- Local/library membership where applicable.

The repository should avoid opening the tab with fresh provider fan-out. It should read current snapshots first and rely on existing refresh services for background freshness.

Output is structural membership, not display metadata:

```kotlin
data class UnifiedWatchlistMembership(
    val authorityKey: String,
    val mediaKind: MediaKind,
    val presentIn: Set<UnifiedWatchlistSource>,
    val sourceRefs: Map<UnifiedWatchlistSource, SourceRef>,
    val confidence: MembershipConfidence
)
```

`authorityKey` should be derived through existing canonical item-key helpers that already feed resolved display. It should not be derived with a new ad hoc string scheme.

#### `UnifiedWatchlistMembershipReducer`

Merge provider membership for the same canonical movie/show. The reducer should lean on existing stable IDs and resolved-display keys. If raw source inputs cannot be confidently associated with the same canonical resolved item, keep them separate rather than merging by weak title similarity.

The reducer may keep a low-confidence marker for diagnostics, but weak fallback identity must not override resolved-display authority.

#### `UnifiedWatchlistProjection`

Join membership with resolved-display items to produce UI rows:

```kotlin
data class UnifiedWatchlistRowItem(
    val displayItem: ResolvedDisplayItem,
    val mediaKind: MediaKind,
    val presentIn: Set<UnifiedWatchlistSource>,
    val sourceRefs: Map<UnifiedWatchlistSource, SourceRef>
)
```

The UI consumes `displayItem` for presentation. `presentIn` and `sourceRefs` are hidden operational state for filters, diagnostics, and future write routing.

#### `UnifiedWatchlistViewModel`

Expose the rendered watchlist rows as a dedicated `StateFlow`, not as a large list inside a broad observed `UiState`. This follows the project rule that hot source/lookup lists do not belong in Compose-observed state data classes.

### Library Integration

The Library screen should default to Unified Watchlist. Existing library tabs remain reachable. The tab should support initial empty/loading/error states that match the current TV UI style.

Version one should ship without provider filters unless implementation discovers an existing Library filter component that makes them essentially free. The default behavior is one `All` watchlist surface with standard Library navigation.

Movie/show/anime segmentation may reuse an existing Library filter if one is already present. Do not add a new filter framework for this feature.

Any future provider filter restricts membership source; it does not change the card contract and does not add provider badges to cards.

## Scrobble Audit Workstream

Before MDBList scrobble is planned, audit Nexio's current Trakt and Simkl scrobble behavior against CrossWatch.

CrossWatch scrobble docs state that scrobbling sends progress updates, pause/stop, and completion, and that good ID matching matters. The implementation adds provider-specific behavior that Nexio should compare directly:

- start/pause/stop endpoint selection
- progress floor for start events
- progress bucket behavior, defaulting to 5% style buckets in CrossWatch sinks
- near-end start suppression
- pause-at-high-progress handling
- stop demotion/promotion thresholds
- duplicate and debounce behavior
- Retry-After and HTTP 429 handling
- HTTP 409 handling
- request body shape for movies, shows, episodes, and anime
- ID preference and fallback ordering
- cached successful request skeletons / "best body" behavior

Known CrossWatch defaults from config:

- SIMKL: `10 GET/sec`, `1 write/sec`
- MDBList: `10 GET/sec`, `1 write/sec`
- Trakt: `3.33 GET/sec`, `1 write/sec`

The audit output should be a small compatibility matrix and a list of required Nexio fixes, if any. If Nexio behavior intentionally differs, record the reason.

### MDBList Scrobble Preparation

MDBList scrobble should be a follow-up after the audit. The design should reuse the same provider mutation outbox family used by Trakt and Simkl, not create a one-off direct writer.

MDBList-specific constraints from CrossWatch:

- API key auth, not OAuth.
- Writes require stable IDs, especially TMDb/IMDb.
- MDBList has weaker user-data rollback/export behavior, so writes must be conservative.
- Default write pacing should be `1 write/sec`.
- Retry-After must be honored.

## Error Handling

Unified Watchlist should degrade by source:

- If one provider snapshot is unavailable, show rows from the remaining sources.
- If no sources are available, show the Library empty state with a settings/auth hint that fits existing app copy.
- If a provider refresh fails, keep the last good snapshot and surface diagnostics through existing debug/log channels rather than blocking the tab.
- If an item cannot be resolved through the display authority, keep it out of the main grid for version one and log diagnostics. Do not invent an unresolved-item fallback UI for this feature.

Do not perform destructive cleanup based on a partial or failed source read.

## Persistence And Memory

No large watchlist blobs should be added to SharedPreferences or DataStore. If a new persisted snapshot is needed and can exceed 50 KB, use a file-backed streaming JSON store with atomic rename, following the project persistence rule.

The ViewModel should keep source membership and rendered rows in separate flows where needed. Avoid putting raw provider lists into a broad Compose-observed state object.

Any suspend iteration over provider lists must use indexed loops where suspension can happen, following the project coroutine rule.

## Testing

Unit coverage:

- Membership reducer merges strong-ID duplicates into one show/movie row.
- Weak or ambiguous source inputs remain separate.
- Episode inputs collapse to show-level membership and never produce episode rows.
- Provider membership is preserved as hidden state.
- Projection uses resolved-display items for UI fields.
- Missing provider snapshots degrade without losing rows from other sources.

Scrobble audit coverage:

- Trakt request body shape parity against representative CrossWatch cases.
- Simkl request body shape parity, including anime parent selection.
- Progress/action boundary tests for pause/stop/start timing.
- Retry classification tests for 409, 429, and 5xx behavior.

UI/smoke coverage:

- Library opens to Unified Watchlist by default.
- Profile selection is completed before any home/library smoke that depends on profile-owned data.
- Unified Watchlist renders movie/show rows without provider badges.
- Series watchlist data does not create episode rows or progress text.

## Acceptance Criteria

1. Library defaults to Unified Watchlist.
2. Unified Watchlist displays provider-neutral movie/show rows.
3. Series render at show level only.
4. No Continue Watching, Up Next, resume, or progress context appears in Unified Watchlist.
5. Display fields come from the existing resolved-display hydration path.
6. Provider membership and source refs are retained internally for filters/diagnostics/future writes.
7. No raw provider DTO list is stored in a broad Compose-observed UI state.
8. Scrobble audit document or section records Trakt/Simkl parity against CrossWatch and identifies fixes before MDBList scrobble work begins.
9. Any new persistent snapshot over 50 KB uses streaming file JSON, not SharedPreferences or DataStore.
10. Existing Trakt/Simkl scrobbling tests still pass after audit-driven fixes.

## Implementation Investigation Tasks

These are code-discovery tasks for the implementation plan, not product behavior decisions:

1. Identify the current Simkl watchlist/list membership source in Nexio, or confirm that the first implementation must omit Simkl membership until a cached source exists.
2. Identify whether MDBList watchlist membership can safely come from existing snapshots, or whether a dedicated cached membership source is required.
3. Identify the existing Library tab/filter components to reuse. If none fit directly, ship the first version as the unfiltered `All` watchlist surface.
