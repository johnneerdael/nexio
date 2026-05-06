package com.nexio.animemap.emit

import com.nexio.animemap.model.AssetCounts
import com.nexio.animemap.model.EpisodeMappingRecord
import com.nexio.animemap.model.IdentityRecord
import com.nexio.animemap.model.NexioAnimeMap
import com.nexio.animemap.model.NexioAnimeMapJsonAdapter
import com.nexio.animemap.model.ProvenanceFile
import com.nexio.animemap.model.ProvenanceFileJsonAdapter
import com.squareup.moshi.Moshi
import java.io.File

class AssetWriter {

    fun writeAsset(
        out: File,
        identity: Map<String, IdentityRecord>,
        episodeMapping: Map<String, EpisodeMappingRecord>,
        generatedAt: String,
        skippedCount: Int = 0
    ) {
        val indexes = IndexBuilder().build(identity)
        val asset = NexioAnimeMap(
            schemaVersion = 2,
            mappingPolicyVersion = 1,
            generatedAt = generatedAt,
            counts = AssetCounts(
                identityRecords = identity.size,
                episodeMappingRecords = episodeMapping.size,
                skippedCount = skippedCount
            ),
            identityRecordsByKitsu = identity,
            episodeMappingsByAnidb = episodeMapping,
            indexes = indexes
        )
        val moshi = Moshi.Builder().build()
        val adapter = NexioAnimeMapJsonAdapter(moshi).indent("  ")
        out.parentFile.mkdirs()
        out.writeText(adapter.toJson(asset))
    }

    fun writeProvenance(out: File, provenance: ProvenanceFile) {
        val moshi = Moshi.Builder().build()
        val adapter = ProvenanceFileJsonAdapter(moshi).indent("  ")
        out.parentFile.mkdirs()
        out.writeText(adapter.toJson(provenance))
    }
}
