package com.nexio.tv.ui.components

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.ui.screens.home.ContinueWatchingItem
import com.nexio.tv.ui.screens.home.NextUpInfo

/**
 * Per-surface projection for Continue Watching rows. Two variants mirror
 * [ContinueWatchingItem]:
 *
 *  - [InProgress]: a resume row backed by [WatchProgress] (positionMs,
 *    durationMs, episode info), display fields drawn from
 *    [ResolvedDisplayItem].
 *  - [NextUp]: a Next-Up row backed by [NextUpInfo] (season/episode
 *    metadata, last-watched timestamp), display fields drawn from
 *    [ResolvedDisplayItem].
 *
 * Per CLAUDE.md rule #1, surfaces consume [ResolvedDisplayItem] (or an
 * approved per-surface projection) rather than raw `MetaPreview`. Typed
 * `posterRef` / `backdropRef` / `logoRef` slots are kept strict (no
 * cross-type fallback).
 */
@Immutable
sealed class ContinueWatchingResolvedDisplayItem {
    abstract val itemKey: String
    abstract val contentId: String
    abstract val title: String?
    abstract val posterRef: ArtworkDisplayRef?
    abstract val backdropRef: ArtworkDisplayRef?
    abstract val logoRef: ArtworkDisplayRef?
    abstract val rating: TitleRating?

    /**
     * Resume row. [source] carries CW-specific fields not on [ResolvedDisplayItem]
     * (episode thumbnail/description, genres, releaseInfo, canonicalKey,
     * streamFetchVideoId).
     */
    @Immutable
    data class InProgress(
        override val itemKey: String,
        override val contentId: String,
        override val title: String?,
        override val posterRef: ArtworkDisplayRef?,
        override val backdropRef: ArtworkDisplayRef?,
        override val logoRef: ArtworkDisplayRef?,
        override val rating: TitleRating?,
        val source: ContinueWatchingItem.InProgress
    ) : ContinueWatchingResolvedDisplayItem() {
        val progress: WatchProgress get() = source.progress
    }

    /** Next-Up row. [source] carries the original [NextUpInfo]. */
    @Immutable
    data class NextUp(
        override val itemKey: String,
        override val contentId: String,
        override val title: String?,
        override val posterRef: ArtworkDisplayRef?,
        override val backdropRef: ArtworkDisplayRef?,
        override val logoRef: ArtworkDisplayRef?,
        override val rating: TitleRating?,
        val source: ContinueWatchingItem.NextUp
    ) : ContinueWatchingResolvedDisplayItem() {
        val info: NextUpInfo get() = source.info
    }

    /**
     * Adapter from the per-surface resolved projection back to the legacy domain
     * [ContinueWatchingItem]. Composables consume the resolved projection for
     * rendering, but ViewModel callbacks still take the legacy type — this returns
     * `source` directly so the boundary stays backward-compatible without leaking
     * the resolved type into VM signatures.
     *
     * TODO(Plan B Surface 4 cleanup): remove once VM callbacks accept
     * [ContinueWatchingResolvedDisplayItem] directly.
     */
    fun toContinueWatchingItem(): ContinueWatchingItem = when (this) {
        is InProgress -> source
        is NextUp -> source
    }

    companion object {
        fun fromInProgress(
            resolved: ResolvedDisplayItem,
            source: ContinueWatchingItem.InProgress
        ): InProgress = InProgress(
            itemKey = resolved.itemKey,
            contentId = resolved.contentId,
            title = resolved.display.title,
            posterRef = resolved.artwork.poster,
            backdropRef = resolved.artwork.backdrop,
            logoRef = resolved.artwork.logo,
            rating = resolved.rating,
            source = source.withResolvedDisplayMetadata(resolved)
        )

        fun fromNextUp(
            resolved: ResolvedDisplayItem,
            source: ContinueWatchingItem.NextUp
        ): NextUp = NextUp(
            itemKey = resolved.itemKey,
            contentId = resolved.contentId,
            title = resolved.display.title,
            posterRef = resolved.artwork.poster,
            backdropRef = resolved.artwork.backdrop,
            logoRef = resolved.artwork.logo,
            rating = resolved.rating,
            source = source.withResolvedDisplayMetadata(resolved)
        )

        /**
         * Best-effort projection from the legacy [ContinueWatchingItem.InProgress]
         * alone, used when no matching [ResolvedDisplayItem] is on the home surface.
         *
         * Trakt-sourced resume entries (and watched items that have since fallen off
         * every visible rail) often have no home-surface entry to join against. The
         * resolved authority can't supply typed artwork in that case, so this factory
         * wraps the legacy poster/backdrop/logo URL strings as
         * [ArtworkDisplayRef.LegacyString] — strict-typed but with the URL string
         * as the source role. Without this fallback, those CW items disappear
         * silently from the rendered list.
         */
        fun fromInProgressLegacy(
            source: ContinueWatchingItem.InProgress
        ): InProgress = InProgress(
            itemKey = homeDisplayItemKey(source.progress.contentType, source.progress.contentId),
            contentId = source.progress.contentId,
            title = source.progress.name,
            posterRef = source.progress.poster.toLegacyArtworkRefOrNull(ArtworkType.POSTER),
            backdropRef = source.progress.backdrop.toLegacyArtworkRefOrNull(ArtworkType.BACKDROP),
            logoRef = source.progress.logo.toLegacyArtworkRefOrNull(ArtworkType.LOGO),
            rating = null,
            source = source
        )

        /**
         * Best-effort projection from the legacy [ContinueWatchingItem.NextUp]
         * alone — same rationale as [fromInProgressLegacy]. Reads the
         * [NextUpInfo.displayPoster] / [NextUpInfo.displayBackdrop] /
         * [NextUpInfo.displayLogo] getters which already apply the
         * `displayMetadata ?: raw` fallback the legacy code uses.
         */
        fun fromNextUpLegacy(
            source: ContinueWatchingItem.NextUp
        ): NextUp = NextUp(
            itemKey = homeDisplayItemKey(source.info.contentType, source.info.contentId),
            contentId = source.info.contentId,
            title = source.info.name,
            posterRef = source.info.displayPoster.toLegacyArtworkRefOrNull(ArtworkType.POSTER),
            backdropRef = source.info.displayBackdrop.toLegacyArtworkRefOrNull(ArtworkType.BACKDROP),
            logoRef = source.info.displayLogo.toLegacyArtworkRefOrNull(ArtworkType.LOGO),
            rating = null,
            source = source
        )
    }
}

private fun ContinueWatchingItem.InProgress.withResolvedDisplayMetadata(
    resolved: ResolvedDisplayItem
): ContinueWatchingItem.InProgress {
    val merged = displayMetadata.mergeResolvedDisplay(resolved)
    return copy(displayMetadata = merged)
}

private fun ContinueWatchingItem.NextUp.withResolvedDisplayMetadata(
    resolved: ResolvedDisplayItem
): ContinueWatchingItem.NextUp {
    val merged = info.displayMetadata.mergeResolvedDisplay(resolved)
    return copy(info = info.copy(
        displayMetadata = merged,
        name = merged.title ?: info.name,
        poster = merged.displayPoster ?: info.poster,
        backdrop = merged.displayBackdrop ?: info.backdrop,
        logo = merged.displayLogo ?: info.logo,
        episodeDescription = info.episodeDescription ?: merged.description,
        genres = info.genres.ifEmpty { merged.genres },
        releaseInfo = info.releaseInfo ?: merged.releaseInfo
    ))
}

private fun HomeDisplayMetadata?.mergeResolvedDisplay(
    resolved: ResolvedDisplayItem
): HomeDisplayMetadata {
    val current = this
    return HomeDisplayMetadata(
        title = resolved.display.title ?: current?.title,
        logo = resolved.artwork.logo.toLegacyArtworkString() ?: current?.logo,
        description = resolved.display.overview ?: current?.description,
        genres = resolved.display.genres.ifEmpty { current?.genres.orEmpty() },
        releaseInfo = resolved.display.releaseDate ?: resolved.display.year?.toString() ?: current?.releaseInfo,
        runtime = resolved.display.runtimeText ?: current?.runtime,
        imdbRating = resolved.rating?.value?.toFloat() ?: current?.imdbRating,
        ratingSource = resolved.rating?.source ?: current?.ratingSource,
        tomatoesRating = resolved.display.tomatoesRating ?: current?.tomatoesRating,
        originalLanguage = current?.originalLanguage,
        imdbId = resolved.imdbId ?: resolved.stableIds.imdb ?: current?.imdbId,
        poster = resolved.artwork.poster.toLegacyArtworkString() ?: current?.poster,
        posterProviderTag = current?.posterProviderTag,
        backdrop = resolved.artwork.backdrop.toLegacyArtworkString() ?: current?.backdrop,
        thumbnail = resolved.artwork.thumbnail.toLegacyArtworkString() ?: current?.thumbnail,
        artwork = resolved.artwork
    )
}

private fun String?.toLegacyArtworkRefOrNull(type: ArtworkType): ArtworkDisplayRef? {
    val v = this?.takeIf { it.isNotBlank() } ?: return null
    return ArtworkDisplayRef.LegacyString(value = v, imageType = type, trace = ArtworkTrace.empty())
}
