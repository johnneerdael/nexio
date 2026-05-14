package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.UnifiedWatchlistMembership
import com.nexio.tv.domain.model.UnifiedWatchlistSource
import com.nexio.tv.domain.model.UnifiedWatchlistSourceItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnifiedWatchlistRepository @Inject constructor(
    private val traktLibraryService: TraktLibraryService,
    private val simklLibraryService: SimklLibraryService
) {
    val memberships: Flow<List<UnifiedWatchlistMembership>> = combine(
        traktLibraryService.observeAllItems(),
        simklLibraryService.observeAllItems()
    ) { traktItems, simklItems ->
        val sourceItems = ArrayList<UnifiedWatchlistSourceItem>(traktItems.size + simklItems.size)
        for (i in traktItems.indices) {
            val item = traktItems[i]
            if (TraktLibraryService.WATCHLIST_KEY in item.listKeys) {
                sourceItems += item.toUnifiedWatchlistSourceItem(UnifiedWatchlistSource.TRAKT, TraktLibraryService.WATCHLIST_KEY)
            }
        }
        for (i in simklItems.indices) {
            val item = simklItems[i]
            if (SimklLibraryService.WATCHLIST_KEY in item.listKeys) {
                sourceItems += item.toUnifiedWatchlistSourceItem(UnifiedWatchlistSource.SIMKL, SimklLibraryService.WATCHLIST_KEY)
            }
        }
        UnifiedWatchlistMembershipReducer.reduce(sourceItems)
    }.distinctUntilChanged()

    private fun LibraryEntry.toUnifiedWatchlistSourceItem(
        source: UnifiedWatchlistSource,
        listKey: String
    ): UnifiedWatchlistSourceItem {
        val type = ContentType.fromString(type).canonicalLibraryContentType()
        return UnifiedWatchlistSourceItem(
            source = source,
            rawKey = id,
            contentType = type,
            title = name,
            year = releaseInfo?.extractYear(),
            imdbId = imdbId ?: id.takeIf { it.startsWith("tt", ignoreCase = true) },
            tmdbId = tmdbId,
            traktId = traktId,
            listKey = listKey
        )
    }

    private fun ContentType.canonicalLibraryContentType(): ContentType =
        when (this) {
            ContentType.TV -> ContentType.SERIES
            else -> this
        }

    private fun String.extractYear(): Int? =
        Regex("""\b(19|20)\d{2}\b""").find(this)?.value?.toIntOrNull()
}
