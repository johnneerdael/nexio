package com.nexio.tv.core.artwork

import com.google.gson.Gson
import java.io.File

class DurableArtworkAssetRecordStore(
    private val file: File,
    gson: Gson
) : ArtworkAssetRecordStore {
    private val codec = ArtworkAssetRecordJsonCodec(gson)
    private val lock = Any()
    private var loaded = false
    private var loadFailure: String? = null
    private var quarantinedRecords = 0
    private val recordsByAsset = linkedMapOf<ArtworkAssetKey, ArtworkAssetRecord>()
    private val latestByDecision = linkedMapOf<ArtworkDecisionKey, ArtworkAssetRecord>()

    override fun put(record: ArtworkAssetRecord) = synchronized(lock) {
        ensureLoadedLocked()
        loadFailure?.let { failure ->
            throw IllegalStateException("Cannot write artwork asset records after failed load: $failure")
        }

        val candidateRecords = LinkedHashMap(recordsByAsset)
        candidateRecords[record.assetKey] = record
        val candidateLatest = latestByDecisionFor(candidateRecords)

        persistLocked(candidateRecords)

        recordsByAsset.clear()
        recordsByAsset.putAll(candidateRecords)
        latestByDecision.clear()
        latestByDecision.putAll(candidateLatest)
    }

    override fun get(assetKey: ArtworkAssetKey): ArtworkAssetRecord? = synchronized(lock) {
        ensureLoadedLocked()
        recordsByAsset[assetKey]
    }

    override fun findLatestAssetForDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetRecord? =
        synchronized(lock) {
            ensureLoadedLocked()
            latestByDecision[decisionKey]
        }

    fun quarantinedRecordCount(): Int = synchronized(lock) {
        ensureLoadedLocked()
        quarantinedRecords
    }

    private fun ensureLoadedLocked() {
        if (loaded) return

        val snapshot = try {
            codec.readStoreFile(file)
        } catch (exception: Exception) {
            recordsByAsset.clear()
            latestByDecision.clear()
            loadFailure = exception.message ?: exception::class.java.simpleName
            loaded = true
            return
        }
        if (snapshot == null) {
            loaded = true
            return
        }

        quarantinedRecords = snapshot.quarantinedRecordCount
        snapshot.records.forEach { record ->
            recordsByAsset[record.assetKey] = record
        }
        rebuildLatestByDecisionLocked()
        loaded = true
    }

    private fun rebuildLatestByDecisionLocked() {
        latestByDecision.clear()
        latestByDecision.putAll(latestByDecisionFor(recordsByAsset))
    }

    private fun latestByDecisionFor(
        records: Map<ArtworkAssetKey, ArtworkAssetRecord>
    ): LinkedHashMap<ArtworkDecisionKey, ArtworkAssetRecord> {
        val latest = linkedMapOf<ArtworkDecisionKey, ArtworkAssetRecord>()
        records.values.forEach { record ->
            val decisionKey = record.decisionKey ?: return@forEach
            val current = latest[decisionKey]
            if (current == null || record.fetchedAtMs >= current.fetchedAtMs) {
                latest[decisionKey] = record
            }
        }
        return latest
    }

    private fun persistLocked(records: Map<ArtworkAssetKey, ArtworkAssetRecord>) {
        codec.writeStoreFile(file, records.values.toList())
    }
}
