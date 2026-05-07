# TVDB Router Artwork Retention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve TVDB poster, backdrop, and clearlogo artwork through the MetadataRouter path when `/series/{id}/extended` contains artwork records.

**Architecture:** Keep the production owner in `TvdbMetadataProviderAdapter`, not the legacy `TvdbMetadataService` and not UI code. Map TVDB extended artwork records into pure shared artwork candidates, pass those candidates through a shared injectable metadata artwork decision boundary backed by the singleton `ArtworkRouter`, durable `ArtworkDecisionCache`, real `ArtworkRemoteSourceStore`, and shared artwork policy settings, then emit `ArtworkDisplayRef.RuntimeAsset` values through `ResolvedField.POSTER`, `ResolvedField.BACKDROP`, and `ResolvedField.LOGO` so `FieldResolver` builds the `ArtworkBundle`.

**Tech Stack:** Kotlin, Android/JVM unit tests, Moshi TVDB DTOs, MetadataRouter, shared artwork pipeline, Gradle `testDebugUnitTest`.

---

## File Structure

- Modify: `app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt`
  - Ensure `TvdbArtworkRecord` exposes all fields needed by router artwork selection.
- Create: `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbArtworkCandidateMapper.kt`
  - Own TVDB artwork type constants, candidate filtering, ranking, and conversion to `ArtworkCandidate`.
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionPolicy.kt`
  - Own shared artwork decision TTL constants used by metadata and premium artwork decision creation.
- Modify: `app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt`
  - Provide the singleton `ArtworkRouter` using the real `ArtworkRemoteSourceStore`.
- Create: `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolver.kt`
  - Own metadata-provider artwork decision resolution using injected shared artwork dependencies only. No default-constructed router and no production noop store.
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt`
  - Allow `buildTvdbCoreLocalizedCandidate` to merge pre-resolved TVDB artwork fields with localized text fields.
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapter.kt`
  - Inject/use the artwork mapper and field resolver. Keep `short=false` via the existing `fetchSeriesExtendedCached` path.
- Test: `app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbArtworkCandidateMapperTest.kt`
  - Validate TVDB type mapping, `extended.image` poster fallback, filtering, language/score ranking, and provider/source metadata.
- Test: `app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbCoreLocalizationTest.kt`
  - Add regression coverage proving `buildTvdbCoreLocalizedCandidate` keeps TVDB artwork fields.
- Test: `app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapterArtworkTest.kt`
  - Validate adapter output from series extended artwork records.
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverArtworkDecisionTest.kt`
  - Add a trace/source-role assertion for TVDB primary artwork refs.

Scope decision: do not add `/series/{id}/artworks` fallback in this plan. The confirmed regression is loss of `extended.artworks`, and the existing active route already uses `/series/{id}/extended` with `short=false`. Add the fallback as a separate plan after this regression is closed.

Design constraints from review:
- `TvdbArtworkCandidateMapper` must stay pure: no cache writes, no router calls, no decisions.
- TVDB remote artwork must use `requiresRuntimeFetch = true`.
- The resolver that writes decisions must be `suspend` and use injected shared dependencies.
- Do not default-construct `ArtworkRouter` or use `NoopArtworkRemoteSourceStore` in production code.
- Do not define TVDB-specific decision TTL constants inside metadata resolver code.
- Prefer wrapping `extended.image` as a lower-priority POSTER artwork candidate over leaving it as a raw `POSTER` string.

---

### Task 1: Add TVDB Artwork Candidate Mapper

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbArtworkCandidateMapper.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbArtworkCandidateMapperTest.kt`

- [ ] **Step 1: Write the failing mapper tests**

Create `app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbArtworkCandidateMapperTest.kt`:

```kotlin
package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSource
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.data.remote.api.TvdbArtworkRecord
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvdbArtworkCandidateMapperTest {
    private val mapper = TvdbArtworkCandidateMapper()

    @Test
    fun `maps tvdb series artwork types to shared artwork candidates`() {
        val candidates = mapper.mapSeriesArtwork(
            seriesId = 121361,
            artworks = listOf(
                TvdbArtworkRecord(id = 1, image = "https://art.example/poster.jpg", type = 2, score = 10.0, language = "eng"),
                TvdbArtworkRecord(id = 2, image = "https://art.example/backdrop.jpg", type = 3, score = 20.0, language = "eng"),
                TvdbArtworkRecord(id = 3, image = "https://art.example/logo.png", type = 23, score = 30.0, language = "eng")
            ),
            requestedLanguage = "nl-NL"
        )

        assertEquals(listOf(ArtworkType.POSTER, ArtworkType.BACKDROP, ArtworkType.LOGO), candidates.map { it.imageType })
        candidates.forEach { candidate ->
            assertEquals(ArtworkOwnerKey.CanonicalContent("tvdb:121361"), candidate.ownerKey)
            assertEquals("tvdb:121361", candidate.canonicalContentId)
            assertEquals(ProviderIds(tvdb = "121361"), candidate.providerIds)
            assertEquals(MetadataMediaKind.SERIES, candidate.mediaKind)
            assertEquals(ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB), candidate.provider)
            assertEquals(ArtworkSourceRole.PRIMARY, candidate.sourceRole)
            assertTrue(candidate.source is ArtworkSource.RemoteUrl)
            assertEquals(true, candidate.requiresRuntimeFetch)
            assertEquals("en", candidate.imageLanguage)
        }
    }

    @Test
    fun `ignores unsupported and blank tvdb artwork records`() {
        val candidates = mapper.mapSeriesArtwork(
            seriesId = 121361,
            artworks = listOf(
                TvdbArtworkRecord(id = 1, image = "", type = 23, score = 100.0),
                TvdbArtworkRecord(id = 2, image = "   ", type = 3, score = 100.0),
                TvdbArtworkRecord(id = 3, image = "https://art.example/clearart.png", type = 22, score = 100.0),
                TvdbArtworkRecord(id = 4, image = "https://art.example/logo.png", type = 23, score = 1.0)
            ),
            requestedLanguage = "en-US"
        )

        assertEquals(1, candidates.size)
        assertEquals(ArtworkType.LOGO, candidates.single().imageType)
    }

    @Test
    fun `prefers english artwork for global image language policy then score then stable id`() {
        val candidates = mapper.mapSeriesArtwork(
            seriesId = 121361,
            artworks = listOf(
                TvdbArtworkRecord(id = 9, image = "https://art.example/nl-logo.png", type = 23, score = 100.0, language = "nld"),
                TvdbArtworkRecord(id = 2, image = "https://art.example/en-logo-low.png", type = 23, score = 10.0, language = "eng"),
                TvdbArtworkRecord(id = 1, image = "https://art.example/en-logo-high.png", type = 23, score = 90.0, language = "eng")
            ),
            requestedLanguage = "nl-NL"
        )

        val logo = candidates.single()
        assertEquals(ArtworkType.LOGO, logo.imageType)
        assertEquals("https://art.example/en-logo-high.png".sha256ForTest(), (logo.source as ArtworkSource.RemoteUrl).normalizedUrlHash)
        assertEquals(0, logo.priority)
    }

    @Test
    fun `uses extended image as lower priority poster fallback when type 2 artwork is absent`() {
        val candidates = mapper.mapSeriesArtwork(
            seriesId = 121361,
            artworks = listOf(
                TvdbArtworkRecord(id = 2, image = "https://art.example/backdrop.jpg", type = 3, score = 20.0, language = "eng"),
                TvdbArtworkRecord(id = 3, image = "https://art.example/logo.png", type = 23, score = 30.0, language = "eng")
            ),
            requestedLanguage = "en-US",
            posterFallbackImage = " https://art.example/fallback-poster.jpg "
        )

        val poster = candidates.single { it.imageType == ArtworkType.POSTER }
        assertEquals(100, poster.priority)
        assertEquals(true, poster.requiresRuntimeFetch)
        assertEquals("https://art.example/fallback-poster.jpg".sha256ForTest(), (poster.source as ArtworkSource.RemoteUrl).normalizedUrlHash)
    }
}
```

Add this helper at the bottom of the test file:

```kotlin
private fun String.sha256ForTest(): String {
    val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 2: Run mapper tests to verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.integration.metadata.TvdbArtworkCandidateMapperTest"
```

Expected: compile failure because `TvdbArtworkCandidateMapper` does not exist.

- [ ] **Step 3: Implement the mapper**

Create `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbArtworkCandidateMapper.kt`:

```kotlin
package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.artwork.ArtworkCandidate
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSource
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.SensitiveArtworkUrl
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.data.remote.api.TvdbArtworkRecord
import com.nexio.tv.domain.model.ProviderIds
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject

object TvdbArtworkTypes {
    const val POSTER = 2
    const val BACKDROP = 3
    const val CLEAR_LOGO = 23
}

class TvdbArtworkCandidateMapper @Inject constructor() {
    fun mapSeriesArtwork(
        seriesId: Int,
        artworks: List<TvdbArtworkRecord>,
        requestedLanguage: String?,
        posterFallbackImage: String? = null
    ): List<ArtworkCandidate> {
        val canonicalContentId = "tvdb:$seriesId"
        val ownerKey = ArtworkOwnerKey.CanonicalContent(canonicalContentId)
        val providerId = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB)
        val providerIds = ProviderIds(tvdb = seriesId.toString())

        val selected = listOfNotNull(
            selectArtwork(artworks, TvdbArtworkTypes.POSTER)?.toCandidate(
                ownerKey = ownerKey,
                canonicalContentId = canonicalContentId,
                providerIds = providerIds,
                providerId = providerId,
                imageType = ArtworkType.POSTER
            ),
            selectArtwork(artworks, TvdbArtworkTypes.BACKDROP)?.toCandidate(
                ownerKey = ownerKey,
                canonicalContentId = canonicalContentId,
                providerIds = providerIds,
                providerId = providerId,
                imageType = ArtworkType.BACKDROP
            ),
            selectArtwork(artworks, TvdbArtworkTypes.CLEAR_LOGO)?.toCandidate(
                ownerKey = ownerKey,
                canonicalContentId = canonicalContentId,
                providerIds = providerIds,
                providerId = providerId,
                imageType = ArtworkType.LOGO
            )
        )
        return if (selected.any { it.imageType == ArtworkType.POSTER }) {
            selected
        } else {
            posterFallbackImage?.trim()?.takeIf { it.isNotBlank() }?.let { fallback ->
                selected + fallback.toCandidate(
                    ownerKey = ownerKey,
                    canonicalContentId = canonicalContentId,
                    providerIds = providerIds,
                    providerId = providerId,
                    imageType = ArtworkType.POSTER,
                    tvdbType = TvdbArtworkTypes.POSTER,
                    priority = 100
                )
            } ?: selected
        }
    }

    private fun selectArtwork(
        artworks: List<TvdbArtworkRecord>,
        tvdbType: Int
    ): TvdbArtworkRecord? =
        artworks
            .filter { artwork -> artwork.type == tvdbType && !artwork.image.isNullOrBlank() }
            .sortedWith(
                compareByDescending<TvdbArtworkRecord> { it.language.normalizedTvdbLanguage() == "eng" }
                    .thenByDescending { it.language.isNullOrBlank() }
                    .thenByDescending { it.score ?: 0.0 }
                    .thenBy { it.id ?: Int.MAX_VALUE }
            )
            .firstOrNull()

    private fun TvdbArtworkRecord.toCandidate(
        ownerKey: ArtworkOwnerKey,
        canonicalContentId: String,
        providerIds: ProviderIds,
        providerId: ArtworkProviderId,
        imageType: ArtworkType
    ): ArtworkCandidate {
        val imageUrl = requireNotNull(image?.trim())
        return imageUrl.toCandidate(
            ownerKey = ownerKey,
            canonicalContentId = canonicalContentId,
            providerIds = providerIds,
            providerId = providerId,
            imageType = imageType,
            tvdbType = requireNotNull(type),
            priority = 0
        )
    }

    private fun String.toCandidate(
        ownerKey: ArtworkOwnerKey,
        canonicalContentId: String,
        providerIds: ProviderIds,
        providerId: ArtworkProviderId,
        imageType: ArtworkType,
        tvdbType: Int,
        priority: Int
    ): ArtworkCandidate {
        val imageUrl = trim()
        val sourceHash = imageUrl.sha256()
        return ArtworkCandidate(
            ownerKey = ownerKey,
            canonicalContentId = canonicalContentId,
            providerIds = providerIds,
            mediaKind = MetadataMediaKind.SERIES,
            imageType = imageType,
            provider = providerId,
            sourceRole = ArtworkSourceRole.PRIMARY,
            source = ArtworkSource.RemoteUrl.of(
                rawUrl = SensitiveArtworkUrl.of(imageUrl),
                normalizedUrlHash = sourceHash
            ),
            priority = priority,
            requiresRuntimeFetch = true,
            imageLanguage = "en",
            trace = ArtworkTrace(
                selectedProvider = providerId.key,
                sourceRole = ArtworkSourceRole.PRIMARY.name,
                reason = "tvdb_series_extended_artwork_type_$tvdbType"
            )
        )
    }

    private fun String?.normalizedTvdbLanguage(): String? =
        this?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Run mapper tests to verify GREEN**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.integration.metadata.TvdbArtworkCandidateMapperTest"
```

Expected: PASS.

- [ ] **Step 5: Commit mapper task**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbArtworkCandidateMapper.kt app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbArtworkCandidateMapperTest.kt
git commit -m "test: map TVDB series artwork candidates"
```

---

### Task 2: Resolve Metadata Artwork Through Shared Decision Boundary

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionPolicy.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolverTest.kt`

- [ ] **Step 1: Write the failing shared decision resolver tests**

Create `app/src/test/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolverTest.kt`:

```kotlin
package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.artwork.ArtworkCandidate
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderSettingsSource
import com.nexio.tv.core.artwork.ArtworkRemoteSourceStore
import com.nexio.tv.core.artwork.ArtworkRouter
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.InMemoryArtworkDecisionCache
import com.nexio.tv.core.artwork.NoopArtworkRemoteSourceStore
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.data.remote.api.TvdbArtworkRecord
import com.nexio.tv.domain.model.ArtworkProviderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataArtworkDecisionResolverTest {
    private val cache: ArtworkDecisionCache = InMemoryArtworkDecisionCache()
    private val remoteSourceStore: ArtworkRemoteSourceStore = NoopArtworkRemoteSourceStore
    private val resolver = MetadataArtworkDecisionResolver(
        artworkRouter = ArtworkRouter(remoteSourceStore = remoteSourceStore),
        artworkDecisionCache = cache,
        remoteSourceStore = remoteSourceStore,
        settingsSource = StaticArtworkProviderSettingsSource()
    )
    private val mapper = TvdbArtworkCandidateMapper()

    @Test
    fun `resolves tvdb primary artwork candidates to runtime asset field values`() = runTest {
        val fields = resolver.resolveFields(
            candidates = tvdbCandidates(),
            contentId = "tvdb:121361"
        )

        val logo = fields.getValue(ResolvedField.LOGO)
        assertEquals(FieldOwner.ARTWORK, logo.owner)
        assertEquals(SourceRole.ARTWORK, logo.sourceRole)
        val logoRef = logo.value as ArtworkDisplayRef.RuntimeAsset
        assertEquals(ArtworkType.LOGO, logoRef.imageType)
        assertEquals("TVDB", logoRef.selectedProvider?.key)
        assertEquals("PRIMARY", logoRef.sourceRole.name)
        assertEquals("TVDB", logoRef.trace.selectedProvider)
        assertNotNull(cache.get(logoRef.decisionKey))

        assertTrue(fields.getValue(ResolvedField.POSTER).value is ArtworkDisplayRef.RuntimeAsset)
        assertTrue(fields.getValue(ResolvedField.BACKDROP).value is ArtworkDisplayRef.RuntimeAsset)
    }

    private fun tvdbCandidates(): List<ArtworkCandidate> =
        mapper.mapSeriesArtwork(
            seriesId = 121361,
            artworks = listOf(
                TvdbArtworkRecord(id = 1, image = "https://art.example/poster.jpg", type = 2, score = 10.0, language = "eng"),
                TvdbArtworkRecord(id = 2, image = "https://art.example/backdrop.jpg", type = 3, score = 20.0, language = "eng"),
                TvdbArtworkRecord(id = 3, image = "https://art.example/logo.png", type = 23, score = 30.0, language = "eng")
            ),
            requestedLanguage = "en-US"
        )

    private class StaticArtworkProviderSettingsSource : ArtworkProviderSettingsSource {
        override val settings: Flow<ArtworkProviderSettings> = flowOf(ArtworkProviderSettings())
    }
}
```

- [ ] **Step 2: Run shared decision resolver tests to verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.integration.metadata.MetadataArtworkDecisionResolverTest"
```

Expected: compile failure because `MetadataArtworkDecisionResolver` does not exist.

- [ ] **Step 3: Add shared decision policy constants**

Create `app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionPolicy.kt`:

```kotlin
package com.nexio.tv.core.artwork

object ArtworkDecisionPolicy {
    const val DECISION_TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L
    const val DECISION_STALE_TTL_MS: Long = 30L * 24L * 60L * 60L * 1000L
}
```

- [ ] **Step 4: Provide the shared ArtworkRouter singleton**

Modify `IntegrationRuntimeModule.kt` and add this provider near the artwork cache/store providers:

```kotlin
@Provides
@Singleton
fun provideArtworkRouter(
    remoteSourceStore: ArtworkRemoteSourceStore
): ArtworkRouter =
    ArtworkRouter(remoteSourceStore = remoteSourceStore)
```

Add imports if missing:

```kotlin
import com.nexio.tv.core.artwork.ArtworkRouter
```

- [ ] **Step 5: Implement metadata artwork decision resolver**

Create `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolver.kt`:

```kotlin
package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.artwork.ArtworkCacheKeys
import com.nexio.tv.core.artwork.ArtworkCandidate
import com.nexio.tv.core.artwork.ArtworkDecision
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDecisionPolicy
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkDisplayHints
import com.nexio.tv.core.artwork.ArtworkProviderSettingsSource
import com.nexio.tv.core.artwork.ArtworkRemoteSourceStore
import com.nexio.tv.core.artwork.ArtworkRouter
import com.nexio.tv.core.artwork.ArtworkRoutingPolicy
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.toPersistedCandidate
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.SourceRole
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class MetadataArtworkDecisionResolver @Inject constructor(
    private val artworkRouter: ArtworkRouter,
    private val artworkDecisionCache: ArtworkDecisionCache,
    private val remoteSourceStore: ArtworkRemoteSourceStore,
    private val settingsSource: ArtworkProviderSettingsSource
) {
    suspend fun resolveFields(
        candidates: List<ArtworkCandidate>,
        contentId: String
    ): Map<ResolvedField, FieldValue> {
        val policy = ArtworkRoutingPolicy(settings = settingsSource.settings.first())
        return candidates
            .groupBy { it.imageType }
            .mapNotNull { (imageType, imageCandidates) ->
                val field = imageType.toResolvedField() ?: return@mapNotNull null
                val selection = artworkRouter.select(imageCandidates, policy)
                val selected = selection.selectedCandidateOrNull ?: return@mapNotNull null
                val now = System.currentTimeMillis()
                val decisionKey = ArtworkCacheKeys.decisionKey(
                    ownerKey = selected.ownerKey,
                    imageType = selected.imageType,
                    provider = selected.provider,
                    premiumEnabled = false,
                    settingsHash = null,
                    credentialHash = null,
                    policyVersion = policy.policyVersion
                )
                val decision = ArtworkDecision(
                    decisionKey = decisionKey,
                    ownerKey = selected.ownerKey,
                    canonicalContentId = selected.canonicalContentId ?: contentId,
                    imageType = selected.imageType,
                    selectedCandidate = selected.toPersistedCandidate(
                        policyVersion = policy.policyVersion,
                        remoteSourceStore = remoteSourceStore
                    ),
                    rejectedCandidates = selection.rejectedCandidates,
                    policyVersion = policy.policyVersion,
                    imageLanguage = selected.imageLanguage,
                    settingsHash = null,
                    credentialHash = null,
                    createdAtMs = now,
                    expiresAtMs = now + ArtworkDecisionPolicy.DECISION_TTL_MS,
                    staleUntilMs = now + ArtworkDecisionPolicy.DECISION_STALE_TTL_MS
                )
                artworkDecisionCache.put(decision)
                field to FieldValue(
                    value = ArtworkDisplayRef.RuntimeAsset(
                        decisionKey = decisionKey,
                        assetKey = null,
                        imageType = imageType,
                        selectedProvider = selected.provider,
                        sourceRole = selected.sourceRole,
                        trace = ArtworkTrace(
                            selectedProvider = selected.provider?.key,
                            sourceRole = selected.sourceRole.name,
                            reason = "metadata_artwork_provider_selection",
                            rejectedCandidates = selection.rejectedCandidates
                        ),
                        displayHints = ArtworkDisplayHints()
                    ),
                    owner = FieldOwner.ARTWORK,
                    sourceRole = SourceRole.ARTWORK
                )
            }
            .toMap()
    }

    private fun ArtworkType.toResolvedField(): ResolvedField? =
        when (this) {
            ArtworkType.POSTER -> ResolvedField.POSTER
            ArtworkType.BACKDROP -> ResolvedField.BACKDROP
            ArtworkType.LOGO -> ResolvedField.LOGO
            ArtworkType.THUMBNAIL -> null
        }
}
```

- [ ] **Step 6: Run shared decision resolver tests to verify GREEN**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.integration.metadata.MetadataArtworkDecisionResolverTest"
```

Expected: PASS.

- [ ] **Step 7: Commit shared decision resolver task**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionPolicy.kt app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolver.kt app/src/test/java/com/nexio/tv/data/integration/metadata/MetadataArtworkDecisionResolverTest.kt
git commit -m "feat: resolve metadata artwork through shared decisions"
```

---

### Task 3: Preserve TVDB Artwork In Core Candidate Builder

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbCoreLocalizationTest.kt`

- [ ] **Step 1: Write the failing candidate-builder regression test**

Add this test to `TvdbCoreLocalizationTest`:

```kotlin
@Test
fun `tvdb core localized candidate preserves artwork fields from router adapter`() {
    val policy = LocalizationPolicy.tvdb("en-US")
    val artworkFields = mapOf(
        ResolvedField.POSTER to com.nexio.tv.core.metadata.router.FieldValue("posterRef", com.nexio.tv.core.metadata.router.FieldOwner.ARTWORK, com.nexio.tv.core.metadata.router.SourceRole.ARTWORK),
        ResolvedField.BACKDROP to com.nexio.tv.core.metadata.router.FieldValue("backdropRef", com.nexio.tv.core.metadata.router.FieldOwner.ARTWORK, com.nexio.tv.core.metadata.router.SourceRole.ARTWORK),
        ResolvedField.LOGO to com.nexio.tv.core.metadata.router.FieldValue("logoRef", com.nexio.tv.core.metadata.router.FieldOwner.ARTWORK, com.nexio.tv.core.metadata.router.SourceRole.ARTWORK)
    )

    val selected = buildTvdbCoreLocalizedCandidate(
        provider = MetadataPrimaryProvider.TVDB,
        policy = policy,
        extended = TvdbSeriesExtendedRecord(
            id = 81189,
            name = "Original title",
            overview = "Original overview"
        ),
        englishTranslation = TvdbTranslationRecord(
            name = "English title",
            overview = "English overview"
        ),
        requestedTranslation = null,
        artworkFields = artworkFields
    )

    assertEquals("posterRef", selected.fields.getValue(ResolvedField.POSTER).value)
    assertEquals("backdropRef", selected.fields.getValue(ResolvedField.BACKDROP).value)
    assertEquals("logoRef", selected.fields.getValue(ResolvedField.LOGO).value)
}
```

- [ ] **Step 2: Run candidate-builder test to verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.integration.metadata.TvdbCoreLocalizationTest.tvdb core localized candidate preserves artwork fields from router adapter"
```

Expected: compile failure because `buildTvdbCoreLocalizedCandidate` has no `artworkFields` parameter.

- [ ] **Step 3: Add artwork field parameter and merge behavior**

Modify `buildTvdbCoreLocalizedCandidate` in `MetadataAdapterCandidates.kt`:

```kotlin
internal fun buildTvdbCoreLocalizedCandidate(
    provider: MetadataPrimaryProvider,
    policy: LocalizationPolicy,
    extended: TvdbSeriesExtendedRecord?,
    englishTranslation: TvdbTranslationRecord?,
    requestedTranslation: TvdbTranslationRecord?,
    artworkFields: Map<ResolvedField, FieldValue> = emptyMap()
): MetadataCandidate {
```

Inside the returned `fields = buildMap { ... }`, replace the current poster-only line:

```kotlin
extended?.image?.let { put(ResolvedField.POSTER, FieldValue(it, FieldOwner.PRIMARY)) }
```

with:

```kotlin
putAll(artworkFields)
```

Do not keep `extended.image` as a raw `ResolvedField.POSTER` fallback in this builder. Task 1 wraps `extended.image` as a lower-priority TVDB POSTER `ArtworkCandidate`, so poster fallback still goes through shared artwork decisions.

- [ ] **Step 4: Run candidate-builder tests to verify GREEN**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.integration.metadata.TvdbCoreLocalizationTest"
```

Expected: PASS.

- [ ] **Step 5: Commit candidate builder task**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbCoreLocalizationTest.kt
git commit -m "feat: carry TVDB artwork fields in router candidates"
```

---

### Task 4: Wire TVDB Adapter To Artwork Mapper And Shared Decision Resolver

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapter.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapterArtworkTest.kt`

- [ ] **Step 1: Write the failing adapter test**

Create `app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapterArtworkTest.kt` with a focused test double for the candidate builder path. If mocking `TvdbIntegrationProvider` is required, follow the `mockk` style used in nearby TVDB tests.

```kotlin
package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderSettingsSource
import com.nexio.tv.core.artwork.ArtworkRemoteSourceStore
import com.nexio.tv.core.artwork.ArtworkRouter
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.InMemoryArtworkDecisionCache
import com.nexio.tv.core.artwork.NoopArtworkRemoteSourceStore
import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataLocalizationFallbackRole
import com.nexio.tv.core.metadata.router.MetadataLocalizationPayloadTrace
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.ProviderPlanRole
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.data.remote.api.TvdbArtworkRecord
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.data.remote.api.TvdbTranslationRecord
import com.nexio.tv.domain.model.ArtworkProviderSettings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TvdbMetadataProviderAdapterArtworkTest {
    private val integrationProvider = mockk<TvdbIntegrationProvider>()
    private val decisionCache: ArtworkDecisionCache = InMemoryArtworkDecisionCache()
    private val remoteSourceStore: ArtworkRemoteSourceStore = NoopArtworkRemoteSourceStore
    private val metadataArtworkDecisionResolver = MetadataArtworkDecisionResolver(
        artworkRouter = ArtworkRouter(remoteSourceStore = remoteSourceStore),
        artworkDecisionCache = decisionCache,
        remoteSourceStore = remoteSourceStore,
        settingsSource = StaticArtworkProviderSettingsSource()
    )
    private val adapter = TvdbMetadataProviderAdapter(
        integrationProvider = integrationProvider,
        traceEvents = com.nexio.tv.core.trace.TraceMetadataEvents(
            sink = com.nexio.tv.core.trace.NoopRuntimeTraceSink,
            sessionId = { null }
        ),
        tvdbArtworkCandidateMapper = TvdbArtworkCandidateMapper(),
        metadataArtworkDecisionResolver = metadataArtworkDecisionResolver
    )

    @Test
    fun `series extended artwork type 23 is emitted as TVDB logo runtime asset`() = runTest {
        coEvery {
            integrationProvider.fetchSeriesExtendedCached(tvdbId = 121361, localizationPolicyVersion = any())
        } returns TvdbSeriesExtendedRecord(
            id = 121361,
            image = "https://art.example/fallback-poster.jpg",
            artworks = listOf(
                TvdbArtworkRecord(id = 1, image = "https://art.example/poster.jpg", type = 2, score = 10.0, language = "eng"),
                TvdbArtworkRecord(id = 2, image = "https://art.example/backdrop.jpg", type = 3, score = 20.0, language = "eng"),
                TvdbArtworkRecord(id = 3, image = "https://art.example/logo.png", type = 23, score = 30.0, language = "eng")
            )
        )
        coEvery {
            integrationProvider.fetchSeriesTranslationWithTrace(any(), any(), any(), any())
        } returns LocalizedPayloadFetch(
            value = TvdbTranslationRecord(name = "Game of Thrones", overview = "Nine noble families fight."),
            trace = MetadataLocalizationPayloadTrace(
                provider = MetadataPrimaryProvider.TVDB,
                apiShapeId = "tvdb.series.translation",
                language = "eng",
                fallbackRole = MetadataLocalizationFallbackRole.LANGUAGE_FALLBACK,
                cacheKey = "cache",
                cacheDecision = null,
                executedNetwork = false,
                policyVersion = LocalizationPolicy.tvdb("en-US").policyVersion
            )
        )

        val result = adapter.execute(
            route = MetadataRoute(
                parentId = "tvdb:121361",
                provider = MetadataPrimaryProvider.TVDB,
                mediaKind = MetadataMediaKind.SERIES,
                targetIds = mapOf(MetadataPrimaryProvider.TVDB to "tvdb:121361"),
                language = "en-US"
            ),
            step = ProviderPlanStep(
                apiShapeId = TvdbApiShapes.SERIES_EXTENDED,
                provider = MetadataPrimaryProvider.TVDB,
                role = ProviderPlanRole.PRIMARY_CORE
            )
        )

        val logo = result.candidate!!.fields.getValue(ResolvedField.LOGO).value as ArtworkDisplayRef.RuntimeAsset
        assertEquals(ArtworkType.LOGO, logo.imageType)
        assertEquals("TVDB", logo.selectedProvider?.key)
        assertEquals("PRIMARY", logo.sourceRole.name)
        assertNotNull(decisionCache.get(logo.decisionKey))
        assertEquals(ArtworkType.POSTER, (result.candidate!!.fields.getValue(ResolvedField.POSTER).value as ArtworkDisplayRef.RuntimeAsset).imageType)
        assertEquals(ArtworkType.BACKDROP, (result.candidate!!.fields.getValue(ResolvedField.BACKDROP).value as ArtworkDisplayRef.RuntimeAsset).imageType)
    }

    private class StaticArtworkProviderSettingsSource : ArtworkProviderSettingsSource {
        override val settings: Flow<ArtworkProviderSettings> = flowOf(ArtworkProviderSettings())
    }
}
```

If the exact translation trace return type has a different name, use the actual type from `TvdbIntegrationProvider.fetchSeriesTranslationWithTrace` and keep the assertions unchanged.

- [ ] **Step 2: Run adapter test to verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.integration.metadata.TvdbMetadataProviderAdapterArtworkTest"
```

Expected: compile failure because `TvdbMetadataProviderAdapter` does not accept the mapper/resolver dependencies, or failure because `LOGO` is absent.

- [ ] **Step 3: Inject mapper/resolver and pass artwork fields**

Modify the constructor in `TvdbMetadataProviderAdapter.kt`:

```kotlin
class TvdbMetadataProviderAdapter @Inject constructor(
    private val integrationProvider: TvdbIntegrationProvider,
    private val traceEvents: TraceMetadataEvents,
    private val advancedMetadataMapper: TvdbAdvancedMetadataMapper = TvdbAdvancedMetadataMapper(),
    private val tvdbArtworkCandidateMapper: TvdbArtworkCandidateMapper = TvdbArtworkCandidateMapper(),
    private val metadataArtworkDecisionResolver: MetadataArtworkDecisionResolver
) : MetadataProviderAdapter {
```

Inside the `TvdbApiShapes.SERIES_EXTENDED` branch, before `buildTvdbCoreLocalizedCandidate`, add:

```kotlin
val artworkCandidates = extended?.let { record ->
    tvdbArtworkCandidateMapper.mapSeriesArtwork(
        seriesId = tvdbId,
        artworks = record.artworks.orEmpty(),
        requestedLanguage = route.language,
        posterFallbackImage = record.image
    )
}.orEmpty()
val artworkFields = metadataArtworkDecisionResolver.resolveFields(
    candidates = artworkCandidates,
    contentId = "tvdb:$tvdbId"
)
```

Then call:

```kotlin
buildTvdbCoreLocalizedCandidate(
    provider = this.provider,
    policy = policy,
    extended = extended,
    englishTranslation = english.value,
    requestedTranslation = requested,
    artworkFields = artworkFields
).withAdvancedFields(extended)
```

- [ ] **Step 4: Run adapter test to verify GREEN**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.integration.metadata.TvdbMetadataProviderAdapterArtworkTest"
```

Expected: PASS.

- [ ] **Step 5: Commit adapter task**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapter.kt app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapterArtworkTest.kt
git commit -m "fix: preserve TVDB artwork in metadata router adapter"
```

---

### Task 5: Verify DTO And Short Mode Contracts

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt`

- [ ] **Step 1: Write DTO/contract regression assertions**

In `TvdbMetadataServiceTest`, extend `series extended dto carries metadata fields used by TVDB mapper` so the artwork record includes fields used by the router mapper:

```kotlin
artworks = listOf(
    TvdbArtworkRecord(
        id = 901,
        image = "https://art.example/logo.png",
        thumbnail = "https://art.example/logo-thumb.png",
        language = "eng",
        type = 23,
        score = 91.5,
        width = 800,
        height = 310
    )
),
```

Add assertions:

```kotlin
val artwork = record.artworks!!.single()
assertEquals(901, artwork.id)
assertEquals("https://art.example/logo.png", artwork.image)
assertEquals("https://art.example/logo-thumb.png", artwork.thumbnail)
assertEquals("eng", artwork.language)
assertEquals(23, artwork.type)
assertEquals(91.5, artwork.score!!, 0.0)
assertEquals(800, artwork.width)
assertEquals(310, artwork.height)
```

- [ ] **Step 2: Run DTO test to verify RED if fields are missing**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest.series extended dto carries metadata fields used by TVDB mapper"
```

Expected: PASS if DTO already has all fields; compile failure if `width`/`height` are missing.

- [ ] **Step 3: Add missing DTO fields**

If `width` and `height` are missing in `TvdbArtworkRecord`, update `TvdbApi.kt`:

```kotlin
@JsonClass(generateAdapter = true)
data class TvdbArtworkRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "image") val image: String? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "score") val score: Double? = null,
    @Json(name = "thumbnail") val thumbnail: String? = null,
    @Json(name = "type") val type: Int? = null,
    @Json(name = "width") val width: Int? = null,
    @Json(name = "height") val height: Int? = null
)
```

- [ ] **Step 4: Add short=false contract assertion for router provider path**

Add this assertion to an existing TVDB provider/contract test, or add a small test next to adapter tests:

```kotlin
@Test
fun `tvdb series extended router path requests short false`() {
    val extended = TvdbApi::class.java.methods.first { it.name == "getSeriesExtended" }
    assertEquals("series/{id}/extended", extended.getAnnotation(retrofit2.http.GET::class.java)?.value)
    val shortParameter = extended.parameters.last()
    assertEquals(Boolean::class.javaObjectType, shortParameter.type)
}
```

Keep the existing call-site assertions that verify `getSeriesExtended(..., null, false)` in TVDB service tests. The router provider path delegates through `fetchSeriesExtendedCached`, whose runtime load already calls `fetchSeriesExtendedWithinRuntimeLoad(tvdbId = tvdbId)` with default `short = false`.

- [ ] **Step 5: Run DTO/contract tests to verify GREEN**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest"
```

Expected: PASS.

- [ ] **Step 6: Commit DTO/contract task**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt
git commit -m "test: lock TVDB extended artwork contract"
```

---

### Task 6: Router And Display Regression Coverage

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverArtworkDecisionTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeTest.kt`

- [ ] **Step 1: Add field resolver trace assertion**

In `FieldResolverArtworkDecisionTest`, update `resolved backdrop and logo strings are derived from artwork refs` so `logoRef` has:

```kotlin
selectedProvider = com.nexio.tv.core.artwork.ArtworkProviderId.RuntimeProvider(com.nexio.tv.core.integration.IntegrationProvider.TVDB),
sourceRole = ArtworkSourceRole.PRIMARY,
trace = ArtworkTrace(
    selectedProvider = "TVDB",
    sourceRole = "PRIMARY",
    reason = "metadata_artwork_provider_selection"
)
```

Add assertions:

```kotlin
assertEquals(SourceRole.ARTWORK, document.sourceRoles[ResolvedField.LOGO])
assertEquals("TVDB", (document.artwork.logo as ArtworkDisplayRef.RuntimeAsset).trace.selectedProvider)
assertEquals("PRIMARY", (document.artwork.logo as ArtworkDisplayRef.RuntimeAsset).trace.sourceRole)
```

- [ ] **Step 2: Add facade display metadata regression**

Add this test to `MetadataRouterFacadeTest`:

```kotlin
@Test
fun `metadata facade carries TVDB typed logo artwork into display metadata`() = runTest {
    val logoRef = artworkRef("tvdbLogoDecision", "tvdbLogoAsset", ArtworkType.LOGO)
    val result = facade(ArtworkLogoMetadataProviderAdapter(MetadataPrimaryProvider.TVDB, logoRef)).resolveRequest(
        MetadataRequest(
            contentId = "tvdb:121361",
            contentType = ContentType.SERIES,
            depth = MetadataDepth.DETAIL_CORE
        )
    )

    assertEquals(logoRef, result.resolvedDocument.artwork.logo)
    assertEquals("nexio-artwork://asset/tvdbLogoAsset", result.resolvedDocument.logo)
    assertEquals(logoRef, result.displayMetadata.artwork?.logo)
    assertEquals("nexio-artwork://asset/tvdbLogoAsset", result.displayMetadata.displayLogo)
}
```

Add this private adapter near the other test adapters in the same file:

```kotlin
private class ArtworkLogoMetadataProviderAdapter(
    override val provider: MetadataPrimaryProvider,
    private val logoRef: ArtworkDisplayRef
) : MetadataProviderAdapter {
    override fun supports(step: ProviderPlanStep): Boolean = true

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
        ProviderStepResult(
            step = step,
            candidate = MetadataCandidate(
                provider = provider,
                fields = mapOf(
                    ResolvedField.CANONICAL_ID to FieldValue("tvdb:121361", FieldOwner.PRIMARY),
                    ResolvedField.TITLE to FieldValue("Game of Thrones", FieldOwner.PRIMARY),
                    ResolvedField.LOGO to FieldValue(logoRef, FieldOwner.ARTWORK, SourceRole.ARTWORK)
                )
            )
        )
}
```

- [ ] **Step 3: Run router/display tests to verify GREEN**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.core.metadata.router.FieldResolverArtworkDecisionTest" --tests "com.nexio.tv.core.metadata.router.MetadataRouterFacadeTest"
```

Expected: PASS.

- [ ] **Step 4: Commit router/display test task**

```bash
git add app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverArtworkDecisionTest.kt app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeTest.kt
git commit -m "test: retain TVDB logo artwork through display metadata"
```

---

### Task 7: Final Verification

**Files:**
- No new files.

- [ ] **Step 1: Run focused TVDB/artwork/router tests**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests "com.nexio.tv.data.integration.metadata.TvdbArtworkCandidateMapperTest" \
  --tests "com.nexio.tv.data.integration.metadata.MetadataArtworkDecisionResolverTest" \
  --tests "com.nexio.tv.data.integration.metadata.TvdbCoreLocalizationTest" \
  --tests "com.nexio.tv.data.integration.metadata.TvdbMetadataProviderAdapterArtworkTest" \
  --tests "com.nexio.tv.core.metadata.router.FieldResolverArtworkDecisionTest" \
  --tests "com.nexio.tv.core.metadata.router.MetadataRouterFacadeTest" \
  --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest"
```

Expected: PASS.

- [ ] **Step 2: Run architecture boundary test for raw artwork URLs**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.architecture.RawRemoteArtworkUrlBoundaryTest"
```

Expected: PASS. This guards against raw remote artwork URLs leaking into UI paths.

- [ ] **Step 3: Inspect diff for forbidden changes**

Run:

```bash
git diff -- app/src/main/java/com/nexio/tv/ui app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt
```

Expected: no UI changes and no production call from router to legacy `TvdbMetadataService`.

- [ ] **Step 4: Inspect touched files**

Run:

```bash
git status --short
```

Expected: only TVDB adapter, metadata artwork mapper/resolver, DTO if needed, and test files changed.

- [ ] **Step 5: Final commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/metadata app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt app/src/test/java/com/nexio/tv/data/integration/metadata app/src/test/java/com/nexio/tv/core/metadata/router app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt
git commit -m "fix: retain TVDB clearlogo artwork in metadata router"
```

---

## Self-Review

**Spec coverage:**
- Parse `extended.artworks`: Task 1 and Task 4.
- Map `2`, `3`, `23`: Task 1.
- Use constants: Task 1.
- Avoid UI fix: Task 7 diff check.
- Avoid legacy service as router caller: Task 7 diff check.
- Emit shared artwork refs through router: Task 2, Task 3, Task 4.
- Ensure `short=false`: Task 5.
- Tests for logo/backdrop/poster retention: Tasks 1, 4, 6.
- `/series/{id}/artworks` fallback: explicitly out of scope for this packet.

**Placeholder scan:** No deferred-work markers, no implementation placeholders, and each code-changing step has concrete code.

**Type consistency:** `TvdbArtworkCandidateMapper.mapSeriesArtwork`, `MetadataArtworkDecisionResolver.resolveFields`, and `buildTvdbCoreLocalizedCandidate(..., artworkFields = ...)` are introduced before use in adapter wiring.
