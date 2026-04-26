You’re absolutely right to call this out — your current spec was still **too abstract** for engineering. It didn’t explicitly connect:

* **real addon payload shapes → routing decisions**
* **real fields → rendering vs canonical overwrite**
* **real edge cases (Crunchyroll tt-anime, Disney mixed rows) → exact code paths**

So below is a **rewritten, engineering-grade spec** that *directly incorporates your survey data* and removes ambiguity.

---

# 🔧 Engineering Spec v2 — MetadataRouter + ResolverOrchestrator (Concrete Version)

This version is **implementation-ready**. It explicitly defines:

* where `type` comes from
* how addon metadata is used
* exact anime resolution chain
* exact fallback behavior
* exact handling of Crunchyroll/Disney anomalies

---

# 0. Key invariant (must be understood first)

```text
Addon metadata is ALWAYS the first render source.
MetadataRouter is ONLY used for canonical enrichment.
```

This is the most important simplification.

---

# 1. Source-of-truth for routing inputs

## DO NOT use catalog-level type

From your data:

```text
Disney catalog → type: "series"
items → mixed movie + series
```

👉 Therefore:

```text
ALWAYS use item.type
NEVER use catalog.manifest.type
```

---

## DO NOT assume id format defines media

From Crunchyroll:

```text
id = tt12343534
type = series
animeType = ⟪ABSENT⟫
```

👉 This is indistinguishable from live-action unless you use Fribb.

---

## Final routing inputs

Routing must use ONLY:

```kotlin
item.id           // always present
item.type         // always present, reliable
AnimeIdentityIndex
IdMappingStore
```

Optional but NOT required:

```text
imdb_id
kitsu_id
tmdb_id
tvdb_id
animeType
```

---

# 2. Final routing rule (canonical)

```kotlin
fun route(itemId: String, itemType: ContentType): MetadataRoute {

    val parentId = parentIdOf(itemId)

    // STEP 1 — anime prefix
    if (isAnimePrefix(parentId)) {
        return route(KITSU, reason = ANIME_PREFIX)
    }

    // STEP 2 — Fribb / local mapping
    val parsed = parseId(parentId)

    if (parsed != null) {
        if (idMappingStore.hasMapping(parsed → KITSU)) {
            return route(KITSU, reason = ID_MAPPING_STORE_HIT)
        }

        if (animeIdentityIndex.resolve(parsed) != null) {
            persistMapping(parsed → KITSU)
            return route(KITSU, reason = FRIBB_HIT)
        }
    }

    // STEP 3 — fallback by type
    return if (itemType == SERIES) {
        route(TVDB, reason = SERIES_DEFAULT)
    } else {
        route(TMDB, reason = MOVIE_DEFAULT)
    }
}
```

---

## 🚨 Explicitly NOT allowed

```text
DO NOT:
- use catalog id (e.g. crunchyroll-overall)
- use addon name
- use genre == Animation
- use popularity/trend fields
```

---

# 3. Addon metadata rendering (CRITICAL)

From your survey:

All addons provide usable preview:

```text
id
name
poster
background
description
releaseInfo
runtime
imdbRating
```

## Rendering rule

```text
Step 1 → render addon MetaPreview immediately
Step 2 → route + canonical fetch in background
Step 3 → replace fields ONLY on success
Step 4 → fallback to addon metadata on failure
```

---

## Replace vs Merge

```text
primary metadata fields:
    replace (never merge)

secondary fields:
    merge via resolvers
```

---

# 4. ProviderPlanExecutor (explicit mapping)

## Movie (non-anime)

```text
Provider: TMDB

Fetch:
GET /movie/{id}?append_to_response=credits,images,release_dates,external_ids

Lazy:
videos, recommendations, reviews
```

---

## TV (non-anime)

```text
Provider: TVDB

Fetch:
GET /series/{id}/extended

Optional:
GET /series/{id}/translations/{lang}

Season:
GET /series/{id}/episodes/{season-type}?page=0
```

---

## Anime

```text
Provider: Kitsu

Fetch:
GET /anime/{id}

Episodes:
GET /anime/{id}/episodes?page[...]

Advanced:
GET /castings?filter[anime_id]=...
GET /anime/{id}/anime-staff
GET /anime/{id}/anime-productions
GET /anime/{id}/media-relationships
```

---

# 5. ResolverOrchestrator (explicit execution)

## PREVIEW

```text
NO network required
Use addon metadata
Optional:
  cached rating
  cached artwork
```

---

## DETAIL_CORE

```text
Primary provider ONLY
Optional:
  cached rating
  artwork decision
```

---

## DETAIL_SECONDARY

```text
ReviewResolver
RecommendationResolver
AnimeDetailResolver
```

---

## PLAYER

```text
TrackingResolver
SkipSegmentResolver
NO metadata prefetch
```

---

# 6. Artwork handling (FIXED — missing earlier)

## Core rule

```text
Poster ≠ metadata
Poster = resolver decision
```

---

## Cache layers

```text
1. Primary metadata cache
   (TMDB/TVDB/Kitsu)

2. Artwork decision cache
   (Top-Posters / RPDB selection)

3. Image cache
```

---

## Critical behavior

When user enables Top-Posters:

```text
primary metadata cache → still valid
artwork decision cache → INVALID
image cache → refreshed as needed
```

---

## Required invalidation triggers

```text
enable/disable premium provider
switch RPDB ↔ Top-Posters
API key change
poster style change
language change
badge options change
```

---

# 7. Continue Watching (fixed behavior)

## Current bug

```text
CW re-fetch → loses original metadata → wrong provider/artwork
```

---

## Correct behavior

### At playback start

Persist:

```text
parentId
provider
HomeDisplayMetadata (addon)
```

---

### CW render

```text
canonical fetch
→ click-time metadata
→ persisted fallback
```

---

### External CW (Trakt/Simkl)

```text
normalize ids
resolve parentId
route normally
apply tracking overlay
```

---

# 8. IdMappingStore (explicit)

## Schema

```text
(source_scheme, source_id)
→ (target_scheme, target_id, resolved_at, source_of_record)
```

---

## Sources

```text
ADDON
FRIBB
RESOLVED_TMDB
RESOLVED_TVDB
RESOLVED_KITSU
NEGATIVE
```

---

## Rules

```text
positive mappings → long-lived
negative mappings → 30d TTL
bidirectional only when known
```

---

# 9. Critical edge cases (must pass)

## Crunchyroll anime

```text
tt12343534 + series
→ Fribb hit → KITSU
```

---

## Disney mixed row

```text
catalog.type = series
item.type = movie → TMDB
item.type = series → TVDB
```

---

## Marvel minimal data

```text
id only → fallback routing works
addon metadata still renders
```

---

## Anime with missing imdb_id

```text
kitsu:... → KITSU
no problem
```

---

## Anime with NO markers + Fribb miss

```text
→ treat as live action
→ DO NOT guess
```

---

# 10. Implementation checklist

## MUST implement

```text
MetadataRouter
AnimeIdentityIndex
parentIdOf()
IdMappingStore
catalog cross-ref harvesting
ProviderPlanExecutor
ResolverOrchestrator
FieldResolver
ArtworkDecisionCache
ContinueWatching fixes
```

---

# 11. Acceptance criteria (strict)

```text
Home renders instantly from addon data
Crunchyroll anime routes Kitsu
Disney mixed row renders correctly
CW preserves metadata after playback
Switching Top-Posters updates posters immediately
No provider call happens before AnimeIdentityIndex ready
No ViewModel calls Retrofit directly
```

---

# Final takeaway

Your research was correct — the original spec failed because it was:

```text
too abstract
too provider-centric
not grounded in real addon data
```

The correct model is:

```text
Addon metadata = truth for first paint
Routing = deterministic id + type + Fribb
Primary provider = one authority only
Resolvers = isolated modules
Artwork = separate cache + policy
```

---

If you want next step, I can turn this into:

👉 **Concrete Kotlin interfaces + class skeletons** (drop-in ready for your codebase)

