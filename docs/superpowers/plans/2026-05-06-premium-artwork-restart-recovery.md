# Premium Artwork Restart Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make premium poster artwork survive app restart, remove remaining raw premium URL display paths, and ensure failed premium materialization falls back instead of rendering blank cards.

**Architecture:** Keep `nexio-artwork://decision/{decisionKey}` as a durable semantic pointer and `nexio-artwork://asset/{assetKey}` as the fast disk-backed pointer. Persist safe `ArtworkDecision` records, materialize them through `ArtworkAssetRepository` and `IntegrationRuntime`, and sanitize legacy snapshots that contain raw RPDB/Top-Posters URLs or unrecoverable decision refs. Asset-ref promotion is a P1 optimization; this P0 packet accepts durable decision refs in snapshots as long as matching durable decision records exist.

**Tech Stack:** Kotlin, Android/Hilt, Gson, SharedPreferences/file-backed cache patterns, Coil fetchers, Robolectric unit tests, Gradle Android test tasks, adb/logcat for on-device verification.

---

## RCA Summary

The device at `192.168.50.98` showed the router selecting RPDB correctly, but home persisted poster strings as `nexio-artwork://decision/...` while `ArtworkDecisionCache` was process-memory only. After restart, Coil still receives decision refs from `home_catalog_snapshot.xml`, but `ArtworkAssetRepository.getOrFetchDecision()` cannot find the corresponding decision object and returns null.

Device evidence from 2026-05-06:

```text
home_catalog_snapshot.xml:
  nexio-artwork://decision refs: 276
  unique decision refs: 90
  nexio-artwork://asset refs: 0
  raw Top-Posters URL occurrences: 347

/data/data/com.nexio.tv/cache/artwork-assets/RPDB/poster:
  RPDB shared artwork files: 98

/data/data/com.nexio.tv/cache:
  legacy integration-poster-*.img files: 1040
```

The fix is durable decisions plus failed-artwork fallback, not raw provider URL fallback. Asset-ref promotion can improve performance later, but durable decision recovery is the required correctness fix.

---

## File Structure

Modify `app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionCache.kt`
- Keep the existing synchronous interface shape for low-churn compatibility.
- Add `remove(key)` and a file-backed implementation entry point.
- Keep `InMemoryArtworkDecisionCache` for tests.

Create `app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCache.kt`
- Persist safe DTOs for `ArtworkDecision`.
- Store no raw API keys and no raw premium URLs.
- Load decisions lazily into memory and flush atomically to disk after mutating operations.

Modify `app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt`
- Provide `DurableArtworkDecisionCache` as the production `ArtworkDecisionCache`.

Modify `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
- Keep disk-first asset lookup.
- Add fallback candidate materialization for recoverable provider failures.
- Emit `artwork.fallback_materialized` and keep `artwork.decision_missing`.

Modify `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt`
- Add shared-artwork projection for `MetaPreview`.
- Stop home refresh from calling legacy raw `apply(...)`.
- Keep `resolvePosterUrl(...)` only for legacy tests/compat paths, not production home surfaces.

Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt`
- Replace `PosterRatingsUrlResolver.apply(...)` usage with shared artwork decision refs.
- Keep internal artwork refs out of raw image prefetch.

Modify `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
- Sanitize raw premium URLs on read/write.
- Clear unrecoverable decision refs when durable decision cache does not have the decision.
- Persist asset refs when items already carry asset refs.
- Keep decision refs when a durable decision exists; do not require asset refs in snapshots for P0 correctness.

Create `app/src/main/java/com/nexio/tv/ui/components/FallbackArtworkImage.kt`
- Render `AsyncImage` for the current model.
- If Coil errors, switch to fallback model.
- If fallback also errors or no fallback exists, render `MonochromePosterPlaceholder`.

Modify `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt`
- Use `FallbackArtworkImage` for modern home poster cards.

Modify tests:
- `app/src/test/java/com/nexio/tv/core/artwork/ArtworkDecisionCacheTest.kt`
- `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`
- `app/src/test/java/com/nexio/tv/core/poster/PosterRatingsUrlResolverTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTest.kt`
- `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt`
- `app/src/test/java/com/nexio/tv/architecture/RawRemoteArtworkUrlBoundaryTest.kt`

---

## Task 1: Durable Artwork Decision Cache

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionCache.kt`
- Create: `app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCache.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkDecisionCacheTest.kt`

- [ ] **Step 1: Extend rejected candidate persistence fields**

Modify `RejectedArtworkCandidate` in `ArtworkModels.kt` before adding durable-cache DTO tests:

```kotlin
data class RejectedArtworkCandidate(
    val provider: ArtworkProviderId?,
    val sourceRole: ArtworkSourceRole,
    val reason: String,
    val sourceHash: String? = null,
    val redactedSourceForTrace: String? = null,
    val providerTemplate: PersistedProviderTemplate? = null,
    val priority: Int = 0
)
```

Existing call sites should continue compiling because every new field has a default value.

- [ ] **Step 2: Extend the cache contract test first**

Add these imports and tests to `ArtworkDecisionCacheTest.kt`. Reuse the existing test helpers in that file if they already exist; otherwise add the helper code below at the bottom of the test class.

```kotlin
import com.google.gson.Gson
import com.nexio.tv.core.integration.IntegrationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
```

```kotlin
@Test
fun `durable cache survives process restart without raw secrets`() {
    val temp = TemporaryFolder().also { it.create() }
    val file = temp.newFile("artwork-decisions.json")
    val first = DurableArtworkDecisionCache(file = file, gson = Gson())
    val decision = durableRpdbDecision()

    first.put(decision)

    val raw = file.readText()
    assertTrue(raw.contains(decision.decisionKey.value))
    assertFalse(raw.contains("rpdb-key"))
    assertFalse(raw.contains("https://api.ratingposterdb.com"))
    assertFalse(raw.contains("https://api.top-posters.com"))

    val second = DurableArtworkDecisionCache(file = file, gson = Gson())
    val restored = second.get(decision.decisionKey)

    assertEquals(decision.decisionKey, restored?.decisionKey)
    assertEquals("RPDB", restored?.selectedCandidate?.provider?.key)
    assertEquals("imdb", restored?.selectedCandidate?.providerTemplate?.idType)
    assertEquals("tt15940132", restored?.selectedCandidate?.providerTemplate?.mediaId)
    assertEquals(decision.credentialHash, restored?.credentialHash)
}

@Test
fun `durable cache remove deletes persisted decision`() {
    val temp = TemporaryFolder().also { it.create() }
    val file = temp.newFile("artwork-decisions.json")
    val cache = DurableArtworkDecisionCache(file = file, gson = Gson())
    val decision = durableRpdbDecision()

    cache.put(decision)
    cache.remove(decision.decisionKey)

    val restarted = DurableArtworkDecisionCache(file = file, gson = Gson())
    assertNull(restarted.get(decision.decisionKey))
}

@Test
fun `durable cache restores rejected fallback candidate source data`() {
    val temp = TemporaryFolder().also { it.create() }
    val file = temp.newFile("artwork-decisions.json")
    val first = DurableArtworkDecisionCache(file = file, gson = Gson())
    val fallbackTemplate = PersistedProviderTemplate(
        provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
        imageType = ArtworkType.POSTER,
        idType = "tmdb",
        mediaId = "550",
        providerPathHash = "fallbackpathhash",
        settingsHash = null,
        credentialHash = null,
        imageLanguage = "en",
        policyVersion = 1
    )
    val decision = durableRpdbDecision().copy(
        rejectedCandidates = listOf(
            RejectedArtworkCandidate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                sourceRole = ArtworkSourceRole.PRIMARY,
                reason = "available_fallback",
                sourceHash = "fallbacksourcehash",
                redactedSourceForTrace = "https://image.tmdb.org/t/p/w500/<redacted>",
                providerTemplate = fallbackTemplate,
                priority = 10
            )
        )
    )

    first.put(decision)

    val restored = DurableArtworkDecisionCache(file = file, gson = Gson())
        .get(decision.decisionKey)
        ?.rejectedCandidates
        ?.single()

    assertEquals("fallbacksourcehash", restored?.sourceHash)
    assertEquals("https://image.tmdb.org/t/p/w500/<redacted>", restored?.redactedSourceForTrace)
    assertEquals(fallbackTemplate, restored?.providerTemplate)
    assertEquals(10, restored?.priority)
}

@Test
fun `durable cache invalidates premium decisions by credential hash`() {
    val temp = TemporaryFolder().also { it.create() }
    val file = temp.newFile("artwork-decisions.json")
    val cache = DurableArtworkDecisionCache(file = file, gson = Gson())
    val decision = durableRpdbDecision()

    cache.put(decision)
    cache.invalidateByCredentialHash("credentialhash")

    assertNull(cache.get(decision.decisionKey))
}
```

Add this helper:

```kotlin
private fun durableRpdbDecision(): ArtworkDecision =
    ArtworkDecision(
        decisionKey = ArtworkDecisionKey(
            "artwork-decision:poster:canonical:imdb:tt15940132:provider:RPDB:" +
                "premium:true:settings:settingshash:credential:credentialhash:imageLang:en:policy:1"
        ),
        ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt15940132"),
        canonicalContentId = "imdb:tt15940132",
        imageType = ArtworkType.POSTER,
        selectedCandidate = PersistedArtworkCandidate(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            sourceRole = ArtworkSourceRole.PREMIUM,
            sourceHash = "sourcehash",
            redactedSourceForTrace = null,
            providerTemplate = PersistedProviderTemplate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                imageType = ArtworkType.POSTER,
                idType = "imdb",
                mediaId = "tt15940132",
                providerPathHash = "pathhash",
                settingsHash = "settingshash",
                credentialHash = "credentialhash",
                imageLanguage = "en",
                policyVersion = 1
            ),
            priority = 100
        ),
        rejectedCandidates = listOf(
            RejectedArtworkCandidate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                sourceRole = ArtworkSourceRole.PRIMARY,
                reason = "premium_selected"
            )
        ),
        policyVersion = 1,
        imageLanguage = "en",
        settingsHash = "settingshash",
        credentialHash = "credentialhash",
        createdAtMs = 1_000L,
        expiresAtMs = 2_000L,
        staleUntilMs = 3_000L
    )
```

- [ ] **Step 3: Run the cache tests and verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkDecisionCacheTest
```

Expected failure:

```text
Unresolved reference: DurableArtworkDecisionCache
Unresolved reference: remove
```

- [ ] **Step 4: Add `remove` to the cache interface and memory implementation**

Modify `ArtworkDecisionCache.kt`:

```kotlin
interface ArtworkDecisionCache {
    fun get(key: ArtworkDecisionKey): ArtworkDecision?
    fun put(decision: ArtworkDecision)
    fun remove(key: ArtworkDecisionKey)
    fun linkPreviewToCanonical(
        previewKey: ArtworkDecisionKey,
        canonicalKey: ArtworkDecisionKey
    )
    fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision?
    fun invalidateBySettingsHash(settingsHash: String)
    fun invalidateByCredentialHash(credentialHash: String)
    fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>)
    fun invalidatePremiumArtworkPolicy()
}
```

Add this method to `InMemoryArtworkDecisionCache`:

```kotlin
@Synchronized
override fun remove(key: ArtworkDecisionKey) {
    decisions.remove(key)
    val links = previewToCanonical.iterator()
    while (links.hasNext()) {
        val (previewKey, canonicalKey) = links.next()
        if (previewKey == key || canonicalKey == key) {
            links.remove()
        }
    }
}
```

- [ ] **Step 5: Implement the durable cache**

Create `DurableArtworkDecisionCache.kt` with this content:

```kotlin
package com.nexio.tv.core.artwork

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.nexio.tv.core.integration.IntegrationProvider
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DurableArtworkDecisionCache(
    private val file: File,
    private val gson: Gson
) : ArtworkDecisionCache {
    private val lock = Any()
    private var loaded = false
    private val decisions = linkedMapOf<ArtworkDecisionKey, ArtworkDecision>()
    private val previewToCanonical = linkedMapOf<ArtworkDecisionKey, ArtworkDecisionKey>()

    override fun get(key: ArtworkDecisionKey): ArtworkDecision? = synchronized(lock) {
        ensureLoadedLocked()
        decisions[key]
    }

    override fun put(decision: ArtworkDecision) = synchronized(lock) {
        ensureLoadedLocked()
        decisions[decision.decisionKey] = decision
        persistLocked()
    }

    override fun remove(key: ArtworkDecisionKey) = synchronized(lock) {
        ensureLoadedLocked()
        decisions.remove(key)
        removeLinksForLocked(setOf(key))
        persistLocked()
    }

    override fun linkPreviewToCanonical(
        previewKey: ArtworkDecisionKey,
        canonicalKey: ArtworkDecisionKey
    ) = synchronized(lock) {
        ensureLoadedLocked()
        previewToCanonical[previewKey] = canonicalKey
        persistLocked()
    }

    override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? = synchronized(lock) {
        ensureLoadedLocked()
        previewToCanonical[previewKey]?.let(decisions::get)
    }

    override fun invalidateBySettingsHash(settingsHash: String) = synchronized(lock) {
        ensureLoadedLocked()
        invalidateMatchingLocked { it.settingsHash == settingsHash }
    }

    override fun invalidateByCredentialHash(credentialHash: String) = synchronized(lock) {
        ensureLoadedLocked()
        invalidateMatchingLocked { it.credentialHash == credentialHash }
    }

    override fun invalidateArtworkPolicy(
        settingsHashes: Set<String>,
        credentialHashes: Set<String>
    ) = synchronized(lock) {
        ensureLoadedLocked()
        invalidateMatchingLocked { decision ->
            decision.settingsHash in settingsHashes || decision.credentialHash in credentialHashes
        }
    }

    override fun invalidatePremiumArtworkPolicy() = synchronized(lock) {
        ensureLoadedLocked()
        invalidateMatchingLocked { decision ->
            decision.settingsHash != null || decision.credentialHash != null
        }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return

        val raw = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val type = object : TypeToken<StoreDto>() {}.type
        val dto = runCatching { gson.fromJson<StoreDto>(raw, type) }.getOrNull() ?: return
        if (dto.schemaVersion != SCHEMA_VERSION) return

        dto.decisions
            .mapNotNull { it.toDomainOrNull() }
            .forEach { decision -> decisions[decision.decisionKey] = decision }
        dto.previewLinks.forEach { link ->
            runCatching {
                previewToCanonical[ArtworkDecisionKey(link.previewKey)] = ArtworkDecisionKey(link.canonicalKey)
            }
        }
    }

    private fun invalidateMatchingLocked(matches: (ArtworkDecision) -> Boolean) {
        val deleted = decisions.values
            .filter(matches)
            .mapTo(mutableSetOf()) { it.decisionKey }
        if (deleted.isEmpty()) return

        deleted.forEach(decisions::remove)
        removeLinksForLocked(deleted)
        persistLocked()
    }

    private fun removeLinksForLocked(keys: Set<ArtworkDecisionKey>) {
        val iterator = previewToCanonical.iterator()
        while (iterator.hasNext()) {
            val (previewKey, canonicalKey) = iterator.next()
            if (previewKey in keys || canonicalKey in keys) {
                iterator.remove()
            }
        }
    }

    private fun persistLocked() {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val dto = StoreDto(
            schemaVersion = SCHEMA_VERSION,
            decisions = decisions.values.map(DecisionDto::fromDomain),
            previewLinks = previewToCanonical.map { (preview, canonical) ->
                PreviewLinkDto(previewKey = preview.value, canonicalKey = canonical.value)
            }
        )
        val tempFile = File(file.parentFile ?: File("."), "${file.name}.tmp")
        tempFile.writeText(gson.toJson(dto))
        try {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private data class StoreDto(
        val schemaVersion: Int,
        val decisions: List<DecisionDto>,
        val previewLinks: List<PreviewLinkDto>
    )

    private data class PreviewLinkDto(
        val previewKey: String,
        val canonicalKey: String
    )

    private data class DecisionDto(
        val decisionKey: String,
        val owner: OwnerDto,
        val canonicalContentId: String?,
        val imageType: String,
        val selectedCandidate: CandidateDto,
        val rejectedCandidates: List<RejectedDto>,
        val policyVersion: Int,
        val imageLanguage: String,
        val settingsHash: String?,
        val credentialHash: String?,
        val createdAtMs: Long,
        val expiresAtMs: Long,
        val staleUntilMs: Long?
    ) {
        fun toDomainOrNull(): ArtworkDecision? = runCatching {
            ArtworkDecision(
                decisionKey = ArtworkDecisionKey(decisionKey),
                ownerKey = owner.toDomain(),
                canonicalContentId = canonicalContentId,
                imageType = ArtworkType.valueOf(imageType),
                selectedCandidate = selectedCandidate.toDomain(),
                rejectedCandidates = rejectedCandidates.map { it.toDomain() },
                policyVersion = policyVersion,
                imageLanguage = imageLanguage,
                settingsHash = settingsHash,
                credentialHash = credentialHash,
                createdAtMs = createdAtMs,
                expiresAtMs = expiresAtMs,
                staleUntilMs = staleUntilMs
            )
        }.getOrNull()

        companion object {
            fun fromDomain(decision: ArtworkDecision): DecisionDto =
                DecisionDto(
                    decisionKey = decision.decisionKey.value,
                    owner = OwnerDto.fromDomain(decision.ownerKey),
                    canonicalContentId = decision.canonicalContentId,
                    imageType = decision.imageType.name,
                    selectedCandidate = CandidateDto.fromDomain(decision.selectedCandidate),
                    rejectedCandidates = decision.rejectedCandidates.map(RejectedDto::fromDomain),
                    policyVersion = decision.policyVersion,
                    imageLanguage = decision.imageLanguage,
                    settingsHash = decision.settingsHash,
                    credentialHash = decision.credentialHash,
                    createdAtMs = decision.createdAtMs,
                    expiresAtMs = decision.expiresAtMs,
                    staleUntilMs = decision.staleUntilMs
                )
        }
    }

    private data class OwnerDto(
        val type: String,
        val contentId: String?,
        val itemKey: String?,
        val sourcePayloadHash: String?
    ) {
        fun toDomain(): ArtworkOwnerKey = when (type) {
            "canonical" -> ArtworkOwnerKey.CanonicalContent(requireNotNull(contentId))
            "preview" -> ArtworkOwnerKey.PreviewItem(
                itemKey = requireNotNull(itemKey),
                sourcePayloadHash = requireNotNull(sourcePayloadHash)
            )
            else -> error("Unknown owner type $type")
        }

        companion object {
            fun fromDomain(owner: ArtworkOwnerKey): OwnerDto = when (owner) {
                is ArtworkOwnerKey.CanonicalContent -> OwnerDto(
                    type = "canonical",
                    contentId = owner.contentId,
                    itemKey = null,
                    sourcePayloadHash = null
                )
                is ArtworkOwnerKey.PreviewItem -> OwnerDto(
                    type = "preview",
                    contentId = null,
                    itemKey = owner.itemKey,
                    sourcePayloadHash = owner.sourcePayloadHash
                )
            }
        }
    }

    private data class CandidateDto(
        val provider: ProviderDto?,
        val sourceRole: String,
        val sourceHash: String?,
        val redactedSourceForTrace: String?,
        val providerTemplate: TemplateDto?,
        val priority: Int
    ) {
        fun toDomain(): PersistedArtworkCandidate =
            PersistedArtworkCandidate(
                provider = provider?.toDomain(),
                sourceRole = ArtworkSourceRole.valueOf(sourceRole),
                sourceHash = sourceHash,
                redactedSourceForTrace = redactedSourceForTrace,
                providerTemplate = providerTemplate?.toDomain(),
                priority = priority
            )

        companion object {
            fun fromDomain(candidate: PersistedArtworkCandidate): CandidateDto =
                CandidateDto(
                    provider = candidate.provider?.let(ProviderDto::fromDomain),
                    sourceRole = candidate.sourceRole.name,
                    sourceHash = candidate.sourceHash,
                    redactedSourceForTrace = candidate.redactedSourceForTrace,
                    providerTemplate = candidate.providerTemplate?.let(TemplateDto::fromDomain),
                    priority = candidate.priority
                )
        }
    }

    private data class RejectedDto(
        val provider: ProviderDto?,
        val sourceRole: String,
        val reason: String,
        val sourceHash: String?,
        val redactedSourceForTrace: String?,
        val providerTemplate: TemplateDto?,
        val priority: Int
    ) {
        fun toDomain(): RejectedArtworkCandidate =
            RejectedArtworkCandidate(
                provider = provider?.toDomain(),
                sourceRole = ArtworkSourceRole.valueOf(sourceRole),
                reason = reason,
                sourceHash = sourceHash,
                redactedSourceForTrace = redactedSourceForTrace,
                providerTemplate = providerTemplate?.toDomain(),
                priority = priority
            )

        companion object {
            fun fromDomain(rejected: RejectedArtworkCandidate): RejectedDto =
                RejectedDto(
                    provider = rejected.provider?.let(ProviderDto::fromDomain),
                    sourceRole = rejected.sourceRole.name,
                    reason = rejected.reason,
                    sourceHash = rejected.sourceHash,
                    redactedSourceForTrace = rejected.redactedSourceForTrace,
                    providerTemplate = rejected.providerTemplate?.let(TemplateDto::fromDomain),
                    priority = rejected.priority
                )
        }
    }

    private data class TemplateDto(
        val provider: ProviderDto,
        val imageType: String,
        val idType: String,
        val mediaId: String,
        val providerPathHash: String?,
        val settingsHash: String?,
        val credentialHash: String?,
        val imageLanguage: String,
        val policyVersion: Int,
        val pathParams: Map<String, String>
    ) {
        fun toDomain(): PersistedProviderTemplate =
            PersistedProviderTemplate(
                provider = provider.toDomain(),
                imageType = ArtworkType.valueOf(imageType),
                idType = idType,
                mediaId = mediaId,
                providerPathHash = providerPathHash,
                settingsHash = settingsHash,
                credentialHash = credentialHash,
                imageLanguage = imageLanguage,
                policyVersion = policyVersion,
                pathParams = pathParams
            )

        companion object {
            fun fromDomain(template: PersistedProviderTemplate): TemplateDto =
                TemplateDto(
                    provider = ProviderDto.fromDomain(template.provider),
                    imageType = template.imageType.name,
                    idType = template.idType,
                    mediaId = template.mediaId,
                    providerPathHash = template.providerPathHash,
                    settingsHash = template.settingsHash,
                    credentialHash = template.credentialHash,
                    imageLanguage = template.imageLanguage,
                    policyVersion = template.policyVersion,
                    pathParams = template.pathParams
                )
        }
    }

    private data class ProviderDto(
        val type: String,
        val integrationProvider: String?
    ) {
        fun toDomain(): ArtworkProviderId = when (type) {
            "runtime" -> ArtworkProviderId.RuntimeProvider(IntegrationProvider.valueOf(requireNotNull(integrationProvider)))
            "rail_preview" -> ArtworkProviderId.RailPreview
            "addon_preview" -> ArtworkProviderId.AddonPreview
            "placeholder" -> ArtworkProviderId.Placeholder
            else -> error("Unknown provider type $type")
        }

        companion object {
            fun fromDomain(provider: ArtworkProviderId): ProviderDto = when (provider) {
                is ArtworkProviderId.RuntimeProvider -> ProviderDto(
                    type = "runtime",
                    integrationProvider = provider.providerId.name
                )
                ArtworkProviderId.RailPreview -> ProviderDto(type = "rail_preview", integrationProvider = null)
                ArtworkProviderId.AddonPreview -> ProviderDto(type = "addon_preview", integrationProvider = null)
                ArtworkProviderId.Placeholder -> ProviderDto(type = "placeholder", integrationProvider = null)
            }
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
    }
}
```

- [ ] **Step 6: Wire Hilt to use the durable cache**

Modify `IntegrationRuntimeModule.kt` imports:

```kotlin
import com.google.gson.Gson
import com.nexio.tv.core.artwork.DurableArtworkDecisionCache
```

Replace `provideArtworkDecisionCache()`:

```kotlin
@Provides
@Singleton
fun provideArtworkDecisionCache(
    @ApplicationContext context: Context,
    gson: Gson
): ArtworkDecisionCache =
    DurableArtworkDecisionCache(
        file = File(context.filesDir, "artwork-decisions-v1.json"),
        gson = gson
    )
```

Add this import:

```kotlin
import java.io.File
```

- [ ] **Step 7: Run cache tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkDecisionCacheTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 8: Commit Task 1**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionCache.kt \
  app/src/main/java/com/nexio/tv/core/artwork/ArtworkModels.kt \
  app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCache.kt \
  app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt \
  app/src/test/java/com/nexio/tv/core/artwork/ArtworkDecisionCacheTest.kt
git commit -m "fix(artwork): persist artwork decisions across restart"
```

---

## Task 2: Decision Materialization After Restart

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`

- [ ] **Step 1: Add a restart materialization test**

Add this test to `ArtworkAssetRepositoryTest.kt`:

```kotlin
@Test
fun `decision ref materializes from durable cache after repository restart`() = runTest {
    val decisionFile = temp.newFile("decisions.json")
    val diskCache = ArtworkAssetDiskCache(temp.root)
    val decision = rpdbTemplateDecision()

    DurableArtworkDecisionCache(decisionFile, com.google.gson.Gson()).put(decision)

    val restartedCache = DurableArtworkDecisionCache(decisionFile, com.google.gson.Gson())
    val runtime = LoadingIntegrationRuntime()
    val repository = repository(
        runtime = runtime,
        cache = restartedCache,
        diskCache = diskCache,
        byteLoader = ArtworkByteLoader { _, _ ->
            IntegrationLoadResult.Success("after-restart".toByteArray())
        }
    )

    val result = repository.getOrFetchDecision(decision.decisionKey)

    assertNotNull(result)
    assertArrayEquals("after-restart".toByteArray(), result!!.localFile.readBytes())
    assertEquals("MISS_THEN_NETWORK", result.cacheDecision)
}
```

- [ ] **Step 2: Run the restart materialization test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest
```

Expected before Task 1 is complete:

```text
Unresolved reference: DurableArtworkDecisionCache
```

Expected after Task 1 is complete:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 3: Add fallback materialization tests**

Add this test to `ArtworkAssetRepositoryTest.kt`:

```kotlin
@Test
fun `selected provider failure falls back to primary remote candidate`() = runTest {
    val selected = rpdbTemplateDecision()
    val fallback = remotePreviewCandidate()
    val decision = selected.copy(
        selectedCandidate = selected.selectedCandidate,
        rejectedCandidates = selected.rejectedCandidates + RejectedArtworkCandidate(
            provider = fallback.provider,
            sourceRole = fallback.sourceRole,
            reason = "available_fallback",
            sourceHash = fallback.sourceHash,
            redactedSourceForTrace = fallback.redactedSourceForTrace,
            providerTemplate = fallback.providerTemplate,
            priority = fallback.priority
        )
    )
    val cache = InMemoryArtworkDecisionCache()
    cache.put(decision)
    val runtime = LoadingIntegrationRuntime()
    var loadCount = 0
    val repository = repository(
        runtime = runtime,
        cache = cache,
        sourceMaterializer = ArtworkSourceMaterializer(
            mapOf(requireNotNull(fallback.sourceHash) to SensitiveArtworkUrl.of("https://image.tmdb.org/t/p/w500/fallback.jpg"))
        ),
        byteLoader = ArtworkByteLoader { source, _ ->
            loadCount += 1
            if (source is ArtworkSource.ProviderTemplate) {
                IntegrationLoadResult.NetworkError(IllegalStateException("premium unavailable"))
            } else {
                IntegrationLoadResult.Success("fallback-bytes".toByteArray())
            }
        }
    )

    val result = repository.getOrFetchDecision(decision.decisionKey)

    assertNotNull(result)
    assertArrayEquals("fallback-bytes".toByteArray(), result!!.localFile.readBytes())
    assertEquals(2, loadCount)
    assertEquals("FALLBACK_MATERIALIZED", result.cacheDecision)
}
```

Add this helper near existing decision helpers:

```kotlin
private fun remotePreviewCandidate(): PersistedArtworkCandidate =
    PersistedArtworkCandidate(
        provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
        sourceRole = ArtworkSourceRole.PRIMARY,
        sourceHash = "fallbacksourcehash",
        redactedSourceForTrace = "https://image.tmdb.org/t/p/w500/<redacted>",
        providerTemplate = null,
        priority = 10
    )
```

- [ ] **Step 4: Run the fallback test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest
```

Expected failure:

```text
expected:<fallback-bytes> but was:<null>
```

- [ ] **Step 5: Implement fallback candidate materialization**

Modify `ArtworkAssetRepository.getOrFetchDecision()` to call a fallback helper when selected materialization returns null:

```kotlin
val result = getOrFetch(decision) ?: getOrFetchFallback(decision)
```

Add this helper to `ArtworkAssetRepository`:

```kotlin
private suspend fun getOrFetchFallback(decision: ArtworkDecision): ArtworkAssetResult? {
    val fallbackCandidates = decision.rejectedCandidates
        .filter { rejected ->
            rejected.provider != null &&
                rejected.sourceRole in setOf(
                    ArtworkSourceRole.PRIMARY,
                    ArtworkSourceRole.RAIL_PREVIEW,
                    ArtworkSourceRole.ADDON_PREVIEW,
                    ArtworkSourceRole.FALLBACK
                )
        }

    for (candidate in fallbackCandidates) {
        val fallbackDecision = decision.copy(
            selectedCandidate = PersistedArtworkCandidate(
                provider = candidate.provider,
                sourceRole = candidate.sourceRole,
                sourceHash = candidate.sourceHash,
                redactedSourceForTrace = candidate.redactedSourceForTrace,
                providerTemplate = candidate.providerTemplate,
                priority = candidate.priority
            ),
            rejectedCandidates = emptyList()
        )
        val result = getOrFetch(fallbackDecision)
        if (result != null) {
            traceArtwork(
                eventType = "artwork.fallback_materialized",
                payload = mapOf(
                    "decisionKey" to decision.decisionKey.value,
                    "fallbackProvider" to result.record.provider?.key,
                    "assetKey" to result.assetKey.value
                )
            )
            return result.copy(cacheDecision = "FALLBACK_MATERIALIZED")
        }
    }
    return null
}
```

The helper must use the `sourceHash`, `redactedSourceForTrace`, `providerTemplate`, and `priority` fields added to `RejectedArtworkCandidate` in Task 1. Do not introduce a hard-coded fallback source hash.


- [ ] **Step 6: Ensure router records fallback source data**

Modify `ArtworkRouter` where it creates rejected candidates so fallback candidates include source material. For each rejected `ArtworkCandidate`, build:

```kotlin
RejectedArtworkCandidate(
    provider = candidate.provider,
    sourceRole = candidate.sourceRole,
    reason = reason,
    sourceHash = candidate.persistedSourceHashOrNull(),
    redactedSourceForTrace = candidate.redactedSourceForTraceOrNull(),
    providerTemplate = candidate.persistedProviderTemplateOrNull(policy.policyVersion),
    priority = candidate.priority
)
```

If `persistedSourceHashOrNull`, `redactedSourceForTraceOrNull`, or `persistedProviderTemplateOrNull` already exist privately in `PosterRatingsUrlResolver`, move them to a small shared helper in `ArtworkCandidatePersistence.kt`:

```kotlin
package com.nexio.tv.core.artwork

fun ArtworkCandidate.toPersistedCandidate(policyVersion: Int): PersistedArtworkCandidate =
    PersistedArtworkCandidate(
        provider = provider,
        sourceRole = sourceRole,
        sourceHash = when (val src = source) {
            is ArtworkSource.RemoteUrl -> src.normalizedUrlHash
            is ArtworkSource.ProviderTemplate -> src.providerPathHash
            is ArtworkSource.LocalAsset -> src.assetKey.value
            is ArtworkSource.Placeholder -> null
        },
        redactedSourceForTrace = (source as? ArtworkSource.RemoteUrl)?.redactedUrlForTrace,
        providerTemplate = (source as? ArtworkSource.ProviderTemplate)?.let { template ->
            PersistedProviderTemplate(
                provider = template.provider,
                imageType = imageType,
                idType = template.idType,
                mediaId = template.mediaId,
                providerPathHash = template.providerPathHash,
                settingsHash = template.settingsHash,
                credentialHash = template.credentialHash,
                imageLanguage = imageLanguage,
                policyVersion = policyVersion,
                pathParams = template.pathParams
            )
        },
        priority = priority
    )
```

- [ ] **Step 7: Run focused artwork tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest --tests com.nexio.tv.core.artwork.ArtworkRouterTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 8: Commit Task 2**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork \
  app/src/test/java/com/nexio/tv/core/artwork
git commit -m "fix(artwork): recover decision refs and fallback after restart"
```

---

## Task 3: Remove Raw Premium URL Home Bypass

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt`
- Test: `app/src/test/java/com/nexio/tv/core/poster/PosterRatingsUrlResolverTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTest.kt`

- [ ] **Step 1: Add resolver test for `MetaPreview` artwork refs**

Add this test to `PosterRatingsUrlResolverTest.kt`:

```kotlin
@Test
fun `meta preview premium projection returns shared artwork ref not raw provider url`() {
    val cache = InMemoryArtworkDecisionCache()
    val resolver = resolver(cache)
    val preview = com.nexio.tv.domain.model.MetaPreview(
        id = "tmdb:550",
        type = ContentType.MOVIE,
        rawType = "movie",
        name = "Fight Club",
        poster = "https://image.tmdb.org/t/p/w500/fallback.jpg",
        posterShape = com.nexio.tv.domain.model.PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        firstPaintStableIds = ProviderIds(tmdb = "550", imdb = "tt0137523")
    )

    val resolved = resolver.applyArtworkRef(
        metaPreview = preview,
        settings = rpdbSettings()
    )

    assertInternalArtworkRef(resolved.poster)
    assertNoRawPremiumUrl(resolved.poster)
    assertEquals("rpdb", resolved.posterProviderTag)
    val decision = cache.get(decisionKeyFromRef(resolved.poster!!))
    assertEquals("RPDB", decision?.selectedCandidate?.provider?.key)
}

@Test
fun `meta preview fallback poster is not tagged as premium`() {
    val cache = InMemoryArtworkDecisionCache()
    val resolver = resolver(cache)
    val preview = com.nexio.tv.domain.model.MetaPreview(
        id = "source-preview-1",
        type = ContentType.MOVIE,
        rawType = "movie",
        name = "No Premium Id",
        poster = "https://image.tmdb.org/t/p/w500/fallback.jpg",
        posterShape = com.nexio.tv.domain.model.PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        firstPaintStableIds = ProviderIds()
    )

    val resolved = resolver.applyArtworkRef(
        metaPreview = preview,
        settings = rpdbSettings()
    )

    assertEquals("https://image.tmdb.org/t/p/w500/fallback.jpg", resolved.poster)
    assertNull(resolved.posterProviderTag)
}
```

- [ ] **Step 2: Run resolver test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.poster.PosterRatingsUrlResolverTest
```

Expected failure:

```text
Unresolved reference: applyArtworkRef
```

- [ ] **Step 3: Add shared-artwork projection to `PosterRatingsUrlResolver`**

Add imports:

```kotlin
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import java.security.MessageDigest
```

Add this method:

```kotlin
fun applyArtworkRef(
    metaPreview: MetaPreview,
    settings: ArtworkProviderSettings
): MetaPreview {
    val providerTag = settings.selection.posterProvider
        .takeIf { it != ArtworkProviderChoiceKey.DEFAULT }
        ?.name
        ?.lowercase()
    val ownerKey = metaPreview.artworkOwnerKey()
    val mediaKind = when (metaPreview.type) {
        ContentType.MOVIE -> MetadataMediaKind.MOVIE
        ContentType.SERIES, ContentType.TV -> MetadataMediaKind.SERIES
        ContentType.ANIME -> MetadataMediaKind.ANIME
        else -> MetadataMediaKind.UNKNOWN
    }
    val resolved = resolvePosterArtworkString(
        settings = settings,
        providerIds = metaPreview.firstPaintStableIds,
        mediaKind = mediaKind,
        ownerKey = ownerKey,
        fallbackPosterUrl = metaPreview.poster
    ) ?: metaPreview.poster?.takeUnless { it.isPremiumProviderRawUrl() }
    val resolvedIsPremiumArtworkRef =
        resolved != null && resolved.isPremiumDecisionRef(providerTag)

    return metaPreview.copy(
        poster = resolved,
        posterShape = metaPreview.posterShape.takeIf { resolved != null } ?: PosterShape.POSTER,
        posterProviderTag = if (resolvedIsPremiumArtworkRef) providerTag else null
    )
}

// Keep this as a private member function of PosterRatingsUrlResolver, not a top-level extension,
// because it needs access to the injected artworkDecisionCache.
private fun String.isPremiumDecisionRef(providerTag: String?): Boolean {
    if (providerTag == null) return false
    val key = removePrefix("nexio-artwork://decision/")
    if (key == this) return false
    val decision = artworkDecisionCache.get(ArtworkDecisionKey(key)) ?: return false
    return decision.selectedCandidate.sourceRole == ArtworkSourceRole.PREMIUM
}

private fun MetaPreview.artworkOwnerKey(): ArtworkOwnerKey {
    val stableIds = firstPaintStableIds
    val canonicalId = stableIds.imdb
        ?.takeIf { it.isNotBlank() }
        ?.let { "imdb:$it" }
        ?: stableIds.tmdb
            ?.takeIf { it.isNotBlank() }
            ?.let { "tmdb:$it" }
    if (canonicalId != null) {
        return ArtworkOwnerKey.CanonicalContent(canonicalId)
    }

    return ArtworkOwnerKey.PreviewItem(
        itemKey = id,
        sourcePayloadHash = stablePreviewHash()
    )
}

private fun MetaPreview.stablePreviewHash(): String {
    val payload = listOf(id, type.name, rawType, name, poster.orEmpty()).joinToString("|")
    return MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
```

Keep `resolvePosterUrl(...)` private or annotate it to prevent new production use:

```kotlin
@Deprecated(
    message = "Legacy raw-provider compatibility only. Production display paths must use applyArtworkRef/resolvePosterArtworkString.",
    level = DeprecationLevel.WARNING
)
```

- [ ] **Step 4: Add home coordinator test that rejects legacy `apply`**

Add this test to `HomeCatalogRefreshCoordinatorTest.kt`:

```kotlin
@Test
fun `home refresh routes premium posters through shared artwork refs`() = runTest {
    val catalogRepository = mockk<CatalogRepository>(relaxed = true)
    val metadataRouterFacade = mockk<MetadataRouterFacade>()
    val titleRatingOverrideRepository = mockk<TitleRatingOverrideRepository>()
    val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
    val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>()
    val input = preview(id = "550", poster = "https://image.tmdb.org/t/p/w500/fallback.jpg").copy(
        type = ContentType.MOVIE,
        rawType = "movie"
    )
    val routed = input.copy(
        poster = "nexio-artwork://decision/artwork-decision:poster:canonical:tmdb:550:provider:RPDB:premium:true:settings:s:credential:c:imageLang:en:policy:1",
        posterProviderTag = "rpdb"
    )
    coEvery { titleRatingOverrideRepository.enrichPreview(any()) } answers { firstArg() }
    coEvery { metadataRouterFacade.resolveRequest(any()) } returns null
    every { posterRatingsUrlResolver.applyArtworkRef(any(), any()) } returns routed

    coordinator(
        catalogRepository = catalogRepository,
        metadataRouterFacade = metadataRouterFacade,
        titleRatingOverrideRepository = titleRatingOverrideRepository,
        metadataDiskCacheStore = metadataDiskCacheStore,
        posterRatingsUrlResolver = posterRatingsUrlResolver
    ).prefetchVisibleImagesOnly(
        items = listOf(input),
        telemetryEnabled = false,
        onLog = { _, _ -> }
    )

    verify(exactly = 0) { posterRatingsUrlResolver.apply(any<MetaPreview>(), any()) }
}
```

- [ ] **Step 5: Replace home raw URL mutation**

In `HomeCatalogRefreshCoordinator.kt`, replace:

```kotlin
posterRatingsUrlResolver.apply(enriched, activePosterProvider)
```

with:

```kotlin
val activeArtworkSettings = posterRatingsUrlResolver.currentSettings()
posterRatingsUrlResolver.applyArtworkRef(
    metaPreview = enriched,
    settings = activeArtworkSettings
)
```

Because this code is inside a coroutine/suspend path, calling `currentSettings()` is valid. If the current function is not marked `suspend`, hoist the settings read to the enclosing suspend caller that already computes `activePosterProvider`.

- [ ] **Step 6: Run home and resolver tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.poster.PosterRatingsUrlResolverTest --tests com.nexio.tv.ui.screens.home.HomeCatalogRefreshCoordinatorTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 7: Add architecture scan**

Add this assertion to `RawRemoteArtworkUrlBoundaryTest.kt`:

```kotlin
@Test
fun `home production code does not call legacy poster ratings apply`() {
    val source = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt").readText()
    assertFalse(
        "Home refresh must not call PosterRatingsUrlResolver.apply because it writes raw premium URLs.",
        source.contains("posterRatingsUrlResolver.apply(")
    )
}
```

- [ ] **Step 8: Run architecture scan**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.architecture.RawRemoteArtworkUrlBoundaryTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 9: Commit Task 3**

```bash
git add app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt \
  app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt \
  app/src/test/java/com/nexio/tv/core/poster/PosterRatingsUrlResolverTest.kt \
  app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTest.kt \
  app/src/test/java/com/nexio/tv/architecture/RawRemoteArtworkUrlBoundaryTest.kt
git commit -m "fix(home): route premium posters through artwork refs"
```

---

## Task 4: Snapshot Migration And Sanitization

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt`

- [ ] **Step 1: Add snapshot sanitization tests**

Add these tests to `HomeCatalogSnapshotStoreTest.kt`:

```kotlin
@Test
fun `write sanitizes raw premium provider urls from home snapshot`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cache = InMemoryArtworkDecisionCache()
    val store = homeSnapshotStore(context = context, artworkDecisionCache = cache)
    val row = CatalogRow(
        addonId = "trakt",
        addonName = "Trakt",
        addonBaseUrl = "https://api.trakt.tv",
        catalogId = "trakt_trending_shows",
        catalogName = "Trakt Trending Shows",
        type = ContentType.SERIES,
        rawType = "series",
        items = listOf(
            snapshotPreview(
                poster = "https://api.top-posters.com/raw-key/imdb/poster/tt123.jpg",
                posterProviderTag = "top_posters"
            )
        )
    )

    store.write(
        snapshot = HomeCatalogSnapshotStore.Snapshot(
            catalogRows = listOf(row),
            fullCatalogRows = emptyList(),
            heroItems = emptyList()
        ),
        posterProviderToken = "TOP_POSTERS:hash"
    )

    val raw = context
        .getSharedPreferences("home_catalog_snapshot", Context.MODE_PRIVATE)
        .getString("snapshot:p1:en", "")
        .orEmpty()
    assertFalse(raw.contains("api.top-posters.com"))
    assertFalse(raw.contains("raw-key"))
}

@Test
fun `read clears decision ref when durable decision is missing`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cache = InMemoryArtworkDecisionCache()
    val store = homeSnapshotStore(context = context, artworkDecisionCache = cache)
    val missingDecision = "nexio-artwork://decision/artwork-decision:poster:canonical:imdb:tt123:provider:RPDB:premium:true:settings:s:credential:c:imageLang:en:policy:1"
    val row = CatalogRow(
        addonId = "trakt",
        addonName = "Trakt",
        addonBaseUrl = "https://api.trakt.tv",
        catalogId = "trakt_trending_shows",
        catalogName = "Trakt Trending Shows",
        type = ContentType.SERIES,
        rawType = "series",
        items = listOf(snapshotPreview(poster = missingDecision, posterProviderTag = "rpdb"))
    )

    store.write(
        snapshot = HomeCatalogSnapshotStore.Snapshot(
            catalogRows = listOf(row),
            fullCatalogRows = emptyList(),
            heroItems = emptyList()
        ),
        posterProviderToken = "RPDB:hash"
    )

    val restored = store.read(posterProviderToken = "RPDB:hash")

    assertEquals(null, restored?.catalogRows?.single()?.items?.single()?.poster)
    assertEquals(null, restored?.catalogRows?.single()?.items?.single()?.posterProviderTag)
}
```

Add helper:

```kotlin
private fun snapshotPreview(
    poster: String?,
    posterProviderTag: String?
): MetaPreview =
    MetaPreview(
        id = "tt123",
        type = ContentType.SERIES,
        rawType = "series",
        name = "Snapshot Item",
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        posterProviderTag = posterProviderTag
    )
```

- [ ] **Step 2: Run snapshot tests and verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest
```

Expected failure:

```text
raw premium URL still present
```

- [ ] **Step 3: Inject `ArtworkDecisionCache` into `HomeCatalogSnapshotStore`**

Add import:

```kotlin
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDecisionKey
```

Update primary constructor:

```kotlin
private val artworkDecisionCache: ArtworkDecisionCache,
```

Update injected constructor to pass `artworkDecisionCache`.

Update test constructor to pass `InMemoryArtworkDecisionCache()` when callers do not supply one.

- [ ] **Step 4: Add snapshot sanitizer helpers**

Add these helpers to `HomeCatalogSnapshotStore`:

```kotlin
private fun sanitizePremiumArtworkFields(item: MetaPreview): MetaPreview {
    val poster = item.poster
    if (poster.isNullOrBlank()) return item

    return when {
        poster.isRawPremiumProviderUrl() -> item.copy(
            poster = null,
            posterProviderTag = null
        )
        poster.startsWith("nexio-artwork://decision/") && !hasDurableDecision(poster) -> item.copy(
            poster = null,
            posterProviderTag = null
        )
        else -> item
    }
}

private fun hasDurableDecision(ref: String): Boolean {
    val key = ref.substringAfter("nexio-artwork://decision/", missingDelimiterValue = "")
    if (key.isBlank()) return false
    return runCatching { artworkDecisionCache.get(ArtworkDecisionKey(key)) != null }.getOrDefault(false)
}

private fun String.isRawPremiumProviderUrl(): Boolean =
    startsWith("https://api.ratingposterdb.com/", ignoreCase = true) ||
        startsWith("https://api.top-posters.com/", ignoreCase = true)
```

Modify `sanitizeMetaPreviews`:

```kotlin
private fun sanitizeMetaPreviews(values: List<*>, label: String): List<MetaPreview> {
    return values.mapIndexedNotNull { index, value ->
        val item = value as? MetaPreview
        if (item == null) {
            Log.w(TAG, "Dropping malformed cached $label[$index]: ${value?.javaClass?.name}")
        }
        item?.sanitizedForCache()?.let(::sanitizePremiumArtworkFields)
    }
}
```

Modify `write(...)` to sanitize before JSON serialization:

```kotlin
val sanitizedSnapshot = snapshot.sanitize()
```

Then serialize `sanitizedSnapshot.catalogRows`, `sanitizedSnapshot.fullCatalogRows`, and `sanitizedSnapshot.heroItems`.

- [ ] **Step 5: Run snapshot tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 6: Commit Task 4**

```bash
git add app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt \
  app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt
git commit -m "fix(home): sanitize stale premium artwork snapshots"
```

---

## Task 5: UI Fallback For Failed Artwork Models

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/components/FallbackArtworkImage.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/components/FallbackArtworkImageTest.kt`

- [ ] **Step 1: Add UI fallback component test**

Create `FallbackArtworkImageTest.kt`:

```kotlin
package com.nexio.tv.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertExists
import org.junit.Rule
import org.junit.Test

class FallbackArtworkImageTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun failedPrimaryWithoutFallbackShowsPlaceholder() {
        composeRule.setContent {
            FallbackArtworkImage(
                model = "nexio-artwork://decision/missing",
                fallbackModel = null,
                contentDescription = "Poster",
                modifier = androidx.compose.ui.Modifier,
                testForceError = true
            )
        }

        composeRule.onNodeWithTag("fallback-artwork-placeholder").assertExists()
    }

    @Test
    fun nullPrimaryUsesFallbackModel() {
        composeRule.setContent {
            FallbackArtworkImage(
                model = null,
                fallbackModel = "https://image.tmdb.org/t/p/w500/fallback.jpg",
                contentDescription = "Poster",
                modifier = androidx.compose.ui.Modifier,
                testTag = "fallback-artwork-image"
            )
        }

        composeRule.onNodeWithTag("fallback-artwork-image").assertExists()
    }
}
```

- [ ] **Step 2: Run UI fallback test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.components.FallbackArtworkImageTest
```

Expected:

```text
Unresolved reference: FallbackArtworkImage
```

- [ ] **Step 3: Create `FallbackArtworkImage`**

Create `FallbackArtworkImage.kt`:

```kotlin
package com.nexio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import coil.compose.AsyncImage

@Composable
fun FallbackArtworkImage(
    model: Any?,
    fallbackModel: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    testTag: String = "fallback-artwork-image",
    testForceError: Boolean = false
) {
    var failedPrimary by remember(model) { mutableStateOf(false) }
    var failedFallback by remember(fallbackModel) { mutableStateOf(false) }

    LaunchedEffect(testForceError) {
        if (testForceError) failedPrimary = true
    }

    val activeModel = when {
        model != null && !failedPrimary -> model
        fallbackModel != null && !failedFallback -> fallbackModel
        else -> null
    }

    if (activeModel == null) {
        MonochromePosterPlaceholder(
            modifier = modifier.testTag("fallback-artwork-placeholder")
        )
        return
    }

    AsyncImage(
        model = activeModel,
        contentDescription = contentDescription,
        modifier = modifier.testTag(testTag),
        contentScale = contentScale,
        onError = {
            if (!failedPrimary) {
                failedPrimary = true
            } else {
                failedFallback = true
            }
        }
    )
}
```

- [ ] **Step 4: Use fallback component in modern home cards**

In `ModernHomeRows.kt`, replace the `AsyncImage` inside:

```kotlin
if (hasImage) {
    AsyncImage(
        model = imageModel,
        contentDescription = item.title,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
} else {
    MonochromePosterPlaceholder()
}
```

with:

```kotlin
if (hasImage) {
    FallbackArtworkImage(
        model = imageModel,
        fallbackModel = item.heroPreview.poster?.takeIf { it != imageUrl },
        contentDescription = item.title,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
} else {
    MonochromePosterPlaceholder()
}
```

Add import:

```kotlin
import com.nexio.tv.ui.components.FallbackArtworkImage
```

- [ ] **Step 5: Run UI fallback tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.components.FallbackArtworkImageTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 6: Commit Task 5**

```bash
git add app/src/main/java/com/nexio/tv/ui/components/FallbackArtworkImage.kt \
  app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt \
  app/src/test/java/com/nexio/tv/ui/components/FallbackArtworkImageTest.kt
git commit -m "fix(home): show fallback for failed artwork models"
```

---

## Task 6: Durable Decision Ref Projection Guardrails

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkLegacyProjection.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkLegacyProjectionTest.kt`
- Test: `app/src/test/java/com/nexio/tv/architecture/RawRemoteArtworkUrlBoundaryTest.kt`

- [ ] **Step 1: Add legacy projection tests**

Add tests to `ArtworkLegacyProjectionTest.kt`:

```kotlin
@Test
fun `runtime asset projection prefers asset key over decision key`() {
    val ref = ArtworkDisplayRef.RuntimeAsset(
        decisionKey = ArtworkDecisionKey("artwork-decision:poster:canonical:imdb:tt123"),
        assetKey = ArtworkAssetKey("artwork-asset:RPDB:poster:imdb:tt123:settings:s:credential:c:imageLang:en:policy:1"),
        imageType = ArtworkType.POSTER,
        selectedProvider = ArtworkProviderId.RuntimeProvider(com.nexio.tv.core.integration.IntegrationProvider.RPDB),
        sourceRole = ArtworkSourceRole.PREMIUM,
        trace = ArtworkTrace.empty()
    )

    assertEquals(
        "nexio-artwork://asset/artwork-asset:RPDB:poster:imdb:tt123:settings:s:credential:c:imageLang:en:policy:1",
        ref.toLegacyArtworkString()
    )
}

@Test
fun `runtime asset projection uses decision key when asset key absent`() {
    val ref = ArtworkDisplayRef.RuntimeAsset(
        decisionKey = ArtworkDecisionKey("artwork-decision:poster:canonical:imdb:tt123"),
        assetKey = null,
        imageType = ArtworkType.POSTER,
        selectedProvider = ArtworkProviderId.RuntimeProvider(com.nexio.tv.core.integration.IntegrationProvider.RPDB),
        sourceRole = ArtworkSourceRole.PREMIUM,
        trace = ArtworkTrace.empty()
    )

    assertEquals(
        "nexio-artwork://decision/artwork-decision:poster:canonical:imdb:tt123",
        ref.toLegacyArtworkString()
    )
}
```

Asset-ref promotion is deliberately deferred from this P0 packet. These tests only preserve the projection contract: use asset refs when already present, and allow decision refs when no asset key has been promoted yet.

- [ ] **Step 2: Run projection tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkLegacyProjectionTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 3: Add snapshot raw URL architecture scan**

Add this test to `RawRemoteArtworkUrlBoundaryTest.kt`:

```kotlin
@Test
fun `production snapshot writers do not persist raw premium urls`() {
    val files = listOf(
        File("app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt"),
        File("app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt"),
        File("app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt")
    )
    val forbidden = Regex("""https://api\.(top-posters|ratingposterdb)\.com""")
    files.forEach { file ->
        assertFalse(
            "Raw premium provider URLs must not be hard-coded or persisted by ${file.path}",
            forbidden.containsMatchIn(file.readText())
        )
    }
}
```

- [ ] **Step 4: Run architecture tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.architecture.RawRemoteArtworkUrlBoundaryTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit Task 6**

```bash
git add app/src/test/java/com/nexio/tv/core/artwork/ArtworkLegacyProjectionTest.kt \
  app/src/test/java/com/nexio/tv/architecture/RawRemoteArtworkUrlBoundaryTest.kt
git commit -m "test(artwork): guard asset ref projection and raw url snapshots"
```

---

## Task 7: Final Verification And Device RCA Checks

**Files:**
- No production changes.

- [ ] **Step 1: Run focused unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.nexio.tv.core.artwork.ArtworkDecisionCacheTest \
  --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest \
  --tests com.nexio.tv.core.image.NexioArtworkFetcherTest \
  --tests com.nexio.tv.core.poster.PosterRatingsUrlResolverTest \
  --tests com.nexio.tv.ui.screens.home.HomeCatalogRefreshCoordinatorTest \
  --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest \
  --tests com.nexio.tv.architecture.RawRemoteArtworkUrlBoundaryTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 2: Build debug APK**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 3: Install only when explicitly approved**

Do not install automatically. The user asked earlier to avoid debug installs unless requested.

When approved, run:

```bash
adb connect 192.168.50.98:5555
adb -s 192.168.50.98:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected:

```text
Success
```

- [ ] **Step 4: Verify snapshot no longer stores raw premium URLs**

Run after launching the app and letting home load:

```bash
adb -s 192.168.50.98:5555 exec-out su -c 'cat /data/data/com.nexio.tv/shared_prefs/home_catalog_snapshot.xml' \
  | rg -o 'https://api\.top-posters\.com|https://api\.ratingposterdb\.com|nexio-artwork://decision/[^"&< ]+|nexio-artwork://asset/[^"&< ]+' \
  | sort \
  | uniq -c
```

Expected:

```text
0 raw https://api.top-posters.com entries
  0 raw https://api.ratingposterdb.com entries
  decision refs allowed when matching durable decision records exist
  asset refs may be 0 in this P0 packet because promotion is deferred
```

- [ ] **Step 5: Verify durable decisions exist**

Run:

```bash
adb -s 192.168.50.98:5555 shell su -c 'ls -l /data/data/com.nexio.tv/files/artwork-decisions-v1.json; wc -c /data/data/com.nexio.tv/files/artwork-decisions-v1.json'
```

Expected:

```text
artwork-decisions-v1.json exists
file size is greater than 0 after home rows with premium artwork load
```

- [ ] **Step 6: Restart app and verify posters remain**

Run:

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexio.tv
adb -s 192.168.50.98:5555 shell monkey -p com.nexio.tv 1
```

Expected:

```text
Previously materialized premium posters render after restart.
Rows no longer turn into blank poster cards when decision refs are restored.
```

- [ ] **Step 7: Inspect logcat for decision recovery**

Run:

```bash
adb -s 192.168.50.98:5555 logcat -d -v time \
  | rg 'artwork\.decision_lookup|artwork\.decision_missing|artwork\.asset_materialized|runtime\.cache_decision|ARTWORK_DISK_HIT|FALLBACK_MATERIALIZED' \
  | tail -200
```

Expected:

```text
artwork.decision_lookup found=true for restored decision refs
artwork.asset_materialized success=true
runtime.cache_decision HIT or ARTWORK_DISK_HIT on repeat loads
artwork.decision_missing does not flood during normal home load
```

- [ ] **Step 8: Commit final verification notes if a markdown RCA was updated**

If the implementation updates an RCA markdown file, commit it:

```bash
git add docs/superpowers/plans/2026-05-06-premium-artwork-restart-recovery.md
git commit -m "docs(artwork): document premium restart recovery plan"
```

If no docs changed during implementation, skip this commit.

---

## Self-Review

Spec coverage:
- Durable decisions: Task 1.
- Decision refs materialize after restart: Task 2.
- Durable decision ref projection guardrails: Task 6.
- Remove raw premium URL home path: Task 3.
- Snapshot migration/sanitization: Task 4.
- UI fallback for failed models: Task 5.
- On-device verification: Task 7.

Placeholder scan:
- No `TBD`, `TODO`, or "implement later" entries remain.
- Every task has exact file paths, commands, expected results, and concrete code snippets.

Type consistency:
- `ArtworkDecisionCache` remains synchronous to match current call sites.
- `DurableArtworkDecisionCache` uses safe DTOs for sealed/value-class model persistence.
- `RejectedArtworkCandidate` persists fallback source fields so fallback materialization survives restart.
- `PosterRatingsUrlResolver.applyArtworkRef(...)` accepts `MetaPreview` plus `ArtworkProviderSettings`, avoiding raw `ActiveProvider` URL construction.
- `PosterRatingsUrlResolver.applyArtworkRef(...)` tags posters as premium only when the selected poster is a premium decision ref.
- `HomeCatalogSnapshotStore` sanitization checks `ArtworkDecisionKey` against the durable cache before accepting persisted decision refs.
- UI fallback handles both failed primary models and null primary models with a non-null fallback.
- Asset refs are not required in snapshots for P0 verification; durable decision refs are valid when backed by durable decision records.
