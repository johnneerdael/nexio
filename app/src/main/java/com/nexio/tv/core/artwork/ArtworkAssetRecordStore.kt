package com.nexio.tv.core.artwork

interface ArtworkAssetRecordStore {
    fun put(record: ArtworkAssetRecord)
    fun get(assetKey: ArtworkAssetKey): ArtworkAssetRecord?
    fun findLatestAssetForDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetRecord?
}
