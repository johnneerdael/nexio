package com.nexio.tv.core.artwork

fun ArtworkDisplayRef?.toLegacyArtworkString(): String? =
    when (this) {
        null -> null
        is ArtworkDisplayRef.RuntimeAsset ->
            assetKey?.let { "nexio-artwork://asset/${it.value}" }
                ?: "nexio-artwork://decision/${decisionKey.value}"
        is ArtworkDisplayRef.Placeholder ->
            "nexio-placeholder://${placeholderType.name.lowercase()}"
    }
