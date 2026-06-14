package com.nexio.tv.core.artwork

/**
 * Projects an [ArtworkDisplayRef] to its persisted-string form for downstream consumers
 * (overlays, snapshots, MetaPreview legacy strings).
 *
 * For [ArtworkDisplayRef.RuntimeAsset], prefer `nexio-artwork://asset/<key>` when
 * the selected asset key is known. Warm startup snapshots should point directly at
 * stable cached asset bytes; if an asset file is evicted, the fetcher can rehydrate
 * it through the persisted asset-record index. Refs without a selected asset key
 * still fall back to the decision URI so the self-healing decision path can run.
 */
fun ArtworkDisplayRef?.toLegacyArtworkString(): String? =
    when (this) {
        null -> null
        is ArtworkDisplayRef.RuntimeAsset ->
            assetKey?.let { "nexio-artwork://asset/${it.value}" }
                ?: "nexio-artwork://decision/${decisionKey.value}"
        is ArtworkDisplayRef.Placeholder ->
            "nexio-placeholder://${placeholderType.name.lowercase()}"
        is ArtworkDisplayRef.LegacyString ->
            value
    }
