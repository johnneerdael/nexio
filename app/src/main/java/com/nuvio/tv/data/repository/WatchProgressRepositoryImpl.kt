package com.nuvio.tv.data.repository

import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class WatchProgressRepositoryImpl @Inject constructor(
    private val watchProgressPreferences: WatchProgressPreferences,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktProgressService: TraktProgressService
) : WatchProgressRepository {

    override val allProgress: Flow<List<WatchProgress>>
        get() = traktAuthDataStore.isAuthenticated
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (isAuthenticated) {
                    traktProgressService.observeAllProgress()
                } else {
                    watchProgressPreferences.allProgress
                }
            }

    override val continueWatching: Flow<List<WatchProgress>>
        get() = allProgress.map { list -> list.filter { it.isInProgress() } }

    override fun getProgress(contentId: String): Flow<WatchProgress?> {
        return traktAuthDataStore.isAuthenticated
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (isAuthenticated) {
                    allProgress.map { items ->
                        items
                            .filter { it.contentId == contentId }
                            .maxByOrNull { it.lastWatched }
                    }
                } else {
                    watchProgressPreferences.getProgress(contentId)
                }
            }
    }

    override fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?> {
        return traktAuthDataStore.isAuthenticated
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (isAuthenticated) {
                    allProgress.map { items ->
                        items.firstOrNull {
                            it.contentId == contentId && it.season == season && it.episode == episode
                        }
                    }
                } else {
                    watchProgressPreferences.getEpisodeProgress(contentId, season, episode)
                }
            }
    }

    override fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return traktAuthDataStore.isAuthenticated
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (isAuthenticated) {
                    combine(
                        traktProgressService.observeEpisodeProgress(contentId),
                        allProgress.map { items ->
                            items.filter { it.contentId == contentId && it.season != null && it.episode != null }
                        }
                    ) { remoteMap, liveEpisodes ->
                        val merged = remoteMap.toMutableMap()
                        liveEpisodes.forEach { episodeProgress ->
                            val season = episodeProgress.season ?: return@forEach
                            val episode = episodeProgress.episode ?: return@forEach
                            merged[season to episode] = episodeProgress
                        }
                        merged
                    }.distinctUntilChanged()
                } else {
                    watchProgressPreferences.getAllEpisodeProgress(contentId)
                }
            }
    }

    override suspend fun saveProgress(progress: WatchProgress) {
        if (traktAuthDataStore.isAuthenticated.first()) {
            traktProgressService.applyOptimisticProgress(progress)
            return
        }
        watchProgressPreferences.saveProgress(progress)
    }

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        if (traktAuthDataStore.isAuthenticated.first()) {
            traktProgressService.applyOptimisticRemoval(contentId, season, episode)
            traktProgressService.removeProgress(contentId, season, episode)
            return
        }
        watchProgressPreferences.removeProgress(contentId, season, episode)
    }

    override suspend fun markAsCompleted(progress: WatchProgress) {
        if (traktAuthDataStore.isAuthenticated.first()) {
            traktProgressService.applyOptimisticProgress(
                progress.copy(
                    position = progress.duration,
                    progressPercent = 100f,
                    lastWatched = System.currentTimeMillis()
                )
            )
            return
        }
        watchProgressPreferences.markAsCompleted(progress)
    }

    override suspend fun clearAll() {
        if (traktAuthDataStore.isAuthenticated.first()) {
            traktProgressService.clearOptimistic()
            return
        }
        watchProgressPreferences.clearAll()
    }
}
