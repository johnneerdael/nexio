# Artwork Reference Integrity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make persisted home artwork references integrity-safe and recoverable so an app restart cannot blank premium posters or reject a usable home snapshot because individual artwork refs are orphaned.

**Architecture:** Keep the existing typed `ArtworkDecisionCache` authority model, then add an integrity layer above it. Persist a lightweight asset-record reverse index, recover decision URIs from existing asset files when decisions are missing, degrade snapshot artwork per item instead of rejecting whole snapshots, and add a write barrier that prevents unbacked decision refs and `poster=null` with a provider tag from being persisted.

**Tech Stack:** Kotlin, Android/Hilt, Gson file persistence, shared preferences snapshot storage, Coil fetchers, existing `RuntimeTraceSink` / logcat tracing, JUnit4/Robolectric, MockK.

---

## Preflight

Work in `/Users/jneerdael/Scripts/nexio` on the dirty local `main` checkout. Do not move this to a clean worktree. Do not revert or overwrite unrelated local files.

Before starting execution, record the current state:

```bash
git status --short
git branch --show-current
```

Expected:

```text
main
```

If unrelated files are already modified, leave them untouched and stage only files listed in each task.

## Current Evidence Boundary

The latest device evidence changes the failure from "RPDB cannot render" to "persisted artwork references are not integrity-safe":

- RPDB asset bytes exist and can render.
- `ArtworkAssetRepository` can produce `ARTWORK_DISK_HIT`.
- Some `nexio-artwork://decision/{decisionKey}` snapshot refs do not have matching durable decision records.
- `HomeCatalogSnapshotStore` treats those missing decisions as `MissingAuthoritative`, clears posters in memory, then `poster_provider_tag_mismatch` can reject the whole snapshot.
- Background/logo fallback still passes through legacy remote/Coil paths with weak diagnostics.

The implementation must not restore raw RPDB or Top-Posters URLs as a fallback.

## File Structure

- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRecordStore.kt`
  - Owns the reverse index contract for `assetKey` and `decisionKey` lookup.
- Create: `app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkAssetRecordStore.kt`
  - Persists explicit DTOs to `context.filesDir/artwork-asset-records-v1.json`, writes atomically, quarantines malformed records, and rebuilds latest-by-decision indexes on load.
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidator.kt`
  - Validates persisted `nexio-artwork://decision/...` and `nexio-artwork://asset/...` refs, classifies orphan refs, and reports recoverable asset records.
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
  - Uses typed decision lookup, writes asset records after materialization, recovers missing decisions from the reverse index, and emits orphan/recovery traces.
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetDiskCache.kt`
  - Adds file lookup helpers for persisted `ArtworkAssetRecord` and basic readable-image checks.
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
  - Stops rejecting whole snapshots for artwork provider-tag mismatch, validates artwork refs before write, derives or clears provider tags, preserves unknown refs, requests rehydration for orphan refs, and adds a real write barrier.
- Modify: `app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt`
  - Provides `ArtworkAssetRecordStore` and `ArtworkReferenceIntegrityValidator`.
- Modify: `app/src/main/java/com/nexio/tv/core/image/LegacyRemoteArtworkFetcher.kt`
  - Adds trace coverage for compatibility fallback fetches.
- Modify: `app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt`
  - Curates new integrity/recovery/fallback events for gated logcat.
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRecordStoreTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidatorTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/image/LegacyRemoteArtworkFetcherTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSinkTest.kt`

## Task 1: Add Durable Asset Record Reverse Index

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRecordStore.kt`
- Create: `app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkAssetRecordStore.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRecordStoreTest.kt`

- [ ] **Step 1: Write failing tests for latest asset lookup by decision**

Create `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRecordStoreTest.kt`:

```kotlin
package com.nexio.tv.core.artwork

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkAssetRecordStoreTest {
    private val temp = TemporaryFolder().also { it.create() }

    @Test
    fun `findLatestAssetForDecision returns newest valid record after restart`() {
        val file = temp.newFile("artwork-asset-records.json")
        val decisionKey = ArtworkDecisionKey("decision-a")
        val older = record("asset-old", decisionKey, fetchedAtMs = 100)
        val newer = record("asset-new", decisionKey, fetchedAtMs = 200)

        DurableArtworkAssetRecordStore(file, Gson()).put(older)
        DurableArtworkAssetRecordStore(file, Gson()).put(newer)

        val restarted = DurableArtworkAssetRecordStore(file, Gson())

        assertEquals(newer, restarted.findLatestAssetForDecision(decisionKey))
        assertEquals(newer, restarted.get(ArtworkAssetKey("asset-new")))
        assertEquals(older, restarted.get(ArtworkAssetKey("asset-old")))
    }

    @Test
    fun `record without decision key is stored by asset but excluded from decision lookup`() {
        val file = temp.newFile("artwork-asset-records.json")
        val record = record("asset-only", decisionKey = null, fetchedAtMs = 300)

        val store = DurableArtworkAssetRecordStore(file, Gson())
        store.put(record)

        assertEquals(record, store.get(ArtworkAssetKey("asset-only")))
        assertNull(store.findLatestAssetForDecision(ArtworkDecisionKey("decision-a")))
    }

    @Test
    fun `malformed asset record is quarantined without dropping valid records`() {
        val file = temp.newFile("artwork-asset-records.json")
        file.writeText(
            """
            {
              "schemaVersion": 1,
              "records": [
                {
                  "assetKey": "asset-valid",
                  "decisionKey": "decision-valid",
                  "provider": "PLACEHOLDER",
                  "imageType": "POSTER",
                  "imageLanguage": "en",
                  "relativePath": "artwork-assets/test/asset-valid.bin",
                  "mimeType": "image/jpeg",
                  "byteCount": 4,
                  "sourceHash": "source-valid",
                  "policyVersion": 1,
                  "fetchedAtMs": 100,
                  "expiresAtMs": 200,
                  "staleUntilMs": 300
                },
                {
                  "assetKey": "asset-bad",
                  "decisionKey": "decision-bad",
                  "provider": "PLACEHOLDER",
                  "imageType": "NOT_A_REAL_IMAGE_TYPE",
                  "imageLanguage": "en",
                  "relativePath": "artwork-assets/test/asset-bad.bin",
                  "mimeType": "image/jpeg",
                  "byteCount": 4,
                  "sourceHash": "source-bad",
                  "policyVersion": 1,
                  "fetchedAtMs": 100,
                  "expiresAtMs": 200,
                  "staleUntilMs": 300
                }
              ]
            }
            """.trimIndent()
        )

        val store = DurableArtworkAssetRecordStore(file, Gson())

        assertEquals(ArtworkAssetKey("asset-valid"), store.get(ArtworkAssetKey("asset-valid"))?.assetKey)
        assertNull(store.get(ArtworkAssetKey("asset-bad")))
        assertEquals(1, store.quarantinedRecordCount())
    }

    private fun record(
        assetKey: String,
        decisionKey: ArtworkDecisionKey?,
        fetchedAtMs: Long
    ): ArtworkAssetRecord =
        ArtworkAssetRecord(
            assetKey = ArtworkAssetKey(assetKey),
            decisionKey = decisionKey,
            provider = ArtworkProviderId.Placeholder,
            imageType = ArtworkType.POSTER,
            imageLanguage = "en",
            relativePath = "artwork-assets/test/$assetKey.bin",
            mimeType = "image/jpeg",
            byteCount = 4,
            sourceHash = "source-$assetKey",
            policyVersion = 1,
            fetchedAtMs = fetchedAtMs,
            expiresAtMs = fetchedAtMs + 1_000,
            staleUntilMs = fetchedAtMs + 2_000
        )
}
```

- [ ] **Step 2: Run the new test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRecordStoreTest
```

Expected: compile failure for missing `ArtworkAssetRecordStore` and `DurableArtworkAssetRecordStore`.

- [ ] **Step 3: Add the store contract**

Create `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRecordStore.kt`:

```kotlin
package com.nexio.tv.core.artwork

interface ArtworkAssetRecordStore {
    fun put(record: ArtworkAssetRecord)
    fun get(assetKey: ArtworkAssetKey): ArtworkAssetRecord?
    fun findLatestAssetForDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetRecord?
}
```

- [ ] **Step 4: Add the durable implementation**

Create `app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkAssetRecordStore.kt`:

```kotlin
package com.nexio.tv.core.artwork

import com.google.gson.Gson
import com.nexio.tv.core.integration.IntegrationProvider
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DurableArtworkAssetRecordStore(
    private val file: File,
    private val gson: Gson
) : ArtworkAssetRecordStore {
    private val recordsByAsset = linkedMapOf<ArtworkAssetKey, ArtworkAssetRecord>()
    private val latestByDecision = linkedMapOf<ArtworkDecisionKey, ArtworkAssetRecord>()
    private var quarantinedCount: Int = 0

    init {
        load()
    }

    @Synchronized
    override fun put(record: ArtworkAssetRecord) {
        val current = recordsByAsset[record.assetKey]
        if (current == record) return
        recordsByAsset[record.assetKey] = record
        record.decisionKey?.let { decisionKey ->
            val previous = latestByDecision[decisionKey]
            if (previous == null || record.fetchedAtMs >= previous.fetchedAtMs) {
                latestByDecision[decisionKey] = record
            }
        }
        write()
    }

    @Synchronized
    override fun get(assetKey: ArtworkAssetKey): ArtworkAssetRecord? =
        recordsByAsset[assetKey]

    @Synchronized
    override fun findLatestAssetForDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetRecord? =
        latestByDecision[decisionKey]

    @Synchronized
    fun quarantinedRecordCount(): Int = quarantinedCount

    private fun load() {
        if (!file.isFile) return
        val dto = runCatching {
            gson.fromJson(file.readText(), StoreDto::class.java)
        }.getOrNull() ?: return
        dto.records.forEach { recordDto ->
            val record = runCatching { recordDto.toDomain() }
                .onFailure { quarantinedCount += 1 }
                .getOrNull()
                ?: return@forEach
            recordsByAsset[record.assetKey] = record
            record.decisionKey?.let { decisionKey ->
                val previous = latestByDecision[decisionKey]
                if (previous == null || record.fetchedAtMs >= previous.fetchedAtMs) {
                    latestByDecision[decisionKey] = record
                }
            }
        }
    }

    private fun write() {
        file.parentFile?.mkdirs()
        val temp = File.createTempFile("${file.name}.", ".tmp", file.parentFile)
        try {
            temp.writeText(
                gson.toJson(
                    StoreDto(
                        schemaVersion = 1,
                        records = recordsByAsset.values.map { it.toDto() }
                    )
                )
            )
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private data class StoreDto(
        val schemaVersion: Int = 1,
        val records: List<ArtworkAssetRecordDto> = emptyList()
    )

    private data class ArtworkAssetRecordDto(
        val assetKey: String,
        val decisionKey: String?,
        val provider: String?,
        val imageType: String,
        val imageLanguage: String,
        val relativePath: String,
        val mimeType: String?,
        val byteCount: Long,
        val sourceHash: String,
        val policyVersion: Int,
        val fetchedAtMs: Long,
        val expiresAtMs: Long,
        val staleUntilMs: Long
    )

    private fun ArtworkAssetRecord.toDto(): ArtworkAssetRecordDto =
        ArtworkAssetRecordDto(
            assetKey = assetKey.value,
            decisionKey = decisionKey?.value,
            provider = provider?.key,
            imageType = imageType.name,
            imageLanguage = imageLanguage,
            relativePath = relativePath,
            mimeType = mimeType,
            byteCount = byteCount,
            sourceHash = sourceHash,
            policyVersion = policyVersion,
            fetchedAtMs = fetchedAtMs,
            expiresAtMs = expiresAtMs,
            staleUntilMs = staleUntilMs
        )

    private fun ArtworkAssetRecordDto.toDomain(): ArtworkAssetRecord =
        ArtworkAssetRecord(
            assetKey = ArtworkAssetKey(assetKey),
            decisionKey = decisionKey?.let { ArtworkDecisionKey(it) },
            provider = provider.toProviderDomain(),
            imageType = ArtworkType.valueOf(imageType),
            imageLanguage = imageLanguage.ifBlank { "en" },
            relativePath = relativePath,
            mimeType = mimeType,
            byteCount = byteCount,
            sourceHash = sourceHash,
            policyVersion = policyVersion,
            fetchedAtMs = fetchedAtMs,
            expiresAtMs = expiresAtMs,
            staleUntilMs = staleUntilMs
        )

    private fun String?.toProviderDomain(): ArtworkProviderId? =
        when (this) {
            null -> null
            ArtworkProviderId.RailPreview.key -> ArtworkProviderId.RailPreview
            ArtworkProviderId.AddonPreview.key -> ArtworkProviderId.AddonPreview
            ArtworkProviderId.Placeholder.key -> ArtworkProviderId.Placeholder
            else -> ArtworkProviderId.RuntimeProvider(IntegrationProvider.valueOf(this))
        }
}
```

- [ ] **Step 5: Run the store test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRecordStoreTest
```

Expected: PASS.

## Task 2: Add Disk Cache Helpers For Persisted Records

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetDiskCache.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRecordStoreTest.kt`

- [ ] **Step 1: Add failing tests for record-backed file validation**

Append to `ArtworkAssetRecordStoreTest.kt`:

```kotlin
@Test
fun `disk cache resolves readable file from persisted record`() {
    val diskCache = ArtworkAssetDiskCache(temp.root)
    val record = record("asset-file", ArtworkDecisionKey("decision-file"), fetchedAtMs = 400)
    val written = diskCache.write(record, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01))

    assertEquals(written.file, diskCache.getExistingFile(written.record))
    assertEquals(true, diskCache.hasReadableImageBytes(written.record))
}

@Test
fun `disk cache rejects missing record file`() {
    val diskCache = ArtworkAssetDiskCache(temp.root)
    val record = record("missing-file", ArtworkDecisionKey("decision-file"), fetchedAtMs = 500)

    assertNull(diskCache.getExistingFile(record))
    assertEquals(false, diskCache.hasReadableImageBytes(record))
}

@Test
fun `disk cache rejects persisted record path outside cache root`() {
    val diskCache = ArtworkAssetDiskCache(temp.root)
    val unsafe = record("unsafe-file", ArtworkDecisionKey("decision-file"), fetchedAtMs = 600)
        .copy(relativePath = "../outside-cache.bin")

    assertNull(diskCache.getExistingFile(unsafe))
    assertEquals(false, diskCache.hasReadableImageBytes(unsafe))
}
```

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRecordStoreTest
```

Expected: compile failure for missing `getExistingFile(ArtworkAssetRecord)` and `hasReadableImageBytes`.

- [ ] **Step 3: Add helpers to `ArtworkAssetDiskCache`**

Add below the existing `getExistingFile(assetKey: ArtworkAssetKey)` function:

```kotlin
fun getExistingFile(record: ArtworkAssetRecord): File? {
    val canonicalRoot = cacheRoot.canonicalFile
    val file = File(canonicalRoot, record.relativePath).canonicalFile
    if (!file.path.startsWith(canonicalRoot.path + File.separator)) return null
    return file.takeIf { it.isFile && it.canRead() }
}

fun hasReadableImageBytes(record: ArtworkAssetRecord): Boolean {
    val file = getExistingFile(record) ?: return false
    val header = runCatching {
        file.inputStream().use { input ->
            val buffer = ByteArray(12)
            val count = input.read(buffer)
            if (count <= 0) ByteArray(0) else buffer.copyOf(count)
        }
    }.getOrNull() ?: return false
    if (header.size < 4) return false
    val isJpeg = header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()
    val isPng = header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
        header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()
    val isWebp = header.size >= 12 &&
        header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
        header[2] == 0x46.toByte() && header[3] == 0x46.toByte() &&
        header[8] == 0x57.toByte() && header[9] == 0x45.toByte() &&
        header[10] == 0x42.toByte() && header[11] == 0x50.toByte()
    return isJpeg || isPng || isWebp
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRecordStoreTest
```

Expected: PASS.

## Task 3: Persist Asset Records During Materialization

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`

- [ ] **Step 1: Add an in-memory test store and failing persistence test**

In `ArtworkAssetRepositoryTest.kt`, add this test near the existing materialization tests:

```kotlin
@Test
fun `materialized decision writes asset record reverse index`() = runTest {
    val recordStore = RecordingArtworkAssetRecordStore()
    val decision = rpdbTemplateDecision()
    val repository = repository(
        runtime = LoadingIntegrationRuntime(),
        assetRecordStore = recordStore,
        byteLoader = ArtworkByteLoader { _, _ ->
            IntegrationLoadResult.Success(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01))
        }
    )

    val result = repository.getOrFetch(decision)

    assertNotNull(result)
    assertEquals(result!!.record, recordStore.get(result.assetKey))
    assertEquals(result.record, recordStore.findLatestAssetForDecision(decision.decisionKey))
}
```

Add this helper inside `ArtworkAssetRepositoryTest`:

```kotlin
private class RecordingArtworkAssetRecordStore : ArtworkAssetRecordStore {
    private val records = linkedMapOf<ArtworkAssetKey, ArtworkAssetRecord>()

    override fun put(record: ArtworkAssetRecord) {
        records[record.assetKey] = record
    }

    override fun get(assetKey: ArtworkAssetKey): ArtworkAssetRecord? =
        records[assetKey]

    override fun findLatestAssetForDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetRecord? =
        records.values
            .filter { it.decisionKey == decisionKey }
            .maxByOrNull { it.fetchedAtMs }
}
```

Change the `repository(...)` test factory signature to include:

```kotlin
assetRecordStore: ArtworkAssetRecordStore = RecordingArtworkAssetRecordStore(),
```

and pass it to `ArtworkAssetRepository`.

- [ ] **Step 2: Run the repository test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest.materialized_decision_writes_asset_record_reverse_index
```

Expected: compile failure because `ArtworkAssetRepository` does not accept `assetRecordStore`.

- [ ] **Step 3: Inject and write asset records**

Modify `ArtworkAssetResult` so reverse-index disk recovery does not need to read full image bytes into memory:

```kotlin
data class ArtworkAssetResult(
    val assetKey: ArtworkAssetKey,
    val localFile: File,
    val record: ArtworkAssetRecord,
    val runtimeResult: IntegrationFetchResult<ByteArray>?,
    val runtimeApiShapeId: String,
    val cacheDecision: String,
    val mimeType: String?,
    val networkExecuted: Boolean
)
```

Then modify the constructor in `ArtworkAssetRepository.kt`:

```kotlin
class ArtworkAssetRepository @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val diskCache: ArtworkAssetDiskCache,
    private val sourceMaterializer: ArtworkSourceMaterializer,
    private val byteLoader: ArtworkByteLoader,
    private val decisionCache: ArtworkDecisionCache,
    private val assetRecordStore: ArtworkAssetRecordStore,
    private val traceSink: RuntimeTraceSink = NoopRuntimeTraceSink
) {
```

After every successful `ArtworkAssetResult` creation, store the record. In the fresh write path:

```kotlin
val write = diskCache.write(record, bytes)
assetRecordStore.put(write.record)
return ArtworkAssetResult(
    assetKey = materialized.assetKey,
    localFile = write.file,
    record = write.record,
    runtimeResult = result,
    runtimeApiShapeId = apiShapeId,
    cacheDecision = result.cacheDecision(),
    mimeType = write.record.mimeType,
    networkExecuted = loaderInvoked
)
```

In `existingAssetResultOrNull`, before returning:

```kotlin
assetRecordStore.put(record)
return ArtworkAssetResult(
    assetKey = materialized.assetKey,
    localFile = file,
    record = record,
    runtimeResult = IntegrationFetchResult.Fresh(bytes),
    runtimeApiShapeId = materialized.apiShapeId,
    cacheDecision = cacheDecision,
    mimeType = record.mimeType,
    networkExecuted = networkExecuted
)
```

- [ ] **Step 4: Run the targeted repository test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest.materialized_decision_writes_asset_record_reverse_index
```

Expected: PASS.

## Task 4: Recover Missing Decisions From Existing Assets

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`

- [ ] **Step 1: Write failing recovery test**

Add to `ArtworkAssetRepositoryTest.kt`:

```kotlin
@Test
fun `missing authoritative decision recovers from indexed asset file`() = runTest {
    val decision = rpdbTemplateDecision()
    val diskCache = ArtworkAssetDiskCache(temp.root)
    val assetKey = ArtworkCacheKeys.assetKeyForProviderTemplate(decision.selectedCandidate.providerTemplate!!)
    val record = diskCache.recordFor(
        assetKey = assetKey,
        decision = decision,
        provider = decision.selectedCandidate.provider,
        sourceHash = "source-hash",
        mimeType = "image/jpeg",
        byteCount = 4,
        fetchedAtMs = 1_000
    )
    val write = diskCache.write(record, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01))
    val recordStore = RecordingArtworkAssetRecordStore()
    recordStore.put(write.record)
    val traceSink = RecordingArtworkTraceSink()
    val repository = repository(
        runtime = LoadingIntegrationRuntime(),
        cache = InMemoryArtworkDecisionCache(),
        diskCache = diskCache,
        assetRecordStore = recordStore,
        traceSink = traceSink
    )

    val result = repository.getOrFetchDecision(decision.decisionKey)

    assertNotNull(result)
    assertEquals(assetKey, result!!.assetKey)
    assertEquals("DECISION_MISSING_ASSET_RECOVERED", result.cacheDecision)
    assertEquals(
        listOf(
            "artwork.decision_lookup",
            "artwork.orphan_decision_ref_found",
            "artwork.orphan_decision_ref_asset_recovered"
        ),
        traceSink.events.map { it.eventType }
    )
}
```

- [ ] **Step 2: Run the recovery test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest.missing_authoritative_decision_recovers_from_indexed_asset_file
```

Expected: FAIL because `getOrFetchDecision` returns null.

- [ ] **Step 3: Use typed lookup and recover by reverse index**

Replace the start of `getOrFetchDecision` in `ArtworkAssetRepository.kt` with:

```kotlin
suspend fun getOrFetchDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetResult? {
    return when (val lookup = decisionCache.lookup(decisionKey, requiredContext = null)) {
        is ArtworkDecisionLookupResult.Found -> {
            traceDecisionLookup(decisionKey, found = true, lookupResult = "found")
            materializeFoundDecision(lookup.decision)
        }
        is ArtworkDecisionLookupResult.MissingAuthoritative -> {
            traceDecisionLookup(decisionKey, found = false, lookupResult = "missing_authoritative")
            recoverMissingDecisionFromAsset(decisionKey)
        }
        is ArtworkDecisionLookupResult.CacheNotAuthoritative -> {
            traceDecisionLookup(decisionKey, found = false, lookupResult = "cache_not_authoritative")
            traceArtwork(
                eventType = "artwork.orphan_decision_ref_rehydrate_requested",
                payload = mapOf(
                    "decisionKeyHash" to decisionKey.value.hashCode().toString(),
                    "reason" to "decision_cache_not_authoritative"
                )
            )
            null
        }
        is ArtworkDecisionLookupResult.LookupFailed -> {
            traceDecisionLookup(decisionKey, found = false, lookupResult = "lookup_failed")
            traceArtwork(
                eventType = "artwork.orphan_decision_ref_rehydrate_requested",
                payload = mapOf(
                    "decisionKeyHash" to decisionKey.value.hashCode().toString(),
                    "reason" to "lookup_failed",
                    "errorClass" to lookup.errorClass
                )
            )
            null
        }
    }
}
```

Add helper methods:

```kotlin
private suspend fun materializeFoundDecision(decision: ArtworkDecision): ArtworkAssetResult? {
    val result = getOrFetch(decision) ?: getOrFetchFallback(decision)
    traceArtwork(
        eventType = "artwork.asset_materialized",
        payload = mapOf(
            "decisionKey" to decision.decisionKey.value,
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

private fun recoverMissingDecisionFromAsset(decisionKey: ArtworkDecisionKey): ArtworkAssetResult? {
    traceArtwork(
        eventType = "artwork.orphan_decision_ref_found",
        payload = mapOf(
            "decisionKeyHash" to decisionKey.value.hashCode().toString(),
            "lookupResult" to "missing_authoritative"
        )
    )
    val record = assetRecordStore.findLatestAssetForDecision(decisionKey)
    val file = record?.let { diskCache.getExistingFile(it) }
    if (record != null && file != null && diskCache.hasReadableImageBytes(record)) {
        traceArtwork(
            eventType = "artwork.orphan_decision_ref_asset_recovered",
            payload = mapOf(
                "decisionKeyHash" to decisionKey.value.hashCode().toString(),
                "assetKeyHash" to record.assetKey.value.hashCode().toString(),
                "provider" to record.provider?.key,
                "imageType" to record.imageType.name,
                "fileExists" to true,
                "byteValid" to true,
                "source" to "asset_reverse_index"
            )
        )
        return ArtworkAssetResult(
            assetKey = record.assetKey,
            localFile = file,
            record = record,
            runtimeResult = null,
            runtimeApiShapeId = "ARTWORK_REVERSE_INDEX",
            cacheDecision = "DECISION_MISSING_ASSET_RECOVERED",
            mimeType = record.mimeType,
            networkExecuted = false
        )
    }
    traceArtwork(
        eventType = "artwork.orphan_decision_ref_rehydrate_requested",
        payload = mapOf(
            "decisionKeyHash" to decisionKey.value.hashCode().toString(),
            "reason" to "missing_decision_no_asset",
            "assetRecordFound" to (record != null),
            "fileExists" to (file != null)
        )
    )
    return null
}

private fun traceDecisionLookup(
    decisionKey: ArtworkDecisionKey,
    found: Boolean,
    lookupResult: String
) {
    traceArtwork(
        eventType = "artwork.decision_lookup",
        payload = mapOf(
            "decisionKey" to decisionKey.value,
            "found" to found,
            "lookupResult" to lookupResult
        )
    )
}
```

- [ ] **Step 4: Update the existing missing decision trace test**

In `ArtworkAssetRepositoryTest`, update `getOrFetchDecision returns null and traces missing decision` to expect:

```kotlin
assertEquals(
    listOf(
        "artwork.decision_lookup",
        "artwork.orphan_decision_ref_found",
        "artwork.orphan_decision_ref_rehydrate_requested"
    ),
    traceSink.events.map { it.eventType }
)
```

- [ ] **Step 5: Run repository tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest
```

Expected: PASS.

## Task 5: Add Artwork Reference Integrity Validator

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidator.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidatorTest.kt`

- [ ] **Step 1: Write validator tests**

Create `app/src/test/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidatorTest.kt`:

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.RecordingTraceSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkReferenceIntegrityValidatorTest {
    private val temp = TemporaryFolder().also { it.create() }

    @Test
    fun `decision ref is valid when decision exists`() {
        val cache = InMemoryArtworkDecisionCache()
        val decision = sampleDecision(ArtworkDecisionKey("decision-valid"))
        cache.put(decision)
        val validator = validator(cache = cache)

        val result = validator.validate("nexio-artwork://decision/decision-valid")

        assertEquals(ArtworkReferenceIntegrityResult.ValidDecision(decision.decisionKey), result)
    }

    @Test
    fun `missing decision recovers when indexed asset file has image bytes`() {
        val decision = sampleDecision(ArtworkDecisionKey("decision-orphan"))
        val diskCache = ArtworkAssetDiskCache(temp.root)
        val recordStore = RecordingAssetRecordStore()
        val record = diskCache.recordFor(
            assetKey = ArtworkAssetKey("asset-recovered"),
            decision = decision,
            provider = ArtworkProviderId.Placeholder,
            sourceHash = "source",
            mimeType = "image/jpeg",
            byteCount = 4,
            fetchedAtMs = 1_000
        )
        recordStore.put(diskCache.write(record, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01)).record)
        val validator = validator(recordStore = recordStore, diskCache = diskCache)

        val result = validator.validate("nexio-artwork://decision/decision-orphan")

        assertEquals(
            ArtworkReferenceIntegrityResult.RecoverableAssetForDecision(
                decisionKey = decision.decisionKey,
                assetKey = ArtworkAssetKey("asset-recovered")
            ),
            result
        )
    }

    @Test
    fun `missing decision without asset is orphaned and traceable`() {
        val traceSink = RecordingTraceSink()
        val validator = validator(traceSink = traceSink)

        val result = validator.validate("nexio-artwork://decision/decision-missing")

        assertTrue(result is ArtworkReferenceIntegrityResult.OrphanedDecisionRef)
        assertEquals("artwork.orphan_decision_ref_found", traceSink.events.single().eventType)
    }

    @Test
    fun `non authoritative decision cache returns unknown not orphaned`() {
        val validator = validator(cache = NonAuthoritativeDecisionCache())

        val result = validator.validate("nexio-artwork://decision/decision-unknown")

        assertEquals(
            ArtworkReferenceIntegrityResult.UnknownDecisionRef(
                decisionKey = ArtworkDecisionKey("decision-unknown"),
                reason = "decision_cache_not_authoritative"
            ),
            result
        )
    }

    private fun validator(
        cache: ArtworkDecisionCache = InMemoryArtworkDecisionCache(),
        recordStore: ArtworkAssetRecordStore = RecordingAssetRecordStore(),
        diskCache: ArtworkAssetDiskCache = ArtworkAssetDiskCache(temp.root),
        traceSink: RecordingTraceSink = RecordingTraceSink()
    ): ArtworkReferenceIntegrityValidator =
        DefaultArtworkReferenceIntegrityValidator(
            decisionCache = cache,
            assetRecordStore = recordStore,
            diskCache = diskCache,
            traceSink = traceSink
        )

    private fun sampleDecision(key: ArtworkDecisionKey): ArtworkDecision =
        ArtworkDecision(
            decisionKey = key,
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt123"),
            canonicalContentId = "imdb:tt123",
            imageType = ArtworkType.POSTER,
            selectedCandidate = PersistedArtworkCandidate(
                provider = ArtworkProviderId.Placeholder,
                sourceRole = ArtworkSourceRole.PLACEHOLDER,
                sourceHash = null,
                redactedSourceForTrace = null,
                providerTemplate = null,
                priority = 1
            ),
            rejectedCandidates = emptyList(),
            policyVersion = 1,
            settingsHash = "settings",
            credentialHash = "credential",
            createdAtMs = 100,
            expiresAtMs = 200,
            staleUntilMs = 300
        )

    private class RecordingAssetRecordStore : ArtworkAssetRecordStore {
        private val records = linkedMapOf<ArtworkAssetKey, ArtworkAssetRecord>()
        override fun put(record: ArtworkAssetRecord) { records[record.assetKey] = record }
        override fun get(assetKey: ArtworkAssetKey): ArtworkAssetRecord? = records[assetKey]
        override fun findLatestAssetForDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetRecord? =
            records.values.filter { it.decisionKey == decisionKey }.maxByOrNull { it.fetchedAtMs }
    }

    private class NonAuthoritativeDecisionCache : ArtworkDecisionCache {
        override fun lookup(
            key: ArtworkDecisionKey,
            requiredContext: ArtworkDecisionAuthorityContext?
        ): ArtworkDecisionLookupResult =
            ArtworkDecisionLookupResult.CacheNotAuthoritative(
                decisionKey = key,
                loadState = ArtworkDecisionStoreLoadState.LoadedPartialNonAuthoritative(
                    decisionCount = 0,
                    droppedDecisionCount = 1,
                    quarantinedDecisionCount = 0
                ),
                reason = "partial_load",
                errorClass = null
            )

        override fun get(key: ArtworkDecisionKey): ArtworkDecision? = null
        override fun put(decision: ArtworkDecision) = Unit
        override fun remove(key: ArtworkDecisionKey) = Unit
        override fun linkPreviewToCanonical(previewKey: ArtworkDecisionKey, canonicalKey: ArtworkDecisionKey) = Unit
        override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? = null
        override fun invalidateBySettingsHash(settingsHash: String) = Unit
        override fun invalidateByCredentialHash(credentialHash: String) = Unit
        override fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>) = Unit
        override fun invalidatePremiumArtworkPolicy() = Unit
    }
}
```

- [ ] **Step 2: Run validator tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkReferenceIntegrityValidatorTest
```

Expected: compile failure for missing validator/result types.

- [ ] **Step 3: Add validator implementation**

Create `app/src/main/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidator.kt`:

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

interface ArtworkReferenceIntegrityValidator {
    fun validate(ref: String?): ArtworkReferenceIntegrityResult
}

object NoopArtworkReferenceIntegrityValidator : ArtworkReferenceIntegrityValidator {
    override fun validate(ref: String?): ArtworkReferenceIntegrityResult =
        when {
            ref.isNullOrBlank() -> ArtworkReferenceIntegrityResult.Empty
            ref.startsWith("nexio-artwork://asset/") ->
                ArtworkReferenceIntegrityResult.ValidAsset(
                    ArtworkAssetKey(ref.removePrefix("nexio-artwork://asset/"))
                )
            ref.startsWith("nexio-artwork://decision/") ->
                ArtworkReferenceIntegrityResult.ValidDecision(
                    ArtworkDecisionKey(ref.removePrefix("nexio-artwork://decision/"))
                )
            else -> ArtworkReferenceIntegrityResult.Invalid("unsupported_artwork_ref")
        }
}

sealed interface ArtworkReferenceIntegrityResult {
    data object Empty : ArtworkReferenceIntegrityResult
    data class ValidDecision(val decisionKey: ArtworkDecisionKey) : ArtworkReferenceIntegrityResult
    data class ValidAsset(val assetKey: ArtworkAssetKey) : ArtworkReferenceIntegrityResult
    data class RecoverableAssetForDecision(
        val decisionKey: ArtworkDecisionKey,
        val assetKey: ArtworkAssetKey
    ) : ArtworkReferenceIntegrityResult
    data class OrphanedDecisionRef(
        val decisionKey: ArtworkDecisionKey,
        val reason: String
    ) : ArtworkReferenceIntegrityResult
    data class UnknownDecisionRef(
        val decisionKey: ArtworkDecisionKey,
        val reason: String
    ) : ArtworkReferenceIntegrityResult
    data class Invalid(val reason: String) : ArtworkReferenceIntegrityResult
}

@Singleton
class DefaultArtworkReferenceIntegrityValidator @Inject constructor(
    private val decisionCache: ArtworkDecisionCache,
    private val assetRecordStore: ArtworkAssetRecordStore,
    private val diskCache: ArtworkAssetDiskCache,
    private val traceSink: RuntimeTraceSink = NoopRuntimeTraceSink
) : ArtworkReferenceIntegrityValidator {
    private val sequence = AtomicLong(0L)

    override fun validate(ref: String?): ArtworkReferenceIntegrityResult {
        if (ref.isNullOrBlank()) return ArtworkReferenceIntegrityResult.Empty
        parseAsset(ref)?.let { assetKey ->
            val record = assetRecordStore.get(assetKey)
            val valid = record != null && diskCache.hasReadableImageBytes(record)
            trace("artwork.ref_integrity_checked", mapOf("refKind" to "asset", "valid" to valid))
            return if (valid) {
                ArtworkReferenceIntegrityResult.ValidAsset(assetKey)
            } else {
                ArtworkReferenceIntegrityResult.Invalid("missing_or_unreadable_asset")
            }
        }
        parseDecision(ref)?.let { decisionKey ->
            return validateDecision(decisionKey)
        }
        return ArtworkReferenceIntegrityResult.Invalid("unsupported_artwork_ref")
    }

    private fun validateDecision(decisionKey: ArtworkDecisionKey): ArtworkReferenceIntegrityResult {
        return when (val lookup = decisionCache.lookup(decisionKey, requiredContext = null)) {
            is ArtworkDecisionLookupResult.Found -> {
                trace("artwork.ref_integrity_checked", mapOf("refKind" to "decision", "valid" to true))
                ArtworkReferenceIntegrityResult.ValidDecision(decisionKey)
            }
            is ArtworkDecisionLookupResult.MissingAuthoritative -> {
                val recovered = assetRecordStore.findLatestAssetForDecision(decisionKey)
                    ?.takeIf { diskCache.hasReadableImageBytes(it) }
                if (recovered != null) {
                    trace(
                        "artwork.orphan_decision_ref_asset_recovered",
                        mapOf(
                            "decisionKeyHash" to decisionKey.value.hashCode().toString(),
                            "assetKeyHash" to recovered.assetKey.value.hashCode().toString(),
                            "source" to "asset_reverse_index"
                        )
                    )
                    ArtworkReferenceIntegrityResult.RecoverableAssetForDecision(decisionKey, recovered.assetKey)
                } else {
                    trace(
                        "artwork.orphan_decision_ref_found",
                        mapOf(
                            "decisionKeyHash" to decisionKey.value.hashCode().toString(),
                            "reason" to "missing_authoritative_no_asset"
                        )
                    )
                    ArtworkReferenceIntegrityResult.OrphanedDecisionRef(decisionKey, "missing_authoritative_no_asset")
                }
            }
            is ArtworkDecisionLookupResult.CacheNotAuthoritative -> {
                ArtworkReferenceIntegrityResult.UnknownDecisionRef(decisionKey, "decision_cache_not_authoritative")
            }
            is ArtworkDecisionLookupResult.LookupFailed -> {
                ArtworkReferenceIntegrityResult.UnknownDecisionRef(decisionKey, "lookup_failed")
            }
        }
    }

    private fun parseDecision(ref: String): ArtworkDecisionKey? =
        ref.removePrefixOrNull(DECISION_URI_PREFIX)?.takeIf { it.isNotBlank() }?.let { ArtworkDecisionKey(it) }

    private fun parseAsset(ref: String): ArtworkAssetKey? =
        ref.removePrefixOrNull(ASSET_URI_PREFIX)?.takeIf { it.isNotBlank() }?.let { ArtworkAssetKey(it) }

    private fun String.removePrefixOrNull(prefix: String): String? =
        takeIf { startsWith(prefix) }?.removePrefix(prefix)

    private fun trace(eventType: String, payload: Map<String, Any?>) {
        traceSink.emit(
            TraceEventEnvelope(
                traceSessionId = traceSink.activeTraceSessionId() ?: "logcat-only",
                sequence = sequence.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = eventType,
                payload = payload
            )
        )
    }

    private companion object {
        const val ASSET_URI_PREFIX = "nexio-artwork://asset/"
        const val DECISION_URI_PREFIX = "nexio-artwork://decision/"
    }
}
```

- [ ] **Step 4: Run validator tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkReferenceIntegrityValidatorTest
```

Expected: PASS.

## Task 6: Make Snapshot Read Non-Fatal For Artwork Mismatches

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt`

- [ ] **Step 1: Replace the whole-snapshot rejection test with non-fatal behavior**

In `HomeCatalogSnapshotStoreTest.kt`, replace `read rejects active poster provider snapshots with mismatched poster tags` with:

```kotlin
@Test
fun `read does not reject active poster provider snapshot with mismatched poster tags`() {
    val snapshotPrefs = InMemorySharedPreferences()
    val localePrefs = localePrefs("en")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 0
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val traceSink = RecordingTraceSink()
    val store = HomeCatalogSnapshotStore(
        context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
        metadataDiskCacheStore = metadataStore,
        posterRatingsUrlResolver = posterResolver,
        traceSink = traceSink
    )
    val row = sampleRow("addon", "movies", posterProviderTag = "top_posters")
    val snapshot = HomeCatalogSnapshotStore.Snapshot(
        catalogRows = listOf(row),
        fullCatalogRows = listOf(row),
        heroItems = row.items,
        orderedGroupKeys = listOf("addon_movie_movies")
    )

    store.write(snapshot, "RPDB:12345")

    val restored = store.read("RPDB:12345")

    assertEquals("Sample", restored?.catalogRows?.single()?.items?.single()?.name)
    assertTrue(traceSink.events.any { it.eventType == "home.snapshot_provider_tag_mismatch_ignored" })
    assertTrue(traceSink.events.any { event ->
        event.eventType == "home.snapshot_artwork_rehydrate_requested" &&
            (event.payload as Map<*, *>)["reason"] == "poster_provider_tag_mismatch"
    })
}
```

Replace `provider tag mismatch rejects found decision ref` with:

```kotlin
@Test
fun `provider tag mismatch on found decision ref does not reject whole snapshot`() {
    val snapshotPrefs = InMemorySharedPreferences()
    val localePrefs = localePrefs("en")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 0
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val cache = InMemoryArtworkDecisionCache()
    cache.put(sampleArtworkDecision(ArtworkDecisionKey("found-mismatched-provider-decision")))
    val store = HomeCatalogSnapshotStore(
        context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
        metadataDiskCacheStore = metadataStore,
        posterRatingsUrlResolver = posterResolver,
        artworkDecisionCache = cache
    )
    val snapshot = sampleSnapshotWithPoster(
        poster = "nexio-artwork://decision/found-mismatched-provider-decision",
        posterProviderTag = "top_posters"
    )

    store.write(snapshot, "RPDB:12345")

    assertEquals("Sample", store.read("RPDB:12345")?.catalogRows?.single()?.items?.single()?.name)
}
```

- [ ] **Step 2: Run snapshot tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest
```

Expected: failures where `read(...)` returns null because `poster_provider_tag_mismatch` still rejects the snapshot.

- [ ] **Step 3: Change read policy to degrade per item**

In `HomeCatalogSnapshotStore.read(...)`, replace the restored/null decision:

```kotlin
val restored = sanitized.takeIf {
    it.hasValidPosterProviderTags(
        requiredPosterProviderTag,
        providerTagMismatchExemptPosterRefs = sanitizeResult.providerTagMismatchExemptPosterRefs
    )
}
```

with:

```kotlin
val providerTagMismatchCount = sanitized.countPosterProviderTagMismatches(
    requiredPosterProviderTag,
    providerTagMismatchExemptPosterRefs = sanitizeResult.providerTagMismatchExemptPosterRefs
)
if (providerTagMismatchCount > 0) {
    emitSnapshotTrace(
        eventType = "home.snapshot_provider_tag_mismatch_ignored",
        payload = mapOf(
            "mismatchCount" to providerTagMismatchCount,
            "reason" to "artwork_mismatch_is_item_scoped",
            "snapshotReadContinues" to true
        )
    )
    emitSnapshotTrace(
        eventType = "home.snapshot_artwork_rehydrate_requested",
        payload = mapOf(
            "reason" to "poster_provider_tag_mismatch",
            "requestCount" to providerTagMismatchCount,
            "snapshotPhase" to "read"
        )
    )
}
val restored = sanitized
```

Keep full-snapshot rejection only for decode failure, language epoch mismatch, and structural incompatibility.

Add a helper next to `hasValidPosterProviderTags`:

```kotlin
private fun Snapshot.countPosterProviderTagMismatches(
    requiredPosterProviderTag: String?,
    providerTagMismatchExemptPosterRefs: Set<String>
): Int {
    if (requiredPosterProviderTag == null) return 0
    return allItems().count { item ->
        val tag = item.posterProviderTag
        val poster = item.poster
        tag != null &&
            tag != requiredPosterProviderTag &&
            poster !in providerTagMismatchExemptPosterRefs
    }
}
```

Add this concrete private helper near the provider-tag functions:

```kotlin
private fun Snapshot.allItems(): Sequence<MetaPreview> =
    sequence {
        catalogRows.forEach { row -> yieldAll(row.items) }
        fullCatalogRows.forEach { row -> yieldAll(row.items) }
        yieldAll(heroItems)
    }
```

- [ ] **Step 4: Run snapshot tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest
```

Expected: PASS or failures only in tests whose expected assertions still refer to full-snapshot rejection.

## Task 7: Treat Orphaned Authoritative Misses As Recoverable Snapshot Artwork

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt`

Important semantic rule for this task:

```text
MissingAuthoritative means the cache is authoritative for lookup, not that read-time snapshot cleanup may destructively clear the ref.
Read preserves decision refs and requests rehydration.
Only the write barrier may remove or replace an orphaned decision ref, and only after validating fallback/recovery state.
```

- [ ] **Step 1: Update missing-decision test expectation**

Replace `authoritative missing clears decision refs and tag` with:

```kotlin
@Test
fun `authoritative missing decision preserves ref in memory and requests hydration`() {
    val snapshotPrefs = InMemorySharedPreferences()
    val localePrefs = localePrefs("en")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 0
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val cache = InMemoryArtworkDecisionCache()
    val decisionKey = ArtworkDecisionKey("missing-decision")
    cache.put(sampleArtworkDecision(decisionKey))
    val traceSink = RecordingTraceSink()
    val store = HomeCatalogSnapshotStore(
        context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
        metadataDiskCacheStore = metadataStore,
        posterRatingsUrlResolver = posterResolver,
        artworkDecisionCache = cache,
        traceSink = traceSink
    )
    val snapshot = sampleSnapshotWithPoster(
        poster = "nexio-artwork://decision/missing-decision",
        posterProviderTag = "rpdb"
    )

    store.write(snapshot, "RPDB:12345")
    cache.remove(decisionKey)

    val restored = store.read("RPDB:12345")

    assertPosterFieldsPreserved(restored, "nexio-artwork://decision/missing-decision", "rpdb")
    assertTrue(traceSink.events.any { event ->
        event.eventType == "home.snapshot_artwork_rehydrate_requested" &&
            (event.payload as Map<*, *>)["reason"] == "missing_decision_authoritative"
    })
}
```

- [ ] **Step 2: Run the test and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest.authoritative_missing_decision_preserves_ref_in_memory_and_requests_hydration
```

Expected: FAIL because current sanitizer clears missing authoritative decision refs.

- [ ] **Step 3: Change `lookupDecisionProof` clearing policy**

In `HomeCatalogSnapshotStore.lookupDecisionProof`, change:

```kotlin
clearMissingDecisionRef = lookupResult is ArtworkDecisionLookupResult.MissingAuthoritative
```

to:

```kotlin
clearMissingDecisionRef = false
```

and add `MissingAuthoritative` to the rehydration request branch:

```kotlin
val shouldRequestRehydrate = lookupResult is ArtworkDecisionLookupResult.MissingAuthoritative ||
    lookupResult is ArtworkDecisionLookupResult.CacheNotAuthoritative ||
    lookupResult is ArtworkDecisionLookupResult.LookupFailed
```

Map `MissingAuthoritative` to:

```kotlin
"missing_decision_authoritative"
```

Keep raw premium URL and legacy integration URI sanitization destructive; this task changes only decision-backed refs.

- [ ] **Step 4: Update sanitizer trace wording**

In `SnapshotSanitizeTraceState.emitIfNeeded()`, split action fields:

```kotlin
"action" to when {
    missingDecisionCount > 0 && rawPremiumCount == 0 && legacyIntegrationCount == 0 -> "preserve_ref_request_rehydrate"
    else -> "clear_unsafe_artwork_ref"
},
"destructive" to (rawPremiumCount > 0 || legacyIntegrationCount > 0),
"writeBackAllowed" to false,
"posterProviderTagAction" to when {
    rawPremiumCount > 0 || legacyIntegrationCount > 0 -> "clear"
    else -> "preserve"
}
```

- [ ] **Step 5: Run snapshot tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest
```

Expected: PASS after updating old assertions that expected missing decisions to be destructive.

## Task 8: Add Real Snapshot Artwork Write Barrier

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt`

- [ ] **Step 1: Add failing write-barrier tests**

Add to `HomeCatalogSnapshotStoreTest.kt`:

```kotlin
@Test
fun `write barrier never persists poster null with provider tag`() {
    val snapshotPrefs = InMemorySharedPreferences()
    val localePrefs = localePrefs("en")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 0
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val store = HomeCatalogSnapshotStore(
        context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
        metadataDiskCacheStore = metadataStore,
        posterRatingsUrlResolver = posterResolver
    )
    val snapshot = sampleSnapshotWithPoster(
        poster = null,
        posterProviderTag = "rpdb"
    )

    store.write(snapshot, "RPDB:12345")

    val raw = persistedSnapshotJson(snapshotPrefs)
    assertFalse(raw.contains("\"posterProviderTag\":\"rpdb\""))
}

@Test
fun `write barrier detects unbacked decision ref and does not persist it as premium poster`() {
    val snapshotPrefs = InMemorySharedPreferences()
    val localePrefs = localePrefs("en")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 0
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val traceSink = RecordingTraceSink()
    val store = HomeCatalogSnapshotStore(
        context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
        metadataDiskCacheStore = metadataStore,
        posterRatingsUrlResolver = posterResolver,
        artworkReferenceIntegrityValidator = FakeArtworkReferenceIntegrityValidator(
            "nexio-artwork://decision/unbacked-write-decision" to
                ArtworkReferenceIntegrityResult.OrphanedDecisionRef(
                    decisionKey = ArtworkDecisionKey("unbacked-write-decision"),
                    reason = "missing_authoritative_no_asset"
                )
        ),
        traceSink = traceSink
    )

    store.write(
        sampleSnapshotWithPoster(
            poster = "nexio-artwork://decision/unbacked-write-decision",
            posterProviderTag = "rpdb"
        ),
        "RPDB:12345"
    )

    val raw = persistedSnapshotJson(snapshotPrefs)
    assertFalse(raw.contains("nexio-artwork://decision/unbacked-write-decision"))
    assertFalse(raw.contains("\"posterProviderTag\":\"rpdb\""))
    assertTrue(traceSink.events.any { event ->
        event.eventType == "home.snapshot_artwork_rehydrate_requested" &&
            (event.payload as Map<*, *>)["reason"] == "orphaned_decision_ref_write_barrier"
    })
}

@Test
fun `write barrier promotes recoverable decision ref to asset ref`() {
    val snapshotPrefs = InMemorySharedPreferences()
    val localePrefs = localePrefs("en")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 0
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val store = HomeCatalogSnapshotStore(
        context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
        metadataDiskCacheStore = metadataStore,
        posterRatingsUrlResolver = posterResolver,
        artworkReferenceIntegrityValidator = FakeArtworkReferenceIntegrityValidator(
            "nexio-artwork://decision/recoverable-write-decision" to
                ArtworkReferenceIntegrityResult.RecoverableAssetForDecision(
                    decisionKey = ArtworkDecisionKey("recoverable-write-decision"),
                    assetKey = ArtworkAssetKey("asset-for-recoverable-write-decision")
                )
        )
    )

    store.write(
        sampleSnapshotWithPoster(
            poster = "nexio-artwork://decision/recoverable-write-decision",
            posterProviderTag = "rpdb"
        ),
        "RPDB:12345"
    )

    val raw = persistedSnapshotJson(snapshotPrefs)
    assertFalse(raw.contains("nexio-artwork://decision/recoverable-write-decision"))
    assertTrue(raw.contains("nexio-artwork://asset/asset-for-recoverable-write-decision"))
    assertTrue(raw.contains("\"posterProviderTag\":\"rpdb\""))
}

@Test
fun `write barrier clears premium provider tag for fallback poster`() {
    val snapshotPrefs = InMemorySharedPreferences()
    val localePrefs = localePrefs("en")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 0
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val store = HomeCatalogSnapshotStore(
        context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
        metadataDiskCacheStore = metadataStore,
        posterRatingsUrlResolver = posterResolver
    )

    store.write(
        sampleSnapshotWithPoster(
            poster = "https://image.tmdb.org/t/p/w500/fallback.jpg",
            posterProviderTag = "rpdb"
        ),
        "RPDB:12345"
    )

    val raw = persistedSnapshotJson(snapshotPrefs)
    assertTrue(raw.contains("https://image.tmdb.org/t/p/w500/fallback.jpg"))
    assertFalse(raw.contains("\"posterProviderTag\":\"rpdb\""))
}
```

Add this fake helper near the other test helpers:

```kotlin
private class FakeArtworkReferenceIntegrityValidator(
    private vararg val results: Pair<String, ArtworkReferenceIntegrityResult>
) : ArtworkReferenceIntegrityValidator {
    private val resultMap = results.toMap()

    override fun validate(ref: String?): ArtworkReferenceIntegrityResult =
        resultMap[ref] ?: when {
            ref.isNullOrBlank() -> ArtworkReferenceIntegrityResult.Empty
            ref.startsWith("nexio-artwork://asset/") ->
                ArtworkReferenceIntegrityResult.ValidAsset(
                    ArtworkAssetKey(ref.removePrefix("nexio-artwork://asset/"))
                )
            ref.startsWith("nexio-artwork://decision/") ->
                ArtworkReferenceIntegrityResult.ValidDecision(
                    ArtworkDecisionKey(ref.removePrefix("nexio-artwork://decision/"))
                )
            else -> ArtworkReferenceIntegrityResult.Invalid("unsupported_artwork_ref")
        }
}
```

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest
```

Expected: compile failure for missing `artworkReferenceIntegrityValidator` constructor parameter and then failures where unbacked decision refs are still persisted.

- [ ] **Step 3: Inject the integrity validator into the snapshot store**

Add imports to `HomeCatalogSnapshotStore.kt`:

```kotlin
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkReferenceIntegrityResult
import com.nexio.tv.core.artwork.ArtworkReferenceIntegrityValidator
import com.nexio.tv.core.artwork.NoopArtworkReferenceIntegrityValidator
```

Add the dependency to the private primary constructor:

```kotlin
private val artworkReferenceIntegrityValidator: ArtworkReferenceIntegrityValidator,
```

Add it to the injected constructor parameters:

```kotlin
artworkReferenceIntegrityValidator: ArtworkReferenceIntegrityValidator,
```

and pass it through:

```kotlin
artworkReferenceIntegrityValidator = artworkReferenceIntegrityValidator,
```

Add it to the test constructor with a safe default:

```kotlin
artworkReferenceIntegrityValidator: ArtworkReferenceIntegrityValidator = NoopArtworkReferenceIntegrityValidator,
```

and pass it to the primary constructor. `NoopArtworkReferenceIntegrityValidator` is introduced in Task 5 and treats non-`nexio-artwork` refs as invalid for premium tagging and existing `nexio-artwork` refs as structurally valid for tests that are not exercising the write barrier.

- [ ] **Step 4: Add write barrier validation**

In `HomeCatalogSnapshotStore.write(...)`, replace:

```kotlin
val sanitizedSnapshot = snapshot.sanitize()
```

with:

```kotlin
val sanitizedSnapshot = snapshot
    .sanitize()
    .repairArtworkWriteInvariants(requiredPosterProviderTag(posterProviderToken))
```

Add:

```kotlin
private fun Snapshot.repairArtworkWriteInvariants(requiredProviderTag: String?): Snapshot {
    var repairedCount = 0
    var rehydrateCount = 0
    val repaired = mapItems { item ->
        val posterRepair = repairPosterForWrite(item.poster, item.posterProviderTag, requiredProviderTag)
        val backgroundRepair = repairStandaloneArtworkRefForWrite(item.background, "background")
        val logoRepair = repairStandaloneArtworkRefForWrite(item.logo, "logo")
        listOf(posterRepair, backgroundRepair, logoRepair).forEach { repair ->
            if (repair.changed) repairedCount += 1
            if (repair.requestRehydrate) rehydrateCount += 1
        }
        listOf(posterRepair, backgroundRepair, logoRepair).filter { it.requestRehydrate }.forEach { repair ->
            traceSnapshot(
                eventType = "home.snapshot_artwork_rehydrate_requested",
                payload = mapOf(
                    "reason" to repair.rehydrateReason,
                    "artworkField" to repair.fieldName,
                    "artworkKind" to posterKind(repair.originalRef.orEmpty()),
                    "providerTag" to item.posterProviderTag.takeIf { repair.fieldName == "poster" },
                    "snapshotPhase" to "write_barrier"
                )
            )
        }
        item.copy(
            poster = posterRepair.ref,
            posterProviderTag = posterRepair.posterProviderTag,
            background = backgroundRepair.ref,
            logo = logoRepair.ref
        )
    }
    if (repairedCount > 0) {
        traceSnapshot(
            eventType = "home.snapshot_write_barrier_repaired",
            payload = mapOf(
                "repairedCount" to repairedCount,
                "rehydrateRequestCount" to rehydrateCount
            )
        )
    }
    return repaired
}

private fun repairPosterForWrite(
    poster: String?,
    posterProviderTag: String?,
    requiredProviderTag: String?
): ArtworkWriteRepair {
    val ref = poster?.trim()?.takeIf { it.isNotBlank() }
    if (ref == null) {
        return ArtworkWriteRepair(
            fieldName = "poster",
            originalRef = poster,
            ref = null,
            posterProviderTag = null,
            changed = posterProviderTag != null,
            requestRehydrate = false,
            rehydrateReason = null
        )
    }
    if (!isSafeArtworkRef(ref)) {
        return ArtworkWriteRepair(
            fieldName = "poster",
            originalRef = poster,
            ref = ref,
            posterProviderTag = null,
            changed = posterProviderTag != null,
            requestRehydrate = false,
            rehydrateReason = null
        )
    }
    return when (val integrity = artworkReferenceIntegrityValidator.validate(ref)) {
        is ArtworkReferenceIntegrityResult.ValidDecision -> {
            val derivedTag = posterProviderTag.takeIf { it == requiredProviderTag }
            ArtworkWriteRepair(
                fieldName = "poster",
                originalRef = poster,
                ref = ref,
                posterProviderTag = derivedTag,
                changed = derivedTag != posterProviderTag,
                requestRehydrate = false
            )
        }
        is ArtworkReferenceIntegrityResult.ValidAsset -> {
            val derivedTag = posterProviderTag.takeIf { it == requiredProviderTag }
            ArtworkWriteRepair(
                fieldName = "poster",
                originalRef = poster,
                ref = ref,
                posterProviderTag = derivedTag,
                changed = derivedTag != posterProviderTag,
                requestRehydrate = false
            )
        }
        is ArtworkReferenceIntegrityResult.RecoverableAssetForDecision -> {
            val assetRef = "nexio-artwork://asset/${integrity.assetKey.value}"
            ArtworkWriteRepair(
                fieldName = "poster",
                originalRef = poster,
                ref = assetRef,
                posterProviderTag = posterProviderTag.takeIf { it == requiredProviderTag },
                changed = assetRef != ref,
                requestRehydrate = false,
                rehydrateReason = null
            )
        }
        is ArtworkReferenceIntegrityResult.OrphanedDecisionRef -> {
            ArtworkWriteRepair(
                fieldName = "poster",
                originalRef = poster,
                ref = null,
                posterProviderTag = null,
                changed = true,
                requestRehydrate = true,
                rehydrateReason = "orphaned_decision_ref_write_barrier"
            )
        }
        is ArtworkReferenceIntegrityResult.UnknownDecisionRef -> {
            ArtworkWriteRepair(
                fieldName = "poster",
                originalRef = poster,
                ref = ref,
                posterProviderTag = posterProviderTag.takeIf { it == requiredProviderTag },
                changed = posterProviderTag != posterProviderTag.takeIf { it == requiredProviderTag },
                requestRehydrate = true,
                rehydrateReason = integrity.reason
            )
        }
        is ArtworkReferenceIntegrityResult.Invalid,
        ArtworkReferenceIntegrityResult.Empty -> {
            ArtworkWriteRepair(
                fieldName = "poster",
                originalRef = poster,
                ref = null,
                posterProviderTag = null,
                changed = true,
                requestRehydrate = true,
                rehydrateReason = "invalid_artwork_ref_write_barrier"
            )
        }
    }
}

private fun repairStandaloneArtworkRefForWrite(ref: String?, fieldName: String): ArtworkWriteRepair {
    val value = ref?.trim()?.takeIf { it.isNotBlank() }
        ?: return ArtworkWriteRepair(fieldName, ref, null, null, changed = false, requestRehydrate = false)
    if (!isSafeArtworkRef(value)) {
        return ArtworkWriteRepair(fieldName, ref, value, null, changed = value != ref, requestRehydrate = false)
    }
    return when (val integrity = artworkReferenceIntegrityValidator.validate(value)) {
        is ArtworkReferenceIntegrityResult.ValidDecision,
        is ArtworkReferenceIntegrityResult.ValidAsset ->
            ArtworkWriteRepair(fieldName, ref, value, null, changed = value != ref, requestRehydrate = false)
        is ArtworkReferenceIntegrityResult.RecoverableAssetForDecision -> {
            val assetRef = "nexio-artwork://asset/${integrity.assetKey.value}"
            ArtworkWriteRepair(fieldName, ref, assetRef, null, changed = assetRef != value, requestRehydrate = false)
        }
        is ArtworkReferenceIntegrityResult.OrphanedDecisionRef ->
            ArtworkWriteRepair(fieldName, ref, null, null, changed = true, requestRehydrate = true, rehydrateReason = "orphaned_decision_ref_write_barrier")
        is ArtworkReferenceIntegrityResult.UnknownDecisionRef ->
            ArtworkWriteRepair(fieldName, ref, value, null, changed = value != ref, requestRehydrate = true, rehydrateReason = integrity.reason)
        is ArtworkReferenceIntegrityResult.Invalid,
        ArtworkReferenceIntegrityResult.Empty ->
            ArtworkWriteRepair(fieldName, ref, null, null, changed = true, requestRehydrate = true, rehydrateReason = "invalid_artwork_ref_write_barrier")
    }
}

private fun isSafeArtworkRef(ref: String): Boolean =
    ref.startsWith("nexio-artwork://decision/") || ref.startsWith("nexio-artwork://asset/")

private data class ArtworkWriteRepair(
    val fieldName: String,
    val originalRef: String?,
    val ref: String?,
    val posterProviderTag: String?,
    val changed: Boolean,
    val requestRehydrate: Boolean,
    val rehydrateReason: String? = null
)
```

Add this concrete private mapper next to `repairArtworkWriteInvariants()`:

```kotlin
private fun Snapshot.mapItems(transform: (MetaPreview) -> MetaPreview): Snapshot =
    copy(
        catalogRows = catalogRows.map { row -> row.copy(items = row.items.map(transform)) },
        fullCatalogRows = fullCatalogRows.map { row -> row.copy(items = row.items.map(transform)) },
        heroItems = heroItems.map(transform)
    )
```

- [ ] **Step 5: Run snapshot tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest
```

Expected: PASS.

## Task 9: Wire DI For Asset Store And Validator

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt`
- Test: compile all unit tests touched so far.

- [ ] **Step 1: Add providers**

In `IntegrationRuntimeModule.kt`, add imports:

```kotlin
import com.nexio.tv.core.artwork.ArtworkAssetRecordStore
import com.nexio.tv.core.artwork.ArtworkReferenceIntegrityValidator
import com.nexio.tv.core.artwork.DefaultArtworkReferenceIntegrityValidator
import com.nexio.tv.core.artwork.DurableArtworkAssetRecordStore
```

Add provider methods after `provideArtworkAssetDiskCache`:

```kotlin
@Provides
@Singleton
fun provideArtworkAssetRecordStore(
    @ApplicationContext context: Context,
    gson: Gson
): ArtworkAssetRecordStore =
    DurableArtworkAssetRecordStore(
        file = File(context.filesDir, "artwork-asset-records-v1.json"),
        gson = gson
    )

@Provides
@Singleton
fun provideArtworkReferenceIntegrityValidator(
    impl: DefaultArtworkReferenceIntegrityValidator
): ArtworkReferenceIntegrityValidator = impl
```

- [ ] **Step 2: Fix constructor call sites**

Update test factories and production Hilt injection where `ArtworkAssetRepository` is constructed directly. The test factory in `ArtworkAssetRepositoryTest.kt` should pass:

```kotlin
assetRecordStore = assetRecordStore,
```

- [ ] **Step 3: Run compile-focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest
```

Expected: PASS.

## Task 10: Instrument Legacy Remote Artwork Fallback

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/image/LegacyRemoteArtworkFetcher.kt`
- Test: `app/src/test/java/com/nexio/tv/core/image/LegacyRemoteArtworkFetcherTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt`
- Test: `app/src/test/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSinkTest.kt`

This is compatibility visibility only. `LegacyRemoteArtworkFetcher` must not become the normal premium fallback path, and it must not accept RPDB or Top-Posters URLs.

- [ ] **Step 1: Add fetcher trace tests**

Create `app/src/test/java/com/nexio/tv/core/image/LegacyRemoteArtworkFetcherTest.kt`:

```kotlin
package com.nexio.tv.core.image

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.SensitiveArtworkUrl
import com.nexio.tv.data.integration.posters.transport.PosterTransport
import com.nexio.tv.data.integration.posters.transport.PosterTransportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import io.mockk.every
import io.mockk.mockk

class LegacyRemoteArtworkFetcherTest {
    @Test
    fun `legacy remote fetch emits start and success traces`() = runTest {
        val traceSink = RecordingTraceSink()
        val transport = mockk<PosterTransport>()
        every { transport.execute("https://image.tmdb.org/t/p/w500/a.jpg") } returns PosterTransportResult(
            statusCode = 200,
            isSuccessful = true,
            body = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01)
        )
        val fetcher = LegacyRemoteArtworkFetcher(
            model = LegacyRemoteArtworkModel(
                key = "legacy-artwork:test",
                imageType = ArtworkType.POSTER,
                url = SensitiveArtworkUrl.of("https://image.tmdb.org/t/p/w500/a.jpg")
            ),
            transport = transport,
            traceSink = traceSink
        )

        val result = fetcher.fetch()

        assertNotNull(result)
        assertEquals(
            listOf("legacy_remote_artwork.fetch_start", "legacy_remote_artwork.fetch_success"),
            traceSink.events.map { it.eventType }
        )
    }

    @Test
    fun `legacy remote fetch emits failure trace`() = runTest {
        val traceSink = RecordingTraceSink()
        val transport = mockk<PosterTransport>()
        every { transport.execute("https://image.tmdb.org/t/p/w500/missing.jpg") } returns PosterTransportResult(
            statusCode = 404,
            isSuccessful = false,
            body = null
        )
        val fetcher = LegacyRemoteArtworkFetcher(
            model = LegacyRemoteArtworkModel(
                key = "legacy-artwork:missing",
                imageType = ArtworkType.POSTER,
                url = SensitiveArtworkUrl.of("https://image.tmdb.org/t/p/w500/missing.jpg")
            ),
            transport = transport,
            traceSink = traceSink
        )

        val result = fetcher.fetch()

        assertNull(result)
        assertEquals(
            listOf("legacy_remote_artwork.fetch_start", "legacy_remote_artwork.fetch_failed"),
            traceSink.events.map { it.eventType }
        )
    }

    @Test
    fun `premium provider urls do not create legacy remote artwork model`() {
        assertNull(
            "https://api.ratingposterdb.com/key/imdb/poster-default/tt123.jpg"
                .toLegacyArtworkCoilModelOrNull(ownerKey = "tt123", imageType = ArtworkType.POSTER)
        )
        assertNull(
            "https://api.top-posters.com/key/imdb/poster-default/tt123.jpg"
                .toLegacyArtworkCoilModelOrNull(ownerKey = "tt123", imageType = ArtworkType.POSTER)
        )
    }
}
```

- [ ] **Step 2: Run the fetcher test and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.image.LegacyRemoteArtworkFetcherTest
```

Expected: compile failure because `LegacyRemoteArtworkFetcher` does not accept `RuntimeTraceSink`.

- [ ] **Step 3: Add trace sink to fetcher**

Modify the constructor:

```kotlin
class LegacyRemoteArtworkFetcher(
    private val model: LegacyRemoteArtworkModel,
    private val transport: PosterTransport,
    private val traceSink: RuntimeTraceSink = NoopRuntimeTraceSink
) : Fetcher {
```

Trace start, success, and fail:

```kotlin
override suspend fun fetch(): FetchResult? {
    trace("legacy_remote_artwork.fetch_start", mapOf("urlHash" to model.url.value.hashCode().toString()))
    val result = try {
        transport.execute(model.url.value)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        trace("legacy_remote_artwork.fetch_failed", mapOf("errorClass" to error::class.java.simpleName))
        return null
    }
    val bytes = result.body?.takeIf { result.isSuccessful }
    if (bytes == null) {
        trace("legacy_remote_artwork.fetch_failed", mapOf("statusCode" to result.statusCode))
        return null
    }
    trace("legacy_remote_artwork.fetch_success", mapOf("byteCount" to bytes.size))
    return SourceResult(
        source = createTempFileSource(bytes),
        mimeType = "image/jpeg",
        dataSource = DataSource.NETWORK
    )
}
```

Add a private trace helper using the same `TraceEventEnvelope` pattern as `ArtworkAssetRepository`.

Update `Factory` to inject and pass `RuntimeTraceSink`.

- [ ] **Step 4: Add logcat event names**

In `LogcatRuntimeTraceSink.kt`, include these event types in the same gated routing used for artwork/runtime trace:

```kotlin
"artwork.ref_integrity_checked",
"artwork.orphan_decision_ref_found",
"artwork.orphan_decision_ref_asset_recovered",
"artwork.orphan_decision_ref_rehydrate_requested",
"home.snapshot_write_barrier_repaired",
"home.snapshot_provider_tag_mismatch_ignored",
"legacy_remote_artwork.fetch_start",
"legacy_remote_artwork.fetch_success",
"legacy_remote_artwork.fetch_failed"
```

- [ ] **Step 5: Run trace tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.image.LegacyRemoteArtworkFetcherTest --tests com.nexio.tv.core.trace.LogcatRuntimeTraceSinkTest
```

Expected: PASS.

## Task 11: On-Device Verification

**Files:**
- No new files unless logs are saved under `tmp/`.

- [ ] **Step 1: Build and install**

Run:

```bash
./gradlew :app:assembleDebug
adb connect 192.168.50.71
adb -s 192.168.50.71 install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected:

```text
Success
```

- [ ] **Step 2: Capture restart logs**

Run:

```bash
adb -s 192.168.50.71 logcat -c
adb -s 192.168.50.71 shell am force-stop com.nexio.tv
adb -s 192.168.50.71 shell monkey -p com.nexio.tv 1
sleep 40
adb -s 192.168.50.71 logcat -d > tmp/artwork-reference-integrity-50-71.log
```

Expected log evidence:

```text
home.snapshot_read success=true
home.snapshot_provider_tag_mismatch_ignored mismatchCount>=0
artwork.orphan_decision_ref_found
artwork.orphan_decision_ref_asset_recovered
home.snapshot_artwork_rehydrate_requested
```

There must be no new persisted raw premium URLs:

```bash
adb -s 192.168.50.71 shell run-as com.nexio.tv sh -c 'grep -R "ratingposterdb\\|top-posters" files shared_prefs 2>/dev/null'
```

Expected: no output.

- [ ] **Step 3: Check UI screenshot**

Run:

```bash
adb -s 192.168.50.71 exec-out screencap -p > tmp/artwork-reference-integrity-50-71.png
```

Expected: previously materialized premium posters render after restart without waiting for a full home refresh. If a row still has placeholders, its log path must show either `artwork.orphan_decision_ref_rehydrate_requested` or `legacy_remote_artwork.fetch_failed`, not `home.snapshot_read success=false reason=poster_provider_tag_mismatch`.

## Task 12: Full Verification And Commit

**Files:**
- All files touched in Tasks 1-10.

- [ ] **Step 1: Run focused unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.nexio.tv.core.artwork.ArtworkAssetRecordStoreTest \
  --tests com.nexio.tv.core.artwork.ArtworkReferenceIntegrityValidatorTest \
  --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest \
  --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest \
  --tests com.nexio.tv.core.image.LegacyRemoteArtworkFetcherTest \
  --tests com.nexio.tv.core.trace.LogcatRuntimeTraceSinkTest
```

Expected: PASS.

- [ ] **Step 2: Run broader app tests if time allows**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS. If unrelated tests fail, save the failure output and do not hide it in the final handoff.

- [ ] **Step 3: Review git diff for scope**

Run:

```bash
git diff -- app/src/main/java/com/nexio/tv/core/artwork app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt app/src/main/java/com/nexio/tv/core/image/LegacyRemoteArtworkFetcher.kt app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt app/src/test/java/com/nexio/tv/core/artwork app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt app/src/test/java/com/nexio/tv/core/image app/src/test/java/com/nexio/tv/core/trace
```

Expected: only artwork reference integrity, snapshot read/write barrier, trace, DI, and tests are changed.

- [ ] **Step 4: Commit**

Run:

```bash
git status --short
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRecordStore.kt \
  app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkAssetRecordStore.kt \
  app/src/main/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidator.kt \
  app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt \
  app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetDiskCache.kt \
  app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt \
  app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt \
  app/src/main/java/com/nexio/tv/core/image/LegacyRemoteArtworkFetcher.kt \
  app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt \
  app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRecordStoreTest.kt \
  app/src/test/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidatorTest.kt \
  app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt \
  app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt \
  app/src/test/java/com/nexio/tv/core/image/LegacyRemoteArtworkFetcherTest.kt \
  app/src/test/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSinkTest.kt
git commit -m "fix: recover orphan artwork refs after restart"
```

Expected: one focused commit. Do not stage unrelated dirty files.

## Self-Review

- Spec coverage:
  - Non-fatal snapshot read for artwork mismatch: Task 6.
  - DTO-backed decision-to-asset reverse lookup with malformed-record quarantine: Tasks 1-4.
  - Snapshot write barrier that validates persisted refs, promotes recoverable decision refs to asset refs, clears unbacked refs, and never persists `poster=null` with `posterProviderTag`: Task 8.
  - Rehydration request path for missing, unknown, invalid, and provider-mismatched artwork: Tasks 4, 6, 7, and 8.
  - Provider tag no longer authoritative for whole snapshot rejection and is cleared when it cannot be derived safely: Tasks 6 and 8.
  - Legacy fallback instrumentation plus premium-provider exclusion: Task 10.
  - Raw premium URL avoidance: existing sanitizer tests remain, Task 11 verifies persisted storage.

- Placeholder scan:
  - No task contains open-ended placeholder implementation language. Each code-modifying task includes concrete test code, implementation snippets, commands, and expected results.

- Type consistency:
  - `ArtworkAssetRecordStore`, `DurableArtworkAssetRecordStore`, `ArtworkReferenceIntegrityValidator`, `ArtworkReferenceIntegrityResult`, `UnknownDecisionRef`, `NoopArtworkReferenceIntegrityValidator`, and `DefaultArtworkReferenceIntegrityValidator` are introduced before use.
  - `ArtworkAssetRepository` receives `assetRecordStore` before tests pass it through the factory.
  - `ArtworkAssetResult.runtimeResult` is made nullable before reverse recovery returns a disk-only result without reading full image bytes.
  - Snapshot behavior is changed from destructive missing-decision cleanup to preservation plus hydration before the write barrier validates and repairs persisted artwork refs.
