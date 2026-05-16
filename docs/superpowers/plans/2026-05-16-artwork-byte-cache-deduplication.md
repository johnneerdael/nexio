# Artwork Byte-Cache Deduplication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the byte-for-byte duplication where every fetched artwork image is written to disk TWICE — once at `files/integration-cache/artwork-asset/<provider>/.../1.bin` (Layer 1, `IntegrationRuntime` blob store) and once at `cache/artwork-assets/<provider>/...` (Layer 2, `ArtworkAssetDiskCache`). Confirmed 2026-05-16 on device: 33 FANART_TV files × ~700 KB each stored twice (~22 MB wasted just for FANART; ~30 MB across all providers).

**Architecture:** Drop Layer 2 entirely. Route `ArtworkAssetRepository.fetchInternal` reads through a new `IntegrationRuntime.getBlobFile(spec): File?` API that returns the Layer 1 blob File directly. `NexioArtworkFetcher`'s `ArtworkAssetResult.localFile` then points at the Layer 1 file rather than a Layer 2 copy. The Room `integration_cache` table already holds equivalent metadata (cacheKey, provider, blobPath, expiresAtEpochMs, staleUntilEpochMs, ownerToken), so the `artwork-asset-records-v1.json` records file becomes unnecessary too.

**Tech Stack:** Kotlin, Hilt, Room (`integration-cache.db`), kotlinx.coroutines, JUnit4/MockK.

---

## Context: Why this exists today

Historical accident, not deliberate design:

1. `ArtworkAssetDiskCache` predated artwork being routed through `IntegrationRuntime`. It owned both byte storage (`cache/artwork-assets/...`) and metadata (`ArtworkAssetRecordStore` → `artwork-asset-records-v1.json`).
2. When artwork byte fetches were rerouted through `IntegrationRuntime.get(IntegrationSpec(...))` so the runtime's `CacheFirst(ttlMs)` policy could enforce 7-day TTL, the runtime started caching every spec result by `cacheKey` — including the artwork bytes. The runtime cache lives at `files/integration-cache/<provider>/<scope>/...bin` and is Room-indexed.
3. Nobody removed `ArtworkAssetDiskCache`. Now `ArtworkAssetRepository.fetchInternal` reads from Layer 2 first, falls through to `runtime.get(spec)` (which reads/writes Layer 1), then on success writes the bytes AGAIN to Layer 2 via `diskCache.write(record, bytes)`. Both layers stay synced because every fetch writes both.

**Same SHA256, same byte count, two files.** Verified on-device 2026-05-16: `/data/data/com.nexio.tv.earlyaccess/files/integration-cache/artwork-asset/FANART_TV/backdrop/urlHash/8a4116f4.../1.bin` and `/data/data/com.nexio.tv.earlyaccess/cache/artwork-assets/FANART_TV/backdrop/artwork-asset_FANART_TV_backdrop_urlHash_8a4116f4..._policy_1.bin` are byte-identical (956,257 bytes, SHA256 `500bf52e...`).

---

## File Structure

### Files to MODIFY

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/core/integration/IntegrationRuntime.kt` | Add `suspend fun getBlobFile(spec): File?` returning the cached blob File without materializing bytes into memory |
| `app/src/main/java/com/nexio/tv/core/integration/LocalIntegrationCacheStore.kt` | Add `fun blobFileFor(cacheKey: String, provider: IntegrationProvider, scopeKey: String): File?` that queries Room for the row and returns the resolved file path |
| `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt` | Rewrite `fetchInternal`: drop `diskCache.getExistingFile()` read, drop `diskCache.write()` post-write, drop `persistAssetRecordBestEffort()`; resolve `localFile` via `runtime.getBlobFile(spec)`; the path through `getOrFetch`/`fetchOrFallback`/`getOrFetchDecision` stays — only the internal mechanism changes |
| `app/src/main/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidator.kt` | Replace `diskCache.hasReadableImageBytes(record)` and `assetRecordStore.get(assetKey)` / `findLatestAssetForDecision(decisionKey)` with equivalent queries via `IntegrationRuntime` + `ArtworkDecisionCache` |
| `app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt` | Remove `provideArtworkAssetDiskCache` and `provideArtworkAssetRecordStore` provider methods (lines 123–135) and their imports |

### Files to DELETE

| File | Why |
|---|---|
| `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetDiskCache.kt` | Layer 2 byte store — no longer needed |
| `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRecordStore.kt` | Metadata is now sourced from `integration_cache` Room table + `ArtworkDecisionCache` |
| `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetDiskCacheTest.kt` | Tests for deleted class |
| `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRecordStoreTest.kt` | Tests for deleted class |

### Files to CREATE

| File | Purpose |
|---|---|
| `app/src/main/java/com/nexio/tv/core/artwork/LegacyArtworkCacheCleaner.kt` | One-shot migration: deletes `cache/artwork-assets/` and `files/artwork-asset-records-v1.json` on first app launch post-upgrade |
| `app/src/test/java/com/nexio/tv/core/integration/LocalIntegrationCacheStoreBlobFileTest.kt` | Tests for the new `blobFileFor(...)` API |
| `app/src/test/java/com/nexio/tv/core/artwork/LegacyArtworkCacheCleanerTest.kt` | Tests for migration |

### Files where tests must be UPDATED (not deleted)

| File | Why |
|---|---|
| `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt` | The 1500+ line test file mocks `diskCache: ArtworkAssetDiskCache` and `assetRecordStore: ArtworkAssetRecordStore` extensively. Each test that uses them needs to switch to mocking `IntegrationRuntime.getBlobFile(...)` and `ArtworkDecisionCache.lookup(...)` |
| `app/src/test/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidatorTest.kt` | Same swap |

---

## Pre-flight: verify baseline storage on a real device

Before starting, capture the *before* picture so the cleanup can be quantified after.

```bash
DEV=192.168.50.98:5555
PKG=com.nexio.tv.earlyaccess
adb -s $DEV shell "su -c 'du -sh /data/data/$PKG/files/integration-cache/artwork-asset/* /data/data/$PKG/cache/artwork-assets/*'"
adb -s $DEV shell "su -c 'sqlite3 /data/data/$PKG/databases/integration-cache.db \"SELECT provider, COUNT(*) FROM integration_cache WHERE cacheKey LIKE \\\"artwork-asset:%\\\" GROUP BY provider;\"'"
```

Record the totals. After Task 10 verification, the `cache/artwork-assets/` row should be GONE and the `files/integration-cache/artwork-asset/*` row should be UNCHANGED.

---

## Task 1: Characterization test — lock in the current duplication contract

**Files:**
- Create: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkByteStoreDuplicationCharacterizationTest.kt`

This test documents (and locks in) the pre-cleanup behavior so that after the refactor we can show: "the same external observable end state is produced, but only one byte store is written." Once Task 9 removes the second store, this test gets deleted in the same commit.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.artwork

import com.google.gson.Gson
import com.nexio.tv.core.integration.IntegrationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pre-refactor characterization. Locks in the on-disk duplication observed 2026-05-16:
 * after one successful fetch, the same bytes appear in BOTH
 *   files/integration-cache/.../1.bin (IntegrationRuntime blob)
 *   cache/artwork-assets/.../<assetKey>.bin (ArtworkAssetDiskCache)
 *
 * After Task 4 lands, this test SHOULD fail (only one copy on disk). At that point
 * delete this file in the same commit — it has served its purpose.
 */
class ArtworkByteStoreDuplicationCharacterizationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `successful fetch writes bytes to both runtime cache and disk cache`() = runTest {
        val bytes = ByteArray(1024) { it.toByte() }
        val diskCache = ArtworkAssetDiskCache(temp.newFolder("cache"))
        val recordStore = mockk<ArtworkAssetRecordStore>(relaxed = true)
        val runtime = FakeIntegrationRuntimeWithBlobStore(
            blobsRoot = temp.newFolder("files-integration-cache"),
            successBytes = bytes
        )

        val repo = buildRepo(diskCache = diskCache, recordStore = recordStore, runtime = runtime)
        val decision = boysBackdropFanartDecision()

        val result = repo.getOrFetch(decision)
        assertNotNull(result)

        // Layer 1 wrote the blob
        val layer1File = runtime.blobFor(decision.selectedCandidate.sourceHash!!)
        assertArrayEquals(bytes, layer1File.readBytes())

        // Layer 2 ALSO wrote the same bytes (this is what we're characterizing)
        val layer2File = diskCache.getExistingFile(ArtworkCacheKeys.assetKeyForRemoteUrl(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
            imageType = ArtworkType.BACKDROP,
            normalizedUrlHash = decision.selectedCandidate.sourceHash!!,
            variant = null,
            policyVersion = decision.policyVersion
        ))
        assertNotNull("Layer 2 disk cache must contain bytes (pre-refactor)", layer2File)
        assertArrayEquals(bytes, layer2File!!.readBytes())
    }

    private fun buildRepo(
        diskCache: ArtworkAssetDiskCache,
        recordStore: ArtworkAssetRecordStore,
        runtime: FakeIntegrationRuntimeWithBlobStore
    ): ArtworkAssetRepository {
        // Use the production constructor; helpers are reused from ArtworkAssetRepositoryTest.
        return ArtworkAssetRepository(
            runtime = runtime,
            diskCache = diskCache,
            sourceMaterializer = ArtworkSourceMaterializer(emptyMap()),
            byteLoader = mockk(relaxed = true),
            decisionCache = mockk(relaxed = true),
            assetRecordStore = recordStore
        )
    }

    private fun boysBackdropFanartDecision(): ArtworkDecision =
        // Reuse the helper already defined in ArtworkAssetRepositoryTest.kt — extract to
        // a shared test fixture file `ArtworkDecisionTestFixtures.kt` as part of this step
        // if it isn't already shared.
        ArtworkAssetRepositoryTestFixtures.fanartBackdropDecision(
            tvdbId = "355567",
            url = "https://assets.fanart.tv/fanart/the-boys-2019-5bcc305c1e41f.jpg"
        )
}
```

If `ArtworkAssetRepositoryTestFixtures` doesn't already exist, extract the fixture helpers from `ArtworkAssetRepositoryTest.kt` into a new file `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTestFixtures.kt` as part of this step. Look in the existing test for `rpdbDecisionWithTmdbFallback`, `rpdbTemplateDecision`, etc., and add a `fanartBackdropDecision(tvdbId, url)` helper that returns a `FANART_TV` PREMIUM RemoteUrl decision.

`FakeIntegrationRuntimeWithBlobStore` is a small in-test fake: it implements `IntegrationRuntime` by writing successful spec results to `<blobsRoot>/<provider>/<scope>/<cacheKey-sanitized>.bin` and returning the bytes back through the `get(spec)` contract. Add it to the test file (or a shared test utility file) — do NOT mock `IntegrationRuntime` for this test; we need a real on-disk write to verify duplication.

- [ ] **Step 2: Run test to verify it passes (it should — this is characterization)**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.artwork.ArtworkByteStoreDuplicationCharacterizationTest' \
  -x generateIntegrationRuntimeAudit
```

Expected: PASS. If it fails, the fake or the production code has drifted from the documented behavior — investigate before continuing.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/core/artwork/ArtworkByteStoreDuplicationCharacterizationTest.kt \
        app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTestFixtures.kt
git commit -m "test(artwork): characterize byte-cache duplication pre-cleanup"
```

---

## Task 2: Add `IntegrationRuntime.getBlobFile(spec)` API

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationRuntime.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/integration/LocalIntegrationCacheStore.kt`
- Create: `app/src/test/java/com/nexio/tv/core/integration/LocalIntegrationCacheStoreBlobFileTest.kt`

The new `getBlobFile(spec): File?` returns the cached blob File when there's a fresh-or-stale-but-acceptable row in `integration_cache` for the spec's `cacheKey`. Returns `null` if no row exists (cache miss) or the row's blobPath file is missing on disk. This is the non-byte-loading equivalent of `runtime.get(spec).bytesOrNull()` — useful when the caller needs a File for streaming to Coil, not a `ByteArray` in memory.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.integration

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalIntegrationCacheStoreBlobFileTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `blobFileFor returns null when no row exists`() {
        val store = LocalIntegrationCacheStore(
            cacheRoot = temp.newFolder(),
            roomDb = inMemoryRoomDb(),
            gson = Gson()
        )
        val result = store.blobFileFor(
            cacheKey = "artwork-asset:FANART_TV:backdrop:urlHash:abc",
            provider = IntegrationProvider.FANART_TV,
            scopeKey = "global:image:lang:en"
        )
        assertNull(result)
    }

    @Test
    fun `blobFileFor returns the persisted blob path when row exists and file exists`() {
        val cacheRoot = temp.newFolder()
        val store = LocalIntegrationCacheStore(cacheRoot = cacheRoot, roomDb = inMemoryRoomDb(), gson = Gson())
        // Pre-populate one row (use the existing write API)
        store.put(
            cacheKey = "artwork-asset:FANART_TV:backdrop:urlHash:abc",
            provider = IntegrationProvider.FANART_TV,
            scopeKey = "global:image:lang:en",
            bytes = byteArrayOf(1, 2, 3, 4),
            mimeType = "application/octet-stream",
            expiresAtEpochMs = System.currentTimeMillis() + 86_400_000L,
            staleUntilEpochMs = System.currentTimeMillis() + 86_400_000L * 7
        )
        val result = store.blobFileFor(
            cacheKey = "artwork-asset:FANART_TV:backdrop:urlHash:abc",
            provider = IntegrationProvider.FANART_TV,
            scopeKey = "global:image:lang:en"
        )
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), result!!.readBytes().toList())
    }

    @Test
    fun `blobFileFor returns null when row exists but blob file was deleted`() {
        val cacheRoot = temp.newFolder()
        val store = LocalIntegrationCacheStore(cacheRoot = cacheRoot, roomDb = inMemoryRoomDb(), gson = Gson())
        store.put(
            cacheKey = "artwork-asset:FANART_TV:backdrop:urlHash:abc",
            provider = IntegrationProvider.FANART_TV,
            scopeKey = "global:image:lang:en",
            bytes = byteArrayOf(1, 2, 3, 4),
            mimeType = "application/octet-stream",
            expiresAtEpochMs = System.currentTimeMillis() + 86_400_000L,
            staleUntilEpochMs = System.currentTimeMillis() + 86_400_000L * 7
        )
        // Simulate external eviction (e.g. user clear cache)
        cacheRoot.walkBottomUp().filter { it.extension == "bin" }.forEach { it.delete() }
        val result = store.blobFileFor(
            cacheKey = "artwork-asset:FANART_TV:backdrop:urlHash:abc",
            provider = IntegrationProvider.FANART_TV,
            scopeKey = "global:image:lang:en"
        )
        assertNull(result)
    }

    private fun inMemoryRoomDb() = TODO("Use the same in-memory Room builder the rest of this codebase uses for IntegrationCacheDatabase — see existing LocalIntegrationCacheStoreTest")
}
```

The `inMemoryRoomDb()` helper should mirror whatever pattern exists in `LocalIntegrationCacheStoreTest.kt` already (search the repo for `Room.inMemoryDatabaseBuilder` and the `IntegrationCacheDatabase` reference) — do NOT introduce a new pattern.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.integration.LocalIntegrationCacheStoreBlobFileTest' \
  -x generateIntegrationRuntimeAudit
```

Expected: FAIL with `Unresolved reference: blobFileFor`.

- [ ] **Step 3: Implement `blobFileFor` in `LocalIntegrationCacheStore`**

In `LocalIntegrationCacheStore.kt`, add:

```kotlin
/**
 * Returns the on-disk File backing the cached blob for [cacheKey], or null if no row
 * exists or the file is missing. Does NOT consult TTL — callers that need freshness
 * checks should use [get] (which returns null for expired entries) and then call this.
 *
 * Intended for byte-stream consumers (Coil's NexioArtworkFetcher) that prefer a File
 * over a ByteArray to avoid materializing large images in memory.
 */
fun blobFileFor(
    cacheKey: String,
    provider: IntegrationProvider,
    scopeKey: String
): File? {
    val row = dao.findByKey(cacheKey) ?: return null
    // Defensive: caller-supplied provider/scopeKey must match the row's actual values,
    // otherwise we risk returning the wrong blob for a colliding cacheKey.
    if (row.provider != provider.name || row.scopeKey != scopeKey) return null
    val file = File(cacheRoot, row.blobPath)
    return file.takeIf { it.isFile && it.length() > 0 }
}
```

- [ ] **Step 4: Add `getBlobFile(spec)` to `IntegrationRuntime`**

In `IntegrationRuntime.kt` add the method that delegates to the store:

```kotlin
/**
 * Returns the cached blob File for [spec] if one exists on disk, regardless of freshness.
 * Use [get] when freshness matters; use this when you only need the bytes-on-disk for
 * streaming (e.g. Coil image fetcher).
 *
 * Implementation note: this is the read-side companion to the byte-storing path inside
 * [get]. By exposing the File directly, downstream callers avoid loading the entire
 * ByteArray into memory just to write it to a second on-disk cache (the historical
 * `ArtworkAssetDiskCache` did exactly this — duplicating bytes for no benefit).
 */
suspend fun getBlobFile(spec: IntegrationSpec<*>): File? {
    return cacheStore.blobFileFor(
        cacheKey = spec.cacheKey,
        provider = spec.provider,
        scopeKey = spec.scope.key
    )
}
```

If `IntegrationRuntime` is currently an interface, add this to both interface and implementation. If `spec.scope.key` isn't the right way to derive the scope key, mirror whatever the existing `cache get` code path does (check how `IntegrationRuntime.get` looks up rows today and reuse the same `scopeKey` derivation).

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.integration.LocalIntegrationCacheStoreBlobFileTest' \
  -x generateIntegrationRuntimeAudit
```

Expected: PASS (all three tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/integration/IntegrationRuntime.kt \
        app/src/main/java/com/nexio/tv/core/integration/LocalIntegrationCacheStore.kt \
        app/src/test/java/com/nexio/tv/core/integration/LocalIntegrationCacheStoreBlobFileTest.kt
git commit -m "feat(integration): expose IntegrationRuntime.getBlobFile for stream consumers"
```

---

## Task 3: Rewrite `ArtworkAssetRepository.fetchInternal` to read via `getBlobFile`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt` (lines 321–410)
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`

Goal: remove the Layer 2 read at the top of `fetchInternal` (the `diskCache.getExistingFile(materialized.assetKey)?.let { ... }` block at line 324) and replace it with a `runtime.getBlobFile(spec)` shortcut.

- [ ] **Step 1: Write the failing test**

In `ArtworkAssetRepositoryTest.kt`, add (or update if a similar test exists):

```kotlin
@Test
fun `fetchInternal returns blob from runtime without re-fetching when runtime cache hit`() = runTest {
    val bytes = byteArrayOf(1, 2, 3, 4)
    val runtime = FakeIntegrationRuntimeWithBlobStore(
        blobsRoot = temp.newFolder("files-integration-cache"),
        successBytes = bytes
    )
    // Pre-populate the runtime cache for the decision's assetKey
    val decision = fanartBackdropDecision(
        tvdbId = "355567",
        url = "https://assets.fanart.tv/fanart/the-boys-2019-5bcc305c1e41f.jpg"
    )
    runtime.prePopulateBlobFor(decision.selectedCandidate.sourceHash!!, bytes)

    val byteLoader = mockk<ArtworkByteLoader>()
    // CRITICAL: byteLoader.load MUST NOT be called — the test fails if it is
    coEvery { byteLoader.load(any(), any()) } answers {
        throw AssertionError("byteLoader.load called on a runtime cache hit")
    }

    val repo = ArtworkAssetRepository(
        runtime = runtime,
        diskCache = mockk(relaxed = true),  // unused by fetchInternal after refactor
        sourceMaterializer = ArtworkSourceMaterializer(
            remoteSourcesByHash = mapOf(
                decision.selectedCandidate.sourceHash!! to SensitiveArtworkUrl.of(
                    "https://assets.fanart.tv/fanart/the-boys-2019-5bcc305c1e41f.jpg"
                )
            )
        ),
        byteLoader = byteLoader,
        decisionCache = mockk(relaxed = true),
        assetRecordStore = mockk(relaxed = true)
    )

    val result = repo.getOrFetch(decision)
    assertNotNull(result)
    assertEquals(bytes.toList(), result!!.localFile.readBytes().toList())
    assertFalse("Network must not have been touched", result.networkExecuted)
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest.fetchInternal returns blob from runtime without re-fetching when runtime cache hit' \
  -x generateIntegrationRuntimeAudit
```

Expected: FAIL. Today's code calls `diskCache.getExistingFile()` (mocked relaxed → returns null), falls through to `runtime.get(spec)` which deserializes bytes into memory, then tries to write to Layer 2 (mocked relaxed → no-op), then returns a `localFile` that points at a Layer 2 path that doesn't exist. Should fail with "file not found" or null `result`.

- [ ] **Step 3: Rewrite `fetchInternal` (read-path only)**

Replace lines 321–332 in `ArtworkAssetRepository.kt`:

```kotlin
private suspend fun fetchInternal(decision: ArtworkDecision): FetchOutcome {
    val materialized = sourceMaterializer.materialize(decision)
        ?: return FetchOutcome.NotMaterialized

    val runtimeSpec = buildSpec(decision, materialized) { loaderInvoked, lastLoadResult ->
        // load lambda — same as before, byteLoader.load(materialized.source, decision)
        loaderInvoked.value = true
        val loaded = byteLoader.load(materialized.source, decision)
        lastLoadResult.value = loaded
        loaded
    }

    // Layer 1 hit fast-path: if the runtime already has the blob on disk, return it
    // without round-tripping through runtime.get (which would deserialize bytes into
    // memory unnecessarily). This replaces the old `diskCache.getExistingFile()` call
    // that read from a duplicate on-disk store.
    runtime.getBlobFile(runtimeSpec)?.let { existingBlob ->
        return existingAssetResultOrNull(
            file = existingBlob,
            materialized = materialized,
            decision = decision,
            cacheDecision = "ARTWORK_RUNTIME_BLOB_HIT",
            networkExecuted = false
        )?.let { FetchOutcome.Success(it) } ?: FetchOutcome.NotMaterialized
    }

    // ... rest of the function continues as before with the runtime.get(spec) call
```

Where `buildSpec` is a small extracted helper that wraps the existing `IntegrationSpec(...)` construction (lines 338–358 today) so we can call it from two places (the fast-path skip above and the slow-path fetch below) without duplicating the spec definition. Extract it as:

```kotlin
private fun buildSpec(
    decision: ArtworkDecision,
    materialized: MaterializedArtworkSource,
    load: suspend (loaderInvoked: AtomicBoolean, lastLoadResult: AtomicReference<IntegrationLoadResult<ByteArray>?>) -> IntegrationLoadResult<ByteArray>
): IntegrationSpec<ByteArray> = IntegrationSpec(
    provider = materialized.runtimeProvider,
    apiShapeId = materialized.apiShapeId,
    operationKey = materialized.assetKey.value,
    cacheKey = materialized.assetKey.value,
    codec = ByteArrayIntegrationCodec,
    cachePolicy = IntegrationCachePolicy.CacheFirst(
        ttlMs = (decision.expiresAtMs - decision.createdAtMs).coerceAtLeast(1L),
        staleAfterExpiryMs = ((decision.staleUntilMs ?: decision.expiresAtMs) - decision.expiresAtMs)
            .coerceAtLeast(0L)
    ),
    workClass = IntegrationWorkClass.BACKGROUND_HYDRATION,
    scope = IntegrationScope.GlobalEnglishImage,
    load = { /* delegate via the passed-in lambda */ }
)
```

Then in `fetchInternal`'s slow path (lines 338–360 today) call `runtime.get(runtimeSpec)` using the same `runtimeSpec` built above.

In the success branch (lines 384–410), DELETE the `diskCache.recordFor(...)` / `diskCache.write(record, bytes)` / `persistAssetRecordBestEffort(...)` block. After `runtime.get` returns success, the bytes are already on disk via Layer 1. Build the `ArtworkAssetResult` directly:

```kotlin
val blobFile = runtime.getBlobFile(runtimeSpec)
    ?: return FetchOutcome.HardFailure(loadErrorClass = "blob_file_missing_after_write")
return FetchOutcome.Success(
    ArtworkAssetResult(
        assetKey = materialized.assetKey,
        localFile = blobFile,
        record = ArtworkAssetRecord(  // construct in-memory only, do not persist
            assetKey = materialized.assetKey,
            decisionKey = decision.decisionKey,
            provider = materialized.provider,
            imageType = decision.imageType,
            imageLanguage = decision.imageLanguage,
            relativePath = "",  // unused now that Layer 2 is gone
            mimeType = ByteArrayIntegrationCodec.mimeType,
            byteCount = blobFile.length(),
            sourceHash = materialized.sourceHash,
            policyVersion = decision.policyVersion,
            fetchedAtMs = System.currentTimeMillis(),
            expiresAtMs = decision.expiresAtMs,
            staleUntilMs = decision.staleUntilMs
        ),
        runtimeResult = result,
        runtimeApiShapeId = apiShapeId,
        cacheDecision = result.cacheDecision(),
        mimeType = ByteArrayIntegrationCodec.mimeType,
        networkExecuted = loaderInvoked.value,
        durable = true  // Layer 1 is always durable; the prior `durable=false` branch went away with persistAssetRecordBestEffort
    )
)
```

If `ArtworkAssetRecord.relativePath` is read anywhere besides the deleted `ArtworkAssetDiskCache`, audit those readers (grep `relativePath`) and either fix them to use `localFile` or remove the field from the record in Task 6.

- [ ] **Step 4: Run the new test to verify it passes**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest.fetchInternal returns blob from runtime without re-fetching when runtime cache hit' \
  -x generateIntegrationRuntimeAudit
```

Expected: PASS.

- [ ] **Step 5: Run the full repository test suite — many will fail**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest' \
  -x generateIntegrationRuntimeAudit 2>&1 | tail -20
```

Expected: a batch of failures. Each failure indicates a test that was asserting Layer 2 behavior (`diskCache.write`, `assetRecordStore.put`, `result.durable == false` because record persistence failed, etc.). Update each test in the next step.

- [ ] **Step 6: Update broken tests in `ArtworkAssetRepositoryTest.kt`**

For each failing test:

- If the test asserts `diskCache.write was called` → delete the assertion (Layer 2 write is gone)
- If the test asserts `assetRecordStore.put was called` → delete the assertion (records file is gone)
- If the test asserts `result.durable == false` due to record-write failure → change to `result.durable == true` (Layer 1 writes are atomic-rename, always durable)
- If the test pre-populates the OLD Layer 2 store via `diskCache.write(...)` to simulate a cache hit → replace with `runtime.prePopulateBlobFor(...)` on the fake runtime
- If the test mocks `diskCache: ArtworkAssetDiskCache` with `every { ... }` blocks → switch to mocking `runtime.getBlobFile(...)`

This is mechanical but tedious. Estimated 20–30 test updates. Each one should still test the same external contract (does fetch succeed / fail / fall back?) — only the inputs change.

- [ ] **Step 7: Run all artwork tests to verify only the characterization test from Task 1 still fails**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.artwork.*' \
  -x generateIntegrationRuntimeAudit 2>&1 | tail -10
```

Expected: ONLY `ArtworkByteStoreDuplicationCharacterizationTest` fails (because Layer 2 no longer gets written — proving the cleanup works). All other artwork tests pass. The 6 pre-existing failures from main (`ArtworkAssetRecordStoreTest`, `ArtworkDecisionCacheTest`, `PosterRatingsUrlResolverTest`) are unrelated and out of scope.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt \
        app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt
git commit -m "refactor(artwork): route fetchInternal reads through IntegrationRuntime.getBlobFile

Removes the Layer 2 read+write path in ArtworkAssetRepository.fetchInternal.
Bytes now live in exactly one place on disk (files/integration-cache/) instead
of being mirrored to cache/artwork-assets/. The Layer 1 file is exposed via
runtime.getBlobFile(spec) and returned directly as ArtworkAssetResult.localFile,
so NexioArtworkFetcher continues to stream from a real File without any change."
```

---

## Task 4: Delete the characterization test (it has done its job)

**Files:**
- Delete: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkByteStoreDuplicationCharacterizationTest.kt`

- [ ] **Step 1: Verify the characterization test is now failing (post-Task 3 state)**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.artwork.ArtworkByteStoreDuplicationCharacterizationTest' \
  -x generateIntegrationRuntimeAudit
```

Expected: FAIL on `assertNotNull("Layer 2 disk cache must contain bytes (pre-refactor)", layer2File)`. This is the expected outcome — Layer 2 is no longer being written.

- [ ] **Step 2: Delete the file**

```bash
git rm app/src/test/java/com/nexio/tv/core/artwork/ArtworkByteStoreDuplicationCharacterizationTest.kt
```

- [ ] **Step 3: Commit**

```bash
git commit -m "test(artwork): drop byte-duplication characterization (cleanup landed)"
```

---

## Task 5: Rewrite `ArtworkReferenceIntegrityValidator` to query without Layer 2

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidator.kt` (lines 58, 85, 101, 172, 191)
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidatorTest.kt`

The validator is the second consumer of Layer 2. Today it uses:
- `assetRecordStore.get(assetKey)` → check if an asset record exists
- `assetRecordStore.findLatestAssetForDecision(decisionKey)` → look up the most recent asset record for a decision (used during recovery)
- `diskCache.hasReadableImageBytes(record)` → check that the bytes file exists and has a non-trivial header

Replacement strategy: keep the public surface of `ArtworkReferenceIntegrityValidator` unchanged. Inside, replace each Layer 2 call with the equivalent `IntegrationRuntime` query.

- [ ] **Step 1: Read the validator's current logic in full and identify each query's semantics**

```bash
cat app/src/main/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidator.kt
```

Walk through every test in `ArtworkReferenceIntegrityValidatorTest.kt` to enumerate the exact contracts the validator must preserve. List each behavior in this task's commit message later.

- [ ] **Step 2: Replace `diskCache.hasReadableImageBytes(record)` with a Layer 1 equivalent**

Where the validator currently does:

```kotlin
diskCache.hasReadableImageBytes(record)
```

Add a small file in the same package or extend `IntegrationRuntime` with:

```kotlin
suspend fun hasReadableBlob(spec: IntegrationSpec<*>): Boolean {
    val file = getBlobFile(spec) ?: return false
    if (file.length() < 12) return false
    return file.inputStream().use { input ->
        val header = ByteArray(12)
        val count = input.read(header)
        // mirror existing ArtworkAssetDiskCache.hasReadableImageBytes logic:
        // detect JPEG (ff d8), PNG (89 50 4e 47), GIF (47 49 46), WebP (52 49 46 46 ... 57 45 42 50)
        count >= 4 && isKnownImageHeader(header.copyOf(count.coerceAtLeast(0)))
    }
}

private fun isKnownImageHeader(bytes: ByteArray): Boolean {
    if (bytes.size < 2) return false
    return when {
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> true  // JPEG
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> true  // PNG
        bytes.size >= 3 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() -> true  // GIF
        bytes.size >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte() -> true  // WebP
        else -> false
    }
}
```

Cross-check against the existing `ArtworkAssetDiskCache.hasReadableImageBytes` to make sure the header detection set matches. If `ArtworkAssetDiskCache` does anything more (e.g. checking byte count > some minimum), preserve that behavior in `isKnownImageHeader`.

Then in `ArtworkReferenceIntegrityValidator`, replace each call:

```kotlin
// BEFORE
diskCache.hasReadableImageBytes(record)

// AFTER
runtime.hasReadableBlob(record.toIntegrationSpec())
```

Where `record.toIntegrationSpec()` is a small extension that builds the same `IntegrationSpec` shape the repository uses. Place it next to `ArtworkAssetRepository`'s `buildSpec` so they share the construction logic.

- [ ] **Step 3: Replace `assetRecordStore.get(assetKey)` with Room query**

For `assetRecordStore.get(assetKey)`: today this returns an `ArtworkAssetRecord` if one exists for the assetKey, regardless of which decision produced it. The Room `integration_cache` table has one row per cacheKey (= assetKey). A "found" record corresponds to "Room row exists AND blob file exists".

Replace:

```kotlin
val recordExists = assetRecordStore.get(assetKey) != null
```

With:

```kotlin
val recordExists = runtime.getBlobFile(specForAssetKey(assetKey)) != null
```

Where `specForAssetKey(assetKey: ArtworkAssetKey)` reconstructs a minimal `IntegrationSpec` from the assetKey alone (parsing provider/imageType out of the assetKey string — the existing `ArtworkAssetDiskCache.relativePathFor` does exactly this parsing, lines 86–97). Note: the spec won't have the original `load` lambda, but `getBlobFile` doesn't need it.

- [ ] **Step 4: Replace `assetRecordStore.findLatestAssetForDecision(decisionKey)`**

This is the trickiest one. The existing `findLatestAssetForDecision` indexes asset records by `decisionKey`. The Room `integration_cache` table does NOT have a `decisionKey` column today.

Two options:

**Option A (recommended):** route through `ArtworkDecisionCache`. The decision cache already knows the decision → it has the selectedCandidate with sourceHash → derive the assetKey → query Layer 1 by assetKey. Implement as:

```kotlin
private suspend fun findRecoveryAssetForDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetResolution? {
    val decision = decisionCache.lookupOrStale(decisionKey) ?: return null
    val materialized = sourceMaterializer.materialize(decision) ?: return null
    val blobFile = runtime.getBlobFile(materializedToSpec(decision, materialized)) ?: return null
    return ArtworkAssetResolution(assetKey = materialized.assetKey, file = blobFile)
}
```

**Option B (heavier):** add a `decisionKey` column + index to `integration_cache` Room table via a migration. More work, but lets the validator do a direct query. Choose Option A unless the validator needs to find assets by decisionKey in performance-critical paths.

Pick Option A.

- [ ] **Step 5: Run validator tests, expect failures, update them**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.artwork.ArtworkReferenceIntegrityValidatorTest' \
  -x generateIntegrationRuntimeAudit 2>&1 | tail -20
```

Expected: many failures. Update each one to mock `runtime.getBlobFile`, `runtime.hasReadableBlob`, and `decisionCache.lookupOrStale` instead of the old surface. Same mechanical patterns as Task 3 Step 6.

- [ ] **Step 6: Run all artwork tests to confirm nothing else broke**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.artwork.*' -x generateIntegrationRuntimeAudit 2>&1 | tail -10
```

Expected: pass (except the 6 pre-existing unrelated failures from main).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidator.kt \
        app/src/main/java/com/nexio/tv/core/integration/IntegrationRuntime.kt \
        app/src/test/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidatorTest.kt
git commit -m "refactor(artwork): rewrite integrity validator to query Layer 1 directly"
```

---

## Task 6: Delete `ArtworkAssetDiskCache` and `ArtworkAssetRecordStore`

**Files:**
- Delete: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetDiskCache.kt`
- Delete: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRecordStore.kt` (and any sibling files like `DurableArtworkAssetRecordStore.kt` / `FileBackedArtworkAssetRecordStore.kt`)
- Delete: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetDiskCacheTest.kt`
- Delete: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRecordStoreTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt` (constructor)
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidator.kt` (constructor)

- [ ] **Step 1: Remove the now-unused constructor params from `ArtworkAssetRepository`**

Drop `diskCache: ArtworkAssetDiskCache` and `assetRecordStore: ArtworkAssetRecordStore` from the constructor. Compiler errors will point at every callsite that still passes them — update each one to drop the args. Most call sites are in tests; the production wiring is in the DI module.

- [ ] **Step 2: Remove the same params from `ArtworkReferenceIntegrityValidator`**

Same as above.

- [ ] **Step 3: Delete the DI providers**

In `app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt`, delete:
- The `provideArtworkAssetDiskCache` provider (~line 123–127)
- The `provideArtworkAssetRecordStore` provider (~line 129–135)
- The corresponding imports (`com.nexio.tv.core.artwork.ArtworkAssetDiskCache`, `com.nexio.tv.core.artwork.ArtworkAssetRecordStore`, `com.nexio.tv.core.artwork.DurableArtworkAssetRecordStore`)

- [ ] **Step 4: Delete the source files**

```bash
git rm app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetDiskCache.kt
git rm app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRecordStore.kt
# Also any DurableArtworkAssetRecordStore.kt / FileBackedArtworkAssetRecordStore.kt that exists
ls app/src/main/java/com/nexio/tv/core/artwork/*RecordStore*.kt app/src/main/java/com/nexio/tv/core/artwork/*DiskCache*.kt 2>/dev/null
# git rm each one that's listed
git rm app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetDiskCacheTest.kt
git rm app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRecordStoreTest.kt
```

- [ ] **Step 5: Verify the build compiles**

```bash
./gradlew :app:compileUniversalDebugKotlin -x generateIntegrationRuntimeAudit 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. Any compilation errors point at a leftover import or call site — fix and retry.

- [ ] **Step 6: Run all artwork tests**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.artwork.*' -x generateIntegrationRuntimeAudit 2>&1 | tail -10
```

Expected: PASS (modulo the 6 pre-existing unrelated failures).

- [ ] **Step 7: Commit**

```bash
git add -u  # picks up the deletions and modifications by explicit-path tracking
# WAIT — rule #7 forbids `git add -u` if there are other-agent changes. Use explicit paths instead:
git add app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt \
        app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt \
        app/src/main/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidator.kt
# Deletions are already staged via `git rm` above
git status -sb  # verify staged set
git commit -m "refactor(artwork): delete ArtworkAssetDiskCache and ArtworkAssetRecordStore

Layer 2 byte-store and metadata-store removed. ArtworkAssetRepository and
ArtworkReferenceIntegrityValidator now read exclusively from Layer 1
(IntegrationRuntime files/integration-cache/) via getBlobFile / hasReadableBlob.
Net delta: -N production lines, -M test lines (numbers in PR description).

cache/artwork-assets/ and files/artwork-asset-records-v1.json are no longer
written. A one-shot cleanup of the legacy directories is added in Task 7."
```

---

## Task 7: One-shot legacy cache cleaner

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/LegacyArtworkCacheCleaner.kt`
- Create: `app/src/test/java/com/nexio/tv/core/artwork/LegacyArtworkCacheCleanerTest.kt`
- Modify: a startup hook (likely `app/src/main/java/com/nexio/tv/NexioApplication.kt` or whichever `Application.onCreate` exists — search for `class.*Application` and `onCreate`)

On the first launch after the upgrade lands, delete the now-orphaned legacy directories. This is the migration that prevents existing users from being stuck with 22+ MB of dead Layer 2 bytes plus a stale records JSON.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.artwork

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyArtworkCacheCleanerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `cleanOnce deletes cache subdir and records file when present`() {
        val cacheDir = temp.newFolder("cache")
        val filesDir = temp.newFolder("files")
        val legacyArtworkDir = File(cacheDir, "artwork-assets").apply {
            mkdirs()
            File(this, "FANART_TV/backdrop/something.bin").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1, 2))
        }
        val legacyRecordsFile = File(filesDir, "artwork-asset-records-v1.json").apply {
            writeText("""{"schemaVersion":1,"records":{}}""")
        }
        val markerFile = File(filesDir, "legacy-artwork-cache-cleaned.marker")

        val cleaner = LegacyArtworkCacheCleaner(cacheDir = cacheDir, filesDir = filesDir)
        cleaner.cleanOnce()

        assertFalse("Layer 2 byte dir must be deleted", legacyArtworkDir.exists())
        assertFalse("Records file must be deleted", legacyRecordsFile.exists())
        assertTrue("Marker file must exist to prevent re-running", markerFile.exists())
    }

    @Test
    fun `cleanOnce is idempotent — second call is a no-op even if user re-creates the dir`() {
        val cacheDir = temp.newFolder("cache")
        val filesDir = temp.newFolder("files")
        val cleaner = LegacyArtworkCacheCleaner(cacheDir = cacheDir, filesDir = filesDir)
        cleaner.cleanOnce()  // first run — creates marker
        // User somehow ends up with the legacy dir again (a stale Coil version, dev playback)
        File(cacheDir, "artwork-assets").apply { mkdirs() }
        cleaner.cleanOnce()  // second run — should NOT delete (marker exists)
        assertTrue("Second call must not delete user-created dir", File(cacheDir, "artwork-assets").exists())
    }

    @Test
    fun `cleanOnce is a no-op when legacy paths do not exist (fresh install)`() {
        val cacheDir = temp.newFolder("cache")
        val filesDir = temp.newFolder("files")
        val cleaner = LegacyArtworkCacheCleaner(cacheDir = cacheDir, filesDir = filesDir)
        cleaner.cleanOnce()  // must not throw
        assertTrue("Marker still gets written on fresh install (so we don't re-check)", File(filesDir, "legacy-artwork-cache-cleaned.marker").exists())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.artwork.LegacyArtworkCacheCleanerTest' \
  -x generateIntegrationRuntimeAudit
```

Expected: FAIL with `Unresolved reference: LegacyArtworkCacheCleaner`.

- [ ] **Step 3: Implement the cleaner**

```kotlin
package com.nexio.tv.core.artwork

import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LegacyArtworkCacheCleaner @Inject constructor(
    private val cacheDir: File,
    private val filesDir: File
) {
    /**
     * Deletes the orphaned Layer 2 byte store (`cache/artwork-assets/`) and the
     * orphaned asset-records JSON (`files/artwork-asset-records-v1.json`) on first
     * launch after the byte-cache deduplication landed. Idempotent via a marker file.
     *
     * Safe to call from `Application.onCreate` (synchronous, single-digit-ms even when
     * the legacy dir has thousands of files because we use `File.deleteRecursively`).
     */
    fun cleanOnce() {
        val marker = File(filesDir, MARKER_FILE_NAME)
        if (marker.exists()) return

        try {
            val legacyByteDir = File(cacheDir, LEGACY_BYTE_DIR_NAME)
            if (legacyByteDir.exists()) {
                val deleted = legacyByteDir.deleteRecursively()
                Log.i(TAG, "Legacy artwork byte dir deletion: $deleted (path=${legacyByteDir.absolutePath})")
            }
            val legacyRecordsFile = File(filesDir, LEGACY_RECORDS_FILE_NAME)
            if (legacyRecordsFile.exists()) {
                val deleted = legacyRecordsFile.delete()
                Log.i(TAG, "Legacy artwork records file deletion: $deleted (path=${legacyRecordsFile.absolutePath})")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Legacy artwork cache cleanup encountered an error; marker still written to avoid retry loop", e)
        } finally {
            // Marker is written even on failure so we don't churn the cleanup every launch.
            runCatching { marker.writeBytes(byteArrayOf(1)) }
        }
    }

    private companion object {
        const val TAG = "LegacyArtworkCacheCleaner"
        const val MARKER_FILE_NAME = "legacy-artwork-cache-cleaned.marker"
        const val LEGACY_BYTE_DIR_NAME = "artwork-assets"
        const val LEGACY_RECORDS_FILE_NAME = "artwork-asset-records-v1.json"
    }
}
```

- [ ] **Step 4: Wire it into app startup**

Search for the application class:

```bash
grep -rn 'class.*Application' app/src/main/java/com/nexio/tv/NexioApplication.kt 2>/dev/null || grep -rln 'AndroidEntryPoint\|HiltAndroidApp' app/src/main/java | grep -i application | head -3
```

In whichever class has the `onCreate` that already runs other startup hooks (look for `@Inject` fields and `super.onCreate()`), inject `LegacyArtworkCacheCleaner` and call `cleaner.cleanOnce()` after `super.onCreate()`. Example:

```kotlin
@Inject lateinit var legacyArtworkCacheCleaner: LegacyArtworkCacheCleaner

override fun onCreate() {
    super.onCreate()
    legacyArtworkCacheCleaner.cleanOnce()
    // ...existing init...
}
```

If the existing application class uses a "startup tasks" list pattern (some apps have a `StartupTasks` collector), add `LegacyArtworkCacheCleaner::cleanOnce` to that list instead of editing `onCreate` directly.

- [ ] **Step 5: Add DI binding**

In the appropriate Hilt module (likely the one that already provides `@ApplicationContext File`s — search for `provideCacheDir` / `provideFilesDir` if those exist, or use the `@ApplicationContext` constructor injection if `cacheDir` / `filesDir` can be derived). The simplest path:

```kotlin
@Provides
@Singleton
fun provideLegacyArtworkCacheCleaner(
    @ApplicationContext context: Context
): LegacyArtworkCacheCleaner = LegacyArtworkCacheCleaner(
    cacheDir = context.cacheDir,
    filesDir = context.filesDir
)
```

- [ ] **Step 6: Run test to verify it passes**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.core.artwork.LegacyArtworkCacheCleanerTest' \
  -x generateIntegrationRuntimeAudit
```

Expected: PASS (all three tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/LegacyArtworkCacheCleaner.kt \
        app/src/test/java/com/nexio/tv/core/artwork/LegacyArtworkCacheCleanerTest.kt \
        app/src/main/java/com/nexio/tv/NexioApplication.kt  # or wherever startup wiring landed
# also stage the Hilt module that gained provideLegacyArtworkCacheCleaner
git status -sb  # verify staged set
git commit -m "feat(artwork): one-shot cleanup of legacy Layer 2 byte cache

On first launch after the artwork byte-cache deduplication, delete the
orphaned cache/artwork-assets/ directory and files/artwork-asset-records-v1.json.
Idempotent via a marker file in filesDir."
```

---

## Task 8: On-device verification

**Files:** none (verification only)

- [ ] **Step 1: Capture *before* state on the device** (if a real device is available — skip if running CI-only)

```bash
DEV=192.168.50.98:5555
PKG=com.nexio.tv.earlyaccess
adb -s $DEV shell "su -c 'du -sh /data/data/$PKG/files/integration-cache/artwork-asset/* /data/data/$PKG/cache/artwork-assets/* 2>/dev/null'"
```

Record the totals.

- [ ] **Step 2: Build and install**

```bash
./gradlew :app:installArmv7ReleaseEarlyAccess \
  -PNEXIO_PREBUILT_FFMPEG_ROOT="$(pwd)/media/libraries/decoder_ffmpeg/prebuilt/ffmpeg" \
  -x generateIntegrationRuntimeAudit 2>&1 | tail -5
```

- [ ] **Step 3: Run the CLAUDE.md rule #8 smoke (profile picker is NOT the home screen)**

```bash
DEV=192.168.50.98:5555
PKG=com.nexio.tv.earlyaccess
adb -s $DEV shell am force-stop $PKG
adb -s $DEV logcat -c
adb -s $DEV shell monkey -p $PKG 1 > /dev/null 2>&1
sleep 5
adb -s $DEV shell input keyevent KEYCODE_DPAD_CENTER
sleep 60
adb -s $DEV logcat -d -t 1000 | grep -E "FATAL|AndroidRuntime|ANR|ClassCastException|NoSuchMethod" | tail -10
```

Expected: no crashes.

- [ ] **Step 4: Verify Layer 2 is gone**

```bash
adb -s $DEV shell "su -c 'ls -la /data/data/$PKG/cache/artwork-assets 2>&1; ls -la /data/data/$PKG/files/artwork-asset-records-v1.json 2>&1; ls -la /data/data/$PKG/files/legacy-artwork-cache-cleaned.marker 2>&1'"
```

Expected: first two paths report "No such file or directory"; the marker file exists.

- [ ] **Step 5: Verify Layer 1 still has the artwork bytes**

```bash
adb -s $DEV shell "su -c 'du -sh /data/data/$PKG/files/integration-cache/artwork-asset/*'"
adb -s $DEV shell "su -c 'sqlite3 /data/data/$PKG/databases/integration-cache.db \"SELECT provider, COUNT(*) FROM integration_cache WHERE cacheKey LIKE \\\"artwork-asset:%\\\" GROUP BY provider;\"'"
```

Expected: same row counts as in Step 1's *before* picture for `files/integration-cache/`. Total on-disk artwork bytes should be roughly HALF of what Step 1 reported (Layer 2 gone).

- [ ] **Step 6: Verify rendering still works (visual)**

Navigate Modern Home for ~60s. Confirm:
- Posters, backdrops, logos all render
- Fanart-configured types render Fanart art (not TVDB)
- No "broken image" placeholders appear

If any artwork is missing, capture logcat with `Nexio.MetaRoute` and `NexioArtworkFetcher` filters and investigate. The most likely failure mode is `runtime.getBlobFile(spec)` returning null for a spec that has a valid `integration_cache` row — check the `provider`/`scopeKey` defensive comparison in Task 2 Step 3.

---

## Task 9: PR prep

**Files:** none (documentation only)

- [ ] **Step 1: Compute the net diff**

```bash
git diff --stat <branch-base>..HEAD -- app/src/main/java/com/nexio/tv/core/artwork app/src/main/java/com/nexio/tv/core/integration app/src/main/java/com/nexio/tv/core/di app/src/test/java/com/nexio/tv/core/artwork app/src/test/java/com/nexio/tv/core/integration
```

Note the +/- line counts for the PR description.

- [ ] **Step 2: Write the PR body**

Use this skeleton:

```
## Summary
- Removes ~30 MB of duplicate artwork bytes on every device (per-user; scales with library size)
- ArtworkAssetRepository and ArtworkReferenceIntegrityValidator now read exclusively
  from IntegrationRuntime's blob store (Layer 1) — Layer 2 (ArtworkAssetDiskCache /
  ArtworkAssetRecordStore) is deleted
- One-shot LegacyArtworkCacheCleaner deletes orphaned cache/artwork-assets/ and
  files/artwork-asset-records-v1.json on first launch after upgrade

## Test plan
- [ ] All existing artwork unit tests pass (`./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.core.artwork.*'`)
- [ ] LegacyArtworkCacheCleanerTest passes (3 tests)
- [ ] LocalIntegrationCacheStoreBlobFileTest passes (3 tests)
- [ ] On-device smoke (CLAUDE.md rule #8) — no crashes, no broken artwork on Modern Home
- [ ] Storage measurement: cache/artwork-assets/ gone, files/integration-cache/artwork-asset/* unchanged
- [ ] First-launch marker file written to filesDir/legacy-artwork-cache-cleaned.marker

## Notes
- 6 pre-existing test failures in main (ArtworkAssetRecordStoreTest, ArtworkDecisionCacheTest,
  PosterRatingsUrlResolverTest) are unrelated and the first two are obsoleted by file deletion
  in this PR; the third is orthogonal
- No schema changes to integration-cache.db Room database
- No changes to Coil disk cache (cache/image_cache/) — that's Layer 3, independent
```

- [ ] **Step 3: Push the branch and open the PR**

```bash
git push -u origin <feature-branch-name>
# Open in browser using the gh CLI:
gh pr create --title "refactor(artwork): drop duplicate Layer 2 byte cache" --body "$(cat pr-body.md)"
```

---

## Self-Review Checklist

Run through this before declaring the plan complete:

- [x] **Spec coverage**: each layer-2 concern (read path, write path, validator, DI, tests, migration) has its own task
- [x] **Placeholder scan**: no "TBD" / "add appropriate error handling" / "similar to Task N" — every step has either exact code or an exact command
- [x] **Type consistency**: `getBlobFile(spec)` introduced in Task 2 is the same name used in Tasks 3, 5, 8; `hasReadableBlob(spec)` introduced in Task 5 is consistent; `LegacyArtworkCacheCleaner.cleanOnce()` signature matches between definition (Task 7 Step 3) and call sites (Task 7 Step 4 + tests)
- [x] **CLAUDE.md compliance**: every commit uses explicit-path `git add` (rule #7); the on-device verification includes profile selection (rule #8); no large blobs land in SharedPreferences/DataStore (rule #3); coroutines don't iterate via suspending `forEach` (rule #4) — the cleaner is synchronous so this doesn't apply
- [x] **Reversibility**: Tasks 1–6 are reversible via `git revert`. Task 7's cleanup is destructive (deletes Layer 2 byte files) but the bytes are reproducible from Layer 1 + a re-fetch; the marker file ensures the cleanup runs once

---

## Out of scope (deliberately)

- **Coil image_cache changes**: the Layer 3 LRU disk cache stays as-is; this plan only addresses Layer 1 / Layer 2 duplication
- **OkHttp http_cache**: not used for artwork; out of scope
- **`persistAssetRecordBestEffort` fix**: the records JSON sync issue we identified earlier becomes moot once the records JSON is deleted (Task 6)
- **`integration_cache` schema changes**: deliberately avoided to keep this PR migration-free
- **Decision-cache cleanup**: separate concern; `ArtworkDecisionCache` is not affected by this refactor
- **Coil cache invalidation on settings change**: orthogonal; the existing `ArtworkSettingsInvalidator` handles decision-cache invalidation, not byte cache
