package com.nexio.tv.core.artwork

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtworkCacheRepairService @Inject constructor(
    private val diskCache: ArtworkAssetDiskCache,
    private val assetRecordStore: ArtworkAssetRecordStore
) {
    fun repairDecisionRef(decisionKey: ArtworkDecisionKey): String? {
        val record = assetRecordStore.findLatestAssetForDecision(decisionKey) ?: return null
        val file = diskCache.getExistingFile(record) ?: return null
        if (!file.isFile || !diskCache.hasReadableImageBytes(record)) return null
        return "nexio-artwork://asset/${record.assetKey.value}"
    }
}
