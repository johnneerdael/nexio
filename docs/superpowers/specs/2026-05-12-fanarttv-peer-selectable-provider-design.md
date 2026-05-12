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

Fanart.tv slots into the existing chain at three points: a new `ArtworkProviderChoiceKey`, a new descriptor in the registry, and a new candidate generator injected into `MetadataArtworkDecisionResolver`. The generator emits `ArtworkCandidate` instances with `provider=FANART_TV` and `sourceRole=PREMIUM` for poster/logo/backdrop when the user selected FANART_TV for that type. The existing `ArtworkRouter.isActiveSupportedPremium(...)` already picks user-selected PREMIUM candidates over PRIMARY — no router rank change, no new `ArtworkSourceRole` value. `PosterRatingsUrlResolver` is unchanged; it remains the templated-URL path for RPDB/Top Posters and ignores FANART_TV (its `resolveProvider` returns null for non-RPDB/non-TopPosters selections, which is the well-behaved no-op).

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
        ├─► HomeResolvedDisplayMapper:
        │     ArtworkProviderResolver.resolve(POSTER, ...) → FANART_TV (or ADDON via fall-through)
        │     preferredArtworkProviders[POSTER] = result, stamped on ResolvedDisplayItem
        │
        └─► MetadataArtworkDecisionResolver.resolveFields(candidates):
              ├─ existing primary candidates (TMDB/TVDB/addon)
              ├─ existing premium candidates (RPDB/TopPosters)
              └─ NEW: FanartTvCandidateGenerator
                    │
                    ├─ For each requested type (POSTER/LOGO/BACKDROP):
                    │     ├─ settings.selection.providerFor(type) != FANART_TV → skip
                    │     ├─ capability rejects (anime / missing id / unconfigured) → skip
                    │     └─ else: include in lookup
                    │
                    ├─ If at least one type to fetch:
                    │     IntegrationRuntime call via FanartTvLookupShape (CacheFirst 14d)
                    │     in-memory parse, picker.pickFor(doc, callType, type) → URL or null
                    │
                    └─ Emit ArtworkCandidate(provider=FANART_TV, sourceRole=PREMIUM, source=RemoteUrl(url))
                       for each non-null URL

ArtworkRouter.select(candidates, policy):
   PREMIUM candidates whose provider matches user selection win
   FANART_TV-selected + Fanart-emitted PREMIUM candidate present → wins
   FANART_TV-selected + no Fanart candidate emitted → falls through to PRIMARY (ADDON)

MetadataArtworkDecisionResolver writes ArtworkDecision to ArtworkDecisionCache
ArtworkAssetRepository fetches bytes for selected URL via existing pipeline
Slot built with selectedProvider = chosen provider id

ResolvedDisplaySurfaceRepository.preferredAwareSlot:
   preferred[POSTER] = FANART_TV
   incoming slot.selectedProvider = FANART_TV → accept (Fanart delivered)
   incoming slot.selectedProvider = ADDON     → reject (non-downgrade) → slot empty per Q1 rule
   incoming slot.selectedProvider = ADDON AND preferred = ADDON (anime fall-through case) → accept

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
| `core/artwork/fanarttv/FanartTvCandidateGenerator.kt` | Augments `MetadataArtworkDecisionResolver`. For each requested type, checks user selection + capability + Fanart availability; if all pass, calls `FanartTvLookup.fetch(...)` and emits a `RemoteUrl` `ArtworkCandidate(provider=FANART_TV, sourceRole=PREMIUM)` for each non-null picker output. Single-flight + 14d JSON cache provided by `IntegrationRuntime`; the generator is stateless. |
| `core/artwork/fanarttv/FanartTvApiShapes.kt` | `const LOOKUP = "fanarttv.lookup"`. |
| `core/artwork/fanarttv/dto/FanartTvDocument.kt` | `@Serializable` DTO with the six consumed image arrays. |
| `core/artwork/fanarttv/dto/FanartTvImage.kt` | `@Serializable` single-image DTO (id, url, lang, likes). |
| `core/artwork/fanarttv/FanartTvLookup.kt` | Interface + `FanartTvLookupResult` sealed (`Success(doc) / NotFound / AuthFailed / Transient`). |
| `data/integration/fanarttv/FanartTvApi.kt` | Retrofit interface: `GET /v3.2/movies/{tmdbId}` and `GET /v3.2/tv/{tvdbId}` with `?api_key=`. |
| `data/integration/fanarttv/FanartTvApiModule.kt` | Hilt module: provides `FanartTvApi` (Retrofit + base URL `https://webservice.fanart.tv/`); binds `FanartTvLookup` to `RuntimeFanartTvLookup`; provides `FanartTvCandidateGenerator`. |
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
| `data/integration/metadata/MetadataArtworkDecisionResolver.kt` | Inject `FanartTvCandidateGenerator`; augment the candidate list at the top of `resolveFields(...)` by appending Fanart-emitted candidates per ownerKey before routing. |

`PosterRatingsUrlResolver`, `TmdbMetadataService`, `TvdbMetadataService`, and `HomeCatalogRefreshCoordinator` are **unchanged**. `PosterRatingsUrlResolver.resolveProvider(settings)` already returns `null` for any selection that isn't RPDB or TOP_POSTERS, so it cleanly no-ops when FANART_TV is selected. Logo and backdrop URLs flow through `MetadataArtworkDecisionResolver` and the existing addon/metadata candidate path; the new generator simply adds Fanart-sourced candidates that the router picks ahead of PRIMARY when user selection matches.

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

## Candidate Generation

`FanartTvCandidateGenerator.generate(ownerKey, canonicalContentId, mediaKind, providerIds, requestedTypes, settings): List<ArtworkCandidate>`:

1. If `BuildConfig.FANARTTV_API_KEY` is blank → return empty.
2. Filter `requestedTypes` to those where `settings.selection.providerFor(type) == FANART_TV`. If empty → return empty.
3. Filter further to types that pass `ArtworkProviderCapabilityResolver` (anime / missing id / unsupported type → drop). If empty → return empty.
4. `callId = FanartTvIdSelector.select(mediaKind, providerIds)`. If null → return empty.
5. `result = lookup.fetch(callId, BuildConfig.FANARTTV_API_KEY)`.
   - `Success(doc)` → for each remaining requested type, `picker.pickFor(doc, callId.type, type)` → URL or null. For each non-null URL, emit one `ArtworkCandidate(provider=FANART_TV, sourceRole=PREMIUM, source=RemoteUrl(url))`.
   - `NotFound` / `AuthFailed` / `Transient` → return empty.

The generator emits zero candidates when Fanart can't deliver. The router then naturally picks a PRIMARY (addon) candidate. `ResolvedDisplaySurfaceRepository.preferredAwareSlot` enforces the empty-slot contract: when `preferredArtworkProviders[type] = FANART_TV` and the incoming slot's `selectedProvider = ADDON`, the slot is rejected → empty per the user's Q1 rule. No fetcher-side null-handling required.

For anime, both `ArtworkProviderResolver.resolve(...)` and the generator's capability check return ADDON / skip Fanart respectively. `preferredArtworkProviders[type] = ADDON` and the incoming ADDON slot is accepted — anime renders normally.

The generator is invoked from `MetadataArtworkDecisionResolver.resolveFields(...)` per `ownerKey`. Single-flight and 14d JSON cache come from `IntegrationRuntime` + `IntegrationSingleFlight` automatically — the generator does no coalescing of its own.

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

**Generator layer (FANART_TV selected, capability supported, non-anime):**

| Condition | Generator emits | Router picks | Surface result |
|---|---|---|---|
| Lookup `Success(doc)` + picker finds match | 1 candidate per non-null type | FANART_TV PREMIUM (preferred match) | filled with Fanart URL |
| Lookup `Success(doc)` + picker finds no match (no en variant for poster/logo, empty array) | 0 candidates for that type | PRIMARY (ADDON) | rejected by preferredAwareSlot → empty slot per Q1 rule |
| Lookup `NotFound` (404) | 0 candidates | PRIMARY (ADDON) | rejected → empty slot |
| Lookup `AuthFailed` (401/403) | 0 candidates | PRIMARY (ADDON) | rejected → empty slot (recovers on next resolve after key fix) |
| Lookup `Transient` (429/5xx/network) | 0 candidates | PRIMARY (ADDON) | rejected → empty slot (runtime backoff applies) |

The empty-slot contract is enforced at the surface layer by `preferredAwareSlot`, not at the generator/fetcher. The generator simply emits zero candidates; the router falls through to ADDON; the surface repo rejects the ADDON slot because the preferred provider is FANART_TV. This means **no fetcher-side null-handling code** — the existing non-downgrade machinery does the work.

## Test Plan

### Pure-function tests

- `FanartTvAvailabilityTest` — `Available` for non-blank, `Disabled("no_build_config_key")` for blank/whitespace.
- `FanartTvIdSelectorTest` — Movie+TMDB → MOVIE call-id; Series+TVDB → TV call-id; ANIME → null (defensive); missing id → null; UNKNOWN → null.
- `FanartTvImagePickerTest` — table-driven over the documented rules. Plus two fixture-driven assertions that lock picker outputs against the committed Fight Club / Breaking Bad responses. THUMBNAIL returns null.

### Generator test

`FanartTvCandidateGeneratorTest` (with a fake `FanartTvLookup`):

- BuildConfig key blank → emits zero candidates without calling lookup.
- No requested type has FANART_TV selected → emits zero, no lookup call.
- All requested types fail capability (anime / missing id) → emits zero, no lookup call.
- ANIME (defensive) → emits zero without calling lookup.
- Lookup `Success(doc)` with en variants for all three types → emits 3 candidates with `provider=FANART_TV`, `sourceRole=PREMIUM`, `source=RemoteUrl(url)`.
- Lookup `Success(doc)` with en variant only for poster → emits 1 candidate (poster); zero for logo/backdrop.
- Lookup `Success(empty doc)` → emits zero candidates.
- Lookup `NotFound` / `AuthFailed` / `Transient` → emits zero candidates.
- Mixed selection (POSTER=FANART_TV, LOGO=DEFAULT, BACKDROP=FANART_TV) → exactly one lookup call; only POSTER and BACKDROP candidates emitted.

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

### Resolver-augmentation test

`MetadataArtworkDecisionResolverFanartTvTest`:

- When `resolveFields(...)` is called with TVDB primary candidates and the user has `posterProvider=FANART_TV`, the resolver invokes `FanartTvCandidateGenerator.generate(...)` exactly once per ownerKey with `requestedTypes` = the union of artwork types in the input candidate list (excluding THUMBNAIL).
- The generator's emitted candidates are appended to the candidate list passed to `ArtworkRouter.select(...)`.
- When `posterProvider=DEFAULT` → generator is invoked but emits zero (per generator-level filtering); existing behavior unchanged.

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

### Phase 5: Settings-layer integration
- Add `ArtworkProviderChoiceKey.FANART_TV` constant; extend `fromStored()` and `toRuntimeProviderId()`.
- Add `fanartTvDescriptor` to `artworkProviderDescriptors` list.
- Extend `ArtworkProviderCapabilityResolver` with `descriptor()` branch and `fanartTvRejectionReason`.
- Extend registry, capability-resolver, and resolver-decision tests.

### Phase 6: Candidate generator
- `FanartTvCandidateGenerator` composing settings filter + capability filter + lookup + picker → emit candidates.
- Hilt `@Provides` for the generator.
- Unit tests covering all generator-layer outcomes.

### Phase 7: MetadataArtworkDecisionResolver augmentation
- Inject `FanartTvCandidateGenerator` into `MetadataArtworkDecisionResolver`.
- At the top of `resolveFields(...)`, group input candidates by ownerKey, call generator once per group with the union of imageTypes (excluding THUMBNAIL), append generator output to the list before routing.
- Add the augmentation test.

### Phase 8: Verification & smoke
- Audit redaction end-to-end test.
- Surface-repo extension test for FANART_TV preferred provider.
- Settings-invalidator test for FANART_TV toggle.
- Manual smoke per the plan above.
- Verify whether `api_key` participates in `IntegrationCallSpec` equality; if not, add a one-line `markStaleAll` on boot when the build-config key differs from the last-seen key.
