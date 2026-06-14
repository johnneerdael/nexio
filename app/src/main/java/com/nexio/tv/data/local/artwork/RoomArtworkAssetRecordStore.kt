package com.nexio.tv.data.local.artwork

import com.google.gson.Gson
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkAssetRecord
import com.nexio.tv.core.artwork.ArtworkAssetRecordJsonCodec
import com.nexio.tv.core.artwork.ArtworkAssetRecordStore
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class RoomArtworkAssetRecordStore(
    private val dao: ArtworkAssetRecordDao,
    private val migrationDao: ArtworkCacheMigrationDao,
    private val legacyFile: File,
    gson: Gson,
    private val fallback: ArtworkAssetRecordStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ArtworkAssetRecordStore {
    private val codec = ArtworkAssetRecordJsonCodec(gson)
    private val mapper = ArtworkAssetRecordEntityMapper(codec)
    private val lock = Any()
    private var loaded = false
    private var fallbackMode = false
    private val recordsByAsset = linkedMapOf<ArtworkAssetKey, ArtworkAssetRecord>()
    private val latestByDecision = linkedMapOf<ArtworkDecisionKey, ArtworkAssetRecord>()

    override fun put(record: ArtworkAssetRecord) = synchronized(lock) {
        if (fallbackMode) {
            fallback.put(record)
            return@synchronized
        }
        if (!ensureLoadedLocked()) {
            fallback.put(record)
            return@synchronized
        }

        try {
            runBlocking(ioDispatcher) {
                dao.upsertAssetRecord(mapper.toEntity(record))
            }
        } catch (_: Exception) {
            enterFallbackModeLocked()
            fallback.put(record)
            return@synchronized
        }

        recordsByAsset[record.assetKey] = record
        rebuildLatestByDecisionLocked()
    }

    override fun get(assetKey: ArtworkAssetKey): ArtworkAssetRecord? = synchronized(lock) {
        if (fallbackMode) {
            return@synchronized fallback.get(assetKey)
        }
        if (!ensureLoadedLocked()) {
            return@synchronized fallback.get(assetKey)
        }
        recordsByAsset[assetKey]
    }

    override fun findLatestAssetForDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetRecord? =
        synchronized(lock) {
            if (fallbackMode) {
                return@synchronized fallback.findLatestAssetForDecision(decisionKey)
            }
            if (!ensureLoadedLocked()) {
                return@synchronized fallback.findLatestAssetForDecision(decisionKey)
            }
            latestByDecision[decisionKey]
        }

    private fun ensureLoadedLocked(): Boolean {
        if (loaded) return !fallbackMode

        val entities = try {
            runBlocking(ioDispatcher) {
                importLegacyJsonIfNeeded()
                dao.getAllAssetRecords()
            }
        } catch (_: Exception) {
            enterFallbackModeLocked()
            return false
        }

        recordsByAsset.clear()
        latestByDecision.clear()
        entities.forEach { entity ->
            val record = mapper.toDomain(entity) ?: return@forEach
            recordsByAsset[record.assetKey] = record
        }
        rebuildLatestByDecisionLocked()
        loaded = true
        return true
    }

    private suspend fun importLegacyJsonIfNeeded() {
        if (migrationDao.get(MIGRATION_KEY) != null) return

        val snapshot = codec.readStoreFile(legacyFile)
        val records = snapshot?.records.orEmpty()
        if (records.isNotEmpty()) {
            dao.upsertAssetRecords(records.map(mapper::toEntity))
        }
        migrationDao.markComplete(
            ArtworkCacheMigrationEntity(
                key = MIGRATION_KEY,
                completedAtMs = System.currentTimeMillis(),
                importedCount = records.size,
                skippedCount = snapshot?.quarantinedRecordCount ?: 0
            )
        )
    }

    private fun rebuildLatestByDecisionLocked() {
        latestByDecision.clear()
        recordsByAsset.values.forEach { record ->
            val decisionKey = record.decisionKey ?: return@forEach
            val current = latestByDecision[decisionKey]
            if (current == null || record.fetchedAtMs >= current.fetchedAtMs) {
                latestByDecision[decisionKey] = record
            }
        }
    }

    private fun enterFallbackModeLocked() {
        recordsByAsset.clear()
        latestByDecision.clear()
        fallbackMode = true
        loaded = true
    }

    private companion object {
        const val MIGRATION_KEY = "asset-records-json-v1"
    }
}
