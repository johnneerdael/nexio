# Unified Artwork Pipeline Design

## Architecture

Target flow:

```text
Source payload URL/path
-> ArtworkCandidate
-> ArtworkRouter
-> ArtworkDecisionCache
-> ArtworkAssetRepository
-> IntegrationRuntime CacheFirst
-> ArtworkAssetDiskCache
-> nexio-artwork:// Coil fetcher
-> UI render
```

Coil remains the Android renderer and decoded bitmap cache. Nexio owns artwork decisions, provider
precedence, fetch policy, TTL/stale behavior, profile/language scope, byte storage, and traceability.

## Ownership Rules

Raw remote artwork URLs are source payload data only. They are never final display ownership for
metadata surfaces: Home, Detail, Continue Watching, Player metadata, catalog rails, posters,
backdrops, logos, and thumbnails.

Raw remote URLs may appear only in DTOs, provider payload records, runtime fetch material,
`ArtworkCandidate.source`, `ArtworkAssetFetchRequest`, and redacted trace/debug output.

UI-facing metadata artwork uses one of:

- `ArtworkDisplayRef`
- `nexio-artwork://asset/{assetKey}`
- `nexio-artwork://decision/{decisionKey}`
- `nexio-placeholder://{type}`
- local/content URIs produced by `ArtworkAssetRepository`

Prefer `nexio-artwork://asset/{assetKey}` once a concrete asset is known. Use
`nexio-artwork://decision/{decisionKey}` only during compatibility or when lazy decision resolution
is required. A decision URI resolves an existing `ArtworkDecision` or recomputes through
`ArtworkRouter` using deterministic inputs; the Coil fetcher must not perform ad hoc provider
selection.

Legacy string fields remain temporarily, but they are one-way compatibility projections from
`ArtworkDisplayRef`. Production code must not parse legacy strings back into artwork ownership
decisions except for explicit migration/backfill.

## Core Display Types

```kotlin
enum class ArtworkType {
    POSTER,
    BACKDROP,
    LOGO,
    THUMBNAIL
}
```

```kotlin
enum class ArtworkSourceRole {
    PREMIUM,
    PRIMARY,
    CURRENT_PREVIEW,
    OTHER_PREVIEW,
    RAIL_PREVIEW,
    ADDON_PREVIEW,
    FALLBACK,
    PLACEHOLDER,
    LEGACY_STRING_COMPAT
}
```

```kotlin
data class ArtworkBundle(
    val poster: ArtworkDisplayRef?,
    val backdrop: ArtworkDisplayRef?,
    val logo: ArtworkDisplayRef?,
    val thumbnail: ArtworkDisplayRef?
)
```

```kotlin
sealed interface ArtworkDisplayRef {
    val imageType: ArtworkType
    val trace: ArtworkTrace

    data class RuntimeAsset(
        val decisionKey: ArtworkDecisionKey,
        val assetKey: ArtworkAssetKey?,
        override val imageType: ArtworkType,
        val selectedProvider: ArtworkProviderId?,
        val sourceRole: ArtworkSourceRole,
        override val trace: ArtworkTrace
    ) : ArtworkDisplayRef

    data class Placeholder(
        val placeholderType: PlaceholderType,
        override val imageType: ArtworkType,
        override val trace: ArtworkTrace
    ) : ArtworkDisplayRef
}
```

## Provider Identity

Known runtime providers must use existing `ProviderId` values where they exist. The artwork pipeline
must not create a second provider namespace with alternate spellings.

One acceptable model:

```kotlin
sealed interface ArtworkProviderId {
    data class RuntimeProvider(val providerId: ProviderId) : ArtworkProviderId
    data object RailPreview : ArtworkProviderId
    data object AddonPreview : ArtworkProviderId
    data object Placeholder : ArtworkProviderId
}
```

Known runtime provider examples include `TMDB`, `TVDB`, `KITSU`, `RPDB`, `TOP_POSTERS`, `ADDON`,
`MDBLIST`, `SIMKL`, and `TRAKT`. Pseudo-providers such as `RAIL_PREVIEW`, `ADDON_PREVIEW`, and
`PLACEHOLDER` must not collide with runtime provider identities.

## Owner Keys

First-paint preview artwork must not wait for canonical identity. Decisions therefore use an owner
key:

```kotlin
sealed interface ArtworkOwnerKey {
    data class CanonicalContent(
        val contentId: ContentIdentity
    ) : ArtworkOwnerKey

    data class PreviewItem(
        val itemKey: String,
        val sourcePayloadHash: String
    ) : ArtworkOwnerKey
}
```

Before stable ID resolution, a `PreviewItem` decision may drive first paint. After canonical identity
resolution, a canonical decision supersedes the preview decision as the primary artwork decision.
The preview artwork remains available as a fallback candidate in the canonical decision.

## Runtime Candidate Model

Runtime candidates may contain fetch material. Persisted decisions must not.

```kotlin
data class ArtworkCandidate(
    val ownerKey: ArtworkOwnerKey,
    val canonicalContentId: ContentIdentity?,
    val imageType: ArtworkType,
    val provider: ArtworkProviderId?,
    val sourceRole: ArtworkSourceRole,
    val source: ArtworkSource,
    val priority: Int,
    val requiresRuntimeFetch: Boolean,
    val imageLanguage: String = "en",
    val trace: ArtworkTrace
)
```

```kotlin
sealed interface ArtworkSource {
    class RemoteUrl(
        val rawUrl: SensitiveArtworkUrl,
        val redactedUrlForTrace: String,
        val normalizedUrlHash: String
    ) : ArtworkSource {
        override fun toString(): String =
            "RemoteUrl(redactedUrlForTrace=$redactedUrlForTrace, normalizedUrlHash=$normalizedUrlHash)"
    }

    data class ProviderTemplate(
        val provider: ArtworkProviderId,
        val idType: String,
        val mediaId: String,
        val providerPathHash: String?,
        val settingsHash: String?,
        val credentialHash: String?
    ) : ArtworkSource

    data class LocalAsset(val assetKey: ArtworkAssetKey) : ArtworkSource
    data class Placeholder(val placeholderType: PlaceholderType) : ArtworkSource
}
```

`SensitiveArtworkUrl` must never serialize, log, persist, or appear in generated `toString` output.
It may expose the raw value only to the runtime fetch materializer.

## Persisted Decision Model

```kotlin
data class ArtworkDecision(
    val decisionKey: ArtworkDecisionKey,
    val ownerKey: ArtworkOwnerKey,
    val canonicalContentId: ContentIdentity?,
    val imageType: ArtworkType,
    val selectedCandidate: PersistedArtworkCandidate,
    val rejectedCandidates: List<RejectedArtworkCandidate>,
    val policyVersion: Int,
    val imageLanguage: String = "en",
    val settingsHash: String?,
    val credentialHash: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val staleUntilMs: Long?
)
```

```kotlin
data class PersistedArtworkCandidate(
    val provider: ArtworkProviderId?,
    val sourceRole: ArtworkSourceRole,
    val sourceHash: String?,
    val redactedSourceForTrace: String?,
    val providerTemplate: PersistedProviderTemplate?,
    val priority: Int
)
```

```kotlin
data class PersistedProviderTemplate(
    val provider: ArtworkProviderId,
    val imageType: ArtworkType,
    val idType: String,
    val mediaId: String,
    val providerPathHash: String?,
    val settingsHash: String?,
    val credentialHash: String?,
    val imageLanguage: String = "en",
    val policyVersion: Int
)
```

`PersistedProviderTemplate` may contain non-secret media IDs and settings/credential hashes. It must
not contain raw API keys, raw provider URLs, raw auth headers, or raw credentials.

`PersistedArtworkCandidate` is not a fetch request. `ArtworkAssetRepository` obtains fetch material
through `ArtworkSourceMaterializer`:

- provider templates can be reconstructed from safe template inputs
- remote preview URLs are recovered from the owning preview/source payload record by `ownerKey` and
  `sourceHash`
- if fetch material cannot be recovered, `ArtworkRouter` recomputes candidates or falls back

## Asset Model

```kotlin
data class ArtworkAssetRecord(
    val assetKey: ArtworkAssetKey,
    val decisionKey: ArtworkDecisionKey?,
    val provider: ArtworkProviderId?,
    val imageType: ArtworkType,
    val imageLanguage: String = "en",
    val relativePath: String,
    val mimeType: String?,
    val byteCount: Long,
    val sourceHash: String,
    val policyVersion: Int,
    val fetchedAtMs: Long,
    val expiresAtMs: Long,
    val staleUntilMs: Long
)
```

Store cache-relative paths, not durable absolute file paths or durable content URIs. The repository
turns asset records into Android `Uri` values at read time.

## Cache Keys

Decision keys include:

- owner key: canonical content ID or preview item key plus source payload hash
- artwork type
- active artwork provider policy
- premium provider enabled/disabled state
- premium settings hash when relevant
- credential hash when relevant
- `imageLanguage=en`
- decision policy version

Asset keys include:

- provider/source
- artwork type
- canonical content ID, provider template identity, or normalized source URL hash
- image variant/size if relevant
- premium settings hash when relevant
- credential hash when relevant
- `imageLanguage=en`
- asset policy version

Cache keys must not include profile display language, raw profile ID, watched/progress/list state,
raw API keys, raw authorization tokens, raw remote URLs, usernames, or emails. Profile/account scope
may be included only when artwork settings or credentials are truly profile-specific.

Generic remote URL hashes use normalized URLs. Normalization trims input, canonicalizes scheme/host
casing, removes known tracking parameters when safe, preserves cache-busting parameters when they
affect image bytes, and redacts secrets before trace output.

## Routing And Fallback

Poster precedence:

```text
1. active premium provider candidate, if supported
2. primary provider artwork
3. current first-paint source preview artwork
4. other preview artwork
5. placeholder
```

`current first-paint source preview` means addon preview for addon-originated rows and rail preview
for built-in/API rail rows.

Premium provider capability is explicit. Unsupported premium provider candidates are rejected with a
trace reason such as `UNSUPPORTED_ID_TYPE`; RPDB and Top-Posters must not pretend raw Kitsu IDs are
supported.

If selected asset fetch fails:

1. `ArtworkAssetRepository` may serve a stale selected asset when policy allows.
2. If unavailable, `ArtworkRouter` may select the next fallback candidate.
3. If no candidate is available, `FieldResolver` returns placeholder.
4. UI may keep the previously rendered asset until a replacement is ready, but this is display
   continuity and must be trace-labeled; it is not a new artwork decision.

## Invalidation

Changing active premium provider, premium settings, badge style, credential hash, artwork policy
version, or provider capability invalidates affected artwork decisions and affected asset keys. It
must not invalidate TMDB/TVDB/Kitsu metadata caches, identity mappings, profile overlays, episode
metadata, ratings, tracking, or routing.

All metadata artwork uses `imageLanguage=en`. Profile display language must not affect artwork
decision or asset cache keys.

## Legacy Projection

```kotlin
fun ArtworkDisplayRef?.toLegacyString(): String? =
    when (this) {
        null -> null
        is ArtworkDisplayRef.RuntimeAsset ->
            assetKey?.let { "nexio-artwork://asset/${it.value}" }
                ?: "nexio-artwork://decision/${decisionKey.value}"
        is ArtworkDisplayRef.Placeholder ->
            "nexio-placeholder://${placeholderType.name.lowercase()}"
    }
```

No provider mapper, adapter, resolver, or UI model may assign a raw remote provider URL directly to
a compatibility artwork string.
