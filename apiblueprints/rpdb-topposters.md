Yes — I would group them as one **Artwork/Premium Poster Resolver** subsystem, because they are mutually exclusive at runtime and produce the same product outcome:

```text
input: media identity + artwork policy
output: final poster/logo/thumbnail candidate
```

But I would **not** collapse them into one provider contract. Use one shared resolver interface with two provider-specific adapters.

```text
ArtworkRouter
    ↓
PremiumPosterProvider
    ├── RpdbPosterProvider
    └── TopPostersProvider
    ↓
IntegrationRuntime
```

## Why group them

They are both secondary resolvers, not metadata authorities. They should never own title, overview, episode numbering, cast, etc. They only produce artwork overlays/rewrites.

Your audit already treats them as adjacent shapes: `rpdb.key_validation`, `topposters.key_validation`, `rpdb.poster_template`, `topposters.poster_template`, and `topposters.thumbnail`, with path/query credential handling and `Retry-After` capture.

Top-Posters is also explicitly positioned as RPDB-compatible / drop-in replacement, but with more capabilities: multi-source ratings, languages, badge customization, trend indicators, RPDB style, episode thumbnails, and anime IDs.

## Why keep separate provider contracts

Top-Posters has a richer API surface:

| Capability                         |                               RPDB |                Top-Posters |
| ---------------------------------- | ---------------------------------: | -------------------------: |
| Poster rewrite                     |                                Yes |                        Yes |
| RPDB-style output                  |                             Native | Supported via `style=rpdb` |
| Multi-language                     | Limited/unknown from current audit |                        Yes |
| Trend indicators                   |                         No/unknown |                        Yes |
| Custom badge sources               |                         No/unknown |                        Yes |
| Episode thumbnails                 |                                 No |               Yes, Premium |
| Anime IDs: Kitsu/AniList/AniDB/MAL |                         No/limited |                        Yes |
| Logo endpoint                      |                         No/unknown |                        Yes |
| User-agent/device-aware thumbnails |                                 No |                        Yes |

Top-Posters’ OpenAPI documents poster generation as:

```text
/{api_key}/{id_type}/{poster_type}/{media_id}.jpg
```

with query options such as `lang`, `trend`, `style`, and `fallback_url`; it also documents thumbnails as:

```text
/{api_key}/{id_type}/thumbnail/{media_id}/S{season}E{episode}.jpg
```

with `badge_position`, `badge_size`, `blur`, `user_agent`, and `fallback_url`.

So the grouping should be at the **resolver level**, not the **contract level**.

## Recommended model

```kotlin
interface PremiumPosterProvider {
    val provider: ArtworkProvider

    suspend fun validateKey(): ProviderKeyValidationResult

    suspend fun resolvePoster(
        request: PremiumPosterRequest
    ): PremiumArtworkResult

    suspend fun resolveThumbnail(
        request: PremiumThumbnailRequest
    ): PremiumArtworkResult
}
```

Then:

```kotlin
enum class ArtworkProvider {
    NONE,
    RPDB,
    TOP_POSTERS
}
```

Settings should enforce:

```text
Only one active premium poster provider at a time.
```

## Shared subsystem contract

| Area                        | Contract                                                                                                                                                          |
| --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Subsystem                   | `PREMIUM_ARTWORK_RESOLVER`                                                                                                                                        |
| Providers                   | `RPDB`, `TOP_POSTERS`                                                                                                                                             |
| Mutual exclusivity          | Exactly zero or one active provider                                                                                                                               |
| Primary metadata authority? | No                                                                                                                                                                |
| Owns fields                 | Final artwork selection only                                                                                                                                      |
| May modify                  | Poster/logo/thumbnail URL or local cached file                                                                                                                    |
| Must not modify             | Title, overview, IDs, episodes, cast, ratings document                                                                                                            |
| Runtime requirement         | All validation and generated artwork fetches go through `IntegrationRuntime`                                                                                      |
| Cache posture               | `CacheFirst` image/blob cache                                                                                                                                     |
| Work class                  | `USER_VISIBLE` for visible artwork; `BACKGROUND_HYDRATION` for prefetch; thumbnails can be `USER_VISIBLE` or `PLAYBACK_RESOLUTION` only if needed for playback UI |
| 429 policy                  | Parse `Retry-After`; provider/key scoped cooldown; fall back to native poster if configured                                                                       |
| Secret policy               | API key is in path/query, always redacted; cache keys use credential hash only if entitlement affects output                                                      |

## Recommended shape names

```yaml
apiShapes:
  artwork.rpdb.key_validation:
    provider: RPDB

  artwork.rpdb.poster:
    provider: RPDB

  artwork.topposters.key_validation:
    provider: TOP_POSTERS

  artwork.topposters.poster:
    provider: TOP_POSTERS

  artwork.topposters.logo:
    provider: TOP_POSTERS

  artwork.topposters.thumbnail:
    provider: TOP_POSTERS
```

## Cache-key rule

For both providers, cache keys must include every output-varying input.

For Top-Posters especially:

```text
provider
id_type
media_id
poster_type
lang
style
trend
fallback_url_hash
badge_position
badge_size
blur
user_agent_profile_id
season
episode
credentialHash/tier discriminator if tier affects output
schema_version
```

Top-Posters explicitly notes anime seasonal posters and that cache keys include a season suffix to avoid cross-season conflicts.

## My recommendation

Yes, group them as:

```text
Premium Artwork / Poster Provider
```

but implement as:

```text
shared resolver + provider-specific contracts
```

That gives you one product path and one mutual-exclusion setting, while still preserving the important API differences between RPDB and Top-Posters.
