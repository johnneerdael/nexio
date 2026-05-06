package com.nexio.animemap

import com.nexio.animemap.emit.AssetWriter
import com.nexio.animemap.merge.IdentityMerger
import com.nexio.animemap.merge.MergedScudlee
import com.nexio.animemap.merge.OverlayApplier
import com.nexio.animemap.model.OverlayFile
import com.nexio.animemap.model.OverlayFileJsonAdapter
import com.nexio.animemap.model.ProvenanceFile
import com.nexio.animemap.model.ProvenanceOverlay
import com.nexio.animemap.model.ProvenanceSource
import com.nexio.animemap.parse.FribbJsonParser
import com.nexio.animemap.parse.MappingListExpander
import com.nexio.animemap.parse.ScudleeXmlParser
import com.squareup.moshi.Moshi
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

object Generator {

    data class Args(
        val fribbInput: File,
        val scudleeInput: File,
        val overlayInput: File,
        val assetOutput: File,
        val provenanceOutput: File,
        val fribbUrl: String,
        val fribbCommit: String?,
        val scudleeUrl: String,
        val scudleeCommit: String?
    )

    fun run(args: Args) {
        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val fribbFragments = FribbJsonParser().parse(args.fribbInput.readText())
        val scudleeEntries = ScudleeXmlParser().parse(args.scudleeInput.readText())
        val expandedByAnidb = scudleeEntries.associate { entry ->
            entry.anidb to MergedScudlee(
                entry = entry,
                expanded = entry.mappingListXml?.let { MappingListExpander().expand(it) }
            )
        }

        val merged = IdentityMerger().merge(fribbFragments, expandedByAnidb)

        val overlay = OverlayFileJsonAdapter(Moshi.Builder().build())
            .fromJson(args.overlayInput.readText())
            ?: OverlayFile(schemaVersion = 1, entries = emptyList())

        val finalData = OverlayApplier().apply(merged.identity, merged.episodeMapping, overlay.entries)

        val writer = AssetWriter()
        writer.writeAsset(
            out = args.assetOutput,
            identity = finalData.identity,
            episodeMapping = finalData.episodeMapping,
            generatedAt = now
        )
        writer.writeProvenance(args.provenanceOutput, ProvenanceFile(
            generatedAt = now,
            sources = mapOf(
                "fribb" to ProvenanceSource(args.fribbUrl, args.fribbCommit, now),
                "scudlee" to ProvenanceSource(args.scudleeUrl, args.scudleeCommit, now)
            ),
            overlay = ProvenanceOverlay(version = overlay.schemaVersion, entryCount = overlay.entries.size),
            counts = com.nexio.animemap.model.AssetCounts(
                identityRecords = finalData.identity.size,
                episodeMappingRecords = finalData.episodeMapping.size
            )
        ))
    }
}

fun main(args: Array<String>) {
    require(args.size == 8) { "expected 8 args: fribbIn scudleeIn overlayIn assetOut provenanceOut fribbUrl fribbCommit scudleeUrl" }
    Generator.run(Generator.Args(
        fribbInput = File(args[0]),
        scudleeInput = File(args[1]),
        overlayInput = File(args[2]),
        assetOutput = File(args[3]),
        provenanceOutput = File(args[4]),
        fribbUrl = args[5],
        fribbCommit = args.getOrNull(6),
        scudleeUrl = args[7],
        scudleeCommit = null
    ))
}
