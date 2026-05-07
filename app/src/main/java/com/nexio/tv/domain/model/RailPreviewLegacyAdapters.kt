package com.nexio.tv.domain.model

fun MetaPreview.toLegacyRailItemPreview(
    railId: String,
    railSource: RailSource = firstPaintRailSource ?: RailSource.ADDON_CATALOG,
    sourceProvider: ProviderId? = firstPaintSourceProvider,
    sourcePayloadHash: String? = null,
    generatedAtMs: Long = 0L,
    sourcePayloadQuality: SourcePayloadQuality = SourcePayloadQuality.DISPLAY_BASIC
): RailItemPreview {
    val sourceItemId = firstPaintSourceItemId ?: id
    val effectiveSourcePayloadHash = sourcePayloadHash
        ?: legacyRailPreviewSourcePayloadHash(railId = railId, itemId = id)
    val ratingText = imdbRating?.let { formatRailPreviewRatingText(it) }

    return RailItemPreview(
        railId = railId,
        railSource = railSource,
        sourceProvider = sourceProvider,
        sourceItemId = sourceItemId,
        itemType = type,
        stableIds = firstPaintStableIds.mergeConservative(deriveStableIdsFromPrefixedIds(id, sourceItemId)),
        display = RailDisplaySeed(
            title = name,
            releaseDate = releaseInfo,
            overview = description,
            runtimeText = runtime,
            genres = genres,
            posterUrl = poster,
            posterShape = posterShape,
            backdropUrl = background,
            logoUrl = logo,
            rating = ratingText?.let { RatingSeed(provider = ratingSource.toProviderId(), value = it.toDouble()) },
            ratingText = ratingText,
            trailerHint = trailerYtIds.firstOrNull()?.let { TrailerHint.YouTube(it) }
        ),
        sourcePayloadQuality = sourcePayloadQuality,
        sourcePayloadHash = effectiveSourcePayloadHash,
        generatedAtMs = generatedAtMs,
        hydrationState = RailHydrationState.PREVIEW_ONLY
    )
}

fun List<MetaPreview>.toLegacyRailItemPreviews(
    railId: String,
    railSource: RailSource = firstOrNull()?.firstPaintRailSource ?: RailSource.ADDON_CATALOG,
    sourceProvider: ProviderId? = firstOrNull()?.firstPaintSourceProvider,
    generatedAtMs: Long = 0L
): List<RailItemPreview> {
    return map { preview ->
        preview.toLegacyRailItemPreview(
            railId = railId,
            railSource = preview.firstPaintRailSource ?: railSource,
            sourceProvider = preview.firstPaintSourceProvider ?: sourceProvider,
            generatedAtMs = generatedAtMs
        )
    }
}

private fun legacyRailPreviewSourcePayloadHash(railId: String, itemId: String): String {
    return "legacy:$railId:$itemId"
}

private fun ProviderIds.mergeConservative(derived: ProviderIds): ProviderIds {
    return ProviderIds(
        imdb = imdb ?: derived.imdb,
        tmdb = tmdb ?: derived.tmdb,
        tvdb = tvdb ?: derived.tvdb,
        trakt = trakt ?: derived.trakt,
        simkl = simkl ?: derived.simkl,
        kitsu = kitsu ?: derived.kitsu,
        slug = slug ?: derived.slug,
        mal = mal ?: derived.mal,
        anilist = anilist ?: derived.anilist,
        anidb = anidb ?: derived.anidb
    )
}

private fun deriveStableIdsFromPrefixedIds(vararg ids: String?): ProviderIds {
    val values = ids.filterNotNull()

    return ProviderIds(
        imdb = values.firstPrefixedId("imdb")?.takeIf { it.isStrictImdbId() }
            ?: values.firstOrNull { it.isStrictImdbId() },
        tmdb = values.firstPrefixedId("tmdb"),
        tvdb = values.firstPrefixedId("tvdb"),
        trakt = values.firstPrefixedId("trakt"),
        simkl = values.firstPrefixedId("simkl"),
        kitsu = values.firstPrefixedId("kitsu")
    )
}

private fun List<String>.firstPrefixedId(prefix: String): String? {
    val expectedPrefix = "$prefix:"

    return firstNotNullOfOrNull { value ->
        value.takeIf { it.startsWith(expectedPrefix) }
            ?.removePrefix(expectedPrefix)
            ?.substringAfterLast(":")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}

private fun String.isStrictImdbId(): Boolean {
    return matches(Regex("tt\\d+"))
}

private fun TitleRatingSource?.toProviderId(): ProviderId {
    return when (this) {
        TitleRatingSource.TMDB -> ProviderId.TMDB
        TitleRatingSource.IMDB,
        null -> ProviderId.IMDB
    }
}

private fun formatRailPreviewRatingText(rating: Float): String =
    RatingDisplayFormatter.formatTitleRating(rating)
