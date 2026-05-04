package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.toLegacyArtworkString
import org.junit.Assert.assertEquals
import org.junit.Test

class FieldResolverArtworkDecisionTest {
    private val resolver = FieldResolver()

    @Test
    fun `resolved document poster string is derived from artwork ref`() {
        val posterRef = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("decision"),
            assetKey = ArtworkAssetKey("asset"),
            imageType = ArtworkType.POSTER,
            selectedProvider = null,
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty()
        )
        val primary = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue("Fight Club", FieldOwner.PRIMARY),
                ResolvedField.POSTER to FieldValue(posterRef, FieldOwner.ARTWORK, SourceRole.ARTWORK)
            )
        )

        val document = resolver.resolve(primary, emptyList(), requestContentId = "tt0137523")

        assertEquals(posterRef, document.artwork.poster)
        assertEquals(posterRef.toLegacyArtworkString(), document.poster)
        assertEquals("Fight Club", document.title)
    }

    @Test
    fun `resolved backdrop and logo strings are derived from artwork refs`() {
        val backdropRef = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("backdrop-decision"),
            assetKey = ArtworkAssetKey("backdrop-asset"),
            imageType = ArtworkType.BACKDROP,
            selectedProvider = null,
            sourceRole = ArtworkSourceRole.PRIMARY,
            trace = ArtworkTrace.empty()
        )
        val logoRef = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("logo-decision"),
            assetKey = ArtworkAssetKey("logo-asset"),
            imageType = ArtworkType.LOGO,
            selectedProvider = null,
            sourceRole = ArtworkSourceRole.PRIMARY,
            trace = ArtworkTrace.empty()
        )
        val primary = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.BACKDROP to FieldValue(backdropRef, FieldOwner.ARTWORK, SourceRole.ARTWORK),
                ResolvedField.LOGO to FieldValue(logoRef, FieldOwner.ARTWORK, SourceRole.ARTWORK)
            )
        )

        val document = resolver.resolve(primary, emptyList(), requestContentId = "tt0137523")

        assertEquals(backdropRef, document.artwork.backdrop)
        assertEquals(logoRef, document.artwork.logo)
        assertEquals(backdropRef.toLegacyArtworkString(), document.backdrop)
        assertEquals(logoRef.toLegacyArtworkString(), document.logo)
    }

    @Test
    fun `legacy string poster remains compatible when no artwork ref exists`() {
        val primary = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.POSTER to FieldValue("https://image.tmdb.org/t/p/w500/poster.jpg", FieldOwner.PRIMARY)
            )
        )

        val document = resolver.resolve(primary, emptyList(), requestContentId = "tt0137523")

        assertEquals(null, document.artwork.poster)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", document.poster)
    }
}
