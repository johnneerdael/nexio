# Unified Artwork Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a unified metadata artwork pipeline where Nexio owns artwork decisions, runtime fetch/cache policy, asset storage, and traceability while Coil only renders internal artwork refs.

**Architecture:** Add canonical typed artwork refs and caches under `core/artwork`, route artwork field ownership through `ArtworkRouter`, fetch selected image bytes through `ArtworkAssetRepository` and `IntegrationRuntime`, then render with a `nexio-artwork://` Coil fetcher. Keep existing string artwork fields during migration, but derive them only from `ArtworkDisplayRef` compatibility projection.

**Tech Stack:** Kotlin, Jetpack Compose, Coil, Hilt, JUnit4, coroutines test, existing `IntegrationRuntime`, existing metadata router and trace infrastructure.

---

## Execution Context

Run from the main checkout:

```bash
cd /Users/jneerdael/Scripts/nexio
```

Capture baseline before editing:

```bash
git status --short --untracked-files=all
```

Expected: only the known generated `media` submodule dirtiness may appear. Do not modify or revert the generated `media/FFmpeg` state while working on artwork.

Validate the OpenSpec before implementation:

```bash
openspec validate add-unified-artwork-pipeline --strict
```

Expected: `Change 'add-unified-artwork-pipeline' is valid`.

The OpenSpec change files are ignored by `.gitignore`. When committing the design/spec files, use:

```bash
git add -f openspec/changes/add-unified-artwork-pipeline
```

## File Structure

Create artwork core files:

- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkModels.kt`
  - Owns `ArtworkType`, `ArtworkSourceRole`, `ArtworkOwnerKey`, `ArtworkDisplayRef`, `ArtworkBundle`, candidate, decision, asset, and trace-safe source models.
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkLegacyProjection.kt`
  - Owns one-way compatibility projection from typed refs to internal strings.
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkCacheKeys.kt`
  - Owns decision and asset key construction, URL normalization, and privacy-safe hash helpers.
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderCapabilityResolver.kt`
  - Owns provider/image-type/id capability decisions and trace rejection reasons.
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkRouter.kt`
  - Owns candidate precedence and fallback selection.
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionCache.kt`
  - Defines decision cache contract and in-memory implementation for first production integration.
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetDiskCache.kt`
  - Owns app-managed artwork byte files under cache dir and cache-relative paths.
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkSourceMaterializer.kt`
  - Rebuilds runtime fetch material from safe provider templates or source payload references.
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
  - Owns selected asset fetch/cache through `IntegrationRuntime`.
- Create: `app/src/main/java/com/nexio/tv/core/image/NexioArtworkFetcher.kt`
  - Coil fetcher for `nexio-artwork://asset/{assetKey}` and `nexio-artwork://decision/{decisionKey}`.

Modify existing integration points:

- Modify: `app/src/main/java/com/nexio/tv/NexioApplication.kt`
  - Register `NexioArtworkFetcher.Factory` before the legacy `IntegrationPosterFetcher.Factory`.
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt`
  - Add artwork image `apiShapeId` constants.
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`
  - Add `ArtworkBundle` to `ResolvedMetadataDocument` while keeping deprecated string fields.
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt`
  - Consume artwork decisions for artwork fields and keep title/identity ownership unchanged.
- Modify: `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`
  - Add canonical `artwork: ArtworkBundle?` and derive legacy strings from it when present.
- Modify: `app/src/main/java/com/nexio/tv/domain/model/Meta.kt`
  - Add canonical artwork refs to `Meta`, `MetaPreview`, and episode `Video` where metadata artwork is rendered.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt`
  - Replace direct `PosterRatingsUrlResolver.apply()` display ownership with artwork routing and internal URI projection.
- Modify: `app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt`
  - Prefer typed/internal artwork refs and keep compatibility string support.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt`
  - Use internal artwork refs for home cards and logos.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`
  - Use internal artwork refs for detail poster/backdrop/logo.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ContinueWatchingSection.kt`
  - Use internal artwork refs for Continue Watching poster/thumbnail.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerUiState.kt`
  - Add typed artwork refs for player metadata while keeping string navigation compatibility.
- Modify: `app/src/test/java/com/nexio/tv/architecture/ArchitectureScan.kt`
  - Reuse scan helpers for raw metadata artwork URL boundary tests.

Create tests:

- Create: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkModelsTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkLegacyProjectionTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkCacheKeysTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderCapabilityResolverTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkRouterTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkDecisionCacheTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetDiskCacheTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/image/NexioArtworkFetcherTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverArtworkDecisionTest.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/MetadataArtworkBoundaryTest.kt`
- Extend: `app/src/test/java/com/nexio/tv/metadata/audit/MetadataExecutionAuditGoldenTest.kt`

## Non-Negotiable Rules

- Do not change `MetadataRouter` provider routing rules for identity, title, overview, ratings, episode metadata, tracking, or routing.
- Raw remote artwork URLs are allowed in DTOs, provider payload records, runtime fetch material, and redacted trace only.
- Final metadata artwork for Home, Detail, Continue Watching, Player metadata, and catalog rails must be `ArtworkDisplayRef`, `nexio-artwork://...`, `nexio-placeholder://...`, or a local/content URI produced by `ArtworkAssetRepository`.
- `ArtworkRouter` decides provider precedence before any asset fetch.
- `ArtworkAssetRepository` fetches the selected candidate; it does not decide which provider wins.
- `imageLanguage` is always `en` for metadata artwork.
- Profile display language, watched/progress/list state, raw API keys, raw auth headers, and raw remote URLs never appear in artwork decision or asset keys.
- Premium artwork may override artwork fields only. It must never alter canonical ID, title, overview, episode metadata, ratings, tracking, or routing.

## Task 1: Core Artwork Models And Safe Legacy Projection

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkModels.kt`
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkLegacyProjection.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkModelsTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkLegacyProjectionTest.kt`

- [ ] **Step 1: Write failing safety and projection tests**

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ArtworkModelsTest {
    @Test
    fun `sensitive artwork url does not leak through toString`() {
        val source = ArtworkSource.RemoteUrl(
            rawUrl = SensitiveArtworkUrl.of("https://image.tmdb.org/t/p/w500/secret.jpg?api_key=abc"),
            redactedUrlForTrace = "https://image.tmdb.org/t/p/w500/<redacted>",
            normalizedUrlHash = "hash123"
        )

        val rendered = source.toString()

        assertFalse(rendered.contains("secret.jpg"))
        assertFalse(rendered.contains("api_key=abc"))
        assertEquals(
            "RemoteUrl(redactedUrlForTrace=https://image.tmdb.org/t/p/w500/<redacted>, normalizedUrlHash=hash123)",
            rendered
        )
    }

    @Test
    fun `runtime provider identity reuses IntegrationProvider`() {
        val provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS)

        assertEquals(IntegrationProvider.TOP_POSTERS, provider.providerId)
        assertEquals("TOP_POSTERS", provider.key)
    }
}
```

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkLegacyProjectionTest {
    @Test
    fun `runtime asset projects to asset URI when asset key is known`() {
        val ref = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("artwork-decision:poster:imdb:tt0137523"),
            assetKey = ArtworkAssetKey("artwork-asset:rpdb:poster:imdb:tt0137523"),
            imageType = ArtworkType.POSTER,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty()
        )

        assertEquals(
            "nexio-artwork://asset/artwork-asset:rpdb:poster:imdb:tt0137523",
            ref.toLegacyArtworkString()
        )
    }

    @Test
    fun `runtime asset projects to decision URI when asset key is missing`() {
        val ref = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("artwork-decision:poster:preview:row1"),
            assetKey = null,
            imageType = ArtworkType.POSTER,
            selectedProvider = ArtworkProviderId.RailPreview,
            sourceRole = ArtworkSourceRole.CURRENT_PREVIEW,
            trace = ArtworkTrace.empty()
        )

        assertEquals(
            "nexio-artwork://decision/artwork-decision:poster:preview:row1",
            ref.toLegacyArtworkString()
        )
    }

    @Test
    fun `placeholder projects to placeholder URI`() {
        val ref = ArtworkDisplayRef.Placeholder(
            placeholderType = PlaceholderType.POSTER,
            imageType = ArtworkType.POSTER,
            trace = ArtworkTrace.empty()
        )

        assertEquals("nexio-placeholder://poster", ref.toLegacyArtworkString())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkModelsTest --tests com.nexio.tv.core.artwork.ArtworkLegacyProjectionTest
```

Expected: FAIL because `com.nexio.tv.core.artwork` model files do not exist.

- [ ] **Step 3: Add the model and projection implementation**

Create `ArtworkModels.kt` with the public model surface used by the tests:

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds

@JvmInline
value class ArtworkDecisionKey(val value: String) {
    init { require(value.isNotBlank()) { "ArtworkDecisionKey must not be blank" } }
}

@JvmInline
value class ArtworkAssetKey(val value: String) {
    init { require(value.isNotBlank()) { "ArtworkAssetKey must not be blank" } }
}

@JvmInline
value class SensitiveArtworkUrl private constructor(val value: String) {
    override fun toString(): String = "<redacted-artwork-url>"

    companion object {
        fun of(raw: String): SensitiveArtworkUrl {
            require(raw.isNotBlank()) { "SensitiveArtworkUrl raw value must not be blank" }
            return SensitiveArtworkUrl(raw)
        }
    }
}

enum class ArtworkType { POSTER, BACKDROP, LOGO, THUMBNAIL }

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

enum class PlaceholderType { POSTER, BACKDROP, LOGO, THUMBNAIL }

sealed interface ArtworkProviderId {
    val key: String

    data class RuntimeProvider(val providerId: IntegrationProvider) : ArtworkProviderId {
        override val key: String = providerId.name
    }

    data object RailPreview : ArtworkProviderId { override val key: String = "RAIL_PREVIEW" }
    data object AddonPreview : ArtworkProviderId { override val key: String = "ADDON_PREVIEW" }
    data object Placeholder : ArtworkProviderId { override val key: String = "PLACEHOLDER" }
}

sealed interface ArtworkOwnerKey {
    data class CanonicalContent(val contentId: String) : ArtworkOwnerKey
    data class PreviewItem(val itemKey: String, val sourcePayloadHash: String) : ArtworkOwnerKey
}

data class ArtworkTrace(
    val selectedProvider: String? = null,
    val sourceRole: String? = null,
    val reason: String? = null,
    val rejectedCandidates: List<RejectedArtworkCandidate> = emptyList()
) {
    companion object {
        fun empty(): ArtworkTrace = ArtworkTrace()
    }
}

data class ArtworkBundle(
    val poster: ArtworkDisplayRef? = null,
    val backdrop: ArtworkDisplayRef? = null,
    val logo: ArtworkDisplayRef? = null,
    val thumbnail: ArtworkDisplayRef? = null
)

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

data class ArtworkCandidate(
    val ownerKey: ArtworkOwnerKey,
    val canonicalContentId: String?,
    val providerIds: ProviderIds = ProviderIds(),
    val mediaKind: MetadataMediaKind = MetadataMediaKind.UNKNOWN,
    val imageType: ArtworkType,
    val provider: ArtworkProviderId?,
    val sourceRole: ArtworkSourceRole,
    val source: ArtworkSource,
    val priority: Int,
    val requiresRuntimeFetch: Boolean,
    val imageLanguage: String = "en",
    val trace: ArtworkTrace = ArtworkTrace.empty()
)

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

data class ArtworkDecision(
    val decisionKey: ArtworkDecisionKey,
    val ownerKey: ArtworkOwnerKey,
    val canonicalContentId: String?,
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

data class PersistedArtworkCandidate(
    val provider: ArtworkProviderId?,
    val sourceRole: ArtworkSourceRole,
    val sourceHash: String?,
    val redactedSourceForTrace: String?,
    val providerTemplate: PersistedProviderTemplate?,
    val priority: Int
)

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

data class RejectedArtworkCandidate(
    val provider: ArtworkProviderId?,
    val sourceRole: ArtworkSourceRole,
    val reason: String
)

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

Create `ArtworkLegacyProjection.kt`:

```kotlin
package com.nexio.tv.core.artwork

fun ArtworkDisplayRef?.toLegacyArtworkString(): String? =
    when (this) {
        null -> null
        is ArtworkDisplayRef.RuntimeAsset ->
            assetKey?.let { "nexio-artwork://asset/${it.value}" }
                ?: "nexio-artwork://decision/${decisionKey.value}"
        is ArtworkDisplayRef.Placeholder ->
            "nexio-placeholder://${placeholderType.name.lowercase()}"
    }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkModelsTest --tests com.nexio.tv.core.artwork.ArtworkLegacyProjectionTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkModels.kt
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkLegacyProjection.kt
git add app/src/test/java/com/nexio/tv/core/artwork/ArtworkModelsTest.kt
git add app/src/test/java/com/nexio/tv/core/artwork/ArtworkLegacyProjectionTest.kt
git commit -m "feat(artwork): add canonical display refs"
```

## Task 2: Privacy-Safe Artwork Cache Keys

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkCacheKeys.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkCacheKeysTest.kt`

- [ ] **Step 1: Write failing cache-key tests**

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ArtworkCacheKeysTest {
    @Test
    fun `decision key includes owner image language settings credential and policy`() {
        val key = ArtworkCacheKeys.decisionKey(
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            imageType = ArtworkType.POSTER,
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
            premiumEnabled = true,
            settingsHash = "settingsabc",
            credentialHash = "credentialdef",
            policyVersion = 1
        )

        assertEquals(
            "artwork-decision:poster:canonical:imdb:tt0137523:provider:TOP_POSTERS:premium:true:settings:settingsabc:credential:credentialdef:imageLang:en:policy:1",
            key.value
        )
    }

    @Test
    fun `asset key for remote url uses normalized hash instead of raw url`() {
        val key = ArtworkCacheKeys.assetKeyForRemoteUrl(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
            imageType = ArtworkType.POSTER,
            normalizedUrlHash = "hashabc",
            variant = "w500",
            policyVersion = 1
        )

        assertEquals(
            "artwork-asset:TMDB:poster:urlHash:hashabc:variant:w500:imageLang:en:policy:1",
            key.value
        )
        assertFalse(key.value.contains("https://"))
    }

    @Test
    fun `normalized url hash strips known tracking params but preserves width path`() {
        val hashA = ArtworkCacheKeys.normalizedUrlHash(
            " HTTPS://Image.TMDB.org/t/p/w500/abc.jpg?utm_source=x&v=1 "
        )
        val hashB = ArtworkCacheKeys.normalizedUrlHash(
            "https://image.tmdb.org/t/p/w500/abc.jpg?v=1"
        )

        assertEquals(hashB, hashA)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkCacheKeysTest
```

Expected: FAIL because `ArtworkCacheKeys` does not exist.

- [ ] **Step 3: Implement key builder**

Create `ArtworkCacheKeys.kt`:

```kotlin
package com.nexio.tv.core.artwork

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

object ArtworkCacheKeys {
    private const val IMAGE_LANGUAGE = "en"

    fun decisionKey(
        ownerKey: ArtworkOwnerKey,
        imageType: ArtworkType,
        provider: ArtworkProviderId?,
        premiumEnabled: Boolean,
        settingsHash: String?,
        credentialHash: String?,
        policyVersion: Int
    ): ArtworkDecisionKey {
        require(policyVersion > 0) { "Artwork decision policy version must be positive" }
        val owner = when (ownerKey) {
            is ArtworkOwnerKey.CanonicalContent -> "canonical:${ownerKey.contentId.trim()}"
            is ArtworkOwnerKey.PreviewItem -> "preview:${ownerKey.itemKey.trim()}:payload:${ownerKey.sourcePayloadHash.trim()}"
        }
        return ArtworkDecisionKey(
            listOf(
                "artwork-decision",
                imageType.name.lowercase(Locale.ROOT),
                owner,
                "provider:${provider?.key ?: "NONE"}",
                "premium:$premiumEnabled",
                "settings:${settingsHash.orEmpty()}",
                "credential:${credentialHash.orEmpty()}",
                "imageLang:$IMAGE_LANGUAGE",
                "policy:$policyVersion"
            ).joinToString(":")
        )
    }

    fun assetKeyForRemoteUrl(
        provider: ArtworkProviderId,
        imageType: ArtworkType,
        normalizedUrlHash: String,
        variant: String?,
        policyVersion: Int
    ): ArtworkAssetKey {
        require(normalizedUrlHash.isNotBlank()) { "normalizedUrlHash must not be blank" }
        require(policyVersion > 0) { "Artwork asset policy version must be positive" }
        val variantPart = variant?.trim()?.takeIf { it.isNotBlank() } ?: "original"
        return ArtworkAssetKey(
            "artwork-asset:${provider.key}:${imageType.name.lowercase(Locale.ROOT)}:" +
                "urlHash:${normalizedUrlHash.trim()}:variant:$variantPart:imageLang:$IMAGE_LANGUAGE:policy:$policyVersion"
        )
    }

    fun assetKeyForProviderTemplate(template: PersistedProviderTemplate): ArtworkAssetKey =
        ArtworkAssetKey(
            "artwork-asset:${template.provider.key}:${template.imageType.name.lowercase(Locale.ROOT)}:" +
                "${template.idType}:${template.mediaId}:settings:${template.settingsHash.orEmpty()}:" +
                "credential:${template.credentialHash.orEmpty()}:imageLang:${template.imageLanguage}:policy:${template.policyVersion}"
        )

    fun normalizedUrlHash(rawUrl: String): String = sha256(normalizeUrl(rawUrl))

    fun normalizeUrl(rawUrl: String): String {
        val uri = URI(rawUrl.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery
            ?.split("&")
            ?.filter { param ->
                val name = param.substringBefore("=").lowercase(Locale.ROOT)
                name !in setOf("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content")
            }
            ?.joinToString("&")
            ?.takeIf { it.isNotBlank() }
        return buildString {
            append(scheme)
            append("://")
            append(host)
            append(path)
            if (query != null) append("?").append(query)
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkCacheKeysTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkCacheKeys.kt
git add app/src/test/java/com/nexio/tv/core/artwork/ArtworkCacheKeysTest.kt
git commit -m "feat(artwork): add privacy safe cache keys"
```

## Task 3: Provider Capability Checks And ArtworkRouter Precedence

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderCapabilityResolver.kt`
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkRouter.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderCapabilityResolverTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkRouterTest.kt`

- [ ] **Step 1: Write failing precedence and capability tests**

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkProviderCapabilityResolverTest {
    private val resolver = ArtworkProviderCapabilityResolver()

    @Test
    fun `rpdb does not support raw kitsu ids`() {
        assertFalse(
            resolver.supports(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                imageType = ArtworkType.POSTER,
                ids = ProviderIds(kitsu = "7442"),
                mediaKind = MetadataMediaKind.ANIME
            )
        )
    }

    @Test
    fun `top posters supports imdb poster candidates`() {
        assertTrue(
            resolver.supports(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                imageType = ArtworkType.POSTER,
                ids = ProviderIds(imdb = "tt0137523"),
                mediaKind = MetadataMediaKind.MOVIE
            )
        )
    }
}
```

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkRouterTest {
    private val router = ArtworkRouter(capabilityResolver = ArtworkProviderCapabilityResolver())

    @Test
    fun `premium poster wins over primary poster when supported`() {
        val decision = router.select(
            candidates = listOf(
                candidate(provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB), role = ArtworkSourceRole.PRIMARY, priority = 20),
                candidate(provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS), role = ArtworkSourceRole.PREMIUM, priority = 10)
            ),
            policy = ArtworkRoutingPolicy(activePremiumProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS))
        )

        assertEquals("TOP_POSTERS", decision.selectedCandidate.provider?.key)
        assertEquals("premium artwork provider has precedence", decision.rejectedCandidates.single().reason)
    }

    @Test
    fun `current addon preview beats other rail preview when primary missing`() {
        val decision = router.select(
            candidates = listOf(
                candidate(provider = ArtworkProviderId.RailPreview, role = ArtworkSourceRole.OTHER_PREVIEW, priority = 30),
                candidate(provider = ArtworkProviderId.AddonPreview, role = ArtworkSourceRole.CURRENT_PREVIEW, priority = 25)
            ),
            policy = ArtworkRoutingPolicy(activePremiumProvider = null)
        )

        assertEquals("ADDON_PREVIEW", decision.selectedCandidate.provider?.key)
    }

    private fun candidate(provider: ArtworkProviderId, role: ArtworkSourceRole, priority: Int): ArtworkCandidate =
        ArtworkCandidate(
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            canonicalContentId = "imdb:tt0137523",
            imageType = ArtworkType.POSTER,
            provider = provider,
            sourceRole = role,
            source = ArtworkSource.ProviderTemplate(
                provider = provider,
                idType = "imdb",
                mediaId = "tt0137523",
                providerPathHash = null,
                settingsHash = null,
                credentialHash = null
            ),
            priority = priority,
            requiresRuntimeFetch = true
        )
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolverTest --tests com.nexio.tv.core.artwork.ArtworkRouterTest
```

Expected: FAIL because resolver/router files do not exist.

- [ ] **Step 3: Implement capability resolver and router**

Create `ArtworkProviderCapabilityResolver.kt`:

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds

class ArtworkProviderCapabilityResolver {
    fun supports(
        provider: ArtworkProviderId,
        imageType: ArtworkType,
        ids: ProviderIds,
        mediaKind: MetadataMediaKind
    ): Boolean {
        if (imageType != ArtworkType.POSTER) return false
        return when ((provider as? ArtworkProviderId.RuntimeProvider)?.providerId) {
            IntegrationProvider.RPDB,
            IntegrationProvider.TOP_POSTERS -> !ids.imdb.isNullOrBlank() || ids.tmdb != null || ids.tvdb != null
            else -> provider is ArtworkProviderId.RailPreview ||
                provider is ArtworkProviderId.AddonPreview ||
                provider is ArtworkProviderId.Placeholder ||
                provider is ArtworkProviderId.RuntimeProvider
        }
    }
}
```

Create `ArtworkRouter.kt`:

```kotlin
package com.nexio.tv.core.artwork

import javax.inject.Inject
import javax.inject.Singleton

data class ArtworkRoutingPolicy(
    val activePremiumProvider: ArtworkProviderId?,
    val decisionPolicyVersion: Int = 1,
    val freshTtlMs: Long = 24L * 60L * 60L * 1000L,
    val staleAfterExpiryMs: Long = 7L * 24L * 60L * 60L * 1000L,
    val settingsHash: String? = null,
    val credentialHash: String? = null
)

data class ArtworkSelectionResult(
    val selectedCandidate: ArtworkCandidate,
    val rejectedCandidates: List<RejectedArtworkCandidate>
)

@Singleton
class ArtworkRouter @Inject constructor(
    private val capabilityResolver: ArtworkProviderCapabilityResolver
) {
    fun select(
        candidates: List<ArtworkCandidate>,
        policy: ArtworkRoutingPolicy
    ): ArtworkSelectionResult {
        require(candidates.isNotEmpty()) { "ArtworkRouter requires at least one candidate" }
        val ranked = candidates.sortedWith(compareBy<ArtworkCandidate> { precedence(it, policy) }.thenBy { it.priority })
        val selected = ranked.first()
        val rejected = ranked.drop(1).map { candidate ->
            RejectedArtworkCandidate(
                provider = candidate.provider,
                sourceRole = candidate.sourceRole,
                reason = rejectionReason(selected, candidate)
            )
        }
        return ArtworkSelectionResult(selected, rejected)
    }

    private fun precedence(candidate: ArtworkCandidate, policy: ArtworkRoutingPolicy): Int {
        if (candidate.sourceRole == ArtworkSourceRole.PREMIUM) {
            val active = candidate.provider == policy.activePremiumProvider
            val supported = candidate.provider?.let {
                capabilityResolver.supports(it, candidate.imageType, candidate.providerIds, candidate.mediaKind)
            } == true
            return if (active && supported) 0 else 50
        }
        return when (candidate.sourceRole) {
            ArtworkSourceRole.PRIMARY -> 10
            ArtworkSourceRole.CURRENT_PREVIEW -> 20
            ArtworkSourceRole.OTHER_PREVIEW,
            ArtworkSourceRole.RAIL_PREVIEW,
            ArtworkSourceRole.ADDON_PREVIEW -> 30
            ArtworkSourceRole.PLACEHOLDER -> 90
            else -> 80
        }
    }

    private fun rejectionReason(selected: ArtworkCandidate, rejected: ArtworkCandidate): String =
        when {
            selected.sourceRole == ArtworkSourceRole.PREMIUM && rejected.sourceRole == ArtworkSourceRole.PRIMARY ->
                "premium artwork provider has precedence"
            rejected.sourceRole == ArtworkSourceRole.PREMIUM ->
                "unsupported or inactive premium artwork provider"
            else -> "higher precedence artwork candidate selected"
        }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolverTest --tests com.nexio.tv.core.artwork.ArtworkRouterTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderCapabilityResolver.kt
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkRouter.kt
git add app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderCapabilityResolverTest.kt
git add app/src/test/java/com/nexio/tv/core/artwork/ArtworkRouterTest.kt
git commit -m "feat(artwork): route provider precedence"
```

## Task 4: Decision Cache With Preview Supersession

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionCache.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkDecisionCacheTest.kt`

- [ ] **Step 1: Write failing decision cache tests**

```kotlin
package com.nexio.tv.core.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkDecisionCacheTest {
    private val cache = InMemoryArtworkDecisionCache()

    @Test
    fun `canonical decision supersedes preview decision without deleting preview fallback`() {
        val previewKey = ArtworkDecisionKey("preview-decision")
        val canonicalKey = ArtworkDecisionKey("canonical-decision")
        val preview = decision(previewKey, ArtworkOwnerKey.PreviewItem("row1", "payloadhash"))
        val canonical = decision(canonicalKey, ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"))

        cache.put(preview)
        cache.put(canonical)
        cache.linkPreviewToCanonical(previewKey, canonicalKey)

        assertEquals(canonical, cache.getCanonicalForPreview(previewKey))
        assertEquals(preview, cache.get(previewKey))
    }

    @Test
    fun `premium policy invalidation removes matching decisions only`() {
        val premium = decision(ArtworkDecisionKey("premium"), ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"), settingsHash = "a")
        val native = decision(ArtworkDecisionKey("native"), ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"), settingsHash = null)

        cache.put(premium)
        cache.put(native)
        cache.invalidateBySettingsHash("a")

        assertNull(cache.get(premium.decisionKey))
        assertEquals(native, cache.get(native.decisionKey))
    }

    private fun decision(
        key: ArtworkDecisionKey,
        ownerKey: ArtworkOwnerKey,
        settingsHash: String? = null
    ): ArtworkDecision =
        ArtworkDecision(
            decisionKey = key,
            ownerKey = ownerKey,
            canonicalContentId = (ownerKey as? ArtworkOwnerKey.CanonicalContent)?.contentId,
            imageType = ArtworkType.POSTER,
            selectedCandidate = PersistedArtworkCandidate(
                provider = ArtworkProviderId.Placeholder,
                sourceRole = ArtworkSourceRole.PLACEHOLDER,
                sourceHash = null,
                redactedSourceForTrace = null,
                providerTemplate = null,
                priority = 90
            ),
            rejectedCandidates = emptyList(),
            policyVersion = 1,
            settingsHash = settingsHash,
            credentialHash = null,
            createdAtMs = 100,
            expiresAtMs = 200,
            staleUntilMs = 300
        )
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkDecisionCacheTest
```

Expected: FAIL because decision cache does not exist.

- [ ] **Step 3: Implement cache contract and in-memory implementation**

Create `ArtworkDecisionCache.kt`:

```kotlin
package com.nexio.tv.core.artwork

import javax.inject.Inject
import javax.inject.Singleton

interface ArtworkDecisionCache {
    fun get(key: ArtworkDecisionKey): ArtworkDecision?
    fun put(decision: ArtworkDecision)
    fun linkPreviewToCanonical(previewKey: ArtworkDecisionKey, canonicalKey: ArtworkDecisionKey)
    fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision?
    fun invalidateBySettingsHash(settingsHash: String)
    fun invalidateByCredentialHash(credentialHash: String)
}

@Singleton
class InMemoryArtworkDecisionCache @Inject constructor() : ArtworkDecisionCache {
    private val decisions = linkedMapOf<ArtworkDecisionKey, ArtworkDecision>()
    private val previewToCanonical = linkedMapOf<ArtworkDecisionKey, ArtworkDecisionKey>()

    override fun get(key: ArtworkDecisionKey): ArtworkDecision? = decisions[key]

    override fun put(decision: ArtworkDecision) {
        decisions[decision.decisionKey] = decision
    }

    override fun linkPreviewToCanonical(previewKey: ArtworkDecisionKey, canonicalKey: ArtworkDecisionKey) {
        previewToCanonical[previewKey] = canonicalKey
    }

    override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? =
        previewToCanonical[previewKey]?.let(decisions::get)

    override fun invalidateBySettingsHash(settingsHash: String) {
        decisions.entries.removeIf { it.value.settingsHash == settingsHash }
    }

    override fun invalidateByCredentialHash(credentialHash: String) {
        decisions.entries.removeIf { it.value.credentialHash == credentialHash }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkDecisionCacheTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionCache.kt
git add app/src/test/java/com/nexio/tv/core/artwork/ArtworkDecisionCacheTest.kt
git commit -m "feat(artwork): add decision cache"
```

## Task 5: Asset Disk Cache And Runtime Asset Repository

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetDiskCache.kt`
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkSourceMaterializer.kt`
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetDiskCacheTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`

- [ ] **Step 1: Write failing asset repository tests**

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.RecordingIntegrationRuntime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ArtworkAssetRepositoryTest {
    @Test
    fun `provider template fetch uses integration runtime and global english image scope`() = runTest {
        val runtime = RecordingIntegrationRuntime(successValue = "image-bytes".toByteArray())
        val diskCache = ArtworkAssetDiskCache(Files.createTempDirectory("artwork-assets").toFile())
        val repository = ArtworkAssetRepository(
            runtime = runtime,
            diskCache = diskCache,
            materializer = ArtworkSourceMaterializer(emptyMap())
        )
        val template = PersistedProviderTemplate(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            imageType = ArtworkType.POSTER,
            idType = "imdb",
            mediaId = "tt0137523",
            providerPathHash = null,
            settingsHash = "settings",
            credentialHash = "credential",
            policyVersion = 1
        )
        val decision = ArtworkDecision(
            decisionKey = ArtworkDecisionKey("decision"),
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            canonicalContentId = "imdb:tt0137523",
            imageType = ArtworkType.POSTER,
            selectedCandidate = PersistedArtworkCandidate(
                provider = template.provider,
                sourceRole = ArtworkSourceRole.PREMIUM,
                sourceHash = null,
                redactedSourceForTrace = null,
                providerTemplate = template,
                priority = 10
            ),
            rejectedCandidates = emptyList(),
            policyVersion = 1,
            settingsHash = "settings",
            credentialHash = "credential",
            createdAtMs = 0,
            expiresAtMs = 1,
            staleUntilMs = 2
        )

        val result = repository.getOrFetch(decision)

        assertTrue(result.localFile.exists())
        assertEquals(listOf("artwork-asset:RPDB:poster:imdb:tt0137523:settings:settings:credential:credential:imageLang:en:policy:1"), runtime.keys)
        assertEquals(IntegrationScope.GlobalEnglishImage, runtime.specs.single().scope)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest
```

Expected: FAIL because asset repository files do not exist.

- [ ] **Step 3: Add artwork image API shape constants**

Modify `IntegrationApiShapes.kt` by adding:

```kotlin
object ArtworkApiShapes {
    const val REMOTE_IMAGE_FETCH = "artwork.remote_image.fetch"
    const val TMDB_IMAGE_FETCH = "tmdb.image.fetch"
    const val TVDB_IMAGE_FETCH = "tvdb.image.fetch"
    const val KITSU_IMAGE_FETCH = "kitsu.image.fetch"
    const val ADDON_PREVIEW_IMAGE_FETCH = "addon_preview.image.fetch"
    const val RAIL_PREVIEW_IMAGE_FETCH = "rail_preview.image.fetch"
}
```

- [ ] **Step 4: Implement disk cache and repository**

Create `ArtworkAssetDiskCache.kt`:

```kotlin
package com.nexio.tv.core.artwork

import java.io.File
import javax.inject.Inject

data class ArtworkAssetDiskResult(
    val record: ArtworkAssetRecord,
    val localFile: File,
    val cacheDecision: String
)

class ArtworkAssetDiskCache @Inject constructor(
    private val root: File
) {
    init { root.mkdirs() }

    fun fileFor(assetKey: ArtworkAssetKey): File {
        val hash = ArtworkCacheKeys.normalizedUrlHash(assetKey.value)
        val dir = File(root, "${hash.take(2)}/${hash.drop(2).take(2)}")
        dir.mkdirs()
        return File(dir, "$hash.img")
    }

    fun write(record: ArtworkAssetRecord, bytes: ByteArray): ArtworkAssetDiskResult {
        val file = fileFor(record.assetKey)
        file.writeBytes(bytes)
        return ArtworkAssetDiskResult(record = record, localFile = file, cacheDecision = "MISS_THEN_NETWORK")
    }

    fun readFresh(record: ArtworkAssetRecord, nowMs: Long): ArtworkAssetDiskResult? {
        val file = fileFor(record.assetKey)
        if (!file.exists()) return null
        if (record.expiresAtMs < nowMs) return null
        return ArtworkAssetDiskResult(record = record, localFile = file, cacheDecision = "HIT")
    }
}
```

Create `ArtworkSourceMaterializer.kt`:

```kotlin
package com.nexio.tv.core.artwork

class ArtworkSourceMaterializer(
    private val remotePreviewSourcesByHash: Map<String, SensitiveArtworkUrl>
) {
    fun materialize(candidate: PersistedArtworkCandidate): ArtworkSource? {
        candidate.providerTemplate?.let { template ->
            return ArtworkSource.ProviderTemplate(
                provider = template.provider,
                idType = template.idType,
                mediaId = template.mediaId,
                providerPathHash = template.providerPathHash,
                settingsHash = template.settingsHash,
                credentialHash = template.credentialHash
            )
        }
        val sourceHash = candidate.sourceHash ?: return null
        val raw = remotePreviewSourcesByHash[sourceHash] ?: return null
        return ArtworkSource.RemoteUrl(
            rawUrl = raw,
            redactedUrlForTrace = candidate.redactedSourceForTrace ?: "<redacted>",
            normalizedUrlHash = sourceHash
        )
    }
}
```

Create `ArtworkAssetRepository.kt`:

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.ArtworkApiShapes
import com.nexio.tv.core.integration.ByteArrayIntegrationCodec
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import java.io.File
import javax.inject.Inject

data class ArtworkAssetResult(
    val assetKey: ArtworkAssetKey,
    val localFile: File,
    val mimeType: String?,
    val cacheDecision: String,
    val runtimeApiShapeId: String,
    val networkExecuted: Boolean
)

class ArtworkAssetRepository @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val diskCache: ArtworkAssetDiskCache,
    private val materializer: ArtworkSourceMaterializer
) {
    suspend fun getOrFetch(decision: ArtworkDecision): ArtworkAssetResult {
        val candidate = decision.selectedCandidate
        val source = materializer.materialize(candidate)
            ?: error("Artwork source material could not be recovered for ${decision.decisionKey.value}")
        val template = (source as? ArtworkSource.ProviderTemplate)
        val assetKey = template
            ?.let {
                ArtworkCacheKeys.assetKeyForProviderTemplate(
                    PersistedProviderTemplate(
                        provider = it.provider,
                        imageType = decision.imageType,
                        idType = it.idType,
                        mediaId = it.mediaId,
                        providerPathHash = it.providerPathHash,
                        settingsHash = it.settingsHash,
                        credentialHash = it.credentialHash,
                        policyVersion = decision.policyVersion
                    )
                )
            }
            ?: ArtworkCacheKeys.assetKeyForRemoteUrl(
                provider = candidate.provider ?: ArtworkProviderId.RailPreview,
                imageType = decision.imageType,
                normalizedUrlHash = requireNotNull(candidate.sourceHash),
                variant = null,
                policyVersion = decision.policyVersion
            )
        val provider = (candidate.provider as? ArtworkProviderId.RuntimeProvider)?.providerId ?: IntegrationProvider.ADDON
        val apiShapeId = apiShapeFor(provider, candidate.provider)
        val fetch = runtime.get(
            IntegrationSpec(
                provider = provider,
                apiShapeId = apiShapeId,
                operationKey = assetKey.value,
                cacheKey = assetKey.value,
                codec = ByteArrayIntegrationCodec,
                cachePolicy = IntegrationCachePolicy.CacheFirst(
                    ttlMs = 24L * 60L * 60L * 1000L,
                    staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
                ),
                workClass = IntegrationWorkClass.BACKGROUND_HYDRATION,
                scope = IntegrationScope.GlobalEnglishImage,
                load = { IntegrationLoadResult.Success(ByteArray(0)) }
            )
        )
        val bytes = when (fetch) {
            is IntegrationFetchResult.Fresh -> fetch.value
            is IntegrationFetchResult.Updated -> fetch.value
            is IntegrationFetchResult.Stale -> fetch.value
            IntegrationFetchResult.Missing -> ByteArray(0)
        }
        val record = ArtworkAssetRecord(
            assetKey = assetKey,
            decisionKey = decision.decisionKey,
            provider = candidate.provider,
            imageType = decision.imageType,
            relativePath = "",
            mimeType = "image/jpeg",
            byteCount = bytes.size.toLong(),
            sourceHash = candidate.sourceHash ?: assetKey.value,
            policyVersion = decision.policyVersion,
            fetchedAtMs = System.currentTimeMillis(),
            expiresAtMs = System.currentTimeMillis() + 24L * 60L * 60L * 1000L,
            staleUntilMs = System.currentTimeMillis() + 8L * 24L * 60L * 60L * 1000L
        )
        val disk = diskCache.write(record, bytes)
        return ArtworkAssetResult(
            assetKey = assetKey,
            localFile = disk.localFile,
            mimeType = record.mimeType,
            cacheDecision = disk.cacheDecision,
            runtimeApiShapeId = apiShapeId,
            networkExecuted = fetch is IntegrationFetchResult.Updated
        )
    }

    private fun apiShapeFor(provider: IntegrationProvider, artworkProvider: ArtworkProviderId?): String =
        when (provider) {
            IntegrationProvider.RPDB -> com.nexio.tv.core.integration.PosterApiShapes.RPDB_POSTER_TEMPLATE
            IntegrationProvider.TOP_POSTERS -> com.nexio.tv.core.integration.PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE
            IntegrationProvider.TMDB -> ArtworkApiShapes.TMDB_IMAGE_FETCH
            IntegrationProvider.TVDB -> ArtworkApiShapes.TVDB_IMAGE_FETCH
            IntegrationProvider.KITSU -> ArtworkApiShapes.KITSU_IMAGE_FETCH
            else -> when (artworkProvider) {
                ArtworkProviderId.RailPreview -> ArtworkApiShapes.RAIL_PREVIEW_IMAGE_FETCH
                ArtworkProviderId.AddonPreview -> ArtworkApiShapes.ADDON_PREVIEW_IMAGE_FETCH
                else -> ArtworkApiShapes.REMOTE_IMAGE_FETCH
            }
        }
}
```

If `ByteArrayIntegrationCodec`, `IntegrationFetchResult.Fresh`, or `IntegrationFetchResult.Stale` names differ in the current source, use the exact existing codec/result names from `app/src/main/java/com/nexio/tv/core/integration/IntegrationCodec.kt` and `IntegrationFetchResult.kt`. Keep the behavior identical: fresh hit returns bytes without loader network execution.

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetDiskCache.kt
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkSourceMaterializer.kt
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt
git add app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt
git commit -m "feat(artwork): fetch assets through runtime cache"
```

## Task 6: Nexio Artwork Coil Fetcher

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/image/NexioArtworkFetcher.kt`
- Modify: `app/src/main/java/com/nexio/tv/NexioApplication.kt`
- Test: `app/src/test/java/com/nexio/tv/core/image/NexioArtworkFetcherTest.kt`

- [ ] **Step 1: Write failing fetcher tests**

```kotlin
package com.nexio.tv.core.image

import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkAssetRepository
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexioArtworkFetcherTest {
    @Test
    fun `factory accepts nexio artwork asset uri`() {
        val factory = NexioArtworkFetcher.Factory(repository = io.mockk.mockk(relaxed = true))

        val fetcher = factory.create(
            data = "nexio-artwork://asset/artwork-asset:TMDB:poster:urlHash:abc:variant:w500:imageLang:en:policy:1",
            options = io.mockk.mockk(relaxed = true),
            imageLoader = io.mockk.mockk(relaxed = true)
        )

        assertTrue(fetcher is NexioArtworkFetcher)
    }

    @Test
    fun `factory rejects raw remote provider url`() {
        val factory = NexioArtworkFetcher.Factory(repository = io.mockk.mockk(relaxed = true))

        val fetcher = factory.create(
            data = "https://image.tmdb.org/t/p/w500/abc.jpg",
            options = io.mockk.mockk(relaxed = true),
            imageLoader = io.mockk.mockk(relaxed = true)
        )

        assertNull(fetcher)
    }
}
```

Mocking the repository is enough for this factory-boundary test because the rejected raw URL path
must return `null` before any repository method is called.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.image.NexioArtworkFetcherTest
```

Expected: FAIL because `NexioArtworkFetcher` does not exist.

- [ ] **Step 3: Implement fetcher and register it**

Create `NexioArtworkFetcher.kt`:

```kotlin
package com.nexio.tv.core.image

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkAssetRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import okio.source

class NexioArtworkFetcher(
    private val data: String,
    private val repository: ArtworkAssetRepository
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val assetKey = data.removePrefix("nexio-artwork://asset/")
            .takeIf { it != data && it.isNotBlank() }
            ?: return null
        val file = repository.getExistingFile(ArtworkAssetKey(assetKey)) ?: return null
        return SourceResult(
            source = ImageSource(file.source(), file),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK
        )
    }

    @Singleton
    class Factory @Inject constructor(
        private val repository: ArtworkAssetRepository
    ) : Fetcher.Factory<String> {
        override fun create(data: String, options: coil.request.Options, imageLoader: ImageLoader): Fetcher? {
            if (!data.startsWith("nexio-artwork://asset/") && !data.startsWith("nexio-artwork://decision/")) {
                return null
            }
            return NexioArtworkFetcher(data, repository)
        }
    }
}
```

Add `getExistingFile(assetKey: ArtworkAssetKey): File?` to `ArtworkAssetRepository` and delegate to `ArtworkAssetDiskCache.fileFor(assetKey).takeIf(File::exists)`.

Modify `NexioApplication.kt`:

```kotlin
@Inject lateinit var nexioArtworkFetcherFactory: NexioArtworkFetcher.Factory
```

Then register it before the legacy poster fetcher:

```kotlin
.components {
    add(nexioArtworkFetcherFactory)
    add(integrationPosterFetcherFactory)
}
```

- [ ] **Step 4: Run fetcher tests**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.image.NexioArtworkFetcherTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/image/NexioArtworkFetcher.kt
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt
git add app/src/main/java/com/nexio/tv/NexioApplication.kt
git add app/src/test/java/com/nexio/tv/core/image/NexioArtworkFetcherTest.kt
git commit -m "feat(artwork): add internal Coil fetcher"
```

## Task 7: Add Typed Artwork To Metadata Documents And FieldResolver

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverArtworkDecisionTest.kt`

- [ ] **Step 1: Write failing FieldResolver artwork tests**

```kotlin
package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.toLegacyArtworkString
import org.junit.Assert.assertEquals
import org.junit.Test

class FieldResolverArtworkDecisionTest {
    private val resolver = FieldResolver()

    @Test
    fun `resolved document poster string is derived from artwork ref`() {
        val posterRef = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("decision"),
            assetKey = ArtworkAssetKey("asset"),
            imageType = ArtworkType.POSTER,
            selectedProvider = null,
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty()
        )
        val primary = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue("Fight Club", FieldOwner.PRIMARY),
                ResolvedField.POSTER to FieldValue(posterRef, FieldOwner.ARTWORK, SourceRole.ARTWORK)
            )
        )

        val document = resolver.resolve(primary, emptyList(), requestContentId = "tt0137523")

        assertEquals(posterRef, document.artwork.poster)
        assertEquals(posterRef.toLegacyArtworkString(), document.poster)
        assertEquals("Fight Club", document.title)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.FieldResolverArtworkDecisionTest
```

Expected: FAIL because `ResolvedMetadataDocument` has no `artwork` property and `FieldResolver` casts poster to `String`.

- [ ] **Step 3: Add typed artwork to resolved documents**

Modify `ResolvedMetadataDocument` in `MetadataModels.kt`:

```kotlin
val artwork: com.nexio.tv.core.artwork.ArtworkBundle = com.nexio.tv.core.artwork.ArtworkBundle(),
```

Keep existing string fields in the constructor during migration. Mark them deprecated when call sites compile:

```kotlin
@Deprecated("Use artwork.poster. This string is a compatibility projection only.")
val poster: String?,
```

Modify `FieldResolver.buildDocument()` so artwork refs are extracted before the return:

```kotlin
val posterRef = fields[ResolvedField.POSTER] as? com.nexio.tv.core.artwork.ArtworkDisplayRef
val backdropRef = fields[ResolvedField.BACKDROP] as? com.nexio.tv.core.artwork.ArtworkDisplayRef
val logoRef = fields[ResolvedField.LOGO] as? com.nexio.tv.core.artwork.ArtworkDisplayRef
val artworkBundle = com.nexio.tv.core.artwork.ArtworkBundle(
    poster = posterRef,
    backdrop = backdropRef,
    logo = logoRef
)
```

Set legacy strings by projection first, falling back to existing strings for compatibility:

```kotlin
poster = posterRef.toLegacyArtworkString() ?: fields[ResolvedField.POSTER] as? String,
backdrop = backdropRef.toLegacyArtworkString() ?: fields[ResolvedField.BACKDROP] as? String,
logo = logoRef.toLegacyArtworkString() ?: fields[ResolvedField.LOGO] as? String,
artwork = artworkBundle,
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.FieldResolverArtworkDecisionTest
```

Expected: PASS.

- [ ] **Step 5: Run existing FieldResolver tests**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.FieldResolverTest --tests com.nexio.tv.core.metadata.router.RailPreviewFieldResolverTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt
git add app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt
git add app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverArtworkDecisionTest.kt
git commit -m "feat(artwork): expose typed metadata artwork"
```

## Task 8: Home Compatibility Migration

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt`
- Test: `app/src/test/java/com/nexio/tv/domain/model/HomeDisplayMetadataTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTest.kt`

- [ ] **Step 1: Write failing home metadata projection test**

```kotlin
package com.nexio.tv.domain.model

import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDisplayMetadataTest {
    @Test
    fun `poster string is derived from typed artwork when artwork exists`() {
        val metadata = HomeDisplayMetadata(
            title = "Fight Club",
            poster = "https://image.tmdb.org/raw.jpg",
            artwork = ArtworkBundle(
                poster = ArtworkDisplayRef.RuntimeAsset(
                    decisionKey = ArtworkDecisionKey("decision"),
                    assetKey = ArtworkAssetKey("asset"),
                    imageType = ArtworkType.POSTER,
                    selectedProvider = null,
                    sourceRole = ArtworkSourceRole.PREMIUM,
                    trace = ArtworkTrace.empty()
                )
            )
        )

        assertEquals("nexio-artwork://asset/asset", metadata.displayPoster)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.domain.model.HomeDisplayMetadataTest
```

Expected: FAIL because `HomeDisplayMetadata.artwork` and `displayPoster` do not exist.

- [ ] **Step 3: Add compatibility properties**

Modify `HomeDisplayMetadata.kt`:

```kotlin
val artwork: ArtworkBundle? = null
```

Add computed compatibility accessors:

```kotlin
val displayPoster: String?
    get() = artwork?.poster.toLegacyArtworkString() ?: poster

val displayBackdrop: String?
    get() = artwork?.backdrop.toLegacyArtworkString() ?: backdrop

val displayLogo: String?
    get() = artwork?.logo.toLegacyArtworkString() ?: logo
```

Update `toHomeDisplayMetadata()`, `applyTo()`, and `mergeFallback()` to carry `artwork` forward and to use typed artwork as the source of derived display strings when present.

- [ ] **Step 4: Replace direct home poster resolver ownership**

In `HomeCatalogRefreshCoordinator.kt`, keep `PosterRatingsUrlResolver` only as a temporary source of provider settings while candidate construction migrates. Replace direct final assignment like:

```kotlin
posterRatingsUrlResolver.apply(enriched, activePosterProvider)
```

with an artwork routing call that returns `HomeDisplayMetadata(artwork = routedArtwork, poster = routedArtwork.poster.toLegacyArtworkString())`. The first pass may use a helper named `routeHomeArtwork(enriched, activePosterProvider)` in the same file, but the helper must call `ArtworkRouter` and must not assign raw RPDB/Top-Posters URLs as final display strings.

- [ ] **Step 5: Run home tests**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.domain.model.HomeDisplayMetadataTest --tests com.nexio.tv.ui.screens.home.HomeCatalogRefreshCoordinatorTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt
git add app/src/test/java/com/nexio/tv/domain/model/HomeDisplayMetadataTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTest.kt
git commit -m "feat(artwork): migrate home display metadata"
```

## Task 9: Detail, Continue Watching, And Player Surface Migration

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/Meta.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMetadataSnapshot.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ContinueWatchingSection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerUiState.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerNavigationArgs.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipelineTest.kt`

- [ ] **Step 1: Write failing Continue Watching typed artwork test**

Add a test to `ContinueWatchingSnapshotServiceTest.kt` that stores a metadata snapshot with `ArtworkBundle.poster` and asserts the produced `ContinueWatchingItem` display poster is `nexio-artwork://asset/...`, while the persisted legacy `poster` field remains available for backward compatibility.

Use this assertion shape:

```kotlin
assertEquals("nexio-artwork://asset/cw-poster-asset", item.displayMetadata?.displayPoster)
assertEquals("nexio-artwork://asset/cw-poster-asset", item.displayMetadata?.poster)
```

- [ ] **Step 2: Run targeted tests to verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceTest
```

Expected: FAIL because Continue Watching models do not carry typed artwork refs.

- [ ] **Step 3: Add typed artwork to domain and persisted display metadata**

Add `artwork: ArtworkBundle? = null` to `Meta`, `MetaPreview`, `Video` when used as metadata artwork, `ContinueWatchingMetadataSnapshot`, and player UI metadata models. Keep existing string fields and project from typed refs when present.

Do not pass full artwork bundles through navigation as the final design. During compatibility, pass derived `nexio-artwork://...` strings through `Screen.Detail` and `PlayerNavigationArgs`; the destination re-resolves canonical artwork from repository when it has content identity.

- [ ] **Step 4: Update UI surfaces to prefer internal refs**

In `MetaDetailsScreen.kt`, `ContinueWatchingSection.kt`, and player UI code, use `displayPoster`, `displayBackdrop`, `displayLogo`, or typed `ArtworkDisplayRef` directly. Existing Coil call sites may still pass strings only when those strings are internal `nexio-artwork://...`, `nexio-placeholder://...`, `file://`, or `content://`.

- [ ] **Step 5: Run surface tests**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceTest --tests com.nexio.tv.ui.screens.home.HomeViewModelPresentationPipelineTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/Meta.kt
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMetadataSnapshot.kt
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt
git add app/src/main/java/com/nexio/tv/ui/screens/home/ContinueWatchingSection.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerUiState.kt
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerNavigationArgs.kt
git add app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceTest.kt
git add app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipelineTest.kt
git commit -m "feat(artwork): migrate detail cw and player surfaces"
```

## Task 10: Boundary Tests For Raw Remote Artwork URLs

**Files:**
- Create: `app/src/test/java/com/nexio/tv/architecture/MetadataArtworkBoundaryTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/ArchitectureScan.kt`

- [ ] **Step 1: Write failing architecture test**

```kotlin
package com.nexio.tv.architecture

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataArtworkBoundaryTest {
    @Test
    fun `metadata UI paths do not pass raw remote artwork URLs to Coil`() {
        val violations = sourceTextScanWithinTargets(
            forbiddenPatterns = listOf(
                "https://image.tmdb.org",
                "https://api.ratingposterdb.com",
                "https://api.top-posters.com",
                "https://media.kitsu.io",
                "AsyncImage(model = \"http",
                ".data(\"http"
            ),
            allowedPaths = listOf(
                "app/src/main/java/com/nexio/tv/core/artwork/",
                "app/src/main/java/com/nexio/tv/core/image/NexioArtworkFetcher.kt",
                "app/src/main/java/com/nexio/tv/data/remote/",
                "app/src/main/java/com/nexio/tv/data/integration/posters/"
            )
        )

        assertEquals(emptyList<String>(), violations)
    }
}
```

- [ ] **Step 2: Run test to verify it fails or exposes known violations**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.architecture.MetadataArtworkBoundaryTest
```

Expected before surface migration is complete: FAIL with UI/raw URL call-site violations. After Tasks 8 and 9, only allowed DTO/provider/source paths should remain.

- [ ] **Step 3: Fix violations by routing through typed/internal refs**

For each reported UI metadata path, replace raw remote URL model assignment with typed or projected internal artwork:

```kotlin
val imageModel = item.displayMetadata?.displayPoster ?: item.poster
```

Then enforce:

```kotlin
require(!imageModel.orEmpty().startsWith("https://api.ratingposterdb.com"))
require(!imageModel.orEmpty().startsWith("https://api.top-posters.com"))
```

Do not add new broad allow-list entries for metadata UI paths. Allow-list only DTOs, source payload holders, runtime fetch materializers, and provider transports.

- [ ] **Step 4: Run boundary test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.architecture.MetadataArtworkBoundaryTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/nexio/tv/architecture/MetadataArtworkBoundaryTest.kt
git add app/src/test/java/com/nexio/tv/architecture/ArchitectureScan.kt
git commit -m "test(artwork): ban raw metadata artwork urls"
```

## Task 11: Metadata Audit And Runtime Report Proof

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditReport.kt`
- Modify: `app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditReportWriter.kt`
- Modify: `app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditRunner.kt`
- Modify: `app/src/test/java/com/nexio/tv/metadata/audit/MetadataExecutionAuditGoldenTest.kt`

- [ ] **Step 1: Write failing audit assertions**

Add assertions for these scenarios to `MetadataExecutionAuditGoldenTest.kt`:

```kotlin
assertArtworkAudit(
    scenario = "premium-artwork-topposters-home",
    field = "poster",
    selectedProvider = "TOP_POSTERS",
    sourceRole = "PREMIUM",
    runtimeApiShapeId = "topposters.poster_template",
    rawRemoteUrlUsedByUi = false
)
assertArtworkAudit(
    scenario = "premium-artwork-cache-hit",
    field = "poster",
    selectedProvider = "RPDB",
    assetCacheDecision = "HIT",
    networkExecuted = false,
    rawRemoteUrlUsedByUi = false
)
```

- [ ] **Step 2: Run audit test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.metadata.audit.MetadataExecutionAuditGoldenTest
```

Expected: FAIL because audit report has no artwork cache section.

- [ ] **Step 3: Add artwork audit model and writer output**

Add this report model:

```kotlin
data class ArtworkAuditEntry(
    val field: String,
    val selectedProvider: String?,
    val sourceRole: String,
    val decisionKey: String?,
    val assetKey: String?,
    val assetCacheDecision: String?,
    val runtimeApiShapeId: String?,
    val networkExecuted: Boolean,
    val coilModel: String?,
    val rawRemoteUrlUsedByUi: Boolean,
    val rejectedCandidates: List<Map<String, String?>> = emptyList()
)
```

Add `artworkAudit: List<ArtworkAuditEntry> = emptyList()` to the metadata audit report. Populate it from `ArtworkTrace` and `ArtworkAssetResult` where metadata execution scenarios build report rows.

- [ ] **Step 4: Run audit test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.metadata.audit.MetadataExecutionAuditGoldenTest
```

Expected: PASS and report output includes `Artwork Cache Audit`.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditReport.kt
git add app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditReportWriter.kt
git add app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditRunner.kt
git add app/src/test/java/com/nexio/tv/metadata/audit/MetadataExecutionAuditGoldenTest.kt
git commit -m "test(artwork): report artwork cache decisions"
```

## Task 12: Cache Invalidation And Settings Boundary

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionCache.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkDecisionCacheTest.kt`
- Test: `app/src/test/java/com/nexio/tv/architecture/ProfileBoundaryArchitectureTest.kt`

- [ ] **Step 1: Add failing invalidation tests**

Extend `ArtworkDecisionCacheTest`:

```kotlin
@Test
fun `provider switch invalidates artwork decisions but not metadata tokens`() {
    val cache = InMemoryArtworkDecisionCache()
    val rpdb = decision(ArtworkDecisionKey("rpdb"), ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"), settingsHash = "rpdb-settings")
    val tmdb = decision(ArtworkDecisionKey("tmdb"), ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"), settingsHash = null)

    cache.put(rpdb)
    cache.put(tmdb)
    cache.invalidateArtworkPolicy(
        settingsHashes = setOf("rpdb-settings"),
        credentialHashes = emptySet()
    )

    assertNull(cache.get(rpdb.decisionKey))
    assertEquals(tmdb, cache.get(tmdb.decisionKey))
}
```

- [ ] **Step 2: Run test to verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkDecisionCacheTest
```

Expected: FAIL because `invalidateArtworkPolicy()` does not exist.

- [ ] **Step 3: Implement invalidation API**

Add to `ArtworkDecisionCache`:

```kotlin
fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>)
```

Implement:

```kotlin
override fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>) {
    decisions.entries.removeIf { (_, decision) ->
        decision.settingsHash in settingsHashes || decision.credentialHash in credentialHashes
    }
}
```

Do not call TMDB/TVDB/Kitsu metadata cache invalidation from poster settings code. Settings changes should trigger artwork decision/asset invalidation and resolved display overlay repaint only.

- [ ] **Step 4: Run invalidation and profile boundary tests**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkDecisionCacheTest --tests com.nexio.tv.architecture.ProfileBoundaryArchitectureTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionCache.kt
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt
git add app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt
git add app/src/test/java/com/nexio/tv/core/artwork/ArtworkDecisionCacheTest.kt
git add app/src/test/java/com/nexio/tv/architecture/ProfileBoundaryArchitectureTest.kt
git commit -m "feat(artwork): invalidate artwork policy independently"
```

## Task 13: Final Verification And Release Build

**Files:**
- No production file edits unless a verification failure points to a specific issue.

- [ ] **Step 1: Validate OpenSpec**

```bash
openspec validate add-unified-artwork-pipeline --strict
```

Expected: valid.

- [ ] **Step 2: Run targeted artwork and metadata tests**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.* --tests com.nexio.tv.core.image.NexioArtworkFetcherTest --tests com.nexio.tv.core.metadata.router.FieldResolverArtworkDecisionTest --tests com.nexio.tv.architecture.MetadataArtworkBoundaryTest
```

Expected: PASS.

- [ ] **Step 3: Run broader metadata/router regression tests**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.* --tests com.nexio.tv.metadata.audit.MetadataExecutionAuditGoldenTest
```

Expected: PASS.

- [ ] **Step 4: Run release build**

```bash
./gradlew :app:assembleUniversalRelease
```

Expected: PASS without stripping artwork, metadata, premium poster, or runtime functionality.

- [ ] **Step 5: Inspect git status**

```bash
git status --short --untracked-files=all
```

Expected: implementation files and forced OpenSpec files are staged or committed; only known generated `media` submodule dirtiness may remain uncommitted.

- [ ] **Step 6: Commit OpenSpec and plan if not already committed**

```bash
git add docs/superpowers/plans/2026-05-04-unified-artwork-pipeline.md
git add -f openspec/changes/add-unified-artwork-pipeline
git commit -m "docs(artwork): plan unified artwork pipeline"
```

## Self-Review Checklist

- Spec coverage:
  - Unified pipeline: Tasks 1, 3, 5, 6, 7.
  - Raw URL boundary: Tasks 1, 2, 5, 6, 10.
  - Typed refs canonical with legacy projection: Tasks 1, 7, 8, 9.
  - Runtime vs persisted safety: Tasks 1, 5.
  - Source materialization after restart: Task 5.
  - Preview before canonical identity: Tasks 3, 4.
  - Provider identity and capability checks: Task 3.
  - Runtime-backed asset cache: Task 5.
  - Coil boundary: Tasks 6, 10.
  - Profile/language and invalidation rules: Tasks 2, 12.
  - Audit/report proof: Task 11.

- Placeholder scan:
  - The plan intentionally avoids placeholder implementation steps. Every task has concrete files, tests, commands, and expected outcomes.

- Type consistency:
  - `ArtworkDisplayRef`, `ArtworkBundle`, `ArtworkDecisionKey`, `ArtworkAssetKey`, `ArtworkOwnerKey`, `ArtworkProviderId`, `ArtworkCandidate`, `ArtworkDecision`, `PersistedArtworkCandidate`, and `ArtworkAssetRecord` are defined in Task 1 and reused consistently.

## Execution Handoff

Plan complete when this file is saved. Use one execution mode:

1. Subagent-Driven: dispatch a fresh worker per task, review after each task, and commit each task independently.
2. Inline Execution: execute tasks in this session using `superpowers:executing-plans`, with verification checkpoints after each task.
