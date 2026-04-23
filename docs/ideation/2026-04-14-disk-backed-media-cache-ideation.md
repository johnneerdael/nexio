---
date: 2026-04-14
topic: disk-backed-media-cache
focus: Poster and media image caching for TMDB, RPDB, TOP Posters, and other collected images
---

# Ideation: Disk-Backed Media Cache

## Codebase Context

NEXIO is an Android TV / Fire TV app built with Kotlin and Jetpack Compose. The relevant cache path is split across metadata, home snapshots, and Coil image loading:

- `NexioApplication` configures Coil with a memory cache and a 200 MB disk cache at `cacheDir/image_cache`.
- `MetadataDiskCacheStore` stores metadata in SharedPreferences under keys like `meta::<item>::<language>::<provider>` and `tmdb::<tmdbId:type>::<language>::<provider>`.
- `MetaRepositoryImpl`, `TmdbMetadataService`, and `HomeCatalogSnapshotStore` all derive provider tokens from the active poster provider. Enabling RPDB or TOP Posters creates a different logical metadata/snapshot cache namespace.
- `PosterRatingsUrlResolver` rewrites poster URLs to RPDB or TOP Posters URLs when a provider is active. TOP Posters also includes `fallback_url` when a source poster exists.
- Existing tests already protect startup retention of `image_cache` files, and TMDB enrichment tests assert that TMDB posters are hidden when a poster-ratings provider is active.

The likely failure shape is not total absence of caching. It is that metadata/provider identity changes cause fresh provider URLs to be emitted, and Coil's disk cache is URL-keyed rather than item-identity-keyed. That makes the system poor at answering "do we already have a valid poster for this title on disk?" before deciding to ask RPDB or TOP Posters again.

No recent `docs/ideation/` or `docs/solutions/` artifacts existed for this topic.

## Ranked Ideas

### 1. Canonical Disk Media Asset Registry

**Description:** Add a small disk-backed registry for media assets keyed by stable identity: `service:id:assetType:provider`. Example keys: `tmdb:550:poster:top_posters`, `tmdb:550:poster:rpdb`, `tmdb:550:backdrop:tmdb`, `tmdb:550:logo:tmdb`, `tmdb:100:episode_thumbnail:tmdb:s1e2`. Each entry stores source URL, resolved local disk reference or Coil disk key, provider token, content type, dimensions/hash when available, `updatedAtMs`, `lastVerifiedAtMs`, and validity status.

**Rationale:** This directly answers the missing question: "is there a valid disk poster for this ID and provider?" It also generalizes beyond posters to all collected images, which avoids repeating this bug for backdrops, logos, thumbnails, cast photos, and profile avatars.

**Downsides:** Medium implementation surface because Coil's disk cache is not currently exposed through a repository-level abstraction. Needs careful invalidation so stale/broken images do not become permanent.

**Confidence:** 93%

**Complexity:** Medium

**Status:** Unexplored

### 2. One-Time Poster Provider Override Migration

**Description:** When RPDB or TOP Posters becomes active, perform a one-time metadata override from native poster metadata to provider-specific poster metadata only if disk verification succeeds. The migration should read existing metadata for the same item/language under `native`, derive the provider URL, check disk presence for the provider poster, and write the provider cache entry without re-fetching network metadata. The requested TTL is 10 days for provider poster validity.

**Rationale:** This is the most direct answer to the observed 25,000 poster consumption problem. It preserves the existing provider-token split while avoiding repeated downloads when the poster has already landed on disk.

**Downsides:** It still depends on reliable disk verification and may need a small bridge between metadata cache entries and Coil's disk cache. It also needs provider-specific behavior because RPDB prefers IMDb IDs while TOP Posters supports TMDB and others.

**Confidence:** 90%

**Complexity:** Medium

**Status:** Unexplored

### 3. Disk-Only Validity Gate Before Provider URL Emission

**Description:** Before `PosterRatingsUrlResolver` emits an RPDB or TOP Posters URL for an item, consult a disk-only asset validity gate. Memory cache hits do not count. If a valid disk asset exists and is younger than the 10-day TTL, return the stable local/cache-backed route. If missing or expired, emit the provider URL and record the reason.

**Rationale:** The user explicitly called out disk cache, not memory cache. Putting the gate before URL emission prevents UI scroll/recomposition, home snapshot hydration, and addon metadata fetches from blindly producing external provider URLs.

**Downsides:** The resolver is currently pure URL construction plus settings lookup. Adding disk state directly would make it too stateful unless the gate is placed in a new service and the resolver remains simple.

**Confidence:** 88%

**Complexity:** Medium

**Status:** Unexplored

### 4. Provider-Aware Cache Key Contract

**Description:** Replace ad hoc provider token strings like `PROVIDER:apiKey.hashCode()` with a shared cache-key contract object that can render human-readable metadata keys and disk asset keys. Keys should include service, ID, asset type, provider, content type, language where relevant, and provider account scope without leaking API keys.

**Rationale:** Current keys are functional but not very inspectable. A labelled `id-service-type-provider` contract makes cached media easy to find, test, migrate, and debug.

**Downsides:** Mostly structural. It will touch multiple cache call sites and requires compatibility handling for existing cache entries.

**Confidence:** 86%

**Complexity:** Low-Medium

**Status:** Unexplored

### 5. Media Cache TTL Policy Matrix

**Description:** Define explicit TTL policy per asset family: 10 days for rating-overlay posters, longer or immutable TTL for provider URLs documented as immutable, 12 hours for existing TMDB video/trailer cache, and a separate policy for avatars/profile-controlled images. Use stale-while-revalidate when an existing disk asset is present.

**Rationale:** The repository already has a 12-hour TTL for TMDB videos, but metadata images currently lack an explicit freshness model. A policy matrix prevents accidental "cache forever" and accidental "redownload constantly" behavior.

**Downsides:** A policy document alone is not enough; it needs code enforcement through the asset registry/gate.

**Confidence:** 84%

**Complexity:** Low

**Status:** Unexplored

### 6. Poster Fetch Observability And Quota Guardrails

**Description:** Add structured logging or counters for poster decisions: disk hit, disk miss, expired, memory-only ignored, provider URL emitted, network fetch completed, and provider request suppressed. Surface aggregate counts in debug diagnostics so a runaway provider can be spotted before a monthly dashboard shows 25,000 consumed posters.

**Rationale:** The current symptom was discovered externally in a provider dashboard. NEXIO should be able to explain why it asked for a poster and whether the disk cache did its job.

**Downsides:** Does not fix the bug by itself. It must follow the cache gate or it risks becoming noise.

**Confidence:** 82%

**Complexity:** Low-Medium

**Status:** Unexplored

### 7. Bounded Missing-Asset Hydration Queue

**Description:** When a disk asset is genuinely missing, queue a bounded background hydration job keyed by media asset identity. The queue dedupes in-flight work, rate-limits provider fetches, and favors visible/focused items over bulk list hydration.

**Rationale:** This prevents a catalog refresh or home rebuild from stampeding provider poster APIs. It also gives the app a single place to populate missing media once and then serve from disk.

**Downsides:** Higher coordination cost and not required for the first fix. It is best as a second-stage improvement once the identity registry exists.

**Confidence:** 76%

**Complexity:** Medium-High

**Status:** Unexplored

## Rejection Summary

| # | Idea | Reason Rejected |
|---|------|-----------------|
| 1 | Increase Coil disk cache size | Does not solve repeated provider URL generation or missing item-identity lookup. |
| 2 | Rely on memory cache | Explicitly violates the disk-only requirement and fails across cold starts. |
| 3 | Disable RPDB/TOP Posters automatically | Avoids the symptom by removing the feature instead of fixing cache correctness. |
| 4 | Cache provider URLs forever | Ratings overlays need occasional refresh, and broken image responses would become sticky. |
| 5 | Build a local image proxy first | Too much infrastructure before proving the simpler registry/gate works. |
| 6 | Move all metadata cache to Room immediately | Potentially valuable later, but too large for the specific poster consumption failure. |
| 7 | Just remove `fallback_url` from TOP Posters URLs | May reduce URL variance, but does not establish disk validity or media identity. |
| 8 | Eagerly prefetch every catalog poster | Risks making provider consumption worse without a deduped disk gate. |
| 9 | Treat TMDB and TOP Posters posters as interchangeable | Incorrect because provider overlays are different assets with different freshness expectations. |
| 10 | Use HTTP ETags only | Helpful when available, but provider APIs may still count requests and it does not avoid URL emission. |
| 11 | Keep only current home posters in cache | Conflicts with startup snapshots, Continue Watching, and browse-back behavior. |
| 12 | Add more home snapshot retention | The snapshot is downstream of poster URL selection, so retention alone does not prevent provider churn. |

## Session Log

- 2026-04-14: Initial ideation: 19 candidates generated, 7 survived. Focused on disk-valid media identity, provider override migration, 10-day poster TTL, and diagnostics for poster API consumption.
