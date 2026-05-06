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

object FetchMain {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 7) { "expected 7 args: fribbRawUrl fribbCommitsUrl fribbCachePath scudleeRawUrl scudleeCommitsUrl scudleeCachePath shasOutputPath" }
        val fetcher = com.nexio.animemap.fetch.UpstreamFetcher()
        val fribb = fetcher.fetchSource(args[0], args[1], java.io.File(args[2]))
        val scudlee = fetcher.fetchSource(args[3], args[4], java.io.File(args[5]))
        val moshi = com.squareup.moshi.Moshi.Builder().build()
        val mapType = com.squareup.moshi.Types.newParameterizedType(
            Map::class.java, String::class.java, Any::class.java
        )
        @Suppress("UNCHECKED_CAST")
        val adapter = moshi.adapter<Map<String, Any?>>(mapType).indent("  ")
        val out = java.io.File(args[6])
        out.parentFile.mkdirs()
        out.writeText(adapter.toJson(mapOf(
            "fribb" to mapOf("url" to fribb.url, "commit" to fribb.commit, "fetchedAt" to fribb.fetchedAt),
            "scudlee" to mapOf("url" to scudlee.url, "commit" to scudlee.commit, "fetchedAt" to scudlee.fetchedAt)
        )))
    }
}

object GenerateMain {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 8) { "expected 8 args: fribbInput scudleeInput overlayInput shasFile assetOutput provenanceOutput fribbUrl scudleeUrl" }
        val moshi = com.squareup.moshi.Moshi.Builder().build()
        val mapType = com.squareup.moshi.Types.newParameterizedType(
            Map::class.java, String::class.java, Any::class.java
        )
        val shasFile = java.io.File(args[3])
        @Suppress("UNCHECKED_CAST")
        val shas: Map<String, Any?> = if (shasFile.exists())
            (moshi.adapter<Map<String, Any?>>(mapType).fromJson(shasFile.readText()) ?: emptyMap())
        else emptyMap()
        val fribbCommit = ((shas["fribb"] as? Map<*, *>)?.get("commit") as? String)
        val scudleeCommit = ((shas["scudlee"] as? Map<*, *>)?.get("commit") as? String)
        Generator.run(Generator.Args(
            fribbInput = java.io.File(args[0]),
            scudleeInput = java.io.File(args[1]),
            overlayInput = java.io.File(args[2]),
            assetOutput = java.io.File(args[4]),
            provenanceOutput = java.io.File(args[5]),
            fribbUrl = args[6], fribbCommit = fribbCommit,
            scudleeUrl = args[7], scudleeCommit = scudleeCommit
        ))
    }
}

object CheckMain {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "expected 1 arg: assetPath" }
        val file = java.io.File(args[0])
        check(file.exists()) { "anime mapping asset missing: ${file.absolutePath}" }
        val moshi = com.squareup.moshi.Moshi.Builder().build()
        val asset = com.nexio.animemap.model.NexioAnimeMapJsonAdapter(moshi)
            .fromJson(file.readText())
            ?: error("failed to parse anime mapping asset at ${file.absolutePath}")
        check(asset.schemaVersion == 2) {
            "expected schemaVersion=2 but got ${asset.schemaVersion}"
        }
        check(asset.identityRecordsByKitsu.isNotEmpty()) {
            "anime mapping asset has zero identity records"
        }
        println("anime mapping asset OK: ${asset.counts.identityRecords} identity, ${asset.counts.episodeMappingRecords} mapping records")
    }
}
