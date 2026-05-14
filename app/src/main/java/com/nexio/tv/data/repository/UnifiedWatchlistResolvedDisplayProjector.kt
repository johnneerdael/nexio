package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.UnifiedWatchlistMembership
import com.nexio.tv.domain.model.UnifiedWatchlistRowItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Singleton
class UnifiedWatchlistResolvedDisplayProjector @Inject constructor(
    private val resolvedDisplaySurfaceRepository: ResolvedDisplaySurfaceRepository
) {
    fun observeRows(
        profileId: Int,
        memberships: List<UnifiedWatchlistMembership>
    ): Flow<List<UnifiedWatchlistRowItem>> =
        resolvedDisplaySurfaceRepository.observeUnifiedWatchlistSurface(profileId)
            .map { resolvedItems -> project(memberships, resolvedItems) }
            .distinctUntilChanged()

    fun observeRows(
        profileId: Int,
        memberships: Flow<List<UnifiedWatchlistMembership>>
    ): Flow<List<UnifiedWatchlistRowItem>> =
        combine(
            memberships,
            resolvedDisplaySurfaceRepository.observeUnifiedWatchlistSurface(profileId)
        ) { latestMemberships, resolvedItems ->
            project(latestMemberships, resolvedItems)
        }.distinctUntilChanged()

    private fun project(
        memberships: List<UnifiedWatchlistMembership>,
        resolvedItems: List<ResolvedDisplayItem>
    ): List<UnifiedWatchlistRowItem> {
        if (memberships.isEmpty() || resolvedItems.isEmpty()) return emptyList()
        val resolvedByAlias = HashMap<String, ResolvedDisplayItem>(resolvedItems.size * 4)
        for (i in resolvedItems.indices) {
            val item = resolvedItems[i]
            val aliases = item.aliases()
            for (aliasIndex in aliases.indices) {
                resolvedByAlias.putIfAbsent(aliases[aliasIndex], item)
            }
        }

        val rows = ArrayList<UnifiedWatchlistRowItem>(memberships.size)
        for (i in memberships.indices) {
            val membership = memberships[i]
            val resolved = membership.aliases().firstNotNullOfOrNull { resolvedByAlias[it] }
            if (resolved != null) {
                rows += UnifiedWatchlistRowItem(
                    membership = membership,
                    displayItem = resolved
                )
            }
        }
        return rows
    }

    private fun ResolvedDisplayItem.aliases(): List<String> {
        val typeKey = itemType.toAuthorityTypeKey()
        val aliases = ArrayList<String>(8)
        aliases += itemKey
        canonicalId?.let { id ->
            canonicalProvider?.let { provider -> aliases += "$typeKey:${provider.lowercase()}:$id" }
        }
        imdbId?.let { aliases += "$typeKey:imdb:${it.lowercase()}" }
        stableIds.imdb?.let { aliases += "$typeKey:imdb:${it.lowercase()}" }
        stableIds.tmdb?.let { aliases += "$typeKey:tmdb:$it" }
        stableIds.trakt?.let { aliases += "$typeKey:trakt:$it" }
        stableIds.simkl?.let { aliases += "$typeKey:simkl:$it" }
        stableIds.tvdb?.let { aliases += "$typeKey:tvdb:$it" }
        return aliases.distinct()
    }

    private fun UnifiedWatchlistMembership.aliases(): List<String> {
        val typeKey = contentType.toAuthorityTypeKey()
        val aliases = ArrayList<String>(8)
        aliases += authorityKey
        imdbId?.let { aliases += "$typeKey:imdb:${it.lowercase()}" }
        tmdbId?.let { aliases += "$typeKey:tmdb:$it" }
        traktId?.let { aliases += "$typeKey:trakt:$it" }
        simklId?.let { aliases += "$typeKey:simkl:$it" }
        tvdbId?.let { aliases += "$typeKey:tvdb:$it" }
        return aliases.distinct()
    }

    private fun ContentType.toAuthorityTypeKey(): String =
        when (this) {
            ContentType.SERIES,
            ContentType.TV -> "series"
            ContentType.MOVIE -> "movie"
            else -> toApiString()
        }
}
