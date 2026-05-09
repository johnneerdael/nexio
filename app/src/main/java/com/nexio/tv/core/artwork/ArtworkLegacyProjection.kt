package com.nexio.tv.core.artwork

/**
 * Projects an [ArtworkDisplayRef] to its persisted-string form for downstream consumers
 * (overlays, snapshots, MetaPreview legacy strings).
 *
 * For [ArtworkDisplayRef.RuntimeAsset] we always emit `nexio-artwork://decision/<key>`,
 * never `nexio-artwork://asset/<key>`. Rationale: asset URIs are read-only at the Coil
 * fetcher layer ([com.nexio.tv.core.image.NexioArtworkFetcher] only calls
 * `getExistingFile(assetKey)` and `getOrRehydrateAsset(assetKey)` — neither materializes
 * bytes if the originating decision was never `getOrFetch`-ed). [MetadataArtworkDecisionResolver]
 * constructs `RuntimeAsset(decisionKey, assetKey)` from a URL hash without ever fetching,
 * so an asset URI in a persisted overlay points at bytes nobody ever wrote, and the URI
 * is permanently dangling.
 *
 * Decision URIs are self-healing: the fetcher's decision branch calls
 * `getOrFetchDecision → fetchInternal → persistAssetRecordBestEffort`, materializing
 * bytes on first paint AND seeding the asset record store entry that
 * [com.nexio.tv.core.artwork.ArtworkAssetRepository.getOrRehydrateAsset] needs to
 * recover from later eviction.
 */
fun ArtworkDisplayRef?.toLegacyArtworkString(): String? =
    when (this) {
        null -> null
        is ArtworkDisplayRef.RuntimeAsset ->
            "nexio-artwork://decision/${decisionKey.value}"
        is ArtworkDisplayRef.Placeholder ->
            "nexio-placeholder://${placeholderType.name.lowercase()}"
        is ArtworkDisplayRef.LegacyString ->
            value
    }
