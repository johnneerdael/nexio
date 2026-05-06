# Premium Artwork Decision Materialization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `nexio-artwork://decision/{decisionKey}` Coil models materialize premium artwork decisions into cached asset files so RPDB and Top Posters posters render without exposing raw provider URLs.

**Architecture:** Keep provider selection in `ArtworkRouter` / `FieldResolver`; the image layer may only materialize an already-selected `ArtworkDecision`. `NexioArtworkFetcher` resolves decision refs through the shared `ArtworkDecisionCache`, `ArtworkAssetRepository`, `IntegrationRuntime`, `ArtworkByteLoader`, and `ArtworkAssetDiskCache`, then returns a local disk `SourceResult` to Coil. Raw RPDB/Top Posters URLs and `integration-poster://` remain outside the final UI/Coil model path.

**Tech Stack:** Android TV app, Kotlin, Hilt, Coil, Room-backed `IntegrationRuntime`, OkHttp-backed `PosterTransport`, JUnit/Robolectric, MockK.

---

## Current State And Scope

The RCA is saved at `review-dossier/android-premium-artwork-and-poster-ratings-settings-rca.md`.

The independent dialog scroll bug was already fixed in this branch:

- `app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsScreen.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsDialogScrollContractTest.kt`

This plan only implements the artwork pipeline fix.

## Runtime Type Boundary

Keep these result types deliberately separate:

- `ArtworkByteLoader.load(...)` returns `IntegrationLoadResult<ByteArray>`.
- `IntegrationRuntime.get(...)` maps the loader result plus cache state into `IntegrationFetchResult<ByteArray>`.
- `ArtworkAssetResult.runtimeResult` stores the `IntegrationFetchResult<ByteArray>` returned by `IntegrationRuntime.get(...)`.

Do not make `ArtworkAssetRepository` return `IntegrationLoadResult` directly. The repository must preserve the runtime cache decision (`Fresh`, `Updated`, `Stale`, `Missing`) because Coil materialization needs the runtime-owned cache/backoff boundary.

## File Structure

Modify:

- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
  - Own decision lookup and decision materialization.
  - Keep provider precedence out of the fetcher.
  - Emit small artwork trace events for decision lookup and materialization outcomes.

- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionCache.kt`
  - Keep the existing synchronous in-memory API. Do not convert it to `suspend`; it is process-local and all existing call sites are synchronous.

- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkCredentialResolver.kt`
  - New file. Resolve a raw provider key from current `PosterRatingsSettingsDataStore` only when the stored decision credential hash matches the current key hash.

- `app/src/main/java/com/nexio/tv/core/artwork/DefaultArtworkByteLoader.kt`
  - New file. Convert materialized artwork sources into bytes via `PosterTransport`, returning `IntegrationLoadResult<ByteArray>`.
  - Build RPDB poster, Top Posters poster, Top Posters thumbnail, and safe remote URL fetches.
  - Not a public provider-fetch API. Its `load(...)` method must only be invoked through `ArtworkAssetRepository` inside an `IntegrationRuntime.get(...)` loader lambda.

- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkCredentialHash.kt`
  - New file. Centralize the credential hash currently duplicated as private `stableHashHex` logic in `PosterRatingsUrlResolver`.

- `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt`
  - Use `ArtworkCredentialHash.hashCredential(apiKey)` for decision credential hashes.
  - Do not add raw API keys to `ArtworkDecision`.

- `app/src/main/java/com/nexio/tv/core/image/NexioArtworkFetcher.kt`
  - Support both asset and decision refs.
  - Decision refs call `ArtworkAssetRepository.getOrFetchDecision(decisionKey)`.

- `app/src/main/java/com/nexio/tv/NexioApplication.kt`
  - Inject and register the Hilt-managed `NexioArtworkFetcher.Factory`.
  - Remove manual partial `ArtworkAssetRepository(...)` construction.

- `app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt`
  - Provide `ArtworkAssetDiskCache`, `ArtworkSourceMaterializer`, `ArtworkByteLoader`, `ArtworkCredentialResolver`, and the shared `ArtworkAssetRepository` bindings needed by Hilt.

Tests:

- `app/src/test/java/com/nexio/tv/core/image/NexioArtworkFetcherTest.kt`
- `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`
- `app/src/test/java/com/nexio/tv/core/artwork/DefaultArtworkByteLoaderTest.kt`
- `app/src/test/java/com/nexio/tv/core/artwork/PosterRatingsArtworkCredentialResolverTest.kt`
- `app/src/test/java/com/nexio/tv/core/poster/PosterRatingsUrlResolverTest.kt`
- `app/src/test/java/com/nexio/tv/architecture/PremiumArtworkSharedPipelineContractTest.kt`

---

### Task 1: Add Fetcher Decision-URI Regression Tests

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/image/NexioArtworkFetcherTest.kt`
- Later modify: `app/src/main/java/com/nexio/tv/core/image/NexioArtworkFetcher.kt`

- [ ] **Step 1: Add missing imports to the test**

Add these imports to `NexioArtworkFetcherTest.kt`:

```kotlin
import com.nexio.tv.core.artwork.ArtworkAssetRecord
import com.nexio.tv.core.artwork.ArtworkAssetResult
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationProvider
import io.mockk.coEvery
```

`IntegrationFetchResult` is intentional here because `ArtworkAssetResult.runtimeResult` records the result from `IntegrationRuntime.get(...)`. Do not replace this with `IntegrationLoadResult`; byte loaders use `IntegrationLoadResult`, but repositories expose runtime fetch results.

- [ ] **Step 2: Add failing decision fetch test**

Add this test below `fetcher reads existing asset file from repository`:

```kotlin
@Test
fun `fetcher materializes decision uri through repository`() = runTest {
    val decisionKey = ArtworkDecisionKey("decision-key")
    val assetKey = ArtworkAssetKey("artwork-asset:RPDB:poster:imdb:tt0137523")
    val file = temp.newFile("decision-asset.bin")
    file.writeBytes("decision-image-bytes".toByteArray())
    val repository = mockk<ArtworkAssetRepository>()
    coEvery { repository.getOrFetchDecision(decisionKey) } returns ArtworkAssetResult(
        assetKey = assetKey,
        localFile = file,
        record = ArtworkAssetRecord(
            assetKey = assetKey,
            decisionKey = decisionKey,
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            imageType = ArtworkType.POSTER,
            imageLanguage = "en",
            relativePath = "artwork-assets/RPDB/poster/${assetKey.value}.bin",
            mimeType = "image/jpeg",
            byteCount = file.length(),
            sourceHash = "source-hash",
            policyVersion = 1,
            fetchedAtMs = 1_000L,
            expiresAtMs = 2_000L,
            staleUntilMs = 3_000L
        ),
        runtimeResult = IntegrationFetchResult.Updated<ByteArray>("decision-image-bytes".toByteArray()),
        runtimeApiShapeId = "rpdb.poster_template",
        cacheDecision = "MISS_THEN_NETWORK",
        mimeType = "image/jpeg",
        networkExecuted = true
    )
    val fetcher = NexioArtworkFetcher(
        assetKey = null,
        decisionKey = decisionKey,
        repository = repository
    )

    val result = fetcher.fetch()

    assertTrue(result is SourceResult)
    result as SourceResult
    assertEquals(DataSource.DISK, result.dataSource)
    assertEquals("image/jpeg", result.mimeType)
}
```

- [ ] **Step 3: Add failing missing-decision test**

Add this test below the decision fetch test:

```kotlin
@Test
fun `fetcher returns null when decision is missing`() = runTest {
    val decisionKey = ArtworkDecisionKey("missing-decision")
    val repository = mockk<ArtworkAssetRepository>()
    coEvery { repository.getOrFetchDecision(decisionKey) } returns null
    val fetcher = NexioArtworkFetcher(
        assetKey = null,
        decisionKey = decisionKey,
        repository = repository
    )

    val result = fetcher.fetch()

    assertNull(result)
}
```

- [ ] **Step 4: Update existing asset fetcher construction in the test**

Change the existing direct constructor call from:

```kotlin
val fetcher = NexioArtworkFetcher(
    assetKey = assetKey,
    repository = repository
)
```

to:

```kotlin
val fetcher = NexioArtworkFetcher(
    assetKey = assetKey,
    decisionKey = null,
    repository = repository
)
```

- [ ] **Step 5: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.image.NexioArtworkFetcherTest
```

Expected: FAIL. The failure should mention missing `decisionKey` constructor parameter and missing `getOrFetchDecision`.

- [ ] **Step 6: Commit failing tests**

```bash
git add app/src/test/java/com/nexio/tv/core/image/NexioArtworkFetcherTest.kt
git commit -m "test: capture nexio artwork decision fetch behavior"
```

---

### Task 2: Teach `NexioArtworkFetcher` To Carry Decision Keys

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/image/NexioArtworkFetcher.kt`
- Test: `app/src/test/java/com/nexio/tv/core/image/NexioArtworkFetcherTest.kt`

- [ ] **Step 1: Replace the fetcher constructor and fetch method**

In `NexioArtworkFetcher.kt`, replace the class header and `fetch()` method with:

```kotlin
class NexioArtworkFetcher(
    private val assetKey: ArtworkAssetKey?,
    private val decisionKey: ArtworkDecisionKey?,
    private val repository: ArtworkAssetRepository
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val result = when {
            assetKey != null -> {
                val file = repository.getExistingFile(assetKey) ?: return null
                ArtworkFetchFile(file = file, mimeType = null)
            }
            decisionKey != null -> {
                val materialized = repository.getOrFetchDecision(decisionKey) ?: return null
                ArtworkFetchFile(file = materialized.localFile, mimeType = materialized.mimeType)
            }
            else -> return null
        }
        val source = createImageSource(result.file)
        return SourceResult(
            source = source,
            mimeType = result.mimeType,
            dataSource = DataSource.DISK
        )
    }
```

- [ ] **Step 2: Add private file result model**

Add this inside `NexioArtworkFetcher`, below `createImageSource`:

```kotlin
    private data class ArtworkFetchFile(
        val file: java.io.File,
        val mimeType: String?
    )
```

- [ ] **Step 3: Update factory asset URI branch**

Replace the asset branch with:

```kotlin
            parseAssetKey(model)?.let { assetKey ->
                return NexioArtworkFetcher(
                    assetKey = assetKey,
                    decisionKey = null,
                    repository = repository
                )
            }
```

- [ ] **Step 4: Update factory decision URI branch**

Replace the decision branch with:

```kotlin
            parseDecisionKey(model)?.let { decisionKey ->
                return NexioArtworkFetcher(
                    assetKey = null,
                    decisionKey = decisionKey,
                    repository = repository
                )
            }
```

- [ ] **Step 5: Run fetcher tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.image.NexioArtworkFetcherTest
```

Expected: still FAIL because `ArtworkAssetRepository.getOrFetchDecision` is not implemented.

- [ ] **Step 6: Commit fetcher parsing and model changes**

```bash
git add app/src/main/java/com/nexio/tv/core/image/NexioArtworkFetcher.kt
git commit -m "feat: parse nexio artwork decision refs in Coil fetcher"
```

---

### Task 3: Add Repository Decision Lookup And Trace Tests

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`
- Later modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`

- [ ] **Step 1: Add trace imports**

Add:

```kotlin
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import org.junit.Assert.assertNull
```

- [ ] **Step 2: Add repository helper**

Inside `ArtworkAssetRepositoryTest`, add this helper before the fake runtime classes:

```kotlin
private fun repository(
    runtime: IntegrationRuntime,
    cache: ArtworkDecisionCache = InMemoryArtworkDecisionCache(),
    sourceMaterializer: ArtworkSourceMaterializer = ArtworkSourceMaterializer(emptyMap()),
    byteLoader: ArtworkByteLoader = ArtworkByteLoader { _, _ ->
        IntegrationLoadResult.Success("image-bytes".toByteArray())
    },
    traceSink: RuntimeTraceSink = RecordingArtworkTraceSink()
): ArtworkAssetRepository =
    ArtworkAssetRepository(
        runtime = runtime,
        diskCache = ArtworkAssetDiskCache(temp.root),
        sourceMaterializer = sourceMaterializer,
        byteLoader = byteLoader,
        decisionCache = cache,
        traceSink = traceSink
    )
```

- [ ] **Step 3: Add trace sink helper**

Add this helper below `repository(...)`:

```kotlin
private class RecordingArtworkTraceSink : RuntimeTraceSink {
    val events = mutableListOf<TraceEventEnvelope<*>>()

    override fun emit(event: TraceEventEnvelope<*>) {
        events += event
    }
}
```

- [ ] **Step 4: Add failing decision lookup test**

Add this test after `repository loader executes on runtime Updated path`:

```kotlin
@Test
fun `getOrFetchDecision looks up decision and materializes asset`() = runTest {
    val cache = InMemoryArtworkDecisionCache()
    val decision = rpdbTemplateDecision()
    cache.put(decision)
    val runtime = LoadingIntegrationRuntime()
    val repository = repository(
        runtime = runtime,
        cache = cache,
        byteLoader = ArtworkByteLoader { _, _ ->
            IntegrationLoadResult.Success("decision-image".toByteArray())
        }
    )

    val result = repository.getOrFetchDecision(decision.decisionKey)

    assertNotNull(result)
    result!!
    assertEquals(ArtworkCacheKeys.assetKeyForProviderTemplate(decision.selectedCandidate.providerTemplate!!), result.assetKey)
    assertArrayEquals("decision-image".toByteArray(), result.localFile.readBytes())
    assertEquals("MISS_THEN_NETWORK", result.cacheDecision)
}
```

- [ ] **Step 5: Add failing missing decision trace test**

Add:

```kotlin
@Test
fun `getOrFetchDecision returns null and traces missing decision`() = runTest {
    val traceSink = RecordingArtworkTraceSink()
    val repository = repository(
        runtime = LoadingIntegrationRuntime(),
        cache = InMemoryArtworkDecisionCache(),
        traceSink = traceSink
    )

    val result = repository.getOrFetchDecision(ArtworkDecisionKey("missing-decision"))

    assertNull(result)
    assertEquals("artwork.decision_lookup", traceSink.events.single().eventType)
    assertEquals(
        false,
        (traceSink.events.single().payload as Map<*, *>)["found"]
    )
}
```

- [ ] **Step 6: Add provider-template materialization test with empty source map**

Add:

```kotlin
@Test
fun `provider template decision materializes with empty source materializer`() = runTest {
    val cache = InMemoryArtworkDecisionCache()
    val decision = rpdbTemplateDecision()
    cache.put(decision)
    var loadedSource: ArtworkSource? = null
    val repository = repository(
        runtime = LoadingIntegrationRuntime(),
        cache = cache,
        sourceMaterializer = ArtworkSourceMaterializer(emptyMap()),
        byteLoader = ArtworkByteLoader { source, _ ->
            loadedSource = source
            IntegrationLoadResult.Success("template-image".toByteArray())
        }
    )

    val result = repository.getOrFetchDecision(decision.decisionKey)

    assertNotNull(result)
    assertArrayEquals("template-image".toByteArray(), result!!.localFile.readBytes())
    assertTrue(loadedSource is ArtworkSource.ProviderTemplate)
}
```

This locks the rule that `ProviderTemplate` decisions do not depend on the `remoteSourcesByHash` source map.

- [ ] **Step 7: Add remote-url missing-source traceability test**

Add:

```kotlin
@Test
fun `remote url decision missing source materializer fails traceably`() = runTest {
    val cache = InMemoryArtworkDecisionCache()
    val decision = remotePreviewDecision()
    cache.put(decision)
    val traceSink = RecordingArtworkTraceSink()
    val repository = repository(
        runtime = LoadingIntegrationRuntime(),
        cache = cache,
        sourceMaterializer = ArtworkSourceMaterializer(emptyMap()),
        byteLoader = ArtworkByteLoader { source, _ ->
            if (source is UnavailableRemoteArtworkSource) {
                IntegrationLoadResult.NetworkError(IllegalStateException("raw source unavailable"))
            } else {
                IntegrationLoadResult.Success("unexpected".toByteArray())
            }
        },
        traceSink = traceSink
    )

    val result = repository.getOrFetchDecision(decision.decisionKey)

    assertNull(result)
    assertEquals("artwork.decision_lookup", traceSink.events.first().eventType)
    assertEquals(true, (traceSink.events.first().payload as Map<*, *>)["found"])
    assertEquals("artwork.asset_materialized", traceSink.events.last().eventType)
    assertEquals(false, (traceSink.events.last().payload as Map<*, *>)["success"])
}
```

This locks the rule that `RemoteUrl` decisions without source-map material are allowed to fail, but must fail as a traced null result rather than crashing or inventing a provider URL.

- [ ] **Step 8: Run repository tests to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest
```

Expected: FAIL because constructor parameters and `getOrFetchDecision` do not exist.

- [ ] **Step 9: Commit failing repository tests**

```bash
git add app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt
git commit -m "test: capture artwork decision materialization repository path"
```

---

### Task 4: Implement Repository Decision Materialization

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`

- [ ] **Step 1: Add imports**

Add:

```kotlin
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import javax.inject.Inject
import javax.inject.Singleton
```

- [ ] **Step 2: Replace repository constructor**

Replace the class header with:

```kotlin
@Singleton
class ArtworkAssetRepository @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val diskCache: ArtworkAssetDiskCache,
    private val sourceMaterializer: ArtworkSourceMaterializer,
    private val byteLoader: ArtworkByteLoader,
    private val decisionCache: ArtworkDecisionCache,
    private val traceSink: RuntimeTraceSink = NoopRuntimeTraceSink
) {
```

- [ ] **Step 3: Add decision lookup method**

Add this method above `getOrFetch(decision: ArtworkDecision)`:

```kotlin
    suspend fun getOrFetchDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetResult? {
        val decision = decisionCache.get(decisionKey)
        traceArtwork(
            eventType = "artwork.decision_lookup",
            payload = mapOf(
                "decisionKey" to decisionKey.value,
                "found" to (decision != null)
            )
        )
        if (decision == null) return null

        val result = getOrFetch(decision)
        traceArtwork(
            eventType = "artwork.asset_materialized",
            payload = mapOf(
                "decisionKey" to decisionKey.value,
                "assetKey" to result?.assetKey?.value,
                "provider" to result?.record?.provider?.key,
                "imageType" to result?.record?.imageType?.name,
                "cacheDecision" to result?.cacheDecision,
                "networkExecuted" to result?.networkExecuted,
                "success" to (result != null)
            )
        )
        return result
    }
```

- [ ] **Step 4: Add trace helper**

Add this private method above `bytesOrNull()`:

```kotlin
    private fun traceArtwork(
        eventType: String,
        payload: Map<String, Any?>
    ) {
        traceSink.emit(
            TraceEventEnvelope(
                eventType = eventType,
                payload = payload
            )
        )
    }
```

- [ ] **Step 5: Update existing repository test construction**

In `ArtworkAssetRepositoryTest.kt`, replace direct `ArtworkAssetRepository(...)` calls with the `repository(...)` helper where practical. For example:

```kotlin
val repository = repository(
    runtime = runtime,
    byteLoader = ArtworkByteLoader { _, _ ->
        loaderCalled = true
        IntegrationLoadResult.Success("loaded".toByteArray())
    }
)
```

- [ ] **Step 6: Run repository and fetcher tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest --tests com.nexio.tv.core.image.NexioArtworkFetcherTest
```

Expected: PASS for repository decision lookup and fetcher decision refs, unless byte loader tests are not implemented yet.

- [ ] **Step 7: Commit repository materialization path**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt app/src/main/java/com/nexio/tv/core/image/NexioArtworkFetcher.kt
git commit -m "feat: materialize artwork decisions into asset files"
```

---

### Task 5: Centralize Artwork Credential Hashing

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkCredentialHash.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/core/poster/PosterRatingsUrlResolverTest.kt`

- [ ] **Step 1: Create credential hash utility**

Create `ArtworkCredentialHash.kt`:

```kotlin
package com.nexio.tv.core.artwork

object ArtworkCredentialHash {
    fun hashCredential(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(trimmed.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }
}
```

- [ ] **Step 2: Add test assertion that decisions store only hash**

In `PosterRatingsUrlResolverTest.kt`, add this assertion to both existing tests `top posters selected poster returns internal artwork ref without provider domain` and `rpdb selected poster returns internal artwork ref without provider domain`:

```kotlin
assertFalse(decision?.credentialHash.orEmpty().contains("key"))
assertEquals(64, decision?.credentialHash?.length)
```

- [ ] **Step 3: Update resolver imports**

In `PosterRatingsUrlResolver.kt`, add:

```kotlin
import com.nexio.tv.core.artwork.ArtworkCredentialHash
```

- [ ] **Step 4: Replace private credential hash implementation**

Replace:

```kotlin
    private fun ArtworkProviderSettings.credentialHash(providerChoice: ArtworkProviderChoiceKey): String? =
        when (providerChoice) {
            ArtworkProviderChoiceKey.RPDB -> rpdbApiKey.trim()
            ArtworkProviderChoiceKey.TOP_POSTERS -> topPostersApiKey.trim()
            else -> ""
        }.takeIf { it.isNotBlank() }?.let { stableHashHex(it) }
```

with:

```kotlin
    private fun ArtworkProviderSettings.credentialHash(providerChoice: ArtworkProviderChoiceKey): String? =
        when (providerChoice) {
            ArtworkProviderChoiceKey.RPDB -> ArtworkCredentialHash.hashCredential(rpdbApiKey)
            ArtworkProviderChoiceKey.TOP_POSTERS -> ArtworkCredentialHash.hashCredential(topPostersApiKey)
            else -> null
        }
```

- [ ] **Step 5: Run resolver tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.poster.PosterRatingsUrlResolverTest
```

Expected: PASS.

- [ ] **Step 6: Commit credential hash utility**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkCredentialHash.kt app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt app/src/test/java/com/nexio/tv/core/poster/PosterRatingsUrlResolverTest.kt
git commit -m "refactor: centralize artwork credential hashing"
```

---

### Task 6: Add Credential Resolver Tests

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkCredentialResolver.kt`
- Create: `app/src/test/java/com/nexio/tv/core/artwork/PosterRatingsArtworkCredentialResolverTest.kt`

- [ ] **Step 1: Create interface shell**

Create `ArtworkCredentialResolver.kt`:

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.ArtworkProviderSettings
import kotlinx.coroutines.flow.Flow

interface ArtworkCredentialResolver {
    suspend fun apiKeyFor(
        provider: IntegrationProvider,
        credentialHash: String?
    ): String?
}

interface ArtworkProviderSettingsSource {
    val settings: Flow<ArtworkProviderSettings>
}
```

- [ ] **Step 2: Create failing resolver test file**

Create `PosterRatingsArtworkCredentialResolverTest.kt`:

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.ArtworkProviderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosterRatingsArtworkCredentialResolverTest {
    @Test
    fun `rpdb key resolves only when credential hash matches current settings`() = runTest {
        val settings = MutableStateFlow(ArtworkProviderSettings(rpdbApiKey = "rpdb-key"))
        val resolver = PosterRatingsArtworkCredentialResolver(FakeArtworkProviderSettingsSource(settings))

        val result = resolver.apiKeyFor(
            provider = IntegrationProvider.RPDB,
            credentialHash = ArtworkCredentialHash.hashCredential("rpdb-key")
        )

        assertEquals("rpdb-key", result)
    }

    @Test
    fun `top posters key resolves only when credential hash matches current settings`() = runTest {
        val settings = MutableStateFlow(ArtworkProviderSettings(topPostersApiKey = "top-key"))
        val resolver = PosterRatingsArtworkCredentialResolver(FakeArtworkProviderSettingsSource(settings))

        val result = resolver.apiKeyFor(
            provider = IntegrationProvider.TOP_POSTERS,
            credentialHash = ArtworkCredentialHash.hashCredential("top-key")
        )

        assertEquals("top-key", result)
    }

    @Test
    fun `resolver returns null when current key hash differs from decision hash`() = runTest {
        val settings = MutableStateFlow(ArtworkProviderSettings(rpdbApiKey = "new-key"))
        val resolver = PosterRatingsArtworkCredentialResolver(FakeArtworkProviderSettingsSource(settings))

        val result = resolver.apiKeyFor(
            provider = IntegrationProvider.RPDB,
            credentialHash = ArtworkCredentialHash.hashCredential("old-key")
        )

        assertNull(result)
    }

    private class FakeArtworkProviderSettingsSource(
        override val settings: Flow<ArtworkProviderSettings>
    ) : ArtworkProviderSettingsSource
}
```

- [ ] **Step 3: Run resolver test to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.PosterRatingsArtworkCredentialResolverTest
```

Expected: FAIL because `PosterRatingsArtworkCredentialResolver` does not exist.

- [ ] **Step 4: Commit failing credential resolver tests**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkCredentialResolver.kt app/src/test/java/com/nexio/tv/core/artwork/PosterRatingsArtworkCredentialResolverTest.kt
git commit -m "test: capture artwork credential resolution"
```

---

### Task 7: Implement Poster Ratings Credential Resolver

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkCredentialResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/PosterRatingsArtworkCredentialResolverTest.kt`

- [ ] **Step 1: Replace `ArtworkCredentialResolver.kt` with the full implementation**

Replace `ArtworkCredentialResolver.kt` with:

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.domain.model.ArtworkProviderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

interface ArtworkCredentialResolver {
    suspend fun apiKeyFor(
        provider: IntegrationProvider,
        credentialHash: String?
    ): String?
}

interface ArtworkProviderSettingsSource {
    val settings: Flow<ArtworkProviderSettings>
}

@Singleton
class PosterRatingsArtworkProviderSettingsSource @Inject constructor(
    private val settingsDataStore: PosterRatingsSettingsDataStore
) : ArtworkProviderSettingsSource {
    override val settings = settingsDataStore.settings
}

@Singleton
class PosterRatingsArtworkCredentialResolver @Inject constructor(
    private val settingsSource: ArtworkProviderSettingsSource
) : ArtworkCredentialResolver {
    override suspend fun apiKeyFor(
        provider: IntegrationProvider,
        credentialHash: String?
    ): String? {
        if (credentialHash.isNullOrBlank()) return null
        val settings = settingsSource.settings.first()
        val candidate = when (provider) {
            IntegrationProvider.RPDB -> settings.rpdbApiKey.trim()
            IntegrationProvider.TOP_POSTERS -> settings.topPostersApiKey.trim()
            else -> return null
        }
        if (candidate.isBlank()) return null
        return candidate.takeIf {
            ArtworkCredentialHash.hashCredential(it) == credentialHash
        }
    }
}
```

- [ ] **Step 2: Run credential resolver tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.PosterRatingsArtworkCredentialResolverTest
```

Expected: PASS.

- [ ] **Step 3: Commit resolver implementation**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkCredentialResolver.kt app/src/test/java/com/nexio/tv/core/artwork/PosterRatingsArtworkCredentialResolverTest.kt
git commit -m "feat: resolve artwork provider credentials from settings"
```

---

### Task 8: Add Byte Loader Tests

**Files:**
- Create: `app/src/test/java/com/nexio/tv/core/artwork/DefaultArtworkByteLoaderTest.kt`
- Later create: `app/src/main/java/com/nexio/tv/core/artwork/DefaultArtworkByteLoader.kt`

- [ ] **Step 1: Create byte loader test file**

Create `DefaultArtworkByteLoaderTest.kt`:

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.data.integration.posters.transport.PosterTransportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultArtworkByteLoaderTest {
    @Test
    fun `rpdb provider template builds redacted-safe poster request`() = runTest {
        val transport = RecordingArtworkPosterTransport(
            PosterTransportResult(statusCode = 200, isSuccessful = true, body = "rpdb".toByteArray())
        )
        val loader = DefaultArtworkByteLoader(
            credentialResolver = StaticCredentialResolver(IntegrationProvider.RPDB, "credentialhash", "rpdb-key"),
            posterTransport = transport
        )
        val decision = templateDecision(
            provider = IntegrationProvider.RPDB,
            idType = "imdb",
            mediaId = "tt0137523",
            credentialHash = "credentialhash",
            imageType = ArtworkType.POSTER
        )

        val result = loader.load(decision.selectedCandidate.providerTemplate!!.toSource(), decision)

        assertTrue(result is IntegrationLoadResult.Success)
        assertArrayEquals("rpdb".toByteArray(), (result as IntegrationLoadResult.Success).value)
        assertEquals("https://api.ratingposterdb.com/rpdb-key/imdb/poster-default/tt0137523.jpg", transport.lastUrl)
    }

    @Test
    fun `top posters provider template builds poster request`() = runTest {
        val transport = RecordingArtworkPosterTransport(
            PosterTransportResult(statusCode = 200, isSuccessful = true, body = "top".toByteArray())
        )
        val loader = DefaultArtworkByteLoader(
            credentialResolver = StaticCredentialResolver(IntegrationProvider.TOP_POSTERS, "credentialhash", "top-key"),
            posterTransport = transport
        )
        val decision = templateDecision(
            provider = IntegrationProvider.TOP_POSTERS,
            idType = "tmdb",
            mediaId = "movie-550",
            credentialHash = "credentialhash",
            imageType = ArtworkType.POSTER
        )

        val result = loader.load(decision.selectedCandidate.providerTemplate!!.toSource(), decision)

        assertTrue(result is IntegrationLoadResult.Success)
        assertArrayEquals("top".toByteArray(), (result as IntegrationLoadResult.Success).value)
        assertEquals("https://api.top-posters.com/top-key/tmdb/poster/movie-550.jpg", transport.lastUrl)
    }

    @Test
    fun `provider template encodes id type and media id as path segments`() = runTest {
        val transport = RecordingArtworkPosterTransport(
            PosterTransportResult(statusCode = 200, isSuccessful = true, body = "encoded".toByteArray())
        )
        val loader = DefaultArtworkByteLoader(
            credentialResolver = StaticCredentialResolver(IntegrationProvider.TOP_POSTERS, "credentialhash", "top-key"),
            posterTransport = transport
        )
        val decision = templateDecision(
            provider = IntegrationProvider.TOP_POSTERS,
            idType = "tmdb id",
            mediaId = "movie/550",
            credentialHash = "credentialhash",
            imageType = ArtworkType.POSTER
        )

        val result = loader.load(decision.selectedCandidate.providerTemplate!!.toSource(), decision)

        assertTrue(result is IntegrationLoadResult.Success)
        assertEquals("https://api.top-posters.com/top-key/tmdb%20id/poster/movie%2F550.jpg", transport.lastUrl)
    }

    @Test
    fun `top posters thumbnail provider template builds thumbnail request`() = runTest {
        val transport = RecordingArtworkPosterTransport(
            PosterTransportResult(statusCode = 200, isSuccessful = true, body = "thumb".toByteArray())
        )
        val loader = DefaultArtworkByteLoader(
            credentialResolver = StaticCredentialResolver(IntegrationProvider.TOP_POSTERS, "credentialhash", "top-key"),
            posterTransport = transport
        )
        val decision = templateDecision(
            provider = IntegrationProvider.TOP_POSTERS,
            idType = "tvdb",
            mediaId = "1399",
            credentialHash = "credentialhash",
            imageType = ArtworkType.THUMBNAIL,
            pathParams = mapOf(
                "season" to "1",
                "episode" to "2",
                "badgeSize" to "small",
                "badgePosition" to "top-right",
                "blur" to "false"
            )
        )

        val result = loader.load(decision.selectedCandidate.providerTemplate!!.toSource(), decision)

        assertTrue(result is IntegrationLoadResult.Success)
        assertEquals(
            "https://api.top-posters.com/top-key/tvdb/thumbnail/1399/S1E2.jpg?badge_size=small&badge_position=top-right&blur=false",
            transport.lastUrl
        )
    }

    @Test
    fun `provider template returns missing credential error without network call`() = runTest {
        val transport = RecordingArtworkPosterTransport(
            PosterTransportResult(statusCode = 200, isSuccessful = true, body = "unused".toByteArray())
        )
        val loader = DefaultArtworkByteLoader(
            credentialResolver = StaticCredentialResolver(IntegrationProvider.RPDB, "otherhash", "rpdb-key"),
            posterTransport = transport
        )
        val decision = templateDecision(
            provider = IntegrationProvider.RPDB,
            idType = "imdb",
            mediaId = "tt0137523",
            credentialHash = "credentialhash",
            imageType = ArtworkType.POSTER
        )

        val result = loader.load(decision.selectedCandidate.providerTemplate!!.toSource(), decision)

        assertTrue(result is IntegrationLoadResult.NetworkError)
        assertEquals(null, transport.lastUrl)
    }

    @Test
    fun `premium key change old decision does not materialize`() = runTest {
        val transport = RecordingArtworkPosterTransport(
            PosterTransportResult(statusCode = 200, isSuccessful = true, body = "unused".toByteArray())
        )
        val loader = DefaultArtworkByteLoader(
            credentialResolver = StaticCredentialResolver(IntegrationProvider.RPDB, "newhash", "new-rpdb-key"),
            posterTransport = transport
        )
        val oldDecision = templateDecision(
            provider = IntegrationProvider.RPDB,
            idType = "imdb",
            mediaId = "tt0137523",
            credentialHash = "oldhash",
            imageType = ArtworkType.POSTER
        )

        val result = loader.load(oldDecision.selectedCandidate.providerTemplate!!.toSource(), oldDecision)

        assertTrue(result is IntegrationLoadResult.NetworkError)
        assertEquals(null, transport.lastUrl)
    }

    @Test
    fun `premium key change new decision materializes`() = runTest {
        val transport = RecordingArtworkPosterTransport(
            PosterTransportResult(statusCode = 200, isSuccessful = true, body = "new".toByteArray())
        )
        val loader = DefaultArtworkByteLoader(
            credentialResolver = StaticCredentialResolver(IntegrationProvider.RPDB, "newhash", "new-rpdb-key"),
            posterTransport = transport
        )
        val newDecision = templateDecision(
            provider = IntegrationProvider.RPDB,
            idType = "imdb",
            mediaId = "tt0137523",
            credentialHash = "newhash",
            imageType = ArtworkType.POSTER
        )

        val result = loader.load(newDecision.selectedCandidate.providerTemplate!!.toSource(), newDecision)

        assertTrue(result is IntegrationLoadResult.Success)
        assertArrayEquals("new".toByteArray(), (result as IntegrationLoadResult.Success).value)
        assertEquals("https://api.ratingposterdb.com/new-rpdb-key/imdb/poster-default/tt0137523.jpg", transport.lastUrl)
    }

    @Test
    fun `top posters unsupported image type returns error without network call`() = runTest {
        val transport = RecordingArtworkPosterTransport(
            PosterTransportResult(statusCode = 200, isSuccessful = true, body = "unused".toByteArray())
        )
        val loader = DefaultArtworkByteLoader(
            credentialResolver = StaticCredentialResolver(IntegrationProvider.TOP_POSTERS, "credentialhash", "top-key"),
            posterTransport = transport
        )
        val decision = templateDecision(
            provider = IntegrationProvider.TOP_POSTERS,
            idType = "tmdb",
            mediaId = "movie-550",
            credentialHash = "credentialhash",
            imageType = ArtworkType.LOGO
        )

        val result = loader.load(decision.selectedCandidate.providerTemplate!!.toSource(), decision)

        assertTrue(result is IntegrationLoadResult.NetworkError)
        assertEquals(null, transport.lastUrl)
    }

    private class RecordingArtworkPosterTransport(
        private val result: PosterTransportResult
    ) : ArtworkPosterTransport {
        var lastUrl: String? = null

        override fun execute(url: String): PosterTransportResult {
            lastUrl = url
            return result
        }
    }

    private class StaticCredentialResolver(
        private val provider: IntegrationProvider,
        private val credentialHash: String,
        private val apiKey: String
    ) : ArtworkCredentialResolver {
        override suspend fun apiKeyFor(provider: IntegrationProvider, credentialHash: String?): String? =
            apiKey.takeIf {
                this.provider == provider && this.credentialHash == credentialHash
            }
    }

    private fun PersistedProviderTemplate.toSource(): ArtworkSource.ProviderTemplate =
        ArtworkSource.ProviderTemplate(
            provider = provider,
            idType = idType,
            mediaId = mediaId,
            providerPathHash = providerPathHash,
            settingsHash = settingsHash,
            credentialHash = credentialHash,
            pathParams = pathParams
        )

    private fun templateDecision(
        provider: IntegrationProvider,
        idType: String,
        mediaId: String,
        credentialHash: String,
        imageType: ArtworkType,
        pathParams: Map<String, String> = emptyMap()
    ): ArtworkDecision {
        val providerId = ArtworkProviderId.RuntimeProvider(provider)
        return ArtworkDecision(
            decisionKey = ArtworkDecisionKey("decision-$provider-$imageType"),
            ownerKey = ArtworkOwnerKey.CanonicalContent("$idType:$mediaId"),
            canonicalContentId = "$idType:$mediaId",
            imageType = imageType,
            selectedCandidate = PersistedArtworkCandidate(
                provider = providerId,
                sourceRole = ArtworkSourceRole.PREMIUM,
                sourceHash = "source-hash",
                redactedSourceForTrace = null,
                providerTemplate = PersistedProviderTemplate(
                    provider = providerId,
                    imageType = imageType,
                    idType = idType,
                    mediaId = mediaId,
                    providerPathHash = "pathhash",
                    settingsHash = "settingshash",
                    credentialHash = credentialHash,
                    policyVersion = 1,
                    pathParams = pathParams
                ),
                priority = 1
            ),
            rejectedCandidates = emptyList(),
            policyVersion = 1,
            settingsHash = "settingshash",
            credentialHash = credentialHash,
            createdAtMs = 1L,
            expiresAtMs = 2L,
            staleUntilMs = 3L
        )
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.DefaultArtworkByteLoaderTest
```

Expected: FAIL because `DefaultArtworkByteLoader` and `ArtworkPosterTransport` do not exist.

- [ ] **Step 3: Commit failing byte loader tests**

```bash
git add app/src/test/java/com/nexio/tv/core/artwork/DefaultArtworkByteLoaderTest.kt
git commit -m "test: capture premium artwork byte loading"
```

---

### Task 9: Implement Default Artwork Byte Loader

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/DefaultArtworkByteLoader.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/DefaultArtworkByteLoaderTest.kt`

- [ ] **Step 1: Create production byte loader**

Create `DefaultArtworkByteLoader.kt`:

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.data.integration.posters.transport.PosterTransport
import com.nexio.tv.data.integration.posters.transport.PosterTransportResult
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

interface ArtworkPosterTransport {
    fun execute(url: String): PosterTransportResult
}

class DefaultArtworkPosterTransport @Inject constructor(
    private val posterTransport: PosterTransport
) : ArtworkPosterTransport {
    override fun execute(url: String): PosterTransportResult =
        posterTransport.execute(url)
}

@Singleton
class DefaultArtworkByteLoader @Inject constructor(
    private val credentialResolver: ArtworkCredentialResolver,
    private val posterTransport: ArtworkPosterTransport
) : ArtworkByteLoader {
    override suspend fun load(
        source: ArtworkSource,
        decision: ArtworkDecision
    ): IntegrationLoadResult<ByteArray> =
        when (source) {
            is ArtworkSource.ProviderTemplate -> loadProviderTemplate(source, decision)
            is ArtworkSource.RemoteUrl -> loadRemoteUrl(source)
            is UnavailableRemoteArtworkSource -> IntegrationLoadResult.NetworkError(
                IllegalStateException("Remote artwork source is unavailable for ${source.normalizedUrlHash}")
            )
            is ArtworkSource.LocalAsset -> IntegrationLoadResult.NetworkError(
                IllegalStateException("Local artwork asset should be loaded from disk")
            )
            is ArtworkSource.Placeholder -> IntegrationLoadResult.NetworkError(
                IllegalStateException("Placeholder artwork has no remote bytes")
            )
        }

    private suspend fun loadProviderTemplate(
        source: ArtworkSource.ProviderTemplate,
        decision: ArtworkDecision
    ): IntegrationLoadResult<ByteArray> {
        val runtimeProvider = (source.provider as? ArtworkProviderId.RuntimeProvider)?.providerId
            ?: return IntegrationLoadResult.NetworkError(IllegalStateException("Unsupported artwork provider ${source.provider.key}"))
        val credentialHash = source.credentialHash ?: decision.credentialHash
        val apiKey = credentialResolver.apiKeyFor(runtimeProvider, credentialHash)
            ?: return IntegrationLoadResult.NetworkError(IllegalStateException("Missing artwork credential for ${runtimeProvider.name}"))
        val url = try {
            when (runtimeProvider) {
                IntegrationProvider.RPDB -> rpdbPosterUrl(apiKey, source)
                IntegrationProvider.TOP_POSTERS -> topPostersUrl(apiKey, source, decision)
                    ?: return IntegrationLoadResult.NetworkError(IllegalStateException("Top Posters does not support ${decision.imageType} provider-template byte loading"))
                else -> return IntegrationLoadResult.NetworkError(IllegalStateException("Unsupported artwork provider ${runtimeProvider.name}"))
            }
        } catch (error: Throwable) {
            return IntegrationLoadResult.NetworkError(error)
        }
        return execute(url)
    }

    private fun loadRemoteUrl(source: ArtworkSource.RemoteUrl): IntegrationLoadResult<ByteArray> =
        execute(source.rawUrl.value)

    private fun execute(url: String): IntegrationLoadResult<ByteArray> =
        runCatching { posterTransport.execute(url) }
            .fold(
                onSuccess = { result ->
                    when {
                        result.body == null ->
                            IntegrationLoadResult.HttpError(result.statusCode, reason = "artwork_missing_body")
                        !result.isSuccessful ->
                            IntegrationLoadResult.HttpError(result.statusCode, reason = "artwork_fetch_failed")
                        else ->
                            IntegrationLoadResult.Success(result.body)
                    }
                },
                onFailure = { IntegrationLoadResult.NetworkError(it) }
            )

    private fun rpdbPosterUrl(
        apiKey: String,
        source: ArtworkSource.ProviderTemplate
    ): String =
        "https://api.ratingposterdb.com/${apiKey.encodePathSegment()}/${source.idType.encodePathSegment()}/poster-default/${source.mediaId.encodePathSegment()}.jpg"

    private fun topPostersUrl(
        apiKey: String,
        source: ArtworkSource.ProviderTemplate,
        decision: ArtworkDecision
    ): String? =
        when (decision.imageType) {
            ArtworkType.POSTER ->
                "https://api.top-posters.com/${apiKey.encodePathSegment()}/${source.idType.encodePathSegment()}/poster/${source.mediaId.encodePathSegment()}.jpg"
            ArtworkType.THUMBNAIL ->
                topPostersThumbnailUrl(apiKey, source)
            else ->
                null
        }

    private fun topPostersThumbnailUrl(
        apiKey: String,
        source: ArtworkSource.ProviderTemplate
    ): String {
        val season = requireNotNull(source.pathParams["season"]) { "Top Posters thumbnail season is required" }
        val episode = requireNotNull(source.pathParams["episode"]) { "Top Posters thumbnail episode is required" }
        val badgeSize = source.pathParams["badgeSize"] ?: "small"
        val badgePosition = source.pathParams["badgePosition"] ?: "top-right"
        val blur = source.pathParams["blur"] ?: "false"
        return "https://api.top-posters.com/${apiKey.encodePathSegment()}/${source.idType.encodePathSegment()}/thumbnail/${source.mediaId.encodePathSegment()}/S${season.encodePathSegment()}E${episode.encodePathSegment()}.jpg" +
            "?badge_size=${badgeSize.encodeQuery()}" +
            "&badge_position=${badgePosition.encodeQuery()}" +
            "&blur=${blur.encodeQuery()}"
    }

    private fun String.encodePathSegment(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())
            .replace("+", "%20")

    private fun String.encodeQuery(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())
}
```

- [ ] **Step 2: Run byte loader tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.DefaultArtworkByteLoaderTest
```

Expected: PASS.

- [ ] **Step 3: Commit byte loader**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/DefaultArtworkByteLoader.kt app/src/test/java/com/nexio/tv/core/artwork/DefaultArtworkByteLoaderTest.kt
git commit -m "feat: load premium artwork provider templates"
```

---

### Task 10: Wire Hilt Singleton Repository And Fetcher Factory

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt`
- Modify: `app/src/main/java/com/nexio/tv/NexioApplication.kt`
- Test: `app/src/test/java/com/nexio/tv/architecture/PremiumArtworkSharedPipelineContractTest.kt`

- [ ] **Step 1: Add architecture contract test**

Create `PremiumArtworkSharedPipelineContractTest.kt`:

```kotlin
package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumArtworkSharedPipelineContractTest {
    @Test
    fun `application does not manually construct partial artwork asset repository`() {
        val source = File("app/src/main/java/com/nexio/tv/NexioApplication.kt").readText()

        assertFalse(
            "NexioApplication must use the injected NexioArtworkFetcher.Factory, not construct ArtworkAssetRepository manually.",
            source.contains("ArtworkAssetRepository(")
        )
        assertTrue(source.contains("@Inject lateinit var nexioArtworkFetcherFactory: NexioArtworkFetcher.Factory"))
    }

    @Test
    fun `integration runtime module binds artwork byte loader and disk cache`() {
        val source = File("app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt").readText()

        assertTrue(source.contains("provideArtworkAssetDiskCache"))
        assertTrue(source.contains("provideArtworkByteLoader"))
        assertTrue(source.contains("provideArtworkPosterTransport"))
        assertTrue(source.contains("provideArtworkProviderSettingsSource"))
        assertTrue(source.contains("provideArtworkCredentialResolver"))
    }

    @Test
    fun `default byte loader is not called directly by production code`() {
        val productionReferences = File("app/src/main/java/com/nexio/tv")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name == "DefaultArtworkByteLoader.kt" }
            .filterNot { it.name == "IntegrationRuntimeModule.kt" }
            .joinToString("\n") { it.readText() }
        val repository = File("app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt").readText()

        assertFalse(
            "DefaultArtworkByteLoader must stay behind ArtworkAssetRepository and IntegrationRuntime.",
            productionReferences.contains("DefaultArtworkByteLoader(")
        )
        assertTrue(repository.contains("runtime.get("))
        assertTrue(repository.contains("byteLoader.load(materialized.source, decision)"))
    }
}
```

- [ ] **Step 2: Run architecture contract to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.architecture.PremiumArtworkSharedPipelineContractTest
```

Expected: FAIL because `NexioApplication` still manually constructs the repository and module bindings are missing.

- [ ] **Step 3: Add module imports**

In `IntegrationRuntimeModule.kt`, add:

```kotlin
import com.nexio.tv.core.artwork.ArtworkAssetDiskCache
import com.nexio.tv.core.artwork.ArtworkByteLoader
import com.nexio.tv.core.artwork.ArtworkCredentialResolver
import com.nexio.tv.core.artwork.ArtworkPosterTransport
import com.nexio.tv.core.artwork.ArtworkProviderSettingsSource
import com.nexio.tv.core.artwork.ArtworkSourceMaterializer
import com.nexio.tv.core.artwork.DefaultArtworkByteLoader
import com.nexio.tv.core.artwork.DefaultArtworkPosterTransport
import com.nexio.tv.core.artwork.PosterRatingsArtworkProviderSettingsSource
import com.nexio.tv.core.artwork.PosterRatingsArtworkCredentialResolver
```

- [ ] **Step 4: Add Hilt providers**

Add these providers inside `IntegrationRuntimeModule`:

```kotlin
    @Provides
    @Singleton
    fun provideArtworkAssetDiskCache(
        @ApplicationContext context: Context
    ): ArtworkAssetDiskCache = ArtworkAssetDiskCache(context.cacheDir)

    @Provides
    @Singleton
    fun provideArtworkSourceMaterializer(): ArtworkSourceMaterializer =
        ArtworkSourceMaterializer(emptyMap())

    @Provides
    @Singleton
    fun provideArtworkPosterTransport(
        impl: DefaultArtworkPosterTransport
    ): ArtworkPosterTransport = impl

    @Provides
    @Singleton
    fun provideArtworkProviderSettingsSource(
        impl: PosterRatingsArtworkProviderSettingsSource
    ): ArtworkProviderSettingsSource = impl

    @Provides
    @Singleton
    fun provideArtworkCredentialResolver(
        impl: PosterRatingsArtworkCredentialResolver
    ): ArtworkCredentialResolver = impl

    @Provides
    @Singleton
    fun provideArtworkByteLoader(
        impl: DefaultArtworkByteLoader
    ): ArtworkByteLoader = impl
```

The empty `ArtworkSourceMaterializer(emptyMap())` is intentional for the singleton. `ProviderTemplate` decisions contain enough provider, id, image type, path parameter, settings hash, and credential hash data to materialize without a raw URL map. Raw `RemoteUrl` decisions may need source-map material and must fail as a traced null result when that material is unavailable; Task 3 locks both behaviors.

- [ ] **Step 5: Update `NexioApplication` imports and injected fields**

Remove these imports from `NexioApplication.kt`:

```kotlin
import com.nexio.tv.core.artwork.ArtworkAssetDiskCache
import com.nexio.tv.core.artwork.ArtworkAssetRepository
import com.nexio.tv.core.artwork.ArtworkSourceMaterializer
```

Keep:

```kotlin
import com.nexio.tv.core.image.NexioArtworkFetcher
```

Add an injected field next to `integrationPosterFetcherFactory`:

```kotlin
    @Inject lateinit var nexioArtworkFetcherFactory: NexioArtworkFetcher.Factory
```

- [ ] **Step 6: Register injected fetcher factory**

In `newImageLoader()`, replace:

```kotlin
                add(nexioArtworkFetcherFactory())
```

with:

```kotlin
                add(nexioArtworkFetcherFactory)
```

- [ ] **Step 7: Remove manual factory helper**

Delete this method from `NexioApplication.kt`:

```kotlin
    private fun nexioArtworkFetcherFactory(): NexioArtworkFetcher.Factory =
        NexioArtworkFetcher.Factory(
            ArtworkAssetRepository(
                runtime = integrationRuntime,
                diskCache = ArtworkAssetDiskCache(cacheDir),
                sourceMaterializer = ArtworkSourceMaterializer(emptyMap())
            )
        )
```

- [ ] **Step 8: Run architecture contract and compile-focused test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.architecture.PremiumArtworkSharedPipelineContractTest --tests com.nexio.tv.core.image.NexioArtworkFetcherTest
```

Expected: PASS.

- [ ] **Step 9: Commit Hilt wiring**

```bash
git add app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt app/src/main/java/com/nexio/tv/NexioApplication.kt app/src/test/java/com/nexio/tv/architecture/PremiumArtworkSharedPipelineContractTest.kt
git commit -m "fix: use shared artwork repository in image loader"
```

---

### Task 11: Add No-Raw-Premium-URL Guard Tests

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/poster/PosterRatingsUrlResolverTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/PremiumArtworkSharedPipelineContractTest.kt`

Scope these guards to UI/Coil model projection and metadata adapters. Do not globally ban provider domains from `DefaultArtworkByteLoader` or its tests; that class is the internal transport boundary that must build provider URLs behind `IntegrationRuntime`.

- [ ] **Step 1: Add resolver no-raw assertion helper**

In `PosterRatingsUrlResolverTest.kt`, add:

```kotlin
private fun assertNoRawPremiumUrl(value: String?) {
    val text = value.orEmpty()
    assertFalse(text.startsWith("https://api.ratingposterdb.com"))
    assertFalse(text.startsWith("https://api.top-posters.com"))
    assertFalse(text.startsWith("integration-poster://"))
}
```

- [ ] **Step 2: Use helper in premium selection tests**

Add this assertion in both premium selection tests:

```kotlin
assertNoRawPremiumUrl(resolved)
```

- [ ] **Step 3: Add key-cleared fallback test**

In `PosterRatingsUrlResolverTest.kt`, add:

```kotlin
@Test
fun `premium key clear falls back to primary artwork`() {
    val cache = InMemoryArtworkDecisionCache()
    val resolver = resolver(cache)
    val fallbackUrl = "https://image.tmdb.org/t/p/w500/poster.jpg"

    val resolved = resolver.resolvePosterArtworkString(
        settings = ArtworkProviderSettings(
            rpdbApiKey = "",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB
            )
        ),
        providerIds = ProviderIds(imdb = "tt15940132"),
        mediaKind = MetadataMediaKind.MOVIE,
        ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt15940132"),
        fallbackPosterUrl = fallbackUrl
    )

    assertInternalArtworkRef(resolved)
    assertNoRawPremiumUrl(resolved)
    val decision = cache.get(decisionKeyFromRef(resolved!!))
    assertEquals("TMDB", decision?.selectedCandidate?.provider?.key)
    assertNull(decision?.selectedCandidate?.providerTemplate)
}
```

- [ ] **Step 4: Add Coil model guard to architecture contract**

Append this test to `PremiumArtworkSharedPipelineContractTest.kt`:

```kotlin
@Test
fun `premium poster adapters emit nexio artwork refs instead of integration poster refs`() {
    val rpdb = File("app/src/main/java/com/nexio/tv/data/integration/posters/RpdbMetadataProviderAdapter.kt").readText()
    val topPosters = File("app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersMetadataProviderAdapter.kt").readText()
    val combined = rpdb + "\n" + topPosters

    assertTrue(combined.contains("resolvePosterArtworkString"))
    assertFalse(combined.contains("resolvePosterUrl("))
    assertFalse(combined.contains("integration-poster://"))
    assertFalse(combined.contains("api.ratingposterdb.com"))
    assertFalse(combined.contains("api.top-posters.com"))
}
```

- [ ] **Step 5: Run guard tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.poster.PosterRatingsUrlResolverTest --tests com.nexio.tv.architecture.PremiumArtworkSharedPipelineContractTest
```

Expected: PASS.

- [ ] **Step 6: Commit guard tests**

```bash
git add app/src/test/java/com/nexio/tv/core/poster/PosterRatingsUrlResolverTest.kt app/src/test/java/com/nexio/tv/architecture/PremiumArtworkSharedPipelineContractTest.kt
git commit -m "test: guard premium artwork shared pipeline boundaries"
```

---

### Task 12: Full Focused Verification

**Files:**
- No production files.
- Verify changed files and focused tests.

- [ ] **Step 1: Run all focused artwork tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.nexio.tv.core.image.NexioArtworkFetcherTest \
  --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest \
  --tests com.nexio.tv.core.artwork.DefaultArtworkByteLoaderTest \
  --tests com.nexio.tv.core.artwork.PosterRatingsArtworkCredentialResolverTest \
  --tests com.nexio.tv.core.poster.PosterRatingsUrlResolverTest \
  --tests com.nexio.tv.architecture.PremiumArtworkSharedPipelineContractTest
```

Expected: PASS. Existing Gradle native-library and deprecation warnings may still print.

- [ ] **Step 2: Run metadata audit tests covering premium artwork**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.metadata.audit.MetadataExecutionAuditGoldenTest
```

Expected: PASS. These tests already assert premium artwork route decisions and `nexio-artwork://` model usage.

- [ ] **Step 3: Run existing raw artwork boundary test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.architecture.RawRemoteArtworkUrlBoundaryTest
```

Expected: PASS. This protects against raw remote provider URLs reaching UI image model boundaries.

- [ ] **Step 4: Build debug APK**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Inspect diff**

Run:

```bash
git diff --stat
git diff -- app/src/main/java/com/nexio/tv/core/artwork app/src/main/java/com/nexio/tv/core/image/NexioArtworkFetcher.kt app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt app/src/main/java/com/nexio/tv/NexioApplication.kt
```

Expected: diff only touches shared artwork materialization, byte loading, wiring, and tests.

- [ ] **Step 6: Commit verification-complete state**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork app/src/main/java/com/nexio/tv/core/image/NexioArtworkFetcher.kt app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt app/src/main/java/com/nexio/tv/NexioApplication.kt app/src/test/java/com/nexio/tv/core/image/NexioArtworkFetcherTest.kt app/src/test/java/com/nexio/tv/core/artwork app/src/test/java/com/nexio/tv/core/poster/PosterRatingsUrlResolverTest.kt app/src/test/java/com/nexio/tv/architecture/PremiumArtworkSharedPipelineContractTest.kt
git commit -m "fix: materialize premium artwork decisions for Coil"
```

---

### Task 13: On-Device Verification On `192.168.50.98`

**Files:**
- No file changes.

- [ ] **Step 1: Confirm target is connected**

Run:

```bash
adb devices -l
```

Expected output contains:

```text
192.168.50.98:5555     device
```

- [ ] **Step 2: Install current debug APK**

Run the project’s normal install task for the debug variant used locally. If the project exposes `installUniversalDebug`, run:

```bash
./gradlew :app:installUniversalDebug
```

Expected: install succeeds on the connected device.

- [ ] **Step 3: Launch app**

Run:

```bash
adb -s 192.168.50.98:5555 shell monkey -p com.nexio.tv 1
```

Expected: app launches.

- [ ] **Step 4: Capture premium artwork trace bundle**

Run:

```bash
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell am broadcast \
  -a com.nexio.tv.action.PLAYBACK_TRACE_CLEAR \
  -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver
adb -s 192.168.50.98:5555 shell am broadcast \
  -a com.nexio.tv.action.PLAYBACK_TRACE_ENABLE \
  -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver
```

Navigate to home/detail surfaces that previously selected RPDB or Top Posters posters.

Stop tracing and pull the latest JSONL trace:

```bash
adb -s 192.168.50.98:5555 shell am broadcast \
  -a com.nexio.tv.action.PLAYBACK_TRACE_DISABLE \
  -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver
adb -s 192.168.50.98:5555 shell 'run-as com.nexio.tv ls -t files/playback-traces | head -1'
adb -s 192.168.50.98:5555 exec-out run-as com.nexio.tv sh -c 'cat files/playback-traces/$(ls -t files/playback-traces | head -1)' \
  > premium-artwork-trace.jsonl
rg 'metadata.field_selected|artwork.decision_lookup|artwork.asset_materialized|runtime.cache_decision' premium-artwork-trace.jsonl
```

If `PlaybackTraceAdb` reports that ADB trace control is disabled, enable the app's trace ADB-control toggle in settings and rerun the commands. Logcat is only used to confirm trace-control status; validate artwork behavior from `premium-artwork-trace.jsonl` unless the relevant trace channels are explicitly mirrored to logcat on that build.

Expected first-load signals:

```text
metadata.field_selected ... field=POSTER selectedProvider=RPDB
artwork.decision_lookup ... found=true
runtime.cache_decision ... provider=RPDB ... apiShapeId=rpdb.poster_template ... decision=MISS
artwork.asset_materialized ... success=true ... networkExecuted=true
```

Expected second-load signals:

```text
artwork.decision_lookup ... found=true
runtime.cache_decision ... decision=HIT
artwork.asset_materialized ... success=true ... networkExecuted=false
```

- [ ] **Step 5: Verify failure cases do not crash**

Temporarily switch the poster provider API key to an invalid value in settings, then open a surface with premium poster selection.

Expected:

```text
artwork.decision_lookup ... found=true
artwork.asset_materialized ... success=false
```

The app must not crash. API keys must not appear in `premium-artwork-trace.jsonl` or logcat.

- [ ] **Step 6: Restore valid settings**

Restore the valid RPDB or Top Posters API key and re-open the same surface.

Expected: posters render, `premium-artwork-trace.jsonl` and logcat contain no raw `https://api.ratingposterdb.com/<key>` or `https://api.top-posters.com/<key>` values.

---

## Self-Review

### Spec Coverage

- Decision refs resolve through `ArtworkDecisionCache`: Task 3 and Task 4.
- `NexioArtworkFetcher` supports asset and decision refs: Task 1 and Task 2.
- Shared singleton repository replaces manual `NexioApplication` construction: Task 10.
- Real byte loading for RPDB, Top Posters posters, and Top Posters thumbnails: Task 8 and Task 9.
- `IntegrationRuntime` remains the cache/trace execution boundary: Task 4 keeps repository-owned `runtime.get(...)`; Task 9 only implements `IntegrationSpec.load`.
- Provider-template decisions materialize with an empty source map, while missing raw remote source material fails traceably: Task 3 and Task 10.
- `DefaultArtworkByteLoader` dispatches Top Posters by `decision.imageType`, encodes path segments, and is protected from direct production use outside the repository/runtime path: Task 8, Task 9, and Task 10.
- Key-change recovery is covered: old credential-hash decisions do not materialize, new decisions materialize, and clearing a key falls back to primary artwork: Task 8 and Task 11.
- Provider precedence remains in router/decision, not fetcher: Task 2 fetcher only consumes `assetKey` or `decisionKey`.
- No raw premium URLs or `integration-poster://` final fix: Task 11.
- On-device verification uses exported JSONL trace data, not assumed logcat mirroring, for first load, cache hit, missing decision/provider failures, and redaction: Task 13.
- Dialog scroll fix: already completed before this plan; current branch has `PosterRatingsSettingsDialogScrollContractTest`.

### Placeholder Scan

The plan contains concrete file paths, commands, expected outcomes, and code snippets. It intentionally avoids open-ended placeholder tasks.

### Type Consistency

The plan uses existing production types:

- `ArtworkDecisionKey`
- `ArtworkAssetKey`
- `ArtworkAssetResult`
- `ArtworkDecisionCache`
- `ArtworkAssetRepository`
- `ArtworkSource.ProviderTemplate`
- `IntegrationLoadResult<ByteArray>`
- `IntegrationFetchResult<ByteArray>`
- `PosterTransportResult`
- `RuntimeTraceSink`
- `TraceEventEnvelope`

The new types introduced by the plan are defined before use:

- `ArtworkCredentialHash`
- `ArtworkCredentialResolver`
- `PosterRatingsArtworkCredentialResolver`
- `ArtworkPosterTransport`
- `DefaultArtworkPosterTransport`
- `DefaultArtworkByteLoader`
- `PremiumArtworkSharedPipelineContractTest`
