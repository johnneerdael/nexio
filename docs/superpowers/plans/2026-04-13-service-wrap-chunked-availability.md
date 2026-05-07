# Service Wrap Chunked Availability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce Service Wrap time-to-first-link by resolving incoming P2P hashes in small chunks and caching recently resolved wrapped links, while preserving progressive stream display and autoplay early finish.

**Architecture:** Keep the current progressive `ServiceWrapSessionFactory` flow, but stop treating every hash as a fully independent provider request. Queue candidates as they arrive, flush chunks at 20 candidates or after 250 ms, batch provider availability where the provider API already supports it, and emit one terminal result per candidate so `StreamRepositoryImpl` accounting stays stable. Add a short-lived in-memory resolved-link cache keyed by provider, hash, requested episode context, and source stream key.

**Tech Stack:** Android/Kotlin, coroutines, Kotlin Flow, Hilt singletons, existing Retrofit debrid APIs, JVM unit tests with `kotlinx-coroutines-test`.

---

## File Structure

- Modify `app/src/main/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapModels.kt`
  - Add chunk-level result models that can represent provider results for multiple candidates.
  - Keep existing `ServiceWrapResolvedBatch` so repository UI emissions do not need a large rewrite.
- Modify `app/src/main/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapResolver.kt`
  - Add a chunked progressive resolver method with a compatibility default.
- Modify `app/src/main/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapProviderBackend.kt`
  - Add a default chunk method for provider backends.
- Modify `app/src/main/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapSessionFactory.kt`
  - Replace immediate per-hash resolution with a chunk queue.
  - Preserve one terminal event per candidate.
  - Keep the existing max concurrent resolution guard.
- Modify `app/src/main/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolver.kt`
  - Resolve each chunk across configured providers.
  - Override chunk methods for providers whose cache APIs already accept multiple hashes or magnets: Premiumize, TorBox, EasyDebrid.
  - Keep Real-Debrid on its single-hash path unless a verified multi-hash API contract is added later.
- Create `app/src/main/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapResolvedStreamCache.kt`
  - Store short-lived wrapped link results to avoid repeated debrid work during retries and repeated stream selection.
- Modify `app/src/test/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapSessionFactoryTest.kt`
  - Add tests for chunk flush by size, chunk flush by delay, and terminal-per-candidate behavior.
- Modify `app/src/test/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolverTest.kt`
  - Add tests for batched provider availability and cache hits.
- Modify `app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplTest.kt`
  - Add a regression that first chunk results are emitted before a later chunk completes.

---

### Task 1: Add Chunk Result Models

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapModels.kt`

- [ ] **Step 1: Add chunk result models**

Add this below `data class ServiceWrapResolutionBatch`:

```kotlin
data class ServiceWrapResolutionChunkBatch(
    val streamsByHash: Map<String, List<ResolvedServiceWrapStream>>,
    val isTerminal: Boolean
) {
    companion object {
        fun terminalEmpty(hashes: Collection<String>): ServiceWrapResolutionChunkBatch {
            return ServiceWrapResolutionChunkBatch(
                streamsByHash = hashes.associateWith { emptyList() },
                isTerminal = true
            )
        }
    }
}

data class ServiceWrapResolvedChunk(
    val candidate: WrapCandidate,
    val wrappedStreams: List<Stream>,
    val isTerminal: Boolean
)
```

- [ ] **Step 2: Run compile to verify the model addition**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapModels.kt
git commit -m "refactor: add service-wrap chunk models"
```

---

### Task 2: Add Chunk Resolver Interfaces

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapProviderBackend.kt`

- [ ] **Step 1: Extend `ServiceWrapResolver` with a chunk default**

Replace `ServiceWrapResolver.kt` with:

```kotlin
package com.nexio.tv.data.repository.servicewrap

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface ServiceWrapResolver {
    suspend fun resolve(
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): List<ResolvedServiceWrapStream>

    fun resolveProgressively(
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): Flow<ServiceWrapResolutionBatch> = flow {
        emit(
            ServiceWrapResolutionBatch(
                streams = resolve(candidate, requestContext),
                isTerminal = true
            )
        )
    }

    fun resolveChunkProgressively(
        candidates: List<WrapCandidate>,
        requestContext: ServiceWrapRequestContext
    ): Flow<ServiceWrapResolutionChunkBatch> = flow {
        val resultsByHash = LinkedHashMap<String, List<ResolvedServiceWrapStream>>()
        candidates.forEach { candidate ->
            resultsByHash[candidate.normalizedInfoHash] = resolve(candidate, requestContext)
        }
        emit(
            ServiceWrapResolutionChunkBatch(
                streamsByHash = resultsByHash,
                isTerminal = true
            )
        )
    }
}
```

- [ ] **Step 2: Extend `ServiceWrapProviderBackend` with a chunk default**

Replace `ServiceWrapProviderBackend.kt` with:

```kotlin
package com.nexio.tv.data.repository.servicewrap

internal interface ServiceWrapProviderBackend {
    val provider: ServiceWrapProvider

    suspend fun isConfigured(): Boolean

    suspend fun resolve(
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): List<ResolvedServiceWrapStream>

    suspend fun resolveChunk(
        candidates: List<WrapCandidate>,
        requestContext: ServiceWrapRequestContext
    ): Map<String, List<ResolvedServiceWrapStream>> {
        val resolved = LinkedHashMap<String, List<ResolvedServiceWrapStream>>()
        candidates.forEach { candidate ->
            resolved[candidate.normalizedInfoHash] = resolve(candidate, requestContext)
        }
        return resolved
    }
}
```

- [ ] **Step 3: Run compile to verify compatibility defaults**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapResolver.kt app/src/main/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapProviderBackend.kt
git commit -m "refactor: add service-wrap chunk resolver API"
```

---

### Task 3: Make Session Flush Candidates in Chunks

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapSessionFactory.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapSessionFactoryTest.kt`

- [ ] **Step 1: Write failing chunk-by-size test**

Add this test to `ServiceWrapSessionFactoryTest`:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
@Test
fun `session flushes a chunk when twenty candidates are queued`() = runTest {
    val chunkSizes = mutableListOf<Int>()
    val factory = ServiceWrapSessionFactory(
        extractor = WrapCandidateExtractor(),
        resolver = object : ServiceWrapResolver {
            override suspend fun resolve(
                candidate: WrapCandidate,
                requestContext: ServiceWrapRequestContext
            ): List<ResolvedServiceWrapStream> = error("chunk path should be used")

            override fun resolveChunkProgressively(
                candidates: List<WrapCandidate>,
                requestContext: ServiceWrapRequestContext
            ): Flow<ServiceWrapResolutionChunkBatch> = flow {
                chunkSizes += candidates.size
                emit(
                    ServiceWrapResolutionChunkBatch(
                        streamsByHash = candidates.associate { candidate ->
                            candidate.normalizedInfoHash to listOf(
                                resolvedStream(ServiceWrapProvider.REAL_DEBRID, candidate.normalizedInfoHash)
                            )
                        },
                        isTerminal = true
                    )
                )
            }
        },
        wrappedStreamBuilder = WrappedStreamBuilder(),
        maxConcurrentResolutions = 6,
        chunkSize = 20,
        chunkFlushDelayMs = 250L
    )
    val batches = mutableListOf<ServiceWrapResolvedBatch>()
    val session = factory.createSession(
        requestContext = ServiceWrapRequestContext(contentType = "movie", season = null, episode = null),
        scope = this,
        onResolved = { batches += it }
    )

    val streams = (1..20).map { index ->
        stream(
            name = "Movie Candidate $index",
            infoHash = "ABCDEF0123456789ABCDEF0123456789ABCDEF%02d".format(index),
            url = null,
            description = "Movie.2024.2160p.REMUX"
        )
    }

    val result = session.processAddonStreams(
        addonName = "Addon A",
        addonLogo = null,
        streams = streams
    )

    assertEquals(20, result.launchedWrapCount)
    runCurrent()
    assertEquals(listOf(20), chunkSizes)
    assertEquals(20, batches.count { it.isTerminal })
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.servicewrap.ServiceWrapSessionFactoryTest.session flushes a chunk when twenty candidates are queued'
```

Expected: FAIL because `ServiceWrapSessionFactory` has no `chunkSize` or `chunkFlushDelayMs` constructor parameters and does not call `resolveChunkProgressively`.

- [ ] **Step 3: Write failing flush-by-delay test**

Add this test to `ServiceWrapSessionFactoryTest`:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
@Test
fun `session flushes a partial chunk after delay`() = runTest {
    val chunkSizes = mutableListOf<Int>()
    val factory = ServiceWrapSessionFactory(
        extractor = WrapCandidateExtractor(),
        resolver = object : ServiceWrapResolver {
            override suspend fun resolve(
                candidate: WrapCandidate,
                requestContext: ServiceWrapRequestContext
            ): List<ResolvedServiceWrapStream> = error("chunk path should be used")

            override fun resolveChunkProgressively(
                candidates: List<WrapCandidate>,
                requestContext: ServiceWrapRequestContext
            ): Flow<ServiceWrapResolutionChunkBatch> = flow {
                chunkSizes += candidates.size
                emit(
                    ServiceWrapResolutionChunkBatch(
                        streamsByHash = candidates.associate { candidate ->
                            candidate.normalizedInfoHash to listOf(
                                resolvedStream(ServiceWrapProvider.REAL_DEBRID, candidate.normalizedInfoHash)
                            )
                        },
                        isTerminal = true
                    )
                )
            }
        },
        wrappedStreamBuilder = WrappedStreamBuilder(),
        maxConcurrentResolutions = 6,
        chunkSize = 20,
        chunkFlushDelayMs = 250L
    )
    val batches = mutableListOf<ServiceWrapResolvedBatch>()
    val session = factory.createSession(
        requestContext = ServiceWrapRequestContext(contentType = "movie", season = null, episode = null),
        scope = this,
        onResolved = { batches += it }
    )

    val result = session.processAddonStreams(
        addonName = "Addon A",
        addonLogo = null,
        streams = (1..3).map { index ->
            stream(
                name = "Movie Candidate $index",
                infoHash = "ABCDEF0123456789ABCDEF0123456789ABCDEF%02d".format(index),
                url = null,
                description = "Movie.2024.2160p.REMUX"
            )
        }
    )

    assertEquals(3, result.launchedWrapCount)
    runCurrent()
    assertTrue(chunkSizes.isEmpty())

    advanceTimeBy(250L)
    runCurrent()
    assertEquals(listOf(3), chunkSizes)
    assertEquals(3, batches.count { it.isTerminal })
}
```

- [ ] **Step 4: Run the second failing test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.servicewrap.ServiceWrapSessionFactoryTest.session flushes a partial chunk after delay'
```

Expected: FAIL for the same missing chunking behavior.

- [ ] **Step 5: Implement chunk queue in `ServiceWrapSessionFactory`**

Update `ServiceWrapSessionFactory.kt` with these constants and constructor fields:

```kotlin
private const val DEFAULT_MAX_CONCURRENT_SERVICE_WRAP_RESOLUTIONS = 6
private const val DEFAULT_SERVICE_WRAP_CHUNK_SIZE = 20
private const val DEFAULT_SERVICE_WRAP_CHUNK_FLUSH_DELAY_MS = 250L
```

Change the primary class body fields to:

```kotlin
) {
    private var maxConcurrentResolutions: Int = DEFAULT_MAX_CONCURRENT_SERVICE_WRAP_RESOLUTIONS
    private var chunkSize: Int = DEFAULT_SERVICE_WRAP_CHUNK_SIZE
    private var chunkFlushDelayMs: Long = DEFAULT_SERVICE_WRAP_CHUNK_FLUSH_DELAY_MS

    internal constructor(
        extractor: WrapCandidateExtractor,
        resolver: ServiceWrapResolver,
        wrappedStreamBuilder: WrappedStreamBuilder,
        maxConcurrentResolutions: Int = DEFAULT_MAX_CONCURRENT_SERVICE_WRAP_RESOLUTIONS,
        chunkSize: Int = DEFAULT_SERVICE_WRAP_CHUNK_SIZE,
        chunkFlushDelayMs: Long = DEFAULT_SERVICE_WRAP_CHUNK_FLUSH_DELAY_MS
    ) : this(
        extractor = extractor,
        resolver = resolver,
        wrappedStreamBuilder = wrappedStreamBuilder
    ) {
        this.maxConcurrentResolutions = maxConcurrentResolutions.coerceAtLeast(1)
        this.chunkSize = chunkSize.coerceAtLeast(1)
        this.chunkFlushDelayMs = chunkFlushDelayMs.coerceAtLeast(0L)
    }
```

Pass the chunk settings into `Session`:

```kotlin
return Session(
    requestContext = requestContext,
    scope = scope,
    extractor = extractor,
    resolver = resolver,
    wrappedStreamBuilder = wrappedStreamBuilder,
    maxConcurrentResolutions = maxConcurrentResolutions,
    chunkSize = chunkSize,
    chunkFlushDelayMs = chunkFlushDelayMs,
    onResolved = onResolved
)
```

Inside `Session`, add:

```kotlin
private val pendingCandidates = ArrayList<WrapCandidate>()
private var pendingFlushJob: Job? = null
```

Replace the current per-candidate launch block with:

```kotlin
enqueueCandidate(candidate)
```

Add these methods inside `Session`:

```kotlin
private fun enqueueCandidate(candidate: WrapCandidate) {
    pendingCandidates += candidate
    if (pendingCandidates.size >= chunkSize) {
        flushPendingCandidates()
        return
    }
    if (pendingFlushJob == null) {
        pendingFlushJob = scope.launch {
            delay(chunkFlushDelayMs)
            flushPendingCandidates()
        }
    }
}

private fun flushPendingCandidates() {
    if (pendingCandidates.isEmpty()) return
    pendingFlushJob?.cancel()
    pendingFlushJob = null

    val chunk = pendingCandidates.toList()
    pendingCandidates.clear()
    launchChunkResolution(chunk)
}

private fun launchChunkResolution(candidates: List<WrapCandidate>) {
    scope.launch {
        val terminalHashes = HashSet<String>()
        try {
            resolutionPermits.withPermit {
                resolver.resolveChunkProgressively(
                    candidates = candidates,
                    requestContext = requestContext
                ).collect { resolution ->
                    candidates.forEach { candidate ->
                        val resolved = resolution.streamsByHash[candidate.normalizedInfoHash].orEmpty()
                        val wrappedStreams = wrappedStreamBuilder.build(candidate, resolved)
                        val isTerminal = resolution.isTerminal
                        if (isTerminal) terminalHashes += candidate.normalizedInfoHash
                        if (wrappedStreams.isEmpty() && !isTerminal) return@forEach
                        onResolved(
                            ServiceWrapResolvedBatch(
                                addonName = candidate.sourceAddonName,
                                addonLogo = candidate.sourceAddonLogo,
                                wrappedStreams = wrappedStreams,
                                isTerminal = isTerminal
                            )
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            candidates.forEach { candidate ->
                terminalHashes += candidate.normalizedInfoHash
                onResolved(
                    ServiceWrapResolvedBatch(
                        addonName = candidate.sourceAddonName,
                        addonLogo = candidate.sourceAddonLogo,
                        wrappedStreams = emptyList(),
                        isTerminal = true
                    )
                )
            }
        } finally {
            candidates.forEach { candidate ->
                if (candidate.normalizedInfoHash !in terminalHashes) {
                    onResolved(
                        ServiceWrapResolvedBatch(
                            addonName = candidate.sourceAddonName,
                            addonLogo = candidate.sourceAddonLogo,
                            wrappedStreams = emptyList(),
                            isTerminal = true
                        )
                    )
                }
                inFlight.decrementAndGet()
            }
        }
    }
}
```

- [ ] **Step 6: Run chunk session tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.servicewrap.ServiceWrapSessionFactoryTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapSessionFactory.kt app/src/test/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapSessionFactoryTest.kt
git commit -m "feat: chunk service-wrap candidate resolution"
```

---

### Task 4: Add Short-Lived Resolved Link Cache

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapResolvedStreamCache.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolverTest.kt`

- [ ] **Step 1: Create the cache class**

Create `ServiceWrapResolvedStreamCache.kt`:

```kotlin
package com.nexio.tv.data.repository.servicewrap

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val SERVICE_WRAP_RESOLVED_STREAM_CACHE_TTL_MS = 10L * 60L * 1000L
private const val SERVICE_WRAP_RESOLVED_STREAM_CACHE_MAX_ENTRIES = 500

@Singleton
class ServiceWrapResolvedStreamCache @Inject constructor() {
    private data class Entry(
        val createdAtMs: Long,
        val streams: List<ResolvedServiceWrapStream>
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(
        provider: ServiceWrapProvider,
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): List<ResolvedServiceWrapStream>? {
        val key = key(provider, candidate, requestContext)
        val entry = entries[key] ?: return null
        val ageMs = System.currentTimeMillis() - entry.createdAtMs
        if (ageMs > SERVICE_WRAP_RESOLVED_STREAM_CACHE_TTL_MS) {
            entries.remove(key)
            return null
        }
        return entry.streams
    }

    fun put(
        provider: ServiceWrapProvider,
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext,
        streams: List<ResolvedServiceWrapStream>
    ) {
        if (entries.size >= SERVICE_WRAP_RESOLVED_STREAM_CACHE_MAX_ENTRIES) {
            val oldestKey = entries.minByOrNull { it.value.createdAtMs }?.key
            if (oldestKey != null) entries.remove(oldestKey)
        }
        entries[key(provider, candidate, requestContext)] = Entry(
            createdAtMs = System.currentTimeMillis(),
            streams = streams
        )
    }

    private fun key(
        provider: ServiceWrapProvider,
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): String {
        return buildString {
            append(provider.providerId)
            append('|')
            append(candidate.normalizedInfoHash)
            append('|')
            append(requestContext.contentType.lowercase())
            append('|')
            append(requestContext.season ?: -1)
            append('|')
            append(requestContext.episode ?: -1)
            append('|')
            append(candidate.sourceStreamKey)
        }
    }
}
```

- [ ] **Step 2: Add cache test**

Create `app/src/test/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapResolvedStreamCacheTest.kt`:

```kotlin
package com.nexio.tv.data.repository.servicewrap

import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceWrapResolvedStreamCacheTest {
    @Test
    fun `cache keys include episode context`() {
        val cache = ServiceWrapResolvedStreamCache()
        val candidate = candidate("ABCDEF0123456789ABCDEF0123456789ABCDEF01")
        val streams = listOf(resolvedStream("ABCDEF0123456789ABCDEF0123456789ABCDEF01"))

        cache.put(
            provider = ServiceWrapProvider.REAL_DEBRID,
            candidate = candidate,
            requestContext = ServiceWrapRequestContext(contentType = "series", season = 1, episode = 1),
            streams = streams
        )

        assertEquals(
            streams,
            cache.get(
                provider = ServiceWrapProvider.REAL_DEBRID,
                candidate = candidate,
                requestContext = ServiceWrapRequestContext(contentType = "series", season = 1, episode = 1)
            )
        )
        assertNull(
            cache.get(
                provider = ServiceWrapProvider.REAL_DEBRID,
                candidate = candidate,
                requestContext = ServiceWrapRequestContext(contentType = "series", season = 1, episode = 2)
            )
        )
    }

    private fun candidate(hash: String): WrapCandidate {
        val stream = Stream(
            name = "Show.S01E01.1080p",
            title = null,
            description = "Show.S01E01.1080p",
            url = null,
            ytId = null,
            infoHash = hash,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "Addon A",
            addonLogo = null,
            addonParserPreset = AddonParserPreset.GENERIC
        )
        return WrapCandidate(
            normalizedInfoHash = hash,
            magnetUri = "magnet:?xt=urn:btih:$hash",
            sourceStream = stream,
            sourceAddonName = "Addon A",
            sourceAddonLogo = null,
            sourceStreamKey = stableWrapStreamKey(stream),
            sourceParsed = parseSourceStream(stream)
        )
    }

    private fun resolvedStream(hash: String): ResolvedServiceWrapStream {
        return ResolvedServiceWrapStream(
            provider = ServiceWrapProvider.REAL_DEBRID,
            normalizedInfoHash = hash,
            playbackUrl = "https://rd.example/$hash",
            selectedFileIndex = 0,
            filename = "Show.S01E01.1080p.mkv",
            folderName = "Show",
            sizeBytes = 1_000_000L,
            durationMs = null,
            bitrate = null,
            width = null,
            height = null
        )
    }
}
```

- [ ] **Step 3: Run cache test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.servicewrap.ServiceWrapResolvedStreamCacheTest'
```

Expected: PASS.

- [ ] **Step 4: Wire cache into `DebridAvailabilityResolver` constructor**

Modify constructor parameters:

```kotlin
class DebridAvailabilityResolver @Inject constructor(
    private val realDebridAuthService: RealDebridAuthService,
    private val realDebridApi: RealDebridApi,
    private val premiumizeApi: PremiumizeApi,
    private val premiumizeSettingsDataStore: PremiumizeSettingsDataStore,
    private val premiumizeService: PremiumizeService,
    private val torBoxApi: TorBoxApi,
    private val torBoxSettingsDataStore: TorBoxSettingsDataStore,
    private val torBoxService: TorBoxService,
    private val easyDebridApi: EasyDebridApi,
    private val easyDebridSettingsDataStore: EasyDebridSettingsDataStore,
    private val easyDebridService: EasyDebridService,
    private val resolvedStreamCache: ServiceWrapResolvedStreamCache
) : ServiceWrapResolver {
```

- [ ] **Step 5: Cache provider results in `resolveChunkProgressively`**

Inside `resolveChunkProgressively`, before launching provider work, split candidates into cached and uncached:

```kotlin
val configuredBackends = backends.filter { backend -> backend.isConfigured() }
if (configuredBackends.isEmpty()) {
    emit(ServiceWrapResolutionChunkBatch.terminalEmpty(candidates.map { it.normalizedInfoHash }))
    return@flow
}

val cachedByHash = LinkedHashMap<String, MutableList<ResolvedServiceWrapStream>>()
val pendingByBackend = LinkedHashMap<ServiceWrapProviderBackend, List<WrapCandidate>>()
configuredBackends.forEach { backend ->
    val pending = ArrayList<WrapCandidate>()
    candidates.forEach { candidate ->
        val cached = resolvedStreamCache.get(backend.provider, candidate, requestContext)
        if (cached != null) {
            cachedByHash.getOrPut(candidate.normalizedInfoHash) { mutableListOf() } += cached
        } else {
            pending += candidate
        }
    }
    pendingByBackend[backend] = pending
}
if (cachedByHash.isNotEmpty()) {
    emit(
        ServiceWrapResolutionChunkBatch(
            streamsByHash = cachedByHash,
            isTerminal = pendingByBackend.values.all { it.isEmpty() }
        )
    )
}
if (pendingByBackend.values.all { it.isEmpty() }) return@flow
```

When a backend returns `resolved`, store each candidate result:

```kotlin
backendCandidates.forEach { candidate ->
    val streams = resolved[candidate.normalizedInfoHash].orEmpty()
    resolvedStreamCache.put(
        provider = backend.provider,
        candidate = candidate,
        requestContext = requestContext,
        streams = streams
    )
}
```

- [ ] **Step 6: Run service-wrap tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.servicewrap.*'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapResolvedStreamCache.kt app/src/main/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolver.kt app/src/test/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapResolvedStreamCacheTest.kt
git commit -m "feat: cache service-wrap resolved streams"
```

---

### Task 5: Batch Provider Availability Within Chunks

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolverTest.kt`

- [ ] **Step 1: Add test for chunked Premiumize cache checks**

Add this test to `DebridAvailabilityResolverTest` after existing tests:

```kotlin
@Test
fun `premiumize chunk resolver checks cache once for a chunk`() = runTest {
    val hash1 = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
    val hash2 = "ABCDEF0123456789ABCDEF0123456789ABCDEF02"
    val hash3 = "ABCDEF0123456789ABCDEF0123456789ABCDEF03"
    val candidates = listOf(
        buildWrapCandidate("Movie.2024.2160p.REMUX.$hash1.mkv").copy(normalizedInfoHash = hash1),
        buildWrapCandidate("Movie.2024.1080p.$hash2.mkv").copy(normalizedInfoHash = hash2),
        buildWrapCandidate("Movie.2024.720p.$hash3.mkv").copy(normalizedInfoHash = hash3)
    )

    val checkCacheCalls = mutableListOf<List<String>>()
    val premiumizeApi = mockk<PremiumizeApi>()
    coEvery {
        premiumizeApi.checkCache(apiKey = "pm-key", items = capture(checkCacheCalls))
    } returns Response.success(
        PremiumizeCacheCheckDto(
            status = "success",
            response = listOf(true, true, true)
        )
    )
    coEvery {
        premiumizeApi.createDirectDownload(apiKey = "pm-key", source = any())
    } answers {
        val source = secondArg<String>()
        Response.success(
            PremiumizeDirectDownloadDto(
                status = "success",
                location = "https://pm.example/${source.takeLast(8)}",
                filename = "Movie.2024.1080p.mkv",
                filesize = 1_000_000L
            )
        )
    }

    val resolver = buildResolver(
        premiumizeApi = premiumizeApi,
        premiumizeApiKey = "pm-key",
        realDebridAuthenticated = false,
        torBoxApiKey = "",
        easyDebridApiKey = ""
    )

    val batches = resolver.resolveChunkProgressively(
        candidates = candidates,
        requestContext = ServiceWrapRequestContext(contentType = "movie", season = null, episode = null)
    ).toList()

    val terminalBatch = batches.last()
    assertEquals(1, checkCacheCalls.size)
    assertEquals(3, checkCacheCalls.single().size)
    assertEquals(setOf(hash1, hash2, hash3), terminalBatch.streamsByHash.keys)
}
```

Add helper `buildResolver` in the same test file with mocked dependencies for services not under test. The helper returns a `DebridAvailabilityResolver` with `ServiceWrapResolvedStreamCache()` and flow-backed settings stores.

- [ ] **Step 2: Run the failing Premiumize test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.servicewrap.DebridAvailabilityResolverTest.premiumize chunk resolver checks cache once for a chunk'
```

Expected: FAIL because `PremiumizeBackend.resolveChunk` still uses the default per-candidate method.

- [ ] **Step 3: Implement `PremiumizeBackend.resolveChunk`**

Add this method inside `PremiumizeBackend`:

```kotlin
override suspend fun resolveChunk(
    candidates: List<WrapCandidate>,
    requestContext: ServiceWrapRequestContext
): Map<String, List<ResolvedServiceWrapStream>> {
    val apiKey = premiumizeSettingsDataStore.settings.first().apiKey.trim()
    if (apiKey.isBlank() || candidates.isEmpty()) return emptyMap()

    val cacheResponse = runCatching {
        premiumizeApi.checkCache(
            apiKey = apiKey,
            items = candidates.map { it.magnetUri }
        )
    }.getOrNull() ?: return candidates.associate { it.normalizedInfoHash to emptyList() }
    val cacheBody = cacheResponse.body()
    if (!cacheResponse.isSuccessful || cacheBody == null || !cacheBody.status.equals("success", ignoreCase = true)) {
        return candidates.associate { it.normalizedInfoHash to emptyList() }
    }

    val cachedCandidates = candidates.zip(cacheBody.response).filter { (_, cached) -> cached }.map { it.first }
    val resolved = LinkedHashMap<String, List<ResolvedServiceWrapStream>>()
    cachedCandidates.forEach { candidate ->
        resolved[candidate.normalizedInfoHash] = resolve(candidate, requestContext)
    }
    candidates.forEach { candidate ->
        resolved.putIfAbsent(candidate.normalizedInfoHash, emptyList())
    }
    return resolved
}
```

- [ ] **Step 4: Add tests for TorBox and EasyDebrid chunk availability**

Add two tests named:

```kotlin
@Test
fun `torbox chunk resolver checks cached torrents once for a chunk`() = runTest {
    val hash1 = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
    val hash2 = "ABCDEF0123456789ABCDEF0123456789ABCDEF02"
    val hash3 = "ABCDEF0123456789ABCDEF0123456789ABCDEF03"
    val requests = mutableListOf<TorBoxCheckCachedRequestDto>()
    val torBoxApi = mockk<TorBoxApi>()
    coEvery {
        torBoxApi.checkCachedTorrents(
            authorization = "Bearer tb-key",
            body = capture(requests),
            format = any(),
            listFiles = any()
        )
    } returns Response.success(
        TorBoxEnvelopeDto(
            success = true,
            data = listOf(
                TorBoxCachedTorrentDto(hash = hash1, files = listOf(TorBoxFileDto(id = 1, name = "Movie.2024.2160p.mkv", size = 1_000_000L))),
                TorBoxCachedTorrentDto(hash = hash2, files = listOf(TorBoxFileDto(id = 2, name = "Movie.2024.1080p.mkv", size = 1_000_000L))),
                TorBoxCachedTorrentDto(hash = hash3, files = listOf(TorBoxFileDto(id = 3, name = "Movie.2024.720p.mkv", size = 1_000_000L)))
            )
        )
    )
    coEvery { torBoxApi.createTorrent(any(), any(), any(), any()) } returns Response.success(TorBoxEnvelopeDto(success = true, data = TorBoxCreateTorrentDto(id = 100)))
    coEvery { torBoxApi.getMyTorrentList(any(), any(), any(), any(), any()) } returns Response.success(TorBoxEnvelopeDto(success = true, data = listOf(TorBoxTorrentListItemDto(id = 100, downloadFinished = true, files = listOf(TorBoxFileDto(id = 1, name = "Movie.2024.2160p.mkv", size = 1_000_000L))))))
    coEvery { torBoxApi.requestDownloadLink(any(), any(), any(), any(), any()) } returns Response.success(TorBoxEnvelopeDto(success = true, data = "https://tb.example/file.mkv"))

    val resolver = buildResolver(
        premiumizeApiKey = "",
        realDebridAuthenticated = false,
        torBoxApi = torBoxApi,
        torBoxApiKey = "tb-key",
        easyDebridApiKey = ""
    )

    resolver.resolveChunkProgressively(
        candidates = listOf(
            buildWrapCandidate("Movie.2024.2160p.$hash1.mkv").copy(normalizedInfoHash = hash1),
            buildWrapCandidate("Movie.2024.1080p.$hash2.mkv").copy(normalizedInfoHash = hash2),
            buildWrapCandidate("Movie.2024.720p.$hash3.mkv").copy(normalizedInfoHash = hash3)
        ),
        requestContext = ServiceWrapRequestContext(contentType = "movie", season = null, episode = null)
    ).toList()

    assertEquals(1, requests.size)
    assertEquals(listOf(hash1, hash2, hash3), requests.single().hashes)
}

@Test
fun `easydebrid chunk resolver performs lookup details once for a chunk`() = runTest {
    val urls = mutableListOf<EasyDebridLookupRequestDto>()
    val easyDebridApi = mockk<EasyDebridApi>()
    coEvery {
        easyDebridApi.lookupDetails(
            authorization = "Bearer ed-key",
            body = capture(urls)
        )
    } returns Response.success(
        EasyDebridLookupDetailsDto(
            result = listOf(
                EasyDebridLookupDetailsResultDto(cached = true),
                EasyDebridLookupDetailsResultDto(cached = true),
                EasyDebridLookupDetailsResultDto(cached = true)
            )
        )
    )
    coEvery { easyDebridApi.generate(any(), any()) } returns Response.success(
        EasyDebridGenerateDto(
            files = listOf(EasyDebridGeneratedFileDto(filename = "Movie.2024.1080p.mkv", size = 1_000_000L, url = "https://ed.example/file.mkv"))
        )
    )

    val resolver = buildResolver(
        premiumizeApiKey = "",
        realDebridAuthenticated = false,
        torBoxApiKey = "",
        easyDebridApi = easyDebridApi,
        easyDebridApiKey = "ed-key"
    )

    resolver.resolveChunkProgressively(
        candidates = listOf(
            buildWrapCandidate("Movie.2024.2160p.one.mkv"),
            buildWrapCandidate("Movie.2024.1080p.two.mkv"),
            buildWrapCandidate("Movie.2024.720p.three.mkv")
        ),
        requestContext = ServiceWrapRequestContext(contentType = "movie", season = null, episode = null)
    ).toList()

    assertEquals(1, urls.size)
    assertEquals(3, urls.single().urls.size)
}
```

The TorBox test must assert one `checkCachedTorrents` call with 3 hashes. The EasyDebrid test must assert one `lookupDetails` call with 3 URLs.

- [ ] **Step 5: Implement `TorBoxBackend.resolveChunk`**

Add this method inside `TorBoxBackend`:

```kotlin
override suspend fun resolveChunk(
    candidates: List<WrapCandidate>,
    requestContext: ServiceWrapRequestContext
): Map<String, List<ResolvedServiceWrapStream>> {
    val apiKey = torBoxSettingsDataStore.settings.first().apiKey.trim()
    if (apiKey.isBlank() || candidates.isEmpty()) return emptyMap()

    val cacheResponse = runCatching {
        torBoxApi.checkCachedTorrents(
            authorization = "Bearer $apiKey",
            body = TorBoxCheckCachedRequestDto(hashes = candidates.map { it.normalizedInfoHash })
        )
    }.getOrNull() ?: return candidates.associate { it.normalizedInfoHash to emptyList() }
    val cacheBody = cacheResponse.body()
    if (!cacheResponse.isSuccessful || cacheBody == null || cacheBody.success == false) {
        return candidates.associate { it.normalizedInfoHash to emptyList() }
    }

    val cachedByHash = cacheBody.data.orEmpty().associateBy { it.hash.uppercase(Locale.US) }
    val resolved = LinkedHashMap<String, List<ResolvedServiceWrapStream>>()
    candidates.forEach { candidate ->
        if (cachedByHash.containsKey(candidate.normalizedInfoHash)) {
            resolved[candidate.normalizedInfoHash] = resolve(candidate, requestContext)
        } else {
            resolved[candidate.normalizedInfoHash] = emptyList()
        }
    }
    return resolved
}
```

- [ ] **Step 6: Implement `EasyDebridBackend.resolveChunk`**

Add this method inside `EasyDebridBackend`:

```kotlin
override suspend fun resolveChunk(
    candidates: List<WrapCandidate>,
    requestContext: ServiceWrapRequestContext
): Map<String, List<ResolvedServiceWrapStream>> {
    val apiKey = easyDebridSettingsDataStore.settings.first().apiKey.trim()
    if (apiKey.isBlank() || candidates.isEmpty()) return emptyMap()

    val authorization = "Bearer $apiKey"
    val lookupBody = runCatching {
        easyDebridApi.lookupDetails(
            authorization = authorization,
            body = EasyDebridLookupRequestDto(urls = candidates.map { it.magnetUri })
        )
    }.getOrNull()?.body() ?: return candidates.associate { it.normalizedInfoHash to emptyList() }

    val resolved = LinkedHashMap<String, List<ResolvedServiceWrapStream>>()
    candidates.zip(lookupBody.result).forEach { (candidate, lookupResult) ->
        if (lookupResult.cached) {
            resolved[candidate.normalizedInfoHash] = resolve(candidate, requestContext)
        } else {
            resolved[candidate.normalizedInfoHash] = emptyList()
        }
    }
    candidates.drop(lookupBody.result.size).forEach { candidate ->
        resolved[candidate.normalizedInfoHash] = emptyList()
    }
    return resolved
}
```

- [ ] **Step 7: Run resolver tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.servicewrap.DebridAvailabilityResolverTest'
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolver.kt app/src/test/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolverTest.kt
git commit -m "feat: batch service-wrap availability checks"
```

---

### Task 6: Prove Early Chunk Emission at Repository Level

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplTest.kt`

- [ ] **Step 1: Add repository regression**

Add this test:

```kotlin
@Test
fun `getStreamsFromAllAddons emits first service-wrap chunk before later chunks finish`() = runTest {
    mockAndroidLog()

    val addonApi = mockk<AddonApi>()
    val addonRepository = mockk<AddonRepository>()
    val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
    val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
    val okHttpClient = mockk<OkHttpClient>(relaxed = true)
    val dispatcher = mockk<Dispatcher>()
    every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
    every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings(serviceWrapEnabled = true))
    every { okHttpClient.dispatcher } returns dispatcher
    every { dispatcher.runningCalls() } returns mutableListOf()
    every { dispatcher.queuedCalls() } returns mutableListOf()

    val addonA = streamAddon("https://addon-a.example", "Addon A")
    every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addonA))
    coEvery { addonApi.getStreams(match { it.contains("addon-a.example") }, any()) } returns Response.success(
        StreamResponseDto(
            streams = (1..25).map { index ->
                streamDto(
                    name = "P2P Candidate $index",
                    url = null,
                    infoHash = "ABCDEF0123456789ABCDEF0123456789ABCDEF%02d".format(index),
                    description = "Movie.2024.2160p.REMUX"
                )
            }
        )
    )

    val serviceWrapSessionFactory = ServiceWrapSessionFactory(
        extractor = WrapCandidateExtractor(),
        resolver = object : ServiceWrapResolver {
            override suspend fun resolve(
                candidate: WrapCandidate,
                requestContext: ServiceWrapRequestContext
            ): List<ResolvedServiceWrapStream> = error("chunk path should be used")

            override fun resolveChunkProgressively(
                candidates: List<WrapCandidate>,
                requestContext: ServiceWrapRequestContext
            ): Flow<ServiceWrapResolutionChunkBatch> = flow {
                if (candidates.size == 5) delay(1_000L)
                emit(
                    ServiceWrapResolutionChunkBatch(
                        streamsByHash = candidates.associate { candidate ->
                            candidate.normalizedInfoHash to listOf(
                                resolvedStream(ServiceWrapProvider.REAL_DEBRID, candidate.normalizedInfoHash)
                            )
                        },
                        isTerminal = true
                    )
                )
            }
        },
        wrappedStreamBuilder = WrappedStreamBuilder(),
        maxConcurrentResolutions = 2,
        chunkSize = 20,
        chunkFlushDelayMs = 250L
    )

    val repository = StreamRepositoryImpl(
        api = addonApi,
        addonRepository = addonRepository,
        debugSettingsDataStore = debugSettingsDataStore,
        playerSettingsDataStore = playerSettingsDataStore,
        serviceWrapSessionFactory = serviceWrapSessionFactory,
        okHttpClient = okHttpClient
    )

    val emissions = withTimeout(5_000L) {
        repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1234567",
            requestOrigin = "test_service_wrap_chunk_early",
            requestId = "request-service-wrap-chunk-early"
        ).toList()
    }

    val successes = emissions.filterIsInstance<NetworkResult.Success<List<com.nexio.tv.domain.model.AddonStreams>>>()
    assertTrue(successes.any { success -> success.data.single().streams.size == 20 })
    assertEquals(25, successes.last().data.single().streams.size)
}
```

- [ ] **Step 2: Run the repository regression**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.StreamRepositoryImplTest.getStreamsFromAllAddons emits first service-wrap chunk before later chunks finish'
```

Expected: PASS after Tasks 1-5.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplTest.kt
git commit -m "test: cover early service-wrap chunk emission"
```

---

### Task 7: Add Diagnostics for Chunk Latency

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapSessionFactory.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolver.kt`

- [ ] **Step 1: Add chunk diagnostics logs**

In `ServiceWrapSessionFactory.launchChunkResolution`, record:

```kotlin
val chunkStartedAtMs = System.currentTimeMillis()
```

After `resolveChunkProgressively` completes, log:

```kotlin
android.util.Log.d(
    "ServiceWrapSessionFactory",
    "SERVICE_WRAP_DIAG chunk size=${candidates.size} durationMs=${System.currentTimeMillis() - chunkStartedAtMs}"
)
```

In `DebridAvailabilityResolver.resolveChunkProgressively`, around each backend call, record:

```kotlin
val backendStartedAtMs = System.currentTimeMillis()
val resolved = runCatching {
    backend.resolveChunk(backendCandidates, requestContext)
}.getOrDefault(emptyMap())
android.util.Log.d(
    "DebridAvailabilityResolver",
    "SERVICE_WRAP_DIAG provider=${backend.provider.providerId} chunk=${backendCandidates.size} durationMs=${System.currentTimeMillis() - backendStartedAtMs} resolved=${resolved.values.sumOf { it.size }}"
)
```

- [ ] **Step 2: Run compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/servicewrap/ServiceWrapSessionFactory.kt app/src/main/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolver.kt
git commit -m "chore: log service-wrap chunk timings"
```

---

## Suggested Follow-Up After This Plan

If chunking and the 10-minute resolved stream cache still feel slow, add lazy playback URL resolution as a separate plan. The next architecture would mirror AIOStreams more closely: display cached availability results immediately with a resolver token, then unrestrict/generate the actual debrid download URL only when the user selects a stream or deterministic autoplay picks a winner. That is a larger player/navigation change because `StreamPlaybackInfo.url` currently gates playback routing.

---

## Verification Checklist

- [ ] Run targeted unit tests:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.servicewrap.*' --tests 'com.nexio.tv.data.repository.StreamRepositoryImplTest'
```

- [ ] Run whitespace check:

```bash
git diff --check
```

- [ ] Install on Shield and capture diagnostics while opening stream selection:

```bash
adb -s 192.168.50.13:5555 logcat -c
adb -s 192.168.50.13:5555 logcat -d -t 3000 | rg -i "SERVICE_WRAP_DIAG|STREAM_DIAG|DebridAvailabilityResolver|ServiceWrapSessionFactory"
```

Expected diagnostic shape:

```text
SERVICE_WRAP_DIAG chunk size=20 durationMs=<number>
SERVICE_WRAP_DIAG provider=PM chunk=20 durationMs=<number> resolved=<number>
SERVICE_WRAP_DIAG provider=TB chunk=20 durationMs=<number> resolved=<number>
```

---

## Self-Review

**Spec coverage:** The plan implements chunked processing with an initial chunk size of 20, early chunk emission, short-lived cache, and provider-specific batching where existing APIs accept multiple hashes or URLs. It avoids a single full “all hashes per provider” batch by flushing chunks at 20 candidates or after 250 ms.

**Placeholder scan:** The plan avoids open-ended implementation placeholders and names the exact tests, assertions, models, and methods needed for the provider batching work.

**Type consistency:** The new chunk method names are `resolveChunkProgressively` and `resolveChunk`; the new model is `ServiceWrapResolutionChunkBatch`; the session still emits `ServiceWrapResolvedBatch` so repository accounting remains candidate-terminal based.
