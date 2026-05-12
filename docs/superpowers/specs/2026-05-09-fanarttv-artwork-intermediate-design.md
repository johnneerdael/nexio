# Fanart.tv Artwork Intermediate Provider Design

> **SUPERSEDED 2026-05-12** by `2026-05-12-fanarttv-peer-selectable-provider-design.md`. The Nexio artwork architecture refactored away from router-rank candidate selection toward a per-type `ArtworkProviderResolver`, and the routing decision was changed from auto-injected intermediate to peer-selectable provider. Read the newer spec.

Date: 2026-05-09

## Purpose

Add Fanart.tv (API v3.2) as an intermediate artwork provider for movies and TV. Improves the `Default` mode for posters, logos, and backdrops without changing user-facing settings, without inventing parallel network paths, and without persistently caching the Fanart.tv JSON document.

This design extends the architecture defined in `2026-05-05-premium-artwork-provider-selection-design.md`, which explicitly anticipated Fanart.tv as an "intermediate provider" under Default-mode precedence.

## Goals

- Use Fanart.tv to source higher-quality posters/logos/backdrops than TMDB/TVDB primary metadata when an entry exists.
- Reuse the existing `IntegrationRuntime`, `ArtworkDecisionCache`, `ArtworkAssetRepository`, and Coil bytes pipeline. No parallel network paths.
- Single Fanart.tv API call per title satisfies all three artwork types in one shot via single-flight coalescing.
- Persist only **decisions** (chosen URL or null) and **bytes** — never the JSON document.
- Always pick the highest-`likes` image; require `lang=en` for posters and logos; accept any lang for backdrops (the API returns empty `lang` for them).
- Skip Fanart.tv entirely for anime.
- Silently fall through to TMDB/TVDB primary when Fanart.tv has nothing usable, without ever blanking artwork.

## Non-Goals

- No new entry in the Logo / Backdrop / Poster selectors. Fanart.tv is not a `RuntimeProvider` choice.
- No user-entered Fanart.tv API key. Key is a build secret in `local.properties`.
- No persistent caching of the Fanart.tv JSON body.
- No use of image types outside `{hdmovielogo, moviebackground, movieposter, hdtvlogo, showbackground, tvposter}`.
- No SD logo fallback (`movielogo` / `clearlogo`) inside the Fanart.tv response.
- No use of Fanart.tv for any explicit premium selection — premium selections always beat the intermediate.

## Existing Context

The premium-artwork spec already designed the routing extension surface. Fanart.tv slots into:

- `ArtworkProviderRegistry` — unchanged. Fanart.tv is not a `RuntimeProvider` choice.
- `ArtworkRouter` — gains one new `RoutingRank` between `PREMIUM` and `PRIMARY`.
- `ArtworkSourceRole` — gains `INTERMEDIATE`.
- `ArtworkDecisionCache` — used as-is for storing chosen URLs and null decisions per (provider, title, type).
- `ArtworkAssetRepository` + Coil pipeline — unchanged. Fanart.tv URLs flow through it like any other.
- `IntegrationApiShapes` + `IntegrationRuntime` — gains one shape `fanarttv.lookup` for the API call.
- `IntegrationProvider` enum — gains `FANART_TV`.

The candidate-gathering site that today emits TMDB/TVDB primary candidates and RPDB/Top Posters premium candidates also invokes the new `FanartTvCandidateGenerator`.

## Architecture

Fanart.tv is an **intermediate provider role** — a new tier between explicit premium selections and primary metadata. It activates only when all four conditions hold:

1. `BuildConfig.FANARTTV_API_KEY` is non-blank.
2. The user's selection for the relevant artwork type is `Default` (not RPDB, Top Posters, or any future premium).
3. `mediaKind != Anime`.
4. A usable id exists for the call: TMDB for movies, TVDB for TV.

Updated `ArtworkRouter.RoutingRank`:

```
PREMIUM (0)         unchanged: explicit RPDB / Top Posters
INTERMEDIATE (1)    new: Fanart.tv (Default mode only)
PRIMARY (2)         was 1: TMDB / TVDB
CURRENT_PREVIEW (3) was 2
OTHER_PREVIEW (4)   was 3
FALLBACK (5)        was 4
PLACEHOLDER (6)     was 5
```

A premium-selected type bypasses INTERMEDIATE because the Fanart.tv generator sees a non-`Default` selection for that type and emits nothing. Per-type mixing is supported: a user with RPDB selected for posters and Default for logos and backdrops will see RPDB posters and Fanart.tv logos/backdrops.

## Components

All new files under `app/src/main/java/com/nexio/tv/core/artwork/fanarttv/`.

| Unit | Purpose |
|------|---------|
| `FanartTvAvailability` | Returns `Available(apiKey)` when `BuildConfig.FANARTTV_API_KEY` is non-blank, else `Disabled(reason)`. |
| `FanartTvIdSelector` | Given `(mediaKind, ProviderIds)`, returns the call id: Movie → tmdb, Series → tvdb, anything else → null. |
| `FanartTvApi` | Retrofit interface with two endpoints: `GET /v3.2/movies/{tmdbId}` and `GET /v3.2/tv/{tvdbId}`. Returns the full document DTO. |
| `FanartTvLookupShape` | Declares the `IntegrationApiShapes` entry. Wires the API call through `IntegrationRuntime` with `IntegrationCachePolicy.CacheFirst(ttlMs = 14d)`. The runtime persists the JSON response body in `integration_cache` and serves cache hits without a network call. Declares `api_key` as a redacted query parameter. |
| `FanartTvLookup` (interface) + `RuntimeFanartTvLookup` (impl) | Thin adapter that calls `FanartTvLookupShape` and maps `HttpException` to a typed result (`Success` / `NotFound` / `AuthFailed` / `Transient`). The generator depends on the interface so tests can fake it without going through the runtime. |
| `FanartTvImagePicker` | Pure function. Given the parsed document and `(callType, ArtworkType)`, returns the chosen URL or null. Picks highest `likes`; requires `lang=en` for poster/logo; accepts any lang for backdrop; deterministic tie-break by ascending `id`. |
| `FanartTvCandidateGenerator` | The seam into the existing artwork pipeline. Bails on Anime, missing key, or missing id. Calls `FanartTvLookup.fetch(...)` (runtime cache short-circuits), runs the picker for each artwork type, and emits `ArtworkCandidate(sourceRole = INTERMEDIATE, provider = FanartTv)` for each non-null URL. No coalescing, no decision-store interaction. |

One-line additions to existing files:

- `ArtworkSourceRole`: add `INTERMEDIATE`.
- `ArtworkRouter.RoutingRank`: insert `INTERMEDIATE(1)` and shift others (see Architecture).
- `IntegrationProvider`: add `FANART_TV`.
- `IntegrationApiShapes.kt`: register `fanarttv.lookup`.

## Data Flow

```
Caller asks ArtworkAssetRepository for (title, type) bytes
        │
        ▼
Existing candidate-gathering site
        ├─► Existing primary generator (TMDB/TVDB)            → PRIMARY candidates
        ├─► Existing premium generators (RPDB/Top Posters)    → PREMIUM candidates (only if user-selected)
        └─► FanartTvCandidateGenerator
                │
                ├─ mediaKind == Anime?               → emit nothing, exit
                ├─ Availability.Disabled?            → emit nothing, exit
                ├─ FanartTvIdSelector returns null?  → emit nothing, exit
                │
                ├─ Call IntegrationRuntime via fanarttv.lookup shape
                │   • Shape policy: CacheFirst(ttlMs = 14d)
                │   • Runtime serves from integration_cache disk blob if fresh,
                │     else makes one network call and caches the response
                │   • Concurrent identical specs are coalesced by IntegrationSingleFlight
                │
                ├─ Pick URLs (in-memory, transient):
                │     picker.pickFor(dto, callType, POSTER) → URL or null
                │     picker.pickFor(dto, callType, LOGO)   → URL or null
                │     picker.pickFor(dto, callType, BACKDROP) → URL or null
                │
                └─ Emit ArtworkCandidate for each non-null URL with sourceRole=INTERMEDIATE
                  (No special per-type write; routing decision is recorded by the
                   downstream MetadataArtworkDecisionResolver in ArtworkDecisionCache.)
        │
        ▼
ArtworkRouter.select(candidates, policy)
   PREMIUM > INTERMEDIATE > PRIMARY > CURRENT_PREVIEW > …
        │
        ▼
MetadataArtworkDecisionResolver writes ArtworkDecision (existing flow)
        │
        ▼
ArtworkAssetRepository fetches bytes for selected URL
   (existing pipeline; URL-derived bytes record key; 14d TTL via existing
    DurableArtworkAssetRecordStore)
        │
        ▼
Coil renders bytes
```

## Cache Layers

This feature reuses the standard artwork chain and adds **no new persistence layer**. Three existing caches participate:

| Layer | What it stores | TTL | Where |
|---|---|---|---|
| Runtime response cache | The Fanart.tv JSON body for one (mediaKind, id) | 14d | `integration_cache` Room table (blob on disk), keyed by `IntegrationCallSpec` |
| Routing decision cache | The chosen URL per (owner, artwork type) after routing | per existing `ArtworkDecisionPolicy` | `ArtworkDecisionCache` (existing) |
| Asset bytes cache | The fetched image bytes | per existing `DurableArtworkAssetRecordStore` policy | Disk file + record store |

**Key invariants:**

1. **No invented store.** The Fanart.tv path uses only the three existing caches above. There is no `FanartTvDecisionStore`, no parallel Room entity, no in-memory DTO cache.
2. **JSON is cached as a runtime response body.** Like every other provider that goes through `IntegrationRuntime` (TMDB, TVDB, Trakt, RPDB, Top Posters), the response body is persisted in `integration_cache` as a disk blob. The 14d TTL is enforced by `IntegrationCachePolicy.CacheFirst(ttlMs = 14L * 24 * 3600 * 1000)` declared on `FanartTvLookupShape`.
3. **Per-type URL pinning is the standard `ArtworkDecisionCache`.** When the router picks the Fanart-emitted candidate, `MetadataArtworkDecisionResolver` writes a routing decision exactly like it does for TMDB/TVDB/RPDB/Top Posters today. Fanart.tv is not special.
4. **Bytes layer is unchanged.** Fanart URLs flow through `ArtworkAssetRepository` like any other URL.
5. **Single-flight is runtime-provided.** Concurrent `MetadataArtworkDecisionResolver.resolveFields` calls for the same title produce one Fanart.tv HTTP call via `IntegrationSingleFlight`. The candidate generator does no coalescing of its own.

**Negative caching.** A 404 ("title not in fanart.tv") is short-lived: the runtime backoff system (`IntegrationProviderBackoffEntity`) throttles repeated 4xx responses; in addition, Fanart.tv decisions get re-queried on the existing `ArtworkDecisionCache` TTL boundary, not on every resolver call. Stable absences naturally settle into a cheap "decision says PRIMARY won → no Fanart re-query until decision expires" steady state.

## Image Selection Rules

Image types consumed:

| Media | Type | Fanart.tv array | Lang requirement |
|---|---|---|---|
| Movie | Logo | `hdmovielogo` | `lang == "en"` |
| Movie | Backdrop | `moviebackground` | none (entries have empty lang) |
| Movie | Poster | `movieposter` | `lang == "en"` |
| TV | Logo | `hdtvlogo` | `lang == "en"` |
| TV | Backdrop | `showbackground` | none (entries have empty lang) |
| TV | Poster | `tvposter` | `lang == "en"` |

Image types **not** consumed: `movielogo`, `clearlogo`, `clearart`, `hdclearart`, `hdmovieclearart`, `characterart`, `moviebanner`, `tvbanner`, `moviedisc`, `moviethumb`, `tvthumb`, `seasonbanner`, `seasonposter`, `seasonthumb`.

Picker algorithm (pure function):

```
1. Take the array for the (media, type).
2. Filter to entries where lang == "en" (skip filter for backdrops).
3. Sort by likes (numeric, descending).
4. Tie-break by id (ascending) for determinism.
5. Return the first entry's url, or null if empty.
```

Two reference fixtures (Fight Club / Breaking Bad) provided by the user must be encoded as committed test fixtures and produce the exact URL outputs:

- Fight Club logo: `hdmovielogo` id `12657` (en, 8 likes).
- Fight Club backdrop: `moviebackground` id `119633` (no lang, 5 likes).
- Fight Club poster: `movieposter` id `50065` (en, 15 likes).
- Breaking Bad logo: `hdtvlogo` id `20282` (en, 24 likes).
- Breaking Bad backdrop: `showbackground` id `18563` (no lang, 12 likes).
- Breaking Bad poster: `tvposter` id `45072` (en, 14 likes).

## Settings UI & Migration

**No UI changes.** `ArtworkProviderRegistry.availableChoices()` continues to return `[Default, RPDB?, TopPosters?]` based on user-entered keys. No new entry in `ArtworkProviderChoiceKey` or `ArtworkProviderDescriptor`.

**No settings migration.** `ArtworkProviderSettings` blobs deserialize unchanged.

**Build wiring** (the only "settings"-adjacent change):

```kotlin
// app/build.gradle.kts
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
android {
    defaultConfig {
        buildConfigField(
            "String",
            "FANARTTV_API_KEY",
            "\"${localProps.getProperty("fanarttv.api.key", "")}\""
        )
    }
}
```

`local.properties` (developer/CI machine, gitignored):

```
fanarttv.api.key=07882f4309da827df559bb85b63793f9
```

`FanartTvAvailability` reads `BuildConfig.FANARTTV_API_KEY`. Empty string → `Disabled(reason = "no_build_config_key")`. Builds without the key compile and run; the Fanart.tv intermediate just never activates.

## Audit & Redaction

The API key is a build secret but flows in request URLs as a query parameter (`?api_key=...`). The `IntegrationRuntime` audit/trace path must redact the `api_key` query parameter before any log/audit/trace write. `FanartTvLookupShape` declares this redaction policy at the shape level so the runtime applies it automatically — same approach the existing premium spec applies to RPDB/Top Posters keys.

Trace fields the candidate generator writes for each emit/skip:

```
provider=fanart_tv
artwork_type={poster|logo|backdrop}
mediaKind={Movie|Series|Anime|...}
id_type={tmdb|tvdb|none}
decision_source={cache_fresh|cache_stale_refreshed|cache_null|generator_skipped}
skip_reason={anime|no_build_key|no_id|null_decision|api_4xx|api_5xx|rate_limited|...}
```

`api_key` and any URL containing it must never appear in trace output.

## Error Handling & Fallback

Generator-level outcomes (each is a clean exit; never blanks artwork):

| Condition | Generator outcome | Runtime cache effect |
|---|---|---|
| `mediaKind == Anime` | Emit nothing — exit before runtime call | No call made |
| BuildConfig key blank | Emit nothing — exit before runtime call | No call made |
| No usable id (movie has no TMDB / TV has no TVDB) | Emit nothing — exit before runtime call | No call made |
| Runtime serves cached JSON | Pick URLs and emit candidates for non-null URLs | Hit (no network) |
| Runtime makes network call → 200 | Pick URLs and emit candidates for non-null URLs | Body cached for 14d |
| Runtime makes network call → 404 | Emit nothing | Provider backoff applies; `ArtworkDecisionCache` TTL governs re-query cadence |
| Runtime makes network call → 401/403 | Emit nothing | Provider backoff applies; key fix recovers immediately on next resolver pass |
| Runtime makes network call → 429 / 5xx / network error | Emit nothing for this resolution | `Retry-After` honored by `IntegrationRuntime`; backoff applies |
| Picker finds entries but no `lang=en` (poster/logo) | Emit nothing for that type | None — JSON is cached, picker just returned null for that type |

Re-query cadence in the steady state is governed by the longer of: (a) the runtime cache TTL on the JSON body (14d), and (b) the `ArtworkDecisionCache` TTL on the routing decision. Stable absences (404, no-en-variant) settle into "decision says PRIMARY → no Fanart re-query until decision expires" without any Fanart-specific negative-cache machinery.

Router-level fallback (existing behavior, unchanged):

- Fanart.tv candidate present + Default selection → INTERMEDIATE wins over PRIMARY → Fanart.tv URL is selected → bytes fetched.
- Fanart.tv candidate absent (any reason above) → PRIMARY wins → TMDB/TVDB image is selected.
- Fanart.tv URL fetch returns 4xx/5xx at the bytes layer → existing `ArtworkAssetRepository` failure path → router re-runs without the failed candidate → falls through to PRIMARY for this resolution. The stale decision is naturally overwritten on its next refresh (within ≤14d). No Fanart.tv-specific recovery code.

## Test Plan

**`FanartTvImagePicker` (pure function, table-driven):**

- Picks `hdmovielogo` with highest `likes` and `lang=en`; ignores higher-liked non-en entries.
- Picks `hdtvlogo` with highest `likes` and `lang=en`.
- Picks `moviebackground` / `showbackground` with highest `likes` regardless of lang.
- Picks `movieposter` / `tvposter` with highest `likes` and `lang=en`.
- Returns null when no `lang=en` entry exists for poster/logo.
- Returns null when the array is missing or empty.
- Tie-break by ascending `id` is deterministic.
- Two real fixtures (Fight Club / Breaking Bad) verify exact URL outputs match the documented "Chosen" picks above.

**`FanartTvAvailability`:**

- `Available` when BuildConfig key is non-blank.
- `Disabled(no_build_config_key)` when blank.

**`FanartTvIdSelector`:**

- Movie + TMDB id present → returns `(tmdb, value)`.
- Movie + no TMDB id → returns null.
- Series + TVDB id present → returns `(tvdb, value)`.
- Series + no TVDB id → returns null.
- Anime → returns null.

**`FanartTvCandidateGenerator` (with a fake `FanartTvLookup`):**

- Anime input emits zero candidates and makes zero `lookup.fetch` calls.
- Missing BuildConfig key emits zero, zero `lookup.fetch` calls.
- Missing usable id emits zero, zero `lookup.fetch` calls.
- Lookup returns a populated document → emits candidates for each non-null picker output.
- Lookup returns an empty document → emits zero candidates.
- Lookup returns 404 / auth-failed / transient → emits zero candidates.
- Per-call deterministic: same input + same `FanartTvLookup` answer produces the same candidate list.

**`FanartTvLookupShape`:**

- Declares `IntegrationCachePolicy.CacheFirst(ttlMs = 14L * 24 * 3600 * 1000)`.
- Declares `IntegrationProvider.FANART_TV` and `FanartTvApiShapes.LOOKUP` shape id.
- Declares `api_key` as a redacted query parameter so any URL traced/audited replaces its value.
- A trace fixture exercising the spec with the real key produces no trace string containing the raw key.

**Router (extends existing `ArtworkRouterTest`):**

- INTERMEDIATE candidate present + Default selection beats PRIMARY for the same type.
- PREMIUM (RPDB / Top Posters) selection beats INTERMEDIATE for that type.
- INTERMEDIATE absent → PRIMARY wins (no behavior change).
- Per-type mix: RPDB selected for posters + Default for logos/backdrops → RPDB poster + Fanart.tv logo + Fanart.tv backdrop.

**Bytes-layer "stale URL":**

- Fresh decision pointing to a 404-returning URL → bytes fetch fails → candidate marked rejected at the asset layer → router falls through to PRIMARY for this resolution.

**Audit:**

- Trace records for emit / skip / refresh paths contain the documented fields and never the raw key.

No end-to-end network test against real fanart.tv. The two captured Fight Club / Breaking Bad responses become committed JSON fixtures under `app/src/test/resources/fixtures/fanarttv/` and drive both picker and generator tests.

## Implementation Phases

### Phase 1: Build wiring & DTO

- Add `local.properties` parsing and `BuildConfig.FANARTTV_API_KEY` field.
- Add `FanartTvApi` Retrofit interface and DTO classes covering the six consumed image arrays.
- Commit Fight Club / Breaking Bad JSON fixtures under `app/src/test/resources/fixtures/fanarttv/`.

### Phase 2: Picker, availability, id selector

- `FanartTvAvailability`, `FanartTvIdSelector`, `FanartTvImagePicker`.
- Unit tests (all pure / fake-driven).

### Phase 3: Runtime shape

- Add `IntegrationProvider.FANART_TV`.
- Register `fanarttv.lookup` in `IntegrationApiShapes` with `api_key` redaction policy.
- `FanartTvLookupShape` integration test verifies redaction.

### Phase 4: Candidate generator + router rank

- Add `ArtworkSourceRole.INTERMEDIATE` and `RoutingRank.INTERMEDIATE(1)` (shifts other ranks by +1).
- Implement `FanartTvCandidateGenerator`: gates → call `FanartTvLookup` → run picker → emit candidates. No coalescer, no decision-store interaction.
- Wire the generator into `MetadataArtworkDecisionResolver.resolveFields` so its candidates join the existing primary/premium candidates before routing.
- Extend `ArtworkRouterTest` for INTERMEDIATE precedence and per-type mixing.
- Generator unit tests cover all rows of the failure/fallback table.

### Phase 5: Audit & verification

- Verify trace output never contains the raw key.
- End-to-end manual smoke: Default mode for a real movie (Fight Club) and real series (Breaking Bad) produces the picked URLs from the fixtures; anime title produces zero Fanart.tv calls.
