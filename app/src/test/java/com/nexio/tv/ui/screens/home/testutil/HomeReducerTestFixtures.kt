package com.nexio.tv.ui.screens.home.testutil

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedSlot
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource

object HomeReducerTestFixtures {
    fun emptySlots(): ResolvedDisplayFieldSlots = ResolvedDisplayFieldSlots(
        title = ResolvedSlot.empty(0L),
        originalTitle = ResolvedSlot.empty(0L),
        overview = ResolvedSlot.empty(0L),
        genres = ResolvedSlot.empty(0L),
        releaseInfo = ResolvedSlot.empty(0L),
        runtime = ResolvedSlot.empty(0L),
        rating = ResolvedSlot.empty(0L),
        poster = ResolvedSlot.empty(0L),
        backdrop = ResolvedSlot.empty(0L),
        logo = ResolvedSlot.empty(0L),
        thumbnail = ResolvedSlot.empty(0L),
        posterProviderTag = ResolvedSlot.empty(0L)
    )

    fun artworkRef(value: String, type: ArtworkType): ArtworkDisplayRef =
        ArtworkDisplayRef.LegacyString(value, type, ArtworkTrace.empty())

    fun posterSlot(rank: DisplaySourceRank, value: String?, provider: String = "TEST"): ResolvedSlot<ArtworkDisplayRef> =
        ResolvedSlot(
            value = value?.let { artworkRef(it, ArtworkType.POSTER) },
            rank = if (value == null && rank.ordinal < DisplaySourceRank.STALE_RESOLVED.ordinal) DisplaySourceRank.EMPTY else rank,
            provider = provider, role = "TEST", updatedAtMs = 0L, expiresAtMs = null, trace = emptyList()
        )

    fun backdropSlot(rank: DisplaySourceRank, value: String?, provider: String = "TEST"): ResolvedSlot<ArtworkDisplayRef> =
        ResolvedSlot(
            value = value?.let { artworkRef(it, ArtworkType.BACKDROP) },
            rank = if (value == null && rank.ordinal < DisplaySourceRank.STALE_RESOLVED.ordinal) DisplaySourceRank.EMPTY else rank,
            provider = provider, role = "TEST", updatedAtMs = 0L, expiresAtMs = null, trace = emptyList()
        )

    fun logoSlot(rank: DisplaySourceRank, value: String?, provider: String = "TEST"): ResolvedSlot<ArtworkDisplayRef> =
        ResolvedSlot(
            value = value?.let { artworkRef(it, ArtworkType.LOGO) },
            rank = if (value == null && rank.ordinal < DisplaySourceRank.STALE_RESOLVED.ordinal) DisplaySourceRank.EMPTY else rank,
            provider = provider, role = "TEST", updatedAtMs = 0L, expiresAtMs = null, trace = emptyList()
        )

    fun stringSlot(rank: DisplaySourceRank, value: String?, provider: String = "TEST"): ResolvedSlot<String> =
        ResolvedSlot(
            value = value,
            rank = if (value == null && rank.ordinal < DisplaySourceRank.STALE_RESOLVED.ordinal) DisplaySourceRank.EMPTY else rank,
            provider = provider, role = "TEST", updatedAtMs = 0L, expiresAtMs = null, trace = emptyList()
        )

    fun ratingSlot(rank: DisplaySourceRank, value: Double?, provider: String = "TEST"): ResolvedSlot<TitleRating> =
        ResolvedSlot(
            value = value?.let { TitleRating(it, TitleRatingSource.IMDB) },
            rank = if (value == null && rank.ordinal < DisplaySourceRank.STALE_RESOLVED.ordinal) DisplaySourceRank.EMPTY else rank,
            provider = provider, role = "TEST", updatedAtMs = 0L, expiresAtMs = null, trace = emptyList()
        )

    fun slotsWith(
        poster: ResolvedSlot<ArtworkDisplayRef>? = null,
        backdrop: ResolvedSlot<ArtworkDisplayRef>? = null,
        logo: ResolvedSlot<ArtworkDisplayRef>? = null,
        title: ResolvedSlot<String>? = null,
        rating: ResolvedSlot<TitleRating>? = null
    ): ResolvedDisplayFieldSlots {
        val empty = emptySlots()
        return empty.copy(
            poster = poster ?: empty.poster,
            backdrop = backdrop ?: empty.backdrop,
            logo = logo ?: empty.logo,
            title = title ?: empty.title,
            rating = rating ?: empty.rating
        )
    }
}
