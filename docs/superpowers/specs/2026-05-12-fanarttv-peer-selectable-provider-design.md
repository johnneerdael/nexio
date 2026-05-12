# Fanart.tv as Peer-Selectable Artwork Provider Design

Date: 2026-05-12

> **Supersedes** `docs/superpowers/specs/2026-05-09-fanarttv-artwork-intermediate-design.md`. The 2026-05-09 spec assumed Fanart.tv would auto-inject under Default mode (an INTERMEDIATE routing rank). The Nexio artwork architecture has since refactored away from router-rank candidate selection toward a per-type `ArtworkProviderResolver` that picks one provider per type given (settings, content type, isAnime, ids). The architecture also added a "durable non-downgrade" surface layer (`preferredArtworkProviders` + `preferredAwareSlot`) and an automatic `ArtworkSettingsInvalidator`. This spec rebuilds against the current architecture and reflects the user's revised decision: Fanart.tv is a peer-selectable provider in settings, not an auto-injected intermediate.

## Purpose

Add Fanart.tv (API v3.2) as a fourth `ArtworkProviderChoiceKey` peer to `DEFAULT`, `RPDB`, and `TOP_POSTERS`. When a user explicitly selects Fanart.tv for poster, logo, or backdrop, the resolver returns the Fanart.tv provider id and a new `FanartTvArtworkResolver` performs a list-and-pick API call to produce the URL. Anime titles never use Fanart.tv (capability rejection → fall through to `ContentTypeDefaults`); non-anime items where Fanart.tv cannot deliver leave the slot empty, strictly honoring the explicit choice.

## Goals

- Fanart.tv appears as a selectable choice in the per-type artwork provider selectors when the build was compiled with a Fanart.tv API key.
- Highest-`likes` selection rule, requires `lang=en` for poster and logo, accepts any lang for backdrop.
- Reuses the standard artwork chain: `IntegrationRuntime` `CacheFirst(14d)` for the JSON body, existing asset disk cache for bytes, existing surface repository for non-downgrade.
- No new persistence layer.
- No new UI surface — the existing `PosterRatingsSettingsScreen` per-type selectors auto-pick up the new choice via `ArtworkProviderRegistry`.
- Toggling the Fanart.tv selection invalidates and re-hydrates affected items automatically via the existing `ArtworkSettingsInvalidator`.

## Non-Goals

- No swap of `ContentTypeDefaults.resolve` to use Fanart.tv as a non-anime default. The pre-engineered comment slot in `ContentTypeDefaults.kt` is preserved for a future one-line follow-up.
- No new dropdown entry beyond Fanart.tv itself. No "Fanart.tv with addon fallback" or other compound options.
- No user-entered Fanart.tv API key. Key is a build secret in `local.properties`.
- No use of image types outside `{hdmovielogo, moviebackground, movieposter, hdtvlogo, showbackground, tvposter}`.
- No SD logo fallback (`movielogo` / `clearlogo`) inside the Fanart.tv response.
- No router-rank changes. No new `ArtworkSourceRole` value.
- No support for thumbnails. Fanart.tv has no per-episode artwork.
- No user-toggle to control the anime-fallback behavior. Anime always falls through to `ContentTypeDefaults`.

## Existing Architecture (Source of Truth)

These existing units are reused unchanged or extended surgically:

- `ArtworkProviderResolver.resolve(type, contentType, isAnime, ids, settings) → ArtworkProviderId`. Picks the user's explicit choice if capability check passes; else falls through to `ContentTypeDefaults.resolve(type, isAnime)`. Emits a `home.artwork_resolver.decision` trace event with `chosenProvider`, `fellThroughTo`, and `capabilitySupported` fields.
- `ArtworkProviderCapabilityResolver.evaluate(...)` — per-provider rejection logic. Returns a typed `ArtworkProviderCapability(supported, reason?)`.
- `ArtworkProviderRegistry.availableChoices(type, settings)` — drives the settings dialog choices. Returns `[DEFAULT, ...descriptors that match]`.
- `ArtworkProviderChoiceKey` — value class holding the persisted string keys. Currently `DEFAULT`, `RPDB`, `TOP_POSTERS`.
- `ArtworkProviderSettings.toSettingsSignature()` — produces a stable signature of the per-type selections used for invalidation.
- `ArtworkSettingsInvalidator` — observes the signature; on change, calls `HydratedHomeOverlayStore.markStaleAll(reason="settings_change")`. App-scoped, started from `NexioApplication.onCreate`.
- `ResolvedDisplaySurfaceRepository.preferredAwareSlot(incoming, existing, preferred, ...)` — surface-level non-downgrade. When `preferredArtworkProviders[type]` is set, only slots from the preferred provider can fill that slot.
- `HomeResolvedDisplayMapper` — computes `preferredArtworkProviders` per item and stamps it on `ResolvedDisplayItem`.
- `PosterRatingsUrlResolver` — current URL resolver for RPDB and Top Posters (templated URLs). Stays focused on its current scope.
- `IntegrationRuntime` + `IntegrationCachePolicy.CacheFirst` — standard request/cache machinery used by every provider; persists response bodies in `integration_cache` (`IntegrationCacheEntity.blobPath`).
- `IntegrationSingleFlight` — coalesces concurrent identical specs.
- `IntegrationProviderBackoffEntity` + provider-level backoff — handles 429 / 5xx / network errors uniformly.

## Architecture

Fanart.tv slots into the existing chain at three points: a new `ArtworkProviderChoiceKey`, a new descriptor in the registry, and a new dedicated URL resolver injected at the same consumer sites that already consume `PosterRatingsUrlResolver`.

```
PosterRatingsSettingsScreen
        │
        ▼
ArtworkProviderRegistry.availableChoices(type, settings)
        │
        ▼
[DEFAULT, RPDB?, TOP_POSTERS?, FANART_TV?]   ← Fanart.tv listed when BuildConfig key non-blank
        │
        ▼
User picks FANART_TV for (e.g.) POSTER
        │
        ▼
ArtworkProviderSettings flows → toSettingsSignature() changes
        │
        ▼
ArtworkSettingsInvalidator → HydratedHomeOverlayStore.markStaleAll
        │
        ▼
Home pipeline re-hydrates each item
        │
        ▼
For each item:
   ArtworkProviderResolver.resolve(POSTER, contentType, isAnime, ids, settings)
        │
        ├─ explicit = FANART_TV
        ├─ ArtworkProviderCapabilityResolver.evaluate:
        │     ├─ ANIME?                         → reject "anime_unsupported_for_fanart_tv"
        │     ├─ no TMDB(movie)/TVDB(series)?   → reject "missing_supported_provider_id"
        │     ├─ BuildConfig key blank?         → reject "fanart_tv_not_configured"
        │     └─ else                           → supported
        │
        └─ returns FANART_TV (or ContentTypeDefaults fallback)

Consumer (TmdbMetadataService / TvdbMetadataService / HomeCatalogRefreshCoordinator):
   if providerId == FANART_TV:
       FanartTvArtworkResolver.fetchUrl(callId, type) → URL or null
       on null AND not anime → empty slot (no further fallback)
   else:
       existing PosterRatingsUrlResolver path
        │
        ▼
preferredArtworkProviders[type] = FANART_TV stamped on ResolvedDisplayItem
        │
        ▼
ResolvedDisplaySurfaceRepository.preferredAwareSlot enforces non-downgrade
        │
        ▼
Coil renders the URL via the existing ArtworkAssetRepository disk pipeline
```

The `ContentTypeDefaults` table is unchanged. The pre-engineered comment slot (`// ↑ fanart.tv lands → else fanartProvider`) stays in place as the marker for the deferred follow-up.

## Components

All new files under two packages, mirroring existing patterns.

### New files

| File | Purpose |
|------|---------|
| `core/artwork/fanarttv/FanartTvAvailability.kt` | Sealed type. `Available(apiKey)` when `BuildConfig.FANARTTV_API_KEY` is non-blank, else `Disabled(reason)`. |
| `core/artwork/fanarttv/FanartTvIdSelector.kt` | `(mediaKind, ProviderIds) → FanartTvCallId?`. Movie → tmdb, Series → tvdb, anything else (including anime) → null. |
| `core/artwork/fanarttv/FanartTvImagePicker.kt` | Pure function. `(FanartTvDocument, FanartTvCallId.Type, ArtworkType) → URL?`. Highest-`likes`; `lang=en` for poster/logo; any lang for backdrop; deterministic tie-break by ascending id. THUMBNAIL → null. |
| `core/artwork/fanarttv/FanartTvArtworkResolver.kt` | The new resolver. Composes availability + id selector + lookup + picker → URL or null. |
| `core/artwork/fanarttv/FanartTvApiShapes.kt` | `const LOOKUP = "fanarttv.lookup"`. |
| `core/artwork/fanarttv/dto/FanartTvDocument.kt` | `@Serializable` DTO with the six consumed image arrays. |
| `core/artwork/fanarttv/dto/FanartTvImage.kt` | `@Serializable` single-image DTO (id, url, lang, likes). |
| `core/artwork/fanarttv/FanartTvLookup.kt` | Interface + `FanartTvLookupResult` sealed (`Success(doc) / NotFound / AuthFailed / Transient`). |
| `data/integration/fanarttv/FanartTvApi.kt` | Retrofit interface: `GET /v3.2/movies/{tmdbId}` and `GET /v3.2/tv/{tvdbId}` with `?api_key=`. |
| `data/integration/fanarttv/FanartTvApiModule.kt` | Hilt module: provides `FanartTvApi` (Retrofit + base URL `https://webservice.fanart.tv/`); binds `FanartTvLookup` to `RuntimeFanartTvLookup`. |
| `data/integration/fanarttv/FanartTvLookupShape.kt` | `IntegrationCallSpec` builder with `IntegrationCachePolicy.CacheFirst(14d)` and `api_key` declared as a redacted query parameter. |
| `data/integration/fanarttv/RuntimeFanartTvLookup.kt` | `FanartTvLookup` impl. Calls `FanartTvLookupShape.fetch(...)` and maps `HttpException` to typed result. |

### Modified files

| File | Change |
|------|--------|
| `app/build.gradle.kts` | Add `FANARTTV_API_KEY` BuildConfig field from `local.properties`. |
| `core/integration/IntegrationProvider.kt` | Add `FANART_TV` enum value. |
| `domain/model/ArtworkProviderSettings.kt` | Add `ArtworkProviderChoiceKey.FANART_TV` constant; extend `fromStored()`; extend `toRuntimeProviderId()`. |
| `core/artwork/ArtworkProviderRegistry.kt` | Add `fanartTvDescriptor` to `artworkProviderDescriptors` list. |
| `core/artwork/ArtworkProviderCapabilityResolver.kt` | Add `descriptor()` branch for FANART_TV; add `fanartTvRejectionReason(...)` (build-key check + anime block + asymmetric tmdb-for-movie / tvdb-for-series id check). |
| `core/tmdb/TmdbMetadataService.kt` | Inject `FanartTvArtworkResolver`; when active provider is FANART_TV, route through it. |
| `core/tvdb/TvdbMetadataService.kt` | Same wiring. |
| `ui/screens/home/HomeCatalogRefreshCoordinator.kt` | Same wiring. |

The consumer wiring at the three sites uses the same shape: branch on `providerId == FANART_TV`, route to `FanartTvArtworkResolver.fetchUrl(...)`. The branch is kept inline at each site rather than abstracted into a dispatcher — three call sites, small shape, dispatcher would obscure the wiring.

## Settings UI Behavior

No UI surface added. Existing per-type selectors render the new choice via the registry.

```
Build with FANARTTV_API_KEY non-blank:
  Poster:    Default, [RPDB?], [Top Posters?], Fanart.tv
  Logo:      Default, Fanart.tv
  Backdrop:  Default, Fanart.tv
  Thumbnail: Default, [Top Posters?]

Build with FANARTTV_API_KEY blank:
  Fanart.tv absent from every dropdown (same pattern as RPDB when its key is blank).
```

The descriptor's `isConfigured` lambda reads `BuildConfig.FANARTTV_API_KEY` directly via `FanartTvAvailability.from(...)`. It ignores the `ArtworkProviderSettings` parameter — Fanart.tv has no settings field for its key. This is a mild departure from the RPDB/TopPosters pattern (where `isConfigured` reads `settings.hasXKey`); the alternative — adding a fake `fanartTvKey: String` to `ArtworkProviderSettings` — would be worse.

## Capability Rules

`fanartTvRejectionReason(imageType, ids, mediaKind, settings)`:

```
1. BuildConfig key blank                      → "fanart_tv_not_configured"
2. imageType not in {POSTER, LOGO, BACKDROP}  → "unsupported_artwork_type_for_provider"
3. mediaKind == ANIME                         → "anime_unsupported_for_fanart_tv"
4. mediaKind == MOVIE  + tmdb blank           → "missing_supported_provider_id"
5. mediaKind == SERIES + tvdb blank           → "missing_supported_provider_id"
6. mediaKind not MOVIE and not SERIES         → "missing_supported_provider_id"
7. else                                       → null (supported)
```

The mediaKind-aware id check is stricter than RPDB's "any of the supported id types is present". Fanart.tv specifically wants TMDB for movies and TVDB for series, never the other way around. That asymmetry justifies the dedicated branch.

## URL Resolution

`FanartTvArtworkResolver.fetchUrl(callId: FanartTvCallId, type: ArtworkType): String?`:

1. `availability = FanartTvAvailability.from(BuildConfig.FANARTTV_API_KEY)`. If `Disabled`, return null.
2. `result = lookup.fetch(callId, availability.apiKey)`.
3. Match on `result`:
   - `Success(doc)` → return `picker.pickFor(doc, callId.type, type)` (URL or null).
   - `NotFound` / `AuthFailed` / `Transient` → return null.

The resolver makes no decisions about fallback. The consumer (TmdbMetadataService / TvdbMetadataService / HomeCatalogRefreshCoordinator) writes the URL (or null) into the slot. The surface repo's `preferredAwareSlot` enforces non-downgrade — if Fanart returns null, no other source back-fills.

The resolver does not check anime — that's enforced upstream by `ArtworkProviderResolver` (capability rejection routes anime to defaults). The defensive check in `FanartTvIdSelector` (returns null for ANIME) provides belt-and-suspenders.

## Cache Layers (No New Persistence)

| Layer | What it stores | TTL | Where |
|---|---|---|---|
| Runtime response cache | The Fanart.tv JSON body for one (mediaKind, id) | 14d | `integration_cache` table (blob), keyed by `IntegrationCallSpec` |
| Image bytes | The fetched poster/logo/backdrop bytes | per existing `ArtworkAssetDiskCache` policy | Disk file + record store |
| Surface authority | `preferredArtworkProviders[type] = FANART_TV` | until next settings change | `ResolvedDisplayItem.preferredArtworkProviders` + surface repo |

**Invariants:**

1. **No new persistence layer.** No new Room entity, no new DAO, no new decision store.
2. **JSON cache uses the standard runtime cache.** `IntegrationCachePolicy.CacheFirst(14L * 24 * 60 * 60 * 1000)` declared on `FanartTvLookupShape`. JSON body persisted in `integration_cache` exactly like every other provider.
3. **Single-flight is runtime-provided.** `IntegrationSingleFlight` coalesces concurrent identical specs.
4. **Settings change = full re-hydration.** `ArtworkSettingsInvalidator` already does this; toggling Fanart.tv on/off triggers `markStaleAll` automatically. No Fanart-specific invalidation code.
5. **Empty slot is a real outcome.** Non-anime + Fanart returns null → consumer writes null → surface repo respects the explicit choice.

## Audit & Redaction

The API key is a build secret but flows in request URLs as a query parameter (`?api_key=...`). `FanartTvLookupShape` declares `api_key` as a redacted query parameter using the same redaction-policy mechanism that RPDB/Top Posters use for their key parameters. Any URL appearing in trace, audit, or log output replaces the `api_key` value with `<redacted>`.

The existing `home.artwork_resolver.decision` trace event records `chosenProvider=FANART_TV` or `fellThroughTo=ADDON` with `fanartTvRejectionReason` as the cause when applicable. No new trace events.

## 401/403 Recovery

If `BuildConfig.FANARTTV_API_KEY` is wrong, every Fanart.tv lookup returns `AuthFailed` and the slot stays empty for non-anime. To recover, the developer fixes `local.properties` and rebuilds. The new key produces different `IntegrationCallSpec` instances (assuming `api_key` participates in spec equality / cache key), so cached bad-key bodies are not served.

If `api_key` does not participate in spec equality, the implementation must explicitly trigger `HydratedHomeOverlayStore.markStaleAll` on app boot when the build-config key differs from the last-seen key. This is a verification item flagged in the implementation plan, not a separate spec section.

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

Image types **not** consumed (intentionally ignored even if present in the response): `movielogo`, `clearlogo`, `clearart`, `hdclearart`, `hdmovieclearart`, `characterart`, `moviebanner`, `tvbanner`, `moviedisc`, `moviethumb`, `tvthumb`, `seasonbanner`, `seasonposter`, `seasonthumb`. The DTO does not model them; `Json { ignoreUnknownKeys = true }` discards them.

Picker algorithm (pure function):

```
1. Take the array for the (callType, artworkType).
2. Filter to entries where url is non-blank.
3. Filter to entries where lang == "en" (skip filter for backdrop).
4. Sort by likes descending (parsed as Int; malformed → 0).
5. Tie-break by id ascending (parsed as Long; malformed → MAX_VALUE).
6. Return the first entry's url, or null if empty.
7. THUMBNAIL artwork type returns null unconditionally.
```

Two reference fixtures (Fight Club tmdb_id 550, Breaking Bad tvdb_id 81189) are committed under `app/src/test/resources/fixtures/fanarttv/` and verify exact picker outputs:

- Fight Club logo: `hdmovielogo` id `12657` (en, 8 likes).
- Fight Club backdrop: `moviebackground` id `119633` (no lang, 5 likes).
- Fight Club poster: `movieposter` id `50065` (en, 15 likes).
- Breaking Bad logo: `hdtvlogo` id `20282` (en, 24 likes).
- Breaking Bad backdrop: `showbackground` id `18563` (no lang, 12 likes).
- Breaking Bad poster: `tvposter` id `45072` (en, 14 likes).

## Build Wiring

```kotlin
// app/build.gradle.kts (alongside existing buildConfigField calls in defaultConfig)
buildConfigField(
    "String",
    "FANARTTV_API_KEY",
    "\"${localProperties.getProperty("fanarttv.api.key", "")}\""
)
```

`local.properties` (developer/CI machine, gitignored):
```
fanarttv.api.key=07882f4309da827df559bb85b63793f9
```

Builds without the key compile and run; Fanart.tv simply never appears in the settings dropdown.

## Error Handling Summary

**Capability layer (resolver decides which provider):**

| Condition | Capability rejection reason | Resolver returns |
|---|---|---|
| ANIME + Fanart selected | `anime_unsupported_for_fanart_tv` | `ContentTypeDefaults.resolve(type, isAnime=true)` → ADDON |
| Movie + no TMDB id + Fanart selected | `missing_supported_provider_id` | `ContentTypeDefaults.resolve(type, isAnime=false)` → ADDON (today) |
| Series + no TVDB id + Fanart selected | `missing_supported_provider_id` | ADDON fallback |
| BuildConfig key blank + Fanart selected | `fanart_tv_not_configured` | ADDON fallback |
| THUMBNAIL with Fanart selected (defensive — UI cannot reach this) | `unsupported_artwork_type_for_provider` | ADDON fallback |
| Otherwise (movie+TMDB / series+TVDB, non-anime, key present) | none | `FANART_TV` |

**Fetcher layer (resolver returned FANART_TV):**

| Condition | `fetchUrl(...)` result | Slot |
|---|---|---|
| Lookup `Success(doc)` + picker finds match | URL string | filled with Fanart URL |
| Lookup `Success(doc)` + picker finds no match (no en variant for poster/logo, empty array) | `null` | empty slot per Q1 rule |
| Lookup `NotFound` (404) | `null` | empty slot |
| Lookup `AuthFailed` (401/403) | `null` | empty slot (recovers when key fixed) |
| Lookup `Transient` (429/5xx/network) | `null` | empty slot (runtime backoff applies) |

The strict no-fallback-at-fetcher-layer rule is intentional. If the user picked Fanart for non-anime, an empty slot is the contract. The only fallback is the capability-layer fallback.

## Test Plan

### Pure-function tests

- `FanartTvAvailabilityTest` — `Available` for non-blank, `Disabled("no_build_config_key")` for blank/whitespace.
- `FanartTvIdSelectorTest` — Movie+TMDB → MOVIE call-id; Series+TVDB → TV call-id; ANIME → null (defensive); missing id → null; UNKNOWN → null.
- `FanartTvImagePickerTest` — table-driven over the documented rules. Plus two fixture-driven assertions that lock picker outputs against the committed Fight Club / Breaking Bad responses. THUMBNAIL returns null.

### Resolver test

`FanartTvArtworkResolverTest` (with a fake `FanartTvLookup`):

- BuildConfig key blank → returns null without calling lookup.
- ANIME (defensive) → returns null without calling lookup.
- Lookup `Success(doc)` with en variant → returns the picked URL.
- Lookup `Success(empty doc)` → returns null.
- Lookup `NotFound` / `AuthFailed` / `Transient` → returns null.

### Lookup-shape test

`FanartTvLookupShapeTest`:

- `specFor(callId, apiKey)` produces `IntegrationCachePolicy.CacheFirst(ttlMs = 14L * 24 * 60 * 60 * 1000)`.
- `redactedUrlForTrace` does not contain the raw key.
- A trace fixture exercising the spec with a real key produces no trace string containing it.

### Capability-resolver test (extends existing `ArtworkProviderCapabilityResolverTest`)

- FANART_TV + ANIME → `anime_unsupported_for_fanart_tv`.
- FANART_TV + MOVIE + no TMDB id → `missing_supported_provider_id`.
- FANART_TV + SERIES + no TVDB id → `missing_supported_provider_id`.
- FANART_TV + MOVIE + TMDB id present + non-anime → supported.
- FANART_TV + SERIES + TVDB id present + non-anime → supported.
- FANART_TV + THUMBNAIL → `unsupported_artwork_type_for_provider`.

### Registry test (extends existing `ArtworkProviderRegistryTest`)

- BuildConfig key non-blank → `availableChoices(POSTER/LOGO/BACKDROP)` includes `FANART_TV`.
- BuildConfig key non-blank → `availableChoices(THUMBNAIL)` does NOT include `FANART_TV`.
- BuildConfig key blank → `FANART_TV` absent from all type lists.

### Resolver-decision test (extends existing `ArtworkProviderResolverTest`)

- Explicit FANART_TV + capable (movie+TMDB+non-anime) → returns FANART_TV.
- Explicit FANART_TV + ANIME → returns ContentTypeDefaults.resolve(POSTER, isAnime=true) → ADDON; trace records `fellThroughTo=ADDON`.
- Explicit FANART_TV + missing-id → returns ContentTypeDefaults.resolve(POSTER, isAnime=false) → ADDON; trace records `fellThroughTo=ADDON`.

### Consumer-wiring tests

For each modified consumer (`TmdbMetadataService`, `TvdbMetadataService`, `HomeCatalogRefreshCoordinator`):

- When `ArtworkProviderResolver` returns FANART_TV → service calls `FanartTvArtworkResolver.fetchUrl(...)` instead of the existing `PosterRatingsUrlResolver.apply(...)` path.
- When `ArtworkProviderResolver` returns RPDB / TOP_POSTERS / ADDON → existing path is preserved (regression guard).

### Surface-repo test (extends existing `ResolvedDisplaySurfaceRepositoryTest`)

- Item with `preferredArtworkProviders[POSTER] = FANART_TV` and incoming poster slot from non-Fanart source → `preferredAwareSlot` rejects the incoming slot.
- Item with `preferredArtworkProviders[POSTER] = FANART_TV` and incoming Fanart poster URL is null → slot stays empty; surface repo does not back-fill from any other source.

### Settings-invalidator test

- Toggling settings from `posterProvider=DEFAULT` to `posterProvider=FANART_TV` produces a different `toSettingsSignature()` → triggers `markStaleAll`.

### Manual smoke

- BuildConfig with key set → settings dialog shows Fanart.tv option for POSTER/LOGO/BACKDROP, not for THUMBNAIL.
- Select Fanart.tv for poster → Fight Club poster matches `https://assets.fanart.tv/fanart/fight-club-522a5477c7bd3.jpg`.
- Select Fanart.tv for poster → Breaking Bad poster matches `https://assets.fanart.tv/fanart/breaking-bad-5427fc5ebded7.jpg`.
- Select Fanart.tv for poster → an anime title still renders the addon poster (not blank, not Fanart).
- Select Fanart.tv for poster → a movie with no TMDB id still renders the addon poster (capability fallback).
- Toggle Fanart.tv off → all visible items re-hydrate via `markStaleAll` and revert to the prior provider.
- Force-stop and re-open with Fanart.tv selected for a previously-resolved title → no new `fanarttv.lookup` runtime call (cache hit) and no new image bytes call (disk cache hit).

No end-to-end network test against real fanart.tv. The two captured fixtures cover deterministic picker behavior; live API behavior is covered by the manual smoke step.

## Implementation Phases

### Phase 1: Build wiring + enum
- Add `FANARTTV_API_KEY` BuildConfig field and `local.properties` entry.
- Add `IntegrationProvider.FANART_TV` enum value.

### Phase 2: API contract + fixtures + DTO + Retrofit
- Commit Fight Club / Breaking Bad JSON fixtures.
- Add DTO classes for the six consumed image arrays.
- Add `FanartTvApi` Retrofit interface.

### Phase 3: Pure helper units
- `FanartTvAvailability`, `FanartTvIdSelector`, `FanartTvImagePicker` with full unit tests including fixture-driven picker assertions.

### Phase 4: Runtime wiring
- `FanartTvApiShapes.LOOKUP` constant.
- `FanartTvApiModule` (Hilt) — provides `FanartTvApi`, configures `Json { ignoreUnknownKeys = true }`.
- `FanartTvLookupShape` with `CacheFirst(14d)` and `api_key` redaction policy.
- `FanartTvLookup` interface + `FanartTvLookupResult` types.
- `RuntimeFanartTvLookup` impl mapping `HttpException` → typed result.
- `FanartTvLookupShapeTest` verifying CacheFirst TTL and redaction.

### Phase 5: Resolver
- `FanartTvArtworkResolver` composing availability + lookup + picker.
- Hilt `@Provides` for the resolver.
- Unit tests covering all fetcher-layer outcomes.

### Phase 6: Settings-layer integration
- Add `ArtworkProviderChoiceKey.FANART_TV` constant; extend `fromStored()` and `toRuntimeProviderId()`.
- Add `fanartTvDescriptor` to `artworkProviderDescriptors` list.
- Extend `ArtworkProviderCapabilityResolver` with `descriptor()` branch and `fanartTvRejectionReason`.
- Extend registry, capability-resolver, and resolver-decision tests.

### Phase 7: Consumer wiring
- Inject `FanartTvArtworkResolver` into `TmdbMetadataService`, `TvdbMetadataService`, `HomeCatalogRefreshCoordinator`.
- At each site, branch on FANART_TV → call resolver; otherwise existing path.
- Add per-consumer wiring tests.

### Phase 8: Verification & smoke
- Audit redaction end-to-end test.
- Surface-repo extension test for FANART_TV preferred provider.
- Settings-invalidator test for FANART_TV toggle.
- Manual smoke per the plan above.
- Verify whether `api_key` participates in `IntegrationCallSpec` equality; if not, add a one-line `markStaleAll` on boot when the build-config key differs from the last-seen key.
