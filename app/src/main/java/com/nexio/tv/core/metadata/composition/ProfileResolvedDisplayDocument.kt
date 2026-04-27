package com.nexio.tv.core.metadata.composition

data class ProfileResolvedDisplayDocument(
    val profileId: Int,
    val global: GlobalMetadataDocument,
    val overlay: ProfileMetadataOverlay,
    val artworkDecision: ArtworkDecision,
    val trace: List<FieldTrace>
) {
    init {
        require(profileId > 0) { "ProfileResolvedDisplayDocument.profileId must be positive" }
        require(profileId == overlay.profileId) {
            "ProfileResolvedDisplayDocument.profileId must equal overlay.profileId"
        }
    }
}

data class ArtworkDecision(val posterUrl: String?, val backgroundUrl: String?, val provider: String)
