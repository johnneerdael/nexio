# Premium Artwork Provider Selection Design

Date: 2026-05-05

## Purpose

Replace the current global premium poster provider model with a future-proof artwork capability and selection system. Users configure provider credentials once, then choose providers per artwork type. The shared artwork router decides the final image for posters, logos, backdrops, and thumbnails.

This design covers Phase 1 support for:

- RPDB posters.
- Top Posters posters.
- Top Posters Premium episode thumbnails with rating badges.
- Migration from existing RPDB and Top Posters poster settings.

It also creates the model needed for later Fanart.tv, OpenPosterDB, AIORatings, and future RPDB logo/backdrop support.

## Goals

- Remove RPDB and Top Posters enabled/disabled toggles.
- Treat a non-empty API key as provider configuration.
- Let per-artwork-type selectors determine usage.
- Verify Top Posters tier and features through the shared `IntegrationRuntime`.
- Avoid querying Top Posters entitlement on every artwork fetch.
- Route all Top Posters posters, thumbnails, and key validation through shared integration runtime components.
- Suppress local episode rating overlays only when the selected thumbnail embeds rating badges.
- Preserve fallback behavior so artwork failure never blanks episode cards.

## Non-Goals

- No custom Top Posters badge, trend, style, or language settings in Phase 1.
- No Phase 1 implementation of RPDB logos/backdrops.
- No Phase 1 implementation of Fanart.tv, OpenPosterDB, or AIORatings.
- No direct Top Posters calls outside `IntegrationRuntime`.
- No separate Top Posters thumbnail toggle.

## Existing Context

The app already has useful artwork primitives:

- `ArtworkType` includes `POSTER`, `BACKDROP`, `LOGO`, and `THUMBNAIL`.
- `ArtworkDisplayRef` represents typed runtime artwork.
- `ArtworkAssetRepository` fetches runtime assets through the integration cache.
- `ArtworkRouter` chooses between premium, primary, preview, fallback, and placeholder candidates.
- `Video.thumbnailArtwork` exists for typed episode thumbnail projection.

The main gap is that premium settings and routing still revolve around one active premium poster provider. That model does not fit per-type choices or Top Posters thumbnail entitlement.

## Settings Model

Replace `PosterRatingsSettings` with a generic artwork provider settings model.

```kotlin
data class ArtworkProviderSettings(
    val rpdbApiKey: String = "",
    val topPostersApiKey: String = "",
    val topPostersEntitlement: TopPostersEntitlementSnapshot? = null,
    val selections: ArtworkProviderSelectionSettings = ArtworkProviderSelectionSettings()
)
```

```kotlin
data class ArtworkProviderSelectionSettings(
    val posterProvider: ArtworkProviderChoice = ArtworkProviderChoice.Default,
    val logoProvider: ArtworkProviderChoice = ArtworkProviderChoice.Default,
    val backdropProvider: ArtworkProviderChoice = ArtworkProviderChoice.Default,
    val thumbnailProvider: ArtworkProviderChoice = ArtworkProviderChoice.Default
)
```

```kotlin
sealed interface ArtworkProviderChoice {
    data object Default : ArtworkProviderChoice
    data class Provider(val providerId: ArtworkProviderId) : ArtworkProviderChoice
}
```

Provider configuration is key-based:

- RPDB is configured when `rpdbApiKey` is not blank.
- Top Posters is configured when `topPostersApiKey` is not blank.

There are no enabled toggles. API keys make providers available; selectors decide whether they are used.

## Migration

Existing settings migrate as follows:

| Existing state | New state |
| --- | --- |
| `rpdbEnabled=true` and RPDB key present | Keep key, set `posterProvider=RPDB` |
| `rpdbEnabled=false` and RPDB key present | Keep key, set `posterProvider=Default` |
| `topPostersEnabled=true` and Top Posters key present | Keep key, set `posterProvider=TOP_POSTERS` |
| `topPostersEnabled=false` and Top Posters key present | Keep key, set `posterProvider=Default` |
| No provider key | All selections remain `Default` |

If a configured key is later cleared, any saved selection pointing to that provider becomes effectively `Default`. The stored selection may be retained for diagnostics, but runtime and UI must resolve it as unavailable.

## Top Posters Entitlement

Top Posters tier is read from the API, not user-selected.

Use:

```text
GET /auth/verify/{api_key}
```

through `IntegrationRuntime` shape:

```text
topposters.key_validation
```

Persist a parsed snapshot:

```kotlin
data class TopPostersEntitlementSnapshot(
    val valid: Boolean,
    val isActive: Boolean,
    val tier: Int?,
    val tierName: String?,
    val episodeThumbnails: Boolean,
    val rateLimitPerMinute: Int?,
    val rateLimitPerMonth: Int?,
    val usageTotalRequests: Long?,
    val usageLastUsed: String?,
    val expiresAt: String?,
    val verifiedAtMs: Long
)
```

The endpoint returns tier and feature data. A verified Premium response includes `tier=1`, `tier_name=Premium`, and `tier_info.features.episode_thumbnails=true`.

### Validation Policy

Validation must not happen per poster or thumbnail fetch.

Rules:

- Force verify when the Top Posters key is added or changed.
- Verify on settings screen entry if the snapshot is stale.
- Verify on app start if the snapshot is stale.
- Allow manual "Verify" to force a refresh.
- Refresh if Top Posters artwork returns `401` or `403`.
- Use a 24-hour freshness TTL for normal revalidation.
- If verification fails because of network/server failure, keep the last successful snapshot.
- If verification returns invalid, inactive, expired, or lower entitlement, persist that result and disable unavailable choices.
- If there is no successful snapshot, expose only Free/Tier 3 behavior.

The `topposters.key_validation` runtime shape should use cache semantics that enforce the 24-hour TTL while still allowing explicit forced refresh.

## Capability Registry

Introduce `ArtworkProviderRegistry` as the source of truth for provider capabilities and settings choices.

Responsibilities:

- Determine whether a provider is configured.
- Determine whether entitlement permits a capability.
- Determine supported artwork types.
- Determine supported ID types.
- Determine whether episode context is required.
- Compute available UI choices for each artwork type.
- Explain rejection reasons for trace and tests.

Example interface:

```kotlin
interface ArtworkProviderRegistry {
    fun availableChoices(
        imageType: ArtworkType,
        settings: ArtworkProviderSettings,
        mediaKind: MetadataMediaKind? = null
    ): List<ArtworkProviderChoice>

    fun capability(
        provider: ArtworkProviderId,
        imageType: ArtworkType,
        settings: ArtworkProviderSettings,
        context: ArtworkCapabilityContext
    ): ArtworkCapabilityResult
}
```

Phase 1 capabilities:

| Provider | Poster | Thumbnail | Logo | Backdrop |
| --- | --- | --- | --- | --- |
| RPDB | Available with API key and IMDb/TMDB/TVDB ID | Unavailable | Designed, not exposed | Designed, not exposed |
| Top Posters Tier 3/2 | Available with API key and supported ID | Unavailable | Designed, not exposed | Unavailable |
| Top Posters Tier 1 | Available with API key and supported ID | Available when entitlement has `episode_thumbnails=true` | Designed, not exposed | Unavailable |

Top Posters supported ID types:

```text
imdb, tmdb, tvdb, trakt, mal, kitsu, anilist, anidb
```

RPDB supported ID types:

```text
imdb, tmdb, tvdb
```

## Selection UI

Settings displays one selector per artwork type:

```text
Poster provider
Logo provider
Backdrop provider
Thumbnail provider
```

Choices are registry-driven.

Examples:

No keys configured:

```text
Poster: Default
Logo: Default
Backdrop: Default
Thumbnail: Default
```

Top Posters key configured, unverified or Tier 3/Tier 2:

```text
Poster: Default, Top Posters
Logo: Default
Backdrop: Default
Thumbnail: Default
```

Top Posters key configured, verified Premium with episode thumbnails:

```text
Poster: Default, Top Posters
Logo: Default
Backdrop: Default
Thumbnail: Default, Top Posters
```

RPDB and Top Posters configured, Top Posters Premium:

```text
Poster: Default, Top Posters, RPDB
Logo: Default
Backdrop: Default
Thumbnail: Default, Top Posters
```

There is no separate "enable episode thumbnails with ratings" switch. Selecting `Thumbnail provider = Top Posters` is the opt-in.

## Artwork Routing

Change routing policy from a single `activePremiumProvider` to selection by artwork type.

```kotlin
data class ArtworkRoutingPolicy(
    val selections: ArtworkProviderSelectionSettings,
    val configuredProviders: ArtworkProviderSettings,
    val policyVersion: Int
)
```

Routing precedence:

1. Explicit provider selected for the artwork type, if configured and supported.
2. Future intermediate providers for `Default`, such as Fanart.tv.
3. Primary provider artwork from TMDB/TVDB/Kitsu.
4. Rail/addon/preview artwork.
5. Placeholder.

If an explicit provider is unavailable, unsupported for the ID, or fetches unsuccessfully, the decision records a rejected candidate and falls back through the normal chain. The router must not blank artwork.

## External ID Selection

Centralize provider-specific ID selection.

```kotlin
interface ArtworkExternalIdSelector {
    fun selectIds(
        provider: ArtworkProviderId,
        imageType: ArtworkType,
        mediaKind: MetadataMediaKind,
        providerIds: ProviderIds,
        episodeContext: EpisodeContext?
    ): List<ArtworkProviderExternalId>
}
```

```kotlin
data class ArtworkProviderExternalId(
    val idType: ArtworkExternalIdType,
    val mediaId: String
)
```

Top Posters poster ID order:

- Movie: TMDB `movie-{id}`, IMDb.
- TV: TVDB, TMDB `series-{id}`, IMDb, Trakt.
- Anime: Kitsu, MAL, AniList, AniDB, IMDb, TMDB/TVDB when available.

Top Posters thumbnail ID order:

- Anime: Kitsu, MAL, AniList, AniDB, IMDb, TMDB/TVDB when available.
- TV: TVDB, TMDB `series-{id}`, IMDb, Trakt.
- Movie: not applicable.

RPDB poster ID order:

- IMDb, TMDB, TVDB.

For thumbnails:

- Episode context is required.
- Season and episode must be at least 1.
- Media kind must be series/anime.

Trace the selected ID type and rejected alternatives where useful.

## Top Posters Thumbnail Requests

Runtime shape:

```text
topposters.thumbnail
```

Request format:

```text
/{api_key}/{id_type}/thumbnail/{media_id}/S{season}E{episode}.jpg
```

Always send TV layout parameters:

```text
badge_position=top-right
badge_size=small
blur=false
```

Do not rely on Top Posters user-agent/device preferences because the Android TV layout requires the top-right corner to remain the stable rating position.

Use `fallback_url` only when it does not bypass app-side trace/fallback guarantees. The app should still be able to know whether Top Posters or fallback artwork is selected so local rating overlay suppression remains correct.

## Display Hints And Rating Overlay

Add display hints to the selected artwork result or display ref:

```kotlin
data class ArtworkDisplayHints(
    val embedsRatingOverlay: Boolean = false
)
```

Top Posters thumbnails set:

```text
embedsRatingOverlay=true
```

Primary/fallback thumbnails set:

```text
embedsRatingOverlay=false
```

Episode UI logic:

```kotlin
val showLocalRatingOverlay = !thumbnailRef.displayHints.embedsRatingOverlay
```

Expected behavior:

| Selected thumbnail | Local rating overlay |
| --- | --- |
| Top Posters thumbnail | Hidden |
| TVDB/TMDB/Kitsu fallback thumbnail | Shown |
| Default thumbnail provider | Shown |
| Top Posters configured but not selected | Shown |
| Top Posters selected but fetch failed and fallback wins | Shown |

Episode UI must not inspect provider settings directly.

## Cache Keys

Top Posters entitlement cache key:

```text
topposters:validate:credentialHash:{credentialHash}:policy:{policyVersion}
```

Top Posters thumbnail decision key must include:

```text
provider
imageType
canonicalSeriesId
idType
mediaId
season
episode
badgePosition
badgeSize
blur
credentialHash
imageLang
policyVersion
```

Top Posters thumbnail asset key must include:

```text
provider
imageType
idType
mediaId
season
episode
badgePosition
badgeSize
blur
credentialHash
imageLang
policyVersion
```

Poster cache keys should not include tier unless the app sends tier-varying request parameters. Tier 2 and Tier 3 do not change Phase 1 poster request parameters.

## Runtime And Error Handling

All provider network calls use `IntegrationRuntime`.

Required runtime shapes:

```text
topposters.key_validation
topposters.poster_template
topposters.thumbnail
rpdb.poster_template
rpdb.key_validation
```

Cache policy:

- Top Posters key validation: 24h freshness TTL, explicit forced refresh support.
- Top Posters posters and thumbnails: `CacheFirst`, 24h TTL, 7d stale window.
- Work class: `USER_VISIBLE` for visible artwork; background hydration can use a lower priority work class.

429 behavior:

- Respect `Retry-After` through the shared runtime/backoff machinery.
- Use stale Top Posters artwork if allowed.
- Otherwise fall back to primary provider artwork.

Auth failure:

- `401`/`403` invalidates or refreshes Top Posters entitlement through runtime.
- Unavailable features are removed from effective choices.

Sensitive data:

- API keys must be redacted from trace, audit, logcat, cache reports, and failure messages.
- Provider paths containing API keys must be represented by hashes or redacted source strings.

## Future Providers

The registry must allow later providers by adding capabilities, not UI-specific branches.

Planned future capabilities:

| Provider | Role | Types |
| --- | --- | --- |
| Fanart.tv | Intermediate | Posters, logos, backdrops |
| OpenPosterDB | Selectable premium/open provider | Posters, logos, backdrops, thumbnails |
| AIORatings | Selectable premium | Posters |
| RPDB future | Selectable premium | Logos, backdrops |

Default mode future precedence:

```text
intermediate provider, e.g. Fanart.tv
primary provider artwork
preview artwork
placeholder
```

Explicit selected provider precedence:

```text
selected provider
intermediate fallback if policy allows
primary provider artwork
preview artwork
placeholder
```

Explicit premium selections always beat Fanart.tv.

## Test Plan

Settings and migration:

- Migrates enabled RPDB with key to `posterProvider=RPDB`.
- Migrates disabled RPDB with key to `posterProvider=Default`.
- Migrates enabled Top Posters with key to `posterProvider=TOP_POSTERS`.
- Migrates disabled Top Posters with key to `posterProvider=Default`.
- Clearing provider key makes saved provider selection effectively `Default`.

Top Posters entitlement:

- Key validation runs through `IntegrationRuntime`.
- Entitlement snapshot parses tier, feature flags, limits, expiry, active state.
- Snapshot refreshes after 24h.
- Fresh snapshot avoids revalidation per artwork fetch.
- Network/server failure keeps last successful snapshot.
- Invalid/inactive/lower entitlement disables unavailable choices.
- No snapshot means Tier 3/free capabilities.

Provider choices:

- No keys exposes only `Default` for all types.
- Top Posters Tier 3/Tier 2 exposes posters only.
- Top Posters Premium exposes thumbnail choice.
- RPDB and Top Posters both configured expose both poster choices.
- Logo/backdrop expose only `Default` in Phase 1.

Routing and fallback:

- Explicit poster provider wins when supported.
- Explicit thumbnail provider wins when supported.
- Unsupported ID rejects premium candidate and falls back.
- Top Posters thumbnail fetch failure falls back to TVDB/TMDB/Kitsu thumbnail.
- 429 uses stale asset if available, otherwise primary fallback.

Thumbnail requests:

- Requires episode context.
- Rejects season or episode less than 1.
- Forces `badge_position=top-right`.
- Forces `badge_size=small`.
- Includes `blur=false`.
- Uses selected stable ID type and media ID.
- Cache key includes badge position, size, blur, ID, season, episode, credential hash, language, and policy version.

Episode UI:

- Top Posters thumbnail selection suppresses local rating overlay.
- Toggle-free selection model uses `thumbnailProvider=Top Posters` as the opt-in.
- Fallback thumbnail shows local rating overlay.
- Top Posters configured but not selected shows local rating overlay.

Runtime and audit:

- `topposters.thumbnail` is covered by integration runtime audit.
- API key is redacted from trace and reports.
- Cache hit suppresses network.
- Rejected candidate trace explains entitlement, unsupported ID, auth, rate limit, or fetch failure.

## Implementation Phases

### Phase 1A: Settings and Migration

- Add generic artwork provider settings.
- Remove enabled toggles from UI and data model.
- Add migration from old poster rating settings.
- Persist Top Posters entitlement snapshot.

### Phase 1B: Registry and Selection UI

- Add provider registry and capability evaluator.
- Render dynamic provider selectors for poster, logo, backdrop, thumbnail.
- Resolve unavailable selections as `Default`.

### Phase 1C: Router and ID Selector

- Replace `activePremiumProvider` routing with per-type selections.
- Add external ID selector.
- Route RPDB and Top Posters posters through the new model.
- Route Top Posters thumbnails through the new model.

### Phase 1D: Runtime and Cache

- Add typed Top Posters entitlement response parsing.
- Ensure key validation, poster, and thumbnail calls use `IntegrationRuntime`.
- Add thumbnail runtime shape, cache keys, redaction, and backoff behavior.

### Phase 1E: Episode UI

- Add artwork display hints.
- Hydrate `Video.thumbnailArtwork` with selected thumbnail decisions.
- Suppress local rating overlay only when selected thumbnail embeds ratings.

### Phase 1F: Reports and Tests

- Add targeted unit tests for settings, registry, routing, runtime, cache, and UI display hints.
- Extend metadata/artwork audit scenarios for Top Posters entitlement and thumbnail fallback.
