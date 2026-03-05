package com.nexio.tv.data.repository

import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.WatchProgressPreferences
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class WatchProgressRepositoryImpl @Inject constructor(
    private val watchProgressPreferences: WatchProgressPreferences,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktProgressService: TraktProgressService,
    private val metaRepository: MetaRepository
) : WatchProgressRepository {
    private data class EpisodeMetadata(
        val title: String?,
        val thumbnail: String?
    )

    private data class ContentMetadata(
        val name: String?,
        val poster: String?,
        val backdrop: String?,
        val logo: String?,
        val episodes: Map<Pair<Int, Int>, EpisodeMetadata>
    )

    private val metadataState = MutableStateFlow<Map<String, ContentMetadata>>(emptyMap())
    private val metadataMutex = Mutex()
    private val inFlightMetadataKeys = mutableSetOf<String>()
    private val metadataHydrationLimit = 30
    private val metadataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun hydrateMetadata(progressList: List<WatchProgress>) {
        val sorted = progressList.sortedByDescending { it.lastWatched }
        val uniqueByContent = linkedMapOf<String, WatchProgress>()
        sorted.forEach { progress ->
            if (uniqueByContent.size < metadataHydrationLimit) {
                uniqueByContent.putIfAbsent(progress.contentId, progress)
            }
        }

        uniqueByContent.values.forEach { progress ->
            val contentId = progress.contentId
            if (contentId.isBlank()) return@forEach
            if (metadataState.value.containsKey(contentId)) return@forEach

            metadataScope.launch {
                val shouldFetch = metadataMutex.withLock {
                    if (metadataState.value.containsKey(contentId)) return@withLock false
                    if (inFlightMetadataKeys.contains(contentId)) return@withLock false
                    inFlightMetadataKeys.add(contentId)
                    true
                }
                if (!shouldFetch) return@launch

                try {
                    val metadata = fetchContentMetadata(
                        contentId = contentId,
                        contentType = progress.contentType
                    ) ?: return@launch
                    metadataState.update { current ->
                        current + (contentId to metadata)
                    }
                } finally {
                    metadataMutex.withLock {
                        inFlightMetadataKeys.remove(contentId)
                    }
                }
            }
        }
    }

    private suspend fun fetchContentMetadata(
        contentId: String,
        contentType: String
    ): ContentMetadata? {
        val typeCandidates = buildList {
            val normalized = contentType.lowercase()
            if (normalized.isNotBlank()) add(normalized)
            if (normalized in listOf("series", "tv")) {
                add("series")
                add("tv")
            } else {
                add("movie")
            }
        }.distinct()

        val idCandidates = buildList {
            add(contentId)
            if (contentId.startsWith("tmdb:")) add(contentId.substringAfter(':'))
            if (contentId.startsWith("trakt:")) add(contentId.substringAfter(':'))
        }.distinct()

        for (type in typeCandidates) {
            for (candidateId in idCandidates) {
                val result = withTimeoutOrNull(3500) {
                    metaRepository.getMetaFromAllAddons(type = type, id = candidateId)
                        .first { it !is NetworkResult.Loading }
                } ?: continue

                val meta = (result as? NetworkResult.Success)?.data ?: continue
                val episodes = meta.videos
                    .mapNotNull { video ->
                        val season = video.season ?: return@mapNotNull null
                        val episode = video.episode ?: return@mapNotNull null
                        (season to episode) to EpisodeMetadata(
                            title = video.title,
                            thumbnail = video.thumbnail
                        )
                    }
                    .toMap()

                return ContentMetadata(
                    name = meta.name,
                    poster = meta.poster,
                    backdrop = meta.background,
                    logo = meta.logo,
                    episodes = episodes
                )
            }
        }
        return null
    }

    private fun enrichWithMetadata(
        progress: WatchProgress,
        metadataMap: Map<String, ContentMetadata>
    ): WatchProgress {
        val metadata = metadataMap[progress.contentId] ?: return progress
        val episodeMeta = if (progress.season != null && progress.episode != null) {
            metadata.episodes[progress.season to progress.episode]
        } else {
            null
        }
        val shouldOverrideName = progress.name.isBlank() || progress.name == progress.contentId
        val backdrop = progress.backdrop
            ?: metadata.backdrop
            ?: episodeMeta?.thumbnail

        return progress.copy(
            name = if (shouldOverrideName) metadata.name ?: progress.name else progress.name,
            poster = progress.poster ?: metadata.poster,
            backdrop = backdrop,
            logo = progress.logo ?: metadata.logo,
            episodeTitle = progress.episodeTitle ?: episodeMeta?.title
        )
    }

    override val allProgress: Flow<List<WatchProgress>>
        get() = traktAuthDataStore.isEffectivelyAuthenticated
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (isAuthenticated) {
                    combine(
                        traktProgressService.observeAllProgress()
                            .onStart {
                                // Emit local-cache-backed continue watching immediately on app start
                                // while the first Trakt snapshot is still loading.
                                emit(emptyList())
                            },
                        watchProgressPreferences.allRawProgress,
                        metadataState
                    ) { remoteItems, localItems, metadataMap ->
                        val merged = mergeProgressLists(remoteItems, localItems)
                        hydrateMetadata(merged)
                        merged.map { enrichWithMetadata(it, metadataMap) }
                    }
                } else {
                    flowOf(emptyList())
                }
            }

    override val continueWatching: Flow<List<WatchProgress>>
        get() = allProgress.map { list -> list.filter { it.isInProgress() } }

    override fun getProgress(contentId: String): Flow<WatchProgress?> {
        return traktAuthDataStore.isEffectivelyAuthenticated
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (isAuthenticated) {
                    allProgress.map { items ->
                        items
                            .filter { it.contentId == contentId }
                            .maxByOrNull { it.lastWatched }
                    }
                } else {
                    flowOf(null)
                }
            }
    }

    override fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?> {
        return traktAuthDataStore.isEffectivelyAuthenticated
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (isAuthenticated) {
                    allProgress.map { items ->
                        items.firstOrNull {
                            it.contentId == contentId && it.season == season && it.episode == episode
                        }
                    }
                } else {
                    flowOf(null)
                }
            }
    }

    override fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return traktAuthDataStore.isEffectivelyAuthenticated
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
                    flowOf(emptyMap())
                }
            }
    }

    override fun isWatched(contentId: String, season: Int?, episode: Int?): Flow<Boolean> {
        return traktAuthDataStore.isEffectivelyAuthenticated
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (!isAuthenticated) {
                    return@flatMapLatest flowOf(false)
                }

                if (season != null && episode != null) {
                    traktProgressService.observeEpisodeProgress(contentId)
                        .map { progressMap ->
                            progressMap[season to episode]?.isCompleted() == true
                        }
                        .distinctUntilChanged()
                } else {
                    traktProgressService.observeMovieWatched(contentId)
                }
            }
    }

    override suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean) {
        if (!traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            return
        }
        traktProgressService.applyOptimisticProgress(progress)
        watchProgressPreferences.saveProgress(progress)
    }

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        val isAuthenticated = traktAuthDataStore.isEffectivelyAuthenticated.first()
        if (!isAuthenticated) return
        traktProgressService.applyOptimisticRemoval(contentId, season, episode)
        traktProgressService.removeProgress(contentId, season, episode)
        watchProgressPreferences.removeProgress(contentId, season, episode)
    }

    override suspend fun removeFromHistory(contentId: String, season: Int?, episode: Int?) {
        if (!traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            return
        }
        traktProgressService.removeFromHistory(contentId, season, episode)
        watchProgressPreferences.removeProgress(contentId, season, episode)
    }

    override suspend fun markAsCompleted(progress: WatchProgress) {
        if (!traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            return
        }
        val now = System.currentTimeMillis()
        val duration = progress.duration.takeIf { it > 0L } ?: 1L
        val completed = progress.copy(
            position = duration,
            duration = duration,
            progressPercent = 100f,
            lastWatched = now
        )
        traktProgressService.applyOptimisticProgress(completed)
        runCatching {
            traktProgressService.markAsWatched(
                progress = completed,
                title = completed.name.takeIf { it.isNotBlank() },
                year = null
            )
        }.onFailure {
            traktProgressService.applyOptimisticRemoval(
                contentId = completed.contentId,
                season = completed.season,
                episode = completed.episode
            )
            throw it
        }
    }

    override suspend fun clearAll() {
        traktProgressService.clearOptimistic()
        watchProgressPreferences.clearAll()
    }

    private fun progressKey(progress: WatchProgress): String {
        return if (progress.season != null && progress.episode != null) {
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        } else {
            progress.contentId
        }
    }

    private fun mergeProgressLists(
        remoteItems: List<WatchProgress>,
        localItems: List<WatchProgress>
    ): List<WatchProgress> {
        val mergedByKey = linkedMapOf<String, WatchProgress>()

        fun upsert(progress: WatchProgress) {
            val key = progressKey(progress)
            val existing = mergedByKey[key]
            if (existing == null || shouldPreferProgress(existing, progress)) {
                mergedByKey[key] = progress
            }
        }

        remoteItems.forEach(::upsert)
        localItems.forEach(::upsert)

        return mergedByKey.values
            .sortedByDescending { it.lastWatched }
    }

    private fun shouldPreferProgress(existing: WatchProgress, candidate: WatchProgress): Boolean {
        val timeDiffMs = candidate.lastWatched - existing.lastWatched
        if (timeDiffMs > 1_000L) return true
        if (timeDiffMs < -1_000L) return false

        val candidateInProgress = candidate.isInProgress()
        val existingInProgress = existing.isInProgress()
        if (candidateInProgress && !existingInProgress) return true
        if (!candidateInProgress && existingInProgress) return false

        val candidateIsPlayback = candidate.source == WatchProgress.SOURCE_TRAKT_PLAYBACK
        val existingIsPlayback = existing.source == WatchProgress.SOURCE_TRAKT_PLAYBACK
        if (candidateIsPlayback && !existingIsPlayback) return true
        if (!candidateIsPlayback && existingIsPlayback) return false

        return false
    }
}
