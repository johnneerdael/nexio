package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.homeDisplayItemKey
import java.util.Locale

internal object HomeArtworkOverlayKeys {
    fun aliasesFor(
        rowItemKey: String,
        contentId: String,
        itemType: String,
        providerIds: ProviderIds,
        canonicalProvider: ProviderId?,
        canonicalId: String?
    ): Set<String> = buildSet {
        rowItemKey.trim().takeIf { it.isNotBlank() }?.let(::add)
        contentId.trim().takeIf { it.isNotBlank() }?.let { id ->
            add(homeDisplayItemKey(itemType, id))
            addTypedProviderAlias(itemType, id)
        }
        addStableAliases(itemType, providerIds)
        if (canonicalProvider != null && !canonicalId.isNullOrBlank()) {
            addStableAliases(
                itemType,
                when (canonicalProvider) {
                    ProviderId.TMDB -> ProviderIds(tmdb = canonicalId)
                    ProviderId.TVDB -> ProviderIds(tvdb = canonicalId)
                    ProviderId.IMDB -> ProviderIds(imdb = canonicalId)
                    ProviderId.TRAKT -> ProviderIds(trakt = canonicalId)
                    ProviderId.KITSU -> ProviderIds(kitsu = canonicalId)
                    else -> ProviderIds()
                }
            )
        }
    }

    private fun MutableSet<String>.addTypedProviderAlias(itemType: String, contentId: String) {
        val parts = contentId.trim().split(':').filter { it.isNotBlank() }
        if (parts.size < 2) return
        val provider = parts[0].lowercase(Locale.US)
        val id = when {
            parts.size >= 3 && parts[1].equals("tv", ignoreCase = true) -> parts[2]
            parts.size >= 3 && parts[1].equals("movie", ignoreCase = true) -> parts[2]
            else -> parts[1]
        }
        normalizedTypes(itemType).forEach { type ->
            add("$type:$provider:$id")
        }
    }

    private fun MutableSet<String>.addStableAliases(type: String, ids: ProviderIds) {
        normalizedTypes(type).forEach { normalizedType ->
            ids.imdb?.takeIf { it.isNotBlank() }?.let { add("$normalizedType:imdb:$it") }
            ids.tmdb?.takeIf { it.isNotBlank() }?.let { add("$normalizedType:tmdb:$it") }
            ids.tvdb?.takeIf { it.isNotBlank() }?.let { add("$normalizedType:tvdb:$it") }
            ids.trakt?.takeIf { it.isNotBlank() }?.let { add("$normalizedType:trakt:$it") }
            ids.kitsu?.takeIf { it.isNotBlank() }?.let { add("$normalizedType:kitsu:$it") }
        }
    }

    private fun normalizedTypes(type: String): Set<String> =
        when (type.lowercase(Locale.US)) {
            "series", "tv", "show" -> setOf("series", "tv")
            "movie" -> setOf("movie")
            else -> setOf(type.lowercase(Locale.US))
        }
}
