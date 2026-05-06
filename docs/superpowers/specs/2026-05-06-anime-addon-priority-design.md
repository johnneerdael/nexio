# Anime Addon Priority — Design

**Date:** 2026-05-06
**Status:** Approved for implementation planning (amended 2026-05-06 to address review)
**Owner:** John Neerdael

> **Amendments since first approval:** (1) Anime detection uses a boolean `AnimeIdentityIndex.isAnime(parsed)` API rather than `resolveKitsuId(...) != null`, to avoid arbitrarily selecting one Kitsu record from a one-to-many IMDb→Kitsu mapping. (2) Episode IDs are normalized to parent IDs via `MetadataRequestNormalizer.parentIdOf(...)` before parsing. (3) Trace events `stream.request_classified` and `stream.addon_bucketed` are in scope (not deferred). (4) An explicit non-goal: `addon.isAnime` is a stream-priority hint only — it is forbidden as input to metadata routing or catalog classification. (5) Tests added for episode-id normalization, IMDb one-to-many mapping, and the empty anime-tagged addon fallback.

## Problem

Generic stream addons (Torrentio, StremThru, AIO, etc.) routinely return poor or absent results for anime titles, where naming conventions, season/episode mappings, and source pools differ from mainstream Western movies and shows. Users who curate dedicated anime addons have no way to tell NEXIO that those addons should be preferred when fetching streams for anime content.

Today, addons carry one categorization dimension — `parserPreset` (`GENERIC | STREMTHRU | TORRENTIO | WEBSTREAMR`) — which only drives stream-line parsing. Addon order in the stream list is governed by `sortOrder`, which is content-agnostic.

## Goal

Introduce a per-addon "anime specialty" tag that, when content is detected as anime, floats anime-tagged addon sections above the rest in both the manual stream list and the autoplay candidate stream order. Within each bucket, the existing `sortOrder` rule applies unchanged. For non-anime content, behavior is byte-identical to today.

## Non-goals

- **Catalog/rail ordering.** Catalog rails can contain mixed content; the priority is purely a *stream-resolution* concern. Rail composition is unaffected.
- **Search/meta resolution.** Only the stream pipeline reads the new flag.
- **Metadata-routing input.** `addon.isAnime` MUST NOT influence `MetadataRouter` primary authority, identity resolution, catalog classification, or any other routing decision. It is a stream-priority hint, period. The trace validator `RouteDecisionUsedInputs` already forbids `addon` and `animeType` tokens in `usedInputs`; this design preserves that invariant — `MetadataRouter` never reads the new field.
- **Auto-detection.** No heuristic that infers an addon is anime from its manifest. Users opt in explicitly per addon.
- **Multi-specialty taxonomy.** A boolean is enough for this iteration. A future migration to `specialty: 'anime' | 'kids' | ...` is not blocked by this design.

## Decisions locked in

| Decision | Choice |
|---|---|
| When does the priority kick in? | **Content-aware.** Only when content is detected as anime. |
| Sort vs filter? | **Soft re-bucket.** All addons fetched in parallel; anime-tagged sections sort above the rest. No fetch gating. |
| Anime detection | Reuse the existing `AnimeIdentityIndex` — no parallel system. New default method `isAnime(parsed): Boolean` (initial implementation `= resolveKitsuId(parsed) != null`) gives stream priority a boolean API that survives the upcoming one-to-many IMDb→Kitsu mapping pack without forcing a single Kitsu record selection. |
| Episode-ID normalization | Parse the *parent* content ID, not the episode ID, before anime detection. Reuse the existing `MetadataRequestNormalizer.parentIdOf(...)` helper — extracted into a pure top-level function so the stream pipeline can call it without DI churn. |
| Schema shape | **Boolean `isAnime`** on `AddonRecord`. Single field, optional, defaults to `false`. |
| Surface scope | **Streams only.** Manual stream list and autoplay. Catalogs, search, meta untouched. |
| Pipeline seam | **Tag at repository, sort at consumer.** Repo computes `isAnimeBucket` once per request and stamps it on each emitted `AddonStreams`; the existing `StreamAutoPlaySelector.orderAddonStreams` comparator applies the bucket sort. |

## Architecture

### Data model

**Web — `nexio-web/types/portal.ts`:**
```ts
export type AddonRecord = {
  // ...existing fields...
  isAnime?: boolean   // default false; absent on read = false
}
```

**Android — `com.nexio.tv.domain.model.Addon`:**
```kotlin
data class Addon(
    // ...existing fields...
    val isAnime: Boolean = false,
)
```

**Android — `com.nexio.tv.domain.model.AddonStreams`:**
```kotlin
data class AddonStreams(
    val addonName: String,
    val addonLogo: String?,
    val streams: List<Stream>,
    val isAnimeBucket: Boolean = false,   // = addon.isAnime && contentIsAnime
)
```

The persisted source of truth is the per-account portal record. Both nexio-web and Android read/write through the same record shape.

### Editing UI

**nexio-web addon manager:** new "Anime" toggle on each addon row and on the add-addon dialog, persisted via the existing addon-update mutation. Helper text: "Prioritize for anime content."

**Android `AddonManagerScreen`:** new toggle row in the addon edit panel, mirroring how `parserPreset` is exposed today. Plumbs through a new `AddonManagerViewModel.updateAddonIsAnime(baseUrl, value)` method, mirroring the existing `updateAddonParserPreset`.

No badge or grouping in the addon-list view; the toggle in the edit panel is sufficient discoverability.

### Stream pipeline

**Resolve `contentIsAnime` once per request** in `StreamRepositoryImpl.fetchStreams`, immediately after `streamAddons` is computed:

```kotlin
val parentContentId = MetadataParentIdNormalizer.parentIdOf(videoId)
val parsedContentId = MetadataIdParser.parse(parentContentId)
val contentIsAnime = parsedContentId?.let { parsed ->
    animeIdentityIndex.isAnime(parsed)
} ?: false
```

Three pieces:

1. `MetadataParentIdNormalizer.parentIdOf(...)` is a top-level pure function extracted from `MetadataRequestNormalizer.parentIdOf(...)`. The existing instance method delegates to it. This avoids requiring the stream pipeline to inject a class with a `TraceMetadataEvents` dependency just to do string parsing, while keeping a single source of truth for episode-id → parent-id normalization.
2. `AnimeIdentityIndex.isAnime(parsed)` is a new default method on the existing interface: `suspend fun isAnime(id: ParsedMetadataId): Boolean = resolveKitsuId(id) != null`. The default keeps current behavior; once the Anime Mapping Pack lands with one-to-many IMDb→Kitsu mappings (`recordsForImdbId(...)`, `isAnimeImdbId(...)`), the implementation overrides `isAnime` to use a boolean lookup that doesn't need to pick a single canonical record. Stream priority calls only `isAnime(...)` and never `resolveKitsuId(...)`.
3. `AnimeIdentityIndex` is a `@Singleton` injected into the repository. The signal covers Kitsu/MAL/AniList/AniDB native IDs *and* the IMDB→Kitsu mapping pack — exactly the same definition the rest of the app already uses (`MetadataRouter`, `TrackingScrobbleService`, anime season projection).

**Tag each emitted `AddonStreams`** at the existing emit site (~line 113 of `StreamRepositoryImpl`):

```kotlin
emittedAddonStreams = AddonStreams(
    addonName = addon.displayName,
    addonLogo = addon.logo,
    streams = streamsResult.data,
    isAnimeBucket = addon.isAnime && contentIsAnime,
)
```

Fetch parallelism is unchanged — every installed stream-resource addon still launches concurrently. The bucket flag is metadata, not a fetch gate.

### Consumer sort

Single seam: `StreamAutoPlaySelector.orderAddonStreams`, called from `StreamScreenViewModel:408` and feeding both the manual stream list (`addonStreams = organizedResult.orderedAddonStreams` at line 514) and the autoplay candidate flatten (`orderedAddonStreams.flatMap { it.streams }` at line 412).

**Today:**
```kotlin
return streams.sortedBy { addonStreams ->
    installedOrder.indexOf(addonStreams.addonName).let { index ->
        if (index >= 0) index else Int.MAX_VALUE
    }
}
```

**After:**
```kotlin
return streams.sortedWith(
    compareByDescending<AddonStreams> { it.isAnimeBucket }
        .thenBy { addonStreams ->
            installedOrder.indexOf(addonStreams.addonName).let { index ->
                if (index >= 0) index else Int.MAX_VALUE
            }
        }
)
```

`candidateAutoPlayStreams` is unchanged: because it consumes a `List<Stream>` flattened *after* `orderAddonStreams`, autoplay automatically picks anime-bucket streams first when scanning for `FIRST_STREAM` and `REGEX_MATCH` matches.

**Web parity:** the same two-level comparator is applied client-side in nexio-web's stream view, keyed on the `isAnimeBucket` field returned by the API.

## Edge cases

| Case | Behavior |
|---|---|
| Non-anime content | Every addon's `isAnimeBucket = false`. Comparator's first key collapses. Ordering byte-identical to today. |
| Anime content, no anime-tagged addons installed | All `isAnimeBucket = false`. Comparator collapses. Ordering identical to today. Feature invisible until at least one addon is tagged. |
| Anime-tagged addon returns zero streams | Generic-tagged addons ran in parallel and their streams are visible below an empty anime section. No fallback logic needed — never gated fetching. |
| `MetadataIdParser.parse(parentContentId)` returns null | `contentIsAnime = false`. Behavior identical to today. Anime-priority is only ever additive, never subtractive. |
| Episode ID (`tt12343534:1:1`, `kitsu:7442:1:1`, `mal:21:1:1`) | Normalized to its parent (`tt12343534`, `kitsu:7442`, `mal:21`) via `parentIdOf(...)` before parsing, so the anime check operates on the work's identity rather than the episode coordinate. |
| IMDb ID maps to multiple Kitsu records (one-to-many) | `isAnime(parsed)` returns `true` without committing to any specific Kitsu record. `contentIsAnime = true`. The stream pipeline never asks for a canonical Kitsu ID, so the Anime Mapping Pack is free to refuse one. |
| Older Android client reads new server data | Unknown `isAnime` field ignored at JSON parse. No crash. |
| Newer Android client reads old server data without `isAnime` | Decoded as `false` via Kotlin default. No migration step. |

## Migration

None. The new field is optional on the wire (`isAnime?: boolean` web / `Boolean = false` Android default). No data migration script, no schema version bump.

## Testing

**Android unit tests (new):**
- `MetadataParentIdNormalizerTest` — `tt12343534:1:1 → tt12343534`, `kitsu:7442:1:1 → kitsu:7442`, `mal:21:1:1 → mal:21`, parent IDs unchanged, blank/garbage inputs returned as-is. Ensures the extracted top-level function preserves the existing `MetadataRequestNormalizer.parentIdOf(...)` contract.
- `AnimeIdentityIndexIsAnimeTest` — default `isAnime(parsed)` returns `resolveKitsuId(...) != null`; overrides can return `true` without resolving (forward-compat for one-to-many).
- `StreamAutoPlaySelectorAnimePriorityTest` — anime-bucket sections sort above non-anime; ties resolved by `installedOrder`; mixed input produces stable two-level ordering; all-non-anime input ordering is byte-identical to legacy output (regression guard); empty anime-bucket section preserved at the top of its bucket (does not collapse other anime sections).
- `StreamRepositoryImplAnimeBucketTest` — `isAnimeBucket = true` only when both `addon.isAnime` and `AnimeIdentityIndex.isAnime(parsed) == true`; falsy when either condition fails; unparseable parent ID → all buckets false; **`kitsu_episode_id_routes_to_anime_bucket`** (`videoId = "kitsu:7442:1:1"` is normalized to `kitsu:7442` before lookup); **`imdb_episode_id_routes_to_anime_bucket_when_parent_imdb_is_anime`** (`videoId = "tt12343534:1:1"` normalized to `tt12343534`, IMDb→anime mapping returns true); **`mal_episode_id_routes_to_anime_bucket`** (`mal:21:1:1` normalized to `mal:21`); **`imdb_one_to_many_anime_id_sets_contentIsAnime_without_selecting_single_kitsu_record`** (override of `isAnime` returns true without delegating to `resolveKitsuId`; `contentIsAnime = true` and no Kitsu ID is consumed); **`anime_tagged_addon_empty_generic_addons_still_selected`** (anime-tagged addon emits zero streams; generic addons run in parallel and their sections appear below the empty anime section; autoplay falls through to generic streams).
- `AddonManagerViewModelAnimeToggleTest` — toggling `isAnime` persists through the existing addon-update path.
- `MetadataRouterIgnoresAddonIsAnimeTest` — verifies `addon.isAnime` is never read by `MetadataRouter` or any class on its `usedInputs` path. Asserted via construction: the router's constructor and the `RouteDecisionUsedInputs` validator both stay green when an anime-tagged addon is installed.

**nexio-web tests (new):**
- API serializer round-trip for `isAnime` (present, absent, explicit false).
- Stream-list comparator parity — shared fixture with the Android test, asserts identical ordering output.

**Trace events (new — in scope, not deferred):**
- `stream.request_classified` — emitted once per `getStreamsFromAllAddons` call. Payload: `{ contentId, parentId, contentIsAnime, evidence }`. `evidence` = `"AnimeIdentityIndex"` (room for future overrides like `"AnimeMappingPack"`).
- `stream.addon_bucketed` — emitted once per addon section as it lands in the result. Payload: `{ addonIdHash, addonIsAnime, contentIsAnime, isAnimeBucket }`. `addonIdHash` is the existing addon-id hashing scheme used elsewhere in the trace; the addon ID itself is not emitted.

Both events live alongside the existing `metadata.*` and `anime.*` event families on `TraceMetadataEvents`. New optional validator rules are added: a smoke rule asserting that `stream.addon_bucketed` is only ever emitted with `isAnimeBucket = (addonIsAnime && contentIsAnime)`, and the existing `RouteDecisionUsedInputs` invariant continues to forbid `addon` and `animeType` tokens in `metadata.route_decision.usedInputs` (no change required, but covered by `MetadataRouterIgnoresAddonIsAnimeTest`).

## Out-of-scope follow-ups

- Multi-specialty schema (`specialty: 'anime' | 'kids' | 'sports' | ...`). Re-evaluate after a second specialty appears.
- Per-content-type override (e.g. "this addon is anime for series only"). Not requested.
- Visual grouping in the addon manager list. Skipped per user direction.
- Catalog/rail ordering by addon specialty. Explicitly out — rails contain mixed content.
