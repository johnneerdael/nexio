package com.nexio.tv.data.local.artwork

import com.google.gson.JsonParser
import com.nexio.tv.core.artwork.ArtworkAssetRecord
import com.nexio.tv.core.artwork.ArtworkAssetRecordJsonCodec

class ArtworkAssetRecordEntityMapper(private val codec: ArtworkAssetRecordJsonCodec) {
    fun toEntity(record: ArtworkAssetRecord): ArtworkAssetRecordEntity =
        ArtworkAssetRecordEntity(
            assetKey = record.assetKey.value,
            decisionKey = record.decisionKey?.value,
            providerKey = record.provider.toArtworkProviderKey(),
            imageType = record.imageType.name,
            imageLanguage = record.imageLanguage,
            relativePath = record.relativePath,
            mimeType = record.mimeType,
            byteCount = record.byteCount,
            sourceHash = record.sourceHash,
            policyVersion = record.policyVersion,
            fetchedAtMs = record.fetchedAtMs,
            expiresAtMs = record.expiresAtMs,
            staleUntilMs = record.staleUntilMs,
            payloadJson = codec.toRecordJson(record).toString()
        )

    fun toDomain(entity: ArtworkAssetRecordEntity): ArtworkAssetRecord? =
        runCatching {
            JsonParser.parseString(entity.payloadJson)
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let(codec::fromRecordJson)
        }.getOrNull()
}
