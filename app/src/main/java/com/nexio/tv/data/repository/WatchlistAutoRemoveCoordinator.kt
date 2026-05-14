package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.mdblist.MDBListLibraryService
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.UnifiedWatchlistMembership
import com.nexio.tv.domain.model.UnifiedWatchlistSource
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class WatchlistAutoRemoveCoordinator @Inject constructor(
    private val unifiedWatchlistRepository: Provider<UnifiedWatchlistRepository>,
    private val traktLibraryService: Provider<TraktLibraryService>,
    private val simklLibraryService: Provider<SimklLibraryService>,
    private val mdbListLibraryService: Provider<MDBListLibraryService>,
) {
    data class RemovePlan(
        val source: UnifiedWatchlistSource,
        val rawKey: String,
        val authorityKey: String
    ) {
        val dedupeKey: String = "$authorityKey|${source.name}|$rawKey"
    }

    private val lastSentByDedupeKey = linkedMapOf<String, Long>()

    suspend fun onCompletedScrobble(candidate: UnifiedWatchlistMembership) {
        val membership = findUnifiedMembership(candidate) ?: candidate
        val item = membership.toLibraryEntryInputOrNull() ?: return
        val nowMs = System.currentTimeMillis()
        val plans = synchronized(lastSentByDedupeKey) {
            val planned = planRemoveOperations(membership, nowMs, lastSentByDedupeKey)
            for (i in planned.indices) {
                lastSentByDedupeKey[planned[i].dedupeKey] = nowMs
            }
            trimLocked(nowMs)
            planned
        }

        for (i in plans.indices) {
            when (plans[i].source) {
                UnifiedWatchlistSource.TRAKT -> traktLibraryService.get().removeWatchlistItem(item)
                UnifiedWatchlistSource.SIMKL -> simklLibraryService.get().removeWatchlistItem(item)
                UnifiedWatchlistSource.MDBLIST -> mdbListLibraryService.get().removeWatchlistItem(item)
                UnifiedWatchlistSource.LOCAL -> Unit
            }
        }
    }

    private suspend fun findUnifiedMembership(candidate: UnifiedWatchlistMembership): UnifiedWatchlistMembership? {
        val memberships = runCatching { unifiedWatchlistRepository.get().memberships.first() }.getOrNull()
            ?: return null
        for (i in memberships.indices) {
            val membership = memberships[i]
            if (membership.contentType != candidate.contentType) continue
            if (membership.stronglyMatches(candidate)) return membership
        }
        return null
    }

    private fun trimLocked(nowMs: Long) {
        val iterator = lastSentByDedupeKey.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value > TTL_MS) iterator.remove()
        }
    }

    companion object {
        private const val TTL_MS = 120_000L

        fun planRemoveOperations(
            membership: UnifiedWatchlistMembership,
            nowMs: Long,
            lastSentByDedupeKey: Map<String, Long>
        ): List<RemovePlan> {
            if (membership.contentType != ContentType.MOVIE) return emptyList()

            val plans = ArrayList<RemovePlan>(membership.sourceRefs.size)
            for (i in membership.sourceRefs.indices) {
                val ref = membership.sourceRefs[i]
                if (!ref.isWatchlistRef()) continue
                if (ref.source == UnifiedWatchlistSource.LOCAL) continue
                val plan = RemovePlan(ref.source, ref.rawKey, membership.authorityKey)
                val lastSent = lastSentByDedupeKey[plan.dedupeKey]
                if (lastSent != null && nowMs - lastSent < TTL_MS) continue
                plans += plan
            }
            return plans
        }

        private fun com.nexio.tv.domain.model.UnifiedWatchlistSourceRef.isWatchlistRef(): Boolean {
            return when (source) {
                UnifiedWatchlistSource.TRAKT -> listKey == TraktLibraryService.WATCHLIST_KEY
                UnifiedWatchlistSource.SIMKL -> listKey == SimklLibraryService.WATCHLIST_KEY
                UnifiedWatchlistSource.MDBLIST -> listKey == MDBListLibraryService.WATCHLIST_KEY
                UnifiedWatchlistSource.LOCAL -> false
            }
        }
    }
}

private fun UnifiedWatchlistMembership.toLibraryEntryInputOrNull(): LibraryEntryInput? {
    if (contentType != ContentType.MOVIE) return null
    val stableItemId = when {
        traktId != null -> "trakt:$traktId"
        tmdbId != null -> "tmdb:$tmdbId"
        imdbId != null -> imdbId
        simklId != null -> "simkl:$simklId"
        else -> authorityKey.substringAfter("movie:", authorityKey)
    }.takeIf { it.isNotBlank() } ?: return null
    return LibraryEntryInput(
        itemId = stableItemId,
        itemType = "movie",
        title = title ?: stableItemId,
        year = year,
        traktId = traktId,
        imdbId = imdbId,
        tmdbId = tmdbId
    )
}

private fun UnifiedWatchlistMembership.stronglyMatches(other: UnifiedWatchlistMembership): Boolean =
    (imdbId != null && imdbId == other.imdbId) ||
        (tmdbId != null && tmdbId == other.tmdbId) ||
        (traktId != null && traktId == other.traktId) ||
        (simklId != null && simklId == other.simklId) ||
        (tvdbId != null && tvdbId == other.tvdbId)
