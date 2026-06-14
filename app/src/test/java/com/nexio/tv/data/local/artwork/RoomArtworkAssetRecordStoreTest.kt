package com.nexio.tv.data.local.artwork

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkAssetRecord
import com.nexio.tv.core.artwork.ArtworkAssetRecordJsonCodec
import com.nexio.tv.core.artwork.ArtworkAssetRecordStore
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkType
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomArtworkAssetRecordStoreTest {
    private val gson = Gson()
    private val temp = TemporaryFolder().also { it.create() }

    @Test
    fun `put get and latest decision lookup survive recreated store`() = runTest {
        val db = inMemoryDatabase()
        val legacyFile = temp.newFile("missing-legacy.json").also { it.delete() }
        val decisionKey = ArtworkDecisionKey("decision-a")
        val older = record("asset-old", decisionKey, fetchedAtMs = 100)
        val newer = record("asset-new", decisionKey, fetchedAtMs = 200)

        store(db, legacyFile).put(older)
        store(db, legacyFile).put(newer)

        val restarted = store(db, legacyFile)

        assertEquals(older, restarted.get(older.assetKey))
        assertEquals(newer, restarted.get(newer.assetKey))
        assertEquals(newer, restarted.findLatestAssetForDecision(decisionKey))
    }

    @Test
    fun `imports legacy json records once and marks migration complete`() = runTest {
        val db = inMemoryDatabase()
        val legacyFile = temp.newFile("legacy-records.json")
        val validA = record("legacy-a", ArtworkDecisionKey("legacy-decision"), fetchedAtMs = 100)
        val validB = record("legacy-b", ArtworkDecisionKey("legacy-decision"), fetchedAtMs = 200)
        writeLegacyFile(legacyFile, listOf(validA, validB), includeMalformedRecord = true)

        val firstStore = store(db, legacyFile)

        assertEquals(validA, firstStore.get(validA.assetKey))
        assertEquals(validB, firstStore.findLatestAssetForDecision(ArtworkDecisionKey("legacy-decision")))
        assertEquals(
            ArtworkCacheMigrationEntity(
                key = "asset-records-json-v1",
                completedAtMs = db.migrationDao().get("asset-records-json-v1")!!.completedAtMs,
                importedCount = 2,
                skippedCount = 1
            ),
            db.migrationDao().get("asset-records-json-v1")
        )

        val notImported = record(
            "legacy-after-migration",
            ArtworkDecisionKey("legacy-decision"),
            fetchedAtMs = 300
        )
        writeLegacyFile(legacyFile, listOf(notImported), includeMalformedRecord = false)
        val restarted = store(db, legacyFile)

        assertNull(restarted.get(notImported.assetKey))
        assertEquals(validB, restarted.findLatestAssetForDecision(ArtworkDecisionKey("legacy-decision")))
    }

    @Test
    fun `record without decision key is stored by asset but excluded from latest lookup`() = runTest {
        val db = inMemoryDatabase()
        val legacyFile = temp.newFile("missing-legacy.json").also { it.delete() }
        val assetOnly = record("asset-only", decisionKey = null, fetchedAtMs = 300)

        val store = store(db, legacyFile)
        store.put(assetOnly)

        assertEquals(assetOnly, store.get(assetOnly.assetKey))
        assertNull(store.findLatestAssetForDecision(ArtworkDecisionKey("decision-a")))
    }

    @Test
    fun `malformed Room payload is skipped in memory without crashing get or latest`() = runTest {
        val db = inMemoryDatabase()
        val mapper = ArtworkAssetRecordEntityMapper(ArtworkAssetRecordJsonCodec(gson))
        val valid = record("valid-room", ArtworkDecisionKey("decision-room"), fetchedAtMs = 100)
        val malformed = record("malformed-room", ArtworkDecisionKey("decision-room"), fetchedAtMs = 200)
        db.migrationDao().markComplete(
            ArtworkCacheMigrationEntity(
                key = "asset-records-json-v1",
                completedAtMs = 1000,
                importedCount = 0,
                skippedCount = 0
            )
        )
        db.assetRecordDao().upsertAssetRecords(
            listOf(
                mapper.toEntity(valid),
                mapper.toEntity(malformed).copy(payloadJson = "{bad json")
            )
        )

        val store = store(db, temp.newFile("missing-legacy.json").also { it.delete() })

        assertEquals(valid, store.get(valid.assetKey))
        assertNull(store.get(malformed.assetKey))
        assertEquals(valid, store.findLatestAssetForDecision(ArtworkDecisionKey("decision-room")))
    }

    @Test
    fun `Room write failure delegates the write to fallback`() = runTest {
        val fallback = InMemoryArtworkAssetRecordStore()
        val record = record("fallback-record", ArtworkDecisionKey("fallback-decision"), fetchedAtMs = 500)
        val store = RoomArtworkAssetRecordStore(
            dao = FailingWriteArtworkAssetRecordDao(),
            migrationDao = InMemoryArtworkCacheMigrationDao(),
            legacyFile = temp.newFile("missing-legacy.json").also { it.delete() },
            gson = gson,
            fallback = fallback
        )

        store.put(record)

        assertEquals(record, fallback.get(record.assetKey))
        assertEquals(record, store.get(record.assetKey))
        assertEquals(
            record,
            store.findLatestAssetForDecision(ArtworkDecisionKey("fallback-decision"))
        )
    }

    private fun store(
        db: ArtworkCacheDatabase,
        legacyFile: File,
        fallback: ArtworkAssetRecordStore = InMemoryArtworkAssetRecordStore()
    ): RoomArtworkAssetRecordStore =
        RoomArtworkAssetRecordStore(
            dao = db.assetRecordDao(),
            migrationDao = db.migrationDao(),
            legacyFile = legacyFile,
            gson = gson,
            fallback = fallback
        )

    private fun inMemoryDatabase(): ArtworkCacheDatabase {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return Room.inMemoryDatabaseBuilder(context, ArtworkCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private fun writeLegacyFile(
        file: File,
        records: List<ArtworkAssetRecord>,
        includeMalformedRecord: Boolean
    ) {
        val json = ArtworkAssetRecordJsonCodec(gson).toStoreJson(records)
        if (includeMalformedRecord) {
            json.getAsJsonArray("records").add(
                JsonObject().apply {
                    addProperty("assetKey", "legacy-bad")
                    addProperty("imageType", "NOT_REAL")
                }
            )
        }
        file.writeText(gson.toJson(json))
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
            expiresAtMs = fetchedAtMs + 100,
            staleUntilMs = fetchedAtMs + 200
        )

    private class InMemoryArtworkAssetRecordStore : ArtworkAssetRecordStore {
        private val recordsByAsset = linkedMapOf<ArtworkAssetKey, ArtworkAssetRecord>()
        private val latestByDecision = linkedMapOf<ArtworkDecisionKey, ArtworkAssetRecord>()

        override fun put(record: ArtworkAssetRecord) {
            recordsByAsset[record.assetKey] = record
            latestByDecision.clear()
            recordsByAsset.values.forEach { stored ->
                val decisionKey = stored.decisionKey ?: return@forEach
                val current = latestByDecision[decisionKey]
                if (current == null || stored.fetchedAtMs >= current.fetchedAtMs) {
                    latestByDecision[decisionKey] = stored
                }
            }
        }

        override fun get(assetKey: ArtworkAssetKey): ArtworkAssetRecord? = recordsByAsset[assetKey]

        override fun findLatestAssetForDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetRecord? =
            latestByDecision[decisionKey]
    }

    private class FailingWriteArtworkAssetRecordDao : ArtworkAssetRecordDao {
        override suspend fun upsertAssetRecord(entity: ArtworkAssetRecordEntity) {
            throw IOException("write failed")
        }

        override suspend fun upsertAssetRecords(entities: List<ArtworkAssetRecordEntity>) = Unit

        override suspend fun getAssetRecord(assetKey: String): ArtworkAssetRecordEntity? = null

        override suspend fun getAllAssetRecords(): List<ArtworkAssetRecordEntity> = emptyList()

        override suspend fun findLatestAssetForDecision(decisionKey: String): ArtworkAssetRecordEntity? = null

        override suspend fun findExpired(nowMs: Long): List<ArtworkAssetRecordEntity> = emptyList()
    }

    private class InMemoryArtworkCacheMigrationDao : ArtworkCacheMigrationDao {
        private val entities = linkedMapOf<String, ArtworkCacheMigrationEntity>()

        override suspend fun get(key: String): ArtworkCacheMigrationEntity? = entities[key]

        override suspend fun markComplete(entity: ArtworkCacheMigrationEntity) {
            entities[entity.key] = entity
        }
    }
}
