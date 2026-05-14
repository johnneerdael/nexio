package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.UnifiedWatchlistMembership
import com.nexio.tv.domain.model.UnifiedWatchlistMembershipConfidence
import com.nexio.tv.domain.model.UnifiedWatchlistSource
import com.nexio.tv.domain.model.UnifiedWatchlistSourceItem
import com.nexio.tv.domain.model.UnifiedWatchlistSourceRef
import java.util.Locale

object UnifiedWatchlistMembershipReducer {
    fun reduce(items: List<UnifiedWatchlistSourceItem>): List<UnifiedWatchlistMembership> {
        if (items.isEmpty()) return emptyList()

        val union = ItemUnion(items.size)
        val strongKeyToIndex = LinkedHashMap<String, Int>()
        val weakKeyToIndex = LinkedHashMap<String, Int>()

        for (i in items.indices) {
            val item = items[i]
            val strongKeys = item.strongKeys()
            for (keyIndex in strongKeys.indices) {
                val key = strongKeys[keyIndex]
                val previous = strongKeyToIndex[key]
                if (previous != null) {
                    union.union(i, previous)
                } else {
                    strongKeyToIndex[key] = i
                }
            }
            if (strongKeys.isEmpty()) {
                item.weakKey()?.let { key ->
                    val previous = weakKeyToIndex[key]
                    if (previous != null) {
                        union.union(i, previous)
                    } else {
                        weakKeyToIndex[key] = i
                    }
                }
            }
        }

        val groups = linkedMapOf<Int, MutableList<UnifiedWatchlistSourceItem>>()
        for (i in items.indices) {
            groups.getOrPut(union.find(i)) { mutableListOf() } += items[i]
        }
        return groups
            .values
            .mapNotNull { group -> group.toMembership() }
            .sortedWith(compareBy<UnifiedWatchlistMembership> { it.title?.lowercase(Locale.US).orEmpty() }.thenBy { it.authorityKey })
    }

    private fun MutableList<UnifiedWatchlistSourceItem>.toMembership(): UnifiedWatchlistMembership? {
        if (isEmpty()) return null
        val representative = first()
        val strongKeys = flatMap { it.strongKeys() }
        val authorityKey = strongKeys.firstOrNull()
            ?: representative.weakKey()
            ?: return null
        val confidence = if (strongKeys.isNotEmpty()) {
            UnifiedWatchlistMembershipConfidence.STRONG
        } else {
            UnifiedWatchlistMembershipConfidence.LOW
        }
        val contentType = representative.canonicalContentType()

        return UnifiedWatchlistMembership(
            authorityKey = authorityKey,
            contentType = contentType,
            presentIn = sources(),
            sourceRefs = sourceRefs(),
            confidence = confidence,
            title = firstNotNullOfOrNull { it.title?.trim()?.takeIf(String::isNotEmpty) },
            year = firstNotNullOfOrNull { it.year },
            imdbId = firstNotNullOfOrNull { it.showImdbId ?: it.imdbId },
            tmdbId = firstNotNullOfOrNull { it.canonicalTmdbId() },
            tvdbId = firstNotNullOfOrNull { it.showTvdbId ?: it.tvdbId },
            traktId = firstNotNullOfOrNull { it.showTraktId ?: it.traktId },
            simklId = firstNotNullOfOrNull { it.showSimklId ?: it.simklId },
            showTmdbId = firstNotNullOfOrNull { it.showTmdbId },
            season = null,
            episode = null
        )
    }

    private fun List<UnifiedWatchlistSourceItem>.sources(): Set<UnifiedWatchlistSource> =
        mapTo(linkedSetOf()) { it.source }

    private fun List<UnifiedWatchlistSourceItem>.sourceRefs(): List<UnifiedWatchlistSourceRef> =
        map { item ->
            UnifiedWatchlistSourceRef(
                source = item.source,
                rawKey = item.rawKey,
                listKey = item.listKey
            )
        }.distinct()

    private fun UnifiedWatchlistSourceItem.strongKeys(): List<String> {
        val type = canonicalContentType()
        val typeKey = type.toAuthorityTypeKey()
        val keys = mutableListOf<String>()
        canonicalTmdbId()?.let { keys += "$typeKey:tmdb:$it" }
        (showImdbId ?: imdbId)?.normalizedImdb()?.let { keys += "$typeKey:imdb:$it" }
        (showTraktId ?: traktId)?.let { keys += "$typeKey:trakt:$it" }
        (showSimklId ?: simklId)?.let { keys += "$typeKey:simkl:$it" }
        (showTvdbId ?: tvdbId)?.let { keys += "$typeKey:tvdb:$it" }
        return keys
    }

    private fun UnifiedWatchlistSourceItem.weakKey(): String? {
        val normalizedTitle = title
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val normalizedYear = year ?: return null
        return "${canonicalContentType().toAuthorityTypeKey()}:title:$normalizedTitle:$normalizedYear"
    }

    private fun UnifiedWatchlistSourceItem.canonicalTmdbId(): Int? =
        showTmdbId ?: tmdbId

    private fun UnifiedWatchlistSourceItem.canonicalContentType(): ContentType =
        if (season != null || episode != null || contentType == ContentType.TV || contentType == ContentType.SERIES) {
            ContentType.SERIES
        } else {
            contentType
        }

    private fun ContentType.toAuthorityTypeKey(): String =
        when (this) {
            ContentType.SERIES,
            ContentType.TV -> "series"
            ContentType.MOVIE -> "movie"
            else -> toApiString()
        }

    private fun String.normalizedImdb(): String? =
        trim().lowercase(Locale.US).takeIf { it.isNotEmpty() }

    private class ItemUnion(size: Int) {
        private val parent = IntArray(size) { it }

        fun find(index: Int): Int {
            var root = index
            while (parent[root] != root) {
                root = parent[root]
            }
            var current = index
            while (parent[current] != current) {
                val next = parent[current]
                parent[current] = root
                current = next
            }
            return root
        }

        fun union(left: Int, right: Int) {
            val leftRoot = find(left)
            val rightRoot = find(right)
            if (leftRoot != rightRoot) {
                parent[rightRoot] = leftRoot
            }
        }
    }
}
