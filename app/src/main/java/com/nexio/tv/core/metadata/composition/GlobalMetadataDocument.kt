package com.nexio.tv.core.metadata.composition

data class GlobalMetadataDocument(
    val contentId: String,
    val provider: String,
    val language: String,
    val title: String?,
    val overview: String?,
    val runtimeMinutes: Int?,
    val episodeMetadata: List<EpisodeMetadata>,
    val artworkCandidates: List<ArtworkCandidate>,
    val fieldTrace: List<FieldTrace>
) {
    init {
        require(contentId.isNotBlank()) { "GlobalMetadataDocument.contentId must not be blank" }
        require(provider.isNotBlank()) { "GlobalMetadataDocument.provider must not be blank" }
        require(language.isNotBlank()) { "GlobalMetadataDocument.language must not be blank" }
    }
}

data class EpisodeMetadata(
    val season: Int,
    val number: Int,
    val title: String?,
    val overview: String?
)

data class ArtworkCandidate(
    val kind: String,
    val url: String,
    val language: String
)

data class FieldTrace(
    val field: String,
    val sourceProvider: String,
    val sourceLanguage: String?
)
