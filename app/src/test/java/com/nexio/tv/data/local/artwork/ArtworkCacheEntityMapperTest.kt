package com.nexio.tv.data.local.artwork

import com.google.gson.Gson
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkAssetRecord
import com.nexio.tv.core.artwork.ArtworkAssetRecordJsonCodec
import com.nexio.tv.core.artwork.ArtworkDecision
import com.nexio.tv.core.artwork.ArtworkDecisionJsonCodec
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.PersistedArtworkCandidate
import com.nexio.tv.core.artwork.PersistedProviderTemplate
import com.nexio.tv.core.artwork.RejectedArtworkCandidate
import com.nexio.tv.core.integration.IntegrationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkCacheEntityMapperTest {
    private val decisionMapper = ArtworkDecisionEntityMapper(ArtworkDecisionJsonCodec(Gson()))
    private val assetRecordMapper = ArtworkAssetRecordEntityMapper(ArtworkAssetRecordJsonCodec(Gson()))

    @Test
    fun `decision mapper round trips domain decision`() {
        val decision = decision()

        val entity = decisionMapper.toEntity(decision)
        val restored = decisionMapper.toDomain(entity)

        assertEquals("preview", entity.ownerType)
        assertNull(entity.ownerContentId)
        assertEquals("preview-item-a", entity.ownerItemKey)
        assertEquals("payload-hash-a", entity.ownerSourcePayloadHash)
        assertEquals("RPDB", entity.selectedProviderKey)
        assertEquals("PREMIUM", entity.selectedSourceRole)
        assertEquals(decision, restored)
    }

    @Test
    fun `asset record mapper round trips domain record`() {
        val record = assetRecord()

        val entity = assetRecordMapper.toEntity(record)
        val restored = assetRecordMapper.toDomain(entity)

        assertEquals("RAIL_PREVIEW", entity.providerKey)
        assertEquals("asset-a", entity.assetKey)
        assertEquals("decision-a", entity.decisionKey)
        assertEquals(record, restored)
    }

    @Test
    fun `provider key mapping covers special indexed providers`() {
        val addonDecision = decision().let { base ->
            base.copy(
                selectedCandidate = base.selectedCandidate.copy(provider = ArtworkProviderId.AddonPreview)
            )
        }
        val placeholderAssetRecord = assetRecord().copy(provider = ArtworkProviderId.Placeholder)

        assertEquals("ADDON_PREVIEW", decisionMapper.toEntity(addonDecision).selectedProviderKey)
        assertEquals("PLACEHOLDER", assetRecordMapper.toEntity(placeholderAssetRecord).providerKey)
    }

    @Test
    fun `decision mapper returns null for malformed payload`() {
        val entity = decisionMapper.toEntity(decision()).copy(payloadJson = "[1,2,3]")

        assertNull(decisionMapper.toDomain(entity))
    }

    @Test
    fun `asset record mapper returns null for malformed payload`() {
        val entity = assetRecordMapper.toEntity(assetRecord()).copy(payloadJson = "{bad json")

        assertNull(assetRecordMapper.toDomain(entity))
    }

    private fun decision(): ArtworkDecision =
        ArtworkDecision(
            decisionKey = ArtworkDecisionKey("decision-a"),
            ownerKey = ArtworkOwnerKey.PreviewItem(
                itemKey = "preview-item-a",
                sourcePayloadHash = "payload-hash-a"
            ),
            canonicalContentId = "imdb-tt0137523",
            imageType = ArtworkType.POSTER,
            selectedCandidate = PersistedArtworkCandidate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                sourceRole = ArtworkSourceRole.PREMIUM,
                sourceHash = "source-hash-a",
                redactedSourceForTrace = "https://example.test/<redacted>",
                providerTemplate = PersistedProviderTemplate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                    imageType = ArtworkType.POSTER,
                    idType = "imdb",
                    mediaId = "tt0137523",
                    providerPathHash = "provider-path-hash-a",
                    settingsHash = "settings-a",
                    credentialHash = "credential-a",
                    imageLanguage = "en",
                    policyVersion = 7,
                    pathParams = mapOf("size" to "poster")
                ),
                priority = 10
            ),
            rejectedCandidates = listOf(
                RejectedArtworkCandidate(
                    provider = ArtworkProviderId.Placeholder,
                    sourceRole = ArtworkSourceRole.PLACEHOLDER,
                    reason = "selected-premium",
                    priority = 1
                )
            ),
            policyVersion = 7,
            imageLanguage = "en",
            settingsHash = "settings-a",
            credentialHash = "credential-a",
            createdAtMs = 1000,
            expiresAtMs = 2000,
            staleUntilMs = 2500
        )

    private fun assetRecord(): ArtworkAssetRecord =
        ArtworkAssetRecord(
            assetKey = ArtworkAssetKey("asset-a"),
            decisionKey = ArtworkDecisionKey("decision-a"),
            provider = ArtworkProviderId.RailPreview,
            imageType = ArtworkType.BACKDROP,
            imageLanguage = "en",
            relativePath = "artwork-assets/rail/backdrop/asset-a.webp",
            mimeType = "image/webp",
            byteCount = 1234,
            sourceHash = "source-hash-a",
            policyVersion = 7,
            fetchedAtMs = 1000,
            expiresAtMs = 2000,
            staleUntilMs = 2500
        )
}
