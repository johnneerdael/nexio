package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.toLegacyArtworkString

enum class FirstPaintSource {
    ADDON_META_PREVIEW,
    RAIL_PREVIEW
}

@Immutable
data class MetaPreview(
    val id: String,
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val name: String,
    val poster: String?,
    val posterShape: PosterShape,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val runtime: String? = null,
    val imdbRating: Float?,
    val ratingSource: TitleRatingSource? = TitleRatingSource.IMDB,
    val tomatoesRating: Double? = null,
    val genres: List<String>,
    val trailerYtIds: List<String> = emptyList(),
    @Deprecated(
        message = "Reading `language` for production-language decisions (audio targeting, " +
            "stream filtering, original-language matching) is unsafe. This field has " +
            "historically been overloaded with the user's UI-locale fetch language. " +
            "Use `originalLanguage` instead. The field remains for cosmetic display " +
            "in the detail-screen language badge (HeroSection); see " +
            "docs/superpowers/notes/2026-05-10-original-language-audio-track-bug.md.",
        replaceWith = ReplaceWith("originalLanguage")
    )
    val language: String? = null,
    /**
     * Production language of the title (e.g. `"eng"` for English-original
     * content). Distinct from [language] which is overloaded.
     * See `docs/superpowers/notes/2026-05-10-original-language-audio-track-bug.md`.
     */
    val originalLanguage: String? = null,
    val posterProviderTag: String? = null,
    val firstPaintSource: FirstPaintSource = FirstPaintSource.ADDON_META_PREVIEW,
    val firstPaintSourceProvider: ProviderId? = null,
    val firstPaintStableIds: ProviderIds = ProviderIds(),
    val firstPaintRailSource: RailSource? = null,
    val firstPaintSourceItemId: String? = null,
    @Transient
    val artwork: ArtworkBundle? = null
) {
    val apiType: String
        get() = type.toApiString(rawType)

    val displayPoster: String?
        get() = artwork?.poster.toLegacyArtworkString() ?: poster

    val displayBackground: String?
        get() = artwork?.backdrop.toLegacyArtworkString() ?: background

    val displayLogo: String?
        get() = artwork?.logo.toLegacyArtworkString() ?: logo

    /**
     * Gson bypasses the Kotlin default-constructor mechanism, so it can set [firstPaintSource]
     * to `null` at runtime even though the type is declared non-null. Callers that receive a
     * Gson-deserialized [MetaPreview] (e.g. from disk cache) should run
     * [com.nexio.tv.data.local.MetadataModelSanitizers.sanitizedForCache] before operating on
     * the value. These overrides ensure that `hashCode` and `equals` do not crash on such
     * instances in the meantime.
     */
    override fun hashCode(): Int {
        // Cast to nullable so the Elvis is not flagged as redundant by the compiler.
        // Gson can inject null into non-null fields (firstPaintSource, firstPaintStableIds)
        // when deserializing legacy cached JSON that predates these fields.
        val safeSource = (firstPaintSource as FirstPaintSource?) ?: FirstPaintSource.ADDON_META_PREVIEW
        val safeStableIds = (firstPaintStableIds as ProviderIds?) ?: ProviderIds()
        return java.util.Objects.hash(
            id, type, rawType, name, poster, posterShape, background, logo, description,
            releaseInfo, runtime, imdbRating, ratingSource, tomatoesRating, genres,
            trailerYtIds, language, originalLanguage, posterProviderTag,
            safeSource,
            firstPaintSourceProvider, safeStableIds, firstPaintRailSource, firstPaintSourceItemId,
            artwork
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MetaPreview) return false
        // Cast to nullable so the Elvis is not flagged as redundant by the compiler.
        val effectiveSource = (firstPaintSource as FirstPaintSource?) ?: FirstPaintSource.ADDON_META_PREVIEW
        val otherEffectiveSource = (other.firstPaintSource as FirstPaintSource?) ?: FirstPaintSource.ADDON_META_PREVIEW
        val effectiveStableIds = (firstPaintStableIds as ProviderIds?) ?: ProviderIds()
        val otherEffectiveStableIds = (other.firstPaintStableIds as ProviderIds?) ?: ProviderIds()
        return id == other.id &&
            type == other.type &&
            rawType == other.rawType &&
            name == other.name &&
            poster == other.poster &&
            posterShape == other.posterShape &&
            background == other.background &&
            logo == other.logo &&
            description == other.description &&
            releaseInfo == other.releaseInfo &&
            runtime == other.runtime &&
            imdbRating == other.imdbRating &&
            ratingSource == other.ratingSource &&
            tomatoesRating == other.tomatoesRating &&
            genres == other.genres &&
            trailerYtIds == other.trailerYtIds &&
            language == other.language &&
            originalLanguage == other.originalLanguage &&
            posterProviderTag == other.posterProviderTag &&
            effectiveSource == otherEffectiveSource &&
            firstPaintSourceProvider == other.firstPaintSourceProvider &&
            effectiveStableIds == otherEffectiveStableIds &&
            firstPaintRailSource == other.firstPaintRailSource &&
            firstPaintSourceItemId == other.firstPaintSourceItemId &&
            artwork == other.artwork
    }
}
