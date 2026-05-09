# Fanart.tv Artwork Intermediate Provider Design

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
| `FanartTvLookupShape` | `IntegrationApiShapes` entry. Wires the API call through `IntegrationRuntime` for backoff, single-flight, audit, and redaction. The DTO is consumed transiently by the candidate generator and never persisted. |
| `FanartTvImagePicker` | Pure function. Given the parsed document and `ArtworkType`, returns the chosen URL or null. Picks highest `likes`; requires `lang=en` for poster/logo; accepts any lang for backdrop; deterministic tie-break by ascending `id`. |
| `FanartTvCandidateGenerator` | The seam into the existing artwork pipeline. Bails on Anime, missing key, or missing id. Checks decision-cache freshness for each requested type; if any is stale, single-flights one lookup and writes 3 decisions (URL or null). Emits `ArtworkCandidate(sourceRole = INTERMEDIATE, provider = FanartTv)` for each non-null URL. |

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
                ├─ mediaKind == Anime?                         → emit nothing, exit
                ├─ Availability.Disabled?                      → emit nothing, exit
                ├─ FanartTvIdSelector returns null?            → emit nothing, exit
                ├─ For each requested type, look up ArtworkDecisionCache(provider=FanartTv, titleId, type):
                │     • fresh hit (URL or null) under 14d      → use it
                │     • stale or missing                       → mark "needs lookup"
                │
                ├─ Any "needs lookup"?
                │     YES ─► Single-flight one IntegrationRuntime call to fanarttv.lookup
                │             → parse DTO in-memory only (never persisted)
                │             → For each of {poster, logo, backdrop}:
                │                  picker(dto, type) → URL or null
                │                  write decision (URL or null) with 14d TTL
                │             → drop DTO
                │     NO  ─► skip the network call entirely
                │
                └─ Emit ArtworkCandidate for each non-null URL with sourceRole=INTERMEDIATE
        │
        ▼
ArtworkRouter.select(candidates, policy)
   PREMIUM > INTERMEDIATE > PRIMARY > CURRENT_PREVIEW > …
        │
        ▼
ArtworkAssetRepository fetches bytes for selected URL
   (existing pipeline; URL-derived bytes record key; 14d TTL via existing
    DurableArtworkAssetRecordStore)
        │
        ▼
Coil renders bytes
```

## Cache Contract

**Invariants (load-bearing):**

1. **No JSON persistence.** The DTO lives only in the candidate generator's local scope. The decision cache stores the *picked URL* (or `null`), not the document.
2. **Decision cache freshness short-circuits the API call.** A non-null URL still inside its 14d TTL → no fanart.tv hit, even if bytes have expired. Bytes refetch via the same URL.
3. **Null decisions are first-class.** "Fanart.tv had nothing for this type" is cached as a null decision with the same 14d TTL — prevents re-querying for 14d when fanart has no en logo / no entry at all.
4. **One call per title, not per type.** Coalesced via single-flight on `(provider=FanartTv, titleId)`. If poster, logo, and backdrop all need refresh in a burst, exactly one HTTP request goes out.
5. **Bytes layer is unchanged.** Existing `ArtworkAssetRepository` + disk cache + Coil pipeline already enforces "no network if bytes are valid" — Fanart.tv URLs flow through it like any other URL.

**Decision cache key:**

```
fanarttv:decision:{policyVersion}:{titleIdType}:{titleIdValue}:{artworkType}
```

**Bytes record key:** existing URL-derived hash, no special-casing.

**TTLs:** 14 days for both decisions and bytes. The existing `ArtworkDecisionCache` and `DurableArtworkAssetRecordStore` TTL machinery is reused.

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

| Condition | Outcome | Decision cache write? |
|---|---|---|
| `mediaKind == Anime` | Emit nothing | No |
| BuildConfig key blank | Emit nothing | No |
| No usable id (movie has no TMDB / TV has no TVDB) | Emit nothing | No |
| Decision cache fresh, URL non-null | Emit candidate from cache | No |
| Decision cache fresh, URL null | Emit nothing for this type | No |
| Decision cache stale → API call succeeds | Emit candidates for non-null URLs | Yes — 3 decisions written |
| API 404 (title not in fanart.tv) | Emit nothing | Yes — 3 null decisions, 14d TTL |
| API 401/403 (bad key) | Emit nothing | **No persistent decision** — transient `Disabled(auth_failure)` snapshot held in-memory for the runtime backoff window |
| API 429 / rate limit | Emit nothing for this resolution | No — `Retry-After` honored by `IntegrationRuntime`; next resolution after backoff retries |
| API network/5xx failure | Emit nothing for this resolution | No — keep last successful decision if any; existing `IntegrationRuntime` backoff applies |
| Picker finds entries but no `lang=en` (poster/logo) | Emit nothing for that type | Yes — null decision for that type, 14d TTL |

The 401/403 vs 404 split is intentional: 404 is a stable absence (no point re-querying for 14d); 401/403 is a recoverable misconfiguration (must not be locked in for 14d).

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

**`FanartTvCandidateGenerator` (with fakes for runtime + decision cache):**

- Anime input emits zero candidates and makes zero API calls.
- Missing BuildConfig key emits zero, zero API calls.
- Missing usable id emits zero, zero API calls.
- All 3 types stale → exactly one API call → 3 decisions written → candidates emitted for non-null URLs.
- All 3 types fresh non-null → zero API calls → 3 candidates emitted from cache.
- All 3 types fresh null → zero API calls → zero candidates emitted.
- Mixed fresh/stale → exactly one API call (single-flight coalesces).
- API 404 → 3 null decisions written with 14d TTL → zero candidates emitted.
- API 401/403 → no decisions written → zero candidates emitted (recovery on next resolution after backoff).
- Concurrent requests for the same title → exactly one API call (single-flight verified).
- DTO is not persisted (assert decision cache only contains URLs/nulls).

**`IntegrationApiShapes` registration:**

- `fanarttv.lookup` is registered with the redaction policy that strips `api_key` from URL/trace output.
- A trace fixture with the real key produces no trace string containing the key.

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
- Implement `FanartTvCandidateGenerator` with single-flight coalescing and decision-cache freshness checks.
- Wire the generator into the existing candidate-gathering site.
- Extend `ArtworkRouterTest` for INTERMEDIATE precedence and per-type mixing.
- Generator unit tests cover all rows of the failure/fallback table.

### Phase 5: Audit & verification

- Verify trace output never contains the raw key.
- End-to-end manual smoke: Default mode for a real movie (Fight Club) and real series (Breaking Bad) produces the picked URLs from the fixtures; anime title produces zero Fanart.tv calls.
